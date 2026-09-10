/* Copyright 2025 Kompile Inc. Licensed under the Apache License, Version 2.0. */
package ai.kompile.knowledgegraph.generation;

import ai.kompile.core.crawl.graph.DistributedGraphRuntimeContext;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

/** Request-scoped routing of fact-sheet operations into a hidden physical generation. */
public final class GraphGenerationContext {

    private static final ThreadLocal<State> CURRENT = new ThreadLocal<>();

    private record State(GraphGeneration.Ref generation, String ownerJobId,
                         DistributedGraphRuntimeContext remoteRoute) { }

    private GraphGenerationContext() { }

    public static Optional<GraphGeneration.Ref> current() {
        return Optional.ofNullable(CURRENT.get()).map(State::generation);
    }

    public static Optional<String> ownerJobId() {
        return Optional.ofNullable(CURRENT.get()).map(State::ownerJobId);
    }

    public static Optional<DistributedGraphRuntimeContext> remoteRoute() {
        return Optional.ofNullable(CURRENT.get()).map(State::remoteRoute);
    }

    public static Scope open(GraphGeneration.Ref generation) {
        return open(generation, "unowned");
    }

    public static Scope open(GraphGeneration.Ref generation, String ownerJobId) {
        if (generation == null) throw new IllegalArgumentException("generation is required");
        State previous = CURRENT.get();
        CURRENT.set(new State(generation,
                ownerJobId == null || ownerJobId.isBlank() ? "unowned" : ownerJobId,
                previous != null ? previous.remoteRoute() : null));
        return new Scope(previous);
    }

    public static Scope openRemote(GraphGeneration.Ref generation, String ownerJobId,
                                   DistributedGraphRuntimeContext route) {
        if (route == null) throw new IllegalArgumentException("remote route is required");
        State previous = CURRENT.get();
        CURRENT.set(new State(generation,
                ownerJobId == null || ownerJobId.isBlank() ? "unowned" : ownerJobId, route));
        return new Scope(previous);
    }

    public static Runnable wrap(GraphGeneration.Ref generation, String ownerJobId, Runnable task) {
        State current = CURRENT.get();
        return wrapState(new State(generation,
                ownerJobId == null || ownerJobId.isBlank() ? "unowned" : ownerJobId,
                current != null ? current.remoteRoute() : null), task);
    }

    public static <T> Callable<T> wrap(
            GraphGeneration.Ref generation, String ownerJobId, Callable<T> task) {
        State current = CURRENT.get();
        return wrapState(new State(generation,
                ownerJobId == null || ownerJobId.isBlank() ? "unowned" : ownerJobId,
                current != null ? current.remoteRoute() : null), task);
    }

    /** Capture the current generation explicitly for a task submitted to an existing pool. */
    public static Runnable wrapCurrent(Runnable task) {
        State captured = CURRENT.get();
        return captured == null ? task : wrapState(captured, task);
    }

    /** Capture the current generation explicitly for a task submitted to an existing pool. */
    public static <T> Callable<T> wrapCurrent(Callable<T> task) {
        State captured = CURRENT.get();
        return captured == null ? task : wrapState(captured, task);
    }

    public static Future<?> submit(ExecutorService executor, Runnable task) {
        return executor.submit(wrapCurrent(task));
    }

    public static <T> Future<T> submit(ExecutorService executor, Callable<T> task) {
        return executor.submit(wrapCurrent(task));
    }

    public static String resolve(long factSheetId, String logicalGraphId) {
        State current = CURRENT.get();
        return current != null && current.generation() != null
                && current.generation().factSheetId() == factSheetId
                && current.generation().logicalGraphId().equals(logicalGraphId)
                ? current.generation().physicalGraphId() : logicalGraphId;
    }

    private static Runnable wrapState(State state, Runnable task) {
        return () -> {
            State previous = CURRENT.get();
            CURRENT.set(state);
            try {
                task.run();
            } finally {
                if (previous == null) CURRENT.remove();
                else CURRENT.set(previous);
            }
        };
    }

    private static <T> Callable<T> wrapState(State state, Callable<T> task) {
        return () -> {
            State previous = CURRENT.get();
            CURRENT.set(state);
            try {
                return task.call();
            } finally {
                if (previous == null) CURRENT.remove();
                else CURRENT.set(previous);
            }
        };
    }

    public static final class Scope implements AutoCloseable {
        private final State previous;
        private boolean closed;

        private Scope(State previous) { this.previous = previous; }

        @Override
        public void close() {
            if (closed) return;
            if (previous == null) CURRENT.remove();
            else CURRENT.set(previous);
            closed = true;
        }
    }
}
