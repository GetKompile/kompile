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

import java.util.Map;

/**
 * Defines routing logic for LLM actions based on conditions.
 * Routing rules determine how the orchestrator should handle different scenarios.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PromptRoutingRule {

    /**
     * Unique identifier for this rule.
     */
    private String id;

    /**
     * Display name for the rule.
     */
    private String name;

    /**
     * Description of what this rule does.
     */
    private String description;

    /**
     * The condition that triggers this rule.
     */
    private PromptCondition condition;

    /**
     * The action to take when this rule matches.
     */
    @Builder.Default
    private RoutingAction action = RoutingAction.CONTINUE;

    /**
     * Target state for TRANSITION action.
     */
    private String targetState;

    /**
     * Target task for EXECUTE_TASK action.
     */
    private String targetTaskId;

    /**
     * Custom prompt for LLM_INVOKE action.
     */
    private String llmPrompt;

    /**
     * System prompt override for this routing.
     */
    private String systemPromptOverride;

    /**
     * Additional context to pass to the target.
     */
    private Map<String, Object> additionalContext;

    /**
     * Maximum retries for RETRY action.
     */
    @Builder.Default
    private int maxRetries = 3;

    /**
     * Delay in seconds between retries.
     */
    @Builder.Default
    private long retryDelaySeconds = 5;

    /**
     * Whether to use exponential backoff for retries.
     */
    @Builder.Default
    private boolean exponentialBackoff = true;

    /**
     * Priority for rule selection (higher = selected first).
     */
    @Builder.Default
    private int priority = 0;

    /**
     * Whether this rule is enabled.
     */
    @Builder.Default
    private boolean enabled = true;

    /**
     * Advice to include in LLM prompt when this rule matches.
     */
    private String advice;

    /**
     * Whether to stop processing after this rule matches.
     */
    @Builder.Default
    private boolean terminal = false;

    /**
     * Actions that can be taken by routing rules.
     */
    public enum RoutingAction {
        /**
         * Continue with normal processing.
         */
        CONTINUE,

        /**
         * Transition to a different state.
         */
        TRANSITION,

        /**
         * Retry the current operation.
         */
        RETRY,

        /**
         * Execute a specific task.
         */
        EXECUTE_TASK,

        /**
         * Invoke LLM with a specific prompt.
         */
        LLM_INVOKE,

        /**
         * Mark the workflow as failed.
         */
        FAIL,

        /**
         * Mark the workflow as completed.
         */
        COMPLETE,

        /**
         * Wait for external event or approval.
         */
        WAIT,

        /**
         * Escalate to human operator.
         */
        ESCALATE,

        /**
         * Skip the current step.
         */
        SKIP,

        /**
         * Execute a custom handler.
         */
        CUSTOM
    }

    /**
     * Check if this rule matches the given context.
     */
    public boolean matches(String stateId, String previousStateId, Map<String, Object> context,
                           String output, Integer exitCode, String classification, int retryCount) {
        if (!enabled) {
            return false;
        }

        if (condition == null) {
            return true;
        }

        return condition.evaluate(stateId, previousStateId, context, output, exitCode, classification, retryCount);
    }

    // Factory methods for common routing rules

    /**
     * Create a rule to retry on transient errors.
     */
    public static PromptRoutingRule retryOnTransientError() {
        return PromptRoutingRule.builder()
                .id("retry-transient")
                .name("Retry on Transient Error")
                .description("Retry when encountering transient errors like timeouts or connection issues")
                .condition(PromptCondition.or(
                        PromptCondition.matchesError("(?i)timeout|timed out"),
                        PromptCondition.matchesError("(?i)connection refused|reset|closed"),
                        PromptCondition.matchesError("(?i)temporary failure|try again"),
                        PromptCondition.matchesError("(?i)service unavailable|503")
                ))
                .action(RoutingAction.RETRY)
                .maxRetries(3)
                .retryDelaySeconds(5)
                .exponentialBackoff(true)
                .priority(100)
                .advice("This appears to be a transient error. Retrying may resolve the issue.")
                .build();
    }

    /**
     * Create a rule to escalate on critical errors.
     */
    public static PromptRoutingRule escalateOnCriticalError() {
        return PromptRoutingRule.builder()
                .id("escalate-critical")
                .name("Escalate on Critical Error")
                .description("Escalate to human operator on critical system errors")
                .condition(PromptCondition.or(
                        PromptCondition.matchesError("(?i)out of memory|OOM"),
                        PromptCondition.matchesError("(?i)disk full|no space"),
                        PromptCondition.matchesError("(?i)permission denied|access denied"),
                        PromptCondition.matchesError("SIGSEGV|Segmentation fault")
                ))
                .action(RoutingAction.ESCALATE)
                .priority(200)
                .terminal(true)
                .advice("This is a critical system error that requires human intervention.")
                .build();
    }

    /**
     * Create a rule to invoke LLM for compilation errors.
     */
    public static PromptRoutingRule llmForCompilationError() {
        return PromptRoutingRule.builder()
                .id("llm-compilation")
                .name("LLM for Compilation Error")
                .description("Use LLM to analyze and fix compilation errors")
                .condition(PromptCondition.matchesError("\\[ERROR\\].*\\.java:\\[\\d+"))
                .action(RoutingAction.LLM_INVOKE)
                .llmPrompt("""
                        A compilation error has occurred. Please analyze the error and provide a fix.

                        Error Output:
                        ```
                        {{output}}
                        ```

                        Provide:
                        1. Root cause analysis
                        2. Specific code fix with file path and line number
                        3. Explanation of why this fix resolves the issue
                        """)
                .priority(90)
                .advice("Let me analyze this compilation error and suggest a fix.")
                .build();
    }

    /**
     * Create a rule to transition on success.
     */
    public static PromptRoutingRule transitionOnSuccess(String targetState) {
        return PromptRoutingRule.builder()
                .id("success-transition-" + targetState)
                .name("Transition on Success")
                .description("Transition to " + targetState + " on successful completion")
                .condition(PromptCondition.and(
                        PromptCondition.exitCode(0),
                        PromptCondition.builder()
                                .type(PromptCondition.ConditionType.ERROR_PATTERN)
                                .pattern("(?i)error|fail|exception")
                                .operator(PromptCondition.ConditionOperator.NOT_EXISTS)
                                .build()
                ))
                .action(RoutingAction.TRANSITION)
                .targetState(targetState)
                .priority(50)
                .build();
    }

    /**
     * Create a rule to fail on too many retries.
     */
    public static PromptRoutingRule failOnMaxRetries(int maxRetries) {
        return PromptRoutingRule.builder()
                .id("fail-max-retries")
                .name("Fail on Max Retries")
                .description("Fail the workflow when maximum retries exceeded")
                .condition(PromptCondition.builder()
                        .type(PromptCondition.ConditionType.RETRY_COUNT)
                        .operator(PromptCondition.ConditionOperator.GREATER_OR_EQUAL)
                        .value(String.valueOf(maxRetries))
                        .build())
                .action(RoutingAction.FAIL)
                .priority(150)
                .terminal(true)
                .advice("Maximum retry attempts exceeded. Manual intervention may be required.")
                .build();
    }

    /**
     * Create a rule for test failures.
     */
    public static PromptRoutingRule llmForTestFailure() {
        return PromptRoutingRule.builder()
                .id("llm-test-failure")
                .name("LLM for Test Failure")
                .description("Use LLM to analyze and fix test failures")
                .condition(PromptCondition.matchesError("(?:FAILURE|FAILED).*Tests run:"))
                .action(RoutingAction.LLM_INVOKE)
                .llmPrompt("""
                        Tests have failed. Please analyze the failures and suggest fixes.

                        Test Output:
                        ```
                        {{output}}
                        ```

                        For each failing test:
                        1. Identify what the test is checking
                        2. Determine if this is a test bug or implementation bug
                        3. Provide the specific fix needed
                        """)
                .priority(85)
                .advice("Let me analyze these test failures.")
                .build();
    }
}
