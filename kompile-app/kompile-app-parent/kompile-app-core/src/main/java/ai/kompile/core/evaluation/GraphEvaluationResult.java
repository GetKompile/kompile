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
 * Result of a graph extraction evaluation, including precision/recall/F1
 * and per-entity/relationship match details.
 */
@Data
@Builder
public class GraphEvaluationResult {

    /**
     * The evaluator that produced this result.
     */
    private String evaluatorName;

    /**
     * The type of evaluation performed.
     */
    private EvaluationType evaluationType;

    /**
     * Whether the evaluation passed the threshold.
     */
    private boolean passed;

    /**
     * The primary score (typically F1 for presence evaluators, accuracy for type evaluators).
     */
    private double score;

    /**
     * Precision: TP / (TP + FP). Of extracted items, what fraction are correct.
     */
    private double precision;

    /**
     * Recall: TP / (TP + FN). Of expected items, what fraction were found.
     */
    private double recall;

    /**
     * F1: harmonic mean of precision and recall.
     */
    private double f1;

    /**
     * Number of true positives (correctly extracted items found in ground truth).
     */
    private int truePositives;

    /**
     * Number of false positives (extracted items not in ground truth).
     */
    private int falsePositives;

    /**
     * Number of false negatives (ground truth items not found in extraction).
     */
    private int falseNegatives;

    /**
     * The threshold used for pass/fail determination.
     */
    @Builder.Default
    private double threshold = 0.5;

    /**
     * Detailed per-entity match results.
     */
    @Builder.Default
    private List<EntityMatch> entityMatches = Collections.emptyList();

    /**
     * Detailed per-relationship match results.
     */
    @Builder.Default
    private List<RelationshipMatch> relationshipMatches = Collections.emptyList();

    /**
     * Additional metrics (e.g., per-type precision/recall breakdowns).
     */
    @Builder.Default
    private Map<String, Double> metrics = Collections.emptyMap();

    /**
     * Human-readable explanation of the result.
     */
    private String explanation;

    /**
     * Time taken for evaluation in milliseconds.
     */
    private long evaluationTimeMs;

    /**
     * A match result for a single entity.
     */
    @Data
    @Builder
    public static class EntityMatch {
        /**
         * The entity title from the extracted graph (null if false negative).
         */
        private String extractedTitle;

        /**
         * The entity title from the ground truth (null if false positive).
         */
        private String expectedTitle;

        /**
         * The extracted entity type.
         */
        private String extractedType;

        /**
         * The expected entity type from ground truth.
         */
        private String expectedType;

        /**
         * The match classification.
         */
        private MatchType matchType;

        /**
         * Similarity score between extracted and expected titles (1.0 for exact match).
         */
        @Builder.Default
        private double similarity = 0.0;
    }

    /**
     * A match result for a single relationship.
     */
    @Data
    @Builder
    public static class RelationshipMatch {
        /**
         * Source entity title in the extracted relationship (null if false negative).
         */
        private String extractedSource;

        /**
         * Target entity title in the extracted relationship.
         */
        private String extractedTarget;

        /**
         * Relationship type in the extracted graph.
         */
        private String extractedType;

        /**
         * Source entity title in the ground truth relationship (null if false positive).
         */
        private String expectedSource;

        /**
         * Target entity title in the ground truth relationship.
         */
        private String expectedTarget;

        /**
         * Relationship type in the ground truth.
         */
        private String expectedType;

        /**
         * The match classification.
         */
        private MatchType matchType;
    }

    /**
     * Classification of a match between extracted and expected items.
     */
    public enum MatchType {
        /**
         * Extracted item correctly matches a ground truth item.
         */
        TRUE_POSITIVE,

        /**
         * Extracted item has no corresponding ground truth item.
         */
        FALSE_POSITIVE,

        /**
         * Ground truth item was not found in the extracted graph.
         */
        FALSE_NEGATIVE,

        /**
         * Entity matched by title but has incorrect type.
         */
        TYPE_MISMATCH
    }
}
