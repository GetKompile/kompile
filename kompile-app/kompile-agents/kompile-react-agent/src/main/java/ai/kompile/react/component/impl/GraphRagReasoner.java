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

import ai.kompile.core.graphrag.GraphRagService;
import ai.kompile.core.graphrag.query.GraphRagQuery;
import ai.kompile.core.graphrag.query.GraphRagResult;
import ai.kompile.core.graphrag.query.SearchType;
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

/**
 * A Reasoner implementation that integrates with GraphRag for knowledge-enhanced reasoning.
 * This reasoner augments the LLM context with knowledge graph information.
 *
 * <p>The GraphRag integration provides:
 * <ul>
 *   <li>Entity and relationship context from the knowledge graph</li>
 *   <li>Community summaries for complex topics</li>
 *   <li>Local or global search strategies</li>
 * </ul>
 */
@Slf4j
public class GraphRagReasoner implements Reasoner {

    private static final String GRAPH_RAG_SYSTEM_PROMPT = """
            You are a helpful assistant with access to a knowledge graph.
            Use the provided graph context to inform your reasoning and decisions.

            When the graph context is relevant to the user's question:
            1. Incorporate the entity and relationship information
            2. Reference specific facts from the knowledge graph
            3. Acknowledge when the graph doesn't contain relevant information

            Think step by step and use the available tools when needed.
            When you have enough information to answer, call the final_answer tool.
            """;

    private final String id;
    private final String name;
    private final ChatClient chatClient;
    private final GraphRagService graphRagService;
    private final String systemPrompt;
    private final SearchType defaultSearchType;
    private final int maxGraphResults;
    private final ObjectMapper objectMapper;

    @Builder
    public GraphRagReasoner(
            String id,
            String name,
            ChatClient chatClient,
            GraphRagService graphRagService,
            String systemPrompt,
            SearchType defaultSearchType,
            Integer maxGraphResults
    ) {
        this.id = id != null ? id : "graphrag-reasoner";
        this.name = name != null ? name : "GraphRag Reasoner";
        this.chatClient = chatClient;
        this.graphRagService = graphRagService;
        this.systemPrompt = systemPrompt != null ? systemPrompt : GRAPH_RAG_SYSTEM_PROMPT;
        this.defaultSearchType = defaultSearchType != null ? defaultSearchType : SearchType.LOCAL;
        this.maxGraphResults = maxGraphResults != null ? maxGraphResults : 10;
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
        log.debug("GraphRag Reasoner processing step {}", context.getCurrentStep());

        try {
            // Extract the query from context
            String query = extractQuery(context);

            // Query the knowledge graph for relevant context
            String graphContext = queryGraphRag(query);

            // Build messages with graph context
            List<Message> messages = buildMessages(context, graphContext);

            // Create prompt and call LLM
            Prompt prompt = new Prompt(messages);
            ChatResponse response = chatClient.prompt(prompt).call().chatResponse();

            return parseResponse(response);

        } catch (Exception e) {
            log.error("GraphRag Reasoner failed: {}", e.getMessage(), e);
            throw new RuntimeException("GraphRag reasoning failed: " + e.getMessage(), e);
        }
    }

    private String extractQuery(AgentContext context) {
        // Get the user's query from context
        List<ReActMessage> messages = context.getMessages();

        // Find the last user message
        for (int i = messages.size() - 1; i >= 0; i--) {
            ReActMessage msg = messages.get(i);
            if (msg.getRole() == ReActMessage.Role.USER && msg.getContent() != null) {
                return msg.getContent();
            }
        }

        // If no user message, use the last assistant thought as a query
        for (int i = messages.size() - 1; i >= 0; i--) {
            ReActMessage msg = messages.get(i);
            if (msg.getRole() == ReActMessage.Role.ASSISTANT && msg.getThought() != null) {
                return msg.getThought();
            }
        }

        return "What information is available?";
    }

    private String queryGraphRag(String query) {
        if (graphRagService == null) {
            log.debug("No GraphRag service available");
            return "";
        }

        try {
            GraphRagQuery graphQuery = GraphRagQuery.builder()
                    .query(query)
                    .searchType(defaultSearchType)
                    .k(maxGraphResults)
                    .build();

            GraphRagResult result = graphRagService.answerQuery(graphQuery);

            if (result != null && result.getFormattedContext() != null) {
                return formatGraphContext(result);
            }

            return "";

        } catch (Exception e) {
            log.warn("GraphRag query failed: {}", e.getMessage());
            return "";
        }
    }

    private String formatGraphContext(GraphRagResult result) {
        StringBuilder context = new StringBuilder();
        context.append("## Knowledge Graph Context\n\n");

        if (result.getAnswer() != null) {
            context.append("**Graph Summary**: ").append(result.getAnswer()).append("\n\n");
        }

        if (result.getFormattedContext() != null) {
            context.append("**Relevant Information**:\n").append(result.getFormattedContext()).append("\n\n");
        }

        return context.toString();
    }

    private List<Message> buildMessages(AgentContext context, String graphContext) {
        List<Message> messages = new ArrayList<>();

        // Build system prompt with graph context
        String effectiveSystemPrompt = context.getSystemPrompt() != null
                ? context.getSystemPrompt()
                : systemPrompt;

        if (!graphContext.isEmpty()) {
            effectiveSystemPrompt = effectiveSystemPrompt + "\n\n" + graphContext;
        }

        // Add tool descriptions
        String toolDescriptions = buildToolDescriptions(context.getToolkit().getTools());
        if (!toolDescriptions.isEmpty()) {
            effectiveSystemPrompt = effectiveSystemPrompt + "\n\n" + toolDescriptions;
        }

        messages.add(new SystemMessage(effectiveSystemPrompt));

        // Add conversation messages
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
            sb.append(tool.getDescription()).append("\n\n");
        }

        sb.append("\nTo use a tool, respond with JSON:\n");
        sb.append("```json\n");
        sb.append("{\n");
        sb.append("  \"thought\": \"Your reasoning\",\n");
        sb.append("  \"tool_calls\": [{\"name\": \"tool_name\", \"arguments\": {...}}]\n");
        sb.append("}\n");
        sb.append("```\n");

        return sb.toString();
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

    private ReActMessage parseResponse(ChatResponse response) {
        if (response == null || response.getResults().isEmpty()) {
            return ReActMessage.assistant("No response from LLM");
        }

        Generation generation = response.getResult();
        AssistantMessage assistantMessage = generation.getOutput();
        String content = assistantMessage.getText();

        // Calculate token usage
        TokenUsage usage = TokenUsage.empty();
        if (response.getMetadata() != null && response.getMetadata().getUsage() != null) {
            var aiUsage = response.getMetadata().getUsage();
            usage = TokenUsage.of(aiUsage.getPromptTokens(), aiUsage.getCompletionTokens());
        }

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

            return ReActMessage.builder()
                    .role(ReActMessage.Role.ASSISTANT)
                    .thought(thought)
                    .content(content)
                    .toolCalls(toolCalls)
                    .usage(usage)
                    .metadata(Map.of("graphrag_enhanced", true))
                    .build();

        } catch (Exception e) {
            log.debug("Response is not JSON: {}", e.getMessage());

            return ReActMessage.builder()
                    .role(ReActMessage.Role.ASSISTANT)
                    .content(content)
                    .usage(usage)
                    .build();
        }
    }
}
