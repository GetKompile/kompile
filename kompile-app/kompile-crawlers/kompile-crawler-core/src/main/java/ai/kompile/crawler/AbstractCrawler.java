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

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Base class for {@link Crawler} implementations. Provides:
 * <ul>
 *   <li>Configuration validation helpers</li>
 *   <li>Thread pool management for async crawl execution</li>
 *   <li>Common job ID generation</li>
 * </ul>
 *
 * Subclasses implement {@link #createJob(String, CrawlConfig, CrawlEventListener)}
 * to produce their specific {@link CrawlJob} implementation.
 */
public abstract class AbstractCrawler implements Crawler {

    private static final int CRAWLER_THREADS = Math.max(1,
            Integer.getInteger("kompile.crawler.maxThreads", 2));
    private static final int CRAWLER_QUEUE_CAPACITY = Math.max(1,
            Integer.getInteger("kompile.crawler.queueCapacity", 25));

    private final ExecutorService executor = new ThreadPoolExecutor(
            CRAWLER_THREADS,
            CRAWLER_THREADS,
            60L,
            TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(CRAWLER_QUEUE_CAPACITY),
            r -> {
        Thread t = new Thread(r);
        t.setDaemon(true);
        t.setName("crawler-" + getId() + "-" + t.getId());
        return t;
            },
            new ThreadPoolExecutor.AbortPolicy());

    @Override
    public CrawlJob start(CrawlConfig config, CrawlEventListener listener) {
        List<String> validationErrors = validate(config);
        if (!validationErrors.isEmpty()) {
            throw new IllegalArgumentException(
                    "Invalid crawl configuration: " + String.join("; ", validationErrors));
        }

        String jobId = UUID.randomUUID().toString();
        CrawlEventListener safeListener = listener != null ? listener : new CrawlEventListener() {};
        AbstractCrawlJob job = createJob(jobId, config, safeListener);

        try {
            executor.submit(() -> {
            job.crawlThread = Thread.currentThread();
            job.startedAt = java.time.Instant.now();
            job.status.set(CrawlStatus.RUNNING);
            try {
                executeCrawl(job);
                if (job.status.get() == CrawlStatus.RUNNING) {
                    job.completeCrawl(CrawlStatus.COMPLETED);
                } else if (job.status.get() == CrawlStatus.CANCELLED) {
                    job.completeCrawl(CrawlStatus.CANCELLED);
                }
            } catch (Exception e) {
                job.recordError(config.getSeed(), e);
                job.completeCrawl(CrawlStatus.FAILED);
            }
            });
        } catch (RejectedExecutionException e) {
            job.recordError(config.getSeed(), new IllegalStateException("Crawler queue is full"));
            job.completeCrawl(CrawlStatus.FAILED);
        }

        return job;
    }

    @Override
    public List<String> validate(CrawlConfig config) {
        List<String> errors = new ArrayList<>();
        if (config.getSeed() == null || config.getSeed().isBlank()) {
            errors.add("Seed URL/path is required");
        }
        if (config.getMaxDepth() < 0) {
            errors.add("maxDepth must be >= 0");
        }
        if (config.getMaxDocuments() < 0) {
            errors.add("maxDocuments must be >= 0");
        }
        errors.addAll(validateSpecific(config));
        return errors;
    }

    /**
     * Subclass hook for type-specific validation.
     */
    protected List<String> validateSpecific(CrawlConfig config) {
        return List.of();
    }

    /**
     * Creates the concrete CrawlJob for this crawler type.
     */
    protected abstract AbstractCrawlJob createJob(String jobId, CrawlConfig config, CrawlEventListener listener);

    /**
     * Runs the actual crawl logic. Called on the executor thread.
     * Implementations should periodically call {@code job.checkPauseAndContinue()}
     * and {@code job.shouldStop()}.
     */
    protected abstract void executeCrawl(AbstractCrawlJob job) throws Exception;

    @jakarta.annotation.PreDestroy
    public void shutdown() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
