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

package ai.kompile.cli.main.chat.harness.eval;

import ai.kompile.cli.main.chat.harness.TaskOutcome;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Aggregate result of running an entire {@link EvalSuite}.
 * Persisted to ~/.kompile/eval-results.json for cross-run comparison.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class EvalRunResult {

    @JsonProperty private String runId;
    @JsonProperty private String suiteName;
    @JsonProperty private String suiteFile;
    @JsonProperty private Instant startTime;
    @JsonProperty private Instant endTime;
    @JsonProperty private long totalDurationMs;

    // Aggregate stats
    @JsonProperty private int totalCases;
    @JsonProperty private int passedCases;
    @JsonProperty private int failedCases;
    @JsonProperty private int skippedCases;
    @JsonProperty private double passRate;
    @JsonProperty private boolean suitePassed;
    @JsonProperty private double requiredPassRate;

    // Outcome breakdown
    @JsonProperty private Map<String, Integer> outcomeCounts = new LinkedHashMap<>();

    // Per-case results
    @JsonProperty private List<EvalCaseResult> caseResults = new ArrayList<>();

    // Aggregate scores
    @JsonProperty private float avgCompositeScore;
    @JsonProperty private float avgJudgeCorrectness;
    @JsonProperty private float avgJudgeCompleteness;
    @JsonProperty private long totalTokens;

    /** Compute all aggregate fields from the case results. */
    public void computeAggregates(double requiredPassRate) {
        this.requiredPassRate = requiredPassRate;
        this.totalCases = caseResults.size();
        this.passedCases = 0;
        this.failedCases = 0;
        this.skippedCases = 0;
        this.outcomeCounts.clear();

        float totalComposite = 0;
        float totalCorrectness = 0;
        float totalCompleteness = 0;
        int scoredCount = 0;

        for (EvalCaseResult r : caseResults) {
            TaskOutcome outcome = r.getOutcome();
            String key = outcome != null ? outcome.name() : "UNKNOWN";
            outcomeCounts.merge(key, 1, Integer::sum);

            if (r.passed()) {
                passedCases++;
            } else {
                failedCases++;
            }

            if (r.getCompositeScore() > 0) {
                totalComposite += r.getCompositeScore();
                scoredCount++;
            }
            if (r.getJudgeCorrectness() > 0) {
                totalCorrectness += r.getJudgeCorrectness();
            }
            if (r.getJudgeCompleteness() > 0) {
                totalCompleteness += r.getJudgeCompleteness();
            }
            totalTokens += r.getInputTokens() + r.getOutputTokens();
        }

        this.passRate = totalCases > 0 ? (double) passedCases / totalCases : 0;
        this.suitePassed = passRate >= requiredPassRate;

        if (scoredCount > 0) {
            avgCompositeScore = totalComposite / scoredCount;
            avgJudgeCorrectness = totalCorrectness / scoredCount;
            avgJudgeCompleteness = totalCompleteness / scoredCount;
        }

        if (endTime != null && startTime != null) {
            totalDurationMs = endTime.toEpochMilli() - startTime.toEpochMilli();
        }
    }

    /** Generate a text summary of the run. */
    public String generateSummary() {
        StringBuilder sb = new StringBuilder();
        sb.append(suitePassed ? "PASSED" : "FAILED");
        sb.append(" — ").append(suiteName);
        sb.append(String.format(" | %d/%d passed (%.0f%%)", passedCases, totalCases, passRate * 100));
        sb.append(String.format(" | required: %.0f%%", requiredPassRate * 100));
        if (avgCompositeScore > 0) {
            sb.append(String.format(" | avg score: %.1f/5.0", avgCompositeScore));
        }
        sb.append(String.format(" | %dms", totalDurationMs));

        if (failedCases > 0) {
            sb.append("\n\nFailed cases:");
            for (EvalCaseResult r : caseResults) {
                if (!r.passed()) {
                    sb.append("\n  - ").append(r.getCaseId()).append(": ")
                            .append(r.getOutcome()).append(" — ").append(r.getOutcomeReason());
                }
            }
        }

        return sb.toString();
    }
}
