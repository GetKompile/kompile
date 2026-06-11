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

package ai.kompile.cli.main.coordination;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;

/**
 * Represents a background process published to coordination state. Serialized as JSON to
 * {@code <workDir>/.kompile/coordination/processes/<sessionId>-<procId>.proc.json}.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class ProcessCoordEntry {

    @JsonProperty("processId")
    private String processId;

    @JsonProperty("sessionId")
    private String sessionId;

    @JsonProperty("agentName")
    private String agentName;

    @JsonProperty("command")
    private String command;

    @JsonProperty("description")
    private String description;

    @JsonProperty("pid")
    private long pid;

    @JsonProperty("state")
    private String state;

    @JsonProperty("startedAt")
    private Instant startedAt;

    @JsonProperty("lastHeartbeat")
    private Instant lastHeartbeat;

    @JsonProperty("outputFile")
    private String outputFile;

    @JsonProperty("ttlSeconds")
    private int ttlSeconds;

    public ProcessCoordEntry() {}

    public ProcessCoordEntry(String processId, String sessionId, String agentName,
                             String command, String description, long pid, String state,
                             Instant startedAt, String outputFile, int ttlSeconds) {
        this.processId = processId;
        this.sessionId = sessionId;
        this.agentName = agentName;
        this.command = command;
        this.description = description;
        this.pid = pid;
        this.state = state;
        this.startedAt = startedAt;
        this.lastHeartbeat = startedAt;
        this.outputFile = outputFile;
        this.ttlSeconds = ttlSeconds;
    }

    public String getProcessId() { return processId; }
    public String getSessionId() { return sessionId; }
    public String getAgentName() { return agentName; }
    public String getCommand() { return command; }
    public String getDescription() { return description; }
    public long getPid() { return pid; }
    public String getState() { return state; }
    public Instant getStartedAt() { return startedAt; }
    public Instant getLastHeartbeat() { return lastHeartbeat; }
    public String getOutputFile() { return outputFile; }
    public int getTtlSeconds() { return ttlSeconds; }

    public void setLastHeartbeat(Instant lastHeartbeat) {
        this.lastHeartbeat = lastHeartbeat;
    }

    public void setState(String state) { this.state = state; }

    /**
     * Returns true if this entry has exceeded its TTL based on the last heartbeat.
     */
    public boolean isStale() {
        return Instant.now().isAfter(lastHeartbeat.plusSeconds(ttlSeconds));
    }
}
