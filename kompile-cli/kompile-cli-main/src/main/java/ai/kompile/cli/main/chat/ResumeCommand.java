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

import ai.kompile.cli.common.chat.sources.ChatSessionSummary;
import ai.kompile.cli.common.chat.sources.ChatSourceAdapter;
import ai.kompile.cli.common.chat.sources.ChatSourceRegistry;
import ai.kompile.cli.common.util.JsonUtils;
import ai.kompile.cli.main.chat.render.AsciiRenderer;
import ai.kompile.cli.main.chat.render.TerminalRenderer;
import ai.kompile.cli.main.chat.tools.ResumeTool;
import ai.kompile.cli.main.chat.tools.NativeResumeCoordinator;
import ai.kompile.cli.main.chat.tools.CrossAgentResumeCompactor;
import ai.kompile.cli.main.chat.format.ConversationExporter;
import picocli.CommandLine;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;

/**
 * Resume command - Interactive multi-tab TUI for browsing, searching, migrating,
 * and resuming conversations with different agents.
 * <p>
 * This command launches the Resume Tool which provides:
 * <ul>
 *   <li>Tabbed interface grouped by agent (claude, codex, qwen, opencode, etc.)</li>
 *   <li>Search and filter conversations by keyword, agent, or source</li>
 *   <li>View conversation transcripts</li>
 *   <li>Migrate conversations between formats (kompile, openai, anthropic, markdown, jsonl)</li>
 *   <li>Resume conversations with a designated agent via passthrough mode</li>
 * </ul>
 * <p>
 * The resume feature allows you to:
 * 1. Browse conversations from any agent in a multi-tab TUI
 * 2. Select a conversation to migrate/resume
 * 3. Choose a target agent for resumption
 * 4. Launch the agent with the conversation context injected
 * <p>
 * Examples:
 *   kompile resume                                          # Launch interactive TUI
 *   kompile resume --search "database"                      # Search for conversations
 *   kompile resume --session-id abc123                      # Resume specific conversation
 *   kompile resume --session-id abc123 --agent claude       # Resume with specific agent
 *   kompile resume --session-id abc123 -t target-uuid-here  # Resume with a specific target session UUID
 */
@CommandLine.Command(
        name = "resume",
        description = "Interactive multi-tab tool for browsing, searching, migrating, and resuming conversations",
        mixinStandardHelpOptions = true
)
public class ResumeCommand implements Callable<Integer> {

    @CommandLine.Option(names = {"--session-id", "-s"}, description =
            "Resume a specific conversation by transcript UUID or legacy session ID")
    private String sessionId;

    @CommandLine.Option(names = {"--target-session-id", "-t"}, description = "UUID to use as the target session ID when resuming (instead of generating a new one)")
    private String targetSessionId;

    @CommandLine.Option(names = {"--agent", "-a"}, description = "Target agent for resume (auto/kompile/claude/codex/qwen/opencode/gemini/pi)", defaultValue = "auto")
    private String agent;

    @CommandLine.Option(names = {"--search", "-q"}, description = "Search conversations by keyword")
    private String searchQuery;

    @CommandLine.Option(names = {"--filter-agent"}, description = "Filter conversations by agent name")
    private String filterAgent;

    @CommandLine.Option(names = {"--filter-source"}, description = "Filter conversations by source (kompile, claude-code, opencode, pi, etc.)")
    private String filterSource;

    @CommandLine.Option(names = {"--migrate", "-m"}, description = "Migrate conversation to format (kompile/openai/anthropic/markdown/jsonl)")
    private String migrateFormat;

    @CommandLine.Option(names = {"--view", "-v"}, description = "View conversation transcript and exit", defaultValue = "false")
    private boolean viewOnly;

    @CommandLine.Option(names = {"--list", "-l"}, description = "List conversations for the current directory and exit", defaultValue = "false")
    private boolean listOnly;

    @CommandLine.Option(names = {"--inject-tools"}, description = "Inject kompile tools (RAG, Graph RAG, etc.) into the agent via MCP", defaultValue = "true")
    private boolean injectTools;

    @CommandLine.Option(names = {"--url", "-u"}, description = "Kompile-app base URL for MCP tools", defaultValue = "")
    private String kompileUrl;

    @CommandLine.Option(names = {"--mcp-port"}, description = "Port for embedded MCP server (0 = auto-detect kompile-app)", defaultValue = "0")
    private int mcpPort;

    // Cached resolved MCP URL (to avoid double-probing)
    private McpUrlResolver mcpUrlResolver = new McpUrlResolver();

    // ANSI color codes
    private static final String RESET = "\033[0m";
    private static final String GREEN = "\033[32m";
    private static final String YELLOW = "\033[33m";
    private static final String DIM = "\033[2m";

    @Override
    public Integer call() {
        try {
            // Handle --list: just list conversations
            if (listOnly) {
                return listConversations();
            }

            // Handle --view: view a specific conversation
            if (viewOnly) {
                if (sessionId == null || sessionId.isBlank()) {
                    System.err.println("Error: --session-id is required with --view");
                    return 1;
                }
                return viewConversation(sessionId);
            }

            // Handle --migrate: migrate a conversation
            if (migrateFormat != null && !migrateFormat.isBlank()) {
                if (sessionId == null || sessionId.isBlank()) {
                    System.err.println("Error: --session-id is required with --migrate");
                    return 1;
                }
                return migrateConversation(sessionId, migrateFormat);
            }

            // Handle --search: search and show results
            if (searchQuery != null && !searchQuery.isBlank()) {
                return searchConversations(searchQuery, filterAgent, filterSource);
            }

            // Handle --session-id with --agent: direct resume
            if (sessionId != null && !sessionId.isBlank()) {
                return resumeConversation(sessionId, agent);
            }

            // Otherwise, launch the full interactive TUI
            return runInteractiveTui();

        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            e.printStackTrace();
            return 1;
        }
    }

    /**
     * List all conversations to stdout with optional --filter-agent / --filter-source.
     */
    private int listConversations() {
        try {
            List<ChatHistory.ConversationSummary> conversations =
                    ChatHistory.listResumableConversations(
                            Path.of(System.getProperty("user.dir")));
            List<ChatSessionSummary> piConversations = listPiConversations();
            if (conversations.isEmpty() && piConversations.isEmpty()) {
                System.out.println("No saved conversations found.");
                return 0;
            }

            System.out.println("Saved conversations:");
            System.out.println();
            int printed = 0;
            for (ChatHistory.ConversationSummary c : conversations) {
                String displayAgent = displayAgentForKompileConversation(c);
                if (filterAgent != null && !filterAgent.isBlank()
                        && !filterAgent.equalsIgnoreCase(displayAgent)) {
                    continue;
                }
                if (filterSource != null && !filterSource.isBlank()
                        && !"kompile".equalsIgnoreCase(filterSource)) {
                    continue;
                }
                System.out.printf("  %-36s  %-20s  agent=%-8s  %s%n",
                        c.sessionId(), c.started(), displayAgent,
                        c.title().isEmpty() ? "(empty)" : c.title());
                printed++;
            }
            for (ChatSessionSummary c : piConversations) {
                if (filterAgent != null && !filterAgent.isBlank()
                        && !"pi".equalsIgnoreCase(filterAgent)
                        && !"pi-cli".equalsIgnoreCase(filterAgent)) {
                    continue;
                }
                String title = c.title() == null || c.title().isBlank() ? "(untitled)" : c.title();
                System.out.printf("  %-36s  %-20s  agent=%-8s  %s (%d messages)%n",
                        c.sessionId(), String.valueOf(c.lastModifiedMillis()), "pi", title, c.messageCount());
                printed++;
            }
            if (printed == 0) {
                System.out.println("No conversations matched the requested filters.");
            }
            System.out.println();
            System.out.println("Resume with: kompile resume --session-id <transcript-uuid-or-id>");
            return 0;
        } catch (Exception e) {
            System.err.println("Error listing conversations: " + e.getMessage());
            return 1;
        }
    }

    private List<ChatSessionSummary> listPiConversations() {
        if (filterSource != null && !filterSource.isBlank()
                && !"pi".equalsIgnoreCase(filterSource)
                && !"pi-cli".equalsIgnoreCase(filterSource)) {
            return List.of();
        }
        try {
            ChatSourceAdapter adapter = ChatSourceRegistry.getInstance().find("pi").orElse(null);
            if (adapter == null) {
                return List.of();
            }
            Path currentDirectory = Path.of(System.getProperty("user.dir"))
                    .toAbsolutePath().normalize();
            return adapter.list(currentDirectory).stream()
                    .filter(summary -> workingDirectoryMatches(
                            summary.workingDirectory(), currentDirectory))
                    .toList();
        } catch (IOException e) {
            System.err.println("Warning: Could not list Pi sessions: " + e.getMessage());
            return List.of();
        }
    }

    private static boolean workingDirectoryMatches(String recordedDirectory, Path expected) {
        if (recordedDirectory == null || recordedDirectory.isBlank()) {
            return false;
        }
        try {
            Path recorded = Path.of(recordedDirectory);
            return recorded.isAbsolute() && expected.equals(recorded.normalize());
        } catch (RuntimeException invalidPath) {
            return false;
        }
    }

    /**
     * View a specific conversation transcript.
     */
    private int viewConversation(String sessionId) {
        try {
            Path transcriptPath = Paths.get(
                    System.getProperty("user.home"), ".kompile", "conversations", sessionId + ".txt");
            ai.kompile.cli.main.chat.format.ConversationReader reader =
                    new ai.kompile.cli.main.chat.format.ConversationReader();

            if (!Files.exists(transcriptPath)) {
                for (String source : List.of("pi", "claude-code", "codex", "qwen", "opencode", "gemini")) {
                    try {
                        List<ChatHistory.Turn> turns = reader.readExternalSession(source, sessionId);
                        if (turns == null || turns.isEmpty()) continue;
                        printRenderedConversation(sessionId, "source=" + source, turns);
                        return 0;
                    } catch (Exception ignored) {
                        // Try the next registered external source.
                    }
                }
                System.err.println("Conversation not found: " + sessionId);
                return 1;
            }

            // Parse the persisted format before displaying it. Printing the file directly
            // exposes storage markers/role prefixes as literal text instead of rendering
            // markdown and presenting each turn consistently with live chat.
            List<ChatHistory.Turn> turns = reader.readKompileSession(sessionId);
            if (turns == null || turns.isEmpty()) {
                System.err.println("Conversation is empty: " + sessionId);
                return 1;
            }
            printRenderedConversation(sessionId, "kompile", turns);
            return 0;
        } catch (Exception e) {
            System.err.println("Error viewing conversation: " + e.getMessage());
            return 1;
        }
    }

    private void printRenderedConversation(
            String sessionId, String source, List<ChatHistory.Turn> turns) {
        TerminalRenderer renderer = new TerminalRenderer();
        AsciiRenderer ascii = new AsciiRenderer(renderer);
        System.out.println("Conversation: " + sessionId + " (" + source + ")");
        for (ChatHistory.Turn turn : turns) {
            String role = turn.role() == null
                    ? "assistant"
                    : turn.role().trim().toLowerCase(Locale.ROOT);
            String label = switch (role) {
                case "user" -> renderer.cyan("You:");
                case "assistant" -> renderer.green("Assistant:");
                case "system" -> renderer.dim("System:");
                case "tool", "tool_result" -> renderer.yellow("Tool:");
                default -> renderer.dim(role + ":");
            };
            System.out.println(label);
            String content = turn.content();
            if (content != null && !content.isBlank()) {
                for (String line : ascii.renderMarkdown(content).split("\\n", -1)) {
                    System.out.println("  " + line);
                }
            }
            System.out.println();
        }
    }

    /**
     * Migrate a conversation to a different format.
     */
    private int migrateConversation(String sessionId, String format) {
        try {
            ai.kompile.cli.main.chat.format.ConversationReader reader = 
                    new ai.kompile.cli.main.chat.format.ConversationReader();
            
            List<ChatHistory.Turn> turns = 
                    reader.readKompileSession(sessionId);
            
            String migrated = ai.kompile.cli.main.chat.format.ConversationFormatter.format(turns, format);
            
            Path outputPath = Paths.get(
                    System.getProperty("user.home"), ".kompile", "conversations",
                    sessionId + "-migrated." + format);
            
            Files.writeString(outputPath, migrated);
            
            System.out.println("✓ Conversation migrated to " + format + " format");
            System.out.println("  Saved to: " + outputPath);
            return 0;
        } catch (Exception e) {
            System.err.println("Error migrating conversation: " + e.getMessage());
            return 1;
        }
    }

    /**
     * Search conversations by keyword.
     */
    private int searchConversations(String query, String filterAgent, String filterSource) {
        try {
            ResumeTool tool = new ResumeTool();
            ObjectMapper om = JsonUtils.standardMapper();
            ObjectNode params = om.createObjectNode();
            params.put("action", "search");
            params.put("query", query);
            if (filterAgent != null && !filterAgent.isBlank()) {
                params.put("agent", filterAgent);
            }
            if (filterSource != null && !filterSource.isBlank()) {
                params.put("source", filterSource);
            }
            
            ai.kompile.cli.main.chat.tools.ToolResult result = tool.execute(params, null);
            
            if (result.isError()) {
                System.err.println("Search error: " + result.getOutput());
                return 1;
            }
            
            System.out.println(result.getOutput());
            return 0;
        } catch (Exception e) {
            System.err.println("Error searching conversations: " + e.getMessage());
            return 1;
        }
    }

    static String displayAgentForKompileConversation(
            ChatHistory.ConversationSummary conversation) {
        String nativeSessionId = ChatHistory.resolveNativeSessionId(
                conversation.sessionId(), conversation.agent());
        return ResumeTool.isStandardKompileChatSession(
                conversation.sessionId(), "kompile", conversation.agent(), nativeSessionId)
                ? "kompile"
                : conversation.agent();
    }

    static boolean shouldResumeStandardChat(
            String sessionId,
            String requestedAgent,
            String recordedAgent,
            String nativeSessionId) {
        boolean standardTarget = requestedAgent == null
                || requestedAgent.isBlank()
                || "auto".equalsIgnoreCase(requestedAgent)
                || "kompile".equalsIgnoreCase(requestedAgent);
        return standardTarget && ResumeTool.isStandardKompileChatSession(
                sessionId, "kompile", recordedAgent, nativeSessionId);
    }

    static boolean shouldResumeStandardChat(
            ChatHistory.ConversationSummary kompileSummary,
            String requestedAgent,
            String nativeSessionId) {
        return kompileSummary != null && shouldResumeStandardChat(
                kompileSummary.sessionId(), requestedAgent, kompileSummary.agent(), nativeSessionId);
    }

    static String effectiveTargetAgent(String requestedAgent) {
        return requestedAgent == null
                || requestedAgent.isBlank()
                || "auto".equalsIgnoreCase(requestedAgent)
                ? "claude"
                : requestedAgent;
    }

    /**
     * Resume a conversation with a specific agent.
     */
    private int resumeConversation(String sessionId, String agent) {
        try {
            ChatHistory.ConversationSummary kompileSummary =
                    ChatHistory.listResumableConversations().stream()
                            .filter(candidate -> candidate.sessionId().equals(sessionId))
                            .findFirst()
                            .orElse(null);
            String recordedAgent = kompileSummary == null ? "" : kompileSummary.agent();
            String nativeSessionId = ChatHistory.resolveNativeSessionId(sessionId, recordedAgent);
            Path transcriptWorkingDirectory = ChatHistory.resolveWorkingDirectory(sessionId)
                    .orElse(Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize());
            if (shouldResumeStandardChat(kompileSummary, agent, nativeSessionId)) {
                System.out.println("Resuming Kompile standard chat: " + sessionId);
                return new CommandLine(new ChatCommand()).execute(
                        "--resume", sessionId, "--mode", "standard",
                        "--working-dir", transcriptWorkingDirectory.toString());
            }
            boolean automaticTarget = agent == null || agent.isBlank() || "auto".equalsIgnoreCase(agent);
            agent = effectiveTargetAgent(agent);
            if (automaticTarget && NativeResumeCoordinator.FIRST_PARTY_AGENTS.contains(
                    NativeResumeCoordinator.normalizeAgent(recordedAgent))) {
                // Provider-backed Kompile wrappers should return to their original vendor;
                // falling back to Claude here loses the native session identity.
                agent = NativeResumeCoordinator.normalizeAgent(recordedAgent);
            }

            ai.kompile.cli.main.chat.format.ConversationReader reader =
                    new ai.kompile.cli.main.chat.format.ConversationReader();

            List<ChatHistory.Turn> turns = null;
            String source = "kompile";
            Path workingDirectory = transcriptWorkingDirectory;

            // Explicit IDs remain globally addressable, but resume them in the project where
            // the transcript was actually recorded rather than the caller's current directory.
            boolean kompileSessionExists = ai.kompile.cli.main.chat.ChatHistory.exists(sessionId);
            if (kompileSessionExists) {
                workingDirectory = ChatHistory.resolveWorkingDirectory(sessionId)
                        .orElse(workingDirectory);
            }

            // A caller-supplied OpenCode target already exists natively and needs no export,
            // but it must still launch from the source transcript's recorded project.
            if ("opencode".equalsIgnoreCase(agent)
                    && targetSessionId != null && !targetSessionId.isBlank()) {
                try {
                    Path targetWorkingDirectory =
                            ai.kompile.cli.main.chat.format.ConversationReader
                                    .resolveExternalWorkingDirectory("opencode", targetSessionId);
                    if (targetWorkingDirectory != null) {
                        workingDirectory = targetWorkingDirectory;
                    }
                } catch (Exception ignored) {
                    // A new caller-supplied UUID has no native metadata yet; use source CWD.
                }
                return resumeOpenCodeDirect(targetSessionId, workingDirectory);
            }

            try {
                turns = reader.readKompileSession(sessionId);
            } catch (Exception kompileError) {
                // If the kompile file exists but couldn't be parsed or is empty,
                // don't fall through to external sources (produces confusing errors)
                if (kompileSessionExists) {
                    throw new IOException("Session transcript is empty (transcript harvest may have failed): " + sessionId, kompileError);
                }

                Exception lastExternalError = null;
                for (String externalSource : List.of("claude-code", "codex", "qwen", "opencode", "gemini", "pi")) {
                    try {
                        List<ChatHistory.Turn> externalTurns =
                                reader.readExternalSession(externalSource, sessionId);
                        Path externalWorkingDirectory =
                                ai.kompile.cli.main.chat.format.ConversationReader
                                        .resolveExternalWorkingDirectory(externalSource, sessionId);
                        turns = externalTurns;
                        source = externalSource;
                        workingDirectory = externalWorkingDirectory;
                        break;
                    } catch (Exception externalError) {
                        lastExternalError = externalError;
                    }
                }
                if (turns == null) {
                    if (lastExternalError != null) {
                        lastExternalError.addSuppressed(kompileError);
                        throw lastExternalError;
                    }
                    throw new IOException("No transcript found for session: " + sessionId, kompileError);
                }
            }

            if (turns == null || turns.isEmpty()) {
                System.err.println("No transcript found for session: " + sessionId);
                return 1;
            }

            String originalAgent = NativeResumeCoordinator.normalizeAgent(
                    "kompile".equalsIgnoreCase(source) ? recordedAgent : source);
            boolean sameFirstPartyVendor = NativeResumeCoordinator.FIRST_PARTY_AGENTS.contains(agent)
                    && agent.equals(originalAgent)
                    && targetSessionId == null
                    && ChatHistory.exists(sessionId);
            if (sameFirstPartyVendor) {
                NativeResumeCoordinator.Result ensured = NativeResumeCoordinator.ensure(
                        sessionId, agent, nativeSessionId, turns, source, workingDirectory);
                if (ensured.recreated()) {
                    System.out.println("✓ Recreated missing native " + ensured.agent()
                            + " session: " + ensured.nativeSessionId());
                }
                return resumeNativeDirect(
                        ensured.agent(), ensured.nativeSessionId(), ensured.launchToken(),
                        ensured.workingDirectory());
            }

            // Short-circuit: opencode→opencode needs no export/import.
            // The session already lives in OpenCode's DB/storage; re-importing
            // would create a duplicate with a mangled session ID.
            if ("opencode".equalsIgnoreCase(agent) && "opencode".equalsIgnoreCase(source)) {
                String nativeId = (targetSessionId != null && !targetSessionId.isBlank())
                        ? targetSessionId
                        : sessionId;
                return resumeOpenCodeDirect(nativeId, workingDirectory);
            }
            if ("pi".equalsIgnoreCase(agent) && "pi".equalsIgnoreCase(source)) {
                String nativeId = (targetSessionId != null && !targetSessionId.isBlank())
                        ? targetSessionId
                        : sessionId;
                return resumePiDirect(nativeId, workingDirectory);
            }

            System.out.println("✓ Resuming conversation: " + sessionId);
            System.out.println("  Target agent: " + agent);
            System.out.println("  Source: " + source);
            System.out.println("  Messages: " + turns.size());
            System.out.println();

            // "kompile" target: launch managed TUI with the original source agent
            if ("kompile".equalsIgnoreCase(agent)) {
                return resumeWithKompileManagedUi(sessionId, source, workingDirectory);
            }

            // Export to agent's native format
            if (targetSessionId != null && !targetSessionId.isBlank()) {
                System.out.println("Exporting to " + agent + " native format with session " + targetSessionId + "...");
            } else {
                System.out.println("Exporting to " + agent + " native format...");
            }
            CrossAgentResumeCompactor.Result compaction =
                    compactForTargetAgent(turns, agent, source, workingDirectory);

            ai.kompile.cli.main.chat.format.ConversationExporter.ExportResult exportResult =
                    ai.kompile.cli.main.chat.format.ConversationExporter.exportToAgent(
                            compaction.turns(), agent, targetSessionId, source, workingDirectory);

            System.out.println();
            System.out.println("✓ Exported to " + agent + " native format");
            System.out.println("  Session ID: " + exportResult.getSessionId());
            System.out.println("  Saved to: " + exportResult.getSessionPath());
            System.out.println();

            // Inject MCP tools FIRST so .mcp.json exists before building the agent command
            Path injectedSettingsFile = null;
            if (injectTools) {
                Path agentWorkingDir = exportResult.getWorkingDirectory() != null
                        ? exportResult.getWorkingDirectory()
                        : Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
                // Pre-configure Claude Code hooks BEFORE injection/launch
                if (agent.toLowerCase(Locale.ROOT).contains("claude")) {
                    ai.kompile.cli.main.chat.mcp.McpToolInjection.ensureHooksPreConfigured(agentWorkingDir);
                }
                try {
                    String sseUrl = resolveMcpUrl();
                    injectedSettingsFile = ai.kompile.cli.main.chat.mcp.McpToolInjection.injectTools(
                            agentWorkingDir, agent, sseUrl);
                    if (injectedSettingsFile != null) {
                        String mode = (sseUrl != null && !sseUrl.isBlank()) ? "sse" : "stdio";
                        System.out.println(GREEN + "Kompile tools injected (" + mode + ")" + RESET
                                + DIM + " (" + injectedSettingsFile + ")" + RESET);
                    }
                } catch (IOException e) {
                    System.err.println(YELLOW + "Warning: Could not inject MCP tools: " + e.getMessage() + RESET);
                }
            }

            // Build the agent command AFTER injection so --mcp-config can reference the written file
            List<String> agentCommand = buildAgentCommand(agent, exportResult, injectedSettingsFile != null);

            System.out.println();
            System.out.println("Launching agent with native session resume...");
            System.out.println(DIM + "  Command: " + String.join(" ", agentCommand) + RESET);
            System.out.println(DIM + "  Working dir: " + exportResult.getWorkingDirectory() + RESET);
            System.out.println(DIM + "  MCP injected: " + injectedSettingsFile + RESET);
            System.out.println();

            int exitCode;
            try {
                ProcessBuilder pb = new ProcessBuilder(agentCommand);
                if (exportResult.getWorkingDirectory() != null) {
                    pb.directory(exportResult.getWorkingDirectory().toFile());
                }
                // Set MCP_TIMEOUT to give the kompile MCP server time to connect
                // before Claude fires the first API request. Default is 30s which
                // races with slow tool init; 60s provides headroom.
                // See: https://github.com/anthropics/claude-code/issues/36060
                pb.environment().putIfAbsent("MCP_TIMEOUT", "60000");
                pb.inheritIO();
                Process process = pb.start();
                try {
                    exitCode = process.waitFor();
                } catch (InterruptedException ie) {
                    process.destroy();
                    try { process.waitFor(5, TimeUnit.SECONDS); } catch (InterruptedException ignored) {}
                    if (process.isAlive()) process.destroyForcibly();
                    exitCode = 130;
                    Thread.currentThread().interrupt();
                }
            } finally {
                // Restore original settings to prevent pollution
                ai.kompile.cli.main.chat.mcp.McpToolInjection.removeTools(injectedSettingsFile);
            }

            System.out.println();
            if (exitCode == 130) {
                System.out.println("Agent interrupted.");
            } else {
                System.out.println("✓ Agent session completed (exit code: " + exitCode + ")");
            }
            return exitCode;
        } catch (Exception e) {
            System.err.println("Error resuming conversation: " + e.getMessage());
            e.printStackTrace();
            return 1;
        }
    }

    /**
     * Launch a verified native session without exporting it a second time.
     */
    private int resumeNativeDirect(String agent, String nativeSessionId,
                                   String launchToken, Path sessionWorkingDirectory) {
        Path workingDir = sessionWorkingDirectory != null
                ? sessionWorkingDirectory.toAbsolutePath().normalize()
                : Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        String normalizedAgent = NativeResumeCoordinator.normalizeAgent(agent);
        String resumeCommand = switch (normalizedAgent) {
            case "claude" -> "claude --resume " + launchToken;
            case "codex" -> "codex resume " + launchToken;
            case "qwen" -> "qwen --resume " + launchToken;
            case "opencode" -> "opencode --session " + launchToken;
            case "gemini" -> "gemini --resume " + launchToken;
            case "pi" -> "pi --session " + launchToken;
            default -> throw new IllegalArgumentException("Unsupported native resume agent: " + agent);
        };

        Path injectedSettingsFile = null;
        try {
            if (injectTools) {
                try {
                    String sseUrl = resolveMcpUrl();
                    injectedSettingsFile = ai.kompile.cli.main.chat.mcp.McpToolInjection.injectTools(
                            workingDir, normalizedAgent, sseUrl);
                } catch (IOException e) {
                    System.err.println(YELLOW + "Warning: Could not inject MCP tools: "
                            + e.getMessage() + RESET);
                }
            }

            ConversationExporter.ExportResult nativeResult = new ConversationExporter.ExportResult(
                    nativeSessionId, normalizedAgent, null, resumeCommand, workingDir);
            List<String> command = buildAgentCommand(
                    normalizedAgent, nativeResult, injectedSettingsFile != null);
            System.out.println("Resuming " + normalizedAgent + " native session directly: " + nativeSessionId);
            System.out.println(DIM + "  Command: " + String.join(" ", command) + RESET);
            System.out.println(DIM + "  Working dir: " + workingDir + RESET);

            ProcessBuilder pb = new ProcessBuilder(command);
            pb.directory(workingDir.toFile());
            pb.environment().putIfAbsent("MCP_TIMEOUT", "60000");
            pb.inheritIO();
            Process process = pb.start();
            try {
                return process.waitFor();
            } catch (InterruptedException e) {
                process.destroy();
                if (process.isAlive()) process.destroyForcibly();
                Thread.currentThread().interrupt();
                return 130;
            }
        } catch (Exception e) {
            System.err.println("Error resuming " + normalizedAgent + " session: " + e.getMessage());
            return 1;
        } finally {
            ai.kompile.cli.main.chat.mcp.McpToolInjection.removeTools(injectedSettingsFile);
        }
    }

    /**
     * Directly resume an existing Pi session by UUID without exporting. Pi's
     * {@code --resume} flag opens a picker; deterministic resume uses
     * {@code --session <id>} instead.
     */
    private int resumePiDirect(String piSessionId, Path sessionWorkingDirectory) {
        Path workingDir = sessionWorkingDirectory != null
                ? sessionWorkingDirectory.toAbsolutePath().normalize()
                : Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        Path injectedSettingsFile = null;
        try {
            if (injectTools) {
                try {
                    String sseUrl = resolveMcpUrl();
                    injectedSettingsFile = ai.kompile.cli.main.chat.mcp.McpToolInjection.injectTools(
                            workingDir, "pi", sseUrl);
                    if (injectedSettingsFile != null) {
                        System.out.println(GREEN + "Kompile tools injected" + RESET
                                + DIM + " (" + injectedSettingsFile + ")" + RESET);
                    }
                } catch (IOException e) {
                    System.err.println(YELLOW + "Warning: Could not inject MCP tools: " + e.getMessage() + RESET);
                }
            }

            List<String> cmd = new ArrayList<>();
            cmd.add("pi");
            if (injectTools) {
                cmd.addAll(ai.kompile.cli.main.chat.mcp.McpToolInjection.commandLineOverrides(
                        workingDir, "pi"));
            }
            cmd.add("--session");
            cmd.add(piSessionId);

            System.out.println();
            System.out.println("Launching Pi with session resume...");
            System.out.println(DIM + "  Command: " + String.join(" ", cmd) + RESET);
            System.out.println(DIM + "  Working dir: " + workingDir + RESET);
            System.out.println();

            int exitCode;
            try {
                ProcessBuilder pb = new ProcessBuilder(cmd);
                pb.directory(workingDir.toFile());
                pb.environment().putIfAbsent("MCP_TIMEOUT", "60000");
                pb.inheritIO();
                Process process = pb.start();
                try {
                    exitCode = process.waitFor();
                } catch (InterruptedException ie) {
                    process.destroy();
                    try { process.waitFor(5, TimeUnit.SECONDS); } catch (InterruptedException ignored) {}
                    if (process.isAlive()) process.destroyForcibly();
                    exitCode = 130;
                    Thread.currentThread().interrupt();
                }
            } finally {
                ai.kompile.cli.main.chat.mcp.McpToolInjection.removeTools(injectedSettingsFile);
            }
            return exitCode;
        } catch (Exception e) {
            System.err.println("Error resuming Pi session: " + e.getMessage());
            return 1;
        }
    }

    CrossAgentResumeCompactor.Result compactForTargetAgent(List<ChatHistory.Turn> turns,
                                                           String targetAgent,
                                                           String sourceAgent,
                                                           Path workingDirectory) {
        CrossAgentResumeCompactor.TargetBudget targetBudget =
                CrossAgentResumeCompactor.targetBudget(targetAgent, sourceAgent, workingDirectory);
        CrossAgentResumeCompactor.Result compaction =
                CrossAgentResumeCompactor.compactToFit(turns, targetBudget);
        if (compaction.compacted()) {
            System.out.println(YELLOW + "Compacted transcript for target context: "
                    + compaction.tokensBefore() + " → " + compaction.tokensAfter()
                    + " tokens (" + compaction.summarizedTurns() + " older turns, "
                    + compaction.stages() + " stage" + (compaction.stages() == 1 ? "" : "s")
                    + ", model " + targetBudget.modelId() + ")" + RESET);
        }
        return compaction;
    }

    /**
     * Directly resume an existing OpenCode session by UUID without exporting.
     * Uses {@code opencode --session <uuid>} to attach to the session.
     */
    private int resumeOpenCodeDirect(String opencodeSessionId, Path sessionWorkingDirectory) {
        try {
            Path workingDir = sessionWorkingDirectory == null
                    ? Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize()
                    : sessionWorkingDirectory.toAbsolutePath().normalize();

            System.out.println("Resuming OpenCode session directly: " + opencodeSessionId);

            // Inject MCP tools
            Path injectedSettingsFile = null;
            if (injectTools) {
                try {
                    String sseUrl = resolveMcpUrl();
                    injectedSettingsFile = ai.kompile.cli.main.chat.mcp.McpToolInjection.injectTools(
                            workingDir, "opencode", sseUrl);
                    if (injectedSettingsFile != null) {
                        String mode = (sseUrl != null && !sseUrl.isBlank()) ? "sse" : "stdio";
                        System.out.println(GREEN + "Kompile tools injected (" + mode + ")" + RESET
                                + DIM + " (" + injectedSettingsFile + ")" + RESET);
                    }
                } catch (IOException e) {
                    System.err.println(YELLOW + "Warning: Could not inject MCP tools: " + e.getMessage() + RESET);
                }
            }

            List<String> cmd = new ArrayList<>();
            cmd.add("opencode");
            cmd.add("--session");
            cmd.add(opencodeSessionId);

            System.out.println();
            System.out.println("Launching OpenCode with session resume...");
            System.out.println(DIM + "  Command: " + String.join(" ", cmd) + RESET);
            System.out.println();

            int exitCode;
            try {
                ProcessBuilder pb = new ProcessBuilder(cmd);
                pb.directory(workingDir.toFile());
                pb.environment().putIfAbsent("MCP_TIMEOUT", "60000");
                pb.inheritIO();
                Process process = pb.start();
                try {
                    exitCode = process.waitFor();
                } catch (InterruptedException ie) {
                    process.destroy();
                    try { process.waitFor(5, TimeUnit.SECONDS); } catch (InterruptedException ignored) {}
                    if (process.isAlive()) process.destroyForcibly();
                    exitCode = 130;
                    Thread.currentThread().interrupt();
                }
            } finally {
                ai.kompile.cli.main.chat.mcp.McpToolInjection.removeTools(injectedSettingsFile);
            }

            System.out.println();
            if (exitCode == 130) {
                System.out.println("Agent interrupted.");
            } else {
                System.out.println("Agent session completed (exit code: " + exitCode + ")");
            }
            return exitCode;
        } catch (Exception e) {
            System.err.println("Error resuming OpenCode session: " + e.getMessage());
            e.printStackTrace();
            return 1;
        }
    }

    /**
     * Launch the kompile managed TUI (EmulatedPassthroughCommand) with the original
     * source agent. No export needed — the conversation is replayed in the TUI
     * and the agent starts fresh so the user can continue.
     */
    private int resumeWithKompileManagedUi(String origSessionId, String sourceAgent, Path workingDirectory) {
        // Map source identifier to agent binary name
        String agentName = switch (sourceAgent.toLowerCase(Locale.ROOT)) {
            case "claude-code" -> "claude";
            case "codex", "qwen", "opencode", "gemini", "pi", "pi-cli" -> sourceAgent.toLowerCase(Locale.ROOT).equals("pi-cli") ? "pi" : sourceAgent.toLowerCase(Locale.ROOT);
            default -> "claude";
        };

        System.out.println("Launching kompile managed UI with " + agentName + " agent...");

        // Build args for EmulatedPassthroughCommand
        List<String> args = new ArrayList<>();
        args.add("--agent");
        args.add(agentName);
        args.add("--resume-session");
        args.add(origSessionId);
        if (workingDirectory != null) {
            args.add("--working-dir");
            args.add(workingDirectory.toString());
        }

        return new CommandLine(new EmulatedPassthroughCommand())
                .execute(args.toArray(new String[0]));
    }

    /**
     * Build the agent command for resume.
     * <p>
     * Tool injection is handled the same way as PassthroughCommand: we write
     * .mcp.json (or equivalent) to the working directory and let the agent
     * auto-discover it. We do NOT pass --mcp-config or --system-prompt flags
     * because those can conflict with --resume on some agents.
     */
    private List<String> buildAgentCommand(String agent,
                                           ai.kompile.cli.main.chat.format.ConversationExporter.ExportResult exportResult,
                                           boolean toolsInjected) {
        List<String> cmd = new ArrayList<>();
        String name = agent.toLowerCase();

        // Parse the resume command (e.g., "qwen --resume <sessionId>")
        String[] resumeParts = exportResult.getResumeCommand().split("\\s+");
        if (resumeParts.length == 0 || resumeParts[0].isEmpty()) {
            throw new IllegalArgumentException("Resume command is empty for agent: " + agent);
        }

        cmd.add(resumeParts[0]);
        if (toolsInjected && ai.kompile.cli.main.chat.mcp.PiMcpAdapterProvisioner.isPiAgent(agent)) {
            try {
                cmd.addAll(ai.kompile.cli.main.chat.mcp.McpToolInjection.commandLineOverrides(
                        exportResult.getWorkingDirectory() != null
                                ? exportResult.getWorkingDirectory() : Path.of(System.getProperty("user.dir")), agent));
            } catch (IOException e) {
                System.err.println("Warning: Could not provision Pi MCP adapter: " + e.getMessage());
            }
        }
        if (name.contains("codex") && exportResult.getWorkingDirectory() != null) {
            cmd.add("-C");
            cmd.add(exportResult.getWorkingDirectory().toString());
        }

        // Add permission bypass flags via centralized overrides.
        // Insert them before the resume subcommand so they are parsed as global
        // flags (e.g. `codex --dangerously-bypass-approvals-and-sandbox resume ...`).
        // OpenCode's TUI resume (`opencode --session <id>`) does not accept
        // `--dangerously-skip-permissions`; that flag is only valid for
        // `opencode run`. Skip it here so the TUI resume actually launches
        // instead of printing the help page and exiting with code 1.
        if (!name.contains("opencode")) {
            ai.kompile.cli.main.chat.agent.AgentFlagOverrides.addPermissionBypassFlags(
                    cmd, agent, true, exportResult.getWorkingDirectory());
        }

        for (int i = 1; i < resumeParts.length; i++) {
            String part = resumeParts[i];
            if (!part.isEmpty()) {
                cmd.add(part);
            }
        }

        // On resume, inject a system prompt directive to verify MCP tools are
        // connected before proceeding. Claude Code has a race condition where
        // the first API request fires before MCP servers finish connecting
        // (see https://github.com/anthropics/claude-code/issues/36060).
        // The --append-system-prompt tells the model to check tool availability,
        // which also gives the MCP server time to complete initialization.
        if (toolsInjected && name.contains("claude")) {
            cmd.add("--append-system-prompt");
            cmd.add("This is a resumed session with kompile MCP tools. "
                    + "If you need to use kompile tools (mcp__kompile__*) and they appear "
                    + "unavailable, run /mcp to check MCP server status and reconnect if needed. "
                    + "The kompile MCP server should be connected — if it shows as disconnected, "
                    + "wait a moment and retry /mcp.");
        }

        // MCP tools are auto-discovered from the provider config. Pi additionally
        // receives the bundled extension path immediately after its executable.

        return cmd;
    }

    /**
     * Run the full interactive TUI.
     */
    private int runInteractiveTui() {
        try {
            ResumeTool tool = new ResumeTool();
            ai.kompile.cli.main.chat.tools.ToolResult result = tool.runInteractiveBrowser();
            return result.isError() ? 1 : 0;
        } catch (Exception e) {
            System.err.println("Error in interactive TUI: " + e.getMessage());
            return 1;
        }
    }

    // ── MCP URL resolution ──────────────────────────────────────────────────

    private String resolveMcpUrl() {
        return mcpUrlResolver.resolveMcpUrl(kompileUrl, mcpPort);
    }

}
