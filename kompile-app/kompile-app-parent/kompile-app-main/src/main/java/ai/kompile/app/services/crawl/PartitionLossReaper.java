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

package ai.kompile.app.services.crawl;

import ai.kompile.app.config.ResourceSchedulerConfig;
import ai.kompile.app.services.cluster.CrawlWorkerRegistry;
import ai.kompile.app.services.scheduler.ResourceSchedulerConfigService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Detects lost workers in active distributed crawl sessions and (optionally) re-dispatches their partitions.
 * A RUNNING partition is "lost" when its worker is heartbeat-evicted from the {@link CrawlWorkerRegistry} or
 * has reported no progress for longer than the configured timeout. Opt-in via {@code clusterReassignOnLoss}
 * (default off). Mirrors the {@code ClusterWorkerBroadcaster} scheduled-daemon pattern; a pure consumer of
 * the coordinator's public API (no coordinator-internal coupling beyond {@code reassignWorkerPartition}).
 */
@Service
@ConditionalOnBean(DistributedCrawlCoordinator.class)
public class PartitionLossReaper {

    private static final Logger log = LoggerFactory.getLogger(PartitionLossReaper.class);

    private final DistributedCrawlCoordinator coordinator;
    private final ResourceSchedulerConfigService configService;

    /** Optional: present when this node is a cluster orchestrator. */
    @Autowired(required = false)
    private CrawlWorkerRegistry registry;

    private ScheduledExecutorService scheduler;

    public PartitionLossReaper(DistributedCrawlCoordinator coordinator,
                               ResourceSchedulerConfigService configService) {
        this.coordinator = coordinator;
        this.configService = configService;
    }

    @PostConstruct
    void start() {
        ResourceSchedulerConfig cfg = configService != null ? configService.getConfiguration() : null;
        if (cfg == null || !cfg.isClusterOrchestrator()) {
            return; // only an orchestrator owns distributed sessions
        }
        int intervalSec = Math.max(5, cfg.getClusterPartitionReaperIntervalSeconds());
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "partition-loss-reaper");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleWithFixedDelay(this::scanSafe, intervalSec, intervalSec, TimeUnit.SECONDS);
        log.info("PartitionLossReaper started (interval={}s)", intervalSec);
    }

    @PreDestroy
    void stop() {
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
    }

    private void scanSafe() {
        try {
            scan();
        } catch (Exception e) {
            log.debug("Partition-loss scan failed: {}", e.getMessage());
        }
    }

    /** One reaper pass; package-private for tests. No-op unless {@code clusterReassignOnLoss} is enabled. */
    void scan() {
        ResourceSchedulerConfig cfg = configService != null ? configService.getConfiguration() : null;
        if (cfg == null || !cfg.isClusterReassignOnLoss()) {
            return;
        }
        long now = System.currentTimeMillis();
        long timeoutMs = Math.max(1, cfg.getClusterPartitionProgressTimeoutSeconds()) * 1000L;
        int maxReassign = Math.max(0, cfg.getClusterMaxReassignments());

        for (DistributedCrawlSession session : coordinator.getAllSessions()) {
            if (session.getStatus() != DistributedCrawlSession.Status.RUNNING) {
                continue;
            }
            for (DistributedCrawlSession.WorkerInfo w : session.getWorkers().values()) {
                if (w.getStatus() != DistributedCrawlSession.WorkerStatus.RUNNING) {
                    continue;
                }
                String reason = lossReason(w, now, timeoutMs);
                if (reason == null) {
                    continue;
                }
                if (w.getReassignmentCount() >= maxReassign) {
                    log.warn("Partition {} lost ({}) — max reassignments ({}) reached, failing",
                            w.getWorkerId(), reason, maxReassign);
                    session.workerFailed(w.getWorkerId(), reason + " (max reassignments reached)");
                } else {
                    log.warn("Partition {} lost ({}) — reassigning", w.getWorkerId(), reason);
                    coordinator.reassignWorkerPartition(session, w);
                }
            }
            finalizeIfDone(session);
        }
    }

    /** Null when the worker still looks alive; otherwise a human-readable loss reason. */
    private String lossReason(DistributedCrawlSession.WorkerInfo w, long now, long timeoutMs) {
        String ref = w.getExternalRef();
        if (registry != null && ref != null) {
            boolean live = registry.liveWorkers(now).stream()
                    .anyMatch(lw -> ref.contains(lw.baseUrl()));
            if (!live) {
                return "worker evicted (no heartbeat)";
            }
        }
        Instant last = w.getLastProgressAt();
        if (last != null && now - last.toEpochMilli() > timeoutMs) {
            return "no progress for " + ((now - last.toEpochMilli()) / 1000) + "s";
        }
        return null;
    }

    private void finalizeIfDone(DistributedCrawlSession session) {
        if (session.isAllWorkersFinished()
                && session.getStatus() == DistributedCrawlSession.Status.RUNNING) {
            session.setStatus(session.getFailedWorkers().get() > 0
                    ? DistributedCrawlSession.Status.PARTIALLY_COMPLETED
                    : DistributedCrawlSession.Status.COMPLETED);
            session.setCompletedAt(Instant.now());
        }
    }
}
