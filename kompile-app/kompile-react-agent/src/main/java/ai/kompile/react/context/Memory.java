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
package ai.kompile.react.context;

import ai.kompile.react.model.ReActMessage;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Memory interface for storing and retrieving agent messages.
 * Implementations can provide different storage strategies (in-memory, graded, persistent).
 */
public interface Memory {

    /**
     * Add a message to memory.
     *
     * @param message The message to add
     */
    void add(ReActMessage message);

    /**
     * Add multiple messages to memory.
     *
     * @param messages The messages to add
     */
    void addAll(List<ReActMessage> messages);

    /**
     * Remove a message from memory by ID.
     *
     * @param messageId The message ID
     * @return true if the message was removed
     */
    boolean remove(UUID messageId);

    /**
     * Get all messages in memory.
     *
     * @return The list of messages
     */
    List<ReActMessage> getMessages();

    /**
     * Get a message by ID.
     *
     * @param messageId The message ID
     * @return The message, if found
     */
    Optional<ReActMessage> getMessage(UUID messageId);

    /**
     * Get the last N messages.
     *
     * @param n The number of messages to retrieve
     * @return The last N messages
     */
    List<ReActMessage> getLastMessages(int n);

    /**
     * Get messages by role.
     *
     * @param role The role to filter by
     * @return Messages with the specified role
     */
    List<ReActMessage> getMessagesByRole(ReActMessage.Role role);

    /**
     * Clear all messages from memory.
     */
    void clear();

    /**
     * Get the total number of messages.
     */
    int size();
}
