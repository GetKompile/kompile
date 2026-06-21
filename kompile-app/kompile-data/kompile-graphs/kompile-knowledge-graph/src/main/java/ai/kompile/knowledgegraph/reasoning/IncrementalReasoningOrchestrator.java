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

import ai.kompile.graph.reasoning.fol.EntailmentEngine;
import ai.kompile.graph.reasoning.fol.EntailmentRecord;
import ai.kompile.graph.reasoning.fol.Fact;
import ai.kompile.graph.reasoning.fol.FactStore;
import ai.kompile.graph.reasoning.fol.InferredFact;
import ai.kompile.graph.reasoning.fol.InferredFactStore;
import ai.kompile.graph.reasoning.learning.MebnWeightLearner;
import ai.kompile.graph.reasoning.learning.PslWeightLearningService;
import ai.kompile.graph.reasoning.learning.WeightStore;
import ai.kompile.graph.reasoning.mebn.MTheory;
import ai.kompile.graph.reasoning.model.ReasoningGraph;
import ai.kompile.graph.reasoning.psl.HlMrfMapInference;
import ai.kompile.graph.reasoning.psl.PslProgram;
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
     * When true (default), PSL weight learning is run after each MAP solve.
     * Set {@code kompile.kb.learning.enabled=false} to disable.
     */
    @Value("${kompile.kb.learning.enabled:true}")
    boolean learningEnabled;

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
     * PSL weight learner — reused across cascades (stateless, cheap to construct).
     * Uses 1 mini-batch step per cascade (cheap; won't destabilize inference).
     */
    private final PslWeightLearningService pslWeightLearner = new PslWeightLearningService();

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
        PslProgram program = buildProgramFromFactStore(factStore);

        // ── STEP 3b (NEW): Load project-level PSL rules if dataDir is configured ─────
        // If <dataDir>/rules/*.psl files exist, their rules override the default soft-
        // propagation rules added by buildProgramFromFactStore. This is the pluggable
        // rule seam described in design spec §7 (P0 build plan item 4).
        loadProjectPslRules(program);

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
        if (learningEnabled && cascadeWeightStore != null && !program.rules().isEmpty()) {
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
        if (learningEnabled && cascadeWeightStore != null && !program.rules().isEmpty()
                && !result.values().isEmpty()) {
            PslProgram trainedProgram = program;
            try {
                // Build soft targets: use MAP atom values (posteriors) as labels
                Map<String, Double> softTargets = new HashMap<>(result.values());
                // 1 update step: cheap warm-start accumulation, no convergence risk
                trainedProgram = pslWeightLearner.updateOnBatch(program, softTargets, 1);

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
        if (learningEnabled && mebnWeightAdapter != null
                && cascadeCount % MEBN_LEARNING_INTERVAL == 0) {
            MTheory theory = mebnTheories.get(factSheetId);
            ReasoningGraph mebnGraph = mebnGraphs.get(factSheetId);
            if (theory != null && mebnGraph != null && !result.values().isEmpty()) {
                try {
                    // Load previously persisted weights to warm-start learning
                    mebnWeightAdapter.load(factSheetId, theory);
                    // Use MAP posteriors as observations (same label signal as PSL training)
                    Map<String, Double> observations = new HashMap<>(result.values());
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
    static PslProgram buildProgramFromFactStore(FactStore factStore) {
        PslProgram program = new PslProgram();
        Set<String> predicates = new LinkedHashSet<>();

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
            }
        }

        // Add soft propagation rules so the MAP solver derives confidence values.
        for (String pred : predicates) {
            try {
                // Unary rule — works for any arity ≤ 1 in the predicate index
                program.addRule("0.8: " + pred + "(?X) -> derived_" + pred + "(?X)");
            } catch (Exception ignored) {
                // Rule may fail to parse for 0-arity or higher-arity predicates — silently skip
            }
            try {
                program.addRule("0.8: " + pred + "(?X, ?Y) -> derived_" + pred + "(?X, ?Y)");
            } catch (Exception ignored) {
                // Silently skip — only one arity variant will match
            }
        }

        return program;
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
