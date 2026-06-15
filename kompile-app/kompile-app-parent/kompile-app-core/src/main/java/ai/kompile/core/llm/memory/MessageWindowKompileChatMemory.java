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
 * Message window-based implementation of KompileChatMemory that maintains a sliding window
 * of the most recent messages. This implementation automatically removes older messages
 * when the window size is exceeded, helping to stay within token limits for LLM calls.
 * 
 * <p>This implementation is inspired by Spring AI's MessageWindowChatMemory and provides
 * similar functionality while maintaining compatibility with Kompile's chat memory abstractions.</p>
 * 
 * @author Kompile Inc.
 * @since 1.0.0
 */
public class MessageWindowKompileChatMemory implements KompileChatMemory {

    private final KompileChatMemoryRepository repository;
    private final int maxMessages;

    /**
     * Default maximum number of messages to keep in the window.
     */
    public static final int DEFAULT_MAX_MESSAGES = 20;

    /**
     * Creates a new message window chat memory with the default maximum messages (20).
     * 
     * @param repository the repository for storing messages
     */
    public MessageWindowKompileChatMemory(KompileChatMemoryRepository repository) {
        this(repository, DEFAULT_MAX_MESSAGES);
    }

    /**
     * Creates a new message window chat memory with the specified maximum messages.
     * 
     * @param repository the repository for storing messages
     * @param maxMessages the maximum number of messages to keep in the window
     */
    public MessageWindowKompileChatMemory(KompileChatMemoryRepository repository, int maxMessages) {
        Assert.notNull(repository, "repository cannot be null");
        Assert.isTrue(maxMessages > 0, "maxMessages must be positive");
        this.repository = repository;
        this.maxMessages = maxMessages;
    }

    @Override
    public void add(String conversationId, List<Message> messages) {
        Assert.hasText(conversationId, "conversationId cannot be null or empty");
        Assert.notNull(messages, "messages cannot be null");
        
        if (messages.isEmpty()) {
            return;
        }

        // Add the new messages
        repository.add(conversationId, messages);

        // Enforce the window size limit
        enforceWindowSize(conversationId);
    }

    @Override
    public List<Message> get(String conversationId) {
        Assert.hasText(conversationId, "conversationId cannot be null or empty");
        return repository.findByConversationId(conversationId, maxMessages);
    }

    @Override
    public List<Message> get(String conversationId, int lastN) {
        Assert.hasText(conversationId, "conversationId cannot be null or empty");
        Assert.isTrue(lastN > 0, "lastN must be positive");
        
        int limit = Math.min(lastN, maxMessages);
        return repository.findByConversationId(conversationId, limit);
    }

    @Override
    public void clear(String conversationId) {
        Assert.hasText(conversationId, "conversationId cannot be null or empty");
        repository.deleteByConversationId(conversationId);
    }

    @Override
    public int size(String conversationId) {
        Assert.hasText(conversationId, "conversationId cannot be null or empty");
        return Math.min(repository.countByConversationId(conversationId), maxMessages);
    }

    @Override
    public boolean exists(String conversationId) {
        Assert.hasText(conversationId, "conversationId cannot be null or empty");
        return repository.existsByConversationId(conversationId);
    }

    @Override
    public List<String> getActiveConversationIds() {
        return repository.findAllConversationIds();
    }

    /**
     * Gets the maximum number of messages kept in the window.
     * 
     * @return the maximum messages
     */
    public int getMaxMessages() {
        return maxMessages;
    }

    /**
     * Gets the underlying repository used for storage.
     * 
     * @return the chat memory repository
     */
    public KompileChatMemoryRepository getRepository() {
        return repository;
    }

    /**
     * Enforces the window size limit by removing older messages if necessary.
     * 
     * @param conversationId the conversation to check
     */
    private void enforceWindowSize(String conversationId) {
        int currentSize = repository.countByConversationId(conversationId);
        
        if (currentSize > maxMessages) {
            // Get all messages and keep only the most recent ones
            List<Message> allMessages = repository.findByConversationId(conversationId);
            
            // Calculate how many messages to keep
            int fromIndex = Math.max(0, allMessages.size() - maxMessages);
            List<Message> messagesToKeep = allMessages.subList(fromIndex, allMessages.size());
            
            // Replace all messages with the windowed set
            repository.save(conversationId, messagesToKeep);
        }
    }

    /**
     * Builder for creating MessageWindowKompileChatMemory instances.
     */
    public static class Builder {
        private KompileChatMemoryRepository repository;
        private int maxMessages = DEFAULT_MAX_MESSAGES;

        /**
         * Sets the repository for storing messages.
         * 
         * @param repository the repository
         * @return this builder
         */
        public Builder repository(KompileChatMemoryRepository repository) {
            this.repository = repository;
            return this;
        }

        /**
         * Sets the maximum number of messages to keep in the window.
         * 
         * @param maxMessages the maximum messages (must be positive)
         * @return this builder
         */
        public Builder maxMessages(int maxMessages) {
            Assert.isTrue(maxMessages > 0, "maxMessages must be positive");
            this.maxMessages = maxMessages;
            return this;
        }

        /**
         * Builds the MessageWindowKompileChatMemory instance.
         * 
         * @return the configured chat memory
         * @throws IllegalStateException if repository is not set
         */
        public MessageWindowKompileChatMemory build() {
            Assert.notNull(repository, "Repository must be set");
            return new MessageWindowKompileChatMemory(repository, maxMessages);
        }
    }

    /**
     * Creates a new builder for MessageWindowKompileChatMemory.
     * 
     * @return a new builder
     */
    public static Builder builder() {
        return new Builder();
    }
}
