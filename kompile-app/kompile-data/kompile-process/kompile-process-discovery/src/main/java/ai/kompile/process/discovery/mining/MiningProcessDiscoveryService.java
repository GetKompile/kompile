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

import ai.kompile.knowledgegraph.service.KnowledgeGraphService;
import ai.kompile.process.discovery.ProcessSuggestion;
import ai.kompile.process.discovery.ProcessSuggestionStore;
import ai.kompile.process.discovery.mining.causal.ProcessCausalAnalyzer;
import ai.kompile.process.discovery.mining.convert.ProcessTreeToSuggestion;
import ai.kompile.process.discovery.mining.dfg.DfgBuilder;
import ai.kompile.process.discovery.mining.dfg.DirectlyFollowsGraph;
import ai.kompile.process.discovery.mining.extract.EventLogExtractor;
import ai.kompile.process.discovery.mining.log.EventLog;
import ai.kompile.process.discovery.mining.miner.InductiveMiner;
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
    private final EventLogExtractor extractor = new EventLogExtractor();

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

    /**
     * Extract → Inductive Miner → {@link ProcessSuggestion}, persisted to the suggestion store.
     *
     * @param noiseThreshold 0 for classic Inductive Miner; 0&lt;t≤1 for the IMf infrequent-filter variant
     * @return the discovered suggestion, or {@code null} if the graph yielded no events
     */
    public ProcessSuggestion discoverForFactSheet(Long factSheetId, double noiseThreshold) {
        EventLog eventLog = extractor.extractForFactSheet(graph, factSheetId);
        if (eventLog.isEmpty()) {
            log.info("Process mining: no events extracted for fact sheet {} — nothing to discover", factSheetId);
            return null;
        }
        ProcessTree tree = new InductiveMiner(noiseThreshold).mine(eventLog);
        ProcessSuggestion suggestion = ProcessTreeToSuggestion.convert(
                tree, eventLog, "Mined process (fact sheet " + factSheetId + ")");
        suggestion.setId("mined-" + UUID.randomUUID());
        suggestion.setFactSheetId(factSheetId);
        suggestion.setDiscoveredAt(Instant.now());
        if (suggestionStore != null) {
            suggestionStore.saveAll(List.of(suggestion));
        }
        log.info("Process mining discovered a {}-phase process for fact sheet {}: {}",
                suggestion.getPhases().size(), factSheetId, tree);
        return suggestion;
    }

    /** The raw discovered model, without conversion or persistence. */
    public ProcessTree mineFactSheet(Long factSheetId, double noiseThreshold) {
        return new InductiveMiner(noiseThreshold).mine(extractor.extractForFactSheet(graph, factSheetId));
    }

    /** Inspect the intermediate artifacts (log, DFG, tree) without persisting — drives the UI preview. */
    public Map<String, Object> preview(Long factSheetId, double noiseThreshold) {
        EventLog eventLog = extractor.extractForFactSheet(graph, factSheetId);
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
    public ProcessCausalAnalyzer.ProcessCausalModel causalAnalysis(Long factSheetId) {
        return ProcessCausalAnalyzer.analyze(extractor.extractForFactSheet(graph, factSheetId));
    }
}
