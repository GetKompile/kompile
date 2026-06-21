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

import ai.kompile.core.kgembedding.KGEmbeddingModel;
import ai.kompile.core.kgembedding.Triple;
import ai.kompile.knowledgegraph.embedding.service.KGEmbeddingStorageService;
import ai.kompile.knowledgegraph.embedding.service.KGEmbeddingStorageService.EmbeddingStats;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link JpaKgEmbeddingGraphAdapter} — the relational (JPA) KG-embedding adapter.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class JpaKgEmbeddingGraphAdapterTest {

    @Test
    void priorityAndStoreType() {
        JpaKgEmbeddingGraphAdapter adapter = new JpaKgEmbeddingGraphAdapter(mock(KGEmbeddingStorageService.class));
        assertEquals(0, adapter.priority(), "JPA is the lowest-priority (fallback) adapter");
        assertEquals("jpa", adapter.storeType());
    }

    @Test
    void hasGraphDataReflectsEdgeCount() {
        KGEmbeddingStorageService storage = mock(KGEmbeddingStorageService.class);
        JpaKgEmbeddingGraphAdapter adapter = new JpaKgEmbeddingGraphAdapter(storage);

        when(storage.getStats(1L)).thenReturn(new EmbeddingStats(10, 0, 5, 0, null));
        when(storage.getStats(2L)).thenReturn(new EmbeddingStats(0, 0, 0, 0, null));

        assertTrue(adapter.hasGraphData(1L), "fact sheet with edges has graph data");
        assertFalse(adapter.hasGraphData(2L), "fact sheet with no edges has no graph data");
    }

    @Test
    void delegatesExtractAndStoreToStorageService() {
        KGEmbeddingStorageService storage = mock(KGEmbeddingStorageService.class);
        JpaKgEmbeddingGraphAdapter adapter = new JpaKgEmbeddingGraphAdapter(storage);

        List<Triple> triples = List.of(new Triple("a", "RELATED_TO", "b"));
        when(storage.extractTriples(1L)).thenReturn(triples);
        assertSame(triples, adapter.extractTriples(1L));

        KGEmbeddingModel model = mock(KGEmbeddingModel.class);
        when(storage.storeEmbeddings(model, 1L, 7L)).thenReturn(3);
        assertEquals(3, adapter.storeEmbeddings(model, 1L, 7L));
        verify(storage).storeEmbeddings(model, 1L, 7L);
    }
}
