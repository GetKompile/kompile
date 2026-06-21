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
package ai.kompile.knowledgegraph.embedding.adapter;

import ai.kompile.core.kgembedding.KGEmbeddingAlgorithm;
import ai.kompile.core.kgembedding.KGEmbeddingModel;
import ai.kompile.core.kgembedding.Triple;
import ai.kompile.knowledgegraph.matrix.model.AdjacencyMatrixGraph;
import ai.kompile.knowledgegraph.matrix.model.MatrixGraphNode;
import ai.kompile.knowledgegraph.matrix.store.MatrixGraphStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.nd4j.linalg.factory.Nd4j;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link MatrixKgEmbeddingGraphAdapter} — the bridge that lets the KG-embedding training
 * pipeline read triples from, and write structural embeddings back to, the live vector/matrix store.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MatrixKgEmbeddingGraphAdapterTest {

    private MatrixGraphStore store;
    private MatrixKgEmbeddingGraphAdapter adapter;
    private AdjacencyMatrixGraph graph;

    @BeforeEach
    void setUp() {
        store = mock(MatrixGraphStore.class);
        adapter = new MatrixKgEmbeddingGraphAdapter(store);

        // a → b → c chain.
        graph = new AdjacencyMatrixGraph("g1", 16);
        graph.addNode(node("a"));
        graph.addNode(node("b"));
        graph.addNode(node("c"));
        graph.addEdge("a", "b", 1.0, "RELATED_TO", false);
        graph.addEdge("b", "c", 1.0, "RELATED_TO", false);

        when(store.listGraphsByFactSheet(1L)).thenReturn(List.of("g1"));
        when(store.loadGraph("g1")).thenReturn(Optional.of(graph));
    }

    @AfterEach
    void tearDown() {
        if (graph != null) {
            graph.close();
        }
    }

    private MatrixGraphNode node(String id) {
        return MatrixGraphNode.builder().nodeId(id).nodeType("CONCEPT").title(id).build();
    }

    @Test
    void hasGraphDataTrueWhenLiveGraphHasEdges() {
        assertTrue(adapter.hasGraphData(1L));
    }

    @Test
    void hasGraphDataFalseWhenNoGraphsForFactSheet() {
        when(store.listGraphsByFactSheet(2L)).thenReturn(List.of());
        assertFalse(adapter.hasGraphData(2L));
    }

    @Test
    void extractTriplesReturnsTypedTriplesKeyedByNodeId() {
        List<Triple> triples = adapter.extractTriples(1L);

        assertEquals(2, triples.size());
        Set<String> asStrings = triples.stream().map(Triple::toString).collect(Collectors.toSet());
        assertTrue(asStrings.contains("(a, RELATED_TO, b)"), asStrings.toString());
        assertTrue(asStrings.contains("(b, RELATED_TO, c)"), asStrings.toString());
    }

    @Test
    void storeEmbeddingsWritesEntityVectorsIntoNodeMetadataAndRoundTrips() {
        KGEmbeddingModel model = mock(KGEmbeddingModel.class);
        when(model.getAlgorithm()).thenReturn(KGEmbeddingAlgorithm.TRANSE);
        when(model.getAllEntityEmbeddings()).thenReturn(Map.of(
                "a", Nd4j.create(new float[]{1f, 2f, 3f}),
                "b", Nd4j.create(new float[]{4f, 5f, 6f})
        ));

        int updated = adapter.storeEmbeddings(model, 1L, 99L);

        assertEquals(2, updated, "two nodes have embeddings; node c does not");
        verify(store).updateNode(eq("g1"), argThat(n -> "a".equals(n.getNodeId())));
        verify(store).updateNode(eq("g1"), argThat(n -> "b".equals(n.getNodeId())));
        verify(store, never()).updateNode(eq("g1"), argThat(n -> "c".equals(n.getNodeId())));

        // Vector round-trips through the node-metadata string encoding.
        MatrixGraphNode a = graph.getNode("a").orElseThrow();
        assertEquals("TRANSE", a.getMetadata().get(MatrixKgEmbeddingGraphAdapter.KGE_ALGORITHM_KEY));
        assertEquals(99L, a.getMetadata().get(MatrixKgEmbeddingGraphAdapter.KGE_VERSION_KEY));
        INDArray decoded = MatrixKgEmbeddingGraphAdapter.decode(
                a.getMetadata().get(MatrixKgEmbeddingGraphAdapter.KGE_EMBEDDING_KEY));
        assertNotNull(decoded);
        assertArrayEquals(new float[]{1f, 2f, 3f}, decoded.toFloatVector(), 1e-5f);

        // Node c had no embedding → no KGE metadata written.
        MatrixGraphNode c = graph.getNode("c").orElseThrow();
        assertNull(c.getMetadata().get(MatrixKgEmbeddingGraphAdapter.KGE_EMBEDDING_KEY));
    }

    @Test
    void encodeDecodeRoundTrip() {
        INDArray v = Nd4j.create(new float[]{0.5f, -1.25f, 3.0f});
        INDArray back = MatrixKgEmbeddingGraphAdapter.decode(MatrixKgEmbeddingGraphAdapter.encode(v));
        assertArrayEquals(v.toFloatVector(), back.toFloatVector(), 1e-5f);
    }

    @Test
    void decodeNullOrBlankReturnsNull() {
        assertNull(MatrixKgEmbeddingGraphAdapter.decode(null));
        assertNull(MatrixKgEmbeddingGraphAdapter.decode(""));
    }
}
