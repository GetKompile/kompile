/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.cli.main.codeindex;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.function.Consumer;

/** Routes asynchronous code-index diagnostics away from JLine-owned rows. */
public final class CodeIndexDiagnostics {

    private static final Object SINK_LOCK = new Object();
    private static final Deque<Registration> ALERT_SINKS = new ArrayDeque<>();

    private CodeIndexDiagnostics() {
    }

    /** Install a session-local alert sink and return an identity-safe cleanup. */
    public static Runnable installAlertSink(Consumer<String> sink) {
        if (sink == null) return () -> { };
        Registration registration = new Registration(sink);
        synchronized (SINK_LOCK) {
            ALERT_SINKS.addLast(registration);
        }
        return () -> {
            synchronized (SINK_LOCK) {
                ALERT_SINKS.remove(registration);
            }
        };
    }

    public static void alert(String message) {
        if (message == null || message.isBlank()) return;
        Consumer<String> sink;
        synchronized (SINK_LOCK) {
            Registration registration = ALERT_SINKS.peekLast();
            sink = registration == null ? null : registration.sink();
        }
        if (sink != null) {
            try {
                sink.accept(message);
                return;
            } catch (RuntimeException ignored) {
                // A diagnostic must never break indexing because a TUI is closing.
            }
        }
        System.err.println(message);
    }

    public static boolean hasAlertSink() {
        synchronized (SINK_LOCK) {
            return !ALERT_SINKS.isEmpty();
        }
    }

    public static boolean isAlertLine(String line) {
        if (line == null) return false;
        String normalized = line.strip().toLowerCase();
        return normalized.startsWith("[code-index]")
                || normalized.startsWith("[lsp] incremental reindex")
                || normalized.startsWith("warning:")
                || normalized.startsWith("error indexing")
                || normalized.startsWith("error writing")
                || normalized.contains("connectivity pass failed");
    }

    private record Registration(Consumer<String> sink) {
    }
}
