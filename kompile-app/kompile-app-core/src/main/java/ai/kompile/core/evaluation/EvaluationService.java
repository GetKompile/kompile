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
 * Service for orchestrating RAG evaluations.
 * <p>
 * This service manages the execution of multiple evaluators and
 * aggregates their results into a comprehensive evaluation report.
 */
public interface EvaluationService {

    /**
     * Evaluate a RAG response using all registered evaluators.
     *
     * @param query The original user query
     * @param response The LLM response
     * @param retrievedDocuments The documents retrieved for context
     * @param context The evaluation context
     * @return Aggregated evaluation report
     */
    EvaluationReport evaluate(String query, String response,
                              List<String> retrievedDocuments, EvaluationContext context);

    /**
     * Evaluate using specific evaluator types.
     *
     * @param query The original user query
     * @param response The LLM response
     * @param retrievedDocuments The documents retrieved for context
     * @param types The types of evaluation to perform
     * @param context The evaluation context
     * @return Aggregated evaluation report
     */
    EvaluationReport evaluate(String query, String response,
                              List<String> retrievedDocuments,
                              List<EvaluationType> types, EvaluationContext context);

    /**
     * Get all registered evaluators.
     *
     * @return List of evaluators
     */
    List<RagEvaluator> getEvaluators();

    /**
     * Get evaluators of a specific type.
     *
     * @param type The evaluation type
     * @return List of evaluators of that type
     */
    List<RagEvaluator> getEvaluatorsByType(EvaluationType type);

    /**
     * Register an evaluator.
     *
     * @param evaluator The evaluator to register
     */
    void registerEvaluator(RagEvaluator evaluator);

    /**
     * Check if evaluation is enabled.
     *
     * @return true if evaluation is enabled
     */
    boolean isEnabled();
}
