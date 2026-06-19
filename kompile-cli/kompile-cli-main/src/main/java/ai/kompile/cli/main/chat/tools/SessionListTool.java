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

package ai.kompile.cli.main.chat.tools;

import ai.kompile.cli.common.registry.InstanceInfo;
import ai.kompile.cli.common.util.JsonUtils;
import ai.kompile.utils.FormatUtils;
import ai.kompile.utils.StringUtils;
import ai.kompile.cli.common.registry.InstanceRegistry;
import ai.kompile.cli.common.registry.McpSessionInfo;
import ai.kompile.cli.common.registry.McpSessionRegistry;
import ai.kompile.cli.main.coordination.CoordinationStateManager;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * CLI MCP tool for listing running kompile sessions, instances, and agents.
 * <p>
 * Provides a unified view of:
 * <ul>
 *   <li>Running kompile-app instances (from {@code ~/.kompile/instances/})</li>
 *   <li>Active agent sessions from coordination state (edit locks, registered agents, processes)</li>
 *   <li>Server-side passthrough sessions (via REST API if a kompile-app is running)</li>
 * </ul>
 * <p>
 * This enables agents to discover what else is running and coordinate accordingly —
 * e.g., checking if a build process is already active before starting another one.
 */
public class SessionListTool implements CliTool {

    private static final String TOOL_ID = "sessions";

    private final CoordinationStateManager coordinator;
    private final ObjectMapper mapper;
    private final HttpClient httpClient;

    public SessionListTool(CoordinationStateManager coordinator) {
        this.coordinator = coordinator;
        this.mapper = JsonUtils.standardMapper();
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .build();
    }

    @Override
    public String id() {
        return TOOL_ID;
    }

    @Override
    public String description() {
        return "List running kompile instances, active agent sessions, and background processes. " +
                "Shows kompile-app servers, passthrough chat sessions, active edit locks, and " +
                "background processes across all agents. Use this to check what's running before " +
                "starting builds, to find agents for A2A coordination, or to monitor active work.";
    }

    @Override
    public JsonNode parameterSchema() {
        ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");

        props.putObject("section").put("type", "string")
                .put("description",
                        "Which section to show: 'all' (default), 'instances', 'agents', 'processes', 'mcp_sessions', 'sessions'");

        return schema;
    }

    @Override
    public String permissionKey() {
        return "read";
    }

    @Override
    public McpToolAnnotations mcpAnnotations() {
        return McpToolAnnotations.READ_ONLY;
    }

    @Override
    public ToolResult execute(JsonNode params, ToolContext context) throws ToolExecutionException {
        String section = params.path("section").asText("all");

        StringBuilder sb = new StringBuilder();
        sb.append("=== Kompile Session Dashboard ===\n");
        sb.append("Time: ").append(Instant.now()).append("\n\n");

        boolean showAll = "all".equals(section);

        // ── Running kompile-app instances ────────────────────────────────
        if (showAll || "instances".equals(section)) {
            appendInstances(sb);
        }

        // ── Active agents from coordination ─────────────────────────────
        if (showAll || "agents".equals(section)) {
            appendAgents(sb);
        }

        // ── Active processes from coordination ──────────────────────────
        if (showAll || "processes".equals(section)) {
            appendProcesses(sb);
        }

        // ── MCP agent sessions (local A2A-capable) ────────────────────
        if (showAll || "mcp_sessions".equals(section)) {
            appendMcpSessions(sb);
        }

        // ── Server-side passthrough sessions (if kompile-app is reachable) ──
        if (showAll || "sessions".equals(section)) {
            appendServerSessions(sb);
        }

        return ToolResult.success("Sessions", sb.toString());
    }

    private void appendInstances(StringBuilder sb) {
        sb.append("KOMPILE INSTANCES\n");
        try {
            List<InstanceInfo> instances = InstanceRegistry.listAll();
            if (instances.isEmpty()) {
                sb.append("  (no running instances)\n");
            } else {
                for (InstanceInfo info : instances) {
                    sb.append(String.format("  %-20s [%-6s] port=%-5d pid=%-7d %s\n",
                            info.getName(),
                            info.getType(),
                            info.getPort(),
                            info.getPid(),
                            isProcessAlive(info.getPid()) ? "ALIVE" : "STALE"));
                    if (info.getStartedAt() != null) {
                        String uptime = FormatUtils.formatDuration(Duration.between(info.getStartedAt(), Instant.now()));
                        sb.append("                       uptime: ").append(uptime).append("\n");
                    }
                }
            }
        } catch (IOException e) {
            sb.append("  (error reading instances: ").append(e.getMessage()).append(")\n");
        }
        sb.append("\n");
    }

    private void appendAgents(StringBuilder sb) {
        sb.append("ACTIVE AGENTS\n");
        if (coordinator == null) {
            sb.append("  (coordination not available)\n\n");
            return;
        }

        var agents = coordinator.queryAgents();
        if (agents.isEmpty()) {
            sb.append("  (none)\n");
        } else {
            for (var a : agents) {
                String dur = FormatUtils.formatDuration(Duration.between(a.getStartedAt(), Instant.now()));
                sb.append(String.format("  %-30s %-12s depth=%d  pid=%-7d  %s\n",
                        a.getSessionId(),
                        a.getAgentName(),
                        a.getDepth(),
                        a.getPid(),
                        dur));
                if (a.getTask() != null && !a.getTask().isEmpty()) {
                    sb.append("    task: ").append(StringUtils.truncate(a.getTask(), 60)).append("\n");
                }
            }
        }

        // Also show active edit locks
        var edits = coordinator.queryEdits();
        if (!edits.isEmpty()) {
            sb.append("\n  Active Edit Locks (").append(edits.size()).append("):\n");
            for (var e : edits) {
                String age = FormatUtils.formatDuration(Duration.between(e.getAcquiredAt(), Instant.now()));
                sb.append(String.format("    %-50s %-6s %s\n",
                        StringUtils.truncate(e.getFilePath(), 50), e.getEditType(), age));
            }
        }
        sb.append("\n");
    }

    private void appendProcesses(StringBuilder sb) {
        sb.append("BACKGROUND PROCESSES\n");
        if (coordinator == null) {
            sb.append("  (coordination not available)\n\n");
            return;
        }

        var processes = coordinator.queryProcesses();
        if (processes.isEmpty()) {
            sb.append("  (none)\n");
        } else {
            for (var p : processes) {
                String dur = FormatUtils.formatDuration(Duration.between(p.getStartedAt(), Instant.now()));
                sb.append(String.format("  %-12s %-10s %-30s %s\n",
                        p.getProcessId(), p.getState(),
                        StringUtils.truncate(p.getCommand(), 30), dur));
                if (p.getDescription() != null && !p.getDescription().isEmpty()) {
                    sb.append("    ").append(StringUtils.truncate(p.getDescription(), 60)).append("\n");
                }
            }
        }
        sb.append("\n");
    }

    private void appendServerSessions(StringBuilder sb) {
        sb.append("SERVER-SIDE SESSIONS\n");

        // Try to reach each running kompile-app instance for session data
        try {
            List<InstanceInfo> instances = InstanceRegistry.listAll();
            boolean found = false;
            for (InstanceInfo info : instances) {
                if (!isProcessAlive(info.getPid())) continue;

                try {
                    String url = "http://localhost:" + info.getPort() + "/api/agents/passthrough/sessions";
                    HttpRequest request = HttpRequest.newBuilder()
                            .uri(URI.create(url))
                            .header("Accept", "application/json")
                            .timeout(Duration.ofSeconds(3))
                            .GET()
                            .build();
                    HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

                    if (response.statusCode() == 200) {
                        JsonNode sessions = mapper.readTree(response.body());
                        if (sessions.isArray() && !sessions.isEmpty()) {
                            found = true;
                            sb.append("  From ").append(info.getName())
                                    .append(" (port ").append(info.getPort()).append("):\n");
                            for (JsonNode session : sessions) {
                                sb.append("    ").append(session.path("sessionId").asText())
                                        .append("  agent=").append(session.path("agentName").asText("unknown"))
                                        .append("  status=").append(session.path("status").asText("unknown"))
                                        .append("\n");
                            }
                        }
                    }
                } catch (Exception ignored) {
                    // Instance not reachable or doesn't support this endpoint
                }
            }

            if (!found) {
                sb.append("  (no server-side sessions found)\n");
            }
        } catch (IOException e) {
            sb.append("  (error: ").append(e.getMessage()).append(")\n");
        }
        sb.append("\n");
    }

    private void appendMcpSessions(StringBuilder sb) {
        sb.append("MCP AGENT SESSIONS\n");
        try {
            List<McpSessionInfo> sessions = McpSessionRegistry.listAlive();
            if (sessions.isEmpty()) {
                sb.append("  (no MCP sessions registered)\n");
            } else {
                for (McpSessionInfo info : sessions) {
                    sb.append(String.format("  %-35s [%-7s] pid=%-7d",
                            StringUtils.truncate(info.getSessionId(), 35),
                            info.getAgentType(),
                            info.getPid()));
                    if (info.getA2aPort() > 0) {
                        sb.append(String.format(" a2a=:%-5d", info.getA2aPort()));
                    } else {
                        sb.append("            ");
                    }
                    sb.append(isProcessAlive(info.getPid()) ? " ALIVE" : " STALE");
                    sb.append("\n");
                    if (info.getWorkDir() != null) {
                        sb.append("                                      dir: ").append(info.getWorkDir()).append("\n");
                    }
                    if (info.getStartedAt() != null) {
                        String uptime = FormatUtils.formatDuration(Duration.between(info.getStartedAt(), Instant.now()));
                        sb.append("                                      uptime: ").append(uptime).append("\n");
                    }
                }
            }
        } catch (IOException e) {
            sb.append("  (error reading sessions: ").append(e.getMessage()).append(")\n");
        }
        sb.append("\n");
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private static boolean isProcessAlive(long pid) {
        if (pid <= 0) return false;
        try {
            return ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
        } catch (Exception e) {
            return false;
        }
    }

}
