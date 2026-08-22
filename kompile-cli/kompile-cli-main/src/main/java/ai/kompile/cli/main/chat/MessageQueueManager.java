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

import ai.kompile.utils.StringUtils;
import ai.kompile.cli.main.chat.render.AsciiRenderer;
import ai.kompile.cli.main.chat.render.TerminalRenderer;

import java.util.List;

/**
 * Manages the message queue used to buffer user messages while the LLM is busy.
 * Extracted from ChatRepl to reduce its size.
 */
public class MessageQueueManager {

    private final ChatRepl repl;
    private final MessageQueue messageQueue;
    private final ChatMessageHandler messageHandler;
    private final BackgroundTaskManager backgroundTaskManager;
    private final ChatSessionMetrics sessionMetrics;
    private final TerminalRenderer renderer;
    private final AsciiRenderer ascii;
    private boolean autoDequeueEnabled;

    public MessageQueueManager(
            ChatRepl repl,
            MessageQueue messageQueue,
            ChatMessageHandler messageHandler,
            BackgroundTaskManager backgroundTaskManager,
            ChatSessionMetrics sessionMetrics,
            TerminalRenderer renderer,
            AsciiRenderer ascii,
            boolean autoDequeueEnabled) {
        this.repl = repl;
        this.messageQueue = messageQueue;
        this.messageHandler = messageHandler;
        this.backgroundTaskManager = backgroundTaskManager;
        this.sessionMetrics = sessionMetrics;
        this.renderer = renderer;
        this.ascii = ascii;
        this.autoDequeueEnabled = autoDequeueEnabled;
    }

    public MessageQueue getMessageQueue() {
        return messageQueue;
    }

    public boolean isAutoDequeueEnabled() {
        return autoDequeueEnabled;
    }

    public void setAutoDequeueEnabled(boolean value) {
        this.autoDequeueEnabled = value;
    }

    // ========================================================================
    // Queue management commands
    // ========================================================================

    /**
     * Adds a message to the queue.
     *
     * @param content the message content
     */
    public void enqueueMessage(String content) {
        if (content.isBlank()) {
            System.out.println("Usage: /queue <message>");
            System.out.println("Adds a message to the queue to be sent later.");
            return;
        }

        MessageQueue.QueuedMessage msg = messageQueue.enqueue(content);
        repl.requestStatusRedraw();
        System.out.println(renderer.green("Message queued [") + msg.getId() + renderer.green("]"));
        System.out.println(renderer.dim("  Use /queues to view, /queue-send to send now, /queue-remove <id> to cancel"));
        System.out.println();
    }

    /**
     * Lists all queued messages.
     */
    public void listQueuedMessages() {
        if (messageQueue.isEmpty()) {
            System.out.println(renderer.cyan("Queue is empty"));
            return;
        }

        StringBuilder body = new StringBuilder();
        body.append(renderer.bold(renderer.cyan("Queued Messages"))).append("\n\n");

        List<MessageQueue.QueuedMessage> messages = messageQueue.getAll();
        for (int i = 0; i < messages.size(); i++) {
            MessageQueue.QueuedMessage msg = messages.get(i);
            body.append(renderer.bold(String.valueOf(i + 1)))
                .append(". [")
                .append(renderer.cyan(msg.getId()))
                .append("] ")
                .append(StringUtils.truncate(msg.getContent(), 70))
                .append("\n");
        }

        body.append("\n").append(renderer.dim("Total: ")).append(messages.size()).append(" message(s)");
        System.out.println(ascii.panel("Message Queue", body.toString(), AsciiRenderer.ROUNDED, "cyan"));
        System.out.println();
        System.out.println(renderer.dim("Commands:"));
        System.out.println(renderer.dim("  /queue-send [id]     Send the next message (or specific ID)"));
        System.out.println(renderer.dim("  /queue-edit <id> <text>  Edit a queued message"));
        System.out.println(renderer.dim("  /queue-move <id> <n>     Move a message to position n"));
        System.out.println(renderer.dim("  /queue-send-all      Send all queued messages"));
        System.out.println(renderer.dim("  /queue-remove <id>   Remove a message from the queue"));
        System.out.println(renderer.dim("  /queue-clear         Clear all queued messages"));
        System.out.println();
    }

    /**
     * Sends the next queued message immediately.
     */
    public void sendNextQueuedMessage() {
        MessageQueue.QueuedMessage msg = messageQueue.peek();
        if (msg == null) {
            System.out.println(renderer.yellow("Queue is empty"));
            return;
        }
        if (repl.isLlmBusy()) {
            System.out.println(renderer.yellow("Current turn is still active; message remains queued [")
                    + msg.getId() + renderer.yellow("]"));
            System.out.println(renderer.dim("  It will be acknowledged and removed when dispatch actually starts."));
            return;
        }

        if (isBeingEdited(msg)) {
            System.out.println(renderer.yellow("Message is currently being edited [")
                    + msg.getId() + renderer.yellow("]"));
            return;
        }

        MessageQueue.QueuedMessage dequeued = messageHandler.dispatchNextQueuedMessage();
        if (dequeued == null) {
            System.out.println(renderer.yellow("Message is currently being edited [")
                    + msg.getId() + renderer.yellow("]"));
            return;
        }
        System.out.println(renderer.cyan("Sending queued message [")
                + dequeued.getId() + renderer.cyan("]"));
        repl.requestStatusRedraw();
    }

    /**
     * Sends a specific queued message by ID.
     *
     * @param id the message ID
     */
    public void sendQueuedMessageById(String id) {
        if (id.isBlank()) {
            System.out.println("Usage: /queue-send <id>");
            return;
        }

        MessageQueue.QueuedMessage msg = messageQueue.get(id);
        if (msg == null) {
            System.out.println(renderer.red("Message not found: ") + id);
            return;
        }
        if (repl.isLlmBusy()) {
            System.out.println(renderer.yellow("Current turn is still active; message remains queued [")
                    + id + renderer.yellow("]"));
            System.out.println(renderer.dim("  It will be acknowledged and removed when dispatch actually starts."));
            return;
        }

        if (isBeingEdited(msg)) {
            System.out.println(renderer.yellow("Message is currently being edited [")
                    + id + renderer.yellow("]"));
            return;
        }

        MessageQueue.QueuedMessage dequeued = messageHandler.dispatchQueuedMessage(id);
        if (dequeued == null) {
            System.out.println(renderer.yellow("Message is currently being edited or no longer queued [")
                    + id + renderer.yellow("]"));
            return;
        }
        System.out.println(renderer.cyan("Sending queued message [") + id + renderer.cyan("]"));
        repl.requestStatusRedraw();
    }

    /**
     * Sends all queued messages in order.
     */
    public void sendAllQueuedMessages() {
        if (messageQueue.isEmpty()) {
            System.out.println(renderer.yellow("Queue is empty"));
            return;
        }
        if (repl.isLlmBusy()) {
            System.out.println(renderer.yellow("Current turn is still active; the queue was not changed."));
            if (autoDequeueEnabled) {
                System.out.println(renderer.dim("  All queued messages will dispatch in order after it completes."));
            } else {
                System.out.println(renderer.dim("  Enable /auto-dequeue or run /queue-send-all again when idle."));
            }
            return;
        }
        MessageQueue.QueuedMessage first = messageQueue.peek();
        if (first != null && isBeingEdited(first)) {
            System.out.println(renderer.yellow("The upcoming message is being edited; queue order was not changed."));
            return;
        }

        int total = messageQueue.size();
        backgroundTaskManager.startQueueChain(total);
        System.out.println(renderer.cyan("Sending all " + total + " queued messages..."));
        System.out.println();

        // Start only the first turn. The asynchronous message handler keeps the
        // REPL readable, and ChatRepl's normal auto-dequeue hand-off drains the
        // remaining messages in order. Looping here would dequeue and immediately
        // re-enqueue every item while the first turn is busy.
        backgroundTaskManager.advanceQueueChain();
        MessageQueue.QueuedMessage msg = messageHandler.dispatchNextQueuedMessage();
        if (msg == null) {
            backgroundTaskManager.endQueueChain();
            return;
        }
        repl.requestStatusRedraw();
        System.out.println(renderer.dim("→ [1/" + total + "] Sending: ")
                + StringUtils.truncate(msg.getContent(), 55));
    }

    /**
     * Removes a message from the queue by ID.
     *
     * @param id the message ID
     */
    public void removeQueuedMessage(String id) {
        if (id.isBlank()) {
            System.out.println("Usage: /queue-remove <id>");
            return;
        }

        if (messageQueue.remove(id)) {
            repl.requestStatusRedraw();
            System.out.println(renderer.green("Removed message [") + id + renderer.green("]"));
        } else {
            System.out.println(renderer.red("Message not found: ") + id);
        }
    }

    /** Edit a queued message by ID without changing its queue position. */
    public void editQueuedMessage(String arguments) {
        String[] parts = arguments == null ? new String[0] : arguments.strip().split("\\s+", 2);
        if (parts.length < 2 || parts[0].isBlank() || parts[1].isBlank()) {
            System.out.println("Usage: /queue-edit <id> <new message>");
            System.out.println(renderer.dim("  Press ↑ on an empty prompt to edit the latest queued message interactively."));
            return;
        }
        if (messageQueue.update(parts[0], parts[1].strip())) {
            repl.requestStatusRedraw();
            System.out.println(renderer.green("Updated message [") + parts[0] + renderer.green("]"));
        } else {
            System.out.println(renderer.red("Message not found: ") + parts[0]);
        }
    }

    /** Move a queued message to a one-based queue position. */
    public void moveQueuedMessage(String arguments) {
        String[] parts = arguments == null ? new String[0] : arguments.strip().split("\\s+", 2);
        if (parts.length < 2) {
            System.out.println("Usage: /queue-move <id> <position>");
            return;
        }
        int position;
        try {
            position = Integer.parseInt(parts[1].strip());
        } catch (NumberFormatException e) {
            System.out.println(renderer.red("Queue position must be a number: ") + parts[1]);
            return;
        }
        if (position < 1 || position > messageQueue.size()) {
            System.out.println(renderer.red("Queue position must be between 1 and ") + messageQueue.size());
            return;
        }
        if (messageQueue.move(parts[0], position - 1)) {
            repl.requestStatusRedraw();
            System.out.println(renderer.green("Moved message [") + parts[0]
                    + renderer.green("] to position " + position));
        } else {
            System.out.println(renderer.red("Message not found: ") + parts[0]);
        }
    }

    /**
     * Clears all queued messages.
     */
    public void clearQueuedMessages() {
        messageQueue.clear();
        repl.requestStatusRedraw();
        System.out.println(renderer.green("Queue cleared"));
    }

    /**
     * Shows the queue status.
     */
    public void showQueueStatus() {
        System.out.println(messageQueue.getStatus());
    }

    /**
     * Toggles auto-dequeue on/off.
     */
    public void toggleAutoDequeue() {
        autoDequeueEnabled = !autoDequeueEnabled;
        if (autoDequeueEnabled) {
            System.out.println(renderer.green("✓ Auto-dequeue enabled"));
            System.out.println(renderer.dim("  Queued messages will send at the next model/tool boundary"));
            if (!messageQueue.isEmpty()) {
                System.out.println(renderer.dim("  " + messageQueue.size() + " message(s) in queue"));
            }
        } else {
            System.out.println(renderer.yellow("○ Auto-dequeue disabled"));
            System.out.println(renderer.dim("  Use /queue-send to manually send queued messages"));
            if (!messageQueue.isEmpty()) {
                System.out.println(renderer.dim("  " + messageQueue.size() + " message(s) waiting in queue"));
            }
        }
        System.out.println();
    }

    private static boolean isBeingEdited(MessageQueue.QueuedMessage message) {
        return message != null && message.getStatus()
                == MessageQueue.QueuedMessage.QueuedMessageStatus.EDITING;
    }

}
