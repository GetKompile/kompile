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

import ai.kompile.graph.reasoning.fol.grounding.PlattCalibrator;
import ai.kompile.knowledgegraph.grounding.KbGroundingService;
import ai.kompile.knowledgegraph.service.KnowledgeGraphService;
import ai.kompile.process.discovery.ProcessSuggestion;
import ai.kompile.process.discovery.ProcessSuggestionStore;
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
import ai.kompile.process.discovery.mining.export.ProcessBpmnExporter;
import ai.kompile.process.discovery.mining.export.ProcessMermaidExporter;
import ai.kompile.process.discovery.mining.extract.ActivityClassifier;
import ai.kompile.process.discovery.mining.extract.AnchorTypeCorrelation;
import ai.kompile.process.discovery.mining.extract.EventLogExtractor;
import ai.kompile.process.discovery.mining.extract.RoleBindingExtractor;
import ai.kompile.process.discovery.mining.log.EventLog;
import ai.kompile.process.discovery.mining.miner.HeuristicsMiner;
import ai.kompile.process.discovery.mining.miner.HeuristicsNet;
import ai.kompile.process.discovery.mining.miner.InductiveMiner;
import ai.kompile.process.discovery.mining.perf.PerformanceAnalysis;
import ai.kompile.process.discovery.mining.perf.PerformanceMiner;
import ai.kompile.process.discovery.mining.dfg.DirectlyFollowsGraph;
import ai.kompile.process.discovery.mining.rules.MinedRulePersistenceService;
import ai.kompile.process.discovery.mining.tree.ProcessTree;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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

    /** Optional object-centric case notion: when set, each entity of this type anchors a process instance. */
    @org.springframework.beans.factory.annotation.Value("${kompile.process.mining.anchor-type:}")
    private String anchorEntityType;

    /** Switches the extractor to anchor-based correlation when an anchor type is configured. */
    @jakarta.annotation.PostConstruct
    void configureCaseNotion() {
        if (anchorEntityType != null && !anchorEntityType.isBlank()) {
            this.extractor = new EventLogExtractor(
                    ai.kompile.process.discovery.mining.extract.ActivityClassifier.byEntityType(),
                    new ai.kompile.process.discovery.mining.extract.AnchorTypeCorrelation(anchorEntityType),
                    EventLogExtractor.DEFAULT_EXCLUDED_LEVELS);
        }
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

    /**
     * Returns an event log for the given fact sheet using the per-request anchor type when supplied,
     * otherwise falls back to the service-level extractor (which may itself be anchor-configured via
     * {@code kompile.process.mining.anchor-type}).
     */
    private EventLog extractLog(Long factSheetId, String anchorType) {
        if (anchorType == null || anchorType.isBlank()) {
            return extractor.extractForFactSheet(graph, factSheetId);
        }
        return new EventLogExtractor(ActivityClassifier.byEntityType(),
                new AnchorTypeCorrelation(anchorType),
                EventLogExtractor.DEFAULT_EXCLUDED_LEVELS)
                .extractForFactSheet(graph, factSheetId);
    }

    /**
     * Extract → Inductive Miner → {@link ProcessSuggestion}, persisted to the suggestion store.
     *
     * @param noiseThreshold 0 for classic Inductive Miner; 0&lt;t≤1 for the IMf infrequent-filter variant
     * @param anchorType     optional object-centric case notion: one process instance per entity of this type
     * @return the discovered suggestion, or {@code null} if the graph yielded no events
     */
    public ProcessSuggestion discoverForFactSheet(Long factSheetId, double noiseThreshold, String anchorType) {
        EventLog eventLog = extractLog(factSheetId, anchorType);
        if (eventLog.isEmpty()) {
            log.info("Process mining: no events extracted for fact sheet {} — nothing to discover", factSheetId);
            return null;
        }
        ProcessTree tree = new InductiveMiner(noiseThreshold).mine(eventLog);

        // Use grounded conversion when KbGroundingService is wired
        PlattCalibrator calibrator = new PlattCalibrator();
        ProcessSuggestion suggestion = ProcessTreeToSuggestion.convertGrounded(
                tree, eventLog,
                "Mined process (fact sheet " + factSheetId + ")",
                kbGroundingService, calibrator, factSheetId);

        // Apply role bindings via ConjunctiveQueryEngine-backed extractor
        if (kbGroundingService != null) {
            suggestion.getPhases().forEach(phase ->
                RoleBindingExtractor.applyRoleBindings(phase.getSteps(), kbGroundingService, factSheetId));
        }

        // L4: causal analysis → persist PSL rules → propagate into next cascade.
        // Run on the same event log so no redundant extraction occurs.
        ProcessCausalAnalyzer.ProcessCausalModel causalModel =
                ProcessCausalAnalyzer.analyze(eventLog);

        // Attach causal arcs as structured evidence on the suggestion (D3-B)
        if (!causalModel.dependencies().isEmpty()) {
            List<ProcessSuggestion.StructuredEvidence> causalEvidence = new java.util.ArrayList<>();
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
            suggestion.getStructuredEvidence().addAll(causalEvidence);
        }

        // Persist mined rules to <dataDir>/rules/<factSheetId>-mined.psl (+ lineage + index + audit)
        if (rulePersistenceService != null && !causalModel.pslRules().isEmpty()) {
            try {
                MinedRulePersistenceService.PersistResult result =
                        rulePersistenceService.persistCausalRules(factSheetId, causalModel);
                if (result.persisted()) {
                    log.info("Process mining: persisted {} rules for fact sheet {} → {} (v{})",
                            result.ruleCount(), factSheetId, result.fileName(), result.version());
                }
            } catch (Exception e) {
                // Rule persistence failure is non-fatal: suggestion is still returned
                log.warn("Process mining: rule persistence failed for fact sheet {} — {}", factSheetId, e.getMessage());
            }
        }

        suggestion.setId("mined-" + UUID.randomUUID());
        suggestion.setFactSheetId(factSheetId);
        suggestion.setDiscoveredAt(Instant.now());
        if (suggestionStore != null) {
            suggestionStore.saveAll(List.of(suggestion));
        }
        log.info("Process mining discovered a {}-phase process for fact sheet {} (grounding={}, causalRules={}): {}",
                suggestion.getPhases().size(), factSheetId, kbGroundingService != null,
                causalModel.pslRules().size(), tree);
        return suggestion;
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
    public ProcessPslInference.Result pslInference(Long factSheetId, java.util.Collection<String> evidenceActive, String anchorType) {
        DirectlyFollowsGraph dfg = DfgBuilder.build(extractLog(factSheetId, anchorType));
        return ProcessPslInference.infer(dfg, evidenceActive);
    }

    /**
     * Bayesian inference driven by the discovered process: a noisy-OR network over the activities, with
     * directly-follows dependency strengths as edge weights, solved by exact variable elimination.
     */
    public ProcessBayesianInference.Result bayesianInference(Long factSheetId, java.util.Collection<String> evidenceActive, String anchorType) {
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
}
