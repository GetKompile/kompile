/*
 *   Copyright 2025 Kompile Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ai.kompile.process.discovery.mining;

import ai.kompile.graph.reasoning.confidence.Opinion;
import ai.kompile.graph.reasoning.explain.ReasoningTrace;
import ai.kompile.graph.reasoning.fol.Fact;
import ai.kompile.graph.reasoning.fol.InferredFact;
import ai.kompile.graph.reasoning.fol.grounding.PlattCalibrator;
import ai.kompile.graph.reasoning.tms.BeliefRevisionResult;
import ai.kompile.graph.reasoning.tms.ProbabilisticContradictionDetector;
import ai.kompile.knowledgegraph.audit.FactAuditEvent;
import ai.kompile.knowledgegraph.audit.FileBackedAuditLog;
import ai.kompile.knowledgegraph.domain.EdgeProvenance;
import ai.kompile.knowledgegraph.domain.EdgeType;
import ai.kompile.knowledgegraph.domain.GraphEdge;
import ai.kompile.knowledgegraph.domain.GraphNode;
import ai.kompile.knowledgegraph.domain.GraphProvenanceKeys;
import ai.kompile.knowledgegraph.grounding.KbGroundingService;
import ai.kompile.knowledgegraph.service.KnowledgeGraphService;
import ai.kompile.knowledgegraph.staging.ModelTrainedEvent;
import ai.kompile.process.discovery.ProcessSuggestion;
import ai.kompile.process.discovery.ProcessSuggestionStore;
import ai.kompile.process.discovery.ProcessUnifiedGraphArtifacts;
import ai.kompile.process.discovery.mining.causal.CausalDependency;
import ai.kompile.process.discovery.mining.causal.ProcessBayesianInference;
import ai.kompile.process.discovery.mining.causal.ProcessCausalAnalyzer;
import ai.kompile.process.discovery.mining.causal.ProcessPslInference;
import ai.kompile.process.discovery.mining.conformance.ConformanceChecker;
import ai.kompile.process.discovery.mining.conformance.ConformanceResult;
import ai.kompile.process.discovery.mining.convert.ProcessTreeToSuggestion;
import ai.kompile.process.discovery.mining.declare.DeclareConstraint;
import ai.kompile.process.discovery.mining.declare.DeclareMiner;
import ai.kompile.process.discovery.mining.dfg.DfgBuilder;
import ai.kompile.process.discovery.mining.entail.PrecedenceMaterializer;
import ai.kompile.process.discovery.mining.entail.ProcessAtoms;
import ai.kompile.process.discovery.mining.entail.ProcessHybridActivation;
import ai.kompile.process.discovery.mining.entail.ProcessEntailment;
import ai.kompile.process.discovery.mining.entail.ProcessEntailmentResult;
import ai.kompile.process.discovery.mining.log.Event;
import ai.kompile.process.discovery.mining.log.Trace;
import ai.kompile.process.discovery.mining.export.ProcessBpmnExporter;
import ai.kompile.process.discovery.mining.export.ProcessMermaidExporter;
import ai.kompile.process.discovery.mining.extract.ActivityClassifier;
import ai.kompile.process.discovery.mining.extract.ActorResourceObservations;
import ai.kompile.process.discovery.mining.extract.AnchorTypeCorrelation;
import ai.kompile.process.discovery.mining.extract.ConnectedComponentCorrelation;
import ai.kompile.process.discovery.mining.extract.EventLogExtractor;
import ai.kompile.process.discovery.mining.extract.RelationActivityClassifier;
import ai.kompile.process.discovery.mining.extract.RoleBindingExtractor;
import ai.kompile.process.discovery.mining.extract.TraceClusterer;
import ai.kompile.process.discovery.mining.log.EventLog;
import ai.kompile.process.discovery.mining.miner.HeuristicsMiner;
import ai.kompile.process.discovery.mining.miner.HeuristicsNet;
import ai.kompile.process.discovery.mining.miner.InductiveMiner;
import ai.kompile.process.discovery.mining.perf.PerformanceAnalysis;
import ai.kompile.process.discovery.mining.perf.PerformanceMiner;
import ai.kompile.process.discovery.mining.dfg.DirectlyFollowsGraph;
import ai.kompile.process.discovery.mining.rules.MinedRulePersistenceService;
import ai.kompile.process.discovery.mining.convert.ProcessNarrator;
import ai.kompile.process.discovery.mining.trace.ProcessReasoningTraceBuilder;
import ai.kompile.process.discovery.mining.trace.ProcessReasoningTraceEvent;
import ai.kompile.process.discovery.mining.trace.ProcessReasoningTraceStore;
import ai.kompile.process.discovery.mining.tree.ProcessTree;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The principled, LLM-free alternative to the bespoke {@code analyzeXxxFlows} matchers: it derives a
 * business process from a fact sheet's knowledge graph using the standard process-mining pipeline
 * (graph → event log → directly-follows graph → Inductive Miner → process tree) and emits the result
 * as a {@link ProcessSuggestion}, which flows through the existing accept/UI machinery unchanged.
 *
 * <p>Registered as a normal bean alongside the legacy discovery service; it does not subscribe to
 * graph-build events, so it never runs unless explicitly invoked (via {@code MiningDiscoveryController}
 * or another caller). This keeps it strictly additive while the two engines are compared.
 */
@Service
@ConditionalOnBean(KnowledgeGraphService.class)
public class MiningProcessDiscoveryService {

    private static final Logger log = LoggerFactory.getLogger(MiningProcessDiscoveryService.class);

    private KnowledgeGraphService graph;
    private ProcessSuggestionStore suggestionStore;
    private EventLogExtractor extractor = new EventLogExtractor();

    /** Optional KB grounding service — wired when kompile-knowledge-graph is present. */
    private KbGroundingService kbGroundingService;

    /** Optional rule persistence service — wired when available (Spring context only). */
    private MinedRulePersistenceService rulePersistenceService;

    /** Optional trace store for walkable process derivations. */
    private ProcessReasoningTraceStore reasoningTraceStore;

    /** Optional event publisher — wired when the Spring context provides one; used to notify staging. */
    private ApplicationEventPublisher eventPublisher;

    /** Optional project data directory: used to emit PROCESS_CREATED audit events. */
    @org.springframework.beans.factory.annotation.Value("${kompile.data.dir:#{null}}")
    private String dataDir;

    /**
     * Kompile-managed tunables (thresholds, clustering, recency decay, weight learning, semantic
     * blend, actor-share gate, anchor type) — JSON-backed, hot-reloaded, UI-edited. There are NO
     * {@code @Value} tunables in this service; plain-Java tests without Spring fall back to
     * {@link ProcessMiningConfig#defaults()}.
     */
    private ProcessMiningConfigManager configManager;

    @Autowired(required = false)
    public void setConfigManager(ProcessMiningConfigManager configManager) {
        this.configManager = configManager;
    }

    /** Effective config: the managed file when a manager is wired, factory defaults otherwise. */
    private ProcessMiningConfig cfg() {
        return configManager != null ? configManager.current() : ProcessMiningConfig.defaults();
    }

    /** Optional activity-label embedder — provided by the application layer (app-main wraps EmbeddingModel). */
    private ActivityEmbedder activityEmbedder;

    private static final int MAX_EMBEDDING_CONTEXTS_PER_ACTIVITY = 8;
    private static final int MAX_EMBEDDING_CONTEXT_ATTRIBUTES = 16;
    private static final int MAX_EMBEDDING_CONTEXT_CHARS = 768;

    private record ActivityEmbeddingBatch(Map<String, double[]> vectors,
                                          String source,
                                          String model,
                                          int contextualizedActivities) {
        private ActivityEmbeddingBatch {
            vectors = vectors == null ? Map.of() : Map.copyOf(vectors);
        }

        private static ActivityEmbeddingBatch empty() {
            return new ActivityEmbeddingBatch(Map.of(), null, null, 0);
        }
    }

    @Autowired(required = false)
    public void setActivityEmbedder(ActivityEmbedder activityEmbedder) {
        this.activityEmbedder = activityEmbedder;
    }

    /** Batch-embed the log's activity labels via the wired embedder; empty map when unavailable. */
    private ActivityEmbeddingBatch embedActivities(EventLog eventLog, double semanticWeight) {
        if (activityEmbedder == null || semanticWeight <= 0) {
            return ActivityEmbeddingBatch.empty();
        }
        try {
            List<ActivityEmbedder.ActivityContext> contexts = activityEmbeddingContexts(eventLog);
            if (contexts.size() < 2) {
                return ActivityEmbeddingBatch.empty();
            }
            Map<String, double[]> embeddings = activityEmbedder.embedContexts(contexts);
            if (embeddings == null || embeddings.isEmpty()) {
                return ActivityEmbeddingBatch.empty();
            }
            Map<String, double[]> valid = new LinkedHashMap<>();
            for (ActivityEmbedder.ActivityContext context : contexts) {
                double[] vector = embeddings.get(context.label());
                if (vector != null && vector.length > 0) {
                    valid.put(context.label(), vector);
                }
            }
            int contextualized = (int) contexts.stream()
                    .filter(context -> !context.contexts().isEmpty())
                    .count();
            return new ActivityEmbeddingBatch(valid, activityEmbedder.embeddingSource(),
                    activityEmbedder.embeddingModel(), contextualized);
        } catch (Exception e) {
            log.debug("Process mining: activity embedding failed — semantic blend off ({})", e.getMessage());
            return ActivityEmbeddingBatch.empty();
        }
    }

    static List<ActivityEmbedder.ActivityContext> activityEmbeddingContexts(EventLog eventLog) {
        Map<String, LinkedHashSet<String>> byActivity = new LinkedHashMap<>();
        for (String activity : eventLog.activityNames()) {
            byActivity.put(activity, new LinkedHashSet<>());
        }
        for (Trace trace : eventLog.traces()) {
            for (Event event : trace.events()) {
                LinkedHashSet<String> contexts = byActivity.computeIfAbsent(
                        event.activity(), ignored -> new LinkedHashSet<>());
                if (contexts.size() >= MAX_EMBEDDING_CONTEXTS_PER_ACTIVITY) {
                    continue;
                }
                String context = eventEmbeddingContext(event);
                if (!context.isBlank()) {
                    contexts.add(context);
                }
            }
        }
        List<ActivityEmbedder.ActivityContext> out = new ArrayList<>(byActivity.size());
        for (Map.Entry<String, LinkedHashSet<String>> entry : byActivity.entrySet()) {
            out.add(new ActivityEmbedder.ActivityContext(entry.getKey(),
                    new ArrayList<>(entry.getValue())));
        }
        return out;
    }

    private static String eventEmbeddingContext(Event event) {
        List<Map.Entry<String, Object>> attributes = new ArrayList<>();
        for (Map.Entry<String, Object> entry : event.attributes().entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            if (key == null || key.isBlank() || value == null
                    || !(value instanceof String || value instanceof Number || value instanceof Boolean)) {
                continue;
            }
            String normalized = key.toLowerCase(Locale.ROOT);
            if (normalized.endsWith("id") || normalized.contains("timestamp")
                    || normalized.equals("weight") || normalized.equals("confidence")) {
                continue;
            }
            attributes.add(entry);
        }
        attributes.sort((left, right) -> left.getKey().compareTo(right.getKey()));
        StringBuilder context = new StringBuilder();
        int count = 0;
        for (Map.Entry<String, Object> entry : attributes) {
            if (count++ >= MAX_EMBEDDING_CONTEXT_ATTRIBUTES) {
                break;
            }
            if (!context.isEmpty()) {
                context.append("; ");
            }
            String value = String.valueOf(entry.getValue()).trim();
            if (value.length() > 160) {
                value = value.substring(0, 160);
            }
            context.append(entry.getKey()).append('=').append(value);
            if (context.length() >= MAX_EMBEDDING_CONTEXT_CHARS) {
                context.setLength(MAX_EMBEDDING_CONTEXT_CHARS);
                break;
            }
        }
        return context.toString();
    }

    @Autowired
    public MiningProcessDiscoveryService(KnowledgeGraphService graph) {
        this.graph = graph;
    }

    /** No-arg constructor for CGLIB proxy instantiation in GraalVM native image. */
    protected MiningProcessDiscoveryService() {
    }

    @Autowired(required = false)
    public void setSuggestionStore(ProcessSuggestionStore suggestionStore) {
        this.suggestionStore = suggestionStore;
    }

    @Autowired(required = false)
    public void setKbGroundingService(KbGroundingService kbGroundingService) {
        this.kbGroundingService = kbGroundingService;
    }

    @Autowired(required = false)
    public void setRulePersistenceService(MinedRulePersistenceService rulePersistenceService) {
        this.rulePersistenceService = rulePersistenceService;
    }

    @Autowired(required = false)
    public void setReasoningTraceStore(ProcessReasoningTraceStore reasoningTraceStore) {
        this.reasoningTraceStore = reasoningTraceStore;
    }

    @Autowired(required = false)
    public void setEventPublisher(ApplicationEventPublisher eventPublisher) {
        this.eventPublisher = eventPublisher;
    }

    /** Optional accept/dismiss-fitted calibration — wired in Spring contexts. */
    private ProcessCalibrationService calibrationService;

    @Autowired(required = false)
    public void setCalibrationService(ProcessCalibrationService calibrationService) {
        this.calibrationService = calibrationService;
    }

    /**
     * Returns an event log for the given fact sheet using the per-request anchor type when
     * supplied, else the managed config's {@code miningAnchorEntityType}, else the default
     * connected-component extractor.
     */
    private EventLog extractLog(Long factSheetId, String anchorType) {
        String anchor = (anchorType != null && !anchorType.isBlank())
                ? anchorType
                : cfg().getAnchorEntityType();
        EventLog nodeLog = extractionFor(anchor, RelationActivityClassifier.none())
                .extractForFactSheet(graph, factSheetId);
        if (!cfg().isRelationEventFallbackEnabled() || hasMineableBusinessSequence(nodeLog)) {
            return nodeLog;
        }
        EventLog relationLog = extractionFor(anchor, RelationActivityClassifier.byResolvedTypes())
                .extractForFactSheet(graph, factSheetId);
        if (hasMineableBusinessSequence(relationLog)) {
            log.info("Process mining: relation-event extraction recovered {} activity label(s) "
                    + "for fact sheet {}", relationLog.activityNames().size(), factSheetId);
            return relationLog;
        }
        return nodeLog;
    }

    private EventLogExtractor extractionFor(String anchor, RelationActivityClassifier relationClassifier) {
        if (anchor == null || anchor.isBlank()) {
            return new EventLogExtractor(ActivityClassifier.byEntityType(), new ConnectedComponentCorrelation(),
                    EventLogExtractor.DEFAULT_EXCLUDED_LEVELS, relationClassifier);
        }
        return new EventLogExtractor(ActivityClassifier.byEntityType(), new AnchorTypeCorrelation(anchor),
                EventLogExtractor.DEFAULT_EXCLUDED_LEVELS, relationClassifier);
    }

    private static boolean hasMineableBusinessSequence(EventLog log) {
        if (log == null || log.isEmpty()) {
            return false;
        }
        for (Trace trace : log.traces()) {
            long businessActivities = trace.activitySequence().stream()
                    .filter(activity -> !ActivityClassifier.isCommunicationScaffoldLabel(activity))
                    .distinct()
                    .limit(2)
                    .count();
            if (businessActivities >= 2) {
                return true;
            }
        }
        return false;
    }

    /**
     * Extract → Inductive Miner → {@link ProcessSuggestion}, persisted to the suggestion store.
     *
     * @param noiseThreshold 0 for classic Inductive Miner; 0&lt;t≤1 for the IMf infrequent-filter variant
     * @param anchorType     optional object-centric case notion: one process instance per entity of this type
     * @return the discovered suggestion, or {@code null} if the graph yielded no events
     */
    public ProcessSuggestion discoverForFactSheet(Long factSheetId, double noiseThreshold, String anchorType) {
        EventLog fullLog = extractLog(factSheetId, anchorType);
        if (fullLog.isEmpty()) {
            log.info("Process mining: no events extracted for fact sheet {} — nothing to discover", factSheetId);
            return null;
        }

        ProcessMiningConfig config = cfg();

        // Embedding-based activity alias unification (WP14b at the mining seam): labels whose
        // embedding cosine clears the threshold are the same business activity under different
        // names ("Bill" → "Invoice") and unify onto the more frequent label BEFORE clustering,
        // so the miner never sees two half-support ghosts of one activity.
        List<ActivityAliasUnifier.Merge> aliasMerges = List.of();
        try {
            Map<String, double[]> labelVectors = embedActivities(fullLog, 1.0).vectors();
            ActivityAliasUnifier.Result unified = ActivityAliasUnifier.unify(
                    fullLog, labelVectors, config.getAliasSimilarityThreshold());
            if (!unified.merges().isEmpty()) {
                fullLog = unified.log();
                aliasMerges = unified.merges();
                log.info("Process mining: unified {} aliased activity label(s) for fact sheet {}: {}",
                        aliasMerges.size(), factSheetId, aliasMerges.stream()
                                .map(ActivityAliasUnifier::describe).toList());
            }
        } catch (Exception e) {
            log.debug("Process mining: alias unification skipped for fact sheet {} — {}",
                    factSheetId, e.getMessage());
        }

        // OWL is-a resolution — by CONSUMING what the reasoning stack already materialized:
        // OwlReasoningService writes each entity's cax-sco closure onto its node metadata
        // (owlInferredTypes — a CHIANTI node carries [Chianti, RedWine, Wine]); TaxonomyRollup
        // reads the same keys retrieval's matchesEntityType honors and rolls sibling activities
        // up to their shared concept, so the process is ABOUT Wine instead of three thin
        // subtype variants. Leaf labels survive as the `category` event attribute — the existing
        // guard mining rediscovers subtype routing from it. Inert without closure metadata.
        List<GraphNode> factSheetNodes = List.of();
        List<GraphEdge> factSheetEdges = List.of();
        try {
            factSheetNodes = graph.getNodesInFactSheet(factSheetId);
            factSheetEdges = graph.getEdgesInFactSheet(factSheetId);
        } catch (Exception e) {
            log.debug("Process mining: fact-sheet fetch for taxonomy/tally failed — {}", e.getMessage());
        }
        Map<String, String> taxonomyRollups = Map.of();
        List<String> taxonomyGroups = List.of();
        try {
            TaxonomyRollup.Result rolled = TaxonomyRollup.apply(fullLog, factSheetNodes,
                    ActivityClassifier.byEntityType(), config.getTaxonomyMinSiblings());
            if (!rolled.isEmpty()) {
                fullLog = rolled.log();
                taxonomyRollups = rolled.rollups();
                taxonomyGroups = rolled.groups();
                log.info("Process mining: taxonomy roll-up for fact sheet {}: {}",
                        factSheetId, taxonomyGroups);
            }
        } catch (Exception e) {
            log.debug("Process mining: taxonomy roll-up skipped for fact sheet {} — {}",
                    factSheetId, e.getMessage());
        }

        // has-a: the OWL enrichment materializes transitive part-of closure as HIERARCHICAL
        // INFERRED edges on the SAME edge list — map instance pairs to activity-concept pairs.
        Map<String, String> hasAPairs = detectHasAPairs(factSheetNodes, factSheetEdges, taxonomyRollups);

        // One fact sheet routinely holds several unrelated workflows. Cluster traces by
        // activity-set overlap and mine each cluster into its OWN suggestion — otherwise the
        // Inductive Miner glues unrelated processes into a single incoherent XOR/flower tree.
        List<EventLog> clusterLogs = TraceClusterer.cluster(fullLog,
                config.getClusterJaccardThreshold(), config.getClusterMaxProcesses());

        // Resource perspective: the actor-incident relations that case correlation excludes are
        // tallied here into per-activity majority performers ("bob sent the approval in 4/4
        // cases") — the strongest tier of role binding and the source of performedBy(...) facts.
        // Tally keys follow the SAME alias unification AND taxonomy roll-up as the log, or
        // merged/rolled labels would miss their role bindings. The detailed tally also keeps
        // CONTESTED roles (two well-supported performers = conflicting sources) so ownership
        // disagreements surface instead of being resolved by a silent tie-break. Node→source
        // provenance rides along for attribution.
        Map<String, ActorResourceObservations.ObservedRole> observedRoles = Map.of();
        List<ActorResourceObservations.RoleConflict> roleConflicts = List.of();
        Map<String, String> sourceByNode = new LinkedHashMap<>();
        try {
            for (GraphNode node : factSheetNodes) {
                // Canonical source attribution (GraphProvenanceKeys): plain source tag, then the
                // reserved _source/_crawlRunId/_sourceDocumentId/url fallbacks — one reader, shared
                // with the provenance view, instead of a bare "source" lookup that missed them.
                String label = GraphProvenanceKeys
                        .sourceLabel(node.getMetadata());
                if (node.getNodeId() != null && label != null) {
                    sourceByNode.put(node.getNodeId(), label);
                }
            }
            ActorResourceObservations.Tally tally = ActorResourceObservations.tallyDetailed(
                    factSheetNodes,
                    factSheetEdges,
                    ActivityClassifier.byEntityType(),
                    config.getActorInvolvementMinShare());
            observedRoles = ActivityAliasUnifier.remapKeys(tally.roles(), aliasMerges,
                    (a, b) -> a.involvedInstances() >= b.involvedInstances() ? a : b);
            observedRoles = TaxonomyRollup.remapKeys(observedRoles, taxonomyRollups,
                    (a, b) -> a.involvedInstances() >= b.involvedInstances() ? a : b);
            roleConflicts = tally.conflicts();
        } catch (Exception e) {
            log.debug("Process mining: actor tally failed for fact sheet {} — {}", factSheetId, e.getMessage());
        }

        List<ProcessSuggestion> mined = new ArrayList<>();
        ProcessCausalAnalyzer.ProcessCausalModel primaryCausalModel = null;
        List<String> extraRuleTexts = new ArrayList<>();
        int clusterIndex = 1;
        for (EventLog clusterLog : clusterLogs) {
            ClusterMining result = mineCluster(factSheetId, clusterLog, noiseThreshold,
                    clusterIndex++, clusterLogs.size(), observedRoles, aliasMerges,
                    roleConflicts, sourceByNode, taxonomyRollups, taxonomyGroups, hasAPairs);
            if (result == null) {
                continue;
            }
            mined.add(result.suggestion());
            if (primaryCausalModel == null) {
                primaryCausalModel = result.causalModel();
            } else {
                extraRuleTexts.addAll(result.causalModel().pslRules());
            }
            extraRuleTexts.addAll(result.declareRuleTexts());
        }
        if (mined.isEmpty()) {
            return null;
        }

        // Rules persist once per fact sheet (single <factSheetId>-mined.psl, one staging event).
        persistMinedRules(factSheetId, primaryCausalModel, extraRuleTexts);

        // Persist the same deterministic ranking returned to clients. Learned acceptance
        // likelihood outranks raw confidence once a ranker model exists.
        mined.sort(Comparator.<ProcessSuggestion>comparingDouble(s ->
                        s.getLearnedScore() != null ? s.getLearnedScore() : s.getConfidence())
                .reversed()
                .thenComparing(s -> s.getName() == null ? "" : s.getName()));
        for (int i = 0; i < mined.size(); i++) {
            mined.get(i).setReasoningRank(i + 1);
        }

        if (suggestionStore != null) {
            resolveProcessIdentity(factSheetId, mined, aliasMerges, config);
            suggestionStore.saveAll(mined);
        }
        if (mined.size() > 1) {
            log.info("Process mining: fact sheet {} yielded {} distinct processes from {} trace clusters",
                    factSheetId, mined.size(), clusterLogs.size());
        }
        // API compatibility: callers get the strongest suggestion; the store holds them all.
        return mined.get(0);
    }

    /** Per-cluster mining outcome: the suggestion plus the rules to persist at fact-sheet level. */
    private record ClusterMining(ProcessSuggestion suggestion,
                                 ProcessCausalAnalyzer.ProcessCausalModel causalModel,
                                 List<String> declareRuleTexts) {
    }

    /**
     * Mine ONE trace cluster into a suggestion: entailment → fact promotion → grounded conversion
     * → evidence/dependencies → Bayesian → confidence fusion → KB seeding → edge materialization
     * → audit. Rule persistence and suggestion-store writes happen once per fact sheet in
     * {@link #discoverForFactSheet}.
     */
    private ClusterMining mineCluster(Long factSheetId, EventLog eventLog, double noiseThreshold,
                                      int clusterIndex, int clusterCount,
                                      Map<String, ActorResourceObservations.ObservedRole> observedRoles,
                                      List<ActivityAliasUnifier.Merge> aliasMerges,
                                      List<ActorResourceObservations.RoleConflict> roleConflicts,
                                      Map<String, String> sourceByNode,
                                      Map<String, String> taxonomyRollups,
                                      List<String> taxonomyGroups,
                                      Map<String, String> hasAPairs) {
        ProcessTree tree = new InductiveMiner(noiseThreshold).mine(eventLog);

        String suggestionId = "mined-" + UUID.randomUUID();

        // The DFG is shared by the causal, Bayesian, and entailment passes below.
        DirectlyFollowsGraph dfg = DfgBuilder.build(eventLog);

        // L4: causal analysis (dependency measure + χ² per directly-follows arc). Its PSL rules
        // persist to <dataDir>/rules/ further below.
        ProcessCausalAnalyzer.ProcessCausalModel causalModel =
                ProcessCausalAnalyzer.analyze(eventLog);

        // Entailment pass: Declare constraints + directly-follows evidence → the HL-MRF engine
        // settles the Precedes relation (transitive derivation, antisymmetry/NCE suppression),
        // cross-examined against the log's valid time. Runs BEFORE conversion so its atoms can be
        // promoted into the KB first.
        ProcessMiningConfig config = cfg();
        List<DeclareConstraint> declareConstraints = List.of();
        ProcessEntailmentResult entailment = ProcessEntailmentResult.empty();
        try {
            declareConstraints = DeclareMiner.mine(eventLog,
                    config.getDeclareMinSupport(), config.getDeclareMinConfidence());
            entailment = ProcessEntailment.entail(eventLog, dfg, declareConstraints,
                    config.getRecencyHalfLifeDays(),
                    config.getWeightLearningMinLabels(), config.getWeightLearningEpochs());
        } catch (Exception e) {
            log.warn("Process mining: entailment pass failed for fact sheet {} — {}", factSheetId, e.getMessage());
        }

        // Fact promotion: the miner's derived atoms become KB observations BEFORE conversion, so
        // (a) the per-step activity("X") verification during conversion sees SUPPORTED facts
        // instead of UNKNOWN, and (b) grounding cascades can finally unify the persisted mined
        // Occurs(...) rules — whose ground atoms are UNQUOTED after Term.parse — against real
        // observations. One batch: one contradiction scan, one cascade event.
        List<String> kbContradictions = List.of();
        if (kbGroundingService != null) {
            kbContradictions = promoteProcessFacts(factSheetId, suggestionId, eventLog, entailment,
                    observedRoles, taxonomyRollups, hasAPairs);
        }

        // Use grounded conversion when KbGroundingService is wired. The calibrator carries the
        // accept/dismiss-fitted Platt parameters when outcomes exist (identity sigmoid otherwise).
        PlattCalibrator calibrator = calibrationService != null
                ? calibrationService.loadCalibrator() : new PlattCalibrator();
        String processName = clusterCount > 1
                ? "Mined process " + clusterIndex + "/" + clusterCount + " (fact sheet " + factSheetId + ")"
                : "Mined process (fact sheet " + factSheetId + ")";
        ProcessSuggestion suggestion = ProcessTreeToSuggestion.convertGrounded(
                tree, eventLog, processName,
                kbGroundingService, calibrator, factSheetId, config.getGuardMinAccuracy());
        suggestion.setReasoningProjection("EVENT_LOG");
        suggestion.setReasoningFamily("PERSISTED_GRAPH_EVENTS");
        suggestion.setEntailmentScore(entailment.isEmpty() ? null : entailment.fusedOpinion().expectation());
        suggestion.setProcessCaseCount(eventLog.size());
        suggestion.setProcessActivityCount(eventLog.activityNames().size());
        suggestion.setDirectlyFollowsCount(dfg.arcs().size());
        suggestion.setAcceptedPrecedenceCount(entailment.accepted().size());
        suggestion.setEntailedOnlyPrecedenceCount(entailment.entailedOnly().size());

        // Resolve node IDs → human-readable titles on every step (additive; empty list = unresolved)
        if (suggestion.getPhases() != null) {
            for (ProcessSuggestion.SuggestedPhase phase : suggestion.getPhases()) {
                if (phase.getSteps() == null) continue;
                for (ProcessSuggestion.SuggestedStep step : phase.getSteps()) {
                    if (step.getGraphNodeIds() != null && !step.getGraphNodeIds().isEmpty()) {
                        List<String> titles = new ArrayList<>();
                        for (String id : step.getGraphNodeIds()) {
                            titles.add(resolveNodeTitle(id));
                        }
                        step.setGraphNodeTitles(titles);
                    }
                    if (step.getLineageRef() != null && step.getLineageRef().getBasisNodeIds() != null) {
                        List<String> titles = new ArrayList<>();
                        for (String id : step.getLineageRef().getBasisNodeIds()) {
                            titles.add(resolveNodeTitle(id));
                        }
                        step.getLineageRef().setBasisNodeTitles(titles);
                    }
                }
            }
        }

        // Apply role bindings: observed majority actor first, then KB queries, then name keywords.
        // Runs even without a KB — the observed and heuristic tiers don't need one.
        suggestion.getPhases().forEach(phase ->
            RoleBindingExtractor.applyRoleBindings(phase.getSteps(), kbGroundingService, factSheetId,
                    observedRoles));

        // Normalize to a mutable evidence list once — every pass below appends to it. (The converter
        // builds it with List.of(); appending to that immutable list was a latent 500.)
        suggestion.setStructuredEvidence(new ArrayList<>(
                suggestion.getStructuredEvidence() != null ? suggestion.getStructuredEvidence() : List.of()));

        // Aliased labels this mine unified — surfaced so a merge is a visible decision, never a
        // silent rewrite ("where did 'Bill' go?").
        if (aliasMerges != null && !aliasMerges.isEmpty()) {
            Set<String> clusterActivities = new LinkedHashSet<>(eventLog.activityNames());
            List<String> applied = aliasMerges.stream()
                    .filter(m -> clusterActivities.contains(m.canonical()))
                    .map(ActivityAliasUnifier::describe)
                    .toList();
            if (!applied.isEmpty()) {
                suggestion.getStructuredEvidence().add(ProcessSuggestion.StructuredEvidence.builder()
                        .type("ALIAS")
                        .description("Embedding-unified activity labels: " + String.join(", ", applied))
                        .score((double) applied.size())
                        .build());
            }
        }

        // Within-log change-POINT: recency decay already makes verdicts reflect the process as it
        // is NOW; this explains WHEN it stopped being what it was — detectable on the very first
        // mine of a long log, no predecessor generation needed.
        try {
            ChangePointDetector.detect(eventLog, config.getChangePointMinWindowCases())
                    .ifPresent(cp -> suggestion.getStructuredEvidence().add(
                            ProcessSuggestion.StructuredEvidence.builder()
                                    .type("DRIFT")
                                    .description(String.format(Locale.ROOT,
                                            "Change point ~%s within this log (%d cases before, %d after): %s%s",
                                            cp.splitAt().toLocalDate(), cp.beforeCases(), cp.afterCases(),
                                            String.join("; ", cp.changes()),
                                            cp.totalChanges() > cp.changes().size()
                                                    ? "; and " + (cp.totalChanges() - cp.changes().size()) + " more"
                                                    : ""))
                                    .score(Math.min(1.0, cp.totalChanges() / (double) ChangePointDetector.MAX_REPORTED_CHANGES))
                                    .build()));
        } catch (Exception e) {
            log.debug("Process mining: change-point detection failed for fact sheet {} — {}",
                    factSheetId, e.getMessage());
        }

        // Conflicting source descriptions: two live, well-supported, disagreeing accounts of the
        // process. BOTH sides surface with source attribution; the reconciliation is an explicit
        // GUESS with its basis — choosing between departmental realities is the operator's call.
        // The numbers come from the reasoning library's own machinery: the conflict SCORE from
        // ProbabilisticContradictionDetector (mutually-exclusive posterior mass), the
        // reconciliation strength from cumulative-fused per-source Opinions, and — when tied
        // evidence promoted BOTH directions into the KB — the actual resolution from
        // BeliefReviser.retract on the guessed loser.
        try {
            int shownConflicts = 0;
            for (ProcessConflictAnalyzer.OrderingConflict conflict :
                    ProcessConflictAnalyzer.orderingConflicts(eventLog, sourceByNode,
                            config.getConflictMinorityShare())) {
                if (shownConflicts++ >= 4) {
                    break;
                }
                ProbabilisticContradictionDetector.ProbabilisticContradiction
                        detected = detectOrderContradiction(conflict, entailment);
                suggestion.getStructuredEvidence().add(ProcessSuggestion.StructuredEvidence.builder()
                        .type("CONFLICT")
                        .description(ProcessConflictAnalyzer.describe(conflict)
                                + (detected != null ? String.format(Locale.ROOT,
                                        " — joint conflict %.2f, entropy %.2f (%s)",
                                        detected.jointConflict(), detected.entropy(), detected.reason())
                                        : ""))
                        .score(detected != null ? detected.jointConflict() : conflict.majorityShare())
                        .build());
                suggestion.getStructuredEvidence().add(ProcessSuggestion.StructuredEvidence.builder()
                        .type("RECONCILIATION")
                        .description(conflict.reconciliation() + " [basis: " + conflict.basis() + "]")
                        .score(conflict.reconciledOpinion() != null
                                ? conflict.reconciledOpinion().expectation() : conflict.majorityShare())
                        .build());
                applyKbReconciliation(factSheetId, suggestion, conflict);
            }
            Set<String> clusterSteps = new LinkedHashSet<>(eventLog.activityNames());
            int shownRoleConflicts = 0;
            for (ActorResourceObservations.RoleConflict conflict : roleConflicts) {
                if (!clusterSteps.contains(conflict.activity()) || shownRoleConflicts >= 4) {
                    continue;
                }
                shownRoleConflicts++;
                suggestion.getStructuredEvidence().add(ProcessSuggestion.StructuredEvidence.builder()
                        .type("CONFLICT")
                        .description(String.format(Locale.ROOT,
                                "Conflicting owner for '%s': %s (%s) vs %s (%s)",
                                conflict.activity(),
                                conflict.winnerTitle(), performerSources(conflict.winnerInstanceIds(), sourceByNode),
                                conflict.rivalTitle(), performerSources(conflict.rivalInstanceIds(), sourceByNode)))
                        .score((double) conflict.winnerInstanceIds().size()
                                / Math.max(1, conflict.winnerInstanceIds().size() + conflict.rivalInstanceIds().size()))
                        .build());
                suggestion.getStructuredEvidence().add(ProcessSuggestion.StructuredEvidence.builder()
                        .type("RECONCILIATION")
                        .description(String.format(Locale.ROOT,
                                "GUESS: bind '%s' to %s (%d vs %d performer instances); %s may be a "
                                        + "delegate, backup, or the owner in the disagreeing source — confirm",
                                conflict.activity(), conflict.winnerTitle(),
                                conflict.winnerInstanceIds().size(), conflict.rivalInstanceIds().size(),
                                conflict.rivalTitle()))
                        .score((double) conflict.winnerInstanceIds().size()
                                / Math.max(1, conflict.winnerInstanceIds().size() + conflict.rivalInstanceIds().size()))
                        .build());
            }
        } catch (Exception e) {
            log.debug("Process mining: conflict analysis failed for fact sheet {} — {}",
                    factSheetId, e.getMessage());
        }

        // The taxonomy resolution this mine consumed — abstraction is a visible decision. is-a
        // groups name the roll-ups; has-a pairs relate the CLUSTER'S OWN step concepts.
        try {
            Set<String> clusterActivities = new LinkedHashSet<>(eventLog.activityNames());
            for (String group : taxonomyGroups) {
                suggestion.getStructuredEvidence().add(ProcessSuggestion.StructuredEvidence.builder()
                        .type("TAXONOMY")
                        .description(group + "; subtype rides each step's 'category' attribute for routing")
                        .score(1.0)
                        .build());
            }
            for (Map.Entry<String, String> pair : hasAPairs.entrySet()) {
                if (!clusterActivities.contains(pair.getKey()) || !clusterActivities.contains(pair.getValue())) {
                    continue;
                }
                suggestion.getStructuredEvidence().add(ProcessSuggestion.StructuredEvidence.builder()
                        .type("TAXONOMY")
                        .description("'" + pair.getKey() + "' is part of '" + pair.getValue()
                                + "' (OWL has-a closure, materialized HIERARCHICAL edges)")
                        .score(1.0)
                        .build());
            }
        } catch (Exception e) {
            log.debug("Process mining: taxonomy evidence failed for fact sheet {} — {}",
                    factSheetId, e.getMessage());
        }

        // Surface each observed performer as RESOURCE evidence — per-instance counts, not keywords.
        Set<String> resourceActivities = new LinkedHashSet<>();
        suggestion.getPhases().forEach(phase -> phase.getSteps().forEach(step -> {
            ActorResourceObservations.ObservedRole observed = observedRoles.get(step.getName());
            if (observed != null && resourceActivities.add(step.getName())) {
                suggestion.getStructuredEvidence().add(ProcessSuggestion.StructuredEvidence.builder()
                        .type("RESOURCE")
                        .description(step.getName() + " performed by " + observed.actorTitle()
                                + (observed.roleLabel() != null && !observed.roleLabel().equals(observed.actorTitle())
                                        ? " (" + observed.roleLabel() + ")" : "")
                                + " — observed on " + observed.evidenceInstances() + " of "
                                + observed.activityInstances() + " instances"
                                + (observed.performerEvidence() ? "" : " (involvement only, no authorship relation)"))
                        .score(observed.share())
                        .build());
            }
        }));

        // Attach causal arcs as structured evidence on the suggestion (D3-B)
        if (!causalModel.dependencies().isEmpty()) {
            List<ProcessSuggestion.StructuredEvidence> causalEvidence = new ArrayList<>();
            for (CausalDependency dep : causalModel.dependencies()) {
                causalEvidence.add(ProcessSuggestion.StructuredEvidence.builder()
                        .type("CAUSAL")
                        .description(dep.from() + " → " + dep.to() +
                                " [" + dep.type().name() + "]" +
                                " dep=" + String.format("%.3f", dep.dependency()) +
                                " sig=" + dep.significant())
                        .score(dep.dependency())
                        .build());
            }
            // getStructuredEvidence() may be immutable or null depending on how the suggestion factory
            // built it (e.g. Stream.toList()), so merge into a fresh mutable list instead of addAll-ing
            // onto it — addAll on an immutable list threw UnsupportedOperationException whenever causal
            // dependencies existed, 500-ing the whole mining endpoint.
            List<ProcessSuggestion.StructuredEvidence> mergedEvidence = new ArrayList<>();
            if (suggestion.getStructuredEvidence() != null) {
                mergedEvidence.addAll(suggestion.getStructuredEvidence());
            }
            mergedEvidence.addAll(causalEvidence);
            suggestion.setStructuredEvidence(mergedEvidence);
        }

        // Entailed precedence → structured evidence (new orderings + temporal contradictions) and
        // entailment-driven step dependencies (names; the accept path maps them to step ids).
        if (!entailment.isEmpty()) {
            int shownEntailed = 0;
            for (ProcessEntailmentResult.EntailedPrecedence p : entailment.entailedOnly()) {
                if (shownEntailed++ >= 12) {
                    break;
                }
                suggestion.getStructuredEvidence().add(ProcessSuggestion.StructuredEvidence.builder()
                        .type("ENTAILED")
                        .description(p.from() + " ⊨ precedes " + p.to()
                                + String.format(Locale.ROOT, " (posterior %.2f; temporal +%d/-%d%s)",
                                        p.posterior(), p.orderedEvidence(), p.reversedEvidence(),
                                        p.overlappedEvidence() > 0
                                                ? " ~" + p.overlappedEvidence() + " overlap" : ""))
                        .score(p.posterior())
                        .build());
            }
            // Pairs whose interval overlap outweighs both directions are CONCURRENT — surfaced,
            // and assertable()/materialization already exclude them.
            int shownConcurrent = 0;
            for (ProcessEntailmentResult.EntailedPrecedence p : entailment.precedences()) {
                if (!p.concurrent() || p.posterior() < 0.5 || shownConcurrent >= 6) {
                    continue;
                }
                shownConcurrent++;
                suggestion.getStructuredEvidence().add(ProcessSuggestion.StructuredEvidence.builder()
                        .type("PARALLEL")
                        .description(String.format(Locale.ROOT,
                                "%s ∥ %s — activity intervals overlap in %d dated case(s) "
                                        + "(vs +%d/-%d ordered); precedes() withheld",
                                p.from(), p.to(), p.overlappedEvidence(),
                                p.orderedEvidence(), p.reversedEvidence()))
                        .score(p.posterior())
                        .build());
            }
            int shownRefuted = 0;
            for (ProcessEntailmentResult.EntailedPrecedence p : entailment.precedences()) {
                // A caveat is an ordering the engine BELIEVED (posterior ≥ 0.5) that time refuted.
                // Reverse-direction probes the engine already rejected are confirmations, not news.
                if (!p.temporallyRefuted() || p.posterior() < 0.5) {
                    continue;
                }
                if (shownRefuted++ >= 8) {
                    break;
                }
                suggestion.getStructuredEvidence().add(ProcessSuggestion.StructuredEvidence.builder()
                        .type("CONTRADICTION")
                        .description("precedes(" + p.from() + ", " + p.to() + ") temporally refuted "
                                + String.format(Locale.ROOT, "(+%d/-%d dated traces)",
                                        p.orderedEvidence(), p.reversedEvidence()))
                        .score(p.posterior())
                        .build());
            }
            applyEntailedDependencies(suggestion, entailment);
        }

        // Contradictions detected while promoting the miner's facts into the KB are surfaced on
        // the suggestion, never swallowed.
        int shownKbContradictions = 0;
        for (String contradiction : kbContradictions) {
            if (shownKbContradictions++ >= 8) {
                break;
            }
            suggestion.getStructuredEvidence().add(ProcessSuggestion.StructuredEvidence.builder()
                    .type("CONTRADICTION")
                    .description("KB contradiction on fact promotion: " + contradiction)
                    .build());
        }

        // Declare→PSL constraint encodings join the fact-sheet-level rule persistence in the caller.
        List<String> declareRuleTexts = new ArrayList<>();
        for (DeclareConstraint dc : declareConstraints) {
            dc.toPslRule().ifPresent(declareRuleTexts::add);
        }

        // D3-C: Inline Bayesian posteriors — call ProcessBayesianInference.infer on the event log
        // and populate bayesianPosteriors / bayesianPriors on the suggestion
        Double bayesianAvgPosterior = null;
        try {
            ProcessBayesianInference.Result bayesResult = ProcessBayesianInference.infer(dfg, List.of());
            if (!bayesResult.posteriors().isEmpty()) {
                suggestion.setBayesianPosteriors(new LinkedHashMap<>(bayesResult.posteriors()));
                suggestion.setBayesianPriors(new LinkedHashMap<>(bayesResult.priors()));
                bayesianAvgPosterior = bayesResult.posteriors().values().stream()
                        .mapToDouble(Double::doubleValue).average().orElse(0.0);
                // Also add a BAYESIAN structured evidence entry
                suggestion.getStructuredEvidence().add(
                        ProcessSuggestion.StructuredEvidence.builder()
                                .type("BAYESIAN")
                                .description(String.format(
                                        "Bayesian inference: %d activities, %d edges (noisy-OR DAG)",
                                        bayesResult.nodes(), bayesResult.edges()))
                                .score(bayesianAvgPosterior)
                                .build());
            }
        } catch (Exception e) {
            log.warn("Process mining: inline Bayesian inference failed for fact sheet {} — {}",
                    factSheetId, e.getMessage());
        }

        // Hybrid activation: the discovered structure — relations weighted by the mined metadata
        // (dependency strengths, temporal-discounted entailed posteriors) — ranked by BOTH
        // structural engines through the library's HybridReasoner. The per-activity consensus is
        // what the fusion below consumes as the structural modality (it subsumes the separate
        // Bayesian average; noisy-OR posteriors stay on the suggestion for the UI panel).
        // With an ActivityEmbedder wired, each engine additionally blends cosine similarity to the
        // process's semantic centroid (activity labels embedded once per cluster).
        ProcessHybridActivation.Result hybridActivation = ProcessHybridActivation.Result.empty();
        try {
            double semanticWeight = config.getHybridSemanticWeight();
            ActivityEmbeddingBatch activityEmbeddings = embedActivities(eventLog, semanticWeight);
            hybridActivation = ProcessHybridActivation.activate(eventLog, dfg, entailment,
                    activityEmbeddings.vectors(), semanticWeight);
            if (!hybridActivation.isEmpty()) {
                suggestion.setHybridScore(hybridActivation.meanHybrid());
                ProcessSuggestion.HybridReasoningDetails hybridDetails =
                        ProcessHybridActivation.toSuggestionDetails(hybridActivation);
                if (hybridDetails != null) {
                    hybridDetails.setEmbeddingSource(activityEmbeddings.source());
                    hybridDetails.setEmbeddingModel(activityEmbeddings.model());
                    hybridDetails.setContextualizedActivityCount(
                            activityEmbeddings.contextualizedActivities());
                    hybridDetails.setDirectlyEmbeddedActivityCount(
                            activityEmbeddings.vectors().size());
                }
                suggestion.setHybridReasoning(hybridDetails);
                suggestion.getStructuredEvidence().add(ProcessSuggestion.StructuredEvidence.builder()
                        .type("HYBRID")
                        .description(String.format(Locale.ROOT,
                                "HybridReasoner activation over %d activities (PSL ⊕ Bayesian consensus, "
                                        + "relations weighted by mined metadata%s%s): mean %.2f",
                                hybridActivation.byActivity().size(),
                                hybridActivation.semanticEngaged() ? String.format(Locale.ROOT,
                                        ", semantic centroid blend effective w=%.2f over %d embedded labels",
                                        hybridActivation.semanticWeight(),
                                        hybridActivation.embeddedActivityCount()) : ", structural-only",
                                activityEmbeddings.source() == null ? "" : String.format(Locale.ROOT,
                                        ", embedding source=%s%s, %d context-enriched labels",
                                        activityEmbeddings.source(),
                                        activityEmbeddings.model() == null
                                                ? "" : "/" + activityEmbeddings.model(),
                                        activityEmbeddings.contextualizedActivities()),
                                hybridActivation.meanHybrid()))
                        .score(hybridActivation.meanHybrid())
                        .build());
            }
        } catch (Exception e) {
            log.warn("Process mining: hybrid activation failed for fact sheet {} — {}",
                    factSheetId, e.getMessage());
        }

        // Confidence fusion: the grounded/conformance score already on the suggestion is one
        // modality; causal significance, the hybrid structural consensus, and the entailment
        // verdict join it via subjective-logic average fusion (the sources share one log —
        // dependent evidence — so cumulative fusion would double-count). A KB-refuted suggestion
        // (grounded hard zero) stays zero: refutation is a gate, not a vote.
        boolean kbRefuted = suggestion.getConfidence() == 0.0
                && suggestion.getGroundedSteps() != null && !suggestion.getGroundedSteps().isEmpty();
        if (!kbRefuted) {
            List<Opinion> modalities = new ArrayList<>();
            StringBuilder breakdown = new StringBuilder();
            modalities.add(Opinion.fromSoftTruth(suggestion.getConfidence(), eventLog.size()));
            breakdown.append(String.format(Locale.ROOT, "grounded %.2f", suggestion.getConfidence()));
            long significantArcs = causalModel.dependencies().stream()
                    .filter(CausalDependency::significant).count();
            long otherArcs = causalModel.dependencies().size() - significantArcs;
            if (significantArcs + otherArcs > 0) {
                Opinion causal = Opinion.fromBetaEvidence(significantArcs, otherArcs);
                modalities.add(causal);
                breakdown.append(String.format(Locale.ROOT, ", causal %.2f (%d+/%d-)",
                        causal.expectation(), significantArcs, otherArcs));
            }
            if (!hybridActivation.isEmpty()) {
                modalities.add(Opinion.fromBayesianPosterior(hybridActivation.meanHybrid(), 0.5));
                breakdown.append(String.format(Locale.ROOT, ", hybrid %.2f (psl⊕bayes)",
                        hybridActivation.meanHybrid()));
            } else if (bayesianAvgPosterior != null) {
                // Fallback when hybrid activation could not run: the noisy-OR average alone.
                modalities.add(Opinion.fromBayesianPosterior(bayesianAvgPosterior, 0.5));
                breakdown.append(String.format(Locale.ROOT, ", bayesian %.2f", bayesianAvgPosterior));
            }
            if (!entailment.isEmpty()) {
                Opinion entailed = entailment.fusedOpinion();
                modalities.add(entailed);
                breakdown.append(String.format(Locale.ROOT, ", entailment %.2f", entailed.expectation()));
            }
            if (modalities.size() > 1) {
                Opinion fusedOpinion = Opinion.averageFuse(modalities.toArray(new Opinion[0]));
                double fused = fusedOpinion.expectation();
                breakdown.append(String.format(Locale.ROOT, " -> %.2f (u=%.2f)",
                        fused, fusedOpinion.uncertainty()));
                suggestion.getStructuredEvidence().add(ProcessSuggestion.StructuredEvidence.builder()
                        .type("FUSION")
                        .description("Confidence fused from " + modalities.size()
                                + " modalities: " + breakdown)
                        .score(fused)
                        .build());
                suggestion.setConfidence(fused);
            }
        }

        // D3-D / D3-E: Build process-level lineage tracing back to facts, rules, and causal arcs
        {
            List<String> ruleTexts = causalModel.pslRules();
            List<String> causalPairs = new ArrayList<>();
            for (CausalDependency dep : causalModel.dependencies()) {
                causalPairs.add(dep.from() + " -> " + dep.to() +
                        " [" + dep.type().name() + " dep=" + String.format("%.3f", dep.dependency()) + "]");
            }
            List<String> basisIds = new ArrayList<>(
                    suggestion.getSourceGraphNodeIds() != null ? suggestion.getSourceGraphNodeIds() : List.of());
            List<String> basisTitles = new ArrayList<>();
            for (String id : basisIds) {
                basisTitles.add(resolveNodeTitle(id));
            }
            ProcessSuggestion.ProcessLineage lineage = ProcessSuggestion.ProcessLineage.builder()
                    .basisNodeIds(basisIds)
                    .basisNodeTitles(basisTitles)
                    .supportingRuleTexts(new ArrayList<>(ruleTexts))
                    .causalActivityPairs(causalPairs)
                    .derivationMethod("PROCESS_MINING")
                    .build();
            suggestion.setLineageRef(lineage);
        }

        suggestion.setId(suggestionId);
        suggestion.setFactSheetId(factSheetId);
        suggestion.setDiscoveredAt(Instant.now());

        // Business-sounding identity: name from the flow's business endpoints ("Purchase Request
        // → Invoice process") and a deterministic narrative EVERY suggestion carries. The
        // app-side narration service upgrades the narrative to LLM prose when a model is
        // configured — the LLM narrates the mined structure, never replaces it.
        String businessName = ProcessNarrator.businessName(suggestion);
        suggestion.setName(clusterCount > 1
                ? businessName + " (" + clusterIndex + "/" + clusterCount + ", fact sheet " + factSheetId + ")"
                : businessName + " (fact sheet " + factSheetId + ")");
        suggestion.setNarrative(ProcessNarrator.narrate(suggestion)
                + ProcessNarrator.conflictClause(suggestion));
        suggestion.setNarrativeSource(ProcessNarrator.TEMPLATE_SOURCE);

        // Learned re-rank: once enough accept/dismiss outcomes exist, the logistic ranker scores
        // this suggestion's signal features — it RANKS alongside the fused confidence, never
        // replaces it.
        if (calibrationService != null) {
            Double learned = calibrationService.scoreSuggestion(suggestion);
            if (learned != null) {
                suggestion.setLearnedScore(learned);
                suggestion.getStructuredEvidence().add(ProcessSuggestion.StructuredEvidence.builder()
                        .type("LEARNED")
                        .description(String.format(Locale.ROOT,
                                "Acceptance likelihood %.0f%% from the re-ranker fitted to your "
                                        + "accept/dismiss history", learned * 100))
                        .score(learned)
                        .build());
            }
        }

        persistReasoningTrace(suggestion, eventLog, dfg, entailment, causalModel,
                hybridActivation, bayesianAvgPosterior, tree);

        // Entailment → KB: seed the settled precedes(...) conclusions into the inferred-fact store
        // (the channel DefaultKbVerifier reads first-class, Lucene-durable in production) so the
        // created process becomes queryable knowledge — ask_graph_verify can answer ordering
        // questions and explain() can walk the derivation, since each fact carries its supporting
        // atoms and activated rules. Deliberately NOT assertFact: that would fire one re-ground
        // cascade event per pair, which is wrong for a mined batch (and soft facts are invisible
        // to the verifier anyway).
        if (kbGroundingService != null && !entailment.isEmpty()) {
            try {
                long version = Instant.now().toEpochMilli(); // re-mining supersedes earlier runs
                List<InferredFact> inferredFacts = new ArrayList<>();
                for (ProcessEntailmentResult.EntailedPrecedence p : entailment.assertable(config.getEntailAssertThreshold())) {
                    List<String> ruleIds = new ArrayList<>(p.activatedRules());
                    ruleIds.add("process-mining:" + suggestion.getId());
                    inferredFacts.add(InferredFact.of(
                            p.kbAtomKey(), p.posterior(),
                            p.supportingFactKeys(), ruleIds,
                            entailment.runId(), version));
                }
                if (!inferredFacts.isEmpty()) {
                    kbGroundingService.seedInferredFacts(factSheetId, inferredFacts);
                    log.info("Process mining: seeded {} precedes(...) facts into the KB for fact sheet {}",
                            inferredFacts.size(), factSheetId);
                }
            } catch (Exception e) {
                log.warn("Process mining: KB seeding failed for fact sheet {} — {}",
                        factSheetId, e.getMessage());
            }
        }

        // Materialize the control flow onto the graph (observed DIRECTLY_FOLLOWS at instance level,
        // entailed PRECEDES at activity level, observed PERFORMED_BY performers at activity level)
        // so attribution/retrieval/visualization can traverse it.
        try {
            PrecedenceMaterializer.Result materialized = PrecedenceMaterializer.materialize(
                    graph, factSheetId, suggestion.getId(), eventLog, dfg, entailment,
                    config.getEntailMaterializeThreshold(), observedRoles);
            if (materialized.created() > 0) {
                log.info("Process mining: materialized {} edges (of {} DIRECTLY_FOLLOWS "
                                + "+ {} PRECEDES + {} PERFORMED_BY specs) for fact sheet {}",
                        materialized.created(), materialized.directlyFollowsSpecs(),
                        materialized.precedesSpecs(), materialized.performedBySpecs(), factSheetId);
            }
        } catch (Exception e) {
            log.warn("Process mining: control-flow materialization failed for fact sheet {} — {}",
                    factSheetId, e.getMessage());
        }

        // Emit PROCESS_CREATED audit event — so process creation is a tracked, traceable decision
        if (rulePersistenceService != null && dataDir != null && !dataDir.isBlank()) {
            try {
                FileBackedAuditLog auditLog =
                        new FileBackedAuditLog(
                                Path.of(dataDir), factSheetId);
                FactAuditEvent event =
                        new FactAuditEvent(
                                UUID.randomUUID().toString(),
                                "PROCESS_CREATED",
                                "process:factSheet:" + factSheetId,
                                Instant.now(),
                                "PROCESS_MINER",
                                suggestion.getId(),
                                Double.NaN,
                                suggestion.getConfidence(),
                                Double.NaN,
                                Double.NaN,
                                null,
                                null,
                                suggestion.getId(),
                                "causal-mining:factSheet:" + factSheetId,
                                false,
                                suggestion.getId(),
                                Double.NaN,
                                Double.NaN,
                                "process_mining from fact sheet " + factSheetId
                        );
                auditLog.append(event);
            } catch (Exception e) {
                log.warn("Process mining: could not emit PROCESS_CREATED audit for fact sheet {} — {}",
                        factSheetId, e.getMessage());
            }
        }
        log.info("Process mining discovered a {}-phase process for fact sheet {} "
                        + "(cluster {}/{}, grounding={}, causalRules={}): {}",
                suggestion.getPhases().size(), factSheetId, clusterIndex, clusterCount,
                kbGroundingService != null, causalModel.pslRules().size(), tree);
        return new ClusterMining(suggestion, causalModel, declareRuleTexts);
    }

    private void persistReasoningTrace(ProcessSuggestion suggestion,
                                       EventLog eventLog,
                                       DirectlyFollowsGraph dfg,
                                       ProcessEntailmentResult entailment,
                                       ProcessCausalAnalyzer.ProcessCausalModel causalModel,
                                       ProcessHybridActivation.Result hybridActivation,
                                       Double bayesianAvgPosterior,
                                       ProcessTree tree) {
        try {
            ReasoningTrace trace = ProcessReasoningTraceBuilder.build(
                    suggestion, eventLog, dfg, entailment, causalModel,
                    hybridActivation, bayesianAvgPosterior, tree);
            suggestion.setReasoningTraceId(ProcessReasoningTraceStore.traceId(suggestion.getId()));
            suggestion.setReasoningTraceArtifactName(
                    ProcessUnifiedGraphArtifacts.traceArtifactName(suggestion.getId()));
            if (reasoningTraceStore != null) {
                reasoningTraceStore.save(suggestion.getId(), trace);
            }
            if (eventPublisher != null) {
                eventPublisher.publishEvent(new ProcessReasoningTraceEvent(
                        this, suggestion.getId(), suggestion.getFactSheetId(), trace));
            }
        } catch (Exception e) {
            log.warn("Process mining: reasoning trace build failed for suggestion {} — {}",
                    suggestion.getId(), e.getMessage());
        }
    }

    /**
     * Persist the mined rules once per fact sheet: the primary (largest) cluster's causal model —
     * whose arcs feed the lineage sidecar — plus every other cluster's causal rules and all
     * Declare→PSL encodings as extras. Publishes {@code ModelTrainedEvent(psl-mined)}, which also
     * triggers the app-side process-ontology contribution listener.
     */
    private void persistMinedRules(Long factSheetId,
                                   ProcessCausalAnalyzer.ProcessCausalModel primaryModel,
                                   List<String> extraRuleTexts) {
        if (rulePersistenceService == null || primaryModel == null) {
            return;
        }
        if (primaryModel.pslRules().isEmpty() && extraRuleTexts.isEmpty()) {
            return;
        }
        try {
            MinedRulePersistenceService.PersistResult result =
                    rulePersistenceService.persistCausalRules(factSheetId, primaryModel, extraRuleTexts);
            if (result.persisted()) {
                log.info("Process mining: persisted {} rules for fact sheet {} → {} (v{})",
                        result.ruleCount(), factSheetId, result.fileName(), result.version());
                // Notify model-staging registry so mined PSL rules reach staging like cascade weights do.
                if (eventPublisher != null && dataDir != null && !dataDir.isBlank()) {
                    try {
                        Path ruleFile =
                                Path.of(dataDir, "rules", result.fileName());
                        if (Files.exists(ruleFile)
                                && Files.size(ruleFile) > 0) {
                            eventPublisher.publishEvent(
                                    new ModelTrainedEvent(this, "psl", factSheetId, ruleFile, "psl-mined"));
                            log.debug("Process mining: published ModelTrainedEvent(psl-mined) for fact sheet {}",
                                    factSheetId);
                        } else {
                            log.warn("Process mining: rule file {} missing/empty — ModelTrainedEvent not published",
                                    ruleFile);
                        }
                    } catch (Exception pubEx) {
                        log.warn("Process mining: could not publish ModelTrainedEvent for fact sheet {} — {}",
                                factSheetId, pubEx.getMessage());
                    }
                }
            }
        } catch (Exception e) {
            // Rule persistence failure is non-fatal: suggestions are still returned/stored
            log.warn("Process mining: rule persistence failed for fact sheet {} — {}",
                    factSheetId, e.getMessage());
        }
    }

    /**
     * Promote the miner's derived atoms into the KB fact store — the observations grounding
     * cascades reason over (one batch: one contradiction scan, one cascade event):
     * <ul>
     *   <li>hard {@code activity("X")} existence facts — verify-facing, so per-step grounding in
     *       {@code ProcessTreeToSuggestion} returns SUPPORTED instead of UNKNOWN;</li>
     *   <li>soft {@code Occurs(X)} occurrence facts at trace support — cascade-facing and
     *       deliberately UNQUOTED, matching the mined rules' ground atoms after {@code Term.parse}
     *       strips their quoted constants (the quoted/unquoted mismatch had left every persisted
     *       mined rule grounding against zero atoms);</li>
     *   <li>soft {@code precedes("A", "B")} facts with fused opinions for the assertable entailed
     *       pairs — future cascade observations, same keys the inferred store carries.</li>
     * </ul>
     *
     * @return contradiction descriptions from the batch assert (empty when clean or on failure)
     */
    private List<String> promoteProcessFacts(Long factSheetId, String suggestionId,
                                             EventLog eventLog, ProcessEntailmentResult entailment,
                                             Map<String, ActorResourceObservations.ObservedRole> observedRoles,
                                             Map<String, String> taxonomyRollups,
                                             Map<String, String> hasAPairs) {
        try {
            String sourceId = "process-mining:" + suggestionId;
            Instant now = Instant.now();
            int totalTraces = Math.max(1, eventLog.size());

            Map<String, Long> tracesContaining = new LinkedHashMap<>();
            for (Trace trace : eventLog.traces()) {
                Set<String> seen = new LinkedHashSet<>();
                for (Event event : trace.events()) {
                    seen.add(event.activity());
                }
                for (String activity : seen) {
                    tracesContaining.merge(activity, 1L, Long::sum);
                }
            }

            List<Fact> facts = new ArrayList<>();
            for (Map.Entry<String, Long> entry : tracesContaining.entrySet()) {
                long containing = entry.getValue();
                double support = Math.min(1.0, containing / (double) totalTraces);
                facts.add(new Fact(ProcessAtoms.activityAtom(entry.getKey()), 1.0, sourceId, now, true));
                facts.add(new Fact(ProcessAtoms.occursAtom(entry.getKey()), support, sourceId, now, false,
                        Opinion.fromBetaEvidence(containing, Math.max(0, totalTraces - containing))));
            }
            for (ProcessEntailmentResult.EntailedPrecedence p : entailment.assertable(cfg().getEntailAssertThreshold())) {
                Opinion factOpinion = Opinion.fromBayesianPosterior(p.posterior(), 0.5);
                if (p.temporalOpinion() != null) {
                    factOpinion = factOpinion.averageFuse(p.temporalOpinion());
                }
                facts.add(Fact.withOpinion(p.kbAtomKey(), sourceId, factOpinion));
            }

            // Taxonomy facts, hard (they mirror the ontology's own is-a/has-a structure): the
            // activity-level counterparts of the projector's entity-level isa atoms, so the KB can
            // answer "which categories does this Wine process cover?". Asserted into the fact
            // store (verify-visible) AND seeded as InferredFacts below — conjunctive query() reads
            // the inferred store, the same dual-channel lesson performedBy taught. Note the
            // OCCURS/ACTIVITY facts above are already ROLLED UP — one Occurs(Wine) whose Beta
            // opinion accumulated across every subtype's traces (corroboration transfers up the
            // hierarchy), instead of three weak per-subtype facts.
            List<String> taxonomyKeys = new ArrayList<>();
            for (Map.Entry<String, String> rollup : taxonomyRollups.entrySet()) {
                if (tracesContaining.containsKey(rollup.getValue())) {
                    taxonomyKeys.add(ProcessAtoms.isAAtom(rollup.getKey(), rollup.getValue()));
                }
            }
            for (Map.Entry<String, String> pair : hasAPairs.entrySet()) {
                if (tracesContaining.containsKey(pair.getKey()) && tracesContaining.containsKey(pair.getValue())) {
                    taxonomyKeys.add(ProcessAtoms.partOfAtom(pair.getKey(), pair.getValue()));
                }
            }
            for (String key : taxonomyKeys) {
                facts.add(new Fact(key, 1.0, sourceId, now, true));
            }

            // Resource facts from the observed-actor tally, for THIS cluster's activities only:
            // performedBy("Approval", "bob") with a Beta opinion from the per-instance counts, plus
            // hasRole(...) when a ROLE/DEPARTMENT neighbor named the role better than the actor.
            // Also seeded as InferredFacts below, which is what makes RoleBindingExtractor's KB tier
            // (and ask_graph_query) able to answer these predicates at all.
            List<InferredFact> resourceSeeds = new ArrayList<>();
            long seedVersion = now.toEpochMilli();
            for (Map.Entry<String, ActorResourceObservations.ObservedRole> entry : observedRoles.entrySet()) {
                String activity = entry.getKey();
                if (!tracesContaining.containsKey(activity)) {
                    continue;
                }
                ActorResourceObservations.ObservedRole role = entry.getValue();
                int positive = role.evidenceInstances();
                int negative = Math.max(0, role.activityInstances() - positive);
                Opinion actorOpinion = Opinion.fromBetaEvidence(positive, negative);
                String performedByKey = ProcessAtoms.performedByAtom(activity, role.actorTitle());
                facts.add(Fact.withOpinion(performedByKey, sourceId, actorOpinion));
                resourceSeeds.add(InferredFact.of(
                        performedByKey, role.share(),
                        List.of(ProcessAtoms.activityAtom(activity)),
                        List.of(sourceId + ":observed-actor"), sourceId, seedVersion));
                if (role.roleLabel() != null && !role.roleLabel().equals(role.actorTitle())) {
                    String hasRoleKey = ProcessAtoms.hasRoleAtom(activity, role.roleLabel());
                    facts.add(Fact.withOpinion(hasRoleKey, sourceId, actorOpinion));
                    resourceSeeds.add(InferredFact.of(
                            hasRoleKey, role.share(),
                            List.of(performedByKey),
                            List.of(sourceId + ":observed-actor"), sourceId, seedVersion));
                }
            }
            for (String key : taxonomyKeys) {
                resourceSeeds.add(InferredFact.of(
                        key, 1.0, List.of(),
                        List.of(sourceId + ":owl-closure"), sourceId, seedVersion));
            }

            if (facts.isEmpty()) {
                return List.of();
            }
            KbGroundingService.AssertResult result = kbGroundingService.assertFactsBatch(factSheetId, facts);
            if (!resourceSeeds.isEmpty()) {
                kbGroundingService.seedInferredFacts(factSheetId, resourceSeeds);
            }
            log.info("Process mining: promoted {} facts ({} resource) into the KB for fact sheet {} ({} contradictions)",
                    facts.size(), resourceSeeds.size(), factSheetId, result.contradictions().size());
            return result.contradictions();
        } catch (Exception e) {
            log.warn("Process mining: fact promotion failed for fact sheet {} — {}",
                    factSheetId, e.getMessage());
            return List.of();
        }
    }

    /**
     * Resolve "the same process at different times": match each fresh suggestion to a predecessor
     * head by alias-unified activity-set Jaccard ({@link ProcessIdentityResolver}); matched fresh
     * suggestions inherit the predecessor's {@code processKey}, link {@code previousSuggestionId},
     * carry a DRIFT diff (evidence + narrative clause), and — when the predecessor was ACCEPTED —
     * a {@code revisesProcessDefinitionId} so acceptance bumps the live definition's version.
     *
     * <p>Supersede is MARK, never delete: every pending mined predecessor gets
     * {@code supersededAt} (+ successor id when matched; a null successor records that the
     * process stopped being discovered), so lineage stays walkable across generations. Accepted
     * suggestions are never superseded — they anchor identity.
     */
    private void resolveProcessIdentity(Long factSheetId, List<ProcessSuggestion> mined,
                                        List<ActivityAliasUnifier.Merge> aliasMerges,
                                        ProcessMiningConfig config) {
        try {
            List<ProcessSuggestion> heads = new ArrayList<>();
            List<ProcessSuggestion> pendingHeads = new ArrayList<>();
            for (ProcessSuggestion existing : suggestionStore.listByFactSheet(factSheetId)) {
                if (!"PROCESS_MINING".equals(existing.getDiscoverySource())
                        || existing.getSupersededAt() != null) {
                    continue;
                }
                heads.add(existing);
                if (!Boolean.TRUE.equals(existing.getAccepted())) {
                    pendingHeads.add(existing);
                }
            }

            Map<String, ProcessIdentityResolver.Match> matches = ProcessIdentityResolver.resolve(
                    mined, heads, aliasMerges, config.getIdentityJaccardThreshold());

            Set<String> succeededPendingIds = new LinkedHashSet<>();
            for (ProcessSuggestion fresh : mined) {
                ProcessIdentityResolver.Match match = matches.get(fresh.getId());
                if (match == null) {
                    fresh.setProcessKey(fresh.getId()); // first sighting mints the identity
                    continue;
                }
                ProcessSuggestion predecessor = match.predecessor();
                fresh.setProcessKey(predecessor.getProcessKey() != null
                        ? predecessor.getProcessKey() : predecessor.getId());
                fresh.setPreviousSuggestionId(predecessor.getId());
                if (Boolean.TRUE.equals(predecessor.getAccepted())) {
                    fresh.setRevisesProcessDefinitionId(predecessor.getAcceptedProcessDefinitionId());
                } else {
                    succeededPendingIds.add(predecessor.getId());
                    predecessor.setSupersededAt(Instant.now());
                    predecessor.setSupersededBySuggestionId(fresh.getId());
                    suggestionStore.save(predecessor);
                }
                attachDrift(fresh, predecessor, match.jaccard());
            }

            // Pending heads no successor claimed: the process stopped being discovered. Mark,
            // never delete — the lineage (and the disappearance itself) stays visible.
            for (ProcessSuggestion vanished : pendingHeads) {
                if (!succeededPendingIds.contains(vanished.getId())
                        && vanished.getSupersededAt() == null) {
                    vanished.setSupersededAt(Instant.now());
                    suggestionStore.save(vanished);
                }
            }
        } catch (Exception e) {
            log.warn("Process mining: identity resolution failed for fact sheet {} — {}",
                    factSheetId, e.getMessage());
        }
    }

    /**
     * Run the library's {@link ai.kompile.graph.reasoning.tms.ProbabilisticContradictionDetector}
     * over the entailment's OWN posteriors for a conflicted pair: {@code Precedes(A,B)} and
     * {@code Precedes(B,A)} are declared mutually exclusive states of the pair's ORDER group, and
     * the detector scores how much posterior mass sits on both — the principled conflict measure
     * (joint conflict + entropy), not a hand-rolled ratio.
     */
    private static ProbabilisticContradictionDetector.ProbabilisticContradiction
    detectOrderContradiction(ProcessConflictAnalyzer.OrderingConflict conflict,
                             ProcessEntailmentResult entailment) {
        Double forward = null;
        Double backward = null;
        for (ProcessEntailmentResult.EntailedPrecedence p : entailment.precedences()) {
            if (p.from().equals(conflict.from()) && p.to().equals(conflict.to())) {
                forward = p.posterior();
            } else if (p.from().equals(conflict.to()) && p.to().equals(conflict.from())) {
                backward = p.posterior();
            }
        }
        if (forward == null || backward == null) {
            return null;
        }
        String pairEntity = conflict.from() + "||" + conflict.to();
        String varForward = "Precedes(" + conflict.from() + ", " + conflict.to() + ")";
        String varBackward = "Precedes(" + conflict.to() + ", " + conflict.from() + ")";
        Map<String, Double> posteriors = Map.of(varForward, forward, varBackward, backward);
        Map<String, Map<String, String>> meta = Map.of(
                varForward, Map.of("entityId", pairEntity, "exclusiveGroup", "ORDER", "state", "FROM_FIRST"),
                varBackward, Map.of("entityId", pairEntity, "exclusiveGroup", "ORDER", "state", "TO_FIRST"));
        List<ProbabilisticContradictionDetector.ProbabilisticContradiction>
                detected = ProbabilisticContradictionDetector.detect(
                        posteriors, Map.of(), meta,
                        List.of(new ProbabilisticContradictionDetector.MutualExclusion(
                                pairEntity, "ORDER", "FROM_FIRST", "TO_FIRST")),
                        ProbabilisticContradictionDetector.Policy.defaults());
        return detected.isEmpty() ? null : detected.get(0);
    }

    /**
     * The ACTUAL resolution, through the TMS: when the KB asserts the conflict's GUESSED LOSER —
     * either alongside the canonical direction (a mutually-exclusive pair the store cannot
     * honestly hold) or alone (a stale/foreign assertion the current evidence contests) — retract
     * it via {@code BeliefReviser} behind the KB's write lock and report what the retraction
     * unsupported/weakened. A consistent KB with no loser fact is never rewritten on a guess —
     * the reconciliation stays evidence-only, carrying the fused Opinion's honest residue.
     */
    private void applyKbReconciliation(Long factSheetId, ProcessSuggestion suggestion,
                                       ProcessConflictAnalyzer.OrderingConflict conflict) {
        if (kbGroundingService == null) {
            return;
        }
        try {
            String canonicalKey = conflict.canonicalFromFirst()
                    ? ProcessAtoms.precedesAtom(conflict.from(), conflict.to())
                    : ProcessAtoms.precedesAtom(conflict.to(), conflict.from());
            String loserKey = conflict.canonicalFromFirst()
                    ? ProcessAtoms.precedesAtom(conflict.to(), conflict.from())
                    : ProcessAtoms.precedesAtom(conflict.from(), conflict.to());
            var factStore = kbGroundingService.getState(factSheetId).factStore();
            if (factStore.factFor(loserKey).isEmpty()) {
                return; // KB never claimed the contested direction — the guess stays a guess
            }
            boolean bothPresent = factStore.factFor(canonicalKey).isPresent();
            KbGroundingService.RetractResult retractResult =
                    kbGroundingService.retractFact(factSheetId, loserKey);
            BeliefRevisionResult revision = retractResult.revision();
            suggestion.getStructuredEvidence().add(ProcessSuggestion.StructuredEvidence.builder()
                    .type("RECONCILIATION")
                    .description(String.format(Locale.ROOT,
                            "Applied (KB held %s): retracted %s via BeliefReviser%s — "
                                    + "%d atom(s) lost their only support, %d weakened",
                            bothPresent ? "BOTH directions"
                                    : "the contested minority direction from an earlier assertion",
                            loserKey,
                            bothPresent ? ", keeping " + canonicalKey : "",
                            revision.unsupportedAtoms().size(), revision.weakenedAtoms().size()))
                    .score(conflict.reconciledOpinion() != null
                            ? conflict.reconciledOpinion().expectation() : conflict.majorityShare())
                    .build());
            log.info("Process mining: reconciled contested KB ordering for fact sheet {} — "
                    + "retracted {} ({})", factSheetId, loserKey,
                    bothPresent ? "kept " + canonicalKey : "minority-only");
        } catch (Exception e) {
            log.debug("Process mining: KB reconciliation skipped for fact sheet {} — {}",
                    factSheetId, e.getMessage());
        }
    }

    /**
     * has-a resolution — again by consuming what OWL already materialized: the enrichment writes
     * the transitive part-of closure as {@code HIERARCHICAL} edges with {@code INFERRED}
     * provenance (OwlReasoningService.materializeInferences). Map those instance pairs onto
     * activity-concept pairs (part-activity → whole-activity), through the taxonomy roll-up so
     * "Chianti partOf Order Placed" reads "Wine is part of Order Placed".
     */
    private static Map<String, String> detectHasAPairs(List<GraphNode> nodes, List<GraphEdge> edges,
                                                       Map<String, String> taxonomyRollups) {
        Map<String, String> pairs = new LinkedHashMap<>();
        if (nodes == null || nodes.isEmpty() || edges == null || edges.isEmpty()) {
            return pairs;
        }
        Map<String, GraphNode> byId = new LinkedHashMap<>();
        for (GraphNode node : nodes) {
            if (node.getNodeId() != null) {
                byId.put(node.getNodeId(), node);
            }
        }
        ActivityClassifier classifier = ActivityClassifier.byEntityType();
        for (GraphEdge edge : edges) {
            if (edge == null || edge.getEdgeType() != EdgeType.HIERARCHICAL
                    || edge.getProvenanceType() != EdgeProvenance.INFERRED) {
                continue;
            }
            GraphNode source = byId.get(edge.getSourceNodeId());
            GraphNode target = byId.get(edge.getTargetNodeId());
            if (source == null || target == null) {
                continue;
            }
            String part = classifier.activityOf(source);
            String whole = classifier.activityOf(target);
            if (part == null || whole == null || part.equals(whole)) {
                continue;
            }
            part = taxonomyRollups.getOrDefault(part, part);
            whole = taxonomyRollups.getOrDefault(whole, whole);
            if (!part.equals(whole)) {
                pairs.putIfAbsent(part, whole);
            }
        }
        return pairs;
    }

    /** Compact source attribution for a set of performer instance nodes: "finance-crawl ×4". */
    private static String performerSources(Set<String> instanceIds,
                                           Map<String, String> sourceByNode) {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (String id : instanceIds) {
            counts.merge(sourceByNode.getOrDefault(id, "(unknown source)"), 1L, Long::sum);
        }
        return counts.entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
                .map(e -> e.getKey() + " ×" + e.getValue())
                .reduce((a, b) -> a + ", " + b)
                .orElse("no performer instances");
    }

    /** Drift between generations → DRIFT evidence entries (capped) + one narrative clause. */
    private void attachDrift(ProcessSuggestion fresh, ProcessSuggestion predecessor, double jaccard) {
        List<String> drift = ProcessDriftAnalyzer.diff(predecessor, fresh);
        String since = predecessor.getDiscoveredAt() != null
                ? predecessor.getDiscoveredAt().toString().substring(0, 10) : "the previous mine";
        if (drift.isEmpty()) {
            fresh.getStructuredEvidence().add(ProcessSuggestion.StructuredEvidence.builder()
                    .type("DRIFT")
                    .description(String.format(Locale.ROOT,
                            "Stable since %s (same process, activity overlap %.0f%%)", since, jaccard * 100))
                    .score(0.0)
                    .build());
            return;
        }
        int shown = 0;
        for (String line : drift) {
            if (shown++ >= 8) {
                break;
            }
            fresh.getStructuredEvidence().add(ProcessSuggestion.StructuredEvidence.builder()
                    .type("DRIFT")
                    .description("Since " + since + ": " + line)
                    .score(Math.min(1.0, drift.size() / 8.0))
                    .build());
        }
        if (fresh.getNarrative() != null) {
            String clause = " Changes since " + since + ": "
                    + String.join("; ", drift.subList(0, Math.min(3, drift.size())))
                    + (drift.size() > 3 ? "; and " + (drift.size() - 3) + " more" : "") + ".";
            fresh.setNarrative(fresh.getNarrative() + clause);
        }
    }

    /**
     * Mine every fact sheet that currently has a graph — the whole-project entry point behind
     * {@code POST /api/process/mining/discover-all} (the UI's Run Discovery triggers both this and
     * the legacy engine). One fact sheet failing never blocks the rest.
     *
     * @return fact sheet id → discovered suggestion id (fact sheets that yielded no events are omitted)
     */
    public Map<Long, String> discoverAll(double noiseThreshold, String anchorType) {
        Map<Long, String> discovered = new LinkedHashMap<>();
        List<Long> factSheetIds = graph.getFactSheetIdsWithGraphs();
        for (Long factSheetId : factSheetIds) {
            try {
                ProcessSuggestion suggestion = discoverForFactSheet(factSheetId, noiseThreshold, anchorType);
                if (suggestion != null) {
                    discovered.put(factSheetId, suggestion.getId());
                }
            } catch (Exception e) {
                log.warn("Process mining: discover-all failed for fact sheet {} — {}",
                        factSheetId, e.getMessage());
            }
        }
        log.info("Process mining: discover-all mined {} of {} fact sheets",
                discovered.size(), factSheetIds.size());
        return discovered;
    }

    /** The raw discovered model, without conversion or persistence. */
    public ProcessTree mineFactSheet(Long factSheetId, double noiseThreshold, String anchorType) {
        return new InductiveMiner(noiseThreshold).mine(extractLog(factSheetId, anchorType));
    }

    /** Inspect the intermediate artifacts (log, DFG, tree) without persisting — drives the UI preview. */
    public Map<String, Object> preview(Long factSheetId, double noiseThreshold, String anchorType) {
        EventLog eventLog = extractLog(factSheetId, anchorType);
        DirectlyFollowsGraph dfg = DfgBuilder.build(eventLog);
        ProcessTree tree = new InductiveMiner(noiseThreshold).mine(eventLog);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("factSheetId", factSheetId);
        out.put("cases", eventLog.size());
        out.put("activities", eventLog.activityNames());
        out.put("variants", eventLog.variants().size());
        out.put("directlyFollowsArcs", dfg.arcs().size());
        out.put("startActivities", dfg.startActivities());
        out.put("endActivities", dfg.endActivities());
        out.put("processTree", tree.toString());
        return out;
    }

    /**
     * The causal coupling (Phase 2): score every directly-follows relation in the fact sheet's graph
     * with the dependency measure + a χ² independence test, classify it into a {@code CausalEdgeType},
     * and emit the weighted PSL rules it implies — all without an LLM.
     */
    public ProcessCausalAnalyzer.ProcessCausalModel causalAnalysis(Long factSheetId, String anchorType) {
        return ProcessCausalAnalyzer.analyze(extractLog(factSheetId, anchorType));
    }

    /**
     * Live PSL inference driven by the discovered process: builds an HL-MRF program over the activities
     * with the directly-follows dependency strengths as {@code Link} truths and runs the existing engine.
     * Optionally clamp some activities as observed-active evidence.
     */
    public ProcessPslInference.Result pslInference(Long factSheetId, Collection<String> evidenceActive, String anchorType) {
        DirectlyFollowsGraph dfg = DfgBuilder.build(extractLog(factSheetId, anchorType));
        return ProcessPslInference.infer(dfg, evidenceActive);
    }

    /**
     * Bayesian inference driven by the discovered process: a noisy-OR network over the activities, with
     * directly-follows dependency strengths as edge weights, solved by exact variable elimination.
     */
    public ProcessBayesianInference.Result bayesianInference(Long factSheetId, Collection<String> evidenceActive, String anchorType) {
        DirectlyFollowsGraph dfg = DfgBuilder.build(extractLog(factSheetId, anchorType));
        return ProcessBayesianInference.infer(dfg, evidenceActive);
    }

    /** The declarative (Declare/MINERful) constraint view of the discovered process. */
    public List<DeclareConstraint> declareConstraints(Long factSheetId, double minSupport, double minConfidence, String anchorType) {
        return DeclareMiner.mine(extractLog(factSheetId, anchorType), minSupport, minConfidence);
    }

    /** Mermaid diagram source for the discovered process: the directly-follows map and the tree blocks. */
    public Map<String, String> mermaid(Long factSheetId, double noiseThreshold, String anchorType) {
        EventLog eventLog = extractLog(factSheetId, anchorType);
        DirectlyFollowsGraph dfg = DfgBuilder.build(eventLog);
        ProcessTree tree = new InductiveMiner(noiseThreshold).mine(eventLog);
        Map<String, String> out = new LinkedHashMap<>();
        out.put("dfg", ProcessMermaidExporter.dfgToMermaid(dfg));
        out.put("tree", ProcessMermaidExporter.treeToMermaid(tree));
        return out;
    }

    /**
     * Mine a Heuristics Net from the fact sheet's graph using the Weijters &amp; van der Aalst
     * dependency-threshold cut. Arcs whose dependency measure is below {@code dependencyThreshold}
     * are pruned; typical default is 0.5.
     *
     * @param factSheetId        the fact sheet whose knowledge graph supplies the event log
     * @param dependencyThreshold dependency-measure cut-off in (-1, 1); arcs below this are dropped
     * @param anchorType         optional object-centric case notion
     * @return the mined {@link HeuristicsNet}, never {@code null} (may have no arcs if the graph is
     *         empty or all activities are symmetric)
     */
    public HeuristicsNet heuristicsNet(Long factSheetId, double dependencyThreshold, String anchorType) {
        EventLog eventLog = extractLog(factSheetId, anchorType);
        DirectlyFollowsGraph dfg = DfgBuilder.build(eventLog);
        return HeuristicsMiner.mine(dfg, dependencyThreshold);
    }

    /** Conformance of the discovered model to its log: fitness, precision, simplicity. */
    public ConformanceResult conformance(Long factSheetId, double noiseThreshold, String anchorType) {
        EventLog eventLog = extractLog(factSheetId, anchorType);
        ProcessTree tree = new InductiveMiner(noiseThreshold).mine(eventLog);
        return ConformanceChecker.check(tree, eventLog);
    }

    /**
     * Performance (bottleneck) analysis: per directly-follows arc, the count, mean, and median
     * transition duration in seconds (pairs without timestamps are skipped). Arcs are sorted by
     * median descending so bottlenecks appear first.
     */
    public PerformanceAnalysis performance(Long factSheetId, String anchorType) {
        return PerformanceMiner.analyze(extractLog(factSheetId, anchorType));
    }

    /**
     * Export the discovered process for a fact sheet as BPMN 2.0 XML.
     *
     * <p>Runs the full discovery pipeline (extract → mine → ground → role-bind) and then
     * exports the result via {@link ProcessBpmnExporter}. The BPMN XML is returned as a string
     * so the controller can set the appropriate {@code Content-Type: application/xml} header.
     *
     * @param factSheetId    the fact sheet whose graph is the source
     * @param noiseThreshold 0 for classic Inductive Miner; 0&lt;t≤1 for IMf infrequent filter
     * @param anchorType     optional object-centric case notion
     * @return BPMN 2.0 XML string, or {@code null} when the graph yielded no events
     */
    public String bpmnExport(Long factSheetId, double noiseThreshold, String anchorType) {
        ProcessSuggestion suggestion = discoverForFactSheet(factSheetId, noiseThreshold, anchorType);
        if (suggestion == null) return null;
        return ProcessBpmnExporter.export(suggestion);
    }

    /**
     * The entailment view of a fact sheet's process (pure inspection — nothing persisted):
     * Declare constraints + directly-follows evidence → the settled {@code Precedes} relation
     * with per-pair provenance and temporal verdicts.
     */
    public ProcessEntailmentResult entailment(Long factSheetId, String anchorType,
                                              double minSupport, double minConfidence) {
        EventLog eventLog = extractLog(factSheetId, anchorType);
        if (eventLog.isEmpty()) {
            return ProcessEntailmentResult.empty();
        }
        DirectlyFollowsGraph dfg = DfgBuilder.build(eventLog);
        List<DeclareConstraint> constraints = DeclareMiner.mine(eventLog, minSupport, minConfidence);
        return ProcessEntailment.entail(eventLog, dfg, constraints);
    }

    /**
     * Adds entailed high-confidence orderings as step dependencies (by step name — the accept path
     * translates names to step ids). Tree-derived dependencies stay; a reachability guard keeps the
     * dependency graph acyclic even if the antisymmetry suppression left a borderline pair.
     */
    private void applyEntailedDependencies(ProcessSuggestion suggestion, ProcessEntailmentResult entailment) {
        if (suggestion.getPhases() == null) {
            return;
        }
        Map<String, ProcessSuggestion.SuggestedStep> byName = new LinkedHashMap<>();
        for (ProcessSuggestion.SuggestedPhase phase : suggestion.getPhases()) {
            if (phase.getSteps() == null) {
                continue;
            }
            for (ProcessSuggestion.SuggestedStep step : phase.getSteps()) {
                byName.putIfAbsent(step.getName(), step);
            }
        }
        for (ProcessEntailmentResult.EntailedPrecedence p : entailment.assertable(cfg().getEntailAssertThreshold())) {
            ProcessSuggestion.SuggestedStep target = byName.get(p.to());
            if (target == null || !byName.containsKey(p.from()) || p.from().equals(p.to())) {
                continue;
            }
            if (target.getDependsOn().contains(p.from())) {
                continue;
            }
            if (dependencyPathExists(byName, p.from(), p.to())) {
                continue; // adding it would create a cycle
            }
            target.getDependsOn().add(p.from());
        }
    }

    /** True when {@code start} already (transitively) depends on {@code goal}. */
    private static boolean dependencyPathExists(Map<String, ProcessSuggestion.SuggestedStep> byName,
                                                String start, String goal) {
        ArrayDeque<String> frontier = new ArrayDeque<>();
        Set<String> visited = new HashSet<>();
        frontier.add(start);
        while (!frontier.isEmpty()) {
            String current = frontier.poll();
            if (!visited.add(current)) {
                continue;
            }
            if (current.equals(goal)) {
                return true;
            }
            ProcessSuggestion.SuggestedStep step = byName.get(current);
            if (step != null && step.getDependsOn() != null) {
                frontier.addAll(step.getDependsOn());
            }
        }
        return false;
    }

    /**
     * Resolves a raw graph node ID to its human-readable title, falling back to the raw ID
     * when the node is not found or has no title set.
     */
    private String resolveNodeTitle(String nodeId) {
        return graph.getNode(nodeId)
                .map(n -> n.getTitle() != null && !n.getTitle().isBlank() ? n.getTitle() : nodeId)
                .orElse(nodeId);
    }
}
