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
package ai.kompile.react.context.impl;

import ai.kompile.react.context.Memory;
import ai.kompile.react.model.ReActMessage;

import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

/**
 * Simple in-memory implementation of the Memory interface.
 * Uses a thread-safe list to store messages.
 */
public class InMemoryMemory implements Memory {

    private final List<ReActMessage> messages = new CopyOnWriteArrayList<>();

    @Override
    public void add(ReActMessage message) {
        if (message != null) {
            messages.add(message);
        }
    }

    @Override
    public void addAll(List<ReActMessage> messagesToAdd) {
        if (messagesToAdd != null) {
            for (ReActMessage message : messagesToAdd) {
                if (message != null) {
                    messages.add(message);
                }
            }
        }
    }

    @Override
    public boolean remove(UUID messageId) {
        return messages.removeIf(m -> messageId.equals(m.getId()));
    }

    @Override
    public List<ReActMessage> getMessages() {
        return new ArrayList<>(messages);
    }

    @Override
    public Optional<ReActMessage> getMessage(UUID messageId) {
        return messages.stream()
                .filter(m -> messageId.equals(m.getId()))
                .findFirst();
    }

    @Override
    public List<ReActMessage> getLastMessages(int n) {
        if (n <= 0) {
            return Collections.emptyList();
        }
        int size = messages.size();
        if (n >= size) {
            return new ArrayList<>(messages);
        }
        return new ArrayList<>(messages.subList(size - n, size));
    }

    @Override
    public List<ReActMessage> getMessagesByRole(ReActMessage.Role role) {
        return messages.stream()
                .filter(m -> m.getRole() == role)
                .collect(Collectors.toList());
    }

    @Override
    public void clear() {
        messages.clear();
    }

    @Override
    public int size() {
        return messages.size();
    }
}
