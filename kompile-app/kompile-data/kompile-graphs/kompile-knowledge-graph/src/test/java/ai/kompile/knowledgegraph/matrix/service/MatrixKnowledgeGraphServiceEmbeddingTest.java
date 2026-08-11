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
package ai.kompile.knowledgegraph.matrix.service;

import ai.kompile.knowledgegraph.matrix.model.AdjacencyMatrixGraph;
import ai.kompile.knowledgegraph.matrix.model.MatrixGraphNode;
import ai.kompile.knowledgegraph.matrix.store.MatrixGraphStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.nd4j.linalg.factory.Nd4j;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the store-agnostic node-embedding seam on the matrix/vector store.
 */
@ExtendWith(MockitoExtension.class)
class MatrixKnowledgeGraphServiceEmbeddingTest {

    private static final String DEFAULT_GRAPH_ID = "default-knowledge-graph";

    @Mock
    private MatrixGraphStore graphStore;

    private MatrixKnowledgeGraphService service;

    @BeforeEach
    void setUp() {
        service = new MatrixKnowledgeGraphService(graphStore, new ObjectMapper());
    }

    @Test
    void exportNodeEmbeddings_filtersByFactSheetAndSkipsZeroRows() {
        AdjacencyMatrixGraph graph = mock(AdjacencyMatrixGraph.class);
        // exportNodeEmbeddings(1L) reads the segmented graph "factsheet_1"
        when(graphStore.loadGraph("factsheet_1")).thenReturn(Optional.of(graph));

        MatrixGraphNode n1 = MatrixGraphNode.builder().nodeId("n1").factSheetId(1L).build();
        MatrixGraphNode n2 = MatrixGraphNode.builder().nodeId("n2").factSheetId(1L).build(); // zero row
        MatrixGraphNode n3 = MatrixGraphNode.builder().nodeId("n3").factSheetId(2L).build(); // other fact sheet
        when(graphStore.getAllNodes("factsheet_1")).thenReturn(List.of(n1, n2, n3));

        when(graph.getNodeEmbedding("n1")).thenReturn(Nd4j.create(new float[]{1f, 2f, 3f}, new long[]{3}));
        when(graph.getNodeEmbedding("n2")).thenReturn(Nd4j.zeros(3));

        Map<String, INDArray> result = service.exportNodeEmbeddings(1L);

        assertEquals(1, result.size());
        assertTrue(result.containsKey("n1"));
        assertFalse(result.containsKey("n2"), "all-zero row must be skipped");
        assertFalse(result.containsKey("n3"), "node from another fact sheet must be excluded");
        assertArrayEquals(new float[]{1f, 2f, 3f}, result.get("n1").toFloatVector(), 1e-6f);
    }

    @Test
    void applyNodeEmbeddings_batchesKnownNodesAndSkipsUnknown() {
        when(graphStore.getNode(DEFAULT_GRAPH_ID, "n1"))
                .thenReturn(Optional.of(MatrixGraphNode.builder().nodeId("n1").build()));
        when(graphStore.getNode(DEFAULT_GRAPH_ID, "ghost")).thenReturn(Optional.empty());

        Map<String, INDArray> in = new LinkedHashMap<>();
        in.put("n1", Nd4j.create(new float[]{1f, 2f, 3f}, new long[]{3}));
        in.put("ghost", Nd4j.create(new float[]{9f, 9f, 9f}, new long[]{3})); // not in the rehydrated graph

        int applied = service.applyNodeEmbeddings(in);

        assertEquals(1, applied);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> ids = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<INDArray> emb = ArgumentCaptor.forClass(INDArray.class);
        verify(graphStore).storeNodeEmbeddings(eq(DEFAULT_GRAPH_ID), ids.capture(), emb.capture());
        assertEquals(List.of("n1"), ids.getValue());
        assertArrayEquals(new long[]{1, 3}, emb.getValue().shape(), "embeddings must be stacked [n, dim]");
    }

    @Test
    void applyNodeEmbeddings_emptyMapIsNoOp() {
        assertEquals(0, service.applyNodeEmbeddings(Map.of()));
        verifyNoInteractions(graphStore);
    }
}
