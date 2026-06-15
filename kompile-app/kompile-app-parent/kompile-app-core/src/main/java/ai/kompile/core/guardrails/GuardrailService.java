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

package ai.kompile.core.guardrails;

import java.util.List;

/**
 * Service for orchestrating guardrail checks.
 * <p>
 * This service manages the execution of multiple input and output guardrails
 * in priority order and aggregates their results.
 */
public interface GuardrailService {

    /**
     * Validate input against all registered input guardrails.
     *
     * @param input The input to validate
     * @param context The guardrail context
     * @return Aggregated result from all guardrails
     * @throws GuardrailException if any guardrail blocks the request
     */
    GuardrailResult validateInput(String input, GuardrailContext context);

    /**
     * Validate output against all registered output guardrails.
     *
     * @param output The LLM output to validate
     * @param originalQuery The original user query
     * @param retrievedContext Retrieved documents (for RAG)
     * @param context The guardrail context
     * @return Aggregated result from all guardrails
     */
    GuardrailResult validateOutput(String output, String originalQuery,
                                   List<String> retrievedContext, GuardrailContext context);

    /**
     * Get all registered input guardrails.
     *
     * @return List of input guardrails in priority order
     */
    List<InputGuardrail> getInputGuardrails();

    /**
     * Get all registered output guardrails.
     *
     * @return List of output guardrails in priority order
     */
    List<OutputGuardrail> getOutputGuardrails();

    /**
     * Register an input guardrail.
     *
     * @param guardrail The guardrail to register
     */
    void registerInputGuardrail(InputGuardrail guardrail);

    /**
     * Register an output guardrail.
     *
     * @param guardrail The guardrail to register
     */
    void registerOutputGuardrail(OutputGuardrail guardrail);

    /**
     * Check if guardrails are enabled.
     *
     * @return true if guardrails are enabled
     */
    boolean isEnabled();
}
