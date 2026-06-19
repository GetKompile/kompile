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

package ai.kompile.cli.main.chat.config;

import ai.kompile.cli.main.chat.PassthroughStreamParser;
import ai.kompile.utils.FormatUtils;
import ai.kompile.cli.main.chat.PassthroughStreamParser.PassthroughEvent;
import ai.kompile.cli.main.chat.PassthroughStreamParser.TextChunk;
import ai.kompile.cli.main.chat.PassthroughStreamParser.TokenUsage;
import ai.kompile.cli.main.chat.PassthroughStreamParser.ToolComplete;
import ai.kompile.cli.main.chat.PassthroughStreamParser.ToolOutput;
import ai.kompile.cli.main.chat.PassthroughStreamParser.ToolUse;
import ai.kompile.cli.main.chat.PassthroughStreamParser.TurnComplete;
import ai.kompile.cli.main.chat.agent.SubprocessAgentRunner;
import ai.kompile.cli.main.chat.enforcer.RealtimeInterruptEvent;
import ai.kompile.cli.main.chat.enforcer.ScoringRealtimeMonitor;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * LLM client that delegates to an external CLI agent (claude, codex, gemini, etc.)
 * as the backend for kompile's chat REPL.
 * <p>
 * Each {@link #streamChat} call launches the agent as a subprocess with the user
 * message piped to stdin and structured output (stream-json) on stdout. The output
 * is parsed via {@link PassthroughStreamParser} and streamed to System.out as it
 * arrives — same behavior as {@link DirectLlmClient}.
 * <p>
 * Conversation continuity is maintained via {@code --continue} / session flags
 * after the first message. This avoids the need for a persistent PTY-wrapped
 * subprocess (which produces TUI noise) while keeping the conversation state
 * across turns.
 * <p>
 * Extends {@link DirectLlmClient} so it plugs directly into {@code AgenticChatLoop}.
 */
public class AgentSubprocessClient extends DirectLlmClient implements AutoCloseable {

    private final String agentName;
    private final String agentBinary;
    private final File workingDir;
    private final PassthroughStreamParser parser = new PassthroughStreamParser();

    // Track conversation state for multi-turn
    private volatile boolean firstMessageSent = false;
    private volatile String sessionId = null;

    // Active subprocess for cancellation
    private volatile Process activeProcess;

    // Real-time monitor for enforcement, scoring, and auto-reprompt
    private volatile ScoringRealtimeMonitor realtimeMonitor;

    // Extra CLI args to pass to the agent on every invocation (e.g. system prompt flags)
    private volatile List<String> extraArgs = List.of();

    // Extra environment variables to set on the agent subprocess (e.g. GEMINI_SYSTEM_MD)
    private volatile Map<String, String> extraEnv = Map.of();

    public AgentSubprocessClient(String agentName, String workingDir, ObjectMapper objectMapper) {
        super(new ChatConfig(agentName, null, agentName, null), objectMapper);
        this.agentName = agentName;
        this.agentBinary = resolveAgentBinary(agentName);
        this.workingDir = new File(workingDir).getAbsoluteFile();
    }

    /**
     * Constructor for tests — accepts an explicit binary path.
     */
    protected AgentSubprocessClient(String agentName, String workingDir,
                                     ObjectMapper objectMapper, String binaryPath) {
        super(new ChatConfig(agentName, null, agentName, null), objectMapper);
        this.agentName = agentName;
        this.agentBinary = binaryPath;
        this.workingDir = new File(workingDir).getAbsoluteFile();
    }

    public String getAgentName() {
        return agentName;
    }

    public boolean isAvailable() {
        return agentBinary != null;
    }

    /**
     * Validate agent exists. Called before first streamChat.
     */
    public void start() throws IOException {
        if (agentBinary == null) {
            throw new IOException("Agent '" + agentName + "' not found on PATH");
        }
        System.out.println("Agent '" + agentName + "' ready at: " + agentBinary);
        System.out.flush();
    }

    /**
     * Install a real-time scoring monitor that evaluates agent output as it
     * streams in and can interrupt + auto-reprompt on rule violations.
     */
    public void setRealtimeMonitor(ScoringRealtimeMonitor monitor) {
        this.realtimeMonitor = monitor;
    }

    /**
     * Returns the installed real-time monitor, or null if none.
     */
    public ScoringRealtimeMonitor getRealtimeMonitor() {
        return realtimeMonitor;
    }

    /**
     * Set extra CLI arguments to pass to the agent on every subprocess invocation.
     * Used for system prompt injection (e.g. --append-system-prompt-file).
     */
    public void setExtraArgs(List<String> extraArgs) {
        this.extraArgs = extraArgs != null ? extraArgs : List.of();
    }

    /**
     * Set extra environment variables for the agent subprocess.
     * Used for system prompt injection (e.g. GEMINI_SYSTEM_MD).
     */
    public void setExtraEnv(Map<String, String> extraEnv) {
        this.extraEnv = extraEnv != null ? extraEnv : Map.of();
    }

    /**
     * Start with a specific command — for tests that provide a mock agent.
     */
    protected void startWithCommand(List<String> command) throws IOException {
        // For test subclasses that override the subprocess directly
    }

    @Override
    public StreamResult streamChat(String userMessage, String systemPrompt,
                                    ArrayNode toolDefs, List<ToolCallResultInput> toolResults,
                                    String modelOverride) {
        return streamChat(userMessage, systemPrompt, toolDefs, toolResults, modelOverride, null);
    }

    @Override
    public StreamResult streamChat(String userMessage, String systemPrompt,
                                    ArrayNode toolDefs, List<ToolCallResultInput> toolResults,
                                    String modelOverride, List<AttachmentInput> attachments) {
        ScoringRealtimeMonitor monitor = this.realtimeMonitor;

        // Reset monitor for this turn
        if (monitor != null) {
            monitor.resetFull(userMessage);
        }

        // Auto-reprompt loop: if the monitor interrupts with a correction prompt,
        // kill the subprocess and send the correction as the next message.
        int maxAttempts = monitor != null ? monitor.getMaxAutoReprompts() + 1 : 1;
        String currentMessage = userMessage;
        StreamResult result = new StreamResult();

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            result = runSingleAttempt(currentMessage, monitor);

            // Check if this was an enforcer interrupt with a correction prompt
            if (result.monitorInterrupted && result.correctionPrompt != null
                    && !result.correctionPrompt.isBlank()
                    && attempt < maxAttempts) {
                // Fire reprompt event
                if (monitor != null) {
                    monitor.resetTurn(userMessage);
                }

                System.out.println("\n  \033[33m[enforcer] violation detected — auto-reprompting "
                        + "(attempt " + (attempt + 1) + "/" + maxAttempts + ")\033[0m");
                System.out.flush();

                // Build correction message and retry
                String correction = monitor != null
                        ? monitor.buildCorrectionMessage(result.correctionPrompt)
                        : result.correctionPrompt;
                currentMessage = correction;
                firstMessageSent = true; // Use --continue for the retry
                continue;
            }

            break;
        }

        // Final turn-level score
        if (monitor != null && result.text != null && !result.text.isBlank()) {
            monitor.scoreTurn(result.text);
        }

        return result;
    }

    /**
     * Run a single subprocess attempt for one message. Returns the result
     * with optional monitor interrupt metadata.
     */
    private StreamResult runSingleAttempt(String message, ScoringRealtimeMonitor monitor) {
        StreamResult result = new StreamResult();

        if (agentBinary == null) {
            result.text = "Agent '" + agentName + "' not found on PATH";
            return result;
        }

        List<String> cmd = buildCommand(message);

        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.directory(workingDir);
            pb.redirectErrorStream(true);
            inheritEnv(pb.environment(), "PATH", "HOME", "USER", "SHELL", "LANG",
                    "LC_ALL", "JAVA_HOME", "MAVEN_HOME", "TERM", "COLORTERM",
                    "ANTHROPIC_API_KEY", "OPENAI_API_KEY", "GOOGLE_API_KEY");
            // Apply extra environment variables (e.g. GEMINI_SYSTEM_MD for system prompt)
            pb.environment().putAll(extraEnv);

            Process process = pb.start();
            activeProcess = process;

            // Pipe the message to stdin and close it so the agent reads EOF
            try (OutputStream stdin = process.getOutputStream()) {
                stdin.write(message.getBytes(StandardCharsets.UTF_8));
                stdin.write('\n');
                stdin.flush();
            }

            // Read and parse stdout, streaming text to terminal
            StringBuilder fullText = new StringBuilder();
            // Accumulate token usage across assistant messages in this turn
            long turnInputTokens = 0, turnOutputTokens = 0;
            long turnCacheRead = 0, turnCacheCreate = 0;
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (isCancelled()) {
                        result.cancelled = true;
                        process.destroyForcibly();
                        break;
                    }

                    List<PassthroughEvent> events = parseAgentLineMulti(line);

                    for (PassthroughEvent event : events) {
                        if (event instanceof PassthroughStreamParser.ThinkingChunk) {
                            // Model is reasoning — skip, don't render as text
                            continue;
                        }
                        if (event instanceof TextChunk tc) {
                            // Check monitor before rendering
                            if (monitor != null) {
                                fullText.append(tc.text());
                                SubprocessAgentRunner.MonitorDecision decision =
                                        monitor.onTextChunk(tc.text(), fullText.toString());
                                if (decision.interrupt()) {
                                    result.monitorInterrupted = true;
                                    result.correctionPrompt = decision.correctionPrompt();
                                    result.text = fullText.toString();
                                    process.destroyForcibly();
                                    activeProcess = null;
                                    firstMessageSent = true;
                                    return result;
                                }
                                printStreamingChunk(tc.text());
                            } else {
                                printStreamingChunk(tc.text());
                                fullText.append(tc.text());
                            }
                        } else if (event instanceof TokenUsage tu) {
                            // Accumulate per-message token usage
                            turnInputTokens += tu.inputTokens();
                            turnOutputTokens += tu.outputTokens();
                            turnCacheRead += tu.cacheReadTokens();
                            turnCacheCreate += tu.cacheCreationTokens();
                            // Display inline token stats
                            System.out.println("\n" + formatTokenStats(
                                    tu.inputTokens(), tu.outputTokens(),
                                    tu.cacheReadTokens(), tu.cacheCreationTokens()));
                            System.out.flush();
                        } else if (event instanceof ToolUse toolUse) {
                            // Check monitor for tool calls
                            if (monitor != null) {
                                SubprocessAgentRunner.MonitorDecision decision =
                                        monitor.onToolUse(toolUse.name(), toolUse.input());
                                if (decision.interrupt()) {
                                    result.monitorInterrupted = true;
                                    result.correctionPrompt = decision.correctionPrompt();
                                    result.text = fullText.toString();
                                    process.destroyForcibly();
                                    activeProcess = null;
                                    firstMessageSent = true;
                                    return result;
                                }
                            }
                            System.out.println("\n  [tool: " + toolUse.name() + "]");
                            System.out.flush();
                        } else if (event instanceof ToolOutput toolOutput) {
                            printToolOutput(toolOutput.output());
                        } else if (event instanceof ToolComplete toolComplete) {
                            printToolOutput(toolComplete.output());
                            String status = toolComplete.exitCode() >= 0
                                    ? "exit " + toolComplete.exitCode()
                                    : "completed";
                            System.out.println("\n  [tool: " + toolComplete.name() + " " + status + "]");
                            System.out.flush();
                        } else if (event instanceof TurnComplete tc) {
                            // Use result-level tokens if available, otherwise use accumulated
                            if (tc.inputTokens() > 0 || tc.outputTokens() > 0) {
                                turnInputTokens = tc.inputTokens();
                                turnOutputTokens = tc.outputTokens();
                                turnCacheRead = tc.cacheReadTokens();
                                turnCacheCreate = tc.cacheCreationTokens();
                            }
                            // Stats line with tokens
                            StringBuilder stats = new StringBuilder();
                            if (tc.durationMs() > 0) {
                                stats.append(String.format("%.1fs", tc.durationMs() / 1000.0));
                            }
                            if (tc.costUsd() > 0) {
                                if (stats.length() > 0) stats.append(" · ");
                                stats.append(String.format("$%.4f", tc.costUsd()));
                            }
                            if (turnInputTokens > 0 || turnOutputTokens > 0) {
                                if (stats.length() > 0) stats.append(" · ");
                                stats.append(FormatUtils.formatNumber(turnInputTokens)).append(" in / ")
                                     .append(FormatUtils.formatNumber(turnOutputTokens)).append(" out");
                                if (turnCacheRead > 0) {
                                    stats.append(" · ").append(FormatUtils.formatNumber(turnCacheRead)).append(" cached");
                                }
                            }
                            if (stats.length() > 0) {
                                System.out.println("\n  \033[2m[" + stats + "]\033[0m");
                                System.out.flush();
                            }
                        } else if (event instanceof PassthroughStreamParser.SessionInit si) {
                            if (si.sessionId() != null) {
                                sessionId = si.sessionId();
                            }
                        }
                    }
                }
            }

            // Wait for process to finish
            try {
                process.waitFor(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                result.cancelled = true;
            }

            activeProcess = null;
            firstMessageSent = true;
            result.text = fullText.toString();

            // Populate token fields so AgenticChatLoop records them to sessionMetrics
            result.inputTokens = turnInputTokens;
            result.outputTokens = turnOutputTokens;
            result.cacheReadTokens = turnCacheRead;
            result.cacheCreationTokens = turnCacheCreate;

        } catch (IOException e) {
            result.text = "Error running agent: " + e.getMessage();
        }

        return result;
    }

    private static void printToolOutput(String output) {
        if (output == null || output.isEmpty()) {
            return;
        }
        System.out.print(output);
        if (!output.endsWith("\n")) {
            System.out.println();
        }
        System.out.flush();
    }

    /**
     * Format per-message token stats as a compact inline string.
     */
    private static String formatTokenStats(long input, long output, long cacheRead, long cacheCreate) {
        StringBuilder sb = new StringBuilder("  \033[2m[tokens: ");
        sb.append(FormatUtils.formatNumber(input)).append(" in / ").append(FormatUtils.formatNumber(output)).append(" out");
        if (cacheRead > 0) {
            sb.append(" · ").append(FormatUtils.formatNumber(cacheRead)).append(" cached");
        }
        if (cacheCreate > 0) {
            sb.append(" · ").append(FormatUtils.formatNumber(cacheCreate)).append(" new cache");
        }
        sb.append("]\033[0m");
        return sb.toString();
    }

    /**
     * Build the command for a message. Uses stdin for the message body (no -p flag).
     * Claude reads stdin when it detects piped input. Uses --continue for multi-turn.
     */
    protected List<String> buildCommand(String message) {
        List<String> cmd = new ArrayList<>();
        cmd.add(agentBinary);

        String name = agentName.toLowerCase();

        if (name.contains("claude")) {
            cmd.add("--output-format");
            cmd.add("stream-json");
            cmd.add("--verbose");
            if (firstMessageSent) {
                cmd.add("--continue");
            }
        } else if (name.contains("gemini")) {
            cmd.add("-o");
            cmd.add("stream-json");
            if (firstMessageSent) {
                cmd.add("--resume");
                cmd.add("latest");
            }
        } else if (name.contains("codex")) {
            cmd.add("exec");
            cmd.add("--json");
            if (firstMessageSent && sessionId != null) {
                cmd.add("resume");
                cmd.add(sessionId);
            }
        } else if (name.contains("qwen")) {
            cmd.add("-o");
            cmd.add("stream-json");
            if (firstMessageSent) {
                cmd.add("--continue");
            }
        } else if (name.contains("opencode")) {
            cmd.add("run");
            cmd.add("--format");
            cmd.add("json");
            if (firstMessageSent) {
                cmd.add("--continue");
            }
        } else if (name.contains("pi")) {
            cmd.add("--mode");
            cmd.add("json");
            cmd.add("--print");
            if (firstMessageSent) {
                cmd.add("--continue");
            }
        }

        // Append extra args (e.g. system prompt flags) after agent-specific args
        cmd.addAll(extraArgs);

        return cmd;
    }

    private List<PassthroughEvent> parseAgentLineMulti(String line) {
        String name = agentName.toLowerCase();
        if (name.contains("claude")) {
            return parser.parseClaudeLineMulti(line);
        }
        PassthroughEvent event;
        if (name.contains("codex")) {
            event = parser.parseCodexLine(line);
        } else if (name.contains("opencode")) {
            return parser.parseOpenCodeLineMulti(line);
        } else if (name.contains("pi")) {
            return parser.parsePiLineMulti(line);
        } else if (name.contains("gemini") || name.contains("qwen")) {
            event = parser.parseGeminiLine(line);
        } else {
            return parser.parseClaudeLineMulti(line);
        }
        return event != null ? List.of(event) : List.of();
    }

    // ── Environment ───────────────────────────────────────────────────────

    private static void inheritEnv(Map<String, String> env, String... keys) {
        for (String key : keys) {
            String val = System.getenv(key);
            if (val != null) {
                env.put(key, val);
            }
        }
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────

    @Override
    public void close() {
        Process p = activeProcess;
        if (p != null && p.isAlive()) {
            p.destroy();
            try {
                if (!p.waitFor(5, TimeUnit.SECONDS)) {
                    p.destroyForcibly();
                }
            } catch (InterruptedException e) {
                p.destroyForcibly();
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * Resolve the agent binary on PATH.
     */
    private static String resolveAgentBinary(String name) {
        return SubprocessAgentRunner.resolveAgentBinary(name);
    }
}
