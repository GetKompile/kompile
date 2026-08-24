/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.cli.main.chat.tools.grounding;

import ai.kompile.cli.main.chat.tools.ToolResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

/**
 * Bounded, process-local lifecycle registry for asynchronous project crawls.
 *
 * <p>The registry is deliberately owned by the MCP host rather than by a model
 * runtime child. A crawl keeps its request-scoped workers until the job reaches
 * a terminal state, and completed handles are retained for a bounded period so
 * an agent can poll and then retrieve the result.</p>
 */
final class LocalCrawlJobRegistry {
    static final long DEFAULT_POLL_AFTER_MS = 1_000L;
    private static final long COMPLETED_RETENTION_MS =
            Long.getLong("kompile.crawl.asyncRetentionMs", 3_600_000L);
    private static final int MAX_COMPLETED_JOBS =
            Math.max(16, Integer.getInteger("kompile.crawl.asyncMaxCompleted", 128));
    private static final ConcurrentHashMap<String, AsyncJob> JOBS = new ConcurrentHashMap<>();
    private static final Pattern LOCAL_JOB_ID = Pattern.compile(
            "local-[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-5][0-9a-fA-F]{3}-"
                    + "[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}");
    private static final ExecutorService EXECUTOR = Executors.newFixedThreadPool(
            Math.max(1, Math.min(4, Integer.getInteger("kompile.crawl.asyncParallelism", 2))),
            new DaemonThreadFactory());

    private LocalCrawlJobRegistry() {
    }

    static String newJobId() {
        return "local-" + UUID.randomUUID();
    }

    static boolean isJobId(String value) {
        return value != null && LOCAL_JOB_ID.matcher(value.trim()).matches();
    }

    static AsyncJob submit(String jobId,
                           String knowledgeBase,
                           Runnable cancelHook,
                           Callable<ToolResult> work) {
        return submit(jobId, knowledgeBase, null, null, cancelHook, work);
    }

    static AsyncJob submit(String jobId,
                           String knowledgeBase,
                           Path projectRoot,
                           JsonNode request,
                           Runnable cancelHook,
                           Callable<ToolResult> work) {
        cleanup();
        LocalCrawlJobStore.initialize(projectRoot, jobId, knowledgeBase, request);
        AsyncJob job = new AsyncJob(jobId, knowledgeBase, projectRoot, cancelHook, work);
        FutureTask<Void> task = new FutureTask<>(() -> {
            job.run();
            return null;
        });
        job.future = task;
        JOBS.put(jobId, job);
        EXECUTOR.execute(task);
        return job;
    }

    static AsyncJob get(String jobId) {
        cleanup();
        return jobId == null ? null : JOBS.get(jobId);
    }

    static int activeCount() {
        cleanup();
        int active = 0;
        for (AsyncJob job : JOBS.values()) {
            if (!job.terminal()) {
                active++;
            }
        }
        return active;
    }

    static Map<String, Object> activeSummary() {
        cleanup();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("activeJobs", activeCount());
        result.put("trackedJobs", JOBS.size());
        return result;
    }

    static ToolResult status(String jobId, ObjectMapper mapper) {
        return status(jobId, mapper, null);
    }

    static ToolResult status(String jobId, ObjectMapper mapper, Path projectRoot) {
        AsyncJob job = get(jobId);
        if (job == null) {
            return LocalCrawlJobStore.storedStatus(projectRoot, jobId, mapper);
        }
        return job.statusResult(mapper);
    }

    static ToolResult result(String jobId, ObjectMapper mapper) {
        return result(jobId, mapper, null);
    }

    static ToolResult result(String jobId, ObjectMapper mapper, Path projectRoot) {
        AsyncJob job = get(jobId);
        if (job == null) {
            return LocalCrawlJobStore.storedResult(projectRoot, jobId, mapper);
        }
        return job.resultResult(mapper);
    }

    static ToolResult transcript(String jobId, ObjectMapper mapper, Path projectRoot) {
        AsyncJob job = get(jobId);
        Path root = job == null ? projectRoot : job.projectRoot;
        return LocalCrawlJobStore.transcript(root, jobId, mapper);
    }

    static ArrayNode retainedJobs(Path projectRoot, ObjectMapper mapper) {
        return mapper.valueToTree(LocalCrawlJobStore.list(projectRoot));
    }

    static boolean cancel(String jobId) {
        AsyncJob job = get(jobId);
        return job != null && job.cancel();
    }

    static void updateStage(String jobId, String stage, String detail, int progressPercent) {
        AsyncJob job = get(jobId);
        if (job != null) {
            job.updateStage(stage, detail, progressPercent);
        }
    }

    static void updatePipelineProgress(String jobId, String stage, String detail,
                                       int progressPercent, Map<String, Object> progress) {
        AsyncJob job = get(jobId);
        if (job != null) {
            job.updatePipelineProgress(stage, detail, progressPercent, progress);
        }
    }

    private static void cleanup() {
        long now = System.currentTimeMillis();
        for (AsyncJob job : JOBS.values()) {
            if (job.terminal()) {
                if (job.finishedAt() != null
                        && now - job.finishedAt().toEpochMilli() > COMPLETED_RETENTION_MS) {
                    JOBS.remove(job.jobId(), job);
                }
            }
        }
        long completed = JOBS.values().stream().filter(AsyncJob::terminal).count();
        if (completed <= MAX_COMPLETED_JOBS) {
            return;
        }
        JOBS.values().stream()
                .filter(AsyncJob::terminal)
                .sorted((left, right) -> {
                    Instant l = left.finishedAt();
                    Instant r = right.finishedAt();
                    if (l == null && r == null) return 0;
                    if (l == null) return -1;
                    if (r == null) return 1;
                    return l.compareTo(r);
                })
                .limit(Math.max(0L, completed - MAX_COMPLETED_JOBS))
                .forEach(job -> JOBS.remove(job.jobId(), job));
    }

    static final class AsyncJob {
        private final String jobId;
        private final String knowledgeBase;
        private final Path projectRoot;
        private final Runnable cancelHook;
        private final Callable<ToolResult> work;
        private final Instant createdAt = Instant.now();
        private volatile Instant startedAt;
        private volatile Instant finishedAt;
        private volatile Future<?> future;
        private volatile ToolResult result;
        private volatile boolean cancelRequested;
        private volatile String stage = "QUEUED";
        private volatile String stageDetail = "Waiting for a project-local crawl worker";
        private volatile int progressPercent;
        private volatile Instant stageUpdatedAt = createdAt;
        private volatile Map<String, Object> pipelineProgress = Map.of();
        private final AtomicBoolean workerEntered = new AtomicBoolean(false);

        private AsyncJob(String jobId,
                         String knowledgeBase,
                         Path projectRoot,
                         Runnable cancelHook,
                         Callable<ToolResult> work) {
            this.jobId = jobId;
            this.knowledgeBase = knowledgeBase == null || knowledgeBase.isBlank()
                    ? null : knowledgeBase;
            this.projectRoot = LocalCrawlJobStore.normalizeRoot(projectRoot);
            this.cancelHook = cancelHook;
            this.work = work;
        }

        String jobId() {
            return jobId;
        }

        Instant finishedAt() {
            return finishedAt;
        }

        boolean terminal() {
            return terminalStatus(status());
        }

        String status() {
            if (cancelRequested && finishedAt != null) {
                return "CANCELLED";
            }
            ToolResult completed = result;
            if (completed != null) {
                Object requested = completed.getMetadata().get("status");
                if (requested != null && terminalStatus(String.valueOf(requested))) {
                    return String.valueOf(requested).toUpperCase(Locale.ROOT);
                }
                return completed.isError() ? "FAILED" : "COMPLETED";
            }
            Future<?> submitted = future;
            if (startedAt == null) {
                if (cancelRequested) return "CANCELLING";
                return "QUEUED";
            }
            if (cancelRequested) return "CANCELLING";
            if (submitted != null && submitted.isDone()) {
                return "FAILED";
            }
            return "RUNNING";
        }

        private void run() {
            if (!workerEntered.compareAndSet(false, true)) return;
            startedAt = Instant.now();
            updateStage("PREPARING", "Preparing the project-local crawl request", 5);
            persist("JOB_STARTED");
            try {
                if (cancelRequested) {
                    result = ToolResult.error("Project-local crawl cancelled before execution.");
                    return;
                }
                ToolResult completed = work.call();
                result = cancelRequested
                        ? ToolResult.error("Project-local crawl cancelled.")
                        : completed;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                result = ToolResult.error("Project-local crawl cancelled.");
            } catch (Throwable failure) {
                String message = failure.getMessage();
                result = ToolResult.error("Project-local asynchronous crawl failed: "
                        + failure.getClass().getName()
                        + (message == null || message.isBlank() ? "" : ": " + message));
            } finally {
                finish();
            }
        }

        private synchronized void finish() {
            finishedAt = Instant.now();
            String terminalStage = status();
            stage = terminalStage;
            stageDetail = switch (terminalStage) {
                case "COMPLETED", "COMPLETED_WITH_ERRORS" -> "Project-local crawl finished";
                case "CANCELLED" -> "Project-local crawl was cancelled";
                default -> "Project-local crawl failed";
            };
            progressPercent = 100;
            stageUpdatedAt = finishedAt;
            persist("JOB_TERMINAL");
        }

        private synchronized void updateStage(String nextStage, String detail, int percent) {
            if (nextStage == null || nextStage.isBlank() || terminal()
                    || (cancelRequested && !"CANCELLING".equalsIgnoreCase(nextStage))) {
                return;
            }
            stage = nextStage.trim().toUpperCase(Locale.ROOT);
            stageDetail = detail == null || detail.isBlank() ? null : detail.trim();
            progressPercent = Math.max(0, Math.min(99, percent));
            stageUpdatedAt = Instant.now();
            persist("JOB_STAGE");
        }

        private synchronized void updatePipelineProgress(String nextStage, String detail, int percent,
                                                         Map<String, Object> progress) {
            if (cancelRequested || terminal()) return;
            pipelineProgress = progress == null ? Map.of() : Map.copyOf(progress);
            updateStage(nextStage, detail, percent);
        }

        private synchronized boolean cancel() {
            if (terminal()) {
                return false;
            }
            cancelRequested = true;
            updateStage("CANCELLING", "Stopping the project-local crawl worker", progressPercent);
            if (cancelHook != null) {
                try {
                    cancelHook.run();
                } catch (RuntimeException ignored) {
                    // The interrupt below is still attempted.
                }
            }
            Future<?> submitted = future;
            if (submitted != null) {
                boolean cancelled = submitted.cancel(true);
                if (cancelled && workerEntered.compareAndSet(false, true)) {
                    result = ToolResult.error("Project-local crawl cancelled before execution.");
                    finishedAt = Instant.now();
                    stage = "CANCELLED";
                    stageDetail = "Project-local crawl was cancelled";
                    progressPercent = 100;
                    stageUpdatedAt = finishedAt;
                    persist("JOB_TERMINAL");
                }
            }
            return true;
        }

        private ToolResult statusResult(ObjectMapper mapper) {
            Map<String, Object> payload = payload();
            Map<String, Object> metadata = new LinkedHashMap<>();
            attachHandle(mapper, payload, metadata);
            metadata.put("status", status());
            metadata.put("terminal", terminal());
            metadata.put("stage", stage);
            if (stageDetail != null) metadata.put("stageDetail", stageDetail);
            metadata.put("progressPercent", progressPercent);
            metadata.put("stageUpdatedAt", stageUpdatedAt.toString());
            if (!pipelineProgress.isEmpty()) metadata.put("pipelineProgress", pipelineProgress);
            return ToolResult.success("crawl_status", json(mapper, payload), metadata);
        }

        private ToolResult resultResult(ObjectMapper mapper) {
            if (!terminal()) {
                return statusResult(mapper);
            }
            ToolResult completed = result;
            if (completed == null) {
                if ("CANCELLED".equals(status())) {
                    return ToolResult.error("Project-local crawl cancelled: " + jobId);
                }
                return ToolResult.error("Project-local crawl ended without a result: " + jobId);
            }
            Map<String, Object> metadata = new LinkedHashMap<>(completed.getMetadata());
            Map<String, Object> payload = payload();
            attachHandle(mapper, payload, metadata);
            metadata.put("jobId", jobId);
            metadata.put("status", status());
            metadata.put("terminal", true);
            metadata.put("stage", stage);
            if (stageDetail != null) metadata.put("stageDetail", stageDetail);
            metadata.put("progressPercent", progressPercent);
            metadata.put("stageUpdatedAt", stageUpdatedAt.toString());
            if (!pipelineProgress.isEmpty()) metadata.put("pipelineProgress", pipelineProgress);
            return new ToolResult(completed.getTitle(), completed.getOutput(), metadata,
                    completed.isError() || "FAILED".equals(status()));
        }

        private Map<String, Object> payload() {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("schema", "kompile-crawl-result/v1");
            payload.put("backend", "project-local");
            payload.put("jobId", jobId);
            if (knowledgeBase != null) {
                payload.put("knowledgeBase", knowledgeBase);
            }
            payload.put("status", status());
            payload.put("terminal", terminal());
            payload.put("resultAvailable", terminal() && result != null);
            payload.put("stage", stage);
            if (stageDetail != null) payload.put("stageDetail", stageDetail);
            payload.put("progressPercent", progressPercent);
            payload.put("stageUpdatedAt", stageUpdatedAt.toString());
            if (!pipelineProgress.isEmpty()) payload.put("pipelineProgress", pipelineProgress);
            payload.put("pollAfterMs", DEFAULT_POLL_AFTER_MS);
            payload.put("createdAt", createdAt.toString());
            if (startedAt != null) payload.put("startedAt", startedAt.toString());
            if (finishedAt != null) payload.put("finishedAt", finishedAt.toString());
            return payload;
        }

        private ObjectNode persistentState(ObjectMapper mapper) {
            ObjectNode state = mapper.valueToTree(payload());
            state.put("schema", LocalCrawlJobStore.SCHEMA);
            state.put("ownerPid", ProcessHandle.current().pid());
            if (knowledgeBase != null) state.put("knowledgeBaseId", knowledgeBase);
            ToolResult completed = result;
            if (completed != null) {
                ObjectNode storedResult = state.putObject("result");
                storedResult.put("title", completed.getTitle());
                storedResult.put("output", completed.getOutput());
                storedResult.set("metadata", mapper.valueToTree(completed.getMetadata()));
                storedResult.put("error", completed.isError());
            }
            return state;
        }

        private void persist(String eventType) {
            if (projectRoot == null) return;
            LocalCrawlJobStore.persist(projectRoot, persistentState(new ObjectMapper()), eventType);
        }

        private void attachHandle(ObjectMapper mapper,
                                  Map<String, Object> payload,
                                  Map<String, Object> metadata) {
            ObjectNode node = mapper.valueToTree(payload);
            CrawlResultHandle.from(node, "project-local", jobId, knowledgeBase)
                    .attachTo(metadata);
            payload.put("crawlResult", metadata.get("crawlResult"));
            payload.put("nextActions", metadata.get("nextActions"));
        }

        private static String json(ObjectMapper mapper, Map<String, Object> value) {
            try {
                return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(value);
            } catch (Exception e) {
                return value.toString();
            }
        }

        private static boolean terminalStatus(String value) {
            if (value == null) return false;
            return switch (value.toUpperCase(Locale.ROOT)) {
                case "COMPLETED", "COMPLETED_WITH_ERRORS", "SUCCESS", "SUCCEEDED",
                        "FAILED", "CANCELLED", "CANCELED", "SKIPPED" -> true;
                default -> false;
            };
        }
    }

    private static final class DaemonThreadFactory implements ThreadFactory {
        private int sequence;

        @Override
        public synchronized Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "kompile-local-crawl-" + (++sequence));
            thread.setDaemon(true);
            return thread;
        }
    }
}
