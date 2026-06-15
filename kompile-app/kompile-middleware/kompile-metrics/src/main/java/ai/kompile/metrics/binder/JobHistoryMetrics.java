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
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PostConstruct;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Metrics for indexing job history and ingest event tracking.
 *
 * Counters:
 * <ul>
 *   <li>{@code kompile.jobs.completed} – completed indexing jobs</li>
 *   <li>{@code kompile.jobs.failed} – failed indexing jobs</li>
 *   <li>{@code kompile.jobs.cancelled} – cancelled indexing jobs</li>
 *   <li>{@code kompile.jobs.memory_killed} – jobs killed due to OOM</li>
 *   <li>{@code kompile.jobs.restart_attempts} – restart attempts across all jobs</li>
 *   <li>{@code kompile.jobs.restart_recovered} – successful recoveries after restart</li>
 *   <li>{@code kompile.jobs.documents_indexed} – total documents indexed across all jobs</li>
 *   <li>{@code kompile.jobs.chunks_created} – total chunks created across all jobs</li>
 *   <li>{@code kompile.jobs.tokens_processed} – total tokens processed across all jobs</li>
 *   <li>{@code kompile.events.total} – total ingest events logged</li>
 *   <li>{@code kompile.events.errors} – total error events</li>
 * </ul>
 *
 * Gauges:
 * <ul>
 *   <li>{@code kompile.jobs.running} – currently running indexing jobs</li>
 *   <li>{@code kompile.jobs.last.loading_ms} – last job loading phase duration</li>
 *   <li>{@code kompile.jobs.last.chunking_ms} – last job chunking phase duration</li>
 *   <li>{@code kompile.jobs.last.embedding_ms} – last job embedding phase duration</li>
 *   <li>{@code kompile.jobs.last.indexing_ms} – last job indexing phase duration</li>
 * </ul>
 *
 * Timers:
 * <ul>
 *   <li>{@code kompile.jobs.loading.time} – loading phase time distribution</li>
 *   <li>{@code kompile.jobs.chunking.time} – chunking phase time distribution</li>
 *   <li>{@code kompile.jobs.embedding.time} – embedding phase time distribution</li>
 *   <li>{@code kompile.jobs.indexing.time} – indexing phase time distribution</li>
 * </ul>
 */
public class JobHistoryMetrics {

    private final MeterRegistry registry;
    private final AtomicLong runningJobs = new AtomicLong(0);
    private final AtomicLong lastLoadingMs = new AtomicLong(0);
    private final AtomicLong lastChunkingMs = new AtomicLong(0);
    private final AtomicLong lastEmbeddingMs = new AtomicLong(0);
    private final AtomicLong lastIndexingMs = new AtomicLong(0);

    private Counter jobsCompleted;
    private Counter jobsFailed;
    private Counter jobsCancelled;
    private Counter jobsMemoryKilled;
    private Counter restartAttempts;
    private Counter restartRecovered;
    private Counter documentsIndexed;
    private Counter chunksCreated;
    private Counter tokensProcessed;
    private Counter eventsTotal;
    private Counter eventsErrors;
    private Timer loadingTimer;
    private Timer chunkingTimer;
    private Timer embeddingTimer;
    private Timer indexingTimer;

    public JobHistoryMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    @PostConstruct
    public void bindMetrics() {
        jobsCompleted = Counter.builder("kompile.jobs.completed")
                .description("Completed indexing jobs").register(registry);
        jobsFailed = Counter.builder("kompile.jobs.failed")
                .description("Failed indexing jobs").register(registry);
        jobsCancelled = Counter.builder("kompile.jobs.cancelled")
                .description("Cancelled indexing jobs").register(registry);
        jobsMemoryKilled = Counter.builder("kompile.jobs.memory_killed")
                .description("Indexing jobs killed due to OOM").register(registry);
        restartAttempts = Counter.builder("kompile.jobs.restart_attempts")
                .description("Restart attempts across all jobs").register(registry);
        restartRecovered = Counter.builder("kompile.jobs.restart_recovered")
                .description("Successful recoveries after restart").register(registry);
        documentsIndexed = Counter.builder("kompile.jobs.documents_indexed")
                .description("Total documents indexed across all jobs").register(registry);
        chunksCreated = Counter.builder("kompile.jobs.chunks_created")
                .description("Total chunks created across all jobs").register(registry);
        tokensProcessed = Counter.builder("kompile.jobs.tokens_processed")
                .description("Total tokens processed across all jobs").register(registry);

        eventsTotal = Counter.builder("kompile.events.total")
                .description("Total ingest events logged").register(registry);
        eventsErrors = Counter.builder("kompile.events.errors")
                .description("Total error ingest events").register(registry);

        loadingTimer = Timer.builder("kompile.jobs.loading.time")
                .description("Document loading phase duration").register(registry);
        chunkingTimer = Timer.builder("kompile.jobs.chunking.time")
                .description("Chunking phase duration").register(registry);
        embeddingTimer = Timer.builder("kompile.jobs.embedding.time")
                .description("Embedding phase duration").register(registry);
        indexingTimer = Timer.builder("kompile.jobs.indexing.time")
                .description("Indexing phase duration").register(registry);

        Gauge.builder("kompile.jobs.running", runningJobs, AtomicLong::get)
                .description("Currently running indexing jobs").register(registry);
        Gauge.builder("kompile.jobs.last.loading_ms", lastLoadingMs, AtomicLong::get)
                .description("Last job loading phase duration (ms)").register(registry);
        Gauge.builder("kompile.jobs.last.chunking_ms", lastChunkingMs, AtomicLong::get)
                .description("Last job chunking phase duration (ms)").register(registry);
        Gauge.builder("kompile.jobs.last.embedding_ms", lastEmbeddingMs, AtomicLong::get)
                .description("Last job embedding phase duration (ms)").register(registry);
        Gauge.builder("kompile.jobs.last.indexing_ms", lastIndexingMs, AtomicLong::get)
                .description("Last job indexing phase duration (ms)").register(registry);
    }

    public void recordJobStarted() {
        runningJobs.incrementAndGet();
    }

    public void recordJobCompleted(int docsIndexed, int chunks, long tokens,
                                   long loadMs, long chunkMs, long embedMs, long indexMs) {
        runningJobs.decrementAndGet();
        jobsCompleted.increment();
        documentsIndexed.increment(docsIndexed);
        chunksCreated.increment(chunks);
        tokensProcessed.increment(tokens);
        lastLoadingMs.set(loadMs);
        lastChunkingMs.set(chunkMs);
        lastEmbeddingMs.set(embedMs);
        lastIndexingMs.set(indexMs);
        if (loadMs > 0) loadingTimer.record(loadMs, TimeUnit.MILLISECONDS);
        if (chunkMs > 0) chunkingTimer.record(chunkMs, TimeUnit.MILLISECONDS);
        if (embedMs > 0) embeddingTimer.record(embedMs, TimeUnit.MILLISECONDS);
        if (indexMs > 0) indexingTimer.record(indexMs, TimeUnit.MILLISECONDS);
    }

    public void recordJobFailed(String failureReason) {
        runningJobs.decrementAndGet();
        jobsFailed.increment();
    }

    public void recordJobCancelled() {
        runningJobs.decrementAndGet();
        jobsCancelled.increment();
    }

    public void recordJobMemoryKilled() {
        runningJobs.decrementAndGet();
        jobsMemoryKilled.increment();
    }

    public void recordRestartAttempt(boolean recovered) {
        restartAttempts.increment();
        if (recovered) restartRecovered.increment();
    }

    public void recordEvent(String eventType) {
        eventsTotal.increment();
    }

    public void recordErrorEvent() {
        eventsTotal.increment();
        eventsErrors.increment();
    }
}
