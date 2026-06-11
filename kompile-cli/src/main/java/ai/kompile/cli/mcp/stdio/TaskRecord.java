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
    private Status status = Status.PENDING;

    @JsonProperty("pid")
    private long pid = -1;

    @JsonProperty("exitCode")
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

    public TaskRecord() {
        this.createdAt = Instant.now();
        this.lastActivity = this.createdAt;
    }

    public TaskRecord(String taskId, String taskType, String agentName, String description,
                      String promptSummary, String sessionId, String workDir) {
        this.taskId = taskId;
        this.taskType = taskType;
        this.agentName = agentName;
        this.description = description;
        this.promptSummary = promptSummary;
        this.sessionId = sessionId;
        this.workDir = workDir;
        this.createdAt = Instant.now();
        this.lastActivity = this.createdAt;
    }

    // ── Getters ──────────────────────────────────────────────────────────

    public String getTaskId() { return taskId; }
    public String getParentTaskId() { return parentTaskId; }
    public List<String> getChildTaskIds() { return childTaskIds; }
    public String getTaskType() { return taskType; }
    public String getAgentName() { return agentName; }
    public String getRoleName() { return roleName; }
    public String getSubtaskName() { return subtaskName; }
    public String getPromptSummary() { return promptSummary; }
    public Status getStatus() { return status; }
    public long getPid() { return pid; }
    public int getExitCode() { return exitCode; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getStartedAt() { return startedAt; }
    public Instant getCompletedAt() { return completedAt; }
    public Instant getLastActivity() { return lastActivity; }
    public String getOutputPath() { return outputPath; }
    public String getResultSummary() { return resultSummary; }
    public String getSessionId() { return sessionId; }
    public String getWorkDir() { return workDir; }
    public String getDescription() { return description; }

    // ── Setters ──────────────────────────────────────────────────────────

    public void setTaskId(String taskId) { this.taskId = taskId; }
    public void setParentTaskId(String parentTaskId) { this.parentTaskId = parentTaskId; }
    public void setChildTaskIds(List<String> childTaskIds) { this.childTaskIds = childTaskIds; }
    public void setTaskType(String taskType) { this.taskType = taskType; }
    public void setAgentName(String agentName) { this.agentName = agentName; }
    public void setRoleName(String roleName) { this.roleName = roleName; }
    public void setSubtaskName(String subtaskName) { this.subtaskName = subtaskName; }
    public void setPromptSummary(String promptSummary) { this.promptSummary = promptSummary; }
    public void setStatus(Status status) { this.status = status; }
    public void setPid(long pid) { this.pid = pid; }
    public void setExitCode(int exitCode) { this.exitCode = exitCode; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public void setStartedAt(Instant startedAt) { this.startedAt = startedAt; }
    public void setCompletedAt(Instant completedAt) { this.completedAt = completedAt; }
    public void setLastActivity(Instant lastActivity) { this.lastActivity = lastActivity; }
    public void setOutputPath(String outputPath) { this.outputPath = outputPath; }
    public void setResultSummary(String resultSummary) { this.resultSummary = resultSummary; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }
    public void setWorkDir(String workDir) { this.workDir = workDir; }
    public void setDescription(String description) { this.description = description; }

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
