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

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Duration;
import java.time.Instant;

/**
 * Represents a background process published to coordination state. Serialized as JSON to
 * {@code <workDir>/.kompile/coordination/processes/<sessionId>-<procId>.proc.json}.
 */
@Data
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class ProcessCoordEntry {

    @JsonProperty("processId")
    private String processId;

    @JsonProperty("sessionId")
    private String sessionId;

    @JsonProperty("agentName")
    private String agentName;

    @JsonProperty("roleName")
    private String roleName;

    @JsonProperty("kind")
    private String kind;

    @JsonProperty("resourceClass")
    private String resourceClass;

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

    @JsonProperty("endedAt")
    private Instant endedAt;

    @JsonProperty("exitCode")
    private Integer exitCode;

    @JsonProperty("lastHeartbeat")
    private Instant lastHeartbeat;

    @JsonProperty("outputFile")
    private String outputFile;

    @JsonProperty("ttlSeconds")
    private int ttlSeconds;

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

    /**
     * Returns true if this entry has exceeded its TTL based on the last heartbeat.
     */
    @JsonIgnore
    public boolean isStale() {
        Instant heartbeat = lastHeartbeat != null
                ? lastHeartbeat
                : endedAt != null ? endedAt : startedAt;
        if (heartbeat == null) return true;
        int ttl = ttlSeconds > 0 ? ttlSeconds : 120;
        return Instant.now().isAfter(heartbeat.plusSeconds(ttl));
    }

    @JsonIgnore
    public boolean isRunningState() {
        if (state == null || state.isBlank()) return true;
        String normalized = state.trim().toUpperCase(java.util.Locale.ROOT);
        return normalized.equals("RUNNING")
                || normalized.equals("STARTING")
                || normalized.equals("ACTIVE");
    }

    @JsonIgnore
    public boolean isTerminalState() {
        return !isRunningState();
    }

    /** Elapsed runtime, frozen at the terminal timestamp when one exists. */
    @JsonIgnore
    public Duration getDuration() {
        if (startedAt == null) return Duration.ZERO;
        Instant end = endedAt != null ? endedAt : Instant.now();
        return end.isBefore(startedAt) ? Duration.ZERO : Duration.between(startedAt, end);
    }
}
