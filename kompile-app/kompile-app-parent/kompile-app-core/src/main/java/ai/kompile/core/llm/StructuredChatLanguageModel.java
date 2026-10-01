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

    /**
     * Most inline images one request may carry, counted across every message. Local vision
     * serving refuses more, since each image is a full vision-encoder pass held in device
     * memory, and chat clients drop the oldest images to stay within it.
     */
    int MAX_INLINE_IMAGES_PER_REQUEST = 8;

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

    /**
     * One conversation message. {@code images} belong to this message and render before its
     * text, so a multi-turn conversation keeps every image in the turn that sent it.
     */
    record Message(String role, String content, List<InlineImage> images) {
        public Message {
            role = role == null ? "" : role.trim();
            content = content == null ? "" : content;
            images = images == null ? List.of() : List.copyOf(images);
            if (role.isBlank()) {
                throw new IllegalArgumentException("message role must not be blank");
            }
        }

        public Message(String role, String content) {
            this(role, content, List.of());
        }
    }

    /**
     * One inline image riding with a chat turn (base64 already encoded; no data: prefix).
     * consumed by adapters that own an image-to-embeddings path (local VLM serving);
     * remote providers map it to their own image content block.
     */
    record InlineImage(String mimeType, String base64Data, String detail) {
        public InlineImage {
            mimeType = mimeType == null || mimeType.isBlank() ? "image/png" : mimeType.trim();
            base64Data = base64Data == null ? "" : base64Data;
            detail = detail == null ? "" : detail.trim();
            if (base64Data.isBlank()) {
                throw new IllegalArgumentException("inline image base64Data must not be blank");
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

    /**
     * One structured chat request. Images normally ride on the {@link Message} that sent
     * them; the request-level {@code images} are the older wire shape, meaning "images for
     * the latest user turn", and adapters attach them to the last user message. Both default
     * to empty and all legacy constructors stay intact.
     */
    record Request(
            List<Message> messages,
            List<Tool> tools,
            boolean addGenerationPrompt,
            ToolDefinitionFormat toolDefinitionFormat,
            ToolCallFormat toolCallFormat,
            ToolChoice toolChoice,
            Map<String, Object> templateArguments,
            List<InlineImage> images) {
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
            templateArguments = templateArguments == null
                    ? Map.of()
                    : Map.copyOf(new LinkedHashMap<>(templateArguments));
            images = images == null ? List.of() : List.copyOf(images);
        }

        public Request(
                List<Message> messages,
                List<Tool> tools,
                boolean addGenerationPrompt,
                ToolDefinitionFormat toolDefinitionFormat,
                ToolCallFormat toolCallFormat,
                ToolChoice toolChoice,
                Map<String, Object> templateArguments) {
            this(messages, tools, addGenerationPrompt, toolDefinitionFormat,
                    toolCallFormat, toolChoice, templateArguments, List.of());
        }

        public Request(
                List<Message> messages,
                List<Tool> tools,
                boolean addGenerationPrompt,
                ToolDefinitionFormat toolDefinitionFormat,
                ToolCallFormat toolCallFormat,
                ToolChoice toolChoice) {
            this(messages, tools, addGenerationPrompt, toolDefinitionFormat,
                    toolCallFormat, toolChoice, Map.of());
        }

        public Request(
                List<Message> messages,
                List<Tool> tools,
                boolean addGenerationPrompt,
                ToolDefinitionFormat toolDefinitionFormat,
                ToolCallFormat toolCallFormat) {
            this(messages, tools, addGenerationPrompt, toolDefinitionFormat,
                    toolCallFormat, ToolChoice.AUTO, Map.of());
        }

        public Request(List<Message> messages, List<Tool> tools) {
            this(messages, tools, true, ToolDefinitionFormat.FLAT,
                    ToolCallFormat.MODEL, ToolChoice.AUTO, Map.of());
        }

        /** True when any message, or the request itself, carries inline images. */
        public boolean hasImages() {
            return imageCount() > 0;
        }

        /** Every inline image in the conversation, message-level and request-level. */
        public int imageCount() {
            int count = images.size();
            for (Message message : messages) {
                count += message.images().size();
            }
            return count;
        }
    }

    /** One model/template-declared assistant output block. */
    record OutputBlock(String type, String content) {
        public OutputBlock {
            type = type == null ? "" : type.trim();
            content = content == null ? "" : content;
            if (type.isBlank()) {
                throw new IllegalArgumentException("output block type must not be blank");
            }
        }
    }

    record Response(
            String rawText,
            String content,
            String reasoningContent,
            List<OutputBlock> outputBlocks,
            List<ToolCall> toolCalls,
            List<String> parseErrors) {
        public Response {
            rawText = rawText == null ? "" : rawText;
            content = content == null ? "" : content;
            reasoningContent = reasoningContent == null ? "" : reasoningContent;
            outputBlocks = outputBlocks == null ? List.of() : List.copyOf(outputBlocks);
            toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
            parseErrors = parseErrors == null ? List.of() : List.copyOf(parseErrors);
        }

        public Response(
                String rawText,
                String content,
                String reasoningContent,
                List<ToolCall> toolCalls,
                List<String> parseErrors) {
            this(rawText, content, reasoningContent,
                    reasoningContent == null || reasoningContent.isBlank()
                            ? List.of()
                            : List.of(new OutputBlock("think", reasoningContent)),
                    toolCalls, parseErrors);
        }

        public Response(
                String rawText,
                String content,
                List<ToolCall> toolCalls,
                List<String> parseErrors) {
            this(rawText, content, "", List.of(), toolCalls, parseErrors);
        }
    }

    /**
     * Thrown when a request carries images and the loaded model has no vision path. The serving
     * endpoint maps only this type to {@code errorKind=IMAGE_INPUT_UNSUPPORTED}, so an unrelated
     * {@link UnsupportedOperationException} deep in a backend is never reported as a vision gap.
     */
    final class ImageInputUnsupportedException extends UnsupportedOperationException {
        public ImageInputUnsupportedException(String message) {
            super(message);
        }
    }

    Response generateChat(Request request, int maxNewTokens);
}
