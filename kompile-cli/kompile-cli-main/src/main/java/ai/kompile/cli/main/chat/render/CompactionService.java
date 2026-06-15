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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;

/**
 * Context compaction service for managing conversation history when approaching
 * token limits. Comparable to OpenCode's compaction strategy.
 *
 * Strategy:
 * 1. Estimate token count of conversation history
 * 2. When approaching limit (within 20K tokens of max), trigger compaction
 * 3. Prune old tool outputs, keeping recent 40K tokens intact
 * 4. Replace pruned tool outputs with summaries
 * 5. Preserve system messages and user messages
 */
public class CompactionService {

    private static final int DEFAULT_MAX_TOKENS = 128_000;
    private static final int COMPACTION_BUFFER = 20_000;
    private static final int PRESERVE_RECENT_TOKENS = 40_000;
    private static final double CHARS_PER_TOKEN = 4.0; // rough estimate

    private final int maxTokens;
    private final ObjectMapper objectMapper;

    public CompactionService(ObjectMapper objectMapper) {
        this(objectMapper, DEFAULT_MAX_TOKENS);
    }

    public CompactionService(ObjectMapper objectMapper, int maxTokens) {
        this.objectMapper = objectMapper;
        this.maxTokens = maxTokens;
    }

    /**
     * Check if compaction is needed based on estimated token count.
     */
    public boolean needsCompaction(List<ConversationEntry> entries) {
        int estimatedTokens = estimateTokens(entries);
        return estimatedTokens >= (maxTokens - COMPACTION_BUFFER);
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

        // Find the cutoff point: preserve the most recent PRESERVE_RECENT_TOKENS
        int recentTokens = 0;
        int preserveFromIndex = entries.size();
        for (int i = entries.size() - 1; i >= 0; i--) {
            recentTokens += estimateEntryTokens(entries.get(i));
            if (recentTokens >= PRESERVE_RECENT_TOKENS) {
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
                String summary = summarizeToolResult(entry);
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

    private String summarizeToolResult(ConversationEntry entry) {
        String content = entry.content;
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
        summary.append("[").append(entry.toolName != null ? entry.toolName : "tool").append(" result: ");
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
