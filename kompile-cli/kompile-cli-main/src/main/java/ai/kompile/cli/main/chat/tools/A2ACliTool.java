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

import ai.kompile.cli.common.registry.McpSessionInfo;
import ai.kompile.cli.common.registry.McpSessionRegistry;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

/**
 * CLI-side MCP tool for A2A (Agent-to-Agent) protocol communication.
 * <p>
 * This tool runs inside the MCP stdio server (no Spring dependencies) and
 * enables passthrough chat agents to discover, list, and send tasks to remote
 * A2A agents. It reads agent configurations from {@code ~/.kompile/config/a2a-agents.json}
 * (the same file the server-side A2ARegistryService uses) and communicates
 * directly via HTTP using the A2A JSON-RPC protocol.
 * <p>
 * Supported actions:
 * <ul>
 *   <li>{@code discover} — resolve an agent card from a URL and register it</li>
 *   <li>{@code list} — list all registered remote agents</li>
 *   <li>{@code send} — send a synchronous task to a remote agent</li>
 *   <li>{@code send_async} — send a task asynchronously, returns a delegation ID</li>
 *   <li>{@code get_result} — poll for or wait on an async delegation result</li>
 *   <li>{@code cancel} — cancel an active async delegation</li>
 *   <li>{@code ping} — check connectivity to a remote agent</li>
 *   <li>{@code list_siblings} — discover sibling MCP sessions on this machine</li>
 *   <li>{@code send_local} — send a message to a sibling session by session ID</li>
 *   <li>{@code discover_local} — auto-register all live sibling sessions as A2A agents</li>
 * </ul>
 */
public class A2ACliTool implements CliTool {

    private static final String TOOL_ID = "a2a";

    private final ObjectMapper mapper;
    private final HttpClient httpClient;
    private final Path configPath;
    private final Map<String, AsyncDelegation> asyncDelegations = new ConcurrentHashMap<>();
    private final ExecutorService asyncExecutor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "a2a-cli-async");
        t.setDaemon(true);
        return t;
    });

    /** This session's ID (set by McpStdioCommand after session registration). */
    private volatile String mySessionId;

    public A2ACliTool() {
        this.mapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
                .configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false);
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        this.configPath = Path.of(System.getProperty("user.home"),
                ".kompile", "config", "a2a-agents.json");
    }

    /**
     * Sets this session's ID so local discovery can exclude self.
     */
    public void setMySessionId(String sessionId) {
        this.mySessionId = sessionId;
    }

    @Override
    public String id() {
        return TOOL_ID;
    }

    @Override
    public String description() {
        return "Communicate with A2A (Agent-to-Agent) agents — both remote and local sibling sessions. " +
                "Remote actions: 'discover' (register by URL), 'list' (registered agents), " +
                "'send' (sync task), 'send_async' (non-blocking), 'get_result' (poll), 'cancel', 'ping'. " +
                "Local actions: 'list_siblings' (discover sibling MCP sessions on this machine), " +
                "'send_local' (message a sibling by session_id), 'discover_local' (auto-register all siblings). " +
                "Use local actions to coordinate with other Claude/Qwen/Codex agents spawned by the same CLI.";
    }

    @Override
    public JsonNode parameterSchema() {
        ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");

        props.putObject("action").put("type", "string")
                .put("description",
                        "Action: 'discover', 'list', 'send', 'send_async', 'get_result', 'cancel', 'ping' " +
                        "(remote agents), or 'list_siblings', 'send_local', 'discover_local' (local sessions)");
        props.putObject("base_url").put("type", "string")
                .put("description", "Base URL of remote agent (for 'discover'). E.g. http://host:9090");
        props.putObject("agent_id").put("type", "string")
                .put("description", "Agent ID (for 'send', 'send_async', 'ping')");
        props.putObject("session_id").put("type", "string")
                .put("description", "Target session ID (for 'send_local'). Get from 'list_siblings'.");
        props.putObject("message").put("type", "string")
                .put("description", "Task message/prompt to send (for 'send', 'send_async', 'send_local')");
        props.putObject("delegation_id").put("type", "string")
                .put("description", "Delegation ID (for 'get_result', 'cancel')");
        props.putObject("blocking").put("type", "boolean")
                .put("description", "Wait for result (default true) or return immediately (for 'get_result')");
        props.putObject("timeout_seconds").put("type", "integer")
                .put("description", "Timeout in seconds for synchronous send (default 300)");

        schema.putArray("required").add("action");
        return schema;
    }

    @Override
    public String permissionKey() {
        return "a2a";
    }

    @Override
    public McpToolAnnotations mcpAnnotations() {
        return McpToolAnnotations.DELEGATION;
    }

    @Override
    public ToolResult execute(JsonNode params, ToolContext context) throws ToolExecutionException {
        String action = params.path("action").asText("");

        return switch (action) {
            case "discover" -> doDiscover(params);
            case "list" -> doList();
            case "send" -> doSend(params);
            case "send_async" -> doSendAsync(params);
            case "get_result" -> doGetResult(params);
            case "cancel" -> doCancel(params);
            case "ping" -> doPing(params);
            // Local session actions
            case "list_siblings" -> doListSiblings();
            case "send_local" -> doSendLocal(params);
            case "discover_local" -> doDiscoverLocal();
            default -> ToolResult.error("Unknown action: " + action +
                    ". Remote: discover, list, send, send_async, get_result, cancel, ping. " +
                    "Local: list_siblings, send_local, discover_local");
        };
    }

    // ── Actions ─────────────────────────────────────────────────────────────

    private ToolResult doDiscover(JsonNode params) {
        String baseUrl = params.path("base_url").asText("");
        if (baseUrl.isBlank()) {
            return ToolResult.error("base_url is required for 'discover'");
        }
        baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;

        try {
            // Fetch agent card
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/.well-known/agent-card.json"))
                    .header("Accept", "application/json")
                    .timeout(Duration.ofSeconds(10))
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                return ToolResult.error("Failed to discover agent at " + baseUrl +
                        ": HTTP " + response.statusCode());
            }

            JsonNode card = mapper.readTree(response.body());
            String agentName = card.path("name").asText("unknown");

            // Build config entry
            Map<String, Object> config = new LinkedHashMap<>();
            String agentId = agentName.toLowerCase().replaceAll("[^a-z0-9-]", "-");
            config.put("id", agentId);
            config.put("name", agentName);
            config.put("baseUrl", baseUrl);
            config.put("enabled", true);
            config.put("reachable", true);
            config.put("registeredAt", Instant.now().toString());
            config.put("lastContactedAt", Instant.now().toString());

            // Save to registry
            Map<String, Map<String, Object>> agents = loadAgents();
            agents.put(agentId, config);
            saveAgents(agents);

            StringBuilder sb = new StringBuilder();
            sb.append("Agent discovered and registered:\n");
            sb.append("  ID:          ").append(agentId).append("\n");
            sb.append("  Name:        ").append(agentName).append("\n");
            sb.append("  URL:         ").append(baseUrl).append("\n");
            sb.append("  Description: ").append(card.path("description").asText("")).append("\n");

            JsonNode skills = card.path("skills");
            if (skills.isArray() && !skills.isEmpty()) {
                sb.append("  Skills:      ").append(skills.size()).append("\n");
                for (JsonNode skill : skills) {
                    sb.append("    - ").append(skill.path("name").asText())
                            .append(": ").append(skill.path("description").asText("")).append("\n");
                }
            }

            sb.append("\nUse a2a send with agent_id='").append(agentId).append("' to send tasks.");
            return ToolResult.success("A2A Agent Discovered", sb.toString());

        } catch (Exception e) {
            return ToolResult.error("Discovery failed: " + e.getMessage());
        }
    }

    private ToolResult doList() {
        try {
            Map<String, Map<String, Object>> agents = loadAgents();

            if (agents.isEmpty()) {
                return ToolResult.success("A2A Agents", "No remote agents registered.\n" +
                        "Use a2a discover with base_url to register a remote agent.");
            }

            StringBuilder sb = new StringBuilder();
            sb.append("Registered A2A Agents (").append(agents.size()).append("):\n\n");

            for (Map.Entry<String, Map<String, Object>> entry : agents.entrySet()) {
                Map<String, Object> agent = entry.getValue();
                sb.append("  ").append(entry.getKey()).append(":\n");
                sb.append("    Name:       ").append(agent.getOrDefault("name", "unknown")).append("\n");
                sb.append("    URL:        ").append(agent.getOrDefault("baseUrl", "")).append("\n");
                sb.append("    Enabled:    ").append(agent.getOrDefault("enabled", true)).append("\n");
                sb.append("    Reachable:  ").append(agent.getOrDefault("reachable", false)).append("\n");
                String lastContact = String.valueOf(agent.getOrDefault("lastContactedAt", "never"));
                sb.append("    Last seen:  ").append(lastContact).append("\n");
                sb.append("\n");
            }

            // Also list async delegations in progress
            if (!asyncDelegations.isEmpty()) {
                sb.append("Active Async Delegations (").append(asyncDelegations.size()).append("):\n");
                for (Map.Entry<String, AsyncDelegation> entry : asyncDelegations.entrySet()) {
                    AsyncDelegation d = entry.getValue();
                    long elapsed = System.currentTimeMillis() - d.startTime;
                    sb.append("  ").append(entry.getKey())
                            .append(" → ").append(d.agentId)
                            .append(" (").append(elapsed / 1000).append("s")
                            .append(d.future.isDone() ? ", done" : ", running")
                            .append(")\n");
                }
            }

            return ToolResult.success("A2A Agents", sb.toString());
        } catch (Exception e) {
            return ToolResult.error("Failed to list agents: " + e.getMessage());
        }
    }

    private ToolResult doSend(JsonNode params) {
        String agentId = params.path("agent_id").asText("");
        String message = params.path("message").asText("");

        if (agentId.isBlank()) return ToolResult.error("agent_id is required for 'send'");
        if (message.isBlank()) return ToolResult.error("message is required for 'send'");

        try {
            Map<String, Object> agent = loadAgents().get(agentId);
            if (agent == null) {
                return ToolResult.error("Agent not found: " + agentId +
                        ". Use a2a list to see registered agents, or a2a discover to register one.");
            }

            String baseUrl = String.valueOf(agent.get("baseUrl"));
            long startTime = System.currentTimeMillis();

            // Build JSON-RPC request
            Map<String, Object> rpcRequest = new LinkedHashMap<>();
            rpcRequest.put("jsonrpc", "2.0");
            rpcRequest.put("id", UUID.randomUUID().toString());
            rpcRequest.put("method", "message/send");
            rpcRequest.put("params", Map.of(
                    "message", Map.of(
                            "role", "user",
                            "parts", List.of(Map.of("type", "text", "text", message))
                    ),
                    "contextId", UUID.randomUUID().toString()
            ));

            int timeout = params.path("timeout_seconds").asInt(300);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/a2a"))
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .timeout(Duration.ofSeconds(timeout))
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(rpcRequest)))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            long elapsed = System.currentTimeMillis() - startTime;

            if (response.statusCode() != 200) {
                return ToolResult.error("Task failed: HTTP " + response.statusCode() + " - " + response.body());
            }

            JsonNode rpcResponse = mapper.readTree(response.body());
            if (rpcResponse.has("error") && !rpcResponse.path("error").isNull()) {
                return ToolResult.error("A2A error: " + rpcResponse.path("error").path("message").asText());
            }

            // Update last contacted
            updateLastContacted(agentId);

            // Extract response text from artifacts
            JsonNode result = rpcResponse.path("result");
            String responseText = extractResponseText(result);

            StringBuilder sb = new StringBuilder();
            sb.append("Task completed (").append(elapsed).append("ms):\n");
            sb.append("  Agent:  ").append(agentId).append("\n");
            sb.append("  State:  ").append(result.path("status").path("state").asText("unknown")).append("\n");
            if (!responseText.isEmpty()) {
                sb.append("\nResponse:\n").append(responseText);
            }

            return ToolResult.success("A2A Task Complete", sb.toString());

        } catch (Exception e) {
            return ToolResult.error("Send failed: " + e.getMessage());
        }
    }

    private ToolResult doSendAsync(JsonNode params) {
        String agentId = params.path("agent_id").asText("");
        String message = params.path("message").asText("");

        if (agentId.isBlank()) return ToolResult.error("agent_id is required for 'send_async'");
        if (message.isBlank()) return ToolResult.error("message is required for 'send_async'");

        try {
            Map<String, Object> agent = loadAgents().get(agentId);
            if (agent == null) {
                return ToolResult.error("Agent not found: " + agentId);
            }

            String baseUrl = String.valueOf(agent.get("baseUrl"));
            String delegationId = "a2a-" + UUID.randomUUID().toString().substring(0, 8);
            long startTime = System.currentTimeMillis();

            CompletableFuture<String> future = CompletableFuture.supplyAsync(() -> {
                try {
                    Map<String, Object> rpcRequest = new LinkedHashMap<>();
                    rpcRequest.put("jsonrpc", "2.0");
                    rpcRequest.put("id", UUID.randomUUID().toString());
                    rpcRequest.put("method", "message/send");
                    rpcRequest.put("params", Map.of(
                            "message", Map.of(
                                    "role", "user",
                                    "parts", List.of(Map.of("type", "text", "text", message))
                            ),
                            "contextId", UUID.randomUUID().toString()
                    ));

                    HttpRequest request = HttpRequest.newBuilder()
                            .uri(URI.create(baseUrl + "/a2a"))
                            .header("Content-Type", "application/json")
                            .header("Accept", "application/json")
                            .timeout(Duration.ofSeconds(600))
                            .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(rpcRequest)))
                            .build();

                    HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                    return response.body();
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }, asyncExecutor);

            asyncDelegations.put(delegationId, new AsyncDelegation(delegationId, agentId, message, future, startTime));

            return ToolResult.success("A2A Async Task Sent",
                    "Task sent asynchronously to " + agentId + ".\n" +
                            "Delegation ID: " + delegationId + "\n" +
                            "Use a2a get_result with delegation_id='" + delegationId + "' to check the result.");

        } catch (Exception e) {
            return ToolResult.error("Async send failed: " + e.getMessage());
        }
    }

    private ToolResult doGetResult(JsonNode params) {
        String delegationId = params.path("delegation_id").asText("");
        if (delegationId.isBlank()) return ToolResult.error("delegation_id is required for 'get_result'");

        AsyncDelegation delegation = asyncDelegations.get(delegationId);
        if (delegation == null) {
            return ToolResult.error("Delegation not found: " + delegationId);
        }

        if (delegation.future.isDone()) {
            asyncDelegations.remove(delegationId);
            try {
                String body = delegation.future.get();
                long elapsed = System.currentTimeMillis() - delegation.startTime;

                JsonNode rpcResponse = mapper.readTree(body);
                if (rpcResponse.has("error") && !rpcResponse.path("error").isNull()) {
                    return ToolResult.error("A2A error: " + rpcResponse.path("error").path("message").asText());
                }

                JsonNode result = rpcResponse.path("result");
                String responseText = extractResponseText(result);

                StringBuilder sb = new StringBuilder();
                sb.append("Async task completed (").append(elapsed).append("ms):\n");
                sb.append("  Agent:  ").append(delegation.agentId).append("\n");
                sb.append("  State:  ").append(result.path("status").path("state").asText("unknown")).append("\n");
                if (!responseText.isEmpty()) {
                    sb.append("\nResponse:\n").append(responseText);
                }
                return ToolResult.success("A2A Async Result", sb.toString());
            } catch (Exception e) {
                return ToolResult.error("Failed to get result: " + e.getMessage());
            }
        }

        boolean blocking = params.path("blocking").asBoolean(true);
        if (!blocking) {
            long elapsed = System.currentTimeMillis() - delegation.startTime;
            return ToolResult.success("A2A Task Pending",
                    "Task still running (" + elapsed / 1000 + "s elapsed).\n" +
                            "Agent: " + delegation.agentId + "\n" +
                            "Use a2a get_result again to check.");
        }

        // Block and wait
        try {
            String body = delegation.future.get(600, TimeUnit.SECONDS);
            asyncDelegations.remove(delegationId);
            long elapsed = System.currentTimeMillis() - delegation.startTime;

            JsonNode rpcResponse = mapper.readTree(body);
            JsonNode result = rpcResponse.path("result");
            String responseText = extractResponseText(result);

            StringBuilder sb = new StringBuilder();
            sb.append("Async task completed (").append(elapsed).append("ms):\n");
            sb.append("  Agent:  ").append(delegation.agentId).append("\n");
            if (!responseText.isEmpty()) {
                sb.append("\nResponse:\n").append(responseText);
            }
            return ToolResult.success("A2A Async Result", sb.toString());
        } catch (TimeoutException e) {
            return ToolResult.error("Timeout waiting for result from " + delegation.agentId);
        } catch (Exception e) {
            asyncDelegations.remove(delegationId);
            return ToolResult.error("Failed: " + e.getMessage());
        }
    }

    private ToolResult doCancel(JsonNode params) {
        String delegationId = params.path("delegation_id").asText("");
        if (delegationId.isBlank()) return ToolResult.error("delegation_id is required for 'cancel'");

        AsyncDelegation delegation = asyncDelegations.remove(delegationId);
        if (delegation == null) {
            return ToolResult.error("Delegation not found: " + delegationId);
        }

        delegation.future.cancel(true);
        return ToolResult.success("A2A Task Cancelled",
                "Cancelled delegation " + delegationId + " to " + delegation.agentId);
    }

    private ToolResult doPing(JsonNode params) {
        String agentId = params.path("agent_id").asText("");
        if (agentId.isBlank()) return ToolResult.error("agent_id is required for 'ping'");

        try {
            Map<String, Object> agent = loadAgents().get(agentId);
            if (agent == null) {
                return ToolResult.error("Agent not found: " + agentId);
            }

            String baseUrl = String.valueOf(agent.get("baseUrl"));
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/a2a/health"))
                    .header("Accept", "application/json")
                    .timeout(Duration.ofSeconds(5))
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            boolean reachable = response.statusCode() == 200;

            // Update registry
            Map<String, Map<String, Object>> agents = loadAgents();
            Map<String, Object> agentConfig = agents.get(agentId);
            if (agentConfig != null) {
                agentConfig.put("reachable", reachable);
                agentConfig.put("lastContactedAt", Instant.now().toString());
                saveAgents(agents);
            }

            return ToolResult.success("A2A Ping",
                    agentId + ": " + (reachable ? "reachable" : "unreachable"));

        } catch (Exception e) {
            // Update as unreachable
            try {
                Map<String, Map<String, Object>> agents = loadAgents();
                Map<String, Object> agentConfig = agents.get(agentId);
                if (agentConfig != null) {
                    agentConfig.put("reachable", false);
                    saveAgents(agents);
                }
            } catch (IOException ignored) {}

            return ToolResult.success("A2A Ping", agentId + ": unreachable (" + e.getMessage() + ")");
        }
    }

    // ── Local session actions ──────────────────────────────────────────────

    private ToolResult doListSiblings() {
        try {
            List<McpSessionInfo> allSessions = McpSessionRegistry.listAlive();

            if (allSessions.isEmpty()) {
                return ToolResult.success("Local Sessions",
                        "No MCP sessions registered. This session may not have registered itself yet.");
            }

            // Determine my parent PID for sibling detection
            long myPid = ProcessHandle.current().pid();
            long myParentPid = ProcessHandle.current().parent()
                    .map(ProcessHandle::pid).orElse(-1L);

            StringBuilder sb = new StringBuilder();
            sb.append("Local MCP Sessions (").append(allSessions.size()).append(" total):\n\n");

            // Categorize: self, siblings (same parent), same-project peers, others
            McpSessionInfo self = null;
            List<McpSessionInfo> siblings = new ArrayList<>();
            List<McpSessionInfo> peers = new ArrayList<>();
            List<McpSessionInfo> others = new ArrayList<>();

            for (McpSessionInfo info : allSessions) {
                if (mySessionId != null && mySessionId.equals(info.getSessionId())) {
                    self = info;
                } else if (myParentPid > 0 && info.getParentPid() == myParentPid) {
                    siblings.add(info);
                } else if (self != null && self.getWorkDir() != null
                        && self.getWorkDir().equals(info.getWorkDir())) {
                    peers.add(info);
                } else {
                    others.add(info);
                }
            }

            // Show self
            if (self != null) {
                sb.append("THIS SESSION:\n");
                formatSession(sb, self, "  ");
                sb.append("\n");
            }

            // Show siblings (same parent — spawned together)
            if (!siblings.isEmpty()) {
                sb.append("SIBLINGS (same parent, spawned together):\n");
                for (McpSessionInfo s : siblings) {
                    formatSession(sb, s, "  ");
                }
                sb.append("\n");
            }

            // Show peers (same project dir)
            if (!peers.isEmpty()) {
                sb.append("PEERS (same project directory):\n");
                for (McpSessionInfo s : peers) {
                    formatSession(sb, s, "  ");
                }
                sb.append("\n");
            }

            // Show others
            if (!others.isEmpty()) {
                sb.append("OTHER SESSIONS:\n");
                for (McpSessionInfo s : others) {
                    formatSession(sb, s, "  ");
                }
                sb.append("\n");
            }

            if (siblings.isEmpty() && peers.isEmpty() && others.isEmpty()) {
                sb.append("No other sessions found — this is the only running agent.\n");
            } else {
                sb.append("Use 'send_local' with session_id to message a sibling.\n");
                sb.append("Use 'discover_local' to auto-register all siblings as A2A agents.\n");
            }

            return ToolResult.success("Local Sessions", sb.toString());

        } catch (Exception e) {
            return ToolResult.error("Failed to list siblings: " + e.getMessage());
        }
    }

    private void formatSession(StringBuilder sb, McpSessionInfo info, String indent) {
        sb.append(indent).append(info.getSessionId())
                .append(" [").append(info.getAgentType()).append("]");
        if (info.getLabel() != null) sb.append(" label=").append(info.getLabel());
        sb.append(" pid=").append(info.getPid());
        if (info.getA2aPort() > 0) {
            sb.append(" a2a=:").append(info.getA2aPort());
        } else {
            sb.append(" (no A2A bridge)");
        }
        sb.append("\n");
        if (info.getWorkDir() != null) {
            sb.append(indent).append("  dir: ").append(info.getWorkDir()).append("\n");
        }
        if (info.getProfile() != null) {
            sb.append(indent).append("  profile: ").append(info.getProfile()).append("\n");
        }
        if (info.getLastHeartbeat() != null) {
            long agoSec = java.time.Duration.between(info.getLastHeartbeat(), Instant.now()).getSeconds();
            sb.append(indent).append("  last heartbeat: ").append(agoSec).append("s ago\n");
        }
    }

    private ToolResult doSendLocal(JsonNode params) {
        String targetSessionId = params.path("session_id").asText("");
        String message = params.path("message").asText("");

        if (targetSessionId.isBlank()) return ToolResult.error("session_id is required for 'send_local'");
        if (message.isBlank()) return ToolResult.error("message is required for 'send_local'");

        try {
            McpSessionInfo target = McpSessionRegistry.getIfAlive(targetSessionId);
            if (target == null) {
                return ToolResult.error("Session not found or dead: " + targetSessionId +
                        ". Use 'list_siblings' to see active sessions.");
            }

            // Try HTTP first (if the target has an A2A bridge running)
            if (target.getA2aPort() > 0) {
                try {
                    return sendViaHttp(target, message);
                } catch (Exception e) {
                    // Fall through to file-based routing
                    System.err.println("[A2A] HTTP send failed, falling back to mailbox: " + e.getMessage());
                }
            }

            // Fall back to file-based mailbox
            return sendViaMailbox(target, message);

        } catch (Exception e) {
            return ToolResult.error("Send failed: " + e.getMessage());
        }
    }

    private ToolResult sendViaHttp(McpSessionInfo target, String message) throws Exception {
        String baseUrl = target.getA2aBaseUrl();

        Map<String, Object> rpcRequest = new LinkedHashMap<>();
        rpcRequest.put("jsonrpc", "2.0");
        rpcRequest.put("id", UUID.randomUUID().toString());
        rpcRequest.put("method", "message/send");
        rpcRequest.put("params", Map.of(
                "message", Map.of(
                        "role", "user",
                        "parts", List.of(Map.of("type", "text", "text", message))
                ),
                "contextId", UUID.randomUUID().toString()
        ));

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/a2a"))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .header("X-Session-Id", mySessionId != null ? mySessionId : "unknown")
                .timeout(Duration.ofSeconds(30))
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(rpcRequest)))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new IOException("HTTP " + response.statusCode() + ": " + response.body());
        }

        JsonNode rpcResponse = mapper.readTree(response.body());
        if (rpcResponse.has("error") && !rpcResponse.path("error").isNull()) {
            return ToolResult.error("A2A error: " + rpcResponse.path("error").path("message").asText());
        }

        JsonNode result = rpcResponse.path("result");
        String responseText = extractResponseText(result);

        StringBuilder sb = new StringBuilder();
        sb.append("Message delivered via HTTP to ").append(target.getSessionId())
                .append(" [").append(target.getAgentType()).append("]\n");
        sb.append("State: ").append(result.path("status").path("state").asText("unknown")).append("\n");
        if (!responseText.isEmpty()) {
            sb.append("\nResponse:\n").append(responseText);
        }

        return ToolResult.success("Local A2A Message Sent", sb.toString());
    }

    private ToolResult sendViaMailbox(McpSessionInfo target, String message) throws IOException {
        Map<String, Object> mailboxMsg = new LinkedHashMap<>();
        mailboxMsg.put("senderSessionId", mySessionId != null ? mySessionId : "unknown");
        mailboxMsg.put("message", message);
        mailboxMsg.put("sentAt", Instant.now().toString());

        McpSessionRegistry.writeToInbox(target.getSessionId(),
                mapper.writeValueAsString(mailboxMsg));

        return ToolResult.success("Local A2A Message Queued",
                "Message delivered to mailbox of " + target.getSessionId() +
                        " [" + target.getAgentType() + "].\n" +
                        "The target agent will receive it when it next polls its inbox (~5 seconds).\n" +
                        (target.getA2aPort() <= 0 ?
                                "Note: target has no A2A bridge — mailbox is the only delivery method." : ""));
    }

    private ToolResult doDiscoverLocal() {
        try {
            List<McpSessionInfo> allSessions = McpSessionRegistry.listAlive();
            Map<String, Map<String, Object>> agents = loadAgents();
            int registered = 0;

            for (McpSessionInfo info : allSessions) {
                // Skip self
                if (mySessionId != null && mySessionId.equals(info.getSessionId())) continue;
                // Skip sessions without A2A bridge
                if (info.getA2aPort() <= 0) continue;

                String agentId = "local-" + info.getSessionId();

                // Check if already registered and up-to-date
                Map<String, Object> existing = agents.get(agentId);
                if (existing != null
                        && String.valueOf(existing.get("baseUrl")).equals(info.getA2aBaseUrl())) {
                    // Already registered with same URL — just update timestamp
                    existing.put("lastContactedAt", Instant.now().toString());
                    existing.put("reachable", true);
                    continue;
                }

                // Register as a new A2A agent
                Map<String, Object> config = new LinkedHashMap<>();
                config.put("id", agentId);
                config.put("name", info.getLabel() != null ? info.getLabel() : info.getSessionId());
                config.put("baseUrl", info.getA2aBaseUrl());
                config.put("enabled", true);
                config.put("reachable", true);
                config.put("local", true);
                config.put("sessionId", info.getSessionId());
                config.put("agentType", info.getAgentType());
                config.put("pid", info.getPid());
                config.put("registeredAt", Instant.now().toString());
                config.put("lastContactedAt", Instant.now().toString());
                agents.put(agentId, config);
                registered++;
            }

            saveAgents(agents);

            if (registered == 0) {
                return ToolResult.success("Local Discovery",
                        "No new local agents to register. " + agents.size() + " agents in registry total.\n" +
                        "Run 'list_siblings' to see all MCP sessions (including those without A2A bridges).");
            }

            return ToolResult.success("Local Discovery",
                    "Registered " + registered + " local agent(s). " + agents.size() + " agents in registry total.\n" +
                    "You can now use 'send' with these agent IDs, or 'list' to see all registered agents.");

        } catch (Exception e) {
            return ToolResult.error("Local discovery failed: " + e.getMessage());
        }
    }

    // ── Config persistence ──────────────────────────────────────────────────

    private Map<String, Map<String, Object>> loadAgents() throws IOException {
        if (!Files.exists(configPath)) {
            return new LinkedHashMap<>();
        }
        String json = Files.readString(configPath);
        if (json.isBlank()) return new LinkedHashMap<>();
        return mapper.readValue(json, new TypeReference<>() {});
    }

    private void saveAgents(Map<String, Map<String, Object>> agents) throws IOException {
        Files.createDirectories(configPath.getParent());
        mapper.writerWithDefaultPrettyPrinter().writeValue(configPath.toFile(), agents);
    }

    private void updateLastContacted(String agentId) {
        try {
            Map<String, Map<String, Object>> agents = loadAgents();
            Map<String, Object> agent = agents.get(agentId);
            if (agent != null) {
                agent.put("reachable", true);
                agent.put("lastContactedAt", Instant.now().toString());
                saveAgents(agents);
            }
        } catch (IOException ignored) {}
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private String extractResponseText(JsonNode taskResult) {
        StringBuilder text = new StringBuilder();
        JsonNode artifacts = taskResult.path("artifacts");
        if (artifacts.isArray()) {
            for (JsonNode artifact : artifacts) {
                JsonNode parts = artifact.path("parts");
                if (parts.isArray()) {
                    for (JsonNode part : parts) {
                        if ("text".equals(part.path("type").asText())) {
                            text.append(part.path("text").asText());
                        }
                    }
                }
            }
        }
        return text.toString();
    }

    private record AsyncDelegation(
            String delegationId,
            String agentId,
            String message,
            CompletableFuture<String> future,
            long startTime
    ) {}
}
