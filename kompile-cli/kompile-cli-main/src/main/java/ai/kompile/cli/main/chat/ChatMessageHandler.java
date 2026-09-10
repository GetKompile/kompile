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
import ai.kompile.cli.common.mcp.McpSseClient;
import ai.kompile.cli.main.chat.agent.AgenticChatLoop;
import ai.kompile.cli.main.chat.config.DirectLlmClient;
import ai.kompile.cli.main.chat.config.IdleTimeoutInputStream;
import ai.kompile.cli.main.chat.config.ProviderConnectivityPolicy;
import ai.kompile.cli.main.chat.render.AsciiRenderer;
import ai.kompile.cli.main.chat.render.StreamingMarkdownRenderer;
import ai.kompile.cli.main.chat.render.TerminalRenderer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

/**
 * Handles chat message dispatch: inline RAG, local/agentic chat, server streaming, and
 * SSE event parsing. Extracted from ChatRepl to reduce its size.
 */
public class ChatMessageHandler {

    private final ChatSessionContext sessionContext = ChatSessionContext.current();
    private final ChatRepl repl;
    private final McpSseClient mcpClient;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final String sessionId;
    private final boolean localMode;
    private final ChatHistory chatHistory;
    private final ChatMemory chatMemory;
    private final ChatSessionMetrics sessionMetrics;
    private final TerminalRenderer renderer;
    private final AsciiRenderer asciiRenderer;
    private final AgenticChatLoop agenticLoop;
    private final BackgroundTaskManager backgroundTaskManager;
    private final MessageQueue messageQueue;
    private final AtomicBoolean cancelSignal;
    private final List<ChatRepl.PendingAttachment> pendingAttachments;
    private final ReminderManager reminderManager;
    private final Object turnDispatchLock = new Object();
    /** Serializes synchronous crawl/headless turns without blocking cancellation. */
    private final Object synchronousTurnLock = new Object();
    private final ProviderConnectivityPolicy serverConnectivityPolicy =
            ProviderConnectivityPolicy.forProvider("kompile");
    private final AtomicReference<Thread> activeDispatchThread = new AtomicReference<>();
    private final AtomicReference<Thread> synchronousTurnOwner = new AtomicReference<>();
    private final AtomicReference<InputStream> activeResponseBody = new AtomicReference<>();
    private final AtomicReference<String> activeRemoteProcessId = new AtomicReference<>();
    private final AtomicBoolean turnActive = new AtomicBoolean();
    private final AtomicBoolean acceptingDispatches = new AtomicBoolean(true);
    private final AtomicBoolean acceptingExternalMessages = new AtomicBoolean(true);
    private final AtomicBoolean externalTurnActive = new AtomicBoolean(false);
    private final AtomicBoolean externalWorkClaimed = new AtomicBoolean(false);
    /** Mandatory judge feedback that must retain user role and outrank system wakeups. */
    private final ConcurrentLinkedDeque<String> mandatoryUserFeedback =
            new ConcurrentLinkedDeque<>();
    private final ConcurrentLinkedDeque<String> mandatoryExternalMessages =
            new ConcurrentLinkedDeque<>();
    /**
     * Input handed directly to a detached turn. These messages are no longer
     * durable queue entries: Ctrl+B explicitly released them for processing by
     * the active owner at its first safe model/tool boundary.
     */
    private final ConcurrentLinkedDeque<BackgroundInput> backgroundInputs =
            new ConcurrentLinkedDeque<>();

    private record BackgroundInput(String content, String formerQueueId) { }

    // Mutable llmBusy flag — read/written by ChatRepl main loop as well
    // We access it via ChatRepl accessors to keep a single source of truth.

    public ChatMessageHandler(
            ChatRepl repl,
            McpSseClient mcpClient,
            HttpClient httpClient,
            ObjectMapper objectMapper,
            String sessionId,
            boolean localMode,
            ChatHistory chatHistory,
            ChatMemory chatMemory,
            ChatSessionMetrics sessionMetrics,
            TerminalRenderer renderer,
            AsciiRenderer asciiRenderer,
            AgenticChatLoop agenticLoop,
            BackgroundTaskManager backgroundTaskManager,
            MessageQueue messageQueue,
            AtomicBoolean cancelSignal,
            List<ChatRepl.PendingAttachment> pendingAttachments,
            ReminderManager reminderManager) {
        this.repl = repl;
        this.mcpClient = mcpClient;
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
        this.sessionId = sessionId;
        this.localMode = localMode;
        this.chatHistory = chatHistory;
        this.chatMemory = chatMemory;
        this.sessionMetrics = sessionMetrics;
        this.renderer = renderer;
        this.asciiRenderer = asciiRenderer;
        this.agenticLoop = agenticLoop;
        this.backgroundTaskManager = backgroundTaskManager;
        this.messageQueue = messageQueue;
        this.cancelSignal = cancelSignal;
        this.pendingAttachments = pendingAttachments;
        this.reminderManager = reminderManager;
        ChatCompleter.setQueueSupplier(() -> this.messageQueue.getAll().stream()
                .map(MessageQueue.QueuedMessage::getContent)
                .collect(Collectors.toList()));
        this.agenticLoop.setQueuedMessageSupplier(this::claimPendingInputAtBoundary);
    }

    // ========================================================================
    // Entry point
    // ========================================================================

    /**
     * Handles a user chat message entered at the REPL prompt. A foreground
     * owner queues concurrent input; a backgrounded owner receives it through
     * the direct input lane instead.
     */
    public void handleChatMessage(String message) {
        if (!acceptingDispatches.get()) return;
        repl.initializeSessionTitleFromPrompt(message);
        // Crawl/headless runs deliberately stay synchronous so callers do not
        // tear down the transcript before the one requested turn completes.
        if (repl.isForceAgentic()) {
            runSynchronousAgenticTurn(message);
            return;
        }

        synchronized (turnDispatchLock) {
            if (!acceptingDispatches.get()) return;
            if (hasBackgroundedActiveTurn()) {
                acceptBackgroundInput(message, null);
                return;
            }
            dispatchTurn(message, () -> handleAcceptedChatMessage(message), "standard-chat-dispatch");
        }
    }

    private void runSynchronousAgenticTurn(String message) {
        synchronized (synchronousTurnLock) {
            Thread owner = Thread.currentThread();
            synchronized (turnDispatchLock) {
                if (!acceptingDispatches.get()) return;
                cancelSignal.set(false);
                repl.setLlmBusy(true);
                turnActive.set(true);
                synchronousTurnOwner.set(owner);
            }
            try {
                repl.syncPendingSessionTitle();
                handleAcceptedChatMessage(message);
            } finally {
                synchronized (turnDispatchLock) {
                    turnActive.set(false);
                    synchronousTurnOwner.compareAndSet(owner, null);
                    activeResponseBody.set(null);
                    activeRemoteProcessId.set(null);
                    repl.setLlmBusy(false);
                }
            }
        }
    }

    /** Deliver a system event even when ordinary queue auto-dequeue is disabled. */
    public void handleExternalMessage(String message) {
        if (message == null || message.isBlank() || repl.isForceAgentic()
                || !acceptingExternalMessages.get()) return;
        String normalized = message.strip();
        synchronized (turnDispatchLock) {
            if (!acceptingExternalMessages.get()) return;
            if (repl.isLlmBusy()) {
                if (mandatoryExternalMessages.contains(normalized)) return;
                mandatoryExternalMessages.add(normalized);
                ChatCompleter.showNotice(renderer.cyan(hasBackgroundedActiveTurn()
                        ? "  ↻ Completion event handed to background task"
                        : "  ↻ Completion event queued for agent"));
                repl.requestStatusRedraw();
                return;
            }
            dispatchTurn(normalized,
                    () -> handleAcceptedExternalMessage(normalized),
                    "standard-chat-process-wakeup");
        }
    }

    /**
     * Deliver judge feedback as an explicit user-authored turn. When another
     * turn owns the session, enqueue first and publish cancellation under the
     * same dispatch lock so the successor can never be cancelled by mistake.
     */
    public boolean handleUserFeedback(String message, boolean interrupt) {
        if (message == null || message.isBlank() || repl.isForceAgentic()) return false;
        String normalized = message.strip();
        synchronized (turnDispatchLock) {
            if (!acceptingDispatches.get()) return false;
            boolean activeTurn = turnActive.get() || repl.isLlmBusy()
                    || activeDispatchThread.get() != null
                    || synchronousTurnOwner.get() != null;
            if (activeTurn) {
                if (mandatoryUserFeedback.contains(normalized)) return true;
                mandatoryUserFeedback.add(normalized);
                ChatCompleter.showNotice(renderer.cyan(
                        "  ↻ Judge feedback queued as an interrupting user message"));
                repl.requestStatusRedraw();
                if (interrupt) requestCancel();
                return true;
            }
            dispatchTurn(normalized,
                    () -> handleAcceptedChatMessage(normalized),
                    "standard-chat-judge-feedback");
            return true;
        }
    }

    void startAcceptingExternalMessages() {
        synchronized (turnDispatchLock) {
            acceptingDispatches.set(true);
            acceptingExternalMessages.set(true);
        }
    }

    void stopAcceptingExternalMessages() {
        Thread externalOwner;
        synchronized (turnDispatchLock) {
            acceptingExternalMessages.set(false);
            mandatoryExternalMessages.clear();
            externalOwner = activeDispatchThread.get();
        }
        if (externalTurnActive.get() || externalWorkClaimed.get()) {
            requestCancel();
            if (externalOwner != null && externalOwner != Thread.currentThread()) {
                try {
                    externalOwner.join(2_000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    /** Stop all dispatch before the owning REPL closes shared session resources. */
    void shutdown() {
        synchronized (turnDispatchLock) {
            acceptingDispatches.set(false);
            mandatoryUserFeedback.clear();
            restoreUnclaimedBackgroundInputs();
        }
        stopAcceptingExternalMessages();
        Thread asyncOwner = activeDispatchThread.get();
        Thread synchronousOwner = synchronousTurnOwner.get();
        if ((asyncOwner == null || asyncOwner == Thread.currentThread())
                && (synchronousOwner == null || synchronousOwner == Thread.currentThread())) return;
        requestCancel();
        joinOwner(asyncOwner);
        if (synchronousOwner != asyncOwner) joinOwner(synchronousOwner);
    }

    private static void joinOwner(Thread owner) {
        if (owner == null || owner == Thread.currentThread()) return;
        try {
            owner.join(2_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Run a scheduler callback atomically with respect to session shutdown. */
    boolean runIfAcceptingDispatches(Runnable action) {
        if (action == null) return false;
        synchronized (turnDispatchLock) {
            if (!acceptingDispatches.get()) return false;
            action.run();
            return true;
        }
    }

    /** Serialize scheduler callbacks with synchronous interactive crawl turns. */
    boolean runScheduledDispatch(Runnable action) {
        if (!repl.isForceAgentic()) return runIfAcceptingDispatches(action);
        if (action == null) return false;
        synchronized (synchronousTurnLock) {
            if (!acceptingDispatches.get()) return false;
            action.run();
            return true;
        }
    }

    /** Atomically claim and reserve the next queued message for a new owner. */
    MessageQueue.QueuedMessage dispatchNextQueuedMessage() {
        return dispatchQueuedMessage(null);
    }

    /** Atomically claim and reserve a specific queued message for a new owner. */
    MessageQueue.QueuedMessage dispatchQueuedMessage(String id) {
        synchronized (turnDispatchLock) {
            if (!acceptingDispatches.get() || repl.isLlmBusy()) return null;
            MessageQueue.QueuedMessage claimed = id == null
                    ? messageQueue.dequeue() : messageQueue.takeForSend(id);
            if (claimed == null) return null;
            dispatchTurn(claimed.getContent(),
                    () -> handleAcceptedChatMessage(claimed.getContent()),
                    "standard-chat-queued-dispatch");
            return claimed;
        }
    }

    /** Runs one foreground turn on its own owner thread so Escape can interrupt it. */
    private void dispatchTurn(String message, Runnable action, String threadName) {
        synchronized (turnDispatchLock) {
            if (!acceptingDispatches.get()) return;
            if (repl.isLlmBusy()) {
                enqueueChatMessage(message);
                return;
            }

            // Reserve the turn before starting the worker. Without this, a fast
            // second Enter can race the worker and launch two model turns.
            repl.setLlmBusy(true);
            cancelSignal.set(false);
            turnActive.set(true);
            activeRemoteProcessId.set(null);
            Thread dispatchThread = new Thread(sessionContext.wrap(() -> {
                try {
                    repl.syncPendingSessionTitle();
                    action.run();
                } catch (Throwable uncaughtTurnFailure) {
                    // LinkageError and friends are not Exception: before this guard, an
                    // error thrown outside the per-tool catch (request assembly, history
                    // rebuild, stream wiring) escaped this thread and the turn died with
                    // zero terminal output — the "typed Continue, nothing happened" class
                    // of silent crash. Render it the same way as ordinary chat errors.
                    repl.stopGeneratingSpinner();
                    emitLine(renderer.red("Error in chat turn: "
                            + AgenticChatLoop.describeThrowable(uncaughtTurnFailure)));
                    chatHistory.logSystem("Uncaught turn failure: "
                            + uncaughtTurnFailure.getClass().getName() + ": "
                            + uncaughtTurnFailure.getMessage());
                } finally {
                    releaseTurnOwnershipAndHandOff();
                }
            }), threadName);
            dispatchThread.setDaemon(true);
            activeDispatchThread.set(dispatchThread);
            dispatchThread.start();
        }
    }

    /**
     * Shared turn-owner release: clears the reservation and hands pending queue
     * work to the next owner. Called from the worker thread's finally block so
     * every accepted turn — chat or maintenance — releases state identically.
     */
    private void releaseTurnOwnershipAndHandOff() {
        turnActive.set(false);
        synchronized (turnDispatchLock) {
            boolean ownerReleased = activeDispatchThread.compareAndSet(
                    Thread.currentThread(), null);
            if (ownerReleased) {
                activeResponseBody.set(null);
                activeRemoteProcessId.set(null);
                repl.setLlmBusy(false);
                // Queue hand-off happens under the same reservation lock
                // only after this owner can no longer be overwritten.
                boolean feedbackDispatched = dispatchPendingUserFeedbackAfterTurnRelease();
                boolean externalDispatched = !feedbackDispatched && acceptingExternalMessages.get()
                        && dispatchPendingExternalAfterTurnRelease();
                boolean backgroundInputDispatched = !feedbackDispatched && !externalDispatched
                        && dispatchPendingBackgroundInputAfterTurnRelease();
                if (acceptingDispatches.get() && !feedbackDispatched && !externalDispatched
                        && !backgroundInputDispatched) {
                    repl.dispatchQueuedMessageAfterTurnRelease();
                }
            }
        }
    }

    /**
     * Runs a maintenance action (e.g. /compact) through the same turn lifecycle
     * as a chat turn. Compaction must never execute on the REPL reader thread:
     * the point-in-time isLlmBusy() guard there misses backgrounded turns
     * (llmBusy is false while a detached turn still owns the model/history),
     * and a reader-thread LLM call cannot be cancelled because no dispatch
     * owner is registered — a slow or wedged summarization call deadlocks the
     * whole session. Dispatching it as a reserved turn (a) atomically re-checks
     * occupancy under the dispatch lock so a turn starting between the check
     * and the call cannot race the compaction, and (b) registers the worker as
     * the cancel/interrupt owner so Escape can always break a hung call.
     *
     * @return true when the action was accepted for execution, false when a
     *         turn (foreground or backgrounded) is active and the caller must
     *         retry after it finishes.
     */
    public boolean dispatchMaintenanceTurn(Runnable action, String threadName) {
        if (!acceptingDispatches.get()) return false;
        if (repl.isForceAgentic()) {
            // Headless runs stay synchronous by contract, mirroring chat turns.
            cancelSignal.set(false);
            turnActive.set(true);
            try {
                action.run();
                return true;
            } finally {
                turnActive.set(false);
            }
        }
        synchronized (turnDispatchLock) {
            if (!acceptingDispatches.get()) return false;
            // turnActive stays true while a Ctrl+B-backgrounded turn still owns
            // the model/tools even though llmBusy is false — check both plus the
            // registered owner to close the race the busy flag alone misses.
            if (repl.isLlmBusy() || turnActive.get() || activeDispatchThread.get() != null) {
                return false;
            }
            repl.setLlmBusy(true);
            cancelSignal.set(false);
            turnActive.set(true);
            Thread dispatchThread = new Thread(sessionContext.wrap(() -> {
                try {
                    action.run();
                } catch (Throwable uncaughtTurnFailure) {
                    // LinkageError and friends are not Exception: before this guard, an
                    // error thrown outside the per-tool catch (request assembly, history
                    // rebuild, stream wiring) escaped this thread and the turn died with
                    // zero terminal output — the "typed Continue, nothing happened" class
                    // of silent crash. Render it the same way as ordinary chat errors.
                    repl.stopGeneratingSpinner();
                    emitLine(renderer.red("Error in chat turn: "
                            + AgenticChatLoop.describeThrowable(uncaughtTurnFailure)));
                    chatHistory.logSystem("Uncaught turn failure: "
                            + uncaughtTurnFailure.getClass().getName() + ": "
                            + uncaughtTurnFailure.getMessage());
                } finally {
                    releaseTurnOwnershipAndHandOff();
                }
            }), threadName);
            dispatchThread.setDaemon(true);
            activeDispatchThread.set(dispatchThread);
            dispatchThread.start();
            return true;
        }
    }

    /**
     * Cancels the currently accepted turn. The shared signal cooperatively stops
     * stream parsers and tools; interrupting the owner thread also wakes blocking
     * HTTP sends and process waits immediately.
     */
    public boolean requestCancel() {
        Thread active;
        Thread synchronous;
        String processId;
        synchronized (turnDispatchLock) {
            // Keep the old owner reserved until every local cancellation signal is
            // published. Its release callback cannot start a successor that these
            // operations would accidentally cancel.
            active = activeDispatchThread.get();
            synchronous = synchronousTurnOwner.get();
            processId = activeRemoteProcessId.getAndSet(null);
            InputStream responseBody = activeResponseBody.get();
            boolean accepted = turnActive.get() || active != null || synchronous != null
                    || responseBody != null
                    || (processId != null && !processId.isBlank());
            if (!accepted) {
                return false;
            }
            cancelSignal.set(true);
            ChatCompleter.markInterrupted();
            repl.requestStatusRedraw();
            agenticLoop.cancelActiveTurn();
            responseBody = activeResponseBody.getAndSet(null);
            if (responseBody != null) {
                try {
                    responseBody.close();
                } catch (IOException ignored) {
                    // The owner thread will observe the cancellation signal.
                }
            }
            if (active != null && active != Thread.currentThread()) {
                active.interrupt();
            }
            if (synchronous != null && synchronous != active
                    && synchronous != Thread.currentThread()) {
                synchronous.interrupt();
            }
        }
        if (processId != null && !processId.isBlank()) {
            cancelRemoteProcess(processId);
        }
        return true;
    }

    /**
     * Detach the blocking subagent worker without cancelling it. The parent
     * resumes with an explicit pending tool result, so queued and fresh input
     * reach a model boundary without waiting for that worker to finish.
     */
    public boolean requestBackground() {
        synchronized (turnDispatchLock) {
            if (!turnActive.get()) {
                return false;
            }
            BackgroundTaskManager.BackgroundTask task = backgroundTaskManager.requestBackground();
            if (task == null) {
                return false;
            }

            int released = releaseQueuedInputToBackgroundTurn();
            task.appendOutput("\n[Backgrounded; input is processed directly at the next safe boundary]"
                    + (released > 0 ? " [released " + released + " queued message(s)]" : "")
                    + "\n");
            agenticLoop.backgroundActiveTurn(task::appendOutput, () -> {
                synchronized (turnDispatchLock) {
                    backgroundTaskManager.detachTask(task);
                    backgroundTaskManager.startTask("Parent conversation (background task continues)");
                    agenticLoop.clearBackgroundOutput();
                }
            }, result -> {
                task.appendOutput("\n" + result.getOutput() + "\n");
                backgroundTaskManager.completeDetachedTask(task, result.isError()
                        ? new IllegalStateException(result.getOutput()) : null);
            });
            repl.stopGeneratingSpinner();
            ChatCompleter.setActivity(null);
            repl.requestStatusRedraw();
            sessionMetrics.recordTaskBackgrounded();
            return true;
        }
    }

    int pendingBackgroundInputCount() {
        return backgroundInputs.size();
    }

    private void cancelRemoteProcess(String processId) {
        try {
            HttpRequest cancelRequest = HttpRequest.newBuilder()
                    .uri(URI.create(repl.getBaseUrl() + "/api/agents/chat/cancel/" + processId))
                    .POST(HttpRequest.BodyPublishers.noBody())
                    .timeout(Duration.ofSeconds(5))
                    .build();
            httpClient.sendAsync(cancelRequest, HttpResponse.BodyHandlers.discarding());
        } catch (Exception ignored) {
            // Local cancellation remains effective even if the remote cleanup request fails.
        }
    }

    private void enqueueChatMessage(String message) {
        if (messageQueue.enqueue(message) == null) return;
        repl.requestStatusRedraw();
        sessionMetrics.recordMessageQueued();
        int queueSize = messageQueue.size();
        ChatCompleter.showNotice(renderer.yellow("  ⏳ Queued ")
                + renderer.dim("(" + queueSize + " pending) → ") + StringUtils.truncate(message, 60)
                + renderer.dim(repl.isAutoDequeueEnabled()
                        ? " · Will auto-send at the next model/tool boundary"
                        : " · Use /queue-send to send manually"));
        // MessageQueue is the durable source until dispatch accepts this input.
        // Logging it here and again at acceptance duplicates context on resume.
    }

    private void handleAcceptedChatMessage(String message) {
        sessionMetrics.recordUserTurn(message);
        boolean workflowForServer = !localMode && agenticLoop.isWorkflowActive();
        if (!repl.isForceAgentic() && !workflowForServer) {
            // Record exactly what the model will receive, reminder block included.
            // previewUserTurn does not tick; the send boundary owns the interval counter.
            chatHistory.logUserMessage(reminderManager == null
                    ? message : reminderManager.previewUserTurn(message));
        }

        try {
            if (repl.isForceAgentic() || workflowForServer) {
                // Crawl and workflow profiles require the local agentic owner so tool
                // prerequisites can be checked before execution.
                runAgenticChat(message);
            } else if (localMode) {
                // In local mode, all messages go through the agentic loop
                handleLocalChat(message);
            } else {
                handleServerChat(message);
            }
        } finally {
            setActivityAfterTurn();
            repl.requestStatusRedraw();
            completeAcceptedTurn();
        }
    }

    private void handleAcceptedExternalMessage(String message) {
        if (!acceptingExternalMessages.get()) {
            repl.completeTaskWithoutAutoDequeue();
            return;
        }
        externalTurnActive.set(true);
        if (!acceptingExternalMessages.get()) {
            externalTurnActive.set(false);
            repl.completeTaskWithoutAutoDequeue();
            return;
        }
        chatHistory.logSystem(message);
        try {
            if (localMode) handleLocalChat(message);
            else handleServerChat(message);
        } finally {
            externalTurnActive.set(false);
            setActivityAfterTurn();
            repl.requestStatusRedraw();
            completeAcceptedTurn();
        }
    }

    private boolean dispatchPendingUserFeedbackAfterTurnRelease() {
        if (!acceptingDispatches.get()) {
            mandatoryUserFeedback.clear();
            return false;
        }
        String message = mandatoryUserFeedback.poll();
        if (message == null) return false;
        dispatchTurn(message,
                () -> handleAcceptedChatMessage(message),
                "standard-chat-judge-feedback");
        return true;
    }

    private boolean dispatchPendingExternalAfterTurnRelease() {
        if (!acceptingExternalMessages.get()) {
            mandatoryExternalMessages.clear();
            return false;
        }
        String message = mandatoryExternalMessages.poll();
        if (message == null) return false;
        if (!acceptingExternalMessages.get()) return false;
        dispatchTurn(message,
                () -> handleAcceptedExternalMessage(message),
                "standard-chat-process-wakeup");
        return true;
    }

    /** Dispatch input that a detached owner could not consume before it ended. */
    private boolean dispatchPendingBackgroundInputAfterTurnRelease() {
        if (!acceptingDispatches.get()) return false;
        BackgroundInput input = backgroundInputs.poll();
        if (input == null) return false;
        if (input.formerQueueId() != null) {
            sessionMetrics.recordMessageAutoDequeued();
        }
        dispatchTurn(input.content(),
                () -> handleAcceptedChatMessage(input.content()),
                "standard-chat-background-successor");
        return true;
    }

    /**
     * Completes one accepted turn without launching its successor. dispatchTurn's
     * owner-release callback claims mandatory judge feedback first, process events
     * second, direct background input third, and ordinary queued input last.
     */
    private void completeAcceptedTurn() {
        synchronized (turnDispatchLock) {
            if (cancelSignal.get()) {
                repl.completeTaskWithoutAutoDequeue();
            } else {
                repl.completeTaskWithAutoDequeue();
            }
            agenticLoop.clearBackgroundOutput();
            externalWorkClaimed.set(false);
        }
    }

    /** Show the user the reminder block attached to an outbound prompt, if any. */
    private void emitReminderSection(String outboundMessage) {
        String section = renderer.renderReminderSection(
                ReminderManager.reminderBlockContent(outboundMessage));
        if (!section.isEmpty()) {
            emitLine(section);
        }
    }

    private void emitLine(String line) {
        BackgroundTaskManager.BackgroundTask task = backgroundTaskManager.getCurrentTask();
        if (task != null && task.getStatus()
                == BackgroundTaskManager.BackgroundTask.BackgroundTaskStatus.BACKGROUNDED) {
            task.appendOutput((line == null ? "" : line) + System.lineSeparator());
            return;
        }
        ChatCompleter.printAbove(line);
    }

    private void appendFinalTaskOutput(
            BackgroundTaskManager.BackgroundTask task, String response) {
        if (task == null || response == null || response.isEmpty()) {
            return;
        }
        // Background streaming/tool rendering is already retained incrementally.
        // Foreground tasks still need their final response copied into job history.
        if (!task.wasBackgrounded() || task.getOutput().isBlank()) {
            task.appendOutput(response);
        }
    }

    private void setForegroundActivity(String activity) {
        BackgroundTaskManager.BackgroundTask task = backgroundTaskManager.getCurrentTask();
        if (task == null || task.getStatus()
                != BackgroundTaskManager.BackgroundTask.BackgroundTaskStatus.BACKGROUNDED) {
            ChatCompleter.setActivity(activity);
        }
    }

    /** Atomically claim the highest-priority pending input for the active agent loop. */
    private AgenticChatLoop.QueuedInput claimPendingInputAtBoundary() {
        synchronized (turnDispatchLock) {
            if (!acceptingDispatches.get()) return null;
            String feedback = mandatoryUserFeedback.poll();
            if (feedback != null) {
                return new AgenticChatLoop.QueuedInput(feedback, () -> {
                    synchronized (turnDispatchLock) {
                        if (cancelSignal.get() || !acceptingDispatches.get()) return false;
                        sessionMetrics.recordUserTurn(feedback);
                        chatHistory.logUserMessage(reminderManager == null
                                ? feedback : reminderManager.previewUserTurn(feedback));
                        ChatCompleter.showNotice(renderer.cyan(
                                "  ↻ Applying judge feedback as user guidance"));
                        repl.requestStatusRedraw();
                        return true;
                    }
                }, () -> {
                    synchronized (turnDispatchLock) {
                        if (acceptingDispatches.get()) {
                            mandatoryUserFeedback.remove(feedback);
                            mandatoryUserFeedback.addFirst(feedback);
                        }
                    }
                });
            }
            if (!acceptingExternalMessages.get()) {
                mandatoryExternalMessages.clear();
            }
            String external = mandatoryExternalMessages.poll();
            if (external != null && acceptingExternalMessages.get()) {
                return new AgenticChatLoop.QueuedInput(external, () -> {
                    synchronized (turnDispatchLock) {
                        if (cancelSignal.get() || !acceptingExternalMessages.get()) {
                            return false;
                        }
                        externalWorkClaimed.set(true);
                        chatHistory.logSystem(external);
                        ChatCompleter.showNotice(renderer.cyan(
                                "  ↻ Applying completion event at agent boundary"));
                        repl.requestStatusRedraw();
                        return true;
                    }
                }, () -> {
                    synchronized (turnDispatchLock) {
                        externalWorkClaimed.set(false);
                        if (acceptingExternalMessages.get()) {
                            mandatoryExternalMessages.remove(external);
                            mandatoryExternalMessages.addFirst(external);
                        }
                    }
                });
            }
            BackgroundInput backgroundInput = backgroundInputs.poll();
            if (backgroundInput != null) {
                return new AgenticChatLoop.QueuedInput(backgroundInput.content(), () -> {
                    synchronized (turnDispatchLock) {
                        if (cancelSignal.get() || !acceptingDispatches.get()) return false;
                        if (backgroundInput.formerQueueId() != null) {
                            sessionMetrics.recordMessageAutoDequeued();
                        }
                        sessionMetrics.recordUserTurn(backgroundInput.content());
                        chatHistory.logUserMessage(reminderManager == null
                                ? backgroundInput.content()
                                : reminderManager.previewUserTurn(backgroundInput.content()));
                        repl.requestStatusRedraw();
                        ChatCompleter.showNotice(renderer.cyan(
                                        "  ↪ Processing input immediately after backgrounding")
                                + (backgroundInput.formerQueueId() == null ? ""
                                        : renderer.dim(" [" + backgroundInput.formerQueueId() + "]")));
                        return true;
                    }
                }, () -> {
                    synchronized (turnDispatchLock) {
                        if (acceptingDispatches.get()) {
                            backgroundInputs.removeIf(input -> input.content().equals(backgroundInput.content()));
                            backgroundInputs.addFirst(backgroundInput);
                            repl.requestStatusRedraw();
                        } else {
                            messageQueue.enqueue(backgroundInput.content());
                        }
                    }
                });
            }
            if (!repl.isAutoDequeueEnabled() && !backgroundTaskManager.isInQueueChain()) {
                return null;
            }
            MessageQueue.QueuedMessage next = messageQueue.peek();
            if (next == null || next.getStatus()
                    == MessageQueue.QueuedMessage.QueuedMessageStatus.EDITING) {
                return null;
            }
            MessageQueue.QueuedMessage claimed = messageQueue.dequeue();
            if (claimed == null) {
                return null;
            }
            repl.requestStatusRedraw();
            return new AgenticChatLoop.QueuedInput(claimed.getContent(), () -> {
                synchronized (turnDispatchLock) {
                    if (cancelSignal.get() || !acceptingDispatches.get()) return false;
                    if (!backgroundTaskManager.isInQueueChain()) {
                        backgroundTaskManager.startQueueChain(messageQueue.size() + 1);
                    }
                    backgroundTaskManager.advanceQueueChain();
                    if (messageQueue.isEmpty()) {
                        backgroundTaskManager.endQueueChain();
                    }
                    sessionMetrics.recordMessageAutoDequeued();
                    sessionMetrics.recordUserTurn(claimed.getContent());
                    chatHistory.logUserMessage(reminderManager == null
                            ? claimed.getContent()
                            : reminderManager.previewUserTurn(claimed.getContent()));
                    repl.requestStatusRedraw();
                    ChatCompleter.showNotice(renderer.cyan(
                                    "  ↪ Sent queued message at tool boundary")
                            + renderer.dim(" [" + claimed.getId() + "]"));
                    return true;
                }
            }, () -> {
                synchronized (turnDispatchLock) {
                    messageQueue.requeueFirst(claimed);
                    if (acceptingDispatches.get()) {
                        repl.requestStatusRedraw();
                    }
                }
            });
        }
    }

    private boolean hasBackgroundedActiveTurn() {
        BackgroundTaskManager.BackgroundTask task = backgroundTaskManager.getCurrentTask();
        return turnActive.get() && activeDispatchThread.get() != null && task != null
                && task.getStatus()
                == BackgroundTaskManager.BackgroundTask.BackgroundTaskStatus.BACKGROUNDED;
    }

    private void acceptBackgroundInput(String message, String formerQueueId) {
        if (message == null || message.isBlank()) return;
        if (backgroundInputs.stream().anyMatch(input -> input.content().equals(message))) return;
        backgroundInputs.add(new BackgroundInput(message, formerQueueId));
        repl.requestStatusRedraw();
        ChatCompleter.showNotice(renderer.cyan("  ↪ Processing with background task")
                + renderer.dim(" (not queued) → ") + StringUtils.truncate(message, 60));
    }

    /** Move all currently sendable queue entries into the detached turn's direct lane. */
    private int releaseQueuedInputToBackgroundTurn() {
        int released = 0;
        MessageQueue.QueuedMessage message;
        while ((message = messageQueue.dequeue()) != null) {
            BackgroundInput input = new BackgroundInput(message.getContent(), message.getId());
            if (backgroundInputs.stream().noneMatch(queued -> queued.content().equals(input.content()))) {
                backgroundInputs.add(input);
                released++;
            }
        }
        if (released > 0 && backgroundTaskManager.isInQueueChain()) {
            backgroundTaskManager.endQueueChain();
        }
        return released;
    }

    /** Session shutdown must not lose input that was removed from the durable queue. */
    private void restoreUnclaimedBackgroundInputs() {
        BackgroundInput input;
        while ((input = backgroundInputs.poll()) != null) {
            messageQueue.enqueue(input.content());
        }
    }

    private void setActivityAfterTurn() {
        if (cancelSignal.get()) {
            ChatCompleter.markInterrupted();
        } else {
            ChatCompleter.setActivity(null);
        }
    }

    private void emitInterruptedMessage(BackgroundTaskManager.BackgroundTask task) {
        repl.stopGeneratingSpinner();
        emitLine(renderer.yellow("  ⊘ Interrupted by user"));
        if (task != null) {
            task.appendOutput("\n[Interrupted by user]");
        }
    }

    private void startActivityIndicator() {
        setForegroundActivity("Thinking");
        repl.requestStatusRedraw();
        // With an active LineReader the persistent status bar renders the
        // foreground RUNNING task. A carriage-return spinner would overwrite
        // the draft the user is typing for the queue.
        if (!ChatCompleter.hasLineReader()) {
            repl.printGeneratingIndicator();
        }
    }

    // ========================================================================
    // Local / agentic chat
    // ========================================================================

    public void handleLocalChat(String message) {
        // Build memory-enriched message if memory is enabled
        String enrichedMessage = message;
        if (chatMemory != null && chatMemory.isEnabled()) {
            String memoryContext = chatMemory.buildMemoryContext(message);
            if (memoryContext != null) {
                enrichedMessage = "<memory_context>\n" + memoryContext + "</memory_context>\n\n" + message;
            }
        }

        // Load pending attachments and pass to agentic loop
        List<DirectLlmClient.AttachmentInput> attachments = loadAttachments();
        if (attachments != null) {
            agenticLoop.setPendingAttachments(attachments);
        }

        emitLine("");
        repl.setLlmBusy(true);
        BackgroundTaskManager.BackgroundTask task = backgroundTaskManager.startTask("LLM response: " + StringUtils.truncate(message, 50));
        startActivityIndicator();
        agenticLoop.setOnFirstOutput(repl::stopGeneratingSpinner);
        long turnStart = System.currentTimeMillis();

        try {
            String response = agenticLoop.chat(
                    enrichedMessage, sessionId, repl.getLocalAgentName(), repl.getAgentName(), false);

            repl.stopGeneratingSpinner();
            long turnDuration = System.currentTimeMillis() - turnStart;
            emitLine("");
            chatHistory.logAgentResponse(repl.getLocalAgentName(), response, turnDuration);
            appendFinalTaskOutput(task, response);
            sessionMetrics.recordAssistantTurn(response, turnDuration);
        } catch (Exception e) {
            if (cancelSignal.get()) {
                emitInterruptedMessage(task);
            } else {
                repl.stopGeneratingSpinner();
                emitLine(renderer.red("Error in chat: " + e.getMessage()));
                task.setError(e);
            }
        }
    }

    // ========================================================================
    // Server RAG chat
    // ========================================================================

    public void handleServerChat(String message) {
        // Build memory-enriched message if memory is enabled
        String enrichedMessage = message;
        if (chatMemory != null && chatMemory.isEnabled()) {
            String memoryContext = chatMemory.buildMemoryContext(message);
            if (memoryContext != null) {
                enrichedMessage = "<memory_context>\n" + memoryContext + "</memory_context>\n\n" + message;
            }
        }

        repl.setLlmBusy(true);
        BackgroundTaskManager.BackgroundTask task = backgroundTaskManager.startTask("LLM response: " + StringUtils.truncate(message, 50));
        startActivityIndicator();
        try {
            String outboundMessage = reminderManager == null
                    ? enrichedMessage : reminderManager.decorateUserTurn(enrichedMessage);
            emitReminderSection(outboundMessage);
            ObjectNode args = objectMapper.createObjectNode();
            args.put("sessionId", sessionId);
            args.put("message", outboundMessage);
            args.put("enableRag", repl.isRagEnabled());
            args.put("maxResults", 10);
            args.put("similarityThreshold", 0.5);

            String rawResponse = mcpClient.callTool("send_chat_message", args);
            repl.stopGeneratingSpinner();

            try {
                JsonNode json = objectMapper.readTree(rawResponse);
                String answer = json.path("answer").asText(null);
                int docsRetrieved = json.path("documentsRetrieved").asInt(0);
                long timeMs = json.path("executionTimeMs").asLong(0);

                if (answer != null) {
                    emitLine("");
                    emitLine(asciiRenderer.renderMarkdown(answer));
                } else {
                    answer = rawResponse;
                    emitLine("");
                    emitLine(asciiRenderer.renderMarkdown(rawResponse));
                }

                if (docsRetrieved > 0) {
                    emitLine("  [" + docsRetrieved + " docs retrieved, " + timeMs + "ms]");
                    sessionMetrics.recordRagQuery(docsRetrieved);
                }
                emitLine("");

                String finalAnswer = answer != null ? answer : rawResponse;
                sessionMetrics.recordAssistantTurn(finalAnswer, timeMs);
                chatHistory.logAssistantMessage(finalAnswer, docsRetrieved, timeMs);
            } catch (Exception e) {
                emitLine("");
                emitLine(asciiRenderer.renderMarkdown(rawResponse));
                emitLine("");
                chatHistory.logAssistantMessage(rawResponse, 0, 0);
            }
        } catch (Exception e) {
            if (cancelSignal.get()) {
                emitInterruptedMessage(task);
            } else {
                repl.stopGeneratingSpinner();
                emitLine(renderer.red("Error sending message: " + e.getMessage()));
                task.setError(e);
            }
        }
    }

    // ========================================================================
    // Agent streaming (/ask command) via REST /api/agents/chat/stream SSE
    // ========================================================================

    public void streamAgentChat(String message) {
        if (message.isBlank()) {
            emitLine("Usage: /ask <message>");
            emitLine("Sends a message to the configured agent with streaming output.");
            return;
        }
        repl.initializeSessionTitleFromPrompt(message);
        if (agenticLoop.isWorkflowActive()) {
            dispatchTurn(message, () -> runAgenticChat(message),
                    "workflow-agentic-chat");
            return;
        }
        dispatchTurn(message, () -> streamAgentChatAccepted(message), "server-stream-chat");
    }

    private void streamAgentChatAccepted(String message) {
        chatHistory.logUserMessage("/ask " + (reminderManager == null
                ? message : reminderManager.previewUserTurn(message)));

        // Build memory-enriched message if memory is enabled
        String enrichedMessage = message;
        if (chatMemory != null && chatMemory.isEnabled()) {
            String memoryContext = chatMemory.buildMemoryContext(message);
            if (memoryContext != null) {
                enrichedMessage = "<memory_context>\n" + memoryContext + "</memory_context>\n\n" + message;
            }
        }

        repl.setLlmBusy(true);
        BackgroundTaskManager.BackgroundTask task = backgroundTaskManager.startTask("Streaming LLM response: " + StringUtils.truncate(message, 40));
        startActivityIndicator();
        try {
            String outboundMessage = reminderManager == null
                    ? enrichedMessage : reminderManager.decorateUserTurn(enrichedMessage);
            emitReminderSection(outboundMessage);
            ObjectNode request = objectMapper.createObjectNode();
            request.put("message", outboundMessage);
            request.put("agentName", repl.getAgentName());
            request.put("enableRag", repl.isRagEnabled());
            request.put("ragMaxResults", 5);
            request.put("ragSimilarityThreshold", 0.5);
            request.put("includeKeywordSearch", true);
            request.put("includeSemanticSearch", true);
            request.put("systemPromptOverride", agenticLoop.getProjectContextPrompt());
            request.put("injectMcpTools", true);
            request.put("skipPermissions", true);
            request.put("timeoutSeconds", 300);

            String body = objectMapper.writeValueAsString(request);
            StringBuilder fullResponse = new StringBuilder();
            long[] durationMs = {0};
            StreamingMarkdownRenderer streamingMd =
                    new StreamingMarkdownRenderer(asciiRenderer, this::emitLine);
            boolean connected = false;
            for (int attempt = 1; attempt <= serverConnectivityPolicy.maxAttempts(); attempt++) {
                boolean streamStarted = false;
                boolean terminalEvent = false;
                InputStream responseBody = null;
                try {
                    HttpRequest httpRequest = HttpRequest.newBuilder()
                            .uri(URI.create(repl.getBaseUrl() + "/api/agents/chat/stream"))
                            .header("Content-Type", "application/json")
                            .header("Accept", "text/event-stream")
                            .POST(HttpRequest.BodyPublishers.ofString(body))
                            .timeout(serverConnectivityPolicy.requestTimeout())
                            .build();
                    HttpResponse<InputStream> response = httpClient.send(
                            httpRequest, HttpResponse.BodyHandlers.ofInputStream());
                    if (response.statusCode() != 200) {
                        if (serverConnectivityPolicy.isRetryableStatus(response.statusCode())
                                && attempt < serverConnectivityPolicy.maxAttempts()) {
                            response.body().close();
                            reconnectServerStream(attempt,
                                    "HTTP " + response.statusCode(), response.headers());
                            continue;
                        }
                        response.body().close();
                        repl.stopGeneratingSpinner();
                        emitLine(renderer.red("Agent stream failed: HTTP " + response.statusCode()));
                        return;
                    }

                    repl.stopGeneratingSpinner();
                    setForegroundActivity("Responding");
                    repl.requestStatusRedraw();
                    emitLine("");
                    responseBody = new IdleTimeoutInputStream(
                            response.body(), serverConnectivityPolicy.streamIdleTimeout());
                    activeResponseBody.set(responseBody);
                    try (BufferedReader reader = new BufferedReader(
                            new InputStreamReader(responseBody))) {
                        String eventType = null;
                        StringBuilder dataBuffer = new StringBuilder();
                        String line;
                        while ((line = reader.readLine()) != null) {
                            if (cancelSignal.get()) {
                                streamingMd.flush();
                                emitLine("\n" + renderer.yellow("  ⊘ Interrupted by user"));
                                fullResponse.append("\n[Interrupted by user]");
                                break;
                            }
                            if (line.startsWith("event:")) {
                                eventType = line.substring(6).trim();
                            } else if (line.startsWith("data:")) {
                                dataBuffer.append(line.substring(5).trim());
                            } else if (line.isEmpty() && eventType != null) {
                                streamStarted = true;
                                terminalEvent = terminalEvent || "complete".equals(eventType)
                                        || "cancelled".equals(eventType)
                                        || "error".equals(eventType);
                                handleStreamEvent(eventType, dataBuffer.toString(), fullResponse,
                                        durationMs, streamingMd);
                                eventType = null;
                                dataBuffer.setLength(0);
                            }
                        }
                    }
                    if (!cancelSignal.get() && !terminalEvent) {
                        throw new IOException("Agent stream ended before a terminal event");
                    }
                    connected = true;
                    break;
                } catch (Exception failure) {
                    boolean retry = !streamStarted && fullResponse.isEmpty()
                            && serverConnectivityPolicy.isRetryableFailure(failure)
                            && attempt < serverConnectivityPolicy.maxAttempts();
                    if (!retry) throw failure;
                    reconnectServerStream(attempt, connectivityMessage(failure), null);
                } finally {
                    if (responseBody != null) {
                        activeResponseBody.compareAndSet(responseBody, null);
                    }
                }
            }
            if (!connected && !cancelSignal.get()) {
                throw new IOException("Agent stream could not reconnect");
            }
            streamingMd.flush();

            emitLine("");

            String responseText = fullResponse.toString();
            chatHistory.logAgentResponse(repl.getAgentName(), responseText, durationMs[0]);
            appendFinalTaskOutput(task, responseText);
            sessionMetrics.recordAssistantTurn(responseText, durationMs[0]);

        } catch (Exception e) {
            if (cancelSignal.get()) {
                emitInterruptedMessage(task);
            } else {
                repl.stopGeneratingSpinner();
                emitLine(renderer.red("Error in agent stream: " + e.getMessage()));
                task.setError(e);
            }
        } finally {
            setActivityAfterTurn();
            repl.requestStatusRedraw();
            completeAcceptedTurn();
        }
    }

    private void reconnectServerStream(
            int failedAttempt, String reason, HttpHeaders headers) throws InterruptedException {
        Duration delay = serverConnectivityPolicy.retryDelay(failedAttempt, headers);
        setForegroundActivity("Reconnecting Kompile · " + (failedAttempt + 1) + "/"
                + serverConnectivityPolicy.maxAttempts() + " in " + delay.toMillis()
                + " ms (" + reason + ")");
        repl.requestStatusRedraw();
        long deadline = System.nanoTime() + delay.toNanos();
        while (System.nanoTime() < deadline) {
            if (cancelSignal.get()) throw new InterruptedException("Chat turn cancelled");
            long remaining = deadline - System.nanoTime();
            TimeUnit.NANOSECONDS.sleep(Math.min(
                    remaining, TimeUnit.MILLISECONDS.toNanos(100)));
        }
    }

    private static String connectivityMessage(Throwable failure) {
        String message = failure.getMessage();
        return message == null || message.isBlank()
                ? failure.getClass().getSimpleName() : message;
    }

    // ========================================================================
    // Agentic tool loop (/agent-chat command)
    // ========================================================================

    public void agenticChat(String message) {
        if (message.isBlank()) {
            runAgenticChat(message);
            return;
        }
        repl.initializeSessionTitleFromPrompt(message);
        dispatchTurn(message, () -> {
            try {
                runAgenticChat(message);
            } finally {
                setActivityAfterTurn();
                repl.requestStatusRedraw();
                completeAcceptedTurn();
            }
        }, "agentic-chat-dispatch");
    }

    private void runAgenticChat(String message) {
        if (message.isBlank()) {
            emitLine("Usage: /agent-chat <message>");
            emitLine("Sends a message through the agentic tool loop with local tool execution.");
            emitLine("Current local agent: " + repl.getLocalAgentName());
            emitLine("Available local agents: " + String.join(", ",
                    repl.getAgentRegistry().getPrimaryAgents().stream()
                            .map(a -> a.getName()).toArray(String[]::new)));
            return;
        }

        String transcriptPrefix = repl.isForceAgentic() || agenticLoop.isWorkflowActive()
                ? "" : "/agent-chat ";
        chatHistory.logUserMessage(transcriptPrefix + (reminderManager == null
                ? message : reminderManager.previewUserTurn(message)));

        // Build memory-enriched message if memory is enabled
        String enrichedMessage = message;
        if (chatMemory != null && chatMemory.isEnabled()) {
            String memoryContext = chatMemory.buildMemoryContext(message);
            if (memoryContext != null) {
                enrichedMessage = "<memory_context>\n" + memoryContext + "</memory_context>\n\n" + message;
            }
        }

        emitLine("");

        repl.setLlmBusy(true);
        backgroundTaskManager.startTask("Agentic chat: " + StringUtils.truncate(message, 40));
        startActivityIndicator();
        agenticLoop.setOnFirstOutput(repl::stopGeneratingSpinner);
        long turnStart = System.currentTimeMillis();
        try {
            String response = agenticLoop.chat(
                    enrichedMessage, sessionId, repl.getLocalAgentName(), repl.getAgentName(), repl.isRagEnabled());

            repl.stopGeneratingSpinner();
            long turnDuration = System.currentTimeMillis() - turnStart;
            emitLine("");
            chatHistory.logAgentResponse(repl.getLocalAgentName(), response, turnDuration);
            appendFinalTaskOutput(backgroundTaskManager.getCurrentTask(), response);
            sessionMetrics.recordAssistantTurn(response, turnDuration);

        } catch (Exception e) {
            BackgroundTaskManager.BackgroundTask parentTask = backgroundTaskManager.getCurrentTask();
            if (cancelSignal.get()) {
                emitInterruptedMessage(parentTask);
            } else {
                repl.stopGeneratingSpinner();
                emitLine(renderer.red("Error in agentic chat: " + e.getMessage()));
                if (parentTask != null) parentTask.setError(e);
            }
        }
    }

    // ========================================================================
    // SSE event handling
    // ========================================================================

    public void handleStreamEvent(String eventType, String data,
                                  StringBuilder fullResponse, long[] durationMs,
                                  StreamingMarkdownRenderer streamingMd) {
        switch (eventType) {
            case "chunk":
                String chunk = data;
                if (chunk.startsWith("\"") && chunk.endsWith("\"")) {
                    try {
                        chunk = objectMapper.readValue(chunk, String.class);
                    } catch (Exception e) {
                        // Use as-is
                    }
                }
                streamingMd.accept(chunk);
                fullResponse.append(chunk);
                break;

            case "start":
                streamingMd.flush();
                try {
                    JsonNode json = objectMapper.readTree(data);
                    String agent = json.path("agent").asText("");
                    String processId = json.path("processId").asText("");
                    if (!processId.isBlank()) {
                        activeRemoteProcessId.set(processId);
                        if (cancelSignal.get()) {
                            activeRemoteProcessId.compareAndSet(processId, null);
                            cancelRemoteProcess(processId);
                        }
                    }
                    emitLine(renderer.dim("[Agent: " + agent + "]"));
                } catch (Exception e) {
                    // ignore
                }
                break;

            case "sources":
                streamingMd.flush();
                try {
                    JsonNode sources = objectMapper.readTree(data);
                    if (sources.isArray() && sources.size() > 0) {
                        emitLine(renderer.dim("[Retrieved " + sources.size() + " documents]"));
                    }
                } catch (Exception e) {
                    // ignore
                }
                break;

            case "stats":
                streamingMd.flush();
                try {
                    JsonNode stats = objectMapper.readTree(data);
                    durationMs[0] = stats.path("durationMs").asLong(0);
                    if (durationMs[0] > 0) {
                        emitLine(renderer.dim("  [completed in " + durationMs[0] + "ms]"));
                    }
                } catch (Exception e) {
                    // ignore
                }
                break;

            case "error":
                streamingMd.flush();
                try {
                    JsonNode error = objectMapper.readTree(data);
                    emitLine(renderer.red("Error: " + error.path("message").asText(data)));
                } catch (Exception e) {
                    emitLine(renderer.red("Error: " + data));
                }
                break;

            case "complete":
                streamingMd.flush();
                break;

            case "cancelled":
                streamingMd.flush();
                emitLine(renderer.yellow("[Interrupted by user]"));
                break;

            default:
                break;
        }
    }

    // ========================================================================
    // Attachment loading
    // ========================================================================

    /**
     * Load pending attachments into DirectLlmClient.AttachmentInput format,
     * reading file contents (base64 for images, text for text files).
     */
    public List<DirectLlmClient.AttachmentInput> loadAttachments() {
        if (pendingAttachments.isEmpty()) return null;

        List<DirectLlmClient.AttachmentInput> loaded = new ArrayList<>();
        for (ChatRepl.PendingAttachment att : pendingAttachments) {
            try {
                if (att.isImage()) {
                    byte[] bytes = Files.readAllBytes(att.path());
                    String base64 = Base64.getEncoder().encodeToString(bytes);
                    loaded.add(new DirectLlmClient.AttachmentInput(
                            att.path().toString(), att.mimeType(), true, base64, null));
                } else {
                    String text = Files.readString(att.path());
                    loaded.add(new DirectLlmClient.AttachmentInput(
                            att.path().toString(), att.mimeType(), false, null, text));
                }
            } catch (Exception e) {
                emitLine(renderer.yellow("Warning: Could not read attachment "
                        + att.path().getFileName() + ": " + e.getMessage()));
            }
        }

        // Clear pending after loading
        pendingAttachments.clear();
        return loaded.isEmpty() ? null : loaded;
    }

}
