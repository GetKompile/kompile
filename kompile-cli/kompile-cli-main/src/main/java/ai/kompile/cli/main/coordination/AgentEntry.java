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
 * Represents an active agent session. Serialized as JSON to
 * {@code <workDir>/.kompile/coordination/agents/<sessionId>.agent.json}.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class AgentEntry {

    @JsonProperty("sessionId")
    private String sessionId;

    @JsonProperty("agentName")
    private String agentName;

    @JsonProperty("agentType")
    private String agentType;

    @JsonProperty("parentSessionId")
    private String parentSessionId;

    @JsonProperty("depth")
    private int depth;

    @JsonProperty("task")
    private String task;

    @JsonProperty("workDir")
    private String workDir;

    @JsonProperty("pid")
    private long pid;

    @JsonProperty("startedAt")
    private Instant startedAt;

    @JsonProperty("lastHeartbeat")
    private Instant lastHeartbeat;

    @JsonProperty("ttlSeconds")
    private int ttlSeconds;

    public AgentEntry() {}

    public AgentEntry(String sessionId, String agentName, String agentType,
                      String parentSessionId, int depth, String task,
                      String workDir, long pid, Instant startedAt, int ttlSeconds) {
        this.sessionId = sessionId;
        this.agentName = agentName;
        this.agentType = agentType;
        this.parentSessionId = parentSessionId;
        this.depth = depth;
        this.task = task;
        this.workDir = workDir;
        this.pid = pid;
        this.startedAt = startedAt;
        this.lastHeartbeat = startedAt;
        this.ttlSeconds = ttlSeconds;
    }

    public String getSessionId() { return sessionId; }
    public String getAgentName() { return agentName; }
    public String getAgentType() { return agentType; }
    public String getParentSessionId() { return parentSessionId; }
    public int getDepth() { return depth; }
    public String getTask() { return task; }
    public String getWorkDir() { return workDir; }
    public long getPid() { return pid; }
    public Instant getStartedAt() { return startedAt; }
    public Instant getLastHeartbeat() { return lastHeartbeat; }
    public int getTtlSeconds() { return ttlSeconds; }

    public void setLastHeartbeat(Instant lastHeartbeat) {
        this.lastHeartbeat = lastHeartbeat;
    }

    public void setTask(String task) { this.task = task; }

    /**
     * Returns true if this entry has exceeded its TTL based on the last heartbeat.
     */
    public boolean isStale() {
        return Instant.now().isAfter(lastHeartbeat.plusSeconds(ttlSeconds));
    }
}
