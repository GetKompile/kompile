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

package ai.kompile.app.web.controllers;

import ai.kompile.app.services.cluster.CrawlWorkerCapabilityService;
import ai.kompile.app.services.cluster.CrawlWorkerRegistry;
import ai.kompile.app.services.cluster.WorkerCapabilities;
import ai.kompile.app.services.scheduler.ResourceSchedulerConfigService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import java.util.List;
import java.util.Map;

/**
 * Cluster HTTP surface — where CrawlWorker capabilities are shared. Workers POST their
 * {@link WorkerCapabilities} to {@code /api/cluster/workers} (register + heartbeat); the orchestrator's live
 * view is readable at the same path. {@code /api/cluster/capabilities} returns this node's own capabilities.
 *
 * <p>When {@code externalAuthToken} is configured, register/deregister require a matching
 * {@code Authorization: Bearer <token>} header; with no token set the endpoints are open (local/dev).</p>
 */
@RestController
@RequestMapping("/api/cluster")
public class CrawlClusterController {

    private static final Logger log = LoggerFactory.getLogger(CrawlClusterController.class);

    private final CrawlWorkerRegistry registry;
    private final CrawlWorkerCapabilityService capabilityService;
    private final ResourceSchedulerConfigService configService;

    public CrawlClusterController(CrawlWorkerRegistry registry,
                                  CrawlWorkerCapabilityService capabilityService,
                                  ResourceSchedulerConfigService configService) {
        this.registry = registry;
        this.capabilityService = capabilityService;
        this.configService = configService;
    }

    /** A worker registers or heartbeats its capabilities (orchestrator side). */
    @PostMapping("/workers")
    public ResponseEntity<Map<String, Object>> registerWorker(
            @RequestBody WorkerCapabilities caps,
            @RequestHeader(value = "Authorization", required = false) String auth) {
        if (!authorized(auth)) {
            return ResponseEntity.status(401).body(Map.of("ok", false, "error", "unauthorized"));
        }
        try {
            registry.registerOrHeartbeat(caps, System.currentTimeMillis());
            return ResponseEntity.ok(Map.of("ok", true, "workerId", caps.workerId()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("ok", false, "error", e.getMessage()));
        }
    }

    /** The live cluster view — capabilities of every known worker (orchestrator side). */
    @GetMapping("/workers")
    public ResponseEntity<List<WorkerCapabilities>> liveWorkers(
            @RequestHeader(value = "Authorization", required = false) String auth) {
        if (!authorized(auth)) return ResponseEntity.status(401).build();
        return ResponseEntity.ok(registry.liveWorkers(System.currentTimeMillis()));
    }

    /** Graceful deregister of a worker leaving the cluster (orchestrator side). */
    @DeleteMapping("/workers/{workerId}")
    public ResponseEntity<Map<String, Object>> deregisterWorker(
            @PathVariable String workerId,
            @RequestHeader(value = "Authorization", required = false) String auth) {
        if (!authorized(auth)) {
            return ResponseEntity.status(401).body(Map.of("ok", false, "error", "unauthorized"));
        }
        boolean removed = registry.deregister(workerId);
        return ResponseEntity.ok(Map.of("ok", true, "removed", removed));
    }

    /** This node's own current capabilities (what it can do + live load). */
    @GetMapping("/capabilities")
    public ResponseEntity<WorkerCapabilities> localCapabilities(
            @RequestHeader(value = "Authorization", required = false) String auth) {
        if (!authorized(auth)) return ResponseEntity.status(401).build();
        return ResponseEntity.ok(capabilityService.localCapabilities());
    }

    // ==================== Lifecycle management ====================

    private final java.net.http.HttpClient httpClient = java.net.http.HttpClient.newBuilder()
            .connectTimeout(java.time.Duration.ofSeconds(10)).build();

    /** Worker side: drain ({@code on=true}) / resume ({@code on=false}) this node — stops accepting new work. */
    @PostMapping("/local/drain")
    public ResponseEntity<Map<String, Object>> localDrain(
            @org.springframework.web.bind.annotation.RequestParam(value = "on", defaultValue = "true") boolean on,
            @RequestHeader(value = "Authorization", required = false) String auth) {
        if (!authorized(auth)) {
            return ResponseEntity.status(401).body(Map.of("ok", false, "error", "unauthorized"));
        }
        capabilityService.setDraining(on);
        log.info("Worker draining set to {}", on);
        return ResponseEntity.ok(Map.of("ok", true, "draining", on));
    }

    /** Orchestrator side: drain/resume a worker by id (proxies to its {@code /local/drain}). */
    @PostMapping("/workers/{workerId}/drain")
    public ResponseEntity<Map<String, Object>> drainWorker(
            @PathVariable String workerId,
            @org.springframework.web.bind.annotation.RequestParam(value = "on", defaultValue = "true") boolean on,
            @RequestHeader(value = "Authorization", required = false) String auth) {
        if (!authorized(auth)) {
            return ResponseEntity.status(401).body(Map.of("ok", false, "error", "unauthorized"));
        }
        return proxy(workerId, "POST", "/api/cluster/local/drain?on=" + on);
    }

    /** Orchestrator side: a worker's delegated-job statuses (proxies to its {@code /local/jobs}). */
    @GetMapping("/workers/{workerId}/jobs")
    public ResponseEntity<Map<String, Object>> workerJobs(
            @PathVariable String workerId,
            @RequestHeader(value = "Authorization", required = false) String auth) {
        if (!authorized(auth)) {
            return ResponseEntity.status(401).body(Map.of("ok", false, "error", "unauthorized"));
        }
        return proxy(workerId, "GET", "/api/cluster/local/jobs");
    }

    /** Orchestrator side: cancel a delegated job on a worker (proxies to its job cancel). */
    @DeleteMapping("/workers/{workerId}/jobs/{jobId}")
    public ResponseEntity<Map<String, Object>> cancelWorkerJob(
            @PathVariable String workerId, @PathVariable String jobId,
            @RequestHeader(value = "Authorization", required = false) String auth) {
        if (!authorized(auth)) {
            return ResponseEntity.status(401).body(Map.of("ok", false, "error", "unauthorized"));
        }
        return proxy(workerId, "DELETE", "/api/cluster/jobs/" + jobId + "/cancel");
    }

    private ResponseEntity<Map<String, Object>> proxy(String workerId, String method, String path) {
        java.util.Optional<WorkerCapabilities> w = registry.find(workerId, System.currentTimeMillis());
        if (w.isEmpty()) {
            return ResponseEntity.status(404).body(Map.of("ok", false, "error", "no live worker '" + workerId + "'"));
        }
        String url = normalize(w.get().baseUrl()) + path;
        try {
            java.net.http.HttpRequest.Builder rb = java.net.http.HttpRequest.newBuilder()
                    .uri(java.net.URI.create(url)).timeout(java.time.Duration.ofSeconds(20));
            switch (method) {
                case "POST" -> rb.POST(java.net.http.HttpRequest.BodyPublishers.noBody());
                case "DELETE" -> rb.DELETE();
                default -> rb.GET();
            }
            String token = configService.getConfiguration().getExternalAuthToken();
            if (token != null && !token.isBlank()) {
                rb.header("Authorization", "Bearer " + token);
            }
            java.net.http.HttpResponse<String> resp =
                    httpClient.send(rb.build(), java.net.http.HttpResponse.BodyHandlers.ofString());
            Map<String, Object> out = new java.util.LinkedHashMap<>();
            out.put("ok", resp.statusCode() / 100 == 2);
            out.put("worker", workerId);
            out.put("status", resp.statusCode());
            out.put("body", resp.body() == null ? "" : resp.body());
            return ResponseEntity.status(resp.statusCode()).body(out);
        } catch (Exception e) {
            log.warn("Proxy {} {} to worker '{}' failed: {}", method, path, workerId, e.getMessage());
            return ResponseEntity.status(502).body(Map.of("ok", false, "error", e.getMessage()));
        }
    }

    private static String normalize(String url) {
        if (url == null) {
            return "";
        }
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    private boolean authorized(String authHeader) {
        String token = configService.getConfiguration().getExternalAuthToken();
        if (token == null || token.isBlank()) {
            return !configService.getConfiguration().isClusterWorker()
                    && !configService.getConfiguration().isClusterOrchestrator();
        }
        byte[] expected = ("Bearer " + token).getBytes(StandardCharsets.UTF_8);
        byte[] actual = authHeader == null ? new byte[0] : authHeader.getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(expected, actual);
    }
}
