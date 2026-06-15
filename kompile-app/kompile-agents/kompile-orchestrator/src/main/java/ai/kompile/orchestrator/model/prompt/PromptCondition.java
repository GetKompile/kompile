/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ai.kompile.orchestrator.model.prompt;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Represents a condition for prompt injection or routing.
 * Conditions are evaluated against the current state and context.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PromptCondition {

    /**
     * Type of condition to evaluate.
     */
    private ConditionType type;

    /**
     * The field or context key to evaluate.
     */
    private String field;

    /**
     * The operator for comparison.
     */
    @Builder.Default
    private ConditionOperator operator = ConditionOperator.EQUALS;

    /**
     * The value to compare against.
     */
    private String value;

    /**
     * List of values for IN/NOT_IN operators.
     */
    private List<String> values;

    /**
     * Pattern for MATCHES operator (regex).
     */
    private String pattern;

    /**
     * Nested conditions for AND/OR logic.
     */
    private List<PromptCondition> conditions;

    /**
     * Whether all nested conditions must match (AND) or any (OR).
     */
    @Builder.Default
    private boolean matchAll = true;

    /**
     * Compiled pattern for regex matching (transient).
     */
    private transient Pattern compiledPattern;

    /**
     * Condition types.
     */
    public enum ConditionType {
        /**
         * Check the current state ID.
         */
        STATE,

        /**
         * Check the previous state ID.
         */
        PREVIOUS_STATE,

        /**
         * Check a context variable.
         */
        CONTEXT,

        /**
         * Check the task exit code.
         */
        EXIT_CODE,

        /**
         * Check for error patterns in output.
         */
        ERROR_PATTERN,

        /**
         * Check for success patterns in output.
         */
        SUCCESS_PATTERN,

        /**
         * Check the classification type (from output classifier).
         */
        CLASSIFICATION,

        /**
         * Check the retry count.
         */
        RETRY_COUNT,

        /**
         * Composite condition (AND/OR of nested conditions).
         */
        COMPOSITE,

        /**
         * Always matches.
         */
        ALWAYS
    }

    /**
     * Comparison operators.
     */
    public enum ConditionOperator {
        EQUALS,
        NOT_EQUALS,
        CONTAINS,
        NOT_CONTAINS,
        STARTS_WITH,
        ENDS_WITH,
        MATCHES,       // Regex match
        IN,            // Value in list
        NOT_IN,        // Value not in list
        GREATER_THAN,
        LESS_THAN,
        GREATER_OR_EQUAL,
        LESS_OR_EQUAL,
        EXISTS,        // Field exists and is not null
        NOT_EXISTS     // Field does not exist or is null
    }

    /**
     * Evaluate this condition against the given context.
     *
     * @param stateId        Current state ID
     * @param previousStateId Previous state ID
     * @param context        Context map
     * @param output         Task/command output
     * @param exitCode       Exit code (if applicable)
     * @param classification Classification type (if applicable)
     * @param retryCount     Current retry count
     * @return true if condition matches
     */
    public boolean evaluate(String stateId, String previousStateId, Map<String, Object> context,
                            String output, Integer exitCode, String classification, int retryCount) {
        if (type == null) {
            return false;
        }

        switch (type) {
            case STATE:
                return evaluateString(stateId);

            case PREVIOUS_STATE:
                return evaluateString(previousStateId);

            case CONTEXT:
                Object contextValue = context != null ? context.get(field) : null;
                return evaluateValue(contextValue);

            case EXIT_CODE:
                return evaluateNumber(exitCode != null ? exitCode : -1);

            case ERROR_PATTERN:
            case SUCCESS_PATTERN:
                return evaluatePattern(output);

            case CLASSIFICATION:
                return evaluateString(classification);

            case RETRY_COUNT:
                return evaluateNumber(retryCount);

            case COMPOSITE:
                return evaluateComposite(stateId, previousStateId, context, output, exitCode, classification, retryCount);

            case ALWAYS:
                return true;

            default:
                return false;
        }
    }

    private boolean evaluateString(String actualValue) {
        if (actualValue == null && operator != ConditionOperator.EXISTS && operator != ConditionOperator.NOT_EXISTS) {
            return operator == ConditionOperator.NOT_EXISTS;
        }

        switch (operator) {
            case EQUALS:
                return value != null && value.equals(actualValue);
            case NOT_EQUALS:
                return value == null || !value.equals(actualValue);
            case CONTAINS:
                return actualValue != null && value != null && actualValue.contains(value);
            case NOT_CONTAINS:
                return actualValue == null || value == null || !actualValue.contains(value);
            case STARTS_WITH:
                return actualValue != null && value != null && actualValue.startsWith(value);
            case ENDS_WITH:
                return actualValue != null && value != null && actualValue.endsWith(value);
            case MATCHES:
                return matchesPattern(actualValue);
            case IN:
                return values != null && values.contains(actualValue);
            case NOT_IN:
                return values == null || !values.contains(actualValue);
            case EXISTS:
                return actualValue != null;
            case NOT_EXISTS:
                return actualValue == null;
            default:
                return false;
        }
    }

    private boolean evaluateValue(Object actualValue) {
        if (actualValue == null) {
            return operator == ConditionOperator.NOT_EXISTS ||
                   (operator == ConditionOperator.NOT_EQUALS && value != null);
        }

        String stringValue = actualValue.toString();

        // Try numeric comparison if applicable
        if (actualValue instanceof Number) {
            try {
                return evaluateNumber(((Number) actualValue).intValue());
            } catch (Exception e) {
                // Fall through to string comparison
            }
        }

        return evaluateString(stringValue);
    }

    private boolean evaluateNumber(int actualValue) {
        if (value == null) {
            return operator == ConditionOperator.NOT_EXISTS;
        }

        try {
            int compareValue = Integer.parseInt(value);
            switch (operator) {
                case EQUALS:
                    return actualValue == compareValue;
                case NOT_EQUALS:
                    return actualValue != compareValue;
                case GREATER_THAN:
                    return actualValue > compareValue;
                case LESS_THAN:
                    return actualValue < compareValue;
                case GREATER_OR_EQUAL:
                    return actualValue >= compareValue;
                case LESS_OR_EQUAL:
                    return actualValue <= compareValue;
                case IN:
                    return values != null && values.stream()
                            .anyMatch(v -> {
                                try {
                                    return Integer.parseInt(v) == actualValue;
                                } catch (NumberFormatException e) {
                                    return false;
                                }
                            });
                case NOT_IN:
                    return values == null || values.stream()
                            .noneMatch(v -> {
                                try {
                                    return Integer.parseInt(v) == actualValue;
                                } catch (NumberFormatException e) {
                                    return true;
                                }
                            });
                default:
                    return evaluateString(String.valueOf(actualValue));
            }
        } catch (NumberFormatException e) {
            return evaluateString(String.valueOf(actualValue));
        }
    }

    private boolean evaluatePattern(String output) {
        if (output == null || pattern == null) {
            return false;
        }
        return matchesPattern(output);
    }

    private boolean matchesPattern(String text) {
        if (text == null || pattern == null) {
            return false;
        }

        if (compiledPattern == null) {
            try {
                compiledPattern = Pattern.compile(pattern, Pattern.MULTILINE | Pattern.DOTALL);
            } catch (Exception e) {
                return false;
            }
        }

        return compiledPattern.matcher(text).find();
    }

    private boolean evaluateComposite(String stateId, String previousStateId, Map<String, Object> context,
                                       String output, Integer exitCode, String classification, int retryCount) {
        if (conditions == null || conditions.isEmpty()) {
            return true;
        }

        if (matchAll) {
            // AND logic
            return conditions.stream().allMatch(c ->
                    c.evaluate(stateId, previousStateId, context, output, exitCode, classification, retryCount));
        } else {
            // OR logic
            return conditions.stream().anyMatch(c ->
                    c.evaluate(stateId, previousStateId, context, output, exitCode, classification, retryCount));
        }
    }

    // Factory methods

    /**
     * Create a condition that matches a specific state.
     */
    public static PromptCondition forState(String stateId) {
        return PromptCondition.builder()
                .type(ConditionType.STATE)
                .operator(ConditionOperator.EQUALS)
                .value(stateId)
                .build();
    }

    /**
     * Create a condition that matches when transitioning from a specific state.
     */
    public static PromptCondition fromState(String previousStateId) {
        return PromptCondition.builder()
                .type(ConditionType.PREVIOUS_STATE)
                .operator(ConditionOperator.EQUALS)
                .value(previousStateId)
                .build();
    }

    /**
     * Create a condition that matches an error pattern.
     */
    public static PromptCondition matchesError(String errorPattern) {
        return PromptCondition.builder()
                .type(ConditionType.ERROR_PATTERN)
                .pattern(errorPattern)
                .build();
    }

    /**
     * Create a condition that matches exit code.
     */
    public static PromptCondition exitCode(int code) {
        return PromptCondition.builder()
                .type(ConditionType.EXIT_CODE)
                .operator(ConditionOperator.EQUALS)
                .value(String.valueOf(code))
                .build();
    }

    /**
     * Create a condition for non-zero exit code.
     */
    public static PromptCondition exitCodeNonZero() {
        return PromptCondition.builder()
                .type(ConditionType.EXIT_CODE)
                .operator(ConditionOperator.NOT_EQUALS)
                .value("0")
                .build();
    }

    /**
     * Create a condition that matches a context value.
     */
    public static PromptCondition contextEquals(String key, String value) {
        return PromptCondition.builder()
                .type(ConditionType.CONTEXT)
                .field(key)
                .operator(ConditionOperator.EQUALS)
                .value(value)
                .build();
    }

    /**
     * Create a condition that matches a classification type.
     */
    public static PromptCondition classification(String classificationType) {
        return PromptCondition.builder()
                .type(ConditionType.CLASSIFICATION)
                .operator(ConditionOperator.EQUALS)
                .value(classificationType)
                .build();
    }

    /**
     * Create an AND condition.
     */
    public static PromptCondition and(PromptCondition... conditions) {
        return PromptCondition.builder()
                .type(ConditionType.COMPOSITE)
                .conditions(List.of(conditions))
                .matchAll(true)
                .build();
    }

    /**
     * Create an OR condition.
     */
    public static PromptCondition or(PromptCondition... conditions) {
        return PromptCondition.builder()
                .type(ConditionType.COMPOSITE)
                .conditions(List.of(conditions))
                .matchAll(false)
                .build();
    }

    /**
     * Create a condition that always matches.
     */
    public static PromptCondition always() {
        return PromptCondition.builder()
                .type(ConditionType.ALWAYS)
                .build();
    }
}
