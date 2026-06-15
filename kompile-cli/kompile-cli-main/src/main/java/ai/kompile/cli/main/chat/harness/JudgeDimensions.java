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
 * Multi-dimensional judge LLM evaluation result.
 * Each dimension is scored 1-5; -1 means N/A (dimension not applicable
 * for this task type or no data available).
 */
public class JudgeDimensions {

    private final float correctness;
    private final float completeness;
    private final float designQuality;
    private final float thinkingCoherence;
    private final String reasoning;
    private final boolean error;
    private final String errorDetail;

    private JudgeDimensions(float correctness, float completeness, float designQuality,
                            float thinkingCoherence, String reasoning,
                            boolean error, String errorDetail) {
        this.correctness = correctness;
        this.completeness = completeness;
        this.designQuality = designQuality;
        this.thinkingCoherence = thinkingCoherence;
        this.reasoning = reasoning;
        this.error = error;
        this.errorDetail = errorDetail;
    }

    public static JudgeDimensions of(float correctness, float completeness,
                                      float designQuality, float thinkingCoherence,
                                      String reasoning) {
        return new JudgeDimensions(
                clamp(correctness), clamp(completeness),
                designQuality < 0 ? -1 : clamp(designQuality),
                thinkingCoherence < 0 ? -1 : clamp(thinkingCoherence),
                reasoning, false, null);
    }

    public static JudgeDimensions error(String detail) {
        return new JudgeDimensions(0, 0, -1, -1,
                detail != null ? detail : "Judge evaluation failed", true, detail);
    }

    /**
     * Weighted average of applicable dimensions.
     * Weights: correctness=0.4, completeness=0.4, designQuality=0.1, thinkingCoherence=0.1.
     * N/A dimensions (-1) are excluded and weights renormalized.
     */
    public float weightedAverage() {
        if (error) return 0f;

        float totalWeight = 0f;
        float weightedSum = 0f;

        if (correctness > 0) {
            weightedSum += 0.4f * correctness;
            totalWeight += 0.4f;
        }
        if (completeness > 0) {
            weightedSum += 0.4f * completeness;
            totalWeight += 0.4f;
        }
        if (designQuality > 0) {
            weightedSum += 0.1f * designQuality;
            totalWeight += 0.1f;
        }
        if (thinkingCoherence > 0) {
            weightedSum += 0.1f * thinkingCoherence;
            totalWeight += 0.1f;
        }

        return totalWeight > 0 ? weightedSum / totalWeight : 0f;
    }

    public boolean isValid() {
        return !error && (correctness > 0 || completeness > 0);
    }

    // ── Getters ───────────────────────────────────────────────────────
    public float getCorrectness() { return correctness; }
    public float getCompleteness() { return completeness; }
    public float getDesignQuality() { return designQuality; }
    public float getThinkingCoherence() { return thinkingCoherence; }
    public String getReasoning() { return reasoning; }
    public boolean isError() { return error; }
    public String getErrorDetail() { return errorDetail; }

    @Override
    public String toString() {
        if (error) return "JudgeDimensions{error, " + errorDetail + "}";
        return "JudgeDimensions{c=" + correctness + ", comp=" + completeness
                + ", dq=" + designQuality + ", tc=" + thinkingCoherence
                + ", avg=" + String.format("%.1f", weightedAverage()) + "}";
    }

    private static float clamp(float v) {
        return Math.max(0, Math.min(5, v));
    }
}
