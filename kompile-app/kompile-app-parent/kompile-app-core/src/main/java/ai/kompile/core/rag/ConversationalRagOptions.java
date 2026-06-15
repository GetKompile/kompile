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

package ai.kompile.core.rag;

import ai.kompile.core.rag.retrieval.RetrievalOptions;
import ai.kompile.core.reranking.RerankerConfig;

import java.util.Map;

/**
 * Configuration options for conversational RAG operations.
 * <p>
 * Extends retrieval options with conversation-specific settings.
 *
 * @param semanticK Number of documents to retrieve via semantic search
 * @param keywordK Number of documents to retrieve via keyword search
 * @param similarityThreshold Minimum similarity score threshold
 * @param enableKeywordSearch Whether to include keyword search
 * @param enableSemanticSearch Whether to include semantic search
 * @param enableQueryProcessing Whether to process/rewrite queries for retrieval
 * @param maxHistoryMessages Maximum conversation history messages to include
 * @param maxContextTokens Maximum tokens for context in LLM prompt
 * @param useToolCalling Whether to enable LLM tool calling
 * @param systemPrompt Optional custom system prompt
 * @param metadataFilters Optional metadata filters for retrieval
 * @param rerankerConfig Optional reranking configuration
 */
public record ConversationalRagOptions(
    int semanticK,
    int keywordK,
    double similarityThreshold,
    boolean enableKeywordSearch,
    boolean enableSemanticSearch,
    boolean enableQueryProcessing,
    int maxHistoryMessages,
    int maxContextTokens,
    boolean useToolCalling,
    String systemPrompt,
    Map<String, Object> metadataFilters,
    RerankerConfig rerankerConfig
) {

    /**
     * Default max history messages.
     */
    public static final int DEFAULT_MAX_HISTORY = 10;

    /**
     * Default max context tokens.
     */
    public static final int DEFAULT_MAX_CONTEXT_TOKENS = 4000;

    /**
     * Creates default conversational RAG options.
     */
    public static ConversationalRagOptions defaults() {
        return new ConversationalRagOptions(
            5,                          // semanticK
            5,                          // keywordK
            0.5,                        // similarityThreshold
            true,                       // enableKeywordSearch
            true,                       // enableSemanticSearch
            true,                       // enableQueryProcessing
            DEFAULT_MAX_HISTORY,        // maxHistoryMessages
            DEFAULT_MAX_CONTEXT_TOKENS, // maxContextTokens
            false,                      // useToolCalling
            null,                       // systemPrompt
            Map.of(),                   // metadataFilters
            null                        // rerankerConfig
        );
    }

    /**
     * Creates options for simple RAG without conversation features.
     */
    public static ConversationalRagOptions simpleRag(int k, double threshold) {
        return new ConversationalRagOptions(
            k, k, threshold,
            true, true, false,
            0, DEFAULT_MAX_CONTEXT_TOKENS,
            false, null, Map.of(), null
        );
    }

    /**
     * Creates options from a RagQuery for backward compatibility.
     */
    public static ConversationalRagOptions fromRagQuery(RagQuery query) {
        int k = query.getK() > 0 ? query.getK() : 10;
        return new ConversationalRagOptions(
            k / 2, k / 2, 0.0,
            true, true, false,
            0, DEFAULT_MAX_CONTEXT_TOKENS,
            query.isUseToolCalling(), null, Map.of(), null
        );
    }

    /**
     * Converts to RetrievalOptions.
     */
    public RetrievalOptions toRetrievalOptions() {
        return new RetrievalOptions(
            semanticK,
            keywordK,
            similarityThreshold,
            enableKeywordSearch,
            enableSemanticSearch,
            true, // deduplicate
            metadataFilters
        );
    }

    /**
     * Returns total k (semantic + keyword).
     */
    public int totalK() {
        int total = 0;
        if (enableSemanticSearch) total += semanticK;
        if (enableKeywordSearch) total += keywordK;
        return total;
    }

    // Builder methods

    public ConversationalRagOptions withSemanticK(int k) {
        return new ConversationalRagOptions(k, keywordK, similarityThreshold, enableKeywordSearch, enableSemanticSearch, enableQueryProcessing, maxHistoryMessages, maxContextTokens, useToolCalling, systemPrompt, metadataFilters, rerankerConfig);
    }

    public ConversationalRagOptions withKeywordK(int k) {
        return new ConversationalRagOptions(semanticK, k, similarityThreshold, enableKeywordSearch, enableSemanticSearch, enableQueryProcessing, maxHistoryMessages, maxContextTokens, useToolCalling, systemPrompt, metadataFilters, rerankerConfig);
    }

    public ConversationalRagOptions withSimilarityThreshold(double threshold) {
        return new ConversationalRagOptions(semanticK, keywordK, threshold, enableKeywordSearch, enableSemanticSearch, enableQueryProcessing, maxHistoryMessages, maxContextTokens, useToolCalling, systemPrompt, metadataFilters, rerankerConfig);
    }

    public ConversationalRagOptions withQueryProcessing(boolean enabled) {
        return new ConversationalRagOptions(semanticK, keywordK, similarityThreshold, enableKeywordSearch, enableSemanticSearch, enabled, maxHistoryMessages, maxContextTokens, useToolCalling, systemPrompt, metadataFilters, rerankerConfig);
    }

    public ConversationalRagOptions withMaxHistoryMessages(int max) {
        return new ConversationalRagOptions(semanticK, keywordK, similarityThreshold, enableKeywordSearch, enableSemanticSearch, enableQueryProcessing, max, maxContextTokens, useToolCalling, systemPrompt, metadataFilters, rerankerConfig);
    }

    public ConversationalRagOptions withMaxContextTokens(int max) {
        return new ConversationalRagOptions(semanticK, keywordK, similarityThreshold, enableKeywordSearch, enableSemanticSearch, enableQueryProcessing, maxHistoryMessages, max, useToolCalling, systemPrompt, metadataFilters, rerankerConfig);
    }

    public ConversationalRagOptions withToolCalling(boolean enabled) {
        return new ConversationalRagOptions(semanticK, keywordK, similarityThreshold, enableKeywordSearch, enableSemanticSearch, enableQueryProcessing, maxHistoryMessages, maxContextTokens, enabled, systemPrompt, metadataFilters, rerankerConfig);
    }

    public ConversationalRagOptions withSystemPrompt(String prompt) {
        return new ConversationalRagOptions(semanticK, keywordK, similarityThreshold, enableKeywordSearch, enableSemanticSearch, enableQueryProcessing, maxHistoryMessages, maxContextTokens, useToolCalling, prompt, metadataFilters, rerankerConfig);
    }

    public ConversationalRagOptions withMetadataFilters(Map<String, Object> filters) {
        return new ConversationalRagOptions(semanticK, keywordK, similarityThreshold, enableKeywordSearch, enableSemanticSearch, enableQueryProcessing, maxHistoryMessages, maxContextTokens, useToolCalling, systemPrompt, filters, rerankerConfig);
    }

    public ConversationalRagOptions withRerankerConfig(RerankerConfig config) {
        return new ConversationalRagOptions(semanticK, keywordK, similarityThreshold, enableKeywordSearch, enableSemanticSearch, enableQueryProcessing, maxHistoryMessages, maxContextTokens, useToolCalling, systemPrompt, metadataFilters, config);
    }

    /**
     * Returns true if reranking is enabled.
     */
    public boolean isRerankingEnabled() {
        return rerankerConfig != null && rerankerConfig.isEnabled();
    }
}
