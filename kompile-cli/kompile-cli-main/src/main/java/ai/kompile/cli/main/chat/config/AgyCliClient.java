/*
 * Copyright 2026 Kompile Inc.
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.cli.main.chat.config;

import ai.kompile.cli.main.chat.PassthroughStreamParser;
import ai.kompile.cli.main.chat.agent.SubagentContainment;
import ai.kompile.cli.main.chat.agent.SubagentDelegationGuard;
import ai.kompile.cli.main.chat.agent.SubprocessAgentRunner;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * The chat's transport for Gemini turns that run on the Antigravity CLI (agy).
 * <p>
 * agy has no serve mode: each headless turn is one {@code agy -p <message>
 * --output-format stream-json} process, whose stream-json envelopes the shared
 * {@link PassthroughStreamParser} already understands ({@code init} carries the
 * conversation id, {@code step_update.agent_response.text_delta} streams text,
 * {@code result} settles the turn's cumulative usage). A new session seeds the
 * earlier conversation as text ahead of the message; later turns resume with
 * {@code --conversation <id>} and restore nothing. The CLI owns the Google
 * Code Assist login, its own tool loop, and its own compaction — exactly the
 * ownership split the Claude Code route has with {@code claude -p}.
 * <p>
 * The MCP config comes from the injection the owner runs once per client
 * (Antigravity reads the shared user-wide {@code ~/.gemini/config/mcp_config.json}),
 * so no per-launch config file rides the command.
 */
final class AgyCliClient implements AutoCloseable {

    private static final long TURN_TIMEOUT_MS = 10 * 60_000L;
    private static final long MAX_STREAM_FRAME_CHARS = 1_048_576L;
    private static final long MAX_DIAGNOSTIC_CHARS = 4096L;

    static final class TurnNotStartedException extends Exception {
        TurnNotStartedException(String message) { super(message); }
    }

    static final class TurnFailedException extends Exception {
        TurnFailedException(String message) { super(message); }
    }

    private final String workingDirectory;
    private final PassthroughStreamParser parser = new PassthroughStreamParser();
    /** Memory files and skills this conversation used, re-delivered after agy compacts it. */
    private final NativeCompactionReload compactionReload;

    /** The agy conversation this client keeps; null until agy's init envelope lands. */
    private volatile String nativeSessionId;
    private volatile boolean firstMessageSent;
    private volatile boolean skipPermissions = true;
    /** KOMPILE_SUBAGENT_DEPTH of the agent this client serves; 0 for a top-level chat. */
    private volatile int subagentDepth;
    private volatile BooleanSupplier cancellationCheck = () -> false;
    private volatile Process activeProcess;
    private final Object lifecycleLock = new Object();

    AgyCliClient(String workingDirectory) {
        this.workingDirectory = workingDirectory == null || workingDirectory.isBlank()
                ? "." : workingDirectory;
        this.compactionReload = new NativeCompactionReload(Path.of(this.workingDirectory), new ObjectMapper());
    }

    /** What this conversation must get back once agy compacts it; the caller prepends it. */
    NativeCompactionReload compactionReload() {
        return compactionReload;
    }

    /**
     * Feeds the compaction reload from one stream event: a settled Kompile memory or skill
     * call (paired with the call's parameters by tool name, in order), or agy's own compaction.
     */
    void observeForReload(PassthroughStreamParser.PassthroughEvent event,
                          Map<String, ArrayDeque<String>> toolInputs) {
        if (event instanceof PassthroughStreamParser.ToolUse use) {
            toolInputs.computeIfAbsent(use.name(), name -> new ArrayDeque<>()).addLast(use.input());
        } else if (event instanceof PassthroughStreamParser.ToolComplete done) {
            ArrayDeque<String> inputs = toolInputs.get(done.name());
            String input = inputs == null ? null : inputs.pollFirst();
            if (!done.error()) compactionReload.recordToolUse(done.name(), input, done.output());
        } else if (event instanceof PassthroughStreamParser.ContextCompacted) {
            compactionReload.compacted();
        }
    }

    void setSkipPermissions(boolean skipPermissions) {
        this.skipPermissions = skipPermissions;
    }

    /**
     * Serve a Kompile subagent: each agy turn carries the depth into its environment (and so
     * into the kompile MCP server it starts), adds the sub-agent guard workspace, and stops
     * when its stream shows a sub-agent tool anyway ({@link SubagentContainment}).
     */
    void setSubagentDepth(int depth) {
        this.subagentDepth = Math.max(0, depth);
    }

    void setCancellationCheck(BooleanSupplier check) {
        this.cancellationCheck = check == null ? () -> false : check;
    }

    String nativeSessionId() {
        return nativeSessionId;
    }

    boolean firstTurnSent() {
        return firstMessageSent;
    }

    /** Forget the native conversation: the next turn starts a fresh one (seeded again). */
    void startNewSession() {
        nativeSessionId = null;
        firstMessageSent = false;
    }

    @Override
    public void close() {
        cancelActiveRun();
    }

    void cancelActiveRun() {
        Process process = activeProcess;
        if (process != null) {
            process.toHandle().descendants().forEach(ProcessHandle::destroy);
            process.destroy();
        }
    }

    /**
     * Run one agy turn. Returns the settled turn text. The conversation id stays
     * in this client; per-event observations surface through {@code onEvent}.
     * Failure kinds shape replayability: pre-turn spawn problems are replayable
     * ({@link TurnNotStartedException}); anything after process start is not
     * ({@link TurnFailedException}).
     *
     * @param model  the model id agy receives as {@code --model}, or null for its default
     * @param prompt the full turn prompt (system instructions, restored seed, user message)
     */
    String send(String model, String prompt, Consumer<PassthroughStreamParser.PassthroughEvent> onEvent)
            throws TurnNotStartedException, TurnFailedException {
        if (prompt == null || prompt.isBlank()) {
            throw new TurnFailedException("The agy turn prompt is empty");
        }
        if (firstMessageSent && (nativeSessionId == null || nativeSessionId.isBlank())) {
            throw new TurnFailedException("Missing agy conversation id for exact resume");
        }
        String binary = SubprocessAgentRunner.resolveAgentBinary("agy");
        if (binary == null) {
            throw new TurnNotStartedException("The 'agy' CLI is not installed or not on PATH;"
                    + " install Antigravity and run `kompile auth login agy` once");
        }

        List<String> managed = SubprocessAgentRunner.buildManagedCommand(
                "agy", binary, prompt, firstMessageSent, nativeSessionId,
                skipPermissions, Path.of(workingDirectory), null, model, null);
        int depth = subagentDepth;
        if (depth > 0) {
            try {
                managed = SubprocessAgentRunner.prependGlobalOptions(managed,
                        SubagentContainment.launchArguments("agy"));
            } catch (IOException e) {
                throw new TurnNotStartedException("BLOCKED: could not prepare the agy subagent guard, "
                        + "so the subagent is not launched: " + e.getMessage());
            }
        }
        AtomicReference<String> failure = new AtomicReference<>();
        AtomicReference<PassthroughStreamParser.TurnError> failedEnvelope = new AtomicReference<>();
        StringBuilder text = new StringBuilder();
        Map<String, ArrayDeque<String>> toolInputs = new HashMap<>();
        Process process = null;
        try {
            ProcessBuilder builder = new ProcessBuilder(new ArrayList<>(managed));
            builder.directory(Path.of(workingDirectory).toFile());
            builder.redirectErrorStream(false);
            builder.environment().put("GEMINI_CLI_TRUST_WORKSPACE", "true");
            if (depth > 0) {
                builder.environment().put(SubagentDelegationGuard.DEPTH_ENV, Integer.toString(depth));
            }
            synchronized (lifecycleLock) {
                if (cancellationCheck.getAsBoolean()) {
                    throw new TurnFailedException("Native run cancelled");
                }
                process = builder.start();
                activeProcess = process;
            }
            Process child = process;
            // agy's stream input would block the one-shot transport; close it.
            child.getOutputStream().close();
            StringBuilder diagnostics = new StringBuilder();

            Thread events = new Thread(() -> {
                try {
                    readBoundedLines(child.getInputStream(), line -> {
                        String delegated = depth > 0 ? SubagentContainment.violationInLine("agy", line) : null;
                        if (delegated != null) {
                            failure.compareAndSet(null, "agy " + delegated);
                            destroyTree(child);
                            return;
                        }
                        for (PassthroughStreamParser.PassthroughEvent event
                                : parser.parseAgyLineMulti(line)) {
                            if (event instanceof PassthroughStreamParser.SessionInit init) {
                                if (firstMessageSent && !Objects.equals(nativeSessionId, init.sessionId())) {
                                    throw new IllegalStateException(
                                            "agy changed the exact resumed conversation id");
                                }
                                nativeSessionId = init.sessionId();
                                continue;
                            }
                            if (event instanceof PassthroughStreamParser.TextChunk chunk) {
                                text.append(chunk.text());
                            }
                            if (event instanceof PassthroughStreamParser.TurnError turnError) {
                                failedEnvelope.set(turnError);
                            }
                            if (depth > 0 && event instanceof PassthroughStreamParser.ToolUse use) {
                                String violation = SubagentContainment.violation("agy", use.name());
                                if (violation != null) {
                                    failure.compareAndSet(null, "agy " + violation);
                                    destroyTree(child);
                                    return;
                                }
                            }
                            observeForReload(event, toolInputs);
                            onEvent.accept(event);
                        }
                    });
                } catch (Exception streamFailure) {
                    failure.compareAndSet(null, streamFailure.getMessage() == null
                            ? streamFailure.getClass().getSimpleName() : streamFailure.getMessage());
                }
            }, "agy-turn-events");
            Thread diagnosticsThread = new Thread(() -> {
                try {
                    readBoundedLines(child.getErrorStream(), line -> {
                        synchronized (diagnostics) {
                            if (diagnostics.length() < MAX_DIAGNOSTIC_CHARS) diagnostics.append(line, 0,
                                    Math.min(line.length(),
                                            (int) (MAX_DIAGNOSTIC_CHARS - diagnostics.length()))).append('\n');
                        }
                    });
                } catch (Exception streamFailure) {
                    failure.compareAndSet(null, streamFailure.getMessage() == null
                            ? streamFailure.getClass().getSimpleName() : streamFailure.getMessage());
                }
            }, "agy-turn-diagnostics");
            events.setDaemon(true);
            diagnosticsThread.setDaemon(true);
            events.start();
            diagnosticsThread.start();

            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(TURN_TIMEOUT_MS);
            while (!child.waitFor(200, TimeUnit.MILLISECONDS)) {
                if (cancellationCheck.getAsBoolean()) {
                    destroyTree(child);
                    child.waitFor(2, TimeUnit.SECONDS);
                    throw new TurnFailedException("Native run cancelled");
                }
                if (System.nanoTime() >= deadline) {
                    destroyTree(child);
                    child.waitFor(2, TimeUnit.SECONDS);
                    throw new TurnFailedException("agy did not finish within "
                            + (TURN_TIMEOUT_MS / 60_000L) + " minutes");
                }
            }
            events.join(2000);
            diagnosticsThread.join(2000);
            if (events.isAlive() || diagnosticsThread.isAlive()) {
                if (failure.get() == null) failure.set("agy's stream did not close");
            }
            if (failure.get() != null) {
                throw new TurnFailedException(failure.get());
            }
            int exit = child.exitValue();
            // A failed turn settles with a status/error envelope (e.g. the Code
            // Assist quota exhaustion) — surface that message, never the raw stream.
            PassthroughStreamParser.TurnError failedTurn = failedEnvelope.get();
            if (failedTurn != null && !failedTurn.message().isBlank()) {
                throw new TurnFailedException("agy turn failed"
                        + (failedTurn.status().isBlank() ? "" : " (" + failedTurn.status() + ")")
                        + ": " + failedTurn.message());
            }
            if (exit != 0) {
                String stderrTail = humanStderr(diagnostics);
                throw new TurnFailedException("agy failed (exit " + exit + ")"
                        + (stderrTail.isEmpty()
                                ? " with no result envelope or stderr; the turn error is recorded"
                                        + " in agy's own log (~/.gemini/antigravity-cli/log)"
                                : ": " + stderrTail));
            }
            firstMessageSent = true;
            return text.toString();
        } catch (TurnFailedException reference) {
            throw reference;
        } catch (IOException e) {
            throw new TurnNotStartedException("Could not start the agy CLI: "
                    + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new TurnFailedException("Native run cancelled");
        } finally {
            cancelActiveRun();
            synchronized (lifecycleLock) {
                if (process != null) {
                    try {
                        process.getInputStream().close();
                    } catch (IOException ignored) {
                    }
                    try {
                        process.getErrorStream().close();
                    } catch (IOException ignored) {
                    }
                }
                activeProcess = null;
            }
        }
    }

    /** Human-only stderr tail: agy may still print operational JSON to stderr. */
    private static String humanStderr(StringBuilder diagnostics) {
        StringBuilder human = new StringBuilder();
        synchronized (diagnostics) {
            for (String line : diagnostics.toString().split("\n")) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("{")
                        || trimmed.startsWith("jetski:")
                        || trimmed.startsWith("YOLO mode is enabled")) {
                    continue;
                }
                human.append(human.length() > 0 ? " " : "").append(trimmed);
                if (human.length() >= 400) {
                    break;
                }
            }
        }
        return human.length() > 400 ? human.substring(0, 400) : human.toString();
    }

    private static void destroyTree(Process process) {
        process.toHandle().descendants().forEach(ProcessHandle::destroy);
        process.destroy();
    }

    /** A native stream frame is bounded independently of the turn's answer length. */
    private static void readBoundedLines(InputStream input, Consumer<String> lines) throws IOException {
        try (java.io.Reader reader = new java.io.InputStreamReader(input, StandardCharsets.UTF_8)) {
            StringBuilder line = new StringBuilder();
            char[] buffer = new char[4096];
            int count;
            while ((count = reader.read(buffer)) != -1) {
                for (int i = 0; i < count; i++) {
                    if (buffer[i] == '\n') {
                        lines.accept(line.toString());
                        line.setLength(0);
                    } else {
                        if (line.length() >= MAX_STREAM_FRAME_CHARS) {
                            throw new IOException("agy stream frame exceeds 1 MiB");
                        }
                        line.append(buffer[i]);
                    }
                }
            }
            if (!line.isEmpty()) lines.accept(line.toString());
        }
    }
}
