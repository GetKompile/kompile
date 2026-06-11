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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.File;
import java.lang.management.ManagementFactory;
import java.lang.management.RuntimeMXBean;
import java.net.Socket;
import java.util.*;

/**
 * REST controller exposing system information equivalent to the {@code kompile info} CLI command.
 *
 * <ul>
 *   <li>{@code GET  /api/system/info}        — version, home dir, installed tools, JVM runtime stats</li>
 *   <li>{@code GET  /api/system/health}       — simple UP/DOWN liveness probe</li>
 *   <li>{@code GET  /api/system/components}   — known Kompile component types with port-probe status</li>
 *   <li>{@code POST /api/system/components/{componentId}/start}   — request a component start</li>
 *   <li>{@code POST /api/system/components/{componentId}/stop}    — request a component stop</li>
 *   <li>{@code POST /api/system/components/{componentId}/restart} — request a component restart</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/system")
public class SystemInfoController {

    private static final Logger logger = LoggerFactory.getLogger(SystemInfoController.class);

    // Default ports for known Kompile component types
    private static final Map<String, Integer> COMPONENT_DEFAULT_PORTS;

    static {
        Map<String, Integer> ports = new LinkedHashMap<>();
        ports.put("kompile-app-main", 8080);
        ports.put("kompile-model-staging", 8081);
        COMPONENT_DEFAULT_PORTS = Collections.unmodifiableMap(ports);
    }

    // No-arg constructor — no injected dependencies
    public SystemInfoController() {
    }

    // =========================================================================
    // GET /api/system/info
    // =========================================================================

    /**
     * Returns system information equivalent to {@code kompile info}.
     */
    @GetMapping("/info")
    public ResponseEntity<Map<String, Object>> getSystemInfo() {
        Map<String, Object> response = new LinkedHashMap<>();

        try {
            response.put("version", buildVersionSection());
            response.put("homeDirectory", kompileHomePath());
            response.put("homeExists", new File(kompileHomePath()).exists());
            response.put("installedTools", buildInstalledToolsSection());
            response.put("runtime", buildRuntimeSection());
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            logger.error("Error building system info", e);
            response.put("status", "error");
            response.put("message", e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    // =========================================================================
    // GET /api/system/health
    // =========================================================================

    /**
     * Simple liveness probe. Returns {@code {"status":"UP"}} when the application is running.
     */
    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> getHealth() {
        return ResponseEntity.ok(Map.of("status", "UP"));
    }

    // =========================================================================
    // GET /api/system/components
    // =========================================================================

    /**
     * Returns the list of known Kompile component types together with a live port-probe status.
     */
    @GetMapping("/components")
    public ResponseEntity<List<Map<String, Object>>> getComponents() {
        try {
            List<Map<String, Object>> components = buildComponentsList();
            return ResponseEntity.ok(components);
        } catch (Exception e) {
            logger.error("Error building components list", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    // =========================================================================
    // POST /api/system/components/{componentId}/start
    // =========================================================================

    /**
     * Requests a component start. The actual launch is a server-side concern; this endpoint
     * acknowledges the request and returns the target component and port.
     *
     * @param componentId the component identifier (e.g. {@code kompile-app-main})
     * @param port        optional override port; defaults to the component's registered default
     */
    @PostMapping("/components/{componentId}/start")
    public ResponseEntity<Map<String, Object>> startComponent(
            @PathVariable String componentId,
            @RequestParam(required = false) Integer port) {

        Map<String, Object> response = new LinkedHashMap<>();

        if (!COMPONENT_DEFAULT_PORTS.containsKey(componentId)) {
            response.put("status", "error");
            response.put("message", "Unknown component: " + componentId);
            return ResponseEntity.badRequest().body(response);
        }

        int targetPort = port != null ? port : COMPONENT_DEFAULT_PORTS.get(componentId);
        response.put("componentId", componentId);
        response.put("targetPort", targetPort);
        response.put("status", "accepted");
        response.put("message", "Start request accepted for component '" + componentId
                + "' on port " + targetPort + ". Component lifecycle is managed server-side.");
        return ResponseEntity.accepted().body(response);
    }

    // =========================================================================
    // POST /api/system/components/{componentId}/stop
    // =========================================================================

    /**
     * Requests a component stop. Returns an acknowledgement; actual lifecycle management is
     * server-side.
     *
     * @param componentId the component identifier
     */
    @PostMapping("/components/{componentId}/stop")
    public ResponseEntity<Map<String, Object>> stopComponent(@PathVariable String componentId) {
        Map<String, Object> response = new LinkedHashMap<>();

        if (!COMPONENT_DEFAULT_PORTS.containsKey(componentId)) {
            response.put("status", "error");
            response.put("message", "Unknown component: " + componentId);
            return ResponseEntity.badRequest().body(response);
        }

        response.put("componentId", componentId);
        response.put("status", "accepted");
        response.put("message", "Stop request accepted for component '" + componentId
                + "'. Component lifecycle is managed server-side.");
        return ResponseEntity.accepted().body(response);
    }

    // =========================================================================
    // POST /api/system/components/{componentId}/restart
    // =========================================================================

    /**
     * Requests a component restart. Returns an acknowledgement; actual lifecycle management is
     * server-side.
     *
     * @param componentId the component identifier
     */
    @PostMapping("/components/{componentId}/restart")
    public ResponseEntity<Map<String, Object>> restartComponent(@PathVariable String componentId) {
        Map<String, Object> response = new LinkedHashMap<>();

        if (!COMPONENT_DEFAULT_PORTS.containsKey(componentId)) {
            response.put("status", "error");
            response.put("message", "Unknown component: " + componentId);
            return ResponseEntity.badRequest().body(response);
        }

        response.put("componentId", componentId);
        response.put("status", "accepted");
        response.put("message", "Restart request accepted for component '" + componentId
                + "'. Component lifecycle is managed server-side.");
        return ResponseEntity.accepted().body(response);
    }

    // =========================================================================
    // Private helpers
    // =========================================================================

    private String kompileHomePath() {
        return System.getProperty("user.home") + "/.kompile";
    }

    private Map<String, Object> buildVersionSection() {
        Map<String, Object> version = new LinkedHashMap<>();
        version.put("app", "0.1.0-SNAPSHOT");
        version.put("springBoot", "3.4.5");
        version.put("java", System.getProperty("java.version", "unknown"));
        version.put("os", System.getProperty("os.name", "unknown"));
        version.put("arch", System.getProperty("os.arch", "unknown"));
        return version;
    }

    private Map<String, Boolean> buildInstalledToolsSection() {
        String home = kompileHomePath();
        Map<String, Boolean> tools = new LinkedHashMap<>();
        tools.put("graalvm", new File(home, "graalvm").exists());
        tools.put("maven",   new File(home, "mvn").exists());
        tools.put("python",  new File(home, "python").exists());
        tools.put("cmake",   new File(home, "cmake").exists());
        return tools;
    }

    private Map<String, Object> buildRuntimeSection() {
        Map<String, Object> runtime = new LinkedHashMap<>();
        Runtime jvmRuntime = Runtime.getRuntime();
        runtime.put("totalMemory", jvmRuntime.totalMemory());
        runtime.put("freeMemory", jvmRuntime.freeMemory());
        runtime.put("maxMemory", jvmRuntime.maxMemory());
        runtime.put("availableProcessors", jvmRuntime.availableProcessors());
        runtime.put("uptime", formatUptime(getUptimeMillis()));
        return runtime;
    }

    private long getUptimeMillis() {
        try {
            RuntimeMXBean bean = ManagementFactory.getRuntimeMXBean();
            return bean.getUptime();
        } catch (Exception e) {
            return 0L;
        }
    }

    /**
     * Formats a duration in milliseconds into a human-readable string like {@code "2h 15m"}.
     */
    private String formatUptime(long uptimeMs) {
        long totalSeconds = uptimeMs / 1000L;
        long days    = totalSeconds / 86400L;
        long hours   = (totalSeconds % 86400L) / 3600L;
        long minutes = (totalSeconds % 3600L) / 60L;
        long seconds = totalSeconds % 60L;

        StringBuilder sb = new StringBuilder();
        if (days > 0) {
            sb.append(days).append("d ");
        }
        if (hours > 0 || days > 0) {
            sb.append(hours).append("h ");
        }
        if (minutes > 0 || hours > 0 || days > 0) {
            sb.append(minutes).append("m");
        } else {
            sb.append(seconds).append("s");
        }
        return sb.toString().trim();
    }

    private List<Map<String, Object>> buildComponentsList() {
        List<Map<String, Object>> components = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : COMPONENT_DEFAULT_PORTS.entrySet()) {
            String id   = entry.getKey();
            int    port = entry.getValue();

            Map<String, Object> component = new LinkedHashMap<>();
            component.put("componentId", id);
            component.put("defaultPort", port);
            component.put("status", probePort(port) ? "running" : "stopped");
            components.add(component);
        }
        return components;
    }

    /**
     * Tests whether a local TCP port is accepting connections.
     *
     * @param port the port to probe
     * @return {@code true} if a connection was established, {@code false} otherwise
     */
    private boolean probePort(int port) {
        try (Socket socket = new Socket("localhost", port)) {
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
