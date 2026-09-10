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

package ai.kompile.cli.main.chat.exec;

import ai.kompile.cli.common.KompileHome;
import ai.kompile.cli.common.util.JsonUtils;
import ai.kompile.cli.main.chat.ChatHistory;
import ai.kompile.cli.main.chat.ChatMemory;
import ai.kompile.cli.main.chat.ReminderManager;
import ai.kompile.cli.main.chat.ChatSessionMetrics;
import ai.kompile.cli.main.chat.agent.AgentRegistry;
import ai.kompile.cli.main.chat.agent.AgentRunController;
import ai.kompile.cli.main.chat.agent.AgenticChatLoop;
import ai.kompile.cli.main.chat.agent.CustomAgentLoader;
import ai.kompile.cli.main.chat.agent.ProjectChatContext;
import ai.kompile.cli.main.chat.skill.SkillRegistry;
import ai.kompile.cli.main.chat.config.ChatConfig;
import ai.kompile.cli.main.chat.config.DirectLlmClient;
import ai.kompile.cli.main.chat.harness.HarnessConfig;
import ai.kompile.cli.main.chat.mcp.McpBundleToolLoader;
import ai.kompile.cli.main.chat.permission.PermissionService;
import ai.kompile.cli.main.chat.render.TerminalRenderer;
import ai.kompile.cli.main.chat.roles.RoleConfig;
import ai.kompile.cli.main.chat.roles.RoleManager;
import ai.kompile.cli.main.coordination.CoordinationStateManager;
import ai.kompile.cli.main.chat.tools.BackgroundProcessManager;
import ai.kompile.cli.main.chat.tools.ToolResult;
import ai.kompile.cli.main.chat.tools.ToolRegistry;
import ai.kompile.cli.main.chat.tools.ToolRegistryFactory;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Runs kompile's native agent ({@link AgenticChatLoop}) for a single prompt,
 * non-interactively, and streams its text to stdout — the engine behind
 * {@code kompile exec} (analogous to {@code codex exec} / {@code opencode run}).
 *
 * <p>The harness build mirrors {@code EvalRunner.executeInternal}: load the local
 * LLM {@link ChatConfig}, create a {@link DirectLlmClient}, auto-approve permissions
 * (no interactive prompts are possible), and run one {@code loop.chat(...)} turn.
 *
 * <p><b>Output routing.</b> The loop streams assistant text through
 * {@code DirectLlmClient.printStreamingChunk} and prints all of its "chrome"
 * (step markers, tool indicators, spinners) directly to {@code System.out}. To keep
 * stdout clean and pipe-friendly we:
 * <ul>
 *   <li>use {@link CapturingLlmClient}, which overrides {@code printStreamingChunk}
 *       to forward the <em>raw</em> text (no markdown/ANSI) to a mode-specific sink
 *       on the real stdout; and</li>
 *   <li>redirect {@code System.out} to stderr (or a sink in quiet mode) for the
 *       duration of the run, so all chrome lands on stderr.</li>
 * </ul>
 * Our own output always goes through the captured {@code realOut}/{@code realErr}
 * references, independent of the {@code System.out} redirect.
 */
public final class HeadlessAgentRunner {

    /** System.out is process-global; serialize the temporary chrome redirect. */
    private static final Object STDOUT_REDIRECT_LOCK = new Object();

    /** How the agent's output is presented on stdout. */
    public enum OutputMode {
        /** Stream raw assistant text to stdout; chrome/progress to stderr. */
        TEXT,
        /** Print only the final response text to stdout; suppress everything else. */
        QUIET,
        /** Emit a JSONL event stream (session/text/tool/result) to stdout; chrome to stderr. */
        JSON
    }

    /** Immutable run configuration. */
    public record Options(
            String prompt,
            String sessionId,
            boolean resume,
            String agentName,
            String modelOverride,
            OutputMode outputMode,
            Path workingDirectory,
            long timeoutMs,
            Path outputLastMessage,
            String crawlBaseUrl,
            AgentRunController runController,
            HeadlessRunEventSink eventSink,
            ChatConfig chatConfig,
            String serverBaseUrl,
            boolean ragEnabled,
            boolean memoryEnabled,
            String roleName,
            boolean autoApproveTools,
            List<DirectLlmClient.AttachmentInput> attachments) {

        public Options {
            attachments = attachments == null ? List.of() : List.copyOf(attachments);
        }

        /** Compatibility constructor for callers compiled against the pre-attachment shape. */
        public Options(String prompt, String sessionId, boolean resume, String agentName,
                       String modelOverride, OutputMode outputMode, Path workingDirectory,
                       long timeoutMs, Path outputLastMessage, String crawlBaseUrl,
                       AgentRunController runController, HeadlessRunEventSink eventSink,
                       ChatConfig chatConfig, String serverBaseUrl, boolean ragEnabled,
                       boolean memoryEnabled, String roleName, boolean autoApproveTools) {
            this(prompt, sessionId, resume, agentName, modelOverride, outputMode,
                    workingDirectory, timeoutMs, outputLastMessage, crawlBaseUrl,
                    runController, eventSink, chatConfig, serverBaseUrl, ragEnabled,
                    memoryEnabled, roleName, autoApproveTools, List.of());
        }

        /** Backward-compatible options used by the general {@code kompile exec} command. */
        public Options(String prompt, String sessionId, boolean resume, String agentName,
                       String modelOverride, OutputMode outputMode, Path workingDirectory,
                       long timeoutMs, Path outputLastMessage) {
            this(prompt, sessionId, resume, agentName, modelOverride, outputMode,
                    workingDirectory, timeoutMs, outputLastMessage, null, null, null,
                    null, null, false, false, null, true, List.of());
        }

        /** Backward-compatible options used by crawl workers. */
        public Options(String prompt, String sessionId, boolean resume, String agentName,
                       String modelOverride, OutputMode outputMode, Path workingDirectory,
                       long timeoutMs, Path outputLastMessage, String crawlBaseUrl,
                       AgentRunController runController) {
            this(prompt, sessionId, resume, agentName, modelOverride, outputMode,
                    workingDirectory, timeoutMs, outputLastMessage, crawlBaseUrl,
                    runController, null, null, null, false, false, null, true, List.of());
        }

        /** Compatibility constructor for callers that supplied an observational event sink. */
        public Options(String prompt, String sessionId, boolean resume, String agentName,
                       String modelOverride, OutputMode outputMode, Path workingDirectory,
                       long timeoutMs, Path outputLastMessage, String crawlBaseUrl,
                       AgentRunController runController, HeadlessRunEventSink eventSink) {
            this(prompt, sessionId, resume, agentName, modelOverride, outputMode,
                    workingDirectory, timeoutMs, outputLastMessage, crawlBaseUrl,
                    runController, eventSink, null, null, false, false, null, true,
                    List.of());
        }
    }

    /** Run outcome. {@code exitCode} 0 = ok, 124 = timed out, 1 = error. */
    public record Result(int exitCode, String text, String sessionId) {}

    public Result run(Options opts) {
        synchronized (STDOUT_REDIRECT_LOCK) {
            return runWithRedirect(opts);
        }
    }

    private Result runWithRedirect(Options opts) {
        final PrintStream realOut = System.out;
        final PrintStream realErr = System.err;
        final ObjectMapper mapper = JsonUtils.standardMapper();
        HeadlessRunEventSink configuredEventSink = opts.eventSink();
        if (configuredEventSink == null && opts.outputMode() == OutputMode.JSON) {
            configuredEventSink = event -> {
                synchronized (realOut) {
                    realOut.println(ExecJsonEvents.event(mapper, event));
                    realOut.flush();
                }
            };
        }
        EventPublisher events = new EventPublisher(configuredEventSink);
        final PrintStream chromeTarget = (opts.outputMode() == OutputMode.QUIET)
                ? new PrintStream(OutputStream.nullOutputStream(), true, StandardCharsets.UTF_8)
                : realErr;
        System.setOut(chromeTarget);
        try {
            return runInternal(opts, realOut, realErr, mapper, events);
        } catch (RuntimeException e) {
            String message = e.getMessage() == null
                    ? e.getClass().getSimpleName() : e.getMessage();
            events.publishTerminal(HeadlessRunEvent.failed(opts.sessionId(), message, 1));
            if (opts.outputMode() != OutputMode.JSON) {
                realErr.println("Error: " + message);
            }
            return new Result(1, "", opts.sessionId());
        } finally {
            System.setOut(realOut);
            if (chromeTarget != realErr) {
                chromeTarget.close();
            }
        }
    }

    private Result runInternal(Options opts, PrintStream realOut, PrintStream realErr,
                               ObjectMapper mapper, EventPublisher events) {
        boolean serverMode = opts.serverBaseUrl() != null && !opts.serverBaseUrl().isBlank();

        // ── Resolve the same project-scoped config used by interactive chat ──
        ChatConfig config = opts.chatConfig() != null
                ? opts.chatConfig() : ChatConfig.loadOrFromEnv(opts.workingDirectory());
        if (config == null && serverMode) {
            config = new ChatConfig("kompile", null, null, opts.serverBaseUrl());
        }
        if (config == null) {
            String msg = "No LLM configuration found. Run `kompile chat --setup` to configure a provider and model.";
            events.publishTerminal(HeadlessRunEvent.failed(opts.sessionId(), msg, 1));
            if (opts.outputMode() != OutputMode.JSON) {
                realErr.println(msg);
            }
            return new Result(1, "", opts.sessionId());
        }
        if (opts.modelOverride() != null) {
            config.setModel(opts.modelOverride());
        }
        if (!serverMode && ("passthrough".equalsIgnoreCase(config.getChatMode())
                || !config.isValid())) {
            String msg = "Incomplete Standard Chat configuration for provider '"
                    + config.getProvider() + "'. Run `kompile chat --setup`.";
            events.publishTerminal(HeadlessRunEvent.failed(opts.sessionId(), msg, 2));
            if (opts.outputMode() != OutputMode.JSON) realErr.println(msg);
            return new Result(2, "", opts.sessionId());
        }

        AgentRegistry agentRegistry = new AgentRegistry();
        for (var custom : new CustomAgentLoader(opts.workingDirectory()).loadAll().values()) {
            agentRegistry.register(custom);
        }
        RoleManager roleManager = new RoleManager(opts.workingDirectory());
        String localAgent = firstNonBlank(
                serverMode ? null : opts.agentName(), config.getDefaultAgent(), "coder");
        String serverAgent = serverMode
                ? firstNonBlank(opts.agentName(), "claude-cli") : localAgent;
        if (opts.roleName() != null && !opts.roleName().isBlank()) {
            RoleConfig role = roleManager.getRole(opts.roleName());
            if (role == null) {
                String msg = "Role not found: " + opts.roleName();
                events.publishTerminal(HeadlessRunEvent.failed(opts.sessionId(), msg, 2));
                if (opts.outputMode() != OutputMode.JSON) realErr.println(msg);
                return new Result(2, "", opts.sessionId());
            }
            agentRegistry.register(role.toAgentConfig());
            localAgent = role.getName();
        }
        String effectiveAgent = serverMode ? serverAgent : localAgent;
        boolean effectiveRag = serverMode && opts.ragEnabled();

        Map<String, String> effectiveConfiguration = new LinkedHashMap<>();
        effectiveConfiguration.put("mode", serverMode ? "server" : "standard");
        String effectiveProvider = serverMode ? "kompile" : config.getProvider();
        effectiveConfiguration.put("provider", nullToEmpty(effectiveProvider));
        effectiveConfiguration.put("auth", serverMode ? "none" : effectiveAuth(config));
        effectiveConfiguration.put("thinking", serverMode
                ? "" : nullToEmpty(config.getThinking()));
        effectiveConfiguration.put("agent", effectiveAgent);
        effectiveConfiguration.put("role", nullToEmpty(opts.roleName()));
        effectiveConfiguration.put("rag", Boolean.toString(effectiveRag));
        effectiveConfiguration.put("memory", Boolean.toString(opts.memoryEnabled()));
        events.publish(HeadlessRunEvent.started(opts.sessionId(),
                serverMode ? null : config.getModel(),
                opts.workingDirectory().toString(), effectiveConfiguration));

        // ── Mode-specific raw-text sink (writes to the REAL stdout) ─────────
        final StreamingTextCapture streamedText = new StreamingTextCapture();
        final Consumer<String> textSink = switch (opts.outputMode()) {
            case TEXT -> chunk -> {
                if (events.isClosed()) return;
                streamedText.append(chunk);
                events.publish(HeadlessRunEvent.assistantDelta(opts.sessionId(), chunk));
                realOut.print(chunk);
            };
            case JSON -> chunk -> {
                if (events.isClosed()) return;
                streamedText.append(chunk);
                events.publish(HeadlessRunEvent.assistantDelta(opts.sessionId(), chunk));
            };
            case QUIET -> chunk -> {
                if (events.isClosed()) return;
                streamedText.append(chunk);
                events.publish(HeadlessRunEvent.assistantDelta(opts.sessionId(), chunk));
            };
        };

        final CapturingLlmClient directClient = serverMode
                ? null : new CapturingLlmClient(
                config, mapper, textSink, opts.workingDirectory());

        // ── Build the agent harness (auto-approve: non-interactive) ─────────
        PermissionService permissionService = new PermissionService();
        permissionService.setAutoApproveAll(opts.autoApproveTools());
        BackgroundProcessManager processManager = new BackgroundProcessManager(
                opts.sessionId(), opts.workingDirectory());
        CoordinationStateManager coordinationManager = new CoordinationStateManager(
                opts.workingDirectory(), opts.sessionId(), mapper);
        TerminalRenderer renderer = new TerminalRenderer();
        ToolRegistry toolRegistry = ToolRegistryFactory.create(
                mapper, serverMode ? opts.serverBaseUrl() : "", agentRegistry,
                permissionService, renderer, processManager,
                serverMode ? null : config, roleManager, opts.crawlBaseUrl(),
                opts.workingDirectory(), coordinationManager);
        ProjectChatContext projectContext = ProjectChatContext.load(opts.workingDirectory());
        AgenticChatLoop loop = new AgenticChatLoop(
                serverMode ? opts.serverBaseUrl() : null,
                mapper, toolRegistry, permissionService, agentRegistry,
                opts.workingDirectory(), directClient, processManager,
                projectContext.skillRegistry());
        loop.setWorkflowGlobalEnabled(
                HarnessConfig.load(mapper).isJudgeGlobalEnabled());
        loop.configureConversationSession(opts.sessionId());
        if (!serverMode && !opts.attachments().isEmpty()) {
            loop.setPendingAttachments(opts.attachments());
        }
        ReminderManager reminderManager = new ReminderManager(
                mapper, opts.sessionId(), opts.workingDirectory());
        loop.setReminderManager(reminderManager);
        if (serverMode) {
            loop.setAssistantDeltaListener(textSink);
            loop.setServerEventListener(new AgenticChatLoop.ServerEventListener() {
                @Override
                public void onBackendStarted(String agent, String processId) {
                    events.publish(HeadlessRunEvent.backendStarted(
                            opts.sessionId(), agent, processId));
                }

                @Override
                public void onSources(com.fasterxml.jackson.databind.JsonNode sources) {
                    events.publish(HeadlessRunEvent.sources(
                            opts.sessionId(), sources.toString()));
                }

                @Override
                public void onStats(com.fasterxml.jackson.databind.JsonNode stats) {
                    events.publish(HeadlessRunEvent.stats(
                            opts.sessionId(), stats.toString()));
                }
            });
        }
        if (opts.runController() != null) {
            loop.setRunController(opts.runController());
        }

        // ── Ordered tool lifecycle events ───────────────────────────────────
        final ToolEventCounter toolCounter = new ToolEventCounter();
        Map<String, Long> toolStarts = new ConcurrentHashMap<>();
        loop.setToolActivityListener(new AgenticChatLoop.ToolActivityListener() {
            @Override
            public void onToolStart(String callId, String toolName, String rawInput) {
                toolStarts.put(callId == null ? "" : callId, System.currentTimeMillis());
                events.publish(HeadlessRunEvent.toolStarted(opts.sessionId(), callId, toolName, rawInput));
            }

            @Override
            public void onToolComplete(String callId, String toolName, String rawInput, ToolResult result) {
                String key = callId == null ? "" : callId;
                long started = toolStarts.getOrDefault(key, System.currentTimeMillis());
                long duration = Math.max(0, System.currentTimeMillis() - started);
                toolCounter.inc();
                events.publish(HeadlessRunEvent.toolCompleted(opts.sessionId(), callId, toolName,
                        rawInput, result != null && !result.isError(), duration));
            }
        });
        ChatSessionMetrics metrics = new EventEmittingMetrics(opts.sessionId(), events);
        metrics.setProvider(effectiveProvider);
        metrics.setModel(config.getModel());
        metrics.setAgentName(effectiveAgent);
        loop.setSessionMetrics(metrics);

        AtomicBoolean cancel = new AtomicBoolean(false);
        loop.setCancelSignal(cancel);

        // ── Restore prior session (for --continue / --resume) ───────────────
        if (opts.resume() && ChatHistory.exists(opts.sessionId())) {
            try {
                List<ChatHistory.Turn> turns = new ChatHistory(opts.sessionId()).readTurns();
                if (turns != null && !turns.isEmpty()) {
                    loop.restoreHistory(turns);
                }
            } catch (Exception e) {
                realErr.println("Warning: could not restore session history: " + e.getMessage());
            }
        }

        // ── Persist this run's turns so future --resume picks them up ───────
        ChatHistory history = new ChatHistory(opts.sessionId());
        try {
            history.open(serverMode ? opts.serverBaseUrl() : "(local)", effectiveAgent,
                    effectiveRag, opts.workingDirectory());
        } catch (Exception ignored) {
            // Transcript persistence is best-effort; never block the run on it.
        }
        String resolvedPrompt = projectContext.skillRegistry().resolveInvocation(opts.prompt())
                .map(SkillRegistry.SkillInvocation::prompt)
                .orElse(opts.prompt());
        ChatMemory chatMemory = new ChatMemory(
                null, opts.sessionId(), opts.memoryEnabled(), opts.workingDirectory());
        String memoryContext = chatMemory.buildMemoryContext(resolvedPrompt);
        String effectivePrompt = memoryContext == null || memoryContext.isBlank()
                ? resolvedPrompt
                : "<memory_context>\n" + memoryContext
                + "</memory_context>\n\n" + resolvedPrompt;
        // Decorate once here: the transcript records the outbound text and the agentic
        // loop's idempotent decoration will not tick or stack a second block.
        String outboundPrompt = reminderManager.decorateUserTurn(effectivePrompt);
        history.logUserMessage(outboundPrompt);

        // ── Run; the public wrapper already routed terminal chrome off stdout ─

        int exitCode = 0;
        String response = "";
        String failureMessage = null;
        long start = System.currentTimeMillis();
        McpBundleToolLoader mcpBundleTools = null;
        try {
            // Process-backed MCP bundles are intentionally acquired inside the cleanup
            // scope so even a required-server startup failure closes headless resources.
            mcpBundleTools = McpBundleToolLoader.load(
                    opts.workingDirectory(), toolRegistry, opts.sessionId());
            response = opts.timeoutMs() > 0
                    ? runWithTimeout(loop, opts, outboundPrompt,
                    localAgent, serverAgent, effectiveRag, cancel)
                    : loop.chat(outboundPrompt, opts.sessionId(), localAgent,
                    serverAgent, effectiveRag);
            if (response == null) { // null sentinel from runWithTimeout == timed out
                response = streamedText.captured();
                exitCode = 124;
            }
        } catch (Exception e) {
            response = streamedText.captured();
            exitCode = 1;
            failureMessage = e.getMessage() == null
                    ? e.getClass().getSimpleName() : e.getMessage();
            if (opts.outputMode() != OutputMode.JSON) {
                realErr.println("Error: " + failureMessage);
            }
        } finally {
            if (mcpBundleTools != null) {
                mcpBundleTools.close();
            }
            if (directClient != null) {
                directClient.close();
            }
        }
        if (response == null) {
            response = "";
        }
        long durationMs = System.currentTimeMillis() - start;

        try {
            history.logAgentResponse(effectiveAgent, response, durationMs);
        } catch (Exception ignored) {
            // best-effort
        }
        metrics.recordAssistantTurn(response, durationMs);
        metrics.saveToFile(
                KompileHome.homeDirectory().toPath().resolve("conversations")
                        .resolve(opts.sessionId() + ".metrics.json"), mapper);
        history.close();
        processManager.close();
        coordinationManager.shutdown();

        HeadlessRunEvent terminalEvent = exitCode == 1
                ? HeadlessRunEvent.failed(opts.sessionId(), failureMessage, exitCode)
                : HeadlessRunEvent.completed(
                opts.sessionId(), response, exitCode, toolCounter.count());
        events.publishTerminal(terminalEvent);

        // ── Final output per mode ───────────────────────────────────────────
        switch (opts.outputMode()) {
            case TEXT -> {
                if (exitCode != 1) realOut.println(); // newline after the streamed text
            }
            case QUIET -> realOut.println(response.stripTrailing());
            case JSON -> { /* terminal JSON event was published above */ }
        }

        if (opts.outputLastMessage() != null) {
            try {
                Files.writeString(opts.outputLastMessage(), response, StandardCharsets.UTF_8);
            } catch (Exception e) {
                realErr.println("Warning: could not write --output-last-message: " + e.getMessage());
            }
        }

        if (exitCode == 124 && opts.outputMode() != OutputMode.JSON) {
            realErr.println("[timed out after " + (opts.timeoutMs() / 1000) + "s]");
        }
        return new Result(exitCode, response, opts.sessionId());
    }

    /**
     * Run the loop on a worker thread bounded by {@code opts.timeoutMs()}.
     * On timeout, signals cancellation and returns {@code null} (the caller
     * substitutes whatever text was streamed so far).
     */
    private String runWithTimeout(AgenticChatLoop loop, Options opts, String prompt,
                                  String localAgent, String serverAgent, boolean effectiveRag,
                                  AtomicBoolean cancel) throws Exception {
        ExecutorService exec = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "kompile-exec");
            t.setDaemon(true);
            return t;
        });
        Future<String> future = exec.submit(() ->
                loop.chat(prompt, opts.sessionId(), localAgent,
                        serverAgent, effectiveRag));
        try {
            return future.get(opts.timeoutMs(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException te) {
            cancel.set(true);
            loop.cancelActiveTurn();
            future.cancel(true);
            return null;
        } catch (ExecutionException e) {
            cancel.set(true);
            future.cancel(true);
            Throwable cause = e.getCause();
            if (cause instanceof Exception exception) throw exception;
            throw new RuntimeException(cause);
        } catch (InterruptedException e) {
            cancel.set(true);
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw e;
        } finally {
            exec.shutdownNow();
            try {
                exec.awaitTermination(2, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static String firstNonBlank(String... values) {
        if (values != null) {
            for (String value : values) {
                if (value != null && !value.isBlank()) return value;
            }
        }
        return "";
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static String effectiveAuth(ChatConfig config) {
        if (config == null) return "none";
        if (config.isOpenCodeNative()) return "native";
        var auth = config.resolveRequestAuth();
        if (auth == null || auth.token() == null || auth.token().isBlank()) return "none";
        return auth.oauth() ? "oauth" : "api-key";
    }

    // ========================================================================
    // Collaborators
    // ========================================================================

    /** Assigns a monotonic sequence and serializes delivery to each consumer. */
    private static final class EventPublisher {
        private final HeadlessRunEventSink sink;
        private final AtomicLong sequence = new AtomicLong();
        private boolean closed;

        private EventPublisher(HeadlessRunEventSink sink) {
            this.sink = sink;
        }

        synchronized void publish(HeadlessRunEvent event) {
            if (closed || event == null) return;
            try {
                if (sink != null) sink.accept(event.withSequence(sequence.incrementAndGet()));
            } catch (RuntimeException ignored) {
                // Event sinks are observational; a broken sink must not fail the run.
            }
        }

        synchronized void publishTerminal(HeadlessRunEvent event) {
            if (closed) return;
            try {
                if (sink != null && event != null) {
                    sink.accept(event.withSequence(sequence.incrementAndGet()));
                }
            } catch (RuntimeException ignored) {
                // Terminal delivery remains best-effort for observational sinks.
            } finally {
                closed = true;
            }
        }

        synchronized boolean isClosed() {
            return closed;
        }
    }

    /**
     * A {@link DirectLlmClient} that captures raw streamed text and forwards it to a
     * sink, deliberately bypassing the loop's markdown renderer (which writes ANSI to
     * {@code System.out}). This keeps stdout free of styling for pipe consumers.
     */
    static final class CapturingLlmClient extends DirectLlmClient {
        private final Consumer<String> sink;
        private final StringBuilder captured = new StringBuilder();

        CapturingLlmClient(ChatConfig config, ObjectMapper mapper, Consumer<String> sink,
                           Path workingDirectory) {
            super(config, mapper, workingDirectory);
            this.sink = sink;
        }

        @Override
        protected void printStreamingChunk(String chunk) {
            if (chunk == null) {
                return;
            }
            captured.append(chunk);
            if (sink != null) {
                sink.accept(chunk);
            }
        }

        String captured() {
            return captured.toString();
        }
    }

    /** Thread-safe capture shared by direct-model and server-SSE transports. */
    static final class StreamingTextCapture {
        private final StringBuilder text = new StringBuilder();

        synchronized void append(String chunk) {
            if (chunk != null) text.append(chunk);
        }

        synchronized String captured() {
            return text.toString();
        }
    }

    /** Thread-safe counter for completed tool calls (used in the JSON {@code result} event). */
    static final class ToolEventCounter {
        private int n;

        synchronized void inc() { n++; }

        synchronized int count() { return n; }
    }

    /** Emits provider-reported token usage through the same ordered event stream. */
    static final class EventEmittingMetrics extends ChatSessionMetrics {
        private final String sessionId;
        private final EventPublisher events;

        EventEmittingMetrics(String sessionId, EventPublisher events) {
            super(sessionId);
            this.sessionId = sessionId;
            this.events = events;
        }

        @Override
        public void recordTokenUsage(long input, long output,
                                     long cacheRead, long cacheCreation) {
            super.recordTokenUsage(input, output, cacheRead, cacheCreation);
            events.publish(HeadlessRunEvent.tokenUsage(
                    sessionId, input, output, cacheRead, cacheCreation));
        }
    }
}
