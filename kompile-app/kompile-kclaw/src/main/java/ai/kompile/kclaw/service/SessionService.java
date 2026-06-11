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
package ai.kompile.kclaw.service;

import ai.kompile.react.model.ReActMessage;

import java.util.List;

/**
 * Service for managing conversation sessions with JSONL-based persistence.
 * Compatible with KClaw session format.
 */
public interface SessionService {

    /**
     * Load a session's message history from JSONL storage.
     *
     * @param sessionKey The unique session identifier
     * @return List of messages in the session, empty if session doesn't exist
     */
    List<ReActMessage> loadSession(String sessionKey);

    /**
     * Append a single message to a session.
     *
     * @param sessionKey The unique session identifier
     * @param message The message to append
     */
    void appendMessage(String sessionKey, ReActMessage message);

    /**
     * Append multiple messages to a session.
     *
     * @param sessionKey The unique session identifier
     * @param messages The messages to append
     */
    void appendMessages(String sessionKey, List<ReActMessage> messages);

    /**
     * Save the complete session, replacing existing content.
     *
     * @param sessionKey The unique session identifier
     * @param messages The complete message list
     */
    void saveSession(String sessionKey, List<ReActMessage> messages);

    /**
     * Compact old messages by summarizing them.
     * Called when session grows too large for context window.
     *
     * @param sessionKey The unique session identifier
     * @param maxTokens The maximum tokens to retain before compaction
     */
    void compactSession(String sessionKey, int maxTokens);

    /**
     * Clear a session, removing all messages.
     *
     * @param sessionKey The unique session identifier
     */
    void clearSession(String sessionKey);

    /**
     * Check if a session exists.
     *
     * @param sessionKey The unique session identifier
     * @return true if session exists and has messages
     */
    boolean sessionExists(String sessionKey);

    /**
     * Get the estimated token count for a session.
     *
     * @param sessionKey The unique session identifier
     * @return Estimated token count
     */
    int estimateTokenCount(String sessionKey);

    /**
     * List all session keys.
     *
     * @return List of session keys
     */
    List<String> listSessions();
}
