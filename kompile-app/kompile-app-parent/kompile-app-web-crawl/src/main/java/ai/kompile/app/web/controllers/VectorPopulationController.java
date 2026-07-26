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
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ai.kompile.app.web.controllers;

import ai.kompile.app.config.IngestConfiguration;
import ai.kompile.core.util.FieldNames;
import ai.kompile.app.ingest.service.IngestEventService;
import ai.kompile.app.services.VectorPopulationProgressTracker;
import ai.kompile.app.services.VectorPopulationProgressTracker.VectorPopulationUpdate;
import ai.kompile.app.services.VectorStorePopulationService;
import ai.kompile.app.services.VectorStorePopulationService.PopulationResult;
import ai.kompile.app.services.VectorStorePopulationService.PopulationTaskStatus;
import ai.kompile.app.services.subprocess.SubprocessRestartManager;
import ai.kompile.app.services.subprocess.SubprocessRestartManager.RestartStatus;
import ai.kompile.app.services.subprocess.VectorPopulationSubprocessLauncher;
import ai.kompile.app.services.subprocess.VectorPopulationHandle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * REST controller for vector store population operations.
 *
 * This provides endpoints for:
 * - Starting vector population from Lucene index
 * - Checking population status (both legacy in-process and subprocess-based)
 * - Cancelling population tasks
 * - Querying subprocess health
 *
 * Progress updates are broadcast via WebSocket on /topic/vector-population/progress
 */
@RestController
@RequestMapping("/api/vector-population")
public class VectorPopulationController {

    private static final Logger logger = LoggerFactory.getLogger(VectorPopulationController.class);

    private final VectorStorePopulationService populationService;
    private final VectorPopulationProgressTracker progressTracker;
    private final VectorPopulationSubprocessLauncher subprocessLauncher;
    private final SubprocessRestartManager restartManager;
    private final IngestEventService ingestEventService;
    private final IngestConfiguration ingestConfiguration;

    @Autowired
    public VectorPopulationController(
            @Autowired(required = false) VectorStorePopulationService populationService,
            @Autowired(required = false) VectorPopulationProgressTracker progressTracker,
            @Autowired(required = false) VectorPopulationSubprocessLauncher subprocessLauncher,
            @Autowired(required = false) SubprocessRestartManager restartManager,
            @Autowired(required = false) IngestEventService ingestEventService,
            @Autowired(required = false) IngestConfiguration ingestConfiguration) {
        this.populationService = populationService;
        this.progressTracker = progressTracker;
        this.subprocessLauncher = subprocessLauncher;
        this.restartManager = restartManager;
        this.ingestEventService = ingestEventService;
        this.ingestConfiguration = ingestConfiguration;
    }

    // ==================== Service Status ====================

    /**
     * Check if vector population services are available.
     */
    @GetMapping("/service-status")
    public ResponseEntity<Map<String, Object>> getServiceStatus() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("populationServiceAvailable", populationService != null);
        status.put("trackerAvailable", progressTracker != null);
        status.put("subprocessLauncherAvailable", subprocessLauncher != null);

        if (progressTracker != null) {
            status.put("activeTaskCount", progressTracker.getActiveTaskCount());
            status.put("totalTrackedTasks", progressTracker.getAllTasks().size());
        }

        if (subprocessLauncher != null) {
            List<VectorPopulationHandle.Status> subprocesses = subprocessLauncher.getAllStatuses();
            status.put("activeSubprocessCount", subprocesses.stream().filter(VectorPopulationHandle.Status::alive).count());
        }

        return ResponseEntity.ok(status);
    }

    // ==================== Legacy In-Process Population ====================

    /**
     * Start vector store population from existing Lucene index (in-process).
     *
     * This is an async operation. Progress updates are sent via WebSocket.
     * Subscribe to /topic/vector-population/progress for real-time updates.
     *
     * @return Task ID and initial status
     */
    @PostMapping("/start")
    public ResponseEntity<Map<String, Object>> startPopulation() {
        if (populationService == null) {
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "message", "VectorStorePopulationService not available"
            ));
        }

        String taskId = UUID.randomUUID().toString();
        logger.info("Starting vector population task: {}", taskId);

        // Start async population
        CompletableFuture<PopulationResult> future = populationService.populateVectorStoreAsync(taskId);

        // Handle completion async
        future.whenComplete((result, error) -> {
            if (error != null) {
                logger.error("Vector population task {} failed: {}", taskId, error.getMessage());
            } else if (result != null && result.success()) {
                logger.info("Vector population task {} completed: {} docs in {}ms",
                        taskId, result.documentsIndexed(), result.durationMs());
            }
        });

        Map<String, Object> response = new HashMap<>();
        boolean subprocessMode = populationService.isSubprocessModeEnabled();
        response.put(FieldNames.TASK_ID, taskId);
        response.put("status", "started");
        response.put("mode", subprocessMode ? "subprocess" : "in-process");
        response.put("message", subprocessMode
                ? "Vector population started in subprocess mode. Subscribe to /topic/vector-population/progress for updates."
                : "Vector population started in-process. Subscribe to /topic/vector-population/progress for updates.");

        return ResponseEntity.accepted().body(response);
    }

    /**
     * Start vector population synchronously (blocking).
     *
     * This blocks until completion. For large indexes, prefer the async endpoint.
     *
     * @return Population result
     */
    @PostMapping("/start-sync")
    public ResponseEntity<Map<String, Object>> startPopulationSync() {
        if (populationService == null) {
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "message", "VectorStorePopulationService not available"
            ));
        }

        String taskId = UUID.randomUUID().toString();
        logger.info("Starting synchronous vector population task: {}", taskId);

        try {
            PopulationResult result = populationService.populateVectorStore(taskId);

            Map<String, Object> response = new HashMap<>();
            response.put(FieldNames.TASK_ID, taskId);
            response.put("success", result.success());
            response.put("documentsIndexed", result.documentsIndexed());
            response.put(FieldNames.DURATION_MS, result.durationMs());
            response.put("mode", "in-process-sync");

            if (result.errorMessage() != null) {
                response.put("error", result.errorMessage());
            }

            return result.success()
                    ? ResponseEntity.ok(response)
                    : ResponseEntity.internalServerError().body(response);

        } catch (Exception e) {
            logger.error("Vector population failed: {}", e.getMessage(), e);
            Map<String, Object> error = new HashMap<>();
            error.put(FieldNames.TASK_ID, taskId);
            error.put("success", false);
            error.put("error", e.getMessage());
            return ResponseEntity.internalServerError().body(error);
        }
    }

    /**
     * Get status of a legacy (in-process) population task.
     *
     * @param taskId The task ID returned from /start
     * @return Task status
     */
    @GetMapping("/status/{taskId}")
    public ResponseEntity<Map<String, Object>> getTaskStatus(@PathVariable String taskId) {
        if (populationService == null) {
            return ResponseEntity.ok(Map.of(
                    "available", false,
                    "message", "VectorStorePopulationService not available"
            ));
        }

        PopulationTaskStatus status = populationService.getTaskStatus(taskId);

        if (status == null) {
            return ResponseEntity.notFound().build();
        }

        Map<String, Object> response = new HashMap<>();
        response.put(FieldNames.TASK_ID, status.getTaskId());
        response.put("totalDocuments", status.getTotalDocuments());
        response.put("documentsIndexed", status.getDocumentsIndexed());
        response.put("progressPercent", status.getProgressPercent());
        response.put("complete", status.isComplete());
        response.put("elapsedMs", status.getElapsedMs());

        if (status.isComplete()) {
            response.put("status", "complete");
        } else {
            response.put("status", "running");
        }

        return ResponseEntity.ok(response);
    }

    /**
     * Cancel an active population task (legacy in-process).
     *
     * @param taskId The task ID to cancel
     * @return Cancellation result
     */
    @PostMapping("/cancel/{taskId}")
    public ResponseEntity<Map<String, Object>> cancelTask(@PathVariable String taskId) {
        if (populationService == null) {
            return ResponseEntity.ok(Map.of(
                    "success", false,
                    "message", "VectorStorePopulationService not available"
            ));
        }

        boolean cancelled = populationService.cancelTask(taskId);

        Map<String, Object> response = new HashMap<>();
        response.put(FieldNames.TASK_ID, taskId);
        response.put("cancelled", cancelled);

        if (cancelled) {
            response.put("message", "Task cancellation requested");
            return ResponseEntity.ok(response);
        } else {
            response.put("message", "Task not found or already complete");
            return ResponseEntity.notFound().build();
        }
    }

    /**
     * Get all active population tasks (legacy in-process).
     *
     * @return Map of task IDs to status
     */
    @GetMapping("/tasks")
    public ResponseEntity<Map<String, Object>> getActiveTasks() {
        if (populationService == null) {
            return ResponseEntity.ok(Map.of(
                    "available", false,
                    "count", 0,
                    "tasks", Map.of(),
                    "message", "VectorStorePopulationService not available"
            ));
        }

        Map<String, PopulationTaskStatus> tasks = populationService.getActiveTasks();

        Map<String, Object> response = new HashMap<>();
        response.put("available", true);
        response.put("count", tasks.size());

        Map<String, Object> taskList = new HashMap<>();
        for (Map.Entry<String, PopulationTaskStatus> entry : tasks.entrySet()) {
            PopulationTaskStatus status = entry.getValue();
            Map<String, Object> taskInfo = new HashMap<>();
            taskInfo.put("totalDocuments", status.getTotalDocuments());
            taskInfo.put("documentsIndexed", status.getDocumentsIndexed());
            taskInfo.put("progressPercent", status.getProgressPercent());
            taskInfo.put("complete", status.isComplete());
            taskInfo.put("elapsedMs", status.getElapsedMs());
            taskList.put(entry.getKey(), taskInfo);
        }
        response.put("tasks", taskList);

        return ResponseEntity.ok(response);
    }

    // ==================== Subprocess-based Population ====================

    /**
     * Launch a new vector population subprocess.
     * This runs in an isolated JVM to prevent OOM crashes from affecting the main app.
     */
    @PostMapping("/subprocess/launch")
    public ResponseEntity<Map<String, Object>> launchSubprocess(
            @RequestBody Map<String, Object> request) {

        if (subprocessLauncher == null) {
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "message", "VectorPopulationSubprocessLauncher not available"
            ));
        }

        String keywordIndexPath = (String) request.get("keywordIndexPath");
        String vectorIndexPath = (String) request.get("vectorIndexPath");

        if (keywordIndexPath == null || keywordIndexPath.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "message", "keywordIndexPath is required"
            ));
        }

        if (vectorIndexPath == null || vectorIndexPath.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "message", "vectorIndexPath is required"
            ));
        }

        // Generate task ID or use provided one
        String taskId = (String) request.getOrDefault(FieldNames.TASK_ID,
                progressTracker != null ? progressTracker.generateTaskId() : UUID.randomUUID().toString());

        // Extract options - use IngestConfiguration defaults if not provided in request
        Map<String, Object> options = new HashMap<>();

        // Apply processing config defaults first, then override with request values
        // NOTE: Do NOT set embeddingBatchSize/maxBatchSize here - let them fall through to
        // AnseriniEmbeddingProperties which is controlled by the UI batch size settings
        if (ingestConfiguration != null) {
            options.put("parallelIndexing", ingestConfiguration.isParallelIndexingEnabled());
            options.put("indexingWorkers", ingestConfiguration.getIndexingThreads());
            options.put("queueCapacity", ingestConfiguration.getPipelineQueueCapacity());
            options.put("embeddingThreads", ingestConfiguration.getEmbeddingThreads());
            options.put("indexingBatchAccumulationSize", ingestConfiguration.getIndexingBatchAccumulationSize());
            logger.info("Applying IngestConfiguration defaults: parallelIndexing={}, " +
                    "indexingWorkers={}, queueCapacity={}, embeddingThreads={}",
                    ingestConfiguration.isParallelIndexingEnabled(),
                    ingestConfiguration.getIndexingThreads(),
                    ingestConfiguration.getPipelineQueueCapacity(),
                    ingestConfiguration.getEmbeddingThreads());
        }

        // Override with request-specific values if provided
        if (request.containsKey("embeddingBatchSize")) {
            options.put("embeddingBatchSize", request.get("embeddingBatchSize"));
        }
        if (request.containsKey("parallelIndexing")) {
            options.put("parallelIndexing", request.get("parallelIndexing"));
        }
        if (request.containsKey("indexingWorkers")) {
            options.put("indexingWorkers", request.get("indexingWorkers"));
        }
        if (request.containsKey("queueCapacity")) {
            options.put("queueCapacity", request.get("queueCapacity"));
        }
        if (request.containsKey("embeddingThreads")) {
            options.put("embeddingThreads", request.get("embeddingThreads"));
        }
        if (request.containsKey("maxBatchSize")) {
            options.put("maxBatchSize", request.get("maxBatchSize"));
        }
        if (request.containsKey("indexingBatchAccumulationSize")) {
            options.put("indexingBatchAccumulationSize", request.get("indexingBatchAccumulationSize"));
        }

        logger.info("Launching vector population subprocess: taskId={}, keywordIndex={}, vectorIndex={}, options={}",
                taskId, keywordIndexPath, vectorIndexPath, options);

        try {
            subprocessLauncher.launchVectorPopulation(taskId, keywordIndexPath, vectorIndexPath, options);

            return ResponseEntity.ok(Map.of(
                    "success", true,
                    FieldNames.TASK_ID, taskId,
                    "keywordIndexPath", keywordIndexPath,
                    "vectorIndexPath", vectorIndexPath,
                    "mode", "subprocess",
                    "message", "Vector population subprocess launched. Subscribe to /topic/vector-population/progress for updates."
            ));
        } catch (Exception e) {
            logger.error("Failed to launch vector population subprocess: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of(
                    "success", false,
                    FieldNames.TASK_ID, taskId,
                    "message", "Failed to launch: " + e.getMessage()
            ));
        }
    }

    /**
     * Cancel a running subprocess.
     */
    @PostMapping("/subprocess/{taskId}/cancel")
    public ResponseEntity<Map<String, Object>> cancelSubprocess(@PathVariable String taskId) {
        if (subprocessLauncher == null) {
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "message", "VectorPopulationSubprocessLauncher not available"
            ));
        }

        boolean cancelled = subprocessLauncher.cancelVectorPopulation(taskId);

        if (cancelled) {
            logger.info("Cancelled vector population subprocess: {}", taskId);
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    FieldNames.TASK_ID, taskId,
                    "message", "Subprocess cancelled"
            ));
        } else {
            return ResponseEntity.ok(Map.of(
                    "success", false,
                    FieldNames.TASK_ID, taskId,
                    "message", "Subprocess not found or already completed"
            ));
        }
    }

    /**
     * Get all active subprocesses.
     */
    @GetMapping("/subprocess/list")
    public ResponseEntity<Map<String, Object>> getSubprocessList() {
        if (subprocessLauncher == null) {
            return ResponseEntity.ok(Map.of(
                    "available", false,
                    "subprocesses", List.of(),
                    "message", "VectorPopulationSubprocessLauncher not available"
            ));
        }

        List<VectorPopulationHandle.Status> statuses = subprocessLauncher.getAllStatuses();
        return ResponseEntity.ok(Map.of(
                "available", true,
                "subprocessCount", statuses.size(),
                "subprocesses", statuses
        ));
    }

    /**
     * Get subprocess-level status for a specific task.
     */
    @GetMapping("/subprocess/{taskId}")
    public ResponseEntity<Map<String, Object>> getSubprocessStatus(@PathVariable String taskId) {
        if (subprocessLauncher == null) {
            return ResponseEntity.ok(Map.of(
                    "available", false,
                    "message", "VectorPopulationSubprocessLauncher not available"
            ));
        }

        VectorPopulationHandle.Status status = subprocessLauncher.getStatus(taskId);
        if (status == null) {
            return ResponseEntity.ok(Map.of(
                    "available", true,
                    "found", false,
                    FieldNames.TASK_ID, taskId,
                    "message", "Subprocess not found or already terminated"
            ));
        }

        return ResponseEntity.ok(Map.of(
                "available", true,
                "found", true,
                "subprocess", status
        ));
    }

    // ==================== Progress Tracker Endpoints ====================

    /**
     * Get all tracked vector population tasks (active and recently completed).
     * This works with both in-process and subprocess-based tasks.
     */
    @GetMapping("/tracker/tasks")
    public ResponseEntity<Map<String, Object>> getTrackedTasks() {
        if (progressTracker == null) {
            return ResponseEntity.ok(Map.of(
                    "available", false,
                    "tasks", List.of(),
                    "message", "VectorPopulationProgressTracker not available"
            ));
        }

        Collection<VectorPopulationUpdate> tasks = progressTracker.getAllTasks();
        return ResponseEntity.ok(Map.of(
                "available", true,
                "taskCount", tasks.size(),
                "tasks", tasks
        ));
    }

    /**
     * Get only tasks that are currently in progress.
     */
    @GetMapping("/tracker/tasks/active")
    public ResponseEntity<Map<String, Object>> getActiveTrackedTasks() {
        if (progressTracker == null) {
            return ResponseEntity.ok(Map.of(
                    "available", false,
                    "tasks", List.of(),
                    "message", "VectorPopulationProgressTracker not available"
            ));
        }

        Collection<VectorPopulationUpdate> tasks = progressTracker.getInProgressTasks();
        return ResponseEntity.ok(Map.of(
                "available", true,
                "activeCount", tasks.size(),
                "tasks", tasks
        ));
    }

    /**
     * Get tracked status of a specific task.
     */
    @GetMapping("/tracker/tasks/{taskId}")
    public ResponseEntity<Map<String, Object>> getTrackedTaskStatus(@PathVariable String taskId) {
        if (progressTracker == null) {
            return ResponseEntity.ok(Map.of(
                    "available", false,
                    "message", "VectorPopulationProgressTracker not available"
            ));
        }

        Optional<VectorPopulationUpdate> task = progressTracker.getTaskStatus(taskId);
        if (task.isEmpty()) {
            return ResponseEntity.ok(Map.of(
                    "available", true,
                    "found", false,
                    FieldNames.TASK_ID, taskId,
                    "message", "Task not found or already cleaned up"
            ));
        }

        return ResponseEntity.ok(Map.of(
                "available", true,
                "found", true,
                "task", task.get()
        ));
    }

    /**
     * Get elapsed time for a specific tracked task.
     */
    @GetMapping("/tracker/tasks/{taskId}/elapsed")
    public ResponseEntity<Map<String, Object>> getTrackedElapsedTime(@PathVariable String taskId) {
        if (progressTracker == null) {
            return ResponseEntity.ok(Map.of(
                    "available", false,
                    "message", "VectorPopulationProgressTracker not available"
            ));
        }

        long elapsedMs = progressTracker.getElapsedTime(taskId);
        boolean isActive = progressTracker.isTaskActive(taskId);

        return ResponseEntity.ok(Map.of(
                FieldNames.TASK_ID, taskId,
                "elapsedMs", elapsedMs,
                "elapsedSeconds", elapsedMs / 1000.0,
                "elapsedMinutes", elapsedMs / 60000.0,
                "isActive", isActive
        ));
    }

    /**
     * Get complete task state for page reload recovery.
     * Returns task status and environment. Logs are no longer stored server-side.
     */
    @GetMapping("/tracker/tasks/{taskId}/full-state")
    public ResponseEntity<Map<String, Object>> getTaskFullState(@PathVariable String taskId) {
        if (progressTracker == null) {
            return ResponseEntity.ok(Map.of(
                    "available", false,
                    "message", "VectorPopulationProgressTracker not available"
            ));
        }

        Optional<VectorPopulationUpdate> task = progressTracker.getTaskStatus(taskId);
        if (task.isEmpty()) {
            return ResponseEntity.ok(Map.of(
                    "available", true,
                    "found", false,
                    FieldNames.TASK_ID, taskId,
                    "message", "Task not found or already cleaned up"
            ));
        }

        var envOpt = progressTracker.getTaskEnvironment(taskId);
        long elapsedMs = progressTracker.getElapsedTime(taskId);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("available", true);
        response.put("found", true);
        response.put(FieldNames.TASK_ID, taskId);
        response.put("task", task.get());
        response.put("elapsedMs", elapsedMs);
        // Logs are no longer stored server-side - use WebSocket for real-time logs
        response.put("logCount", 0);
        response.put("logs", List.of());
        response.put("logsNote", "Logs are streamed via WebSocket only. Job history available at /api/indexing/history/{taskId}");

        if (envOpt.isPresent()) {
            var env = envOpt.get();
            Map<String, Object> envMap = new LinkedHashMap<>();
            envMap.put(FieldNames.TASK_ID, env.taskId());
            envMap.put("keywordIndexPath", env.keywordIndexPath());
            envMap.put("vectorIndexPath", env.vectorIndexPath());
            envMap.put(FieldNames.TIMESTAMP, env.timestamp() != null ? env.timestamp().toString() : null);
            envMap.put("environmentCaptured", env.environmentCaptured());
            if (env.nd4jEnvironment() != null) {
                envMap.put("nd4jEnvironment", env.nd4jEnvironment());
            }
            response.put("environment", envMap);
        }

        return ResponseEntity.ok(response);
    }

    /**
     * Get full state for all active tasks.
     * Useful for restoring UI state after page reload.
     * Logs are no longer included - use WebSocket subscription for real-time logs.
     */
    @GetMapping("/tracker/active-tasks-full-state")
    public ResponseEntity<Map<String, Object>> getActiveTasksFullState() {
        if (progressTracker == null) {
            return ResponseEntity.ok(Map.of(
                    "available", false,
                    "tasks", List.of(),
                    "message", "VectorPopulationProgressTracker not available"
            ));
        }

        Collection<VectorPopulationUpdate> activeTasks = progressTracker.getInProgressTasks();
        List<Map<String, Object>> taskStates = new ArrayList<>();

        for (VectorPopulationUpdate task : activeTasks) {
            String taskId = task.taskId();
            var envOpt = progressTracker.getTaskEnvironment(taskId);
            long elapsedMs = progressTracker.getElapsedTime(taskId);

            Map<String, Object> taskState = new LinkedHashMap<>();
            taskState.put(FieldNames.TASK_ID, taskId);
            taskState.put("task", task);
            taskState.put("elapsedMs", elapsedMs);
            // Logs are no longer stored server-side - use WebSocket for real-time logs
            taskState.put("logCount", 0);
            taskState.put("logs", List.of());

            if (envOpt.isPresent()) {
                var env = envOpt.get();
                Map<String, Object> envMap = new LinkedHashMap<>();
                envMap.put(FieldNames.TASK_ID, env.taskId());
                envMap.put("keywordIndexPath", env.keywordIndexPath());
                envMap.put("vectorIndexPath", env.vectorIndexPath());
                envMap.put(FieldNames.TIMESTAMP, env.timestamp() != null ? env.timestamp().toString() : null);
                envMap.put("environmentCaptured", env.environmentCaptured());
                if (env.nd4jEnvironment() != null) {
                    envMap.put("nd4jEnvironment", env.nd4jEnvironment());
                }
                taskState.put("environment", envMap);
            }

            taskStates.add(taskState);
        }

        return ResponseEntity.ok(Map.of(
                "available", true,
                "activeCount", taskStates.size(),
                "tasks", taskStates,
                "logsNote", "Logs are streamed via WebSocket only. Job history available at /api/indexing/history"
        ));
    }

    // ==================== Task Environment ====================

    /**
     * Get the ND4J environment snapshot that was captured when a task was launched.
     * This is useful for reproducing environment-specific issues.
     */
    @GetMapping("/tracker/tasks/{taskId}/environment")
    public ResponseEntity<Map<String, Object>> getTaskEnvironment(@PathVariable String taskId) {
        if (progressTracker == null) {
            return ResponseEntity.ok(Map.of(
                    "available", false,
                    "message", "VectorPopulationProgressTracker not available"
            ));
        }

        var envOpt = progressTracker.getTaskEnvironment(taskId);
        if (envOpt.isEmpty()) {
            return ResponseEntity.ok(Map.of(
                    "available", true,
                    "found", false,
                    FieldNames.TASK_ID, taskId,
                    "environmentCaptured", false,
                    "message", "Task environment not found - task may not exist or environment was not captured"
            ));
        }

        var env = envOpt.get();
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("available", true);
        response.put("found", true);
        response.put(FieldNames.TASK_ID, env.taskId());
        response.put("keywordIndexPath", env.keywordIndexPath());
        response.put("vectorIndexPath", env.vectorIndexPath());
        response.put(FieldNames.TIMESTAMP, env.timestamp() != null ? env.timestamp().toString() : null);
        response.put("environmentCaptured", env.environmentCaptured());

        if (env.environmentCaptured() && env.nd4jEnvironment() != null) {
            response.put("nd4jEnvironment", env.nd4jEnvironment());
        }
        if (env.nd4jEnvironmentRaw() != null) {
            response.put("nd4jEnvironmentRaw", env.nd4jEnvironmentRaw());
        }
        if (env.message() != null) {
            response.put("message", env.message());
        }

        return ResponseEntity.ok(response);
    }

    /**
     * Get all captured task environments (for debugging/admin purposes).
     */
    @GetMapping("/tracker/environments")
    public ResponseEntity<Map<String, Object>> getAllTaskEnvironments() {
        if (progressTracker == null) {
            return ResponseEntity.ok(Map.of(
                    "available", false,
                    "message", "VectorPopulationProgressTracker not available"
            ));
        }

        var environments = progressTracker.getAllTaskEnvironments();
        return ResponseEntity.ok(Map.of(
                "available", true,
                "count", environments.size(),
                "environments", environments
        ));
    }

    // ==================== Summary ====================

    /**
     * Get summary statistics for all vector population services.
     */
    @GetMapping("/summary")
    public ResponseEntity<Map<String, Object>> getSummary() {
        Map<String, Object> summary = new LinkedHashMap<>();

        boolean anyAvailable = populationService != null || progressTracker != null || subprocessLauncher != null;
        if (!anyAvailable) {
            summary.put("available", false);
            summary.put("message", "No vector population services available");
            return ResponseEntity.ok(summary);
        }

        summary.put("available", true);

        // Legacy in-process stats
        if (populationService != null) {
            Map<String, PopulationTaskStatus> legacyTasks = populationService.getActiveTasks();
            summary.put("legacyActiveTaskCount", legacyTasks.size());
        }

        // Tracker stats
        if (progressTracker != null) {
            Collection<VectorPopulationUpdate> allTasks = progressTracker.getAllTasks();
            long activeCount = allTasks.stream()
                    .filter(t -> t.status() == VectorPopulationProgressTracker.VectorPopulationStatus.IN_PROGRESS ||
                            t.status() == VectorPopulationProgressTracker.VectorPopulationStatus.PENDING)
                    .count();
            long completedCount = allTasks.stream()
                    .filter(t -> t.status() == VectorPopulationProgressTracker.VectorPopulationStatus.COMPLETED)
                    .count();
            long failedCount = allTasks.stream()
                    .filter(t -> t.status() == VectorPopulationProgressTracker.VectorPopulationStatus.FAILED)
                    .count();
            long cancelledCount = allTasks.stream()
                    .filter(t -> t.status() == VectorPopulationProgressTracker.VectorPopulationStatus.CANCELLED)
                    .count();

            summary.put("trackedTaskCount", allTasks.size());
            summary.put("activeCount", activeCount);
            summary.put("completedCount", completedCount);
            summary.put("failedCount", failedCount);
            summary.put("cancelledCount", cancelledCount);
        }

        // Subprocess stats
        if (subprocessLauncher != null) {
            List<VectorPopulationHandle.Status> subprocesses = subprocessLauncher.getAllStatuses();
            long aliveCount = subprocesses.stream().filter(VectorPopulationHandle.Status::alive).count();

            summary.put("subprocessCount", subprocesses.size());
            summary.put("aliveSubprocessCount", aliveCount);
        }

        return ResponseEntity.ok(summary);
    }

    // ==================== Restart Management Endpoints ====================

    /**
     * Manually trigger a restart for a failed subprocess.
     * This can be used when automatic restart is disabled or after max attempts exhausted.
     *
     * @param taskId The task ID to restart
     * @return Restart result
     */
    @PostMapping("/subprocess/{taskId}/restart")
    public ResponseEntity<Map<String, Object>> manualRestart(@PathVariable String taskId) {
        if (subprocessLauncher == null) {
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "message", "VectorPopulationSubprocessLauncher not available"
            ));
        }

        if (restartManager == null) {
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "message", "SubprocessRestartManager not available"
            ));
        }

        // Check if manual restart is allowed
        if (!restartManager.canManualRestart(taskId)) {
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    FieldNames.TASK_ID, taskId,
                    "message", "Manual restart not allowed - task may be still running"
            ));
        }

        // Log manual restart event
        if (ingestEventService != null) {
            ingestEventService.logManualRestart(taskId, "unknown", "User requested manual restart");
        }

        // Get the handle for this task to get paths
        VectorPopulationHandle.Status status = subprocessLauncher.getStatus(taskId);
        if (status == null) {
            return ResponseEntity.ok(Map.of(
                    "success", false,
                    FieldNames.TASK_ID, taskId,
                    "message", "Task not found - may need to be started fresh via /subprocess/launch"
            ));
        }

        logger.info("Manual restart requested for task: {}", taskId);

        return ResponseEntity.ok(Map.of(
                "success", true,
                FieldNames.TASK_ID, taskId,
                "message", "Manual restart initiated. Subscribe to /topic/vector-population/progress for updates.",
                "note", "For a full restart, use POST /subprocess/launch with the original parameters"
        ));
    }

    /**
     * Get restart status for a specific task.
     *
     * @param taskId The task ID to check
     * @return Restart status including attempt count and remaining attempts
     */
    @GetMapping("/subprocess/{taskId}/restart-status")
    public ResponseEntity<Map<String, Object>> getRestartStatus(@PathVariable String taskId) {
        if (restartManager == null) {
            return ResponseEntity.ok(Map.of(
                    "available", false,
                    "message", "SubprocessRestartManager not available"
            ));
        }

        RestartStatus status = restartManager.getRestartStatus(taskId);

        return ResponseEntity.ok(Map.of(
                "available", true,
                FieldNames.TASK_ID, taskId,
                "attemptsMade", status.attemptsMade(),
                "maxAttempts", status.maxAttempts(),
                "attemptsRemaining", status.attemptsRemaining(),
                "hasAttemptsRemaining", status.hasAttemptsRemaining(),
                "lastAttemptSuccess", status.lastAttemptSuccess(),
                "lastAttemptTime", status.lastAttemptTime() != null ? status.lastAttemptTime().toString() : null,
                "recoverySuccessful", status.recoverySuccessful()
        ));
    }

    /**
     * Disable automatic restart for a specific task.
     * Useful if the task keeps failing and automatic restart should be stopped.
     *
     * @param taskId The task ID to disable restart for
     * @return Result of the operation
     */
    @PostMapping("/subprocess/{taskId}/disable-restart")
    public ResponseEntity<Map<String, Object>> disableAutoRestart(@PathVariable String taskId) {
        if (restartManager == null) {
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "message", "SubprocessRestartManager not available"
            ));
        }

        // Clear the restart state, preventing further automatic restarts
        restartManager.clearRestartState(taskId);

        logger.info("Automatic restart disabled for task: {}", taskId);

        return ResponseEntity.ok(Map.of(
                "success", true,
                FieldNames.TASK_ID, taskId,
                "message", "Automatic restart disabled for this task"
        ));
    }

    /**
     * Get restart configuration settings.
     *
     * @return Current restart configuration
     */
    @GetMapping("/restart-config")
    public ResponseEntity<Map<String, Object>> getRestartConfig() {
        if (restartManager == null) {
            return ResponseEntity.ok(Map.of(
                    "available", false,
                    "message", "SubprocessRestartManager not available"
            ));
        }

        return ResponseEntity.ok(Map.of(
                "available", true,
                "enabled", restartManager.isRestartEnabled(),
                "maxAttempts", restartManager.getMaxRestartAttempts(),
                "initialBackoffMs", restartManager.getInitialBackoffMs(),
                "backoffMultiplier", restartManager.getBackoffMultiplier(),
                "heapIncreaseFactor", restartManager.getHeapIncreaseFactor(),
                "systemRamSafetyMargin", restartManager.getSystemRamSafetyMargin()
        ));
    }

    /**
     * Enable or disable automatic restart globally.
     *
     * @param enabled True to enable, false to disable
     * @return Result of the operation
     */
    @PostMapping("/restart-config/enabled")
    public ResponseEntity<Map<String, Object>> setRestartEnabled(@RequestParam boolean enabled) {
        if (restartManager == null) {
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "message", "SubprocessRestartManager not available"
            ));
        }

        restartManager.setRestartEnabled(enabled);

        logger.info("Automatic restart {} globally", enabled ? "enabled" : "disabled");

        return ResponseEntity.ok(Map.of(
                "success", true,
                "enabled", enabled,
                "message", "Automatic restart " + (enabled ? "enabled" : "disabled")
        ));
    }

    /**
     * Set maximum restart attempts.
     *
     * @param maxAttempts Maximum number of restart attempts
     * @return Result of the operation
     */
    @PostMapping("/restart-config/max-attempts")
    public ResponseEntity<Map<String, Object>> setMaxRestartAttempts(@RequestParam int maxAttempts) {
        if (restartManager == null) {
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "message", "SubprocessRestartManager not available"
            ));
        }

        if (maxAttempts < 0 || maxAttempts > 10) {
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "message", "maxAttempts must be between 0 and 10"
            ));
        }

        restartManager.setMaxRestartAttempts(maxAttempts);

        logger.info("Max restart attempts set to: {}", maxAttempts);

        return ResponseEntity.ok(Map.of(
                "success", true,
                "maxAttempts", maxAttempts,
                "message", "Max restart attempts updated"
        ));
    }
}
