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

import ai.kompile.cli.common.KompileHome;
import ai.kompile.cli.common.mcp.McpSseClient;
import ai.kompile.cli.common.routing.KompileService;
import ai.kompile.cli.common.routing.KompileServiceEndpoints;
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
import com.fasterxml.jackson.databind.JsonNode;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Scanner;
import java.util.UUID;
import java.util.concurrent.Callable;

@CommandLine.Command(
        name = "chat",
        description = "Interactive chat REPL. Uses a Kompile instance, the first-party "
                + "Kompile serving subprocess, or a configured model API.",
        mixinStandardHelpOptions = true
)
public class ChatCommand implements Callable<Integer> {

    @CommandLine.Option(names = {"--url"}, description = {
            "Base URL of the kompile chat server.",
            "Example: http://localhost:8081"
    })
    private String url;

    @CommandLine.Option(names = {"--port", "-p"}, description = "Port of the kompile chat server on localhost")
    private Integer port;

    @CommandLine.Option(names = {"--start"}, negatable = true, defaultValue = "true",
            fallbackValue = "true", description =
            "Start the installed chat subprocess when no instance is configured (default: true)")
    private boolean startServer;

    @CommandLine.Option(names = {"--startup-timeout"}, defaultValue = "120", description =
            "Seconds to wait for the installed chat subprocess (default: 120)")
    private int startupTimeoutSeconds;

    @CommandLine.Option(names = {"--session-id"}, description =
            "Transcript UUID/ID (a full UUID is generated if not provided)")
    private String sessionId;

    @CommandLine.Option(names = {"--agent"}, description = "Agent name for chat sessions (standard mode) or passthrough agent name (passthrough mode)")
    private String agentName;

    @CommandLine.Option(names = {"--working-dir"}, description =
            "Project directory for chat configuration, tools, and transcript metadata")
    private Path workingDirectory;

    @CommandLine.Option(names = {"--model"}, description = {
            "Model passed to the passthrough agent CLI.",
            "Examples: haiku, gpt-5.2-codex, anthropic/claude-haiku-4-5"
    })
    private String model;

    @CommandLine.Option(names = {"--thinking", "--effort"}, description =
            "Thinking/effort override for the selected passthrough agent")
    private String thinking;

    @CommandLine.Option(names = {"--rag"}, negatable = true, description = "Enable RAG for chat (default: true)", defaultValue = "true")
    private boolean rag;

    @CommandLine.Option(names = {"--resume", "-r"}, description =
            "Resume a previous conversation by transcript UUID or legacy session ID")
    private String resumeSessionId;

    @CommandLine.Option(names = {"--continue", "-c"}, description = "Continue the most recent conversation", defaultValue = "false")
    private boolean continueLastSession;

    @CommandLine.Option(names = {"--list", "-l"}, description = "List saved conversations and exit", defaultValue = "false")
    private boolean listConversations;

    @CommandLine.Option(names = {"--memory"}, negatable = true, description = "Enable memory (default: true)", defaultValue = "true")
    private boolean memory;

    @CommandLine.Option(names = {"--dangerously-skip-permissions"}, defaultValue = "false",
            description = "Allow every standard-chat tool permission for this session without prompting")
    private boolean dangerouslySkipPermissions;

    @CommandLine.Option(names = {"--local"}, description = "Force no-instance mode (Kompile serving subprocess or direct model API)", defaultValue = "false")
    private boolean forceLocal;

    @CommandLine.Option(names = {"--setup"}, description = "Run chat configuration setup wizard", defaultValue = "false")
    private boolean runSetup;

    @CommandLine.Option(names = {"--global-config"}, description = "Use global ~/.kompile/chat-config.json instead of project-local .kompile/chat-config.json", defaultValue = "false")
    private boolean globalConfig;

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

    boolean dangerouslySkipsPermissions() {
        return dangerouslySkipPermissions;
    }

    static String newTranscriptUuid() {
        return UUID.randomUUID().toString();
    }

    Path effectiveWorkingDirectory() {
        return workingDirectory == null
                ? Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize()
                : workingDirectory.toAbsolutePath().normalize();
    }

    void inferResumeWorkingDirectory() {
        if (workingDirectory != null || resumeSessionId == null || resumeSessionId.isBlank()) {
            return;
        }
        try {
            ChatHistory.resolveWorkingDirectory(resumeSessionId)
                    .ifPresent(path -> workingDirectory = path);
        } catch (IOException e) {
            System.err.println("Warning: Could not resolve resumed chat directory: " + e.getMessage());
        }
    }

    @Override
    public Integer call() {
        // Handle --setup: run wizard and exit
        if (runSetup) {
            ChatConfig config = runSetupWizard();
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
        if (isResume) {
            inferResumeWorkingDirectory();
        }

        // Resolve role: --role flag > role selection menu > none
        String resolvedRole = resolveRole();

        if (sessionId == null || sessionId.isBlank()) {
            sessionId = newTranscriptUuid();
        }

        // Explicit action flags retain their existing behavior and skip the session wizard.
        boolean hasExplicitAction = isResume || (mode != null && !mode.isBlank())
                || (url != null && !url.isBlank()) || port != null || forceLocal || !startServer;

        // Load saved chat settings. The legacy direct-LLM path can still fall back
        // to env-based provider config; passthrough configs are CLI-agent/session
        // state and do not require provider credentials.
        ChatConfig config = globalConfig
                ? ChatConfig.loadGlobalOrFromEnv()
                : ChatConfig.loadOrFromEnv(effectiveWorkingDirectory());
        boolean configSelectedInThisRun = false;

        if (config == null && hasExplicitAction) {
            config = configFromExplicitRoute();
        }

        // ResumeCommand routes standard transcripts back through this command with
        // --mode standard. Restore the provider/model recorded for that transcript. Do not
        // turn a passthrough config into provider=kompile: that selects the HTTP chat-server
        // bootstrap and is not the local stdio/direct-chat path used by the CLI.
        config = normalizeResumeConfig(config, isResume);

        // Bare chat keeps the established wizard-first flow. The installed subprocess is
        // considered only after the user has selected Standard Chat and a provider.
        if (shouldRunSetupWizard(config, hasExplicitAction)) {
            config = runSetupWizard();
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

        // Resuming a transcript must stay on that session's local/stdio chat path. Never
        // silently replace it with the installed HTTP chat subprocess during resume.
        boolean startInstalledChatSubprocess = !isResume && config.isKompileServer()
                && shouldStartInstalledChatSubprocess(config,
                ChatInstanceBootstrap.isDistributionInstalled());

        return routeFromConfig(config, isResume, resolvedRole, configSelectedInThisRun,
                startInstalledChatSubprocess);
    }

    private ChatConfig runSetupWizard() {
        return globalConfig
                ? SetupWizard.runGlobal()
                : SetupWizard.run(ChatConfig.Scope.PROJECT, effectiveWorkingDirectory());
    }

    ChatConfig normalizeResumeConfig(ChatConfig config, boolean isResume) {
        String requestedMode = mode == null ? "" : mode.trim();
        if (!isResume || !"standard".equalsIgnoreCase(requestedMode)) {
            return config;
        }

        ChatConfig recorded = loadRecordedResumeConfig(config);
        if (recorded != null) {
            return recorded;
        }

        // A direct standard config is already usable. A passthrough-only config has no
        // model endpoint and must not be silently converted into the HTTP server route;
        // returning null lets the explicit resume flow ask for a real standard config.
        if (config != null && !"passthrough".equalsIgnoreCase(config.getChatMode())) {
            config.setChatMode("standard");
            return config;
        }
        return null;
    }

    private ChatConfig loadRecordedResumeConfig(ChatConfig fallback) {
        if (resumeSessionId == null || resumeSessionId.isBlank()) {
            return null;
        }
        Path metrics = KompileHome.homeDirectory().toPath().resolve("conversations")
                .resolve(resumeSessionId + ".metrics.json");
        if (!Files.isRegularFile(metrics)) {
            return null;
        }
        try {
            JsonNode session = JsonUtils.standardMapper().readTree(metrics.toFile()).path("session");
            String provider = session.path("provider").asText(null);
            String model = session.path("model").asText(null);
            if (provider == null || provider.isBlank() || model == null || model.isBlank()) {
                return null;
            }
            ChatConfig recorded = new ChatConfig(provider, null, model, null);
            recorded.setChatMode("standard");
            if (fallback != null && provider.equalsIgnoreCase(fallback.getProvider())) {
                recorded.setApiKey(fallback.getApiKey());
                recorded.setBaseUrl(fallback.getBaseUrl());
                recorded.setThinking(fallback.getThinking());
            }
            return recorded;
        } catch (IOException | RuntimeException ignored) {
            return null;
        }
    }

    boolean shouldRunSetupWizard(ChatConfig config, boolean hasExplicitAction) {
        return !hasExplicitAction || config == null;
    }

    boolean canUseInstalledChatSubprocessFallback() {
        if (!startServer || forceLocal || (url != null && !url.isBlank()) || port != null) {
            return false;
        }
        String normalizedMode = mode == null ? null : mode.trim().toLowerCase(Locale.ROOT);
        return normalizedMode == null || normalizedMode.isBlank() || "standard".equals(normalizedMode);
    }

    boolean shouldStartInstalledChatSubprocess(ChatConfig config,
                                               boolean distributionInstalled) {
        return config != null && config.isKompileServer() && distributionInstalled
                && canUseInstalledChatSubprocessFallback()
                && ChatInstanceBootstrap.isLoopbackHttpUrl(resolveServerUrl(config));
    }

    ChatConfig configFromExplicitRoute() {
        String normalizedMode = mode == null ? null : mode.trim().toLowerCase(Locale.ROOT);
        if ("passthrough".equals(normalizedMode)) {
            ChatConfig config = new ChatConfig(null, null, null, null);
            config.setChatMode("passthrough");
            if (agentName != null && !agentName.isBlank()) {
                config.setPassthroughAgent(agentName);
            }
            return config;
        }
        if ((url != null && !url.isBlank()) || port != null
                || (!startServer && !forceLocal
                && (normalizedMode == null || normalizedMode.isBlank()
                || "standard".equals(normalizedMode)))) {
            ChatConfig config = new ChatConfig("kompile", null, null, null);
            config.setChatMode("standard");
            return config;
        }
        return null;
    }

    /**
     * Route to the correct chat mode based on the resolved config.
     * Dispatches between passthrough (managed/direct), server, and local LLM modes.
     */
    private int routeFromConfig(ChatConfig config, boolean isResume, String resolvedRole,
                                boolean configSelectedInThisRun,
                                boolean startInstalledChatSubprocess) {
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

            // Decide ONCE whether this session runs enforced. A direct passthrough
            // config should remain a raw native-agent session unless the user passes
            // explicit rule flags. Enforcement is per-session opt-in: a project enforcer
            // config on disk never activates silently — the user is asked every run
            // (wizard answer for wizard runs, activation prompt otherwise).
            Path wd = effectiveWorkingDirectory();
            boolean hasExplicitRuleFlags = (enforcerRules != null && !enforcerRules.isBlank())
                    || (enforcerRuleFile != null && !enforcerRuleFile.isBlank());
            boolean allowEnforcement = shouldConsiderEnforcement(config, hasExplicitRuleFlags);
            EnforcerConfig enforcerConfig =
                    allowEnforcement ? EnforcerConfig.load(wd) : null;
            // Only THIS run's wizard answer counts; a persisted answer from an earlier
            // session must not re-activate enforcement without asking again.
            Boolean sessionChoice = configSelectedInThisRun ? config.getEnforcementEnabled() : null;
            if (allowEnforcement && !hasExplicitRuleFlags && sessionChoice == null
                    && enforcerConfig != null && enforcerConfig.isEnforcementEnabled()) {
                sessionChoice = EnforcerActivationPrompt
                        .confirmViaConsole(enforcerConfig);
            }
            boolean enforce = allowEnforcement
                    && ai.kompile.cli.main.chat.enforcer.EnforcerConfig.shouldActivate(
                    sessionChoice, hasExplicitRuleFlags, enforcerConfig);

            // Enforcement requires the managed REPL; otherwise honor the chosen style.
            boolean managed = config.isPassthroughManaged() || enforce;

            if (managed) {
                return runManagedPassthroughMode(agent, isResume, enforce);
            } else {
                return runDirectPassthroughMode(agent, isResume);
            }
        }

        // Explicit server routes remain connect-only for backwards compatibility.
        String targetUrl = resolveExplicitUrl();
        if (targetUrl != null) {
            return runServerMode(targetUrl, isResume, resolvedRole);
        }

        if (config.isKompileServer()) {
            String serverUrl = resolveServerUrl(config);
            if (serverUrl == null || serverUrl.isBlank()) {
                System.err.println("Kompile chat server URL is not configured.");
                System.err.println("Reconfigure with: kompile chat --setup");
                return 1;
            }

            if (startInstalledChatSubprocess) {
                try {
                    ChatInstanceBootstrap.StartupResult started = ChatInstanceBootstrap.ensureReady(
                            serverUrl, startupTimeoutSeconds);
                    serverUrl = started.chatUrl();
                } catch (ChatInstanceBootstrap.BootstrapException e) {
                    System.err.println("Could not start local Kompile chat: " + e.getMessage());
                    System.err.println("Use --no-start to connect without launching the subprocess, "
                            + "or run 'kompile chat --setup' for another provider.");
                    return 1;
                }
            }
            return runServerMode(serverUrl, isResume, resolvedRole);
        }

        if (config.isKompileLocalServing()) {
            return runKompileLocalServingMode(config, isResume, resolvedRole);
        }

        // Direct LLM API mode.
        return runLocalLlmMode(config, isResume, resolvedRole);
    }

    static boolean shouldConsiderEnforcement(ChatConfig config, boolean hasExplicitRuleFlags) {
        boolean directStyle = !config.isPassthroughManaged();
        boolean explicitEnforcement = hasExplicitRuleFlags
                || Boolean.TRUE.equals(config.getEnforcementEnabled());
        return !directStyle || explicitEnforcement;
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
            client.notifyInitialized();

            if (isResume) {
                System.out.println("Resuming conversation: " + sessionId);
                restoreConversation(client, targetUrl);
            } else {
                createChatSession(client);
                System.out.println("New conversation: " + sessionId);
            }

            System.out.println("Type /help for commands, /quit to exit.\n");

            ChatRepl repl = new ChatRepl(
                    client, targetUrl, sessionId, rag, agentName, memory, null,
                    effectiveWorkingDirectory());
            repl.setDangerouslySkipPermissions(dangerouslySkipPermissions);

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
     * First-party local model mode: start Kompile's low-overhead serving
     * subprocess with the selected model, then keep it alive for the normal
     * Standard Chat REPL. No full Kompile application instance is started.
     */
    private int runKompileLocalServingMode(
            ChatConfig config, boolean isResume, String assignedRole) {
        try (LocalServingRuntimePool.Lease runtime =
                     LocalServingRuntimePool.acquire(
                             config, startupTimeoutSeconds)) {
            runtime.applyTo(config);
            return runLocalLlmMode(config, isResume, assignedRole);
        } catch (KompileLocalServingBootstrap.BootstrapException e) {
            System.err.println("Could not start Kompile local serving: "
                    + e.getMessage());
            System.err.println("Reconfigure with 'kompile chat --setup' to use "
                    + "an external Ollama/OpenAI-compatible endpoint, a cloud provider, "
                    + "or a Kompile instance.");
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
                    config,     // LLM config for direct calls
                    effectiveWorkingDirectory()
            );
            repl.setDangerouslySkipPermissions(dangerouslySkipPermissions);

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
    private int runManagedPassthroughMode(String agent, boolean isResume, boolean enforce) {
        // The enforce / opt-out decision is made once, up front, in routeFromConfig — a
        // session that opted out is never re-escalated here from a stale project config.
        if (enforce) {
            return runEnforcedPassthroughMode(agent, isResume);
        }
        return runPlainManagedPassthrough(agent, isResume);
    }

    /**
     * Plain managed passthrough (no enforcement): the kompile REPL wraps the agent
     * subprocess with MCP tool injection, system prompt, skills, and metrics tracking.
     * Also the graceful-degrade target when an enforcer config cannot be activated.
     */
    private int runPlainManagedPassthrough(String agent, boolean isResume) {
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
            passthrough.workingDir = effectiveWorkingDirectory().toString();
            passthrough.skipPermissions = true;
            passthrough.injectTools = true;
            passthrough.kompileUrl = "";
            passthrough.mcpPort = 0;
            passthrough.model = model;
            passthrough.thinking = thinking;
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
     * Print a clear, actionable warning that rule enforcement could not be activated and
     * the session is starting WITHOUT it. The chat session must always start — a stale or
     * empty {@code .kompile/enforcer-config.json} must never trap the user out of chat.
     */
    private void warnEnforcementDegraded(Path wd, String reason) {
        Path cfg = ai.kompile.cli.main.chat.enforcer.EnforcerConfig.resolveConfigPath(wd);
        System.err.println();
        System.err.println("\033[33m  ⚠ Rule enforcement disabled for this session: " + reason + ".\033[0m");
        System.err.println("\033[2m    Config: " + cfg + "\033[0m");
        System.err.println("\033[2m    Run 'kompile enforcer init' to fix it, or delete the file to silence this.\033[0m");
        System.err.println("\033[2m    Continuing without enforcement...\033[0m");
        System.err.println();
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
            passthrough.workingDir = effectiveWorkingDirectory().toString();
            passthrough.skipPermissions = true;
            passthrough.injectTools = true;
            passthrough.kompileUrl = "";
            passthrough.mcpPort = 0;
            passthrough.model = model;
            passthrough.thinking = thinking;
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
    private int runEnforcedPassthroughMode(String agent, boolean isResume) {
        ObjectMapper objectMapper = JsonUtils.standardMapper();
        Path wd = effectiveWorkingDirectory();

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
            warnEnforcementDegraded(wd,
                    "no rules are defined (the enforcer config is present but empty)");
            return runPlainManagedPassthrough(agent, isResume);
        }

        EnforcerPolicy policy = new EnforcerPolicy(rules, effectiveMaxReprompts, false);
        HarnessConfig harnessConfig = HarnessConfig.load(objectMapper);

        // Choose evaluator
        EnforcerEvaluator evaluator;
        if (useKeywordMode) {
            KeywordEnforcerEvaluator kwEval = KeywordEnforcerEvaluator.fromPolicy(policy, objectMapper, projectEnforcerConfig);
            if (!kwEval.isAvailable()) {
                warnEnforcementDegraded(wd,
                        "no keyword rules could be parsed (use BAN:/STOP:/BAN_TOOL:/BAN_CMD: prefixes)");
                return runPlainManagedPassthrough(agent, isResume);
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
                warnEnforcementDegraded(wd, "no LLM judge backend is available "
                        + "(configure ~/.kompile/harness-config.json, pass --judge-provider/--judge-model, "
                        + "or switch the enforcer config to keyword mode)");
                return runPlainManagedPassthrough(agent, isResume);
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

        // Track every judgement made this session to a durable JSONL log, and (for an LLM
        // judge) capture the raw judge response so the managed judge session is observable.
        ai.kompile.cli.main.chat.enforcer.JudgementLog judgementLog =
                ai.kompile.cli.main.chat.enforcer.JudgementLog.forSession(runtimePolicy.getSessionId());
        if (evaluator instanceof EnforcerJudge enforcerJudge) {
            enforcerJudge.setJudgementLog(judgementLog);
        }
        service.setJudgementLog(judgementLog);

        // Apply the configured fallback policy (judge unavailable / mid-turn failure).
        ai.kompile.cli.main.chat.enforcer.EnforcerFallbackPolicy fallbackPolicy =
                ai.kompile.cli.main.chat.enforcer.EnforcerFallbackPolicy.parse(
                        projectEnforcerConfig != null ? projectEnforcerConfig.getJudgeFallbackPolicy() : null);
        service.setFallbackPolicy(fallbackPolicy, objectMapper);

        // Make the enforced session non-opaque: announce what is enforcing,
        // which judge backend is in use, and where judgements are recorded.
        int ruleCount = (int) rules.lines().filter(l -> !l.isBlank()).count();
        String enfSessionId = runtimePolicy.toEnvironment()
                .getOrDefault("KOMPILE_ENFORCER_SESSION_ID", "?");
        System.out.println();
        System.out.println("\033[1m\033[36m  🛡 Enforced session active\033[0m");
        System.out.println("     mode:       " + (useKeywordMode ? "keyword" : "LLM judge"));
        System.out.println("     backend:    " + evaluator.describe());
        System.out.println("     rules:      " + ruleCount);
        System.out.println("     session:    " + enfSessionId);
        System.out.println("     judgements: ~/.kompile/sessions/" + enfSessionId + "/judgements.jsonl");
        System.out.println();

        try {
            // Delegate to the single REPL with enforcer fields set
            EmulatedPassthroughCommand passthrough = new EmulatedPassthroughCommand();
            passthrough.agent = agent;
            passthrough.workingDir = wd.toString();
            passthrough.skipPermissions = true;
            passthrough.injectTools = true;
            passthrough.kompileUrl = "";
            passthrough.mcpPort = 0;
            passthrough.model = model;
            passthrough.thinking = thinking;
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
            System.out.printf("  %-36s  %-20s  agent=%-8s  %s%n",
                    c.sessionId(), c.started(), c.agent(),
                    c.title().isEmpty() ? "(empty)" : c.title());
        }
        System.out.println();
        System.out.println("Resume with: kompile chat --resume <transcript-uuid-or-id>");
        System.out.println("Continue last: kompile chat --continue");
        return 0;
    }

    /**
     * Restores a previous conversation to the server-side session so the LLM
     * has full context. The interactive ChatRepl renders the parsed turns after
     * its TUI is initialized; this method must not print the physical transcript.
     */
    private void restoreConversation(McpSseClient client, String targetUrl) throws Exception {
        ChatHistory history = new ChatHistory(sessionId);
        List<ChatHistory.Turn> turns = history.readTurns();

        if (turns.isEmpty()) {
            createChatSession(client);
            System.out.println("(no previous messages to restore)");
            return;
        }

        // Rendering is deferred to ChatRepl so the parsed turns are drawn in
        // Kompile's scroll region once the TUI owns the terminal.
        System.out.println("Restoring " + turns.size() + " turns to server...");
        createChatSession(client, turns);
        System.out.println("Restored conversation context.");
    }

    /** Resolve URL only from explicit --url or --port flags. */
    private String resolveExplicitUrl() {
        if (url != null && !url.isBlank()) {
            return url;
        }
        if (port != null) {
            return "http://localhost:" + port;
        }
        return null;
    }

    String resolveServerUrl(ChatConfig config) {
        String explicit = resolveExplicitUrl();
        if (explicit != null) {
            return explicit;
        }
        if (config != null && config.isKompileServer()) {
            String configured = config.resolveBaseUrl();
            if (configured != null && !configured.isBlank()) {
                return configured;
            }
        }
        return KompileServiceEndpoints.resolve(KompileService.CHAT).baseUrl();
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
        RoleManager roleManager = new RoleManager(effectiveWorkingDirectory());
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
        createChatSession(client, List.of());
    }

    private void createChatSession(McpSseClient client, List<ChatHistory.Turn> turns) throws Exception {
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
        if (turns != null && !turns.isEmpty()) {
            var history = args.putArray("history");
            for (ChatHistory.Turn turn : turns) {
                ObjectNode item = history.addObject();
                item.put("role", turn.role());
                item.put("content", turn.content());
            }
        }
        client.callTool("create_chat_session", args);
    }
}
