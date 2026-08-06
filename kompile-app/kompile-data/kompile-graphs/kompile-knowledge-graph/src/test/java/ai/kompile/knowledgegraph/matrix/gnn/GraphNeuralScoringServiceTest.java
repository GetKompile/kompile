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
    void trainsMessagePassingModelAndPersistsAuditableScores() {
        List<GraphNode> nodes = List.of(
                node("alpha-1", NodeLevel.ENTITY, "Alpha finance planning"),
                node("alpha-2", NodeLevel.DOCUMENT, "Alpha finance forecast"),
                node("alpha-3", NodeLevel.ATTACHMENT, "Alpha finance workbook"),
                node("beta-1", NodeLevel.ENTITY, "Beta engineering planning"),
                node("beta-2", NodeLevel.DOCUMENT, "Beta engineering design"),
                node("beta-3", NodeLevel.ATTACHMENT, "Beta engineering drawing"));
        List<GraphEdge> edges = new ArrayList<>();
        edges.addAll(completeDirectedEdges("alpha-1", "alpha-2", "alpha-3"));
        edges.addAll(completeDirectedEdges("beta-1", "beta-2", "beta-3"));

        when(knowledgeGraphService.getNodesInFactSheet(11L)).thenReturn(nodes);
        when(knowledgeGraphService.getEdgesInFactSheet(11L)).thenReturn(edges);

        List<KnowledgeGraphService.EdgeMetadataUpdate> persisted = new ArrayList<>();
        when(knowledgeGraphService.updateEdgeMetadataBatch(anyList())).thenAnswer(invocation -> {
            List<KnowledgeGraphService.EdgeMetadataUpdate> batch = invocation.getArgument(0);
            persisted.addAll(batch);
            return batch.size();
        });

        GraphNeuralScoringService.ScoringResult result = scorer.scoreFactSheetEdges(
                11L,
                10,
                20,
                3,
                new GraphNeuralScoringService.TrainingConfig(
                        0.7, 0.3, 80, 0.05, 1, 100, 1_729L, 1.0e-4));

        assertEquals("factsheet_11", result.graphId());
        assertEquals(6, result.nodeCount());
        assertEquals(12, result.edgesSeen());
        assertEquals(12, result.edgesScored());
        assertFalse(result.skipped());
        assertEquals("ok", result.reason());
        assertEquals(GraphNeuralScoringService.MODEL_NAME, result.modelName());
        assertTrue(result.trainingExamples() > 0);
        assertTrue(result.validationExamples() > 0);
        assertTrue(result.finalLoss() < result.initialLoss());
        assertTrue(result.validationPositiveMean() > result.validationNegativeMean());
        assertEquals(64, result.modelFingerprint().length());
        assertEquals(12, persisted.size());

        for (KnowledgeGraphService.EdgeMetadataUpdate update : persisted) {
            Map<String, Object> metadata = update.additionalMetadata();
            Number score = assertInstanceOf(Number.class,
                    metadata.get(GraphNeuralScoringService.SCORE_KEY));
            assertTrue(Double.isFinite(score.doubleValue()));
            assertTrue(score.doubleValue() >= 0.0 && score.doubleValue() <= 1.0);
            assertEquals(GraphNeuralScoringService.MODEL_NAME,
                    metadata.get(GraphNeuralScoringService.MODEL_KEY));
            assertEquals(GraphNeuralScoringService.RUNTIME_NAME, metadata.get("gnn.runtime"));
            assertEquals(result.modelFingerprint(), metadata.get("gnn.modelFingerprint"));
            assertEquals(result.initialLoss(), metadata.get("gnn.initialLoss"));
            assertEquals(result.finalLoss(), metadata.get("gnn.finalLoss"));
            assertEquals(score, metadata.get("neural.score"));
        }
    }

    @Test
    void skipsWhenNoAbsentPairExistsForNegativeSupervision() {
        List<GraphNode> nodes = List.of(
                node("n1", NodeLevel.ENTITY, "One"),
                node("n2", NodeLevel.ENTITY, "Two"));
        List<GraphEdge> edges = List.of(
                edge("n1", "n2", "RELATED_TO", 1.0),
                edge("n2", "n1", "RELATED_TO", 1.0));
        when(knowledgeGraphService.getNodesInFactSheet(13L)).thenReturn(nodes);
        when(knowledgeGraphService.getEdgesInFactSheet(13L)).thenReturn(edges);

        GraphNeuralScoringService.ScoringResult result =
                scorer.scoreFactSheetEdges(13L, 10, 10, 50, 0.7, 0.3);

        assertTrue(result.skipped());
        assertTrue(result.reason().contains("negative supervision"));
        assertEquals(0, result.edgesScored());
        verify(knowledgeGraphService, never()).updateEdgeMetadataBatch(anyList());
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

    private static List<GraphEdge> completeDirectedEdges(String... nodeIds) {
        List<GraphEdge> edges = new ArrayList<>();
        for (String source : nodeIds) {
            for (String target : nodeIds) {
                if (!source.equals(target)) {
                    edges.add(edge(source, target, "COLLABORATES_WITH", 1.0));
                }
            }
        }
        return edges;
    }
}
