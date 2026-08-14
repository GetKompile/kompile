/*
 *   Copyright 2025 Kompile Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ai.kompile.cli.main.chat.render;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

/**
 * Context compaction service for managing conversation history when approaching
 * token limits. Comparable to OpenCode's compaction strategy.
 *
 * Strategy:
 * 1. Estimate token count of conversation history
 * 2. When approaching the model's context window (proportional headroom), trigger compaction
 * 3. Prune old tool outputs, keeping a recent window (proportional to the model) intact
 * 4. Replace pruned tool outputs with summaries
 * 5. Preserve system messages and user messages
 *
 * The token budget is model-aware: the chat loop refreshes {@link #setMaxTokens(int)}
 * from the active model's real context window each turn, so thresholds are correct
 * for both a 200K Claude and a 4K local staged GGUF.
 */
public class CompactionService {

    private static final int DEFAULT_MAX_TOKENS = 128_000;
    private static final int DEFAULT_MAX_OUTPUT_TOKENS = 8_192;
    private static final double DEFAULT_TRIGGER_RATIO = 0.85d;
    private static final int MAX_PRESERVE_RECENT_TOKENS = 40_000;
    private static final double CHARS_PER_TOKEN = 4.0; // rough estimate

    /**
     * Context budget in tokens. Mutable: refreshed per turn from the active model's
     * real context window (dynamic CLI catalog, static table, or local staging probe)
     * so the trigger tracks the model actually being chatted with.
     */
    private volatile int maxTokens;
    private volatile int maxOutputTokens = DEFAULT_MAX_OUTPUT_TOKENS;
    private volatile boolean autoCompactEnabled = true;
    private volatile double triggerRatio = DEFAULT_TRIGGER_RATIO;
    private volatile int explicitReserveTokens = 0;
    private final ObjectMapper objectMapper;

    public CompactionService(ObjectMapper objectMapper) {
        this(objectMapper, DEFAULT_MAX_TOKENS);
    }

    public CompactionService(ObjectMapper objectMapper, int maxTokens) {
        this.objectMapper = objectMapper;
        this.maxTokens = sanitizeMaxTokens(maxTokens);
    }

    /** Update only the context budget, retaining the current policy. */
    public void setMaxTokens(int maxTokens) {
        this.maxTokens = sanitizeMaxTokens(maxTokens);
    }

    /** Configure the provider-neutral policy for the active provider/model. */
    public void configure(boolean enabled, double threshold, int maxOutputTokens,
                          int explicitReserveTokens) {
        this.autoCompactEnabled = enabled;
        this.triggerRatio = sanitizeRatio(threshold);
        this.maxOutputTokens = maxOutputTokens > 0
                ? maxOutputTokens : DEFAULT_MAX_OUTPUT_TOKENS;
        this.explicitReserveTokens = Math.max(0, explicitReserveTokens);
    }

    public int getMaxTokens() { return maxTokens; }
    public int getMaxOutputTokens() { return maxOutputTokens; }
    public boolean isAutoCompactEnabled() { return autoCompactEnabled; }
    public double getTriggerRatio() { return triggerRatio; }

    private static int sanitizeMaxTokens(int maxTokens) {
        return maxTokens > 0 ? Math.max(1_024, maxTokens) : DEFAULT_MAX_TOKENS;
    }

    private static double sanitizeRatio(double ratio) {
        if (!Double.isFinite(ratio)) return DEFAULT_TRIGGER_RATIO;
        return Math.max(0.50d, Math.min(0.95d, ratio));
    }

    /** Effective headroom, derived from output capacity unless explicitly configured. */
    public int effectiveReserveTokens() {
        int reserve = explicitReserveTokens;
        if (reserve <= 0) {
            int safety = Math.max(256, Math.min(8_192, maxTokens / 20));
            long automatic = (long) maxOutputTokens + safety;
            reserve = (int) Math.min(Integer.MAX_VALUE, automatic);
        }
        return Math.min(reserve, Math.max(0, maxTokens - 1_024));
    }

    /** Input-token ceiling at which auto-compaction begins. */
    public int triggerTokens() {
        long ratioLimit = (long) Math.floor(maxTokens * triggerRatio);
        long reserveLimit = (long) maxTokens - effectiveReserveTokens();
        return (int) Math.max(1_024L, Math.min(ratioLimit, reserveLimit));
    }

    int compactionBuffer() {
        return maxTokens - triggerTokens();
    }

    /**
     * Recent-history span preserved verbatim during heuristic compaction,
     * proportional to the window (a fixed 40K preserve span would make
     * compaction a no-op on models smaller than 40K).
     */
    int preserveRecentTokens() {
        return Math.min(MAX_PRESERVE_RECENT_TOKENS, Math.max(512, maxTokens / 3));
    }

    /**
     * Check if compaction is needed based on estimated token count.
     */
    public boolean needsCompaction(List<ConversationEntry> entries) {
        return needsCompaction(entries, 0L);
    }

    /**
     * Check if compaction is needed, additionally considering the last prompt token
     * count reported by the provider API ({@code usage.prompt_tokens} /
     * {@code usage.input_tokens}). The reported figure includes the system prompt and
     * tool definitions the char estimate can't see, so take the max of both signals.
     */
    public boolean needsCompaction(List<ConversationEntry> entries, long reportedInputTokens) {
        return needsCompaction(Math.max(estimateTokens(entries), reportedInputTokens));
    }

    /** Check an already-projected provider context occupancy. */
    public boolean needsCompaction(long projectedInputTokens) {
        return autoCompactEnabled && projectedInputTokens >= triggerTokens();
    }

    /**
     * Compact the conversation history by pruning old tool outputs.
     *
     * @param entries the conversation entries
     * @return compacted entries with tool output summaries
     */
    public CompactionResult compact(List<ConversationEntry> entries) {
        return compact(entries, estimateTokens(entries));
    }

    /** Compact using a provider-aware projected occupancy as the trigger signal. */
    public CompactionResult compact(List<ConversationEntry> entries, long projectedInputTokens) {
        int totalBefore = estimateTokens(entries);

        if (!needsCompaction(Math.max(totalBefore, projectedInputTokens))) {
            return new CompactionResult(entries, totalBefore, totalBefore, false);
        }

        // Find the cutoff point: preserve the most recent proportional window
        int preserveTarget = preserveRecentTokens();
        int recentTokens = 0;
        int preserveFromIndex = entries.size();
        for (int i = entries.size() - 1; i >= 0; i--) {
            recentTokens += estimateEntryTokens(entries.get(i));
            if (recentTokens >= preserveTarget) {
                preserveFromIndex = i;
                break;
            }
        }

        List<ConversationEntry> compacted = new ArrayList<>();

        for (int i = 0; i < entries.size(); i++) {
            ConversationEntry entry = entries.get(i);

            if (i >= preserveFromIndex) {
                // Preserve recent entries as-is
                compacted.add(entry);
            } else if (entry.type == EntryType.TOOL_RESULT) {
                // Replace old tool results with summaries
                String summary = summarizeToolResultContent(entry.toolName, entry.content);
                compacted.add(new ConversationEntry(
                        EntryType.TOOL_RESULT,
                        entry.role,
                        summary,
                        entry.toolName,
                        entry.toolCallId
                ));
            } else if (entry.type == EntryType.SYSTEM || entry.type == EntryType.USER) {
                // Always preserve system and user messages
                compacted.add(entry);
            } else {
                // Truncate old assistant messages
                String content = entry.content;
                if (content.length() > 500) {
                    content = content.substring(0, 500) + "\n... (truncated during compaction)";
                }
                compacted.add(new ConversationEntry(
                        entry.type, entry.role, content, entry.toolName, entry.toolCallId));
            }
        }

        int totalAfter = estimateTokens(compacted);
        return new CompactionResult(compacted, totalBefore, totalAfter, true);
    }

    /**
     * Estimate total tokens for a list of entries.
     */
    public int estimateTokens(List<ConversationEntry> entries) {
        long total = 0;
        for (ConversationEntry entry : entries) {
            total += estimateEntryTokens(entry);
            if (total >= Integer.MAX_VALUE) return Integer.MAX_VALUE;
        }
        return (int) total;
    }

    /** Estimate a pending message that is not yet present in tracked history. */
    public int estimateTextTokens(String text) {
        if (text == null || text.isEmpty()) return 0;
        return Math.max(1, (int) Math.ceil(text.length() / CHARS_PER_TOKEN));
    }

    private int estimateEntryTokens(ConversationEntry entry) {
        return entry == null ? 0 : estimateTextTokens(entry.content);
    }

    /**
     * Deterministic plain-text digest of a conversation — the no-LLM fallback used
     * when a summarization call fails but the history must still shrink. Roles are
     * labeled, tool results collapse to their one-line summaries, and long turns
     * are clipped, so the digest is safe to inject as replacement history.
     */
    public String renderDigest(List<ConversationEntry> entries) {
        StringBuilder digest = new StringBuilder();
        for (ConversationEntry entry : entries) {
            if (entry.content == null || entry.content.isBlank()) continue;
            switch (entry.type) {
                case USER -> digest.append("User: ").append(clip(entry.content, 400)).append('\n');
                case ASSISTANT -> digest.append("Assistant: ").append(clip(entry.content, 400)).append('\n');
                case SYSTEM -> digest.append("Note: ").append(clip(entry.content, 400)).append('\n');
                case TOOL_CALL -> digest.append("Tool call: ")
                        .append(entry.toolName != null ? entry.toolName : "tool").append('\n');
                case TOOL_RESULT -> digest.append(clip(
                        summarizeToolResultContent(entry.toolName, entry.content), 300)).append('\n');
            }
        }
        return digest.toString().trim();
    }

    private static String clip(String text, int maxChars) {
        String flat = text.strip();
        return flat.length() <= maxChars ? flat : flat.substring(0, maxChars) + "…";
    }

    /**
     * Collapse a tool result to a short summary (line/size counts, saved-path pointer,
     * first-lines preview). Static so the wire-history pruner can reuse the exact same
     * rendering on {@code DirectLlmClient}'s message list.
     */
    public static String summarizeToolResultContent(String toolName, String content) {
        if (content == null || content.isEmpty()) {
            return "(empty result)";
        }

        // Extract saved file path if present (appended as "[saved to: /path/...]")
        String savedPath = null;
        String contentForSummary = content;
        int savedIdx = content.lastIndexOf("\n[saved to: ");
        if (savedIdx >= 0) {
            savedPath = content.substring(savedIdx + 12, content.length() - 1);
            contentForSummary = content.substring(0, savedIdx);
        }

        // Count lines
        long lineCount = contentForSummary.lines().count();
        int charCount = contentForSummary.length();

        StringBuilder summary = new StringBuilder();
        summary.append("[").append(toolName != null ? toolName : "tool").append(" result: ");
        summary.append(lineCount).append(" lines, ");
        summary.append(formatSize(charCount)).append("]");

        // Include the file path so the agent can read the full output
        if (savedPath != null) {
            summary.append("\nFull output saved to: ").append(savedPath);
            summary.append("\nUse the `read` tool to access the full output.");
        }

        // Include first few lines as preview
        String[] lines = contentForSummary.split("\n", 6);
        int previewLines = Math.min(3, lines.length);
        if (previewLines > 0) {
            summary.append("\n");
            for (int i = 0; i < previewLines; i++) {
                String line = lines[i];
                if (line.length() > 120) line = line.substring(0, 117) + "...";
                summary.append(line).append("\n");
            }
            if (lineCount > previewLines) {
                summary.append("... (").append(lineCount - previewLines).append(" more lines)");
            }
        }

        return summary.toString();
    }

    private static String formatSize(int chars) {
        if (chars < 1024) return chars + " chars";
        return String.format("%.1fKB", chars / 1024.0);
    }

    // ========================================================================
    // Data types
    // ========================================================================

    public enum EntryType {
        SYSTEM, USER, ASSISTANT, TOOL_CALL, TOOL_RESULT
    }

    public static class ConversationEntry {
        public final EntryType type;
        public final String role;
        public final String content;
        public final String toolName;
        public final String toolCallId;

        public ConversationEntry(EntryType type, String role, String content,
                                  String toolName, String toolCallId) {
            this.type = type;
            this.role = role;
            this.content = content;
            this.toolName = toolName;
            this.toolCallId = toolCallId;
        }

        public static ConversationEntry system(String content) {
            return new ConversationEntry(EntryType.SYSTEM, "system", content, null, null);
        }

        public static ConversationEntry user(String content) {
            return new ConversationEntry(EntryType.USER, "user", content, null, null);
        }

        public static ConversationEntry assistant(String content) {
            return new ConversationEntry(EntryType.ASSISTANT, "assistant", content, null, null);
        }

        public static ConversationEntry toolCall(String toolName, String callId, String content) {
            return new ConversationEntry(EntryType.TOOL_CALL, "assistant", content, toolName, callId);
        }

        public static ConversationEntry toolResult(String toolName, String callId, String content) {
            return new ConversationEntry(EntryType.TOOL_RESULT, "tool", content, toolName, callId);
        }
    }

    public static class CompactionResult {
        private final List<ConversationEntry> entries;
        private final int tokensBefore;
        private final int tokensAfter;
        private final boolean compacted;

        public CompactionResult(List<ConversationEntry> entries, int tokensBefore,
                                 int tokensAfter, boolean compacted) {
            this.entries = entries;
            this.tokensBefore = tokensBefore;
            this.tokensAfter = tokensAfter;
            this.compacted = compacted;
        }

        public List<ConversationEntry> getEntries() { return entries; }
        public int getTokensBefore() { return tokensBefore; }
        public int getTokensAfter() { return tokensAfter; }
        public boolean isCompacted() { return compacted; }
    }
}
