/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.cli.main.auth.oauth;

import org.jline.terminal.Attributes;
import org.jline.terminal.Terminal;
import org.jline.utils.NonBlockingReader;

import java.io.IOException;

/**
 * Hands a JLine terminal to an interactive child process and takes it back.
 *
 * <p>JLine reads stdin one character per request and is idle once a
 * {@code readLine} returns, so the child can read the inherited terminal
 * directly. (JLine's system terminal cannot pause; {@code Terminal.pause()} is a
 * no-op there.) What the child leaves behind is the problem: Claude Code switches
 * the terminal to raw mode and an early exit keeps that mode, and keys typed as
 * it exits would otherwise answer the next wizard prompt.</p>
 */
public final class TerminalHandoff {
    private static final long DRAIN_TIMEOUT_MILLIS = 25L;

    private TerminalHandoff() {
    }

    /** Run {@code task}, then restore the terminal's modes and discard unread input. */
    public static <T> T run(Terminal terminal, OAuthProviderFlow.TerminalTask<T> task)
            throws IOException, InterruptedException {
        if (terminal == null) {
            return task.run();
        }
        System.out.flush();
        terminal.writer().flush();
        Attributes saved = terminal.getAttributes();
        try {
            return task.run();
        } finally {
            try {
                terminal.setAttributes(saved);
            } catch (RuntimeException ignored) {
                // A closed or dumb terminal has no modes to restore.
            }
            discardPendingInput(terminal);
        }
    }

    private static void discardPendingInput(Terminal terminal) {
        try {
            NonBlockingReader reader = terminal.reader();
            while (reader.read(DRAIN_TIMEOUT_MILLIS) >= 0) {
                // Leftover keys belong to the child that just exited.
            }
        } catch (IOException | RuntimeException ignored) {
            // Nothing readable is left to discard.
        }
    }
}
