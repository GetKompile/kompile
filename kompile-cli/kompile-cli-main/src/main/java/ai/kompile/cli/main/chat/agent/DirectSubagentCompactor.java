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
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ai.kompile.cli.main.chat.agent;

import ai.kompile.cli.main.chat.config.ChatConfig;
import ai.kompile.cli.main.chat.config.DirectLlmClient;
import ai.kompile.cli.main.chat.config.ModelContextResolver;
import ai.kompile.cli.main.chat.context.ConversationBoundaryPlanner;
import ai.kompile.cli.main.chat.context.ConversationLedger;
import ai.kompile.cli.main.chat.render.CompactionService;
import ai.kompile.cli.main.chat.render.ConversationSummarizer;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Model-aware context compaction for direct task subagents — the child-side
 * counterpart of {@code AgenticChatLoop}'s pipeline, sized to what a retained
 * child session can know without a durable ledger:
 *
 * <ul>
 *   <li>The budget follows the child's resolved model (same resolver, same
 *       policy knobs as main chat), so a subagent on a small model compacts
 *       earlier than the parent.</li>
 *   <li>A portable projection mirrors every history commit {@link DirectLlmClient}
 *       makes, giving token estimates, complete-exchange boundary selection, and
 *       a replay source for Tier-2 rewrites.</li>
 *   <li>Tier 1 (mid-exchange) shrinks old wire bodies in place — the same
 *       {@code compactToolHistory} transform main chat uses — because roles,
 *       ids, and tool_call wiring must stay provider-valid mid-exchange.</li>
 *   <li>Tier 2 (fresh-exchange boundary) summarizes the prefix older than the
 *       newest user exchange, with a deterministic digest fallback, and commits
 *       only on strict token reduction.</li>
 *   <li>Context-overflow recovery rewrites once and retries the same outbound
 *       turn; failed requests commit no history, so the retry cannot duplicate.</li>
 * </ul>
 */
final class DirectSubagentCompactor {

    /** Preserve the newest 8 messages, matching main chat's mid-exchange pruner. */
    private static final int TIER1_PRESERVE_RECENT_MESSAGES = 8;
    /** Thresholds mirror DirectLlmClient.compactToolHistory exactly. */
    private static final int TOOL_RESULT_SUMMARY_THRESHOLD = 600;
    private static final int ASSISTANT_CLIP_THRESHOLD = 2_000;

    private final CompactionService compactionService;
    private final ModelContextResolver contextResolver = new ModelContextResolver();

    /** Portable projection of the child's wire history, in commit order. */
    private final List<CompactionService.ConversationEntry> projection = new ArrayList<>();
    /**
     * Projection prefix known to be committed to the client's wire history.
     * Session-scoped (not per-run): an exchange finished under an earlier
     * follow-up run is just as committed as one finished now, and a failed
     * request must only roll back the pending turn — never history that
     * previous turns already sent.
     */
    private int committedMark;
    private long lastReportedInputTokens;

    DirectSubagentCompactor(ObjectMapper objectMapper) {
        this.compactionService = new CompactionService(objectMapper);
    }

    /** Size the budget from the child's own model and the shared compaction policy. */
    void configure(ChatConfig childConfig, String modelOverride) {
        ModelContextResolver.ModelLimits limits = contextResolver.resolveLimits(childConfig, modelOverride);
        compactionService.setMaxTokens(limits.contextWindow());
        compactionService.configure(childConfig.isAutoCompactEnabled(),
                childConfig.getAutoCompactThreshold(), limits.maxOutputTokens(),
                childConfig.getCompactionReserveTokens());
    }

    int contextWindowTokens() {
        return compactionService.getMaxTokens();
    }

    int wireMaxOutputTokens() {
        return compactionService.wireMaxOutputTokens();
    }

    /** Track the provider-reported prompt occupancy of the most recent call. */
    void recordReportedTokens(long contextInputTokens) {
        if (contextInputTokens > 0) {
            lastReportedInputTokens = contextInputTokens;
        }
    }

    void appendUser(String content) {
        if (content != null) {
            projection.add(CompactionService.ConversationEntry.user(content));
        }
    }

    /**
     * Projection length before the pending outbound message is appended.
     * Returns the last committed prefix — the mark never regresses below
     * what earlier exchanges already committed to the client history.
     */
    int markRequestStart() {
        return committedMark;
    }

    /**
     * The request was accepted: everything the adapter just committed (previous
     * pending tool results, the outbound message, the new assistant envelope)
     * is now part of the durable child history.
     */
    void commitThroughPendingOutbound() {
        committedMark = projection.size();
    }

    /**
     * The request failed before any history commit. Drop only the pending
     * outbound message (and anything appended after the committed prefix), so
     * the retry re-sends it exactly once — the main-chat "rejected tail stays
     * out of the checkpoint" rule — without touching committed history.
     */
    void rollbackTo(int mark) {
        int safeMark = Math.min(mark, committedMark);
        while (projection.size() > safeMark) {
            projection.remove(projection.size() - 1);
        }
    }

    void appendAssistant(String content) {
        if (content != null && !content.isEmpty()) {
            projection.add(CompactionService.ConversationEntry.assistant(content));
        }
    }

    void appendToolCall(String toolName, String callId, String argumentsJson) {
        projection.add(CompactionService.ConversationEntry.toolCall(
                toolName, callId, argumentsJson == null ? "{}" : argumentsJson));
    }

    void appendToolResult(String toolName, String callId, String output) {
        projection.add(CompactionService.ConversationEntry.toolResult(
                toolName, callId, output == null ? "" : output));
    }

    /**
     * Projected input tokens of the next request: the max of the char/4 estimate
     * (projection plus the pending outbound message) and the provider-reported
     * occupancy of the previous call plus that message.
     */
    long projectedInputTokens(String outboundMessage) {
        long estimated = compactionService.estimateTokens(projection)
                + compactionService.estimateTextTokens(outboundMessage);
        long reported = lastReportedInputTokens + compactionService.estimateTextTokens(outboundMessage);
        return Math.max(estimated, reported);
    }

    /** Whether the configured trigger fires for the next request (policy-gated). */
    boolean preventiveNeeded(String outboundMessage) {
        return compactionService.needsCompaction(projectedInputTokens(outboundMessage));
    }

    /**
     * Tier 1 — mid-exchange in-place shrink of old tool bodies and assistant
     * text. Roles, ids, and tool_call envelopes stay intact; the projection is
     * shrunk by the same transform so estimates stay truthful. Returns the number
     * of shrunk bodies (0 when everything is inside the recent window).
     */
    int shrinkInPlace(DirectLlmClient client) {
        int shrunk = client.compactToolHistory(TIER1_PRESERVE_RECENT_MESSAGES,
                CompactionService::summarizeToolResultContent);
        int keep = Math.max(0, projection.size() - TIER1_PRESERVE_RECENT_MESSAGES);
        for (int i = 0; i < keep; i++) {
            CompactionService.ConversationEntry entry = projection.get(i);
            if (entry == null || entry.content == null) continue;
            if (entry.type == CompactionService.EntryType.TOOL_RESULT
                    && entry.content.length() > TOOL_RESULT_SUMMARY_THRESHOLD) {
                projection.set(i, CompactionService.ConversationEntry.toolResult(
                        entry.toolName, entry.toolCallId,
                        CompactionService.summarizeToolResultContent(entry.toolName, entry.content)));
            } else if (entry.type == CompactionService.EntryType.ASSISTANT
                    && entry.content.length() > ASSISTANT_CLIP_THRESHOLD) {
                projection.set(i, new CompactionService.ConversationEntry(
                        entry.type, entry.role,
                        entry.content.substring(0, ASSISTANT_CLIP_THRESHOLD)
                                + "\n... (truncated during compaction)",
                        entry.toolName, entry.toolCallId));
            }
        }
        return shrunk;
    }

    /**
     * Tier 2 — summarize the prefix older than the newest user exchange at a
     * complete-exchange boundary, replay the compacted candidate into the client
     * history, and mirror it into the projection. A refused commit carries the
     * reason so the parent task surfaces a precise failure instead of a bare
     * "compaction did nothing".
     */
    CommitResult compactAtBoundary(DirectLlmClient client, String modelOverride, String focusInstruction) {
        if (projection.isEmpty()) {
            return CommitResult.rejected("the child history is empty");
        }
        int tokensBefore = compactionService.estimateTokens(projection);
        int preserveIndex = ConversationBoundaryPlanner.preserveFrom(
                projection, compactionService, compactionService.preserveRecentTokens());
        if (preserveIndex <= 0) {
            return CommitResult.rejected("no complete older exchange exists to summarize"
                    + " (the newest exchange already spans the preserve window)");
        }

        List<CompactionService.ConversationEntry> prefix =
                new ArrayList<>(projection.subList(0, preserveIndex));
        List<CompactionService.ConversationEntry> tail =
                new ArrayList<>(projection.subList(preserveIndex, projection.size()));

        String summary;
        ConversationSummarizer.SummaryResult summarized =
                new ConversationSummarizer(client).summarize(prefix, focusInstruction, modelOverride);
        if (summarized.isSuccessful() && summarized.getSummary() != null
                && !summarized.getSummary().isBlank()) {
            summary = summarized.getSummary();
        } else {
            summary = compactionService.renderDigest(prefix);
        }
        if (summary.isBlank()) {
            return CommitResult.rejected("summarization produced no usable text");
        }

        List<CompactionService.ConversationEntry> candidate = new ArrayList<>();
        candidate.add(CompactionService.ConversationEntry.system(
                ConversationLedger.SUMMARY_MARKER + summary));
        candidate.addAll(tail);
        int tokensAfter = compactionService.estimateTokens(candidate);
        if (tokensAfter >= tokensBefore) {
            return CommitResult.rejected("the compacted candidate (" + tokensAfter
                    + " estimated tokens) would not shrink the " + tokensBefore
                    + "-token history");
        }

        replayCandidate(client, modelOverride, candidate);
        projection.clear();
        projection.addAll(candidate);
        // Everything replayed above is committed client history; the mark follows
        // the rewritten projection so later rollbacks never touch it.
        committedMark = projection.size();
        lastReportedInputTokens = 0;
        return new CommitResult(tokensBefore, tokensAfter, summarized.isSuccessful(), null);
    }

    /**
     * Child variant of {@code AgenticChatLoop.rebuildDirectHistory}: protocol-
     * correct envelope replay of the compacted candidate, closing dangling tool
     * calls with synthetic cancellation results so the wire stays valid.
     */
    private void replayCandidate(DirectLlmClient client, String modelOverride,
                                 List<CompactionService.ConversationEntry> entries) {
        client.clearHistory();
        Map<String, CompactionService.ConversationEntry> pendingCalls = new LinkedHashMap<>();
        int index = 0;
        while (index < entries.size()) {
            CompactionService.ConversationEntry entry = entries.get(index);
            if (entry == null || ((entry.content == null || entry.content.isBlank())
                    && entry.type != CompactionService.EntryType.TOOL_CALL
                    && entry.type != CompactionService.EntryType.TOOL_RESULT)) {
                index++;
                continue;
            }
            if (entry.type == CompactionService.EntryType.TOOL_CALL) {
                List<DirectLlmClient.ReplayedToolCallInput> calls = new ArrayList<>();
                while (index < entries.size()) {
                    CompactionService.ConversationEntry call = entries.get(index);
                    if (call == null || call.type != CompactionService.EntryType.TOOL_CALL) break;
                    calls.add(new DirectLlmClient.ReplayedToolCallInput(
                            call.toolName, call.toolCallId, call.content));
                    if (call.toolCallId != null && !call.toolCallId.isBlank()) {
                        pendingCalls.put(call.toolCallId, call);
                    }
                    index++;
                }
                client.addReplayedToolCalls(calls, modelOverride);
                continue;
            }
            if (entry.type == CompactionService.EntryType.TOOL_RESULT) {
                List<DirectLlmClient.ToolCallResultInput> results = new ArrayList<>();
                while (index < entries.size()) {
                    CompactionService.ConversationEntry result = entries.get(index);
                    if (result == null || result.type != CompactionService.EntryType.TOOL_RESULT) break;
                    results.add(new DirectLlmClient.ToolCallResultInput(
                            result.toolCallId, result.toolName, result.content, false));
                    pendingCalls.remove(result.toolCallId);
                    index++;
                }
                client.addReplayedToolResults(results, modelOverride);
                continue;
            }
            switch (entry.type) {
                case SYSTEM -> {
                    client.addToHistory("user", "[Conversation summary]\n" + entry.content);
                    client.addToHistory("assistant",
                            "Understood. I will continue from that conversation summary.");
                }
                case USER, ASSISTANT -> client.addToHistory(entry.role, entry.content);
                case TOOL_CALL, TOOL_RESULT -> { }
            }
            index++;
        }
        if (!pendingCalls.isEmpty()) {
            List<DirectLlmClient.ToolCallResultInput> cancellations = new ArrayList<>();
            for (CompactionService.ConversationEntry pending : pendingCalls.values()) {
                cancellations.add(new DirectLlmClient.ToolCallResultInput(
                        pending.toolCallId, pending.toolName,
                        "Tool call was cancelled before execution; no result was recorded.", true));
            }
            client.addReplayedToolResults(cancellations, modelOverride);
        }
    }

    record CommitResult(int tokensBefore, int tokensAfter, boolean summarizedWithModel,
                        String rejection) {

        static CommitResult rejected(String reason) {
            return new CommitResult(0, 0, false, reason);
        }

        boolean committed() {
            return rejection == null;
        }
    }
}
