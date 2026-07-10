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
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Collections;

/**
 * Missing-bean implementation of ConversationalLanguageModel.
 * Model invocation fails loudly until a real conversational language model is configured.
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
                   "Initializing NoOpConversationalLanguageModelImpl. Invocations will fail until a real model is configured.");
    }

    @Override
    public String generateResponse(String userQuery, List<String> context) {
        throw notConfigured();
    }

    @Override
    public ChatResponse generateResponseWithPotentialToolCalls(String userQuery, List<String> context) {
        throw notConfigured();
    }

    @Override
    public String generateConversationalResponse(String conversationId, String userQuery, List<String> context) {
        throw notConfigured();
    }

    @Override
    public ChatResponse generateConversationalResponseWithToolCalls(String conversationId, String userQuery, List<String> context) {
        throw notConfigured();
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

    private IllegalStateException notConfigured() {
        return new IllegalStateException("ConversationalLanguageModel is not configured");
    }
}
