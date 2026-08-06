package ai.kompile.staging.web;

import ai.kompile.staging.execution.ChatTemplateService;
import ai.kompile.staging.execution.LlmExecutionService;
import ai.kompile.staging.web.dto.*;
import ai.kompile.staging.web.dto.openai.*;
import ai.kompile.cli.common.KompileHome;
import ai.kompile.cli.common.routing.ServiceEndpointsConfigManager;
import ai.kompile.cli.common.util.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.io.PrintWriter;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * OpenAI-compatible API controller for the staging module.
 * Exposes /v1/models and /v1/chat/completions so that the loaded
 * kompile model can be used as a standard API agent via ApiAgentChatExecutor.
 */
@ConditionalOnClass(name = "ai.kompile.staging.catalog.CatalogService")
@ConditionalOnProperty(name = "kompile.staging.app.enabled", havingValue = "true")
@RestController
@RequestMapping("/v1")
public class OpenAiCompatController {

    private static final Logger log = LoggerFactory.getLogger(OpenAiCompatController.class);
    private static final int SERVING_CONNECT_TIMEOUT_MS = 5_000;
    private static final int SERVING_READ_TIMEOUT_MS = 600_000;
    private final ObjectMapper objectMapper = JsonUtils.standardMapper();

    private final LlmExecutionService executionService;
    private final ChatTemplateService chatTemplateService;
    private final ServiceEndpointsConfigManager endpointConfigManager;

    @Autowired
    public OpenAiCompatController(LlmExecutionService executionService,
                                  ChatTemplateService chatTemplateService) {
        this(executionService, chatTemplateService,
                ServiceEndpointsConfigManager.forProjectDirectory(
                        KompileHome.resolvedProjectDirectory().toPath()));
    }

    OpenAiCompatController(LlmExecutionService executionService,
                           ChatTemplateService chatTemplateService,
                           ServiceEndpointsConfigManager endpointConfigManager) {
        this.executionService = executionService;
        this.chatTemplateService = chatTemplateService;
        this.endpointConfigManager = endpointConfigManager;
    }

    /**
     * List loaded models (OpenAI GET /v1/models compatible).
     */
    @GetMapping("/models")
    public ResponseEntity<OpenAiModelsResponse> listModels() {
        List<OpenAiModelInfo> models = new ArrayList<>();
        availableModel().ifPresent(available ->
            models.add(OpenAiModelInfo.builder()
                    .id(available.modelId())
                    .created(System.currentTimeMillis() / 1000)
                    .ownedBy("kompile-local")
                    .build()));

        return ResponseEntity.ok(OpenAiModelsResponse.builder()
                .data(models)
                .build());
    }

    /**
     * Chat completions endpoint (OpenAI POST /v1/chat/completions compatible).
     * Supports both streaming (SSE) and non-streaming modes.
     */
    @PostMapping("/chat/completions")
    public void chatCompletions(@RequestBody OpenAiChatCompletionRequest request,
                                HttpServletResponse response) {
        try {
            LlmModelStatusResponse status = executionService.getStatus();
            if (status.isLoaded() && status.getModelId() != null) {
                ChatRequest chatRequest = mapToChatRequest(request, status.getModelId());
                if (request.isStream()) {
                    handleStreaming(request, chatRequest, status.getModelId(), response);
                } else {
                    handleNonStreaming(request, chatRequest, status.getModelId(), response);
                }
                return;
            }

            Optional<ServingStatus> serving = servingStatus();
            if (serving.isEmpty() || !serving.get().loaded() || serving.get().modelId() == null) {
                response.setStatus(503);
                response.setContentType("application/json");
                response.getWriter().write("{\"error\":{\"message\":\"No model loaded\",\"type\":\"server_error\"}}");
                return;
            }

            ServingGeneration generation = generateThroughServing(request);
            if (request.isStream()) {
                handleBufferedServingStream(serving.get().modelId(), generation, response);
            } else {
                handleServingNonStreaming(serving.get().modelId(), generation, response);
            }
        } catch (Exception e) {
            log.error("Chat completions error", e);
            try {
                response.setStatus(500);
                response.setContentType("application/json");
                String message = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                response.getWriter().write("{\"error\":{\"message\":\"" +
                        message.replace("\"", "\\\"") + "\",\"type\":\"server_error\"}}");
            } catch (Exception writeEx) {
                log.debug("Could not write error response to client (connection likely closed): {}", writeEx.getMessage());
            }
        }
    }

    private Optional<AvailableModel> availableModel() {
        LlmModelStatusResponse local = executionService.getStatus();
        if (local.isLoaded() && local.getModelId() != null) {
            return Optional.of(new AvailableModel(local.getModelId()));
        }
        return servingStatus()
                .filter(ServingStatus::loaded)
                .map(ServingStatus::modelId)
                .filter(Objects::nonNull)
                .map(AvailableModel::new);
    }

    private Optional<ServingStatus> servingStatus() {
        HttpURLConnection connection = null;
        String servingUrl = endpointConfigManager.current().effectiveServingUrl();
        try {
            connection = openServingConnection(servingUrl + "/api/llm/status", "GET");
            int responseCode = connection.getResponseCode();
            if (responseCode != 200) {
                log.debug("Managed serving status returned HTTP {} from {}", responseCode, servingUrl);
                return Optional.empty();
            }
            JsonNode root = objectMapper.readTree(connection.getInputStream());
            return Optional.of(new ServingStatus(
                    root.path("loaded").asBoolean(false),
                    root.path("modelId").isTextual() ? root.path("modelId").asText() : null));
        } catch (Exception e) {
            log.debug("Managed serving endpoint {} is unavailable: {}", servingUrl, e.getMessage());
            return Optional.empty();
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private ServingGeneration generateThroughServing(OpenAiChatCompletionRequest request) throws IOException {
        String servingUrl = endpointConfigManager.current().effectiveServingUrl();
        HttpURLConnection connection = openServingConnection(servingUrl + "/api/llm/generate", "POST");
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "application/json");

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("prompt", formatMessagesForServing(request.getMessages()));
        if (request.getMaxTokens() > 0) {
            payload.put("maxTokens", request.getMaxTokens());
        }
        payload.put("temperature", request.getTemperature());

        try {
            byte[] body = objectMapper.writeValueAsBytes(payload);
            connection.setFixedLengthStreamingMode(body.length);
            connection.getOutputStream().write(body);

            int responseCode = connection.getResponseCode();
            byte[] responseBody = (responseCode >= 200 && responseCode < 300
                    ? connection.getInputStream()
                    : connection.getErrorStream()).readAllBytes();
            if (responseCode < 200 || responseCode >= 300) {
                throw new IOException("Managed serving returned HTTP " + responseCode + ": "
                        + new String(responseBody, StandardCharsets.UTF_8));
            }

            JsonNode root = objectMapper.readTree(responseBody);
            String finishReason = root.path("finishReason").asText("completed");
            if (finishReason.toLowerCase(Locale.ROOT).startsWith("error")) {
                throw new IOException(finishReason);
            }
            return new ServingGeneration(
                    root.path("generatedText").asText(""),
                    finishReason,
                    root.path("totalTimeMs").asLong(0),
                    root.path("totalTokens").asInt(0));
        } finally {
            connection.disconnect();
        }
    }

    private HttpURLConnection openServingConnection(String url, String method) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) URI.create(url).toURL().openConnection();
        connection.setRequestMethod(method);
        connection.setConnectTimeout(SERVING_CONNECT_TIMEOUT_MS);
        connection.setReadTimeout(SERVING_READ_TIMEOUT_MS);
        return connection;
    }

    String formatMessagesForServing(List<OpenAiMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            throw new IllegalArgumentException("messages must not be empty");
        }
        List<ChatMessage> chatMessages = messages.stream()
                .filter(Objects::nonNull)
                .filter(message -> message.getContent() != null && !message.getContent().isBlank())
                .map(message -> ChatMessage.builder()
                        .role(message.getRole() == null || message.getRole().isBlank()
                                ? "user" : message.getRole().trim().toLowerCase(Locale.ROOT))
                        .content(message.getContent())
                        .build())
                .toList();
        if (chatMessages.isEmpty()) {
            throw new IllegalArgumentException("messages must contain text content");
        }
        return chatTemplateService.format(chatMessages, "chatml");
    }

    private void handleServingNonStreaming(String modelId,
                                           ServingGeneration generation,
                                           HttpServletResponse response) throws IOException {
        OpenAiChatCompletionResponse openAiResponse = OpenAiChatCompletionResponse.builder()
                .id(completionId())
                .object("chat.completion")
                .created(System.currentTimeMillis() / 1000)
                .model(modelId)
                .choices(List.of(OpenAiChoice.builder()
                        .index(0)
                        .message(OpenAiMessage.builder()
                                .role("assistant")
                                .content(generation.generatedText())
                                .build())
                        .finishReason(mapFinishReason(generation.finishReason()))
                        .build()))
                .usage(OpenAiUsage.builder()
                        .promptTokens(0)
                        .completionTokens(generation.totalTokens())
                        .totalTokens(generation.totalTokens())
                        .build())
                .build();
        response.setContentType("application/json");
        response.getWriter().write(objectMapper.writeValueAsString(openAiResponse));
    }

    private void handleBufferedServingStream(String modelId,
                                             ServingGeneration generation,
                                             HttpServletResponse response) throws IOException {
        response.setContentType("text/event-stream");
        response.setCharacterEncoding("UTF-8");
        response.setHeader("Cache-Control", "no-cache");
        response.setHeader("Connection", "keep-alive");
        response.setHeader("X-Accel-Buffering", "no");

        PrintWriter writer = response.getWriter();
        String id = completionId();
        writer.write("data: " + objectMapper.writeValueAsString(
                buildChunkJson(id, modelId, Map.of("role", "assistant"), null)) + "\n\n");
        if (!generation.generatedText().isEmpty()) {
            writer.write("data: " + objectMapper.writeValueAsString(
                    buildChunkJson(id, modelId, Map.of("content", generation.generatedText()), null)) + "\n\n");
        }
        Map<String, Object> finalChunk = buildChunkJson(
                id, modelId, Collections.emptyMap(), mapFinishReason(generation.finishReason()));
        finalChunk.put("usage", Map.of(
                "prompt_tokens", 0,
                "completion_tokens", generation.totalTokens(),
                "total_tokens", generation.totalTokens()));
        writer.write("data: " + objectMapper.writeValueAsString(finalChunk) + "\n\n");
        writer.write("data: [DONE]\n\n");
        writer.flush();
    }

    private String completionId() {
        return "chatcmpl-" + UUID.randomUUID().toString().replace("-", "").substring(0, 24);
    }

    private record AvailableModel(String modelId) {}

    private record ServingStatus(boolean loaded, String modelId) {}

    private record ServingGeneration(String generatedText,
                                     String finishReason,
                                     long totalTimeMs,
                                     int totalTokens) {}

    private ChatRequest mapToChatRequest(OpenAiChatCompletionRequest request, String modelId) {
        List<ChatMessage> messages = new ArrayList<>();
        if (request.getMessages() != null) {
            for (OpenAiMessage msg : request.getMessages()) {
                messages.add(ChatMessage.builder()
                        .role(msg.getRole())
                        .content(msg.getContent())
                        .build());
            }
        }

        return ChatRequest.builder()
                .messages(messages)
                .maxTokens(request.getMaxTokens() > 0 ? request.getMaxTokens() : 256)
                .temperature(request.getTemperature())
                .topP(request.getTopP())
                .stopSequences(request.getStop())
                .doSample(request.getTemperature() > 0)
                .build();
    }

    private void handleNonStreaming(OpenAiChatCompletionRequest request,
                                    ChatRequest chatRequest,
                                    String modelId,
                                    HttpServletResponse httpResponse) throws Exception {
        ChatResponse chatResponse = executionService.chat(chatRequest, chatTemplateService);
        String completionId = "chatcmpl-" + UUID.randomUUID().toString().replace("-", "").substring(0, 24);

        OpenAiChatCompletionResponse openAiResponse = OpenAiChatCompletionResponse.builder()
                .id(completionId)
                .object("chat.completion")
                .created(System.currentTimeMillis() / 1000)
                .model(modelId)
                .choices(List.of(OpenAiChoice.builder()
                        .index(0)
                        .message(OpenAiMessage.builder()
                                .role("assistant")
                                .content(chatResponse.getAssistantMessage())
                                .build())
                        .finishReason(mapFinishReason(chatResponse.getFinishReason()))
                        .build()))
                .usage(OpenAiUsage.builder()
                        .promptTokens(0)
                        .completionTokens(chatResponse.getTotalTokens())
                        .totalTokens(chatResponse.getTotalTokens())
                        .build())
                .build();

        httpResponse.setContentType("application/json");
        httpResponse.getWriter().write(objectMapper.writeValueAsString(openAiResponse));
    }

    private void handleStreaming(OpenAiChatCompletionRequest request,
                                 ChatRequest chatRequest,
                                 String modelId,
                                 HttpServletResponse httpResponse) throws Exception {
        httpResponse.setContentType("text/event-stream");
        httpResponse.setCharacterEncoding("UTF-8");
        httpResponse.setHeader("Cache-Control", "no-cache");
        httpResponse.setHeader("Connection", "keep-alive");
        httpResponse.setHeader("X-Accel-Buffering", "no");

        PrintWriter writer = httpResponse.getWriter();
        String completionId = "chatcmpl-" + UUID.randomUUID().toString().replace("-", "").substring(0, 24);

        AtomicInteger tokenCount = new AtomicInteger(0);

        // Send initial role chunk
        Map<String, Object> roleChunk = buildChunkJson(completionId, modelId,
                Map.of("role", "assistant"), null);
        writer.write("data: " + objectMapper.writeValueAsString(roleChunk) + "\n\n");
        writer.flush();

        ChatResponse chatResponse = executionService.chatStreaming(chatRequest, chatTemplateService, token -> {
            try {
                tokenCount.incrementAndGet();
                Map<String, Object> chunk = buildChunkJson(completionId, modelId,
                        Map.of("content", token), null);
                writer.write("data: " + objectMapper.writeValueAsString(chunk) + "\n\n");
                writer.flush();
            } catch (Exception e) {
                log.warn("Failed to write SSE chunk", e);
            }
        });

        // Send final chunk with finish_reason and usage
        Map<String, Object> finalChunk = buildChunkJson(completionId, modelId,
                Collections.emptyMap(), "stop");

        // Add usage to final chunk
        Map<String, Object> usage = new LinkedHashMap<>();
        usage.put("prompt_tokens", 0);
        usage.put("completion_tokens", tokenCount.get());
        usage.put("total_tokens", tokenCount.get());
        finalChunk.put("usage", usage);

        writer.write("data: " + objectMapper.writeValueAsString(finalChunk) + "\n\n");
        writer.write("data: [DONE]\n\n");
        writer.flush();
    }

    private Map<String, Object> buildChunkJson(String id, String model,
                                                Map<String, Object> deltaFields,
                                                String finishReason) {
        Map<String, Object> chunk = new LinkedHashMap<>();
        chunk.put("id", id);
        chunk.put("object", "chat.completion.chunk");
        chunk.put("created", System.currentTimeMillis() / 1000);
        chunk.put("model", model);

        Map<String, Object> delta = new LinkedHashMap<>(deltaFields);

        Map<String, Object> choice = new LinkedHashMap<>();
        choice.put("index", 0);
        choice.put("delta", delta);
        choice.put("finish_reason", finishReason);

        chunk.put("choices", List.of(choice));
        return chunk;
    }

    private String mapFinishReason(String internalReason) {
        if (internalReason == null) return "stop";
        if (internalReason.startsWith("error")) return "stop";
        if (internalReason.contains("length") || internalReason.contains("max_tokens")) return "length";
        if (internalReason.contains("stop")) return "stop";
        return "stop";
    }
}
