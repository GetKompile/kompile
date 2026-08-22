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

package ai.kompile.cli.main.chat;

import ai.kompile.cli.common.util.JsonUtils;
import ai.kompile.utils.StringUtils;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Thread-safe queue for storing and managing chat messages.
 * Supports adding, removing, editing, and reordering queued messages.
 */
public class MessageQueue {

    /**
     * Represents a single queued message.
     */
    public static class QueuedMessage {
        private final String id;
        private final String content;
        private final Instant createdAt;
        private Instant updatedAt;
        private QueuedMessageStatus status;

        public enum QueuedMessageStatus {
            PENDING,
            EDITING,
            SENDING_NOW,
            FAILED
        }

        public QueuedMessage(String content) {
            this.id = UUID.randomUUID().toString().substring(0, 8);
            this.content = content;
            this.createdAt = Instant.now();
            this.updatedAt = this.createdAt;
            this.status = QueuedMessageStatus.PENDING;
        }

        @JsonCreator
        QueuedMessage(
                @JsonProperty("id") String id,
                @JsonProperty("content") String content,
                @JsonProperty("createdAt") Instant createdAt,
                @JsonProperty("updatedAt") Instant updatedAt,
                @JsonProperty("status") QueuedMessageStatus status) {
            this.id = id;
            this.content = content;
            this.createdAt = createdAt;
            this.updatedAt = updatedAt;
            this.status = status != null ? status : QueuedMessageStatus.PENDING;
        }

        public String getId() {
            return id;
        }

        public String getContent() {
            return content;
        }

        public Instant getCreatedAt() {
            return createdAt;
        }

        public Instant getUpdatedAt() {
            return updatedAt;
        }

        public QueuedMessageStatus getStatus() {
            return status;
        }

        public void setStatus(QueuedMessageStatus status) {
            this.status = status;
            this.updatedAt = Instant.now();
        }

        public void updateContent(String newContent) {
            // Content is immutable after creation for simplicity
            // If mutable content is needed, add setter here
            this.updatedAt = Instant.now();
        }
    }

    private final CopyOnWriteArrayList<QueuedMessage> queue;
    private final String sessionId;
    private final Path queueFile;
    private final ObjectMapper objectMapper;

    /**
     * Creates a new MessageQueue for the given session.
     *
     * @param sessionId the chat session ID
     */
    public MessageQueue(String sessionId) {
        this.sessionId = sessionId;
        this.queue = new CopyOnWriteArrayList<>();
        this.objectMapper = JsonUtils.standardMapper();
        this.queueFile = getQueueFilePath(sessionId);
        loadQueue();
    }

    /**
     * Adds a message to the end of the queue.
     *
     * @param content the message content
     * @return the queued message
     */
    public synchronized QueuedMessage enqueue(String content) {
        QueuedMessage message = new QueuedMessage(content);
        queue.add(message);
        saveQueue();
        return message;
    }

    /**
     * Removes a message from the queue by ID.
     *
     * @param id the message ID
     * @return true if removed, false if not found
     */
    public synchronized boolean remove(String id) {
        boolean removed = queue.removeIf(msg -> msg.getId().equals(id));
        if (removed) {
            saveQueue();
        }
        return removed;
    }

    /**
     * Replaces the content of a queued message while preserving its ID and status.
     *
     * @param id the message ID
     * @param content the new message content
     * @return true if updated, false if not found
     */
    public synchronized boolean update(String id, String content) {
        if (id == null || content == null) return false;
        for (int i = 0; i < queue.size(); i++) {
            QueuedMessage existing = queue.get(i);
            if (existing.getId().equals(id)) {
                QueuedMessage.QueuedMessageStatus nextStatus =
                        existing.getStatus() == QueuedMessage.QueuedMessageStatus.EDITING
                                ? QueuedMessage.QueuedMessageStatus.PENDING
                                : existing.getStatus();
                QueuedMessage replacement = new QueuedMessage(
                        existing.getId(),
                        content,
                        existing.getCreatedAt(),
                        Instant.now(),
                        nextStatus);
                queue.set(i, replacement);
                saveQueue();
                return true;
            }
        }
        return false;
    }

    /**
     * Marks one queued message as being edited without removing it from the
     * visible queue. Only one edit lease may be active at a time.
     */
    public synchronized boolean beginEdit(String id) {
        if (id == null || id.isBlank()) return false;
        for (QueuedMessage message : queue) {
            if (message.getStatus() == QueuedMessage.QueuedMessageStatus.EDITING) {
                return message.getId().equals(id);
            }
        }
        return replaceStatus(id, QueuedMessage.QueuedMessageStatus.EDITING);
    }

    /** Return an abandoned edit lease to the normal pending state. */
    public synchronized boolean cancelEdit(String id) {
        QueuedMessage message = get(id);
        if (message == null
                || message.getStatus() != QueuedMessage.QueuedMessageStatus.EDITING) {
            return false;
        }
        return replaceStatus(id, QueuedMessage.QueuedMessageStatus.PENDING);
    }

    /**
     * Moves a queued message to a zero-based position while preserving its ID,
     * timestamps, content, and status.
     */
    public synchronized boolean move(String id, int targetIndex) {
        if (id == null || id.isBlank() || queue.isEmpty()) return false;
        int sourceIndex = -1;
        for (int i = 0; i < queue.size(); i++) {
            if (queue.get(i).getId().equals(id)) {
                sourceIndex = i;
                break;
            }
        }
        if (sourceIndex < 0) return false;
        int destination = Math.max(0, Math.min(targetIndex, queue.size() - 1));
        if (sourceIndex == destination) return true;
        QueuedMessage message = queue.remove(sourceIndex);
        queue.add(destination, message);
        saveQueue();
        return true;
    }

    private boolean replaceStatus(String id, QueuedMessage.QueuedMessageStatus status) {
        for (int i = 0; i < queue.size(); i++) {
            QueuedMessage existing = queue.get(i);
            if (!existing.getId().equals(id)) continue;
            queue.set(i, new QueuedMessage(
                    existing.getId(), existing.getContent(), existing.getCreatedAt(),
                    Instant.now(), status));
            return true;
        }
        return false;
    }

    /**
     * Gets a message by ID.
     *
     * @param id the message ID
     * @return the message, or null if not found
     */
    public QueuedMessage get(String id) {
        return queue.stream()
                .filter(msg -> msg.getId().equals(id))
                .findFirst()
                .orElse(null);
    }

    /**
     * Gets all queued messages.
     *
     * @return list of queued messages
     */
    public List<QueuedMessage> getAll() {
        return new ArrayList<>(queue);
    }

    /**
     * Gets the first message in the queue (next to be sent).
     *
     * @return the first message, or null if queue is empty
     */
    public QueuedMessage peek() {
        return queue.isEmpty() ? null : queue.get(0);
    }

    /**
     * Removes and returns the first message from the queue.
     *
     * @return the first message, or null if queue is empty
     */
    public synchronized QueuedMessage dequeue() {
        if (queue.isEmpty()) {
            return null;
        }
        if (queue.get(0).getStatus() == QueuedMessage.QueuedMessageStatus.EDITING) {
            return null;
        }
        QueuedMessage message = queue.remove(0);
        saveQueue();
        return message;
    }

    /** Atomically claims a specific non-editing message for dispatch. */
    public synchronized QueuedMessage takeForSend(String id) {
        if (id == null || id.isBlank()) return null;
        for (int i = 0; i < queue.size(); i++) {
            QueuedMessage message = queue.get(i);
            if (!message.getId().equals(id)) continue;
            if (message.getStatus() == QueuedMessage.QueuedMessageStatus.EDITING) {
                return null;
            }
            queue.remove(i);
            saveQueue();
            return message;
        }
        return null;
    }

    /**
     * Clears all messages from the queue.
     */
    public synchronized void clear() {
        queue.clear();
        saveQueue();
    }

    /**
     * Returns the number of messages in the queue.
     *
     * @return the queue size
     */
    public int size() {
        return queue.size();
    }

    /**
     * Returns true if the queue is empty.
     *
     * @return true if empty
     */
    public boolean isEmpty() {
        return queue.isEmpty();
    }

    /**
     * Saves the queue to disk.
     */
    private void saveQueue() {
        try {
            String json = objectMapper.writeValueAsString(queue);
            Files.writeString(queueFile, json);
        } catch (IOException e) {
            // Silently fail - queue is in-memory primary
            System.err.println("Warning: Failed to save message queue: " + e.getMessage());
        }
    }

    /**
     * Loads the queue from disk.
     */
    private void loadQueue() {
        if (!Files.exists(queueFile)) {
            return;
        }
        try {
            String json = Files.readString(queueFile);
            List<QueuedMessage> loaded = objectMapper.readValue(json, new TypeReference<List<QueuedMessage>>() {});
            for (QueuedMessage message : loaded) {
                // EDITING is an in-memory lease. A process exit must never leave
                // a persisted queue permanently blocked on its next launch.
                if (message.getStatus() == QueuedMessage.QueuedMessageStatus.EDITING) {
                    queue.add(new QueuedMessage(
                            message.getId(), message.getContent(), message.getCreatedAt(),
                            message.getUpdatedAt(), QueuedMessage.QueuedMessageStatus.PENDING));
                } else {
                    queue.add(message);
                }
            }
        } catch (IOException e) {
            System.err.println("Warning: Failed to load message queue: " + e.getMessage());
        }
    }

    /**
     * Gets the path to the queue file for a session.
     *
     * @param sessionId the session ID
     * @return the path to the queue file
     */
    private Path getQueueFilePath(String sessionId) {
        // Store queue files in ~/.kompile/queues/
        String homeDir = System.getProperty("user.home");
        Path kompileDir = Path.of(homeDir, ".kompile");
        Path queuesDir = kompileDir.resolve("queues");
        
        if (!Files.exists(queuesDir)) {
            try {
                Files.createDirectories(queuesDir);
            } catch (IOException e) {
                // Fall back to temp directory
                queuesDir = Path.of(System.getProperty("java.io.tmpdir"), "kompile-queues");
                try {
                    Files.createDirectories(queuesDir);
                } catch (IOException ex) {
                    // Last resort: current directory
                    queuesDir = Path.of(".");
                }
            }
        }
        
        return queuesDir.resolve("queue-" + sessionId + ".json");
    }

    /**
     * Gets the status of the queue as a formatted string.
     *
     * @return formatted queue status
     */
    public String getStatus() {
        if (queue.isEmpty()) {
            return "Queue is empty";
        }
        
        StringBuilder sb = new StringBuilder();
        sb.append("Queue size: ").append(queue.size()).append(" message(s)\n");
        for (int i = 0; i < queue.size(); i++) {
            QueuedMessage msg = queue.get(i);
            sb.append("  ").append(i + 1).append(". [")
              .append(msg.getId()).append("] ")
              .append(StringUtils.truncate(msg.getContent(), 60))
              .append("\n");
        }
        return sb.toString();
    }

}
