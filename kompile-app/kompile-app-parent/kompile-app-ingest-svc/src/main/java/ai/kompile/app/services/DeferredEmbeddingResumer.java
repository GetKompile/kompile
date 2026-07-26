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

import ai.kompile.core.crawl.graph.DeferredWorkSource;
import ai.kompile.core.crawl.graph.UnifiedCrawlJob;
import ai.kompile.core.crawl.graph.UnifiedCrawlService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * {@link DeferredWorkSource} that drains crawl jobs left in {@code COMPLETED_PENDING_EMBEDDING},
 * completing their deferred embedding once the embedding model and GPU capacity are available.
 *
 * <p>Closes the gap where deferred chunks were stranded forever: a heavy local workload (embedding)
 * is deferred under GPU pressure (or when the model isn't ready) and this source re-runs it later,
 * flipping the job to {@code COMPLETED}. It consults the {@link ResourceGovernor} for GPU headroom so
 * it never competes with an active crawl for VRAM, and relies on the in-pipeline memory backpressure
 * inside {@code indexDocuments()} as a second guard.</p>
 *
 * <p>The polling schedule is owned by the shared {@link DeferredWorkDrainer}; this class only
 * declares its capacity gate ({@link #hasCapacity()}) and drain action ({@link #drainAvailable()}).</p>
 */
@Service
public class DeferredEmbeddingResumer implements DeferredWorkSource {

    private static final Logger log = LoggerFactory.getLogger(DeferredEmbeddingResumer.class);

    @Autowired(required = false)
    private UnifiedCrawlService unifiedCrawlService;

    @Autowired(required = false)
    private ResourceGovernor governor;

    @Override
    public String name() {
        return "embedding";
    }

    @Override
    public boolean hasCapacity() {
        // Need the crawl service to resume, and GPU headroom so we don't starve an active crawl of VRAM.
        return unifiedCrawlService != null
                && (governor == null || governor.hasGpuHeadroom("EMBEDDING"));
    }

    @Override
    public int drainAvailable() {
        return resumeEligibleJobs();
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
