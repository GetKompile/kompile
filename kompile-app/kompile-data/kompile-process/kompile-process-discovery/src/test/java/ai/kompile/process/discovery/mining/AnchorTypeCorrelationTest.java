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
import ai.kompile.process.discovery.mining.extract.AnchorTypeCorrelation;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the object-centric case notion: each anchor-type entity seeds its own case, neighbours attach
 * to their nearest anchor, and nodes unreachable from any anchor are excluded.
 */
class AnchorTypeCorrelationTest {

    private GraphNode node(String id, String entityType) {
        return GraphNode.builder()
                .nodeId(id).nodeType(NodeLevel.ENTITY).externalId(id).title(id)
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
    void eachAnchorBecomesItsOwnCaseWithNearestNeighbours() {
        GraphNode order1 = node("o1", "ORDER");
        GraphNode order2 = node("o2", "ORDER");
        GraphNode item1a = node("i1a", "ITEM");
        GraphNode item1b = node("i1b", "ITEM");
        GraphNode item2a = node("i2a", "ITEM");
        GraphNode orphan = node("x", "NOISE"); // not connected to any anchor

        List<GraphNode> nodes = List.of(order1, order2, item1a, item1b, item2a, orphan);
        List<GraphEdge> edges = List.of(
                edge(order1, item1a), edge(order1, item1b), edge(order2, item2a));

        Map<String, List<String>> cases = new AnchorTypeCorrelation("ORDER").correlate(nodes, edges);

        assertEquals(2, cases.size(), "two ORDER anchors ⇒ two cases");
        assertTrue(cases.get("case-o1").containsAll(List.of("o1", "i1a", "i1b")));
        assertTrue(cases.get("case-o2").containsAll(List.of("o2", "i2a")));
        assertFalse(cases.values().stream().anyMatch(c -> c.contains("x")),
                "a node unreachable from any anchor must be dropped");
    }
}
