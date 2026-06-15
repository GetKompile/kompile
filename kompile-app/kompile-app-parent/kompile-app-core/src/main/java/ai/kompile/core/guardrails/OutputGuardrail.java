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
 * Interface for output guardrails that validate LLM responses.
 * <p>
 * Output guardrails can detect and handle:
 * <ul>
 *   <li>Hallucinations</li>
 *   <li>Invalid output formats</li>
 *   <li>Business rule violations</li>
 *   <li>Harmful or toxic responses</li>
 *   <li>PII in responses</li>
 * </ul>
 * <p>
 * Unlike input guardrails, output guardrails can trigger retry/reprompt
 * to give the LLM another chance to produce valid output.
 */
public interface OutputGuardrail {

    /**
     * Validate the LLM output.
     *
     * @param output The LLM output to validate
     * @param originalQuery The original user query (for context)
     * @param retrievedContext The retrieved documents used for RAG (if any)
     * @param context Additional context for validation
     * @return The validation result
     */
    GuardrailResult validate(String output, String originalQuery,
                             List<String> retrievedContext, GuardrailContext context);

    /**
     * Validate the LLM output with minimal context.
     *
     * @param output The LLM output to validate
     * @param originalQuery The original user query
     * @return The validation result
     */
    default GuardrailResult validate(String output, String originalQuery) {
        return validate(output, originalQuery, List.of(), GuardrailContext.empty());
    }

    /**
     * Get the name of this guardrail.
     *
     * @return The guardrail name
     */
    String getName();

    /**
     * Get the categories this guardrail checks for.
     *
     * @return Array of categories this guardrail detects
     */
    GuardrailCategory[] getCategories();

    /**
     * Check if this guardrail supports retry on failure.
     * If true, the system can request a new response from the LLM.
     *
     * @return true if retry is supported
     */
    default boolean supportsRetry() {
        return false;
    }

    /**
     * Get the maximum number of retries this guardrail allows.
     *
     * @return Maximum retry count (default 2)
     */
    default int getMaxRetries() {
        return 2;
    }

    /**
     * Generate a reprompt instruction for retry attempts.
     * Called when the guardrail fails and supports retry.
     *
     * @param result The failed guardrail result
     * @param attemptNumber The current retry attempt (1-indexed)
     * @return Instructions to prepend to the retry prompt
     */
    default String generateRepromptInstruction(GuardrailResult result, int attemptNumber) {
        return "Please revise your response. " + result.getFailureReason();
    }

    /**
     * Get the priority of this guardrail (lower = higher priority).
     *
     * @return The priority (default 100)
     */
    default int getPriority() {
        return 100;
    }

    /**
     * Check if this guardrail is enabled.
     *
     * @return true if enabled
     */
    default boolean isEnabled() {
        return true;
    }

    /**
     * Check if this guardrail requires an LLM for detection.
     *
     * @return true if an LLM is required
     */
    default boolean requiresLlm() {
        return false;
    }
}
