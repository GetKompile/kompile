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
 * Builds context strings for LLM prompts from retrieved documents and conversation history.
 * <p>
 * Context building involves:
 * <ul>
 *   <li>Formatting retrieved documents into a coherent context string</li>
 *   <li>Managing token budgets to avoid exceeding model limits</li>
 *   <li>Incorporating relevant conversation history</li>
 *   <li>Generating appropriate system prompts</li>
 * </ul>
 *
 * @see BuiltContext
 * @see ContextBuildRequest
 */
public interface ContextBuilder {

    /**
     * Builds context from a request containing documents, history, and options.
     *
     * @param request The context build request
     * @return Built context ready for LLM consumption
     */
    BuiltContext build(ContextBuildRequest request);

    /**
     * Simplified method to build context from documents and query.
     *
     * @param documents Retrieved documents with scores
     * @param query The user's query
     * @param maxTokens Maximum tokens for the context
     * @return Built context
     */
    default BuiltContext build(List<ScoredDocument> documents, String query, int maxTokens) {
        return build(ContextBuildRequest.of(documents, query, maxTokens));
    }

    /**
     * Simplified method to build context with history.
     *
     * @param documents Retrieved documents with scores
     * @param history Conversation history
     * @param query The user's query
     * @param maxTokens Maximum tokens for the context
     * @return Built context
     */
    default BuiltContext build(List<ScoredDocument> documents, List<Message> history,
                                String query, int maxTokens) {
        return build(new ContextBuildRequest(documents, history, query, maxTokens, null));
    }

    /**
     * Estimates token count for a string.
     * <p>
     * Default implementation uses a simple heuristic (4 chars = 1 token).
     * Implementations may use more accurate tokenizers.
     *
     * @param text Text to estimate
     * @return Estimated token count
     */
    default int estimateTokens(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        // Simple heuristic: ~4 characters per token for English text
        return (int) Math.ceil(text.length() / 4.0);
    }

    /**
     * Returns the name of this builder implementation.
     *
     * @return Builder name
     */
    default String getName() {
        return getClass().getSimpleName();
    }
}
