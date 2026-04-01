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

import org.springframework.ai.chat.messages.Message;

import java.util.List;
import java.util.Objects;

/**
 * Result of context building for LLM prompts.
 * <p>
 * Contains the formatted context from retrieved documents, along with
 * system prompts and conversation history ready for LLM consumption.
 *
 * @param systemPrompt The system prompt to set the LLM's behavior
 * @param contextString Formatted string containing retrieved document content
 * @param userPromptWithContext User's query augmented with context (if applicable)
 * @param formattedHistory Conversation history messages formatted for the LLM
 * @param includedDocCount Number of documents included in the context
 * @param estimatedTokens Estimated token count of the full prompt
 * @param truncated Whether the context was truncated due to token limits
 */
public record BuiltContext(
    String systemPrompt,
    String contextString,
    String userPromptWithContext,
    List<Message> formattedHistory,
    int includedDocCount,
    int estimatedTokens,
    boolean truncated
) {

    public BuiltContext {
        Objects.requireNonNull(systemPrompt, "systemPrompt cannot be null");
        if (contextString == null) {
            contextString = "";
        }
        if (formattedHistory == null) {
            formattedHistory = List.of();
        }
    }

    /**
     * Creates an empty context with default system prompt.
     */
    public static BuiltContext empty() {
        return new BuiltContext(
            "You are a helpful AI assistant.",
            "",
            "",
            List.of(),
            0,
            0,
            false
        );
    }

    /**
     * Returns true if there is context available.
     */
    public boolean hasContext() {
        return contextString != null && !contextString.isBlank();
    }

    /**
     * Returns true if conversation history is available.
     */
    public boolean hasHistory() {
        return formattedHistory != null && !formattedHistory.isEmpty();
    }

    /**
     * Builder for constructing BuiltContext instances.
     */
    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String systemPrompt = "You are a helpful AI assistant.";
        private String contextString = "";
        private String userPromptWithContext = "";
        private List<Message> formattedHistory = List.of();
        private int includedDocCount = 0;
        private int estimatedTokens = 0;
        private boolean truncated = false;

        public Builder systemPrompt(String prompt) {
            this.systemPrompt = prompt != null ? prompt : "You are a helpful AI assistant.";
            return this;
        }

        public Builder contextString(String context) {
            this.contextString = context != null ? context : "";
            return this;
        }

        public Builder userPromptWithContext(String prompt) {
            this.userPromptWithContext = prompt != null ? prompt : "";
            return this;
        }

        public Builder formattedHistory(List<Message> history) {
            this.formattedHistory = history != null ? history : List.of();
            return this;
        }

        public Builder includedDocCount(int count) {
            this.includedDocCount = count;
            return this;
        }

        public Builder estimatedTokens(int tokens) {
            this.estimatedTokens = tokens;
            return this;
        }

        public Builder truncated(boolean truncated) {
            this.truncated = truncated;
            return this;
        }

        public BuiltContext build() {
            return new BuiltContext(
                systemPrompt,
                contextString,
                userPromptWithContext,
                formattedHistory,
                includedDocCount,
                estimatedTokens,
                truncated
            );
        }
    }
}
