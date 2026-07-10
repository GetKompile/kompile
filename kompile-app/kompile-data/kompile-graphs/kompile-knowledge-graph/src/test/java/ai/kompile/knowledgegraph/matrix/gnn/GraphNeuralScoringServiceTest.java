/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ai.kompile.knowledgegraph.matrix.gnn;

import ai.kompile.knowledgegraph.domain.EdgeType;
import ai.kompile.knowledgegraph.domain.GraphEdge;
import ai.kompile.knowledgegraph.domain.GraphNode;
import ai.kompile.knowledgegraph.domain.NodeLevel;
import ai.kompile.knowledgegraph.service.KnowledgeGraphService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GraphNeuralScoringServiceTest {

    @Mock
    private KnowledgeGraphService knowledgeGraphService;

    private GraphNeuralScoringService scorer;

    @BeforeEach
    void setUp() {
        scorer = new GraphNeuralScoringService(knowledgeGraphService);
    }

    @Test
    void scoresGraphOnlyFeaturesAndPersistsBatches() {
        List<GraphNode> nodes = List.of(
                node("person-1", NodeLevel.ENTITY, "Finance manager"),
                node("email-1", NodeLevel.DOCUMENT, "Forecast review request"),
                node("file-1", NodeLevel.ATTACHMENT, "Forecast workbook"));
        List<GraphEdge> edges = List.of(
                edge("person-1", "email-1", "SENT", 0.9),
                edge("email-1", "file-1", "HAS_ATTACHMENT", 0.8));

        when(knowledgeGraphService.getNodesInFactSheet(11L)).thenReturn(nodes);
        when(knowledgeGraphService.getEdgesInFactSheet(11L)).thenReturn(edges);

        List<KnowledgeGraphService.EdgeMetadataUpdate> persisted = new ArrayList<>();
        when(knowledgeGraphService.updateEdgeMetadataBatch(anyList())).thenAnswer(invocation -> {
            List<KnowledgeGraphService.EdgeMetadataUpdate> batch = invocation.getArgument(0);
            persisted.addAll(batch);
            return batch.size();
        });

        GraphNeuralScoringService.ScoringResult result =
                scorer.scoreFactSheetEdges(11L, 10, 10, 1, 0.7, 0.3);

        assertEquals("factsheet_11", result.graphId());
        assertEquals(3, result.nodeCount());
        assertEquals(2, result.edgesSeen());
        assertEquals(2, result.edgesScored());
        assertFalse(result.skipped());
        assertEquals("ok", result.reason());
        assertEquals(2, persisted.size());

        for (KnowledgeGraphService.EdgeMetadataUpdate update : persisted) {
            Map<String, Object> metadata = update.additionalMetadata();
            Number score = assertInstanceOf(Number.class,
                    metadata.get(GraphNeuralScoringService.SCORE_KEY));
            assertTrue(Double.isFinite(score.doubleValue()));
            assertTrue(score.doubleValue() >= 0.0 && score.doubleValue() <= 1.0);
            assertEquals(GraphNeuralScoringService.MODEL_NAME,
                    metadata.get(GraphNeuralScoringService.MODEL_KEY));
            assertEquals(score, metadata.get("neural.score"));
        }
    }

    @Test
    void skipsGraphsBeyondConfiguredSafetyBound() {
        when(knowledgeGraphService.getNodesInFactSheet(12L)).thenReturn(List.of(
                node("n1", NodeLevel.ENTITY, "One"),
                node("n2", NodeLevel.ENTITY, "Two")));
        when(knowledgeGraphService.getEdgesInFactSheet(12L)).thenReturn(List.of(
                edge("n1", "n2", "RELATED_TO", 1.0)));

        GraphNeuralScoringService.ScoringResult result =
                scorer.scoreFactSheetEdges(12L, 1, 10, 50, 1.0, 1.0);

        assertTrue(result.skipped());
        assertEquals(0, result.edgesScored());
        assertTrue(result.reason().contains("node safety bound exceeded"));
        verify(knowledgeGraphService, never()).updateEdgeMetadataBatch(anyList());
    }

    private static GraphNode node(String id, NodeLevel level, String title) {
        return GraphNode.builder()
                .nodeId(id)
                .nodeType(level)
                .externalId(id)
                .title(title)
                .metadataJson("{\"source\":\"test-graph\"}")
                .stale(false)
                .build();
    }

    private static GraphEdge edge(
            String sourceId, String targetId, String relationType, double weight) {
        return GraphEdge.builder()
                .edgeId(sourceId + "::" + targetId + "::" + relationType)
                .sourceNodeId(sourceId)
                .targetNodeId(targetId)
                .edgeType(EdgeType.USER_DEFINED)
                .relationType(relationType)
                .weight(weight)
                .stale(false)
                .build();
    }
}
