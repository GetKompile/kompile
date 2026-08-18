/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.cli.main.chat.tools.grounding;

import ai.kompile.cli.main.chat.tools.ToolResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

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
import java.util.concurrent.ThreadFactory;

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
    private static final ExecutorService EXECUTOR = Executors.newFixedThreadPool(
            Math.max(1, Math.min(4, Integer.getInteger("kompile.crawl.asyncParallelism", 2))),
            new DaemonThreadFactory());

    private LocalCrawlJobRegistry() {
    }

    static String newJobId() {
        return "local-" + UUID.randomUUID();
    }

    static AsyncJob submit(String jobId,
                           String knowledgeBase,
                           Runnable cancelHook,
                           Callable<ToolResult> work) {
        cleanup();
        AsyncJob job = new AsyncJob(jobId, knowledgeBase, cancelHook, work);
        JOBS.put(jobId, job);
        job.future = EXECUTOR.submit(job::run);
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
        AsyncJob job = get(jobId);
        if (job == null) {
            return ToolResult.error("Unknown project-local crawl job: " + jobId);
        }
        return job.statusResult(mapper);
    }

    static ToolResult result(String jobId, ObjectMapper mapper) {
        AsyncJob job = get(jobId);
        if (job == null) {
            return ToolResult.error("Unknown project-local crawl job: " + jobId);
        }
        return job.resultResult(mapper);
    }

    static boolean cancel(String jobId) {
        AsyncJob job = get(jobId);
        return job != null && job.cancel();
    }

    private static void cleanup() {
        long now = System.currentTimeMillis();
        int completed = 0;
        for (AsyncJob job : JOBS.values()) {
            if (job.terminal()) {
                completed++;
                if (job.finishedAt() != null
                        && now - job.finishedAt().toEpochMilli() > COMPLETED_RETENTION_MS) {
                    JOBS.remove(job.jobId(), job);
                }
            }
        }
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
                .limit(completed - MAX_COMPLETED_JOBS)
                .forEach(job -> JOBS.remove(job.jobId(), job));
    }

    static final class AsyncJob {
        private final String jobId;
        private final String knowledgeBase;
        private final Runnable cancelHook;
        private final Callable<ToolResult> work;
        private final Instant createdAt = Instant.now();
        private volatile Instant startedAt;
        private volatile Instant finishedAt;
        private volatile Future<?> future;
        private volatile ToolResult result;
        private volatile boolean cancelRequested;

        private AsyncJob(String jobId,
                         String knowledgeBase,
                         Runnable cancelHook,
                         Callable<ToolResult> work) {
            this.jobId = jobId;
            this.knowledgeBase = knowledgeBase == null || knowledgeBase.isBlank()
                    ? null : knowledgeBase;
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
            if (cancelRequested) {
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
            if (submitted != null && submitted.isCancelled()) {
                return "CANCELLED";
            }
            if (startedAt == null) {
                return "QUEUED";
            }
            if (submitted != null && submitted.isDone()) {
                return "FAILED";
            }
            return "RUNNING";
        }

        private void run() {
            startedAt = Instant.now();
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
            } catch (Exception e) {
                result = ToolResult.error("Project-local asynchronous crawl failed: "
                        + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
            } finally {
                finishedAt = Instant.now();
            }
        }

        private boolean cancel() {
            if (terminal()) {
                return false;
            }
            cancelRequested = true;
            if (cancelHook != null) {
                try {
                    cancelHook.run();
                } catch (RuntimeException ignored) {
                    // The interrupt below is still attempted.
                }
            }
            Future<?> submitted = future;
            if (submitted != null) {
                submitted.cancel(true);
            }
            return true;
        }

        private ToolResult statusResult(ObjectMapper mapper) {
            Map<String, Object> payload = payload();
            Map<String, Object> metadata = new LinkedHashMap<>();
            attachHandle(mapper, payload, metadata);
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
            payload.put("pollAfterMs", DEFAULT_POLL_AFTER_MS);
            payload.put("createdAt", createdAt.toString());
            if (startedAt != null) payload.put("startedAt", startedAt.toString());
            if (finishedAt != null) payload.put("finishedAt", finishedAt.toString());
            return payload;
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
