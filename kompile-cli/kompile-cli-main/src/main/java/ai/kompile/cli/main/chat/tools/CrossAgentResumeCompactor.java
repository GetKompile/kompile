/*
 *   Copyright 2025 Kompile Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ai.kompile.cli.main.chat.tools;

import ai.kompile.cli.main.chat.ChatHistory;
import ai.kompile.cli.main.chat.format.ConversationExporter;
import ai.kompile.core.llm.ModelContextWindows;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Target-aware transcript compaction for cross-agent native resume.
 *
 * <p>The exported native session must fit the target model, not the source model
 * that produced the transcript. This class performs deterministic staged
 * compaction before export: preserve recent turns, summarize older turns in
 * chunks, then recursively compact the summaries until the result fits.</p>
 */
public final class CrossAgentResumeCompactor {

    private static final double CHARS_PER_TOKEN = 4.0;
    private static final int MIN_EXPORT_BUDGET_TOKENS = 2_048;
    private static final int MAX_RECENT_TURNS = 8;
    private static final int MIN_RECENT_TURNS = 2;
    private static final int CHUNK_TARGET_TOKENS = 16_000;
    private static final int MAX_COMPACTION_STAGES = 8;

    private CrossAgentResumeCompactor() {
    }

    public static TargetBudget targetBudget(String targetAgent, String sourceAgent, Path workingDirectory) {
        ConversationExporter.ResolvedTargetModel targetModel =
                ConversationExporter.resolveTargetModel(targetAgent, sourceAgent, workingDirectory);
        String modelId = targetModel.modelId();
        int contextWindow = ModelContextWindows.getContextWindow(modelId);
        int maxOutput = ModelContextWindows.getMaxOutputTokens(modelId);
        if (targetAgent != null && "opencode".equalsIgnoreCase(targetAgent)) {
            var liveMetadata = OpenCodeModelMetadataResolver.resolve(targetModel.providerId(), modelId);
            if (liveMetadata.isPresent()) {
                OpenCodeModelMetadataResolver.ModelMetadata metadata = liveMetadata.get();
                contextWindow = metadata.contextWindow();
                maxOutput = metadata.maxOutputTokens();
            }
        }
        int safety = Math.min(Math.max(8_192, contextWindow / 10), 40_000);
        int reserved = Math.min(contextWindow / 2, maxOutput + safety);
        int exportBudget = Math.max(MIN_EXPORT_BUDGET_TOKENS, contextWindow - reserved);
        return new TargetBudget(modelId, contextWindow, maxOutput, exportBudget);
    }

    public static Result compactToFit(List<ChatHistory.Turn> turns, TargetBudget budget) {
        if (turns == null || turns.isEmpty()) {
            return new Result(List.of(), false, 0, 0, budget, 0, 0);
        }

        int tokensBefore = estimateTokens(turns);
        if (tokensBefore <= budget.exportTokenBudget()) {
            return new Result(List.copyOf(turns), false, tokensBefore, tokensBefore, budget, 0, 0);
        }

        int recentCount = chooseRecentTurnCount(turns, budget.exportTokenBudget());
        int splitIndex = Math.max(0, turns.size() - recentCount);
        List<IndexedTurn> older = indexed(turns.subList(0, splitIndex), 0);
        List<ChatHistory.Turn> recent = new ArrayList<>(turns.subList(splitIndex, turns.size()));

        int tailBudget = Math.max(512, Math.min(budget.exportTokenBudget() / 3, 40_000));
        recent = fitTurnsToBudget(recent, tailBudget, true);

        int recentTokens = estimateTokens(recent);
        if (recentTokens >= budget.exportTokenBudget()) {
            recent = fitTurnsToBudget(recent, Math.max(512, budget.exportTokenBudget() - 256), true);
            recentTokens = estimateTokens(recent);
        }

        int summaryBudget = Math.max(256, budget.exportTokenBudget() - recentTokens - 256);
        String summary = summarizeOlderTurns(older, turns.size(), budget, summaryBudget);
        int stages = 1;

        List<ChatHistory.Turn> compacted = compose(summary, recent);
        while (estimateTokens(compacted) > budget.exportTokenBudget()
                && stages < MAX_COMPACTION_STAGES
                && summary.length() > 512) {
            int availableSummaryChars = Math.max(512,
                    (budget.exportTokenBudget() - estimateTokens(recent) - 256) * (int) CHARS_PER_TOKEN);
            summary = compactSummaryText(summary, availableSummaryChars, stages + 1);
            compacted = compose(summary, recent);
            stages++;
        }

        if (estimateTokens(compacted) > budget.exportTokenBudget()) {
            int availableSummaryChars = Math.max(0,
                    (budget.exportTokenBudget() - estimateTokens(recent) - 128) * (int) CHARS_PER_TOKEN);
            summary = truncatePreservingEnds(summary, availableSummaryChars);
            compacted = compose(summary, recent);
        }

        if (estimateTokens(compacted) > budget.exportTokenBudget()) {
            int remainingForRecent = Math.max(512, budget.exportTokenBudget() - estimateTextTokens(summary) - 128);
            recent = fitTurnsToBudget(recent, remainingForRecent, false);
            compacted = compose(summary, recent);
        }

        if (estimateTokens(compacted) > budget.exportTokenBudget()) {
            compacted = forceFit(compacted, budget.exportTokenBudget());
        }

        int tokensAfter = estimateTokens(compacted);
        return new Result(compacted, true, tokensBefore, tokensAfter, budget, stages, older.size());
    }

    static int estimateTokens(List<ChatHistory.Turn> turns) {
        int total = 0;
        for (ChatHistory.Turn turn : turns) {
            total += estimateTextTokens(turn.content());
        }
        return total;
    }

    private static int estimateTextTokens(String text) {
        if (text == null || text.isEmpty()) return 0;
        return Math.max(1, (int) Math.ceil(text.length() / CHARS_PER_TOKEN));
    }

    private static int chooseRecentTurnCount(List<ChatHistory.Turn> turns, int budgetTokens) {
        int max = Math.min(MAX_RECENT_TURNS, turns.size());
        int min = Math.min(MIN_RECENT_TURNS, turns.size());
        int tailBudget = Math.max(512, Math.min(budgetTokens / 3, 40_000));
        for (int count = max; count >= min; count--) {
            List<ChatHistory.Turn> tail = turns.subList(turns.size() - count, turns.size());
            if (estimateTokens(tail) <= tailBudget) {
                return count;
            }
        }
        return min;
    }

    private static List<IndexedTurn> indexed(List<ChatHistory.Turn> turns, int startIndex) {
        List<IndexedTurn> indexed = new ArrayList<>();
        for (int i = 0; i < turns.size(); i++) {
            indexed.add(new IndexedTurn(startIndex + i + 1, turns.get(i)));
        }
        return indexed;
    }

    private static String summarizeOlderTurns(List<IndexedTurn> older,
                                              int originalTurnCount,
                                              TargetBudget budget,
                                              int summaryBudgetTokens) {
        StringBuilder summary = new StringBuilder();
        summary.append("[Compacted cross-agent resume context]\n");
        summary.append("Target model: ").append(nullToUnknown(budget.modelId())).append("\n");
        summary.append("Target context: ").append(budget.contextWindow()).append(" tokens; ");
        summary.append("export budget: ").append(budget.exportTokenBudget()).append(" tokens.\n");
        summary.append("Original transcript turns: ").append(originalTurnCount).append(". ");
        summary.append("Older turns were compacted in chunks; recent turns follow verbatim where possible.\n\n");

        if (older.isEmpty()) {
            summary.append("No older turns required compaction.\n");
            return summary.toString();
        }

        List<List<IndexedTurn>> chunks = chunk(older);
        int bodyBudgetChars = Math.max(512, summaryBudgetTokens * (int) CHARS_PER_TOKEN - summary.length());
        int perChunkChars = Math.max(320, Math.min(2_400, bodyBudgetChars / Math.max(1, chunks.size())));

        for (int i = 0; i < chunks.size(); i++) {
            summary.append(summarizeChunk(chunks.get(i), i + 1, chunks.size(), perChunkChars));
            if (summary.length() >= summaryBudgetTokens * CHARS_PER_TOKEN) {
                summary.append("\n... remaining compacted chunks omitted by target budget.\n");
                break;
            }
        }
        return summary.toString();
    }

    private static List<List<IndexedTurn>> chunk(List<IndexedTurn> turns) {
        List<List<IndexedTurn>> chunks = new ArrayList<>();
        List<IndexedTurn> current = new ArrayList<>();
        int currentTokens = 0;
        for (IndexedTurn turn : turns) {
            int turnTokens = estimateTextTokens(turn.turn().content());
            if (!current.isEmpty() && currentTokens + turnTokens > CHUNK_TARGET_TOKENS) {
                chunks.add(current);
                current = new ArrayList<>();
                currentTokens = 0;
            }
            current.add(turn);
            currentTokens += turnTokens;
        }
        if (!current.isEmpty()) {
            chunks.add(current);
        }
        return chunks;
    }

    private static String summarizeChunk(List<IndexedTurn> chunk, int chunkIndex, int chunkCount, int maxChars) {
        int userTurns = 0;
        int assistantTurns = 0;
        int estimatedTokens = 0;
        for (IndexedTurn turn : chunk) {
            if ("assistant".equalsIgnoreCase(turn.turn().role())) assistantTurns++;
            else userTurns++;
            estimatedTokens += estimateTextTokens(turn.turn().content());
        }

        StringBuilder sb = new StringBuilder();
        sb.append("## Chunk ").append(chunkIndex).append(" of ").append(chunkCount)
                .append(" (turns ").append(chunk.get(0).index()).append("-")
                .append(chunk.get(chunk.size() - 1).index()).append(", ~")
                .append(estimatedTokens).append(" tokens)\n");
        sb.append("- Roles: ").append(userTurns).append(" user, ")
                .append(assistantTurns).append(" assistant.\n");

        int remaining = Math.max(160, maxChars - sb.length() - 64);
        int perTurnChars = Math.max(96, Math.min(480, remaining / Math.max(1, chunk.size())));
        int emitted = 0;
        for (IndexedTurn turn : chunk) {
            String line = "- Turn " + turn.index() + " " + normalizeRole(turn.turn().role()) + ": "
                    + compactWhitespace(turn.turn().content());
            line = truncatePreservingEnds(line, perTurnChars);
            if (sb.length() + line.length() + 1 > maxChars) {
                break;
            }
            sb.append(line).append("\n");
            emitted++;
        }
        if (emitted < chunk.size()) {
            sb.append("- ").append(chunk.size() - emitted)
                    .append(" additional turn(s) omitted from this chunk summary.\n");
        }
        return sb.toString();
    }

    private static String compactSummaryText(String summary, int maxChars, int stage) {
        if (summary == null || summary.length() <= maxChars) {
            return summary;
        }
        String[] lines = summary.split("\\R");
        StringBuilder compacted = new StringBuilder();
        compacted.append("[Stage ").append(stage).append(" compacted summary]\n");

        int omitted = 0;
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) continue;
            if (isHighSignalLine(trimmed) || compacted.length() < maxChars / 3) {
                String capped = truncatePreservingEnds(trimmed, 220);
                if (compacted.length() + capped.length() + 1 > maxChars) {
                    break;
                }
                compacted.append(capped).append("\n");
            } else {
                omitted++;
            }
        }
        if (omitted > 0 && compacted.length() + 32 < maxChars) {
            compacted.append("... omitted ").append(omitted).append(" lower-signal summary lines.\n");
        }
        if (compacted.length() > maxChars) {
            return truncatePreservingEnds(compacted.toString(), maxChars);
        }
        return compacted.toString();
    }

    private static boolean isHighSignalLine(String line) {
        String lower = line.toLowerCase(Locale.ROOT);
        return lower.contains("error")
                || lower.contains("failed")
                || lower.contains("fixed")
                || lower.contains("todo")
                || lower.contains("pending")
                || lower.contains("command")
                || lower.contains("file")
                || lower.contains("/")
                || lower.contains(".java")
                || lower.contains(".ts")
                || lower.contains(".py")
                || lower.contains(".md")
                || lower.contains("target model")
                || lower.contains("original transcript");
    }

    private static List<ChatHistory.Turn> compose(String summary, List<ChatHistory.Turn> recent) {
        List<ChatHistory.Turn> out = new ArrayList<>();
        if (summary != null && !summary.isBlank()) {
            out.add(new ChatHistory.Turn("user", summary, null));
        }
        out.addAll(recent);
        return out;
    }

    private static List<ChatHistory.Turn> fitTurnsToBudget(List<ChatHistory.Turn> turns,
                                                           int budgetTokens,
                                                           boolean preserveTurnCount) {
        if (turns.isEmpty() || estimateTokens(turns) <= budgetTokens) {
            return new ArrayList<>(turns);
        }
        List<ChatHistory.Turn> working = new ArrayList<>(turns);
        while (!preserveTurnCount && working.size() > 1 && estimateTokens(working) > budgetTokens) {
            working.remove(0);
        }
        if (estimateTokens(working) <= budgetTokens) {
            return working;
        }
        int maxChars = Math.max(160, (budgetTokens * (int) CHARS_PER_TOKEN) / Math.max(1, working.size()));
        List<ChatHistory.Turn> fitted = new ArrayList<>();
        for (ChatHistory.Turn turn : working) {
            String content = truncatePreservingEnds(turn.content(), maxChars);
            fitted.add(new ChatHistory.Turn(turn.role(), content, null));
        }
        return fitted;
    }

    private static List<ChatHistory.Turn> forceFit(List<ChatHistory.Turn> turns, int budgetTokens) {
        if (estimateTokens(turns) <= budgetTokens) return turns;
        int maxChars = Math.max(80, (budgetTokens * (int) CHARS_PER_TOKEN) / Math.max(1, turns.size()));
        List<ChatHistory.Turn> fitted = new ArrayList<>();
        for (ChatHistory.Turn turn : turns) {
            fitted.add(new ChatHistory.Turn(turn.role(), truncatePreservingEnds(turn.content(), maxChars), null));
        }
        while (fitted.size() > 1 && estimateTokens(fitted) > budgetTokens) {
            fitted.remove(1);
        }
        if (!fitted.isEmpty() && estimateTokens(fitted) > budgetTokens) {
            ChatHistory.Turn only = fitted.get(0);
            fitted.set(0, new ChatHistory.Turn(only.role(),
                    truncatePreservingEnds(only.content(), budgetTokens * (int) CHARS_PER_TOKEN), null));
        }
        return fitted;
    }

    private static String truncatePreservingEnds(String text, int maxChars) {
        if (text == null) return "";
        if (maxChars <= 0) return "";
        if (text.length() <= maxChars) return text;
        if (maxChars < 64) return text.substring(0, maxChars);
        int head = Math.max(24, (int) (maxChars * 0.65));
        int tail = Math.max(16, maxChars - head - 48);
        if (head + tail >= text.length()) return text.substring(0, maxChars);
        return text.substring(0, head)
                + "\n... (compacted; omitted " + (text.length() - head - tail) + " chars) ...\n"
                + text.substring(text.length() - tail);
    }

    private static String compactWhitespace(String text) {
        if (text == null) return "";
        return text.replaceAll("\\s+", " ").trim();
    }

    private static String normalizeRole(String role) {
        if (role == null || role.isBlank()) return "UNKNOWN";
        return role.toUpperCase(Locale.ROOT);
    }

    private static String nullToUnknown(String value) {
        return value == null || value.isBlank() ? "unknown" : value;
    }

    private record IndexedTurn(int index, ChatHistory.Turn turn) {}

    public record TargetBudget(String modelId, int contextWindow, int maxOutputTokens, int exportTokenBudget) {}

    public record Result(List<ChatHistory.Turn> turns,
                         boolean compacted,
                         int tokensBefore,
                         int tokensAfter,
                         TargetBudget targetBudget,
                         int stages,
                         int summarizedTurns) {}
}
