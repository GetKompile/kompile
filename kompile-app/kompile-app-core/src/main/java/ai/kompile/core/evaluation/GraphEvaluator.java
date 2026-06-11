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

/**
 * Interface for evaluating graph extraction quality.
 * <p>
 * Unlike {@link RagEvaluator} which evaluates RAG query/response pairs,
 * GraphEvaluator assesses the quality of entity and relationship extraction
 * by comparing an extracted graph against ground truth or source text.
 * <p>
 * Evaluators can assess:
 * <ul>
 *   <li>Entity presence — precision/recall/F1 of extracted entities vs. expected</li>
 *   <li>Relationship presence — precision/recall/F1 of extracted relationships vs. expected</li>
 *   <li>Entity type accuracy — correctness of assigned entity types</li>
 *   <li>Graph completeness — whether all entities/relationships from source text were captured</li>
 * </ul>
 */
public interface GraphEvaluator {

    /**
     * Evaluate an extracted graph.
     *
     * @param extracted The graph produced by the extraction pipeline
     * @param context   Evaluation context containing ground truth and/or source text
     * @return The evaluation result with precision, recall, F1, and per-entity details
     */
    GraphEvaluationResult evaluate(Graph extracted, GraphEvaluationContext context);

    /**
     * Get the name of this evaluator.
     *
     * @return The evaluator name
     */
    String getName();

    /**
     * Get the type of evaluation this evaluator performs.
     *
     * @return The evaluation type
     */
    EvaluationType getType();

    /**
     * Get the default pass/fail threshold.
     *
     * @return The threshold (0.0 to 1.0)
     */
    default double getDefaultThreshold() {
        return 0.5;
    }

    /**
     * Check if this evaluator requires an LLM.
     *
     * @return true if an LLM is required
     */
    default boolean requiresLlm() {
        return false;
    }

    /**
     * Check if this evaluator requires a ground truth graph.
     *
     * @return true if ground truth is required
     */
    default boolean requiresGroundTruth() {
        return true;
    }

    /**
     * Check if this evaluator is enabled.
     *
     * @return true if enabled
     */
    default boolean isEnabled() {
        return true;
    }
}
