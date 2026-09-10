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
import ai.kompile.cli.common.util.JsonUtils;
import ai.kompile.cli.main.chat.activity.AgentActivitySnapshotService;
import ai.kompile.cli.main.chat.activity.ActivityIdentity;
import ai.kompile.cli.main.chat.activity.ConversationActivityService;
import ai.kompile.cli.main.chat.activity.LogActivityAdapter;
import ai.kompile.cli.main.chat.activity.ProjectActivityController;
import ai.kompile.cli.main.chat.activity.TaskActivityAdapter;
import ai.kompile.cli.main.chat.activity.ToolCallTailReader;
import ai.kompile.cli.mcp.stdio.TaskRecord;
import ai.kompile.cli.mcp.stdio.TaskRegistry;
import ai.kompile.cli.main.chat.agent.*;
import ai.kompile.cli.main.codeindex.CodeIndexDiagnostics;
import ai.kompile.cli.main.chat.crawl.CrawlRunStore;
import ai.kompile.utils.StringUtils;
import ai.kompile.project.KompileProjectChatSession;
import ai.kompile.project.KompileProjectStore;
import ai.kompile.cli.main.chat.config.ChatConfig;
import ai.kompile.cli.main.chat.config.DirectLlmClient;
import ai.kompile.cli.main.chat.config.ModelCatalogSelection;
import ai.kompile.cli.main.chat.config.ModelDiscovery;
import ai.kompile.cli.main.chat.config.SetupWizard;
import ai.kompile.cli.main.chat.enforcer.EnforcerConfig;
import ai.kompile.cli.main.chat.enforcer.EnforcerDiagnostics;
import ai.kompile.cli.main.chat.enforcer.EnforcerEvaluator;
import ai.kompile.cli.main.chat.enforcer.EnforcerJudge;
import ai.kompile.cli.main.chat.enforcer.EnforcerPolicy;
import ai.kompile.cli.main.chat.enforcer.EnforcerToolCallDecision;
import ai.kompile.cli.main.chat.enforcer.KeywordEnforcerEvaluator;
import ai.kompile.cli.main.chat.enforcer.JudgeControl;
import ai.kompile.cli.main.chat.harness.HarnessConfig;
import ai.kompile.cli.main.chat.harness.JudgeBackend;
import ai.kompile.cli.main.chat.harness.JudgeBackendFactory;
import ai.kompile.cli.main.chat.harness.JudgeLlmEvaluator;
import ai.kompile.cli.main.chat.harness.PerformanceHarness;
import ai.kompile.cli.main.chat.mcp.McpBundleToolLoader;
import ai.kompile.cli.main.chat.mcp.McpDashboardController;
import ai.kompile.cli.main.chat.permission.PermissionService;
import ai.kompile.cli.main.chat.render.AsciiRenderer;
import ai.kompile.cli.main.chat.render.TerminalRenderer;
import ai.kompile.cli.main.chat.roles.RoleConfig;
import ai.kompile.cli.main.chat.roles.RoleManager;
import ai.kompile.cli.main.coordination.CoordinationStateManager;
import ai.kompile.cli.main.chat.skill.CustomSkillLoader;
import ai.kompile.cli.main.chat.skill.SkillConfig;
import ai.kompile.cli.main.chat.skill.SkillRegistry;
import ai.kompile.cli.main.chat.tools.*;
import ai.kompile.cli.main.chat.tui.KompileTui;
import ai.kompile.cli.main.chat.tui.StatusBar;
import ai.kompile.utils.AnsiConstants;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jline.reader.Binding;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.Reference;
import org.jline.reader.UserInterruptException;
import org.jline.reader.Widget;
import org.jline.reader.EndOfFileException;
import org.jline.terminal.MouseEvent;
import org.jline.terminal.Terminal;
import org.jline.keymap.KeyMap;
import org.jline.reader.impl.LineReaderImpl;
import org.jline.utils.InfoCmp;
import org.jline.utils.NonBlockingReader;

import java.io.File;
import java.io.IOError;
import java.io.IOException;
import java.io.PrintStream;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Interactive REPL for chatting with LLMs.
 * <p>
 * Supports two operational modes:
 * <ul>
 *   <li><b>Server mode</b>: Connected to a running kompile-app instance via MCP.
 *       Supports inline RAG chat, agent streaming, and agentic tool loop.</li>
 *   <li><b>Local mode</b>: Direct LLM API calls without a server.
 *       All chat goes through the agentic tool loop with local tool execution.</li>
 * </ul>
 * <p>
 * This class is a thin shell that wires together:
 * <ul>
 *   <li>{@link ChatCommandRouter} — slash command routing and all /command handlers</li>
 *   <li>{@link ChatMessageHandler} — message dispatch (local/server/streaming/agentic)</li>
 *   <li>{@link MessageQueueManager} — message queue management</li>
 *   <li>{@link SessionLifecycleManager} — session restore, summary, mode switching</li>
 * </ul>
 */
public class ChatRepl implements AutoCloseable {

    // Captured before constructor work (including judge warmup).
    private final ChatSessionContext sessionContext = ChatSessionContext.current();
    private final ChatUiSession uiSession = ChatUiSession.current();
    private LineReader retainedReader;
    private Terminal retainedTerminal;
    private boolean hostManaged;
    private boolean interactiveInitialized;
    private volatile boolean interactiveFinished;
    private final AtomicBoolean resourcesClosed = new AtomicBoolean();
    private Runnable codeIndexAlertCleanup = () -> { };
    private Runnable coordinationAlertCleanup = () -> { };
    private Runnable enforcerAlertCleanup = () -> { };

    // ── Core state ────────────────────────────────────────────────────────────

    private final McpSseClient mcpClient; // null in local mode
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final String baseUrl; // null in local mode
    private final String sessionId;
    private final ChatHistory chatHistory;
    private final ChatMemory chatMemory;
    private final ReminderManager reminderManager;
    private final ChatSessionTitle sessionTitle = new ChatSessionTitle();
    private final AtomicBoolean sessionTitleSyncPending = new AtomicBoolean();
    private boolean ragEnabled;
    private String agentName;
    private String localAgentName;
    private List<McpSseClient.ToolInfo> cachedTools;
    private boolean forceAgentic;
    private AgentRunController runController;
    private CrawlRunStore crawlRunStore;

    // Tool & agent system
    private final ToolRegistry toolRegistry;
    private final McpBundleToolLoader mcpBundleTools;
    private volatile McpDashboardController dashboardController;
    private final AgentRegistry agentRegistry;
    private final SkillRegistry skillRegistry;
    private final RoleManager roleManager;
    private final PermissionService permissionService;
    private final AgenticChatLoop agenticLoop;
    private final DirectLlmClient directClient;
    private final PerformanceHarness performanceHarness;
    private final JudgeLlmEvaluator directToolJudge;
    /** The only supervisory REPL exposed in the activity panel. */
    private final AuxiliaryChatRepl judgeRepl;
    /** Optional model-backed enforcer transport; never registered as a second REPL view. */
    private volatile AuxiliaryChatRepl enforcerRepl;

    /** User control over the judge: durable guidance + one-shot override (/judge). */
    private final ai.kompile.cli.main.chat.enforcer.JudgeControl judgeControl;
    /** Persistent master switch loaded from ~/.kompile/harness-config.json. */
    private volatile boolean judgeGloballyEnabled;
    private volatile EnforcerJudge enforcerJudge;
    /** Opt-in goal-drift monitor; constructed only when the project config enables it. */
    private volatile ai.kompile.cli.main.chat.enforcer.DirectionJudge directionJudge;
    private volatile AuxiliaryChatRepl directionRepl;
    private final AtomicBoolean auxiliarySupervisionActive = new AtomicBoolean(false);
    private final BackgroundProcessManager processManager;
    private final CoordinationStateManager coordinationManager;
    private final ProjectActivityController projectActivityController;
    private final ConversationActivityService conversationActivityService;
    private final AtomicBoolean acceptingProcessWakeups = new AtomicBoolean(false);
    private final BackgroundProcessManager.MonitorCallback processExitWakeListener =
            (entry, monitor) -> sessionContext.wrap(() -> handleProcessExitWakeup(entry, monitor)).run();
    private final TerminalRenderer renderer;
    private AsciiRenderer ascii;
    private final Path workingDirectory;
    private volatile ScheduledLoopManager scheduledLoopManager;
    private volatile ScheduledLoopManager globalScheduledLoopManager;

    // Mode
    private final boolean localMode;
    private ChatConfig chatConfig; // non-null in local mode
    // Message queue for queued chats
    private final MessageQueue messageQueue;
    /** Queue item currently leased into the JLine input buffer for editing. */
    private volatile String editingQueuedMessageId;

    // Flag to track if LLM is currently processing a response
    private volatile boolean llmBusy = false;

    // Cancel signal for interrupting in-progress LLM operations
    private final AtomicBoolean cancelSignal = new AtomicBoolean(false);

    // Background task manager for Ctrl+B job control
    private final BackgroundTaskManager backgroundTaskManager;

    // Auto-dequeue enabled flag
    private boolean autoDequeueEnabled = true;

    // Session metrics tracking
    private final ChatSessionMetrics sessionMetrics;

    // Animated generating spinner handle (active during LLM processing)
    private volatile TerminalRenderer.SpinnerHandle generatingSpinner;

    // Unified TUI manager: TopBar + scroll region + StatusBar
    private final KompileTui tui;

    // Active JLine handles. The model/provider picker runs after the outer readLine
    // returns, so it can safely borrow this reader without a re-entrant read loop.
    private volatile LineReader activeReader;
    private volatile Terminal activeTerminal;
    private volatile boolean modelPickerActive;
    /** Set by /clear so the owning ChatCommand starts a fresh transcript in this JVM. */
    private volatile boolean newConversationRequested;

    // Persistent below-bar status line showing processes, subagents, queue
    private final StatusBar statusBar;

    // Interactive process/subagent rows reserved directly below the input area.
    private final StandardChatActivityPanel activityPanel;
    private volatile Runnable auxiliaryActivityRedraw = () -> { };

    // Pending file/image attachments for the next message
    private final List<PendingAttachment> pendingAttachments = new ArrayList<>();

    // ── Extracted collaborators ───────────────────────────────────────────────

    private ChatCommandRouter commandRouter;
    private ChatMessageHandler messageHandler;
    private MessageQueueManager queueManager;
    private SessionLifecycleManager lifecycleManager;

    /** A file or image queued for the next chat message. */
    public record PendingAttachment(Path path, String mimeType, boolean isImage) {}

    public static final Set<String> IMAGE_MIME_TYPES = Set.of(
            "image/png", "image/jpeg", "image/gif", "image/webp", "image/bmp", "image/svg+xml");

    public static final Set<String> TEXT_EXTENSIONS = Set.of(
            "txt", "md", "java", "py", "js", "ts", "json", "xml", "yaml", "yml",
            "toml", "ini", "cfg", "conf", "sh", "bash", "zsh", "fish", "ps1",
            "c", "cpp", "h", "hpp", "cs", "go", "rs", "rb", "kt", "scala",
            "html", "css", "scss", "less", "sql", "graphql", "proto",
            "dockerfile", "makefile", "cmake", "gradle", "properties", "csv", "log");

    // ── Constructors ──────────────────────────────────────────────────────────

    /**
     * Server mode constructor.
     */
    public ChatRepl(McpSseClient mcpClient, String baseUrl, String sessionId,
                    boolean ragEnabled, String agentName) {
        this(mcpClient, baseUrl, sessionId, ragEnabled, agentName, true, null);
    }

    /**
     * Server mode constructor with memory option.
     */
    public ChatRepl(McpSseClient mcpClient, String baseUrl, String sessionId,
                    boolean ragEnabled, String agentName, boolean memoryEnabled) {
        this(mcpClient, baseUrl, sessionId, ragEnabled, agentName, memoryEnabled, null);
    }

    /**
     * Full constructor supporting both server and local modes.
     *
     * @param mcpClient   MCP client (null for local mode)
     * @param baseUrl     Server URL (null for local mode)
     * @param sessionId   Chat session ID
     * @param ragEnabled  Whether RAG is enabled
     * @param agentName   Server agent name
     * @param memoryEnabled Whether memory is enabled
     * @param chatConfig  LLM config for local mode (null for server mode)
     */
    public ChatRepl(McpSseClient mcpClient, String baseUrl, String sessionId,
                    boolean ragEnabled, String agentName, boolean memoryEnabled,
                    ChatConfig chatConfig) {
        this(mcpClient, baseUrl, sessionId, ragEnabled, agentName, memoryEnabled,
                chatConfig, null);
    }

    /** Full constructor with an explicit project directory for resumed sessions. */
    public ChatRepl(McpSseClient mcpClient, String baseUrl, String sessionId,
                    boolean ragEnabled, String agentName, boolean memoryEnabled,
                    ChatConfig chatConfig, Path workingDirectory) {
        this(mcpClient, baseUrl, sessionId, ragEnabled, agentName, memoryEnabled,
                chatConfig, workingDirectory, new TerminalRenderer());
    }

    /** Package-private renderer seam for exercising the real retained lifecycle. */
    ChatRepl(McpSseClient mcpClient, String baseUrl, String sessionId,
             boolean ragEnabled, String agentName, boolean memoryEnabled,
             ChatConfig incomingConfig, Path workingDirectory, TerminalRenderer renderer) {
        this.mcpClient = mcpClient;
        this.localMode = (mcpClient == null);
        ChatConfig chatConfig = incomingConfig == null ? null : incomingConfig.copy();
        this.chatConfig = chatConfig;
        if (chatConfig != null && mcpClient == null) {
            try { chatConfig.bindSession(sessionId); }
            catch (IOException e) { throw new IllegalStateException("Cannot persist session authentication", e); }
        }
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();

        if (mcpClient != null) {
            this.objectMapper = mcpClient.getObjectMapper();
        } else {
            this.objectMapper = JsonUtils.standardMapper();
        }

        this.baseUrl = baseUrl;
        this.sessionId = sessionId;
        this.ragEnabled = localMode ? false : ragEnabled;
        this.agentName = agentName;
        this.localAgentName = "coder";
        this.forceAgentic = false;
        this.chatHistory = new ChatHistory(sessionId);
        this.workingDirectory = workingDirectory == null
                ? Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize()
                : workingDirectory.toAbsolutePath().normalize();
        Path workDir = this.workingDirectory;

        // ChatMemory works in both modes: persistent memory + transcripts always,
        // RAG search only when server is connected
        this.chatMemory = new ChatMemory(mcpClient, sessionId, memoryEnabled, workDir);

        // Initialize tool & agent system
        this.permissionService = new PermissionService();
        this.agentRegistry = new AgentRegistry();
        this.renderer = Objects.requireNonNull(renderer, "renderer");
        this.ascii = new AsciiRenderer(renderer);
        this.permissionService.setPromptListener(sessionContext.wrapConsumer(prompt -> {
            ChatCompleter.printAbove("");
            ChatCompleter.printAbove(renderer.yellow("Permission required: ")
                    + renderer.bold(prompt.permissionKey()));
            if (prompt.description() != null && !prompt.description().isBlank()) {
                ChatCompleter.printAbove("  " + prompt.description());
            }
            ChatCompleter.printAbove(renderer.dim(
                    "  Enter y=yes, n=no, a=allow for session, v=deny for session"));
        }));

        this.reminderManager = new ReminderManager(objectMapper, sessionId, workDir);

        // Load custom agents from .kompile/agents/ and ~/.kompile/agents/
        CustomAgentLoader customAgentLoader = new CustomAgentLoader(workDir);
        Map<String, AgentConfig> customAgents = customAgentLoader.loadAll();
        for (AgentConfig custom : customAgents.values()) {
            agentRegistry.register(custom);
        }

        // Initialize skill registry with built-in and custom skills
        this.skillRegistry = new SkillRegistry();
        CustomSkillLoader customSkillLoader = new CustomSkillLoader(workDir);
        Map<String, SkillConfig> customSkills = customSkillLoader.loadAll();
        for (SkillConfig custom : customSkills.values()) {
            skillRegistry.register(custom);
        }

        // Initialize role manager with built-in and custom roles
        this.roleManager = new RoleManager(workDir);
        for (RoleConfig role : roleManager.getAllRoles()) {
            agentRegistry.registerRole(role);
        }

        // Create background process manager for this session
        this.processManager = new BackgroundProcessManager(sessionId, workDir);
        this.coordinationManager = new CoordinationStateManager(workDir, sessionId, objectMapper);

        this.toolRegistry = ToolRegistryFactory.create(
                objectMapper, baseUrl != null ? baseUrl : "", agentRegistry,
                permissionService, renderer, processManager,
                localMode ? chatConfig : null, roleManager, null,
                workDir, coordinationManager);
        SubagentRunner configuredSubagentRunner = toolRegistry.getSubagentRunner();
        if (configuredSubagentRunner != null) {
            configuredSubagentRunner.setReminderManager(reminderManager);
        }

        // Create DirectLlmClient for local mode
        this.directClient = localMode && chatConfig != null
                ? new DirectLlmClient(chatConfig, objectMapper, workDir) : null;

        this.agenticLoop = new AgenticChatLoop(
                baseUrl, objectMapper, toolRegistry, permissionService,
                agentRegistry, workDir, directClient, processManager, skillRegistry);
        this.agenticLoop.configureConversationSession(sessionId);
        this.agenticLoop.setReminderManager(reminderManager);

        // Initialize message queue for queued chats
        this.messageQueue = new MessageQueue(sessionId);

        // Initialize background task manager
        this.backgroundTaskManager = new BackgroundTaskManager();
        this.backgroundTaskManager.setBackgroundableCheck(
                () -> withSessionContext(agenticLoop::isBlockingSubagentInvocationActive));

        // Initialize unified TUI (TopBar + scroll region + StatusBar)
        this.tui = new KompileTui(backgroundTaskManager, processManager, messageQueue, renderer);
        this.statusBar = tui.getStatusBar();
        this.conversationActivityService = new ConversationActivityService();
        ActivityIdentity activityIdentity = ActivityIdentity.conversation(sessionId, workDir);
        this.conversationActivityService.setCurrentSession(activityIdentity);
        Path taskRegistryRoot = workDir.resolve(".kompile").resolve("task-registry");
        if (Files.isDirectory(taskRegistryRoot)) {
            this.conversationActivityService.addReader(identity -> new TaskActivityAdapter(() ->
                    new TaskRegistry(workDir).listAll().stream()
                            .map(record -> new TaskActivityAdapter.TaskObservation(
                                    record.getSessionId(), record.getTaskId(),
                                    record.getStatus() == TaskRecord.Status.FAILED))
                            .toList()));
        }
        this.conversationActivityService.addReader(identity -> new LogActivityAdapter(() ->
                processManager.listAll().stream()
                        .map(entry -> new LogActivityAdapter.LogObservation(
                                entry.getMetadata().getOrDefault("sessionId", sessionId),
                                entry.getId(), entry.getStartTime(), entry.getEndTime(),
                                entry.getState() == BackgroundProcessManager.ProcessState.FAILED))
                        .toList()));
        this.projectActivityController = new ProjectActivityController(
                new AgentActivitySnapshotService(
                        coordinationManager, new ToolCallTailReader()),
                sessionId, tui::getTerminalWidth);
        this.projectActivityController.setConversationActivityBrowser(
                conversationActivityService, activityIdentity, workDir);
        this.activityPanel = new StandardChatActivityPanel(
                backgroundTaskManager, processManager, statusBar, tui::getReservedMiddleRows);
        this.activityPanel.setProjectActivityView(projectActivityController);

        HarnessConfig harnessConfig = HarnessConfig.load(objectMapper);
        this.judgeGloballyEnabled = harnessConfig.isJudgeGlobalEnabled();
        this.agenticLoop.setWorkflowGlobalEnabled(judgeGloballyEnabled);
        this.judgeControl = ai.kompile.cli.main.chat.enforcer.JudgeControl.load(sessionId);
        this.judgeControl.setEnabled(judgeGloballyEnabled);
        this.agenticLoop.setJudgeControl(judgeControl);
        this.judgeRepl = harnessConfig.isEnabled() && harnessConfig.isJudgeEnabled()
                ? createModelAuxiliaryRepl(
                        AuxiliaryChatRepl.Kind.JUDGE,
                        harnessConfig.getJudgeProvider(), harnessConfig.getJudgeApiKey(),
                        harnessConfig.getJudgeModel(), harnessConfig.getJudgeBaseUrl())
                : AuxiliaryChatRepl.observer(
                        AuxiliaryChatRepl.Kind.JUDGE, "disabled");
        attachAuxiliaryRepl(judgeRepl);
        this.enforcerRepl = null;

        this.agenticLoop.setToolActivityListener(new AgenticChatLoop.ToolActivityListener() {
            @Override
            public void onToolStart(String callId, String toolName, String rawInput) {
                sessionContext.wrap(() -> activityPanel.recordToolStart(callId, toolName, rawInput)).run();
            }

            @Override
            public void onToolComplete(String callId, String toolName,
                                       String rawInput, ToolResult result) {
                sessionContext.wrap(() -> {
                activityPanel.recordToolComplete(callId, toolName, rawInput, result);
                McpDashboardController dashboard = dashboardController;
                if (dashboard != null) {
                    dashboard.onToolComplete(toolName, rawInput, result);
                }
                }).run();
            }

            @Override
            public void onToolDenied(String callId, String toolName,
                                     String rawInput, String reason) {
                sessionContext.wrap(() -> activityPanel.recordToolDenied(callId, toolName, rawInput, reason)).run();
            }
        });

        // Initialize session metrics
        this.sessionMetrics = new ChatSessionMetrics(sessionId);
        if (localMode && chatConfig != null) {
            sessionMetrics.setProvider(chatConfig.getProvider());
            sessionMetrics.setModel(chatConfig.getModel());
        }
        sessionMetrics.setAgentName(agentName);
        sessionMetrics.setRagEnabled(ragEnabled);

        // Wire metrics into agentic loop
        this.agenticLoop.setSessionMetrics(sessionMetrics);

        // Standard chat owns an in-process judge REPL. The explicit backend keeps
        // the harness away from the persistent CLI judge-process pool.
        this.performanceHarness = localMode && directClient != null
                ? new PerformanceHarness(
                        directClient, chatConfig, objectMapper, renderer, sessionMetrics,
                        processManager, boundedJudgeBackend(judgeRepl, harnessConfig))
                : null;
        this.directToolJudge = performanceHarness == null && judgeRepl.isAvailable()
                ? new JudgeLlmEvaluator(
                        boundedJudgeBackend(judgeRepl, harnessConfig), objectMapper)
                : null;
        this.agenticLoop.setInlineEnforcerActivityListener(sessionContext.wrapConsumer(this::recordSupervisorActivity));
        this.agenticLoop.setSupervisorFeedbackHandler((source, feedback, interrupt) ->
                withSessionContext(() -> forwardSupervisorFeedback(source, feedback, interrupt)));

        // Wire cancel signal into agentic loop
        this.agenticLoop.setCancelSignal(cancelSignal);

        // A configured project policy activates deterministically. There is no startup
        // question; /judge on|off owns this session and /judge global owns every session.
        if (judgeGloballyEnabled) {
            loadInlineEnforcer(workDir, true);
        }

        ai.kompile.cli.main.chat.enforcer.JudgementLog judgeControlLog =
                ai.kompile.cli.main.chat.enforcer.JudgementLog.forSession(sessionId);
        attachJudgeControlLog(judgeControlLog);

        // Direction remains opt-in through directionMonitoring in the project policy,
        // but no second startup prompt or user-facing supervisor concept is created.
        // Constructed ONLY when the project enforcer config sets directionMonitoring=true.
        // It never flips itself on, and it is intentionally NOT subject to the /judge
        // one-shot override: direction monitoring is a separate supervision contract.
        if (judgeGloballyEnabled) {
            loadDirectionJudge(workDir);
        }

        // Wire up extracted collaborators
        initCollaborators();
        Consumer<BackgroundTaskManager.BackgroundTask> completionListener =
                sessionContext.wrapConsumer(this::handleBackgroundTaskCompletion);
        backgroundTaskManager.addCompletionListener(completionListener::accept);
        processManager.addMonitorListener(processExitWakeListener);

        // Interactive Standard Chat exposes the same project-local MCP bundle
        // surface as headless chat. Load last so no later constructor step can
        // orphan a successfully-started stdio child.
        this.mcpBundleTools = McpBundleToolLoader.loadInteractive(
                workDir, toolRegistry, sessionId);
        this.dashboardController = new McpDashboardController(
                mcpBundleTools, tui, () -> callInSession(this::dashboardToolContext));
    }

    private ToolContext dashboardToolContext() {
        AgentConfig agent = agentRegistry.get(localAgentName);
        return new ToolContext(
                sessionId, agent, permissionService, workingDirectory, toolRegistry);
    }

    public String handleDashboardCommand(String arguments) {
        McpDashboardController dashboard = dashboardController;
        if (dashboard == null) return "No project dashboard is configured.";
        return dashboard.command(arguments).message();
    }

    void onDirectToolComplete(String toolName, String rawInput, ToolResult result) {
        McpDashboardController dashboard = dashboardController;
        if (dashboard != null) dashboard.onToolComplete(toolName, rawInput, result);
    }

    /**
     * Crawl profile constructor. It reuses the production REPL and agent loop,
     * but forces normal messages through the bounded agentic path.
     */
    public ChatRepl(McpSseClient mcpClient, String baseUrl, String sessionId,
                    boolean ragEnabled, String agentName, boolean memoryEnabled,
                    ChatConfig chatConfig, boolean forceAgentic) {
        this(mcpClient, baseUrl, sessionId, ragEnabled, agentName, memoryEnabled, chatConfig);
        this.forceAgentic = forceAgentic;
        if (forceAgentic) {
            AgentConfig crawler = agentRegistry.get("crawler");
            if (crawler != null) {
                this.localAgentName = crawler.getName();
                this.agenticLoop.setAgentConfig(crawler);
            }
        }
    }

    /**
     * Apply the explicit standard-chat dangerous permission bypass for this session.
     * This only affects Kompile tool permissions; provider subprocess flags are handled separately.
     */
    public void setDangerouslySkipPermissions(boolean enabled) {
        permissionService.setAutoApproveAll(enabled);
    }

    /** Attach durable control state for a crawl run. */
    public void configureCrawlControl(AgentRunController controller, CrawlRunStore store) {
        this.runController = controller;
        this.crawlRunStore = store;
        this.agenticLoop.setRunController(controller);
        if (store != null && controller != null) {
            store.open(controller, baseUrl, agentName);
        }
    }

    public AgentRunController getRunController() { return runController; }
    public CrawlRunStore getCrawlRunStore() { return crawlRunStore; }
    public boolean isForceAgentic() { return forceAgentic; }
    ReminderManager getReminderManager() { return reminderManager; }

    void requestNewConversation() {
        newConversationRequested = true;
    }

    boolean isNewConversationRequested() {
        return newConversationRequested;
    }

    /** Initialise the four extracted collaborator classes after construction. */
    private void initCollaborators() {
        this.messageHandler = new ChatMessageHandler(
                this, mcpClient, httpClient, objectMapper, sessionId, localMode,
                chatHistory, chatMemory, sessionMetrics, renderer, ascii, agenticLoop,
                backgroundTaskManager, messageQueue, cancelSignal, pendingAttachments,
                reminderManager);
        this.queueManager = new MessageQueueManager(
                this, messageQueue, messageHandler, backgroundTaskManager, sessionMetrics,
                renderer, ascii, autoDequeueEnabled);

        this.lifecycleManager = new SessionLifecycleManager(
                this, sessionId, localMode, chatHistory, sessionMetrics, renderer,
                ascii, agenticLoop, agentRegistry, toolRegistry);

        this.commandRouter = new ChatCommandRouter(
                this, messageHandler, queueManager, lifecycleManager,
                mcpClient, objectMapper, sessionId, localMode,
                chatHistory, sessionMetrics, renderer, ascii,
                agentRegistry, skillRegistry, roleManager,
                toolRegistry, permissionService, agenticLoop,
                backgroundTaskManager, processManager, statusBar,
                pendingAttachments, reminderManager);
    }

    private AuxiliaryChatRepl createModelAuxiliaryRepl(
            AuxiliaryChatRepl.Kind kind,
            String providerOverride,
            String apiKeyOverride,
            String modelOverride,
            String baseUrlOverride) {
        ChatConfig baseChatConfig = chatConfig != null
                ? chatConfig : ChatConfig.loadOrFromEnv(workingDirectory);
        if (baseChatConfig == null) {
            return AuxiliaryChatRepl.observer(kind, "model client unavailable");
        }

        DirectLlmClient client = JudgeBackendFactory.createDirectJudgeClient(
                baseChatConfig, providerOverride, apiKeyOverride, modelOverride, baseUrlOverride,
                objectMapper, workingDirectory);
        return client == null
                ? AuxiliaryChatRepl.observer(kind, "model client unavailable")
                : AuxiliaryChatRepl.modelBacked(kind, client, null);
    }

    private JudgeBackend boundedJudgeBackend(
            AuxiliaryChatRepl repl, HarnessConfig config) {
        ChatConfig effectiveChatConfig = chatConfig != null
                ? chatConfig : ChatConfig.loadOrFromEnv(workingDirectory);
        return JudgeBackendFactory.withResilience(
                repl.verdictBackend(), config, objectMapper,
                effectiveChatConfig, workingDirectory);
    }

    private <T> T callInSession(java.util.concurrent.Callable<T> callback) {
        try {
            return sessionContext.wrapCallable(callback).call();
        } catch (RuntimeException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new IllegalStateException("Session callback failed", failure);
        }
    }

    private boolean withSessionContext(java.util.function.BooleanSupplier callback) {
        boolean[] result = new boolean[1];
        sessionContext.wrap(() -> result[0] = callback.getAsBoolean()).run();
        return result[0];
    }

    private void attachAuxiliaryRepl(AuxiliaryChatRepl auxiliaryRepl) {
        if (auxiliaryRepl == null) return;
        auxiliaryRepl.setMainChatControl((source, feedback, interrupt) ->
                withSessionContext(() -> submitAuxiliaryFeedback(source, feedback, interrupt)));
        auxiliaryRepl.addChangeListener(sessionContext.wrap(this::requestAuxiliaryActivityRedraw));
        activityPanel.registerAuxiliaryRepl(auxiliaryRepl);
    }

    /**
     * Attach the session judgement log to every live judge so /judge judgements sees
     * verdicts from the inline enforcer judge and any later-loaded replacement.
     */
    private void attachJudgeControlLog(
            ai.kompile.cli.main.chat.enforcer.JudgementLog judgementLog) {
        if (enforcerJudge != null) {
            enforcerJudge.setJudgementLog(judgementLog);
        }
    }

    // ── Judge control accessors (used by ChatCommandRouter /judge) ────────────

    public ai.kompile.cli.main.chat.enforcer.JudgeControl getJudgeControl() {
        return judgeControl;
    }

    public boolean isJudgeGloballyEnabled() {
        return judgeGloballyEnabled;
    }

    public boolean isJudgeSessionEnabled() {
        return judgeControl.isEnabled();
    }

    /** Enable/disable judge evaluation/intervention without disabling judge chat. */
    public boolean setJudgeSessionEnabled(boolean enabled) {
        if (enabled && !judgeGloballyEnabled) {
            return false;
        }
        judgeControl.setEnabled(enabled);
        agenticLoop.setWorkflowSessionEnabled(enabled);
        recordSupervisorActivity("[state] " + (enabled ? "ready" : "disabled for this session"));
        return true;
    }

    /** Apply the persistent evaluation/intervention switch immediately to this REPL. */
    public void setJudgeGloballyEnabled(boolean enabled) {
        judgeGloballyEnabled = enabled;
        judgeControl.setEnabled(enabled);
        agenticLoop.setWorkflowGlobalEnabled(enabled);
        agenticLoop.setWorkflowSessionEnabled(enabled);
        if (performanceHarness != null) {
            performanceHarness.setJudgeGlobalEnabled(enabled);
        }
        if (enabled) {
            agenticLoop.reloadWorkflowConfiguration();
            loadInlineEnforcer(workingDirectory, true);
            loadDirectionJudge(workingDirectory);
        } else {
            clearJudgePolicy();
        }
        recordSupervisorActivity("[state] globally " + (enabled ? "enabled" : "disabled"));
    }

    /** Re-read all project judge policy components without another activation prompt. */
    public void reloadJudgeConfiguration() {
        if (!judgeGloballyEnabled) {
            clearJudgePolicy();
            return;
        }
        loadInlineEnforcer(workingDirectory, true);
        loadDirectionJudge(workingDirectory);
        agenticLoop.reloadWorkflowConfiguration();
    }

    /** Remove active project policy/direction components while retaining judge chat history. */
    public void clearJudgePolicy() {
        clearInlineJudgePolicy();
        clearDirectionJudge();
        agenticLoop.reloadWorkflowConfiguration();
    }

    private void clearInlineJudgePolicy() {
        clearInlineJudgePolicy(false);
    }

    private void clearInlineJudgePolicy(boolean requested) {
        EnforcerJudge previousJudge = enforcerJudge;
        enforcerJudge = null;
        agenticLoop.setInlineEnforcer(null, null, 0);
        agenticLoop.setInlineEnforcerEnabled(requested);
        replaceEnforcerRepl(null);
        if (previousJudge != null) {
            previousJudge.close();
        }
    }

    /** Best-effort verdict history for the /judge judgements subcommand. */
    public java.util.List<ai.kompile.cli.main.chat.enforcer.JudgementRecord> judgeHistory(int limit) {
        java.util.List<ai.kompile.cli.main.chat.enforcer.JudgementRecord> all =
                ai.kompile.cli.main.chat.enforcer.JudgementLog.readAll(sessionId);
        if (limit > 0 && all.size() > limit) {
            return all.subList(all.size() - limit, all.size());
        }
        return all;
    }

    /** Continue the judge-side conversation without enabling intervention. */
    public String sendToJudge(String message) {
        // Prefer the visible quality-judge REPL so the conversation appears in the
        // same transcript that now also carries enforcer activity.
        if (judgeRepl != null && judgeRepl.isAvailable()) {
            try {
                return judgeRepl.generate(message,
                        ai.kompile.cli.main.chat.enforcer.EnforcerJudge.CHAT_SYSTEM_PROMPT);
            } catch (Exception e) {
                return "[judge chat failed: " + e.getMessage() + "]";
            }
        }

        EnforcerJudge judge = enforcerJudge;
        if (judge != null && judge.isAvailable()) {
            try {
                judgeRepl.observe("[judge chat via policy backend]\n> " + message);
                String response = judge.chatWithJudge(message);
                judgeRepl.observe(response);
                return response;
            } catch (Exception e) {
                judgeRepl.observe("[judge chat error] " + e.getMessage());
                return "[judge chat failed: " + e.getMessage() + "]";
            }
        }
        return "[no judge backend is available — configure the harness judge or run /judge init]";
    }

    /** Human-readable judge backend summary for /judge status. */
    public String describeJudge() {
        if (judgeRepl != null && judgeRepl.isAvailable()) {
            return "harness judge · " + judgeRepl.status() + " · " + judgeRepl.describe();
        }
        EnforcerJudge judge = enforcerJudge;
        if (judge != null && judge.isAvailable()) {
            return "policy judge · " + judge.judgeStatus();
        }
        return "no judge backend — configure the harness judge or run /judge on";
    }

    public boolean isJudgeChatAvailable() {
        if (judgeRepl != null && judgeRepl.isAvailable()) return true;
        EnforcerJudge judge = enforcerJudge;
        return judge != null && judge.isAvailable();
    }

    /** Restart the inline enforcer judge's backend (no-op when it is not loaded). */
    public String restartJudge() {
        EnforcerJudge judge = enforcerJudge;
        if (judge == null) {
            return "No policy judge is loaded — nothing to restart. "
                    + "Use /judge on to load one.";
        }
        return judge.restartJudge();
    }

    /** Switch the inline enforcer judge to another CLI agent (no-op when not loaded). */
    public String modifyJudge(String selection) {
        EnforcerJudge judge = enforcerJudge;
        if (judge == null) {
            return "No policy judge is loaded — nothing to modify. "
                    + "Use /judge on to load one first.";
        }
        return judge.modifyJudge(selection);
    }

    private void replaceEnforcerRepl(AuxiliaryChatRepl replacement) {
        AuxiliaryChatRepl previous = enforcerRepl;
        enforcerRepl = replacement;
        if (previous != null && previous != replacement) {
            previous.close();
        }
    }

    private void requestAuxiliaryActivityRedraw() {
        auxiliaryActivityRedraw.run();
    }

    private boolean submitAuxiliaryFeedback(
            String source, String feedback, boolean interrupt) {
        ChatMessageHandler handler = messageHandler;
        if (handler == null || feedback == null || feedback.isBlank()) return false;
        String message = "[" + source + " feedback]\n" + feedback.strip();
        return handler.handleUserFeedback(message, interrupt);
    }

    private boolean forwardSupervisorFeedback(
            String source, String feedback, boolean interrupt) {
        recordSupervisorFeedback(source, feedback, interrupt);
        return submitAuxiliaryFeedback(source, feedback, interrupt);
    }

    private void recordSupervisorActivity(String event) {
        appendSupervisorActivity(judgeRepl, event);
    }

    static void appendSupervisorActivity(AuxiliaryChatRepl repl, String event) {
        if (repl == null || event == null || event.isBlank()) return;
        if (event.startsWith("[state] ")) {
            repl.observe("[judge state] "
                    + event.substring("[state] ".length()).strip());
        } else if (event.startsWith("[judge ")
                || event.startsWith("[direction]")) {
            repl.observe(event);
        } else if (event.startsWith("[enforcer ")) {
            repl.observe("[judge " + event.substring("[enforcer ".length()));
        } else {
            repl.observe("[judge] " + event);
        }
    }

    private void recordSupervisorFeedback(
            String source, String feedback, boolean interrupt) {
        if (feedback == null || feedback.isBlank()) return;
        judgeRepl.observe("[judge feedback -> main"
                + (interrupt ? " · interrupt" : "") + "]\n" + feedback.strip());
    }

    private void closeAuxiliaryRepls() {
        if (performanceHarness != null) {
            performanceHarness.shutdown();
        }
        if (directToolJudge != null) directToolJudge.close();
        EnforcerJudge judge = enforcerJudge;
        if (judge != null) judge.close();
        AuxiliaryChatRepl enforcer = enforcerRepl;
        if (enforcer != null) enforcer.close();
        AuxiliaryChatRepl direction = directionRepl;
        if (direction != null) direction.close();
        ai.kompile.cli.main.chat.enforcer.DirectionJudge dj = directionJudge;
        if (dj != null) dj.close();
        judgeRepl.close();
    }

    private void activateAuxiliarySupervision() {
        if (!auxiliarySupervisionActive.compareAndSet(false, true)) return;
        if (performanceHarness != null) {
            agenticLoop.setPerformanceHarness(performanceHarness);
            agenticLoop.setJudgeToolCallInterceptor((userPrompt, assistantContext,
                                                      toolName, toolInput) ->
                    callInSession(() -> performanceHarness.evaluateToolCall(
                            userPrompt, assistantContext, toolName, toolInput)));
        } else if (directToolJudge != null) {
            directToolJudge.setGuidanceSupplier(() -> callInSession(judgeControl::getGuidance));
            agenticLoop.setJudgeToolCallInterceptor((userPrompt, assistantContext, toolName, toolInput) ->
                    callInSession(() -> evaluateDirectToolCall(userPrompt, assistantContext, toolName, toolInput)));
        }
    }

    private EnforcerToolCallDecision evaluateDirectToolCall(
            String userPrompt, String assistantContext,
            String toolName, String toolInput) {
        try {
            return directToolJudge.evaluateToolCall(
                    userPrompt, assistantContext, toolName, toolInput);
        } catch (Exception failure) {
            return EnforcerToolCallDecision.allow(
                    "Quality judge failed open: " + failure.getMessage());
        }
    }

    // ── Judge policy loading (called from /judge and at deterministic startup) ──

    /** Explicitly load and enable the configured project judge policy. */
    public void loadInlineEnforcer(Path workDir) {
        loadInlineEnforcer(workDir, true);
    }

    private void loadInlineEnforcer(Path workDir, boolean enable) {
        EnforcerConfig enforcerConfig = EnforcerConfig.load(workDir);
        boolean requested = judgeGloballyEnabled && enable && (enforcerConfig == null
                ? EnforcerConfig.exists(workDir) : enforcerConfig.isEnforcementEnabled());
        if (!requested) {
            clearInlineJudgePolicy();
            return;
        }
        agenticLoop.setInlineEnforcerEnabled(true);
        try {
            if (enforcerConfig == null) throw new IllegalStateException("Configured enforcement policy could not be loaded");
            String rulesText = enforcerConfig.buildRulesText(workDir);
            if (rulesText == null || rulesText.isBlank()) {
                clearInlineJudgePolicy(true);
                recordSupervisorActivity("[configuration error] requested enforcement policy has no readable rules");
                return;
            }

            EnforcerPolicy policy =
                    new EnforcerPolicy(rulesText, enforcerConfig.getMaxCorrections(), false);
            EnforcerEvaluator evaluator;
            EnforcerJudge replacementJudge = null;
            AuxiliaryChatRepl replacement = null;
            if (enforcerConfig.isKeywordMode()) {
                evaluator = KeywordEnforcerEvaluator.fromPolicy(
                        policy, objectMapper, enforcerConfig);
            } else {
                replacement = createModelAuxiliaryRepl(
                        AuxiliaryChatRepl.Kind.ENFORCER,
                        enforcerConfig.getJudgeProvider(), enforcerConfig.getJudgeApiKey(),
                        enforcerConfig.getJudgeModel(), enforcerConfig.getJudgeBaseUrl());
                HarnessConfig judgeConfig = HarnessConfig.load(objectMapper);
                if (replacement.isAvailable()) {
                    replacementJudge = new EnforcerJudge(
                            boundedJudgeBackend(replacement, judgeConfig), objectMapper,
                            () -> judgeControl.hasGuidance() ? judgeControl.getGuidance() : null);
                    evaluator = replacementJudge;
                } else {
                    replacement.close();
                    replacement = null;
                    evaluator = null;
                }
            }

            if (evaluator != null && evaluator.isAvailable()) {
                EnforcerJudge previousJudge = enforcerJudge;
                enforcerJudge = replacementJudge;
                replaceEnforcerRepl(replacement);
                if (previousJudge != null && previousJudge != replacementJudge) {
                    previousJudge.close();
                }
                if (enforcerJudge != null) {
                    // Keep the dedicated enforcer judge on the shared session log so
                    // /judge judgements sees its verdicts (chat lane + tool verdicts).
                    enforcerJudge.setJudgementLog(
                            ai.kompile.cli.main.chat.enforcer.JudgementLog.forSession(sessionId));
                }
                agenticLoop.setInlineEnforcer(evaluator, policy, enforcerConfig.getMaxCorrections());
                agenticLoop.setInlineEnforcerEnabled(enable);
            } else {
                clearInlineJudgePolicy(true);
                recordSupervisorActivity("[configuration error] no usable judge policy evaluator");
            }
        } catch (Exception e) {
            clearInlineJudgePolicy(true);
            recordSupervisorActivity("[configuration error] " + e.getMessage());
        }
    }

    // ── Direction judge loading (called at startup and from /judge reload) ──

    /**
     * Construct and bind the direction judge when — and only when — the project enforcer
     * config explicitly sets {@code directionMonitoring=true}. A config without that flag,
     * a missing judge backend, or any construction failure leaves direction monitoring OFF;
     * nothing here can enable it implicitly.
     */
    public void loadDirectionJudge(Path workDir) {
        EnforcerConfig enforcerConfig = EnforcerConfig.load(workDir);
        if (!judgeGloballyEnabled || enforcerConfig == null
                || !enforcerConfig.isDirectionMonitoring()) {
            clearDirectionJudge();
            return;
        }
        try {
            AuxiliaryChatRepl replacement = createModelAuxiliaryRepl(
                    AuxiliaryChatRepl.Kind.DIRECTION,
                    enforcerConfig.getJudgeProvider(), enforcerConfig.getJudgeApiKey(),
                    enforcerConfig.getJudgeModel(), enforcerConfig.getJudgeBaseUrl());
            if (!replacement.isAvailable()) {
                replacement.close();
                clearDirectionJudge();
                recordSupervisorActivity("[direction] unavailable — no judge backend; "
                        + "staying OFF");
                return;
            }
            ai.kompile.cli.main.chat.enforcer.DirectionJudge.Options options =
                    new ai.kompile.cli.main.chat.enforcer.DirectionJudge.Options(
                            enforcerConfig.getDirectionGoal(),
                            enforcerConfig.getDirectionCheckEvery(),
                            enforcerConfig.getDirectionMaxRedirects(),
                            enforcerConfig.isDirectionReportOnly(),
                            enforcerConfig.getDirectionConfidenceThreshold(),
                            enforcerConfig.getDirectionCrossTurnDriftLimit());
            ai.kompile.cli.main.chat.enforcer.DirectionJudge replacementJudge =
                    new ai.kompile.cli.main.chat.enforcer.DirectionJudge(
                            boundedJudgeBackend(replacement, HarnessConfig.load(objectMapper)),
                            objectMapper, options);
            replacementJudge.setJudgementLog(
                    ai.kompile.cli.main.chat.enforcer.JudgementLog.forSession(sessionId));
            replacementJudge.bindStateFile(
                    ai.kompile.cli.main.chat.enforcer.JudgementLog.sessionDir(sessionId)
                            .resolve("direction-state.json"));
            replacementJudge.setGuidanceSupplier(() -> callInSession(() -> judgeControl.hasGuidance()
                    ? judgeControl.getGuidance() : null));
            agenticLoop.setDirectionJudge(replacementJudge);
            AuxiliaryChatRepl previousRepl = directionRepl;
            directionRepl = replacement;
            ai.kompile.cli.main.chat.enforcer.DirectionJudge previousJudge = directionJudge;
            directionJudge = replacementJudge;
            if (previousRepl != null && previousRepl != replacement) {
                previousRepl.close();
            }
            if (previousJudge != null && previousJudge != replacementJudge) {
                previousJudge.close();
            }
            recordSupervisorActivity("[direction] enabled · " + replacementJudge.describe()
                    + " · every " + options.checkEvery() + " iterations"
                    + " · confidence >= " + options.confidenceThreshold()
                    + (options.crossTurnDriftLimit() == 0
                            ? " · cross-turn off"
                            : " · cross-turn " + options.crossTurnDriftLimit())
                    + (options.reportOnly() ? " · report-only" : ""));
        } catch (Exception e) {
            clearDirectionJudge();
            recordSupervisorActivity("[direction] configuration error: " + e.getMessage());
        }
    }

    private void clearDirectionJudge() {
        ai.kompile.cli.main.chat.enforcer.DirectionJudge previousJudge = directionJudge;
        AuxiliaryChatRepl previousRepl = directionRepl;
        directionJudge = null;
        directionRepl = null;
        agenticLoop.setDirectionJudge(null);
        if (previousJudge != null) previousJudge.close();
        if (previousRepl != null) previousRepl.close();
    }

    /** The live direction judge, or null when direction monitoring is off. */
    public ai.kompile.cli.main.chat.enforcer.DirectionJudge getDirectionJudge() {
        return directionJudge;
    }

    // ── Main REPL loop ────────────────────────────────────────────────────────

    /**
     * Execute exactly one crawl instruction without constructing JLine or the TUI.
     * The same agentic loop, tool registry, transcript, checkpoint store and
     * lifecycle cleanup used by the interactive command are retained for CI and
     * production automation.
     */
    public void runHeadless(String message) throws Exception {
        if (message == null || message.isBlank()) {
            throw new IllegalArgumentException("Headless crawl message must not be blank");
        }
        activateAuxiliarySupervision();
        lifecycleManager.restoreSession();
        chatHistory.open(baseUrl != null ? baseUrl : "(local)", agentName, ragEnabled,
                workingDirectory);
        restoreSessionTitle();
        lifecycleManager.registerSession();
        lifecycleManager.syncSessionTitle(currentSessionTitle());
        registerProjectActivityPresence();
        if (!localMode) {
            try {
                cachedTools = mcpClient.listTools();
            } catch (Exception e) {
                cachedTools = List.of();
            }
        } else {
            cachedTools = List.of();
        }

        try {
            initializeSessionTitleFromPrompt(message);
            String effectiveMessage = skillRegistry.resolveInvocation(message)
                    .map(SkillRegistry.SkillInvocation::prompt)
                    .orElse(message);
            messageHandler.handleChatMessage(effectiveMessage);
            if (crawlRunStore != null && runController != null) {
                crawlRunStore.event("headless_completed", runController.state().name());
            }
        } finally {
            acceptingProcessWakeups.set(false);
            messageHandler.shutdown();
            closeAuxiliaryRepls();
            processManager.removeMonitorListener(processExitWakeListener);
            stopGeneratingSpinner();
            if (crawlRunStore != null && runController != null) {
                crawlRunStore.checkpoint(runController, "headless_closed");
                crawlRunStore.event("session_closed", runController.state().name());
            }
            lifecycleManager.printSessionSummary(false);
            chatHistory.close();
            if (chatHistory.getTranscriptFile() != null) {
                Path metricsFile = chatHistory.getTranscriptFile().resolveSibling(sessionId + ".metrics.json");
                sessionMetrics.saveToFile(metricsFile, objectMapper);
            }
            exportTranscriptToProject();
            dashboardController.close();
            mcpBundleTools.close();
            projectActivityController.close();
            processManager.close();
            coordinationManager.shutdown();
        }
    }

    /** Idempotently release project MCP children even when run setup fails early. */
    @Override
    public void close() {
        if (!resourcesClosed.compareAndSet(false, true)) return;
        try {
            if (interactiveInitialized && !interactiveFinished) finishInteractive(false, null);
            else if (!interactiveInitialized) {
                // Startup/role initialization can fail before the first prompt.
                acceptingProcessWakeups.set(false);
                messageHandler.shutdown();
                closeAuxiliaryRepls();
                stopGeneratingSpinner();
                processManager.removeMonitorListener(processExitWakeListener);
                tui.stop();
                if (directClient != null) directClient.close();
                processManager.close();
                coordinationManager.shutdown();
            }
        } finally {
            shutdownScheduledLoops();
            projectActivityController.close();
            dashboardController.close();
            mcpBundleTools.close();
        }
    }

    public void run() throws Exception {
        if (hostManaged) throw new IllegalStateException("The multi-session host owns input; do not run another REPL loop");
        Terminal terminal = ChatCompleter.buildSystemTerminal();
        IOException terminalFailure = null;
        boolean normalEnd = false;
        try {
            initializeInteractive(terminal);
            while (submitInteractiveLine(readInteractiveLine())) { }
            normalEnd = true;
        } catch (UserInterruptException | EndOfFileException end) {
            normalEnd = true;
        } catch (IOError failure) {
            terminalFailure = terminalReadFailure(failure);
        } finally {
            try { finishInteractive(normalEnd, terminalFailure); }
            finally { try { terminal.close(); } catch (Exception | IOError ignored) { } }
        }
        if (terminalFailure != null) throw terminalFailure;
    }

    /** Initialize once; a host calls read/submit sequentially, never another run loop. */
    void initializeInteractive(Terminal terminal) throws Exception {
        if (interactiveInitialized) throw new IllegalStateException("Chat already initialized");
        interactiveInitialized = true;
        retainedTerminal = terminal;
        activateAuxiliarySupervision();
        // The real terminal is not available until after JLine is built; attach the
        // title controller below so OSC updates reach the active terminal stream.

        // Open transcript file for writing
        chatHistory.open(baseUrl != null ? baseUrl : "(local)", agentName, ragEnabled,
                workingDirectory);
        restoreSessionTitle();
        lifecycleManager.registerSession();
        lifecycleManager.syncSessionTitle(currentSessionTitle());
        registerProjectActivityPresence();

        // Pre-cache tools for completion (server mode only)
        if (!localMode) {
            try {
                cachedTools = mcpClient.listTools();
            } catch (Exception e) {
                cachedTools = List.of();
            }
        } else {
            cachedTools = List.of();
        }

        renderer.attachTerminal(terminal, readyTerminalTitle(defaultTerminalTitle()));
        renderer.updateProcessActivity(processManager.listRunning().size());
        // Clear tracking modes left behind by an older session so the host terminal
        // retains native transcript selection and paste behavior.
        disableTranscriptMouse(terminal);

        // Re-create AsciiRenderer with actual terminal width now that the terminal is available
        int termW = terminal.getWidth();
        if (termW > 0) {
            this.ascii = new AsciiRenderer(renderer, termW);
            // Re-init collaborators so they reference the new ascii instance
            initCollaborators();
        }

        Path historyFile = new File(KompileHome.homeDirectory(),
                hostManaged ? "chat_input_history_" + UUID.nameUUIDFromBytes(
                        sessionId.getBytes(java.nio.charset.StandardCharsets.UTF_8)) : "chat_input_history").toPath();

        LineReader reader = LineReaderBuilder.builder()
                .terminal(terminal)
                .completer(new ChatCompleter(
                        () -> cachedTools,
                        () -> skillRegistry.names(),
                        () -> agentRegistry.getPrimaryAgents().stream()
                                .map(AgentConfig::getName)
                                .collect(Collectors.toCollection(LinkedHashSet::new)),
                        () -> roleManager.getAllRoles().stream()
                                .map(RoleConfig::getName)
                                .collect(Collectors.toCollection(LinkedHashSet::new))
                ))
                .variable(LineReader.HISTORY_FILE, historyFile)
                .build();

        retainedReader = reader;
        reader.getHistory().load();
        this.activeReader = reader;
        this.activeTerminal = terminal;
        tui.attachLineReader(reader);

        // Auto-trigger slash command completion as the user types
        ChatCompleter.enableAutoTrigger(reader);

        // Standard chat owns the activity rows below the input. Down enters them,
        // Up navigates back toward the prompt, Enter opens the selected activity,
        // and Del stops owned work. Normal typing/history remain the fallback.
        bindStandardChatActivityKeys(
                (LineReaderImpl) reader, messageQueue, activityPanel, tui,
                id -> editingQueuedMessageId = id,
                () -> ClipboardUtil.readFromClipboard().orElse(""));

        // Bind Ctrl+B in every possible active JLine map.
        bindBackgroundKey(((LineReaderImpl) reader).getKeyMaps());

        ((LineReaderImpl) reader).getWidgets().put("background-task", new Widget() {
            @Override
            public boolean apply() {
                if (messageHandler.requestBackground()) {
                    BackgroundTaskManager.BackgroundTask task = backgroundTaskManager.getCurrentTask();
                    int releasedInput = messageHandler.pendingBackgroundInputCount();
                    ChatCompleter.showNotice(renderer.yellow("  ◐ Subagent invocation backgrounded")
                            + renderer.dim(" [" + (task != null ? task.getId() : "?") + "] · "
                            + (releasedInput > 0 ? releasedInput + " pending message(s) released · " : "")
                            + "Output retained in /jobs; ↓ selects the subagent"));
                    statusBar.getActiveSubagents().forEach(entry ->
                            refreshInlineSubagentBlock(tui, activityPanel, entry.getId(), true));

                }
                return true;
            }
        });

        // Bind cancel key (default: Escape) to cancel in-progress operations.
        // Escape is also the prefix for arrows and EMACS Meta sequences. Keep a short
        // ambiguity window so complete escape sequences still win without making a lone
        // Escape wait for JLine's one-second default.
        LineReaderImpl lineReader = (LineReaderImpl) reader;
        // The ambiguity variable is read while JLine builds its default maps; set the
        // timeout on each existing map as well so a bare Escape is dispatched promptly.
        for (KeyMap<Binding> keyMap : lineReader.getKeyMaps().values()) {
            if (keyMap != null) {
                keyMap.setAmbiguousTimeout(80L);
            }
        }
        String cancelKeyBinding = resolveCancelKeyBinding();
        // The active map can be selected by the user's JLine/inputrc setup. Bind
        // every available map so Escape remains a cancellation key in both the
        // default emacs map and vi/inputrc configurations.
        bindCancelKey(lineReader.getKeyMaps(), cancelKeyBinding);

        lineReader.getWidgets().put("cancel-operation", new Widget() {
            @Override
            public boolean apply() {
                if (modelPickerActive || tui.isTemporaryWindowActive()) {
                    // Escape is an interruption for the picker itself. If a model
                    // turn is also active, apply the same scoped cancellation policy
                    // before unwinding the nested picker read. Active subagents keep
                    // running and remain Delete-targeted.
                    requestCancelFromInput();
                    ChatCompleter.markInterrupted();
                    requestStatusRedraw();
                    throw new UserInterruptException("");
                }
                if (editingQueuedMessageId != null) {
                    String editId = editingQueuedMessageId;
                    editingQueuedMessageId = null;
                    messageQueue.cancelEdit(editId);
                    lineReader.getBuffer().clear();
                    activityPanel.refresh();
                    redisplayWithContent(lineReader, tui);
                    return true;
                }
                boolean cancelled = requestCancelFromInput();
                if (cancelled) {
                    // Keep this in the live status bar rather than printing a
                    // permanent transcript line. Typing the next message clears it.
                    ChatCompleter.markInterrupted();
                    requestStatusRedraw();
                }
                return true;
            }
        });

        // ================================================================
        // Mode-switching hotkeys (Ctrl+X prefix chord)
        // ================================================================

        KeyMap<Binding> emacsKeyMap =
                ((LineReaderImpl) reader).getKeyMaps().get(LineReader.EMACS);
        bindModeSwitchingHotkeys(emacsKeyMap);

        // Ctrl+X P — Toggle planning mode
        ((LineReaderImpl) reader).getWidgets().put("toggle-plan-mode", new Widget() {
            @Override
            public boolean apply() {
                boolean newState = !agenticLoop.isPlanningMode();
                agenticLoop.setPlanningMode(newState);
                tui.setPlanningMode(newState);
                System.out.println();
                if (newState) {
                    System.out.println(renderer.green("  ✓ Planning mode ON"));
                    System.out.println(renderer.dim("    Next message: plan → approve → execute"));
                } else {
                    System.out.println(renderer.yellow("  ○ Planning mode OFF"));
                    System.out.println(renderer.dim("    Messages go directly to the agentic loop"));
                }
                System.out.println();
                System.out.flush();
                chatHistory.logSystem("Planning mode " + (newState ? "enabled" : "disabled") + " (via Ctrl+X P)");
                return true;
            }
        });

        // Ctrl+X T — Show todos / checklist
        ((LineReaderImpl) reader).getWidgets().put("show-todos", new Widget() {
            @Override
            public boolean apply() {
                List<TodoWriteTool.TodoItem> todos = TodoWriteTool.getTodos(sessionId, workingDirectory);
                System.out.println();
                if (todos.isEmpty()) {
                    System.out.println(renderer.dim("  No tasks in the current session."));
                } else {
                    System.out.println(renderer.renderTodoList(todos));
                }
                System.out.println();
                System.out.flush();
                return true;
            }
        });

        // Ctrl+X A — Cycle primary agent (coder → planner → coder)
        ((LineReaderImpl) reader).getWidgets().put("cycle-agent", new Widget() {
            @Override
            public boolean apply() {
                List<AgentConfig> primaries = agentRegistry.getPrimaryAgents();
                if (primaries.size() < 2) return true;

                // Find current index and advance
                int currentIdx = -1;
                for (int i = 0; i < primaries.size(); i++) {
                    if (primaries.get(i).getName().equals(localAgentName)) {
                        currentIdx = i;
                        break;
                    }
                }
                int nextIdx = (currentIdx + 1) % primaries.size();
                AgentConfig nextAgent = primaries.get(nextIdx);
                localAgentName = nextAgent.getName();
                agenticLoop.setAgentConfig(nextAgent);
                tui.setAgentName(localAgentName);

                System.out.println();
                System.out.println(renderer.cyan("  ⇄ Agent: " + nextAgent.getDisplayName())
                        + renderer.dim(" — " + nextAgent.getDescription()));
                System.out.println();
                System.out.flush();
                chatHistory.logSystem("Switched agent to: " + localAgentName + " (via Ctrl+X A)");
                return true;
            }
        });

        // Print welcome banner with mode options
        if (localMode) {
            System.out.println(ascii.welcomePanelWithModes(
                    sessionId, localProviderDisplayName(), false, true));
            System.out.println();
            String provider = chatConfig != null ? chatConfig.getProvider() : "unknown";
            String model = chatConfig != null ? chatConfig.getModel() : "unknown";
            System.out.println(renderer.dim("  Mode: local (direct LLM) — " + provider + "/" + model));
            System.out.println(renderer.dim("  All messages use the agentic tool loop with local tools."));
            System.out.println(renderer.dim("  Type /help for commands, /setup to reconfigure."));
        } else {
            System.out.println(ascii.welcomePanelWithModes(sessionId, agentName, ragEnabled, false));
        }

        // Show AGENTS.md status
        String agentsMd = agenticLoop.getAgentsMdContent();
        if (agentsMd != null && !agentsMd.isEmpty()) {
            List<Path> files = agenticLoop.getAgentsMdFiles();
            System.out.println(renderer.dim("  Loaded AGENTS.md from: " +
                    files.stream().map(p -> p.getParent().toString()).collect(Collectors.joining(", "))));
            int estimatedInstructionTokens = Math.max(1, agentsMd.length() / 4);
            if (estimatedInstructionTokens > 16_000) {
                System.out.println(renderer.yellow("  Warning: AGENTS.md contributes about "
                        + estimatedInstructionTokens + " tokens before chat history and tools"));
            }
        }

        // Show available subagents
        List<AgentConfig> subagents = agentRegistry.getSubagents();
        long customCount = subagents.stream().filter(AgentConfig::isCustom).count();
        long builtinCount = subagents.size() - customCount;
        StringBuilder agentInfo = new StringBuilder();
        agentInfo.append("  Subagents: ").append(builtinCount).append(" built-in");
        if (customCount > 0) {
            agentInfo.append(", ").append(customCount).append(" custom");
        }
        agentInfo.append(" (");
        agentInfo.append(subagents.stream()
                .map(a -> a.getName() + (a.isCustom() ? "*" : ""))
                .collect(Collectors.joining(", ")));
        agentInfo.append(")");
        System.out.println(renderer.dim(agentInfo.toString()));

        // Show the single judge control-plane status.
        if (!judgeGloballyEnabled) {
            System.out.println(renderer.yellow("  Judge: globally disabled — /judge global on"));
        } else if (!judgeControl.isEnabled()) {
            System.out.println(renderer.yellow("  Judge: disabled for this session — /judge on"));
        } else if (agenticLoop.isInlineEnforcerEnabled()) {
            System.out.println(renderer.green("  Judge policy: " + agenticLoop.describeInlineEnforcer()
                    + " — /judge off to disable"));
        } else if (agenticLoop.describeInlineEnforcer() != null) {
            System.out.println(renderer.dim("  Judge policy: " + agenticLoop.describeInlineEnforcer()
                    + " — loaded but OFF, /judge on to enable"));
        }
        System.out.println();

        // Start the unified TUI: TopBar + scroll region + StatusBar
        tui.setAgentName(localMode ? activeModelTopPaneLabel() : agentName);
        tui.setSessionId(sessionId);
        tui.setMode(localMode ? "local" : "server");
        tui.setPlanningMode(agenticLoop.isPlanningMode());
        tui.setReservedRowsCalculator(StandardChatActivityPanel::reservedRowsForTerminal);
        dashboardController.prepare();
        tui.start(terminal);
        if (hostManaged && !tui.isTerminalAttached()) {
            throw new IllegalStateException("--multi-session requires an ANSI-enabled TUI terminal");
        }
        // Managed selection/copy and wheel scrolling share the same mouse reports.
        enableTranscriptMouse(terminal);
        activityPanel.refresh();

        // KompileTui.start() clears the terminal while establishing its bars and
        // scroll region. Restore only after that clear, and route each line through
        // the TUI so the prior transcript remains visible above the live prompt.
        lifecycleManager.restoreSession(tui::printInScrollRegion);
        dashboardController.requestRefresh();

        // Wire activity changes into both the compact status line and the
        // interactive process/subagent rows.
        Runnable activityRedraw = sessionContext.wrap(() -> {
            activityPanel.refresh();
            refreshCurrentActivityView(tui, activityPanel);
            renderer.updateProcessActivity(processManager.listRunning().size());
        });
        AtomicBoolean auxiliaryRefreshQueued = new AtomicBoolean(false);
        auxiliaryActivityRedraw = () -> {
            if (!auxiliaryRefreshQueued.compareAndSet(false, true)) return;
            CompletableFuture.delayedExecutor(50, TimeUnit.MILLISECONDS).execute(sessionContext.wrap(() -> {
                auxiliaryRefreshQueued.set(false);
                activityRedraw.run();
            }));
        };
        backgroundTaskManager.addChangeListener(activityRedraw);
        processManager.addChangeListener(activityRedraw);
        // Process lifecycle listeners only fire on launch/exit. Subscribe to the
        // capture stream as well so an opened process view follows output line-by-line.
        AtomicBoolean processOutputRefreshQueued = new AtomicBoolean(false);
        processManager.addOutputListener((entry, line) -> {
            if (!processOutputRefreshQueued.compareAndSet(false, true)) return;
            CompletableFuture.delayedExecutor(50, TimeUnit.MILLISECONDS).execute(sessionContext.wrap(() -> {
                processOutputRefreshQueued.set(false);
                activityRedraw.run();
            }));
        });
        tui.addResizeListener(activityRedraw);
        projectActivityController.start(activityRedraw);

        // Wire subagent lifecycle tracking into the status bar
        SubagentRunner runner = toolRegistry.getSubagentRunner();
        if (runner != null) {
            activityPanel.setSubagentRunner(runner);
            Set<String> dirtySubagentOutputs = java.util.concurrent.ConcurrentHashMap.newKeySet();
            AtomicBoolean subagentOutputRefreshQueued = new AtomicBoolean(false);
            Runnable scheduleSubagentOutputRefresh = () -> {
                if (!subagentOutputRefreshQueued.compareAndSet(false, true)) return;
                CompletableFuture.delayedExecutor(50, TimeUnit.MILLISECONDS).execute(sessionContext.wrap(() -> {
                    subagentOutputRefreshQueued.set(false);
                    Set<String> ids = Set.copyOf(dirtySubagentOutputs);
                    dirtySubagentOutputs.removeAll(ids);
                    ids.forEach(id -> refreshInlineSubagentBlock(
                            tui, activityPanel, id, false));
                    refreshCurrentActivityView(tui, activityPanel);
                }));
            };
            runner.setLifecycleListener(new SubagentRunner.LifecycleListener() {
                @Override
                public void onSubagentStart(String id, String type, String description) {
                    sessionContext.wrap(() -> {
                    statusBar.registerSubagent(id, type, description);
                    activityPanel.refresh();
                    refreshInlineSubagentBlock(tui, activityPanel, id, false);
                    refreshCurrentActivityView(tui, activityPanel);
                    }).run();
                }
                @Override
                public void onSubagentStatus(String id, String status) {
                    sessionContext.wrap(() -> {
                    statusBar.updateSubagentStatus(id, status);
                    activityPanel.refresh();
                    refreshInlineSubagentBlock(tui, activityPanel, id, false);
                    refreshCurrentActivityView(tui, activityPanel);
                    }).run();
                }
                @Override
                public void onSubagentActivity(String id, String summary, String detail) {
                    sessionContext.wrap(() -> {
                    statusBar.appendSubagentActivity(id, summary, detail);
                    activityPanel.refresh();
                    refreshInlineSubagentBlock(tui, activityPanel, id, false);
                    refreshCurrentActivityView(tui, activityPanel);
                    }).run();
                }
                @Override
                public void onSubagentOutput(String id, String chunk) {
                    sessionContext.wrap(() -> {
                    statusBar.appendSubagentOutput(id, chunk);
                    dirtySubagentOutputs.add(id);
                    scheduleSubagentOutputRefresh.run();
                    }).run();
                }
                @Override
                public void onSubagentEnd(String id) {
                    sessionContext.wrap(() -> {
                    statusBar.unregisterSubagent(id);
                    activityPanel.refresh();
                    refreshInlineSubagentBlock(tui, activityPanel, id, false);
                    refreshCurrentActivityView(tui, activityPanel);
                    }).run();
                }
            });
            agenticLoop.setBackgroundEligibilityListener(sessionContext.wrap(() -> {
                activityPanel.refresh();
                statusBar.requestRedraw();
                statusBar.getActiveSubagents().forEach(entry ->
                        refreshInlineSubagentBlock(tui, activityPanel, entry.getId(), false));
                statusBar.getRecentSubagents().forEach(entry ->
                        refreshInlineSubagentBlock(tui, activityPanel, entry.getId(), false));
                refreshCurrentActivityView(tui, activityPanel);
            }));
        }

        // Pass terminal ref to ChatCompleter. Streamed lines are recorded in the
        // TUI, then emitted through JLine's thread-safe printAbove path so
        // background output cannot corrupt typing.
        ChatCompleter.setTerminalRef(reader, terminal);
        ChatCompleter.setActivityListener(sessionContext.wrapConsumer(renderer::updateActivity));
        if (hostManaged || tui.isStarted()) {
            // JLine post rows start below the prompt, which is the final row of the
            // transcript scroll region. Render slash candidates in the already-reserved
            // activity panel instead so opening autocomplete cannot scroll the transcript.
            ChatCompleter.setCompletionDisplay(items -> withSessionContext(() -> activityPanel.updateCompletions(items)));
            ChatCompleter.setContentRedraw(sessionContext.wrap(tui::redrawContentView));
            ChatCompleter.setContentOutput(sessionContext.wrapConsumer(tui::recordInScrollRegion));
            ChatCompleter.setAlertOutput(sessionContext.wrapConsumer(tui::showAlert));
            ChatCompleter.setTranscriptBlockOutput((key, content) ->
                withSessionContext(() -> tui.upsertMainTranscriptBlock(key, content)));
        } else {
            ChatCompleter.setCompletionDisplay(null);
            ChatCompleter.setContentRedraw(null);
            ChatCompleter.setContentOutput(null);
            ChatCompleter.setAlertOutput(null);
            ChatCompleter.setTranscriptBlockOutput(null);
        }

        if (tui.isStarted()) {
            if (!hostManaged) {
                codeIndexAlertCleanup = CodeIndexDiagnostics.installAlertSink(tui::showAlert);
                enforcerAlertCleanup = EnforcerDiagnostics.installAlertSink(tui::showAlert);
            }
            coordinationAlertCleanup = coordinationManager.installWarningSink(
                    sessionContext.wrapConsumer(tui::showAlert));
        }
        // Interactive standard and crawl chats both own local schedules. Headless
        // crawl runs use runHeadless(), never reach this initialization, and stay one-shot.
        getScheduledLoopManager();
        getGlobalScheduledLoopManager();
        processManager.addMonitorListener(processExitWakeListener);
        messageHandler.startAcceptingExternalMessages();
        acceptingProcessWakeups.set(true);
        installResourceWakeups();
        // JLine may invoke widgets from its redraw path, outside the lexical read call.
        lineReader.getWidgets().replaceAll((name, widget) ->
                () -> withSessionContext(widget::apply));
    }

    /** Only the input-owner thread may call this, for the foreground session. */
    String readInteractiveLine() {
        if (activeReader == null) throw new IllegalStateException("Chat is detached");
        int termWidth = activeTerminal.getWidth() > 0 ? activeTerminal.getWidth() : 80;
        tui.reestablishScrollRegion();
        enableTranscriptMouse(activeTerminal);
        ChatCompleter.schedulePostRestore();
        String line = activeReader.readLine(buildPrompt(termWidth));
        tui.clearSubmittedInput();
        return line;
    }

    /** Queue edits retain priority over host slash commands. */
    boolean ownsInteractiveInput() {
        return editingQueuedMessageId != null || permissionService.hasPendingPrompt();
    }

    boolean submitInteractiveLine(String line) throws Exception {
        // Queue editing owns the whole line, including leading slash text.
        // Update the existing queue item rather than dispatching a duplicate.
        if (editingQueuedMessageId != null) {
            finishQueuedMessageEdit(line);
            return true;
        }
        if (permissionService.submitPromptResponse(line)) return true;
        if (line == null || line.isBlank()) return true;

        String trimmed = line.trim();
        String viewedSubagentId = activityPanel.viewedSubagentId();
        if (!trimmed.startsWith("/") && !viewedSubagentId.isBlank()) {
            if (activityPanel.trySendMessageToViewedSubagent(trimmed)) {
                refreshCurrentActivityView(tui, activityPanel);
                return true;
            }
            // The child no longer accepts follow-ups: continue this same message
            // through Main instead of silently consuming it.
            tui.showMainView();
        }

        // Accepted parent input, including bracketed paste and slash commands,
        // returns from a process/tool transcript to Main before dispatch.
        if (!activityPanel.isViewingMain()) {
            activityPanel.returnToMain();
            tui.showMainView();
        }
        if (trimmed.startsWith("/")) {
            if (hostManaged && MultiChatSessionHost.unsupportedCommand(trimmed)) {
                ChatCompleter.printAbove("This command is unavailable in --multi-session; use a separate ordinary chat invocation.");
                return true;
            }
            if (hostManaged && trimmed.equalsIgnoreCase("/help")) {
                ChatCompleter.printAbove(MultiChatSessionHost.HELP);
            }
            return tui.runCommandOutput(() -> commandRouter.handleSlashCommand(trimmed));
        }
        // The accepted editor rows are gone; keep one retained transcript copy.
        tui.recordInScrollRegion("kompile> " + trimmed);
        messageHandler.handleChatMessage(trimmed);
        return true;
    }

    /** Final disposal only, never called when switching focus. */
    void finishInteractive(boolean normalSessionEnd, IOException terminalFailure) {
        if (interactiveFinished) return;
        interactiveFinished = true;
        LineReader reader = retainedReader;
        Terminal terminal = retainedTerminal;
            acceptingProcessWakeups.set(false);
            agenticLoop.setBackgroundEligibilityListener(null);
            messageHandler.shutdown();
            stopGeneratingSpinner();
            auxiliaryActivityRedraw = () -> { };
            projectActivityController.close();
            closeAuxiliaryRepls();
            processManager.removeMonitorListener(processExitWakeListener);
            shutdownScheduledLoops();
            codeIndexAlertCleanup.run();
            coordinationAlertCleanup.run();
            enforcerAlertCleanup.run();
            tui.clearAlert();
            if (editingQueuedMessageId != null) {
                messageQueue.cancelEdit(editingQueuedMessageId);
                editingQueuedMessageId = null;
            }
            permissionService.cancelPendingPrompts();
            try {
                if (reader != null) reader.getHistory().save();
            } catch (IOException historyFailure) {
                if (terminalFailure != null) {
                    terminalFailure.addSuppressed(historyFailure);
                } else {
                    System.err.println("Warning: Could not save chat input history: "
                            + historyFailure.getMessage());
                }
            }

            if (crawlRunStore != null && runController != null) {
                crawlRunStore.checkpoint(runController, "session_closed");
                crawlRunStore.event("session_closed", runController.state().name());
            }

            // A cleared conversation remains resumable as its own transcript, but do
            // not flash an exit summary while the owning command redraws a fresh chat.
            if (newConversationRequested) {
                chatHistory.logSystem("Conversation cleared; continuing in a new transcript.");
            } else if (normalSessionEnd) {
                lifecycleManager.printSessionSummary(false);
            } else {
                chatHistory.logSystem(
                        "Session interrupted unexpectedly; the transcript was preserved for resume.");
            }
            chatHistory.close();

            // Save metrics JSON alongside transcript
            if (chatHistory.getTranscriptFile() != null) {
                Path metricsFile = chatHistory.getTranscriptFile().resolveSibling(sessionId + ".metrics.json");
                sessionMetrics.saveToFile(metricsFile, objectMapper);
            }

            // Export the full transcript into the current project's versioned
            // data/chats/ surface so the conversation is locally versioned, not
            // left only in the global ~/.kompile/conversations store.
            exportTranscriptToProject();

            // Stop the unified TUI (resets scroll regions, stops refresh threads)
            tui.detachLineReader();
            tui.stop();
            ChatCompleter.clearTerminalRef(reader);
            ChatCompleter.setQueueSupplier(null);
            renderer.detachTerminal();
            activeReader = null;
            activeTerminal = null;
            modelPickerActive = false;

            // Clean up provider-owned native processes before the general process manager.
            if (directClient != null) {
                directClient.close();
            }

            // Close project MCP children before the general process manager.
            dashboardController.close();
            mcpBundleTools.close();

            // Clean up background process manager to prevent shutdown hook leak
            processManager.close();
            coordinationManager.shutdown();

            // Properly clean up JLine terminal state.
            // Catch Exception AND IOError — JLine wraps stty failures in
            // IOError (extends Error) when the thread is interrupted during
            // shutdown, especially in GraalVM native images.
            try {
                if (!hostManaged && terminal != null) {
                    disableTranscriptMouse(terminal);
                    terminal.writer().print("\033[2J");
                    terminal.writer().flush();
                }
            } catch (Exception | IOError e) {
                // Ignore cleanup errors - terminal may already be in bad state
            }

            if (!newConversationRequested) {
                boolean resumeAvailable = shouldPrintPostExitResumeCommand(
                        forceAgentic, chatHistory.getTranscriptFile());
                if (normalSessionEnd) {
                    printPostExitResumeCommand(System.out, sessionId, resumeAvailable);
                } else {
                    printUnexpectedExitResumeCommand(System.err, sessionId, resumeAvailable);
                }
            }
    }

    void configureHost(ScheduledLoopManager projectLoops) {
        if (interactiveInitialized) throw new IllegalStateException("Chat already initialized");
        hostManaged = true;
        globalScheduledLoopManager = Objects.requireNonNull(projectLoops);
    }

    void installRetainedOutput() {
        ChatCompleter.setContentOutput(sessionContext.wrapConsumer(tui::recordInScrollRegion));
        ChatCompleter.setTranscriptBlockOutput((key, content) ->
                withSessionContext(() -> tui.upsertMainTranscriptBlock(key, content)));
    }

    void showHostMessage(String message) {
        // Host controls, like ordinary slash commands, report in Main. Diagnostics
        // use ChatCompleter directly so they do not steal an activity view's focus.
        if (!activityPanel.isViewingMain()) {
            activityPanel.returnToMain();
            tui.showMainView();
        }
        ChatCompleter.printAbove(message);
    }

    void dispatchHostProjectLoop(String prompt) {
        if (interactiveFinished) {
            throw new IllegalStateException("Project loop target (first session) is closed; restart the multi-session host");
        }
        dispatchScheduledLoop(prompt);
    }

    void detachInteractive() {
        if (activeReader == null) return;
        disableTranscriptMouse(activeTerminal);
        ChatCompleter.detachTerminalRef(retainedReader);
        tui.detachTerminal();
        renderer.detachTerminal();
        activeReader = null;
        activeTerminal = null;
    }

    void attachInteractive() {
        if (interactiveFinished) throw new IllegalStateException("Chat is closed");
        activeReader = retainedReader;
        activeTerminal = retainedTerminal;
        renderer.attachTerminal(activeTerminal, readyTerminalTitle(defaultTerminalTitle()));
        tui.attachLineReader(activeReader);
        tui.attachTerminal(activeTerminal);
        ChatCompleter.setTerminalRef(activeReader, activeTerminal);
        enableTranscriptMouse(activeTerminal);
        activityPanel.refresh();
        tui.redrawContentView();
    }

    static IOException terminalReadFailure(IOError error) {
        Throwable cause = error == null ? null : error.getCause();
        String detail = cause != null && cause.getMessage() != null
                ? cause.getMessage()
                : error != null && error.getMessage() != null
                        ? error.getMessage()
                        : "unknown terminal error";
        return new IOException("Terminal I/O failed: " + detail, error);
    }

    static boolean shouldPrintPostExitResumeCommand(boolean forceAgentic, Path transcriptFile) {
        // Server chat uses MCP transport but is still standard Kompile Chat. Only
        // force-agentic crawl flows and sessions without a persisted transcript are excluded.
        return !forceAgentic && transcriptFile != null && Files.isRegularFile(transcriptFile);
    }

    static void printPostExitResumeCommand(PrintStream out, String sessionId, boolean enabled) {
        if (!enabled || out == null || sessionId == null || sessionId.isBlank()) {
            return;
        }
        out.println();
        out.println("Resume this chat:");
        out.println("  kompile chat --resume " + sessionId + " --mode standard");
        out.flush();
    }

    static void printUnexpectedExitResumeCommand(
            PrintStream out, String sessionId, boolean enabled) {
        if (!enabled || out == null || sessionId == null || sessionId.isBlank()) {
            return;
        }
        out.println();
        out.println("Chat ended unexpectedly; the transcript was preserved.");
        out.println("Resume this interrupted chat:");
        out.println("  kompile chat --resume " + sessionId + " --mode standard");
        out.flush();
    }

    ScheduledLoopManager getScheduledLoopManager() {
        ScheduledLoopManager existing = scheduledLoopManager;
        if (existing != null) return existing;
        synchronized (this) {
            if (scheduledLoopManager == null) {
                scheduledLoopManager = new ScheduledLoopManager(
                        sessionContext.wrapConsumer(this::dispatchScheduledLoop),
                        ScheduledLoopManager.stateFileForSession(sessionId));
            }
            return scheduledLoopManager;
        }
    }

    ScheduledLoopManager getGlobalScheduledLoopManager() {
        ScheduledLoopManager existing = globalScheduledLoopManager;
        if (existing != null) return existing;
        synchronized (this) {
            if (globalScheduledLoopManager == null) {
                globalScheduledLoopManager = new ScheduledLoopManager(
                        sessionContext.wrapConsumer(this::dispatchScheduledLoop),
                        ScheduledLoopManager.stateFileForProject(workingDirectory));
            }
            return globalScheduledLoopManager;
        }
    }

    private void shutdownScheduledLoops() {
        ScheduledLoopManager sessionLoops = scheduledLoopManager;
        if (sessionLoops != null) sessionLoops.shutdown();
        ScheduledLoopManager globalLoops = globalScheduledLoopManager;
        if (globalLoops != null && !hostManaged) globalLoops.shutdown();
    }

    void dispatchScheduledLoop(String prompt) {
        if (prompt == null || prompt.isBlank()) return;
        messageHandler.runScheduledDispatch(sessionContext.wrap(() -> {
            ChatCompleter.showNotice(renderer.cyan("  ⟳ Scheduled loop fired")
                    + renderer.dim(" → " + StringUtils.truncate(prompt, 60)));
            if (prompt.stripLeading().startsWith("/")) {
                if (hostManaged) {
                    // Scheduled callbacks never borrow the host's reader or change its focus.
                    ChatCompleter.printAbove("Multi-session scheduled loops accept chat prompts, not slash commands.");
                    return;
                }
                commandRouter.handleSlashCommand(prompt.strip());
            } else {
                messageHandler.handleChatMessage(prompt.strip());
            }
            statusBar.requestRedraw();
        }));
    }

    void installResourceWakeups() {
        if (!forceAgentic) {
            coordinationManager.activityWaits().setWakeHandler(sessionId, sessionContext.wrapConsumer(message -> {
                if (!acceptingProcessWakeups.get()) {
                    throw new IllegalStateException("Chat is not accepting resource wakeups");
                }
                messageHandler.handleExternalMessage(message);
            }));
        }
    }

    private void handleProcessExitWakeup(BackgroundProcessManager.ProcessEntry entry,
                                         BackgroundProcessManager.ProcessMonitor monitor) {
        if (!acceptingProcessWakeups.get() || forceAgentic || entry == null
                || monitor == null) {
            return;
        }
        int exitCode = entry.getExitCode() == null ? -1 : entry.getExitCode();
        String description = entry.getDescription() == null
                ? entry.getCommand() : entry.getDescription();
        String message = "[System process completion]\n"
                + "Process " + entry.getId() + " finished with state "
                + entry.getState().name().toLowerCase(Locale.ROOT)
                + " and exit code " + exitCode + ".\n"
                + "Description: " + description + "\n"
                + "Duration: " + ProcessManagementTool.formatDuration(entry.getDuration()) + "\n"
                + "Output log: " + entry.getOutputFile() + "\n"
                + (monitor.message().isBlank() ? ""
                : "Monitor instructions: " + monitor.message() + "\n")
                + "Inspect the process output if relevant, then continue the parent task.";
        ChatCompleter.showNotice(renderer.cyan("  ↻ Monitored process " + entry.getId()
                + " exited; waking agent"));
        messageHandler.handleExternalMessage(message);
    }

    /** Turn a terminal Ctrl+B task into a mandatory agent turn, not just a UI notification. */
    private void handleBackgroundTaskCompletion(BackgroundTaskManager.BackgroundTask task) {
        if (task == null || !task.wasBackgrounded() || forceAgentic || cancelSignal.get()) return;
        String output = task.getOutput() == null ? "" : task.getOutput().strip();
        if (output.length() > 2_000) {
            output = output.substring(0, 2_000) + "\n… [truncated; inspect the task log]";
        }
        String message = "[System background task completion]\n"
                + "Background task " + task.getId() + " finished with state "
                + task.getStatus().name().toLowerCase(Locale.ROOT) + ".\n"
                + "Description: " + task.getDescription() + "\n"
                + "Duration: " + task.getElapsedTime() + "\n"
                + (task.getError() == null ? "" : "Error: " + task.getError().getMessage() + "\n")
                + (output.isBlank() ? "" : "Captured output:\n" + output + "\n")
                + "Review the completed task, use its result if relevant, and continue the user's work.";
        ChatCompleter.showNotice(renderer.cyan(
                "  ↻ Background task " + task.getId() + " finished; waking agent"));
        messageHandler.handleExternalMessage(message);
    }

    /**
     * Best-effort export of this session's transcript into the current project's
     * versioned {@code data/chats/} directory and chat catalog, so the conversation
     * is captured as locally-versioned project metadata rather than living only in
     * the global {@code ~/.kompile/conversations} store.
     *
     * <p>No-op when the working directory is not inside a kompile project, or when
     * the transcript was never written (an empty session). Failures are swallowed —
     * exporting must never break session shutdown.</p>
     */
    private void exportTranscriptToProject() {
        try {
            Path transcript = chatHistory.getTranscriptFile();
            if (transcript == null || !Files.exists(transcript)) {
                return;
            }
            KompileProjectStore store = new KompileProjectStore();
            Optional<Path> projectRoot = store.findProjectRoot(workingDirectory);
            if (projectRoot.isEmpty()) {
                return;
            }
            Path root = projectRoot.get();
            Path chatsDir = root.resolve("data/chats");
            Files.createDirectories(chatsDir);

            // Copy the full transcript content (not just a catalog stub) so the
            // conversation is fully reproducible from the versioned project.
            Files.copy(transcript, chatsDir.resolve(sessionId + ".txt"),
                    StandardCopyOption.REPLACE_EXISTING);

            // Upsert the catalog entry, preserving the original createdAt if present.
            List<KompileProjectChatSession> sessions = new ArrayList<>(store.listChatSessions(root));
            String now = Instant.now().toString();
            String createdAt = sessions.stream()
                    .filter(s -> sessionId.equals(s.getSessionId()))
                    .map(KompileProjectChatSession::getCreatedAt)
                    .filter(Objects::nonNull)
                    .findFirst()
                    .orElse(now);
            String exportedTitle = sessionTitle.get();
            if (exportedTitle == null) {
                exportedTitle = deriveTranscriptTitle(transcript);
            }
            sessions.removeIf(s -> sessionId.equals(s.getSessionId()));
            sessions.add(KompileProjectChatSession.builder()
                    .sessionId(sessionId)
                    .title(exportedTitle)
                    .source("kompile-cli")
                    .messageCount(sessionMetrics.getTotalTurns())
                    .createdAt(createdAt)
                    .updatedAt(now)
                    .build());
            store.writeChatCatalog(root, sessions);
        } catch (Exception e) {
            // Best-effort — never fail session shutdown on export.
        }
    }

    /**
     * Derives a short, human-readable title from the first user message in the
     * transcript (lines are prefixed with {@code "> "}). Falls back to the session id.
     */
    private String deriveTranscriptTitle(Path transcript) {
        String title = findTranscriptTitle(transcript);
        return title == null ? "Session " + sessionId : title;
    }

    private String findTranscriptTitle(Path transcript) {
        try {
            boolean insideReminderBlock = false;
            for (String line : Files.readAllLines(transcript)) {
                if (!line.startsWith("> ")) {
                    continue;
                }
                String content = line.substring(2);
                // Skip reminder decoration so resumed titles keep the original wording.
                if (ReminderManager.opensReminderBlock(content)) {
                    insideReminderBlock = true;
                    continue;
                }
                if (insideReminderBlock) {
                    if (ReminderManager.closesReminderBlock(content)) {
                        insideReminderBlock = false;
                    }
                    continue;
                }
                String title = ChatSessionTitle.fromPrompt(content);
                // Remaining '<' openers are command/infra wrappers, not user content.
                if (title != null && !title.startsWith("<command-")
                        && !title.startsWith("<local-command-")) {
                    return title;
                }
            }
        } catch (Exception ignored) {
            // fall through to no stored title
        }
        return null;
    }

    private void restoreSessionTitle() {
        if (sessionTitle.get() != null) return;
        String restored = chatHistory.readSessionTitle();
        if (restored == null) {
            restored = findTranscriptTitle(chatHistory.getTranscriptFile());
        }
        if (restored != null) {
            sessionTitle.replace(restored);
        }
    }

    /** Set the title once from the first user prompt accepted by this session. */
    void initializeSessionTitleFromPrompt(String prompt) {
        if (sessionTitle.initializeFromPrompt(prompt)) {
            // Persist the [title] marker so resume and compaction keep the original wording.
            chatHistory.logSessionTitle(sessionTitle.get());
            // The shared registry can block on another session's file lock/fsync.
            // Mirror on the existing turn owner, never on the input thread.
            sessionTitleSyncPending.set(true);
            if (activeTerminal != null) {
                renderer.setReadyTerminalTitle(sessionTitle.get());
            }
        }
    }

    /** Called by the turn owner before model work; no extra executor is needed. */
    void syncPendingSessionTitle() {
        if (sessionTitleSyncPending.compareAndSet(true, false)) {
            lifecycleManager.syncSessionTitle(sessionTitle.get());
        }
    }

    /** Replace the prompt-derived title with an explicit user-provided title. */
    String setSessionTitle(String title) {
        String updated = sessionTitle.replace(title);
        chatHistory.logSessionTitle(updated);
        if (activeTerminal != null) {
            renderer.setReadyTerminalTitle(updated);
        }
        return updated;
    }

    String displayedSessionTitle() {
        return readyTerminalTitle(defaultTerminalTitle());
    }

    String currentSessionTitle() {
        return sessionTitle.get();
    }

    private String defaultTerminalTitle() {
        return "kompile chat" + (localMode ? " (local)" : " — " + agentName);
    }

    private String readyTerminalTitle(String fallback) {
        String current = sessionTitle.get();
        return current == null ? fallback : current;
    }

    // ── Role assignment at startup (called by ChatCommand) ───────────────────

    /**
     * Assign a role at startup (before REPL loop starts).
     * Called by ChatCommand when --role or --roles is used.
     */
    public void assignRoleAtStartup(String roleName) {
        RoleConfig role = roleManager.setActiveRole(roleName);
        if (role == null) {
            System.out.println(renderer.yellow("  Warning: Role not found: ") + roleName);
            System.out.println(renderer.dim("  Continuing with default agent"));
            System.out.println();
            return;
        }

        // Update the agent name to reflect the role
        String oldAgentName = agentName;
        agentName = role.getName();

        // Update the agentic loop with the role's agent config
        AgentConfig roleAgentConfig = role.toAgentConfig();
        agenticLoop.setAgentConfig(roleAgentConfig);

        System.out.println();
        System.out.println(renderer.green("  ✓ Role assigned: ") + renderer.cyan(role.getName()));
        System.out.println("  " + role.getDisplayName() + renderer.dim(" - " + role.getDescription()));
        System.out.println();
        System.out.println(renderer.dim("  Agent set to " + agentName + " (was " + oldAgentName + ")"));
        System.out.println(renderer.dim("  Use /roles to change"));
        System.out.println();

        // Track in metrics
        sessionMetrics.setAgentName(agentName);
        sessionMetrics.setActiveRole(role.getName());
    }

    // ── Status bar accessor ───────────────────────────────────────────────────

    /**
     * Expose the status bar for subagent tracking from external callers.
     */
    public StatusBar getStatusBar() {
        return statusBar;
    }

    /**
     * Expose the unified TUI for external callers that need layout access.
     */
    public KompileTui getTui() {
        return tui;
    }

    // ── TUI prompt building ───────────────────────────────────────────────────

    /**
     * Builds the prompt string sized to the given terminal width.
     */
    private String buildPrompt(int termWidth) {
        // Standard chat reports completion live via handleBackgroundTaskCompletion.
        // Drain its legacy queue without replaying expired notices at the next prompt.
        List<BackgroundTaskManager.BackgroundTask> notifications = backgroundTaskManager.drainNotifications();
        if (forceAgentic && !notifications.isEmpty()) {
            for (BackgroundTaskManager.BackgroundTask task : notifications) {
                ChatCompleter.showNotice("Background task [" + task.getId() + "] "
                        + task.getStatus().name().toLowerCase(Locale.ROOT) + " · "
                        + task.getElapsedTime() + " · /jobs shows retained output");
            }
        }

        StringBuilder prompt = new StringBuilder();
        prompt.append("kompile");
        String viewedSubagent = activityPanel.viewedSubagentId();
        if (!viewedSubagent.isBlank()) {
            prompt.append("[").append(viewedSubagent).append("]");
        }
        if (editingQueuedMessageId != null) {
            prompt.append("[edit:").append(editingQueuedMessageId).append("]");
        }

        // Queue count and chain progress are live status-bar state. Embedding them
        // in readLine's immutable prompt leaves stale kompile[N] text after dequeue
        // or Up-to-edit and makes acknowledged messages look permanently queued.

        // Show planning mode indicator
        if (agenticLoop.isPlanningMode()) {
            prompt.append(renderer.cyan("[plan]"));
        }

        prompt.append("> ");
        return prompt.toString();
    }

    /**
     * Starts the animated "Generating..." spinner with terminal title update.
     * Call {@link #stopGeneratingSpinner()} when the response starts arriving.
     */
    public void printGeneratingIndicator() {
        String chainInfo = "";
        if (backgroundTaskManager.isInQueueChain()) {
            int current = backgroundTaskManager.getQueueChainCurrent();
            int total = backgroundTaskManager.getQueueChainTotal();
            chainInfo = renderer.dim(" [" + current + "/" + total + "]");
        }
        stopGeneratingSpinner(); // stop any previous spinner
        if (!uiSession.usesLegacyOutput() || ChatCompleter.hasLineReader()) {
            // JLine owns the editable row. Activity belongs in the fixed status
            // bar; a carriage-return spinner corrupts the prompt and can scroll.
            ChatCompleter.setActivity("Thinking");
            // ChatCompleter.setActivity drives both the status bar and the tab title.
            statusBar.requestRedraw();
            return;
        }
        generatingSpinner = renderer.startGeneratingSpinner(chainInfo);
    }

    /**
     * Stops the animated generating spinner and clears the line.
     */
    public void stopGeneratingSpinner() {
        TerminalRenderer.SpinnerHandle spinner = generatingSpinner;
        if (spinner != null) {
            spinner.stop();
            generatingSpinner = null;
        }
        if (!uiSession.usesLegacyOutput() || ChatCompleter.hasLineReader()) {
            // First output is the transition from model thinking to streamed response.
            // Completion/error paths clear the activity explicitly later.
            String activity = ChatCompleter.getActivity();
            if (activity != null && activity.toLowerCase(Locale.ROOT).contains("think")) {
                ChatCompleter.setActivity("Responding");
            }
            statusBar.requestRedraw();
        }
    }

    // ── Queue editing / auto-dequeue ─────────────────────────────────────────

    private void finishQueuedMessageEdit(String line) {
        String id = editingQueuedMessageId;
        editingQueuedMessageId = null;
        if (id == null) return;

        String content = line == null ? "" : line.strip();
        if (content.isBlank()) {
            messageQueue.cancelEdit(id);
            ChatCompleter.showNotice(renderer.dim("  Queue edit cancelled [" + id + "]"));
            activityPanel.refresh();
            return;
        }

        if (!messageQueue.update(id, content)) {
            tui.printInScrollRegion(renderer.yellow(
                    "  Queued message no longer exists [" + id + "]"));
            activityPanel.refresh();
            return;
        }

        ChatCompleter.showNotice(renderer.green("  ✓ Updated queued message [" + id + "]"));
        activityPanel.refresh();
        if (!llmBusy && isAutoDequeueEnabled()) {
            MessageQueue.QueuedMessage next = messageQueue.peek();
            if (next != null && next.getId().equals(id)) {
                queueManager.sendNextQueuedMessage();
            }
        }
    }

    // ── Auto-dequeue / task completion ───────────────────────────────────────

    /**
     * Completes the current task. Successor dispatch is deferred until the
     * ChatMessageHandler owner thread releases its active resources, where
     * mandatory process events are prioritized ahead of ordinary user input.
     */
    public void completeTaskWithAutoDequeue() {
        backgroundTaskManager.completeCurrentTask();
        ChatCompleter.setActivity(null);
    }

    /**
     * Called after ChatMessageHandler has cleared the completed dispatch owner.
     * In particular, an Escape interruption reaches this point only after the old
     * thread can no longer overwrite the next turn's active-thread references.
     */
    void dispatchQueuedMessageAfterTurnRelease() {
        if (llmBusy) return;
        dispatchNextQueuedMessageIfEligible();
    }

    private void dispatchNextQueuedMessageIfEligible() {
        if (llmBusy) return;

        // Continue either the normal auto-dequeue policy or an explicit
        // /queue-send-all chain. Both consult the queue manager's single state.
        if ((isAutoDequeueEnabled() || backgroundTaskManager.isInQueueChain())
                && !messageQueue.isEmpty()) {
            MessageQueue.QueuedMessage nextMsg = messageQueue.peek();
            if (nextMsg != null) {
                if (nextMsg.getStatus()
                        == MessageQueue.QueuedMessage.QueuedMessageStatus.EDITING) {
                    if (backgroundTaskManager.isInQueueChain()) {
                        backgroundTaskManager.endQueueChain();
                    }
                    ChatCompleter.showNotice(renderer.yellow(
                            "  Queue paused while [" + nextMsg.getId() + "] is being edited"));
                    statusBar.requestRedraw();
                    return;
                }
                // Start chain tracking if not already in a chain
                if (!backgroundTaskManager.isInQueueChain()) {
                    backgroundTaskManager.startQueueChain(messageQueue.size());
                }
                backgroundTaskManager.advanceQueueChain();

                int current = backgroundTaskManager.getQueueChainCurrent();
                int total = backgroundTaskManager.getQueueChainTotal();
                int remaining = messageQueue.size() - 1;

                MessageQueue.QueuedMessage dequeued =
                        messageHandler.dispatchNextQueuedMessage();
                if (dequeued == null) {
                    backgroundTaskManager.endQueueChain();
                    ChatCompleter.showNotice(renderer.yellow(
                            "  Queue paused because the upcoming message is being edited"));
                    statusBar.requestRedraw();
                    return;
                }

                ChatCompleter.showNotice(renderer.green("  ✓ Complete ")
                        + renderer.dim("→ sending next (" + current + "/" + total + ") → ")
                        + StringUtils.truncate(dequeued.getContent(), 60)
                        + (remaining > 0 ? renderer.dim(" (" + remaining + " more queued)") : ""));
                statusBar.requestRedraw();
                sessionMetrics.recordMessageAutoDequeued();
                return;
            }
        }

        // End queue chain if we're done
        if (backgroundTaskManager.isInQueueChain()) {
            int total = backgroundTaskManager.getQueueChainTotal();
            backgroundTaskManager.endQueueChain();
            ChatCompleter.showNotice(renderer.green("  ✓ All " + total + " queued messages processed"));
            statusBar.requestRedraw();
        }
    }

    /**
     * Finish an interrupted turn. Its queued successor is intentionally not
     * claimed here; dispatchTurn invokes the owner-release hand-off immediately
     * after the interrupted thread has relinquished its active resources.
     */
    public void completeTaskWithoutAutoDequeue() {
        backgroundTaskManager.completeCurrentTask();
        // requestCancel/setActivityAfterTurn already published the transient
        // interruption state. Keep it visible until the next turn replaces it.
        statusBar.requestRedraw();
    }

    // ── Key binding helper ────────────────────────────────────────────────────

    static final String STANDARD_CHAT_UP_WIDGET = "standard-chat-contextual-up";
    static final String STANDARD_CHAT_DOWN_WIDGET = "standard-chat-activity-down";
    static final String STANDARD_CHAT_PARENT_WIDGET = "standard-chat-activity-parent";
    static final String STANDARD_CHAT_PAGE_UP_WIDGET = "standard-chat-transcript-page-up";
    static final String STANDARD_CHAT_PAGE_DOWN_WIDGET = "standard-chat-transcript-page-down";
    static final String STANDARD_CHAT_SCROLL_TOP_WIDGET = "standard-chat-transcript-top";
    static final String STANDARD_CHAT_SCROLL_BOTTOM_WIDGET = "standard-chat-transcript-bottom";
    static final String STANDARD_CHAT_SCROLL_MOUSE_WIDGET = "standard-chat-transcript-mouse";

    static void bindBackgroundKey(Map<String, KeyMap<Binding>> keyMaps) {
        Reference background = new Reference("background-task");
        for (KeyMap<Binding> keyMap : keyMaps.values()) {
            if (keyMap != null) {
                keyMap.bind(background, KeyMap.ctrl('B'));
            }
        }
    }

    enum StandardUpAction {
        MOVE_WITHIN_DRAFT,
        EDIT_LATEST_QUEUED,
        PREVIOUS_HISTORY,
        KEEP_DRAFT
    }

    /**
     * Resolve the one unambiguous action for Up in standard chat.
     */
    static StandardUpAction resolveStandardUpAction(
            String buffer, int cursor, boolean hasQueuedMessage, boolean browsingHistory) {
        String text = buffer == null ? "" : buffer;
        int safeCursor = Math.max(0, Math.min(cursor, text.length()));
        if (text.substring(0, safeCursor).indexOf('\n') >= 0) {
            return StandardUpAction.MOVE_WITHIN_DRAFT;
        }
        if (text.isEmpty() && hasQueuedMessage) {
            return StandardUpAction.EDIT_LATEST_QUEUED;
        }
        if (text.isEmpty() || browsingHistory) {
            return StandardUpAction.PREVIOUS_HISTORY;
        }
        return StandardUpAction.KEEP_DRAFT;
    }

    static void bindStandardChatUpArrow(LineReaderImpl reader, MessageQueue queue) {
        bindStandardChatUpArrow(reader, queue, null, null, ignored -> { });
    }

    private static void bindStandardChatUpArrow(
            LineReaderImpl reader, MessageQueue queue,
            StandardChatActivityPanel activityPanel, KompileTui tui,
            Consumer<String> queueEditStarted) {
        reader.getWidgets().put(STANDARD_CHAT_UP_WIDGET, () -> {
            // The picker borrows this reader, not the chat queue/activity actions.
            if (tui != null && tui.isTemporaryWindowActive()) {
                reader.callWidget(LineReader.UP_LINE_OR_HISTORY);
                return true;
            }
            String text = reader.getBuffer().toString();
            if (activityPanel != null && activityPanel.isFocused()) {
                if (text.isBlank() && activityPanel.selectPrevious()) {
                    redisplayWithContent(reader, tui);
                    return true;
                }
                activityPanel.clearSelection();
            }
            int historyIndex = reader.getHistory().index();
            boolean browsingHistory = reader.getHistory().size() > 0
                    && historyIndex >= reader.getHistory().first()
                    && historyIndex <= reader.getHistory().last();
            StandardUpAction action = resolveStandardUpAction(
                    text, reader.getBuffer().cursor(), !queue.isEmpty(), browsingHistory);

            if (action == StandardUpAction.MOVE_WITHIN_DRAFT
                    || action == StandardUpAction.PREVIOUS_HISTORY) {
                reader.callWidget(LineReader.UP_LINE_OR_HISTORY);
                return true;
            }
            if (action == StandardUpAction.EDIT_LATEST_QUEUED) {
                List<MessageQueue.QueuedMessage> queued = queue.getAll();
                if (!queued.isEmpty()) {
                    MessageQueue.QueuedMessage latest = queued.stream()
                            .filter(message -> message.getStatus()
                                    == MessageQueue.QueuedMessage.QueuedMessageStatus.EDITING)
                            .findFirst()
                            .orElse(queued.get(queued.size() - 1));
                    if (queue.beginEdit(latest.getId())) {
                        queueEditStarted.accept(latest.getId());
                        reader.getBuffer().write(latest.getContent());
                        if (activityPanel != null) {
                            activityPanel.refresh();
                        }
                        ChatCompleter.refreshPostDisplay(reader);
                    }
                }
                return true;
            }

            // A non-empty single-line draft remains untouched. History is still
            // available after clearing the prompt, so Up cannot destroy typed text.
            redisplayWithContent(reader, tui);
            return true;
        });

        Reference contextualUp = new Reference(STANDARD_CHAT_UP_WIDGET);
        KeyMap<Binding> emacs = reader.getKeyMaps().get(LineReader.EMACS);
        emacs.bind(contextualUp, "\033[A", "\033OA");
    }

    static void bindStandardChatActivityKeys(
            LineReaderImpl reader,
            MessageQueue queue,
            StandardChatActivityPanel activityPanel,
            KompileTui tui) {
        bindStandardChatActivityKeys(
                reader, queue, activityPanel, tui, ignored -> { }, () -> "");
    }

    static void bindStandardChatActivityKeys(
            LineReaderImpl reader,
            MessageQueue queue,
            StandardChatActivityPanel activityPanel,
            KompileTui tui,
            Consumer<String> queueEditStarted) {
        bindStandardChatActivityKeys(
                reader, queue, activityPanel, tui, queueEditStarted, () -> "");
    }

    static void bindStandardChatActivityKeys(
            LineReaderImpl reader,
            MessageQueue queue,
            StandardChatActivityPanel activityPanel,
            KompileTui tui,
            Consumer<String> queueEditStarted,
            Supplier<String> clipboardTextSupplier) {
        bindStandardChatActivityKeys(
                reader, queue, activityPanel, tui, queueEditStarted, clipboardTextSupplier,
                text -> ClipboardUtil.copyToClipboardAsync(text, reader.getTerminal()));
    }

    static void bindStandardChatActivityKeys(
            LineReaderImpl reader,
            MessageQueue queue,
            StandardChatActivityPanel activityPanel,
            KompileTui tui,
            Consumer<String> queueEditStarted,
            Supplier<String> clipboardTextSupplier,
            Consumer<String> clipboardCopyConsumer) {
        bindStandardChatUpArrow(reader, queue, activityPanel, tui, queueEditStarted);

        Widget originalDown = reader.getWidgets().get(LineReader.DOWN_LINE_OR_HISTORY);
        reader.getWidgets().put(STANDARD_CHAT_DOWN_WIDGET, () -> {
            if (tui != null && tui.isTemporaryWindowActive()) {
                return originalDown == null || originalDown.apply();
            }
            String text = reader.getBuffer().toString();
            if (text.isBlank() && activityPanel.selectNext()) {
                redisplayWithContent(reader, tui);
                return true;
            }
            if (activityPanel.isFocused()) {
                activityPanel.clearSelection();
            }
            return originalDown == null || originalDown.apply();
        });
        reader.getKeyMaps().get(LineReader.EMACS).bind(
                new Reference(STANDARD_CHAT_DOWN_WIDGET), "\033[B", "\033OB");

        Widget originalLeft = reader.getWidgets().get(LineReader.BACKWARD_CHAR);
        reader.getWidgets().put(STANDARD_CHAT_PARENT_WIDGET, () -> {
            if (tui != null && tui.isTemporaryWindowActive()) {
                return originalLeft == null || originalLeft.apply();
            }
            if (reader.getBuffer().toString().isBlank() && activityPanel.selectParent()) {
                redisplayWithContent(reader, tui);
                return true;
            }
            return originalLeft == null || originalLeft.apply();
        });
        reader.getKeyMaps().get(LineReader.EMACS).bind(
                new Reference(STANDARD_CHAT_PARENT_WIDGET), "\033[D", "\033OD");

        reader.getWidgets().put(STANDARD_CHAT_PAGE_UP_WIDGET, () -> {
            boolean changed = tui != null && tui.pageContent(1);
            redisplayWithContent(reader, tui);
            return changed;
        });
        reader.getWidgets().put(STANDARD_CHAT_PAGE_DOWN_WIDGET, () -> {
            boolean changed = tui != null && tui.pageContent(-1);
            redisplayWithContent(reader, tui);
            return changed;
        });
        reader.getWidgets().put(STANDARD_CHAT_SCROLL_TOP_WIDGET, () -> {
            boolean changed = tui != null && tui.scrollToTop();
            redisplayWithContent(reader, tui);
            return changed;
        });
        reader.getWidgets().put(STANDARD_CHAT_SCROLL_BOTTOM_WIDGET, () -> {
            boolean changed = tui != null && tui.scrollToBottom();
            redisplayWithContent(reader, tui);
            return changed;
        });
        MouseEvent[] previousMouseEvent = new MouseEvent[1];
        reader.getWidgets().put(STANDARD_CHAT_SCROLL_MOUSE_WIDGET, () -> {
            boolean changed = false;
            try {
                MouseEvent event = readStandardChatMouseEvent(reader, previousMouseEvent[0]);
                if (event != null && tui != null) {
                    previousMouseEvent[0] = event;
                    switch (event.getButton()) {
                        case WheelUp -> changed = tui.scrollContent(3);
                        case WheelDown -> changed = tui.scrollContent(-3);
                        case Button1 -> {
                            switch (event.getType()) {
                                case Pressed -> {
                                    changed = tui.handleScrollToBottomClick(
                                            event.getX(), event.getY());
                                    if (!changed) {
                                        changed = tui.beginTranscriptSelection(
                                                event.getX(), event.getY());
                                    }
                                }
                                case Dragged -> changed = tui.dragTranscriptSelection(
                                        event.getX(), event.getY());
                                case Released -> changed = tui.finishTranscriptSelection(
                                        event.getX(), event.getY());
                                default -> { /* ignore motion without Button1 */ }
                            }
                        }
                        case Button2 -> {
                            if (event.getType() == MouseEvent.Type.Pressed) {
                                String clipboardText = clipboardTextSupplier.get();
                                changed = ChatCompleter.insertPastedText(reader, clipboardText);
                            }
                        }
                        case Button3 -> {
                            if (event.getType() == MouseEvent.Type.Pressed) {
                                String selected = tui.getSelectedTranscriptText();
                                if (!selected.isEmpty()
                                        && tui.isTranscriptCoordinate(event.getX(), event.getY())) {
                                    clipboardCopyConsumer.accept(selected);
                                    changed = true;
                                } else {
                                    String clipboardText = clipboardTextSupplier.get();
                                    changed = ChatCompleter.insertPastedText(reader, clipboardText);
                                }
                            }
                        }
                        case NoButton -> {
                            if (event.getType() == MouseEvent.Type.Released) {
                                changed = tui.finishTranscriptSelection(
                                        event.getX(), event.getY());
                            }
                        }
                    }
                }
            } catch (RuntimeException ignored) {
                // A partial mouse report must never break the active prompt.
            }
            redisplayWithContent(reader, tui);
            return changed;
        });
        // Page keys must follow the active JLine map as well (emacs/vi/inputrc);
        // otherwise scrolling works only in the default map.
        for (KeyMap<Binding> activityKeys : reader.getKeyMaps().values()) {
            if (activityKeys == null) {
                continue;
            }
            activityKeys.bind(new Reference(STANDARD_CHAT_PAGE_UP_WIDGET),
                    keySequences(reader, InfoCmp.Capability.key_ppage,
                            "\033[5~", "\033[5;2~", "\033[1;2A")
                            .toArray(String[]::new));
            activityKeys.bind(new Reference(STANDARD_CHAT_PAGE_DOWN_WIDGET),
                    keySequences(reader, InfoCmp.Capability.key_npage,
                            "\033[6~", "\033[6;2~", "\033[1;2B")
                            .toArray(String[]::new));
            activityKeys.bind(new Reference(STANDARD_CHAT_SCROLL_TOP_WIDGET),
                    "\033[1;5H", "\033[5H");
            activityKeys.bind(new Reference(STANDARD_CHAT_SCROLL_BOTTOM_WIDGET),
                    "\033[1;5F", "\033[5F");
            activityKeys.bind(new Reference(STANDARD_CHAT_SCROLL_MOUSE_WIDGET),
                    keySequences(reader, InfoCmp.Capability.key_mouse,
                            "\033[M", "\033[<")
                            .toArray(String[]::new));
        }

        Widget originalAccept = reader.getWidgets().get(LineReader.ACCEPT_LINE);
        if (originalAccept != null) {
            reader.getWidgets().put(LineReader.ACCEPT_LINE, () -> {
                if (tui != null && tui.isTemporaryWindowActive()) return originalAccept.apply();
                if (activityPanel.isFocused() && reader.getBuffer().toString().isBlank()) {
                    showActivityView(tui, activityPanel.openSelectedView());
                    redisplayWithContent(reader, tui);
                    return true;
                }
                if (activityPanel.isFocused()) {
                    activityPanel.clearSelection();
                }
                return originalAccept.apply();
            });
        }

        Widget originalDelete = reader.getWidgets().get(LineReader.DELETE_CHAR);
        if (originalDelete != null) {
            reader.getWidgets().put(LineReader.DELETE_CHAR, () -> {
                if (tui != null && tui.isTemporaryWindowActive()) return originalDelete.apply();
                if (activityPanel.isFocused() && reader.getBuffer().toString().isBlank()) {
                    String result = activityPanel.killSelected();
                    StandardChatActivityPanel.ActivityView view = activityPanel.currentView();
                    if (view != null && !view.main()) {
                        showActivityView(tui, view);
                    } else {
                        ChatCompleter.showNotice(result);
                    }
                    redisplayWithContent(reader, tui);
                    return true;
                }
                if (activityPanel.isFocused()) {
                    activityPanel.clearSelection();
                }
                return originalDelete.apply();
            });
        }

        Widget originalSelfInsert = reader.getWidgets().get(LineReader.SELF_INSERT);
        if (originalSelfInsert != null) {
            reader.getWidgets().put(LineReader.SELF_INSERT, () -> {
                if (tui != null && tui.isTemporaryWindowActive()) return originalSelfInsert.apply();
                ChatCompleter.clearInterruptedOnInput();
                if (activityPanel.isFocused()) {
                    activityPanel.clearSelection();
                }
                if (!activityPanel.isViewingMain()
                        && activityPanel.viewedSubagentId().isBlank()) {
                    activityPanel.returnToMain();
                    tui.showMainView();
                }
                return originalSelfInsert.apply();
            });
        }

        Widget originalBackspace = reader.getWidgets().get(LineReader.BACKWARD_DELETE_CHAR);
        if (originalBackspace != null) {
            reader.getWidgets().put(LineReader.BACKWARD_DELETE_CHAR, () -> {
                if (tui != null && tui.isTemporaryWindowActive()) return originalBackspace.apply();
                ChatCompleter.clearInterruptedOnInput();
                if (activityPanel.isFocused()) {
                    activityPanel.clearSelection();
                }
                if (!activityPanel.isViewingMain()
                        && activityPanel.viewedSubagentId().isBlank()) {
                    activityPanel.returnToMain();
                    tui.showMainView();
                }
                return originalBackspace.apply();
            });
        }
    }

    private static void redisplayWithContent(LineReaderImpl reader, KompileTui tui) {
        // A plain content repaint moves the physical cursor but does not invalidate
        // JLine's cached prompt. Let the TUI clear and restore the input in one
        // synchronous widget frame so scrolling cannot leave the cursor at column 1.
        if (tui != null && tui.redrawForInputWidget()) {
            return;
        }
        reader.callWidget(LineReader.REDISPLAY);
    }

    private static List<String> keySequences(
            LineReaderImpl reader,
            InfoCmp.Capability capability,
            String... fallbackSequences) {
        LinkedHashSet<String> sequences = new LinkedHashSet<>();
        if (capability != null) {
            try {
                String terminalSequence = reader.getTerminal().getStringCapability(capability);
                if (terminalSequence != null && !terminalSequence.isBlank()) {
                    sequences.add(terminalSequence);
                }
            } catch (RuntimeException ignored) {
                // Fall back to standard ANSI sequences below.
            }
        }
        sequences.addAll(Arrays.asList(fallbackSequences));
        return new ArrayList<>(sequences);
    }

    /** Decode either legacy X10 reports or SGR reports used for drag selection. */
    private static MouseEvent readStandardChatMouseEvent(
            LineReaderImpl reader, MouseEvent previous) {
        if ("\033[<".equals(reader.getLastBinding())) {
            return readSgrMouseEvent(reader.getTerminal(), previous);
        }
        return reader.getTerminal().readMouseEvent();
    }

    private static MouseEvent readSgrMouseEvent(Terminal terminal, MouseEvent previous) {
        StringBuilder report = new StringBuilder(24);
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(100);
        try {
            while (report.length() < 64) {
                long remainingNanos = deadline - System.nanoTime();
                if (remainingNanos <= 0) return null;
                long timeoutMillis = Math.max(1L,
                        TimeUnit.NANOSECONDS.toMillis(remainingNanos));
                int next = terminal.reader().read(timeoutMillis);
                if (next == NonBlockingReader.READ_EXPIRED || next < 0) return null;
                char value = (char) next;
                report.append(value);
                if (value == 'M' || value == 'm') {
                    return parseSgrMouseEvent(report.toString(), previous);
                }
                if (value != ';' && (value < '0' || value > '9')) return null;
            }
        } catch (IOException ignored) {
            // A terminal can disappear between the bound prefix and report body.
        }
        return null;
    }

    static MouseEvent parseSgrMouseEvent(String report, MouseEvent previous) {
        if (report == null || report.length() < 6) return null;
        char terminator = report.charAt(report.length() - 1);
        if (terminator != 'M' && terminator != 'm') return null;
        String[] fields = report.substring(0, report.length() - 1).split(";", -1);
        if (fields.length != 3) return null;
        try {
            int code = Integer.parseInt(fields[0]);
            int reportedX = Integer.parseInt(fields[1]);
            int reportedY = Integer.parseInt(fields[2]);
            if (code < 0 || reportedX <= 0 || reportedY <= 0 || (code & ~0x7f) != 0) return null;
            int x = reportedX - 1;
            int y = reportedY - 1;
            EnumSet<MouseEvent.Modifier> modifiers = EnumSet.noneOf(MouseEvent.Modifier.class);
            if ((code & 4) != 0) modifiers.add(MouseEvent.Modifier.Shift);
            if ((code & 8) != 0) modifiers.add(MouseEvent.Modifier.Alt);
            if ((code & 16) != 0) modifiers.add(MouseEvent.Modifier.Control);

            MouseEvent.Type type;
            MouseEvent.Button button;
            if ((code & 64) != 0) {
                if ((code & 3) > 1 || (code & 32) != 0 || terminator != 'M') return null;
                type = MouseEvent.Type.Wheel;
                button = (code & 1) == 0
                        ? MouseEvent.Button.WheelUp : MouseEvent.Button.WheelDown;
            } else {
                button = switch (code & 3) {
                    case 0 -> MouseEvent.Button.Button1;
                    case 1 -> MouseEvent.Button.Button2;
                    case 2 -> MouseEvent.Button.Button3;
                    default -> MouseEvent.Button.NoButton;
                };
                if (terminator == 'm' || ((code & 3) == 3 && (code & 32) == 0)) {
                    type = MouseEvent.Type.Released;
                    if (button == MouseEvent.Button.NoButton && previous != null) {
                        button = previous.getButton();
                    }
                } else if ((code & 32) != 0) {
                    type = button == MouseEvent.Button.NoButton
                            ? MouseEvent.Type.Moved : MouseEvent.Type.Dragged;
                } else {
                    type = MouseEvent.Type.Pressed;
                }
            }
            return new MouseEvent(type, button, modifiers, x, y);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    /** Enable wheel reports and button-motion reports for transcript drag selection.
     * Right-click copies the managed selection; terminal-native menus require the
     * terminal's mouse override (usually Shift). Disabling capture also disables wheels.
     */
    static void enableTranscriptMouse(Terminal terminal) {
        if (terminal == null) return;
        try {
            terminal.writer().print("\033[?1000l\033[?1003l\033[?1002h\033[?1006h");
            terminal.writer().flush();
        } catch (RuntimeException | IOError ignored) {
            // The terminal may already be shutting down.
        }
    }

    /** Return mouse ownership to the terminal during startup reset, detach, and exit. */
    static void disableTranscriptMouse(Terminal terminal) {
        if (terminal == null) return;
        try {
            terminal.writer().print("\033[?1000l\033[?1002l\033[?1003l\033[?1006l");
            terminal.writer().flush();
        } catch (RuntimeException | IOError ignored) {
            // The terminal may already be shutting down.
        }
    }

    private static void showActivityView(
            KompileTui tui,
            StandardChatActivityPanel.ActivityView view) {
        if (view == null) {
            return;
        }
        if (view.main()) {
            tui.showMainView();
        } else {
            tui.showActivityView(view.key(), view.title(), view.content());
        }
    }

    private static void refreshCurrentActivityView(
            KompileTui tui, StandardChatActivityPanel activityPanel) {
        StandardChatActivityPanel.ActivityView view = activityPanel.currentView();
        if (view == null || view.main()) {
            if (!tui.isMainContentView()) {
                // Re-check the panel selection while the TUI draw lock is held. A
                // delayed Main refresh must not overwrite a child explicitly opened
                // after this callback captured its view snapshot.
                tui.showMainViewIf(activityPanel::isViewingMain);
            }
            return;
        }
        // Explicit selection uses showActivityView. This refresh is identity-guarded
        // so a delayed child callback cannot reopen it after the user returned to Main.
        tui.updateActivityView(view.key(), view.title(), view.content());
    }

    boolean handleProjectActivityCommand(String arguments) {
        String input = arguments == null ? "" : arguments.strip();
        if (input.isBlank() || input.equalsIgnoreCase("list")
                || input.equalsIgnoreCase("local") || input.equalsIgnoreCase("status")) {
            if (input.isBlank() && projectActivityController.isHistoricalBrowserVisible()) {
                projectActivityController.closeBrowser();
            }
            return false;
        }
        String[] parts = input.split("\\s+", 2);
        String command = parts[0].toLowerCase(Locale.ROOT);
        String value = parts.length > 1 ? parts[1].strip() : "";
        if (Set.of("session", "project", "global", "history", "outcomes", "transcript",
                "detail", "enter", "next", "previous", "prev", "search", "confirm", "annotate")
                .contains(command)) {
            projectActivityController.openConversationActivity(input);
            showActivityView(tui, activityPanel.openProjectActivity(""));
            return true;
        }
        switch (command) {
            case "agents", "dashboard" -> showActivityView(
                    tui, liveProjectActivityView());
            case "agent" -> {
                if (value.isBlank()) {
                    tui.printInScrollRegion(renderer.dim(
                            "  Usage: /activity agent <session-prefix|agent|role>"));
                } else {
                    showActivityView(tui, liveProjectActivityView(value));
                }
            }
            case "refresh" -> {
                if (!activityPanel.isViewingProjectActivity()) {
                    showActivityView(tui, liveProjectActivityView());
                }
                activityPanel.refreshProjectActivity();
            }
            case "close", "hide", "off" -> {
                projectActivityController.closeBrowser();
                activityPanel.returnToMain();
                tui.showMainView();
            }
            default -> tui.printInScrollRegion(renderer.dim(
                    "  Usage: /activity [agents | agent <filter> | refresh | close]"));
        }
        return true;
    }

    private StandardChatActivityPanel.ActivityView liveProjectActivityView() {
        projectActivityController.closeBrowser();
        return activityPanel.openProjectActivity("");
    }

    private StandardChatActivityPanel.ActivityView liveProjectActivityView(String filter) {
        projectActivityController.closeBrowser();
        return activityPanel.openProjectActivity(filter);
    }

    private void registerProjectActivityPresence() {
        String provider = localMode && chatConfig != null
                ? chatConfig.getProvider() : "kompile-server";
        if (provider == null || provider.isBlank()) provider = "kompile-chat";
        String role = roleManager.getActiveRoleName();
        if (role == null || role.isBlank()) role = localAgentName;
        String task = currentSessionTitle();
        if (task == null || task.isBlank()) task = "Standard chat session";
        coordinationManager.registerAgent(task, null, provider, 0,
                ProcessHandle.current().pid(), sessionId, role);
    }

    private static void refreshInlineSubagentBlock(
            KompileTui tui,
            StandardChatActivityPanel activityPanel,
            String subagentId,
            boolean includeBackgroundMarker) {
        if (activityPanel.isCurrentTurnBackgrounded() && !includeBackgroundMarker) return;
        String content = activityPanel.inlineSubagentTranscript(subagentId);
        if (!content.isBlank()) {
            tui.upsertMainTranscriptBlock("subagent:" + subagentId, content);
        }
    }

    /**
     * Binds mode shortcuts as true two-key Ctrl+X chords. JLine's bind method
     * accepts independent key sequences as varargs, so passing Ctrl+X and "p"
     * separately would also intercept the printable letter p.
     */
    static void bindModeSwitchingHotkeys(KeyMap<Binding> keyMap) {
        bindCtrlXChord(keyMap, "toggle-plan-mode", 'p');
        bindCtrlXChord(keyMap, "show-todos", 't');
        bindCtrlXChord(keyMap, "cycle-agent", 'a');
    }

    private static void bindCtrlXChord(KeyMap<Binding> keyMap, String widgetName, char key) {
        String prefix = KeyMap.ctrl('X');
        keyMap.bind(
                new Reference(widgetName),
                prefix + Character.toLowerCase(key),
                prefix + Character.toUpperCase(key)
        );
    }

    /** Bind the configured cancel sequence in every keymap JLine may activate. */
    static void bindCancelKey(Map<String, KeyMap<Binding>> keyMaps, String keyBinding) {
        for (KeyMap<Binding> keyMap : keyMaps.values()) {
            if (keyMap != null) {
                keyMap.bind(new Reference("cancel-operation"), keyBinding);
            }
        }
    }

    /**
     * Resolves the cancel key binding string for JLine from the chat config.
     * Supports: ESCAPE (default), Ctrl+<letter> (e.g., "Ctrl+Q"), or raw key strings.
     */
    private String resolveCancelKeyBinding() {
        String key = "ESCAPE";
        if (chatConfig != null && chatConfig.getCancelKey() != null && !chatConfig.getCancelKey().isBlank()) {
            key = chatConfig.getCancelKey().trim().toUpperCase();
        }

        if ("ESCAPE".equals(key) || "ESC".equals(key)) {
            return "\033";
        }

        // Support Ctrl+<letter> format
        if (key.startsWith("CTRL+") && key.length() == 6) {
            char letter = key.charAt(5);
            return KeyMap.ctrl(Character.toUpperCase(letter));
        }

        // Fallback to escape
        return "\033";
    }

    // ── Package-accessible state accessors (used by collaborator classes) ────

    private String localProviderDisplayName() {
        if (chatConfig == null || chatConfig.getProvider() == null
                || chatConfig.getProvider().isBlank()) {
            return "local";
        }
        String provider = chatConfig.getProvider().trim().toLowerCase(java.util.Locale.ROOT);
        return SetupWizard.vendorLabel(SetupWizard.vendorForProvider(provider));
    }

    String getAgentName() { return agentName; }
    void setAgentName(String name) { this.agentName = name; }

    String getLocalAgentName() { return localAgentName; }
    void setLocalAgentName(String name) { this.localAgentName = name; }

    String getBaseUrl() { return baseUrl; }

    Path getWorkingDirectory() { return workingDirectory; }

    boolean isRagEnabled() { return ragEnabled; }
    void setRagEnabled(boolean enabled) { this.ragEnabled = enabled; }

    ChatConfig getChatConfig() { return chatConfig; }

    /**
     * Apply a standard direct-provider change without replacing this REPL,
     * session id, transcript, or the config object shared by local collaborators.
     *
     * @return true when the new configuration is active in this session
     */
    boolean updateChatConfig(ChatConfig config) {
        if (config == null) {
            return false;
        }
        if (!localMode) {
            this.chatConfig = config;
            return true;
        }
        if (this.chatConfig == null || !canHotSwitchLocalProvider(config)) {
            return false;
        }

        String previousProvider = this.chatConfig.getProvider();
        String previousModel = this.chatConfig.getModel();
        this.chatConfig.applyLlmSettingsFrom(config);
        int retainedMessages = agenticLoop.rebuildDirectHistoryForProviderSwitch();
        sessionMetrics.setProvider(this.chatConfig.getProvider());
        sessionMetrics.setModel(this.chatConfig.getModel());
        chatHistory.logSystem("Switched LLM from " + previousProvider + "/" + previousModel
                + " to " + this.chatConfig.getProvider() + "/" + this.chatConfig.getModel()
                + "; retained " + retainedMessages + " conversation messages");
        refreshModelDisplay();
        return true;
    }

    static boolean canHotSwitchLocalProvider(ChatConfig config) {
        return config != null
                && "standard".equalsIgnoreCase(config.getChatMode())
                && config.getProvider() != null
                && !config.isKompileServer()
                && !config.isKompileLocalServing();
    }

    ChatMemory getChatMemory() { return chatMemory; }

    AgentRegistry getAgentRegistry() { return agentRegistry; }

    List<McpSseClient.ToolInfo> getCachedTools() { return cachedTools; }
    void setCachedTools(List<McpSseClient.ToolInfo> tools) { this.cachedTools = tools; }

    boolean isLlmBusy() { return llmBusy; }
    void setLlmBusy(boolean busy) { this.llmBusy = busy; }
    void requestStatusRedraw() { statusBar.requestRedraw(); }

    /**
     * Handle the configured cancel key without turning it into an implicit
     * subagent kill key. A blocking subagent keeps running until the user
     * backgrounds it with Ctrl+B or targets its activity row with Delete.
     */
    boolean requestCancelFromInput() {
        if (agenticLoop.isBlockingSubagentInvocationActive()) {
            String action = backgroundTaskManager.isCurrentTaskBackgroundable()
                    ? "Ctrl+B backgrounds it; ↓ selects its row and Delete stops it"
                    : "↓ selects its row and Delete stops it";
            ChatCompleter.showNotice(renderer.dim("  Subagent continues · " + action));
            requestStatusRedraw();
            return false;
        }
        if (messageHandler != null) {
            // The handler owns the active-turn thread; do not gate this on
            // llmBusy because synchronous tools may run between model phases.
            return messageHandler.requestCancel();
        }
        if (llmBusy) {
            cancelSignal.set(true);
            return true;
        }
        return false;
    }

    String configureResources(String args) {
        if (!ai.kompile.cli.main.chat.tools.ResourceWizard.handles(args))
            return ai.kompile.cli.main.chat.tools.ResourcePolicy.command(getWorkingDirectory(), args);
        LineReader reader = activeReader;
        if (reader == null) return "Resource wizard needs an interactive chat reader.";
        try {
            return ai.kompile.cli.main.chat.tools.ResourceWizard.run(getWorkingDirectory(), args, (lines, question) -> {
                ChatCompleter.setTemporaryWindowActive(true);
                tui.updateTemporaryWindow("Resource configuration", lines);
                return reader.readLine(question);
            });
        } finally {
            ChatCompleter.setTemporaryWindowActive(false);
            tui.closeTemporaryWindow();
            refreshCurrentActivityView(tui, activityPanel);
        }
    }

    /** Run resume-all with a modal using the existing chat reader. */
    int executeResumeAll(String args) {
        LineReader reader = activeReader;
        if (reader == null) return ResumeAllCommand.executeInline(args);
        try {
            return ResumeAllCommand.executeInline(args, (lines, question) -> {
                ChatCompleter.setTemporaryWindowActive(true);
                tui.updateTemporaryWindow("Resume recent sessions", lines);
                return reader.readLine(question);
            });
        } finally {
            ChatCompleter.setTemporaryWindowActive(false);
            tui.closeTemporaryWindow();
            refreshCurrentActivityView(tui, activityPanel);
        }
    }

    /**
     * Open the provider/model switcher as a modal owned by the transcript region.
     * The outer chat prompt has already returned when this is called, so borrowing
     * the active reader is safe and keeps all selection input in the same terminal.
     */
    void openModelProviderPicker() {
        if (!localMode || chatConfig == null) {
            ChatCompleter.printAbove("Provider/model switching is only available in local standard chat.");
            return;
        }
        LineReader reader = activeReader;
        if (reader == null) {
            ChatCompleter.printAbove("The model picker is unavailable until the interactive terminal is ready.");
            return;
        }

        List<String> providers = switchableProviders();
        if (providers.isEmpty()) {
            ChatCompleter.printAbove("No providers can be switched in this session. Use /setup to reconfigure.");
            return;
        }

        String selectedProvider = chatConfig.getProvider();
        String selectedVendor = SetupWizard.vendorForProvider(selectedProvider);
        String selectedModel = chatConfig.getModel();
        boolean committed = false;
        try {
            modelPickerActive = true;
            ChatCompleter.setTemporaryWindowActive(true);
            while (true) {
                tui.updateTemporaryWindow("Provider and model", pickerLines(
                        "Choose a provider", providers, selectedVendor, selectedVendor, selectedProvider, selectedModel));
                String providerInput = reader.readLine("picker provider (number/name, Esc cancels): ");
                if (providerInput == null || providerInput.isBlank()
                        || "cancel".equalsIgnoreCase(providerInput.trim())) {
                    return;
                }
                if ("back".equalsIgnoreCase(providerInput.trim())) {
                    continue;
                }
                String providerChoice = parsePickerChoice(providerInput, providers);
                if (providerChoice == null) {
                    tui.updateTemporaryWindow("Provider and model", List.of(
                            "Invalid provider: " + providerInput.trim(),
                            "Choose a numbered provider or its exact name.",
                            "Current: " + activeModelDisplayName()));
                    continue;
                }
                selectedVendor = providerChoice;

                List<SetupWizard.AuthMethod> authMethods =
                        SetupWizard.authMethodsForPicker(selectedVendor);
                if (authMethods.isEmpty()) {
                    tui.updateTemporaryWindow("Provider and model", List.of(
                            "No configured authentication route exists for "
                                    + SetupWizard.vendorLabel(selectedVendor) + ".",
                            "Run /setup to configure this provider."));
                    continue;
                }
                SetupWizard.AuthMethod selectedAuth =
                        selectedVendor.equalsIgnoreCase(SetupWizard.vendorForProvider(selectedProvider))
                                ? SetupWizard.authMethodForProvider(selectedProvider)
                                : authMethods.get(0);
                if (!authMethods.contains(selectedAuth)) {
                    selectedAuth = authMethods.get(0);
                }
                if (authMethods.size() > 1) {
                    List<String> authChoices = authMethods.stream()
                            .map(SetupWizard::authMethodLabel).toList();
                    boolean backToProvider = false;
                    while (true) {
                        tui.updateTemporaryWindow("Provider and model", pickerLines(
                                "Choose authentication for " + SetupWizard.vendorLabel(selectedVendor),
                                authChoices,
                                SetupWizard.authMethodLabel(selectedAuth),
                                selectedVendor, selectedProvider, selectedModel));
                        String authInput = reader.readLine(
                                "picker auth (number/name, back, Esc cancels): ");
                        if (authInput == null || "cancel".equalsIgnoreCase(authInput.trim())) {
                            return;
                        }
                        if ("back".equalsIgnoreCase(authInput.trim())) {
                            backToProvider = true;
                            break;
                        }
                        String authChoice = parsePickerChoice(authInput, authChoices);
                        if (authChoice == null) {
                            tui.updateTemporaryWindow("Provider and model", List.of(
                                    "Invalid authentication method: " + authInput.trim(),
                                    "Choose a numbered method or its exact name."));
                            continue;
                        }
                        selectedAuth = authMethods.get(authChoices.indexOf(authChoice));
                        break;
                    }
                    if (backToProvider) {
                        continue;
                    }
                }
                // Credential/OAuth prompts write through the command-output sink.
                // Give them a short page instead of appending below the provider list.
                tui.updateTemporaryWindow("Provider authentication", List.of(
                        SetupWizard.vendorLabel(selectedVendor) + " · "
                                + SetupWizard.authMethodLabel(selectedAuth)));
                SetupWizard.AuthenticationSelection authentication =
                        "global".equals(chatConfig.getAuthenticationScope())
                                ? SetupWizard.authenticate(reader, selectedVendor, selectedAuth)
                                : SetupWizard.authenticateSession(reader, selectedVendor, selectedAuth);
                if (authentication == null) {
                    tui.updateTemporaryWindow("Provider and model", List.of(
                            "Authentication was not completed for "
                                    + SetupWizard.vendorLabel(selectedVendor) + ".",
                            "Choose another authentication route or provider."));
                    continue;
                }
                selectedProvider = authentication.provider();
                String selectedBaseUrl = null;
                if ("custom".equalsIgnoreCase(selectedProvider)) {
                    selectedBaseUrl = SetupWizard.promptBaseUrl(reader, selectedProvider);
                    if (selectedBaseUrl == null || selectedBaseUrl.isBlank()) {
                        tui.updateTemporaryWindow("Provider and model", List.of(
                                "A custom endpoint URL is required.",
                                "Enter a base URL or choose another provider."));
                        continue;
                    }
                }

                boolean sameProvider = selectedProvider.equalsIgnoreCase(chatConfig.getProvider());
                ChatConfig discoveryConfig = new ChatConfig(
                        selectedProvider,
                        authentication.apiKey(),
                        sameProvider ? chatConfig.getModel() : null,
                        selectedBaseUrl);
                discoveryConfig.setAuthenticationScope(chatConfig.getAuthenticationScope());
                discoveryConfig.setCredentialName(authentication.credentialName());
                discoveryConfig.setAuthenticationMethod(authentication.authMethod().name()
                        .toLowerCase(Locale.ROOT).replace('_', '-'));
                if (sameProvider && (selectedBaseUrl == null || selectedBaseUrl.isBlank())) {
                    discoveryConfig.setBaseUrl(chatConfig.getBaseUrl());
                }
                ModelDiscovery.Result discovery = SetupWizard.modelDiscovery(
                        selectedProvider, authentication.apiKey(), discoveryConfig);
                // A transient failure must not collapse the picker to a manual
                // id prompt. Retry once for transport-grade statuses before
                // considering the last known good catalog.
                if (isTransientDiscoveryFailure(discovery)) {
                    discovery = SetupWizard.refreshModelDiscovery(
                            selectedProvider, authentication.apiKey(), discoveryConfig);
                }
                ModelCatalogSelection.CatalogList catalog =
                        ModelCatalogSelection.listForPicker(discovery, selectedProvider);
                List<String> models = catalog.models();
                String fallbackBanner = catalog.banner();
                boolean usedFallback = catalog.fromFallback();
                while (true) {
                    String defaultModel = models.isEmpty() ? null : models.get(0);
                    List<String> modelPickerLines = pickerLines(
                            "Choose a model for " + providerLabel(selectedVendor),
                            models, defaultModel, selectedVendor, selectedProvider, selectedModel);
                    if (!fallbackBanner.isBlank()) {
                        modelPickerLines.add("");
                        modelPickerLines.add(renderer.yellow("  ⚠ " + fallbackBanner));
                    }
                    if (!discovery.message().isBlank()) {
                        modelPickerLines.add("");
                        modelPickerLines.add("Discovery: "
                                + discovery.status().name().toLowerCase().replace('_', ' '));
                        modelPickerLines.add(discovery.message());
                    }
                    tui.updateTemporaryWindow("Provider and model", modelPickerLines);
                    String modelInput = reader.readLine(
                            "picker model (number/name, refresh, blank uses default, Esc cancels): ");
                    if (modelInput == null || "cancel".equalsIgnoreCase(modelInput.trim())) {
                        return;
                    }
                    if ("back".equalsIgnoreCase(modelInput.trim())) {
                        break;
                    }
                    if ("refresh".equalsIgnoreCase(modelInput.trim())) {
                        discovery = SetupWizard.refreshModelDiscovery(
                                selectedProvider, authentication.apiKey(), discoveryConfig);
                        ModelCatalogSelection.CatalogList refreshed =
                                ModelCatalogSelection.listForPicker(discovery, selectedProvider);
                        models = refreshed.models();
                        fallbackBanner = refreshed.banner();
                        usedFallback = refreshed.fromFallback();
                        continue;
                    }
                    String modelChoice = modelInput.isBlank()
                            ? defaultModel
                            : parsePickerChoice(modelInput, models);
                    if (modelChoice == null && !modelInput.isBlank()) {
                        modelChoice = modelInput.trim();
                    }
                    if (modelChoice == null || modelChoice.isBlank()) {
                        tui.updateTemporaryWindow("Provider and model", List.of(
                                "No model was selected for " + providerLabel(selectedVendor) + ".",
                                "Choose a listed model, enter an id manually, refresh, or go back.",
                                "Current: " + activeModelDisplayName()));
                        continue;
                    }
                    selectedModel = modelChoice;

                    List<SetupWizard.ThinkingOption> thinkingOptions =
                            SetupWizard.thinkingOptions(
                                    selectedProvider, selectedModel, authentication.apiKey(),
                                    discoveryConfig, discovery);
                    String selectedThinking = SetupWizard.compatibleThinking(
                            selectedProvider, selectedModel, chatConfig.getThinking(), discovery);
                    boolean backToModel = false;
                    if (thinkingOptions.size() > 1) {
                        List<String> thinkingChoices = thinkingOptions.stream()
                                .map(SetupWizard.ThinkingOption::label)
                                .toList();
                        String defaultThinkingChoice = thinkingChoices.get(0);
                        for (int i = 0; i < thinkingOptions.size(); i++) {
                            if (Objects.equals(thinkingOptions.get(i).value(), selectedThinking)) {
                                defaultThinkingChoice = thinkingChoices.get(i);
                                break;
                            }
                        }
                        while (true) {
                            tui.updateTemporaryWindow("Provider and model", pickerLines(
                                    "Choose reasoning effort", thinkingChoices, defaultThinkingChoice,
                                    selectedVendor, selectedProvider, selectedModel));
                            String thinkingInput = reader.readLine(
                                    "picker thinking (number/name, blank keeps current, back, Esc cancels): ");
                            if (thinkingInput == null
                                    || "cancel".equalsIgnoreCase(thinkingInput.trim())) {
                                return;
                            }
                            if ("back".equalsIgnoreCase(thinkingInput.trim())) {
                                backToModel = true;
                                break;
                            }
                            String thinkingChoice = thinkingInput.isBlank()
                                    ? defaultThinkingChoice
                                    : parsePickerChoice(thinkingInput, thinkingChoices);
                            if (thinkingChoice == null) {
                                tui.updateTemporaryWindow("Provider and model", List.of(
                                        "Invalid reasoning effort: " + thinkingInput.trim(),
                                        "Choose a listed effort, blank, back, or Esc to cancel."));
                                continue;
                            }
                            selectedThinking = thinkingOptions.get(thinkingChoices.indexOf(thinkingChoice)).value();
                            break;
                        }
                    }
                    if (backToModel) {
                        continue;
                    }

                    ChatConfig candidate = buildModelProviderCandidate(
                            selectedProvider, selectedModel, selectedBaseUrl);
                    candidate.setCredentialName(authentication.credentialName());
                    candidate.setAuthenticationMethod(authentication.authMethod().name()
                            .toLowerCase(Locale.ROOT).replace('_', '-'));
                    candidate.setThinking(selectedThinking);
                    if (candidate.supportsFastMode()) {
                        List<String> fastChoices = SetupWizard.fastModeOptions(selectedProvider, selectedModel);
                        String defaultFastChoice = candidate.isFastMode() ? "on" : "off";
                        while (true) {
                            List<String> lines = pickerLines("Choose fast mode (higher cost)",
                                    fastChoices, defaultFastChoice, selectedVendor, selectedProvider, selectedModel);
                            lines.add(candidate.fastModeCapabilities().notice());
                            tui.updateTemporaryWindow("Provider and model", lines);
                            String input = reader.readLine(
                                    "picker fast (on/off, blank keeps current, back, Esc cancels): ");
                            if (input == null || "cancel".equalsIgnoreCase(input.trim())) return;
                            if ("back".equalsIgnoreCase(input.trim())) {
                                backToModel = true;
                                break;
                            }
                            String choice = input.isBlank() ? defaultFastChoice : parsePickerChoice(input, fastChoices);
                            if (choice == null) continue;
                            candidate.setFastMode("on".equals(choice));
                            break;
                        }
                    }
                    if (backToModel) continue;
                    if (authentication.apiKey() != null && !authentication.apiKey().isBlank()) {
                        // API-key input is transient and write-only; it is never persisted
                        // into chat-config.json.
                        candidate.setApiKey(authentication.apiKey());
                    }
                    if (!canHotSwitchLocalProvider(candidate)) {
                        tui.updateTemporaryWindow("Provider and model", List.of(
                                "That provider owns a separate runtime and cannot be replaced in-place:",
                                "  " + providerLabel(selectedVendor),
                                "Use /setup and restart the session for this provider."));
                        continue;
                    }
                    if (!candidate.isValid()) {
                        tui.updateTemporaryWindow("Provider and model", List.of(
                                "Credentials are not configured for " + providerLabel(selectedVendor) + ".",
                                "Run /setup to configure this provider, then try again.",
                                "The current provider/model is still active."));
                        continue;
                    }
                    if (commitModelProviderSelection(candidate)) {
                        committed = true;
                        if (usedFallback) {
                            ChatCompleter.printAbove(renderer.yellow(
                                    "  Selected from the last known good catalog — the live "
                                            + "list could not be verified. Run /model → refresh later."));
                        }
                    }
                    return;
                }
            }
        } catch (UserInterruptException | EndOfFileException ignored) {
            // Escape/EOF closes only the temporary picker. The active turn's cancel
            // widget has already requested interruption when Escape was pressed.
        } finally {
            modelPickerActive = false;
            ChatCompleter.setTemporaryWindowActive(false);
            tui.closeTemporaryWindow();
            refreshCurrentActivityView(tui, activityPanel);
            if (committed) {
                refreshModelDisplay();
                ChatCompleter.showNotice(renderer.green("  Active model: ")
                        + renderer.cyan(activeModelDisplayName())
                        + renderer.dim(" (applies to the next message)"));
            }
        }
    }

    /** Apply the explicit `/model <name>` form through the same atomic path. */
    void applyModelSelection(String model) {
        if (model == null || model.isBlank() || chatConfig == null) return;
        String selected = model.trim();
        ModelDiscovery.Result discovery = SetupWizard.modelDiscovery(
                chatConfig.getProvider(), null, chatConfig);
        ModelCatalogSelection.SelectionDecision decision =
                ModelCatalogSelection.decisionFor(
                        discovery, chatConfig.getProvider(), selected);
        if (decision == ModelCatalogSelection.SelectionDecision.UNKNOWN) {
            ChatCompleter.printAbove(renderer.yellow(
                    "  Model is not in the provider's live model list or the last known good catalog: ")
                    + renderer.cyan(selected)
                    + renderer.dim(" Use /model to browse, or retry when the provider is reachable."));
            return;
        }
        ChatConfig candidate = buildModelProviderCandidate(chatConfig.getProvider(), selected);
        candidate.setThinking(SetupWizard.compatibleThinking(
                chatConfig.getProvider(), selected, chatConfig.getThinking(), discovery));
        if (!candidate.isValid()) {
            ChatCompleter.printAbove(renderer.yellow("  Cannot use model ") + renderer.cyan(model.trim())
                    + renderer.dim(" because the current provider is not configured."));
            return;
        }
        commitModelProviderSelection(candidate);
        String note = ModelCatalogSelection.noteFor(decision, selected, chatConfig.getProvider());
        if (!note.isBlank()) {
            ChatCompleter.printAbove(renderer.yellow("  " + note));
        }
    }

    /** Transport-grade discovery statuses worth one automatic retry. */
    private static boolean isTransientDiscoveryFailure(ModelDiscovery.Result discovery) {
        return discovery != null && (
                discovery.status() == ModelDiscovery.Status.TIMEOUT
                        || discovery.status() == ModelDiscovery.Status.UNAVAILABLE
                        || discovery.status() == ModelDiscovery.Status.RATE_LIMITED);
    }

    void handleAuthenticationCommand(String arguments) {
        if (!localMode || chatConfig == null) {
            ChatCompleter.printAbove("Session authentication requires standard direct chat.");
            return;
        }
        String[] args = arguments == null || arguments.isBlank() ? new String[0] : arguments.trim().split("\\s+");
        try {
            var store = ai.kompile.cli.main.auth.CredentialStore.create();
            if (args.length == 0 || "list".equals(args[0])) {
                ChatCompleter.printAbove("Authentication: " + chatConfig.getAuthenticationScope()
                        + " / " + chatConfig.getProvider() + " / "
                        + (chatConfig.getCredentialName() == null ? "vendor global default" : chatConfig.getCredentialName()));
                for (var info : store.list(chatConfig.getProvider()))
                    ChatCompleter.printAbove("  " + info.credentialName() + " (" + info.type() + ")"
                            + (info.active() ? " [global default]" : ""));
                ChatCompleter.printAbove("/auth session [name] — pin this session; /auth global — follow vendor default\n"
                        + "/auth global <provider> <name> — select credential for all global-mode sessions of that provider\n"
                        + "/auth default session|global — default mode for new sessions; /provider — choose vendor/account/model");
                return;
            }
            if (args.length == 2 && "default".equals(args[0])) {
                ChatConfig defaults = ChatConfig.loadGlobal();
                if (defaults == null) defaults = new ChatConfig();
                defaults.setAuthenticationScope(args[1]);
                defaults.saveGlobal();
                ChatCompleter.printAbove("New-session global authentication default: " + args[1]
                        + " (project settings can override it).");
                return;
            }
            if (args.length == 3 && "global".equals(args[0])) {
                if (!store.switchCredential(args[1], args[2])) throw new IllegalArgumentException("Unknown credential");
                ChatCompleter.printAbove("Global credential selected for " + args[1]
                        + "; global-mode sessions use it on their next request. Session pins are unchanged.");
                return;
            }
            ChatConfig candidate = chatConfig.copy();
            if ("session".equals(args[0]) && args.length <= 2) {
                candidate.setAuthenticationScope("session");
                if (args.length == 2) candidate.setCredentialName(args[1]);
                else candidate.pinActiveCredential();
            } else if ("global".equals(args[0]) && args.length == 1) {
                candidate.setAuthenticationScope("global");
                candidate.setApiKey(null);
            } else throw new IllegalArgumentException("Use /auth for authentication commands");
            candidate.setAuthenticationMethod(null);
            candidate.resolveRequestAuth(); // Fail closed before changing the active session.
            if (updateChatConfig(candidate)) {
                chatConfig.saveLoadedOrGlobal();
                ChatCompleter.printAbove("Authentication scope: " + chatConfig.getAuthenticationScope());
            }
        } catch (IOException | RuntimeException e) {
            ChatCompleter.printAbove("Authentication selection failed: " + e.getMessage());
        }
    }

    private boolean commitModelProviderSelection(ChatConfig candidate) {
        String previous = activeModelDisplayName();
        if (!updateChatConfig(candidate)) {
            ChatCompleter.printAbove(renderer.yellow("  Provider/model switch was not applied.")
                    + renderer.dim(" Use /setup for a runtime-owned provider."));
            return false;
        }
        try {
            chatConfig.saveLoadedOrGlobal();
        } catch (Exception e) {
            ChatCompleter.printAbove("Provider/model changed in memory, but session settings could not be saved: " + e.getMessage());
        }
        refreshModelDisplay();
        chatHistory.logSystem("Selected provider/model: " + activeModelDisplayName());
        if (!previous.equals(activeModelDisplayName())) {
            ChatCompleter.showNotice(renderer.green("  Selected provider/model: ")
                    + renderer.cyan(activeModelDisplayName())
                    + renderer.dim(" — current response continues; next message uses it."));
        }
        return true;
    }

    private ChatConfig buildModelProviderCandidate(String provider, String model) {
        return buildModelProviderCandidate(provider, model, null);
    }

    private ChatConfig buildModelProviderCandidate(
            String provider, String model, String baseUrlOverride) {
        return buildModelProviderCandidateFrom(
                chatConfig, provider, model, baseUrlOverride);
    }

    static ChatConfig buildModelProviderCandidateFrom(
            ChatConfig activeConfig, String provider, String model,
            String baseUrlOverride) {
        ChatConfig candidate = new ChatConfig();
        candidate.applyLlmSettingsFrom(activeConfig);
        // Authentication remains a managed provider capability. Flattening an
        // OAuth/subscription credential into a transient API key loses its
        // provider-owned headers and base URL (notably OpenAI Codex/Anthropic).
        candidate.setApiKey(null);
        candidate.setProvider(provider);
        candidate.setModel(model);
        candidate.setFastMode(provider != null && provider.equalsIgnoreCase(activeConfig.getProvider())
                && activeConfig.isFastMode() && candidate.supportsFastMode());
        // Thinking is resolved for the selected model below; never carry an
        // incompatible value through the candidate-building step.
        candidate.setThinking(null);
        // Never carry a credential across providers. Same-provider candidates
        // resolve the active managed credential lazily after model selection.
        if (provider != null && provider.equalsIgnoreCase(activeConfig.getProvider())) {
            candidate.setBaseUrl(baseUrlOverride == null
                    ? activeConfig.getBaseUrl() : baseUrlOverride);
        } else {
            candidate.setApiKey(null);
            candidate.setBaseUrl(baseUrlOverride);
            candidate.setAuthenticationMethod(null);
        }
        return candidate;
    }

    private List<String> switchableProviders() {
        LinkedHashSet<String> providers = new LinkedHashSet<>(SetupWizard.providerPickerOrder());
        String currentVendor = SetupWizard.vendorForProvider(chatConfig.getProvider());
        if (currentVendor != null && !currentVendor.isBlank()
                && !"kompile".equalsIgnoreCase(currentVendor)
                && !"kompile-local".equalsIgnoreCase(currentVendor)) {
            providers.add(currentVendor);
        }
        return List.copyOf(providers);
    }

    private static String parsePickerChoice(String input, List<String> choices) {
        String value = input == null ? "" : input.trim();
        if (value.isEmpty()) return null;
        try {
            int index = Integer.parseInt(value);
            if (index >= 1 && index <= choices.size()) return choices.get(index - 1);
        } catch (NumberFormatException ignored) {
            // Match names case-insensitively below.
        }
        for (String choice : choices) {
            if (choice.equalsIgnoreCase(value)) return choice;
        }
        return null;
    }

    private List<String> pickerLines(String heading, List<String> choices,
                                     String defaultChoice, String vendor,
                                     String provider, String model) {
        List<String> lines = new ArrayList<>();
        lines.add("Active: " + activeModelDisplayName());
        lines.add(heading + ":");
        for (int i = 0; i < choices.size(); i++) {
            String display = "Choose a provider".equals(heading)
                    ? SetupWizard.vendorLabel(choices.get(i)) : choices.get(i);
            String marker = choices.get(i).equalsIgnoreCase(defaultChoice) ? " *" : "  ";
            lines.add(String.format("%2d%s %s", i + 1, marker, display));
        }
        if (choices.isEmpty()) lines.add("  (type a value at the prompt)");
        lines.add("");
        String auth = SetupWizard.authMethodLabel(SetupWizard.authMethodForProvider(provider));
        lines.add("Provider: " + SetupWizard.vendorLabel(vendor)
                + " (" + provider + ")   Auth: " + auth + "   Model: " + model);
        String commands = heading.startsWith("Choose reasoning effort")
                ? "number/name, blank keeps current, back, or Esc to cancel"
                : "number/name, model id, back, or Esc to cancel";
        lines.add("Commands: " + commands);
        return lines;
    }

    private String providerLabel(String provider) {
        return SetupWizard.vendorLabel(SetupWizard.vendorForProvider(provider));
    }

    private String activeModelDisplayName() {
        if (chatConfig == null) return "local";
        String provider = localProviderDisplayName();
        String model = chatConfig.getModel();
        return model == null || model.isBlank() ? provider : provider + " / " + model;
    }

    private String activeModelTopPaneLabel() {
        return modelTopPaneLabel(activeModelDisplayName(), chatConfig == null ? null : chatConfig.getThinking(),
                chatConfig != null && chatConfig.supportsFastMode(), chatConfig != null && chatConfig.isFastMode());
    }

    static String modelTopPaneLabel(String modelDisplayName, String thinking,
                                   boolean supportsFastMode, boolean fastMode) {
        return modelTopPaneLabel(modelDisplayName, thinking)
                + (supportsFastMode ? " / fast: " + (fastMode ? "on (requested)" : "off") : "");
    }

    static String modelTopPaneLabel(String modelDisplayName, String thinking) {
        String effort = thinking == null || thinking.isBlank() ? "default" : thinking.trim();
        return modelDisplayName + " / effort: " + effort;
    }

    void refreshModelDisplay() {
        tui.setAgentName(activeModelTopPaneLabel());
        renderer.setReadyTerminalTitle(readyTerminalTitle(
                "kompile chat (local) — " + activeModelDisplayName()));
        statusBar.requestRedraw();
    }

    boolean isAutoDequeueEnabled() {
        return queueManager == null ? autoDequeueEnabled : queueManager.isAutoDequeueEnabled();
    }

    /** Active terminal stream used by commands that emit OSC clipboard sequences. */
    Terminal getActiveTerminal() {
        return activeTerminal;
    }

    /** Called by SessionLifecycleManager's showMainMenu() to run the setup wizard. */
    void runSetupFromMenu() {
        ChatConfig newConfig = SetupWizard.run(ChatConfig.Scope.PROJECT, workingDirectory);
        if (newConfig == null) {
            System.out.println("Setup cancelled.");
            return;
        }
        if (updateChatConfig(newConfig)) {
            System.out.println(renderer.green(
                    "Provider updated in this session. Existing transcript and conversation context were retained."));
        } else {
            System.out.println(renderer.dim(
                    "Configuration saved for the next session; this runtime change cannot be applied in-place."));
        }
    }

}
