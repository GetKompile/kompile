/*
 *   Copyright 2025 Kompile Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ai.kompile.cli.main.chat.format;

import ai.kompile.cli.main.chat.ChatHistory;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * Deterministic compaction for cross-agent conversation resume.
 * <p>
 * Some target agents submit every resumed turn as a separate API input item.
 * Very long imported conversations can exceed provider array limits before the
 * target agent has a chance to compact locally. This compactor collapses older
 * history into one resume-context turn and preserves a bounded recent window.
 */
public final class ConversationCompactor {

    public static final int DEFAULT_MAX_CHARS = 60_000;
    public static final int DEFAULT_RECENT_TURNS = 40;

    private static final int MIN_MAX_CHARS = 4_000;
    private static final int MAX_RECENT_TURNS = 200;
    private static final int OLDER_PREVIEW_CHARS = 360;

    private ConversationCompactor() {
    }

    public static CompactResult compact(List<ChatHistory.Turn> turns) {
        return compact(turns, DEFAULT_MAX_CHARS, DEFAULT_RECENT_TURNS);
    }

    public static CompactResult compact(List<ChatHistory.Turn> turns,
                                        int maxChars,
                                        int recentTurns) {
        List<ChatHistory.Turn> source = normalize(turns);
        int originalTurnCount = source.size();
        int charsBefore = totalContentChars(source);

        if (source.isEmpty()) {
            return new CompactResult(List.of(), 0, 0, 0, 0, 0, false);
        }

        int effectiveMaxChars = maxChars > 0 ? Math.max(MIN_MAX_CHARS, maxChars) : DEFAULT_MAX_CHARS;
        int effectiveRecentTurns = recentTurns > 0
                ? clamp(recentTurns, 1, MAX_RECENT_TURNS)
                : DEFAULT_RECENT_TURNS;

        if (source.size() <= effectiveRecentTurns + 1 && charsBefore <= effectiveMaxChars) {
            return new CompactResult(source, originalTurnCount, source.size(),
                    charsBefore, charsBefore, source.size(), false);
        }

        int recentStart = Math.max(0, source.size() - effectiveRecentTurns);
        List<ChatHistory.Turn> older = source.subList(0, recentStart);
        List<ChatHistory.Turn> recent = source.subList(recentStart, source.size());

        int summaryBudget = Math.max(1_200, Math.min(effectiveMaxChars / 3, 12_000));
        String summary = buildSummary(source, older, summaryBudget);
        int remainingBudget = Math.max(800, effectiveMaxChars - summary.length());

        List<ChatHistory.Turn> compacted = new ArrayList<>();
        compacted.add(new ChatHistory.Turn("user", summary));
        compacted.addAll(fitRecentTurns(recent, remainingBudget));

        int charsAfter = totalContentChars(compacted);
        return new CompactResult(List.copyOf(compacted), originalTurnCount, compacted.size(),
                charsBefore, charsAfter, Math.max(0, compacted.size() - 1), true);
    }

    private static List<ChatHistory.Turn> normalize(List<ChatHistory.Turn> turns) {
        if (turns == null || turns.isEmpty()) {
            return List.of();
        }

        List<ChatHistory.Turn> normalized = new ArrayList<>();
        for (ChatHistory.Turn turn : turns) {
            if (turn == null) {
                continue;
            }
            String role = "assistant".equals(turn.role()) ? "assistant" : "user";
            String content = turn.content() == null ? "" : turn.content();
            if (!content.isBlank()) {
                normalized.add(new ChatHistory.Turn(role, content));
            }
        }
        return List.copyOf(normalized);
    }

    private static String buildSummary(List<ChatHistory.Turn> allTurns,
                                       List<ChatHistory.Turn> olderTurns,
                                       int budget) {
        StringBuilder sb = new StringBuilder();
        sb.append("This is a compacted cross-agent resume context.\n");
        sb.append("The original transcript had ").append(allTurns.size()).append(" turn(s). ");
        sb.append(olderTurns.size()).append(" older turn(s) were collapsed here; ");
        sb.append("the most recent turns follow this message as native transcript turns.\n\n");
        sb.append("Use this context to continue the prior work. Treat the recent turns after ");
        sb.append("this message as the most reliable source of current state.\n\n");

        ChatHistory.Turn firstUser = firstRole(allTurns, "user");
        if (firstUser != null) {
            sb.append("Initial user request:\n");
            appendBullet(sb, firstUser.content(), OLDER_PREVIEW_CHARS);
            sb.append("\n");
        }

        appendHighlights(sb, olderTurns, "user", "Older user requests:", 12, budget);
        appendHighlights(sb, olderTurns, "assistant", "Older assistant progress:", 12, budget);

        if (sb.length() > budget) {
            return truncateTail(sb.toString(), budget);
        }
        return sb.toString().stripTrailing();
    }

    private static void appendHighlights(StringBuilder sb,
                                         List<ChatHistory.Turn> turns,
                                         String role,
                                         String heading,
                                         int maxItems,
                                         int budget) {
        int count = 0;
        int headingLength = sb.length();
        sb.append(heading).append("\n");
        for (ChatHistory.Turn turn : turns) {
            if (!role.equals(turn.role())) {
                continue;
            }
            appendBullet(sb, turn.content(), OLDER_PREVIEW_CHARS);
            count++;
            if (count >= maxItems || sb.length() >= budget) {
                break;
            }
        }
        if (count == 0) {
            sb.setLength(headingLength);
        } else {
            sb.append("\n");
        }
    }

    private static void appendBullet(StringBuilder sb, String text, int maxChars) {
        sb.append("- ").append(cleanWhitespace(truncateTail(text, maxChars))).append("\n");
    }

    private static List<ChatHistory.Turn> fitRecentTurns(List<ChatHistory.Turn> recent, int budget) {
        ArrayDeque<ChatHistory.Turn> kept = new ArrayDeque<>();
        int used = 0;

        for (int i = recent.size() - 1; i >= 0; i--) {
            ChatHistory.Turn turn = recent.get(i);
            int overhead = turn.role().length() + 16;
            int remaining = budget - used - overhead;

            if (remaining <= 0 && !kept.isEmpty()) {
                break;
            }

            String content = turn.content();
            boolean mustKeepOne = kept.isEmpty();
            if (content.length() + overhead <= budget - used || mustKeepOne) {
                if (content.length() > remaining && remaining > 120) {
                    content = truncateTail(content, remaining);
                } else if (remaining <= 120 && content.length() > 120) {
                    content = truncateTail(content, 120);
                }
                kept.addFirst(new ChatHistory.Turn(turn.role(), content));
                used += content.length() + overhead;
            } else {
                break;
            }
        }

        return List.copyOf(kept);
    }

    private static ChatHistory.Turn firstRole(List<ChatHistory.Turn> turns, String role) {
        for (ChatHistory.Turn turn : turns) {
            if (role.equals(turn.role())) {
                return turn;
            }
        }
        return null;
    }

    private static int totalContentChars(List<ChatHistory.Turn> turns) {
        int total = 0;
        for (ChatHistory.Turn turn : turns) {
            total += turn.content() != null ? turn.content().length() : 0;
        }
        return total;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static String cleanWhitespace(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        return text.replaceAll("\\s+", " ").trim();
    }

    private static String truncateTail(String text, int maxChars) {
        if (text == null) {
            return "";
        }
        if (maxChars <= 0 || text.length() <= maxChars) {
            return text;
        }
        if (maxChars <= 32) {
            return text.substring(0, maxChars);
        }
        return text.substring(0, maxChars - 31) + "... (truncated for resume)";
    }

    public record CompactResult(List<ChatHistory.Turn> turns,
                                int originalTurnCount,
                                int compactedTurnCount,
                                int charsBefore,
                                int charsAfter,
                                int preservedRecentTurns,
                                boolean compacted) {
    }
}
