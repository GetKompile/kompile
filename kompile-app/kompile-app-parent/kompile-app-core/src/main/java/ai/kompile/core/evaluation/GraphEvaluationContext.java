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

import ai.kompile.core.graphrag.model.Graph;
import lombok.Builder;
import lombok.Data;

import java.util.Collections;
import java.util.Map;
import java.util.Set;

/**
 * Context for graph extraction evaluation.
 */
@Data
@Builder
public class GraphEvaluationContext {

    /**
     * The ground truth graph containing expected entities and relationships.
     * Required for presence and type accuracy evaluators.
     */
    private Graph groundTruth;

    /**
     * The original source text from which entities were extracted.
     * Required for the LLM-based completeness evaluator.
     */
    private String sourceText;

    /**
     * Custom pass/fail threshold (overrides evaluator default).
     */
    private Double threshold;

    /**
     * Whether to allow fuzzy (case-insensitive, substring) matching
     * when comparing entity titles. Default false (exact match).
     */
    @Builder.Default
    private boolean fuzzyMatch = false;

    /**
     * Similarity threshold for fuzzy entity name matching (0.0 to 1.0).
     * Only used when fuzzyMatch is true.
     */
    @Builder.Default
    private double similarityThreshold = 0.85;

    /**
     * Whether to match entities only by title or also require type match.
     * When false, entities are matched by title only.
     * When true, both title and type must match for a true positive.
     */
    @Builder.Default
    private boolean requireTypeMatch = false;

    /**
     * Entity types to include in evaluation. If empty, all types are included.
     * Allows filtering evaluation to specific entity categories (e.g., only PERSON and ORGANIZATION).
     */
    @Builder.Default
    private Set<String> entityTypeFilter = Collections.emptySet();

    /**
     * Relationship types to include in evaluation. If empty, all types are included.
     */
    @Builder.Default
    private Set<String> relationshipTypeFilter = Collections.emptySet();

    /**
     * Additional metadata.
     */
    @Builder.Default
    private Map<String, Object> metadata = Collections.emptyMap();

    /**
     * Create a context with only ground truth.
     */
    public static GraphEvaluationContext withGroundTruth(Graph groundTruth) {
        return GraphEvaluationContext.builder()
                .groundTruth(groundTruth)
                .build();
    }

    /**
     * Create a context with only source text (for LLM-based completeness evaluation).
     */
    public static GraphEvaluationContext withSourceText(String sourceText) {
        return GraphEvaluationContext.builder()
                .sourceText(sourceText)
                .build();
    }

    /**
     * Create a context with ground truth and fuzzy matching enabled.
     */
    public static GraphEvaluationContext withFuzzyMatch(Graph groundTruth, double similarityThreshold) {
        return GraphEvaluationContext.builder()
                .groundTruth(groundTruth)
                .fuzzyMatch(true)
                .similarityThreshold(similarityThreshold)
                .build();
    }
}
