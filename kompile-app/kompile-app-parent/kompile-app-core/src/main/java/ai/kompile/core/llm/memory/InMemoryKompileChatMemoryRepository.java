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

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.ArrayList;
import java.util.Collections;
import java.util.stream.Collectors;

/**
 * In-memory implementation of KompileChatMemoryRepository that stores messages in a ConcurrentHashMap.
 * This implementation is suitable for development, testing, and single-instance deployments
 * where conversation persistence across application restarts is not required.
 * 
 * <p>Messages are stored in memory and will be lost when the application is restarted.
 * For production deployments requiring persistence, consider using a database-backed
 * implementation instead.</p>
 * 
 * @author Kompile Inc.
 * @since 1.0.0
 */
public class InMemoryKompileChatMemoryRepository implements KompileChatMemoryRepository {

    private final ConcurrentMap<String, List<Message>> conversations = new ConcurrentHashMap<>();

    @Override
    public void save(String conversationId, List<Message> messages) {
        if (messages == null || messages.isEmpty()) {
            conversations.remove(conversationId);
            return;
        }
        conversations.put(conversationId, new ArrayList<>(messages));
    }

    @Override
    public void add(String conversationId, List<Message> messages) {
        if (messages == null || messages.isEmpty()) {
            return;
        }
        
        conversations.compute(conversationId, (key, existingMessages) -> {
            List<Message> messageList = existingMessages != null ? 
                new ArrayList<>(existingMessages) : new ArrayList<>();
            messageList.addAll(messages);
            return messageList;
        });
    }

    @Override
    public List<Message> findByConversationId(String conversationId, int limit) {
        List<Message> messages = conversations.get(conversationId);
        if (messages == null || messages.isEmpty()) {
            return Collections.emptyList();
        }
        
        if (limit >= messages.size()) {
            return new ArrayList<>(messages);
        }
        
        // Return the last N messages (most recent)
        int fromIndex = Math.max(0, messages.size() - limit);
        return new ArrayList<>(messages.subList(fromIndex, messages.size()));
    }

    @Override
    public void deleteByConversationId(String conversationId) {
        conversations.remove(conversationId);
    }

    @Override
    public int countByConversationId(String conversationId) {
        List<Message> messages = conversations.get(conversationId);
        return messages != null ? messages.size() : 0;
    }

    @Override
    public boolean existsByConversationId(String conversationId) {
        List<Message> messages = conversations.get(conversationId);
        return messages != null && !messages.isEmpty();
    }

    @Override
    public List<String> findAllConversationIds() {
        return conversations.entrySet().stream()
            .filter(entry -> entry.getValue() != null && !entry.getValue().isEmpty())
            .map(ConcurrentMap.Entry::getKey)
            .collect(Collectors.toList());
    }

    @Override
    public int deleteConversationsBefore(long beforeTimestamp) {
        // For in-memory implementation, we don't track timestamps
        // This could be enhanced to include message timestamps in the future
        return 0;
    }

    /**
     * Gets the total number of active conversations.
     * 
     * @return the number of conversations with messages
     */
    public int getActiveConversationCount() {
        return (int) conversations.entrySet().stream()
            .filter(entry -> entry.getValue() != null && !entry.getValue().isEmpty())
            .count();
    }

    /**
     * Gets the total number of messages across all conversations.
     * 
     * @return the total message count
     */
    public int getTotalMessageCount() {
        return conversations.values().stream()
            .filter(messages -> messages != null)
            .mapToInt(List::size)
            .sum();
    }

    /**
     * Clears all conversations. Useful for testing and development.
     */
    public void clearAll() {
        conversations.clear();
    }
}
