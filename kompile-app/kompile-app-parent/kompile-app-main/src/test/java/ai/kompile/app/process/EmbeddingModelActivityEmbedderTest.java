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
package ai.kompile.app.process;

import ai.kompile.core.embeddings.EmbeddingModel;
import ai.kompile.process.discovery.mining.ActivityEmbedder;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class EmbeddingModelActivityEmbedderTest {

    @Test
    void batchesContextEnrichedActivityTextAndPreservesLabelKeys() {
        EmbeddingModel model = mock(EmbeddingModel.class);
        when(model.canEmbed()).thenReturn(true);
        when(model.getOptimalBatchSize()).thenReturn(1);
        when(model.getMaxBatchSize()).thenReturn(2);
        when(model.getModelIdentifier()).thenReturn("test-embedding-model");

        List<String> embeddedTexts = new ArrayList<>();
        when(model.embedBatch(anyList())).thenAnswer(invocation -> {
            List<String> texts = invocation.getArgument(0);
            embeddedTexts.addAll(texts);
            List<float[]> vectors = new ArrayList<>();
            for (String text : texts) {
                vectors.add(text.startsWith("Approve")
                        ? new float[]{1.0f, 0.0f, 0.5f}
                        : new float[]{0.0f, 1.0f, 0.5f});
            }
            return vectors;
        });

        EmbeddingModelActivityEmbedder embedder = new EmbeddingModelActivityEmbedder(model);
        Map<String, double[]> result = embedder.embedContexts(List.of(
                new ActivityEmbedder.ActivityContext("Approve", List.of(
                        "relationType=APPROVED_BY; sourceType=FORECAST")),
                new ActivityEmbedder.ActivityContext("Publish", List.of(
                        "relationType=PUBLISHES; targetType=REPORT"))));

        assertEquals(2, result.size());
        assertArrayEquals(new double[]{1.0, 0.0, 0.5}, result.get("Approve"), 1.0e-9);
        assertArrayEquals(new double[]{0.0, 1.0, 0.5}, result.get("Publish"), 1.0e-9);
        assertEquals("TEXT_EMBEDDING_MODEL", embedder.embeddingSource());
        assertEquals("test-embedding-model", embedder.embeddingModel());
        assertEquals(2, embeddedTexts.size());
        assertTrue(embeddedTexts.get(0).contains("APPROVED_BY"));
        assertTrue(embeddedTexts.get(1).contains("PUBLISHES"));
    }

    @Test
    void noModelDegradesToNoEmbeddings() {
        EmbeddingModelActivityEmbedder embedder = new EmbeddingModelActivityEmbedder((EmbeddingModel) null);
        assertTrue(embedder.embed(List.of("Approve", "Publish")).isEmpty());
    }
}
