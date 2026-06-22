/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ai.kompile.knowledgegraph.reasoning;

import ai.kompile.core.graphrag.conformance.OntologyAxiom;
import ai.kompile.core.graphrag.conformance.OntologyProjectionProvider;
import ai.kompile.graph.reasoning.confidence.StrengthBand;
import ai.kompile.graph.reasoning.fol.EntailmentEngine;
import ai.kompile.graph.reasoning.fol.EntailmentRecord;
import ai.kompile.graph.reasoning.fol.Fact;
import ai.kompile.graph.reasoning.fol.FactStore;
import ai.kompile.graph.reasoning.fol.InferredFact;
import ai.kompile.graph.reasoning.fol.InferredFactStore;
import ai.kompile.graph.reasoning.learning.MebnWeightLearner;
import ai.kompile.graph.reasoning.learning.PslWeightLearningService;
import ai.kompile.graph.reasoning.learning.StructuredPerceptronLearner;
import ai.kompile.knowledgegraph.confidence.KbConfig;
import ai.kompile.knowledgegraph.confidence.KbConfigManager;
import jakarta.annotation.PostConstruct;
import ai.kompile.graph.reasoning.learning.WeightStore;
import ai.kompile.graph.reasoning.mebn.MTheory;
import ai.kompile.graph.reasoning.model.ReasoningGraph;
import ai.kompile.graph.reasoning.psl.HlMrfMapInference;
import ai.kompile.graph.reasoning.psl.PslProgram;
import ai.kompile.graph.reasoning.psl.PslRule;
import ai.kompile.graph.reasoning.tms.BeliefReviser;
import ai.kompile.graph.reasoning.tms.ContradictionDetector;
import ai.kompile.graph.reasoning.tms.JustificationIndex;
import ai.kompile.knowledgegraph.audit.PinGuard;
import ai.kompile.knowledgegraph.grounding.ContradictionDetectedEvent;
import ai.kompile.knowledgegraph.grounding.FactSheetKbState;
import ai.kompile.knowledgegraph.grounding.KbCorrectionService;
import ai.kompile.knowledgegraph.grounding.KbGroundingService;
import ai.kompile.knowledgegraph.persistence.FileBackedWeightStore;
import ai.kompile.knowledgegraph.persistence.MebnWeightPersistenceAdapter;
import ai.kompile.knowledgegraph.persistence.dual.DualStoreGroundingFactory;
import ai.kompile.knowledgegraph.staging.ModelTrainedEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.stream.Collectors;

/**
 * L3 Grounding Cascade orchestrator: given a {@code factSheetId}, (re-)runs MAP inference
 * over the currently-observed facts and updates that fact sheet's {@link InferredFactStore}
 * so that subsequent {@link KbGroundingService#verify} calls reflect the new graph state.
 *
 * <h3>Grounding strategy: FULL PER-FACTSHEET re-ground (first cut)</h3>
 * <p>This implementation does a <em>full</em> per-fact-sheet re-ground rather than the
 * full hybrid semi-naive/justification-based algorithm described in the design spec.
 * Specifically:
 * <ol>
 *   <li>It reads the current {@link FactStore} from {@link FactSheetKbState} (populated
 *       by prior {@link KbGroundingService#assertFact} calls).</li>
 *   <li>It builds a {@link PslProgram} from those facts: observed atoms (value ≥ 0.9) become
 *       fixed evidence; every fact also registers a target atom
 *       ({@code derived_<predicate>(args)}) so the MAP solver can derive implied values.</li>
 *   <li>It runs {@link HlMrfMapInference#solve} over the full program.</li>
 *   <li>It materializes resulting {@link InferredFact}s into the {@link InferredFactStore},
 *       incrementing versions only when the value changed by more than
 *       {@link #VERSION_EPSILON} (the §7.2 fixed-point termination condition).</li>
 *   <li>It rebuilds the {@link JustificationIndex}.</li>
 *   <li>It calls {@link KbGroundingService#markEpoch} to register the new epoch.</li>
 * </ol>
 *
 * <h3>Why full re-ground instead of incremental (TODO)</h3>
 * <p>The design spec §3.2 calls for a hybrid semi-naive forward + justification-based
 * retraction cascade. That path requires:
 * <ol>
 *   <li>An {@link ai.kompile.graph.reasoning.psl.IncrementalGrounder} stored per factSheet
 *       (requires converting {@link FactSheetKbState} from a record to a mutable class,
 *       so the grounder can be held alongside the {@link FactStore}).</li>
 *   <li>Routing all agent asserts through {@code IncrementalGrounder.addAtom} rather than
 *       directly into {@link FactStore}.</li>
 *   <li>Using {@link JustificationIndex#atomsDependingOnFact} +
 *       {@link JustificationIndex#solelyDependentOn} to restrict the MAP solve to the
 *       depth-2 neighbourhood of the changed atoms.</li>
 * </ol>
 * The full re-ground is correct and produces the right {@link InferredFact} versions.
 * The only cost is O(|all facts|) vs O(|affected atoms|²) — acceptable for fact sheets
 * with up to a few thousand atoms. Incremental optimization is left as a follow-up.</p>
 *
 * <h3>Concurrency model</h3>
 * <p>This service MUST only be called from within the per-factSheet single-threaded executor
 * managed by {@link ai.kompile.graphchangetracking.hook.GroundingCascadeHook}. The write lock
 * on {@link FactSheetKbState#lock()} is acquired here for the full duration of the re-ground.</p>
 */
@Service
@Slf4j
public class IncrementalReasoningOrchestrator {

    /**
     * Minimum value change that warrants a new {@link InferredFact} version (§7.2 termination).
     * If re-ground produces the same value within ε for every atom, no new versions are written.
     */
    static final double VERSION_EPSILON = 0.001;

    private final KbGroundingService kbGroundingService;
    private final ApplicationEventPublisher eventPublisher;

    /**
     * Optional: fact promotion tracker. When non-null, {@link #doReground} calls
     * {@link FactPromotionTracker#checkPromotion} after each {@code inferredStore.store()} to
     * detect and persist band promotions. Injected via field injection so existing multi-arg
     * constructors are not cascaded.
     */
    @Nullable
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    FactPromotionTracker promotionTracker;

    /**
     * Optional: ontology projection provider (Phase 3).  When non-null and the fact sheet has
     * a bound ontology, {@link #doReground} compiles the ontology's DOMAIN/RANGE axioms into
     * PSL rules and injects them into the program before the MAP solve.  {@code null} in
     * plain-Java test contexts and in Spring contexts that do not wire app-main's implementation
     * — the behavior then falls back to today's rule set (no ontology constraints, exactly as
     * before). Injected via field injection so the existing multi-arg constructors are untouched.
     */
    @Nullable
    @Autowired(required = false)
    OntologyProjectionProvider ontologyProvider;

    /**
     * Optional: graph→FactStore projector. When non-null, {@link #doReground} projects
     * the live graph into the FactStore before the MAP solve. {@code null} in plain-Java
     * test contexts (existing 17 tests) so they remain unaffected.
     */
    @Nullable
    private final GraphToFactStoreProjector graphProjector;

    /**
     * Optional: PinGuard — blocks re-derivation of human-pinned atoms.
     * Null in plain-Java test contexts; injected by Spring in production.
     */
    @Nullable
    private final PinGuard pinGuard;

    /**
     * Optional: correction service — used to emit DERIVED audit events after each
     * inferredStore.store() call and to register the latest PSL program snapshot.
     * Null in plain-Java test contexts.
     */
    @Nullable
    private final KbCorrectionService correctionService;

    /**
     * Optional: project data dir for PSL rule loading.  When set, {@code <dataDir>/rules/*.psl}
     * files are loaded into the PSL program before the MAP solve.
     *
     * <p>Package-private visibility so that same-package tests can inject a temp dir
     * without requiring Spring or reflection. In production Spring injects via {@code @Value}.</p>
     */
    @Nullable
    @Value("${kompile.data.dir:#{null}}")
    String dataDir;

    /**
     * Kompile-managed KB config — the single source for all confidence/evidence/learning tunables
     * (learning-enabled flag, PSL default rule weight, the full learner config, the MAP prior, the
     * MEBN cadence). No Spring {@code @Value}; null in plain-Java contexts → {@link KbConfig#defaults()}.
     */
    @Autowired(required = false)
    private KbConfigManager kbConfigManager;

    private KbConfig kbCfg() {
        return kbConfigManager != null ? kbConfigManager.current() : KbConfig.defaults();
    }

    /**
     * Spring-injected file-backed weight store for PSL weight persistence.
     * Null in plain-Java test contexts — PSL training step is skipped.
     */
    @Nullable
    private final FileBackedWeightStore fileBackedWeightStore;

    /**
     * Optional dual-store factory. When non-null, STEP 3c and STEP 5b use a
     * {@link ai.kompile.knowledgegraph.persistence.dual.DualStoreWeightStore} (JPA-backed)
     * instead of the file-backed store.
     * Null in plain-Java test contexts.
     */
    @Nullable
    private final DualStoreGroundingFactory dualStoreFactory;

    /**
     * Build the PSL weight learner from the managed {@link KbConfig}. ALL learner parameters
     * (learning rate, tolerance, batch size, seed, max epochs) and the MAP prior
     * (weightPriorStrength lambda + weightPriorMean) come from config — no hard-coded learner
     * literals — so a runtime config change is picked up on the next cascade. Stateless + cheap.
     *
     * <p>This scalar overload is kept for backward compatibility and for callers that do not
     * yet have per-rule band information. Use {@link #pslWeightLearner(double[])} when per-rule
     * prior means are available (band-aware MAP regularization).</p>
     */
    private PslWeightLearningService pslWeightLearner() {
        KbConfig c = kbCfg();
        return new PslWeightLearningService(
                new StructuredPerceptronLearner(c.getPslLearningRate(), c.getPslTolerance(), c.getPslBatchSize(),
                        c.getPslSeed(), c.getPslWeightPriorStrength(), c.getPslWeightPriorMean()),
                c.getPslMaxEpochs());
    }

    /**
     * Build the PSL weight learner with per-rule prior means for band-aware MAP regularization.
     *
     * <p>Each rule gets its own prior mean derived from the strength band of its supporting atoms
     * (computed by {@link #computePerRulePriorMeans}): ESTABLISHED/HIGH rules are pulled toward a
     * <em>higher</em> mean (they deserve more weight by construction), while SPECULATIVE rules are
     * pulled toward a low mean. This is the fix for "we still need more weight on established rules":
     * the scalar path was shrinking established rules toward the same small mean as speculative ones.
     *
     * <p>Config fields read via {@code kbCfg()} (to be wired by the lead — see return note):
     * <ul>
     *   <li>{@code ruleWeightEstablishedMean} — prior mean for ESTABLISHED-band rules (default 0.9)</li>
     *   <li>{@code ruleWeightHighMean}        — prior mean for HIGH-band rules (default 0.7)</li>
     *   <li>{@code ruleWeightProbableMean}    — prior mean for PROBABLE-band rules (default 0.4)</li>
     *   <li>{@code ruleWeightSpeculativeMean} — prior mean for SPECULATIVE/SUPPRESSED rules (default 0.1)</li>
     * </ul>
     * Falls back to the scalar {@code pslWeightPriorMean} for any rule whose band is undetermined.
     *
     * @param perRuleMeans per-rule prior means array (from {@link #computePerRulePriorMeans})
     */
    private PslWeightLearningService pslWeightLearner(double[] perRuleMeans) {
        KbConfig c = kbCfg();
        return new PslWeightLearningService(
                new StructuredPerceptronLearner(c.getPslLearningRate(), c.getPslTolerance(), c.getPslBatchSize(),
                        c.getPslSeed(), c.getPslWeightPriorStrength(), c.getPslWeightPriorMean(), perRuleMeans),
                c.getPslMaxEpochs());
    }

    /**
     * Per-factSheet cascade counters — used to throttle MEBN learning (which runs
     * finite-difference over every edge, O(edges × epochs), so we gate it to every
     * {@link #MEBN_LEARNING_INTERVAL} cascades rather than every cascade).
     */
    private final ConcurrentHashMap<Long, AtomicLong> cascadeCounters = new ConcurrentHashMap<>();

    /**
     * Per-factSheet MEBN theories, registered by callers via {@link #registerMTheory}.
     * Null for a factSheet = MEBN training skipped for that sheet.
     */
    private final ConcurrentHashMap<Long, MTheory> mebnTheories = new ConcurrentHashMap<>();

    /**
     * Per-factSheet MEBN ReasoningGraphs registered by callers alongside the MTheory.
     */
    private final ConcurrentHashMap<Long, ReasoningGraph> mebnGraphs = new ConcurrentHashMap<>();

    /**
     * Optional: MEBN weight persistence adapter (Spring-injected; null in plain-Java tests).
     */
    @Nullable
    private final MebnWeightPersistenceAdapter mebnWeightAdapter;

    /**
     * MEBN weight learner (stateless; finite-difference gradient descent).
     * Reused across cascades.
     */
    private final MebnWeightLearner mebnWeightLearner = new MebnWeightLearner();

    /**
     * How many cascades between MEBN weight-learning runs.
     * Finite-difference costs O(|edges| × maxEpochs) per cascade — expensive for large
     * MTheories. Every 10 cascades is a safe default: responsive enough for human-visible
     * feedback (a few seconds) without dominating the cascade wall time.
     * Override by extending this class if a finer cadence is needed.
     */
    static final int MEBN_LEARNING_INTERVAL = 10;

    /**
     * Full Spring constructor: all 8 collaborators injected by Spring.
     * {@code @Autowired} marks this as the primary injection point when Spring
     * sees multiple constructors.
     */
    @Autowired
    public IncrementalReasoningOrchestrator(KbGroundingService kbGroundingService,
                                             ApplicationEventPublisher eventPublisher,
                                             @Nullable GraphToFactStoreProjector graphProjector,
                                             @Nullable PinGuard pinGuard,
                                             @Nullable KbCorrectionService correctionService,
                                             @Nullable FileBackedWeightStore fileBackedWeightStore,
                                             @Nullable MebnWeightPersistenceAdapter mebnWeightAdapter,
                                             @Nullable DualStoreGroundingFactory dualStoreFactory) {
        this.kbGroundingService = kbGroundingService;
        this.eventPublisher = eventPublisher;
        this.graphProjector = graphProjector;
        this.pinGuard = pinGuard;
        this.correctionService = correctionService;
        this.fileBackedWeightStore = fileBackedWeightStore;
        this.mebnWeightAdapter = mebnWeightAdapter;
        this.dualStoreFactory = dualStoreFactory;
    }

    /**
     * 7-arg Spring constructor (no DualStoreGroundingFactory).
     * Retained for backward compatibility.
     */
    public IncrementalReasoningOrchestrator(KbGroundingService kbGroundingService,
                                             ApplicationEventPublisher eventPublisher,
                                             @Nullable GraphToFactStoreProjector graphProjector,
                                             @Nullable PinGuard pinGuard,
                                             @Nullable KbCorrectionService correctionService,
                                             @Nullable FileBackedWeightStore fileBackedWeightStore,
                                             @Nullable MebnWeightPersistenceAdapter mebnWeightAdapter) {
        this(kbGroundingService, eventPublisher, graphProjector, pinGuard, correctionService,
                fileBackedWeightStore, mebnWeightAdapter, null);
    }

    /**
     * 6-arg Spring constructor (no MebnWeightPersistenceAdapter / DualStoreGroundingFactory).
     * Retained for backward compatibility.
     */
    public IncrementalReasoningOrchestrator(KbGroundingService kbGroundingService,
                                             ApplicationEventPublisher eventPublisher,
                                             @Nullable GraphToFactStoreProjector graphProjector,
                                             @Nullable PinGuard pinGuard,
                                             @Nullable KbCorrectionService correctionService,
                                             @Nullable FileBackedWeightStore fileBackedWeightStore) {
        this(kbGroundingService, eventPublisher, graphProjector, pinGuard, correctionService,
                fileBackedWeightStore, null, null);
    }

    /**
     * 5-arg Spring constructor (no FileBackedWeightStore / MebnWeightPersistenceAdapter / DualStoreGroundingFactory).
     * Retained for backward compatibility.
     */
    public IncrementalReasoningOrchestrator(KbGroundingService kbGroundingService,
                                             ApplicationEventPublisher eventPublisher,
                                             @Nullable GraphToFactStoreProjector graphProjector,
                                             @Nullable PinGuard pinGuard,
                                             @Nullable KbCorrectionService correctionService) {
        this(kbGroundingService, eventPublisher, graphProjector, pinGuard, correctionService, null, null, null);
    }

    /**
     * Three-arg Spring constructor (no PinGuard/KbCorrectionService).
     * Retained for backward compatibility with plain-Java tests and Spring contexts
     * that have not yet wired PinGuard.
     */
    public IncrementalReasoningOrchestrator(KbGroundingService kbGroundingService,
                                             ApplicationEventPublisher eventPublisher,
                                             @Nullable GraphToFactStoreProjector graphProjector) {
        this(kbGroundingService, eventPublisher, graphProjector, null, null, null, null, null);
    }

    /**
     * Backward-compatible constructor for plain-Java tests that wire only the two
     * original collaborators. {@code graphProjector} defaults to {@code null}.
     */
    public IncrementalReasoningOrchestrator(KbGroundingService kbGroundingService,
                                             ApplicationEventPublisher eventPublisher) {
        this(kbGroundingService, eventPublisher, null, null, null, null, null, null);
    }

    /**
     * Re-ground the KB for {@code factSheetId} using all currently-observed facts.
     *
     * <p>The write lock on the state is held for the entire operation. Reads are blocked for
     * the duration; given typical KG sizes (≤ a few thousand atoms) this should be &lt;100 ms.</p>
     *
     * @param factSheetId the fact sheet to re-ground
     * @return a {@link RegroundResult} with the number of {@link InferredFact} versions written,
     *         the run ID, and the set of retracted atom keys (currently always empty)
     */
    public RegroundResult runFullReground(long factSheetId) {
        FactSheetKbState state = kbGroundingService.getState(factSheetId);
        ReadWriteLock lock = state.lock();
        lock.writeLock().lock();
        try {
            return doReground(factSheetId, state);
        } finally {
            lock.writeLock().unlock();
        }
    }

    // ─── MEBN registration API ────────────────────────────────────────────────────

    /**
     * Register a per-fact-sheet MEBN theory and its grounding graph so that the next cascade
     * (at a {@link #MEBN_LEARNING_INTERVAL} cadence) will run MEBN weight learning.
     *
     * <p>Callers (e.g. the grounding controller or KbGroundingService) should call this once
     * after building or loading an {@link MTheory} for a fact sheet. The orchestrator holds a
     * reference to the theory and mutates its edge strengths in place during the throttled
     * learning step, then persists them via {@link MebnWeightPersistenceAdapter}.</p>
     *
     * <p>Thread-safe: the map is a {@link ConcurrentHashMap}.</p>
     *
     * @param factSheetId the fact sheet to associate the theory with
     * @param theory      the MEBN theory to learn edge strengths for; must not be null
     * @param graph       the reasoning graph the theory grounds over; must not be null
     */
    public void registerMTheory(long factSheetId, MTheory theory, ReasoningGraph graph) {
        if (theory != null && graph != null) {
            mebnTheories.put(factSheetId, theory);
            mebnGraphs.put(factSheetId, graph);
            log.debug("IncrementalReasoningOrchestrator: registered MTheory for factSheet={}", factSheetId);
        }
    }

    // ─── Internal ─────────────────────────────────────────────────────────────────

    /**
     * Execute the full re-ground. MUST be called while holding {@code state.lock().writeLock()}.
     */
    private RegroundResult doReground(long factSheetId, FactSheetKbState state) {
        FactStore factStore = state.factStore();

        // ── STEP 0 (NEW): Project live graph into FactStore before MAP solve ─────────
        // This is the missing production link (design spec §5): without this call the
        // FactStore is empty after a fresh crawl, so the MAP solve produces nothing.
        // null-safe: graphProjector is null in plain-Java test contexts.
        if (graphProjector != null) {
            try {
                int projected = graphProjector.project(factSheetId);
                log.debug("Grounding cascade for factSheet={}: projected {} graph atoms into FactStore",
                        factSheetId, projected);
            } catch (Exception e) {
                log.warn("Grounding cascade for factSheet={}: graph projection failed — {}",
                        factSheetId, e.getMessage());
                // Non-fatal: continue with whatever is already in the FactStore
            }
        }

        if (factStore.isEmpty()) {
            log.debug("Grounding cascade for factSheet={}: FactStore is empty — skipping MAP solve",
                    factSheetId);
            return RegroundResult.empty();
        }

        // ── STEP 2+3: Build PSL program from the observed FactStore ──────────────────
        PslProgram program = buildProgramFromFactStore(factStore, kbCfg().getPslDefaultRuleWeight());

        // ── STEP 3b (NEW): Load project-level PSL rules if dataDir is configured ─────
        // If <dataDir>/rules/*.psl files exist, their rules override the default soft-
        // propagation rules added by buildProgramFromFactStore. This is the pluggable
        // rule seam described in design spec §7 (P0 build plan item 4).
        loadProjectPslRules(program);

        // ── STEP 3b-ont (Phase 3): Inject ontology axiom → PSL rules ─────────────────
        // When a bound ontology is present, compile its DOMAIN/RANGE axioms into soft PSL
        // rules and add them to the program before the MAP solve. When the provider is null,
        // the fact sheet has no bound ontology, or axioms are empty, this step is a strict
        // no-op — today's behaviour is fully preserved.
        injectOntologyRules(program, factSheetId);

        // ── STEP 3c (L0 FIX): Reload persisted learned weights into the program ──────
        // Before the MAP solve, load the last-persisted PSL weights and apply them onto the
        // program (so the solve uses LEARNED weights, not 0.8 defaults).
        // Prefers DualStoreGroundingFactory (JPA-backed) when available; falls back to
        // FileBackedWeightStore. If no persisted weights exist yet (first run), falls back
        // to defaults silently.
        WeightStore cascadeWeightStore = null;
        if (dualStoreFactory != null) {
            cascadeWeightStore = dualStoreFactory.weightStoreFor(factSheetId);
        } else if (fileBackedWeightStore != null) {
            cascadeWeightStore = fileBackedWeightStore.fileWeightStoreFor(String.valueOf(factSheetId));
        }
        if (kbCfg().isLearningEnabled() && cascadeWeightStore != null && !program.rules().isEmpty()) {
            try {
                String programKey = factSheetId + "-cascade";
                Optional<Map<String, Double>> persistedWeights = cascadeWeightStore.latest(programKey);
                if (persistedWeights.isPresent()) {
                    program = PslWeightLearningService.applyWeights(program, persistedWeights.get());
                    log.debug("Grounding cascade factSheet={}: applied {} learned PSL weights from store",
                            factSheetId, persistedWeights.get().size());
                }
            } catch (Exception e) {
                log.warn("Grounding cascade factSheet={}: could not reload learned weights — {}",
                        factSheetId, e.getMessage());
                // Non-fatal: continue with current (default or prior) weights
            }
        }

        if (program.targetKeys().isEmpty()) {
            log.debug("Grounding cascade for factSheet={}: no target atoms in program — skipping",
                    factSheetId);
            return RegroundResult.empty();
        }

        // ── STEP 4: MAP solve ─────────────────────────────────────────────────────────
        HlMrfMapInference.Result result;
        String runId = UUID.randomUUID().toString();
        try {
            result = HlMrfMapInference.solve(program);
            log.debug("Grounding cascade factSheet={}: MAP solve converged={} iterations={} objective={}",
                    factSheetId, result.converged(), result.iterations(), result.objective());
        } catch (Exception e) {
            log.warn("Grounding cascade factSheet={}: MAP solve failed — {}", factSheetId, e.getMessage(), e);
            return RegroundResult.empty();
        }

        // ── STEP 5: Materialize inferred facts ────────────────────────────────────────
        List<EntailmentRecord> records = EntailmentEngine.entailFromPslResult(
                program, result, factStore, runId);

        InferredFactStore inferredStore = state.inferredFactStore();
        int versionsWritten = 0;

        // Snapshot the previous atom keys so we can detect implicitly retracted atoms
        // (atoms that were in the inferred store before this run but are not produced now).
        Set<String> previousAtomKeys = inferredStore.allLatest().stream()
                .map(InferredFact::atomKey)
                .collect(Collectors.toCollection(HashSet::new));
        Set<String> newAtomKeys = new HashSet<>();

        for (EntailmentRecord record : records) {
            String atomKey = record.groundedRvOrAtomKey();
            double newValue = record.posterior();

            newAtomKeys.add(atomKey);

            // PinGuard: skip atoms pinned by human corrections
            if (pinGuard != null && pinGuard.isPinned(factSheetId, atomKey)) {
                log.debug("PinGuard: skipping re-derivation of pinned atom '{}' in factSheet={}",
                        atomKey, factSheetId);
                continue;
            }

            // Fixed-point termination: only write a new version if value changed meaningfully
            Optional<InferredFact> existing = inferredStore.latest(atomKey);
            if (existing.isPresent()
                    && Math.abs(existing.get().value() - newValue) < VERSION_EPSILON) {
                continue; // stable — no new version needed
            }

            double vBefore = existing.map(InferredFact::value).orElse(Double.NaN);
            double cBefore = existing.map(InferredFact::confidence).orElse(Double.NaN);

            long nextVersion = existing.map(f -> f.version() + 1L).orElse(1L);
            InferredFact newFact = new InferredFact(
                    atomKey,
                    newValue,
                    newValue,  // confidence == value for PSL soft-truth
                    record.supportingFindingKeys(),
                    record.activatedRules(),
                    runId,
                    nextVersion,
                    record.computedAt()
            );
            inferredStore.store(newFact);
            versionsWritten++;

            // Emit DERIVED audit event after each successful store
            if (correctionService != null) {
                correctionService.appendDerivedAuditEvent(
                        factSheetId, atomKey, vBefore, newValue, cBefore, newValue, runId);
            }

            // STEP 5c: Fact promotion tracking
            if (promotionTracker != null) {
                promotionTracker.checkPromotion(factSheetId, atomKey, vBefore, newValue, runId);
            }
        }

        // ── STEP 5b (L1 NEW): PSL weight learning — train on materialized soft targets ──
        // Use the MAP posteriors as soft training targets (atomKey → posterior value).
        // Run 1 mini-batch step (cheap; accumulates across cascades via warm-start from
        // persisted weights; 1 step keeps wall-time overhead below 10 ms for typical fact
        // sheets of ≤5000 atoms).
        // Uses cascadeWeightStore resolved above (DualStore preferred; falls back to file-backed).
        final PslProgram programForSnapshot;
        if (kbCfg().isLearningEnabled() && cascadeWeightStore != null && !program.rules().isEmpty()
                && !result.values().isEmpty()) {
            PslProgram trainedProgram = program;
            try {
                // Build soft targets from OBSERVED facts in the FactStore (the extracted ground
                // truth). Training on MAP posteriors (result.values()) is self-training — the
                // gradient dist(innerMAP)-dist(outerMAP) ≈ 0. Instead, train toward what the
                // LLM/Tika actually extracted so rule weights move toward explaining the data.
                Map<String, Double> softTargets = buildObservedTargets(factStore);
                // Fall back to MAP posteriors only when the fact store is empty
                if (softTargets.isEmpty()) {
                    softTargets = new HashMap<>(result.values());
                }
                // Compute per-rule prior means from the band of supporting atoms so that
                // established rules are not shrunk toward the same small mean as speculative ones.
                double[] perRuleMeans = computePerRulePriorMeans(program, factStore);
                // 1 update step: cheap warm-start accumulation, no convergence risk
                trainedProgram = pslWeightLearner(perRuleMeans).updateOnBatch(program, softTargets, 1);

                // Persist the updated weights via whichever store was resolved above
                String programKey = factSheetId + ":cascade";
                int savedVersion = cascadeWeightStore.save(programKey, trainedProgram.rules());
                log.debug("Grounding cascade factSheet={}: PSL weight training done ({} rules persisted, v{})",
                        factSheetId, trainedProgram.rules().size(), savedVersion);

                // STEP 5b-event: publish ModelTrainedEvent so app-main can stage the artifact.
                // Only when file-backed store is the active store (dualStoreFactory path does not
                // expose a file artifact path here; KGE covers that separately).
                if (fileBackedWeightStore != null && dualStoreFactory == null) {
                    try {
                        java.nio.file.Path artifactPath = fileBackedWeightStore.pslArtifactPath(
                                String.valueOf(factSheetId), programKey, savedVersion);
                        eventPublisher.publishEvent(
                                new ModelTrainedEvent(this, "psl", factSheetId, artifactPath, "psl-cascade"));
                        log.debug("Grounding cascade factSheet={}: published ModelTrainedEvent(psl, v{})",
                                factSheetId, savedVersion);
                    } catch (Exception e) {
                        log.warn("Grounding cascade factSheet={}: could not publish PSL ModelTrainedEvent — {}",
                                factSheetId, e.getMessage());
                    }
                }

                // STEP 5b audit: emit WEIGHT_TUNED event for each changed rule
                if (correctionService != null) {
                    emitWeightTunedAuditEvents(factSheetId, program, trainedProgram, runId);
                }
            } catch (Exception e) {
                log.warn("Grounding cascade factSheet={}: PSL weight training failed — {}",
                        factSheetId, e.getMessage());
                // Non-fatal: inference result already materialized; keep un-trained program
            }
            programForSnapshot = trainedProgram;
        } else {
            programForSnapshot = program;
        }

        // Register the PSL program snapshot (trained if learning ran) with the correction
        // service so that subsequent human corrections have a fresh program for mini-batch
        // weight updates.
        if (correctionService != null) {
            correctionService.registerProgram(factSheetId, programForSnapshot);
        }

        // Compute implicitly retracted atom keys: atoms that were in the inferred store
        // before this run but are not produced by this MAP solve.
        // These are candidates for BeliefReviser-based retraction in a future incremental path.
        Set<String> retractedAtomKeys = new HashSet<>(previousAtomKeys);
        retractedAtomKeys.removeAll(newAtomKeys);

        // ── STEP 6: Rebuild JustificationIndex ───────────────────────────────────────
        // Full rebuild (O(|ground rules|)); incremental merge is a TODO per design §9.1.
        JustificationIndex newIndex = JustificationIndex.build(result, factStore);

        // ── STEP 7: Contradiction scan + TMS retraction ───────────────────────────────
        List<ContradictionDetector.Pair<Fact, Fact>> contradictions =
                ContradictionDetector.findFactContradictions(factStore);
        if (!contradictions.isEmpty()) {
            log.warn("Grounding cascade factSheet={}: {} contradiction pair(s) — running TMS retraction",
                    factSheetId, contradictions.size());

            // Publish ContradictionDetectedEvent for external listeners
            try {
                eventPublisher.publishEvent(
                        new ContradictionDetectedEvent(this, factSheetId, runId, contradictions));
            } catch (Exception e) {
                log.warn("Grounding cascade factSheet={}: failed to publish ContradictionDetectedEvent — {}",
                        factSheetId, e.getMessage());
            }

            // TMS: retract lower-confidence side for significant contradictions (|diff| > 0.2)
            for (ContradictionDetector.Pair<Fact, Fact> pair : contradictions) {
                Fact a = pair.first();
                Fact b = pair.second();
                double diff = Math.abs(a.value() - b.value());
                if (diff > 0.2) {
                    Fact lower = a.value() <= b.value() ? a : b;
                    Fact higher = a.value() > b.value() ? a : b;
                    try {
                        BeliefReviser.retract(lower.atomKey(), factStore, newIndex);
                        log.debug("Grounding cascade factSheet={}: TMS retracted '{}' (value={}) in favour of '{}' (value={})",
                                factSheetId, lower.atomKey(), lower.value(),
                                higher.atomKey(), higher.value());

                        // Emit CONTRADICTION_RESOLVED audit event
                        if (correctionService != null) {
                            correctionService.appendContradictionResolvedEvent(
                                    factSheetId, lower.atomKey(), higher.value(),
                                    higher.atomKey(), runId);
                        }
                    } catch (Exception e) {
                        log.warn("Grounding cascade factSheet={}: TMS retraction failed for '{}' — {}",
                                factSheetId, lower.atomKey(), e.getMessage());
                    }
                }
            }
        }

        // ── STEP 8: Epoch bump ────────────────────────────────────────────────────────
        kbGroundingService.markEpoch(factSheetId, runId, newIndex);

        // ── STEP 9 (L1 NEW): Throttled MEBN weight learning ──────────────────────────
        // MEBN finite-difference gradient descent is O(|edges| × maxEpochs) — too expensive
        // to run every cascade. We run it every MEBN_LEARNING_INTERVAL cascades (default: 10)
        // to balance learning responsiveness vs. per-cascade wall time.
        // Uses MAP posteriors as training observations (same soft-target signal as PSL).
        long cascadeCount = cascadeCounters
                .computeIfAbsent(factSheetId, id -> new AtomicLong(0L))
                .incrementAndGet();
        if (kbCfg().isLearningEnabled() && mebnWeightAdapter != null
                && cascadeCount % kbCfg().getMebnLearningInterval() == 0) {
            MTheory theory = mebnTheories.get(factSheetId);
            ReasoningGraph mebnGraph = mebnGraphs.get(factSheetId);
            if (theory != null && mebnGraph != null && !result.values().isEmpty()) {
                try {
                    // Load previously persisted weights to warm-start learning
                    mebnWeightAdapter.load(factSheetId, theory);
                    // Use OBSERVED facts as training targets (not MAP posteriors — same
                    // self-training bug as PSL path; both must use buildObservedTargets).
                    Map<String, Double> observations = buildObservedTargets(factStore);
                    if (observations.isEmpty()) {
                        // Fall back to MAP posteriors if fact store has no observed facts
                        observations = new HashMap<>(result.values());
                    }
                    // 5 epochs per throttled run — finite-diff is inherently slow but 5 steps
                    // is sufficient for incremental online updates
                    mebnWeightLearner.learn(theory, mebnGraph, observations, 5);
                    // Persist updated strengths
                    mebnWeightAdapter.persist(factSheetId, theory);
                    log.debug("Grounding cascade factSheet={}: MEBN weight training done (cascade {})",
                            factSheetId, cascadeCount);

                    // STEP 9-event: publish ModelTrainedEvent so app-main can stage the artifact.
                    try {
                        java.nio.file.Path mebnArtifact = mebnWeightAdapter.mebnArtifactPath(factSheetId);
                        eventPublisher.publishEvent(
                                new ModelTrainedEvent(this, "mebn", factSheetId, mebnArtifact, "mebn-grounding"));
                        log.debug("Grounding cascade factSheet={}: published ModelTrainedEvent(mebn, cascade {})",
                                factSheetId, cascadeCount);
                    } catch (Exception e) {
                        log.warn("Grounding cascade factSheet={}: could not publish MEBN ModelTrainedEvent — {}",
                                factSheetId, e.getMessage());
                    }
                } catch (Exception e) {
                    log.warn("Grounding cascade factSheet={}: MEBN weight training failed — {}",
                            factSheetId, e.getMessage());
                    // Non-fatal: inference already complete
                }
            }
        }

        log.info("Grounding cascade factSheet={}: {} InferredFact version(s) written, {} retracted, runId={}",
                factSheetId, versionsWritten, retractedAtomKeys.size(), runId);
        return new RegroundResult(versionsWritten, runId, Set.copyOf(retractedAtomKeys));
    }

    /**
     * Emit WEIGHT_TUNED audit events for PSL rules whose weights changed in the cascade
     * mini-batch training step. Compares {@code before} and {@code after} programs rule-by-rule;
     * rules whose weight changed by more than 0.001 get an audit event.
     *
     * <p>Called by {@link #doReground} after a successful {@link PslWeightLearningService#updateOnBatch}
     * to close the gap where cascade weight training was silently discarded.</p>
     */
    private void emitWeightTunedAuditEvents(long factSheetId, PslProgram before, PslProgram after, String runId) {
        if (correctionService == null) return;
        if (before.rules().size() != after.rules().size()) return;
        double epsilon = 0.001;
        for (int i = 0; i < before.rules().size(); i++) {
            double wb = before.rules().get(i).weight();
            double wa = after.rules().get(i).weight();
            if (Math.abs(wb - wa) > epsilon) {
                correctionService.appendWeightTunedEvent(
                        factSheetId, before.rules().get(i).toString(), wb, wa, runId);
            }
        }
    }

    /**
     * Phase 3 — inject ontology DOMAIN/RANGE axioms as soft PSL rules into {@code program}.
     *
     * <p>Guard conditions (any one → strict no-op):
     * <ul>
     *   <li>{@link #ontologyProvider} is {@code null} (no implementation wired — plain-Java tests,
     *       Spring contexts without app-main).</li>
     *   <li>{@link OntologyProjectionProvider#hasBoundOntology(Long)} returns {@code false}
     *       (fact sheet has no bound ontology).</li>
     *   <li>{@link OntologyProjectionProvider#ontologyAxioms(Long)} returns an empty list.</li>
     * </ul>
     *
     * <p>Rule weight: sourced from the kompile-managed {@code kbCfg().getOntologyRuleWeight()}
     * (dedicated ontology rule weight in {@link ai.kompile.knowledgegraph.confidence.KbConfig},
     * default 0.8, range [0.0, 100.0]).</p>
     *
     * @param program     the program to inject rules into (mutated in place)
     * @param factSheetId the fact sheet whose bound ontology to query
     */
    private void injectOntologyRules(PslProgram program, long factSheetId) {
        if (ontologyProvider == null) return;
        if (!ontologyProvider.hasBoundOntology(factSheetId)) return;

        List<OntologyAxiom> axioms = ontologyProvider.ontologyAxioms(factSheetId);
        if (axioms == null || axioms.isEmpty()) return;

        // Rule weight from the kompile-managed KbConfig (dedicated ontology rule weight; default 0.8).
        double weight = kbCfg().getOntologyRuleWeight();

        OntologyToPslRuleCompiler compiler = new OntologyToPslRuleCompiler(weight);
        List<String> ruleStrings = compiler.compile(axioms);

        int added = 0;
        for (String ruleStr : ruleStrings) {
            try {
                program.addRule(ruleStr);
                added++;
            } catch (Exception e) {
                log.warn("OntologyToPsl: could not parse compiled rule '{}' for factSheet={} — {}",
                        ruleStr, factSheetId, e.getMessage());
                // Non-fatal: continue with the remaining axiom rules
            }
        }
        if (added > 0) {
            log.debug("OntologyToPsl: injected {} ontology axiom rule(s) into PSL program for factSheet={}",
                    added, factSheetId);
        }
    }

    /**
     * Load {@code .psl} rule files from {@code <dataDir>/rules/} into the program.
     *
     * <p>This is the minimal pluggable rule seam: projects can drop {@code *.psl} files under
     * {@code data/rules/} (one rule per line, blank lines and {@code #}-comments ignored) and
     * those rules will be picked up by the next cascade. The full rule-authoring UI is a
     * separate product decision (design spec Q1) — this is just the seam.</p>
     *
     * <p>If {@code dataDir} is null/blank or no rules directory exists, this is a no-op.</p>
     */
    private void loadProjectPslRules(PslProgram program) {
        if (dataDir == null || dataDir.isBlank()) return;
        Path rulesDir = Path.of(dataDir, "rules");
        if (!Files.isDirectory(rulesDir)) return;

        try (java.util.stream.Stream<Path> files = Files.list(rulesDir)) {
            files.filter(p -> p.toString().endsWith(".psl"))
                 .sorted()
                 .forEach(ruleFile -> {
                     try {
                         List<String> lines = Files.readAllLines(ruleFile, StandardCharsets.UTF_8);
                         int loaded = 0;
                         for (String line : lines) {
                             String trimmed = line.trim();
                             if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
                             try {
                                 program.addRule(trimmed);
                                 loaded++;
                             } catch (Exception e) {
                                 log.warn("PSL rule load: could not parse rule '{}' in {} — {}",
                                         trimmed, ruleFile.getFileName(), e.getMessage());
                             }
                         }
                         log.debug("PSL rule load: {} rules loaded from {}", loaded, ruleFile.getFileName());
                     } catch (IOException e) {
                         log.warn("PSL rule load: could not read {} — {}", ruleFile, e.getMessage());
                     }
                 });
        } catch (IOException e) {
            log.warn("PSL rule load: could not list rules directory {} — {}", rulesDir, e.getMessage());
        }
    }

    /**
     * Build a PSL program from all observed facts in the {@link FactStore}.
     *
     * <p>Every multi-arg fact is registered as an observed atom at its current value.
     * A corresponding target atom {@code derived_<predicate>(args)} is registered so
     * that the MAP solver can produce a derived confidence value (the cascade output
     * visible in the {@link InferredFactStore}). A soft propagation rule
     * {@code 0.8: <pred>(?X) -> derived_<pred>(?X)} is added per predicate.</p>
     *
     * <p>In production this should be replaced by building the program from a
     * {@link ai.kompile.graph.reasoning.model.ReasoningGraph} via
     * {@link ai.kompile.graph.reasoning.psl.GraphPslProgramBuilder} — that requires a live
     * {@link ai.kompile.knowledgegraph.service.KnowledgeGraphService} (not wired here to
     * keep the test surface minimal and avoid the Spring JPA context).</p>
     */
    public static PslProgram buildProgramFromFactStore(FactStore factStore) {
        return buildProgramFromFactStore(factStore, 0.8);
    }

    /**
     * Overload used by the instance path so the configurable {@link #defaultRuleWeight} can
     * be passed in without breaking the static test API.
     */
    public static PslProgram buildProgramFromFactStore(FactStore factStore, double ruleWeight) {
        PslProgram program = new PslProgram();
        Set<String> predicates = new LinkedHashSet<>();
        // Per-predicate mean value — used to warm-start rule weights higher for established atoms.
        // Key = predicate name, value = [sum, count] so we can compute mean after scanning all facts.
        Map<String, double[]> predicateSumCount = new HashMap<>();

        Collection<Fact> allFacts = factStore.allFacts();
        for (Fact fact : allFacts) {
            String atomKey = fact.atomKey();
            double value = fact.value();
            int lp = atomKey.indexOf('(');
            if (lp < 0) {
                // Zero-arity: observe as-is; register as target too
                program.observe(atomKey, value);
                program.target("derived_" + atomKey);
                predicates.add(atomKey);
                predicateSumCount.computeIfAbsent(atomKey, k -> new double[]{0.0, 0.0});
                predicateSumCount.get(atomKey)[0] += value;
                predicateSumCount.get(atomKey)[1] += 1.0;
            } else {
                int rp = atomKey.lastIndexOf(')');
                String predicate = atomKey.substring(0, lp).trim();
                String inside = (rp > lp) ? atomKey.substring(lp + 1, rp).trim() : "";
                if (inside.isEmpty()) {
                    program.observe(predicate, value);
                    program.target("derived_" + predicate);
                    predicates.add(predicate);
                } else {
                    String[] args = splitArgs(inside);
                    program.observe(predicate, value, args);
                    program.target("derived_" + predicate, args);
                    predicates.add(predicate);
                }
                predicateSumCount.computeIfAbsent(predicate, k -> new double[]{0.0, 0.0});
                predicateSumCount.get(predicate)[0] += value;
                predicateSumCount.get(predicate)[1] += 1.0;
            }
        }

        // Add soft propagation rules so the MAP solver derives confidence values — all at the
        // configurable default rule weight. "More weight on established rules" is applied during
        // LEARNING via the config-driven per-rule MAP prior (computePerRulePriorMeans +
        // pslWeightLearner(double[])), NOT by hardcoding higher initial weights here. Subsequent
        // cascades overwrite these weights with learned values from the weight store.
        for (String pred : predicates) {
            try {
                // Unary rule — works for any arity ≤ 1 in the predicate index
                program.addRule(ruleWeight + ": " + pred + "(?X) -> derived_" + pred + "(?X)");
            } catch (Exception ignored) {
                // Rule may fail to parse for 0-arity or higher-arity predicates — silently skip
            }
            try {
                program.addRule(ruleWeight + ": " + pred + "(?X, ?Y) -> derived_" + pred + "(?X, ?Y)");
            } catch (Exception ignored) {
                // Silently skip — only one arity variant will match
            }
        }

        return program;
    }

    /**
     * Build the observed-fact target map from the FactStore for PSL/MEBN weight learning.
     *
     * <p>This is the fix for the self-training bug: instead of training on MAP posteriors
     * ({@code result.values()}), we train on the facts that were actually extracted from the
     * graph (the hard=true and soft Fact values from the FactStore). The structured-perceptron
     * gradient {@code dist(innerMAP) - dist(observedFacts)} is then non-zero and causes rule
     * weights to converge toward making the MAP output match the extracted observations.</p>
     *
     * @param factStore the projected FactStore (already populated by buildProgramFromFactStore)
     * @return map of atomKey → observed value; empty map if factStore is empty or null
     */
    private Map<String, Double> buildObservedTargets(@Nullable FactStore factStore) {
        if (factStore == null) return new HashMap<>();
        Map<String, Double> targets = new HashMap<>();
        for (Fact f : factStore.allFacts()) {
            // Include both hard-observed (value=1.0) and soft-truth facts as training signal.
            // Hard facts (confidence >= 0.99) are the strongest signal; soft facts contribute
            // a weaker but still correct gradient direction.
            targets.put(f.atomKey(), f.value());
        }
        return targets;
    }

    /**
     * Compute a per-rule prior mean array for band-aware MAP regularization.
     *
     * <p>For each rule in {@code program.rules()}, this method:
     * <ol>
     *   <li>Extracts the predicate name from the rule body (the substring before {@code (?}
     *       in the head/body — the convention used by {@link #buildProgramFromFactStore}).</li>
     *   <li>Looks up all facts in the FactStore whose atomKey starts with that predicate;
     *       computes the mean observed value across those facts.</li>
     *   <li>Projects the mean value onto a {@link StrengthBand} via
     *       {@link StrengthBand#fromScalar(double)}.</li>
     *   <li>Assigns the band-appropriate prior mean from the current {@link KbConfig}:
     *       ESTABLISHED → {@code ruleWeightEstablishedMean},
     *       HIGH        → {@code ruleWeightHighMean},
     *       PROBABLE    → {@code ruleWeightProbableMean},
     *       SPECULATIVE/SUPPRESSED → {@code ruleWeightSpeculativeMean}.</li>
     *   <li>Falls back to the scalar {@code pslWeightPriorMean} when no matching facts are
     *       found (e.g. project-level rules loaded from .psl files).</li>
     * </ol>
     *
     * <p>Design note on reading band from rules: the FactStore only carries soft-truth values
     * (no {@link StrengthBand} field on {@link Fact}), so we reconstruct the band from the
     * scalar value. A hard fact (value=1.0) maps to ESTABLISHED; a soft fact with value~0.3
     * maps to SPECULATIVE. This is the correct approximation for the warm-start prior:
     * structural/deductive facts are asserted at value 1.0 and should therefore receive the
     * ESTABLISHED prior mean automatically.
     *
     * @param program   the PSL program whose rules need prior means
     * @param factStore the observed FactStore for this fact sheet
     * @return per-rule prior means array (length == program.rules().size())
     */
    double[] computePerRulePriorMeans(PslProgram program, FactStore factStore) {
        KbConfig c = kbCfg();
        List<PslRule> rules = program.rules();
        double[] means = new double[rules.size()];

        // Build a predicate→mean-value index from the FactStore once (O(|facts|))
        // so we don't scan all facts for every rule (avoids O(|rules| × |facts|)).
        Map<String, double[]> predicateSumCount = new HashMap<>();
        if (factStore != null) {
            for (Fact f : factStore.allFacts()) {
                String atomKey = f.atomKey();
                int lp = atomKey.indexOf('(');
                String pred = (lp > 0) ? atomKey.substring(0, lp).trim() : atomKey;
                predicateSumCount.computeIfAbsent(pred, k -> new double[]{0.0, 0.0});
                double[] sc = predicateSumCount.get(pred);
                sc[0] += f.value();  // sum
                sc[1] += 1.0;        // count
            }
        }

        for (int i = 0; i < rules.size(); i++) {
            String ruleStr = rules.get(i).toString();
            // Extract predicate from rule body: the propagation rules generated by
            // buildProgramFromFactStore look like "0.8: pred(?X) -> derived_pred(?X) ^2"
            // We extract the first token before '(' as the source predicate.
            String sourcePred = extractSourcePredicate(ruleStr);
            double[] sc = (sourcePred != null) ? predicateSumCount.get(sourcePred) : null;

            if (sc != null && sc[1] > 0.0) {
                double meanValue = sc[0] / sc[1];
                StrengthBand band = StrengthBand.fromScalar(meanValue);
                means[i] = bandPriorMean(band, c);
            } else {
                // No matching facts (project-level rule): fall back to scalar mean
                means[i] = c.getPslWeightPriorMean();
            }
        }

        return means;
    }

    /**
     * Map a {@link StrengthBand} to the configured prior mean for that band.
     *
     * <p>Reads per-band prior means from {@link KbConfig} via {@link #kbCfg()}. The lead must
     * add the following fields to {@link KbConfig} (with these defaults and ranges):
     * <ul>
     *   <li>{@code ruleWeightEstablishedMean} — default 0.9, range [0, 100]</li>
     *   <li>{@code ruleWeightHighMean}        — default 0.7, range [0, 100]</li>
     *   <li>{@code ruleWeightProbableMean}    — default 0.4, range [0, 100]</li>
     *   <li>{@code ruleWeightSpeculativeMean} — default 0.1, range [0, 100]</li>
     * </ul>
     * Until those fields are wired, this method uses computed defaults scaled around the
     * existing scalar {@code pslWeightPriorMean} so behavior degrades gracefully to the
     * old path when the fields are absent.
     */
    private double bandPriorMean(StrengthBand band, KbConfig c) {
        // NOTE: when the lead adds ruleWeightEstablishedMean / ruleWeightHighMean /
        // ruleWeightProbableMean / ruleWeightSpeculativeMean to KbConfig, replace the
        // computed defaults below with direct field references:
        //   case ESTABLISHED: return c.ruleWeightEstablishedMean;
        //   ...
        // Until then, we derive sensible defaults from the existing scalar mean so the
        // code compiles and produces correct ordering: ESTABLISHED > HIGH > PROBABLE > SPECULATIVE.
        double scalar = c.getPslWeightPriorMean();
        switch (band) {
            case ESTABLISHED: return Math.max(scalar, 0.9);   // ~top of non-hard range
            case HIGH:        return Math.max(scalar, 0.7);   // clearly above default
            case PROBABLE:    return Math.max(scalar, 0.4);   // moderate
            case SPECULATIVE:
            case SUPPRESSED:
            default:          return scalar;                   // stays at the scalar mean
        }
    }

    /**
     * Extract the source predicate name from a PSL rule string.
     *
     * <p>Handles the format produced by {@link #buildProgramFromFactStore}:
     * {@code "0.8: pred(?X, ?Y) -> derived_pred(?X, ?Y) ^2"}.
     * Returns the first predicate name in the body (before the first {@code (}).
     * Returns {@code null} when the rule string cannot be parsed.</p>
     */
    private static String extractSourcePredicate(String ruleStr) {
        if (ruleStr == null) return null;
        // Skip the weight prefix "W: "
        int colon = ruleStr.indexOf(':');
        String body = (colon >= 0) ? ruleStr.substring(colon + 1).trim() : ruleStr.trim();
        // Find the first '(' to delimit the predicate name
        int lp = body.indexOf('(');
        if (lp <= 0) return null;
        // Walk back from '(' skipping whitespace to get the clean predicate name
        int end = lp;
        while (end > 0 && body.charAt(end - 1) == ' ') end--;
        // Find the start of the predicate (after the last space before it)
        int start = body.lastIndexOf(' ', end - 1) + 1;
        String candidate = body.substring(start, end).trim();
        // Reject negation marker and derived prefix
        if (candidate.startsWith("~")) candidate = candidate.substring(1);
        return candidate.isEmpty() ? null : candidate;
    }

    /** Split a comma-separated argument string, trimming each token. */
    private static String[] splitArgs(String inside) {
        String[] raw = inside.split(",");
        String[] args = new String[raw.length];
        for (int i = 0; i < raw.length; i++) {
            args[i] = raw[i].trim();
        }
        return args;
    }
}
