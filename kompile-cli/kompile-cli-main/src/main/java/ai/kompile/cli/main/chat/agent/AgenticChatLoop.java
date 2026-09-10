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
import ai.kompile.cli.main.chat.ChatSessionContext;
import ai.kompile.cli.main.chat.ReminderManager;
import ai.kompile.cli.main.chat.ToolCallIndex;
import ai.kompile.cli.main.chat.config.ChatConfig;
import ai.kompile.cli.main.chat.config.DirectLlmClient;
import ai.kompile.cli.main.chat.config.IdleTimeoutInputStream;
import ai.kompile.cli.main.chat.config.ModelContextResolver;
import ai.kompile.cli.main.chat.config.ProviderConnectivityPolicy;
import ai.kompile.cli.main.chat.context.ConversationBoundaryPlanner;
import ai.kompile.cli.main.chat.context.ConversationLedger;
import ai.kompile.cli.main.chat.enforcer.EnforcerConversationContext;
import ai.kompile.cli.main.chat.enforcer.EnforcerDecision;
import ai.kompile.cli.main.chat.enforcer.EnforcerEvaluator;
import ai.kompile.cli.main.chat.enforcer.EnforcerJudge;
import ai.kompile.cli.main.chat.enforcer.EnforcerPolicy;
import ai.kompile.cli.main.chat.enforcer.EnforcerToolCallDecision;
import ai.kompile.cli.main.chat.enforcer.JudgeToolPolicy;
import ai.kompile.cli.main.chat.harness.PerformanceHarness;
import ai.kompile.cli.main.chat.permission.PermissionService;
import ai.kompile.cli.main.chat.render.AsciiRenderer;
import ai.kompile.cli.main.chat.render.CompactionProgress;
import ai.kompile.cli.main.chat.render.CompactionProgressIndicator;
import ai.kompile.cli.main.chat.render.CompactionService;
import ai.kompile.cli.main.chat.render.ConversationSummarizer;
import ai.kompile.cli.main.chat.render.OutputTruncator;
import ai.kompile.cli.main.chat.render.StreamingMarkdownRenderer;
import ai.kompile.cli.main.chat.render.TerminalRenderer;
import ai.kompile.cli.main.chat.skill.SkillRegistry;
import ai.kompile.cli.main.chat.tui.SidePanelManager;
import ai.kompile.cli.main.chat.tools.*;
import ai.kompile.cli.main.chat.workflow.WorkflowController;
import ai.kompile.cli.main.chat.workflow.WorkflowPolicy;
import ai.kompile.utils.StringUtils;
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
import java.net.http.HttpHeaders;
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
import java.util.function.BooleanSupplier;
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

    private final ChatSessionContext sessionContext = ChatSessionContext.current();

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

    public interface ServerEventListener {
        default void onBackendStarted(String agent, String processId) { }
        default void onSources(JsonNode sources) { }
        default void onStats(JsonNode stats) { }
    }

    /** Synchronous pre-execution review for a proposed main-REPL MCP tool call. */
    @FunctionalInterface
    public interface ToolCallInterceptor {
        EnforcerToolCallDecision intercept(
                String userPrompt, String assistantContext,
                String toolName, String toolInput) throws Exception;
    }

    /** Direct feedback/control lane from an auxiliary REPL to the main REPL owner. */
    @FunctionalInterface
    public interface SupervisorFeedbackHandler {
        boolean submit(String source, String feedback, boolean interrupt);
    }

    /**
     * A queue item claimed at a model/tool boundary but not yet owned by the
     * next provider request. Cancellation can restore the claim until accept()
     * linearizes ownership at the outbound request boundary.
     */
    public static final class QueuedInput {
        private enum State { CLAIMED, ACCEPTED, RESTORED }

        private final String content;
        private final BooleanSupplier acceptance;
        private final Runnable restoration;
        private State state = State.CLAIMED;

        public QueuedInput(
                String content, BooleanSupplier acceptance, Runnable restoration) {
            this.content = content == null ? "" : content;
            this.acceptance = acceptance != null ? acceptance : () -> true;
            this.restoration = restoration != null ? restoration : () -> { };
        }

        public static QueuedInput immediate(String content) {
            return new QueuedInput(content, () -> true, () -> { });
        }

        public String content() {
            return content;
        }

        public synchronized boolean accept() {
            if (state != State.CLAIMED) return state == State.ACCEPTED;
            if (!acceptance.getAsBoolean()) return false;
            state = State.ACCEPTED;
            return true;
        }

        public synchronized void restore() {
            if (state != State.CLAIMED) return;
            state = State.RESTORED;
            restoration.run();
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
    private volatile ToolCallInterceptor judgeToolCallInterceptor;
    private volatile ToolCallInterceptor enforcerToolCallInterceptor;
    private volatile SupervisorFeedbackHandler supervisorFeedbackHandler;
    private volatile Consumer<String> inlineEnforcerActivityListener;
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
    private volatile ServerEventListener serverEventListener;
    /** Raw server-model deltas for non-terminal transports such as JSONL. */
    private volatile Consumer<String> assistantDeltaListener;
    private final AtomicLong transcriptBlockSequence = new AtomicLong();
    private volatile Supplier<QueuedInput> queuedMessageSupplier = () -> null;
    private volatile ReminderManager reminderManager;
    private final AtomicReference<Consumer<String>> backgroundOutputConsumer = new AtomicReference<>();
    private final AtomicBoolean blockingSubagentInvocation = new AtomicBoolean(false);
    private volatile Runnable backgroundEligibilityListener = () -> { };

    // Inline enforcer: deterministic or LLM-backed checker applied to every turn.
    // Auto-loaded from .kompile/enforcer-config.json when present. Toggle with /enforcer on|off.
    private volatile EnforcerEvaluator inlineEnforcer;
    private volatile EnforcerPolicy inlineEnforcerPolicy;
    private volatile boolean inlineEnforcerEnabled = false;
    // Configuration/user intent is independent of a loaded or available evaluator.
    private volatile boolean inlineEnforcerRequested = false;
    private int inlineEnforcerMaxCorrections = 3;

    // User control over the judge (guidance + one-shot report-only override), captured
    // once per turn via beginTurn() so a single user decision covers every iteration
    // and tool call inside that turn.
    private volatile ai.kompile.cli.main.chat.enforcer.JudgeControl judgeControl;
    private volatile ai.kompile.cli.main.chat.enforcer.JudgeControl.TurnSnapshot activeJudgeSnapshot =
            ai.kompile.cli.main.chat.enforcer.JudgeControl.TurnSnapshot.NONE;

    // Direction judge: goal-drift supervision for long conversations. Strictly opt-in
    // (configured + enabled), and deliberately NOT subject to the one-shot /judge
    // override: direction monitoring is its own supervision contract.
    private volatile ai.kompile.cli.main.chat.enforcer.DirectionJudge directionJudge;

    // Host-applied workflow state is deterministic and independent from probabilistic
    // judge verdicts. The one-shot judge override therefore never bypasses this gate.
    private final WorkflowController workflowController;


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
            Consumer<ai.kompile.cli.main.chat.tools.BackgroundProcessManager.ProcessEntry> exitCallback =
                    sessionContext.wrapConsumer(entry -> {
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
            processManager.setExitCallback(exitCallback::accept);
        }

        // Initialize current agent config with default
        this.currentAgentConfig = agentRegistry.getDefault();

        SkillRegistry effectiveSkills = skillRegistry != null
                ? skillRegistry : ProjectChatContext.load(workingDirectory).skillRegistry();
        this.projectChatContext = ProjectChatContext.load(workingDirectory, effectiveSkills);
        this.agentsMdContent = projectChatContext.agentsMdContent();
        this.workflowController = new WorkflowController(
                workingDirectory, effectiveSkills, toolRegistry);
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

    public void setJudgeToolCallInterceptor(ToolCallInterceptor interceptor) {
        this.judgeToolCallInterceptor = interceptor;
    }

    public void setEnforcerToolCallInterceptor(ToolCallInterceptor interceptor) {
        this.enforcerToolCallInterceptor = interceptor;
        if (inlineEnforcer == null) {
            this.inlineEnforcerEnabled = interceptor != null;
            this.inlineEnforcerRequested = interceptor != null;
        }
    }

    public void setSupervisorFeedbackHandler(SupervisorFeedbackHandler handler) {
        this.supervisorFeedbackHandler = handler;
    }

    /** Bind the session's user-judge control (guidance + one-shot override). */
    public void setJudgeControl(ai.kompile.cli.main.chat.enforcer.JudgeControl control) {
        this.judgeControl = control;
    }

    /**
     * Bind the direction judge. When present (and enabled), the loop periodically asks it
     * whether the conversation is still moving toward the user's goal; drift verdicts
     * redirect the agent in place and, after the redirect budget is exhausted, halt the
     * turn via the supervisor feedback lane. The judge must already be configured and
     * enabled by the caller — the loop never constructs or enables one implicitly.
     */
    public void setDirectionJudge(ai.kompile.cli.main.chat.enforcer.DirectionJudge judge) {
        this.directionJudge = judge;
    }

    public ai.kompile.cli.main.chat.enforcer.DirectionJudge getDirectionJudge() {
        return directionJudge;
    }

    public void setInlineEnforcerActivityListener(Consumer<String> listener) {
        this.inlineEnforcerActivityListener = listener;
    }

    /**
     * Observe raw assistant text chunks before terminal markdown rendering. Direct
     * model clients already expose their raw stream; this closes the equivalent
     * server-SSE gap for headless transports.
     */
    public void setAssistantDeltaListener(Consumer<String> listener) {
        this.assistantDeltaListener = listener;
    }

    public void setServerEventListener(ServerEventListener listener) {
        this.serverEventListener = listener;
    }

    private void notifyAssistantDelta(String chunk) {
        Consumer<String> listener = assistantDeltaListener;
        if (listener == null || chunk == null || chunk.isEmpty()) return;
        try {
            listener.accept(chunk);
        } catch (RuntimeException ignored) {
            // Output observers are transport concerns and must not fail the model turn.
        }
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
    public void setQueuedMessageSupplier(Supplier<QueuedInput> supplier) {
        this.queuedMessageSupplier = supplier != null ? supplier : () -> null;
    }

    /** Apply active session and project reminders at the provider request boundary. */
    public void setReminderManager(ReminderManager reminderManager) {
        this.reminderManager = reminderManager;
        bindReminderConstraints(inlineEnforcer);
    }

    private record ToolDetach(Consumer<String> output, Runnable detached,
                              Consumer<ToolResult> completed) { }
    private final AtomicReference<ToolDetach> toolDetach = new AtomicReference<>();

    public void backgroundActiveTurn(Consumer<String> output, Runnable detached,
                                     Consumer<ToolResult> completed) {
        backgroundActiveTurn(output);
        toolDetach.set(new ToolDetach(output, detached, completed));
    }

    /** Route subsequent turn output to a retained background task. */
    public void backgroundActiveTurn(Consumer<String> outputConsumer) {
        if (outputConsumer != null) {
            backgroundOutputConsumer.set(outputConsumer);
            ChatCompleter.setActivity(null);
        }
    }

    /**
     * True only while the parent turn is synchronously waiting for TaskTool.
     * Main-model thinking and retained interactive follow-ups are deliberately
     * excluded: neither is the subagent invocation Ctrl+B is meant to detach.
     */
    public boolean isBlockingSubagentInvocationActive() {
        return blockingSubagentInvocation.get();
    }

    /** Repaint the owning REPL when TaskTool enters or leaves its blocking phase. */
    public void setBackgroundEligibilityListener(Runnable listener) {
        backgroundEligibilityListener = listener != null ? listener : () -> { };
    }

    private void setBlockingSubagentInvocation(boolean active) {
        if (blockingSubagentInvocation.getAndSet(active) == active) return;
        try {
            backgroundEligibilityListener.run();
        } catch (RuntimeException ignored) {
            // UI observers must never fail the tool invocation.
        }
    }

    /** Restore foreground output routing after the owning turn terminates. */
    public void clearBackgroundOutput() {
        backgroundOutputConsumer.set(null);
        toolDetach.set(null);
    }

    boolean isOutputBackgrounded() {
        return backgroundOutputConsumer.get() != null;
    }

    private QueuedInput claimQueuedMessage() {
        Supplier<QueuedInput> supplier = queuedMessageSupplier;
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
     * Set the inline policy evaluator for this chat loop. When enabled, every LLM response
     * is checked against the configured rules; an LLM judge also receives active reminder
     * constraints. Violations trigger automatic correction attempts.
     */
    public void setInlineEnforcer(EnforcerEvaluator evaluator,
                                   EnforcerPolicy policy,
                                   int maxCorrections) {
        this.inlineEnforcer = evaluator;
        this.inlineEnforcerPolicy = policy;
        // Clearing readiness (e.g. a reload failure) is not a user request to disable policy.
        if (evaluator != null || policy != null) this.inlineEnforcerRequested = true;
        this.inlineEnforcerMaxCorrections = maxCorrections;
        this.inlineEnforcerEnabled = evaluator != null && evaluator.isAvailable();
        bindReminderConstraints(evaluator);
        this.enforcerToolCallInterceptor = evaluator == null ? null
                : (userPrompt, assistantContext, toolName, toolInput) ->
                        evaluateInlineToolCall(evaluator, policy, userPrompt,
                                assistantContext, toolName, toolInput);
        emitInlineEnforcerActivity("[state] "
                + (inlineEnforcerEnabled ? "ready" : "disabled"));
    }

    private void bindReminderConstraints(EnforcerEvaluator evaluator) {
        if (evaluator instanceof EnforcerJudge judge) {
            ReminderManager reminders = reminderManager;
            judge.setReminderSupplier(reminders == null ? null : reminders::enforcementConstraints);
        }
    }

    private EnforcerToolCallDecision evaluateInlineToolCall(
            EnforcerEvaluator evaluator, EnforcerPolicy policy,
            String userPrompt, String assistantContext,
            String toolName, String toolInput) throws Exception {
        if (!(evaluator instanceof EnforcerJudge) && !(evaluator instanceof EnforcerJudge.CapturedEvaluator)) {
            return evaluator.evaluateToolCall(toolName, toolInput, policy);
        }
        List<EnforcerConversationContext.Message> messages = new ArrayList<>(2);
        if (userPrompt != null && !userPrompt.isBlank()) {
            messages.add(new EnforcerConversationContext.Message("user", userPrompt));
        }
        if (assistantContext != null && !assistantContext.isBlank()) {
            messages.add(new EnforcerConversationContext.Message("assistant", assistantContext));
        }
        var context = EnforcerConversationContext.of(messages);
        return evaluator instanceof EnforcerJudge.CapturedEvaluator captured
                ? captured.evaluateToolCall(toolName, toolInput, policy, context)
                : ((EnforcerJudge) evaluator).evaluateToolCall(toolName, toolInput, policy, context);
    }

    /**
     * Toggle enforcer on/off without changing configuration.
     */
    public void setInlineEnforcerEnabled(boolean enabled) {
        this.inlineEnforcerRequested = enabled;
        this.inlineEnforcerEnabled = enabled && inlineEnforcer != null;
        emitInlineEnforcerActivity("[state] "
                + (inlineEnforcerEnabled ? "ready" : "disabled"));
    }

    public boolean isInlineEnforcerEnabled() {
        return inlineEnforcerEnabled;
    }

    public String describeInlineEnforcer() {
        if (inlineEnforcer == null) return null;
        return inlineEnforcer.describe();
    }

    private void emitInlineEnforcerActivity(String event) {
        Consumer<String> listener = inlineEnforcerActivityListener;
        if (listener == null || event == null || event.isBlank()) return;
        try {
            listener.accept(event);
        } catch (RuntimeException ignored) {
            // Monitoring must not change enforcement semantics.
        }
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
     * Build a cache-stable system prefix. Reusable agent/project/workflow instructions
     * precede session-local result paths, and the changing result inventory stays in the
     * durable index instead of rewriting this prompt after every tool call.
     */
    String buildSystemPrompt(AgentConfig agent) {
        StringBuilder sb = new StringBuilder();

        String base = agent.getSystemPrompt();
        if (base != null && !base.isBlank()) {
            sb.append(base.strip());
        }

        String projectPrompt = projectChatContext.renderSystemPrompt();
        if (!projectPrompt.isBlank()) {
            sb.append("\n\n").append(projectPrompt);
        }

        String workflowPrompt = workflowController.activeSystemPrompt();
        if (!workflowPrompt.isBlank()) {
            sb.append("\n\n").append(workflowPrompt);
        }

        if (toolResultStore != null) {
            sb.append("\n\n# Tool Result Files\n\n");
            sb.append("All tool call outputs for this session are saved to: ")
                    .append(toolResultStore.getResultDir()).append("\n");
            sb.append("After context compaction, use the `read` tool with a saved result path.\n");
            sb.append("Use `glob` with pattern \"").append(toolResultStore.getResultDir())
                    .append("/*.txt\" to list results, or read ")
                    .append(toolResultStore.getResultDir().resolve("_index.txt"))
                    .append(" for the complete index.\n");
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

    public boolean isWorkflowActive() {
        return workflowController.isActive();
    }

    public boolean isWorkflowEnforced() {
        return workflowController.isEnforced();
    }

    public WorkflowController.Status workflowStatus() {
        return workflowController.status();
    }

    public void setWorkflowSessionMode(WorkflowPolicy.Mode mode, List<String> requiredSkills) {
        workflowController.setSessionMode(mode, requiredSkills);
    }

    public void setWorkflowGlobalEnabled(boolean enabled) {
        workflowController.setGlobalEnabled(enabled);
    }

    public void setWorkflowSessionEnabled(boolean enabled) {
        workflowController.setSessionEnabled(enabled);
    }

    public void reloadWorkflowConfiguration() {
        workflowController.reload();
    }

    /** Bind durable context state and provider cache affinity before the first turn. */
    public void configureConversationSession(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) return;
        if (directLlmClient != null) {
            directLlmClient.setPromptCacheSessionId(sessionId);
        }
        if (sessionId.equals(conversationSessionId)) return;
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
        return rebuildDirectHistory(entries, clearHistory, skipPortableSummary, true);
    }

    private int rebuildDirectHistory(
            List<CompactionService.ConversationEntry> entries,
            boolean clearHistory,
            boolean skipPortableSummary,
            boolean closeDanglingToolCalls) {
        String replayModel = currentAgentConfig != null
                && currentAgentConfig.getModelOverride() != null
                && !currentAgentConfig.getModelOverride().isBlank()
                ? currentAgentConfig.getModelOverride()
                : directLlmClient.getConfiguredModel();
        return rebuildDirectHistory(
                entries, clearHistory, skipPortableSummary,
                closeDanglingToolCalls, replayModel);
    }

    private int rebuildDirectHistory(
            List<CompactionService.ConversationEntry> entries,
            boolean clearHistory,
            boolean skipPortableSummary,
            boolean closeDanglingToolCalls,
            String replayModel) {
        if (clearHistory) directLlmClient.clearHistory();
        int replayed = 0;
        // Tool calls interrupted before execution have no durable result. Wire
        // APIs reject an unanswered tool envelope, so each pending call is held
        // until its result arrives (or the replay ends) and then closed with a
        // synthetic cancellation result.
        Map<String, CompactionService.ConversationEntry> pendingToolCalls = new LinkedHashMap<>();
        for (int index = 0; index < entries.size();) {
            CompactionService.ConversationEntry entry = entries.get(index);
            if (entry == null || ((entry.content == null || entry.content.isBlank())
                    && entry.type != CompactionService.EntryType.TOOL_CALL
                    && entry.type != CompactionService.EntryType.TOOL_RESULT)) {
                index++;
                continue;
            }
            if (skipPortableSummary && entry.type == CompactionService.EntryType.SYSTEM
                    && entry.content.startsWith(ConversationLedger.SUMMARY_MARKER)) {
                index++;
                continue;
            }
            if (entry.type == CompactionService.EntryType.TOOL_CALL) {
                List<DirectLlmClient.ReplayedToolCallInput> calls = new ArrayList<>();
                while (index < entries.size()) {
                    CompactionService.ConversationEntry call = entries.get(index);
                    if (call == null || call.type != CompactionService.EntryType.TOOL_CALL) break;
                    calls.add(new DirectLlmClient.ReplayedToolCallInput(
                            call.toolName, call.toolCallId, call.content));
                    if (call.toolCallId != null && !call.toolCallId.isBlank()) {
                        pendingToolCalls.put(call.toolCallId, call);
                    }
                    index++;
                }
                directLlmClient.addReplayedToolCalls(calls, replayModel);
                replayed += calls.size();
                continue;
            }
            if (entry.type == CompactionService.EntryType.TOOL_RESULT) {
                List<DirectLlmClient.ToolCallResultInput> results = new ArrayList<>();
                while (index < entries.size()) {
                    CompactionService.ConversationEntry toolResult = entries.get(index);
                    if (toolResult == null
                            || toolResult.type != CompactionService.EntryType.TOOL_RESULT) break;
                    results.add(new DirectLlmClient.ToolCallResultInput(
                            toolResult.toolCallId, toolResult.toolName,
                            toolResult.content, false));
                    if (toolResult.toolCallId != null) {
                        pendingToolCalls.remove(toolResult.toolCallId);
                    }
                    index++;
                }
                directLlmClient.addReplayedToolResults(results, replayModel);
                replayed += results.size();
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
                case TOOL_CALL, TOOL_RESULT -> { }
            }
            index++;
        }
        if (closeDanglingToolCalls) {
            List<DirectLlmClient.ToolCallResultInput> cancellations = new ArrayList<>();
            for (CompactionService.ConversationEntry pending : pendingToolCalls.values()) {
                cancellations.add(new DirectLlmClient.ToolCallResultInput(
                        pending.toolCallId, pending.toolName,
                        "Tool call was cancelled before execution; no result was recorded.",
                        true));
            }
            if (!cancellations.isEmpty()) {
                directLlmClient.addReplayedToolResults(cancellations, replayModel);
            }
            replayed += cancellations.size();
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
        return forceCompact(focusInstruction, null);
    }

    /**
     * Same as {@link #forceCompact(String)} with live progress reporting to the
     * given callback (phase announcements only; never throws through the
     * compaction itself).
     */
    public ForceCompactResult forceCompact(String focusInstruction,
                                           CompactionProgress progress) {
        CompactionProgress progressRef = progress == null
                ? CompactionProgress.NO_OP : progress;
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
        progressRef.phase("Inspecting " + conversationHistory.size() + " conversation entr"
                + (conversationHistory.size() == 1 ? "y" : "ies"));

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
            preserveIndex = conversationHistory.size();
        }

        String modelOverride = currentAgentConfig != null ? currentAgentConfig.getModelOverride() : null;
        progressRef.phase("Checking provider compaction support");
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
            progressRef.phase("Summarizing " + toSummarize.size() + " older entr"
                    + (toSummarize.size() == 1 ? "y" : "ies") + " with the model");
            ConversationSummarizer summarizer = new ConversationSummarizer(directLlmClient);
            summary = summarizer.summarize(toSummarize, focusInstruction, modelOverride);
        }

        if (isCancelled()) {
            if (nativeResult.applied()) rebuildDirectHistoryForProviderSwitch();
            return ForceCompactResult.failed("Compaction was cancelled; history unchanged.");
        }

        if (summary.isEmpty() || looksLikeFailedSummary(summary.getSummary())) {
            if (nativeResult.applied()) rebuildDirectHistoryForProviderSwitch();
            return ForceCompactResult.failed("Summarization returned empty output; history unchanged.");
        }

        List<CompactionService.ConversationEntry> candidate = new ArrayList<>();
        candidate.add(CompactionService.ConversationEntry.system(
                ConversationLedger.SUMMARY_MARKER + summary.getSummary()));
        candidate.addAll(toPreserve);
        progressRef.phase("Measuring the compacted checkpoint");
        int tokensAfter = compactionService.estimateTokens(candidate);
        if (tokensAfter >= tokensBefore) {
            if (nativeResult.applied()) rebuildDirectHistoryForProviderSwitch();
            return ForceCompactResult.failed(
                    "Compaction did not reduce the active context; history unchanged.");
        }
        if (isCancelled()) {
            if (nativeResult.applied()) rebuildDirectHistoryForProviderSwitch();
            return ForceCompactResult.failed("Compaction was cancelled; history unchanged.");
        }
        progressRef.phase("Committing checkpoint");
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
            if (nativeResult.applied()) rebuildDirectHistoryForProviderSwitch();
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

    /**
     * Emergency compaction after a provider rejects an otherwise replay-safe request for
     * exceeding its context window. Unlike manual compaction, this method never absorbs
     * the pending user/tool-result payload into the checkpoint: that tail is resent once
     * after the older complete exchanges have been summarized.
     */
    private ForceCompactResult compactForContextOverflow(
            String currentMessage,
            List<ToolCallResult> pendingToolResults,
            String modelOverride) {
        ConversationLedger.Snapshot snapshot = conversationLedger.snapshot();
        List<CompactionService.ConversationEntry> history = snapshot.activeEntries();
        int requestTailStart = pendingRequestTailStart(
                history, currentMessage, pendingToolResults);
        int normalPreserveIndex = ConversationBoundaryPlanner.preserveFrom(
                history, compactionService, compactionService.preserveRecentTokens());
        if (requestTailStart >= history.size()) {
            return ForceCompactResult.failed(
                    "The rejected request could not be isolated from retained history.");
        }
        int preserveIndex = normalPreserveIndex > 0
                ? Math.min(requestTailStart, normalPreserveIndex)
                : requestTailStart;
        if (preserveIndex <= 0 || preserveIndex >= history.size()) {
            return ForceCompactResult.failed(
                    "No completed older exchange can be compacted without changing the rejected request.");
        }

        List<CompactionService.ConversationEntry> prefix = new ArrayList<>(
                history.subList(0, preserveIndex));
        List<CompactionService.ConversationEntry> tail = new ArrayList<>(
                history.subList(preserveIndex, history.size()));
        int tokensBefore = compactionService.estimateTokens(history);

        ConversationSummarizer.SummaryResult summary;
        try {
            summary = new ConversationSummarizer(directLlmClient)
                    .summarize(prefix,
                            "Recover from a provider context-window rejection",
                            modelOverride);
        } catch (RuntimeException failure) {
            summary = new ConversationSummarizer.SummaryResult("", 0, 0, false);
        }
        if (isCancelled()) {
            return ForceCompactResult.failed("Context recovery was cancelled; history unchanged.");
        }

        String strategy = "overflow-recovery";
        String summaryText = summary.isEmpty() || looksLikeFailedSummary(summary.getSummary())
                ? null : summary.getSummary();
        int tokensAfter = estimateCompactedTokens(summaryText, tail);
        if (summaryText == null || tokensAfter >= tokensBefore) {
            summaryText = compactionService.renderDigest(prefix);
            strategy = "deterministic-overflow-recovery";
            tokensAfter = estimateCompactedTokens(summaryText, tail);
        }
        if (summaryText == null || summaryText.isBlank() || tokensAfter >= tokensBefore) {
            return ForceCompactResult.failed(
                    "Compaction could not reduce the rejected request; history unchanged.");
        }

        if (isCancelled()) {
            return ForceCompactResult.failed("Context recovery was cancelled; history unchanged.");
        }

        long coveredThrough = snapshot.coveredThroughForPrefix(preserveIndex);
        String effectiveModel = modelOverride != null
                ? modelOverride : directLlmClient.getConfiguredModel();
        if (!conversationLedger.commitCompaction(
                snapshot.version(), coveredThrough, summaryText, strategy,
                directLlmClient.getConfiguredProvider(), effectiveModel,
                tokensBefore, tokensAfter)) {
            return ForceCompactResult.failed(
                    "Conversation changed during context recovery; history unchanged.");
        }

        rebuildDirectHistoryForRetry(currentMessage, pendingToolResults, modelOverride);
        if (sessionMetrics != null) {
            sessionMetrics.recordCompaction(tokensBefore, tokensAfter);
            if (summary != null && strategy.equals("overflow-recovery")) {
                sessionMetrics.recordTokenUsage(
                        summary.getInputTokens(), summary.getOutputTokens(), 0, 0);
            }
        }
        resetReportedContextUsage();
        return ForceCompactResult.ok(tokensBefore, tokensAfter, tail.size(), summaryText);
    }

    private int estimateCompactedTokens(
            String summary, List<CompactionService.ConversationEntry> tail) {
        if (summary == null || summary.isBlank()) return Integer.MAX_VALUE;
        List<CompactionService.ConversationEntry> candidate = new ArrayList<>();
        candidate.add(CompactionService.ConversationEntry.system(
                ConversationLedger.SUMMARY_MARKER + summary));
        candidate.addAll(tail);
        return compactionService.estimateTokens(candidate);
    }

    private int pendingRequestTailStart(
            List<CompactionService.ConversationEntry> history,
            String currentMessage,
            List<ToolCallResult> pendingToolResults) {
        int start = history.size();
        if (currentMessage != null) {
            for (int i = history.size() - 1; i >= 0; i--) {
                CompactionService.ConversationEntry entry = history.get(i);
                if (entry.type == CompactionService.EntryType.USER
                        && Objects.equals(currentMessage, entry.content)) {
                    start = i;
                    break;
                }
            }
        }

        Set<String> pendingCallIds = new LinkedHashSet<>();
        if (pendingToolResults != null) {
            for (ToolCallResult result : pendingToolResults) {
                if (result != null && result.callId != null) {
                    pendingCallIds.add(result.callId);
                }
            }
        }
        if (!pendingCallIds.isEmpty()) {
            for (int i = history.size() - 1; i >= 0; i--) {
                CompactionService.ConversationEntry entry = history.get(i);
                if ((entry.type == CompactionService.EntryType.TOOL_CALL
                        || entry.type == CompactionService.EntryType.TOOL_RESULT)
                        && pendingCallIds.contains(entry.toolCallId)) {
                    start = Math.min(start, i);
                }
            }
        }

        if (start < history.size()) {
            for (int i = start; i >= 0; i--) {
                CompactionService.ConversationEntry entry = history.get(i);
                if (entry.type == CompactionService.EntryType.USER) return i;
            }
        }
        return start;
    }

    private void rebuildDirectHistoryForRetry(
            String currentMessage, List<ToolCallResult> pendingToolResults,
            String modelOverride) {
        List<CompactionService.ConversationEntry> entries = new ArrayList<>(
                conversationLedger.snapshot().activeEntries());
        Set<String> pendingCallIds = new LinkedHashSet<>();
        if (pendingToolResults != null) {
            for (ToolCallResult result : pendingToolResults) {
                if (result != null && result.callId != null) {
                    pendingCallIds.add(result.callId);
                }
            }
        }

        boolean pendingUserRemoved = currentMessage == null;
        for (int i = entries.size() - 1; i >= 0; i--) {
            CompactionService.ConversationEntry entry = entries.get(i);
            if (!pendingUserRemoved && entry.type == CompactionService.EntryType.USER
                    && Objects.equals(currentMessage, entry.content)) {
                entries.remove(i);
                pendingUserRemoved = true;
                continue;
            }
            if (entry.type == CompactionService.EntryType.TOOL_RESULT
                    && pendingCallIds.contains(entry.toolCallId)) {
                entries.remove(i);
            }
        }
        // Pending call envelopes deliberately remain dangling here: the exact matching
        // tool results are supplied once on the retried request.
        rebuildDirectHistory(entries, true, false, false, modelOverride);
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
            refreshNativeCompactionTrigger();
        } catch (Exception e) {
            // Budget refresh must never break a chat turn; keep the previous budget.
        }
    }

    private boolean hasAutoCompactableHistory() {
        if (!compactionService.isAutoCompactEnabled()) return false;
        var entries = conversationLedger.snapshot().activeEntries();
        return compactionService.estimateTokens(entries) > compactionService.preserveRecentTokens()
                && ConversationBoundaryPlanner.preserveFrom(
                        entries, compactionService, compactionService.preserveRecentTokens()) > 0;
    }

    private void refreshNativeCompactionTrigger() {
        directLlmClient.setNativeCompactionTriggerTokens(
                hasAutoCompactableHistory() ? compactionService.triggerTokens() : 0);
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
        if (!hasAutoCompactableHistory()) return;
        String pendingRequestMessage = reminderManager == null
                ? pendingMessage : reminderManager.previewUserTurn(pendingMessage);
        long projectedInputTokens = projectedInputTokens(pendingRequestMessage);
        if (lastReportedInputTokens <= 0L && toolDefs != null) {
            projectedInputTokens = saturatingAdd(projectedInputTokens,
                    compactionService.estimateTextTokens(toolDefs.toString()));
        }
        // This counter has no attachment input. Do not label a text-only count as
        // authoritative for a request that will also send pending media/documents.
        DirectLlmClient.TokenCountResult exact = pendingAttachments == null || pendingAttachments.isEmpty()
                ? directLlmClient.countInputTokens(
                        pendingRequestMessage, systemPrompt, toolDefs, null, modelOverride)
                : DirectLlmClient.TokenCountResult.unsupported();
        if (exact.exact() && exact.inputTokens() > 0L) {
            // A count of this request supersedes both the estimate and older usage.
            // Anchor growth to the raw user entry the caller appends next. The count
            // already includes its reminder-decorated wire form, system prompt and tools.
            projectedInputTokens = exact.inputTokens();
            lastReportedInputTokens = exact.inputTokens();
            lastReportedHistoryTokens = saturatingAdd(
                    compactionService.estimateTokens(conversationLedger.snapshot().activeEntries()),
                    compactionService.estimateTextTokens(pendingMessage));
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
        int preserveIndex = ConversationBoundaryPlanner.preserveFrom(
                conversationHistory, compactionService,
                compactionService.preserveRecentTokens());
        // Fixed system instructions and tool definitions are not reducible. If
        // every prior conversation entry belongs to the newest exchange, an LLM
        // summary cannot create headroom and may actually grow a tiny history.
        if (preserveIndex <= 0) {
            return;
        }
        int tokensBefore = compactionService.estimateTokens(conversationHistory);
        long irreducibleInputTokens = Math.max(0L, projectedInputTokens - tokensBefore);
        if (irreducibleInputTokens >= compactionService.triggerTokens()) {
            // The system prompt, tool schemas, and pending user input already fill
            // the budget. Summarizing conversation history cannot cross the trigger.
            return;
        }
        // Auto-compaction runs silently otherwise (activity is not yet "Thinking"
        // at this point); show the same live "Compacting" surface as /compact.
        CompactionProgressIndicator autoProgress = CompactionProgressIndicator.start(
                "compact:auto:" + System.nanoTime(), renderer,
                conversationHistory.size(), tokensBefore,
                this::emitLine, this::setForegroundActivity);
        try {
            ForceCompactResult forced = forceCompact(null, autoProgress);
            if (forced.isSuccess()) {
                autoProgress.complete(forced.getTokensBefore(), forced.getTokensAfter(),
                        forced.getPreservedTurns());
                resetReportedContextUsage();
                return;
            }
            if (forced.getStatus() == ForceCompactResult.Status.NOOP) {
                autoProgress.noop(forced.getMessage());
                return;
            }
            if (isCancelled()) {
                autoProgress.failed("Compaction was cancelled; retrying next turn.");
                return;
            }
            autoProgress.phase("Writing a deterministic digest checkpoint");

            // Summarization unavailable or failed — commit a deterministic portable
            // digest at the same complete-exchange boundary. Raw events remain durable.
            List<CompactionService.ConversationEntry> tail = new ArrayList<>(
                    conversationHistory.subList(preserveIndex, conversationHistory.size()));
            String digest = compactionService.renderDigest(
                    conversationHistory.subList(0, preserveIndex));
            if (digest.isBlank()) {
                autoProgress.abandonIfActive(
                        "Nothing could be summarized from the older history.");
                return;
            }
            List<CompactionService.ConversationEntry> candidate = new ArrayList<>();
            candidate.add(CompactionService.ConversationEntry.system(
                    ConversationLedger.SUMMARY_MARKER + digest));
            candidate.addAll(tail);
            int tokensAfter = compactionService.estimateTokens(candidate);
            if (tokensAfter >= tokensBefore || isCancelled()) {
                autoProgress.failed(
                        "A deterministic digest could not reduce the context; retrying next turn.");
                return;
            }
            if (!conversationLedger.commitCompaction(
                    snapshot.version(), snapshot.coveredThroughForPrefix(preserveIndex), digest,
                    "deterministic", directLlmClient.getConfiguredProvider(),
                    directLlmClient.getConfiguredModel(), tokensBefore, tokensAfter)) {
                autoProgress.failed("Conversation changed during compaction; retrying next turn.");
                return;
            }
            rebuildDirectHistoryForProviderSwitch();
            if (sessionMetrics != null) {
                sessionMetrics.recordCompaction(tokensBefore, tokensAfter);
            }
            autoProgress.complete(tokensBefore, tokensAfter, tail.size());
            resetReportedContextUsage();
        } catch (RuntimeException autoCompactFailure) {
            autoProgress.failed(AgenticChatLoop.describeThrowable(autoCompactFailure));
            throw autoCompactFailure;
        } finally {
            // Catches any early return this refactor missed; a no-op when the
            // indicator already reached a terminal state above.
            autoProgress.abandonIfActive(null);
        }
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

    /** Most recent provider-measured request size, from exact counting or usage (0 if none yet). */
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
        String originalUserPrompt = extractOriginalUserPrompt(message);
        workflowController.beginTurn(originalUserPrompt, sessionId);
        try {
            if (planningMode && exitPlanModeTool != null) {
                return chatWithPlanning(
                        message, originalUserPrompt, sessionId, agentName, serverAgent, ragEnabled);
            }

            return chatInternal(
                    message, originalUserPrompt, sessionId, agentName, serverAgent, ragEnabled);
        } finally {
            workflowController.completeTurn();
        }
    }

    private String chatWithPlanning(String message, String originalUserPrompt,
                                    String sessionId, String agentName,
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

        String planResponse = chatInternal(
                message, originalUserPrompt, sessionId, "planner", serverAgent, ragEnabled);

        // Show the checklist after planning
        List<TodoWriteTool.TodoItem> todos = TodoWriteTool.getTodos(sessionId, workingDirectory);
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
            String executionResponse = chatInternal(
                    executionPrompt, executionPrompt,
                    sessionId, agentName, serverAgent, ragEnabled);

            return planResponse + "\n\n--- Execution ---\n\n" + executionResponse;
        }

        return planResponse;
    }

    private String chatInternal(String message, String originalUserPrompt,
                                String sessionId, String agentName,
                                String serverAgent, boolean ragEnabled) {
        configureConversationSession(sessionId);
        long turnStartMs = System.currentTimeMillis();
        AgentConfig agent = agentRegistry.get(agentName);
        if (agent == null) agent = agentRegistry.getDefault();

        // Capture the user's judge posture (guidance + one-shot override) once per turn.
        // beginTurn() also consumes the one-shot override, so a single /judge override
        // decision covers every model iteration and tool call within this turn.
        ai.kompile.cli.main.chat.enforcer.JudgeControl control = judgeControl;
        if (control == null || !control.getSessionId().equals(sessionId)) {
            control = ai.kompile.cli.main.chat.enforcer.JudgeControl.load(sessionId);
            judgeControl = control;
        }
        if (control != null) {
            activeJudgeSnapshot = control.beginTurn();
        } else {
            activeJudgeSnapshot = ai.kompile.cli.main.chat.enforcer.JudgeControl.TurnSnapshot.NONE;
        }

        // Direction judge turn lifecycle: reset per-turn counters and reuse the user's
        // durable judge guidance in every direction prompt.
        ai.kompile.cli.main.chat.enforcer.DirectionJudge direction = directionJudge;
        if (activeJudgeSnapshot.enabled() && direction != null) {
            direction.beginTurn();
            direction.setGuidanceSupplier(() -> activeJudgeSnapshot.hasGuidance()
                    ? activeJudgeSnapshot.guidance() : null);
        }

        // Initialize tool result store for this session
        this.toolResultStore = new ToolResultStore(sessionId);

        AtomicBoolean turnToolAbort = new AtomicBoolean(isCancelled());
        activeToolAbortSignal.set(turnToolAbort);
        ToolContext toolContext = new ToolContext(
                sessionId, agent, permissionService, workingDirectory, toolRegistry);
        toolContext.bindJudgeControl(control, false);
        toolContext.linkAbortSignal(turnToolAbort);
        toolContext.setOutputConsumer(sessionContext.wrapConsumer(this::emitLine));

        boolean progressiveToolLoading = usesProgressiveToolLoading();
        if (progressiveToolLoading) {
            toolRegistry.prepareProgressiveTools(agent);
        }

        // Compose a stable prefix: agent/project/workflow first, session result path last.
        String systemPrompt = buildSystemPrompt(agent);
        ArrayNode initialToolDefs = toolDefinitions(agent, progressiveToolLoading);

        StringBuilder fullResponse = new StringBuilder();
        int iteration = 0;
        int inlineEnforcerCorrections = inheritedEnforcerCorrectionCount(originalUserPrompt);

        String currentMessage = message;
        // Only an accepted user input can replace the objective under review;
        // internal continuations, corrections and redirects must not replace it.
        String promptUnderReview = originalUserPrompt;
        List<ToolCallResult> pendingToolResults = null;
        QueuedInput pendingQueuedInput = null;
        boolean directionHaltedTurn = false;
        boolean directionLoopCompleted = false;
        boolean directHistoryHasUnsubmittedToolCalls = false;

        try {
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
            if (directHistoryHasUnsubmittedToolCalls
                    && (pendingToolResults == null || pendingToolResults.isEmpty())
                    && directLlmClient != null) {
                // A supervision redirect/correction replaced the message before the
                // provider's tool calls ran. Rebuild so the new prompt is sent exactly
                // once and the superseded calls close with synthetic results.
                rebuildDirectHistoryForRetry(
                        currentMessage, pendingToolResults, agent.getModelOverride());
                directHistoryHasUnsubmittedToolCalls = false;
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
            if (pendingQueuedInput != null) {
                if (!pendingQueuedInput.accept()) {
                    pendingQueuedInput.restore();
                    pendingQueuedInput = null;
                    break;
                }
                promptUnderReview = extractOriginalUserPrompt(pendingQueuedInput.content());
                conversationLedger.append(
                        CompactionService.ConversationEntry.user(currentMessage));
                pendingQueuedInput = null;
            }
            ConversationLedger.Snapshot ledgerBeforeRequest = conversationLedger.snapshot();
            String outboundMessage = reminderManager == null
                    ? currentMessage : reminderManager.decorateUserTurn(currentMessage);
            String reminderContent = ReminderManager.reminderBlockContent(outboundMessage);
            if (reminderContent != null) {
                emitLine(renderer.renderReminderSection(reminderContent));
            }

            StreamResult result;
            boolean retryProjectionInstalled = false;
            if (isDirectMode()) {
                List<DirectLlmClient.AttachmentInput> requestAttachments = pendingAttachments;
                pendingAttachments = null;
                result = streamDirectTurn(
                        outboundMessage, systemPrompt, toolDefs, pendingToolResults,
                        agent.getModelOverride(), requestAttachments);
                if (result.contextOverflow && result.contextOverflowRetrySafe
                        && !isCancelled()) {
                    emitLine(renderer.yellow(
                            "  ↻ Provider context limit reached; compacting and retrying once"));
                    CompactionProgressIndicator recoveryProgress =
                            CompactionProgressIndicator.start(
                                    "compact:recovery:" + System.nanoTime(), renderer,
                                    conversationLedger.snapshot().activeEntries().size(),
                                    compactionService.estimateTokens(
                                            conversationLedger.snapshot().activeEntries()),
                                    this::emitLine, this::setForegroundActivity);
                    recoveryProgress.phase("Recovering from the context-window rejection");
                    ForceCompactResult recovery;
                    try {
                        recovery = compactForContextOverflow(
                                currentMessage, pendingToolResults, agent.getModelOverride());
                    } catch (RuntimeException recoveryFailure) {
                        // The recovery path can throw through; still close the block.
                        recoveryProgress.failed(AgenticChatLoop.describeThrowable(recoveryFailure));
                        throw recoveryFailure;
                    }
                    if (recovery.isSuccess()) {
                        recoveryProgress.complete(recovery.getTokensBefore(),
                                recovery.getTokensAfter(), recovery.getPreservedTurns());
                        ledgerBeforeRequest = conversationLedger.snapshot();
                        retryProjectionInstalled = true;
                        try {
                            result = streamDirectTurn(
                                    outboundMessage, systemPrompt, toolDefs, pendingToolResults,
                                    agent.getModelOverride(), requestAttachments);
                        } catch (RuntimeException retryFailure) {
                            rebuildDirectHistoryForProviderSwitch();
                            throw retryFailure;
                        }
                    } else {
                        // Failed/NOOP recovery: publish the reason and continue the
                        // turn normally (no retry).
                        recoveryProgress.abandonIfActive(recovery.getMessage());
                    }
                }
            } else {
                result = streamServerTurn(
                        outboundMessage, sessionId, serverAgent, ragEnabled,
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

            if (result.failed) {
                if (retryProjectionInstalled && directLlmClient != null) {
                    rebuildDirectHistoryForProviderSwitch();
                    directHistoryHasUnsubmittedToolCalls = false;
                }
                String failure = result.providerFailureMessage;
                if (failure == null || failure.isBlank()) failure = result.text;
                throw new ProviderChatException(failure == null || failure.isBlank()
                        ? "Standard Chat provider request failed" : failure);
            }
            if (isDirectMode()) {
                if (pendingToolResults != null && !pendingToolResults.isEmpty()) {
                    directHistoryHasUnsubmittedToolCalls = false;
                }
                if (!result.toolCalls.isEmpty()) {
                    directHistoryHasUnsubmittedToolCalls = true;
                }
            }

            boolean rebuildAfterRejectedNativeCheckpoint = false;
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
                int tokensAfter = compactionService.estimateTextTokens(
                        ConversationLedger.SUMMARY_MARKER + portableSummary);
                long coveredThrough = ledgerBeforeRequest.coveredThroughForPrefix(
                        ledgerBeforeRequest.activeEntries().size());
                String model = agent.getModelOverride() != null
                        ? agent.getModelOverride() : directLlmClient.getConfiguredModel();
                boolean committed = tokensAfter < tokensBefore
                        && (result.nativeCompactionPayload == null
                        ? conversationLedger.commitCompaction(
                                ledgerBeforeRequest.version(), coveredThrough, portableSummary,
                                "native-" + result.nativeCompactionStrategy,
                                directLlmClient.getConfiguredProvider(), model,
                                tokensBefore, tokensAfter)
                        : conversationLedger.commitNativeCompaction(
                                ledgerBeforeRequest.version(), coveredThrough, portableSummary,
                                "native-" + result.nativeCompactionStrategy,
                                directLlmClient.getConfiguredProvider(), model,
                                tokensBefore, tokensAfter, result.nativeCompactionPayload));
                if (committed) {
                    // Usage belongs to the request before its native compaction checkpoint.
                    // Do not carry that high-water mark into the newly shortened history.
                    resetReportedContextUsage();
                    emitLine(renderer.renderCompactionNotice(tokensBefore, tokensAfter));
                    if (sessionMetrics != null) {
                        sessionMetrics.recordCompaction(tokensBefore, tokensAfter);
                    }
                } else {
                    rebuildAfterRejectedNativeCheckpoint = true;
                }
            }

            // Accumulate text output
            if (!result.text.isEmpty()) {
                fullResponse.append(result.text);
                conversationLedger.append(
                        CompactionService.ConversationEntry.assistant(result.text));
            }
            if (rebuildAfterRejectedNativeCheckpoint && directLlmClient != null) {
                rebuildDirectHistoryForProviderSwitch();
            }

            // ── Inline enforcer check ─────────────────────────────────────
            if (activeJudgeSnapshot.enabled()
                    && inlineEnforcerEnabled && inlineEnforcer != null && !result.text.isEmpty()) {
                // User guidance rides with the prompt so the judge sees it verbatim.
                String guidance = activeJudgeSnapshot.hasGuidance()
                        ? promptUnderReview + "\n\n[USER GUIDANCE TO THE JUDGE]\n"
                          + activeJudgeSnapshot.guidance()
                        : promptUnderReview;
                emitInlineEnforcerActivity("[turn review] assistant output · "
                        + result.text.length() + " chars");
                EnforcerDecision decision = null;
                try {
                    decision = inlineEnforcer.evaluate(
                            guidance, result.text, inlineEnforcerPolicy,
                            inlineEnforcerCorrections + 1);
                } catch (Exception failure) {
                    String feedback = "Enforcer evaluation failed: " + failure.getMessage();
                    emitInlineEnforcerActivity("[turn decision] FAIL-OPEN · " + feedback);
                    emitLine("\n" + renderer.yellow("[judge] " + feedback
                            + " — failing open"));
                    decision = EnforcerDecision.pass(feedback);
                }
                if (decision != null) {
                    emitInlineEnforcerActivity("[turn decision] "
                            + (decision.isCompliant() ? "ALLOW" : decision.isStop() ? "STOP" : "CORRECT")
                            + (activeJudgeSnapshot.reportOnly() ? " (overridden: report-only)" : "")
                            + " · " + decision.getReasoning());
                    if (activeJudgeSnapshot.reportOnly()) {
                        if (!decision.isCompliant()) {
                            emitLine(renderer.yellow("[judge] (overridden) would flag: "
                                    + String.join("; ", decision.getViolations())));
                        }
                    } else if (!decision.isCompliant()) {
                        String violations = String.join("; ", decision.getViolations());
                        emitLine("\n" + (decision.isStop()
                                ? renderer.red("[judge] BLOCKED: " + violations)
                                : renderer.yellow("[judge] violation: " + violations)));
                        if (inlineEnforcerCorrections < inlineEnforcerMaxCorrections) {
                            inlineEnforcerCorrections++;
                            emitLine(renderer.yellow("[judge] sending correction (attempt "
                                    + inlineEnforcerCorrections + "/"
                                    + inlineEnforcerMaxCorrections + ")"));
                            String correction = enforcerFeedback(decision);
                            String userFeedback = enforcerFeedbackForUser(
                                    correction, inlineEnforcerCorrections,
                                    inlineEnforcerMaxCorrections, promptUnderReview);
                            if (submitSupervisorFeedback("enforcer", userFeedback, true)) {
                                fullResponse.append(
                                        "\n[Interrupted by judge; correction queued as user feedback]");
                                break;
                            }
                            // Headless/embedded callers have no main REPL owner. Preserve
                            // their bounded in-loop correction behavior as a fallback.
                            currentMessage = correction;
                            pendingToolResults = null;
                            conversationLedger.append(
                                    CompactionService.ConversationEntry.user(currentMessage));
                            continue;
                        }
                        emitLine(renderer.red("[judge] max corrections exceeded; response blocked"));
                        fullResponse.append("\n[Blocked by judge policy]");
                        break;
                    }
                }
            }

            // ── Direction check (goal-drift supervision) ────────────────────
            // Runs on EVERY model iteration, including the final text-only one: a
            // drifting final answer is exactly the long-conversation derailment this
            // judge exists to catch. In-place redirect: the agent keeps its context
            // and receives a corrective instruction as the next message (tool calls
            // in flight are superseded, never executed). Halt: the turn ends with a
            // supervisor feedback hand-off, never a silent stop.
            if (activeJudgeSnapshot.enabled() && direction != null && direction.isEnabled()) {
                DirectionJudgement judgement = checkDirection(
                        direction, originalUserPrompt, iteration, fullResponse, currentMessage,
                        result.toolCalls.isEmpty());
                if (judgement.redirected()) {
                    currentMessage = judgement.redirectPrompt();
                    pendingToolResults = null;
                    conversationLedger.append(
                            CompactionService.ConversationEntry.user(currentMessage));
                    continue;
                }
                if (judgement.halted()) {
                    directionHaltedTurn = true;
                    fullResponse.append("\n[Direction judge halted the turn: ")
                            .append(judgement.reason()).append("]");
                    submitSupervisorFeedback("direction-judge",
                            directionFeedback(judgement.reason(), judgement.redirectPrompt()),
                            true);
                    break;
                }
            }

            // A queued message can continue the same owner immediately after a model
            // response, avoiding an end-of-turn dequeue/re-dispatch race.
            if (result.toolCalls.isEmpty()) {
                WorkflowController.FinalDecision workflowFinal =
                        workflowController.beforeFinalResponse();
                if (!workflowFinal.allowed()) {
                    if (workflowFinal.retry()) {
                        emitLine(renderer.yellow("[workflow] " + workflowFinal.reason()
                                + " — sending correction"));
                        currentMessage = workflowFinal.correctionPrompt();
                        pendingToolResults = null;
                        conversationLedger.append(
                                CompactionService.ConversationEntry.user(currentMessage));
                        continue;
                    }
                    emitLine(renderer.red("[workflow] BLOCKED: " + workflowFinal.reason()));
                    fullResponse.append("\n[Blocked by workflow: ")
                            .append(workflowFinal.reason()).append("]");
                    submitSupervisorFeedback(
                            "workflow", workflowFinal.correctionPrompt(), true);
                    break;
                }
                pendingQueuedInput = claimQueuedMessage();
                if (pendingQueuedInput != null
                        && !pendingQueuedInput.content().isBlank()) {
                    currentMessage = pendingQueuedInput.content();
                    pendingToolResults = null;
                    continue;
                }
                if (pendingQueuedInput != null) {
                    pendingQueuedInput.restore();
                    pendingQueuedInput = null;
                }
                if (runController != null) runController.afterStep();
                break;
            }

            // Execute tool calls with proper rendering
            emitLine("");
            List<ToolCallResult> toolResults = new ArrayList<>();
            workflowController.beginToolBatch();

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
                pendingQueuedInput = claimQueuedMessage();
                queuedMessage = pendingQueuedInput == null
                        ? null : pendingQueuedInput.content();
                if (queuedMessage != null && !queuedMessage.isBlank()) {
                    addSupersededToolResults(
                            result.toolCalls, toolIndex, toolResults, queuedMessage);
                    break;
                }
                if (pendingQueuedInput != null) {
                    pendingQueuedInput.restore();
                    pendingQueuedInput = null;
                }

                String normalizedToolName = TerminalRenderer.stripMcpPrefix(call.name);
                String rawToolInput = call.arguments != null ? call.arguments.toString() : "";

                ToolInterception supervision = interceptToolCall(
                        originalUserPrompt, result.text, call.name, call.arguments);
                String rewriteNotice = null;
                if (isCancelled()) {
                    String cancelled = "Cancelled during supervisory tool review";
                    notifyToolDenied(call, rawToolInput, cancelled);
                    toolResults.add(new ToolCallResult(
                            call.id, call.name, cancelled, true));
                    conversationLedger.append(CompactionService.ConversationEntry.toolResult(
                            call.name, call.id, cancelled));
                    if (sessionMetrics != null) {
                        sessionMetrics.recordToolCall(call.name, true, 0);
                    }
                    break;
                }
                if (supervision.rewrittenArguments() != call.arguments) {
                    call.arguments = supervision.rewrittenArguments();
                    rawToolInput = call.arguments == null ? "" : call.arguments.toString();
                    rewriteNotice = "Arguments rewritten by " + supervision.source()
                            + " before execution: " + rawToolInput;
                    emitLine(renderer.yellow("[" + supervision.source()
                            + "] rewrote MCP tool arguments before execution"));
                }
                if (supervision.blocked()) {
                    String denied = "Blocked by " + supervision.source()
                            + ": " + supervision.reason();
                    fireFirstOutput();
                    notifyToolDenied(call, rawToolInput, denied);
                    emitLine(renderer.renderToolCallDenied(call.name, denied));
                    toolResults.add(new ToolCallResult(call.id, call.name, denied, true));
                    conversationLedger.append(CompactionService.ConversationEntry.toolResult(
                            call.name, call.id, denied));
                    if (sessionMetrics != null) {
                        sessionMetrics.recordToolCall(call.name, true, 0);
                    }
                    submitSupervisorFeedback(
                            supervision.source(), supervision.feedback(), true);
                    continue;
                }

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
                // Capture on the owner thread before task execution moves to a worker.
                toolContext.setSubagentSupervision(captureSubagentSupervision(promptUnderReview, toolContext));
                ToolContext callToolContext = toolContext.forkForToolExecution();
                callToolContext.setOutputConsumer(sessionContext.wrapConsumer(transcriptBlock::appendOutput));

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
                        workflowController.afterTool(call.name, call.arguments, missing);
                        notifyToolComplete(call, rawToolInput, missing);
                        transcriptBlock.complete(missing);
                        toolResults.add(new ToolCallResult(call.id, call.name, errMsg, true));
                        conversationLedger.append(CompactionService.ConversationEntry.toolResult(
                                call.name, call.id, errMsg));
                        if (sessionMetrics != null) sessionMetrics.recordToolCall(call.name, true, 0);
                        continue;
                    }

                    long toolStart = System.currentTimeMillis();
                    boolean subagentInvocation = "task".equals(normalizedToolName);
                    if (subagentInvocation) {
                        setBlockingSubagentInvocation(true);
                    }
                    ToolResult toolResult;
                    String completionSession = sessionId + "-background-" + java.util.UUID.randomUUID();
                    String completionInput = rawToolInput;
                    try {
                        toolResult = executeToolInterruptibly(
                                tool, call.arguments, callToolContext, call.name,
                                completed -> new ToolResultStore(completionSession).save(
                                        call.name, call.id, completionInput,
                                        completed.getOutput(), completed.isError()));
                    } finally {
                        if (subagentInvocation) {
                            setBlockingSubagentInvocation(false);
                        }
                    }
                    long toolDurationMs = System.currentTimeMillis() - toolStart;
                    if (rewriteNotice != null) {
                        toolResult = new ToolResult(
                                toolResult.getTitle(),
                                rewriteNotice + "\n" + toolResult.getOutput(),
                                toolResult.getMetadata(), toolResult.isError());
                    }

                    workflowController.afterTool(call.name, call.arguments, toolResult);

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
                        List<TodoWriteTool.TodoItem> todos = TodoWriteTool.getTodos(
                                toolContext.getSessionId(), toolContext.getWorkingDirectory());
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
                    if (!e.isPermissionDenied()) {
                        workflowController.afterTool(
                                call.name, call.arguments, ToolResult.error(errMsg));
                    }
                    toolResultStore.save(call.name, call.id, null, errMsg, true);
                    toolResults.add(new ToolCallResult(call.id, call.name, errMsg, true));
                    conversationLedger.append(CompactionService.ConversationEntry.toolResult(
                            call.name, call.id, errMsg));
                    if (sessionMetrics != null) sessionMetrics.recordToolCall(call.name, true, 0);
                } catch (Throwable unexpectedToolFailure) {
                    // Never let a non-ToolExecutionException (LinkageError/NoClassDefFoundError
                    // from a jar swapped under a running JVM, OOM, native crash recovery, ...)
                    // escape this loop: it would unwind the turn thread with zero output, which
                    // is exactly the "silent crash" signature. Report the failure as a normal
                    // tool error so the model sees it and the turn continues.
                    if (spinner != null) spinner.stop();
                    String failure = describeThrowable(unexpectedToolFailure);
                    ToolResult failed = ToolResult.error(failure);
                    notifyToolComplete(call, rawToolInput, failed);
                    transcriptBlock.complete(failed);

                    String errMsg = "Error: " + failure;
                    workflowController.afterTool(call.name, call.arguments, failed);
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
                pendingQueuedInput = claimQueuedMessage();
                queuedMessage = pendingQueuedInput == null
                        ? null : pendingQueuedInput.content();
                if (queuedMessage != null && queuedMessage.isBlank()) {
                    pendingQueuedInput.restore();
                    pendingQueuedInput = null;
                    queuedMessage = null;
                }
            }

            if (runController != null) runController.afterStep();

            // Set up next iteration with tool results
            pendingToolResults = toolResults;
            currentMessage = queuedMessage == null || queuedMessage.isBlank()
                    ? null : queuedMessage;
        }
        directionLoopCompleted = true;
        } finally {
            if (pendingQueuedInput != null) {
                pendingQueuedInput.restore();
            }
            if (activeJudgeSnapshot.enabled() && direction != null) {
                try {
                    boolean countTurn = directionHaltedTurn
                            || (directionLoopCompleted && !isCancelled());
                    ai.kompile.cli.main.chat.enforcer.DirectionJudge.SessionState state =
                            direction.completeTurn(countTurn);
                    if (direction.getChecksThisTurn() > 0) {
                        emitInlineEnforcerActivity("[direction] turn state · streak "
                                + state.consecutiveDriftTurns() + "/"
                                + (state.crossTurnDriftLimit() == 0
                                        ? "off" : state.crossTurnDriftLimit())
                                + " · total " + state.totalDriftTurns());
                    }
                } catch (RuntimeException failure) {
                    emitInlineEnforcerActivity(
                            "[direction] state FAIL-OPEN · " + failure.getMessage());
                }
            }
            try {
                truncator.cleanupOldFiles();
                // A cancelled direct turn may have left provider-native tool-call
                // envelopes without a submitted result. Reproject before the queued
                // successor starts so the next provider request is valid.
                if ((isCancelled() || directHistoryHasUnsubmittedToolCalls)
                        && directLlmClient != null) {
                    rebuildDirectHistoryForProviderSwitch();
                    directHistoryHasUnsubmittedToolCalls = false;
                }
            } finally {
                // Provider failures, including an unrecoverable context rejection,
                // must release per-turn abort state just like normal completion.
                activeToolAbortSignal.compareAndSet(turnToolAbort, null);
            }
        }

        // Evaluate turn with performance harness if configured
        String output = fullResponse.toString();
        if (activeJudgeSnapshot.enabled() && performanceHarness != null && !output.isBlank()
                && !isCancelled()) {
            try {
                long turnLatency = System.currentTimeMillis() - turnStartMs;
                performanceHarness.evaluateTurnAsync(
                        agentName,
                        agent.getModelOverride(),
                        originalUserPrompt,
                        output,
                        sessionId,
                        turnLatency);
            } catch (Exception e) {
                // Harness evaluation is best-effort — never fail the turn
            }
        }

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

    DirectSubagentSupervision.Contract captureSubagentSupervision(String userPrompt, ToolContext parent) {
        EnforcerEvaluator captured = inlineEnforcer;
        try {
            if (captured instanceof EnforcerJudge judge) captured = judge.captureForChild();
        } catch (RuntimeException failure) {
            emitInlineEnforcerActivity("[child supervision] Cannot capture judge constraints: " + failure.getMessage());
            captured = null; // Required review remains required, and reports unavailable explicitly.
        }
        EnforcerEvaluator evaluator = captured;
        EnforcerPolicy policy = inlineEnforcerPolicy;
        String objective = userPrompt + (activeJudgeSnapshot.hasGuidance()
                ? "\n\n[USER GUIDANCE TO THE JUDGE]\n" + activeJudgeSnapshot.guidance() : "");
        ReminderManager reminders = reminderManager;
        if (reminders != null) objective += "\n\n[Captured reminder constraints]\n" + reminders.enforcementConstraints();
        return new DirectSubagentSupervision.Contract(
                workflowController.captureChildContract(), objective, evaluator, policy,
                evaluator == null ? null : (childPrompt, assistant, name, input) ->
                        evaluateInlineToolCall(evaluator, policy, childPrompt, assistant, name, input),
                activeJudgeSnapshot.enabled() && inlineEnforcerRequested, inlineEnforcerMaxCorrections)
                .withCeiling(parent);
    }

    private ToolInterception interceptToolCall(
            String userPrompt, String assistantContext,
            String toolName, JsonNode arguments) {
        WorkflowController.Decision workflow =
                workflowController.beforeTool(toolName, arguments);
        if (!workflow.allowed()) {
            return new ToolInterception(arguments, "workflow", workflow.reason(),
                    workflow.correctionPrompt(), true);
        }
        if (!activeJudgeSnapshot.enabled()) {
            return new ToolInterception(arguments, "", "", "", false);
        }
        String toolInput = arguments == null ? "{}" : arguments.toString();
        // Controls enforce user confirmation/permission in the tool, not through the judge
        // they are meant to correct. Workflow and child supervision remain independent.
        if ("judge_control".equals(toolName)) {
            return new ToolInterception(arguments, "judge control", "", "", false);
        }
        if (activeJudgeSnapshot.approvesCommand(toolName, toolInput)) {
            EnforcerToolCallDecision mandate = ai.kompile.cli.main.chat.enforcer.ShellMandatePolicy
                    .evaluateFromSerializedArgs(toolName, toolInput);
            if (mandate != null) {
                return new ToolInterception(arguments, "shell mandate", mandate.blockMessage(),
                        mandate.getCorrectionPrompt(), true);
            }
            emitInlineEnforcerActivity("[user command approval] " + toolName + " " + toolInput);
            return new ToolInterception(arguments, "user approval", "", "", false);
        }
        NamedToolDecision enforcement = null;

        ToolCallInterceptor enforcer = enforcerToolCallInterceptor;
        if (inlineEnforcerEnabled && enforcer != null) {
            emitInlineEnforcerActivity("[tool review] " + toolName + " " + toolInput);
            EnforcerToolCallDecision readOnlyGit = JudgeToolPolicy.evaluateReadOnlyGitTool(
                    toolName, toolInput, inlineEnforcerPolicy, objectMapper);
            enforcement = readOnlyGit != null
                    ? new NamedToolDecision("enforcer", readOnlyGit)
                    : invokeToolInterceptor(
                            "enforcer", enforcer, userPrompt, assistantContext,
                            toolName, toolInput, false);
        }
        if (isCancelled()) {
            return new ToolInterception(arguments, "", "", "", false);
        }

        // There is one tool reviewer. A configured policy judge is authoritative;
        // only when no policy judge exists does the quality judge run as advisory.
        // Routine bookkeeping never needs a speculative advisory model call.
        ToolCallInterceptor judge = judgeToolCallInterceptor;
        if (judge != null && enforcement == null
                && !JudgeToolPolicy.isRoutineSessionTool(toolName)
                && JudgeToolPolicy.evaluateReadOnlyGitTool(
                        toolName, toolInput, inlineEnforcerPolicy, objectMapper) == null) {
            String judgeToolInput = redactSensitiveToolInput(arguments);
            NamedToolDecision advisory = invokeToolInterceptor(
                    "judge", judge, userPrompt, assistantContext,
                    toolName, judgeToolInput, false);
            EnforcerToolCallDecision decision = advisory.decision();
            emitInlineEnforcerActivity("[judge advisory"
                    + (activeJudgeSnapshot.reportOnly() ? " · override armed" : "")
                    + "] " + toolName + " · " + decision.getAction()
                    + (decision.getReason().isBlank() ? "" : " · " + decision.getReason()));
        }

        if (enforcement == null) {
            return new ToolInterception(arguments, "", "", "", false);
        }

        EnforcerToolCallDecision decision = enforcement.decision();
        if (!decision.isAllowed()
                || (decision.isRewrite() && decision.getRewrittenArgs() == null)) {
            String reason = decision.blockMessage();
            if (ai.kompile.cli.main.chat.enforcer.ShellMandatePolicy.isShellTool(toolName)) {
                reason += "\nUser controls: /judge approve <exact bash command> (next turn only),"
                        + " or /judge feedback <correction>. Permissions and hard tool protections still apply.";
            }
            String feedback = decision.getCorrectionPrompt();
            if (feedback.isBlank()) {
                feedback = "The proposed MCP tool call '" + toolName
                        + "' was blocked before execution. Revise the approach.\nReason: " + reason;
            }
            return new ToolInterception(
                    arguments, enforcement.source(), reason, feedback, true);
        }

        if (decision.isRewrite()) {
            return new ToolInterception(
                    objectMapper.valueToTree(decision.getRewrittenArgs()),
                    enforcement.source(), "", "", false);
        }
        return new ToolInterception(arguments, "", "", "", false);
    }

    private static String redactSensitiveToolInput(JsonNode arguments) {
        if (arguments == null) return "{}";
        JsonNode copy = arguments.deepCopy();
        redactSensitiveFields(copy);
        return copy.toString();
    }

    private static void redactSensitiveFields(JsonNode node) {
        if (node == null) return;
        if (node.isObject()) {
            ObjectNode object = (ObjectNode) node;
            List<String> names = new ArrayList<>();
            object.fieldNames().forEachRemaining(names::add);
            for (String name : names) {
                JsonNode value = object.get(name);
                if (isSensitiveField(name)) {
                    object.put(name, "***REDACTED***");
                } else {
                    redactSensitiveFields(value);
                }
            }
        } else if (node.isArray()) {
            node.forEach(AgenticChatLoop::redactSensitiveFields);
        }
    }

    private static boolean isSensitiveField(String name) {
        if (name == null) return false;
        String normalized = name.toLowerCase(Locale.ROOT)
                .replace("_", "").replace("-", "");
        return normalized.contains("apikey")
                || normalized.contains("accesstoken")
                || normalized.contains("refreshtoken")
                || normalized.contains("password")
                || normalized.contains("secret")
                || normalized.contains("authorization")
                || normalized.contains("cookie")
                || normalized.contains("privatekey")
                || normalized.contains("credential");
    }

    private NamedToolDecision invokeToolInterceptor(
            String source,
            ToolCallInterceptor interceptor,
            String userPrompt,
            String assistantContext,
            String toolName,
            String toolInput,
            boolean failClosed) {
        try {
            EnforcerToolCallDecision decision = interceptor.intercept(
                    userPrompt, assistantContext, toolName, toolInput);
            if (decision == null) {
                decision = failClosed
                        ? EnforcerToolCallDecision.block(source + " returned no decision")
                        : EnforcerToolCallDecision.allow(source + " returned no decision");
            }
            if ("enforcer".equals(source)) {
                emitInlineEnforcerActivity("[tool decision] " + decision.getAction()
                        + " · " + decision.getReason());
            }
            return new NamedToolDecision(source, decision);
        } catch (Exception failure) {
            String reason = source + " tool-call review failed: " + failure.getMessage();
            EnforcerToolCallDecision decision = failClosed
                    ? new EnforcerToolCallDecision(
                            EnforcerToolCallDecision.Action.BLOCK,
                            reason, List.of(reason),
                            "Stop and choose a policy-compliant tool path.", null)
                    : EnforcerToolCallDecision.allow(reason);
            if ("enforcer".equals(source)) {
                emitInlineEnforcerActivity("[tool decision] " + decision.getAction()
                        + " · " + reason);
            }
            return new NamedToolDecision(source, decision);
        }
    }

    private boolean submitSupervisorFeedback(
            String source, String feedback, boolean interrupt) {
        SupervisorFeedbackHandler handler = supervisorFeedbackHandler;
        if (handler == null || feedback == null || feedback.isBlank()) return false;
        try {
            return handler.submit(source, feedback, interrupt);
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private static String enforcerFeedback(EnforcerDecision decision) {
        if (decision != null && decision.getCorrectionPrompt() != null
                && !decision.getCorrectionPrompt().isBlank()) {
            return decision.getCorrectionPrompt();
        }
        String reason = decision == null || decision.getViolations().isEmpty()
                ? "The active enforcer stopped the response."
                : String.join("; ", decision.getViolations());
        return "Stop the current path and produce a compliant response.\nReason: " + reason;
    }

    private static String enforcerFeedbackForUser(
            String correction, int attempt, int maxCorrections, String originalUserPrompt) {
        return "[correction " + attempt + "/" + maxCorrections + "]\n"
                + "Original user request:\n"
                + StringUtils.truncateWithSize(originalUserPrompt, 4_000) + "\n\n"
                + correction;
    }

    /** Carry the configured correction budget across interrupting feedback turns. */
    private static int inheritedEnforcerCorrectionCount(String message) {
        if (message == null) return 0;
        String normalized = message.stripLeading().replace("\r\n", "\n");
        String prefix = "[enforcer feedback]\n[correction ";
        if (!normalized.startsWith(prefix)) return 0;
        int slash = normalized.indexOf('/', prefix.length());
        int end = slash < 0 ? -1 : normalized.indexOf(']', slash + 1);
        if (slash < 0 || end < 0) return 0;
        try {
            return Math.max(0, Integer.parseInt(normalized.substring(prefix.length(), slash)));
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    /**
     * Chat memory is provider context, not the user's request. Supervisors must
     * judge the actual text after the injected memory envelope.
     */
    private static String extractOriginalUserPrompt(String message) {
        if (message == null || message.isBlank()) return message == null ? "" : message;
        String normalized = message.stripLeading();
        if (!normalized.startsWith("<memory_context>")) return message;
        int end = normalized.indexOf("</memory_context>");
        if (end < 0) return message;
        String userPrompt = normalized.substring(end + "</memory_context>".length()).stripLeading();
        return userPrompt.isBlank() ? message : userPrompt;
    }

    // ── Direction judge (goal-drift supervision) ────────────────────────────

    /** Outcome of one direction check: none / in-place redirect / turn halt. */
    private record DirectionJudgement(
            String redirectPrompt, String reason, boolean redirected, boolean halted) {
        static final DirectionJudgement NONE =
                new DirectionJudgement(null, null, false, false);
        static DirectionJudgement redirect(String prompt) {
            return new DirectionJudgement(prompt, null, true, false);
        }
        static DirectionJudgement halt(String reason, String redirect) {
            return new DirectionJudgement(redirect, reason, false, true);
        }
    }

    /**
     * Run the direction judge at the configured cadence. Returns what, if anything,
     * the turn should do about drift. The judge is NEVER trusted on a degraded check:
     * unavailable backends, malformed verdicts, or low-confidence flags are fail-open.
     */
    private DirectionJudgement checkDirection(
            ai.kompile.cli.main.chat.enforcer.DirectionJudge direction,
            String turnGoal, int iteration, StringBuilder fullResponse,
            String currentMessage, boolean finalResponse) {
        if (!finalResponse && direction.getCheckEvery() > 1
                && iteration % direction.getCheckEvery() != 0) {
            return DirectionJudgement.NONE;
        }
        String outputSoFar = fullResponse.toString();
        if (outputSoFar.isBlank() && (currentMessage == null || currentMessage.isBlank())) {
            return DirectionJudgement.NONE;
        }
        String goal = firstNonBlankText(direction.getGoal(), turnGoal);
        ai.kompile.cli.main.chat.enforcer.DirectionJudge.Verdict verdict;
        emitInlineEnforcerActivity("[direction] check #" + (direction.getChecksThisTurn() + 1)
                + " · iteration " + iteration);
        try {
            verdict = direction.checkTurn(
                    goal, turnGoal == null ? "" : turnGoal,
                    renderRecentConversation(), outputSoFar, iteration);
        } catch (RuntimeException failure) {
            emitInlineEnforcerActivity("[direction] FAIL-OPEN · " + failure.getMessage());
            return DirectionJudgement.NONE;
        }
        if (verdict.failOpen()) {
            emitInlineEnforcerActivity("[direction] FAIL-OPEN · " + verdict.reason());
            return DirectionJudgement.NONE;
        }
        if (verdict.onTrack()) {
            emitInlineEnforcerActivity("[direction] ON TRACK · " + verdict.reason());
            return DirectionJudgement.NONE;
        }
        if (!direction.isActionable(verdict)) {
            emitInlineEnforcerActivity("[direction] IGNORED · confidence "
                    + verdict.confidence() + " < " + direction.getConfidenceThreshold());
            return DirectionJudgement.NONE;
        }
        emitInlineEnforcerActivity("[direction] DRIFT · " + verdict.reason());
        if (direction.isReportOnly()) {
            emitLine(renderer.yellow("[direction] (report-only) drift: " + verdict.reason()));
            return DirectionJudgement.NONE;
        }
        if (direction.isCrossTurnEscalationDue()) {
            int streak = direction.projectedConsecutiveDriftTurns();
            String haltReason = "persistent direction drift across " + streak
                    + " consecutive turns: " + verdict.reason();
            String recovery = persistentDirectionRedirect(
                    goal, streak, verdict.redirectPrompt());
            emitLine(renderer.red("[direction] HALTING turn — " + haltReason));
            return DirectionJudgement.halt(haltReason, recovery);
        }
        // Confident drift verdict. Redirect in place while budget remains; otherwise
        // halt the turn and hand control back through the supervisor lane.
        if (verdict.redirectPrompt() != null && direction.canRedirect()) {
            direction.recordRedirect();
            String notice = "[direction] drift detected — redirecting in place: "
                    + verdict.reason();
            emitLine(renderer.yellow(notice));
            return DirectionJudgement.redirect(verdict.redirectPrompt());
        }
        String haltReason = "direction drift exceeded redirect budget: " + verdict.reason();
        emitLine(renderer.red("[direction] HALTING turn — " + haltReason));
        return DirectionJudgement.halt(haltReason, verdict.redirectPrompt());
    }

    private static String persistentDirectionRedirect(
            String goal, int streak, String judgeRedirect) {
        StringBuilder sb = new StringBuilder();
        sb.append("Persistent direction drift has recurred across ")
                .append(streak).append(" consecutive turns. Stop the current approach and ")
                .append("re-anchor before doing more work.");
        if (goal != null && !goal.isBlank()) {
            String boundedGoal = goal.strip();
            if (boundedGoal.length() > 1_000) {
                boundedGoal = boundedGoal.substring(0, 1_000) + "… [truncated]";
            }
            sb.append("\nOriginal goal: ").append(boundedGoal);
        }
        sb.append("\nState a concise recovery plan, identify which prior path failed to ")
                .append("converge, and continue only with steps that directly serve the goal.");
        if (judgeRedirect != null && !judgeRedirect.isBlank()) {
            sb.append("\nDirection judge guidance: ").append(judgeRedirect.strip());
        }
        return sb.toString();
    }

    private static String directionFeedback(String reason, String redirectPrompt) {
        StringBuilder sb = new StringBuilder();
        sb.append("The direction judge stopped this turn because the conversation was ")
          .append("no longer moving toward the goal.\nReason: ")
          .append(reason == null ? "unspecified" : reason);
        if (redirectPrompt != null && !redirectPrompt.isBlank()) {
            sb.append("\nSuggested redirection for your next turn: ").append(redirectPrompt);
        }
        return sb.toString();
    }

    private static String firstNonBlankText(String a, String b) {
        if (a != null && !a.isBlank()) return a;
        return b;
    }

    /**
     * Render a bounded recent-conversation trail from the durable ledger for the
     * direction judge: goal first, then the newest events last.
     */
    private String renderRecentConversation() {
        try {
            List<CompactionService.ConversationEntry> entries =
                    conversationLedger.snapshot().activeEntries();
            int from = Math.max(0, entries.size() - 40);
            StringBuilder sb = new StringBuilder();
            for (int i = from; i < entries.size(); i++) {
                CompactionService.ConversationEntry entry = entries.get(i);
                String label = switch (entry.type) {
                    case USER -> "user";
                    case ASSISTANT -> "assistant";
                    case TOOL_CALL -> "tool_call " + entry.toolName;
                    case TOOL_RESULT -> "tool_result";
                    case SYSTEM -> "system";
                };
                String content = entry.content == null ? "" : entry.content.strip();
                if (content.length() > 600) {
                    content = content.substring(0, 600) + "… [truncated]";
                }
                if (content.isBlank()) continue;
                sb.append(label).append(": ").append(content).append("\n");
            }
            return sb.toString();
        } catch (RuntimeException ignored) {
            return "(conversation trail unavailable)";
        }
    }

    private record NamedToolDecision(
            String source, EnforcerToolCallDecision decision) { }

    private record ToolInterception(
            JsonNode rewrittenArguments,
            String source,
            String reason,
            String feedback,
            boolean blocked) { }

    /**
     * Execute a synchronous tool away from the dispatch owner. Escape can then
     * release the owner immediately even if an extension blocks or swallows
     * interruption; cooperative built-ins also observe ToolContext's shared abort
     * signal and terminate their underlying subprocess/network operation.
     */
    private ToolResult executeToolInterruptibly(
            CliTool tool, JsonNode arguments, ToolContext context, String toolName,
            java.util.function.Function<ToolResult, Path> saveCompletion)
            throws ToolExecutionException {
        ToolContext executionContext = context.forkForToolExecution();
        // Children may retain this signal/supplier and consumer before Ctrl+B.
        // Switch their routing through stable indirections, not by replacing a
        // context field after the child has already copied it.
        AtomicBoolean detached = new AtomicBoolean(false);
        executionContext.linkAbortSignal(new AtomicBoolean(false));
        executionContext.linkAbortCheck(() -> !detached.get() && context.isAborted());
        AtomicReference<Consumer<String>> output = new AtomicReference<>(context.getOutputConsumer());
        executionContext.setOutputConsumer(sessionContext.wrapConsumer(line -> {
            Consumer<String> sink = output.get();
            if (sink != null) sink.accept(line);
        }));
        FutureTask<ToolResult> execution = new FutureTask<>(
                sessionContext.wrapCallable(() -> tool.execute(arguments, executionContext)));
        Thread worker = new Thread(execution,
                "chat-tool-" + (toolName == null ? "unknown"
                        : toolName.replaceAll("[^A-Za-z0-9_.-]", "_")));
        worker.setDaemon(true);
        worker.start();

        try {
            while (true) {
                ToolDetach transfer = blockingSubagentInvocation.get() ? toolDetach.getAndSet(null) : null;
                if (transfer != null) {
                    detached.set(true);
                    output.set(transfer.output());
                    setBlockingSubagentInvocation(false);
                    transfer.detached().run();
                    Thread completion = new Thread(sessionContext.wrap(() -> {
                        ToolResult result;
                        try {
                            result = execution.get();
                        } catch (Exception failure) {
                            result = ToolResult.error(describeThrowable(failure instanceof ExecutionException
                                    ? failure.getCause() : failure));
                        }
                        try {
                            Path saved = saveCompletion.apply(result);
                            if (saved != null) transfer.output().accept("\n[Final tool result saved to: " + saved + "]\n");
                        } finally {
                            transfer.completed().accept(result);
                        }
                    }), "chat-detached-tool-completion");
                    completion.setDaemon(true);
                    completion.start();
                    return ToolResult.success("Task is still running in the background; this is not its final result. "
                            + "Continue handling user input. Completion will be retained in the activity panel.");
                }
                if (isCancelled() || context.isAborted()) {
                    output.set(ignored -> { });
                    executionContext.abort();
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
            executionContext.abort();
            output.set(ignored -> { });
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

    /**
     * Human-readable, single-line description of any tool failure. Internal errors
     * (NoClassDefFoundError / LinkageError / NoSuchMethodError) carry raw slashed
     * class names like "ai/kompile/cli/main/chat/tools/GrepTool$LineEntry", which
     * read as gibberish in transcripts; expand them into a diagnostic that names
     * the failure class and points at the usual cause.
     */
    public static String describeThrowable(Throwable t) {
        if (t == null) {
            return "unknown tool failure";
        }
        if (t instanceof LinkageError || t instanceof ExceptionInInitializerError) {
            String missing = t.getMessage() == null ? "" : t.getMessage().replace('/', '.');
            String kind = t instanceof NoClassDefFoundError ? "class initialization/lookup failed"
                    : t instanceof NoSuchMethodError ? "binary mismatch (stale build)"
                    : "class linkage failure";
            return "internal " + t.getClass().getSimpleName() + " (" + kind + ")"
                    + (missing.isBlank() ? "" : ": " + missing)
                    + " — the running process may have been built/deployed while this session was live";
        }
        return t.getMessage() == null || t.getMessage().isBlank()
                ? t.getClass().getName() : t.getMessage();
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
                    .execute(sessionContext.wrap(this::publishManagedUpdate));
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
                                           String modelOverride,
                                           List<DirectLlmClient.AttachmentInput> attachments) {
        refreshNativeCompactionTrigger();
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

        StreamingMarkdownRenderer markdownRenderer =
                new StreamingMarkdownRenderer(asciiRenderer, this::emitLine);
        java.util.function.Consumer<String> previousConsumer = directLlmClient.getOutputConsumer();
        java.util.function.Consumer<DirectLlmClient.ConnectivityEvent> previousConnectivityConsumer =
                directLlmClient.getConnectivityEventConsumer();
        DirectLlmClient.ProviderActivityListener previousProviderActivityListener =
                directLlmClient.getProviderActivityListener();
        DirectLlmClient.StreamResult directResult;
        AtomicBoolean reconnecting = new AtomicBoolean();
        directLlmClient.setOutputConsumer(sessionContext.wrapConsumer(chunk -> {
            fireFirstOutput();
            reconnecting.set(false);
            setForegroundActivity("Responding");
            markdownRenderer.accept(chunk);
        }));
        directLlmClient.setConnectivityEventConsumer(sessionContext.wrapConsumer(event -> {
            markdownRenderer.flush();
            reconnecting.set(true);
            // Retry state is transient, not a permanent transcript notification.
            String warning = "Reconnecting " + event.provider() + " · "
                    + event.attempt() + "/" + event.maxAttempts()
                    + " in " + event.delay().toMillis() + " ms (" + event.reason() + ")";
            if (!ChatCompleter.showAlert(warning)) setForegroundActivity(warning);
        }));
        directLlmClient.setProviderActivityListener(
                new DirectLlmClient.ProviderActivityListener() {
                    @Override
                    public void onToolStart(String callId, String name, String input) {
                        sessionContext.wrap(() -> {
                            if (reconnecting.getAndSet(false)) setForegroundActivity("Thinking");
                            if (previousProviderActivityListener != null) {
                                previousProviderActivityListener.onToolStart(callId, name, input);
                            }
                            ToolActivityListener listener = toolActivityListener;
                            if (listener != null) listener.onToolStart(callId, name, input);
                        }).run();
                    }

                    @Override
                    public void onToolComplete(String callId, String name, String output,
                                               int exitCode, boolean error) {
                        sessionContext.wrap(() -> {
                            if (previousProviderActivityListener != null) {
                                previousProviderActivityListener.onToolComplete(
                                        callId, name, output, exitCode, error);
                            }
                            ToolActivityListener listener = toolActivityListener;
                            if (listener != null) {
                                ToolResult providerResult = error
                                        ? ToolResult.error(output == null ? "" : output)
                                        : ToolResult.success(output == null ? "" : output);
                                listener.onToolComplete(
                                        callId, name, "", providerResult);
                            }
                        }).run();
                    }

                    @Override
                    public void onTokenUsage(long input, long output,
                                             long cacheRead, long cacheCreation) {
                        sessionContext.wrap(() -> {
                            if (previousProviderActivityListener != null) {
                                previousProviderActivityListener.onTokenUsage(
                                        input, output, cacheRead, cacheCreation);
                            }
                        }).run();
                    }
                });
        try {
            directResult = directLlmClient.streamChat(message, systemPrompt, toolDefs, directToolResults, modelOverride, attachments);
        } finally {
            // Tool-only, empty, failed and cancelled responses may emit no text.
            if (reconnecting.getAndSet(false)) setForegroundActivity("Thinking");
            markdownRenderer.flush();
            directLlmClient.setOutputConsumer(previousConsumer);
            directLlmClient.setConnectivityEventConsumer(previousConnectivityConsumer);
            directLlmClient.setProviderActivityListener(previousProviderActivityListener);
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
        result.failed = directResult.failed && !directResult.cancelled;
        result.contextOverflow = directResult.isContextOverflow();
        result.contextOverflowRetrySafe = directResult.canRetryAfterContextOverflow();
        result.providerFailureMessage = directResult.failureMessage;
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
        ProviderConnectivityPolicy policy = ProviderConnectivityPolicy.forProvider("kompile");
        for (int attempt = 1; attempt <= policy.maxAttempts(); attempt++) {
            StreamResult result = streamServerTurnAttempt(
                    message, sessionId, serverAgent, ragEnabled, systemPrompt,
                    toolDefs, toolResults, policy);
            if (result.terminalError != null && !result.terminalError.isBlank()) {
                throw new ServerChatException(result.terminalError);
            }
            if (!result.retryableConnectivityFailure) return result;

            boolean replaySafe = !result.streamStarted
                    && result.text.isEmpty() && result.toolCalls.isEmpty();
            if (!replaySafe || attempt == policy.maxAttempts()) {
                String error = "[Kompile connection error: " + result.connectivityFailure + "]";
                if (!replaySafe) {
                    error += "\n[Response was not replayed because the server agent had already started.]";
                }
                emitLine(renderer.red(error));
                throw new ServerChatException(error);
            }

            Duration delay = policy.retryDelay(attempt, result.connectivityHeaders);
            String warning = "Reconnecting Kompile · " + (attempt + 1) + "/"
                    + policy.maxAttempts() + " in " + delay.toMillis()
                    + " ms (" + result.connectivityFailure + ")";
            if (!ChatCompleter.showAlert(warning)) setForegroundActivity(warning);
            try {
                if (!waitForServerRetry(delay)) {
                    result.retryableConnectivityFailure = false;
                    return result;
                }
            } finally {
                setForegroundActivity("Thinking");
            }
        }
        throw new IllegalStateException("Unreachable server connectivity retry state");
    }

    private StreamResult streamServerTurnAttempt(
            String message, String sessionId, String serverAgent,
            boolean ragEnabled, String systemPrompt,
            ArrayNode toolDefs, List<ToolCallResult> toolResults,
            ProviderConnectivityPolicy policy) {
        StreamResult result = new StreamResult();

        StreamingMarkdownRenderer markdownRenderer =
                new StreamingMarkdownRenderer(asciiRenderer, this::emitLine);
        InputStream responseBody = null;
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

            ArrayNode restoredHistory = serverChatHistory(message);
            if (!restoredHistory.isEmpty()) {
                request.set("chatHistory", restoredHistory);
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
                    .timeout(policy.requestTimeout())
                    .build();

            HttpResponse<java.io.InputStream> response = httpClient.send(
                    httpRequest, HttpResponse.BodyHandlers.ofInputStream());

            if (response.statusCode() != 200) {
                response.body().close();
                if (policy.isRetryableStatus(response.statusCode())) {
                    result.retryableConnectivityFailure = true;
                    result.connectivityFailure = "HTTP " + response.statusCode();
                    result.connectivityHeaders = response.headers();
                    return result;
                }
                result.terminalError = "Agent HTTP error " + response.statusCode();
                return result;
            }

            // Parse SSE stream
            responseBody = new IdleTimeoutInputStream(
                    response.body(), policy.streamIdleTimeout());
            activeResponseBody.set(responseBody);
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(responseBody))) {
                String eventType = null;
                StringBuilder dataBuffer = new StringBuilder();
                boolean terminalEvent = false;
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
                        result.streamStarted = true;
                        terminalEvent = terminalEvent || "complete".equals(eventType)
                                || "cancelled".equals(eventType)
                                || "error".equals(eventType);
                        processStreamEvent(eventType, data, result, markdownRenderer);
                        eventType = null;
                        dataBuffer.setLength(0);
                    }
                }
                if (!isCancelled() && !terminalEvent) {
                    throw new IOException("Server connection closed before a terminal event");
                }
            }
            markdownRenderer.flush();

        } catch (Exception e) {
            markdownRenderer.flush();
            if (!isCancelled() && policy.isRetryableFailure(e)) {
                result.retryableConnectivityFailure = true;
                result.connectivityFailure = e.getMessage() == null
                        ? e.getClass().getSimpleName() : e.getMessage();
            } else {
                result.terminalError = e.getMessage() == null
                        ? e.getClass().getSimpleName() : e.getMessage();
            }
        } finally {
            if (responseBody != null) {
                activeResponseBody.compareAndSet(responseBody, null);
            }
        }

        return result;
    }

    /** Project the restored canonical ledger into the server DTO's text history. */
    private ArrayNode serverChatHistory(String currentMessage) {
        ArrayNode history = objectMapper.createArrayNode();
        List<CompactionService.ConversationEntry> entries =
                conversationLedger.snapshot().activeEntries();
        int end = entries.size();
        if (end > 0) {
            CompactionService.ConversationEntry last = entries.get(end - 1);
            if (last.type == CompactionService.EntryType.USER
                    && Objects.equals(last.content, currentMessage)) {
                end--;
            }
        }
        for (int i = 0; i < end; i++) {
            CompactionService.ConversationEntry entry = entries.get(i);
            if (entry.type != CompactionService.EntryType.SYSTEM
                    && entry.type != CompactionService.EntryType.USER
                    && entry.type != CompactionService.EntryType.ASSISTANT) {
                continue;
            }
            if (entry.content == null || entry.content.isBlank()) continue;
            ObjectNode item = objectMapper.createObjectNode();
            item.put("role", entry.role);
            item.put("content", entry.content);
            history.add(item);
        }
        return history;
    }

    private boolean waitForServerRetry(Duration delay) {
        long deadline = System.nanoTime() + delay.toNanos();
        while (System.nanoTime() < deadline) {
            if (isCancelled()) return false;
            try {
                long remaining = deadline - System.nanoTime();
                TimeUnit.NANOSECONDS.sleep(Math.min(
                        remaining, TimeUnit.MILLISECONDS.toNanos(100)));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return !isCancelled();
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
                notifyAssistantDelta(chunk);
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
                            cancelActiveTurn();
                        }
                    }
                    if (!agent.isEmpty()) {
                        emitLine(renderer.dim("[Agent: " + agent + "]"));
                    }
                    ServerEventListener listener = serverEventListener;
                    if (listener != null) listener.onBackendStarted(agent, processId);
                } catch (Exception ignored) {}
                break;

            case "sources":
                markdownRenderer.flush();
                try {
                    JsonNode sources = objectMapper.readTree(data);
                    if (sources.isArray() && sources.size() > 0) {
                        emitLine(renderer.dim("[Retrieved " + sources.size() + " documents]"));
                    }
                    ServerEventListener listener = serverEventListener;
                    if (listener != null) listener.onSources(sources.deepCopy());
                } catch (Exception ignored) {}
                break;

            case "stats":
                markdownRenderer.flush();
                try {
                    JsonNode stats = objectMapper.readTree(data);
                    long durationMs = stats.path("durationMs").asLong(0);
                    JsonNode tokens = stats.path("tokenMetrics");
                    long inputTokens = tokens.path("inputTokens").asLong(0L);
                    long outputTokens = tokens.path("outputTokens").asLong(0L);
                    long cacheReadTokens = tokens.path("cacheReadTokens").asLong(0L);
                    long cacheCreationTokens = tokens.path("cacheCreationTokens").asLong(0L);
                    if (sessionMetrics != null && (inputTokens > 0 || outputTokens > 0
                            || cacheReadTokens > 0 || cacheCreationTokens > 0)) {
                        sessionMetrics.recordTokenUsage(
                                inputTokens, outputTokens,
                                cacheReadTokens, cacheCreationTokens);
                    }
                    long contextInputTokens = tokens.has("contextInputTokens")
                            ? tokens.path("contextInputTokens").asLong(0L)
                            : inputTokens + cacheReadTokens + cacheCreationTokens;
                    if (contextInputTokens > 0) {
                        result.contextInputTokens = contextInputTokens;
                        lastReportedInputTokens = contextInputTokens;
                        lastReportedHistoryTokens = compactionService.estimateTokens(
                                conversationLedger.snapshot().activeEntries());
                    }
                    if (durationMs > 0) {
                        emitLine(renderer.dim("  [completed in " + durationMs + "ms]"));
                    }
                    ServerEventListener listener = serverEventListener;
                    if (listener != null) listener.onStats(stats.deepCopy());
                } catch (Exception ignored) {}
                break;

            case "error":
                markdownRenderer.flush();
                try {
                    JsonNode error = objectMapper.readTree(data);
                    String msg = error.path("message").asText(data);
                    result.terminalError = msg;
                    emitLine(renderer.red("\n[Error: " + msg + "]"));
                } catch (Exception e) {
                    result.terminalError = data;
                    emitLine(renderer.red("\n[Error: " + data + "]"));
                }
                activeRemoteProcessId.set(null);
                break;

            case "complete":
            case "cancelled":
                markdownRenderer.flush();
                activeRemoteProcessId.set(null);
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
        boolean failed;
        boolean contextOverflow;
        boolean contextOverflowRetrySafe;
        String providerFailureMessage;
        boolean streamStarted;
        boolean retryableConnectivityFailure;
        String connectivityFailure;
        HttpHeaders connectivityHeaders;
        String terminalError;
        String nativeCompactionSummary;
        String nativeCompactionStrategy;
        JsonNode nativeCompactionPayload;
        long contextInputTokens;
    }

    private static final class ServerChatException extends RuntimeException {
        private ServerChatException(String message) {
            super(message);
        }
    }

    private static final class ProviderChatException extends RuntimeException {
        private ProviderChatException(String message) {
            super(message);
        }
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
