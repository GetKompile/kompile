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

import java.util.Map;

/**
 * Interface for input guardrails that validate user input before sending to LLM.
 * <p>
 * Input guardrails can detect and prevent:
 * <ul>
 *   <li>Prompt injection attacks</li>
 *   <li>Jailbreak attempts</li>
 *   <li>Toxic or harmful content</li>
 *   <li>PII exposure</li>
 *   <li>Off-topic queries</li>
 * </ul>
 * <p>
 * Unlike output guardrails, input guardrails do not support retry/reprompt.
 * Failures result in immediate rejection with a GuardrailException.
 */
public interface InputGuardrail {

    /**
     * Validate the input before sending to the LLM.
     *
     * @param input The user input to validate
     * @param context Additional context for validation
     * @return The validation result
     */
    GuardrailResult validate(String input, GuardrailContext context);

    /**
     * Validate the input with default context.
     *
     * @param input The user input to validate
     * @return The validation result
     */
    default GuardrailResult validate(String input) {
        return validate(input, GuardrailContext.empty());
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
     * Get the priority of this guardrail (lower = higher priority).
     * Guardrails are executed in priority order.
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
