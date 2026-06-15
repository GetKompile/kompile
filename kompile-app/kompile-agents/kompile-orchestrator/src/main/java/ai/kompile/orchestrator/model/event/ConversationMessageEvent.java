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
package ai.kompile.orchestrator.model.event;

import ai.kompile.orchestrator.model.llm.ConversationMessage;
import ai.kompile.orchestrator.model.llm.ConversationMessage.MessageRole;
import lombok.Getter;

/**
 * Event published when a conversation message is added.
 * Used for real-time streaming of chat messages to connected clients.
 */
@Getter
public class ConversationMessageEvent extends OrchestratorEvent {

    private final Long sessionId;
    private final Long messageId;
    private final MessageRole role;
    private final String content;
    private final Integer sequenceNumber;
    private final String toolName;
    private final String toolInput;
    private final String toolOutput;
    private final Integer feedbackIteration;
    private final Long taskInstanceId;

    private ConversationMessageEvent(Object source, String orchestratorInstanceId,
                                      OrchestratorEventType eventType, String message,
                                      Long sessionId, Long messageId, MessageRole role,
                                      String content, Integer sequenceNumber,
                                      String toolName, String toolInput, String toolOutput,
                                      Integer feedbackIteration, Long taskInstanceId) {
        super(source, orchestratorInstanceId, eventType, message);
        this.sessionId = sessionId;
        this.messageId = messageId;
        this.role = role;
        this.content = content;
        this.sequenceNumber = sequenceNumber;
        this.toolName = toolName;
        this.toolInput = toolInput;
        this.toolOutput = toolOutput;
        this.feedbackIteration = feedbackIteration;
        this.taskInstanceId = taskInstanceId;
    }

    /**
     * Create event from a ConversationMessage entity.
     */
    public static ConversationMessageEvent from(Object source, ConversationMessage message) {
        OrchestratorEventType eventType = determineEventType(message.getRole());
        String eventMessage = formatEventMessage(message);

        return new ConversationMessageEvent(
                source,
                message.getOrchestratorInstanceId(),
                eventType,
                eventMessage,
                message.getSessionId(),
                message.getId(),
                message.getRole(),
                message.getContent(),
                message.getSequenceNumber(),
                message.getToolName(),
                message.getToolInput(),
                message.getToolOutput(),
                message.getFeedbackIteration(),
                message.getTaskInstanceId()
        );
    }

    /**
     * Create a user message event.
     */
    public static ConversationMessageEvent userMessage(Object source, String orchestratorInstanceId,
                                                        Long sessionId, Long messageId,
                                                        String content, Integer sequenceNumber) {
        return new ConversationMessageEvent(
                source, orchestratorInstanceId,
                OrchestratorEventType.CONVERSATION_MESSAGE_ADDED,
                "User message added",
                sessionId, messageId, MessageRole.USER, content, sequenceNumber,
                null, null, null, null, null
        );
    }

    /**
     * Create an assistant message event.
     */
    public static ConversationMessageEvent assistantMessage(Object source, String orchestratorInstanceId,
                                                             Long sessionId, Long messageId,
                                                             String content, Integer sequenceNumber) {
        return new ConversationMessageEvent(
                source, orchestratorInstanceId,
                OrchestratorEventType.CONVERSATION_MESSAGE_ADDED,
                "Assistant message added",
                sessionId, messageId, MessageRole.ASSISTANT, content, sequenceNumber,
                null, null, null, null, null
        );
    }

    /**
     * Create a tool call event.
     */
    public static ConversationMessageEvent toolCall(Object source, String orchestratorInstanceId,
                                                     Long sessionId, Long messageId,
                                                     String toolName, String toolInput,
                                                     Integer sequenceNumber) {
        return new ConversationMessageEvent(
                source, orchestratorInstanceId,
                OrchestratorEventType.CONVERSATION_TOOL_CALL,
                "Tool call: " + toolName,
                sessionId, messageId, MessageRole.TOOL_CALL, null, sequenceNumber,
                toolName, toolInput, null, null, null
        );
    }

    /**
     * Create a tool result event.
     */
    public static ConversationMessageEvent toolResult(Object source, String orchestratorInstanceId,
                                                       Long sessionId, Long messageId,
                                                       String toolName, String toolOutput,
                                                       Integer sequenceNumber) {
        return new ConversationMessageEvent(
                source, orchestratorInstanceId,
                OrchestratorEventType.CONVERSATION_TOOL_RESULT,
                "Tool result: " + toolName,
                sessionId, messageId, MessageRole.TOOL_RESULT, null, sequenceNumber,
                toolName, null, toolOutput, null, null
        );
    }

    /**
     * Create a feedback message event.
     */
    public static ConversationMessageEvent feedback(Object source, String orchestratorInstanceId,
                                                     Long sessionId, Long messageId,
                                                     String content, Integer sequenceNumber,
                                                     Integer feedbackIteration) {
        return new ConversationMessageEvent(
                source, orchestratorInstanceId,
                OrchestratorEventType.CONVERSATION_FEEDBACK_ADDED,
                "Feedback iteration " + feedbackIteration,
                sessionId, messageId, MessageRole.FEEDBACK, content, sequenceNumber,
                null, null, null, feedbackIteration, null
        );
    }

    /**
     * Create a task output event.
     */
    public static ConversationMessageEvent taskOutput(Object source, String orchestratorInstanceId,
                                                       Long sessionId, Long messageId,
                                                       String content, Integer sequenceNumber,
                                                       Long taskInstanceId) {
        return new ConversationMessageEvent(
                source, orchestratorInstanceId,
                OrchestratorEventType.CONVERSATION_MESSAGE_ADDED,
                "Task output added",
                sessionId, messageId, MessageRole.TASK_OUTPUT, content, sequenceNumber,
                null, null, null, null, taskInstanceId
        );
    }

    private static OrchestratorEventType determineEventType(MessageRole role) {
        return switch (role) {
            case TOOL_CALL -> OrchestratorEventType.CONVERSATION_TOOL_CALL;
            case TOOL_RESULT -> OrchestratorEventType.CONVERSATION_TOOL_RESULT;
            case FEEDBACK -> OrchestratorEventType.CONVERSATION_FEEDBACK_ADDED;
            default -> OrchestratorEventType.CONVERSATION_MESSAGE_ADDED;
        };
    }

    private static String formatEventMessage(ConversationMessage message) {
        return switch (message.getRole()) {
            case SYSTEM -> "System message added";
            case USER -> "User message added";
            case ASSISTANT -> "Assistant message added";
            case TOOL_CALL -> "Tool call: " + message.getToolName();
            case TOOL_RESULT -> "Tool result: " + message.getToolName();
            case TASK_OUTPUT -> "Task output added";
            case ERROR -> "Error recorded";
            case FEEDBACK -> "Feedback iteration " + message.getFeedbackIteration();
        };
    }
}
