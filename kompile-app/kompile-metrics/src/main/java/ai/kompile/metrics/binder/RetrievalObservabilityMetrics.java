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

import ai.kompile.core.rag.retrieval.RetrievalMetrics;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PostConstruct;

import java.util.concurrent.TimeUnit;

/**
 * Exports retrieval pipeline metrics (semantic search, keyword search, deduplication)
 * to Micrometer.
 *
 * Counters:
 * <ul>
 *   <li>{@code kompile.retrieval.count} – total retrieval operations</li>
 *   <li>{@code kompile.retrieval.semantic.hits} – documents from semantic search</li>
 *   <li>{@code kompile.retrieval.keyword.hits} – documents from keyword search</li>
 *   <li>{@code kompile.retrieval.duplicates_removed} – deduplication removals</li>
 * </ul>
 *
 * Timers:
 * <ul>
 *   <li>{@code kompile.retrieval.embedding.time} – query embedding time</li>
 *   <li>{@code kompile.retrieval.semantic.time} – semantic search time</li>
 *   <li>{@code kompile.retrieval.keyword.time} – keyword search time</li>
 *   <li>{@code kompile.retrieval.total.time} – end-to-end retrieval time</li>
 * </ul>
 */
public class RetrievalObservabilityMetrics {

    private final MeterRegistry registry;

    private Counter retrievalCounter;
    private Counter semanticHitsCounter;
    private Counter keywordHitsCounter;
    private Counter duplicatesRemovedCounter;
    private Timer embeddingTimer;
    private Timer semanticSearchTimer;
    private Timer keywordSearchTimer;
    private Timer totalRetrievalTimer;

    public RetrievalObservabilityMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    @PostConstruct
    public void bindMetrics() {
        retrievalCounter = Counter.builder("kompile.retrieval.count")
                .description("Total retrieval operations").register(registry);

        semanticHitsCounter = Counter.builder("kompile.retrieval.semantic.hits")
                .description("Total documents returned from semantic (vector) search").register(registry);

        keywordHitsCounter = Counter.builder("kompile.retrieval.keyword.hits")
                .description("Total documents returned from keyword (BM25) search").register(registry);

        duplicatesRemovedCounter = Counter.builder("kompile.retrieval.duplicates_removed")
                .description("Total duplicates removed during deduplication").register(registry);

        embeddingTimer = Timer.builder("kompile.retrieval.embedding.time")
                .description("Time spent generating query embeddings").register(registry);

        semanticSearchTimer = Timer.builder("kompile.retrieval.semantic.time")
                .description("Time spent on semantic similarity search").register(registry);

        keywordSearchTimer = Timer.builder("kompile.retrieval.keyword.time")
                .description("Time spent on keyword search").register(registry);

        totalRetrievalTimer = Timer.builder("kompile.retrieval.total.time")
                .description("End-to-end retrieval time").register(registry);
    }

    /**
     * Record a completed retrieval operation using the existing RetrievalMetrics record.
     *
     * @param metrics the retrieval metrics from a completed operation
     */
    public void recordRetrieval(RetrievalMetrics metrics) {
        retrievalCounter.increment();
        semanticHitsCounter.increment(metrics.semanticHits());
        keywordHitsCounter.increment(metrics.keywordHits());
        duplicatesRemovedCounter.increment(metrics.duplicatesRemoved());
        embeddingTimer.record(metrics.embeddingTimeNanos(), TimeUnit.NANOSECONDS);
        semanticSearchTimer.record(metrics.semanticSearchTimeNanos(), TimeUnit.NANOSECONDS);
        keywordSearchTimer.record(metrics.keywordSearchTimeNanos(), TimeUnit.NANOSECONDS);
        totalRetrievalTimer.record(metrics.totalTimeNanos(), TimeUnit.NANOSECONDS);
    }
}
