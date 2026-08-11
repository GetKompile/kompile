/*
 *   Copyright 2025 Kompile Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ai.kompile.core.llm;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Portable structured-chat capability shared by the crawl, serving bridge, and model adapters.
 *
 * <p>The model adapter owns chat-template rendering and native tool-call parsing. Callers pass
 * roles and function schemas without flattening them into a prompt string.</p>
 */
public interface StructuredChatLanguageModel {

    enum ToolDefinitionFormat {
        STANDARD,
        FLAT
    }

    enum ToolCallFormat {
        /** Let the imported model/tokenizer metadata select its native protocol. */
        MODEL,
        NATIVE,
        JSON
    }

    enum ToolChoice {
        AUTO,
        REQUIRED,
        NONE
    }

    record Message(String role, String content) {
        public Message {
            role = role == null ? "" : role.trim();
            content = content == null ? "" : content;
            if (role.isBlank()) {
                throw new IllegalArgumentException("message role must not be blank");
            }
        }
    }

    record Tool(String name, String description, Map<String, Object> parameters) {
        public Tool {
            name = name == null ? "" : name.trim();
            description = description == null ? "" : description;
            parameters = parameters == null
                    ? Map.of()
                    : Map.copyOf(new LinkedHashMap<>(parameters));
            if (name.isBlank()) {
                throw new IllegalArgumentException("tool name must not be blank");
            }
        }
    }

    record ToolCall(String id, String name, Map<String, Object> arguments) {
        public ToolCall {
            id = id == null ? "" : id;
            name = name == null ? "" : name.trim();
            arguments = arguments == null
                    ? Map.of()
                    : Map.copyOf(new LinkedHashMap<>(arguments));
            if (name.isBlank()) {
                throw new IllegalArgumentException("tool-call name must not be blank");
            }
        }
    }

    record Request(
            List<Message> messages,
            List<Tool> tools,
            boolean addGenerationPrompt,
            ToolDefinitionFormat toolDefinitionFormat,
            ToolCallFormat toolCallFormat,
            ToolChoice toolChoice) {
        public Request {
            messages = messages == null ? List.of() : List.copyOf(messages);
            tools = tools == null ? List.of() : List.copyOf(tools);
            toolDefinitionFormat = toolDefinitionFormat == null
                    ? ToolDefinitionFormat.FLAT
                    : toolDefinitionFormat;
            toolCallFormat = toolCallFormat == null
                    ? ToolCallFormat.MODEL
                    : toolCallFormat;
            toolChoice = toolChoice == null ? ToolChoice.AUTO : toolChoice;
        }

        public Request(
                List<Message> messages,
                List<Tool> tools,
                boolean addGenerationPrompt,
                ToolDefinitionFormat toolDefinitionFormat,
                ToolCallFormat toolCallFormat) {
            this(messages, tools, addGenerationPrompt, toolDefinitionFormat,
                    toolCallFormat, ToolChoice.AUTO);
        }

        public Request(List<Message> messages, List<Tool> tools) {
            this(messages, tools, true, ToolDefinitionFormat.FLAT,
                    ToolCallFormat.MODEL, ToolChoice.AUTO);
        }
    }

    record Response(
            String rawText,
            String content,
            List<ToolCall> toolCalls,
            List<String> parseErrors) {
        public Response {
            rawText = rawText == null ? "" : rawText;
            content = content == null ? "" : content;
            toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
            parseErrors = parseErrors == null ? List.of() : List.copyOf(parseErrors);
        }
    }

    Response generateChat(Request request, int maxNewTokens);
}
