/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.kompile.cli.main.chat.render;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Model-aware compaction budgets: thresholds must scale with the active model's real
 * context window. The historical bug: fixed 20K-buffer/40K-preserve constants meant a
 * 4K local GGUF was permanently past the trigger line while nothing could ever be
 * pruned (the preserve span exceeded the whole window).
 */
class CompactionServiceModelAwareTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private static List<CompactionService.ConversationEntry> entriesOfTokens(int approxTokens) {
        // chars/4 heuristic → approxTokens*4 chars split over a few entries
        List<CompactionService.ConversationEntry> entries = new ArrayList<>();
        int totalChars = approxTokens * 4;
        int perEntry = Math.max(400, totalChars / 8);
        int written = 0;
        boolean user = true;
        while (written < totalChars) {
            int size = Math.min(perEntry, totalChars - written);
            String content = "x".repeat(size);
            entries.add(user
                    ? CompactionService.ConversationEntry.user(content)
                    : CompactionService.ConversationEntry.assistant(content));
            user = !user;
            written += size;
        }
        return entries;
    }

    @Test
    void smallWindowDoesNotPermanentlyTrigger() {
        CompactionService service = new CompactionService(mapper, 4_096);
        // A short conversation (~500 tokens) in a 4K window must NOT need compaction.
        assertFalse(service.needsCompaction(entriesOfTokens(500)));
        // Near the window it must trigger.
        assertTrue(service.needsCompaction(entriesOfTokens(3_900)));
    }

    @Test
    void buffersScaleProportionally() {
        CompactionService small = new CompactionService(mapper, 4_096);
        assertTrue(small.compactionBuffer() < 4_096,
                "buffer must be smaller than the window itself");
        assertTrue(small.preserveRecentTokens() < 4_096,
                "preserve span must be smaller than the window itself");

        CompactionService large = new CompactionService(mapper, 200_000);
        assertEquals(30_000, large.compactionBuffer(),
                "the default 85% ceiling must scale with large windows");
        assertEquals(40_000, large.preserveRecentTokens(),
                "recent-history preservation remains bounded");
    }

    @Test
    void setMaxTokensRetunesThresholds() {
        CompactionService service = new CompactionService(mapper); // default 128K
        List<CompactionService.ConversationEntry> conversation = entriesOfTokens(10_000);
        assertFalse(service.needsCompaction(conversation));

        service.setMaxTokens(8_192); // model switch to a small local GGUF
        assertTrue(service.needsCompaction(conversation));
        assertEquals(8_192, service.getMaxTokens());
    }

    @Test
    void reportedInputTokensTriggerEvenWhenEstimateIsLow() {
        CompactionService service = new CompactionService(mapper, 8_192);
        List<CompactionService.ConversationEntry> tiny = entriesOfTokens(100);
        assertFalse(service.needsCompaction(tiny, 0));
        // Provider reported the real prompt (system prompt + tools) was near the window.
        assertTrue(service.needsCompaction(tiny, 8_000));
    }

    @Test
    void outputCapacityReservesEnoughContextForTheResponse() {
        CompactionService service = new CompactionService(mapper, 400_000);
        service.configure(true, 0.90d, 128_000, 0);

        assertEquals(136_192, service.effectiveReserveTokens(),
                "reserve includes the provider output limit plus an input-estimation safety margin");
        assertEquals(263_808, service.triggerTokens(),
                "output reserve must win over a later ratio trigger");
        assertTrue(service.needsCompaction(263_808));
    }

    @Test
    void millionTokenWindowDoesNotCompactAtFortyTwoThousandTokens() {
        CompactionService service = new CompactionService(mapper, 1_050_000);
        service.configure(true, 0.85d, 128_000, 0);

        assertEquals(136_192, service.effectiveReserveTokens());
        assertEquals(892_500, service.triggerTokens());
        assertFalse(service.needsCompaction(42_000));
        assertFalse(service.needsCompaction(892_499));
        assertTrue(service.needsCompaction(892_500));
    }

    @Test
    void oversizedCatalogOutputLimitCannotConsumeTheInputWindow() {
        CompactionService service = new CompactionService(mapper, 1_050_000);
        for (int outputLimit : new int[]{1_050_000, Integer.MAX_VALUE}) {
            service.configure(true, 0.85d, outputLimit, 0);
            assertEquals(outputLimit, service.getMaxOutputTokens(),
                    "retain the advertised capability; only automatic headroom is capped");
            assertEquals(533_192, service.effectiveReserveTokens());
            assertEquals(516_808, service.triggerTokens());
            assertFalse(service.needsCompaction(42_000));
        }
    }

    @Test
    void automaticReserveAlsoLeavesRoomInSmallWindows() {
        CompactionService service = new CompactionService(mapper, 4_096);
        service.configure(true, 0.85d, 4_096, 0);
        assertEquals(2_304, service.effectiveReserveTokens());
        assertEquals(1_792, service.triggerTokens());
        assertFalse(service.needsCompaction(1_024));

        service.setMaxTokens(1_024);
        assertEquals(0, service.effectiveReserveTokens());
        assertEquals(1_024, service.triggerTokens());
    }

    @Test
    void explicitReserveIsNotSubjectToTheAutomaticCap() {
        CompactionService service = new CompactionService(mapper, 1_050_000);
        service.configure(true, 0.85d, 1_050_000, 900_000);
        assertEquals(900_000, service.effectiveReserveTokens());
        assertEquals(150_000, service.triggerTokens());
    }

    @Test
    void policyCanBeDisabledAndExplicitlyRetuned() {
        CompactionService service = new CompactionService(mapper, 100_000);
        service.configure(false, 0.75d, 8_192, 10_000);
        assertEquals(75_000, service.triggerTokens());
        assertFalse(service.needsCompaction(99_000));

        service.configure(true, 0.75d, 8_192, 30_000);
        assertEquals(70_000, service.triggerTokens());
        assertTrue(service.needsCompaction(70_000));
    }

    @Test
    void projectedProviderUsageCanForceHeuristicFallback() {
        CompactionService service = new CompactionService(mapper, 8_192);
        List<CompactionService.ConversationEntry> entries = List.of(
                CompactionService.ConversationEntry.toolResult("read", "c1", "x".repeat(4_000)));
        assertFalse(service.compact(entries).isCompacted());
        assertTrue(service.compact(entries, 8_000).isCompacted());
    }

    @Test
    void compactOnSmallWindowActuallyShrinks() {
        CompactionService service = new CompactionService(mapper, 4_096);
        List<CompactionService.ConversationEntry> entries = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            entries.add(CompactionService.ConversationEntry.toolResult(
                    "read", "call_" + i, ("line of tool output " + i + "\n").repeat(120)));
            entries.add(CompactionService.ConversationEntry.assistant("ok " + i + " " + "y".repeat(900)));
        }
        CompactionService.CompactionResult result = service.compact(entries);
        assertTrue(result.isCompacted());
        assertTrue(result.getTokensAfter() < result.getTokensBefore(),
                "compaction on a small window must actually reduce tokens");
    }

    @Test
    void renderDigestCollapsesToolResultsAndLabelsRoles() {
        CompactionService service = new CompactionService(mapper, 8_192);
        List<CompactionService.ConversationEntry> entries = List.of(
                CompactionService.ConversationEntry.user("How do I build the dist?"),
                CompactionService.ConversationEntry.toolResult("bash", "c1", "l1\nl2\nl3\nl4\nl5\n".repeat(50)),
                CompactionService.ConversationEntry.assistant("Run build-dist.sh from the repo root."));
        String digest = service.renderDigest(entries);
        assertTrue(digest.contains("User: How do I build the dist?"));
        assertTrue(digest.contains("Assistant: Run build-dist.sh"));
        assertTrue(digest.contains("[bash result:"), "tool result must collapse to its summary");
    }

    @Test
    void summarizeToolResultContentKeepsSavedPath() {
        String content = "big output\n".repeat(200) + "\n[saved to: /tmp/tool-results/abc.txt]";
        String summary = CompactionService.summarizeToolResultContent("grep", content);
        assertTrue(summary.contains("[grep result:"));
        assertTrue(summary.contains("/tmp/tool-results/abc.txt"));
        assertTrue(summary.length() < content.length());
    }
}
