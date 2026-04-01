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

package ai.kompile.core.evaluation;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Aggregated report from multiple evaluators.
 */
@Data
@Builder
public class EvaluationReport {

    /**
     * The query that was evaluated.
     */
    private String query;

    /**
     * The response that was evaluated.
     */
    private String response;

    /**
     * Overall pass/fail status.
     */
    private boolean overallPassed;

    /**
     * Overall score (average of all evaluators).
     */
    private double overallScore;

    /**
     * Individual evaluation results.
     */
    @Builder.Default
    private List<EvaluationResult> results = Collections.emptyList();

    /**
     * Scores by evaluation type.
     */
    @Builder.Default
    private Map<EvaluationType, Double> scoresByType = Collections.emptyMap();

    /**
     * Total evaluation time in milliseconds.
     */
    private long totalEvaluationTimeMs;

    /**
     * Timestamp of the evaluation.
     */
    @Builder.Default
    private Instant timestamp = Instant.now();

    /**
     * Summary of findings across all evaluators.
     */
    private String summary;

    /**
     * Recommendations based on the evaluation.
     */
    @Builder.Default
    private List<String> recommendations = Collections.emptyList();

    /**
     * Get the number of evaluators that passed.
     *
     * @return Count of passing evaluators
     */
    public long getPassedCount() {
        return results.stream().filter(EvaluationResult::isPassed).count();
    }

    /**
     * Get the number of evaluators that failed.
     *
     * @return Count of failing evaluators
     */
    public long getFailedCount() {
        return results.stream().filter(r -> !r.isPassed()).count();
    }

    /**
     * Get all failed evaluations.
     *
     * @return List of failed evaluation results
     */
    public List<EvaluationResult> getFailedEvaluations() {
        return results.stream()
                .filter(r -> !r.isPassed())
                .collect(Collectors.toList());
    }

    /**
     * Get evaluation result by type.
     *
     * @param type The evaluation type
     * @return The result for that type, or null if not evaluated
     */
    public EvaluationResult getResultByType(EvaluationType type) {
        return results.stream()
                .filter(r -> r.getEvaluationType() == type)
                .findFirst()
                .orElse(null);
    }

    /**
     * Check if a specific evaluation type passed.
     *
     * @param type The evaluation type
     * @return true if passed, false if failed or not evaluated
     */
    public boolean passedType(EvaluationType type) {
        EvaluationResult result = getResultByType(type);
        return result != null && result.isPassed();
    }

    /**
     * Create an empty report for when no evaluation is performed.
     *
     * @param query The query
     * @param response The response
     * @return An empty report
     */
    public static EvaluationReport empty(String query, String response) {
        return EvaluationReport.builder()
                .query(query)
                .response(response)
                .overallPassed(true)
                .overallScore(1.0)
                .summary("No evaluation performed")
                .build();
    }
}
