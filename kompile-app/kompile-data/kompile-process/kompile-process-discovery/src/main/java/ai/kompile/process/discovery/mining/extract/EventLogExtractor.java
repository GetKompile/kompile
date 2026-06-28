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

package ai.kompile.process.discovery.mining.extract;

import ai.kompile.knowledgegraph.domain.GraphEdge;
import ai.kompile.knowledgegraph.domain.GraphNode;
import ai.kompile.knowledgegraph.domain.NodeLevel;
import ai.kompile.knowledgegraph.service.KnowledgeGraphService;
import ai.kompile.process.discovery.mining.log.Event;
import ai.kompile.process.discovery.mining.log.EventLog;
import ai.kompile.process.discovery.mining.log.Trace;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Turns a knowledge (sub)graph into an {@link EventLog} — the bridge from "data" to "process mining."
 *
 * <p>This is the Event-Knowledge-Graph construction (Esser &amp; Fahland) specialised to kompile's
 * graph: a {@link CaseCorrelation} strategy groups nodes into cases, each node becomes an
 * {@link Event} (activity via {@link ActivityClassifier}, time via {@link GraphNode#getOccurredAt()},
 * provenance via the node id), and the events of a case are ordered by timestamp into a directly-follows
 * {@link Trace}. Purely structural — no LLM.
 *
 * <p>Structural plumbing nodes ({@code SOURCE} crawl roots and {@code SNIPPET} chunks) are excluded by
 * default so the log reflects real entities/documents/events rather than ingestion artefacts.
 */
public final class EventLogExtractor {

    /** Levels that are ingestion scaffolding rather than process events. */
    public static final Set<NodeLevel> DEFAULT_EXCLUDED_LEVELS = EnumSet.of(NodeLevel.SOURCE, NodeLevel.SNIPPET);

    private final ActivityClassifier classifier;
    private final CaseCorrelation correlation;
    private final Set<NodeLevel> excludedLevels;

    public EventLogExtractor() {
        this(ActivityClassifier.byEntityType(), new ConnectedComponentCorrelation(), DEFAULT_EXCLUDED_LEVELS);
    }

    public EventLogExtractor(ActivityClassifier classifier, CaseCorrelation correlation, Set<NodeLevel> excludedLevels) {
        this.classifier = (classifier != null) ? classifier : ActivityClassifier.byEntityType();
        this.correlation = (correlation != null) ? correlation : new ConnectedComponentCorrelation();
        this.excludedLevels = (excludedLevels != null) ? EnumSet.copyOf(excludedLevels) : EnumSet.noneOf(NodeLevel.class);
    }

    /** Convenience: pull a fact sheet's nodes and edges from the graph service and extract a log. */
    public EventLog extractForFactSheet(KnowledgeGraphService graph, Long factSheetId) {
        List<GraphNode> nodes = graph.getNodesInFactSheet(factSheetId);
        List<GraphEdge> edges = graph.getEdgesInFactSheet(factSheetId);
        return extract(nodes, edges);
    }

    /**
     * Extracts an event log from an explicit node/edge set. Pure (no graph-service calls), which keeps
     * the whole mining pipeline unit-testable on hand-built graphs.
     */
    public EventLog extract(List<GraphNode> nodes, List<GraphEdge> edges) {
        Map<String, GraphNode> byId = new LinkedHashMap<>();
        if (nodes != null) {
            for (GraphNode n : nodes) {
                if (n.getNodeId() == null) {
                    continue;
                }
                if (n.getNodeType() != null && excludedLevels.contains(n.getNodeType())) {
                    continue;
                }
                byId.put(n.getNodeId(), n);
            }
        }

        Map<String, List<String>> cases = correlation.correlate(
                new ArrayList<>(byId.values()), edges != null ? edges : List.of());

        List<Trace> traces = new ArrayList<>();
        for (Map.Entry<String, List<String>> entry : cases.entrySet()) {
            String caseId = entry.getKey();
            List<Event> events = new ArrayList<>();
            for (String nodeId : entry.getValue()) {
                GraphNode node = byId.get(nodeId);
                if (node == null) {
                    continue; // filtered out as an excluded level
                }
                String activity = classifier.activityOf(node);
                if (activity == null) {
                    continue; // classifier excluded this node (e.g. a spreadsheet structural type)
                }
                events.add(new Event(caseId, activity, node.getOccurredAt(), nodeId, Map.of()));
            }
            if (!events.isEmpty()) {
                traces.add(new Trace(caseId, events));
            }
        }
        return new EventLog(traces);
    }
}
