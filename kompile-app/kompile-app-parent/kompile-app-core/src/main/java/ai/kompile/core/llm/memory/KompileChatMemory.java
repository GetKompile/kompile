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

package ai.kompile.core.llm.memory;

import org.springframework.ai.chat.messages.Message;
import org.springframework.util.Assert;

import java.util.List;

/**
 * Kompile's abstraction for chat memory that interoperates with Spring AI's ChatMemory.
 * This interface provides methods to manage conversation history across multiple interactions
 * with language models, enabling contextual awareness in chat applications.
 * 
 * <p>This abstraction allows for different storage backends (in-memory, database, etc.)
 * while maintaining compatibility with Spring AI's chat memory system.</p>
 * 
 * @author Kompile Inc.
 * @since 1.0.0
 */
public interface KompileChatMemory {

    /**
     * The conversation ID parameter key used in advisors (matches Spring AI's constant).
     */
    String CONVERSATION_ID = "chat_memory_conversation_id";

    /**
     * Default conversation ID when none is specified (matches Spring AI's constant).
     */
    String DEFAULT_CONVERSATION_ID = "default";

    /**
     * Adds a single message to the conversation.
     * 
     * @param conversationId the unique identifier for the conversation
     * @param message the message to add
     */
    default void add(String conversationId, Message message) {
        Assert.hasText(conversationId, "conversationId cannot be null or empty");
        Assert.notNull(message, "message cannot be null");
        this.add(conversationId, List.of(message));
    }

    /**
     * Adds multiple messages to the conversation.
     * 
     * @param conversationId the unique identifier for the conversation
     * @param messages the list of messages to add
     */
    void add(String conversationId, List<Message> messages);

    /**
     * Retrieves all messages from the conversation.
     * 
     * @param conversationId the unique identifier for the conversation
     * @return the list of messages in the conversation
     */
    List<Message> get(String conversationId);

    /**
     * Retrieves the last N messages from the conversation.
     * This is a Kompile-specific extension for window-based memory management.
     * 
     * @param conversationId the unique identifier for the conversation
     * @param lastN the number of recent messages to retrieve
     * @return the list of recent messages
     */
    List<Message> get(String conversationId, int lastN);

    /**
     * Clears all messages from the conversation.
     * 
     * @param conversationId the unique identifier for the conversation
     */
    void clear(String conversationId);

    /**
     * Returns the total number of messages in the conversation.
     * This is a Kompile-specific extension.
     * 
     * @param conversationId the unique identifier for the conversation
     * @return the number of messages in the conversation
     */
    int size(String conversationId);

    /**
     * Checks if the conversation exists and has messages.
     * This is a Kompile-specific extension.
     * 
     * @param conversationId the unique identifier for the conversation
     * @return true if the conversation exists and has messages, false otherwise
     */
    boolean exists(String conversationId);

    /**
     * Gets all active conversation IDs.
     * This is a Kompile-specific extension.
     * 
     * @return the list of conversation IDs that have messages
     */
    List<String> getActiveConversationIds();
}
