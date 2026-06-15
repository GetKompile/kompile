/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ai.kompile.react.model;

import lombok.Builder;
import lombok.Data;

/**
 * Tracks token usage for LLM calls.
 */
@Data
@Builder
public class TokenUsage {

    /**
     * Number of tokens in the prompt/input.
     */
    @Builder.Default
    private long promptTokens = 0;

    /**
     * Number of tokens in the completion/output.
     */
    @Builder.Default
    private long completionTokens = 0;

    /**
     * Total tokens used.
     */
    @Builder.Default
    private long totalTokens = 0;

    /**
     * Create an empty token usage.
     */
    public static TokenUsage empty() {
        return TokenUsage.builder().build();
    }

    /**
     * Create token usage with values.
     */
    public static TokenUsage of(long promptTokens, long completionTokens) {
        return TokenUsage.builder()
                .promptTokens(promptTokens)
                .completionTokens(completionTokens)
                .totalTokens(promptTokens + completionTokens)
                .build();
    }

    /**
     * Add another token usage to this one.
     */
    public TokenUsage add(TokenUsage other) {
        if (other == null) {
            return this;
        }
        return TokenUsage.builder()
                .promptTokens(this.promptTokens + other.promptTokens)
                .completionTokens(this.completionTokens + other.completionTokens)
                .totalTokens(this.totalTokens + other.totalTokens)
                .build();
    }
}
