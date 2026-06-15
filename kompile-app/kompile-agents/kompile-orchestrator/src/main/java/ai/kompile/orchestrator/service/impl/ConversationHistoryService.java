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
package ai.kompile.orchestrator.service.impl;

import ai.kompile.orchestrator.model.event.ConversationMessageEvent;
import ai.kompile.orchestrator.model.llm.ConversationMessage;
import ai.kompile.orchestrator.model.llm.ConversationMessage.MessageRole;
import ai.kompile.orchestrator.model.llm.LlmSession;
import ai.kompile.orchestrator.model.task.TaskInstance;
import ai.kompile.orchestrator.repository.ConversationMessageRepository;
import ai.kompile.orchestrator.repository.LlmSessionRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Service for managing conversation history and feedback loops.
 * This service stores and retrieves chat history for React Agent sessions,
 * enabling feedback loops where the agent can iteratively respond to task outputs.
 *
 * <p>Key features:
 * <ul>
 *   <li>Store complete conversation history for LLM sessions</li>
 *   <li>Track feedback loop iterations</li>
 *   <li>Associate messages with tasks and tool calls</li>
 *   <li>Provide conversation context for multi-turn interactions</li>
 *   <li>Support audit and debugging of agent behavior</li>
 * </ul>
 */
@Service
@Slf4j
@RequiredArgsConstructor
@NoArgsConstructor(access = AccessLevel.PROTECTED, force = true)
public class ConversationHistoryService {


    @Autowired
    private final ConversationMessageRepository messageRepository;
    @Autowired
    private final LlmSessionRepository sessionRepository;
    @Autowired
    private final ObjectMapper objectMapper;
    @Autowired
    private final ApplicationEventPublisher eventPublisher;

    // Track active feedback loops: sessionId -> current iteration
    private final Map<Long, Integer> feedbackIterations = new ConcurrentHashMap<>();

    // Maximum feedback loop iterations to prevent infinite loops
    private static final int MAX_FEEDBACK_ITERATIONS = 10;

    // ==================== Message Recording ====================

    /**
     * Record a system message in the conversation.
     */
    @Transactional
    public ConversationMessage recordSystemMessage(Long sessionId, String content) {
        ConversationMessage message = ConversationMessage.system(sessionId, content);
        message.setOrchestratorInstanceId(getOrchestratorInstanceId(sessionId));
        message.setSequenceNumber(getNextSequenceNumber(sessionId));
        ConversationMessage saved = messageRepository.save(message);
        publishMessageEvent(saved);
        return saved;
    }

    /**
     * Record a user message in the conversation.
     */
    @Transactional
    public ConversationMessage recordUserMessage(Long sessionId, String content) {
        ConversationMessage message = ConversationMessage.user(sessionId, content);
        message.setOrchestratorInstanceId(getOrchestratorInstanceId(sessionId));
        message.setSequenceNumber(getNextSequenceNumber(sessionId));
        ConversationMessage saved = messageRepository.save(message);
        publishMessageEvent(saved);
        return saved;
    }

    /**
     * Record an assistant response in the conversation.
     */
    @Transactional
    public ConversationMessage recordAssistantMessage(Long sessionId, String content, Integer tokenCount) {
        ConversationMessage message = ConversationMessage.assistant(sessionId, content);
        message.setOrchestratorInstanceId(getOrchestratorInstanceId(sessionId));
        message.setSequenceNumber(getNextSequenceNumber(sessionId));
        message.setTokenCount(tokenCount);
        ConversationMessage saved = messageRepository.save(message);
        publishMessageEvent(saved);
        return saved;
    }

    /**
     * Record a task output in the conversation.
     */
    @Transactional
    public ConversationMessage recordTaskOutput(Long sessionId, TaskInstance task) {
        ConversationMessage message = ConversationMessage.taskOutput(
                sessionId, task.getId(), task.getOutput());
        message.setOrchestratorInstanceId(task.getOrchestratorInstanceId());
        message.setSequenceNumber(getNextSequenceNumber(sessionId));
        ConversationMessage saved = messageRepository.save(message);
        publishMessageEvent(saved);
        return saved;
    }

    /**
     * Record a tool call in the conversation.
     */
    @Transactional
    public ConversationMessage recordToolCall(Long sessionId, String toolName,
                                               Map<String, Object> input) {
        ConversationMessage message = ConversationMessage.toolCall(
                sessionId, toolName, toJson(input));
        message.setOrchestratorInstanceId(getOrchestratorInstanceId(sessionId));
        message.setSequenceNumber(getNextSequenceNumber(sessionId));
        ConversationMessage saved = messageRepository.save(message);
        publishMessageEvent(saved);
        return saved;
    }

    /**
     * Record a tool result in the conversation.
     */
    @Transactional
    public ConversationMessage recordToolResult(Long sessionId, String toolName,
                                                 Object output) {
        ConversationMessage message = ConversationMessage.toolResult(
                sessionId, toolName, toJson(output));
        message.setOrchestratorInstanceId(getOrchestratorInstanceId(sessionId));
        message.setSequenceNumber(getNextSequenceNumber(sessionId));
        ConversationMessage saved = messageRepository.save(message);
        publishMessageEvent(saved);
        return saved;
    }

    /**
     * Record an error in the conversation.
     */
    @Transactional
    public ConversationMessage recordError(Long sessionId, String errorMessage) {
        ConversationMessage message = ConversationMessage.error(sessionId, errorMessage);
        message.setOrchestratorInstanceId(getOrchestratorInstanceId(sessionId));
        message.setSequenceNumber(getNextSequenceNumber(sessionId));
        ConversationMessage saved = messageRepository.save(message);
        publishMessageEvent(saved);
        return saved;
    }

    // ==================== Feedback Loop Management ====================

    /**
     * Start a new feedback loop for a session.
     * Returns true if the loop can be started, false if max iterations reached.
     */
    public boolean startFeedbackLoop(Long sessionId) {
        int currentIteration = feedbackIterations.getOrDefault(sessionId, 0);
        if (currentIteration >= MAX_FEEDBACK_ITERATIONS) {
            log.warn("Max feedback iterations ({}) reached for session {}",
                    MAX_FEEDBACK_ITERATIONS, sessionId);
            return false;
        }
        feedbackIterations.put(sessionId, currentIteration + 1);
        log.debug("Started feedback loop iteration {} for session {}",
                currentIteration + 1, sessionId);
        return true;
    }

    /**
     * Record a feedback message in the conversation.
     */
    @Transactional
    public ConversationMessage recordFeedback(Long sessionId, String content) {
        int iteration = feedbackIterations.getOrDefault(sessionId, 0);
        ConversationMessage message = ConversationMessage.feedback(sessionId, content, iteration);
        message.setOrchestratorInstanceId(getOrchestratorInstanceId(sessionId));
        message.setSequenceNumber(getNextSequenceNumber(sessionId));
        ConversationMessage saved = messageRepository.save(message);
        publishMessageEvent(saved);
        return saved;
    }

    /**
     * Get the current feedback iteration for a session.
     */
    public int getCurrentFeedbackIteration(Long sessionId) {
        return feedbackIterations.getOrDefault(sessionId, 0);
    }

    /**
     * End the feedback loop for a session.
     */
    public void endFeedbackLoop(Long sessionId) {
        feedbackIterations.remove(sessionId);
        log.debug("Ended feedback loop for session {}", sessionId);
    }

    /**
     * Check if session is in feedback loop.
     */
    public boolean isInFeedbackLoop(Long sessionId) {
        return feedbackIterations.containsKey(sessionId);
    }

    // ==================== Conversation Retrieval ====================

    /**
     * Get the full conversation history for a session.
     */
    public List<ConversationMessage> getConversationHistory(Long sessionId) {
        return messageRepository.findBySessionIdOrderBySequenceNumberAsc(sessionId);
    }

    /**
     * Get conversation history as formatted string for context.
     */
    public String getFormattedHistory(Long sessionId) {
        List<ConversationMessage> messages = getConversationHistory(sessionId);
        StringBuilder sb = new StringBuilder();

        for (ConversationMessage msg : messages) {
            switch (msg.getRole()) {
                case SYSTEM:
                    sb.append("[System]: ").append(msg.getContent()).append("\n\n");
                    break;
                case USER:
                    sb.append("[User]: ").append(msg.getContent()).append("\n\n");
                    break;
                case ASSISTANT:
                    sb.append("[Assistant]: ").append(msg.getContent()).append("\n\n");
                    break;
                case TASK_OUTPUT:
                    sb.append("[Task Output]: ").append(msg.getContent()).append("\n\n");
                    break;
                case TOOL_CALL:
                    sb.append("[Tool Call - ").append(msg.getToolName()).append("]: ")
                      .append(msg.getToolInput()).append("\n\n");
                    break;
                case TOOL_RESULT:
                    sb.append("[Tool Result - ").append(msg.getToolName()).append("]: ")
                      .append(msg.getToolOutput()).append("\n\n");
                    break;
                case ERROR:
                    sb.append("[Error]: ").append(msg.getContent()).append("\n\n");
                    break;
                case FEEDBACK:
                    sb.append("[Feedback #").append(msg.getFeedbackIteration()).append("]: ")
                      .append(msg.getContent()).append("\n\n");
                    break;
            }
        }

        return sb.toString();
    }

    /**
     * Get the latest N messages from a conversation.
     */
    public List<ConversationMessage> getLatestMessages(Long sessionId, int count) {
        Page<ConversationMessage> page = messageRepository.getLatestMessages(
                sessionId, PageRequest.of(0, count));
        List<ConversationMessage> messages = new ArrayList<>(page.getContent());
        Collections.reverse(messages); // Return in chronological order
        return messages;
    }

    /**
     * Get messages for an orchestrator instance.
     */
    public List<ConversationMessage> getMessagesForOrchestrator(String orchestratorInstanceId) {
        return messageRepository.findByOrchestratorInstanceIdOrderByTimestampDesc(orchestratorInstanceId);
    }

    /**
     * Get messages for an orchestrator instance, paged.
     */
    public Page<ConversationMessage> getMessagesForOrchestrator(String orchestratorInstanceId, Pageable pageable) {
        return messageRepository.findByOrchestratorInstanceIdOrderByTimestampDesc(orchestratorInstanceId, pageable);
    }

    /**
     * Get tool calls for a session.
     */
    public List<ConversationMessage> getToolCalls(Long sessionId) {
        return messageRepository.findToolCalls(sessionId);
    }

    /**
     * Get feedback messages for a session.
     */
    public List<ConversationMessage> getFeedbackMessages(Long sessionId) {
        return messageRepository.findBySessionIdAndFeedbackLoopTrueOrderByFeedbackIterationAsc(sessionId);
    }

    /**
     * Get message count for a session.
     */
    public long getMessageCount(Long sessionId) {
        return messageRepository.countBySessionId(sessionId);
    }

    /**
     * Get total token count for a session.
     */
    public Long getTotalTokenCount(Long sessionId) {
        return messageRepository.getTotalTokenCount(sessionId);
    }

    // ==================== Conversation Context Building ====================

    /**
     * Build conversation context for the next LLM call.
     * This includes relevant history to provide context for multi-turn interactions.
     */
    public String buildConversationContext(Long sessionId, int maxMessages) {
        List<ConversationMessage> recentMessages = getLatestMessages(sessionId, maxMessages);

        StringBuilder context = new StringBuilder();
        context.append("=== Conversation History ===\n\n");

        for (ConversationMessage msg : recentMessages) {
            String roleLabel = getRoleLabel(msg.getRole());
            context.append(roleLabel).append(": ");

            if (msg.getRole() == MessageRole.TOOL_CALL) {
                context.append("[Called ").append(msg.getToolName()).append("]");
            } else if (msg.getRole() == MessageRole.TOOL_RESULT) {
                context.append("[Result from ").append(msg.getToolName()).append("]");
            }

            context.append(truncate(msg.getContent(), 1000)).append("\n\n");
        }

        context.append("=== End History ===\n\n");
        return context.toString();
    }

    /**
     * Build a summary of the conversation for audit/display.
     */
    public ConversationSummary getConversationSummary(Long sessionId) {
        List<ConversationMessage> messages = getConversationHistory(sessionId);

        int userMessages = 0;
        int assistantMessages = 0;
        int toolCalls = 0;
        int errors = 0;
        int feedbackLoops = 0;
        Long totalTokens = 0L;

        ConversationMessage firstMessage = null;
        ConversationMessage lastMessage = null;

        for (ConversationMessage msg : messages) {
            if (firstMessage == null) firstMessage = msg;
            lastMessage = msg;

            switch (msg.getRole()) {
                case USER -> userMessages++;
                case ASSISTANT -> assistantMessages++;
                case TOOL_CALL -> toolCalls++;
                case ERROR -> errors++;
                case FEEDBACK -> feedbackLoops++;
            }

            if (msg.getTokenCount() != null) {
                totalTokens += msg.getTokenCount();
            }
        }

        return ConversationSummary.builder()
                .sessionId(sessionId)
                .totalMessages(messages.size())
                .userMessages(userMessages)
                .assistantMessages(assistantMessages)
                .toolCalls(toolCalls)
                .errors(errors)
                .feedbackLoops(feedbackLoops)
                .totalTokens(totalTokens)
                .startTime(firstMessage != null ? firstMessage.getTimestamp() : null)
                .endTime(lastMessage != null ? lastMessage.getTimestamp() : null)
                .build();
    }

    // ==================== Maintenance ====================

    /**
     * Delete conversation history for a session.
     */
    @Transactional
    public void deleteConversation(Long sessionId) {
        messageRepository.deleteBySessionId(sessionId);
        feedbackIterations.remove(sessionId);
        log.info("Deleted conversation history for session {}", sessionId);
    }

    /**
     * Clean up old conversations.
     */
    @Transactional
    public void cleanupOldConversations(LocalDateTime before) {
        messageRepository.deleteByTimestampBefore(before);
        log.info("Cleaned up conversations before {}", before);
    }

    // ==================== Helper Methods ====================

    /**
     * Publish a conversation message event for real-time streaming.
     */
    private void publishMessageEvent(ConversationMessage message) {
        try {
            ConversationMessageEvent event = ConversationMessageEvent.from(this, message);
            eventPublisher.publishEvent(event);
            log.debug("Published conversation message event: sessionId={}, role={}, seq={}",
                    message.getSessionId(), message.getRole(), message.getSequenceNumber());
        } catch (Exception e) {
            log.warn("Failed to publish conversation message event: {}", e.getMessage());
        }
    }

    private int getNextSequenceNumber(Long sessionId) {
        Integer maxSeq = messageRepository.getMaxSequenceNumber(sessionId);
        return (maxSeq != null ? maxSeq : 0) + 1;
    }

    private String getOrchestratorInstanceId(Long sessionId) {
        return sessionRepository.findById(sessionId)
                .map(LlmSession::getOrchestratorInstanceId)
                .orElse(null);
    }

    private String toJson(Object obj) {
        if (obj == null) return null;
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            log.debug("Failed to serialize to JSON", e);
            return obj.toString();
        }
    }

    private String getRoleLabel(MessageRole role) {
        return switch (role) {
            case SYSTEM -> "System";
            case USER -> "User";
            case ASSISTANT -> "Assistant";
            case TOOL_CALL -> "Tool Call";
            case TOOL_RESULT -> "Tool Result";
            case TASK_OUTPUT -> "Task Output";
            case ERROR -> "Error";
            case FEEDBACK -> "Feedback";
        };
    }

    private String truncate(String text, int maxLength) {
        if (text == null) return "";
        if (text.length() <= maxLength) return text;
        return text.substring(0, maxLength) + "...";
    }

    // ==================== DTOs ====================

    /**
     * Summary of a conversation.
     */
    @lombok.Data
    @lombok.Builder
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class ConversationSummary {
        private Long sessionId;
        private int totalMessages;
        private int userMessages;
        private int assistantMessages;
        private int toolCalls;
        private int errors;
        private int feedbackLoops;
        private Long totalTokens;
        private LocalDateTime startTime;
        private LocalDateTime endTime;
    }
}
