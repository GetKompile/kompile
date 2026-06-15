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

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

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
        this.objectMapper = new ObjectMapper();
        this.objectMapper.registerModule(new JavaTimeModule());
        this.objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        this.queueFile = getQueueFilePath(sessionId);
        loadQueue();
    }

    /**
     * Adds a message to the end of the queue.
     *
     * @param content the message content
     * @return the queued message
     */
    public QueuedMessage enqueue(String content) {
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
    public boolean remove(String id) {
        boolean removed = queue.removeIf(msg -> msg.getId().equals(id));
        if (removed) {
            saveQueue();
        }
        return removed;
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
    public QueuedMessage dequeue() {
        if (queue.isEmpty()) {
            return null;
        }
        QueuedMessage message = queue.remove(0);
        saveQueue();
        return message;
    }

    /**
     * Clears all messages from the queue.
     */
    public void clear() {
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
            queue.addAll(loaded);
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
              .append(truncate(msg.getContent(), 60))
              .append("\n");
        }
        return sb.toString();
    }

    /**
     * Truncates a string to the specified length.
     *
     * @param str the string to truncate
     * @param maxLength the maximum length
     * @return the truncated string
     */
    private String truncate(String str, int maxLength) {
        if (str == null || str.length() <= maxLength) {
            return str;
        }
        return str.substring(0, maxLength - 3) + "...";
    }
}
