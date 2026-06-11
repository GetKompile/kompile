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

package ai.kompile.cli.main.chat.harness;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Layer 2: Zero-LLM heuristic escape detection.
 * Detects refusals, empty outputs, tool loops, abandoned tasks, off-topic
 * responses, deadlocks (stuck agents), infinite loops, low-effort responses,
 * and thinking loops.
 * <p>
 * Stateful: maintains a ring buffer of recent turn fingerprints to detect
 * cross-turn repetition patterns (deadlock/stuck agent). A single instance
 * should be used per session/agent.
 * <p>
 * All detection is sub-millisecond string/int pattern matching — no I/O.
 */
public class EscapeDetector {

    public enum EscapeType {
        NONE,
        EXPLICIT_REFUSAL,
        EMPTY_OUTPUT,
        TRIVIALLY_SHORT,
        NO_TOOLS_USED,
        MAX_STEPS_ABANDONED,
        TOOL_LOOP,
        OFF_TOPIC,
        /** Same tool called 3+ times with identical arguments within a single turn. */
        TOOL_ARGS_LOOP,
        /** Agent producing near-identical output across consecutive turns. */
        STUCK_LOOP,
        /** Identical tool call sequence detected across consecutive turns. */
        TOOL_SEQUENCE_LOOP,
        /** Low-effort response — filler/placeholder without real substance. */
        LOW_EFFORT,
        /** Circular reasoning detected in extended thinking content. */
        THINKING_LOOP
    }

    public record EscapeResult(boolean hasEscape, EscapeType type, String detail, float penalty) {
        public static EscapeResult none() {
            return new EscapeResult(false, EscapeType.NONE, "", 0f);
        }

        public boolean isHardEscape() {
            return type == EscapeType.EXPLICIT_REFUSAL || type == EscapeType.EMPTY_OUTPUT;
        }
    }

    // ── Refusal patterns ─────────────────────────────────────────────

    private static final List<Pattern> REFUSAL_PATTERNS = List.of(
            Pattern.compile("\\bI can'?t\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bI'?m unable\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bI cannot\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bI don'?t have access\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bI'?m not able\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bI must decline\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bI'?m sorry,? but I\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bunable to (help|assist|complete|perform|do)\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bbeyond my (capabilities|ability)\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bI (will|need to) refrain\\b", Pattern.CASE_INSENSITIVE)
    );

    // ── Low-effort patterns (provider-specific filler) ───────────────

    /** Anthropic-style filler: promises action but produces no substance. */
    private static final List<Pattern> LOW_EFFORT_PATTERNS_ANTHROPIC = List.of(
            Pattern.compile("I'll help you with that", Pattern.CASE_INSENSITIVE),
            Pattern.compile("I'd be happy to help", Pattern.CASE_INSENSITIVE),
            Pattern.compile("Let me (help|assist) you", Pattern.CASE_INSENSITIVE),
            Pattern.compile("I'll (take care|handle|work on) (of )?that", Pattern.CASE_INSENSITIVE),
            Pattern.compile("Sure!? I can do that", Pattern.CASE_INSENSITIVE)
    );

    /** OpenAI-style filler. */
    private static final List<Pattern> LOW_EFFORT_PATTERNS_OPENAI = List.of(
            Pattern.compile("Here'?s (a |the )?(?:summary|overview|breakdown)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("Absolutely!? (Let me|I'll|Here)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("Of course!? (Let me|I'll|Here)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("Great question!", Pattern.CASE_INSENSITIVE)
    );

    /** Provider-agnostic filler. */
    private static final List<Pattern> LOW_EFFORT_PATTERNS_GENERIC = List.of(
            Pattern.compile("^(Sure|Okay|Alright|Got it)[.!]?\\s*$", Pattern.CASE_INSENSITIVE | Pattern.MULTILINE),
            Pattern.compile("(?s)^\\s*(Sure|Okay|Alright|Of course|Absolutely|Great)[.!,]?\\s*" +
                    "(Let me|I'll|I will).*$")
    );

    // ── Thinking loop patterns ───────────────────────────────────────

    /** Patterns indicating circular reasoning in extended thinking. */
    private static final List<Pattern> THINKING_LOOP_PATTERNS = List.of(
            Pattern.compile("(?:as I (said|mentioned|noted) (before|earlier|above|previously))", Pattern.CASE_INSENSITIVE),
            Pattern.compile("(?:going back to (my|the) (earlier|previous|original) (thought|point|idea))", Pattern.CASE_INSENSITIVE),
            Pattern.compile("(?:I keep coming back to)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("(?:I('m| am) going in circles)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("(?:let me try (again|a different|another) approach)", Pattern.CASE_INSENSITIVE)
    );

    // ── Task keyword vocabulary ──────────────────────────────────────

    private static final Map<String, Set<String>> TASK_KEYWORDS = Map.of(
            "code-review", Set.of("diff", "change", "function", "class", "method", "bug", "issue",
                    "review", "commit", "fix", "refactor", "variable", "line"),
            "planning", Set.of("step", "approach", "design", "architecture", "plan", "phase",
                    "implement", "strategy", "component", "module"),
            "research", Set.of("found", "source", "according", "result", "reference",
                    "documentation", "article", "link"),
            "exploration", Set.of("file", "class", "method", "directory", "package", "found",
                    "search", "grep", "match", "path"),
            "incident-response", Set.of("incident", "remediation", "escalation", "approval",
                    "checkpoint", "interrupt", "severity", "runbook", "pagerduty",
                    "on-call", "rca", "root cause", "sla", "rollback")
    );

    private static final Set<String> TOOL_REQUIRED_TASK_TYPES = Set.of(
            "code-review", "exploration", "indexing", "incident-response"
    );

    // ── Thresholds ───────────────────────────────────────────────────

    private static final int EMPTY_THRESHOLD = 30;
    private static final int SHORT_THRESHOLD = 150;
    private static final int TOOL_LOOP_THRESHOLD = 3;
    /** Minimum output length to consider for low-effort check (shorter outputs
     *  are already caught by TRIVIALLY_SHORT). */
    private static final int LOW_EFFORT_MAX_LENGTH = 300;
    /** Jaccard similarity threshold for near-duplicate output detection. */
    private static final double SIMILARITY_THRESHOLD = 0.75;
    /** How many recent turn fingerprints to keep for cross-turn detection. */
    private static final int HISTORY_SIZE = 8;
    /** Minimum consecutive similar turns to flag as stuck. */
    private static final int STUCK_TURN_THRESHOLD = 3;
    /** Minimum repeated thinking paragraphs to flag as thinking loop. */
    private static final int THINKING_LOOP_PARAGRAPH_THRESHOLD = 3;

    // ── Cross-turn state (ring buffer) ───────────────────────────────

    private final Deque<TurnFingerprint> turnHistory = new ArrayDeque<>();

    /** Fingerprint of a single turn for cross-turn comparison. */
    private record TurnFingerprint(
            /** Bag-of-words from output (lowercased, split on whitespace). */
            Set<String> outputWords,
            /** Ordered list of tool invocations as "name:argsHash" strings. */
            List<String> toolSequence,
            /** Truncated output for exact-match checks. */
            String outputPrefix
    ) {}

    // ── Public API ───────────────────────────────────────────────────

    /**
     * Detect escape conditions from turn metrics. Evaluated in priority order,
     * short-circuits on first match.
     * <p>
     * This method is stateful: it records the turn fingerprint for
     * cross-turn deadlock/loop detection. Call once per completed turn.
     */
    public EscapeResult detect(TurnMetrics metrics, String taskType) {
        String output = metrics.getAgentOutput();

        // 1. Empty output — hard escape
        if (output == null || output.isBlank() || output.trim().length() < EMPTY_THRESHOLD) {
            recordTurn(metrics);
            return new EscapeResult(true, EscapeType.EMPTY_OUTPUT,
                    "Output is empty or trivially short (" +
                            (output == null ? 0 : output.trim().length()) + " chars)",
                    0f);
        }

        // 2. Explicit refusal — hard escape
        if (isRefusal(output)) {
            recordTurn(metrics);
            return new EscapeResult(true, EscapeType.EXPLICIT_REFUSAL,
                    "Agent explicitly refused the task", 0f);
        }

        // 3. Max steps abandoned
        if (metrics.isHitMaxSteps()) {
            recordTurn(metrics);
            return new EscapeResult(true, EscapeType.MAX_STEPS_ABANDONED,
                    "Agent exhausted max steps (" + metrics.getAgenticSteps() + ")", 1.5f);
        }

        // 4. Tool args loop — same tool+args called 3+ times in a single turn
        EscapeResult argsLoop = detectToolArgsLoop(metrics);
        if (argsLoop != null) {
            recordTurn(metrics);
            return argsLoop;
        }

        // 5. Stuck loop — near-identical output across consecutive turns
        EscapeResult stuck = detectStuckLoop(metrics);
        if (stuck != null) {
            recordTurn(metrics);
            return stuck;
        }

        // 6. Tool sequence loop — identical tool call sequence across consecutive turns
        EscapeResult seqLoop = detectToolSequenceLoop(metrics);
        if (seqLoop != null) {
            recordTurn(metrics);
            return seqLoop;
        }

        // 7. Thinking loop — circular reasoning in extended thinking
        if (metrics.hasThinking()) {
            EscapeResult thinkLoop = detectThinkingLoop(metrics.getThinkingText());
            if (thinkLoop != null) {
                recordTurn(metrics);
                return thinkLoop;
            }
        }

        // 8. Low-effort — filler without substance
        EscapeResult lowEffort = detectLowEffort(output, metrics.getProvider());
        if (lowEffort != null) {
            recordTurn(metrics);
            return lowEffort;
        }

        // 9. Trivially short (for non-trivial tasks)
        if (output.trim().length() < SHORT_THRESHOLD && !"general".equals(taskType)) {
            recordTurn(metrics);
            return new EscapeResult(true, EscapeType.TRIVIALLY_SHORT,
                    "Output suspiciously short (" + output.trim().length() + " chars) for " + taskType,
                    1.5f);
        }

        // 10. No tools used when task requires them
        if (TOOL_REQUIRED_TASK_TYPES.contains(taskType) && metrics.getToolCallsTotal() == 0) {
            recordTurn(metrics);
            return new EscapeResult(true, EscapeType.NO_TOOLS_USED,
                    "No tool calls for tool-requiring task type '" + taskType + "'", 1.0f);
        }

        // 11. Tool name loop detection (original — same tool name 3+ times)
        if (hasToolLoop(metrics)) {
            recordTurn(metrics);
            return new EscapeResult(true, EscapeType.TOOL_LOOP,
                    "Same tool called " + TOOL_LOOP_THRESHOLD + "+ times (possible loop)", 0.8f);
        }

        // 12. Off-topic detection
        if (isOffTopic(output, taskType)) {
            recordTurn(metrics);
            return new EscapeResult(true, EscapeType.OFF_TOPIC,
                    "Output has no overlap with " + taskType + " vocabulary", 0.5f);
        }

        recordTurn(metrics);
        return EscapeResult.none();
    }

    /**
     * Reset cross-turn history. Call when switching agents or sessions.
     */
    public void resetHistory() {
        turnHistory.clear();
    }

    /**
     * Get the number of turns in the cross-turn history buffer.
     */
    public int getHistorySize() {
        return turnHistory.size();
    }

    // ── Detection methods ────────────────────────────────────────────

    private boolean isRefusal(String output) {
        if (output.length() > 500) return false;
        for (Pattern p : REFUSAL_PATTERNS) {
            if (p.matcher(output).find()) {
                return true;
            }
        }
        return false;
    }

    private boolean hasToolLoop(TurnMetrics metrics) {
        Map<String, Integer> breakdown = metrics.getToolCallBreakdown();
        if (breakdown.isEmpty()) return false;
        for (int count : breakdown.values()) {
            if (count >= TOOL_LOOP_THRESHOLD) {
                return true;
            }
        }
        return false;
    }

    /**
     * Detect same tool called with identical arguments 3+ times in a single turn.
     * Uses the ToolInvocation list from TurnMetrics.
     */
    private EscapeResult detectToolArgsLoop(TurnMetrics metrics) {
        List<TurnMetrics.ToolInvocation> invocations = metrics.getToolInvocations();
        if (invocations == null || invocations.size() < TOOL_LOOP_THRESHOLD) return null;

        // Count (name + argsFingerprint) pairs
        Map<String, Integer> callCounts = new HashMap<>();
        String loopKey = null;
        for (TurnMetrics.ToolInvocation inv : invocations) {
            String key = inv.toolName() + ":" + inv.argsFingerprint();
            int count = callCounts.merge(key, 1, Integer::sum);
            if (count >= TOOL_LOOP_THRESHOLD) {
                loopKey = key;
            }
        }

        if (loopKey != null) {
            return new EscapeResult(true, EscapeType.TOOL_ARGS_LOOP,
                    "Identical tool call repeated " + callCounts.get(loopKey) + " times: " + loopKey,
                    1.0f);
        }
        return null;
    }

    /**
     * Detect agent producing near-identical output across consecutive turns.
     * Uses Jaccard similarity on bag-of-words.
     */
    private EscapeResult detectStuckLoop(TurnMetrics metrics) {
        if (turnHistory.size() < STUCK_TURN_THRESHOLD - 1) return null;

        String output = metrics.getAgentOutput();
        if (output == null || output.isBlank()) return null;

        Set<String> currentWords = bagOfWords(output);
        if (currentWords.size() < 5) return null; // too short for meaningful comparison

        int similarCount = 0;
        Iterator<TurnFingerprint> it = turnHistory.descendingIterator();
        while (it.hasNext() && similarCount < STUCK_TURN_THRESHOLD - 1) {
            TurnFingerprint prev = it.next();
            double sim = jaccardSimilarity(currentWords, prev.outputWords);
            if (sim >= SIMILARITY_THRESHOLD) {
                similarCount++;
            } else {
                break; // must be consecutive
            }
        }

        if (similarCount >= STUCK_TURN_THRESHOLD - 1) {
            return new EscapeResult(true, EscapeType.STUCK_LOOP,
                    "Agent producing near-identical output for " + (similarCount + 1) + " consecutive turns",
                    1.5f);
        }
        return null;
    }

    /**
     * Detect identical tool call sequences across consecutive turns.
     */
    private EscapeResult detectToolSequenceLoop(TurnMetrics metrics) {
        if (turnHistory.isEmpty()) return null;

        List<String> currentSeq = buildToolSequence(metrics);
        if (currentSeq.isEmpty()) return null;

        int matchCount = 0;
        Iterator<TurnFingerprint> it = turnHistory.descendingIterator();
        while (it.hasNext()) {
            TurnFingerprint prev = it.next();
            if (prev.toolSequence.equals(currentSeq)) {
                matchCount++;
            } else {
                break;
            }
        }

        if (matchCount >= 2) {
            return new EscapeResult(true, EscapeType.TOOL_SEQUENCE_LOOP,
                    "Identical tool sequence repeated across " + (matchCount + 1) + " consecutive turns: "
                            + summarizeSequence(currentSeq),
                    1.2f);
        }
        return null;
    }

    /**
     * Detect circular reasoning in extended thinking content.
     * Checks for explicit circular patterns and repeated paragraph-level content.
     */
    private EscapeResult detectThinkingLoop(String thinkingText) {
        if (thinkingText == null || thinkingText.length() < 200) return null;

        // Check explicit circular reasoning patterns
        int circularMatches = 0;
        for (Pattern p : THINKING_LOOP_PATTERNS) {
            Matcher m = p.matcher(thinkingText);
            while (m.find()) {
                circularMatches++;
            }
        }

        // Check for repeated paragraph-level content (more aggressive than ThinkingAnalyzer)
        int repeatedParagraphs = countRepeatedParagraphs(thinkingText);

        if (circularMatches >= 3 || repeatedParagraphs >= THINKING_LOOP_PARAGRAPH_THRESHOLD) {
            String detail;
            if (circularMatches >= 3 && repeatedParagraphs >= THINKING_LOOP_PARAGRAPH_THRESHOLD) {
                detail = "Circular reasoning (" + circularMatches + " markers) and " +
                        repeatedParagraphs + " repeated reasoning blocks";
            } else if (circularMatches >= 3) {
                detail = "Circular reasoning detected (" + circularMatches + " self-referential markers)";
            } else {
                detail = repeatedParagraphs + " near-duplicate reasoning blocks in thinking";
            }
            return new EscapeResult(true, EscapeType.THINKING_LOOP, detail, 1.0f);
        }

        return null;
    }

    /**
     * Detect low-effort filler responses. Provider-aware: different LLMs
     * have different characteristic filler patterns.
     */
    private EscapeResult detectLowEffort(String output, String provider) {
        // Only check short-ish outputs — long responses have substance by definition
        if (output.trim().length() > LOW_EFFORT_MAX_LENGTH) return null;

        // Check provider-specific patterns
        List<Pattern> providerPatterns = getProviderPatterns(provider);
        for (Pattern p : providerPatterns) {
            if (p.matcher(output).find()) {
                // Verify this is MOSTLY filler — strip the filler and check what remains
                String stripped = output;
                for (Pattern sp : providerPatterns) {
                    stripped = sp.matcher(stripped).replaceAll("");
                }
                stripped = stripped.trim();
                if (stripped.length() < 50) {
                    return new EscapeResult(true, EscapeType.LOW_EFFORT,
                            "Response is mostly filler" +
                                    (provider != null ? " (" + provider + "-style)" : "") +
                                    " with <50 chars of substance",
                            0.8f);
                }
            }
        }

        // Check generic patterns
        for (Pattern p : LOW_EFFORT_PATTERNS_GENERIC) {
            if (p.matcher(output).find()) {
                String stripped = output;
                for (Pattern sp : LOW_EFFORT_PATTERNS_GENERIC) {
                    stripped = sp.matcher(stripped).replaceAll("");
                }
                if (stripped.trim().length() < 50) {
                    return new EscapeResult(true, EscapeType.LOW_EFFORT,
                            "Response is generic filler with no actionable content",
                            0.8f);
                }
            }
        }

        return null;
    }

    private boolean isOffTopic(String output, String taskType) {
        Set<String> keywords = TASK_KEYWORDS.get(taskType);
        if (keywords == null) return false;
        String lower = output.toLowerCase();
        for (String keyword : keywords) {
            if (lower.contains(keyword)) {
                return false;
            }
        }
        return true;
    }

    // ── History tracking ─────────────────────────────────────────────

    private void recordTurn(TurnMetrics metrics) {
        TurnFingerprint fp = new TurnFingerprint(
                bagOfWords(metrics.getAgentOutput()),
                buildToolSequence(metrics),
                truncateOutput(metrics.getAgentOutput())
        );
        turnHistory.addLast(fp);
        while (turnHistory.size() > HISTORY_SIZE) {
            turnHistory.removeFirst();
        }
    }

    // ── Utility methods ──────────────────────────────────────────────

    private static Set<String> bagOfWords(String text) {
        if (text == null || text.isBlank()) return Set.of();
        Set<String> words = new HashSet<>();
        for (String word : text.toLowerCase().split("\\s+")) {
            String clean = word.replaceAll("[^a-z0-9]", "");
            if (clean.length() >= 3) {
                words.add(clean);
            }
        }
        return words;
    }

    private static double jaccardSimilarity(Set<String> a, Set<String> b) {
        if (a.isEmpty() && b.isEmpty()) return 1.0;
        if (a.isEmpty() || b.isEmpty()) return 0.0;
        Set<String> intersection = new HashSet<>(a);
        intersection.retainAll(b);
        Set<String> union = new HashSet<>(a);
        union.addAll(b);
        return (double) intersection.size() / union.size();
    }

    private static List<String> buildToolSequence(TurnMetrics metrics) {
        List<TurnMetrics.ToolInvocation> invocations = metrics.getToolInvocations();
        if (invocations != null && !invocations.isEmpty()) {
            List<String> seq = new ArrayList<>(invocations.size());
            for (TurnMetrics.ToolInvocation inv : invocations) {
                seq.add(inv.toolName() + ":" + inv.argsFingerprint());
            }
            return seq;
        }
        // Fall back to tool breakdown (name-only, sorted)
        Map<String, Integer> breakdown = metrics.getToolCallBreakdown();
        if (breakdown.isEmpty()) return List.of();
        List<String> seq = new ArrayList<>();
        breakdown.forEach((name, count) -> {
            for (int i = 0; i < count; i++) seq.add(name + ":?");
        });
        Collections.sort(seq);
        return seq;
    }

    private static String truncateOutput(String output) {
        if (output == null) return "";
        String trimmed = output.trim();
        return trimmed.substring(0, Math.min(200, trimmed.length()));
    }

    private static String summarizeSequence(List<String> seq) {
        if (seq.size() <= 3) return seq.toString();
        return "[" + seq.get(0) + ", " + seq.get(1) + ", ... +" + (seq.size() - 2) + " more]";
    }

    private static int countRepeatedParagraphs(String text) {
        String[] paragraphs = text.split("\\n\\n+");
        if (paragraphs.length < 3) return 0;

        Map<String, Integer> seen = new HashMap<>();
        int repeated = 0;
        for (String para : paragraphs) {
            String normalized = para.trim().toLowerCase();
            if (normalized.length() < 40) continue;
            String fingerprint = normalized.substring(0, Math.min(80, normalized.length()));
            int count = seen.merge(fingerprint, 1, Integer::sum);
            if (count == 2) repeated++; // count each duplicate once
        }
        return repeated;
    }

    private static List<Pattern> getProviderPatterns(String provider) {
        if (provider == null) return List.of();
        return switch (provider.toLowerCase()) {
            case "anthropic" -> LOW_EFFORT_PATTERNS_ANTHROPIC;
            case "openai" -> LOW_EFFORT_PATTERNS_OPENAI;
            default -> List.of();
        };
    }
}
