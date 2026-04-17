/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ai.kompile.graph.neo4j.integration;

import ai.kompile.core.llm.memory.KompileChatMemory;
import org.springframework.ai.chat.messages.Message;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Test implementation of KompileChatMemory for integration tests.
 * Stores conversations in memory for verification.
 */
public class TestChatMemory implements KompileChatMemory {

    private final Map<String, List<Message>> conversations = new HashMap<>();

    @Override
    public void add(String conversationId, List<Message> messages) {
        conversations.computeIfAbsent(conversationId, k -> new ArrayList<>()).addAll(messages);
    }

    @Override
    public List<Message> get(String conversationId) {
        return new ArrayList<>(conversations.getOrDefault(conversationId, new ArrayList<>()));
    }

    @Override
    public List<Message> get(String conversationId, int lastN) {
        List<Message> all = get(conversationId);
        if (all.size() <= lastN) {
            return all;
        }
        return new ArrayList<>(all.subList(all.size() - lastN, all.size()));
    }

    @Override
    public void clear(String conversationId) {
        conversations.remove(conversationId);
    }

    @Override
    public int size(String conversationId) {
        return get(conversationId).size();
    }

    @Override
    public boolean exists(String conversationId) {
        return conversations.containsKey(conversationId) && !conversations.get(conversationId).isEmpty();
    }

    @Override
    public List<String> getActiveConversationIds() {
        return new ArrayList<>(conversations.keySet());
    }

    /**
     * Clear all conversations (useful for test cleanup).
     */
    public void clearAll() {
        conversations.clear();
    }

    /**
     * Get all conversations for verification.
     */
    public Map<String, List<Message>> getAllConversations() {
        return new HashMap<>(conversations);
    }
}
