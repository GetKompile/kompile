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

import ai.kompile.cli.common.mcp.InstanceDiscovery;
import ai.kompile.cli.common.mcp.McpSseClient;
import ai.kompile.cli.main.chat.config.ChatConfig;
import ai.kompile.cli.main.chat.config.SetupWizard;
import ai.kompile.cli.main.chat.roles.RoleConfig;
import ai.kompile.cli.main.chat.roles.RoleManager;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import picocli.CommandLine;

import java.nio.file.Paths;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;

@CommandLine.Command(
        name = "chat",
        description = "Interactive chat REPL. Works with a running kompile-app (server mode) "
                + "or directly with LLM APIs (local mode).",
        mixinStandardHelpOptions = true
)
public class ChatCommand implements Callable<Integer> {

    @CommandLine.Option(names = {"--url"}, description = "Base URL of the kompile-app instance (e.g. http://localhost:8080)")
    private String url;

    @CommandLine.Option(names = {"--port", "-p"}, description = "Port of the kompile-app instance on localhost")
    private Integer port;

    @CommandLine.Option(names = {"--session-id"}, description = "Chat session ID (generated if not provided)")
    private String sessionId;

    @CommandLine.Option(names = {"--agent"}, description = "Agent name for chat sessions (standard mode) or passthrough agent name (passthrough mode)", defaultValue = "claude")
    private String agentName;

    @CommandLine.Option(names = {"--rag"}, negatable = true, description = "Enable RAG for chat (default: true)", defaultValue = "true")
    private boolean rag;

    @CommandLine.Option(names = {"--resume", "-r"}, description = "Resume a previous conversation by session ID")
    private String resumeSessionId;

    @CommandLine.Option(names = {"--continue", "-c"}, description = "Continue the most recent conversation", defaultValue = "false")
    private boolean continueLastSession;

    @CommandLine.Option(names = {"--list", "-l"}, description = "List saved conversations and exit", defaultValue = "false")
    private boolean listConversations;

    @CommandLine.Option(names = {"--memory"}, negatable = true, description = "Enable memory (default: true)", defaultValue = "true")
    private boolean memory;

    @CommandLine.Option(names = {"--local"}, description = "Force local mode (direct LLM, no server)", defaultValue = "false")
    private boolean forceLocal;

    @CommandLine.Option(names = {"--setup"}, description = "Run LLM configuration setup wizard", defaultValue = "false")
    private boolean runSetup;

    @CommandLine.Option(names = {"--mode"}, description = "Chat mode: 'standard' or 'passthrough' (overrides config)")
    private String mode;

    @CommandLine.Option(names = {"--role"}, description = "Assign a role to the agent (e.g. architect, reviewer, devops)")
    private String role;

    @CommandLine.Option(names = {"--roles"}, description = "Show role selection menu before starting chat", defaultValue = "false")
    private boolean showRoleMenu;

    @Override
    public Integer call() {
        // Handle --setup: run wizard and exit
        if (runSetup) {
            ChatConfig config = SetupWizard.run();
            return config != null ? 0 : 1;
        }

        // Handle --list: just print conversations and exit
        if (listConversations) {
            return listSavedConversations();
        }

        // Handle --continue: find most recent conversation
        if (continueLastSession) {
            List<ChatHistory.ConversationSummary> convos = ChatHistory.listConversations();
            if (convos.isEmpty()) {
                System.err.println("No saved conversations found.");
                return 1;
            }
            resumeSessionId = convos.get(0).sessionId();
        }

        // Handle --resume: use existing session ID
        if (resumeSessionId != null && !resumeSessionId.isBlank()) {
            sessionId = resumeSessionId;
            if (!ChatHistory.exists(sessionId)) {
                System.err.println("No saved conversation found for session: " + sessionId);
                return 1;
            }
        }

        boolean isResume = resumeSessionId != null && !resumeSessionId.isBlank();

        // Resolve role: --role flag > role selection menu > none
        String resolvedRole = resolveRole();

        if (sessionId == null || sessionId.isBlank()) {
            sessionId = "cli-" + UUID.randomUUID().toString().substring(0, 8);
        }

        // Load config to determine chat mode
        ChatConfig config = ChatConfig.loadOrFromEnv();
        String chatMode = config != null ? config.getChatMode() : "standard";

        // Allow --mode CLI flag to override config
        if (mode != null && !mode.isBlank()) {
            chatMode = mode.toLowerCase();
        }

        // If passthrough mode, delegate to PassthroughCommand
        if ("passthrough".equals(chatMode) && config != null) {
            // Allow --agent flag to override config's agent
            String agent = agentName != null && !agentName.isBlank() ? agentName : config.getPassthroughAgent();
            System.out.println("Starting passthrough mode with agent: " + agent);
            return runPassthroughMode(agent, isResume);
        }

        // Try to find a server (unless --local is set)
        String targetUrl = forceLocal ? null : resolveUrl();

        // If no server found via discovery, check if config points to a kompile instance
        if (targetUrl == null && !forceLocal) {
            if (config != null && config.isKompileServer()) {
                targetUrl = config.resolveBaseUrl();
            }
        }

        if (targetUrl != null) {
            // Server mode
            return runServerMode(targetUrl, isResume, resolvedRole);
        } else {
            // Local mode - direct LLM
            return runLocalMode(isResume, resolvedRole);
        }
    }

    /**
     * Server mode: connect to kompile-app via MCP SSE.
     */
    private int runServerMode(String targetUrl, boolean isResume, String assignedRole) {
        System.out.println("Connecting to " + targetUrl + " ...");

        try (McpSseClient client = new McpSseClient(targetUrl)) {
            client.connect();
            client.initialize();

            if (isResume) {
                System.out.println("Resuming conversation: " + sessionId);
                restoreConversation(client, targetUrl);
            } else {
                createChatSession(client);
                System.out.println("New conversation: " + sessionId);
            }

            System.out.println("Type /help for commands, /quit to exit.\n");

            ChatRepl repl = new ChatRepl(client, targetUrl, sessionId, rag, agentName, memory);

            // Assign role if specified
            if (assignedRole != null && !assignedRole.isBlank()) {
                repl.assignRoleAtStartup(assignedRole);
            }

            repl.run();

            return 0;
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            return 1;
        }
    }

    /**
     * Local mode: direct LLM API calls without a server.
     * Runs setup wizard if no configuration exists.
     */
    private int runLocalMode(boolean isResume, String assignedRole) {
        // Load or create LLM configuration
        ChatConfig config = ChatConfig.loadOrFromEnv();

        if (config == null) {
            // No config found - run setup wizard
            if (!forceLocal) {
                System.out.println("No running kompile-app instance found.");
                System.out.println("Starting setup...");
                System.out.println();
            }

            config = SetupWizard.run();
            if (config == null) {
                System.err.println("Setup cancelled.");
                return 1;
            }
            
            // Check if user selected resume mode (wizard launched resume tool which already completed)
            if ("resume".equals(config.getChatMode())) {
                return 0; // Resume tool already finished
            }

            // Check if user selected passthrough mode in wizard
            if ("passthrough".equals(config.getChatMode())) {
                String agent = config.getPassthroughAgent();
                System.out.println("Starting passthrough mode with agent: " + agent);
                return runPassthroughMode(agent, isResume);
            }
        } else {
            if (!forceLocal) {
                System.out.println("No running kompile-app instance found. Using local mode.");
            }

            // Check if config has passthrough mode
            if ("passthrough".equals(config.getChatMode())) {
                String agent = config.getPassthroughAgent();
                System.out.println("Starting passthrough mode with agent: " + agent);
                return runPassthroughMode(agent, isResume);
            }
        }

        // If user selected kompile provider, redirect to server mode
        if (config.isKompileServer()) {
            return runServerMode(config.resolveBaseUrl(), isResume, assignedRole);
        }

        if (isResume) {
            System.out.println("Resuming conversation: " + sessionId);
        } else {
            System.out.println("New conversation: " + sessionId);
        }

        try {
            ChatRepl repl = new ChatRepl(
                    null,       // no MCP client
                    null,       // no base URL
                    sessionId,
                    false,      // no RAG in local mode
                    agentName,
                    memory,
                    config      // LLM config for direct calls
            );

            // Assign role if specified
            if (assignedRole != null && !assignedRole.isBlank()) {
                repl.assignRoleAtStartup(assignedRole);
            }

            repl.run();
            return 0;
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            return 1;
        }
    }

    /**
     * Passthrough mode: delegate to external CLI agent (Claude Code, Codex, etc.)
     */
    private int runPassthroughMode(String agent, boolean isResume) {
        try {
            PassthroughCommand passthrough = new PassthroughCommand();
            // Set fields via reflection or direct access since we're in same package
            passthrough.agent = agent;
            passthrough.workingDir = ".";
            passthrough.skipPermissions = true;
            passthrough.injectTools = true;
            passthrough.kompileUrl = "";
            passthrough.mcpPort = 0;
            return passthrough.call();
        } catch (Exception e) {
            System.err.println("Error running passthrough mode: " + e.getMessage());
            return 1;
        }
    }

    private int listSavedConversations() {
        List<ChatHistory.ConversationSummary> conversations = ChatHistory.listConversations();
        if (conversations.isEmpty()) {
            System.out.println("No saved conversations.");
            System.out.println("Start a new one with: kompile chat");
            return 0;
        }

        System.out.println("Saved conversations:");
        System.out.println();
        for (ChatHistory.ConversationSummary c : conversations) {
            System.out.printf("  %-24s  %-20s  agent=%-8s  %s%n",
                    c.sessionId(), c.started(), c.agent(),
                    c.title().isEmpty() ? "(empty)" : c.title());
        }
        System.out.println();
        System.out.println("Resume with: kompile chat --resume <session-id>");
        System.out.println("Continue last: kompile chat --continue");
        return 0;
    }

    /**
     * Restores a previous conversation by replaying the local transcript
     * to the server-side session so the LLM has full context.
     */
    private void restoreConversation(McpSseClient client, String targetUrl) throws Exception {
        ChatHistory history = new ChatHistory(sessionId);
        List<ChatHistory.Turn> turns = history.readTurns();

        if (turns.isEmpty()) {
            createChatSession(client);
            System.out.println("(no previous messages to restore)");
            return;
        }

        String transcript = history.readTranscript();
        if (transcript != null) {
            System.out.println();
            System.out.println(transcript);
            System.out.println("─── end of previous conversation ───");
            System.out.println();
        }

        createChatSession(client);

        System.out.println("Restoring " + turns.size() + " turns to server...");
        System.out.println("(server session is fresh; local transcript preserved for reference)");
    }

    private String resolveUrl() {
        if (url != null && !url.isBlank()) {
            return url;
        }
        if (port != null) {
            return "http://localhost:" + port;
        }
        return InstanceDiscovery.discover();
    }

    /**
     * Resolve the role to assign at startup.
     * Priority: --role flag > --roles menu > none
     */
    private String resolveRole() {
        // If --role is specified, use it directly
        if (role != null && !role.isBlank()) {
            return role.trim();
        }

        // If --roles menu is requested, show selection
        if (showRoleMenu) {
            return promptForRole();
        }

        return null;
    }

    /**
     * Show interactive role selection menu.
     */
    private String promptForRole() {
        RoleManager roleManager = new RoleManager(Paths.get(System.getProperty("user.dir")));
        List<RoleConfig> roles = roleManager.getAllRoles();

        if (roles.isEmpty()) {
            System.out.println("No roles available.");
            return null;
        }

        System.out.println();
        System.out.println("\033[1m\033[36m  ╭──────────────────────────────────────╮\033[0m");
        System.out.println("\033[1m\033[36m  │       Select a Role                  │\033[0m");
        System.out.println("\033[1m\033[36m  ╰──────────────────────────────────────╯\033[0m");
        System.out.println();
        System.out.println("  \033[1mAvailable Roles:\033[0m");
        System.out.println();

        // Group by category
        var byCategory = roleManager.getRolesByCategory();
        int idx = 1;
        for (var entry : byCategory.entrySet()) {
            System.out.println("  \033[1m[" + entry.getKey() + "]\033[0m");
            for (String roleName : entry.getValue()) {
                RoleConfig rc = roleManager.getRole(roleName);
                if (rc != null) {
                    System.out.printf("  \033[36m%d\033[0m  %-20s %s%n",
                            idx, roleName, rc.getDescription());
                    idx++;
                }
            }
            System.out.println();
        }

        System.out.println("  \033[36m0\033[0m  (none - default agent)");
        System.out.println();

        java.util.Scanner scanner = new java.util.Scanner(System.in);
        while (true) {
            System.out.print("  \033[1mSelect role [0]:\033[0m ");
            String input = scanner.nextLine().trim();
            if (input.isEmpty() || "0".equals(input)) {
                return null;
            }

            try {
                int choice = Integer.parseInt(input);
                if (choice < 0 || choice >= idx) {
                    System.out.println("  \033[33mInvalid choice. Please enter 0-" + (idx - 1) + "\033[0m");
                    continue;
                }

                // Map choice index to role
                int current = 1;
                for (var entry : byCategory.entrySet()) {
                    for (String roleName : entry.getValue()) {
                        if (current == choice) {
                            System.out.println("  → \033[32m" + roleName + "\033[0m");
                            System.out.println();
                            return roleName;
                        }
                        current++;
                    }
                }
            } catch (NumberFormatException e) {
                // Accept role name directly
                RoleConfig rc = roleManager.getRole(input.toLowerCase());
                if (rc != null) {
                    System.out.println("  → \033[32m" + rc.getName() + "\033[0m");
                    System.out.println();
                    return rc.getName();
                }
                System.out.println("  \033[33mRole not found. Try again.\033[0m");
            }
        }
    }

    private void createChatSession(McpSseClient client) throws Exception {
        ObjectNode args = client.getObjectMapper().createObjectNode();
        args.put("sessionId", sessionId);
        args.put("agentName", agentName);
        args.put("enableRag", rag);
        args.put("enableSemanticSearch", true);
        args.put("enableKeywordSearch", true);
        args.put("semanticK", 5);
        args.put("keywordK", 5);
        args.put("maxHistoryMessages", 50);
        args.put("similarityThreshold", 0.5);
        args.put("systemPrompt", "");
        client.callTool("create_chat_session", args);
    }
}
