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
 * Metadata sidecar for a single agent run, written as
 * {@code <processId>.meta.json} next to the log file.
 *
 * <p>Populated when the run starts and rewritten when the run finishes.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AgentLogMetadata {

    private String processId;
    private String agentName;
    private String orchestratorInstanceId;
    private Long sessionId;
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
    private Double costUsd;
    private Integer numTurns;

    public AgentLogMetadata() {
    }

    public String getProcessId() {
        return processId;
    }

    public void setProcessId(String processId) {
        this.processId = processId;
    }

    public String getAgentName() {
        return agentName;
    }

    public void setAgentName(String agentName) {
        this.agentName = agentName;
    }

    public String getOrchestratorInstanceId() {
        return orchestratorInstanceId;
    }

    public void setOrchestratorInstanceId(String orchestratorInstanceId) {
        this.orchestratorInstanceId = orchestratorInstanceId;
    }

    public Long getSessionId() {
        return sessionId;
    }

    public void setSessionId(Long sessionId) {
        this.sessionId = sessionId;
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

    public Double getCostUsd() {
        return costUsd;
    }

    public void setCostUsd(Double costUsd) {
        this.costUsd = costUsd;
    }

    public Integer getNumTurns() {
        return numTurns;
    }

    public void setNumTurns(Integer numTurns) {
        this.numTurns = numTurns;
    }
}
