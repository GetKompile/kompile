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

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Result of a RAG evaluation.
 */
@Data
@Builder
public class EvaluationResult {

    /**
     * The evaluator that produced this result.
     */
    private String evaluatorName;

    /**
     * The type of evaluation performed.
     */
    private EvaluationType evaluationType;

    /**
     * Whether the evaluation passed.
     */
    private boolean passed;

    /**
     * The score (0.0 to 1.0).
     */
    private double score;

    /**
     * Confidence in the evaluation (0.0 to 1.0).
     */
    @Builder.Default
    private double confidence = 1.0;

    /**
     * Explanation of the evaluation result.
     */
    private String explanation;

    /**
     * Detailed findings from the evaluation.
     */
    @Builder.Default
    private List<Finding> findings = Collections.emptyList();

    /**
     * The threshold used for pass/fail determination.
     */
    @Builder.Default
    private double threshold = 0.5;

    /**
     * Additional metrics from the evaluation.
     */
    @Builder.Default
    private Map<String, Double> metrics = Collections.emptyMap();

    /**
     * Additional metadata.
     */
    @Builder.Default
    private Map<String, Object> metadata = Collections.emptyMap();

    /**
     * Time taken for evaluation in milliseconds.
     */
    private long evaluationTimeMs;

    /**
     * Create a passing result.
     *
     * @param evaluatorName The evaluator name
     * @param type The evaluation type
     * @param score The score
     * @return A passing EvaluationResult
     */
    public static EvaluationResult pass(String evaluatorName, EvaluationType type, double score) {
        return EvaluationResult.builder()
                .evaluatorName(evaluatorName)
                .evaluationType(type)
                .passed(true)
                .score(score)
                .build();
    }

    /**
     * Create a failing result.
     *
     * @param evaluatorName The evaluator name
     * @param type The evaluation type
     * @param score The score
     * @param explanation Explanation of failure
     * @return A failing EvaluationResult
     */
    public static EvaluationResult fail(String evaluatorName, EvaluationType type,
                                        double score, String explanation) {
        return EvaluationResult.builder()
                .evaluatorName(evaluatorName)
                .evaluationType(type)
                .passed(false)
                .score(score)
                .explanation(explanation)
                .build();
    }

    /**
     * A specific finding from the evaluation.
     */
    @Data
    @Builder
    public static class Finding {
        /**
         * Type of finding.
         */
        private FindingType type;

        /**
         * Description of the finding.
         */
        private String description;

        /**
         * The relevant content.
         */
        private String content;

        /**
         * Evidence supporting this finding.
         */
        private String evidence;

        /**
         * Severity of the finding.
         */
        @Builder.Default
        private FindingSeverity severity = FindingSeverity.INFO;

        /**
         * Score contribution of this finding.
         */
        private double scoreImpact;
    }

    /**
     * Types of evaluation findings.
     */
    public enum FindingType {
        SUPPORTED_CLAIM,
        UNSUPPORTED_CLAIM,
        CONTRADICTED_CLAIM,
        MISSING_INFORMATION,
        IRRELEVANT_CONTENT,
        ACCURATE_RESPONSE,
        INACCURATE_RESPONSE,
        PARTIAL_MATCH,
        HALLUCINATION,
        OFF_TOPIC
    }

    /**
     * Severity levels for findings.
     */
    public enum FindingSeverity {
        INFO,
        WARNING,
        ERROR,
        CRITICAL
    }
}
