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

import ai.kompile.cli.main.chat.ChatCompleter;
import ai.kompile.cli.main.chat.ToolCallIndex;
import ai.kompile.cli.main.chat.config.ChatConfig;
import ai.kompile.cli.main.chat.config.DirectLlmClient;
import ai.kompile.cli.main.chat.config.ModelContextResolver;
import ai.kompile.cli.main.chat.context.ConversationBoundaryPlanner;
import ai.kompile.cli.main.chat.context.ConversationLedger;
import ai.kompile.cli.main.chat.harness.PerformanceHarness;
import ai.kompile.cli.main.chat.permission.PermissionService;
import ai.kompile.cli.main.chat.render.AsciiRenderer;
import ai.kompile.cli.main.chat.render.CompactionService;
import ai.kompile.cli.main.chat.render.ConversationSummarizer;
import ai.kompile.cli.main.chat.render.OutputTruncator;
import ai.kompile.cli.main.chat.render.StreamingMarkdownRenderer;
import ai.kompile.cli.main.chat.render.TerminalRenderer;
import ai.kompile.cli.main.chat.skill.SkillRegistry;
import ai.kompile.cli.main.chat.tui.SidePanelManager;
import ai.kompile.cli.main.chat.tools.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;

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

    private static final int MAX_LIVE_TOOL_OUTPUT_LINES = 500;
    private static final int MAX_LIVE_TOOL_OUTPUT_CHARS = 30_000;
    private static final int MAX_LIVE_TOOL_LINE_CHARS = 4_000;
    private static final long LIVE_TOOL_FRAME_DELAY_MS = 50L;

    /**
     * Optional standard-chat side channel for tool lifecycle activity. The listener
     * updates the activity panel while the normal transcript always renders tool
     * calls inline as they start and complete.
     */
    public interface ToolActivityListener {
        void onToolStart(String callId, String toolName, String rawInput);
        void onToolComplete(String callId, String toolName, String rawInput, ToolResult result);
        default void onToolDenied(String callId, String toolName, String rawInput, String reason) {
            onToolComplete(callId, toolName, rawInput, ToolResult.error(reason));
        }
    }

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
    private final ProjectChatContext projectChatContext;
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

    // Canonical, durable conversation state. Provider wire histories are projections
    // of this ledger rather than an independent source of compaction truth.
    private final ConversationLedger conversationLedger;
    private volatile String conversationSessionId;

    // Resolves the active model's real context window (catalog first, then a local
    // staging-server probe for staged GGUFs the catalogs don't know).
    private final ModelContextResolver contextResolver = new ModelContextResolver();

    // Prompt tokens the provider reported for the most recent direct-mode call.
    // A truer context-usage signal than the char/4 estimate (it includes the system
    // prompt and tool definitions), used alongside the estimate to trigger compaction.
    private volatile long lastReportedInputTokens = 0L;
    // Heuristic history size at the moment the provider usage was reported. Growth
    // after that call (assistant/tool output and the next user turn) is projected on top.
    private volatile long lastReportedHistoryTokens = 0L;

    // Cancel signal - set by ChatRepl when user presses Escape
    private volatile AtomicBoolean cancelSignal;
    private final AtomicReference<AtomicBoolean> activeToolAbortSignal = new AtomicReference<>();
    private final AtomicReference<InputStream> activeResponseBody = new AtomicReference<>();
    private final AtomicReference<String> activeRemoteProcessId = new AtomicReference<>();

    // Optional production crawl/run controller. It is null for ordinary chat.
    private volatile AgentRunController runController;

    // Pending attachments for the next chat turn (consumed on first direct-mode call)
    private volatile List<DirectLlmClient.AttachmentInput> pendingAttachments;

    // Callback fired once when the first model text or tool call is printed.
    // Used by ChatRepl to stop the generating spinner.
    private volatile Runnable onFirstOutput;
    private volatile ToolActivityListener toolActivityListener;
    private final AtomicLong transcriptBlockSequence = new AtomicLong();
    private volatile Supplier<String> queuedMessageSupplier = () -> null;
    private final AtomicReference<Consumer<String>> backgroundOutputConsumer = new AtomicReference<>();

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
        this(baseUrl, objectMapper, toolRegistry, permissionService, agentRegistry,
                workingDirectory, directLlmClient, processManager, null);
    }

    /** Full constructor using the same skill registry owned by the normal ChatRepl. */
    public AgenticChatLoop(String baseUrl, ObjectMapper objectMapper,
                            ToolRegistry toolRegistry, PermissionService permissionService,
                            AgentRegistry agentRegistry, Path workingDirectory,
                            DirectLlmClient directLlmClient,
                            ai.kompile.cli.main.chat.tools.BackgroundProcessManager processManager,
                            SkillRegistry skillRegistry) {
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
        this.conversationLedger = new ConversationLedger(objectMapper);
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
                emitLine("");
                emitLine(notification);
                emitLine("");
            });
        }

        // Initialize current agent config with default
        this.currentAgentConfig = agentRegistry.getDefault();

        SkillRegistry effectiveSkills = skillRegistry != null
                ? skillRegistry : ProjectChatContext.load(workingDirectory).skillRegistry();
        this.projectChatContext = ProjectChatContext.load(workingDirectory, effectiveSkills);
        this.agentsMdContent = projectChatContext.agentsMdContent();
    }

    private SidePanelManager findSidePanelManager(ToolRegistry registry) {
        CliTool sidePanelTool = registry != null ? registry.get("side_panel") : null;
        if (sidePanelTool instanceof SidePanelTool tool) {
            return tool.getSidePanelManager();
        }
        return null;
    }

    /**
     * Sets the session metrics tracker for recording tool calls, iterations, and token usage.
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

    public void setToolActivityListener(ToolActivityListener listener) {
        this.toolActivityListener = listener;
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

    /** Print without damaging an active JLine input buffer, or retain it after Ctrl+B. */
    private void emitLine(String line) {
        Consumer<String> background = backgroundOutputConsumer.get();
        if (background != null) {
            background.accept((line == null ? "" : line) + System.lineSeparator());
            return;
        }
        ChatCompleter.printAbove(line);
    }

    private void setForegroundActivity(String activity) {
        if (backgroundOutputConsumer.get() == null) {
            ChatCompleter.setActivity(activity);
        }
    }

    /** Supply queued user guidance from the owning normal-REPL session. */
    public void setQueuedMessageSupplier(Supplier<String> supplier) {
        this.queuedMessageSupplier = supplier != null ? supplier : () -> null;
    }

    /** Route subsequent turn output to a retained background task. */
    public void backgroundActiveTurn(Consumer<String> outputConsumer) {
        if (outputConsumer != null) {
            backgroundOutputConsumer.set(outputConsumer);
            ChatCompleter.setActivity(null);
        }
    }

    /** Restore foreground output routing after the owning turn terminates. */
    public void clearBackgroundOutput() {
        backgroundOutputConsumer.set(null);
    }

    boolean isOutputBackgrounded() {
        return backgroundOutputConsumer.get() != null;
    }

    private String claimQueuedMessage() {
        Supplier<String> supplier = queuedMessageSupplier;
        return supplier != null ? supplier.get() : null;
    }

    /**
     * Sets the cancel signal for interrupting in-progress operations.
     * The signal is checked between agentic iterations and during LLM streaming.
     */
    public void setCancelSignal(AtomicBoolean cancelSignal) {
        this.cancelSignal = cancelSignal;
        if (directLlmClient != null) {
            directLlmClient.setCancelSignal(cancelSignal);
        }
    }

    /** Interrupts a blocking server stream and asks the server to kill its agent process. */
    public void cancelActiveTurn() {
        AtomicBoolean signal = cancelSignal;
        if (signal != null) {
            signal.set(true);
        }
        AtomicBoolean toolAbort = activeToolAbortSignal.get();
        if (toolAbort != null) {
            toolAbort.set(true);
        }
        InputStream responseBody = activeResponseBody.getAndSet(null);
        if (responseBody != null) {
            try {
                responseBody.close();
            } catch (IOException ignored) {
                // The stream owner will observe the cancellation signal.
            }
        }
        String processId = activeRemoteProcessId.getAndSet(null);
        if (processId != null && !processId.isBlank() && baseUrl != null && !baseUrl.isBlank()) {
            try {
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(baseUrl + "/api/agents/chat/cancel/" + processId))
                        .POST(HttpRequest.BodyPublishers.noBody())
                        .timeout(Duration.ofSeconds(5))
                        .build();
                httpClient.sendAsync(request, HttpResponse.BodyHandlers.discarding());
            } catch (Exception ignored) {
                // Local stream closure remains effective if remote cleanup is unavailable.
            }
        }
    }

    /** Attach a cooperative controller for a production crawl run. */
    public void setRunController(AgentRunController controller) {
        this.runController = controller;
    }

    public AgentRunController getRunController() {
        return runController;
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
    String buildSystemPrompt(AgentConfig agent) {
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

        String projectPrompt = projectChatContext.renderSystemPrompt();
        if (!projectPrompt.isBlank()) {
            sb.append("\n\n").append(projectPrompt);
        }

        return sb.toString();
    }

    /**
     * Folder-local models default to progressive tool disclosure because a large
     * fixed catalog consumes attention even when it fits in the context window.
     * A JVM property provides an explicit override for any provider.
     */
    boolean usesProgressiveToolLoading() {
        String configured = System.getProperty("kompile.chat.progressiveTools");
        if (configured != null && !configured.isBlank()) {
            return Boolean.parseBoolean(configured);
        }
        return isDirectMode()
                && directLlmClient.getChatConfig().isKompileLocalServing();
    }

    private ArrayNode toolDefinitions(AgentConfig agent, boolean progressive) {
        if (isDirectMode()) {
            return progressive
                    ? toolRegistry.buildProgressiveDirectToolDefinitions(agent)
                    : toolRegistry.buildDirectToolDefinitions(agent);
        }
        return progressive
                ? toolRegistry.buildProgressiveToolDefinitions(agent)
                : toolRegistry.buildToolDefinitions(agent);
    }

    /**
     * Get the loaded AGENTS.md content (for display by /help or /config).
     */
    public String getAgentsMdContent() {
        return agentsMdContent;
    }

    /** Supplemental AGENTS.md and skill catalog shared with non-agentic chat routes. */
    public String getProjectContextPrompt() {
        return projectChatContext.renderSystemPrompt();
    }

    public List<Path> getAgentsMdFiles() {
        return projectChatContext.agentsMdFiles();
    }

    /** Bind durable context state before restoring or accepting the first turn. */
    public void configureConversationSession(String sessionId) {
        if (sessionId == null || sessionId.isBlank()
                || sessionId.equals(conversationSessionId)) return;
        conversationLedger.configureSession(sessionId);
        conversationSessionId = sessionId;
        if (conversationLedger.hasDurableState() && directLlmClient != null) {
            rebuildDirectHistoryForProviderSwitch();
        }
    }

    /**
     * Restore conversation history from previous session turns.
     * Replays turns into the DirectLlmClient and conversation history.
     */
    public void restoreHistory(java.util.List<ai.kompile.cli.main.chat.ChatHistory.Turn> turns) {
        conversationLedger.importLegacyTurns(turns);
        rebuildDirectHistoryForProviderSwitch();
    }

    /**
     * Rebuild the direct client's wire history after a provider switch.
     * Provider-specific tool-call envelopes cannot safely cross API formats, so
     * replay the canonical text transcript while retaining this loop's complete
     * conversation and the outer ChatHistory/session unchanged.
     *
     * @return number of provider-neutral messages replayed
     */
    public int rebuildDirectHistoryForProviderSwitch() {
        if (directLlmClient == null) {
            return 0;
        }

        ConversationLedger.Snapshot snapshot = conversationLedger.snapshot();
        ConversationLedger.CompactionCheckpoint checkpoint = snapshot.checkpoint();
        String effectiveModel = currentAgentConfig != null
                && currentAgentConfig.getModelOverride() != null
                && !currentAgentConfig.getModelOverride().isBlank()
                ? currentAgentConfig.getModelOverride()
                : directLlmClient.getConfiguredModel();
        boolean restoredNative = checkpoint != null
                && checkpoint.nativePayload() != null
                && checkpoint.provider() != null
                && checkpoint.provider().equalsIgnoreCase(
                        directLlmClient.getConfiguredProvider())
                && Objects.equals(checkpoint.model(), effectiveModel);
        if (restoredNative) {
            directLlmClient.replaceHistoryWithNativeCheckpoint(checkpoint.nativePayload());
        }
        return rebuildDirectHistory(snapshot.activeEntries(), !restoredNative, restoredNative);
    }

    private int rebuildDirectHistory(List<CompactionService.ConversationEntry> entries) {
        return rebuildDirectHistory(entries, true, false);
    }

    private int rebuildDirectHistory(
            List<CompactionService.ConversationEntry> entries,
            boolean clearHistory,
            boolean skipPortableSummary) {
        if (clearHistory) directLlmClient.clearHistory();
        int replayed = 0;
        for (CompactionService.ConversationEntry entry : entries) {
            if (entry == null || entry.content == null || entry.content.isBlank()) {
                continue;
            }
            if (skipPortableSummary && entry.type == CompactionService.EntryType.SYSTEM
                    && entry.content.startsWith(ConversationLedger.SUMMARY_MARKER)) {
                continue;
            }
            switch (entry.type) {
                case SYSTEM -> {
                    directLlmClient.addToHistory(
                            "user", "[Conversation summary]\n" + entry.content);
                    directLlmClient.addToHistory(
                            "assistant", "Understood. I will continue from that conversation summary.");
                    replayed += 2;
                }
                case USER, ASSISTANT -> {
                    directLlmClient.addToHistory(entry.role, entry.content);
                    replayed++;
                }
                case TOOL_CALL -> {
                    // Provider-native envelopes cannot cross protocols safely, but
                    // dropping a preserved tool exchange loses the very recent state
                    // compaction promised to retain. Replay it as portable text.
                    directLlmClient.addToHistory("assistant",
                            "[Tool call " + entry.toolName + " " + entry.toolCallId + "]\n"
                                    + entry.content);
                    replayed++;
                }
                case TOOL_RESULT -> {
                    directLlmClient.addToHistory("user",
                            "[Tool result " + entry.toolName + " " + entry.toolCallId + "]\n"
                                    + entry.content);
                    replayed++;
                }
            }
        }
        resetReportedContextUsage();
        return replayed;
    }

    /**
     * Current estimated token count of the tracked conversation history.
     * Uses the heuristic char/4 estimate from CompactionService.
     */
    public int estimateConversationTokens() {
        return compactionService.estimateTokens(
                conversationLedger.snapshot().activeEntries());
    }

    /**
     * Number of tracked conversation entries (user, assistant, tool calls, tool results).
     */
    public int conversationEntryCount() {
        return conversationLedger.snapshot().activeEntries().size();
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
        ConversationLedger.Snapshot ledgerSnapshot = conversationLedger.snapshot();
        List<CompactionService.ConversationEntry> conversationHistory =
                ledgerSnapshot.activeEntries();
        if (conversationHistory.isEmpty()) {
            return ForceCompactResult.noop("Nothing to compact — conversation is empty.");
        }

        int tokensBefore = compactionService.estimateTokens(conversationHistory);

        // Split off recent turns to preserve after summarization
        int preserveIndex = ConversationBoundaryPlanner.preserveFrom(
                conversationHistory, compactionService,
                compactionService.preserveRecentTokens());
        List<CompactionService.ConversationEntry> toSummarize =
                new ArrayList<>(conversationHistory.subList(0, preserveIndex));
        List<CompactionService.ConversationEntry> toPreserve =
                new ArrayList<>(conversationHistory.subList(preserveIndex, conversationHistory.size()));

        if (toSummarize.isEmpty()) {
            // A single large recent turn used to make both manual and automatic
            // compaction a permanent NOOP. Summarize that turn too; retaining an
            // oversized tail would defeat the operation.
            toSummarize = new ArrayList<>(conversationHistory);
            toPreserve = new ArrayList<>();
        }

        String modelOverride = currentAgentConfig != null ? currentAgentConfig.getModelOverride() : null;
        DirectLlmClient.NativeCompactionResult nativeResult =
                directLlmClient.tryNativeCompact(modelOverride);
        String strategy = "generic";
        ConversationSummarizer.SummaryResult summary;
        if (nativeResult.applied()) {
            strategy = "native-" + directLlmClient.compactionCapabilities(modelOverride)
                    .nativeCompaction().name().toLowerCase(Locale.ROOT);
            // Provider-owned native sessions summarize their complete active
            // context, so the portable checkpoint must cover the same range.
            preserveIndex = conversationHistory.size();
            toPreserve = new ArrayList<>();
            String portableSummary = nativeResult.portableSummary();
            if (portableSummary == null || portableSummary.isBlank()) {
                portableSummary = compactionService.renderDigest(conversationHistory);
            }
            summary = new ConversationSummarizer.SummaryResult(
                    portableSummary, 0L, 0L);
        } else {
            ConversationSummarizer summarizer = new ConversationSummarizer(directLlmClient);
            summary = summarizer.summarize(toSummarize, focusInstruction, modelOverride);
        }

        if (summary.isEmpty() || looksLikeFailedSummary(summary.getSummary())) {
            return ForceCompactResult.failed("Summarization returned empty output; history unchanged.");
        }

        List<CompactionService.ConversationEntry> candidate = new ArrayList<>();
        candidate.add(CompactionService.ConversationEntry.system(
                ConversationLedger.SUMMARY_MARKER + summary.getSummary()));
        candidate.addAll(toPreserve);
        int tokensAfter = compactionService.estimateTokens(candidate);
        long coveredThrough = ledgerSnapshot.coveredThroughForPrefix(preserveIndex);
        String effectiveModel = modelOverride != null
                ? modelOverride : directLlmClient.getConfiguredModel();
        boolean checkpointCommitted = nativeResult.nativePayload() == null
                ? conversationLedger.commitCompaction(
                        ledgerSnapshot.version(), coveredThrough, summary.getSummary(), strategy,
                        directLlmClient.getConfiguredProvider(), effectiveModel,
                        tokensBefore, tokensAfter)
                : conversationLedger.commitNativeCompaction(
                        ledgerSnapshot.version(), coveredThrough, summary.getSummary(), strategy,
                        directLlmClient.getConfiguredProvider(), effectiveModel,
                        tokensBefore, tokensAfter, nativeResult.nativePayload());
        if (!checkpointCommitted) {
            return ForceCompactResult.failed(
                    "Conversation changed while it was being summarized; history unchanged.");
        }

        // The checkpoint is now authoritative. Reproject the provider wire view
        // from that exact committed state rather than mutating two histories.
        rebuildDirectHistoryForProviderSwitch();

        if (sessionMetrics != null) {
            sessionMetrics.recordCompaction(tokensBefore, tokensAfter);
            sessionMetrics.recordTokenUsage(
                    summary.getInputTokens(), summary.getOutputTokens(), 0, 0);
        }

        // The provider-reported prompt size described the pre-compaction history.
        resetReportedContextUsage();

        return ForceCompactResult.ok(tokensBefore, tokensAfter, toPreserve.size(),
                summary.getSummary());
    }

    private boolean looksLikeFailedSummary(String summary) {
        if (summary == null) return true;
        String normalized = summary.strip().toLowerCase(Locale.ROOT);
        return normalized.startsWith("[error:")
                || normalized.startsWith("[kompile serving error:")
                || normalized.startsWith("[anthropic api error")
                || normalized.startsWith("[radius api error");
    }

    /**
     * Refresh the compaction budget from the real context window of the model this
     * chat is talking to: per-agent override first, then the configured model; the
     * resolver consults the live CLI catalogs, the static table, and — for staged
     * local GGUFs unknown to both — the local serving origin's /api/llm/status.
     * Server mode keeps the default budget (the server bounds its own context).
     */
    private void refreshCompactionBudget(AgentConfig agent) {
        if (directLlmClient == null) return;
        try {
            String override = agent != null ? agent.getModelOverride() : null;
            ChatConfig chatConfig = directLlmClient.getChatConfig();
            ModelContextResolver.ModelLimits limits = contextResolver.resolveLimits(chatConfig, override);
            compactionService.setMaxTokens(limits.contextWindow());
            compactionService.configure(
                    chatConfig.isAutoCompactEnabled(),
                    chatConfig.getAutoCompactThreshold(),
                    limits.maxOutputTokens(),
                    chatConfig.getCompactionReserveTokens());
            directLlmClient.setNativeCompactionTriggerTokens(
                    compactionService.triggerTokens());
        } catch (Exception e) {
            // Budget refresh must never break a chat turn; keep the previous budget.
        }
    }

    /** Refresh policy immediately after a live /auto-compact configuration change. */
    public void refreshCompactionPolicy() {
        refreshCompactionBudget(currentAgentConfig);
    }

    /**
     * Turn-start auto-compaction. Runs the same LLM summarization as /compact when
     * the tracked history (or the provider-reported prompt size of the last call)
     * is near the model's context window; falls back to deterministic pruning plus
     * a digest rewrite of the wire history when summarization fails. Turn start is
     * the only point where wholesale wire-history replacement is safe — mid-loop,
     * only in-place tool-content shrinking is allowed.
     */
    private void maybeAutoCompactBeforeTurn(
            String pendingMessage, String systemPrompt, ArrayNode toolDefs,
            String modelOverride) {
        if (directLlmClient == null) return;
        if (!compactionService.isAutoCompactEnabled()) return;
        long projectedInputTokens = projectedInputTokens(pendingMessage);
        if (lastReportedInputTokens <= 0L && toolDefs != null) {
            projectedInputTokens = saturatingAdd(projectedInputTokens,
                    compactionService.estimateTextTokens(toolDefs.toString()));
        }
        DirectLlmClient.TokenCountResult exact = directLlmClient.countInputTokens(
                pendingMessage, systemPrompt, toolDefs, null, modelOverride);
        if (exact.exact() && exact.inputTokens() > 0L) {
            projectedInputTokens = Math.max(projectedInputTokens, exact.inputTokens());
        }
        if (!compactionService.needsCompaction(projectedInputTokens)) return;

        // Anthropic performs compaction inside the pending Messages request and
        // returns a portable compaction block that is committed below with the
        // response. Do not preempt it with generic summarization.
        if (directLlmClient.compactionCapabilities(modelOverride).nativeCompaction()
                == ai.kompile.cli.main.chat.config.ProviderCompactionCapabilities
                .NativeCompaction.ANTHROPIC_MESSAGES
                && compactionService.triggerTokens() >= 50_000) {
            return;
        }

        ConversationLedger.Snapshot snapshot = conversationLedger.snapshot();
        List<CompactionService.ConversationEntry> conversationHistory = snapshot.activeEntries();
        int tokensBefore = compactionService.estimateTokens(conversationHistory);
        ForceCompactResult forced = forceCompact(null);
        if (forced.isSuccess()) {
            emitLine(renderer.renderCompactionNotice(
                    forced.getTokensBefore(), forced.getTokensAfter()));
            resetReportedContextUsage();
            return;
        }
        if (forced.getStatus() == ForceCompactResult.Status.NOOP) {
            return;
        }

        // Summarization unavailable or failed — commit a deterministic portable
        // digest at the same complete-exchange boundary. Raw events remain durable.
        int preserveIndex = ConversationBoundaryPlanner.preserveFrom(
                conversationHistory, compactionService,
                compactionService.preserveRecentTokens());
        if (preserveIndex == 0) preserveIndex = conversationHistory.size();
        List<CompactionService.ConversationEntry> tail = new ArrayList<>(
                conversationHistory.subList(preserveIndex, conversationHistory.size()));
        String digest = compactionService.renderDigest(
                conversationHistory.subList(0, preserveIndex));
        if (digest.isBlank()) return;
        List<CompactionService.ConversationEntry> candidate = new ArrayList<>();
        candidate.add(CompactionService.ConversationEntry.system(
                ConversationLedger.SUMMARY_MARKER + digest));
        candidate.addAll(tail);
        int tokensAfter = compactionService.estimateTokens(candidate);
        if (!conversationLedger.commitCompaction(
                snapshot.version(), snapshot.coveredThroughForPrefix(preserveIndex), digest,
                "deterministic", directLlmClient.getConfiguredProvider(),
                directLlmClient.getConfiguredModel(), tokensBefore, tokensAfter)) {
            return;
        }
        rebuildDirectHistoryForProviderSwitch();
        if (sessionMetrics != null) {
            sessionMetrics.recordCompaction(tokensBefore, tokensAfter);
        }
        emitLine(renderer.renderCompactionNotice(tokensBefore, tokensAfter));
        resetReportedContextUsage();
    }

    private long projectedInputTokens(String pendingMessage) {
        long estimatedHistory = compactionService.estimateTokens(
                conversationLedger.snapshot().activeEntries());
        long pendingTokens = compactionService.estimateTextTokens(pendingMessage);
        if (lastReportedInputTokens <= 0L) {
            long systemTokens = compactionService.estimateTextTokens(
                    buildSystemPrompt(currentAgentConfig));
            return saturatingAdd(saturatingAdd(estimatedHistory, pendingTokens), systemTokens);
        }
        long historyGrowth = Math.max(0L, estimatedHistory - lastReportedHistoryTokens);
        return saturatingAdd(saturatingAdd(lastReportedInputTokens, historyGrowth), pendingTokens);
    }

    private static long saturatingAdd(long left, long right) {
        if (left < 0L) left = 0L;
        if (right < 0L) right = 0L;
        return Long.MAX_VALUE - left < right ? Long.MAX_VALUE : left + right;
    }

    private void resetReportedContextUsage() {
        lastReportedInputTokens = 0L;
        lastReportedHistoryTokens = 0L;
    }

    /** The model-aware context budget currently in effect, in tokens. */
    public int contextWindowTokens() {
        return compactionService.getMaxTokens();
    }

    /** Prompt tokens the provider reported for the most recent direct-mode call (0 if none yet). */
    public long lastReportedInputTokens() {
        return lastReportedInputTokens;
    }

    public boolean autoCompactEnabled() { return compactionService.isAutoCompactEnabled(); }
    public int compactionTriggerTokens() { return compactionService.triggerTokens(); }
    public int compactionReserveTokens() { return compactionService.effectiveReserveTokens(); }
    public int maxOutputTokens() { return compactionService.getMaxOutputTokens(); }
    public double autoCompactThreshold() { return compactionService.getTriggerRatio(); }

    public String compactionStrategyDescription() {
        if (directLlmClient == null) return "server-managed";
        String model = currentAgentConfig == null ? null : currentAgentConfig.getModelOverride();
        var capabilities = directLlmClient.compactionCapabilities(model);
        String compaction = switch (capabilities.nativeCompaction()) {
            case ANTHROPIC_MESSAGES -> "Anthropic server compaction";
            case OPENAI_RESPONSES -> "OpenAI Responses compaction";
            case OPENCODE_SESSION -> "OpenCode session summarize";
            case NONE -> "generic structured summary";
        };
        String counting = switch (capabilities.tokenCounting()) {
            case ANTHROPIC_MESSAGES -> "Anthropic count_tokens";
            case OPENAI_RESPONSES -> "Responses input_tokens";
            case GEMINI -> "Gemini countTokens";
            case NONE -> "reported usage + estimate";
        };
        return compaction + " (" + counting + ", deterministic fallback)";
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

        emitLine(renderer.bold(renderer.cyan("  ╭─ Planning Mode ─────────────────────────────────────╮")));
        emitLine(renderer.cyan("  │") + renderer.dim("  Analyzing task with read-only tools...              ") + renderer.cyan("│"));
        emitLine(renderer.cyan("  │") + renderer.dim("  Call exit_plan_mode when plan is ready.             ") + renderer.cyan("│"));
        emitLine(renderer.bold(renderer.cyan("  ╰───────────────────────────────────────────────────────╯")));
        emitLine("");

        String planResponse = chatInternal(message, sessionId, "planner", serverAgent, ragEnabled);

        // Show the checklist after planning
        List<TodoWriteTool.TodoItem> todos = TodoWriteTool.getTodos(sessionId);
        if (!todos.isEmpty()) {
            emitLine("");
            emitLine(renderer.bold(renderer.cyan("  ── Plan Checklist ──")));
            emitLine(renderer.renderTodoList(todos));
            emitLine("");
        }

        // Check if plan was approved via exit_plan_mode tool
        if (exitPlanModeTool.isPlanApproved()) {
            emitLine(renderer.bold(renderer.yellow("  Plan ready for approval.")));
            emitLine(renderer.dim("  The agent will now proceed with execution."));
            emitLine("");

            // Phase 2: Execution pass with coder agent
            emitLine(renderer.bold(renderer.green("  ╭─ Execution Mode ────────────────────────────────────╮")));
            emitLine(renderer.green("  │") + renderer.dim("  Executing plan with full tool access...             ") + renderer.green("│"));
            emitLine(renderer.bold(renderer.green("  ╰───────────────────────────────────────────────────────╯")));
            emitLine("");

            String executionPrompt = "Execute the plan you just created. "
                    + "Update each task status as you complete it using todowrite. "
                    + "Here was the plan:\n\n" + planResponse;
            // exit_plan_mode is a one-shot planning signal. Carrying its approved
            // state into execution would make the execution loop stop at its first
            // queue/tool boundary and could discard newly claimed input.
            exitPlanModeTool.reset();
            String executionResponse = chatInternal(executionPrompt, sessionId, agentName, serverAgent, ragEnabled);

            return planResponse + "\n\n--- Execution ---\n\n" + executionResponse;
        }

        return planResponse;
    }

    private String chatInternal(String message, String sessionId, String agentName,
                                 String serverAgent, boolean ragEnabled) {
        configureConversationSession(sessionId);
        long turnStartMs = System.currentTimeMillis();
        AgentConfig agent = agentRegistry.get(agentName);
        if (agent == null) agent = agentRegistry.getDefault();

        // Initialize tool result store for this session
        this.toolResultStore = new ToolResultStore(sessionId);

        AtomicBoolean turnToolAbort = new AtomicBoolean(isCancelled());
        activeToolAbortSignal.set(turnToolAbort);
        ToolContext toolContext = new ToolContext(
                sessionId, agent, permissionService, workingDirectory, toolRegistry);
        toolContext.linkAbortSignal(turnToolAbort);
        toolContext.setOutputConsumer(this::emitLine);

        boolean progressiveToolLoading = usesProgressiveToolLoading();
        if (progressiveToolLoading) {
            toolRegistry.prepareProgressiveTools(agent);
        }

        // Compose system prompt: agent prompt + AGENTS.md content + result store info
        String systemPrompt = buildSystemPrompt(agent);
        ArrayNode initialToolDefs = toolDefinitions(agent, progressiveToolLoading);

        StringBuilder fullResponse = new StringBuilder();
        int iteration = 0;

        String currentMessage = message;
        List<ToolCallResult> pendingToolResults = null;

        // Refresh the compaction budget from the active model's real context window,
        // then auto-compact BEFORE this turn if the prior conversation is already near it.
        refreshCompactionBudget(agent);
        maybeAutoCompactBeforeTurn(
                message, systemPrompt, initialToolDefs, agent.getModelOverride());

        // Track conversation for compaction
        conversationLedger.append(CompactionService.ConversationEntry.user(message));

        // A chat turn runs until the model finishes, the user interrupts it,
        // or an explicit tool/plan decision ends it. There is no arbitrary step
        // or execution-limit cutoff in the chat loop.
        while (true) {
            // Check cancellation and external crawl controls before each iteration.
            if (isCancelled()) {
                emitLine("\n" + renderer.yellow("  ⊘ Interrupted by user"));
                fullResponse.append("\n[Interrupted by user]");
                break;
            }

            int nextStep = iteration + 1;
            if (runController != null && !runController.beforeStep(nextStep)) {
                fullResponse.append("\n[Agent run is " + runController.state().name().toLowerCase() + "]");
                break;
            }
            iteration = nextStep;
            setForegroundActivity("Thinking");

            // Check compaction (model-aware budget; also honors the provider-reported
            // prompt size of the previous call, which sees system prompt + tool defs)
            long projectedInputTokens = projectedInputTokens(null);
            if (compactionService.needsCompaction(projectedInputTokens) && isDirectMode()) {
                // A wholesale checkpoint is legal only at a completed-turn boundary.
                // Mid-exchange, shrink provider tool bodies in place while retaining
                // the canonical ledger and all call/result identifiers verbatim.
                directLlmClient.compactToolHistory(
                        8, CompactionService::summarizeToolResultContent);
            }

            // Rebuild after every iteration. activate_tools mutates the active capability
            // set, so its selected group must be visible to the very next model call.
            ArrayNode toolDefs = iteration == 1
                    ? initialToolDefs
                    : toolDefinitions(agent, progressiveToolLoading);
            ConversationLedger.Snapshot ledgerBeforeRequest = conversationLedger.snapshot();

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
                emitLine("\n" + renderer.yellow("  ⊘ Interrupted by user"));
                fullResponse.append("\n[Interrupted by user]");
                break;
            }

            if ((result.nativeCompactionSummary != null
                    && !result.nativeCompactionSummary.isBlank())
                    || result.nativeCompactionPayload != null) {
                int tokensBefore = compactionService.estimateTokens(
                        ledgerBeforeRequest.activeEntries());
                String portableSummary = result.nativeCompactionSummary;
                if (portableSummary == null || portableSummary.isBlank()) {
                    portableSummary = compactionService.renderDigest(
                            ledgerBeforeRequest.activeEntries());
                }
                int tokensAfter = result.contextInputTokens > 0L
                        ? (int) Math.min(Integer.MAX_VALUE, result.contextInputTokens)
                        : compactionService.estimateTextTokens(portableSummary);
                long coveredThrough = ledgerBeforeRequest.coveredThroughForPrefix(
                        ledgerBeforeRequest.activeEntries().size());
                String model = agent.getModelOverride() != null
                        ? agent.getModelOverride() : directLlmClient.getConfiguredModel();
                boolean committed = result.nativeCompactionPayload == null
                        ? conversationLedger.commitCompaction(
                                ledgerBeforeRequest.version(), coveredThrough, portableSummary,
                                "native-" + result.nativeCompactionStrategy,
                                directLlmClient.getConfiguredProvider(), model,
                                tokensBefore, tokensAfter)
                        : conversationLedger.commitNativeCompaction(
                                ledgerBeforeRequest.version(), coveredThrough, portableSummary,
                                "native-" + result.nativeCompactionStrategy,
                                directLlmClient.getConfiguredProvider(), model,
                                tokensBefore, tokensAfter, result.nativeCompactionPayload);
                if (committed) {
                    emitLine(renderer.renderCompactionNotice(tokensBefore, tokensAfter));
                    if (sessionMetrics != null) {
                        sessionMetrics.recordCompaction(tokensBefore, tokensAfter);
                    }
                }
            }

            // Accumulate text output
            if (!result.text.isEmpty()) {
                fullResponse.append(result.text);
                conversationLedger.append(
                        CompactionService.ConversationEntry.assistant(result.text));
            }

            // ── Inline enforcer check ─────────────────────────────────────
            if (inlineEnforcerEnabled && inlineEnforcer != null && !result.text.isEmpty()) {
                ai.kompile.cli.main.chat.enforcer.EnforcerDecision decision =
                        inlineEnforcer.evaluate(currentMessage, result.text, inlineEnforcerPolicy, iteration);
                if (decision.isStop()) {
                    // Hard stop — reject and notify
                    emitLine("\n" + renderer.red("[enforcer] BLOCKED: "
                            + String.join("; ", decision.getViolations())));
                    fullResponse.append("\n[Blocked by enforcer]");
                    break;
                } else if (!decision.isCompliant()) {
                    // Violation with correction — feed the correction prompt back
                    emitLine("\n" + renderer.yellow("[enforcer] violation: "
                            + String.join("; ", decision.getViolations())));
                    emitLine(renderer.yellow("[enforcer] sending correction (attempt "
                            + iteration + "/" + inlineEnforcerMaxCorrections + ")"));
                    if (iteration <= inlineEnforcerMaxCorrections) {
                        currentMessage = decision.getCorrectionPrompt();
                        pendingToolResults = null;
                        conversationLedger.append(
                                CompactionService.ConversationEntry.user(currentMessage));
                        continue;
                    } else {
                        emitLine(renderer.red("[enforcer] max corrections exceeded, accepting"));
                    }
                }
            }

            // A queued message can continue the same owner immediately after a model
            // response, avoiding an end-of-turn dequeue/re-dispatch race.
            if (result.toolCalls.isEmpty()) {
                String queuedMessage = claimQueuedMessage();
                if (queuedMessage != null && !queuedMessage.isBlank()) {
                    currentMessage = queuedMessage;
                    pendingToolResults = null;
                    conversationLedger.append(
                            CompactionService.ConversationEntry.user(queuedMessage));
                    continue;
                }
                if (runController != null) runController.afterStep();
                break;
            }

            // Execute tool calls with proper rendering
            emitLine("");
            List<ToolCallResult> toolResults = new ArrayList<>();

            // Record the complete provider request before executing anything.
            // Every branch below publishes exactly one matching result, including
            // denial, missing-tool, exception, and cancellation outcomes.
            for (ToolCallRequest requested : result.toolCalls) {
                conversationLedger.append(CompactionService.ConversationEntry.toolCall(
                        requested.name, requested.id,
                        requested.arguments == null ? "{}" : requested.arguments.toString()));
            }

            String queuedMessage = null;
            for (int toolIndex = 0; toolIndex < result.toolCalls.size(); toolIndex++) {
                ToolCallRequest call = result.toolCalls.get(toolIndex);
                // Check cancellation before each tool
                if (isCancelled()) {
                    emitLine("\n" + renderer.yellow("  ⊘ Interrupted by user — skipping remaining tools"));
                    fullResponse.append("\n[Interrupted by user — tools skipped]");
                    for (int skippedIndex = toolIndex;
                         skippedIndex < result.toolCalls.size(); skippedIndex++) {
                        ToolCallRequest skipped = result.toolCalls.get(skippedIndex);
                        conversationLedger.append(CompactionService.ConversationEntry.toolResult(
                                skipped.name, skipped.id, "Cancelled before tool execution"));
                    }
                    break;
                }

                // This is the safe steering point: the previous tool (if any) has
                // returned and no next tool/subprocess has started yet.
                queuedMessage = claimQueuedMessage();
                if (queuedMessage != null && !queuedMessage.isBlank()) {
                    addSupersededToolResults(
                            result.toolCalls, toolIndex, toolResults, queuedMessage);
                    break;
                }

                String normalizedToolName = TerminalRenderer.stripMcpPrefix(call.name);
                String rawToolInput = call.arguments != null ? call.arguments.toString() : "";

                if (runController != null) {
                    AgentRunController.Decision decision = runController.beforeTool(call.name, call.arguments);
                    if (!decision.allowed()) {
                        String denied = "Tool call held: " + decision.reason();
                        fireFirstOutput();
                        notifyToolDenied(call, rawToolInput, decision.reason());
                        emitLine(renderer.renderToolCallDenied(call.name, decision.reason()));
                        toolResults.add(new ToolCallResult(call.id, call.name, denied, true));
                        conversationLedger.append(CompactionService.ConversationEntry.toolResult(
                                call.name, call.id, denied));
                        if (sessionMetrics != null) sessionMetrics.recordToolCall(call.name, true, 0);
                        continue;
                    }
                }

                // Every tool is visible inline. The previous read-only grouping hid
                // read/grep/glob calls until the entire batch had completed.
                fireFirstOutput();
                String callSummary = TerminalRenderer.summarizeToolCall(
                        call.name, rawToolInput, 96);
                setForegroundActivity("Working: " + callSummary);
                notifyToolStart(call, rawToolInput);
                String transcriptKey = "tool:" + sessionId + ":"
                        + transcriptBlockSequence.incrementAndGet() + ":"
                        + (call.id == null ? "" : call.id);
                ToolTranscriptBlock transcriptBlock = new ToolTranscriptBlock(
                        transcriptKey, call.name, rawToolInput);
                ToolContext callToolContext = toolContext.forkForToolExecution();
                callToolContext.setOutputConsumer(transcriptBlock::appendOutput);

                // JLine owns the cursor while the asynchronous REPL accepts queued
                // input. In that mode the bottom status bar is the activity spinner;
                // a carriage-return spinner would erase the user's draft.
                TerminalRenderer.SpinnerHandle spinner = null;
                if (!ChatCompleter.hasLineReader()) {
                    spinner = renderer.startSpinner(call.name);
                }

                try {
                    CliTool tool = toolRegistry.get(call.name);
                    if (tool == null) {
                        if (spinner != null) spinner.stop();
                        String errMsg = "Unknown tool: " + call.name;
                        ToolResult missing = ToolResult.error(errMsg);
                        notifyToolComplete(call, rawToolInput, missing);
                        transcriptBlock.complete(missing);
                        toolResults.add(new ToolCallResult(call.id, call.name, errMsg, true));
                        conversationLedger.append(CompactionService.ConversationEntry.toolResult(
                                call.name, call.id, errMsg));
                        if (sessionMetrics != null) sessionMetrics.recordToolCall(call.name, true, 0);
                        continue;
                    }

                    long toolStart = System.currentTimeMillis();
                    ToolResult toolResult = executeToolInterruptibly(
                            tool, call.arguments, callToolContext, call.name);
                    long toolDurationMs = System.currentTimeMillis() - toolStart;

                    // Truncate large outputs
                    OutputTruncator.TruncationResult truncResult =
                            truncator.truncate(toolResult.getOutput(), call.name);
                    if (truncResult.isTruncated()) {
                        toolResult = new ToolResult(toolResult.getTitle(),
                                truncResult.getOutput(), toolResult.getMetadata(), toolResult.isError());
                    }

                    if (spinner != null) spinner.stop();

                    notifyToolComplete(call, rawToolInput, toolResult);
                    transcriptBlock.complete(toolResult);

                    // Render inline todo updates after todowrite calls
                    if ("todowrite".equals(call.name) && !toolResult.isError()) {
                        List<TodoWriteTool.TodoItem> todos = TodoWriteTool.getTodos(toolContext.getSessionId());
                        if (!todos.isEmpty()) {
                            emitLine(renderer.renderTodoList(todos));
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
                        conversationLedger.append(CompactionService.ConversationEntry.toolResult(
                                call.name, call.id, toolResult.getOutput()));
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

                    // Index the tool call so kompile-managed local/headless agent sessions surface
                    // in the MCP Hub tool-call catalog alongside passthrough + MCP server calls.
                    ToolCallIndex.getInstance().record(
                            toolContext.getSessionId(), call.name, argsStr,
                            toolContext.getAgent() != null ? toolContext.getAgent().getName() : "kompile-agent",
                            "local-chat", toolResult.isError(), toolDurationMs,
                            toolContext.getWorkingDirectory() != null
                                    ? toolContext.getWorkingDirectory().toString() : null);

                    // Track in conversation history (include file path for compaction)
                    conversationLedger.append(CompactionService.ConversationEntry.toolResult(
                            call.name, call.id, outputWithPath));

                } catch (ToolExecutionException e) {
                    if (spinner != null) spinner.stop();

                    if (e.isPermissionDenied()) {
                        notifyToolDenied(call, rawToolInput, e.getMessage());
                        transcriptBlock.completeRendered(
                                renderer.renderToolCallDenied(call.name, e.getMessage()));
                    } else {
                        ToolResult failed = ToolResult.error(e.getMessage());
                        notifyToolComplete(call, rawToolInput, failed);
                        transcriptBlock.complete(failed);
                    }

                    String errMsg = "Error: " + e.getMessage();
                    toolResultStore.save(call.name, call.id, null, errMsg, true);
                    toolResults.add(new ToolCallResult(call.id, call.name, errMsg, true));
                    conversationLedger.append(CompactionService.ConversationEntry.toolResult(
                            call.name, call.id, errMsg));
                    if (sessionMetrics != null) sessionMetrics.recordToolCall(call.name, true, 0);
                }
            }

            // Stop before claiming queued input: exit_plan_mode ends this owner,
            // so anything polled here would otherwise be removed and discarded.
            if (exitPlanModeTool != null && exitPlanModeTool.isPlanApproved()) {
                break;
            }

            // Also poll after the last tool, before the follow-up model request.
            if ((queuedMessage == null || queuedMessage.isBlank()) && !isCancelled()) {
                queuedMessage = claimQueuedMessage();
            }

            if (runController != null) runController.afterStep();

            // Set up next iteration with tool results
            pendingToolResults = toolResults;
            currentMessage = queuedMessage == null || queuedMessage.isBlank()
                    ? null : queuedMessage;
            if (currentMessage != null) {
                conversationLedger.append(
                        CompactionService.ConversationEntry.user(currentMessage));
            }
        }

        // Cleanup old truncation files
        truncator.cleanupOldFiles();

        // A cancelled direct turn may have left provider-native tool-call envelopes
        // without a submitted result. Reproject the durable ledger as portable text
        // before the queued successor starts so the next provider request is valid.
        if (isCancelled() && directLlmClient != null) {
            rebuildDirectHistoryForProviderSwitch();
        }

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

        activeToolAbortSignal.compareAndSet(turnToolAbort, null);
        return output;
    }

    private void addSupersededToolResults(
            List<ToolCallRequest> calls,
            int firstSkippedIndex,
            List<ToolCallResult> toolResults,
            String queuedMessage) {
        String reason = "Skipped before execution because queued user guidance superseded "
                + "the remaining tool calls: " + queuedMessage;
        emitLine(renderer.dim("  ↪ Queued guidance superseded "
                + (calls.size() - firstSkippedIndex) + " pending tool call(s)"));
        for (int i = firstSkippedIndex; i < calls.size(); i++) {
            ToolCallRequest skipped = calls.get(i);
            String rawToolInput = skipped.arguments != null ? skipped.arguments.toString() : "";
            ToolResult skippedResult = ToolResult.error(reason);
            notifyToolDenied(skipped, rawToolInput, reason);
            toolResults.add(new ToolCallResult(
                    skipped.id, skipped.name, skippedResult.getOutput(), true));
            conversationLedger.append(CompactionService.ConversationEntry.toolResult(
                    skipped.name, skipped.id, skippedResult.getOutput()));
            if (sessionMetrics != null) {
                sessionMetrics.recordToolCall(skipped.name, true, 0);
            }
        }
    }

    /**
     * Execute a synchronous tool away from the dispatch owner. Escape can then
     * release the owner immediately even if an extension blocks or swallows
     * interruption; cooperative built-ins also observe ToolContext's shared abort
     * signal and terminate their underlying subprocess/network operation.
     */
    private ToolResult executeToolInterruptibly(
            CliTool tool, JsonNode arguments, ToolContext context, String toolName)
            throws ToolExecutionException {
        ToolContext executionContext = context.forkForToolExecution();
        FutureTask<ToolResult> execution = new FutureTask<>(
                () -> tool.execute(arguments, executionContext));
        Thread worker = new Thread(execution,
                "chat-tool-" + (toolName == null ? "unknown"
                        : toolName.replaceAll("[^A-Za-z0-9_.-]", "_")));
        worker.setDaemon(true);
        worker.start();

        try {
            while (true) {
                if (isCancelled() || context.isAborted()) {
                    executionContext.setOutputConsumer(ignored -> { });
                    execution.cancel(true);
                    throw new ToolExecutionException("Cancelled by user");
                }
                try {
                    return execution.get(100, TimeUnit.MILLISECONDS);
                } catch (TimeoutException ignored) {
                    // Poll the shared abort signal without blocking the dispatch owner.
                }
            }
        } catch (InterruptedException e) {
            context.abort();
            executionContext.setOutputConsumer(ignored -> { });
            execution.cancel(true);
            Thread.currentThread().interrupt();
            throw new ToolExecutionException("Cancelled by user", e);
        } catch (CancellationException e) {
            executionContext.setOutputConsumer(ignored -> { });
            throw new ToolExecutionException("Cancelled by user", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof ToolExecutionException toolFailure) {
                throw toolFailure;
            }
            throw new ToolExecutionException(
                    cause == null ? "Tool execution failed" : cause.getMessage(), cause);
        }
    }

    /** One replaceable provider-style block for a running tool and its live output. */
    private final class ToolTranscriptBlock {
        private final String key;
        private final String toolName;
        private final String rawInput;
        private final String start;
        private final Deque<String> liveOutput = new ArrayDeque<>();
        private int liveOutputChars;
        private long omittedOutputLines;
        private boolean managed;
        private boolean updateScheduled;
        private boolean backgroundMarkerPublished;
        private ToolResult result;
        private String terminalHeader;

        private ToolTranscriptBlock(String key, String toolName, String rawInput) {
            this.key = key;
            this.toolName = toolName;
            this.rawInput = rawInput;
            this.start = renderer.renderToolCallStart(toolName, rawInput);
            if (backgroundOutputConsumer.get() != null) {
                this.backgroundMarkerPublished = true;
                this.managed = false;
                emitLine(start);
            } else {
                this.managed = ChatCompleter.upsertTranscriptBlock(key, start);
                if (!managed) emitLine(start);
            }
        }

        private synchronized void appendOutput(String output) {
            if (routeToBackground(output)) return;
            if (!managed) {
                emitLine(output);
                return;
            }
            String rendered = renderer.renderToolOutput(output);
            for (String rawLine : rendered.split("\\R", -1)) {
                String line = rawLine.length() <= MAX_LIVE_TOOL_LINE_CHARS
                        ? rawLine
                        : rawLine.substring(0, MAX_LIVE_TOOL_LINE_CHARS - 1) + "…";
                liveOutput.addLast(line);
                liveOutputChars += line.length();
            }
            while (liveOutput.size() > MAX_LIVE_TOOL_OUTPUT_LINES
                    || liveOutputChars > MAX_LIVE_TOOL_OUTPUT_CHARS) {
                String omitted = liveOutput.removeFirst();
                liveOutputChars -= omitted.length();
                omittedOutputLines++;
            }
            scheduleManagedUpdate();
        }

        private synchronized void complete(ToolResult completed) {
            this.result = completed;
            String completedBlock = renderer.renderToolCallComplete(toolName, rawInput, completed);
            if (!routeToBackground(completedBlock)) publishCompletion(completedBlock);
        }

        private synchronized void completeRendered(String rendered) {
            this.terminalHeader = rendered;
            if (!routeToBackground(rendered)) publishCompletion(rendered);
        }

        private boolean routeToBackground(String output) {
            if (backgroundOutputConsumer.get() == null) return false;
            if (managed && !backgroundMarkerPublished) {
                ChatCompleter.upsertTranscriptBlock(key,
                        start + "\n" + renderer.dim("  ↳ continued in background"));
                backgroundMarkerPublished = true;
                managed = false;
            }
            emitLine(output);
            return true;
        }

        private void scheduleManagedUpdate() {
            if (!managed || updateScheduled) return;
            updateScheduled = true;
            CompletableFuture.delayedExecutor(
                    LIVE_TOOL_FRAME_DELAY_MS, TimeUnit.MILLISECONDS)
                    .execute(this::publishManagedUpdate);
        }

        private synchronized void publishManagedUpdate() {
            updateScheduled = false;
            if (managed) {
                managed = ChatCompleter.upsertTranscriptBlock(key, renderBlock());
            }
        }

        private void publishCompletion(String appendOnlyRendering) {
            if (managed) {
                managed = ChatCompleter.upsertTranscriptBlock(key, renderBlock());
            }
            if (!managed) emitLine(appendOnlyRendering);
        }

        private String renderBlock() {
            String header = terminalHeader != null
                    ? terminalHeader
                    : result != null
                            ? renderer.renderToolCallSummary(toolName, rawInput, result)
                            : start;
            StringBuilder block = new StringBuilder(header);
            if (omittedOutputLines > 0) {
                block.append('\n').append(renderer.renderToolOutput(
                        "… (" + omittedOutputLines + " earlier output lines omitted)"));
            }
            for (String line : liveOutput) block.append('\n').append(line);
            if (result != null) {
                String detail = renderer.renderToolResultDetail(toolName, rawInput, result);
                if (!detail.isBlank()) block.append('\n').append(detail);
            }
            return block.toString();
        }
    }

    private void notifyToolStart(ToolCallRequest call, String rawInput) {
        ToolActivityListener listener = toolActivityListener;
        if (listener == null) return;
        try {
            listener.onToolStart(call.id, call.name, rawInput);
        } catch (RuntimeException ignored) {
            // A status-panel failure must not hide the inline transcript event.
        }
    }

    private void notifyToolComplete(ToolCallRequest call, String rawInput, ToolResult result) {
        ToolActivityListener listener = toolActivityListener;
        if (listener == null) return;
        try {
            listener.onToolComplete(call.id, call.name, rawInput, result);
        } catch (RuntimeException ignored) {
            // A status-panel failure must not hide the inline transcript event.
        }
    }

    private void notifyToolDenied(ToolCallRequest call, String rawInput, String reason) {
        ToolActivityListener listener = toolActivityListener;
        if (listener == null) return;
        try {
            listener.onToolDenied(call.id, call.name, rawInput, reason);
        } catch (RuntimeException ignored) {
            // A status-panel failure must not hide the inline transcript event.
        }
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
            emitLine(renderer.dim("  Side panel hidden"));
            return;
        }

        String title = snapshot.title() != null && !snapshot.title().isBlank()
                ? snapshot.title() : "Side Panel";
        String content = boundedSidePanelContent(snapshot.content());
        emitLine(asciiRenderer.panel(title, content));
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

        StreamingMarkdownRenderer markdownRenderer =
                new StreamingMarkdownRenderer(asciiRenderer, this::emitLine);
        java.util.function.Consumer<String> previousConsumer = directLlmClient.getOutputConsumer();
        DirectLlmClient.StreamResult directResult;
        directLlmClient.setOutputConsumer(chunk -> {
            fireFirstOutput();
            setForegroundActivity("Responding");
            markdownRenderer.accept(chunk);
        });
        try {
            directResult = directLlmClient.streamChat(message, systemPrompt, toolDefs, directToolResults, modelOverride, attachments);
        } finally {
            markdownRenderer.flush();
            directLlmClient.setOutputConsumer(previousConsumer);
        }

        // Record token usage from API response
        if (sessionMetrics != null) {
            sessionMetrics.recordTokenUsage(
                    directResult.inputTokens + directResult.compactionInputTokens,
                    directResult.outputTokens + directResult.compactionOutputTokens,
                    directResult.cacheReadTokens, directResult.cacheCreationTokens);
        }
        long contextInputTokens = directResult.contextInputTokens();
        if (contextInputTokens > 0) {
            // Cached tokens still occupy the context even though providers bill them
            // separately. Capture the matching tracked-history size so later growth
            // can be projected before the next request.
            lastReportedInputTokens = contextInputTokens;
            lastReportedHistoryTokens = compactionService.estimateTokens(
                    conversationLedger.snapshot().activeEntries());
        }

        result.text = directResult.text;
        result.nativeCompactionSummary = directResult.nativeCompactionSummary;
        result.nativeCompactionStrategy = directResult.nativeCompactionStrategy;
        result.nativeCompactionPayload = directResult.nativeCompactionPayload;
        result.contextInputTokens = contextInputTokens;
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

    private String systemPromptForServerAgent(String systemPrompt, String serverAgent) {
        if (systemPrompt == null || systemPrompt.isEmpty()) return "";
        String normalized = serverAgent == null ? "" : serverAgent.toLowerCase(Locale.ROOT);
        if (!normalized.contains("codex") && !normalized.contains("opencode")) {
            return systemPrompt;
        }
        String projectPrompt = projectChatContext.renderSystemPrompt();
        return projectPrompt.isBlank()
                ? systemPrompt
                : systemPrompt.replace(projectPrompt, projectChatContext.renderSkillsPrompt());
    }

    private StreamResult streamServerTurn(String message, String sessionId, String serverAgent,
                                           boolean ragEnabled, String systemPrompt,
                                           ArrayNode toolDefs, List<ToolCallResult> toolResults) {
        StreamResult result = new StreamResult();

        StreamingMarkdownRenderer markdownRenderer =
                new StreamingMarkdownRenderer(asciiRenderer, this::emitLine);

        try {
            ObjectNode request = objectMapper.createObjectNode();
            if (message != null) {
                request.put("message", message);
            }
            request.put("agentName", serverAgent);
            request.put("enableRag", ragEnabled);
            request.put("skipPermissions", true);
            request.put("timeoutSeconds", 300);
            // Agy-backed server agents need the CLI project context to create
            // prompt files and resolve their project-scoped configuration.
            if (workingDirectory != null) {
                request.put("workingDirectory", workingDirectory.toAbsolutePath().toString());
            }
            // Current Gemini CLI versions load MCP from project .gemini/settings.json
            // and reject the legacy --mcp-server flag. The crawl wrapper provisions
            // that project transport; other agents continue using server-side injection.
            boolean geminiServerAgent = serverAgent != null
                    && serverAgent.toLowerCase(Locale.ROOT).contains("gemini");
            request.put("injectMcpTools", !geminiServerAgent);

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

            String effectiveSystemPrompt = systemPromptForServerAgent(systemPrompt, serverAgent);
            if (!effectiveSystemPrompt.isEmpty()) {
                request.put("systemPromptOverride", effectiveSystemPrompt);
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
            InputStream responseBody = response.body();
            activeResponseBody.set(responseBody);
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(responseBody))) {
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
            activeResponseBody.compareAndSet(responseBody, null);
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
                fireFirstOutput();
                setForegroundActivity("Responding");
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
                    emitLine(renderer.red("  [Error parsing tool call: " + e.getMessage() + "]"));
                }
                break;

            case "start":
                markdownRenderer.flush();
                try {
                    JsonNode json = objectMapper.readTree(data);
                    String agent = json.path("agent").asText("");
                    String processId = json.path("processId").asText("");
                    if (!processId.isBlank()) {
                        activeRemoteProcessId.set(processId);
                        if (isCancelled()) {
                            activeRemoteProcessId.compareAndSet(processId, null);
                            cancelActiveTurn();
                        }
                    }
                    if (!agent.isEmpty()) {
                        emitLine(renderer.dim("[Agent: " + agent + "]"));
                    }
                } catch (Exception ignored) {}
                break;

            case "sources":
                markdownRenderer.flush();
                try {
                    JsonNode sources = objectMapper.readTree(data);
                    if (sources.isArray() && sources.size() > 0) {
                        emitLine(renderer.dim("[Retrieved " + sources.size() + " documents]"));
                    }
                } catch (Exception ignored) {}
                break;

            case "stats":
                markdownRenderer.flush();
                try {
                    JsonNode stats = objectMapper.readTree(data);
                    long durationMs = stats.path("durationMs").asLong(0);
                    if (durationMs > 0) {
                        emitLine(renderer.dim("  [completed in " + durationMs + "ms]"));
                    }
                } catch (Exception ignored) {}
                break;

            case "error":
                markdownRenderer.flush();
                try {
                    JsonNode error = objectMapper.readTree(data);
                    String msg = error.path("message").asText(data);
                    emitLine(renderer.red("\n[Error: " + msg + "]"));
                    result.text += "\n[Error: " + msg + "]";
                } catch (Exception e) {
                    emitLine(renderer.red("\n[Error: " + data + "]"));
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
        String nativeCompactionSummary;
        String nativeCompactionStrategy;
        JsonNode nativeCompactionPayload;
        long contextInputTokens;
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
