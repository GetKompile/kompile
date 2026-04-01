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

import java.util.List;

/**
 * Interface for evaluating RAG system responses.
 * <p>
 * Evaluators assess the quality of RAG responses along different dimensions:
 * <ul>
 *   <li>Relevancy - Is the response relevant to the query?</li>
 *   <li>Faithfulness - Is the response grounded in the retrieved context?</li>
 *   <li>Factuality - Is the response factually accurate?</li>
 *   <li>Completeness - Does the response fully answer the query?</li>
 * </ul>
 */
public interface RagEvaluator {

    /**
     * Evaluate a RAG response.
     *
     * @param query The original user query
     * @param response The LLM response
     * @param retrievedDocuments The documents retrieved for context
     * @param context Additional evaluation context
     * @return The evaluation result
     */
    EvaluationResult evaluate(String query, String response,
                              List<String> retrievedDocuments, EvaluationContext context);

    /**
     * Evaluate a RAG response with minimal context.
     *
     * @param query The original user query
     * @param response The LLM response
     * @param retrievedDocuments The documents retrieved for context
     * @return The evaluation result
     */
    default EvaluationResult evaluate(String query, String response, List<String> retrievedDocuments) {
        return evaluate(query, response, retrievedDocuments, EvaluationContext.empty());
    }

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
     * Check if this evaluator requires ground truth answers.
     *
     * @return true if ground truth is required
     */
    default boolean requiresGroundTruth() {
        return false;
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
