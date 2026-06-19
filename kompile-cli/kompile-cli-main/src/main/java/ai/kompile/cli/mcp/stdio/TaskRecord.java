/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ai.kompile.cli.mcp.stdio;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Durable record for a dispatched agent task. Persisted as JSON to
 * {@code <workDir>/.kompile/task-registry/<taskId>.task.json}.
 *
 * <p>Created before the child process launches so that a task ID is always
 * available even if the MCP tool-call timeout fires before the child completes.
 */
@Data
@Builder
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class TaskRecord {

    /** Task lifecycle states. */
    public enum Status {
        /** Record created, process not yet started. */
        PENDING,
        /** Child process is running. */
        RUNNING,
        /** MCP call timed out but child process is still alive. */
        DETACHED,
        /** Child process completed successfully. */
        COMPLETED,
        /** Child process exited with an error. */
        FAILED,
        /** Task was explicitly cancelled. */
        CANCELLED
    }

    @JsonProperty("taskId")
    private String taskId;

    @JsonProperty("parentTaskId")
    private String parentTaskId;

    @JsonProperty("childTaskIds")
    @Builder.Default
    private List<String> childTaskIds = new ArrayList<>();

    @JsonProperty("taskType")
    private String taskType; // "task", "multi_task", "quorum_task"

    @JsonProperty("agentName")
    private String agentName;

    @JsonProperty("roleName")
    private String roleName;

    @JsonProperty("subtaskName")
    private String subtaskName;

    @JsonProperty("promptSummary")
    private String promptSummary;

    @JsonProperty("status")
    @Builder.Default
    private Status status = Status.PENDING;

    @JsonProperty("pid")
    @Builder.Default
    private long pid = -1;

    @JsonProperty("exitCode")
    @Builder.Default
    private int exitCode = -1;

    @JsonProperty("createdAt")
    private Instant createdAt;

    @JsonProperty("startedAt")
    private Instant startedAt;

    @JsonProperty("completedAt")
    private Instant completedAt;

    @JsonProperty("lastActivity")
    private Instant lastActivity;

    @JsonProperty("outputPath")
    private String outputPath;

    @JsonProperty("resultSummary")
    private String resultSummary;

    @JsonProperty("sessionId")
    private String sessionId;

    @JsonProperty("workDir")
    private String workDir;

    @JsonProperty("description")
    private String description;

    // ── Manual no-arg constructor (sets dynamic defaults that @Builder.Default cannot) ──

    public TaskRecord() {
        this.createdAt = Instant.now();
        this.lastActivity = this.createdAt;
        this.status = Status.PENDING;
        this.pid = -1;
        this.exitCode = -1;
        this.childTaskIds = new ArrayList<>();
    }

    // ── Mutation helpers ─────────────────────────────────────────────────

    public void addChildTaskId(String childId) {
        if (childTaskIds == null) childTaskIds = new ArrayList<>();
        childTaskIds.add(childId);
    }

    public void markRunning(long pid) {
        this.status = Status.RUNNING;
        this.pid = pid;
        this.startedAt = Instant.now();
        this.lastActivity = this.startedAt;
    }

    public void markDetached() {
        this.status = Status.DETACHED;
        this.lastActivity = Instant.now();
    }

    public void markCompleted(int exitCode, String resultSummary, String outputPath) {
        this.status = Status.COMPLETED;
        this.exitCode = exitCode;
        this.completedAt = Instant.now();
        this.lastActivity = this.completedAt;
        this.resultSummary = resultSummary;
        if (outputPath != null) this.outputPath = outputPath;
    }

    public void markFailed(int exitCode, String error) {
        this.status = Status.FAILED;
        this.exitCode = exitCode;
        this.completedAt = Instant.now();
        this.lastActivity = this.completedAt;
        this.resultSummary = error;
    }

    public void markCancelled() {
        this.status = Status.CANCELLED;
        this.completedAt = Instant.now();
        this.lastActivity = this.completedAt;
    }

    public void touch() {
        this.lastActivity = Instant.now();
    }

    /** True if the task is in a terminal state. */
    public boolean isTerminal() {
        return status == Status.COMPLETED || status == Status.FAILED || status == Status.CANCELLED;
    }

    /** True if the task might still have a running process. */
    public boolean isActive() {
        return status == Status.RUNNING || status == Status.DETACHED;
    }
}
