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

import ai.kompile.app.config.ResourceSchedulerConfig;
import ai.kompile.app.services.ResourceSnapshot;
import ai.kompile.app.services.ResourceTelemetryService;
import ai.kompile.app.services.scheduler.ResourceSchedulerConfigService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The worker side of the cluster: builds this node's live {@link WorkerCapabilities} from telemetry + config,
 * and — when this node's role includes {@code worker} — heartbeats them to the orchestrator over HTTP so the
 * cluster always knows what this node can do and how loaded it is. Standalone nodes ({@code clusterRole=none})
 * incur zero overhead (the heartbeat never starts).
 */
@Service
public class CrawlWorkerCapabilityService {

    private static final Logger log = LoggerFactory.getLogger(CrawlWorkerCapabilityService.class);

    @Autowired
    private ResourceTelemetryService telemetry;

    @Autowired
    private ResourceSchedulerConfigService configService;

    @Autowired
    private ObjectMapper objectMapper;

    /** Optional: lets the advertiser restrict supported job types to those with a registered runner. */
    @Autowired(required = false)
    private ClusterJobRunnerRegistry runnerRegistry;

    @Value("${server.port:8080}")
    private int serverPort;

    /** Delegated jobs currently running on this node — Phase 2's worker executor adjusts this; feeds capacity. */
    private final AtomicInteger activeDelegatedJobs = new AtomicInteger(0);

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private volatile String cachedWorkerId;
    private ScheduledExecutorService heartbeat;

    /** Previous GC sample, for the recent GC-overhead fraction computed across heartbeats (Phase 3). */
    private volatile long lastGcCollectionMs = -1;
    private volatile long lastGcSampleWallMs = -1;

    @PostConstruct
    public void start() {
        ResourceSchedulerConfig cfg = configService.getConfiguration();
        warnIfClusterEndpointsOpen(cfg);
        if (!cfg.isClusterWorker()) {
            log.info("Crawl cluster: node role '{}' — not advertising as a worker", cfg.getClusterRole());
            return;
        }
        if (cfg.getClusterOrchestratorUrl() == null || cfg.getClusterOrchestratorUrl().isBlank()) {
            log.warn("Crawl cluster: role includes worker but clusterOrchestratorUrl is blank — cannot advertise");
            return;
        }
        long periodSec = Math.max(5, cfg.getClusterHeartbeatSeconds());
        heartbeat = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "crawl-worker-heartbeat");
            t.setDaemon(true);
            return t;
        });
        heartbeat.scheduleWithFixedDelay(this::advertiseSafe, 2, periodSec, TimeUnit.SECONDS);
        log.info("Crawl cluster: advertising as CrawlWorker '{}' to orchestrator {} every {}s",
                workerId(), cfg.getClusterOrchestratorUrl(), periodSec);
    }

    /**
     * Warn loudly when this node participates in a cluster (role != none) but no {@code externalAuthToken} is set.
     * The cluster callback/progress/transcript endpoints then accept unauthenticated POSTs from anyone who can
     * reach the port (the controllers treat a blank token as "open"). Fine for a trusted LAN/dev box; dangerous
     * if the port is publicly exposed.
     */
    private void warnIfClusterEndpointsOpen(ResourceSchedulerConfig cfg) {
        String role = cfg.getClusterRole() == null ? "none" : cfg.getClusterRole().trim();
        boolean inCluster = !role.isEmpty() && !"none".equalsIgnoreCase(role);
        String token = cfg.getExternalAuthToken();
        if (inCluster && (token == null || token.isBlank())) {
            log.warn("Crawl cluster: role '{}' is active but externalAuthToken is blank — cluster endpoints "
                    + "(/api/distributed-crawl/progress, /transcripts, /callback and /api/cluster/**) accept "
                    + "UNAUTHENTICATED requests. Set externalAuthToken (or bind only to a trusted network) "
                    + "before exposing this node.", role);
        }
    }

    @PreDestroy
    public void stop() {
        if (heartbeat != null) {
            heartbeat.shutdownNow();
        }
        // Best-effort graceful deregister so the orchestrator drops us immediately rather than on timeout.
        ResourceSchedulerConfig cfg = configService.getConfiguration();
        if (cfg.isClusterWorker() && cfg.getClusterOrchestratorUrl() != null
                && !cfg.getClusterOrchestratorUrl().isBlank()) {
            try {
                HttpRequest req = HttpRequest.newBuilder()
                        .uri(URI.create(normalize(cfg.getClusterOrchestratorUrl())
                                + "/api/cluster/workers/" + workerId()))
                        .timeout(Duration.ofSeconds(5))
                        .DELETE()
                        .build();
                httpClient.send(req, HttpResponse.BodyHandlers.discarding());
            } catch (Exception ignored) {
                // shutting down — nothing useful to do if the orchestrator is unreachable
            }
        }
    }

    /** Counter the Phase 2 worker-side executor increments/decrements as it runs delegated jobs. */
    public AtomicInteger activeDelegatedJobs() {
        return activeDelegatedJobs;
    }

    private final AtomicBoolean draining = new AtomicBoolean(false);

    /** Drain (true) / resume (false): when draining, this worker advertises {@code acceptingWork=false}. */
    public void setDraining(boolean on) {
        draining.set(on);
    }

    public boolean isDraining() {
        return draining.get();
    }

    /** This node's current capabilities, built fresh from live telemetry + cluster config. */
    public WorkerCapabilities localCapabilities() {
        ResourceSchedulerConfig cfg = configService.getConfiguration();
        ResourceSnapshot s = telemetry.latest();

        List<String> backends = new ArrayList<>();
        backends.add("CPU");
        int gpuCount = 0;
        long totalGpuBytes = 0L;
        List<WorkerCapabilities.GpuInfo> gpuInfos = new ArrayList<>();
        if (s.gpuBackendAvailable() && s.gpus() != null && !s.gpus().isEmpty()) {
            backends.add("CUDA");
            gpuCount = s.gpus().size();
            for (ResourceSnapshot.GpuSnapshot g : s.gpus()) {
                totalGpuBytes += g.totalBytes();
                gpuInfos.add(new WorkerCapabilities.GpuInfo(
                        g.deviceIndex(), g.usedFraction(), g.totalBytes(), g.vramPressure().name()));
            }
        }

        int maxConc = Math.max(1, cfg.getClusterMaxConcurrentJobs());
        int active = activeDelegatedJobs.get();
        boolean drained = draining.get();
        boolean accepting = cfg.isClusterWorker()
                && !drained
                && active < maxConc
                && !s.ramPressure().atLeast(ResourceSnapshot.PressureLevel.CRITICAL);
        String role = cfg.getClusterRole() == null ? "none" : cfg.getClusterRole();

        return new WorkerCapabilities(
                workerId(), advertiseBaseUrl(cfg), role, backends, gpuCount, totalGpuBytes,
                Runtime.getRuntime().availableProcessors(),
                advertisedJobTypes(cfg), maxConc, active,
                s.systemCpuLoad(), s.worstGpuUsedFraction(),
                s.cpuPressure().name(), s.worstGpuPressure().name(),
                accepting, System.currentTimeMillis(),
                s.systemRamUsedFraction(), s.ramPressure().name(), gpuInfos, drained,
                computeGcOverheadFraction());
    }

    /**
     * Recent GC overhead = Δ(total GC collection time) / Δ(wall) since the last sample, clamped to [0,1].
     * A sustained high value means the JVM is spending most of its time collecting rather than making progress —
     * the cluster uses it to down-weight (assignment) and fast-reap (loss detection) a churning worker.
     */
    private double computeGcOverheadFraction() {
        long gc = 0;
        for (GarbageCollectorMXBean b : ManagementFactory.getGarbageCollectorMXBeans()) {
            long t = b.getCollectionTime();
            if (t > 0) {
                gc += t;
            }
        }
        long now = System.currentTimeMillis();
        long prevGc = lastGcCollectionMs;
        long prevWall = lastGcSampleWallMs;
        lastGcCollectionMs = gc;
        lastGcSampleWallMs = now;
        if (prevGc < 0 || prevWall < 0 || now <= prevWall) {
            return 0.0; // first sample — no interval yet
        }
        long gcDelta = Math.max(0, gc - prevGc);
        long wallDelta = now - prevWall;
        return Math.min(1.0, (double) gcDelta / wallDelta);
    }

    /**
     * Job types to advertise: the configured list intersected with the types that actually have a registered
     * {@link ClusterJobRunner} on this node (so the orchestrator never routes work this worker can't run).
     */
    private List<String> advertisedJobTypes(ResourceSchedulerConfig cfg) {
        List<String> configured = cfg.getClusterSupportedJobTypes();
        if (runnerRegistry == null) {
            return configured == null ? new ArrayList<>() : new ArrayList<>(configured);
        }
        Set<String> runnable = runnerRegistry.supportedJobTypes();
        if (configured == null || configured.isEmpty()) {
            return new ArrayList<>(runnable);
        }
        List<String> out = new ArrayList<>();
        for (String t : configured) {
            if (runnable.contains(t)) {
                out.add(t);
            }
        }
        return out;
    }

    private void advertiseSafe() {
        try {
            advertise();
        } catch (Throwable t) {
            log.debug("Crawl cluster heartbeat failed: {}", t.toString());
        }
    }

    private void advertise() throws Exception {
        ResourceSchedulerConfig cfg = configService.getConfiguration();
        if (!cfg.isClusterWorker()) {
            return;
        }
        String url = normalize(cfg.getClusterOrchestratorUrl()) + "/api/cluster/workers";
        String body = objectMapper.writeValueAsString(localCapabilities());
        HttpRequest.Builder rb = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(10))
                .POST(HttpRequest.BodyPublishers.ofString(body));
        String token = cfg.getExternalAuthToken();
        if (token != null && !token.isBlank()) {
            rb.header("Authorization", "Bearer " + token);
        }
        HttpResponse<Void> resp = httpClient.send(rb.build(), HttpResponse.BodyHandlers.discarding());
        if (resp.statusCode() / 100 != 2) {
            log.warn("Crawl cluster heartbeat to {} returned HTTP {}", url, resp.statusCode());
        }
    }

    private String workerId() {
        String configured = configService.getConfiguration().getClusterWorkerId();
        if (configured != null && !configured.isBlank()) {
            return configured;
        }
        if (cachedWorkerId != null) {
            return cachedWorkerId;
        }
        String host;
        try {
            host = InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            host = "worker";
        }
        cachedWorkerId = host + ":" + serverPort;
        return cachedWorkerId;
    }

    private String advertiseBaseUrl(ResourceSchedulerConfig cfg) {
        if (cfg.getClusterAdvertiseBaseUrl() != null && !cfg.getClusterAdvertiseBaseUrl().isBlank()) {
            return normalize(cfg.getClusterAdvertiseBaseUrl());
        }
        String host;
        try {
            host = InetAddress.getLocalHost().getHostAddress();
        } catch (Exception e) {
            host = "127.0.0.1";
        }
        return "http://" + host + ":" + serverPort;
    }

    private static String normalize(String url) {
        if (url == null) {
            return "";
        }
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
