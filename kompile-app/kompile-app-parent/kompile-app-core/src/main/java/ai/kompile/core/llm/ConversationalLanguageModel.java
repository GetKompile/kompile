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
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.List;

/**
 * Enhanced language model interface that integrates with chat memory for contextual conversations.
 * This extends the base LanguageModel interface to provide memory-aware conversation capabilities.
 * 
 * @author Kompile Inc.
 * @since 1.0.0
 */
public interface ConversationalLanguageModel extends LanguageModel {

    /**
     * Generates a response with conversation context from memory.
     * 
     * @param conversationId the unique identifier for the conversation
     * @param userQuery the user's input
     * @param context additional context to include in the prompt
     * @return the generated response
     */
    String generateConversationalResponse(String conversationId, String userQuery, List<String> context);

    /**
     * Generates a response with conversation context and potential tool calls.
     * 
     * @param conversationId the unique identifier for the conversation
     * @param userQuery the user's input
     * @param context additional context to include in the prompt
     * @return the chat response with potential tool calls
     */
    ChatResponse generateConversationalResponseWithToolCalls(String conversationId, String userQuery, List<String> context);

    /**
     * Clears the conversation history for a specific conversation.
     * 
     * @param conversationId the unique identifier for the conversation
     */
    void clearConversation(String conversationId);

    /**
     * Gets the chat memory instance used by this language model.
     * 
     * @return the chat memory instance
     */
    KompileChatMemory getChatMemory();

    /**
     * Gets the number of messages in a conversation.
     * 
     * @param conversationId the unique identifier for the conversation
     * @return the number of messages in the conversation
     */
    default int getConversationSize(String conversationId) {
        return getChatMemory().size(conversationId);
    }

    /**
     * Checks if a conversation exists and has messages.
     * 
     * @param conversationId the unique identifier for the conversation
     * @return true if the conversation exists, false otherwise
     */
    default boolean conversationExists(String conversationId) {
        return getChatMemory().exists(conversationId);
    }

    /**
     * Gets all active conversation IDs.
     * 
     * @return the list of conversation IDs that have messages
     */
    default List<String> getActiveConversations() {
        return getChatMemory().getActiveConversationIds();
    }
}
