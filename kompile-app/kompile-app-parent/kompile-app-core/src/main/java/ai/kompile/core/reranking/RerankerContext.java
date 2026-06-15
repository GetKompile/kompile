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

package ai.kompile.core.reranking;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Context information for reranking operations.
 * <p>
 * This class provides additional information that rerankers may use
 * to improve reranking quality, such as tokenized query terms,
 * feedback terms from previous reranking stages, and custom attributes.
 */
public class RerankerContext {

    private static final RerankerContext EMPTY = new RerankerContext(
            Collections.emptyList(),
            Collections.emptyMap(),
            Collections.emptyMap()
    );

    private final List<String> queryTokens;
    private final Map<String, Float> feedbackTerms;
    private final Map<String, Object> attributes;

    private RerankerContext(List<String> queryTokens,
                           Map<String, Float> feedbackTerms,
                           Map<String, Object> attributes) {
        this.queryTokens = queryTokens != null ? List.copyOf(queryTokens) : Collections.emptyList();
        this.feedbackTerms = feedbackTerms != null ? Map.copyOf(feedbackTerms) : Collections.emptyMap();
        this.attributes = attributes != null ? Map.copyOf(attributes) : Collections.emptyMap();
    }

    /**
     * Returns an empty context with no additional information.
     */
    public static RerankerContext empty() {
        return EMPTY;
    }

    /**
     * Creates a new builder for constructing a RerankerContext.
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Get the tokenized query terms.
     */
    public List<String> getQueryTokens() {
        return queryTokens;
    }

    /**
     * Get feedback terms from previous reranking stages.
     * Keys are terms, values are their weights.
     */
    public Map<String, Float> getFeedbackTerms() {
        return feedbackTerms;
    }

    /**
     * Get a custom attribute by key.
     */
    @SuppressWarnings("unchecked")
    public <T> T getAttribute(String key) {
        return (T) attributes.get(key);
    }

    /**
     * Get a custom attribute by key with a default value.
     */
    @SuppressWarnings("unchecked")
    public <T> T getAttribute(String key, T defaultValue) {
        Object value = attributes.get(key);
        return value != null ? (T) value : defaultValue;
    }

    /**
     * Check if an attribute exists.
     */
    public boolean hasAttribute(String key) {
        return attributes.containsKey(key);
    }

    /**
     * Get all custom attributes.
     */
    public Map<String, Object> getAttributes() {
        return attributes;
    }

    /**
     * Builder for RerankerContext.
     */
    public static class Builder {
        private List<String> queryTokens;
        private Map<String, Float> feedbackTerms;
        private Map<String, Object> attributes = new HashMap<>();

        public Builder queryTokens(List<String> queryTokens) {
            this.queryTokens = queryTokens;
            return this;
        }

        public Builder feedbackTerms(Map<String, Float> feedbackTerms) {
            this.feedbackTerms = feedbackTerms;
            return this;
        }

        public Builder attribute(String key, Object value) {
            this.attributes.put(key, value);
            return this;
        }

        public Builder attributes(Map<String, Object> attributes) {
            if (attributes != null) {
                this.attributes.putAll(attributes);
            }
            return this;
        }

        public RerankerContext build() {
            return new RerankerContext(queryTokens, feedbackTerms, attributes);
        }
    }
}
