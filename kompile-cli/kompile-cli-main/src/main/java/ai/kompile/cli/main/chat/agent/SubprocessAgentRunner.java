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

package ai.kompile.cli.main.chat.agent;

import ai.kompile.cli.main.chat.ChatHistory;
import ai.kompile.utils.FormatUtils;
import ai.kompile.cli.main.chat.ChatSessionMetrics;
import ai.kompile.cli.main.chat.McpUrlResolver;
import ai.kompile.cli.main.chat.PassthroughStreamParser;
import ai.kompile.cli.main.chat.config.SystemPromptManager;
import ai.kompile.cli.main.chat.render.AsciiRenderer;
import ai.kompile.cli.main.chat.render.TerminalRenderer;
import ai.kompile.cli.main.chat.skill.CustomSkillLoader;
import ai.kompile.cli.main.chat.skill.SkillRegistry;
import ai.kompile.cli.main.chat.skill.SkillsInjection;
import ai.kompile.cli.main.chat.ToolCallIndex;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Runs external CLI agents (Claude Code, Codex, Gemini, OpenCode, Qwen, etc.)
 * as per-message subprocesses. Extracted from EmulatedPassthroughCommand so that
 * any TUI (ChatRepl, Lanterna, etc.) can drive agent subprocesses.
 * <p>
 * Each call to {@link #runMessage} launches the agent in non-interactive mode,
 * captures output, parses structured events, and renders through the shared
 * {@link TerminalRenderer} and {@link AsciiRenderer}.
 */
public class SubprocessAgentRunner {

    public interface RealtimeMonitor {
        default MonitorDecision onTextChunk(String chunk, String fullText) {
            return MonitorDecision.continueRun();
        }

        default MonitorDecision onToolUse(String toolName, String input) {
            return MonitorDecision.continueRun();
        }
    }

    public record MonitorDecision(boolean interrupt, String reason, String correctionPrompt) {
        public static MonitorDecision continueRun() {
            return new MonitorDecision(false, "", "");
        }

        public static MonitorDecision interrupt(String reason, String correctionPrompt) {
            return new MonitorDecision(true, reason, correctionPrompt);
        }
    }

    private String agent;
    private final String workingDir;
    private volatile boolean skipPermissions;
    private final boolean injectTools;
    private final String kompileUrl;
    private final int mcpPort;
    private final SystemPromptManager systemPromptManager;

    private final TerminalRenderer renderer;
    private final AsciiRenderer ascii;
    private final PassthroughStreamParser parser = new PassthroughStreamParser();
    private final McpUrlResolver mcpUrlResolver = new McpUrlResolver();

    // Subprocess state
    private volatile Process activeProcess;
    private volatile Thread waitingThread;
    private final AtomicBoolean cancelSignal = new AtomicBoolean(false);
    private volatile OutputStream agentStdin;
    private final LinkedBlockingQueue<PassthroughStreamParser.PassthroughEvent> interactiveQueue = new LinkedBlockingQueue<>();
    // Tracks last output timestamp for TUI stall detection
    private final AtomicLong lastOutputTime = new AtomicLong(0);

    // Persistent TUI process (OpenCode) — launched once, reused across messages
    private volatile Process tuiProcess;
    private volatile Thread tuiOutputReader;
    private volatile StringBuilder tuiFullText;
    private volatile List<String> tuiToolCalls;
    private volatile ChatSessionMetrics tuiMetrics;
    private volatile ChatHistory tuiHistory;
    private volatile TerminalRenderer.SpinnerHandle tuiSpinner;
    private volatile AtomicBoolean tuiSpinnerStopped;
    private volatile StringBuilder tuiPendingText;

    // Multi-turn state
    private volatile boolean firstMessageSent = false;
    private volatile String agentSessionId = null;

    // Interactive input — the TUI supplies a callback to get user responses
    private Function<String, String> inputProvider;

    // Activity tracking — fires on each meaningful event so callers can display live status
    private volatile Consumer<String> activityListener;
    private volatile String currentActivity;
    private volatile String currentToolName;
    private volatile RealtimeMonitor realtimeMonitor;
    private volatile Map<String, String> extraEnvironment = Map.of();
    private volatile String monitorInterruptReason;
    private volatile boolean monitorSoftInterrupt;

    // Injected settings file path (for cleanup)
    private Path injectedSettingsFile;

    // Skills injection (loaded lazily on first injectSkills() call)
    private SkillsInjection skillsInjection;

    // Output consumer — when set, all text output goes through this instead of raw System.out
    private volatile Consumer<String> outputConsumer;

    // ANSI
    private static final String RESET = "\033[0m";
    private static final String CYAN = "\033[36m";
    private static final String GREEN = "\033[32m";
    private static final String YELLOW = "\033[33m";
    private static final String DIM = "\033[2m";
    private static final String BOLD = "\033[1m";

    private static final String ANSI_REGEX =
            "\033\\[[0-9;?><]*[a-zA-Z]"
            + "|\033\\].*?(?:\033\\\\|\007)"
            + "|\033[()][0-9A-B]"
            + "|\033[>=<]"
            + "|\033\\\\";

    public SubprocessAgentRunner(String agent, String workingDir, boolean skipPermissions,
                                  boolean injectTools, String kompileUrl, int mcpPort,
                                  SystemPromptManager systemPromptManager,
                                  TerminalRenderer renderer, AsciiRenderer ascii) {
        this.agent = agent;
        this.workingDir = workingDir;
        this.skipPermissions = skipPermissions;
        this.injectTools = injectTools;
        this.kompileUrl = kompileUrl;
        this.mcpPort = mcpPort;
        this.systemPromptManager = systemPromptManager;
        this.renderer = renderer;
        this.ascii = ascii;
    }

    public void setInputProvider(Function<String, String> inputProvider) {
        this.inputProvider = inputProvider;
    }

    /**
     * Set a listener that receives activity descriptions as the agent works.
     * Called from the output-reader thread on ToolUse, ThinkingChunk, TextChunk, and TurnComplete events.
     * The string is a provider-aware description like "claude: bash: ls -la" or "codex: exec: npm test".
     */
    public void setActivityListener(Consumer<String> listener) {
        this.activityListener = listener;
    }

    /**
     * Set a real-time monitor that can interrupt the active subprocess while
     * streamed text or tool-use events are being parsed.
     */
    public void setRealtimeMonitor(RealtimeMonitor realtimeMonitor) {
        this.realtimeMonitor = realtimeMonitor;
    }

    /**
     * Add environment variables to subprocesses launched by this runner.
     */
    public void setExtraEnvironment(Map<String, String> extraEnvironment) {
        this.extraEnvironment = extraEnvironment != null ? Map.copyOf(extraEnvironment) : Map.of();
    }

    /**
     * Set an output consumer — all streamed text output goes through this.
     * When null, falls back to System.out.println.
     */
    public void setOutputConsumer(Consumer<String> outputConsumer) {
        this.outputConsumer = outputConsumer;
    }

    /** Route a line of output through the consumer or System.out. */
    private void emitLine(String text) {
        Consumer<String> oc = outputConsumer;
        if (oc != null) {
            oc.accept(text);
        } else {
            System.out.println(text);
        }
    }

    /**
     * Returns the current activity string (what the agent is doing right now).
     * Thread-safe — can be polled from any thread (e.g., the 1s process-display-updater).
     */
    public String getCurrentActivity() {
        return currentActivity;
    }

    /**
     * Returns the name of the tool currently being executed, or null if idle/generating text.
     */
    public String getCurrentToolName() {
        return currentToolName;
    }

    /**
     * Returns a display-friendly agent name (e.g., "claude", "codex", "gemini").
     */
    public String getAgentDisplayName() {
        if (agent == null) return "agent";
        String name = agent.toLowerCase();
        // Strip path if it's a full path to a binary
        int slash = name.lastIndexOf('/');
        if (slash >= 0) name = name.substring(slash + 1);
        return name;
    }

    private void updateActivity(String activity) {
        this.currentActivity = activity;
        Consumer<String> listener = this.activityListener;
        if (listener != null) {
            try {
                listener.accept(activity);
            } catch (RuntimeException ignored) {}
        }
    }

    public String getAgent() { return agent; }

    public void setAgent(String agent) {
        this.agent = agent;
        this.firstMessageSent = false;
        this.agentSessionId = null;
    }

    /**
     * Update the skip-permissions flag at runtime.
     * Takes effect on the next subprocess launch (next message).
     */
    public void setSkipPermissions(boolean skipPermissions) {
        this.skipPermissions = skipPermissions;
    }

    public boolean isSkipPermissions() {
        return skipPermissions;
    }

    public boolean isFirstMessageSent() { return firstMessageSent; }

    /**
     * Inject MCP tools into the agent's working directory. Call once before first message.
     */
    public void injectMcpTools() {
        if (!injectTools) return;
        try {
            String sseUrl = mcpUrlResolver.resolveMcpUrl(kompileUrl, mcpPort);
            injectedSettingsFile = ai.kompile.cli.main.chat.mcp.McpToolInjection.injectTools(
                    Path.of(workingDir), agent, sseUrl);
            if (injectedSettingsFile != null) {
                String mode = (sseUrl != null && !sseUrl.isBlank()) ? "sse" : "stdio";
                emitLine(GREEN + "  Kompile tools injected (" + mode + ")" + RESET
                        + DIM + " (" + injectedSettingsFile + ")" + RESET);
            }
        } catch (IOException e) {
            System.err.println(YELLOW + "Warning: Could not inject MCP tools: " + e.getMessage() + RESET);
        }
    }

    /**
     * Inject system prompt file into the agent's working directory.
     */
    public void injectSystemPrompt() {
        if (systemPromptManager != null) {
            Path injectedPromptFile = systemPromptManager.injectInstructionFile(
                    agent, Path.of(workingDir).toAbsolutePath());
            if (injectedPromptFile != null) {
                emitLine(GREEN + "  System prompt injected" + RESET
                        + DIM + " (" + injectedPromptFile + ")" + RESET);
            }
        }
    }

    /**
     * Install skills into the agent's native command/skill infrastructure.
     * Claude: .claude/commands/name.md, Codex: ~/.agents/skills/name/SKILL.md,
     * Qwen: .qwen/commands/name.md, OpenCode/Gemini: AGENTS.md fallback.
     * Call once before first message.
     */
    public void injectSkills() {
        try {
            SkillRegistry registry = new SkillRegistry();
            CustomSkillLoader loader = new CustomSkillLoader(Path.of(workingDir).toAbsolutePath());
            for (var custom : loader.loadAll().values()) {
                registry.register(custom);
            }
            skillsInjection = new SkillsInjection(registry, Path.of(workingDir));

            int installed = skillsInjection.installSkills(agent);
            if (installed > 0) {
                emitLine(GREEN + "  Skills installed (" + installed + " skills into " + agent + " native commands)" + RESET);
            }
        } catch (Exception e) {
            System.err.println(YELLOW + "Warning: Could not inject skills: " + e.getMessage() + RESET);
        }
    }

    /**
     * Remove injected MCP tools and skills from working directory.
     */
    public void cleanup() {
        // Kill persistent TUI process if running
        if (tuiProcess != null && tuiProcess.isAlive()) {
            killProcess(tuiProcess);
            tuiProcess = null;
        }
        ai.kompile.cli.main.chat.mcp.McpToolInjection.removeTools(injectedSettingsFile);
        if (skillsInjection != null) {
            skillsInjection.cleanup();
        }
    }

    /**
     * Cancel the currently running subprocess.
     */
    public void cancel() {
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

    /**
     * Check if an agent binary exists on PATH.
     */
    public static String resolveAgentBinary(String name) {
        // Resolve command name from the registry (matches by name or command),
        // then fall back to using the input directly for unknown agents.
        String binary = name.toLowerCase();
        for (ai.kompile.core.agent.AgentProvider agent : ai.kompile.core.agent.CliAgentRegistry.loadAll()) {
            if (agent.getName().equalsIgnoreCase(name)
                    || agent.getCommand().equalsIgnoreCase(name)
                    || agent.getDisplayName().equalsIgnoreCase(name)) {
                binary = agent.getCommand();
                break;
            }
        }

        String path = System.getenv("PATH");
        if (path != null) {
            for (String dir : path.split(File.pathSeparator)) {
                File candidate = new File(dir, binary);
                if (candidate.canExecute()) return candidate.getAbsolutePath();
                for (String ext : new String[]{".exe", ".cmd", ".bat"}) {
                    File candidateExt = new File(dir, binary + ext);
                    if (candidateExt.canExecute()) return candidateExt.getAbsolutePath();
                }
            }
        }
        return null;
    }

    // ========================================================================
    // Core message execution
    // ========================================================================

    /**
     * Send a message to the agent subprocess and return the full response text.
     * Blocks until the subprocess exits (or response stall for TUI agents).
     */
    public String runMessage(String message, ChatHistory history, ChatSessionMetrics metrics) {
        // opencode now uses structured JSON output (opencode run --format json)
        // and is handled by the standard subprocess pipeline below.
        String agentBinary = resolveAgentBinary(agent);
        if (agentBinary == null) {
            emitLine(renderer.red("  Agent '" + agent + "' not found on PATH."));
            emitLine(renderer.dim("  Supported agents: " + String.join(", ",
                    ai.kompile.cli.main.chat.config.ChatConfig.getPassthroughAgentOrder())));
            return "";
        }

        cancelSignal.set(false);
        monitorSoftInterrupt = false;
        monitorInterruptReason = null;
        currentToolName = null;
        updateActivity(getAgentDisplayName() + ": starting...");
        history.logUserMessage(message);
        metrics.recordUserTurn(message);

        List<String> agentCmd = buildCommand(agentBinary, message);

        renderer.setTerminalTitle("Kompiling... (" + agent + ")");
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
            // Trust workspace for agents that require it (gemini)
            env.put("GEMINI_CLI_TRUST_WORKSPACE", "true");

            if (systemPromptManager != null) {
                env.putAll(systemPromptManager.getExtraEnv(agent));
            }
            env.putAll(extraEnvironment);

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
                                        metrics, history, spinner, spinnerStopped, pendingText);
                                lineBuffer.setLength(0);
                            } else if (c == '\r') {
                                if (lineBuffer.length() > 0) {
                                    processOutputLine(lineBuffer.toString(), fullText, toolCalls,
                                            metrics, history, spinner, spinnerStopped, pendingText);
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
                                metrics, history, spinner, spinnerStopped, pendingText);
                    }
                } catch (IOException e) {
                    // Stream closed — expected
                }
            }, "emulated-output-reader");
            outputReader.setDaemon(true);
            outputReader.start();

            waitingThread = Thread.currentThread();

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
                emitLine(renderer.red("\n  Error running agent: " + e.getMessage()));
            }
        } finally {
            spinner.stop();
            activeProcess = null;
        }

        long turnDuration = System.currentTimeMillis() - turnStart;

        if (cancelSignal.get() || monitorSoftInterrupt) {
            emitLine("");
            if (monitorInterruptReason != null && !monitorInterruptReason.isBlank()) {
                emitLine(renderer.yellow("  Interrupted by enforcer: " + monitorInterruptReason));
                history.logSystem("Enforcer interrupted agent response after " + turnDuration
                        + "ms: " + monitorInterruptReason);
            } else if (cancelSignal.get()) {
                emitLine(renderer.yellow("  Cancelled."));
                history.logSystem("User cancelled agent response after " + turnDuration + "ms");
            }
        }

        if (fullText.length() > 0) {
            history.logAgentResponse(agent, fullText.toString(), turnDuration);
            metrics.recordAssistantTurn(fullText.toString(), turnDuration);
        }

        if (!toolCalls.isEmpty()) {
            emitLine(renderer.dim("  " + toolCalls.size() + " tool call(s): "
                    + String.join(", ", toolCalls)));
        }

        firstMessageSent = true;
        currentToolName = null;
        updateActivity(null); // clear activity — agent is idle
        emitLine("");
        renderer.setTerminalTitle("kompile [" + agent + "]");
        return fullText.toString();
    }

    /**
     * Persistent TUI agent path (OpenCode). The TUI process is launched once
     * and kept alive across messages. Each message writes to stdin and reads
     * the streamed response from stdout via the PTY.
     */
    private String runTuiMessage(String message, ChatHistory history, ChatSessionMetrics metrics) {
        String agentBinary = resolveAgentBinary(agent);
        if (agentBinary == null) {
            emitLine(renderer.red("  Agent '" + agent + "' not found on PATH."));
            emitLine(renderer.dim("  Supported agents: " + String.join(", ",
                    ai.kompile.cli.main.chat.config.ChatConfig.getPassthroughAgentOrder())));
            return "";
        }

        cancelSignal.set(false);
        monitorSoftInterrupt = false;
        monitorInterruptReason = null;
        currentToolName = null;
        updateActivity(getAgentDisplayName() + ": starting...");
        history.logUserMessage(message);
        metrics.recordUserTurn(message);

        renderer.setTerminalTitle("Kompiling... (" + agent + ")");
        TerminalRenderer.SpinnerHandle spinner = renderer.startGeneratingSpinner(agent);

        StringBuilder fullText = new StringBuilder();
        StringBuilder pendingText = new StringBuilder();
        List<String> toolCalls = new ArrayList<>();
        long turnStart = System.currentTimeMillis();
        AtomicBoolean spinnerStopped = new AtomicBoolean(false);

        // Discard TUI initialization output — only capture post-message response.
        tuiFullText = null;
        tuiToolCalls = null;
        tuiMetrics = metrics;
        tuiHistory = history;
        tuiSpinner = spinner;
        tuiSpinnerStopped = spinnerStopped;
        tuiPendingText = null;

        try {
            // Launch persistent TUI process on first message
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
                env.put("GEMINI_CLI_TRUST_WORKSPACE", "true");
                if (systemPromptManager != null) {
                    env.putAll(systemPromptManager.getExtraEnv(agent));
                }
                env.putAll(extraEnvironment);

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
                        // Stream closed — expected
                    }
                }, "tui-output-reader");
                tuiOutputReader.setDaemon(true);
                tuiOutputReader.start();

                // Wait for TUI to initialize
                Thread.sleep(2000);
            }

            // Now wire up buffers so response goes into this message's state
            tuiFullText = fullText;
            tuiToolCalls = toolCalls;
            tuiPendingText = pendingText;

            // Send message via stdin
            agentStdin.write((message + "\n").getBytes(StandardCharsets.UTF_8));
            agentStdin.flush();
            long messageSentAt = System.currentTimeMillis();
            lastOutputTime.set(messageSentAt);

            // Wait for response to complete (stall detection).
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
                emitLine(renderer.red("\n  Error running agent: " + e.getMessage()));
            }
        } finally {
            spinner.stop();
        }

        long turnDuration = System.currentTimeMillis() - turnStart;

        if (cancelSignal.get() || monitorSoftInterrupt) {
            emitLine("");
            if (monitorInterruptReason != null && !monitorInterruptReason.isBlank()) {
                emitLine(renderer.yellow("  Interrupted by enforcer: " + monitorInterruptReason));
            } else if (cancelSignal.get()) {
                emitLine(renderer.yellow("  Cancelled."));
            }
        }

        if (fullText.length() > 0) {
            history.logAgentResponse(agent, fullText.toString(), turnDuration);
            metrics.recordAssistantTurn(fullText.toString(), turnDuration);
        }

        if (!toolCalls.isEmpty()) {
            emitLine(renderer.dim("  " + toolCalls.size() + " tool call(s): "
                    + String.join(", ", toolCalls)));
        }

        firstMessageSent = true;
        currentToolName = null;
        updateActivity(null);
        emitLine("");
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
            emitLine("");
        }
        emitLine("  " + stripped);
        ft.append(stripped).append("\n");
    }

    // ========================================================================
    // Output parsing
    // ========================================================================

    private synchronized void processOutputLine(String line, StringBuilder fullText,
                                    List<String> toolCalls, ChatSessionMetrics metrics,
                                    ChatHistory history,
                                    TerminalRenderer.SpinnerHandle spinner,
                                    AtomicBoolean spinnerStopped,
                                    StringBuilder pendingText) {
        lastOutputTime.set(System.currentTimeMillis());
        String agentLower = agent.toLowerCase();

        List<PassthroughStreamParser.PassthroughEvent> events = parseAgentLineMulti(agentLower, line);

        if (events.isEmpty()) {
            if (!isStructuredAgent(agentLower)) {
                String cleaned = stripAnsi(line).trim();
                if (!cleaned.isEmpty()) {
                    if (spinnerStopped.compareAndSet(false, true)) {
                        spinner.stop();
                        emitLine("");
                    }
                    emitLine("  " + cleaned);
                    fullText.append(cleaned).append("\n");
                }
            }
            return;
        }

        for (PassthroughStreamParser.PassthroughEvent event : events) {
            processEvent(event, fullText, toolCalls, metrics, history, spinner, spinnerStopped, pendingText);
        }
    }

    private void processEvent(PassthroughStreamParser.PassthroughEvent event,
                              StringBuilder fullText, List<String> toolCalls,
                              ChatSessionMetrics metrics, ChatHistory history,
                              TerminalRenderer.SpinnerHandle spinner,
                              AtomicBoolean spinnerStopped,
                              StringBuilder pendingText) {
        if (event instanceof PassthroughStreamParser.SessionInit si) {
            if (si.sessionId() != null) {
                agentSessionId = si.sessionId();
            }
            return;
        }

        if (event instanceof PassthroughStreamParser.InteractiveQuestion
                || event instanceof PassthroughStreamParser.InteractiveApproval) {
            flushPendingText(pendingText, spinner, spinnerStopped);
            interactiveQueue.offer(event);
            return;
        }

        if (event instanceof PassthroughStreamParser.ThinkingChunk) {
            // Model is reasoning — switch spinner to "Thinking..." phase
            // but don't add reasoning content to response text.
            spinner.setPhase("Thinking");
            updateActivity(getAgentDisplayName() + ": thinking...");
            renderer.setTerminalTitle("Thinking... (" + agent + ")");
            return;
        }

        if (event instanceof PassthroughStreamParser.TextChunk tc) {
            pendingText.append(tc.text());
            fullText.append(tc.text());
            MonitorDecision decision = evaluateRealtimeText(tc.text(), fullText.toString());
            if (decision.interrupt()) {
                interruptForMonitor(decision);
                return;
            }
            // Flush immediately so output streams in real time
            flushPendingText(pendingText, spinner, spinnerStopped);
            if (currentToolName == null) {
                updateActivity(getAgentDisplayName() + ": generating...");
            }
        } else if (event instanceof PassthroughStreamParser.ToolUse tu) {
            flushPendingText(pendingText, spinner, spinnerStopped);
            MonitorDecision decision = evaluateRealtimeToolUse(tu.name(), tu.input());
            if (decision.interrupt()) {
                interruptForMonitor(decision);
                return;
            }
            if (spinnerStopped.compareAndSet(false, true)) {
                spinner.stop();
                emitLine("");
            }
            emitLine(renderer.renderToolCallStart(tu.name(), tu.input()));
            toolCalls.add(tu.name());
            metrics.recordToolCall(tu.name(), false, 0);
            history.logToolCall(tu.name(), false, 0);
            ToolCallIndex.getInstance().record(
                    metrics.getSessionId(), tu.name(), tu.input(),
                    agent, "emulated-passthrough", false, 0,
                    new File(workingDir).getAbsolutePath());
            currentToolName = tu.name();
            String toolActivity = formatToolActivity(tu.name(), tu.input());
            updateActivity(getAgentDisplayName() + ": " + toolActivity);
        } else if (event instanceof PassthroughStreamParser.ToolOutput toolOutput) {
            flushPendingText(pendingText, spinner, spinnerStopped);
            if (spinnerStopped.compareAndSet(false, true)) {
                spinner.stop();
                emitLine("");
            }
            printExternalToolOutput(toolOutput.output());
        } else if (event instanceof PassthroughStreamParser.ToolComplete toolComplete) {
            flushPendingText(pendingText, spinner, spinnerStopped);
            if (spinnerStopped.compareAndSet(false, true)) {
                spinner.stop();
                emitLine("");
            }
            printExternalToolOutput(toolComplete.output());
            String status = toolComplete.exitCode() >= 0
                    ? "exit " + toolComplete.exitCode()
                    : "completed";
            emitLine(renderer.dim("  [" + toolComplete.name() + " " + status + "]"));
            currentToolName = null;
            updateActivity(getAgentDisplayName() + ": tool complete");
        } else if (event instanceof PassthroughStreamParser.TokenUsage tu) {
            if (metrics != null) {
                metrics.recordTokenUsage(tu.inputTokens(), tu.outputTokens(),
                        tu.cacheReadTokens(), tu.cacheCreationTokens());
            }
            emitLine("");
            emitLine(renderer.dim("  [tokens: " + FormatUtils.formatNumber(tu.inputTokens())
                    + " in / " + FormatUtils.formatNumber(tu.outputTokens()) + " out"
                    + (tu.cacheReadTokens() > 0 ? " \u00b7 " + FormatUtils.formatNumber(tu.cacheReadTokens()) + " cached" : "")
                    + "]"));
        } else if (event instanceof PassthroughStreamParser.TurnComplete tc) {
            flushPendingText(pendingText, spinner, spinnerStopped);

            if (tc.inputTokens() > 0 || tc.outputTokens() > 0) {
                if (metrics != null) {
                    metrics.recordTokenUsage(tc.inputTokens(), tc.outputTokens(),
                            tc.cacheReadTokens(), tc.cacheCreationTokens());
                }
            }

            StringBuilder stats = new StringBuilder();
            if (tc.durationMs() > 0) stats.append(FormatUtils.formatDuration(tc.durationMs()));
            if (tc.costUsd() > 0) {
                if (stats.length() > 0) stats.append(" \u00b7 ");
                stats.append(String.format("$%.4f", tc.costUsd()));
            }
            if (tc.inputTokens() > 0 || tc.outputTokens() > 0) {
                if (stats.length() > 0) stats.append(" \u00b7 ");
                stats.append(FormatUtils.formatNumber(tc.inputTokens())).append(" in / ")
                     .append(FormatUtils.formatNumber(tc.outputTokens())).append(" out");
                if (tc.cacheReadTokens() > 0) {
                    stats.append(" \u00b7 ").append(FormatUtils.formatNumber(tc.cacheReadTokens())).append(" cached");
                }
            }
            if (tc.numTurns() > 0) {
                if (stats.length() > 0) stats.append(" \u00b7 ");
                stats.append(tc.numTurns()).append(" turn(s)");
            }
            if (stats.length() > 0) {
                emitLine("");
                emitLine(renderer.dim("  [" + stats + "]"));
            }
            currentToolName = null;
            updateActivity(getAgentDisplayName() + ": turn complete");
        }
    }

    private void printExternalToolOutput(String output) {
        if (output == null || output.isEmpty()) {
            return;
        }
        for (String line : output.split("\n", -1)) {
            if (!line.isEmpty()) {
                emitLine(renderer.dim("    " + line));
            }
        }
    }

    /**
     * Format a tool activity string for display. Provider-aware: extracts the most
     * useful summary from tool input based on the tool name.
     */
    private String formatToolActivity(String toolName, String input) {
        String summary = toolName;
        if (input != null && !input.isEmpty()) {
            // Extract a short preview from the tool input
            String preview = input.replaceAll("\\s+", " ").trim();
            if (preview.length() > 50) preview = preview.substring(0, 47) + "...";
            summary = toolName + ": " + preview;
        }
        return summary;
    }

    /**
     * Detect system prompt / tool listing noise that should not be rendered
     * as response text.
     */
    public static boolean isSystemPromptNoise(String text) {
        if (text == null || text.length() < 20) return false;
        if (text.contains("subordinate LLM in an enforcer-controlled chat")) return true;
        if (text.contains("Enforcer-Controlled Task")) return true;
        if (text.contains("Enforcer Rules") && text.contains("STOP_CMD:")) return true;
        if (text.contains("Enforcer Rules") && text.contains("BAN_CMD:")) return true;
        if (text.contains("Enforcer Rules") && text.contains("BAN_DIFF")) return true;
        if (text.contains("Produce the response now. Do not mention the enforcer")) return true;
        if (text.contains("enforcer rule wins for this task")) return true;
        if (text.contains("Enforcer Correction") && text.contains("blocked by the enforcer")) return true;
        if (text.matches("^\\s*(STOP_CMD|BAN_CMD|BAN_DIFF|BAN_DIFF_REGEX|BAN_TOOL|BAN|STOP):.*")) return true;
        if (countOccurrences(text, "mcp__") >= 2) return true;
        if (text.contains("mcp__") && text.contains("Tool Description")) return true;
        if (countOccurrences(text, "Tool Description") >= 2) return true;
        if (text.contains("available MCP tools") || text.contains("MCP tools from")) return true;
        if (text.contains("kompile server") && text.contains("Tool")) return true;
        // Category headers from MCP tool listing (garbled TUI output)
        String[] categoryHeaders = {"File I/O", "Agent Orchestration", "Knowledge", "Execution", "DevOps"};
        int categoryCount = 0;
        for (String header : categoryHeaders) {
            if (text.contains(header)) categoryCount++;
        }
        if (categoryCount >= 3) return true;
        int toolPatterns = 0;
        if (text.contains("Spawn a single subagent")) toolPatterns++;
        if (text.contains("Spawn multiple parallel subagent")) toolPatterns++;
        if (text.contains("Search conversation transcripts")) toolPatterns++;
        if (text.contains("Activate/deactivate tools")) toolPatterns++;
        if (text.contains("Fetch web page content")) toolPatterns++;
        if (text.contains("Search the web")) toolPatterns++;
        if (text.contains("Lock files for multi-agent editing")) toolPatterns++;
        if (toolPatterns >= 2) return true;
        return false;
    }

    private static int countOccurrences(String text, String pattern) {
        int count = 0, idx = 0;
        while ((idx = text.indexOf(pattern, idx)) >= 0) {
            count++;
            idx += pattern.length();
        }
        return count;
    }

    private void flushPendingText(StringBuilder pendingText,
                                   TerminalRenderer.SpinnerHandle spinner,
                                   AtomicBoolean spinnerStopped) {
        if (pendingText.length() == 0) return;

        if (spinnerStopped.compareAndSet(false, true)) {
            spinner.stop();
            emitLine("");
        }

        String rendered = ascii.renderMarkdown(pendingText.toString());
        for (String rl : rendered.split("\n", -1)) {
            emitLine("  " + rl);
        }
        pendingText.setLength(0);
    }

    private MonitorDecision evaluateRealtimeText(String chunk, String fullText) {
        if (monitorSoftInterrupt) return MonitorDecision.continueRun();
        RealtimeMonitor monitor = this.realtimeMonitor;
        if (monitor == null) {
            return MonitorDecision.continueRun();
        }
        try {
            MonitorDecision decision = monitor.onTextChunk(chunk, fullText);
            return decision != null ? decision : MonitorDecision.continueRun();
        } catch (RuntimeException e) {
            return MonitorDecision.continueRun();
        }
    }

    private MonitorDecision evaluateRealtimeToolUse(String toolName, String input) {
        if (monitorSoftInterrupt) return MonitorDecision.continueRun();
        RealtimeMonitor monitor = this.realtimeMonitor;
        if (monitor == null) {
            return MonitorDecision.continueRun();
        }
        try {
            MonitorDecision decision = monitor.onToolUse(toolName, input);
            return decision != null ? decision : MonitorDecision.continueRun();
        } catch (RuntimeException e) {
            return MonitorDecision.continueRun();
        }
    }

    private void interruptForMonitor(MonitorDecision decision) {
        String reason = decision.reason();
        if (reason == null || reason.isBlank()) {
            reason = "rule violation";
        }
        monitorInterruptReason = reason;
        monitorSoftInterrupt = true;
        currentToolName = null;
        updateActivity(getAgentDisplayName() + ": interrupted by enforcer");
        Process p = activeProcess;
        if (p != null && p.isAlive()) {
            // Send SIGINT (escape) only — let the agent wind down gracefully
            sendSigint(p);
        }
    }

    /**
     * Send SIGINT to a process without escalating to SIGTERM/SIGKILL.
     * This tells the agent to stop generating and exit gracefully.
     */
    private void sendSigint(Process process) {
        if (process == null || !process.isAlive()) return;
        try {
            long pid = process.pid();
            boolean isUnix = !System.getProperty("os.name", "").toLowerCase().startsWith("win");
            if (isUnix) {
                new ProcessBuilder("kill", "-INT", String.valueOf(pid))
                        .redirectErrorStream(true).start().waitFor();
            } else {
                // Windows doesn't have SIGINT for non-console processes — use Ctrl+C via stdin
                OutputStream stdin = agentStdin;
                if (stdin != null) {
                    try {
                        stdin.write(3); // ETX (Ctrl+C)
                        stdin.flush();
                    } catch (IOException ignored) {}
                }
            }
        } catch (Exception ignored) {}
    }

    // ========================================================================
    // Interactive input handling
    // ========================================================================

    private void handleInteractiveEvent(PassthroughStreamParser.PassthroughEvent event,
                                         TerminalRenderer.SpinnerHandle spinner,
                                         AtomicBoolean spinnerStopped) {
        if (spinnerStopped.compareAndSet(false, true)) {
            spinner.stop();
            emitLine("");
        }

        if (event instanceof PassthroughStreamParser.InteractiveQuestion iq) {
            handleInteractiveQuestion(iq);
        } else if (event instanceof PassthroughStreamParser.InteractiveApproval ia) {
            handleInteractiveApproval(ia);
        }
    }

    private void handleInteractiveQuestion(PassthroughStreamParser.InteractiveQuestion iq) {
        emitLine("");
        List<PassthroughStreamParser.QuestionOption> options = iq.options();

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
                    body.append(DIM).append(" \u2014 ").append(opt.description()).append(RESET);
                }
            }
            if (iq.freeformAllowed()) {
                body.append("\n\n").append(DIM).append("  Or type a custom answer").append(RESET);
            }
        }

        emitLine(ascii.panel("Agent Question", body.toString()));

        String response = readInput(options.isEmpty()
                ? "  answer> "
                : "  choice [1-" + options.size() + "]> ");

        if (response == null || response.isBlank()) return;

        String answer = response.trim();
        try {
            int idx = Integer.parseInt(answer);
            if (idx >= 1 && idx <= options.size()) {
                answer = options.get(idx - 1).label();
            }
        } catch (NumberFormatException ignored) {}

        writeToAgentStdin(answer, iq.callId(), iq.turnId(), iq.questionId());
        emitLine(renderer.dim("  \u2192 Sent: " + answer));
        emitLine("");
    }

    private void handleInteractiveApproval(PassthroughStreamParser.InteractiveApproval ia) {
        emitLine("");

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

        emitLine(ascii.panel("Approval Required", body.toString()));

        String response = readInput("  decision [1-" + decisions.size() + "]> ");
        if (response == null || response.isBlank()) return;

        String decision = response.trim();
        try {
            int idx = Integer.parseInt(decision);
            if (idx >= 1 && idx <= decisions.size()) {
                decision = decisions.get(idx - 1);
            }
        } catch (NumberFormatException ignored) {}

        writeApprovalToAgentStdin(decision, ia.callId(), ia.turnId());
        String color = decision.equalsIgnoreCase("approve") ? GREEN : YELLOW;
        emitLine(color + "  \u2192 " + decision + RESET);
        emitLine("");
    }

    /**
     * Read user input via the configured input provider.
     */
    private String readInput(String prompt) {
        if (inputProvider != null) {
            return inputProvider.apply(prompt);
        }
        return null;
    }

    // ========================================================================
    // Agent stdin writing
    // ========================================================================

    private void writeToAgentStdin(String answer, String callId, String turnId, String questionId) {
        String agentLower = agent.toLowerCase();

        if (agentLower.contains("codex") && callId != null && !callId.isEmpty()) {
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
            writePlainToAgentStdin(answer);
        }
    }

    private void writeApprovalToAgentStdin(String decision, String callId, String turnId) {
        String agentLower = agent.toLowerCase();

        if (agentLower.contains("codex") && callId != null && !callId.isEmpty()) {
            String json = String.format(
                    "{\"op\":{\"ExecApproval\":{\"id\":\"%s\",\"turn_id\":\"%s\",\"decision\":\"%s\"}}}",
                    escapeJson(callId), escapeJson(turnId != null ? turnId : ""),
                    escapeJson(decision.toLowerCase()));
            writePlainToAgentStdin(json);
        } else {
            writePlainToAgentStdin(decision.toLowerCase());
        }
    }

    private void writePlainToAgentStdin(String text) {
        OutputStream os = agentStdin;
        if (os == null) return;
        try {
            os.write((text + "\n").getBytes(StandardCharsets.UTF_8));
            os.flush();
        } catch (IOException e) {
            // Agent may have closed stdin
        }
    }

    /**
     * Write a line to the running agent subprocess's stdin.
     * Used to forward user text responses to the agent process.
     *
     * @param text the text to send (newline is appended automatically)
     * @return true if the text was sent, false if no agent process is active
     */
    public boolean sendToStdin(String text) {
        OutputStream os = agentStdin;
        if (os == null) return false;
        try {
            os.write((text + "\n").getBytes(StandardCharsets.UTF_8));
            os.flush();
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Write raw bytes to the running agent subprocess's stdin.
     * Used to forward terminal escape sequences (e.g. Shift+Tab = ESC [ Z)
     * so the agent's own key handlers fire.
     *
     * @param data the raw bytes to send (no newline appended)
     * @return true if the data was sent, false if no agent process is active
     */
    public boolean sendRawToStdin(byte[] data) {
        OutputStream os = agentStdin;
        if (os == null) return false;
        try {
            os.write(data);
            os.flush();
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Check if an agent subprocess is currently running with an open stdin.
     */
    public boolean hasActiveProcess() {
        return agentStdin != null && activeProcess != null && activeProcess.isAlive();
    }

    // ========================================================================
    // Command building
    // ========================================================================

    private List<String> buildCommand(String binary, String message) {
        List<String> cmd = new ArrayList<>();
        cmd.add(binary);

        String name = agent.toLowerCase();

        if (name.contains("claude")) {
            cmd.add("-p");
            cmd.add(message);
            cmd.add("--output-format");
            cmd.add("stream-json");
            cmd.add("--verbose");
            AgentFlagOverrides.addPermissionBypassFlags(cmd, agent, skipPermissions, Path.of(workingDir));
            if (firstMessageSent) {
                if (agentSessionId != null) {
                    cmd.add("--resume");
                    cmd.add(agentSessionId);
                } else {
                    cmd.add("--continue");
                }
            }
        } else if (name.contains("codex")) {
            if (firstMessageSent) {
                cmd.add("exec");
                cmd.add("resume");
                if (agentSessionId != null) {
                    cmd.add(agentSessionId);
                } else {
                    cmd.add("--last");
                }
                cmd.add("--json");
                AgentFlagOverrides.addPermissionBypassFlags(cmd, agent, skipPermissions, Path.of(workingDir));
                cmd.add(message);
            } else {
                cmd.add("exec");
                cmd.add("--json");
                AgentFlagOverrides.addPermissionBypassFlags(cmd, agent, skipPermissions, Path.of(workingDir));
                cmd.add(message);
            }
        } else if (name.contains("gemini")) {
            cmd.add("-p");
            cmd.add(message);
            cmd.add("-o");
            cmd.add("stream-json");
            AgentFlagOverrides.addPermissionBypassFlags(cmd, agent, skipPermissions, Path.of(workingDir));
            if (firstMessageSent) {
                cmd.add("--resume");
                cmd.add("latest");
            }
        } else if (name.contains("qwen")) {
            cmd.add("-o");
            cmd.add("stream-json");
            AgentFlagOverrides.addPermissionBypassFlags(cmd, agent, skipPermissions, Path.of(workingDir));
            if (firstMessageSent) {
                cmd.add("--continue");
            }
            cmd.add(message);
        } else if (name.contains("opencode")) {
            // Structured JSON output mode: "opencode run --format json [--session <id>] <message>"
            // This produces machine-readable JSON events identical to Claude/Gemini stream-json.
            cmd.add("run");
            cmd.add("--format");
            cmd.add("json");
            AgentFlagOverrides.addPermissionBypassFlags(cmd, agent, skipPermissions, Path.of(workingDir));
            if (firstMessageSent && agentSessionId != null) {
                cmd.add("--session");
                cmd.add(agentSessionId);
            }
            cmd.add(message);
        } else if (name.contains("pi")) {
            cmd.add("--mode");
            cmd.add("json");
            cmd.add("-p");
            cmd.add(message);
            AgentFlagOverrides.addPermissionBypassFlags(cmd, agent, skipPermissions, Path.of(workingDir));
            if (firstMessageSent) {
                cmd.add("--continue");
            }
        } else {
            cmd.add("-p");
            cmd.add(message);
        }

        if (systemPromptManager != null) {
            cmd.addAll(systemPromptManager.getExtraArgs(agent));
        }

        // Skills are installed into native command infrastructure (not CLI flags)
        // via installSkills() called from injectSkills() — no extra args needed here.

        return cmd;
    }

    // ========================================================================
    // Follow-up question detection
    // ========================================================================

    /**
     * Check if the agent's response ends with a question.
     * Returns the user's follow-up input, or null.
     */
    public String promptIfQuestion(String agentText) {
        if (agentText == null || agentText.isBlank()) return null;

        String[] lines = agentText.strip().split("\n");
        String lastLine = "";
        for (int i = lines.length - 1; i >= 0; i--) {
            if (!lines[i].isBlank()) {
                lastLine = lines[i].strip();
                break;
            }
        }

        if (!lastLine.contains("?")) return null;

        List<String> options = new ArrayList<>();
        for (String line : lines) {
            String trimmed = line.strip();
            if (trimmed.matches("^\\d+\\.\\s+.+")) {
                options.add(trimmed.replaceFirst("^\\d+\\.\\s+", ""));
            }
        }

        emitLine("");
        if (!options.isEmpty()) {
            String response = readInput("  choice [1-" + options.size() + "]> ");
            if (response == null || response.isBlank()) return null;
            try {
                int idx = Integer.parseInt(response.trim());
                if (idx >= 1 && idx <= options.size()) {
                    return options.get(idx - 1);
                }
            } catch (NumberFormatException ignored) {}
            return response.trim();
        } else {
            String response = readInput("  answer> ");
            if (response == null || response.isBlank()) return null;
            return response.trim();
        }
    }

    // ========================================================================
    // PTY wrapper
    // ========================================================================

    private static List<String> wrapWithPty(List<String> cmd) {
        boolean isWindows = System.getProperty("os.name", "").toLowerCase().startsWith("win");
        if (isWindows) return cmd;

        try {
            Process check = new ProcessBuilder("which", "script")
                    .redirectErrorStream(true).start();
            int rc = check.waitFor();
            if (rc != 0) return cmd;
        } catch (Exception e) {
            return cmd;
        }

        boolean isMac = System.getProperty("os.name", "").toLowerCase().contains("mac");
        List<String> wrapped = new ArrayList<>();
        wrapped.add("script");
        wrapped.add("-q");
        if (isMac) {
            wrapped.add("/dev/null");
            wrapped.addAll(cmd);
        } else {
            wrapped.add("/dev/null");
            wrapped.add("-c");
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
            wrapped.add(cmdStr.toString());
        }
        return wrapped;
    }

    // ========================================================================
    // Helpers
    // ========================================================================

    private static boolean isStructuredAgent(String agentLower) {
        return agentLower.contains("claude")
                || agentLower.contains("gemini") || agentLower.contains("qwen")
                || agentLower.contains("codex") || agentLower.contains("pi")
                || agentLower.contains("opencode");
    }

    private List<PassthroughStreamParser.PassthroughEvent> parseAgentLineMulti(String agentLower, String line) {
        if (agentLower.contains("claude")) {
            return parser.parseClaudeLineMulti(line);
        } else if (agentLower.contains("gemini") || agentLower.contains("qwen")) {
            PassthroughStreamParser.PassthroughEvent e = parser.parseGeminiLine(line);
            return e != null ? List.of(e) : List.of();
        } else if (agentLower.contains("codex")) {
            PassthroughStreamParser.PassthroughEvent e = parser.parseCodexLine(line);
            return e != null ? List.of(e) : List.of();
        } else if (agentLower.contains("pi")) {
            return parser.parsePiLineMulti(line);
        } else if (agentLower.contains("opencode")) {
            return parser.parseOpenCodeLineMulti(line);
        }
        return List.of();
    }

    private void killProcess(Process process) {
        if (process == null || !process.isAlive()) return;
        try {
            long pid = process.pid();
            boolean isUnix = !System.getProperty("os.name", "").toLowerCase().startsWith("win");
            if (isUnix) {
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

    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }

    static String stripAnsi(String s) {
        return s.replaceAll(ANSI_REGEX, "");
    }

}
