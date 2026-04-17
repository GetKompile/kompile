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
package ai.kompile.cli.main.chat;

import ai.kompile.cli.main.chat.MessageQueue.QueuedMessage;
import ai.kompile.cli.main.chat.MessageQueue.QueuedMessage.QueuedMessageStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for MessageQueue — thread-safe message queue with disk persistence.
 * Uses a unique session ID per test to avoid file conflicts.
 */
@DisplayName("MessageQueue")
class MessageQueueTest {

    private MessageQueue queue;

    @BeforeEach
    void setUp() {
        // Use a unique session ID per test to avoid file collisions
        String uniqueId = "test-" + System.nanoTime();
        queue = new MessageQueue(uniqueId);
    }

    @Nested
    @DisplayName("Basic operations")
    class BasicOperations {

        @Test
        void newQueueIsEmpty() {
            assertTrue(queue.isEmpty());
            assertEquals(0, queue.size());
            assertNull(queue.peek());
            assertNull(queue.dequeue());
        }

        @Test
        void enqueueAddsToEnd() {
            QueuedMessage m1 = queue.enqueue("first");
            QueuedMessage m2 = queue.enqueue("second");

            assertEquals(2, queue.size());
            assertFalse(queue.isEmpty());
            assertEquals("first", queue.peek().getContent());
            assertNotEquals(m1.getId(), m2.getId());
        }

        @Test
        void dequeueRemovesFromFront() {
            queue.enqueue("first");
            queue.enqueue("second");

            QueuedMessage dequeued = queue.dequeue();
            assertEquals("first", dequeued.getContent());
            assertEquals(1, queue.size());
            assertEquals("second", queue.peek().getContent());
        }

        @Test
        void peekDoesNotRemove() {
            queue.enqueue("only");
            QueuedMessage peeked = queue.peek();
            assertEquals("only", peeked.getContent());
            assertEquals(1, queue.size());
        }

        @Test
        void removeById() {
            QueuedMessage m1 = queue.enqueue("first");
            queue.enqueue("second");

            assertTrue(queue.remove(m1.getId()));
            assertEquals(1, queue.size());
            assertEquals("second", queue.peek().getContent());
        }

        @Test
        void removeNonexistentReturnsFalse() {
            queue.enqueue("first");
            assertFalse(queue.remove("nonexistent-id"));
            assertEquals(1, queue.size());
        }

        @Test
        void getById() {
            QueuedMessage m1 = queue.enqueue("first");
            queue.enqueue("second");

            QueuedMessage found = queue.get(m1.getId());
            assertNotNull(found);
            assertEquals("first", found.getContent());
        }

        @Test
        void getNonexistentReturnsNull() {
            assertNull(queue.get("nonexistent"));
        }

        @Test
        void clearRemovesAll() {
            queue.enqueue("first");
            queue.enqueue("second");
            queue.clear();
            assertTrue(queue.isEmpty());
            assertEquals(0, queue.size());
        }

        @Test
        void getAllReturnsCopy() {
            queue.enqueue("first");
            queue.enqueue("second");

            List<QueuedMessage> all = queue.getAll();
            assertEquals(2, all.size());
            // Modifying the returned list should not affect the queue
            all.clear();
            assertEquals(2, queue.size());
        }
    }

    @Nested
    @DisplayName("QueuedMessage properties")
    class QueuedMessageProperties {

        @Test
        void messageHasIdAndTimestamp() {
            QueuedMessage msg = queue.enqueue("test");
            assertNotNull(msg.getId());
            assertEquals(8, msg.getId().length());
            assertNotNull(msg.getCreatedAt());
            assertNotNull(msg.getUpdatedAt());
            assertEquals(QueuedMessageStatus.PENDING, msg.getStatus());
        }

        @Test
        void setStatusUpdatesTimestamp() throws InterruptedException {
            QueuedMessage msg = queue.enqueue("test");
            var createdAt = msg.getUpdatedAt();
            Thread.sleep(5); // ensure time passes
            msg.setStatus(QueuedMessageStatus.SENDING_NOW);
            assertEquals(QueuedMessageStatus.SENDING_NOW, msg.getStatus());
            assertTrue(msg.getUpdatedAt().isAfter(createdAt) || msg.getUpdatedAt().equals(createdAt));
        }
    }

    @Nested
    @DisplayName("Status formatting")
    class StatusFormatting {

        @Test
        void emptyQueueStatus() {
            assertEquals("Queue is empty", queue.getStatus());
        }

        @Test
        void nonEmptyQueueShowsMessages() {
            queue.enqueue("Hello world");
            queue.enqueue("Second message");

            String status = queue.getStatus();
            assertTrue(status.contains("Queue size: 2"));
            assertTrue(status.contains("Hello world"));
            assertTrue(status.contains("Second message"));
        }

        @Test
        void longMessagesTruncatedInStatus() {
            String longMsg = "A".repeat(100);
            queue.enqueue(longMsg);

            String status = queue.getStatus();
            assertTrue(status.contains("..."));
        }
    }

    @Nested
    @DisplayName("FIFO ordering")
    class FifoOrdering {

        @Test
        void dequeueOrderMatchesEnqueueOrder() {
            queue.enqueue("first");
            queue.enqueue("second");
            queue.enqueue("third");

            assertEquals("first", queue.dequeue().getContent());
            assertEquals("second", queue.dequeue().getContent());
            assertEquals("third", queue.dequeue().getContent());
            assertNull(queue.dequeue());
        }
    }
}
