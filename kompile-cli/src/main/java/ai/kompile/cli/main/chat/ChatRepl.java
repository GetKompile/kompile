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
import ai.kompile.cli.main.chat.agent.*;
import ai.kompile.cli.main.chat.config.ChatConfig;
import ai.kompile.cli.main.chat.config.DirectLlmClient;
import ai.kompile.cli.main.chat.config.SetupWizard;
import ai.kompile.cli.main.chat.enforcer.EnforcerConfig;
import ai.kompile.cli.main.chat.enforcer.EnforcerSetupWizard;
import ai.kompile.cli.main.chat.harness.PerformanceHarness;
import ai.kompile.cli.main.chat.permission.PermissionService;
import ai.kompile.cli.main.chat.render.AsciiRenderer;
import ai.kompile.cli.main.chat.render.TerminalRenderer;
import ai.kompile.cli.main.chat.roles.RoleConfig;
import ai.kompile.cli.main.chat.roles.RoleManager;
import ai.kompile.cli.main.chat.roles.RoleWizard;
import ai.kompile.cli.main.chat.skill.CustomSkillLoader;
import ai.kompile.cli.main.chat.skill.SkillConfig;
import ai.kompile.cli.main.chat.skill.SkillRegistry;
import ai.kompile.cli.main.chat.tools.*;
import ai.kompile.cli.main.chat.tui.StatusBar;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.UserInterruptException;
import org.jline.reader.EndOfFileException;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.jline.keymap.KeyMap;
import org.jline.reader.impl.LineReaderImpl;

import ai.kompile.cli.main.chat.config.ModelContextWindows;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOError;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import ai.kompile.cli.main.chat.config.DirectLlmClient;

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
 */
public class ChatRepl {

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

    // Persistent below-bar status line showing processes, subagents, queue
    private final StatusBar statusBar;

    // Pending file/image attachments for the next message
    private final List<PendingAttachment> pendingAttachments = new ArrayList<>();

    /** A file or image queued for the next chat message. */
    public record PendingAttachment(Path path, String mimeType, boolean isImage) {}

    private static final Set<String> IMAGE_MIME_TYPES = Set.of(
            "image/png", "image/jpeg", "image/gif", "image/webp", "image/bmp", "image/svg+xml");

    private static final Set<String> TEXT_EXTENSIONS = Set.of(
            "txt", "md", "java", "py", "js", "ts", "json", "xml", "yaml", "yml",
            "toml", "ini", "cfg", "conf", "sh", "bash", "zsh", "fish", "ps1",
            "c", "cpp", "h", "hpp", "cs", "go", "rs", "rb", "kt", "scala",
            "html", "css", "scss", "less", "sql", "graphql", "proto",
            "dockerfile", "makefile", "cmake", "gradle", "properties", "csv", "log");

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
            this.objectMapper = new ObjectMapper();
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

        // Initialize below-bar status line
        this.statusBar = new StatusBar(backgroundTaskManager, processManager, messageQueue, renderer);

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
    }

    private void loadInlineEnforcer(Path workDir) {
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

    public void run() throws Exception {
        // Restore previous conversation if resuming
        restoreSession();

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
                statusBar.setPlanningMode(newState);
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
                statusBar.setActiveAgent(localAgentName);

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

        // Start the persistent below-bar status line
        statusBar.setActiveAgent(localMode ? localAgentName : agentName);
        statusBar.setPlanningMode(agenticLoop.isPlanningMode());
        statusBar.start(terminal);

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
                    // Show queue indicator in prompt if there are queued messages
                    int termWidth = terminal.getWidth() > 0 ? terminal.getWidth() : 80;
                    printTopBorder(termWidth);
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
                    if (!handleSlashCommand(trimmed)) {
                        break;
                    }
                } else {
                    handleChatMessage(trimmed);
                }
            }
        } finally {
            reader.getHistory().save();

            // Log session summary to transcript and save metrics
            printSessionSummary();
            chatHistory.close();

            // Save metrics JSON alongside transcript
            Path metricsFile = chatHistory.getTranscriptFile().resolveSibling(sessionId + ".metrics.json");
            sessionMetrics.saveToFile(metricsFile, objectMapper);

            // Stop the below-bar status line (resets scroll regions)
            statusBar.stop();

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
     * Restore a previous conversation session by replaying transcript turns
     * into the DirectLlmClient history (local mode) or printing the transcript (server mode).
     */
    private void restoreSession() {
        if (!ChatHistory.exists(sessionId)) return;

        try {
            ChatHistory history = new ChatHistory(sessionId);
            List<ChatHistory.Turn> turns = history.readTurns();

            if (turns.isEmpty()) return;

            // Print the previous transcript
            String transcript = history.readTranscript();
            if (transcript != null) {
                System.out.println();
                // Print a condensed version — last 50 lines
                String[] lines = transcript.split("\n");
                if (lines.length > 50) {
                    System.out.println(renderer.dim("  ... (" + (lines.length - 50) + " earlier lines)"));
                    for (int i = lines.length - 50; i < lines.length; i++) {
                        System.out.println(lines[i]);
                    }
                } else {
                    System.out.println(transcript);
                }
                System.out.println(renderer.dim("─── end of previous conversation (" + turns.size() + " turns) ───"));
                System.out.println();
            }

            // In local mode, replay turns into the DirectLlmClient
            if (localMode && agenticLoop != null) {
                agenticLoop.restoreHistory(turns);
                System.out.println(renderer.dim("  Restored " + turns.size() + " turns to context."));
            }

            // Validate and report subagent availability on resume
            validateAndReportSubagentAvailability();

        } catch (Exception e) {
            System.err.println("Warning: Could not restore session: " + e.getMessage());
        }
    }

    /**
     * Validate that subagent delegation is properly configured and report
     * availability to the user when resuming a session.
     */
    private void validateAndReportSubagentAvailability() {
        try {
            // Check if TaskTool is registered
            CliTool taskTool = toolRegistry.get("task");
            if (taskTool == null) {
                System.out.println(renderer.warn("  ⚠ Subagent delegation (task tool) not available"));
                return;
            }

            // Validate TaskTool health
            if (taskTool instanceof TaskTool) {
                TaskTool task = (TaskTool) taskTool;
                if (!task.isHealthy()) {
                    System.out.println(renderer.warn("  ⚠ Subagent delegation tool is not properly configured"));
                    return;
                }
            }

            // Check subagent registry health
            if (!agentRegistry.isSubagentDelegationHealthy()) {
                System.out.println(renderer.warn("  ⚠ Subagent registry may not be properly configured"));
            }

            // Report subagent availability
            String summary = agentRegistry.getSubagentSummary();
            System.out.println(renderer.dim("  ✓ Subagent delegation enabled: " + summary));

        } catch (Exception e) {
            // Non-fatal - subagents are optional, but log the issue
            System.out.println(renderer.dim("  ⚠ Subagent validation skipped: " + e.getMessage()));
        }
    }

    /**
     * @return false if the REPL should exit
     */
    private boolean handleSlashCommand(String input) {
        String[] parts = input.split("\\s+", 2);
        String cmd = parts[0].toLowerCase();
        String rest = parts.length > 1 ? parts[1] : "";

        switch (cmd) {
            case "/quit":
            case "/exit":
                return false;

            case "/help":
                printHelp();
                return true;

            case "/setup":
                runSetup();
                return true;

            case "/tools":
                if (localMode) {
                    listLocalTools();
                } else {
                    listTools();
                }
                return true;

            case "/subagents":
                listSubagents();
                return true;

            case "/local-tools":
                listLocalTools();
                return true;

            case "/tool":
                if (localMode) {
                    invokeLocalTool(rest);
                } else {
                    invokeTool(rest);
                }
                return true;

            case "/local-tool":
                invokeLocalTool(rest);
                return true;

            case "/status":
                printStatus();
                return true;

            case "/history":
                if (localMode) {
                    showTranscript();
                } else {
                    showHistory();
                }
                return true;

            case "/clear":
                if (localMode) {
                    System.out.println("Session transcript cleared (local mode).");
                } else {
                    clearSession();
                }
                return true;

            case "/compact":
                handleCompact(rest);
                return true;

            case "/rag":
                if (localMode) {
                    System.out.println(renderer.dim("RAG is not available in local mode. "
                            + "Connect to a kompile-app server for RAG support."));
                } else {
                    toggleRag(rest);
                }
                return true;

            case "/agents":
                if (localMode) {
                    listLocalAgents();
                } else {
                    listAgents();
                }
                return true;

            case "/local-agents":
                listLocalAgents();
                return true;

            case "/agent":
                if (localMode) {
                    switchLocalAgent(rest);
                } else {
                    switchAgent(rest);
                }
                return true;

            case "/local-agent":
                switchLocalAgent(rest);
                return true;

            case "/config":
                if (localMode) {
                    showLocalConfig();
                } else {
                    handleConfig(rest);
                }
                return true;

            case "/sessions":
                if (localMode) {
                    listConversations();
                } else {
                    listSessions();
                }
                return true;

            case "/ask":
                if (localMode) {
                    // In local mode, /ask goes through the agentic loop too
                    agenticChat(rest);
                } else {
                    streamAgentChat(rest);
                }
                return true;

            case "/agent-chat":
                agenticChat(rest);
                return true;

            case "/conversations":
                listConversations();
                return true;

            case "/transcript":
                showTranscript();
                return true;

            case "/memory":
                handleMemory(rest);
                return true;

            case "/recall":
                handleRecall(rest);
                return true;

            case "/permissions":
                handlePermissions(rest);
                return true;

            case "/todos":
                showTodos();
                return true;

            case "/plan":
                togglePlanMode(rest);
                return true;

            // Queue management commands
            case "/queue":
                enqueueMessage(rest);
                return true;

            case "/queues":
                listQueuedMessages();
                return true;

            case "/queue-send":
                if (rest.isBlank()) {
                    sendNextQueuedMessage();
                } else {
                    sendQueuedMessageById(rest.trim());
                }
                return true;

            case "/queue-send-all":
                sendAllQueuedMessages();
                return true;

            case "/queue-remove":
                removeQueuedMessage(rest.trim());
                return true;

            case "/queue-clear":
                clearQueuedMessages();
                return true;

            case "/queue-status":
                showQueueStatus();
                return true;

            // Background task management commands
            case "/jobs":
                listBackgroundTasks();
                return true;

            case "/jobs-remove":
                removeBackgroundTask(rest.trim());
                return true;

            case "/jobs-clear":
                clearCompletedBackgroundTasks();
                return true;

            // Process management & status bar commands
            case "/processes":
                showProcessPanel();
                return true;

            case "/process-kill":
                killProcess(rest.trim());
                return true;

            case "/process-output":
                showProcessOutput(rest.trim());
                return true;

            case "/process-status":
                showProcessStatus(rest.trim());
                return true;

            case "/statusbar":
                toggleStatusBar();
                return true;

            case "/auto-dequeue":
                toggleAutoDequeue();
                return true;

            case "/stats":
                printSessionSummary();
                return true;

            case "/passthrough":
                launchPassthroughMode(rest.trim());
                return true;

            case "/resume":
                launchResumeTool(rest.trim());
                return true;

            case "/mode":
                handleModeSwitch(rest.trim());
                return true;

            case "/menu":
                showMainMenu();
                return true;

            case "/skills":
                listSkills();
                return true;

            case "/roles":
                manageRoles();
                return true;

            case "/role":
                if (rest.isBlank()) {
                    showCurrentRole();
                } else {
                    assignRole(rest.trim());
                }
                return true;

            case "/model":
                handleModelCommand(rest.trim());
                return true;

            case "/enforce":
            case "/enforcer":
                handleEnforcerCommand(rest.trim());
                return true;

            case "/forward":
                forwardCommandToAgent(rest.trim());
                return true;

            // Multimodal attachment commands
            case "/image":
                handleAttachImage(rest.trim());
                return true;

            case "/file":
                handleAttachFile(rest.trim());
                return true;

            case "/attach":
                if (rest.isBlank()) {
                    showPendingAttachments();
                } else {
                    handleAttachFile(rest.trim());
                }
                return true;

            case "/attachments":
                showPendingAttachments();
                return true;

            default:
                // Check if it's a skill invocation (e.g. /commit, /review)
                String skillName = cmd.substring(1); // strip leading /
                SkillConfig skill = skillRegistry.get(skillName);
                if (skill != null) {
                    executeSkill(skill, rest);
                    return true;
                }
                System.out.println("Unknown command: " + cmd + ". Type /help for available commands.");
                return true;
        }
    }

    // ========================================================================
    // Chat message handling
    // ========================================================================

    private void handleChatMessage(String message) {
        // Auto-queue message if LLM is busy
        if (llmBusy) {
            MessageQueue.QueuedMessage msg = messageQueue.enqueue(message);
            sessionMetrics.recordMessageQueued();
            int queueSize = messageQueue.size();
            System.out.println();
            System.out.println(renderer.yellow("  ⏳ Queued ") + renderer.dim("(" + queueSize + " pending)"));
            System.out.println(renderer.dim("     → ") + truncate(message, 60));
            if (autoDequeueEnabled) {
                System.out.println(renderer.dim("     Will auto-send when current task completes"));
            } else {
                System.out.println(renderer.dim("     Use /queue-send to send manually"));
            }
            System.out.println();
            chatHistory.logUserMessage("(queued) " + message);
            return;
        }

        // Reset cancel signal for this new message
        cancelSignal.set(false);

        sessionMetrics.recordUserTurn(message);
        chatHistory.logUserMessage(message);

        if (localMode) {
            // In local mode, all messages go through the agentic loop
            handleLocalChat(message);
        } else {
            handleServerChat(message);
        }
    }

    private void handleLocalChat(String message) {
        // Build memory-enriched message if memory is enabled
        String enrichedMessage = message;
        if (chatMemory != null && chatMemory.isEnabled()) {
            String memoryContext = chatMemory.buildMemoryContext(message);
            if (memoryContext != null) {
                enrichedMessage = "<memory_context>\n" + memoryContext + "</memory_context>\n\n" + message;
            }
        }

        // Load pending attachments and pass to agentic loop
        List<DirectLlmClient.AttachmentInput> attachments = loadAttachments();
        if (attachments != null) {
            agenticLoop.setPendingAttachments(attachments);
        }

        System.out.println();
        llmBusy = true;
        BackgroundTaskManager.BackgroundTask task = backgroundTaskManager.startTask("LLM response: " + truncate(message, 50));
        printGeneratingIndicator();
        agenticLoop.setOnFirstOutput(this::stopGeneratingSpinner);
        long turnStart = System.currentTimeMillis();

        try {
            String response = agenticLoop.chat(
                    enrichedMessage, sessionId, localAgentName, agentName, false);

            stopGeneratingSpinner();
            long turnDuration = System.currentTimeMillis() - turnStart;
            System.out.println("\n");
            chatHistory.logAgentResponse(localAgentName, response, turnDuration);
            task.appendOutput(response);
            sessionMetrics.recordAssistantTurn(response, turnDuration);
            completeTaskWithAutoDequeue();
        } catch (Exception e) {
            stopGeneratingSpinner();
            System.err.println("\nError in chat: " + e.getMessage());
            task.setError(e);
            completeTaskWithAutoDequeue();
        }
    }

    private void handleServerChat(String message) {
        // Build memory-enriched message if memory is enabled
        String enrichedMessage = message;
        if (chatMemory != null && chatMemory.isEnabled()) {
            String memoryContext = chatMemory.buildMemoryContext(message);
            if (memoryContext != null) {
                enrichedMessage = "<memory_context>\n" + memoryContext + "</memory_context>\n\n" + message;
            }
        }

        llmBusy = true;
        BackgroundTaskManager.BackgroundTask task = backgroundTaskManager.startTask("LLM response: " + truncate(message, 50));
        printGeneratingIndicator();
        try {
            ObjectNode args = objectMapper.createObjectNode();
            args.put("sessionId", sessionId);
            args.put("message", enrichedMessage);
            args.put("enableRag", ragEnabled);
            args.put("maxResults", 10);
            args.put("similarityThreshold", 0.5);

            String rawResponse = mcpClient.callTool("send_chat_message", args);
            stopGeneratingSpinner();

            try {
                JsonNode json = objectMapper.readTree(rawResponse);
                String answer = json.path("answer").asText(null);
                int docsRetrieved = json.path("documentsRetrieved").asInt(0);
                long timeMs = json.path("executionTimeMs").asLong(0);

                if (answer != null) {
                    System.out.println("\n" + answer);
                } else {
                    answer = rawResponse;
                    System.out.println("\n" + rawResponse);
                }

                if (docsRetrieved > 0) {
                    System.out.printf("  [%d docs retrieved, %dms]%n", docsRetrieved, timeMs);
                    sessionMetrics.recordRagQuery(docsRetrieved);
                }
                System.out.println();

                String finalAnswer = answer != null ? answer : rawResponse;
                sessionMetrics.recordAssistantTurn(finalAnswer, timeMs);
                chatHistory.logAssistantMessage(finalAnswer, docsRetrieved, timeMs);
            } catch (Exception e) {
                System.out.println("\n" + rawResponse + "\n");
                chatHistory.logAssistantMessage(rawResponse, 0, 0);
            }
        } catch (Exception e) {
            stopGeneratingSpinner();
            System.err.println("Error sending message: " + e.getMessage());
            task.setError(e);
        }
        completeTaskWithAutoDequeue();
    }

    // ========================================================================
    // Agent streaming (/ask command) via REST /api/agents/chat/stream SSE
    // ========================================================================

    private void streamAgentChat(String message) {
        if (message.isBlank()) {
            System.out.println("Usage: /ask <message>");
            System.out.println("Sends a message to the configured agent with streaming output.");
            return;
        }

        chatHistory.logUserMessage("/ask " + message);

        // Build memory-enriched message if memory is enabled
        String enrichedMessage = message;
        if (chatMemory != null && chatMemory.isEnabled()) {
            String memoryContext = chatMemory.buildMemoryContext(message);
            if (memoryContext != null) {
                enrichedMessage = "<memory_context>\n" + memoryContext + "</memory_context>\n\n" + message;
            }
        }

        llmBusy = true;
        BackgroundTaskManager.BackgroundTask task = backgroundTaskManager.startTask("Streaming LLM response: " + truncate(message, 40));
        printGeneratingIndicator();
        try {
            ObjectNode request = objectMapper.createObjectNode();
            request.put("message", enrichedMessage);
            request.put("agentName", agentName);
            request.put("enableRag", ragEnabled);
            request.put("ragMaxResults", 5);
            request.put("ragSimilarityThreshold", 0.5);
            request.put("includeKeywordSearch", true);
            request.put("includeSemanticSearch", true);
            request.put("injectMcpTools", true);
            request.put("skipPermissions", true);
            request.put("timeoutSeconds", 300);

            String body = objectMapper.writeValueAsString(request);

            HttpRequest httpRequest = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/api/agents/chat/stream"))
                    .header("Content-Type", "application/json")
                    .header("Accept", "text/event-stream")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .timeout(Duration.ofMinutes(10))
                    .build();

            HttpResponse<InputStream> response = httpClient.send(
                    httpRequest, HttpResponse.BodyHandlers.ofInputStream());

            if (response.statusCode() != 200) {
                stopGeneratingSpinner();
                System.err.println("Agent stream failed: HTTP " + response.statusCode());
                return;
            }

            stopGeneratingSpinner();
            System.out.println();

            // Accumulate full response for transcript
            StringBuilder fullResponse = new StringBuilder();
            long[] durationMs = {0};

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(response.body()))) {
                String eventType = null;
                StringBuilder dataBuffer = new StringBuilder();
                String line;

                while ((line = reader.readLine()) != null) {
                    if (cancelSignal.get()) {
                        System.out.println("\n" + renderer.yellow("  ⊘ Cancelled"));
                        fullResponse.append("\n[Cancelled by user]");
                        break;
                    }
                    if (line.startsWith("event:")) {
                        eventType = line.substring(6).trim();
                    } else if (line.startsWith("data:")) {
                        dataBuffer.append(line.substring(5).trim());
                    } else if (line.isEmpty() && eventType != null) {
                        handleStreamEvent(eventType, dataBuffer.toString(), fullResponse, durationMs);
                        eventType = null;
                        dataBuffer.setLength(0);
                    }
                }
            }

            System.out.println("\n");

            String responseText = fullResponse.toString();
            chatHistory.logAgentResponse(agentName, responseText, durationMs[0]);
            task.appendOutput(responseText);
            sessionMetrics.recordAssistantTurn(responseText, durationMs[0]);

        } catch (Exception e) {
            stopGeneratingSpinner();
            System.err.println("\nError in agent stream: " + e.getMessage());
            task.setError(e);
        }
        completeTaskWithAutoDequeue();
    }

    // ========================================================================
    // Agentic tool loop (/agent-chat command) - local tool execution
    // ========================================================================

    private void agenticChat(String message) {
        if (message.isBlank()) {
            System.out.println("Usage: /agent-chat <message>");
            System.out.println("Sends a message through the agentic tool loop with local tool execution.");
            System.out.println("Current local agent: " + localAgentName);
            System.out.println("Available local agents: " + String.join(", ",
                    agentRegistry.getPrimaryAgents().stream().map(AgentConfig::getName).toArray(String[]::new)));
            return;
        }

        chatHistory.logUserMessage("/agent-chat " + message);

        // Build memory-enriched message if memory is enabled
        String enrichedMessage = message;
        if (chatMemory != null && chatMemory.isEnabled()) {
            String memoryContext = chatMemory.buildMemoryContext(message);
            if (memoryContext != null) {
                enrichedMessage = "<memory_context>\n" + memoryContext + "</memory_context>\n\n" + message;
            }
        }

        System.out.println();

        llmBusy = true;
        BackgroundTaskManager.BackgroundTask task = backgroundTaskManager.startTask("Agentic chat: " + truncate(message, 40));
        printGeneratingIndicator();
        agenticLoop.setOnFirstOutput(this::stopGeneratingSpinner);
        long turnStart = System.currentTimeMillis();
        try {
            String response = agenticLoop.chat(
                    enrichedMessage, sessionId, localAgentName, agentName, ragEnabled);

            stopGeneratingSpinner();
            long turnDuration = System.currentTimeMillis() - turnStart;
            System.out.println("\n");
            chatHistory.logAgentResponse(localAgentName, response, turnDuration);
            task.appendOutput(response);
            sessionMetrics.recordAssistantTurn(response, turnDuration);

        } catch (Exception e) {
            stopGeneratingSpinner();
            System.err.println("\nError in agentic chat: " + e.getMessage());
            task.setError(e);
        }
        completeTaskWithAutoDequeue();
    }

    private void handleStreamEvent(String eventType, String data,
                                   StringBuilder fullResponse, long[] durationMs) {
        switch (eventType) {
            case "chunk":
                String chunk = data;
                if (chunk.startsWith("\"") && chunk.endsWith("\"")) {
                    try {
                        chunk = objectMapper.readValue(chunk, String.class);
                    } catch (Exception e) {
                        // Use as-is
                    }
                }
                System.out.print(chunk);
                System.out.flush();
                fullResponse.append(chunk);
                break;

            case "start":
                try {
                    JsonNode json = objectMapper.readTree(data);
                    String agent = json.path("agent").asText("");
                    System.out.println("[Agent: " + agent + "]");
                } catch (Exception e) {
                    // ignore
                }
                break;

            case "sources":
                try {
                    JsonNode sources = objectMapper.readTree(data);
                    if (sources.isArray() && sources.size() > 0) {
                        System.out.println("[Retrieved " + sources.size() + " documents]");
                    }
                } catch (Exception e) {
                    // ignore
                }
                break;

            case "stats":
                try {
                    JsonNode stats = objectMapper.readTree(data);
                    durationMs[0] = stats.path("durationMs").asLong(0);
                    if (durationMs[0] > 0) {
                        System.out.printf("%n  [completed in %dms]", durationMs[0]);
                    }
                } catch (Exception e) {
                    // ignore
                }
                break;

            case "error":
                try {
                    JsonNode error = objectMapper.readTree(data);
                    System.err.println("\nError: " + error.path("message").asText(data));
                } catch (Exception e) {
                    System.err.println("\nError: " + data);
                }
                break;

            case "complete":
                break;

            case "cancelled":
                System.out.println("\n[Cancelled]");
                break;

            default:
                break;
        }
    }

    // ========================================================================
    // Slash commands
    // ========================================================================

    private void printHelp() {
        StringBuilder body = new StringBuilder();

        if (localMode) {
            body.append(renderer.bold(renderer.cyan("Chat"))).append("\n");
            body.append("  ").append(renderer.cyan("<text>")).append("              Send a message (agentic tool loop)\n");
            body.append("  ").append(renderer.cyan("/agent-chat <text>")).append("  Same as above (explicit)\n");
            body.append("\n");
            body.append(renderer.bold(renderer.cyan("Tools & Agents"))).append("\n");
            body.append("  ").append(renderer.cyan("/tools")).append("              List local CLI tools\n");
            body.append("  ").append(renderer.cyan("/tool")).append(" name [json]   Invoke a tool directly\n");
            body.append("  ").append(renderer.cyan("/subagents")).append("          List available subagents for delegation\n");
            body.append("  ").append(renderer.cyan("/agents")).append("             List local agent types\n");
            body.append("  ").append(renderer.cyan("/agent")).append(" name         Switch agent type\n");
            body.append("  ").append(renderer.cyan("/model")).append(" [name]       Show/switch LLM model\n");
            body.append("  ").append(renderer.cyan("/permissions")).append("        View or set tool permissions\n");
            body.append("  ").append(renderer.cyan("/todos")).append("              Show the session task list\n");
            body.append("  ").append(renderer.cyan("/plan")).append(" [on|off]       Toggle planning mode (plan → approve → execute)\n");
            body.append("\n");
            body.append(renderer.bold(renderer.cyan("Navigation"))).append("\n");
            body.append("  ").append(renderer.cyan("/menu")).append("               Main menu (chat, passthrough, resume, setup)\n");
            body.append("  ").append(renderer.cyan("/passthrough [agent]")).append("  Launch external CLI agent\n");
            body.append("  ").append(renderer.cyan("/resume")).append("               Browse & resume conversations\n");
            body.append("  ").append(renderer.cyan("/mode <mode>")).append("          Switch mode (standard/passthrough/plan)\n");
            body.append("  ").append(renderer.cyan("/setup")).append("              Reconfigure LLM provider\n");
            body.append("  ").append(renderer.cyan("/enforcer")).append(" [cmd]       Enforcer config (init/show/rules/run/delete)\n");
            body.append("\n");
            body.append(renderer.bold(renderer.cyan("Message Queue"))).append("\n");
            body.append("  ").append(renderer.cyan("/queue <text>")).append("       Add a message to the queue\n");
            body.append("  ").append(renderer.cyan("/queues")).append("               List queued messages\n");
            body.append("  ").append(renderer.cyan("/queue-send")).append("           Send the next queued message\n");
            body.append("  ").append(renderer.cyan("/queue-send <id>")).append("      Send a specific queued message\n");
            body.append("  ").append(renderer.cyan("/queue-send-all")).append("       Send all queued messages\n");
            body.append("  ").append(renderer.cyan("/queue-remove <id>")).append("    Remove a message from the queue\n");
            body.append("  ").append(renderer.cyan("/queue-clear")).append("          Clear all queued messages\n");
            body.append("  ").append(renderer.cyan("/queue-status")).append("         Show queue status\n");
            body.append("\n");
            body.append(renderer.bold(renderer.cyan("Hotkeys & Background"))).append("\n");
            body.append("  ").append(renderer.cyan("Escape")).append("              Cancel in-progress LLM/tool operation\n");
            body.append("  ").append(renderer.cyan("Ctrl+B")).append("              Background current LLM response\n");
            body.append("  ").append(renderer.cyan("Ctrl+X P")).append("            Toggle planning mode\n");
            body.append("  ").append(renderer.cyan("Ctrl+X T")).append("            Show session task list (todos)\n");
            body.append("  ").append(renderer.cyan("Ctrl+X A")).append("            Cycle agent (coder/planner)\n");
            body.append("  ").append(renderer.cyan("/jobs")).append("               List background tasks\n");
            body.append("  ").append(renderer.cyan("/jobs-remove <id>")).append("   Remove a completed task\n");
            body.append("  ").append(renderer.cyan("/jobs-clear")).append("         Clear all completed tasks\n");
            body.append("  ").append(renderer.cyan("/processes")).append("          Show processes & subagents panel\n");
            body.append("  ").append(renderer.cyan("/process-kill <id>")).append("  Kill a running process\n");
            body.append("  ").append(renderer.cyan("/process-output <id>")).append("View process output\n");
            body.append("  ").append(renderer.cyan("/process-status <id>")).append("Show process or watcher status\n");
            body.append("  ").append(renderer.cyan("/statusbar")).append("          Toggle status bar on/off\n");
            body.append("  ").append(renderer.cyan("/auto-dequeue")).append("       Toggle auto-send queued messages\n");
            body.append("\n");
            body.append(renderer.bold(renderer.cyan("Attachments"))).append("\n");
            body.append("  ").append(renderer.cyan("/image <path>")).append("       Attach an image (PNG, JPEG, GIF, WebP)\n");
            body.append("  ").append(renderer.cyan("/file <path>")).append("        Attach a file (image or text)\n");
            body.append("  ").append(renderer.cyan("/attach [path]")).append("      Attach a file or show pending attachments\n");
            body.append("  ").append(renderer.cyan("/attachments")).append("        List pending attachments\n");
            body.append("\n");
            body.append(renderer.bold(renderer.cyan("Skills"))).append("\n");
            body.append("  ").append(renderer.cyan("/skills")).append("             List all available skills\n");
            body.append("  ").append(renderer.cyan("/<skill> [args]")).append("     Run a skill (e.g. /commit, /review, /fix)\n");
            body.append("\n");
            body.append(renderer.bold(renderer.cyan("Roles"))).append("\n");
            body.append("  ").append(renderer.cyan("/roles")).append("              Open role management wizard\n");
            body.append("  ").append(renderer.cyan("/role")).append("               Show current active role\n");
            body.append("  ").append(renderer.cyan("/role <name>")).append("        Assign a role to the current agent\n");
            body.append("\n");
            body.append(renderer.bold(renderer.cyan("Context"))).append("\n");
            body.append("  ").append(renderer.cyan("/compact [focus]")).append("    LLM-summarize conversation, freeing context\n");
            body.append("\n");
            body.append(renderer.bold(renderer.cyan("General"))).append("\n");
            body.append("  ").append(renderer.cyan("/stats")).append("              Session statistics (tokens, timing, tools)\n");
            body.append("  ").append(renderer.cyan("/help")).append("               This help message\n");
            body.append("  ").append(renderer.cyan("/quit")).append("               Exit the chat");
        } else {
            body.append(renderer.bold(renderer.cyan("Chat Commands"))).append("\n");
            body.append("  ").append(renderer.cyan("<text>")).append("              Send a message (inline RAG chat)\n");
            body.append("  ").append(renderer.cyan("/ask <text>")).append("         Send via server agent with streaming\n");
            body.append("  ").append(renderer.cyan("/agent-chat <text>")).append("  Agentic tool loop (local tools)\n");
            body.append("\n");
            body.append(renderer.bold(renderer.cyan("Local Tools & Agents"))).append("\n");
            body.append("  ").append(renderer.cyan("/local-tools")).append("        List all local CLI tools\n");
            body.append("  ").append(renderer.cyan("/local-tool")).append(" name    Invoke a local tool directly\n");
            body.append("  ").append(renderer.cyan("/local-agents")).append("       List local agent types\n");
            body.append("  ").append(renderer.cyan("/local-agent")).append(" name   Switch local agent type\n");
            body.append("  ").append(renderer.cyan("/model")).append(" [name]       Show/switch LLM model\n");
            body.append("  ").append(renderer.cyan("/permissions")).append("        View or set tool permissions\n");
            body.append("  ").append(renderer.cyan("/todos")).append("              Show the session task list\n");
            body.append("  ").append(renderer.cyan("/plan")).append(" [on|off]       Toggle planning mode (plan → approve → execute)\n");
            body.append("\n");
            body.append(renderer.bold(renderer.cyan("Memory & Recall"))).append("\n");
            body.append("  ").append(renderer.cyan("/memory")).append("             Show memory status / toggle\n");
            body.append("  ").append(renderer.cyan("/recall <query>")).append("     Search conversations and RAG\n");
            body.append("\n");
            body.append(renderer.bold(renderer.cyan("Session & History"))).append("\n");
            body.append("  ").append(renderer.cyan("/history")).append("            Server-side conversation history\n");
            body.append("  ").append(renderer.cyan("/transcript")).append("         Local transcript file\n");
            body.append("  ").append(renderer.cyan("/conversations")).append("      List all saved conversations\n");
            body.append("  ").append(renderer.cyan("/clear")).append("              Clear server history\n");
            body.append("  ").append(renderer.cyan("/compact [focus]")).append("    LLM-summarize conversation (local mode only)\n");
            body.append("  ").append(renderer.cyan("/config")).append("             Show/update session config\n");
            body.append("  ").append(renderer.cyan("/setup")).append("              Reconfigure LLM provider\n");
            body.append("\n");
            body.append(renderer.bold(renderer.cyan("Modes"))).append("\n");
            body.append("  ").append(renderer.cyan("/passthrough [agent]")).append("  Launch external CLI agent\n");
            body.append("  ").append(renderer.cyan("/resume")).append("               Browse & resume conversations\n");
            body.append("  ").append(renderer.cyan("/mode <mode>")).append("          Switch mode (standard/passthrough/plan)\n");
            body.append("  ").append(renderer.cyan("/enforcer")).append(" [cmd]         Enforcer config (init/show/rules/run/delete)\n");
            body.append("\n");
            body.append(renderer.bold(renderer.cyan("RAG & Server Agents"))).append("\n");
            body.append("  ").append(renderer.cyan("/rag")).append(" on|off         Toggle RAG retrieval\n");
            body.append("  ").append(renderer.cyan("/agents")).append("             List server agents\n");
            body.append("  ").append(renderer.cyan("/agent")).append(" <name>       Switch server agent\n");
            body.append("  ").append(renderer.cyan("/tools")).append("              List MCP tools\n");
            body.append("  ").append(renderer.cyan("/tool")).append(" <name> [json] Invoke MCP tool\n");
            body.append("\n");
            body.append(renderer.bold(renderer.cyan("Skills"))).append("\n");
            body.append("  ").append(renderer.cyan("/skills")).append("             List all available skills\n");
            body.append("  ").append(renderer.cyan("/<skill> [args]")).append("     Run a skill (e.g. /commit, /review, /fix)\n");
            body.append("\n");
            body.append(renderer.bold(renderer.cyan("Roles"))).append("\n");
            body.append("  ").append(renderer.cyan("/roles")).append("              Open role management wizard\n");
            body.append("  ").append(renderer.cyan("/role")).append("               Show current active role\n");
            body.append("  ").append(renderer.cyan("/role <name>")).append("        Assign a role to the current agent\n");
            body.append("\n");
            body.append(renderer.bold(renderer.cyan("General"))).append("\n");
            body.append("  ").append(renderer.cyan("/status")).append("             Connection and session info\n");
            body.append("  ").append(renderer.cyan("/help")).append("               This help message\n");
            body.append("  ").append(renderer.cyan("/quit")).append("               Exit the chat");
            body.append("\n");
            body.append(renderer.bold(renderer.cyan("Message Queue"))).append("\n");
            body.append("  ").append(renderer.cyan("/queue <text>")).append("       Add a message to the queue\n");
            body.append("  ").append(renderer.cyan("/queues")).append("               List queued messages\n");
            body.append("  ").append(renderer.cyan("/queue-send")).append("           Send the next queued message\n");
            body.append("  ").append(renderer.cyan("/queue-send <id>")).append("      Send a specific queued message\n");
            body.append("  ").append(renderer.cyan("/queue-send-all")).append("       Send all queued messages\n");
            body.append("  ").append(renderer.cyan("/queue-remove <id>")).append("    Remove a message from the queue\n");
            body.append("  ").append(renderer.cyan("/queue-clear")).append("          Clear all queued messages\n");
            body.append("  ").append(renderer.cyan("/queue-status")).append("         Show queue status\n");
            body.append("\n");
            body.append(renderer.bold(renderer.cyan("Hotkeys & Background"))).append("\n");
            body.append("  ").append(renderer.cyan("Escape")).append("              Cancel in-progress LLM/tool operation\n");
            body.append("  ").append(renderer.cyan("Ctrl+B")).append("              Background current LLM response\n");
            body.append("  ").append(renderer.cyan("Ctrl+X P")).append("            Toggle planning mode\n");
            body.append("  ").append(renderer.cyan("Ctrl+X T")).append("            Show session task list (todos)\n");
            body.append("  ").append(renderer.cyan("Ctrl+X A")).append("            Cycle agent (coder/planner)\n");
            body.append("  ").append(renderer.cyan("/jobs")).append("               List background tasks\n");
            body.append("  ").append(renderer.cyan("/jobs-remove <id>")).append("   Remove a completed task\n");
            body.append("  ").append(renderer.cyan("/jobs-clear")).append("         Clear all completed tasks\n");
            body.append("  ").append(renderer.cyan("/processes")).append("          Show processes & subagents panel\n");
            body.append("  ").append(renderer.cyan("/process-kill <id>")).append("  Kill a running process\n");
            body.append("  ").append(renderer.cyan("/process-status <id>")).append("Show process or watcher status\n");
            body.append("  ").append(renderer.cyan("/statusbar")).append("          Toggle status bar on/off\n");
            body.append("  ").append(renderer.cyan("/auto-dequeue")).append("       Toggle auto-send queued messages\n");
        }

        System.out.println(ascii.panel("Help", body.toString(), AsciiRenderer.ROUNDED, "cyan"));
        System.out.println();
        System.out.println(renderer.dim("  Conversations saved to ~/.kompile/conversations/"));
        System.out.println(renderer.dim("  Resume: kompile chat --resume <session-id>"));
        System.out.println(renderer.dim("  Continue last: kompile chat --continue"));
    }

    private void runSetup() {
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

    private void handleEnforcerCommand(String args) {
        Path wd = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        String subCmd = args.isBlank() ? "status" : args.split("\\s+")[0].toLowerCase();

        switch (subCmd) {
            case "init", "setup" -> {
                EnforcerConfig config = EnforcerSetupWizard.run(wd);
                if (config != null) {
                    System.out.println(renderer.green("Enforcer configured. Run 'kompile enforcer' to start."));
                } else {
                    System.out.println("Enforcer setup cancelled.");
                }
            }
            case "show", "status" -> {
                // Show live status first
                if (agenticLoop.isInlineEnforcerEnabled()) {
                    System.out.println(renderer.green("  [ACTIVE] " + agenticLoop.describeInlineEnforcer()));
                } else if (agenticLoop.describeInlineEnforcer() != null) {
                    System.out.println(renderer.yellow("  [DISABLED] " + agenticLoop.describeInlineEnforcer() + " — /enforcer on to enable"));
                } else {
                    System.out.println(renderer.dim("  [OFF] No inline enforcer loaded"));
                }
                EnforcerConfig config = EnforcerConfig.load(wd);
                if (config == null) {
                    System.out.println(renderer.dim("  No enforcer config for this project."));
                    System.out.println(renderer.dim("  Run /enforcer init to configure."));
                    return;
                }
                System.out.println();
                System.out.println(renderer.bold("  Enforcer Config"));
                System.out.println("  ──────────────────────────");
                System.out.println("  Agent:         " + renderer.cyan(config.getAgent()));
                System.out.println("  Mode:          " + (config.isKeywordMode() ? "keyword (no LLM)" : "LLM judge"));
                System.out.println("  Max retries:   " + config.getMaxCorrections());
                System.out.println("  Diff archive:  " + (config.isArchiveDiffs() ? "enabled" : "disabled"));
                System.out.println("  Auto-rollback: " + (config.isAutoRollbackOnViolation() ? "yes" : "no"));
                if (config.getRuleFile() != null) {
                    System.out.println("  Rule file:     " + config.getRuleFile());
                }
                if (config.getInlineRules() != null && !config.getInlineRules().isBlank()) {
                    int lines = config.getInlineRules().split("\n").length;
                    System.out.println("  Inline rules:  " + lines + " lines");
                }
                if (!config.getBannedTools().isEmpty()) {
                    System.out.println("  Banned tools:  " + String.join(", ", config.getBannedTools()));
                }
                if (!config.getBannedCommands().isEmpty()) {
                    System.out.println("  Banned cmds:   " + String.join(", ", config.getBannedCommands()));
                }
                if (!config.getBannedKeywords().isEmpty()) {
                    System.out.println("  Banned words:  " + String.join(", ", config.getBannedKeywords()));
                }
                if (!config.getDiffPatternRules().isEmpty()) {
                    System.out.println("  Diff patterns: " + config.getDiffPatternRules().size() + " rules");
                }
                if (config.getDiffPatternsFile() != null) {
                    System.out.println("  Patterns file: " + config.getDiffPatternsFile());
                }
                if (config.getJudgeProvider() != null) {
                    System.out.println("  Judge:         " + config.getJudgeProvider()
                            + (config.getJudgeModel() != null ? "/" + config.getJudgeModel() : ""));
                }
                System.out.println("  Config path:   " + EnforcerConfig.resolveConfigPath(wd));
                System.out.println();
                System.out.println(renderer.dim("  /enforcer init   — reconfigure"));
                System.out.println(renderer.dim("  /enforcer delete — remove config"));
                System.out.println(renderer.dim("  /enforcer run    — launch enforcer session"));
                System.out.println();
            }
            case "delete", "remove" -> {
                try {
                    if (EnforcerConfig.delete(wd)) {
                        System.out.println(renderer.green("Enforcer config deleted."));
                    } else {
                        System.out.println(renderer.dim("No enforcer config found."));
                    }
                } catch (Exception e) {
                    System.out.println(renderer.red("Failed: " + e.getMessage()));
                }
            }
            case "run", "start", "launch" -> {
                EnforcerConfig config = EnforcerConfig.load(wd);
                if (config == null) {
                    System.out.println(renderer.dim("No enforcer config. Run /enforcer init first."));
                    return;
                }
                System.out.println(renderer.dim("Launching enforcer mode..."));
                System.out.println(renderer.dim("Use 'kompile enforcer' for the full interactive session."));
                System.out.println();
                // Build the command line for the user
                StringBuilder cmd = new StringBuilder("kompile enforcer");
                if (config.isKeywordMode()) cmd.append(" --keyword-mode");
                if (!config.isArchiveDiffs()) cmd.append(" --archive-diffs=false");
                if (config.getMaxCorrections() != 2) cmd.append(" --max-corrections=").append(config.getMaxCorrections());
                if (config.getRuleFile() != null) cmd.append(" --rule-file=").append(config.getRuleFile());
                if (config.getDiffPatternsFile() != null) cmd.append(" --diff-patterns=").append(config.getDiffPatternsFile());
                if (!"claude".equals(config.getAgent())) cmd.append(" --agent=").append(config.getAgent());
                System.out.println("  " + renderer.cyan(cmd.toString()));
                System.out.println();
                System.out.println(renderer.dim("  Or just run 'kompile enforcer' — it auto-loads the project config."));
            }
            case "rules" -> {
                EnforcerConfig config = EnforcerConfig.load(wd);
                if (config == null) {
                    System.out.println(renderer.dim("No enforcer config. Run /enforcer init first."));
                    return;
                }
                try {
                    String rules = config.buildRulesText(wd);
                    if (rules.isBlank()) {
                        System.out.println(renderer.dim("No rules configured."));
                    } else {
                        System.out.println();
                        System.out.println(renderer.bold("  Active Enforcer Rules"));
                        System.out.println("  ──────────────────────────");
                        for (String line : rules.split("\n")) {
                            System.out.println("  " + line);
                        }
                        System.out.println();
                    }
                } catch (Exception e) {
                    System.out.println(renderer.red("Error loading rules: " + e.getMessage()));
                }
            }
            case "on", "enable" -> {
                if (agenticLoop.describeInlineEnforcer() != null) {
                    agenticLoop.setInlineEnforcerEnabled(true);
                    System.out.println(renderer.green("  Enforcer enabled: " + agenticLoop.describeInlineEnforcer()));
                } else {
                    // Try to load from config
                    loadInlineEnforcer(wd);
                    if (agenticLoop.isInlineEnforcerEnabled()) {
                        System.out.println(renderer.green("  Enforcer loaded and enabled: " + agenticLoop.describeInlineEnforcer()));
                    } else {
                        System.out.println(renderer.dim("No enforcer config for this project. Run /enforcer init first."));
                    }
                }
            }
            case "off", "disable" -> {
                agenticLoop.setInlineEnforcerEnabled(false);
                System.out.println(renderer.yellow("  Enforcer disabled."));
            }
            case "reload" -> {
                loadInlineEnforcer(wd);
                if (agenticLoop.isInlineEnforcerEnabled()) {
                    System.out.println(renderer.green("  Enforcer reloaded: " + agenticLoop.describeInlineEnforcer()));
                } else {
                    System.out.println(renderer.dim("No keyword-mode enforcer config found."));
                }
            }
            default -> {
                System.out.println("Usage: /enforcer [on|off|init|show|rules|reload|delete]");
                System.out.println();
                System.out.println(renderer.dim("  on      — enable inline enforcer for this session"));
                System.out.println(renderer.dim("  off     — disable inline enforcer"));
                System.out.println(renderer.dim("  reload  — reload config from disk"));
                System.out.println(renderer.dim("  init    — interactive setup wizard"));
                System.out.println(renderer.dim("  show    — view current config (default)"));
                System.out.println(renderer.dim("  rules   — show resolved enforcer rules"));
                System.out.println(renderer.dim("  run     — show launch command"));
                System.out.println(renderer.dim("  delete  — remove project config"));
            }
        }
    }

    private void forwardCommandToAgent(String args) {
        if (args.isBlank()) {
            System.out.println("Usage: /forward <command> [args]");
            System.out.println(renderer.dim("  Forwards a slash command to the underlying agent CLI."));
            return;
        }
        String slashCmd = args.startsWith("/") ? args : "/" + args;
        String agentBinary = AgentCommandForwarder.resolveAgentBinary(agentName);
        if (agentBinary == null) {
            System.out.println(renderer.yellow("Agent '" + agentName + "' not found on PATH."));
            System.out.println(renderer.dim("Supported agents: " + String.join(", ",
                    ai.kompile.cli.main.chat.config.ChatConfig.getPassthroughAgentOrder())));
            return;
        }
        AgentCommandForwarder forwarder = new AgentCommandForwarder();
        AgentCommandForwarder.AgentCommand agentCmd = forwarder.mapSlashCommand(slashCmd, agentBinary, agentName);
        if (agentCmd != null) {
            System.out.println(renderer.dim("  → " + agentCmd.label()));
            forwarder.executeWithRealtimeOutput(agentCmd);
        } else {
            System.out.println(renderer.dim("  Command not supported for agent: " + agentName));
        }
    }

    private void showLocalConfig() {
        if (chatConfig == null) {
            System.out.println("No LLM configuration. Run /setup to configure.");
            return;
        }

        LinkedHashMap<String, String> configMap = new LinkedHashMap<>();
        configMap.put("Provider", renderer.cyan(chatConfig.getProvider()));
        configMap.put("Model", renderer.cyan(chatConfig.getModel()));
        configMap.put("Base URL", chatConfig.resolveBaseUrl());
        configMap.put("API Key", chatConfig.getApiKey() != null ?
                renderer.dim(chatConfig.getApiKey().substring(0, Math.min(4, chatConfig.getApiKey().length())) + "...") :
                renderer.red("not set"));
        configMap.put("Config file", "~/.kompile/chat-config.json");

        System.out.println(ascii.panel("LLM Configuration", ascii.keyValueList(configMap), AsciiRenderer.ROUNDED, "blue"));
        System.out.println();
        System.out.println(renderer.dim("  /setup to reconfigure"));
    }

    private void listTools() {
        try {
            cachedTools = mcpClient.listTools();
            if (cachedTools.isEmpty()) {
                System.out.println("No MCP tools available.");
                return;
            }
            List<String> headers = List.of("Tool", "Description");
            List<List<String>> rows = new ArrayList<>();
            for (McpSseClient.ToolInfo tool : cachedTools) {
                rows.add(List.of(tool.getName(), truncate(tool.getDescription(), 55)));
            }
            System.out.println(ascii.sectionHeader("MCP Tools (" + cachedTools.size() + ")"));
            System.out.println(ascii.table(headers, rows));
        } catch (Exception e) {
            System.err.println("Error listing tools: " + e.getMessage());
        }
    }

    private void listLocalTools() {
        AgentConfig agent = agentRegistry.get(localAgentName);
        if (agent == null) agent = agentRegistry.getDefault();

        List<CliTool> tools = toolRegistry.getToolsForAgent(agent);
        List<String> headers = List.of("Tool", "Permission", "Description");
        List<List<String>> rows = new ArrayList<>();
        for (CliTool tool : tools) {
            String desc = tool.description();
            if (desc.length() > 55) desc = desc.substring(0, 52) + "...";
            rows.add(List.of(tool.id(), tool.permissionKey(), desc));
        }

        String title = "Local CLI Tools (" + tools.size() + ") — agent: " + agent.getName();
        System.out.println(ascii.sectionHeader(title));
        System.out.println(ascii.table(headers, rows));
        System.out.println();
        if (localMode) {
            System.out.println(renderer.dim("  /tool <name> [json]   invoke directly"));
        } else {
            System.out.println(renderer.dim("  /local-tool <name> [json]   invoke directly"));
            System.out.println(renderer.dim("  /agent-chat <message>       use via agentic loop"));
        }
    }

    /**
     * List available subagents for delegation via the task tool.
     */
    private void listSubagents() {
        List<AgentConfig> subagents = agentRegistry.getSubagents();
        
        if (subagents.isEmpty()) {
            System.out.println(renderer.yellow("  No subagents available for delegation."));
            System.out.println(renderer.dim("  Subagents can be added in ~/.kompile/agents/ or .kompile/agents/"));
            return;
        }

        List<String> headers = List.of("Agent", "Description", "Steps", "Model");
        List<List<String>> rows = new ArrayList<>();
        
        for (AgentConfig subagent : subagents) {
            String desc = subagent.getDescription();
            if (desc != null && desc.length() > 50) {
                desc = desc.substring(0, 47) + "...";
            }
            String model = subagent.getModelHint() != null ? subagent.getModelHint() : "default";
            String customTag = subagent.isCustom() ? " [custom]" : "";
            
            rows.add(List.of(
                subagent.getName() + customTag,
                desc != null ? desc : "",
                String.valueOf(subagent.getMaxSteps()),
                model
            ));
        }

        String title = "Available Subagents (" + subagents.size() + ")";
        System.out.println(ascii.sectionHeader(title));
        System.out.println(ascii.table(headers, rows));
        System.out.println();
        System.out.println(renderer.dim("  Use in chat: \"task an explore-deep to analyze the codebase\""));
        System.out.println(renderer.dim("  Or via /task tool: delegate specific subtasks to specialized agents"));
    }

    private void invokeLocalTool(String rest) {
        if (rest.isBlank()) {
            System.out.println("Usage: /tool <tool_name> [json_arguments]");
            System.out.println("Example: /tool read {\"file_path\":\"pom.xml\"}");
            System.out.println("Example: /tool glob {\"pattern\":\"**/*.java\"}");
            System.out.println("Example: /tool bash {\"command\":\"git status\"}");
            return;
        }

        String[] parts = rest.split("\\s+", 2);
        String toolName = parts[0];
        String argsJson = parts.length > 1 ? parts[1] : "{}";

        CliTool tool = toolRegistry.get(toolName);
        if (tool == null) {
            System.err.println("Unknown local tool: " + toolName);
            System.out.println("Available: " + String.join(", ", toolRegistry.ids()));
            return;
        }

        try {
            JsonNode args = objectMapper.readTree(argsJson);
            AgentConfig agent = agentRegistry.get(localAgentName);
            if (agent == null) agent = agentRegistry.getDefault();

            Path workDir = Paths.get(System.getProperty("user.dir"));
            ToolContext ctx = new ToolContext(sessionId, agent, permissionService, workDir, toolRegistry);

            TerminalRenderer.SpinnerHandle spinner = renderer.startSpinner(toolName);
            ToolResult result = tool.execute(args, ctx);
            spinner.stop();

            System.out.println(renderer.renderToolCallComplete(toolName, result));
        } catch (ToolExecutionException e) {
            if (e.isPermissionDenied()) {
                System.out.println(renderer.renderToolCallDenied(toolName, e.getMessage()));
            } else {
                System.err.println(renderer.red("Tool error: " + e.getMessage()));
            }
        } catch (Exception e) {
            System.err.println(renderer.red("Error: " + e.getMessage()));
        }
    }

    private void listLocalAgents() {
        List<String> agentHeaders = List.of("Agent", "Type", "Description", "Active");
        List<List<String>> agentRows = new ArrayList<>();
        for (AgentConfig a : agentRegistry.getPrimaryAgents()) {
            String active = a.getName().equals(localAgentName) ? renderer.green("●") : "";
            agentRows.add(List.of(a.getName(), "primary", a.getDisplayName(), active));
        }
        for (AgentConfig a : agentRegistry.getSubagents()) {
            agentRows.add(List.of(a.getName(), renderer.dim("subagent"), a.getDisplayName(), ""));
        }

        System.out.println(ascii.sectionHeader("Local Agents"));
        System.out.println(ascii.table(agentHeaders, agentRows));
        System.out.println();
        String switchCmd = localMode ? "/agent <name>" : "/local-agent <name>";
        System.out.println(renderer.dim("  Switch with: " + switchCmd));
    }

    // ========================================================================
    // Model switching
    // ========================================================================

    private void handleModelCommand(String rest) {
        if (!localMode) {
            System.out.println(renderer.dim("Model switching is only available in local mode."));
            return;
        }
        if (chatConfig == null) {
            System.out.println("No LLM configuration. Run /setup to configure.");
            return;
        }

        String provider = chatConfig.getProvider();
        String currentModel = chatConfig.getModel();

        // Get all models for the provider
        String[] providerModels = ChatConfig.getDefaultModels(provider);

        // Get the active agent's allowed models
        AgentConfig activeAgent = agentRegistry.get(localAgentName);
        if (activeAgent == null) activeAgent = agentRegistry.getDefault();
        List<String> allowedModels = activeAgent.getAllowedModels();

        if (rest.isEmpty()) {
            // Show current model and list available models
            System.out.println(ascii.sectionHeader("Model"));
            System.out.println("  Current model: " + renderer.cyan(currentModel));
            System.out.println("  Provider:      " + renderer.dim(provider));
            System.out.println("  Agent:         " + renderer.dim(localAgentName));
            System.out.println();

            if (providerModels.length == 0 && (allowedModels == null || allowedModels.isEmpty())) {
                System.out.println(renderer.dim("  No predefined models for provider '" + provider + "'."));
                System.out.println(renderer.dim("  Use /model <model-name> to set any model."));
            } else {
                // Merge: show all provider models + mark which are allowed for this agent
                LinkedHashSet<String> allModels = new LinkedHashSet<>();
                for (String m : providerModels) allModels.add(m);
                if (allowedModels != null) {
                    for (String m : allowedModels) allModels.add(m);
                }

                List<String> headers = List.of("Model", "Status", "Agent Access");
                List<List<String>> rows = new ArrayList<>();
                for (String m : allModels) {
                    String status = m.equals(currentModel) ? renderer.green("● active") : "";
                    String access;
                    if (allowedModels == null || allowedModels.isEmpty()) {
                        access = renderer.green("allowed");
                    } else if (allowedModels.contains(m)) {
                        access = renderer.green("allowed");
                    } else {
                        access = renderer.dim("not in allowlist");
                    }
                    rows.add(List.of(m, status, access));
                }
                System.out.println(ascii.table(headers, rows));
            }
            System.out.println();
            System.out.println(renderer.dim("  Usage: /model <model-name>"));
            System.out.println(renderer.dim("  Example: /model claude-opus-4-20250514"));
            return;
        }

        // Switch model
        String newModel = rest;

        // Warn if not in the agent's allowlist (but still allow it)
        if (allowedModels != null && !allowedModels.isEmpty() && !allowedModels.contains(newModel)) {
            System.out.println(renderer.yellow("  ⚠ Model '" + newModel + "' is not in the allowlist for agent '" +
                    localAgentName + "'."));
            System.out.println(renderer.dim("    Allowed: " + String.join(", ", allowedModels)));
            System.out.println(renderer.dim("    Switching anyway — use /model to see recommended models."));
        }

        chatConfig.setModel(newModel);
        try {
            chatConfig.save();
        } catch (Exception e) {
            // Non-fatal — config will still be used in memory this session
        }

        // Update session metrics
        sessionMetrics.setModel(newModel);

        chatHistory.logSystem("Switched model to: " + newModel);
        System.out.println(renderer.green("  Switched model to: ") + renderer.cyan(newModel));
        System.out.println(renderer.dim("  Note: the new model will be used for subsequent messages."));
    }

    // ========================================================================
    // Multimodal attachment handling (/image, /file, /attach)
    // ========================================================================

    private void handleAttachImage(String pathStr) {
        if (pathStr.isBlank()) {
            System.out.println("Usage: /image <path>");
            System.out.println("Attach an image to the next message.");
            System.out.println("Supported: PNG, JPEG, GIF, WebP");
            return;
        }

        // Check model supports vision
        if (localMode && chatConfig != null) {
            String model = chatConfig.getModel();
            if (!ModelContextWindows.supportsVision(model)) {
                System.out.println(renderer.yellow("  ⚠ Model '" + model + "' may not support image inputs."));
                System.out.println(renderer.dim("    Attaching anyway — the API will reject if unsupported."));
            }
        }

        Path filePath = resolveAttachmentPath(pathStr);
        if (filePath == null) return;

        String mime = detectImageMimeType(filePath);
        if (mime == null) {
            System.out.println(renderer.yellow("  ⚠ Could not detect image type for: " + filePath.getFileName()));
            System.out.println(renderer.dim("    Supported formats: PNG, JPEG, GIF, WebP"));
            return;
        }

        pendingAttachments.add(new PendingAttachment(filePath, mime, true));
        System.out.println(renderer.green("  ✓ Image attached: ") + renderer.cyan(filePath.getFileName().toString()));
        System.out.println(renderer.dim("    Type: " + mime + ", Size: " + formatFileSize(filePath)));
        System.out.println(renderer.dim("    Will be included with your next message."));
    }

    private void handleAttachFile(String pathStr) {
        if (pathStr.isBlank()) {
            System.out.println("Usage: /file <path>");
            System.out.println("Attach a file to the next message.");
            System.out.println("Images are sent as vision inputs; text files are inlined.");
            return;
        }

        Path filePath = resolveAttachmentPath(pathStr);
        if (filePath == null) return;

        String mime = detectImageMimeType(filePath);
        boolean isImage = (mime != null);

        if (!isImage) {
            // Determine mime for text files
            String ext = getFileExtension(filePath).toLowerCase();
            if (TEXT_EXTENSIONS.contains(ext)) {
                mime = "text/plain";
            } else {
                mime = "application/octet-stream";
            }
        }

        pendingAttachments.add(new PendingAttachment(filePath, mime, isImage));
        String typeLabel = isImage ? "Image" : "File";
        System.out.println(renderer.green("  ✓ " + typeLabel + " attached: ") + renderer.cyan(filePath.getFileName().toString()));
        System.out.println(renderer.dim("    Type: " + mime + ", Size: " + formatFileSize(filePath)));
        System.out.println(renderer.dim("    Will be included with your next message."));
    }

    private void showPendingAttachments() {
        if (pendingAttachments.isEmpty()) {
            System.out.println(renderer.dim("  No pending attachments."));
            System.out.println(renderer.dim("  Use /image <path> or /file <path> to attach files."));
            return;
        }

        System.out.println(renderer.bold("  Pending Attachments:"));
        for (int i = 0; i < pendingAttachments.size(); i++) {
            PendingAttachment att = pendingAttachments.get(i);
            String icon = att.isImage() ? "🖼" : "📄";
            System.out.printf("  %d. %s %s (%s)%n", i + 1, icon,
                    renderer.cyan(att.path().getFileName().toString()), att.mimeType());
        }
        System.out.println(renderer.dim("  These will be sent with your next message."));
        System.out.println(renderer.dim("  Send a message or use /attachments to review."));
    }

    /**
     * Load pending attachments into DirectLlmClient.AttachmentInput format,
     * reading file contents (base64 for images, text for text files).
     */
    private List<DirectLlmClient.AttachmentInput> loadAttachments() {
        if (pendingAttachments.isEmpty()) return null;

        List<DirectLlmClient.AttachmentInput> loaded = new ArrayList<>();
        for (PendingAttachment att : pendingAttachments) {
            try {
                if (att.isImage()) {
                    byte[] bytes = Files.readAllBytes(att.path());
                    String base64 = Base64.getEncoder().encodeToString(bytes);
                    loaded.add(new DirectLlmClient.AttachmentInput(
                            att.path().toString(), att.mimeType(), true, base64, null));
                } else {
                    String text = Files.readString(att.path());
                    loaded.add(new DirectLlmClient.AttachmentInput(
                            att.path().toString(), att.mimeType(), false, null, text));
                }
            } catch (Exception e) {
                System.err.println("Warning: Could not read attachment " + att.path().getFileName() + ": " + e.getMessage());
            }
        }

        // Clear pending after loading
        pendingAttachments.clear();
        return loaded.isEmpty() ? null : loaded;
    }

    private Path resolveAttachmentPath(String pathStr) {
        Path filePath = Paths.get(pathStr);
        if (!filePath.isAbsolute()) {
            filePath = Paths.get(System.getProperty("user.dir")).resolve(filePath);
        }
        if (!Files.exists(filePath)) {
            System.out.println(renderer.yellow("  ⚠ File not found: " + pathStr));
            return null;
        }
        if (!Files.isRegularFile(filePath)) {
            System.out.println(renderer.yellow("  ⚠ Not a file: " + pathStr));
            return null;
        }
        return filePath;
    }

    private static String detectImageMimeType(Path path) {
        String ext = getFileExtension(path).toLowerCase();
        return switch (ext) {
            case "png" -> "image/png";
            case "jpg", "jpeg" -> "image/jpeg";
            case "gif" -> "image/gif";
            case "webp" -> "image/webp";
            case "bmp" -> "image/bmp";
            case "svg" -> "image/svg+xml";
            default -> null;
        };
    }

    private static String getFileExtension(Path path) {
        String name = path.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot >= 0 ? name.substring(dot + 1) : "";
    }

    private static String formatFileSize(Path path) {
        try {
            long size = Files.size(path);
            if (size < 1024) return size + " B";
            if (size < 1024 * 1024) return String.format("%.1f KB", size / 1024.0);
            return String.format("%.1f MB", size / (1024.0 * 1024.0));
        } catch (Exception e) {
            return "unknown";
        }
    }

    private void switchLocalAgent(String name) {
        if (name.isBlank()) {
            System.out.println("Current local agent: " + localAgentName);
            System.out.println("Usage: /agent <name>");
            return;
        }

        AgentConfig agent = agentRegistry.get(name.trim());
        if (agent == null) {
            System.err.println("Unknown agent: " + name);
            return;
        }
        if (agent.isSubagent()) {
            System.err.println("Cannot switch to subagent '" + name + "'. Use a primary agent.");
            return;
        }

        localAgentName = name.trim();
        chatHistory.logSystem("Switched local agent to: " + localAgentName);
        System.out.println("Switched local agent to: " + localAgentName);
    }

    // ========================================================================
    // Skill execution
    // ========================================================================

    private void executeSkill(SkillConfig skill, String args) {
        String expandedPrompt = skill.expandTemplate(args);
        String taggedPrompt = "<skill name=\"" + skill.getName() + "\">\n" + expandedPrompt + "\n</skill>";
        chatHistory.logSystem("Executing skill: /" + skill.getName() + (args.isBlank() ? "" : " " + args));
        handleChatMessage(taggedPrompt);
    }

    private void listSkills() {
        List<String> categories = skillRegistry.categories();
        List<String> headers = List.of("Skill", "Category", "Description", "Type");
        List<List<String>> rows = new ArrayList<>();

        for (String category : categories) {
            for (SkillConfig skill : skillRegistry.getByCategory(category)) {
                String type = skill.isBuiltIn() ? renderer.dim("built-in") : renderer.cyan("custom");
                rows.add(List.of("/" + skill.getName(), category, skill.getDescription(), type));
            }
        }

        System.out.println(ascii.sectionHeader("Skills"));
        System.out.println(ascii.table(headers, rows));
        System.out.println();
        System.out.println(renderer.dim("  Usage: /<skill> [args]  (e.g. /commit -m \"fix auth bug\")"));
        System.out.println(renderer.dim("  Custom skills: ~/.kompile/skills/ or .kompile/skills/"));
    }

    // ========================================================================
    // Role management
    // ========================================================================

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

    private void manageRoles() {
        RoleWizard wizard = new RoleWizard(roleManager);
        wizard.run();
    }

    private void showCurrentRole() {
        String activeRole = roleManager.getActiveRoleName();
        System.out.println();
        System.out.println(ascii.sectionHeader("Current Role"));
        System.out.println();
        
        if (activeRole == null) {
            System.out.println("  No role currently active");
            System.out.println("  Using default agent: " + renderer.cyan(agentName));
        } else {
            RoleConfig role = roleManager.getRole(activeRole);
            if (role != null) {
                System.out.println("  Role: " + renderer.cyan(role.getName()));
                System.out.println("  Display Name: " + role.getDisplayName());
                System.out.println("  Category: " + role.getCategory());
                System.out.println("  Description: " + role.getDescription());
                System.out.println();
                System.out.println(renderer.dim("  Use /role <name> to switch roles"));
                System.out.println(renderer.dim("  Use /roles to manage roles"));
            }
        }
        System.out.println();
    }

    private void assignRole(String roleName) {
        RoleConfig role = roleManager.setActiveRole(roleName);
        if (role == null) {
            System.out.println();
            System.out.println(renderer.yellow("  Role not found: ") + roleName);
            System.out.println(renderer.dim("  Use /roles to see available roles"));
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
        System.out.println(renderer.dim("  Agent changed from " + oldAgentName + " to " + agentName));
        System.out.println(renderer.dim("  The agent will now use this role's system prompt"));
        System.out.println();

        // Track in metrics
        sessionMetrics.setAgentName(agentName);
        sessionMetrics.setActiveRole(role.getName());
    }

    private void handlePermissions(String rest) {
        if (rest.isBlank()) {
            List<String> permHeaders = List.of("Tool", "Level", "Description");
            List<List<String>> permRows = List.of(
                    List.of("read", renderer.green("allow"), "File reading"),
                    List.of("grep", renderer.green("allow"), "Content search"),
                    List.of("glob", renderer.green("allow"), "File search"),
                    List.of("list", renderer.green("allow"), "Directory listing"),
                    List.of("edit", renderer.yellow("ask"), "File modification"),
                    List.of("bash", renderer.yellow("ask"), "Shell execution"),
                    List.of("webfetch", renderer.green("allow"), "URL fetching"),
                    List.of("task", renderer.green("allow"), "Subagent spawning")
            );
            System.out.println(ascii.sectionHeader("Tool Permissions"));
            System.out.println(ascii.table(permHeaders, permRows));
            System.out.println();
            System.out.println(renderer.dim("  /permissions <key> <allow|deny|ask>"));
            System.out.println(renderer.dim("  /permissions allow-all"));
            return;
        }

        String[] parts = rest.trim().split("\\s+", 2);
        if ("allow-all".equals(parts[0])) {
            permissionService.allowAll();
            System.out.println("All permissions set to allow for this session.");
            return;
        }

        if (parts.length < 2) {
            System.out.println("Usage: /permissions <key> <allow|deny|ask>");
            return;
        }

        String key = parts[0];
        String level = parts[1].toUpperCase();
        try {
            PermissionService.PermissionLevel pl = PermissionService.PermissionLevel.valueOf(level);
            permissionService.setUserOverride(key, pl);
            System.out.println("Set " + key + " = " + level.toLowerCase());
        } catch (IllegalArgumentException e) {
            System.err.println("Invalid level: " + parts[1] + ". Use allow, deny, or ask.");
        }
    }

    private void showTodos() {
        List<TodoWriteTool.TodoItem> todos = TodoWriteTool.getTodos(sessionId);
        if (todos.isEmpty()) {
            System.out.println("No tasks in the current session.");
            return;
        }

        System.out.println(renderer.renderTodoList(todos));
    }

    private void togglePlanMode(String arg) {
        if (arg == null || arg.isBlank()) {
            boolean current = agenticLoop.isPlanningMode();
            System.out.println("Planning mode: " + (current ? renderer.green("on") : renderer.dim("off")));
            System.out.println();
            System.out.println(renderer.dim("  When enabled, the agent first creates a read-only plan"));
            System.out.println(renderer.dim("  using todowrite, then executes after approval."));
            System.out.println();
            System.out.println("  Usage: " + renderer.cyan("/plan on") + "  or  " + renderer.cyan("/plan off"));
            return;
        }

        String normalizedArg = arg.toLowerCase().trim();
        switch (normalizedArg) {
            case "on":
            case "enable":
            case "true":
                agenticLoop.setPlanningMode(true);
                System.out.println(renderer.green("  ✓ Planning mode enabled"));
                System.out.println(renderer.dim("    Next message will go through plan → approve → execute flow."));
                chatHistory.logSystem("Planning mode enabled");
                break;
            case "off":
            case "disable":
            case "false":
                agenticLoop.setPlanningMode(false);
                System.out.println(renderer.yellow("  ○ Planning mode disabled"));
                System.out.println(renderer.dim("    Messages will go directly to the agentic loop."));
                chatHistory.logSystem("Planning mode disabled");
                break;
            default:
                System.out.println("Usage: " + renderer.cyan("/plan on") + " | " + renderer.cyan("/plan off"));
                break;
        }
    }

    private void invokeTool(String rest) {
        if (rest.isBlank()) {
            System.out.println("Usage: /tool <tool_name> [json_arguments]");
            System.out.println("Example: /tool get_document_count");
            System.out.println("Example: /tool rag_query {\"query\":\"what is kompile?\",\"maxResults\":3}");
            return;
        }

        String[] parts = rest.split("\\s+", 2);
        String toolName = parts[0];
        String argsJson = parts.length > 1 ? parts[1] : null;

        try {
            String result = mcpClient.callTool(toolName, argsJson);
            try {
                JsonNode json = objectMapper.readTree(result);
                System.out.println(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(json));
            } catch (Exception e) {
                System.out.println(result);
            }
        } catch (Exception e) {
            System.err.println("Error invoking tool '" + toolName + "': " + e.getMessage());
        }
    }

    private void printStatus() {
        LinkedHashMap<String, String> statusMap = new LinkedHashMap<>();

        if (localMode) {
            statusMap.put("Mode", renderer.cyan("local (direct LLM)"));
            if (chatConfig != null) {
                statusMap.put("Provider", chatConfig.getProvider());
                statusMap.put("Model", chatConfig.getModel());
                String model = chatConfig.getModel();
                boolean vision = ModelContextWindows.supportsVision(model);
                int ctx = ModelContextWindows.getContextWindow(model);
                statusMap.put("Vision", vision ? renderer.green("supported") : renderer.dim("not supported"));
                statusMap.put("Context window", String.format("%,d tokens", ctx));
            }
            statusMap.put("Session", sessionId);
            statusMap.put("Local agent", renderer.cyan(localAgentName));
            if (!pendingAttachments.isEmpty()) {
                statusMap.put("Attachments", renderer.yellow(pendingAttachments.size() + " pending"));
            }
            statusMap.put("Transcript", chatHistory.getTranscriptFile().toString());
            statusMap.put("Working dir", System.getProperty("user.dir"));
            statusMap.put("Local tools", toolRegistry.ids().size() + " available");
        } else {
            boolean connected = mcpClient.isConnected();
            statusMap.put("Mode", renderer.green("server"));
            statusMap.put("Connection", connected ? renderer.green("● connected") : renderer.red("● disconnected"));
            statusMap.put("Server", baseUrl);
            statusMap.put("Session", sessionId);
            statusMap.put("Server agent", renderer.cyan(agentName));
            statusMap.put("Local agent", renderer.cyan(localAgentName));
            statusMap.put("RAG", ragEnabled ? renderer.green("enabled") : renderer.dim("disabled"));
            statusMap.put("Transcript", chatHistory.getTranscriptFile().toString());
            statusMap.put("Working dir", System.getProperty("user.dir"));
            if (cachedTools != null) {
                statusMap.put("MCP tools", cachedTools.size() + " available");
            }
            statusMap.put("Local tools", toolRegistry.ids().size() + " available");
        }

        System.out.println(ascii.panel("Status", ascii.keyValueList(statusMap), AsciiRenderer.ROUNDED, "blue"));
        System.out.println();
        if (chatMemory != null) {
            System.out.println(chatMemory.getStatus());
        }
    }

    private void showHistory() {
        try {
            ObjectNode args = objectMapper.createObjectNode();
            args.put("sessionId", sessionId);
            args.put("lastN", 20);
            String rawResult = mcpClient.callTool("get_chat_history", args);

            try {
                JsonNode json = objectMapper.readTree(rawResult);
                JsonNode messages = json.path("messages");
                int total = json.path("totalMessages").asInt(0);

                if (!messages.isArray() || messages.isEmpty()) {
                    System.out.println("No messages in server session.");
                    System.out.println("(Use /transcript to view the local conversation log)");
                    return;
                }

                System.out.println("Server history (" + messages.size() + " of " + total + " messages):");
                System.out.println("---");
                for (JsonNode msg : messages) {
                    String role = msg.path("role").asText("unknown");
                    String content = msg.path("content").asText("");
                    String label = role.toUpperCase().contains("USER") ? "You" : "Assistant";
                    System.out.println(label + ": " + content);
                    System.out.println("---");
                }
            } catch (Exception e) {
                System.out.println(rawResult);
            }
        } catch (Exception e) {
            System.err.println("Error fetching history: " + e.getMessage());
        }
    }

    private void showTranscript() {
        try {
            String transcript = chatHistory.readTranscript();
            if (transcript == null || transcript.isBlank()) {
                System.out.println("Transcript is empty.");
            } else {
                System.out.println(transcript);
            }
        } catch (Exception e) {
            System.err.println("Error reading transcript: " + e.getMessage());
        }
    }

    private void listConversations() {
        List<ChatHistory.ConversationSummary> conversations = ChatHistory.listConversations();
        if (conversations.isEmpty()) {
            System.out.println("No saved conversations.");
            return;
        }

        List<String> headers = List.of("Session", "Started", "Agent", "Title");
        List<List<String>> rows = new ArrayList<>();
        for (ChatHistory.ConversationSummary c : conversations) {
            String sid = c.sessionId().equals(sessionId)
                    ? renderer.green("● " + c.sessionId()) : c.sessionId();
            String title = c.title().isEmpty() ? renderer.dim("(empty)") : c.title();
            rows.add(List.of(sid, c.started(), c.agent(), title));
        }

        System.out.println(ascii.sectionHeader("Saved Conversations"));
        System.out.println(ascii.table(headers, rows));
        System.out.println();
        System.out.println(renderer.dim("  Resume: kompile chat --resume <session-id>"));
    }

    private void clearSession() {
        try {
            ObjectNode args = objectMapper.createObjectNode();
            args.put("sessionId", sessionId);
            mcpClient.callTool("clear_chat_session", args);
            chatHistory.logSystem("Session cleared.");
            System.out.println("Chat session cleared.");
        } catch (Exception e) {
            System.err.println("Error clearing session: " + e.getMessage());
        }
    }

    /**
     * Handle the /compact slash command. Runs an LLM-driven summarization of
     * the current conversation and replaces the in-memory history with the
     * summary, freeing context while preserving what the model needs to
     * continue the work. Optionally accepts a focus instruction.
     */
    private void handleCompact(String focusInstruction) {
        if (!agenticLoop.supportsForceCompact()) {
            System.out.println(renderer.yellow("  /compact requires local mode."));
            System.out.println(renderer.dim("  In server mode, use /clear to reset the server session, "
                    + "or run kompile chat in local mode for LLM-based compaction."));
            return;
        }

        int tokensBefore = agenticLoop.estimateConversationTokens();
        int entriesBefore = agenticLoop.conversationEntryCount();
        if (entriesBefore == 0) {
            System.out.println(renderer.dim("  Nothing to compact — conversation is empty."));
            return;
        }

        System.out.println();
        System.out.println(renderer.cyan("  ─── compacting context ───"));
        if (focusInstruction != null && !focusInstruction.isBlank()) {
            System.out.println(renderer.dim("  focus: " + focusInstruction.trim()));
        }
        System.out.println(renderer.dim("  before: " + entriesBefore + " entries, ~"
                + tokensBefore + " tokens. Summarizing…"));
        System.out.println();

        AgenticChatLoop.ForceCompactResult result;
        try {
            result = agenticLoop.forceCompact(focusInstruction);
        } catch (Exception e) {
            System.out.println(renderer.red("  /compact failed: " + e.getMessage()));
            return;
        }

        System.out.println();
        switch (result.getStatus()) {
            case OK:
                int after = result.getTokensAfter();
                int before = result.getTokensBefore();
                double ratio = before > 0 ? (1.0 - ((double) after / before)) * 100.0 : 0.0;
                System.out.println(renderer.renderCompactionNotice(before, after));
                System.out.println(renderer.dim(String.format(
                        "  Compaction complete — saved %.1f%% (%d → %d tokens), preserved %d recent turn(s).",
                        ratio, before, after, result.getPreservedTurns())));
                chatHistory.logSystem("Context compacted: " + before + " → " + after + " tokens.");
                break;
            case NOOP:
                System.out.println(renderer.dim("  " + result.getMessage()));
                break;
            case UNSUPPORTED:
                System.out.println(renderer.yellow("  " + result.getMessage()));
                break;
            case FAILED:
                System.out.println(renderer.red("  " + result.getMessage()));
                break;
        }
    }

    private void toggleRag(String arg) {
        String trimmed = arg.trim();
        if ("on".equalsIgnoreCase(trimmed)) {
            ragEnabled = true;
            updateSessionRag(true);
            chatHistory.logSystem("RAG enabled.");
            System.out.println("RAG enabled.");
        } else if ("off".equalsIgnoreCase(trimmed)) {
            ragEnabled = false;
            updateSessionRag(false);
            chatHistory.logSystem("RAG disabled.");
            System.out.println("RAG disabled.");
        } else {
            System.out.println("RAG is currently " + (ragEnabled ? "enabled" : "disabled") + ".");
            System.out.println("Usage: /rag on|off");
        }
    }

    private void updateSessionRag(boolean enabled) {
        try {
            ObjectNode args = objectMapper.createObjectNode();
            args.put("sessionId", sessionId);
            args.put("agentName", agentName);
            args.put("enableRag", enabled);
            args.put("semanticK", 5);
            args.put("keywordK", 5);
            args.put("similarityThreshold", 0.5);
            args.put("systemPrompt", "");
            mcpClient.callTool("update_session_config", args);
        } catch (Exception e) {
            // Best effort
        }
    }

    private void listAgents() {
        try {
            String rawResult = mcpClient.callTool("list_agents", (JsonNode) null);
            try {
                JsonNode json = objectMapper.readTree(rawResult);
                JsonNode agents = json.path("agents");
                if (!agents.isArray() || agents.isEmpty()) {
                    System.out.println("No server agents available.");
                    return;
                }
                System.out.println("Available server agents:");
                for (JsonNode agent : agents) {
                    String name = agent.path("name").asText();
                    boolean available = agent.path("available").asBoolean(false);
                    boolean isDefault = agent.path("isDefault").asBoolean(false);
                    String status = available ? "available" : "unavailable";
                    String marker = isDefault ? " (default)" : "";
                    String active = name.equals(agentName) ? " *" : "";
                    System.out.printf("  %-20s [%s]%s%s%n", name, status, marker, active);
                }
            } catch (Exception e) {
                System.out.println(rawResult);
            }
        } catch (Exception e) {
            System.err.println("Error listing agents: " + e.getMessage());
        }
    }

    private void switchAgent(String name) {
        if (name.isBlank()) {
            System.out.println("Current server agent: " + agentName);
            System.out.println("Usage: /agent <name> (see /agents for available agents)");
            return;
        }

        String newAgent = name.trim();
        try {
            ObjectNode args = objectMapper.createObjectNode();
            args.put("sessionId", sessionId);
            args.put("agentName", newAgent);
            args.put("enableRag", ragEnabled);
            args.put("semanticK", 5);
            args.put("keywordK", 5);
            args.put("similarityThreshold", 0.5);
            args.put("systemPrompt", "");
            String rawResult = mcpClient.callTool("update_session_config", args);

            JsonNode json = objectMapper.readTree(rawResult);
            if ("error".equals(json.path("status").asText())) {
                System.err.println("Error: " + json.path("error").asText());
                return;
            }

            agentName = newAgent;
            chatHistory.logSystem("Switched to server agent: " + agentName);
            System.out.println("Switched to server agent: " + agentName);
        } catch (Exception e) {
            System.err.println("Error switching agent: " + e.getMessage());
        }
    }

    private void handleConfig(String rest) {
        if (rest.isBlank()) {
            showConfig();
            return;
        }

        String[] parts = rest.trim().split("\\s+", 2);
        if (parts.length < 2) {
            System.out.println("Usage: /config <key> <value>");
            System.out.println("Keys: semanticK, keywordK, similarityThreshold, systemPrompt");
            return;
        }

        String key = parts[0];
        String value = parts[1];

        try {
            ObjectNode args = objectMapper.createObjectNode();
            args.put("sessionId", sessionId);
            args.put("agentName", agentName);
            args.put("enableRag", ragEnabled);
            args.put("semanticK", 5);
            args.put("keywordK", 5);
            args.put("similarityThreshold", 0.5);
            args.put("systemPrompt", "");

            switch (key.toLowerCase()) {
                case "semantick":
                    args.put("semanticK", Integer.parseInt(value));
                    break;
                case "keywordk":
                    args.put("keywordK", Integer.parseInt(value));
                    break;
                case "similaritythreshold":
                    args.put("similarityThreshold", Double.parseDouble(value));
                    break;
                case "systemprompt":
                    args.put("systemPrompt", value);
                    break;
                default:
                    System.out.println("Unknown config key: " + key);
                    System.out.println("Keys: semanticK, keywordK, similarityThreshold, systemPrompt");
                    return;
            }

            mcpClient.callTool("update_session_config", args);
            chatHistory.logSystem("Config updated: " + key + " = " + value);
            System.out.println("Updated " + key + " = " + value);
        } catch (NumberFormatException e) {
            System.err.println("Invalid value for " + key + ": " + value);
        } catch (Exception e) {
            System.err.println("Error updating config: " + e.getMessage());
        }
    }

    private void showConfig() {
        try {
            ObjectNode args = objectMapper.createObjectNode();
            args.put("sessionId", sessionId);
            String rawResult = mcpClient.callTool("get_session_config", args);

            try {
                JsonNode json = objectMapper.readTree(rawResult);
                JsonNode config = json.path("configuration");
                if (config.isMissingNode()) {
                    System.out.println(rawResult);
                    return;
                }

                System.out.println("Session configuration:");
                System.out.println("  Session ID:           " + config.path("sessionId").asText(""));
                System.out.println("  Server agent:         " + config.path("agentName").asText(""));
                System.out.println("  Local agent:          " + localAgentName);
                System.out.println("  RAG enabled:          " + config.path("enableRag").asBoolean(false));
                System.out.println("  Semantic K:           " + config.path("semanticK").asInt(0));
                System.out.println("  Keyword K:            " + config.path("keywordK").asInt(0));
                System.out.println("  Similarity threshold: " + config.path("similarityThreshold").asDouble(0));
                System.out.println("  Keyword search:       " + config.path("enableKeywordSearch").asBoolean(false));
                System.out.println("  Semantic search:      " + config.path("enableSemanticSearch").asBoolean(false));
                System.out.println("  Max history:          " + config.path("maxHistoryMessages").asInt(0));
                String prompt = config.path("systemPrompt").asText("");
                if (!prompt.isEmpty()) {
                    System.out.println("  System prompt:        " + truncate(prompt, 60));
                }

                int convSize = json.path("conversationSize").asInt(-1);
                if (convSize >= 0) {
                    System.out.println("  Conversation size:    " + convSize + " messages");
                }
            } catch (Exception e) {
                System.out.println(rawResult);
            }
        } catch (Exception e) {
            System.err.println("Error fetching config: " + e.getMessage());
        }
    }

    private void listSessions() {
        try {
            String rawResult = mcpClient.callTool("list_chat_sessions", (JsonNode) null);
            try {
                JsonNode json = objectMapper.readTree(rawResult);
                JsonNode sessions = json.path("sessions");
                int count = json.path("sessionCount").asInt(0);

                if (!sessions.isArray() || sessions.isEmpty()) {
                    System.out.println("No active server chat sessions.");
                    return;
                }

                System.out.println("Active server sessions (" + count + "):");
                for (JsonNode session : sessions) {
                    String sid = session.path("sessionId").asText("");
                    String agent = session.path("agentName").asText("none");
                    boolean rag = session.path("enableRag").asBoolean(false);
                    int msgCount = session.path("messageCount").asInt(0);
                    String active = sid.equals(sessionId) ? " *" : "";
                    System.out.printf("  %-30s agent=%-10s rag=%-5s msgs=%d%s%n",
                            sid, agent, rag, msgCount, active);
                }
            } catch (Exception e) {
                System.out.println(rawResult);
            }
        } catch (Exception e) {
            System.err.println("Error listing sessions: " + e.getMessage());
        }
    }

    private void handleMemory(String rest) {
        if (chatMemory == null) return;

        String trimmed = rest.trim().toLowerCase();

        if (trimmed.isEmpty()) {
            System.out.println(chatMemory.getStatus());
            return;
        }

        switch (trimmed) {
            case "on":
                chatMemory.setEnabled(true);
                chatHistory.logSystem("Memory enabled.");
                System.out.println("Memory enabled.");
                break;

            case "off":
                chatMemory.setEnabled(false);
                chatHistory.logSystem("Memory disabled.");
                System.out.println("Memory disabled.");
                break;

            case "persistent on":
                chatMemory.setPersistentMemoryEnabled(true);
                chatMemory.reloadPersistentMemory();
                chatHistory.logSystem("Persistent memory (MEMORY.md) enabled.");
                System.out.println("Persistent memory enabled. MEMORY.md files will be loaded.");
                break;

            case "persistent off":
                chatMemory.setPersistentMemoryEnabled(false);
                chatHistory.logSystem("Persistent memory (MEMORY.md) disabled.");
                System.out.println("Persistent memory disabled.");
                break;

            case "transcripts on":
                chatMemory.setTranscriptSearchEnabled(true);
                chatHistory.logSystem("Transcript memory search enabled.");
                System.out.println("Transcript search enabled.");
                break;

            case "transcripts off":
                chatMemory.setTranscriptSearchEnabled(false);
                chatHistory.logSystem("Transcript memory search disabled.");
                System.out.println("Transcript search disabled.");
                break;

            case "rag on":
                chatMemory.setRagSearchEnabled(true);
                chatHistory.logSystem("RAG memory search enabled.");
                System.out.println("RAG memory search enabled.");
                break;

            case "rag off":
                chatMemory.setRagSearchEnabled(false);
                chatHistory.logSystem("RAG memory search disabled.");
                System.out.println("RAG memory search disabled.");
                break;

            case "reload":
                chatMemory.reloadPersistentMemory();
                System.out.println("Persistent memory reloaded from MEMORY.md files.");
                break;

            case "show":
                String content = chatMemory.getPersistentMemoryContent();
                if (content != null && !content.isBlank()) {
                    System.out.println(content);
                } else {
                    System.out.println("No MEMORY.md files found.");
                    System.out.println("Create one at .kompile/memory/MEMORY.md (project) or ~/.kompile/memory/MEMORY.md (global).");
                }
                break;

            default:
                if (trimmed.startsWith("search ")) {
                    String query = rest.trim().substring(7).trim();
                    if (query.isEmpty()) {
                        System.out.println("Usage: /memory search <query>");
                        return;
                    }
                    System.out.println(chatMemory.search(query));
                } else {
                    System.out.println("Usage:");
                    System.out.println("  /memory                 Show memory status");
                    System.out.println("  /memory on|off          Toggle all memory");
                    System.out.println("  /memory persistent on|off  Toggle MEMORY.md loading");
                    System.out.println("  /memory transcripts on|off Toggle transcript search");
                    System.out.println("  /memory rag on|off      Toggle RAG search");
                    System.out.println("  /memory show            Show loaded MEMORY.md content");
                    System.out.println("  /memory reload          Reload MEMORY.md from disk");
                    System.out.println("  /memory search <query>  Search all memory sources");
                }
                break;
        }
    }

    private void handleRecall(String rest) {
        if (chatMemory == null) return;

        if (rest.isBlank()) {
            System.out.println("Usage: /recall <query>");
            System.out.println("Search across MEMORY.md, previous conversations, and RAG documents.");
            return;
        }

        System.out.println(chatMemory.search(rest.trim()));
    }

    private static String truncate(String s, int maxLen) {
        if (s == null) return "";
        return s.length() <= maxLen ? s : s.substring(0, maxLen - 3) + "...";
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

    /**
     * Prints the top border line above the input area.
     */
    private void printTopBorder(int termWidth) {
        int borderWidth = Math.max(20, Math.min(termWidth, 200));
        System.out.println(renderer.dim("─".repeat(borderWidth)));
    }

    /**
     * Builds the prompt string with queue indicator and notifications.
     */
    private String buildPrompt() {
        return buildPrompt(80);
    }

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
    private void printGeneratingIndicator() {
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
    private void stopGeneratingSpinner() {
        TerminalRenderer.SpinnerHandle spinner = generatingSpinner;
        if (spinner != null) {
            spinner.stop();
            generatingSpinner = null;
        }
    }

    // ========================================================================
    // Message Queue Management
    // ========================================================================

    /**
     * Adds a message to the queue.
     *
     * @param content the message content
     */
    private void enqueueMessage(String content) {
        if (content.isBlank()) {
            System.out.println("Usage: /queue <message>");
            System.out.println("Adds a message to the queue to be sent later.");
            return;
        }

        MessageQueue.QueuedMessage msg = messageQueue.enqueue(content);
        System.out.println(renderer.green("Message queued [") + msg.getId() + renderer.green("]"));
        System.out.println(renderer.dim("  Use /queues to view, /queue-send to send now, /queue-remove <id> to cancel"));
        System.out.println();
    }

    /**
     * Lists all queued messages.
     */
    private void listQueuedMessages() {
        if (messageQueue.isEmpty()) {
            System.out.println(renderer.cyan("Queue is empty"));
            return;
        }

        StringBuilder body = new StringBuilder();
        body.append(renderer.bold(renderer.cyan("Queued Messages"))).append("\n\n");

        List<MessageQueue.QueuedMessage> messages = messageQueue.getAll();
        for (int i = 0; i < messages.size(); i++) {
            MessageQueue.QueuedMessage msg = messages.get(i);
            body.append(renderer.bold(String.valueOf(i + 1)))
                .append(". [")
                .append(renderer.cyan(msg.getId()))
                .append("] ")
                .append(truncate(msg.getContent(), 70))
                .append("\n");
        }

        body.append("\n").append(renderer.dim("Total: ")).append(messages.size()).append(" message(s)");
        System.out.println(ascii.panel("Message Queue", body.toString(), AsciiRenderer.ROUNDED, "cyan"));
        System.out.println();
        System.out.println(renderer.dim("Commands:"));
        System.out.println(renderer.dim("  /queue-send [id]     Send the next message (or specific ID)"));
        System.out.println(renderer.dim("  /queue-send-all      Send all queued messages"));
        System.out.println(renderer.dim("  /queue-remove <id>   Remove a message from the queue"));
        System.out.println(renderer.dim("  /queue-clear         Clear all queued messages"));
        System.out.println();
    }

    /**
     * Sends the next queued message immediately.
     */
    private void sendNextQueuedMessage() {
        MessageQueue.QueuedMessage msg = messageQueue.peek();
        if (msg == null) {
            System.out.println(renderer.yellow("Queue is empty"));
            return;
        }

        System.out.println(renderer.cyan("Sending queued message [") + msg.getId() + renderer.cyan("]"));
        messageQueue.dequeue();
        handleChatMessage(msg.getContent());
    }

    /**
     * Sends a specific queued message by ID.
     *
     * @param id the message ID
     */
    private void sendQueuedMessageById(String id) {
        if (id.isBlank()) {
            System.out.println("Usage: /queue-send <id>");
            return;
        }

        MessageQueue.QueuedMessage msg = messageQueue.get(id);
        if (msg == null) {
            System.out.println(renderer.red("Message not found: ") + id);
            return;
        }

        System.out.println(renderer.cyan("Sending queued message [") + id + renderer.cyan("]"));
        messageQueue.remove(id);
        handleChatMessage(msg.getContent());
    }

    /**
     * Sends all queued messages in order.
     */
    private void sendAllQueuedMessages() {
        if (messageQueue.isEmpty()) {
            System.out.println(renderer.yellow("Queue is empty"));
            return;
        }

        int total = messageQueue.size();
        backgroundTaskManager.startQueueChain(total);
        System.out.println(renderer.cyan("Sending all " + total + " queued messages..."));
        System.out.println();

        int count = 0;
        while (!messageQueue.isEmpty()) {
            count++;
            backgroundTaskManager.advanceQueueChain();
            MessageQueue.QueuedMessage msg = messageQueue.dequeue();
            System.out.println(renderer.dim("→ [" + count + "/" + total + "] Sending: ") + truncate(msg.getContent(), 55));
            handleChatMessage(msg.getContent());
        }

        backgroundTaskManager.endQueueChain();
        System.out.println(renderer.green("✓ All " + total + " queued messages sent"));
    }

    /**
     * Removes a message from the queue by ID.
     *
     * @param id the message ID
     */
    private void removeQueuedMessage(String id) {
        if (id.isBlank()) {
            System.out.println("Usage: /queue-remove <id>");
            return;
        }

        if (messageQueue.remove(id)) {
            System.out.println(renderer.green("Removed message [") + id + renderer.green("]"));
        } else {
            System.out.println(renderer.red("Message not found: ") + id);
        }
    }

    /**
     * Clears all queued messages.
     */
    private void clearQueuedMessages() {
        messageQueue.clear();
        System.out.println(renderer.green("Queue cleared"));
    }

    /**
     * Shows the queue status.
     */
    private void showQueueStatus() {
        System.out.println(messageQueue.getStatus());
    }

    // ========================================================================
    // Background Task Management
    // ========================================================================

    /**
     * Lists all background tasks with comprehensive status.
     */
    private void listBackgroundTasks() {
        StringBuilder body = new StringBuilder();

        // Active tasks section
        List<BackgroundTaskManager.BackgroundTask> active = backgroundTaskManager.getActiveTasks();
        if (!active.isEmpty()) {
            body.append(renderer.bold(renderer.cyan("Active"))).append("\n");
            for (BackgroundTaskManager.BackgroundTask task : active) {
                body.append("  ").append(task.getStatusIcon())
                    .append(" [").append(renderer.cyan(task.getId())).append("] ")
                    .append(task.getDescription())
                    .append(renderer.dim(" (" + task.getElapsedTime() + ")"))
                    .append("\n");
            }
        }

        // Completed/failed tasks section
        List<BackgroundTaskManager.BackgroundTask> completed = backgroundTaskManager.getCompletedTasks();
        if (!completed.isEmpty()) {
            if (!active.isEmpty()) body.append("\n");
            body.append(renderer.bold(renderer.cyan("Recent"))).append("\n");
            int start = Math.max(0, completed.size() - 8);
            for (int i = start; i < completed.size(); i++) {
                BackgroundTaskManager.BackgroundTask task = completed.get(i);
                String icon = task.getStatus() == BackgroundTaskManager.BackgroundTask.BackgroundTaskStatus.COMPLETED
                        ? renderer.green(task.getStatusIcon()) : renderer.red(task.getStatusIcon());
                body.append("  ").append(icon)
                    .append(" [").append(renderer.dim(task.getId())).append("] ")
                    .append(task.getDescription())
                    .append(renderer.dim(" (" + task.getElapsedTime() + ")"));
                if (task.getError() != null) {
                    body.append(renderer.red(" — " + task.getError().getMessage()));
                }
                body.append("\n");
                // Output preview for completed tasks
                if (task.getStatus() == BackgroundTaskManager.BackgroundTask.BackgroundTaskStatus.COMPLETED
                        && task.getOutput() != null && !task.getOutput().isEmpty()) {
                    String preview = task.getOutput().replaceAll("\\s+", " ").trim();
                    if (preview.length() > 70) preview = preview.substring(0, 67) + "...";
                    body.append(renderer.dim("       " + preview)).append("\n");
                }
            }
        }

        if (active.isEmpty() && completed.isEmpty()) {
            body.append(renderer.dim("  No background tasks")).append("\n");
        }

        // Queue section
        body.append("\n");
        if (!messageQueue.isEmpty()) {
            body.append(renderer.bold(renderer.cyan("Queue"))).append(renderer.dim(" (" + messageQueue.size() + " pending)")).append("\n");
            List<MessageQueue.QueuedMessage> messages = messageQueue.getAll();
            for (int i = 0; i < Math.min(messages.size(), 5); i++) {
                MessageQueue.QueuedMessage msg = messages.get(i);
                String prefix = i == 0 ? renderer.yellow("  → ") : renderer.dim("  " + (i + 1) + ". ");
                body.append(prefix).append(truncate(msg.getContent(), 60)).append("\n");
            }
            if (messages.size() > 5) {
                body.append(renderer.dim("  ... and " + (messages.size() - 5) + " more")).append("\n");
            }
            body.append("\n");
            if (autoDequeueEnabled) {
                body.append(renderer.green("  ✓ Auto-dequeue ON")).append(renderer.dim(" — messages send automatically")).append("\n");
            } else {
                body.append(renderer.yellow("  ○ Auto-dequeue OFF")).append(renderer.dim(" — use /queue-send to send manually")).append("\n");
            }
        } else {
            body.append(renderer.dim("  Queue empty")).append("\n");
        }

        // Queue chain progress
        if (backgroundTaskManager.isInQueueChain()) {
            body.append(renderer.dim("  Processing " + backgroundTaskManager.getQueueChainCurrent()
                    + "/" + backgroundTaskManager.getQueueChainTotal())).append("\n");
        }

        System.out.println(ascii.panel("Jobs & Queue", body.toString(), AsciiRenderer.ROUNDED, "cyan"));
        System.out.println();
        System.out.println(renderer.dim("  Escape              Cancel in-progress operation"));
        System.out.println(renderer.dim("  Ctrl+B              Background current task"));
        System.out.println(renderer.dim("  Ctrl+X P            Toggle planning mode"));
        System.out.println(renderer.dim("  Ctrl+X T            Show todos"));
        System.out.println(renderer.dim("  Ctrl+X A            Cycle agent"));
        System.out.println(renderer.dim("  /jobs-remove <id>   Remove a completed task"));
        System.out.println(renderer.dim("  /jobs-clear         Clear all completed tasks"));
        System.out.println(renderer.dim("  /auto-dequeue       Toggle auto-send queued messages"));
        System.out.println();
    }

    /**
     * Removes a background task by ID.
     *
     * @param id the task ID
     */
    private void removeBackgroundTask(String id) {
        if (id.isBlank()) {
            System.out.println("Usage: /jobs-remove <id>");
            return;
        }

        if (backgroundTaskManager.removeTask(id)) {
            System.out.println(renderer.green("Removed task [") + id + renderer.green("]"));
        } else {
            System.out.println(renderer.red("Task not found or still running: ") + id);
        }
    }

    /**
     * Clears all completed background tasks.
     */
    private void clearCompletedBackgroundTasks() {
        backgroundTaskManager.clearCompletedTasks();
        System.out.println(renderer.green("Cleared completed tasks"));
    }

    // ========================================================================
    // Process Management (below-bar detail views)
    // ========================================================================

    /**
     * Shows the detailed process management panel.
     */
    private void showProcessPanel() {
        String body = statusBar.renderProcessPanel();
        System.out.println(ascii.panel("Processes & Subagents", body, AsciiRenderer.ROUNDED, "cyan"));
        System.out.println();
        System.out.println(renderer.dim("  /process-kill <id>     Kill a running process"));
        System.out.println(renderer.dim("  /process-output <id>   View process output (last 30 lines)"));
        System.out.println(renderer.dim("  /process-status <id>   Show process or watcher status"));
        System.out.println(renderer.dim("  /jobs                  View LLM background tasks & queue"));
        System.out.println(renderer.dim("  /statusbar             Toggle the status bar on/off"));
        System.out.println();
    }

    /**
     * Kill a running process by ID.
     */
    private void killProcess(String id) {
        if (id.isBlank()) {
            System.out.println("Usage: /process-kill <id>");
            return;
        }
        if (processManager.kill(id)) {
            System.out.println(renderer.green("Killed process [") + id + renderer.green("]"));
        } else {
            System.out.println(renderer.red("Process not found or not running: ") + id);
        }
    }

    /**
     * Show the output of a process.
     */
    private void showProcessOutput(String id) {
        if (id.isBlank()) {
            System.out.println("Usage: /process-output <id>");
            return;
        }
        String output = processManager.readOutput(id, 30);
        System.out.println(ascii.panel("Output: " + id, output, AsciiRenderer.ROUNDED, "cyan"));
        System.out.println();
    }

    /**
     * Show detailed status for a process or logical watcher.
     */
    private void showProcessStatus(String id) {
        if (id.isBlank()) {
            System.out.println("Usage: /process-status <id>");
            return;
        }
        BackgroundProcessManager.ProcessEntry entry = processManager.get(id);
        if (entry == null) {
            System.out.println(renderer.red("Process not found: ") + id);
            return;
        }

        StringBuilder body = new StringBuilder();
        body.append("Kind:        ").append(entry.getKind().label()).append("\n");
        body.append("State:       ").append(entry.getState()).append("\n");
        body.append("PID:         ").append(entry.getPid() > 0 ? String.valueOf(entry.getPid()) : "-").append("\n");
        body.append("Command:     ").append(entry.getCommand()).append("\n");
        body.append("Description: ").append(entry.getDescription()).append("\n");
        body.append("Started:     ").append(entry.getStartTime()).append("\n");
        if (entry.getEndTime() != null) {
            body.append("Ended:       ").append(entry.getEndTime()).append("\n");
        }
        body.append("Duration:    ").append(ProcessManagementTool.formatDuration(entry.getDuration())).append("\n");
        if (entry.getExitCode() != null) {
            body.append("Exit Code:   ").append(entry.getExitCode()).append("\n");
        }
        if (!entry.getMetadata().isEmpty()) {
            body.append("\nMetadata:\n");
            entry.getMetadata().forEach((key, value) ->
                    body.append("  ").append(key).append(": ").append(value).append("\n"));
        }
        System.out.println(ascii.panel("Process Status: " + id, body.toString(), AsciiRenderer.ROUNDED, "cyan"));
        System.out.println();
    }

    /**
     * Toggle the persistent status bar on/off.
     */
    private void toggleStatusBar() {
        boolean newState = !statusBar.isEnabled();
        statusBar.setEnabled(newState);
        if (newState) {
            System.out.println(renderer.green("  ✓ Status bar enabled"));
        } else {
            System.out.println(renderer.yellow("  ○ Status bar disabled"));
        }
    }

    /**
     * Expose the status bar for subagent tracking from external callers.
     */
    public StatusBar getStatusBar() {
        return statusBar;
    }

    /**
     * Toggles auto-dequeue on/off.
     */
    private void toggleAutoDequeue() {
        autoDequeueEnabled = !autoDequeueEnabled;
        if (autoDequeueEnabled) {
            System.out.println(renderer.green("✓ Auto-dequeue enabled"));
            System.out.println(renderer.dim("  Queued messages will send automatically when tasks complete"));
            if (!messageQueue.isEmpty()) {
                System.out.println(renderer.dim("  " + messageQueue.size() + " message(s) in queue"));
            }
        } else {
            System.out.println(renderer.yellow("○ Auto-dequeue disabled"));
            System.out.println(renderer.dim("  Use /queue-send to manually send queued messages"));
            if (!messageQueue.isEmpty()) {
                System.out.println(renderer.dim("  " + messageQueue.size() + " message(s) waiting in queue"));
            }
        }
        System.out.println();
    }
    
    /**
     * Completes the current task and auto-dequeues next message if available.
     * Tracks queue chain progress for "Processing 2/5" indicators.
     */
    private void completeTaskWithAutoDequeue() {
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
                System.out.println(renderer.dim("     → ") + truncate(nextMsg.getContent(), 60));
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
                handleChatMessage(nextMsg.getContent());
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

    // ========================================================================
    // Session Summary & Stats
    // ========================================================================

    /**
     * Prints comprehensive session summary on exit.
     * Inspired by Claude Code /cost, Codex CLI stats, and Aider session metrics.
     */
    private void printSessionSummary() {
        java.time.Duration duration = sessionMetrics.getSessionDuration();
        StringBuilder body = new StringBuilder();

        // Session info
        body.append(renderer.bold("Session")).append("\n");
        body.append("  ID:        ").append(sessionId).append("\n");
        body.append("  Duration:  ").append(sessionMetrics.formatDuration(duration)).append("\n");
        if (localMode && chatConfig != null) {
            body.append("  Provider:  ").append(chatConfig.getProvider()).append("/").append(chatConfig.getModel()).append("\n");
        }
        body.append("  Agent:     ").append(agentName);
        if (localMode) body.append(" (local)");
        body.append("\n");
        body.append("  RAG:       ").append(ragEnabled ? "enabled" : "disabled").append("\n");

        // Conversation
        body.append("\n").append(renderer.bold("Conversation")).append("\n");
        body.append("  Turns:     ").append(sessionMetrics.getUserTurns()).append(" user, ")
            .append(sessionMetrics.getAssistantTurns()).append(" assistant");
        if (sessionMetrics.getUserTurns() + sessionMetrics.getAssistantTurns() > 0) {
            body.append(" (").append(sessionMetrics.getTotalTurns()).append(" total)");
        }
        body.append("\n");

        if (sessionMetrics.getApiCalls() > 0) {
            body.append("  API calls: ").append(sessionMetrics.getApiCalls()).append("\n");
            body.append("  Avg time:  ").append(Math.round(sessionMetrics.getAvgResponseTimeMs())).append("ms per response\n");
        }

        // Tokens
        body.append("\n").append(renderer.bold("Tokens")).append("\n");
        if (sessionMetrics.hasActualTokenCounts()) {
            body.append("  Input:     ").append(formatNumber(sessionMetrics.getInputTokens())).append("\n");
            body.append("  Output:    ").append(formatNumber(sessionMetrics.getOutputTokens())).append("\n");
            body.append("  Total:     ").append(formatNumber(sessionMetrics.getTotalTokens())).append("\n");
            if (sessionMetrics.getCacheReadTokens() > 0) {
                body.append("  Cache hit: ").append(formatNumber(sessionMetrics.getCacheReadTokens())).append("\n");
            }
            if (sessionMetrics.getCacheCreationTokens() > 0) {
                body.append("  Cache new: ").append(formatNumber(sessionMetrics.getCacheCreationTokens())).append("\n");
            }
        } else {
            long estInput = sessionMetrics.getEstimatedInputTokens();
            long estOutput = sessionMetrics.getEstimatedOutputTokens();
            body.append("  ~Input:    ").append(formatNumber(estInput)).append(" (estimated)\n");
            body.append("  ~Output:   ").append(formatNumber(estOutput)).append(" (estimated)\n");
            body.append("  ~Total:    ").append(formatNumber(estInput + estOutput)).append(" (estimated)\n");
        }

        // Tools
        if (sessionMetrics.getTotalToolCalls() > 0) {
            body.append("\n").append(renderer.bold("Tools")).append("\n");
            body.append("  Total:     ").append(sessionMetrics.getTotalToolCalls()).append(" calls");
            if (sessionMetrics.getTotalToolErrors() > 0) {
                body.append(" (").append(sessionMetrics.getTotalToolErrors()).append(" errors)");
            }
            body.append("\n");

            // Top tools breakdown
            List<Map.Entry<String, Integer>> topTools = sessionMetrics.getTopTools(8);
            for (Map.Entry<String, Integer> entry : topTools) {
                body.append("  ").append(String.format("%-12s", entry.getKey()))
                    .append(" ").append(entry.getValue()).append("\n");
            }
        }

        // Agentic
        if (sessionMetrics.getAgenticSteps() > 0) {
            body.append("\n").append(renderer.bold("Agentic")).append("\n");
            body.append("  Steps:     ").append(sessionMetrics.getAgenticSteps()).append("\n");
            if (sessionMetrics.getCompactionEvents() > 0) {
                body.append("  Compacts:  ").append(sessionMetrics.getCompactionEvents()).append("\n");
            }
        }

        // RAG
        if (sessionMetrics.getRagQueries() > 0) {
            body.append("\n").append(renderer.bold("RAG")).append("\n");
            body.append("  Queries:   ").append(sessionMetrics.getRagQueries()).append("\n");
            body.append("  Docs:      ").append(sessionMetrics.getDocumentsRetrieved()).append(" retrieved\n");
        }

        // Queue
        if (sessionMetrics.getMessagesQueued() > 0 || sessionMetrics.getTasksBackgrounded() > 0) {
            body.append("\n").append(renderer.bold("Queue & Background")).append("\n");
            if (sessionMetrics.getMessagesQueued() > 0) {
                body.append("  Queued:    ").append(sessionMetrics.getMessagesQueued()).append(" messages\n");
            }
            if (sessionMetrics.getMessagesAutoDequeued() > 0) {
                body.append("  Auto-sent: ").append(sessionMetrics.getMessagesAutoDequeued()).append("\n");
            }
            if (sessionMetrics.getTasksBackgrounded() > 0) {
                body.append("  Bgnd:      ").append(sessionMetrics.getTasksBackgrounded()).append(" tasks\n");
            }
        }

        // Files
        body.append("\n").append(renderer.bold("Files")).append("\n");
        body.append("  Transcript: ").append(chatHistory.getTranscriptFile()).append("\n");
        body.append("  Metrics:    ").append(chatHistory.getTranscriptFile().resolveSibling(sessionId + ".metrics.json")).append("\n");
        body.append("  Resume:     kompile chat --resume ").append(sessionId).append("\n");

        System.out.println();
        System.out.println(ascii.panel("Session Summary", body.toString(), AsciiRenderer.ROUNDED, "cyan"));
        System.out.println();

        // Also log summary to transcript
        chatHistory.logSystem("Session ended — " + sessionMetrics.formatDuration(duration) +
                ", " + sessionMetrics.getTotalTurns() + " turns" +
                (sessionMetrics.hasActualTokenCounts() ?
                        ", " + formatNumber(sessionMetrics.getTotalTokens()) + " tokens" :
                        ", ~" + formatNumber(sessionMetrics.getEstimatedInputTokens() + sessionMetrics.getEstimatedOutputTokens()) + " est. tokens") +
                (sessionMetrics.getTotalToolCalls() > 0 ? ", " + sessionMetrics.getTotalToolCalls() + " tool calls" : ""));
    }

    private String formatNumber(long n) {
        if (n < 1000) return String.valueOf(n);
        return String.format("%,d", n);
    }

    // ── Main menu ─────────────────────────────────────────────────────────────

    /**
     * Show a quick menu to switch between modes from within chat.
     */
    private void showMainMenu() {
        System.out.println();
        System.out.println(ascii.panel("Kompile Main Menu",
                "  1. Chat        - Continue this conversation\n" +
                "  2. Passthrough - Launch external CLI agent (Claude, Codex, Qwen, etc.)\n" +
                "  3. Resume      - Browse & resume past conversations\n" +
                "  4. Setup       - Reconfigure LLM provider settings\n" +
                "  5. Back        - Return to chat",
                AsciiRenderer.ROUNDED, "cyan"));
        System.out.println();
        System.out.print("  Select [1-5]: ");
        System.out.flush();

        // Read single key
        try {
            InputStream in = System.in;
            int b = in.read();
            while (b != -1) {
                if (b == '\n' || b == '\r') break;
                if (b == '1') {
                    System.out.println("1");
                    System.out.println("  " + renderer.dim("Continuing chat session..."));
                    return;
                } else if (b == '2') {
                    System.out.println("2");
                    launchPassthroughMode("");
                    return;
                } else if (b == '3') {
                    System.out.println("3");
                    launchResumeTool("");
                    return;
                } else if (b == '4') {
                    System.out.println("4");
                    runSetup();
                    return;
                } else if (b == '5' || b == 'q' || b == 27) {
                    System.out.println(b == 5 ? "5" : "q");
                    return;
                }
                b = in.read();
            }
        } catch (Exception e) {
            System.err.println("Menu error: " + e.getMessage());
        }
    }

    // ========================================================================
    // Passthrough and Resume integration
    // ========================================================================

    /**
     * Launch passthrough mode from within the chat REPL.
     * Delegates to PassthroughCommand to launch an external CLI agent.
     */
    private void launchPassthroughMode(String agentArg) {
        try {
            String agent = agentArg != null && !agentArg.isBlank() ? agentArg : 
                          (chatConfig != null ? chatConfig.getPassthroughAgent() : "claude");
            
            System.out.println();
            System.out.println(renderer.dim("  Launching passthrough mode with agent: " + agent));
            System.out.println(renderer.dim("  The agent will take control of the terminal."));
            System.out.println(renderer.dim("  Return to chat when the agent session ends."));
            System.out.println();
            
            // Create and configure PassthroughCommand
            PassthroughCommand passthrough = new PassthroughCommand();
            passthrough.agent = agent;
            passthrough.workingDir = System.getProperty("user.dir");
            passthrough.skipPermissions = true;
            passthrough.injectTools = !localMode;
            if (!localMode && baseUrl != null) {
                passthrough.kompileUrl = baseUrl;
            }
            
            // Execute passthrough
            int exitCode = passthrough.call();

            // Clean up terminal state after passthrough returns
            System.out.print("\033[0m"); // reset colors
            System.out.print("\033[?25h"); // show cursor
            System.out.println();

            System.out.println();
            System.out.println(renderer.green("  ✓ Passthrough session ended (exit code: " + exitCode + ")"));
            System.out.println(renderer.dim("  Returned to chat session: " + sessionId));
            System.out.println();
            
            // Log to transcript
            chatHistory.logSystem("Passthrough session with " + agent + " ended (exit: " + exitCode + ")");
        } catch (Exception e) {
            System.err.println("Error launching passthrough: " + e.getMessage());
            chatHistory.logSystem("Failed to launch passthrough: " + e.getMessage());
        }
    }

    /**
     * Launch resume tool from within the chat REPL.
     * Delegates to ResumeTool for browsing and resuming conversations.
     */
    private void launchResumeTool(String args) {
        try {
            System.out.println();
            System.out.println(renderer.dim("  Launching resume tool..."));
            System.out.println(renderer.dim("  Browse, search, and resume past conversations."));
            System.out.println(renderer.dim("  Return to chat when done."));
            System.out.println();
            
            // Create and execute ResumeTool
            ResumeTool resumeTool = new ResumeTool();
            
            // Launch interactive browser (args are handled within the tool)
            resumeTool.runInteractiveBrowser();

            // Clean up terminal state after resume tool returns
            System.out.print("\033[0m"); // reset colors
            System.out.print("\033[?25h"); // show cursor
            System.out.println();

            System.out.println();
            System.out.println(renderer.green("  ✓ Resume session ended"));
            System.out.println(renderer.dim("  Returned to chat session: " + sessionId));
            System.out.println();
            
            // Log to transcript
            chatHistory.logSystem("Resume tool session ended");
        } catch (Exception e) {
            System.err.println("Error launching resume tool: " + e.getMessage());
            chatHistory.logSystem("Failed to launch resume tool: " + e.getMessage());
        }
    }

    /**
     * Handle mode switching from within the chat REPL.
     * Allows switching between standard and passthrough modes.
     */
    private void handleModeSwitch(String mode) {
        if (mode == null || mode.isBlank()) {
            String currentMode = localMode ? "local" :
                                (chatConfig != null && "passthrough".equals(chatConfig.getChatMode()) ? "passthrough" : "standard");
            if (agenticLoop.isPlanningMode()) {
                currentMode += " + plan";
            }
            System.out.println("Current mode: " + renderer.bold(currentMode));
            System.out.println("Usage: /mode <standard|passthrough|local|plan>");
            return;
        }

        String normalizedMode = mode.toLowerCase().trim();

        switch (normalizedMode) {
            case "passthrough":
                System.out.println("Switching to passthrough mode...");
                System.out.println("Use " + renderer.cyan("/passthrough") + " to launch an external agent.");
                chatHistory.logSystem("Mode switched to: passthrough");
                break;

            case "standard":
            case "normal":
                agenticLoop.setPlanningMode(false);
                System.out.println("Switching to standard mode...");
                System.out.println("Use " + renderer.cyan("/resume") + " to browse past conversations.");
                chatHistory.logSystem("Mode switched to: standard");
                break;

            case "plan":
            case "planning":
                agenticLoop.setPlanningMode(true);
                System.out.println(renderer.green("  ✓ Planning mode enabled"));
                System.out.println(renderer.dim("    Agent will plan before executing. Use /plan off to disable."));
                chatHistory.logSystem("Mode switched to: plan");
                break;

            case "local":
                if (!localMode) {
                    System.out.println(renderer.yellow("  Note: Already in server mode."));
                    System.out.println("  Local mode requires restarting the chat.");
                } else {
                    System.out.println("Already in local mode.");
                }
                break;

            default:
                System.out.println("Unknown mode: " + renderer.bold(mode));
                System.out.println("Available modes: standard, passthrough, local, plan");
                break;
        }
    }
}
