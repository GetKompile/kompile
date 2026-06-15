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

package ai.kompile.cli.main.chat.enforcer;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Thread-safe rolling context buffer for enforcer decisions.
 */
public class EnforcerConversationWindow {

    public static final int DEFAULT_MAX_MESSAGES = 12;
    private static final int MAX_MESSAGE_CHARS = 12_000;
    private static final int PERSIST_INTERVAL_CHARS = 512;
    private static final long PERSIST_INTERVAL_MS = 1_000;

    private final Deque<EnforcerConversationContext.Message> messages = new ArrayDeque<>();
    private final int maxMessages;
    private final Path contextFile;
    private final ObjectMapper objectMapper;

    private int lastPersistChars;
    private long lastPersistMs;

    public EnforcerConversationWindow(Path contextFile, ObjectMapper objectMapper) {
        this(contextFile, objectMapper, DEFAULT_MAX_MESSAGES);
    }

    public EnforcerConversationWindow(Path contextFile, ObjectMapper objectMapper, int maxMessages) {
        this.contextFile = contextFile;
        this.objectMapper = objectMapper;
        this.maxMessages = Math.max(2, maxMessages);
        persist(true);
    }

    public synchronized void addUserMessage(String content) {
        addMessage("user", content);
        persist(true);
    }

    public synchronized void addSystemMessage(String content) {
        addMessage("system", content);
        persist(true);
    }

    public synchronized void addToolCall(String toolName, String input) {
        StringBuilder sb = new StringBuilder();
        sb.append(toolName == null || toolName.isBlank() ? "unknown" : toolName);
        if (input != null && !input.isBlank()) {
            sb.append("\n").append(input);
        }
        addMessage("tool_call", sb.toString());
        persist(true);
    }

    public synchronized void updateAssistantMessage(String content) {
        replaceOrAddTrailingAssistant(content);
        persist(false);
    }

    public synchronized void finishAssistantMessage(String content) {
        replaceOrAddTrailingAssistant(content);
        persist(true);
    }

    public synchronized EnforcerConversationContext snapshot() {
        return new EnforcerConversationContext(new ArrayList<>(messages));
    }

    public synchronized void persistNow() {
        persist(true);
    }

    private void replaceOrAddTrailingAssistant(String content) {
        String normalized = normalize(content);
        if (normalized.isBlank()) {
            return;
        }
        EnforcerConversationContext.Message replacement =
                new EnforcerConversationContext.Message("assistant", normalized);
        EnforcerConversationContext.Message last = messages.peekLast();
        if (last != null && "assistant".equals(last.role())) {
            messages.removeLast();
        }
        messages.addLast(replacement);
        trim();
    }

    private void addMessage(String role, String content) {
        String normalized = normalize(content);
        if (normalized.isBlank()) {
            return;
        }
        messages.addLast(new EnforcerConversationContext.Message(role, normalized));
        trim();
    }

    private void trim() {
        while (messages.size() > maxMessages) {
            messages.removeFirst();
        }
    }

    private String normalize(String text) {
        if (text == null) {
            return "";
        }
        String trimmed = text.trim();
        if (trimmed.length() <= MAX_MESSAGE_CHARS) {
            return trimmed;
        }
        return "... (truncated, " + trimmed.length() + " chars total)\n"
                + trimmed.substring(trimmed.length() - MAX_MESSAGE_CHARS);
    }

    private void persist(boolean force) {
        if (contextFile == null || objectMapper == null) {
            return;
        }
        long now = System.currentTimeMillis();
        int chars = totalChars();
        if (!force && Math.abs(chars - lastPersistChars) < PERSIST_INTERVAL_CHARS
                && now - lastPersistMs < PERSIST_INTERVAL_MS) {
            return;
        }
        snapshot().write(contextFile, objectMapper);
        lastPersistChars = chars;
        lastPersistMs = now;
    }

    private int totalChars() {
        int chars = 0;
        for (EnforcerConversationContext.Message message : messages) {
            chars += message.content().length();
        }
        return chars;
    }
}
