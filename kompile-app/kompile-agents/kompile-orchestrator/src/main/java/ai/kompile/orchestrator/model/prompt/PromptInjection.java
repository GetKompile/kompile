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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Represents dynamic content to be injected into prompts based on conditions.
 * Injections can add advice, context, or routing instructions to LLM prompts.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PromptInjection {

    /**
     * Unique identifier for this injection.
     */
    private String id;

    /**
     * Display name for the injection.
     */
    private String name;

    /**
     * Description of what this injection does.
     */
    private String description;

    /**
     * Condition that must be met for this injection to apply.
     */
    private PromptCondition condition;

    /**
     * The content to inject.
     * Supports variable substitution with {{variableName}} syntax.
     */
    private String content;

    /**
     * Where in the prompt to inject the content.
     */
    @Builder.Default
    private InjectionPosition position = InjectionPosition.BEFORE_TASK;

    /**
     * Priority for ordering multiple injections (higher = earlier).
     */
    @Builder.Default
    private int priority = 0;

    /**
     * Whether this injection is enabled.
     */
    @Builder.Default
    private boolean enabled = true;

    /**
     * Tags for categorization.
     */
    private List<String> tags;

    /**
     * The type of injection (advice, context, routing, etc.).
     */
    @Builder.Default
    private InjectionType type = InjectionType.ADVICE;

    /**
     * For routing injections, the suggested next action.
     */
    private String suggestedAction;

    /**
     * For routing injections, the target state.
     */
    private String targetState;

    // Pattern for variable substitution
    private static final Pattern VARIABLE_PATTERN = Pattern.compile("\\{\\{([a-zA-Z_][a-zA-Z0-9_]*)}}");

    /**
     * Positions where content can be injected.
     */
    public enum InjectionPosition {
        /**
         * At the very beginning of the prompt.
         */
        START,

        /**
         * Before the system prompt.
         */
        BEFORE_SYSTEM,

        /**
         * After the system prompt.
         */
        AFTER_SYSTEM,

        /**
         * Before the task description.
         */
        BEFORE_TASK,

        /**
         * After the task description.
         */
        AFTER_TASK,

        /**
         * Before the output section.
         */
        BEFORE_OUTPUT,

        /**
         * After the output section.
         */
        AFTER_OUTPUT,

        /**
         * At the very end of the prompt.
         */
        END,

        /**
         * Replace a specific placeholder in the prompt.
         */
        PLACEHOLDER
    }

    /**
     * Types of injections.
     */
    public enum InjectionType {
        /**
         * Advice or guidance for the LLM.
         */
        ADVICE,

        /**
         * Additional context about the situation.
         */
        CONTEXT,

        /**
         * Routing instructions (suggest actions/states).
         */
        ROUTING,

        /**
         * Error-specific guidance.
         */
        ERROR_HANDLING,

        /**
         * Constraints or rules to follow.
         */
        CONSTRAINTS,

        /**
         * Examples to guide behavior.
         */
        EXAMPLES,

        /**
         * Custom injection type.
         */
        CUSTOM
    }

    /**
     * Render the injection content with variable substitution.
     *
     * @param variables Map of variable names to values
     * @return Rendered content
     */
    public String render(Map<String, Object> variables) {
        if (content == null) {
            return "";
        }

        if (variables == null || variables.isEmpty()) {
            // Remove unresolved variables
            return VARIABLE_PATTERN.matcher(content).replaceAll("");
        }

        Matcher matcher = VARIABLE_PATTERN.matcher(content);
        StringBuffer sb = new StringBuffer();

        while (matcher.find()) {
            String varName = matcher.group(1);
            Object value = variables.get(varName);
            String replacement = value != null ? Matcher.quoteReplacement(value.toString()) : "";
            matcher.appendReplacement(sb, replacement);
        }
        matcher.appendTail(sb);

        return sb.toString();
    }

    /**
     * Check if this injection applies to the current state/context.
     */
    public boolean applies(String stateId, String previousStateId, Map<String, Object> context,
                           String output, Integer exitCode, String classification, int retryCount) {
        if (!enabled) {
            return false;
        }

        if (condition == null) {
            return true; // No condition = always applies
        }

        return condition.evaluate(stateId, previousStateId, context, output, exitCode, classification, retryCount);
    }

    // Factory methods for common injection patterns

    /**
     * Create an advice injection for compilation errors.
     */
    public static PromptInjection compilationErrorAdvice() {
        return PromptInjection.builder()
                .id("compilation-error-advice")
                .name("Compilation Error Advice")
                .type(InjectionType.ERROR_HANDLING)
                .condition(PromptCondition.matchesError("\\[ERROR\\].*\\.java:\\[\\d+"))
                .content("""
                        ## Compilation Error Detected

                        The build has failed with compilation errors. Please:
                        1. Carefully read each error message and identify the root cause
                        2. Check for common issues: missing imports, typos, type mismatches
                        3. Look at the line numbers mentioned in the errors
                        4. If multiple errors exist, fix them in order as later errors may be caused by earlier ones
                        5. Propose specific code fixes with exact file paths and line numbers

                        """)
                .position(InjectionPosition.BEFORE_OUTPUT)
                .priority(100)
                .build();
    }

    /**
     * Create an advice injection for test failures.
     */
    public static PromptInjection testFailureAdvice() {
        return PromptInjection.builder()
                .id("test-failure-advice")
                .name("Test Failure Advice")
                .type(InjectionType.ERROR_HANDLING)
                .condition(PromptCondition.matchesError("(?:FAILURE|FAILED).*Tests run:"))
                .content("""
                        ## Test Failure Detected

                        One or more tests have failed. Please:
                        1. Identify which tests failed and why
                        2. Determine if this is a test bug or an implementation bug
                        3. Check the assertion messages for expected vs actual values
                        4. Look for patterns across multiple failing tests
                        5. Propose fixes for either the tests or the implementation

                        """)
                .position(InjectionPosition.BEFORE_OUTPUT)
                .priority(90)
                .build();
    }

    /**
     * Create an advice injection for runtime exceptions.
     */
    public static PromptInjection runtimeExceptionAdvice() {
        return PromptInjection.builder()
                .id("runtime-exception-advice")
                .name("Runtime Exception Advice")
                .type(InjectionType.ERROR_HANDLING)
                .condition(PromptCondition.matchesError("Exception in thread|at\\s+[a-zA-Z0-9.]+\\([^)]+\\.java:\\d+\\)"))
                .content("""
                        ## Runtime Exception Detected

                        A runtime exception has occurred. Please:
                        1. Identify the exception type and message
                        2. Trace the stack trace to find the origin of the error
                        3. Check for null pointer issues, array bounds, or resource problems
                        4. Consider if proper error handling is missing
                        5. Propose a fix that addresses the root cause, not just the symptom

                        """)
                .position(InjectionPosition.BEFORE_OUTPUT)
                .priority(95)
                .build();
    }

    /**
     * Create a routing injection for failed state.
     */
    public static PromptInjection failedStateRouting() {
        return PromptInjection.builder()
                .id("failed-state-routing")
                .name("Failed State Routing")
                .type(InjectionType.ROUTING)
                .condition(PromptCondition.forState("FAILED"))
                .content("""
                        ## Recovery Required

                        The workflow is in a FAILED state. Consider the following options:
                        - RETRY: Retry the last action if the failure was transient
                        - FIX: Propose and implement a fix for the underlying issue
                        - ROLLBACK: Revert changes and return to a known good state
                        - ESCALATE: If the issue is beyond automated resolution

                        Analyze the failure reason and recommend the best recovery approach.

                        """)
                .position(InjectionPosition.AFTER_SYSTEM)
                .priority(100)
                .suggestedAction("ANALYZE_FAILURE")
                .build();
    }

    /**
     * Create a context injection for retry scenarios.
     */
    public static PromptInjection retryContext(int maxRetries) {
        return PromptInjection.builder()
                .id("retry-context")
                .name("Retry Context")
                .type(InjectionType.CONTEXT)
                .condition(PromptCondition.builder()
                        .type(PromptCondition.ConditionType.RETRY_COUNT)
                        .operator(PromptCondition.ConditionOperator.GREATER_THAN)
                        .value("0")
                        .build())
                .content("""
                        ## Retry Attempt

                        This is retry attempt {{retryCount}} of {{maxRetries}}.
                        Previous attempts have failed. Consider:
                        - What was different about the previous attempts?
                        - Is there a pattern to the failures?
                        - Should a different approach be tried?

                        """)
                .position(InjectionPosition.BEFORE_TASK)
                .priority(80)
                .build();
    }

    /**
     * Create an advice injection for dependency errors.
     */
    public static PromptInjection dependencyErrorAdvice() {
        return PromptInjection.builder()
                .id("dependency-error-advice")
                .name("Dependency Error Advice")
                .type(InjectionType.ERROR_HANDLING)
                .condition(PromptCondition.or(
                        PromptCondition.matchesError("Could not resolve dependencies"),
                        PromptCondition.matchesError("dependency.*not found"),
                        PromptCondition.matchesError("NoClassDefFoundError"),
                        PromptCondition.matchesError("ClassNotFoundException")
                ))
                .content("""
                        ## Dependency Issue Detected

                        There appears to be a dependency problem. Please check:
                        1. Is the required dependency declared in pom.xml/build.gradle?
                        2. Is the correct version specified?
                        3. Are there conflicting versions of the same dependency?
                        4. Is the dependency available in the configured repositories?
                        5. For ClassNotFound errors, check if the class is in the correct package

                        """)
                .position(InjectionPosition.BEFORE_OUTPUT)
                .priority(85)
                .build();
    }

    /**
     * Create a constraints injection for safe operations.
     */
    public static PromptInjection safeOperationsConstraint() {
        return PromptInjection.builder()
                .id("safe-operations")
                .name("Safe Operations Constraint")
                .type(InjectionType.CONSTRAINTS)
                .condition(PromptCondition.always())
                .content("""
                        ## Safety Guidelines

                        When proposing changes:
                        - DO NOT modify production configuration files without explicit approval
                        - DO NOT delete files unless absolutely necessary
                        - DO NOT expose sensitive information in logs or outputs
                        - Prefer reversible changes over irreversible ones
                        - Create backups before modifying critical files

                        """)
                .position(InjectionPosition.END)
                .priority(10)
                .build();
    }
}
