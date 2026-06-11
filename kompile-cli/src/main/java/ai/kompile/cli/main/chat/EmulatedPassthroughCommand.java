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
            ascii = new AsciiRenderer(renderer);

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

            // Welcome
            printWelcomePanel(agentBinary);

            try {
                while (true) {
                    String line;
                    try {
                        printTopBorder();
                        ChatCompleter.schedulePostRestore();
                        line = lineReader.readLine(buildPrompt());
                    } catch (UserInterruptException e) {
                        // Ctrl+C at idle prompt → exit
                        System.out.println();
                        break;
                    } catch (EndOfFileException e) {
                        break;
                    } catch (IOError e) {
                        break;
                    }

                    // Clear the old input box (top border + prompt) so
                    // input boxes don't stack on every Enter press.
                    clearInputBox();

                    if (line == null || line.isBlank()) {
                        continue;
                    }
                    String trimmed = line.trim();

                    if (trimmed.startsWith("/")) {
                        String result = handleSlashCommand(trimmed, lineReader, metrics);
                        if ("quit".equals(result)) break;
                    } else {
                        // Echo user message in chat area, then dispatch synchronously
                        System.out.println(BOLD + "  > " + RESET + trimmed);
                        System.out.println(DIM + "  " + agent + " responding..." + RESET);
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

                System.out.println();
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

    // ── Thread-safe output ──────────────────────────────────────────────────

    /**
     * Prints a line of text safely.
     */
    private void safePrintln(String text) {
        System.out.println(text);
    }

    /** Prints an empty line safely. */
    private void safePrintln() {
        safePrintln("");
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
        // Route TUI-based agents to the persistent process path
        if (agent.toLowerCase().contains("opencode")) {
            return sendToTuiAgent(message, history, metrics);
        }

        String agentBinary = resolveAgent(agent);
        if (agentBinary == null) {
            System.out.println(renderer.red("  Agent '" + agent + "' not found on PATH."));
            System.out.println(renderer.dim("  Supported agents: " + String.join(", ",
                    ai.kompile.cli.main.chat.config.ChatConfig.getPassthroughAgentOrder())));
            return "";
        }

        cancelSignal.set(false);
        history.logUserMessage(message);
        metrics.recordUserTurn(message);

        List<String> agentCmd = buildCommand(agentBinary, message);

        renderer.setTerminalTitle("Generating... (" + agent + ")");
        TerminalRenderer.SpinnerHandle spinner = renderer.startGeneratingSpinner(agent);

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
            System.out.println(renderer.red("  Agent '" + agent + "' not found on PATH."));
            System.out.println(renderer.dim("  Supported agents: " + String.join(", ",
                    ai.kompile.cli.main.chat.config.ChatConfig.getPassthroughAgentOrder())));
            return "";
        }

        cancelSignal.set(false);
        history.logUserMessage(message);
        metrics.recordUserTurn(message);

        renderer.setTerminalTitle("Generating... (" + agent + ")");
        TerminalRenderer.SpinnerHandle spinner = renderer.startGeneratingSpinner(agent);

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
            System.out.println();
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
        System.out.println();
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

        System.out.println(ascii.panel("Agent Question", body.toString()));

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
        System.out.println(renderer.dim("  → Sent: " + answer));
        System.out.println();
    }

    /**
     * Present an approval request for a command and read the user's decision.
     */
    private void handleInteractiveApproval(PassthroughStreamParser.InteractiveApproval ia) {
        System.out.println();

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

        System.out.println(ascii.panel("Approval Required", body.toString()));

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
        System.out.println(color + "  → " + decision + RESET);
        System.out.println();
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
            // Codex: exec [resume <id>|--last] --json [--full-auto] message
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
                    cmd.add("--full-auto");
                }
                cmd.add(message);
            } else {
                cmd.add("exec");
                cmd.add("--json");
                if (skipPermissions) {
                    cmd.add("--full-auto");
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
            // Interactive TUI mode — streams text in real time via PTY.
            // Message is written to stdin after the TUI initializes.
            // Note: --dangerously-skip-permissions is only valid for 'opencode run',
            // not the base TUI command, so we don't pass it here.
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
            case "/clear" -> { System.out.print("\033[2J\033[H"); System.out.flush(); }
            case "/mode" -> {
                System.out.println(renderer.dim("  Current mode: emulated passthrough (" + agent + ")"));
                System.out.println(renderer.dim("  Available: emulated, passthrough, standard"));
            }
            case "/enforcer" -> handleEnforcerSlash(rest.trim());
            default -> {
                // Forward unrecognized slash commands to the underlying agent
                String agentBinary = AgentCommandForwarder.resolveAgentBinary(agent);
                if (agentBinary == null) {
                    System.out.println(renderer.dim("  Unknown command: " + cmd + " (agent '" + agent + "' not found on PATH)"));
                } else {
                    AgentCommandForwarder forwarder = new AgentCommandForwarder(
                            Path.of(workingDir).toAbsolutePath().normalize().toString());
                    String slashCmd = rest.isEmpty() ? cmd : cmd + " " + rest;
                    AgentCommandForwarder.AgentCommand agentCmd = forwarder.mapSlashCommand(slashCmd, agentBinary, agent);
                    if (agentCmd != null) {
                        System.out.println(renderer.dim("  → " + agentCmd.label()));
                        forwarder.executeWithRealtimeOutput(agentCmd);
                    } else {
                        System.out.println(renderer.dim("  Command " + cmd + " not supported for " + agent));
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
                    System.out.println(renderer.green("  Enforcer configured."));
                } else {
                    System.out.println("  Setup cancelled.");
                }
            }
            case "show", "status" -> {
                ai.kompile.cli.main.chat.enforcer.EnforcerConfig config =
                        ai.kompile.cli.main.chat.enforcer.EnforcerConfig.load(wd);
                if (config == null) {
                    System.out.println(renderer.dim("  No enforcer config. Run /enforcer init to configure."));
                    return;
                }
                System.out.println();
                System.out.println("  Agent:         " + config.getAgent());
                System.out.println("  Mode:          " + (config.isKeywordMode() ? "keyword" : "LLM judge"));
                System.out.println("  Max retries:   " + config.getMaxCorrections());
                System.out.println("  Diff archive:  " + (config.isArchiveDiffs() ? "enabled" : "disabled"));
                if (!config.getBannedTools().isEmpty()) {
                    System.out.println("  Banned tools:  " + String.join(", ", config.getBannedTools()));
                }
                System.out.println();
            }
            case "delete" -> {
                try {
                    ai.kompile.cli.main.chat.enforcer.EnforcerConfig.delete(wd);
                    System.out.println(renderer.dim("  Enforcer config deleted."));
                } catch (Exception e) {
                    System.out.println(renderer.yellow("  Failed: " + e.getMessage()));
                }
            }
            default -> System.out.println(renderer.dim("  Usage: /enforcer [init|show|delete]"));
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

        System.out.println();
        System.out.println(ascii.panel("Emulated Passthrough Help", body));
        System.out.println();
    }

    private void switchAgent(String newAgent, LineReader lineReader) {
        if (newAgent == null) {
            System.out.println("  Available: claude, codex, qwen, opencode, gemini (or any binary on PATH)");
            try {
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
            System.out.println(renderer.yellow("  Agent '" + newAgent + "' not found on PATH."));
            System.out.println(renderer.dim("  Supported agents: " + String.join(", ",
                    ai.kompile.cli.main.chat.config.ChatConfig.getPassthroughAgentOrder())));
            return;
        }

        agent = newAgent;
        firstMessageSent = false;
        agentSessionId = null;
        System.out.println(renderer.green("  Switched to " + agent));
        System.out.println(renderer.dim("  Conversation context reset (new agent session)."));
        renderer.setTerminalTitle("kompile [" + agent + "]");
    }

    private void printStatus(ChatSessionMetrics metrics) {
        StringBuilder body = new StringBuilder();
        body.append("Agent:     ").append(agent).append(" (emulated passthrough)\n");
        body.append("Messages:  ").append(metrics.getUserTurns()).append(" sent, ")
                .append(metrics.getAssistantTurns()).append(" received\n");
        if (metrics.getTotalToolCalls() > 0) {
            body.append("Tools:     ").append(metrics.getTotalToolCalls()).append(" calls\n");
        }
        body.append("Duration:  ").append(metrics.formatDuration(metrics.getSessionDuration())).append("\n");
        body.append("Context:   ").append(firstMessageSent ? "active (--continue)" : "new session");
        System.out.println();
        System.out.println(ascii.panel("Session Status", body.toString()));
        System.out.println();
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
        body.append("Mode:    Emulated Passthrough\n");
        body.append("\n");
        body.append("Each message is sent to ").append(agent)
                .append(" as a non-interactive subprocess.\n");
        body.append("Agent output is cleaned and rendered through kompile's UI.\n");
        body.append("Slash commands (/help, /quit, /agent, /status) remain active.\n");
        body.append("\n");
        body.append(DIM).append("Ctrl+C to cancel · /agent to switch · /quit to exit").append(RESET);

        System.out.println();
        System.out.println(ascii.panel("Kompile Emulated Passthrough", body.toString()));
        System.out.println();

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
                    System.out.println();
                    System.out.println(renderer.yellow("  Cancelling..."));
                    System.out.flush();
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
                || agentLower.contains("codex");
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
        System.out.println();
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

    /** Prints the top border line above the input area. */
    private void printTopBorder() {
        int termWidth = 80;
        if (terminal != null) {
            int w = terminal.getWidth();
            if (w > 0) termWidth = w;
        }
        int borderWidth = Math.max(20, Math.min(termWidth, 200));
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
     * The REPL blocks until the agent finishes, giving real-time streaming output.
     */
    private void dispatchToAgent(String message, ChatHistory history, ChatSessionMetrics metrics) {
        agentBusy = true;
        try {
            sendToAgent(message, history, metrics);
        } finally {
            agentBusy = false;
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
