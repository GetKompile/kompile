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

import ai.kompile.app.config.DeviceRoutingConfig;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Manages the lifecycle of the kompile-model-staging server from within kompile-app-main.
 * Can check health, start, and stop the staging server process.
 *
 * <p>Resolves the staging executable in priority order:</p>
 * <ol>
 *   <li><b>Project-local native image:</b> {@code <projectDir>/staging/kompile-model-staging}</li>
 *   <li><b>Project-local JAR:</b> {@code <projectDir>/staging/kompile-model-staging.jar}</li>
 *   <li><b>Global install:</b> {@code ~/.kompile/components/kompile-model-staging/<version>/*.jar}</li>
 * </ol>
 *
 * <p>When launched from a project-local executable, the staging server's data directories
 * are pointed at the project ({@code <projectDir>/data/models/}) so each project maintains
 * its own model cache.</p>
 */
@Service
public class StagingServerLifecycleService {

    private static final Logger log = LoggerFactory.getLogger(StagingServerLifecycleService.class);
    private static final String COMPONENT_ID = "kompile-model-staging";
    private static final String NATIVE_EXECUTABLE_NAME = "kompile-model-staging";
    private static final String JAR_PREFIX = "kompile-model-staging";
    private static final int DEFAULT_PORT = 8090;
    private static final int HEALTH_CHECK_TIMEOUT_MS = 3000;
    private static final int STARTUP_TIMEOUT_SECONDS = 120;

    @org.springframework.beans.factory.annotation.Value("${kompile.staging.auto-start:true}")
    private boolean autoStartEnabled = true;

    @org.springframework.beans.factory.annotation.Value("${kompile.staging.port:8090}")
    private int configuredPort = DEFAULT_PORT;

    @org.springframework.beans.factory.annotation.Value("${kompile.staging.heap-size:4g}")
    private String heapSize = "4g";

    @Autowired(required = false)
    private DeviceRoutingConfigService deviceRoutingConfigService;

    private volatile Process managedProcess;
    private volatile long managedPid = -1;
    private volatile int managedPort = DEFAULT_PORT;
    private volatile StagingExecutable resolvedExecutable;
    private volatile boolean projectLocal;

    // ═══════════════════════════════════════════════════════════════════════════
    // EXECUTABLE RESOLUTION
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Type of staging executable found.
     */
    public enum ExecutableType {
        NATIVE_IMAGE,
        JAR
    }

    /**
     * A resolved staging server executable with its type and source location.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class StagingExecutable {
        private File path;
        private ExecutableType type;
        private boolean projectLocal;

        public boolean isNativeImage() {
            return type == ExecutableType.NATIVE_IMAGE;
        }
    }

    /**
     * Resolve the staging executable using the priority search order:
     * 1. Project-local native image: ./staging/kompile-model-staging
     * 2. Project-local JAR: ./staging/kompile-model-staging*.jar
     * 3. Global install: ~/.kompile/components/kompile-model-staging/<version>/*.jar
     */
    public StagingExecutable findStagingExecutable() {
        // 1. Project-local directory (working directory / staging/)
        File projectStagingDir = getProjectStagingDir();
        if (projectStagingDir != null && projectStagingDir.isDirectory()) {
            // Check for native image first
            File nativeExe = new File(projectStagingDir, NATIVE_EXECUTABLE_NAME);
            if (nativeExe.isFile() && nativeExe.canExecute()) {
                log.debug("Found project-local native staging executable: {}", nativeExe);
                return StagingExecutable.builder()
                        .path(nativeExe)
                        .type(ExecutableType.NATIVE_IMAGE)
                        .projectLocal(true)
                        .build();
            }

            // Check for JAR
            File[] jars = projectStagingDir.listFiles(
                    (dir, name) -> name.startsWith(JAR_PREFIX) && name.endsWith(".jar"));
            if (jars != null && jars.length > 0) {
                log.debug("Found project-local staging JAR: {}", jars[0]);
                return StagingExecutable.builder()
                        .path(jars[0])
                        .type(ExecutableType.JAR)
                        .projectLocal(true)
                        .build();
            }
        }

        // 2. Global install: ~/.kompile/components/kompile-model-staging/
        File globalJar = findGlobalJar();
        if (globalJar != null) {
            log.debug("Found global staging JAR: {}", globalJar);
            return StagingExecutable.builder()
                    .path(globalJar)
                    .type(ExecutableType.JAR)
                    .projectLocal(false)
                    .build();
        }

        return null;
    }

    /**
     * Get the project's staging directory.
     * Convention: ./staging/ relative to the working directory.
     */
    private File getProjectStagingDir() {
        String userDir = System.getProperty("user.dir");
        if (userDir == null) return null;
        File stagingDir = new File(userDir, "staging");
        return stagingDir.isDirectory() ? stagingDir : null;
    }

    /**
     * Get the project's data/models directory (for project-local staging).
     * Convention: ./data/models/ relative to the working directory.
     */
    public File getProjectModelsDir() {
        String userDir = System.getProperty("user.dir");
        if (userDir == null) return null;
        return new File(userDir, "data/models");
    }

    /**
     * Find the staging JAR in the global install location:
     * ~/.kompile/components/kompile-model-staging/<version>/
     */
    private File findGlobalJar() {
        String home = System.getProperty("user.home");
        File componentsDir = new File(home, ".kompile/components/" + COMPONENT_ID);
        if (!componentsDir.exists()) return null;

        File[] versionDirs = componentsDir.listFiles(File::isDirectory);
        if (versionDirs == null || versionDirs.length == 0) return null;

        // Use the latest version directory (sort descending)
        java.util.Arrays.sort(versionDirs, (a, b) -> b.getName().compareTo(a.getName()));

        for (File versionDir : versionDirs) {
            File[] jars = versionDir.listFiles(
                    (dir, name) -> name.endsWith(".jar") && name.startsWith(JAR_PREFIX));
            if (jars != null && jars.length > 0) {
                return jars[0];
            }
        }

        return null;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // STATUS & HEALTH
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Check if the staging server is reachable on the managed port.
     */
    public boolean isRunning() {
        return isRunning(managedPort);
    }

    public boolean isRunning(int port) {
        return checkHealth(port);
    }

    /**
     * Get comprehensive status of the staging server.
     */
    public StagingServerStatus getStatus() {
        return getStatus(managedPort);
    }

    public StagingServerStatus getStatus(int port) {
        StagingExecutable exe = findStagingExecutable();
        boolean installed = exe != null;
        boolean running = checkHealth(port);

        String statusStr;
        if (running) {
            statusStr = "running";
        } else if (managedProcess != null && managedProcess.isAlive()) {
            statusStr = "starting";
        } else if (installed) {
            statusStr = "stopped";
        } else {
            statusStr = "not_installed";
        }

        return StagingServerStatus.builder()
                .componentId(COMPONENT_ID)
                .status(statusStr)
                .installed(installed)
                .port(port)
                .pid(managedPid > 0 ? managedPid : null)
                .url(running ? "http://localhost:" + port : null)
                .executableType(exe != null ? exe.getType().name() : null)
                .projectLocal(exe != null && exe.isProjectLocal())
                .build();
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // START / STOP
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Start the staging server on the configured port.
     */
    public StartResult startServer() {
        return startServer(configuredPort);
    }

    /**
     * Start the staging server on the given port.
     * Resolves the executable (project-local first, then global), builds the
     * appropriate command, and launches the process.
     *
     * <p>When the executable is project-local, the staging server's data directories
     * are pointed at {@code <projectDir>/data/models/} so each project maintains
     * its own model cache.</p>
     */
    public StartResult startServer(int port) {
        // Check if already running
        if (checkHealth(port)) {
            return StartResult.builder()
                    .success(true)
                    .message("Staging server already running on port " + port)
                    .port(port)
                    .alreadyRunning(true)
                    .build();
        }

        // Find the executable
        StagingExecutable exe = findStagingExecutable();
        if (exe == null) {
            return StartResult.builder()
                    .success(false)
                    .message("kompile-model-staging not found. Searched:\n" +
                            "  1. ./staging/ (project-local native image or JAR)\n" +
                            "  2. ~/.kompile/components/" + COMPONENT_ID + "/ (global install)\n\n" +
                            "Install with: kompile install kompile-model-staging\n" +
                            "Or copy the JAR/native-image to ./staging/")
                    .build();
        }

        this.resolvedExecutable = exe;
        this.projectLocal = exe.isProjectLocal();

        // Write app-main's classpath to a file so the staging server can use it
        // when launching serving subprocesses for inference
        Path classpathFile = writeClasspathFile();

        // Build command
        List<String> command = buildCommand(exe, port, classpathFile);

        log.info("Starting kompile-model-staging ({} {}): {}",
                exe.getType(), exe.isProjectLocal() ? "project-local" : "global",
                String.join(" ", command));

        try {
            ProcessBuilder pb = new ProcessBuilder(command);

            // Redirect output to log files if possible
            File logDir = resolveLogDir();
            if (logDir != null) {
                logDir.mkdirs();
                pb.redirectOutput(new File(logDir, "staging-server.out.log"));
                pb.redirectError(new File(logDir, "staging-server.err.log"));
            } else {
                pb.redirectOutput(ProcessBuilder.Redirect.PIPE);
                pb.redirectError(ProcessBuilder.Redirect.PIPE);
            }

            managedProcess = pb.start();
            managedPid = managedProcess.pid();
            managedPort = port;

            log.info("kompile-model-staging started with PID {} on port {} ({})",
                    managedPid, port, exe.isProjectLocal() ? "project-local" : "global");

            // Register in ~/.kompile/instances/ so CLI can see it
            registerInstance(port, managedPid, exe);

            // Wait for health
            boolean healthy = waitForHealth(port, STARTUP_TIMEOUT_SECONDS);

            if (healthy) {
                log.info("kompile-model-staging is healthy on port {}", port);
                return StartResult.builder()
                        .success(true)
                        .message("Staging server started successfully on port " + port +
                                " (" + exe.getType() + ", " +
                                (exe.isProjectLocal() ? "project-local" : "global") + ")")
                        .port(port)
                        .pid(managedPid)
                        .executableType(exe.getType().name())
                        .projectLocal(exe.isProjectLocal())
                        .build();
            } else {
                log.warn("kompile-model-staging started but health check failed on port {}", port);
                return StartResult.builder()
                        .success(true)
                        .message("Staging server started (PID " + managedPid +
                                ") but health check timed out. It may still be initializing.")
                        .port(port)
                        .pid(managedPid)
                        .executableType(exe.getType().name())
                        .projectLocal(exe.isProjectLocal())
                        .build();
            }

        } catch (IOException e) {
            log.error("Failed to start kompile-model-staging", e);
            return StartResult.builder()
                    .success(false)
                    .message("Failed to start staging server: " + e.getMessage())
                    .build();
        }
    }

    /**
     * Build the launch command for the given executable and port.
     *
     * <p>Passes the app-main's classpath and backend config to the staging server
     * so it can launch serving subprocesses with the correct ND4J backend.</p>
     */
    private List<String> buildCommand(StagingExecutable exe, int port, Path classpathFile) {
        List<String> command = new ArrayList<>();

        if (exe.isNativeImage()) {
            command.add(exe.getPath().getAbsolutePath());
        } else {
            command.add("java");
            command.add("-Xmx" + heapSize);
            command.add("-jar");
            command.add(exe.getPath().getAbsolutePath());
        }

        command.add("--server");
        command.add("--server.port=" + port);

        // Pass app-main's classpath file so the staging server can use it
        // when launching serving subprocesses for inference
        if (classpathFile != null) {
            command.add("--kompile.subprocess.classpath-file=" + classpathFile.toAbsolutePath());
        }

        // Check device routing config to determine if LLM subprocess needs CUDA
        boolean needsCuda = detectNeedsCuda();
        command.add("--kompile.subprocess.needs-cuda=" + needsCuda);
        log.info("Passing subprocess config to staging server: needsCuda={}, classpathFile={}",
                needsCuda, classpathFile);

        // Point data directories at the project when project-local
        if (exe.isProjectLocal()) {
            File modelsDir = getProjectModelsDir();
            if (modelsDir != null) {
                command.add("--kompile.staging.model-dir=" + modelsDir.getAbsolutePath());
                command.add("--kompile.staging.staging-dir=" +
                        new File(modelsDir, ".staging").getAbsolutePath());
            }
        }

        return command;
    }

    /**
     * Write the app-main's effective classpath to a file that the staging server
     * can read when launching serving subprocesses.
     *
     * @return the path to the classpath file, or null if writing fails
     */
    private Path writeClasspathFile() {
        try {
            String classpath = collectEffectiveClasspath();
            File logDir = resolveLogDir();
            Path cpFile;
            if (logDir != null) {
                logDir.mkdirs();
                cpFile = new File(logDir, "subprocess-classpath.txt").toPath();
            } else {
                cpFile = Files.createTempFile("subprocess-classpath-", ".txt");
            }
            Files.writeString(cpFile, classpath);
            log.info("Wrote app-main classpath ({} chars) to {}", classpath.length(), cpFile);
            return cpFile;
        } catch (IOException e) {
            log.warn("Failed to write classpath file: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Collect the effective classpath of this (app-main) JVM process.
     * Includes java.class.path and classloader URLs.
     */
    private String collectEffectiveClasspath() {
        Set<String> entries = new java.util.LinkedHashSet<>();
        String pathSeparator = System.getProperty("path.separator");

        // java.class.path
        String systemCp = System.getProperty("java.class.path");
        if (systemCp != null && !systemCp.isBlank()) {
            for (String entry : systemCp.split(pathSeparator)) {
                if (!entry.isBlank()) entries.add(entry.trim());
            }
        }

        // Walk classloader hierarchy
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        if (cl == null) cl = getClass().getClassLoader();
        while (cl != null) {
            if (cl instanceof java.net.URLClassLoader urlCl) {
                for (java.net.URL url : urlCl.getURLs()) {
                    try {
                        String path = url.toURI().getPath();
                        if (path != null && !path.isBlank()) entries.add(path);
                    } catch (Exception e) {
                        String s = url.toString();
                        if (s.startsWith("file:")) entries.add(s.substring(5));
                    }
                }
            } else {
                try {
                    java.lang.reflect.Method getUrls = cl.getClass().getMethod("getURLs");
                    Object result = getUrls.invoke(cl);
                    if (result instanceof java.net.URL[] urls) {
                        for (java.net.URL url : urls) {
                            try {
                                String path = url.toURI().getPath();
                                if (path != null && !path.isBlank()) entries.add(path);
                            } catch (Exception e) {
                                String s = url.toString();
                                if (s.startsWith("file:")) entries.add(s.substring(5));
                            }
                        }
                    }
                } catch (NoSuchMethodException ignored) {
                } catch (Exception e) {
                    log.debug("Could not extract URLs from classloader {}: {}", cl.getClass().getName(), e.getMessage());
                }
            }
            cl = cl.getParent();
        }

        return String.join(pathSeparator, entries);
    }

    /**
     * Determine if the LLM subprocess needs CUDA by checking the device routing
     * config (persisted JSON at ~/.kompile/config/device-routing-config.json).
     *
     * <p>This does NOT check the current JVM's classpath — app-main runs on CPU
     * (nd4j-native) but the subprocess needs CUDA from ~/.m2/repository.
     * SubprocessBackendResolver handles finding the CUDA JARs.</p>
     */
    private boolean detectNeedsCuda() {
        if (deviceRoutingConfigService != null) {
            try {
                DeviceRoutingConfig routingConfig = deviceRoutingConfigService.getConfiguration();
                if (routingConfig.hasRouteFor(DeviceRoutingConfig.SERVICE_LLM)) {
                    DeviceRoutingConfig.ServiceDeviceConfig llmRoute =
                            routingConfig.serviceRoutes().get(DeviceRoutingConfig.SERVICE_LLM);
                    boolean cuda = "cuda".equalsIgnoreCase(llmRoute.deviceType());
                    log.info("Device routing config says LLM service needs CUDA: {}", cuda);
                    return cuda;
                }
            } catch (Exception e) {
                log.debug("Could not check device routing for LLM backend: {}", e.getMessage());
            }
        }
        // Default: true — SubprocessBackendResolver gracefully falls back to CPU
        // if CUDA JARs aren't found in ~/.m2/repository
        log.info("No device routing config for LLM service, defaulting needsCuda=true");
        return true;
    }

    /**
     * Resolve the log directory for staging server output.
     */
    private File resolveLogDir() {
        // Try project-local first
        String userDir = System.getProperty("user.dir");
        if (userDir != null) {
            File projectLogs = new File(userDir, "data/logs");
            if (projectLogs.isDirectory() || projectLogs.mkdirs()) {
                return projectLogs;
            }
        }

        // Fall back to ~/.kompile/logs
        String home = System.getProperty("user.home");
        if (home != null) {
            File globalLogs = new File(home, ".kompile/logs");
            if (globalLogs.isDirectory() || globalLogs.mkdirs()) {
                return globalLogs;
            }
        }

        return null;
    }

    /**
     * Stop the managed staging server process.
     */
    public StopResult stopServer() {
        if (managedProcess == null || !managedProcess.isAlive()) {
            return StopResult.builder()
                    .success(false)
                    .message("No managed staging server process to stop")
                    .build();
        }

        long pid = managedPid;
        managedProcess.destroy();

        // Wait up to 10 seconds for graceful shutdown
        try {
            boolean exited = managedProcess.waitFor(10, java.util.concurrent.TimeUnit.SECONDS);
            if (!exited) {
                log.warn("Staging server did not stop gracefully, force killing PID {}", pid);
                managedProcess.destroyForcibly();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            managedProcess.destroyForcibly();
        }

        managedProcess = null;
        managedPid = -1;

        // Unregister from ~/.kompile/instances/
        unregisterInstance();

        return StopResult.builder()
                .success(true)
                .message("Staging server stopped (was PID " + pid + ")")
                .pid(pid)
                .build();
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // REMOTE API CALLS
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Stage a model from the catalog on the staging server.
     */
    public String stageModelFromCatalog(int port, String modelId) throws IOException {
        URL url = new URL("http://localhost:" + port + "/api/staging/stage/catalog/" + modelId);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setConnectTimeout(HEALTH_CHECK_TIMEOUT_MS);
        conn.setReadTimeout(30_000); // staging can take a while

        int responseCode = conn.getResponseCode();
        String response = new String(
                responseCode < 400 ? conn.getInputStream().readAllBytes() : conn.getErrorStream().readAllBytes()
        );
        conn.disconnect();

        if (responseCode >= 400) {
            throw new IOException("Stage model failed (HTTP " + responseCode + "): " + response);
        }
        return response;
    }

    /**
     * Get the catalog of available models from the staging server.
     */
    public String getCatalog(int port) throws IOException {
        URL url = new URL("http://localhost:" + port + "/api/staging/catalog");
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(HEALTH_CHECK_TIMEOUT_MS);
        conn.setReadTimeout(HEALTH_CHECK_TIMEOUT_MS);

        int responseCode = conn.getResponseCode();
        String response = new String(conn.getInputStream().readAllBytes());
        conn.disconnect();

        if (responseCode >= 400) {
            throw new IOException("Get catalog failed (HTTP " + responseCode + ")");
        }
        return response;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // PUBLIC ACCESSORS
    // ═══════════════════════════════════════════════════════════════════════════

    public boolean isAutoStartEnabled() {
        return autoStartEnabled;
    }

    public int getConfiguredPort() {
        return configuredPort;
    }

    public boolean isProjectLocal() {
        return projectLocal;
    }

    public int getManagedPort() {
        return managedPort;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // INSTANCE REGISTRY (writes JSON compatible with CLI's InstanceRegistry)
    // ═══════════════════════════════════════════════════════════════════════════

    private static final String INSTANCE_NAME = "kompile-model-staging-auto";

    /**
     * Write a JSON file to ~/.kompile/instances/ so the CLI's `kompile manage list`
     * can discover staging servers started by app-main's auto-start.
     */
    private void registerInstance(int port, long pid, StagingExecutable exe) {
        try {
            Path instancesDir = Path.of(System.getProperty("user.home"), ".kompile", "instances");
            Files.createDirectories(instancesDir);
            Path instanceFile = instancesDir.resolve(INSTANCE_NAME + ".json");
            String json = String.format(
                    "{\"name\":\"%s\",\"type\":\"staging\",\"port\":%d,\"pid\":%d," +
                    "\"jarPath\":\"%s\",\"startedAt\":\"%s\",\"url\":\"http://localhost:%d\"}",
                    INSTANCE_NAME, port, pid,
                    exe.getPath().getAbsolutePath().replace("\\", "\\\\"),
                    java.time.Instant.now().toString(), port);
            Files.writeString(instanceFile, json);
            log.debug("Registered staging instance in {}", instanceFile);
        } catch (IOException e) {
            log.debug("Could not register staging instance: {}", e.getMessage());
        }
    }

    /**
     * Remove the instance JSON file on shutdown.
     */
    private void unregisterInstance() {
        try {
            Path instanceFile = Path.of(System.getProperty("user.home"), ".kompile", "instances", INSTANCE_NAME + ".json");
            Files.deleteIfExists(instanceFile);
            log.debug("Unregistered staging instance");
        } catch (IOException e) {
            log.debug("Could not unregister staging instance: {}", e.getMessage());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // PRIVATE HELPERS
    // ═══════════════════════════════════════════════════════════════════════════

    private boolean checkHealth(int port) {
        try {
            URL url = new URL("http://localhost:" + port + "/actuator/health");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(HEALTH_CHECK_TIMEOUT_MS);
            conn.setReadTimeout(HEALTH_CHECK_TIMEOUT_MS);
            int code = conn.getResponseCode();
            conn.disconnect();
            return code == 200;
        } catch (Exception e) {
            return false;
        }
    }

    private boolean waitForHealth(int port, int timeoutSeconds) {
        long deadline = System.currentTimeMillis() + (timeoutSeconds * 1000L);
        while (System.currentTimeMillis() < deadline) {
            if (checkHealth(port)) {
                return true;
            }
            try {
                Thread.sleep(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // DATA CLASSES
    // ═══════════════════════════════════════════════════════════════════════════

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class StagingServerStatus {
        private String componentId;
        private String status; // running, stopped, starting, not_installed
        private boolean installed;
        private int port;
        private Long pid;
        private String url;
        private String executableType; // NATIVE_IMAGE or JAR
        private boolean projectLocal;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class StartResult {
        private boolean success;
        private String message;
        private Integer port;
        private Long pid;
        private boolean alreadyRunning;
        private String executableType;
        private boolean projectLocal;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class StopResult {
        private boolean success;
        private String message;
        private Long pid;
    }
}
