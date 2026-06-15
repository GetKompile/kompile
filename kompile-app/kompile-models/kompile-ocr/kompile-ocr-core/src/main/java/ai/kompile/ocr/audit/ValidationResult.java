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

package ai.kompile.ocr.audit;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.List;

/**
 * Result of validation on OCR output.
 */
@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ValidationResult {

    /**
     * Unique identifier for this validation.
     */
    private String id;

    /**
     * What was validated (field name, table ID, etc).
     */
    private String target;

    /**
     * Type of validation performed.
     */
    private ValidationType validationType;

    /**
     * Validation outcome.
     */
    private ValidationOutcome outcome;

    /**
     * Human-readable message.
     */
    private String message;

    /**
     * Original value that was validated.
     */
    private String originalValue;

    /**
     * Corrected value if applicable.
     */
    private String correctedValue;

    /**
     * Confidence in the validation (0.0 to 1.0).
     */
    @Builder.Default
    private double confidence = 1.0;

    /**
     * Validator that performed the validation.
     */
    private String validatorId;

    /**
     * Timestamp of validation.
     */
    @Builder.Default
    private Instant timestamp = Instant.now();

    /**
     * Issues found during validation.
     */
    private List<ValidationIssue> issues;

    /**
     * Types of validation.
     */
    public enum ValidationType {
        FORMAT("Format validation"),
        RANGE("Range/bounds validation"),
        CROSS_REFERENCE("Cross-document validation"),
        CONSISTENCY("Internal consistency"),
        PATTERN("Pattern matching"),
        CUSTOM("Custom validation rule");

        private final String description;

        ValidationType(String description) {
            this.description = description;
        }

        public String getDescription() {
            return description;
        }
    }

    /**
     * Validation outcomes.
     */
    public enum ValidationOutcome {
        VALID,
        INVALID,
        WARNING,
        CORRECTED,
        SKIPPED,
        ERROR
    }

    /**
     * Individual validation issue.
     */
    @Data
    @Builder
    public static class ValidationIssue {
        private String code;
        private String message;
        private IssueSeverity severity;
        private String suggestion;

        public enum IssueSeverity {
            INFO,
            WARNING,
            ERROR,
            CRITICAL
        }

        public static ValidationIssue error(String code, String message) {
            return ValidationIssue.builder()
                    .code(code)
                    .message(message)
                    .severity(IssueSeverity.ERROR)
                    .build();
        }

        public static ValidationIssue warning(String code, String message) {
            return ValidationIssue.builder()
                    .code(code)
                    .message(message)
                    .severity(IssueSeverity.WARNING)
                    .build();
        }
    }

    /**
     * Creates a valid result.
     */
    public static ValidationResult valid(String target, ValidationType type, String validatorId) {
        return ValidationResult.builder()
                .target(target)
                .validationType(type)
                .outcome(ValidationOutcome.VALID)
                .validatorId(validatorId)
                .message("Validation passed")
                .timestamp(Instant.now())
                .build();
    }

    /**
     * Creates an invalid result.
     */
    public static ValidationResult invalid(String target, ValidationType type,
                                           String validatorId, String message) {
        return ValidationResult.builder()
                .target(target)
                .validationType(type)
                .outcome(ValidationOutcome.INVALID)
                .validatorId(validatorId)
                .message(message)
                .timestamp(Instant.now())
                .build();
    }

    /**
     * Creates a corrected result.
     */
    public static ValidationResult corrected(String target, ValidationType type,
                                             String validatorId, String original,
                                             String corrected) {
        return ValidationResult.builder()
                .target(target)
                .validationType(type)
                .outcome(ValidationOutcome.CORRECTED)
                .validatorId(validatorId)
                .originalValue(original)
                .correctedValue(corrected)
                .message("Value was corrected")
                .timestamp(Instant.now())
                .build();
    }

    /**
     * Checks if validation passed.
     */
    public boolean isValid() {
        return outcome == ValidationOutcome.VALID || outcome == ValidationOutcome.CORRECTED;
    }

    /**
     * Checks if validation has issues.
     */
    public boolean hasIssues() {
        return issues != null && !issues.isEmpty();
    }
}
