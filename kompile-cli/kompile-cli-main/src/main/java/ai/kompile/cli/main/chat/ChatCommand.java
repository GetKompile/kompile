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
import ai.kompile.cli.main.chat.agent.ProjectChatContext;
import ai.kompile.cli.main.chat.agent.SubprocessAgentRunner;
import ai.kompile.cli.main.chat.config.ChatConfig;
import ai.kompile.cli.main.chat.config.DirectLlmClient;
import ai.kompile.cli.main.chat.config.ProviderPromptCacheCapabilities;
import ai.kompile.cli.main.chat.config.SetupWizard;
import ai.kompile.cli.main.chat.config.SystemPromptManager;
import ai.kompile.cli.main.chat.enforcer.*;
import ai.kompile.cli.main.chat.exec.ChatAttachmentLoader;
import ai.kompile.cli.main.chat.exec.ChatHarnessCapabilities;
import ai.kompile.cli.main.chat.exec.ExecJsonEvents;
import ai.kompile.cli.main.chat.exec.HeadlessAgentRunner;
import ai.kompile.cli.main.chat.exec.HeadlessRunEvent;
import ai.kompile.cli.main.chat.exec.PromptResolver;
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
import java.io.PrintStream;
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

    private static final int RESTART_MANAGED_CHAT = Integer.MIN_VALUE;
    private TranscriptLogScope transcriptLogScope;

    @CommandLine.Spec
    private CommandLine.Model.CommandSpec commandSpec;

    @CommandLine.Option(names = "--web", description = "Start a fresh installed CHAT web UI (LAN-accessible by default) and print its local URL using CLI config (CHAT JAR tier only). Combine with --setup to configure first.")
    private boolean web;

    @CommandLine.Option(names = "--open-browser", description = "Also open the web UI in a local browser (requires --web).")
    private boolean openBrowser;

    @FunctionalInterface
    interface StandardSessionRunner {
        boolean run(String transcriptUuid, boolean resume) throws Exception;
    }

    @CommandLine.Parameters(arity = "0..*", paramLabel = "PROMPT",
            description = "Prompt text for a non-interactive turn. Without a prompt, starts the REPL.")
    private List<String> promptParts = new ArrayList<>();

    @CommandLine.Option(names = {"--prompt"}, paramLabel = "TEXT",
            description = "Prompt for a non-interactive turn (alternative to positional PROMPT).")
    private String headlessPrompt;

    @CommandLine.Option(names = {"--json"}, defaultValue = "false",
            description = "Emit an ordered streaming JSONL event stream to stdout.")
    private boolean jsonOutput;

    @CommandLine.Option(names = {"--output-format"}, paramLabel = "FORMAT",
            description = "Non-interactive output format: text or stream-json.")
    private String outputFormat;

    @CommandLine.Option(names = {"--output-last-message"}, paramLabel = "FILE",
            description = "Also write the final assistant response to FILE.")
    private Path outputLastMessage;

    @CommandLine.Option(names = {"--timeout"}, defaultValue = "0", paramLabel = "SECONDS",
            description = "Maximum non-interactive run time in seconds (0 = no limit).")
    private long timeoutSeconds;

    @CommandLine.Option(names = {"--attachment"}, paramLabel = "FILE",
            description = "Attach a file to a non-interactive direct-model turn.")
    private List<Path> attachmentPaths = new ArrayList<>();

    @CommandLine.Option(names = {"--capabilities"}, defaultValue = "false",
            description = "Print the configured kompile-cli-main chat harness as JSON and exit.")
    private boolean printCapabilities;

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
            "Model override for standard or passthrough chat.",
            "Examples: haiku, gpt-5.2-codex, anthropic/claude-haiku-4-5"
    })
    private String model;

    @CommandLine.Option(names = {"--thinking", "--effort"}, description =
            "Thinking/effort override for standard or passthrough chat")
    private String thinking;

    @CommandLine.Option(names = {"--provider"}, description =
            "Direct-model provider override (credentials still come from managed config or environment).")
    private String provider;

    @CommandLine.Option(names = {"--auth"}, paramLabel = "METHOD", description =
            "Authentication route for --provider: none, native, oauth, or api-key.")
    private String authenticationMethod;

    @CommandLine.Option(names = "--auth-scope", paramLabel = "SCOPE",
            description = "Credential selection scope: session (pinned account) or global (vendor default).")
    private String authenticationScope;

    @CommandLine.Option(names = "--credential", paramLabel = "NAME",
            description = "Named managed credential to pin to this session; does not change other sessions.")
    private String credentialName;

    @CommandLine.Option(names = {"--prompt-cache-retention"}, paramLabel = "POLICY",
            description = "Prompt-prefix cache policy: none, short, or long.")
    private String promptCacheRetention;

    @CommandLine.Option(names = {"--base-url"}, description =
            "Direct-model API base URL override (use --url for a Kompile chat server).")
    private String providerBaseUrl;

    @CommandLine.Option(names = {"--auto-compact"}, negatable = true,
            description = "Enable or disable automatic conversation compaction.")
    private Boolean autoCompact;

    @CommandLine.Option(names = {"--auto-compact-threshold"}, paramLabel = "FRACTION",
            description = "Context fraction at which automatic compaction may begin.")
    private Double autoCompactThreshold;

    @CommandLine.Option(names = {"--compaction-reserve-tokens"}, paramLabel = "TOKENS",
            description = "Input headroom reserved during compaction.")
    private Integer compactionReserveTokens;

    @CommandLine.Option(names = {"--context-window-tokens"}, paramLabel = "TOKENS",
            description = "Override the selected model context window.")
    private Integer contextWindowTokens;

    @CommandLine.Option(names = {"--max-output-tokens"}, paramLabel = "TOKENS",
            description = "Override the selected model output-token limit.")
    private Integer maxOutputTokens;

    @CommandLine.Option(names = {"--rag"}, negatable = true, fallbackValue = "true",
            description = "Enable RAG for chat (default: true)", defaultValue = "true")
    private boolean rag;

    @CommandLine.Option(names = {"--resume", "-r"}, description =
            "Resume a previous conversation by transcript UUID or legacy session ID")
    private String resumeSessionId;

    @CommandLine.Option(names = "--internal-managed-resume", hidden = true)
    private boolean internalManagedResume;

    @CommandLine.Option(names = {"--continue", "-c"}, description = "Continue the most recent conversation", defaultValue = "false")
    private boolean continueLastSession;

    @CommandLine.Option(names = {"--list", "-l"}, description = "List saved conversations and exit", defaultValue = "false")
    private boolean listConversations;

    @CommandLine.Option(names = {"--memory"}, negatable = true, fallbackValue = "true",
            description = "Enable memory (default: true)", defaultValue = "true")
    private boolean memory;

    @CommandLine.Option(names = {"--dangerously-skip-permissions"}, defaultValue = "false",
            description = "Allow every standard-chat tool permission for this session without prompting")
    private boolean dangerouslySkipPermissions;

    @CommandLine.Option(names = {"--local"}, description = "Force no-instance mode (Kompile serving subprocess or direct model API)", defaultValue = "false")
    private boolean forceLocal;

    @CommandLine.Option(names = "--multi-session", defaultValue = "false", description = {
            "Opt in to in-process multi-session direct standard chat in this working directory.",
            "/sessions lists; /session new, /session N, /session close manage live chats.",
            "/quit, Ctrl-C or EOF closes the selected chat; the last close exits. /clear replaces it.",
            "No server, local-serving, passthrough, nested resume/menu/setup/restart, or directory changes."
    })
    private boolean multiSession;

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

    boolean isRagEnabled() {
        return rag;
    }

    boolean isMemoryEnabled() {
        return memory;
    }

    static String newTranscriptUuid() {
        return UUID.randomUUID().toString();
    }

    /**
     * Runs consecutive standard-chat transcripts without returning from the CLI command.
     * A runner result of {@code true} is the /clear signal; the next iteration receives
     * a new UUID and is never treated as a resume.
     */
    static String runStandardSessionLoop(String initialTranscriptUuid, boolean resume,
                                         StandardSessionRunner runner) throws Exception {
        String currentTranscriptUuid = initialTranscriptUuid;
        boolean currentResume = resume;
        while (true) {
            if (!runner.run(currentTranscriptUuid, currentResume)) {
                return currentTranscriptUuid;
            }
            String previousTranscriptUuid = currentTranscriptUuid;
            do {
                currentTranscriptUuid = newTranscriptUuid();
            } while (currentTranscriptUuid.equals(previousTranscriptUuid));
            currentResume = false;
        }
    }

    private void activateTranscriptLog(String transcriptUuid, boolean resumed)
            throws IOException {
        if (transcriptLogScope != null) {
            transcriptLogScope.switchTo(
                    transcriptUuid, effectiveWorkingDirectory(), resumed);
        }
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
        if (openBrowser && !web) return printError("--open-browser requires --web.", 2);
        if (web) return runWebHandoff();
        if (multiSession) {
            String unsupported = multiSessionOptionError();
            if (unsupported != null) return printError(unsupported, 2);
        }
        if (printCapabilities) {
            System.out.println(ChatHarnessCapabilities.toJson(
                    ChatHarnessCapabilities.inspect(effectiveWorkingDirectory(), globalConfig)));
            return 0;
        }
        boolean headless = isHeadlessRequested();
        HeadlessAgentRunner.OutputMode headlessMode = null;
        String resolvedPrompt = null;
        if (headless) {
            try {
                headlessMode = resolveHeadlessOutputMode();
            } catch (IllegalArgumentException e) {
                return headlessError(e.getMessage(), 2);
            }
            if (runSetup || listConversations || showRoleMenu) {
                return headlessError(
                        "--setup, --list, and --roles are interactive actions and cannot be combined with a prompt or streaming output.",
                        2);
            }
            try {
                resolvedPrompt = resolveHeadlessPrompt();
            } catch (IllegalArgumentException | IOException e) {
                return headlessError(e.getMessage(), 2);
            }
            if ((blankToNull(url) != null || port != null) && blankToNull(provider) != null) {
                return headlessError(
                        "--url/--port selects a Kompile server and cannot be combined with --provider. Use --base-url for a direct provider endpoint.",
                        2);
            }
        }
        if (blankToNull(authenticationMethod) != null && blankToNull(provider) == null) {
            String message = "--auth requires --provider.";
            return headless ? headlessError(message, 2) : printError(message, 2);
        }

        // Handle --setup: run wizard and exit
        if (runSetup) {
            SetupWizard.SetupResult result = runSetupWizard();
            if (result == null) return 1;
            return result.destination() == SetupWizard.Destination.BROWSER
                    ? runWebHandoff(result.config()) : 0;
        }

        // Handle --list: just print conversations and exit
        if (listConversations) {
            return listSavedConversations();
        }

        // Handle --continue: find most recent conversation
        if (continueLastSession) {
            List<ChatHistory.ConversationSummary> convos = ChatHistory.listConversations();
            if (convos.isEmpty()) {
                return headless
                        ? headlessError("No saved conversations found.", 1)
                        : printError("No saved conversations found.", 1);
            }
            resumeSessionId = convos.get(0).sessionId();
        }

        // Handle --resume: use existing session ID
        if (resumeSessionId != null && !resumeSessionId.isBlank()) {
            sessionId = resumeSessionId;
            if (!ChatHistory.exists(sessionId)) {
                String message = "No saved conversation found for session: " + sessionId;
                return headless ? headlessError(message, 1) : printError(message, 1);
            }
        }

        boolean isResume = resumeSessionId != null && !resumeSessionId.isBlank();
        if (isResume) {
            inferResumeWorkingDirectory();
        }
        if (multiSession && !effectiveWorkingDirectory().equals(
                Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize())) {
            return printError("--multi-session cannot change working directory (including a resumed transcript's project). Start chat from that project directory.", 2);
        }

        // Resolve role: --role flag > role selection menu > none
        String resolvedRole = headless ? blankToNull(role) : resolveRole();

        if (sessionId == null || sessionId.isBlank()) {
            sessionId = newTranscriptUuid();
        }

        try (TranscriptLogScope scope = multiSession ? null : TranscriptLogScope.open(
                sessionId, effectiveWorkingDirectory(), isResume)) {
            transcriptLogScope = scope;

        // Explicit action flags retain their existing behavior and skip the session wizard.
        boolean hasExplicitAction = multiSession || isResume || (mode != null && !mode.isBlank())
                || (url != null && !url.isBlank()) || port != null || forceLocal || !startServer
                || headless || blankToNull(provider) != null || blankToNull(providerBaseUrl) != null
                || blankToNull(authenticationMethod) != null || blankToNull(authenticationScope) != null
                || blankToNull(credentialName) != null;

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
        if (config == null && blankToNull(provider) != null) {
            config = new ChatConfig(provider, null, blankToNull(model), blankToNull(providerBaseUrl));
            config.setChatMode("standard");
        }

        // ResumeCommand routes standard transcripts back through this command with
        // --mode standard. Restore the provider/model recorded for that transcript. Do not
        // turn a passthrough config into provider=kompile: that selects the HTTP chat-server
        // bootstrap and is not the local stdio/direct-chat path used by the CLI.
        config = normalizeResumeConfig(config, isResume);

        // Bare chat keeps the established wizard-first flow. The installed subprocess is
        // considered only after the user has selected Standard Chat and a provider.
        if (headless && config == null) {
            return headlessError(
                    "No chat configuration found. Run `kompile chat --setup` first or provide --provider and --model.",
                    1);
        }
        if (multiSession && config == null) {
            return printError("--multi-session requires a configured direct provider. Run `kompile chat --setup` separately first.", 2);
        }
        if (!multiSession && !headless && shouldRunSetupWizard(config, hasExplicitAction)) {
            SetupWizard.SetupResult result = runSetupWizard();
            config = result == null ? null : result.config();
            if (result != null && result.destination() == SetupWizard.Destination.BROWSER) {
                return runWebHandoff(config);
            }
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
        if (!multiSession && shouldOfferProjectProfile(headless, configSelectedInThisRun, isResume)
                && System.console() != null) {
            SetupWizard.ProfileSelection selection = SetupWizard.selectProjectProfile(
                    effectiveWorkingDirectory(), blankToNull(provider) != null
                            && blankToNull(mode) == null ? "standard" : config.getChatMode());
            if (selection.cancelled()) return printError("Profile selection cancelled.", 1);
            if (selection.config() != null) {
                config = selection.config();
                config.save(globalConfig ? ChatConfig.Scope.GLOBAL : ChatConfig.Scope.PROJECT,
                        effectiveWorkingDirectory());
                configSelectedInThisRun = true;
            }
        }
        try {
            applyCommandLineOverrides(config);
        } catch (IllegalArgumentException e) {
            return headless ? headlessError(e.getMessage(), 2) : printError(e.getMessage(), 2);
        }
        if (!isWizardActionMode(config)
                && !"passthrough".equalsIgnoreCase(config.getChatMode())
                && !config.isValid()) {
            String message = "Incomplete Standard Chat configuration for provider '"
                    + config.getProvider() + "'. Run `kompile chat --setup` or provide a compatible model and credential.";
            return headless ? headlessError(message, 2) : printError(message, 2);
        }

        if (multiSession) {
            if (!MultiChatSessionHost.supports(config)) {
                return printError("--multi-session supports only direct standard chat, not server, local-serving, or passthrough routes. Use --mode standard --provider <provider>.", 2);
            }
            try {
                MultiChatSessionHost.run(config, effectiveWorkingDirectory(), sessionId, isResume,
                        agentName, memory, dangerouslySkipPermissions, resolvedRole);
                return 0;
            } catch (Exception e) {
                return printError("Multi-session chat failed: " + e.getMessage(), 1);
            }
        }

        // Resuming a transcript must stay on that session's local/stdio chat path. Never
        // silently replace it with the installed HTTP chat subprocess during resume.
        boolean startInstalledChatSubprocess = !isResume && config.isKompileServer()
                && shouldStartInstalledChatSubprocess(config,
                ChatInstanceBootstrap.isDistributionInstalled());

        if (headless) {
            if (!"passthrough".equalsIgnoreCase(config.getChatMode()) && !config.isKompileServer())
                config.bindSession(sessionId);
            return runHeadlessConfigured(config, isResume, resolvedRole, resolvedPrompt,
                    headlessMode, startInstalledChatSubprocess);
        }
        return routeFromConfig(config, isResume, resolvedRole, configSelectedInThisRun,
                startInstalledChatSubprocess);
        } catch (IOException e) {
            System.err.println("Cannot start chat without a transcript log: " + e.getMessage());
            return 1;
        } finally {
            transcriptLogScope = null;
        }
    }

    String webOptionError() {
        if (!promptParts.isEmpty()) return "--web does not accept a prompt; send it in the browser.";
        if (commandSpec != null && commandSpec.commandLine().getParseResult() != null) {
            var allowed = java.util.Set.of("--web", "--open-browser", "--setup", "--global-config", "--working-dir", "--startup-timeout");
            for (var option : commandSpec.commandLine().getParseResult().matchedOptions()) {
                if (!allowed.contains(option.longestName())) {
                    return "--web cannot be combined with " + option.longestName()
                            + "; configure saved defaults with --setup instead.";
                }
            }
        }
        return null;
    }

    static String webConfigError(ChatConfig config) {
        if (config == null) return "Setup cancelled, save failed, or no saved chat configuration found.";
        if (!"standard".equalsIgnoreCase(config.getChatMode()) || config.isKompileServer()) {
            return "--web requires a saved Standard Chat direct/local provider, not passthrough, resume, or a Kompile server.";
        }
        return config.isValid() ? null : "Incomplete chat configuration; run kompile chat --setup --web.";
    }

    ChatConfig selectWebConfig(Path directory) {
        ChatConfig.Scope scope = globalConfig ? ChatConfig.Scope.GLOBAL : ChatConfig.Scope.PROJECT;
        ChatConfig saved = globalConfig ? ChatConfig.loadGlobalOrFromEnv() : ChatConfig.loadOrFromEnv(directory);
        return runSetup || saved == null ? SetupWizard.runForWeb(scope, directory) : saved;
    }

    ChatInstanceBootstrap.StartupResult startWeb(Path directory) throws Exception {
        return ChatInstanceBootstrap.startWeb(directory, globalConfig, startupTimeoutSeconds);
    }

    void openWebBrowser(String address) {
        // Browser is optional (headless/SSH hosts can use the printed URL); no credentials in URL.
        try {
            String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
            ProcessBuilder opener = os.contains("mac") ? new ProcessBuilder("open", address)
                    : os.contains("win") ? new ProcessBuilder("rundll32", "url.dll,FileProtocolHandler", address)
                    : new ProcessBuilder("xdg-open", address);
            Process process = opener.redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD).start();
            if (!process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)) process.destroyForcibly();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException ignored) { }
    }

    private int runWebHandoff() {
        return runWebHandoff(null);
    }

    private int runWebHandoff(ChatConfig wizardConfig) {
        String error = webOptionError();
        if (error != null) return printError(error, 2);
        try {
            Path directory = effectiveWorkingDirectory().toRealPath();
            if (!Files.isDirectory(directory)) return printError("Not a working directory: " + directory, 2);
            ChatConfig config = wizardConfig != null ? wizardConfig : selectWebConfig(directory);
            error = webConfigError(config);
            if (error != null) return printError(error, 1);
            ChatInstanceBootstrap.StartupResult result = startWeb(directory);
            System.out.println("Web chat: " + result.chatUrl());
            System.out.println("Working directory: " + directory + "; config scope: "
                    + (globalConfig ? "global" : "project")
                    + ". New sessions bind current CLI config on their first turn; resumed sessions retain their pins.");
            if (openBrowser) openWebBrowser(result.chatUrl());
            return 0;
        } catch (Exception e) {
            return printError("Web chat handoff failed: " + e.getMessage(), 1);
        }
    }

    String multiSessionOptionError() {
        if (isHeadlessRequested() || printCapabilities || runSetup || listConversations || showRoleMenu
                || outputLastMessage != null || timeoutSeconds != 0 || !attachmentPaths.isEmpty()) {
            return "--multi-session is an interactive chat mode; it cannot be combined with headless output, setup, listing, capabilities, or role menus.";
        }
        if (blankToNull(url) != null || port != null || internalManagedResume
                || (blankToNull(mode) != null && !"standard".equalsIgnoreCase(mode))) {
            return "--multi-session supports only direct standard chat; server and passthrough options are unsupported.";
        }
        if (workingDirectory != null && !effectiveWorkingDirectory().equals(
                Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize())) {
            return "--multi-session cannot change working directory. Start chat from that project directory.";
        }
        return null;
    }

    boolean isHeadlessRequested() {
        return jsonOutput || blankToNull(outputFormat) != null
                || headlessPrompt != null
                || (promptParts != null && !promptParts.isEmpty());
    }

    HeadlessAgentRunner.OutputMode resolveHeadlessOutputMode() {
        String requested = blankToNull(outputFormat);
        if (jsonOutput && requested != null
                && !"json".equalsIgnoreCase(requested)
                && !"jsonl".equalsIgnoreCase(requested)
                && !"stream-json".equalsIgnoreCase(requested)) {
            throw new IllegalArgumentException(
                    "--json cannot be combined with --output-format " + requested + ".");
        }
        if (jsonOutput) return HeadlessAgentRunner.OutputMode.JSON;
        if (requested == null || "text".equalsIgnoreCase(requested)) {
            return HeadlessAgentRunner.OutputMode.TEXT;
        }
        if ("json".equalsIgnoreCase(requested)
                || "jsonl".equalsIgnoreCase(requested)
                || "stream-json".equalsIgnoreCase(requested)) {
            return HeadlessAgentRunner.OutputMode.JSON;
        }
        throw new IllegalArgumentException(
                "Unsupported --output-format '" + requested + "'. Use text or stream-json.");
    }

    private String resolveHeadlessPrompt() throws IOException {
        if (blankToNull(headlessPrompt) != null && promptParts != null && !promptParts.isEmpty()) {
            throw new IllegalArgumentException(
                    "Use either --prompt or positional PROMPT arguments, not both.");
        }
        if (headlessPrompt != null) {
            if (headlessPrompt.isBlank()) {
                throw new IllegalArgumentException("--prompt cannot be blank.");
            }
            return headlessPrompt.trim();
        }
        return PromptResolver.resolve(promptParts, System.in);
    }

    void applyCommandLineOverrides(ChatConfig config) {
        if (config == null) return;
        if (blankToNull(provider) != null) {
            String resolvedProvider = resolveProviderOverride(config);
            boolean providerChanged = config.getProvider() == null
                    || !resolvedProvider.equalsIgnoreCase(config.getProvider());
            config.setProvider(resolvedProvider);
            if (providerChanged) {
                // Provider-bound state must never bleed into a new endpoint. Managed
                // credentials are resolved lazily for the newly selected provider.
                config.setApiKey(null);
                config.setBaseUrl(null);
                if (blankToNull(model) == null) config.setModel(null);
                if (blankToNull(thinking) == null) config.setThinking(null);
                config.setFastMode(false);
                if (contextWindowTokens == null) config.setContextWindowTokens(0);
                if (maxOutputTokens == null) config.setMaxOutputTokens(0);
                if (blankToNull(authenticationMethod) == null) {
                    config.setAuthenticationMethod(null);
                }
            }
            if (blankToNull(authenticationMethod) != null) {
                SetupWizard.AuthMethod selectedAuth = parseAuthMethod(authenticationMethod);
                if (selectedAuth != SetupWizard.AuthMethod.API_KEY) {
                    config.setApiKey(null);
                }
                config.setAuthenticationMethod(selectedAuth.name()
                        .toLowerCase(Locale.ROOT).replace('_', '-'));
            }
            if (blankToNull(mode) == null) {
                config.setChatMode("standard");
            }
        }
        if (blankToNull(authenticationScope) != null) config.setAuthenticationScope(authenticationScope.trim());
        if (blankToNull(credentialName) != null) {
            if ("global".equals(authenticationScope))
                throw new IllegalArgumentException("--credential requires session scope; use /auth global to change a vendor default");
            config.setAuthenticationScope("session");
            config.setCredentialName(credentialName.trim());
        }
        if (blankToNull(providerBaseUrl) != null) config.setBaseUrl(providerBaseUrl.trim());
        if (blankToNull(model) != null) {
            if (!model.trim().equals(config.getModel()) && blankToNull(thinking) == null) {
                config.setThinking(null);
            }
            config.setModel(model.trim());
        }
        if (!config.supportsFastMode()) config.setFastMode(false);
        if (blankToNull(thinking) != null) config.setThinking(thinking.trim());
        if (blankToNull(promptCacheRetention) != null) {
            config.setPromptCacheRetention(parsePromptCacheRetention(promptCacheRetention));
        }
        if (autoCompact != null) config.setAutoCompactEnabled(autoCompact);
        if (autoCompactThreshold != null) config.setAutoCompactThreshold(autoCompactThreshold);
        if (compactionReserveTokens != null) {
            config.setCompactionReserveTokens(compactionReserveTokens);
        }
        if (contextWindowTokens != null) config.setContextWindowTokens(contextWindowTokens);
        if (maxOutputTokens != null) config.setMaxOutputTokens(maxOutputTokens);
    }

    private String resolveProviderOverride(ChatConfig config) {
        String requested = provider.trim();
        if (blankToNull(authenticationMethod) != null) {
            String vendor = SetupWizard.vendorForProvider(requested);
            return SetupWizard.resolveProviderForAuth(
                    vendor, parseAuthMethod(authenticationMethod));
        }
        String activeProvider = config.getProvider();
        String activeVendor = SetupWizard.vendorForProvider(activeProvider);
        return activeProvider != null && requested.equalsIgnoreCase(activeVendor)
                ? activeProvider : requested;
    }

    private static SetupWizard.AuthMethod parseAuthMethod(String value) {
        String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT)
                .replace('_', '-');
        return switch (normalized) {
            case "none" -> SetupWizard.AuthMethod.NONE;
            case "native", "cli" -> SetupWizard.AuthMethod.NATIVE;
            case "oauth", "subscription", "sub" -> SetupWizard.AuthMethod.OAUTH;
            case "api-key", "apikey", "key" -> SetupWizard.AuthMethod.API_KEY;
            default -> throw new IllegalArgumentException(
                    "Unsupported --auth '" + value
                            + "'. Use none, native, oauth, or api-key.");
        };
    }

    private static String parsePromptCacheRetention(String value) {
        String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "none", "short", "long" ->
                    ProviderPromptCacheCapabilities.Retention.from(normalized).wireValue();
            default -> throw new IllegalArgumentException(
                    "Unsupported --prompt-cache-retention '" + value
                            + "'. Use none, short, or long.");
        };
    }

    private int runHeadlessConfigured(
            ChatConfig config, boolean isResume, String resolvedRole, String prompt,
            HeadlessAgentRunner.OutputMode outputMode,
            boolean startInstalledChatSubprocess) {
        if ("passthrough".equalsIgnoreCase(config.getChatMode())) {
            return headlessError(
                    "Streaming JSON currently requires standard chat mode; use --mode standard with a configured provider or Kompile server.",
                    2);
        }

        if (forceLocal && config.isKompileServer()) {
            return headlessError(
                    "--local requires a direct, external-local, or first-party local provider. Supply --provider and --model or run `kompile chat --setup`.",
                    2);
        }

        String serverUrl = resolveExplicitUrl();
        if (serverUrl == null && config.isKompileServer()) {
            serverUrl = resolveServerUrl(config);
            if (serverUrl == null || serverUrl.isBlank()) {
                return headlessError("Kompile chat server URL is not configured.", 1);
            }
            if (startInstalledChatSubprocess) {
                PrintStream previousOut = redirectStartupOutput(outputMode);
                String startupError = null;
                try {
                    serverUrl = ChatInstanceBootstrap.ensureReady(
                            serverUrl, startupTimeoutSeconds).chatUrl();
                } catch (ChatInstanceBootstrap.BootstrapException e) {
                    startupError = "Could not start local Kompile chat: " + e.getMessage();
                } finally {
                    restoreStartupOutput(previousOut);
                }
                if (startupError != null) return headlessError(startupError, 1);
            }
        }

        // Explicit --url/--port has the same precedence as interactive chat, even
        // when the saved configuration describes a first-party local runtime.
        if (serverUrl != null) {
            return runHeadlessTurn(
                    config, serverUrl, isResume, resolvedRole, prompt, outputMode);
        }

        if (config.isKompileLocalServing()) {
            LocalServingRuntimePool.Lease acquired = null;
            String startupError = null;
            PrintStream previousOut = redirectStartupOutput(outputMode);
            try {
                acquired = LocalServingRuntimePool.acquire(config, startupTimeoutSeconds);
            } catch (KompileLocalServingBootstrap.BootstrapException e) {
                startupError = "Could not start Kompile local serving: " + e.getMessage();
            } finally {
                restoreStartupOutput(previousOut);
            }
            if (startupError != null) return headlessError(startupError, 1);
            try (LocalServingRuntimePool.Lease runtime = acquired) {
                runtime.applyTo(config);
            }
            return runHeadlessTurn(
                    config, null, isResume, resolvedRole, prompt, outputMode);
        }
        return runHeadlessTurn(
                config, serverUrl, isResume, resolvedRole, prompt, outputMode);
    }

    private int runHeadlessTurn(
            ChatConfig config, String serverUrl, boolean isResume, String resolvedRole,
            String prompt, HeadlessAgentRunner.OutputMode outputMode) {
        List<DirectLlmClient.AttachmentInput> attachments;
        try {
            attachments = ChatAttachmentLoader.load(attachmentPaths);
        } catch (IOException e) {
            return headlessError("Could not load attachment: " + e.getMessage(), 2);
        }
        HeadlessAgentRunner.Options options = new HeadlessAgentRunner.Options(
                prompt,
                sessionId,
                isResume,
                blankToNull(agentName),
                blankToNull(model),
                outputMode,
                effectiveWorkingDirectory(),
                Math.max(0L, timeoutSeconds) * 1000L,
                outputLastMessage,
                null,
                null,
                null,
                config,
                blankToNull(serverUrl),
                rag,
                memory,
                resolvedRole,
                dangerouslySkipPermissions,
                attachments);
        return new HeadlessAgentRunner().run(options).exitCode();
    }

    private int headlessError(String message, int exitCode) {
        if (jsonOutput || "json".equalsIgnoreCase(outputFormat)
                || "jsonl".equalsIgnoreCase(outputFormat)
                || "stream-json".equalsIgnoreCase(outputFormat)) {
            System.out.println(ExecJsonEvents.event(
                    JsonUtils.standardMapper(),
                    HeadlessRunEvent.failed(sessionId, message, exitCode).withSequence(1)));
        } else {
            System.err.println(message);
        }
        return exitCode;
    }

    private static PrintStream redirectStartupOutput(HeadlessAgentRunner.OutputMode mode) {
        if (mode != HeadlessAgentRunner.OutputMode.JSON) return null;
        PrintStream previous = System.out;
        System.setOut(System.err);
        return previous;
    }

    private static void restoreStartupOutput(PrintStream previous) {
        if (previous != null) System.setOut(previous);
    }

    private static int printError(String message, int exitCode) {
        System.err.println(message);
        return exitCode;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    SetupWizard.SetupResult runSetupWizard() {
        ChatConfig.Scope scope = globalConfig ? ChatConfig.Scope.GLOBAL : ChatConfig.Scope.PROJECT;
        // Resume and other terminal-only flags cannot be carried into a fresh browser chat.
        if (webOptionError() != null) {
            ChatConfig config = SetupWizard.run(scope, effectiveWorkingDirectory());
            return config == null ? null : new SetupWizard.SetupResult(config, SetupWizard.Destination.TERMINAL);
        }
        return SetupWizard.runWithDestination(scope, effectiveWorkingDirectory());
    }

    ChatConfig normalizeResumeConfig(ChatConfig config, boolean isResume) {
        String requestedMode = mode == null ? "" : mode.trim();
        if (isResume && resumeSessionId != null && !"passthrough".equalsIgnoreCase(requestedMode)) {
            ChatConfig saved = ChatConfig.loadSession(resumeSessionId);
            if (saved != null) return saved;
        }
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
                // Preserve the provider-owned auth route and resolve its managed
                // credential lazily. Copying getApiKey() would flatten OAuth into
                // a plain API key and discard subscription headers/metadata.
                recorded.setAuthenticationMethod(fallback.getAuthenticationMethod());
                recorded.setBaseUrl(fallback.getBaseUrl());
                recorded.setThinking(fallback.getThinking());
                recorded.setFastMode(fallback.useFastMode(model));
            }
            return recorded;
        } catch (IOException | RuntimeException ignored) {
            return null;
        }
    }

    boolean shouldOfferProjectProfile(boolean headless, boolean selectedInWizard, boolean isResume) {
        // Resume actions preserve the recorded session and native resume routing. Bulk/headless
        // launches must never acquire a terminal or wait for a profile question.
        return !headless && !selectedInWizard && !isResume;
    }

    boolean shouldRunSetupWizard(ChatConfig config, boolean hasExplicitAction) {
        return !hasExplicitAction || config == null;
    }

    static boolean isWizardActionMode(ChatConfig config) {
        return config != null && "resume".equalsIgnoreCase(config.getChatMode());
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

        // Resume action: the wizard already launched ResumeTool or ResumeAllCommand.
        if (isWizardActionMode(config)) {
            return 0;
        }

        if ("passthrough".equals(chatMode)) {
            String agent = effectivePassthroughAgent(config, configSelectedInThisRun);
            if (agent == null || agent.isBlank()) {
                System.err.println("Passthrough mode requires an agent.");
                return 1;
            }
            applyPassthroughProfileSettings(config, agent);
            System.out.println("Starting passthrough mode with agent: " + agent);

            // A managed passthrough uses its configured project judge policy without a
            // second startup prompt. The persistent global switch is authoritative.
            Path wd = effectiveWorkingDirectory();
            boolean hasExplicitRuleFlags = (enforcerRules != null && !enforcerRules.isBlank())
                    || (enforcerRuleFile != null && !enforcerRuleFile.isBlank());
            boolean allowEnforcement = HarnessConfig.load().isJudgeGlobalEnabled()
                    && shouldConsiderEnforcement(config, hasExplicitRuleFlags);
            EnforcerConfig enforcerConfig =
                    allowEnforcement ? EnforcerConfig.load(wd) : null;
            Boolean sessionChoice = configSelectedInThisRun ? config.getEnforcementEnabled() : null;
            boolean enforce = allowEnforcement
                    && ai.kompile.cli.main.chat.enforcer.EnforcerConfig.shouldActivate(
                    sessionChoice, hasExplicitRuleFlags, enforcerConfig);

            // Enforcement requires the managed REPL. DeepSeek Harness does too:
            // upstream ships a one-shot headless profile, not an interactive TUI.
            boolean managed = shouldUseManagedPassthroughForInvocation(
                    config, enforce, agent);

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

    /** All three passthrough launch paths consume these resolved fields. Never cross agent boundaries. */
    void applyPassthroughProfileSettings(ChatConfig config, String agent) {
        if (!"passthrough".equals(config.getChatMode()) || blankToNull(config.getProvider()) != null
                || agent == null || !agent.equalsIgnoreCase(config.getPassthroughAgent())) return;
        boolean sameModel = blankToNull(model) == null || model.equals(config.getModel());
        if (blankToNull(model) == null) model = config.getModel();
        if (blankToNull(thinking) == null && sameModel) thinking = config.getThinking();
    }

    static boolean shouldConsiderEnforcement(ChatConfig config, boolean hasExplicitRuleFlags) {
        boolean directStyle = !config.isPassthroughManaged();
        boolean explicitEnforcement = hasExplicitRuleFlags
                || Boolean.TRUE.equals(config.getEnforcementEnabled());
        return !directStyle || explicitEnforcement;
    }

    static boolean shouldUseManagedPassthrough(
            ChatConfig config, boolean enforce, String agent) {
        return config.isPassthroughManaged()
                || enforce
                || SubprocessAgentRunner.requiresManagedOneShot(agent);
    }

    boolean shouldUseManagedPassthroughForInvocation(
            ChatConfig config, boolean enforce, String agent) {
        return internalManagedResume
                || shouldUseManagedPassthrough(config, enforce, agent);
    }

    boolean shouldDelegateManagedResume(boolean isResume) {
        return isResume && resumeSessionId != null && !resumeSessionId.isBlank()
                && !internalManagedResume;
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

            sessionId = runStandardSessionLoop(sessionId, isResume,
                    (currentTranscriptUuid, resumeCurrentTranscript) -> {
                        sessionId = currentTranscriptUuid;
                        activateTranscriptLog(sessionId, resumeCurrentTranscript);
                        if (resumeCurrentTranscript) {
                            System.out.println("Resuming conversation: " + sessionId);
                            restoreConversation(client, targetUrl);
                        } else {
                            createChatSession(client);
                            System.out.println("New conversation: " + sessionId);
                        }

                        System.out.println("Type /help for commands, /quit to exit.\n");

                        try (ChatRepl repl = new ChatRepl(
                                client, targetUrl, sessionId, rag, agentName, memory, null,
                                effectiveWorkingDirectory())) {
                            repl.setDangerouslySkipPermissions(dangerouslySkipPermissions);

                            if (assignedRole != null && !assignedRole.isBlank()) {
                                repl.assignRoleAtStartup(assignedRole);
                            }

                            repl.run();
                            return repl.isNewConversationRequested();
                        }
                    });

            return 0;
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            return 1;
        }
    }

    /**
     * First-party local model mode: start Kompile's low-overhead serving
     * subprocess with the selected model. The REPL retains a reconnectable binding;
     * only model requests hold leases, so idle time between turns can unload the model.
     * No full Kompile application instance is started.
     */
    private int runKompileLocalServingMode(
            ChatConfig config, boolean isResume, String assignedRole) {
        try (LocalServingRuntimePool.Lease runtime =
                     LocalServingRuntimePool.acquire(
                             config, startupTimeoutSeconds)) {
            runtime.applyTo(config);
        } catch (KompileLocalServingBootstrap.BootstrapException e) {
            System.err.println("Could not start Kompile local serving: "
                    + e.getMessage());
            System.err.println("Reconfigure with 'kompile chat --setup' to use "
                    + "an external Ollama/OpenAI-compatible endpoint, a cloud provider, "
                    + "or a Kompile instance.");
            return 1;
        }
        return runLocalLlmMode(config, isResume, assignedRole);
    }

    /**
     * Local LLM mode: direct API calls without a server.
     * Config is already resolved by the caller (call() or routeFromConfig()).
     */
    private int runLocalLlmMode(ChatConfig config, boolean isResume, String assignedRole) {
        try {
            sessionId = runStandardSessionLoop(sessionId, isResume,
                    (currentTranscriptUuid, resumeCurrentTranscript) -> {
                        sessionId = currentTranscriptUuid;
                        activateTranscriptLog(sessionId, resumeCurrentTranscript);
                        if (resumeCurrentTranscript) {
                            System.out.println("Resuming conversation: " + sessionId);
                        } else {
                            System.out.println("New conversation: " + sessionId);
                        }

                        try (ChatRepl repl = new ChatRepl(
                                null,       // no MCP client
                                null,       // no base URL
                                sessionId,
                                false,      // no RAG in local mode
                                agentName,
                                memory,
                                config,     // LLM config for direct calls
                                effectiveWorkingDirectory()
                        )) {
                            repl.setDangerouslySkipPermissions(dangerouslySkipPermissions);

                            if (assignedRole != null && !assignedRole.isBlank()) {
                                repl.assignRoleAtStartup(assignedRole);
                            }

                            repl.run();
                            return repl.isNewConversationRequested();
                        }
                    });
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
        boolean resumeCurrentTranscript = isResume;
        while (true) {
            // The enforce / opt-out decision is made once, up front, in routeFromConfig — a
            // session that opted out is never re-escalated here from a stale project config.
            int result = enforce
                    ? runEnforcedPassthroughMode(agent, resumeCurrentTranscript)
                    : runPlainManagedPassthrough(agent, resumeCurrentTranscript);
            if (result != RESTART_MANAGED_CHAT) {
                return result;
            }
            resumeCurrentTranscript = false;
            resumeSessionId = null;
            sessionId = newTranscriptUuid();
            try {
                activateTranscriptLog(sessionId, false);
            } catch (IOException e) {
                System.err.println("Cannot continue without a transcript log: " + e.getMessage());
                return 1;
            }
        }
    }

    /**
     * Plain managed passthrough (no enforcement): the kompile REPL wraps the agent
     * subprocess with MCP tool injection, system prompt, skills, and metrics tracking.
     * Also the graceful-degrade target when an enforcer config cannot be activated.
     */
    private int runPlainManagedPassthrough(String agent, boolean isResume) {
        // User-requested cross-agent resumes keep the existing ResumeCommand route.
        // /restart sets the hidden flag so this fresh process re-enters the same
        // Kompile-managed UI instead of dropping into the provider's native TUI.
        if (shouldDelegateManagedResume(isResume)) {
            return new CommandLine(new ResumeCommand())
                    .execute("--session-id", resumeSessionId, "--agent", agent);
        }

        try {
            // Delegate to EmulatedPassthroughCommand — the single REPL implementation
            // that has full slash-command completion, auto-trigger, MCP tools, etc.
            EmulatedPassthroughCommand passthrough = new EmulatedPassthroughCommand();
            passthrough.transcriptId = sessionId;
            passthrough.resumeSessionId = isResume ? resumeSessionId : null;
            passthrough.agent = agent;
            passthrough.workingDir = effectiveWorkingDirectory().toString();
            passthrough.skipPermissions = true;
            passthrough.injectTools = true;
            passthrough.kompileUrl = "";
            passthrough.mcpPort = 0;
            passthrough.model = model;
            passthrough.thinking = thinking;
            passthrough.systemPromptManager = SystemPromptManager.resolve(null, null, null);
            int result = passthrough.call();
            return passthrough.isNewConversationRequested()
                    ? RESTART_MANAGED_CHAT : result;
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
        ReminderManager reminderManager = new ReminderManager(objectMapper, sessionId, wd);

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
            EnforcerJudge judge = new EnforcerJudge(harnessConfig, objectMapper, wd);
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
            runtimePolicy = EnforcerRuntimePolicy.create(
                    wd, policy, harnessConfig, objectMapper,
                    useKeywordMode ? null : reminderManager);
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
            enforcerJudge.setReminderSupplier(runtimePolicy::getReminderConstraints);
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
        String configuredReminders = reminderManager.enforcementConstraints();
        int reminderCount = configuredReminders.isBlank() ? 0
                : (int) configuredReminders.lines().filter(l -> !l.isBlank()).count();
        System.out.println("     reminders:  " + reminderCount
                + (useKeywordMode ? " (prompt only in keyword mode)" : " (enforced)"));
        System.out.println("     session:    " + enfSessionId);
        System.out.println("     judgements: ~/.kompile/sessions/" + enfSessionId + "/judgements.jsonl");
        System.out.println();

        try {
            // Delegate to the single REPL with enforcer fields set
            EmulatedPassthroughCommand passthrough = new EmulatedPassthroughCommand();
            passthrough.transcriptId = sessionId;
            passthrough.resumeSessionId = isResume ? resumeSessionId : null;
            passthrough.agent = agent;
            passthrough.workingDir = wd.toString();
            passthrough.skipPermissions = true;
            passthrough.injectTools = true;
            passthrough.kompileUrl = "";
            passthrough.mcpPort = 0;
            passthrough.model = model;
            passthrough.thinking = thinking;
            passthrough.systemPromptManager = SystemPromptManager.resolve(null, null, null);
            passthrough.setReminderManager(reminderManager);
            passthrough.enforcerEvaluator = evaluator;
            passthrough.enforcerPolicy = policy;
            passthrough.enforcerService = service;
            passthrough.enforcerConversationWindow = conversationWindow;
            passthrough.enforcerExtraEnv = runtimePolicy.toEnvironment();
            passthrough.enforcerJudge = evaluator instanceof EnforcerJudge judge ? judge : null;
            int result = passthrough.call();
            return passthrough.isNewConversationRequested()
                    ? RESTART_MANAGED_CHAT : result;
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

    String serverSystemPrompt() {
        String projectContext = ProjectChatContext.load(effectiveWorkingDirectory()).renderSystemPrompt();
        if (projectContext.isBlank()) {
            return "";
        }
        return "You are a helpful AI assistant.\n\n" + projectContext;
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
        args.put("systemPrompt", serverSystemPrompt());
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
