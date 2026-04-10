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

    @CommandLine.Option(names = {"--url", "-u"}, description = "Kompile-app base URL for MCP tools", defaultValue = "")
    String kompileUrl;

    @CommandLine.Option(names = {"--mcp-port"}, description = "Port for embedded MCP server (0 = auto-detect kompile-app)", defaultValue = "0")
    int mcpPort;

    // Cached resolved MCP URL (to avoid double-probing)
    private String resolvedMcpUrl;
    private boolean mcpUrlResolved;

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
                mcpUrlResolved = false;
                resolvedMcpUrl = null;

                List<String> agentCommand = buildCommand(agentBinary);
                System.out.println(CYAN + "Starting " + agent + " session..." + RESET);
                if (injectTools) {
                    try {
                        Path settingsFile = ai.kompile.cli.main.chat.mcp.McpToolInjection.injectTools(Path.of(workingDir), agent);
                        if (settingsFile != null) {
                            System.out.println(GREEN + "Kompile tools injected" + RESET
                                    + DIM + " (" + settingsFile + ")" + RESET);
                        }
                    } catch (java.io.IOException e) {
                        System.err.println(YELLOW + "Warning: Could not inject MCP tools: " + e.getMessage() + RESET);
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

                    Process process = pb.start();
                    lastExitCode = process.waitFor();
                } catch (Exception e) {
                    System.err.println("Error running agent: " + e.getMessage());
                    lastExitCode = 1;
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
        }
    }

    /**
     * Locate and parse Claude Code's JSONL session log.
     * Claude stores conversations at ~/.claude/projects/{project-hash}/{session-id}.jsonl.
     */
    private void harvestClaudeTranscript(ChatHistory history, ChatSessionMetrics metrics, Instant sessionStart) {
        Path claudeProjectsDir = Path.of(System.getProperty("user.home"), ".claude", "projects");
        if (!Files.isDirectory(claudeProjectsDir)) return;

        // Claude encodes the working dir path as the project dir name (/ -> -)
        String absWorkDir = new File(workingDir).getAbsoluteFile().toPath().normalize().toString();
        Path projectDir = findClaudeProjectDir(claudeProjectsDir, absWorkDir);
        if (projectDir == null) return;

        // Find the most recently modified .jsonl created during our session
        File jsonlFile = findNewestJsonl(projectDir, sessionStart);
        if (jsonlFile == null) return;

        parseClaudeJsonl(jsonlFile, history, metrics);
    }

    /**
     * Find the Claude project directory by matching against the working dir path.
     */
    private Path findClaudeProjectDir(Path claudeProjectsDir, String absWorkDir) {
        // Try direct encoding first: /home/user/project -> -home-user-project
        String directName = absWorkDir.replace("/", "-").replace("\\", "-");
        Path direct = claudeProjectsDir.resolve(directName);
        if (Files.isDirectory(direct)) return direct;

        // Scan for a matching directory
        String normalized = absWorkDir.replace("\\", "/");
        File[] dirs = claudeProjectsDir.toFile().listFiles(File::isDirectory);
        if (dirs == null) return null;

        for (File dir : dirs) {
            // Decode: -home-user-project -> /home/user/project
            String decoded = "/" + dir.getName().substring(1).replace("-", "/");
            if (normalized.equals(decoded) || normalized.startsWith(decoded + "/")) {
                return dir.toPath();
            }
        }

        // Fallback: try replacing only path separators, keeping hyphens in dir names
        for (File dir : dirs) {
            String name = dir.getName();
            if (name.startsWith("-")) {
                // Build path from segments
                String withSlashes = name.replaceFirst("^-", "/");
                // This is ambiguous (hyphens in names vs path separators)
                // but check if our workdir starts with something close
                if (normalized.replace("/", "-").equals(name) ||
                        ("-" + normalized.substring(1).replace("/", "-")).equals(name)) {
                    return dir.toPath();
                }
            }
        }
        return null;
    }

    /**
     * Find the most recently modified .jsonl file that was updated during our session.
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
            // Must have been modified after our session started (5s tolerance)
            if (lastMod >= startMillis - 5000 && lastMod > newestTime) {
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
                                }
                                case "custom_tool_call" -> {
                                    String toolName = payload.path("name").asText("unknown");
                                    String status = payload.path("status").asText("");
                                    boolean isError = "error".equalsIgnoreCase(status);
                                    history.logToolCall(toolName, isError, 0);
                                    metrics.recordToolCall(toolName, isError, 0);
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
     * OpenCode session harvesting stub.
     * OpenCode doesn't appear to store structured session logs in a known location.
     * If a log format is discovered in the future, implement parsing here.
     */
    private void harvestOpenCodeTranscript(ChatHistory history, ChatSessionMetrics metrics, Instant sessionStart) {
        // OpenCode (~/.opencode/) currently stores only binaries and node_modules.
        // No known session log format. Metrics will show zeros.
        history.logSystem("OpenCode session - no structured logs available for harvesting.");
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
            if (lastMod >= startMillis - 5000 && lastMod > newestTime) {
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
                        } else {
                            // Regular tool use — log with input summary
                            String inputSummary = summarizeToolInput(toolName, toolInput);
                            history.logToolCall(toolName, false, 0);
                            if (!inputSummary.isEmpty()) {
                                history.logSystem("  " + toolName + ": " + inputSummary);
                            }
                            metrics.recordToolCall(toolName, false, 0);
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
                cmd.add("--full-auto");
            } else if (name.contains("qwen")) {
                cmd.add("--yolo");
            } else if (name.contains("gemini")) {
                cmd.add("--yolo");
            }
            // opencode: auto-approves in interactive TUI and in `run` mode
        }

        return cmd;
    }

    // ── MCP URL resolution ──────────────────────────────────────────────────

    private String resolveMcpUrl() {
        if (mcpUrlResolved) return resolvedMcpUrl;
        mcpUrlResolved = true;
        resolvedMcpUrl = doResolveMcpUrl();
        return resolvedMcpUrl;
    }

    private String doResolveMcpUrl() {
        if (kompileUrl != null && !kompileUrl.isEmpty()) {
            String url = kompileUrl.endsWith("/") ? kompileUrl.substring(0, kompileUrl.length() - 1) : kompileUrl;
            if (!url.contains("/mcp")) url = url + "/mcp/sse";
            return url;
        }

        if (mcpPort > 0) return "http://localhost:" + mcpPort + "/mcp/sse";

        int[] probePorts = {8080, 8443, 9090, 3000};
        for (int port : probePorts) {
            if (probeKompileApp(port)) {
                System.out.println(DIM + "Auto-detected kompile-app on port " + port + RESET);
                return "http://localhost:" + port + "/mcp/sse";
            }
        }
        return null;
    }

    private boolean probeKompileApp(int port) {
        try {
            java.net.http.HttpClient client = java.net.http.HttpClient.newBuilder()
                    .connectTimeout(java.time.Duration.ofMillis(500))
                    .build();
            java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
                    .uri(java.net.URI.create("http://localhost:" + port + "/mcp/status"))
                    .timeout(java.time.Duration.ofMillis(1000))
                    .GET()
                    .build();
            java.net.http.HttpResponse<String> response = client.send(request,
                    java.net.http.HttpResponse.BodyHandlers.ofString());
            return response.statusCode() == 200;
        } catch (Exception e) {
            return false;
        }
    }

    private String findKompileBinary() {
        try {
            String processCmd = ProcessHandle.current().info().command().orElse(null);
            if (processCmd == null) return null;
            if (!processCmd.contains("java")) return processCmd;
            String jarPath = getClass().getProtectionDomain()
                    .getCodeSource().getLocation().toURI().getPath();
            if (java.nio.file.Files.exists(java.nio.file.Path.of(jarPath))) return processCmd + " -jar " + jarPath;
        } catch (Exception e) { /* Fall through */ }
        return null;
    }

    private static String formatNumber(long n) {
        if (n < 1000) return String.valueOf(n);
        return String.format("%,d", n);
    }
}
