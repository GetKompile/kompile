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

import ai.kompile.cli.main.chat.tools.ResumeTool;
import picocli.CommandLine;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

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

    @CommandLine.Option(names = {"--session-id", "-s"}, description = "Resume a specific conversation by session ID")
    private String sessionId;

    @CommandLine.Option(names = {"--target-session-id", "-t"}, description = "UUID to use as the target session ID when resuming (instead of generating a new one)")
    private String targetSessionId;

    @CommandLine.Option(names = {"--agent", "-a"}, description = "Target agent for resume (claude/codex/qwen/opencode/gemini)", defaultValue = "claude")
    private String agent;

    @CommandLine.Option(names = {"--search", "-q"}, description = "Search conversations by keyword")
    private String searchQuery;

    @CommandLine.Option(names = {"--filter-agent"}, description = "Filter conversations by agent name")
    private String filterAgent;

    @CommandLine.Option(names = {"--filter-source"}, description = "Filter conversations by source (kompile, claude-code, opencode, etc.)")
    private String filterSource;

    @CommandLine.Option(names = {"--migrate", "-m"}, description = "Migrate conversation to format (kompile/openai/anthropic/markdown/jsonl)")
    private String migrateFormat;

    @CommandLine.Option(names = {"--view", "-v"}, description = "View conversation transcript and exit", defaultValue = "false")
    private boolean viewOnly;

    @CommandLine.Option(names = {"--list", "-l"}, description = "List all conversations and exit", defaultValue = "false")
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
            java.util.List<ChatHistory.ConversationSummary> conversations = ChatHistory.listConversations();
            if (conversations.isEmpty()) {
                System.out.println("No saved conversations found.");
                return 0;
            }

            System.out.println("Saved conversations:");
            System.out.println();
            for (ChatHistory.ConversationSummary c : conversations) {
                if (filterAgent != null && !filterAgent.isBlank()
                        && !filterAgent.equalsIgnoreCase(c.agent())) {
                    continue;
                }
                System.out.printf("  %-24s  %-20s  agent=%-8s  %s%n",
                        c.sessionId(), c.started(), c.agent(),
                        c.title().isEmpty() ? "(empty)" : c.title());
            }
            System.out.println();
            System.out.println("Resume with: kompile resume --session-id <session-id>");
            return 0;
        } catch (Exception e) {
            System.err.println("Error listing conversations: " + e.getMessage());
            return 1;
        }
    }

    /**
     * View a specific conversation transcript.
     */
    private int viewConversation(String sessionId) {
        try {
            java.nio.file.Path transcriptPath = java.nio.file.Paths.get(
                    System.getProperty("user.home"), ".kompile", "conversations", sessionId + ".txt");
            
            if (!java.nio.file.Files.exists(transcriptPath)) {
                System.err.println("Conversation not found: " + sessionId);
                return 1;
            }

            String transcript = java.nio.file.Files.readString(transcriptPath);
            System.out.println(transcript);
            return 0;
        } catch (Exception e) {
            System.err.println("Error viewing conversation: " + e.getMessage());
            return 1;
        }
    }

    /**
     * Migrate a conversation to a different format.
     */
    private int migrateConversation(String sessionId, String format) {
        try {
            ai.kompile.cli.main.chat.format.ConversationReader reader = 
                    new ai.kompile.cli.main.chat.format.ConversationReader();
            
            java.util.List<ai.kompile.cli.main.chat.ChatHistory.Turn> turns = 
                    reader.readKompileSession(sessionId);
            
            String migrated = ai.kompile.cli.main.chat.format.ConversationFormatter.format(turns, format);
            
            java.nio.file.Path outputPath = java.nio.file.Paths.get(
                    System.getProperty("user.home"), ".kompile", "conversations",
                    sessionId + "-migrated." + format);
            
            java.nio.file.Files.writeString(outputPath, migrated);
            
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
            com.fasterxml.jackson.databind.ObjectMapper om = new com.fasterxml.jackson.databind.ObjectMapper();
            com.fasterxml.jackson.databind.node.ObjectNode params = om.createObjectNode();
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

    /**
     * Resume a conversation with a specific agent.
     */
    private int resumeConversation(String sessionId, String agent) {
        try {
            // Short-circuit: if the target agent is opencode and a target session UUID
            // is specified directly, just launch `opencode -s <uuid>` without exporting.
            if ("opencode".equalsIgnoreCase(agent) && targetSessionId != null && !targetSessionId.isBlank()) {
                return resumeOpenCodeDirect(targetSessionId);
            }

            ai.kompile.cli.main.chat.format.ConversationReader reader =
                    new ai.kompile.cli.main.chat.format.ConversationReader();

            java.util.List<ai.kompile.cli.main.chat.ChatHistory.Turn> turns = null;
            String source = "kompile";
            java.nio.file.Path workingDirectory = java.nio.file.Path.of(System.getProperty("user.dir"))
                    .toAbsolutePath()
                    .normalize();

            // Check if this is a known kompile session first
            boolean kompileSessionExists = ai.kompile.cli.main.chat.ChatHistory.exists(sessionId);

            try {
                turns = reader.readKompileSession(sessionId);
            } catch (Exception kompileError) {
                // If the kompile file exists but couldn't be parsed or is empty,
                // don't fall through to external sources (produces confusing errors)
                if (kompileSessionExists) {
                    throw new IOException("Session transcript is empty (transcript harvest may have failed): " + sessionId, kompileError);
                }

                Exception lastExternalError = null;
                for (String externalSource : java.util.List.of("claude-code", "codex", "qwen", "opencode", "gemini")) {
                    try {
                        java.util.List<ai.kompile.cli.main.chat.ChatHistory.Turn> externalTurns =
                                reader.readExternalSession(externalSource, sessionId);
                        java.nio.file.Path externalWorkingDirectory =
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

            // Short-circuit: opencode→opencode needs no export/import.
            // The session already lives in OpenCode's DB/storage; re-importing
            // would create a duplicate with a mangled session ID.
            if ("opencode".equalsIgnoreCase(agent) && "opencode".equalsIgnoreCase(source)) {
                String nativeId = (targetSessionId != null && !targetSessionId.isBlank())
                        ? targetSessionId
                        : sessionId;
                return resumeOpenCodeDirect(nativeId);
            }

            System.out.println("✓ Resuming conversation: " + sessionId);
            System.out.println("  Target agent: " + agent);
            System.out.println("  Source: " + source);
            System.out.println("  Messages: " + turns.size());
            System.out.println();

            // Export to agent's native format
            if (targetSessionId != null && !targetSessionId.isBlank()) {
                System.out.println("Exporting to " + agent + " native format with session " + targetSessionId + "...");
            } else {
                System.out.println("Exporting to " + agent + " native format...");
            }
            ai.kompile.cli.main.chat.format.ConversationExporter.ExportResult exportResult =
                    ai.kompile.cli.main.chat.format.ConversationExporter.exportToAgent(
                            turns, agent, targetSessionId, source, workingDirectory);

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
                if (agent.toLowerCase(java.util.Locale.ROOT).contains("claude")) {
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
                } catch (java.io.IOException e) {
                    System.err.println(YELLOW + "Warning: Could not inject MCP tools: " + e.getMessage() + RESET);
                }
            }

            // Build the agent command AFTER injection so --mcp-config can reference the written file
            List<String> agentCommand = buildAgentCommand(agent, exportResult, injectTools);

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
                    try { process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS); } catch (InterruptedException ignored) {}
                    if (process.isAlive()) process.destroyForcibly();
                    exitCode = 130;
                    Thread.interrupted();
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
     * Directly resume an existing OpenCode session by UUID without exporting.
     * Uses {@code opencode -s <uuid>} to attach to the session.
     */
    private int resumeOpenCodeDirect(String opencodeSessionId) {
        try {
            Path workingDir = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();

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
                } catch (java.io.IOException e) {
                    System.err.println(YELLOW + "Warning: Could not inject MCP tools: " + e.getMessage() + RESET);
                }
            }

            List<String> cmd = new ArrayList<>();
            cmd.add("opencode");
            cmd.add("-s");
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
                    try { process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS); } catch (InterruptedException ignored) {}
                    if (process.isAlive()) process.destroyForcibly();
                    exitCode = 130;
                    Thread.interrupted();
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
        if (name.contains("codex") && exportResult.getWorkingDirectory() != null) {
            cmd.add("-C");
            cmd.add(exportResult.getWorkingDirectory().toString());
        }
        for (int i = 1; i < resumeParts.length; i++) {
            String part = resumeParts[i];
            if (!part.isEmpty()) {
                cmd.add(part);
            }
        }

        // Add permission bypass flags via centralized overrides
        ai.kompile.cli.main.chat.agent.AgentFlagOverrides.addPermissionBypassFlags(
                cmd, agent, true, exportResult.getWorkingDirectory());

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

        // MCP tools are auto-discovered from .mcp.json / .qwen/settings.json / etc.
        // written by McpToolInjection.injectTools() — no extra flags needed.

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
