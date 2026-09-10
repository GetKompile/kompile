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

import ai.kompile.cli.common.mcp.McpSseClient;
import ai.kompile.cli.main.chat.agent.AgentConfig;
import ai.kompile.cli.main.chat.agent.AgentRunController;
import ai.kompile.cli.main.chat.crawl.CrawlRunStore;
import ai.kompile.cli.main.chat.agent.AgentRegistry;
import ai.kompile.cli.main.chat.agent.AgenticChatLoop;
import ai.kompile.cli.main.chat.config.ChatConfig;
import ai.kompile.cli.main.chat.config.DirectLlmClient;
import ai.kompile.cli.main.chat.config.SetupWizard;
import ai.kompile.core.llm.ModelContextWindows;
import ai.kompile.cli.main.chat.enforcer.EnforcerConfig;
import ai.kompile.cli.main.chat.enforcer.EnforcerSetupWizard;
import ai.kompile.cli.main.chat.harness.HarnessConfig;
import ai.kompile.cli.main.chat.permission.PermissionService;
import ai.kompile.cli.main.chat.render.AsciiRenderer;
import ai.kompile.cli.main.chat.render.CompactionProgressIndicator;
import ai.kompile.cli.main.chat.render.TerminalRenderer;
import ai.kompile.cli.main.chat.roles.RoleConfig;
import ai.kompile.cli.main.chat.roles.RoleManager;
import ai.kompile.cli.main.chat.roles.RoleWizard;
import ai.kompile.cli.main.chat.skill.SkillConfig;
import ai.kompile.cli.main.chat.skill.SkillRegistry;
import ai.kompile.cli.main.chat.tools.*;
import ai.kompile.cli.main.chat.workflow.WorkflowController;
import ai.kompile.cli.main.chat.workflow.WorkflowPolicy;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Routes slash commands entered in the chat REPL to their corresponding handler methods.
 * Extracts all /command handling logic from ChatRepl to keep it focused.
 */
public class ChatCommandRouter {

    private static final Set<String> JUDGE_CONTROL_COMMANDS = Set.of(
            "status", "show", "on", "enable", "resume", "off", "disable", "pause",
            "global", "workflow", "direction", "policy", "init", "setup", "config",
            "rules", "reload", "delete", "remove", "run", "start", "launch", "chat",
            "talk", "ask", "feedback", "guidance", "override", "bypass", "allow-next",
            "approve", "judgements", "history", "restart", "agent", "help", "usage");

    static JudgeCommand parseJudgeCommand(String args) {
        String normalized = args == null ? "" : args.trim();
        if (normalized.isBlank()) return new JudgeCommand("status", "");
        String[] command = normalized.split("\\s+", 2);
        String subcommand = command[0].toLowerCase(Locale.ROOT);
        String remainder = command.length > 1 ? command[1].trim() : "";
        if (!JUDGE_CONTROL_COMMANDS.contains(subcommand)) {
            return new JudgeCommand("chat", normalized);
        }
        if ("ask".equals(subcommand)) subcommand = "chat";
        return new JudgeCommand(subcommand, remainder);
    }

    record JudgeCommand(String subcommand, String arguments) { }

    // References to shared REPL state
    private final ChatRepl repl;
    private final ChatMessageHandler messageHandler;
    private final MessageQueueManager queueManager;
    private final SessionLifecycleManager lifecycleManager;

    // Direct references to frequently-used REPL state
    private final McpSseClient mcpClient;
    private final ObjectMapper objectMapper;
    private final String sessionId;
    private final boolean localMode;
    private final ChatHistory chatHistory;
    private final ChatSessionMetrics sessionMetrics;
    private final TerminalRenderer renderer;
    private final AsciiRenderer ascii;
    private final AgentRegistry agentRegistry;
    private final SkillRegistry skillRegistry;
    private final RoleManager roleManager;
    private final ToolRegistry toolRegistry;
    private final PermissionService permissionService;
    private final AgenticChatLoop agenticLoop;
    private final BackgroundTaskManager backgroundTaskManager;
    private final BackgroundProcessManager processManager;
    private final ai.kompile.cli.main.chat.tui.StatusBar statusBar;
    private final List<ChatRepl.PendingAttachment> pendingAttachments;
    private final ReminderManager reminderManager;
    private String serverCustomSystemPrompt;

    // Mutable state that the router can modify via ChatRepl accessors
    // (these are updated by individual handlers and ChatRepl reads them back)

    public ChatCommandRouter(
            ChatRepl repl,
            ChatMessageHandler messageHandler,
            MessageQueueManager queueManager,
            SessionLifecycleManager lifecycleManager,
            McpSseClient mcpClient,
            ObjectMapper objectMapper,
            String sessionId,
            boolean localMode,
            ChatHistory chatHistory,
            ChatSessionMetrics sessionMetrics,
            TerminalRenderer renderer,
            AsciiRenderer ascii,
            AgentRegistry agentRegistry,
            SkillRegistry skillRegistry,
            RoleManager roleManager,
            ToolRegistry toolRegistry,
            PermissionService permissionService,
            AgenticChatLoop agenticLoop,
            BackgroundTaskManager backgroundTaskManager,
            BackgroundProcessManager processManager,
            ai.kompile.cli.main.chat.tui.StatusBar statusBar,
            List<ChatRepl.PendingAttachment> pendingAttachments,
            ReminderManager reminderManager) {
        this.repl = repl;
        this.messageHandler = messageHandler;
        this.queueManager = queueManager;
        this.lifecycleManager = lifecycleManager;
        this.mcpClient = mcpClient;
        this.objectMapper = objectMapper;
        this.sessionId = sessionId;
        this.localMode = localMode;
        this.chatHistory = chatHistory;
        this.sessionMetrics = sessionMetrics;
        this.renderer = renderer;
        this.ascii = ascii;
        this.agentRegistry = agentRegistry;
        this.skillRegistry = skillRegistry;
        this.roleManager = roleManager;
        this.toolRegistry = toolRegistry;
        this.permissionService = permissionService;
        this.agenticLoop = agenticLoop;
        this.backgroundTaskManager = backgroundTaskManager;
        this.processManager = processManager;
        this.statusBar = statusBar;
        this.pendingAttachments = pendingAttachments;
        this.reminderManager = reminderManager;
    }

    /**
     * Routes a slash command to the appropriate handler.
     *
     * @param input the full command string including the leading /
     * @return false if the current REPL should stop (exit or start a fresh conversation)
     */
    public boolean handleSlashCommand(String input) {
        String[] parts = input.split("\\s+", 2);
        String cmd = parts[0].toLowerCase();
        String rest = parts.length > 1 ? parts[1] : "";

        switch (cmd) {
            case "/quit":
            case "/exit":
                return false;

            case "/resources":
                System.out.println(repl.configureResources(rest));
                return true;

            case "/help":
                printHelp();
                return true;

            case "/auth":
                repl.handleAuthenticationCommand(rest);
                return true;

            case "/setup":
                runSetup();
                return true;

            case "/provider":
                if (localMode) {
                    repl.openModelProviderPicker();
                } else {
                    runSetup();
                }
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

            case "/dashboard":
                System.out.println(renderer.cyan("  "
                        + repl.handleDashboardCommand(rest.trim())));
                return true;

            case "/title":
                handleTitle(rest);
                return true;

            case "/history":
                if (localMode) {
                    showTranscript();
                } else {
                    showHistory();
                }
                return true;

            case "/clear":
                repl.requestNewConversation();
                return false;

            case "/restart":
            case "/reset":
                return restartCurrentSession(cmd, rest);

            case "/reset-all":
                return restartAllActiveSessions(rest);

            case "/compact":
                handleCompact(rest);
                return true;

            case "/auto-compact":
                handleAutoCompact(rest);
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
                    // Use the same asynchronous direct-chat dispatcher so input
                    // remains available for queued follow-up messages.
                    messageHandler.handleChatMessage(rest);
                } else {
                    messageHandler.streamAgentChat(rest);
                }
                return true;

            case "/agent-chat":
                if (localMode) {
                    messageHandler.handleChatMessage(rest);
                } else {
                    messageHandler.agenticChat(rest);
                }
                return true;

            case "/crawl":
                handleCrawlControl(rest);
                return true;

            case "/conversations":
                listConversations();
                return true;

            case "/transcript":
                showTranscript();
                return true;

            case "/copy":
                copyLatestResponse(rest);
                return true;

            case "/memory":
                handleMemory(rest);
                return true;

            case "/recall":
                handleRecall(rest);
                return true;

            case "/reminder":
                printReminderResult(ReminderManager.Scope.SESSION, rest);
                return true;

            case "/reminder-global":
                printReminderResult(ReminderManager.Scope.PROJECT, rest);
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
                queueManager.enqueueMessage(rest);
                return true;

            case "/queues":
                queueManager.listQueuedMessages();
                return true;

            case "/queue-send":
                if (rest.isBlank()) {
                    queueManager.sendNextQueuedMessage();
                } else {
                    queueManager.sendQueuedMessageById(rest.trim());
                }
                return true;

            case "/queue-send-all":
                queueManager.sendAllQueuedMessages();
                return true;

            case "/queue-remove":
                queueManager.removeQueuedMessage(rest.trim());
                return true;

            case "/queue-edit":
                queueManager.editQueuedMessage(rest);
                return true;

            case "/queue-move":
                queueManager.moveQueuedMessage(rest);
                return true;

            case "/queue-clear":
                queueManager.clearQueuedMessages();
                return true;

            case "/queue-status":
                queueManager.showQueueStatus();
                return true;

            case "/loop":
                handleLoop(LoopScope.SESSION, rest);
                return true;

            case "/loop-global":
                handleLoop(LoopScope.PROJECT, rest);
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
            case "/activity":
                if (!repl.handleProjectActivityCommand(rest.trim())) {
                    showProcessPanel();
                }
                return true;

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
                queueManager.toggleAutoDequeue();
                return true;

            case "/stats":
                lifecycleManager.printSessionSummary();
                return true;

            case "/passthrough":
                lifecycleManager.launchPassthroughMode(rest.trim());
                return true;

            case "/resume":
                lifecycleManager.launchResumeTool(rest.trim());
                return true;

            case "/resume-all":
                lifecycleManager.launchResumeAll(rest.trim());
                return true;

            case "/mode":
                lifecycleManager.handleModeSwitch(rest.trim());
                return true;

            case "/menu":
                lifecycleManager.showMainMenu();
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

            case "/fast":
                handleFastModeCommand(rest.trim());
                return true;

            case "/enforce":
            case "/enforcer":
                handleJudgeCommand(rest.trim());
                return true;

            case "/judge":
                handleJudgeCommand(rest.trim());
                return true;

            case "/judge-global":
                handleJudgeGlobal(rest.trim());
                return true;

            case "/direction":
                handleJudgeCommand("direction" + (rest.isBlank() ? "" : " " + rest.trim()));
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
                // Check if it's a skill invocation (e.g. /commit, /review).
                SkillRegistry.SkillInvocation invocation =
                        skillRegistry.resolveInvocation(input).orElse(null);
                if (invocation != null) {
                    executeSkill(invocation);
                    return true;
                }
                System.out.println("Unknown command: " + cmd + ". Type /help for available commands.");
                return true;
        }
    }

    // ========================================================================
    // Crawl run controls
    // ========================================================================

    private void handleCrawlControl(String args) {
        AgentRunController controller = repl.getRunController();
        if (controller == null) {
            System.out.println(renderer.dim("/crawl is available only in the production crawl profile."));
            return;
        }
        String op = args.isBlank() ? "status" : args.trim().split("\\s+")[0].toLowerCase(Locale.ROOT);
        CrawlRunStore store = repl.getCrawlRunStore();
        switch (op) {
            case "pause" -> { controller.pause(); if (store != null) store.event("paused", "operator"); }
            case "resume", "continue" -> { controller.resume(); if (store != null) store.event("resumed", "operator"); }
            case "step" -> { controller.stepOnce(); if (store != null) store.event("step_requested", "operator"); }
            case "approve", "allow" -> { controller.approveOnce(); if (store != null) store.event("approved", "next mutation"); }
            case "stop", "cancel" -> { controller.stop(); if (store != null) store.event("stopped", "operator"); }
            case "events" -> {
                if (store == null) { System.out.println(renderer.dim("No crawl run store is attached.")); return; }
                try { store.readEvents().forEach(System.out::println); }
                catch (Exception e) { System.out.println(renderer.yellow("Unable to read crawl events: " + e.getMessage())); }
                return;
            }
            case "help" -> {
                System.out.println("/crawl pause|resume|step|approve|stop|status|events");
                return;
            }
            case "status" -> { /* render below */ }
            default -> {
                System.out.println(renderer.yellow("Unknown /crawl control: " + op));
                System.out.println(renderer.dim("Use /crawl pause|resume|step|approve|stop|status|events"));
                return;
            }
        }
        AgentRunController.Snapshot snapshot = controller.snapshot();
        System.out.println(renderer.cyan("  Crawl run: " + snapshot.state())
                + renderer.dim(" mode=" + snapshot.mode()
                + " completed=" + snapshot.completedSteps()
                + " tools=" + snapshot.toolCalls()
                + " approval=" + snapshot.approvalPending()));
        if (store != null) store.checkpoint(controller, "operator_" + op);
    }

    private enum LoopScope {
        SESSION("/loop", "Session", "session"),
        PROJECT("/loop-global", "Project-global", "project-global");

        private final String command;
        private final String heading;
        private final String label;

        LoopScope(String command, String heading, String label) {
            this.command = command;
            this.heading = heading;
            this.label = label;
        }
    }

    private void handleLoop(LoopScope scope, String arguments) {
        String input = arguments == null ? "" : arguments.strip();
        if (input.isEmpty() || input.equalsIgnoreCase("list")
                || input.equalsIgnoreCase("status")) {
            listLoops(scope);
            return;
        }

        String[] operation = input.split("\\s+", 2);
        String command = operation[0].toLowerCase(Locale.ROOT);
        String rest = operation.length > 1 ? operation[1].strip() : "";
        switch (command) {
            case "add" -> addLoop(scope, rest);
            case "pause" -> updateLoop(scope, "pause", rest);
            case "resume" -> updateLoop(scope, "resume", rest);
            case "remove", "delete", "stop" -> updateLoop(scope, "remove", rest);
            case "run", "now" -> updateLoop(scope, "run", rest);
            case "clear" -> clearLoops(scope, rest);
            default -> addLoop(scope, input); // Claude-style shorthand: /loop 5m prompt
        }
    }

    private void addLoop(LoopScope scope, String arguments) {
        String schedule;
        String prompt;
        if (arguments.startsWith("cron ")) {
            String cronAndPrompt = arguments.substring(5).strip();
            int separator = cronAndPrompt.indexOf(" -- ");
            if (separator < 0) {
                printLoopUsage(scope);
                return;
            }
            schedule = cronAndPrompt.substring(0, separator).strip();
            prompt = cronAndPrompt.substring(separator + 4).strip();
        } else {
            String[] parts = arguments.split("\\s+", 2);
            if (parts.length < 2) {
                printLoopUsage(scope);
                return;
            }
            schedule = parts[0];
            prompt = parts[1].strip();
        }

        ScheduledLoopManager.ScheduledLoop loop =
                loopManager(scope).create(schedule, prompt);
        if (loop == null) {
            System.out.println(renderer.red("Invalid loop schedule or empty prompt."));
            printLoopUsage(scope);
            return;
        }
        System.out.println(renderer.green(
                        "✓ Scheduled " + scope.label + " loop [")
                + loop.getId() + renderer.green("] ") + loop.getFormattedInterval());
        System.out.println(renderer.dim("  " + prompt));
    }

    private void updateLoop(LoopScope scope, String operation, String id) {
        if (id == null || id.isBlank()) {
            printLoopUsage(scope);
            return;
        }
        ScheduledLoopManager loops = loopManager(scope);
        boolean changed = switch (operation) {
            case "pause" -> loops.pause(id);
            case "resume" -> loops.resume(id);
            case "remove" -> loops.remove(id);
            case "run" -> loops.runNow(id);
            default -> false;
        };
        if (changed) {
            String label = switch (operation) {
                case "pause" -> "paused";
                case "resume" -> "resumed";
                case "remove" -> "removed";
                case "run" -> "started";
                default -> operation;
            };
            System.out.println(renderer.green(
                    "✓ " + scope.heading + " loop " + label + ": ") + id);
        } else {
            System.out.println(renderer.red(
                    scope.heading + " loop not found or invalid state: ") + id);
        }
    }

    private void clearLoops(LoopScope scope, String arguments) {
        if (arguments != null && !arguments.isBlank()) {
            printLoopUsage(scope);
            return;
        }
        int cleared = loopManager(scope).clear();
        System.out.println(renderer.green("✓ Cleared " + cleared + " " + scope.label
                + " scheduled loop" + (cleared == 1 ? "." : "s.")));
    }

    private void listLoops(LoopScope scope) {
        List<ScheduledLoopManager.ScheduledLoop> loops = loopManager(scope).list();
        if (loops.isEmpty()) {
            System.out.println(renderer.dim("No " + scope.label + " scheduled loops."));
            printLoopUsage(scope);
            return;
        }
        System.out.println(ascii.sectionHeader(scope.heading + " Scheduled Loops"));
        for (ScheduledLoopManager.ScheduledLoop loop : loops) {
            System.out.println("  " + ScheduledLoopManager.formatLoop(loop));
        }
        if (scope == LoopScope.SESSION) {
            System.out.println(renderer.dim(
                    "  Follows this conversation across resume; runs while this chat is open."));
        } else {
            System.out.println(renderer.dim(
                    "  Shared by this project; loaded whenever a project chat opens."));
        }
    }

    private ScheduledLoopManager loopManager(LoopScope scope) {
        return scope == LoopScope.SESSION
                ? repl.getScheduledLoopManager()
                : repl.getGlobalScheduledLoopManager();
    }

    private void printLoopUsage(LoopScope scope) {
        System.out.println(renderer.dim("  " + scope.command + " add <5m|2h30m> <prompt>"));
        System.out.println(renderer.dim("  " + scope.command
                + " add cron <min hour dom mon dow> -- <prompt>"));
        System.out.println(renderer.dim("  " + scope.command
                + " list | clear | pause <id> | resume <id> | run <id> | remove <id>"));
    }

    // ========================================================================
    // Help
    // ========================================================================

    private void printReminderResult(ReminderManager.Scope scope, String arguments) {
        System.out.println(reminderManager.handleCommand(scope, arguments));
    }

    private boolean restartCurrentSession(String command, String arguments) {
        if (arguments != null && !arguments.isBlank()) {
            System.out.println(renderer.dim("  Usage: " + command));
            return true;
        }

        SessionRestartLauncher.LaunchResult result =
                SessionRestartLauncher.restartCurrentSession(sessionId, repl.getWorkingDirectory());
        if (!result.started()) {
            System.out.println(renderer.red("  Could not restart this session: " + result.error()));
            return true;
        }

        chatHistory.logSystem("Session restart requested; replacement process "
                + result.processId() + " will resume this transcript.");
        System.out.println(renderer.cyan("  Opening the restarted session in a new terminal"
                + " (launcher process " + result.processId() + ")..."));
        return false;
    }

    private boolean restartAllActiveSessions(String arguments) {
        if (arguments != null && !arguments.isBlank()) {
            System.out.println(renderer.dim("  Usage: /reset-all"));
            return true;
        }

        SessionRestartLauncher.RestartAllResult result =
                SessionRestartLauncher.restartAllActiveSessions(
                        sessionId, repl.getWorkingDirectory());
        printRestartAllResult(result);
        if (!result.currentSessionRestarted()) {
            System.out.println(renderer.yellow(
                    "  This session stayed open because its replacement could not be started."));
            return true;
        }

        chatHistory.logSystem("All active sessions reset requested; started replacements for "
                + result.replacementsStarted() + " of " + result.activeSessions() + " sessions.");
        return false;
    }

    private void printRestartAllResult(SessionRestartLauncher.RestartAllResult result) {
        if (result.activeSessions() == 0) {
            System.out.println(renderer.dim("  No active sessions were found."));
            return;
        }
        System.out.println(renderer.cyan("  Started replacements for "
                + result.replacementsStarted() + " of " + result.activeSessions()
                + " active session" + (result.activeSessions() == 1 ? "" : "s") + "."));
        for (SessionRestartLauncher.RestartFailure failure : result.failures()) {
            System.out.println(renderer.yellow("  " + failure.sessionId() + ": " + failure.error()));
        }
    }

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
            if (repl.getChatConfig() != null && repl.getChatConfig().supportsFastMode()) {
                body.append("  ").append(renderer.cyan("/fast")).append(" [on|off|status]  Toggle premium fast mode\n");
            }
            body.append("  ").append(renderer.cyan("/permissions")).append("        View or set tool permissions\n");
            body.append("  ").append(renderer.cyan("/todos")).append("              Show the session task list\n");
            body.append("  ").append(renderer.cyan("/plan")).append(" [on|off]       Toggle planning mode (plan → approve → execute)\n");
            body.append("\n");
            body.append(renderer.bold(renderer.cyan("Navigation"))).append("\n");
            body.append("  ").append(renderer.cyan("/menu")).append("               Main menu (chat, passthrough, resume, setup)\n");
            body.append("  ").append(renderer.cyan("/passthrough [agent]")).append("  Launch external CLI agent\n");
            body.append("  ").append(renderer.cyan("/resume")).append("               Browse & resume conversations\n");
            body.append("  ").append(renderer.cyan("/resume-all [options]")).append(" Restore recent exited/crashed conversations\n");
            body.append("  ").append(renderer.cyan("/mode <mode>")).append("          Switch mode (standard/passthrough/plan)\n");
            body.append("  ").append(renderer.cyan("/auth")).append("               Session account or global per-vendor authentication\n");
            body.append("  ").append(renderer.cyan("/provider")).append("           Switch provider/model and keep this conversation\n");
            body.append("  ").append(renderer.cyan("/setup")).append("              Reconfigure provider/runtime\n");
            body.append("  ").append(renderer.cyan("/clear")).append("              Start a new conversation in this process\n");
            body.append("  ").append(renderer.cyan("/reset")).append("              Restart this session in a new process\n");
            body.append("  ").append(renderer.cyan("/reset-all")).append("          Restart every active CLI chat session\n");
            body.append("  ").append(renderer.cyan("/restart")).append("            Alias for /reset\n");
            body.append("  ").append(renderer.cyan("/title [text]")).append("       Show or change the session title\n");
            body.append("  ").append(renderer.cyan("/dashboard [cmd]")).append("   Refresh/show/hide the project dashboard\n");
            body.append("  ").append(renderer.cyan("/judge")).append(" [cmd]         Judge control, policy, direction, and global switch\n");
            body.append("\n");
            body.append(renderer.bold(renderer.cyan("Message Queue"))).append("\n");
            body.append("  ").append(renderer.cyan("/queue <text>")).append("       Add a message to the queue\n");
            body.append("  ").append(renderer.cyan("/queues")).append("               List queued messages\n");
            body.append("  ").append(renderer.cyan("/queue-send")).append("           Send the next queued message\n");
            body.append("  ").append(renderer.cyan("/queue-send <id>")).append("      Send a specific queued message\n");
            body.append("  ").append(renderer.cyan("/queue-send-all")).append("       Send all queued messages\n");
            body.append("  ").append(renderer.cyan("/queue-remove <id>")).append("    Remove a message from the queue\n");
            body.append("  ").append(renderer.cyan("/queue-edit <id> <text>")).append("Edit a queued message\n");
            body.append("  ").append(renderer.cyan("/queue-move <id> <n>")).append("   Reorder a queued message\n");
            body.append("  ").append(renderer.cyan("/queue-clear")).append("          Clear all queued messages\n");
            body.append("  ").append(renderer.cyan("/queue-status")).append("         Show queue status\n");
            body.append("  ").append(renderer.cyan("/loop add <time> <text>")).append("Schedule a session recurring task\n");
            body.append("  ").append(renderer.cyan("/loop-global add <time> <text>")).append("Schedule a project-global task\n");
            body.append("  ").append(renderer.cyan("/loop[-global] list|clear")).append(" List/clear/pause/resume/run/remove schedules\n");
            body.append("\n");
            body.append(renderer.bold(renderer.cyan("Hotkeys & Background"))).append("\n");
            body.append("  ").append(renderer.cyan("Escape")).append("              Cancel main LLM/tool operation (not subagents)\n");
            body.append("  ").append(renderer.cyan("Ctrl+B")).append("              Background active subagent invocation\n");
            body.append("  ").append(renderer.cyan("Ctrl+X P")).append("            Toggle planning mode\n");
            body.append("  ").append(renderer.cyan("Ctrl+X T")).append("            Show session task list (todos)\n");
            body.append("  ").append(renderer.cyan("Ctrl+X A")).append("            Cycle agent (coder/planner)\n");
            body.append("  ").append(renderer.cyan("/jobs")).append("               List background tasks\n");
            body.append("  ").append(renderer.cyan("/jobs-remove <id>")).append("   Remove a completed task\n");
            body.append("  ").append(renderer.cyan("/jobs-clear")).append("         Clear all completed tasks\n");
            body.append("  ").append(renderer.cyan("/activity agents")).append("     Open live project-agent activity\n");
            body.append("  ").append(renderer.cyan("/resources help")).append("     Configure resource rules and preview admission classes\n");
            body.append("  ").append(renderer.cyan("/processes")).append("          Show processes, monitors & subagents panel\n");
            body.append("  ").append(renderer.cyan("/process-kill <id>")).append("  Kill a running process\n");
            body.append("  ").append(renderer.cyan("/process-output <id>")).append("View process output\n");
            body.append("  ").append(renderer.cyan("/process-status <id>")).append("Show process or watcher status\n");
            body.append("  ").append(renderer.cyan("/statusbar")).append("          Toggle status bar on/off\n");
            body.append("  ").append(renderer.cyan("/process-monitors")).append("   List process completion monitors\n");

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
            body.append("  ").append(renderer.cyan("/reminder [text]")).append("    List/add/clear; 'interval <n|off>' sets cadence\n");
            body.append("  ").append(renderer.cyan("/reminder-global [text]")).append("Project reminders; optional interval override\n");
            body.append("  ").append(renderer.cyan("/compact [focus]")).append("    LLM-summarize conversation, freeing context\n");
            body.append("  ").append(renderer.cyan("/auto-compact ...")).append("   Configure automatic model-aware compaction\n");
            body.append("\n");
            body.append(renderer.bold(renderer.cyan("General"))).append("\n");
            body.append("  ").append(renderer.cyan("/stats")).append("              Session statistics (tokens, timing, tools)\n");
            body.append("  ").append(renderer.cyan("/copy")).append("              Copy latest assistant response\n");
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
            body.append("  ").append(renderer.cyan("/copy")).append("              Copy latest assistant response\n");
            body.append("  ").append(renderer.cyan("/conversations")).append("      List all saved conversations\n");
            body.append("  ").append(renderer.cyan("/clear")).append("              Start a new conversation in this process\n");
            body.append("  ").append(renderer.cyan("/reset")).append("              Restart this session in a new process\n");
            body.append("  ").append(renderer.cyan("/reset-all")).append("          Restart every active CLI chat session\n");
            body.append("  ").append(renderer.cyan("/restart")).append("            Alias for /reset\n");
            body.append("  ").append(renderer.cyan("/compact [focus]")).append("    LLM-summarize conversation (local mode only)\n");
            body.append("  ").append(renderer.cyan("/auto-compact ...")).append("   Auto-compaction status and policy\n");
            body.append("  ").append(renderer.cyan("/config")).append("             Show/update session config\n");
            body.append("  ").append(renderer.cyan("/setup")).append("              Reconfigure LLM provider\n");
            body.append("  ").append(renderer.cyan("/title [text]")).append("       Show or change the session title\n");
            body.append("  ").append(renderer.cyan("/dashboard [cmd]")).append("   Refresh/show/hide the project dashboard\n");
            body.append("  ").append(renderer.cyan("/reminder [text]")).append("    List/add/clear; 'interval <n|off>' sets cadence\n");
            body.append("  ").append(renderer.cyan("/reminder-global [text]")).append("Project reminders; optional interval override\n");
            body.append("\n");
            body.append(renderer.bold(renderer.cyan("Modes"))).append("\n");
            body.append("  ").append(renderer.cyan("/passthrough [agent]")).append("  Launch external CLI agent\n");
            body.append("  ").append(renderer.cyan("/resume")).append("               Browse & resume conversations\n");
            body.append("  ").append(renderer.cyan("/resume-all [options]")).append(" Restore recent exited/crashed conversations\n");
            body.append("  ").append(renderer.cyan("/mode <mode>")).append("          Switch mode (standard/passthrough/plan)\n");
            body.append("  ").append(renderer.cyan("/judge")).append(" [cmd]           Judge control, policy, direction, and global switch\n");
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
            body.append("  ").append(renderer.cyan("/queue-edit <id> <text>")).append("Edit a queued message\n");
            body.append("  ").append(renderer.cyan("/queue-move <id> <n>")).append("   Reorder a queued message\n");
            body.append("  ").append(renderer.cyan("/queue-clear")).append("          Clear all queued messages\n");
            body.append("  ").append(renderer.cyan("/queue-status")).append("         Show queue status\n");
            body.append("  ").append(renderer.cyan("/loop add <time> <text>")).append("Schedule a session recurring task\n");
            body.append("  ").append(renderer.cyan("/loop-global add <time> <text>")).append("Schedule a project-global task\n");
            body.append("  ").append(renderer.cyan("/loop[-global] list|clear")).append(" List/clear/pause/resume/run/remove schedules\n");
            body.append("\n");
            body.append(renderer.bold(renderer.cyan("Hotkeys & Background"))).append("\n");
            body.append("  ").append(renderer.cyan("Escape")).append("              Cancel main LLM/tool operation (not subagents)\n");
            body.append("  ").append(renderer.cyan("Ctrl+B")).append("              Background active subagent invocation\n");
            body.append("  ").append(renderer.cyan("Ctrl+X P")).append("            Toggle planning mode\n");
            body.append("  ").append(renderer.cyan("Ctrl+X T")).append("            Show session task list (todos)\n");
            body.append("  ").append(renderer.cyan("Ctrl+X A")).append("            Cycle agent (coder/planner)\n");
            body.append("  ").append(renderer.cyan("/jobs")).append("               List background tasks\n");
            body.append("  ").append(renderer.cyan("/jobs-remove <id>")).append("   Remove a completed task\n");
            body.append("  ").append(renderer.cyan("/jobs-clear")).append("         Clear all completed tasks\n");
            body.append("  ").append(renderer.cyan("/activity agents")).append("     Open live project-agent activity\n");
            body.append("  ").append(renderer.cyan("/resources help")).append("     Configure resource rules and preview admission classes\n");
            body.append("  ").append(renderer.cyan("/processes")).append("          Show processes, monitors & subagents panel\n");
            body.append("  ").append(renderer.cyan("/process-kill <id>")).append("  Kill a running process\n");
            body.append("  ").append(renderer.cyan("/process-status <id>")).append("Show process or watcher status\n");
            body.append("  ").append(renderer.cyan("/process-monitors")).append("   List process completion monitors\n");

            body.append("  ").append(renderer.cyan("/statusbar")).append("          Toggle status bar on/off\n");
            body.append("  ").append(renderer.cyan("/auto-dequeue")).append("       Toggle auto-send queued messages\n");
        }

        if (repl.isForceAgentic()) {
            body.append("\\n").append(renderer.bold(renderer.cyan("Crawl controls"))).append("\\n");
            body.append("  ").append(renderer.cyan("/crawl status")).append("       Show run state and budgets\\n");
            body.append("  ").append(renderer.cyan("/crawl pause|resume")).append(" Pause/resume at safe points\\n");
            body.append("  ").append(renderer.cyan("/crawl step")).append("         Permit one agent step\\n");
            body.append("  ").append(renderer.cyan("/crawl approve")).append("      Approve one mutation\\n");
            body.append("  ").append(renderer.cyan("/crawl stop")).append("         Stop the run\\n");
        }
        System.out.println(ascii.panel("Help", body.toString(), AsciiRenderer.ROUNDED, "cyan"));
        System.out.println();
        System.out.println(renderer.dim("  Conversations saved to ~/.kompile/conversations/"));
        System.out.println(renderer.dim("  Resume one: kompile chat --resume <session-id>"));
        System.out.println(renderer.dim("  Restore recent: /resume-all --dry-run, then /resume-all"));
        System.out.println(renderer.dim("  Continue last: kompile chat --continue"));
    }

    private void handleTitle(String requestedTitle) {
        if (requestedTitle == null || requestedTitle.isBlank()) {
            System.out.println(renderer.dim("  Session title: ") + repl.displayedSessionTitle());
            return;
        }
        String updated = repl.setSessionTitle(requestedTitle);
        System.out.println(renderer.green("  Session title updated: ") + updated);
    }

    // ========================================================================
    // Setup / config
    // ========================================================================

    private void runSetup() {
        ChatConfig newConfig = SetupWizard.run();
        if (newConfig == null) {
            System.out.println("Setup cancelled.");
            return;
        }
        if (repl.updateChatConfig(newConfig)) {
            System.out.println(renderer.green(
                    "Provider updated in this session. Existing transcript and conversation context were retained."));
            System.out.println(renderer.dim(
                    "  New messages will use " + newConfig.getProvider() + "/" + newConfig.getModel() + "."));
        } else {
            System.out.println(renderer.dim(
                    "Configuration saved for the next session; this runtime change cannot be applied in-place."));
        }
    }

    // ========================================================================
    // Judge policy configuration (legacy implementation names retained internally)
    // ========================================================================

    private void handleEnforcerCommand(String args) {
        Path wd = repl.getWorkingDirectory();
        String subCmd = args.isBlank() ? "status" : args.split("\\s+")[0].toLowerCase();

        switch (subCmd) {
            case "init", "setup" -> {
                EnforcerConfig config = EnforcerSetupWizard.run(wd);
                if (config != null) {
                    repl.reloadJudgeConfiguration();
                    System.out.println(renderer.green("Judge policy configured and reloaded."));
                } else {
                    System.out.println("Judge policy setup cancelled.");
                }
            }
            case "show", "status" -> {
                // Show live status first
                if (agenticLoop.isInlineEnforcerEnabled()) {
                    System.out.println(renderer.green("  [ACTIVE] " + agenticLoop.describeInlineEnforcer()));
                } else if (agenticLoop.describeInlineEnforcer() != null) {
                    System.out.println(renderer.yellow("  [DISABLED] " + agenticLoop.describeInlineEnforcer() + " — /judge on to enable"));
                } else {
                    System.out.println(renderer.dim("  [OFF] No inline enforcer loaded"));
                }
                EnforcerConfig config = EnforcerConfig.load(wd);
                if (config == null) {
                    System.out.println(renderer.dim("  No judge policy config for this project."));
                    System.out.println(renderer.dim("  Run /judge init to configure."));
                    return;
                }
                System.out.println();
                System.out.println(renderer.bold("  Judge Policy Config"));
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
                System.out.println(renderer.dim("  /judge init   — reconfigure"));
                System.out.println(renderer.dim("  /judge delete — remove config"));
                System.out.println(renderer.dim("  /judge run    — show standalone compatibility command"));
                System.out.println();
            }
            case "delete", "remove" -> {
                try {
                    if (EnforcerConfig.delete(wd)) {
                    repl.clearJudgePolicy();
                    System.out.println(renderer.green("Judge policy config deleted and unloaded."));
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
                    System.out.println(renderer.dim("No judge policy config. Run /judge init first."));
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
                    System.out.println(renderer.dim("No judge policy config. Run /judge init first."));
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
                    repl.loadInlineEnforcer(wd);
                    if (agenticLoop.isInlineEnforcerEnabled()) {
                        System.out.println(renderer.green("  Enforcer loaded and enabled: " + agenticLoop.describeInlineEnforcer()));
                    } else {
                        System.out.println(renderer.dim("No judge policy config for this project. Run /judge init first."));
                    }
                }
            }
            case "off", "disable" -> {
                agenticLoop.setInlineEnforcerEnabled(false);
                System.out.println(renderer.yellow("  Enforcer disabled."));
            }
            case "reload" -> {
                repl.reloadJudgeConfiguration();
                if (agenticLoop.isInlineEnforcerEnabled()) {
                    System.out.println(renderer.green("  Judge policy reloaded: " + agenticLoop.describeInlineEnforcer()));
                } else {
                    System.out.println(renderer.dim("No active project judge policy found."));
                }
            }
            default -> {
                System.out.println("Usage: /judge [on|off|init|config|rules|reload|delete]");
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

    // ========================================================================
    // Judge control (/judge) — chat with the judge, give feedback, override
    // ========================================================================

    private void handleJudgeCommand(String args) {
        ai.kompile.cli.main.chat.enforcer.JudgeControl control = repl.getJudgeControl();
        if (control == null) {
            System.out.println(renderer.red("Judge control is not available in this session."));
            return;
        }
        JudgeCommand command = parseJudgeCommand(args);
        String subCmd = command.subcommand();
        String rest = command.arguments();

        switch (subCmd) {
            case "status", "show" -> printJudgeStatus(control);
            case "on", "enable", "resume" -> {
                if (repl.setJudgeSessionEnabled(true)) {
                    System.out.println(renderer.green("  Judge enabled for this session."));
                } else {
                    System.out.println(renderer.yellow(
                            "  Judge is globally disabled. Use /judge global on first."));
                }
            }
            case "off", "disable", "pause" -> {
                repl.setJudgeSessionEnabled(false);
                System.out.println(renderer.yellow(
                        "  Judge intervention disabled for this session; chat remains available."));
            }
            case "global" -> handleJudgeGlobal(rest);
            case "workflow" -> handleWorkflowCommand(rest);
            case "direction" -> handleDirectionCommand(rest);
            case "policy" -> handleEnforcerCommand(rest);
            case "init", "setup", "config", "rules", "reload", "delete", "remove",
                 "run", "start", "launch" -> handleEnforcerCommand(
                    "config".equals(subCmd) ? "show" : subCmd + (rest.isBlank() ? "" : " " + rest));
            case "chat", "talk" -> handleJudgeChat(rest);
            case "feedback", "guidance" -> handleJudgeFeedback(control, rest);
            case "approve" -> {
                if (rest.isBlank()) {
                    System.out.println("Usage: /judge approve [--pattern] <bash command> | off");
                    return;
                }
                boolean cancel = "off".equalsIgnoreCase(rest) || "cancel".equalsIgnoreCase(rest);
                boolean pattern = rest.equals("--pattern") || rest.startsWith("--pattern ");
                try {
                    if (pattern) control.approvePatternNext(rest.substring("--pattern".length()).trim());
                    else control.approveCommandNext(cancel ? "" : rest);
                } catch (IllegalArgumentException invalid) {
                    System.out.println(renderer.red("  Invalid approval pattern: " + invalid.getMessage()));
                    return;
                }
                System.out.println(renderer.yellow(cancel ? "  Command approval cancelled."
                        : "  Approved " + (pattern ? "bash token pattern" : "exact bash command")
                        + " for the next turn only: " + control.getApprovedCommandNext()));
                if (pattern) System.out.println(renderer.dim(
                        "  * matches within one argument (not /); final ** accepts remaining arguments. No shell operators or expansion."));
                System.out.println(renderer.dim("  Permissions, workflow and dedicated-tool/managed-memory protections remain active."));
                System.out.println(renderer.dim("  This does not execute a command. Ask the agent to retry in your next turn."));
            }
            case "override", "bypass", "allow-next" -> {
                boolean enable = !"off".equalsIgnoreCase(rest) && !"cancel".equalsIgnoreCase(rest);
                control.setOverrideNext(enable);
                if (enable) {
                    System.out.println(renderer.yellow("  Judge override ARMED for the next turn:"));
                    System.out.println(renderer.dim("  · turn review still evaluates and logs, but is report-only"));
                    System.out.println(renderer.dim("  · explicit hard MCP policy remains active for this one-shot override"));
                    System.out.println(renderer.dim("  · use /judge off to disable every judge lane for the session"));
                    System.out.println(renderer.dim("  · cancel before it fires with: /judge override off"));
                } else {
                    System.out.println(renderer.green("  Judge override disarmed — normal turn enforcement resumes."));
                }
            }
            case "judgements", "history" -> printJudgeJudgements(rest);
            case "restart" -> {
                String message = repl.restartJudge();
                System.out.println(renderer.green(message));
            }
            case "agent" -> {
                if (rest.isBlank()) {
                    System.out.println("Usage: /judge agent <name>  (e.g. claude, codex, gemini)");
                    return;
                }
                System.out.println(renderer.green(repl.modifyJudge(rest)));
            }
            case "help", "usage" -> printJudgeUsage();
            default -> throw new IllegalStateException("Unhandled /judge command: " + subCmd);
        }
    }

    private void printJudgeStatus(ai.kompile.cli.main.chat.enforcer.JudgeControl control) {
        System.out.println();
        System.out.println(renderer.bold("  Judge Control"));
        System.out.println("  ──────────────────────────────────────────");
        System.out.println("  Global:     " + (repl.isJudgeGloballyEnabled()
                ? renderer.green("enabled") : renderer.yellow("disabled")));
        System.out.println("  Session:    " + (repl.isJudgeSessionEnabled()
                ? renderer.green("enabled") : renderer.yellow("disabled")));
        System.out.println("  Backend:    " + renderer.cyan(repl.describeJudge()));
        System.out.println("  Chat:       " + (repl.isJudgeChatAvailable()
                ? renderer.green("available independently of intervention")
                : renderer.yellow("no backend available")));
        String policy = agenticLoop.describeInlineEnforcer();
        boolean policyActive = repl.isJudgeGloballyEnabled() && repl.isJudgeSessionEnabled()
                && agenticLoop.isInlineEnforcerEnabled();
        System.out.println("  Policy:     " + (policy == null
                ? renderer.dim("not configured")
                : (policyActive ? renderer.green(policy) : renderer.yellow(policy + " (off)"))));
        ai.kompile.cli.main.chat.enforcer.DirectionJudge direction = repl.getDirectionJudge();
        System.out.println("  Direction:  " + (direction == null
                ? renderer.dim("not configured")
                : (direction.isEnabled() ? renderer.green("enabled") : renderer.yellow("off"))));
        WorkflowController.Status workflow = agenticLoop.workflowStatus();
        System.out.println("  Workflow:   " + (workflow.effectiveMode() == WorkflowPolicy.Mode.OFF
                ? renderer.dim("off")
                : workflow.effectiveMode() == WorkflowPolicy.Mode.ENFORCED
                        ? renderer.green("enforced") : renderer.yellow("advisory")));
        if (control.hasGuidance()) {
            System.out.println("  Guidance:   " + renderer.green("active"));
            for (String line : control.getGuidance().split("\n", 20)) {
                System.out.println(renderer.dim("    " + line));
            }
        } else {
            System.out.println("  Guidance:   " + renderer.dim("(none — /judge feedback <text> to correct the judge)"));
        }
        System.out.println("  Override:   " + (control.isOverrideNextSet()
                ? renderer.yellow("ARMED for next turn (turn review report-only; hard MCP policy active)")
                : renderer.dim("not armed")));
        System.out.println("  Approved command (next turn): " + (control.getApprovedCommandNext().isBlank()
                ? renderer.dim("none") : renderer.yellow((control.isApprovalPatternNext() ? "pattern: " : "exact: ")
                        + control.getApprovedCommandNext())));
        System.out.println("  State file: " + renderer.dim(control.getFile().toString()));
        System.out.println();
        System.out.println(renderer.dim("  /judge chat [msg]        talk with the judge (no msg = interactive)"));
        System.out.println(renderer.dim("  /judge on|off            enable/disable every judge lane this session"));
        System.out.println(renderer.dim("  /judge global on|off     persistent master switch for every session"));
        System.out.println(renderer.dim("  /judge init|rules|reload configure the project judge policy"));
        System.out.println(renderer.dim("  /judge workflow ...      configure deterministic workflow gates"));
        System.out.println(renderer.dim("  /judge direction ...     configure goal-drift monitoring"));
        System.out.println(renderer.dim("  /judge feedback <text>   durable guidance for every future verdict"));
        System.out.println(renderer.dim("  /judge approve [--pattern] <cmd>   approve next turn; off cancels"));
        System.out.println(renderer.dim("  /judge override [off]    one-shot report-only turn; hard MCP policy stays active"));
        System.out.println(renderer.dim("  /judge judgements [n]    show the judge's recent verdicts"));
        System.out.println(renderer.dim("  /judge restart|agent X   restart judge / switch judge agent"));
        System.out.println();
    }

    private void handleJudgeGlobal(String args) {
        HarnessConfig config = HarnessConfig.load(objectMapper);
        String value = args == null ? "" : args.trim().toLowerCase(Locale.ROOT);
        if (value.isBlank() || "status".equals(value) || "show".equals(value)) {
            System.out.println("  Judge global setting: " + (config.isJudgeGlobalEnabled()
                    ? renderer.green("enabled") : renderer.yellow("disabled")));
            System.out.println("  Config: " + renderer.dim(HarnessConfig.getConfigFilePath().toString()));
            return;
        }
        if (!"on".equals(value) && !"enable".equals(value)
                && !"off".equals(value) && !"disable".equals(value)) {
            System.out.println("Usage: /judge global [on|off|status]");
            return;
        }
        boolean enabled = "on".equals(value) || "enable".equals(value);
        config.setJudgeGlobalEnabled(enabled);
        config.save(objectMapper);
        repl.setJudgeGloballyEnabled(enabled);
        System.out.println(enabled
                ? renderer.green("  Judge enabled globally and for this session.")
                : renderer.yellow(
                        "  Judge intervention disabled globally; configured judge chat remains available."));
    }

    // ========================================================================
    // Workflow profile (/judge workflow)
    // ========================================================================

    private void handleWorkflowCommand(String args) {
        String[] parts = args == null || args.isBlank()
                ? new String[] {"status"} : args.trim().split("\\s+", 2);
        String command = parts[0].toLowerCase(Locale.ROOT);
        String rest = parts.length > 1 ? parts[1].trim() : "";
        try {
            switch (command) {
                case "status", "show" -> printWorkflowStatus();
                case "reload", "default", "configured" -> {
                    agenticLoop.reloadWorkflowConfiguration();
                    System.out.println(renderer.green("  Workflow profile reloaded from project config."));
                    printWorkflowStatus();
                }
                case "off", "disable", "disabled",
                     "advisory", "advise", "guided", "guide",
                     "enforced", "enforce", "strict" -> {
                    WorkflowPolicy.Mode mode = WorkflowPolicy.Mode.parse(command);
                    List<String> skills = parseWorkflowSkills(rest);
                    agenticLoop.setWorkflowSessionMode(mode, skills);
                    System.out.println(renderer.green("  Workflow session mode set to "
                            + mode.name().toLowerCase(Locale.ROOT) + "."));
                    printWorkflowStatus();
                }
                case "help", "usage" -> printWorkflowUsage();
                default -> {
                    System.out.println(renderer.red(
                            "Unknown /judge workflow subcommand: " + command));
                    printWorkflowUsage();
                }
            }
        } catch (IllegalArgumentException | IllegalStateException e) {
            System.out.println(renderer.red("  Workflow configuration failed: " + e.getMessage()));
        }
    }

    private void printWorkflowStatus() {
        WorkflowController.Status status = agenticLoop.workflowStatus();
        System.out.println();
        System.out.println(renderer.bold("  Host Workflow Profile"));
        System.out.println("  ──────────────────────────────────────────");
        System.out.println("  Configured: " + status.configuredMode().name().toLowerCase(Locale.ROOT));
        System.out.println("  Effective:  " + status.effectiveMode().name().toLowerCase(Locale.ROOT)
                + (status.sessionOverride() ? " (session override)" : ""));
        System.out.println("  Skills:     " + (status.requiredSkills().isEmpty()
                ? renderer.dim("none") : String.join(", ", status.requiredSkills())));
        System.out.println("  Plan gate:  " + (status.requirePlanBeforeMutation()
                ? "todowrite add/set required before mutation" : "disabled"));
        System.out.println("  Corrections: " + status.maxCorrections());
        System.out.println("  Scope:      standard/headless agentic loop");
        if (!status.globalEnabled() || !status.sessionEnabled()) {
            System.out.println(renderer.yellow("  Disabled by the "
                    + (!status.globalEnabled() ? "global" : "session") + " judge switch."));
        }
        System.out.println();
    }

    private static List<String> parseWorkflowSkills(String value) {
        if (value == null || value.isBlank()) return null;
        if ("none".equalsIgnoreCase(value) || "clear".equalsIgnoreCase(value)) {
            return List.of();
        }
        LinkedHashSet<String> names = new LinkedHashSet<>();
        for (String item : value.split("[,\\s]+")) {
            if (!item.isBlank()) names.add(item.trim());
        }
        return List.copyOf(names);
    }

    private void printWorkflowUsage() {
        System.out.println();
        System.out.println(renderer.bold("  /judge workflow usage"));
        System.out.println("  ──────────────────────────────────────────");
        System.out.println(renderer.dim("  status                         show effective profile"));
        System.out.println(renderer.dim("  enforced [skill,...]           block mutation before plan"));
        System.out.println(renderer.dim("  advisory [skill,...]           inject guidance without blocking"));
        System.out.println(renderer.dim("  off                            disable for this session"));
        System.out.println(renderer.dim("  reload                         clear override and reload config"));
        System.out.println(renderer.dim("  Persist with enforcer_config workflow_* fields."));
        System.out.println();
    }

    // ========================================================================
    // Direction judge (/judge direction; /direction remains a compatibility alias)
    // ========================================================================

    private void handleDirectionCommand(String args) {
        ai.kompile.cli.main.chat.enforcer.DirectionJudge judge = repl.getDirectionJudge();
        String[] parts = args == null || args.isBlank()
                ? new String[] {"status"} : args.trim().split("\\s+", 2);
        String subCmd = parts[0].toLowerCase(java.util.Locale.ROOT);
        String rest = parts.length > 1 ? parts[1].trim() : "";

        if (judge == null) {
            // Off by default — explain exactly how to turn it on.
            if ("help".equals(subCmd) || "usage".equals(subCmd)) {
                printDirectionUsage();
                return;
            }
            System.out.println(renderer.yellow(
                    "  Direction monitoring is OFF in this session."));
            System.out.println(renderer.dim(
                    "  It is strictly opt-in: add \"directionMonitoring\": true to"));
            System.out.println(renderer.dim(
                    "  .kompile/enforcer-config.json (or /judge reload after editing),"));
            System.out.println(renderer.dim(
                    "  or run: kompile enforcer init --direction-monitoring"));
            printDirectionUsage();
            return;
        }

        switch (subCmd) {
            case "status", "show" -> printDirectionStatus(judge);
            case "goal", "set-goal" -> {
                if (rest.isBlank()) {
                    System.out.println("Usage: /judge direction goal <text>   (or '... goal clear' to reset)");
                    return;
                }
                if ("clear".equalsIgnoreCase(rest) || "none".equalsIgnoreCase(rest)) {
                    judge.setGoal(null);
                    System.out.println(renderer.green(
                            "  Direction goal cleared — the judge will use each turn's user message."));
                } else {
                    judge.setGoal(rest);
                    System.out.println(renderer.green("  Direction goal set: " + rest));
                }
                System.out.println(renderer.dim(
                        "  Session-scoped: 'kompile enforcer init --direction-goal \"...\"' persists it."));
            }
            case "on" -> {
                judge.setEnabled(true);
                System.out.println(renderer.green("  Direction judge enabled."));
            }
            case "off" -> {
                judge.setEnabled(false);
                System.out.println(renderer.yellow(
                        "  Direction judge disabled for this session."));
                System.out.println(renderer.dim(
                        "  The project keeps directionMonitoring=true; /judge direction on re-enables it."));
            }
            case "reset", "reset-streak" -> {
                judge.resetCrossTurnState();
                System.out.println(renderer.green(
                        "  Cross-turn direction streak and history reset for this session."));
            }
            case "reload" -> {
                repl.loadDirectionJudge(repl.getWorkingDirectory());
                ai.kompile.cli.main.chat.enforcer.DirectionJudge reloaded = repl.getDirectionJudge();
                if (reloaded != null) {
                    System.out.println(renderer.green("  Direction judge reloaded: "
                            + reloaded.describe()));
                } else {
                    System.out.println(renderer.yellow(
                            "  Direction monitoring stayed OFF — the project config does not"));
                    System.out.println(renderer.dim(
                            "  enable it (\"directionMonitoring\": true), or no judge backend is available."));
                }
            }
            case "help", "usage" -> printDirectionUsage();
            default -> {
                System.out.println(renderer.red("Unknown /judge direction subcommand: " + subCmd));
                printDirectionUsage();
            }
        }
    }

    private void printDirectionStatus(ai.kompile.cli.main.chat.enforcer.DirectionJudge judge) {
        System.out.println();
        System.out.println(renderer.bold("  Direction Judge (goal-drift monitor)"));
        System.out.println("  ──────────────────────────────────────────");
        System.out.println("  Backend:   " + renderer.cyan(judge.describe()));
        System.out.println("  Enabled:   " + (judge.isEnabled()
                ? renderer.green("yes") : renderer.yellow("no (/judge direction on)")));
        System.out.println("  Mode:      " + (judge.isReportOnly()
                ? renderer.yellow("report-only (never redirects or halts)")
                : "active (redirect in place; halt after redirect budget)"));
        System.out.println("  Cadence:   every " + judge.getCheckEvery() + " model iterations");
        System.out.println("  Redirects: " + judge.getMaxRedirects() + " max per turn ("
                + judge.getRedirectsThisTurn() + " used this turn, "
                + judge.getChecksThisTurn() + " checks)");
        System.out.println("  Acts at:   confidence >= " + judge.getConfidenceThreshold());
        ai.kompile.cli.main.chat.enforcer.DirectionJudge.SessionState state =
                judge.getSessionState();
        System.out.println("  Cross-turn: " + state.consecutiveDriftTurns() + " consecutive / "
                + (state.crossTurnDriftLimit() == 0
                        ? "disabled" : state.crossTurnDriftLimit() + " limit")
                + " · " + state.totalDriftTurns() + " drift-affected / "
                + state.assessedTurns() + " assessed turns");
        if (state.lastDriftReason() != null) {
            System.out.println("  Last drift: " + renderer.yellow(state.lastDriftReason()));
        }
        String goal = judge.getGoal();
        if (goal != null) {
            System.out.println("  Goal:      " + renderer.green(goal));
            System.out.println(renderer.dim("             (session goal — /judge direction goal clear to reset)"));
        } else {
            System.out.println("  Goal:      " + renderer.dim("(none — using each turn's user message)"));
        }
        System.out.println();
        System.out.println(renderer.dim("  /judge direction goal <text>  set a session goal (or 'clear')"));
        System.out.println(renderer.dim("  /judge direction on|off       enable/disable direction checks"));
        System.out.println(renderer.dim("  /judge direction reset        clear cross-turn streak/history"));
        System.out.println(renderer.dim("  /judge reload                 re-read the project config"));
        if (judge.getStateFile() != null) {
            System.out.println("  State file: " + renderer.dim(judge.getStateFile().toString()));
        }
        System.out.println(renderer.dim("  /judge direction help"));
        System.out.println();
    }

    private void printDirectionUsage() {
        System.out.println();
        System.out.println(renderer.bold("  /judge direction usage"));
        System.out.println("  ──────────────────────────────────────────");
        System.out.println(renderer.dim("  status                    show judge state, goal, and counters"));
        System.out.println(renderer.dim("  goal <text>               set a session goal (or 'clear')"));
        System.out.println(renderer.dim("  on|off                    enable/disable for this session"));
        System.out.println(renderer.dim("  reset                     clear cross-turn streak/history"));
        System.out.println(renderer.dim("  reload                    re-read the project config"));
        System.out.println();
    }

    private void handleJudgeChat(String message) {
        if (message.isBlank()) {
            System.out.println(renderer.dim(
                    "  Use /judge chat <message> or simply /judge <message>."));
            System.out.println(renderer.dim(
                    "  Repeated messages keep the judge conversation history; intervention can remain off."));
            return;
        }
        String reply = repl.sendToJudge(message);
        System.out.println();
        System.out.println(renderer.bold("  Judge:") + " " + reply.strip());
        System.out.println();
    }

    private void handleJudgeFeedback(ai.kompile.cli.main.chat.enforcer.JudgeControl control,
                                     String text) {
        if (text.isBlank()) {
            if (control.hasGuidance()) {
                System.out.println(renderer.dim("  Current guidance:"));
                for (String line : control.getGuidance().split("\n", 20)) {
                    System.out.println("    " + line);
                }
            } else {
                System.out.println("Usage: /judge feedback <text>   (or '/judge feedback clear' to remove)");
            }
            return;
        }
        if ("clear".equalsIgnoreCase(text) || "none".equalsIgnoreCase(text)
                || "off".equalsIgnoreCase(text)) {
            control.clearGuidance();
            System.out.println(renderer.green("  Judge guidance cleared."));
            return;
        }
        control.setGuidance(text);
        System.out.println(renderer.green("  Judge guidance saved — injected into every future judge prompt:"));
        for (String line : text.split("\n", 20)) {
            System.out.println(renderer.dim("    " + line));
        }
        System.out.println(renderer.dim("  Persists for this session across restarts ("
                + control.getFile().getFileName() + ")."));
    }

    private void printJudgeJudgements(String rest) {
        int limit = 10;
        if (!rest.isBlank()) {
            try {
                limit = Math.max(1, Math.min(100, Integer.parseInt(rest.split("\\s+")[0])));
            } catch (NumberFormatException ignored) {
                // keep default
            }
        }
        java.util.List<ai.kompile.cli.main.chat.enforcer.JudgementRecord> records =
                repl.judgeHistory(limit);
        if (records.isEmpty()) {
            System.out.println(renderer.dim("  No judgements recorded for this session yet."));
            return;
        }
        System.out.println();
        System.out.println(renderer.bold("  Recent Judgements (newest first, last " + limit + ")"));
        System.out.println("  ──────────────────────────────────────────");
        for (int i = records.size() - 1; i >= 0; i--) {
            ai.kompile.cli.main.chat.enforcer.JudgementRecord r = records.get(i);
            String verdict = r.isStop() ? "STOP"
                    : r.isCompliant() ? "ALLOW" : "CORRECT";
            String color = r.isCompliant() && !r.isStop()
                    ? renderer.green(verdict) : renderer.yellow(verdict);
            System.out.println("  [" + (r.getTimestamp() == null ? "?" : r.getTimestamp()) + "] "
                    + r.getPhase() + " · " + color
                    + " · " + r.getBackend()
                    + (r.getLatencyMs() > 0 ? " · " + r.getLatencyMs() + "ms" : ""));
            String reasoning = r.getReasoning();
            if (reasoning != null && !reasoning.isBlank()) {
                System.out.println(renderer.dim("      " + reasoning));
            }
        }
        System.out.println();
    }

    private void printJudgeUsage() {
        System.out.println("Usage: /judge [message | status|on|off|global|workflow|init|config|rules|direction|chat|ask|feedback|approve|override|judgements|restart|agent]");
        System.out.println();
        System.out.println(renderer.dim("  status              — guidance, override state, backend health"));
        System.out.println(renderer.dim("  on|off              — enable/disable intervention this session; chat stays available"));
        System.out.println(renderer.dim("  global on|off       — persistent intervention switch for every session"));
        System.out.println(renderer.dim("  init|config|rules   — configure or inspect project judge policy"));
        System.out.println(renderer.dim("  workflow ...        — required skills and plan-before-mutation gate"));
        System.out.println(renderer.dim("  direction ...       — configure goal-drift checks"));
        System.out.println(renderer.dim("  chat <message>      — converse with the judge using normal REPL turns"));
        System.out.println(renderer.dim("  <message>           — shorthand for chat (for example: /judge why did you block that?)"));
        System.out.println(renderer.dim("  ask <message>       — explicit chat alias; conversation remains available while intervention is off"));
        System.out.println(renderer.dim("  feedback <text>     — durable guidance injected into every verdict"));
        System.out.println(renderer.dim("  feedback clear      — remove durable guidance"));
        System.out.println(renderer.dim("  approve <command>   — override judge for exact bash command next turn; does not execute it"));
        System.out.println(renderer.dim("  approve --pattern <pattern> — token globs, e.g. git log ** or rm -rf target/cache-*"));
        System.out.println(renderer.dim("  * stays within one argument and path component; final ** permits remaining arguments"));
        System.out.println(renderer.dim("  Patterns reject chaining, redirects, substitutions, shell globs and .. paths; use exact approval for complex shell"));
        System.out.println(renderer.dim("  approve off         — cancel pending command approval"));
        System.out.println(renderer.dim("  override            — report-only turn review; hard MCP policy stays active"));
        System.out.println(renderer.dim("  override off        — disarm a pending override"));
        System.out.println(renderer.dim("  judgements [n]      — show recent judge verdicts"));
        System.out.println(renderer.dim("  restart             — restart the judge backend"));
        System.out.println(renderer.dim("  agent <name>        — switch the judge agent (claude, codex, …)"));
        System.out.println();
    }

    private void forwardCommandToAgent(String args) {
        if (args.isBlank()) {
            System.out.println("Usage: /forward <command> [args]");
            System.out.println(renderer.dim("  Forwards a slash command to the underlying agent CLI."));
            return;
        }
        String slashCmd = args.startsWith("/") ? args : "/" + args;
        String agentBinary = AgentCommandForwarder.resolveAgentBinary(repl.getAgentName());
        if (agentBinary == null) {
            System.out.println(renderer.yellow("Agent '" + repl.getAgentName() + "' not found on PATH."));
            System.out.println(renderer.dim("Supported agents: " + String.join(", ",
                    ai.kompile.cli.main.chat.config.ChatConfig.getPassthroughAgentOrder())));
            return;
        }
        AgentCommandForwarder forwarder = new AgentCommandForwarder();
        AgentCommandForwarder.AgentCommand agentCmd = forwarder.mapSlashCommand(slashCmd, agentBinary, repl.getAgentName());
        if (agentCmd != null) {
            System.out.println(renderer.dim("  → " + agentCmd.label()));
            forwarder.executeWithRealtimeOutput(agentCmd);
        } else {
            System.out.println(renderer.dim("  Command not supported for agent: " + repl.getAgentName()));
        }
    }

    private void showLocalConfig() {
        ChatConfig chatConfig = repl.getChatConfig();
        if (chatConfig == null) {
            System.out.println("No chat configuration. Run /setup to configure.");
            return;
        }

        LinkedHashMap<String, String> configMap = new LinkedHashMap<>();
        configMap.put("Mode", renderer.cyan(chatConfig.getChatMode()));
        if ("passthrough".equals(chatConfig.getChatMode())) {
            configMap.put("Agent", renderer.cyan(chatConfig.getPassthroughAgent()));
            configMap.put("Style", chatConfig.isPassthroughManaged() ? "Kompile managed" : "Direct");
        } else {
            configMap.put("Provider", renderer.cyan(chatConfig.getProvider()));
            configMap.put("Model", renderer.cyan(chatConfig.getModel()));
            configMap.put("Base URL", chatConfig.resolveBaseUrl());
            configMap.put("API Key", chatConfig.getApiKey() != null ?
                    renderer.dim(chatConfig.getApiKey().substring(0, Math.min(4, chatConfig.getApiKey().length())) + "...") :
                    renderer.red("not set"));
        }
        configMap.put("Config file", chatConfig.getLoadedFrom() != null
                ? chatConfig.getLoadedFrom().toString()
                : "(in-memory)");

        System.out.println(ascii.panel("Chat Configuration", ascii.keyValueList(configMap), AsciiRenderer.ROUNDED, "blue"));
        System.out.println();
        System.out.println(renderer.dim("  /setup to reconfigure"));
    }

    // ========================================================================
    // Tools
    // ========================================================================

    private void listTools() {
        try {
            List<McpSseClient.ToolInfo> cachedTools = mcpClient.listTools();
            repl.setCachedTools(cachedTools);
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
        AgentConfig agent = agentRegistry.get(repl.getLocalAgentName());
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

        List<String> headers = List.of("Agent", "Description", "Model");
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
            AgentConfig agent = agentRegistry.get(repl.getLocalAgentName());
            if (agent == null) agent = agentRegistry.getDefault();

            Path workDir = Paths.get(System.getProperty("user.dir"));
            ToolContext ctx = new ToolContext(sessionId, agent, permissionService, workDir, toolRegistry);

            TerminalRenderer.SpinnerHandle spinner = renderer.startSpinner(toolName);
            ToolResult result = tool.execute(args, ctx);
            spinner.stop();

            System.out.println(renderer.renderToolCallComplete(toolName, argsJson, result));
            repl.onDirectToolComplete(toolName, argsJson, result);
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
            String active = a.getName().equals(repl.getLocalAgentName()) ? renderer.green("●") : "";
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

    private void handleFastModeCommand(String rest) {
        String action = rest.toLowerCase(java.util.Locale.ROOT);
        if (!Set.of("", "on", "off", "status").contains(action)) {
            ChatCompleter.printAbove("Usage: /fast [on|off|status] (no argument toggles)");
            return;
        }
        ChatConfig config = repl.getChatConfig();
        if (!localMode || config == null) {
            ChatCompleter.printAbove("Fast mode is only configurable in local standard chat.");
            return;
        }
        // Always allow clearing an old preference, even after eligibility changes.
        if (!config.supportsFastMode() && !"off".equals(action)) {
            ChatCompleter.printAbove("Fast mode is not supported for the selected provider/model. Use /model.");
            return;
        }
        if (!"status".equals(action)) {
            config.setFastMode("on".equals(action) || (action.isEmpty() && !config.isFastMode()));
            try {
                config.saveLoadedOrGlobal();
            } catch (java.io.IOException error) {
                ChatCompleter.printAbove(renderer.yellow(
                        "Fast mode changed for this session, but could not be saved: " + error.getMessage()));
            }
            repl.refreshModelDisplay();
        }
        ChatCompleter.printAbove("Fast mode " + (config.isFastMode() ? "ON (requested)" : "OFF")
                + " — applies to subsequent requests; reasoning effort is unchanged.");
        ChatCompleter.printAbove(config.fastModeCapabilities().notice());
    }

    private void handleModelCommand(String rest) {
        if (!localMode) {
            System.out.println(renderer.dim("Model switching is only available in local mode."));
            return;
        }
        ChatConfig chatConfig = repl.getChatConfig();
        if (chatConfig == null) {
            System.out.println("No LLM configuration. Run /setup to configure.");
            return;
        }

        if (rest.isEmpty()) {
            repl.openModelProviderPicker();
            return;
        }

        repl.applyModelSelection(rest);
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
        ChatConfig chatConfig = repl.getChatConfig();
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

        pendingAttachments.add(new ChatRepl.PendingAttachment(filePath, mime, true));
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
            if (ChatRepl.TEXT_EXTENSIONS.contains(ext)) {
                mime = "text/plain";
            } else {
                mime = "application/octet-stream";
            }
        }

        pendingAttachments.add(new ChatRepl.PendingAttachment(filePath, mime, isImage));
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
            ChatRepl.PendingAttachment att = pendingAttachments.get(i);
            String icon = att.isImage() ? "🖼" : "📄";
            System.out.printf("  %d. %s %s (%s)%n", i + 1, icon,
                    renderer.cyan(att.path().getFileName().toString()), att.mimeType());
        }
        System.out.println(renderer.dim("  These will be sent with your next message."));
        System.out.println(renderer.dim("  Send a message or use /attachments to review."));
    }

    // Attachment helper utilities

    Path resolveAttachmentPath(String pathStr) {
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

    static String detectImageMimeType(Path path) {
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

    static String getFileExtension(Path path) {
        String name = path.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot >= 0 ? name.substring(dot + 1) : "";
    }

    static String formatFileSize(Path path) {
        try {
            long size = Files.size(path);
            if (size < 1024) return size + " B";
            if (size < 1024 * 1024) return String.format("%.1f KB", size / 1024.0);
            return String.format("%.1f MB", size / (1024.0 * 1024.0));
        } catch (Exception e) {
            return "unknown";
        }
    }

    // ========================================================================
    // Agent switching
    // ========================================================================

    private void switchLocalAgent(String name) {
        if (name.isBlank()) {
            System.out.println("Current local agent: " + repl.getLocalAgentName());
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

        repl.setLocalAgentName(name.trim());
        chatHistory.logSystem("Switched local agent to: " + repl.getLocalAgentName());
        System.out.println("Switched local agent to: " + repl.getLocalAgentName());
    }

    // ========================================================================
    // Skill execution
    // ========================================================================

    private void executeSkill(SkillRegistry.SkillInvocation invocation) {
        SkillConfig skill = invocation.skill();
        String args = invocation.arguments();
        repl.initializeSessionTitleFromPrompt("/" + skill.getName()
                + (args.isBlank() ? "" : " " + args));
        chatHistory.logSystem("Executing skill: /" + skill.getName()
                + (args.isBlank() ? "" : " " + args));
        messageHandler.handleChatMessage(invocation.prompt());
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
            System.out.println("  Using default agent: " + renderer.cyan(repl.getAgentName()));
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
        String oldAgentName = repl.getAgentName();
        repl.setAgentName(role.getName());

        // Update the agentic loop with the role's agent config
        AgentConfig roleAgentConfig = role.toAgentConfig();
        agenticLoop.setAgentConfig(roleAgentConfig);

        System.out.println();
        System.out.println(renderer.green("  ✓ Role assigned: ") + renderer.cyan(role.getName()));
        System.out.println("  " + role.getDisplayName() + renderer.dim(" - " + role.getDescription()));
        System.out.println();
        System.out.println(renderer.dim("  Agent changed from " + oldAgentName + " to " + repl.getAgentName()));
        System.out.println(renderer.dim("  The agent will now use this role's system prompt"));
        System.out.println();

        // Track in metrics
        sessionMetrics.setAgentName(repl.getAgentName());
        sessionMetrics.setActiveRole(role.getName());
    }

    // ========================================================================
    // Permissions
    // ========================================================================

    private void handlePermissions(String rest) {
        if (rest.isBlank() || "list".equalsIgnoreCase(rest.trim())) {
            AgentConfig agent = agentRegistry.get(repl.getLocalAgentName());
            if (agent == null) {
                agent = agentRegistry.getDefault();
            }

            Map<String, String> descriptions = new TreeMap<>();
            for (CliTool tool : toolRegistry.all()) {
                String permissionKey = tool.permissionKey();
                if (permissionKey == null || permissionKey.isBlank()) {
                    continue;
                }
                String description = tool.description() == null
                        ? ""
                        : tool.description().replaceAll("\\s+", " ").trim();
                if (description.length() > 64) {
                    description = description.substring(0, 61) + "...";
                }
                descriptions.putIfAbsent(permissionKey, description);
            }
            descriptions.putIfAbsent("external_directory", "Access paths outside the working directory");

            List<String> permHeaders = List.of("Permission key", "Level", "Description");
            List<List<String>> permRows = new ArrayList<>();
            for (Map.Entry<String, String> entry : descriptions.entrySet()) {
                PermissionService.PermissionLevel level =
                        permissionService.getEffectiveLevel(agent, entry.getKey());
                String renderedLevel = switch (level) {
                    case ALLOW -> renderer.green("allow");
                    case DENY -> renderer.red("deny");
                    case ASK -> renderer.yellow("ask");
                };
                permRows.add(List.of(entry.getKey(), renderedLevel, entry.getValue()));
            }

            System.out.println(ascii.sectionHeader("Tool Permissions"));
            System.out.println(ascii.table(permHeaders, permRows));
            System.out.println();
            System.out.println(renderer.dim("  /permissions <key> <allow|deny|ask>"));
            System.out.println(renderer.dim("  /permissions allow-all   /permissions reset"));
            return;
        }

        String[] parts = rest.trim().split("\\s+", 2);
        if ("allow-all".equalsIgnoreCase(parts[0])) {
            permissionService.allowAll();
            System.out.println("All current and future tool permissions are allowed for this session.");
            return;
        }
        if ("reset".equalsIgnoreCase(parts[0])) {
            permissionService.resetSessionOverrides();
            System.out.println("Permission choices reset. MCP tools default to allow; explicit agent rules still apply.");
            return;
        }

        if (parts.length < 2) {
            System.out.println("Usage: /permissions <key> <allow|deny|ask>");
            return;
        }

        String key = parts[0];
        String level = parts[1].trim().toUpperCase(Locale.ROOT);
        try {
            PermissionService.PermissionLevel pl = PermissionService.PermissionLevel.valueOf(level);
            permissionService.setUserOverride(key, pl);
            System.out.println("Set " + key + " = " + level.toLowerCase(Locale.ROOT)
                    + " for this session.");
        } catch (IllegalArgumentException e) {
            System.err.println("Invalid level: " + parts[1] + ". Use allow, deny, or ask.");
        }
    }

    // ========================================================================
    // Todos and plan mode
    // ========================================================================

    private void showTodos() {
        List<TodoWriteTool.TodoItem> todos =
                TodoWriteTool.getTodos(sessionId, repl.getWorkingDirectory());
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

    // ========================================================================
    // MCP tool invocation
    // ========================================================================

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

    // ========================================================================
    // Status
    // ========================================================================

    private void printStatus() {
        LinkedHashMap<String, String> statusMap = new LinkedHashMap<>();

        ChatConfig chatConfig = repl.getChatConfig();

        if (localMode) {
            statusMap.put("Mode", renderer.cyan("local (direct LLM)"));
            if (chatConfig != null) {
                statusMap.put("Provider", chatConfig.getProvider());
                statusMap.put("Model", chatConfig.getModel());
                String model = chatConfig.getModel();
                boolean vision = ModelContextWindows.supportsVision(model);
                // Before the first turn only the catalog number is known; once the loop
                // has run, its budget also covers staged local models via the staging probe.
                int ctx = agenticLoop.conversationEntryCount() > 0
                        ? agenticLoop.contextWindowTokens()
                        : ModelContextWindows.getContextWindow(model);
                statusMap.put("Vision", vision ? renderer.green("supported") : renderer.dim("not supported"));
                statusMap.put("Context window", String.format("%,d tokens", ctx));
                long used = Math.max(agenticLoop.estimateConversationTokens(),
                        agenticLoop.lastReportedInputTokens());
                if (used > 0 && ctx > 0) {
                    long pct = Math.min(100, used * 100 / ctx);
                    String usage = String.format("~%,d tokens (%d%%)", used, pct);
                    statusMap.put("Context used", pct >= 80 ? renderer.yellow(usage
                            + " — /compact recommended") : usage);
                }
            }
            statusMap.put("Session", sessionId);
            statusMap.put("Local agent", renderer.cyan(repl.getLocalAgentName()));
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
            statusMap.put("Server", repl.getBaseUrl());
            statusMap.put("Session", sessionId);
            statusMap.put("Server agent", renderer.cyan(repl.getAgentName()));
            statusMap.put("Local agent", renderer.cyan(repl.getLocalAgentName()));
            statusMap.put("RAG", repl.isRagEnabled() ? renderer.green("enabled") : renderer.dim("disabled"));
            statusMap.put("Transcript", chatHistory.getTranscriptFile().toString());
            statusMap.put("Working dir", System.getProperty("user.dir"));
            List<McpSseClient.ToolInfo> cachedTools = repl.getCachedTools();
            if (cachedTools != null) {
                statusMap.put("MCP tools", cachedTools.size() + " available");
            }
            statusMap.put("Local tools", toolRegistry.ids().size() + " available");
        }

        System.out.println(ascii.panel("Status", ascii.keyValueList(statusMap), AsciiRenderer.ROUNDED, "blue"));
        System.out.println();
        ChatMemory chatMemory = repl.getChatMemory();
        if (chatMemory != null) {
            System.out.println(chatMemory.getStatus());
        }
    }

    // ========================================================================
    // History / sessions
    // ========================================================================

    private void showHistory() {
        try {
            ObjectNode args = objectMapper.createObjectNode();
            args.put("sessionId", sessionId);
            args.put("lastN", 20);
            String rawResult = mcpClient.callTool("get_chat_history", args);

            try {
                com.fasterxml.jackson.databind.JsonNode json = objectMapper.readTree(rawResult);
                com.fasterxml.jackson.databind.JsonNode messages = json.path("messages");
                int total = json.path("totalMessages").asInt(0);

                if (!messages.isArray() || messages.isEmpty()) {
                    System.out.println("No messages in server session.");
                    System.out.println("(Use /transcript to view the local conversation log)");
                    return;
                }

                System.out.println("Server history (" + messages.size() + " of " + total + " messages):");
                System.out.println("---");
                for (com.fasterxml.jackson.databind.JsonNode msg : messages) {
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

    private void copyLatestResponse(String rest) {
        if (rest != null && !rest.isBlank()) {
            ChatCompleter.printAbove("Usage: /copy");
            return;
        }
        Optional<String> latest = chatHistory.latestAssistantMessage();
        if (latest.isEmpty()) {
            ChatCompleter.printAbove("No assistant response is available to copy.");
            return;
        }
        boolean copied = ClipboardUtil.copyToClipboard(latest.get(), repl.getActiveTerminal());
        ChatCompleter.printAbove(copied
                ? "Copied the latest assistant response to the clipboard."
                : "Could not access a clipboard provider.");
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

    // ========================================================================
    // Compact
    // ========================================================================

    private void handleCompact(String focusInstruction) {
        if (!agenticLoop.supportsForceCompact()) {
            ChatCompleter.printAbove(renderer.yellow("  /compact requires local mode."));
            ChatCompleter.printAbove(renderer.dim("  In server mode, use /clear to reset the server session, "
                    + "or run kompile chat in local mode for LLM-based compaction."));
            return;
        }

        int entriesBefore = agenticLoop.conversationEntryCount();
        if (entriesBefore == 0) {
            ChatCompleter.printAbove(renderer.dim(
                    "  Nothing to compact — conversation is empty."));
            return;
        }

        // Dispatch through the turn lifecycle instead of running inline: the
        // reservation closes the race where a turn (foreground or backgrounded)
        // starts between this check and the LLM call, and the registered worker
        // stays interruptible — an inline reader-thread call deadlocks the
        // session because requestCancel() cannot reach it.
        final String focus = focusInstruction;
        boolean accepted = messageHandler.dispatchMaintenanceTurn(() -> {
            runCompactOnTurnThread(focus);
        }, "compact-dispatch");
        if (!accepted) {
            // printAbove, not System.out: the managed TUI owns the terminal, and
            // raw stdout paints straight over the user's input row.
            ChatCompleter.printAbove(renderer.yellow(
                    "  /compact is available after the active turn finishes."));
            ChatCompleter.printAbove(renderer.dim(
                    "  Mid-tool compaction would break provider function-call/result linkage."));
        }
    }

    private void runCompactOnTurnThread(String focusInstruction) {
        // Runs on the compact worker while the REPL prompt is live. All rendering
        // goes through CompactionProgressIndicator: one replaceable transcript
        // block in the managed TUI (animated in place, never inside the input
        // row) or a single-line fallback on unmanaged surfaces.
        int tokensBefore = agenticLoop.estimateConversationTokens();
        int entriesBefore = agenticLoop.conversationEntryCount();
        if (entriesBefore == 0) {
            ChatCompleter.printAbove(renderer.dim(
                    "  Nothing to compact — conversation is empty."));
            return;
        }

        CompactionProgressIndicator progress = null;
        try {
            progress = CompactionProgressIndicator.start(
                    "compact:" + System.nanoTime(), renderer, entriesBefore, tokensBefore,
                    ChatCompleter::printAbove,
                    label -> ChatCompleter.setActivity(label));
            progress.phase("Summarizing conversation");
            AgenticChatLoop.ForceCompactResult result =
                    agenticLoop.forceCompact(focusInstruction, progress);
            switch (result.getStatus()) {
                case OK:
                    progress.complete(result.getTokensBefore(), result.getTokensAfter(),
                            result.getPreservedTurns());
                    chatHistory.logSystem("Context compacted · portable history estimate: ~"
                            + result.getTokensBefore() + " → ~" + result.getTokensAfter()
                            + " tokens; provider context not measured.");
                    break;
                case NOOP:
                    progress.noop(result.getMessage());
                    break;
                case UNSUPPORTED:
                    progress.unsupported(result.getMessage());
                    break;
                case FAILED:
                    progress.failed(result.getMessage());
                    break;
            }
        } catch (Exception e) {
            String message = e.getMessage() == null || e.getMessage().isBlank()
                    ? e.getClass().getSimpleName() : e.getMessage();
            if (progress != null && !progress.isFinished()) {
                progress.failed("/compact failed: " + message);
            } else {
                ChatCompleter.printAbove(renderer.red("  ✗ /compact failed: " + message));
            }
        } finally {
            if (progress != null) {
                progress.abandonIfActive(null);
            }
        }
    }

    private void handleAutoCompact(String arguments) {
        ChatConfig config = repl.getChatConfig();
        if (!localMode || config == null || !agenticLoop.supportsForceCompact()) {
            ChatCompleter.printAbove(renderer.yellow("  /auto-compact requires standard local chat mode."));
            return;
        }

        String trimmed = arguments == null ? "" : arguments.trim();
        if (!trimmed.isEmpty() && !"status".equalsIgnoreCase(trimmed)) {
            String[] parts = trimmed.split("\\s+", 2);
            String action = parts[0].toLowerCase(Locale.ROOT);
            String value = parts.length > 1 ? parts[1].trim() : "";
            try {
                switch (action) {
                    case "on" -> config.setAutoCompactEnabled(true);
                    case "off" -> config.setAutoCompactEnabled(false);
                    case "threshold" -> config.setAutoCompactThreshold(parseCompactionThreshold(value));
                    case "reserve" -> config.setCompactionReserveTokens(parseTokenSetting(value));
                    case "context" -> config.setContextWindowTokens(parseTokenSetting(value));
                    case "output" -> config.setMaxOutputTokens(parseTokenSetting(value));
                    default -> {
                        printAutoCompactUsage();
                        return;
                    }
                }
                config.saveLoadedOrGlobal();
                agenticLoop.refreshCompactionPolicy();
            } catch (Exception e) {
                ChatCompleter.printAbove(renderer.red(
                        "  Could not update auto-compaction: " + e.getMessage()));
                printAutoCompactUsage();
                return;
            }
        } else {
            agenticLoop.refreshCompactionPolicy();
        }
        printAutoCompactStatus(config);
    }

    private double parseCompactionThreshold(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("threshold value is required");
        }
        String normalized = value.trim();
        boolean percent = normalized.endsWith("%");
        if (percent) normalized = normalized.substring(0, normalized.length() - 1).trim();
        double parsed = Double.parseDouble(normalized);
        if (percent || parsed > 1.0d) parsed /= 100.0d;
        if (!Double.isFinite(parsed) || parsed < 0.50d || parsed > 0.95d) {
            throw new IllegalArgumentException("threshold must be between 50% and 95%");
        }
        return parsed;
    }

    private int parseTokenSetting(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("token value is required");
        }
        if ("auto".equalsIgnoreCase(value.trim())) return 0;
        long parsed = Long.parseLong(value.trim().replace("_", ""));
        if (parsed <= 0L || parsed > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("token value must be positive or 'auto'");
        }
        return (int) parsed;
    }

    private void printAutoCompactStatus(ChatConfig config) {
        String state = agenticLoop.autoCompactEnabled() ? "enabled" : "disabled";
        ChatCompleter.printAbove(renderer.cyan("  Auto-compaction: ") + state);
        ChatCompleter.printAbove(renderer.dim("  strategy: "
                + agenticLoop.compactionStrategyDescription()));
        ChatCompleter.printAbove(renderer.dim(String.format(Locale.ROOT,
                "  active model limits: %,d context / %,d output tokens",
                agenticLoop.contextWindowTokens(), agenticLoop.maxOutputTokens())));
        ChatCompleter.printAbove(renderer.dim(String.format(Locale.ROOT,
                "  trigger: %,d tokens (%.0f%% ceiling, %,d reserved)",
                agenticLoop.compactionTriggerTokens(),
                agenticLoop.autoCompactThreshold() * 100.0d,
                agenticLoop.compactionReserveTokens())));
        ChatCompleter.printAbove(renderer.dim("  overrides: context="
                + tokenSetting(config.getContextWindowTokens()) + ", output="
                + tokenSetting(config.getMaxOutputTokens()) + ", reserve="
                + tokenSetting(config.getCompactionReserveTokens())));
    }

    private String tokenSetting(int value) {
        return value > 0 ? String.format(Locale.ROOT, "%,d", value) : "auto";
    }

    private void printAutoCompactUsage() {
        ChatCompleter.printAbove(renderer.dim("  Usage: /auto-compact status|on|off"));
        ChatCompleter.printAbove(renderer.dim("         /auto-compact threshold <50%-95%>"));
        ChatCompleter.printAbove(renderer.dim(
                "         /auto-compact reserve|context|output <tokens|auto>"));
    }

    // ========================================================================
    // RAG toggle
    // ========================================================================

    private void toggleRag(String arg) {
        String trimmed = arg.trim();
        if ("on".equalsIgnoreCase(trimmed)) {
            repl.setRagEnabled(true);
            updateSessionRag(true);
            chatHistory.logSystem("RAG enabled.");
            System.out.println("RAG enabled.");
        } else if ("off".equalsIgnoreCase(trimmed)) {
            repl.setRagEnabled(false);
            updateSessionRag(false);
            chatHistory.logSystem("RAG disabled.");
            System.out.println("RAG disabled.");
        } else {
            System.out.println("RAG is currently " + (repl.isRagEnabled() ? "enabled" : "disabled") + ".");
            System.out.println("Usage: /rag on|off");
        }
    }

    private String serverSystemPrompt(String customPrompt) {
        if (customPrompt != null) {
            serverCustomSystemPrompt = customPrompt.isBlank() ? null : customPrompt.strip();
        }
        String projectPrompt = agenticLoop.getProjectContextPrompt();
        String base = serverCustomSystemPrompt == null
                ? "You are a helpful AI assistant." : serverCustomSystemPrompt;
        return projectPrompt.isBlank() ? base : base + "\n\n" + projectPrompt;
    }

    private void updateSessionRag(boolean enabled) {
        try {
            ObjectNode args = objectMapper.createObjectNode();
            args.put("sessionId", sessionId);
            args.put("agentName", repl.getAgentName());
            args.put("enableRag", enabled);
            args.put("semanticK", 5);
            args.put("keywordK", 5);
            args.put("similarityThreshold", 0.5);
            args.put("systemPrompt", serverSystemPrompt(null));
            mcpClient.callTool("update_session_config", args);
        } catch (Exception e) {
            // Best effort
        }
    }

    // ========================================================================
    // Server agents
    // ========================================================================

    private void listAgents() {
        try {
            String rawResult = mcpClient.callTool("list_agents", (JsonNode) null);
            try {
                com.fasterxml.jackson.databind.JsonNode json = objectMapper.readTree(rawResult);
                com.fasterxml.jackson.databind.JsonNode agents = json.path("agents");
                if (!agents.isArray() || agents.isEmpty()) {
                    System.out.println("No server agents available.");
                    return;
                }
                System.out.println("Available server agents:");
                for (com.fasterxml.jackson.databind.JsonNode agent : agents) {
                    String name = agent.path("name").asText();
                    boolean available = agent.path("available").asBoolean(false);
                    boolean isDefault = agent.path("isDefault").asBoolean(false);
                    String status = available ? "available" : "unavailable";
                    String marker = isDefault ? " (default)" : "";
                    String active = name.equals(repl.getAgentName()) ? " *" : "";
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
            System.out.println("Current server agent: " + repl.getAgentName());
            System.out.println("Usage: /agent <name> (see /agents for available agents)");
            return;
        }

        String newAgent = name.trim();
        try {
            ObjectNode args = objectMapper.createObjectNode();
            args.put("sessionId", sessionId);
            args.put("agentName", newAgent);
            args.put("enableRag", repl.isRagEnabled());
            args.put("semanticK", 5);
            args.put("keywordK", 5);
            args.put("similarityThreshold", 0.5);
            args.put("systemPrompt", serverSystemPrompt(null));
            String rawResult = mcpClient.callTool("update_session_config", args);

            com.fasterxml.jackson.databind.JsonNode json = objectMapper.readTree(rawResult);
            if ("error".equals(json.path("status").asText())) {
                System.err.println("Error: " + json.path("error").asText());
                return;
            }

            repl.setAgentName(newAgent);
            chatHistory.logSystem("Switched to server agent: " + repl.getAgentName());
            System.out.println("Switched to server agent: " + repl.getAgentName());
        } catch (Exception e) {
            System.err.println("Error switching agent: " + e.getMessage());
        }
    }

    // ========================================================================
    // Config
    // ========================================================================

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
            args.put("agentName", repl.getAgentName());
            args.put("enableRag", repl.isRagEnabled());
            args.put("semanticK", 5);
            args.put("keywordK", 5);
            args.put("similarityThreshold", 0.5);
            args.put("systemPrompt", serverSystemPrompt(null));

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
                    args.put("systemPrompt", serverSystemPrompt(value));
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
                com.fasterxml.jackson.databind.JsonNode json = objectMapper.readTree(rawResult);
                com.fasterxml.jackson.databind.JsonNode config = json.path("configuration");
                if (config.isMissingNode()) {
                    System.out.println(rawResult);
                    return;
                }

                System.out.println("Session configuration:");
                System.out.println("  Session ID:           " + config.path("sessionId").asText(""));
                System.out.println("  Server agent:         " + config.path("agentName").asText(""));
                System.out.println("  Local agent:          " + repl.getLocalAgentName());
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
                com.fasterxml.jackson.databind.JsonNode json = objectMapper.readTree(rawResult);
                com.fasterxml.jackson.databind.JsonNode sessions = json.path("sessions");
                int count = json.path("sessionCount").asInt(0);

                if (!sessions.isArray() || sessions.isEmpty()) {
                    System.out.println("No active server chat sessions.");
                    return;
                }

                System.out.println("Active server sessions (" + count + "):");
                for (com.fasterxml.jackson.databind.JsonNode session : sessions) {
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

    // ========================================================================
    // Memory
    // ========================================================================

    private void handleMemory(String rest) {
        ChatMemory chatMemory = repl.getChatMemory();
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
        ChatMemory chatMemory = repl.getChatMemory();
        if (chatMemory == null) return;

        if (rest.isBlank()) {
            System.out.println("Usage: /recall <query>");
            System.out.println("Search across MEMORY.md, previous conversations, and RAG documents.");
            return;
        }

        System.out.println(chatMemory.search(rest.trim()));
    }

    // ========================================================================
    // Background tasks
    // ========================================================================

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
        MessageQueue messageQueue = queueManager.getMessageQueue();
        if (!messageQueue.isEmpty()) {
            body.append("\n");
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
            if (queueManager.isAutoDequeueEnabled()) {
                body.append(renderer.green("  ✓ Auto-dequeue ON")).append(renderer.dim(" — messages send automatically")).append("\n");
            } else {
                body.append(renderer.yellow("  ○ Auto-dequeue OFF")).append(renderer.dim(" — use /queue-send to send manually")).append("\n");
            }
        }

        // Queue chain progress
        if (backgroundTaskManager.isInQueueChain()) {
            body.append(renderer.dim("  Processing " + backgroundTaskManager.getQueueChainCurrent()
                    + "/" + backgroundTaskManager.getQueueChainTotal())).append("\n");
        }

        System.out.println(ascii.panel("Jobs & Queue", body.toString(), AsciiRenderer.ROUNDED, "cyan"));
        System.out.println();
        System.out.println(renderer.dim("  Escape              Cancel main operation; subagents keep running"));
        System.out.println(renderer.dim("  Ctrl+B              Background active subagent invocation"));
        System.out.println(renderer.dim("  Ctrl+X P            Toggle planning mode"));
        System.out.println(renderer.dim("  Ctrl+X T            Show todos"));
        System.out.println(renderer.dim("  Ctrl+X A            Cycle agent"));
        System.out.println(renderer.dim("  /jobs-remove <id>   Remove a completed task"));
        System.out.println(renderer.dim("  /jobs-clear         Clear all completed tasks"));
        System.out.println(renderer.dim("  /auto-dequeue       Toggle auto-send queued messages"));
        System.out.println();
    }

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

    private void clearCompletedBackgroundTasks() {
        backgroundTaskManager.clearCompletedTasks();
        System.out.println(renderer.green("Cleared completed tasks"));
    }

    // ========================================================================
    // Process management
    // ========================================================================

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

    private void showProcessOutput(String id) {
        if (id.isBlank()) {
            System.out.println("Usage: /process-output <id>");
            return;
        }
        String output = processManager.readOutput(id, 30);
        System.out.println(ascii.panel("Output: " + id, output, AsciiRenderer.ROUNDED, "cyan"));
        System.out.println();
    }

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
        body.append("Output File: ").append(entry.getOutputFile()).append("\n");
        body.append("\nRecent Output:\n").append(indentProcessOutput(processManager.readOutput(id, 10)));
        if (!entry.getMetadata().isEmpty()) {
            body.append("\nMetadata:\n");
            entry.getMetadata().forEach((key, value) ->
                    body.append("  ").append(key).append(": ").append(value).append("\n"));
        }
        System.out.println(ascii.panel("Process Status: " + id, body.toString(), AsciiRenderer.ROUNDED, "cyan"));
        System.out.println();
    }

    private void toggleStatusBar() {
        boolean newState = !statusBar.isEnabled();
        statusBar.setEnabled(newState);
        if (newState) {
            System.out.println(renderer.green("  ✓ Status bar enabled"));
        } else {
            System.out.println(renderer.yellow("  ○ Status bar disabled"));
        }
    }

    // ========================================================================
    // Utility
    // ========================================================================

    private static String indentProcessOutput(String output) {
        StringBuilder sb = new StringBuilder();
        String text = output == null || output.isBlank() ? "(no output captured yet)" : output;
        for (String line : text.split("\\R", -1)) {
            sb.append("  ").append(line).append("\n");
        }
        return sb.toString();
    }

    static String truncate(String s, int maxLen) {
        if (s == null) return "";
        return s.length() <= maxLen ? s : s.substring(0, maxLen - 3) + "...";
    }
}
