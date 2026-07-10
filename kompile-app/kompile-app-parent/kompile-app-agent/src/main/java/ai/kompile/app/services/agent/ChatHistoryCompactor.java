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

package ai.kompile.app.services.agent;

import ai.kompile.app.web.dto.AgentChatRequest.ChatHistoryEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Compacts a chat window's conversation history to fit the model's context budget.
 *
 * <p>The history is split into an old head and a recent tail. The tail (sized as a
 * fraction of the input budget, never fewer than the last exchange) is preserved
 * verbatim for continuity; the head is replaced by a summary — LLM-generated through
 * the same lane the chat itself uses when a {@link Summarizer} is supplied, or a
 * deterministic role-labeled digest when summarization fails or is unavailable.</p>
 *
 * <p>The compacted history starts with a user/assistant summary exchange (mirroring the
 * CLI's {@code /compact}) so any provider — hosted API, staging's OpenAI facade, or a
 * prompt-embedded CLI agent — continues naturally from it.</p>
 */
@Service
public class ChatHistoryCompactor {

    private static final Logger log = LoggerFactory.getLogger(ChatHistoryCompactor.class);

    /** Auto-compaction fires when estimated usage crosses this share of the input budget. */
    public static final double TRIGGER_RATIO = 0.8;

    /** Marker prefix on the injected summary message — lets UIs style it distinctly. */
    public static final String SUMMARY_MARKER = "[Conversation summary — earlier messages were compacted]";

    private static final int MIN_TAIL_MESSAGES = 2;
    private static final int MAX_TAIL_TOKENS = 4_000;
    private static final int TRANSCRIPT_ENTRY_CAP_CHARS = 2_000;
    private static final int SUMMARY_CAP_CHARS = 8_000;
    private static final int DIGEST_ENTRY_CAP_CHARS = 240;
    private static final int DIGEST_CAP_CHARS = 4_000;

    /** Lane-appropriate one-shot LLM call: transcript in, summary text out. */
    @FunctionalInterface
    public interface Summarizer {
        String summarize(String transcript, String focusInstruction) throws Exception;
    }

    /**
     * @param compacted    whether anything changed; when false, {@code history} is the input list
     * @param usedFallback true when the deterministic digest replaced a failed/absent LLM summary
     */
    public record Result(boolean compacted,
                         String summary,
                         List<ChatHistoryEntry> history,
                         int tokensBefore,
                         int tokensAfter,
                         boolean usedFallback) {

        static Result unchanged(List<ChatHistoryEntry> history, int tokens) {
            return new Result(false, null, history, tokens, tokens, false);
        }
    }

    /**
     * Whether the history (plus the tokens of the message about to be sent) is close
     * enough to the input budget that it should be compacted before sending.
     */
    public boolean needsCompaction(List<ChatHistoryEntry> history,
                                   ChatContextBudgetService.ContextBudget budget,
                                   int upcomingPromptTokens) {
        if (history == null || history.isEmpty() || budget == null) return false;
        long estimate = ChatContextBudgetService.estimateHistoryTokens(history) + Math.max(0, upcomingPromptTokens);
        return estimate > (long) (budget.inputBudgetTokens() * TRIGGER_RATIO);
    }

    /**
     * Compact the history against the budget.
     *
     * @param force      compact even when under the trigger threshold (manual /compact)
     * @param summarizer optional LLM summarizer; null falls straight to the digest
     */
    public Result compact(List<ChatHistoryEntry> history,
                          ChatContextBudgetService.ContextBudget budget,
                          String focusInstruction,
                          boolean force,
                          Summarizer summarizer) {
        if (history == null || history.isEmpty() || budget == null) {
            return Result.unchanged(history, 0);
        }

        int tokensBefore = ChatContextBudgetService.estimateHistoryTokens(history);
        if (!force && !needsCompaction(history, budget, 0)) {
            return Result.unchanged(history, tokensBefore);
        }

        int cutoff = tailCutoffIndex(history, budget, force);
        if (cutoff <= 0) {
            // Everything already fits inside the preserved tail — nothing to summarize.
            return Result.unchanged(history, tokensBefore);
        }

        List<ChatHistoryEntry> head = history.subList(0, cutoff);
        List<ChatHistoryEntry> tail = new ArrayList<>(history.subList(cutoff, history.size()));

        String transcript = renderTranscript(head, budget);
        String summary = null;
        boolean usedFallback = false;
        if (summarizer != null) {
            try {
                summary = summarizer.summarize(transcript, focusInstruction);
            } catch (Exception e) {
                log.warn("Chat history summarization failed, using deterministic digest: {}", e.getMessage());
            }
        }
        if (summary == null || summary.isBlank()) {
            summary = renderDigest(head);
            usedFallback = true;
        }
        if (summary.length() > SUMMARY_CAP_CHARS) {
            summary = summary.substring(0, SUMMARY_CAP_CHARS) + "…";
        }

        List<ChatHistoryEntry> compacted = new ArrayList<>(tail.size() + 2);
        compacted.add(new ChatHistoryEntry("user", SUMMARY_MARKER + "\n\n" + summary));
        compacted.add(new ChatHistoryEntry("assistant",
                "Understood. I have the compacted summary and will continue from here."));
        compacted.addAll(tail);

        int tokensAfter = ChatContextBudgetService.estimateHistoryTokens(compacted);
        log.info("Compacted chat history for agent={} model={}: {} → {} entries, ~{} → ~{} tokens{}",
                budget.agentName(), budget.model(), history.size(), compacted.size(),
                tokensBefore, tokensAfter, usedFallback ? " (digest fallback)" : "");
        return new Result(true, summary, compacted, tokensBefore, tokensAfter, usedFallback);
    }

    /**
     * Index where the preserved tail begins.
     *
     * <p>Auto mode: the tail holds the most recent entries up to a fraction of the
     * input budget (capped), but always at least the last {@value MIN_TAIL_MESSAGES}
     * messages so the current exchange survives verbatim.</p>
     *
     * <p>Force mode (manual compact): the user explicitly asked to shrink, so preserve
     * only from the most recent user turn onward (mirroring the CLI's /compact) even
     * when the whole history would fit the auto-mode tail.</p>
     */
    private int tailCutoffIndex(List<ChatHistoryEntry> history,
                                ChatContextBudgetService.ContextBudget budget,
                                boolean force) {
        if (force) {
            for (int i = history.size() - 1; i >= 0; i--) {
                ChatHistoryEntry entry = history.get(i);
                if (entry != null && "user".equalsIgnoreCase(entry.getRole())) {
                    return i;
                }
            }
            return history.size() > MIN_TAIL_MESSAGES ? history.size() - MIN_TAIL_MESSAGES : 0;
        }

        int tailBudget = Math.min(MAX_TAIL_TOKENS, Math.max(256, budget.inputBudgetTokens() / 4));
        int tokens = 0;
        int cutoff = history.size();
        for (int i = history.size() - 1; i >= 0; i--) {
            ChatHistoryEntry entry = history.get(i);
            int entryTokens = ChatContextBudgetService.estimateTokens(entry != null ? entry.getContent() : null);
            if (tokens + entryTokens > tailBudget && history.size() - i > MIN_TAIL_MESSAGES) {
                break;
            }
            tokens += entryTokens;
            cutoff = i;
        }
        return cutoff;
    }

    /**
     * Role-labeled transcript of the head, bounded so the summarization call itself
     * fits the summarizing model: per-entry clip plus an overall cap that drops the
     * OLDEST entries first (the tail already preserves recency; among the head, newer
     * context matters more).
     */
    private String renderTranscript(List<ChatHistoryEntry> head,
                                    ChatContextBudgetService.ContextBudget budget) {
        long capChars = (long) (budget.inputBudgetTokens() * ChatContextBudgetService.CHARS_PER_TOKEN * 0.6);
        List<String> lines = new ArrayList<>(head.size());
        long total = 0;
        for (int i = head.size() - 1; i >= 0; i--) {
            ChatHistoryEntry entry = head.get(i);
            if (entry == null || entry.getContent() == null || entry.getContent().isBlank()) continue;
            String line = roleLabel(entry.getRole()) + ": " + clip(entry.getContent(), TRANSCRIPT_ENTRY_CAP_CHARS);
            if (total + line.length() > capChars && !lines.isEmpty()) {
                lines.add("(earlier messages omitted)");
                break;
            }
            total += line.length();
            lines.add(line);
        }
        StringBuilder transcript = new StringBuilder();
        for (int i = lines.size() - 1; i >= 0; i--) {
            transcript.append(lines.get(i)).append('\n');
        }
        return transcript.toString().trim();
    }

    /**
     * Deterministic no-LLM fallback: clipped role-labeled digest of the head. Filled
     * newest-first so the cap drops the OLDEST head entries, then re-ordered.
     */
    private String renderDigest(List<ChatHistoryEntry> head) {
        List<String> lines = new ArrayList<>(head.size());
        long total = 0;
        for (int i = head.size() - 1; i >= 0; i--) {
            ChatHistoryEntry entry = head.get(i);
            if (entry == null || entry.getContent() == null || entry.getContent().isBlank()) continue;
            String line = roleLabel(entry.getRole()) + ": " + clip(entry.getContent(), DIGEST_ENTRY_CAP_CHARS);
            if (total + line.length() > DIGEST_CAP_CHARS && !lines.isEmpty()) {
                lines.add("(earliest messages omitted)");
                break;
            }
            total += line.length();
            lines.add(line);
        }
        StringBuilder digest = new StringBuilder();
        for (int i = lines.size() - 1; i >= 0; i--) {
            digest.append(lines.get(i)).append('\n');
        }
        return digest.toString().trim();
    }

    private static String roleLabel(String role) {
        if (role == null || role.isBlank()) return "Message";
        String normalized = role.toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "user" -> "User";
            case "assistant" -> "Assistant";
            case "system" -> "Note";
            default -> role;
        };
    }

    private static String clip(String text, int maxChars) {
        String flat = text.strip();
        return flat.length() <= maxChars ? flat : flat.substring(0, maxChars) + "…";
    }
}
