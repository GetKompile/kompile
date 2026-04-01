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

package ai.kompile.core.filter;

import ai.kompile.core.embeddings.ScoredDocument;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.ai.chat.messages.Message;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The context passed through the filter chain.
 * Contains all request/response data that filters can inspect and mutate.
 * <p>
 * Filters can modify fields to transform the request/response, and these
 * mutations are tracked for debugging and observability.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FilterContext {

    // ─────────────────────────────────────────────────────────────────────────────
    // REQUEST IDENTIFICATION
    // ─────────────────────────────────────────────────────────────────────────────

    /**
     * Unique ID for this request, used for tracing.
     */
    @Builder.Default
    private String requestId = UUID.randomUUID().toString();

    /**
     * Conversation ID (if part of a conversation).
     */
    private String conversationId;

    /**
     * Session ID for user session tracking.
     */
    private String sessionId;

    /**
     * User ID if authenticated.
     */
    private String userId;

    /**
     * When the request was initiated.
     */
    @Builder.Default
    private Instant requestTimestamp = Instant.now();

    // ─────────────────────────────────────────────────────────────────────────────
    // QUERY / INPUT FIELDS
    // ─────────────────────────────────────────────────────────────────────────────

    /**
     * The original user message/query as received.
     */
    private String userMessage;

    /**
     * The original query (may be same as userMessage).
     */
    private String originalQuery;

    /**
     * The rewritten/transformed query (set by filters).
     * If set, this should be used for retrieval instead of originalQuery.
     */
    private String rewrittenQuery;

    /**
     * The conversation history for context.
     */
    @Builder.Default
    private List<Message> conversationHistory = new ArrayList<>();

    /**
     * Additional request metadata.
     */
    @Builder.Default
    private Map<String, Object> requestMetadata = new HashMap<>();

    // ─────────────────────────────────────────────────────────────────────────────
    // RETRIEVAL FIELDS
    // ─────────────────────────────────────────────────────────────────────────────

    /**
     * Documents retrieved from the vector store.
     * Filters can modify this list (rerank, filter, enrich).
     */
    @Builder.Default
    private List<ScoredDocument> retrievedDocuments = new ArrayList<>();

    /**
     * The formatted context string built from retrieved documents.
     */
    private String formattedContext;

    /**
     * Maximum number of documents to retrieve.
     */
    @Builder.Default
    private int maxResults = 10;

    /**
     * Similarity threshold for retrieval.
     */
    @Builder.Default
    private double similarityThreshold = 0.0;

    // ─────────────────────────────────────────────────────────────────────────────
    // LLM FIELDS
    // ─────────────────────────────────────────────────────────────────────────────

    /**
     * The system prompt to use.
     */
    private String systemPrompt;

    /**
     * The LLM response text.
     */
    private String llmResponse;

    /**
     * The model used for generation.
     */
    private String modelId;

    /**
     * Generation time in milliseconds.
     */
    private Long generationTimeMs;

    // ─────────────────────────────────────────────────────────────────────────────
    // MUTATION TRACKING
    // ─────────────────────────────────────────────────────────────────────────────

    /**
     * Mutations made by filters, for debugging/auditing.
     */
    @Builder.Default
    private List<FilterMutation> mutations = new ArrayList<>();

    /**
     * Trace entries emitted by filters.
     */
    @Builder.Default
    private List<FilterTraceEntry> traces = new ArrayList<>();

    // ─────────────────────────────────────────────────────────────────────────────
    // TERMINATION
    // ─────────────────────────────────────────────────────────────────────────────

    /**
     * If a filter terminates the chain, this holds the termination details.
     */
    private FilterTermination termination;

    // ─────────────────────────────────────────────────────────────────────────────
    // HELPER METHODS
    // ─────────────────────────────────────────────────────────────────────────────

    /**
     * Record a mutation made by a filter.
     *
     * @param filterId The filter making the change
     * @param field The field being changed
     * @param oldValue The previous value
     * @param newValue The new value
     */
    public void recordMutation(String filterId, String field, Object oldValue, Object newValue) {
        if (mutations == null) {
            mutations = new ArrayList<>();
        }
        mutations.add(FilterMutation.of(filterId, field, oldValue, newValue));
    }

    /**
     * Record a mutation with reason.
     */
    public void recordMutation(String filterId, String field, Object oldValue, Object newValue, String reason) {
        if (mutations == null) {
            mutations = new ArrayList<>();
        }
        mutations.add(FilterMutation.of(filterId, field, oldValue, newValue, reason));
    }

    /**
     * Add a trace entry.
     */
    public void addTrace(FilterTraceEntry trace) {
        if (traces == null) {
            traces = new ArrayList<>();
        }
        traces.add(trace);
    }

    /**
     * Get the effective query to use for retrieval.
     * Returns rewrittenQuery if set, otherwise originalQuery, otherwise userMessage.
     */
    public String getEffectiveQuery() {
        if (rewrittenQuery != null && !rewrittenQuery.isBlank()) {
            return rewrittenQuery;
        }
        if (originalQuery != null && !originalQuery.isBlank()) {
            return originalQuery;
        }
        return userMessage;
    }

    /**
     * Set the rewritten query and record the mutation.
     */
    public void setRewrittenQuery(String filterId, String newQuery, String reason) {
        String oldQuery = this.rewrittenQuery;
        this.rewrittenQuery = newQuery;
        recordMutation(filterId, "rewrittenQuery", oldQuery, newQuery, reason);
    }

    /**
     * Create an empty context for a new request.
     */
    public static FilterContext forQuery(String query) {
        return FilterContext.builder()
                .userMessage(query)
                .originalQuery(query)
                .build();
    }

    /**
     * Create a context for a conversational query.
     */
    public static FilterContext forConversation(String conversationId, String userMessage, List<Message> history) {
        return FilterContext.builder()
                .conversationId(conversationId)
                .userMessage(userMessage)
                .originalQuery(userMessage)
                .conversationHistory(history != null ? new ArrayList<>(history) : new ArrayList<>())
                .build();
    }

    /**
     * Inner class for termination details.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class FilterTermination {
        private String filterId;
        private FilterAction action;
        private String message;
        private int httpStatusCode;
        private Map<String, Object> metadata;
    }
}
