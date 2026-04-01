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
package ai.kompile.orchestrator.model.llm;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * Represents a single message in a conversation/chat history.
 * This is used to store the complete chat history for React Agent sessions
 * and LLM interactions, enabling feedback loops and audit trails.
 */
@Entity
@Table(name = "conversation_messages", indexes = {
        @Index(name = "idx_conv_session", columnList = "session_id"),
        @Index(name = "idx_conv_timestamp", columnList = "timestamp"),
        @Index(name = "idx_conv_orchestrator", columnList = "orchestrator_instance_id")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ConversationMessage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    /**
     * The LLM session this message belongs to.
     */
    @Column(name = "session_id", nullable = false)
    private Long sessionId;

    /**
     * The orchestrator instance this message belongs to.
     */
    @Column(name = "orchestrator_instance_id")
    private String orchestratorInstanceId;

    /**
     * The role of the message sender.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false)
    private MessageRole role;

    /**
     * The message content.
     */
    @Column(name = "content", columnDefinition = "TEXT")
    private String content;

    /**
     * Timestamp when the message was created.
     */
    @Column(name = "timestamp")
    @Builder.Default
    private LocalDateTime timestamp = LocalDateTime.now();

    /**
     * The order/sequence number of this message in the conversation.
     */
    @Column(name = "sequence_number")
    private Integer sequenceNumber;

    /**
     * Token count for this message (if available).
     */
    @Column(name = "token_count")
    private Integer tokenCount;

    /**
     * The task instance ID that triggered this message (if applicable).
     */
    @Column(name = "task_instance_id")
    private Long taskInstanceId;

    /**
     * The tool/function that was called (for function calling messages).
     */
    @Column(name = "tool_name")
    private String toolName;

    /**
     * Tool input parameters as JSON.
     */
    @Column(name = "tool_input", columnDefinition = "TEXT")
    private String toolInput;

    /**
     * Tool output/result as JSON.
     */
    @Column(name = "tool_output", columnDefinition = "TEXT")
    private String toolOutput;

    /**
     * Whether this message is part of a feedback loop.
     */
    @Column(name = "feedback_loop")
    @Builder.Default
    private boolean feedbackLoop = false;

    /**
     * The feedback loop iteration number (starts at 0).
     */
    @Column(name = "feedback_iteration")
    @Builder.Default
    private Integer feedbackIteration = 0;

    /**
     * Optional metadata as JSON.
     */
    @Column(name = "metadata_json", columnDefinition = "TEXT")
    private String metadataJson;

    @PrePersist
    protected void onCreate() {
        if (timestamp == null) {
            timestamp = LocalDateTime.now();
        }
    }

    /**
     * Message roles in a conversation.
     */
    public enum MessageRole {
        /**
         * System/instruction message.
         */
        SYSTEM,

        /**
         * User/human message.
         */
        USER,

        /**
         * Assistant/AI response.
         */
        ASSISTANT,

        /**
         * Tool/function call request.
         */
        TOOL_CALL,

        /**
         * Tool/function result.
         */
        TOOL_RESULT,

        /**
         * Task output (from orchestrator task).
         */
        TASK_OUTPUT,

        /**
         * Error message.
         */
        ERROR,

        /**
         * Feedback/annotation message.
         */
        FEEDBACK
    }

    /**
     * Create a system message.
     */
    public static ConversationMessage system(Long sessionId, String content) {
        return ConversationMessage.builder()
                .sessionId(sessionId)
                .role(MessageRole.SYSTEM)
                .content(content)
                .build();
    }

    /**
     * Create a user message.
     */
    public static ConversationMessage user(Long sessionId, String content) {
        return ConversationMessage.builder()
                .sessionId(sessionId)
                .role(MessageRole.USER)
                .content(content)
                .build();
    }

    /**
     * Create an assistant message.
     */
    public static ConversationMessage assistant(Long sessionId, String content) {
        return ConversationMessage.builder()
                .sessionId(sessionId)
                .role(MessageRole.ASSISTANT)
                .content(content)
                .build();
    }

    /**
     * Create a task output message.
     */
    public static ConversationMessage taskOutput(Long sessionId, Long taskInstanceId, String output) {
        return ConversationMessage.builder()
                .sessionId(sessionId)
                .role(MessageRole.TASK_OUTPUT)
                .content(output)
                .taskInstanceId(taskInstanceId)
                .build();
    }

    /**
     * Create a tool call message.
     */
    public static ConversationMessage toolCall(Long sessionId, String toolName, String input) {
        return ConversationMessage.builder()
                .sessionId(sessionId)
                .role(MessageRole.TOOL_CALL)
                .toolName(toolName)
                .toolInput(input)
                .build();
    }

    /**
     * Create a tool result message.
     */
    public static ConversationMessage toolResult(Long sessionId, String toolName, String output) {
        return ConversationMessage.builder()
                .sessionId(sessionId)
                .role(MessageRole.TOOL_RESULT)
                .toolName(toolName)
                .toolOutput(output)
                .build();
    }

    /**
     * Create an error message.
     */
    public static ConversationMessage error(Long sessionId, String errorMessage) {
        return ConversationMessage.builder()
                .sessionId(sessionId)
                .role(MessageRole.ERROR)
                .content(errorMessage)
                .build();
    }

    /**
     * Create a feedback message (for feedback loop iterations).
     */
    public static ConversationMessage feedback(Long sessionId, String content, int iteration) {
        return ConversationMessage.builder()
                .sessionId(sessionId)
                .role(MessageRole.FEEDBACK)
                .content(content)
                .feedbackLoop(true)
                .feedbackIteration(iteration)
                .build();
    }
}
