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
import ai.kompile.cli.main.chat.agent.AgentFlagOverrides;
import ai.kompile.cli.main.chat.agent.SubprocessAgentRunner;
import ai.kompile.cli.mcp.stdio.TaskRecord;
import ai.kompile.cli.mcp.stdio.TaskRegistry;
import ai.kompile.utils.FormatUtils;
import ai.kompile.cli.main.chat.config.ChatConfig;
import ai.kompile.cli.main.chat.config.SystemPromptManager;
import ai.kompile.cli.main.chat.enforcer.*;
import ai.kompile.cli.common.enforcer.DiffPatternEvaluator;
import ai.kompile.cli.main.chat.format.ConversationReader;
import ai.kompile.cli.main.chat.harness.HarnessConfig;
import ai.kompile.cli.main.chat.mcp.McpToolInjection;
import ai.kompile.cli.main.chat.render.AsciiRenderer;
import ai.kompile.cli.main.chat.render.TerminalRenderer;
import ai.kompile.cli.main.chat.terminal.AgentLaunchSpec;
import ai.kompile.cli.main.chat.terminal.AgentProcess;
import ai.kompile.cli.main.chat.terminal.FrameSettleGate;
import ai.kompile.cli.main.chat.terminal.InterruptEscalation;
import ai.kompile.cli.main.chat.terminal.PtyDims;
import ai.kompile.cli.main.chat.terminal.RenderPolicy;
import ai.kompile.cli.main.chat.terminal.ScriptAgentProcess;
import ai.kompile.cli.main.chat.terminal.ScriptPtyProvider;
import ai.kompile.cli.main.chat.terminal.SessionIdentity;
import ai.kompile.cli.main.chat.terminal.TerminalQueryStripResult;
import ai.kompile.cli.main.chat.terminal.TerminalQueryStripper;
import ai.kompile.cli.main.chat.terminal.TurnIdleDetector;
import ai.kompile.cli.main.chat.tools.BackgroundProcessManager;
import ai.kompile.cli.main.chat.tui.AgentTuiDecoder;
import ai.kompile.cli.main.chat.tui.KompileTui;
import ai.kompile.cli.main.chat.tui.MirrorRenderer;
import ai.kompile.cli.main.chat.tui.StatusBar;
import ai.kompile.cli.main.chat.tui.VirtualTerminal;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import ai.kompile.utils.AnsiConstants;
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
import java.util.stream.Stream;

import org.jline.keymap.KeyMap;
import org.jline.reader.Binding;
import org.jline.reader.Candidate;
import org.jline.reader.EndOfFileException;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.MaskingCallback;
import org.jline.reader.ParsedLine;
import org.jline.reader.Reference;
import org.jline.reader.UserInterruptException;
import org.jline.reader.Widget;
import org.jline.reader.impl.LineReaderImpl;
import org.jline.terminal.Attributes;
import org.jline.terminal.MouseEvent;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.jline.utils.InfoCmp;
import org.jline.utils.NonBlockingReader;
import sun.misc.Signal;
import sun.misc.SignalHandler;


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

    @CommandLine.Option(names = {"--model", "-m"}, description = "Model passed to the agent CLI (e.g. haiku, gpt-5.2-codex, anthropic/claude-haiku-4-5)")
    String model;

    private final ObjectMapper objectMapper = JsonUtils.standardMapper();
    private final PassthroughStreamParser parser = new PassthroughStreamParser();
    private final ChatCompleter chatCompleter = new ChatCompleter(() -> null);
    private McpUrlResolver mcpUrlResolver = new McpUrlResolver();

    private TerminalRenderer renderer;
    private AsciiRenderer ascii;
    private KompileTui tui;
    private VirtualTerminal virtualTerminal;

    // Track whether the managed session has sent at least one message.
    private volatile boolean firstMessageSent = false;

    // Agent session IDs — captured from first structured event, used for multi-turn.
    // Volatile: written by output reader thread, read by main thread in buildCommand.
    private volatile String agentSessionId = null;

    // The session transcript — kept as a field so the output reader thread can record
    // the agent's native session id ([harvested:] marker) when it is captured.
    private volatile ChatHistory sessionHistory;

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
    private SignalHandler sigintHandler; // re-installed before each subprocess

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
    // True while the agent has paused mid-turn to ask the user a decision (confirmation, menu,
    // yes/no, or question). While set: the turn is kept alive (not completed by the idle detector)
    // and the user's typed input is forwarded straight to the agent's stdin as the answer, so
    // answering behaves like the native app. Set/cleared by the output-reader from the decoder.
    private volatile boolean agentAwaitingInput = false;
    // Last time the agent's dialog was positively detected — used to debounce the awaiting state so
    // it survives the brief non-match while the agent redraws its menu on each arrow keystroke.
    private volatile long lastAwaitingAt = 0;
    // Tracks last subprocess output timestamp for status/debug logging.
    private final AtomicLong lastOutputTime = new AtomicLong(0);
    // Last message sent to agent — used to filter PTY echo of user input
    private volatile String lastSentMessage;
    // Subprocess log file — raw PTY output for debugging
    private volatile Writer subprocessLogWriter;
    // Raw PTY byte dump — replay with `cat` to see exact subprocess rendering.
    // Stored at <project>/.kompile/logs/agent-pty-dump.bin (or ~/.kompile/logs fallback)
    // per-session, overwritten each launch.
    private volatile OutputStream subprocessPtyDump;
    // Agent-specific TUI decoder — knows each agent's chrome layout and terminal queries.
    private volatile AgentTuiDecoder agentDecoder;

    // Scroll region layout — input box stays fixed at bottom, chat scrolls above
    private int scrollBottom;  // last row of scroll region (1-indexed)
    private int inputRows = 1;
    private int activityRows = 2;
    private volatile String currentStatus = "idle";
    private final List<String> scrollbackLines = new ArrayList<>();
    private int scrollViewportOffset = 0; // lines above live bottom; 0 means follow output
    private int liveDecoderScrollbackStart = -1;
    private int liveDecoderScrollbackLength = 0;
    /** Lines scrolled per mouse-wheel notch over the managed transcript. */
    private static final int WHEEL_SCROLL_LINES = 3;
    // Real-terminal mouse capture. When enabled, the wheel is delivered to
    // Kompile and consumed for transcript scrolling instead of scrolling the
    // host terminal's native scrollback. Only ON while a decoder owns the
    // screen; reports are NEVER forwarded to the agent (decoder-owned TUIs such
    // as OpenCode render mouse activity if they receive them).
    private volatile boolean transcriptMouseEnabled = false;
    // Raw key pass-through: while true, the REPL loop forwards every keystroke
    // straight to the agent (for the agent's own multi-key / menu shortcuts) instead
    // of running JLine's line editor. Entered via /passthrough, exited with Ctrl+].
    private volatile boolean agentPassthroughActive = false;
    // Decoder-owned rendering is gated on output-burst drain + screen-hash change so we snapshot a
    // coherent screen, never a mid-update frame (the cause of jumbled text). L2 of the Terminal
    // Session Framework (WP6): the settle+hash gate is the shared FrameSettleGate, and HOW a frame
    // reaches the terminal (raw bytes | mirror blit | decoded transcript) is a hot-swappable
    // RenderPolicy strategy object the pump dispatches through.
    private final FrameSettleGate frameSettleGate =
            new FrameSettleGate();
    private RenderPolicy rawPolicy;
    private RenderPolicy mirrorPolicy;
    private RenderPolicy decodedPolicy;
    private volatile RenderPolicy renderPolicy;
    // renderMode = mirror: blit the agent's VirtualTerminal screen verbatim into the scroll
    // region (the agent paints its own UI) instead of decoding+merging it into a transcript.
    private volatile boolean mirrorRender = false;
    private volatile boolean mirrorInputPrimed = false;
    // Set when kompile auto-switched to mirror because the user began arrow-navigating an agent
    // dialog (so the agent's own screen renders the live cursor/tabs in place). Restored to decoded
    // once the dialog closes. Distinct from a user's manual /render mirror.
    private volatile boolean autoMirrorForDialog = false;

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
    // L0 handle owning spawn/PTY/signals/resize for the persistent TUI process (Terminal Session Framework).
    private volatile AgentProcess tuiAgentProcess;
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
        private String status = "running";

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
            addLogRecord(normalized);
        }

        private void addLogRecord(String log) {
            String normalized = log == null ? "" : log.replaceAll("\\s+", " ").trim();
            if (normalized.isBlank()) return;
            logs.addLast(normalized);
            while (logs.size() > 40) {
                logs.removeFirst();
            }
        }

        private boolean active() {
            if (process != null) return process.isAlive();
            return status == null || status.isBlank() || status.equalsIgnoreCase("running");
        }
    }

    private record ActivityMenuItem(String id, String kind, String label, String status,
                                    String latestLog, List<String> logs, String outputPath,
                                    boolean killable, boolean registryBacked,
                                    String agentName, String roleName, String parentId,
                                    int childCount) {}

    // Background process manager for this session; the judge + enforcer register as watcher
    // entries here so they are visible in the status bar and the /processes (/activity) menu.
    private BackgroundProcessManager bgProcMgr;

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
    // The LLM judge (judge mode only; null in keyword mode). Set by EnforcerCommand alongside the
    // service. Enables the REALTIME semantic tap below — the judge evaluates the agent's native
    // session JSONL as it is written, independent of which render policy owns the screen (WP9/F3).
    EnforcerJudge enforcerJudge;
    // WP9/F3: tails the agent's native session JSONL and judge-evaluates text + tool calls in
    // realtime during a managed TUI turn — the "missing link" that gives PTY modes the enforcement
    // the headless path already had. Started lazily on first enforced dispatch; closed on shutdown.
    // Violations are now actuated through the same agent-input path users exercise: send ESC to
    // stop generation while keeping the persistent TUI alive, wait until the decoder sees idle, then
    // submit the judge's correction prompt into the same session. L0 signal escalation remains out of
    // this path because it is a process-killer.
    private volatile RealtimeEnforcementTap enforcerRealtimeTap;
    private final AtomicBoolean enforcerTailStarted =
            new AtomicBoolean(false);
    private final AtomicBoolean realtimeEnforcerActuatedThisTurn = new AtomicBoolean(false);
    private final AtomicBoolean realtimeEnforcerActuationInFlight = new AtomicBoolean(false);
    private final AtomicLong realtimeEnforcerCorrectionSubmittedAt = new AtomicLong(0);
    private static final long REALTIME_ENFORCER_CANCEL_IDLE_TIMEOUT_MS = 5_000L;
    // WP13: observe-only fallback advisor. Watches per-turn judge outcomes and SUGGESTS switching to
    // a stronger agent when the current one degrades (catastrophic / consecutive / cumulative). It
    // never switches on its own — surfacing a hint keeps this safe; the user acts via /agent.
    private volatile FallbackSupervisor enforcerFallbackAdvisor;

    // The one identity for this session (F8): Kompile id + enforcer id + agent-native id. Minted at
    // session start; judgement lookups key off it instead of guessing between the two id schemes.
    private volatile SessionIdentity sessionIdentity;

    // Live enforcement toggle — /enforcer pause|resume flips this. The dispatch gate skips the
    // judge while paused WITHOUT tearing down the configured enforcer, so it can be resumed.
    volatile boolean enforcementPaused;

    // Diff-archive + diff-pattern features ported from the classic `kompile enforcer` (live-PTY
    // enforcement): per-turn git snapshot, diff-pattern checks, and rollback-on-violation. Set by
    // EnforcerCommand when delegating to a live PTY session.
    EnforcerDiffArchive enforcerDiffArchive;
    DiffPatternEvaluator enforcerDiffPatternEvaluator;
    boolean enforcerAutoRollbackOnViolation;

    // ANSI — delegated to shared constants
    private static final String RESET = AnsiConstants.RESET;
    private static final String CYAN = AnsiConstants.CYAN;
    private static final String GREEN = AnsiConstants.GREEN;
    private static final String YELLOW = AnsiConstants.YELLOW;
    private static final String DIM = AnsiConstants.DIM;
    private static final String BOLD = AnsiConstants.BOLD;
    private static final String INVERSE = AnsiConstants.INVERSE;

    private static final String ANSI_REGEX = AnsiConstants.ANSI_STRIP_REGEX;

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

            // Install via Signal (fires during process.waitFor).
            // JLine's readLine() will override this while reading, so we
            // re-install it before each subprocess in sendToAgent().
            SignalHandler previousHandler = null;
            try {
                previousHandler = Signal.handle(
                        new Signal("INT"), sigintHandler);
            } catch (IllegalArgumentException e) {
                // Signal handling not supported
            }
            final SignalHandler savedHandler = previousHandler;

            // Resolve agent binary
            String agentBinary = resolveAgent(agent);
            if (agentBinary == null) {
                System.err.println("Agent '" + agent + "' not found on PATH.");
                System.err.println("Supported agents: " + String.join(", ",
                        ChatConfig.getPassthroughAgentOrder()));
                System.err.println("Install the agent and make sure it is on your PATH.");
                return 1;
            }

            // Session setup
            String sessionId = "emulated-" + UUID.randomUUID().toString().substring(0, 8);
            // One identity for the session (F8): Kompile's id + the enforcer runtime-policy id
            // (from the env), so judgement lookups no longer have to guess which id is in scope.
            this.sessionIdentity = SessionIdentity.of(
                    sessionId,
                    enforcerExtraEnv != null ? enforcerExtraEnv.get("KOMPILE_ENFORCER_SESSION_ID") : null);
            ChatHistory history = new ChatHistory(sessionId);
            this.sessionHistory = history;
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
            this.bgProcMgr = new BackgroundProcessManager(sessionId, Path.of(workingDir));
            this.tui = new KompileTui(bgTaskMgr, bgProcMgr, messageQueue, renderer);
            this.drawLock = tui.getDrawLock();
            tui.setAgentName(agent);
            tui.setSessionId(sessionId);
            tui.setMode("passthrough");
            tui.setEnforcerActive(enforcerEvaluator != null);
            // Make the judge + enforcer visible as watcher "processes" (status bar + /processes).
            registerEnforcerWatchers();
            // Tell KompileTui to reserve rows for the input box + status line + activity panel.
            tui.setReservedRowsCalculator((h, w) -> {
                int ir = h < 12 ? 1 : Math.max(3, Math.min(8, h / 5));
                int ar = h < 16 ? 1 : Math.max(2, Math.min(4, h / 8));
                // queue(1)+busy(1)+topBorder(1)+input(ir)+bottomBorder(1)+status(1)+activity(ar)
                return ir + ar + 5;
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
                // Layer 3: propagate the new geometry to the subprocess. The L0 AgentProcess sends
                // SIGWINCH to the whole descendant tree so it reaches the agent running inside
                // script(1)'s child shell — the old direct-child-only signal missed it (WP2/F7).
                AgentProcess proc = tuiAgentProcess;
                if (proc != null && proc.isAlive()) {
                    proc.resize(newRows, newCols);
                }
            });

            // Welcome — prints into scroll region
            printWelcomePanel(agentBinary);

            // If resuming, replay prior conversation turns into Kompile's UI.
            // The managed subprocess launch remains provider-agnostic.
            if (resumeSessionId != null && !resumeSessionId.isBlank()) {
                replayConversationHistory(resumeSessionId, history);
                // Seed continuation with the agent's REAL session id. Kompile-stored
                // sessions (passthrough-*/emulated-*) use a synthetic id the agent knows
                // nothing about — the native id lives in their [harvested:] markers.
                String nativeResumeId = ChatHistory.exists(resumeSessionId)
                        ? ChatHistory.resolveNativeSessionId(resumeSessionId, agent)
                        : resumeSessionId;
                agentSessionId = nativeResumeId;
                if (sessionIdentity != null && nativeResumeId != null) {
                    sessionIdentity = sessionIdentity.withAgentNativeSessionId(nativeResumeId);
                }
                firstMessageSent = true;
            }

            replThread = Thread.currentThread();
            try {
                while (true) {
                    if (shutdownSignal.get()) break;

                    // Raw key pass-through runs its own input loop instead of the line
                    // editor, forwarding every keystroke to the agent until Ctrl+].
                    if (agentPassthroughActive) {
                        runAgentPassthroughLoop();
                        continue;
                    }

                    // Mirror mode uses Kompile's input box + dispatch just like decoded mode —
                    // it ONLY changes how the scroll region is rendered (blit vs transcript).
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
                                        (MaskingCallback) null, restoredDraft);
                    } catch (UserInterruptException e) {
                        if (agentBusy) {
                            // Ctrl+C while the child is active is a child interrupt; Ctrl+G is Kompile's hard cancel.
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

                    if (line == null) {
                        continue;
                    }
                    if (line.isBlank()) {
                        // A bare Enter while the agent is prompting accepts its default option.
                        if (agentAwaitingInput) forwardPromptAnswer("");
                        continue;
                    }
                    String trimmed = line.trim();

                    // Re-establish scroll region (JLine's readLine may have reset it)
                    tui.reestablishScrollRegion();
                    scrollBottom = tui.scrollBottom();

                    if (trimmed.startsWith("/")) {
                        String result = handleSlashCommand(trimmed, lineReader, history, metrics);
                        if ("quit".equals(result)) break;
                    } else if (agentAwaitingInput || isRecentPromptAnswer(trimmed)) {
                        // The agent paused mid-turn to ask — forward the typed answer straight to it,
                        // as if the user had answered in the native app (number, yes/no, or text).
                        recordInputHistory(trimmed);
                        safePrintln(renderer.dim("  → " + trimmed));
                        forwardPromptAnswer(trimmed);
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

                // Stop the realtime enforcer JSONL tap (WP9) before tearing down the process.
                stopEnforcerRealtimeTail();

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
                        Signal.handle(new Signal("INT"), savedHandler);
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
            activityRows = h < 16 ? 1 : Math.max(2, Math.min(4, h / 8));
            // queue(1)+busy(1)+topBorder(1)+input(inputRows)+bottomBorder(1)+status(1)+activity(activityRows)
            // Kompile keeps its input box + bottom chrome in BOTH modes — mirror only changes how
            // the scroll-region CONTENT is produced (blit vs decoded transcript), not the layout.
            int reserved = inputRows + activityRows + 5;
            tui.setReservedMiddleRows(reserved);
            tui.reestablishScrollRegion();
            scrollBottom = tui.scrollBottom();
            clampScrollViewportOffsetLocked();
            if (mirrorRender && virtualTerminal != null) {
                mirrorVtToScrollRegion(virtualTerminal);
            } else {
                redrawScrollViewportContentLocked();
            }
            drawFixedInputBox();
        }
    }

    /**
     * Clear the entire screen and repaint everything fresh — top/bottom bars, scroll-region
     * content (mirror blit or decoded transcript), and the input box — so a render-mode switch
     * leaves NO stale content from the previous mode/state. Driven by {@code /render}.
     */
    private void fullRepaint() {
        if (tui == null) return;
        synchronized (drawLock) {
            System.out.print("\033[r\033[2J\033[H");   // reset scroll region, erase screen, home
            tui.redrawBars();
            tui.reestablishScrollRegion();
            scrollBottom = tui.scrollBottom();
            clampScrollViewportOffsetLocked();
            if (mirrorRender && virtualTerminal != null) {
                drawFixedInputBox();                    // Kompile keeps its input box in mirror
                System.out.flush();
                mirrorVtToScrollRegion(virtualTerminal); // blit; restores the cursor to the box
            } else {
                redrawScrollViewportLocked(activeLineReader);
                System.out.flush();
            }
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
            if (rendered.isEmpty()) {
                return;
            }
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
        StringBuilder filtered = new StringBuilder();
        String[] lines = text.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String line = stripTrailingWhitespace(lines[i]);
            String stripped = line.trim();
            if (!stripped.isEmpty()) {
                if (isSentMessageEcho(stripped)) {
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

    /**
     * True when a decoded output line is just the agent echoing the message we sent.
     * Both sides are normalized first ({@link #normalizeEchoLine}) so box-drawing/dash
     * glyphs an agent's input frame bleeds into the echoed row don't defeat the match.
     * Recognized on every repaint of the prompt box — we intentionally do NOT consume
     * {@code lastSentMessage} — which is what stops the echo from leaking a duplicate
     * when the agent repaints.
     */
    private boolean isSentMessageEcho(String strippedLine) {
        String echo = normalizeEchoLine(lastSentMessage);
        if (echo.isEmpty()) return false;
        String norm = normalizeEchoLine(strippedLine);
        if (norm.isEmpty()) return false;
        return norm.equals(echo)
                || (echo.length() >= 8 && norm.endsWith(echo))
                || (echo.length() >= 16 && norm.length() >= 12 && echo.contains(norm));
    }

    /** Drop box-drawing/dash glyphs an input frame can bleed in, then collapse whitespace. */
    private String normalizeEchoLine(String s) {
        if (s == null || s.isBlank()) return "";
        return s.replaceAll("\u001B\\[[0-?]*[ -/]*[@-~]", " ")
                .replaceAll(ANSI_REGEX, " ")
                .replaceAll("\\[[0-9;?]*[A-Za-z]", " ")
                .replaceAll("[\\u2500-\\u257F\\u2010-\\u2015]", " ")
                .replaceAll("\\s+", " ")
                .trim();
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
        if (mirrorRender) {
            // Mirror shows the agent's LIVE screen (a blit), not a Kompile scrollback. Repainting
            // the transcript here would clobber the blit and the agent's UI would vanish. There is
            // no Kompile scrollback to move through in mirror, so keep painting the agent's current
            // screen (the agent owns its own scrollback). Scroll is effectively a no-op here.
            if (virtualTerminal != null) mirrorVtToScrollRegion(virtualTerminal);
            return false;
        }
        int before = scrollViewportOffset;
        scrollViewportOffset = Math.max(0, Math.min(offset, maxScrollViewportOffsetLocked()));
        redrawScrollViewportLocked(reader);
        return before != scrollViewportOffset;
    }

    private void redrawScrollViewportLocked(LineReader reader) {
        hideCursor();
        redrawScrollViewportContentLocked();
        if (reader != null && !busyInputActive) {
            drawFixedInputChrome(true);
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
            // Fit to width-1, not full width: printing a full-width line on the bottom
            // row of the scroll region can trigger an unwanted region scroll (collapse) on
            // terminals without deferred auto-wrap. fitScrollLine clips without an ellipsis,
            // so we keep the no-"..." behavior while preserving the safe last-column margin.
            System.out.printf("\033[%d;1H\033[2K%s", row, fitScrollLine(line, Math.max(1, width - 1)));
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
            renderStatusLineLocked();
            renderActivityPanelLocked();
            if (keepCursorInInput) {
                drawBusyInputRows(inputBuffer, w);
            } else if (preserveCursor) {
                restoreCursor();
            }
            System.out.flush();
        }
    }

    private void drawFixedInputChrome() {
        drawFixedInputChrome(false);
    }

    private void drawFixedInputChrome(boolean clearInputRows) {
        int w = terminal != null && terminal.getWidth() > 0 ? terminal.getWidth() : 120;
        String border = DIM + "\u2500".repeat(w) + RESET;
        drawQueuePreviewLine(w);
        drawBusyPromptLine(w);
        System.out.printf("\033[%d;1H\033[2K%s", topBorderRow(), border);
        if (clearInputRows) {
            for (int row = firstInputRow(); row <= lastInputRow(); row++) {
                System.out.printf("\033[%d;1H\033[2K", row);
            }
        }
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
            line = "  Enter draft · ↑ edit pending · Ctrl+B background · Esc/Ctrl+C child · Ctrl+G cancel";
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
        String plain = AnsiConstants.stripAnsi(text);
        if (plain.length() <= width) return text;
        return truncatePlain(plain, width);
    }

    /**
     * Fit a transcript line to the scroll viewport. Unlike {@link #fitAnsiLine} this
     * never appends an ellipsis: a line that exactly fills the width (a separator, a
     * full-width box border, padded agent output) is legitimate content, and a "..."
     * marker at the right edge of every such line reads as a spurious column of dots.
     * Lines that fit keep their ANSI styling; only genuinely over-wide lines are
     * hard-clipped, with no marker.
     */
    private String fitScrollLine(String text, int width) {
        if (text == null || text.isEmpty()) return "";
        String plain = AnsiConstants.stripAnsi(text);
        if (plain.length() <= width) return text;
        return plain.substring(0, Math.max(0, width));
    }

    /** Update the managed status line ("kompile [agent] · status …") below the input box. */
    void updateStatusLine(String status) {
        currentStatus = status == null || status.isBlank() ? "idle" : status;
        redrawStatusLine();
        // KompileTui's bottom StatusBar tracks its own consolidated metrics separately.
        if (tui != null) {
            tui.getStatusBar().requestRedraw();
        }
    }

    private void drawActivityPanel(int terminalWidth) {
        redrawActivityPanelOnly();
    }

    /** Repaint just the status line in place, preserving the active prompt cursor. */
    private void redrawStatusLine() {
        synchronized (drawLock) {
            if (terminal == null || scrollBottom <= 0) return;
            boolean activePrompt = activeLineReader != null && !busyInputActive;
            if (activePrompt) hideCursor();
            saveCursor();
            renderStatusLineLocked();
            restoreCursor();
            if (activePrompt) showCursor();
            System.out.flush();
        }
    }

    /** Render the status line at {@link #statusRow()}. Caller owns cursor save/restore. */
    private void renderStatusLineLocked() {
        if (scrollBottom <= 0) return;
        int w = terminal != null && terminal.getWidth() > 0 ? terminal.getWidth() : 120;
        String status = currentStatus == null || currentStatus.isBlank() ? "idle" : currentStatus;
        // BUG 9 fix: never show "idle" while a turn is active — the decoder can briefly report
        // isResponding=false between frames even though agentBusy is still true, which caused the
        // middle status line to flicker to "idle" while the input box showed [busy].
        if (agentBusy && "idle".equals(status)) status = "responding";
        String enforcerTag = enforcerStatusTag(enforcerEvaluator != null, enforcementPaused);
        String text = "  kompile [" + agent + "] · " + status + enforcerTag + " · Esc/Ctrl+C child · Ctrl+G cancel";
        System.out.printf("\033[%d;1H\033[2K%s", statusRow(),
                DIM + truncatePlain(text, Math.max(12, w - 1)) + RESET);
    }

    /** Render the activity panel rows below the status line. Caller owns cursor save/restore. */
    private void renderActivityPanelLocked() {
        if (scrollBottom <= 0) return;
        int w = terminal != null && terminal.getWidth() > 0 ? terminal.getWidth() : 120;
        List<String> lines = buildActivityLines(Math.max(12, w - 1));
        int first = activityFirstRow();
        for (int i = 0; i < activityRows; i++) {
            String line = i < lines.size() ? lines.get(i) : "";
            System.out.printf("\033[%d;1H\033[2K%s", first + i, fitAnsiLine(line, Math.max(1, w - 1)));
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
                ? "  /activity enter <id> | logs <id> | kill <id> | close"
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
            String more = truncatePlain(AnsiConstants.stripAnsi(lines.get(last)) + " +" + (items.size() - shown) + " more", width);
            lines.set(last, selected ? INVERSE + more + RESET : more);
        }
        if (hasTodos && lines.size() < activityRows) {
            lines.add(DIM + "  todos " + RESET + formatTodoItems(todos, Math.max(10, width - 10)));
        }
        if (lines.size() < activityRows) {
            String hint = activityFocusActive
                    ? "  up/down select | Enter inspect | Del kill"
                    : "  Down selects activity | /activity enter <id> | logs <id> | kill <id>";
            lines.add(DIM + truncatePlain(hint, width) + RESET);
        }
        return lines.size() <= activityRows ? lines : lines.subList(0, activityRows);
    }

    private String formatActivityMenuLine(ActivityMenuItem item, int width) {
        String action = item.killable ? " kill" : " enter";
        String log = item.latestLog == null || item.latestLog.isBlank() ? "" : " - " + item.latestLog;
        String relation = activityRelationSuffix(item);
        String line = truncatePlain("  [" + item.id + "] " + item.kind + " " + item.status + " "
                + item.label + relation + action + log, width);
        return isSelectedActivity(item) ? INVERSE + line + RESET : line;
    }

    private String activityOwnerPrefix(String agentName, String roleName) {
        String agent = agentName == null ? "" : agentName.trim();
        String role = roleName == null ? "" : roleName.trim();
        if (agent.isBlank() && role.isBlank()) return "";
        if (agent.isBlank()) return role + " -> ";
        if (role.isBlank()) return agent + " -> ";
        return agent + "/" + role + " -> ";
    }

    private String activityRelationSuffix(ActivityMenuItem item) {
        if (item == null) return "";
        List<String> parts = new ArrayList<>();
        if (item.parentId != null && !item.parentId.isBlank()) {
            parts.add("parent=" + item.parentId);
        }
        if (item.childCount > 0) {
            parts.add("children=" + item.childCount);
        }
        return parts.isEmpty() ? "" : " (" + String.join(", ", parts) + ")";
    }

    private List<ActivityMenuItem> activityMenuItems() {
        List<ActivityMenuItem> items = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        synchronized (activityLock) {
            for (ActivityItem item : subagentActivities) {
                addActivityMenuItem(items, seen, activityMenuItem(item, "agent", activityStatus(item)));
            }
        }
        for (TaskRecord record : activeTaskRecords()) {
            addActivityMenuItem(items, seen, activityMenuItem(record));
        }
        synchronized (activityLock) {
            for (ActivityItem item : backgroundActivities) {
                addActivityMenuItem(items, seen, activityMenuItem(item, "bg", activityStatus(item)));
            }
        }
        if (bgProcMgr != null) {
            for (BackgroundProcessManager.ProcessEntry entry : bgProcMgr.listAll()) {
                addActivityMenuItem(items, seen, activityMenuItem(entry));
            }
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
        activityMenuMessage = "Enter inspect | Del kill | Up returns to input";
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
        activityMenuMessage = "Enter inspect | Del kill | Up returns to input";
        redrawActivityPanelOnly();
        return true;
    }

    private boolean openSelectedActivityLogs() {
        if (!activityFocusActive || selectedActivityId.isBlank()) return false;
        enterActivityItem(selectedActivityId);
        activityMenuMessage = "entered " + selectedActivityId;
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
                logs, "", killable, false, "", "", "", 0);
    }

    private String taskActivityLabel(TaskRecord record) {
        String title = firstNonBlankText(record.getSubtaskName(), record.getDescription(),
                record.getPromptSummary(), record.getTaskId());
        String owner = activityOwnerPrefix(firstNonBlankText(record.getAgentName()),
                firstNonBlankText(record.getRoleName()));
        if (!owner.isBlank()) return owner + title;
        return title;
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
        String label = taskActivityLabel(record);
        String status = record.getStatus() == null ? "active" : record.getStatus().name().toLowerCase(Locale.ROOT);
        boolean killable = record.isActive() && record.getPid() > 0;
        int childCount = record.getChildTaskIds() == null ? 0 : record.getChildTaskIds().size();
        return new ActivityMenuItem(record.getTaskId(), kind, truncatePlain(label, 80), status,
                latest, logs, record.getOutputPath(), killable, true,
                firstNonBlankText(record.getAgentName()), firstNonBlankText(record.getRoleName()),
                firstNonBlankText(record.getParentTaskId()), childCount);
    }

    private ActivityMenuItem activityMenuItem(BackgroundProcessManager.ProcessEntry entry) {
        if (entry == null) return null;
        String status = entry.getState().name().toLowerCase(Locale.ROOT);
        Map<String, String> md = entry.getMetadata();
        List<String> logs = tailProcessOutput(entry.getOutputFile(), 40);
        String latest = logs.isEmpty()
                ? (md != null && md.get("backend") != null ? md.get("backend") : "")
                : logs.get(logs.size() - 1);
        boolean killable = !entry.isVirtual() && entry.isRunning();
        return new ActivityMenuItem(entry.getId(), entry.getKind().label(),
                truncatePlain(entry.getDescription(), 80), status,
                latest, logs, entry.getOutputFile() == null ? "" : entry.getOutputFile().toString(),
                killable, false, "", "", "", 0);
    }

    /**
     * Register the judge + enforcer as virtual watcher entries in the background process manager so
     * they show up in the status bar and the /processes (/activity) menu. Without this the
     * enforcement machinery ran invisibly during a passthrough session.
     */
    private void registerEnforcerWatchers() {
        registerEnforcerWatchers(bgProcMgr, enforcerEvaluator, enforcerPolicy);
    }

    /** Register judge + enforcer watcher entries. Package-private + static for testing. */
    static void registerEnforcerWatchers(BackgroundProcessManager mgr,
                                         EnforcerEvaluator evaluator, EnforcerPolicy policy) {
        if (mgr == null || evaluator == null) {
            return;
        }
        boolean llm = evaluator.recordsJudgements();
        String mode = llm ? "LLM judge" : "keyword";
        String backend = evaluator.describe();
        int ruleCount = 0;
        if (policy != null && policy.getRules() != null) {
            ruleCount = (int) policy.getRules().lines().filter(l -> !l.isBlank()).count();
        }
        mgr.registerVirtual(
                BackgroundProcessManager.ProcessKind.ENFORCER, "enforcer",
                "Enforcer · " + mode + " · " + ruleCount + " rule" + (ruleCount == 1 ? "" : "s"),
                Map.of("mode", mode, "rules", String.valueOf(ruleCount), "backend", backend));
        if (llm) {
            mgr.registerVirtual(
                    BackgroundProcessManager.ProcessKind.JUDGE, "judge",
                    "Judge · " + backend,
                    Map.of("backend", backend, "mode", mode));
        }
    }

    private String activityStatus(ActivityItem item) {
        if (item == null) return "unknown";
        if (item.process != null) return item.process.isAlive() ? "running" : "exited";
        return item.status == null || item.status.isBlank() ? "running" : item.status;
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
        try (Stream<String> lines = Files.lines(path, StandardCharsets.UTF_8)) {
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

    private List<String> tailProcessOutput(Path outputFile, int maxLines) {
        if (outputFile == null || maxLines <= 0) return List.of();
        try {
            BackgroundProcessManager.TailResult tail = BackgroundProcessManager.tailOutputFile(outputFile, maxLines);
            List<String> normalized = new ArrayList<>();
            for (String line : tail.lines()) {
                String log = normalizeActivityLog(line);
                if (!log.isBlank()) {
                    normalized.add(log);
                }
            }
            return normalized;
        } catch (Exception ignored) {
            return List.of();
        }
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

    private boolean removeCompletedActivityItem(String id) {
        String wanted = id == null ? "" : id.trim();
        if (wanted.isBlank()) return false;
        synchronized (activityLock) {
            return removeCompletedActivity(backgroundActivities, wanted)
                    || removeCompletedActivity(subagentActivities, wanted);
        }
    }

    private int clearCompletedActivityItems() {
        synchronized (activityLock) {
            return clearCompletedActivity(backgroundActivities) + clearCompletedActivity(subagentActivities);
        }
    }

    private boolean removeCompletedActivity(Deque<ActivityItem> items, String key) {
        Iterator<ActivityItem> iterator = items.descendingIterator();
        while (iterator.hasNext()) {
            ActivityItem item = iterator.next();
            if (key.equals(item.key) && !item.active()) {
                iterator.remove();
                if (tui != null) tui.getStatusBar().unregisterSubagent(item.key);
                return true;
            }
        }
        return false;
    }

    private int clearCompletedActivity(Deque<ActivityItem> items) {
        int removed = 0;
        Iterator<ActivityItem> iterator = items.iterator();
        while (iterator.hasNext()) {
            ActivityItem item = iterator.next();
            if (!item.active()) {
                iterator.remove();
                removed++;
                if (tui != null) tui.getStatusBar().unregisterSubagent(item.key);
            }
        }
        return removed;
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
            case "enter", "inspect", "open", "status" -> {
                activityMenuOpen = true;
                String id = !value.isBlank() ? value : selectedActivityId;
                if (id == null || id.isBlank()) {
                    activityMenuMessage = "usage: /activity enter <id>";
                    redrawActivityPanelOnly();
                    safePrintln(DIM + "  Usage: /activity enter <id>" + RESET);
                } else {
                    enterActivityItem(id);
                    activityMenuMessage = "entered " + id;
                    redrawActivityPanelOnly();
                }
            }
            case "logs", "log", "output", "view" -> {
                activityMenuOpen = true;
                String id = !value.isBlank() ? value : selectedActivityId;
                if (id == null || id.isBlank()) {
                    activityMenuMessage = "usage: /activity logs <id>";
                    redrawActivityPanelOnly();
                    safePrintln(DIM + "  Usage: /activity logs <id>" + RESET);
                } else {
                    printActivityLogs(id);
                    activityMenuMessage = "showing logs for " + id;
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
            case "remove", "rm", "delete" -> {
                activityMenuOpen = true;
                if (value.isBlank()) {
                    activityMenuMessage = "usage: /activity remove <id>";
                    redrawActivityPanelOnly();
                    safePrintln(DIM + "  Usage: /activity remove <id>" + RESET);
                } else {
                    boolean removed = removeCompletedActivityItem(value);
                    activityMenuMessage = removed ? "removed " + value : "no completed activity for " + value;
                    redrawActivityPanelOnly();
                    safePrintln((removed ? GREEN : YELLOW) + "  " + activityMenuMessage + RESET);
                }
            }
            case "clear", "clean" -> {
                activityMenuOpen = true;
                int removed = clearCompletedActivityItems();
                activityMenuMessage = "cleared " + removed + " completed";
                redrawActivityPanelOnly();
                safePrintln(GREEN + "  " + activityMenuMessage + RESET);
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
        safePrintln(DIM + "  Use /activity enter <id>, logs <id>, kill <id>, remove <id>, clear, or close." + RESET);
        safePrintln("");
    }

    private void enterActivityItem(String id) {
        ActivityMenuItem item = findActivityMenuItem(id);
        safePrintln("");
        if (item == null) {
            safePrintln(YELLOW + "  Activity not found: " + id + RESET);
            safePrintln("");
            return;
        }
        safePrintln(BOLD + CYAN + "  Activity: " + item.id + RESET);
        safePrintln("  kind: " + item.kind + " · status: " + item.status);
        safePrintln("  label: " + item.label);
        if (item.agentName != null && !item.agentName.isBlank()) {
            safePrintln("  agent: " + item.agentName);
        }
        if (item.roleName != null && !item.roleName.isBlank()) {
            safePrintln("  role: " + item.roleName);
        }
        if (item.parentId != null && !item.parentId.isBlank()) {
            safePrintln("  parent: " + item.parentId);
        }
        if (item.childCount > 0) {
            safePrintln("  children: " + item.childCount);
        }
        if (item.outputPath != null && !item.outputPath.isBlank()) {
            safePrintln(DIM + "  output: " + item.outputPath + RESET);
        }
        safePrintln(DIM + "  commands: /activity enter " + item.id
                + " · /activity logs " + item.id
                + (item.killable ? " · /activity kill " + item.id : "") + RESET);
        printActivityLogLines(item);
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
        printActivityLogLines(item);
        safePrintln("");
    }

    private void printActivityLogLines(ActivityMenuItem item) {
        List<String> logs = item.logs == null ? List.of() : item.logs;
        if (logs.isEmpty()) {
            safePrintln(DIM + "  No logs captured yet." + RESET);
        } else {
            safePrintln(DIM + "  logs:" + RESET);
            for (String log : logs) {
                safePrintln("  " + log);
            }
        }
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

    private void redrawActivityPanelOnly() {
        synchronized (drawLock) {
            if (terminal != null && scrollBottom > 0) {
                boolean activePrompt = activeLineReader != null && !busyInputActive;
                if (activePrompt) hideCursor();
                saveCursor();
                renderActivityPanelLocked();
                restoreCursor();
                if (activePrompt) showCursor();
                System.out.flush();
            }
        }
        // Keep KompileTui's bottom StatusBar (consolidated metrics) in sync.
        if (tui != null) {
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
                String delegationLabel = summarizeDelegationLabel(toolUse.name(), toolUse.input());
                String delegationLog = summarizeDelegationDetails(toolUse.name(), toolUse.input());
                ActivityItem item = new ActivityItem(key, delegationLabel, delegationLog);
                item.addLogRecord("tool: " + firstNonBlankText(toolUse.name(), "subagent"));
                subagentActivities.addLast(item);
                trimActivity(subagentActivities, 5);
                if (tui != null) tui.getStatusBar().registerSubagent(key, "agent", delegationLabel);
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
            appendToLatestActiveActivity(backgroundActivities, normalizedLog);
            appendToLatestActiveActivity(subagentActivities, normalizedLog);
        }
        redrawActivityPanel();
    }

    private void trackToolActivityComplete(String toolName, String output, boolean error) {
        if (!error) {
            trackTodoActivity(toolName, output);
        }
        String normalizedLog = normalizeActivityLog(output);
        synchronized (activityLock) {
            ActivityItem agentItem = updateLatestActivityStatus(subagentActivities,
                    toolActivityPrefix("agent", toolName), normalizedLog, error ? "failed" : "completed");
            if (agentItem != null && tui != null) tui.getStatusBar().unregisterSubagent(agentItem.key);

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
        boolean updated = false;
        synchronized (activityLock) {
            if (!backgroundActivities.isEmpty()) {
                backgroundActivities.peekLast().addLog(normalizedLog);
                updated = true;
            }
        }
        // Only repaint when an actual background activity changed. A foreground
        // turn has no activity row to update, and repainting there would emit
        // cursor-control bytes into the scroll region mid-stream.
        if (updated) {
            redrawActivityPanel();
        }
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
            JsonNode node = objectMapper.readTree(input);
            return applyTodoJsonMutation(node);
        } catch (Exception ignored) {
            return false;
        }
    }

    private boolean applyTodoJsonMutation(JsonNode node) {
        if (node == null || node.isNull()) return false;
        if (node.isArray()) {
            boolean changed = false;
            for (JsonNode item : node) {
                changed |= applyTodoJsonMutation(item);
            }
            return changed;
        }
        if (!node.isObject()) return false;

        String action = firstNonBlank(node, "action", "operation", "op").toLowerCase(Locale.ROOT);
        JsonNode todosNode = node.get("todos");
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

    private void addTodoNodes(JsonNode todosNode) {
        if (todosNode == null || todosNode.isNull()) return;
        if (todosNode.isArray()) {
            for (JsonNode item : todosNode) {
                putTodoNode(item, "pending");
            }
        } else {
            putTodoNode(todosNode, "pending");
        }
    }

    private boolean putTodoNode(JsonNode node, String defaultStatus) {
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

    private boolean updateTodoNode(JsonNode node, String action) {
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

    private boolean removeTodoNode(JsonNode node) {
        if (node == null || !node.isObject()) return false;
        String id = normalizeTodoId(firstNonBlank(node, "task_id", "taskId", "id"));
        String content = todoContent(node);
        TodoActivityItem item = findTodoItem(id, content);
        if (item == null) return false;
        activeTodos.remove(item.key);
        return true;
    }

    private boolean applyTodoTextMutation(String input) {
        String raw = input == null ? "" : AnsiConstants.stripAnsi(input).replace("\r\n", "\n").replace('\r', '\n');
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

    private boolean hasTodoContent(JsonNode node) {
        return !todoContent(node).isBlank();
    }

    private String todoContent(JsonNode node) {
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

    private String firstNonBlank(JsonNode node, String... fields) {
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
            if (matchesActivityPrefix(item.key, prefix) && item.active()) {
                item.addLog(normalizedLog);
                return;
            }
        }
    }

    private ActivityItem updateLatestActivityStatus(Deque<ActivityItem> items, String prefix, String log, String status) {
        if (prefix == null || prefix.isBlank()) return null;
        String normalizedLog = normalizeActivityLog(log);
        Iterator<ActivityItem> iterator = items.descendingIterator();
        while (iterator.hasNext()) {
            ActivityItem item = iterator.next();
            if (matchesActivityPrefix(item.key, prefix) && item.active()) {
                if (!normalizedLog.isBlank()) item.addLog(normalizedLog);
                item.status = status == null || status.isBlank() ? item.status : status;
                return item;
            }
        }
        return null;
    }

    private boolean appendToLatestActiveActivity(Deque<ActivityItem> items, String log) {
        String normalizedLog = normalizeActivityLog(log);
        if (normalizedLog.isBlank()) return false;
        Iterator<ActivityItem> iterator = items.descendingIterator();
        while (iterator.hasNext()) {
            ActivityItem item = iterator.next();
            if (item.active()) {
                item.addLog(normalizedLog);
                return true;
            }
        }
        return false;
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

    private String summarizeDelegationLabel(String name, String input) {
        String tool = TerminalRenderer.prettifyToolName(name == null ? "subagent" : name);
        try {
            JsonNode node = objectMapper.readTree(input == null ? "" : input);
            if (node.isObject()) {
                String agent = firstNonBlank(node, "agent", "agentName", "subagent_type", "subagentType", "model", "provider");
                String role = firstNonBlank(node, "role", "roleName");
                String count = firstNonBlank(node, "agent_count", "agentCount", "count");
                if (count.isBlank() && node.path("agents").isArray()) count = String.valueOf(node.path("agents").size());
                String target = !count.isBlank() ? count + " agents" : firstNonBlankText(agent, role, "subagent");
                return truncatePlain(tool + " " + target, 72);
            }
        } catch (Exception ignored) {
            // Fall back to the standard compact label below.
        }
        String detail = summarizeToolInput(input);
        return detail.isBlank() ? truncatePlain(tool, 72) : truncatePlain(tool + " " + detail, 72);
    }

    private String summarizeDelegationDetails(String name, String input) {
        try {
            JsonNode node = objectMapper.readTree(input == null ? "" : input);
            if (node.isObject()) {
                List<String> parts = new ArrayList<>();
                String agent = firstNonBlank(node, "agent", "agentName", "subagent_type", "subagentType", "model", "provider");
                String role = firstNonBlank(node, "role", "roleName");
                String count = firstNonBlank(node, "agent_count", "agentCount", "count");
                if (count.isBlank() && node.path("agents").isArray()) count = String.valueOf(node.path("agents").size());
                String prompt = firstNonBlank(node, "description", "task", "subject", "prompt", "query");
                if (!agent.isBlank()) parts.add("agent=" + agent);
                if (!role.isBlank()) parts.add("role=" + role);
                if (!count.isBlank()) parts.add("count=" + count);
                if (!prompt.isBlank()) parts.add("task=" + prompt.replaceAll("\\s+", " ").trim());
                if (!parts.isEmpty()) return truncatePlain(String.join(" · ", parts), 120);
            }
        } catch (Exception ignored) {
            // Fall back to the standard input summary below.
        }
        return summarizeToolInput(input);
    }

    private String summarizeToolInput(String input) {
        if (input == null || input.isBlank()) return "";
        try {
            JsonNode node = objectMapper.readTree(input);
            if (node.isObject()) {
                String value = firstNonBlank(node, "description", "task", "subject", "command", "cmd", "prompt", "process_id", "processId");
                if (!value.isBlank()) return truncatePlain(value.replaceAll("\\s+", " ").trim(), 80);
            }
        } catch (Exception ignored) {
            // Fall back to sanitized text below.
        }
        return truncatePlain(AnsiConstants.stripAnsi(input).replaceAll("\\s+", " ").trim(), 80);
    }

    private String normalizeActivityLog(String log) {
        if (log == null) return "";
        String cleaned = AnsiConstants.stripAnsi(log).replaceAll("\\s+", " ").trim();
        return truncatePlain(cleaned, 96);
    }

    private void redrawActivityPanel() {
        redrawActivityPanelOnly();
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
        reassertTranscriptMouse();
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
        // Do not ask JLine to redisplay here: the active readLine was entered with the
        // old busy prompt, so REDISPLAY can overwrite Kompile's freshly painted idle prompt.
        // drawIdlePromptLine already preserves the current buffer and cursor position.
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
        drawActivePromptLine(reader, force, true);
    }

    private void drawActivePromptLine(LineReader reader, boolean force, boolean restoreCursorWhenUnchanged) {
        int width = terminal != null && terminal.getWidth() > 0 ? terminal.getWidth() : 120;
        String prompt = buildPrompt();
        String buffer = currentReadLineBuffer(reader);
        int cursor = currentReadLineCursor(reader, buffer);
        String line = fitAnsiLine(prompt + buffer, Math.max(12, width - 1));
        int promptWidth = AnsiConstants.stripAnsi(prompt).length();
        int cursorCol = Math.min(Math.max(1, promptWidth + cursor + 1), Math.max(1, width));
        boolean lineChanged = force || !Objects.equals(line, activePromptLastRenderedLine);
        boolean cursorChanged = force || cursorCol != activePromptLastCursorCol;
        if (lineChanged) {
            System.out.printf("\033[%d;1H\033[2K%s", firstInputRow(), line);
            activePromptLastRenderedLine = line;
        }
        activePromptLastCursorCol = cursorCol;
        if (restoreCursorWhenUnchanged || lineChanged || cursorChanged) {
            System.out.printf("\033[?25h\033[%d;%dH", firstInputRow(), cursorCol);
        }
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
    /** Status line ("kompile [agent] · status …") sits just below the input box. */
    private int statusRow() { return bottomBorderRow() + 1; }
    /** Activity panel (background work, subagents, todos, slash completions) below the status line. */
    private int activityFirstRow() { return statusRow() + 1; }

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
            busyPrompt = "  Enter draft · ↑ edit pending · Ctrl+B background · Esc/Ctrl+C child · Ctrl+G cancel";
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
            forwardAgentInterrupt(new byte[]{0x03});
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

    private void forwardAgentInterrupt(byte[] keySequence) {
        if (sendRawToAgentStdin(keySequence)) {
            updateStatusLine("interrupt sent to " + agent);
            return;
        }
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
        // The reset sequence also disables mouse tracking (?1000l). Callers that
        // return to the idle prompt re-assert it via reassertTranscriptMouse();
        // teardown/startup deliberately leave it off.
        transcriptMouseEnabled = false;
    }

    /**
     * Re-establish Kompile's wheel capture after a {@link #resetHostInputModes()}
     * that returns control to the idle prompt. Safe to call when no decoder owns
     * the screen — it simply stays off.
     */
    private void reassertTranscriptMouse() {
        enableTranscriptMouse();
    }

    /**
     * True when the active agent's output is rendered by Kompile into the
     * scroll transcript, rather than the agent painting the real screen itself.
     * Only then may Kompile own the mouse wheel for transcript scrolling — raw
     * passthrough agents (renderRawTui) keep their native input handling.
     */
    private boolean decoderOwnsScreen() {
        AgentTuiDecoder d = agentDecoder;
        return d != null && !d.renderRawTui();
    }

    /**
     * Enable X10 mouse tracking on the REAL terminal so wheel events are
     * delivered to Kompile (and consumed by the scroll widget) instead of
     * scrolling the host terminal's native scrollback. X10 (?1000h) reports as
     * {@code ESC[M}-prefixed events, matching JLine's {@code key_mouse} binding;
     * coordinates are irrelevant for wheel detection. No-op unless a decoder
     * owns the screen. Idempotent — re-sending the enable sequence is harmless.
     */
    private void enableTranscriptMouse() {
        if (terminal == null || transcriptMouseEnabled || !decoderOwnsScreen()) return;
        try {
            writeRawTerminal("\033[?1000h");
            transcriptMouseEnabled = true;
        } catch (IOException | IOError ignored) {
            // Mouse capture is best-effort; PageUp/PageDown still scroll the transcript.
        }
    }

    /**
     * Force wheel capture back on while the agent is responding. Unlike
     * {@link #enableTranscriptMouse()} this re-sends {@code ?1000h} even when
     * {@code transcriptMouseEnabled} is already set: re-entering JLine's readLine for
     * the response prompt can silently reset the terminal's mouse mode, leaving our
     * flag {@code true} while the wheel has fallen back to the host terminal's native
     * scrollback. Re-asserting keeps wheel scrolling consistent between the idle prompt
     * and an in-progress response. Idempotent and invisible.
     */
    private void forceTranscriptMouseCapture() {
        if (terminal == null || !decoderOwnsScreen()) return;
        try {
            writeRawTerminal("\033[?1000h");
            transcriptMouseEnabled = true;
        } catch (IOException | IOError ignored) {
            // Best-effort; PageUp/PageDown still scroll the transcript.
        }
    }

    /** Disable Kompile's real-terminal mouse tracking, restoring native selection/scroll. */
    private void disableTranscriptMouse() {
        if (terminal == null || !transcriptMouseEnabled) return;
        try {
            writeRawTerminal("\033[?1000l");
        } catch (IOException | IOError ignored) {
        } finally {
            transcriptMouseEnabled = false;
        }
    }

    private void safePrintln(String text) {
        String line = text == null ? "" : text;
        LineReader reader = activeLineReader;
        boolean restoreActivePrompt = reader != null && !busyInputActive;
        synchronized (drawLock) {
            appendScrollbackLineLocked(line);
            boolean keepCursorInInput = busyInputActive;
            boolean redrawFromScrollback = !restoreActivePrompt && !keepCursorInInput;
            if (restoreActivePrompt) {
                hideCursor();
            } else if (redrawFromScrollback) {
                saveCursor();
            } else if (!keepCursorInInput) {
                saveCursor();
            }
            if (tui != null) {
                tui.reestablishScrollRegion();
                scrollBottom = tui.scrollBottom();
                clampScrollViewportOffsetLocked();
            }
            if (scrollViewportOffset == 0 && !redrawFromScrollback) {
                // Move cursor to last row of scroll region and print text.
                System.out.printf("\033[%d;1H\033[2K%s", scrollBottom, line);
                // Newline at the bottom of the scroll region triggers scroll-up.
                System.out.print("\n");
            } else {
                // When no readLine owns the cursor, redraw from Kompile's scrollback instead of
                // depending on terminal scroll-region side effects. JLine can leave the submitted
                // input buffer painted in the viewport; a model-backed repaint clears it.
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
            turns = ConversationReader.readKompileSession(sessionId);
        } catch (Exception e) {
            // Fall through to external sources
        }

        // Try external agent sources
        if (turns == null || turns.isEmpty()) {
            for (String source : List.of("claude-code", "codex", "qwen", "opencode", "gemini")) {
                try {
                    turns = ConversationReader.readExternalSession(source, sessionId);
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
            ArrayNode blocks = turn.rawContentBlocks();

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
    private void replayUserBlocks(ArrayNode blocks,
                                  ChatHistory history, List<String> buf) {
        for (JsonNode block : blocks) {
            String type = block.has("type") ? block.get("type").asText() : "";
            switch (type) {
                case "tool_result" -> {
                    boolean isError = block.has("is_error") && block.get("is_error").asBoolean();
                    String status = isError ? renderer.red("✗ error") : renderer.green("✓");
                    String preview = "";
                    if (block.has("content")) {
                        JsonNode contentNode = block.get("content");
                        if (contentNode.isTextual()) {
                            preview = truncateForReplay(contentNode.asText());
                        } else if (contentNode.isArray()) {
                            for (JsonNode part : contentNode) {
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
    private void replayAssistantBlocks(ArrayNode blocks,
                                       ChatHistory history, List<String> buf) {
        StringBuilder fullText = new StringBuilder();

        for (JsonNode block : blocks) {
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
                    ChatConfig.getPassthroughAgentOrder())));
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
        // Set when the agent surfaces a quota/credit/auth block instead of a response, so the
        // turn ends immediately (with the reason shown) rather than spinning to the idle timeout.
        String blockingNotice = null;

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
        realtimeEnforcerActuatedThisTurn.set(false);
        realtimeEnforcerActuationInFlight.set(false);
        realtimeEnforcerCorrectionSubmittedAt.set(0);
        agentAwaitingInput = false;
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

                // L0: spawn/PTY/env are owned by the Terminal Session Framework's AgentProcess.
                // The shared env base keys + terminal defaults + COLUMNS/LINES live in ScriptAgentProcess;
                // only host-specific env (enforcer) is layered on here.
                AgentLaunchSpec.Builder specBuilder =
                        AgentLaunchSpec.builder(agentCmd, workingDir)
                                .dims(PtyDims.of(ptyRows, ptyCols));
                if (enforcerExtraEnv != null) {
                    specBuilder.env(enforcerExtraEnv);
                }
                AgentProcess agentProcess =
                        new ScriptAgentProcess();
                agentProcess.start(specBuilder.build());

                Process process = agentProcess.process();
                tuiAgentProcess = agentProcess;
                tuiProcess = process;
                activeProcess = process;
                agentStdin = agentProcess.stdin();

                    // Open subprocess log files:
                    //   agent-subprocess.log  — text log (append, human-readable annotations)
                    //   agent-pty-dump.bin    — raw byte dump (overwritten each session)
                    //     Replay: cat <kompile home>/logs/agent-pty-dump.bin
                    //     Hex:    xxd <kompile home>/logs/agent-pty-dump.bin | less
                    try {
                        Path logDir = BackgroundProcessManager.locateOutputRoot(Path.of(workingDir))
                                .resolve("logs");
                        Files.createDirectories(logDir);
                    Path logFile = logDir.resolve("agent-subprocess.log");
                    subprocessLogWriter = new BufferedWriter(
                            new FileWriter(logFile.toFile(), true));
                    subprocessLogWriter.write("\n--- " + agent + " subprocess started at "
                            + java.time.Instant.now() + " ---\n");
                    subprocessLogWriter.flush();
                    // Raw binary dump — overwrite per session so it stays manageable
                    Path dumpFile = logDir.resolve("agent-pty-dump.bin");
                    subprocessPtyDump = new BufferedOutputStream(
                            new FileOutputStream(dumpFile.toFile()));
                } catch (IOException e) {
                    // Non-fatal — log files are optional
                }

                // Create the agent-specific decoder for VT response generation
                // and content extraction (history/logging). The decoder knows
                // which terminal queries this agent needs answered and which
                // would cause problems (e.g. Kitty keyboard → nano in Claude Code).
                agentDecoder = AgentTuiDecoder.forAgent(agent);
                tuiQueryStripper.reset();
                // Wire the L2 render policies now that the decoder is known; renderRawTui() selects
                // the default (raw for self-painting TUIs, otherwise mirror/decoded per /render).
                initRenderPolicies();

                // Create a VirtualTerminal to shadow the subprocess screen state.
                // Raw passthrough handles rendering — the VT is only used by the
                // decoder for cursor position tracking (DSR responses) and
                // content extraction for history/logging.
                if (virtualTerminal == null) {
                    virtualTerminal = new VirtualTerminal(ptyRows, ptyCols);
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
                final AgentTuiDecoder decoder = agentDecoder;
                final VirtualTerminal vt = virtualTerminal;
                tuiOutputReader = new Thread(() -> {
                    try {
                        InputStream is = process.getInputStream();
                        byte[] buf = new byte[8192];
                        int n;
                        while ((n = is.read(buf)) != -1) {
                            if (Thread.currentThread().isInterrupted()) break;
                            // Raw binary dump — exact bytes for replay/analysis
                            OutputStream dump = subprocessPtyDump;
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

                            // Detect a mid-turn decision prompt so the REPL keeps the turn alive and
                            // routes the user's typed answer / arrow keys to the agent. Only while a
                            // turn is active (startup trust/MCP prompts are auto-handled above).
                            // The state is DEBOUNCED: the agent redraws its menu on each arrow key,
                            // briefly not matching the prompt pattern — clearing on that flicker would
                            // interrupt navigation and wrongly end the turn. So it's held until the
                            // agent is clearly generating again or the prompt has been gone a while.
                            // A full-screen picker (alternate screen — claude's /model, /agents…) is
                            // ALWAYS an input dialog and CANNOT be shown through the decoded transcript:
                            // its cursor-addressed drawing decodes to "[C[C…" garbage. Mirror it as soon
                            // as the agent switches to the alternate screen; the mirror IS the picker, so
                            // no text banner (that's only for inline numbered menus).
                            // Detect a dialog by its AFFORDANCE (numbered menu / y-n / confirm), NOT by the
                            // alternate-screen flag: claude does NOT emit an alt-screen-leave when a picker
                            // is dismissed, so isInAlternateScreen() stays stuck true and would wedge every
                            // later turn at "awaiting input". isAwaitingUserInput goes false the instant the
                            // agent shows normal output again. Gate on an ACTIVE turn so a Kompile hard-cancelled
                            // or finished turn never re-asserts it while stale picker pixels remain on screen.
                            boolean turnActive = agentBusy && !cancelSignal.get();
                            boolean awaitingNow = turnActive && decoder.isAwaitingUserInput(vt);
                            long awaitCheckMs = System.currentTimeMillis();
                            if (awaitingNow) {
                                lastAwaitingAt = awaitCheckMs;
                                // A full-screen picker (alternate screen) can't be shown through the decoded
                                // transcript — its cursor addressing decodes to "[C[C…" garbage — so mirror
                                // the agent's real screen (the mirror IS the picker, no text banner). An
                                // inline numbered menu decodes fine and gets the explicit text banner.
                                boolean fullScreen = vt.isInAlternateScreen();
                                if (fullScreen) enterMirrorForDialog();
                                if (!agentAwaitingInput) {
                                    agentAwaitingInput = true;
                                    if (!fullScreen) onAwaitingInputChanged(true);
                                }
                            } else if (agentAwaitingInput
                                    && (decoder.isResponding(vt) || awaitCheckMs - lastAwaitingAt > 900)) {
                                agentAwaitingInput = false;
                                onAwaitingInputChanged(false);
                                exitMirrorForDialog();
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

                            // L2 render dispatch (WP6): the RenderPolicy strategy decides HOW this
                            // frame reaches the terminal. RAW forwards bytes verbatim (agent owns the
                            // screen). MIRROR/DECODED settle-gate first — a single repaint can arrive
                            // across several reads, so is.available()==0 alone catches jumbled
                            // mid-redraw frames; wait for the screen to SETTLE, then render one
                            // coherent frame (hash-deduped, time-capped so the UI stays live). Mirror
                            // uses a longer window/cap (verbatim blit must be coherent); decoded
                            // filters jumbles downstream so it can be snappier.
                            RenderPolicy policy = renderPolicy;
                            if (policy == null) policy = selectDefaultRenderPolicy();
                            if (policy.isRaw()) {
                                policy.applyRaw(displayBytes);
                            } else {
                                final InputStream fis = is;
                                boolean settled = frameSettleGate.awaitSettle(
                                        () -> { try { return fis.available() > 0; } catch (IOException e) { return false; } },
                                        policy.settleWindowMs());
                                long nowMs = System.currentTimeMillis();
                                if (frameSettleGate.shouldRender(settled, vt.getScreenHash(), nowMs, policy.timeCapMs())) {
                                    policy.applyFrame(vt);
                                }
                            }
                        }
                    } catch (IOException e) {
                        // Stream closed — expected on shutdown
                    } finally {
                        // Flush and close the dump on stream end
                        OutputStream dump = subprocessPtyDump;
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
            // Publish this thread so Kompile hard-cancel can interrupt the 200ms poll sleep
            // immediately instead of waiting up to a full tick to notice cancelSignal.
            waitingThread = Thread.currentThread();
            while (tuiProcess.isAlive() && !cancelSignal.get()) {
                // Keep Kompile's wheel capture asserted for the whole response. Re-entering
                // readLine for the response prompt (JLine re-inits terminal modes) can reset
                // the terminal's mouse tracking, which would let the wheel scroll the host
                // terminal's native scrollback instead of the managed transcript — the cause
                // of scrolling behaving inconsistently while the agent is responding.
                forceTranscriptMouseCapture();
                // Bail out the moment the agent shows a quota/credit/auth block — otherwise the
                // turn spins silently to the 45s no-content timeout. Gate on the agent NOT actively
                // responding: a block makes the agent stop (idle), whereas a live answer keeps
                // isResponding() true — so this catches a late-appearing block (codex flashes a
                // "Working" spinner first, which is why the old !tuiTurnSawContent guard missed it)
                // without truncating a mid-stream response. The phrases are specific error wording,
                // so a normal answer won't trip it.
                if (agentDecoder != null && virtualTerminal != null
                        && !agentAwaitingInput
                        && !agentDecoder.isResponding(virtualTerminal)) {
                    String notice = agentDecoder.detectBlockingNotice(virtualTerminal);
                    if (notice == null) {
                        String rendered = tuiLastDecodedContent;
                        if (rendered != null && !rendered.isEmpty()) {
                            notice = agentDecoder.detectBlockingNoticeInText(rendered);
                        }
                    }
                    if (notice != null) {
                        blockingNotice = notice;
                        break;
                    }
                }
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
            waitingThread = null;
            spinner.stop();
            // Restore kompile's scroll region and input area after the
            // subprocess TUI was rendering directly to the terminal.
            if (tui != null) {
                tui.reestablishScrollRegion();
                scrollBottom = tui.scrollBottom();
            }
            resetHostInputModes();
            reassertTranscriptMouse();
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

        if (blockingNotice != null) {
            safePrintln();
            safePrintln(renderer.yellow("  ⚠ " + agent + " is blocked: " + blockingNotice));
            safePrintln(renderer.dim("    No response this turn — resolve the limit/credits/login above, "
                    + "then retry. Switch agents with /agent, or /quit."));
            history.logSystem(agent + " blocked (quota/auth): " + blockingNotice);
            if (tui != null && tuiSubagentId != null) {
                tui.getStatusBar().updateSubagentStatus(tuiSubagentId, "blocked");
            }
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

        String finalDecodedText = fullText.toString();
        tuiFullText = null;
        tuiToolCalls = null;
        tuiPendingText = null;
        tuiSpinner = null;
        tuiSpinnerStopped = null;
        if (!finalDecodedText.isBlank() && agentDecoder != null && !agentDecoder.renderRawTui()) {
            // BUG 7 fix: flushDecodedTuiRemainder (called above) already called
            // updateLiveDecoderScrollbackBlock(extracted, true) with the structured decoder
            // renderHistory(), which preserves [tool:…] panels and proper formatting.
            // Calling it again here with the raw accumulated tuiFullText would overwrite that
            // structured block with plain delta text, losing tool-panel structure and potentially
            // re-duplicating content.  Gate this call so it only fires when the flush did NOT
            // already populate the live block (liveDecoderScrollbackLength == 0).
            boolean flushAlreadyPopulated;
            synchronized (drawLock) {
                flushAlreadyPopulated = liveDecoderScrollbackLength > 0;
            }
            if (!flushAlreadyPopulated) {
                updateLiveDecoderScrollbackBlock(finalDecodedText, true);
            }
        }
        finishLiveDecoderScrollbackBlock();

        if (tui != null && tuiSubagentId != null) {
            tui.getStatusBar().updateSubagentStatus(tuiSubagentId, "idle");
        }

        firstMessageSent = true;
        if (agentDecoder == null || agentDecoder.renderRawTui()) {
            safePrintln();
        }
        renderer.setTerminalTitle("kompile [" + agent + "]");
        return fullText.toString();
    }


    /**
     * Announce that the agent is (no longer) waiting on the user for a decision. When it starts
     * waiting we drop a one-line hint into the transcript and flip the status so the user knows to
     * type their answer; the answer itself is forwarded by the REPL loop.
     */
    private void onAwaitingInputChanged(boolean awaiting) {
        if (awaiting) {
            currentStatus = "awaiting input";
            if (tui != null && tuiSubagentId != null) {
                tui.getStatusBar().updateSubagentStatus(tuiSubagentId, "awaiting input");
            }
            safePrintln("");
            safePrintln(renderer.yellow("  ▸ " + agent + " is waiting for your answer:"));
            // Show the choices explicitly so they're always clear, independent of the transcript.
            String prompt = agentDecoder != null ? agentDecoder.extractPromptText(virtualTerminal) : "";
            if (prompt != null && !prompt.isBlank()) {
                for (String pl : prompt.split("\n")) safePrintln(renderer.dim("    " + pl));
                safePrintln(renderer.dim("    → type the option number, or ↑↓/←→/Tab to navigate,"
                        + " then Enter (or /keys for full keyboard control)"));
            } else {
                safePrintln(renderer.dim("    type your answer below and press Enter"
                        + " (or /keys for full keyboard control)"));
            }
            updateStatusLine("awaiting input");
        }
        // When it clears, the turn resumes and processDecodedTuiScreen restores the running status.
    }

    private boolean decodedTuiTurnComplete(AgentTuiDecoder decoder,
                                           long messageSentAt) {
        if (decoder == null || virtualTerminal == null) return false;
        // The agent has paused to ask the user something — the turn is NOT done, it is blocked on
        // input. Keep it alive so the idle detector doesn't end the turn out from under the prompt.
        if (agentAwaitingInput) return false;
        if (realtimeEnforcerActuationInFlight.get()) return false;
        long now = System.currentTimeMillis();
        long activityAt = Math.max(messageSentAt, lastOutputTime.get());
        long correctionSubmittedAt = realtimeEnforcerCorrectionSubmittedAt.get();
        if (correctionSubmittedAt > 0) {
            activityAt = Math.max(activityAt, correctionSubmittedAt);
        }
        long quietFor = now - activityAt;
        boolean sawContent = tuiTurnSawContent.get() || tuiLastDecodedAt.get() >= messageSentAt;
        // L4 turn-idle decision (WP8) — the shared, unit-tested detector; the god-class supplies the
        // timing/state and the decoder its idle/responding verdicts.
        return TurnIdleDetector.turnComplete(
                sawContent,
                decoder.isIdle(virtualTerminal),
                decoder.isResponding(virtualTerminal),
                quietFor,
                decoder.turnIdleMillis(),
                now - messageSentAt);
    }

    private void stopTuiSpinnerForDecodedContent() {
        TerminalRenderer.SpinnerHandle spinner = tuiSpinner;
        AtomicBoolean stopped = tuiSpinnerStopped;
        if (spinner != null && stopped != null && stopped.compareAndSet(false, true)) {
            spinner.stop();
            safePrintln();
        }
    }

    // ── L2 render policy (WP6) ───────────────────────────────────────────────

    /**
     * Build the three RenderPolicy strategy objects once (raw byte-forward, mirror blit, decoded
     * transcript) wiring each to the host render method, then select the default for this agent.
     * The render bodies stay here (they are entangled with chrome geometry, the draw lock, and
     * status/cursor restore); the policy just names the mode, carries the settle windows, and
     * dispatches to the right body.
     */
    private void initRenderPolicies() {
        if (rawPolicy == null) {
            rawPolicy = RenderPolicy.raw(this::renderRawDisplayBytes);
            mirrorPolicy = RenderPolicy.mirror(this::mirrorVtToScrollRegion);
            decodedPolicy = RenderPolicy.decoded(
                    vt -> processDecodedTuiScreen(agentDecoder, vt));
        }
        renderPolicy = selectDefaultRenderPolicy();
    }

    /**
     * The default render policy for the active agent: RAW when the decoder paints its own
     * full-screen TUI ({@code renderRawTui()}), otherwise MIRROR or DECODED per the {@code /render}
     * toggle. This is where {@code AgentTuiDecoder.renderRawTui()} acts as the default-policy
     * selector (design §4.3) rather than an inline branch in the pump.
     */
    private RenderPolicy selectDefaultRenderPolicy() {
        if (rawPolicy == null) initRenderPolicies();
        if (agentDecoder != null && agentDecoder.renderRawTui()) return rawPolicy;
        return mirrorRender ? mirrorPolicy : decodedPolicy;
    }

    /** RAW sink: forward the query-stripped display bytes verbatim to the real terminal fd. */
    private void renderRawDisplayBytes(byte[] displayBytes) {
        if (displayBytes == null || displayBytes.length == 0) return;
        synchronized (drawLock) {
            try {
                OutputStream out = terminal.output();
                out.write(displayBytes);
                out.flush();
            } catch (IOException | IOError ignored) {
                // Terminal closed — the pump's read loop will end on the next read.
            }
        }
    }

    /**
     * Mirror mode: blit the agent's VirtualTerminal screen verbatim into Kompile's scroll
     * region (rows scrollTop..scrollBottom), preserving the agent's exact layout/styling and
     * cursor. No content extraction or transcript merge — the agent paints its own UI; Kompile
     * keeps only the top bar and bottom status bar. This is the "defer rendering to the agent
     * CLI" path that avoids the decoder-merge bug class entirely.
     */
    private void mirrorVtToScrollRegion(VirtualTerminal vt) {
        if (vt == null || terminal == null) return;
        // Mirror mode renders the AGENT's screen into the scroll region but keeps Kompile's own
        // input box + chrome, and the turn still runs through the normal dispatch (agentBusy +
        // wait loop). processDecodedTuiScreen is skipped here, so feed the wait loop the
        // "saw content" signal and reflect the agent's responding/idle state in Kompile's status.
        AgentTuiDecoder dec = agentDecoder;
        if (dec != null && tuiFullText != null) {
            boolean responding = dec.isResponding(vt);
            if (responding) tuiTurnSawContent.set(true);
            // Don't let the per-frame render overwrite the "awaiting input" state with "idle".
            String status = agentAwaitingInput ? "awaiting input" : (responding ? "responding" : "idle");
            if (!status.equals(currentStatus)) updateStatusLine(status);
            if (tui != null && tuiSubagentId != null) {
                tui.getStatusBar().updateSubagentStatus(tuiSubagentId, status);
            }
        }
        synchronized (drawLock) {
            int top = tui != null ? tui.scrollTop() : 2;
            int bottom = scrollBottom;
            int regionRows = Math.max(0, bottom - top + 1);
            // Same code path the headless framebuffer harness exercises (MirrorRenderer),
            // written through JLine's raw terminal fd so escape bytes reach the real terminal.
            LineReader promptReader = activeLineReader;
            boolean activePrompt = promptReader != null && !busyInputActive;
            try {
                String blit = MirrorRenderer.buildMirrorBlit(vt, top, regionRows);
                // Mirror blits move through the child screen. Save/restore the host cursor around
                // the blit so unchanged prompt frames do not visibly bounce between child rows and
                // Kompile's input box on every agent repaint.
                writeRawTerminal(activePrompt ? "\0337" + blit + "\0338\033[?25h" : blit);
            } catch (IOException | IOError ignored) {
                return;
            }
            // Keep the REAL cursor in Kompile's input box — the user types THERE, not in the
            // agent's mirrored box. The agent's caret is just a rendered glyph in the mirror above;
            // we deliberately do NOT steal the terminal cursor for it.
            if (activePrompt) {
                drawActivePromptLine(promptReader, false, false);
            } else {
                drawFixedInputBox(true);
            }
            System.out.flush();
        }
    }

    /**
     * Mirror-mode input: raw keystrokes go straight to the agent, which owns its own input
     * box (shown in the mirror). Ctrl-\ drops to a single Kompile command (the caller runs
     * one readLine, then re-enters this loop). Returns true on Ctrl-\, false when mirror mode
     * ended or the agent/shell is shutting down. The 150ms read timeout re-checks liveness so
     * this can never wedge.
     */
    private boolean runMirrorInputLoop() {
        if (terminal == null || agentStdin == null) return false;
        Attributes prev = null;
        try {
            prev = terminal.enterRawMode();
            NonBlockingReader in = terminal.reader();
            if (!mirrorInputPrimed) {
                // Stop the REAL terminal emitting focus/bracketed-paste events that would be
                // forwarded to the agent and echoed as garbage (^[[I, ^[[?...); drain any
                // leftover query responses sitting on stdin so they don't reach the agent.
                try { writeRawTerminal("\033[?1004l\033[?2004l"); } catch (IOException | IOError ignored) {}
                try {
                    int d;
                    while ((d = in.read(5L)) != NonBlockingReader.READ_EXPIRED && d >= 0) { /* drain */ }
                } catch (IOException ignored) {}
                mirrorInputPrimed = true;
            }
            while (mirrorRender && tuiProcess != null && tuiProcess.isAlive() && !shutdownSignal.get()) {
                int c = in.read(150L);
                if (c == NonBlockingReader.READ_EXPIRED) continue;
                if (c < 0) break;
                if (c == 0x1C) return true;     // Ctrl-\ → one Kompile command
                // Drop terminal-generated noise the agent shouldn't see: focus in/out
                // (ESC[I / ESC[O) and device/mode query responses (ESC[?...). Real key
                // sequences (arrows ESC[A.. etc.) are NOT ESC[I/ESC[O/ESC[?, so they pass.
                if (c == 0x1B) {
                    int c1 = in.read(20L);
                    if (c1 == '[') {
                        int c2 = in.read(20L);
                        if (c2 == 'I' || c2 == 'O') continue;                 // focus in/out — drop
                        if (c2 == '?') {                                      // device/mode response — drop
                            int q;
                            while ((q = in.read(20L)) >= 0 && !(q >= 0x40 && q <= 0x7E)) { /* skip params */ }
                            continue;
                        }
                        sendRawToAgentStdin(new byte[]{0x1B, '[', (byte) c2});
                        continue;
                    }
                    sendRawToAgentStdin(c1 >= 0 ? new byte[]{0x1B, (byte) c1} : new byte[]{0x1B});
                    continue;
                }
                if (Character.isHighSurrogate((char) c)) {
                    int lo = in.read(50L);
                    if (lo >= 0 && Character.isLowSurrogate((char) lo)) {
                        sendRawToAgentStdin(new String(new char[]{(char) c, (char) lo})
                                .getBytes(StandardCharsets.UTF_8));
                        continue;
                    }
                    sendRawToAgentStdin(encodeInputChar(c));
                    if (lo >= 0) sendRawToAgentStdin(encodeInputChar(lo));
                    continue;
                }
                sendRawToAgentStdin(encodeInputChar(c));
            }
        } catch (IOException | RuntimeException ignored) {
            // Any failure just ends the mirror input loop cleanly.
        } finally {
            if (prev != null) {
                try { terminal.setAttributes(prev); } catch (RuntimeException ignored) {}
            }
        }
        return false;
    }

    private synchronized void processDecodedTuiScreen(AgentTuiDecoder decoder,
                                                      VirtualTerminal vt) {
        if (decoder == null || vt == null) return;
        if (tuiFullText == null) return;
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
        // Keep the cursor in Kompile's input box: the live-block redraw + the spinner-stop
        // safePrintln above can leave it parked in the content area while the user is typing
        // at the prompt. Reposition (no forced line redraw → no flicker) on every frame.
        LineReader promptReader = activeLineReader;
        if (promptReader != null && !busyInputActive) {
            synchronized (drawLock) { drawActivePromptLine(promptReader, false); }
        }
    }

    private synchronized void flushDecodedTuiRemainder(StringBuilder fullText,
                                                       TerminalRenderer.SpinnerHandle spinner,
                                                       AtomicBoolean spinnerStopped) {
        AgentTuiDecoder decoder = agentDecoder;
        if (decoder == null || decoder.renderRawTui() || virtualTerminal == null) return;
        decoder.observe(virtualTerminal);
        String extracted = decoder.renderHistory();
        if (extracted == null || extracted.isBlank()) extracted = decoder.extractContent(virtualTerminal);
        if (extracted == null || extracted.isBlank()) {
            flushDecodedMarkdownBuffer(true);
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
            // BUG 3 fix: the content has diverged — current neither appends to nor is contained by
            // what we have already rendered.  Returning 'current' (the full decoder history) here
            // caused emitDecodedTuiText to append the ENTIRE history to tuiFullText even though
            // earlier partial deltas were already accumulated there, producing visible duplicates by
            // turn end.  previous is non-blank in this branch (otherwise the first branch fires),
            // so we have already rendered some content; prefer a safe no-op over duplication.
            // The structured scrollback block is updated separately with the full 'extracted' value,
            // and flushDecodedTuiRemainder handles any genuinely-remaining tail at turn end.
            return "";
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
        StringBuilder filtered = new StringBuilder();
        String[] lines = text.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String line = stripTrailingWhitespace(lines[i]);
            String stripped = line.trim();
            if (!stripped.isEmpty()) {
                if (isSentMessageEcho(stripped)) {
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

    // The legacy scrape→render path (processInteractiveOutputChunk / processTuiOutputChunk) was
    // removed with WP5: it was the sole production caller of VirtualTerminal.getNewText() and had
    // no callers of its own — the live pump renders via the mirror/decoded render policies (the
    // 2026-06-20 raw-passthrough direction) rather than re-scraping the VT into markdown. Its VT
    // query-answering was a dead duplicate of the pump's decoder.buildResponses path.

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
        // (e.g., text + AskUserQuestion in the same assistant message).
        // Strip ANSI escape sequences first for structured agents: the script(1) PTY
        // wrapper injects cursor-positioning codes (\033[...H, \033[2K, etc.) into JSON
        // lines, which would otherwise make objectMapper.readTree() throw and the event
        // be silently discarded.
        String parseLine = isStructuredAgent(agentLower) ? AnsiConstants.stripAnsi(line) : line;
        List<PassthroughStreamParser.PassthroughEvent> events = parseAgentLineMulti(agentLower, parseLine);

        if (events.isEmpty()) {
            // No structured parser matched — fall back to ANSI-stripped markdown-rendered text
            if (!isStructuredAgent(agentLower)) {
                String cleaned = AnsiConstants.stripAnsi(line).trim();
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
                // Fold the agent's native id into the unified SessionIdentity (F8).
                if (sessionIdentity != null) {
                    sessionIdentity = sessionIdentity.withAgentNativeSessionId(si.sessionId());
                }
                // Durably record the native id so the resume tool can map this kompile
                // session back to the agent's own session (logHarvestedSource dedups).
                if (sessionHistory != null) {
                    sessionHistory.logHarvestedSource(si.sessionId());
                }
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
            // Index the tool call live (not just via post-hoc transcript harvest) so emulated
            // passthrough sessions surface in the MCP Hub tool-call catalog like native passthrough.
            ToolCallIndex.getInstance().record(
                    metrics.getSessionId(), tu.name(),
                    tu.input() != null ? tu.input().toString() : "",
                    agent, "emulated-passthrough", false, 0,
                    System.getProperty("user.dir"));
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
                // Truncate the on-screen preview so a large JSON tool output can't wrap
                // past the scroll region into the input box. Full output still goes to the log above.
                safePrintln(renderer.dim("  " + TerminalRenderer.truncatePreview(output, 200)));
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
                // Truncate the on-screen preview so a large JSON tool output can't wrap
                // past the scroll region into the input box. Full output still goes to the log above.
                safePrintln(renderer.dim("  " + TerminalRenderer.truncatePreview(output, 200)));
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

    /**
     * Encode one decoded input code unit (from JLine's {@code NonBlockingReader}, which returns
     * Unicode not raw bytes) as UTF-8 for the agent's stdin. A bare {@code (byte) c} cast truncates
     * anything above U+007F — corrupting accented Latin, CJK, and other IME input into a single
     * Latin-1 byte. ASCII and control bytes (ESC, Ctrl keys) are unchanged.
     */
    private static byte[] encodeInputChar(int c) {
        if (c < 0x80) return new byte[]{(byte) c};
        return new String(new char[]{(char) c}).getBytes(StandardCharsets.UTF_8);
    }

    private interface ManagedAgentInputActuator {
        boolean sendEsc();
        boolean awaitIdle(long timeoutMillis);
        boolean sendTextAndSubmit(String text);
    }

    private ManagedAgentInputActuator managedAgentInputActuator() {
        return new ManagedAgentInputActuator() {
            @Override
            public boolean sendEsc() {
                return forwardEscapeToAgent();
            }

            @Override
            public boolean awaitIdle(long timeoutMillis) {
                return awaitAgentIdleForRealtimeCorrection(timeoutMillis);
            }

            @Override
            public boolean sendTextAndSubmit(String text) {
                boolean sent = forwardPromptAnswer(text == null ? "" : text);
                if (sent) {
                    long now = System.currentTimeMillis();
                    realtimeEnforcerCorrectionSubmittedAt.set(now);
                    lastOutputTime.set(now);
                    updateStatusLine("correcting");
                    if (tui != null && tuiSubagentId != null) {
                        tui.getStatusBar().updateSubagentStatus(tuiSubagentId, "correcting");
                    }
                }
                return sent;
            }
        };
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

    /**
     * Accept short menu/confirmation answers during the debounce window after an agent prompt was
     * detected. This closes the race where the output-reader briefly clears the prompt state while
     * readLine returns the user's answer, causing "1" / "y" to be queued as a new draft.
     */
    private boolean isRecentPromptAnswer(String answer) {
        if (!agentBusy || agentStdin == null) return false;
        long ageMs = System.currentTimeMillis() - lastAwaitingAt;
        if (lastAwaitingAt <= 0 || ageMs < 0 || ageMs > 1500) return false;
        if (answer == null) return false;
        String trimmed = answer.trim().toLowerCase(Locale.ROOT);
        if (trimmed.matches("[1-9]")) return true;
        return trimmed.equals("y") || trimmed.equals("n")
                || trimmed.equals("yes") || trimmed.equals("no");
    }

    /**
     * Forward the user's answer to a mid-turn agent prompt: the typed text (if any) followed by the
     * agent's submit sequence, mirroring how a message is submitted so a menu selection, yes/no, or
     * free-text answer lands exactly as if typed into the native app. An empty answer sends only the
     * submit key (bare Enter → accept the default option).
     */
    private boolean forwardPromptAnswer(String answer) {
        OutputStream os = agentStdin;
        if (os == null || agentDecoder == null) return false;
        try {
            synchronized (os) {
                if (answer != null && !answer.isEmpty()) {
                    os.write(answer.getBytes(StandardCharsets.UTF_8));
                    os.flush();
                    long delay = agentDecoder.submitDelayMillis();
                    if (delay > 0) {
                        try { Thread.sleep(delay); }
                        catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
                    }
                }
                os.write(agentDecoder.submitSequence().getBytes(StandardCharsets.UTF_8));
                os.flush();
            }
        } catch (IOException ignored) {
            return false;
        }
        // Optimistically clear; the output-reader re-sets it if the agent shows another prompt.
        agentAwaitingInput = false;
        return true;
    }

    /**
     * Forward a raw control key to the agent's stdin only once the agent is actively
     * responding with rendered content, i.e. past its startup handshake. Forwarding raw
     * keys during launch or at the idle prompt can disrupt the agent's init, so callers
     * must fall back to their Kompile/line-editor behavior when this returns false.
     */
    private boolean forwardKeyToAgent(byte[] key) {
        if (agentBusy && tuiTurnSawContent.get() && decoderOwnsScreen() && agentStdin != null) {
            return sendRawToAgentStdin(key);
        }
        return false;
    }

    /**
     * Forward a navigation key (arrow / Tab / Shift+Tab) to the agent, but ONLY while it has a
     * dialog up ({@code agentAwaitingInput}) — so the user can drive the agent's own menus and
     * multi-tab dialogs with the arrow keys, exactly like the native app. Returns true (consumed)
     * when forwarded; false so the caller falls back to normal line-editor / transcript behavior.
     */
    private boolean forwardNavToAgent(byte[] seq) {
        if (agentAwaitingInput && agentStdin != null && decoderOwnsScreen()) {
            // Switch to mirror on the first nav keystroke so the agent's own screen (moving cursor,
            // tab switches) renders live in place — the decoded transcript can only append fragments.
            enterMirrorForDialog();
            sendRawToAgentStdin(seq);
            return true;
        }
        return false;
    }

    /**
     * Confirm the agent's current dialog selection by forwarding a carriage return — used by the
     * Enter key when a dialog is up and the input box is empty. Unlike {@link #forwardNavToAgent}
     * it does NOT enter mirror (the dialog is closing) and does NOT go through readLine's accept-
     * line path (which races the mirror teardown). Returns true when it forwarded.
     */
    private boolean forwardConfirmToAgent() {
        if (agentAwaitingInput && agentStdin != null && decoderOwnsScreen()) {
            // For a numbered menu, confirm the currently-highlighted option by sending its DIGIT —
            // some agents (claude's plan menu) act on the number key, not a bare Enter. For a pure
            // arrow/tab dialog with no numbered options, send a bare carriage return.
            String digit = agentDecoder != null ? agentDecoder.selectedOptionDigit(virtualTerminal) : null;
            if (digit != null && !digit.isBlank()) {
                forwardPromptAnswer(digit);   // digit (+ submit) — the verified type-the-number path
            } else {
                sendRawToAgentStdin(new byte[]{'\r'});
                agentAwaitingInput = false;
            }
            return true;
        }
        return false;
    }

    /**
     * Escape belongs to the child agent while a managed turn is active. Forward it so the
     * agent dismisses its own picker/dialog or cancels generation, then keep Kompile observing the
     * turn. The output reader clears awaiting/mirror state when the child actually leaves that UI.
     */
    private boolean forwardEscapeToAgent() {
        boolean childOwnsEscape = agentAwaitingInput || agentBusy;
        if (!childOwnsEscape || agentStdin == null || !decoderOwnsScreen()) return false;
        return sendRawToAgentStdin(new byte[]{0x1B});
    }

    private boolean awaitAgentIdleForRealtimeCorrection(long timeoutMillis) {
        long timeout = Math.max(0L, timeoutMillis);
        long deadline = System.currentTimeMillis() + timeout;
        long idleSince = -1L;
        while (System.currentTimeMillis() <= deadline) {
            Process process = tuiProcess;
            AgentTuiDecoder decoder = agentDecoder;
            VirtualTerminal vt = virtualTerminal;
            if (!agentBusy || process == null || !process.isAlive() || decoder == null || vt == null) {
                return false;
            }
            long now = System.currentTimeMillis();
            boolean idleNow = !agentAwaitingInput && (decoder.isIdle(vt) || !decoder.isResponding(vt));
            if (idleNow) {
                if (idleSince < 0) idleSince = now;
                if (now - idleSince >= 200L) return true;
            } else {
                idleSince = -1L;
            }
            try {
                Thread.sleep(50L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    /** Switch to mirror rendering for live dialog navigation (first nav keystroke); REPL thread. */
    private void enterMirrorForDialog() {
        if (mirrorRender) return;
        autoMirrorForDialog = true;
        mirrorRender = true;
        renderPolicy = selectDefaultRenderPolicy();
        frameSettleGate.forceNextRender();
        if (virtualTerminal != null) fullRepaint();
    }

    /** Restore decoded rendering once an auto-mirror dialog closes. */
    private void exitMirrorForDialog() {
        if (!autoMirrorForDialog) return;
        autoMirrorForDialog = false;
        mirrorRender = false;
        mirrorInputPrimed = false;
        renderPolicy = selectDefaultRenderPolicy();
        if (virtualTerminal != null) fullRepaint();
    }

    /**
     * Ctrl+B is contextual. If the child agent advertises native backgrounding, forward the
     * key so the child owns its subprocess UI. Otherwise Kompile keeps Ctrl+B for its managed
     * background/process flow. At an idle prompt it falls back to the normal line-editor action.
     */
    private void installBackgroundShortcutForwarding(LineReaderImpl impl) {
        Widget originalBackward = impl.getWidgets().get(LineReader.BACKWARD_CHAR);
        impl.getWidgets().put("agent-or-kompile-background", () -> {
            if (agentBusy) {
                if (agentDecoder != null && agentDecoder.supportsNativeBackgrounding()
                        && forwardKeyToAgent(new byte[]{0x02})) {
                    return true;
                }
                requestAgentBackground();
                return true;
            }
            return originalBackward == null || originalBackward.apply();
        });
        Reference ctrlB = new Reference("agent-or-kompile-background");
        for (KeyMap<Binding> keyMap : impl.getKeyMaps().values()) {
            keyMap.bind(ctrlB, KeyMap.ctrl('B'));
        }
    }

    /**
     * Raw key pass-through loop: forwards every keystroke straight to the agent's
     * stdin so the agent's own multi-key / menu shortcuts work (e.g. Claude Code's
     * subprocess menus). Runs INSTEAD of the JLine line editor while
     * {@code agentPassthroughActive} (entered via /passthrough); the terminal is put
     * in raw mode for the duration. Exits on Ctrl+] (0x1D), agent exit, EOF, or
     * shutdown — the 200ms read timeout re-checks liveness so it can never wedge, and
     * the flag is always cleared in finally. Opt-in and self-contained: a bug here
     * cannot affect normal line editing.
     */
    private void runAgentPassthroughLoop() {
        if (!decoderOwnsScreen() || agentStdin == null || terminal == null) {
            agentPassthroughActive = false;
            return;
        }
        Attributes prev = null;
        try {
            prev = terminal.enterRawMode();
            safePrintln(renderer.dim("  ▸ Pass-through ON — keys go to " + agent + ". Ctrl+] to exit."));
            NonBlockingReader in = terminal.reader();
            while (agentPassthroughActive && tuiProcess != null && tuiProcess.isAlive()
                    && !shutdownSignal.get()) {
                int c = in.read(200L);
                if (c == NonBlockingReader.READ_EXPIRED) continue;
                if (c < 0 || c == 0x1D) break;   // EOF on terminal input, or Ctrl+] to leave
                // Pair an astral code point (emoji, CJK-ext via IME) split across two UTF-16 reads
                // so it re-encodes to a valid UTF-8 sequence rather than a lone-surrogate '?'.
                if (Character.isHighSurrogate((char) c)) {
                    int lo = in.read(50L);
                    if (lo >= 0 && Character.isLowSurrogate((char) lo)) {
                        sendRawToAgentStdin(new String(new char[]{(char) c, (char) lo})
                                .getBytes(StandardCharsets.UTF_8));
                        continue;
                    }
                    sendRawToAgentStdin(encodeInputChar(c));
                    if (lo == 0x1D) break;
                    if (lo >= 0) sendRawToAgentStdin(encodeInputChar(lo));
                    continue;
                }
                sendRawToAgentStdin(encodeInputChar(c));
            }
        } catch (IOException | RuntimeException ignored) {
            // Any failure just ends pass-through cleanly rather than stranding the user.
        } finally {
            if (prev != null) {
                try { terminal.setAttributes(prev); } catch (RuntimeException ignored) {}
            }
            agentPassthroughActive = false;
            safePrintln(renderer.dim("  ▸ Pass-through OFF."));
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
     * <p>
     * It DOES honor {@code --skip-permissions} via the single shared interactive command builder
     * (WP2/F7 — the flag was previously accepted but silently dropped). For opencode this adds
     * nothing (it auto-approves in the TUI; the flag is only valid on {@code opencode run}).
     */
    private List<String> buildCommand(String binary, String message) {
        List<String> cmd = new ArrayList<>(List.of(binary));
        AgentFlagOverrides.addInteractivePermissionBypassFlags(
                cmd, agent, skipPermissions,
                workingDir == null ? null : java.nio.file.Path.of(workingDir));
        AgentFlagOverrides.addModelFlag(cmd, agent, model);
        return cmd;
    }

    private void toggleManagedStatusBar() {
        if (tui == null) {
            safePrintln(renderer.dim("  Status bar is unavailable before the managed TUI starts."));
            return;
        }
        StatusBar statusBar = tui.getStatusBar();
        boolean enabled = !statusBar.isEnabled();
        statusBar.setEnabled(enabled);
        safePrintln((enabled ? GREEN : YELLOW) + "  Status bar " + (enabled ? "enabled" : "disabled") + RESET);
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
            case "/passthrough", "/keys" -> {
                if (!decoderOwnsScreen()) {
                    safePrintln(renderer.dim("  Pass-through needs a managed (decoder-owned) agent."));
                } else {
                    // Picked up at the top of the REPL loop on the next iteration.
                    agentPassthroughActive = true;
                }
            }
            case "/render" -> {
                String mode = rest.trim().toLowerCase();
                if (mode.equals("mirror")) {
                    mirrorRender = true;
                    renderPolicy = selectDefaultRenderPolicy();  // swap the L2 strategy object
                    frameSettleGate.forceNextRender();           // force the next frame to blit
                    if (virtualTerminal != null) {
                        // Full clear + repaint so the agent's screen takes over with NO stale
                        // decoder content behind it; the blit also parks the cursor in its box.
                        fullRepaint();
                    } else {
                        safePrintln(renderer.dim("  Render mode: mirror — send a message to start the agent."));
                    }
                } else if (mode.equals("decoded") || mode.equals("decode")) {
                    mirrorRender = false;
                    mirrorInputPrimed = false;
                    renderPolicy = selectDefaultRenderPolicy();  // swap the L2 strategy object
                    fullRepaint();   // full clear + repaint the decoded transcript cleanly
                } else {
                    safePrintln(renderer.dim("  Render mode: " + (renderPolicy != null ? renderPolicy.name()
                            : (mirrorRender ? "mirror" : "decoded")) + " · use /render mirror|decoded"));
                }
            }
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
            case "/process-status" -> handleActivitySlash(rest.isBlank() ? "" : "status " + rest.trim());
            case "/jobs" -> handleActivitySlash(rest.isBlank() ? "" : rest.trim());
            case "/jobs-remove" -> handleActivitySlash("remove " + rest.trim());
            case "/jobs-clear" -> handleActivitySlash("clear");
            case "/statusbar" -> toggleManagedStatusBar();
            case "/archive" -> handleEnforcerArchiveSlash();
            case "/rollback" -> handleEnforcerRollbackSlash(rest.trim());
            case "/diff" -> handleEnforcerDiffSlash(rest.trim());
            case "/purge" -> handleEnforcerPurgeSlash();
            case "/enforce", "/enforcer" -> handleEnforcerSlash(rest.trim());
            default -> {
                // An unrecognized slash command is the AGENT's own (claude/codex/opencode all take
                // slash commands like /model, /agents, /config on stdin). Forward it to the managed
                // agent's own handler as a normal turn — launching the agent on first use — so its
                // native picker opens IN-SESSION and the alternate-screen mirror + arrow/Enter dialog
                // navigation drive it. This deliberately does NOT spawn a separate agent subprocess
                // (the old AgentCommandForwarder path): a second agent opens its own PTY whose
                // full-screen escapes leak as raw garbage ("[C[C…", DCS device-query replies) and, in
                // an untrusted dir, blocks on the startup trust prompt.
                String agentBinary = resolveAgent(agent);
                if (agentBinary == null) {
                    safePrintln(renderer.dim("  Unknown command: " + cmd + " (agent '" + agent + "' not found on PATH)"));
                } else if (agentBusy) {
                    safePrintln(renderer.dim("  " + agent + " is busy — finish or cancel the current turn, then retry " + cmd + "."));
                } else {
                    String slash = rest.isBlank() ? cmd : cmd + " " + rest;
                    safePrintln(renderer.dim("  → " + agent + " " + slash));
                    dispatchToAgentAsync(slash, history, metrics);
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

    /** Whether a turn should be routed through the enforcer. Static + pure for unit testing. */
    static boolean shouldEnforce(boolean hasService, boolean hasPolicy, boolean paused) {
        return hasService && hasPolicy && !paused;
    }

    /** The status-bar enforcer tag for the given state. Static + pure for unit testing. */
    static String enforcerStatusTag(boolean configured, boolean paused) {
        if (!configured) {
            return "";
        }
        return paused ? " · enforcer paused" : " · enforcer";
    }

    /** Live enforcement gate used by {@link #dispatchToAgent}. */
    private boolean enforcementActive() {
        return shouldEnforce(enforcerService != null, enforcerPolicy != null, enforcementPaused);
    }

    /**
     * The durable judgement records for this enforced session, read from the judge's own log
     * (falling back to the enforcer session id passed in the environment). Empty for keyword mode
     * or when no judge is attached.
     */
    private List<JudgementRecord> liveEnforcerRecords() {
        if (enforcerEvaluator instanceof EnforcerJudge ej && ej.getJudgementLog() != null) {
            return JudgementLog.readFile(ej.getJudgementLog().getFile());
        }
        // F8: resolve via the unified SessionIdentity (enforcer id when active, else Kompile id).
        String sid = sessionIdentity != null ? sessionIdentity.forJudgements() : null;
        if (sid != null && !sid.isBlank()) {
            return JudgementLog.readAll(sid);
        }
        return List.of();
    }

    private static final java.time.format.DateTimeFormatter JUDGEMENT_HMS =
            java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss")
                    .withZone(java.time.ZoneId.systemDefault());

    /** Format a judgement timestamp as HH:mm:ss (best-effort). */
    static String judgementShortTime(String iso) {
        if (iso == null || iso.isBlank()) {
            return "--:--:--";
        }
        try {
            return JUDGEMENT_HMS.format(java.time.Instant.parse(iso));
        } catch (Exception e) {
            return iso.length() > 8 ? iso.substring(0, 8) : iso;
        }
    }

    /**
     * Render the most recent {@code limit} judgement records as compact display lines. Pure +
     * static so it is unit-testable without a live session. Each record becomes
     * "{@code <sym> HH:mm:ss <phase> <headline> [backend] tool=…}", optionally followed by an
     * indented violations line. {@code limit <= 0} renders all.
     */
    static List<String> formatJudgementLines(List<JudgementRecord> records, int limit) {
        List<String> out = new ArrayList<>();
        if (records == null || records.isEmpty()) {
            return out;
        }
        int start = (limit > 0 && records.size() > limit) ? records.size() - limit : 0;
        for (int i = start; i < records.size(); i++) {
            JudgementRecord r = records.get(i);
            String sym = r.isCompliant() ? "✓" : (r.isStop() ? "■" : "✗");
            String headline = r.getStatus() != null ? r.getStatus()
                    : (r.getSeverity() != null ? r.getSeverity() : "");
            StringBuilder sb = new StringBuilder();
            sb.append(sym).append(' ').append(judgementShortTime(r.getTimestamp()));
            if (r.getPhase() != null && !r.getPhase().isBlank()) {
                sb.append("  ").append(r.getPhase());
            }
            if (r.getAttempt() > 0) {
                sb.append(" a").append(r.getAttempt());
            }
            if (!headline.isBlank()) {
                sb.append("  ").append(headline);
            }
            if (r.getBackend() != null && !r.getBackend().isBlank()) {
                sb.append("  [").append(r.getBackend()).append(']');
            }
            if (r.getToolName() != null && !r.getToolName().isBlank()) {
                sb.append("  tool=").append(r.getToolName());
            }
            out.add(sb.toString());
            if (r.getViolations() != null && !r.getViolations().isEmpty()) {
                out.add("     violations: " + String.join("; ", r.getViolations()));
            }
        }
        return out;
    }

    private void handleEnforcerSlash(String args) {
        String wdText = workingDir == null || workingDir.isBlank() ? "." : workingDir;
        Path wd = Path.of(wdText).toAbsolutePath().normalize();
        String[] parts = args.isBlank() ? new String[0] : args.trim().split("\\s+");
        String subCmd = parts.length == 0 ? "status" : parts[0].toLowerCase();
        switch (subCmd) {
            case "init", "setup" -> {
                EnforcerConfig config =
                        EnforcerSetupWizard.run(wd);
                if (config != null) {
                    safePrintln(renderer.green("  Enforcer configured. Restart the session to apply."));
                } else {
                    safePrintln("  Setup cancelled.");
                }
            }
            case "show" -> {
                EnforcerConfig config =
                        EnforcerConfig.load(wd);
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
            case "status" -> printLiveEnforcerStatus();
            case "pause", "off" -> setEnforcementPaused(true);
            case "resume", "on" -> setEnforcementPaused(false);
            case "judgements", "judgments", "log" -> {
                int limit = 10;
                if (parts.length > 1) {
                    try {
                        limit = Integer.parseInt(parts[1]);
                    } catch (NumberFormatException ignored) {
                        // keep the default
                    }
                }
                printEnforcerJudgements(limit);
            }
            case "delete" -> {
                try {
                    EnforcerConfig.delete(wd);
                    safePrintln(renderer.dim("  Enforcer config deleted."));
                } catch (Exception e) {
                    safePrintln(renderer.yellow("  Failed: " + e.getMessage()));
                }
            }
            default -> safePrintln(renderer.dim(
                    "  Usage: /enforcer [status|pause|resume|judgements [N]|show|init|delete]"));
        }
    }

    /** Live (in-session) enforcer status: active/paused, backend, rules, retries, judgement count. */
    private void printLiveEnforcerStatus() {
        if (enforcerEvaluator == null) {
            safePrintln(renderer.dim("  No enforcer active in this session. "
                    + "Configured rules (if any) are shown by /enforcer show."));
            return;
        }
        int ruleCount = enforcerPolicy != null
                ? (int) enforcerPolicy.getRules().lines().filter(l -> !l.isBlank()).count() : 0;
        int retries = enforcerPolicy != null ? enforcerPolicy.getMaxCorrections() : 0;
        int judgements = liveEnforcerRecords().size();
        safePrintln("");
        safePrintln("  Enforcement:  " + (enforcementPaused
                ? renderer.yellow("paused") : renderer.green("active")));
        safePrintln("  Judge:        " + enforcerEvaluator.describe());
        safePrintln("  Rules:        " + ruleCount);
        safePrintln("  Max retries:  " + retries);
        safePrintln("  Judgements:   " + judgements + " recorded this session");
        safePrintln(renderer.dim("  Control with: /enforcer pause | resume | judgements [N]"));
        safePrintln("");
    }

    /** Pause or resume live enforcement, updating the status bar + TUI chrome. */
    private void setEnforcementPaused(boolean paused) {
        if (enforcerEvaluator == null) {
            safePrintln(renderer.dim("  No enforcer active in this session."));
            return;
        }
        if (enforcementPaused == paused) {
            safePrintln(renderer.dim("  Enforcement is already " + (paused ? "paused." : "active.")));
            return;
        }
        enforcementPaused = paused;
        redrawStatusLine();
        if (tui != null) {
            tui.setEnforcerActive(!paused);
        }
        if (paused) {
            safePrintln(renderer.yellow(
                    "  ⏸ Enforcement paused — turns dispatch without judge review. "
                            + "/enforcer resume to re-enable."));
        } else {
            safePrintln(renderer.green("  ▶ Enforcement resumed — turns are judged again."));
        }
    }

    /** Print the most recent judgements recorded by this session's judge. */
    private void printEnforcerJudgements(int limit) {
        if (enforcerEvaluator == null) {
            safePrintln(renderer.dim("  No enforcer active in this session."));
            return;
        }
        if (!enforcerEvaluator.recordsJudgements()) {
            safePrintln(renderer.dim("  Keyword-mode enforcer records no LLM judgements."));
            return;
        }
        List<JudgementRecord> records = liveEnforcerRecords();
        if (records.isEmpty()) {
            safePrintln(renderer.dim("  No judgements recorded yet this session."));
            return;
        }
        int shown = Math.min(limit <= 0 ? records.size() : limit, records.size());
        safePrintln("");
        safePrintln(renderer.dim("  Recent judgements (" + shown + " of " + records.size() + "):"));
        for (String line : formatJudgementLines(records, limit)) {
            safePrintln("  " + line);
        }
        safePrintln(renderer.dim("  Full log: kompile enforcer judgements --raw"));
        safePrintln("");
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
                  /process-status <id> Inspect process/subagent status
                  /jobs              Alias for the activity/job panel
                  /jobs-remove <id>  Remove a completed retained activity
                  /jobs-clear        Clear completed retained activities
                  /statusbar         Toggle the bottom status bar
                  /status            Show session metrics
                  /clear             Clear the screen
                  /passthrough|/keys Forward keys straight to the agent (Ctrl+] to exit)
                  /render [mode]     Show or set render mode: mirror · decoded
                  /mode              Show current mode
                  /archive           List archived turns (enforced sessions)
                  /rollback [id]     Roll back violated turns (or a specific turn)
                  /diff <id>         Show the diff for an archived turn
                  /purge             Purge this session's diff archive
                  /rules             Show the active enforcer rules
                  /enforcer [cmd]    Enforcer: status · pause · resume · judgements [N]
                  /help              Show this help
                  /quit              Exit

                Keyboard shortcuts:
                  Esc/Ctrl+C         Forward to the active child agent/subprocess
                  Ctrl+G             Force-cancel Kompile's managed agent process
                  Ctrl+B             Background: child-native when supported, otherwise Kompile-managed
                  Type + Enter       Queue a message while the agent is busy
                  Wheel/PgUp/PgDn    Scroll transcript · Ctrl+Home/End jump to top/bottom

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
                    ChatConfig.getPassthroughAgentOrder())));
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
        body.append(DIM).append("Wheel/PageUp/PageDown scroll · Ctrl+Home/End jump · Esc/Ctrl+C child · Ctrl+G cancel").append(RESET);

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
            injectedSettingsFile = McpToolInjection.injectTools(
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
        McpToolInjection.removeTools(injectedSettingsFile);
    }

    // ── Key bindings ───────────────────────────────────────────────────────

    private void bindCancelKey(LineReader lineReader) {
        if (lineReader instanceof LineReaderImpl impl) {
            impl.getKeyMaps().get(LineReader.EMACS).bind(
                    new Reference("cancel-emulated"),
                    KeyMap.ctrl('G')
            );

            impl.setVariable("cancel-emulated", (Widget) () -> {
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
        // A lone Escape (forwarded to the child when it owns the turn) is a prefix of the arrow/tab escape sequences, so
        // JLine waits `ambiguous-binding` ms after ESC to disambiguate before firing its widget. The
        // default 1000ms made Escape feel like it "stuck busy" for ~1s. 80ms fires it near-instantly
        // while still far exceeding the sub-millisecond, single-write inter-byte gap of a real arrow
        // sequence (whose '[' is already buffered, so arrows never incur the wait at all).
        lineReader.setVariable(LineReader.AMBIGUOUS_BINDING, 80L);
        wrapSlashRefreshWidget(impl, LineReader.SELF_INSERT);
        wrapSlashRefreshWidget(impl, LineReader.BACKWARD_DELETE_CHAR);
        installScrollbackWidgets(impl);
        installActivityNavigationWidgets(impl);
        installDialogNavForwarding(impl);
        installBackgroundShortcutForwarding(impl);
        wrapActivityKillWidget(impl, LineReader.DELETE_CHAR);
        wrapSlashRefreshWidget(impl, LineReader.COMPLETE_WORD);
        wrapActivityAcceptWidget(impl, LineReader.ACCEPT_LINE);
    }

    private void installScrollbackWidgets(LineReaderImpl impl) {
        String pageUpName = "scroll-page-up";
        String pageDownName = "scroll-page-down";
        String topName = "scroll-top";
        String bottomName = "scroll-bottom";
        String mouseName = "scroll-mouse-wheel";

        impl.getWidgets().put(pageUpName, this::scrollTranscriptPageUp);
        impl.getWidgets().put(pageDownName, this::scrollTranscriptPageDown);
        impl.getWidgets().put(topName, this::scrollTranscriptToTop);
        impl.getWidgets().put(bottomName, this::scrollTranscriptToBottom);
        impl.getWidgets().put(mouseName, () -> handleTranscriptMouseEvent(impl));

        Reference pageUp = new Reference(pageUpName);
        Reference pageDown = new Reference(pageDownName);
        Reference top = new Reference(topName);
        Reference bottom = new Reference(bottomName);
        Reference mouse = new Reference(mouseName);

        List<String> pageUpSequences = keySequences(impl, InfoCmp.Capability.key_ppage,
                "\033[5~", "\033[5;2~");
        List<String> pageDownSequences = keySequences(impl, InfoCmp.Capability.key_npage,
                "\033[6~", "\033[6;2~");
        List<String> topSequences = keySequences(impl, null,
                "\033[1;5H", "\033[5H");
        List<String> bottomSequences = keySequences(impl, null,
                "\033[1;5F", "\033[5F");
        // X10 mouse reports are ESC[M-prefixed; readMouseEvent() consumes the rest.
        List<String> mouseSequences = keySequences(impl, InfoCmp.Capability.key_mouse,
                "\033[M");

        for (KeyMap<Binding> keyMap : impl.getKeyMaps().values()) {
            keyMap.bind(pageUp, pageUpSequences.toArray(String[]::new));
            keyMap.bind(pageDown, pageDownSequences.toArray(String[]::new));
            keyMap.bind(top, topSequences.toArray(String[]::new));
            keyMap.bind(bottom, bottomSequences.toArray(String[]::new));
            keyMap.bind(mouse, mouseSequences.toArray(String[]::new));
        }
    }

    /**
     * Handle a mouse report on the managed transcript. Fires only while Kompile
     * has wheel capture enabled (decoder-owned screen). Wheel up/down scroll the
     * transcript; every other mouse event (clicks, motion, release) is consumed
     * so it never leaks into the readline buffer or to the agent's stdin.
     */
    private boolean handleTranscriptMouseEvent(LineReaderImpl impl) {
        try {
            MouseEvent event = impl.getTerminal().readMouseEvent();
            if (event == null || !decoderOwnsScreen()) return true;
            switch (event.getButton()) {
                case WheelUp -> scrollTranscriptBy(WHEEL_SCROLL_LINES);
                case WheelDown -> scrollTranscriptBy(-WHEEL_SCROLL_LINES);
                default -> { /* consume clicks/motion; do not scroll or forward */ }
            }
        } catch (RuntimeException ignored) {
            // A malformed/partial mouse report must never break the active readLine.
        }
        return true;
    }

    /**
     * Route Left / Right / Shift+Tab to the agent while it has a dialog up, so multi-tab dialogs
     * (claude's /agents, /model, config panels, etc.) can be navigated with the arrows and their
     * tabs switched — exactly like the native app. Falls back to normal line-editing otherwise.
     */
    private void installDialogNavForwarding(LineReaderImpl impl) {
        Widget origLeft = impl.getWidgets().get(LineReader.BACKWARD_CHAR);
        Widget origRight = impl.getWidgets().get(LineReader.FORWARD_CHAR);

        impl.getWidgets().put("dialog-left", () -> {
            if (forwardNavToAgent(new byte[]{0x1B, '[', 'D'})) return true;
            return origLeft == null || origLeft.apply();
        });
        impl.getWidgets().put("dialog-right", () -> {
            if (forwardNavToAgent(new byte[]{0x1B, '[', 'C'})) return true;
            return origRight == null || origRight.apply();
        });
        impl.getWidgets().put("dialog-shift-tab", () -> {
            if (forwardNavToAgent(new byte[]{0x1B, '[', 'Z'})) return true;
            return true; // Shift+Tab has no line-editor action; consume it when idle
        });
        // Escape while a child turn is active belongs to the child. Binding a BARE ESC coexists
        // with ESC[… arrow/tab bindings above: JLine's keymap trie fires the longest match, so
        // ESC[A stays an arrow and a lone ESC fires this widget. With no child owner, consume the
        // lone ESC as a no-op because it has no useful line-editor action here.
        impl.getWidgets().put("agent-escape", () -> {
            forwardEscapeToAgent();
            return true;
        });

        Reference left = new Reference("dialog-left");
        Reference right = new Reference("dialog-right");
        Reference shiftTab = new Reference("dialog-shift-tab");
        Reference escape = new Reference("agent-escape");
        List<String> leftSeq = keySequences(impl, InfoCmp.Capability.key_left,
                "\033[D", "\033OD", "\033[1D");
        List<String> rightSeq = keySequences(impl, InfoCmp.Capability.key_right,
                "\033[C", "\033OC", "\033[1C");
        for (KeyMap<Binding> keyMap : impl.getKeyMaps().values()) {
            keyMap.bind(left, leftSeq.toArray(String[]::new));
            keyMap.bind(right, rightSeq.toArray(String[]::new));
            keyMap.bind(shiftTab, "\033[Z");
            keyMap.bind(escape, "\033");
            // A lone ESC (cancel a dialog) is a prefix of the arrow/tab binds above, so JLine's
            // BindingReader waits the KeyMap's ambiguousTimeout to disambiguate before firing it.
            // The default 1000ms made Escape feel "stuck busy" ~1s. Set it on the keymap directly
            // (BindingReader reads keyMap.getAmbiguousTimeout(), not the LineReader variable). 80ms
            // far exceeds a real arrow's sub-ms inter-byte gap (its '[' is already buffered).
            keyMap.setAmbiguousTimeout(80L);
        }
    }

    private void installActivityNavigationWidgets(LineReaderImpl impl) {
        Widget originalDown = impl.getWidgets().get(LineReader.DOWN_LINE_OR_HISTORY);
        Widget originalUp = impl.getWidgets().get(LineReader.UP_LINE_OR_HISTORY);
        String downWidgetName = "activity-down";
        String upWidgetName = "activity-up";

        impl.getWidgets().put(downWidgetName, () -> {
            // While the agent has a dialog up, arrows navigate ITS menu (native feel).
            if (forwardNavToAgent(new byte[]{0x1B, '[', 'B'})) return true;
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
            if (forwardNavToAgent(new byte[]{0x1B, '[', 'A'})) return true;
            if (activityFocusActive && selectPreviousActivityItem()) return true;
            if (originalUp != null) {
                boolean result = originalUp.apply();
                refreshManagedSlashCompletion(impl);
                return result;
            }
            return true;
        });

        Reference down = new Reference(downWidgetName);
        Reference up = new Reference(upWidgetName);
        List<String> downSequences = arrowSequences(impl, InfoCmp.Capability.key_down,
                "\033[B", "\033OB", "\033[1B");
        List<String> upSequences = arrowSequences(impl, InfoCmp.Capability.key_up,
                "\033[A", "\033OA", "\033[1A");
        for (KeyMap<Binding> keyMap : impl.getKeyMaps().values()) {
            keyMap.bind(down, downSequences.toArray(String[]::new));
            keyMap.bind(up, upSequences.toArray(String[]::new));
        }
    }

    private List<String> arrowSequences(LineReaderImpl impl,
                                        InfoCmp.Capability capability,
                                        String... fallbackSequences) {
        return keySequences(impl, capability, fallbackSequences);
    }

    private List<String> keySequences(LineReaderImpl impl,
                                      InfoCmp.Capability capability,
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
            // Enter while an agent dialog is up and the input box is empty → confirm the agent's
            // current (arrow-selected) option by forwarding a CR to it, rather than submitting an
            // empty line. A non-empty buffer falls through to normal accept (type-and-forward).
            String buf = impl.getBuffer() == null ? "" : impl.getBuffer().toString();
            if (buf.trim().isEmpty() && forwardConfirmToAgent()) {
                return true;
            }
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
            // Multi-event: a completed tool_use yields both ToolUse and ToolComplete.
            // The single-event parser drops the ToolComplete, so tool output never renders.
            return parser.parseOpenCodeLineMulti(line);
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
        return ScriptPtyProvider.INSTANCE.wrap(cmd, PtyDims.of(rows, cols));
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
            if (enforcementActive()) {
                dispatchEnforced(message, history, metrics);
            } else {
                sendToAgent(message, history, metrics);
            }
            return false;
        } finally {
            agentBusy = false;
            agentAwaitingInput = false;
            exitMirrorForDialog();
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
     * Start the realtime JSONL semantic tap once, if a judge is configured (WP9/F3). Tails the
     * agent's native session file (claude/codex) and judge-evaluates text + tool calls as they are
     * written — giving the managed TUI the realtime enforcement the headless path already had,
     * regardless of which render policy owns the screen. Best-effort and non-destabilizing: the
     * tailer never touches the agent, only observes; the violation handler decides what to do.
     */
    private void startEnforcerRealtimeTailIfConfigured() {
        if (enforcerJudge == null || enforcerPolicy == null) {
            return; // keyword mode / no judge — nothing for the realtime tap to do.
        }
        if (!enforcerTailStarted.compareAndSet(false, true)) {
            return;
        }
        try {
            java.nio.file.Path wd = java.nio.file.Path.of(workingDir == null ? "." : workingDir);
            // Shared tap (fromComponents): judge/policy are owned by EnforcerCommand, so the tap does
            // NOT close them; it builds its own fresh window (never the turn-gate's — see the tap doc).
            enforcerRealtimeTap = RealtimeEnforcementTap.fromComponents(
                    agent, wd, objectMapper, enforcerJudge, enforcerPolicy,
                    this::onRealtimeEnforcerViolation);
            enforcerRealtimeTap.start();
        } catch (RuntimeException e) {
            enforcerTailStarted.set(false);
            // Tailing is best-effort — the turn-gate still enforces even if the tap can't start.
        }
    }

    private void stopEnforcerRealtimeTail() {
        RealtimeEnforcementTap tap = enforcerRealtimeTap;
        if (tap != null) {
            tap.close();
            enforcerRealtimeTap = null;
        }
    }

    /**
     * Feed one enforcer turn outcome to the observe-only fallback advisor (WP13) and surface a
     * switch suggestion if the current agent has degraded past the thresholds. Advisory only — the
     * user acts via {@code /agent}; nothing is switched or interrupted here.
     */
    private void adviseFallbackFromResult(EnforcerResult result) {
        if (result == null) {
            return;
        }
        FallbackSupervisor advisor = enforcerFallbackAdvisor;
        if (advisor == null) {
            // Observe-only supervisor (autoAdvance=false) seeded with the current agent.
            advisor = new FallbackSupervisor(FallbackSupervisor.Config.defaults(), agent, false);
            enforcerFallbackAdvisor = advisor;
        }
        int score = FallbackSupervisor.scoreForOutcome(result.isAccepted(), lastDecisionSeverity(result));
        FallbackSupervisor.Decision decision = advisor.recordTurnScore(score, !result.isAccepted());
        if (decision.fallback()) {
            safePrintln(renderer.yellow("[enforcer] " + decision.fromAgent()
                    + " is underperforming (" + decision.reason() + "). Consider switching: /agent "
                    + decision.toAgent()));
        }
    }

    /** The severity of the last attempt's judge decision, or null (e.g. keyword mode / no decision). */
    private static String lastDecisionSeverity(EnforcerResult result) {
        List<EnforcerResult.Attempt> attempts = result.getAttempts();
        if (attempts != null && !attempts.isEmpty()) {
            EnforcerDecision decision = attempts.get(attempts.size() - 1).decision();
            if (decision != null) {
                return decision.getSeverity();
            }
        }
        return null;
    }

    /**
     * Realtime violation callback from the JSONL tap (runs on the tap's poll thread). Surfaces the
     * violation, then actuates once for the current managed turn: ESC cancels generation without
     * killing the persistent TUI, and the judge correction is submitted after the decoder sees idle.
     */
    private void onRealtimeEnforcerViolation(String reason, String correctionPrompt, boolean toolCall) {
        String kind = toolCall ? "tool call" : "output";
        safePrintln(renderer.yellow("[enforcer] realtime " + kind + " violation: " + reason));
        if (tui != null && tuiSubagentId != null) {
            tui.getStatusBar().updateSubagentStatus(tuiSubagentId, "violation");
        }
        if (!agentBusy || enforcementPaused) {
            return;
        }
        if (!realtimeEnforcerActuatedThisTurn.compareAndSet(false, true)) {
            return;
        }
        Thread actuatorThread = new Thread(
                () -> actuateRealtimeEnforcerViolation(reason, correctionPrompt, toolCall),
                "enforcer-realtime-actuator");
        actuatorThread.setDaemon(true);
        actuatorThread.start();
    }

    private void actuateRealtimeEnforcerViolation(String reason, String correctionPrompt, boolean toolCall) {
        realtimeEnforcerActuationInFlight.set(true);
        try {
            ManagedAgentInputActuator actuator = managedAgentInputActuator();
            if (!actuator.sendEsc()) {
                safePrintln(renderer.dim("[enforcer] realtime correction skipped: agent input is unavailable"));
                return;
            }
            updateStatusLine("interrupting violation");
            boolean idle = actuator.awaitIdle(REALTIME_ENFORCER_CANCEL_IDLE_TIMEOUT_MS);
            if (!idle) {
                safePrintln(renderer.dim("[enforcer] realtime correction continuing before idle confirmation"));
            }
            if (!agentBusy || agentStdin == null) {
                return;
            }
            String prompt = realtimeCorrectionPrompt(reason, correctionPrompt, toolCall);
            if (actuator.sendTextAndSubmit(prompt)) {
                safePrintln(renderer.yellow("[enforcer] sent realtime correction"));
            } else {
                safePrintln(renderer.dim("[enforcer] realtime correction skipped: submit failed"));
            }
        } finally {
            realtimeEnforcerActuationInFlight.set(false);
        }
    }

    private String realtimeCorrectionPrompt(String reason, String correctionPrompt, boolean toolCall) {
        if (correctionPrompt != null && !correctionPrompt.isBlank()) {
            return correctionPrompt;
        }
        StringBuilder prompt = new StringBuilder();
        prompt.append("Your previous ").append(toolCall ? "tool call" : "response")
                .append(" violated the active enforcer rules.");
        if (reason != null && !reason.isBlank()) {
            prompt.append("\n\nReason: ").append(reason.trim());
        }
        prompt.append("\n\nStop that path and produce a corrected response that complies with the rules.");
        return prompt.toString();
    }

    /**
     * Enforced dispatch: wraps sendToAgent with EnforcerService retry logic.
     */
    private void dispatchEnforced(String message, ChatHistory history, ChatSessionMetrics metrics) {
        startEnforcerRealtimeTailIfConfigured();
        if (enforcerConversationWindow != null) {
            enforcerConversationWindow.addUserMessage(message);
        }

        // Per-turn diff snapshot (for rollback-on-violation), ported from the classic enforcer.
        EnforcerDiffArchive.TurnSnapshot snapshot = null;
        if (enforcerDiffArchive != null) {
            try {
                snapshot = enforcerDiffArchive.beginTurn();
            } catch (IOException e) {
                safePrintln(renderer.dim("[enforcer] could not begin diff snapshot: " + e.getMessage()));
            }
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

            // Complete the diff snapshot + run diff-pattern checks with auto-rollback.
            result = applyDiffArchive(result, snapshot, history, metrics);

            // WP13: feed the turn outcome to the observe-only fallback advisor; surface a switch
            // suggestion when the current agent degrades. Never switches automatically.
            adviseFallbackFromResult(result);

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

    /**
     * Complete the per-turn diff snapshot and run diff-pattern checks with auto-rollback. Ported
     * from {@code EnforcerCommand.runEnforcedTurn}, substituting {@code sendToAgent} for the
     * headless {@code SubprocessAgentRunner.runMessage}. No-op when diff archiving is off.
     */
    private EnforcerResult applyDiffArchive(EnforcerResult result, EnforcerDiffArchive.TurnSnapshot snapshot,
                                            ChatHistory history, ChatSessionMetrics metrics) {
        if (enforcerDiffArchive == null || snapshot == null || result == null) {
            return result;
        }
        boolean violated = !result.isAccepted();
        try {
            enforcerDiffArchive.completeTurn(snapshot, violated);
            if (violated) {
                safePrintln(renderer.yellow("[enforcer] changes archived for rollback: " + snapshot.getTurnId()));
            }
        } catch (IOException e) {
            safePrintln(renderer.dim("[enforcer] could not complete diff snapshot: " + e.getMessage()));
        }

        if (enforcerDiffPatternEvaluator != null && enforcerDiffPatternEvaluator.isAvailable()
                && result.isAccepted()) {
            try {
                String turnDiff = enforcerDiffArchive.getTurnDiff(snapshot.getTurnId());
                if (turnDiff != null && !turnDiff.isBlank()) {
                    DiffPatternEvaluator.DiffEvaluation diffEval = enforcerDiffPatternEvaluator.evaluate(turnDiff);
                    if (!diffEval.passed()) {
                        enforcerDiffArchive.completeTurn(snapshot, true);
                        safePrintln(renderer.yellow("[enforcer] code pattern violations in diff:"));
                        for (DiffPatternEvaluator.DiffViolation v : diffEval.violations()) {
                            safePrintln(renderer.yellow("  - " + v.filePath() + ":" + v.lineNumber()
                                    + " — " + v.rule().getDescription()));
                        }
                        if (enforcerAutoRollbackOnViolation) {
                            EnforcerDiffArchive.RollbackResult rr =
                                    enforcerDiffArchive.rollback(snapshot.getTurnId());
                            if (rr.success()) {
                                safePrintln(renderer.yellow("[enforcer] rolled back changes ("
                                        + rr.restoredFiles().size() + " files restored)"));
                            }
                        }
                        if (diffEval.correctionPrompt() != null) {
                            safePrintln(renderer.yellow("[enforcer] sending correction to agent..."));
                            String corrected = sendToAgent(diffEval.correctionPrompt(), history, metrics);
                            if (enforcerConversationWindow != null) {
                                enforcerConversationWindow.finishAssistantMessage(corrected);
                            }
                        }
                        StringBuilder violationMsg = new StringBuilder();
                        for (DiffPatternEvaluator.DiffViolation v : diffEval.violations()) {
                            violationMsg.append(v.filePath()).append(":").append(v.lineNumber())
                                    .append(" — ").append(v.rule().getDescription()).append("; ");
                        }
                        result = EnforcerResult.blocked("", result.getAttempts(),
                                "Code pattern violations: " + violationMsg, "diff-pattern-evaluator");
                    }
                }
            } catch (IOException e) {
                safePrintln(renderer.dim("[enforcer] diff pattern check failed: " + e.getMessage()));
            }
        }
        return result;
    }

    private void handleEnforcerArchiveSlash() {
        if (enforcerDiffArchive == null) {
            safePrintln(renderer.dim("Diff archiving is disabled (no --archive-diffs)."));
            return;
        }
        try {
            List<EnforcerDiffArchive.TurnMetadata> turns = enforcerDiffArchive.listTurns();
            if (turns.isEmpty()) {
                safePrintln(renderer.dim("No turns archived yet."));
                return;
            }
            safePrintln("Enforcer archive:");
            for (EnforcerDiffArchive.TurnMetadata t : turns) {
                String marker = t.violated() ? renderer.red("[VIOLATED]") : renderer.green("[OK]");
                safePrintln(String.format("  %s  %-10s  %s  files: %d",
                        marker, t.turnId(), t.timestamp(), t.changedFiles().size()));
            }
        } catch (IOException e) {
            safePrintln(renderer.red("Error listing archive: " + e.getMessage()));
        }
    }

    private void handleEnforcerRollbackSlash(String turnId) {
        if (enforcerDiffArchive == null) {
            safePrintln(renderer.dim("Diff archiving is disabled."));
            return;
        }
        try {
            EnforcerDiffArchive.RollbackResult rr = (turnId == null || turnId.isBlank())
                    ? enforcerDiffArchive.rollbackViolations()
                    : enforcerDiffArchive.rollback(turnId);
            if (rr.success()) {
                safePrintln(renderer.green("[enforcer] " + rr.message()));
                for (String f : rr.restoredFiles()) {
                    safePrintln(renderer.dim("  restored: " + f));
                }
            } else {
                safePrintln(renderer.yellow(rr.message()));
            }
        } catch (IOException e) {
            safePrintln(renderer.red("Rollback failed: " + e.getMessage()));
        }
    }

    private void handleEnforcerDiffSlash(String turnId) {
        if (enforcerDiffArchive == null) {
            safePrintln(renderer.dim("Diff archiving is disabled."));
            return;
        }
        if (turnId == null || turnId.isBlank()) {
            safePrintln(renderer.dim("Usage: /diff <turn-id>"));
            return;
        }
        try {
            String diff = enforcerDiffArchive.getTurnDiff(turnId);
            if (diff == null || diff.isBlank()) {
                safePrintln(renderer.dim("No diff found for " + turnId));
            } else {
                safePrintln(diff);
            }
        } catch (IOException e) {
            safePrintln(renderer.red("Error reading diff: " + e.getMessage()));
        }
    }

    private void handleEnforcerPurgeSlash() {
        if (enforcerDiffArchive == null) {
            safePrintln(renderer.dim("Diff archiving is disabled."));
            return;
        }
        try {
            enforcerDiffArchive.purge();
            safePrintln(renderer.dim("Archive purged for this session."));
        } catch (IOException e) {
            safePrintln(renderer.red("Purge failed: " + e.getMessage()));
        }
    }

    private void killProcess(Process process) {
        if (process == null || !process.isAlive()) return;
        try {
            boolean isUnix = !System.getProperty("os.name", "").toLowerCase().startsWith("win");
            if (isUnix) {
                InterruptEscalation.hardTree().escalate(process);
            } else {
                process.destroyForcibly();
            }
        } catch (Exception e) {
            process.destroyForcibly();
        }
    }

    record QueuedMessageDraft(String id, String content) {}

}
