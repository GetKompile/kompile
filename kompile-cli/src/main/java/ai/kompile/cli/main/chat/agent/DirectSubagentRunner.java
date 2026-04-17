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

import ai.kompile.cli.main.chat.config.ChatConfig;
import ai.kompile.cli.main.chat.config.DirectLlmClient;
import ai.kompile.cli.main.chat.permission.PermissionService;
import ai.kompile.cli.main.chat.render.TerminalRenderer;
import ai.kompile.cli.main.chat.tools.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;

import java.util.ArrayList;
import java.util.List;

/**
 * Runs subagents directly using the configured LLM API (no kompile-app server needed).
 * Creates a fresh DirectLlmClient for each subagent invocation with its own conversation
 * history, ensuring context isolation between the parent and subagent.
 *
 * Each subagent runs a complete agentic loop: send prompt → receive response →
 * execute tool calls → send results → repeat until text-only response.
 */
public class DirectSubagentRunner implements SubagentRunner {

    private final ChatConfig chatConfig;
    private final ObjectMapper objectMapper;
    private final ToolRegistry toolRegistry;
    private final PermissionService permissionService;
    private final TerminalRenderer renderer;

    public DirectSubagentRunner(ChatConfig chatConfig, ObjectMapper objectMapper,
                                 ToolRegistry toolRegistry, PermissionService permissionService,
                                 TerminalRenderer renderer) {
        this.chatConfig = chatConfig;
        this.objectMapper = objectMapper;
        this.toolRegistry = toolRegistry;
        this.permissionService = permissionService;
        this.renderer = renderer;
    }

    @Override
    public String runSubagent(AgentConfig agent, String prompt, ToolContext parentContext) throws Exception {
        long startTime = System.currentTimeMillis();
        System.out.println(renderer.renderSubagentStart(agent.getName(), truncate(prompt, 80)));

        // Create isolated LLM client for this subagent
        DirectLlmClient subClient = new DirectLlmClient(chatConfig, objectMapper);

        // Build tool definitions filtered to the agent's allowed tools
        ArrayNode toolDefs = toolRegistry.buildToolDefinitions(agent);

        // Build system prompt
        String systemPrompt = agent.getSystemPrompt();
        if (systemPrompt == null) systemPrompt = "";

        // Resolve model override for this subagent based on its modelHint
        String modelOverride = agent.getModelOverride();

        // Run the agentic loop
        StringBuilder fullResponse = new StringBuilder();
        int step = 0;
        int maxSteps = agent.getMaxSteps();

        String currentMessage = prompt;
        List<DirectLlmClient.ToolCallResultInput> pendingToolResults = null;

        while (step < maxSteps) {
            step++;

            DirectLlmClient.StreamResult result = subClient.streamChat(
                    currentMessage, systemPrompt, toolDefs, pendingToolResults, modelOverride);

            // Collect text
            if (result.text != null && !result.text.isEmpty()) {
                fullResponse.append(result.text);
            }

            // No tool calls = done
            if (result.toolCalls.isEmpty()) {
                break;
            }

            // Execute tool calls
            List<DirectLlmClient.ToolCallResultInput> toolResults = new ArrayList<>();

            for (DirectLlmClient.ToolCallOutput tc : result.toolCalls) {
                CliTool tool = toolRegistry.get(tc.name);
                if (tool == null) {
                    System.out.println(renderer.renderSubagentToolCall(tc.name, true));
                    toolResults.add(new DirectLlmClient.ToolCallResultInput(
                            tc.id, tc.name, "Unknown tool: " + tc.name, true));
                    continue;
                }

                // Check tool access for this agent
                List<CliTool> allowed = toolRegistry.getToolsForAgent(agent);
                boolean hasAccess = allowed.stream().anyMatch(t -> t.id().equals(tc.name));
                if (!hasAccess) {
                    System.out.println(renderer.renderSubagentToolCall(tc.name, true));
                    toolResults.add(new DirectLlmClient.ToolCallResultInput(
                            tc.id, tc.name, "Tool not available to " + agent.getName() + ": " + tc.name, true));
                    continue;
                }

                // Create isolated tool context for subagent
                ToolContext subContext = new ToolContext(
                        parentContext.getSessionId() + "-sub-" + agent.getName(),
                        agent,
                        permissionService,
                        parentContext.getWorkingDirectory(),
                        toolRegistry
                );

                try {
                    ToolResult toolResult = tool.execute(tc.arguments, subContext);
                    System.out.println(renderer.renderSubagentToolCall(tc.name, toolResult.isError()));

                    // Truncate large outputs to avoid consuming the subagent's context
                    String output = toolResult.getOutput();
                    if (output != null && output.length() > 50_000) {
                        output = output.substring(0, 50_000) + "\n... (truncated, " + output.length() + " chars total)";
                    }

                    toolResults.add(new DirectLlmClient.ToolCallResultInput(
                            tc.id, tc.name, output, toolResult.isError()));
                } catch (ToolExecutionException e) {
                    System.out.println(renderer.renderSubagentToolCall(tc.name, true));
                    toolResults.add(new DirectLlmClient.ToolCallResultInput(
                            tc.id, tc.name, "Error: " + e.getMessage(), true));
                }
            }

            // Continue with tool results
            pendingToolResults = toolResults;
            currentMessage = null;
        }

        long durationMs = System.currentTimeMillis() - startTime;
        String finalResult = fullResponse.toString().trim();

        if (step >= maxSteps) {
            System.out.println(renderer.renderSubagentError(agent.getName(),
                    "Reached max steps (" + maxSteps + ")"));
            finalResult += "\n[Subagent reached maximum steps (" + maxSteps + ")]";
        } else {
            System.out.println(renderer.renderSubagentComplete(agent.getName(), durationMs));
        }

        return finalResult.isEmpty() ? "(subagent returned empty response)" : finalResult;
    }

    private static String truncate(String text, int maxLen) {
        if (text == null) return "";
        if (text.length() <= maxLen) return text;
        return text.substring(0, maxLen - 3) + "...";
    }
}
