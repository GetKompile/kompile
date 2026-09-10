/*
 * Copyright 2026 Kompile Inc.
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.cli.main.chat;

import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.function.Consumer;

/**
 * Construction/registration-time ownership for asynchronous chat work. Captures UI
 * and only an explicitly bound log scope, never the process's legacy log stack.
 * No thread inheritance, focus lookup, lifetime extension or task cancellation.
 */
public final class ChatSessionContext {
    private final ChatUiSession ui;
    private final TranscriptLogScope log;

    private ChatSessionContext(ChatUiSession ui, TranscriptLogScope log) {
        this.ui = ui;
        this.log = log;
    }

    public static ChatSessionContext current() {
        return new ChatSessionContext(ChatUiSession.current(), TranscriptLogScope.currentExplicitScope());
    }

    /** Closed owners remain captured; they never fall through to a worker's sibling. */
    public Runnable wrap(Runnable task) {
        Objects.requireNonNull(task, "task");
        return ui.capture((Runnable) () -> {
            try (var ignored = TranscriptLogScope.bindCaptured(log)) {
                task.run();
            }
        });
    }

    public <T> Callable<T> wrapCallable(Callable<T> task) {
        Objects.requireNonNull(task, "task");
        return ui.capture((Callable<T>) () -> {
            try (var ignored = TranscriptLogScope.bindCaptured(log)) {
                return task.call();
            }
        });
    }

    public <T> Consumer<T> wrapConsumer(Consumer<T> callback) {
        Objects.requireNonNull(callback, "callback");
        return ui.captureConsumer(value -> {
            try (var ignored = TranscriptLogScope.bindCaptured(log)) {
                callback.accept(value);
            }
        });
    }
}
