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

import ai.kompile.app.config.ResourceSchedulerConfig;
import ai.kompile.app.services.scheduler.ResourceSchedulerConfigService;
import ai.kompile.core.crawl.graph.AgentCallContext;
import ai.kompile.core.crawl.graph.CrawlProgressEvent;
import ai.kompile.core.crawl.graph.HeavyMemoryCoordinator;
import ai.kompile.core.crawl.graph.ResourceGovernorAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * App implementation of the heavy-memory admission gate.
 *
 * <p>Heavy in-memory model operations (KGE training, batch embedding, etc.) are admitted by
 * <b>budget</b>: several ops run concurrently as long as the sum of their declared native-memory
 * footprints stays under the host's heavy-op headroom
 * ({@link ResourceGovernorAdapter#availableMemoryMbForHeavyOps()} = {@code MemAvailable} − OOM floor)
 * × {@link ResourceSchedulerConfig#getHeavyMemoryBudgetSafetyFraction()}. This replaces the original
 * pre-subprocess-isolation binary mutex, which forbade e.g. embedding ∥ KGE training even when tens
 * of GB were free.</p>
 *
 * <p>Admission degrades safely to <b>one op at a time</b> (equivalent to the legacy single permit)
 * whenever the budget is 0 — that happens when budgeted admission is disabled
 * ({@code heavyMemoryBudgetedAdmission=false}), the governor is absent, or {@code MemAvailable} is
 * unreadable. A single op whose estimate exceeds the whole budget still runs (admitted when nothing
 * else holds budget), so the gate can never deadlock.</p>
 *
 * <p>The whole gate is disabled when {@code serializedHeavyOps=false}, in which case {@link #acquire}
 * is a no-op. When a caller has to WAIT for headroom a {@link CrawlProgressEvent} of type
 * {@link CrawlProgressEvent.EventType#DECISION} is published so the crawl UI can surface the delay.</p>
 *
 * <p>Injected as {@code @Autowired(required = false)} in all callers so that contexts without app
 * services (unit tests, knowledge-graph module alone) simply skip the gate.</p>
 */
@Service
public class HeavyMemoryCoordinatorImpl implements HeavyMemoryCoordinator {

    private static final Logger log = LoggerFactory.getLogger(HeavyMemoryCoordinatorImpl.class);

    /** Guards {@link #admittedMb}. Fair so waiting ops are served roughly in arrival order and a
     *  steady embedding stream can't starve queued crawl work. */
    private final ReentrantLock admissionLock = new ReentrantLock(true);
    private final Condition budgetFreed = admissionLock.newCondition();

    /** Sum of the declared footprints (MB) of the currently-admitted heavy ops. */
    private long admittedMb = 0;

    @Autowired
    private ResourceSchedulerConfigService configService;

    /** Live host-RAM headroom source. Absent in CPU-only/unit contexts → budget is treated as 0
     *  (serial admission), which is always safe. */
    @Autowired(required = false)
    private ResourceGovernorAdapter governor;

    @Autowired(required = false)
    private ApplicationEventPublisher eventPublisher;

    /** No-arg constructor for CGLIB proxy / GraalVM native image. */
    protected HeavyMemoryCoordinatorImpl() {
    }

    @Override
    public boolean isEnabled() {
        return configService.getConfiguration().isSerializedHeavyOps();
    }

    @Override
    public AutoCloseable acquire(String opLabel, String jobId) throws InterruptedException {
        // No explicit footprint — derive one from the op label / config.
        return acquire(opLabel, jobId, 0L);
    }

    @Override
    public AutoCloseable acquire(String opLabel, String jobId, long estimatedNativeMb)
            throws InterruptedException {
        ResourceSchedulerConfig cfg = configService.getConfiguration();
        if (!cfg.isSerializedHeavyOps()) {
            return NO_OP_TOKEN;
        }

        String effectiveJobId = (jobId != null && !jobId.isBlank())
                ? jobId : AgentCallContext.getJobId();
        long opMb = Math.max(1L, estimatedNativeMb > 0 ? estimatedNativeMb : estimateForLabel(opLabel, cfg));

        admissionLock.lockInterruptibly();
        try {
            boolean waited = false;
            // Admit immediately when nothing else holds budget (so a single op larger than the whole
            // budget can still run — never deadlock); otherwise wait until the running set leaves room.
            while (admittedMb > 0 && admittedMb + opMb > currentBudgetMb(cfg)) {
                if (!waited) {
                    String waitMsg = "op=" + opLabel + " (~" + opMb + " MB) waiting for heavy-memory headroom"
                            + " (admitted=" + admittedMb + " MB, budget=" + currentBudgetMb(cfg) + " MB)";
                    log.info("[HeavyMemoryCoordinator] {}", waitMsg);
                    publishDecision(effectiveJobId, waitMsg);
                    waited = true;
                }
                // Bounded wait so the budget is re-evaluated as memory frees externally, not only on
                // another op's release, and so live config changes are picked up.
                budgetFreed.await(2, TimeUnit.SECONDS);
                cfg = configService.getConfiguration();
            }
            admittedMb += opMb;
            if (waited) {
                String gotMsg = "op=" + opLabel + " admitted (~" + opMb + " MB, admitted=" + admittedMb + " MB)";
                log.info("[HeavyMemoryCoordinator] {}", gotMsg);
                publishDecision(effectiveJobId, gotMsg);
            } else {
                log.debug("[HeavyMemoryCoordinator] op={} admitted (~{} MB, admitted={} MB, no wait)",
                        opLabel, opMb, admittedMb);
            }
        } finally {
            admissionLock.unlock();
        }

        // Token releases exactly what it reserved and wakes any waiters.
        final long releaseMb = opMb;
        final String releaseLabel = opLabel;
        return () -> {
            admissionLock.lock();
            try {
                admittedMb = Math.max(0, admittedMb - releaseMb);
                budgetFreed.signalAll();
            } finally {
                admissionLock.unlock();
            }
            log.debug("[HeavyMemoryCoordinator] op={} released (~{} MB, admitted={} MB)",
                    releaseLabel, releaseMb, admittedMb);
        };
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    /**
     * Live admission budget in MB. Returns 0 — meaning "one op at a time" given the
     * {@code admittedMb > 0} guard — when budgeting is disabled or headroom is unknown.
     */
    private long currentBudgetMb(ResourceSchedulerConfig cfg) {
        if (!cfg.isHeavyMemoryBudgetedAdmission()) {
            return 0; // strict single-op-at-a-time (legacy mutex behaviour)
        }
        long headroom = governor != null ? governor.availableMemoryMbForHeavyOps() : -1;
        if (headroom <= 0) {
            return 0; // governor absent or MemAvailable unknown → serial (safe)
        }
        double frac = cfg.getHeavyMemoryBudgetSafetyFraction();
        if (frac <= 0) {
            return 0;
        }
        if (frac > 1.0) {
            frac = 1.0;
        }
        return (long) (headroom * frac);
    }

    /** Resolve a heavy op's estimated footprint from the per-label config map, else the default. */
    private long estimateForLabel(String opLabel, ResourceSchedulerConfig cfg) {
        Map<String, Long> estimates = cfg.getHeavyMemoryOpEstimatesMb();
        Long est = (estimates != null && opLabel != null) ? estimates.get(opLabel) : null;
        return est != null && est > 0 ? est : Math.max(1L, cfg.getHeavyMemoryDefaultOpMb());
    }

    private void publishDecision(String jobId, String message) {
        if (eventPublisher == null || jobId == null || jobId.isBlank()) {
            return;
        }
        try {
            eventPublisher.publishEvent(
                    new CrawlProgressEvent(this, jobId, null,
                            CrawlProgressEvent.EventType.DECISION, message));
        } catch (Exception e) {
            log.debug("[HeavyMemoryCoordinator] Failed to publish DECISION event: {}", e.getMessage());
        }
    }

    /** Stateless no-op token returned when the gate is disabled. */
    private static final AutoCloseable NO_OP_TOKEN = () -> {
    };
}
