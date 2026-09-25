/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.cli.main.chat.config;

import ai.kompile.cli.common.util.NativeCliProcess;
import ai.kompile.cli.main.chat.ChatSessionContext;
import ai.kompile.cli.main.chat.PassthroughStreamParser;
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
        void onToolComplete(String callId, String name, String output,
                            int exitCode, boolean error);
        void onTokenUsage(long input, long output, long cacheRead, long cacheCreation);
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
     * Send one turn through the native Claude Code session.
     *
     * @param model  Claude model id (e.g. sonnet); may be null to let the CLI use its default
     * @param effort Claude effort override (e.g. high); may be null
     * @return the final assistant text
     */
    synchronized String send(String model, String effort, String systemPrompt,
                             String userMessage, Consumer<String> output,
                             ActivityListener activityListener) throws Exception {
        if (closed) {
            throw new TurnNotStartedException("Claude CLI chat transport is closed");
        }
        if (sessionId == null) {
            sessionId = UUID.randomUUID().toString();
        }

        Process process;
        try {
            process = NativeCliProcess.processBuilder(
                            buildCommand(model, effort, systemPrompt, userMessage), workingDirectory)
                    .start();
        } catch (IOException e) {
            // Binary missing or unspawnable: the prompt never reached a provider.
            throw new TurnNotStartedException(
                    "Could not start the '" + binaryName() + "' CLI: " + e.getMessage(), e);
        }
        turnProcess = process;
        Thread errorDrain = drainStderr(process);
        try {
            TurnOutcome outcome = consumeTurn(process, output, activityListener);
            errorDrain.join(2_000);
            reap(process);
            // The CLI acknowledged the session; later turns use --resume.
            sessionStarted = true;

            if (outcome.exitCode != 0 && outcome.turnText.isBlank()) {
                throwTurnFailure(outcome.exitCode);
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

    /** Best-effort cancellation of an in-flight CLI turn. */
    void cancel() {
        Process process = turnProcess;
        if (process != null && process.isAlive()) {
            process.destroy();
        }
    }

    @Override
    public synchronized void close() {
        closed = true;
        cancel();
    }

    // ── Turn plumbing ─────────────────────────────────────────────────────

    private TurnOutcome consumeTurn(Process process,
                                    Consumer<String> output,
                                    ActivityListener activityListener) throws IOException {
        TurnOutcome outcome = new TurnOutcome();
        StringBuilder streamed = new StringBuilder();
        PassthroughStreamParser parser = new PassthroughStreamParser();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                List<PassthroughStreamParser.PassthroughEvent> events;
                try {
                    events = parser.parseClaudeLineMulti(line);
                } catch (RuntimeException ignored) {
                    continue;
                }
                for (PassthroughStreamParser.PassthroughEvent event : events) {
                    if (event instanceof PassthroughStreamParser.SessionInit init) {
                        // The CLI echoes its native session id on the init event;
                        // adopting it keeps --resume consistent with sessions the
                        // user can inspect under ~/.claude/projects.
                        if (init.sessionId() != null && !init.sessionId().isBlank()) {
                            sessionId = init.sessionId();
                        }
                    } else if (event instanceof PassthroughStreamParser.TextChunk chunk) {
                        if (!chunk.text().isEmpty()) {
                            streamed.append(chunk.text());
                            if (output != null) output.accept(chunk.text());
                        }
                    } else if (event instanceof PassthroughStreamParser.ToolUse use) {
                        if (activityListener != null) {
                            // The claude stream-json ToolUse block carries no call id;
                            // the tool name doubles as the correlation id.
                            activityListener.onToolStart(use.name(), use.name(), use.input());
                        }
                    } else if (event instanceof PassthroughStreamParser.ToolComplete complete) {
                        if (activityListener != null) {
                            activityListener.onToolComplete(complete.name(), complete.name(),
                                    complete.output(), complete.exitCode(), complete.error());
                        }
                    } else if (event instanceof PassthroughStreamParser.TokenUsage usage) {
                        if (activityListener != null) {
                            activityListener.onTokenUsage(usage.inputTokens(), usage.outputTokens(),
                                    usage.cacheReadTokens(), usage.cacheCreationTokens());
                        }
                    } else if (event instanceof PassthroughStreamParser.TurnComplete turn) {
                        // The terminal `result` event is the turn's authoritative end.
                        outcome.completed = true;
                        if (activityListener != null
                                && (turn.inputTokens() > 0 || turn.outputTokens() > 0)) {
                            activityListener.onTokenUsage(turn.inputTokens(), turn.outputTokens(),
                                    turn.cacheReadTokens(), turn.cacheCreationTokens());
                        }
                    }
                    // ThinkingChunk is intentionally not forwarded: reasoning is a
                    // liveness signal only in this lane, never transcript text.
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

    private List<String> buildCommand(String model, String effort, String systemPrompt, String userMessage) {
        List<String> cmd = new ArrayList<>();
        cmd.add(binaryName());
        cmd.add("-p");
        cmd.add(composePrompt(systemPrompt, userMessage));
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
    }
}
