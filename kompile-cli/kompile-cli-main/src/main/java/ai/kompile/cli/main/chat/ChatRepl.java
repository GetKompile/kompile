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
import ai.kompile.utils.StringUtils;
import ai.kompile.cli.main.chat.config.ChatConfig;
import ai.kompile.cli.main.chat.config.DirectLlmClient;
import ai.kompile.cli.main.chat.config.SetupWizard;
import ai.kompile.cli.main.chat.enforcer.EnforcerConfig;
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
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.UserInterruptException;
import org.jline.reader.EndOfFileException;
import org.jline.terminal.Terminal;
import org.jline.keymap.KeyMap;
import org.jline.reader.impl.LineReaderImpl;

import java.io.File;
import java.io.IOError;
import java.net.http.HttpClient;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
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
        this.chatHistory = new ChatHistory(sessionId);

        // ChatMemory works in both modes: persistent memory + transcripts always,
        // RAG search only when server is connected
        this.chatMemory = new ChatMemory(mcpClient, sessionId, memoryEnabled);

        // Initialize tool & agent system
        this.permissionService = new PermissionService();
        this.agentRegistry = new AgentRegistry();
        this.renderer = new TerminalRenderer();
        this.ascii = new AsciiRenderer(renderer);

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
        this.processManager = new BackgroundProcessManager(sessionId);

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

        // Auto-load inline enforcer from project config if present
        loadInlineEnforcer(workDir);

        // Wire up extracted collaborators
        initCollaborators();
    }

    /** Initialise the four extracted collaborator classes after construction. */
    private void initCollaborators() {
        this.messageHandler = new ChatMessageHandler(
                this, mcpClient, httpClient, objectMapper, sessionId, localMode,
                chatHistory, chatMemory, sessionMetrics, renderer, ascii, agenticLoop,
                backgroundTaskManager, messageQueue, cancelSignal, pendingAttachments);

        this.queueManager = new MessageQueueManager(
                messageQueue, messageHandler, backgroundTaskManager, sessionMetrics,
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

    public void loadInlineEnforcer(Path workDir) {
        EnforcerConfig enforcerConfig = EnforcerConfig.load(workDir);
        if (enforcerConfig == null || !enforcerConfig.isKeywordMode()) {
            return;
        }
        try {
            String rulesText = enforcerConfig.buildRulesText(workDir);
            if (rulesText == null || rulesText.isBlank()) return;

            ai.kompile.cli.main.chat.enforcer.EnforcerPolicy policy =
                    new ai.kompile.cli.main.chat.enforcer.EnforcerPolicy(rulesText, enforcerConfig.getMaxCorrections(), false);
            ai.kompile.cli.main.chat.enforcer.KeywordEnforcerEvaluator evaluator =
                    ai.kompile.cli.main.chat.enforcer.KeywordEnforcerEvaluator.fromPolicy(policy, objectMapper, enforcerConfig);

            if (evaluator.isAvailable()) {
                agenticLoop.setInlineEnforcer(evaluator, policy, enforcerConfig.getMaxCorrections());
            }
        } catch (Exception e) {
            // Silently skip — don't break chat startup
        }
    }

    // ── Main REPL loop ────────────────────────────────────────────────────────

    public void run() throws Exception {
        // Restore previous conversation if resuming
        lifecycleManager.restoreSession();

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

        // Bind Ctrl+B to background current task
        ((LineReaderImpl) reader).getKeyMaps().get(LineReader.EMACS).bind(
            new org.jline.reader.Reference("background-task"),
            KeyMap.ctrl('B')
        );

        ((LineReaderImpl) reader).setVariable("background-task", new org.jline.reader.Widget() {
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

        // Bind cancel key (default: Escape) to cancel in-progress operations
        String cancelKeyBinding = resolveCancelKeyBinding();
        ((LineReaderImpl) reader).getKeyMaps().get(LineReader.EMACS).bind(
            new org.jline.reader.Reference("cancel-operation"),
            cancelKeyBinding
        );

        ((LineReaderImpl) reader).setVariable("cancel-operation", new org.jline.reader.Widget() {
            @Override
            public boolean apply() {
                if (llmBusy) {
                    cancelSignal.set(true);
                    BackgroundTaskManager.BackgroundTask task = backgroundTaskManager.getCurrentTask();
                    System.out.println();
                    System.out.println(renderer.yellow("  ⊘ Cancelling...") + renderer.dim(" [" + (task != null ? task.getId() : "?") + "]"));
                    System.out.println(renderer.dim("    Current operation will stop at next safe point"));
                    System.out.println();
                    System.out.flush();
                }
                return true;
            }
        });

        // ================================================================
        // Mode-switching hotkeys (Ctrl+X prefix chord)
        // ================================================================

        // Ctrl+X P — Toggle planning mode
        ((LineReaderImpl) reader).getKeyMaps().get(LineReader.EMACS).bind(
            new org.jline.reader.Reference("toggle-plan-mode"),
            KeyMap.ctrl('X'), "p"
        );
        ((LineReaderImpl) reader).getKeyMaps().get(LineReader.EMACS).bind(
            new org.jline.reader.Reference("toggle-plan-mode"),
            KeyMap.ctrl('X'), "P"
        );

        ((LineReaderImpl) reader).setVariable("toggle-plan-mode", new org.jline.reader.Widget() {
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
        ((LineReaderImpl) reader).getKeyMaps().get(LineReader.EMACS).bind(
            new org.jline.reader.Reference("show-todos"),
            KeyMap.ctrl('X'), "t"
        );
        ((LineReaderImpl) reader).getKeyMaps().get(LineReader.EMACS).bind(
            new org.jline.reader.Reference("show-todos"),
            KeyMap.ctrl('X'), "T"
        );

        ((LineReaderImpl) reader).setVariable("show-todos", new org.jline.reader.Widget() {
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
        ((LineReaderImpl) reader).getKeyMaps().get(LineReader.EMACS).bind(
            new org.jline.reader.Reference("cycle-agent"),
            KeyMap.ctrl('X'), "a"
        );
        ((LineReaderImpl) reader).getKeyMaps().get(LineReader.EMACS).bind(
            new org.jline.reader.Reference("cycle-agent"),
            KeyMap.ctrl('X'), "A"
        );

        ((LineReaderImpl) reader).setVariable("cycle-agent", new org.jline.reader.Widget() {
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
            System.out.println(ascii.welcomePanelWithModes(sessionId, localAgentName, false, true));
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

        // Show enforcer status if auto-loaded
        if (agenticLoop.isInlineEnforcerEnabled()) {
            System.out.println(renderer.green("  Enforcer: " + agenticLoop.describeInlineEnforcer()
                    + " — /enforcer off to disable"));
        }
        System.out.println();

        // Start the unified TUI: TopBar + scroll region + StatusBar
        tui.setAgentName(localMode ? localAgentName : agentName);
        tui.setSessionId(sessionId);
        tui.setMode(localMode ? "local" : "server");
        tui.setPlanningMode(agenticLoop.isPlanningMode());
        tui.start(terminal);

        // Wire change listeners so the status bar redraws on state changes
        Runnable statusRedraw = statusBar::requestRedraw;
        backgroundTaskManager.addChangeListener(statusRedraw);
        processManager.addChangeListener(statusRedraw);

        // Wire subagent lifecycle tracking into the status bar
        ai.kompile.cli.main.chat.agent.SubagentRunner runner = toolRegistry.getSubagentRunner();
        if (runner != null) {
            runner.setLifecycleListener(new ai.kompile.cli.main.chat.agent.SubagentRunner.LifecycleListener() {
                @Override
                public void onSubagentStart(String id, String type, String description) {
                    statusBar.registerSubagent(id, type, description);
                }
                @Override
                public void onSubagentEnd(String id) {
                    statusBar.unregisterSubagent(id);
                }
            });
        }

        // Pass terminal ref to ChatCompleter for bottom border rendering
        ChatCompleter.setTerminalRef(reader, terminal);

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
                    continue;
                } catch (EndOfFileException e) {
                    break;
                } catch (IOError e) {
                    // JLine wraps stty/terminal errors in IOError during
                    // shutdown or when the terminal is interrupted. Exit
                    // cleanly instead of crashing.
                    break;
                }

                if (line == null || line.isBlank()) {
                    continue;
                }

                String trimmed = line.trim();

                if (trimmed.startsWith("/")) {
                    if (!commandRouter.handleSlashCommand(trimmed)) {
                        break;
                    }
                } else {
                    messageHandler.handleChatMessage(trimmed);
                }
            }
        } finally {
            reader.getHistory().save();

            // Log session summary to transcript and save metrics
            lifecycleManager.printSessionSummary();
            chatHistory.close();

            // Save metrics JSON alongside transcript
            Path metricsFile = chatHistory.getTranscriptFile().resolveSibling(sessionId + ".metrics.json");
            sessionMetrics.saveToFile(metricsFile, objectMapper);

            // Stop the unified TUI (resets scroll regions, stops refresh threads)
            tui.stop();

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

        // Show queue count
        if (!messageQueue.isEmpty()) {
            int queueSize = messageQueue.size();
            prompt.append(renderer.green("["))
                  .append(renderer.yellow(String.valueOf(queueSize)))
                  .append(renderer.green("]"));
        }

        // Show queue chain progress
        if (backgroundTaskManager.isInQueueChain()) {
            int current = backgroundTaskManager.getQueueChainCurrent();
            int total = backgroundTaskManager.getQueueChainTotal();
            prompt.append(renderer.dim("(" + current + "/" + total + ")"));
        }

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

        // Auto-dequeue next message if enabled and queue is not empty
        if (autoDequeueEnabled && !messageQueue.isEmpty()) {
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

                System.out.println();
                System.out.println(renderer.green("  ✓ Complete ") + renderer.dim("→ sending next (" + current + "/" + total + ")"));
                System.out.println(renderer.dim("     → ") + StringUtils.truncate(nextMsg.getContent(), 60));
                if (remaining > 0) {
                    System.out.println(renderer.dim("     (" + remaining + " more queued)"));
                }
                System.out.println();
                messageQueue.dequeue();
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
            System.out.println();
            System.out.println(renderer.green("  ✓ All " + total + " queued messages processed"));
            System.out.println();
        }
    }

    // ── Key binding helper ────────────────────────────────────────────────────

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

    String getAgentName() { return agentName; }
    void setAgentName(String name) { this.agentName = name; }

    String getLocalAgentName() { return localAgentName; }
    void setLocalAgentName(String name) { this.localAgentName = name; }

    String getBaseUrl() { return baseUrl; }

    boolean isRagEnabled() { return ragEnabled; }
    void setRagEnabled(boolean enabled) { this.ragEnabled = enabled; }

    ChatConfig getChatConfig() { return chatConfig; }
    void updateChatConfig(ChatConfig config) { this.chatConfig = config; }

    ChatMemory getChatMemory() { return chatMemory; }

    AgentRegistry getAgentRegistry() { return agentRegistry; }

    List<McpSseClient.ToolInfo> getCachedTools() { return cachedTools; }
    void setCachedTools(List<McpSseClient.ToolInfo> tools) { this.cachedTools = tools; }

    boolean isLlmBusy() { return llmBusy; }
    void setLlmBusy(boolean busy) { this.llmBusy = busy; }

    boolean isAutoDequeueEnabled() { return autoDequeueEnabled; }

    /** Called by SessionLifecycleManager's showMainMenu() to run the setup wizard. */
    void runSetupFromMenu() {
        ChatConfig newConfig = SetupWizard.run();
        if (newConfig != null) {
            this.chatConfig = newConfig;
            System.out.println(renderer.green("Configuration updated. New messages will use the updated settings."));
            if (localMode) {
                System.out.println(renderer.dim("Note: restart the chat to fully apply the new configuration."));
            }
        } else {
            System.out.println("Setup cancelled.");
        }
    }

}
