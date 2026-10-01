/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.cli.main.chat.mcp;

import ai.kompile.cli.main.chat.ChatUiSession;
import ai.kompile.cli.main.chat.tools.BackgroundProcessManager;
import ai.kompile.utils.AnsiConstants;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Routes MCP tool-bridge log lines (tool injection, hook setup, bundle discovery,
 * settings restore) to the owning chat's process browser. Raw stderr is echoed by
 * the transcript log tee, so those lines otherwise draw into the middle of the TUI
 * transcript. A line goes to the newest sink registered by the caller's
 * {@link ChatUiSession}; an unbound caller is the legacy single-chat owner, never
 * the last bound session. Without a matching sink (native passthrough, the stdio
 * server, top-level commands) the line keeps its stderr route. Lines are passed on
 * as written, terminal styling included.
 */
public final class McpDiagnostics {
    private static final Deque<Registration> SINKS = new ArrayDeque<>();

    private McpDiagnostics() { }

    /** Cleanup is idempotent and safe out of order. */
    public static Runnable installSink(ChatUiSession owner, Consumer<String> sink) {
        if (owner == null || sink == null) return () -> { };
        Registration registration = new Registration(owner, sink);
        synchronized (SINKS) {
            SINKS.addLast(registration);
        }
        return () -> {
            synchronized (SINKS) {
                SINKS.remove(registration);
            }
        };
    }

    /**
     * Keep the owner's lines in one virtual {@link BackgroundProcessManager.ProcessKind#MCP}
     * entry of its process browser, registered on the first line and again after it is
     * stopped. The entry stores plain text; {@code echo} also keeps the styled stderr
     * line while no browser is on screen (headless runs, before or after the TUI). A
     * line the manager refuses (closed, or its log unwritable) keeps the stderr route.
     */
    public static Runnable installProcessLog(ChatUiSession owner, BackgroundProcessManager processes,
                                             BooleanSupplier echo) {
        if (processes == null || echo == null) return () -> { };
        return installSink(owner, new ProcessLog(processes, echo));
    }

    public static void log(String message) {
        if (message == null || message.isBlank()) return;
        ChatUiSession owner = ChatUiSession.current();
        Registration registration = null;
        synchronized (SINKS) {
            Iterator<Registration> newestFirst = SINKS.descendingIterator();
            while (newestFirst.hasNext()) {
                Registration candidate = newestFirst.next();
                if (candidate.owner == owner) {
                    registration = candidate;
                    break;
                }
            }
        }
        if (registration != null) {
            try {
                registration.sink.accept(message);
                return;
            } catch (RuntimeException ignored) {
                // A closing chat must not hide the diagnostic.
            }
        }
        System.err.println(message);
        System.err.flush();
    }

    private static final class Registration {
        private final ChatUiSession owner;
        private final Consumer<String> sink;

        private Registration(ChatUiSession owner, Consumer<String> sink) {
            this.owner = owner;
            this.sink = sink;
        }
    }

    private static final class ProcessLog implements Consumer<String> {
        private final BackgroundProcessManager processes;
        private final BooleanSupplier echo;
        private String processId; // guarded by this

        private ProcessLog(BackgroundProcessManager processes, BooleanSupplier echo) {
            this.processes = processes;
            this.echo = echo;
        }

        @Override
        public void accept(String line) {
            synchronized (this) {
                BackgroundProcessManager.ProcessEntry entry =
                        processId == null ? null : processes.get(processId);
                if (entry == null || !entry.isRunning()) {
                    processId = processes.registerVirtual(
                            BackgroundProcessManager.ProcessKind.MCP, "mcp",
                            "MCP tool bridge log", Map.of()).getId();
                }
                if (!processes.appendVirtualOutput(processId, AnsiConstants.stripAnsi(line))) {
                    // log() falls back to stderr.
                    throw new IllegalStateException("MCP log " + processId + " refused a line");
                }
            }
            if (echo.getAsBoolean()) {
                System.err.println(line);
                System.err.flush();
            }
        }
    }
}
