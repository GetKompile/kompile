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

package ai.kompile.cli.main.chat.exec;

import ai.kompile.cli.common.KompileHome;
import ai.kompile.cli.common.util.JsonUtils;
import ai.kompile.cli.main.chat.ChatHistory;
import ai.kompile.cli.main.chat.ChatTitleGenerator;
import ai.kompile.cli.common.chat.sources.KompileTranscriptFormat;
import ai.kompile.cli.main.chat.ChatMemory;
import ai.kompile.cli.main.chat.ReminderManager;
import ai.kompile.cli.main.chat.ChatSessionMetrics;
import ai.kompile.cli.main.chat.SharedProcessMirror;
import ai.kompile.cli.main.chat.agent.AgentRegistry;
import ai.kompile.cli.main.chat.agent.AgentRunController;
import ai.kompile.cli.main.chat.agent.AgenticChatLoop;
import ai.kompile.cli.main.chat.agent.CustomAgentLoader;
import ai.kompile.cli.main.chat.agent.ProjectChatContext;
import ai.kompile.cli.main.chat.skill.SkillRegistry;
import ai.kompile.cli.main.chat.config.ChatConfig;
import ai.kompile.cli.main.chat.config.DirectLlmClient;
import ai.kompile.cli.main.chat.harness.HarnessConfig;
import ai.kompile.cli.main.chat.mcp.McpBundleToolLoader;
import ai.kompile.cli.main.chat.permission.PermissionService;
import ai.kompile.cli.main.chat.render.TerminalRenderer;
import ai.kompile.cli.main.chat.roles.RoleConfig;
import ai.kompile.cli.main.chat.roles.RoleManager;
import ai.kompile.cli.main.coordination.CoordinationStateManager;
import ai.kompile.cli.main.chat.tools.BackgroundProcessManager;
import ai.kompile.cli.main.chat.tools.ProcessManagementTool;
import ai.kompile.cli.main.chat.tools.ToolContext;
import ai.kompile.cli.main.chat.tools.ToolResult;
import ai.kompile.cli.main.chat.tools.ToolRegistry;
import ai.kompile.cli.main.chat.tools.ToolRegistryFactory;
import ai.kompile.cli.main.chat.workflow.WorkflowModelDefaults;
import ai.kompile.cli.main.chat.workflow.WorkflowSessionContext;
import ai.kompile.cli.main.chat.workflow.WorkflowTeam;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Runs kompile's native agent ({@link AgenticChatLoop}) for a single prompt,
 * non-interactively, and streams its text to stdout — the engine behind
 * {@code kompile exec} (analogous to {@code codex exec} / {@code opencode run}).
 *
 * <p>The harness build mirrors {@code EvalRunner.executeInternal}: load the local
 * LLM {@link ChatConfig}, create a {@link DirectLlmClient}, auto-approve permissions
 * (no interactive prompts are possible), and run one {@code loop.chat(...)} turn.
 *
 * <p><b>Output routing.</b> The loop streams assistant text through
 * {@code DirectLlmClient.printStreamingChunk} and prints all of its "chrome"
 * (step markers, tool indicators, spinners) directly to {@code System.out}. To keep
 * stdout clean and pipe-friendly we:
 * <ul>
 *   <li>use {@link CapturingLlmClient}, which overrides {@code printStreamingChunk}
 *       to forward the <em>raw</em> text (no markdown/ANSI) to a mode-specific sink
 *       on the real stdout; and</li>
 *   <li>redirect {@code System.out} to stderr (or a sink in quiet mode) for the
 *       duration of the run, so all chrome lands on stderr.</li>
 * </ul>
 * Our own output always goes through the captured {@code realOut}/{@code realErr}
 * references, independent of the {@code System.out} redirect.
 */
public final class HeadlessAgentRunner {

    /** System.out is process-global; serialize the temporary chrome redirect. */
    private static final Object STDOUT_REDIRECT_LOCK = new Object();

    /** How the agent's output is presented on stdout. */
    public enum OutputMode {
        /** Stream raw assistant text to stdout; chrome/progress to stderr. */
        TEXT,
        /** Print only the final response text to stdout; suppress everything else. */
        QUIET,
        /** Emit a JSONL event stream (session/text/tool/result) to stdout; chrome to stderr. */
        JSON
    }

    /** Immutable run configuration. */
    public record Options(
            String prompt,
            String sessionId,
            boolean resume,
            String agentName,
            String modelOverride,
            OutputMode outputMode,
            Path workingDirectory,
            long timeoutMs,
            Path outputLastMessage,
            String crawlBaseUrl,
            AgentRunController runController,
            HeadlessRunEventSink eventSink,
            ChatConfig chatConfig,
            String serverBaseUrl,
            boolean ragEnabled,
            boolean memoryEnabled,
            String roleName,
            boolean autoApproveTools,
            List<DirectLlmClient.AttachmentInput> attachments,
            WebChatInput webInput,
            ChatSessionStateStore sessionStateStore) {

        public Options {
            attachments = attachments == null ? List.of() : List.copyOf(attachments);
        }

        /**
         * Session state root used for durable web selections; {@code null} uses the
         * Kompile-home default, matching pre-injection behavior.
         */
        public ChatSessionStateStore sessionStateStoreOrDefault() {
            return sessionStateStore != null ? sessionStateStore : new ChatSessionStateStore();
        }

        /** Test/injection seam: run with an explicit session state root. */
        public Options withSessionStateStore(ChatSessionStateStore store) {
            return new Options(prompt, sessionId, resume, agentName, modelOverride, outputMode,
                    workingDirectory, timeoutMs, outputLastMessage, crawlBaseUrl, runController,
                    eventSink, chatConfig, serverBaseUrl, ragEnabled, memoryEnabled, roleName,
                    autoApproveTools, attachments, webInput, store);
        }

        public Options withWebInput(WebChatInput input) {
            return new Options(prompt, sessionId, resume, agentName, modelOverride, outputMode,
                    workingDirectory, timeoutMs, outputLastMessage, crawlBaseUrl, runController,
                    eventSink, chatConfig, serverBaseUrl, ragEnabled, memoryEnabled, roleName,
                    autoApproveTools, attachments, input, sessionStateStore);
        }

        /** Compatibility constructor for the pre-store (attachments, webInput) shape. */
        public Options(String prompt, String sessionId, boolean resume, String agentName,
                       String modelOverride, OutputMode outputMode, Path workingDirectory,
                       long timeoutMs, Path outputLastMessage, String crawlBaseUrl,
                       AgentRunController runController, HeadlessRunEventSink eventSink,
                       ChatConfig chatConfig, String serverBaseUrl, boolean ragEnabled,
                       boolean memoryEnabled, String roleName, boolean autoApproveTools,
                       List<DirectLlmClient.AttachmentInput> attachments,
                       WebChatInput webInput) {
            this(prompt, sessionId, resume, agentName, modelOverride, outputMode,
                    workingDirectory, timeoutMs, outputLastMessage, crawlBaseUrl,
                    runController, eventSink, chatConfig, serverBaseUrl, ragEnabled,
                    memoryEnabled, roleName, autoApproveTools, attachments, webInput, null);
        }

        /** Existing exec callers retain plain prompt semantics. */
        public Options(String prompt, String sessionId, boolean resume, String agentName,
                       String modelOverride, OutputMode outputMode, Path workingDirectory,
                       long timeoutMs, Path outputLastMessage, String crawlBaseUrl,
                       AgentRunController runController, HeadlessRunEventSink eventSink,
                       ChatConfig chatConfig, String serverBaseUrl, boolean ragEnabled,
                       boolean memoryEnabled, String roleName, boolean autoApproveTools,
                       List<DirectLlmClient.AttachmentInput> attachments) {
            this(prompt, sessionId, resume, agentName, modelOverride, outputMode,
                    workingDirectory, timeoutMs, outputLastMessage, crawlBaseUrl,
                    runController, eventSink, chatConfig, serverBaseUrl, ragEnabled,
                    memoryEnabled, roleName, autoApproveTools, attachments, null, null);
        }

        /** Compatibility constructor for callers compiled against the pre-attachment shape. */
        public Options(String prompt, String sessionId, boolean resume, String agentName,
                       String modelOverride, OutputMode outputMode, Path workingDirectory,
                       long timeoutMs, Path outputLastMessage, String crawlBaseUrl,
                       AgentRunController runController, HeadlessRunEventSink eventSink,
                       ChatConfig chatConfig, String serverBaseUrl, boolean ragEnabled,
                       boolean memoryEnabled, String roleName, boolean autoApproveTools) {
            this(prompt, sessionId, resume, agentName, modelOverride, outputMode,
                    workingDirectory, timeoutMs, outputLastMessage, crawlBaseUrl,
                    runController, eventSink, chatConfig, serverBaseUrl, ragEnabled,
                    memoryEnabled, roleName, autoApproveTools, List.of());
        }

        /** Backward-compatible options used by the general {@code kompile exec} command. */
        public Options(String prompt, String sessionId, boolean resume, String agentName,
                       String modelOverride, OutputMode outputMode, Path workingDirectory,
                       long timeoutMs, Path outputLastMessage) {
            this(prompt, sessionId, resume, agentName, modelOverride, outputMode,
                    workingDirectory, timeoutMs, outputLastMessage, null, null, null,
                    null, null, false, false, null, true, List.of());
        }

        /** Backward-compatible options used by crawl workers. */
        public Options(String prompt, String sessionId, boolean resume, String agentName,
                       String modelOverride, OutputMode outputMode, Path workingDirectory,
                       long timeoutMs, Path outputLastMessage, String crawlBaseUrl,
                       AgentRunController runController) {
            this(prompt, sessionId, resume, agentName, modelOverride, outputMode,
                    workingDirectory, timeoutMs, outputLastMessage, crawlBaseUrl,
                    runController, null, null, null, false, false, null, true, List.of());
        }

        /** Compatibility constructor for callers that supplied an observational event sink. */
        public Options(String prompt, String sessionId, boolean resume, String agentName,
                       String modelOverride, OutputMode outputMode, Path workingDirectory,
                       long timeoutMs, Path outputLastMessage, String crawlBaseUrl,
                       AgentRunController runController, HeadlessRunEventSink eventSink) {
            this(prompt, sessionId, resume, agentName, modelOverride, outputMode,
                    workingDirectory, timeoutMs, outputLastMessage, crawlBaseUrl,
                    runController, eventSink, null, null, false, false, null, true,
                    List.of());
        }
    }

    /** Run outcome. {@code exitCode} 0 = ok, 124 = timed out, 1 = error. */
    public record Result(int exitCode, String text, String sessionId) {}

    private final WebHarnessControls webControls;
    /** The web command the caller already resolved; resolving it again would apply its effects twice. */
    private final WebCommandResolver.Resolution resolvedCommand;

    public HeadlessAgentRunner() { this(null); }

    public HeadlessAgentRunner(WebHarnessControls webControls) { this(webControls, null); }

    public HeadlessAgentRunner(WebHarnessControls webControls, WebCommandResolver.Resolution resolvedCommand) {
        this.webControls = webControls;
        this.resolvedCommand = resolvedCommand;
    }

    public Result run(Options opts) {
        synchronized (STDOUT_REDIRECT_LOCK) {
            try {
                if (webControls != null && (opts.webInput() == null || opts.outputMode() != OutputMode.JSON))
                    throw new IllegalArgumentException("Live controls require web-json input and JSON output");
                return runWithRedirect(opts);
            } finally {
                if (webControls != null) webControls.close();
            }
        }
    }

    private Result runWithRedirect(Options opts) {
        final PrintStream realOut = System.out;
        final PrintStream realErr = System.err;
        final ObjectMapper mapper = JsonUtils.standardMapper();
        HeadlessRunEventSink configuredEventSink = opts.eventSink();
        if (configuredEventSink == null && opts.outputMode() == OutputMode.JSON) {
            configuredEventSink = event -> {
                synchronized (realOut) {
                    realOut.println(ExecJsonEvents.event(mapper, event));
                    realOut.flush();
                }
            };
        }
        EventPublisher events = new EventPublisher(configuredEventSink);
        final PrintStream chromeTarget = (opts.outputMode() == OutputMode.QUIET)
                ? new PrintStream(OutputStream.nullOutputStream(), true, StandardCharsets.UTF_8)
                : realErr;
        System.setOut(chromeTarget);
        try {
            return runInternal(opts, realOut, realErr, mapper, events);
        } catch (RuntimeException e) {
            String message = e.getMessage() == null
                    ? e.getClass().getSimpleName() : e.getMessage();
            events.publishTerminal(HeadlessRunEvent.failed(opts.sessionId(), message, 1));
            if (opts.outputMode() != OutputMode.JSON) {
                realErr.println("Error: " + message);
            }
            return new Result(1, "", opts.sessionId());
        } finally {
            System.setOut(realOut);
            if (chromeTarget != realErr) {
                chromeTarget.close();
            }
        }
    }

    private Result runInternal(Options opts, PrintStream realOut, PrintStream realErr,
                               ObjectMapper mapper, EventPublisher events) {
        // Explicit web input only: commands cannot fall through into an LLM, even
        // without provider configuration. Do not load memory/project instructions first.
        WebCommandResolver.Resolution webResolution = opts.webInput() == null ? null
                : resolvedCommand != null ? resolvedCommand
                : WebCommandResolver.resolve(opts.webInput(), opts.workingDirectory(),
                        opts.sessionStateStoreOrDefault(), opts.chatConfig());
        if (webResolution != null && webResolution.isCommandOutcome()) {
            events.publish(HeadlessRunEvent.started(opts.sessionId(), null,
                    opts.workingDirectory().toString(), Map.of("mode", "command")));
            events.publish(HeadlessRunEvent.commandOutcome(opts.sessionId(), webResolution));
            events.publishTerminal(HeadlessRunEvent.completed(opts.sessionId(),
                    webResolution.text(), webResolution.exitCode(), 0));
            if (opts.outputMode() != OutputMode.JSON) realOut.println(webResolution.text());
            if (opts.outputLastMessage() != null) {
                try {
                    Files.writeString(opts.outputLastMessage(), webResolution.text(), StandardCharsets.UTF_8);
                } catch (Exception e) {
                    realErr.println("Warning: could not write --output-last-message: " + e.getMessage());
                }
            }
            return new Result(webResolution.exitCode(), webResolution.text(), opts.sessionId());
        }
        boolean serverMode = opts.serverBaseUrl() != null && !opts.serverBaseUrl().isBlank();

        // ── Resolve the same project-scoped config used by interactive chat ──
        ChatConfig config = opts.chatConfig();
        if (config == null && opts.webInput() != null) config = ChatConfig.loadSession(opts.sessionId());
        if (config == null) config = ChatConfig.loadOrFromEnv(opts.workingDirectory());
        if (config == null && serverMode) {
            config = new ChatConfig("kompile", null, null, opts.serverBaseUrl());
        }
        if (config == null) {
            String msg = "No LLM configuration found. Run `kompile chat --setup` to configure a provider and model.";
            events.publishTerminal(HeadlessRunEvent.failed(opts.sessionId(), msg, 1));
            if (opts.outputMode() != OutputMode.JSON) {
                realErr.println(msg);
            }
            return new Result(1, "", opts.sessionId());
        }
        // A durable explicit web /model selection overrides the configured and persona
        // default model. The persona selector itself is untouched. Applied BEFORE any
        // provider/client construction and reflected in the RUN_STARTED event.
        // A stored provider (web /model <vendor>:<model>) switches the wire provider
        // too: like the interactive picker, cross-provider secrets and base URL are
        // discarded so one vendor's credential can never leak into another.
        String durableRole = null;
        if (opts.webInput() != null && !serverMode) {
            String stateSessionId = opts.webInput().sessionId() != null
                    && !opts.webInput().sessionId().isBlank()
                    ? opts.webInput().sessionId() : opts.sessionId();
            if (opts.modelOverride() == null || opts.modelOverride().isBlank()) {
                ChatSessionStateStore store = opts.sessionStateStoreOrDefault();
                String persistedProvider = store.loadProvider(stateSessionId, opts.workingDirectory());
                String persisted = store.loadModel(stateSessionId, opts.workingDirectory());
                if (persisted != null) {
                    boolean routeChanged = !persisted.equals(config.getModel());
                    if (persistedProvider != null
                            && !persistedProvider.equalsIgnoreCase(config.getProvider())) {
                        routeChanged = true;
                        config.setProvider(persistedProvider);
                        config.setApiKey(null);
                        config.setBaseUrl(null);
                        config.setAuthenticationMethod(
                                ChatConfig.authenticationMethodAfterProviderSwitch(persistedProvider));
                    }
                    config.setModel(persisted);
                    if (routeChanged) config.setThinking(ai.kompile.cli.main.chat.config.SetupWizard.compatibleThinking(
                            config.getProvider(), config.getModel(), config.getThinking(), null));
                }
            }
            ChatSessionStateStore.SessionState sessionState = opts.sessionStateStoreOrDefault()
                    .load(stateSessionId, opts.workingDirectory());
            if (sessionState != null && sessionState.thinking() != null) {
                // Validated against the live model metadata when /thinking saved it; /model clears it.
                config.setThinking(sessionState.thinking().isBlank() ? null : sessionState.thinking());
            }
            // A durable explicit web /role selection behaves like --role for this turn.
            if (opts.roleName() == null || opts.roleName().isBlank()) {
                durableRole = opts.sessionStateStoreOrDefault()
                        .loadRole(stateSessionId, opts.workingDirectory());
            }
        }
        if (opts.modelOverride() != null) {
            config.setModel(opts.modelOverride());
        }
        if (!serverMode && ("passthrough".equalsIgnoreCase(config.getChatMode())
                || !config.isValid())) {
            String msg = "Incomplete Standard Chat configuration for provider '"
                    + config.getProvider() + "'. Run `kompile chat --setup`.";
            events.publishTerminal(HeadlessRunEvent.failed(opts.sessionId(), msg, 2));
            if (opts.outputMode() != OutputMode.JSON) realErr.println(msg);
            return new Result(2, "", opts.sessionId());
        }

        AgentRegistry agentRegistry = new AgentRegistry();
        for (var custom : new CustomAgentLoader(opts.workingDirectory()).loadAll().values()) {
            agentRegistry.register(custom);
        }
        RoleManager roleManager = new RoleManager(opts.workingDirectory());
        String defaultAgent = firstNonBlank(
                serverMode ? null : opts.agentName(), config.getDefaultAgent(), "coder");
        String serverAgent = serverMode
                ? firstNonBlank(opts.agentName(), "claude-cli") : defaultAgent;
        String localAgent = localTurnAgent(
                agentRegistry, roleManager, opts.roleName(), durableRole, defaultAgent);
        if (localAgent == null) {
            String msg = "Role not found: " + opts.roleName();
            events.publishTerminal(HeadlessRunEvent.failed(opts.sessionId(), msg, 2));
            if (opts.outputMode() != OutputMode.JSON) realErr.println(msg);
            return new Result(2, "", opts.sessionId());
        }
        boolean effectiveRag = serverMode && opts.ragEnabled();
        // Effectively-final capture for the turn lambda below; a role (explicit or
        // durable) names a local run.
        final String effectiveAgent = serverMode ? serverAgent : localAgent;

        Map<String, String> effectiveConfiguration = new LinkedHashMap<>();
        effectiveConfiguration.put("mode", serverMode ? "server" : "standard");
        String effectiveProvider = serverMode ? "kompile" : config.getProvider();
        effectiveConfiguration.put("provider", nullToEmpty(effectiveProvider));
        effectiveConfiguration.put("auth", serverMode ? "none" : effectiveAuth(config));
        effectiveConfiguration.put("thinking", serverMode
                ? "" : nullToEmpty(config.effectiveEffort()));
        effectiveConfiguration.put("agent", effectiveAgent);
        effectiveConfiguration.put("role", durableRole != null && !durableRole.isBlank()
                ? durableRole : nullToEmpty(opts.roleName()));
        effectiveConfiguration.put("rag", Boolean.toString(effectiveRag));
        effectiveConfiguration.put("memory", Boolean.toString(opts.memoryEnabled()));
        String workflowTeam = workflowJson(config, mapper);
        if (workflowTeam != null) effectiveConfiguration.put("workflow", workflowTeam);
        events.publish(HeadlessRunEvent.started(opts.sessionId(),
                serverMode ? null : config.getModel(),
                opts.workingDirectory().toString(), effectiveConfiguration));

        // ── Mode-specific raw-text sink (writes to the REAL stdout) ─────────
        final StreamingTextCapture streamedText = new StreamingTextCapture();
        final Consumer<String> textSink = switch (opts.outputMode()) {
            case TEXT -> chunk -> {
                if (events.isClosed()) return;
                streamedText.append(chunk);
                events.publish(HeadlessRunEvent.assistantDelta(opts.sessionId(), chunk));
                realOut.print(chunk);
            };
            case JSON -> chunk -> {
                if (events.isClosed()) return;
                streamedText.append(chunk);
                events.publish(HeadlessRunEvent.assistantDelta(opts.sessionId(), chunk));
            };
            case QUIET -> chunk -> {
                if (events.isClosed()) return;
                streamedText.append(chunk);
                events.publish(HeadlessRunEvent.assistantDelta(opts.sessionId(), chunk));
            };
        };

        // Thinking deltas are events only (never stdout) so TEXT/QUIET pipe
        // consumers keep receiving answer text exclusively.
        final Consumer<String> thinkingSink = chunk -> {
            if (events.isClosed()) return;
            events.publish(HeadlessRunEvent.thinkingDelta(opts.sessionId(), chunk));
        };

        final CapturingLlmClient directClient = serverMode
                ? null : new CapturingLlmClient(
                config, mapper, textSink, thinkingSink, opts.workingDirectory());

        // ── Build the agent harness (auto-approve: non-interactive) ─────────
        PermissionService permissionService = new PermissionService();
        permissionService.setAutoApproveAll(opts.autoApproveTools());
        if (webControls != null) {
            // The JSONL reader owns stdin. ASK must fail closed, never consume a control frame.
            permissionService.setPromptListener(ignored -> permissionService.submitPromptResponse("deny"));
        }
        // Coordination first: it creates the project's .kompile directory, where process
        // logs are then rooted, so the first run's logs land where later runs look.
        CoordinationStateManager coordinationManager = new CoordinationStateManager(
                opts.workingDirectory(), opts.sessionId(), mapper);
        BackgroundProcessManager processManager = new BackgroundProcessManager(
                opts.sessionId(), opts.workingDirectory());
        if (webControls != null && BackgroundProcessManager.isSafeSessionId(opts.sessionId())) {
            // The web starts one harness per message: the session's process history lets
            // later runs, and commands sent between runs, reach what this run launches.
            processManager.enableSessionHistory();
        }
        TerminalRenderer renderer = new TerminalRenderer();
        ToolRegistry toolRegistry = ToolRegistryFactory.create(
                mapper, serverMode ? opts.serverBaseUrl() : "", agentRegistry,
                permissionService, renderer, processManager,
                serverMode ? null : config, roleManager, opts.crawlBaseUrl(),
                opts.workingDirectory(), coordinationManager);
        ProjectChatContext projectContext = ProjectChatContext.load(opts.workingDirectory());
        AgenticChatLoop loop = new AgenticChatLoop(
                serverMode ? opts.serverBaseUrl() : null,
                mapper, toolRegistry, permissionService, agentRegistry,
                opts.workingDirectory(), directClient, processManager,
                projectContext.skillRegistry());
        loop.setWorkflowGlobalEnabled(
                HarnessConfig.load(mapper).isJudgeGlobalEnabled());
        loop.configureConversationSession(opts.sessionId());
        if (!serverMode && !opts.attachments().isEmpty()) {
            loop.setPendingAttachments(opts.attachments());
        }
        ReminderManager reminderManager = new ReminderManager(
                mapper, opts.sessionId(), opts.workingDirectory());
        loop.setReminderManager(reminderManager);
        if (toolRegistry.getSubagentRunner() != null) {
            toolRegistry.getSubagentRunner().setReminderManager(reminderManager);
        }
        if (serverMode) {
            loop.setAssistantDeltaListener(textSink);
            loop.setServerEventListener(new AgenticChatLoop.ServerEventListener() {
                @Override
                public void onBackendStarted(String agent, String processId) {
                    events.publish(HeadlessRunEvent.backendStarted(
                            opts.sessionId(), agent, processId));
                }

                @Override
                public void onSources(com.fasterxml.jackson.databind.JsonNode sources) {
                    events.publish(HeadlessRunEvent.sources(
                            opts.sessionId(), sources.toString()));
                }

                @Override
                public void onStats(com.fasterxml.jackson.databind.JsonNode stats) {
                    events.publish(HeadlessRunEvent.stats(
                            opts.sessionId(), stats.toString()));
                }
            });
        }
        if (opts.runController() != null) {
            loop.setRunController(opts.runController());
        }

        // ── Ordered tool lifecycle events ───────────────────────────────────
        final ToolEventCounter toolCounter = new ToolEventCounter();
        Map<String, Long> toolStarts = new ConcurrentHashMap<>();
        loop.setToolActivityListener(new AgenticChatLoop.ToolActivityListener() {
            @Override
            public void onToolStart(String callId, String toolName, String rawInput) {
                toolStarts.put(callId == null ? "" : callId, System.currentTimeMillis());
                events.publish(HeadlessRunEvent.toolStarted(opts.sessionId(), callId, toolName, rawInput));
            }

            @Override
            public void onToolComplete(String callId, String toolName, String rawInput, ToolResult result) {
                String key = callId == null ? "" : callId;
                Long started = toolStarts.remove(key);
                long duration = started == null ? 0 : Math.max(0, System.currentTimeMillis() - started);
                toolCounter.inc();
                events.publish(HeadlessRunEvent.toolCompleted(opts.sessionId(), callId, toolName,
                        rawInput, result != null && !result.isError(), duration,
                        toolDetail(mapper, toolName, rawInput, result)));
            }
        });
        ChatSessionMetrics metrics = new EventEmittingMetrics(opts.sessionId(), events);
        metrics.setProvider(effectiveProvider);
        metrics.setModel(config.getModel());
        metrics.setAgentName(effectiveAgent);
        loop.setSessionMetrics(metrics);

        AtomicBoolean cancel = new AtomicBoolean(false);
        loop.setCancelSignal(cancel);

        // ── A vendor chat opened in Kompile chat starts with its vendor history ─
        boolean carriedOver;
        try {
            carriedOver = HeadlessPassthroughRunner.carryOverNativeTranscript(
                    opts.sessionId(), opts.workingDirectory());
        } catch (java.io.IOException e) {
            events.publishTerminal(HeadlessRunEvent.failed(opts.sessionId(), e.getMessage(), 1));
            if (opts.outputMode() != OutputMode.JSON) realErr.println(e.getMessage());
            return new Result(1, "", opts.sessionId());
        }

        // ── Restore prior session (for --continue / --resume) ───────────────
        if ((opts.resume() || carriedOver) && ChatHistory.exists(opts.sessionId())) {
            try {
                List<ChatHistory.Turn> turns = new ChatHistory(opts.sessionId()).readTurns();
                if (turns != null && !turns.isEmpty()) {
                    loop.restoreHistory(turns);
                }
            } catch (Exception e) {
                realErr.println("Warning: could not restore session history: " + e.getMessage());
            }
        }

        // ── Persist this run's turns so future --resume picks them up ───────
        ChatHistory history = new ChatHistory(opts.sessionId());
        try {
            history.open(serverMode ? opts.serverBaseUrl() : "(local)", effectiveAgent,
                    effectiveRag, opts.workingDirectory());
        } catch (Exception ignored) {
            // Transcript persistence is best-effort; never block the run on it.
        }
        // Web chat shares the interactive title policy. Restore existing names; only
        // a genuinely empty transcript gets one isolated first-prompt title request.
        String storedTitle = history.readSessionTitle();
        boolean emptyTranscript = false;
        if (opts.webInput() != null && storedTitle == null) {
            try { emptyTranscript = history.readTurns().isEmpty(); }
            catch (java.io.IOException ignored) { /* Unreadable history is not a new session. */ }
        }
        boolean generateTitle = opts.webInput() != null && storedTitle == null && emptyTranscript;
        String fallbackTitle = generateTitle ? KompileTranscriptFormat.normalizeTitle(
                ReminderManager.stripReminderBlock(opts.webInput().rawInput())) : storedTitle;
        if (generateTitle && fallbackTitle != null) history.logSessionTitle(fallbackTitle);
        if (opts.webInput() != null && fallbackTitle != null)
            events.publish(HeadlessRunEvent.sessionTitle(opts.sessionId(), fallbackTitle));
        ChatTitleGenerator titleGeneration = generateTitle && fallbackTitle != null && !serverMode
                ? ChatTitleGenerator.start(config, opts.workingDirectory(), opts.webInput().rawInput(), title -> {
                    synchronized (history) {
                        if (history.readTitleOverride() == null
                                && fallbackTitle.equals(history.readSessionTitle())) {
                            history.logSessionTitle(title);
                            events.publish(HeadlessRunEvent.sessionTitle(opts.sessionId(), title));
                        }
                    }
                }) : null;
        String resolvedPrompt = webResolution != null ? webResolution.modelPrompt()
                : projectContext.skillRegistry().resolveInvocation(opts.prompt())
                .map(SkillRegistry.SkillInvocation::prompt)
                .orElse(opts.prompt());
        ChatMemory chatMemory = new ChatMemory(
                null, opts.sessionId(), opts.memoryEnabled(), opts.workingDirectory());
        String memoryContext = chatMemory.buildMemoryContext(resolvedPrompt);
        String effectivePrompt = memoryContext == null || memoryContext.isBlank()
                ? resolvedPrompt
                : "<memory_context>\n" + memoryContext
                + "</memory_context>\n\n" + resolvedPrompt;
        // Decorate once here: the transcript records the outbound text and the agentic
        // loop's idempotent decoration will not tick or stack a second block.
        String outboundPrompt = reminderManager.decorateUserTurn(effectivePrompt);
        history.logUserMessage(outboundPrompt);

        // ── Run; the public wrapper already routed terminal chrome off stdout ─

        int exitCode = 0;
        String response = "";
        String failureMessage = null;
        long start = System.currentTimeMillis();
        McpBundleToolLoader mcpBundleTools = null;
        SharedProcessMirror sharedMirror = null;
        try {
            // Process-backed MCP bundles are intentionally acquired inside the cleanup
            // scope so even a required-server startup failure closes headless resources.
            mcpBundleTools = McpBundleToolLoader.load(
                    opts.workingDirectory(), toolRegistry, opts.sessionId());
            final String turnAgent = localAgent;
            if (webControls != null) {
                // As in the CLI, processes owned by other sessions join the live activity panel.
                sharedMirror = new SharedProcessMirror(processManager, coordinationManager, opts.sessionId());
                // Read before the first turn: a process that ends after this read can wake the run.
                sharedMirror.pollOnce();
                sharedMirror.start();
                webControls.setSharedProcesses(sharedMirror);
                if (directClient != null) {
                    // Claude Code's MCP server then registers as this session's child, so a monitored
                    // process it launches wakes this run. Claude Code's own tasks show as panel rows.
                    directClient.setClaudeTaskProcesses(processManager);
                }
            }
            ProcessManagementTool processTool = webControls == null ? null
                    : new ProcessManagementTool(processManager, coordinationManager);
            ToolContext controlContext = webControls == null ? null
                    : new ToolContext(opts.sessionId(),
                    agentRegistry.get(turnAgent), permissionService, opts.workingDirectory(), toolRegistry);
            AtomicBoolean firstLiveTurn = new AtomicBoolean(true);
            if (webControls != null) {
                WebChatInput initial = opts.webInput();
                webControls.setInitialDisplay(initial.rawInput());
                // A live /command resolves as it would between runs, in the same session state.
                webControls.setCommandResolver(raw -> WebCommandResolver.resolve(
                        new WebChatInput(WebChatInput.VERSION, raw, "", initial.sessionId()),
                        opts.workingDirectory(), opts.sessionStateStoreOrDefault(), opts.chatConfig()));
            }
            response = webControls != null
                    ? webControls.run(loop, processManager, opts.sessionId(), outboundPrompt, opts.timeoutMs(), cancel,
                    prompt -> {
                        String outbound = reminderManager.decorateUserTurn(prompt);
                        if (!firstLiveTurn.getAndSet(false)) history.logUserMessage(outbound);
                        long turnStart = System.currentTimeMillis();
                        String text = loop.chat(outbound, opts.sessionId(), turnAgent, serverAgent, effectiveRag);
                        long turnDuration = System.currentTimeMillis() - turnStart;
                        history.logAgentResponse(effectiveAgent, text, turnDuration);
                        metrics.recordAssistantTurn(text, turnDuration);
                        return text;
                    },
                    WebHarnessControls.processControl(processTool, controlContext),
                    events::publish, toolRegistry.getSubagentRunner())
                    : opts.timeoutMs() > 0
                    ? runWithTimeout(loop, opts, outboundPrompt,
                    localAgent, serverAgent, effectiveRag, cancel)
                    : loop.chat(outboundPrompt, opts.sessionId(), localAgent,
                    serverAgent, effectiveRag);
            if (response == null) { // null sentinel from runWithTimeout == timed out
                response = streamedText.captured();
                exitCode = 124;
            }
        } catch (Exception e) {
            response = streamedText.captured();
            exitCode = 1;
            failureMessage = e.getMessage() == null
                    ? e.getClass().getSimpleName() : e.getMessage();
            if (opts.outputMode() != OutputMode.JSON) {
                realErr.println("Error: " + failureMessage);
            }
        } finally {
            // Text has already streamed. Keep metadata delivery alive only for the
            // remainder of the bounded title job, never on a failed/cancelled run.
            if (titleGeneration != null) {
                if (exitCode == 0 && !cancel.get()) titleGeneration.await();
                else titleGeneration.close();
            }
            // Stop mirroring before the manager it feeds closes.
            if (sharedMirror != null) {
                sharedMirror.close();
            }
            if (mcpBundleTools != null) {
                mcpBundleTools.close();
            }
            if (directClient != null) {
                directClient.close();
            }
        }
        if (response == null) {
            response = "";
        }
        long durationMs = System.currentTimeMillis() - start;

        try {
            if (webControls == null) history.logAgentResponse(effectiveAgent, response, durationMs);
        } catch (Exception ignored) {
            // best-effort
        }
        if (webControls == null) metrics.recordAssistantTurn(response, durationMs);
        metrics.saveToFile(
                KompileHome.homeDirectory().toPath().resolve("conversations")
                        .resolve(opts.sessionId() + ".metrics.json"), mapper);
        history.close();
        processManager.close();
        coordinationManager.shutdown();

        HeadlessRunEvent terminalEvent = exitCode == 1
                ? HeadlessRunEvent.failed(opts.sessionId(), failureMessage, exitCode)
                : HeadlessRunEvent.completed(
                opts.sessionId(), response, exitCode, toolCounter.count());
        events.publishTerminal(terminalEvent);

        // ── Final output per mode ───────────────────────────────────────────
        switch (opts.outputMode()) {
            case TEXT -> {
                if (exitCode != 1) realOut.println(); // newline after the streamed text
            }
            case QUIET -> realOut.println(response.stripTrailing());
            case JSON -> { /* terminal JSON event was published above */ }
        }

        if (opts.outputLastMessage() != null) {
            try {
                Files.writeString(opts.outputLastMessage(), response, StandardCharsets.UTF_8);
            } catch (Exception e) {
                realErr.println("Warning: could not write --output-last-message: " + e.getMessage());
            }
        }

        if (exitCode == 124 && opts.outputMode() != OutputMode.JSON) {
            realErr.println("[timed out after " + (opts.timeoutMs() / 1000) + "s]");
        }
        return new Result(exitCode, response, opts.sessionId());
    }

    /**
     * Run the loop on a worker thread bounded by {@code opts.timeoutMs()}.
     * On timeout, signals cancellation and returns {@code null} (the caller
     * substitutes whatever text was streamed so far).
     */
    private String runWithTimeout(AgenticChatLoop loop, Options opts, String prompt,
                                  String localAgent, String serverAgent, boolean effectiveRag,
                                  AtomicBoolean cancel) throws Exception {
        ExecutorService exec = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "kompile-exec");
            t.setDaemon(true);
            return t;
        });
        Future<String> future = exec.submit(() ->
                loop.chat(prompt, opts.sessionId(), localAgent,
                        serverAgent, effectiveRag));
        try {
            return future.get(opts.timeoutMs(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException te) {
            cancel.set(true);
            loop.cancelActiveTurn();
            future.cancel(true);
            return null;
        } catch (ExecutionException e) {
            cancel.set(true);
            future.cancel(true);
            Throwable cause = e.getCause();
            if (cause instanceof Exception exception) throw exception;
            throw new RuntimeException(cause);
        } catch (InterruptedException e) {
            cancel.set(true);
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw e;
        } finally {
            exec.shutdownNow();
            try {
                exec.awaitTermination(2, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * The agent a local turn runs as: the explicit role, else the session's durable
     * role, else {@code agent}; a role it returns is registered in {@code agents}. Null
     * when the explicit role does not exist; a durable role that no longer exists is
     * ignored. Web commands sent between runs resolve their agent here too, so they
     * are permitted exactly as the live run would permit them.
     */
    static String localTurnAgent(AgentRegistry agents, RoleManager roles, String roleName,
                                 String durableRole, String agent) {
        if (roleName != null && !roleName.isBlank()) {
            RoleConfig role = roles.getRole(roleName);
            if (role == null) {
                return null;
            }
            agents.register(role.toAgentConfig());
            return role.getName();
        }
        if (durableRole != null && !durableRole.isBlank()) {
            RoleConfig role = roles.getRole(durableRole);
            if (role != null) {
                // The loop runs this agent: without registering the role it only renamed
                // the run while its prompt, tools and permissions never applied.
                agents.register(role.toAgentConfig());
                return role.getName();
            }
        }
        return agent;
    }

    static String firstNonBlank(String... values) {
        if (values != null) {
            for (String value : values) {
                if (value != null && !value.isBlank()) return value;
            }
        }
        return "";
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    /**
     * The terminal's completion row and body for stream-json clients. Presentation only: a
     * rendering failure publishes the completion without it rather than not at all.
     */
    private static JsonNode toolDetail(ObjectMapper mapper, String toolName, String rawInput,
                                       ToolResult result) {
        if (result == null) return null;
        try {
            return ToolCallJson.detail(mapper, toolName, rawInput, result);
        } catch (RuntimeException renderFailure) {
            return null;
        }
    }

    /** The run's auth as reported in its events; package-private for tests. */
    static String effectiveAuth(ChatConfig config) {
        if (config == null) return "none";
        if (config.isOpenCodeNative()) return "native";
        if (config.isClaudeCliNative()) return "claude-code";
        var auth = config.resolveRequestAuth();
        if (auth == null || auth.token() == null || auth.token().isBlank()) return "none";
        return auth.oauth() ? "oauth" : "api-key";
    }

    /**
     * The session's workflow team for its session event, or null without one: the lead
     * first, each participant's role, the model {@code config} runs it on, capabilities
     * and delegation edges, the routing, and the gates with those already approved.
     * Models appear by label, so credentials and endpoints stay out. Package-private for tests.
     */
    static String workflowJson(ChatConfig config, ObjectMapper mapper) {
        WorkflowSessionContext session = WorkflowSessionContext.current();
        if (session == null) return null;
        WorkflowTeam team = session.snapshot().team();
        ObjectNode root = mapper.createObjectNode();
        root.put("name", team.name());
        root.put("version", team.version());
        root.put("lead", team.lead());
        List<WorkflowTeam.Participant> ordered = new ArrayList<>();
        ordered.add(team.participant(team.lead()));
        team.participants().values().stream()
                .filter(participant -> !participant.id().equals(team.lead()))
                .forEach(ordered::add);
        ArrayNode participants = root.putArray("participants");
        for (WorkflowTeam.Participant participant : ordered) {
            ObjectNode entry = participants.addObject();
            entry.put("id", participant.id());
            entry.put("role", participant.role());
            entry.put("model", WorkflowModelDefaults.describe(team, participant, config));
            ArrayNode capabilities = entry.putArray("capabilities");
            participant.capabilities().forEach(capabilities::add);
            if (participant.canDelegate()) {
                ArrayNode delegatesTo = entry.putArray("delegatesTo");
                participant.delegatesTo().forEach(delegatesTo::add);
            }
        }
        ObjectNode routing = root.putObject("routing");
        team.routing().forEach(routing::put);
        WorkflowTeam.Gates gates = team.gates();
        ObjectNode gateNode = root.putObject("gates");
        if (gates.hasImplementationGate()) gateNode.put("implementationRequires", gates.implementationRequires());
        if (gates.hasCompletionGate()) gateNode.put("completionRequires", gates.completionRequires());
        ArrayNode approved = gateNode.putArray("approved");
        new TreeSet<>(session.enforcement().satisfiedGates()).forEach(approved::add);
        root.put("maxConcurrentWorkers", team.maxConcurrentWorkers());
        try {
            return mapper.writeValueAsString(root);
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    // ========================================================================
    // Collaborators
    // ========================================================================

    /** Assigns a monotonic sequence and serializes delivery to each consumer. */
    private static final class EventPublisher {
        private final HeadlessRunEventSink sink;
        private final AtomicLong sequence = new AtomicLong();
        private boolean closed;

        private EventPublisher(HeadlessRunEventSink sink) {
            this.sink = sink;
        }

        synchronized void publish(HeadlessRunEvent event) {
            if (closed || event == null) return;
            try {
                if (sink != null) sink.accept(event.withSequence(sequence.incrementAndGet()));
            } catch (RuntimeException ignored) {
                // Event sinks are observational; a broken sink must not fail the run.
            }
        }

        synchronized void publishTerminal(HeadlessRunEvent event) {
            if (closed) return;
            try {
                if (sink != null && event != null) {
                    sink.accept(event.withSequence(sequence.incrementAndGet()));
                }
            } catch (RuntimeException ignored) {
                // Terminal delivery remains best-effort for observational sinks.
            } finally {
                closed = true;
            }
        }

        synchronized boolean isClosed() {
            return closed;
        }
    }

    /**
     * A {@link DirectLlmClient} that captures raw streamed text and forwards it to a
     * sink, deliberately bypassing the loop's markdown renderer (which writes ANSI to
     * {@code System.out}). This keeps stdout free of styling for pipe consumers.
     */
    static final class CapturingLlmClient extends DirectLlmClient {
        private final Consumer<String> sink;
        private final Consumer<String> thinkingSink;
        private final StringBuilder captured = new StringBuilder();

        CapturingLlmClient(ChatConfig config, ObjectMapper mapper, Consumer<String> sink,
                           Path workingDirectory) {
            this(config, mapper, sink, null, workingDirectory);
        }

        CapturingLlmClient(ChatConfig config, ObjectMapper mapper, Consumer<String> sink,
                           Consumer<String> thinkingSink, Path workingDirectory) {
            super(config, mapper, workingDirectory);
            this.sink = sink;
            this.thinkingSink = thinkingSink;
        }

        @Override
        protected void printStreamingChunk(String chunk) {
            if (chunk == null) {
                return;
            }
            captured.append(chunk);
            if (sink != null) {
                sink.accept(chunk);
            }
        }

        /** Reasoning is display-only chrome — never part of the captured answer,
         *  but streaming consumers (stream-json) receive it as thinking deltas. */
        @Override
        protected void printThinkingChunk(String chunk) {
            if (chunk == null || chunk.isEmpty() || thinkingSink == null) {
                return;
            }
            thinkingSink.accept(chunk);
        }

        String captured() {
            return captured.toString();
        }
    }

    /** Thread-safe capture shared by direct-model and server-SSE transports. */
    static final class StreamingTextCapture {
        private final StringBuilder text = new StringBuilder();

        synchronized void append(String chunk) {
            if (chunk != null) text.append(chunk);
        }

        synchronized String captured() {
            return text.toString();
        }
    }

    /** Thread-safe counter for completed tool calls (used in the JSON {@code result} event). */
    static final class ToolEventCounter {
        private int n;

        synchronized void inc() { n++; }

        synchronized int count() { return n; }
    }

    /** Emits provider-reported token usage through the same ordered event stream. */
    static final class EventEmittingMetrics extends ChatSessionMetrics {
        private final String sessionId;
        private final EventPublisher events;

        EventEmittingMetrics(String sessionId, EventPublisher events) {
            super(sessionId);
            this.sessionId = sessionId;
            this.events = events;
        }

        @Override
        public void recordTokenUsage(long input, long output,
                                     long cacheRead, long cacheCreation) {
            super.recordTokenUsage(input, output, cacheRead, cacheCreation);
            events.publish(HeadlessRunEvent.tokenUsage(
                    sessionId, input, output, cacheRead, cacheCreation));
        }
    }
}
