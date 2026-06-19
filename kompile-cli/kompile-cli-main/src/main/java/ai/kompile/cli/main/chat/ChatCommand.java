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
import ai.kompile.cli.main.chat.agent.SubprocessAgentRunner;
import ai.kompile.cli.main.chat.config.ChatConfig;
import ai.kompile.cli.main.chat.config.SetupWizard;
import ai.kompile.cli.main.chat.config.SystemPromptManager;
import ai.kompile.cli.main.chat.enforcer.*;
import ai.kompile.cli.main.chat.harness.HarnessConfig;
import ai.kompile.cli.main.chat.roles.RoleConfig;
import ai.kompile.cli.main.chat.roles.RoleManager;
import ai.kompile.cli.main.chat.skill.CustomSkillLoader;
import ai.kompile.cli.main.chat.skill.SkillConfig;
import ai.kompile.cli.main.chat.skill.SkillRegistry;
import ai.kompile.cli.main.chat.skill.SkillsInjection;
import ai.kompile.cli.common.util.JsonUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jline.reader.EndOfFileException;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.UserInterruptException;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import picocli.CommandLine;

import java.io.IOError;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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

    @CommandLine.Option(names = {"--agent"}, description = "Agent name for chat sessions (standard mode) or passthrough agent name (passthrough mode)")
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

    @CommandLine.Option(names = {"--rules"}, description = "Inline enforcer rules for real-time judge monitoring in managed passthrough")
    private String enforcerRules;

    @CommandLine.Option(names = {"--rule-file"}, description = "Path to file containing enforcer rules for real-time judge monitoring")
    private String enforcerRuleFile;

    @CommandLine.Option(names = {"--max-reprompts"}, description = "Maximum auto-reprompts on enforcer violations (default: 2)", defaultValue = "2")
    private int maxReprompts;

    @CommandLine.Option(names = {"--judge-provider"}, description = "Judge LLM provider for real-time enforcement (e.g. anthropic, openai)")
    private String judgeProvider;

    @CommandLine.Option(names = {"--judge-model"}, description = "Judge LLM model for real-time enforcement")
    private String judgeModel;

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

        // Check if explicit action flags are given (skip wizard if so)
        boolean hasExplicitAction = isResume || (mode != null && !mode.isBlank())
                || (url != null && !url.isBlank()) || port != null || forceLocal;

        // Load provider credentials/settings, but do not treat saved chat mode/agent as
        // a default session intent. Chat is task-specific and should ask unless the
        // user provided explicit mode/agent flags in this invocation.
        ChatConfig config = ChatConfig.loadOrFromEnv();
        boolean configSelectedInThisRun = false;

        if (!hasExplicitAction || runSetup || config == null) {
            config = SetupWizard.run();
            configSelectedInThisRun = true;
            if (config == null) {
                System.err.println("Setup cancelled.");
                return 1;
            }
        }

        // Override chat mode from --mode flag
        if (mode != null && !mode.isBlank()) {
            config.setChatMode(mode.toLowerCase());
        }

        return routeFromConfig(config, isResume, resolvedRole, configSelectedInThisRun);
    }

    /**
     * Route to the correct chat mode based on the resolved config.
     * Dispatches between passthrough (managed/direct), server, and local LLM modes.
     */
    private int routeFromConfig(ChatConfig config, boolean isResume, String resolvedRole,
                                boolean configSelectedInThisRun) {
        String chatMode = config.getChatMode();

        // Resume mode: wizard already launched ResumeTool, nothing else to do
        if ("resume".equals(chatMode)) {
            return 0;
        }

        if ("passthrough".equals(chatMode)) {
            String agent = effectivePassthroughAgent(config, configSelectedInThisRun);
            if (agent == null || agent.isBlank()) {
                System.err.println("Passthrough mode requires an agent.");
                return 1;
            }
            System.out.println("Starting passthrough mode with agent: " + agent);

            // Auto-escalate to managed mode when enforcer config exists (real-time blocking)
            boolean managed = config.isPassthroughManaged();
            if (!managed) {
                Path wd = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
                ai.kompile.cli.main.chat.enforcer.EnforcerConfig enforcerConfig =
                        ai.kompile.cli.main.chat.enforcer.EnforcerConfig.load(wd);
                if (enforcerConfig != null && enforcerConfig.isKeywordMode()) {
                    managed = true;
                }
            }

            if (managed) {
                return runManagedPassthroughMode(agent, isResume);
            } else {
                return runDirectPassthroughMode(agent, isResume);
            }
        }

        // Server mode only when explicitly requested via --url or --port
        String targetUrl = resolveExplicitUrl();
        if (targetUrl != null) {
            return runServerMode(targetUrl, isResume, resolvedRole);
        }

        // If config has kompile provider, use its URL when explicit
        if (config.isKompileServer()) {
            String serverUrl = resolveExplicitUrl();
            if (serverUrl != null) {
                return runServerMode(serverUrl, isResume, resolvedRole);
            }
            System.err.println("Config has kompile provider but no --url/--port given.");
            System.err.println("Use: kompile chat --url http://localhost:8080");
            System.err.println("Or reconfigure with: kompile chat --setup");
            return 1;
        }

        // Local mode - direct LLM API calls
        return runLocalLlmMode(config, isResume, resolvedRole);
    }

    /**
     * Resolve the passthrough agent. Saved config is only honored when the
     * current run just selected it via the setup wizard; otherwise the user must
     * pass --agent or choose one interactively.
     */
    private String effectivePassthroughAgent(ChatConfig config, boolean configSelectedInThisRun) {
        if (agentName != null && !agentName.isBlank()) {
            return agentName;
        }
        if (configSelectedInThisRun) {
            String configAgent = config.getPassthroughAgent();
            if (configAgent != null && !configAgent.isBlank()) return configAgent;
        }
        return promptForPassthroughAgent();
    }

    private String promptForPassthroughAgent() {
        try (Terminal terminal = TerminalBuilder.builder().system(true).build()) {
            LineReader reader = LineReaderBuilder.builder().terminal(terminal).build();
            List<String> agents = new java.util.ArrayList<>();
            for (String candidate : ChatConfig.getPassthroughAgentOrder()) {
                if (SubprocessAgentRunner.resolveAgentBinary(candidate) != null) {
                    agents.add(candidate);
                }
            }
            if (agents.isEmpty()) {
                List<String> supported = ChatConfig.getPassthroughAgentOrder();
                System.err.println("No supported CLI agents found on PATH.");
                System.err.println("Supported agents: " + String.join(", ", supported));
                System.err.println("Install one and make sure it is on your PATH.");
                return null;
            }
            System.out.println("Select passthrough agent:");
            for (int i = 0; i < agents.size(); i++) {
                System.out.printf("  %d  %s%n", i + 1, agents.get(i));
            }
            while (true) {
                String input = reader.readLine("  Choice (1-" + agents.size() + ", q to cancel): ");
                if (input == null || input.trim().equalsIgnoreCase("q")) return null;
                try {
                    int choice = Integer.parseInt(input.trim());
                    if (choice >= 1 && choice <= agents.size()) return agents.get(choice - 1);
                } catch (NumberFormatException ignored) {
                    for (String agent : agents) {
                        if (agent.contains(input.trim().toLowerCase(Locale.ROOT))) return agent;
                    }
                }
                System.out.println("Enter a valid agent number or name.");
            }
        } catch (Exception e) {
            System.err.println("Could not select passthrough agent: " + e.getMessage());
            return null;
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
     * Local LLM mode: direct API calls without a server.
     * Config is already resolved by the caller (call() or routeFromConfig()).
     */
    private int runLocalLlmMode(ChatConfig config, boolean isResume, String assignedRole) {
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
     * Managed passthrough mode: kompile REPL wraps the agent subprocess via SubprocessAgentRunner.
     * Provides hooks, MCP tool injection, system prompt, skills, memory, and metrics tracking.
     * When --rules or --rule-file is set, enables real-time judge monitoring and interruption.
     */
    private int runManagedPassthroughMode(String agent, boolean isResume) {
        boolean hasEnforcerRules = (enforcerRules != null && !enforcerRules.isBlank())
                || (enforcerRuleFile != null && !enforcerRuleFile.isBlank());

        // Also check for per-project enforcer config
        if (!hasEnforcerRules) {
            Path wd = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
            ai.kompile.cli.main.chat.enforcer.EnforcerConfig enforcerConfig =
                    ai.kompile.cli.main.chat.enforcer.EnforcerConfig.load(wd);
            if (enforcerConfig != null && enforcerConfig.isKeywordMode()) {
                hasEnforcerRules = true;
            }
        }

        if (hasEnforcerRules) {
            return runEnforcedPassthroughMode(agent);
        }

        // If resuming, delegate to ResumeCommand
        if (isResume && resumeSessionId != null && !resumeSessionId.isBlank()) {
            return new CommandLine(new ResumeCommand())
                    .execute("--session-id", resumeSessionId, "--agent", agent);
        }

        try {
            // Delegate to EmulatedPassthroughCommand — the single REPL implementation
            // that has full slash-command completion, auto-trigger, MCP tools, etc.
            EmulatedPassthroughCommand passthrough = new EmulatedPassthroughCommand();
            passthrough.agent = agent;
            passthrough.workingDir = ".";
            passthrough.skipPermissions = true;
            passthrough.injectTools = true;
            passthrough.kompileUrl = "";
            passthrough.mcpPort = 0;
            passthrough.systemPromptManager = SystemPromptManager.resolve(null, null, null);
            return passthrough.call();
        } catch (Exception | IOError e) {
            // IOError can be thrown by JLine when stty fails during
            // terminal shutdown (e.g. thread interrupted in native image).
            System.err.println("Error in managed passthrough: " + e.getMessage());
            e.printStackTrace();
            return 1;
        }
    }

    /**
     * Direct passthrough mode: agent owns the terminal with full native experience.
     * Kompile injects MCP tools, system prompt, and skills but then hands off control.
     */
    private int runDirectPassthroughMode(String agent, boolean isResume) {
        // If resuming, delegate to ResumeCommand
        if (isResume && resumeSessionId != null && !resumeSessionId.isBlank()) {
            return new CommandLine(new ResumeCommand())
                    .execute("--session-id", resumeSessionId, "--agent", agent);
        }

        try {
            PassthroughCommand passthrough = new PassthroughCommand();
            passthrough.agent = agent;
            passthrough.workingDir = ".";
            passthrough.skipPermissions = true;
            passthrough.injectTools = true;
            passthrough.kompileUrl = "";
            passthrough.mcpPort = 0;
            // Inject system prompt so Codex/OpenCode get AGENTS.md
            passthrough.systemPromptManager = SystemPromptManager.resolve(null, null, null);
            return passthrough.call();
        } catch (Exception e) {
            System.err.println("Error running passthrough mode: " + e.getMessage());
            return 1;
        }
    }

    /**
     * Enforced passthrough: kompile controls the agent subprocess via
     * SubprocessAgentRunner, with a background judge that can score output
     * in real time and interrupt on policy violations.
     */
    /**
     * Enforced passthrough mode: resolves enforcer config, then delegates to
     * EmulatedPassthroughCommand with enforcer fields set. ONE REPL, not two.
     */
    private int runEnforcedPassthroughMode(String agent) {
        ObjectMapper objectMapper = JsonUtils.standardMapper();
        Path wd = Path.of(".").toAbsolutePath().normalize();

        String rules;
        try {
            rules = EnforcerPolicy.resolveRules(enforcerRules, enforcerRuleFile, wd);
        } catch (IOException e) {
            System.err.println("Error reading enforcer rules: " + e.getMessage());
            return 1;
        }
        boolean useKeywordMode = false;
        int effectiveMaxReprompts = maxReprompts;
        ai.kompile.cli.main.chat.enforcer.EnforcerConfig projectEnforcerConfig = null;

        if (rules == null || rules.isBlank()) {
            projectEnforcerConfig = ai.kompile.cli.main.chat.enforcer.EnforcerConfig.load(wd);
            if (projectEnforcerConfig != null) {
                try {
                    rules = projectEnforcerConfig.buildRulesText(wd);
                    useKeywordMode = projectEnforcerConfig.isKeywordMode();
                    effectiveMaxReprompts = projectEnforcerConfig.getMaxCorrections();
                } catch (Exception e) {
                    rules = null;
                }
            }
        }

        if (rules == null || rules.isBlank()) {
            System.err.println("Enforcer rules are required (--rules or --rule-file, or project .kompile/enforcer.json).");
            return 1;
        }

        EnforcerPolicy policy = new EnforcerPolicy(rules, effectiveMaxReprompts, false);
        HarnessConfig harnessConfig = HarnessConfig.load(objectMapper);

        // Choose evaluator
        EnforcerEvaluator evaluator;
        if (useKeywordMode) {
            KeywordEnforcerEvaluator kwEval = KeywordEnforcerEvaluator.fromPolicy(policy, objectMapper, projectEnforcerConfig);
            if (!kwEval.isAvailable()) {
                System.err.println("No keyword rules parsed. Use BAN:/STOP: prefixes.");
                return 1;
            }
            evaluator = kwEval;
        } else {
            if (judgeProvider != null && !judgeProvider.isBlank()) {
                harnessConfig.setJudgeProvider(judgeProvider);
            }
            if (judgeModel != null && !judgeModel.isBlank()) {
                harnessConfig.setJudgeModel(judgeModel);
            }
            EnforcerJudge judge = new EnforcerJudge(harnessConfig, objectMapper);
            if (!judge.isAvailable()) {
                System.err.println("No enforcer judge backend available.");
                System.err.println("Configure ~/.kompile/harness-config.json or pass --judge-provider/--judge-model.");
                System.err.println("Tip: use keyword-mode in your enforcer config for simple rules without an LLM.");
                return 1;
            }
            evaluator = judge;
        }

        EnforcerService service = new EnforcerService(evaluator);
        EnforcerRuntimePolicy runtimePolicy;
        try {
            runtimePolicy = EnforcerRuntimePolicy.create(wd, policy, harnessConfig, objectMapper);
        } catch (IOException e) {
            System.err.println("Error initializing enforcer runtime: " + e.getMessage());
            return 1;
        }
        EnforcerConversationWindow conversationWindow =
                new EnforcerConversationWindow(runtimePolicy.getContextFile(), objectMapper);

        try {
            // Delegate to the single REPL with enforcer fields set
            EmulatedPassthroughCommand passthrough = new EmulatedPassthroughCommand();
            passthrough.agent = agent;
            passthrough.workingDir = wd.toString();
            passthrough.skipPermissions = true;
            passthrough.injectTools = true;
            passthrough.kompileUrl = "";
            passthrough.mcpPort = 0;
            passthrough.systemPromptManager = SystemPromptManager.resolve(null, null, null);
            passthrough.enforcerEvaluator = evaluator;
            passthrough.enforcerPolicy = policy;
            passthrough.enforcerService = service;
            passthrough.enforcerConversationWindow = conversationWindow;
            passthrough.enforcerExtraEnv = runtimePolicy.toEnvironment();
            return passthrough.call();
        } catch (Exception e) {
            System.err.println("Error in enforced passthrough: " + e.getMessage());
            return 1;
        } finally {
            runtimePolicy.cleanup();
            if (evaluator instanceof AutoCloseable closeable) {
                try { closeable.close(); } catch (Exception ignored) {}
            }
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

    /**
     * Resolve URL only from explicit --url or --port flags.
     * Never auto-discovers — server mode is opt-in.
     */
    private String resolveExplicitUrl() {
        if (url != null && !url.isBlank()) {
            return url;
        }
        if (port != null) {
            return "http://localhost:" + port;
        }
        return null;
    }

    private String resolveUrl() {
        String explicit = resolveExplicitUrl();
        if (explicit != null) {
            return explicit;
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
