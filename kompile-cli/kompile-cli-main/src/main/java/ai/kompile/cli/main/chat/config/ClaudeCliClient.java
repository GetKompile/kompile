/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.cli.main.chat.config;

import ai.kompile.cli.common.util.NativeCliProcess;
import ai.kompile.cli.main.chat.ChatSessionContext;
import ai.kompile.cli.main.chat.mcp.McpToolInjection;
import ai.kompile.core.agent.AgentProvider;
import ai.kompile.core.agent.CliAgentRegistry;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Native Claude Code transport for the standalone Kompile Chat provider.
 *
 * <p>Follows the same provider-adapter pattern as {@link OpenCodeServeClient}:
 * one persistent native session per chat, one headless CLI turn per message.
 * Turns run {@code claude -p --output-format stream-json --verbose
 * --include-partial-messages [--resume <id>] "<prompt>"} with a closed stdin
 * ({@link NativeCliProcess}) and stdout parsed line-by-line through
 * {@link PassthroughStreamParser#parseClaudeLineMulti(String)} — the identical
 * parser the managed passthrough lane has streamed for years. stderr is kept
 * separate and used only for failure diagnosis, so CLI log noise can never leak
 * into an answer.</p>
 *
 * <p><b>Auth is verified at runtime, not at selection time.</b> Claude Code has
 * no scriptable direct OAuth; the CLI owns subscription (OAuth) credentials and
 * Kompile never sees them. Because the wizard lets the user proceed on the
 * native route without pre-verifying, this transport inspects the failure
 * output of a dead turn for known authentication/login signatures and raises
 * {@link ClaudeCliAuthenticationException} with an actionable message when it
 * matches — so a missing/expired subscription login surfaces as a clear
 * warning instead of an opaque failure.</p>
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

    private final ChatSessionContext sessionContext = ChatSessionContext.current();
    private final Path workingDirectory;
    private final StringBuilder errorOutput = new StringBuilder();
    /** Test seam: when set, spawns this binary instead of the registry-resolved CLI. */
    private final String binaryOverride;

    private String sessionId;
    /** False until the first turn has created the native session. */
    private boolean sessionStarted;
    /** Settings file written by the one-time MCP injection; restored on close. */
    private java.nio.file.Path injectedSettingsFile;
    private Process turnProcess;
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
     * Send one turn through the native Claude Code session. The full prompt
     * (system instructions + user message) is written to a temp file, and the
     * `-p` argument is just the short instruction "Read this file and act on
     * the prompt in the file: <path>" — so prompt content NEVER enters argv
     * and "argument list too long" cannot happen on long conversations.
     *
     * @param model  Claude model id (e.g. sonnet); may be null to let the CLI use its default
     * @param effort Claude effort override (e.g. high, or ultracode); may be null
     * @param fastMode request Claude Code fast mode for this turn's session
     * @return the final assistant text
     */
    synchronized String send(String model, String effort, boolean fastMode, String systemPrompt,
                             String userMessage, Consumer<String> output,
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

        String prompt = composePrompt(systemPrompt, userMessage);
        java.nio.file.Path promptFile = writePromptFile(prompt);

        Process process;
        try {
            // The prompt is passed BY FILE: it is written to a temp file and the
            // CLI is invoked with the file path so it reads the prompt from disk
            // — no argv bloat ("argument list too long" on long chats) and no
            // pipes. This matches the remote-CLI prompt-passing convention used
            // elsewhere in Kompile.
            //
            // Streaming note: headless `claude -p --output-format stream-json
            // --include-partial-messages` flushes each delta to the pipe as it
            // arrives (verified live: init at T+0.1s, text deltas every ~30ms
            // through a raw pipe), so no PTY is needed here — unlike the TUI
            // passthrough lanes, which DO require script(1) to defeat full
            // stdout buffering. A PTY would merge stderr into stdout and mask
            // the real exit code, so it is deliberately NOT used.
            ProcessBuilder builder = NativeCliProcess.processBuilder(
                    buildCommand(model, effort, fastMode, promptFile), workingDirectory);
            process = builder.start();
        } catch (IOException e) {
            deleteQuietly(promptFile);
            // Binary missing or unspawnable: the prompt never reached a provider.
            throw new TurnNotStartedException(
                    "Could not start the '" + binaryName() + "' CLI: " + e.getMessage(), e);
        }
        turnProcess = process;
        Thread errorDrain = drainStderr(process);
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
            // The CLI acknowledged the session; later turns use --resume.
            sessionStarted = true;

            if (!outcome.failure.isBlank()) {
                throw new TurnFailedException(outcome.failure + diagnosticSuffix());
            }
            if (outcome.exitCode != 0) {
                if (outcome.turnText.isBlank() && !outcome.providerSideEffectsObserved) {
                    throwTurnFailure(outcome.exitCode);
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
            deleteQuietly(promptFile);
        }
    }

    private static void deleteQuietly(java.nio.file.Path file) {
        if (file == null) return;
        try {
            java.nio.file.Files.deleteIfExists(file);
        } catch (IOException ignored) {
        }
    }

    /**
     * Persist the turn prompt to a temp file next to the working directory so
     * it can be streamed via stdin without touching argv. The file is deleted
     * when the turn ends (normally or abnormally).
     */
    private java.nio.file.Path writePromptFile(String prompt) throws IOException {
        java.nio.file.Path file = java.nio.file.Files.createTempFile(
                "kompile-claude-prompt-", ".txt");
        java.nio.file.Files.writeString(file, prompt, java.nio.charset.StandardCharsets.UTF_8);
        return file;
    }

    /** Best-effort cancellation of an in-flight CLI turn. */
    void cancel() {
        Process process = turnProcess;
        if (process != null && process.isAlive()) {
            process.destroy();
        }
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
        java.nio.file.Path injected = injectedSettingsFile;
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

    private void throwTurnFailure(int exitCode) {
        String diagnostic = diagnosticText();
        String lower = diagnostic.toLowerCase(Locale.ROOT);
        for (String signature : AUTH_FAILURE_SIGNATURES) {
            if (lower.contains(signature)) {
                throw new ClaudeCliAuthenticationException(
                        "Claude Code rejected its credentials (exit " + exitCode + ", "
                                + trimForError(diagnostic) + "). Log in with `claude /login` "
                                + "(subscription) or configure ANTHROPIC_API_KEY, then retry.");
            }
        }
        throw new TurnNotStartedException("Claude CLI turn failed (exit " + exitCode + ")"
                + diagnosticSuffix());
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
     * Build the turn command. The full prompt is written to a temp file; the
     * -p argument is just the short instruction telling claude to read and act
     * on that file — so prompt content NEVER enters argv and "argument list
     * too long" cannot happen on long conversations.
     */
    private List<String> buildCommand(String model, String effort, boolean fastMode,
                                      java.nio.file.Path promptFile) {
        List<String> cmd = new ArrayList<>();
        cmd.add(binaryName());
        cmd.add("--dangerously-skip-permissions");
        cmd.add("-p");
        // Short argv instruction pointing claude at the prompt file. The file
        // itself carries the actual prompt content (system instructions + user
        // message), so argv stays tiny regardless of conversation length.
        cmd.add("Read this file and act on the prompt in the file: "
                + promptFile.toAbsolutePath());
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

    private static String composePrompt(String systemPrompt, String userMessage) {
        if (systemPrompt == null || systemPrompt.isBlank()) {
            return userMessage == null ? "" : userMessage;
        }
        return "[Kompile Chat system instructions]\n"
                + systemPrompt.trim()
                + "\n[End Kompile Chat system instructions]\n\n"
                + (userMessage == null ? "" : userMessage);
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
        /** Non-JSON prose lines seen on stdout (auth failures, CLI warnings). */
        final StringBuilder ignoredProse = new StringBuilder();
    }
}
