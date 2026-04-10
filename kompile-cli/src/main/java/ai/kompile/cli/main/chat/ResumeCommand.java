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
 *   kompile resume                          # Launch interactive TUI
 *   kompile resume --search "database"      # Search for conversations
 *   kompile resume --session-id abc123      # Resume specific conversation
 *   kompile resume --agent claude           # Resume with specific agent
 */
@CommandLine.Command(
        name = "resume",
        description = "Interactive multi-tab tool for browsing, searching, migrating, and resuming conversations",
        mixinStandardHelpOptions = true
)
public class ResumeCommand implements Callable<Integer> {

    @CommandLine.Option(names = {"--session-id", "-s"}, description = "Resume a specific conversation by session ID")
    private String sessionId;

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
    private String resolvedMcpUrl;
    private boolean mcpUrlResolved;

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
     * List all conversations.
     */
    private int listConversations() {
        try {
            ResumeTool tool = new ResumeTool();
            tool.runInteractiveBrowser();
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
            ai.kompile.cli.main.chat.format.ConversationReader reader =
                    new ai.kompile.cli.main.chat.format.ConversationReader();

            java.util.List<ai.kompile.cli.main.chat.ChatHistory.Turn> turns = null;
            String source = "kompile";
            java.nio.file.Path workingDirectory = java.nio.file.Path.of(System.getProperty("user.dir"))
                    .toAbsolutePath()
                    .normalize();

            try {
                turns = reader.readKompileSession(sessionId);
            } catch (Exception ignored) {
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
                if (turns == null && lastExternalError != null) {
                    throw lastExternalError;
                }
            }

            if (turns == null || turns.isEmpty()) {
                System.err.println("No transcript found for session: " + sessionId);
                return 1;
            }

            System.out.println("✓ Resuming conversation: " + sessionId);
            System.out.println("  Target agent: " + agent);
            System.out.println("  Source: " + source);
            System.out.println("  Messages: " + turns.size());
            System.out.println();

            // Export to agent's native format
            System.out.println("Exporting to " + agent + " native format...");
            ai.kompile.cli.main.chat.format.ConversationExporter.ExportResult exportResult =
                    ai.kompile.cli.main.chat.format.ConversationExporter.exportToAgent(
                            turns, agent, null, source, workingDirectory);

            System.out.println();
            System.out.println("✓ Exported to " + agent + " native format");
            System.out.println("  Session ID: " + exportResult.getSessionId());
            System.out.println("  Saved to: " + exportResult.getSessionPath());
            System.out.println();

            // Build the agent command with tool injection
            List<String> agentCommand = buildAgentCommand(agent, exportResult);

            if (injectTools) {
                Path agentWorkingDir = exportResult.getWorkingDirectory() != null
                        ? exportResult.getWorkingDirectory()
                        : Path.of(System.getProperty("user.dir"));
                try {
                    Path settingsFile = ai.kompile.cli.main.chat.mcp.McpToolInjection.injectTools(agentWorkingDir, agent);
                    if (settingsFile != null) {
                        System.out.println(GREEN + "Kompile tools injected" + RESET
                                + DIM + " (" + settingsFile + ")" + RESET);
                    }
                } catch (java.io.IOException e) {
                    System.err.println(YELLOW + "Warning: Could not inject MCP tools: " + e.getMessage() + RESET);
                }
            }

            System.out.println();
            System.out.println("Launching agent with native session resume...");
            System.out.println();

            ProcessBuilder pb = new ProcessBuilder(agentCommand);
            if (exportResult.getWorkingDirectory() != null) {
                pb.directory(exportResult.getWorkingDirectory().toFile());
            }
            pb.inheritIO();
            Process process = pb.start();
            int exitCode = process.waitFor();

            System.out.println();
            System.out.println("✓ Agent session completed (exit code: " + exitCode + ")");
            return exitCode;
        } catch (Exception e) {
            System.err.println("Error resuming conversation: " + e.getMessage());
            e.printStackTrace();
            return 1;
        }
    }

    /**
     * Build the agent command with optional tool injection.
     */
    private List<String> buildAgentCommand(String agent,
                                           ai.kompile.cli.main.chat.format.ConversationExporter.ExportResult exportResult) {
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

        // Add permission bypass flags for different agents
        if (name.contains("claude")) {
            cmd.add("--dangerously-skip-permissions");
        } else if (name.contains("codex")) {
            cmd.add("--full-auto");
        } else if (name.contains("qwen")) {
            cmd.add("--yolo");
        } else if (name.contains("gemini")) {
            cmd.add("--yolo");
        }

        // Inject tools by writing MCP config to the agent's settings file
        if (injectTools) {
            Path agentWorkingDir = exportResult.getWorkingDirectory() != null
                    ? exportResult.getWorkingDirectory()
                    : Path.of(System.getProperty("user.dir"));
            try {
                Path settingsFile = ai.kompile.cli.main.chat.mcp.McpToolInjection.injectTools(agentWorkingDir, agent);
                if (settingsFile != null) {
                    System.out.println(DIM + "Injected kompile MCP tools into " + settingsFile + RESET);
                }
            } catch (java.io.IOException e) {
                System.err.println(YELLOW + "Warning: Could not inject MCP tools: " + e.getMessage() + RESET);
            }
        }

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
            if (java.nio.file.Files.exists(java.nio.file.Path.of(jarPath)))
                return processCmd + " -jar " + jarPath;
        } catch (Exception e) { /* Fall through */ }
        return null;
    }
}
