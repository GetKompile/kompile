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

import java.util.List;
import java.util.Map;

/**
 * Interface for transforming queries before retrieval.
 * <p>
 * Query transformers can modify, expand, or compress queries to improve
 * retrieval quality. Common strategies include:
 * <ul>
 *   <li>Query expansion - Generate multiple query variants</li>
 *   <li>Query compression - Condense follow-up queries with conversation context</li>
 *   <li>Query rewriting - Reformulate queries for better matching</li>
 *   <li>Hypothetical document embedding (HyDE) - Generate hypothetical answers</li>
 * </ul>
 */
public interface QueryTransformer {

    /**
     * Transform a query into one or more queries.
     *
     * @param query The original query
     * @param context Additional context for transformation (conversation history, metadata, etc.)
     * @return A list of transformed queries (may be one or more)
     */
    List<String> transform(String query, QueryTransformContext context);

    /**
     * Transform a query using default context.
     *
     * @param query The original query
     * @return A list of transformed queries
     */
    default List<String> transform(String query) {
        return transform(query, QueryTransformContext.empty());
    }

    /**
     * Get the name of this transformer.
     *
     * @return The transformer name
     */
    String getName();

    /**
     * Get the type of transformation this transformer performs.
     *
     * @return The transformation type
     */
    QueryTransformationType getType();

    /**
     * Check if this transformer requires an LLM.
     *
     * @return true if an LLM is required
     */
    default boolean requiresLlm() {
        return false;
    }

    /**
     * Types of query transformation.
     */
    enum QueryTransformationType {
        /**
         * No transformation, pass through as-is.
         */
        PASSTHROUGH,

        /**
         * Expand the query into multiple variants.
         */
        EXPANSION,

        /**
         * Compress conversation + query into standalone query.
         */
        COMPRESSION,

        /**
         * Rewrite the query to improve retrieval.
         */
        REWRITING,

        /**
         * Generate a hypothetical document for HyDE.
         */
        HYPOTHETICAL_DOCUMENT,

        /**
         * Step-back prompting - generate more abstract query.
         */
        STEP_BACK,

        /**
         * Multi-query - decompose into sub-questions.
         */
        DECOMPOSITION
    }
}
