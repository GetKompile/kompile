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

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Represents a message in the ReAct agent conversation.
 * Messages can be from the system, user, assistant, or tool executions.
 */
@Data
@Builder
public class ReActMessage {

    /**
     * Message role types.
     */
    public enum Role {
        SYSTEM,
        USER,
        ASSISTANT,
        TOOL
    }

    /**
     * Unique identifier for this message.
     */
    @Builder.Default
    private UUID id = UUID.randomUUID();

    /**
     * The role of this message sender.
     */
    private Role role;

    /**
     * The text content of the message.
     */
    private String content;

    /**
     * The thought/reasoning process (for ASSISTANT messages).
     */
    private String thought;

    /**
     * Tool calls requested by this message (for ASSISTANT messages).
     */
    @Builder.Default
    private List<ToolCall> toolCalls = new ArrayList<>();

    /**
     * Tool call ID this message responds to (for TOOL messages).
     */
    private String toolCallId;

    /**
     * The name of the tool that was called (for TOOL messages).
     */
    private String toolName;

    /**
     * Whether the tool execution was successful (for TOOL messages).
     */
    private Boolean toolSuccess;

    /**
     * Error message if tool execution failed (for TOOL messages).
     */
    private String toolError;

    /**
     * Token usage for this message.
     */
    private TokenUsage usage;

    /**
     * Timestamp when this message was created.
     */
    @Builder.Default
    private Instant timestamp = Instant.now();

    /**
     * Additional metadata.
     */
    private Map<String, Object> metadata;

    /**
     * Create a system message.
     */
    public static ReActMessage system(String content) {
        return ReActMessage.builder()
                .role(Role.SYSTEM)
                .content(content)
                .build();
    }

    /**
     * Create a user message.
     */
    public static ReActMessage user(String content) {
        return ReActMessage.builder()
                .role(Role.USER)
                .content(content)
                .build();
    }

    /**
     * Create an assistant message with content.
     */
    public static ReActMessage assistant(String content) {
        return ReActMessage.builder()
                .role(Role.ASSISTANT)
                .content(content)
                .build();
    }

    /**
     * Create an assistant message with thought and tool calls.
     */
    public static ReActMessage assistant(String thought, List<ToolCall> toolCalls) {
        return ReActMessage.builder()
                .role(Role.ASSISTANT)
                .thought(thought)
                .toolCalls(toolCalls != null ? toolCalls : new ArrayList<>())
                .build();
    }

    /**
     * Create a tool result message.
     */
    public static ReActMessage toolResult(String toolCallId, String toolName, String content, boolean success) {
        return ReActMessage.builder()
                .role(Role.TOOL)
                .toolCallId(toolCallId)
                .toolName(toolName)
                .content(content)
                .toolSuccess(success)
                .build();
    }

    /**
     * Create a tool error message.
     */
    public static ReActMessage toolError(String toolCallId, String toolName, String error) {
        return ReActMessage.builder()
                .role(Role.TOOL)
                .toolCallId(toolCallId)
                .toolName(toolName)
                .toolSuccess(false)
                .toolError(error)
                .content("Error: " + error)
                .build();
    }

    /**
     * Check if this message has tool calls.
     */
    public boolean hasToolCalls() {
        return toolCalls != null && !toolCalls.isEmpty();
    }

    /**
     * Check if this is a final answer message.
     */
    public boolean isFinalAnswer() {
        if (toolCalls == null || toolCalls.isEmpty()) {
            return false;
        }
        return toolCalls.stream().anyMatch(tc -> "final_answer".equals(tc.getName()));
    }
}
