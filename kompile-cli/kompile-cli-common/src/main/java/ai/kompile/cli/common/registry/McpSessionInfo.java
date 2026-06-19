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

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

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
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
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
