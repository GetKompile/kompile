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
import ai.kompile.core.graphrag.conformance.OwlDerivedRuleProvider;
import ai.kompile.core.graphrag.conformance.OntologyProjectionProvider;
import ai.kompile.core.reasoning.ReasoningLearningExecutor;
import ai.kompile.graph.reasoning.confidence.StrengthBand;
import ai.kompile.graph.reasoning.fol.EntailmentEngine;
import ai.kompile.graph.reasoning.fol.EntailmentRecord;
import ai.kompile.graph.reasoning.fol.Fact;
import ai.kompile.graph.reasoning.fol.FactStore;
import ai.kompile.graph.reasoning.fol.InferredFact;
import ai.kompile.graph.reasoning.fol.InferredFactStore;
import ai.kompile.graph.reasoning.fol.MebnInferenceService;
import ai.kompile.graph.reasoning.hybrid.HybridReasoner;
import ai.kompile.graph.reasoning.learning.HybridConsensusTrainer;
import ai.kompile.graph.reasoning.learning.MebnWeightLearner;
import ai.kompile.graph.reasoning.learning.PslWeightLearningService;
import ai.kompile.graph.reasoning.learning.SameDiffMebnStrengthLearner;
import ai.kompile.graph.reasoning.learning.StructuredPerceptronLearner;
import ai.kompile.knowledgegraph.confidence.KbConfig;
import ai.kompile.knowledgegraph.confidence.KbConfigManager;
import ai.kompile.knowledgegraph.confidence.SourceTrustResolver;
import jakarta.annotation.PostConstruct;
import ai.kompile.graph.reasoning.learning.WeightStore;
import ai.kompile.graph.reasoning.mebn.MFrag;
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
import ai.kompile.knowledgegraph.grounding.GroundingProgressEvent;
import ai.kompile.knowledgegraph.grounding.KbCorrectionService;
import ai.kompile.knowledgegraph.grounding.KbGroundingService;
import ai.kompile.knowledgegraph.persistence.FileBackedWeightStore;
import ai.kompile.knowledgegraph.persistence.MebnWeightPersistenceAdapter;
import ai.kompile.knowledgegraph.persistence.TrainingCheckpointStore;
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
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
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
     * Optional: OWL 2 RL-derived PSL rule provider. When non-null, {@link #injectOntologyRules}
     * also injects rules derived from OWL entailments (subClassOf transitivity, transitive property
     * closure) in addition to the plain DOMAIN/RANGE axiom rules compiled by
     * {@link OntologyToPslRuleCompiler}. {@code null} in plain-Java test contexts and in Spring
     * contexts that do not include {@code kompile-app-main} (e.g. kompile-knowledge-graph tests).
     */
    @Nullable
    @Autowired(required = false)
    OwlDerivedRuleProvider owlDerivedRuleProvider;

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
     * Optional: source-trust resolver for the Beta-evidence path.
     * When non-null (Spring production context), {@link #doReground} passes
     * {@code sourceTrustResolver.trustFor("PSL_INFERENCE")} as the {@code sourceTrust}
     * argument to the 6-arg {@link FactPromotionTracker#checkPromotion} overload so that
     * repeated PSL-inference corroborations accumulate evidence and climb bands correctly.
     * When null (plain-Java tests), falls back to {@link KbConfig#getTrustDefault()}.
     */
    @Nullable
    @Autowired(required = false)
    SourceTrustResolver sourceTrustResolver;

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
     * Optional training checkpoint store — persists per-factSheet cascade position
     * (cascades completed, PSL weight version, MEBN backup ID) so a timed-out or
     * killed DERIVATION can resume from the last successful warm-start step rather
     * than cold-starting from the prior weights.
     *
     * <p>Null in plain-Java test contexts (no checkpoint written/read, behaviour unchanged).
     * Spring-injected in production via field injection to preserve all existing constructors.</p>
     */
    @Nullable
    @Autowired(required = false)
    TrainingCheckpointStore trainingCheckpointStore;

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
     * Per-factSheet reasoning graphs used by semantic consensus and, when registered, MEBN.
     * Kept independently from MEBN theories so KGE can improve PSL-only cascades as well.
     */
    private final ConcurrentHashMap<Long, ReasoningGraph> reasoningGraphs = new ConcurrentHashMap<>();

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
     * Optional out-of-process reasoning learning executor (PSL + MEBN).
     * When non-null (app-main context with {@code kompile.learning.subprocess.enabled=true}),
     * {@link #doReground} offloads the weight-learning steps to the managed learning subprocess,
     * bounding SameDiff native memory growth in the main JVM. Null in plain-Java test contexts
     * and when the subprocess is disabled.
     */
    @Nullable
    @Autowired(required = false)
    private ReasoningLearningExecutor reasoningLearningExecutor;

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
     * Total number of named cascade steps — used as {@code totalSteps} in progress events.
     * Matches the set of stages emitted in {@link #doReground}.
     */
    static final int TOTAL_CASCADE_STEPS = 17;

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
        return runFullReground(factSheetId, GroundingProgressEvent.TRIGGER_CASCADE);
    }

    /**
     * Re-ground the KB for {@code factSheetId} with an explicit trigger label for progress events.
     *
     * @param factSheetId the fact sheet to re-ground
     * @param trigger     one of the {@link GroundingProgressEvent} TRIGGER_* constants
     * @return a {@link RegroundResult} with the number of {@link InferredFact} versions written,
     *         the run ID, and the set of retracted atom keys
     */
    public RegroundResult runFullReground(long factSheetId, String trigger) {
        FactSheetKbState state = kbGroundingService.getState(factSheetId);
        ReadWriteLock lock = state.lock();
        lock.writeLock().lock();
        try {
            return doReground(factSheetId, state, trigger);
        } finally {
            lock.writeLock().unlock();
        }
    }

    // ─── Training checkpoint query API ───────────────────────────────────────────

    /**
     * Load the persisted training checkpoint for {@code factSheetId}, if any.
     *
     * <p>Returns {@link Optional#empty()} when no checkpoint exists (cold-start) or when
     * the checkpoint store is not wired (plain-Java test contexts).</p>
     *
     * @param factSheetId the fact sheet to query
     * @return the most-recent durable training checkpoint, or empty
     */
    public Optional<TrainingCheckpointStore.TrainingCheckpoint> trainingCheckpoint(long factSheetId) {
        if (trainingCheckpointStore == null) return Optional.empty();
        return trainingCheckpointStore.load(factSheetId);
    }

    /**
     * Return the current in-memory cascade count for {@code factSheetId}, or {@code 0} if
     * no cascades have run in this JVM session (the durable checkpoint may still have a
     * non-zero value — call {@link #trainingCheckpoint(long)} for the persisted position).
     */
    public long currentCascadeCount(long factSheetId) {
        AtomicLong counter = cascadeCounters.get(factSheetId);
        return counter != null ? counter.get() : 0L;
    }

    /**
     * Delete the durable training checkpoint for {@code factSheetId} and reset the in-memory
     * cascade counter to 0, forcing a cold-start on the next DERIVATION run.
     *
     * <p>Called by the REST layer when the user explicitly requests a training-position reset
     * (e.g. via {@code DELETE /api/kb/weights/training-checkpoint/{factSheetId}}). This is the
     * lightweight alternative to {@link ai.kompile.knowledgegraph.persistence.WeightSessionService#startNewSession}:
     * it clears the position without touching the weight files themselves.</p>
     */
    public void clearTrainingCheckpoint(long factSheetId) {
        if (trainingCheckpointStore != null) {
            trainingCheckpointStore.clear(factSheetId);
        }
        cascadeCounters.remove(factSheetId);
        log.info("[IncrementalReasoningOrchestrator] Training checkpoint cleared for factSheet={}; "
                + "next DERIVATION will cold-start", factSheetId);
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
        if (theory != null) {
            mebnTheories.put(factSheetId, theory);
        }
        registerReasoningGraph(factSheetId, graph);
        if (theory != null && graph != null) {
            log.debug("IncrementalReasoningOrchestrator: registered MTheory for factSheet={}", factSheetId);
        }
    }

    /**
     * Register a graph for online hybrid consensus without requiring an MEBN theory.
     * Replacing the graph is atomic; the next cascade observes newly persisted embeddings.
     */
    public void registerReasoningGraph(long factSheetId, ReasoningGraph graph) {
        if (graph != null) {
            reasoningGraphs.put(factSheetId, graph);
            log.debug("IncrementalReasoningOrchestrator: registered reasoning graph for "
                            + "factSheet={} (entities={}, relations={})",
                    factSheetId, graph.entityCount(), graph.relationCount());
        }
    }

    // ─── Internal ─────────────────────────────────────────────────────────────────

    /**
     * Publish a {@link GroundingProgressEvent} non-fatally (exceptions are swallowed so that
     * a broken event-listener never aborts the cascade).
     */
    private void publishProgress(long factSheetId, String cascadeId, String trigger,
                                 String stage, String status, String message,
                                 int stepIndex, @Nullable java.util.Map<String, Object> data) {
        try {
            eventPublisher.publishEvent(
                    new GroundingProgressEvent(this, factSheetId, cascadeId, trigger,
                            stage, status, message, stepIndex, TOTAL_CASCADE_STEPS, data));
        } catch (Exception ex) {
            log.debug("GroundingProgressEvent publish failed for factSheet={} stage={}: {}",
                    factSheetId, stage, ex.getMessage());
        }
    }

    /**
     * Execute the full re-ground (backward-compatible no-trigger overload).
     * MUST be called while holding {@code state.lock().writeLock()}.
     */
    private RegroundResult doReground(long factSheetId, FactSheetKbState state) {
        return doReground(factSheetId, state, GroundingProgressEvent.TRIGGER_CASCADE);
    }

    /**
     * Execute the full re-ground with an explicit trigger. MUST be called while holding
     * {@code state.lock().writeLock()}.
     */
    private RegroundResult doReground(long factSheetId, FactSheetKbState state, String trigger) {
        FactStore factStore = state.factStore();
        // runId is established early so all progress events share the same cascadeId.
        String runId = UUID.randomUUID().toString();

        // ── STEP 0 (NEW): Project live graph into FactStore before MAP solve ─────────
        // This is the missing production link (design spec §5): without this call the
        // FactStore is empty after a fresh crawl, so the MAP solve produces nothing.
        // null-safe: graphProjector is null in plain-Java test contexts.
        publishProgress(factSheetId, runId, trigger,
                GroundingProgressEvent.STAGE_PROJECTION, GroundingProgressEvent.STATUS_STARTED,
                "Projecting graph atoms into FactStore for factSheet=" + factSheetId, 0, null);
        if (graphProjector != null) {
            try {
                int projected = graphProjector.project(factSheetId);
                log.info("Grounding cascade for factSheet={}: projected {} graph atoms into FactStore",
                        factSheetId, projected);
                Map<String, Object> projData = new java.util.LinkedHashMap<>();
                projData.put("atomsProjected", projected);
                publishProgress(factSheetId, runId, trigger,
                        GroundingProgressEvent.STAGE_PROJECTION, GroundingProgressEvent.STATUS_DONE,
                        "Projected " + projected + " atoms", 0, projData);
            } catch (Exception e) {
                log.warn("Grounding cascade for factSheet={}: graph projection failed — {}",
                        factSheetId, e.getMessage());
                publishProgress(factSheetId, runId, trigger,
                        GroundingProgressEvent.STAGE_PROJECTION, GroundingProgressEvent.STATUS_ERROR,
                        "Projection failed: " + e.getMessage(), 0, null);
                // Non-fatal: continue with whatever is already in the FactStore
            }
        } else {
            publishProgress(factSheetId, runId, trigger,
                    GroundingProgressEvent.STAGE_PROJECTION, GroundingProgressEvent.STATUS_DONE,
                    "Projection skipped (no projector wired)", 0, null);
        }

        if (factStore.isEmpty()) {
            log.info("Grounding cascade for factSheet={}: FactStore is EMPTY after projection — "
                    + "skipping MAP solve (check graphProjector null={} and graph nodes/edges)",
                    factSheetId, graphProjector == null);
            publishProgress(factSheetId, runId, trigger,
                    GroundingProgressEvent.STAGE_COMPLETE, GroundingProgressEvent.STATUS_DONE,
                    "FactStore empty — cascade skipped", TOTAL_CASCADE_STEPS - 1, null);
            return RegroundResult.empty();
        }
        log.info("Grounding cascade for factSheet={}: FactStore has {} atoms; building PSL program",
                factSheetId, factStore.size());

        // ── STEP 2+3: Build PSL program from the observed FactStore ──────────────────
        publishProgress(factSheetId, runId, trigger,
                GroundingProgressEvent.STAGE_PROGRAM_BUILD, GroundingProgressEvent.STATUS_STARTED,
                "Building PSL program from FactStore (" + factStore.allFacts().size() + " facts)", 1, null);
        PslProgram program = buildProgramFromFactStore(factStore, kbCfg().getPslDefaultRuleWeight());

        // ── STEP 3b (NEW): Load project-level PSL rules if dataDir is configured ─────
        // If <dataDir>/rules/*.psl files exist, their rules override the default soft-
        // propagation rules added by buildProgramFromFactStore. This is the pluggable
        // rule seam described in design spec §7 (P0 build plan item 4).
        loadProjectPslRules(program);

        // Diagnose all-1.0 programs: when every observed atom is at value 1.0 the MAP
        // solve will also produce targets at 1.0, making loss=0 and versionsWritten=0
        // on re-runs a certainty. Surface this early so operators don't have to dig.
        long allOneCount = factStore.allFacts().stream()
                .filter(f -> f.value() >= 1.0 - VERSION_EPSILON).count();
        long softFactCount = factStore.allFacts().stream()
                .filter(f -> f.value() < 1.0 - VERSION_EPSILON).count();
        if (softFactCount == 0 && !factStore.isEmpty()) {
            log.info("Grounding cascade factSheet={}: PSL program built — ALL {} observed atoms are "
                    + "hard-observed (value=1.0, 0 soft). MAP solve will derive targets at 1.0; "
                    + "PSL loss will be 0.0; weight updates will be zero. "
                    + "This is a data-coverage gap: edges need confidence < 1.0 to produce "
                    + "non-trivial inference (see GraphToFactStoreProjector edge confidence path).",
                    factSheetId, allOneCount);
        } else if (softFactCount > 0) {
            log.info("Grounding cascade factSheet={}: PSL program built — hard={} soft={} atoms; "
                    + "mixed-truth input will produce non-trivial MAP posteriors.",
                    factSheetId, allOneCount, softFactCount);
        }
        Map<String, Object> pbData = new java.util.LinkedHashMap<>();
        pbData.put("rulesCount", program.rules().size());
        pbData.put("observedHard", allOneCount);
        pbData.put("observedSoft", softFactCount);
        publishProgress(factSheetId, runId, trigger,
                GroundingProgressEvent.STAGE_PROGRAM_BUILD, GroundingProgressEvent.STATUS_DONE,
                "PSL program built: " + program.rules().size() + " rule(s), hard="
                        + allOneCount + " soft=" + softFactCount, 1, pbData);

        // ── STEP 3b-ont (Phase 3): Inject ontology axiom → PSL rules ─────────────────
        // When a bound ontology is present, compile its DOMAIN/RANGE axioms into soft PSL
        // rules and add them to the program before the MAP solve. When the provider is null,
        // the fact sheet has no bound ontology, or axioms are empty, this step is a strict
        // no-op — today's behaviour is fully preserved.
        publishProgress(factSheetId, runId, trigger,
                GroundingProgressEvent.STAGE_ONTOLOGY_RULES, GroundingProgressEvent.STATUS_STARTED,
                "Injecting ontology axiom rules", 2, null);
        int rulesBefore = program.rules().size();
        injectOntologyRules(program, factSheetId);
        int ontRulesAdded = program.rules().size() - rulesBefore;
        Map<String, Object> ontData = new java.util.LinkedHashMap<>();
        ontData.put("ontologyRulesAdded", ontRulesAdded);
        publishProgress(factSheetId, runId, trigger,
                GroundingProgressEvent.STAGE_ONTOLOGY_RULES, GroundingProgressEvent.STATUS_DONE,
                "Ontology rules injected: +" + ontRulesAdded, 2, ontData);

        // ── STEP 3c (L0 FIX): Reload persisted learned weights into the program ──────
        // Before the MAP solve, load the last-persisted PSL weights and apply them onto the
        // program (so the solve uses LEARNED weights, not 0.8 defaults).
        // Prefers DualStoreGroundingFactory (JPA-backed) when available; falls back to
        // FileBackedWeightStore. If no persisted weights exist yet (first run), falls back
        // to defaults silently.
        publishProgress(factSheetId, runId, trigger,
                GroundingProgressEvent.STAGE_WEIGHT_RELOAD, GroundingProgressEvent.STATUS_STARTED,
                "Reloading persisted PSL weights", 3, null);
        WeightStore cascadeWeightStore = null;
        if (dualStoreFactory != null) {
            cascadeWeightStore = dualStoreFactory.weightStoreFor(factSheetId);
        } else if (fileBackedWeightStore != null) {
            cascadeWeightStore = fileBackedWeightStore.fileWeightStoreFor(String.valueOf(factSheetId));
        }
        int reloadedWeights = 0;
        if (kbCfg().isLearningEnabled() && cascadeWeightStore != null && !program.rules().isEmpty()) {
            try {
                // IMPORTANT: programKey MUST match the key used in STEP 5b's save() call below.
                // Prior bug: this was factSheetId + "-cascade" (dash) while STEP 5b saved with
                // factSheetId + ":cascade" (colon) → reload always missed. Fixed to use colon.
                String programKey = factSheetId + ":cascade";
                Optional<Map<String, Double>> persistedWeights = cascadeWeightStore.latest(programKey);
                if (persistedWeights.isPresent()) {
                    program = PslWeightLearningService.applyWeights(program, persistedWeights.get());
                    reloadedWeights = persistedWeights.get().size();
                    log.debug("Grounding cascade factSheet={}: applied {} learned PSL weights from store",
                            factSheetId, reloadedWeights);
                }
            } catch (Exception e) {
                log.warn("Grounding cascade factSheet={}: could not reload learned weights — {}",
                        factSheetId, e.getMessage());
                // Non-fatal: continue with current (default or prior) weights
            }
        }
        Map<String, Object> wrData = new java.util.LinkedHashMap<>();
        wrData.put("weightsReloaded", reloadedWeights);
        publishProgress(factSheetId, runId, trigger,
                GroundingProgressEvent.STAGE_WEIGHT_RELOAD, GroundingProgressEvent.STATUS_DONE,
                "Weight reload: " + reloadedWeights + " weight(s) applied", 3, wrData);

        if (program.targetKeys().isEmpty()) {
            log.info("Grounding cascade for factSheet={}: PSL program has {} rules but no target atoms — skipping MAP solve",
                    factSheetId, program.rules().size());
            publishProgress(factSheetId, runId, trigger,
                    GroundingProgressEvent.STAGE_COMPLETE, GroundingProgressEvent.STATUS_DONE,
                    "No target atoms — cascade skipped", TOTAL_CASCADE_STEPS - 1, null);
            return RegroundResult.empty();
        }

        // ── STEP 4: MAP solve ─────────────────────────────────────────────────────────
        // STARTED fires BEFORE the solve call (fixing the label-timing bug where the old code
        // emitted WEIGHT_LEARNING before re-ground and DERIVATION only after the solve).
        publishProgress(factSheetId, runId, trigger,
                GroundingProgressEvent.STAGE_MAP_SOLVE, GroundingProgressEvent.STATUS_STARTED,
                "Starting MAP inference (HL-MRF) for factSheet=" + factSheetId, 4, null);
        HlMrfMapInference.Result result;
        try {
            result = HlMrfMapInference.solve(program);
            log.debug("Grounding cascade factSheet={}: MAP solve converged={} iterations={} objective={}",
                    factSheetId, result.converged(), result.iterations(), result.objective());
        } catch (Exception e) {
            log.warn("Grounding cascade factSheet={}: MAP solve failed — {}", factSheetId, e.getMessage(), e);
            publishProgress(factSheetId, runId, trigger,
                    GroundingProgressEvent.STAGE_MAP_SOLVE, GroundingProgressEvent.STATUS_ERROR,
                    "MAP solve failed: " + e.getMessage(), 4, null);
            return RegroundResult.empty();
        }
        Map<String, Object> mapData = new java.util.LinkedHashMap<>();
        mapData.put("iterations", result.iterations());
        mapData.put("objective", result.objective());
        mapData.put("converged", result.converged());
        publishProgress(factSheetId, runId, trigger,
                GroundingProgressEvent.STAGE_MAP_SOLVE, GroundingProgressEvent.STATUS_DONE,
                "MAP solve done: converged=" + result.converged() + " iter=" + result.iterations(), 4, mapData);

        // ── STEP 5: Materialize inferred facts ────────────────────────────────────────
        publishProgress(factSheetId, runId, trigger,
                GroundingProgressEvent.STAGE_MATERIALIZE, GroundingProgressEvent.STATUS_STARTED,
                "Materializing inferred facts", 5, null);
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

        // ── BATCHED MATERIALIZATION ────────────────────────────────────────────────
        // Collect all new InferredFact objects first, then bulk-persist via storeAll().
        // This replaces 48k individual repo.save() calls (~400-600ms each) with a single
        // JPA saveAll() call (~seconds total), eliminating the multi-hour materialization
        // bottleneck.  Audit events and promotion tracking run on the collected list after
        // the bulk write completes (cheap in-memory operations, no DB per-record cost).
        //
        // Interrupt-safety: storeAll() clears the interrupt flag before I/O and re-sets it
        // on exit, so a future.cancel(true) from GraphHydrationOrchestrator does not close
        // the shared DB connection mid-batch.
        record FactWithContext(InferredFact fact, double vBefore, double cBefore) {}
        java.util.List<FactWithContext> toWrite = new java.util.ArrayList<>();

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
            toWrite.add(new FactWithContext(newFact, vBefore, cBefore));
        }

        // Bulk persist — single DB round-trip regardless of batch size
        if (!toWrite.isEmpty()) {
            java.util.List<InferredFact> bulkFacts = toWrite.stream()
                    .map(FactWithContext::fact)
                    .collect(Collectors.toList());
            // storeAll() handles interrupt-safety internally
            inferredStore.storeAll(bulkFacts);
            versionsWritten = bulkFacts.size();
            log.info("Grounding cascade factSheet={}: bulk-persisted {} InferredFact(s)", factSheetId, versionsWritten);

            // Post-write: audit events + promotion tracking (cheap, run after DB write)
            for (FactWithContext fwc : toWrite) {
                // Emit DERIVED audit event
                if (correctionService != null) {
                    correctionService.appendDerivedAuditEvent(
                            factSheetId, fwc.fact().atomKey(),
                            fwc.vBefore(), fwc.fact().value(),
                            fwc.cBefore(), fwc.fact().value(), runId);
                }

                // STEP 5c: Fact promotion tracking (6-arg Beta-evidence path)
                // The 6-arg overload accumulates sourceTrust into evidencePos so that repeated
                // PSL-inference corroborations climb bands over time (A2 fix — slow-climb).
                if (promotionTracker != null) {
                    double sourceTrust = sourceTrustResolver != null
                            ? sourceTrustResolver.trustFor("PSL_INFERENCE")
                            : kbCfg().getTrustDefault();
                    promotionTracker.checkPromotion(factSheetId, fwc.fact().atomKey(),
                            fwc.vBefore(), fwc.fact().value(), runId, sourceTrust);
                }
            }
        }

        // ── TRANSPARENT ZERO DIAGNOSTIC ───────────────────────────────────────────────
        // When 0 versions are written despite the MAP solve producing records, explain why.
        // This makes "derived=0" self-diagnosing rather than silently opaque.
        if (versionsWritten == 0 && !records.isEmpty()) {
            // Compute how many were skipped by fixed-point epsilon vs. pinned
            int stableCount = 0;
            int pinnedCount = 0;
            int firstNewCount = 0;
            for (EntailmentRecord record : records) {
                String atomKey = record.groundedRvOrAtomKey();
                if (pinGuard != null && pinGuard.isPinned(factSheetId, atomKey)) {
                    pinnedCount++;
                } else {
                    Optional<InferredFact> existing = inferredStore.latest(atomKey);
                    if (existing.isPresent()
                            && Math.abs(existing.get().value() - record.posterior()) < VERSION_EPSILON) {
                        stableCount++;
                    } else {
                        firstNewCount++;
                    }
                }
            }
            // Compute value distribution to diagnose whether everything is at 1.0
            long atOne = records.stream().filter(r -> r.posterior() >= 1.0 - VERSION_EPSILON).count();
            long atZero = records.stream().filter(r -> r.posterior() <= VERSION_EPSILON).count();
            int previousStoreSize = previousAtomKeys.size();

            String zeroDiag;
            if (stableCount == records.size()) {
                zeroDiag = "All " + records.size() + " records are STABLE (fixed-point: stored values unchanged "
                        + "within ε=" + VERSION_EPSILON + "). "
                        + "Distribution: at-1.0=" + atOne + " at-0.0=" + atZero
                        + " storeSize=" + previousStoreSize + ". "
                        + "This is expected on re-crawls of an unchanged graph — prior cascade(s) already "
                        + "wrote these facts. Weights or graph edits are needed to produce new versions.";
            } else if (firstNewCount == 0 && pinnedCount > 0) {
                zeroDiag = "All " + records.size() + " records skipped: " + pinnedCount
                        + " pinned by human corrections (PinGuard), " + stableCount
                        + " stable (fixed-point). No derivable updates remain.";
            } else {
                zeroDiag = "0 versions written from " + records.size() + " records: "
                        + "stable=" + stableCount + " pinned=" + pinnedCount
                        + " unexpectedly-skipped=" + firstNewCount
                        + " (check VERSION_EPSILON=" + VERSION_EPSILON + ")";
            }
            log.info("Grounding cascade factSheet={}: ZERO derived — {}", factSheetId, zeroDiag);
            publishProgress(factSheetId, runId, trigger,
                    GroundingProgressEvent.STAGE_MATERIALIZE, GroundingProgressEvent.STATUS_DONE,
                    "ZERO derived — " + zeroDiag, 5, null);
        } else if (versionsWritten == 0 && records.isEmpty()) {
            String noDerivedDiag = "EntailmentEngine produced 0 records from MAP solve. "
                    + "Possible causes: (1) no target atoms in PSL program (check buildProgramFromFactStore "
                    + "added derived_X targets), (2) MAP solve returned empty values (check solver logs), "
                    + "(3) factStore was empty (atomsProjected=0 means graph was not projected).";
            log.info("Grounding cascade factSheet={}: ZERO derived — {}", factSheetId, noDerivedDiag);
            publishProgress(factSheetId, runId, trigger,
                    GroundingProgressEvent.STAGE_MATERIALIZE, GroundingProgressEvent.STATUS_DONE,
                    "ZERO derived — " + noDerivedDiag, 5, null);
        }

        Map<String, Object> matData = new java.util.LinkedHashMap<>();
        matData.put("factsMaterialized", versionsWritten);
        matData.put("recordsProduced", records.size());
        matData.put("storeSize", previousAtomKeys.size());
        matData.put("stableFixedPoint", versionsWritten == 0 && !records.isEmpty());
        publishProgress(factSheetId, runId, trigger,
                GroundingProgressEvent.STAGE_MATERIALIZE, GroundingProgressEvent.STATUS_DONE,
                "Materialized " + versionsWritten + " fact version(s) (" + records.size() + " records, storeSize="
                        + previousAtomKeys.size() + ")", 5, matData);

        // ── STEP 5c PROMOTION progress event ─────────────────────────────────────────
        // Collect band counts from promotionTracker (if available) and emit PROMOTION event.
        {
            Map<String, Object> promoData = new java.util.LinkedHashMap<>();
            if (promotionTracker != null) {
                try {
                    int promoted = promotionTracker.promotedAtomCount(factSheetId);
                    promoData.put("promoted", promoted);
                    Map<ai.kompile.graph.reasoning.confidence.StrengthBand, Integer> bands =
                            promotionTracker.bandCounts(factSheetId);
                    if (bands != null) {
                        Map<String, Integer> bandStr = new java.util.LinkedHashMap<>();
                        for (Map.Entry<ai.kompile.graph.reasoning.confidence.StrengthBand, Integer> e : bands.entrySet()) {
                            bandStr.put(e.getKey().name(), e.getValue());
                        }
                        promoData.put("bandCounts", bandStr);
                    }
                } catch (Exception ex) {
                    log.debug("Could not collect promotion metrics: {}", ex.getMessage());
                }
            }
            publishProgress(factSheetId, runId, trigger,
                    GroundingProgressEvent.STAGE_PROMOTION, GroundingProgressEvent.STATUS_DONE,
                    "Promotion check complete", 6, promoData.isEmpty() ? null : promoData);
        }

        // ── JOINT TRAINING SIGNAL: one consensus for PSL (5b) + MEBN (9), derived EVERY cascade ──
        // Replaces the previously independent buildObservedTargets calls AND the throttled hybrid path
        // that ran a second structural inference (+ embedding co-training) only every Nth cascade. Under
        // partial observability there is no complete target to converge to, so every learner runs
        // INCREMENTAL ONLINE — one warm-started step per cascade. To keep that affordable the consensus
        // reuses the structural signal we ALREADY have (this cascade's MAP posteriors, aggregated per
        // entity) instead of a fresh ranking inference; the observed extracted facts are pulled toward
        // that structural importance. PSL and MEBN then both train against this SINGLE consensus —
        // simultaneous online co-training of every structural parameter, not three disconnected learners
        // on three re-derived target sets. (Embeddings are refreshed by the separate offline KGE job.)
        //
        // ── RESUME: seed cascade counter from durable checkpoint on first JVM encounter ──────────────
        // When trainingCheckpointStore is wired and this is the first time this JVM has seen
        // factSheetId (counter not yet in cascadeCounters), load the persisted cascadesCompleted
        // so the in-memory counter continues from where the prior run left off rather than
        // restarting from 0.  This means the cascadeCount increment below yields
        // (checkpoint.cascadesCompleted + 1) on the first cascade after a resume, correctly
        // advancing the in-memory position without repeating already-completed steps.
        cascadeCounters.computeIfAbsent(factSheetId, id -> {
            if (trainingCheckpointStore != null) {
                return trainingCheckpointStore.load(factSheetId)
                        .map(cp -> {
                            log.info("[Grounding cascade factSheet={}] Resuming training from checkpoint: " +
                                    "cascadesCompleted={}, pslVer={}, mebnBackup={}",
                                    factSheetId, cp.cascadesCompleted(), cp.pslWeightVersion(), cp.mebnBackupId());
                            return new AtomicLong(cp.cascadesCompleted());
                        })
                        .orElse(new AtomicLong(0L));
            }
            return new AtomicLong(0L);
        });
        long cascadeCount = cascadeCounters.get(factSheetId).incrementAndGet();
        Map<String, Double> observedTargets = buildObservedTargets(factStore);
        HybridConsensusTrainer.ContextualConsensus contextualConsensus = kbCfg().isLearningEnabled()
                ? deriveHybridConsensus(factSheetId, result.values(), observedTargets)
                : HybridConsensusTrainer.ContextualConsensus.observedOnly(observedTargets);
        Map<String, Double> consensusTargets = contextualConsensus.targets();

        // CONSENSUS event: emit after the hybrid-consensus targets are derived so the UI shows
        // the consensus blending step.
        {
            Map<String, Object> consData = new java.util.LinkedHashMap<>();
            consData.put("observedTargets", observedTargets.size());
            consData.put("consensusTargets", consensusTargets.size());
            consData.put("semanticConsensus", contextualConsensus.semanticConsensus());
            consData.put("semanticAnchors", contextualConsensus.semanticAnchorCount());
            consData.put("inferredSemanticAnchors", contextualConsensus.inferredSemanticAnchorCount());
            consData.put("rankedEntities", contextualConsensus.ranking().size());
            publishProgress(factSheetId, runId, trigger,
                    GroundingProgressEvent.STAGE_CONSENSUS, GroundingProgressEvent.STATUS_DONE,
                    "Hybrid consensus derived: " + consensusTargets.size() + " target(s)", 12, consData);
        }

        // ── STEP 5b (L1 NEW): PSL weight learning — co-train on the hybrid-ranked consensus ──
        // The shared consensus targets blend the extracted observed facts with the hybrid reasoner's
        // ranked response, so PSL weights move toward explaining BOTH the data and the consensus.
        // Run 1 mini-batch step (cheap; accumulates across cascades via warm-start from
        // persisted weights; 1 step keeps wall-time overhead below 10 ms for typical fact
        // sheets of ≤5000 atoms).
        // Uses cascadeWeightStore resolved above (DualStore preferred; falls back to file-backed).
        //
        // ── SUBPROCESS ROUTING: when the ReasoningLearningExecutor is wired (app-main with
        // kompile.learning.subprocess.enabled=true), the compute-heavy weight-learning step is
        // offloaded to the managed learning subprocess (same JVM as KGE training) so that the
        // main app is not burdened. The in-JVM path is the unchanged fallback.
        //
        // ── RESUME CHECKPOINT: track the PSL weight version and MEBN backup ID so we can
        // durably record the training position after each successful cascade for restart-safety.
        int checkpointPslVersion = 0;          // updated after a successful PSL save
        String checkpointMebnBackupId = null;  // updated after a successful MEBN persist
        final PslProgram programForSnapshot;
        if (kbCfg().isLearningEnabled() && cascadeWeightStore != null && !program.rules().isEmpty()
                && !result.values().isEmpty()) {
            publishProgress(factSheetId, runId, trigger,
                    GroundingProgressEvent.STAGE_PSL_LEARNING, GroundingProgressEvent.STATUS_STARTED,
                    "PSL weight learning: 1 mini-batch gradient step", 7, null);
            PslProgram trainedProgram = program;
            try {
                Map<String, Double> softTargets = consensusTargets;
                if (softTargets.isEmpty()) {
                    softTargets = new HashMap<>(result.values());
                }

                String programKey = factSheetId + ":cascade";

                // ── SUBPROCESS PATH ──────────────────────────────────────────────────────────
                // When the learning subprocess is enabled, export the program to the subprocess
                // which runs StructuredPerceptronLearner and writes the weights to the store.
                // The main JVM then reads back the new weights and applies them in-memory.
                // Falls through to the in-JVM path on subprocess failure (non-fatal).
                boolean ranInSubprocess = false;
                if (reasoningLearningExecutor != null && fileBackedWeightStore != null) {
                    try {
                        // Serialize the PSL program as rule texts + atom declarations.
                        List<String> ruleTexts = new ArrayList<>(program.rules().size());
                        for (PslRule r : program.rules()) {
                            ruleTexts.add(r.toString());
                        }
                        Map<String, Double> observedAtomMap = new LinkedHashMap<>();
                        for (String key : program.observedKeys()) {
                            observedAtomMap.put(key, program.value(key));
                        }
                        List<String> targetAtomList = new ArrayList<>(program.targetKeys());

                        // The weight store base directory for the subprocess to write into.
                        String weightStoreDirPath = fileBackedWeightStore
                                .fileWeightStoreFor(String.valueOf(factSheetId))
                                .baseDir()
                                .toString();

                        // Per-iteration crawl UI callback: update the LEARNING step with PSL progress.
                        final String pslCrawlJobId = runId; // use runId as job key for cascade events
                        ReasoningLearningExecutor.ProgressCallback pslCallback =
                                (cJobId, epoch, totalEpochs, loss) -> {
                                    publishProgress(factSheetId, runId, trigger,
                                            GroundingProgressEvent.STAGE_PSL_LEARNING,
                                            GroundingProgressEvent.STATUS_RUNNING,
                                            "PSL iteration " + epoch + "/" + totalEpochs
                                                    + " loss=" + String.format("%.4f", loss),
                                            7, null);
                                };

                        log.info("Grounding cascade factSheet={}: routing PSL weight learning to subprocess",
                                factSheetId);
                        ReasoningLearningExecutor.LearningResult pslResult =
                                reasoningLearningExecutor.runPslLearning(
                                        pslCrawlJobId, factSheetId,
                                        ruleTexts, observedAtomMap, targetAtomList, softTargets,
                                        programKey, weightStoreDirPath,
                                        1, kbCfg().getPslLearningRate(),
                                        1e-4, 64, 0L, 0.1, 0.5, null,
                                        pslCallback);

                        if (pslResult.success()) {
                            // Reload the weights the subprocess just wrote and apply in-memory.
                            Optional<Map<String, Double>> updatedWeights =
                                    cascadeWeightStore.latest(programKey);
                            if (updatedWeights.isPresent()) {
                                trainedProgram = PslWeightLearningService.applyWeights(
                                        program, updatedWeights.get());
                            }
                            checkpointPslVersion = cascadeWeightStore.latestVersion(programKey);
                            ranInSubprocess = true;
                            // Transparent loss=0 diagnosis: when loss is exactly 0.0, this means
                            // MAP posteriors match the consensus targets perfectly — most often because
                            // ALL facts were projected as hard-observed (value=1.0), the MAP solve
                            // pushes derived_X atoms to 1.0, and the consensus is also 1.0.
                            // The learner gradient is zero so weights are not updated.
                            // This is NOT a learner bug but a data signal: the graph needs edges
                            // with confidence < 1.0 for the gradient to be non-zero.
                            if (pslResult.finalLoss() == 0.0) {
                                log.info("Grounding cascade factSheet={}: PSL subprocess done (loss=0.0). "
                                        + "Zero loss indicates MAP posteriors == consensus targets "
                                        + "(likely all-1.0 hard facts). PSL weights unchanged. "
                                        + "No action needed — this is the correct fixed-point for a "
                                        + "fully-certain graph. Weights will update when soft-truth edges appear.",
                                        factSheetId);
                            } else {
                                log.info("Grounding cascade factSheet={}: PSL subprocess done (loss={})",
                                        factSheetId, pslResult.finalLoss());
                            }
                        } else {
                            log.warn("Grounding cascade factSheet={}: PSL subprocess failed ({}), "
                                    + "falling through to in-JVM path",
                                    factSheetId, pslResult.errorMessage());
                        }
                    } catch (Exception subEx) {
                        log.warn("Grounding cascade factSheet={}: PSL subprocess error ({}), "
                                + "falling through to in-JVM path",
                                factSheetId, subEx.getMessage());
                    }
                }

                // ── IN-JVM PATH (fallback or when subprocess is absent) ──────────────────────
                if (!ranInSubprocess) {
                    double[] perRuleMeans = computePerRulePriorMeans(program, factStore);
                    trainedProgram = pslWeightLearner(perRuleMeans).updateOnBatch(program, softTargets, 1);
                    int savedVersion = cascadeWeightStore.save(programKey, trainedProgram.rules());
                    checkpointPslVersion = savedVersion;
                    log.info("Grounding cascade factSheet={}: PSL in-JVM weight training done "
                            + "({} rules persisted, v{})",
                            factSheetId, trainedProgram.rules().size(), savedVersion);
                }

                // ── REAL weight-delta computation (applies to both paths) ─────────────────────
                int rulesUpdated = 0;
                double sumDelta = 0.0;
                double maxDelta = 0.0;
                List<PslRule> beforeRules = program.rules();
                List<PslRule> afterRules  = trainedProgram.rules();
                double weightEpsilon = 0.001;
                if (beforeRules.size() == afterRules.size()) {
                    for (int i = 0; i < beforeRules.size(); i++) {
                        double delta = Math.abs(afterRules.get(i).weight() - beforeRules.get(i).weight());
                        if (delta > weightEpsilon) {
                            rulesUpdated++;
                            sumDelta += delta;
                            if (delta > maxDelta) maxDelta = delta;
                        }
                    }
                }
                double meanDelta = (rulesUpdated > 0) ? sumDelta / rulesUpdated : 0.0;

                Map<String, Object> pslData = new LinkedHashMap<>();
                pslData.put("rulesUpdated", rulesUpdated);
                pslData.put("meanWeightDelta", meanDelta);
                pslData.put("maxWeightDelta", maxDelta);
                pslData.put("savedVersion", checkpointPslVersion);
                pslData.put("subprocess", ranInSubprocess);
                publishProgress(factSheetId, runId, trigger,
                        GroundingProgressEvent.STAGE_PSL_LEARNING, GroundingProgressEvent.STATUS_DONE,
                        "PSL weight update: " + rulesUpdated + " rule(s) changed (meanΔ="
                                + String.format("%.4f", meanDelta) + " maxΔ=" + String.format("%.4f", maxDelta)
                                + (ranInSubprocess ? " [subprocess]" : "") + ")",
                        7, pslData);

                if (fileBackedWeightStore != null && dualStoreFactory == null) {
                    try {
                        Path artifactPath = fileBackedWeightStore.pslArtifactPath(
                                String.valueOf(factSheetId), programKey, checkpointPslVersion);
                        eventPublisher.publishEvent(
                                new ModelTrainedEvent(this, "psl", factSheetId, artifactPath, "psl-cascade"));
                        log.debug("Grounding cascade factSheet={}: published ModelTrainedEvent(psl, v{})",
                                factSheetId, checkpointPslVersion);
                    } catch (Exception e) {
                        log.warn("Grounding cascade factSheet={}: could not publish PSL ModelTrainedEvent — {}",
                                factSheetId, e.getMessage());
                    }
                }

                if (correctionService != null) {
                    emitWeightTunedAuditEvents(factSheetId, program, trainedProgram, runId);
                }
            } catch (Exception e) {
                log.warn("Grounding cascade factSheet={}: PSL weight training failed — {}",
                        factSheetId, e.getMessage());
                publishProgress(factSheetId, runId, trigger,
                        GroundingProgressEvent.STAGE_PSL_LEARNING, GroundingProgressEvent.STATUS_ERROR,
                        "PSL learning failed: " + e.getMessage(), 7, null);
            }
            programForSnapshot = trainedProgram;
        } else {
            programForSnapshot = program;
            publishProgress(factSheetId, runId, trigger,
                    GroundingProgressEvent.STAGE_PSL_LEARNING, GroundingProgressEvent.STATUS_DONE,
                    "PSL weight learning skipped (learning disabled or no weight store)", 7, null);
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
        publishProgress(factSheetId, runId, trigger,
                GroundingProgressEvent.STAGE_JUSTIFICATION, GroundingProgressEvent.STATUS_STARTED,
                "Rebuilding JustificationIndex", 8, null);
        JustificationIndex newIndex = JustificationIndex.build(result, factStore);
        publishProgress(factSheetId, runId, trigger,
                GroundingProgressEvent.STAGE_JUSTIFICATION, GroundingProgressEvent.STATUS_DONE,
                "JustificationIndex rebuilt", 8, null);

        // ── STEP 7: Contradiction scan + TMS retraction ───────────────────────────────
        publishProgress(factSheetId, runId, trigger,
                GroundingProgressEvent.STAGE_CONTRADICTION, GroundingProgressEvent.STATUS_STARTED,
                "Scanning for contradictions", 9, null);
        List<ContradictionDetector.Pair<Fact, Fact>> contradictions =
                ContradictionDetector.findFactContradictions(factStore);
        int retracted = 0;
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
                        retracted++;
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
        Map<String, Object> cData = new java.util.LinkedHashMap<>();
        cData.put("contradictions", contradictions.size());
        cData.put("retracted", retracted);
        publishProgress(factSheetId, runId, trigger,
                GroundingProgressEvent.STAGE_CONTRADICTION, GroundingProgressEvent.STATUS_DONE,
                "Contradiction scan: " + contradictions.size() + " found, " + retracted + " retracted", 9, cData);

        // ── STEP 8: Epoch bump ────────────────────────────────────────────────────────
        publishProgress(factSheetId, runId, trigger,
                GroundingProgressEvent.STAGE_EPOCH, GroundingProgressEvent.STATUS_STARTED,
                "Marking epoch for factSheet=" + factSheetId, 10, null);
        kbGroundingService.markEpoch(factSheetId, runId, newIndex, programForSnapshot.rules());
        publishProgress(factSheetId, runId, trigger,
                GroundingProgressEvent.STAGE_EPOCH, GroundingProgressEvent.STATUS_DONE,
                "Epoch marked: runId=" + runId, 10, null);

        // ── STEP 9 (L1): Online MEBN weight learning — incremental, EVERY cascade ────────────
        // MEBN finite-difference is O(|edges|) inferences per step. Under partial observability there is
        // no complete target to converge to, so we take ONE warm-started online step per cascade
        // (maxEpochs=1) that ACCUMULATES across cascades. Co-trains on the SAME consensus signal as PSL.
        // SameDiffMebnStrengthLearner creates a new SameDiff computation graph per epoch — the native
        // ND4J allocations are memory-risky in the main JVM. When ReasoningLearningExecutor is present,
        // the SameDiff step is offloaded to the bounded learning subprocess (same memory cap as KGE).
        if (kbCfg().isLearningEnabled() && mebnWeightAdapter != null) {
            MTheory theory = mebnTheories.get(factSheetId);
            ReasoningGraph mebnGraph = reasoningGraphs.get(factSheetId);
            if (theory != null && mebnGraph != null && !result.values().isEmpty()) {
                publishProgress(factSheetId, runId, trigger,
                        GroundingProgressEvent.STAGE_MEBN_LEARNING, GroundingProgressEvent.STATUS_STARTED,
                        "MEBN online weight step (cascade " + cascadeCount + ")", 11, null);
                try {
                    Map<String, Double> strengthsBefore = snapshotEdgeStrengths(theory);
                    mebnWeightAdapter.load(factSheetId, theory);
                    Map<String, Double> observations = consensusTargets.isEmpty()
                            ? new HashMap<>(result.values()) : consensusTargets;

                    // ── SUBPROCESS PATH ──────────────────────────────────────────────────────
                    // When the learning subprocess is enabled, export the edge strengths and the
                    // pre-computed tensor batch to the subprocess, which runs the SameDiff autodiff
                    // gradient step in a bounded native JVM, then writes mebn-weights.json. The
                    // main JVM reloads via mebnWeightAdapter.load() after.
                    boolean mebnRanInSubprocess = false;
                    if (reasoningLearningExecutor != null) {
                        try {
                            // Collect edges and current strengths.
                            List<MebnWeightLearner.Edge> edges =
                                    SameDiffMebnStrengthLearner.collectEdges(theory);
                            if (!edges.isEmpty()) {
                                int M = edges.size();
                                List<String> edgeKeys = new ArrayList<>(M);
                                List<Double> currentStrengthsList = new ArrayList<>(M);
                                for (MebnWeightLearner.Edge e : edges) {
                                    // Key format matches what the subprocess writes back:
                                    // "fragName:parent->child" — matches snapshotEdgeStrengths key
                                    edgeKeys.add(e.mfrag().getName() + ":" + e.parent() + "->" + e.child());
                                    currentStrengthsList.add(e.mfrag().getEdgeStrength(e.parent(), e.child()));
                                }

                                // Pre-compute the [E × M] tensor batch in the main JVM
                                // (requires one MEBN inference call — stays in main JVM).
                                MebnInferenceService inferSvc = new MebnInferenceService();
                                Map<String, Double> posteriors = inferSvc.infer(mebnGraph, theory, Map.of());

                                SameDiffMebnStrengthLearner.TensorBatch batch =
                                        SameDiffMebnStrengthLearner.buildTensorBatch(
                                                edges, posteriors, observations);

                                if (batch.rowCount() > 0) {
                                    Path mebnWeightsPath = mebnWeightAdapter.mebnArtifactPath(factSheetId);

                                    final String mebnCrawlJobId = runId;
                                    ReasoningLearningExecutor.ProgressCallback mebnCallback =
                                            (cJobId, epoch, totalEpochs, loss) -> {
                                                publishProgress(factSheetId, runId, trigger,
                                                        GroundingProgressEvent.STAGE_MEBN_LEARNING,
                                                        GroundingProgressEvent.STATUS_RUNNING,
                                                        "MEBN epoch " + epoch + "/" + totalEpochs
                                                                + " loss=" + String.format("%.4f", loss),
                                                        11, null);
                                            };

                                    log.info("Grounding cascade factSheet={}: routing MEBN strength learning to subprocess",
                                            factSheetId);
                                    ReasoningLearningExecutor.LearningResult mebnResult =
                                            reasoningLearningExecutor.runMebnLearning(
                                                    mebnCrawlJobId, factSheetId,
                                                    edgeKeys, currentStrengthsList,
                                                    batch.pParentMatrix(), batch.targetMatrix(),
                                                    mebnWeightsPath.toString(),
                                                    1, kbCfg().getPslLearningRate(), mebnCallback);

                                    if (mebnResult.success()) {
                                        // Reload the updated strengths the subprocess wrote.
                                        mebnWeightAdapter.load(factSheetId, theory);
                                        checkpointMebnBackupId = "cascade-" + cascadeCount;
                                        mebnRanInSubprocess = true;
                                        log.info("Grounding cascade factSheet={}: MEBN subprocess done (loss={})",
                                                factSheetId, mebnResult.finalLoss());
                                    } else {
                                        log.warn("Grounding cascade factSheet={}: MEBN subprocess failed ({}), "
                                                + "falling through to in-JVM path",
                                                factSheetId, mebnResult.errorMessage());
                                    }
                                } else {
                                    log.debug("Grounding cascade factSheet={}: MEBN tensor batch empty, "
                                            + "falling through to in-JVM path", factSheetId);
                                }
                            }
                        } catch (Exception subEx) {
                            log.warn("Grounding cascade factSheet={}: MEBN subprocess error ({}), "
                                    + "falling through to in-JVM path",
                                    factSheetId, subEx.getMessage());
                        }
                    }

                    // ── IN-JVM PATH (fallback or when subprocess is absent) ─────────────────
                    if (!mebnRanInSubprocess) {
                        mebnWeightLearner.learn(theory, mebnGraph, observations, 1);
                        mebnWeightAdapter.persist(factSheetId, theory);
                        checkpointMebnBackupId = "cascade-" + cascadeCount;
                    }

                    // Compute strength deltas for progress event (both paths).
                    Map<String, Double> strengthsAfter = snapshotEdgeStrengths(theory);
                    double sumStrengthDelta = 0.0;
                    double maxStrengthDelta = 0.0;
                    int edgesUpdated = 0;
                    for (Map.Entry<String, Double> entry : strengthsBefore.entrySet()) {
                        Double after = strengthsAfter.get(entry.getKey());
                        if (after != null) {
                            double delta = Math.abs(after - entry.getValue());
                            if (delta > 1e-6) {
                                edgesUpdated++;
                                sumStrengthDelta += delta;
                                if (delta > maxStrengthDelta) maxStrengthDelta = delta;
                            }
                        }
                    }
                    double meanStrengthDelta = (edgesUpdated > 0) ? sumStrengthDelta / edgesUpdated : 0.0;

                    log.debug("Grounding cascade factSheet={}: MEBN online step done (cascade {})",
                            factSheetId, cascadeCount);

                    Map<String, Object> mebnData = new LinkedHashMap<>();
                    mebnData.put("cascadeCount", cascadeCount);
                    mebnData.put("edgesUpdated", edgesUpdated);
                    mebnData.put("meanStrengthDelta", meanStrengthDelta);
                    mebnData.put("maxStrengthDelta", maxStrengthDelta);
                    mebnData.put("subprocess", mebnRanInSubprocess);
                    publishProgress(factSheetId, runId, trigger,
                            GroundingProgressEvent.STAGE_MEBN_LEARNING, GroundingProgressEvent.STATUS_DONE,
                            "MEBN step done: " + edgesUpdated + " edge(s) updated (meanΔ="
                                    + String.format("%.4f", meanStrengthDelta) + ")"
                                    + (mebnRanInSubprocess ? " [subprocess]" : ""), 11, mebnData);

                    if (cascadeCount == 1L || cascadeCount % Math.max(1, kbCfg().getMebnLearningInterval()) == 0) {
                        try {
                            Path mebnArtifact = mebnWeightAdapter.mebnArtifactPath(factSheetId);
                            eventPublisher.publishEvent(
                                    new ModelTrainedEvent(this, "mebn", factSheetId, mebnArtifact, "mebn-grounding"));
                            log.debug("Grounding cascade factSheet={}: published ModelTrainedEvent(mebn, cascade {})",
                                    factSheetId, cascadeCount);
                        } catch (Exception e) {
                            log.warn("Grounding cascade factSheet={}: could not publish MEBN ModelTrainedEvent — {}",
                                    factSheetId, e.getMessage());
                        }
                    }
                } catch (Exception e) {
                    log.warn("Grounding cascade factSheet={}: MEBN online step failed — {}",
                            factSheetId, e.getMessage());
                    publishProgress(factSheetId, runId, trigger,
                            GroundingProgressEvent.STAGE_MEBN_LEARNING, GroundingProgressEvent.STATUS_ERROR,
                            "MEBN step failed: " + e.getMessage(), 11, null);
                }
            } else {
                publishProgress(factSheetId, runId, trigger,
                        GroundingProgressEvent.STAGE_MEBN_LEARNING, GroundingProgressEvent.STATUS_DONE,
                        "MEBN learning skipped (no theory or empty result)", 11, null);
            }
        } else {
            publishProgress(factSheetId, runId, trigger,
                    GroundingProgressEvent.STAGE_MEBN_LEARNING, GroundingProgressEvent.STATUS_DONE,
                    "MEBN learning skipped (learning disabled or no adapter)", 11, null);
        }

        // ── TRAINING CHECKPOINT: persist position after a successful cascade ─────────
        // Write (or update) the durable training checkpoint so a DERIVATION that is killed or
        // timed-out between cascades can resume from this position rather than cold-starting.
        // Only written when training ran (PSL saved a new version OR MEBN persisted a step);
        // skipped when learning is disabled or the checkpoint store is not wired (tests).
        if (trainingCheckpointStore != null
                && kbCfg().isLearningEnabled()
                && (checkpointPslVersion > 0 || checkpointMebnBackupId != null)) {
            try {
                String pslKeyForCp = factSheetId + ":cascade";
                TrainingCheckpointStore.TrainingCheckpoint cp = TrainingCheckpointStore.of(
                        factSheetId, cascadeCount, pslKeyForCp,
                        checkpointPslVersion, checkpointMebnBackupId);
                trainingCheckpointStore.save(cp);
            } catch (Exception e) {
                // Non-fatal: checkpoint is best-effort durability, not required for correctness
                log.warn("[Grounding cascade factSheet={}] Could not write training checkpoint: {}",
                        factSheetId, e.getMessage());
            }
        }

        // ── COMPLETE ─────────────────────────────────────────────────────────────────
        Map<String, Object> completeData = new java.util.LinkedHashMap<>();
        completeData.put("versionsWritten", versionsWritten);
        completeData.put("retractedAtomKeys", retractedAtomKeys.size());
        completeData.put("cascadeCount", cascadeCount);
        log.info("Grounding cascade factSheet={}: {} InferredFact version(s) written, {} retracted, runId={}",
                factSheetId, versionsWritten, retractedAtomKeys.size(), runId);
        publishProgress(factSheetId, runId, trigger,
                GroundingProgressEvent.STAGE_COMPLETE, GroundingProgressEvent.STATUS_DONE,
                "Cascade complete: " + versionsWritten + " version(s) written, " + retractedAtomKeys.size()
                        + " retracted", TOTAL_CASCADE_STEPS - 1, completeData);
        return new RegroundResult(versionsWritten, runId, Set.copyOf(retractedAtomKeys));
    }

    /**
     * Snapshot the edge strengths of an {@link MTheory} as a flat map of "frag:parent->child" → strength.
     * Used before/after {@link MebnWeightLearner#learn} to compute real strength deltas.
     */
    private static Map<String, Double> snapshotEdgeStrengths(MTheory theory) {
        Map<String, Double> snapshot = new java.util.LinkedHashMap<>();
        for (MFrag frag : theory.getMFrags()) {
            Map<String, Double> strengths = frag.getEdgeStrengths();
            if (strengths != null) {
                for (Map.Entry<String, Double> e : strengths.entrySet()) {
                    snapshot.put(frag.getName() + ":" + e.getKey(), e.getValue());
                }
            }
        }
        return snapshot;
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

        // ── Step 3b-owl: also inject OWL 2 RL-derived rules (subClassOf transitivity,
        //    transitive property closure) on top of the plain DOMAIN/RANGE axioms above.
        injectOwlDerivedRules(program, factSheetId, weight);
    }

    /**
     * Inject additional PSL rules derived from OWL 2 RL entailments via
     * {@link OwlDerivedRuleProvider}. This is a strict no-op when the provider is not wired
     * (plain-Java tests, Spring contexts without kompile-app-main).
     */
    private void injectOwlDerivedRules(PslProgram program, long factSheetId, double weight) {
        if (owlDerivedRuleProvider == null) return;

        List<String> owlRules;
        try {
            owlRules = owlDerivedRuleProvider.owlDerivedPslRules(factSheetId, weight);
        } catch (Exception e) {
            log.warn("OwlDerivedRuleProvider: exception deriving OWL rules for factSheet={} — {}",
                    factSheetId, e.getMessage());
            return;
        }

        if (owlRules == null || owlRules.isEmpty()) return;

        int owlAdded = 0;
        for (String ruleStr : owlRules) {
            try {
                program.addRule(ruleStr);
                owlAdded++;
            } catch (Exception e) {
                log.warn("OwlDerivedRuleProvider: could not parse OWL-derived rule '{}' for factSheet={} — {}",
                        ruleStr, factSheetId, e.getMessage());
            }
        }
        if (owlAdded > 0) {
            log.debug("OwlDerivedRuleProvider: injected {} OWL-RL-derived rule(s) for factSheet={}",
                    owlAdded, factSheetId);
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
     * Overload used by the instance path so the configurable rule weight (from
     * {@link KbConfig#getPslDefaultRuleWeight()}) can be passed in without breaking the static test API.
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
     * Aggregate this cascade's MAP posteriors into a per-entity structural importance score: each
     * entity's score is the mean soft-truth of the atoms that mention it. This reuses the inference we
     * already ran — no second ranking pass — which is what lets the consensus be derived EVERY cascade
     * for online co-training. (Argument position is ignored; an entity central to many high-truth atoms
     * scores high.)
     */
    private Map<String, Double> entityImportanceFromMap(Map<String, Double> mapValues) {
        Map<String, double[]> acc = new HashMap<>(); // entity -> [sum, count]
        for (Map.Entry<String, Double> e : mapValues.entrySet()) {
            String atom = e.getKey();
            int open = atom.indexOf('(');
            int close = atom.lastIndexOf(')');
            if (open < 0 || close <= open) {
                continue;
            }
            for (String raw : atom.substring(open + 1, close).split(",")) {
                String ent = raw.trim();
                if (ent.isEmpty()) {
                    continue;
                }
                double[] a = acc.computeIfAbsent(ent, k -> new double[2]);
                a[0] += e.getValue();
                a[1] += 1.0;
            }
        }
        Map<String, Double> out = new HashMap<>(acc.size());
        for (Map.Entry<String, double[]> e : acc.entrySet()) {
            out.put(e.getKey(), e.getValue()[0] / e.getValue()[1]);
        }
        return out;
    }

    /**
     * Derive a joint structural-semantic training signal without rerunning inference. The structural
     * component is aggregated from this cascade's MAP posteriors; the semantic component resolves
     * direct, relation, and nearby graph embeddings from the latest registered reasoning graph.
     */
    private HybridConsensusTrainer.ContextualConsensus deriveHybridConsensus(
            long factSheetId, Map<String, Double> mapValues, Map<String, Double> observed) {
        if (observed == null || observed.isEmpty()) {
            return HybridConsensusTrainer.ContextualConsensus.observedOnly(
                    observed == null ? Map.of() : observed);
        }
        try {
            Map<String, Double> entityScores = entityImportanceFromMap(mapValues);
            ReasoningGraph graph = reasoningGraphs.get(factSheetId);
            HybridConsensusTrainer.ContextualConsensus consensus =
                    HybridConsensusTrainer.contextualConsensus(
                            graph,
                            observed,
                            entityScores,
                            Map.of(),
                            new HybridReasoner().semanticResolutionHops(2),
                            kbCfg().getHybridConsensusWeight());
            log.debug("Consensus: {} structural entity scores, semantic={}, anchors={} "
                            + "(inferred={}) -> {} targets (w={})",
                    entityScores.size(), consensus.semanticConsensus(),
                    consensus.semanticAnchorCount(), consensus.inferredSemanticAnchorCount(),
                    consensus.targets().size(), kbCfg().getHybridConsensusWeight());
            return consensus;
        } catch (Exception e) {
            log.warn("Consensus derivation failed - using observed targets: {}", e.getMessage());
            return HybridConsensusTrainer.ContextualConsensus.observedOnly(observed);
        }
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
     * <p>Reads per-band prior means from {@link KbConfig} via {@link #kbCfg()}.
     * All four means are now config-driven (no hard-coded literals):
     * <ul>
     *   <li>ESTABLISHED → {@code kbRuleWeightEstablishedMean} (default 0.9)</li>
     *   <li>HIGH        → {@code kbRuleWeightHighMean}        (default 0.7)</li>
     *   <li>PROBABLE    → {@code kbRuleWeightProbableMean}    (default 0.4)</li>
     *   <li>SPECULATIVE/SUPPRESSED → {@code kbRuleWeightSpeculativeMean} (default 0.15)</li>
     * </ul>
     */
    private double bandPriorMean(StrengthBand band, KbConfig c) {
        switch (band) {
            case ESTABLISHED: return c.getRuleWeightEstablishedMean();
            case HIGH:        return c.getRuleWeightHighMean();
            case PROBABLE:    return c.getRuleWeightProbableMean();
            case SPECULATIVE:
            case SUPPRESSED:
            default:          return c.getRuleWeightSpeculativeMean();
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
