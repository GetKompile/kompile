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
 * Context for RAG evaluation operations.
 */
@Data
@Builder
public class EvaluationContext {

    /**
     * Ground truth answer (if available for comparison).
     */
    private String groundTruth;

    /**
     * Expected answer format or structure.
     */
    private String expectedFormat;

    /**
     * Domain or topic for domain-specific evaluation.
     */
    private String domain;

    /**
     * Custom pass/fail threshold (overrides evaluator default).
     */
    private Double threshold;

    /**
     * Whether to include detailed findings in result.
     */
    @Builder.Default
    private boolean includeFindings = true;

    /**
     * Whether to include explanation in result.
     */
    @Builder.Default
    private boolean includeExplanation = true;

    /**
     * Custom evaluation criteria.
     */
    @Builder.Default
    private List<String> customCriteria = Collections.emptyList();

    /**
     * Keywords that should be present in the response.
     */
    @Builder.Default
    private List<String> requiredKeywords = Collections.emptyList();

    /**
     * Keywords that should NOT be present in the response.
     */
    @Builder.Default
    private List<String> forbiddenKeywords = Collections.emptyList();

    /**
     * Maximum allowed response length.
     */
    private Integer maxResponseLength;

    /**
     * Minimum required response length.
     */
    private Integer minResponseLength;

    /**
     * Additional metadata.
     */
    @Builder.Default
    private Map<String, Object> metadata = Collections.emptyMap();

    /**
     * Create an empty context.
     *
     * @return An empty EvaluationContext
     */
    public static EvaluationContext empty() {
        return EvaluationContext.builder().build();
    }

    /**
     * Create a context with ground truth.
     *
     * @param groundTruth The expected correct answer
     * @return An EvaluationContext with ground truth
     */
    public static EvaluationContext withGroundTruth(String groundTruth) {
        return EvaluationContext.builder()
                .groundTruth(groundTruth)
                .build();
    }

    /**
     * Create a context with a custom threshold.
     *
     * @param threshold The pass/fail threshold
     * @return An EvaluationContext with the threshold
     */
    public static EvaluationContext withThreshold(double threshold) {
        return EvaluationContext.builder()
                .threshold(threshold)
                .build();
    }
}
