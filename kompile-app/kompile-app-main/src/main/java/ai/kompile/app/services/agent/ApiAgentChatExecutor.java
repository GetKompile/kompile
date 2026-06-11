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

package ai.kompile.app.services.agent;

import ai.kompile.core.agent.AgentProvider;
import ai.kompile.core.retrievers.RetrievedDoc;
import ai.kompile.app.web.dto.AgentChatRequest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Executes chat against OpenAI-compatible API endpoints with streaming.
 * <p>
 * Supports any endpoint implementing the OpenAI chat completions API:
 * OpenAI, Ollama, vLLM, LM Studio, Azure OpenAI, etc.
 */
@Service
public class ApiAgentChatExecutor {

    private static final Logger log = LoggerFactory.getLogger(ApiAgentChatExecutor.class);

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ExecutorService executorService = Executors.newCachedThreadPool();
    private final ModelCapabilityService modelCapabilityService;

    public ApiAgentChatExecutor(ModelCapabilityService modelCapabilityService) {
        this.modelCapabilityService = modelCapabilityService;
    }

    public ApiAgentChatExecutor() {
        this(new ModelCapabilityService(null));
    }

    // Track active connections for cancellation
    private final Map<String, HttpURLConnection> activeConnections = new ConcurrentHashMap<>();

    /**
     * Execute an API chat request with streaming.
     */
    public void executeApiChat(
            AgentProvider agent,
            AgentChatRequest request,
            String augmentedPrompt,
            List<RetrievedDoc> retrievedSources,
            SseEmitter emitter) {

        String processId = UUID.randomUUID().toString();

        executorService.submit(() -> {
            HttpURLConnection connection = null;
            try {
                // Send sources if RAG was used
                if (!retrievedSources.isEmpty()) {
                    sendEvent(emitter, "sources", formatSourcesForClient(retrievedSources));
                }

                // Send start event
                sendEvent(emitter, "start", Map.of(
                        "processId", processId,
                        "agent", agent.getName(),
                        "ragEnabled", request.isEnableRag(),
                        "graphRagEnabled", request.isEnableGraphRag()));

                // Build the OpenAI-compatible request
                String requestBody = buildOpenAiRequest(agent, request, augmentedPrompt);

                // Connect to the endpoint
                String url = normalizeEndpointUrl(agent.getEndpointUrl()) + "/chat/completions";
                log.info("Calling API endpoint: {} with model: {}", url, agent.getModelName());

                connection = (HttpURLConnection) URI.create(url).toURL().openConnection();
                connection.setRequestMethod("POST");
                connection.setRequestProperty("Content-Type", "application/json");
                connection.setRequestProperty("Accept", "text/event-stream");

                // Add API key if present
                if (agent.getApiKey() != null && !agent.getApiKey().isEmpty()) {
                    connection.setRequestProperty("Authorization", "Bearer " + agent.getApiKey());
                }

                connection.setDoOutput(true);
                connection.setConnectTimeout(30_000);
                connection.setReadTimeout(0); // No read timeout for streaming

                // Track connection for cancellation
                activeConnections.put(processId, connection);

                // Send request body
                try (OutputStream os = connection.getOutputStream()) {
                    os.write(requestBody.getBytes(StandardCharsets.UTF_8));
                }

                int responseCode = connection.getResponseCode();
                if (responseCode != 200) {
                    String errorBody = readErrorStream(connection);
                    log.error("API endpoint returned {}: {}", responseCode, errorBody);
                    sendError(emitter, "API error (" + responseCode + "): " + errorBody);
                    return;
                }

                // Stream the response
                long startTime = System.currentTimeMillis();
                int outputTokens = 0;
                StringBuilder fullResponse = new StringBuilder();

                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {

                    String line;
                    while ((line = reader.readLine()) != null) {
                        // Check if cancelled
                        if (!activeConnections.containsKey(processId)) {
                            sendEvent(emitter, "cancelled", Map.of(
                                    "processId", processId,
                                    "content", fullResponse.toString()));
                            break;
                        }

                        if (line.isEmpty()) continue;

                        if (line.startsWith("data: ")) {
                            String data = line.substring(6).trim();

                            if ("[DONE]".equals(data)) {
                                break;
                            }

                            try {
                                JsonNode chunk = objectMapper.readTree(data);
                                JsonNode choices = chunk.path("choices");
                                if (choices.isArray() && choices.size() > 0) {
                                    JsonNode delta = choices.get(0).path("delta");
                                    String content = delta.path("content").asText(null);
                                    if (content != null && !content.isEmpty()) {
                                        fullResponse.append(content);
                                        outputTokens++;
                                        sendEvent(emitter, "chunk", content);
                                    }

                                    // Check for finish_reason
                                    String finishReason = choices.get(0).path("finish_reason").asText(null);
                                    if ("stop".equals(finishReason) || "length".equals(finishReason)) {
                                        // Will be handled after loop
                                    }
                                }

                                // Extract usage if present (some APIs include it in the final chunk)
                                JsonNode usage = chunk.path("usage");
                                if (!usage.isMissingNode() && usage.has("completion_tokens")) {
                                    outputTokens = usage.path("completion_tokens").asInt(outputTokens);
                                }
                            } catch (Exception e) {
                                log.debug("Failed to parse SSE chunk: {}", data, e);
                            }
                        }
                    }
                }

                long durationMs = System.currentTimeMillis() - startTime;
                double tokensPerSecond = durationMs > 0 ? (outputTokens * 1000.0 / durationMs) : 0;

                // Send stats
                Map<String, Object> stats = new HashMap<>();
                stats.put("durationMs", durationMs);
                stats.put("costUsd", 0.0);
                stats.put("numTurns", 1);
                stats.put("isError", false);

                Map<String, Object> tokenMetrics = new HashMap<>();
                tokenMetrics.put("outputTokens", outputTokens);
                tokenMetrics.put("totalGenerationMs", durationMs);
                tokenMetrics.put("tokensPerSecond", tokensPerSecond);
                tokenMetrics.put("model", agent.getModelName());
                stats.put("tokenMetrics", tokenMetrics);
                sendEvent(emitter, "stats", stats);

                // Send complete
                sendEvent(emitter, "complete", Map.of(
                        "processId", processId,
                        "content", fullResponse.toString(),
                        "modifiedFiles", Collections.emptyList()));

            } catch (Exception e) {
                if (!activeConnections.containsKey(processId)) {
                    // Cancelled - don't send error
                    log.info("API chat cancelled for process {}", processId);
                } else {
                    log.error("Error executing API agent chat", e);
                    sendError(emitter, "API execution error: " + e.getMessage());
                }
            } finally {
                activeConnections.remove(processId);
                if (connection != null) {
                    connection.disconnect();
                }
                try {
                    emitter.complete();
                } catch (Exception e) {
                    log.debug("Error completing emitter", e);
                }
            }
        });
    }

    /**
     * Build OpenAI-compatible chat completions request body.
     */
    /**
     * Validate that any attachments are compatible with the agent's model.
     *
     * @return {@code null} if attachments are valid (or there are none), otherwise an error message
     */
    public String validateAttachments(AgentProvider agent, List<AgentChatRequest.MessageAttachment> attachments) {
        if (attachments == null || attachments.isEmpty()) return null;
        String modelId = agent != null ? agent.getModelName() : null;
        for (AgentChatRequest.MessageAttachment att : attachments) {
            if (att.isImage() && !modelCapabilityService.supportsVision(modelId)) {
                return "Model '" + modelId + "' does not support image attachments. " +
                        "Please select a vision-capable model.";
            }
        }
        return null;
    }

    private String buildOpenAiRequest(AgentProvider agent, AgentChatRequest request, String augmentedPrompt) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("model", agent.getModelName());
        root.put("stream", true);
        root.put("temperature", agent.getTemperature());
        root.put("max_tokens", agent.getMaxTokens());

        ArrayNode messages = root.putArray("messages");

        // Add chat history if present — always flat strings (no attachments on history)
        if (request.getChatHistory() != null) {
            for (AgentChatRequest.ChatHistoryEntry entry : request.getChatHistory()) {
                ObjectNode msg = messages.addObject();
                msg.put("role", entry.getRole().toLowerCase());
                msg.put("content", entry.getContent());
            }
        }

        // Add the current message
        List<AgentChatRequest.MessageAttachment> attachments = request.getAttachments();
        ObjectNode userMsg = messages.addObject();
        userMsg.put("role", "user");

        if (attachments == null || attachments.isEmpty()) {
            // No attachments — keep backward-compatible flat string content
            userMsg.put("content", augmentedPrompt);
        } else {
            // Multimodal content — build a content array
            ArrayNode contentArray = userMsg.putArray("content");

            for (AgentChatRequest.MessageAttachment att : attachments) {
                if (att.isImage() && att.base64Data() != null) {
                    // OpenAI image_url block
                    ObjectNode imgBlock = contentArray.addObject();
                    imgBlock.put("type", "image_url");
                    ObjectNode imgUrl = imgBlock.putObject("image_url");
                    imgUrl.put("url", "data:" + att.mimeType() + ";base64," + att.base64Data());
                } else if (att.textContent() != null) {
                    // Text file block — embed content with filename header
                    ObjectNode textBlock = contentArray.addObject();
                    textBlock.put("type", "text");
                    textBlock.put("text", "[File: " + att.filename() + "]\n" + att.textContent());
                }
            }

            // Append the user's prompt as the last text block
            ObjectNode promptBlock = contentArray.addObject();
            promptBlock.put("type", "text");
            promptBlock.put("text", augmentedPrompt);
        }

        return root.toString();
    }

    /**
     * Normalize endpoint URL - ensure no trailing slash.
     */
    private String normalizeEndpointUrl(String url) {
        if (url == null) return "";
        url = url.trim();
        while (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        return url;
    }

    /**
     * Read error response body.
     */
    private String readErrorStream(HttpURLConnection connection) {
        try {
            var errorStream = connection.getErrorStream();
            if (errorStream == null) return "No error details";
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(errorStream, StandardCharsets.UTF_8))) {
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line);
                }
                String body = sb.toString();
                return body.length() > 500 ? body.substring(0, 500) + "..." : body;
            }
        } catch (Exception e) {
            return "Failed to read error: " + e.getMessage();
        }
    }

    /**
     * Cancel an active API stream.
     */
    public boolean cancelApiStream(String processId) {
        HttpURLConnection connection = activeConnections.remove(processId);
        if (connection != null) {
            log.info("Cancelling API stream for process: {}", processId);
            connection.disconnect();
            return true;
        }
        return false;
    }

    /**
     * Check if a process is an active API stream.
     */
    public boolean isApiStream(String processId) {
        return activeConnections.containsKey(processId);
    }

    /**
     * Test connectivity to an API endpoint.
     */
    public Map<String, Object> testEndpoint(String endpointUrl, String apiKey) {
        Map<String, Object> result = new HashMap<>();
        try {
            String url = normalizeEndpointUrl(endpointUrl) + "/models";
            HttpURLConnection connection = (HttpURLConnection) URI.create(url).toURL().openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(10_000);
            connection.setReadTimeout(10_000);

            if (apiKey != null && !apiKey.isEmpty()) {
                connection.setRequestProperty("Authorization", "Bearer " + apiKey);
            }

            int responseCode = connection.getResponseCode();
            result.put("reachable", responseCode == 200);
            result.put("statusCode", responseCode);

            if (responseCode == 200) {
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) {
                        sb.append(line);
                    }
                    JsonNode modelsResponse = objectMapper.readTree(sb.toString());
                    JsonNode data = modelsResponse.path("data");
                    if (data.isArray()) {
                        List<String> modelIds = new ArrayList<>();
                        for (JsonNode model : data) {
                            modelIds.add(model.path("id").asText());
                        }
                        result.put("models", modelIds);
                    }
                }
            } else {
                result.put("error", readErrorStream(connection));
            }

            connection.disconnect();
        } catch (Exception e) {
            result.put("reachable", false);
            result.put("error", e.getMessage());
        }
        return result;
    }

    private void sendEvent(SseEmitter emitter, String eventType, Object data) {
        try {
            emitter.send(SseEmitter.event()
                    .name(eventType)
                    .data(data));
        } catch (IOException e) {
            log.debug("Error sending SSE event: {}", e.getMessage());
        }
    }

    private void sendError(SseEmitter emitter, String errorMessage) {
        try {
            emitter.send(SseEmitter.event()
                    .name("error")
                    .data(Map.of("message", errorMessage)));
        } catch (IOException e) {
            log.debug("Error sending error event: {}", e.getMessage());
        }
    }

    private List<Map<String, Object>> formatSourcesForClient(List<RetrievedDoc> docs) {
        List<Map<String, Object>> sources = new ArrayList<>();
        int index = 1;
        for (RetrievedDoc doc : docs) {
            if (doc.getText() == null || doc.getText().isEmpty()) continue;
            Map<String, Object> source = new HashMap<>();
            source.put("index", index);
            source.put("id", doc.getId() != null ? doc.getId() : "doc-" + index);
            source.put("score", doc.getScore() != null ? doc.getScore() : 0.0);
            String content = doc.getText();
            source.put("preview", content.length() > 300 ? content.substring(0, 300) + "..." : content);
            source.put("content", content.length() > 2000 ? content.substring(0, 2000) + "... [truncated]" : content);
            sources.add(source);
            index++;
        }
        return sources;
    }
}
