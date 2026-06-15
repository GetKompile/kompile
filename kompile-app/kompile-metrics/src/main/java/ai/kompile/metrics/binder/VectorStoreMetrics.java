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

import ai.kompile.core.embeddings.VectorStore;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PostConstruct;

import java.util.concurrent.TimeUnit;

/**
 * Exposes vector store metrics via Micrometer.
 *
 * Gauges:
 * <ul>
 *   <li>{@code kompile.vectorstore.document_count} – approximate number of indexed vectors</li>
 *   <li>{@code kompile.vectorstore.available} – 1 if the store is ready</li>
 *   <li>{@code kompile.vectorstore.fallback} – 1 if using a fallback index</li>
 * </ul>
 *
 * Counters & Timers (call {@link #recordSearch}, {@link #recordAdd}, {@link #recordDelete}
 * from instrumented code):
 * <ul>
 *   <li>{@code kompile.vectorstore.search.count} / {@code kompile.vectorstore.search.time}</li>
 *   <li>{@code kompile.vectorstore.add.count} / {@code kompile.vectorstore.add.time}</li>
 *   <li>{@code kompile.vectorstore.delete.count}</li>
 * </ul>
 */
public class VectorStoreMetrics {

    private final MeterRegistry registry;
    private final VectorStore vectorStore;

    private Counter searchCounter;
    private Counter searchHitsCounter;
    private Timer searchTimer;
    private Counter addCounter;
    private Counter addDocsCounter;
    private Timer addTimer;
    private Counter deleteCounter;

    public VectorStoreMetrics(MeterRegistry registry, VectorStore vectorStore) {
        this.registry = registry;
        this.vectorStore = vectorStore;
    }

    @PostConstruct
    public void bindMetrics() {
        Gauge.builder("kompile.vectorstore.document_count", vectorStore, vs -> {
            try { return vs.getApproxVectorCount(); } catch (Exception e) { return -1; }
        }).description("Approximate number of vectors/documents in the store").register(registry);

        Gauge.builder("kompile.vectorstore.available", vectorStore,
                vs -> vs.isVectorStoreAvailable() ? 1.0 : 0.0)
                .description("1 if the vector store is available and ready").register(registry);

        Gauge.builder("kompile.vectorstore.fallback", vectorStore,
                vs -> vs.isUsingFallbackIndex() ? 1.0 : 0.0)
                .description("1 if the vector store is using a temporary fallback index").register(registry);

        searchCounter = Counter.builder("kompile.vectorstore.search.count")
                .description("Total number of similarity searches performed")
                .register(registry);

        searchHitsCounter = Counter.builder("kompile.vectorstore.search.hits")
                .description("Total number of documents returned from searches")
                .register(registry);

        searchTimer = Timer.builder("kompile.vectorstore.search.time")
                .description("Time spent on similarity search operations")
                .register(registry);

        addCounter = Counter.builder("kompile.vectorstore.add.count")
                .description("Total number of add operations")
                .register(registry);

        addDocsCounter = Counter.builder("kompile.vectorstore.add.documents")
                .description("Total number of documents added to the store")
                .register(registry);

        addTimer = Timer.builder("kompile.vectorstore.add.time")
                .description("Time spent on add operations")
                .register(registry);

        deleteCounter = Counter.builder("kompile.vectorstore.delete.count")
                .description("Total number of delete operations")
                .register(registry);
    }

    /**
     * Record a completed search operation.
     *
     * @param durationMs duration in milliseconds
     * @param resultCount number of documents returned
     */
    public void recordSearch(long durationMs, int resultCount) {
        searchCounter.increment();
        searchHitsCounter.increment(resultCount);
        searchTimer.record(durationMs, TimeUnit.MILLISECONDS);
    }

    /**
     * Record a completed add operation.
     *
     * @param durationMs duration in milliseconds
     * @param documentCount number of documents added
     */
    public void recordAdd(long durationMs, int documentCount) {
        addCounter.increment();
        addDocsCounter.increment(documentCount);
        addTimer.record(durationMs, TimeUnit.MILLISECONDS);
    }

    /**
     * Record a delete operation.
     *
     * @param count number of documents deleted
     */
    public void recordDelete(int count) {
        deleteCounter.increment(count);
    }
}
