/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.cli.main.chat.context;

import ai.kompile.cli.main.chat.render.CompactionService;

import java.util.List;

/** Selects compaction prefixes without splitting a user/tool exchange. */
public final class ConversationBoundaryPlanner {

    private ConversationBoundaryPlanner() {
    }

    /**
     * Returns the first active-entry index that must remain verbatim.
     * At least the newest complete user exchange is retained when possible.
     */
    public static int preserveFrom(
            List<CompactionService.ConversationEntry> entries,
            CompactionService tokenEstimator,
            int preserveTokens) {
        if (entries == null || entries.isEmpty()) return 0;
        int target = Math.max(1, preserveTokens);
        int estimated = 0;
        int candidate = entries.size();
        for (int i = entries.size() - 1; i >= 0; i--) {
            CompactionService.ConversationEntry entry = entries.get(i);
            estimated += tokenEstimator.estimateTextTokens(entry == null ? null : entry.content);
            candidate = i;
            if (estimated >= target) break;
        }

        // A user entry begins one complete exchange. Moving the cutoff backward
        // keeps every assistant tool call and its corresponding tool result with
        // the user request that caused them.
        for (int i = candidate; i >= 0; i--) {
            CompactionService.ConversationEntry entry = entries.get(i);
            if (entry != null && entry.type == CompactionService.EntryType.USER) {
                return i;
            }
        }

        // After a checkpoint, newer provider output may contain no user event.
        // Summarizing only the old summary would preserve an arbitrarily large
        // assistant/tool tail forever, so compact the complete active projection.
        return entries.size();
    }
}
