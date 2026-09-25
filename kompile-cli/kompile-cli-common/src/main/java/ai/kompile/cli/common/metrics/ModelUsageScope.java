/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.cli.common.metrics;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Request-scoped sink for provider-reported model usage (Task 7).
 *
 * <p>A scope is opened for the duration of ONE tool invocation's execution. Any
 * provider usage arriving at a verified model-completion boundary while the scope is
 * open on the SAME thread is attributed to that invocation as a
 * {@link ModelUsageEvent} with {@code PROVIDER_REPORTED} reporting. No scope open →
 * capture is a no-op (uninstrumented paths stay unavailable, never zero). No global
 * session-counter subtraction, no proportional allocation, no synthesis from text.</p>
 *
 * <p>Scopes NEST (subagent/model-backed guard inner calls attribute to the innermost
 * open invocation — a true child execution with its own invocation id). Background
 * executions open their own scope on their worker thread under the SAME invocation id
 * (the ack already linked that id), so events never attach to the wrong invocation.</p>
 */
public final class ModelUsageScope {

    private static final ThreadLocal<Deque<OpenScope>> STACK = ThreadLocal.withInitial(ArrayDeque::new);
    private static final AtomicLong EVENT_SEQ = new AtomicLong(0);

    private record OpenScope(String invocationId, List<ModelUsageEvent> events) {}

    private ModelUsageScope() {
    }

    /** Open a usage scope for an invocation on the current thread. */
    public static void open(String invocationId) {
        if (invocationId == null || invocationId.isBlank()) {
            return;
        }
        STACK.get().push(new OpenScope(invocationId, new ArrayList<>()));
    }

    /**
     * Hook for verified model-completion boundaries: attributes provider usage to the
     * innermost open scope on this thread, or silently no-ops when none is open.
     * Never throws; accounting must not disturb the caller.
     */
    public static void capture(String provider, String modelId,
                               long inputTokens, long outputTokens,
                               long cacheReadTokens, long cacheWriteTokens) {
        try {
            Deque<OpenScope> stack = STACK.get();
            if (stack.isEmpty()) {
                return; // uninstrumented path: unavailable, not zero
            }
            OpenScope top = stack.peek();
            long seq = EVENT_SEQ.incrementAndGet();
            top.events().add(new ModelUsageEvent(
                    top.invocationId() + "-evt-" + seq,
                    top.invocationId(),
                    provider,
                    modelId,
                    "generation",
                    inputTokens > 0 ? inputTokens : null,
                    outputTokens > 0 ? outputTokens : null,
                    null,                       // reasoning split unavailable at this boundary
                    cacheReadTokens > 0 ? cacheReadTokens : null,
                    cacheWriteTokens > 0 ? cacheWriteTokens : null,
                    null,                       // inclusion semantics unknown here — honest nulls
                    null,
                    false,                      // boundary delivers per-request totals (not cumulative)
                    inputTokens + outputTokens > 0 ? inputTokens + outputTokens : null,
                    null, null,                 // latency/timestamp not carried by this boundary
                    null,                       // provider request id unavailable at this boundary
                    ModelUsageEvent.UsageReporting.PROVIDER_REPORTED));
        } catch (Exception ignored) {
            // never disturb the model call path
        }
    }

    /**
     * Close the innermost scope and return its events (for attachment to a
     * {@link ToolCallUsage}). Returns an empty list when no scope is open. Idempotent:
     * a closed scope is gone, later closes pop nothing extra beyond balance.
     */
    public static List<ModelUsageEvent> closeAndCollect() {
        Deque<OpenScope> stack = STACK.get();
        OpenScope scope = stack.poll();
        return scope == null ? List.of() : List.copyOf(scope.events());
    }

    /**
     * Close ALL scopes on this thread (exception/cancellation safety): drops any open
     * scope so a pooled thread can never attribute a later invocation's usage to a
     * stale scope. Returns events of the innermost scope if it was still open.
     */
    public static List<ModelUsageEvent> clearThread() {
        Deque<OpenScope> stack = STACK.get();
        List<ModelUsageEvent> innermost = List.of();
        OpenScope scope = stack.poll();
        if (scope != null) {
            innermost = List.copyOf(scope.events());
        }
        stack.clear();
        STACK.remove();
        return innermost;
    }

    /** Depth of open scopes on this thread (test/diagnostic). */
    public static int depth() {
        return STACK.get().size();
    }

    /** Test support: serialize an event list into a ToolCallUsage-compatible list. */
    public static List<ModelUsageEvent> copy(List<ModelUsageEvent> events) {
        return events == null ? List.of() : List.copyOf(events);
    }
}
