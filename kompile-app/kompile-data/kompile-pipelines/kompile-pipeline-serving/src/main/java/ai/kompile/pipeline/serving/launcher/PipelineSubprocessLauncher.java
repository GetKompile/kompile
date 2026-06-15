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

import ai.kompile.pipeline.serving.definition.UnifiedPipelineDefinition;
import ai.kompile.pipeline.serving.subprocess.PipelineServingMessage;
import ai.kompile.pipeline.serving.subprocess.PipelineServingSubprocessArgs;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
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
public class PipelineSubprocessLauncher {

    private static final Logger logger = LoggerFactory.getLogger(PipelineSubprocessLauncher.class);

    private static final String SUBPROCESS_MAIN_CLASS =
            "ai.kompile.pipeline.serving.subprocess.PipelineServingSubprocessMain";

    private static final long READY_POLL_TIMEOUT_MS = 120_000L;
    private static final long READY_POLL_INTERVAL_MS = 500L;

    private static final String[] FORWARDED_PROPERTY_PREFIXES = {
            "org.nd4j.", "org.bytedeco.", "nd4j.", "cuda.", "cudnn.", "openblas.", "mkl."
    };

    private final ConcurrentHashMap<String, PipelineServingHandle> activeHandles = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicLong> lastHeartbeats = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper = new ObjectMapper();
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
        try {
            List<String> command = buildCommand(definition, argsFile);
            logger.info("Launching one-shot pipeline subprocess for '{}': {}", definition.getPipelineId(), taskId);

            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(false);
            propagateEnvironment(pb.environment());

            Process process = pb.start();

            // Read stdout for PIPELINE_MSG: messages
            CompletableFuture<Map<String, Object>> resultFuture = new CompletableFuture<>();

            Thread reader = new Thread(() -> readOneShotOutput(process, resultFuture), "pipeline-oneshot-reader-" + taskId);
            reader.setDaemon(true);
            reader.start();

            // Also drain stderr
            Thread errReader = new Thread(() -> drainStderr(process, definition.getPipelineId()), "pipeline-oneshot-stderr-" + taskId);
            errReader.setDaemon(true);
            errReader.start();

            // Wait for completion with timeout (5 minutes for one-shot)
            Map<String, Object> result = resultFuture.get(5, TimeUnit.MINUTES);
            process.waitFor(10, TimeUnit.SECONDS);
            return result;

        } finally {
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

        // Wait for READY via HTTP health poll
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

    private List<String> buildCommand(UnifiedPipelineDefinition definition, Path argsFile) {
        String javaPath = ProcessHandle.current().info().command().orElse("java");
        String classpath = System.getProperty("java.class.path");

        UnifiedPipelineDefinition.ServingConfig serving = definition.getServing() != null ?
                definition.getServing() : UnifiedPipelineDefinition.ServingConfig.builder().build();

        List<String> cmd = new ArrayList<>();
        cmd.add(javaPath);
        cmd.add("-Xmx" + serving.getHeapSize());
        cmd.add("-XX:+UseG1GC");
        cmd.add("-XX:MaxGCPauseMillis=200");
        cmd.add("-XX:+ExitOnOutOfMemoryError");
        cmd.add("-Dorg.bytedeco.javacpp.nopointergc=true");

        // Forward relevant system properties
        for (String prefix : FORWARDED_PROPERTY_PREFIXES) {
            Properties sysProps = System.getProperties();
            for (String key : sysProps.stringPropertyNames()) {
                if (key.startsWith(prefix)) {
                    cmd.add("-D" + key + "=" + sysProps.getProperty(key));
                }
            }
        }

        cmd.add("-cp");
        cmd.add(classpath);
        cmd.add(SUBPROCESS_MAIN_CLASS);
        cmd.add(argsFile.toAbsolutePath().toString());

        return cmd;
    }

    private void propagateEnvironment(Map<String, String> env) {
        // Forward ND4J/CUDA environment variables
        String[] envVarPrefixes = {"ND4J_", "KOMPILE_", "CUDA_", "OMP_", "MKL_"};
        Map<String, String> systemEnv = System.getenv();
        for (Map.Entry<String, String> entry : systemEnv.entrySet()) {
            for (String prefix : envVarPrefixes) {
                if (entry.getKey().startsWith(prefix)) {
                    env.put(entry.getKey(), entry.getValue());
                    break;
                }
            }
        }
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
        try (var socket = new java.net.ServerSocket(0)) {
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
            Map<String, String> nd4jProps = new java.util.LinkedHashMap<>();
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
