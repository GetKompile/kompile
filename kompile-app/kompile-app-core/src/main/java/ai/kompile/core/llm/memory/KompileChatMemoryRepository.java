/*
 *  Copyright 2025 Kompile Inc.
 *  *
 *  * Licensed under the Apache License, Version 2.0 (the "License");
 *  * you may not use this file except in compliance with the License.
 *  * You may obtain a copy of the License at
 *  *
 *  * http://www.apache.org/licenses/LICENSE-2.0
 *  *
 *  * Unless required by applicable law or agreed to in writing, software
 *  * distributed under the License is distributed on an "AS IS" BASIS,
 *  * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  * See the License for the specific language governing permissions and
 *  * limitations under the License.
 */

package ai.kompile.core.llm.memory;

import org.springframework.ai.chat.messages.Message;

import java.util.List;

/**
 * Repository abstraction for storing and retrieving chat conversation messages.
 * This interface provides the storage layer for chat memory implementations,
 * allowing different backends (in-memory, database, cache, etc.) to be used
 * for persistence.
 * 
 * <p>This abstraction separates the storage concerns from the memory management
 * logic, enabling pluggable storage solutions for different deployment scenarios.</p>
 * 
 * @author Kompile Inc.
 * @since 1.0.0
 */
public interface KompileChatMemoryRepository {

    /**
     * Saves messages for a specific conversation.
     * 
     * @param conversationId the unique identifier for the conversation
     * @param messages the messages to save
     */
    void save(String conversationId, List<Message> messages);

    /**
     * Adds new messages to an existing conversation.
     * 
     * @param conversationId the unique identifier for the conversation
     * @param messages the messages to add
     */
    void add(String conversationId, List<Message> messages);

    /**
     * Retrieves messages for a specific conversation with optional limit.
     * 
     * @param conversationId the unique identifier for the conversation
     * @param limit the maximum number of messages to retrieve (from most recent)
     * @return the list of messages, empty list if conversation doesn't exist
     */
    List<Message> findByConversationId(String conversationId, int limit);

    /**
     * Retrieves all messages for a specific conversation.
     * 
     * @param conversationId the unique identifier for the conversation
     * @return the list of all messages in the conversation
     */
    default List<Message> findByConversationId(String conversationId) {
        return findByConversationId(conversationId, Integer.MAX_VALUE);
    }

    /**
     * Deletes all messages for a specific conversation.
     * 
     * @param conversationId the unique identifier for the conversation
     */
    void deleteByConversationId(String conversationId);

    /**
     * Counts the number of messages in a conversation.
     * 
     * @param conversationId the unique identifier for the conversation
     * @return the number of messages in the conversation
     */
    int countByConversationId(String conversationId);

    /**
     * Checks if a conversation exists (has any messages).
     * 
     * @param conversationId the unique identifier for the conversation
     * @return true if the conversation exists, false otherwise
     */
    boolean existsByConversationId(String conversationId);

    /**
     * Retrieves all conversation IDs that have messages.
     * 
     * @return the list of active conversation IDs
     */
    List<String> findAllConversationIds();

    /**
     * Deletes conversations older than the specified timestamp.
     * Useful for cleanup operations.
     * 
     * @param beforeTimestamp messages before this timestamp will be deleted
     * @return the number of conversations deleted
     */
    int deleteConversationsBefore(long beforeTimestamp);
}
