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

import ai.kompile.knowledgegraph.domain.EdgeType;
import ai.kompile.knowledgegraph.domain.GraphEdge;
import ai.kompile.knowledgegraph.domain.GraphNode;
import ai.kompile.knowledgegraph.domain.NodeLevel;
import ai.kompile.process.discovery.mining.extract.EventLogExtractor;
import ai.kompile.process.discovery.mining.log.EventLog;
import ai.kompile.process.discovery.mining.miner.InductiveMiner;
import ai.kompile.process.discovery.mining.tree.ProcessTreeNode;
import ai.kompile.process.discovery.mining.tree.ProcessTreeNode.Operator;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * End-to-end check of the knowledge-graph → event-log bridge: connected components become cases,
 * events order by {@code occurredAt}, the activity label comes from {@code entity_type}, and
 * scaffolding ({@code SNIPPET}) nodes are excluded — all the way through to a mined process.
 */
class EventLogExtractorTest {

    private GraphNode node(String id, String entityType, NodeLevel level, LocalDateTime when) {
        return GraphNode.builder()
                .nodeId(id).nodeType(level).externalId(id).title(id)
                .occurredAt(when)
                .metadataJson("{\"entity_type\":\"" + entityType + "\"}")
                .build();
    }

    private GraphEdge edge(GraphNode source, GraphNode target) {
        return GraphEdge.builder()
                .edgeId(source.getNodeId() + "->" + target.getNodeId())
                .sourceNode(source).targetNode(target)
                .edgeType(EdgeType.USER_DEFINED).weight(1.0)
                .build();
    }

    @Test
    void extractsTimeOrderedTracesPerConnectedComponentAndMinesTheSequence() {
        LocalDateTime t0 = LocalDateTime.of(2026, 1, 1, 9, 0);

        // Case 1: order → review → approve
        GraphNode o1 = node("o1", "ORDER", NodeLevel.ENTITY, t0);
        GraphNode r1 = node("r1", "REVIEW", NodeLevel.ENTITY, t0.plusHours(1));
        GraphNode a1 = node("a1", "APPROVE", NodeLevel.ENTITY, t0.plusHours(2));
        // Case 2: a second, disconnected instance of the same flow
        GraphNode o2 = node("o2", "ORDER", NodeLevel.ENTITY, t0.plusDays(1));
        GraphNode r2 = node("r2", "REVIEW", NodeLevel.ENTITY, t0.plusDays(1).plusHours(1));
        GraphNode a2 = node("a2", "APPROVE", NodeLevel.ENTITY, t0.plusDays(1).plusHours(2));
        // A chunk (SNIPPET) hanging off case 1 — ingestion scaffolding, must be excluded.
        GraphNode chunk = node("chunk1", "CHUNK", NodeLevel.SNIPPET, t0.plusMinutes(30));

        List<GraphNode> nodes = List.of(o1, r1, a1, o2, r2, a2, chunk);
        List<GraphEdge> edges = List.of(
                edge(o1, r1), edge(r1, a1), edge(o1, chunk),
                edge(o2, r2), edge(r2, a2));

        EventLog log = new EventLogExtractor().extract(nodes, edges);

        assertEquals(2, log.size(), "two disconnected components ⇒ two cases");
        assertEquals(Set.of("ORDER", "REVIEW", "APPROVE"), log.activityNames(), "SNIPPET chunk excluded");

        ProcessTreeNode root = new InductiveMiner().mine(log).root();
        assertEquals(Operator.SEQUENCE, root.operator());
        assertEquals(List.of("ORDER", "REVIEW", "APPROVE"),
                root.children().stream().map(ProcessTreeNode::activity).toList());
    }
}
