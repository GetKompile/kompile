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

package ai.kompile.cli.main.chat.agent;

import ai.kompile.cli.main.chat.config.DirectLlmClient;
import ai.kompile.cli.main.chat.harness.PerformanceHarness;
import ai.kompile.cli.main.chat.permission.PermissionService;
import ai.kompile.cli.main.chat.render.AsciiRenderer;
import ai.kompile.cli.main.chat.render.CompactionService;
import ai.kompile.cli.main.chat.render.ConversationSummarizer;
import ai.kompile.cli.main.chat.render.OutputTruncator;
import ai.kompile.cli.main.chat.render.StreamingMarkdownRenderer;
import ai.kompile.cli.main.chat.render.TerminalRenderer;
import ai.kompile.cli.main.chat.tui.SidePanelManager;
import ai.kompile.cli.main.chat.tools.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Agentic chat loop with proper terminal rendering, output truncation,
 * and context compaction. Comparable to OpenCode's SessionPrompt.loop().
 * <p>
 * Supports two backends:
 * <ul>
 *   <li><b>Server mode</b>: Streams via kompile-app /api/agents/chat/stream endpoint</li>
 *   <li><b>Direct mode</b>: Calls LLM APIs directly via DirectLlmClient (no server needed)</li>
 * </ul>
 * <p>
 * Flow:
 * 1. Send user message + tool definitions to agent
 * 2. Stream response, collecting text + tool call requests
 * 3. Execute requested tools locally with spinner + colored output
 * 4. Send tool results back as follow-up message
 * 5. Repeat until agent returns text-only response (no tool calls)
 */
public class AgenticChatLoop {

    private static final Set<String> READ_ONLY_TOOLS = Set.of(
            "read", "grep", "glob", "list", "todoread", "webfetch",
            "transcript_search", "rag_search", "graph_search");

    private final String baseUrl; // null for direct mode
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final ToolRegistry toolRegistry;
    private final PermissionService permissionService;
    private final AgentRegistry agentRegistry;
    private final Path workingDirectory;
    private final TerminalRenderer renderer;
    private final AsciiRenderer asciiRenderer;
    private final OutputTruncator truncator;
    private final CompactionService compactionService;
    private final DirectLlmClient directLlmClient; // null for server mode
    private final String agentsMdContent; // loaded AGENTS.md content
    private final ai.kompile.cli.main.chat.tools.BackgroundProcessManager processManager; // background process tracking
    private ToolResultStore toolResultStore; // persists tool outputs to disk
    private ai.kompile.cli.main.chat.ChatSessionMetrics sessionMetrics; // session metrics
    private volatile PerformanceHarness performanceHarness; // optional multi-signal agent evaluation
    private volatile AgentConfig currentAgentConfig; // currently active agent config (can be updated with roles)
    private final SidePanelManager sidePanelManager;
    private long lastRenderedSidePanelVersion = -1;

    // Planning mode state
    private volatile boolean planningMode = false;
    private ExitPlanModeTool exitPlanModeTool;

    // Conversation history for compaction
    private final List<CompactionService.ConversationEntry> conversationHistory = new ArrayList<>();

    // Cancel signal - set by ChatRepl when user presses Escape
    private volatile AtomicBoolean cancelSignal;

    // Pending attachments for the next chat turn (consumed on first direct-mode call)
    private volatile List<DirectLlmClient.AttachmentInput> pendingAttachments;

    // Callback fired once when the first output (step indicator or text chunk) is printed.
    // Used by ChatRepl to stop the generating spinner.
    private volatile Runnable onFirstOutput;

    // Inline enforcer: keyword-based rule checker applied to every turn in the chat REPL.
    // Auto-loaded from .kompile/enforcer-config.json when present. Toggle with /enforcer on|off.
    private volatile ai.kompile.cli.main.chat.enforcer.KeywordEnforcerEvaluator inlineEnforcer;
    private volatile ai.kompile.cli.main.chat.enforcer.EnforcerPolicy inlineEnforcerPolicy;
    private volatile boolean inlineEnforcerEnabled = false;
    private int inlineEnforcerMaxCorrections = 3;

    /**
     * Server mode constructor - uses kompile-app REST endpoint.
     */
    public AgenticChatLoop(String baseUrl, ObjectMapper objectMapper,
                            ToolRegistry toolRegistry, PermissionService permissionService,
                            AgentRegistry agentRegistry, Path workingDirectory) {
        this(baseUrl, objectMapper, toolRegistry, permissionService,
                agentRegistry, workingDirectory, null, null);
    }

    /**
     * Dual mode constructor - uses DirectLlmClient when baseUrl is null.
     */
    public AgenticChatLoop(String baseUrl, ObjectMapper objectMapper,
                            ToolRegistry toolRegistry, PermissionService permissionService,
                            AgentRegistry agentRegistry, Path workingDirectory,
                            DirectLlmClient directLlmClient) {
        this(baseUrl, objectMapper, toolRegistry, permissionService,
                agentRegistry, workingDirectory, directLlmClient, null);
    }

    /**
     * Full constructor with background process manager.
     */
    public AgenticChatLoop(String baseUrl, ObjectMapper objectMapper,
                            ToolRegistry toolRegistry, PermissionService permissionService,
                            AgentRegistry agentRegistry, Path workingDirectory,
                            DirectLlmClient directLlmClient,
                            ai.kompile.cli.main.chat.tools.BackgroundProcessManager processManager) {
        this.baseUrl = baseUrl;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        this.objectMapper = objectMapper;
        this.toolRegistry = toolRegistry;
        this.permissionService = permissionService;
        this.agentRegistry = agentRegistry;
        this.workingDirectory = workingDirectory;
        this.renderer = new TerminalRenderer();
        this.asciiRenderer = new AsciiRenderer(this.renderer);
        this.truncator = new OutputTruncator();
        this.compactionService = new CompactionService(objectMapper);
        this.directLlmClient = directLlmClient;
        this.processManager = processManager;
        this.sidePanelManager = findSidePanelManager(toolRegistry);

        // Set up exit callback for background process notifications
        if (processManager != null) {
            final TerminalRenderer r = this.renderer;
            processManager.setExitCallback(entry -> {
                String durationStr = ai.kompile.cli.main.chat.tools.ProcessManagementTool.formatDuration(
                        entry.getDuration());
                String desc = entry.getDescription() != null ? entry.getDescription() : entry.getCommand();
                int code = entry.getExitCode() != null ? entry.getExitCode() : -1;

                String notification;
                if (code == 0) {
                    notification = r.dim("[process:" + entry.getId() + "] exited with code 0 (took "
                            + durationStr + ") — \"" + desc + "\"");
                } else {
                    notification = r.red("[process:" + entry.getId() + "] exited with code " + code
                            + " (took " + durationStr + ") — \"" + desc + "\"");
                }
                System.out.println();
                System.out.println(notification);
                System.out.println();
            });
        }

        // Initialize current agent config with default
        this.currentAgentConfig = agentRegistry.getDefault();

        // Load AGENTS.md files from project hierarchy
        AgentsMdLoader loader = new AgentsMdLoader(workingDirectory);
        this.agentsMdContent = loader.load();
    }

    private SidePanelManager findSidePanelManager(ToolRegistry registry) {
        CliTool sidePanelTool = registry != null ? registry.get("side_panel") : null;
        if (sidePanelTool instanceof SidePanelTool tool) {
            return tool.getSidePanelManager();
        }
        return null;
    }

    /**
     * Sets the session metrics tracker for recording tool calls, steps, and token usage.
     */
    public void setSessionMetrics(ai.kompile.cli.main.chat.ChatSessionMetrics metrics) {
        this.sessionMetrics = metrics;
    }

    /**
     * Sets the performance harness for multi-signal agent evaluation.
     * When set, each completed turn is evaluated asynchronously (escape detection,
     * judge LLM, thinking analysis) and scores feed into model routing.
     */
    public void setPerformanceHarness(PerformanceHarness harness) {
        this.performanceHarness = harness;
    }

    /**
     * Sets a callback to be fired once when the first output is produced.
     * The callback is consumed after firing (set to null).
     */
    public void setOnFirstOutput(Runnable onFirstOutput) {
        this.onFirstOutput = onFirstOutput;
    }

    /**
     * Set pending attachments for the next chat turn.
     * They will be consumed (sent once) on the first direct-mode LLM call.
     */
    public void setPendingAttachments(List<DirectLlmClient.AttachmentInput> attachments) {
        this.pendingAttachments = attachments;
    }

    /**
     * Fire the onFirstOutput callback if set, then clear it.
     */
    private void fireFirstOutput() {
        Runnable cb = this.onFirstOutput;
        if (cb != null) {
            this.onFirstOutput = null;
            cb.run();
        }
    }

    /**
     * Sets the cancel signal for interrupting in-progress operations.
     * The signal is checked between agentic steps and during LLM streaming.
     */
    public void setCancelSignal(AtomicBoolean cancelSignal) {
        this.cancelSignal = cancelSignal;
        if (directLlmClient != null) {
            directLlmClient.setCancelSignal(cancelSignal);
        }
    }

    /**
     * Updates the current agent configuration (e.g., when a role is assigned).
     */
    public void setAgentConfig(AgentConfig agentConfig) {
        this.currentAgentConfig = agentConfig;
    }

    /**
     * Gets the current agent configuration.
     */
    public AgentConfig getCurrentAgentConfig() {
        return currentAgentConfig;
    }

    /**
     * Enable or disable planning mode. When enabled, the agent first runs a
     * read-only planning pass (planner agent), then asks the user to approve
     * before executing with the coder agent.
     */
    public void setPlanningMode(boolean planningMode) {
        this.planningMode = planningMode;
        if (planningMode) {
            this.exitPlanModeTool = new ExitPlanModeTool();
            toolRegistry.register(exitPlanModeTool);
        } else if (exitPlanModeTool != null) {
            toolRegistry.unregister("exit_plan_mode");
            exitPlanModeTool = null;
        }
    }

    /**
     * Whether planning mode is currently active.
     */
    public boolean isPlanningMode() {
        return planningMode;
    }

    // ── Inline enforcer ─────────────────────────────────────────────────────

    /**
     * Set the inline enforcer for this chat loop. When enabled, every LLM response
     * is checked against keyword rules before being accepted. Violations trigger
     * automatic correction attempts.
     */
    public void setInlineEnforcer(ai.kompile.cli.main.chat.enforcer.KeywordEnforcerEvaluator evaluator,
                                   ai.kompile.cli.main.chat.enforcer.EnforcerPolicy policy,
                                   int maxCorrections) {
        this.inlineEnforcer = evaluator;
        this.inlineEnforcerPolicy = policy;
        this.inlineEnforcerMaxCorrections = maxCorrections;
        this.inlineEnforcerEnabled = evaluator != null && evaluator.isAvailable();
    }

    /**
     * Toggle enforcer on/off without changing configuration.
     */
    public void setInlineEnforcerEnabled(boolean enabled) {
        this.inlineEnforcerEnabled = enabled && inlineEnforcer != null;
    }

    public boolean isInlineEnforcerEnabled() {
        return inlineEnforcerEnabled;
    }

    public String describeInlineEnforcer() {
        if (inlineEnforcer == null) return null;
        return inlineEnforcer.describe();
    }

    /**
     * Check if cancellation has been requested.
     */
    private boolean isCancelled() {
        AtomicBoolean signal = this.cancelSignal;
        return signal != null && signal.get();
    }

    /**
     * Whether this loop operates in direct LLM mode (no server).
     */
    public boolean isDirectMode() {
        return directLlmClient != null && (baseUrl == null || baseUrl.isEmpty());
    }

    /**
     * Build the full system prompt by combining the agent's base prompt
     * with AGENTS.md content and tool result store info.
     */
    private String buildSystemPrompt(AgentConfig agent) {
        StringBuilder sb = new StringBuilder();

        String base = agent.getSystemPrompt();
        if (base != null && !base.isBlank()) {
            sb.append(base.strip());
        }

        // Tool result store info
        if (toolResultStore != null) {
            sb.append("\n\n# Tool Result Files\n\n");
            sb.append("All tool call outputs are saved to: ").append(toolResultStore.getResultDir()).append("\n");
            sb.append("After context compaction, you can use the `read` tool to access any previous tool output.\n");
            sb.append("Compacted tool results include the file path — use `read` with that path to get the full output.\n");
            sb.append("Use `glob` with pattern \"").append(toolResultStore.getResultDir()).append("/*.txt\" to list all saved results.\n");

            // If there are already saved results, include a summary
            String summary = toolResultStore.generateResultsSummary();
            if (!summary.isEmpty()) {
                sb.append("\n").append(summary);
            }
        }

        // AGENTS.md content
        if (agentsMdContent != null && !agentsMdContent.isEmpty()) {
            sb.append("\n\n# Project Instructions (from AGENTS.md)\n\n");
            sb.append(agentsMdContent);
        }

        return sb.toString();
    }

    /**
     * Get the loaded AGENTS.md content (for display by /help or /config).
     */
    public String getAgentsMdContent() {
        return agentsMdContent;
    }

    /**
     * Restore conversation history from previous session turns.
     * Replays turns into the DirectLlmClient and conversation history.
     */
    public void restoreHistory(java.util.List<ai.kompile.cli.main.chat.ChatHistory.Turn> turns) {
        if (directLlmClient != null) {
            for (var turn : turns) {
                directLlmClient.addToHistory(turn.role(), turn.content());
            }
        }
        // Also restore into conversation history for compaction tracking
        for (var turn : turns) {
            if ("user".equals(turn.role())) {
                conversationHistory.add(CompactionService.ConversationEntry.user(turn.content()));
            } else if ("assistant".equals(turn.role())) {
                conversationHistory.add(CompactionService.ConversationEntry.assistant(turn.content()));
            }
        }
    }

    /**
     * Current estimated token count of the tracked conversation history.
     * Uses the heuristic char/4 estimate from CompactionService.
     */
    public int estimateConversationTokens() {
        return compactionService.estimateTokens(conversationHistory);
    }

    /**
     * Number of tracked conversation entries (user, assistant, tool calls, tool results).
     */
    public int conversationEntryCount() {
        return conversationHistory.size();
    }

    /**
     * Whether LLM-driven compaction is available. Requires a DirectLlmClient
     * (local mode); in server mode the CLI does not hold LLM credentials.
     */
    public boolean supportsForceCompact() {
        return directLlmClient != null;
    }

    /**
     * Force a manual LLM-based compaction of the conversation. Produces a
     * structured summary via the configured LLM, then replaces the history
     * (both this loop's tracked history and the DirectLlmClient's own
     * message list) with that summary.
     * <p>
     * Recent turns are preserved in full to keep continuity: the last two
     * user turns plus any assistant response immediately following them
     * survive compaction intact.
     *
     * @param focusInstruction optional user focus hint (e.g. "preserve API
     *                          changes"). Null/blank is fine.
     * @return result describing tokens before/after and the summary text
     */
    public ForceCompactResult forceCompact(String focusInstruction) {
        if (directLlmClient == null) {
            return ForceCompactResult.unsupported(
                    "LLM-based /compact requires local mode (no DirectLlmClient configured).");
        }
        if (conversationHistory.isEmpty()) {
            return ForceCompactResult.noop("Nothing to compact — conversation is empty.");
        }

        int tokensBefore = compactionService.estimateTokens(conversationHistory);

        // Split off recent turns to preserve after summarization
        int preserveIndex = findRecentPreservationCutoff(conversationHistory);
        List<CompactionService.ConversationEntry> toSummarize =
                new ArrayList<>(conversationHistory.subList(0, preserveIndex));
        List<CompactionService.ConversationEntry> toPreserve =
                new ArrayList<>(conversationHistory.subList(preserveIndex, conversationHistory.size()));

        if (toSummarize.isEmpty()) {
            return ForceCompactResult.noop(
                    "Nothing to compact — all turns are within the preserved recent window.");
        }

        ConversationSummarizer summarizer = new ConversationSummarizer(directLlmClient);
        String modelOverride = currentAgentConfig != null ? currentAgentConfig.getModelOverride() : null;

        ConversationSummarizer.SummaryResult summary =
                summarizer.summarize(toSummarize, focusInstruction, modelOverride);

        if (summary.isEmpty()) {
            return ForceCompactResult.failed("Summarization returned empty output; history unchanged.");
        }

        // Rebuild conversationHistory: summary as a system entry + preserved tail
        conversationHistory.clear();
        conversationHistory.add(CompactionService.ConversationEntry.system(
                "[Compacted summary of prior conversation]\n" + summary.getSummary()));
        conversationHistory.addAll(toPreserve);

        // Rebuild DirectLlmClient's message list so the next LLM call sends
        // the compacted summary instead of the full prior history
        directLlmClient.replaceHistoryWithSummary(summary.getSummary());
        // Replay preserved tail into DirectLlmClient so recent context is
        // still visible to the model on the next turn
        for (CompactionService.ConversationEntry entry : toPreserve) {
            if (entry.type == CompactionService.EntryType.USER) {
                directLlmClient.addToHistory("user", entry.content);
            } else if (entry.type == CompactionService.EntryType.ASSISTANT) {
                directLlmClient.addToHistory("assistant", entry.content);
            }
            // Tool calls/results in the preserved window are dropped from the
            // DirectLlmClient replay: they are paired and reconstructing the
            // exact tool_use/tool_result wiring post-compaction is fragile.
            // The structured summary already captures what those tools did.
        }

        int tokensAfter = compactionService.estimateTokens(conversationHistory);

        if (sessionMetrics != null) {
            sessionMetrics.recordCompaction(tokensBefore, tokensAfter);
            sessionMetrics.recordTokenUsage(
                    summary.getInputTokens(), summary.getOutputTokens(), 0, 0);
        }

        return ForceCompactResult.ok(tokensBefore, tokensAfter, toPreserve.size(),
                summary.getSummary());
    }

    /**
     * Find the index at which to split history: entries before this index
     * are summarized, entries from this index onward are preserved in full.
     * Strategy: preserve the last user turn plus any trailing assistant/tool
     * entries, so the summary boundary never cuts mid-exchange.
     */
    private int findRecentPreservationCutoff(
            List<CompactionService.ConversationEntry> entries) {
        // Walk backward to find the start of the most recent user turn.
        for (int i = entries.size() - 1; i >= 0; i--) {
            if (entries.get(i).type == CompactionService.EntryType.USER) {
                return i;
            }
        }
        // No user turns found — summarize everything.
        return entries.size();
    }

    /**
     * Result of a forced compaction invocation.
     */
    public static class ForceCompactResult {
        public enum Status { OK, NOOP, UNSUPPORTED, FAILED }

        private final Status status;
        private final String message;
        private final int tokensBefore;
        private final int tokensAfter;
        private final int preservedTurns;
        private final String summary;

        private ForceCompactResult(Status status, String message, int tokensBefore,
                                   int tokensAfter, int preservedTurns, String summary) {
            this.status = status;
            this.message = message;
            this.tokensBefore = tokensBefore;
            this.tokensAfter = tokensAfter;
            this.preservedTurns = preservedTurns;
            this.summary = summary;
        }

        public static ForceCompactResult ok(int before, int after, int preserved, String summary) {
            return new ForceCompactResult(Status.OK, null, before, after, preserved, summary);
        }

        public static ForceCompactResult noop(String msg) {
            return new ForceCompactResult(Status.NOOP, msg, 0, 0, 0, null);
        }

        public static ForceCompactResult unsupported(String msg) {
            return new ForceCompactResult(Status.UNSUPPORTED, msg, 0, 0, 0, null);
        }

        public static ForceCompactResult failed(String msg) {
            return new ForceCompactResult(Status.FAILED, msg, 0, 0, 0, null);
        }

        public Status getStatus() { return status; }
        public String getMessage() { return message; }
        public int getTokensBefore() { return tokensBefore; }
        public int getTokensAfter() { return tokensAfter; }
        public int getPreservedTurns() { return preservedTurns; }
        public String getSummary() { return summary; }
        public boolean isSuccess() { return status == Status.OK; }
    }

    /**
     * Run the agentic chat loop for a single user message.
     * When planning mode is active, first runs a read-only planning pass,
     * then returns the plan for user approval before executing.
     */
    public String chat(String message, String sessionId, String agentName,
                        String serverAgent, boolean ragEnabled) {
        if (planningMode && exitPlanModeTool != null) {
            return chatWithPlanning(message, sessionId, agentName, serverAgent, ragEnabled);
        }

        return chatInternal(message, sessionId, agentName, serverAgent, ragEnabled);
    }

    private String chatWithPlanning(String message, String sessionId, String agentName,
                                     String serverAgent, boolean ragEnabled) {
        exitPlanModeTool.reset();

        // Phase 1: Planning pass with planner agent
        AgentConfig plannerAgent = agentRegistry.get("planner");
        if (plannerAgent == null) {
            plannerAgent = agentRegistry.getDefault();
        }

        System.out.println(renderer.bold(renderer.cyan("  ╭─ Planning Mode ─────────────────────────────────────╮")));
        System.out.println(renderer.cyan("  │") + renderer.dim("  Analyzing task with read-only tools...              ") + renderer.cyan("│"));
        System.out.println(renderer.cyan("  │") + renderer.dim("  Call exit_plan_mode when plan is ready.             ") + renderer.cyan("│"));
        System.out.println(renderer.bold(renderer.cyan("  ╰───────────────────────────────────────────────────────╯")));
        System.out.println();

        String planResponse = chatInternal(message, sessionId, "planner", serverAgent, ragEnabled);

        // Show the checklist after planning
        List<TodoWriteTool.TodoItem> todos = TodoWriteTool.getTodos(sessionId);
        if (!todos.isEmpty()) {
            System.out.println();
            System.out.println(renderer.bold(renderer.cyan("  ── Plan Checklist ──")));
            System.out.println(renderer.renderTodoList(todos));
            System.out.println();
        }

        // Check if plan was approved via exit_plan_mode tool
        if (exitPlanModeTool.isPlanApproved()) {
            System.out.println(renderer.bold(renderer.yellow("  Plan ready for approval.")));
            System.out.println(renderer.dim("  The agent will now proceed with execution."));
            System.out.println();

            // Phase 2: Execution pass with coder agent
            System.out.println(renderer.bold(renderer.green("  ╭─ Execution Mode ────────────────────────────────────╮")));
            System.out.println(renderer.green("  │") + renderer.dim("  Executing plan with full tool access...             ") + renderer.green("│"));
            System.out.println(renderer.bold(renderer.green("  ╰───────────────────────────────────────────────────────╯")));
            System.out.println();

            String executionPrompt = "Execute the plan you just created. "
                    + "Update each task status as you complete it using todowrite. "
                    + "Here was the plan:\n\n" + planResponse;
            String executionResponse = chatInternal(executionPrompt, sessionId, agentName, serverAgent, ragEnabled);

            return planResponse + "\n\n--- Execution ---\n\n" + executionResponse;
        }

        return planResponse;
    }

    private String chatInternal(String message, String sessionId, String agentName,
                                 String serverAgent, boolean ragEnabled) {
        long turnStartMs = System.currentTimeMillis();
        AgentConfig agent = agentRegistry.get(agentName);
        if (agent == null) agent = agentRegistry.getDefault();

        // Initialize tool result store for this session
        this.toolResultStore = new ToolResultStore(sessionId);

        ToolContext toolContext = new ToolContext(
                sessionId, agent, permissionService, workingDirectory, toolRegistry);

        ArrayNode toolDefs = toolRegistry.buildToolDefinitions(agent);

        // Compose system prompt: agent prompt + AGENTS.md content + result store info
        String systemPrompt = buildSystemPrompt(agent);

        StringBuilder fullResponse = new StringBuilder();
        int step = 0;
        int maxSteps = agent.getMaxSteps();

        String currentMessage = message;
        List<ToolCallResult> pendingToolResults = null;

        // Track conversation for compaction
        conversationHistory.add(CompactionService.ConversationEntry.user(message));

        while (step < maxSteps) {
            // Check cancellation before each step
            if (isCancelled()) {
                System.out.println("\n" + renderer.yellow("  ⊘ Cancelled"));
                fullResponse.append("\n[Cancelled by user]");
                break;
            }

            step++;
            if (sessionMetrics != null) sessionMetrics.recordAgenticStep();
            fireFirstOutput();
            System.out.println(renderer.renderAgentTurnStart(step, maxSteps));

            // Check compaction
            if (compactionService.needsCompaction(conversationHistory)) {
                CompactionService.CompactionResult compResult =
                        compactionService.compact(conversationHistory);
                if (compResult.isCompacted()) {
                    System.out.println(renderer.renderCompactionNotice(
                            compResult.getTokensBefore(), compResult.getTokensAfter()));
                    if (sessionMetrics != null) {
                        sessionMetrics.recordCompaction(compResult.getTokensBefore(), compResult.getTokensAfter());
                    }
                    conversationHistory.clear();
                    conversationHistory.addAll(compResult.getEntries());
                }
            }

            StreamResult result;
            if (isDirectMode()) {
                result = streamDirectTurn(
                        currentMessage, systemPrompt, toolDefs, pendingToolResults,
                        agent.getModelOverride());
            } else {
                result = streamServerTurn(
                        currentMessage, sessionId, serverAgent, ragEnabled,
                        systemPrompt, toolDefs, pendingToolResults);
            }

            // Check if cancelled during streaming
            if (isCancelled()) {
                if (!result.text.isEmpty()) {
                    fullResponse.append(result.text);
                }
                System.out.println("\n" + renderer.yellow("  ⊘ Cancelled"));
                fullResponse.append("\n[Cancelled by user]");
                break;
            }

            // Accumulate text output
            if (!result.text.isEmpty()) {
                fullResponse.append(result.text);
                conversationHistory.add(
                        CompactionService.ConversationEntry.assistant(result.text));
            }

            // ── Inline enforcer check ─────────────────────────────────────
            if (inlineEnforcerEnabled && inlineEnforcer != null && !result.text.isEmpty()) {
                ai.kompile.cli.main.chat.enforcer.EnforcerDecision decision =
                        inlineEnforcer.evaluate(currentMessage, result.text, inlineEnforcerPolicy, step);
                if (decision.isStop()) {
                    // Hard stop — reject and notify
                    System.out.println("\n" + renderer.red("[enforcer] BLOCKED: "
                            + String.join("; ", decision.getViolations())));
                    fullResponse.append("\n[Blocked by enforcer]");
                    break;
                } else if (!decision.isCompliant() && step < maxSteps) {
                    // Violation with correction — feed the correction prompt back
                    System.out.println("\n" + renderer.yellow("[enforcer] violation: "
                            + String.join("; ", decision.getViolations())));
                    System.out.println(renderer.yellow("[enforcer] sending correction (attempt "
                            + step + "/" + inlineEnforcerMaxCorrections + ")"));
                    if (step <= inlineEnforcerMaxCorrections) {
                        currentMessage = decision.getCorrectionPrompt();
                        pendingToolResults = null;
                        conversationHistory.add(CompactionService.ConversationEntry.user(currentMessage));
                        continue;
                    } else {
                        System.out.println(renderer.red("[enforcer] max corrections exceeded, accepting"));
                    }
                }
            }

            // If no tool calls, we're done
            if (result.toolCalls.isEmpty()) {
                break;
            }

            // Execute tool calls with proper rendering
            System.out.println();
            List<ToolCallResult> toolResults = new ArrayList<>();

            // Track read-only tools for context grouping
            Map<String, Integer> readOnlyToolCounts = new LinkedHashMap<>();
            List<ToolCallResult> readOnlyResults = new ArrayList<>();

            for (ToolCallRequest call : result.toolCalls) {
                // Check cancellation before each tool
                if (isCancelled()) {
                    System.out.println("\n" + renderer.yellow("  ⊘ Cancelled — skipping remaining tools"));
                    fullResponse.append("\n[Cancelled by user — tools skipped]");
                    break;
                }

                String normalizedToolName = TerminalRenderer.stripMcpPrefix(call.name);
                boolean isReadOnly = READ_ONLY_TOOLS.contains(normalizedToolName);

                // Start spinner for long-running tools
                TerminalRenderer.SpinnerHandle spinner = null;
                if (!isReadOnly) {
                    System.out.println(renderer.renderToolCallStart(call.name, getToolDescription(call)));
                    spinner = renderer.startSpinner(call.name);
                }

                try {
                    CliTool tool = toolRegistry.get(call.name);
                    if (tool == null) {
                        if (spinner != null) spinner.stop();
                        String errMsg = "Unknown tool: " + call.name;
                        System.out.println(renderer.renderToolCallComplete(call.name,
                                ToolResult.error(errMsg)));
                        toolResults.add(new ToolCallResult(call.id, call.name, errMsg, true));
                        if (sessionMetrics != null) sessionMetrics.recordToolCall(call.name, true, 0);
                        continue;
                    }

                    long toolStart = System.currentTimeMillis();
                    ToolResult toolResult = tool.execute(call.arguments, toolContext);
                    long toolDurationMs = System.currentTimeMillis() - toolStart;

                    // Truncate large outputs
                    OutputTruncator.TruncationResult truncResult =
                            truncator.truncate(toolResult.getOutput(), call.name);
                    if (truncResult.isTruncated()) {
                        toolResult = new ToolResult(toolResult.getTitle(),
                                truncResult.getOutput(), toolResult.getMetadata(), toolResult.isError());
                    }

                    if (spinner != null) spinner.stop();

                    if (isReadOnly && !toolResult.isError()) {
                        // Group read-only tools by normalized name so MCP-prefixed variants aggregate together.
                        readOnlyToolCounts.merge(normalizedToolName, 1, Integer::sum);
                        readOnlyResults.add(new ToolCallResult(call.id, call.name,
                                toolResult.getOutput(), false));
                    } else {
                        System.out.println(renderer.renderToolCallComplete(call.name, toolResult));
                    }

                    // Render inline todo updates after todowrite calls
                    if ("todowrite".equals(call.name) && !toolResult.isError()) {
                        List<TodoWriteTool.TodoItem> todos = TodoWriteTool.getTodos(toolContext.getSessionId());
                        if (!todos.isEmpty()) {
                            System.out.println(renderer.renderTodoList(todos));
                        }
                    }

                    if ("side_panel".equals(normalizedToolName) && !toolResult.isError()) {
                        renderSidePanelUpdate(true);
                    }

                    // Check if exit_plan_mode was called — stop tool loop
                    if ("exit_plan_mode".equals(call.name) && exitPlanModeTool != null
                            && exitPlanModeTool.isPlanApproved()) {
                        toolResults.add(new ToolCallResult(call.id, call.name,
                                toolResult.getOutput(), toolResult.isError()));
                        break;
                    }

                    // Save result to disk for later access
                    String argsStr = call.arguments != null ? call.arguments.toString() : "";
                    Path savedPath = toolResultStore.save(
                            call.name, call.id, argsStr,
                            toolResult.getOutput(), toolResult.isError());

                    // Append file path to output so compacted summaries reference it
                    String outputWithPath = toolResult.getOutput();
                    if (savedPath != null) {
                        outputWithPath += "\n[saved to: " + savedPath + "]";
                    }

                    toolResults.add(new ToolCallResult(call.id, call.name,
                            toolResult.getOutput(), toolResult.isError()));

                    // Record metrics
                    if (sessionMetrics != null) {
                        sessionMetrics.recordToolCall(call.name, toolResult.isError(), toolDurationMs);
                    }

                    // Track in conversation history (include file path for compaction)
                    conversationHistory.add(CompactionService.ConversationEntry.toolResult(
                            call.name, call.id, outputWithPath));

                } catch (ToolExecutionException e) {
                    if (spinner != null) spinner.stop();

                    if (e.isPermissionDenied()) {
                        System.out.println(renderer.renderToolCallDenied(call.name, e.getMessage()));
                    } else {
                        System.out.println(renderer.renderToolCallComplete(call.name,
                                ToolResult.error(e.getMessage())));
                    }

                    String errMsg = "Error: " + e.getMessage();
                    toolResultStore.save(call.name, call.id, null, errMsg, true);
                    toolResults.add(new ToolCallResult(call.id, call.name, errMsg, true));
                    if (sessionMetrics != null) sessionMetrics.recordToolCall(call.name, true, 0);
                }
            }

            // Render context group for read-only tools
            if (!readOnlyToolCounts.isEmpty()) {
                System.out.println(renderer.renderContextGroup(readOnlyToolCounts));
            }

            // Stop the loop if exit_plan_mode was called
            if (exitPlanModeTool != null && exitPlanModeTool.isPlanApproved()) {
                break;
            }

            // Set up next iteration with tool results
            pendingToolResults = toolResults;
            currentMessage = null;
        }

        if (step >= maxSteps) {
            System.out.println(renderer.renderMaxStepsWarning(maxSteps));
            fullResponse.append("\n[Agent reached maximum steps (").append(maxSteps).append(")]");
        }

        // Cleanup old truncation files
        truncator.cleanupOldFiles();

        // Evaluate turn with performance harness if configured
        String output = fullResponse.toString();
        if (performanceHarness != null && !output.isBlank()) {
            try {
                long turnLatency = System.currentTimeMillis() - turnStartMs;
                performanceHarness.evaluateTurnAsync(
                        agentName,
                        agent.getModelOverride(),
                        output,
                        sessionId,
                        turnLatency);
            } catch (Exception e) {
                // Harness evaluation is best-effort — never fail the turn
            }
        }

        return output;
    }

    private String getToolDescription(ToolCallRequest call) {
        if (call.arguments == null) return "";
        if (call.arguments.has("file_path")) {
            return call.arguments.path("file_path").asText("");
        }
        if (call.arguments.has("command")) {
            String cmd = call.arguments.path("command").asText("");
            return cmd.length() > 60 ? cmd.substring(0, 57) + "..." : cmd;
        }
        if (call.arguments.has("pattern")) {
            return call.arguments.path("pattern").asText("");
        }
        if (call.arguments.has("description")) {
            return call.arguments.path("description").asText("");
        }
        return "";
    }

    private void renderSidePanelUpdate(boolean force) {
        if (sidePanelManager == null) {
            return;
        }
        SidePanelManager.Snapshot snapshot = sidePanelManager.snapshot();
        if (!force && snapshot.version() == lastRenderedSidePanelVersion) {
            return;
        }
        lastRenderedSidePanelVersion = snapshot.version();

        if (!snapshot.visible()) {
            System.out.println(renderer.dim("  Side panel hidden"));
            return;
        }

        String title = snapshot.title() != null && !snapshot.title().isBlank()
                ? snapshot.title() : "Side Panel";
        String content = boundedSidePanelContent(snapshot.content());
        System.out.println(asciiRenderer.panel(title, content));
    }

    private String boundedSidePanelContent(String content) {
        if (content == null || content.isBlank()) {
            return renderer.dim("(empty)");
        }

        String[] lines = content.split("\\R", -1);
        StringBuilder body = new StringBuilder();
        int maxLines = Math.min(lines.length, 18);
        for (int i = 0; i < maxLines; i++) {
            if (i > 0) body.append('\n');
            body.append(TerminalRenderer.truncatePreview(lines[i], 100));
        }
        if (lines.length > maxLines) {
            body.append('\n').append(renderer.dim("... "))
                    .append(lines.length - maxLines)
                    .append(renderer.dim(" more lines"));
        }
        return body.toString();
    }

    // ========================================================================
    // Direct LLM mode (no server)
    // ========================================================================

    private StreamResult streamDirectTurn(String message, String systemPrompt,
                                           ArrayNode toolDefs, List<ToolCallResult> toolResults,
                                           String modelOverride) {
        StreamResult result = new StreamResult();

        // Convert tool results to DirectLlmClient format
        List<DirectLlmClient.ToolCallResultInput> directToolResults = null;
        if (toolResults != null && !toolResults.isEmpty()) {
            directToolResults = new ArrayList<>();
            for (ToolCallResult tr : toolResults) {
                directToolResults.add(new DirectLlmClient.ToolCallResultInput(
                        tr.callId, tr.toolName, tr.output, tr.isError));
            }
        }

        // Consume pending attachments on the first call (they are sent only once)
        List<DirectLlmClient.AttachmentInput> attachments = this.pendingAttachments;
        this.pendingAttachments = null;

        StreamingMarkdownRenderer markdownRenderer = new StreamingMarkdownRenderer(asciiRenderer);
        java.util.function.Consumer<String> previousConsumer = directLlmClient.getOutputConsumer();
        DirectLlmClient.StreamResult directResult;
        directLlmClient.setOutputConsumer(markdownRenderer::accept);
        try {
            directResult = directLlmClient.streamChat(message, systemPrompt, toolDefs, directToolResults, modelOverride, attachments);
        } finally {
            markdownRenderer.flush();
            directLlmClient.setOutputConsumer(previousConsumer);
        }

        // Record token usage from API response
        if (sessionMetrics != null) {
            sessionMetrics.recordTokenUsage(
                    directResult.inputTokens, directResult.outputTokens,
                    directResult.cacheReadTokens, directResult.cacheCreationTokens);
        }

        result.text = directResult.text;
        for (DirectLlmClient.ToolCallOutput tc : directResult.toolCalls) {
            ToolCallRequest req = new ToolCallRequest();
            req.id = tc.id;
            req.name = tc.name;
            req.arguments = tc.arguments;
            result.toolCalls.add(req);
        }

        return result;
    }

    // ========================================================================
    // Server mode (kompile-app)
    // ========================================================================

    private StreamResult streamServerTurn(String message, String sessionId, String serverAgent,
                                           boolean ragEnabled, String systemPrompt,
                                           ArrayNode toolDefs, List<ToolCallResult> toolResults) {
        StreamResult result = new StreamResult();

        StreamingMarkdownRenderer markdownRenderer = new StreamingMarkdownRenderer(asciiRenderer);

        try {
            ObjectNode request = objectMapper.createObjectNode();
            if (message != null) {
                request.put("message", message);
            }
            request.put("agentName", serverAgent);
            request.put("enableRag", ragEnabled);
            request.put("skipPermissions", true);
            request.put("timeoutSeconds", 300);

            if (toolDefs != null && toolDefs.size() > 0) {
                request.set("tools", toolDefs);
            }

            if (toolResults != null && !toolResults.isEmpty()) {
                ArrayNode resultsArray = objectMapper.createArrayNode();
                for (ToolCallResult tr : toolResults) {
                    ObjectNode trNode = objectMapper.createObjectNode();
                    trNode.put("tool_call_id", tr.callId);
                    trNode.put("name", tr.toolName);
                    trNode.put("output", tr.output);
                    trNode.put("error", tr.isError);
                    resultsArray.add(trNode);
                }
                request.set("toolResults", resultsArray);
            }

            if (systemPrompt != null && !systemPrompt.isEmpty()) {
                request.put("systemPromptOverride", systemPrompt);
            }

            String body = objectMapper.writeValueAsString(request);

            HttpRequest httpRequest = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/api/agents/chat/stream"))
                    .header("Content-Type", "application/json")
                    .header("Accept", "text/event-stream")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .timeout(Duration.ofMinutes(10))
                    .build();

            HttpResponse<java.io.InputStream> response = httpClient.send(
                    httpRequest, HttpResponse.BodyHandlers.ofInputStream());

            if (response.statusCode() != 200) {
                result.text = "[Agent HTTP error " + response.statusCode() + "]";
                return result;
            }

            // Parse SSE stream
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(response.body()))) {
                String eventType = null;
                StringBuilder dataBuffer = new StringBuilder();
                String line;

                while ((line = reader.readLine()) != null) {
                    if (isCancelled()) {
                        break;
                    }
                    if (line.startsWith("event:")) {
                        eventType = line.substring(6).trim();
                    } else if (line.startsWith("data:")) {
                        dataBuffer.append(line.substring(5).trim());
                    } else if (line.isEmpty() && eventType != null) {
                        String data = dataBuffer.toString();
                        processStreamEvent(eventType, data, result, markdownRenderer);
                        eventType = null;
                        dataBuffer.setLength(0);
                    }
                }
            }
            markdownRenderer.flush();

        } catch (Exception e) {
            markdownRenderer.flush();
            result.text = "[Error: " + e.getMessage() + "]";
        }

        return result;
    }

    private void processStreamEvent(String eventType, String data, StreamResult result,
                                    StreamingMarkdownRenderer markdownRenderer) {
        switch (eventType) {
            case "chunk":
                String chunk = data;
                if (chunk.startsWith("\"") && chunk.endsWith("\"")) {
                    try { chunk = objectMapper.readValue(chunk, String.class); }
                    catch (Exception ignored) {}
                }
                markdownRenderer.accept(chunk);
                result.text += chunk;
                break;

            case "tool_call":
                markdownRenderer.flush();
                try {
                    JsonNode toolCall = objectMapper.readTree(data);
                    ToolCallRequest req = new ToolCallRequest();
                    req.id = toolCall.path("id").asText("call_" + result.toolCalls.size());
                    req.name = toolCall.path("name").asText("");
                    req.arguments = toolCall.path("arguments");
                    result.toolCalls.add(req);
                } catch (Exception e) {
                    System.err.println(renderer.red("  [Error parsing tool call: " + e.getMessage() + "]"));
                }
                break;

            case "start":
                markdownRenderer.flush();
                try {
                    JsonNode json = objectMapper.readTree(data);
                    String agent = json.path("agent").asText("");
                    if (!agent.isEmpty()) {
                        System.out.println(renderer.dim("[Agent: " + agent + "]"));
                    }
                } catch (Exception ignored) {}
                break;

            case "sources":
                markdownRenderer.flush();
                try {
                    JsonNode sources = objectMapper.readTree(data);
                    if (sources.isArray() && sources.size() > 0) {
                        System.out.println(renderer.dim("[Retrieved " + sources.size() + " documents]"));
                    }
                } catch (Exception ignored) {}
                break;

            case "stats":
                markdownRenderer.flush();
                try {
                    JsonNode stats = objectMapper.readTree(data);
                    long durationMs = stats.path("durationMs").asLong(0);
                    if (durationMs > 0) {
                        System.out.println(renderer.dim("  [completed in " + durationMs + "ms]"));
                    }
                } catch (Exception ignored) {}
                break;

            case "error":
                markdownRenderer.flush();
                try {
                    JsonNode error = objectMapper.readTree(data);
                    String msg = error.path("message").asText(data);
                    System.err.println(renderer.red("\n[Error: " + msg + "]"));
                    result.text += "\n[Error: " + msg + "]";
                } catch (Exception e) {
                    System.err.println(renderer.red("\n[Error: " + data + "]"));
                }
                break;

            case "complete":
            case "cancelled":
                markdownRenderer.flush();
                break;
        }
    }

    public TerminalRenderer getRenderer() {
        return renderer;
    }

    // ========================================================================
    // Internal data classes
    // ========================================================================

    static class StreamResult {
        String text = "";
        List<ToolCallRequest> toolCalls = new ArrayList<>();
    }

    static class ToolCallRequest {
        String id;
        String name;
        JsonNode arguments;
    }

    static class ToolCallResult {
        String callId;
        String toolName;
        String output;
        boolean isError;

        ToolCallResult(String callId, String toolName, String output, boolean isError) {
            this.callId = callId;
            this.toolName = toolName;
            this.output = output;
            this.isError = isError;
        }
    }
}
