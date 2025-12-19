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

package ai.kompile.core.rag.query;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Result of query processing/transformation.
 * <p>
 * Query processing transforms a raw user query (which may contain
 * references to conversation context) into a standalone query suitable
 * for retrieval operations.
 *
 * @param originalQuery The user's original, unmodified query
 * @param rewrittenQuery Standalone query suitable for retrieval (references resolved)
 * @param subQueries Optional sub-queries for multi-hop retrieval
 * @param extractedFilters Metadata filters extracted from the query (e.g., "from last year" → date filter)
 * @param intent Detected query intent
 * @param entities Named entities mentioned in the query
 * @param wasRewritten Whether the query was modified during processing
 */
public record ProcessedQuery(
    String originalQuery,
    String rewrittenQuery,
    List<String> subQueries,
    Map<String, Object> extractedFilters,
    QueryIntent intent,
    List<String> entities,
    boolean wasRewritten
) {

    public ProcessedQuery {
        Objects.requireNonNull(originalQuery, "originalQuery cannot be null");
        if (rewrittenQuery == null) {
            rewrittenQuery = originalQuery;
        }
        if (subQueries == null) {
            subQueries = List.of();
        }
        if (extractedFilters == null) {
            extractedFilters = Map.of();
        }
        if (intent == null) {
            intent = QueryIntent.UNKNOWN;
        }
        if (entities == null) {
            entities = List.of();
        }
    }

    /**
     * Creates a simple processed query with no transformation.
     */
    public static ProcessedQuery unchanged(String query) {
        return new ProcessedQuery(
            query,
            query,
            List.of(),
            Map.of(),
            QueryIntent.QUESTION,
            List.of(),
            false
        );
    }

    /**
     * Creates a processed query with rewriting.
     */
    public static ProcessedQuery rewritten(String originalQuery, String rewrittenQuery, QueryIntent intent) {
        return new ProcessedQuery(
            originalQuery,
            rewrittenQuery,
            List.of(),
            Map.of(),
            intent,
            List.of(),
            !originalQuery.equals(rewrittenQuery)
        );
    }

    /**
     * Gets the query to use for retrieval operations.
     * Returns the rewritten query if available, otherwise the original.
     */
    public String getRetrievalQuery() {
        return rewrittenQuery != null && !rewrittenQuery.isBlank() ? rewrittenQuery : originalQuery;
    }

    /**
     * Returns true if there are sub-queries for multi-hop retrieval.
     */
    public boolean hasSubQueries() {
        return subQueries != null && !subQueries.isEmpty();
    }

    /**
     * Returns true if metadata filters were extracted.
     */
    public boolean hasFilters() {
        return extractedFilters != null && !extractedFilters.isEmpty();
    }

    /**
     * Builder for constructing ProcessedQuery instances.
     */
    public static Builder builder(String originalQuery) {
        return new Builder(originalQuery);
    }

    public static class Builder {
        private final String originalQuery;
        private String rewrittenQuery;
        private List<String> subQueries = List.of();
        private Map<String, Object> extractedFilters = Map.of();
        private QueryIntent intent = QueryIntent.QUESTION;
        private List<String> entities = List.of();

        public Builder(String originalQuery) {
            this.originalQuery = Objects.requireNonNull(originalQuery);
            this.rewrittenQuery = originalQuery;
        }

        public Builder rewrittenQuery(String query) {
            this.rewrittenQuery = query;
            return this;
        }

        public Builder subQueries(List<String> queries) {
            this.subQueries = queries != null ? queries : List.of();
            return this;
        }

        public Builder extractedFilters(Map<String, Object> filters) {
            this.extractedFilters = filters != null ? filters : Map.of();
            return this;
        }

        public Builder intent(QueryIntent intent) {
            this.intent = intent != null ? intent : QueryIntent.UNKNOWN;
            return this;
        }

        public Builder entities(List<String> entities) {
            this.entities = entities != null ? entities : List.of();
            return this;
        }

        public ProcessedQuery build() {
            return new ProcessedQuery(
                originalQuery,
                rewrittenQuery,
                subQueries,
                extractedFilters,
                intent,
                entities,
                !originalQuery.equals(rewrittenQuery)
            );
        }
    }
}
