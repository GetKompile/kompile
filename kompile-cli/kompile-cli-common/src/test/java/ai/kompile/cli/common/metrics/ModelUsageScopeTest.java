/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.cli.common.metrics;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Task 7 acceptance: request-scoped model-execution attribution semantics. */
class ModelUsageScopeTest {

    @Test
    @DisplayName("scope open → capture attributes to the invocation with provider/model identity")
    void attributionBasic() {
        ModelUsageScope.open("inv-1");
        try {
            ModelUsageScope.capture("openai", "gpt-5.6", 100, 50, 20, 0);
            List<ModelUsageEvent> events = ModelUsageScope.closeAndCollect();
            assertEquals(1, events.size());
            ModelUsageEvent event = events.get(0);
            assertEquals("inv-1", event.invocationId());
            assertEquals("openai", event.provider());
            assertEquals("gpt-5.6", event.modelId());
            assertEquals(100L, event.inputTokens());
            assertEquals(50L, event.outputTokens());
            assertEquals(20L, event.cacheReadTokens());
            assertEquals(ModelUsageEvent.UsageReporting.PROVIDER_REPORTED, event.reporting());
        } finally {
            ModelUsageScope.clearThread();
        }
    }

    @Test
    @DisplayName("no scope → capture is a no-op (uninstrumented path unavailable, never zero)")
    void uninstrumentedNoop() {
        ModelUsageScope.capture("anthropic", "m", 500, 200, 0, 0);
        assertTrue(ModelUsageScope.closeAndCollect().isEmpty(),
                "usage without an open scope is not captured anywhere");
    }

    @Test
    @DisplayName("nested scopes: inner model call attributes to inner invocation (true child execution)")
    void nestedScopes() {
        ModelUsageScope.open("inv-parent");
        ModelUsageScope.open("inv-child");
        try {
            ModelUsageScope.capture("p", "m-guard", 10, 5, 0, 0);   // model-backed guard, child scope
            ModelUsageScope.capture("p", "m-main", 100, 40, 0, 0);  // main call, child scope
            List<ModelUsageEvent> childEvents = ModelUsageScope.closeAndCollect();
            assertEquals(2, childEvents.size());
            childEvents.forEach(e -> assertEquals("inv-child", e.invocationId()));

            ModelUsageScope.capture("p", "m-parent", 200, 80, 0, 0);
            List<ModelUsageEvent> parentEvents = ModelUsageScope.closeAndCollect();
            assertEquals(1, parentEvents.size());
            assertEquals("inv-parent", parentEvents.get(0).invocationId());
        } finally {
            ModelUsageScope.clearThread();
        }
    }

    @Test
    @DisplayName("cancellation without final usage: dropped scope contributes nothing; no leak to next call")
    void cancelledScopeDropped() {
        ModelUsageScope.open("inv-cancelled");
        ModelUsageScope.capture("p", "m", 30, 15, 0, 0);
        // execution cancelled: scope discarded via clearThread (pool safety)
        ModelUsageScope.clearThread();

        // next invocation on the pooled thread must not inherit the stale scope
        ModelUsageScope.open("inv-next");
        try {
            ModelUsageScope.capture("p", "m", 7, 3, 0, 0);
            List<ModelUsageEvent> events = ModelUsageScope.closeAndCollect();
            assertEquals(1, events.size());
            assertEquals("inv-next", events.get(0).invocationId());
            assertEquals(7L, events.get(0).inputTokens(),
                    "only the new invocation's usage — cancelled events never leak");
        } finally {
            ModelUsageScope.clearThread();
        }
        assertEquals(0, ModelUsageScope.depth(), "balanced scopes leave depth 0");
    }

    @Test
    @DisplayName("separate threads never cross-attribute")
    void threadIsolation() throws Exception {
        ModelUsageScope.open("inv-main");
        try {
            final List<ModelUsageEvent> leaked = new java.util.concurrent.CopyOnWriteArrayList<>();
            Thread other = new Thread(() -> {
                // other thread has no scope
                ModelUsageScope.capture("p", "m", 999, 999, 0, 0);
                leaked.addAll(ModelUsageScope.closeAndCollect());
            });
            other.start();
            other.join();
            assertTrue(leaked.isEmpty(), "no cross-thread attribution");

            // main thread still holds its own scope: capture and collect
            ModelUsageScope.capture("p", "m", 100, 40, 0, 0);
            List<ModelUsageEvent> own = ModelUsageScope.closeAndCollect();
            assertEquals(1, own.size());
            assertEquals(100L, own.get(0).inputTokens());
        } finally {
            ModelUsageScope.clearThread();
        }
    }

    @Test
    @DisplayName("zero/absent usage is preserved as nulls, not fabricated")
    void absentVsZero() {
        ModelUsageScope.open("inv-z");
        ModelUsageScope.capture("p", "m", 0, 0, 0, 0);
        List<ModelUsageEvent> events = ModelUsageScope.closeAndCollect();
        assertEquals(1, events.size(), "boundary fired, event exists");
        assertNull(events.get(0).inputTokens());
        assertNull(events.get(0).outputTokens());
        assertNull(events.get(0).totalTokens(), "no synthetic total from zero inputs");
    }

    @Test
    @DisplayName("events attach to ToolCallUsage and fold in the report as unique executions")
    void integrationWithUsageAndReport() {
        ModelUsageScope.open("inv-r");
        ModelUsageScope.capture("openai", "gpt-5.6", 100, 50, 10, 0);
        List<ModelUsageEvent> events = ModelUsageScope.closeAndCollect();

        ToolCallUsage usage = new ToolCallUsage("inv-r", "sess-r", "bash", "bash",
                1L, 2L, 1L,
                ToolCallUsage.ExecutionOutcome.EXECUTED,
                ToolCallUsage.ResponseDisposition.DELIVERED, false,
                TokenMeasurement.measured(0, "json", PayloadTokenCounter.ARGS_METHOD, "est", "1"),
                TokenMeasurement.measured(25, "utf8-text", PayloadTokenCounter.TEXT_METHOD, "est", "1"),
                null, events, false, null);

        assertEquals(1, usage.modelExecutions().size());
        // payload ledger untouched by model events
        assertEquals(25L, usage.payloadMeasurement().tokens());
        // model ledger sums uniquely
        assertEquals(150L, usage.modelExecutions().get(0).totalTokens());
    }
}
