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

package ai.kompile.cli.main.chat.agent;

import ai.kompile.cli.main.chat.render.TerminalRenderer;
import ai.kompile.utils.StringUtils;
import ai.kompile.cli.main.chat.tools.CliTool;
import ai.kompile.cli.main.chat.tools.ToolContext;
import ai.kompile.cli.main.chat.tools.ToolExecutionException;
import ai.kompile.cli.main.chat.tools.ToolResult;
import ai.kompile.cli.main.chat.tools.ToolRegistry;
import ai.kompile.cli.main.chat.permission.PermissionService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Runs subagents by sending tasks to the kompile-app server's agent streaming
 * endpoint, with local tool execution and proper terminal rendering.
 *
 * Renders subagent activity with box-drawing characters and tool call
 * status indicators, comparable to OpenCode's SubtaskPart rendering.
 */
public class ServerSubagentRunner implements SubagentRunner {

    private final String baseUrl;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final ToolRegistry toolRegistry;
    private final PermissionService permissionService;
    private final TerminalRenderer renderer;
    private volatile LifecycleListener lifecycleListener;
    private final Map<String, ServerSession> sessions = new ConcurrentHashMap<>();

    private static final class ServerSession {
        private final String id;
        private final ToolContext parentContext;
        private final AtomicBoolean cancelled = new AtomicBoolean(false);
        private final AtomicBoolean terminalClaimed = new AtomicBoolean(false);
        private final AtomicReference<java.io.InputStream> responseBody = new AtomicReference<>();
        private volatile String remoteProcessId;
        private volatile Thread ownerThread;

        private ServerSession(String id, ToolContext parentContext) {
            this.id = id;
            this.parentContext = parentContext;
        }
    }

    public ServerSubagentRunner(String baseUrl, ToolRegistry toolRegistry,
                                 PermissionService permissionService, ObjectMapper objectMapper,
                                 TerminalRenderer renderer) {
        this.baseUrl = baseUrl;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        this.objectMapper = objectMapper;
        this.toolRegistry = toolRegistry;
        this.permissionService = permissionService;
        this.renderer = renderer;
    }

    @Override
    public void setLifecycleListener(LifecycleListener listener) {
        this.lifecycleListener = listener;
    }

    @Override
    public String runSubagent(AgentConfig agent, String prompt, ToolContext parentContext) throws Exception {
        long startTime = System.currentTimeMillis();
        String subagentId = agent.getName() + "-"
                + UUID.randomUUID().toString().substring(0, 8);
        ServerSession session = new ServerSession(subagentId, parentContext);
        session.ownerThread = Thread.currentThread();
        sessions.put(subagentId, session);
        if (lifecycleListener != null) {
            lifecycleListener.onSubagentStart(subagentId, agent.getName(), StringUtils.truncate(prompt, 60));
        }
        emitActivity(subagentId, "connecting",
                renderer.renderSubagentStart(agent.getName(), StringUtils.truncate(prompt, 80)),
                parentContext);

        notifyStatus(subagentId, "connecting");
        try {
        // Build the system prompt with available tools
        String systemPrompt = agent.getSystemPrompt() + "\n\n" +
                toolRegistry.buildToolDescriptionsText(agent);

        // Send to server agent endpoint
        ObjectNode request = objectMapper.createObjectNode();
        request.put("message", prompt);
        request.put("agentName", "claude");
        request.put("enableRag", false);
        request.put("skipPermissions", true);
        request.put("timeoutSeconds", 120);
        request.put("systemPromptOverride", systemPrompt);

        String body = objectMapper.writeValueAsString(request);

        HttpRequest httpRequest = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/agents/chat/stream"))
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .timeout(Duration.ofMinutes(5))
                .build();

        HttpResponse<java.io.InputStream> response = httpClient.send(
                httpRequest, HttpResponse.BodyHandlers.ofInputStream());
        session.responseBody.set(response.body());

        if (response.statusCode() != 200) {
            emitActivity(subagentId, "failed · HTTP " + response.statusCode(),
                    renderer.renderSubagentError(agent.getName(),
                            "HTTP " + response.statusCode()), parentContext);
            throw new Exception("Subagent HTTP " + response.statusCode());
        }
        notifyStatus(subagentId, "thinking");

        // Parse SSE stream and collect response
        StringBuilder fullResponse = new StringBuilder();

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(response.body()))) {
            String eventType = null;
            StringBuilder dataBuffer = new StringBuilder();
            String line;

            while ((line = reader.readLine()) != null) {
                if (session.cancelled.get() || parentContext.isAborted()) {
                    emitActivity(subagentId, "aborted",
                            renderer.renderSubagentError(agent.getName(), "Aborted"), parentContext);
                    notifyStatus(subagentId, "aborted");
                    return fullResponse + "\n[Subagent aborted]";
                }

                if (line.startsWith("event:")) {
                    eventType = line.substring(6).trim();
                } else if (line.startsWith("data:")) {
                    dataBuffer.append(line.substring(5).trim());
                } else if (line.isEmpty() && eventType != null) {
                    String data = dataBuffer.toString();
                    switch (eventType) {
                        case "start":
                            try {
                                JsonNode start = objectMapper.readTree(data);
                                session.remoteProcessId = start.path("processId").asText(null);
                                if (session.remoteProcessId == null
                                        || session.remoteProcessId.isBlank()) {
                                    session.remoteProcessId = start.path("process_id").asText(null);
                                }
                            } catch (Exception ignored) {
                            }
                            break;
                        case "chunk":
                            notifyStatus(subagentId, "responding");
                            String chunk = data;
                            if (chunk.startsWith("\"") && chunk.endsWith("\"")) {
                                try { chunk = objectMapper.readValue(chunk, String.class); }
                                catch (Exception ignored) {}
                            }
                            fullResponse.append(chunk);
                            emitOutput(subagentId, chunk);
                            break;

                        case "tool_call":
                            String toolResult = handleToolCall(
                                    data, subagentId, agent, parentContext, session);
                            break;

                        case "error":
                            try {
                                JsonNode error = objectMapper.readTree(data);
                                String errMsg = error.path("message").asText(data);
                                fullResponse.append("\n[Error: ").append(errMsg).append("]");
                                emitActivity(subagentId, "failed · "
                                                + TerminalRenderer.truncatePreview(errMsg, 72),
                                        renderer.renderSubagentError(agent.getName(), errMsg), parentContext);
                                notifyStatus(subagentId, "failed · " + errMsg);
                            } catch (Exception e) {
                                fullResponse.append("\n[Error: ").append(data).append("]");
                                notifyStatus(subagentId, "failed");
                            }
                            break;
                    }
                    eventType = null;
                    dataBuffer.setLength(0);
                }
            }
        }

        long durationMs = System.currentTimeMillis() - startTime;
        String result = fullResponse.toString().trim();

        if (!session.terminalClaimed.compareAndSet(false, true)) {
            notifyStatus(subagentId, "aborted");
            return result + "\n[Subagent aborted]";
        }

        if (!result.isBlank()) emitActivity(subagentId, "responded", "", parentContext);
        notifyStatus(subagentId, "completed");
        emitActivity(subagentId, "completed",
                renderer.renderSubagentComplete(agent.getName(), durationMs), parentContext);

        return result.isEmpty() ? "(subagent returned empty response)" : result;
        } catch (Exception e) {
            if (session.cancelled.get() || parentContext.isAborted()
                    || e instanceof InterruptedException) {
                notifyStatus(subagentId, "aborted");
                emitActivity(subagentId, "aborted",
                        renderer.renderSubagentError(agent.getName(), "Aborted"), parentContext);
                return "[Subagent aborted]";
            }
            notifyStatus(subagentId, "failed · " + e.getClass().getSimpleName());
            emitActivity(subagentId, "failed · " + e.getClass().getSimpleName(),
                    renderer.renderSubagentError(agent.getName(), e.getMessage()), parentContext);
            throw e;
        } finally {
            java.io.InputStream bodyStream = session.responseBody.getAndSet(null);
            if (bodyStream != null) {
                try { bodyStream.close(); } catch (Exception ignored) { }
            }
            session.ownerThread = null;
            if (session.cancelled.get()) Thread.interrupted();
            sessions.remove(subagentId, session);
            if (lifecycleListener != null) {
                lifecycleListener.onSubagentEnd(subagentId);
            }
        }
    }

    @Override
    public boolean canCancel(String subagentId) {
        ServerSession session = sessions.get(subagentId);
        return session != null && !session.terminalClaimed.get() && !session.cancelled.get();
    }

    @Override
    public boolean cancel(String subagentId) {
        ServerSession session = sessions.get(subagentId);
        if (session == null || !session.terminalClaimed.compareAndSet(false, true)) return false;
        session.cancelled.set(true);
        notifyStatus(subagentId, "cancelling");
        java.io.InputStream bodyStream = session.responseBody.getAndSet(null);
        if (bodyStream != null) {
            try { bodyStream.close(); } catch (Exception ignored) { }
        }
        if (session.remoteProcessId != null && !session.remoteProcessId.isBlank()) {
            try {
                httpClient.sendAsync(HttpRequest.newBuilder(
                                URI.create(baseUrl + "/api/agents/chat/cancel/"
                                        + session.remoteProcessId))
                        .timeout(Duration.ofSeconds(5))
                        .POST(HttpRequest.BodyPublishers.noBody())
                        .build(), HttpResponse.BodyHandlers.discarding());
            } catch (RuntimeException ignored) {
            }
        }
        Thread owner = session.ownerThread;
        if (owner != null && owner != Thread.currentThread()) owner.interrupt();
        return true;
    }

    private void notifyStatus(String subagentId, String status) {
        if (lifecycleListener != null) {
            lifecycleListener.onSubagentStatus(subagentId, status);
        }
    }

    private String handleToolCall(String data, String subagentId,
                                  AgentConfig agent, ToolContext parentContext,
                                  ServerSession session) {
        try {
            JsonNode toolCall = objectMapper.readTree(data);
            String toolName = toolCall.path("name").asText("");
            JsonNode arguments = toolCall.path("arguments");
            String rawInput = arguments == null ? "" : arguments.toString();
            String callSummary = TerminalRenderer.summarizeToolCall(toolName, rawInput, 88);
            emitActivity(subagentId, callSummary + " …",
                    renderer.renderToolCallStart(toolName, rawInput), parentContext);

            CliTool tool = toolRegistry.get(toolName);
            if (tool == null) {
                ToolResult failed = ToolResult.error("Tool not found: " + toolName);
                emitActivity(subagentId, callSummary + " ✗ unknown tool",
                        renderer.renderSubagentToolCall(toolName, rawInput, failed), parentContext);
                return "Tool not found: " + toolName;
            }

            List<CliTool> allowed = toolRegistry.getToolsForAgent(agent);
            boolean hasAccess = allowed.stream().anyMatch(t -> t.id().equals(toolName));
            if (!hasAccess) {
                ToolResult failed = ToolResult.error("Tool not available to subagent: " + toolName);
                emitActivity(subagentId, callSummary + " ✗ unavailable",
                        renderer.renderSubagentToolCall(toolName, rawInput, failed), parentContext);
                return "Tool not available to subagent: " + toolName;
            }

            ToolContext subContext = new ToolContext(
                    parentContext.getSessionId() + "-sub",
                    agent,
                    permissionService,
                    parentContext.getWorkingDirectory(),
                    toolRegistry
            );
            subContext.linkAbortCheck(
                    () -> session.cancelled.get() || parentContext.isAborted());
            subContext.setOutputConsumer(parentContext.getOutputConsumer());

            ToolResult result = tool.execute(arguments, subContext);
            String outcome = TerminalRenderer.summarizeToolResult(result, 72);
            emitActivity(subagentId,
                    callSummary + (result.isError() ? " ✗ " : " ✓ ") + outcome,
                    renderer.renderSubagentToolCall(toolName, rawInput, result), parentContext);
            return result.getOutput();

        } catch (ToolExecutionException e) {
            emitActivity(subagentId, "tool failed · "
                            + TerminalRenderer.truncatePreview(e.getMessage(), 72),
                    renderer.renderSubagentError(agent.getName(), e.getMessage()), parentContext);
            return "Tool execution failed: " + e.getMessage();
        } catch (Exception e) {
            emitActivity(subagentId, "tool event malformed",
                    renderer.renderSubagentError(agent.getName(), e.getMessage()), parentContext);
            return "Error parsing tool call: " + e.getMessage();
        }
    }

    private void emitActivity(String subagentId, String summary, String detail,
                              ToolContext parentContext) {
        if (lifecycleListener != null) {
            lifecycleListener.onSubagentActivity(subagentId, summary, detail);
        } else {
            parentContext.emitOutput(detail);
        }
    }

    private void emitOutput(String subagentId, String chunk) {
        LifecycleListener listener = lifecycleListener;
        if (listener != null) listener.onSubagentOutput(subagentId, chunk);
    }

}
