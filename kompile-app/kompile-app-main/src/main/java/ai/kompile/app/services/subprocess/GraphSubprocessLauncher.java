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

package ai.kompile.app.services.subprocess;

import ai.kompile.app.config.DeviceRoutingConfig;
import ai.kompile.app.config.Nd4jEnvironmentConfig;
import ai.kompile.app.config.SubprocessExecutableConfig;
import ai.kompile.app.services.DeviceRoutingConfigService;
import ai.kompile.app.services.Nd4jEnvironmentConfigService;
import ai.kompile.app.services.ServerPortService;
import ai.kompile.app.subprocess.GraphSubprocessArgs;
import ai.kompile.app.subprocess.SubprocessMessage;
import ai.kompile.app.subprocess.SubprocessRegistry;
import ai.kompile.core.graphrag.GraphSubprocessDelegate;
import ai.kompile.core.graphrag.model.Entity;
import ai.kompile.core.graphrag.model.Graph;
import ai.kompile.core.graphrag.model.Relationship;
import ai.kompile.core.graphrag.model.schema.SchemaEnforcementMode;
import ai.kompile.core.retrievers.RetrievedDoc;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import ai.kompile.app.subprocess.SubprocessClasspathBuilder;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * Service for launching and managing graph subprocess instances.
 *
 * <p>Spawns isolated JVM processes to run graph extraction and matrix graph
 * algorithms, preventing CUDA/ND4J native crashes from affecting the main
 * orchestrator process.</p>
 *
 * <p>Follows the same patterns as {@code VlmTestSubprocessLauncher} and
 * {@code SubprocessIngestLauncher}:</p>
 * <ul>
 *   <li>Args serialized to temp JSON file</li>
 *   <li>Progress via INGEST_MSG: protocol on subprocess stdout</li>
 *   <li>HTTP callbacks to main app on completion/failure</li>
 *   <li>Memory watchdog in subprocess for OOM protection</li>
 *   <li>Results written to output JSON file</li>
 * </ul>
 */
@Service
public class GraphSubprocessLauncher implements GraphSubprocessDelegate {

    private static final Logger logger = LoggerFactory.getLogger(GraphSubprocessLauncher.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Value("${kompile.graph.subprocess.java-path:java}")
    private String fallbackJavaPath;

    @Value("${kompile.graph.subprocess.heap-size:4g}")
    private String fallbackHeapSize;

    @Value("${kompile.graph.subprocess.timeout-minutes:60}")
    private int fallbackTimeoutMinutes;

    private final SubprocessExecutableConfig execConfig;
    private final ServerPortService serverPortService;
    private final SubprocessConfigService subprocessConfigService;
    private final Nd4jEnvironmentConfigService nd4jEnvironmentConfigService;
    private final DeviceRoutingConfigService deviceRoutingConfigService;

    @Autowired(required = false)
    private SimpMessagingTemplate messagingTemplate;

    @Autowired(required = false)
    private SubprocessRegistry subprocessRegistry;

    @Autowired(required = false)
    private ai.kompile.app.services.SubprocessHeartbeatBroadcaster heartbeatBroadcaster;

    private final Map<String, GraphProcessHandle> activeProcesses = new ConcurrentHashMap<>();
    private final ExecutorService executor = new java.util.concurrent.ThreadPoolExecutor(
            2, 8, 60L, java.util.concurrent.TimeUnit.SECONDS,
            new java.util.concurrent.LinkedBlockingQueue<>(32),
            r -> { Thread t = new Thread(r, "graph-subprocess-launcher"); t.setDaemon(true); return t; },
            new java.util.concurrent.ThreadPoolExecutor.CallerRunsPolicy());

    public GraphSubprocessLauncher(
            SubprocessExecutableConfig execConfig,
            ServerPortService serverPortService,
            @Autowired(required = false) SubprocessConfigService subprocessConfigService,
            @Autowired(required = false) Nd4jEnvironmentConfigService nd4jEnvironmentConfigService,
            @Autowired(required = false) DeviceRoutingConfigService deviceRoutingConfigService) {
        this.execConfig = execConfig;
        this.serverPortService = serverPortService;
        this.subprocessConfigService = subprocessConfigService;
        this.nd4jEnvironmentConfigService = nd4jEnvironmentConfigService;
        this.deviceRoutingConfigService = deviceRoutingConfigService;
    }

    // === GraphSubprocessDelegate implementation ===

    @Override
    public boolean isAvailable() {
        if (subprocessConfigService != null) {
            return subprocessConfigService.isEnabled();
        }
        return false; // No config service = subprocess mode not available
    }

    @Override
    public CompletableFuture<SubprocessGraphResult> extractInSubprocess(
            List<RetrievedDoc> docs,
            List<String> entityTypes,
            List<String> relationshipTypes,
            String llmProvider,
            String llmModelName,
            double llmTemperature,
            int llmMaxTokens,
            String customPrompt,
            SchemaEnforcementMode schemaEnforcementMode,
            double minConfidence,
            int batchSize,
            boolean skipEmbedding,
            boolean runMatrixAlgorithms,
            Long factSheetId,
            String collectionName) {

        // Convert RetrievedDocs to DocumentChunks
        List<GraphSubprocessArgs.DocumentChunk> chunks = docs.stream()
                .map(doc -> new GraphSubprocessArgs.DocumentChunk(
                        doc.getId(),
                        doc.getText(),
                        doc.getMetadata() != null
                                ? doc.getMetadata().entrySet().stream()
                                    .collect(java.util.stream.Collectors.toMap(
                                            Map.Entry::getKey,
                                            e -> (Object) e.getValue()))
                                : Map.of()))
                .collect(java.util.stream.Collectors.toList());

        String taskId = "graph-" + UUID.randomUUID().toString().substring(0, 8);

        GraphSubprocessArgs args = new GraphSubprocessArgs(
                taskId, factSheetId, collectionName, chunks,
                entityTypes != null ? entityTypes : List.of(),
                relationshipTypes != null ? relationshipTypes : List.of(),
                llmProvider, llmModelName, llmTemperature, llmMaxTokens,
                customPrompt,
                schemaEnforcementMode != null ? schemaEnforcementMode.name() : "LENIENT",
                minConfidence, batchSize, skipEmbedding, runMatrixAlgorithms,
                false, // persistMatrixGraph — main app handles persistence
                false, // entityResolution — main app handles this
                GraphSubprocessArgs.DEFAULT_ENTITY_RESOLUTION_THRESHOLD,
                null, null, null, null, null, // model source — will be injected by launcher
                null, null, null, // callbackBaseUrl, nd4jConfigJson, outputPath — injected by launcher
                GraphSubprocessArgs.DEFAULT_MEMORY_THRESHOLD_PERCENT,
                GraphSubprocessArgs.DEFAULT_MEMORY_CRITICAL_PERCENT,
                GraphSubprocessArgs.DEFAULT_MEMORY_KILL_THRESHOLD_PERCENT,
                GraphSubprocessArgs.DEFAULT_MEMORY_CHECK_INTERVAL_MS,
                GraphSubprocessArgs.DEFAULT_GPU_MEMORY_THRESHOLD_PERCENT,
                GraphSubprocessArgs.DEFAULT_GPU_MEMORY_CRITICAL_PERCENT,
                GraphSubprocessArgs.DEFAULT_GPU_MEMORY_KILL_THRESHOLD_PERCENT,
                GraphSubprocessArgs.DEFAULT_OFF_HEAP_THRESHOLD_PERCENT,
                GraphSubprocessArgs.DEFAULT_OFF_HEAP_CRITICAL_PERCENT,
                GraphSubprocessArgs.DEFAULT_OFF_HEAP_KILL_THRESHOLD_PERCENT,
                Map.of());

        // Launch and convert result
        return launchGraphExtraction(args).thenApply(result -> {
            // Convert extraction result to Graph
            Graph graph = new Graph();
            List<Entity> entities = new ArrayList<>();
            List<Relationship> relationships = new ArrayList<>();

            if (result.entities() != null) {
                for (GraphSubprocessArgs.ExtractedEntity e : result.entities()) {
                    Entity entity = new Entity();
                    entity.setId(e.id());
                    entity.setTitle(e.title());
                    entity.setType(e.type());
                    entity.setDescription(e.description());
                    entity.setConfidence(e.confidence());
                    entity.setAliases(e.aliases());
                    entities.add(entity);
                }
            }
            if (result.relationships() != null) {
                for (GraphSubprocessArgs.ExtractedRelationship r : result.relationships()) {
                    Relationship rel = new Relationship();
                    rel.setSource(r.sourceId());
                    rel.setTarget(r.targetId());
                    rel.setType(r.type());
                    rel.setDescription(r.description());
                    rel.setWeight(r.weight());
                    rel.setConfidence(r.confidence());
                    relationships.add(rel);
                }
            }

            graph.setEntities(entities);
            graph.setRelationships(relationships);

            return new SubprocessGraphResult(
                    graph,
                    result.entitiesExtracted(),
                    result.relationshipsExtracted(),
                    result.documentsProcessed(),
                    result.extractionDurationMs(),
                    result.matrixAlgorithmDurationMs());
        });
    }

    /**
     * Launch a graph extraction subprocess.
     *
     * @param args Pre-built subprocess args
     * @return Future that completes when subprocess finishes, with the extraction result
     */
    public CompletableFuture<GraphSubprocessArgs.GraphExtractionResult> launchGraphExtraction(
            GraphSubprocessArgs args) {

        CompletableFuture<GraphSubprocessArgs.GraphExtractionResult> future = new CompletableFuture<>();

        executor.submit(() -> {
            try {
                runGraphExtraction(args, future);
            } catch (Exception e) {
                logger.error("Failed to launch graph subprocess for task {}", args.taskId(), e);
                future.completeExceptionally(e);
            }
        });

        return future;
    }

    private void runGraphExtraction(GraphSubprocessArgs args,
                                     CompletableFuture<GraphSubprocessArgs.GraphExtractionResult> future) {
        Process process = null;
        Path argsFile = null;
        Path outputFile = null;

        try {
            String taskId = args.taskId();

            // Capture ND4J config from parent
            String nd4jConfigJson = captureNd4jConfig();

            // Create output file path
            outputFile = Files.createTempFile("graph-result-" + taskId + "-", ".json");

            // Rebuild args with nd4j config and output path injected
            GraphSubprocessArgs fullArgs = new GraphSubprocessArgs(
                    args.taskId(),
                    args.factSheetId(),
                    args.collectionName(),
                    args.documentChunks(),
                    args.entityTypes(),
                    args.relationshipTypes(),
                    args.llmProvider(),
                    args.llmModelName(),
                    args.llmTemperature(),
                    args.llmMaxTokens(),
                    args.customPrompt(),
                    args.schemaEnforcementMode(),
                    args.minConfidence(),
                    args.batchSize(),
                    args.skipEmbedding(),
                    args.runMatrixAlgorithms(),
                    args.persistMatrixGraph(),
                    args.entityResolution(),
                    args.entityResolutionThreshold(),
                    args.modelSourceType(),
                    args.modelIdentifier(),
                    args.stagingUrl(),
                    args.stagingApiKey(),
                    args.archivePath(),
                    args.callbackBaseUrl() != null ? args.callbackBaseUrl()
                            : "http://localhost:" + serverPortService.getActualPort() + "/api",
                    nd4jConfigJson != null ? nd4jConfigJson : args.nd4jConfigJson(),
                    outputFile.toString(),
                    args.memoryThresholdPercent(),
                    args.memoryCriticalPercent(),
                    args.memoryKillThresholdPercent(),
                    args.memoryCheckIntervalMs(),
                    args.gpuMemoryThresholdPercent(),
                    args.gpuMemoryCriticalPercent(),
                    args.gpuMemoryKillThresholdPercent(),
                    args.offHeapThresholdPercent(),
                    args.offHeapCriticalPercent(),
                    args.offHeapKillThresholdPercent(),
                    args.options()
            );

            // Write args to temp file
            argsFile = Files.createTempFile("graph-args-" + taskId + "-", ".json");
            fullArgs.toFile(argsFile);

            // Build subprocess command
            String classpath = getClasspath();
            String heapSize = getEffectiveHeapSize();
            String javaPath = getEffectiveJavaPath();

            List<String> command = execConfig.buildGraphCommand(argsFile, heapSize, javaPath, classpath);
            logger.info("Launching graph subprocess for task {}: {}", taskId, String.join(" ", command));

            // Start process
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(false);
            propagateNd4jEnvironment(pb.environment());

            process = pb.start();

            // Register with subprocess registry
            if (subprocessRegistry != null) {
                subprocessRegistry.register("graph-" + taskId, process, "graph");
            }

            // Track active process
            GraphProcessHandle handle = new GraphProcessHandle(taskId, process, future, argsFile, outputFile);
            activeProcesses.put(taskId, handle);

            // Monitor stdout for INGEST_MSG: protocol messages
            Process finalProcess = process;
            Path finalOutputFile = outputFile;

            Thread stdoutThread = new Thread(() -> monitorStdout(taskId, finalProcess, future, finalOutputFile),
                    "graph-stdout-" + taskId);
            stdoutThread.setDaemon(true);
            stdoutThread.start();

            // Monitor stderr for logging
            Thread stderrThread = new Thread(() -> monitorStderr(taskId, finalProcess),
                    "graph-stderr-" + taskId);
            stderrThread.setDaemon(true);
            stderrThread.start();

            // Wait for process with timeout
            int timeoutMinutes = getEffectiveTimeoutMinutes();
            boolean finished = process.waitFor(timeoutMinutes, TimeUnit.MINUTES);

            if (!finished) {
                logger.warn("Graph subprocess timed out after {} minutes for task {}", timeoutMinutes, taskId);
                process.destroyForcibly();
                future.completeExceptionally(new TimeoutException(
                        "Graph subprocess timed out after " + timeoutMinutes + " minutes"));
            } else {
                int exitCode = process.exitValue();
                if (exitCode != 0 && !future.isDone()) {
                    logger.error("Graph subprocess exited with code {} for task {}", exitCode, taskId);
                    future.completeExceptionally(new RuntimeException(
                            "Graph subprocess exited with code " + exitCode));
                } else if (!future.isDone()) {
                    // Process exited normally but future wasn't completed by stdout monitor
                    // Try to read result file
                    tryCompleteFromOutputFile(future, finalOutputFile, taskId);
                }
            }

        } catch (Exception e) {
            logger.error("Graph subprocess launch failed for task {}", args.taskId(), e);
            if (!future.isDone()) {
                future.completeExceptionally(e);
            }
        } finally {
            activeProcesses.remove(args.taskId());
            if (subprocessRegistry != null) {
                subprocessRegistry.deregister("graph-" + args.taskId());
            }
        }
    }

    private void monitorStdout(String taskId, Process process,
                                CompletableFuture<GraphSubprocessArgs.GraphExtractionResult> future,
                                Path outputFile) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("INGEST_MSG:")) {
                    String json = line.substring("INGEST_MSG:".length());
                    handleProtocolMessage(taskId, json, future, outputFile);
                }
            }
        } catch (IOException e) {
            if (process.isAlive()) {
                logger.warn("Error reading graph subprocess stdout for task {}: {}", taskId, e.getMessage());
            }
        }
    }

    private void handleProtocolMessage(String taskId, String json,
                                        CompletableFuture<GraphSubprocessArgs.GraphExtractionResult> future,
                                        Path outputFile) {
        try {
            SubprocessMessage message = MAPPER.readValue(json, SubprocessMessage.class);

            SubprocessMessage.dispatch(message, new SubprocessMessage.Handler() {
                @Override
                public void onReady(SubprocessMessage.Ready ready) {
                    logger.info("Graph subprocess ready for task {}", taskId);
                    if (subprocessRegistry != null) {
                        subprocessRegistry.markReady("graph-" + taskId);
                    }
                }

                @Override
                public void onProgress(SubprocessMessage.Progress progress) {
                    sendWebSocketUpdate(taskId, "RUNNING", progress.progressPercent(),
                            progress.phase(), progress.message());
                }

                @Override
                public void onCompleted(SubprocessMessage.Completed completed) {
                    logger.info("Graph subprocess completed for task {}: {} docs processed",
                            taskId, completed.documentsLoaded());
                    tryCompleteFromOutputFile(future, outputFile, taskId);
                }

                @Override
                public void onFailed(SubprocessMessage.Failed failed) {
                    logger.error("Graph subprocess failed for task {}: {} ({})",
                            taskId, failed.errorMessage(), failed.errorType());
                    future.completeExceptionally(new RuntimeException(
                            "Graph extraction failed: " + failed.errorMessage()));
                }

                @Override
                public void onHeartbeat(SubprocessMessage.Heartbeat heartbeat) {
                    if (heartbeatBroadcaster != null) {
                        heartbeatBroadcaster.broadcastHeartbeat("graph-" + taskId, "graph", heartbeat);
                    }
                }
            });
        } catch (Exception e) {
            logger.debug("Failed to parse protocol message for task {}: {}", taskId, e.getMessage());
        }
    }

    private void tryCompleteFromOutputFile(
            CompletableFuture<GraphSubprocessArgs.GraphExtractionResult> future,
            Path outputFile, String taskId) {
        try {
            if (outputFile != null && Files.exists(outputFile) && Files.size(outputFile) > 0) {
                GraphSubprocessArgs.GraphExtractionResult result =
                        GraphSubprocessArgs.GraphExtractionResult.fromFile(outputFile);
                future.complete(result);
            } else if (!future.isDone()) {
                future.completeExceptionally(new RuntimeException(
                        "Graph subprocess completed but no output file found for task " + taskId));
            }
        } catch (Exception e) {
            if (!future.isDone()) {
                future.completeExceptionally(new RuntimeException(
                        "Failed to read graph extraction results for task " + taskId, e));
            }
        }
    }

    private void monitorStderr(String taskId, Process process) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getErrorStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                logger.debug("[graph-{}] {}", taskId, line);
            }
        } catch (IOException e) {
            if (process.isAlive()) {
                logger.warn("Error reading graph subprocess stderr for task {}", taskId);
            }
        }
    }

    private void sendWebSocketUpdate(String taskId, String status, int percent,
                                      String phase, String message) {
        if (messagingTemplate != null) {
            try {
                Map<String, Object> update = new LinkedHashMap<>();
                update.put("taskId", taskId);
                update.put("status", status);
                update.put("progress", percent);
                update.put("phase", phase);
                update.put("message", message);
                messagingTemplate.convertAndSend("/topic/graph-progress/" + taskId, update);
            } catch (Exception e) {
                logger.debug("Failed to send WebSocket update for graph task {}", taskId);
            }
        }
    }

    /**
     * Cancel a running graph subprocess.
     */
    public boolean cancel(String taskId) {
        GraphProcessHandle handle = activeProcesses.get(taskId);
        if (handle != null && handle.process.isAlive()) {
            logger.info("Cancelling graph subprocess for task {}", taskId);
            handle.process.destroyForcibly();
            handle.future.cancel(true);
            return true;
        }
        return false;
    }

    /**
     * Check if a graph subprocess is currently running for a task.
     */
    public boolean isRunning(String taskId) {
        GraphProcessHandle handle = activeProcesses.get(taskId);
        return handle != null && handle.process.isAlive();
    }

    @PreDestroy
    public void shutdown() {
        for (GraphProcessHandle handle : activeProcesses.values()) {
            if (handle.process.isAlive()) {
                logger.info("Shutting down graph subprocess for task {}", handle.taskId);
                handle.process.destroyForcibly();
            }
        }
        executor.shutdown();
    }

    // === Classpath & Config helpers (same pattern as VlmTestSubprocessLauncher) ===

    private String getEffectiveJavaPath() {
        if (subprocessConfigService != null) {
            String configured = subprocessConfigService.getJavaPath();
            if (configured != null && !configured.isBlank()) return configured.trim();
        }
        return fallbackJavaPath;
    }

    private String getEffectiveHeapSize() {
        if (subprocessConfigService != null) {
            // Reuse ingest heap size config; graph subprocess has similar memory needs
            String configured = subprocessConfigService.getHeapSize();
            if (configured != null && !configured.isBlank()) return configured.trim();
        }
        return fallbackHeapSize;
    }

    private int getEffectiveTimeoutMinutes() {
        return fallbackTimeoutMinutes;
    }

    private String captureNd4jConfig() {
        // Check if device routing provides a service-specific config for graph
        if (deviceRoutingConfigService != null && deviceRoutingConfigService.isEnabled()) {
            try {
                Nd4jEnvironmentConfig routedConfig = deviceRoutingConfigService
                        .resolveNd4jConfigForService(DeviceRoutingConfig.SERVICE_GRAPH);
                logger.info("Using device-routed ND4J config for graph: maxThreads={}, cudaDevice={}",
                        routedConfig.maxThreads(), routedConfig.cudaCurrentDevice());
                return MAPPER.writeValueAsString(routedConfig);
            } catch (Exception e) {
                logger.warn("Failed to resolve device-routed config for graph, falling back: {}", e.getMessage());
            }
        }

        if (nd4jEnvironmentConfigService != null) {
            try {
                Nd4jEnvironmentConfig config = nd4jEnvironmentConfigService.getActualConfiguration();
                return MAPPER.writeValueAsString(config);
            } catch (Exception e) {
                logger.warn("Failed to capture ND4J config: {}", e.getMessage());
            }
        }
        return null;
    }

    private String getClasspath() {
        return SubprocessClasspathBuilder.buildClasspath();
    }

    private void propagateNd4jEnvironment(Map<String, String> env) {
        List<String> nd4jEnvVars = List.of(
                "ND4J_BACKEND", "ND4J_DATA_BUFFER_OPS", "ND4J_RESOURCES_DIR", "ND4J_ALLOW_FALLBACK",
                "OMP_NUM_THREADS", "MKL_NUM_THREADS", "OPENBLAS_NUM_THREADS", "GOTO_NUM_THREADS",
                "VECLIB_MAXIMUM_THREADS", "NUMEXPR_NUM_THREADS",
                "CUDA_VISIBLE_DEVICES", "CUDA_DEVICE_ORDER", "CUDA_LAUNCH_BLOCKING", "CUDA_CACHE_PATH",
                "JAVACPP_PLATFORM", "JAVACPP_CACHESFX",
                "ND4J_HEAP_SPACE", "ND4J_OFF_HEAP_SPACE",
                "KOMPILE_MODELS_DIR");

        for (String varName : nd4jEnvVars) {
            String value = System.getenv(varName);
            if (value != null && !value.isEmpty()) {
                env.put(varName, value);
            }
        }

        // Propagate all ND4J_ and KOMPILE_ prefixed vars
        for (Map.Entry<String, String> entry : System.getenv().entrySet()) {
            String key = entry.getKey();
            if ((key.startsWith("ND4J_") || key.startsWith("KOMPILE_"))
                    && !env.containsKey(key)) {
                env.put(key, entry.getValue());
            }
        }
    }

    private record GraphProcessHandle(
            String taskId,
            Process process,
            CompletableFuture<GraphSubprocessArgs.GraphExtractionResult> future,
            Path argsFile,
            Path outputFile
    ) {}
}
