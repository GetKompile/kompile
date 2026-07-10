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
    private static final int MAX_COMPACTION_BUFFER = 20_000;
    private static final int MAX_PRESERVE_RECENT_TOKENS = 40_000;
    private static final double CHARS_PER_TOKEN = 4.0; // rough estimate

    /**
     * Context budget in tokens. Mutable: refreshed per turn from the active model's
     * real context window (dynamic CLI catalog, static table, or local staging probe)
     * so the trigger tracks the model actually being chatted with.
     */
    private volatile int maxTokens;
    private final ObjectMapper objectMapper;

    public CompactionService(ObjectMapper objectMapper) {
        this(objectMapper, DEFAULT_MAX_TOKENS);
    }

    public CompactionService(ObjectMapper objectMapper, int maxTokens) {
        this.objectMapper = objectMapper;
        this.maxTokens = sanitizeMaxTokens(maxTokens);
    }

    /** Update the context budget (in tokens) for the active model. Non-positive resets to the default. */
    public void setMaxTokens(int maxTokens) {
        this.maxTokens = sanitizeMaxTokens(maxTokens);
    }

    public int getMaxTokens() {
        return maxTokens;
    }

    private static int sanitizeMaxTokens(int maxTokens) {
        return maxTokens > 0 ? Math.max(1_024, maxTokens) : DEFAULT_MAX_TOKENS;
    }

    /**
     * Headroom kept free below the context window before compaction triggers.
     * Proportional to the window so small local models don't sit permanently
     * past the trigger line (a fixed 20K buffer exceeds a 4K window entirely).
     */
    int compactionBuffer() {
        return Math.min(MAX_COMPACTION_BUFFER, Math.max(256, maxTokens / 8));
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
        long estimatedTokens = Math.max(estimateTokens(entries), reportedInputTokens);
        return estimatedTokens >= (long) maxTokens - compactionBuffer();
    }

    /**
     * Compact the conversation history by pruning old tool outputs.
     *
     * @param entries the conversation entries
     * @return compacted entries with tool output summaries
     */
    public CompactionResult compact(List<ConversationEntry> entries) {
        int totalBefore = estimateTokens(entries);

        if (!needsCompaction(entries)) {
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
        int total = 0;
        for (ConversationEntry entry : entries) {
            total += estimateEntryTokens(entry);
        }
        return total;
    }

    private int estimateEntryTokens(ConversationEntry entry) {
        if (entry.content == null) return 0;
        return (int) (entry.content.length() / CHARS_PER_TOKEN);
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
