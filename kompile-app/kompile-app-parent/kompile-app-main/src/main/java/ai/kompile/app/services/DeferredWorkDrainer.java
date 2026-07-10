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
import ai.kompile.core.crawl.graph.DeferredWorkSource;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Single governor-driven loop that drains every registered {@link DeferredWorkSource} when the
 * resource it needs frees up.
 *
 * <p>This is the systemic form of "redistribute work when capacity is available": instead of each
 * subsystem running its own resume poller, it registers a {@link DeferredWorkSource} bean and this
 * drainer polls them all on one schedule, gating each on its own {@link DeferredWorkSource#hasCapacity()}.
 * Deferred embeddings are the first source; archived crawl steps and OOM-floor-skipped KGE runs can
 * join by simply implementing the interface.</p>
 *
 * <p>The poll period reuses {@code governorDeferredEmbeddingResumeMs} (floored at 10 s) so existing
 * tuning carries over.</p>
 */
@Service
public class DeferredWorkDrainer {

    private static final Logger log = LoggerFactory.getLogger(DeferredWorkDrainer.class);
    private static final long MIN_PERIOD_MS = 10_000L;

    /** All deferred-work sources on the classpath; empty when none are registered. */
    @Autowired(required = false)
    List<DeferredWorkSource> sources = List.of();

    @Autowired
    private ResourceSchedulerConfigService configService;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private ScheduledExecutorService scheduler;

    @PostConstruct
    public void start() {
        if (sources == null || sources.isEmpty()) {
            log.info("DeferredWorkDrainer disabled — no deferred-work sources registered");
            return;
        }
        long periodMs = Math.max(MIN_PERIOD_MS,
                configService.getConfiguration().getGovernorDeferredEmbeddingResumeMs());
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "deferred-work-drainer");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleWithFixedDelay(this::tick, periodMs, periodMs, TimeUnit.MILLISECONDS);
        log.info("DeferredWorkDrainer started (period={}ms, sources={})",
                periodMs, sources.stream().map(DeferredWorkSource::name).toList());
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
            drainAll();
        } catch (Exception e) {
            log.debug("Deferred-work drain tick failed: {}", e.getMessage());
        } finally {
            running.set(false);
        }
    }

    /** Poll every source and drain the ones with capacity. Returns the total items drained. */
    int drainAll() {
        int total = 0;
        for (DeferredWorkSource source : sources) {
            try {
                if (!source.hasCapacity()) {
                    continue; // resource still constrained — retry next tick
                }
                int drained = source.drainAvailable();
                if (drained > 0) {
                    total += drained;
                    log.info("Drained {} deferred item(s) from source '{}'", drained, source.name());
                }
            } catch (Exception e) {
                log.warn("Deferred-work source '{}' drain failed: {}", source.name(), e.getMessage());
            }
        }
        return total;
    }
}
