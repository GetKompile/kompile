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

import ai.kompile.cli.main.chat.config.SystemPromptManager;
import ai.kompile.cli.main.chat.skill.CustomSkillLoader;
import ai.kompile.cli.main.chat.skill.SkillConfig;
import ai.kompile.cli.main.chat.skill.SkillRegistry;
import ai.kompile.cli.main.chat.skill.SkillsInjection;
import ai.kompile.cli.main.chat.render.AsciiRenderer;
import com.fasterxml.jackson.databind.JsonNode;
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

import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.UserInterruptException;
import org.jline.reader.EndOfFileException;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;

/**
 * Passthrough command that launches a CLI agent (Claude Code, Codex, Gemini)
 * with direct terminal inheritance. The agent owns the terminal and provides
 * its full native interactive experience (colors, spinners, prompts, etc.).
 * <p>
 * After the agent exits, its own session log (e.g. Claude's JSONL) is located
 * and parsed to populate kompile session metrics (tokens, turns, tool calls, cost)
 * and save a transcript + metrics JSON for analytics.
 */
@CommandLine.Command(
        name = "passthrough",
        description = "Interactive passthrough to a CLI agent (claude, codex, gemini)",
        mixinStandardHelpOptions = true
)
public class PassthroughCommand implements Callable<Integer> {

    @CommandLine.Option(names = {"--agent", "-a"}, description = "Agent command name", defaultValue = "claude")
    String agent;

    @CommandLine.Option(names = {"--working-dir", "-d"}, description = "Working directory for the agent", defaultValue = ".")
    String workingDir;

    @CommandLine.Option(names = {"--skip-permissions"}, description = "Skip permission prompts", defaultValue = "true")
    boolean skipPermissions;

    @CommandLine.Option(names = {"--inject-tools"}, description = "Inject kompile tools (RAG, Graph RAG, etc.) into the agent via MCP", defaultValue = "true")
    boolean injectTools;

    @CommandLine.Option(names = {"--inject-skills"}, description = "Inject skills.md listing into the agent's system prompt", defaultValue = "true")
    boolean injectSkills;

    @CommandLine.Option(names = {"--url", "-u"}, description = "Kompile-app base URL for MCP tools", defaultValue = "")
    String kompileUrl;

    @CommandLine.Option(names = {"--mcp-port"}, description = "Port for embedded MCP server (0 = auto-detect kompile-app)", defaultValue = "0")
    int mcpPort;

    /** System prompt manager — set by ChatCommand when launching passthrough mode. */
    SystemPromptManager systemPromptManager;

    // Cached resolved MCP URL (to avoid double-probing)
    private McpUrlResolver mcpUrlResolver = new McpUrlResolver();

    private final ObjectMapper objectMapper = new ObjectMapper();

    // ANSI color codes
    private static final String RESET = "\033[0m";
    private static final String CYAN = "\033[36m";
    private static final String GREEN = "\033[32m";
    private static final String YELLOW = "\033[33m";
    private static final String DIM = "\033[2m";
    private static final String BOLD = "\033[1m";

    @Override
    public Integer call() {
        try (Terminal terminal = TerminalBuilder.builder().system(true).build()) {
            LineReader lineReader = LineReaderBuilder.builder()
                    .terminal(terminal)
                    .build();

            int lastExitCode = 0;
            while (true) {
                // Resolve agent binary (re-resolve each loop in case agent changed)
                String agentBinary = resolveAgent(agent);
                if (agentBinary == null) {
                    System.err.println("Agent '" + agent + "' not found on PATH.");
                    return 1;
                }

                // Reset MCP URL cache when re-entering loop
                mcpUrlResolver.resetCache();

                List<String> agentCommand = buildCommand(agentBinary);
                System.out.println(CYAN + "Starting " + agent + " session..." + RESET);
                Path injectedSettingsFile = null;
                if (injectTools) {
                    // Pre-configure Claude Code hooks BEFORE injection/launch so that
                    // settings.local.json is stable when Claude starts watching it.
                    if (agent.toLowerCase(Locale.ROOT).contains("claude")) {
                        ai.kompile.cli.main.chat.mcp.McpToolInjection.ensureHooksPreConfigured(
                                Path.of(workingDir).toAbsolutePath().normalize());
                    }
                    try {
                        String sseUrl = resolveMcpUrl();
                        injectedSettingsFile = ai.kompile.cli.main.chat.mcp.McpToolInjection.injectTools(
                                Path.of(workingDir), agent, sseUrl);
                        if (injectedSettingsFile != null) {
                            String mode = (sseUrl != null && !sseUrl.isBlank()) ? "sse" : "stdio";
                            System.out.println(GREEN + "Kompile tools injected (" + mode + ")" + RESET
                                    + DIM + " (" + injectedSettingsFile + ")" + RESET);
                        }
                    } catch (java.io.IOException e) {
                        System.err.println(YELLOW + "Warning: Could not inject MCP tools: " + e.getMessage() + RESET);
                    }
                }
                // Inject system prompt instruction files (for Codex/OpenCode AGENTS.md)
                if (systemPromptManager != null) {
                    Path injectedPromptFile = systemPromptManager.injectInstructionFile(
                            agent, Path.of(workingDir).toAbsolutePath());
                    if (injectedPromptFile != null) {
                        System.out.println(GREEN + "System prompt injected" + RESET
                                + DIM + " (" + injectedPromptFile + ")" + RESET);
                    }
                }
                // Install skills into the agent's native command/skill infrastructure
                SkillsInjection skillsInjection = null;
                if (injectSkills) {
                    SkillRegistry skillRegistry = new SkillRegistry();
                    CustomSkillLoader loader = new CustomSkillLoader(Path.of(workingDir).toAbsolutePath());
                    for (SkillConfig custom : loader.loadAll().values()) {
                        skillRegistry.register(custom);
                    }
                    skillsInjection = new SkillsInjection(skillRegistry, Path.of(workingDir));
                    int installed = skillsInjection.installSkills(agent);
                    if (installed > 0) {
                        System.out.println(GREEN + "Skills installed (" + installed + " into " + agent + " native commands)" + RESET);
                    }
                }
                System.out.println();

                // Set up transcript and metrics
                String sessionId = "passthrough-" + UUID.randomUUID().toString().substring(0, 8);
                ChatHistory history = new ChatHistory(sessionId);
                ChatSessionMetrics metrics = new ChatSessionMetrics(sessionId);
                metrics.setAgentName(agent);
                Instant startTime = Instant.now();

                try {
                    history.open("", agent, false);
                } catch (IOException e) {
                    System.err.println("Warning: Could not open chat history: " + e.getMessage());
                }

                try {
                    ProcessBuilder pb = new ProcessBuilder(agentCommand);
                    pb.directory(new File(workingDir).getAbsoluteFile());
                    pb.inheritIO();

                    // Set MCP_TIMEOUT to give the kompile MCP server time to connect
                    // before the agent fires the first API request. Claude Code's default
                    // 30s races with slow tool init; 60s provides headroom.
                    // See: https://github.com/anthropics/claude-code/issues/36060
                    pb.environment().putIfAbsent("MCP_TIMEOUT", "60000");

                    // Inject system prompt env vars (Gemini: GEMINI_SYSTEM_MD)
                    if (systemPromptManager != null) {
                        Map<String, String> extraEnv = systemPromptManager.getExtraEnv(agent);
                        if (!extraEnv.isEmpty()) {
                            pb.environment().putAll(extraEnv);
                        }
                    }

                    Process process = pb.start();
                    lastExitCode = process.waitFor();
                } catch (Exception e) {
                    System.err.println("Error running agent: " + e.getMessage());
                    lastExitCode = 1;
                } finally {
                    // Restore original settings to prevent pollution
                    ai.kompile.cli.main.chat.mcp.McpToolInjection.removeTools(injectedSettingsFile);
                    // Restore instruction files modified by system prompt injection
                    if (systemPromptManager != null) {
                        systemPromptManager.cleanup();
                    }
                    // Restore instruction files modified by skills injection
                    if (skillsInjection != null) {
                        skillsInjection.cleanup();
                    }
                }

                // Clean up terminal state after agent exits
                System.out.print("\033[0m"); // reset colors
                System.out.print("\033[?25h"); // show cursor
                System.out.println();
                System.out.println();
                terminal.writer().flush();

                // After the agent exits, harvest its session log for transcript + metrics
                harvestAgentTranscript(history, metrics, startTime);

                // Print session summary and save metrics
                System.out.println();
                printSessionSummary(metrics, history, sessionId);
                Path metricsFile = history.getTranscriptFile()
                        .resolveSibling(sessionId + ".metrics.json");
                metrics.saveToFile(metricsFile, objectMapper);
                history.close();

                // Clear line and prompt user for next action
                System.out.println();
                System.out.print("\033[0m"); // reset formatting
                System.out.println(DIM + "  [r] Relaunch " + agent
                        + "  [a] Switch agent  [q] Quit to shell" + RESET);

                try {
                    String input = lineReader.readLine(CYAN + "kompile> " + RESET);
                    if (input == null) break; // EOF

                    String trimmed = input.trim().toLowerCase();
                    if (trimmed.isEmpty() || trimmed.equals("r") || trimmed.equals("relaunch")) {
                        continue;
                    } else if (trimmed.equals("q") || trimmed.equals("quit") || trimmed.equals("exit")) {
                        break;
                    } else if (trimmed.equals("a") || trimmed.startsWith("agent")) {
                        System.out.println("Available: claude, codex, qwen, opencode, gemini (or any binary on PATH)");
                        String agentInput = lineReader.readLine(CYAN + "agent> " + RESET);
                        if (agentInput != null && !agentInput.trim().isEmpty()) {
                            agent = agentInput.trim();
                        }
                        continue;
                    } else {
                        break;
                    }
                } catch (UserInterruptException | EndOfFileException e) {
                    break;
                }
            }

            return lastExitCode;
        } catch (IOException e) {
            System.err.println("Error initializing terminal: " + e.getMessage());
            return 1;
        }
    }

    // ── Session summary ─────────────────────────────────────────────────────

    /**
     * Print session summary matching the format from ChatRepl.printSessionSummary().
     */
    private void printSessionSummary(ChatSessionMetrics metrics, ChatHistory history, String sessionId) {
        Duration duration = metrics.getSessionDuration();
        StringBuilder body = new StringBuilder();

        // Session info
        body.append(BOLD).append("Session").append(RESET).append("\n");
        body.append("  ID:        ").append(sessionId).append("\n");
        body.append("  Duration:  ").append(metrics.formatDuration(duration)).append("\n");
        if (metrics.getProvider() != null) {
            body.append("  Provider:  ").append(metrics.getProvider());
            if (metrics.getModel() != null) body.append("/").append(metrics.getModel());
            body.append("\n");
        }
        body.append("  Agent:     ").append(agent).append(" (passthrough)").append("\n");

        // Conversation
        if (metrics.getTotalTurns() > 0) {
            body.append("\n").append(BOLD).append("Conversation").append(RESET).append("\n");
            body.append("  Turns:     ").append(metrics.getUserTurns()).append(" user, ")
                    .append(metrics.getAssistantTurns()).append(" assistant");
            body.append(" (").append(metrics.getTotalTurns()).append(" total)").append("\n");
        }

        // Tokens
        body.append("\n").append(BOLD).append("Tokens").append(RESET).append("\n");
        if (metrics.hasActualTokenCounts()) {
            body.append("  Input:     ").append(formatNumber(metrics.getInputTokens())).append("\n");
            body.append("  Output:    ").append(formatNumber(metrics.getOutputTokens())).append("\n");
            body.append("  Total:     ").append(formatNumber(metrics.getTotalTokens())).append("\n");
            if (metrics.getCacheReadTokens() > 0) {
                body.append("  Cache hit: ").append(formatNumber(metrics.getCacheReadTokens())).append("\n");
            }
            if (metrics.getCacheCreationTokens() > 0) {
                body.append("  Cache new: ").append(formatNumber(metrics.getCacheCreationTokens())).append("\n");
            }
        } else {
            long estInput = metrics.getEstimatedInputTokens();
            long estOutput = metrics.getEstimatedOutputTokens();
            if (estInput + estOutput > 0) {
                body.append("  ~Input:    ").append(formatNumber(estInput)).append(" (estimated)\n");
                body.append("  ~Output:   ").append(formatNumber(estOutput)).append(" (estimated)\n");
                body.append("  ~Total:    ").append(formatNumber(estInput + estOutput)).append(" (estimated)\n");
            } else {
                body.append("  (no token data available)\n");
            }
        }

        // Tools
        if (metrics.getTotalToolCalls() > 0) {
            body.append("\n").append(BOLD).append("Tools").append(RESET).append("\n");
            body.append("  Total:     ").append(metrics.getTotalToolCalls()).append(" calls");
            if (metrics.getTotalToolErrors() > 0) {
                body.append(" (").append(metrics.getTotalToolErrors()).append(" errors)");
            }
            body.append("\n");

            List<Map.Entry<String, Integer>> topTools = metrics.getTopTools(8);
            for (Map.Entry<String, Integer> entry : topTools) {
                body.append("  ").append(String.format("%-12s", entry.getKey()))
                        .append(" ").append(entry.getValue()).append("\n");
            }
        }

        // Files
        body.append("\n").append(BOLD).append("Files").append(RESET).append("\n");
        body.append("  Transcript: ").append(history.getTranscriptFile()).append("\n");
        body.append("  Metrics:    ").append(
                history.getTranscriptFile().resolveSibling(sessionId + ".metrics.json")).append("\n");

        System.out.println();
        // Use simple box since we may not have AsciiRenderer's terminal instance
        System.out.println(CYAN + "╭─ Session Summary ──────────────────────────────╮" + RESET);
        for (String line : body.toString().split("\n")) {
            System.out.println(CYAN + "│ " + RESET + line);
        }
        System.out.println(CYAN + "╰────────────────────────────────────────────────╯" + RESET);
        System.out.println();

        // Log summary to transcript
        history.logSystem("Session ended — " + metrics.formatDuration(duration) +
                ", " + metrics.getTotalTurns() + " turns" +
                (metrics.hasActualTokenCounts() ?
                        ", " + formatNumber(metrics.getTotalTokens()) + " tokens" :
                        ", ~" + formatNumber(metrics.getEstimatedInputTokens() + metrics.getEstimatedOutputTokens()) + " est. tokens") +
                (metrics.getTotalToolCalls() > 0 ? ", " + metrics.getTotalToolCalls() + " tool calls" : ""));
    }

    // ── Transcript harvesting ──────────────────────────────────────────────

    /**
     * After the agent exits, find its own session log and parse it into
     * kompile's ChatHistory transcript and ChatSessionMetrics.
     * Cross-platform: reads files the agent already wrote, no I/O interception.
     */
    private void harvestAgentTranscript(ChatHistory history, ChatSessionMetrics metrics, Instant sessionStart) {
        String agentLower = agent.toLowerCase();
        if (agentLower.contains("claude")) {
            harvestClaudeTranscript(history, metrics, sessionStart);
        } else if (agentLower.contains("codex")) {
            harvestCodexTranscript(history, metrics, sessionStart);
        } else if (agentLower.contains("qwen")) {
            harvestQwenTranscript(history, metrics, sessionStart);
        } else if (agentLower.contains("opencode")) {
            harvestOpenCodeTranscript(history, metrics, sessionStart);
        } else if (agentLower.contains("gemini")) {
            harvestGeminiTranscript(history, metrics, sessionStart);
        }
    }

    /**
     * Locate and parse Claude Code's JSONL session log.
     * Claude stores conversations at ~/.claude/projects/{project-hash}/{session-id}.jsonl.
     */
    private void harvestClaudeTranscript(ChatHistory history, ChatSessionMetrics metrics, Instant sessionStart) {
        Path claudeProjectsDir = Path.of(System.getProperty("user.home"), ".claude", "projects");
        if (!Files.isDirectory(claudeProjectsDir)) {
            System.err.println(YELLOW + "Warning: Could not harvest Claude transcript - projects dir not found" + RESET);
            return;
        }

        // Claude encodes the working dir path as the project dir name (/ -> -)
        String absWorkDir = new File(workingDir).getAbsoluteFile().toPath().normalize().toString();
        Path projectDir = findClaudeProjectDir(claudeProjectsDir, absWorkDir);
        if (projectDir == null) {
            System.err.println(YELLOW + "Warning: Could not harvest Claude transcript - project dir not found for: " + absWorkDir + RESET);
            return;
        }

        // Find the most recently modified .jsonl created during our session
        File jsonlFile = findNewestJsonl(projectDir, sessionStart);
        if (jsonlFile == null) {
            System.err.println(YELLOW + "Warning: Could not harvest Claude transcript - no JSONL session file found in: " + projectDir + RESET);
            return;
        }

        parseClaudeJsonl(jsonlFile, history, metrics);
    }

    /**
     * Find the Claude project directory by matching against the working dir path.
     * Claude encodes paths by replacing '/' with '-', so /home/user/my-project
     * becomes -home-user-my-project. To avoid ambiguity with directory names
     * that contain hyphens, we use a reverse-lookup approach: encode each candidate
     * directory name the same way Claude would, and check for a match.
     */
    private Path findClaudeProjectDir(Path claudeProjectsDir, String absWorkDir) {
        String normalized = absWorkDir.replace("\\", "/");

        // Compute the expected encoded name for the working directory
        String expectedEncoded = normalized.replace("/", "-");

        // Try direct match first (exact encoded name)
        Path direct = claudeProjectsDir.resolve(expectedEncoded);
        if (Files.isDirectory(direct)) return direct;

        // Reverse lookup: iterate directory entries, encode each one the way Claude would,
        // and compare against the expected encoding of the working directory.
        File[] dirs = claudeProjectsDir.toFile().listFiles(File::isDirectory);
        if (dirs == null) return null;

        for (File dir : dirs) {
            String name = dir.getName();
            // For each candidate, try to decode it back to a path by trying all
            // possible split points for hyphens. Instead, we do the reverse:
            // take the candidate name, and check if our normalized path, when encoded,
            // matches the candidate name.
            // Since we don't know which hyphens are path separators vs. part of dir names,
            // we check: does the candidate, when decoded (all hyphens -> slashes), produce
            // a prefix of our working directory?
            if (!name.startsWith("-")) continue;
            String decoded = "/" + name.substring(1).replace("-", "/");
            if (normalized.equals(decoded) || normalized.startsWith(decoded + "/")
                    || decoded.startsWith(normalized + "/")) {
                return dir.toPath();
            }
        }

        // Fallback: try the old naive encoding match
        String directName = normalized.replace("/", "-");
        for (File dir : dirs) {
            if (dir.getName().equals(directName)) {
                return dir.toPath();
            }
        }
        return null;
    }

    /**
     * Find the most recently modified .jsonl file that was updated during our session.
     * Uses a 30-second tolerance window to account for slow agent writes or filesystem
     * latency. Tradeoff: a wider window reduces the chance of missing the session file,
     * but could theoretically pick up a file from a very recent prior session if the
     * agent didn't write anything this time. The 30s window is a balance between these.
     */
    private File findNewestJsonl(Path projectDir, Instant sessionStart) {
        File[] jsonlFiles = projectDir.toFile().listFiles(
                (dir, name) -> name.endsWith(".jsonl"));
        if (jsonlFiles == null || jsonlFiles.length == 0) return null;

        long startMillis = sessionStart.toEpochMilli();
        File newest = null;
        long newestTime = 0;

        for (File f : jsonlFiles) {
            long lastMod = f.lastModified();
            // Must have been modified after our session started (30s tolerance to
            // account for slow agent writes or filesystem latency)
            if (lastMod >= startMillis - 30000 && lastMod > newestTime) {
                newestTime = lastMod;
                newest = f;
            }
        }
        return newest;
    }

    /**
     * Parse a Claude JSONL session file, populating ChatHistory and ChatSessionMetrics.
     * <p>
     * JSONL entry types:
     * - "user": user messages with message.content (string or array)
     * - "assistant": assistant messages with message.content[] (text/tool_use blocks)
     *                and message.usage (input_tokens, output_tokens, cache_read_input_tokens, etc.)
     * - "file-history-snapshot": skipped
     */
    private void parseClaudeJsonl(File jsonlFile, ChatHistory history, ChatSessionMetrics metrics) {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new FileInputStream(jsonlFile), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;

                try {
                    JsonNode node = objectMapper.readTree(line);
                    String type = node.has("type") ? node.get("type").asText() : "";

                    switch (type) {
                        case "user" -> parseClaudeUserEntry(node, history, metrics);
                        case "assistant" -> parseClaudeAssistantEntry(node, history, metrics);
                        // Skip: file-history-snapshot, system, summary, etc.
                    }
                } catch (Exception e) {
                    // Skip unparseable lines
                }
            }
        } catch (IOException e) {
            // Couldn't read the file — metrics will show zeros
        }
    }

    // ── Codex harvester ───────────────────────────────────────────────────

    /**
     * Locate and parse Codex session logs.
     * Codex stores sessions at ~/.codex/sessions/YYYY/MM/DD/rollout-{timestamp}-{session-id}.jsonl
     * Entry types: session_meta, response_item (user/assistant/function_call/function_call_output),
     * event_msg (user_message/agent_message/token_count/turn_aborted), turn_context
     */
    private void harvestCodexTranscript(ChatHistory history, ChatSessionMetrics metrics, Instant sessionStart) {
        Path sessionsDir = Path.of(System.getProperty("user.home"), ".codex", "sessions");
        if (!Files.isDirectory(sessionsDir)) return;

        // Find the newest .jsonl modified during our session
        File jsonlFile = findNewestJsonlRecursive(sessionsDir, sessionStart);
        if (jsonlFile == null) return;

        parseCodexJsonl(jsonlFile, history, metrics);
    }

    private void parseCodexJsonl(File jsonlFile, ChatHistory history, ChatSessionMetrics metrics) {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new FileInputStream(jsonlFile), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                try {
                    JsonNode node = objectMapper.readTree(line);
                    String type = node.path("type").asText("");
                    JsonNode payload = node.path("payload");

                    switch (type) {
                        case "session_meta" -> {
                            String provider = payload.path("model_provider").asText("");
                            if (!provider.isEmpty()) metrics.setProvider(provider);
                        }
                        case "turn_context" -> {
                            String model = payload.path("model").asText("");
                            if (!model.isEmpty()) metrics.setModel(model);
                        }
                        case "response_item" -> {
                            String payloadType = payload.path("type").asText("");
                            String role = payload.path("role").asText("");
                            switch (payloadType) {
                                case "message" -> {
                                    if ("user".equals(role)) {
                                        String text = extractCodexTextContent(payload);
                                        if (text != null && !text.isBlank()
                                                && !text.startsWith("<environment_context>")) {
                                            history.logUserMessage(text);
                                            metrics.recordUserTurn(text);
                                        }
                                    } else if ("assistant".equals(role)) {
                                        String text = extractCodexTextContent(payload);
                                        if (text != null && !text.isBlank()) {
                                            history.logAgentResponse(agent, text, 0);
                                            metrics.recordAssistantTurn(text, 0);
                                        }
                                    }
                                }
                                case "function_call" -> {
                                    String toolName = payload.path("name").asText("unknown");
                                    history.logToolCall(toolName, false, 0);
                                    metrics.recordToolCall(toolName, false, 0);
                                    ToolCallIndex.getInstance().record(
                                            metrics.getSessionId(), toolName,
                                            payload.path("arguments").toString(),
                                            agent, "passthrough", false, 0,
                                            absWorkDir());
                                }
                                case "custom_tool_call" -> {
                                    String toolName = payload.path("name").asText("unknown");
                                    String status = payload.path("status").asText("");
                                    boolean isError = "error".equalsIgnoreCase(status);
                                    history.logToolCall(toolName, isError, 0);
                                    metrics.recordToolCall(toolName, isError, 0);
                                    ToolCallIndex.getInstance().record(
                                            metrics.getSessionId(), toolName,
                                            payload.path("arguments").toString(),
                                            agent, "passthrough", isError, 0,
                                            absWorkDir());
                                }
                            }
                        }
                        case "event_msg" -> {
                            String payloadType = payload.path("type").asText("");
                            if ("user_message".equals(payloadType)) {
                                String msg = payload.path("message").asText("");
                                if (!msg.isBlank()) {
                                    history.logUserMessage(msg);
                                    metrics.recordUserTurn(msg);
                                }
                            } else if ("agent_message".equals(payloadType)) {
                                String msg = payload.path("message").asText("");
                                if (!msg.isBlank()) {
                                    history.logAgentResponse(agent, msg, 0);
                                    metrics.recordAssistantTurn(msg, 0);
                                }
                            } else if ("turn_aborted".equals(payloadType)) {
                                history.logSystem("[codex] Turn aborted — session ended mid-turn");
                            }
                        }
                    }
                } catch (Exception e) {
                    // Skip unparseable lines
                }
            }
        } catch (IOException e) {
            // Couldn't read
        }
    }

    private String extractCodexTextContent(JsonNode payload) {
        JsonNode content = payload.path("content");
        if (content.isArray()) {
            StringBuilder sb = new StringBuilder();
            for (JsonNode block : content) {
                String blockType = block.path("type").asText("");
                if ("input_text".equals(blockType) || "output_text".equals(blockType)) {
                    sb.append(block.path("text").asText(""));
                }
            }
            return sb.length() > 0 ? sb.toString() : null;
        }
        return null;
    }

    // ── Qwen harvester ──────────────────────────────────────────────────────

    /**
     * Locate and parse Qwen Code session logs.
     * Qwen stores sessions at ~/.qwen/projects/{project-hash}/chats/{session-id}.jsonl
     * Same project-dir encoding as Claude (/ -> -).
     * Entry types: user, assistant (with usageMetadata), system (ui_telemetry with token counts),
     * tool_result
     */
    private void harvestQwenTranscript(ChatHistory history, ChatSessionMetrics metrics, Instant sessionStart) {
        Path qwenProjectsDir = Path.of(System.getProperty("user.home"), ".qwen", "projects");
        if (!Files.isDirectory(qwenProjectsDir)) return;

        String absWorkDir = new File(workingDir).getAbsoluteFile().toPath().normalize().toString();
        // Qwen uses same project-dir encoding as Claude
        Path projectDir = findClaudeProjectDir(qwenProjectsDir, absWorkDir);
        if (projectDir == null) return;

        // Qwen puts JSONL in a chats/ subdirectory
        Path chatsDir = projectDir.resolve("chats");
        if (!Files.isDirectory(chatsDir)) {
            // Fallback: look directly in project dir
            chatsDir = projectDir;
        }

        File jsonlFile = findNewestJsonl(chatsDir, sessionStart);
        if (jsonlFile == null) return;

        parseQwenJsonl(jsonlFile, history, metrics);
    }

    private void parseQwenJsonl(File jsonlFile, ChatHistory history, ChatSessionMetrics metrics) {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new FileInputStream(jsonlFile), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                try {
                    JsonNode node = objectMapper.readTree(line);
                    String type = node.path("type").asText("");

                    switch (type) {
                        case "user" -> {
                            String text = extractQwenMessageText(node);
                            if (text != null && !text.isBlank()) {
                                history.logUserMessage(text);
                                metrics.recordUserTurn(text);
                            }
                        }
                        case "assistant" -> {
                            // Model info
                            String model = node.path("model").asText("");
                            if (!model.isEmpty() && metrics.getProvider() == null) {
                                metrics.setProvider("qwen");
                                metrics.setModel(model);
                            }

                            // Token usage from usageMetadata
                            JsonNode usage = node.path("usageMetadata");
                            if (!usage.isMissingNode()) {
                                long inputTok = usage.path("promptTokenCount").asLong(0);
                                long outputTok = usage.path("candidatesTokenCount").asLong(0);
                                long cacheTok = usage.path("cachedContentTokenCount").asLong(0);
                                metrics.recordTokenUsage(inputTok, outputTok, cacheTok, 0);
                            }

                            // Extract text and tool calls from parts
                            JsonNode parts = node.path("message").path("parts");
                            if (parts.isArray()) {
                                StringBuilder textContent = new StringBuilder();
                                for (JsonNode part : parts) {
                                    // Skip thinking parts
                                    if (part.path("thought").asBoolean(false)) continue;

                                    // Text content
                                    if (part.has("text")) {
                                        textContent.append(part.get("text").asText());
                                    }
                                    // Tool calls (functionCall)
                                    JsonNode funcCall = part.path("functionCall");
                                    if (!funcCall.isMissingNode()) {
                                        String toolName = funcCall.path("name").asText("unknown");
                                        history.logToolCall(toolName, false, 0);
                                        metrics.recordToolCall(toolName, false, 0);
                                        ToolCallIndex.getInstance().record(
                                                metrics.getSessionId(), toolName,
                                                funcCall.path("args").toString(),
                                                agent, "passthrough", false, 0,
                                                absWorkDir());
                                    }
                                }
                                if (textContent.length() > 0) {
                                    String text = textContent.toString().trim();
                                    history.logAgentResponse(agent, text, 0);
                                    metrics.recordAssistantTurn(text, 0);
                                }
                            }
                        }
                        case "system" -> {
                            // Extract token counts from ui_telemetry events
                            String subtype = node.path("subtype").asText("");
                            if ("ui_telemetry".equals(subtype)) {
                                JsonNode uiEvent = node.path("systemPayload").path("uiEvent");
                                String eventName = uiEvent.path("event.name").asText("");
                                if (eventName.contains("api_response")) {
                                    long inputTok = uiEvent.path("input_token_count").asLong(0);
                                    long outputTok = uiEvent.path("output_token_count").asLong(0);
                                    long cacheTok = uiEvent.path("cached_content_token_count").asLong(0);
                                    // Don't double-count if already counted from usageMetadata
                                    // ui_telemetry may fire alongside assistant entries
                                }
                            }
                        }
                        case "tool_result" -> {
                            // Tool results contain function responses
                            JsonNode parts = node.path("message").path("parts");
                            if (parts.isArray()) {
                                for (JsonNode part : parts) {
                                    JsonNode funcResp = part.path("functionResponse");
                                    if (!funcResp.isMissingNode()) {
                                        String toolName = funcResp.path("name").asText("");
                                        if (!toolName.isEmpty()) {
                                            history.logSystem("Tool result: " + toolName);
                                        }
                                    }
                                }
                            }
                        }
                    }
                } catch (Exception e) {
                    // Skip unparseable lines
                }
            }
        } catch (IOException e) {
            // Couldn't read
        }
    }

    private String extractQwenMessageText(JsonNode node) {
        JsonNode parts = node.path("message").path("parts");
        if (parts.isArray()) {
            StringBuilder sb = new StringBuilder();
            for (JsonNode part : parts) {
                if (part.has("text") && !part.path("thought").asBoolean(false)) {
                    sb.append(part.get("text").asText());
                }
            }
            return sb.length() > 0 ? sb.toString() : null;
        }
        return null;
    }

    // ── OpenCode harvester ──────────────────────────────────────────────────

    /**
     * Locate and parse OpenCode session data from its SQLite database.
     * OpenCode stores sessions in ~/.local/share/opencode/opencode.db with tables:
     * session (id, project_id, directory, title, time_created, time_updated),
     * message (id, session_id, role, data, time_created).
     * Message.data is a JSON blob containing model info, parts (text/tool_use/tool_result), etc.
     */
    private void harvestOpenCodeTranscript(ChatHistory history, ChatSessionMetrics metrics, Instant sessionStart) {
        Path opencodeDb = Path.of(System.getProperty("user.home"), ".local", "share", "opencode", "opencode.db");
        if (!Files.exists(opencodeDb)) {
            history.logSystem("OpenCode session - no database found.");
            return;
        }

        String absWorkDir = new File(workingDir).getAbsoluteFile().toPath().normalize().toString();

        try (java.sql.Connection conn = java.sql.DriverManager.getConnection("jdbc:sqlite:" + opencodeDb.toAbsolutePath())) {
            try (java.sql.Statement pragmaStmt = conn.createStatement()) {
                pragmaStmt.execute("PRAGMA busy_timeout = 5000");
            }

            // Find project ID for current working directory
            String projectId = null;
            try (java.sql.PreparedStatement stmt = conn.prepareStatement(
                    "SELECT id FROM project WHERE worktree = ?")) {
                stmt.setString(1, absWorkDir);
                try (java.sql.ResultSet rs = stmt.executeQuery()) {
                    if (rs.next()) projectId = rs.getString("id");
                }
            }

            if (projectId == null) {
                history.logSystem("OpenCode session - no project found for: " + absWorkDir);
                return;
            }

            // Find the most recent session modified after our passthrough started
            long startMs = sessionStart.toEpochMilli();
            String openCodeSessionId = null;
            try (java.sql.PreparedStatement stmt = conn.prepareStatement(
                    "SELECT id FROM session WHERE project_id = ? AND time_updated >= ? ORDER BY time_updated DESC LIMIT 1")) {
                stmt.setString(1, projectId);
                stmt.setLong(2, startMs - 30000); // 30s tolerance
                try (java.sql.ResultSet rs = stmt.executeQuery()) {
                    if (rs.next()) openCodeSessionId = rs.getString("id");
                }
            }

            if (openCodeSessionId == null) {
                history.logSystem("OpenCode session - no session found after passthrough start time.");
                return;
            }

            // Read all messages for this session, ordered by creation time
            try (java.sql.PreparedStatement stmt = conn.prepareStatement(
                    "SELECT role, data FROM message WHERE session_id = ? ORDER BY time_created ASC")) {
                stmt.setString(1, openCodeSessionId);
                try (java.sql.ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        String role = rs.getString("role");
                        String data = rs.getString("data");
                        parseOpenCodeMessage(role, data, history, metrics);
                    }
                }
            }
        } catch (Exception e) {
            history.logSystem("OpenCode session - error reading database: " + e.getMessage());
        }
    }

    /**
     * Parse a single OpenCode message row. The data column is a JSON blob with:
     * model: { providerID, modelID }, parts: [ { type, text/toolName/... } ], usage: { input, output }.
     */
    private void parseOpenCodeMessage(String role, String data, ChatHistory history, ChatSessionMetrics metrics) {
        if (data == null || data.isBlank()) return;
        try {
            JsonNode node = objectMapper.readTree(data);

            // Extract model info
            JsonNode model = node.path("model");
            if (!model.isMissingNode()) {
                String providerId = model.path("providerID").asText("");
                String modelId = model.path("modelID").asText("");
                if (!providerId.isEmpty() && metrics.getProvider() == null) metrics.setProvider(providerId);
                if (!modelId.isEmpty() && metrics.getModel() == null) metrics.setModel(modelId);
            }

            // Extract token usage
            JsonNode usage = node.path("usage");
            if (!usage.isMissingNode()) {
                long inputTokens = usage.path("input").asLong(0);
                long outputTokens = usage.path("output").asLong(0);
                long cacheRead = usage.path("cacheRead").asLong(0);
                long cacheWrite = usage.path("cacheWrite").asLong(0);
                if (inputTokens > 0 || outputTokens > 0) {
                    metrics.recordTokenUsage(inputTokens, outputTokens, cacheRead, cacheWrite);
                }
            }

            // Extract text and tool calls from parts array
            JsonNode parts = node.path("parts");
            if (!parts.isArray()) {
                // Fallback: some messages store content directly
                String content = node.path("content").asText("");
                if (!content.isBlank()) {
                    if ("user".equals(role)) {
                        history.logUserMessage(content);
                        metrics.recordUserTurn(content);
                    } else if ("assistant".equals(role)) {
                        history.logAgentResponse(agent, content, 0);
                        metrics.recordAssistantTurn(content, 0);
                    }
                }
                return;
            }

            StringBuilder textContent = new StringBuilder();
            for (JsonNode part : parts) {
                String type = part.path("type").asText("");
                switch (type) {
                    case "text" -> {
                        String text = part.path("text").asText("");
                        if (!text.isBlank()) textContent.append(text);
                    }
                    case "tool-invocation" -> {
                        String toolName = part.path("toolName").asText(
                                part.path("name").asText("unknown"));
                        boolean isError = "error".equals(part.path("state").asText(""));
                        history.logToolCall(toolName, isError, 0);
                        metrics.recordToolCall(toolName, isError, 0);
                        ToolCallIndex.getInstance().record(
                                metrics.getSessionId(), toolName,
                                part.path("input").toString(),
                                agent, "passthrough", isError, 0,
                                absWorkDir());
                    }
                }
            }

            String text = textContent.toString().trim();
            if (!text.isEmpty()) {
                if ("user".equals(role)) {
                    history.logUserMessage(text);
                    metrics.recordUserTurn(text);
                } else if ("assistant".equals(role)) {
                    history.logAgentResponse(agent, text, 0);
                    metrics.recordAssistantTurn(text, 0);
                }
            }
        } catch (Exception e) {
            // Skip unparseable message
        }
    }

    /**
     * Locate and parse Gemini CLI session logs.
     * Gemini CLI stores sessions at ~/.gemini/sessions/ as JSONL files.
     * TODO: Update path and parsing if Gemini changes its session storage format.
     */
    private void harvestGeminiTranscript(ChatHistory history, ChatSessionMetrics metrics, Instant sessionStart) {
        Path geminiSessionsDir = Path.of(System.getProperty("user.home"), ".gemini", "sessions");
        if (!Files.isDirectory(geminiSessionsDir)) {
            // Fallback: check for .gemini directory at all
            Path geminiDir = Path.of(System.getProperty("user.home"), ".gemini");
            if (!Files.isDirectory(geminiDir)) {
                history.logSystem("Gemini session - no .gemini directory found.");
                return;
            }
            // If .gemini exists but no sessions subdir, try to find JSONL files
            File jsonlFile = findNewestJsonlRecursive(geminiDir, sessionStart);
            if (jsonlFile == null) {
                history.logSystem("Gemini session - no JSONL logs found in .gemini directory.");
                return;
            }
            parseGeminiJsonl(jsonlFile, history, metrics);
            return;
        }

        File jsonlFile = findNewestJsonlRecursive(geminiSessionsDir, sessionStart);
        if (jsonlFile == null) {
            history.logSystem("Gemini session - no JSONL logs found in sessions directory.");
            return;
        }

        parseGeminiJsonl(jsonlFile, history, metrics);
    }

    /**
     * Parse a Gemini CLI JSONL session file.
     * Entry types may include: user, assistant (model), tool_use, tool_result, system.
     * TODO: Verify actual Gemini JSONL schema and adjust field names accordingly.
     */
    private void parseGeminiJsonl(File jsonlFile, ChatHistory history, ChatSessionMetrics metrics) {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new FileInputStream(jsonlFile), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                try {
                    JsonNode node = objectMapper.readTree(line);
                    String role = node.path("role").asText("");
                    String type = node.path("type").asText("");

                    switch (role) {
                        case "user" -> {
                            String text = extractGeminiUserText(node);
                            if (text != null && !text.isBlank()) {
                                history.logUserMessage(text);
                                metrics.recordUserTurn(text);
                            }
                        }
                        case "model", "assistant" -> {
                            String text = extractGeminiAssistantText(node);
                            long tokenCount = node.path("tokenCount").asLong(0);
                            if (tokenCount > 0) {
                                metrics.recordAssistantTurn(text != null ? text : "", tokenCount);
                            }
                            if (text != null && !text.isBlank()) {
                                // Also log if we got text
                                if (tokenCount == 0) {
                                    metrics.recordAssistantTurn(text, 0);
                                }
                            }
                        }
                        case "tool" -> {
                            String toolName = node.path("name").asText("unknown");
                            boolean isError = node.path("error").asBoolean(false);
                            history.logToolCall(toolName, isError, 0);
                            metrics.recordToolCall(toolName, isError, 0);
                            ToolCallIndex.getInstance().record(
                                    metrics.getSessionId(), toolName,
                                    node.path("input").toString(),
                                    agent, "passthrough", isError, 0,
                                    absWorkDir());
                        }
                        default -> {
                            // system, etc. — skip
                        }
                    }
                } catch (Exception e) {
                    // Skip unparseable lines
                }
            }
        } catch (IOException e) {
            // Couldn't read the file — metrics will show zeros
        }
    }

    private String extractGeminiUserText(JsonNode node) {
        // Try common field patterns for user message text
        if (node.has("text") && node.get("text").isTextual()) {
            return node.get("text").asText();
        }
        if (node.has("content") && node.get("content").isTextual()) {
            return node.get("content").asText();
        }
        if (node.has("message") && node.get("message").isTextual()) {
            return node.get("message").asText();
        }
        if (node.has("parts") && node.get("parts").isArray()) {
            StringBuilder sb = new StringBuilder();
            for (JsonNode part : node.get("parts")) {
                if (part.has("text") && part.get("text").isTextual()) {
                    if (sb.length() > 0) sb.append("\n");
                    sb.append(part.get("text").asText());
                }
            }
            return sb.isEmpty() ? null : sb.toString();
        }
        return null;
    }

    private String extractGeminiAssistantText(JsonNode node) {
        // Try common field patterns for assistant/model message text
        if (node.has("text") && node.get("text").isTextual()) {
            return node.get("text").asText();
        }
        if (node.has("content") && node.get("content").isTextual()) {
            return node.get("content").asText();
        }
        if (node.has("message") && node.get("message").isTextual()) {
            return node.get("message").asText();
        }
        if (node.has("parts") && node.get("parts").isArray()) {
            StringBuilder sb = new StringBuilder();
            for (JsonNode part : node.get("parts")) {
                if (part.has("text") && part.get("text").isTextual()) {
                    if (sb.length() > 0) sb.append("\n");
                    sb.append(part.get("text").asText());
                }
            }
            return sb.isEmpty() ? null : sb.toString();
        }
        return null;
    }

    // ── Shared helpers for session log discovery ────────────────────────────

    /**
     * Recursively find the newest .jsonl file modified during our session.
     * Used by Codex which stores logs in nested YYYY/MM/DD directories.
     */
    private File findNewestJsonlRecursive(Path rootDir, Instant sessionStart) {
        long startMillis = sessionStart.toEpochMilli();
        File[] allJsonl = findJsonlFilesRecursive(rootDir.toFile());
        if (allJsonl == null || allJsonl.length == 0) return null;

        File newest = null;
        long newestTime = 0;
        for (File f : allJsonl) {
            long lastMod = f.lastModified();
            // 30s tolerance to match findNewestJsonl
            if (lastMod >= startMillis - 30000 && lastMod > newestTime) {
                newestTime = lastMod;
                newest = f;
            }
        }
        return newest;
    }

    private File[] findJsonlFilesRecursive(File dir) {
        List<File> results = new ArrayList<>();
        collectJsonlFiles(dir, results);
        return results.toArray(new File[0]);
    }

    private void collectJsonlFiles(File dir, List<File> results) {
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            if (f.isDirectory()) {
                collectJsonlFiles(f, results);
            } else if (f.getName().endsWith(".jsonl")) {
                results.add(f);
            }
        }
    }

    private void parseClaudeUserEntry(JsonNode node, ChatHistory history, ChatSessionMetrics metrics) {
        // Check for tool_result entries (these contain subprocess/subagent results)
        JsonNode toolUseResult = node.path("toolUseResult");
        if (!toolUseResult.isMissingNode() && toolUseResult.isObject()) {
            parseToolUseResult(toolUseResult, history, metrics);
            return; // tool_result user entries are not user messages
        }

        String content = extractMessageContent(node);
        if (content != null && !content.isBlank() && !content.startsWith("{\"tool_use_id\"")) {
            history.logUserMessage(content);
            metrics.recordUserTurn(content);
        }
    }

    /**
     * Parse a toolUseResult from a user entry. These appear when the agent
     * receives results from tool calls (including subagent completions).
     * <p>
     * Fields: status, totalDurationMs, totalTokens, totalToolUseCount, usage{}, agentId, content[]
     */
    private void parseToolUseResult(JsonNode result, ChatHistory history, ChatSessionMetrics metrics) {
        String status = result.path("status").asText("unknown");
        boolean isError = "error".equals(status);

        // Check if this is a subagent result (has agentId)
        String agentId = result.path("agentId").asText("");
        if (!agentId.isEmpty()) {
            long durationMs = result.path("totalDurationMs").asLong(0);
            int subTokens = result.path("totalTokens").asInt(0);
            int subToolCount = result.path("totalToolUseCount").asInt(0);

            // Extract description from the prompt if available
            String prompt = result.path("prompt").asText("");
            String description = prompt.length() > 80 ? prompt.substring(0, 77) + "..." : prompt;

            history.logSubagent("passthrough-subagent", description, durationMs, isError);
            metrics.recordAgenticStep();

            // Record the subagent's own token usage if present
            JsonNode subUsage = result.path("usage");
            if (!subUsage.isMissingNode()) {
                long inputTok = subUsage.path("input_tokens").asLong(0);
                long outputTok = subUsage.path("output_tokens").asLong(0);
                long cacheRead = subUsage.path("cache_read_input_tokens").asLong(0);
                long cacheCreation = subUsage.path("cache_creation_input_tokens").asLong(0);
                metrics.recordTokenUsage(inputTok, outputTok, cacheRead, cacheCreation);
            }

            history.logSystem(String.format("Subagent completed: %s, %dms, %d tokens, %d tool calls",
                    status, durationMs, subTokens, subToolCount));
            return;
        }

        // Regular tool result — extract file/type metadata for the transcript
        String resultType = result.path("type").asText("");
        String filePath = result.path("filePath").asText("");
        if (!resultType.isEmpty() || !filePath.isEmpty()) {
            String detail = resultType;
            if (!filePath.isEmpty()) {
                detail += (detail.isEmpty() ? "" : " ") + filePath;
            }
            history.logSystem("Tool result: " + detail);
        }
    }

    private void parseClaudeAssistantEntry(JsonNode node, ChatHistory history, ChatSessionMetrics metrics) {
        JsonNode message = node.path("message");
        if (message.isMissingNode()) return;

        // Extract model info (first time we see it)
        if (message.has("model") && metrics.getProvider() == null) {
            String model = message.get("model").asText();
            metrics.setProvider("anthropic");
            metrics.setModel(model);
        }

        // Extract token usage from message.usage
        JsonNode usage = message.path("usage");
        if (!usage.isMissingNode()) {
            long inputTok = usage.path("input_tokens").asLong(0);
            long outputTok = usage.path("output_tokens").asLong(0);
            long cacheRead = usage.path("cache_read_input_tokens").asLong(0);
            long cacheCreation = usage.path("cache_creation_input_tokens").asLong(0);
            metrics.recordTokenUsage(inputTok, outputTok, cacheRead, cacheCreation);
        }

        // Extract content blocks
        StringBuilder textContent = new StringBuilder();
        JsonNode contentArray = message.path("content");

        if (contentArray.isArray()) {
            for (JsonNode block : contentArray) {
                String blockType = block.has("type") ? block.get("type").asText() : "";
                switch (blockType) {
                    case "text" -> {
                        if (block.has("text")) {
                            textContent.append(block.get("text").asText());
                        }
                    }
                    case "tool_use" -> {
                        String toolName = block.has("name") ? block.get("name").asText() : "unknown";
                        JsonNode toolInput = block.path("input");

                        // Detect subagent invocations (Agent tool)
                        if ("Agent".equals(toolName)) {
                            String subagentType = toolInput.path("subagent_type").asText("unknown");
                            String description = toolInput.path("description").asText("");
                            String prompt = toolInput.path("prompt").asText("");
                            String subagentDesc = description.isEmpty()
                                    ? (prompt.length() > 80 ? prompt.substring(0, 77) + "..." : prompt)
                                    : description;
                            history.logSubagent(subagentType, subagentDesc, 0, false);
                            metrics.recordToolCall("Agent:" + subagentType, false, 0);
                            metrics.recordAgenticStep();
                            // Index subagent tool call
                            ToolCallIndex.getInstance().record(
                                    metrics.getSessionId(), "Agent:" + subagentType,
                                    toolInput.toString(), agent, "passthrough", false, 0,
                                    absWorkDir());
                        } else {
                            // Regular tool use — log with input summary
                            String inputSummary = summarizeToolInput(toolName, toolInput);
                            history.logToolCall(toolName, false, 0);
                            if (!inputSummary.isEmpty()) {
                                history.logSystem("  " + toolName + ": " + inputSummary);
                            }
                            metrics.recordToolCall(toolName, false, 0);
                            // Index tool call for catalog search
                            ToolCallIndex.getInstance().record(
                                    metrics.getSessionId(), toolName,
                                    toolInput.toString(), agent, "passthrough", false, 0,
                                    absWorkDir());
                        }
                    }
                    // Skip: thinking, tool_result, etc.
                }
            }
        } else if (contentArray.isTextual()) {
            textContent.append(contentArray.asText());
        }

        if (textContent.length() > 0) {
            String text = textContent.toString().trim();
            history.logAgentResponse(agent, text, 0);
            metrics.recordAssistantTurn(text, 0);
        }
    }

    /**
     * Produce a short summary of a tool's input for transcript logging.
     * Extracts the most meaningful field(s) depending on the tool type.
     */
    private String summarizeToolInput(String toolName, JsonNode input) {
        if (input == null || input.isMissingNode() || input.isEmpty()) return "";

        return switch (toolName) {
            case "Read" -> input.path("file_path").asText("");
            case "Write" -> input.path("file_path").asText("");
            case "Edit" -> {
                String file = input.path("file_path").asText("");
                String old = input.path("old_string").asText("");
                if (old.length() > 40) old = old.substring(0, 37) + "...";
                yield file + (old.isEmpty() ? "" : " (replacing: " + old + ")");
            }
            case "Bash" -> {
                String cmd = input.path("command").asText("");
                yield cmd.length() > 80 ? cmd.substring(0, 77) + "..." : cmd;
            }
            case "Grep" -> {
                String pattern = input.path("pattern").asText("");
                String path = input.path("path").asText("");
                yield pattern + (path.isEmpty() ? "" : " in " + path);
            }
            case "Glob" -> input.path("pattern").asText("");
            case "WebFetch" -> input.path("url").asText("");
            case "WebSearch" -> input.path("query").asText("");
            default -> {
                // Generic: show first string field up to 80 chars
                String summary = input.toString();
                yield summary.length() > 80 ? summary.substring(0, 77) + "..." : summary;
            }
        };
    }

    /**
     * Extract the text content from a JSONL user message node.
     * Handles both string content and array content (tool_result entries).
     */
    private String extractMessageContent(JsonNode node) {
        JsonNode message = node.path("message");
        if (message.isMissingNode()) return null;

        JsonNode content = message.path("content");
        if (content.isTextual()) {
            return content.asText();
        }
        if (content.isArray()) {
            StringBuilder sb = new StringBuilder();
            for (JsonNode block : content) {
                if (block.isTextual()) {
                    sb.append(block.asText());
                } else if (block.has("type") && "text".equals(block.get("type").asText())) {
                    sb.append(block.path("text").asText(""));
                }
            }
            return sb.length() > 0 ? sb.toString() : null;
        }
        return null;
    }

    /** Resolve the working directory to an absolute path for indexing. */
    private String absWorkDir() {
        return new File(workingDir).getAbsoluteFile().toPath().normalize().toString();
    }

    // ── Agent resolution & command building ─────────────────────────────────

    private String resolveAgent(String name) {
        String binary = switch (name.toLowerCase()) {
            case "claude", "claude-code" -> "claude";
            case "codex" -> "codex";
            case "gemini" -> "gemini";
            case "qwen", "qwen-code" -> "qwen";
            case "opencode", "open-code" -> "opencode";
            default -> name;
        };

        String path = System.getenv("PATH");
        if (path != null) {
            for (String dir : path.split(File.pathSeparator)) {
                File candidate = new File(dir, binary);
                if (candidate.canExecute()) return candidate.getAbsolutePath();
                // Windows: try common extensions
                for (String ext : new String[]{".exe", ".cmd", ".bat"}) {
                    File candidateExt = new File(dir, binary + ext);
                    if (candidateExt.canExecute()) return candidateExt.getAbsolutePath();
                }
            }
        }
        return null;
    }

    private List<String> buildCommand(String binary) {
        List<String> cmd = new ArrayList<>();
        cmd.add(binary);

        String name = agent.toLowerCase();

        if (skipPermissions) {
            if (name.contains("claude")) {
                cmd.add("--dangerously-skip-permissions");
            } else if (name.contains("codex")) {
                cmd.add("--dangerously-bypass-approvals-and-sandbox");
            } else if (name.contains("qwen")) {
                cmd.add("--yolo");
            } else if (name.contains("gemini")) {
                cmd.add("--yolo");
            }
            // opencode: auto-approves in interactive TUI and in `run` mode
        }

        // Append system prompt args (Claude: --append-system-prompt-file, Qwen: --append-system-prompt)
        if (systemPromptManager != null) {
            cmd.addAll(systemPromptManager.getExtraArgs(agent));
        }

        return cmd;
    }

    // ── MCP URL resolution ──────────────────────────────────────────────────

    private String resolveMcpUrl() {
        return mcpUrlResolver.resolveMcpUrl(kompileUrl, mcpPort);
    }

    private static String formatNumber(long n) {
        if (n < 1000) return String.valueOf(n);
        return String.format("%,d", n);
    }
}
