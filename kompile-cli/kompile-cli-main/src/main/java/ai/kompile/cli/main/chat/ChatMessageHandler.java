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
import ai.kompile.cli.main.chat.exec.ChatAttachmentLoader;
import ai.kompile.cli.main.chat.render.AsciiRenderer;
import ai.kompile.cli.main.chat.render.StreamingMarkdownRenderer;
import ai.kompile.cli.main.chat.render.TerminalRenderer;
import ai.kompile.cli.main.chat.tools.ProcessManagementTool;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
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
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiPredicate;
import java.util.stream.Collectors;

/**
 * Handles chat message dispatch: inline RAG, local/agentic chat, server streaming, and
 * SSE event parsing. Extracted from ChatRepl to reduce its size.
 */
public class ChatMessageHandler {

    private static final String SESSION_RESTART_STOP_LABEL = "Stopped: session restarting";
    /** Beside the transcript: events this session received but has not delivered yet. */
    private static final String PENDING_EVENTS_SUFFIX = ".pending-events.json";
    /** An event that waited this long is delivered with its arrival time and age. */
    private static final Duration LATE_EVENT_AGE = Duration.ofMinutes(1);
    static final DateTimeFormatter EVENT_TIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z").withZone(ZoneId.systemDefault());

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
    private final ContinueManager continueManager;
    private final Object turnDispatchLock = new Object();
    /** Explicit native Ctrl+B releases input, but never transfers Claude's turn owner. */
    private boolean claudeBackgroundInputReleased;
    /** Serializes synchronous crawl/headless turns without blocking cancellation. */
    private final Object synchronousTurnLock = new Object();
    /** Auto-continue watchdog for provider usage-limit windows (e.g. 5-hour quota). */
    private final UsageLimitAutoContinue usageLimitAutoContinue = new UsageLimitAutoContinue(
            this::resumeAfterUsageLimitWindow,
            line -> ChatCompleter.showNotice(line), this::quotaProvider);
    private final ThreadLocal<UsageLimitAutoContinue.Turn> quotaTurn = new ThreadLocal<>();
    /** Accessed only under turnDispatchLock; a busy owner must not discard a due wake. */
    private UsageLimitAutoContinue.Resume deferredQuotaResume;
    private final ProviderConnectivityPolicy serverConnectivityPolicy =
            ProviderConnectivityPolicy.forProvider("kompile");
    private final AtomicReference<Thread> activeDispatchThread = new AtomicReference<>();
    private final AtomicReference<Thread> synchronousTurnOwner = new AtomicReference<>();
    private final AtomicReference<InputStream> activeResponseBody = new AtomicReference<>();
    private final AtomicReference<String> activeRemoteProcessId = new AtomicReference<>();
    private final AtomicBoolean turnActive = new AtomicBoolean();
    /**
     * The accepted turn was stopped (Escape, shutdown) rather than replaced by an
     * interrupting successor, so its release holds the automatic lanes.
     */
    private final AtomicBoolean turnStopped = new AtomicBoolean();
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
     * Real events (never provider follow-up markers) with their arrival time, from
     * receipt until a turn logs them. Mirrored to the pending-events file so the
     * next chat on this session still delivers them. Guarded by turnDispatchLock.
     */
    private final Map<String, Instant> undeliveredExternal = new LinkedHashMap<>();
    /** Off once this handler stops, so its late writes cannot clobber a successor's file. */
    private boolean pendingEventsWritable = true;
    /**
     * Writes a delivery (id, text) into the provider turn that is running; false
     * when no turn there can take it. Null for providers that cannot.
     */
    private volatile BiPredicate<String, String> runningTurnInjector;
    /**
     * Deliveries written into a running turn, by id, until the provider reports
     * them read or dropped. Guarded by turnDispatchLock.
     */
    private final Map<String, Injection> injectedExternal = new LinkedHashMap<>();

    private record Injection(String id, String delivery, List<String> events) { }
    /**
     * Input handed directly to a detached turn. These messages are no longer
     * durable queue entries: Ctrl+B explicitly released them for processing by
     * the active owner at its first safe model/tool boundary.
     */
    private final ConcurrentLinkedDeque<BackgroundInput> backgroundInputs =
            new ConcurrentLinkedDeque<>();
    /** Finished assistant response of the owning turn, consumed once at release. */
    private final AtomicReference<String> lastAssistantResponse = new AtomicReference<>();

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
            ReminderManager reminderManager,
            ContinueManager continueManager) {
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
        this.continueManager = continueManager;
        ChatCompleter.setQueueSupplier(() -> this.messageQueue.getAll().stream()
                .map(MessageQueue.QueuedMessage::getContent)
                .collect(Collectors.toList()));
        this.agenticLoop.setQueuedMessageSupplier(this::claimPendingInputAtBoundary);
        // Agent-initiated backgrounding delegates to the same transfer path as
        // Ctrl+B; the subagent-invocation eligibility gate inside requestBackground
        // remains the single source of truth for what may be detached.
        this.agenticLoop.setSelfBackgroundRequest(this::requestBackground);
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
        // Any user-submitted input takes ownership back from the watchdog
        // and re-arms the /continue auto-reply budget.
        synchronized (turnDispatchLock) {
            usageLimitAutoContinue.disarm();
            deferredQuotaResume = null;
        }
        if (continueManager != null) continueManager.noteUserActivity();
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
                turnStopped.set(false);
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
        boolean followUp = DirectLlmClient.isProviderFollowUp(normalized);
        Injection injection;
        synchronized (turnDispatchLock) {
            if (!acceptingExternalMessages.get()) return;
            if (!followUp && undeliveredExternal.putIfAbsent(normalized, Instant.now()) == null) {
                savePendingEvents();
            }
            if (repl.isLlmBusy()) {
                if (mandatoryExternalMessages.contains(normalized) || isInjected(normalized)) return;
                mandatoryExternalMessages.add(normalized);
                injection = followUp ? null : claimForInjection();
                if (injection == null) {
                    waitForRunningTurn();
                    return;
                }
            } else if (followUp) {
                dispatchTurn(normalized,
                        () -> handleAcceptedExternalMessage(normalized, List.of()),
                        "standard-chat-process-wakeup");
                return;
            } else {
                // Events a stopped turn held go out with this one.
                List<String> events = pollQueuedEvents();
                events.remove(normalized);
                events.add(normalized);
                dispatchExternalEvents(events);
                return;
            }
        }
        injectIntoRunningTurn(injection);
    }

    /** The event waits for the running turn's next boundary, or for its end. */
    private void waitForRunningTurn() {
        // A blocking child is not a model boundary. Release the parent
        // automatically so completion events do not require Ctrl+B.
        // Keep ordinary queued user input subject to its own policy.
        requestBackground(false);
        ChatCompleter.showNotice(renderer.cyan(hasBackgroundedActiveTurn()
                ? "  ↻ Completion event handed to background task"
                : "  ↻ Completion event queued for agent"));
        repl.requestStatusRedraw();
    }

    /**
     * Takes the queued events out for the running turn, which reads them after
     * its next tool calls instead of after it ends. Null when no provider can
     * take them, or the turn is being cancelled. Called under turnDispatchLock.
     */
    private Injection claimForInjection() {
        if (runningTurnInjector == null || cancelSignal.get()) return null;
        List<String> events = pollQueuedEvents();
        if (events.isEmpty()) return null;
        Injection injection = new Injection(UUID.randomUUID().toString(),
                composeExternalDelivery(events), List.copyOf(events));
        injectedExternal.put(injection.id(), injection);
        return injection;
    }

    /**
     * Writes a claimed delivery into the running turn, outside turnDispatchLock
     * because the write can block on the provider's input. A delivery nothing
     * took goes back to the queue.
     */
    private void injectIntoRunningTurn(Injection injection) {
        BiPredicate<String, String> injector = runningTurnInjector;
        if (injector != null && injector.test(injection.id(), injection.delivery())) {
            ChatCompleter.showNotice(renderer.cyan("  ↻ Completion event sent to the running turn"));
            return;
        }
        synchronized (turnDispatchLock) {
            if (!requeueInjected(injection.id())) return;
            if (repl.isLlmBusy()) {
                waitForRunningTurn();
            } else {
                // The turn ended meanwhile, and its release did not see these events.
                dispatchPendingExternalAfterTurnRelease();
            }
        }
    }

    /** The running turn took in an injected delivery: its events are delivered. */
    void injectionDelivered(String id) {
        synchronized (turnDispatchLock) {
            Injection injection = injectedExternal.remove(id);
            // A stopped handler leaves the events in the file for the next chat on the session.
            if (injection == null || !acceptingExternalMessages.get()) return;
            chatHistory.logSystem(injection.delivery());
            markExternalDelivered(injection.events());
        }
    }

    /** The provider dropped an injected delivery unread: its events wait for the next turn. */
    void injectionDropped(String id) {
        synchronized (turnDispatchLock) {
            if (!requeueInjected(id)) return;
            if (repl.isLlmBusy()) {
                repl.requestStatusRedraw();
            } else if (cancelSignal.get() && turnStopped.get()) {
                // Escape is not answered by a new turn: they wait with the rest it held.
                noteHeldAfterStop();
            } else {
                dispatchPendingExternalAfterTurnRelease();
            }
        }
    }

    /**
     * Puts an injected delivery's events back at the head of the queue, in order.
     * Removing the entry settles it, so a failed write and a later drop report
     * cannot both requeue it. Called under turnDispatchLock.
     *
     * @return false when it was already settled, or this handler takes no events
     */
    private boolean requeueInjected(String id) {
        Injection injection = injectedExternal.remove(id);
        if (injection == null || !acceptingExternalMessages.get()) return false;
        List<String> events = injection.events();
        for (int i = events.size() - 1; i >= 0; i--) {
            mandatoryExternalMessages.remove(events.get(i));
            mandatoryExternalMessages.addFirst(events.get(i));
        }
        return true;
    }

    private boolean isInjected(String event) {
        for (Injection injection : injectedExternal.values()) {
            if (injection.events().contains(event)) return true;
        }
        return false;
    }

    void setRunningTurnInjector(BiPredicate<String, String> injector) {
        runningTurnInjector = injector;
    }

    /** True while a turn runs, including one backgrounded with Ctrl+B. */
    boolean hasActiveTurn() {
        return turnActive.get() || repl.isLlmBusy()
                || activeDispatchThread.get() != null
                || synchronousTurnOwner.get() != null;
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
            usageLimitAutoContinue.disarm();
            deferredQuotaResume = null;
            if (continueManager != null) continueManager.noteUserActivity();
            if (hasActiveTurn()) {
                if (mandatoryUserFeedback.contains(normalized)) return true;
                mandatoryUserFeedback.add(normalized);
                ChatCompleter.showNotice(renderer.cyan(
                        "  ↻ Judge feedback queued as an interrupting user message"));
                repl.requestStatusRedraw();
                // The feedback replaces the turn: a hand-off, not a stop.
                if (interrupt) cancel(false);
                return true;
            }
            dispatchTurn(normalized,
                    () -> handleAcceptedChatMessage(normalized),
                    "standard-chat-judge-feedback");
            return true;
        }
    }

    /**
     * Opens the event lanes, then delivers what this session received but never
     * delivered before its previous chat stopped (a restart, a resume, a crash).
     */
    void startAcceptingExternalMessages() {
        synchronized (turnDispatchLock) {
            acceptingDispatches.set(true);
            acceptingExternalMessages.set(true);
            pendingEventsWritable = true;
            List<String> restored = restorePendingEvents();
            if (restored.isEmpty()) return;
            ChatCompleter.showNotice(renderer.cyan("  ↻ Delivering " + restored.size()
                    + (restored.size() == 1 ? " event" : " events")
                    + " this chat received before it last stopped"));
            if (repl.isLlmBusy()) {
                mandatoryExternalMessages.addAll(restored);
                repl.requestStatusRedraw();
            } else {
                dispatchExternalEvents(restored);
            }
        }
    }

    void stopAcceptingExternalMessages() {
        Thread externalOwner;
        synchronized (turnDispatchLock) {
            acceptingExternalMessages.set(false);
            // The file keeps what is still undelivered for the next chat on this session.
            pendingEventsWritable = false;
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
        usageLimitAutoContinue.shutdown();
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
            turnStopped.set(false);
            turnActive.set(true);
            lastAssistantResponse.set(null);
            activeRemoteProcessId.set(null);
            UsageLimitAutoContinue.Turn quotaOwner = usageLimitAutoContinue.beginTurn();
            Thread dispatchThread = new Thread(sessionContext.wrap(() -> {
                quotaTurn.set(quotaOwner);
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
                    quotaTurn.remove();
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
                claudeBackgroundInputReleased = false;
                activeResponseBody.set(null);
                activeRemoteProcessId.set(null);
                repl.setLlmBusy(false);
                // Queue hand-off happens under the same reservation lock
                // only after this owner can no longer be overwritten.
                dispatchSuccessorAfterTurnRelease(cancelSignal.get() && turnStopped.get());
            }
        }
    }

    /**
     * Starts the released turn's successor. A stopped turn holds the automatic
     * lanes (judge feedback, process events, /continue) for the next turn, so
     * Escape is not answered by a new turn; input the user typed still goes. A
     * turn the provider started by itself always goes first: it is already
     * running, and anything sent ahead of it would wait unseen behind it.
     */
    private void dispatchSuccessorAfterTurnRelease(boolean stopped) {
        if (dispatchProviderFollowUpAfterTurnRelease()) return;
        if (!stopped && dispatchPendingUserFeedbackAfterTurnRelease()) return;
        if (!stopped && acceptingExternalMessages.get()
                && dispatchPendingExternalAfterTurnRelease()) return;
        if (dispatchPendingBackgroundInputAfterTurnRelease()) return;
        if (stopped) {
            lastAssistantResponse.set(null);
            noteHeldAfterStop();
        } else if (dispatchDeferredQuotaResume() || dispatchContinueAutoReply()) {
            return;
        }
        if (acceptingDispatches.get()) {
            repl.dispatchQueuedMessageAfterTurnRelease();
        }
    }

    private void noteHeldAfterStop() {
        if (!acceptingDispatches.get()) return;
        int held = mandatoryUserFeedback.size()
                + (acceptingExternalMessages.get() ? mandatoryExternalMessages.size() : 0);
        if (held == 0) return;
        ChatCompleter.showNotice(renderer.dim("  " + held + (held == 1
                ? " automatic message is" : " automatic messages are")
                + " held until the next turn"));
        repl.requestStatusRedraw();
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
            turnStopped.set(false);
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
            turnStopped.set(false);
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
        return cancel(true);
    }

    /** A restart ends the session; the turn it cancels was not the user's Escape. */
    void noteSessionRestarting() {
        agenticLoop.setStopLabel(SESSION_RESTART_STOP_LABEL);
    }

    /**
     * @param stop true when the user or the session stops the turn; false when an
     *             interrupting successor (judge feedback) replaces it and the
     *             release hands off as usual
     */
    private boolean cancel(boolean stop) {
        Thread active;
        Thread synchronous;
        String processId;
        synchronized (turnDispatchLock) {
            usageLimitAutoContinue.disarm();
            deferredQuotaResume = null;
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
            if (stop) turnStopped.set(true);
            // Only the first cancel signals and interrupts. The owner is then
            // unwinding, and a repeated one (a second Escape) would interrupt its
            // cleanup, such as history writes over interruptible channels.
            if (!cancelSignal.getAndSet(true)) {
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
        }
        if (processId != null && !processId.isBlank()) {
            cancelRemoteProcess(processId);
        }
        return true;
    }

    /**
     * Background the blocking tool without cancelling it (local worker transfer
     * or Claude's native control). The parent resumes with a pending tool result, so queued and fresh
     * input reach a model boundary without waiting for that worker to finish.
     */
    public boolean requestBackground() {
        return requestBackground(true);
    }

    private boolean requestBackground(boolean releaseUserInput) {
        synchronized (turnDispatchLock) {
            if (!turnActive.get() || cancelSignal.get()) {
                return false;
            }
            // Claude-owned tools have no local worker to detach. Its native control
            // returns a pending tool_result and resumes the same provider turn; the
            // ordinary successor queue then advances without waiting for the task.
            Thread owner = activeDispatchThread.get();
            if (agenticLoop.requestClaudeBackground(() -> {
                synchronized (turnDispatchLock) {
                    if (releaseUserInput && acceptingDispatches.get() && !cancelSignal.get()
                            && turnActive.get() && activeDispatchThread.get() == owner) {
                        claudeBackgroundInputReleased = true;
                        sessionMetrics.recordTaskBackgrounded();
                        releaseQueuedInputToBackgroundTurn();
                        repl.requestStatusRedraw();
                    }
                }
            }, text -> ChatCompleter.showNotice(renderer.dim("  " + text)))) return true;
            BackgroundTaskManager.BackgroundTask task = backgroundTaskManager.requestBackground();
            if (task == null) {
                return false;
            }

            Runnable rejected = () -> {
                synchronized (turnDispatchLock) {
                    if (backgroundTaskManager.getCurrentTask() == task) {
                        task.setStatus(BackgroundTaskManager.BackgroundTask.BackgroundTaskStatus.RUNNING);
                        backgroundTaskManager.clearBackgroundRequest();
                        agenticLoop.clearBackgroundOutput();
                    }
                }
            };
            int released = releaseUserInput ? releaseQueuedInputToBackgroundTurn() : 0;
            task.appendOutput("\n[Backgrounded; input is processed directly at the next safe boundary]"
                    + (released > 0 ? " [released " + released + " queued message(s)]" : "")
                    + "\n");
            boolean accepted = agenticLoop.requestBackgroundActiveTurn(task::appendOutput, () -> {
                synchronized (turnDispatchLock) {
                    backgroundTaskManager.detachTask(task);
                    backgroundTaskManager.startTask("Parent conversation (background task continues)");
                    agenticLoop.clearBackgroundOutput();
                }
            }, result -> {
                task.appendOutput("\n" + result.getOutput() + "\n");
                backgroundTaskManager.completeDetachedTask(task, result.isError()
                        ? new IllegalStateException(result.getOutput()) : null);
            }, rejected);
            if (!accepted) {
                rejected.run();
                return false;
            }
            agenticLoop.backgroundActiveTurn(task::appendOutput);
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

    /**
     * @param events the real events this turn carries; once the turn is logged
     *               they are delivered, and a later chat must not send them again
     */
    private void handleAcceptedExternalMessage(String message, List<String> events) {
        synchronized (turnDispatchLock) {
            if (!acceptingExternalMessages.get()) {
                repl.completeTaskWithoutAutoDequeue();
                return;
            }
            externalTurnActive.set(true);
            chatHistory.logSystem(message);
            markExternalDelivered(events);
        }
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

    /** Delivers every queued event in one turn, not one turn per event. */
    private boolean dispatchPendingExternalAfterTurnRelease() {
        if (!acceptingExternalMessages.get()) {
            mandatoryExternalMessages.clear();
            return false;
        }
        List<String> events = pollQueuedEvents();
        if (events.isEmpty()) return false;
        dispatchExternalEvents(events);
        return true;
    }

    private boolean dispatchProviderFollowUpAfterTurnRelease() {
        String message = pollProviderFollowUp();
        if (message == null) return false;
        dispatchTurn(message,
                () -> handleAcceptedExternalMessage(message, List.of()),
                "standard-chat-process-wakeup");
        return true;
    }

    /** Claims a queued turn the provider started by itself, ahead of other events. */
    private String pollProviderFollowUp() {
        if (!acceptingExternalMessages.get()) return null;
        for (String message : mandatoryExternalMessages) {
            if (DirectLlmClient.isProviderFollowUp(message)
                    && mandatoryExternalMessages.removeFirstOccurrence(message)) {
                return message;
            }
        }
        return null;
    }

    /** Removes and returns the queued real events, oldest first; follow-up markers stay. */
    private List<String> pollQueuedEvents() {
        List<String> events = new ArrayList<>();
        for (String message : mandatoryExternalMessages) {
            if (!DirectLlmClient.isProviderFollowUp(message)
                    && mandatoryExternalMessages.removeFirstOccurrence(message)) {
                events.add(message);
            }
        }
        return events;
    }

    /** Starts one turn that delivers all of the given events. */
    private void dispatchExternalEvents(List<String> events) {
        List<String> carried = List.copyOf(events);
        String delivery = composeExternalDelivery(carried);
        dispatchTurn(delivery, () -> handleAcceptedExternalMessage(delivery, carried),
                "standard-chat-process-wakeup");
    }

    /**
     * Joins events into one delivery. An event that waited at least
     * LATE_EVENT_AGE says when it arrived, so the agent checks the current state
     * before it acts on the event.
     */
    private String composeExternalDelivery(List<String> events) {
        Instant now = Instant.now();
        StringBuilder delivery = new StringBuilder();
        for (String event : events) {
            if (delivery.length() > 0) delivery.append("\n\n");
            Instant receivedAt = undeliveredExternal.get(event);
            Duration waited = receivedAt == null ? Duration.ZERO : Duration.between(receivedAt, now);
            if (waited.compareTo(LATE_EVENT_AGE) >= 0) {
                delivery.append("[Late delivery: this event arrived at ")
                        .append(EVENT_TIME_FORMAT.format(receivedAt)).append(", ")
                        .append(ProcessManagementTool.formatDuration(waited))
                        .append(" ago. Check the current state before acting on it.]\n");
            }
            delivery.append(event);
        }
        return delivery.toString();
    }

    /** A logged event is delivered: no later chat has to send it again. */
    private void markExternalDelivered(List<String> events) {
        boolean changed = false;
        for (String event : events) {
            // The same text queued again is a new arrival that still waits.
            if (!mandatoryExternalMessages.contains(event)
                    && undeliveredExternal.remove(event) != null) {
                changed = true;
            }
        }
        if (changed) savePendingEvents();
    }

    Path pendingEventsFile() {
        Path transcript = chatHistory.getTranscriptFile();
        return transcript == null ? null : transcript.resolveSibling(sessionId + PENDING_EVENTS_SUFFIX);
    }

    /** Mirrors undeliveredExternal beside the transcript; nothing pending, no file. */
    private void savePendingEvents() {
        if (!pendingEventsWritable) return;
        Path file = pendingEventsFile();
        if (file == null) return;
        try {
            if (undeliveredExternal.isEmpty()) {
                Files.deleteIfExists(file);
                return;
            }
            ObjectNode root = objectMapper.createObjectNode();
            root.put("version", 1);
            ArrayNode events = root.putArray("events");
            undeliveredExternal.forEach((content, receivedAt) -> events.addObject()
                    .put("content", content)
                    .put("receivedAt", receivedAt.toString()));
            Files.createDirectories(file.getParent());
            Path temp = Files.createTempFile(file.getParent(), "." + file.getFileName(), ".tmp");
            try {
                Files.writeString(temp, objectMapper.writeValueAsString(root));
                try {
                    Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE,
                            StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException e) {
                    Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(temp);
            }
        } catch (IOException e) {
            // This chat still delivers them from memory; only a later chat would miss them.
        }
    }

    /** Reads the pending-events file; returns the events this handler did not know yet. */
    private List<String> restorePendingEvents() {
        List<String> restored = new ArrayList<>();
        Path file = pendingEventsFile();
        if (file == null || !Files.isRegularFile(file)) return restored;
        try {
            for (JsonNode event : objectMapper.readTree(Files.readString(file)).path("events")) {
                String content = event.path("content").asText("").strip();
                if (content.isEmpty() || DirectLlmClient.isProviderFollowUp(content)) continue;
                if (undeliveredExternal.putIfAbsent(content, receivedAt(event)) == null) {
                    restored.add(content);
                }
            }
        } catch (IOException | RuntimeException e) {
            // An unreadable file must not stop the chat; the events in it are lost.
        }
        return restored;
    }

    private static Instant receivedAt(JsonNode event) {
        try {
            return Instant.parse(event.path("receivedAt").asText(""));
        } catch (DateTimeParseException e) {
            return Instant.now();
        }
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
     * /continue auto-reply: when the finished assistant turn matches a trigger
     * keyword, dispatch its stored reply as an ordinary user turn — ahead of
     * queued input, only when no human/critical lane claimed the release.
     */
    private boolean dispatchContinueAutoReply() {
        if (continueManager == null) return false;
        String response = lastAssistantResponse.getAndSet(null);
        ContinueManager.Decision decision =
                continueManager.decide(response, !repl.isForceAgentic(),
                        agenticLoop.isPlanningMode());
        if (decision.notice() != null) {
            ChatCompleter.showNotice(renderer.yellow("  " + decision.notice()));
            chatHistory.logSystem("[continue] " + decision.notice());
        }
        if (!decision.fire() || decision.reply() == null) return false;
        String reply = decision.reply();
        ChatCompleter.showNotice(renderer.cyan("  ▶ /continue auto-reply: "
                + StringUtils.truncate(reply, 60)));
        chatHistory.logSystem("[continue] auto-reply dispatched (keyword match)");
        dispatchTurn(reply, () -> handleAcceptedChatMessage(reply), "standard-chat-continue");
        continueManager.noteAutoReplySent();
        return true;
    }

    /**
     * Completes one accepted turn without launching its successor; dispatchTurn's
     * owner-release callback picks that (dispatchSuccessorAfterTurnRelease).
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
            // A turn the provider started by itself is already running: it goes first.
            String followUp = pollProviderFollowUp();
            if (followUp != null) return externalInputAtBoundary(followUp, List.of(followUp));
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
            List<String> events = pollQueuedEvents();
            if (!events.isEmpty()) {
                return externalInputAtBoundary(composeExternalDelivery(events), events);
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

    /**
     * @param delivery the text the agent receives
     * @param events   the queued entries it carries; a declined claim puts them
     *                 back at the head of the queue, in order
     */
    private AgenticChatLoop.QueuedInput externalInputAtBoundary(String delivery, List<String> events) {
        return new AgenticChatLoop.QueuedInput(delivery, () -> {
            synchronized (turnDispatchLock) {
                if (cancelSignal.get() || !acceptingExternalMessages.get()) {
                    return false;
                }
                externalWorkClaimed.set(true);
                chatHistory.logSystem(delivery);
                markExternalDelivered(events);
                ChatCompleter.showNotice(renderer.cyan(
                        "  ↻ Applying completion event at agent boundary"));
                repl.requestStatusRedraw();
                return true;
            }
        }, () -> {
            synchronized (turnDispatchLock) {
                externalWorkClaimed.set(false);
                if (acceptingExternalMessages.get()) {
                    for (int i = events.size() - 1; i >= 0; i--) {
                        mandatoryExternalMessages.remove(events.get(i));
                        mandatoryExternalMessages.addFirst(events.get(i));
                    }
                }
            }
        });
    }

    private boolean hasBackgroundedActiveTurn() {
        BackgroundTaskManager.BackgroundTask task = backgroundTaskManager.getCurrentTask();
        return turnActive.get() && activeDispatchThread.get() != null
                && (claudeBackgroundInputReleased || (task != null && task.getStatus()
                == BackgroundTaskManager.BackgroundTask.BackgroundTaskStatus.BACKGROUNDED));
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
        finishForegroundProgress();
        if (cancelSignal.get()) {
            ChatCompleter.markInterrupted();
        } else {
            ChatCompleter.setActivity(null);
        }
    }

    private void emitInterruptedMessage(BackgroundTaskManager.BackgroundTask task) {
        repl.stopGeneratingSpinner();
        String label = agenticLoop.getStopLabel();
        emitLine(renderer.yellow("  ⊘ " + label));
        if (task != null) {
            task.appendOutput("\n[" + label + "]");
        }
    }

    /**
     * Terminal failure evidence for the usage-limit watchdog: the exception
     * message alone loses the in-band error envelope ("[OpenAI Codex API error
     * 429: usage limit reached]") that the provider streamed into the turn's
     * retained output before failing.
     */
    private static String textOf(BackgroundTaskManager.BackgroundTask task) {
        if (task == null) return "";
        String output = task.getOutput();
        return output == null ? "" : output;
    }

    private void startActivityIndicator() {
        setForegroundActivity("Thinking");
        beginForegroundProgress();
        repl.requestStatusRedraw();
        // With an active LineReader the persistent status bar renders the
        // foreground RUNNING task. A carriage-return spinner would overwrite
        // the draft the user is typing for the queue.
        if (!ChatCompleter.hasLineReader()) {
            repl.printGeneratingIndicator();
        }
    }

    /**
     * Open a new request-scoped progress window (working word + elapsed clock
     * + token counter reset). Seq-guarded so late events from an earlier turn
     * cannot leak into this one.
     */
    private void beginForegroundProgress() {
        ForegroundRequestProgress progress = ChatUiSession.current().progress();
        if (progress != null) {
            progress.begin();
            agenticLoop.setForegroundProgress(progress);
        }
    }

    /** Close the foreground progress window for the current turn (idempotent). */
    private void finishForegroundProgress() {
        ForegroundRequestProgress progress = ChatUiSession.current().progress();
        if (progress != null) {
            progress.finishAll();
        }
    }

    // ========================================================================
    // Local / agentic chat
    // ========================================================================

    public void handleLocalChat(String message) {
        // A turn Claude Code started by itself carries no user text: it gets no
        // memory, and pending attachments wait for the user's next message.
        boolean providerFollowUp = DirectLlmClient.isProviderFollowUp(message);
        // Claude Code-style: a bare image path in the prompt becomes an attached image
        // plus an [Image #N] chip so the model sees the pixels, not the filename.
        if (!providerFollowUp) message = repl.autoAttachImagePaths(message);
        // Build memory-enriched message if memory is enabled
        String enrichedMessage = message;
        if (!providerFollowUp && chatMemory != null && chatMemory.isEnabled()) {
            String memoryContext = chatMemory.buildMemoryContext(message);
            if (memoryContext != null) {
                enrichedMessage = "<memory_context>\n" + memoryContext + "</memory_context>\n\n" + message;
            }
        }

        // Load pending attachments and pass to agentic loop
        List<DirectLlmClient.AttachmentInput> attachments = providerFollowUp ? null : loadAttachments();
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
            lastAssistantResponse.set(response);
            usageLimitAutoContinue.noteTurnSucceeded();
        } catch (Exception e) {
            if (cancelSignal.get()) {
                emitInterruptedMessage(task);
            } else {
                repl.stopGeneratingSpinner();
                String failure = e.getMessage() == null ? "" : e.getMessage();
                emitLine(renderer.red("Error in chat: " + e.getMessage()));
                // Only provider failure evidence may set the quota wake, not prior model/tool output.
                usageLimitAutoContinue.onTurnFailure(failure, message, !repl.isForceAgentic(), quotaTurn.get());
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
                lastAssistantResponse.set(finalAnswer);
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
                                String label = agenticLoop.getStopLabel();
                                emitLine("\n" + renderer.yellow("  ⊘ " + label));
                                fullResponse.append("\n[").append(label).append("]");
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
            lastAssistantResponse.set(responseText);

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
            lastAssistantResponse.set(response);
            usageLimitAutoContinue.noteTurnSucceeded();

        } catch (Exception e) {
            BackgroundTaskManager.BackgroundTask parentTask = backgroundTaskManager.getCurrentTask();
            if (cancelSignal.get()) {
                emitInterruptedMessage(parentTask);
            } else {
                repl.stopGeneratingSpinner();
                String failure = e.getMessage() == null ? "" : e.getMessage();
                emitLine(renderer.red("Error in agentic chat: " + e.getMessage()));
                usageLimitAutoContinue.onTurnFailure(failure, message, !repl.isForceAgentic(), quotaTurn.get());
                if (parentTask != null) parentTask.setError(e);
            }
        }
    }

    private String quotaProvider() {
        return repl.getChatConfig() == null ? "" : repl.getChatConfig().getProvider();
    }

    /**
     * Re-dispatch the failed message without treating it as new user input.
     * The dispatch lock and watchdog ticket reject stale wakes while preserving
     * the automatic retry budget for the same provider and session.
     */
    private void resumeAfterUsageLimitWindow(UsageLimitAutoContinue.Resume resume) {
        String message = resume.message();
        synchronized (turnDispatchLock) {
            // Never replay into a different vendor. Busy maintenance/external
            // owners defer the wake until release, without spending another retry.
            if (!acceptingDispatches.get() || !usageLimitAutoContinue.isCurrent(resume)) return;
            if (hasActiveTurn()) {
                deferredQuotaResume = resume;
                return;
            }
            ChatCompleter.showNotice(renderer.cyan("  ▶ Usage limit window elapsed — auto-continuing: "
                    + StringUtils.truncate(message, 60)));
            chatHistory.logSystem("[auto-continue] usage-limit window elapsed; resending the failed turn");
            // This is not user input: preserve the watchdog's consecutive retry budget.
            dispatchTurn(message, () -> handleAcceptedChatMessage(message), "usage-limit-resume");
        }
    }

    /** Called under turnDispatchLock, after the previous owner has released. */
    private boolean dispatchDeferredQuotaResume() {
        UsageLimitAutoContinue.Resume resume = deferredQuotaResume;
        deferredQuotaResume = null;
        if (resume == null || !acceptingDispatches.get() || !usageLimitAutoContinue.isCurrent(resume)) return false;
        resumeAfterUsageLimitWindow(resume);
        return true;
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
                emitLine(renderer.yellow("[" + agenticLoop.getStopLabel() + "]"));
                break;

            default:
                break;
        }
    }

    // ========================================================================
    // Attachment loading
    // ========================================================================

    /**
     * Load pending attachments into DirectLlmClient.AttachmentInput format the way a
     * headless turn does ({@link ChatAttachmentLoader#read}), within the same total limit.
     * A file that can't be read or doesn't fit is skipped with a warning; the rest still go.
     */
    public List<DirectLlmClient.AttachmentInput> loadAttachments() {
        if (pendingAttachments.isEmpty()) return null;

        List<DirectLlmClient.AttachmentInput> loaded = new ArrayList<>();
        long total = 0;
        for (ChatRepl.PendingAttachment att : pendingAttachments) {
            try {
                long size = Files.size(att.path());
                if (total + size > ChatAttachmentLoader.MAX_TOTAL_BYTES) {
                    throw new IOException("attachments exceed the 20 MiB total limit");
                }
                loaded.add(ChatAttachmentLoader.read(att.path(), att.mimeType(), att.isImage()));
                total += size;
            } catch (Exception e) {
                emitLine(renderer.yellow("Warning: Skipped attachment "
                        + att.path().getFileName() + ": " + e.getMessage()));
            }
        }

        // Clear pending after loading
        pendingAttachments.clear();
        return loaded.isEmpty() ? null : loaded;
    }

}
