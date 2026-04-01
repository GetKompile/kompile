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

package ai.kompile.core.query;

import lombok.Builder;
import lombok.Data;
import org.springframework.ai.chat.messages.Message;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Context for query transformation operations.
 * <p>
 * Provides conversation history and additional metadata that transformers
 * can use to improve query transformation quality.
 */
@Data
@Builder
public class QueryTransformContext {

    /**
     * Conversation history for context-aware transformations.
     */
    @Builder.Default
    private List<Message> conversationHistory = Collections.emptyList();

    /**
     * Additional metadata for transformation.
     */
    @Builder.Default
    private Map<String, Object> metadata = Collections.emptyMap();

    /**
     * Maximum number of queries to generate (for expansion).
     */
    @Builder.Default
    private int maxQueries = 3;

    /**
     * The domain or topic for domain-specific transformations.
     */
    private String domain;

    /**
     * Language code for the query (e.g., "en", "es").
     */
    @Builder.Default
    private String language = "en";

    /**
     * Whether to include the original query in expansion results.
     */
    @Builder.Default
    private boolean includeOriginal = true;

    /**
     * Create an empty context.
     *
     * @return An empty QueryTransformContext
     */
    public static QueryTransformContext empty() {
        return QueryTransformContext.builder().build();
    }

    /**
     * Create a context with conversation history.
     *
     * @param history The conversation history
     * @return A QueryTransformContext with the given history
     */
    public static QueryTransformContext withHistory(List<Message> history) {
        return QueryTransformContext.builder()
                .conversationHistory(history != null ? history : Collections.emptyList())
                .build();
    }
}
