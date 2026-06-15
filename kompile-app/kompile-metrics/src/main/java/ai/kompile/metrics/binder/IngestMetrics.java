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

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.Gauge;
import jakarta.annotation.PostConstruct;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Metrics for the document ingestion pipeline.
 *
 * Counters:
 * <ul>
 *   <li>{@code kompile.ingest.documents.total} – total documents ingested</li>
 *   <li>{@code kompile.ingest.documents.failed} – failed document ingestions</li>
 *   <li>{@code kompile.ingest.chunks.total} – total chunks created from documents</li>
 *   <li>{@code kompile.ingest.bytes.total} – total bytes processed</li>
 * </ul>
 *
 * Gauges:
 * <ul>
 *   <li>{@code kompile.ingest.jobs.active} – currently running ingest jobs</li>
 *   <li>{@code kompile.ingest.jobs.queued} – jobs waiting in the queue</li>
 * </ul>
 *
 * Timers:
 * <ul>
 *   <li>{@code kompile.ingest.document.time} – per-document processing time</li>
 *   <li>{@code kompile.ingest.job.time} – full job duration</li>
 * </ul>
 */
public class IngestMetrics {

    private final MeterRegistry registry;
    private final AtomicInteger activeJobs = new AtomicInteger(0);
    private final AtomicInteger queuedJobs = new AtomicInteger(0);
    private final AtomicLong lastJobDurationMs = new AtomicLong(0);

    private Counter documentsTotal;
    private Counter documentsFailed;
    private Counter chunksTotal;
    private Counter bytesTotal;
    private Timer documentTimer;
    private Timer jobTimer;

    public IngestMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    @PostConstruct
    public void bindMetrics() {
        documentsTotal = Counter.builder("kompile.ingest.documents.total")
                .description("Total documents ingested successfully").register(registry);

        documentsFailed = Counter.builder("kompile.ingest.documents.failed")
                .description("Total documents that failed during ingestion").register(registry);

        chunksTotal = Counter.builder("kompile.ingest.chunks.total")
                .description("Total chunks created from documents").register(registry);

        bytesTotal = Counter.builder("kompile.ingest.bytes.total")
                .description("Total bytes processed during ingestion").register(registry);

        documentTimer = Timer.builder("kompile.ingest.document.time")
                .description("Time to process a single document").register(registry);

        jobTimer = Timer.builder("kompile.ingest.job.time")
                .description("Total time for an ingest job").register(registry);

        Gauge.builder("kompile.ingest.jobs.active", activeJobs, AtomicInteger::get)
                .description("Number of currently running ingest jobs").register(registry);

        Gauge.builder("kompile.ingest.jobs.queued", queuedJobs, AtomicInteger::get)
                .description("Number of ingest jobs waiting in the queue").register(registry);

        Gauge.builder("kompile.ingest.last_job_duration_ms", lastJobDurationMs, AtomicLong::get)
                .description("Duration of the most recently completed ingest job (ms)").register(registry);
    }

    public void recordDocumentIngested(String format, long durationMs, int chunkCount, long byteCount) {
        documentsTotal.increment();
        chunksTotal.increment(chunkCount);
        bytesTotal.increment(byteCount);
        documentTimer.record(durationMs, TimeUnit.MILLISECONDS);
    }

    public void recordDocumentFailed(String format) {
        documentsFailed.increment();
    }

    public void recordJobStarted() {
        activeJobs.incrementAndGet();
    }

    public void recordJobCompleted(long durationMs) {
        activeJobs.decrementAndGet();
        lastJobDurationMs.set(durationMs);
        jobTimer.record(durationMs, TimeUnit.MILLISECONDS);
    }

    public void recordJobFailed(long durationMs) {
        activeJobs.decrementAndGet();
        documentsFailed.increment();
        lastJobDurationMs.set(durationMs);
    }

    public void setQueuedJobs(int count) {
        queuedJobs.set(count);
    }
}
