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

package ai.kompile.pipeline.serving.launcher;

import ai.kompile.app.subprocess.BackendConfigurable;
import ai.kompile.app.subprocess.SubprocessEnvironmentPropagator;
import ai.kompile.app.subprocess.SubprocessPlacement;
import ai.kompile.app.subprocess.SubprocessPlacementSupport;
import ai.kompile.pipeline.serving.definition.UnifiedPipelineDefinition;
import ai.kompile.pipeline.serving.subprocess.PipelineServingMessage;
import ai.kompile.pipeline.serving.subprocess.PipelineServingSubprocessArgs;
import ai.kompile.cli.common.util.JavaRuntimeLocator;
import ai.kompile.cli.common.util.JsonUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Launches and manages pipeline serving subprocesses.
 *
 * <p>Supports two modes:</p>
 * <ul>
 *   <li><b>ONE_SHOT</b>: Launch subprocess, execute pipeline once, return result, subprocess exits</li>
 *   <li><b>PERSISTENT_SERVING</b>: Launch subprocess with HTTP server, keep alive for repeated invocations</li>
 * </ul>
 *
 * <p>Follows the same patterns as {@code ServingSubprocessLauncher} and
 * {@code SubprocessIngestLauncher} in kompile-app-main:</p>
 * <ul>
 *   <li>Args serialized to temp JSON file</li>
 *   <li>Stdout protocol (PIPELINE_MSG:) for IPC</li>
 *   <li>Heartbeat-based liveness detection</li>
 *   <li>Subprocess registry integration</li>
 * </ul>
 */
public class PipelineSubprocessLauncher implements BackendConfigurable {

    private static final Logger logger = LoggerFactory.getLogger(PipelineSubprocessLauncher.class);

    /** Shared device-agnostic placement (same base infra every subprocess uses). */
    private final SubprocessPlacementSupport placement = new SubprocessPlacementSupport();

    /** {@link BackendConfigurable} — the scheduler assigns backend/device/memory before spawn. */
    @Override
    public void applyPlacement(SubprocessPlacement p) {
        this.placement.applyPlacement(p);
    }

    private static final long READY_POLL_TIMEOUT_MS = 120_000L;
    private static final long READY_POLL_INTERVAL_MS = 500L;

    private static final String[] FORWARDED_PROPERTY_PREFIXES = {
            "org.nd4j.", "org.bytedeco.", "nd4j.", "cuda.", "cudnn.", "openblas.", "mkl."
    };

    private final ConcurrentHashMap<String, PipelineServingHandle> activeHandles = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicLong> lastHeartbeats = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper = JsonUtils.standardMapper();
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    /**
     * Launch a subprocess for one-shot pipeline execution.
     * Blocks until the subprocess completes and returns the result.
     */
    public Map<String, Object> launchOneShot(UnifiedPipelineDefinition definition,
                                             Map<String, Object> input) throws Exception {
        String taskId = UUID.randomUUID().toString();

        PipelineServingSubprocessArgs args = buildArgs(
                taskId, definition, PipelineServingSubprocessArgs.MODE_ONE_SHOT,
                input != null ? objectMapper.writeValueAsString(input) : null,
                0
        );

        Path argsFile = args.writeToTempFile();
        Process process = null;
        Thread reader = null;
        Thread errReader = null;
        try {
            List<String> command = buildCommand(definition, argsFile);
            logger.info("Launching one-shot pipeline subprocess for '{}': {}", definition.getPipelineId(), taskId);

            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(false);
            propagateEnvironment(pb.environment());

            process = pb.start();
            Process child = process;

            // Read stdout for PIPELINE_MSG: messages
            CompletableFuture<Map<String, Object>> resultFuture = new CompletableFuture<>();

            reader = new Thread(() -> readOneShotOutput(child, resultFuture), "pipeline-oneshot-reader-" + taskId);
            reader.setDaemon(true);
            reader.start();

            // Also drain stderr
            errReader = new Thread(() -> drainStderr(child, definition.getPipelineId()), "pipeline-oneshot-stderr-" + taskId);
            errReader.setDaemon(true);
            errReader.start();

            // Wait for completion with timeout (5 minutes for one-shot)
            Map<String, Object> result = resultFuture.get(5, TimeUnit.MINUTES);
            process.waitFor(10, TimeUnit.SECONDS);
            return result;
        } finally {
            stopChild(process);
            joinQuietly(reader);
            joinQuietly(errReader);
            Files.deleteIfExists(argsFile);
        }
    }

    /**
     * Launch a persistent serving subprocess.
     * Returns immediately after the subprocess reports READY.
     */
    public PipelineServingHandle launchPersistentServing(UnifiedPipelineDefinition definition) throws Exception {
        String pipelineId = definition.getPipelineId();

        // Check if already serving
        PipelineServingHandle existing = activeHandles.get(pipelineId);
        if (existing != null && existing.isAlive()) {
            logger.info("Pipeline '{}' already serving on port {}", pipelineId, existing.port());
            return existing;
        }

        String taskId = UUID.randomUUID().toString();
        int port = definition.getServing() != null && definition.getServing().getPort() > 0 ?
                definition.getServing().getPort() : findAvailablePort();

        PipelineServingSubprocessArgs args = buildArgs(
                taskId, definition, PipelineServingSubprocessArgs.MODE_PERSISTENT_SERVING,
                null, port
        );

        Path argsFile = args.writeToTempFile();
        List<String> command = buildCommand(definition, argsFile);

        logger.info("Launching persistent pipeline serving for '{}' on port {}: {}", pipelineId, port, taskId);

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(false);
        propagateEnvironment(pb.environment());

        Process process = pb.start();

        // Start stdout reader to parse PIPELINE_MSG: lines
        lastHeartbeats.put(pipelineId, new AtomicLong(System.currentTimeMillis()));
        Thread reader = new Thread(() -> readServingOutput(process, pipelineId), "pipeline-serving-reader-" + pipelineId);
        reader.setDaemon(true);
        reader.start();

        // Drain stderr
        Thread errReader = new Thread(() -> drainStderr(process, pipelineId), "pipeline-serving-stderr-" + pipelineId);
        errReader.setDaemon(true);
        errReader.start();

        try {
            // The child consumes its args file during startup; remove it on both success and failure.
            waitForReady(port);

            long pid = process.pid();
            PipelineServingHandle handle = new PipelineServingHandle(
                    pipelineId,
                    definition.getKind() != null ? definition.getKind().name() : "GENERIC",
                    process, port, pid, Instant.now(), taskId
            );
            activeHandles.put(pipelineId, handle);

            logger.info("Pipeline '{}' serving on port {} (pid={})", pipelineId, port, pid);
            return handle;
        } catch (Exception e) {
            lastHeartbeats.remove(pipelineId);
            stopChild(process);
            joinQuietly(reader);
            joinQuietly(errReader);
            throw e;
        } finally {
            Files.deleteIfExists(argsFile);
        }
    }

    /**
     * Stop a persistent serving subprocess.
     */
    public boolean stopServing(String pipelineId) {
        PipelineServingHandle handle = activeHandles.remove(pipelineId);
        lastHeartbeats.remove(pipelineId);
        if (handle == null) {
            return false;
        }

        logger.info("Stopping pipeline serving for '{}' (pid={})", pipelineId, handle.pid());
        Process process = handle.process();
        if (process != null && process.isAlive()) {
            process.destroy();
            try {
                if (!process.waitFor(10, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                }
            } catch (InterruptedException e) {
                process.destroyForcibly();
                Thread.currentThread().interrupt();
            }
        }
        return true;
    }

    /**
     * Invoke a pipeline that is being served persistently.
     */
    public Map<String, Object> invokeServed(String pipelineId, Map<String, Object> input) throws Exception {
        PipelineServingHandle handle = activeHandles.get(pipelineId);
        if (handle == null || !handle.isAlive()) {
            throw new IllegalStateException("Pipeline '" + pipelineId + "' is not being served");
        }

        String url = handle.baseUrl() + "/predict";
        byte[] body = input != null ? objectMapper.writeValueAsBytes(input) : new byte[0];

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .timeout(Duration.ofMinutes(5))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new RuntimeException("Pipeline invocation failed (HTTP " + response.statusCode() + "): " + response.body());
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> result = objectMapper.readValue(response.body(), Map.class);
        return result;
    }

    /**
     * Get all active serving handles.
     */
    public Map<String, PipelineServingHandle> getActiveHandles() {
        return Collections.unmodifiableMap(activeHandles);
    }

    /**
     * Check if a pipeline is actively being served.
     */
    public boolean isServing(String pipelineId) {
        PipelineServingHandle handle = activeHandles.get(pipelineId);
        return handle != null && handle.isAlive();
    }

    @PreDestroy
    public void shutdown() {
        logger.info("Shutting down pipeline subprocess launcher, stopping {} active subprocesses",
                activeHandles.size());
        for (String pipelineId : new ArrayList<>(activeHandles.keySet())) {
            stopServing(pipelineId);
        }
    }

    // ── Private ──────────────────────────────────────────────────────────────

    private PipelineServingSubprocessArgs buildArgs(String taskId,
                                                   UnifiedPipelineDefinition definition,
                                                   String mode,
                                                   String requestDataJson,
                                                   int port) throws Exception {
        UnifiedPipelineDefinition.ServingConfig serving = definition.getServing() != null ?
                definition.getServing() : UnifiedPipelineDefinition.ServingConfig.builder().build();

        return new PipelineServingSubprocessArgs(
                taskId,
                objectMapper.writeValueAsString(definition),
                mode,
                requestDataJson,
                port,
                captureNd4jConfigFromSystemProperties(),
                serving.getMemoryStopPercent(),
                serving.getMemoryCriticalPercent(),
                serving.getMemoryKillPercent(),
                5000L,
                serving.getGpuStopPercent(),
                serving.getGpuCriticalPercent(),
                serving.getGpuKillPercent(),
                serving.getHeartbeatIntervalMs(),
                null // callbackBaseUrl
        );
    }

    private List<String> buildCommand(UnifiedPipelineDefinition definition, Path argsFile)
            throws IOException {
        LauncherArtifact launcher = resolveLauncher();
        UnifiedPipelineDefinition.ServingConfig serving = definition.getServing() != null ?
                definition.getServing() : UnifiedPipelineDefinition.ServingConfig.builder().build();

        List<String> cmd = new ArrayList<>();
        if (launcher.nativeExecutable()) {
            cmd.add(launcher.path().toString());
        } else {
            cmd.add(JavaRuntimeLocator.javaExecutable());
            cmd.add("-Xmx" + serving.getHeapSize());
            cmd.add("-XX:+UseG1GC");
            cmd.add("-XX:MaxGCPauseMillis=200");
            cmd.add("-XX:+ExitOnOutOfMemoryError");
            cmd.add("-Dorg.bytedeco.javacpp.nopointergc=true");

            // JVM properties and placement flags apply to the executable-JAR tier only.
            for (String prefix : FORWARDED_PROPERTY_PREFIXES) {
                Properties sysProps = System.getProperties();
                for (String key : sysProps.stringPropertyNames()) {
                    if (key.startsWith(prefix)) {
                        cmd.add("-D" + key + "=" + sysProps.getProperty(key));
                    }
                }
            }
            cmd.addAll(placement.jvmFlags());
            cmd.add("-jar");
            cmd.add(launcher.path().toString());
        }
        cmd.add(argsFile.toAbsolutePath().normalize().toString());
        return cmd;
    }

    record LauncherArtifact(Path path, boolean nativeExecutable) {
        LauncherArtifact {
            path = path.toAbsolutePath().normalize();
        }
    }

    private LauncherArtifact resolveLauncher() throws IOException {
        String configuredExecutable = firstNonBlank(
                System.getProperty("kompile.pipeline.serving.executable"),
                System.getenv("KOMPILE_PIPELINE_SERVING_EXECUTABLE"));
        if (configuredExecutable != null) {
            Path executable = Path.of(configuredExecutable).toAbsolutePath().normalize();
            if (!Files.isRegularFile(executable) || !Files.isExecutable(executable)) {
                throw new IOException("Configured pipeline-serving executable is not runnable: " + executable);
            }
            return new LauncherArtifact(executable, true);
        }

        String configuredJar = firstNonBlank(
                System.getProperty("kompile.pipeline.serving.jar"),
                System.getenv("KOMPILE_PIPELINE_SERVING_JAR"));
        if (configuredJar != null) {
            Path jar = Path.of(configuredJar).toAbsolutePath().normalize();
            if (!Files.isRegularFile(jar)) {
                throw new IOException("Configured pipeline-serving executable JAR does not exist: " + jar);
            }
            return new LauncherArtifact(jar, false);
        }

        String executableName = System.getProperty("os.name", "").toLowerCase().contains("win")
                ? "kompile-pipeline-serving.exe" : "kompile-pipeline-serving";
        List<Path> executableCandidates = new ArrayList<>();
        addDistributionCandidates(executableCandidates, executableName, "bin");
        for (Path candidate : executableCandidates) {
            if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)) {
                return new LauncherArtifact(candidate, true);
            }
        }

        List<Path> jarCandidates = new ArrayList<>();
        addDistributionCandidates(jarCandidates, "kompile-pipeline-serving-exec.jar", "lib");
        Path developmentJar = findDevelopmentExecJar();
        if (developmentJar != null) {
            jarCandidates.add(developmentJar);
        }
        for (Path candidate : jarCandidates) {
            if (Files.isRegularFile(candidate)) {
                return new LauncherArtifact(candidate, false);
            }
        }

        throw new IOException("No standalone pipeline-serving runtime found. Install "
                + executableName + ", package kompile-pipeline-serving-*-exec.jar, or configure "
                + "KOMPILE_PIPELINE_SERVING_EXECUTABLE / KOMPILE_PIPELINE_SERVING_JAR.");
    }

    private void addDistributionCandidates(List<Path> candidates, String name, String directory) {
        String installDir = System.getenv("KOMPILE_INSTALL_DIR");
        if (installDir != null && !installDir.isBlank()) {
            candidates.add(Path.of(installDir).resolve(directory).resolve(name));
        }
        ProcessHandle.current().info().command().ifPresent(command -> {
            Path executable = Path.of(command).toAbsolutePath().normalize();
            Path bin = executable.getParent();
            if (bin != null && bin.getParent() != null) {
                candidates.add(bin.getParent().resolve(directory).resolve(name));
            }
        });
        candidates.add(Path.of(System.getProperty("user.home"), ".kompile", directory, name));
    }

    private Path findDevelopmentExecJar() {
        Path cursor = Path.of(System.getProperty("user.dir", ".")).toAbsolutePath().normalize();
        for (int i = 0; i < 8 && cursor != null; i++, cursor = cursor.getParent()) {
            Path target = cursor.resolve("kompile-app")
                    .resolve("kompile-data")
                    .resolve("kompile-pipelines")
                    .resolve("kompile-pipeline-serving")
                    .resolve("target");
            if (!Files.isDirectory(target)) {
                continue;
            }
            try (var files = Files.list(target)) {
                Path match = files.filter(Files::isRegularFile)
                        .filter(path -> path.getFileName().toString().endsWith("-exec.jar"))
                        .findFirst().orElse(null);
                if (match != null) {
                    return match.toAbsolutePath().normalize();
                }
            } catch (IOException ignored) {
                // Continue walking toward the workspace root.
            }
        }
        return null;
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }

    private static void stopChild(Process process) {
        if (process == null || !process.isAlive()) {
            return;
        }
        process.destroy();
        try {
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
            }
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
        }
    }

    private static void joinQuietly(Thread thread) {
        if (thread == null) {
            return;
        }
        try {
            thread.join(1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void propagateEnvironment(Map<String, String> env) {
        SubprocessEnvironmentPropagator.propagateToEnvironment(env);
        // Device-agnostic per-device memory bound (SD_MAX_DEVICE_BYTES) — shared base infra.
        placement.applyEnv(env);
    }

    private void waitForReady(int port) throws Exception {
        String healthUrl = "http://localhost:" + port + "/health";
        long deadline = System.currentTimeMillis() + READY_POLL_TIMEOUT_MS;

        while (System.currentTimeMillis() < deadline) {
            try {
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(healthUrl))
                        .GET()
                        .timeout(Duration.ofSeconds(2))
                        .build();
                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() == 200) {
                    logger.debug("Pipeline subprocess health check passed on port {}", port);
                    return;
                }
            } catch (Exception e) {
                // Not ready yet
            }
            Thread.sleep(READY_POLL_INTERVAL_MS);
        }
        throw new TimeoutException("Pipeline subprocess did not become ready within " + READY_POLL_TIMEOUT_MS + "ms");
    }

    private void readOneShotOutput(Process process, CompletableFuture<Map<String, Object>> resultFuture) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith(PipelineServingMessage.MESSAGE_PREFIX)) {
                    String json = line.substring(PipelineServingMessage.MESSAGE_PREFIX.length());
                    PipelineServingMessage msg = objectMapper.readValue(json, PipelineServingMessage.class);

                    if (msg instanceof PipelineServingMessage.Completed completed) {
                        Map<String, Object> result = new HashMap<>();
                        result.put("status", "COMPLETED");
                        result.put("requestId", completed.requestId());
                        result.put("durationMs", completed.durationMs());
                        result.put("output", completed.outputData());
                        resultFuture.complete(result);
                        return;
                    } else if (msg instanceof PipelineServingMessage.Failed failed) {
                        resultFuture.completeExceptionally(
                                new RuntimeException("Pipeline failed in phase '" + failed.phase() +
                                        "': " + failed.errorMessage()));
                        return;
                    } else if (msg instanceof PipelineServingMessage.Progress progress) {
                        logger.debug("[{}] Progress: {} {}% - {}", progress.taskId(),
                                progress.phase(), progress.progressPercent(), progress.message());
                    }
                }
            }
            // Process ended without completing
            if (!resultFuture.isDone()) {
                resultFuture.completeExceptionally(
                        new RuntimeException("Pipeline subprocess exited without completing"));
            }
        } catch (Exception e) {
            if (!resultFuture.isDone()) {
                resultFuture.completeExceptionally(e);
            }
        }
    }

    private void readServingOutput(Process process, String pipelineId) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith(PipelineServingMessage.MESSAGE_PREFIX)) {
                    String json = line.substring(PipelineServingMessage.MESSAGE_PREFIX.length());
                    try {
                        PipelineServingMessage msg = objectMapper.readValue(json, PipelineServingMessage.class);
                        handleServingMessage(pipelineId, msg);
                    } catch (Exception e) {
                        logger.debug("Failed to parse pipeline message: {}", e.getMessage());
                    }
                }
            }
        } catch (Exception e) {
            logger.debug("Pipeline serving stdout reader ended for '{}': {}", pipelineId, e.getMessage());
        }
    }

    private void handleServingMessage(String pipelineId, PipelineServingMessage msg) {
        if (msg instanceof PipelineServingMessage.Heartbeat) {
            AtomicLong lastHb = lastHeartbeats.get(pipelineId);
            if (lastHb != null) {
                lastHb.set(System.currentTimeMillis());
            }
        } else if (msg instanceof PipelineServingMessage.Failed failed) {
            logger.error("Pipeline '{}' failed in phase '{}': {}", pipelineId, failed.phase(), failed.errorMessage());
            activeHandles.remove(pipelineId);
        } else if (msg instanceof PipelineServingMessage.RequestResult result) {
            if (!result.success()) {
                logger.warn("Pipeline '{}' request {} failed: {}", pipelineId, result.requestId(), result.errorMessage());
            }
        }
    }

    private void drainStderr(Process process, String pipelineId) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getErrorStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                logger.debug("[pipeline:{}] {}", pipelineId, line);
            }
        } catch (Exception e) {
            // Expected when process ends
        }
    }

    private int findAvailablePort() {
        try (var socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            return 9090; // fallback
        }
    }

    /**
     * Capture ND4J-relevant system properties as a JSON string for subprocess forwarding.
     * Returns null if no relevant properties are set or serialization fails.
     */
    private String captureNd4jConfigFromSystemProperties() {
        try {
            Map<String, String> nd4jProps = new LinkedHashMap<>();
            for (Map.Entry<Object, Object> entry : System.getProperties().entrySet()) {
                String key = entry.getKey().toString();
                for (String prefix : FORWARDED_PROPERTY_PREFIXES) {
                    if (key.startsWith(prefix)) {
                        nd4jProps.put(key, entry.getValue().toString());
                        break;
                    }
                }
            }
            if (nd4jProps.isEmpty()) {
                return null;
            }
            return objectMapper.writeValueAsString(nd4jProps);
        } catch (Exception e) {
            logger.warn("Failed to capture ND4J config from system properties: {}", e.getMessage());
            return null;
        }
    }
}
