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

package ai.kompile.cli.main.manage;

import ai.kompile.cli.common.registry.InstanceInfo;
import ai.kompile.cli.common.registry.InstanceRegistry;
import ai.kompile.cli.main.install.registry.ComponentRegistry;
import org.zeroturnaround.exec.ProcessExecutor;
import org.zeroturnaround.exec.stream.LogOutputStream;

import java.io.File;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeoutException;

/**
 * Manages the lifecycle of Kompile components (start, stop, status, restart).
 * Handles process management, health checking, and instance registration.
 */
public class ServiceManager {

    private static final int DEFAULT_STARTUP_TIMEOUT_SECONDS = 120;
    private static final int HEALTH_CHECK_TIMEOUT_MS = 5000;

    /**
     * Start a component as a background process
     */
    public ProcessResult startComponent(String componentId, int port, List<String> jvmArgs, List<String> appArgs) throws Exception {
        ComponentRegistry registry = new ComponentRegistry();
        File jarFile = registry.findInstalledJar(componentId);

        if (jarFile == null) {
            return ProcessResult.error("Component not installed: " + componentId +
                    "\nInstall it with: kompile install " + componentId);
        }

        // Check if already running
        InstanceInfo existing = InstanceRegistry.findByType(componentId);
        if (existing != null) {
            Optional<ProcessHandle> process = ProcessHandle.of(existing.getPid());
            if (process.isPresent() && process.get().isAlive()) {
                return ProcessResult.error("Component already running: " + componentId + 
                        "\n  PID: " + existing.getPid() + 
                        "\n  Port: " + existing.getPort() +
                        "\n  URL: " + existing.getUrl());
            } else {
                // Stale registry entry - clean it up
                InstanceRegistry.unregister(componentId);
            }
        }

        // Build command
        List<String> command = new ArrayList<>();
        command.add("java");
        
        // Add JVM arguments
        if (jvmArgs != null) {
            command.addAll(jvmArgs);
        }
        
        // Add default memory settings if not specified
        boolean hasXmx = jvmArgs != null && jvmArgs.stream().anyMatch(arg -> arg.startsWith("-Xmx"));
        if (!hasXmx) {
            command.add("-Xmx4g");
        }
        
        command.add("-jar");
        command.add(jarFile.getAbsolutePath());
        
        // Add Spring Boot port argument
        command.add("--server.port=" + port);
        
        // Add application arguments
        if (appArgs != null) {
            command.addAll(appArgs);
        }

        System.out.println("Starting " + componentId + "...");
        System.out.println("  JAR: " + jarFile.getAbsolutePath());
        System.out.println("  Port: " + port);
        System.out.println("  Command: " + String.join(" ", command));

        // Start process
        Process process = new ProcessBuilder(command)
                .redirectOutput(ProcessBuilder.Redirect.PIPE)
                .redirectError(ProcessBuilder.Redirect.PIPE)
                .start();

        long pid = process.pid();
        System.out.println("  PID: " + pid);

        // Register instance
        InstanceInfo instanceInfo = new InstanceInfo(componentId, componentId, port, pid, jarFile.getAbsolutePath());
        InstanceRegistry.register(instanceInfo);

        // Wait for startup
        System.out.println("  Waiting for service to start (timeout: " + DEFAULT_STARTUP_TIMEOUT_SECONDS + "s)...");
        boolean healthy = waitForHealth(port, DEFAULT_STARTUP_TIMEOUT_SECONDS);

        if (healthy) {
            System.out.println("  ✓ " + componentId + " started successfully on port " + port);
            return ProcessResult.success(componentId, pid, port);
        } else {
            System.err.println("  ⚠ Service started but health check failed");
            System.err.println("  Check logs with: kompile manage logs " + componentId);
            return ProcessResult.warning(componentId, pid, port, "Health check failed");
        }
    }

    /**
     * Stop a running component
     */
    public ProcessResult stopComponent(String componentId) throws Exception {
        InstanceInfo info = InstanceRegistry.findByType(componentId);
        
        if (info == null) {
            return ProcessResult.error("Component not found in registry: " + componentId + 
                    "\nList running components: kompile manage list");
        }

        Optional<ProcessHandle> processOpt = ProcessHandle.of(info.getPid());
        if (!processOpt.isPresent() || !processOpt.get().isAlive()) {
            InstanceRegistry.unregister(componentId);
            return ProcessResult.error("Component process not running (stale registry entry)");
        }

        ProcessHandle process = processOpt.get();
        System.out.println("Stopping " + componentId + " (PID: " + info.getPid() + ")...");

        // Try graceful shutdown first (SIGTERM)
        process.destroy();

        // Wait for termination (up to 10 seconds)
        try {
            boolean exited = process.onExit()
                    .get(10, java.util.concurrent.TimeUnit.SECONDS)
                    .isAlive();
            if (exited) {
                // Process still alive after SIGTERM, force kill
                System.out.println("  Graceful shutdown timed out, force killing...");
                process.destroyForcibly();
            }
        } catch (Exception e) {
            // Timeout or interrupted, force kill
            System.out.println("  Graceful shutdown failed, force killing...");
            process.destroyForcibly();
        }

        // Unregister
        InstanceRegistry.unregister(componentId);

        System.out.println("  ✓ " + componentId + " stopped");
        return ProcessResult.success(componentId, info.getPid(), info.getPort(), "stopped");
    }

    /**
     * Get status of a component
     */
    public ComponentStatus getComponentStatus(String componentId) {
        ComponentRegistry registry = new ComponentRegistry();
        boolean installed = registry.isInstalled(componentId);
        
        try {
            InstanceInfo info = InstanceRegistry.findByType(componentId);
            
            if (info == null) {
                return new ComponentStatus(componentId, "not_running", installed, null, null, null);
            }

            Optional<ProcessHandle> processOpt = ProcessHandle.of(info.getPid());
            boolean isAlive = processOpt.isPresent() && processOpt.get().isAlive();
            
            if (!isAlive) {
                // Clean up stale entry
                InstanceRegistry.unregister(componentId);
                return new ComponentStatus(componentId, "dead", installed, info.getPid(), info.getPort(), null);
            }

            // Check health
            boolean healthy = checkHealth(info.getPort());
            String status = healthy ? "running" : "unhealthy";
            
            return new ComponentStatus(componentId, status, installed, info.getPid(), info.getPort(), info.getUrl());

        } catch (Exception e) {
            return new ComponentStatus(componentId, "error", installed, null, null, e.getMessage());
        }
    }

    /**
     * List all components and their statuses
     */
    public List<ComponentStatus> listAllComponents() {
        ComponentRegistry registry = new ComponentRegistry();
        List<ComponentStatus> statuses = new ArrayList<>();

        for (ComponentRegistry.ComponentDescriptor descriptor : registry.listAllComponents()) {
            statuses.add(getComponentStatus(descriptor.getId()));
        }

        return statuses;
    }

    /**
     * Check if a service is healthy on a given port
     */
    public boolean checkHealth(int port) {
        return checkHealth(port, HEALTH_CHECK_TIMEOUT_MS);
    }

    public boolean checkHealth(int port, int timeoutMs) {
        try {
            URL url = new URL("http://localhost:" + port + "/actuator/health");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(timeoutMs);
            conn.setReadTimeout(timeoutMs);
            
            int responseCode = conn.getResponseCode();
            conn.disconnect();
            
            return responseCode == 200;
        } catch (Exception e) {
            // Try root endpoint as fallback
            try {
                URL url = new URL("http://localhost:" + port + "/");
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                conn.setConnectTimeout(timeoutMs);
                conn.setReadTimeout(timeoutMs);
                
                int responseCode = conn.getResponseCode();
                conn.disconnect();
                
                return responseCode == 200;
            } catch (Exception ex) {
                return false;
            }
        }
    }

    /**
     * Wait for service to become healthy
     */
    public boolean waitForHealth(int port, int timeoutSeconds) {
        long startTime = System.currentTimeMillis();
        long timeoutMs = timeoutSeconds * 1000L;

        while (System.currentTimeMillis() - startTime < timeoutMs) {
            if (checkHealth(port)) {
                return true;
            }
            try {
                Thread.sleep(2000); // Check every 2 seconds
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }

        return false;
    }

    /**
     * Start a project component from a specific JAR file.
     * Supports foreground (inherited IO) and background modes.
     *
     * @param instanceName registry name for this instance
     * @param type         component type (e.g., "kompile-app-main")
     * @param jarFile      path to the JAR or native executable
     * @param port         port to bind
     * @param workDir      working directory for the process
     * @param jvmArgs      extra JVM flags (may be null)
     * @param appArgs      extra application arguments (may be null)
     * @param logDir       directory for stdout/stderr log files (null = inherited IO)
     * @param foreground   true to inherit IO (blocking), false to redirect to log files
     * @return the started Process
     */
    public Process startProjectComponent(String instanceName, String type, File jarFile,
                                         int port, File workDir,
                                         List<String> jvmArgs, List<String> appArgs,
                                         File logDir, boolean foreground) throws IOException {
        List<String> command = new ArrayList<>();

        boolean isNative = !jarFile.getName().endsWith(".jar");
        if (isNative) {
            command.add(jarFile.getAbsolutePath());
        } else {
            command.add("java");
            if (jvmArgs != null) {
                command.addAll(jvmArgs);
            }
            boolean hasXmx = jvmArgs != null && jvmArgs.stream().anyMatch(arg -> arg.startsWith("-Xmx"));
            if (!hasXmx) {
                command.add("-Xmx4g");
            }
            command.add("-jar");
            command.add(jarFile.getAbsolutePath());
        }

        command.add("--server.port=" + port);

        if (appArgs != null) {
            command.addAll(appArgs);
        }

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(workDir);

        if (foreground) {
            pb.inheritIO();
        } else if (logDir != null) {
            logDir.mkdirs();
            pb.redirectOutput(new File(logDir, instanceName + ".out.log"));
            pb.redirectError(new File(logDir, instanceName + ".err.log"));
        }

        Process process = pb.start();

        InstanceInfo info = new InstanceInfo(instanceName, type, port, process.pid(),
                jarFile.getAbsolutePath(), workDir.getAbsolutePath());
        InstanceRegistry.register(info);

        return process;
    }

    /**
     * Stop a running instance by its registry name.
     */
    public ProcessResult stopByName(String instanceName) throws Exception {
        InstanceInfo info;
        try {
            info = InstanceRegistry.get(instanceName);
        } catch (IOException e) {
            return ProcessResult.error("Failed to read instance registry: " + e.getMessage());
        }

        if (info == null) {
            return ProcessResult.error("Instance not found: " + instanceName);
        }

        Optional<ProcessHandle> processOpt = ProcessHandle.of(info.getPid());
        if (!processOpt.isPresent() || !processOpt.get().isAlive()) {
            InstanceRegistry.unregister(instanceName);
            return ProcessResult.error("Process not running (stale registry entry removed)");
        }

        ProcessHandle process = processOpt.get();
        System.out.println("Stopping " + instanceName + " (PID: " + info.getPid() + ")...");

        process.destroy();
        try {
            process.onExit().get(10, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception e) {
            process.destroyForcibly();
        }

        InstanceRegistry.unregister(instanceName);
        System.out.println("  Stopped " + instanceName);
        return ProcessResult.success(instanceName, info.getPid(), info.getPort(), "stopped");
    }

    /**
     * Get the status of a named instance (as opposed to getComponentStatus which looks up by type).
     */
    public ComponentStatus getInstanceStatus(String instanceName) {
        try {
            InstanceInfo info = InstanceRegistry.get(instanceName);

            if (info == null) {
                return new ComponentStatus(instanceName, "not_running", false, null, null, null);
            }

            Optional<ProcessHandle> processOpt = ProcessHandle.of(info.getPid());
            boolean isAlive = processOpt.isPresent() && processOpt.get().isAlive();

            if (!isAlive) {
                InstanceRegistry.unregister(instanceName);
                return new ComponentStatus(instanceName, "dead", false, info.getPid(), info.getPort(), null);
            }

            boolean healthy = checkHealth(info.getPort());
            String status = healthy ? "running" : "unhealthy";
            return new ComponentStatus(instanceName, status, true, info.getPid(), info.getPort(), info.getUrl());

        } catch (Exception e) {
            return new ComponentStatus(instanceName, "error", false, null, null, e.getMessage());
        }
    }

    /**
     * Restart a component
     */
    public ProcessResult restartComponent(String componentId, int port, List<String> jvmArgs, List<String> appArgs) throws Exception {
        // Stop if running
        ProcessResult stopResult = stopComponent(componentId);
        if (stopResult.hasError()) {
            System.out.println("  " + stopResult.getMessage());
        }

        // Small delay to ensure port is freed
        Thread.sleep(2000);

        // Start
        return startComponent(componentId, port, jvmArgs, appArgs);
    }

    /**
     * Component status POJO
     */
    public static class ComponentStatus {
        private String componentId;
        private String status; // running, not_running, dead, unhealthy, error
        private boolean installed;
        private Long pid;
        private Integer port;
        private String url;
        private String message;

        public ComponentStatus(String componentId, String status, boolean installed, 
                               Long pid, Integer port, String urlOrMessage) {
            this.componentId = componentId;
            this.status = status;
            this.installed = installed;
            this.pid = pid;
            this.port = port;
            
            if (urlOrMessage != null && urlOrMessage.startsWith("http")) {
                this.url = urlOrMessage;
            } else {
                this.message = urlOrMessage;
            }
        }

        public String getComponentId() { return componentId; }
        public String getStatus() { return status; }
        public boolean isInstalled() { return installed; }
        public Optional<Long> getPid() { return Optional.ofNullable(pid); }
        public Optional<Integer> getPort() { return Optional.ofNullable(port); }
        public Optional<String> getUrl() { return Optional.ofNullable(url); }
        public Optional<String> getMessage() { return Optional.ofNullable(message); }

        public String getStatusIcon() {
            switch (status) {
                case "running": return "✓";
                case "unhealthy": return "⚠";
                case "not_running": return "✗";
                case "dead": return "✗";
                case "error": return "✗";
                default: return "?";
            }
        }
    }

    /**
     * Process result POJO
     */
    public static class ProcessResult {
        private boolean success;
        private String componentId;
        private Long pid;
        private Integer port;
        private String message;

        private ProcessResult(boolean success, String componentId, Long pid, Integer port, String message) {
            this.success = success;
            this.componentId = componentId;
            this.pid = pid;
            this.port = port;
            this.message = message;
        }

        public static ProcessResult success(String componentId, Long pid, Integer port) {
            return new ProcessResult(true, componentId, pid, port, "Success");
        }

        public static ProcessResult success(String componentId, Long pid, Integer port, String message) {
            return new ProcessResult(true, componentId, pid, port, message);
        }

        public static ProcessResult error(String message) {
            return new ProcessResult(false, null, null, null, message);
        }

        public static ProcessResult warning(String componentId, Long pid, Integer port, String message) {
            return new ProcessResult(true, componentId, pid, port, message);
        }

        public boolean isSuccess() { return success; }
        public boolean hasError() { return !success; }
        public String getComponentId() { return componentId; }
        public Optional<Long> getPid() { return Optional.ofNullable(pid); }
        public Optional<Integer> getPort() { return Optional.ofNullable(port); }
        public String getMessage() { return message; }
    }
}
