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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.util.concurrent.Semaphore;

/**
 * App-main implementation of the heavy-memory serialization gate.
 *
 * <p>Backed by a {@link Semaphore#Semaphore(int) Semaphore(1)} when
 * {@code serializedHeavyOps=true} (default) so that at most one heavy in-memory
 * model operation (KGE training, batch embedding, etc.) runs at a time. When disabled
 * ({@code serializedHeavyOps=false}), {@link #acquire} is a no-op and returns immediately.</p>
 *
 * <p>When a caller has to WAIT for the permit, a {@link CrawlProgressEvent} of type
 * {@link CrawlProgressEvent.EventType#DECISION} is published so the crawl UI can surface the
 * serialization delay. The job id is read from {@link AgentCallContext#getJobId()} if not
 * supplied explicitly.</p>
 *
 * <p>Injected as {@code @Autowired(required = false)} in all callers so that contexts
 * without app-main (e.g. unit tests, knowledge-graph module alone) simply skip the gate.</p>
 */
@Service
public class HeavyMemoryCoordinatorImpl implements HeavyMemoryCoordinator {

    private static final Logger log = LoggerFactory.getLogger(HeavyMemoryCoordinatorImpl.class);

    /** Single-permit serialization semaphore. Non-fair for simplicity (FIFO ordering not required). */
    private final Semaphore semaphore = new Semaphore(1);

    @Autowired
    private ResourceSchedulerConfigService configService;

    @Autowired(required = false)
    private ApplicationEventPublisher eventPublisher;

    /** No-arg constructor for CGLIB proxy / GraalVM native image. */
    protected HeavyMemoryCoordinatorImpl() {
    }

    @Override
    public boolean isEnabled() {
        ResourceSchedulerConfig cfg = configService.getConfiguration();
        return cfg.isSerializedHeavyOps();
    }

    /**
     * Acquire the heavy-memory serialization permit.
     *
     * <p>If the gate is disabled, returns a no-op token immediately. Otherwise attempts
     * a non-blocking {@link Semaphore#tryAcquire()} first: if successful, returns without
     * publishing any event. If the permit is held by another op, publishes a DECISION event
     * ("waiting for gate"), then blocks until the permit is available.</p>
     *
     * @param opLabel short human-readable label (e.g. {@code "kge-training"})
     * @param jobId   crawl job id; if null, falls back to {@link AgentCallContext#getJobId()}
     * @return a try-with-resources token whose {@code close()} releases the permit
     * @throws InterruptedException if interrupted while waiting
     */
    @Override
    public AutoCloseable acquire(String opLabel, String jobId) throws InterruptedException {
        if (!isEnabled()) {
            return NO_OP_TOKEN;
        }

        // Resolve effective job id.
        String effectiveJobId = (jobId != null && !jobId.isBlank())
                ? jobId : AgentCallContext.getJobId();

        boolean acquired = semaphore.tryAcquire();
        if (!acquired) {
            // Another heavy op holds the permit — publish a DECISION event so the UI shows it.
            String waitMsg = "op=" + opLabel + " waiting for heavy-memory gate (another op is running)";
            log.info("[HeavyMemoryCoordinator] {}", waitMsg);
            publishDecision(effectiveJobId, waitMsg);

            // Block until the permit is available.
            semaphore.acquire();

            String gotMsg = "op=" + opLabel + " acquired heavy-memory gate";
            log.info("[HeavyMemoryCoordinator] {}", gotMsg);
            publishDecision(effectiveJobId, gotMsg);
        } else {
            log.debug("[HeavyMemoryCoordinator] op={} acquired heavy-memory gate (no wait)", opLabel);
        }

        // Return a token that releases the semaphore on close().
        return () -> {
            semaphore.release();
            log.debug("[HeavyMemoryCoordinator] op={} released heavy-memory gate", opLabel);
        };
    }

    // ── helpers ─────────────────────────────────────────────────────────────

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
