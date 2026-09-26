/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.cli.main.chat.config;

import ai.kompile.cli.main.auth.oauth.OAuthProviderFlow;
import ai.kompile.utils.AnsiConstants;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Claude Code sign-in driven from Kompile's own prompts.
 *
 * <p>Claude Code has no device-code grant. Its equivalent is `claude auth login`:
 * it prints a link that opens on any device, and the sign-in page then shows a
 * code to paste back. This runs that command, shows its link, and hands it each
 * pasted code, so a machine without a browser (SSH, a server) can sign in.
 * Claude Code keeps the resulting login; Kompile stores neither the code nor the
 * login, and the outcome is always re-read from `claude auth status`.</p>
 */
public final class ClaudeCodeSignIn {
    private static volatile String binaryOverride;

    private ClaudeCodeSignIn() {
    }

    /**
     * The verified Claude Code login. When Claude Code is not signed in, run
     * `claude auth login` through {@code interaction} first: show its link, pass
     * on each pasted code, and relay what it reports. Blank input checks whether
     * a browser on this machine finished the sign-in; "cancel" or the end of
     * input stops it.
     *
     * @return the login `claude auth status` reports afterwards
     */
    public static LiveModelDiscovery.ClaudeCodeLogin ensureSignedIn(OAuthProviderFlow.Interaction interaction)
            throws InterruptedException {
        LiveModelDiscovery.ClaudeCodeLogin login = LiveModelDiscovery.claudeCodeLogin();
        return login.loggedIn() ? login : signIn(interaction);
    }

    private static LiveModelDiscovery.ClaudeCodeLogin signIn(OAuthProviderFlow.Interaction interaction)
            throws InterruptedException {
        String binary = binaryOverride != null ? binaryOverride : LiveModelDiscovery.claudeBinary();
        if (binary == null) {
            interaction.info("No Claude Code agent is registered, so its sign-in cannot start.");
            return LiveModelDiscovery.claudeCodeLogin();
        }
        interaction.info("Claude Code is not signed in on this machine. Starting its sign-in (`claude auth login`).");
        Process process;
        try {
            // stdin stays piped: each pasted code is written to it.
            process = ClaudeCliClient.withoutApiKeyEnvironment(new ProcessBuilder(binary, "auth", "login")).start();
        } catch (IOException e) {
            interaction.info("Could not start Claude Code's sign-in: " + e.getMessage());
            return LiveModelDiscovery.claudeCodeLogin();
        }
        BlockingQueue<Signal> signals = new LinkedBlockingQueue<>();
        AtomicBoolean linkSeen = new AtomicBoolean();
        Thread output = pump(process.getInputStream(), "claude-sign-in-output", line -> {
            URI link = signInLink(line);
            if (link != null && linkSeen.compareAndSet(false, true)) signals.add(new Signal(link, null));
        });
        Thread errors = pump(process.getErrorStream(), "claude-sign-in-errors", line -> {
            String message = AnsiConstants.stripAnsi(line).strip();
            if (!message.isEmpty()) signals.add(new Signal(null, message));
        });
        process.onExit().thenRun(() -> signals.add(Signal.EXITED));
        try {
            converse(process, signals, interaction);
        } finally {
            stop(process);
            joinQuietly(output);
            joinQuietly(errors);
            relayMessages(signals, interaction);
        }
        return LiveModelDiscovery.claudeCodeLogin();
    }

    private static void converse(Process process, BlockingQueue<Signal> signals,
                                 OAuthProviderFlow.Interaction interaction) throws InterruptedException {
        URI link = awaitLink(signals, interaction);
        if (link == null) {
            return;
        }
        interaction.authorizationUrl(link, "Open this link in a browser on any device and sign in to Claude:");
        interaction.info("The page then shows a code: paste it below. If a browser on this machine "
                + "finished the sign-in instead, press Enter. Type cancel to stop.");
        Writer input = new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8);
        while (process.isAlive()) {
            relayMessages(signals, interaction);
            String code = readCode(interaction);
            if (code == null) {
                interaction.info("Claude Code sign-in cancelled.");
                return;
            }
            if (code.isEmpty()) {
                if (!process.waitFor(2, TimeUnit.SECONDS)) {
                    interaction.info("Claude Code is still waiting for the sign-in. "
                            + "Paste the code from the page, or type cancel.");
                }
                continue;
            }
            try {
                input.write(code + "\n");
                input.flush();
            } catch (IOException exited) {
                return; // Claude Code already ended; `claude auth status` reports the outcome.
            }
            awaitAnswer(process, signals, interaction);
        }
    }

    /** Wait for the sign-in link, relaying anything Claude Code reports first; null when it ends or stalls. */
    private static URI awaitLink(BlockingQueue<Signal> signals, OAuthProviderFlow.Interaction interaction)
            throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(LiveModelDiscovery.PROCESS_TIMEOUT_SECONDS);
        while (true) {
            Signal signal = signals.poll(deadline - System.nanoTime(), TimeUnit.NANOSECONDS);
            if (signal == null) {
                interaction.info("Claude Code did not show a sign-in link within "
                        + LiveModelDiscovery.PROCESS_TIMEOUT_SECONDS + " seconds.");
                return null;
            }
            if (signal.exited()) {
                return null;
            }
            if (signal.link() != null) {
                return signal.link();
            }
            interaction.info(signal.message());
        }
    }

    /**
     * Wait for Claude Code's answer to a pasted code: it exits (signed in, or the
     * code was rejected) or reports a problem and waits for another code.
     */
    private static void awaitAnswer(Process process, BlockingQueue<Signal> signals,
                                    OAuthProviderFlow.Interaction interaction) throws InterruptedException {
        Signal signal = signals.poll(LiveModelDiscovery.PROCESS_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        if (signal == null) {
            interaction.info("Claude Code has not answered yet. Press Enter to check again, or type cancel.");
        } else if (signal.message() != null) {
            interaction.info(signal.message());
            // A rejected code ends the command; a malformed one leaves it waiting for another.
            process.waitFor(2, TimeUnit.SECONDS);
        }
    }

    /** The pasted code, "" to check on a browser sign-in, or null to stop (cancel or end of input). */
    private static String readCode(OAuthProviderFlow.Interaction interaction) {
        String answer;
        try {
            answer = interaction.prompt("Code:");
        } catch (IOException unreadable) {
            return null;
        }
        if (answer == null) {
            return null;
        }
        answer = answer.strip();
        return "cancel".equalsIgnoreCase(answer) ? null : answer;
    }

    /** Relay what Claude Code has reported so far, without waiting for more. */
    private static void relayMessages(BlockingQueue<Signal> signals, OAuthProviderFlow.Interaction interaction) {
        for (Signal signal = signals.poll(); signal != null; signal = signals.poll()) {
            if (signal.message() != null) {
                interaction.info(signal.message());
            }
        }
    }

    /** End the sign-in process: ask it to exit, then force it after a short grace period. */
    private static void stop(Process process) {
        if (!process.isAlive()) {
            return;
        }
        process.destroy();
        try {
            if (!process.waitFor(2, TimeUnit.SECONDS)) {
                process.destroyForcibly();
            }
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
        }
    }

    /** Give an output reader a moment to deliver Claude Code's last lines. */
    private static void joinQuietly(Thread pump) {
        try {
            pump.join(TimeUnit.SECONDS.toMillis(2));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Read one output stream of the sign-in process line by line on a daemon thread. */
    private static Thread pump(InputStream stream, String name, Consumer<String> lines) {
        Thread thread = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                for (String line = reader.readLine(); line != null; line = reader.readLine()) {
                    lines.accept(line);
                }
            } catch (IOException closed) {
                // The process ended or its stream was closed; there is nothing more to read.
            }
        }, name);
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    /**
     * The first https link in one line of Claude Code output, or null. The link
     * ends at whitespace or a control character, so a terminal hyperlink escape
     * around it is not part of it.
     */
    static URI signInLink(String line) {
        int start = line == null ? -1 : line.indexOf("https://");
        if (start < 0) {
            return null;
        }
        int end = start;
        while (end < line.length() && !Character.isWhitespace(line.charAt(end))
                && !Character.isISOControl(line.charAt(end))) {
            end++;
        }
        try {
            return new URI(line.substring(start, end));
        } catch (URISyntaxException e) {
            return null;
        }
    }

    /** Test seam: run this executable as Claude Code instead of the registered claude agent; null restores it. */
    public static void useClaudeBinary(String binary) {
        binaryOverride = binary;
    }

    /** One event from `claude auth login`: its sign-in link, a line it wrote to stderr, or its exit. */
    private record Signal(URI link, String message) {
        static final Signal EXITED = new Signal(null, null);

        boolean exited() {
            return link == null && message == null;
        }
    }
}
