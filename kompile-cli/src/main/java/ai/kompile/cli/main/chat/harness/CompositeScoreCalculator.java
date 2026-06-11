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

/**
 * Blends all four signal layers into a single 1.0-5.0 composite score
 * that feeds into {@link ModelRouter} for swap decisions.
 *
 * <pre>
 * Hard escape → 1.0 immediately
 * Otherwise:
 *   composite = W_JUDGE * judgeScore
 *             + W_ESCAPE * escapeScore
 *             + W_EFFICIENCY * efficiencyScore
 *             + W_THINKING * thinkingScore
 * </pre>
 *
 * When the judge is disabled, weights renormalize over the remaining layers.
 * When thinking is absent, a neutral default (3.0) is used for that layer.
 */
public class CompositeScoreCalculator {

    private final HarnessConfig config;

    public CompositeScoreCalculator(HarnessConfig config) {
        this.config = config;
    }

    /**
     * Compute the composite quality score from all signal layers.
     *
     * @param metrics   Layer 1 event data
     * @param escape    Layer 2 escape detection result (null = not run)
     * @param judge     Layer 3 multi-dimensional judge (null = judge disabled)
     * @param thinking  Layer 4 thinking analysis (null = no thinking content)
     * @param taskType  used to calibrate efficiency expectations
     * @return composite score 1.0-5.0, or 0f if insufficient data
     */
    public float compute(TurnMetrics metrics,
                          EscapeDetector.EscapeResult escape,
                          JudgeDimensions judge,
                          ThinkingAnalyzer.ThinkingAnalysis thinking,
                          String taskType) {

        // Hard escapes short-circuit to 1.0
        if (escape != null && escape.isHardEscape()) {
            return 1.0f;
        }

        float escapeScore = (escape != null && escape.hasEscape()) ? 1.0f : 5.0f;
        float efficiencyScore = computeEfficiencyScore(metrics, taskType);

        boolean hasJudge = judge != null && judge.isValid();
        boolean hasThinking = thinking != null && thinking.coherenceScore() > 0;

        float wEscape = config.getEscapeWeight();
        float wEfficiency = config.getEfficiencyWeight();
        float wJudge = config.getJudgeWeight();
        float wThinking = config.getThinkingWeight();

        float base;
        if (hasJudge) {
            float judgeScore = judge.weightedAverage();
            float thinkingScore = hasThinking ? thinking.coherenceScore() : 3.0f;

            base = wJudge * judgeScore
                    + wEscape * escapeScore
                    + wEfficiency * efficiencyScore
                    + wThinking * thinkingScore;
        } else {
            // No judge — renormalize over escape + efficiency
            float totalWeight = wEscape + wEfficiency;
            if (hasThinking) {
                float thinkingScore = thinking.coherenceScore();
                totalWeight += wThinking;
                base = (wEscape / totalWeight) * escapeScore
                        + (wEfficiency / totalWeight) * efficiencyScore
                        + (wThinking / totalWeight) * thinkingScore;
            } else {
                base = (wEscape / totalWeight) * escapeScore
                        + (wEfficiency / totalWeight) * efficiencyScore;
            }
            // Scale back to full range
            base *= totalWeight / (wEscape + wEfficiency + wJudge + wThinking);
            // Simpler: just split between escape and efficiency
            float renorm = wEscape + wEfficiency;
            base = (wEscape / renorm) * escapeScore + (wEfficiency / renorm) * efficiencyScore;
        }

        // Apply soft escape penalty
        if (escape != null && escape.hasEscape() && !escape.isHardEscape()) {
            base -= escape.penalty() * 0.4f;
        }

        return clamp(base);
    }

    /**
     * Convert Layer 1 event metrics into an efficiency score (1.0-5.0).
     */
    private float computeEfficiencyScore(TurnMetrics metrics, String taskType) {
        float score = 4.0f;

        // Hit max steps is a strong negative signal
        if (metrics.isHitMaxSteps()) {
            return 1.5f;
        }

        // Tool error rate penalties
        double errorRate = metrics.getToolErrorRate();
        if (errorRate > 0.5) {
            score = 2.0f;
        } else if (errorRate > 0.25) {
            score = 3.0f;
        } else if (errorRate > 0.1) {
            score -= 0.5f;
        }

        // Compaction penalty — indicates the agent used excessive context
        if (metrics.getCompactionCount() > 0) {
            score -= 0.3f * metrics.getCompactionCount();
        }

        // Step count calibration per task type
        int steps = metrics.getAgenticSteps();
        int expectedMax = getExpectedMaxSteps(taskType);
        if (steps <= expectedMax) {
            // Efficient — bonus
            score += 0.3f;
        } else if (steps > expectedMax * 2) {
            // Excessive steps
            score -= 0.5f;
        }

        return clamp(score);
    }

    private int getExpectedMaxSteps(String taskType) {
        return switch (taskType) {
            case "exploration" -> 5;
            case "code-review" -> 8;
            case "planning" -> 6;
            case "research" -> 10;
            case "incident-response" -> 15;
            default -> 8;
        };
    }

    private static float clamp(float v) {
        return Math.max(1.0f, Math.min(5.0f, v));
    }
}
