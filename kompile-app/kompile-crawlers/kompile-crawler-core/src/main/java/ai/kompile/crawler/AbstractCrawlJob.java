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

package ai.kompile.crawler;

import ai.kompile.core.crawler.*;

import java.time.Duration;
import java.time.Instant;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Base implementation of {@link CrawlJob} with shared lifecycle management,
 * counters, and state tracking. Concrete crawlers extend this and implement
 * the actual crawl logic in a background thread.
 */
public abstract class AbstractCrawlJob implements CrawlJob {

    protected final String jobId;
    protected final CrawlConfig config;
    protected final CrawlEventListener listener;
    protected final CompletableFuture<CrawlSummary> completionFuture = new CompletableFuture<>();

    protected final AtomicReference<CrawlStatus> status = new AtomicReference<>(CrawlStatus.PENDING);
    protected final AtomicInteger discovered = new AtomicInteger(0);
    protected final AtomicInteger processed = new AtomicInteger(0);
    protected final AtomicInteger failed = new AtomicInteger(0);
    protected final AtomicInteger skipped = new AtomicInteger(0);
    protected final AtomicInteger currentDepth = new AtomicInteger(0);
    protected final AtomicReference<String> currentItem = new AtomicReference<>("");

    protected final List<Map.Entry<String, String>> errors = Collections.synchronizedList(new ArrayList<>());
    protected volatile Instant startedAt;
    protected volatile Thread crawlThread;

    // Pause/resume coordination
    protected final Object pauseLock = new Object();

    protected AbstractCrawlJob(String jobId, CrawlConfig config, CrawlEventListener listener) {
        this.jobId = jobId;
        this.config = config;
        this.listener = listener;
    }

    @Override
    public String getJobId() {
        return jobId;
    }

    @Override
    public CrawlStatus getStatus() {
        return status.get();
    }

    @Override
    public CrawlProgress getProgress() {
        int disc = discovered.get();
        int proc = processed.get();
        int fail = failed.get();
        int maxDocs = config.getMaxDocuments();
        int pct = maxDocs > 0 ? Math.min(100, (int) ((proc + fail) * 100L / maxDocs)) : -1;

        return new CrawlProgress(
                disc, proc, fail,
                disc - proc - fail - skipped.get(),
                currentDepth.get(),
                config.getMaxDepth(),
                currentItem.get(),
                pct,
                Instant.now()
        );
    }

    @Override
    public CrawlConfig getConfig() {
        return config;
    }

    @Override
    public void pause() {
        if (status.compareAndSet(CrawlStatus.RUNNING, CrawlStatus.PAUSED)) {
            listener.onProgress(getProgress());
        }
    }

    @Override
    public void resume() {
        if (status.compareAndSet(CrawlStatus.PAUSED, CrawlStatus.RUNNING)) {
            synchronized (pauseLock) {
                pauseLock.notifyAll();
            }
            listener.onProgress(getProgress());
        }
    }

    @Override
    public void cancel() {
        CrawlStatus prev = status.getAndSet(CrawlStatus.CANCELLED);
        if (prev == CrawlStatus.PAUSED) {
            synchronized (pauseLock) {
                pauseLock.notifyAll();
            }
        }
        if (crawlThread != null) {
            crawlThread.interrupt();
        }
    }

    @Override
    public CompletableFuture<CrawlSummary> getCompletionFuture() {
        return completionFuture;
    }

    // ---- Public accessors for use by crawler implementations ----

    public int incrementDiscovered() { return discovered.incrementAndGet(); }
    public int incrementProcessed() { return processed.incrementAndGet(); }
    public int incrementSkipped() { return skipped.incrementAndGet(); }
    public int getDiscoveredCount() { return discovered.get(); }
    public void setCurrentDepth(int depth) { currentDepth.set(depth); }
    public void setCurrentItem(String item) { currentItem.set(item); }
    public CrawlEventListener getListener() { return listener; }

    /**
     * Blocks if the job is paused. Call this periodically from the crawl loop.
     *
     * @return false if the crawl should stop (cancelled or interrupted)
     */
    public boolean checkPauseAndContinue() {
        while (status.get() == CrawlStatus.PAUSED) {
            synchronized (pauseLock) {
                try {
                    pauseLock.wait(1000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
        }
        return status.get() == CrawlStatus.RUNNING;
    }

    /**
     * Checks whether the crawl should stop (max docs reached, cancelled, timeout).
     */
    public boolean shouldStop() {
        if (status.get() != CrawlStatus.RUNNING) return true;
        if (Thread.currentThread().isInterrupted()) return true;
        int maxDocs = config.getMaxDocuments();
        if (maxDocs > 0 && discovered.get() >= maxDocs) return true;
        if (startedAt != null && config.getTimeout() != null) {
            return Duration.between(startedAt, Instant.now()).compareTo(config.getTimeout()) > 0;
        }
        return false;
    }

    /**
     * Records an error for a specific URL/path.
     */
    public void recordError(String url, Exception e) {
        failed.incrementAndGet();
        String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
        errors.add(new AbstractMap.SimpleImmutableEntry<>(url, msg));
        listener.onDocumentFailed(
                CrawlItem.builder().url(url).discoveredAt(Instant.now()).build(),
                e
        );
    }

    /**
     * Builds and returns the final summary, completes the future, and notifies the listener.
     */
    public CrawlSummary completeCrawl(CrawlStatus finalStatus) {
        status.set(finalStatus);
        Instant now = Instant.now();
        CrawlSummary summary = new CrawlSummary(
                finalStatus,
                discovered.get(),
                processed.get(),
                failed.get(),
                skipped.get(),
                currentDepth.get(),
                startedAt,
                now,
                startedAt != null ? Duration.between(startedAt, now) : Duration.ZERO,
                new ArrayList<>(errors),
                checkpoint()
        );
        listener.onComplete(summary);
        completionFuture.complete(summary);
        return summary;
    }
}
