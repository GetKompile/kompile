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

import ai.kompile.cli.main.chat.tools.ConversationImportTool;
import ai.kompile.cli.main.chat.tools.ToolContext;
import ai.kompile.cli.main.chat.tools.ToolResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import picocli.CommandLine;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * Parent command for session management operations.
 * Provides unified interface for listing, importing, and managing
 * chat sessions from kompile and external AI assistants.
 */
@CommandLine.Command(
        name = "session",
        description = "Manage chat sessions from kompile and external AI assistants",
        mixinStandardHelpOptions = true,
        subcommands = {
                SessionCommand.ListCommand.class,
                SessionCommand.ShowCommand.class,
                SessionCommand.ImportCommand.class,
                SessionCommand.ImportAllCommand.class,
                SessionCommand.SearchCommand.class
        }
)
public class SessionCommand implements Callable<Integer> {

    @Override
    public Integer call() {
        System.out.println("Usage: kompile session <subcommand>");
        System.out.println();
        System.out.println("Subcommands:");
        System.out.println("  list       List available sessions from all sources");
        System.out.println("  show       Show transcript of a specific session");
        System.out.println("  import     Import a session from an external assistant");
        System.out.println("  import-all Import all sessions from a source");
        System.out.println("  search     Search across all sessions");
        System.out.println();
        System.out.println("Examples:");
        System.out.println("  kompile session list --source=all");
        System.out.println("  kompile session show imported-claude-abc123");
        System.out.println("  kompile session import opencode ses_abc123");
        System.out.println("  kompile session search \"database migration\"");
        return 0;
    }

    /**
     * List sessions from kompile and/or external assistants.
     */
    @CommandLine.Command(name = "list", description = "List available sessions")
    static class ListCommand implements Callable<Integer> {

        @CommandLine.Option(names = {"--source", "-s"}, 
                           defaultValue = "all",
                           description = "Source to list: all, kompile, claude-code, opencode, codex, qwen")
        private String source;

        @CommandLine.Option(names = {"--limit", "-l"}, 
                           defaultValue = "50",
                           description = "Maximum number of sessions to list")
        private int limit;

        @Override
        public Integer call() {
            ConversationImportTool importTool = new ConversationImportTool();
            ObjectMapper mapper = new ObjectMapper();

            try {
                if ("all".equalsIgnoreCase(source) || "kompile".equalsIgnoreCase(source)) {
                    listKompileSessions();
                }

                if ("all".equalsIgnoreCase(source) || "claude-code".equalsIgnoreCase(source)) {
                    listExternalSource(importTool, mapper, "claude-code");
                }

                if ("all".equalsIgnoreCase(source) || "opencode".equalsIgnoreCase(source)) {
                    listExternalSource(importTool, mapper, "opencode");
                }

                if ("all".equalsIgnoreCase(source) || "codex".equalsIgnoreCase(source)) {
                    listExternalSource(importTool, mapper, "codex");
                }

                if ("all".equalsIgnoreCase(source) || "qwen".equalsIgnoreCase(source)) {
                    listExternalSource(importTool, mapper, "qwen");
                }

            } catch (Exception e) {
                System.err.println("Error listing sessions: " + e.getMessage());
                return 1;
            }

            return 0;
        }

        private void listKompileSessions() {
            List<ChatHistory.ConversationSummary> sessions = ChatHistory.listConversations();
            if (sessions.isEmpty()) {
                System.out.println("No kompile sessions found.");
                return;
            }

            System.out.println("Kompile sessions:");
            System.out.println();
            int count = 0;
            for (ChatHistory.ConversationSummary s : sessions) {
                if (count++ >= limit) break;
                System.out.printf("  %-30s  %-20s  agent=%-8s%n",
                        s.sessionId(), 
                        s.started().isEmpty() ? "(no date)" : s.started(),
                        s.agent());
                if (!s.title().isEmpty()) {
                    System.out.printf("    %s%n", s.title().length() > 70 ? 
                            s.title().substring(0, 67) + "..." : s.title());
                }
            }
            System.out.println();
        }

        private void listExternalSource(ConversationImportTool tool, ObjectMapper mapper, String src) {
            try {
                ObjectNode params = mapper.createObjectNode();
                params.put("action", "list");
                params.put("source", src);

                ToolResult result = tool.execute(params, createToolContext());
                if (result.isError()) {
                    System.out.println(src + ": " + result.getOutput());
                } else {
                    System.out.println(result.getOutput());
                }
            } catch (Exception e) {
                System.out.println(src + ": Error - " + e.getMessage());
            }
        }

        private ToolContext createToolContext() {
            // Create minimal context for tool execution
            return new ToolContext(
                    "session-cli",
                    null, // No agent config needed
                    new ai.kompile.cli.main.chat.permission.PermissionService(),
                    Path.of(System.getProperty("user.dir")),
                    null  // No tool registry needed
            );
        }
    }

    /**
     * Show transcript of a specific session.
     */
    @CommandLine.Command(name = "show", description = "Show session transcript")
    static class ShowCommand implements Callable<Integer> {

        @CommandLine.Parameters(index = "0", description = "Session ID to show")
        private String sessionId;

        @CommandLine.Option(names = {"--lines", "-n"}, 
                           defaultValue = "100",
                           description = "Number of lines to show")
        private int lines;

        @Override
        public Integer call() {
            ChatHistory history = new ChatHistory(sessionId);
            
            if (!ChatHistory.exists(sessionId)) {
                System.err.println("Session not found: " + sessionId);
                System.err.println("Use 'kompile session list' to see available sessions.");
                return 1;
            }

            try {
                String transcript = history.readTranscript();
                if (transcript == null) {
                    System.err.println("Could not read transcript for: " + sessionId);
                    return 1;
                }

                // Limit output if requested
                String[] allLines = transcript.split("\n");
                if (allLines.length > lines) {
                    System.out.println("(Showing first " + lines + " of " + allLines.length + " lines)");
                    System.out.println();
                    for (int i = 0; i < lines; i++) {
                        System.out.println(allLines[i]);
                    }
                    System.out.println();
                    System.out.println("(... " + (allLines.length - lines) + " more lines ...)");
                    System.out.println("Use 'kompile session show " + sessionId + " --lines " + allLines.length + " for full transcript");
                } else {
                    System.out.println(transcript);
                }

            } catch (Exception e) {
                System.err.println("Error reading transcript: " + e.getMessage());
                return 1;
            }

            return 0;
        }
    }

    /**
     * Import a specific session from an external assistant.
     */
    @CommandLine.Command(name = "import", description = "Import a session from external assistant")
    static class ImportCommand implements Callable<Integer> {

        @CommandLine.Parameters(index = "0", description = "Source (claude-code, opencode, codex, qwen)")
        private String source;

        @CommandLine.Parameters(index = "1", description = "Session ID to import")
        private String sessionId;

        @Override
        public Integer call() {
            ConversationImportTool importTool = new ConversationImportTool();
            ObjectMapper mapper = new ObjectMapper();

            try {
                ObjectNode params = mapper.createObjectNode();
                params.put("action", "import");
                params.put("source", source);
                params.put("conversation_id", sessionId);

                ToolResult result = importTool.execute(params, createToolContext());
                
                System.out.println(result.getOutput());
                
                if (result.isError()) {
                    return 1;
                }

                // Show the imported session location
                String targetId = extractTargetId(result.getOutput());
                if (targetId != null) {
                    System.out.println();
                    System.out.println("Session saved to: ~/.kompile/conversations/" + targetId + ".txt");
                    System.out.println("Resume with: kompile chat --resume " + targetId);
                }

            } catch (Exception e) {
                System.err.println("Error importing session: " + e.getMessage());
                return 1;
            }

            return 0;
        }

        private String extractTargetId(String output) {
            // Try to extract target ID from output
            if (output.contains("as '")) {
                int start = output.indexOf("as '") + 4;
                int end = output.indexOf("'", start);
                if (end > start) {
                    return output.substring(start, end);
                }
            }
            return null;
        }

        private ToolContext createToolContext() {
            return new ToolContext(
                    "session-cli",
                    null,
                    new ai.kompile.cli.main.chat.permission.PermissionService(),
                    Path.of(System.getProperty("user.dir")),
                    null
            );
        }
    }

    /**
     * Import all sessions from a source.
     */
    @CommandLine.Command(name = "import-all", description = "Import all sessions from a source")
    static class ImportAllCommand implements Callable<Integer> {

        @CommandLine.Parameters(index = "0", description = "Source (claude-code, opencode, codex, qwen)")
        private String source;

        @CommandLine.Option(names = {"--force", "-f"}, 
                           description = "Overwrite existing imported sessions")
        private boolean force;

        @Override
        public Integer call() {
            ConversationImportTool importTool = new ConversationImportTool();
            ObjectMapper mapper = new ObjectMapper();

            System.out.println("Importing all sessions from " + source + "...");
            System.out.println("This may take a while for large collections.");
            System.out.println();

            try {
                ObjectNode params = mapper.createObjectNode();
                params.put("action", "import-all");
                params.put("source", source);

                ToolResult result = importTool.execute(params, createToolContext());
                
                System.out.println(result.getOutput());
                
                if (result.isError()) {
                    return 1;
                }

            } catch (Exception e) {
                System.err.println("Error importing sessions: " + e.getMessage());
                return 1;
            }

            return 0;
        }

        private ToolContext createToolContext() {
            return new ToolContext(
                    "session-cli",
                    null,
                    new ai.kompile.cli.main.chat.permission.PermissionService(),
                    Path.of(System.getProperty("user.dir")),
                    null
            );
        }
    }

    /**
     * Search across all sessions.
     */
    @CommandLine.Command(name = "search", description = "Search across all sessions")
    static class SearchCommand implements Callable<Integer> {

        @CommandLine.Parameters(index = "0", description = "Search query")
        private String query;

        @CommandLine.Option(names = {"--source", "-s"}, 
                           defaultValue = "all",
                           description = "Source to search: all, kompile, claude-code, opencode, codex, qwen")
        private String source;

        @CommandLine.Option(names = {"--context", "-c"}, 
                           defaultValue = "3",
                           description = "Lines of context to show around matches")
        private int context;

        @Override
        public Integer call() {
            System.out.println("Searching for: " + query);
            System.out.println("Sources: " + source);
            System.out.println();

            int totalMatches = 0;

            // Search kompile sessions
            if ("all".equalsIgnoreCase(source) || "kompile".equalsIgnoreCase(source)) {
                totalMatches += searchKompileSessions(query, context);
            }

            // TODO: Search external sources (requires SessionIndex implementation)
            if ("all".equalsIgnoreCase(source) || "claude-code".equalsIgnoreCase(source)) {
                // Will be implemented in Phase 2
            }

            if (totalMatches == 0) {
                System.out.println("No matches found.");
            } else {
                System.out.println();
                System.out.println("Found " + totalMatches + " match(es).");
            }

            return 0;
        }

        private int searchKompileSessions(String query, int contextLines) {
            List<ChatHistory.ConversationSummary> sessions = ChatHistory.listConversations();
            int matchCount = 0;
            String queryLower = query.toLowerCase();

            for (ChatHistory.ConversationSummary summary : sessions) {
                ChatHistory history = new ChatHistory(summary.sessionId());
                try {
                    String transcript = history.readTranscript();
                    if (transcript == null) continue;

                    String[] lines = transcript.split("\n");
                    for (int i = 0; i < lines.length; i++) {
                        if (lines[i].toLowerCase().contains(queryLower)) {
                            matchCount++;
                            System.out.println("── " + summary.sessionId() + " (line " + (i + 1) + ") ──");
                            
                            // Show context
                            int start = Math.max(0, i - contextLines);
                            int end = Math.min(lines.length, i + contextLines + 1);
                            for (int j = start; j < end; j++) {
                                if (j == i) {
                                    System.out.println("> " + lines[j]);
                                } else {
                                    System.out.println("  " + lines[j]);
                                }
                            }
                            System.out.println();
                            
                            // Limit matches per session
                            if (matchCount % 10 == 0) {
                                break;
                            }
                        }
                    }
                } catch (Exception e) {
                    // Skip unreadable transcripts
                }
            }

            return matchCount;
        }
    }
}
