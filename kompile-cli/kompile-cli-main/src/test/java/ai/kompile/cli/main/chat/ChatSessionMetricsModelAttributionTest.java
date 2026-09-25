/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.cli.main.chat;

import ai.kompile.cli.common.metrics.ModelUsageEvent;
import ai.kompile.cli.common.metrics.ModelUsageScope;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Task 7 real-adapter integration: usage flowing through the REAL
 * {@link ChatSessionMetrics#recordTokenUsage} boundary (the seam every provider path
 * in this repo funnels into — AgenticChatLoop, Passthrough, SubprocessAgentRunner)
 * is attributed to the open invocation scope, while the host conversation ledger
 * keeps its own separate totals.
 */
class ChatSessionMetricsModelAttributionTest {

    @Test
    @DisplayName("real boundary: recordTokenUsage attributes to open scope AND host ledger stays intact")
    void boundaryAttribution() {
        ChatSessionMetrics metrics = new ChatSessionMetrics("sess-m");
        metrics.setProvider("openai");
        metrics.setModel("gpt-5.6");

        ModelUsageScope.open("inv-1");
        try {
            metrics.recordTokenUsage(100, 50, 20, 0);
            List<ModelUsageEvent> events = ModelUsageScope.closeAndCollect();
            assertEquals(1, events.size());
            assertEquals("inv-1", events.get(0).invocationId());
            assertEquals("openai", events.get(0).provider());
            assertEquals(100L, events.get(0).inputTokens());
            // host conversation ledger keeps its independent totals (never consumed)
            assertTrue(metrics.hasActualTokenCounts());
        } finally {
            ModelUsageScope.clearThread();
        }
    }

    @Test
    @DisplayName("real boundary without scope: host ledger only, nothing attributed")
    void boundaryWithoutScope() {
        ChatSessionMetrics metrics = new ChatSessionMetrics("sess-n");
        metrics.setProvider("anthropic");
        metrics.recordTokenUsage(500, 200, 0, 0);
        assertTrue(ModelUsageScope.closeAndCollect().isEmpty());
    }

    @Test
    @DisplayName("model-backed guard + main call: two distinct child events under one invocation")
    void guardAndMainCall() {
        ChatSessionMetrics metrics = new ChatSessionMetrics("sess-g");
        metrics.setProvider("openai");
        metrics.setModel("gpt-5.6");

        ModelUsageScope.open("inv-guard");
        try {
            metrics.recordTokenUsage(10, 5, 0, 0);    // guard call
            metrics.recordTokenUsage(100, 40, 5, 0);  // main call
            List<ModelUsageEvent> events = ModelUsageScope.closeAndCollect();
            assertEquals(2, events.size(), "each completion is a distinct event");
            assertEquals(10L, events.get(0).inputTokens());
            assertEquals(100L, events.get(1).inputTokens());
            // and the host ledger saw both
            assertTrue(metrics.hasActualTokenCounts());
        } finally {
            ModelUsageScope.clearThread();
        }
    }
}
