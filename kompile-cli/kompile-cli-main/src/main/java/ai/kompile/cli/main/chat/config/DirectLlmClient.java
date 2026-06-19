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
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Direct LLM client that calls provider APIs without requiring a kompile-app server.
 * Supports both OpenAI-compatible format and Anthropic Messages API.
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
        if (config.isAnthropicFormat()) {
            return streamAnthropic(userMessage, systemPrompt, toolDefs, toolResults, effectiveModel);
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

            String baseUrl = config.resolveBaseUrl();
            String url = baseUrl + "/chat/completions";

            HttpRequest httpRequest = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + (config.getApiKey() != null ? config.getApiKey() : ""))
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(request)))
                    .timeout(Duration.ofMinutes(10))
                    .build();

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
            result.text = "[Error: " + e.getMessage() + "]";
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
            ObjectNode request = objectMapper.createObjectNode();
            request.put("model", effectiveModel);
            request.put("max_tokens", 8192);
            request.put("stream", true);

            if (systemPrompt != null && !systemPrompt.isEmpty()) {
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

            String baseUrl = config.resolveBaseUrl();

            HttpRequest httpRequest = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/v1/messages"))
                    .header("Content-Type", "application/json")
                    .header("x-api-key", config.getApiKey() != null ? config.getApiKey() : "")
                    .header("anthropic-version", "2023-06-01")
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(request)))
                    .timeout(Duration.ofMinutes(10))
                    .build();

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
            result.text = "[Error: " + e.getMessage() + "]";
        }

        return result;
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
}
