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

import ai.kompile.app.project.ProjectBackendService;
import ai.kompile.app.services.enforcer.EnforcerMetricsService;
import ai.kompile.app.services.enforcer.EnforcerSessionManager;
import ai.kompile.app.services.enforcer.EnforcerSessionState;
import ai.kompile.app.web.dto.EnforcerEventRequest;
import ai.kompile.app.web.dto.EnforcerSessionRequest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * REST controller for managing enforcer sessions.
 * <p>
 * Provides endpoints to create, list, monitor, enable/disable, restart,
 * and destroy enforcer-controlled agent sessions. The web UI and CLI
 * monitor connect through these endpoints.
 */
@RestController
@RequestMapping("/api/enforcer")
public class EnforcerSessionController {

    private static final String CONFIG_FILENAME = "enforcer-config.json";
    private static final ObjectMapper CONFIG_MAPPER = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    private final EnforcerSessionManager sessionManager;
    private final EnforcerMetricsService metricsService;

    @Autowired
    private ProjectBackendService projectBackendService;

    public EnforcerSessionController(EnforcerSessionManager sessionManager,
                                      EnforcerMetricsService metricsService) {
        this.sessionManager = sessionManager;
        this.metricsService = metricsService;
    }

    /**
     * List all active enforcer sessions.
     */
    @GetMapping("/sessions")
    public ResponseEntity<List<Map<String, Object>>> listSessions() {
        return ResponseEntity.ok(sessionManager.listSessions());
    }

    /**
     * List active judge/enforcer watcher processes.
     */
    @GetMapping("/processes")
    public ResponseEntity<List<Map<String, Object>>> listProcesses() {
        return ResponseEntity.ok(sessionManager.listActiveProcesses());
    }

    /**
     * Get detail for a specific session (including recent events).
     */
    @GetMapping("/sessions/{sessionId}")
    public ResponseEntity<Map<String, Object>> getSession(@PathVariable String sessionId) {
        Map<String, Object> detail = sessionManager.getSessionDetail(sessionId);
        if (detail == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(detail);
    }

    /**
     * Create a new enforcer session with a managed agent subprocess.
     */
    @PostMapping("/sessions")
    public ResponseEntity<Map<String, Object>> createSession(
            @RequestBody EnforcerSessionRequest request) {
        try {
            EnforcerSessionState state = sessionManager.createSession(
                    request.getAgentName(),
                    request.getRules(),
                    request.getMaxCorrections(),
                    request.getJudgeBackend(),
                    request.getWorkingDirectory(),
                    request.isSkipPermissions(),
                    request.isInjectMcpTools(),
                    request.getCodingProjectId());
            return ResponseEntity.ok(state.toSummaryMap());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of(
                    "status", "error", "message", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of(
                    "status", "error", "message", e.getMessage()));
        }
    }

    /**
     * Register an externally-managed enforcer session (CLI pushes state here).
     */
    @PostMapping("/sessions/register")
    public ResponseEntity<Map<String, Object>> registerSession(
            @RequestBody EnforcerSessionRequest request,
            @RequestParam(required = false) String sessionId) {
        String id = sessionId != null && !sessionId.isBlank()
                ? sessionId
                : "enforcer-" + UUID.randomUUID().toString().substring(0, 8);
        EnforcerSessionState state = sessionManager.registerExternalSession(
                id, request.getAgentName(), request.getRules(),
                request.getMaxCorrections(), request.getJudgeBackend(),
                request.getWorkingDirectory(), request.getCodingProjectId());
        return ResponseEntity.ok(state.toSummaryMap());
    }

    /**
     * Enable enforcement on a session.
     */
    @PutMapping("/sessions/{sessionId}/enable")
    public ResponseEntity<Map<String, Object>> enableEnforcement(@PathVariable String sessionId) {
        boolean ok = sessionManager.enableEnforcement(sessionId);
        if (!ok) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(Map.of("sessionId", sessionId, "enabled", true));
    }

    /**
     * Disable enforcement on a session (agent runs unchecked).
     */
    @PutMapping("/sessions/{sessionId}/disable")
    public ResponseEntity<Map<String, Object>> disableEnforcement(@PathVariable String sessionId) {
        boolean ok = sessionManager.disableEnforcement(sessionId);
        if (!ok) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(Map.of("sessionId", sessionId, "enabled", false));
    }

    /**
     * Send a message to the agent subprocess in an enforcer session.
     */
    @PostMapping("/sessions/{sessionId}/send")
    public ResponseEntity<Map<String, Object>> sendMessage(
            @PathVariable String sessionId,
            @RequestBody Map<String, String> body) {
        String message = body.get("message");
        if (message == null || message.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "status", "error", "message", "message is required"));
        }
        boolean ok = sessionManager.sendMessage(sessionId, message);
        if (!ok) {
            return ResponseEntity.badRequest().body(Map.of(
                    "status", "error", "message", "Session not found or inactive"));
        }
        return ResponseEntity.ok(Map.of("status", "sent"));
    }

    /**
     * Push an interrupt event from a CLI-side enforcer.
     */
    @PostMapping("/sessions/{sessionId}/events")
    public ResponseEntity<Map<String, Object>> pushEvent(
            @PathVariable String sessionId,
            @RequestBody EnforcerEventRequest request) {
        EnforcerSessionState.InterruptEvent event = new EnforcerSessionState.InterruptEvent(
                request.getEventId() != null ? request.getEventId() : UUID.randomUUID().toString(),
                Instant.now(),
                request.getType() != null ? request.getType() : "UNKNOWN",
                request.getSeverity() != null ? request.getSeverity() : "info",
                request.getScore(),
                request.getViolations() != null ? request.getViolations() : List.of(),
                request.getReason(),
                request.getCorrectionPrompt(),
                request.getAction() != null ? request.getAction() : "CONTINUE"
        );
        boolean ok = sessionManager.recordEvent(sessionId, event);
        if (!ok) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(Map.of("status", "recorded", "eventId", event.eventId()));
    }

    /**
     * Get violations for a session.
     */
    @GetMapping("/sessions/{sessionId}/violations")
    public ResponseEntity<List<Map<String, Object>>> getViolations(@PathVariable String sessionId) {
        List<Map<String, Object>> violations = sessionManager.getViolations(sessionId);
        if (violations == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(violations);
    }

    /**
     * Subscribe to real-time events from a session via SSE.
     */
    @GetMapping(value = "/sessions/{sessionId}/stream",
            produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamEvents(@PathVariable String sessionId) {
        SseEmitter emitter = sessionManager.subscribeEvents(sessionId);
        if (emitter == null) {
            SseEmitter errorEmitter = new SseEmitter(0L);
            try {
                errorEmitter.send(SseEmitter.event()
                        .name("error")
                        .data(Map.of("message", "Session not found: " + sessionId)));
                errorEmitter.complete();
            } catch (Exception ignored) {}
            return errorEmitter;
        }
        return emitter;
    }

    /**
     * Restart an enforcer session (end + recreate with same parameters).
     */
    @PostMapping("/sessions/{sessionId}/restart")
    public ResponseEntity<Map<String, Object>> restartSession(@PathVariable String sessionId) {
        try {
            EnforcerSessionState newState = sessionManager.restartSession(sessionId);
            return ResponseEntity.ok(newState.toSummaryMap());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of(
                    "status", "error", "message", e.getMessage()));
        }
    }

    /**
     * End and remove an enforcer session.
     */
    @DeleteMapping("/sessions/{sessionId}")
    public ResponseEntity<Map<String, Object>> deleteSession(@PathVariable String sessionId) {
        sessionManager.endSession(sessionId);
        return ResponseEntity.ok(Map.of("status", "ended", "sessionId", sessionId));
    }

    // ========================================================================
    // Per-agent-per-folder metrics
    // ========================================================================

    /**
     * Get metrics for all coding projects and agents.
     */
    @GetMapping("/metrics")
    public ResponseEntity<List<Map<String, Object>>> getAllMetrics() {
        return ResponseEntity.ok(metricsService.getAllMetrics());
    }

    /**
     * Get metrics for a specific coding project (all agents).
     */
    @GetMapping("/metrics/{codingProjectId}")
    public ResponseEntity<List<Map<String, Object>>> getProjectMetrics(
            @PathVariable String codingProjectId) {
        return ResponseEntity.ok(metricsService.getMetricsForProject(codingProjectId));
    }

    /**
     * Get detailed metrics for a specific agent in a specific coding project,
     * including event history.
     */
    @GetMapping("/metrics/{codingProjectId}/{agentName}")
    public ResponseEntity<Map<String, Object>> getProjectAgentMetrics(
            @PathVariable String codingProjectId,
            @PathVariable String agentName) {
        Map<String, Object> detail = metricsService.getMetricsForProjectAgent(codingProjectId, agentName);
        if (detail == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(detail);
    }

    // ========================================================================
    // Per-coding-project enforcer/judge config management
    // ========================================================================

    /**
     * Get enforcer/judge config for a coding project.
     * Reads from .kompile/code-projects/{codingProjectId}/enforcer-config.json,
     * falling back to .kompile/enforcer-config.json at the project root.
     */
    @GetMapping("/config/{codingProjectId}")
    public ResponseEntity<JsonNode> getCodeProjectConfig(@PathVariable String codingProjectId) {
        try {
            Path projectRoot = resolveProjectRoot();
            Path configPath = projectRoot.resolve(".kompile").resolve("code-projects")
                    .resolve(codingProjectId).resolve(CONFIG_FILENAME);
            if (Files.exists(configPath)) {
                JsonNode config = CONFIG_MAPPER.readTree(configPath.toFile());
                ObjectNode result = CONFIG_MAPPER.createObjectNode();
                result.put("source", "code-project");
                result.put("codingProjectId", codingProjectId);
                result.put("configPath", configPath.toString());
                result.set("config", config);
                return ResponseEntity.ok(result);
            }
            // Fall back to project-level config
            Path projectConfig = projectRoot.resolve(".kompile").resolve(CONFIG_FILENAME);
            if (Files.exists(projectConfig)) {
                JsonNode config = CONFIG_MAPPER.readTree(projectConfig.toFile());
                ObjectNode result = CONFIG_MAPPER.createObjectNode();
                result.put("source", "project");
                result.put("codingProjectId", codingProjectId);
                result.put("configPath", projectConfig.toString());
                result.set("config", config);
                return ResponseEntity.ok(result);
            }
            ObjectNode result = CONFIG_MAPPER.createObjectNode();
            result.put("source", "none");
            result.put("codingProjectId", codingProjectId);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(
                    CONFIG_MAPPER.createObjectNode().put("error", e.getMessage()));
        }
    }

    /**
     * Save or update enforcer/judge config for a coding project.
     */
    @PutMapping("/config/{codingProjectId}")
    public ResponseEntity<Map<String, Object>> saveCodeProjectConfig(
            @PathVariable String codingProjectId,
            @RequestBody JsonNode config) {
        try {
            Path projectRoot = resolveProjectRoot();
            Path configPath = projectRoot.resolve(".kompile").resolve("code-projects")
                    .resolve(codingProjectId).resolve(CONFIG_FILENAME);
            Files.createDirectories(configPath.getParent());
            CONFIG_MAPPER.writeValue(configPath.toFile(), config);
            return ResponseEntity.ok(Map.of(
                    "status", "saved",
                    "codingProjectId", codingProjectId,
                    "configPath", configPath.toString()));
        } catch (IOException e) {
            return ResponseEntity.internalServerError().body(Map.of(
                    "status", "error", "message", e.getMessage()));
        }
    }

    /**
     * Delete per-coding-project enforcer config (reverts to project-level).
     */
    @DeleteMapping("/config/{codingProjectId}")
    public ResponseEntity<Map<String, Object>> deleteCodeProjectConfig(
            @PathVariable String codingProjectId) {
        try {
            Path projectRoot = resolveProjectRoot();
            Path configPath = projectRoot.resolve(".kompile").resolve("code-projects")
                    .resolve(codingProjectId).resolve(CONFIG_FILENAME);
            if (Files.deleteIfExists(configPath)) {
                return ResponseEntity.ok(Map.of(
                        "status", "deleted", "codingProjectId", codingProjectId));
            }
            return ResponseEntity.ok(Map.of(
                    "status", "not_found", "codingProjectId", codingProjectId));
        } catch (IOException e) {
            return ResponseEntity.internalServerError().body(Map.of(
                    "status", "error", "message", e.getMessage()));
        }
    }

    private Path resolveProjectRoot() {
        try {
            // Use the project backend service's manifest to find the project root
            projectBackendService.getManifest();
            // If getManifest() succeeded, the project root is resolvable via cwd
            return Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        } catch (Exception e) {
            return Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        }
    }
}
