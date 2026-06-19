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

package ai.kompile.app.services;

import ai.kompile.app.services.scheduler.ResourceSchedulerConfigService;
import ai.kompile.core.crawl.graph.UnifiedCrawlJob;
import ai.kompile.core.crawl.graph.UnifiedCrawlService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Drains crawl jobs left in {@code COMPLETED_PENDING_EMBEDDING}, completing their deferred embedding
 * once the embedding model and GPU capacity are available.
 *
 * <p>Closes the gap where deferred chunks were stranded forever: a heavy local workload (embedding)
 * is deferred under GPU pressure (or when the model isn't ready) and this resumer re-runs it later,
 * flipping the job to {@code COMPLETED}. It consults the {@link ResourceGovernor} for GPU headroom so
 * it never competes with an active crawl for VRAM, and relies on the in-pipeline memory backpressure
 * inside {@code indexDocuments()} as a second guard.</p>
 */
@Service
public class DeferredEmbeddingResumer {

    private static final Logger log = LoggerFactory.getLogger(DeferredEmbeddingResumer.class);
    private static final long MIN_PERIOD_MS = 10_000L;

    @Autowired(required = false)
    private UnifiedCrawlService unifiedCrawlService;

    @Autowired(required = false)
    private ResourceGovernor governor;

    @Autowired
    private ResourceSchedulerConfigService configService;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private ScheduledExecutorService scheduler;

    @PostConstruct
    public void start() {
        if (unifiedCrawlService == null) {
            log.info("DeferredEmbeddingResumer disabled — no UnifiedCrawlService present");
            return;
        }
        long periodMs = Math.max(MIN_PERIOD_MS,
                configService.getConfiguration().getGovernorDeferredEmbeddingResumeMs());
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "deferred-embedding-resumer");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleWithFixedDelay(this::tick, periodMs, periodMs, TimeUnit.MILLISECONDS);
        log.info("DeferredEmbeddingResumer started (period={}ms)", periodMs);
    }

    @PreDestroy
    public void stop() {
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
    }

    private void tick() {
        if (!running.compareAndSet(false, true)) {
            return; // a prior tick is still running (also impossible with fixed-delay, but be safe)
        }
        try {
            resumeEligibleJobs();
        } catch (Exception e) {
            log.debug("Deferred embedding resume tick failed: {}", e.getMessage());
        } finally {
            running.set(false);
        }
    }

    /** Scan all jobs and resume eligible deferred embeddings. Returns the number of jobs resumed. */
    int resumeEligibleJobs() {
        if (unifiedCrawlService == null) {
            return 0;
        }
        if (governor != null && !governor.hasGpuHeadroom("EMBEDDING")) {
            return 0; // GPU too pressured to embed right now — try again next tick
        }
        int resumed = 0;
        for (UnifiedCrawlJob job : unifiedCrawlService.getAllJobs()) {
            if (job.getStatus().get() != UnifiedCrawlJob.Status.COMPLETED_PENDING_EMBEDDING) {
                continue;
            }
            if (job.getDeferredEmbeddingChunks().isEmpty()) {
                continue;
            }
            // Re-check headroom before each job — the previous resume may have consumed VRAM.
            if (governor != null && !governor.hasGpuHeadroom("EMBEDDING")) {
                break;
            }
            try {
                int chunks = unifiedCrawlService.resumeDeferredEmbedding(job.getJobId());
                if (chunks > 0) {
                    resumed++;
                    log.info("Resumed deferred embedding for job {} ({} chunk(s))", job.getJobId(), chunks);
                }
            } catch (Exception e) {
                log.warn("Deferred embedding resume failed for job {}: {}", job.getJobId(), e.getMessage());
            }
        }
        return resumed;
    }
}
