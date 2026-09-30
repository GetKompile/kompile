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
     * At least the newest complete user exchange is retained when possible, but
     * never behind a prefix that holds only earlier summaries.
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
                if (i == 0 || !onlySummaries(entries, i)) return i;
                break;
            }
        }

        // Summarizing only earlier summaries cannot shrink the context, and after a
        // checkpoint newer provider output may contain no user event. Keep the newer
        // exchanges verbatim; with none, compact the complete active projection
        // rather than preserve an arbitrarily large assistant/tool tail forever.
        for (int i = candidate + 1; i < entries.size(); i++) {
            CompactionService.ConversationEntry entry = entries.get(i);
            if (entry != null && entry.type == CompactionService.EntryType.USER) {
                return i;
            }
        }
        return entries.size();
    }

    /** True when every entry before {@code end} is a compacted summary. */
    private static boolean onlySummaries(List<CompactionService.ConversationEntry> entries, int end) {
        for (int i = 0; i < end; i++) {
            CompactionService.ConversationEntry entry = entries.get(i);
            if (entry != null && !(entry.type == CompactionService.EntryType.SYSTEM
                    && entry.content != null
                    && entry.content.startsWith(ConversationLedger.SUMMARY_MARKER))) {
                return false;
            }
        }
        return true;
    }
}
