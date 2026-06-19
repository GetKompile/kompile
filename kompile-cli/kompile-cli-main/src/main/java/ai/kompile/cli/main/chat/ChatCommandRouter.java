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
import ai.kompile.cli.main.chat.agent.AgentRegistry;
import ai.kompile.cli.main.chat.agent.AgenticChatLoop;
import ai.kompile.cli.main.chat.config.ChatConfig;
import ai.kompile.cli.main.chat.config.DirectLlmClient;
import ai.kompile.cli.main.chat.config.ModelContextWindows;
import ai.kompile.cli.main.chat.config.SetupWizard;
import ai.kompile.cli.main.chat.enforcer.EnforcerConfig;
import ai.kompile.cli.main.chat.enforcer.EnforcerSetupWizard;
import ai.kompile.cli.main.chat.permission.PermissionService;
import ai.kompile.cli.main.chat.render.AsciiRenderer;
import ai.kompile.cli.main.chat.render.TerminalRenderer;
import ai.kompile.cli.main.chat.roles.RoleConfig;
import ai.kompile.cli.main.chat.roles.RoleManager;
import ai.kompile.cli.main.chat.roles.RoleWizard;
import ai.kompile.cli.main.chat.skill.SkillConfig;
import ai.kompile.cli.main.chat.skill.SkillRegistry;
import ai.kompile.cli.main.chat.tools.*;
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
            List<ChatRepl.PendingAttachment> pendingAttachments) {
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
    }

    /**
     * Routes a slash command to the appropriate handler.
     *
     * @param input the full command string including the leading /
     * @return false if the REPL should exit
     */
    public boolean handleSlashCommand(String input) {
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
                    messageHandler.agenticChat(rest);
                } else {
                    messageHandler.streamAgentChat(rest);
                }
                return true;

            case "/agent-chat":
                messageHandler.agenticChat(rest);
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

            case "/queue-clear":
                queueManager.clearQueuedMessages();
                return true;

            case "/queue-status":
                queueManager.showQueueStatus();
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
    // Help
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

    // ========================================================================
    // Setup / config
    // ========================================================================

    private void runSetup() {
        ChatConfig newConfig = SetupWizard.run();
        if (newConfig != null) {
            repl.updateChatConfig(newConfig);
            System.out.println(renderer.green("Configuration updated. New messages will use the updated settings."));
            if (localMode) {
                System.out.println(renderer.dim("Note: restart the chat to fully apply the new configuration."));
            }
        } else {
            System.out.println("Setup cancelled.");
        }
    }

    // ========================================================================
    // Enforcer
    // ========================================================================

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
                    repl.loadInlineEnforcer(wd);
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
                repl.loadInlineEnforcer(wd);
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
            AgentConfig agent = agentRegistry.get(repl.getLocalAgentName());
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

        String provider = chatConfig.getProvider();
        String currentModel = chatConfig.getModel();

        // Get all models for the provider
        String[] providerModels = ChatConfig.getDefaultModels(provider);

        // Get the active agent's allowed models
        AgentConfig activeAgent = agentRegistry.get(repl.getLocalAgentName());
        if (activeAgent == null) activeAgent = agentRegistry.getDefault();
        List<String> allowedModels = activeAgent.getAllowedModels();

        if (rest.isEmpty()) {
            // Show current model and list available models
            System.out.println(ascii.sectionHeader("Model"));
            System.out.println("  Current model: " + renderer.cyan(currentModel));
            System.out.println("  Provider:      " + renderer.dim(provider));
            System.out.println("  Agent:         " + renderer.dim(repl.getLocalAgentName()));
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
                    repl.getLocalAgentName() + "'."));
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

    private void executeSkill(SkillConfig skill, String args) {
        String expandedPrompt = skill.expandTemplate(args);
        String taggedPrompt = "<skill name=\"" + skill.getName() + "\">\n" + expandedPrompt + "\n</skill>";
        chatHistory.logSystem("Executing skill: /" + skill.getName() + (args.isBlank() ? "" : " " + args));
        messageHandler.handleChatMessage(taggedPrompt);
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

    // ========================================================================
    // Todos and plan mode
    // ========================================================================

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
                int ctx = ModelContextWindows.getContextWindow(model);
                statusMap.put("Vision", vision ? renderer.green("supported") : renderer.dim("not supported"));
                statusMap.put("Context window", String.format("%,d tokens", ctx));
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

    // ========================================================================
    // Compact
    // ========================================================================

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

    private void updateSessionRag(boolean enabled) {
        try {
            ObjectNode args = objectMapper.createObjectNode();
            args.put("sessionId", sessionId);
            args.put("agentName", repl.getAgentName());
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
            args.put("systemPrompt", "");
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
            if (queueManager.isAutoDequeueEnabled()) {
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

    static String truncate(String s, int maxLen) {
        if (s == null) return "";
        return s.length() <= maxLen ? s : s.substring(0, maxLen - 3) + "...";
    }
}
