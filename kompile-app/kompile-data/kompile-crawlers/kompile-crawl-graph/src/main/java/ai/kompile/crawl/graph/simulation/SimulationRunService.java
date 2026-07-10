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
package ai.kompile.crawl.graph.simulation;

import ai.kompile.core.crawl.graph.CrawlProgressEvent;
import ai.kompile.core.graphrag.GraphConstants;
import ai.kompile.crawl.graph.GraphHydrationOrchestrator;
import ai.kompile.crawl.graph.HydrationConfig;
import ai.kompile.crawl.graph.HydrationResult;
import ai.kompile.crawl.graph.LearningMetrics;
import ai.kompile.graph.reasoning.fol.InferredFact;
import ai.kompile.graph.reasoning.simulation.GraphScenario;
import ai.kompile.graph.reasoning.simulation.GroundTruthManifest;
import ai.kompile.graph.reasoning.simulation.GroundTruthManifest.ExpectedEdge;
import ai.kompile.graph.reasoning.simulation.PatternRecoveryScorer;
import ai.kompile.graph.reasoning.simulation.PatternRecoveryScorer.RecoveredEdge;
import ai.kompile.graph.reasoning.simulation.PatternRecoveryScorer.RecoveredState;
import ai.kompile.graph.reasoning.simulation.PatternRecoveryScorer.ScoreReport;
import ai.kompile.graph.reasoning.simulation.ScenarioEdge;
import ai.kompile.graph.reasoning.simulation.ScenarioNode;
import ai.kompile.graph.reasoning.simulation.ScenarioRun;
import ai.kompile.graph.reasoning.simulation.Scenarios;
import ai.kompile.graph.reasoning.simulation.TickBatch;
import ai.kompile.knowledgegraph.confidence.KbConfig;
import ai.kompile.knowledgegraph.confidence.KbConfigManager;
import ai.kompile.knowledgegraph.domain.EdgeProvenance;
import ai.kompile.knowledgegraph.domain.EdgeType;
import ai.kompile.knowledgegraph.domain.GraphEdge;
import ai.kompile.knowledgegraph.domain.GraphNode;
import ai.kompile.knowledgegraph.domain.NodeLevel;
import ai.kompile.knowledgegraph.grounding.KbGroundingService;
import ai.kompile.knowledgegraph.service.KnowledgeGraphService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Orchestrates graph-simulator runs: hydrates a sandbox fact sheet from a {@link GraphScenario}
 * dataset (batched, matrix-store writes only), drives the EXISTING reasoning pass
 * ({@link GraphHydrationOrchestrator}) over it, and scores what the stack learned against the
 * scenario's {@link GroundTruthManifest} via {@link PatternRecoveryScorer}.
 *
 * <p>Deliberately owns no reasoning logic. Inferred edges appear in the graph through the live
 * cascade's own materialization (enabled by default via
 * {@code KbConfig.cascadeMaterializeInferredEnabled}); this service only reads them back.
 * Progress streams over the existing crawl SSE channel: every state change publishes a
 * {@link CrawlProgressEvent} whose jobId is the runId, so the UI subscribes to
 * {@code /api/crawl-events/stream/&lt;runId&gt;} with zero new SSE plumbing.</p>
 *
 * <p>The fact-sheet ROW lifecycle (create/delete) lives with the caller (app-main controller);
 * this service only ever touches the graph side, keyed by factSheetId.</p>
 */
@Service
public class SimulationRunService {

    private static final Logger log = LoggerFactory.getLogger(SimulationRunService.class);
    private static final TypeReference<Map<String, Object>> STRING_OBJECT_MAP =
            new TypeReference<>() { };

    /** Cap on inferred-fact inspector entries kept per run (UI payload bound). */
    private static final int MAX_INSPECTOR_FACTS = 200;
    /** Cap on transcript lines kept per run. */
    private static final int MAX_TRANSCRIPT_LINES = 500;

    public enum RunStatus { CONFIGURED, HYDRATING, REASONING, SCORING, PAUSED, WAITING, COMPLETED, ERROR, DISPOSED }

    /** ALL = hydrate everything then reason once; STEP = manual ticks; PLAY = auto-tick loop. */
    public enum RunMode { ALL, STEP, PLAY }

    /** Caller-facing run parameters (scenario + engine toggles). */
    public record StartSpec(
            String scenarioId,
            long seed,
            Map<String, Object> params,
            RunMode mode,
            int reasonEveryK,
            Set<String> enabledStages,
            double confidencePruneThreshold,
            boolean dryRun) {

        public StartSpec {
            params = (params == null) ? Map.of() : Map.copyOf(params);
            enabledStages = (enabledStages == null) ? Set.of() : Set.copyOf(enabledStages);
        }
    }

    /** One timeline entry: a tick application and/or reasoning pass with its outcomes. */
    public record TickReport(int tick, int nodesApplied, int edgesApplied, boolean reasoned,
                             Map<String, Object> hydration, Map<String, Object> learning,
                             Map<String, Object> score, long atMs) {}

    /** JSON view of a run for the REST surface. */
    public record RunSnapshot(String runId, long factSheetId, String scenarioId, String scenarioName,
                              long seed, String status, String mode, boolean paused,
                              int ticksApplied, int totalTicks, int nodesCreated, int edgesCreated,
                              int reasonEveryK, Set<String> enabledStages, boolean dryRun,
                              Map<String, Object> truthSummary, List<TickReport> timeline,
                              Map<String, Object> lastScore, List<Map<String, Object>> inferredFacts,
                              List<String> transcript, String error, long createdAtMs) {}

    /** One ground-truth item resolved to store node ids for the visualizer overlay. */
    public record TruthEdgeView(String family, String sourceKey, String targetKey, String relationType,
                                String why, String sourceNodeId, String targetNodeId, String status) {}

    /** Ground-truth reveal payload. */
    public record TruthOverlay(List<TruthEdgeView> edges,
                               List<List<Map<String, String>>> duplicateSets,
                               List<List<Map<String, String>>> communities,
                               List<Map<String, Object>> generatingRules,
                               List<Map<String, Object>> hallucinatedEdges) {}

    private final ApplicationEventPublisher eventPublisher;

    /**
     * Local mapper for edge meta-JSON and journal writes — deliberately NOT injected so this
     * service instantiates in minimal Spring test contexts that define no Jackson bean
     * (e.g. crawl-graph's extraction end-to-end TestConfig).
     */
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired(required = false)
    @Nullable
    private KnowledgeGraphService knowledgeGraphService;

    @Autowired(required = false)
    @Nullable
    private GraphHydrationOrchestrator hydrationOrchestrator;

    @Autowired(required = false)
    @Nullable
    private KbGroundingService groundingService;

    @Autowired(required = false)
    @Nullable
    private KbConfigManager kbConfigManager;

    private final Map<String, SimRun> runs = new ConcurrentHashMap<>();

    public SimulationRunService(ApplicationEventPublisher eventPublisher) {
        this.eventPublisher = eventPublisher;
    }

    // ── public API ─────────────────────────────────────────────────────────────────────

    /** Descriptors of all built-in scenarios (for the UI's scenario picker). */
    public List<Map<String, Object>> listScenarios() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (GraphScenario s : Scenarios.builtIn()) {
            out.add(objectMapper.convertValue(s.describe(), STRING_OBJECT_MAP));
        }
        return out;
    }

    /**
     * Create and start a run against an already-created sandbox fact sheet. Generation happens
     * synchronously (fast, pure); hydration/reasoning run on the run's own worker thread.
     *
     * @throws IllegalArgumentException on unknown scenario or cap violation
     * @throws IllegalStateException    when the graph store is not available
     */
    public RunSnapshot startRun(long factSheetId, StartSpec spec) {
        if (knowledgeGraphService == null) {
            throw new IllegalStateException("Knowledge graph store is not available");
        }
        GraphScenario scenario = Scenarios.byId(spec.scenarioId())
                .orElseThrow(() -> new IllegalArgumentException("Unknown scenario: " + spec.scenarioId()));

        ScenarioRun dataset = scenario.generate(spec.seed(), spec.params());
        KbConfig cfg = kbCfg();
        if (dataset.totalNodes() > cfg.getSimMaxNodesPerRun()) {
            throw new IllegalArgumentException("Scenario would hydrate " + dataset.totalNodes()
                    + " nodes, above kbSimMaxNodesPerRun=" + cfg.getSimMaxNodesPerRun());
        }
        if (dataset.totalEdges() > cfg.getSimMaxEdgesPerRun()) {
            throw new IllegalArgumentException("Scenario would hydrate " + dataset.totalEdges()
                    + " edges, above kbSimMaxEdgesPerRun=" + cfg.getSimMaxEdgesPerRun());
        }

        SimRun run = new SimRun("sim-" + UUID.randomUUID().toString().substring(0, 8),
                factSheetId, scenario.describe().name(), spec, dataset);
        runs.put(run.runId, run);
        journal(run, "run.json", Map.of(
                "runId", run.runId, "factSheetId", factSheetId,
                "scenarioId", spec.scenarioId(), "seed", spec.seed(), "params", spec.params(),
                "mode", spec.mode().name(), "totalNodes", dataset.totalNodes(),
                "totalEdges", dataset.totalEdges()));
        journal(run, "ground-truth.json", dataset.groundTruth());
        publish(run, CrawlProgressEvent.EventType.STARTED,
                "Simulation run created: " + spec.scenarioId() + " seed=" + spec.seed()
                        + " (" + dataset.totalNodes() + " nodes / " + dataset.totalEdges()
                        + " edges over " + dataset.ticks().size() + " ticks)");

        if (spec.mode() != RunMode.STEP) {
            submitPlay(run);
        } else {
            run.status = RunStatus.WAITING;
        }
        return snapshot(run);
    }

    /** All runs, newest first. */
    public List<RunSnapshot> listRuns() {
        return runs.values().stream()
                .sorted(Comparator.comparingLong((SimRun r) -> r.createdAtMs).reversed())
                .map(this::snapshot)
                .toList();
    }

    public RunSnapshot getRun(String runId) {
        return snapshot(required(runId));
    }

    /** Apply the next tick (STEP mode or while paused); reasons when the cadence hits. */
    public RunSnapshot step(String runId) {
        SimRun run = required(runId);
        run.executor.submit(() -> safely(run, () -> {
            if (run.nextTick < run.dataset.ticks().size()) {
                applyTick(run);
                maybeReasonAndScore(run, false);
                if (run.status != RunStatus.ERROR) {
                    boolean lastTick = run.nextTick >= run.dataset.ticks().size();
                    if (lastTick) {
                        maybeReasonAndScore(run, true);
                        run.status = RunStatus.COMPLETED;
                        publish(run, CrawlProgressEvent.EventType.COMPLETED, "Run complete");
                    } else {
                        run.status = RunStatus.WAITING;
                    }
                }
            }
        }));
        return snapshot(run);
    }

    /** Resume auto-ticking. */
    public RunSnapshot play(String runId) {
        SimRun run = required(runId);
        run.paused = false;
        submitPlay(run);
        return snapshot(run);
    }

    /** Pause auto-ticking after the in-flight step finishes. */
    public RunSnapshot pause(String runId) {
        SimRun run = required(runId);
        run.paused = true;
        if (run.status == RunStatus.HYDRATING || run.status == RunStatus.WAITING) {
            run.status = RunStatus.PAUSED;
        }
        publish(run, CrawlProgressEvent.EventType.PHASE_CHANGE, "Paused");
        return snapshot(run);
    }

    /** Re-run reasoning + scoring now (re-entrant; usable any number of times). */
    public RunSnapshot reasonNow(String runId) {
        SimRun run = required(runId);
        run.executor.submit(() -> safely(run, () -> {
            reasonAndScore(run);
            run.status = run.nextTick >= run.dataset.ticks().size() ? RunStatus.COMPLETED : RunStatus.WAITING;
        }));
        return snapshot(run);
    }

    /** Ground truth resolved to node ids, with per-item recovery status for the overlay. */
    public TruthOverlay groundTruth(String runId) {
        SimRun run = required(runId);
        GroundTruthManifest truth = run.dataset.groundTruth();
        List<TruthEdgeView> edges = new ArrayList<>();
        synchronized (run) {
            for (ExpectedEdge e : truth.expectedInferredEdges()) {
                edges.add(truthView(run, PatternRecoveryScorer.FAMILY_INFERRED_EDGES, e));
            }
            for (ExpectedEdge e : truth.expectedCausalLinks()) {
                edges.add(truthView(run, PatternRecoveryScorer.FAMILY_CAUSAL_LINKS, e));
            }
            for (ExpectedEdge e : truth.forbiddenCausalLinks()) {
                String status = run.lastScore == null ? "pending"
                        : (matched(run, e) ? "violation" : "avoided");
                edges.add(new TruthEdgeView("forbidden", e.sourceKey(), e.targetKey(), e.relationType(),
                        e.why(), run.keyToNodeId.get(e.sourceKey()), run.keyToNodeId.get(e.targetKey()), status));
            }
            List<Map<String, Object>> hallucinated = new ArrayList<>();
            for (RecoveredEdge h : run.lastHallucinated) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("sourceKey", h.sourceKey());
                m.put("targetKey", h.targetKey());
                m.put("relationType", h.relationType());
                m.put("confidence", h.confidence());
                m.put("sourceNodeId", run.keyToNodeId.get(h.sourceKey()));
                m.put("targetNodeId", run.keyToNodeId.get(h.targetKey()));
                hallucinated.add(m);
            }
            List<Map<String, Object>> rules = new ArrayList<>();
            truth.generatingRules().forEach(r -> rules.add(Map.of("rule", r.rule(), "weight", r.weight())));
            return new TruthOverlay(edges,
                    groupViews(run, truth.duplicateSets()),
                    groupViews(run, truth.communities()),
                    rules, hallucinated);
        }
    }

    /** Stop the run's worker and mark it disposed. The caller owns fact-sheet/graph deletion. */
    public void dispose(String runId) {
        SimRun run = required(runId);
        run.paused = true;
        run.status = RunStatus.DISPOSED;
        run.executor.shutdownNow();
        runs.remove(runId);
        publish(run, CrawlProgressEvent.EventType.CANCELLED, "Run disposed");
    }

    /** The fact sheet backing a run (for the caller's delete/activate operations). */
    public long factSheetIdOf(String runId) {
        return required(runId).factSheetId;
    }

    // ── run loop ───────────────────────────────────────────────────────────────────────

    private void submitPlay(SimRun run) {
        run.executor.submit(() -> safely(run, () -> {
            while (!run.paused && run.status != RunStatus.DISPOSED
                    && run.nextTick < run.dataset.ticks().size()) {
                applyTick(run);
                maybeReasonAndScore(run, false);
            }
            if (run.nextTick >= run.dataset.ticks().size() && run.status != RunStatus.DISPOSED) {
                maybeReasonAndScore(run, true);
                run.status = RunStatus.COMPLETED;
                publish(run, CrawlProgressEvent.EventType.COMPLETED, "Run complete: "
                        + run.nodesCreated + " nodes, " + run.edgesCreated + " edges hydrated");
            } else if (run.paused && run.status != RunStatus.DISPOSED) {
                run.status = RunStatus.PAUSED;
            }
        }));
    }

    /** Reason at the configured cadence: every K ticks, or (K<=0) only at the end. */
    private void maybeReasonAndScore(SimRun run, boolean atEnd) {
        int k = run.spec.reasonEveryK();
        boolean lastTickDone = run.nextTick >= run.dataset.ticks().size();
        boolean cadenceHit = k > 0 && run.nextTick % k == 0;
        if ((atEnd && lastTickDone && !run.reasonedAfterLastTick) || (!atEnd && cadenceHit)) {
            reasonAndScore(run);
        }
    }

    private void applyTick(SimRun run) {
        TickBatch tick = run.dataset.ticks().get(run.nextTick);
        run.status = RunStatus.HYDRATING;
        publish(run, CrawlProgressEvent.EventType.PROGRESS, "Hydrating tick " + (tick.tickIndex() + 1)
                + "/" + run.dataset.ticks().size() + " (" + tick.nodes().size() + " nodes, "
                + tick.edges().size() + " edges)");

        // Nodes: one batched write; keep the scenario-key → nodeId map for scoring/overlays.
        List<KnowledgeGraphService.NodeSpec> nodeSpecs = new ArrayList<>(tick.nodes().size());
        for (ScenarioNode n : tick.nodes()) {
            Map<String, Object> meta = new LinkedHashMap<>(n.metadata());
            meta.put("entity_type", n.entityType());
            meta.put(GraphConstants.META_SOURCE, "graph-simulator");
            meta.put("sim.scenario", run.spec.scenarioId());
            meta.put("sim.key", n.key());
            if (n.occurredAtEpochMs() != null) {
                meta.putIfAbsent("occurredAt", Instant.ofEpochMilli(n.occurredAtEpochMs()).toString());
            }
            nodeSpecs.add(new KnowledgeGraphService.NodeSpec(NodeLevel.ENTITY,
                    "sim:" + run.runId + ":" + n.key(), n.name(),
                    n.entityType() + " (simulated — " + run.spec.scenarioId() + ")", meta));
        }
        List<GraphNode> created = knowledgeGraphService.createNodesBatch(nodeSpecs, run.factSheetId);
        for (int i = 0; i < created.size() && i < tick.nodes().size(); i++) {
            GraphNode node = created.get(i);
            if (node != null && node.getNodeId() != null) {
                run.keyToNodeId.put(tick.nodes().get(i).key(), node.getNodeId());
                run.nodeIdToKey.put(node.getNodeId(), tick.nodes().get(i).key());
            }
        }
        run.nodesCreated += created.size();

        // Edges: one batched write, mirroring the extractors' semantic-relation conventions.
        List<KnowledgeGraphService.EdgeSpec> edgeSpecs = new ArrayList<>(tick.edges().size());
        for (ScenarioEdge e : tick.edges()) {
            String srcId = run.keyToNodeId.get(e.sourceKey());
            String dstId = run.keyToNodeId.get(e.targetKey());
            if (srcId == null || dstId == null) {
                continue;   // generator invariant guarantees this never fires
            }
            Map<String, Object> meta = new LinkedHashMap<>(e.metadata());
            meta.put(GraphConstants.META_SOURCE, "graph-simulator");
            meta.put("sim.noise", e.noise());
            String metaJson = toJson(meta);
            String description = (e.noise() ? "Simulated (noise): " : "Simulated: ") + e.relationType();
            edgeSpecs.add(new KnowledgeGraphService.EdgeSpec(srcId, dstId, EdgeType.USER_DEFINED,
                    e.confidence(), description, e.relationType(), metaJson,
                    EdgeProvenance.EXTRACTED, run.factSheetId));
        }
        int edgesCreated = edgeSpecs.isEmpty() ? 0 : knowledgeGraphService.createEdgesBatch(edgeSpecs);
        run.edgesCreated += edgesCreated;
        run.nextTick++;
        run.reasonedAfterLastTick = false;
        synchronized (run) {
            run.timeline.add(new TickReport(tick.tickIndex(), created.size(), edgesCreated,
                    false, Map.of(), Map.of(), Map.of(), System.currentTimeMillis()));
        }
        publish(run, CrawlProgressEvent.EventType.PROGRESS, "Tick " + (tick.tickIndex() + 1)
                + " applied: +" + created.size() + " nodes, +" + edgesCreated + " edges");
    }

    private void reasonAndScore(SimRun run) {
        if (hydrationOrchestrator == null) {
            transcript(run, "Reasoning skipped: GraphHydrationOrchestrator not available");
            return;
        }
        run.status = RunStatus.REASONING;
        publish(run, CrawlProgressEvent.EventType.PHASE_CHANGE, "Reasoning pass starting (stages="
                + (run.spec.enabledStages().isEmpty() ? "ALL" : run.spec.enabledStages())
                + (run.spec.dryRun() ? ", DRY-RUN" : "") + ")");

        HydrationConfig config = new HydrationConfig(run.spec.enabledStages(),
                run.spec.confidencePruneThreshold(), run.spec.dryRun());
        HydrationResult result = hydrationOrchestrator.run(run.factSheetId, config,
                (stage, msg) -> {
                    transcript(run, "[" + stage + "] " + msg);
                    publish(run, CrawlProgressEvent.EventType.PROGRESS, "[" + stage + "] " + msg);
                });
        run.reasonedAfterLastTick = true;

        Map<String, Object> hydration = new LinkedHashMap<>();
        hydration.put("relationsDerived", result.relationsDerived());
        hydration.put("retractedAtoms", result.retractedAtomCount());
        hydration.put("factsMaterialized", result.factsMaterialized());
        hydration.put("factsRetractedPruned", result.factsRetractedPruned());
        hydration.put("factsConfidencePruned", result.factsConfidencePruned());
        hydration.put("mergesPerformed", result.mergesPerformed());
        hydration.put("orphansRemoved", result.orphansRemoved());
        hydration.put("gnnEdgesScored", result.gnnEdgesScored());
        hydration.put("stagesRun", result.stagesRun());
        hydration.put("runId", result.runId());

        LearningMetrics lm = result.learningMetrics();
        Map<String, Object> learning = new LinkedHashMap<>();
        learning.put("factVersionsWritten", lm.factVersionsWritten());
        learning.put("retractedAtomCount", lm.retractedAtomCount());
        learning.put("promotedAtomCount", lm.promotedAtomCount());
        learning.put("totalCorroboration", lm.totalCorroboration());
        learning.put("bandCounts", lm.bandCounts());
        learning.put("derivationSkipped", lm.derivationSkipped());

        collectInferredFacts(run, result.runId());

        run.status = RunStatus.SCORING;
        Map<String, Object> scoreMap = score(run);

        synchronized (run) {
            run.timeline.add(new TickReport(run.nextTick - 1, 0, 0, true,
                    hydration, learning, scoreMap, System.currentTimeMillis()));
        }
        publish(run, CrawlProgressEvent.EventType.PROGRESS, "Reasoning pass done: derived="
                + result.relationsDerived() + " materialized=" + result.factsMaterialized()
                + (run.lastScore != null ? String.format(" macroF1=%.2f", run.lastScore.macroF1()) : ""));
    }

    /** Read INFERRED edges / merges / communities back and score them against the manifest. */
    private Map<String, Object> score(SimRun run) {
        RecoveredState recovered = collectRecovered(run);
        ScoreReport report = PatternRecoveryScorer.score(run.dataset.groundTruth(), recovered);
        Map<String, Object> map = new LinkedHashMap<>();
        List<Map<String, Object>> families = new ArrayList<>();
        report.families().forEach(f -> {
            Map<String, Object> fm = new LinkedHashMap<>();
            fm.put("family", f.family());
            fm.put("truthCount", f.truthCount());
            fm.put("recoveredCount", f.recoveredCount());
            fm.put("truePositives", f.truePositives());
            fm.put("precision", f.precision());
            fm.put("recall", f.recall());
            fm.put("f1", f.f1());
            families.add(fm);
        });
        map.put("families", families);
        map.put("macroF1", report.macroF1());
        map.put("ece", report.ece());
        map.put("forbiddenViolations", report.forbiddenViolations());
        map.put("hallucinatedCount", report.hallucinatedEdges().size());
        map.put("unanticipatedCount", report.unanticipatedEdges().size());
        List<Map<String, Object>> calibration = new ArrayList<>();
        report.calibration().forEach(b -> calibration.add(Map.of(
                "lo", b.lo(), "hi", b.hi(), "count", b.count(),
                "meanConfidence", b.meanConfidence(), "accuracy", b.accuracy())));
        map.put("calibration", calibration);

        synchronized (run) {
            run.lastScore = report;
            run.lastScoreMap = map;
            run.lastHallucinated = report.hallucinatedEdges();
            run.recoveredEdgeKeys = recovered.inferredEdges().stream()
                    .map(e -> GroundTruthManifest.edgeKey(e.sourceKey(), e.relationType(), e.targetKey()))
                    .collect(HashSet::new, HashSet::add, HashSet::addAll);
        }
        journal(run, "last-score.json", map);
        return map;
    }

    private RecoveredState collectRecovered(SimRun run) {
        if (knowledgeGraphService == null) {
            return RecoveredState.empty();
        }
        List<RecoveredEdge> inferred = new ArrayList<>();
        List<GraphEdge> allEdges = knowledgeGraphService.getEdgesInFactSheet(run.factSheetId);
        List<Set<String>> resolvedGroups = new ArrayList<>();
        Map<String, String> unionParent = new HashMap<>();
        for (GraphEdge e : allEdges) {
            if (Boolean.TRUE.equals(e.getStale())) continue;
            String srcKey = run.nodeIdToKey.get(e.getSourceNodeId());
            String dstKey = run.nodeIdToKey.get(e.getTargetNodeId());
            if (srcKey == null || dstKey == null) continue;   // not a scenario entity
            if (e.getProvenanceType() == EdgeProvenance.INFERRED) {
                String rel = firstNonBlank(e.getRelationType(), e.getLabel(), "RELATED_TO");
                double conf = e.getConfidence() != null ? e.getConfidence()
                        : (e.getWeight() != null ? e.getWeight() : 0.5);
                inferred.add(new RecoveredEdge(srcKey, rel, dstKey, clamp01(conf)));
            }
            boolean resolves = e.getEdgeType() == EdgeType.RESOLVES_TO
                    || "RESOLVES_TO".equalsIgnoreCase(firstNonBlank(e.getRelationType(), e.getLabel(), ""));
            if (resolves) {
                union(unionParent, srcKey, dstKey);
            }
        }
        Map<String, Set<String>> groups = new HashMap<>();
        for (String key : unionParent.keySet()) {
            groups.computeIfAbsent(find(unionParent, key), k -> new HashSet<>()).add(key);
        }
        groups.values().stream().filter(g -> g.size() > 1).forEach(resolvedGroups::add);

        Map<String, String> communityByNode = new HashMap<>();
        for (GraphNode node : knowledgeGraphService.getNodesInFactSheet(run.factSheetId)) {
            if (node == null || Boolean.TRUE.equals(node.getStale())) continue;
            String key = run.nodeIdToKey.get(node.getNodeId());
            if (key == null || node.getMetadata() == null) continue;
            Object community = node.getMetadata().get("community.id");
            if (community != null) {
                communityByNode.put(key, String.valueOf(community));
            }
        }
        return new RecoveredState(inferred, resolvedGroups, communityByNode);
    }

    /** Pull this reasoning run's facts from the per-sheet inferred-fact store for the inspector. */
    private void collectInferredFacts(SimRun run, String reasoningRunId) {
        if (groundingService == null || reasoningRunId == null) {
            return;
        }
        try {
            Collection<InferredFact> facts = groundingService.getState(run.factSheetId)
                    .inferredFactStore()
                    .byRun(reasoningRunId);
            List<Map<String, Object>> view = facts.stream()
                    .sorted(Comparator.comparingDouble(InferredFact::value).reversed())
                    .limit(MAX_INSPECTOR_FACTS)
                    .map(f -> {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("atomKey", f.atomKey());
                        m.put("value", f.value());
                        m.put("confidence", f.confidence());
                        m.put("rules", f.supportingRuleIds());
                        m.put("supports", f.supportingFactKeys());
                        return m;
                    })
                    .toList();
            synchronized (run) {
                run.inferredFacts = view;
            }
        } catch (RuntimeException ex) {
            transcript(run, "Inferred-fact inspection failed: " + ex.getMessage());
        }
    }

    // ── internals ──────────────────────────────────────────────────────────────────────

    private TruthEdgeView truthView(SimRun run, String family, ExpectedEdge e) {
        String status = run.lastScore == null ? "pending" : (matched(run, e) ? "recovered" : "missed");
        return new TruthEdgeView(family, e.sourceKey(), e.targetKey(), e.relationType(), e.why(),
                run.keyToNodeId.get(e.sourceKey()), run.keyToNodeId.get(e.targetKey()), status);
    }

    private boolean matched(SimRun run, ExpectedEdge e) {
        if (run.recoveredEdgeKeys.contains(
                GroundTruthManifest.edgeKey(e.sourceKey(), e.relationType(), e.targetKey()))) {
            return true;
        }
        return e.symmetric() && run.recoveredEdgeKeys.contains(
                GroundTruthManifest.edgeKey(e.targetKey(), e.relationType(), e.sourceKey()));
    }

    private List<List<Map<String, String>>> groupViews(SimRun run, List<Set<String>> groups) {
        List<List<Map<String, String>>> out = new ArrayList<>();
        for (Set<String> group : groups) {
            List<Map<String, String>> members = new ArrayList<>();
            for (String key : group) {
                Map<String, String> m = new LinkedHashMap<>();
                m.put("key", key);
                String nodeId = run.keyToNodeId.get(key);
                if (nodeId != null) {
                    m.put("nodeId", nodeId);
                }
                members.add(m);
            }
            out.add(members);
        }
        return out;
    }

    private RunSnapshot snapshot(SimRun run) {
        synchronized (run) {
            GroundTruthManifest truth = run.dataset.groundTruth();
            Map<String, Object> truthSummary = new LinkedHashMap<>();
            truthSummary.put("expectedInferredEdges", truth.expectedInferredEdges().size());
            truthSummary.put("duplicateSets", truth.duplicateSets().size());
            truthSummary.put("communities", truth.communities().size());
            truthSummary.put("expectedCausalLinks", truth.expectedCausalLinks().size());
            truthSummary.put("forbiddenCausalLinks", truth.forbiddenCausalLinks().size());
            truthSummary.put("corruptedEdges", truth.corruptedEdgeKeys().size());
            truthSummary.put("generatingRules",
                    truth.generatingRules().stream().map(GroundTruthManifest.GeneratingRule::rule).toList());
            return new RunSnapshot(run.runId, run.factSheetId, run.spec.scenarioId(), run.scenarioName,
                    run.spec.seed(), run.status.name(), run.spec.mode().name(), run.paused,
                    run.nextTick, run.dataset.ticks().size(), run.nodesCreated, run.edgesCreated,
                    run.spec.reasonEveryK(), run.spec.enabledStages(), run.spec.dryRun(),
                    truthSummary, List.copyOf(run.timeline), run.lastScoreMap,
                    run.inferredFacts, List.copyOf(run.transcript), run.error, run.createdAtMs);
        }
    }

    private SimRun required(String runId) {
        SimRun run = runs.get(runId);
        if (run == null) {
            throw new IllegalArgumentException("Unknown simulation run: " + runId);
        }
        return run;
    }

    private void safely(SimRun run, Runnable work) {
        try {
            work.run();
        } catch (Exception ex) {
            log.error("[GraphSim {}] run failed: {}", run.runId, ex.getMessage(), ex);
            run.error = ex.getMessage();
            run.status = RunStatus.ERROR;
            publish(run, CrawlProgressEvent.EventType.ERROR, "Run failed: " + ex.getMessage());
        }
    }

    private void publish(SimRun run, CrawlProgressEvent.EventType type, String message) {
        transcript(run, message);
        try {
            eventPublisher.publishEvent(new CrawlProgressEvent(this, run.runId, null, type, message));
        } catch (RuntimeException ex) {
            log.debug("[GraphSim {}] event publish failed: {}", run.runId, ex.getMessage());
        }
    }

    private void transcript(SimRun run, String line) {
        synchronized (run) {
            run.transcript.add(Instant.now().toString() + " " + line);
            while (run.transcript.size() > MAX_TRANSCRIPT_LINES) {
                run.transcript.remove(0);
            }
        }
    }

    private KbConfig kbCfg() {
        return kbConfigManager != null ? kbConfigManager.current() : KbConfig.defaults();
    }

    private String toJson(Map<String, Object> value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            return "{\"source\":\"graph-simulator\"}";
        }
    }

    private void journal(SimRun run, String file, Object payload) {
        try {
            Path dir = Path.of(System.getProperty("user.home"), ".kompile", "simulations", run.runId);
            Files.createDirectories(dir);
            Files.writeString(dir.resolve(file), objectMapper.writerWithDefaultPrettyPrinter()
                    .writeValueAsString(payload), StandardCharsets.UTF_8);
        } catch (Exception ex) {
            log.debug("[GraphSim {}] journal write failed ({}): {}", run.runId, file, ex.getMessage());
        }
    }

    private static String firstNonBlank(String a, String b, String fallback) {
        if (a != null && !a.isBlank()) return a;
        if (b != null && !b.isBlank()) return b;
        return fallback;
    }

    private static double clamp01(double v) {
        return Math.max(0.0, Math.min(1.0, v));
    }

    private static void union(Map<String, String> parent, String a, String b) {
        parent.putIfAbsent(a, a);
        parent.putIfAbsent(b, b);
        String ra = find(parent, a);
        String rb = find(parent, b);
        if (!ra.equals(rb)) {
            parent.put(ra, rb);
        }
    }

    private static String find(Map<String, String> parent, String k) {
        String p = parent.get(k);
        while (!Objects.equals(p, k)) {
            k = p;
            p = parent.get(k);
        }
        return k;
    }

    /** Mutable per-run state; all field mutation happens on the run's single worker thread. */
    private static final class SimRun {
        final String runId;
        final long factSheetId;
        final String scenarioName;
        final StartSpec spec;
        final ScenarioRun dataset;
        final long createdAtMs = System.currentTimeMillis();
        final ExecutorService executor;
        final Map<String, String> keyToNodeId = new ConcurrentHashMap<>();
        final Map<String, String> nodeIdToKey = new ConcurrentHashMap<>();
        final List<TickReport> timeline = new ArrayList<>();
        final List<String> transcript = new ArrayList<>();

        volatile RunStatus status = RunStatus.CONFIGURED;
        volatile boolean paused;
        volatile boolean reasonedAfterLastTick;
        volatile int nextTick;
        volatile int nodesCreated;
        volatile int edgesCreated;
        volatile String error;
        volatile ScoreReport lastScore;
        volatile Map<String, Object> lastScoreMap;
        volatile List<RecoveredEdge> lastHallucinated = List.of();
        volatile Set<String> recoveredEdgeKeys = Set.of();
        volatile List<Map<String, Object>> inferredFacts = List.of();

        SimRun(String runId, long factSheetId, String scenarioName, StartSpec spec, ScenarioRun dataset) {
            this.runId = runId;
            this.factSheetId = factSheetId;
            this.scenarioName = scenarioName;
            this.spec = spec;
            this.dataset = dataset;
            this.executor = Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "graph-sim-" + runId);
                t.setDaemon(true);
                return t;
            });
        }
    }
}
