/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.cli.main.chat.enforcer;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.function.Consumer;

/** Routes enforcer diagnostics to the active TUI's alert row, not JLine's input rows. */
public final class EnforcerDiagnostics {
    private static final Deque<Registration> ALERT_SINKS = new ArrayDeque<>();

    private EnforcerDiagnostics() { }

    /** The latest session owns alerts; cleanup is idempotent and safe out of order. */
    public static Runnable installAlertSink(Consumer<String> sink) {
        if (sink == null) return () -> { };
        Registration registration = new Registration(sink);
        synchronized (ALERT_SINKS) {
            ALERT_SINKS.addLast(registration);
        }
        return () -> {
            synchronized (ALERT_SINKS) {
                ALERT_SINKS.remove(registration);
            }
        };
    }

    public static void alert(String message) {
        if (message == null || message.isBlank()) return;
        Registration registration;
        synchronized (ALERT_SINKS) {
            registration = ALERT_SINKS.peekLast();
        }
        if (registration != null) {
            try {
                registration.sink.accept(message);
                return;
            } catch (RuntimeException ignored) {
                // A closing UI must not alter enforcement or hide the diagnostic.
            }
        }
        System.err.println(message);
    }

    private static final class Registration {
        private final Consumer<String> sink;

        private Registration(Consumer<String> sink) {
            this.sink = sink;
        }
    }
}
