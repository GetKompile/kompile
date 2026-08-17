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

package ai.kompile.cli.main.chat.config;

import ai.kompile.cli.main.auth.oauth.OAuthProviderFlow;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Direct LLM client that calls provider APIs without requiring a kompile-app server.
 * Supports OpenAI Chat Completions and Responses, Anthropic Messages, and Pi Messages.
 * <p>
 * Handles streaming, tool calling, and multi-turn conversations.
 */
public class DirectLlmClient {

    private final ChatConfig config;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final List<ObjectNode> conversationHistory;
    private volatile AtomicBoolean cancelSignal;
    private volatile java.util.function.Consumer<String> outputConsumer;
    private volatile RadiusGatewayConfig radiusGatewayConfig;
    private volatile String radiusGatewayConfigSource;

    public DirectLlmClient(ChatConfig config, ObjectMapper objectMapper) {
        this.config = config;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .build();
        this.conversationHistory = new ArrayList<>();
    }

    /**
     * Sets the cancel signal that can be used to interrupt streaming.
     */
    public void setCancelSignal(AtomicBoolean cancelSignal) {
        this.cancelSignal = cancelSignal;
    }

    protected boolean isCancelled() {
        AtomicBoolean signal = this.cancelSignal;
        return signal != null && signal.get();
    }

    /**
     * Sets a consumer that receives all streamed output text.
     */
    public void setOutputConsumer(java.util.function.Consumer<String> consumer) {
        this.outputConsumer = consumer;
    }

    /**
     * Returns the currently installed streamed-output consumer, if any.
     */
    public java.util.function.Consumer<String> getOutputConsumer() {
        return outputConsumer;
    }

    /**
     * Print a streaming text chunk to the terminal.
     * Subclasses may override to intercept/redirect streaming output.
     */
    protected void printStreamingChunk(String chunk) {
        if (chunk != null) {
            java.util.function.Consumer<String> consumer = this.outputConsumer;
            if (consumer != null) {
                consumer.accept(chunk);
            } else {
                System.out.print(chunk);
                System.out.flush();
            }
        }
    }

    /**
     * Stream a chat completion turn.
     * Returns a StreamResult with the text response and any tool call requests.
     */
    public StreamResult streamChat(String userMessage, String systemPrompt,
                                    ArrayNode toolDefs, List<ToolCallResultInput> toolResults) {
        return streamChat(userMessage, systemPrompt, toolDefs, toolResults, null);
    }

    /**
     * Stream a chat completion turn with optional model override and attachments.
     * Attachments are ignored in the base implementation; subclasses may override.
     */
    public StreamResult streamChat(String userMessage, String systemPrompt,
                                    ArrayNode toolDefs, List<ToolCallResultInput> toolResults,
                                    String modelOverride, List<AttachmentInput> attachments) {
        // Base implementation ignores attachments
        return streamChat(userMessage, systemPrompt, toolDefs, toolResults, modelOverride);
    }

    /**
     * Stream a chat completion turn with optional model override.
     *
     * @param modelOverride if non-null, use this model instead of the configured default
     */
    public StreamResult streamChat(String userMessage, String systemPrompt,
                                    ArrayNode toolDefs, List<ToolCallResultInput> toolResults,
                                    String modelOverride) {
        String effectiveModel = (modelOverride != null && !modelOverride.isBlank())
                ? modelOverride : config.getModel();
        if (config.isKompileLocalServing()) {
            return streamKompileServing(userMessage, systemPrompt, toolDefs, toolResults);
        } else if (config.isOpenAiCodexFormat()) {
            return streamOpenAiResponses(
                    userMessage, systemPrompt, toolDefs, toolResults, effectiveModel, true);
        } else if (config.isPiMessagesFormat()) {
            return streamPiMessages(userMessage, systemPrompt, toolDefs, toolResults, effectiveModel);
        } else if (config.isAnthropicFormat()) {
            return streamAnthropic(userMessage, systemPrompt, toolDefs, toolResults, effectiveModel);
        } else if (usesGitHubAnthropicMessages(effectiveModel)) {
            return streamAnthropic(userMessage, systemPrompt, toolDefs, toolResults, effectiveModel);
        } else if (usesGitHubOpenAiResponses(effectiveModel)) {
            return streamOpenAiResponses(
                    userMessage, systemPrompt, toolDefs, toolResults, effectiveModel, false);
        } else {
            return streamOpenAi(userMessage, systemPrompt, toolDefs, toolResults, effectiveModel);
        }
    }

    /**
     * Clear conversation history (for new sessions).
     */
    public void clearHistory() {
        conversationHistory.clear();
    }

    /**
     * Add a message to history (for replay/resume support).
     */
    public void addToHistory(String role, String content) {
        ObjectNode msg = objectMapper.createObjectNode();
        msg.put("role", role);
        msg.put("content", content);
        conversationHistory.add(msg);
    }

    /**
     * One-shot streaming completion that does NOT mutate conversation history.
     * Used for utility calls like summarization where we want the model's
     * output but must not pollute the ongoing chat with the request/response.
     * <p>
     * Internally: snapshot history, clear, call streamChat (which would add
     * the request turn), then restore the snapshot — yielding a clean call.
     */
    public StreamResult streamOneShot(String prompt, String systemPrompt, String modelOverride) {
        List<ObjectNode> saved = new ArrayList<>(conversationHistory);
        conversationHistory.clear();
        try {
            return streamChat(prompt, systemPrompt, null, null, modelOverride);
        } finally {
            conversationHistory.clear();
            conversationHistory.addAll(saved);
        }
    }

    /**
     * Replace the entire conversation history with a summary of prior turns.
     * The summary is injected as a user/assistant exchange so the next real
     * user turn continues normally. Used by the /compact command.
     */
    public void replaceHistoryWithSummary(String summary) {
        conversationHistory.clear();
        if (summary == null || summary.isBlank()) return;

        ObjectNode userMsg = objectMapper.createObjectNode();
        userMsg.put("role", "user");
        userMsg.put("content",
                "This session was compacted. Below is a structured summary of our "
                        + "prior conversation. Treat it as authoritative context for "
                        + "continuing the work:\n\n" + summary);
        conversationHistory.add(userMsg);

        ObjectNode assistantMsg = objectMapper.createObjectNode();
        assistantMsg.put("role", "assistant");
        assistantMsg.put("content",
                "Understood. I have the compacted summary and will continue from here.");
        conversationHistory.add(assistantMsg);
    }

    public int getHistorySize() {
        return conversationHistory.size();
    }

    /** Live chat configuration shared with the standard-chat REPL. */
    public ChatConfig getChatConfig() {
        return config;
    }

    /** The provider configured for this client. */
    public String getConfiguredProvider() {
        return config.getProvider();
    }

    /** The model configured for this client (before any per-agent override). */
    public String getConfiguredModel() {
        return config.getModel();
    }

    /** The resolved provider base URL this client sends requests to. */
    public String getResolvedBaseUrl() {
        return config.resolveBaseUrl();
    }

    /**
     * In-place mid-conversation pruning of the wire history: collapse the content of
     * old {@code tool} messages to short summaries and clip old assistant text, while
     * leaving message roles, ids, and {@code tool_calls} wiring untouched — so the
     * assistant/tool pairing the provider validates stays intact. Safe to call between
     * agentic steps, unlike a full summary rewrite.
     *
     * @param preserveRecentMessages number of newest messages left untouched
     * @param toolResultSummarizer   maps (toolName, content) → summary text
     * @return number of messages shrunk
     */
    public int compactToolHistory(int preserveRecentMessages,
                                  java.util.function.BinaryOperator<String> toolResultSummarizer) {
        int lastPrunable = conversationHistory.size() - Math.max(0, preserveRecentMessages);
        int shrunk = 0;
        for (int i = 0; i < lastPrunable; i++) {
            ObjectNode msg = conversationHistory.get(i);
            String role = msg.path("role").asText("");
            String content = msg.path("content").asText("");
            if ("tool".equals(role) && content.length() > 600) {
                String toolName = msg.path("name").asText(null);
                msg.put("content", toolResultSummarizer.apply(toolName, content));
                shrunk++;
            } else if ("assistant".equals(role) && content.length() > 2_000) {
                msg.put("content", content.substring(0, 2_000) + "\n... (truncated during compaction)");
                shrunk++;
            }
        }
        return shrunk;
    }

    private boolean usesGitHubAnthropicMessages(String model) {
        return "github-copilot".equals(config.getProvider())
                && model != null
                && model.startsWith("claude-")
                && !model.startsWith("claude-fable-");
    }

    private boolean usesGitHubOpenAiResponses(String model) {
        if (!"github-copilot".equals(config.getProvider()) || model == null) {
            return false;
        }
        return model.startsWith("gpt-5")
                || model.startsWith("grok-")
                || model.startsWith("mai-code-");
    }

    private void applyReasoningEffort(ObjectNode request, boolean responsesFormat) {
        String effort = config.getThinking();
        if (effort == null || effort.isBlank()) {
            return;
        }
        if (responsesFormat) {
            ObjectNode reasoning = objectMapper.createObjectNode();
            reasoning.put("effort", effort);
            request.set("reasoning", reasoning);
        } else {
            request.put("reasoning_effort", effort);
        }
    }

    // ========================================================================
    // OpenAI Responses and ChatGPT Codex Responses
    // ========================================================================

    private StreamResult streamOpenAiResponses(
            String userMessage,
            String systemPrompt,
            ArrayNode toolDefs,
            List<ToolCallResultInput> toolResults,
            String effectiveModel,
            boolean codex) {
        StreamResult result = new StreamResult();
        ResponsesStreamState state = new ResponsesStreamState();

        try {
            ResponsesHistoryLinks historyLinks = sanitizeResponsesHistory(toolResults);
            List<ObjectNode> stagedToolResultItems =
                    prepareResponsesToolResultItems(toolResults, historyLinks);
            ArrayNode input = buildResponsesInput(
                    userMessage, systemPrompt, stagedToolResultItems, codex);
            ObjectNode request = objectMapper.createObjectNode();
            request.put("model", effectiveModel);
            request.set("input", input);
            request.put("stream", true);
            request.put("store", false);
            applyReasoningEffort(request, true);

            if (codex) {
                request.put("instructions",
                        systemPrompt == null || systemPrompt.isBlank()
                                ? "You are a helpful assistant."
                                : systemPrompt);
                ObjectNode text = objectMapper.createObjectNode();
                text.put("verbosity", "low");
                request.set("text", text);
                ArrayNode include = objectMapper.createArrayNode();
                include.add("reasoning.encrypted_content");
                request.set("include", include);
                request.put("tool_choice", "auto");
                request.put("parallel_tool_calls", true);
            }

            if (toolDefs != null && !toolDefs.isEmpty()) {
                request.set("tools", convertToolDefsToResponses(toolDefs, codex));
            }

            OAuthProviderFlow.RequestAuth auth = config.resolveRequestAuth();
            String baseUrl = config.resolveBaseUrl(auth);
            String url = resolveResponsesUrl(baseUrl, codex);
            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Accept", "text/event-stream")
                    .header("Content-Type", "application/json")
                    .header("User-Agent", "kompile")
                    .POST(HttpRequest.BodyPublishers.ofString(
                            objectMapper.writeValueAsString(request),
                            StandardCharsets.UTF_8))
                    .timeout(Duration.ofMinutes(10));
            if (auth == null || !hasHeader(auth.headers(), "Authorization")) {
                requestBuilder.header("Authorization", "Bearer " + (auth == null ? "" : auth.token()));
            }
            applyHeaders(requestBuilder, auth);
            applyProviderRequestHeaders(requestBuilder, userMessage);

            HttpResponse<java.io.InputStream> response = httpClient.send(
                    requestBuilder.build(), HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                String body = new String(response.body().readAllBytes(), StandardCharsets.UTF_8);
                String label = codex ? "OpenAI Codex" : "OpenAI Responses";
                result.text = "[" + label + " API error " + response.statusCode()
                        + ": " + extractErrorMessage(body) + "]";
                printStreamingChunk(result.text);
                return result;
            }

            parseResponsesStream(response.body(), result, state);
            if (!state.failed && !result.cancelled) {
                // Commit submitted tool results only after the provider accepts the
                // request. A rejected request must not poison every later turn.
                conversationHistory.addAll(stagedToolResultItems);
                appendResponsesHistory(userMessage, result, state);
            }
        } catch (Exception e) {
            if (!markCancelled(result, e)) {
                result.text = "[Error: " + formatExceptionMessage(e) + "]";
            }
        }
        return result;
    }

    private ArrayNode buildResponsesInput(
            String userMessage,
            String systemPrompt,
            List<ObjectNode> stagedToolResultItems,
            boolean codex) {
        ArrayNode input = objectMapper.createArrayNode();
        if (!codex && systemPrompt != null && !systemPrompt.isBlank()) {
            ObjectNode system = objectMapper.createObjectNode();
            system.put("role", "developer");
            system.put("content", systemPrompt);
            input.add(system);
        }
        conversationHistory.forEach(input::add);
        if (stagedToolResultItems != null) {
            stagedToolResultItems.forEach(input::add);
        }
        if (userMessage != null) {
            input.add(createResponsesUserMessage(userMessage));
        }
        return input;
    }

    /**
     * Remove malformed/duplicate Responses linkage left by an interrupted older
     * client. Function outputs are valid only when the matching function call is
     * present earlier in the same retained request history. A function call with
     * neither a retained output nor a currently pending executor result is also
     * incomplete and must not be sent on the next request.
     */
    private ResponsesHistoryLinks sanitizeResponsesHistory(
            List<ToolCallResultInput> pendingToolResults) {
        Set<String> pendingCalls = new LinkedHashSet<>();
        if (pendingToolResults != null) {
            for (ToolCallResultInput toolResult : pendingToolResults) {
                if (toolResult != null && toolResult.callId != null
                        && !toolResult.callId.isBlank()) {
                    pendingCalls.add(toolResult.callId.trim());
                }
            }
        }

        Set<String> calls = new LinkedHashSet<>();
        Set<String> outputs = new LinkedHashSet<>();
        List<ObjectNode> sanitized = new ArrayList<>(conversationHistory.size());
        for (ObjectNode item : conversationHistory) {
            String type = item.path("type").asText("");
            if ("function_call".equals(type)) {
                String callId = item.path("call_id").asText("");
                if (callId.isBlank() || !calls.add(callId)) {
                    continue;
                }
            } else if ("function_call_output".equals(type)) {
                String callId = item.path("call_id").asText("");
                if (callId.isBlank() || !calls.contains(callId) || !outputs.add(callId)) {
                    continue;
                }
            }
            sanitized.add(item);
        }

        List<ObjectNode> complete = new ArrayList<>(sanitized.size());
        Set<String> retainedCalls = new LinkedHashSet<>();
        for (ObjectNode item : sanitized) {
            if ("function_call".equals(item.path("type").asText(""))) {
                String callId = item.path("call_id").asText("");
                if (!outputs.contains(callId) && !pendingCalls.contains(callId)) {
                    continue;
                }
                retainedCalls.add(callId);
            }
            complete.add(item);
        }

        if (complete.size() != conversationHistory.size()) {
            conversationHistory.clear();
            conversationHistory.addAll(complete);
        }
        return new ResponsesHistoryLinks(retainedCalls, outputs);
    }

    /**
     * Stage current tool results without mutating retained history. When a full
     * compaction or resume lost the provider-owned function-call item, preserve
     * the useful result as ordinary user context instead of emitting an orphaned
     * function_call_output that OpenAI rejects with HTTP 400.
     */
    private List<ObjectNode> prepareResponsesToolResultItems(
            List<ToolCallResultInput> toolResults,
            ResponsesHistoryLinks historyLinks) {
        if (toolResults == null || toolResults.isEmpty()) {
            return List.of();
        }
        Set<String> submittedOutputs = new LinkedHashSet<>(historyLinks.outputs());
        List<ObjectNode> staged = new ArrayList<>();
        for (ToolCallResultInput toolResult : toolResults) {
            String callId = toolResult.callId == null ? "" : toolResult.callId.trim();
            if (!callId.isBlank() && historyLinks.calls().contains(callId)) {
                if (!submittedOutputs.add(callId)) {
                    continue;
                }
                ObjectNode output = objectMapper.createObjectNode();
                output.put("type", "function_call_output");
                output.put("call_id", callId);
                output.put("output", toolResult.output == null ? "" : toolResult.output);
                staged.add(output);
                continue;
            }

            String toolName = toolResult.name == null || toolResult.name.isBlank()
                    ? "unknown tool" : toolResult.name;
            StringBuilder recovered = new StringBuilder()
                    .append("[Recovered tool result for ").append(toolName)
                    .append("; the provider function-call link was unavailable after compaction/resume]");
            if (toolResult.isError) {
                recovered.append(" [tool reported an error]");
            }
            if (toolResult.output != null && !toolResult.output.isBlank()) {
                recovered.append('\n').append(toolResult.output);
            }
            staged.add(createResponsesUserMessage(recovered.toString()));
        }
        return staged;
    }

    private record ResponsesHistoryLinks(Set<String> calls, Set<String> outputs) {}

    private ObjectNode createResponsesUserMessage(String text) {
        ObjectNode message = objectMapper.createObjectNode();
        message.put("role", "user");
        ArrayNode content = objectMapper.createArrayNode();
        ObjectNode block = objectMapper.createObjectNode();
        block.put("type", "input_text");
        block.put("text", text);
        content.add(block);
        message.set("content", content);
        return message;
    }

    private ArrayNode convertToolDefsToResponses(ArrayNode toolDefs, boolean codex) {
        ArrayNode tools = objectMapper.createArrayNode();
        for (JsonNode tool : toolDefs) {
            ObjectNode responseTool = objectMapper.createObjectNode();
            responseTool.put("type", "function");
            responseTool.put("name", tool.path("name").asText());
            responseTool.put("description", tool.path("description").asText());
            JsonNode parameters = tool.path("inputSchema");
            if (parameters == null || parameters.isMissingNode()) {
                ObjectNode empty = objectMapper.createObjectNode();
                empty.put("type", "object");
                empty.set("properties", objectMapper.createObjectNode());
                parameters = empty;
            }
            responseTool.set("parameters", parameters);
            if (codex) {
                responseTool.putNull("strict");
            }
            tools.add(responseTool);
        }
        return tools;
    }

    private void parseResponsesStream(
            java.io.InputStream inputStream,
            StreamResult result,
            ResponsesStreamState state) throws Exception {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (isCancelled()) {
                    result.cancelled = true;
                    return;
                }
                String trimmed = line.trim();
                if (!trimmed.startsWith("data:")) {
                    continue;
                }
                String data = trimmed.substring(5).trim();
                if (data.isEmpty() || "[DONE]".equals(data)) {
                    continue;
                }

                JsonNode event;
                try {
                    event = objectMapper.readTree(data);
                } catch (Exception ignored) {
                    continue;
                }
                String type = event.path("type").asText("");
                int outputIndex = event.path("output_index").asInt(0);
                switch (type) {
                    case "response.output_item.added" -> {
                        JsonNode item = event.path("item");
                        updateResponsesReasoningItem(state, outputIndex, item);
                        updateResponsesToolAccumulator(state, outputIndex, item, false);
                    }
                    case "response.output_text.delta", "response.refusal.delta" -> {
                        String delta = event.path("delta").asText("");
                        if (!delta.isEmpty()) {
                            printStreamingChunk(delta);
                            result.text += delta;
                        }
                    }
                    case "response.function_call_arguments.delta" -> {
                        ResponsesToolCallAccumulator accumulator =
                                state.toolCalls.computeIfAbsent(
                                        outputIndex, ignored -> new ResponsesToolCallAccumulator());
                        accumulator.arguments.append(event.path("delta").asText(""));
                    }
                    case "response.function_call_arguments.done" -> {
                        ResponsesToolCallAccumulator accumulator =
                                state.toolCalls.computeIfAbsent(
                                        outputIndex, ignored -> new ResponsesToolCallAccumulator());
                        String arguments = event.path("arguments").asText(null);
                        if (arguments != null) {
                            accumulator.arguments.setLength(0);
                            accumulator.arguments.append(arguments);
                        }
                    }
                    case "response.output_item.done" -> {
                        JsonNode item = event.path("item");
                        updateResponsesReasoningItem(state, outputIndex, item);
                        updateResponsesToolAccumulator(state, outputIndex, item, true);
                        if ("message".equals(item.path("type").asText()) && result.text.isEmpty()) {
                            String completedText = responsesMessageText(item);
                            if (!completedText.isEmpty()) {
                                printStreamingChunk(completedText);
                                result.text = completedText;
                            }
                        }
                    }
                    case "response.completed", "response.incomplete" -> {
                        state.terminal = true;
                        JsonNode response = event.path("response");
                        backfillResponsesReasoning(state, response.path("output"));
                        readResponsesUsage(response.path("usage"), result);
                    }
                    case "response.failed" -> {
                        state.terminal = true;
                        state.failed = true;
                        String message = event.path("response").path("error").path("message")
                                .asText("Response failed");
                        appendProtocolError(result, message);
                    }
                    case "error" -> {
                        state.failed = true;
                        appendProtocolError(result, event.path("message").asText("Unknown response error"));
                    }
                    default -> {
                        // Other Responses events carry reasoning/status metadata.
                    }
                }
            }
        }

        for (ResponsesToolCallAccumulator accumulator : state.toolCalls.values()) {
            if (accumulator.name == null || accumulator.name.isBlank()) {
                continue;
            }
            if (accumulator.callId == null || accumulator.callId.isBlank()) {
                accumulator.callId = "call_" + result.toolCalls.size();
            }
            if (accumulator.itemId == null || accumulator.itemId.isBlank()) {
                accumulator.itemId = "fc_" + UUID.randomUUID().toString().replace("-", "");
            }
            ToolCallOutput toolCall = new ToolCallOutput();
            toolCall.id = accumulator.callId;
            toolCall.name = accumulator.name;
            toolCall.arguments = parseToolArguments(accumulator.arguments.toString());
            result.toolCalls.add(toolCall);
        }
        if (!state.terminal && !state.failed && !result.cancelled) {
            state.failed = true;
            appendProtocolError(result, "Responses stream ended without a terminal event");
        }
    }

    private void updateResponsesReasoningItem(
            ResponsesStreamState state,
            int outputIndex,
            JsonNode item) {
        if ("reasoning".equals(item.path("type").asText()) && item.isObject()) {
            state.reasoningItems.put(outputIndex, ((ObjectNode) item).deepCopy());
        }
    }

    private void backfillResponsesReasoning(ResponsesStreamState state, JsonNode output) {
        if (!output.isArray()) {
            return;
        }
        for (int index = 0; index < output.size(); index++) {
            JsonNode item = output.get(index);
            if ("reasoning".equals(item.path("type").asText()) && item.isObject()) {
                state.reasoningItems.put(index, ((ObjectNode) item).deepCopy());
            }
        }
    }

    private void updateResponsesToolAccumulator(
            ResponsesStreamState state,
            int outputIndex,
            JsonNode item,
            boolean finalItem) {
        if (!"function_call".equals(item.path("type").asText())) {
            return;
        }
        ResponsesToolCallAccumulator accumulator =
                state.toolCalls.computeIfAbsent(
                        outputIndex, ignored -> new ResponsesToolCallAccumulator());
        String value = item.path("id").asText(null);
        if (value != null) accumulator.itemId = value;
        value = item.path("call_id").asText(null);
        if (value != null) accumulator.callId = value;
        value = item.path("name").asText(null);
        if (value != null) accumulator.name = value;
        value = item.path("arguments").asText(null);
        if (value != null && (finalItem || accumulator.arguments.isEmpty())) {
            accumulator.arguments.setLength(0);
            accumulator.arguments.append(value);
        }
    }

    private String responsesMessageText(JsonNode item) {
        StringBuilder text = new StringBuilder();
        for (JsonNode content : item.path("content")) {
            if ("output_text".equals(content.path("type").asText())) {
                text.append(content.path("text").asText(""));
            } else if ("refusal".equals(content.path("type").asText())) {
                text.append(content.path("refusal").asText(""));
            }
        }
        return text.toString();
    }

    private void readResponsesUsage(JsonNode usage, StreamResult result) {
        if (usage == null || usage.isMissingNode()) {
            return;
        }
        long cacheRead = usage.path("input_tokens_details").path("cached_tokens").asLong(0);
        long cacheWrite = usage.path("input_tokens_details").path("cache_write_tokens").asLong(0);
        long totalInput = usage.path("input_tokens").asLong(0);
        result.inputTokens = Math.max(0, totalInput - cacheRead - cacheWrite);
        result.outputTokens = usage.path("output_tokens").asLong(0);
        result.cacheReadTokens = cacheRead;
        result.cacheCreationTokens = cacheWrite;
    }

    private void appendResponsesHistory(
            String userMessage,
            StreamResult result,
            ResponsesStreamState state) {
        if (userMessage != null) {
            conversationHistory.add(createResponsesUserMessage(userMessage));
        }
        state.reasoningItems.values().forEach(conversationHistory::add);
        if (!result.text.isEmpty()) {
            ObjectNode message = objectMapper.createObjectNode();
            message.put("type", "message");
            message.put("role", "assistant");
            message.put("status", "completed");
            message.put("id", "msg_" + UUID.randomUUID().toString().replace("-", ""));
            ArrayNode content = objectMapper.createArrayNode();
            ObjectNode text = objectMapper.createObjectNode();
            text.put("type", "output_text");
            text.put("text", result.text);
            text.set("annotations", objectMapper.createArrayNode());
            content.add(text);
            message.set("content", content);
            conversationHistory.add(message);
        }
        for (ResponsesToolCallAccumulator accumulator : state.toolCalls.values()) {
            if (accumulator.name == null || accumulator.name.isBlank()) {
                continue;
            }
            ObjectNode item = objectMapper.createObjectNode();
            item.put("type", "function_call");
            item.put("id", accumulator.itemId);
            item.put("call_id", accumulator.callId);
            item.put("name", accumulator.name);
            item.put("arguments", accumulator.arguments.isEmpty()
                    ? "{}" : accumulator.arguments.toString());
            item.put("status", "completed");
            conversationHistory.add(item);
        }
    }

    private String resolveResponsesUrl(String baseUrl, boolean codex) {
        String normalized = trimTrailingSlashes(baseUrl);
        if (!codex) {
            return normalized.endsWith("/responses") ? normalized : normalized + "/responses";
        }
        if (normalized.endsWith("/codex/responses")) return normalized;
        if (normalized.endsWith("/codex")) return normalized + "/responses";
        return normalized + "/codex/responses";
    }

    // ========================================================================
    // Radius / Pi Messages
    // ========================================================================

    private StreamResult streamPiMessages(
            String userMessage,
            String systemPrompt,
            ArrayNode toolDefs,
            List<ToolCallResultInput> toolResults,
            String effectiveModel) {
        StreamResult result = new StreamResult();
        try {
            OAuthProviderFlow.RequestAuth auth = config.resolveRequestAuth();
            String gateway = config.resolveBaseUrl(auth);
            RadiusGatewayConfig gatewayConfig = loadRadiusGatewayConfig(gateway, auth);
            if (!gatewayConfig.modelIds().isEmpty()
                    && !gatewayConfig.modelIds().contains(effectiveModel)) {
                result.text = "[Radius model is not present in the gateway catalog: "
                        + effectiveModel + "]";
                printStreamingChunk(result.text);
                return result;
            }

            ObjectNode request = objectMapper.createObjectNode();
            request.put("model", effectiveModel);
            ObjectNode context = objectMapper.createObjectNode();
            if (systemPrompt != null && !systemPrompt.isBlank()) {
                context.put("systemPrompt", systemPrompt);
            }
            context.set("messages", buildPiMessages(userMessage, toolResults));
            if (toolDefs != null && !toolDefs.isEmpty()) {
                context.set("tools", convertToolDefsToPi(toolDefs));
            }
            request.set("context", context);
            ObjectNode options = objectMapper.createObjectNode();
            options.put("maxTokens", 8192);
            request.set("options", options);

            String url = appendPath(gatewayConfig.baseUrl(), "/messages");
            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Accept", "text/event-stream")
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(
                            objectMapper.writeValueAsString(request),
                            StandardCharsets.UTF_8))
                    .timeout(Duration.ofMinutes(10));
            if (auth == null || !hasHeader(auth.headers(), "Authorization")) {
                requestBuilder.header("Authorization", "Bearer " + (auth == null ? "" : auth.token()));
            }
            applyHeaders(requestBuilder, auth);

            HttpResponse<java.io.InputStream> response = httpClient.send(
                    requestBuilder.build(), HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                String body = new String(response.body().readAllBytes(), StandardCharsets.UTF_8);
                result.text = "[Radius API error " + response.statusCode()
                        + ": " + extractErrorMessage(body) + "]";
                printStreamingChunk(result.text);
                return result;
            }

            PiMessagesStreamState state = new PiMessagesStreamState();
            parsePiMessagesStream(response.body(), result, state);
            if (!state.failed && !result.cancelled) {
                appendPiMessagesHistory(userMessage, effectiveModel, result, state);
            }
        } catch (Exception e) {
            if (!markCancelled(result, e)) {
                result.text = "[Error: " + formatExceptionMessage(e) + "]";
            }
        }
        return result;
    }

    private RadiusGatewayConfig loadRadiusGatewayConfig(
            String gateway,
            OAuthProviderFlow.RequestAuth auth) throws Exception {
        String normalizedGateway = trimTrailingSlashes(gateway);
        RadiusGatewayConfig cached = radiusGatewayConfig;
        if (cached != null && normalizedGateway.equals(radiusGatewayConfigSource)) {
            return cached;
        }

        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(appendPath(normalizedGateway, "/v1/config")))
                .header("Accept", "application/json")
                .GET()
                .timeout(Duration.ofSeconds(30));
        if (auth == null || !hasHeader(auth.headers(), "Authorization")) {
            builder.header("Authorization", "Bearer " + (auth == null ? "" : auth.token()));
        }
        applyHeaders(builder, auth);
        HttpResponse<String> response = httpClient.send(
                builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("Could not load Radius config (HTTP "
                    + response.statusCode() + "): " + extractErrorMessage(response.body()));
        }
        JsonNode root = objectMapper.readTree(response.body());
        String messagesBaseUrl = root.path("baseUrl").asText(null);
        if (messagesBaseUrl == null || messagesBaseUrl.isBlank()) {
            throw new IllegalStateException("Invalid Radius config: missing baseUrl");
        }
        List<String> modelIds = new ArrayList<>();
        JsonNode models = root.path("models");
        if (!models.isArray()) {
            throw new IllegalStateException("Invalid Radius config: models must be an array");
        }
        for (JsonNode model : models) {
            String id = model.path("id").asText(null);
            if (id != null && !id.isBlank()) {
                modelIds.add(id);
            }
        }
        RadiusGatewayConfig loaded = new RadiusGatewayConfig(
                trimTrailingSlashes(messagesBaseUrl), List.copyOf(modelIds));
        radiusGatewayConfigSource = normalizedGateway;
        radiusGatewayConfig = loaded;
        return loaded;
    }

    private ArrayNode buildPiMessages(
            String userMessage,
            List<ToolCallResultInput> toolResults) {
        ArrayNode messages = objectMapper.createArrayNode();
        conversationHistory.forEach(messages::add);
        if (toolResults != null) {
            for (ToolCallResultInput toolResult : toolResults) {
                ObjectNode message = objectMapper.createObjectNode();
                message.put("role", "toolResult");
                message.put("toolCallId", toolResult.callId);
                message.put("toolName", toolResult.name == null ? "" : toolResult.name);
                ArrayNode content = objectMapper.createArrayNode();
                ObjectNode text = objectMapper.createObjectNode();
                text.put("type", "text");
                text.put("text", toolResult.output == null ? "" : toolResult.output);
                content.add(text);
                message.set("content", content);
                message.put("isError", toolResult.isError);
                message.put("timestamp", System.currentTimeMillis());
                messages.add(message);
                conversationHistory.add(message);
            }
        }
        if (userMessage != null) {
            messages.add(createPiUserMessage(userMessage));
        }
        return messages;
    }

    private ObjectNode createPiUserMessage(String userMessage) {
        ObjectNode message = objectMapper.createObjectNode();
        message.put("role", "user");
        message.put("content", userMessage);
        message.put("timestamp", System.currentTimeMillis());
        return message;
    }

    private ArrayNode convertToolDefsToPi(ArrayNode toolDefs) {
        ArrayNode tools = objectMapper.createArrayNode();
        for (JsonNode tool : toolDefs) {
            ObjectNode piTool = objectMapper.createObjectNode();
            piTool.put("name", tool.path("name").asText());
            piTool.put("description", tool.path("description").asText());
            JsonNode parameters = tool.path("inputSchema");
            if (parameters == null || parameters.isMissingNode()) {
                ObjectNode empty = objectMapper.createObjectNode();
                empty.put("type", "object");
                empty.set("properties", objectMapper.createObjectNode());
                parameters = empty;
            }
            piTool.set("parameters", parameters);
            tools.add(piTool);
        }
        return tools;
    }

    private void parsePiMessagesStream(
            java.io.InputStream inputStream,
            StreamResult result,
            PiMessagesStreamState state) throws Exception {
        Map<Integer, ResponsesToolCallAccumulator> accumulators = new LinkedHashMap<>();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (isCancelled()) {
                    result.cancelled = true;
                    return;
                }
                String trimmed = line.trim();
                if (!trimmed.startsWith("data:")) continue;
                String data = trimmed.substring(5).trim();
                if (data.isEmpty() || "[DONE]".equals(data)) continue;

                JsonNode event;
                try {
                    event = objectMapper.readTree(data);
                } catch (Exception ignored) {
                    continue;
                }
                String type = event.path("type").asText("");
                int contentIndex = event.path("contentIndex").asInt(0);
                switch (type) {
                    case "text_start" ->
                            piContentBlock(state, contentIndex, "text", "text");
                    case "text_delta" -> {
                        String delta = event.path("delta").asText("");
                        ObjectNode block = piContentBlock(state, contentIndex, "text", "text");
                        block.put("text", block.path("text").asText("") + delta);
                        if (!delta.isEmpty()) {
                            printStreamingChunk(delta);
                            result.text += delta;
                        }
                    }
                    case "text_end" -> {
                        ObjectNode block = piContentBlock(state, contentIndex, "text", "text");
                        String content = event.path("content").asText(block.path("text").asText(""));
                        block.put("text", content);
                        String signature = event.path("contentSignature").asText(null);
                        if (signature != null) block.put("textSignature", signature);
                        if (result.text.isEmpty() && !content.isEmpty()) {
                            printStreamingChunk(content);
                            result.text = content;
                        }
                    }
                    case "thinking_start" ->
                            piContentBlock(state, contentIndex, "thinking", "thinking");
                    case "thinking_delta" -> {
                        String delta = event.path("delta").asText("");
                        ObjectNode block = piContentBlock(state, contentIndex, "thinking", "thinking");
                        block.put("thinking", block.path("thinking").asText("") + delta);
                    }
                    case "thinking_end" -> {
                        ObjectNode block = piContentBlock(state, contentIndex, "thinking", "thinking");
                        block.put("thinking",
                                event.path("content").asText(block.path("thinking").asText("")));
                        String signature = event.path("contentSignature").asText(null);
                        if (signature != null) block.put("thinkingSignature", signature);
                        if (event.has("redacted")) block.put("redacted", event.path("redacted").asBoolean());
                    }
                    case "toolcall_start" -> {
                        ResponsesToolCallAccumulator accumulator =
                                accumulators.computeIfAbsent(
                                        contentIndex, ignored -> new ResponsesToolCallAccumulator());
                        accumulator.callId = event.path("id").asText(null);
                        accumulator.name = event.path("toolName").asText(null);
                    }
                    case "toolcall_delta" ->
                            accumulators.computeIfAbsent(
                                            contentIndex, ignored -> new ResponsesToolCallAccumulator())
                                    .arguments.append(event.path("delta").asText(""));
                    case "toolcall_end" -> {
                        JsonNode toolCall = event.path("toolCall");
                        ResponsesToolCallAccumulator accumulator =
                                accumulators.computeIfAbsent(
                                        contentIndex, ignored -> new ResponsesToolCallAccumulator());
                        accumulator.callId = toolCall.path("id").asText(accumulator.callId);
                        accumulator.name = toolCall.path("name").asText(accumulator.name);
                        JsonNode arguments = toolCall.path("arguments");
                        if (!arguments.isMissingNode()) {
                            accumulator.arguments.setLength(0);
                            accumulator.arguments.append(objectMapper.writeValueAsString(arguments));
                        }
                        if (toolCall.isObject()) {
                            ObjectNode block = toolCall.deepCopy();
                            block.put("type", "toolCall");
                            state.contentBlocks.put(contentIndex, block);
                        }
                    }
                    case "done" -> {
                        state.terminal = true;
                        readPiUsage(event.path("usage"), result);
                    }
                    case "error" -> {
                        state.terminal = true;
                        state.failed = true;
                        readPiUsage(event.path("usage"), result);
                        appendProtocolError(
                                result, event.path("errorMessage").asText("Radius request failed"));
                    }
                    default -> {
                        // The start event carries no content.
                    }
                }
            }
        }

        for (Map.Entry<Integer, ResponsesToolCallAccumulator> entry : accumulators.entrySet()) {
            ResponsesToolCallAccumulator accumulator = entry.getValue();
            if (accumulator.name == null || accumulator.name.isBlank()) continue;
            ToolCallOutput toolCall = new ToolCallOutput();
            toolCall.id = accumulator.callId == null || accumulator.callId.isBlank()
                    ? "call_" + result.toolCalls.size()
                    : accumulator.callId;
            toolCall.name = accumulator.name;
            toolCall.arguments = parseToolArguments(accumulator.arguments.toString());
            result.toolCalls.add(toolCall);
            state.contentBlocks.computeIfAbsent(entry.getKey(), ignored -> {
                ObjectNode block = objectMapper.createObjectNode();
                block.put("type", "toolCall");
                block.put("id", toolCall.id);
                block.put("name", toolCall.name);
                block.set("arguments", toolCall.arguments);
                return block;
            });
        }
        if (!state.terminal && !state.failed && !result.cancelled) {
            state.failed = true;
            appendProtocolError(result, "Radius stream ended without a terminal event");
        }
    }

    private ObjectNode piContentBlock(
            PiMessagesStreamState state,
            int contentIndex,
            String type,
            String valueField) {
        return state.contentBlocks.computeIfAbsent(contentIndex, ignored -> {
            ObjectNode block = objectMapper.createObjectNode();
            block.put("type", type);
            block.put(valueField, "");
            return block;
        });
    }

    private void readPiUsage(JsonNode usage, StreamResult result) {
        if (usage == null || usage.isMissingNode()) return;
        result.inputTokens = usage.path("input").asLong(0);
        result.outputTokens = usage.path("output").asLong(0);
        result.cacheReadTokens = usage.path("cacheRead").asLong(0);
        result.cacheCreationTokens = usage.path("cacheWrite").asLong(0);
    }

    private void appendPiMessagesHistory(
            String userMessage,
            String effectiveModel,
            StreamResult result,
            PiMessagesStreamState state) {
        if (userMessage != null) {
            conversationHistory.add(createPiUserMessage(userMessage));
        }
        if (result.text.isEmpty() && result.toolCalls.isEmpty() && state.contentBlocks.isEmpty()) return;

        ObjectNode assistant = objectMapper.createObjectNode();
        assistant.put("role", "assistant");
        ArrayNode content = objectMapper.createArrayNode();
        state.contentBlocks.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> content.add(entry.getValue()));
        boolean hasText = state.contentBlocks.values().stream()
                .anyMatch(block -> "text".equals(block.path("type").asText()));
        boolean hasToolCall = state.contentBlocks.values().stream()
                .anyMatch(block -> "toolCall".equals(block.path("type").asText()));
        if (!hasText && !result.text.isEmpty()) {
            ObjectNode text = objectMapper.createObjectNode();
            text.put("type", "text");
            text.put("text", result.text);
            content.add(text);
        }
        if (!hasToolCall) {
            for (ToolCallOutput toolCall : result.toolCalls) {
                ObjectNode call = objectMapper.createObjectNode();
                call.put("type", "toolCall");
                call.put("id", toolCall.id);
                call.put("name", toolCall.name);
                call.set("arguments", toolCall.arguments);
                content.add(call);
            }
        }
        assistant.set("content", content);
        assistant.put("api", "pi-messages");
        assistant.put("provider", config.getProvider());
        assistant.put("model", effectiveModel);
        ObjectNode usage = objectMapper.createObjectNode();
        usage.put("input", result.inputTokens);
        usage.put("output", result.outputTokens);
        usage.put("cacheRead", result.cacheReadTokens);
        usage.put("cacheWrite", result.cacheCreationTokens);
        usage.put("totalTokens", result.inputTokens + result.outputTokens
                + result.cacheReadTokens + result.cacheCreationTokens);
        ObjectNode cost = objectMapper.createObjectNode();
        cost.put("input", 0);
        cost.put("output", 0);
        cost.put("cacheRead", 0);
        cost.put("cacheWrite", 0);
        cost.put("total", 0);
        usage.set("cost", cost);
        assistant.set("usage", usage);
        assistant.put("stopReason", result.toolCalls.isEmpty() ? "stop" : "toolUse");
        assistant.put("timestamp", System.currentTimeMillis());
        conversationHistory.add(assistant);
    }

    private void appendProtocolError(StreamResult result, String message) {
        String formatted = "[Error: " + message + "]";
        if (!result.text.isEmpty()) {
            result.text += "\n";
        }
        result.text += formatted;
        printStreamingChunk(formatted);
    }

    private JsonNode parseToolArguments(String raw) {
        if (raw == null || raw.isBlank()) {
            return objectMapper.createObjectNode();
        }
        try {
            JsonNode parsed = objectMapper.readTree(raw);
            return parsed == null ? objectMapper.createObjectNode() : parsed;
        } catch (Exception ignored) {
            return objectMapper.createObjectNode();
        }
    }

    private String appendPath(String baseUrl, String path) {
        String normalized = trimTrailingSlashes(baseUrl);
        return normalized.endsWith(path) ? normalized : normalized + path;
    }

    private String trimTrailingSlashes(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Provider base URL is missing");
        }
        return value.trim().replaceAll("/+$", "");
    }

    // ========================================================================
    // Kompile first-party serving subprocess
    // ========================================================================

    /**
     * Send canonical structured chat requests to the private loopback endpoint
     * owned by the first-party {@code ServingSubprocessMain} child.
     */
    private StreamResult streamKompileServing(
            String userMessage,
            String systemPrompt,
            ArrayNode toolDefs,
            List<ToolCallResultInput> toolResults) {
        StreamResult result = new StreamResult();
        try {
            if (isCancelled()) {
                result.cancelled = true;
                return result;
            }
            if (config.getBaseUrl() == null || config.getBaseUrl().isBlank()) {
                throw new IllegalStateException(
                        "Kompile serving subprocess endpoint was not prepared");
            }

            ObjectNode request = objectMapper.createObjectNode();
            ObjectNode structured = request.putObject("request");
            structured.set("messages", buildKompileServingMessages(
                    userMessage, systemPrompt, toolResults));
            ArrayNode tools = buildKompileServingTools(toolDefs);
            structured.set("tools", tools);
            structured.put("addGenerationPrompt", true);
            structured.put("toolDefinitionFormat", "FLAT");
            structured.put("toolCallFormat", "NATIVE");
            structured.put("toolChoice", tools.isEmpty() ? "NONE" : "AUTO");
            request.put("maxTokens", 1024);

            String url = appendPath(config.getBaseUrl(), "/api/llm/chat");
            HttpRequest httpRequest = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(
                            objectMapper.writeValueAsString(request)))
                    .timeout(Duration.ofMinutes(10))
                    .build();
            HttpResponse<String> response = httpClient.send(
                    httpRequest, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                appendProtocolError(result,
                        "Kompile serving HTTP " + response.statusCode() + ": "
                                + extractErrorMessage(response.body()));
                return result;
            }

            JsonNode payload = objectMapper.readTree(response.body());
            String finishReason = payload.path("finishReason").asText("");
            if (finishReason.startsWith("error")) {
                appendProtocolError(result, finishReason);
                return result;
            }

            String rawText = payload.path("rawText").asText("");
            result.text = payload.path("content").asText("");
            JsonNode encodedCalls = payload.path("toolCalls");
            if (encodedCalls.isArray()) {
                for (JsonNode call : encodedCalls) {
                    String name = call.path("name").asText("");
                    if (name.isBlank()) continue;
                    ToolCallOutput output = new ToolCallOutput();
                    output.id = call.path("id").asText("");
                    if (output.id.isBlank()) {
                        output.id = "call_" + result.toolCalls.size();
                    }
                    output.name = name;
                    JsonNode arguments = call.path("arguments");
                    output.arguments = arguments.isObject()
                            ? arguments.deepCopy() : objectMapper.createObjectNode();
                    result.toolCalls.add(output);
                }
            }

            if (result.text.isBlank() && result.toolCalls.isEmpty() && !rawText.isBlank()) {
                result.text = rawText;
            }
            if (!result.text.isEmpty() && !isCancelled()) {
                printStreamingChunk(result.text);
            } else if (isCancelled()) {
                result.cancelled = true;
            }

            if (userMessage != null) {
                ObjectNode user = objectMapper.createObjectNode();
                user.put("role", "user");
                user.put("content", userMessage);
                conversationHistory.add(user);
            }
            if (!rawText.isBlank() || !result.text.isBlank() || !result.toolCalls.isEmpty()) {
                ObjectNode assistant = objectMapper.createObjectNode();
                assistant.put("role", "assistant");
                assistant.put("content", !rawText.isBlank() ? rawText : result.text);
                if (!result.toolCalls.isEmpty()) {
                    ArrayNode calls = assistant.putArray("tool_calls");
                    for (ToolCallOutput call : result.toolCalls) {
                        ObjectNode encoded = calls.addObject();
                        encoded.put("id", call.id);
                        encoded.put("name", call.name);
                        encoded.set("arguments", call.arguments);
                    }
                }
                conversationHistory.add(assistant);
            }
        } catch (Exception e) {
            if (!markCancelled(result, e)) {
                result.text = "[Kompile serving error: " + formatExceptionMessage(e) + "]";
                printStreamingChunk(result.text);
            }
        }
        return result;
    }

    private ArrayNode buildKompileServingMessages(
            String userMessage,
            String systemPrompt,
            List<ToolCallResultInput> toolResults) {
        ArrayNode messages = objectMapper.createArrayNode();
        if (systemPrompt != null && !systemPrompt.isEmpty()) {
            ObjectNode system = messages.addObject();
            system.put("role", "system");
            system.put("content", systemPrompt);
        }
        for (ObjectNode historyMessage : conversationHistory) {
            ObjectNode message = messages.addObject();
            message.put("role", historyMessage.path("role").asText("user"));
            message.put("content", historyMessage.path("content").asText(""));
        }
        if (toolResults != null) {
            for (ToolCallResultInput toolResult : toolResults) {
                ObjectNode message = messages.addObject();
                message.put("role", "tool");
                message.put("content", toolResult.output);
                ObjectNode history = objectMapper.createObjectNode();
                history.put("role", "tool");
                history.put("content", toolResult.output);
                if (toolResult.callId != null) {
                    history.put("tool_call_id", toolResult.callId);
                }
                if (toolResult.name != null) history.put("name", toolResult.name);
                conversationHistory.add(history);
            }
        }
        if (userMessage != null) {
            ObjectNode user = messages.addObject();
            user.put("role", "user");
            user.put("content", userMessage);
        }
        return messages;
    }

    private ArrayNode buildKompileServingTools(ArrayNode toolDefs) {
        ArrayNode tools = objectMapper.createArrayNode();
        if (toolDefs != null) {
            for (JsonNode toolDef : toolDefs) {
                String name = toolDef.path("name").asText("");
                if (name.isBlank()) continue;
                ObjectNode tool = tools.addObject();
                tool.put("name", name);
                tool.put("description", toolDef.path("description").asText(""));
                JsonNode parameters = toolDef.path("inputSchema");
                tool.set("parameters", parameters.isObject()
                        ? parameters.deepCopy()
                        : objectMapper.createObjectNode());
            }
        }
        return tools;
    }

    // ========================================================================
    // OpenAI-compatible Chat Completions
    // ========================================================================

    private StreamResult streamOpenAi(String userMessage, String systemPrompt,
                                       ArrayNode toolDefs, List<ToolCallResultInput> toolResults,
                                       String effectiveModel) {
        StreamResult result = new StreamResult();

        try {
            ArrayNode messages = buildOpenAiMessages(userMessage, systemPrompt, toolResults);

            ObjectNode request = objectMapper.createObjectNode();
            request.put("model", effectiveModel);
            request.set("messages", messages);
            request.put("stream", true);
            applyReasoningEffort(request, false);

            // Request token usage in streamed response
            ObjectNode streamOptions = objectMapper.createObjectNode();
            streamOptions.put("include_usage", true);
            request.set("stream_options", streamOptions);

            if (toolDefs != null && toolDefs.size() > 0) {
                ArrayNode openAiTools = convertToolDefsToOpenAi(toolDefs);
                if (openAiTools.size() > 0) {
                    request.set("tools", openAiTools);
                }
            }

            OAuthProviderFlow.RequestAuth auth = config.resolveRequestAuth();
            String baseUrl = config.resolveBaseUrl(auth);
            String url = baseUrl + "/chat/completions";

            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(request)))
                    .timeout(Duration.ofMinutes(10));
            if (auth == null || !hasHeader(auth.headers(), "Authorization")) {
                requestBuilder.header("Authorization", "Bearer " + (auth == null ? "" : auth.token()));
            }
            applyHeaders(requestBuilder, auth);
            applyProviderRequestHeaders(requestBuilder, userMessage);
            HttpRequest httpRequest = requestBuilder.build();

            HttpResponse<java.io.InputStream> response = httpClient.send(
                    httpRequest, HttpResponse.BodyHandlers.ofInputStream());

            if (response.statusCode() != 200) {
                String body = new String(response.body().readAllBytes());
                result.text = "[LLM API error " + response.statusCode() + ": " + extractErrorMessage(body) + "]";
                printStreamingChunk(result.text);
                return result;
            }

            parseOpenAiStream(response.body(), result);

            // Track in conversation history
            if (userMessage != null) {
                ObjectNode userMsg = objectMapper.createObjectNode();
                userMsg.put("role", "user");
                userMsg.put("content", userMessage);
                conversationHistory.add(userMsg);
            }

            if (!result.text.isEmpty()) {
                ObjectNode assistantMsg = objectMapper.createObjectNode();
                assistantMsg.put("role", "assistant");
                assistantMsg.put("content", result.text);
                if (!result.toolCalls.isEmpty()) {
                    ArrayNode toolCallsArray = objectMapper.createArrayNode();
                    for (ToolCallOutput tc : result.toolCalls) {
                        ObjectNode tcNode = objectMapper.createObjectNode();
                        tcNode.put("id", tc.id);
                        tcNode.put("type", "function");
                        ObjectNode fn = objectMapper.createObjectNode();
                        fn.put("name", tc.name);
                        fn.put("arguments", tc.arguments != null ? tc.arguments.toString() : "{}");
                        tcNode.set("function", fn);
                        toolCallsArray.add(tcNode);
                    }
                    assistantMsg.set("tool_calls", toolCallsArray);
                }
                conversationHistory.add(assistantMsg);
            }

        } catch (Exception e) {
            if (!markCancelled(result, e)) {
                result.text = "[Error: " + formatExceptionMessage(e) + "]";
            }
        }

        return result;
    }

    private ArrayNode buildOpenAiMessages(String userMessage, String systemPrompt,
                                           List<ToolCallResultInput> toolResults) {
        ArrayNode messages = objectMapper.createArrayNode();

        // System prompt
        if (systemPrompt != null && !systemPrompt.isEmpty()) {
            ObjectNode sysMsg = objectMapper.createObjectNode();
            sysMsg.put("role", "system");
            sysMsg.put("content", systemPrompt);
            messages.add(sysMsg);
        }

        // Previous conversation history
        for (ObjectNode msg : conversationHistory) {
            messages.add(msg);
        }

        // Tool results from previous tool calls
        if (toolResults != null && !toolResults.isEmpty()) {
            for (ToolCallResultInput tr : toolResults) {
                ObjectNode toolMsg = objectMapper.createObjectNode();
                toolMsg.put("role", "tool");
                toolMsg.put("tool_call_id", tr.callId);
                toolMsg.put("content", tr.output);
                messages.add(toolMsg);
                conversationHistory.add(toolMsg);
            }
        }

        // Current user message
        if (userMessage != null) {
            ObjectNode userMsg = objectMapper.createObjectNode();
            userMsg.put("role", "user");
            userMsg.put("content", userMessage);
            messages.add(userMsg);
        }

        return messages;
    }

    private ArrayNode convertToolDefsToOpenAi(ArrayNode toolDefs) {
        ArrayNode openAiTools = objectMapper.createArrayNode();
        for (JsonNode tool : toolDefs) {
            ObjectNode oaiTool = objectMapper.createObjectNode();
            oaiTool.put("type", "function");

            ObjectNode fn = objectMapper.createObjectNode();
            fn.put("name", tool.path("name").asText());
            fn.put("description", tool.path("description").asText());

            JsonNode params = tool.path("inputSchema");
            if (params != null && !params.isMissingNode()) {
                fn.set("parameters", params);
            } else {
                ObjectNode emptyParams = objectMapper.createObjectNode();
                emptyParams.put("type", "object");
                emptyParams.set("properties", objectMapper.createObjectNode());
                fn.set("parameters", emptyParams);
            }

            oaiTool.set("function", fn);
            openAiTools.add(oaiTool);
        }
        return openAiTools;
    }

    private void parseOpenAiStream(java.io.InputStream inputStream, StreamResult result) throws Exception {
        // Track tool calls being assembled from deltas
        List<ToolCallAccumulator> toolAccumulators = new ArrayList<>();

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (isCancelled()) {
                    result.cancelled = true;
                    break;
                }
                if (!line.startsWith("data: ")) continue;
                String data = line.substring(6).trim();
                if ("[DONE]".equals(data)) break;

                try {
                    JsonNode chunk = objectMapper.readTree(data);
                    JsonNode delta = chunk.path("choices").path(0).path("delta");

                    // Text content
                    String content = delta.path("content").asText(null);
                    if (content != null) {
                        printStreamingChunk(content);
                        result.text += content;
                    }

                    // Tool calls (streamed as deltas)
                    JsonNode toolCallsNode = delta.path("tool_calls");
                    if (toolCallsNode.isArray()) {
                        for (JsonNode tcDelta : toolCallsNode) {
                            int index = tcDelta.path("index").asInt(0);
                            while (toolAccumulators.size() <= index) {
                                toolAccumulators.add(new ToolCallAccumulator());
                            }
                            ToolCallAccumulator acc = toolAccumulators.get(index);

                            String id = tcDelta.path("id").asText(null);
                            if (id != null) acc.id = id;

                            String name = tcDelta.path("function").path("name").asText(null);
                            if (name != null) acc.name = name;

                            String args = tcDelta.path("function").path("arguments").asText(null);
                            if (args != null) acc.arguments.append(args);
                        }
                    }

                    // Extract token usage if present (final chunk in OpenAI streaming)
                    JsonNode usageNode = chunk.path("usage");
                    if (!usageNode.isMissingNode()) {
                        result.inputTokens = usageNode.path("prompt_tokens").asLong(0);
                        result.outputTokens = usageNode.path("completion_tokens").asLong(0);
                    }

                    // Check for finish_reason
                    String finishReason = chunk.path("choices").path(0).path("finish_reason").asText(null);
                    if ("tool_calls".equals(finishReason) || "stop".equals(finishReason)) {
                        // Finalize any accumulated tool calls
                        for (ToolCallAccumulator acc : toolAccumulators) {
                            if (acc.name != null) {
                                ToolCallOutput tc = new ToolCallOutput();
                                tc.id = acc.id != null ? acc.id : "call_" + result.toolCalls.size();
                                tc.name = acc.name;
                                try {
                                    tc.arguments = objectMapper.readTree(acc.arguments.toString());
                                } catch (Exception e) {
                                    tc.arguments = objectMapper.createObjectNode();
                                }
                                result.toolCalls.add(tc);
                            }
                        }
                    }
                } catch (Exception e) {
                    // Skip unparseable chunks
                }
            }
        }

        // If tool calls were accumulated but finish_reason wasn't caught
        if (result.toolCalls.isEmpty() && !toolAccumulators.isEmpty()) {
            for (ToolCallAccumulator acc : toolAccumulators) {
                if (acc.name != null) {
                    ToolCallOutput tc = new ToolCallOutput();
                    tc.id = acc.id != null ? acc.id : "call_" + result.toolCalls.size();
                    tc.name = acc.name;
                    try {
                        tc.arguments = objectMapper.readTree(acc.arguments.toString());
                    } catch (Exception e) {
                        tc.arguments = objectMapper.createObjectNode();
                    }
                    result.toolCalls.add(tc);
                }
            }
        }
    }

    // ========================================================================
    // Anthropic Messages API
    // ========================================================================

    private StreamResult streamAnthropic(String userMessage, String systemPrompt,
                                          ArrayNode toolDefs, List<ToolCallResultInput> toolResults,
                                          String effectiveModel) {
        StreamResult result = new StreamResult();

        try {
            OAuthProviderFlow.RequestAuth auth = config.resolveRequestAuth();
            ObjectNode request = objectMapper.createObjectNode();
            request.put("model", effectiveModel);
            request.put("max_tokens", 8192);
            request.put("stream", true);

            boolean anthropicOAuth = auth != null && auth.oauth()
                    && "anthropic".equalsIgnoreCase(config.getProvider());
            if (anthropicOAuth) {
                String identity = "You are Claude Code, Anthropic's official CLI for Claude.";
                request.put("system", systemPrompt == null || systemPrompt.isEmpty()
                        ? identity
                        : identity + "\\n\\n" + systemPrompt);
            } else if (systemPrompt != null && !systemPrompt.isEmpty()) {
                request.put("system", systemPrompt);
            }

            ArrayNode messages = buildAnthropicMessages(userMessage, toolResults);
            request.set("messages", messages);

            if (toolDefs != null && toolDefs.size() > 0) {
                ArrayNode anthropicTools = convertToolDefsToAnthropic(toolDefs);
                if (anthropicTools.size() > 0) {
                    request.set("tools", anthropicTools);
                }
            }

            String baseUrl = config.resolveBaseUrl(auth);

            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/v1/messages"))
                    .header("Content-Type", "application/json")
                    .header("anthropic-version", "2023-06-01")
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(request)))
                    .timeout(Duration.ofMinutes(10));
            if (auth == null || (!hasHeader(auth.headers(), "Authorization")
                    && !hasHeader(auth.headers(), "x-api-key"))) {
                if ("github-copilot".equals(config.getProvider())) {
                    requestBuilder.header(
                            "Authorization", "Bearer " + (auth == null ? "" : auth.token()));
                } else {
                    requestBuilder.header("x-api-key", auth == null ? "" : auth.token());
                }
            }
            applyHeaders(requestBuilder, auth);
            applyProviderRequestHeaders(requestBuilder, userMessage);
            HttpRequest httpRequest = requestBuilder.build();

            HttpResponse<java.io.InputStream> response = httpClient.send(
                    httpRequest, HttpResponse.BodyHandlers.ofInputStream());

            if (response.statusCode() != 200) {
                String body = new String(response.body().readAllBytes());
                result.text = "[Anthropic API error " + response.statusCode() + ": " + extractErrorMessage(body) + "]";
                return result;
            }

            parseAnthropicStream(response.body(), result);

            // Track in conversation history
            if (userMessage != null) {
                ObjectNode userMsg = objectMapper.createObjectNode();
                userMsg.put("role", "user");
                ArrayNode content = objectMapper.createArrayNode();
                ObjectNode textBlock = objectMapper.createObjectNode();
                textBlock.put("type", "text");
                textBlock.put("text", userMessage);
                content.add(textBlock);
                userMsg.set("content", content);
                conversationHistory.add(userMsg);
            }

            if (!result.text.isEmpty() || !result.toolCalls.isEmpty()) {
                ObjectNode assistantMsg = objectMapper.createObjectNode();
                assistantMsg.put("role", "assistant");
                ArrayNode content = objectMapper.createArrayNode();
                if (!result.text.isEmpty()) {
                    ObjectNode textBlock = objectMapper.createObjectNode();
                    textBlock.put("type", "text");
                    textBlock.put("text", result.text);
                    content.add(textBlock);
                }
                for (ToolCallOutput tc : result.toolCalls) {
                    ObjectNode toolUseBlock = objectMapper.createObjectNode();
                    toolUseBlock.put("type", "tool_use");
                    toolUseBlock.put("id", tc.id);
                    toolUseBlock.put("name", tc.name);
                    toolUseBlock.set("input", tc.arguments);
                    content.add(toolUseBlock);
                }
                assistantMsg.set("content", content);
                conversationHistory.add(assistantMsg);
            }

        } catch (Exception e) {
            if (!markCancelled(result, e)) {
                result.text = "[Error: " + formatExceptionMessage(e) + "]";
            }
        }

        return result;
    }

    private static void applyHeaders(
            HttpRequest.Builder builder,
            OAuthProviderFlow.RequestAuth auth) {
        if (auth != null) {
            auth.headers().forEach(builder::setHeader);
        }
    }

    private static boolean hasHeader(Map<String, String> headers, String expectedName) {
        return headers.keySet().stream().anyMatch(name -> name.equalsIgnoreCase(expectedName));
    }

    private void applyProviderRequestHeaders(
            HttpRequest.Builder builder,
            String userMessage) {
        if ("github-copilot".equals(config.getProvider())) {
            builder.setHeader("User-Agent", "GitHubCopilotChat/0.35.0");
            builder.setHeader("Editor-Version", "vscode/1.107.0");
            builder.setHeader("Editor-Plugin-Version", "copilot-chat/0.35.0");
            builder.setHeader("Copilot-Integration-Id", "vscode-chat");
            builder.setHeader("X-Initiator", userMessage == null ? "agent" : "user");
            builder.setHeader("Openai-Intent", "conversation-edits");
        }
    }

    private ArrayNode buildAnthropicMessages(String userMessage, List<ToolCallResultInput> toolResults) {
        ArrayNode messages = objectMapper.createArrayNode();

        // Previous history
        for (ObjectNode msg : conversationHistory) {
            messages.add(msg);
        }

        // Tool results
        if (toolResults != null && !toolResults.isEmpty()) {
            ObjectNode userMsg = objectMapper.createObjectNode();
            userMsg.put("role", "user");
            ArrayNode content = objectMapper.createArrayNode();
            for (ToolCallResultInput tr : toolResults) {
                ObjectNode toolResultBlock = objectMapper.createObjectNode();
                toolResultBlock.put("type", "tool_result");
                toolResultBlock.put("tool_use_id", tr.callId);
                toolResultBlock.put("content", tr.output);
                if (tr.isError) {
                    toolResultBlock.put("is_error", true);
                }
                content.add(toolResultBlock);
            }
            userMsg.set("content", content);
            messages.add(userMsg);
            conversationHistory.add(userMsg);
        }

        // Current user message
        if (userMessage != null) {
            ObjectNode userMsg = objectMapper.createObjectNode();
            userMsg.put("role", "user");
            ArrayNode content = objectMapper.createArrayNode();
            ObjectNode textBlock = objectMapper.createObjectNode();
            textBlock.put("type", "text");
            textBlock.put("text", userMessage);
            content.add(textBlock);
            userMsg.set("content", content);
            messages.add(userMsg);
        }

        return messages;
    }

    private ArrayNode convertToolDefsToAnthropic(ArrayNode toolDefs) {
        ArrayNode anthropicTools = objectMapper.createArrayNode();
        for (JsonNode tool : toolDefs) {
            ObjectNode at = objectMapper.createObjectNode();
            at.put("name", tool.path("name").asText());
            at.put("description", tool.path("description").asText());

            JsonNode params = tool.path("inputSchema");
            if (params != null && !params.isMissingNode()) {
                at.set("input_schema", params);
            } else {
                ObjectNode emptySchema = objectMapper.createObjectNode();
                emptySchema.put("type", "object");
                emptySchema.set("properties", objectMapper.createObjectNode());
                at.set("input_schema", emptySchema);
            }

            anthropicTools.add(at);
        }
        return anthropicTools;
    }

    private void parseAnthropicStream(java.io.InputStream inputStream, StreamResult result) throws Exception {
        String currentToolId = null;
        String currentToolName = null;
        StringBuilder currentToolArgs = new StringBuilder();

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (isCancelled()) {
                    result.cancelled = true;
                    break;
                }
                if (!line.startsWith("data: ")) continue;
                String data = line.substring(6).trim();

                try {
                    JsonNode event = objectMapper.readTree(data);
                    String type = event.path("type").asText("");

                    switch (type) {
                        case "message_start": {
                            // Anthropic sends input token count in message_start
                            JsonNode msgUsage = event.path("message").path("usage");
                            if (!msgUsage.isMissingNode()) {
                                result.inputTokens = msgUsage.path("input_tokens").asLong(0);
                                result.cacheReadTokens = msgUsage.path("cache_read_input_tokens").asLong(0);
                                result.cacheCreationTokens = msgUsage.path("cache_creation_input_tokens").asLong(0);
                            }
                            break;
                        }

                        case "content_block_start": {
                            JsonNode contentBlock = event.path("content_block");
                            String blockType = contentBlock.path("type").asText("");
                            if ("tool_use".equals(blockType)) {
                                currentToolId = contentBlock.path("id").asText("call_" + result.toolCalls.size());
                                currentToolName = contentBlock.path("name").asText("");
                                currentToolArgs.setLength(0);
                            }
                            break;
                        }

                        case "content_block_delta": {
                            JsonNode delta = event.path("delta");
                            String deltaType = delta.path("type").asText("");

                            if ("text_delta".equals(deltaType)) {
                                String text = delta.path("text").asText("");
                                printStreamingChunk(text);
                                result.text += text;
                            } else if ("input_json_delta".equals(deltaType)) {
                                String partial = delta.path("partial_json").asText("");
                                currentToolArgs.append(partial);
                            }
                            break;
                        }

                        case "content_block_stop": {
                            if (currentToolName != null) {
                                ToolCallOutput tc = new ToolCallOutput();
                                tc.id = currentToolId;
                                tc.name = currentToolName;
                                try {
                                    tc.arguments = objectMapper.readTree(currentToolArgs.toString());
                                } catch (Exception e) {
                                    tc.arguments = objectMapper.createObjectNode();
                                }
                                result.toolCalls.add(tc);
                                currentToolId = null;
                                currentToolName = null;
                                currentToolArgs.setLength(0);
                            }
                            break;
                        }

                        case "message_stop":
                            break;

                        case "message_delta": {
                            // Anthropic sends output token count in message_delta
                            JsonNode deltaUsage = event.path("usage");
                            if (!deltaUsage.isMissingNode()) {
                                result.outputTokens = deltaUsage.path("output_tokens").asLong(0);
                            }
                            break;
                        }

                        case "error": {
                            String msg = event.path("error").path("message").asText(data);
                            result.text += "\n[Error: " + msg + "]";
                            break;
                        }
                    }
                } catch (Exception e) {
                    // Skip unparseable events
                }
            }
        }
    }

    // ========================================================================
    // Helpers
    // ========================================================================

    /**
     * Build an OpenAI-compatible content array for a message with optional attachments.
     * Images become image_url blocks; text files become text blocks with embedded content.
     * The user message text is appended as the final text block.
     */
    private ArrayNode buildOpenAiContentArray(String text, List<AttachmentInput> attachments) {
        ArrayNode content = objectMapper.createArrayNode();
        if (attachments != null) {
            for (AttachmentInput att : attachments) {
                if (att.isImage()) {
                    ObjectNode imageBlock = objectMapper.createObjectNode();
                    imageBlock.put("type", "image_url");
                    ObjectNode imageUrl = objectMapper.createObjectNode();
                    imageUrl.put("url", "data:" + att.mimeType() + ";base64," + att.base64Data());
                    imageBlock.set("image_url", imageUrl);
                    content.add(imageBlock);
                } else {
                    ObjectNode textBlock = objectMapper.createObjectNode();
                    textBlock.put("type", "text");
                    textBlock.put("text", "[File: " + att.path() + "]\n" + att.textContent());
                    content.add(textBlock);
                }
            }
        }
        ObjectNode textBlock = objectMapper.createObjectNode();
        textBlock.put("type", "text");
        textBlock.put("text", text);
        content.add(textBlock);
        return content;
    }

    /**
     * Build an Anthropic-compatible content array for a message with optional attachments.
     * Images become base64 image source blocks; text files become text blocks with embedded content.
     * The user message text is appended as the final text block.
     */
    private ArrayNode buildAnthropicContentArray(String text, List<AttachmentInput> attachments) {
        ArrayNode content = objectMapper.createArrayNode();
        if (attachments != null) {
            for (AttachmentInput att : attachments) {
                if (att.isImage()) {
                    ObjectNode imageBlock = objectMapper.createObjectNode();
                    imageBlock.put("type", "image");
                    ObjectNode source = objectMapper.createObjectNode();
                    source.put("type", "base64");
                    source.put("media_type", att.mimeType());
                    source.put("data", att.base64Data());
                    imageBlock.set("source", source);
                    content.add(imageBlock);
                } else {
                    ObjectNode textBlock = objectMapper.createObjectNode();
                    textBlock.put("type", "text");
                    textBlock.put("text", "[File: " + att.path() + "]\n" + att.textContent());
                    content.add(textBlock);
                }
            }
        }
        ObjectNode textBlock = objectMapper.createObjectNode();
        textBlock.put("type", "text");
        textBlock.put("text", text);
        content.add(textBlock);
        return content;
    }

    private boolean markCancelled(StreamResult result, Exception error) {
        if (isCancelled() || error instanceof InterruptedException) {
            result.cancelled = true;
            return true;
        }
        return false;
    }

    /**
     * Format an exception message for display. If the exception has no message,
     * returns the simple class name of the exception type.
     */
    private String formatExceptionMessage(Exception e) {
        String msg = e.getMessage();
        return (msg != null) ? msg : e.getClass().getSimpleName();
    }

    private String extractErrorMessage(String body) {
        try {
            JsonNode json = objectMapper.readTree(body);
            // OpenAI format
            String msg = json.path("error").path("message").asText(null);
            if (msg != null) return msg;
            // Anthropic format
            msg = json.path("error").path("message").asText(null);
            if (msg != null) return msg;
            return body.length() > 200 ? body.substring(0, 200) + "..." : body;
        } catch (Exception e) {
            return body.length() > 200 ? body.substring(0, 200) + "..." : body;
        }
    }

    // ========================================================================
    // Data classes
    // ========================================================================

    public static class StreamResult {
        public String text = "";
        public List<ToolCallOutput> toolCalls = new ArrayList<>();
        public boolean cancelled = false;
        // Token usage from API response (when available)
        public long inputTokens = 0;
        public long outputTokens = 0;
        public long cacheReadTokens = 0;
        public long cacheCreationTokens = 0;

        /**
         * Tokens occupying the provider context. Cached tokens are cheaper, not absent;
         * every provider adapter reports them separately but they still consume context.
         */
        public long contextInputTokens() {
            long total = Math.max(0L, inputTokens);
            total = saturatingAdd(total, Math.max(0L, cacheReadTokens));
            return saturatingAdd(total, Math.max(0L, cacheCreationTokens));
        }

        private static long saturatingAdd(long left, long right) {
            return Long.MAX_VALUE - left < right ? Long.MAX_VALUE : left + right;
        }

        // Enforcer monitor fields
        public boolean monitorInterrupted = false;
        public String correctionPrompt = null;
    }

    public static class ToolCallOutput {
        public String id;
        public String name;
        public JsonNode arguments;
    }

    public static class ToolCallResultInput {
        public String callId;
        public String name;
        public String output;
        public boolean isError;

        public ToolCallResultInput(String callId, String name, String output, boolean isError) {
            this.callId = callId;
            this.name = name;
            this.output = output;
            this.isError = isError;
        }
    }

    /**
     * An attachment (file, image, etc.) to include with a chat message.
     */
    public record AttachmentInput(String path, String mimeType, boolean isImage, String base64Data, String textContent) {
        public AttachmentInput(String path, String mimeType) {
            this(path, mimeType, false, null, null);
        }
    }

    private static class ToolCallAccumulator {
        String id;
        String name;
        StringBuilder arguments = new StringBuilder();
    }

    private static class ResponsesToolCallAccumulator {
        String itemId;
        String callId;
        String name;
        StringBuilder arguments = new StringBuilder();
    }

    private static class ResponsesStreamState {
        final Map<Integer, ObjectNode> reasoningItems = new LinkedHashMap<>();
        final Map<Integer, ResponsesToolCallAccumulator> toolCalls = new LinkedHashMap<>();
        boolean terminal;
        boolean failed;
    }

    private static class PiMessagesStreamState {
        final Map<Integer, ObjectNode> contentBlocks = new LinkedHashMap<>();
        boolean terminal;
        boolean failed;
    }

    private record RadiusGatewayConfig(String baseUrl, List<String> modelIds) {
    }
}
