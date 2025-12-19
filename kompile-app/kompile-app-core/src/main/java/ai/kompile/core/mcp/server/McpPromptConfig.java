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

package ai.kompile.core.mcp.server;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * Configuration for an MCP prompt template.
 * Prompts enable consistent AI model interactions with parameter substitution.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class McpPromptConfig {

    /**
     * Unique name for the prompt
     */
    private String name;

    /**
     * Human-readable description of the prompt's purpose
     */
    private String description;

    /**
     * List of arguments that can be passed to the prompt
     */
    @Builder.Default
    private List<PromptArgument> arguments = new ArrayList<>();

    /**
     * List of messages that make up the prompt template
     */
    @Builder.Default
    private List<PromptMessage> messages = new ArrayList<>();

    /**
     * Whether this prompt is enabled
     */
    @Builder.Default
    private boolean enabled = true;

    /**
     * Prompt argument definition
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PromptArgument {
        /**
         * Name of the argument
         */
        private String name;

        /**
         * Description of what the argument is for
         */
        private String description;

        /**
         * Whether this argument is required
         */
        @Builder.Default
        private boolean required = false;

        /**
         * Default value if not provided
         */
        private String defaultValue;
    }

    /**
     * Message in the prompt template
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PromptMessage {
        /**
         * Role of the message (user, assistant, system)
         */
        @Builder.Default
        private MessageRole role = MessageRole.USER;

        /**
         * Content type of the message
         */
        @Builder.Default
        private ContentType contentType = ContentType.TEXT;

        /**
         * The message content (may contain {{argument}} placeholders)
         */
        private String content;
    }

    /**
     * Message roles in prompt
     */
    public enum MessageRole {
        USER,
        ASSISTANT,
        SYSTEM
    }

    /**
     * Content types for messages
     */
    public enum ContentType {
        TEXT,
        IMAGE,
        RESOURCE
    }
}
