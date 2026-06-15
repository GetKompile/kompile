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

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Layer 4: Analyze captured extended thinking content for confusion signals.
 * Detects backtracking, contradictions, repeated reasoning chains, and
 * excessive thinking-to-output ratios.
 * Pure computation — no I/O, no LLM calls.
 */
public class ThinkingAnalyzer {

    public record ThinkingAnalysis(
            float coherenceScore,
            boolean hasBacktracking,
            boolean hasContradictions,
            boolean hasRepeatedChains,
            boolean hasCircularReasoning,
            double thinkingToOutputRatio,
            String summary
    ) {
        public static ThinkingAnalysis absent() {
            return new ThinkingAnalysis(0f, false, false, false, false, 0.0,
                    "no thinking available");
        }
    }

    private static final List<Pattern> BACKTRACK_PATTERNS = List.of(
            Pattern.compile("\\bwait\\b[,.]?\\s", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bactually\\b[,.]?\\s", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bhmm\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("let me reconsider", Pattern.CASE_INSENSITIVE),
            Pattern.compile("on second thought", Pattern.CASE_INSENSITIVE),
            Pattern.compile("I was wrong", Pattern.CASE_INSENSITIVE),
            Pattern.compile("that's not (right|correct)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("scratch that", Pattern.CASE_INSENSITIVE),
            Pattern.compile("no,? that('s|\\s+is) not", Pattern.CASE_INSENSITIVE),
            Pattern.compile("let me (re-?think|start over)", Pattern.CASE_INSENSITIVE)
    );

    private static final List<Pattern> CONTRADICTION_PATTERNS = List.of(
            Pattern.compile("but (earlier|previously|above) I (said|mentioned|wrote)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("this contradicts", Pattern.CASE_INSENSITIVE),
            Pattern.compile("I (just )?said the opposite", Pattern.CASE_INSENSITIVE),
            Pattern.compile("wait,? that can'?t be (right|true)", Pattern.CASE_INSENSITIVE)
    );

    private static final List<Pattern> CIRCULAR_REASONING_PATTERNS = List.of(
            Pattern.compile("as I (said|mentioned|noted) (before|earlier|above|previously)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("going back to (my|the) (earlier|previous|original)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("I keep coming back to", Pattern.CASE_INSENSITIVE),
            Pattern.compile("I('m| am) going in circles", Pattern.CASE_INSENSITIVE),
            Pattern.compile("let me try (again|a different|another) approach", Pattern.CASE_INSENSITIVE),
            Pattern.compile("I('ve| have) already (tried|considered|thought about) this", Pattern.CASE_INSENSITIVE),
            Pattern.compile("this is the same (conclusion|result|answer) as before", Pattern.CASE_INSENSITIVE),
            Pattern.compile("I('m| am) (stuck|not making progress)", Pattern.CASE_INSENSITIVE)
    );

    private static final float BASE_SCORE = 4.0f;
    private static final float BACKTRACK_PENALTY = 0.5f;
    private static final float MAX_BACKTRACK_PENALTY = 1.5f;
    private static final float CONTRADICTION_PENALTY = 1.0f;
    private static final float REPEATED_CHAIN_PENALTY = 0.5f;
    private static final float CIRCULAR_REASONING_PENALTY = 1.2f;
    private static final float EXCESSIVE_RATIO_PENALTY = 0.3f;
    private static final double EXCESSIVE_RATIO_THRESHOLD = 10.0;

    /**
     * Analyze thinking content for coherence signals.
     *
     * @param thinkingText  the captured extended thinking text (may be null)
     * @param thinkingTokens estimated thinking token count
     * @param outputTokens   output token count for ratio calculation
     * @return analysis result with coherence score and detail flags
     */
    public ThinkingAnalysis analyze(String thinkingText, long thinkingTokens, long outputTokens) {
        if (thinkingText == null || thinkingText.isBlank()) {
            return ThinkingAnalysis.absent();
        }

        float score = BASE_SCORE;
        StringBuilder summary = new StringBuilder();

        // Backtracking detection
        int backtrackCount = countPatternMatches(thinkingText, BACKTRACK_PATTERNS);
        boolean hasBacktracking = backtrackCount > 0;
        if (hasBacktracking) {
            float penalty = Math.min(backtrackCount * BACKTRACK_PENALTY, MAX_BACKTRACK_PENALTY);
            score -= penalty;
            summary.append(backtrackCount).append(" backtracks (-")
                    .append(String.format("%.1f", penalty)).append("); ");
        }

        // Contradiction detection
        boolean hasContradictions = hasPatternMatch(thinkingText, CONTRADICTION_PATTERNS);
        if (hasContradictions) {
            score -= CONTRADICTION_PENALTY;
            summary.append("contradictions (-").append(CONTRADICTION_PENALTY).append("); ");
        }

        // Repeated chain detection
        boolean hasRepeatedChains = detectRepeatedChains(thinkingText);
        if (hasRepeatedChains) {
            score -= REPEATED_CHAIN_PENALTY;
            summary.append("repeated reasoning (-").append(REPEATED_CHAIN_PENALTY).append("); ");
        }

        // Circular reasoning detection (thinking loops)
        int circularCount = countPatternMatches(thinkingText, CIRCULAR_REASONING_PATTERNS);
        boolean hasCircularReasoning = circularCount >= 2;
        if (hasCircularReasoning) {
            score -= CIRCULAR_REASONING_PENALTY;
            summary.append(circularCount).append(" circular markers (-")
                    .append(String.format("%.1f", CIRCULAR_REASONING_PENALTY)).append("); ");
        }

        // Thinking-to-output ratio
        double ratio = outputTokens > 0 ? (double) thinkingTokens / outputTokens : 0.0;
        if (ratio > EXCESSIVE_RATIO_THRESHOLD) {
            score -= EXCESSIVE_RATIO_PENALTY;
            summary.append("excessive ratio ").append(String.format("%.1f", ratio))
                    .append(":1 (-").append(EXCESSIVE_RATIO_PENALTY).append("); ");
        }

        score = Math.max(1.0f, Math.min(5.0f, score));

        if (summary.isEmpty()) {
            summary.append("coherent thinking");
        }

        return new ThinkingAnalysis(score, hasBacktracking, hasContradictions,
                hasRepeatedChains, hasCircularReasoning, ratio, summary.toString().trim());
    }

    private int countPatternMatches(String text, List<Pattern> patterns) {
        int count = 0;
        for (Pattern p : patterns) {
            Matcher m = p.matcher(text);
            while (m.find()) {
                count++;
            }
        }
        return count;
    }

    private boolean hasPatternMatch(String text, List<Pattern> patterns) {
        for (Pattern p : patterns) {
            if (p.matcher(text).find()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Detect repeated reasoning chains by comparing paragraph-level content.
     * Splits on double-newlines and checks for near-duplicate paragraphs.
     */
    private boolean detectRepeatedChains(String text) {
        String[] paragraphs = text.split("\\n\\n+");
        if (paragraphs.length < 3) return false;

        Set<String> seen = new HashSet<>();
        for (String para : paragraphs) {
            String normalized = para.trim().toLowerCase();
            if (normalized.length() < 50) continue;
            // Use first 100 chars as fingerprint to detect near-duplicates
            String fingerprint = normalized.substring(0, Math.min(100, normalized.length()));
            if (!seen.add(fingerprint)) {
                return true;
            }
        }
        return false;
    }
}
