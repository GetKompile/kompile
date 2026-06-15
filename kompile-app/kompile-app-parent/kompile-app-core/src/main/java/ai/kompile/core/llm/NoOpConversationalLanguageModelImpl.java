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

package ai.kompile.core.llm;

import ai.kompile.core.llm.memory.KompileChatMemory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Collections;

/**
 * No-operation implementation of ConversationalLanguageModel that provides placeholder functionality
 * when no actual language model is configured. This implementation logs warnings and returns
 * error messages for all operations.
 * 
 * @author Kompile Inc.
 * @since 1.0.0
 */
@Service
@ConditionalOnMissingBean(ConversationalLanguageModel.class)
public class NoOpConversationalLanguageModelImpl implements ConversationalLanguageModel {
    
    private static final Logger logger = LoggerFactory.getLogger(NoOpConversationalLanguageModelImpl.class);
    private final KompileChatMemory chatMemory;

    public NoOpConversationalLanguageModelImpl(KompileChatMemory chatMemory) {
        this.chatMemory = chatMemory;
        logger.warn("No specific ConversationalLanguageModel implementation found. " +
                   "Initializing NoOpConversationalLanguageModelImpl. LLM functionality will be disabled.");
    }

    @Override
    public String generateResponse(String userQuery, List<String> context) {
        String message = "Language Model is not configured. Cannot generate response.";
        logger.warn(message + " Query: " + userQuery);
        return "Error: " + message;
    }

    @Override
    public ChatResponse generateResponseWithPotentialToolCalls(String userQuery, List<String> context) {
        String message = "Language Model is not configured. Cannot generate response with tool calls.";
        logger.warn(message + " Query: " + userQuery);
        return createErrorResponse("Error: " + message);
    }

    @Override
    public String generateConversationalResponse(String conversationId, String userQuery, List<String> context) {
        String message = "Language Model is not configured. Cannot generate conversational response.";
        logger.warn(message + " Conversation: {}, Query: {}", conversationId, userQuery);
        return "Error: " + message;
    }

    @Override
    public ChatResponse generateConversationalResponseWithToolCalls(String conversationId, String userQuery, List<String> context) {
        String message = "Language Model is not configured. Cannot generate conversational response with tool calls.";
        logger.warn(message + " Conversation: {}, Query: {}", conversationId, userQuery);
        return createErrorResponse("Error: " + message);
    }

    @Override
    public void clearConversation(String conversationId) {
        logger.debug("NoOp: Clearing conversation {}", conversationId);
        if (chatMemory != null) {
            chatMemory.clear(conversationId);
        }
    }

    @Override
    public KompileChatMemory getChatMemory() {
        return chatMemory;
    }

    @Override
    public int getConversationSize(String conversationId) {
        return chatMemory != null ? chatMemory.size(conversationId) : 0;
    }

    @Override
    public boolean conversationExists(String conversationId) {
        return chatMemory != null && chatMemory.exists(conversationId);
    }

    @Override
    public List<String> getActiveConversations() {
        return chatMemory != null ? chatMemory.getActiveConversationIds() : Collections.emptyList();
    }

    /**
     * Creates an error response indicating that the language model is not configured.
     * 
     * @param errorMessage the error message
     * @return the error chat response
     */
    private ChatResponse createErrorResponse(String errorMessage) {
        Generation generation = new Generation(new AssistantMessage(errorMessage), null);
        return new ChatResponse(Collections.singletonList(generation));
    }
}
