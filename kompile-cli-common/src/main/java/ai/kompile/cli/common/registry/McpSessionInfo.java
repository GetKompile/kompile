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

package ai.kompile.cli.common.registry;

import java.time.Instant;
import java.util.Map;

/**
 * Describes a running MCP stdio session (one per Claude Code / Qwen / etc. agent).
 * Stored as JSON in {@code ~/.kompile/sessions/<sessionId>.json}.
 * <p>
 * Each MCP stdio server registers itself on startup so that sibling agents
 * (spawned by the same parent or working on the same project) can discover
 * each other for A2A communication.
 */
public class McpSessionInfo {

    /** Unique session identifier (e.g. "mcp-1716400000000-abc12"). */
    private String sessionId;

    /** PID of this MCP stdio server process. */
    private long pid;

    /** PID of the parent process that spawned this MCP server (e.g. the kompile CLI passthrough). */
    private long parentPid;

    /** Local HTTP port where the A2A bridge is listening (0 if not started). */
    private int a2aPort;

    /** Working directory for this session. */
    private String workDir;

    /** Agent type running this session: "claude", "qwen", "codex", "gemini", "opencode", "unknown". */
    private String agentType;

    /** Human-readable label for this session (e.g. "coder", "explorer", "reviewer"). */
    private String label;

    /** The MCP tool profile active in this session: "full", "core", "explore", "minimal". */
    private String profile;

    /** When this session was started. */
    private Instant startedAt;

    /** When this session last sent a heartbeat. */
    private Instant lastHeartbeat;

    /** Optional metadata (e.g. current task description, model name). */
    private Map<String, String> metadata;

    public McpSessionInfo() {
    }

    public McpSessionInfo(String sessionId, long pid, long parentPid, String workDir, String agentType) {
        this.sessionId = sessionId;
        this.pid = pid;
        this.parentPid = parentPid;
        this.workDir = workDir;
        this.agentType = agentType;
        this.startedAt = Instant.now();
        this.lastHeartbeat = Instant.now();
    }

    // ── Getters / Setters ──────────────────────────────────────────────────

    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }

    public long getPid() { return pid; }
    public void setPid(long pid) { this.pid = pid; }

    public long getParentPid() { return parentPid; }
    public void setParentPid(long parentPid) { this.parentPid = parentPid; }

    public int getA2aPort() { return a2aPort; }
    public void setA2aPort(int a2aPort) { this.a2aPort = a2aPort; }

    public String getWorkDir() { return workDir; }
    public void setWorkDir(String workDir) { this.workDir = workDir; }

    public String getAgentType() { return agentType; }
    public void setAgentType(String agentType) { this.agentType = agentType; }

    public String getLabel() { return label; }
    public void setLabel(String label) { this.label = label; }

    public String getProfile() { return profile; }
    public void setProfile(String profile) { this.profile = profile; }

    public Instant getStartedAt() { return startedAt; }
    public void setStartedAt(Instant startedAt) { this.startedAt = startedAt; }

    public Instant getLastHeartbeat() { return lastHeartbeat; }
    public void setLastHeartbeat(Instant lastHeartbeat) { this.lastHeartbeat = lastHeartbeat; }

    public Map<String, String> getMetadata() { return metadata; }
    public void setMetadata(Map<String, String> metadata) { this.metadata = metadata; }

    /** Convenience: base URL for A2A communication with this session. */
    public String getA2aBaseUrl() {
        return a2aPort > 0 ? "http://localhost:" + a2aPort : null;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append(sessionId).append(" [").append(agentType).append("]");
        sb.append(" pid=").append(pid);
        if (a2aPort > 0) sb.append(" a2a=:").append(a2aPort);
        if (label != null) sb.append(" label=").append(label);
        if (workDir != null) sb.append(" dir=").append(workDir);
        return sb.toString();
    }
}
