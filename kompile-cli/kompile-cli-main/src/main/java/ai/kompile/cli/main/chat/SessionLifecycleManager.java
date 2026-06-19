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

import ai.kompile.cli.main.chat.agent.AgentRegistry;
import ai.kompile.cli.main.chat.agent.AgenticChatLoop;
import ai.kompile.cli.main.chat.config.ChatConfig;
import ai.kompile.cli.main.chat.render.AsciiRenderer;
import ai.kompile.cli.main.chat.render.TerminalRenderer;
import ai.kompile.cli.main.chat.tools.TaskTool;
import ai.kompile.cli.main.chat.tools.CliTool;
import ai.kompile.cli.main.chat.tools.ToolRegistry;

import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Manages session lifecycle: restore, summary, mode switching, passthrough/resume tools.
 * Extracted from ChatRepl to reduce its size.
 */
public class SessionLifecycleManager {

    private final ChatRepl repl;
    private final String sessionId;
    private final boolean localMode;
    private final ChatHistory chatHistory;
    private final ChatSessionMetrics sessionMetrics;
    private final TerminalRenderer renderer;
    private final AsciiRenderer ascii;
    private final AgenticChatLoop agenticLoop;
    private final AgentRegistry agentRegistry;
    private final ToolRegistry toolRegistry;

    public SessionLifecycleManager(
            ChatRepl repl,
            String sessionId,
            boolean localMode,
            ChatHistory chatHistory,
            ChatSessionMetrics sessionMetrics,
            TerminalRenderer renderer,
            AsciiRenderer ascii,
            AgenticChatLoop agenticLoop,
            AgentRegistry agentRegistry,
            ToolRegistry toolRegistry) {
        this.repl = repl;
        this.sessionId = sessionId;
        this.localMode = localMode;
        this.chatHistory = chatHistory;
        this.sessionMetrics = sessionMetrics;
        this.renderer = renderer;
        this.ascii = ascii;
        this.agenticLoop = agenticLoop;
        this.agentRegistry = agentRegistry;
        this.toolRegistry = toolRegistry;
    }

    // ========================================================================
    // Session restore
    // ========================================================================

    /**
     * Restore a previous conversation session by replaying transcript turns
     * into the DirectLlmClient history (local mode) or printing the transcript (server mode).
     */
    public void restoreSession() {
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
    public void validateAndReportSubagentAvailability() {
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

    // ========================================================================
    // Session summary
    // ========================================================================

    /**
     * Prints comprehensive session summary on exit.
     * Inspired by Claude Code /cost, Codex CLI stats, and Aider session metrics.
     */
    public void printSessionSummary() {
        java.time.Duration duration = sessionMetrics.getSessionDuration();
        StringBuilder body = new StringBuilder();

        // Session info
        body.append(renderer.bold("Session")).append("\n");
        body.append("  ID:        ").append(sessionId).append("\n");
        body.append("  Duration:  ").append(sessionMetrics.formatDuration(duration)).append("\n");
        ChatConfig chatConfig = repl.getChatConfig();
        if (localMode && chatConfig != null) {
            body.append("  Provider:  ").append(chatConfig.getProvider()).append("/").append(chatConfig.getModel()).append("\n");
        }
        body.append("  Agent:     ").append(repl.getAgentName());
        if (localMode) body.append(" (local)");
        body.append("\n");
        body.append("  RAG:       ").append(repl.isRagEnabled() ? "enabled" : "disabled").append("\n");

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

    public String formatNumber(long n) {
        if (n < 1000) return String.valueOf(n);
        return String.format("%,d", n);
    }

    // ========================================================================
    // Main menu
    // ========================================================================

    /**
     * Show a quick menu to switch between modes from within chat.
     */
    public void showMainMenu() {
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
                    repl.runSetupFromMenu();
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
    public void launchPassthroughMode(String agentArg) {
        try {
            ChatConfig chatConfig = repl.getChatConfig();
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
            if (!localMode && repl.getBaseUrl() != null) {
                passthrough.kompileUrl = repl.getBaseUrl();
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
    public void launchResumeTool(String args) {
        try {
            System.out.println();
            System.out.println(renderer.dim("  Launching resume tool..."));
            System.out.println(renderer.dim("  Browse, search, and resume past conversations."));
            System.out.println(renderer.dim("  Return to chat when done."));
            System.out.println();

            // Create and execute ResumeTool
            ai.kompile.cli.main.chat.tools.ResumeTool resumeTool = new ai.kompile.cli.main.chat.tools.ResumeTool();

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
    public void handleModeSwitch(String mode) {
        if (mode == null || mode.isBlank()) {
            ChatConfig chatConfig = repl.getChatConfig();
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
