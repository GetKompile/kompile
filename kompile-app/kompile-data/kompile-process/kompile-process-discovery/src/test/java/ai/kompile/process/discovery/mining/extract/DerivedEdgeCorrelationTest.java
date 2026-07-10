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

import ai.kompile.knowledgegraph.domain.EdgeProvenance;
import ai.kompile.knowledgegraph.domain.GraphEdge;
import ai.kompile.knowledgegraph.domain.GraphNode;
import ai.kompile.knowledgegraph.domain.NodeLevel;
import ai.kompile.process.discovery.mining.log.EventLog;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The correlation feedback-loop guard: the miner's own materialized control-flow edges
 * (DIRECTLY_FOLLOWS/PRECEDES) and INFERRED-provenance edges (OWL closures, cascade
 * materializations) must NOT union previously-distinct process instances — otherwise each
 * mining run reshapes the next run's cases.
 */
class DerivedEdgeCorrelationTest {

    private static final LocalDateTime BASE = LocalDateTime.of(2024, 6, 1, 8, 0);

    private static GraphNode node(String id, String entityType, int minute) {
        return GraphNode.builder()
                .nodeId(id)
                .nodeType(NodeLevel.ENTITY)
                .occurredAt(BASE.plusMinutes(minute))
                .metadataJson("{\"entity_type\":\"" + entityType + "\"}")
                .build();
    }

    private static GraphEdge edge(GraphNode src, GraphNode tgt, String relationType,
                                  EdgeProvenance provenance) {
        return GraphEdge.builder()
                .edgeId(src.getNodeId() + "->" + tgt.getNodeId())
                .sourceNodeId(src.getNodeId())
                .targetNodeId(tgt.getNodeId())
                .sourceNode(src)
                .targetNode(tgt)
                .relationType(relationType)
                .provenanceType(provenance)
                .build();
    }

    @Test
    void minedControlFlowEdges_doNotMergeCases() {
        GraphNode a1 = node("a1", "Invoice", 0);
        GraphNode a2 = node("a2", "Approve", 1);
        GraphNode b1 = node("b1", "Interview", 10);
        GraphNode b2 = node("b2", "Offer", 11);

        // Two observed components; the ONLY cross-links are reasoning products from a prior run.
        List<GraphEdge> edges = List.of(
                edge(a1, a2, null, null),                                   // observed
                edge(b1, b2, null, null),                                   // observed
                edge(a2, b1, "PRECEDES", EdgeProvenance.INFERRED),          // entailed write-back
                edge(a2, b1, "DIRECTLY_FOLLOWS", null),                     // mined write-back (label alone)
                edge(a1, b2, "RELATED_TO", EdgeProvenance.INFERRED));       // OWL/cascade product

        EventLog log = new EventLogExtractor().extract(List.of(a1, a2, b1, b2), edges);

        assertEquals(2, log.size(),
                "derived edges must be invisible to case correlation — cases stay distinct");
    }

    @Test
    void undatedNode_isDatedByIncidentEdgeOccurredAt_relationMetadata() {
        // The email lane stamps occurredAt on RELATIONS; a node the persist path never lifted a
        // time onto must be dated by its earliest incident observed edge — while a derived edge's
        // time must never date anything.
        GraphNode undated = GraphNode.builder()
                .nodeId("u1").nodeType(NodeLevel.ENTITY)
                .metadataJson("{\"entity_type\":\"Approve\"}")
                .build();
        GraphNode dated = node("d1", "Invoice", 60);

        GraphEdge observed = GraphEdge.builder()
                .edgeId("d1->u1").sourceNodeId("d1").targetNodeId("u1")
                .sourceNode(dated).targetNode(undated)
                .relationType("RELATES_TO").provenanceType(EdgeProvenance.EXTRACTED)
                .occurredAt(BASE.plusMinutes(30))
                .build();
        GraphEdge derivedLater = GraphEdge.builder()
                .edgeId("d1->u1-inferred").sourceNodeId("d1").targetNodeId("u1")
                .sourceNode(dated).targetNode(undated)
                .relationType("PRECEDES").provenanceType(EdgeProvenance.INFERRED)
                .occurredAt(BASE.minusDays(1)) // earlier, but derived — must be ignored
                .build();

        EventLog log = new EventLogExtractor().extract(List.of(undated, dated),
                List.of(observed, derivedLater));

        assertEquals(1, log.size());
        var byActivity = log.traces().get(0).events().stream()
                .collect(java.util.stream.Collectors.toMap(e -> e.activity(), e -> e));
        assertEquals(BASE.plusMinutes(30), byActivity.get("Approve").timestamp(),
                "undated node takes the earliest incident OBSERVED edge time");
        assertEquals(BASE.plusMinutes(60), byActivity.get("Invoice").timestamp(),
                "a node's own occurredAt always wins");
    }

    @Test
    void observedEdges_stillMergeCases() {
        GraphNode a1 = node("a1", "Invoice", 0);
        GraphNode a2 = node("a2", "Approve", 1);
        GraphNode b1 = node("b1", "Pay", 2);

        List<GraphEdge> edges = List.of(
                edge(a1, a2, null, null),
                edge(a2, b1, "EXTRACTED_REL", EdgeProvenance.EXTRACTED));

        EventLog log = new EventLogExtractor().extract(List.of(a1, a2, b1), edges);

        assertEquals(1, log.size(), "genuinely observed edges keep correlating cases");
    }
}
