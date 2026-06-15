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

import ai.kompile.cli.main.chat.agent.SubprocessAgentRunner;
import ai.kompile.cli.main.chat.config.SystemPromptManager;
import ai.kompile.cli.main.chat.enforcer.*;
import ai.kompile.cli.main.chat.harness.HarnessConfig;
import ai.kompile.cli.main.chat.render.AsciiRenderer;
import ai.kompile.cli.main.chat.render.TerminalRenderer;
import com.fasterxml.jackson.databind.ObjectMapper;
import picocli.CommandLine;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

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
 * as a per-message subprocess while maintaining the kompile chat interface.
 * <p>
 * Each user message launches the agent in non-interactive mode ({@code -p} for
 * Claude, {@code run} for OpenCode, etc.). The agent processes the message and
 * exits. Agent output is captured, stripped of TUI escape sequences, and rendered
 * cleanly through kompile's terminal rendering system.
 * <p>
 * For Claude, output is parsed as stream-json for structured tool call rendering.
 * For other agents, raw text output is cleaned and displayed.
 * <p>
 * Conversation continuity is maintained via the agent's continuation flags
 * (e.g. {@code --continue} for Claude Code) after the first message.
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

    @CommandLine.Option(names = {"--resume-session"}, description = "Session ID to resume — loads and displays prior conversation turns and passes the session to the underlying agent for continuation")
    String resumeSessionId;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final PassthroughStreamParser parser = new PassthroughStreamParser();
    private McpUrlResolver mcpUrlResolver = new McpUrlResolver();

    private TerminalRenderer renderer;
    private AsciiRenderer ascii;

    // Track whether first message has been sent (for --continue flag)
    private volatile boolean firstMessageSent = false;

    // Agent session IDs — captured from first structured event, used for multi-turn.
    // Volatile: written by output reader thread, read by main thread in buildCommand.
    private volatile String agentSessionId = null;

    // Active subprocess (for cancellation)
    private volatile Process activeProcess;
    private volatile Thread waitingThread; // thread blocked in waitFor, for interrupt
    private final AtomicBoolean cancelSignal = new AtomicBoolean(false);
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
    // Tracks last output timestamp for TUI stall detection
    private final AtomicLong lastOutputTime = new AtomicLong(0);

    // Scroll region layout — input box stays fixed at bottom, chat scrolls above
    private int scrollBottom;  // last row of scroll region (1-indexed)

    // Persistent TUI process (OpenCode) — launched once, reused across messages
    private volatile Process tuiProcess;
    private volatile Thread tuiOutputReader;
    // Current response state for persistent TUI — swapped on each message
    private volatile StringBuilder tuiFullText;
    private volatile List<String> tuiToolCalls;
    private volatile ChatSessionMetrics tuiMetrics;
    private volatile TerminalRenderer.SpinnerHandle tuiSpinner;
    private volatile AtomicBoolean tuiSpinnerStopped;
    private volatile StringBuilder tuiPendingText;
    // Buffer for partial ANSI escape sequences split across chunk boundaries
    private final StringBuilder tuiEscapeBuffer = new StringBuilder();

    // Optional: set by ChatCommand when platform init is done externally
    SystemPromptManager systemPromptManager;

    // Enforcer support — set by ChatCommand when enforcer rules are active.
    // When non-null, every agent turn is wrapped with enforcement (retry on violations).
    EnforcerEvaluator enforcerEvaluator;
    EnforcerPolicy enforcerPolicy;
    EnforcerService enforcerService;
    EnforcerConversationWindow enforcerConversationWindow;
    Map<String, String> enforcerExtraEnv;

    // ANSI
    private static final String RESET = "\033[0m";
    private static final String CYAN = "\033[36m";
    private static final String GREEN = "\033[32m";
    private static final String YELLOW = "\033[33m";
    private static final String DIM = "\033[2m";
    private static final String BOLD = "\033[1m";

    /**
     * Regex that strips ALL ANSI/VT escape sequences, including:
     *  - CSI sequences: ESC [ ... letter
     *  - OSC sequences: ESC ] ... ST  (string terminator is ESC\ or BEL)
     *  - DCS/PM/APC/SOS
     *  - Simple two-char escapes: ESC ( ESC ) ESC = etc.
     *  - Hyperlink sequences: ]8;...;...\
     */
    private static final String ANSI_REGEX =
            "\033\\[[0-9;?]*[a-zA-Z]"          // CSI: ESC [ params letter
            + "|\033\\].*?(?:\033\\\\|\007)"    // OSC: ESC ] ... (ST or BEL)
            + "|\033[()][0-9A-B]"              // Character set: ESC ( B etc
            + "|\033[>=<]"                      // Keypad/cursor modes
            + "|\033\\\\";                      // String terminator

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
                    .completer(new ChatCompleter(() -> null))
                    .build();

            // Auto-trigger slash command completion as the user types
            ChatCompleter.setTerminalRef(lineReader, term);
            ChatCompleter.setQueueSupplier(() -> null);
            ChatCompleter.enableAutoTrigger(lineReader);

            // SIGINT handler — kills active subprocess and interrupts the
            // waiting thread so waitFor() unblocks immediately.
            // Save JLine's original INT handler so we can delegate to it
            // when the agent is idle (raises UserInterruptException in readLine).
            // Save original handler by swapping in a temporary no-op.
            // Cannot pass null — JLine's handler map is a ConcurrentHashMap
            // which throws NPE on null values.
            Terminal.SignalHandler origIntHandler = terminal.handle(Terminal.Signal.INT, sig -> {});

            sigintHandler = sig -> {
                cancelSignal.set(true);
                Process p = activeProcess;
                if (p != null && p.isAlive()) {
                    killProcess(p);
                }
                Thread wt = waitingThread;
                if (wt != null) {
                    wt.interrupt();
                }
            };

            // Install via JLine terminal (fires when JLine is reading input)
            terminal.handle(Terminal.Signal.INT, sig -> {
                if (agentBusy) {
                    // Agent is running — cancel it
                    sigintHandler.handle(null);
                } else if (origIntHandler != null) {
                    // Agent idle — delegate to JLine's handler (UserInterruptException)
                    origIntHandler.handle(sig);
                }
            });

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

            // Bind Escape to cancel
            bindCancelKey(lineReader);

            // Set up scroll region: chat scrolls in top area, input box fixed at bottom
            initScrollLayout();

            // Welcome — prints into scroll region
            printWelcomePanel(agentBinary);

            // If resuming, replay prior conversation turns and pre-set the agent
            // session ID so the very first message uses continuation flags
            // (--continue, --resume, --session) with the existing native session.
            if (resumeSessionId != null && !resumeSessionId.isBlank()) {
                replayConversationHistory(resumeSessionId, history);
                agentSessionId = resumeSessionId;
                firstMessageSent = true;
            }

            try {
                while (true) {
                    String line;
                    try {
                        // Re-establish scroll region before positioning (JLine may have reset it)
                        System.out.printf("\033[1;%dr", scrollBottom);
                        System.out.flush();
                        // Position cursor at prompt row (fixed area below scroll region)
                        positionAtPrompt();
                        ChatCompleter.schedulePostRestore();
                        line = lineReader.readLine(buildPrompt());
                    } catch (UserInterruptException e) {
                        // Ctrl+C at idle prompt → exit
                        break;
                    } catch (EndOfFileException e) {
                        break;
                    } catch (IOError e) {
                        break;
                    }

                    if (line == null || line.isBlank()) {
                        continue;
                    }
                    String trimmed = line.trim();

                    // Re-establish scroll region (JLine's readLine may have reset it)
                    System.out.printf("\033[1;%dr", scrollBottom);
                    System.out.flush();

                    if (trimmed.startsWith("/")) {
                        String result = handleSlashCommand(trimmed, lineReader, metrics);
                        if ("quit".equals(result)) break;
                    } else {
                        // Echo user message in scroll region, then dispatch
                        safePrintln(BOLD + "  > " + RESET + trimmed);
                        dispatchToAgent(trimmed, history, metrics);
                    }
                }
            } finally {
                // Kill persistent TUI process if running
                if (tuiProcess != null && tuiProcess.isAlive()) {
                    killProcess(tuiProcess);
                    tuiProcess = null;
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

                // Reset scroll region to full terminal BEFORE printing summary
                System.out.print("\033[r");
                // Clear the entire screen and move cursor home
                System.out.print("\033[2J\033[H");
                System.out.flush();

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
     *   Row scrollBottom+1        — top input border  ─────────
     *   Row scrollBottom+2        — prompt:  kompile [agent]> _
     *   Row scrollBottom+3        — bottom input border ─────────
     *   Row scrollBottom+4        — status line
     */
    private void initScrollLayout() {
        int h = terminal.getHeight();
        int w = terminal.getWidth();
        if (h <= 0) h = 24;
        if (w <= 0) w = 120;

        // Reserve 4 rows at bottom for input box + status
        scrollBottom = Math.max(4, h - 4);

        // Clear entire screen BEFORE setting scroll region
        // (some terminals only clear within the active scroll region)
        System.out.print("\033[r");          // Reset scroll region to full screen first
        System.out.print("\033[2J\033[H");   // Clear everything, cursor to home
        // Now draw the fixed input box on the cleared screen
        drawFixedInputBox();
        // THEN set scroll region — this protects the input box from scrolling
        System.out.printf("\033[1;%dr", scrollBottom);
        // Move cursor into scroll region for initial output (welcome panel)
        System.out.printf("\033[1;1H");
        System.out.flush();
    }

    /** Draw the fixed input box borders and status line below the scroll region. */
    private void drawFixedInputBox() {
        int w = terminal.getWidth();
        if (w <= 0) w = 120;
        String border = DIM + "\u2500".repeat(w) + RESET;

        // Top border
        System.out.printf("\033[%d;1H\033[2K%s", scrollBottom + 1, border);
        // Prompt row — readLine fills this
        System.out.printf("\033[%d;1H\033[2K", scrollBottom + 2);
        // Bottom border
        System.out.printf("\033[%d;1H\033[2K%s", scrollBottom + 3, border);
        // Status line
        updateStatusLine("idle");
        System.out.flush();
    }

    /** Update the status line below the input box. */
    void updateStatusLine(String status) {
        int w = terminal.getWidth();
        if (w <= 0) w = 120;
        String enforcerTag = enforcerEvaluator != null ? " · enforcer active" : "";
        String statusText = DIM + "  kompile [" + agent + "] · " + status + enforcerTag
                + " · Esc cancel · /quit exit" + RESET;
        System.out.printf("\033[%d;1H\033[2K%s", scrollBottom + 4, statusText);
        System.out.flush();
    }

    /** Position cursor at prompt row for readLine and redraw input box borders. */
    private void positionAtPrompt() {
        // Redraw the input box to ensure borders are intact after agent output
        drawFixedInputBox();
        // Move cursor to the prompt row and clear it for readLine
        System.out.printf("\033[%d;1H\033[2K", scrollBottom + 2);
        System.out.flush();
    }

    // ── Thread-safe output (prints into scroll region) ────────────────────

    /**
     * Prints a line of text into the scroll region.
     * Moves cursor to scrollBottom, prints the text + newline (which scrolls
     * the region content up), then redraws the entire fixed input box below
     * to guarantee it's never corrupted.
     */
    private synchronized void safePrintln(String text) {
        // Move cursor to last row of scroll region and print text
        System.out.printf("\033[%d;1H\033[2K%s", scrollBottom, text);
        // Newline at the bottom of the scroll region triggers scroll-up
        System.out.print("\n");
        // Redraw the FULL input box (borders + status) — belt-and-suspenders
        // to guarantee they're never corrupted by scroll, cursor drift, or JLine
        drawFixedInputBox();
    }

    /** Prints an empty line in the scroll region. */
    private void safePrintln() {
        safePrintln("");
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
                        String rendered = ascii.renderMarkdown(text);
                        for (String rl : rendered.split("\n", -1)) {
                            buf.add("  " + rl);
                        }
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
        String rendered = ascii.renderMarkdown(content);
        for (String rl : rendered.split("\n", -1)) {
            buf.add("  " + rl);
        }
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
     * Send a user message to the agent as a one-shot non-interactive subprocess.
     * The agent runs with {@code -p} (or equivalent), processes the message,
     * produces output, and exits. Output is captured via a chunk-based reader
     * (no PTY wrapping) and rendered through kompile's UI.
     *
     * @return the agent's text response (for follow-up question detection)
     */
    private String sendToAgent(String message, ChatHistory history, ChatSessionMetrics metrics) {
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

        List<String> agentCmd = buildCommand(agentBinary, message);

        renderer.setTerminalTitle("Kompiling... (" + agent + ")");
        updateStatusLine("running");
        // Start spinner pinned to the last row of the scroll region (above input border)
        TerminalRenderer.SpinnerHandle spinner = renderer.startPinnedSpinner(scrollBottom, " (" + agent + ")");

        StringBuilder fullText = new StringBuilder();
        StringBuilder pendingText = new StringBuilder();
        List<String> toolCalls = new ArrayList<>();
        long turnStart = System.currentTimeMillis();
        AtomicBoolean spinnerStopped = new AtomicBoolean(false);

        try {
            List<String> wrappedCmd = wrapWithPty(agentCmd);

            ProcessBuilder pb = new ProcessBuilder(wrappedCmd);
            pb.directory(new File(workingDir).getAbsoluteFile());
            pb.redirectErrorStream(true);

            Map<String, String> env = pb.environment();
            inheritEnv(env, "PATH", "HOME", "USER", "SHELL", "LANG", "LC_ALL",
                    "JAVA_HOME", "MAVEN_HOME", "TERM", "COLORTERM",
                    "ANTHROPIC_API_KEY", "OPENAI_API_KEY", "GOOGLE_API_KEY");
            if (enforcerExtraEnv != null) {
                env.putAll(enforcerExtraEnv);
            }

            Process process = pb.start();
            activeProcess = process;

            agentStdin = process.getOutputStream();
            interactiveQueue.clear();

            Thread outputReader = new Thread(() -> {
                try {
                    InputStreamReader isr = new InputStreamReader(
                            process.getInputStream(), StandardCharsets.UTF_8);
                    char[] buf = new char[4096];
                    StringBuilder lineBuffer = new StringBuilder();
                    int n;
                    while ((n = isr.read(buf)) != -1) {
                        if (cancelSignal.get() || Thread.currentThread().isInterrupted()) break;
                        for (int i = 0; i < n; i++) {
                            char c = buf[i];
                            if (c == '\n') {
                                processOutputLine(lineBuffer.toString(), fullText, toolCalls,
                                        metrics, spinner, spinnerStopped, pendingText);
                                lineBuffer.setLength(0);
                            } else if (c == '\r') {
                                if (lineBuffer.length() > 0) {
                                    processOutputLine(lineBuffer.toString(), fullText, toolCalls,
                                            metrics, spinner, spinnerStopped, pendingText);
                                    lineBuffer.setLength(0);
                                }
                                if (i + 1 < n && buf[i + 1] == '\n') i++;
                            } else {
                                lineBuffer.append(c);
                            }
                        }
                    }
                    if (lineBuffer.length() > 0) {
                        processOutputLine(lineBuffer.toString(), fullText, toolCalls,
                                metrics, spinner, spinnerStopped, pendingText);
                    }
                } catch (IOException e) {
                    // Stream closed — expected
                }
            }, "emulated-output-reader");
            outputReader.setDaemon(true);
            outputReader.start();

            sun.misc.SignalHandler prevSigHandler = null;
            try {
                prevSigHandler = sun.misc.Signal.handle(new sun.misc.Signal("INT"), sigintHandler);
            } catch (IllegalArgumentException ignored) {}
            waitingThread = Thread.currentThread();

            lastOutputTime.set(System.currentTimeMillis());
            try {
                while (!process.waitFor(200, TimeUnit.MILLISECONDS)) {
                    if (cancelSignal.get()) {
                        killProcess(process);
                        break;
                    }
                    PassthroughStreamParser.PassthroughEvent interactiveEvent = interactiveQueue.poll();
                    if (interactiveEvent != null) {
                        handleInteractiveEvent(interactiveEvent, spinner, spinnerStopped);
                    }
                }
            } catch (InterruptedException e) {
                killProcess(process);
            } finally {
                waitingThread = null;
                Thread.interrupted();
                if (prevSigHandler != null) {
                    try {
                        sun.misc.Signal.handle(new sun.misc.Signal("INT"), prevSigHandler);
                    } catch (IllegalArgumentException ignored) {}
                }
            }

            try {
                if (agentStdin != null) agentStdin.close();
            } catch (IOException ignored) {}
            agentStdin = null;

            outputReader.join(2000);
            if (outputReader.isAlive()) {
                process.getInputStream().close();
                outputReader.join(500);
            }

            flushPendingText(pendingText, spinner, spinnerStopped);
            activeProcess = null;

        } catch (Exception e) {
            if (!cancelSignal.get()) {
                safePrintln(renderer.red("\n  Error running agent: " + e.getMessage()));
            }
        } finally {
            spinner.stop();
            activeProcess = null;
        }

        long turnDuration = System.currentTimeMillis() - turnStart;

        if (cancelSignal.get()) {
            safePrintln();
            safePrintln(renderer.yellow("  Cancelled."));
            history.logSystem("User cancelled agent response after " + turnDuration + "ms");
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
        updateStatusLine("idle");
        return fullText.toString();
    }

    /**
     * Persistent TUI agent path (OpenCode). The TUI process is launched once
     * and kept alive across messages. Each message writes to stdin and reads
     * the streamed response from stdout via the PTY.
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
        // Start spinner pinned to the last row of the scroll region (above input border)
        TerminalRenderer.SpinnerHandle spinner = renderer.startPinnedSpinner(scrollBottom, " (" + agent + ")");

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

        try {
            // Launch the persistent TUI process on first message
            if (tuiProcess == null || !tuiProcess.isAlive()) {
                List<String> agentCmd = buildCommand(agentBinary, message);
                List<String> wrappedCmd = wrapWithPty(agentCmd);

                ProcessBuilder pb = new ProcessBuilder(wrappedCmd);
                pb.directory(new File(workingDir).getAbsoluteFile());
                pb.redirectErrorStream(true);

                Map<String, String> env = pb.environment();
                inheritEnv(env, "PATH", "HOME", "USER", "SHELL", "LANG", "LC_ALL",
                        "JAVA_HOME", "MAVEN_HOME", "TERM", "COLORTERM",
                        "ANTHROPIC_API_KEY", "OPENAI_API_KEY", "GOOGLE_API_KEY");

                Process process = pb.start();
                tuiProcess = process;
                activeProcess = process;
                agentStdin = process.getOutputStream();

                // Persistent output reader — chunk-based (no line buffering).
                // TUI apps use cursor positioning instead of newlines, so we
                // process raw chunks on every read() call.
                tuiOutputReader = new Thread(() -> {
                    try {
                        InputStreamReader isr = new InputStreamReader(
                                process.getInputStream(), StandardCharsets.UTF_8);
                        char[] buf = new char[4096];
                        int n;
                        while ((n = isr.read(buf)) != -1) {
                            if (Thread.currentThread().isInterrupted()) break;
                            String rawChunk = new String(buf, 0, n);
                            processTuiOutputChunk(rawChunk);
                        }
                    } catch (IOException e) {
                        // Stream closed — expected on shutdown
                    }
                }, "tui-output-reader");
                tuiOutputReader.setDaemon(true);
                tuiOutputReader.start();

                // Wait for the TUI to initialize before sending the message
                Thread.sleep(2000);
            }

            // Now wire up buffers so the response goes into this message's state
            tuiFullText = fullText;
            tuiToolCalls = toolCalls;
            tuiPendingText = pendingText;

            // Send the message via stdin
            agentStdin.write((message + "\n").getBytes(StandardCharsets.UTF_8));
            agentStdin.flush();
            long messageSentAt = System.currentTimeMillis();
            lastOutputTime.set(messageSentAt);

            // Wait for the response to complete (stall detection).
            // The TUI process stays alive — we just wait until output stops.
            // Use conservative timeouts: LLM API calls can take 10+ seconds to start,
            // so don't allow stall detection until at least 15s after message send.
            while (tuiProcess.isAlive()) {
                if (cancelSignal.get()) {
                    killProcess(tuiProcess);
                    tuiProcess = null;
                    break;
                }
                long elapsed = System.currentTimeMillis() - messageSentAt;
                long sinceLastOutput = System.currentTimeMillis() - lastOutputTime.get();
                // Require: 15s minimum wait, meaningful text, and 5s of silence
                if (elapsed > 15_000 && fullText.length() > 0 && sinceLastOutput > 5_000) {
                    break;
                }
                Thread.sleep(200);
            }

            flushPendingText(pendingText, spinner, spinnerStopped);

        } catch (Exception e) {
            if (!cancelSignal.get()) {
                safePrintln(renderer.red("\n  Error running agent: " + e.getMessage()));
            }
        } finally {
            spinner.stop();
            // Don't null out activeProcess or kill the TUI — it persists
        }

        long turnDuration = System.currentTimeMillis() - turnStart;

        if (cancelSignal.get()) {
            safePrintln();
            safePrintln(renderer.yellow("  Cancelled."));
            history.logSystem("User cancelled agent response after " + turnDuration + "ms");
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

    /**
     * Routes a chunk of raw TUI output to the current message's buffers.
     * Called by the persistent TUI output reader thread on every read().
     * <p>
     * TUI apps use cursor positioning instead of newlines, so we process
     * raw chunks rather than waiting for line delimiters that may never come.
     * Uses aggressive stripping for TUI-specific escape sequences and
     * decorative unicode characters (box drawing, block elements, bullets).
     */
    private synchronized void processTuiOutputChunk(String rawChunk) {
        // Aggressive ANSI stripping — covers TUI sequences with space params,
        // ~ terminators, DCS sequences, and stray single-char escapes
        String stripped = rawChunk
                .replaceAll("\033\\[[0-9;?><\\s]*[a-zA-Z~@]", "")  // CSI (incl. space params, ~ term)
                .replaceAll("\033\\][^\007\033]*(?:\007|\033\\\\)", "") // OSC
                .replaceAll("\033P[^\033]*\033\\\\", "")              // DCS
                .replaceAll("\033[()][0-9A-B]", "")                  // Character sets
                .replaceAll("\033[>=<]", "")                          // Mode set
                .replaceAll("\033\\\\", "")                           // ST
                .replaceAll("\033.", "");                              // Any remaining ESC + char

        // Strip control characters
        stripped = stripped.replaceAll("[\\x00-\\x1F\\x7F]+", " ");

        // Strip TUI decorative unicode (box drawing, block elements, braille, bullets)
        stripped = stripped.replaceAll("[\\u2500-\\u259F\\u2800-\\u28FF\u00b7\u2022\u25c6\u25c7\u25cb\u25cf\u25a1\u25a0\u25b8\u25b9\u25ba\u25bb\u25aa\u25ab\u2299\u2297\u2295]", "");

        // Collapse whitespace and trim
        stripped = stripped.replaceAll("\\s{2,}", " ").trim();

        if (stripped.length() < 3) return; // Too short — TUI noise

        // Filter out TUI status-line fragments: short strings that are mostly
        // non-alphabetic or look like cursor addresses / mode indicators.
        // Real content has words; TUI noise is short gibberish like "~ 1tcATBDOm", "/ 1".
        if (stripped.length() < 20) {
            String alpha = stripped.replaceAll("[^a-zA-Z]", "");
            long spaceCount = stripped.chars().filter(c -> c == ' ').count();
            // If less than 40% alphabetic or no spaces in a multi-char string, it's noise
            if (alpha.length() < stripped.length() * 0.4 || (stripped.length() > 3 && spaceCount == 0 && alpha.length() < 5)) {
                return;
            }
        }

        // Update timestamp only on real text — stall detection needs this
        lastOutputTime.set(System.currentTimeMillis());

        StringBuilder ft = tuiFullText;
        if (ft == null) return; // Pre-message init or between messages — discard

        TerminalRenderer.SpinnerHandle sp = tuiSpinner;
        AtomicBoolean ss = tuiSpinnerStopped;
        if (sp != null && ss != null && ss.compareAndSet(false, true)) {
            sp.stop();
            safePrintln();
        }
        safePrintln("  " + stripped);
        ft.append(stripped).append("\n");
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
            // No structured parser matched — fall back to ANSI-stripped plain text
            if (!isStructuredAgent(agentLower)) {
                String cleaned = stripAnsi(line).trim();
                if (!cleaned.isEmpty()) {
                    if (spinnerStopped.compareAndSet(false, true)) {
                        spinner.stop();
                        safePrintln();
                    }
                    safePrintln("  " + cleaned);
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
            if (spinnerStopped.compareAndSet(false, true)) {
                spinner.stop();
                safePrintln();
            }
            fullText.append(tc.text());
            // Render text immediately — don't buffer until TurnComplete.
            // This gives real-time streaming output as the agent responds.
            String rendered = ascii.renderMarkdown(tc.text());
            for (String rl : rendered.split("\n", -1)) {
                safePrintln("  " + rl);
            }
        } else if (event instanceof PassthroughStreamParser.ToolUse tu) {
            flushPendingText(pendingText, spinner, spinnerStopped);
            if (spinnerStopped.compareAndSet(false, true)) {
                spinner.stop();
                safePrintln();
            }
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
                safePrintln(renderer.dim("  " + output));
            }
            String status = toolComplete.exitCode() >= 0
                    ? "exit " + toolComplete.exitCode()
                    : "completed";
            safePrintln(renderer.dim("  [" + TerminalRenderer.prettifyToolName(toolComplete.name()) + " " + status + "]"));
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
            if (tc.durationMs() > 0) stats.append(formatDuration(tc.durationMs()));
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

        String rendered = ascii.renderMarkdown(pendingText.toString());
        // Indent each line for consistent formatting
        for (String rl : rendered.split("\n", -1)) {
            safePrintln("  " + rl);
        }
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
            os.write((text + "\n").getBytes(StandardCharsets.UTF_8));
            os.flush();
        } catch (IOException e) {
            // Agent may have closed stdin — this is expected after exit
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
     * Build the agent command for non-interactive execution.
     * Each agent gets its specific flags for single-prompt mode.
     */
    private List<String> buildCommand(String binary, String message) {
        List<String> cmd = new ArrayList<>();
        cmd.add(binary);

        String name = agent.toLowerCase();

        if (name.contains("claude")) {
            // Claude Code: -p message --output-format stream-json --verbose [--continue]
            cmd.add("-p");
            cmd.add(message);
            cmd.add("--output-format");
            cmd.add("stream-json");
            cmd.add("--verbose");
            if (skipPermissions) {
                cmd.add("--dangerously-skip-permissions");
            }
            if (firstMessageSent) {
                cmd.add("--continue");
            }
        } else if (name.contains("codex")) {
            // Codex: exec [resume <id>|--last] --json [--sandbox workspace-write] message
            if (firstMessageSent) {
                cmd.add("exec");
                cmd.add("resume");
                if (agentSessionId != null) {
                    cmd.add(agentSessionId);
                } else {
                    cmd.add("--last");
                }
                cmd.add("--json");
                if (skipPermissions) {
                    cmd.add("--sandbox");
                    cmd.add("workspace-write");
                }
                cmd.add(message);
            } else {
                cmd.add("exec");
                cmd.add("--json");
                if (skipPermissions) {
                    cmd.add("--sandbox");
                    cmd.add("workspace-write");
                }
                cmd.add(message);
            }
        } else if (name.contains("gemini")) {
            // Gemini: -p message -o stream-json [--yolo] [--resume latest]
            cmd.add("-p");
            cmd.add(message);
            cmd.add("-o");
            cmd.add("stream-json");
            if (skipPermissions) {
                cmd.add("--yolo");
            }
            if (firstMessageSent) {
                cmd.add("--resume");
                cmd.add("latest");
            }
        } else if (name.contains("qwen")) {
            // Qwen: -o stream-json [--yolo] [--continue] message
            cmd.add("-o");
            cmd.add("stream-json");
            if (skipPermissions) {
                cmd.add("--yolo");
            }
            if (firstMessageSent) {
                cmd.add("--continue");
            }
            cmd.add(message);
        } else if (name.contains("opencode")) {
            // OpenCode: run --format json [--dangerously-skip-permissions] [--session id] message
            // Uses structured JSON output — same as Claude's stream-json.
            // This avoids the PTY/TUI path that produces garbage from cell-by-cell rendering.
            cmd.add("run");
            cmd.add("--format");
            cmd.add("json");
            if (skipPermissions) {
                cmd.add("--dangerously-skip-permissions");
            }
            if (firstMessageSent && agentSessionId != null) {
                cmd.add("--session");
                cmd.add(agentSessionId);
            }
            cmd.add(message);
        } else {
            // Unknown agent — best-effort with -p
            cmd.add("-p");
            cmd.add(message);
        }

        return cmd;
    }

    // ── Slash commands ─────────────────────────────────────────────────────

    private String handleSlashCommand(String input, LineReader lineReader, ChatSessionMetrics metrics) {
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
                  /status            Show session metrics
                  /clear             Clear the screen
                  /mode              Show current mode
                  /help              Show this help
                  /quit              Exit

                Keyboard shortcuts:
                  Ctrl+C             Cancel in-progress agent call
                  Escape             Cancel in-progress agent call

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
        body.append("Context:   ").append(firstMessageSent ? "active (--continue)" : "new session");
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
                .append(" as a non-interactive subprocess.\n");
        body.append("Agent output is cleaned and rendered through kompile's UI.\n");
        if (enforcerEvaluator != null) {
            body.append("The judge monitors output and can interrupt on violations.\n");
        }
        body.append("Slash commands (/help, /quit, /agent, /status) remain active.\n");
        body.append("\n");
        body.append(DIM).append("Ctrl+C to cancel · /agent to switch · /quit to exit").append(RESET);

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
                    KeyMap.key(impl.getTerminal(), org.jline.utils.InfoCmp.Capability.key_exit)
            );

            impl.getKeyMaps().get(LineReader.EMACS).bind(
                    new org.jline.reader.Reference("cancel-emulated"),
                    "\033"
            );

            impl.setVariable("cancel-emulated", (org.jline.reader.Widget) () -> {
                if (activeProcess != null && activeProcess.isAlive()) {
                    cancelSignal.set(true);
                    killProcess(activeProcess);
                    safePrintln("");
                    safePrintln(renderer.yellow("  Cancelling..."));
                }
                return true;
            });
        }
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
    private static List<String> wrapWithPty(List<String> cmd) {
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

        // Build: script -q /dev/null -c "arg1 arg2 ..."
        // On macOS, script syntax is: script -q /dev/null cmd arg1 arg2
        boolean isMac = System.getProperty("os.name", "").toLowerCase().contains("mac");
        List<String> wrapped = new ArrayList<>();
        wrapped.add("script");
        wrapped.add("-q");
        if (isMac) {
            wrapped.add("/dev/null");
            wrapped.addAll(cmd);
        } else {
            // Linux: script -q /dev/null -c "cmd arg1 arg2"
            wrapped.add("/dev/null");
            wrapped.add("-c");
            StringBuilder cmdStr = new StringBuilder();
            for (int i = 0; i < cmd.size(); i++) {
                if (i > 0) cmdStr.append(' ');
                // Quote arguments that contain spaces
                String arg = cmd.get(i);
                if (arg.contains(" ") || arg.contains("'") || arg.contains("\"")) {
                    cmdStr.append("'").append(arg.replace("'", "'\\''")).append("'");
                } else {
                    cmdStr.append(arg);
                }
            }
            wrapped.add(cmdStr.toString());
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
        sb.append(CYAN).append("> ").append(RESET);
        return sb.toString();
    }

    /**
     * Dispatches a message to the agent synchronously.
     * When enforcer is configured, wraps the call with enforcement (retry on violations).
     * The REPL blocks until the agent finishes, giving real-time streaming output.
     */
    private void dispatchToAgent(String message, ChatHistory history, ChatSessionMetrics metrics) {
        agentBusy = true;
        try {
            if (enforcerService != null && enforcerPolicy != null) {
                dispatchEnforced(message, history, metrics);
            } else {
                sendToAgent(message, history, metrics);
            }
        } finally {
            agentBusy = false;
        }
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
            long pid = process.pid();
            boolean isUnix = !System.getProperty("os.name", "").toLowerCase().startsWith("win");
            if (isUnix) {
                // SIGINT first (what Ctrl+C sends)
                new ProcessBuilder("kill", "-INT", String.valueOf(pid))
                        .redirectErrorStream(true).start().waitFor();
                if (process.isAlive()) {
                    Thread.sleep(500);
                    new ProcessBuilder("kill", "-TERM", String.valueOf(pid))
                            .redirectErrorStream(true).start().waitFor();
                }
                if (process.isAlive()) {
                    Thread.sleep(300);
                    new ProcessBuilder("kill", "-9", String.valueOf(pid))
                            .redirectErrorStream(true).start().waitFor();
                }
            } else {
                process.destroyForcibly();
            }
        } catch (Exception e) {
            process.destroyForcibly();
        }
    }

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

    private static String truncate(String s, int maxLen) {
        if (s == null) return "";
        return s.length() <= maxLen ? s : s.substring(0, maxLen - 3) + "...";
    }

    private static String formatDuration(long ms) {
        if (ms < 1000) return ms + "ms";
        if (ms < 60_000) return String.format("%.1fs", ms / 1000.0);
        long mins = ms / 60_000;
        long secs = (ms % 60_000) / 1000;
        return mins + "m " + secs + "s";
    }
}
