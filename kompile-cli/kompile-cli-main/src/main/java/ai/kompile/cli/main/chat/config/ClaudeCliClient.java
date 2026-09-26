/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.cli.main.chat.config;

import ai.kompile.cli.common.util.NativeCliProcess;
import ai.kompile.cli.main.chat.ChatSessionContext;
import ai.kompile.cli.main.chat.mcp.McpToolInjection;
import ai.kompile.cli.main.chat.render.ProcessManager;
import ai.kompile.core.agent.AgentProvider;
import ai.kompile.core.agent.CliAgentRegistry;
import ai.kompile.utils.HashUtils;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Native Claude Code transport for the standalone Kompile Chat provider.
 *
 * <p>Follows the same provider-adapter pattern as {@link OpenCodeServeClient}:
 * one persistent native session per chat, one headless CLI turn per message.
 * Turns run {@code claude -p "Read this file and act on the prompt in the file:
 * <turn file>" --append-system-prompt-file <instructions file> --output-format
 * stream-json --verbose --include-partial-messages (--session-id|--resume) <id>}
 * with a closed stdin ({@link NativeCliProcess}) and stdout parsed line-by-line
 * through {@link ClaudeCliStreamParser}. stderr is kept separate and used only
 * for failure diagnosis, so CLI log noise can never leak into an answer.</p>
 *
 * <p><b>Instructions are a system prompt, not turn text.</b> Claude Code records
 * the system prompt once per conversation and reuses that copy on every resume
 * until the conversation is compacted, so the instructions are not re-sent with
 * each message. Instructions that change during a session are sent once, in the
 * next turn file, as an update.</p>
 *
 * <p><b>Auth belongs to Claude Code.</b> The CLI owns the login and Kompile
 * never sees it. Selection verifies it with {@code claude auth status}
 * ({@link LiveModelDiscovery#claudeCodeLogin()}); a login that expires later
 * is caught here: the failure output of a dead turn is matched against known
 * authentication signatures and raised as {@link ClaudeCliAuthenticationException}
 * with an actionable message. Turns run without ANTHROPIC_API_KEY
 * ({@link #withoutApiKeyEnvironment(ProcessBuilder)}), so the route always
 * uses the Claude Code login it was verified against.</p>
 */
final class ClaudeCliClient implements AutoCloseable {

    interface ActivityListener {
        void onToolStart(String callId, String name, String input);
        default void onToolInput(String callId, String name, String input) { }
        default void onToolOutput(String callId, String name, String output) { }
        void onToolComplete(String callId, String name, String output,
                            int exitCode, boolean error);
        void onTokenUsage(long input, long output, long cacheRead, long cacheCreation);
        default void onNotice(String text) { }
        /**
         * Live reasoning deltas from the claude stream. Forwarded to the same
         * thinking pipeline every other route uses, so the CLI's boot/file-read/
         * thinking/tool phase renders activity instead of dead silence.
         */
        default void onThinking(String text) {
        }
    }

    /**
     * The turn failed before any provider interaction began — binary missing,
     * spawn failure, or a non-zero exit with no usable answer. No provider-side
     * session history was touched, so the caller may replay the turn (the retry
     * loop discards this transport and spawns fresh).
     */
    static class TurnNotStartedException extends IllegalStateException {
        TurnNotStartedException(String message) {
            super(message);
        }

        TurnNotStartedException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * The turn failed because Claude Code rejected its credentials (subscription
     * OAuth login missing/expired, or no valid auth configured). The prompt was
     * never answered, so a retry after re-authenticating is meaningful.
     */
    static final class ClaudeCliAuthenticationException extends TurnNotStartedException {
        ClaudeCliAuthenticationException(String message) {
            super(message);
        }
    }

    /** The CLI began responding, but the stream ended or reported an error. */
    static final class TurnFailedException extends IllegalStateException {
        TurnFailedException(String message) {
            super(message);
        }
    }

    /** Substrings that identify a Claude Code authentication failure (lowercase). */
    private static final List<String> AUTH_FAILURE_SIGNATURES = List.of(
            "not logged in",
            "please run /login",
            "run /login",
            "invalid api key",
            "api key not valid",
            "unauthorized",
            "credit balance is too low",
            "no oauth token",
            "oauth token has expired",
            "failed to authenticate",
            "authentication_error",
            "claude login",
            "requires login",
            "do not have a subscription",
            "log in with your claude",
            "before logging in",
            "credit balance",
            "billing");

    /** How often a running turn checks whether it was cancelled. */
    private static final long CANCEL_POLL_MILLIS = 100;

    private final ChatSessionContext sessionContext = ChatSessionContext.current();
    private final Path workingDirectory;
    private final StringBuilder errorOutput = new StringBuilder();
    /** Test seam: when set, spawns this binary instead of the registry-resolved CLI. */
    private final String binaryOverride;

    private String sessionId;
    /** False until the first turn has created the native session. */
    private boolean sessionStarted;
    /** Digest of the instructions the native session holds; null until a turn delivers them. */
    private String deliveredInstructionsDigest;
    /** Settings file written by the one-time MCP injection; restored on close. */
    private Path injectedSettingsFile;
    private volatile Process turnProcess;
    /** Returns true once the running turn is cancelled; null when nothing can cancel it. */
    private volatile BooleanSupplier cancellationCheck;
    private volatile boolean closed;

    ClaudeCliClient(Path workingDirectory) {
        this(workingDirectory, null, null);
    }

    /** Testing seam: pins the session id so the --resume flag is assertable deterministically. */
    ClaudeCliClient(Path workingDirectory, String sessionId) {
        this(workingDirectory, sessionId, null);
    }

    /** Testing seam: full injection — pinned session id and an explicit binary path. */
    ClaudeCliClient(Path workingDirectory, String sessionId, String binaryOverride) {
        this.workingDirectory = workingDirectory.toAbsolutePath().normalize();
        this.sessionId = sessionId;
        this.binaryOverride = binaryOverride;
    }

    /**
     * Cancels the running turn once it returns true. Reading the CLI's output
     * blocks without seeing a cancel or an interrupt, so the turn polls this
     * check and stops the CLI and the tools it started.
     */
    void setCancellationCheck(BooleanSupplier check) {
        cancellationCheck = check;
    }

    /**
     * Continue a native session created by an earlier Kompile process: the next
     * turn uses {@code --resume}. If Claude Code no longer has that session, the
     * caller falls back to {@link #startNewSession()}.
     *
     * @param instructionsDigest digest of the instructions that session last received
     */
    synchronized void resumeSession(String nativeSessionId, String instructionsDigest) {
        sessionId = nativeSessionId;
        sessionStarted = true;
        deliveredInstructionsDigest = instructionsDigest;
    }

    /** Drop the current native session; the next turn creates a new one. */
    synchronized void startNewSession() {
        sessionId = null;
        sessionStarted = false;
        deliveredInstructionsDigest = null;
    }

    /** The native session later turns resume, or null until a turn has created one. */
    synchronized DirectLlmClient.ClaudeNativeSession nativeSession() {
        return sessionStarted
                ? new DirectLlmClient.ClaudeNativeSession(sessionId, deliveredInstructionsDigest)
                : null;
    }

    /**
     * Send one turn through the native Claude Code session. Kompile's
     * instructions go to Claude Code as a system prompt through
     * {@code --append-system-prompt-file}; the turn itself (the user's message
     * first, then this turn's context) goes to a second temp file, and the
     * {@code -p} argument is just "Read this file and act on the prompt in the
     * file: <path>". Prompt content never enters argv, so "argument list too
     * long" cannot happen on long conversations.
     *
     * @param model  Claude model id (e.g. sonnet); may be null to let the CLI use its default
     * @param effort Claude effort override (e.g. high, or ultracode); may be null
     * @param fastMode request Claude Code fast mode for this turn's session
     * @param systemPrompt Kompile's instructions for this session
     * @param restoredConversation earlier conversation for a new native session; empty otherwise
     * @return the final assistant text
     */
    synchronized String send(String model, String effort, boolean fastMode, String systemPrompt,
                             String userMessage, String restoredConversation,
                             Consumer<String> output,
                             ActivityListener activityListener) throws Exception {
        if (closed) {
            throw new TurnNotStartedException("Claude CLI chat transport is closed");
        }
        if (sessionId == null) {
            sessionId = UUID.randomUUID().toString();
        }
        synchronized (errorOutput) {
            errorOutput.setLength(0);
        }
        injectKompileToolsOnce();

        String instructions = systemPrompt == null ? "" : systemPrompt.strip();
        String instructionsDigest = HashUtils.sha256Hex(instructions);
        // An existing session keeps the system prompt Claude Code recorded when it
        // began, so instructions that changed since then ride in this turn once.
        String updatedInstructions = sessionStarted && !instructions.isEmpty()
                && !instructionsDigest.equals(deliveredInstructionsDigest) ? instructions : "";
        Path instructionsFile = null;
        Path promptFile = null;
        try {
            try {
                if (!instructions.isEmpty()) {
                    instructionsFile = writeTurnFile("kompile-claude-instructions-", ".md",
                            "[Kompile Chat system instructions]\n" + instructions
                                    + "\n[End Kompile Chat system instructions]\n");
                }
                promptFile = writeTurnFile("kompile-claude-prompt-", ".txt",
                        composeTurn(userMessage, updatedInstructions, restoredConversation));
            } catch (IOException e) {
                throw new TurnNotStartedException(
                        "Could not write the Claude CLI turn files: " + e.getMessage(), e);
            }
            String text = runTurn(buildCommand(model, effort, fastMode, instructionsFile, promptFile),
                    output, activityListener);
            deliveredInstructionsDigest = instructionsDigest;
            return text;
        } finally {
            deleteQuietly(promptFile);
            deleteQuietly(instructionsFile);
        }
    }

    /** Run one CLI turn and classify how it ended. */
    private String runTurn(List<String> command, Consumer<String> output,
                           ActivityListener activityListener) throws Exception {
        Process process;
        try {
            // Streaming note: headless `claude -p --output-format stream-json
            // --include-partial-messages` flushes each delta to the pipe as it
            // arrives (verified live: init at T+0.1s, text deltas every ~30ms
            // through a raw pipe), so no PTY is needed here — unlike the TUI
            // passthrough lanes, which DO require script(1) to defeat full
            // stdout buffering. A PTY would merge stderr into stdout and mask
            // the real exit code, so it is deliberately NOT used.
            ProcessBuilder builder = withoutApiKeyEnvironment(
                    NativeCliProcess.processBuilder(command, workingDirectory));
            process = builder.start();
        } catch (IOException e) {
            // Binary missing or unspawnable: the prompt never reached a provider.
            throw new TurnNotStartedException(
                    "Could not start the '" + binaryName() + "' CLI: " + e.getMessage(), e);
        }
        turnProcess = process;
        Thread errorDrain = drainStderr(process);
        AtomicBoolean cancelled = new AtomicBoolean();
        watchForCancel(process, cancelled);
        try {
            TurnOutcome outcome = consumeTurn(process, output, activityListener);
            errorDrain.join(2_000);
            // Any prose the stdout parse skipped (malformed lines, banner text)
            // lands in the diagnostic buffer so failure classification can use
            // it — auth signatures sometimes surface on stdout.
            if (outcome.ignoredProse.length() > 0) {
                synchronized (errorOutput) {
                    if (errorOutput.length() < 8_000) {
                        errorOutput.append(outcome.ignoredProse);
                    }
                }
            }
            reap(process);
            if (!outcome.refusal.isBlank() && outcome.turnText.isBlank()
                    && !outcome.providerSideEffectsObserved) {
                // Claude Code refused the turn before any model request (an unknown
                // --resume session, for one): no provider saw it and no session changed.
                throwTurnFailure(outcome.exitCode, outcome.refusal);
            }
            // The CLI acknowledged the session; later turns use --resume.
            sessionStarted = true;
            if (cancelled.get()) {
                // Stopped mid-turn: tools may already have run, so this is not
                // reported as a turn that never started and cannot be replayed.
                // The CLI has exited, so nothing above observed an interrupt.
                if (Thread.currentThread().isInterrupted()) {
                    throw new InterruptedException("Claude CLI turn interrupted");
                }
                throw new CancellationException("Claude CLI turn cancelled");
            }

            if (!outcome.failure.isBlank()) {
                throw new TurnFailedException(outcome.failure + diagnosticSuffix());
            }
            if (outcome.exitCode != 0) {
                if (outcome.turnText.isBlank() && !outcome.providerSideEffectsObserved) {
                    throwTurnFailure(outcome.exitCode, "");
                }
                throw new TurnFailedException("Claude CLI turn failed after partial output (exit "
                        + outcome.exitCode + ")" + diagnosticSuffix());
            }
            if (!outcome.completed) {
                if (outcome.turnText.isBlank() && !outcome.providerSideEffectsObserved) {
                    throw new TurnNotStartedException(
                            "Claude CLI returned no assistant text" + diagnosticSuffix());
                }
                throw new TurnFailedException("Claude CLI stream ended before its terminal result event"
                        + diagnosticSuffix());
            }
            if (outcome.turnText.isBlank() && outcome.providerSideEffectsObserved) {
                if (activityListener != null) {
                    activityListener.onNotice("Claude completed tool activity without a final text response.");
                }
                return "";
            }
            if (outcome.turnText.isBlank()) {
                throw new TurnNotStartedException(
                        "Claude CLI returned no assistant text" + diagnosticSuffix());
            }
            return outcome.turnText;
        } catch (InterruptedException e) {
            process.destroy();
            Thread.currentThread().interrupt();
            throw e;
        } finally {
            turnProcess = null;
        }
    }

    private static void deleteQuietly(Path file) {
        if (file == null) return;
        try {
            Files.deleteIfExists(file);
        } catch (IOException ignored) {
        }
    }

    /**
     * Write one turn input to a private temp file (owner-only on POSIX). The
     * caller deletes it when the turn ends.
     */
    private static Path writeTurnFile(String prefix, String suffix, String content)
            throws IOException {
        Path file = Files.createTempFile(prefix, suffix);
        try {
            Files.writeString(file, content, StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException e) {
            deleteQuietly(file);
            throw e;
        }
        return file;
    }

    /** Stop an in-flight CLI turn and the tools it started. */
    void cancel() {
        ProcessManager.killTree(turnProcess);
    }

    /**
     * Register the kompile MCP tools in the sub-claude session, exactly like the
     * passthrough lanes do for claude (project {@code .mcp.json}, hooks
     * pre-configured BEFORE launch because Claude Code watches that file via
     * inotify). Runs once per chat; {@link #close()} restores the original file.
     */
    private void injectKompileToolsOnce() {
        if (injectedSettingsFile != null) return;
        try {
            McpToolInjection.ensureHooksPreConfigured(workingDirectory);
            String sseUrl = null; // claude reads portable project .mcp.json entries on stdio
            injectedSettingsFile = McpToolInjection.injectTools(workingDirectory, "claude", sseUrl);
        } catch (Exception e) {
            // Injection is an enhancement, not a gate: chat works without the
            // kompile tools, exactly as passthrough degrades gracefully.
            injectedSettingsFile = null;
        }
    }

    @Override
    public synchronized void close() {
        closed = true;
        cancel();
        Path injected = injectedSettingsFile;
        injectedSettingsFile = null;
        if (injected != null) {
            try {
                McpToolInjection.removeTools(injected);
            } catch (Exception ignored) {
                // Best-effort restore must never block shutdown.
            }
        }
    }

    // ── Turn plumbing ─────────────────────────────────────────────────────

    private TurnOutcome consumeTurn(Process process,
                                    Consumer<String> output,
                                    ActivityListener activityListener) throws IOException {
        TurnOutcome outcome = new TurnOutcome();
        StringBuilder streamed = new StringBuilder();
        ClaudeCliStreamParser parser = new ClaudeCliStreamParser();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                // stream-json always emits JSON objects. Non-JSON prose (auth
                // failures, CLI warnings) must go to diagnostics, NEVER through
                // the parser's lenient fallback where it would become
                // "assistant text" and mask the failure.
                String trimmedLine = line.trim();
                if (!trimmedLine.startsWith("{")) {
                    recordProse(outcome, line);
                    continue;
                }
                List<ClaudeCliStreamParser.Event> events;
                try {
                    events = parser.parse(line);
                } catch (RuntimeException ignored) {
                    recordProse(outcome, line);
                    continue;
                }
                if (events.isEmpty()) {
                    // JSON-shaped but unrecognized — keep for diagnostics.
                    recordProse(outcome, line);
                    continue;
                }
                for (ClaudeCliStreamParser.Event event : events) {
                    if (event instanceof ClaudeCliStreamParser.SessionInit init) {
                        // The CLI echoes its native session id on the init event;
                        // adopting it keeps --resume consistent with sessions the
                        // user can inspect under ~/.claude/projects.
                        if (init.sessionId() != null && !init.sessionId().isBlank()) {
                            sessionId = init.sessionId();
                        }
                    } else if (event instanceof ClaudeCliStreamParser.Thinking thinking) {
                        if (!thinking.text().isEmpty() && activityListener != null) {
                            activityListener.onThinking(thinking.text());
                        }
                    } else if (event instanceof ClaudeCliStreamParser.Text text) {
                        if (!text.text().isEmpty()) {
                            streamed.append(text.text());
                            if (output != null) output.accept(text.text());
                        }
                    } else if (event instanceof ClaudeCliStreamParser.ToolStart start) {
                        outcome.providerSideEffectsObserved = true;
                        // Claude ends prose before a tool call without a newline. Close
                        // that line first so the renderer paints it above the tool block
                        // and the next message's text is not glued onto it.
                        if (!streamed.isEmpty() && streamed.charAt(streamed.length() - 1) != '\n') {
                            streamed.append('\n');
                            if (output != null) output.accept("\n");
                        }
                        if (activityListener != null) {
                            activityListener.onToolStart(start.callId(), start.name(), start.input());
                        }
                    } else if (event instanceof ClaudeCliStreamParser.ToolInput input) {
                        if (activityListener != null) {
                            activityListener.onToolInput(input.callId(), input.name(), input.input());
                        }
                    } else if (event instanceof ClaudeCliStreamParser.ToolOutput toolOutput) {
                        outcome.providerSideEffectsObserved = true;
                        if (activityListener != null) {
                            activityListener.onToolOutput(toolOutput.callId(), toolOutput.name(),
                                    toolOutput.output());
                        }
                    } else if (event instanceof ClaudeCliStreamParser.ToolComplete complete) {
                        outcome.providerSideEffectsObserved = true;
                        if (activityListener != null) {
                            activityListener.onToolComplete(complete.callId(), complete.name(),
                                    complete.output(), complete.error() ? 1 : 0, complete.error());
                        }
                    } else if (event instanceof ClaudeCliStreamParser.Notice notice) {
                        if (activityListener != null) activityListener.onNotice(notice.text());
                    } else if (event instanceof ClaudeCliStreamParser.TurnComplete turn) {
                        outcome.completed = true;
                        if (turn.error()) {
                            outcome.failure = turn.errorMessage().isBlank()
                                    ? "Claude reported an error for this turn"
                                    : "Claude reported an error: " + turn.errorMessage();
                            if (!turn.started()) {
                                outcome.refusal = turn.errorMessage().isBlank()
                                        ? "Claude Code did not run the turn" : turn.errorMessage();
                            }
                        }
                        if (streamed.length() == 0 && !turn.result().isBlank()) {
                            streamed.append(turn.result());
                            if (output != null) output.accept(turn.result());
                        }
                        if (activityListener != null && (turn.inputTokens() > 0
                                || turn.outputTokens() > 0 || turn.cacheReadTokens() > 0
                                || turn.cacheCreationTokens() > 0)) {
                            activityListener.onTokenUsage(turn.inputTokens(), turn.outputTokens(),
                                    turn.cacheReadTokens(), turn.cacheCreationTokens());
                        }
                    }
                }
            }
        }
        outcome.turnText = streamed.toString();
        try {
            outcome.exitCode = process.waitFor();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            outcome.exitCode = -1;
        }
        return outcome;
    }

    /** Non-JSON prose line (auth failures, CLI warnings) kept for diagnostics. */
    private static void recordProse(TurnOutcome outcome, String line) {
        String value = line == null ? "" : line.trim();
        if (!value.isBlank()) {
            outcome.ignoredProse.append(value).append('\n');
        }
    }

    private Thread drainStderr(Process process) {
        Thread thread = new Thread(sessionContext.wrap(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                    process.getErrorStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    synchronized (errorOutput) {
                        if (errorOutput.length() < 8_000) {
                            errorOutput.append(line).append('\n');
                        }
                    }
                }
            } catch (IOException ignored) {
                // Process shutdown closes the stream.
            }
        }), "kompile-claude-cli-stderr");
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    /**
     * Stop the turn's process tree when the turn is cancelled or its thread is
     * interrupted, and record that in {@code cancelled}. The turn thread blocks
     * reading the CLI's output, which sees neither, so without this the CLI
     * keeps running tools until it finishes on its own.
     */
    private void watchForCancel(Process process, AtomicBoolean cancelled) {
        BooleanSupplier check = cancellationCheck;
        Thread owner = Thread.currentThread();
        Thread thread = new Thread(sessionContext.wrap(() -> {
            try {
                while (!owner.isInterrupted() && !cancelRequested(check)) {
                    if (process.waitFor(CANCEL_POLL_MILLIS, TimeUnit.MILLISECONDS)) {
                        return;
                    }
                }
                if (process.isAlive()) {
                    cancelled.set(true);
                    ProcessManager.killTree(process);
                }
            } catch (InterruptedException ignored) {
                // Daemon thread: only JVM shutdown interrupts it.
            }
        }), "kompile-claude-cli-cancel");
        thread.setDaemon(true);
        thread.start();
    }

    private static boolean cancelRequested(BooleanSupplier check) {
        try {
            return check != null && check.getAsBoolean();
        } catch (RuntimeException e) {
            return false; // A failing check must not end the turn.
        }
    }

    private void reap(Process process) {
        if (process.isAlive()) {
            process.destroy();
            try {
                if (!process.waitFor(5, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                process.destroyForcibly();
            }
        }
    }

    /**
     * The Claude Code route runs on the user's Claude Code login. ANTHROPIC_API_KEY
     * belongs to Kompile's Anthropic API-key route, and claude gives it precedence
     * over the claude.ai login, so it never reaches a Claude Code process.
     */
    static ProcessBuilder withoutApiKeyEnvironment(ProcessBuilder builder) {
        builder.environment().remove("ANTHROPIC_API_KEY");
        return builder;
    }

    /**
     * Throw for a turn that never reached a provider: an authentication failure
     * when the diagnostics say so, otherwise a replayable not-started failure.
     *
     * @param reported the error Claude Code reported in its result event, if any
     */
    private void throwTurnFailure(int exitCode, String reported) {
        String diagnostic = diagnosticText();
        if (!reported.isBlank() && !diagnostic.contains(reported.strip())) {
            diagnostic = reported.strip() + "\n" + diagnostic;
        }
        String lower = diagnostic.toLowerCase(Locale.ROOT);
        for (String signature : AUTH_FAILURE_SIGNATURES) {
            if (lower.contains(signature)) {
                throw new ClaudeCliAuthenticationException(
                        "Claude Code rejected its login (exit " + exitCode + ", "
                                + trimForError(diagnostic) + "). Run `claude auth login` in a terminal "
                                + "and retry, or switch to the Anthropic API-key route with /model.");
            }
        }
        throw new TurnNotStartedException("Claude CLI turn failed (exit " + exitCode + ")"
                + (diagnostic.isBlank() ? "" : ": " + trimForError(diagnostic)));
    }

    private String diagnosticText() {
        synchronized (errorOutput) {
            return errorOutput.toString();
        }
    }

    private String diagnosticSuffix() {
        String diagnostic = diagnosticText();
        return diagnostic.isBlank() ? "" : ": " + trimForError(diagnostic);
    }

    /**
     * Build the turn command. The instructions and the turn live in temp files
     * and argv carries only their paths, so its size never grows with the
     * conversation.
     */
    private List<String> buildCommand(String model, String effort, boolean fastMode,
                                      Path instructionsFile, Path promptFile) {
        List<String> cmd = new ArrayList<>();
        cmd.add(binaryName());
        cmd.add("--dangerously-skip-permissions");
        cmd.add("-p");
        cmd.add("Read this file and act on the prompt in the file: "
                + promptFile.toAbsolutePath());
        if (instructionsFile != null) {
            // Passed on every launch. Claude Code records the system prompt once
            // per conversation and reuses that copy on resume; where recording is
            // not enabled it renders this file on each launch instead.
            cmd.add("--append-system-prompt-file");
            cmd.add(instructionsFile.toAbsolutePath().toString());
        }
        cmd.add("--output-format");
        cmd.add("stream-json");
        cmd.add("--verbose");
        cmd.add("--include-partial-messages");
        // `--session-id` creates the native session on the first turn; after that
        // the same id is resumed every turn (`claude --resume <id>` requires an
        // existing session). `claude /resume` output confirms both forms accept
        // the UUID id echoed in the stream's `system/init` event.
        cmd.add(sessionStarted ? "--resume" : "--session-id");
        cmd.add(sessionId);
        if (model != null && !model.isBlank()) {
            cmd.add("--model");
            cmd.add(model.trim());
        }
        if (effort != null && !effort.isBlank()) {
            cmd.add("--effort");
            cmd.add(effort.trim());
        }
        if (fastMode) {
            // Headless sessions honor fast mode only when launched with it in
            // --settings (Claude Code v2.1.205+); it applies to this session only
            // and never writes the user's settings file.
            cmd.add("--settings");
            cmd.add("{\"fastMode\":true}");
        }
        return cmd;
    }

    private String binaryName() {
        if (binaryOverride != null && !binaryOverride.isBlank()) {
            return binaryOverride;
        }
        AgentProvider definition = claudeDefinition();
        return definition != null && definition.getCommand() != null && !definition.getCommand().isBlank()
                ? definition.getCommand() : "claude";
    }

    private static AgentProvider claudeDefinition() {
        return CliAgentRegistry.loadAll().stream()
                .filter(agent -> "claude".equalsIgnoreCase(agent.getCommand())
                        || "claude".equalsIgnoreCase(agent.getName()))
                .findFirst()
                .orElse(null);
    }

    /**
     * The turn file: the user's message first, then this turn's Kompile context,
     * changed instructions, and restored conversation, each labeled so none of
     * them reads as a new request. A bare message is written as-is.
     */
    static String composeTurn(String userMessage, String updatedInstructions,
                              String restoredConversation) {
        String message = userMessage == null ? "" : userMessage;
        String userText = DirectLlmClient.withoutTurnEnvelopes(message);
        String turnContext = "";
        if (message.endsWith(userText)) {
            turnContext = message.substring(0, message.length() - userText.length()).strip();
        } else {
            userText = message;
        }
        boolean hasUpdate = updatedInstructions != null && !updatedInstructions.isBlank();
        boolean hasRestored = restoredConversation != null && !restoredConversation.isBlank();
        if (turnContext.isEmpty() && !hasUpdate && !hasRestored) {
            return message;
        }
        StringBuilder turn = new StringBuilder("[User message]\n")
                .append(userText.strip())
                .append("\n[End user message]\n");
        if (!turnContext.isEmpty()) {
            turn.append("\n[Kompile context for this turn: it applies to the user message above"
                            + " and is not a new request]\n")
                    .append(turnContext)
                    .append("\n[End Kompile context]\n");
        }
        if (hasUpdate) {
            turn.append("\n[Updated Kompile Chat system instructions: these replace the Kompile"
                            + " Chat system instructions in your system prompt]\n")
                    .append(updatedInstructions.strip())
                    .append("\n[End updated Kompile Chat system instructions]\n");
        }
        if (hasRestored) {
            turn.append('\n').append(restoredConversation.strip()).append('\n');
        }
        return turn.toString();
    }

    private static String trimForError(String value) {
        if (value == null || value.isBlank()) {
            return "no diagnostic output";
        }
        String trimmed = value.trim();
        return trimmed.length() > 400 ? trimmed.substring(0, 400) + "..." : trimmed;
    }

    private static final class TurnOutcome {
        String turnText = "";
        boolean completed;
        int exitCode;
        boolean providerSideEffectsObserved;
        String failure = "";
        /** Why Claude Code refused the turn before any model request; blank when it ran. */
        String refusal = "";
        /** Non-JSON prose lines seen on stdout (auth failures, CLI warnings). */
        final StringBuilder ignoredProse = new StringBuilder();
    }
}
