/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.kompile.app.services.agent;

import ai.kompile.app.web.dto.AgentChatRequest.ChatHistoryEntry;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The chat-window history compactor: trigger math against the lane budget, verbatim
 * tail preservation, the leading summary exchange, and the deterministic digest
 * fallback when the LLM summarizer fails.
 */
class ChatHistoryCompactorTest {

    private final ChatHistoryCompactor compactor = new ChatHistoryCompactor();

    private static ChatContextBudgetService.ContextBudget budget(int contextWindow, int inputBudget) {
        return new ChatContextBudgetService.ContextBudget(
                "test-agent", "test-model", contextWindow, 1_024, inputBudget, "test");
    }

    private static List<ChatHistoryEntry> historyOfTokens(int approxTokens) {
        List<ChatHistoryEntry> history = new ArrayList<>();
        int totalChars = approxTokens * 4;
        int perEntry = Math.max(200, totalChars / 10);
        int written = 0;
        boolean user = true;
        int turn = 0;
        while (written < totalChars) {
            int size = Math.min(perEntry, totalChars - written);
            history.add(new ChatHistoryEntry(user ? "user" : "assistant",
                    "turn-" + (turn++) + " " + "x".repeat(size)));
            user = !user;
            written += size;
        }
        return history;
    }

    @Test
    void underThresholdIsUntouched() {
        List<ChatHistoryEntry> history = historyOfTokens(500);
        assertFalse(compactor.needsCompaction(history, budget(16_384, 10_000), 0));
        ChatHistoryCompactor.Result result =
                compactor.compact(history, budget(16_384, 10_000), null, false, null);
        assertFalse(result.compacted());
        assertEquals(history, result.history());
    }

    @Test
    void upcomingPromptTokensCountTowardTheTrigger() {
        List<ChatHistoryEntry> history = historyOfTokens(7_000);
        assertFalse(compactor.needsCompaction(history, budget(16_384, 10_000), 0));
        assertTrue(compactor.needsCompaction(history, budget(16_384, 10_000), 2_500));
    }

    @Test
    void upcomingPromptIsIncludedInAutomaticCompactionDecision() {
        List<ChatHistoryEntry> history = historyOfTokens(7_000);
        ChatHistoryCompactor.Result result = compactor.compact(
                history, budget(16_384, 10_000), null, 2_500, false, null);

        assertTrue(result.compacted());
        assertTrue(result.tokensAfter() < result.tokensBefore());
    }

    @Test
    void compactionSummarizesHeadAndPreservesTail() throws Exception {
        List<ChatHistoryEntry> history = historyOfTokens(12_000);
        String lastContent = history.get(history.size() - 1).getContent();

        AtomicInteger summarizerCalls = new AtomicInteger();
        ChatHistoryCompactor.Result result = compactor.compact(
                history, budget(16_384, 10_000), null, false,
                (transcript, focus) -> {
                    summarizerCalls.incrementAndGet();
                    assertTrue(transcript.contains("User:"), "transcript must be role-labeled");
                    return "the user and assistant discussed twelve thousand tokens of things";
                });

        assertTrue(result.compacted());
        assertEquals(1, summarizerCalls.get());
        assertTrue(result.tokensAfter() < result.tokensBefore());
        assertFalse(result.usedFallback());

        List<ChatHistoryEntry> compacted = result.history();
        assertTrue(compacted.get(0).getContent().startsWith(ChatHistoryCompactor.SUMMARY_MARKER),
                "compacted history must lead with the summary marker");
        assertEquals("user", compacted.get(0).getRole());
        assertEquals("assistant", compacted.get(1).getRole());
        assertEquals(lastContent, compacted.get(compacted.size() - 1).getContent(),
                "the most recent message survives verbatim");
    }

    @Test
    void summarizerFailureFallsBackToDigest() {
        List<ChatHistoryEntry> history = historyOfTokens(12_000);
        ChatHistoryCompactor.Result result = compactor.compact(
                history, budget(16_384, 10_000), null, false,
                (transcript, focus) -> { throw new IOException("model unavailable"); });

        assertTrue(result.compacted());
        assertTrue(result.usedFallback());
        assertNotNull(result.summary());
        assertTrue(result.tokensAfter() < result.tokensBefore());
    }

    @Test
    void forceCompactsEvenUnderThreshold() {
        List<ChatHistoryEntry> history = historyOfTokens(3_000);
        ChatHistoryCompactor.Result result = compactor.compact(
                history, budget(200_000, 150_000), "keep the API decisions", true,
                (transcript, focus) -> {
                    assertEquals("keep the API decisions", focus);
                    return "summary honoring the focus";
                });
        assertTrue(result.compacted());
        assertEquals("summary honoring the focus", result.summary());
    }

    @Test
    void tinyHistoryHasNothingToCompact() {
        List<ChatHistoryEntry> history = List.of(
                new ChatHistoryEntry("user", "hi"),
                new ChatHistoryEntry("assistant", "hello"));
        ChatHistoryCompactor.Result result = compactor.compact(
                history, budget(4_096, 2_048), null, true, (t, f) -> "unused");
        assertFalse(result.compacted(), "a history that fits in the tail entirely stays as-is");
    }
}
