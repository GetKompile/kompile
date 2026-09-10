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

import ai.kompile.cli.common.util.JsonUtils;
import ai.kompile.core.agent.AgentProvider;
import ai.kompile.core.citation.CitationDto;
import ai.kompile.core.retrievers.RetrievedDoc;
import ai.kompile.core.source.SourceMetadataConstants;
import ai.kompile.app.web.dto.AgentChatRequest;
import ai.kompile.knowledgegraph.citation.CitationSupport;
import ai.kompile.knowledgegraph.domain.GraphProvenanceKeys;
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
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Autowired;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Executes chat against OpenAI-compatible API endpoints with streaming.
 * <p>
 * Supports any endpoint implementing the OpenAI chat completions API:
 * OpenAI, Ollama, vLLM, LM Studio, Azure OpenAI, etc.
 */
@Service
public class ApiAgentChatExecutor {

    private static final Logger log = LoggerFactory.getLogger(ApiAgentChatExecutor.class);

    private final ObjectMapper objectMapper = JsonUtils.standardMapper();
    private final ExecutorService executorService = new ThreadPoolExecutor(
            2, 16, 60L, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(200),
            r -> { Thread t = new Thread(r, "api-agent-chat"); t.setDaemon(true); return t; },
            new ThreadPoolExecutor.CallerRunsPolicy());
    @Autowired(required = false)
    private ModelCapabilityService modelCapabilityService;

    // Real context budget per lane (staging metadata for local models, catalogs otherwise);
    // used to clamp max_tokens so a request never asks for more generation than the window holds.
    @Autowired(required = false)
    private ChatContextBudgetService contextBudgetService;

    public ApiAgentChatExecutor(ModelCapabilityService modelCapabilityService) {
        this.modelCapabilityService = modelCapabilityService;
    }

    /** No-arg for Spring AOT / CGLIB proxy creation. */
    public ApiAgentChatExecutor() {
    }

    // Track active connections for cancellation
    private final Map<String, ActiveApiStream> activeConnections = new ConcurrentHashMap<>();

    /** Completion hooks used by the canonical provisioned-agent conversation projection. */
    public interface ExecutionObserver {
        ExecutionObserver NO_OP = new ExecutionObserver() { };

        default boolean onComplete(String processId, String content) { return true; }
        default boolean onError(String processId, String message) { return true; }
        default boolean onCancelled(String processId, String content) { return true; }
    }

    /**
     * Execute an API chat request with streaming.
     */
    public void executeApiChat(
            AgentProvider agent,
            AgentChatRequest request,
            String augmentedPrompt,
            List<RetrievedDoc> retrievedSources,
            SseEmitter emitter) {
        executeApiChat(agent, request, augmentedPrompt, retrievedSources, emitter,
                ExecutionObserver.NO_OP);
    }

    /** Execute with provider-neutral outcome observation; existing callers use the no-op overload. */
    public void executeApiChat(
            AgentProvider agent,
            AgentChatRequest request,
            String augmentedPrompt,
            List<RetrievedDoc> retrievedSources,
            SseEmitter emitter,
            ExecutionObserver observer) {

        String processId = UUID.randomUUID().toString();
        ExecutionObserver safeObserver = observer == null ? ExecutionObserver.NO_OP : observer;

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
                if (KompileLocalModelService.AGENT_NAME.equals(agent.getName())) {
                    // Model staging protects browser mutations with a non-simple request
                    // marker. The chat component is the trusted server-to-server client.
                    connection.setRequestProperty("X-Kompile-Staging-Request", "1");
                }

                // Add API key if present
                if (agent.getApiKey() != null && !agent.getApiKey().isEmpty()) {
                    connection.setRequestProperty("Authorization", "Bearer " + agent.getApiKey());
                }

                connection.setDoOutput(true);
                connection.setConnectTimeout(30_000);
                connection.setReadTimeout(0); // No read timeout for streaming

                // Track connection for cancellation
                activeConnections.put(processId, new ActiveApiStream(
                        connection, safeObserver, emitter));

                // Send request body
                try (OutputStream os = connection.getOutputStream()) {
                    os.write(requestBody.getBytes(StandardCharsets.UTF_8));
                }

                int responseCode = connection.getResponseCode();
                if (responseCode != 200) {
                    String errorBody = readErrorStream(connection);
                    log.error("API endpoint returned {}: {}", responseCode, errorBody);
                    String message = "API error (" + responseCode + "): " + errorBody;
                    if (notifyError(safeObserver, processId, message)) {
                        sendError(emitter, message);
                    }
                    return;
                }

                // Stream the response
                long startTime = System.currentTimeMillis();
                long streamedChunks = 0;
                ApiTokenUsage providerUsage = null;
                StringBuilder fullResponse = new StringBuilder();

                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {

                    String line;
                    while ((line = reader.readLine()) != null) {
                        // Check if cancelled
                        if (!activeConnections.containsKey(processId)) {
                            if (notifyCancelled(safeObserver, processId, fullResponse.toString())) {
                                sendEvent(emitter, "cancelled", Map.of(
                                        "processId", processId,
                                        "content", fullResponse.toString()));
                            }
                            return;
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
                                        streamedChunks++;
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
                                if (!usage.isMissingNode()) {
                                    providerUsage = parseOpenAiUsage(usage);
                                }
                            } catch (Exception e) {
                                log.debug("Failed to parse SSE chunk: {}", data, e);
                            }
                        }
                    }
                }

                long durationMs = System.currentTimeMillis() - startTime;
                long inputTokens = providerUsage == null ? 0L : providerUsage.inputTokens();
                long cacheReadTokens = providerUsage == null ? 0L : providerUsage.cacheReadTokens();
                long cacheCreationTokens = providerUsage == null
                        ? 0L : providerUsage.cacheCreationTokens();
                long outputTokens = providerUsage != null && providerUsage.outputReported()
                        ? providerUsage.outputTokens() : streamedChunks;
                double tokensPerSecond = durationMs > 0 ? (outputTokens * 1000.0 / durationMs) : 0;

                if (!activeConnections.containsKey(processId)) {
                    if (notifyCancelled(safeObserver, processId, fullResponse.toString())) {
                        sendEvent(emitter, "cancelled", Map.of(
                                "processId", processId,
                                "content", fullResponse.toString()));
                    }
                    return;
                }

                // Send stats
                Map<String, Object> stats = new HashMap<>();
                stats.put("durationMs", durationMs);
                stats.put("costUsd", 0.0);
                stats.put("numTurns", 1);
                stats.put("isError", false);

                Map<String, Object> tokenMetrics = new HashMap<>();
                tokenMetrics.put("inputTokens", inputTokens);
                tokenMetrics.put("outputTokens", outputTokens);
                tokenMetrics.put("cacheReadTokens", cacheReadTokens);
                tokenMetrics.put("cacheCreationTokens", cacheCreationTokens);
                tokenMetrics.put("contextInputTokens",
                        inputTokens + cacheReadTokens + cacheCreationTokens);
                tokenMetrics.put("totalTokens",
                        inputTokens + cacheReadTokens + cacheCreationTokens + outputTokens);
                tokenMetrics.put("totalGenerationMs", durationMs);
                tokenMetrics.put("tokensPerSecond", tokensPerSecond);
                tokenMetrics.put("model", agent.getModelName());
                stats.put("tokenMetrics", tokenMetrics);
                sendEvent(emitter, "stats", stats);

                // Send complete
                if (notifyComplete(safeObserver, processId, fullResponse.toString())) {
                    sendEvent(emitter, "complete", Map.of(
                            "processId", processId,
                            "content", fullResponse.toString(),
                            "modifiedFiles", Collections.emptyList()));
                }

            } catch (Exception e) {
                if (!activeConnections.containsKey(processId)) {
                    // Cancelled - don't send error
                    log.info("API chat cancelled for process {}", processId);
                    if (notifyCancelled(safeObserver, processId, "")) {
                        sendEvent(emitter, "cancelled", Map.of(
                                "processId", processId, "content", ""));
                    }
                } else {
                    log.error("Error executing API agent chat", e);
                    String message = "API execution error: " + e.getMessage();
                    if (notifyError(safeObserver, processId, message)) {
                        sendError(emitter, message);
                    }
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

    private static boolean notifyComplete(
            ExecutionObserver observer, String processId, String content) {
        try {
            return observer.onComplete(processId, content);
        } catch (RuntimeException failure) {
            log.error("API terminal completion observer failed for {}", processId, failure);
            return false;
        }
    }

    private static boolean notifyError(
            ExecutionObserver observer, String processId, String message) {
        try {
            return observer.onError(processId, message);
        } catch (RuntimeException failure) {
            log.error("API terminal error observer failed for {}", processId, failure);
            return false;
        }
    }

    private static boolean notifyCancelled(
            ExecutionObserver observer, String processId, String content) {
        try {
            return observer.onCancelled(processId, content);
        } catch (RuntimeException failure) {
            log.error("API terminal cancellation observer failed for {}", processId, failure);
            return false;
        }
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
        root.putObject("stream_options").put("include_usage", true);
        root.put("temperature", agent.getTemperature());
        root.put("max_tokens", resolveMaxTokens(agent, request, augmentedPrompt));

        ArrayNode messages = root.putArray("messages");

        String systemPrompt = request.getSystemPromptOverride();
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            messages.addObject().put("role", "system").put("content", systemPrompt);
        }

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

    static ApiTokenUsage parseOpenAiUsage(JsonNode usage) {
        JsonNode details = usage == null ? null : usage.path("prompt_tokens_details");
        long cacheRead = firstPresentLong(
                details, "cached_tokens",
                usage, "prompt_cache_hit_tokens",
                usage, "cached_tokens");
        long cacheWrite = details == null
                ? 0L : details.path("cache_write_tokens").asLong(0L);
        long input;
        if (usage != null && usage.has("prompt_cache_miss_tokens")) {
            input = usage.path("prompt_cache_miss_tokens").asLong(0L);
        } else {
            long totalInput = usage == null ? 0L
                    : usage.path("prompt_tokens").asLong(0L);
            input = Math.max(0L, totalInput - cacheRead - cacheWrite);
        }
        boolean outputReported = usage != null && usage.has("completion_tokens");
        long output = outputReported ? usage.path("completion_tokens").asLong(0L) : 0L;
        return new ApiTokenUsage(
                Math.max(0L, input), Math.max(0L, output),
                Math.max(0L, cacheRead), Math.max(0L, cacheWrite), outputReported);
    }

    private static long firstPresentLong(
            JsonNode first, String firstField,
            JsonNode second, String secondField,
            JsonNode third, String thirdField) {
        if (first != null && first.has(firstField)) return first.path(firstField).asLong(0L);
        if (second != null && second.has(secondField)) return second.path(secondField).asLong(0L);
        return third != null && third.has(thirdField)
                ? third.path(thirdField).asLong(0L) : 0L;
    }

    /**
     * Generation cap for this request. The agent's configured max_tokens is clamped to
     * the model's real output ceiling AND to the space left in the context window after
     * the prompt — an over-window max_tokens is a hard 400 on strict servers (llama.cpp,
     * the staging facade) and silent truncation on others. A kompile-local agent whose
     * staged model has a 4K window must not send its registered default of 4096.
     */
    private int resolveMaxTokens(AgentProvider agent, AgentChatRequest request, String augmentedPrompt) {
        int configured = agent.getMaxTokens() > 0 ? agent.getMaxTokens() : 4_096;
        if (contextBudgetService == null) return configured;
        try {
            ChatContextBudgetService.ContextBudget budget = contextBudgetService.resolve(agent);
            int promptTokens = ChatContextBudgetService.estimateHistoryTokens(request.getChatHistory())
                    + ChatContextBudgetService.estimateTokens(request.getSystemPromptOverride())
                    + ChatContextBudgetService.estimateTokens(augmentedPrompt);
            int remaining = budget.contextWindow() - promptTokens - 128;
            if (remaining < 128) {
                throw new IllegalStateException("Fixed system and input context exceeds model window "
                        + budget.contextWindow() + " for " + agent.getName());
            }
            int clamped = Math.min(Math.min(configured, budget.maxOutputTokens()), remaining);
            if (clamped != configured) {
                log.debug("Clamped max_tokens {} → {} for {} (window {} from {}, prompt ~{} tokens)",
                        configured, clamped, agent.getName(), budget.contextWindow(), budget.source(), promptTokens);
            }
            return clamped;
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            log.debug("max_tokens clamp skipped: {}", e.getMessage());
            return configured;
        }
    }

    /**
     * Non-streaming one-shot completion through the agent's endpoint. Used for utility
     * calls — history-compaction summaries — that must not stream into the chat SSE.
     */
    public String completeSync(AgentProvider agent, String systemPrompt, String userPrompt,
                               int timeoutSeconds) throws IOException {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("model", agent.getModelName());
        root.put("stream", false);
        root.put("temperature", 0.2);
        int maxTokens = 2_048;
        if (contextBudgetService != null) {
            try {
                maxTokens = Math.min(maxTokens, contextBudgetService.resolve(agent).maxOutputTokens());
            } catch (Exception ignored) {
                // fall through with the modest default
            }
        }
        root.put("max_tokens", Math.max(128, maxTokens));

        ArrayNode messages = root.putArray("messages");
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            messages.addObject().put("role", "system").put("content", systemPrompt);
        }
        messages.addObject().put("role", "user").put("content", userPrompt);

        String url = normalizeEndpointUrl(agent.getEndpointUrl()) + "/chat/completions";
        HttpURLConnection connection = (HttpURLConnection) URI.create(url).toURL().openConnection();
        try {
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Content-Type", "application/json");
            if (agent.getApiKey() != null && !agent.getApiKey().isEmpty()) {
                connection.setRequestProperty("Authorization", "Bearer " + agent.getApiKey());
            }
            connection.setConnectTimeout(10_000);
            connection.setReadTimeout(Math.max(30, timeoutSeconds) * 1_000);
            connection.setDoOutput(true);
            try (OutputStream out = connection.getOutputStream()) {
                out.write(root.toString().getBytes(StandardCharsets.UTF_8));
            }

            int status = connection.getResponseCode();
            if (status >= 400) {
                throw new IOException("Sync completion returned HTTP " + status + ": "
                        + readErrorStream(connection));
            }
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
                StringBuilder body = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    body.append(line);
                }
                JsonNode parsed = objectMapper.readTree(body.toString());
                return parsed.path("choices").path(0).path("message").path("content").asText("");
            }
        } finally {
            connection.disconnect();
        }
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
        ActiveApiStream active = activeConnections.remove(processId);
        if (active != null) {
            log.info("Cancelling API stream for process: {}", processId);
            if (notifyCancelled(active.observer(), processId, "")) {
                sendEvent(active.emitter(), "cancelled", Map.of(
                        "processId", processId, "content", ""));
            }
            active.connection().disconnect();
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

    private record ActiveApiStream(
            HttpURLConnection connection,
            ExecutionObserver observer,
            SseEmitter emitter) {
    }

    record ApiTokenUsage(
            long inputTokens,
            long outputTokens,
            long cacheReadTokens,
            long cacheCreationTokens,
            boolean outputReported) {
    }

    /**
     * Test connectivity to an API endpoint.
     */
    public Map<String, Object> testEndpoint(String endpointUrl, String apiKey) {
        Map<String, Object> result = new HashMap<>();
        HttpURLConnection connection = null;
        try {
            String url = normalizeEndpointUrl(endpointUrl) + "/models";
            connection = (HttpURLConnection) URI.create(url).toURL().openConnection();
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
        } catch (Exception e) {
            result.put("reachable", false);
            result.put("error", e.getMessage());
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
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

            // Source name extracted from metadata (parity with AgentChatService)
            String sourceName = extractSourceName(doc);
            source.put("sourceName", sourceName);

            String content = doc.getText();
            source.put("preview", content.length() > 300 ? content.substring(0, 300) + "..." : content);
            source.put("content", content.length() > 2000 ? content.substring(0, 2000) + "... [truncated]" : content);

            // Safe metadata (string/number/boolean values only)
            Map<String, Object> safeMetadata = new HashMap<>();
            if (doc.getMetadata() != null) {
                for (Map.Entry<String, Object> entry : doc.getMetadata().entrySet()) {
                    if (entry.getValue() instanceof String
                            || entry.getValue() instanceof Number
                            || entry.getValue() instanceof Boolean) {
                        safeMetadata.put(entry.getKey(), entry.getValue());
                    }
                }
                source.put("metadata", safeMetadata);
            }

            // Uniform citation object
            CitationDto citation = CitationSupport.from(doc.getMetadata(), doc.getScore(), null);
            source.put("citation", citation);

            // Graph linkage: nodeId for "View in knowledge graph", documentId for document navigation
            String nodeId = extractMetadataString(doc.getMetadata(), "node_id", "nodeId", "externalId");
            if (nodeId == null && doc.getId() != null && !doc.getId().startsWith("doc-")) {
                nodeId = doc.getId();
            }
            if (nodeId != null) {
                source.put("nodeId", nodeId);
            }
            String documentId = extractMetadataString(doc.getMetadata(),
                    SourceMetadataConstants.SOURCE_ID,          // "source_id"
                    GraphProvenanceKeys.SOURCE_DOCUMENT_ID);    // "_sourceDocumentId"
            if (documentId == null && citation != null && citation.sourceId() != null) {
                documentId = citation.sourceId();
            }
            if (documentId != null) {
                source.put("documentId", documentId);
            }

            sources.add(source);
            index++;
        }
        return sources;
    }

    /**
     * Return the first non-blank string value found in {@code metadata} under any of
     * the supplied keys, or {@code null} if none match.
     */
    private String extractMetadataString(Map<String, Object> metadata, String... keys) {
        if (metadata == null) return null;
        for (String key : keys) {
            Object v = metadata.get(key);
            if (v instanceof String s && !s.isBlank()) {
                return s;
            }
        }
        return null;
    }

    /**
     * Extract a readable source name from document metadata.
     */
    private String extractSourceName(RetrievedDoc doc) {
        if (doc.getMetadata() != null) {
            String[] nameKeys = {"source", "file_name", "fileName", "title", "name", "path"};
            for (String key : nameKeys) {
                Object value = doc.getMetadata().get(key);
                if (value instanceof String name && !name.isEmpty()) {
                    if (name.contains("/") || name.contains("\\")) {
                        return name.substring(Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\')) + 1);
                    }
                    return name;
                }
            }
        }
        if (doc.getId() != null && !doc.getId().isEmpty()) {
            String id = doc.getId();
            if (id.contains("/") || id.contains("\\")) {
                return id.substring(Math.max(id.lastIndexOf('/'), id.lastIndexOf('\\')) + 1);
            }
            return id.length() > 30 ? id.substring(0, 30) + "..." : id;
        }
        return "Document";
    }

    @PreDestroy
    public void shutdown() {
        executorService.shutdown();
        try {
            if (!executorService.awaitTermination(5, TimeUnit.SECONDS)) {
                executorService.shutdownNow();
            }
        } catch (InterruptedException e) {
            executorService.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
