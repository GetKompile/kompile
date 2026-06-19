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
import ai.kompile.cli.main.chat.agent.SubprocessAgentRunner;
import ai.kompile.cli.mcp.stdio.TaskRecord;
import ai.kompile.cli.mcp.stdio.TaskRegistry;
import ai.kompile.utils.FormatUtils;
import ai.kompile.cli.main.chat.config.SystemPromptManager;
import ai.kompile.cli.main.chat.enforcer.*;
import ai.kompile.cli.main.chat.harness.HarnessConfig;
import ai.kompile.cli.main.chat.render.AsciiRenderer;
import ai.kompile.cli.main.chat.render.TerminalRenderer;
import ai.kompile.cli.main.chat.tools.BackgroundProcessManager;
import ai.kompile.cli.main.chat.tui.KompileTui;
import ai.kompile.cli.main.chat.tui.StatusBar;
import com.fasterxml.jackson.databind.ObjectMapper;
import picocli.CommandLine;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.jline.reader.Candidate;
import org.jline.reader.ParsedLine;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.UserInterruptException;
import org.jline.reader.EndOfFileException;
import org.jline.reader.Widget;
import org.jline.reader.impl.LineReaderImpl;
import org.jline.keymap.KeyMap;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;


/**
 * Emulated passthrough mode: runs an external CLI agent (Claude Code, Codex, etc.)
 * as a Kompile-managed interactive subprocess while maintaining the Kompile chat
 * interface.
 * <p>
 * Kompile owns the terminal UI, sends user messages through the child process
 * stdin, reads stdout/stderr through parent-child process IO, and renders the
 * result itself. Provider-specific prompt-mode flags are intentionally avoided
 * here; backgrounding and queueing are Kompile-managed behaviors.
 */
@CommandLine.Command(
        name = "emulated-passthrough",
        description = "Emulated passthrough mode — agent subprocess with kompile UI",
        mixinStandardHelpOptions = true
)
public class EmulatedPassthroughCommand implements Callable<Integer> {

    @CommandLine.Option(names = {"--agent", "-a"}, description = "Agent command name", defaultValue = "claude")
    String agent;

    @CommandLine.Option(names = {"--working-dir", "-d"}, description = "Working directory for the agent", defaultValue = ".")
    String workingDir;

    @CommandLine.Option(names = {"--skip-permissions"}, description = "Skip permission prompts", defaultValue = "true")
    boolean skipPermissions;

    @CommandLine.Option(names = {"--inject-tools"}, description = "Inject kompile tools via MCP", defaultValue = "true")
    boolean injectTools;

    @CommandLine.Option(names = {"--url", "-u"}, description = "Kompile-app base URL for MCP tools", defaultValue = "")
    String kompileUrl;

    @CommandLine.Option(names = {"--mcp-port"}, description = "Port for embedded MCP server (0 = auto-detect)", defaultValue = "0")
    int mcpPort;

    @CommandLine.Option(names = {"--resume-session"}, description = "Session ID to resume — loads and displays prior Kompile conversation turns")
    String resumeSessionId;

    private final ObjectMapper objectMapper = JsonUtils.standardMapper();
    private final PassthroughStreamParser parser = new PassthroughStreamParser();
    private final ChatCompleter chatCompleter = new ChatCompleter(() -> null);
    private McpUrlResolver mcpUrlResolver = new McpUrlResolver();

    private TerminalRenderer renderer;
    private AsciiRenderer ascii;
    private KompileTui tui;
    private ai.kompile.cli.main.chat.tui.VirtualTerminal virtualTerminal;
    private long vtCreatedAt; // timestamp when VT was created (for init-only responses)

    // Track whether the managed session has sent at least one message.
    private volatile boolean firstMessageSent = false;

    // Agent session IDs — captured from first structured event, used for multi-turn.
    // Volatile: written by output reader thread, read by main thread in buildCommand.
    private volatile String agentSessionId = null;

    // Active subprocess (for cancellation)
    private volatile Process activeProcess;
    private volatile Thread waitingThread; // thread blocked in waitFor, for interrupt
    private volatile Thread replThread; // thread blocked in readLine, for idle shutdown
    private volatile LineReader activeLineReader; // current readLine owner for async redisplay
    private volatile String activePromptLastRenderedLine = "";
    private volatile int activePromptLastCursorCol = -1;
    private final AtomicBoolean cancelSignal = new AtomicBoolean(false);
    private final AtomicBoolean shutdownSignal = new AtomicBoolean(false);
    private final AtomicBoolean backgroundSignal = new AtomicBoolean(false);
    private final AtomicInteger backgroundTurnCount = new AtomicInteger(0);
    private sun.misc.SignalHandler sigintHandler; // re-installed before each subprocess

    // Interactive prompt handling — agent stdin is kept open so we can write responses
    // when the agent asks questions or needs approval.
    private volatile OutputStream agentStdin;
    private final LinkedBlockingQueue<PassthroughStreamParser.PassthroughEvent> interactiveQueue = new LinkedBlockingQueue<>();

    // Terminal and line reader — stored as fields for interactive prompt handling
    private Terminal terminal;
    private LineReader lineReader;

    // Injected settings file path (for cleanup)
    private Path injectedSettingsFile;

    // Set during agent processing for SIGINT handling
    private volatile boolean agentBusy = false;
    // Tracks last subprocess output timestamp for status/debug logging.
    private final AtomicLong lastOutputTime = new AtomicLong(0);
    // Last message sent to agent — used to filter PTY echo of user input
    private volatile String lastSentMessage;
    // Subprocess log file — raw PTY output for debugging
    private volatile java.io.Writer subprocessLogWriter;
    // Raw PTY byte dump — replay with `cat` to see exact subprocess rendering.
    // Stored at ~/.kompile/logs/agent-pty-dump.bin (per-session, overwritten each launch).
    private volatile java.io.OutputStream subprocessPtyDump;
    // Agent-specific TUI decoder — knows each agent's chrome layout and terminal queries.
    private volatile ai.kompile.cli.main.chat.tui.AgentTuiDecoder agentDecoder;

    // Scroll region layout — input box stays fixed at bottom, chat scrolls above
    private int scrollBottom;  // last row of scroll region (1-indexed)
    private int inputRows = 1;
    private int activityRows = 2;
    private volatile String currentStatus = "idle";
    private final List<String> scrollbackLines = new ArrayList<>();
    private int scrollViewportOffset = 0; // lines above live bottom; 0 means follow output
    private int liveDecoderScrollbackStart = -1;
    private int liveDecoderScrollbackLength = 0;

    private final Object activityLock = new Object();
    /** Shared draw lock — same instance as KompileTui.drawLock to prevent interleaved ANSI. */
    private Object drawLock;
    private final Deque<ActivityItem> backgroundActivities = new ArrayDeque<>();
    private final Deque<ActivityItem> subagentActivities = new ArrayDeque<>();
    private final LinkedHashMap<String, TodoActivityItem> activeTodos = new LinkedHashMap<>();
    private volatile List<String> slashCompletionLines = List.of();
    private volatile boolean activityMenuOpen = false;
    private volatile String activityMenuMessage = "";
    private volatile boolean activityFocusActive = false;
    private volatile String selectedActivityId = "";
    private volatile int selectedActivityIndex = -1;
    private final AtomicInteger activitySequence = new AtomicInteger();

    // Managed passthrough message queue. Normal turns serialize; once a response
    // is backgrounded, provider capabilities decide whether follow-ups use native
    // backgrounding/forking or Kompile-managed isolated subprocesses.
    private MessageQueue messageQueue;
    private boolean autoDequeueEnabled = true;
    private volatile boolean busyInputActive = false;
    private volatile String busyInputBuffer = "";
    private volatile String busyEditingQueuedMessageId = null;
    private volatile String busyPrompt = "";
    private volatile String pendingIdleDraft = "";
    private final List<String> busyInputHistory = Collections.synchronizedList(new ArrayList<>());

    // Persistent TUI process (OpenCode) — launched once, reused across messages
    private volatile Process tuiProcess;
    private volatile Thread tuiOutputReader;
    private volatile String tuiSubagentId;
    // Current response state for persistent TUI — swapped on each message
    private volatile StringBuilder tuiFullText;
    private volatile List<String> tuiToolCalls;
    private volatile ChatSessionMetrics tuiMetrics;
    private volatile TerminalRenderer.SpinnerHandle tuiSpinner;
    private volatile AtomicBoolean tuiSpinnerStopped;
    private volatile StringBuilder tuiPendingText;
    private volatile String tuiLastDecodedContent = "";
    private volatile String tuiLastRenderedContent = "";
    private final AtomicBoolean tuiTurnSawContent = new AtomicBoolean(false);
    private final AtomicLong tuiLastDecodedAt = new AtomicLong(0);
    // Streaming sanitizer for subprocess PTY bytes before they reach the real terminal.
    private final TerminalQueryStripper tuiQueryStripper = new TerminalQueryStripper();

    // Optional: set by ChatCommand when platform init is done externally
    SystemPromptManager systemPromptManager;

    private enum BackgroundDispatchMode {
        NATIVE_PROVIDER,
        PROVIDER_FORK,
        KOMPILE_MANAGED
    }

    private static final class ActivityItem {
        private final String key;
        private final String label;
        private final long startedAtMillis;
        private final Deque<String> logs = new ArrayDeque<>();
        private final Process process;
        private String latestLog;

        private ActivityItem(String key, String label, String latestLog) {
            this(key, label, latestLog, null);
        }

        private ActivityItem(String key, String label, String latestLog, Process process) {
            this.key = key;
            this.label = label;
            this.process = process;
            this.startedAtMillis = System.currentTimeMillis();
            addLog(latestLog);
        }

        private void addLog(String log) {
            String normalized = log == null ? "" : log.replaceAll("\\s+", " ").trim();
            this.latestLog = normalized;
            if (normalized.isBlank()) return;
            logs.addLast(normalized);
            while (logs.size() > 40) {
                logs.removeFirst();
            }
        }
    }

    private record ActivityMenuItem(String id, String kind, String label, String status,
                                    String latestLog, List<String> logs, String outputPath,
                                    boolean killable, boolean registryBacked) {}

    private static final class TodoActivityItem {
        private final String key;
        private String id;
        private String content;
        private String status;
        private String priority;

        private TodoActivityItem(String key, String id, String content, String status, String priority) {
            this.key = key;
            this.id = id == null ? "" : id;
            this.content = content == null ? "" : content;
            this.status = status == null ? "pending" : status;
            this.priority = priority == null ? "" : priority;
        }
    }

    // Enforcer support — set by ChatCommand when enforcer rules are active.
    // When non-null, every agent turn is wrapped with enforcement (retry on violations).
    EnforcerEvaluator enforcerEvaluator;
    EnforcerPolicy enforcerPolicy;
    EnforcerService enforcerService;
    EnforcerConversationWindow enforcerConversationWindow;
    Map<String, String> enforcerExtraEnv;

    // ANSI — delegated to shared constants
    private static final String RESET = ai.kompile.utils.AnsiConstants.RESET;
    private static final String CYAN = ai.kompile.utils.AnsiConstants.CYAN;
    private static final String GREEN = ai.kompile.utils.AnsiConstants.GREEN;
    private static final String YELLOW = ai.kompile.utils.AnsiConstants.YELLOW;
    private static final String DIM = ai.kompile.utils.AnsiConstants.DIM;
    private static final String BOLD = ai.kompile.utils.AnsiConstants.BOLD;
    private static final String INVERSE = ai.kompile.utils.AnsiConstants.INVERSE;

    private static final String ANSI_REGEX = ai.kompile.utils.AnsiConstants.ANSI_STRIP_REGEX;

    @Override
    public Integer call() {
        Terminal term = null;
        try {
            term = ChatCompleter.buildSystemTerminal();
            this.terminal = term;
            renderer = new TerminalRenderer();
            int termWidth = term.getWidth();
            if (termWidth <= 0) termWidth = 120;
            ascii = new AsciiRenderer(renderer, termWidth);

            this.lineReader = LineReaderBuilder.builder()
                    .terminal(term)
                    .completer(chatCompleter)
                    .build();

            // Managed passthrough owns the fixed bottom input box itself. Do not
            // enable ChatCompleter's JLine post border here; that second border
            // pushes the cursor/input below this box in some terminals.
            ChatCompleter.setTerminalRef(lineReader, term);
            resetHostInputModes();

            // SIGINT handler. Do not delegate to JLine's saved handler here:
            // under native-image it can be NativeSignalHandler, whose handle()
            // throws UnsupportedOperationException instead of shutting down.
            sigintHandler = sig -> handleSigint();

            // Install via JLine terminal (fires when JLine is reading input).
            terminal.handle(Terminal.Signal.INT, terminalSigintHandler());

            // Install via sun.misc.Signal (fires during process.waitFor).
            // JLine's readLine() will override this while reading, so we
            // re-install it before each subprocess in sendToAgent().
            sun.misc.SignalHandler previousHandler = null;
            try {
                previousHandler = sun.misc.Signal.handle(
                        new sun.misc.Signal("INT"), sigintHandler);
            } catch (IllegalArgumentException e) {
                // Signal handling not supported
            }
            final sun.misc.SignalHandler savedHandler = previousHandler;

            // Resolve agent binary
            String agentBinary = resolveAgent(agent);
            if (agentBinary == null) {
                System.err.println("Agent '" + agent + "' not found on PATH.");
                System.err.println("Supported agents: " + String.join(", ",
                        ai.kompile.cli.main.chat.config.ChatConfig.getPassthroughAgentOrder()));
                System.err.println("Install the agent and make sure it is on your PATH.");
                return 1;
            }

            // Session setup
            String sessionId = "emulated-" + UUID.randomUUID().toString().substring(0, 8);
            ChatHistory history = new ChatHistory(sessionId);
            ChatSessionMetrics metrics = new ChatSessionMetrics(sessionId);
            metrics.setAgentName(agent);
            this.messageQueue = new MessageQueue(sessionId);
            Instant startTime = Instant.now();

            try {
                history.open("", agent + " (emulated)", false);
            } catch (IOException e) {
                System.err.println("Warning: Could not open chat history: " + e.getMessage());
            }

            // MCP tool injection
            injectMcpTools();

            // System prompt injection (if configured externally or auto-resolved)
            if (systemPromptManager == null) {
                systemPromptManager = SystemPromptManager.resolve(null, null, null);
            }
            if (systemPromptManager != null) {
                Path wd = Path.of(workingDir).toAbsolutePath().normalize();
                Path injectedPromptFile = systemPromptManager.injectInstructionFile(agent, wd);
                if (injectedPromptFile != null) {
                    System.out.println(GREEN + "System prompt injected" + RESET);
                }
            }

            // Bind a local cancel fallback. Raw Escape must stay available to
            // the underlying agent/terminal Meta handling.
            bindCancelKey(lineReader);
            enableManagedSlashCompletion(lineReader);

            // Initialize unified TUI — KompileTui is the ONE layout manager
            BackgroundTaskManager bgTaskMgr = new BackgroundTaskManager();
            BackgroundProcessManager bgProcMgr = new BackgroundProcessManager(sessionId);
            this.tui = new KompileTui(bgTaskMgr, bgProcMgr, messageQueue, renderer);
            this.drawLock = tui.getDrawLock();
            tui.setAgentName(agent);
            tui.setSessionId(sessionId);
            tui.setMode("passthrough");
            tui.setEnforcerActive(enforcerEvaluator != null);
            // Tell KompileTui to reserve rows for input box area (queue + busy + borders + input)
            tui.setReservedRowsCalculator((h, w) -> {
                int ir = h < 12 ? 1 : Math.max(3, Math.min(8, h / 5));
                return ir + 4;
            });
            tui.start(terminal);
            initScrollLayout();

            // Layer 2→3 resize propagation: when the user resizes their terminal,
            // update kompile's scroll layout, resize the VirtualTerminal, and
            // propagate the new size to the subprocess PTY.
            terminal.handle(Terminal.Signal.WINCH, signal -> {
                // Layer 2: update KompileTui's internal state (bars, scroll region)
                tui.handleResize();
                // Layer 2: update our input box layout
                initScrollLayout();
                // Layer 3: resize VT emulator and subprocess PTY
                int newRows = terminal.getHeight();
                int newCols = terminal.getWidth();
                if (newRows <= 0) newRows = 24;
                if (newCols <= 0) newCols = 120;
                if (virtualTerminal != null) {
                    virtualTerminal.resize(newRows, newCols);
                }
                // Propagate to subprocess via stty (writes to stdin)
                Process proc = tuiProcess;
                OutputStream os = agentStdin;
                if (proc != null && proc.isAlive() && os != null) {
                    try {
                        // Send SIGWINCH to the subprocess process group
                        // so Bubble Tea knows to re-query terminal size.
                        // The stty in the wrapper already set initial size;
                        // for runtime resize, write a resize escape sequence.
                        // Some TUI apps also respond to CSI 8;rows;cols t
                        // but the most reliable method is to let the PTY
                        // handle it naturally via the process group signal.
                        long pid = proc.pid();
                        new ProcessBuilder("kill", "-WINCH", String.valueOf(pid))
                                .redirectErrorStream(true).start();
                    } catch (Exception ignored) {}
                }
            });

            // Welcome — prints into scroll region
            printWelcomePanel(agentBinary);

            // If resuming, replay prior conversation turns into Kompile's UI.
            // The managed subprocess launch remains provider-agnostic.
            if (resumeSessionId != null && !resumeSessionId.isBlank()) {
                replayConversationHistory(resumeSessionId, history);
                agentSessionId = resumeSessionId;
                firstMessageSent = true;
            }

            replThread = Thread.currentThread();
            try {
                while (true) {
                    if (shutdownSignal.get()) break;

                    String line;
                    try {
                        // Re-establish scroll region before positioning (JLine may have reset it)
                        tui.reestablishScrollRegion();
                        scrollBottom = tui.scrollBottom();
                        // Position cursor at prompt row (fixed area below scroll region)
                        positionAtPrompt();
                        resetActivePromptRenderCache();
                        String restoredDraft = takePendingIdleDraft();
                        activeLineReader = lineReader;
                        line = restoredDraft.isEmpty()
                                ? lineReader.readLine(buildPrompt())
                                : lineReader.readLine(buildPrompt(), null,
                                        (org.jline.reader.MaskingCallback) null, restoredDraft);
                    } catch (UserInterruptException e) {
                        if (agentBusy) {
                            // Ctrl+C while agent processing → cancel agent, keep REPL alive
                            handleSigint();
                            continue;
                        }
                        // Ctrl+C at idle prompt → exit
                        break;
                    } catch (EndOfFileException e) {
                        break;
                    } catch (IOError e) {
                        break;
                    } finally {
                        activeLineReader = null;
                        clearSlashCompletionPanel();
                    }

                    if (line == null || line.isBlank()) {
                        continue;
                    }
                    String trimmed = line.trim();

                    // Re-establish scroll region (JLine's readLine may have reset it)
                    tui.reestablishScrollRegion();
                    scrollBottom = tui.scrollBottom();

                    if (trimmed.startsWith("/")) {
                        String result = handleSlashCommand(trimmed, lineReader, history, metrics);
                        if ("quit".equals(result)) break;
                    } else if (agentBusy) {
                        // Agent is processing — queue the message for later dispatch
                        recordInputHistory(trimmed);
                        enqueueBusyMessage(trimmed);
                        safePrintln(DIM + "  Queued: " + RESET + trimmed);
                    } else {
                        // Agent idle — echo and dispatch, then drain any queued messages
                        recordInputHistory(trimmed);
                        safePrintln(BOLD + "  > " + RESET + trimmed);
                        dispatchToAgentAsync(trimmed, history, metrics);
                    }
                }
            } finally {
                replThread = null;

                // Kill persistent TUI process if running
                if (tuiProcess != null && tuiProcess.isAlive()) {
                    killProcess(tuiProcess);
                    tuiProcess = null;
                }
                // Unregister persistent subprocess from status bar
                if (tuiSubagentId != null && tui != null) {
                    tui.getStatusBar().unregisterSubagent(tuiSubagentId);
                    tuiSubagentId = null;
                }

                // Cleanup
                ChatCompleter.setQueueSupplier(null);
                removeMcpTools();
                if (systemPromptManager != null) {
                    systemPromptManager.cleanup();
                }
                if (enforcerEvaluator instanceof AutoCloseable closeable) {
                    try { closeable.close(); } catch (Exception ignored) {}
                }
                resetHostInputModes();

                // Stop the unified TUI (resets scroll regions, clears screen)
                if (tui != null) {
                    tui.stop();
                } else {
                    System.out.print("\033[r\033[2J\033[H");
                    System.out.flush();
                }

                printSessionSummary(metrics, history, sessionId, startTime);

                Path metricsFile = history.getTranscriptFile()
                        .resolveSibling(sessionId + ".metrics.json");
                metrics.saveToFile(metricsFile, objectMapper);
                history.close();

                renderer.resetTerminalTitle();

                // Restore previous SIGINT handler
                if (savedHandler != null) {
                    try {
                        sun.misc.Signal.handle(new sun.misc.Signal("INT"), savedHandler);
                    } catch (IllegalArgumentException ignored) {}
                }
            }

            return 0;
        } catch (IOException e) {
            System.err.println("Error initializing terminal: " + e.getMessage());
            return 1;
        } finally {
            // Close terminal in a guarded block — stty may fail if the
            // thread was interrupted during shutdown (GraalVM native image
            // or Ctrl+C race). Swallow the error for a clean exit.
            if (term != null) {
                try {
                    term.close();
                } catch (IOException | IOError ignored) {
                }
            }
        }
    }

    // ── Scroll region layout ─────────────────────────────────────────────

    /**
     * Set up the scroll region layout: chat area scrolls in the top portion,
     * input box is fixed at the bottom. This matches how codex/opencode work.
     *
     * Layout (1-indexed rows):
     *   Row 1 .. scrollBottom     — scroll region (chat + agent output)
     *   Row scrollBottom+1        — queued message preview
     *   Row scrollBottom+2        — busy/background prompt
     *   Row scrollBottom+3        — top input border  ─────────
     *   Row scrollBottom+4        — prompt:  kompile [agent]> _
     *   Row ...                   — bottom input border ─────────
     *   Next reserved row         — status line
     *   Rows below status         — background processes, subagents, todos
     */
    /**
     * Recalculate input row counts and tell KompileTui how many middle rows
     * to reserve. KompileTui owns the scroll region — we just read
     * scrollBottom from it and draw the input box in the reserved space.
     * StatusBar (managed by KompileTui) is the sole bottom bar.
     */
    private void initScrollLayout() {
        synchronized (drawLock) {
            int h = terminal.getHeight();
            if (h <= 0) h = 24;

            inputRows = h < 12 ? 1 : Math.max(3, Math.min(8, h / 5));
            // reserved = queue(1) + busy(1) + topBorder(1) + input(inputRows) + bottomBorder(1)
            int reserved = inputRows + 4;
            tui.setReservedMiddleRows(reserved);
            tui.reestablishScrollRegion();
            scrollBottom = tui.scrollBottom();
            clampScrollViewportOffsetLocked();
            redrawScrollViewportContentLocked();
            drawFixedInputBox();
        }
    }

    private int scrollTopRow() {
        return tui != null ? tui.scrollTop() : 1;
    }

    private int scrollViewportRows() {
        return Math.max(1, scrollBottom - scrollTopRow() + 1);
    }

    private int maxScrollViewportOffsetLocked() {
        return Math.max(0, scrollbackLines.size() - scrollViewportRows());
    }

    private void clampScrollViewportOffsetLocked() {
        scrollViewportOffset = Math.max(0, Math.min(scrollViewportOffset, maxScrollViewportOffsetLocked()));
    }

    private void appendScrollbackLineLocked(String text) {
        scrollbackLines.add(text == null ? "" : text);
        if (scrollViewportOffset > 0) {
            scrollViewportOffset++;
        }
        clampScrollViewportOffsetLocked();
    }

    private void resetLiveDecoderScrollbackBlock() {
        synchronized (drawLock) {
            liveDecoderScrollbackStart = -1;
            liveDecoderScrollbackLength = 0;
        }
    }

    private void updateLiveDecoderScrollbackBlock(String decodedText, boolean finalSnapshot) {
        if (decodedText == null || decodedText.isBlank()) return;
        synchronized (drawLock) {
            if (liveDecoderScrollbackStart < 0 || liveDecoderScrollbackStart > scrollbackLines.size()) {
                liveDecoderScrollbackStart = scrollbackLines.size();
                liveDecoderScrollbackLength = 0;
            }
            List<String> rendered = renderDecodedSnapshotLines(decodedText);
            int oldSize = scrollbackLines.size();
            int blockEnd = Math.min(scrollbackLines.size(), liveDecoderScrollbackStart + liveDecoderScrollbackLength);
            for (int i = blockEnd - 1; i >= liveDecoderScrollbackStart; i--) {
                scrollbackLines.remove(i);
            }
            scrollbackLines.addAll(liveDecoderScrollbackStart, rendered);
            liveDecoderScrollbackLength = rendered.size();
            int newSize = scrollbackLines.size();
            if (scrollViewportOffset > 0) {
                scrollViewportOffset = Math.max(0, scrollViewportOffset + (newSize - oldSize));
            }
            clampScrollViewportOffsetLocked();
            redrawScrollViewportLocked(activeLineReader);
        }
    }

    private void finishLiveDecoderScrollbackBlock() {
        synchronized (drawLock) {
            liveDecoderScrollbackStart = -1;
            liveDecoderScrollbackLength = 0;
        }
    }

    private List<String> renderDecodedSnapshotLines(String decodedText) {
        String filtered = filterDecodedTuiTextForDisplay(decodedText);
        if (filtered.isBlank()) return List.of();
        String rendered = ascii.renderMarkdown(filtered);
        List<String> lines = new ArrayList<>();
        for (String line : rendered.split("\n", -1)) {
            lines.add("  " + line);
        }
        return lines;
    }

    private String filterDecodedTuiTextForDisplay(String text) {
        String echoTrimmed = lastSentMessage == null ? "" : lastSentMessage.trim();
        StringBuilder filtered = new StringBuilder();
        String[] lines = text.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String line = stripTrailingWhitespace(lines[i]);
            String stripped = line.trim();
            if (!stripped.isEmpty()) {
                if (!echoTrimmed.isEmpty() && (stripped.equals(echoTrimmed) || stripped.endsWith(echoTrimmed))) {
                    continue;
                }
                filtered.append(line);
            }
            if (i < lines.length - 1) {
                filtered.append('\n');
            }
        }
        return filtered.toString();
    }

    private boolean scrollTranscriptPageUp() {
        return scrollTranscriptBy(Math.max(1, scrollViewportRows() - 1));
    }

    private boolean scrollTranscriptPageDown() {
        return scrollTranscriptBy(-Math.max(1, scrollViewportRows() - 1));
    }

    private boolean scrollTranscriptToTop() {
        return setScrollViewportOffset(Integer.MAX_VALUE);
    }

    private boolean scrollTranscriptToBottom() {
        return setScrollViewportOffset(0);
    }

    private boolean scrollTranscriptBy(int delta) {
        synchronized (drawLock) {
            return setScrollViewportOffsetLocked(scrollViewportOffset + delta, activeLineReader);
        }
    }

    private boolean setScrollViewportOffset(int offset) {
        synchronized (drawLock) {
            return setScrollViewportOffsetLocked(offset, activeLineReader);
        }
    }

    private boolean setScrollViewportOffsetLocked(int offset, LineReader reader) {
        int before = scrollViewportOffset;
        scrollViewportOffset = Math.max(0, Math.min(offset, maxScrollViewportOffsetLocked()));
        redrawScrollViewportLocked(reader);
        return before != scrollViewportOffset;
    }

    private void redrawScrollViewportLocked(LineReader reader) {
        hideCursor();
        redrawScrollViewportContentLocked();
        if (reader != null && !busyInputActive) {
            drawFixedInputChrome();
            drawActivePromptLine(reader, true);
        } else {
            drawFixedInputBox(busyInputActive);
            if (!busyInputActive) {
                System.out.printf("\033[?25h\033[%d;1H", firstInputRow());
            }
        }
        System.out.flush();
    }

    private void redrawScrollViewportContentLocked() {
        int top = scrollTopRow();
        int rows = scrollViewportRows();
        int width = terminal != null && terminal.getWidth() > 0 ? terminal.getWidth() : 120;
        int first = Math.max(0, scrollbackLines.size() - rows - scrollViewportOffset);
        int last = Math.min(scrollbackLines.size(), first + rows);
        int source = first;
        for (int row = top; row <= scrollBottom; row++) {
            String line = source < last ? scrollbackLines.get(source++) : "";
            System.out.printf("\033[%d;1H\033[2K%s", row, fitAnsiLine(line, Math.max(1, width - 1)));
        }
    }

    /** Draw the fixed input box borders and status line below the scroll region. */
    private void drawFixedInputBox() {
        drawFixedInputBox(true);
    }

    private void drawFixedInputBox(boolean preserveCursor) {
        synchronized (drawLock) {
            int w = terminal.getWidth();
            if (w <= 0) w = 120;
            String border = DIM + "\u2500".repeat(w) + RESET;
            boolean keepCursorInInput = busyInputActive;
            String inputBuffer = busyInputBuffer;

            if (preserveCursor) saveCursor();
            drawQueuePreviewLine(w);
            drawBusyPromptLine(w);
            System.out.printf("\033[%d;1H\033[2K%s", topBorderRow(), border);
            for (int row = firstInputRow(); row <= lastInputRow(); row++) {
                System.out.printf("\033[%d;1H\033[2K", row);
            }
            System.out.printf("\033[%d;1H\033[2K%s", bottomBorderRow(), border);
            if (keepCursorInInput) {
                drawBusyInputRows(inputBuffer, w);
            } else if (preserveCursor) {
                restoreCursor();
            }
            System.out.flush();
        }
    }

    private void drawFixedInputChrome() {
        int w = terminal != null && terminal.getWidth() > 0 ? terminal.getWidth() : 120;
        String border = DIM + "\u2500".repeat(w) + RESET;
        drawQueuePreviewLine(w);
        drawBusyPromptLine(w);
        System.out.printf("\033[%d;1H\033[2K%s", topBorderRow(), border);
        System.out.printf("\033[%d;1H\033[2K%s", bottomBorderRow(), border);
    }

    private void drawQueuePreviewLine(int terminalWidth) {
        int width = Math.max(12, terminalWidth - 1);
        String line = "";
        MessageQueue.QueuedMessage queued = nextPreviewQueuedMessage();
        if (queued != null) {
            String content = truncatePlain(queued.getContent(), Math.max(20, width - 12));
            line = "  " + content + DIM + "   ↑ edit" + RESET;
        }
        System.out.printf("\033[%d;1H\033[2K%s", queuePreviewRow(), fitAnsiLine(line, width));
    }

    private void drawBusyPromptLine(int terminalWidth) {
        int width = Math.max(12, terminalWidth - 1);
        String line = busyPrompt;
        if ((line == null || line.isBlank()) && busyInputActive) {
            line = "  Enter draft · ↑ edit pending · Ctrl+B background · Esc pass-through";
        }
        System.out.printf("\033[%d;1H\033[2K%s", busyPromptRow(), DIM + truncatePlain(line, width) + RESET);
    }

    private MessageQueue.QueuedMessage nextPreviewQueuedMessage() {
        if (messageQueue == null || messageQueue.isEmpty()) return null;
        for (MessageQueue.QueuedMessage queued : messageQueue.getAll()) {
            if (!queued.getId().equals(busyEditingQueuedMessageId)) {
                return queued;
            }
        }
        return null;
    }

    private void drawBusyInputRows(String buffer, int terminalWidth) {
        int width = Math.max(12, terminalWidth - 1);
        String prefix = busyEditingQueuedMessageId != null ? "  edit> " : "  draft> ";
        String display = prefix + (buffer == null ? "" : buffer);
        List<String> rows = wrapInputDisplay(display, width);
        int from = Math.max(0, rows.size() - inputRows);
        List<String> visibleRows = rows.subList(from, rows.size());
        for (int i = 0; i < visibleRows.size(); i++) {
            String row = visibleRows.get(i);
            if (row.length() > width) row = row.substring(0, width);
            System.out.printf("\033[%d;1H\033[2K%s", firstInputRow() + i, row);
        }
        int cursorRow = firstInputRow() + Math.max(0, visibleRows.size() - 1);
        String last = visibleRows.isEmpty() ? "" : visibleRows.get(visibleRows.size() - 1);
        int cursorCol = Math.min(Math.max(1, last.length() + 1), Math.max(1, terminalWidth));
        System.out.printf("\033[%d;%dH", cursorRow, cursorCol);
    }

    private List<String> wrapInputDisplay(String text, int width) {
        if (text == null || text.isEmpty()) return List.of("");
        List<String> rows = new ArrayList<>();
        int offset = 0;
        while (offset < text.length()) {
            int end = Math.min(text.length(), offset + width);
            rows.add(text.substring(offset, end));
            offset = end;
        }
        return rows.isEmpty() ? List.of("") : rows;
    }

    private String truncatePlain(String text, int width) {
        if (text == null || width <= 0) return "";
        return text.length() > width ? text.substring(0, Math.max(0, width - 3)) + "..." : text;
    }

    private String fitAnsiLine(String text, int width) {
        if (text == null || text.isEmpty()) return "";
        String plain = stripAnsi(text);
        if (plain.length() <= width) return text;
        return truncatePlain(plain, width);
    }

    /** Update the status — delegates to StatusBar (the sole bottom bar). */
    synchronized void updateStatusLine(String status) {
        currentStatus = status == null || status.isBlank() ? "idle" : status;
        // StatusBar handles the bottom bar; request a redraw to pick up changes
        if (tui != null) {
            tui.getStatusBar().requestRedraw();
        }
    }

    private synchronized void drawActivityPanel(int terminalWidth) {
        if (tui != null) {
            pushActivityMenuToStatusBar();
            tui.getStatusBar().requestRedraw();
        }
    }

    private List<String> buildActivityLines(int terminalWidth) {
        List<String> completions = slashCompletionLines == null ? List.of() : new ArrayList<>(slashCompletionLines);
        if (!completions.isEmpty()) {
            return completions.size() <= activityRows ? completions : completions.subList(0, activityRows);
        }
        List<TodoActivityItem> todos;
        synchronized (activityLock) {
            todos = new ArrayList<>(activeTodos.values());
        }
        List<ActivityMenuItem> activityItems = activityMenuItems();
        if (activityMenuOpen) {
            return buildActivityMenuLines(terminalWidth, activityItems, true);
        }
        if (!activityItems.isEmpty()) {
            return buildPassiveActivityLines(terminalWidth, activityItems, todos);
        }

        List<String> lines = new ArrayList<>();
        if (!todos.isEmpty()) {
            lines.add(DIM + "  todos " + RESET + formatTodoItems(todos, Math.max(10, terminalWidth - 10)));
        }
        return lines.size() <= activityRows ? lines : lines.subList(0, activityRows);
    }

    private List<String> buildActivityMenuLines(int terminalWidth) {
        return buildActivityMenuLines(terminalWidth, activityMenuItems(), true);
    }

    private List<String> buildActivityMenuLines(int terminalWidth, List<ActivityMenuItem> items, boolean showEmpty) {
        int width = Math.max(12, terminalWidth - 1);
        normalizeActivitySelection(items);
        List<String> lines = new ArrayList<>();
        int itemRows = Math.max(1, activityRows - 1);
        int start = activityWindowStart(items, itemRows);
        int end = Math.min(items.size(), start + itemRows);
        for (int i = start; i < end; i++) {
            lines.add(formatActivityMenuLine(items.get(i), width));
        }
        if (items.isEmpty() && showEmpty) {
            lines.add(DIM + "  activity no running background work" + RESET);
        }
        String message = activityMenuMessage == null || activityMenuMessage.isBlank()
                ? "  /activity logs <id> | /activity kill <id> | /activity close"
                : "  " + activityMenuMessage;
        lines.add(DIM + truncatePlain(message, width) + RESET);
        return lines.size() <= activityRows ? lines : lines.subList(0, activityRows);
    }

    private List<String> buildPassiveActivityLines(int terminalWidth, List<ActivityMenuItem> items,
                                                    List<TodoActivityItem> todos) {
        int width = Math.max(12, terminalWidth - 1);
        normalizeActivitySelection(items);
        List<String> lines = new ArrayList<>();
        boolean hasTodos = todos != null && !todos.isEmpty();
        int reservedTodoRows = hasTodos ? 1 : 0;
        int itemRows = Math.max(1, activityRows - reservedTodoRows);
        int start = activityWindowStart(items, itemRows);
        int end = Math.min(items.size(), start + itemRows);
        int shown = Math.max(0, end - start);
        for (int i = start; i < end; i++) {
            lines.add(formatActivityMenuLine(items.get(i), width));
        }
        if (shown < items.size() && !lines.isEmpty()) {
            int last = lines.size() - 1;
            boolean selected = isSelectedActivity(items.get(end - 1));
            String more = truncatePlain(stripAnsi(lines.get(last)) + " +" + (items.size() - shown) + " more", width);
            lines.set(last, selected ? INVERSE + more + RESET : more);
        }
        if (hasTodos && lines.size() < activityRows) {
            lines.add(DIM + "  todos " + RESET + formatTodoItems(todos, Math.max(10, width - 10)));
        }
        if (lines.size() < activityRows) {
            String hint = activityFocusActive
                    ? "  up/down select | Enter logs | Del kill"
                    : "  Down selects activity | /activity logs <id> | /activity kill <id>";
            lines.add(DIM + truncatePlain(hint, width) + RESET);
        }
        return lines.size() <= activityRows ? lines : lines.subList(0, activityRows);
    }

    private String formatActivityMenuLine(ActivityMenuItem item, int width) {
        String action = item.killable ? " kill" : " logs";
        String log = item.latestLog == null || item.latestLog.isBlank() ? "" : " - " + item.latestLog;
        String line = truncatePlain("  [" + item.id + "] " + item.kind + " " + item.status + " "
                + item.label + action + log, width);
        return isSelectedActivity(item) ? INVERSE + line + RESET : line;
    }

    private List<ActivityMenuItem> activityMenuItems() {
        List<ActivityMenuItem> items = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        synchronized (activityLock) {
            for (ActivityItem item : backgroundActivities) {
                addActivityMenuItem(items, seen, activityMenuItem(item, "bg", activityStatus(item)));
            }
            for (ActivityItem item : subagentActivities) {
                addActivityMenuItem(items, seen, activityMenuItem(item, "agent", activityStatus(item)));
            }
        }
        for (TaskRecord record : activeTaskRecords()) {
            addActivityMenuItem(items, seen, activityMenuItem(record));
        }
        return items;
    }

    private void addActivityMenuItem(List<ActivityMenuItem> items, Set<String> seen, ActivityMenuItem item) {
        if (item == null || item.id == null || item.id.isBlank() || !seen.add(item.id)) return;
        items.add(item);
    }

    private void normalizeActivitySelection(List<ActivityMenuItem> items) {
        if (!activityFocusActive) return;
        if (items == null || items.isEmpty()) {
            clearActivitySelection();
            return;
        }
        int found = activityIndex(items, selectedActivityId);
        if (found >= 0) {
            selectedActivityIndex = found;
            return;
        }
        selectedActivityIndex = Math.max(0, Math.min(selectedActivityIndex, items.size() - 1));
        selectedActivityId = items.get(selectedActivityIndex).id;
    }

    private int activityIndex(List<ActivityMenuItem> items, String id) {
        if (items == null || id == null || id.isBlank()) return -1;
        for (int i = 0; i < items.size(); i++) {
            if (id.equals(items.get(i).id)) return i;
        }
        return -1;
    }

    private int activityWindowStart(List<ActivityMenuItem> items, int itemRows) {
        if (!activityFocusActive || items == null || items.isEmpty() || itemRows >= items.size()) return 0;
        int index = activityIndex(items, selectedActivityId);
        if (index < 0) return 0;
        int maxStart = Math.max(0, items.size() - itemRows);
        return Math.max(0, Math.min(index - itemRows + 1, maxStart));
    }

    private boolean isSelectedActivity(ActivityMenuItem item) {
        return activityFocusActive && item != null && item.id != null && item.id.equals(selectedActivityId);
    }

    private void clearActivitySelection() {
        activityFocusActive = false;
        selectedActivityId = "";
        selectedActivityIndex = -1;
        activityMenuMessage = "";
        if (tui != null) {
            tui.getStatusBar().clearMenu();
        }
    }

    private boolean selectNextActivityItem() {
        List<ActivityMenuItem> items = activityMenuItems();
        if (items.isEmpty()) {
            clearActivitySelection();
            redrawActivityPanelOnly();
            return false;
        }
        activityFocusActive = true;
        int current = activityIndex(items, selectedActivityId);
        selectedActivityIndex = current < 0 ? 0 : Math.min(items.size() - 1, current + 1);
        selectedActivityId = items.get(selectedActivityIndex).id;
        activityMenuMessage = "Enter logs | Del kill | Up returns to input";
        redrawActivityPanelOnly();
        return true;
    }

    private boolean selectPreviousActivityItem() {
        if (!activityFocusActive) return false;
        List<ActivityMenuItem> items = activityMenuItems();
        if (items.isEmpty()) {
            clearActivitySelection();
            redrawActivityPanelOnly();
            return true;
        }
        int current = activityIndex(items, selectedActivityId);
        if (current <= 0) {
            clearActivitySelection();
            redrawActivityPanelOnly();
            return true;
        }
        selectedActivityIndex = current - 1;
        selectedActivityId = items.get(selectedActivityIndex).id;
        activityMenuMessage = "Enter logs | Del kill | Up returns to input";
        redrawActivityPanelOnly();
        return true;
    }

    private boolean openSelectedActivityLogs() {
        if (!activityFocusActive || selectedActivityId.isBlank()) return false;
        printActivityLogs(selectedActivityId);
        activityMenuMessage = "showing logs for " + selectedActivityId;
        redrawActivityPanelOnly();
        return true;
    }

    private boolean killSelectedActivityItem() {
        if (!activityFocusActive || selectedActivityId.isBlank()) return false;
        String id = selectedActivityId;
        boolean killed = killActivityItem(id);
        activityMenuMessage = killed ? "kill requested for " + id : "no kill handle for " + id;
        if (killed) {
            selectedActivityId = "";
            selectedActivityIndex = Math.max(0, selectedActivityIndex - 1);
        }
        redrawActivityPanelOnly();
        safePrintln((killed ? GREEN : YELLOW) + "  " + activityMenuMessage + RESET);
        return true;
    }

    private ActivityMenuItem activityMenuItem(ActivityItem item, String kind, String status) {
        List<String> logs = new ArrayList<>(item.logs);
        boolean killable = item.process != null && item.process.isAlive();
        return new ActivityMenuItem(item.key, kind, item.label, status, item.latestLog,
                logs, "", killable, false);
    }

    private ActivityMenuItem activityMenuItem(TaskRecord record) {
        if (record == null || record.getTaskId() == null || record.getTaskId().isBlank()) return null;
        List<String> logs = tailLogFile(record.getOutputPath(), 40);
        String summary = normalizeActivityLog(record.getResultSummary());
        String latest = logs.isEmpty() ? summary : logs.get(logs.size() - 1);
        if (logs.isEmpty() && !summary.isBlank()) {
            logs = List.of(summary);
        }
        String kind = firstNonBlankText(record.getTaskType(), "task");
        String label = firstNonBlankText(record.getSubtaskName(), record.getDescription(),
                record.getPromptSummary(), record.getAgentName(), record.getTaskId());
        String status = record.getStatus() == null ? "active" : record.getStatus().name().toLowerCase(Locale.ROOT);
        boolean killable = record.isActive() && record.getPid() > 0;
        return new ActivityMenuItem(record.getTaskId(), kind, truncatePlain(label, 80), status,
                latest, logs, record.getOutputPath(), killable, true);
    }

    private String activityStatus(ActivityItem item) {
        if (item.process == null) return "running";
        return item.process.isAlive() ? "running" : "exited";
    }

    private List<TaskRecord> activeTaskRecords() {
        try {
            return taskRegistry().listActive();
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private TaskRegistry taskRegistry() {
        String wd = workingDir == null || workingDir.isBlank() ? "." : workingDir;
        return new TaskRegistry(Path.of(wd).toAbsolutePath().normalize());
    }

    private List<String> tailLogFile(String outputPath, int maxLines) {
        if (outputPath == null || outputPath.isBlank() || maxLines <= 0) return List.of();
        Path path;
        try {
            path = Path.of(outputPath);
        } catch (Exception ignored) {
            return List.of();
        }
        if (!Files.exists(path) || !Files.isRegularFile(path)) return List.of();
        Deque<String> tail = new ArrayDeque<>();
        try (java.util.stream.Stream<String> lines = Files.lines(path, StandardCharsets.UTF_8)) {
            lines.forEach(line -> {
                String normalized = normalizeActivityLog(line);
                if (normalized.isBlank()) return;
                tail.addLast(normalized);
                while (tail.size() > maxLines) {
                    tail.removeFirst();
                }
            });
        } catch (Exception ignored) {
            return List.of();
        }
        return new ArrayList<>(tail);
    }

    private String firstNonBlankText(String... values) {
        if (values == null) return "";
        for (String value : values) {
            if (value != null && !value.isBlank()) return value;
        }
        return "";
    }

    private ActivityMenuItem findActivityMenuItem(String id) {
        String wanted = id == null ? "" : id.trim();
        if (wanted.isBlank()) return null;
        for (ActivityMenuItem item : activityMenuItems()) {
            if (wanted.equals(item.id)) return item;
        }
        return null;
    }

    private boolean killActivityItem(String id) {
        String wanted = id == null ? "" : id.trim();
        if (wanted.isBlank()) return false;
        ActivityItem owned = null;
        synchronized (activityLock) {
            for (ActivityItem item : backgroundActivities) {
                if (wanted.equals(item.key) && item.process != null && item.process.isAlive()) {
                    item.addLog("kill requested");
                    owned = item;
                    break;
                }
            }
        }
        if (owned != null) {
            killProcess(owned.process);
            synchronized (activityLock) {
                removeActivity(backgroundActivities, owned.key);
            }
            redrawActivityPanel();
            return true;
        }
        try {
            boolean cancelled = taskRegistry().cancel(wanted);
            if (cancelled) {
                activityMenuMessage = "cancelled " + wanted;
                redrawActivityPanel();
            }
            return cancelled;
        } catch (Exception ignored) {
            return false;
        }
    }

    private void handleActivitySlash(String args) {
        String trimmed = args == null ? "" : args.trim();
        String[] parts = trimmed.isBlank() ? new String[]{"list", ""} : trimmed.split("\\s+", 2);
        String action = parts[0].toLowerCase(Locale.ROOT);
        String value = parts.length > 1 ? parts[1].trim() : "";
        switch (action) {
            case "close", "hide", "off" -> {
                activityMenuOpen = false;
                activityMenuMessage = "";
                redrawActivityPanelOnly();
            }
            case "logs", "log", "output", "view" -> {
                activityMenuOpen = true;
                if (value.isBlank()) {
                    activityMenuMessage = "usage: /activity logs <id>";
                    redrawActivityPanelOnly();
                    safePrintln(DIM + "  Usage: /activity logs <id>" + RESET);
                } else {
                    printActivityLogs(value);
                    activityMenuMessage = "showing logs for " + value;
                    redrawActivityPanelOnly();
                }
            }
            case "kill", "stop", "cancel" -> {
                activityMenuOpen = true;
                if (value.isBlank()) {
                    activityMenuMessage = "usage: /activity kill <id>";
                    redrawActivityPanelOnly();
                    safePrintln(DIM + "  Usage: /activity kill <id>" + RESET);
                } else {
                    boolean killed = killActivityItem(value);
                    activityMenuMessage = killed ? "kill requested for " + value : "no kill handle for " + value;
                    redrawActivityPanelOnly();
                    safePrintln((killed ? GREEN : YELLOW) + "  " + activityMenuMessage + RESET);
                }
            }
            default -> {
                activityMenuOpen = true;
                activityMenuMessage = "";
                printActivityMenu();
                redrawActivityPanelOnly();
            }
        }
    }

    private void printActivityMenu() {
        List<ActivityMenuItem> items = activityMenuItems();
        safePrintln("");
        safePrintln(BOLD + CYAN + "  Activity" + RESET);
        if (items.isEmpty()) {
            safePrintln(DIM + "  No running background work." + RESET);
        } else {
            for (ActivityMenuItem item : items) {
                String action = item.killable ? " · killable" : " · logs only";
                String latest = item.latestLog == null || item.latestLog.isBlank() ? "" : " - " + item.latestLog;
                safePrintln("  [" + item.id + "] " + item.kind + " " + item.status + " "
                        + truncatePlain(item.label, 80) + action + latest);
                if (item.outputPath != null && !item.outputPath.isBlank()) {
                    safePrintln(DIM + "      output: " + item.outputPath + RESET);
                }
            }
        }
        safePrintln(DIM + "  Use /activity logs <id>, /activity kill <id>, or /activity close." + RESET);
        safePrintln("");
    }

    private void printActivityLogs(String id) {
        ActivityMenuItem item = findActivityMenuItem(id);
        safePrintln("");
        if (item == null) {
            safePrintln(YELLOW + "  Activity not found: " + id + RESET);
            safePrintln("");
            return;
        }
        safePrintln(BOLD + CYAN + "  Activity Logs: " + item.id + RESET);
        if (item.outputPath != null && !item.outputPath.isBlank()) {
            safePrintln(DIM + "  output: " + item.outputPath + RESET);
        }
        List<String> logs = item.logs == null ? List.of() : item.logs;
        if (logs.isEmpty()) {
            safePrintln(DIM + "  No logs captured yet." + RESET);
        } else {
            for (String log : logs) {
                safePrintln("  " + log);
            }
        }
        safePrintln("");
    }

    private synchronized void updateSlashCompletionPanel(String buffer, int cursor) {
        if (terminal == null || scrollBottom <= 0) return;
        int w = terminal.getWidth();
        if (w <= 0) w = 120;
        slashCompletionLines = buildSlashCompletionLines(buffer, cursor, Math.max(12, w - 1));
        redrawActivityPanelOnly();
    }

    private synchronized void clearSlashCompletionPanel() {
        if (slashCompletionLines == null || slashCompletionLines.isEmpty()) return;
        slashCompletionLines = List.of();
        redrawActivityPanelOnly();
    }

    private synchronized void redrawActivityPanelOnly() {
        // Convert activity items to StatusBar MenuItems and push to the consolidated bar
        if (tui != null) {
            pushActivityMenuToStatusBar();
            tui.getStatusBar().requestRedraw();
        }
    }

    /**
     * Converts internal activityMenuItems + selection state OR slash completion lines
     * into StatusBar.MenuItems so the consolidated bottom bar renders them.
     * Slash completions take priority over activity items when present.
     */
    private void pushActivityMenuToStatusBar() {
        // Slash completions take priority — show them as menu items
        List<String> completions = slashCompletionLines;
        if (completions != null && !completions.isEmpty()) {
            List<StatusBar.MenuItem> menuItems = new ArrayList<>();
            for (int i = 0; i < completions.size(); i++) {
                menuItems.add(new StatusBar.MenuItem("completion-" + i, completions.get(i), "", false));
            }
            tui.getStatusBar().setMenuItems(menuItems, "");
            return;
        }

        // Otherwise show activity items
        List<ActivityMenuItem> items = activityMenuItems();
        if (items.isEmpty()) {
            tui.getStatusBar().clearMenu();
            return;
        }
        List<StatusBar.MenuItem> menuItems = new ArrayList<>();
        for (ActivityMenuItem item : items) {
            boolean selected = isSelectedActivity(item);
            String label = (item.kind() != null ? "[" + item.kind() + "] " : "") + item.label();
            String status = item.status() != null ? item.status() : "";
            if (item.killable()) {
                status += status.isEmpty() ? "killable" : " | killable";
            }
            menuItems.add(new StatusBar.MenuItem(item.id(), label, status, selected));
        }
        tui.getStatusBar().setMenuItems(menuItems, activityMenuMessage);
    }

    private List<String> buildSlashCompletionLines(String buffer, int cursor, int terminalWidth) {
        if (buffer == null || buffer.isEmpty()) return List.of();
        int safeCursor = Math.max(0, Math.min(cursor, buffer.length()));
        String upToCursor = buffer.substring(0, safeCursor);
        if (!upToCursor.startsWith("/")) return List.of();

        List<Candidate> candidates = new ArrayList<>();
        ParsedLine parsedLine = parseSlashCompletionLine(upToCursor);
        chatCompleter.complete(lineReader, parsedLine, candidates);
        if (candidates.isEmpty()) return List.of();

        int showing = Math.min(candidates.size(), Math.max(1, activityRows));
        int maxName = 0;
        for (int i = 0; i < showing; i++) {
            Candidate candidate = candidates.get(i);
            String name = candidate.displ() != null ? candidate.displ() : candidate.value();
            maxName = Math.max(maxName, name.length());
        }

        List<String> lines = new ArrayList<>();
        for (int i = 0; i < showing; i++) {
            Candidate candidate = candidates.get(i);
            String name = candidate.displ() != null ? candidate.displ() : candidate.value();
            String description = candidate.descr();
            StringBuilder line = new StringBuilder("  ").append(name);
            if (description != null && !description.isBlank()) {
                int pad = Math.max(2, maxName - name.length() + 2);
                line.append(" ".repeat(pad)).append("- ").append(description);
            }
            if (i == showing - 1 && candidates.size() > showing) {
                line.append(DIM).append(" +").append(candidates.size() - showing).append(" more").append(RESET);
            }
            lines.add(DIM + truncatePlain(line.toString(), terminalWidth) + RESET);
        }
        return lines;
    }

    private ParsedLine parseSlashCompletionLine(String line) {
        return new ParsedLine() {
            @Override
            public String word() {
                int space = line.lastIndexOf(' ');
                return space >= 0 ? line.substring(space + 1) : line;
            }

            @Override
            public int wordCursor() {
                return word().length();
            }

            @Override
            public int wordIndex() {
                return line.isBlank() ? 0 : Math.max(0, line.split("\\s+").length - 1);
            }

            @Override
            public List<String> words() {
                if (line.isBlank()) return List.of("");
                return Arrays.asList(line.split("\\s+"));
            }

            @Override
            public String line() {
                return line;
            }

            @Override
            public int cursor() {
                return line.length();
            }
        };
    }

    private void refreshManagedSlashCompletion(LineReaderImpl impl) {
        try {
            updateSlashCompletionPanel(impl.getBuffer().toString(), impl.getBuffer().cursor());
            impl.callWidget(LineReader.REDISPLAY);
        } catch (Exception ignored) {
            // Completion display must never interfere with typing.
        }
    }

    private String formatActivityItems(List<ActivityItem> items, int width) {
        List<String> parts = new ArrayList<>();
        for (ActivityItem item : items) {
            String elapsed = formatElapsed(item.startedAtMillis);
            String part = "[" + item.key + "] " + item.label + " " + elapsed;
            if (item.latestLog != null && !item.latestLog.isBlank()) {
                part += " - " + item.latestLog;
            }
            parts.add(part);
        }
        return truncatePlain(String.join(" | ", parts), width);
    }

    private String formatTodoItems(List<TodoActivityItem> items, int width) {
        List<String> parts = new ArrayList<>();
        for (TodoActivityItem item : items) {
            if (item.content == null || item.content.isBlank()) continue;
            parts.add(todoCheckbox(item.status) + " " + item.content.replaceAll("\\s+", " ").trim());
            if (parts.size() >= 4) break;
        }
        return truncatePlain(String.join(" | ", parts), width);
    }

    private String todoCheckbox(String status) {
        String normalized = normalizeTodoStatus(status);
        return switch (normalized) {
            case "completed" -> "[x]";
            case "in_progress" -> "[*]";
            case "cancelled" -> "[-]";
            default -> "[ ]";
        };
    }

    private String formatElapsed(long startedAtMillis) {
        long elapsedSeconds = Math.max(0, (System.currentTimeMillis() - startedAtMillis) / 1000L);
        if (elapsedSeconds < 60) {
            return elapsedSeconds + "s";
        }
        long minutes = elapsedSeconds / 60;
        long seconds = elapsedSeconds % 60;
        return minutes + "m" + seconds + "s";
    }

    private String startBackgroundActivity(String label, String latestLog) {
        return startBackgroundActivity(label, latestLog, null);
    }

    private String startBackgroundActivity(String label, String latestLog, Process process) {
        String key = "bg-" + activitySequence.incrementAndGet();
        synchronized (activityLock) {
            backgroundActivities.addLast(new ActivityItem(key, label, normalizeActivityLog(latestLog), process));
            trimActivity(backgroundActivities, 5);
        }
        // Register with StatusBar so it shows in the consolidated bottom bar
        if (tui != null) {
            tui.getStatusBar().registerSubagent(key, "process", label);
        }
        redrawActivityPanel();
        return key;
    }

    private void finishBackgroundActivity(String key, String latestLog) {
        synchronized (activityLock) {
            updateActivityLog(backgroundActivities, key, latestLog);
            removeActivity(backgroundActivities, key);
        }
        // Unregister from StatusBar
        if (tui != null) {
            tui.getStatusBar().unregisterSubagent(key);
        }
        redrawActivityPanel();
    }

    private void trackToolActivityStart(PassthroughStreamParser.ToolUse toolUse) {
        if (toolUse == null) return;
        trackTodoActivity(toolUse.name(), toolUse.input());

        String label = summarizeToolLabel(toolUse.name(), toolUse.input());
        String log = summarizeToolInput(toolUse.input());
        synchronized (activityLock) {
            if (isSubagentTool(toolUse.name(), toolUse.input())) {
                String key = newToolActivityKey("agent", toolUse.name());
                subagentActivities.addLast(new ActivityItem(key, label, log));
                trimActivity(subagentActivities, 5);
                if (tui != null) tui.getStatusBar().registerSubagent(key, "agent", label);
            }
            if (isBackgroundProcessTool(toolUse.name(), toolUse.input())) {
                String key = newToolActivityKey("process", toolUse.name());
                backgroundActivities.addLast(new ActivityItem(key, label, log));
                trimActivity(backgroundActivities, 5);
                if (tui != null) tui.getStatusBar().registerSubagent(key, "process", label);
            }
        }
        redrawActivityPanel();
    }

    private void trackToolActivityLog(String toolName, String log) {
        String normalizedLog = normalizeActivityLog(log);
        if (normalizedLog.isBlank()) return;
        synchronized (activityLock) {
            updateLatestActivityLog(subagentActivities, toolActivityPrefix("agent", toolName), normalizedLog);
            updateLatestActivityLog(backgroundActivities, toolActivityPrefix("process", toolName), normalizedLog);
            if (!backgroundActivities.isEmpty()) {
                backgroundActivities.peekLast().addLog(normalizedLog);
            }
            if (!subagentActivities.isEmpty()) {
                subagentActivities.peekLast().addLog(normalizedLog);
            }
        }
        redrawActivityPanel();
    }

    private void trackToolActivityComplete(String toolName, String output, boolean error) {
        if (!error) {
            trackTodoActivity(toolName, output);
        }
        String normalizedLog = normalizeActivityLog(output);
        synchronized (activityLock) {
            updateLatestActivityLog(subagentActivities, toolActivityPrefix("agent", toolName), normalizedLog);
            String agentKey = findAndRemoveLatestActivity(subagentActivities, toolActivityPrefix("agent", toolName));
            if (agentKey != null && tui != null) tui.getStatusBar().unregisterSubagent(agentKey);

            updateLatestActivityLog(backgroundActivities, toolActivityPrefix("process", toolName), normalizedLog);
            if (error || looksLikeFinishedProcessOutput(normalizedLog)) {
                String processKey = findAndRemoveLatestActivity(backgroundActivities, toolActivityPrefix("process", toolName));
                if (processKey != null && tui != null) tui.getStatusBar().unregisterSubagent(processKey);
            }
        }
        redrawActivityPanel();
    }

    private void trackAssistantLog(String text) {
        String normalizedLog = normalizeActivityLog(text);
        if (normalizedLog.isBlank()) return;
        synchronized (activityLock) {
            if (!backgroundActivities.isEmpty()) {
                backgroundActivities.peekLast().addLog(normalizedLog);
            }
        }
        redrawActivityPanel();
    }

    private void trackTodoActivity(String toolName, String input) {
        String haystack = ((toolName == null ? "" : toolName) + " " + (input == null ? "" : input)).toLowerCase(Locale.ROOT);
        if (!haystack.contains("todo")) return;
        synchronized (activityLock) {
            if (!applyTodoJsonMutation(input) && !applyTodoTextMutation(input) && activeTodos.isEmpty()) {
                activeTodos.put("message:todo-list-updated",
                        new TodoActivityItem("message:todo-list-updated", "", "todo list updated", "in_progress", ""));
            }
            trimTodoActivity(8);
        }
    }

    private boolean applyTodoJsonMutation(String input) {
        if (input == null || input.isBlank()) return false;
        try {
            com.fasterxml.jackson.databind.JsonNode node = objectMapper.readTree(input);
            return applyTodoJsonMutation(node);
        } catch (Exception ignored) {
            return false;
        }
    }

    private boolean applyTodoJsonMutation(com.fasterxml.jackson.databind.JsonNode node) {
        if (node == null || node.isNull()) return false;
        if (node.isArray()) {
            boolean changed = false;
            for (com.fasterxml.jackson.databind.JsonNode item : node) {
                changed |= applyTodoJsonMutation(item);
            }
            return changed;
        }
        if (!node.isObject()) return false;

        String action = firstNonBlank(node, "action", "operation", "op").toLowerCase(Locale.ROOT);
        com.fasterxml.jackson.databind.JsonNode todosNode = node.get("todos");
        if ((action.equals("set") || action.equals("replace") || action.isBlank()) && todosNode != null) {
            activeTodos.clear();
            addTodoNodes(todosNode);
            return true;
        }
        if (action.equals("clear")) {
            activeTodos.clear();
            return true;
        }
        if (action.equals("delete") || action.equals("remove")) {
            return removeTodoNode(node);
        }
        if (action.equals("update") || action.equals("complete") || action.equals("cancel")) {
            return updateTodoNode(node, action);
        }
        if (action.equals("add") || hasTodoContent(node)) {
            return putTodoNode(node, action.equals("add") ? "pending" : "");
        }
        return false;
    }

    private void addTodoNodes(com.fasterxml.jackson.databind.JsonNode todosNode) {
        if (todosNode == null || todosNode.isNull()) return;
        if (todosNode.isArray()) {
            for (com.fasterxml.jackson.databind.JsonNode item : todosNode) {
                putTodoNode(item, "pending");
            }
        } else {
            putTodoNode(todosNode, "pending");
        }
    }

    private boolean putTodoNode(com.fasterxml.jackson.databind.JsonNode node, String defaultStatus) {
        if (node == null || !node.isObject()) return false;
        String content = todoContent(node);
        if (content.isBlank()) return false;
        String id = normalizeTodoId(firstNonBlank(node, "task_id", "taskId", "id"));
        String status = normalizeTodoStatus(firstNonBlank(node, "status", "state"));
        if (status.isBlank()) status = normalizeTodoStatus(defaultStatus);
        if (status.isBlank()) status = "pending";
        String priority = firstNonBlank(node, "priority");
        String key = todoKey(id, content);
        TodoActivityItem existing = findTodoItem(id, content);
        if (existing != null) {
            existing.id = id.isBlank() ? existing.id : id;
            existing.content = content;
            existing.status = status;
            existing.priority = priority.isBlank() ? existing.priority : priority;
        } else {
            activeTodos.put(key, new TodoActivityItem(key, id, content, status, priority));
        }
        return true;
    }

    private boolean updateTodoNode(com.fasterxml.jackson.databind.JsonNode node, String action) {
        if (node == null || !node.isObject()) return false;
        String id = normalizeTodoId(firstNonBlank(node, "task_id", "taskId", "id"));
        String content = todoContent(node);
        TodoActivityItem item = findTodoItem(id, content);
        String status = normalizeTodoStatus(firstNonBlank(node, "status", "state"));
        if (status.isBlank() && action.equals("complete")) status = "completed";
        if (status.isBlank() && action.equals("cancel")) status = "cancelled";
        if (item == null) {
            if (content.isBlank() && id.isBlank()) return false;
            String fallbackContent = content.isBlank() ? "task " + id : content;
            String key = todoKey(id, fallbackContent);
            item = new TodoActivityItem(key, id, fallbackContent, status.isBlank() ? "in_progress" : status,
                    firstNonBlank(node, "priority"));
            activeTodos.put(key, item);
            return true;
        }
        if (!id.isBlank()) item.id = id;
        if (!content.isBlank()) item.content = content;
        if (!status.isBlank()) item.status = status;
        String priority = firstNonBlank(node, "priority");
        if (!priority.isBlank()) item.priority = priority;
        return true;
    }

    private boolean removeTodoNode(com.fasterxml.jackson.databind.JsonNode node) {
        if (node == null || !node.isObject()) return false;
        String id = normalizeTodoId(firstNonBlank(node, "task_id", "taskId", "id"));
        String content = todoContent(node);
        TodoActivityItem item = findTodoItem(id, content);
        if (item == null) return false;
        activeTodos.remove(item.key);
        return true;
    }

    private boolean applyTodoTextMutation(String input) {
        String raw = input == null ? "" : stripAnsi(input).replace("\r\n", "\n").replace('\r', '\n');
        if (applyTodoListTextMutation(raw)) return true;
        String text = normalizeActivityLog(input);
        if (text.isBlank()) return false;
        String lower = text.toLowerCase(Locale.ROOT);
        if (lower.startsWith("added task #")) {
            int colon = text.indexOf(':');
            if (colon > 0) {
                String id = normalizeTodoId(text.substring("Added task".length(), colon).trim());
                String content = text.substring(colon + 1).trim();
                TodoActivityItem existing = findTodoItem("", content);
                if (existing != null) {
                    activeTodos.remove(existing.key);
                }
                String key = todoKey(id, content);
                activeTodos.put(key, new TodoActivityItem(key, id, content, "pending", ""));
                return true;
            }
        }
        if (lower.startsWith("updated task #")) {
            String id = firstTokenAfter(text, "Updated task");
            TodoActivityItem item = findTodoItem(id, "");
            if (item == null && !id.isBlank()) {
                String key = todoKey(id, "task " + id);
                item = new TodoActivityItem(key, id, "task " + id, "in_progress", "");
                activeTodos.put(key, item);
            }
            if (item != null) {
                if (lower.contains("completed")) item.status = "completed";
                else if (lower.contains("in_progress")) item.status = "in_progress";
                else if (lower.contains("cancelled") || lower.contains("canceled")) item.status = "cancelled";
                else if (lower.contains("pending")) item.status = "pending";
                return true;
            }
        }
        if (lower.startsWith("deleted task #") || lower.startsWith("removed task #")) {
            String id = firstTokenAfter(text, "task");
            TodoActivityItem item = findTodoItem(id, "");
            if (item != null) {
                activeTodos.remove(item.key);
                return true;
            }
        }
        return false;
    }

    private boolean applyTodoListTextMutation(String raw) {
        if (raw == null || raw.isBlank()) return false;
        java.util.regex.Pattern todoLine = java.util.regex.Pattern.compile("^\\[([ xX*-])\\]\\s*#?([^:]+):\\s*(.+)$");
        List<TodoActivityItem> parsed = new ArrayList<>();
        for (String line : raw.split("\\R")) {
            java.util.regex.Matcher matcher = todoLine.matcher(line.trim());
            if (!matcher.matches()) continue;
            String status = switch (matcher.group(1)) {
                case "x", "X" -> "completed";
                case "*" -> "in_progress";
                case "-" -> "cancelled";
                default -> "pending";
            };
            String id = normalizeTodoId(matcher.group(2));
            String content = matcher.group(3).replaceAll("\\s+", " ").trim();
            if (content.isBlank()) continue;
            String key = todoKey(id, content);
            parsed.add(new TodoActivityItem(key, id, content, status, ""));
        }
        if (parsed.isEmpty()) return false;
        activeTodos.clear();
        for (TodoActivityItem item : parsed) {
            activeTodos.put(item.key, item);
        }
        return true;
    }

    private String firstTokenAfter(String text, String marker) {
        if (text == null || marker == null) return "";
        int index = text.toLowerCase(Locale.ROOT).indexOf(marker.toLowerCase(Locale.ROOT));
        if (index < 0) return "";
        String remainder = text.substring(index + marker.length()).trim();
        if (remainder.isBlank()) return "";
        return normalizeTodoId(remainder.split("\\s+", 2)[0]);
    }

    private boolean hasTodoContent(com.fasterxml.jackson.databind.JsonNode node) {
        return !todoContent(node).isBlank();
    }

    private String todoContent(com.fasterxml.jackson.databind.JsonNode node) {
        String content = firstNonBlank(node, "content", "subject", "task_description", "description", "task", "text");
        return content.replaceAll("\\s+", " ").trim();
    }

    private TodoActivityItem findTodoItem(String id, String content) {
        String normalizedId = normalizeTodoId(id);
        if (!normalizedId.isBlank()) {
            for (TodoActivityItem item : activeTodos.values()) {
                if (normalizedId.equals(normalizeTodoId(item.id))) return item;
            }
        }
        String normalizedContent = content == null ? "" : content.replaceAll("\\s+", " ").trim();
        if (!normalizedContent.isBlank()) {
            for (TodoActivityItem item : activeTodos.values()) {
                if (normalizedContent.equals(item.content)) return item;
            }
        }
        return null;
    }

    private String todoKey(String id, String content) {
        String normalizedId = normalizeTodoId(id);
        if (!normalizedId.isBlank()) return "id:" + normalizedId;
        return "content:" + (content == null ? "" : content.replaceAll("\\s+", " ").trim());
    }

    private String normalizeTodoId(String id) {
        if (id == null) return "";
        return id.replace("#", "").trim().replaceAll("[,:;.]+$", "").trim();
    }

    private String normalizeTodoStatus(String status) {
        if (status == null) return "";
        String normalized = status.toLowerCase(Locale.ROOT).replace('-', '_').trim();
        if (normalized.contains("complete") || normalized.equals("done")) return "completed";
        if (normalized.contains("progress") || normalized.equals("active") || normalized.equals("running")) return "in_progress";
        if (normalized.contains("cancel")) return "cancelled";
        if (normalized.contains("pending") || normalized.contains("todo")) return "pending";
        return normalized;
    }

    private void trimTodoActivity(int max) {
        while (activeTodos.size() > max) {
            Iterator<String> iterator = activeTodos.keySet().iterator();
            if (!iterator.hasNext()) return;
            iterator.next();
            iterator.remove();
        }
    }

    private String firstNonBlank(com.fasterxml.jackson.databind.JsonNode node, String... fields) {
        for (String field : fields) {
            String value = node.path(field).asText("");
            if (!value.isBlank()) return value;
        }
        return "";
    }

    private void trimActivity(Deque<ActivityItem> items, int max) {
        while (items.size() > max) {
            items.removeFirst();
        }
    }

    private void removeActivity(Deque<ActivityItem> items, String key) {
        if (key == null || key.isBlank()) return;
        Iterator<ActivityItem> iterator = items.descendingIterator();
        while (iterator.hasNext()) {
            ActivityItem item = iterator.next();
            if (key.equals(item.key)) {
                iterator.remove();
                return;
            }
        }
    }

    private void updateActivityLog(Deque<ActivityItem> items, String key, String log) {
        String normalizedLog = normalizeActivityLog(log);
        if (normalizedLog.isBlank() || key == null || key.isBlank()) return;
        Iterator<ActivityItem> iterator = items.descendingIterator();
        while (iterator.hasNext()) {
            ActivityItem item = iterator.next();
            if (key.equals(item.key)) {
                item.addLog(normalizedLog);
                return;
            }
        }
    }

    private void updateLatestActivityLog(Deque<ActivityItem> items, String prefix, String log) {
        String normalizedLog = normalizeActivityLog(log);
        if (normalizedLog.isBlank() || prefix == null || prefix.isBlank()) return;
        Iterator<ActivityItem> iterator = items.descendingIterator();
        while (iterator.hasNext()) {
            ActivityItem item = iterator.next();
            if (matchesActivityPrefix(item.key, prefix)) {
                item.addLog(normalizedLog);
                return;
            }
        }
    }

    private void removeLatestActivity(Deque<ActivityItem> items, String prefix) {
        findAndRemoveLatestActivity(items, prefix);
    }

    private String findAndRemoveLatestActivity(Deque<ActivityItem> items, String prefix) {
        if (prefix == null || prefix.isBlank()) return null;
        Iterator<ActivityItem> iterator = items.descendingIterator();
        while (iterator.hasNext()) {
            ActivityItem item = iterator.next();
            if (matchesActivityPrefix(item.key, prefix)) {
                iterator.remove();
                return item.key;
            }
        }
        return null;
    }

    private boolean matchesActivityPrefix(String key, String prefix) {
        return key != null && (key.equals(prefix) || key.startsWith(prefix + "-"));
    }

    private String newToolActivityKey(String kind, String toolName) {
        return toolActivityPrefix(kind, toolName) + "-" + activitySequence.incrementAndGet();
    }

    private String toolActivityPrefix(String kind, String toolName) {
        return kind + ":" + (toolName == null ? "" : toolName.toLowerCase(Locale.ROOT));
    }

    private boolean isSubagentTool(String name, String input) {
        String tool = name == null ? "" : name.toLowerCase(Locale.ROOT);
        String haystack = (tool + " " + (input == null ? "" : input)).toLowerCase(Locale.ROOT);
        return tool.equals("task")
                || tool.equals("multi_task")
                || tool.equals("quorum_task")
                || tool.contains("subagent")
                || tool.contains("agentdelegation")
                || haystack.contains("\"agent_count\"")
                || haystack.contains("\"agents\"")
                || haystack.contains("subagent");
    }

    private boolean isBackgroundProcessTool(String name, String input) {
        String tool = name == null ? "" : name.toLowerCase(Locale.ROOT);
        String haystack = (tool + " " + (input == null ? "" : input)).toLowerCase(Locale.ROOT).replace(" ", "");
        return haystack.contains("run_in_background")
                || haystack.contains("\"background\":true")
                || haystack.contains("backgroundprocess")
                || (tool.contains("process") && (haystack.contains("\"action\":\"launch\"")
                        || haystack.contains("\"action\":\"start\"")
                        || haystack.contains("process_id")
                        || haystack.contains("processid")))
                || (tool.contains("bash") && haystack.contains("background"));
    }

    private boolean looksLikeFinishedProcessOutput(String output) {
        String haystack = output == null ? "" : output.toLowerCase(Locale.ROOT);
        return haystack.contains("exit ")
                || haystack.contains("completed")
                || haystack.contains("finished")
                || haystack.contains("stopped")
                || haystack.contains("failed");
    }

    private String summarizeToolLabel(String name, String input) {
        String base = TerminalRenderer.prettifyToolName(name == null ? "tool" : name);
        String detail = summarizeToolInput(input);
        if (detail.isBlank()) return truncatePlain(base, 64);
        return truncatePlain(base + " " + detail, 64);
    }

    private String summarizeToolInput(String input) {
        if (input == null || input.isBlank()) return "";
        try {
            com.fasterxml.jackson.databind.JsonNode node = objectMapper.readTree(input);
            if (node.isObject()) {
                String value = firstNonBlank(node, "description", "task", "subject", "command", "cmd", "prompt", "process_id", "processId");
                if (!value.isBlank()) return truncatePlain(value.replaceAll("\\s+", " ").trim(), 80);
            }
        } catch (Exception ignored) {
            // Fall back to sanitized text below.
        }
        return truncatePlain(stripAnsi(input).replaceAll("\\s+", " ").trim(), 80);
    }

    private String normalizeActivityLog(String log) {
        if (log == null) return "";
        String cleaned = stripAnsi(log).replaceAll("\\s+", " ").trim();
        return truncatePlain(cleaned, 96);
    }

    private void redrawActivityPanel() {
        if (tui != null) {
            pushActivityMenuToStatusBar();
            tui.getStatusBar().requestRedraw();
        }
    }

    /** Position cursor at prompt row for readLine and redraw input box borders. */
    private void positionAtPrompt() {
        synchronized (drawLock) {
            drawFixedInputBox();
            for (int row = firstInputRow(); row <= lastInputRow(); row++) {
                System.out.printf("\033[%d;1H\033[2K", row);
            }
            System.out.printf("\033[?25h\033[%d;1H", firstInputRow());
            System.out.flush();
        }
    }

    private void restoreIdlePromptCursor() {
        resetHostInputModes();
        LineReader reader = activeLineReader;
        synchronized (drawLock) {
            if (tui != null) {
                tui.reestablishScrollRegion();
                scrollBottom = tui.scrollBottom();
            }
            drawFixedInputBox(false);
            drawIdlePromptLine(reader);
            System.out.flush();
        }
        redisplayActiveReader(reader);
    }

    private void redisplayActiveReader(LineReader reader) {
        if (reader == null) return;
        try {
            reader.callWidget(LineReader.REDISPLAY);
            reader.getTerminal().writer().flush();
        } catch (Exception ignored) {
            // Async prompt restore must never break the active readLine.
        }
    }

    private void drawIdlePromptLine(LineReader reader) {
        drawActivePromptLine(reader, true);
    }

    private void drawActivePromptLine(LineReader reader, boolean force) {
        int width = terminal != null && terminal.getWidth() > 0 ? terminal.getWidth() : 120;
        String prompt = buildPrompt();
        String buffer = currentReadLineBuffer(reader);
        int cursor = currentReadLineCursor(reader, buffer);
        String line = fitAnsiLine(prompt + buffer, Math.max(12, width - 1));
        int promptWidth = stripAnsi(prompt).length();
        int cursorCol = Math.min(Math.max(1, promptWidth + cursor + 1), Math.max(1, width));
        boolean lineChanged = force || !Objects.equals(line, activePromptLastRenderedLine);
        if (lineChanged) {
            System.out.printf("\033[%d;1H\033[2K%s", firstInputRow(), line);
            activePromptLastRenderedLine = line;
        }
        activePromptLastCursorCol = cursorCol;
        System.out.printf("\033[?25h\033[%d;%dH", firstInputRow(), cursorCol);
    }

    private void resetActivePromptRenderCache() {
        activePromptLastRenderedLine = "";
        activePromptLastCursorCol = -1;
    }

    private String currentReadLineBuffer(LineReader reader) {
        if (reader instanceof LineReaderImpl impl) {
            try {
                return impl.getBuffer().toString();
            } catch (Exception ignored) {
            }
        }
        return "";
    }

    private int currentReadLineCursor(LineReader reader, String buffer) {
        if (reader instanceof LineReaderImpl impl) {
            try {
                return Math.max(0, impl.getBuffer().cursor());
            } catch (Exception ignored) {
            }
        }
        return buffer == null ? 0 : buffer.length();
    }

    private int queuePreviewRow() { return scrollBottom + 1; }
    private int busyPromptRow() { return scrollBottom + 2; }
    private int topBorderRow() { return scrollBottom + 3; }
    private int firstInputRow() { return scrollBottom + 4; }
    private int lastInputRow() { return firstInputRow() + inputRows - 1; }
    private int bottomBorderRow() { return lastInputRow() + 1; }

    private void saveCursor() { System.out.print("\0337"); }
    private void restoreCursor() { System.out.print("\0338"); }
    private void hideCursor() { System.out.print("\033[?25l"); }
    private void showCursor() { System.out.print("\033[?25h"); }

    private TerminalRenderer.SpinnerHandle startStatusSpinner(String label) {
        AtomicBoolean running = new AtomicBoolean(true);
        AtomicReference<String> phase = new AtomicReference<>("running");
        TerminalRenderer.SpinnerHandle handle = new TerminalRenderer.SpinnerHandle(running) {
            @Override
            public void stop() {
                running.set(false);
                Thread t = spinnerThread;
                if (t != null) {
                    try { t.join(250); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                }
                clearInlineResponseSpinner();
                updateStatusLine("idle");
            }

            @Override
            public void setPhase(String newPhase) {
                if (newPhase == null || newPhase.isBlank()) {
                    phase.set("running");
                } else {
                    phase.set(newPhase.toLowerCase(Locale.ROOT));
                }
            }
        };

        Thread spinnerThread = new Thread(() -> {
            String[] frames = {"|", "/", "-", "\\"};
            int frame = 0;
            while (running.get()) {
                drawInlineResponseSpinner(frames[frame++ % frames.length], phase.get(), label);
                try {
                    Thread.sleep(120);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }, "managed-passthrough-inline-spinner");
        spinnerThread.setDaemon(true);
        handle.spinnerThread = spinnerThread;
        spinnerThread.start();
        return handle;
    }

    private void drawInlineResponseSpinner(String frame, String phase, String label) {
        synchronized (drawLock) {
            if (terminal == null || scrollBottom <= 0) return;
            int w = terminal.getWidth();
            if (w <= 0) w = 120;
            String plain = "  " + frame + " " + phase + " (" + label + ")";
            if (plain.length() > w) {
                plain = plain.substring(0, Math.max(0, w - 1));
            }
            boolean activePrompt = activeLineReader != null && !busyInputActive;
            if (activePrompt) hideCursor();
            saveCursor();
            System.out.printf("\033[%d;1H\033[2K%s", scrollBottom, DIM + plain + RESET);
            restoreCursor();
            if (activePrompt) showCursor();
            System.out.flush();
        }
    }

    private void clearInlineResponseSpinner() {
        synchronized (drawLock) {
            if (scrollBottom <= 0) return;
            boolean activePrompt = activeLineReader != null && !busyInputActive;
            if (activePrompt) hideCursor();
            saveCursor();
            System.out.printf("\033[%d;1H\033[2K", scrollBottom);
            restoreCursor();
            if (activePrompt) showCursor();
            System.out.flush();
        }
    }

    private synchronized void setBusyInputActive(boolean active) {
        busyInputActive = active;
        if (!active) {
            busyInputBuffer = "";
            busyEditingQueuedMessageId = null;
            busyPrompt = "";
        } else {
            busyPrompt = "  Enter draft · ↑ edit pending · Ctrl+B background · Esc/Ctrl+C stop";
        }
        drawFixedInputBox();
    }

    private synchronized void redrawBusyInputState(String buffer, String editingQueuedId) {
        busyInputBuffer = buffer == null ? "" : buffer;
        busyEditingQueuedMessageId = editingQueuedId;
        if (busyInputActive) {
            drawFixedInputBox();
        }
    }

    private void enqueueBusyMessage(String message) {
        if (message == null || message.isBlank() || messageQueue == null) return;
        String trimmed = message.trim();
        messageQueue.enqueue(trimmed);
        recordInputHistory(trimmed);
        busyPrompt = "  Draft saved · ↑ edit · Ctrl+B background";
        drawFixedInputBox();
        updateStatusLine(currentStatus);
    }

    private void updateBusyQueuedMessage(String id, String message) {
        if (id == null || id.isBlank() || message == null || message.isBlank() || messageQueue == null) return;
        String trimmed = message.trim();
        if (messageQueue.update(id, trimmed)) {
            busyPrompt = "  Draft updated · ↑ edit · Ctrl+B background";
        } else {
            messageQueue.enqueue(trimmed);
            busyPrompt = "  Draft saved · ↑ edit · Ctrl+B background";
        }
        recordInputHistory(trimmed);
        drawFixedInputBox();
        updateStatusLine(currentStatus);
    }

    private synchronized void preserveBusyDraftForIdle() {
        String draft = busyInputBuffer == null ? "" : busyInputBuffer;
        if (draft.isBlank()) return;
        String editingId = busyEditingQueuedMessageId;
        if (editingId != null && messageQueue != null && messageQueue.update(editingId, draft.trim())) {
            recordInputHistory(draft.trim());
            return;
        }
        pendingIdleDraft = draft;
    }

    private synchronized String takePendingIdleDraft() {
        String draft = pendingIdleDraft == null ? "" : pendingIdleDraft;
        pendingIdleDraft = "";
        return draft;
    }

    private void recordInputHistory(String message) {
        if (message == null || message.isBlank()) return;
        String value = message.trim();
        synchronized (busyInputHistory) {
            int last = busyInputHistory.size() - 1;
            if (last >= 0 && busyInputHistory.get(last).equals(value)) return;
            busyInputHistory.add(value);
            if (busyInputHistory.size() > 100) {
                busyInputHistory.remove(0);
            }
        }
    }

    private List<String> inputHistorySnapshot() {
        synchronized (busyInputHistory) {
            return List.copyOf(busyInputHistory);
        }
    }

    private QueuedMessageDraft recallQueuedMessageForBusyEdit() {
        MessageQueue.QueuedMessage queued = nextPreviewQueuedMessage();
        if (queued == null) {
            busyPrompt = "  No pending draft to edit · Enter draft · Ctrl+B background";
            drawFixedInputBox();
            return null;
        }
        busyPrompt = "  Editing draft · Enter save · Ctrl+B background";
        return new QueuedMessageDraft(queued.getId(), queued.getContent());
    }

    private void requestAgentBackground() {
        backgroundSignal.set(true);
        if (canDispatchQueuedMessageAfterBackground()) {
            busyPrompt = "  Backgrounding current response · next draft will run";
        } else {
            busyPrompt = "  Backgrounding current response · drafts stay queued for this agent";
        }
        drawFixedInputBox();
        updateStatusLine("backgrounding current response");
    }

    private Terminal.SignalHandler terminalSigintHandler() {
        return sig -> handleSigint();
    }

    private void handleSigint() {
        if (agentBusy) {
            requestAgentInterrupt(new byte[]{0x03});
        } else {
            requestCliShutdown();
        }
    }

    private void requestCliShutdown() {
        shutdownSignal.set(true);
        Thread rt = replThread;
        if (rt != null) {
            rt.interrupt();
        }
        Terminal term = terminal;
        if (term != null) {
            try {
                term.close();
            } catch (IOException | IOError ignored) {
            }
        }
    }

    private void requestAgentInterrupt(byte[] keySequence) {
        sendRawToAgentStdin(keySequence);
        requestAgentCancel();
    }

    private void requestAgentCancel() {
        cancelSignal.set(true);
        Process p = activeProcess;
        if (p != null && p.isAlive()) {
            killProcess(p);
        }
        Thread wt = waitingThread;
        if (wt != null) {
            wt.interrupt();
        }
    }

    // ── Thread-safe output (prints into scroll region) ────────────────────

    /**
     * Prints a line of text into the scroll region.
     * Moves cursor to scrollBottom, prints the text + newline (which scrolls
     * the region content up), then redraws the entire fixed input box below
     * to guarantee it's never corrupted.
     */
    /**
     * Write terminal-control bytes through JLine's raw terminal fd. This is for
     * the subprocess render path; System.out is wrapped by JLine and can route
     * escape sequences through the line reader instead of the actual terminal.
     */
    private void writeRawTerminal(String text) throws IOException {
        if (text == null || text.isEmpty() || terminal == null) return;
        OutputStream out = terminal.output();
        Object lock = drawLock != null ? drawLock : this;
        synchronized (lock) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
            out.flush();
        }
    }

    private void resetHostInputModes() {
        try {
            writeRawTerminal(TerminalQueryStripper.hostInputModeResetSequence());
        } catch (IOException | IOError ignored) {
        }
    }

    /**
     * Result of filtering one PTY byte chunk. displayBytes are written to the
     * real terminal; queries are sent to the agent-specific decoder so it can
     * synthesize responses back to the subprocess.
     */
    private record TerminalQueryStripResult(byte[] displayBytes, String queries) {
        private static final TerminalQueryStripResult EMPTY = new TerminalQueryStripResult(new byte[0], "");
    }

    /**
     * Streaming byte-level sanitizer for subprocess PTY output.
     * <p>
     * Rendering must stay byte-preserving: converting PTY output to String and
     * back can corrupt UTF-8 when a chunk splits a multibyte glyph. This strips
     * terminal queries that would make the real terminal respond on Kompile's
     * stdin, plus non-text terminal feature payloads (Kitty graphics, DCS/APC,
     * shell integration OSC markers) that some agent TUIs emit even when the
     * parent terminal does not support them. Ordinary repaint/movement/color
     * bytes still pass through unchanged.
     */
    private static final class TerminalQueryStripper {
        private static final int ESC = 0x1B;
        private static final int CSI_8_BIT = 0x9B;
        private static final int DCS_8_BIT = 0x90;
        private static final int SOS_8_BIT = 0x98;
        private static final int OSC_8_BIT = 0x9D;
        private static final int PM_8_BIT = 0x9E;
        private static final int APC_8_BIT = 0x9F;
        private static final Set<String> HOST_INPUT_MODES = Set.of(
                "9", "1000", "1001", "1002", "1003", "1004", "1005", "1006",
                "1007", "1015", "1016", "2004");
        private static final String HOST_INPUT_MODE_RESET = "\033[?9l\033[?1000l\033[?1001l"
                + "\033[?1002l\033[?1003l\033[?1004l\033[?1005l\033[?1006l"
                + "\033[?1007l\033[?1015l\033[?1016l\033[?2004l\033[?25h";

        private byte[] pending = new byte[0];

        static String hostInputModeResetSequence() {
            return HOST_INPUT_MODE_RESET;
        }

        void reset() {
            pending = new byte[0];
        }

        TerminalQueryStripResult strip(byte[] data, int offset, int length) {
            if ((data == null || length <= 0) && pending.length == 0) {
                return TerminalQueryStripResult.EMPTY;
            }

            int safeLength = data == null ? 0 : Math.max(0, length);
            byte[] input = combine(data, offset, safeLength);
            int len = input.length;
            if (len == 0) return TerminalQueryStripResult.EMPTY;

            ByteArrayOutputStream display = new ByteArrayOutputStream(len);
            StringBuilder queries = new StringBuilder();
            int i = 0;
            while (i < len) {
                int b = input[i] & 0xFF;
                if (b == ESC) {
                    if (i + 1 >= len) {
                        savePending(input, i, len);
                        break;
                    }
                    int next = input[i + 1] & 0xFF;
                    if (next == '[') {
                        int end = findCsiEnd(input, i + 2, len);
                        if (end < 0) {
                            savePending(input, i, len);
                            break;
                        }
                        if (isCsiQuery(input, i + 2, end - 1)) {
                            appendQuery(queries, input, i, end);
                        } else if (!isCsiNonDisplay(input, i + 2, end - 1)) {
                            display.write(input, i, end - i);
                        }
                        i = end;
                        continue;
                    }
                    if (next == ']') {
                        int end = findOscEnd(input, i + 2, len);
                        if (end < 0) {
                            savePending(input, i, len);
                            break;
                        }
                        int contentEnd = oscContentEnd(input, end);
                        if (isOscQuery(input, i + 2, contentEnd)) {
                            appendQuery(queries, input, i, end);
                        } else if (!isOscNonDisplay(input, i + 2, contentEnd)) {
                            display.write(input, i, end - i);
                        }
                        i = end;
                        continue;
                    }
                    if (isEscControlStringStarter(next)) {
                        int end = findStringTerminator(input, i + 2, len);
                        if (end < 0) {
                            savePending(input, i, len);
                            break;
                        }
                        i = end;
                        continue;
                    }

                    display.write(input, i, 2);
                    i += 2;
                    continue;
                }

                if (b == CSI_8_BIT) {
                    int end = findCsiEnd(input, i + 1, len);
                    if (end < 0) {
                        savePending(input, i, len);
                        break;
                    }
                    if (isCsiQuery(input, i + 1, end - 1)) {
                        appendC1CsiQuery(queries, input, i + 1, end);
                    } else if (!isCsiNonDisplay(input, i + 1, end - 1)) {
                        display.write(input, i, end - i);
                    }
                    i = end;
                    continue;
                }

                if (b == OSC_8_BIT) {
                    int end = findOscEnd(input, i + 1, len);
                    if (end < 0) {
                        savePending(input, i, len);
                        break;
                    }
                    int contentEnd = oscContentEnd(input, end);
                    if (isOscQuery(input, i + 1, contentEnd)) {
                        appendC1OscQuery(queries, input, i + 1, end);
                    } else if (!isOscNonDisplay(input, i + 1, contentEnd)) {
                        display.write(input, i, end - i);
                    }
                    i = end;
                    continue;
                }

                if (isC1ControlStringStarter(b)) {
                    int end = findStringTerminator(input, i + 1, len);
                    if (end < 0) {
                        savePending(input, i, len);
                        break;
                    }
                    i = end;
                    continue;
                }

                display.write(b);
                i++;
            }

            return new TerminalQueryStripResult(display.toByteArray(), queries.toString());
        }

        private byte[] combine(byte[] data, int offset, int length) {
            if (pending.length == 0) {
                if (length == 0) return new byte[0];
                return Arrays.copyOfRange(data, offset, offset + length);
            }
            byte[] combined = new byte[pending.length + length];
            System.arraycopy(pending, 0, combined, 0, pending.length);
            if (length > 0 && data != null) {
                System.arraycopy(data, offset, combined, pending.length, length);
            }
            pending = new byte[0];
            return combined;
        }

        private void savePending(byte[] input, int start, int end) {
            pending = Arrays.copyOfRange(input, start, end);
        }

        private int findCsiEnd(byte[] input, int start, int len) {
            for (int j = start; j < len; j++) {
                int b = input[j] & 0xFF;
                if (b >= 0x40 && b <= 0x7E) {
                    return j + 1;
                }
            }
            return -1;
        }

        private boolean isCsiQuery(byte[] input, int payloadStart, int finalIndex) {
            char finalByte = (char) (input[finalIndex] & 0xFF);
            String payload = ascii(input, payloadStart, finalIndex);
            if (finalByte == 'c') {
                return payload.isEmpty() || payload.equals("0") || payload.equals(">") || payload.equals(">0");
            }
            if (finalByte == 'n') {
                return payload.equals("5") || payload.equals("6");
            }
            if (finalByte == 'u') {
                return payload.equals("?");
            }
            if (finalByte == 't') {
                return payload.equals("14") || payload.equals("16") || payload.equals("18");
            }
            if (finalByte == 'p') {
                return payload.endsWith("$");
            }
            if (finalByte == 'q') {
                return payload.equals(">") || payload.equals(">0");
            }
            return false;
        }

        private boolean isCsiNonDisplay(byte[] input, int payloadStart, int finalIndex) {
            return isHostInputModeToggle(input, payloadStart, finalIndex)
                    || isSgrMouseReport(input, payloadStart, finalIndex);
        }

        private boolean isHostInputModeToggle(byte[] input, int payloadStart, int finalIndex) {
            char finalByte = (char) (input[finalIndex] & 0xFF);
            if (finalByte != 'h' && finalByte != 'l') return false;
            String payload = ascii(input, payloadStart, finalIndex);
            if (!payload.startsWith("?")) return false;
            for (String mode : payload.substring(1).split(";")) {
                int colon = mode.indexOf(':');
                if (colon >= 0) mode = mode.substring(0, colon);
                if (HOST_INPUT_MODES.contains(mode)) return true;
            }
            return false;
        }

        private boolean isSgrMouseReport(byte[] input, int payloadStart, int finalIndex) {
            char finalByte = (char) (input[finalIndex] & 0xFF);
            if (finalByte != 'M' && finalByte != 'm') return false;
            String payload = ascii(input, payloadStart, finalIndex);
            if (!payload.startsWith("<")) return false;
            String[] parts = payload.substring(1).split(";");
            if (parts.length != 3) return false;
            for (String part : parts) {
                if (!part.matches("\\d+")) return false;
            }
            return true;
        }

        private boolean isEscControlStringStarter(int next) {
            return next == 'P' || next == '_' || next == '^' || next == 'X';
        }

        private boolean isC1ControlStringStarter(int b) {
            return b == DCS_8_BIT || b == SOS_8_BIT || b == PM_8_BIT || b == APC_8_BIT;
        }

        private int findStringTerminator(byte[] input, int start, int len) {
            for (int j = start; j < len; j++) {
                int b = input[j] & 0xFF;
                if (b == ESC && j + 1 < len && (input[j + 1] & 0xFF) == '\\') return j + 2;
            }
            return -1;
        }

        private int findOscEnd(byte[] input, int start, int len) {
            for (int j = start; j < len; j++) {
                int b = input[j] & 0xFF;
                if (b == 0x07) return j + 1;
                if (b == ESC && j + 1 < len && (input[j + 1] & 0xFF) == '\\') return j + 2;
            }
            return -1;
        }

        private int oscContentEnd(byte[] input, int sequenceEnd) {
            if (sequenceEnd >= 2
                    && (input[sequenceEnd - 2] & 0xFF) == ESC
                    && (input[sequenceEnd - 1] & 0xFF) == '\\') {
                return sequenceEnd - 2;
            }
            return sequenceEnd - 1;
        }

        private boolean isOscQuery(byte[] input, int contentStart, int contentEnd) {
            String content = ascii(input, contentStart, contentEnd);
            return content.startsWith("10;?")
                    || content.startsWith("11;?")
                    || content.startsWith("12;?");
        }

        private boolean isOscNonDisplay(byte[] input, int contentStart, int contentEnd) {
            String content = ascii(input, contentStart, contentEnd);
            return content.startsWith("66;")
                    || (content.startsWith("4;") && content.endsWith(";?"));
        }

        private void appendQuery(StringBuilder queries, byte[] input, int start, int end) {
            queries.append(ascii(input, start, end));
        }

        private void appendC1CsiQuery(StringBuilder queries, byte[] input, int payloadStart, int end) {
            queries.append((char) ESC).append('[').append(ascii(input, payloadStart, end));
        }

        private void appendC1OscQuery(StringBuilder queries, byte[] input, int contentStart, int end) {
            queries.append((char) ESC).append(']').append(ascii(input, contentStart, end));
        }

        private String ascii(byte[] input, int start, int end) {
            if (end <= start) return "";
            return new String(input, start, end - start, StandardCharsets.US_ASCII);
        }
    }

    private void safePrintln(String text) {
        String line = text == null ? "" : text;
        LineReader reader = activeLineReader;
        boolean restoreActivePrompt = reader != null && !busyInputActive;
        synchronized (drawLock) {
            appendScrollbackLineLocked(line);
            boolean keepCursorInInput = busyInputActive;
            if (restoreActivePrompt) {
                hideCursor();
            } else if (!keepCursorInInput) {
                saveCursor();
            }
            if (tui != null) {
                tui.reestablishScrollRegion();
                scrollBottom = tui.scrollBottom();
                clampScrollViewportOffsetLocked();
            }
            if (scrollViewportOffset == 0) {
                // Move cursor to last row of scroll region and print text.
                System.out.printf("\033[%d;1H\033[2K%s", scrollBottom, line);
                // Newline at the bottom of the scroll region triggers scroll-up.
                System.out.print("\n");
            } else {
                redrawScrollViewportContentLocked();
            }
            // Redraw the fixed area. If readLine is active, avoid clearing the
            // input row on every streamed line; only repaint prompt text when it
            // changes and otherwise just keep the cursor visible at the buffer.
            if (restoreActivePrompt) {
                drawFixedInputChrome();
                drawActivePromptLine(reader, false);
            } else {
                drawFixedInputBox(keepCursorInInput);
                if (!keepCursorInInput) {
                    restoreCursor();
                }
            }
            System.out.flush();
        }
    }

    /** Prints an empty line in the scroll region. */
    private void safePrintln() {
        safePrintln("");
    }

    /**
     * Single rendering path for all assistant text output.
     * Runs text through the markdown renderer and prints each line
     * to the scroll region with standard indentation.
     */
    private void renderToScroll(String text) {
        String rendered = ascii.renderMarkdown(text);
        for (String rl : rendered.split("\n", -1)) {
            safePrintln("  " + rl);
        }
    }

    /**
     * Same as {@link #renderToScroll(String)} but appends formatted lines
     * to a buffer instead of printing (used during resume replay).
     */
    private void renderToBuffer(String text, List<String> buf) {
        String rendered = ascii.renderMarkdown(text);
        for (String rl : rendered.split("\n", -1)) {
            buf.add("  " + rl);
        }
    }

    /**
     * Replays prior conversation turns into the scroll region so the user
     * can see the conversation history before continuing.
     * <p>
     * Shows "Loading..." while reading turns, then renders ALL turns into a
     * buffer and flushes everything at once so the screen doesn't stream a
     * wall of text line by line. The terminal naturally ends scrolled to
     * the bottom — the user can scroll up to see earlier turns.
     */
    private void replayConversationHistory(String sessionId, ChatHistory history) {
        safePrintln(DIM + "  Loading..." + RESET);

        List<ChatHistory.Turn> turns = null;

        // Try kompile's own session store first
        try {
            turns = ai.kompile.cli.main.chat.format.ConversationReader.readKompileSession(sessionId);
        } catch (Exception e) {
            // Fall through to external sources
        }

        // Try external agent sources
        if (turns == null || turns.isEmpty()) {
            for (String source : List.of("claude-code", "codex", "qwen", "opencode", "gemini")) {
                try {
                    turns = ai.kompile.cli.main.chat.format.ConversationReader.readExternalSession(source, sessionId);
                    if (turns != null && !turns.isEmpty()) break;
                } catch (Exception ignored) {}
            }
        }

        if (turns == null || turns.isEmpty()) {
            safePrintln(DIM + "  (No prior turns found for session " + sessionId + ")" + RESET);
            safePrintln();
            return;
        }

        // Buffer all rendered lines — we flush everything at once so the screen
        // doesn't blitz the user with streaming text.
        List<String> buf = new ArrayList<>();

        buf.add(DIM + "  ── Resumed conversation (" + turns.size() + " turns) ──" + RESET);
        buf.add("");

        for (ChatHistory.Turn turn : turns) {
            com.fasterxml.jackson.databind.node.ArrayNode blocks = turn.rawContentBlocks();

            if ("user".equals(turn.role())) {
                if (blocks != null && !blocks.isEmpty()) {
                    replayUserBlocks(blocks, history, buf);
                } else {
                    String content = turn.content();
                    if (content != null && !content.isBlank() && !isReplayNoise(content)) {
                        buf.add(CYAN + "  You: " + RESET + truncateForReplay(content));
                        history.logUserMessage(content);
                        buf.add("");
                    }
                }
            } else {
                if (blocks != null && !blocks.isEmpty()) {
                    replayAssistantBlocks(blocks, history, buf);
                } else {
                    String content = turn.content();
                    if (content != null && !content.isBlank()) {
                        replayAssistantText(content, history, buf);
                    }
                }
            }
        }

        buf.add(DIM + "  ── End of history · continue below ──" + RESET);
        buf.add("");

        // Flush the entire buffer at once
        for (String line : buf) {
            safePrintln(line);
        }
    }

    /** Replay user-role content blocks into a buffer. */
    private void replayUserBlocks(com.fasterxml.jackson.databind.node.ArrayNode blocks,
                                  ChatHistory history, List<String> buf) {
        for (com.fasterxml.jackson.databind.JsonNode block : blocks) {
            String type = block.has("type") ? block.get("type").asText() : "";
            switch (type) {
                case "tool_result" -> {
                    boolean isError = block.has("is_error") && block.get("is_error").asBoolean();
                    String status = isError ? renderer.red("✗ error") : renderer.green("✓");
                    String preview = "";
                    if (block.has("content")) {
                        com.fasterxml.jackson.databind.JsonNode contentNode = block.get("content");
                        if (contentNode.isTextual()) {
                            preview = truncateForReplay(contentNode.asText());
                        } else if (contentNode.isArray()) {
                            for (com.fasterxml.jackson.databind.JsonNode part : contentNode) {
                                if ("text".equals(part.path("type").asText("")) && part.has("text")) {
                                    preview = truncateForReplay(part.get("text").asText());
                                    break;
                                }
                            }
                        }
                    }
                    if (!preview.isBlank()) {
                        buf.add(renderer.dim("  " + status + " " + truncateForReplay(preview)));
                    }
                }
                case "text" -> {
                    String text = block.has("text") ? block.get("text").asText() : "";
                    if (!text.isBlank() && !isReplayNoise(text)) {
                        buf.add(CYAN + "  You: " + RESET + truncateForReplay(text));
                        history.logUserMessage(text);
                        buf.add("");
                    }
                }
                default -> {
                    if (block.isTextual() && !block.asText().isBlank() && !isReplayNoise(block.asText())) {
                        buf.add(CYAN + "  You: " + RESET + truncateForReplay(block.asText()));
                        history.logUserMessage(block.asText());
                        buf.add("");
                    }
                }
            }
        }
    }

    /** Replay assistant-role content blocks into a buffer. */
    private void replayAssistantBlocks(com.fasterxml.jackson.databind.node.ArrayNode blocks,
                                       ChatHistory history, List<String> buf) {
        StringBuilder fullText = new StringBuilder();

        for (com.fasterxml.jackson.databind.JsonNode block : blocks) {
            String type = block.has("type") ? block.get("type").asText() : "";
            switch (type) {
                case "thinking" -> {
                    String thinking = block.has("thinking") ? block.get("thinking").asText() : "";
                    if (!thinking.isBlank()) {
                        String preview = thinking.length() > 120
                                ? thinking.substring(0, 117) + "..."
                                : thinking;
                        buf.add(renderer.dim("  thinking: " + preview));
                    }
                }
                case "tool_use" -> {
                    String toolName = block.has("name") ? block.get("name").asText() : "unknown";
                    String input = "";
                    if (block.has("input")) {
                        input = block.get("input").toString();
                    }
                    buf.add(renderer.renderToolCallStart(toolName, input));
                }
                case "text" -> {
                    String text = block.has("text") ? block.get("text").asText() : "";
                    if (!text.isBlank() && !isReplayNoise(text)) {
                        fullText.append(text);
                        renderToBuffer(text, buf);
                    }
                }
            }
        }

        if (fullText.length() > 0) {
            history.logAgentResponse(agent, fullText.toString(), 0);
        }
        buf.add("");
    }

    /** Replay plain-text assistant response into a buffer. */
    private void replayAssistantText(String content, ChatHistory history, List<String> buf) {
        if (isReplayNoise(content)) return;
        renderToBuffer(content, buf);
        history.logAgentResponse(agent, content, 0);
        buf.add("");
    }

    /**
     * Detects system prompt noise that should not be displayed during resume replay.
     * Catches XML-tagged instruction blocks (permissions, skills, system-reminder),
     * enforcer rules, MCP tool listings, and other internal framework content.
     */
    private static boolean isReplayNoise(String text) {
        if (text == null || text.isBlank()) return false;
        // Delegate to SubprocessAgentRunner's existing noise detector
        if (SubprocessAgentRunner.isSystemPromptNoise(text)) return true;
        // XML-tagged instruction blocks that agents embed in conversations
        if (text.contains("<permissions") && text.contains("instructions>")) return true;
        if (text.contains("<sandbox_mode>")) return true;
        if (text.contains("<skills_instructions>")) return true;
        if (text.contains("<system-reminder>")) return true;
        if (text.contains("<available-deferred-tools>")) return true;
        if (text.contains("<tool_config>")) return true;
        // Claude system prompt markers
        if (text.contains("You are Claude Code") && text.contains("Anthropic")) return true;
        if (text.contains("IMPORTANT: Assist with authorized security testing")) return true;
        if (text.contains("# Environment") && text.contains("git repository")) return true;
        // Codex/other agent system prompts
        if (text.contains("You are an AI assistant") && text.contains("operating in a sandboxed")) return true;
        if (text.contains("sandbox_mode")) return true;
        return false;
    }

    private static String truncateForReplay(String line) {
        if (line == null) return "";
        if (line.length() > 200) return line.substring(0, 197) + "...";
        return line;
    }

    // ── Subprocess communication ───────────────────────────────────────────

    /**
     * Send a user message to a Kompile-managed interactive subprocess.
     * Kompile writes the prompt to child stdin, scrapes child output through
     * parent-child process IO, and considers the turn complete after output
     * becomes idle.
     *
     * @return the agent's text response (for follow-up question detection)
     */
    private String sendToAgent(String message, ChatHistory history, ChatSessionMetrics metrics) {
        return sendToTuiAgent(message, history, metrics);
    }


    /**
     * Persistent subprocess path. The agent process is launched once and kept
     * alive across messages. Each message writes to stdin and reads the streamed
     * response from stdout via the PTY. VT terminal query responses are written
     * back to the subprocess stdin so TUI agents (Bubble Tea, etc.) don't hang
     * waiting for feature detection replies.
     */
    private String sendToTuiAgent(String message, ChatHistory history, ChatSessionMetrics metrics) {
        String agentBinary = resolveAgent(agent);
        if (agentBinary == null) {
            safePrintln(renderer.red("  Agent '" + agent + "' not found on PATH."));
            safePrintln(renderer.dim("  Supported agents: " + String.join(", ",
                    ai.kompile.cli.main.chat.config.ChatConfig.getPassthroughAgentOrder())));
            return "";
        }

        cancelSignal.set(false);
        history.logUserMessage(message);
        metrics.recordUserTurn(message);

        renderer.setTerminalTitle("Kompiling... (" + agent + ")");
        updateStatusLine("running");
        TerminalRenderer.SpinnerHandle spinner = startStatusSpinner(agent);

        StringBuilder fullText = new StringBuilder();
        StringBuilder pendingText = new StringBuilder();
        List<String> toolCalls = new ArrayList<>();
        long turnStart = System.currentTimeMillis();
        AtomicBoolean spinnerStopped = new AtomicBoolean(false);

        // Discard TUI initialization output — only capture post-message response.
        // Wire spinner so it can be stopped, but don't wire fullText until after send.
        tuiFullText = null;
        tuiToolCalls = null;
        tuiMetrics = metrics;
        tuiSpinner = spinner;
        tuiSpinnerStopped = spinnerStopped;
        tuiPendingText = null;
        tuiLastDecodedContent = "";
        tuiLastRenderedContent = "";
        tuiTurnSawContent.set(false);
        tuiLastDecodedAt.set(0);
        resetLiveDecoderScrollbackBlock();

        try {
            // Launch the persistent TUI process on first message
            if (tuiProcess == null || !tuiProcess.isAlive()) {
                List<String> agentCmd = buildCommand(agentBinary, message);

                // Size the subprocess PTY to fit kompile's scroll region —
                // the area between the top bar and the input box. The subprocess
                // TUI will fill this space with its own chrome + content.
                int ptyCols = terminal != null ? terminal.getWidth() : 120;
                if (ptyCols <= 0) ptyCols = 120;
                // scrollBottom is the last row of the scroll region (1-indexed).
                // Row 1 is the top bar, so the subprocess gets rows 2..scrollBottom.
                int ptyRows = Math.max(10, scrollBottom - 1);
                List<String> wrappedCmd = wrapWithPty(agentCmd, ptyRows, ptyCols);

                ProcessBuilder pb = new ProcessBuilder(wrappedCmd);
                pb.directory(new File(workingDir).getAbsoluteFile());
                pb.redirectErrorStream(true);

                Map<String, String> env = pb.environment();
                inheritEnv(env, "PATH", "HOME", "USER", "SHELL", "LANG", "LC_ALL",
                        "JAVA_HOME", "MAVEN_HOME", "TERM", "COLORTERM",
                        "ANTHROPIC_API_KEY", "OPENAI_API_KEY", "GOOGLE_API_KEY");
                // Ensure the subprocess sees a real terminal environment
                env.putIfAbsent("TERM", "xterm-256color");
                env.putIfAbsent("COLORTERM", "truecolor");
                env.put("COLUMNS", String.valueOf(ptyCols));
                env.put("LINES", String.valueOf(ptyRows));
                if (enforcerExtraEnv != null) {
                    env.putAll(enforcerExtraEnv);
                }

                Process process = pb.start();
                tuiProcess = process;
                activeProcess = process;
                agentStdin = process.getOutputStream();

                // Open subprocess log files:
                //   agent-subprocess.log  — text log (append, human-readable annotations)
                //   agent-pty-dump.bin    — raw byte dump (overwritten each session)
                //     Replay: cat ~/.kompile/logs/agent-pty-dump.bin
                //     Hex:    xxd ~/.kompile/logs/agent-pty-dump.bin | less
                try {
                    Path logDir = Path.of(System.getProperty("user.home"), ".kompile", "logs");
                    Files.createDirectories(logDir);
                    Path logFile = logDir.resolve("agent-subprocess.log");
                    subprocessLogWriter = new java.io.BufferedWriter(
                            new java.io.FileWriter(logFile.toFile(), true));
                    subprocessLogWriter.write("\n--- " + agent + " subprocess started at "
                            + java.time.Instant.now() + " ---\n");
                    subprocessLogWriter.flush();
                    // Raw binary dump — overwrite per session so it stays manageable
                    Path dumpFile = logDir.resolve("agent-pty-dump.bin");
                    subprocessPtyDump = new java.io.BufferedOutputStream(
                            new java.io.FileOutputStream(dumpFile.toFile()));
                } catch (IOException e) {
                    // Non-fatal — log files are optional
                }

                // Create the agent-specific decoder for VT response generation
                // and content extraction (history/logging). The decoder knows
                // which terminal queries this agent needs answered and which
                // would cause problems (e.g. Kitty keyboard → nano in Claude Code).
                agentDecoder = ai.kompile.cli.main.chat.tui.AgentTuiDecoder.forAgent(agent);
                tuiQueryStripper.reset();

                // Create a VirtualTerminal to shadow the subprocess screen state.
                // Raw passthrough handles rendering — the VT is only used by the
                // decoder for cursor position tracking (DSR responses) and
                // content extraction for history/logging.
                if (virtualTerminal == null) {
                    virtualTerminal = new ai.kompile.cli.main.chat.tui.VirtualTerminal(ptyRows, ptyCols);
                }

                // Register persistent subprocess in status bar
                tuiSubagentId = "tui-" + System.currentTimeMillis();
                if (tui != null) {
                    tui.getStatusBar().registerSubagent(tuiSubagentId, agent, "");
                }

                // Raw passthrough output reader — relay PTY bytes directly to
                // the real terminal. Uses JLine's terminal.output() which is
                // the actual terminal fd, NOT System.out (which JLine may have
                // wrapped/redirected and won't pass escape sequences through).
                //
                // Also feeds a shadow VirtualTerminal so the agent-specific decoder
                // can track cursor position (for DSR responses) and extract content.
                final OutputStream termOut = terminal.output();
                final ai.kompile.cli.main.chat.tui.AgentTuiDecoder decoder = agentDecoder;
                final ai.kompile.cli.main.chat.tui.VirtualTerminal vt = virtualTerminal;
                tuiOutputReader = new Thread(() -> {
                    try {
                        InputStream is = process.getInputStream();
                        byte[] buf = new byte[8192];
                        int n;
                        while ((n = is.read(buf)) != -1) {
                            if (Thread.currentThread().isInterrupted()) break;
                            // Raw binary dump — exact bytes for replay/analysis
                            java.io.OutputStream dump = subprocessPtyDump;
                            if (dump != null) {
                                try { dump.write(buf, 0, n); dump.flush(); } catch (IOException ignored) {}
                            }
                            lastOutputTime.set(System.currentTimeMillis());

                            String chunk = new String(buf, 0, n, StandardCharsets.UTF_8);

                            // Feed shadow VT so decoder can track cursor position
                            vt.feed(chunk);

                            String inputResp = decoder.buildInputResponses(chunk, vt);
                            if (inputResp != null && !inputResp.isEmpty()) {
                                try {
                                    process.getOutputStream().write(inputResp.getBytes(StandardCharsets.UTF_8));
                                    process.getOutputStream().flush();
                                } catch (IOException ignored) {}
                            }

                            // Strip terminal query sequences before relaying to the
                            // real terminal. Without this, queries like DA1 (ESC[c),
                            // DSR (ESC[6n) pass through to the real terminal, which
                            // responds on kompile's stdin — showing as garbage text
                            // like "[?65;1;9c" in the status bar. All non-query PTY
                            // bytes remain untouched for raw terminal rendering.
                            TerminalQueryStripResult stripped = tuiQueryStripper.strip(buf, 0, n);

                            // Use the agent-specific decoder to generate VT responses.
                            // Different agents need different responses — the decoder
                            // knows what's safe to send.
                            String queryChunk = stripped.queries();
                            if (queryChunk != null && !queryChunk.isEmpty()) {
                                String vtResp = decoder.buildResponses(queryChunk, vt);
                                if (vtResp != null && !vtResp.isEmpty()) {
                                    try {
                                        process.getOutputStream().write(vtResp.getBytes(StandardCharsets.UTF_8));
                                        process.getOutputStream().flush();
                                    } catch (IOException ignored) {}
                                }
                            }

                            byte[] displayBytes = stripped.displayBytes();

                            if (decoder.renderRawTui()) {
                                // Write cleaned bytes to the real terminal fd for agents whose
                                // own UI should be visible.
                                synchronized (drawLock) {
                                    if (displayBytes.length > 0) {
                                        termOut.write(displayBytes);
                                        termOut.flush();
                                    }
                                }
                            } else {
                                // Full-screen alternate TUIs like OpenCode own their screen in
                                // standalone mode, but Kompile owns this terminal. Render only
                                // decoder-extracted assistant text into the scroll area.
                                processDecodedTuiScreen(decoder, vt);
                            }
                        }
                    } catch (IOException e) {
                        // Stream closed — expected on shutdown
                    } finally {
                        // Flush and close the dump on stream end
                        java.io.OutputStream dump = subprocessPtyDump;
                        if (dump != null) {
                            try { dump.flush(); dump.close(); } catch (IOException ignored) {}
                        }
                    }
                }, "tui-output-reader");
                tuiOutputReader.setDaemon(true);
                tuiOutputReader.start();

                // Wait for the TUI to initialize and for any agent-specific
                // bootstrap prompts to be answered before sending the message.
                Thread.sleep(agentDecoder.startupSettleMillis());
            }

            // Position the host cursor for agents that paint their own TUI.
            // Decoder-owned agents like OpenCode should not move the real cursor
            // away from Kompile's active readLine prompt.
            if (agentDecoder == null || agentDecoder.renderRawTui()) {
                writeRawTerminal("\033[2;1H"); // row 2 = first row after top bar
            }

            // Capture only post-message response text.
            tuiFullText = fullText;
            tuiToolCalls = toolCalls;
            tuiMetrics = metrics;
            tuiPendingText = pendingText;

            // Reset the decoder transcript now that startup chrome has rendered —
            // the persistent decoder is reused across turns, so this clears both
            // any prior turn and startup banners; only the response accumulates.
            if (agentDecoder != null) agentDecoder.resetHistory();

            // Send the message via stdin
            lastSentMessage = message;
            synchronized (agentStdin) {
                agentStdin.write(message.getBytes(StandardCharsets.UTF_8));
                agentStdin.flush();
                long submitDelay = agentDecoder.submitDelayMillis();
                if (submitDelay > 0) {
                    Thread.sleep(submitDelay);
                }
                agentStdin.write(agentDecoder.submitSequence().getBytes(StandardCharsets.UTF_8));
                agentStdin.flush();
            }
            long messageSentAt = System.currentTimeMillis();
            lastOutputTime.set(messageSentAt);

            boolean decoderOwnedRendering = agentDecoder != null && !agentDecoder.renderRawTui();
            while (tuiProcess.isAlive() && !cancelSignal.get()) {
                if (decoderOwnedRendering && virtualTerminal != null && decodedTuiTurnComplete(agentDecoder, messageSentAt)) {
                    break;
                }
                Thread.sleep(200);
            }

        } catch (Exception e) {
            if (!cancelSignal.get()) {
                safePrintln(renderer.red("\n  Error running agent: " + e.getMessage()));
            }
        } finally {
            spinner.stop();
            // Restore kompile's scroll region and input area after the
            // subprocess TUI was rendering directly to the terminal.
            if (tui != null) {
                tui.reestablishScrollRegion();
                scrollBottom = tui.scrollBottom();
            }
            resetHostInputModes();
            drawFixedInputBox();
            // Don't null out activeProcess or kill the TUI — it persists
            // Update status bar to idle (subprocess stays registered but shows idle)
            if (tui != null && tuiSubagentId != null) {
                tui.getStatusBar().updateSubagentStatus(tuiSubagentId, "idle");
            }
        }

        long turnDuration = System.currentTimeMillis() - turnStart;

        if (cancelSignal.get()) {
            safePrintln();
            safePrintln(renderer.yellow("  Cancelled."));
            history.logSystem("User cancelled agent response after " + turnDuration + "ms");
        }

        flushDecodedTuiRemainder(fullText, spinner, spinnerStopped);

        // Extract content from the shadow VT using the agent-specific decoder.
        // Raw passthrough handled rendering — this is for history/logging only.
        if (fullText.length() == 0 && agentDecoder != null && virtualTerminal != null) {
            agentDecoder.observe(virtualTerminal);
            String extracted = agentDecoder.renderHistory();
            if (extracted == null || extracted.isEmpty()) extracted = agentDecoder.extractContent(virtualTerminal);
            if (extracted != null && !extracted.isEmpty()) {
                fullText.append(extracted);
            }
        }

        if (fullText.length() > 0) {
            history.logAgentResponse(agent, fullText.toString(), turnDuration);
            metrics.recordAssistantTurn(fullText.toString(), turnDuration);
        }

        if (!toolCalls.isEmpty()) {
            List<String> prettyNames = toolCalls.stream()
                    .map(TerminalRenderer::prettifyToolName)
                    .toList();
            safePrintln(renderer.dim("  " + toolCalls.size() + " tool call(s): "
                    + String.join(", ", prettyNames)));
        }

        firstMessageSent = true;
        safePrintln();
        renderer.setTerminalTitle("kompile [" + agent + "]");
        return fullText.toString();
    }


    private boolean decodedTuiTurnComplete(ai.kompile.cli.main.chat.tui.AgentTuiDecoder decoder,
                                           long messageSentAt) {
        if (decoder == null || virtualTerminal == null) return false;
        long now = System.currentTimeMillis();
        long quietFor = now - Math.max(messageSentAt, lastOutputTime.get());
        boolean sawContent = tuiTurnSawContent.get() || tuiLastDecodedAt.get() >= messageSentAt;
        boolean idle = decoder.isIdle(virtualTerminal);
        boolean responding = decoder.isResponding(virtualTerminal);

        if (sawContent && idle && quietFor >= decoder.turnIdleMillis()) {
            return true;
        }
        if (sawContent && !responding && quietFor >= Math.max(2500L, decoder.turnIdleMillis() + 1000L)) {
            return true;
        }
        if (sawContent && quietFor >= 6000L) {
            return true;
        }
        return !sawContent && idle && now - messageSentAt >= 45_000L;
    }

    private void stopTuiSpinnerForDecodedContent() {
        TerminalRenderer.SpinnerHandle spinner = tuiSpinner;
        AtomicBoolean stopped = tuiSpinnerStopped;
        if (spinner != null && stopped != null && stopped.compareAndSet(false, true)) {
            spinner.stop();
            safePrintln();
        }
    }

    private synchronized void processDecodedTuiScreen(ai.kompile.cli.main.chat.tui.AgentTuiDecoder decoder,
                                                      ai.kompile.cli.main.chat.tui.VirtualTerminal vt) {
        if (decoder == null || vt == null) return;
        if (decoder.isResponding(vt)) {
            TerminalRenderer.SpinnerHandle spinner = tuiSpinner;
            if (spinner != null) spinner.setPhase("responding");
            if (tui != null && tuiSubagentId != null) {
                tui.getStatusBar().updateSubagentStatus(tuiSubagentId, "responding");
            }
        }

        // Accumulate the turn transcript in the decoder (survives the agent's
        // own viewport scrolling), then render the full history.
        decoder.observe(vt);
        String extracted = decoder.renderHistory();
        if (extracted == null || extracted.isBlank()) extracted = decoder.extractStreamingContent(vt);
        if (extracted == null || extracted.isBlank()) return;
        tuiLastDecodedContent = extracted;
        tuiTurnSawContent.set(true);
        tuiLastDecodedAt.set(System.currentTimeMillis());

        stopTuiSpinnerForDecodedContent();
        updateLiveDecoderScrollbackBlock(extracted, false);

        String renderable = renderableDecodedDelta(tuiLastRenderedContent, extracted, decoder.isIdle(vt));
        if (renderable == null || renderable.isBlank()) return;
        emitDecodedTuiText(renderable, tuiFullText, tuiSpinner, tuiSpinnerStopped, false, false);
        tuiLastRenderedContent = updateRenderedDecodedContent(tuiLastRenderedContent, extracted, renderable);
    }

    private synchronized void flushDecodedTuiRemainder(StringBuilder fullText,
                                                       TerminalRenderer.SpinnerHandle spinner,
                                                       AtomicBoolean spinnerStopped) {
        ai.kompile.cli.main.chat.tui.AgentTuiDecoder decoder = agentDecoder;
        if (decoder == null || decoder.renderRawTui() || virtualTerminal == null) return;
        decoder.observe(virtualTerminal);
        String extracted = decoder.renderHistory();
        if (extracted == null || extracted.isBlank()) extracted = decoder.extractContent(virtualTerminal);
        if (extracted == null || extracted.isBlank()) {
            flushDecodedMarkdownBuffer(true);
            finishLiveDecoderScrollbackBlock();
            return;
        }
        tuiLastDecodedContent = extracted;
        tuiTurnSawContent.set(true);
        tuiLastDecodedAt.set(System.currentTimeMillis());

        String remainder = renderableDecodedDelta(tuiLastRenderedContent, extracted, true);
        if ((remainder == null || remainder.isBlank()) && fullText.length() == 0) {
            remainder = extracted;
        }
        stopTuiSpinnerForDecodedContent();
        updateLiveDecoderScrollbackBlock(extracted, true);
        if (remainder != null && !remainder.isBlank()) {
            emitDecodedTuiText(remainder, fullText, spinner, spinnerStopped, true, false);
            tuiLastRenderedContent = extracted;
        } else {
            flushDecodedMarkdownBuffer(true, false);
        }
        finishLiveDecoderScrollbackBlock();
    }

    private String renderableDecodedDelta(String rendered, String current, boolean finalChunk) {
        if (current == null || current.isBlank()) return "";
        String previous = rendered == null ? "" : rendered;
        if (current.equals(previous)) return "";
        String delta;
        if (previous.isBlank()) {
            delta = current;
        } else if (current.startsWith(previous)) {
            delta = current.substring(previous.length());
        } else if (previous.contains(current)) {
            return "";
        } else {
            delta = current;
        }
        if (finalChunk) return delta;
        int lastNewline = delta.lastIndexOf('\n');
        if (lastNewline < 0) return "";
        return delta.substring(0, lastNewline + 1);
    }

    private String updateRenderedDecodedContent(String rendered, String current, String emitted) {
        if (current == null) return rendered == null ? "" : rendered;
        String previous = rendered == null ? "" : rendered;
        if (emitted == null || emitted.isBlank()) return previous;
        if (previous.isBlank() && current.startsWith(emitted)) return emitted;
        if (current.startsWith(previous + emitted)) return previous + emitted;
        return current;
    }

    private void emitDecodedTuiText(String text,
                                    StringBuilder fullText,
                                    TerminalRenderer.SpinnerHandle spinner,
                                    AtomicBoolean spinnerStopped,
                                    boolean finalChunk) {
        emitDecodedTuiText(text, fullText, spinner, spinnerStopped, finalChunk, true);
    }

    private void emitDecodedTuiText(String text,
                                    StringBuilder fullText,
                                    TerminalRenderer.SpinnerHandle spinner,
                                    AtomicBoolean spinnerStopped,
                                    boolean finalChunk,
                                    boolean renderDisplay) {
        if (text == null || text.isBlank() || fullText == null) return;
        String filtered = filterDecodedTuiText(text);
        if (filtered.isBlank()) return;

        if (spinner != null && spinnerStopped != null && spinnerStopped.compareAndSet(false, true)) {
            spinner.stop();
            safePrintln();
        }
        lastOutputTime.set(System.currentTimeMillis());
        trackAssistantLog(filtered);
        fullText.append(filtered);

        if (!renderDisplay) return;
        StringBuilder pending = tuiPendingText;
        if (pending == null) {
            renderToScroll(filtered);
            return;
        }
        pending.append(filtered);
        flushDecodedMarkdownBuffer(finalChunk);
    }

    private String filterDecodedTuiText(String text) {
        String echoRef = lastSentMessage;
        String echoTrimmed = echoRef == null ? "" : echoRef.trim();
        StringBuilder filtered = new StringBuilder();
        String[] lines = text.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String line = stripTrailingWhitespace(lines[i]);
            String stripped = line.trim();
            if (!stripped.isEmpty()) {
                if (!echoTrimmed.isEmpty() && (stripped.equals(echoTrimmed) || stripped.endsWith(echoTrimmed))) {
                    lastSentMessage = null;
                    continue;
                }
                filtered.append(line);
            }
            if (i < lines.length - 1) {
                filtered.append('\n');
            }
        }
        return filtered.toString();
    }

    private String stripTrailingWhitespace(String value) {
        int end = value.length();
        while (end > 0 && Character.isWhitespace(value.charAt(end - 1))) {
            end--;
        }
        return end == value.length() ? value : value.substring(0, end);
    }

    private void flushDecodedMarkdownBuffer(boolean finalChunk) {
        flushDecodedMarkdownBuffer(finalChunk, true);
    }

    private void flushDecodedMarkdownBuffer(boolean finalChunk, boolean renderDisplay) {
        StringBuilder pending = tuiPendingText;
        if (pending == null || pending.length() == 0) return;
        if (!finalChunk && hasOpenMarkdownBlock(pending.toString())) return;

        String chunk = pending.toString();
        pending.setLength(0);
        if (renderDisplay && !chunk.isBlank()) {
            renderToScroll(chunk);
        }
    }

    private boolean hasOpenMarkdownBlock(String text) {
        return hasOpenCodeFence(text)
                || hasUnclosedTag(text, "[tool:", "[/tool]")
                || hasUnclosedTag(text, "[tool-result]", "[/tool-result]")
                || hasUnclosedTag(text, "<thinking>", "</thinking>");
    }

    private boolean hasOpenCodeFence(String text) {
        boolean open = false;
        String[] lines = text.split("\n", -1);
        for (String line : lines) {
            if (line.stripLeading().startsWith("```")) {
                open = !open;
            }
        }
        return open;
    }

    private boolean hasUnclosedTag(String text, String openTag, String closeTag) {
        int open = text.lastIndexOf(openTag);
        if (open < 0) return false;
        int close = text.lastIndexOf(closeTag);
        return close < open;
    }

    /**
     * Unified scrape→render path for all subprocess output.
     * <p>
     * Scrape: feed raw PTY output into VirtualTerminal (proper VT100 emulation
     * that maintains a 2D screen buffer and extracts only changed content rows,
     * filtering TUI chrome — status bars, spinners, keybinding hints).
     * <p>
     * Render: pass extracted text through the markdown renderer.
     */
    private synchronized void processInteractiveOutputChunk(String rawChunk,
                                                           StringBuilder fullText,
                                                           TerminalRenderer.SpinnerHandle spinner,
                                                           AtomicBoolean spinnerStopped) {
        // Ensure VirtualTerminal exists
        if (virtualTerminal == null) {
            int vtRows = terminal != null ? terminal.getHeight() : 24;
            int vtCols = terminal != null ? terminal.getWidth() : 120;
            if (vtRows <= 0) vtRows = 24;
            if (vtCols <= 0) vtCols = 120;
            virtualTerminal = new ai.kompile.cli.main.chat.tui.VirtualTerminal(vtRows, vtCols);
            vtCreatedAt = System.currentTimeMillis();
            // Chrome filtering is now content-based (VirtualTerminal.isChromeRow),
            // not positional — no need to configure per-agent row counts.
        }

        // Log raw subprocess output for debugging
        java.io.Writer logW = subprocessLogWriter;
        if (logW != null) {
            try { logW.write(rawChunk); logW.flush(); } catch (IOException ignored) {}
        }

        // Raw bytes arriving = subprocess is active.
        // Update both the inline spinner (user-visible) and the status bar entry.
        if (spinner != null) {
            spinner.setPhase("processing");
        }
        if (tui != null && tuiSubagentId != null) {
            tui.getStatusBar().updateSubagentStatus(tuiSubagentId, "processing");
        }

        // Scrape: feed raw TUI output into virtual terminal
        virtualTerminal.feed(rawChunk);

        // Send back safe VT responses (DA1, DSR) that Claude Code needs to
        // proceed, but NOT Kitty keyboard protocol responses which get
        // misinterpreted as keystrokes and trigger nano.
        String vtResp = virtualTerminal.safeTerminalResponsesFor(rawChunk);
        if (vtResp != null && !vtResp.isEmpty() && tuiProcess != null) {
            try {
                tuiProcess.getOutputStream().write(vtResp.getBytes());
                tuiProcess.getOutputStream().flush();
                if (logW != null) {
                    logW.write("[VT response] " + vtResp.replace("\033", "ESC") + "\n");
                    logW.flush();
                }
            } catch (IOException ignored) {}
        }

        String newText = virtualTerminal.getNewText();
        if (logW != null) {
            try {
                logW.write("[VT extracted] " + (newText == null ? "<null>" : newText.replace("\n", "\\n")) + "\n");
                logW.flush();
            } catch (IOException ignored) {}
        }
        if (newText == null || newText.isBlank()) return;

        // Snapshot the last sent message for echo filtering
        String echoRef = lastSentMessage;

        // Render each extracted line through the markdown renderer
        for (String line : newText.split("\n")) {
            String stripped = line.trim();
            if (stripped.isEmpty() || stripped.length() < 3) continue;

            // Filter exact PTY echo of user input — only suppress lines that
            // are the user's message verbatim (possibly with a prompt prefix).
            if (echoRef != null && !echoRef.isEmpty()) {
                String echoTrimmed = echoRef.trim();
                if (stripped.equals(echoTrimmed) || stripped.endsWith(echoTrimmed)) {
                    lastSentMessage = null; // consume — only filter once
                    continue;
                }
            }

            lastOutputTime.set(System.currentTimeMillis());

            // No output buffer means TUI init phase — discard display but keep VT state
            if (fullText == null) continue;

            if (spinner != null && spinnerStopped != null && spinnerStopped.compareAndSet(false, true)) {
                spinner.stop();
                safePrintln();
                // First real content — subprocess is now responding
                if (tui != null && tuiSubagentId != null) {
                    tui.getStatusBar().updateSubagentStatus(tuiSubagentId, "responding");
                }
            }
            trackAssistantLog(stripped);
            renderToScroll(stripped);
            fullText.append(stripped).append("\n");
        }
    }

    /**
     * Routes persistent TUI subprocess output through the unified scrape→render pipeline.
     */
    private synchronized void processTuiOutputChunk(String rawChunk) {
        processInteractiveOutputChunk(rawChunk, tuiFullText, tuiSpinner, tuiSpinnerStopped);
    }

    /**
     * Process a single line of subprocess output.
     * <p>
     * For structured agents (Claude, OpenCode, Gemini, Qwen, Codex): parse JSON
     * events, buffer text chunks, and render markdown on turn completion.
     * Tool calls render immediately. Text is buffered and rendered as formatted
     * markdown when a {@code TurnComplete} event arrives, matching the native
     * agent harness UX.
     * <p>
     * For unstructured agents: strip ANSI sequences and print clean text.
     */
    private synchronized void processOutputLine(String line, StringBuilder fullText,
                                    List<String> toolCalls, ChatSessionMetrics metrics,
                                    TerminalRenderer.SpinnerHandle spinner,
                                    AtomicBoolean spinnerStopped,
                                    StringBuilder pendingText) {
        lastOutputTime.set(System.currentTimeMillis());
        String agentLower = agent.toLowerCase();

        // Filter out CLI deprecation warnings and version-change noise from agent binaries
        // (e.g., codex's "--full-auto is deprecated", node version warnings)
        String trimmedLine = line.trim();
        if (trimmedLine.startsWith("warning:") || trimmedLine.startsWith("Warning:")
                || trimmedLine.startsWith("WARN:") || trimmedLine.startsWith("DeprecationWarning")) {
            return;
        }

        // Route to the correct parser — may return multiple events from one line
        // (e.g., text + AskUserQuestion in the same assistant message)
        List<PassthroughStreamParser.PassthroughEvent> events = parseAgentLineMulti(agentLower, line);

        if (events.isEmpty()) {
            // No structured parser matched — fall back to ANSI-stripped markdown-rendered text
            if (!isStructuredAgent(agentLower)) {
                String cleaned = stripAnsi(line).trim();
                if (!cleaned.isEmpty()) {
                    if (spinnerStopped.compareAndSet(false, true)) {
                        spinner.stop();
                        safePrintln();
                    }
                    trackAssistantLog(cleaned);
                    renderToScroll(cleaned);
                    fullText.append(cleaned).append("\n");
                }
            }
            return;
        }

        for (PassthroughStreamParser.PassthroughEvent event : events) {
            processEvent(event, fullText, toolCalls, metrics, spinner, spinnerStopped, pendingText);
        }
    }

    /** Process a single parsed event from agent output. */
    private void processEvent(PassthroughStreamParser.PassthroughEvent event,
                              StringBuilder fullText, List<String> toolCalls,
                              ChatSessionMetrics metrics,
                              TerminalRenderer.SpinnerHandle spinner,
                              AtomicBoolean spinnerStopped,
                              StringBuilder pendingText) {
        // Capture session ID for multi-turn continuation
        if (event instanceof PassthroughStreamParser.SessionInit si) {
            if (si.sessionId() != null) {
                agentSessionId = si.sessionId();
            }
            return;
        }

        // Interactive events — flush text first so the dialog is visible, then queue
        if (event instanceof PassthroughStreamParser.InteractiveQuestion
                || event instanceof PassthroughStreamParser.InteractiveApproval) {
            flushPendingText(pendingText, spinner, spinnerStopped);
            interactiveQueue.offer(event);
            return;
        }

        if (event instanceof PassthroughStreamParser.ThinkingChunk) {
            // Model is reasoning — switch spinner to "Thinking..." phase
            spinner.setPhase("Thinking");
            renderer.setTerminalTitle("Thinking... (" + agent + ")");
            return;
        }

        if (event instanceof PassthroughStreamParser.TextChunk tc) {
            String text = tc.text();
            if (text == null || text.isEmpty()) return;
            if (spinnerStopped.compareAndSet(false, true)) {
                spinner.stop();
                safePrintln();
            }
            fullText.append(text);
            trackAssistantLog(text);
            pendingText.append(text);
        } else if (event instanceof PassthroughStreamParser.ToolUse tu) {
            flushPendingText(pendingText, spinner, spinnerStopped);
            if (spinnerStopped.compareAndSet(false, true)) {
                spinner.stop();
                safePrintln();
            }
            trackToolActivityStart(tu);
            safePrintln(renderer.renderToolCallStart(tu.name(), tu.input()));
            toolCalls.add(tu.name());
            metrics.recordToolCall(tu.name(), false, 0);
        } else if (event instanceof PassthroughStreamParser.ToolOutput toolOutput) {
            // Tool is still running — just display the output
            flushPendingText(pendingText, spinner, spinnerStopped);
            if (spinnerStopped.compareAndSet(false, true)) {
                spinner.stop();
                safePrintln();
            }
            String output = toolOutput.output();
            if (output != null && !output.isBlank()) {
                trackToolActivityLog("", output);
                safePrintln(renderer.dim("  " + output));
            }
        } else if (event instanceof PassthroughStreamParser.ToolComplete toolComplete) {
            flushPendingText(pendingText, spinner, spinnerStopped);
            if (spinnerStopped.compareAndSet(false, true)) {
                spinner.stop();
                safePrintln();
            }
            String output = toolComplete.output();
            if (output != null && !output.isBlank()) {
                trackToolActivityComplete(toolComplete.name(), output, toolComplete.error());
                safePrintln(renderer.dim("  " + output));
            }
            String status = toolComplete.exitCode() >= 0
                    ? "exit " + toolComplete.exitCode()
                    : "completed";
            safePrintln(renderer.dim("  [" + TerminalRenderer.prettifyToolName(toolComplete.name()) + " " + status + "]"));
            if (output == null || output.isBlank()) {
                trackToolActivityComplete(toolComplete.name(), status, toolComplete.error());
            }
        } else if (event instanceof PassthroughStreamParser.TokenUsage tu) {
            flushPendingText(pendingText, spinner, spinnerStopped);
            StringBuilder stats = new StringBuilder();
            stats.append(tu.inputTokens()).append(" in / ").append(tu.outputTokens()).append(" out");
            if (tu.cacheReadTokens() > 0 || tu.cacheCreationTokens() > 0) {
                stats.append(" · cache ").append(tu.cacheReadTokens()).append("r/")
                     .append(tu.cacheCreationTokens()).append("w");
            }
            safePrintln();
            safePrintln(renderer.dim("  [" + stats + "]"));
        } else if (event instanceof PassthroughStreamParser.TurnComplete tc) {
            flushPendingText(pendingText, spinner, spinnerStopped);

            StringBuilder stats = new StringBuilder();
            if (tc.durationMs() > 0) stats.append(FormatUtils.formatDuration(tc.durationMs()));
            if (tc.costUsd() > 0) {
                if (stats.length() > 0) stats.append(" · ");
                stats.append(String.format("$%.4f", tc.costUsd()));
            }
            if (tc.numTurns() > 0) {
                if (stats.length() > 0) stats.append(" · ");
                stats.append(tc.numTurns()).append(" turn(s)");
            }
            if (stats.length() > 0) {
                safePrintln();
                safePrintln(renderer.dim("  [" + stats + "]"));
            }
        }
    }

    /**
     * Flush buffered text as rendered markdown. Called on TurnComplete,
     * before tool calls, or when the process exits.
     */
    private void flushPendingText(StringBuilder pendingText,
                                   TerminalRenderer.SpinnerHandle spinner,
                                   AtomicBoolean spinnerStopped) {
        if (pendingText.length() == 0) return;

        if (spinnerStopped.compareAndSet(false, true)) {
            spinner.stop();
            safePrintln();
        }

        renderToScroll(pendingText.toString());
        pendingText.setLength(0);
    }

    // ── Interactive prompt handling ─────────────────────────────────────────

    /**
     * Handle an interactive event from the agent (question or approval request).
     * Presents the prompt to the user, reads their response via JLine, and
     * writes the response back to the agent's stdin.
     */
    private void handleInteractiveEvent(PassthroughStreamParser.PassthroughEvent event,
                                         TerminalRenderer.SpinnerHandle spinner,
                                         AtomicBoolean spinnerStopped) {
        if (spinnerStopped.compareAndSet(false, true)) {
            spinner.stop();
            safePrintln("");
        }

        if (event instanceof PassthroughStreamParser.InteractiveQuestion iq) {
            handleInteractiveQuestion(iq);
        } else if (event instanceof PassthroughStreamParser.InteractiveApproval ia) {
            handleInteractiveApproval(ia);
        }
    }

    /**
     * Present an interactive question with numbered choices and read the user's selection.
     * Writes the selected answer back to the agent's stdin.
     */
    private void handleInteractiveQuestion(PassthroughStreamParser.InteractiveQuestion iq) {
        safePrintln("");
        List<PassthroughStreamParser.QuestionOption> options = iq.options();

        // Build the question panel
        StringBuilder body = new StringBuilder();
        if (iq.header() != null && !iq.header().isEmpty()) {
            body.append(BOLD).append(iq.header()).append(RESET).append("\n\n");
        }
        body.append(iq.question());
        if (!options.isEmpty()) {
            body.append("\n");
            for (int i = 0; i < options.size(); i++) {
                PassthroughStreamParser.QuestionOption opt = options.get(i);
                body.append("\n  ").append(CYAN).append(i + 1).append(".").append(RESET).append(" ")
                        .append(BOLD).append(opt.label()).append(RESET);
                if (opt.description() != null && !opt.description().isEmpty()) {
                    body.append(DIM).append(" — ").append(opt.description()).append(RESET);
                }
            }
            if (iq.freeformAllowed()) {
                body.append("\n\n").append(DIM).append("  Or type a custom answer").append(RESET);
            }
        }

        for (String line : ascii.panel("Agent Question", body.toString()).split("\n")) {
            safePrintln(line);
        }

        // Read user's choice
        String response = readUserResponse(options.isEmpty()
                ? "  answer> "
                : "  choice [1-" + options.size() + "]> ");

        if (response == null || response.isBlank()) return;

        // Resolve the answer: if numeric and within range, use the option label
        String answer = response.trim();
        try {
            int idx = Integer.parseInt(answer);
            if (idx >= 1 && idx <= options.size()) {
                answer = options.get(idx - 1).label();
            }
        } catch (NumberFormatException ignored) {
            // Free-form text — use as-is
        }

        // Write response to agent stdin based on agent type
        writeToAgentStdin(answer, iq.callId(), iq.turnId(), iq.questionId());
        safePrintln(renderer.dim("  → Sent: " + answer));
        safePrintln("");
    }

    /**
     * Present an approval request for a command and read the user's decision.
     */
    private void handleInteractiveApproval(PassthroughStreamParser.InteractiveApproval ia) {
        safePrintln("");

        StringBuilder body = new StringBuilder();
        body.append("The agent wants to execute:\n\n");
        body.append("  ").append(BOLD).append(ia.command()).append(RESET).append("\n");
        if (ia.cwd() != null && !ia.cwd().isEmpty()) {
            body.append("  ").append(DIM).append("in ").append(ia.cwd()).append(RESET).append("\n");
        }
        if (ia.reason() != null && !ia.reason().isEmpty()) {
            body.append("\n  Reason: ").append(ia.reason()).append("\n");
        }

        List<String> decisions = ia.decisions();
        body.append("\n");
        for (int i = 0; i < decisions.size(); i++) {
            String d = decisions.get(i);
            String color = d.equalsIgnoreCase("approve") ? GREEN : YELLOW;
            body.append("  ").append(CYAN).append(i + 1).append(".").append(RESET).append(" ")
                    .append(color).append(d).append(RESET).append("\n");
        }

        for (String line : ascii.panel("Approval Required", body.toString()).split("\n")) {
            safePrintln(line);
        }

        String response = readUserResponse("  decision [1-" + decisions.size() + "]> ");
        if (response == null || response.isBlank()) return;

        // Resolve decision
        String decision = response.trim();
        try {
            int idx = Integer.parseInt(decision);
            if (idx >= 1 && idx <= decisions.size()) {
                decision = decisions.get(idx - 1);
            }
        } catch (NumberFormatException ignored) {
            // Might be "approve" / "deny" typed directly
        }

        writeApprovalToAgentStdin(decision, ia.callId(), ia.turnId());
        String color = decision.equalsIgnoreCase("approve") ? GREEN : YELLOW;
        safePrintln(color + "  → " + decision + RESET);
        safePrintln("");
    }

    /**
     * Read a line of user input via JLine. Returns null on interrupt or EOF.
     */
    private String readUserResponse(String prompt) {
        try {
            return lineReader.readLine(prompt);
        } catch (UserInterruptException | EndOfFileException e) {
            return null;
        }
    }

    /**
     * Write a question answer to the agent's stdin.
     * Format depends on agent type (Codex uses JSON protocol, others use plain text).
     */
    private void writeToAgentStdin(String answer, String callId, String turnId, String questionId) {
        String agentLower = agent.toLowerCase();

        if (agentLower.contains("codex") && callId != null && !callId.isEmpty()) {
            // Codex bidirectional protocol: send UserInputAnswer JSON
            String json;
            if (questionId != null && !questionId.isEmpty()) {
                json = String.format(
                        "{\"op\":{\"UserInputAnswer\":{\"user_input_answer\":\"%s\","
                                + "\"response\":{\"answers\":{\"%s\":{\"answers\":[\"%s\"]}}}}}}",
                        escapeJson(callId), escapeJson(questionId), escapeJson(answer));
            } else {
                json = String.format(
                        "{\"op\":{\"UserInputAnswer\":{\"user_input_answer\":\"%s\","
                                + "\"response\":{\"answers\":{\"q0\":{\"answers\":[\"%s\"]}}}}}}",
                        escapeJson(callId), escapeJson(answer));
            }
            writePlainToAgentStdin(json);
        } else {
            // Plain text — OpenCode, Claude, Gemini, Qwen read from stdin directly
            writePlainToAgentStdin(answer);
        }
    }

    /**
     * Write an approval decision to the agent's stdin.
     */
    private void writeApprovalToAgentStdin(String decision, String callId, String turnId) {
        String agentLower = agent.toLowerCase();

        if (agentLower.contains("codex") && callId != null && !callId.isEmpty()) {
            // Codex bidirectional protocol: send ExecApproval JSON
            String json = String.format(
                    "{\"op\":{\"ExecApproval\":{\"id\":\"%s\",\"turn_id\":\"%s\",\"decision\":\"%s\"}}}",
                    escapeJson(callId), escapeJson(turnId != null ? turnId : ""),
                    escapeJson(decision.toLowerCase()));
            writePlainToAgentStdin(json);
        } else {
            // Plain text approval
            writePlainToAgentStdin(decision.toLowerCase());
        }
    }

    /**
     * Write raw text followed by a newline to the agent's stdin and flush.
     */
    private void writePlainToAgentStdin(String text) {
        OutputStream os = agentStdin;
        if (os == null) return;
        try {
            synchronized (os) {
                os.write((text + "\n").getBytes(StandardCharsets.UTF_8));
                os.flush();
            }
        } catch (IOException e) {
            // Agent may have closed stdin — this is expected after exit
        }
    }

    /** Write raw terminal bytes to the agent without appending a newline. */
    private boolean sendRawToAgentStdin(byte[] data) {
        if (data == null || data.length == 0) return false;
        OutputStream os = agentStdin;
        if (os == null) return false;
        try {
            synchronized (os) {
                os.write(data);
                os.flush();
            }
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /** Minimal JSON string escaping for protocol values. */
    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }

    // ── Command building ───────────────────────────────────────────────────

    /**
     * Build the provider-agnostic interactive command for managed passthrough.
     * User prompts are written to stdin after the child process starts; this path
     * must not add provider prompt-mode commands or provider-specific
     * continuation, fork, or session flags.
     */
    private List<String> buildCommand(String binary, String message) {
        return new ArrayList<>(List.of(binary));
    }

    // ── Slash commands ─────────────────────────────────────────────────────

    private String handleSlashCommand(String input, LineReader lineReader, ChatHistory history, ChatSessionMetrics metrics) {
        String[] parts = input.split("\\s+", 2);
        String cmd = parts[0].toLowerCase();
        String rest = parts.length > 1 ? parts[1] : "";

        switch (cmd) {
            case "/quit", "/exit" -> { return "quit"; }
            case "/help" -> printHelp();
            case "/agent" -> switchAgent(rest.isBlank() ? null : rest.trim(), lineReader);
            case "/status" -> printStatus(metrics);
            case "/clear" -> initScrollLayout();
            case "/mode" -> {
                safePrintln(renderer.dim("  Current mode: emulated passthrough (" + agent + ")"));
                safePrintln(renderer.dim("  Available: emulated, passthrough, standard"));
            }
            case "/rules" -> {
                if (enforcerPolicy != null) {
                    for (String line : ascii.panel("Enforcer Rules", enforcerPolicy.getRules()).split("\n")) {
                        safePrintln(line);
                    }
                } else {
                    safePrintln(renderer.dim("  No enforcer rules active."));
                }
            }
            case "/queue" -> {
                if (rest.isBlank()) listQueuedMessages();
                else enqueueMessage(rest.trim(), metrics);
            }
            case "/queues" -> listQueuedMessages();
            case "/queue-send" -> sendQueuedMessage(rest.trim(), history, metrics);
            case "/queue-send-all" -> drainQueuedMessages(history, metrics, true);
            case "/queue-remove" -> removeQueuedMessage(rest.trim());
            case "/queue-clear" -> clearQueuedMessages();
            case "/queue-status" -> showQueueStatus();
            case "/auto-dequeue" -> toggleAutoDequeue();
            case "/activity", "/processes" -> handleActivitySlash(rest.trim());
            case "/process-output" -> handleActivitySlash("logs " + rest.trim());
            case "/process-kill" -> handleActivitySlash("kill " + rest.trim());
            case "/process-status" -> handleActivitySlash(rest.isBlank() ? "" : rest.trim());
            case "/enforcer" -> handleEnforcerSlash(rest.trim());
            default -> {
                // Forward unrecognized slash commands to the underlying agent
                String agentBinary = AgentCommandForwarder.resolveAgentBinary(agent);
                if (agentBinary == null) {
                    safePrintln(renderer.dim("  Unknown command: " + cmd + " (agent '" + agent + "' not found on PATH)"));
                } else {
                    AgentCommandForwarder forwarder = new AgentCommandForwarder(
                            Path.of(workingDir).toAbsolutePath().normalize().toString());
                    String slashCmd = rest.isEmpty() ? cmd : cmd + " " + rest;
                    AgentCommandForwarder.AgentCommand agentCmd = forwarder.mapSlashCommand(slashCmd, agentBinary, agent);
                    if (agentCmd != null) {
                        safePrintln(renderer.dim("  → " + agentCmd.label()));
                        forwarder.executeWithRealtimeOutput(agentCmd);
                    } else {
                        safePrintln(renderer.dim("  Command " + cmd + " not supported for " + agent));
                    }
                }
            }
        }
        return null;
    }

    private void enqueueMessage(String content, ChatSessionMetrics metrics) {
        if (content == null || content.isBlank()) {
            safePrintln("  Usage: /queue <message>");
            return;
        }
        String trimmed = content.trim();
        MessageQueue.QueuedMessage msg = messageQueue.enqueue(trimmed);
        recordInputHistory(trimmed);
        metrics.recordMessageQueued();
        safePrintln(renderer.green("  Queued [") + msg.getId() + renderer.green("] ")
                + truncateForQueue(trimmed));
        updateStatusLine(currentStatus);
    }

    private void listQueuedMessages() {
        if (messageQueue == null || messageQueue.isEmpty()) {
            safePrintln(renderer.dim("  Queue is empty."));
            return;
        }
        safePrintln("");
        safePrintln(renderer.bold(renderer.cyan("  Queued Messages")));
        int i = 1;
        for (MessageQueue.QueuedMessage msg : messageQueue.getAll()) {
            safePrintln("  " + (i++) + ". [" + renderer.cyan(msg.getId()) + "] "
                    + truncateForQueue(msg.getContent()));
        }
        safePrintln("");
    }

    private void sendQueuedMessage(String id, ChatHistory history, ChatSessionMetrics metrics) {
        if (agentBusy) {
            safePrintln(renderer.yellow("  Agent is busy — queued messages will be sent when it finishes."));
            return;
        }
        MessageQueue.QueuedMessage msg;
        if (id == null || id.isBlank()) {
            msg = messageQueue.dequeue();
        } else {
            msg = messageQueue.get(id);
            if (msg != null) {
                messageQueue.remove(id);
            }
        }
        if (msg == null) {
            safePrintln(renderer.yellow("  Queue is empty or message was not found."));
            updateStatusLine(currentStatus);
            return;
        }
        metrics.recordMessageAutoDequeued();
        safePrintln(renderer.dim("  Sending next draft"));
        safePrintln(BOLD + "  > " + RESET + msg.getContent());
        boolean backgrounded = dispatchToAgent(msg.getContent(), history, metrics);
        drainQueuedMessages(history, metrics, false, backgrounded);
    }

    private void drainQueuedMessages(ChatHistory history, ChatSessionMetrics metrics, boolean force) {
        if (force && agentBusy) {
            safePrintln(renderer.yellow("  Agent is busy — queued messages will be sent when it finishes."));
            return;
        }
        drainQueuedMessages(history, metrics, force, false);
    }

    private void drainQueuedMessages(ChatHistory history,
                                     ChatSessionMetrics metrics,
                                     boolean force,
                                     boolean previousTurnBackgrounded) {
        if (messageQueue == null || messageQueue.isEmpty()) {
            updateStatusLine(currentStatus);
            return;
        }
        if (previousTurnBackgrounded && !force && !canDispatchQueuedMessageAfterBackground()) {
            busyPrompt = "  Current response is backgrounded · pending draft stays queued for this agent";
            drawFixedInputBox();
            updateStatusLine("current response backgrounded");
            return;
        }
        if (!force && !autoDequeueEnabled) {
            updateStatusLine(currentStatus);
            return;
        }
        int total = messageQueue.size();
        int sent = 0;
        int maxToSend = previousTurnBackgrounded && !force ? 1 : Integer.MAX_VALUE;
        while (!messageQueue.isEmpty()) {
            if (sent >= maxToSend) break;
            MessageQueue.QueuedMessage msg = messageQueue.dequeue();
            if (msg == null) break;
            sent++;
            metrics.recordMessageAutoDequeued();
            safePrintln(renderer.green("  Sending draft " + sent + "/" + total));
            safePrintln(BOLD + "  > " + RESET + msg.getContent());
            boolean backgrounded = dispatchToAgent(msg.getContent(), history, metrics);
            if (backgrounded && !canDispatchQueuedMessageAfterBackground()) {
                break;
            }
        }
        updateStatusLine(currentStatus);
    }

    private void removeQueuedMessage(String id) {
        if (id == null || id.isBlank()) {
            safePrintln("  Usage: /queue-remove <id>");
            return;
        }
        if (messageQueue.remove(id)) {
            safePrintln(renderer.green("  Removed queued message [") + id + renderer.green("]"));
        } else {
            safePrintln(renderer.yellow("  Message not found: " + id));
        }
        updateStatusLine(currentStatus);
    }

    private void clearQueuedMessages() {
        messageQueue.clear();
        safePrintln(renderer.green("  Queue cleared."));
        updateStatusLine(currentStatus);
    }

    private void showQueueStatus() {
        safePrintln("  " + messageQueue.getStatus());
    }

    private void toggleAutoDequeue() {
        autoDequeueEnabled = !autoDequeueEnabled;
        safePrintln(autoDequeueEnabled
                ? renderer.green("  Auto-dequeue enabled.")
                : renderer.yellow("  Auto-dequeue disabled."));
        updateStatusLine(currentStatus);
    }

    private static String truncateForQueue(String text) {
        if (text == null) return "";
        return text.length() > 80 ? text.substring(0, 77) + "..." : text;
    }

    private void handleEnforcerSlash(String args) {
        Path wd = Path.of(workingDir).toAbsolutePath().normalize();
        String subCmd = args.isBlank() ? "status" : args.split("\\s+")[0].toLowerCase();
        switch (subCmd) {
            case "init", "setup" -> {
                ai.kompile.cli.main.chat.enforcer.EnforcerConfig config =
                        ai.kompile.cli.main.chat.enforcer.EnforcerSetupWizard.run(wd);
                if (config != null) {
                    safePrintln(renderer.green("  Enforcer configured."));
                } else {
                    safePrintln("  Setup cancelled.");
                }
            }
            case "show", "status" -> {
                ai.kompile.cli.main.chat.enforcer.EnforcerConfig config =
                        ai.kompile.cli.main.chat.enforcer.EnforcerConfig.load(wd);
                if (config == null) {
                    safePrintln(renderer.dim("  No enforcer config. Run /enforcer init to configure."));
                    return;
                }
                safePrintln("");
                safePrintln("  Agent:         " + config.getAgent());
                safePrintln("  Mode:          " + (config.isKeywordMode() ? "keyword" : "LLM judge"));
                safePrintln("  Max retries:   " + config.getMaxCorrections());
                safePrintln("  Diff archive:  " + (config.isArchiveDiffs() ? "enabled" : "disabled"));
                if (!config.getBannedTools().isEmpty()) {
                    safePrintln("  Banned tools:  " + String.join(", ", config.getBannedTools()));
                }
                safePrintln("");
            }
            case "delete" -> {
                try {
                    ai.kompile.cli.main.chat.enforcer.EnforcerConfig.delete(wd);
                    safePrintln(renderer.dim("  Enforcer config deleted."));
                } catch (Exception e) {
                    safePrintln(renderer.yellow("  Failed: " + e.getMessage()));
                }
            }
            default -> safePrintln(renderer.dim("  Usage: /enforcer [init|show|delete]"));
        }
    }

    private void printHelp() {
        String body = """
                Chat commands:
                  (type a message)   Send to %s as one-shot subprocess

                Slash commands:
                  /agent [name]      Switch to a different agent
                  /queue <message>   Queue a message for the next turn
                  /queues            List queued messages
                  /queue-send [id]   Send next queued message, or a specific id
                  /queue-send-all    Send every queued message now
                  /queue-remove <id> Remove a queued message
                  /queue-clear       Clear queued messages
                  /auto-dequeue      Toggle sending queued messages after each turn
                  /activity          Manage background processes, subagents, and logs
                  /status            Show session metrics
                  /clear             Clear the screen
                  /mode              Show current mode
                  /help              Show this help
                  /quit              Exit

                Keyboard shortcuts:
                  Ctrl+C             Cancel in-progress agent call
                  Ctrl+B             Show background-task prompt while busy
                  Escape             Pass through to the underlying agent while busy
                  Type + Enter       Queue a message while the agent is busy
                  Up arrow           Edit the next queued message while busy

                Supported agents:
                  claude, codex, gemini, qwen, opencode
                  (or any binary on PATH)""".formatted(agent);

        safePrintln("");
        for (String line : ascii.panel("Emulated Passthrough Help", body).split("\n")) {
            safePrintln(line);
        }
        safePrintln("");
    }

    private void switchAgent(String newAgent, LineReader lineReader) {
        if (newAgent == null) {
            safePrintln("  Available: claude, codex, qwen, opencode, gemini (or any binary on PATH)");
            try {
                positionAtPrompt();
                String agentInput = lineReader.readLine(CYAN + "  agent> " + RESET);
                if (agentInput != null && !agentInput.trim().isEmpty()) {
                    newAgent = agentInput.trim();
                } else {
                    return;
                }
            } catch (Exception e) {
                return;
            }
        }

        String binary = resolveAgent(newAgent);
        if (binary == null) {
            safePrintln(renderer.yellow("  Agent '" + newAgent + "' not found on PATH."));
            safePrintln(renderer.dim("  Supported agents: " + String.join(", ",
                    ai.kompile.cli.main.chat.config.ChatConfig.getPassthroughAgentOrder())));
            return;
        }

        agent = newAgent;
        firstMessageSent = false;
        agentSessionId = null;
        safePrintln(renderer.green("  Switched to " + agent));
        safePrintln(renderer.dim("  Conversation context reset (new agent session)."));
        renderer.setTerminalTitle("kompile [" + agent + "]");
    }

    private void printStatus(ChatSessionMetrics metrics) {
        StringBuilder body = new StringBuilder();
        String modeDesc = enforcerEvaluator != null ? "enforced passthrough" : "emulated passthrough";
        body.append("Agent:     ").append(agent).append(" (").append(modeDesc).append(")\n");
        if (enforcerEvaluator != null) {
            body.append("Judge:     ").append(enforcerEvaluator.describe()).append("\n");
            body.append("Retries:   ").append(enforcerPolicy != null ? enforcerPolicy.getMaxCorrections() : 3).append("\n");
        }
        body.append("Messages:  ").append(metrics.getUserTurns()).append(" sent, ")
                .append(metrics.getAssistantTurns()).append(" received\n");
        if (metrics.getTotalToolCalls() > 0) {
            body.append("Tools:     ").append(metrics.getTotalToolCalls()).append(" calls\n");
        }
        body.append("Duration:  ").append(metrics.formatDuration(metrics.getSessionDuration())).append("\n");
        body.append("Context:   ").append(firstMessageSent ? "active managed session" : "new managed session");
        safePrintln("");
        for (String line : ascii.panel("Session Status", body.toString()).split("\n")) {
            safePrintln(line);
        }
        safePrintln("");
    }

    // ── Welcome panel ──────────────────────────────────────────────────────

    private void printWelcomePanel(String agentBinary) {
        String agentDesc = switch (agent.toLowerCase()) {
            case "claude" -> "Claude Code (Anthropic)";
            case "codex" -> "OpenAI Codex";
            case "gemini" -> "Gemini CLI (Google)";
            case "qwen" -> "Qwen Code (Alibaba)";
            case "opencode" -> "OpenCode";
            default -> agent;
        };

        StringBuilder body = new StringBuilder();
        body.append("Agent:   ").append(agentDesc).append("\n");
        body.append("Binary:  ").append(agentBinary).append("\n");
        if (enforcerEvaluator != null) {
            body.append("Judge:   ").append(enforcerEvaluator.describe()).append("\n");
            body.append("Retries: ").append(enforcerPolicy != null ? enforcerPolicy.getMaxCorrections() : 3).append("\n");
            body.append("Mode:    Managed Passthrough with real-time enforcement\n");
        } else {
            body.append("Mode:    Emulated Passthrough\n");
        }
        body.append("\n");
        body.append("Each message is sent to ").append(agent)
                .append(" through a Kompile-managed interactive subprocess.\n");
        body.append("Agent output is cleaned and rendered through kompile's UI.\n");
        if (enforcerEvaluator != null) {
            body.append("The judge monitors output and can interrupt on violations.\n");
        }
        body.append("Slash commands (/help, /quit, /agent, /status) remain active.\n");
        body.append("\n");
        body.append(DIM).append("PageUp/PageDown scroll · Ctrl+Home/End jump · Ctrl+C cancel · /quit exit").append(RESET);

        String title = enforcerEvaluator != null
                ? "Kompile Enforced Passthrough" : "Kompile Emulated Passthrough";
        safePrintln("");
        String panelText = ascii.panel(title, body.toString());
        for (String panelLine : panelText.split("\n")) {
            safePrintln(panelLine);
        }
        safePrintln("");

        renderer.setTerminalTitle("kompile [" + agent + "]");
    }

    // ── Session summary ────────────────────────────────────────────────────

    private void printSessionSummary(ChatSessionMetrics metrics, ChatHistory history,
                                      String sessionId, Instant startTime) {
        Duration duration = Duration.between(startTime, Instant.now());

        StringBuilder body = new StringBuilder();
        body.append(BOLD).append("Session").append(RESET).append("\n");
        body.append("  ID:        ").append(sessionId).append("\n");
        body.append("  Duration:  ").append(metrics.formatDuration(duration)).append("\n");
        body.append("  Agent:     ").append(agent).append(" (emulated passthrough)").append("\n");

        if (metrics.getTotalTurns() > 0) {
            body.append("\n").append(BOLD).append("Conversation").append(RESET).append("\n");
            body.append("  Turns:     ").append(metrics.getUserTurns()).append(" user, ")
                    .append(metrics.getAssistantTurns()).append(" assistant\n");
        }

        if (metrics.getTotalToolCalls() > 0) {
            body.append("\n").append(BOLD).append("Tools").append(RESET).append("\n");
            body.append("  Total:     ").append(metrics.getTotalToolCalls()).append(" calls\n");
            List<Map.Entry<String, Integer>> topTools = metrics.getTopTools(8);
            for (Map.Entry<String, Integer> entry : topTools) {
                body.append("  ").append(String.format("%-12s", entry.getKey()))
                        .append(" ").append(entry.getValue()).append("\n");
            }
        }

        body.append("\n").append(BOLD).append("Files").append(RESET).append("\n");
        body.append("  Transcript: ").append(history.getTranscriptFile()).append("\n");
        body.append("  Metrics:    ").append(
                history.getTranscriptFile().resolveSibling(sessionId + ".metrics.json")).append("\n");

        System.out.println();
        System.out.println(ascii.panel("Session Summary", body.toString()));
        System.out.println();

        if (metrics.getTotalTurns() > 0) {
            history.logSystem("Session ended — " + metrics.formatDuration(duration) +
                    ", " + metrics.getTotalTurns() + " turns" +
                    (metrics.getTotalToolCalls() > 0 ? ", " + metrics.getTotalToolCalls() + " tool calls" : ""));
        }
    }

    // ── MCP tool injection ─────────────────────────────────────────────────

    private void injectMcpTools() {
        if (!injectTools) return;
        try {
            String sseUrl = mcpUrlResolver.resolveMcpUrl(kompileUrl, mcpPort);
            injectedSettingsFile = ai.kompile.cli.main.chat.mcp.McpToolInjection.injectTools(
                    Path.of(workingDir), agent, sseUrl);
            if (injectedSettingsFile != null) {
                String mode = (sseUrl != null && !sseUrl.isBlank()) ? "sse" : "stdio";
                System.out.println(GREEN + "  Kompile tools injected (" + mode + ")" + RESET
                        + DIM + " (" + injectedSettingsFile + ")" + RESET);
            }
        } catch (IOException e) {
            System.err.println(YELLOW + "Warning: Could not inject MCP tools: " + e.getMessage() + RESET);
        }
    }

    private void removeMcpTools() {
        ai.kompile.cli.main.chat.mcp.McpToolInjection.removeTools(injectedSettingsFile);
    }

    // ── Key bindings ───────────────────────────────────────────────────────

    private void bindCancelKey(LineReader lineReader) {
        if (lineReader instanceof LineReaderImpl impl) {
            impl.getKeyMaps().get(LineReader.EMACS).bind(
                    new org.jline.reader.Reference("cancel-emulated"),
                    KeyMap.ctrl('G')
            );

            impl.setVariable("cancel-emulated", (org.jline.reader.Widget) () -> {
                if (agentBusy && activeProcess != null && activeProcess.isAlive()) {
                    requestAgentInterrupt(new byte[]{0x07});
                    safePrintln("");
                    safePrintln(renderer.yellow("  Cancelling..."));
                }
                return true;
            });
        }
    }

    private void enableManagedSlashCompletion(LineReader lineReader) {
        if (!(lineReader instanceof LineReaderImpl impl)) return;
        lineReader.unsetOpt(LineReader.Option.INSERT_TAB);
        lineReader.setOpt(LineReader.Option.DISABLE_EVENT_EXPANSION);
        lineReader.setVariable("bell-style", "none");
        wrapSlashRefreshWidget(impl, LineReader.SELF_INSERT);
        wrapSlashRefreshWidget(impl, LineReader.BACKWARD_DELETE_CHAR);
        installScrollbackWidgets(impl);
        installActivityNavigationWidgets(impl);
        wrapActivityKillWidget(impl, LineReader.DELETE_CHAR);
        wrapSlashRefreshWidget(impl, LineReader.COMPLETE_WORD);
        wrapActivityAcceptWidget(impl, LineReader.ACCEPT_LINE);
    }

    private void installScrollbackWidgets(LineReaderImpl impl) {
        String pageUpName = "scroll-page-up";
        String pageDownName = "scroll-page-down";
        String topName = "scroll-top";
        String bottomName = "scroll-bottom";

        impl.getWidgets().put(pageUpName, this::scrollTranscriptPageUp);
        impl.getWidgets().put(pageDownName, this::scrollTranscriptPageDown);
        impl.getWidgets().put(topName, this::scrollTranscriptToTop);
        impl.getWidgets().put(bottomName, this::scrollTranscriptToBottom);

        org.jline.reader.Reference pageUp = new org.jline.reader.Reference(pageUpName);
        org.jline.reader.Reference pageDown = new org.jline.reader.Reference(pageDownName);
        org.jline.reader.Reference top = new org.jline.reader.Reference(topName);
        org.jline.reader.Reference bottom = new org.jline.reader.Reference(bottomName);

        List<String> pageUpSequences = keySequences(impl, org.jline.utils.InfoCmp.Capability.key_ppage,
                "\033[5~", "\033[5;2~");
        List<String> pageDownSequences = keySequences(impl, org.jline.utils.InfoCmp.Capability.key_npage,
                "\033[6~", "\033[6;2~");
        List<String> topSequences = keySequences(impl, null,
                "\033[1;5H", "\033[5H");
        List<String> bottomSequences = keySequences(impl, null,
                "\033[1;5F", "\033[5F");

        for (KeyMap<org.jline.reader.Binding> keyMap : impl.getKeyMaps().values()) {
            keyMap.bind(pageUp, pageUpSequences.toArray(String[]::new));
            keyMap.bind(pageDown, pageDownSequences.toArray(String[]::new));
            keyMap.bind(top, topSequences.toArray(String[]::new));
            keyMap.bind(bottom, bottomSequences.toArray(String[]::new));
        }
    }

    private void installActivityNavigationWidgets(LineReaderImpl impl) {
        Widget originalDown = impl.getWidgets().get(LineReader.DOWN_LINE_OR_HISTORY);
        Widget originalUp = impl.getWidgets().get(LineReader.UP_LINE_OR_HISTORY);
        String downWidgetName = "activity-down";
        String upWidgetName = "activity-up";

        impl.getWidgets().put(downWidgetName, () -> {
            if (canHandleActivityDownFromInput(impl)) {
                selectNextActivityItem();
                return true;
            }
            if (originalDown != null) {
                boolean result = originalDown.apply();
                refreshManagedSlashCompletion(impl);
                return result;
            }
            return true;
        });

        impl.getWidgets().put(upWidgetName, () -> {
            if (activityFocusActive && selectPreviousActivityItem()) return true;
            if (originalUp != null) {
                boolean result = originalUp.apply();
                refreshManagedSlashCompletion(impl);
                return result;
            }
            return true;
        });

        org.jline.reader.Reference down = new org.jline.reader.Reference(downWidgetName);
        org.jline.reader.Reference up = new org.jline.reader.Reference(upWidgetName);
        List<String> downSequences = arrowSequences(impl, org.jline.utils.InfoCmp.Capability.key_down,
                "\033[B", "\033OB", "\033[1B");
        List<String> upSequences = arrowSequences(impl, org.jline.utils.InfoCmp.Capability.key_up,
                "\033[A", "\033OA", "\033[1A");
        for (KeyMap<org.jline.reader.Binding> keyMap : impl.getKeyMaps().values()) {
            keyMap.bind(down, downSequences.toArray(String[]::new));
            keyMap.bind(up, upSequences.toArray(String[]::new));
        }
    }

    private List<String> arrowSequences(LineReaderImpl impl,
                                        org.jline.utils.InfoCmp.Capability capability,
                                        String... fallbackSequences) {
        return keySequences(impl, capability, fallbackSequences);
    }

    private List<String> keySequences(LineReaderImpl impl,
                                      org.jline.utils.InfoCmp.Capability capability,
                                      String... fallbackSequences) {
        LinkedHashSet<String> sequences = new LinkedHashSet<>();
        if (capability != null) {
            try {
                String terminalSequence = impl.getTerminal().getStringCapability(capability);
                if (terminalSequence != null && !terminalSequence.isBlank()) {
                    sequences.add(terminalSequence);
                }
            } catch (RuntimeException ignored) {
            }
        }
        sequences.addAll(Arrays.asList(fallbackSequences));
        return new ArrayList<>(sequences);
    }

    private void wrapActivityAcceptWidget(LineReaderImpl impl, String widgetName) {
        Widget original = impl.getWidgets().get(widgetName);
        if (original == null) return;
        impl.getWidgets().put(widgetName, () -> {
            if (activityFocusActive && openSelectedActivityLogs()) return true;
            clearSlashCompletionPanel();
            return original.apply();
        });
    }

    private void wrapActivityKillWidget(LineReaderImpl impl, String widgetName) {
        Widget original = impl.getWidgets().get(widgetName);
        if (original == null) return;
        impl.getWidgets().put(widgetName, () -> {
            if (activityFocusActive && killSelectedActivityItem()) return true;
            boolean result = original.apply();
            refreshManagedSlashCompletion(impl);
            return result;
        });
    }

    private boolean canHandleActivityDownFromInput(LineReaderImpl impl) {
        if (activityFocusActive) return true;
        if (slashCompletionLines != null && !slashCompletionLines.isEmpty()) return false;
        String buffer = impl.getBuffer() == null ? "" : impl.getBuffer().toString();
        return buffer.trim().isEmpty();
    }

    private void wrapSlashRefreshWidget(LineReaderImpl impl, String widgetName) {
        Widget original = impl.getWidgets().get(widgetName);
        if (original == null) return;
        impl.getWidgets().put(widgetName, () -> {
            if (activityFocusActive && LineReader.SELF_INSERT.equals(widgetName)) {
                clearActivitySelection();
            }
            boolean result = original.apply();
            refreshManagedSlashCompletion(impl);
            return result;
        });
    }

    private void wrapSlashClearWidget(LineReaderImpl impl, String widgetName) {
        Widget original = impl.getWidgets().get(widgetName);
        if (original == null) return;
        impl.getWidgets().put(widgetName, () -> {
            clearSlashCompletionPanel();
            return original.apply();
        });
    }

    // ── Agent resolution ───────────────────────────────────────────────────

    private String resolveAgent(String name) {
        return SubprocessAgentRunner.resolveAgentBinary(name);
    }

    // ── Agent output routing ──────────────────────────────────────────────

    /** Whether this agent outputs structured JSON (vs plain text). */
    private static boolean isStructuredAgent(String agentLower) {
        return agentLower.contains("claude")
                || agentLower.contains("gemini") || agentLower.contains("qwen")
                || agentLower.contains("codex") || agentLower.contains("opencode");
    }

    /** Route a line of output to the correct JSON parser. Returns empty list if unparseable. */
    private List<PassthroughStreamParser.PassthroughEvent> parseAgentLineMulti(String agentLower, String line) {
        if (agentLower.contains("claude")) {
            return parser.parseClaudeLineMulti(line);
        } else if (agentLower.contains("gemini") || agentLower.contains("qwen")) {
            PassthroughStreamParser.PassthroughEvent e = parser.parseGeminiLine(line);
            return e != null ? List.of(e) : List.of();
        } else if (agentLower.contains("codex")) {
            PassthroughStreamParser.PassthroughEvent e = parser.parseCodexLine(line);
            return e != null ? List.of(e) : List.of();
        } else if (agentLower.contains("opencode")) {
            PassthroughStreamParser.PassthroughEvent e = parser.parseOpenCodeLine(line);
            return e != null ? List.of(e) : List.of();
        }
        return List.of();
    }

    // ── Follow-up question detection ────────────────────────────────────────

    /**
     * Check if the agent's response ends with a question and prompt the user
     * for a follow-up answer. Returns the user's input (to be sent as the next
     * message), or null if no question was detected or the user skipped.
     */
    private String promptIfQuestion(String agentText) {
        if (agentText == null || agentText.isBlank()) return null;

        // Look at the last non-empty line of the response
        String[] lines = agentText.strip().split("\n");
        String lastLine = "";
        for (int i = lines.length - 1; i >= 0; i--) {
            if (!lines[i].isBlank()) {
                lastLine = lines[i].strip();
                break;
            }
        }

        if (!lastLine.contains("?")) return null;

        // Detect numbered options in the text (e.g., "1. Maven\n2. Gradle\n3. sbt")
        List<String> options = new ArrayList<>();
        for (String line : lines) {
            String trimmed = line.strip();
            if (trimmed.matches("^\\d+\\.\\s+.+")) {
                options.add(trimmed.replaceFirst("^\\d+\\.\\s+", ""));
            }
        }

        // Show a follow-up prompt
        safePrintln("");
        if (!options.isEmpty()) {
            String response = readUserResponse("  choice [1-" + options.size() + "]> ");
            if (response == null || response.isBlank()) return null;
            // Resolve numeric choice to the option text
            try {
                int idx = Integer.parseInt(response.trim());
                if (idx >= 1 && idx <= options.size()) {
                    return options.get(idx - 1);
                }
            } catch (NumberFormatException ignored) {}
            return response.trim();
        } else {
            String response = readUserResponse("  answer> ");
            if (response == null || response.isBlank()) return null;
            return response.trim();
        }
    }

    // ── PTY wrapper ─────────────────────────────────────────────────────────

    /**
     * Wrap a command with {@code script -qc} to allocate a pseudo-TTY.
     * <p>
     * Node.js-based agents (OpenCode, Codex, etc.) fully buffer stdout when
     * writing to a pipe. Without a PTY, no output arrives until the process
     * exits, which can take 30+ seconds for LLM calls. Wrapping with
     * {@code script} forces line-buffered output so we can stream events.
     * <p>
     * Falls back to the original command on Windows or if {@code script}
     * is not available.
     */
    private static List<String> wrapWithPty(List<String> cmd, int rows, int cols) {
        boolean isWindows = System.getProperty("os.name", "").toLowerCase().startsWith("win");
        if (isWindows) return cmd;

        // Check if 'script' is available
        try {
            Process check = new ProcessBuilder("which", "script")
                    .redirectErrorStream(true).start();
            int rc = check.waitFor();
            if (rc != 0) return cmd;
        } catch (Exception e) {
            return cmd;
        }

        // Build the inner command string
        StringBuilder cmdStr = new StringBuilder();
        for (int i = 0; i < cmd.size(); i++) {
            if (i > 0) cmdStr.append(' ');
            String arg = cmd.get(i);
            if (arg.contains(" ") || arg.contains("'") || arg.contains("\"")) {
                cmdStr.append("'").append(arg.replace("'", "'\\''")).append("'");
            } else {
                cmdStr.append(arg);
            }
        }

        // Prepend stty to force PTY dimensions so the subprocess renders at
        // the exact size our VirtualTerminal expects. Without this, the PTY
        // defaults to whatever the kernel assigns and the VT screen layout
        // doesn't match — chrome rows end up in the wrong positions.
        String sizeCmd = "stty rows " + rows + " cols " + cols + " 2>/dev/null; " + cmdStr;

        boolean isMac = System.getProperty("os.name", "").toLowerCase().contains("mac");
        List<String> wrapped = new ArrayList<>();
        wrapped.add("script");
        wrapped.add("-q");
        if (isMac) {
            wrapped.add("/dev/null");
            wrapped.add("/bin/sh");
            wrapped.add("-c");
            wrapped.add(sizeCmd);
        } else {
            wrapped.add("/dev/null");
            wrapped.add("-c");
            wrapped.add(sizeCmd);
        }
        return wrapped;
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    /**
     * Clears the old input box after readLine() returns.
     * The input box is: top border (1 line) + prompt (1 line).
     * After readLine(), cursor is on the line below the prompt.
     * Move up 2 lines and clear to end of screen so the next
     * iteration draws a single clean input box — no stacking.
     */
    private void clearInputBox() {
        System.out.print("\033[2A\033[J");
        System.out.flush();
    }

    /** Prints the top border line above the input area, spanning full terminal width. */
    private void printTopBorder() {
        int termWidth = 80;
        if (terminal != null) {
            int w = terminal.getWidth();
            if (w > 0) termWidth = w;
        }
        int borderWidth = Math.max(20, termWidth);
        System.out.println(DIM + "\u2500".repeat(borderWidth) + RESET);
    }

    private String buildPrompt() {
        StringBuilder sb = new StringBuilder();
        sb.append(CYAN).append("kompile ").append(RESET);
        sb.append(DIM).append("[").append(agent).append("]").append(RESET);
        int queued = messageQueue != null ? messageQueue.size() : 0;
        if (queued > 0) {
            sb.append(DIM).append("[q:").append(queued).append("]").append(RESET);
        }
        if (agentBusy) {
            sb.append(YELLOW).append("[busy]").append(RESET);
        }
        sb.append(CYAN).append("> ").append(RESET);
        return sb.toString();
    }

    /**
     * Dispatches a message to the agent synchronously.
     * When enforcer is configured, wraps the call with enforcement (retry on violations).
     * The REPL blocks until the agent finishes, giving real-time streaming output.
     */
    private boolean dispatchToAgent(String message, ChatHistory history, ChatSessionMetrics metrics) {
        agentBusy = true;
        backgroundSignal.set(false);
        try {
            if (enforcerService != null && enforcerPolicy != null) {
                dispatchEnforced(message, history, metrics);
            } else {
                sendToAgent(message, history, metrics);
            }
            return false;
        } finally {
            agentBusy = false;
        }
    }

    /**
     * Dispatches a message to the agent on a background thread so the REPL input stays active.
     * While the agent is processing, the user can keep typing — messages get queued.
     * When the agent finishes, queued messages are drained automatically.
     */
    private void dispatchToAgentAsync(String message, ChatHistory history, ChatSessionMetrics metrics) {
        Thread dispatchThread = new Thread(() -> {
            try {
                boolean backgrounded = dispatchToAgent(message, history, metrics);
                drainQueuedMessages(history, metrics, false, backgrounded);
            } catch (Exception e) {
                safePrintln(renderer.red("  Agent error: " + e.getMessage()));
            } finally {
                // Redraw prompt hint and return the visible cursor to the active input line.
                restoreIdlePromptCursor();
            }
        }, "agent-dispatch");
        dispatchThread.setDaemon(true);
        dispatchThread.start();
    }

    private boolean canDispatchQueuedMessageAfterBackground() {
        return switch (backgroundDispatchMode()) {
            case NATIVE_PROVIDER, PROVIDER_FORK, KOMPILE_MANAGED -> true;
        };
    }

    private boolean shouldContinueProviderSession() {
        return firstMessageSent && !shouldUseManagedIsolatedBackgroundFollowup();
    }

    private boolean shouldForkProviderBackgroundFollowup() {
        return backgroundTurnCount.get() > 0 && backgroundDispatchMode() == BackgroundDispatchMode.PROVIDER_FORK;
    }

    private boolean shouldUseManagedIsolatedBackgroundFollowup() {
        return backgroundTurnCount.get() > 0 && backgroundDispatchMode() == BackgroundDispatchMode.KOMPILE_MANAGED;
    }

    private BackgroundDispatchMode backgroundDispatchMode() {
        return BackgroundDispatchMode.KOMPILE_MANAGED;
    }

    /**
     * Enforced dispatch: wraps sendToAgent with EnforcerService retry logic.
     */
    private void dispatchEnforced(String message, ChatHistory history, ChatSessionMetrics metrics) {
        if (enforcerConversationWindow != null) {
            enforcerConversationWindow.addUserMessage(message);
        }

        int[] attemptCounter = {0};
        try {
            EnforcerResult result = enforcerService.enforce(message, enforcerPolicy,
                    enforcerConversationWindow != null ? enforcerConversationWindow::snapshot : null,
                    agentPrompt -> {
                        attemptCounter[0]++;
                        if (attemptCounter[0] > 1) {
                            safePrintln(renderer.yellow(
                                    "[enforcer] violation detected, sending correction (attempt "
                                            + attemptCounter[0] + ")"));
                        }
                        String output = sendToAgent(agentPrompt, history, metrics);
                        if (enforcerConversationWindow != null) {
                            enforcerConversationWindow.finishAssistantMessage(output);
                        }
                        return output;
                    });

            if (result != null) {
                switch (result.getStatus()) {
                    case ACCEPTED -> {
                        if (result.getAttempts().size() > 1) {
                            safePrintln(renderer.dim("[enforcer] accepted after "
                                    + result.getAttempts().size() + " attempts"));
                        }
                    }
                    case BLOCKED -> {
                        safePrintln(renderer.yellow("[enforcer] blocked: " + result.getMessage()));
                        var attempts = result.getAttempts();
                        if (!attempts.isEmpty() && attempts.get(attempts.size() - 1).decision() != null) {
                            for (String v : attempts.get(attempts.size() - 1).decision().getViolations()) {
                                safePrintln(renderer.yellow("  - " + v));
                            }
                        }
                    }
                    case UNAVAILABLE, ERROR ->
                            safePrintln(renderer.red("[enforcer] " + result.getMessage()));
                }
            }

            if (result != null && !result.isAccepted()) {
                history.logSystem("Enforcer " + result.getStatus() + ": " + result.getMessage());
            }
        } catch (Exception e) {
            safePrintln(renderer.red("[enforcer] error: " + e.getMessage()));
        }
    }

    private void killProcess(Process process) {
        if (process == null || !process.isAlive()) return;
        try {
            boolean isUnix = !System.getProperty("os.name", "").toLowerCase().startsWith("win");
            if (isUnix) {
                signalProcessTree(process, "-INT");
                if (process.isAlive()) {
                    Thread.sleep(500);
                    signalProcessTree(process, "-TERM");
                }
                if (process.isAlive()) {
                    Thread.sleep(300);
                    signalProcessTree(process, "-9");
                }
            } else {
                process.destroyForcibly();
            }
        } catch (Exception e) {
            process.destroyForcibly();
        }
    }

    private void signalProcessTree(Process process, String signal) throws IOException, InterruptedException {
        List<Long> pids = processTreePids(process);
        for (long pid : pids) {
            new ProcessBuilder("kill", signal, String.valueOf(pid))
                    .redirectErrorStream(true).start().waitFor();
        }
    }

    private static List<Long> processTreePids(Process process) {
        if (process == null) return List.of();
        List<Long> pids = new ArrayList<>();
        process.toHandle().descendants()
                .filter(ProcessHandle::isAlive)
                .map(ProcessHandle::pid)
                .sorted(Comparator.reverseOrder())
                .forEach(pids::add);
        if (process.isAlive()) {
            pids.add(process.pid());
        }
        return pids;
    }

    record QueuedMessageDraft(String id, String content) {}

    private static void inheritEnv(Map<String, String> env, String... keys) {
        for (String key : keys) {
            String val = System.getenv(key);
            if (val != null) env.put(key, val);
        }
    }

    /** Strip ALL ANSI/VT escape sequences from a string. */
    private static String stripAnsi(String s) {
        return s.replaceAll(ANSI_REGEX, "");
    }

}
