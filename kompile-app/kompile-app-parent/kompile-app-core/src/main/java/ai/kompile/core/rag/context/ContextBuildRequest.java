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

package ai.kompile.core.rag.context;

import ai.kompile.core.embeddings.ScoredDocument;
import org.springframework.ai.chat.messages.Message;

import java.util.List;

/**
 * Request for building LLM context from retrieved documents and conversation history.
 *
 * @param documents Retrieved documents with scores
 * @param conversationHistory Previous messages in the conversation
 * @param currentQuery The user's current query
 * @param maxTokens Maximum tokens allowed for the context (0 for unlimited)
 * @param systemPromptTemplate Optional custom system prompt template
 */
public record ContextBuildRequest(
    List<ScoredDocument> documents,
    List<Message> conversationHistory,
    String currentQuery,
    int maxTokens,
    String systemPromptTemplate
) {

    public ContextBuildRequest {
        if (documents == null) {
            documents = List.of();
        }
        if (conversationHistory == null) {
            conversationHistory = List.of();
        }
        if (maxTokens < 0) {
            maxTokens = 0;
        }
    }

    /**
     * Creates a simple request with documents and query.
     */
    public static ContextBuildRequest of(List<ScoredDocument> documents, String query, int maxTokens) {
        return new ContextBuildRequest(documents, List.of(), query, maxTokens, null);
    }

    /**
     * Creates a request with all components.
     */
    public static ContextBuildRequest of(List<ScoredDocument> documents, List<Message> history,
                                          String query, int maxTokens, String systemPrompt) {
        return new ContextBuildRequest(documents, history, query, maxTokens, systemPrompt);
    }

    /**
     * Returns true if there are documents to include.
     */
    public boolean hasDocuments() {
        return documents != null && !documents.isEmpty();
    }

    /**
     * Returns true if there is conversation history.
     */
    public boolean hasHistory() {
        return conversationHistory != null && !conversationHistory.isEmpty();
    }

    /**
     * Returns true if a custom system prompt template is provided.
     */
    public boolean hasCustomSystemPrompt() {
        return systemPromptTemplate != null && !systemPromptTemplate.isBlank();
    }

    /**
     * Returns true if token budget is limited.
     */
    public boolean hasTokenLimit() {
        return maxTokens > 0;
    }

    /**
     * Creates a new request with updated max tokens.
     */
    public ContextBuildRequest withMaxTokens(int tokens) {
        return new ContextBuildRequest(documents, conversationHistory, currentQuery, tokens, systemPromptTemplate);
    }

    /**
     * Creates a new request with custom system prompt.
     */
    public ContextBuildRequest withSystemPrompt(String prompt) {
        return new ContextBuildRequest(documents, conversationHistory, currentQuery, maxTokens, prompt);
    }
}
