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

package ai.kompile.modelmanager;

import lombok.Data;

import java.util.Map;

/**
 * Configuration class for tokenizer settings.
 * Provides model-specific tokenizer configuration with sensible defaults.
 */
@Data
public class TokenizerConfig {
    private final boolean doLowerCase;
    private final boolean addSpecialTokens;
    private final int maxSequenceLength;
    private final boolean stripAccents;

    // Default values
    public static final boolean DEFAULT_DO_LOWER_CASE = true;
    public static final boolean DEFAULT_ADD_SPECIAL_TOKENS = true;
    public static final int DEFAULT_MAX_SEQUENCE_LENGTH = 512;
    public static final boolean DEFAULT_STRIP_ACCENTS = true;

    private TokenizerConfig(boolean doLowerCase, boolean addSpecialTokens, int maxSequenceLength, boolean stripAccents) {
        this.doLowerCase = doLowerCase;
        this.addSpecialTokens = addSpecialTokens;
        this.maxSequenceLength = maxSequenceLength;
        this.stripAccents = stripAccents;
    }

    /**
     * Creates a TokenizerConfig with default settings.
     */
    public static TokenizerConfig defaultConfig() {
        return new TokenizerConfig(DEFAULT_DO_LOWER_CASE, DEFAULT_ADD_SPECIAL_TOKENS,
                                 DEFAULT_MAX_SEQUENCE_LENGTH, DEFAULT_STRIP_ACCENTS);
    }

    /**
     * Creates a TokenizerConfig from model metadata.
     * Falls back to defaults if metadata is missing.
     */
    public static TokenizerConfig fromMetadata(Map<String, Object> metadata) {
        if (metadata == null) {
            return defaultConfig();
        }

        boolean doLowerCase = getBooleanFromMetadata(metadata, "tokenizer_do_lower_case", DEFAULT_DO_LOWER_CASE);
        boolean addSpecialTokens = getBooleanFromMetadata(metadata, "tokenizer_add_special_tokens", DEFAULT_ADD_SPECIAL_TOKENS);
        int maxSequenceLength = getIntFromMetadata(metadata, "tokenizer_max_sequence_length", DEFAULT_MAX_SEQUENCE_LENGTH);
        boolean stripAccents = getBooleanFromMetadata(metadata, "tokenizer_strip_accents", DEFAULT_STRIP_ACCENTS);

        return new TokenizerConfig(doLowerCase, addSpecialTokens, maxSequenceLength, stripAccents);
    }

    /**
     * Builder pattern for creating TokenizerConfig instances.
     */
    public static class Builder {
        private boolean doLowerCase = DEFAULT_DO_LOWER_CASE;
        private boolean addSpecialTokens = DEFAULT_ADD_SPECIAL_TOKENS;
        private int maxSequenceLength = DEFAULT_MAX_SEQUENCE_LENGTH;
        private boolean stripAccents = DEFAULT_STRIP_ACCENTS;

        public Builder doLowerCase(boolean doLowerCase) {
            this.doLowerCase = doLowerCase;
            return this;
        }

        public Builder addSpecialTokens(boolean addSpecialTokens) {
            this.addSpecialTokens = addSpecialTokens;
            return this;
        }

        public Builder maxSequenceLength(int maxSequenceLength) {
            if (maxSequenceLength <= 0) {
                throw new IllegalArgumentException("maxSequenceLength must be positive");
            }
            this.maxSequenceLength = maxSequenceLength;
            return this;
        }

        public Builder stripAccents(boolean stripAccents) {
            this.stripAccents = stripAccents;
            return this;
        }

        public TokenizerConfig build() {
            return new TokenizerConfig(doLowerCase, addSpecialTokens, maxSequenceLength, stripAccents);
        }
    }

    /**
     * Creates a new Builder instance.
     */
    public static Builder builder() {
        return new Builder();
    }

    private static boolean getBooleanFromMetadata(Map<String, Object> metadata, String key, boolean defaultValue) {
        Object value = metadata.get(key);
        if (value instanceof Boolean) {
            return (Boolean) value;
        } else if (value instanceof String) {
            return Boolean.parseBoolean((String) value);
        }
        return defaultValue;
    }

    private static int getIntFromMetadata(Map<String, Object> metadata, String key, int defaultValue) {
        Object value = metadata.get(key);
        if (value instanceof Integer) {
            return (Integer) value;
        } else if (value instanceof Number) {
            return ((Number) value).intValue();
        } else if (value instanceof String) {
            try {
                return Integer.parseInt((String) value);
            } catch (NumberFormatException e) {
                // Fall back to default
            }
        }
        return defaultValue;
    }
}
