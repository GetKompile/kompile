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

package ai.kompile.app.services.cluster;

import ai.kompile.app.services.GpuToCpuMigrationService;
import ai.kompile.app.services.ResourceGovernor;
import ai.kompile.app.services.scheduler.ResourceSchedulerConfigService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Pushes the live cluster view to the worker-management UI in real time — the same broadcaster pattern as
 * {@code JobSchedulerBroadcaster} / {@code SystemResourceBroadcaster}, on a fixed cadence over STOMP.
 *
 * <p>Topic: {@code /topic/cluster/workers} — payload is {@code {workers: [WorkerCapabilities...],
 * workerCount, localSaturated, timestamp}}. Each {@link WorkerCapabilities} already carries the worker's
 * capabilities (backends, devices, supported job types) <em>and</em> live progress (active/max jobs, free
 * slots, CPU/GPU pressure), so one snapshot drives the whole UI. The UI cold-loads from
 * {@code GET /api/cluster/workers} then subscribes here for updates.</p>
 */
@Service
public class ClusterWorkerBroadcaster {

    private static final Logger log = LoggerFactory.getLogger(ClusterWorkerBroadcaster.class);

    private static final String TOPIC = "/topic/cluster/workers";
    private static final long INTERVAL_MS = 3_000L;

    @Autowired(required = false)
    private SimpMessagingTemplate messagingTemplate;

    @Autowired
    private CrawlWorkerRegistry registry;

    @Autowired(required = false)
    private ResourceGovernor governor;

    @Autowired
    private ResourceSchedulerConfigService configService;

    @Autowired(required = false)
    private GpuToCpuMigrationService migrationService;

    private ScheduledExecutorService poller;

    @PostConstruct
    public void start() {
        if (messagingTemplate == null) {
            log.info("ClusterWorkerBroadcaster: no WebSocket broker — cluster UI updates disabled");
            return;
        }
        poller = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "cluster-worker-broadcast");
            t.setDaemon(true);
            return t;
        });
        poller.scheduleWithFixedDelay(this::broadcastSafe, INTERVAL_MS, INTERVAL_MS, TimeUnit.MILLISECONDS);
        log.info("ClusterWorkerBroadcaster started (topic={}, interval={}ms)", TOPIC, INTERVAL_MS);
    }

    @PreDestroy
    public void stop() {
        if (poller != null) {
            poller.shutdownNow();
        }
    }

    private void broadcastSafe() {
        try {
            messagingTemplate.convertAndSend(TOPIC, snapshot());
        } catch (Throwable t) {
            log.debug("Cluster worker broadcast failed: {}", t.toString());
        }
    }

    /** The live cluster snapshot — also usable for REST cold-load. */
    public Map<String, Object> snapshot() {
        long now = System.currentTimeMillis();
        List<WorkerCapabilities> workers = registry.liveWorkers(now);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("workers", workers);
        payload.put("workerCount", workers.size());
        payload.put("workerTimeoutSeconds", configService.getConfiguration().getClusterWorkerTimeoutSeconds());
        if (governor != null) {
            payload.put("localSaturated", governor.isLocalSaturated());
        }
        if (migrationService != null) {
            payload.put("migration", migrationService.status());
        }
        payload.put("timestamp", Instant.now().toString());
        return payload;
    }
}
