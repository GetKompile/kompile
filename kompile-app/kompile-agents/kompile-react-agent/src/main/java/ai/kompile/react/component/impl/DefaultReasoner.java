/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ai.kompile.react.component.impl;

import ai.kompile.react.api.Reasoner;
import ai.kompile.react.context.AgentContext;
import ai.kompile.react.model.ReActMessage;
import ai.kompile.react.model.TokenUsage;
import ai.kompile.react.model.ToolCall;
import ai.kompile.react.model.ToolDefinition;
import ai.kompile.cli.common.util.JsonUtils;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Builder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * Default implementation of the Reasoner interface.
 * Uses Spring AI ChatClient for LLM integration.
 *
 * <p>This reasoner invokes the LLM with the current memory and system prompt
 * to generate the next step, including any tool calls.
 */
@Slf4j
public class DefaultReasoner implements Reasoner {

    private static final String DEFAULT_SYSTEM_PROMPT = """
            You are a helpful assistant that can use tools to accomplish tasks.

            Think step by step and use the available tools when needed.
            When you have enough information to answer the user's question,
            call the final_answer tool with your response.

            Always explain your reasoning before taking actions.
            """;

    private final String id;
    private final String name;
    private final ChatClient chatClient;
    private final String systemPrompt;
    private final ObjectMapper objectMapper;

    @Builder
    public DefaultReasoner(
            String id,
            String name,
            ChatClient chatClient,
            String systemPrompt
    ) {
        this.id = id != null ? id : "default-reasoner";
        this.name = name != null ? name : "Default Reasoner";
        this.chatClient = chatClient;
        this.systemPrompt = systemPrompt != null ? systemPrompt : DEFAULT_SYSTEM_PROMPT;
        this.objectMapper = JsonUtils.standardMapper();
    }

    @Override
    public String getId() {
        return id;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public CompletableFuture<ReActMessage> reason(AgentContext context) {
        return CompletableFuture.supplyAsync(() -> reasonSync(context));
    }

    @Override
    public ReActMessage reasonSync(AgentContext context) {
        log.debug("Reasoner {} processing step {}", id, context.getCurrentStep());

        try {
            // Build messages for the LLM
            List<Message> messages = buildMessages(context);

            // Get tool definitions
            List<ToolDefinition> tools = context.getToolkit().getTools();

            // Create prompt with tools
            Prompt prompt = new Prompt(messages);

            // Call the LLM
            ChatResponse response = chatClient
                    .prompt(prompt)
                    .call()
                    .chatResponse();

            // Parse the response
            return parseResponse(response, context);

        } catch (Exception e) {
            log.error("Reasoner {} failed: {}", id, e.getMessage(), e);
            throw new RuntimeException("Reasoning failed: " + e.getMessage(), e);
        }
    }

    private List<Message> buildMessages(AgentContext context) {
        List<Message> messages = new ArrayList<>();

        // Add system message
        String effectiveSystemPrompt = context.getSystemPrompt() != null
                ? context.getSystemPrompt()
                : systemPrompt;

        // Append tool descriptions to system prompt
        String toolDescriptions = buildToolDescriptions(context.getToolkit().getTools());
        String fullSystemPrompt = effectiveSystemPrompt + "\n\n" + toolDescriptions;

        messages.add(new SystemMessage(fullSystemPrompt));

        // Convert agent messages to Spring AI messages
        for (ReActMessage msg : context.getMessages()) {
            messages.add(toSpringAiMessage(msg));
        }

        return messages;
    }

    private String buildToolDescriptions(List<ToolDefinition> tools) {
        if (tools == null || tools.isEmpty()) {
            return "";
        }

        StringBuilder sb = new StringBuilder("## Available Tools\n\n");
        for (ToolDefinition tool : tools) {
            sb.append("### ").append(tool.getName()).append("\n");
            sb.append(tool.getDescription()).append("\n");
            if (tool.getParameters() != null) {
                sb.append("Parameters: ").append(formatParameters(tool.getParameters())).append("\n");
            }
            sb.append("\n");
        }

        sb.append("\nTo use a tool, respond with a JSON object in this format:\n");
        sb.append("```json\n");
        sb.append("{\n");
        sb.append("  \"thought\": \"Your reasoning about what to do\",\n");
        sb.append("  \"tool_calls\": [\n");
        sb.append("    {\"name\": \"tool_name\", \"arguments\": {\"arg1\": \"value1\"}}\n");
        sb.append("  ]\n");
        sb.append("}\n");
        sb.append("```\n");
        sb.append("\nOr if you have the final answer:\n");
        sb.append("```json\n");
        sb.append("{\n");
        sb.append("  \"thought\": \"Your reasoning\",\n");
        sb.append("  \"tool_calls\": [{\"name\": \"final_answer\", \"arguments\": {\"answer\": \"Your answer\"}}]\n");
        sb.append("}\n");
        sb.append("```\n");

        return sb.toString();
    }

    private String formatParameters(Map<String, Object> parameters) {
        try {
            return objectMapper.writeValueAsString(parameters);
        } catch (Exception e) {
            return parameters.toString();
        }
    }

    private Message toSpringAiMessage(ReActMessage msg) {
        return switch (msg.getRole()) {
            case SYSTEM -> new SystemMessage(msg.getContent());
            case USER -> new UserMessage(msg.getContent());
            case ASSISTANT -> new AssistantMessage(
                    msg.getContent() != null ? msg.getContent() :
                            (msg.getThought() != null ? msg.getThought() : "")
            );
            case TOOL -> new UserMessage(
                    "Tool " + msg.getToolName() + " result: " + msg.getContent()
            );
        };
    }

    private ReActMessage parseResponse(ChatResponse response, AgentContext context) {
        if (response == null || response.getResults().isEmpty()) {
            return ReActMessage.assistant("No response from LLM");
        }

        Generation generation = response.getResult();
        AssistantMessage assistantMessage = generation.getOutput();
        String content = assistantMessage.getText();

        // Try to parse as JSON with tool calls
        try {
            JsonNode json = objectMapper.readTree(content);

            String thought = json.has("thought") ? json.get("thought").asText() : null;
            List<ToolCall> toolCalls = new ArrayList<>();

            if (json.has("tool_calls") && json.get("tool_calls").isArray()) {
                for (JsonNode toolCallNode : json.get("tool_calls")) {
                    String toolName = toolCallNode.get("name").asText();
                    Map<String, Object> args = new HashMap<>();

                    if (toolCallNode.has("arguments")) {
                        args = objectMapper.convertValue(
                                toolCallNode.get("arguments"),
                                new TypeReference<Map<String, Object>>() {}
                        );
                    }

                    toolCalls.add(ToolCall.of(toolName, args));
                }
            }

            // Calculate token usage
            TokenUsage usage = TokenUsage.empty();
            if (response.getMetadata() != null && response.getMetadata().getUsage() != null) {
                var aiUsage = response.getMetadata().getUsage();
                usage = TokenUsage.of(
                        aiUsage.getPromptTokens(),
                        aiUsage.getCompletionTokens()
                );
            }

            return ReActMessage.builder()
                    .role(ReActMessage.Role.ASSISTANT)
                    .thought(thought)
                    .content(content)
                    .toolCalls(toolCalls)
                    .usage(usage)
                    .build();

        } catch (Exception e) {
            log.debug("Response is not JSON, treating as plain text: {}", e.getMessage());

            // Not JSON, return as plain assistant message
            TokenUsage usage = TokenUsage.empty();
            if (response.getMetadata() != null && response.getMetadata().getUsage() != null) {
                var aiUsage = response.getMetadata().getUsage();
                usage = TokenUsage.of(
                        aiUsage.getPromptTokens(),
                        aiUsage.getCompletionTokens()
                );
            }

            return ReActMessage.builder()
                    .role(ReActMessage.Role.ASSISTANT)
                    .content(content)
                    .usage(usage)
                    .build();
        }
    }
}
