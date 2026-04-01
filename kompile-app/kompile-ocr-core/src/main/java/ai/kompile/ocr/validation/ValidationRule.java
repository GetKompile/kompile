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

package ai.kompile.ocr.validation;

import ai.kompile.ocr.audit.ValidationResult;
import ai.kompile.ocr.structured.FieldType;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Configurable validation rule for OCR output.
 * Rules can be defined in configuration and applied dynamically.
 */
@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ValidationRule {

    /**
     * Unique identifier for this rule.
     */
    private String id;

    /**
     * Human-readable name.
     */
    private String name;

    /**
     * Description of what this rule validates.
     */
    private String description;

    /**
     * Field types this rule applies to.
     */
    private List<FieldType> applicableTypes;

    /**
     * Type of validation.
     */
    private RuleType ruleType;

    /**
     * Whether this rule is enabled.
     */
    @Builder.Default
    private boolean enabled = true;

    /**
     * Severity of validation failure.
     */
    @Builder.Default
    private ValidationResult.ValidationIssue.IssueSeverity severity =
            ValidationResult.ValidationIssue.IssueSeverity.ERROR;

    /**
     * Pattern for regex validation.
     */
    private String pattern;

    /**
     * Compiled pattern (transient).
     */
    private transient Pattern compiledPattern;

    /**
     * Minimum value for range validation.
     */
    private Double minValue;

    /**
     * Maximum value for range validation.
     */
    private Double maxValue;

    /**
     * Minimum length for string validation.
     */
    private Integer minLength;

    /**
     * Maximum length for string validation.
     */
    private Integer maxLength;

    /**
     * Allowed values for enum validation.
     */
    private List<String> allowedValues;

    /**
     * Error message template.
     */
    private String errorMessage;

    /**
     * Correction hint for users.
     */
    private String correctionHint;

    /**
     * Types of validation rules.
     */
    public enum RuleType {
        REGEX,          // Pattern matching
        RANGE,          // Numeric range
        LENGTH,         // String length
        ENUM,           // Allowed values
        DATE_FORMAT,    // Date format validation
        CUSTOM          // Custom validator class
    }

    /**
     * Gets the compiled regex pattern.
     */
    public Pattern getCompiledPattern() {
        if (compiledPattern == null && pattern != null) {
            compiledPattern = Pattern.compile(pattern);
        }
        return compiledPattern;
    }

    /**
     * Applies this rule to a value.
     */
    public ValidationResult apply(String value, FieldType fieldType) {
        if (!enabled) {
            return ValidationResult.builder()
                    .target(id)
                    .validationType(ValidationResult.ValidationType.CUSTOM)
                    .outcome(ValidationResult.ValidationOutcome.SKIPPED)
                    .message("Rule is disabled")
                    .build();
        }

        if (applicableTypes != null && !applicableTypes.isEmpty() &&
            !applicableTypes.contains(fieldType)) {
            return ValidationResult.builder()
                    .target(id)
                    .validationType(ValidationResult.ValidationType.CUSTOM)
                    .outcome(ValidationResult.ValidationOutcome.SKIPPED)
                    .message("Rule not applicable to field type")
                    .build();
        }

        return switch (ruleType) {
            case REGEX -> applyRegex(value);
            case RANGE -> applyRange(value);
            case LENGTH -> applyLength(value);
            case ENUM -> applyEnum(value);
            case DATE_FORMAT -> applyDateFormat(value);
            case CUSTOM -> ValidationResult.builder()
                    .target(id)
                    .outcome(ValidationResult.ValidationOutcome.SKIPPED)
                    .message("Custom validation requires custom validator")
                    .build();
        };
    }

    private ValidationResult applyRegex(String value) {
        if (pattern == null) {
            return createInvalid("No pattern defined");
        }
        boolean matches = getCompiledPattern().matcher(value).matches();
        if (matches) {
            return createValid();
        }
        return createInvalid(errorMessage != null ? errorMessage :
                "Value does not match pattern: " + pattern);
    }

    private ValidationResult applyRange(String value) {
        try {
            double numValue = Double.parseDouble(value.replaceAll("[^\\d.-]", ""));
            if (minValue != null && numValue < minValue) {
                return createInvalid("Value " + numValue + " is below minimum " + minValue);
            }
            if (maxValue != null && numValue > maxValue) {
                return createInvalid("Value " + numValue + " is above maximum " + maxValue);
            }
            return createValid();
        } catch (NumberFormatException e) {
            return createInvalid("Value is not a valid number");
        }
    }

    private ValidationResult applyLength(String value) {
        int length = value != null ? value.length() : 0;
        if (minLength != null && length < minLength) {
            return createInvalid("Value length " + length + " is below minimum " + minLength);
        }
        if (maxLength != null && length > maxLength) {
            return createInvalid("Value length " + length + " exceeds maximum " + maxLength);
        }
        return createValid();
    }

    private ValidationResult applyEnum(String value) {
        if (allowedValues == null || allowedValues.isEmpty()) {
            return createInvalid("No allowed values defined");
        }
        if (allowedValues.contains(value)) {
            return createValid();
        }
        return createInvalid("Value '" + value + "' is not in allowed values: " + allowedValues);
    }

    private ValidationResult applyDateFormat(String value) {
        // Basic date format validation
        String[] formats = {
            "\\d{4}-\\d{2}-\\d{2}",           // YYYY-MM-DD
            "\\d{2}/\\d{2}/\\d{4}",           // MM/DD/YYYY
            "\\d{2}-\\d{2}-\\d{4}",           // DD-MM-YYYY
            "\\d{1,2}/\\d{1,2}/\\d{2,4}"      // M/D/YY or MM/DD/YYYY
        };
        for (String fmt : formats) {
            if (value.matches(fmt)) {
                return createValid();
            }
        }
        return createInvalid("Value does not match any known date format");
    }

    private ValidationResult createValid() {
        return ValidationResult.builder()
                .target(id)
                .validationType(ValidationResult.ValidationType.CUSTOM)
                .outcome(ValidationResult.ValidationOutcome.VALID)
                .validatorId(id)
                .message("Validation passed")
                .build();
    }

    private ValidationResult createInvalid(String message) {
        return ValidationResult.builder()
                .target(id)
                .validationType(ValidationResult.ValidationType.CUSTOM)
                .outcome(ValidationResult.ValidationOutcome.INVALID)
                .validatorId(id)
                .message(message)
                .issues(List.of(ValidationResult.ValidationIssue.builder()
                        .code(id)
                        .message(message)
                        .severity(severity)
                        .suggestion(correctionHint)
                        .build()))
                .build();
    }

    /**
     * Creates a regex validation rule.
     */
    public static ValidationRule regex(String id, String name, String pattern,
                                       List<FieldType> types) {
        return ValidationRule.builder()
                .id(id)
                .name(name)
                .ruleType(RuleType.REGEX)
                .pattern(pattern)
                .applicableTypes(types)
                .build();
    }

    /**
     * Creates a range validation rule.
     */
    public static ValidationRule range(String id, String name, Double min, Double max,
                                       List<FieldType> types) {
        return ValidationRule.builder()
                .id(id)
                .name(name)
                .ruleType(RuleType.RANGE)
                .minValue(min)
                .maxValue(max)
                .applicableTypes(types)
                .build();
    }
}
