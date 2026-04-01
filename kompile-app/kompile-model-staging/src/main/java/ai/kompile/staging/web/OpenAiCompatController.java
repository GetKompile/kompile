package ai.kompile.staging.web;

import ai.kompile.staging.execution.ChatTemplateService;
import ai.kompile.staging.execution.LlmExecutionService;
import ai.kompile.staging.web.dto.*;
import ai.kompile.staging.web.dto.openai.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.PrintWriter;
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
@CrossOrigin(origins = "*")
public class OpenAiCompatController {

    private static final Logger log = LoggerFactory.getLogger(OpenAiCompatController.class);
    private final ObjectMapper objectMapper = new ObjectMapper();

    private final LlmExecutionService executionService;
    private final ChatTemplateService chatTemplateService;

    public OpenAiCompatController(LlmExecutionService executionService,
                                  ChatTemplateService chatTemplateService) {
        this.executionService = executionService;
        this.chatTemplateService = chatTemplateService;
    }

    /**
     * List loaded models (OpenAI GET /v1/models compatible).
     */
    @GetMapping("/models")
    public ResponseEntity<OpenAiModelsResponse> listModels() {
        LlmModelStatusResponse status = executionService.getStatus();
        List<OpenAiModelInfo> models = new ArrayList<>();

        if (status.isLoaded() && status.getModelId() != null) {
            models.add(OpenAiModelInfo.builder()
                    .id(status.getModelId())
                    .created(System.currentTimeMillis() / 1000)
                    .ownedBy("kompile-local")
                    .build());
        }

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
            if (!status.isLoaded()) {
                response.setStatus(503);
                response.setContentType("application/json");
                response.getWriter().write("{\"error\":{\"message\":\"No model loaded\",\"type\":\"server_error\"}}");
                return;
            }

            // Map OpenAI messages to internal ChatMessage format
            ChatRequest chatRequest = mapToChatRequest(request, status.getModelId());

            if (request.isStream()) {
                handleStreaming(request, chatRequest, status.getModelId(), response);
            } else {
                handleNonStreaming(request, chatRequest, status.getModelId(), response);
            }
        } catch (Exception e) {
            log.error("Chat completions error", e);
            try {
                response.setStatus(500);
                response.setContentType("application/json");
                response.getWriter().write("{\"error\":{\"message\":\"" +
                        e.getMessage().replace("\"", "\\\"") + "\",\"type\":\"server_error\"}}");
            } catch (Exception ignored) {}
        }
    }

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
