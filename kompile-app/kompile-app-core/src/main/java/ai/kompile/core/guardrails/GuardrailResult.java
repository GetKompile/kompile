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

import lombok.Builder;
import lombok.Data;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Result of a guardrail validation check.
 */
@Data
@Builder
public class GuardrailResult {

    /**
     * Whether the validation passed.
     */
    private boolean passed;

    /**
     * The action to take based on the result.
     */
    @Builder.Default
    private GuardrailAction action = GuardrailAction.CONTINUE;

    /**
     * Reason for failure (if failed).
     */
    private String failureReason;

    /**
     * Category of the failure (if failed).
     */
    private GuardrailCategory category;

    /**
     * Confidence score of the detection (0.0 to 1.0).
     */
    @Builder.Default
    private double confidence = 1.0;

    /**
     * The guardrail name that produced this result.
     */
    private String guardrailName;

    /**
     * Detailed violations found.
     */
    @Builder.Default
    private List<Violation> violations = Collections.emptyList();

    /**
     * Suggested corrections or alternatives.
     */
    @Builder.Default
    private List<String> suggestions = Collections.emptyList();

    /**
     * Additional metadata about the check.
     */
    @Builder.Default
    private Map<String, Object> metadata = Collections.emptyMap();

    /**
     * Create a passing result.
     *
     * @param guardrailName The name of the guardrail
     * @return A passing GuardrailResult
     */
    public static GuardrailResult pass(String guardrailName) {
        return GuardrailResult.builder()
                .passed(true)
                .action(GuardrailAction.CONTINUE)
                .guardrailName(guardrailName)
                .build();
    }

    /**
     * Create a failing result that blocks the request.
     *
     * @param guardrailName The name of the guardrail
     * @param reason The failure reason
     * @param category The failure category
     * @return A blocking GuardrailResult
     */
    public static GuardrailResult block(String guardrailName, String reason, GuardrailCategory category) {
        return GuardrailResult.builder()
                .passed(false)
                .action(GuardrailAction.BLOCK)
                .failureReason(reason)
                .category(category)
                .guardrailName(guardrailName)
                .build();
    }

    /**
     * Create a failing result that requests a retry.
     *
     * @param guardrailName The name of the guardrail
     * @param reason The failure reason
     * @param suggestions Suggestions for the retry
     * @return A retry GuardrailResult
     */
    public static GuardrailResult retry(String guardrailName, String reason, List<String> suggestions) {
        return GuardrailResult.builder()
                .passed(false)
                .action(GuardrailAction.RETRY)
                .failureReason(reason)
                .guardrailName(guardrailName)
                .suggestions(suggestions != null ? suggestions : Collections.emptyList())
                .build();
    }

    /**
     * Create a warning result that allows continuation with a flag.
     *
     * @param guardrailName The name of the guardrail
     * @param reason The warning reason
     * @return A warning GuardrailResult
     */
    public static GuardrailResult warn(String guardrailName, String reason) {
        return GuardrailResult.builder()
                .passed(true)
                .action(GuardrailAction.WARN)
                .failureReason(reason)
                .guardrailName(guardrailName)
                .build();
    }

    /**
     * A specific violation detected by a guardrail.
     */
    @Data
    @Builder
    public static class Violation {
        /**
         * Type of violation.
         */
        private String type;

        /**
         * Description of the violation.
         */
        private String description;

        /**
         * The offending content (if applicable).
         */
        private String content;

        /**
         * Position in the text (if applicable).
         */
        private Integer position;

        /**
         * Severity of the violation.
         */
        @Builder.Default
        private ViolationSeverity severity = ViolationSeverity.MEDIUM;
    }

    /**
     * Severity levels for violations.
     */
    public enum ViolationSeverity {
        LOW,
        MEDIUM,
        HIGH,
        CRITICAL
    }
}
