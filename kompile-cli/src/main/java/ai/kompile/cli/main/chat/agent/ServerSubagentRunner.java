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
    public String runSubagent(AgentConfig agent, String prompt, ToolContext parentContext) throws Exception {
        long startTime = System.currentTimeMillis();
        System.out.println(renderer.renderSubagentStart(agent.getName(), truncate(prompt, 80)));

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

        if (response.statusCode() != 200) {
            long durationMs = System.currentTimeMillis() - startTime;
            System.out.println(renderer.renderSubagentError(agent.getName(),
                    "HTTP " + response.statusCode()));
            throw new Exception("Subagent HTTP " + response.statusCode());
        }

        // Parse SSE stream and collect response
        StringBuilder fullResponse = new StringBuilder();

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(response.body()))) {
            String eventType = null;
            StringBuilder dataBuffer = new StringBuilder();
            String line;

            while ((line = reader.readLine()) != null) {
                if (parentContext.isAborted()) {
                    long durationMs = System.currentTimeMillis() - startTime;
                    System.out.println(renderer.renderSubagentError(agent.getName(), "Aborted"));
                    return fullResponse + "\n[Subagent aborted]";
                }

                if (line.startsWith("event:")) {
                    eventType = line.substring(6).trim();
                } else if (line.startsWith("data:")) {
                    dataBuffer.append(line.substring(5).trim());
                } else if (line.isEmpty() && eventType != null) {
                    String data = dataBuffer.toString();
                    switch (eventType) {
                        case "chunk":
                            String chunk = data;
                            if (chunk.startsWith("\"") && chunk.endsWith("\"")) {
                                try { chunk = objectMapper.readValue(chunk, String.class); }
                                catch (Exception ignored) {}
                            }
                            fullResponse.append(chunk);
                            break;

                        case "tool_call":
                            String toolResult = handleToolCall(data, agent, parentContext);
                            break;

                        case "error":
                            try {
                                JsonNode error = objectMapper.readTree(data);
                                String errMsg = error.path("message").asText(data);
                                fullResponse.append("\n[Error: ").append(errMsg).append("]");
                                System.out.println(renderer.renderSubagentError(
                                        agent.getName(), errMsg));
                            } catch (Exception e) {
                                fullResponse.append("\n[Error: ").append(data).append("]");
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

        System.out.println(renderer.renderSubagentComplete(agent.getName(), durationMs));

        return result.isEmpty() ? "(subagent returned empty response)" : result;
    }

    private String handleToolCall(String data, AgentConfig agent, ToolContext parentContext) {
        try {
            JsonNode toolCall = objectMapper.readTree(data);
            String toolName = toolCall.path("name").asText("");
            JsonNode arguments = toolCall.path("arguments");

            CliTool tool = toolRegistry.get(toolName);
            if (tool == null) {
                System.out.println(renderer.renderSubagentToolCall(toolName, true));
                return "Tool not found: " + toolName;
            }

            List<CliTool> allowed = toolRegistry.getToolsForAgent(agent);
            boolean hasAccess = allowed.stream().anyMatch(t -> t.id().equals(toolName));
            if (!hasAccess) {
                System.out.println(renderer.renderSubagentToolCall(toolName, true));
                return "Tool not available to subagent: " + toolName;
            }

            ToolContext subContext = new ToolContext(
                    parentContext.getSessionId() + "-sub",
                    agent,
                    permissionService,
                    parentContext.getWorkingDirectory(),
                    toolRegistry
            );

            ToolResult result = tool.execute(arguments, subContext);
            System.out.println(renderer.renderSubagentToolCall(toolName, result.isError()));
            return result.getOutput();

        } catch (ToolExecutionException e) {
            return "Tool execution failed: " + e.getMessage();
        } catch (Exception e) {
            return "Error parsing tool call: " + e.getMessage();
        }
    }

    private static String truncate(String text, int maxLen) {
        if (text == null) return "";
        if (text.length() <= maxLen) return text;
        return text.substring(0, maxLen - 3) + "...";
    }
}
