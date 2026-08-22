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

import ai.kompile.cli.common.util.JsonUtils;
import ai.kompile.cli.main.chat.ChatHistory;
import ai.kompile.cli.main.chat.ChatSessionMetrics;
import ai.kompile.cli.main.chat.agent.AgentRegistry;
import ai.kompile.cli.main.chat.agent.AgentRunController;
import ai.kompile.cli.main.chat.agent.AgenticChatLoop;
import ai.kompile.cli.main.chat.config.ChatConfig;
import ai.kompile.cli.main.chat.config.DirectLlmClient;
import ai.kompile.cli.main.chat.mcp.McpBundleToolLoader;
import ai.kompile.cli.main.chat.permission.PermissionService;
import ai.kompile.cli.main.chat.render.TerminalRenderer;
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
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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
            HeadlessRunEventSink eventSink) {

        /** Backward-compatible options used by the general {@code kompile exec} command. */
        public Options(String prompt, String sessionId, boolean resume, String agentName,
                       String modelOverride, OutputMode outputMode, Path workingDirectory,
                       long timeoutMs, Path outputLastMessage) {
            this(prompt, sessionId, resume, agentName, modelOverride, outputMode,
                    workingDirectory, timeoutMs, outputLastMessage, null, null, null);
        }

        /** Backward-compatible options used by crawl workers. */
        public Options(String prompt, String sessionId, boolean resume, String agentName,
                       String modelOverride, OutputMode outputMode, Path workingDirectory,
                       long timeoutMs, Path outputLastMessage, String crawlBaseUrl,
                       AgentRunController runController) {
            this(prompt, sessionId, resume, agentName, modelOverride, outputMode,
                    workingDirectory, timeoutMs, outputLastMessage, crawlBaseUrl,
                    runController, null);
        }
    }

    /** Run outcome. {@code exitCode} 0 = ok, 124 = timed out, 1 = error. */
    public record Result(int exitCode, String text, String sessionId) {}

    public Result run(Options opts) {
        final ObjectMapper mapper = JsonUtils.standardMapper();
        final PrintStream realOut = System.out;
        final PrintStream realErr = System.err;
        HeadlessRunEventSink configuredEventSink = opts.eventSink();
        if (configuredEventSink == null && opts.outputMode() == OutputMode.JSON) {
            configuredEventSink = event -> realOut.println(ExecJsonEvents.event(mapper, event));
        }
        EventPublisher events = new EventPublisher(configuredEventSink);

        // ── Resolve local LLM config (direct mode) ──────────────────────────
        ChatConfig config = ChatConfig.loadOrFromEnv();
        if (config == null) {
            String msg = "No LLM configuration found. Run `kompile chat --setup` to configure a provider and model.";
            events.publish(HeadlessRunEvent.failed(opts.sessionId(), msg, 1));
            if (opts.outputMode() != OutputMode.JSON) {
                realErr.println(msg);
            }
            return new Result(1, "", opts.sessionId());
        }
        if (opts.modelOverride() != null) {
            config.setModel(opts.modelOverride());
        }
        events.publish(HeadlessRunEvent.started(opts.sessionId(), config.getModel(),
                opts.workingDirectory().toString()));

        // ── Mode-specific raw-text sink (writes to the REAL stdout) ─────────
        final Consumer<String> textSink = switch (opts.outputMode()) {
            case TEXT -> chunk -> {
                events.publish(HeadlessRunEvent.assistantDelta(opts.sessionId(), chunk));
                realOut.print(chunk);
            };
            case JSON -> chunk -> events.publish(HeadlessRunEvent.assistantDelta(opts.sessionId(), chunk));
            case QUIET -> chunk -> events.publish(HeadlessRunEvent.assistantDelta(opts.sessionId(), chunk));
        };

        final CapturingLlmClient directClient = new CapturingLlmClient(config, mapper, textSink);

        // ── Build the agent harness (auto-approve: non-interactive) ─────────
        PermissionService permissionService = new PermissionService();
        permissionService.setAutoApproveAll(true);
        AgentRegistry agentRegistry = new AgentRegistry();
        BackgroundProcessManager processManager = new BackgroundProcessManager(
                opts.sessionId(), opts.workingDirectory());
        TerminalRenderer renderer = new TerminalRenderer();
        ToolRegistry toolRegistry = ToolRegistryFactory.create(
                mapper, "", agentRegistry, permissionService, renderer, processManager,
                config, null, opts.crawlBaseUrl());
        // Offline describes the transport topology, not a reduced capability set.
        // Load the same workspace MCP bundles (including Kompile stdio) as normal direct execution.
        McpBundleToolLoader mcpBundleTools =
                McpBundleToolLoader.load(opts.workingDirectory(), toolRegistry);

        AgenticChatLoop loop = new AgenticChatLoop(
                null, mapper, toolRegistry, permissionService, agentRegistry,
                opts.workingDirectory(), directClient, processManager);
        loop.configureConversationSession(opts.sessionId());
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
        ChatSessionMetrics metrics = new ChatSessionMetrics(opts.sessionId());
        metrics.setProvider(config.getProvider());
        metrics.setModel(config.getModel());
        metrics.setAgentName(opts.agentName());
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
            history.open("(local)", opts.agentName(), false);
        } catch (Exception ignored) {
            // Transcript persistence is best-effort; never block the run on it.
        }
        history.logUserMessage(opts.prompt());

        // ── Run, with all loop chrome redirected off of stdout ──────────────
        final PrintStream chromeTarget = (opts.outputMode() == OutputMode.QUIET)
                ? new PrintStream(OutputStream.nullOutputStream(), true, StandardCharsets.UTF_8)
                : realErr;
        System.setOut(chromeTarget);

        int exitCode = 0;
        String response = "";
        long start = System.currentTimeMillis();
        try {
            response = opts.timeoutMs() > 0
                    ? runWithTimeout(loop, opts, directClient, cancel)
                    : loop.chat(opts.prompt(), opts.sessionId(), opts.agentName(), "kompile", false);
            if (response == null) { // null sentinel from runWithTimeout == timed out
                response = directClient.captured();
                exitCode = 124;
            }
        } catch (Exception e) {
            response = directClient.captured();
            exitCode = 1;
            System.setOut(realOut);
            events.publish(HeadlessRunEvent.failed(opts.sessionId(), String.valueOf(e.getMessage()), 1));
            if (opts.outputMode() != OutputMode.JSON) {
                realErr.println("Error: " + e.getMessage());
            }
        } finally {
            System.setOut(realOut);
            if (mcpBundleTools != null) {
                mcpBundleTools.close();
            }
        }
        if (response == null) {
            response = "";
        }
        long durationMs = System.currentTimeMillis() - start;

        try {
            history.logAgentResponse(opts.agentName(), response, durationMs);
        } catch (Exception ignored) {
            // best-effort
        }

        // ── Final output per mode ───────────────────────────────────────────
        switch (opts.outputMode()) {
            case TEXT -> {
                if (exitCode != 1) realOut.println(); // newline after the streamed text
            }
            case QUIET -> realOut.println(response.stripTrailing());
            case JSON -> {
                if (exitCode == 1) {
                    // The failure event was emitted by the catch block.
                } else {
                    events.publish(HeadlessRunEvent.completed(
                            opts.sessionId(), response, exitCode, toolCounter.count()));
                }
            }
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
    private String runWithTimeout(AgenticChatLoop loop, Options opts,
                                  CapturingLlmClient directClient, AtomicBoolean cancel) {
        ExecutorService exec = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "kompile-exec");
            t.setDaemon(true);
            return t;
        });
        Future<String> future = exec.submit(() ->
                loop.chat(opts.prompt(), opts.sessionId(), opts.agentName(), "kompile", false));
        try {
            return future.get(opts.timeoutMs(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException te) {
            cancel.set(true);
            future.cancel(true);
            return null;
        } catch (Exception e) {
            cancel.set(true);
            future.cancel(true);
            // Surface as a streamed-text fallback rather than throwing.
            return directClient.captured();
        } finally {
            exec.shutdownNow();
        }
    }

    // ========================================================================
    // Collaborators
    // ========================================================================

    /** Assigns a monotonic sequence and serializes delivery to each consumer. */
    private static final class EventPublisher {
        private final HeadlessRunEventSink sink;
        private final AtomicLong sequence = new AtomicLong();

        private EventPublisher(HeadlessRunEventSink sink) {
            this.sink = sink;
        }

        synchronized void publish(HeadlessRunEvent event) {
            if (sink == null || event == null) return;
            try {
                sink.accept(event.withSequence(sequence.incrementAndGet()));
            } catch (RuntimeException ignored) {
                // Event sinks are observational; a broken sink must not fail the run.
            }
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

        CapturingLlmClient(ChatConfig config, ObjectMapper mapper, Consumer<String> sink) {
            super(config, mapper);
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

    /** Thread-safe counter for completed tool calls (used in the JSON {@code result} event). */
    static final class ToolEventCounter {
        private int n;

        synchronized void inc() { n++; }

        synchronized int count() { return n; }
    }

    /** {@link ChatSessionMetrics} that also emits a JSONL {@code tool} event per completed tool call. */
    static final class JsonEmittingMetrics extends ChatSessionMetrics {
        private final PrintStream out;
        private final ObjectMapper mapper;
        private final ToolEventCounter counter;

        JsonEmittingMetrics(String sessionId, PrintStream out, ObjectMapper mapper, ToolEventCounter counter) {
            super(sessionId);
            this.out = out;
            this.mapper = mapper;
            this.counter = counter;
        }

        @Override
        public void recordToolCall(String toolName, boolean isError, long durationMs) {
            super.recordToolCall(toolName, isError, durationMs);
            counter.inc();
            out.println(ExecJsonEvents.tool(mapper, toolName, !isError, durationMs));
        }
    }
}
