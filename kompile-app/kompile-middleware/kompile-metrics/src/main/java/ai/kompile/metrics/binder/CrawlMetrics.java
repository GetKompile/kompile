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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Metrics for crawl operations (web crawl, file crawl, unified crawl-graph).
 *
 * Counters:
 * <ul>
 *   <li>{@code kompile.crawl.jobs.total} – total crawl jobs started</li>
 *   <li>{@code kompile.crawl.jobs.completed} – successfully completed crawl jobs</li>
 *   <li>{@code kompile.crawl.jobs.failed} – failed crawl jobs</li>
 *   <li>{@code kompile.crawl.jobs.cancelled} – cancelled crawl jobs</li>
 *   <li>{@code kompile.crawl.urls.discovered} – total URLs/items discovered</li>
 *   <li>{@code kompile.crawl.urls.processed} – total URLs/items processed</li>
 *   <li>{@code kompile.crawl.urls.failed} – total URLs/items that failed</li>
 *   <li>{@code kompile.crawl.urls.skipped} – total URLs/items skipped (filtered/duplicate)</li>
 * </ul>
 *
 * Gauges:
 * <ul>
 *   <li>{@code kompile.crawl.jobs.active} – currently running crawl jobs</li>
 *   <li>{@code kompile.crawl.current.depth} – current crawl depth of the active job</li>
 *   <li>{@code kompile.crawl.last_duration_ms} – duration of the last completed crawl</li>
 * </ul>
 *
 * Timers:
 * <ul>
 *   <li>{@code kompile.crawl.job.time} – crawl job duration distribution</li>
 *   <li>{@code kompile.crawl.url.time} – per-URL processing time</li>
 * </ul>
 */
public class CrawlMetrics {

    private final MeterRegistry registry;
    private final AtomicInteger activeJobs = new AtomicInteger(0);
    private final AtomicInteger currentDepth = new AtomicInteger(0);
    private final AtomicLong lastDurationMs = new AtomicLong(0);

    private Counter jobsTotal;
    private Counter jobsCompleted;
    private Counter jobsFailed;
    private Counter jobsCancelled;
    private Counter urlsDiscovered;
    private Counter urlsProcessed;
    private Counter urlsFailed;
    private Counter urlsSkipped;
    private Timer jobTimer;
    private Timer urlTimer;

    public CrawlMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    @PostConstruct
    public void bindMetrics() {
        jobsTotal = Counter.builder("kompile.crawl.jobs.total")
                .description("Total crawl jobs started").register(registry);
        jobsCompleted = Counter.builder("kompile.crawl.jobs.completed")
                .description("Successfully completed crawl jobs").register(registry);
        jobsFailed = Counter.builder("kompile.crawl.jobs.failed")
                .description("Failed crawl jobs").register(registry);
        jobsCancelled = Counter.builder("kompile.crawl.jobs.cancelled")
                .description("Cancelled crawl jobs").register(registry);

        urlsDiscovered = Counter.builder("kompile.crawl.urls.discovered")
                .description("Total URLs/items discovered across all crawls").register(registry);
        urlsProcessed = Counter.builder("kompile.crawl.urls.processed")
                .description("Total URLs/items successfully processed").register(registry);
        urlsFailed = Counter.builder("kompile.crawl.urls.failed")
                .description("Total URLs/items that failed to process").register(registry);
        urlsSkipped = Counter.builder("kompile.crawl.urls.skipped")
                .description("Total URLs/items skipped (filtered, duplicate, unchanged)").register(registry);

        jobTimer = Timer.builder("kompile.crawl.job.time")
                .description("Crawl job duration").register(registry);
        urlTimer = Timer.builder("kompile.crawl.url.time")
                .description("Per-URL processing time").register(registry);

        Gauge.builder("kompile.crawl.jobs.active", activeJobs, AtomicInteger::get)
                .description("Currently running crawl jobs").register(registry);
        Gauge.builder("kompile.crawl.current.depth", currentDepth, AtomicInteger::get)
                .description("Current crawl depth of the active job").register(registry);
        Gauge.builder("kompile.crawl.last_duration_ms", lastDurationMs, AtomicLong::get)
                .description("Duration of the last completed crawl (ms)").register(registry);
    }

    public void recordCrawlStarted(String crawlerId) {
        jobsTotal.increment();
        activeJobs.incrementAndGet();
    }

    public void recordCrawlCompleted(String crawlerId, long durationMs,
                                     int discovered, int processed, int failed, int skipped) {
        jobsCompleted.increment();
        activeJobs.decrementAndGet();
        lastDurationMs.set(durationMs);
        jobTimer.record(durationMs, TimeUnit.MILLISECONDS);
        urlsDiscovered.increment(discovered);
        urlsProcessed.increment(processed);
        urlsFailed.increment(failed);
        urlsSkipped.increment(skipped);
    }

    public void recordCrawlFailed(String crawlerId, long durationMs,
                                  int discovered, int processed, int failed) {
        jobsFailed.increment();
        activeJobs.decrementAndGet();
        lastDurationMs.set(durationMs);
        urlsDiscovered.increment(discovered);
        urlsProcessed.increment(processed);
        urlsFailed.increment(failed);
    }

    public void recordCrawlCancelled(String crawlerId) {
        jobsCancelled.increment();
        activeJobs.decrementAndGet();
    }

    public void recordUrlProcessed(long durationMs) {
        urlTimer.record(durationMs, TimeUnit.MILLISECONDS);
    }

    public void updateDepth(int depth) {
        currentDepth.set(depth);
    }
}
