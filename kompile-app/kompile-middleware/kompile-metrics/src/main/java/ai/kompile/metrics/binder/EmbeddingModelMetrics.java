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

package ai.kompile.metrics.binder;

import ai.kompile.core.embeddings.EmbeddingModel;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import jakarta.annotation.PostConstruct;

/**
 * Exposes embedding model metrics via Micrometer.
 *
 * Gauges (polled from the live EmbeddingModel bean):
 * <ul>
 *   <li>{@code kompile.embedding.dimensions} – vector dimension</li>
 *   <li>{@code kompile.embedding.batch.size} – current batch chunk count</li>
 *   <li>{@code kompile.embedding.batch.max_seq_length} – padded sequence length</li>
 *   <li>{@code kompile.embedding.batch.total_tokens} – total tokens in batch</li>
 *   <li>{@code kompile.embedding.batch.tokenize_time_ms} – tokenization time</li>
 *   <li>{@code kompile.embedding.batch.forward_pass_time_ms} – model forward pass time</li>
 *   <li>{@code kompile.embedding.batch.total_time_ms} – total batch time</li>
 *   <li>{@code kompile.embedding.batch.tokens_per_second} – throughput</li>
 *   <li>{@code kompile.embedding.batch.chunks_per_second} – chunk throughput</li>
 *   <li>{@code kompile.embedding.initialized} – 1 if model is ready</li>
 *   <li>{@code kompile.embedding.loading} – 1 if model is loading</li>
 *   <li>{@code kompile.embedding.optimal_batch_size} – recommended batch size</li>
 * </ul>
 */
public class EmbeddingModelMetrics {

    private final MeterRegistry registry;
    private final EmbeddingModel embeddingModel;

    public EmbeddingModelMetrics(MeterRegistry registry, EmbeddingModel embeddingModel) {
        this.registry = registry;
        this.embeddingModel = embeddingModel;
    }

    @PostConstruct
    public void bindMetrics() {
        Tags tags = Tags.of("model", embeddingModel.getModelIdentifier());

        Gauge.builder("kompile.embedding.dimensions", embeddingModel, m -> {
            try { return m.dimensions(); } catch (Exception e) { return 0; }
        }).tags(tags).description("Embedding vector dimensionality").register(registry);

        Gauge.builder("kompile.embedding.initialized", embeddingModel,
                m -> m.isInitialized() ? 1.0 : 0.0)
                .tags(tags).description("1 if the embedding model is initialized and ready").register(registry);

        Gauge.builder("kompile.embedding.loading", embeddingModel,
                m -> m.isLoading() ? 1.0 : 0.0)
                .tags(tags).description("1 if the embedding model is currently loading").register(registry);

        Gauge.builder("kompile.embedding.optimal_batch_size", embeddingModel, EmbeddingModel::getOptimalBatchSize)
                .tags(tags).description("Recommended batch size for this model").register(registry);

        // Batch-level gauges (reflect the most recent batch)
        Gauge.builder("kompile.embedding.batch.size", embeddingModel,
                m -> m.getCurrentBatchInfo().numChunks())
                .tags(tags).description("Number of chunks in the current/last batch").register(registry);

        Gauge.builder("kompile.embedding.batch.max_seq_length", embeddingModel,
                m -> m.getCurrentBatchInfo().maxSeqLength())
                .tags(tags).description("Max sequence length after tokenization and padding").register(registry);

        Gauge.builder("kompile.embedding.batch.total_tokens", embeddingModel,
                m -> m.getCurrentBatchInfo().totalTokens())
                .tags(tags).description("Total tokens across all chunks in the batch").register(registry);

        Gauge.builder("kompile.embedding.batch.tokenize_time_ms", embeddingModel,
                m -> m.getCurrentBatchInfo().tokenizeTimeMs())
                .tags(tags).description("Time spent tokenizing the batch (ms)").register(registry);

        Gauge.builder("kompile.embedding.batch.forward_pass_time_ms", embeddingModel,
                m -> m.getCurrentBatchInfo().forwardPassTimeMs())
                .tags(tags).description("Time spent in model forward pass (ms)").register(registry);

        Gauge.builder("kompile.embedding.batch.total_time_ms", embeddingModel,
                m -> m.getCurrentBatchInfo().totalTimeMs())
                .tags(tags).description("Total time to process the batch (ms)").register(registry);

        Gauge.builder("kompile.embedding.batch.tokens_per_second", embeddingModel,
                m -> m.getCurrentBatchInfo().tokensPerSecond())
                .tags(tags).description("Embedding throughput in tokens/second").register(registry);

        Gauge.builder("kompile.embedding.batch.chunks_per_second", embeddingModel,
                m -> m.getCurrentBatchInfo().chunksPerSecond())
                .tags(tags).description("Embedding throughput in chunks/second").register(registry);
    }
}
