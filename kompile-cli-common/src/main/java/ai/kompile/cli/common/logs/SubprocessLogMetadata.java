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

package ai.kompile.cli.common.logs;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;

/**
 * Metadata sidecar for a single subprocess run, written as
 * {@code <runId>.meta.json} next to the log file.
 *
 * <p>Populated on start and rewritten on end by {@link SubprocessLogWriter}.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class SubprocessLogMetadata {

    private String subprocessType;
    private String runId;
    private String parentTaskId;
    private List<String> command;
    private String workingDirectory;
    private Instant startedAt;
    private Instant endedAt;
    private Long durationMs;
    private String state;
    private Integer exitCode;
    private String errorMessage;
    private Integer linesWritten;
    private Long pid;
    private String heapSize;
    private Boolean oomDetected;
    private Boolean gpuOomDetected;

    public SubprocessLogMetadata() {
    }

    public String getSubprocessType() {
        return subprocessType;
    }

    public void setSubprocessType(String subprocessType) {
        this.subprocessType = subprocessType;
    }

    public String getRunId() {
        return runId;
    }

    public void setRunId(String runId) {
        this.runId = runId;
    }

    public String getParentTaskId() {
        return parentTaskId;
    }

    public void setParentTaskId(String parentTaskId) {
        this.parentTaskId = parentTaskId;
    }

    public List<String> getCommand() {
        return command;
    }

    public void setCommand(List<String> command) {
        this.command = command;
    }

    public String getWorkingDirectory() {
        return workingDirectory;
    }

    public void setWorkingDirectory(String workingDirectory) {
        this.workingDirectory = workingDirectory;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(Instant startedAt) {
        this.startedAt = startedAt;
    }

    public Instant getEndedAt() {
        return endedAt;
    }

    public void setEndedAt(Instant endedAt) {
        this.endedAt = endedAt;
    }

    public Long getDurationMs() {
        return durationMs;
    }

    public void setDurationMs(Long durationMs) {
        this.durationMs = durationMs;
    }

    public String getState() {
        return state;
    }

    public void setState(String state) {
        this.state = state;
    }

    public Integer getExitCode() {
        return exitCode;
    }

    public void setExitCode(Integer exitCode) {
        this.exitCode = exitCode;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public Integer getLinesWritten() {
        return linesWritten;
    }

    public void setLinesWritten(Integer linesWritten) {
        this.linesWritten = linesWritten;
    }

    public Long getPid() {
        return pid;
    }

    public void setPid(Long pid) {
        this.pid = pid;
    }

    public String getHeapSize() {
        return heapSize;
    }

    public void setHeapSize(String heapSize) {
        this.heapSize = heapSize;
    }

    public Boolean getOomDetected() {
        return oomDetected;
    }

    public void setOomDetected(Boolean oomDetected) {
        this.oomDetected = oomDetected;
    }

    public Boolean getGpuOomDetected() {
        return gpuOomDetected;
    }

    public void setGpuOomDetected(Boolean gpuOomDetected) {
        this.gpuOomDetected = gpuOomDetected;
    }
}
