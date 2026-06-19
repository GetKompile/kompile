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

package ai.kompile.app.web.controllers;

import ai.kompile.core.embeddings.ScoredDocument;
import ai.kompile.utils.StringUtils;
import ai.kompile.core.rag.ConversationalRagOptions;
import ai.kompile.core.rag.ConversationalRagResult;
import ai.kompile.core.rag.ConversationalRagService;
import ai.kompile.kvcache.model.KVCacheStats;
import ai.kompile.kvcache.service.KVCacheManager;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.Message;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * REST controller for conversational RAG operations.
 * <p>
 * Provides endpoints for multi-turn conversations with RAG-enhanced responses.
 *
 * <h2>Endpoints</h2>
 * <ul>
 *   <li>POST /api/chat - Send a message and get RAG-enhanced response</li>
 *   <li>POST /api/chat/stream - Stream RAG-enhanced response</li>
 *   <li>GET /api/chat/{conversationId}/history - Get conversation history</li>
 *   <li>DELETE /api/chat/{conversationId} - Clear conversation</li>
 *   <li>GET /api/chat/status - Check service status</li>
 * </ul>
 */
@Slf4j
@RestController
@RequestMapping("/api/chat")
public class ConversationalRagController {

    private final ConversationalRagService ragService;

    @Autowired(required = false)
    private KVCacheManager kvCacheManager;

    @Autowired
    public ConversationalRagController(
            @Qualifier("kompileRagOrchestrator") ConversationalRagService ragService) {
        this.ragService = ragService;
        log.info("ConversationalRagController initialized with: {}",
                ragService != null ? ragService.getClass().getSimpleName() : "null");
    }

    /**
     * Sends a message and receives a RAG-enhanced response.
     */
    @PostMapping
    public ResponseEntity<ChatResponse> chat(@RequestBody ChatRequest request) {
        if (request == null || request.message() == null || request.message().isBlank()) {
            log.warn("Received chat request with empty message");
            return ResponseEntity.badRequest()
                    .body(ChatResponse.error("Message cannot be empty"));
        }

        String conversationId = request.conversationId();
        if (conversationId == null || conversationId.isBlank()) {
            conversationId = UUID.randomUUID().toString();
        }

        try {
            log.info("Processing chat request for conversation '{}': '{}'",
                    sanitizeForLog(conversationId), StringUtils.truncate(request.message(), 100));

            ConversationalRagOptions options = buildOptions(request);
            ConversationalRagResult result = ragService.chat(conversationId, request.message(), options);

            if (result.isError()) {
                log.warn("RAG service returned error for conversation '{}': {}",
                        conversationId, result.answer());
                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                        .body(ChatResponse.fromResult(conversationId, result));
            }

            log.info("Chat completed for conversation '{}': {} docs, {}ms",
                    sanitizeForLog(conversationId), result.documentCount(), result.totalTimeMs());

            KVCacheStats cacheStats = null;
            if (kvCacheManager != null) {
                try {
                    cacheStats = kvCacheManager.getAggregateStats();
                } catch (Exception ex) {
                    log.trace("Could not collect KV cache stats: {}", ex.getMessage());
                }
            }

            return ResponseEntity.ok(ChatResponse.fromResult(conversationId, result, cacheStats));

        } catch (Exception e) {
            log.error("Error processing chat for conversation '{}': {}",
                    sanitizeForLog(conversationId), e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(ChatResponse.error("Failed to process message: " + e.getMessage()));
        }
    }

    /**
     * Streams a RAG-enhanced response using Server-Sent Events.
     */
    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> chatStream(@RequestBody ChatRequest request) {
        if (request == null || request.message() == null || request.message().isBlank()) {
            return Flux.just("data: {\"error\": \"Message cannot be empty\"}\n\n");
        }

        String conversationId = request.conversationId();
        if (conversationId == null || conversationId.isBlank()) {
            conversationId = UUID.randomUUID().toString();
        }

        final String finalConversationId = conversationId;
        ConversationalRagOptions options = buildOptions(request);

        return ragService.chatStream(finalConversationId, request.message(), options)
                .map(chunk -> "data: " + escapeForSse(chunk) + "\n\n")
                .concatWith(Flux.just("data: [DONE]\n\n"))
                .timeout(Duration.ofMinutes(5))
                .onErrorResume(e -> {
                    log.error("Error in streaming chat", e);
                    return Flux.just("data: {\"error\": \"" + escapeForSse(e.getMessage()) + "\"}\n\n");
                });
    }

    /**
     * Gets conversation history.
     */
    @GetMapping("/{conversationId}/history")
    public ResponseEntity<ConversationHistoryResponse> getHistory(@PathVariable String conversationId) {
        if (conversationId == null || conversationId.isBlank()) {
            return ResponseEntity.badRequest()
                    .body(new ConversationHistoryResponse(null, List.of(), "Invalid conversation ID"));
        }

        try {
            List<Message> history = ragService.getConversationHistory(conversationId);
            List<MessageDto> messageDtos = history.stream()
                    .map(this::toMessageDto)
                    .toList();

            return ResponseEntity.ok(new ConversationHistoryResponse(conversationId, messageDtos, null));

        } catch (Exception e) {
            log.error("Error getting history for conversation '{}'", sanitizeForLog(conversationId), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(new ConversationHistoryResponse(conversationId, List.of(), e.getMessage()));
        }
    }

    /**
     * Clears conversation history.
     */
    @DeleteMapping("/{conversationId}")
    public ResponseEntity<Map<String, Object>> clearConversation(@PathVariable String conversationId) {
        if (conversationId == null || conversationId.isBlank()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Invalid conversation ID"));
        }

        try {
            ragService.clearConversation(conversationId);
            log.info("Cleared conversation: {}", sanitizeForLog(conversationId));
            return ResponseEntity.ok(Map.of(
                    "conversationId", conversationId,
                    "cleared", true
            ));

        } catch (Exception e) {
            log.error("Error clearing conversation '{}'", sanitizeForLog(conversationId), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Checks service status.
     */
    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> getStatus() {
        boolean available = ragService != null && ragService.isAvailable();
        return ResponseEntity.ok(Map.of(
                "available", available,
                "service", ragService != null ? ragService.getClass().getSimpleName() : "null"
        ));
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // HELPER METHODS
    // ═══════════════════════════════════════════════════════════════════════════════

    private ConversationalRagOptions buildOptions(ChatRequest request) {
        ConversationalRagOptions defaults = ConversationalRagOptions.defaults();

        if (request.options() == null) {
            return defaults;
        }

        ChatOptions opts = request.options();
        ConversationalRagOptions result = defaults;

        if (opts.semanticK() != null) {
            result = result.withSemanticK(opts.semanticK());
        }
        if (opts.keywordK() != null) {
            result = result.withKeywordK(opts.keywordK());
        }
        if (opts.similarityThreshold() != null) {
            result = result.withSimilarityThreshold(opts.similarityThreshold());
        }
        if (opts.maxHistoryMessages() != null) {
            result = result.withMaxHistoryMessages(opts.maxHistoryMessages());
        }
        if (opts.maxContextTokens() != null) {
            result = result.withMaxContextTokens(opts.maxContextTokens());
        }
        if (opts.useToolCalling() != null) {
            result = result.withToolCalling(opts.useToolCalling());
        }
        if (opts.systemPrompt() != null) {
            result = result.withSystemPrompt(opts.systemPrompt());
        }
        if (opts.enableQueryProcessing() != null) {
            result = result.withQueryProcessing(opts.enableQueryProcessing());
        }

        return result;
    }

    private MessageDto toMessageDto(Message message) {
        String role = message.getMessageType().name().toLowerCase();
        String content = message.getText();
        return new MessageDto(role, content);
    }

    private String escapeForSse(String text) {
        if (text == null) return "";
        return text.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r");
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // REQUEST/RESPONSE DTOs
    // ═══════════════════════════════════════════════════════════════════════════════

    /**
     * Chat request DTO.
     */
    public record ChatRequest(
            String conversationId,
            String message,
            ChatOptions options
    ) {}

    /**
     * Chat options DTO.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ChatOptions(
            Integer semanticK,
            Integer keywordK,
            Double similarityThreshold,
            Integer maxHistoryMessages,
            Integer maxContextTokens,
            Boolean useToolCalling,
            String systemPrompt,
            Boolean enableQueryProcessing
    ) {}

    /**
     * Chat response DTO.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ChatResponse(
            String conversationId,
            String answer,
            List<DocumentDto> documents,
            QueryMetadata queryMetadata,
            PerformanceMetrics metrics,
            KVCacheStats kvCacheStats,
            String error
    ) {
        public static ChatResponse fromResult(String conversationId, ConversationalRagResult result) {
            return fromResult(conversationId, result, null);
        }

        public static ChatResponse fromResult(String conversationId, ConversationalRagResult result, KVCacheStats kvCacheStats) {
            List<DocumentDto> docs = result.retrievedDocuments().stream()
                    .map(sd -> new DocumentDto(sd.getId(), sd.getText(), sd.score(), sd.getMetadata()))
                    .toList();

            QueryMetadata queryMeta = null;
            if (result.processedQuery() != null) {
                queryMeta = new QueryMetadata(
                        result.processedQuery().originalQuery(),
                        result.processedQuery().rewrittenQuery(),
                        result.processedQuery().wasRewritten(),
                        result.processedQuery().intent() != null ?
                                result.processedQuery().intent().name() : null
                );
            }

            PerformanceMetrics perf = new PerformanceMetrics(
                    result.totalTimeMs(),
                    result.generationTimeMs(),
                    result.retrievalMetrics() != null ?
                            result.retrievalMetrics().totalTimeNanos() / 1_000_000 : 0,
                    result.documentCount()
            );

            return new ChatResponse(
                    conversationId,
                    result.answer(),
                    docs,
                    queryMeta,
                    perf,
                    kvCacheStats,
                    result.isError() ? result.answer() : null
            );
        }

        public static ChatResponse error(String error) {
            return new ChatResponse(null, null, null, null, null, null, error);
        }
    }

    /**
     * Document DTO.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record DocumentDto(
            String id,
            String content,
            double score,
            Map<String, Object> metadata
    ) {}

    /**
     * Query metadata DTO.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record QueryMetadata(
            String originalQuery,
            String rewrittenQuery,
            boolean wasRewritten,
            String intent
    ) {}

    /**
     * Performance metrics DTO.
     */
    public record PerformanceMetrics(
            long totalMs,
            long generationMs,
            long retrievalMs,
            int documentsRetrieved
    ) {}

    /**
     * Message DTO.
     */
    public record MessageDto(
            String role,
            String content
    ) {}

    /**
     * Conversation history response DTO.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ConversationHistoryResponse(
            String conversationId,
            List<MessageDto> messages,
            String error
    ) {}

    private static String sanitizeForLog(String value) {
        if (value == null) return null;
        return value.replace('\n', ' ').replace('\r', ' ');
    }
}
