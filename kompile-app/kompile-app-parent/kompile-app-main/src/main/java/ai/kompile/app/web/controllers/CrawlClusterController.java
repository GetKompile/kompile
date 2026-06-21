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
    public ResponseEntity<List<WorkerCapabilities>> liveWorkers() {
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
    public ResponseEntity<WorkerCapabilities> localCapabilities() {
        return ResponseEntity.ok(capabilityService.localCapabilities());
    }

    private boolean authorized(String authHeader) {
        String token = configService.getConfiguration().getExternalAuthToken();
        if (token == null || token.isBlank()) {
            return true; // no token configured — open (local/dev)
        }
        return ("Bearer " + token).equals(authHeader);
    }
}
