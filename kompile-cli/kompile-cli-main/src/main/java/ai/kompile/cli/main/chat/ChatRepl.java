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
import ai.kompile.cli.main.chat.agent.*;
import ai.kompile.cli.main.chat.crawl.CrawlRunStore;
import ai.kompile.utils.StringUtils;
import ai.kompile.project.KompileProjectChatSession;
import ai.kompile.project.KompileProjectStore;
import ai.kompile.cli.main.chat.config.ChatConfig;
import ai.kompile.cli.main.chat.config.DirectLlmClient;
import ai.kompile.cli.main.chat.config.SetupWizard;
import ai.kompile.cli.main.chat.enforcer.EnforcerActivationPrompt;
import ai.kompile.cli.main.chat.enforcer.EnforcerConfig;
import ai.kompile.cli.main.chat.enforcer.EnforcerPolicy;
import ai.kompile.cli.main.chat.enforcer.KeywordEnforcerEvaluator;
import ai.kompile.cli.main.chat.harness.PerformanceHarness;
import ai.kompile.cli.main.chat.permission.PermissionService;
import ai.kompile.cli.main.chat.render.AsciiRenderer;
import ai.kompile.cli.main.chat.render.TerminalRenderer;
import ai.kompile.cli.main.chat.roles.RoleConfig;
import ai.kompile.cli.main.chat.roles.RoleManager;
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
import org.jline.terminal.Terminal;
import org.jline.keymap.KeyMap;
import org.jline.reader.impl.LineReaderImpl;

import java.io.File;
import java.io.IOError;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
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
public class ChatRepl {

    // ── Core state ────────────────────────────────────────────────────────────

    private final McpSseClient mcpClient; // null in local mode
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final String baseUrl; // null in local mode
    private final String sessionId;
    private final ChatHistory chatHistory;
    private final ChatMemory chatMemory;
    private boolean ragEnabled;
    private String agentName;
    private String localAgentName;
    private List<McpSseClient.ToolInfo> cachedTools;
    private boolean forceAgentic;
    private AgentRunController runController;
    private CrawlRunStore crawlRunStore;

    // Tool & agent system
    private final ToolRegistry toolRegistry;
    private final AgentRegistry agentRegistry;
    private final SkillRegistry skillRegistry;
    private final RoleManager roleManager;
    private final PermissionService permissionService;
    private final AgenticChatLoop agenticLoop;
    private final BackgroundProcessManager processManager;
    private final TerminalRenderer renderer;
    private AsciiRenderer ascii;

    // Mode
    private final boolean localMode;
    private ChatConfig chatConfig; // non-null in local mode

    // Message queue for queued chats
    private final MessageQueue messageQueue;

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

    // Persistent below-bar status line showing processes, subagents, queue
    private final StatusBar statusBar;

    // Interactive process/subagent rows reserved directly below the input area.
    private final StandardChatActivityPanel activityPanel;

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
        this.mcpClient = mcpClient;
        this.localMode = (mcpClient == null);
        this.chatConfig = chatConfig;
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

        // ChatMemory works in both modes: persistent memory + transcripts always,
        // RAG search only when server is connected
        this.chatMemory = new ChatMemory(mcpClient, sessionId, memoryEnabled);

        // Initialize tool & agent system
        this.permissionService = new PermissionService();
        this.agentRegistry = new AgentRegistry();
        this.renderer = new TerminalRenderer();
        this.ascii = new AsciiRenderer(renderer);
        this.permissionService.setPromptListener(prompt -> {
            ChatCompleter.printAbove("");
            ChatCompleter.printAbove(renderer.yellow("Permission required: ")
                    + renderer.bold(prompt.permissionKey()));
            if (prompt.description() != null && !prompt.description().isBlank()) {
                ChatCompleter.printAbove("  " + prompt.description());
            }
            ChatCompleter.printAbove(renderer.dim(
                    "  Enter y=yes, n=no, a=allow for session, v=deny for session"));
        });

        Path workDir = Paths.get(System.getProperty("user.dir"));

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

        this.toolRegistry = ToolRegistryFactory.create(
                objectMapper, baseUrl != null ? baseUrl : "", agentRegistry,
                permissionService, renderer, processManager,
                localMode ? chatConfig : null, roleManager);

        // Create DirectLlmClient for local mode
        DirectLlmClient directClient = null;
        if (localMode && chatConfig != null) {
            directClient = new DirectLlmClient(chatConfig, objectMapper);
        }

        this.agenticLoop = new AgenticChatLoop(
                baseUrl, objectMapper, toolRegistry, permissionService,
                agentRegistry, workDir, directClient, processManager);

        // Initialize message queue for queued chats
        this.messageQueue = new MessageQueue(sessionId);

        // Initialize background task manager
        this.backgroundTaskManager = new BackgroundTaskManager();

        // Initialize unified TUI (TopBar + scroll region + StatusBar)
        this.tui = new KompileTui(backgroundTaskManager, processManager, messageQueue, renderer);
        this.statusBar = tui.getStatusBar();
        this.activityPanel = new StandardChatActivityPanel(
                backgroundTaskManager, processManager, statusBar, tui::getReservedMiddleRows);
        this.agenticLoop.setToolActivityListener(new AgenticChatLoop.ToolActivityListener() {
            @Override
            public void onToolStart(String callId, String toolName, String rawInput) {
                activityPanel.recordToolStart(callId, toolName, rawInput);
            }

            @Override
            public void onToolComplete(String callId, String toolName,
                                       String rawInput, ToolResult result) {
                activityPanel.recordToolComplete(callId, toolName, rawInput, result);
            }

            @Override
            public void onToolDenied(String callId, String toolName,
                                     String rawInput, String reason) {
                activityPanel.recordToolDenied(callId, toolName, rawInput, reason);
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

        // Wire performance harness for multi-signal agent evaluation (local mode only)
        if (localMode && directClient != null) {
            PerformanceHarness harness = new PerformanceHarness(
                    directClient, chatConfig, objectMapper, renderer, sessionMetrics, processManager);
            this.agenticLoop.setPerformanceHarness(harness);
        }

        // Wire cancel signal into agentic loop
        this.agenticLoop.setCancelSignal(cancelSignal);

        // Load inline enforcer rules from project config if present. Never auto-enables:
        // the user is prompted (interactive) or it stays off (/enforcer on to enable).
        loadInlineEnforcerWithPrompt(workDir);

        // Wire up extracted collaborators
        initCollaborators();
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

    /** Initialise the four extracted collaborator classes after construction. */
    private void initCollaborators() {
        this.messageHandler = new ChatMessageHandler(
                this, mcpClient, httpClient, objectMapper, sessionId, localMode,
                chatHistory, chatMemory, sessionMetrics, renderer, ascii, agenticLoop,
                backgroundTaskManager, messageQueue, cancelSignal, pendingAttachments);

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
                pendingAttachments);
    }

    // ── Inline enforcer loading (called from router on /enforcer on|reload) ──

    /**
     * Explicit load + enable. Used by {@code /enforcer on|reload} where the slash command
     * itself is the user's opt-in — no extra prompt.
     */
    public void loadInlineEnforcer(Path workDir) {
        loadInlineEnforcer(workDir, true);
    }

    /**
     * Startup variant: enforcement is per-session opt-in, so a project config found on
     * disk prompts the user before enabling. Declined (or non-interactive) sessions keep
     * the rules loaded but DISABLED so {@code /enforcer on} can enable them instantly.
     */
    private void loadInlineEnforcerWithPrompt(Path workDir) {
        EnforcerConfig enforcerConfig = EnforcerConfig.load(workDir);
        if (enforcerConfig == null || !enforcerConfig.isKeywordMode()
                || !enforcerConfig.isEnforcementEnabled()) {
            return;
        }
        Boolean choice = EnforcerActivationPrompt
                .confirmViaConsole(enforcerConfig);
        loadInlineEnforcer(workDir, Boolean.TRUE.equals(choice));
    }

    private void loadInlineEnforcer(Path workDir, boolean enable) {
        EnforcerConfig enforcerConfig = EnforcerConfig.load(workDir);
        if (enforcerConfig == null || !enforcerConfig.isKeywordMode()) {
            return;
        }
        try {
            String rulesText = enforcerConfig.buildRulesText(workDir);
            if (rulesText == null || rulesText.isBlank()) return;

            EnforcerPolicy policy =
                    new EnforcerPolicy(rulesText, enforcerConfig.getMaxCorrections(), false);
            KeywordEnforcerEvaluator evaluator =
                    KeywordEnforcerEvaluator.fromPolicy(policy, objectMapper, enforcerConfig);

            if (evaluator.isAvailable()) {
                agenticLoop.setInlineEnforcer(evaluator, policy, enforcerConfig.getMaxCorrections());
                agenticLoop.setInlineEnforcerEnabled(enable);
            }
        } catch (Exception e) {
            // Silently skip — don't break chat startup
        }
    }

    // ── Main REPL loop ────────────────────────────────────────────────────────

    /**
     * Execute exactly one crawl instruction without constructing JLine or the TUI.
     * The same agentic loop, tool registry, transcript, checkpoint store and
     * lifecycle cleanup used by the interactive command are retained for CI and
     * the FP&A production harness.
     */
    public void runHeadless(String message) throws Exception {
        if (message == null || message.isBlank()) {
            throw new IllegalArgumentException("Headless crawl message must not be blank");
        }
        lifecycleManager.restoreSession();
        chatHistory.open(baseUrl != null ? baseUrl : "(local)", agentName, ragEnabled);
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
            messageHandler.handleChatMessage(message);
            if (crawlRunStore != null && runController != null) {
                crawlRunStore.event("headless_completed", runController.state().name());
            }
        } finally {
            stopGeneratingSpinner();
            if (crawlRunStore != null && runController != null) {
                crawlRunStore.checkpoint(runController, "headless_closed");
                crawlRunStore.event("session_closed", runController.state().name());
            }
            lifecycleManager.printSessionSummary();
            chatHistory.close();
            Path metricsFile = chatHistory.getTranscriptFile().resolveSibling(sessionId + ".metrics.json");
            sessionMetrics.saveToFile(metricsFile, objectMapper);
            exportTranscriptToProject();
            processManager.close();
        }
    }

    public void run() throws Exception {
        // Set initial terminal title
        renderer.setTerminalTitle("kompile chat" + (localMode ? " (local)" : " — " + agentName));

        // Open transcript file for writing
        chatHistory.open(baseUrl != null ? baseUrl : "(local)", agentName, ragEnabled);

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

        Terminal terminal = ChatCompleter.buildSystemTerminal();

        // Re-create AsciiRenderer with actual terminal width now that the terminal is available
        int termW = terminal.getWidth();
        if (termW > 0) {
            this.ascii = new AsciiRenderer(renderer, termW);
            // Re-init collaborators so they reference the new ascii instance
            initCollaborators();
        }

        Path historyFile = new File(KompileHome.homeDirectory(), "chat_input_history").toPath();

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

        reader.getHistory().load();

        // Auto-trigger slash command completion as the user types
        ChatCompleter.enableAutoTrigger(reader);

        // Standard chat owns the activity rows below the input. Down enters them,
        // Up navigates back toward the prompt, Enter inspects, and Del kills an
        // owned process. Normal typing/history remain the fallback.
        bindStandardChatActivityKeys(
                (LineReaderImpl) reader, messageQueue, activityPanel, tui);

        // Bind Ctrl+B to background current task
        ((LineReaderImpl) reader).getKeyMaps().get(LineReader.EMACS).bind(
            new Reference("background-task"),
            KeyMap.ctrl('B')
        );

        ((LineReaderImpl) reader).getWidgets().put("background-task", new Widget() {
            @Override
            public boolean apply() {
                if (llmBusy && backgroundTaskManager.getCurrentTask() != null) {
                    backgroundTaskManager.requestBackground();
                    sessionMetrics.recordTaskBackgrounded();
                    BackgroundTaskManager.BackgroundTask task = backgroundTaskManager.getCurrentTask();
                    int queueSize = messageQueue.size();
                    System.out.println();
                    System.out.println(renderer.yellow("  ◐ Task backgrounded") + renderer.dim(" [" + (task != null ? task.getId() : "?") + "]"));
                    if (queueSize > 0) {
                        System.out.println(renderer.dim("    " + queueSize + " queued message(s) will auto-send when complete"));
                    } else {
                        System.out.println(renderer.dim("    Response will complete in background"));
                    }
                    System.out.println(renderer.dim("    Use /jobs to check status"));
                    System.out.println();
                    System.out.flush();
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
                boolean cancelled = false;
                if (messageHandler != null) {
                    // The handler owns the active-turn thread; do not gate this on
                    // llmBusy because tools/subagents may still be running while
                    // the visible model state is between phases.
                    cancelled = messageHandler.requestCancel();
                } else if (llmBusy) {
                    cancelSignal.set(true);
                    cancelled = true;
                }
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
                List<TodoWriteTool.TodoItem> todos = TodoWriteTool.getTodos(sessionId);
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
            AgentsMdLoader loader = new AgentsMdLoader(Paths.get(System.getProperty("user.dir")));
            List<Path> files = loader.listFiles();
            System.out.println(renderer.dim("  Loaded AGENTS.md from: " +
                    files.stream().map(p -> p.getParent().toString()).collect(Collectors.joining(", "))));
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

        // Show enforcer status (enabled via the activation prompt, or loaded-but-off)
        if (agenticLoop.isInlineEnforcerEnabled()) {
            System.out.println(renderer.green("  Enforcer: " + agenticLoop.describeInlineEnforcer()
                    + " — /enforcer off to disable"));
        } else if (agenticLoop.describeInlineEnforcer() != null) {
            System.out.println(renderer.dim("  Enforcer: " + agenticLoop.describeInlineEnforcer()
                    + " — loaded but OFF, /enforcer on to enable"));
        }
        System.out.println();

        // Start the unified TUI: TopBar + scroll region + StatusBar
        tui.setAgentName(localMode ? localProviderDisplayName() : agentName);
        tui.setSessionId(sessionId);
        tui.setMode(localMode ? "local" : "server");
        tui.setPlanningMode(agenticLoop.isPlanningMode());
        tui.setReservedRowsCalculator(StandardChatActivityPanel::reservedRowsForTerminal);
        tui.start(terminal);
        activityPanel.refresh();

        // KompileTui.start() clears the terminal while establishing its bars and
        // scroll region. Restore only after that clear, and route each line through
        // the TUI so the prior transcript remains visible above the live prompt.
        lifecycleManager.restoreSession(tui::printInScrollRegion);

        // Wire activity changes into both the compact status line and the
        // interactive process/subagent rows.
        Runnable activityRedraw = () -> {
            activityPanel.refresh();
            refreshCurrentActivityView(tui, activityPanel);
        };
        backgroundTaskManager.addChangeListener(activityRedraw);
        processManager.addChangeListener(activityRedraw);
        tui.addResizeListener(activityRedraw);

        // Wire subagent lifecycle tracking into the status bar
        SubagentRunner runner = toolRegistry.getSubagentRunner();
        if (runner != null) {
            runner.setLifecycleListener(new SubagentRunner.LifecycleListener() {
                @Override
                public void onSubagentStart(String id, String type, String description) {
                    statusBar.registerSubagent(id, type, description);
                    activityPanel.refresh();
                    refreshCurrentActivityView(tui, activityPanel);
                }
                @Override
                public void onSubagentStatus(String id, String status) {
                    statusBar.updateSubagentStatus(id, status);
                    activityPanel.refresh();
                    refreshCurrentActivityView(tui, activityPanel);
                }
                @Override
                public void onSubagentActivity(String id, String summary, String detail) {
                    statusBar.appendSubagentActivity(id, summary, detail);
                    activityPanel.refresh();
                    refreshCurrentActivityView(tui, activityPanel);
                }
                @Override
                public void onSubagentOutput(String id, String chunk) {
                    statusBar.appendSubagentOutput(id, chunk);
                    refreshCurrentActivityView(tui, activityPanel);
                }
                @Override
                public void onSubagentEnd(String id) {
                    statusBar.unregisterSubagent(id);
                    activityPanel.refresh();
                    refreshCurrentActivityView(tui, activityPanel);
                }
            });
        }

        // Pass terminal ref to ChatCompleter for bottom border rendering.
        // Streamed lines are recorded in the TUI, then emitted through JLine's
        // thread-safe printAbove path so background output cannot corrupt typing.
        ChatCompleter.setTerminalRef(reader, terminal);
        // REDISPLAY is invoked synchronously by the input thread for completion
        // changes; repaint the authoritative transcript before it restores input.
        ChatCompleter.setContentRedraw(tui::redrawContentView);
        ChatCompleter.setContentOutput(tui::recordInScrollRegion);

        try {
            while (true) {
                String line;
                try {
                    int termWidth = terminal.getWidth() > 0 ? terminal.getWidth() : 80;
                    tui.reestablishScrollRegion();
                    ChatCompleter.schedulePostRestore();
                    String prompt = buildPrompt(termWidth);
                    line = reader.readLine(prompt);
                } catch (UserInterruptException e) {
                    // Ctrl-C at the prompt is an explicit request to leave the
                    // standard chat session. JLine has already cleared the
                    // current input buffer, so exit through normal cleanup.
                    break;
                } catch (EndOfFileException e) {
                    break;
                } catch (IOError e) {
                    // JLine wraps stty/terminal errors in IOError during
                    // shutdown or when the terminal is interrupted. Exit
                    // cleanly instead of crashing.
                    break;
                }

                // A background tool may be waiting for a permission decision. JLine
                // owns terminal input, so route this line to that request before treating
                // it as a slash command or queued chat message.
                if (permissionService.submitPromptResponse(line)) {
                    continue;
                }

                if (line == null || line.isBlank()) {
                    continue;
                }

                String trimmed = line.trim();

                String viewedSubagentId = activityPanel.viewedSubagentId();
                if (!trimmed.startsWith("/") && !viewedSubagentId.isBlank()) {
                    if (runner != null && runner.sendMessage(viewedSubagentId, trimmed)) {
                        refreshCurrentActivityView(tui, activityPanel);
                    } else {
                        statusBar.appendSubagentActivity(
                                viewedSubagentId,
                                "follow-up unavailable",
                                "\n  Follow-up was not sent: this subagent session is no longer interactive.");
                        refreshCurrentActivityView(tui, activityPanel);
                    }
                    continue;
                }

                // Any accepted parent-chat input (including bracketed paste, which
                // bypasses SELF_INSERT) returns from a process/tool transcript first.
                if (!activityPanel.isViewingMain() && !trimmed.startsWith("/")) {
                    activityPanel.returnToMain();
                    tui.showMainView();
                }

                if (trimmed.startsWith("/")) {
                    if (!commandRouter.handleSlashCommand(trimmed)) {
                        break;
                    }
                } else {
                    // JLine already painted this row; retain it so switching to a
                    // process transcript and back reconstructs the full parent view.
                    tui.rememberMainTranscriptLine("kompile> " + trimmed);
                    messageHandler.handleChatMessage(trimmed);
                }
            }
        } finally {
            permissionService.cancelPendingPrompts();
            reader.getHistory().save();

            if (crawlRunStore != null && runController != null) {
                crawlRunStore.checkpoint(runController, "session_closed");
                crawlRunStore.event("session_closed", runController.state().name());
            }

            // Log session summary to transcript and save metrics
            lifecycleManager.printSessionSummary();
            chatHistory.close();

            // Save metrics JSON alongside transcript
            Path metricsFile = chatHistory.getTranscriptFile().resolveSibling(sessionId + ".metrics.json");
            sessionMetrics.saveToFile(metricsFile, objectMapper);

            // Export the full transcript into the current project's versioned
            // data/chats/ surface so the conversation is locally versioned, not
            // left only in the global ~/.kompile/conversations store.
            exportTranscriptToProject();

            // Stop the unified TUI (resets scroll regions, stops refresh threads)
            tui.stop();
            ChatCompleter.clearTerminalRef(reader);
            ChatCompleter.setQueueSupplier(null);

            // Clean up background process manager to prevent shutdown hook leak
            processManager.close();

            // Properly clean up JLine terminal state.
            // Catch Exception AND IOError — JLine wraps stty failures in
            // IOError (extends Error) when the thread is interrupted during
            // shutdown, especially in GraalVM native images.
            try {
                terminal.writer().print("\033[2J");
                terminal.writer().flush();
                terminal.close();
            } catch (Exception | IOError e) {
                // Ignore cleanup errors - terminal may already be in bad state
            }
        }
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
            Optional<Path> projectRoot = store.findProjectRoot(Paths.get("").toAbsolutePath());
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
            sessions.removeIf(s -> sessionId.equals(s.getSessionId()));
            sessions.add(KompileProjectChatSession.builder()
                    .sessionId(sessionId)
                    .title(deriveTranscriptTitle(transcript))
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
        try {
            for (String line : Files.readAllLines(transcript)) {
                if (line.startsWith("> ")) {
                    String title = line.substring(2).strip();
                    if (!title.isEmpty()) {
                        return title.length() > 80 ? title.substring(0, 77) + "..." : title;
                    }
                }
            }
        } catch (Exception ignored) {
            // fall through to default
        }
        return "Session " + sessionId;
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
        // Check for completed backgrounded task notifications
        List<BackgroundTaskManager.BackgroundTask> notifications = backgroundTaskManager.drainNotifications();
        if (!notifications.isEmpty()) {
            for (BackgroundTaskManager.BackgroundTask task : notifications) {
                System.out.println();
                System.out.println(renderer.green("  ✓ Backgrounded task completed") + renderer.dim(" [" + task.getId() + "] " + task.getElapsedTime()));
                String desc = task.getDescription();
                if (desc.length() > 60) desc = desc.substring(0, 57) + "...";
                System.out.println(renderer.dim("    " + desc));
                if (task.getOutput() != null && !task.getOutput().isEmpty()) {
                    String preview = task.getOutput().replaceAll("\\s+", " ").trim();
                    if (preview.length() > 70) preview = preview.substring(0, 67) + "...";
                    System.out.println(renderer.dim("    → " + preview));
                }
            }
            System.out.println();
        }

        StringBuilder prompt = new StringBuilder();
        prompt.append("kompile");
        String viewedSubagent = activityPanel.viewedSubagentId();
        if (!viewedSubagent.isBlank()) {
            prompt.append("[").append(viewedSubagent).append("]");
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
        if (ChatCompleter.hasLineReader()) {
            // JLine owns the editable row. Activity belongs in the fixed status
            // bar; a carriage-return spinner corrupts the prompt and can scroll.
            ChatCompleter.setActivity("Thinking");
            renderer.setTerminalTitle("⏳ Kompiling..." + (chainInfo.isEmpty() ? "" : " " + AnsiConstants.stripAnsi(chainInfo)));
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
        if (ChatCompleter.hasLineReader()) {
            renderer.setTerminalTitle("kompile chat" + (localMode ? " (local)" : " — " + agentName));
            statusBar.requestRedraw();
        }
    }

    // ── Auto-dequeue / task completion ───────────────────────────────────────

    /**
     * Completes the current task and auto-dequeues next message if available.
     * Tracks queue chain progress for "Processing 2/5" indicators.
     */
    public void completeTaskWithAutoDequeue() {
        backgroundTaskManager.completeCurrentTask();
        llmBusy = false;
        renderer.setTerminalTitle("kompile chat" + (localMode ? " (local)" : " — " + agentName));

        // Continue either the normal auto-dequeue policy or an explicit
        // /queue-send-all chain. Both consult the queue manager's single state.
        if ((isAutoDequeueEnabled() || backgroundTaskManager.isInQueueChain())
                && !messageQueue.isEmpty()) {
            MessageQueue.QueuedMessage nextMsg = messageQueue.peek();
            if (nextMsg != null) {
                // Start chain tracking if not already in a chain
                if (!backgroundTaskManager.isInQueueChain()) {
                    backgroundTaskManager.startQueueChain(messageQueue.size());
                }
                backgroundTaskManager.advanceQueueChain();

                int current = backgroundTaskManager.getQueueChainCurrent();
                int total = backgroundTaskManager.getQueueChainTotal();
                int remaining = messageQueue.size() - 1;

                ChatCompleter.printAbove("");
                ChatCompleter.printAbove(renderer.green("  ✓ Complete ")
                        + renderer.dim("→ sending next (" + current + "/" + total + ")"));
                ChatCompleter.printAbove(renderer.dim("     → ")
                        + StringUtils.truncate(nextMsg.getContent(), 60));
                if (remaining > 0) {
                    ChatCompleter.printAbove(renderer.dim("     (" + remaining + " more queued)"));
                }
                ChatCompleter.printAbove("");
                messageQueue.dequeue();
                statusBar.requestRedraw();
                sessionMetrics.recordMessageAutoDequeued();
                // Small delay for clean transition
                try {
                    Thread.sleep(300);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                messageHandler.handleChatMessage(nextMsg.getContent());
                return;
            }
        }

        // End queue chain if we're done
        if (backgroundTaskManager.isInQueueChain()) {
            int total = backgroundTaskManager.getQueueChainTotal();
            backgroundTaskManager.endQueueChain();
            ChatCompleter.printAbove("");
            ChatCompleter.printAbove(renderer.green("  ✓ All " + total + " queued messages processed"));
            ChatCompleter.printAbove("");
            statusBar.requestRedraw();
        }
    }

    /** Finish an interrupted turn while leaving queued messages for explicit user control. */
    public void completeTaskWithoutAutoDequeue() {
        backgroundTaskManager.completeCurrentTask();
        llmBusy = false;
        renderer.setTerminalTitle("kompile chat" + (localMode ? " (local)" : " — " + agentName));
        statusBar.requestRedraw();
    }

    // ── Key binding helper ────────────────────────────────────────────────────

    static final String STANDARD_CHAT_UP_WIDGET = "standard-chat-contextual-up";
    static final String STANDARD_CHAT_DOWN_WIDGET = "standard-chat-activity-down";
    static final String STANDARD_CHAT_PARENT_WIDGET = "standard-chat-activity-parent";
    static final String STANDARD_CHAT_PAGE_UP_WIDGET = "standard-chat-transcript-page-up";
    static final String STANDARD_CHAT_PAGE_DOWN_WIDGET = "standard-chat-transcript-page-down";

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
        bindStandardChatUpArrow(reader, queue, null, null);
    }

    private static void bindStandardChatUpArrow(
            LineReaderImpl reader, MessageQueue queue,
            StandardChatActivityPanel activityPanel, KompileTui tui) {
        reader.getWidgets().put(STANDARD_CHAT_UP_WIDGET, () -> {
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
                    MessageQueue.QueuedMessage latest = queued.get(queued.size() - 1);
                    if (queue.remove(latest.getId())) {
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
        bindStandardChatUpArrow(reader, queue, activityPanel, tui);

        Widget originalDown = reader.getWidgets().get(LineReader.DOWN_LINE_OR_HISTORY);
        reader.getWidgets().put(STANDARD_CHAT_DOWN_WIDGET, () -> {
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
            if (reader.getBuffer().toString().isBlank() && activityPanel.selectParent()) {
                redisplayWithContent(reader, tui);
                return true;
            }
            return originalLeft == null || originalLeft.apply();
        });
        reader.getKeyMaps().get(LineReader.EMACS).bind(
                new Reference(STANDARD_CHAT_PARENT_WIDGET), "\033[D", "\033OD");

        reader.getWidgets().put(STANDARD_CHAT_PAGE_UP_WIDGET, () -> {
            boolean changed = tui.pageContent(1);
            redisplayWithContent(reader, tui);
            return changed;
        });
        reader.getWidgets().put(STANDARD_CHAT_PAGE_DOWN_WIDGET, () -> {
            boolean changed = tui.pageContent(-1);
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
                    "\033[5~", "\033[5;2~", "\033[1;2A");
            activityKeys.bind(new Reference(STANDARD_CHAT_PAGE_DOWN_WIDGET),
                    "\033[6~", "\033[6;2~", "\033[1;2B");
        }

        Widget originalAccept = reader.getWidgets().get(LineReader.ACCEPT_LINE);
        if (originalAccept != null) {
            reader.getWidgets().put(LineReader.ACCEPT_LINE, () -> {
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
                if (activityPanel.isFocused() && reader.getBuffer().toString().isBlank()) {
                    String result = activityPanel.killSelected();
                    StandardChatActivityPanel.ActivityView view = activityPanel.currentView();
                    if (view != null && !view.main()) {
                        showActivityView(tui, view);
                    } else {
                        tui.printInScrollRegion(result);
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
        // Keep the input renderer last: the TUI repaint moves the terminal cursor,
        // while JLine REDISPLAY restores the prompt and cursor position.
        if (tui != null) {
            tui.redrawContentView();
        }
        reader.callWidget(LineReader.REDISPLAY);
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
                tui.showMainView();
            }
            return;
        }
        // updateActivityView also performs an authoritative switch when a
        // selection changed between asynchronous refresh callbacks.
        tui.updateActivityView(view.key(), view.title(), view.content());
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
        return switch (provider) {
            case "openai-codex" -> "OpenAI Codex";
            case "openai" -> "OpenAI";
            default -> ChatConfig.PROVIDERS.getOrDefault(provider, provider);
        };
    }

    String getAgentName() { return agentName; }
    void setAgentName(String name) { this.agentName = name; }

    String getLocalAgentName() { return localAgentName; }
    void setLocalAgentName(String name) { this.localAgentName = name; }

    String getBaseUrl() { return baseUrl; }

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

    boolean isAutoDequeueEnabled() {
        return queueManager == null ? autoDequeueEnabled : queueManager.isAutoDequeueEnabled();
    }

    /** Called by SessionLifecycleManager's showMainMenu() to run the setup wizard. */
    void runSetupFromMenu() {
        ChatConfig newConfig = SetupWizard.run();
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
