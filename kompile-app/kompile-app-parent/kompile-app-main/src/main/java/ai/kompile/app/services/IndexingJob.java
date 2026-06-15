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

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Tracks the progress of an async transcript indexing job.
 * Thread-safe: progress counters are updated from the indexing thread
 * and read from HTTP request threads.
 */
public class IndexingJob {

    public enum Status { QUEUED, RUNNING, COMPLETED, FAILED }

    private final String jobId;
    private final String sourceFilter;
    private final boolean reindex;
    private final Instant createdAt;

    private final AtomicReference<Status> status = new AtomicReference<>(Status.QUEUED);
    private final AtomicReference<Instant> startedAt = new AtomicReference<>();
    private final AtomicReference<Instant> completedAt = new AtomicReference<>();
    private final AtomicReference<String> errorMessage = new AtomicReference<>();

    // Progress counters
    private final AtomicInteger sessionsScanned = new AtomicInteger();
    private final AtomicInteger toolCallsIndexed = new AtomicInteger();
    private final AtomicInteger tokenSummariesPersisted = new AtomicInteger();
    private final AtomicInteger errors = new AtomicInteger();
    private final AtomicLong totalInputTokens = new AtomicLong();
    private final AtomicLong totalOutputTokens = new AtomicLong();

    // Per-source breakdown (updated atomically via synchronized)
    private final Map<String, Integer> bySource = new LinkedHashMap<>();

    // Current activity description
    private final AtomicReference<String> currentActivity = new AtomicReference<>("Queued");

    public IndexingJob(String jobId, String sourceFilter, boolean reindex) {
        this.jobId = jobId;
        this.sourceFilter = sourceFilter;
        this.reindex = reindex;
        this.createdAt = Instant.now();
    }

    // -- Lifecycle --

    public void markRunning() {
        status.set(Status.RUNNING);
        startedAt.set(Instant.now());
        currentActivity.set("Starting...");
    }

    public void markCompleted() {
        status.set(Status.COMPLETED);
        completedAt.set(Instant.now());
        currentActivity.set("Completed");
    }

    public void markFailed(String message) {
        status.set(Status.FAILED);
        completedAt.set(Instant.now());
        errorMessage.set(message);
        currentActivity.set("Failed: " + message);
    }

    // -- Progress updates (called from indexing thread) --

    public void incrementSessions() {
        sessionsScanned.incrementAndGet();
    }

    public void addToolCalls(int count) {
        toolCallsIndexed.addAndGet(count);
    }

    public void incrementTokenSummaries() {
        tokenSummariesPersisted.incrementAndGet();
    }

    public void incrementErrors() {
        errors.incrementAndGet();
    }

    public void addTokens(long input, long output) {
        totalInputTokens.addAndGet(input);
        totalOutputTokens.addAndGet(output);
    }

    public synchronized void addSourceCount(String source, int count) {
        bySource.merge(source, count, Integer::sum);
    }

    public void setCurrentActivity(String activity) {
        currentActivity.set(activity);
    }

    // -- Getters --

    public String getJobId() { return jobId; }
    public String getSourceFilter() { return sourceFilter; }
    public boolean isReindex() { return reindex; }
    public Instant getCreatedAt() { return createdAt; }
    public Status getStatus() { return status.get(); }
    public Instant getStartedAt() { return startedAt.get(); }
    public Instant getCompletedAt() { return completedAt.get(); }
    public String getErrorMessage() { return errorMessage.get(); }
    public int getSessionsScanned() { return sessionsScanned.get(); }
    public int getToolCallsIndexed() { return toolCallsIndexed.get(); }
    public int getTokenSummariesPersisted() { return tokenSummariesPersisted.get(); }
    public int getErrors() { return errors.get(); }
    public long getTotalInputTokens() { return totalInputTokens.get(); }
    public long getTotalOutputTokens() { return totalOutputTokens.get(); }
    public String getCurrentActivity() { return currentActivity.get(); }

    public boolean isTerminal() {
        Status s = status.get();
        return s == Status.COMPLETED || s == Status.FAILED;
    }

    /**
     * Snapshot of current job state as a map, suitable for JSON serialization.
     */
    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("jobId", jobId);
        map.put("status", status.get().name());
        map.put("sourceFilter", sourceFilter);
        map.put("reindex", reindex);
        map.put("createdAt", createdAt.toString());
        Instant s = startedAt.get();
        if (s != null) map.put("startedAt", s.toString());
        Instant c = completedAt.get();
        if (c != null) map.put("completedAt", c.toString());
        String err = errorMessage.get();
        if (err != null) map.put("errorMessage", err);

        Map<String, Object> progress = new LinkedHashMap<>();
        progress.put("sessionsScanned", sessionsScanned.get());
        progress.put("toolCallsIndexed", toolCallsIndexed.get());
        progress.put("tokenSummariesPersisted", tokenSummariesPersisted.get());
        progress.put("errors", errors.get());
        progress.put("totalInputTokens", totalInputTokens.get());
        progress.put("totalOutputTokens", totalOutputTokens.get());
        progress.put("currentActivity", currentActivity.get());
        synchronized (this) {
            progress.put("bySource", new LinkedHashMap<>(bySource));
        }
        map.put("progress", progress);

        if (s != null) {
            Instant end = c != null ? c : Instant.now();
            map.put("elapsedSeconds", java.time.Duration.between(s, end).getSeconds());
        }
        return map;
    }
}
