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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Configuration for state-specific prompts and routing.
 * Each state can have its own master prompt, injections, and routing rules.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StatePromptConfig {

    /**
     * The state ID this config applies to.
     */
    private String stateId;

    /**
     * Display name for the state.
     */
    private String displayName;

    /**
     * Description of what this state does.
     */
    private String description;

    /**
     * The master prompt template for this state.
     * This is the base prompt that injections are added to.
     * Supports variable substitution with {{variableName}} syntax.
     */
    private String masterPrompt;

    /**
     * System prompt for LLM interactions in this state.
     */
    private String systemPrompt;

    /**
     * List of prompt injections that can be applied in this state.
     */
    @Builder.Default
    private List<PromptInjection> injections = new ArrayList<>();

    /**
     * Routing rules for this state based on conditions.
     */
    @Builder.Default
    private List<PromptRoutingRule> routingRules = new ArrayList<>();

    /**
     * Default variables for prompt rendering.
     */
    @Builder.Default
    private Map<String, Object> defaultVariables = new HashMap<>();

    /**
     * Whether this config is enabled.
     */
    @Builder.Default
    private boolean enabled = true;

    /**
     * Priority for config selection when multiple configs match.
     */
    @Builder.Default
    private int priority = 0;

    /**
     * Transition-specific prompt configs.
     * Key is "fromState:toState" pattern.
     */
    @Builder.Default
    private Map<String, TransitionPromptConfig> transitionConfigs = new HashMap<>();

    /**
     * Error type to advice mapping.
     */
    @Builder.Default
    private Map<String, String> errorAdvice = new HashMap<>();

    /**
     * Output format hint for this state.
     */
    private String outputFormat;

    /**
     * Maximum tokens for LLM response.
     */
    private Integer maxTokens;

    /**
     * Temperature for LLM response.
     */
    private Double temperature;

    /**
     * Get the config key for a transition.
     */
    public static String transitionKey(String fromState, String toState) {
        return fromState + ":" + toState;
    }

    /**
     * Get transition-specific config if available.
     */
    public TransitionPromptConfig getTransitionConfig(String fromState) {
        if (transitionConfigs == null) {
            return null;
        }
        return transitionConfigs.get(transitionKey(fromState, stateId));
    }

    /**
     * Add an injection to this config.
     */
    public StatePromptConfig addInjection(PromptInjection injection) {
        if (injections == null) {
            injections = new ArrayList<>();
        }
        injections.add(injection);
        return this;
    }

    /**
     * Add a routing rule to this config.
     */
    public StatePromptConfig addRoutingRule(PromptRoutingRule rule) {
        if (routingRules == null) {
            routingRules = new ArrayList<>();
        }
        routingRules.add(rule);
        return this;
    }

    /**
     * Add error advice mapping.
     */
    public StatePromptConfig addErrorAdvice(String errorType, String advice) {
        if (errorAdvice == null) {
            errorAdvice = new HashMap<>();
        }
        errorAdvice.put(errorType, advice);
        return this;
    }

    /**
     * Get applicable injections for the given context.
     */
    public List<PromptInjection> getApplicableInjections(String previousStateId, Map<String, Object> context,
                                                          String output, Integer exitCode,
                                                          String classification, int retryCount) {
        if (injections == null) {
            return List.of();
        }

        return injections.stream()
                .filter(i -> i.applies(stateId, previousStateId, context, output, exitCode, classification, retryCount))
                .sorted((a, b) -> Integer.compare(b.getPriority(), a.getPriority()))
                .toList();
    }

    /**
     * Get the first matching routing rule for the given context.
     */
    public PromptRoutingRule getMatchingRoutingRule(String previousStateId, Map<String, Object> context,
                                                     String output, Integer exitCode,
                                                     String classification, int retryCount) {
        if (routingRules == null) {
            return null;
        }

        return routingRules.stream()
                .filter(PromptRoutingRule::isEnabled)
                .filter(r -> r.matches(stateId, previousStateId, context, output, exitCode, classification, retryCount))
                .sorted((a, b) -> Integer.compare(b.getPriority(), a.getPriority()))
                .findFirst()
                .orElse(null);
    }

    /**
     * Configuration for transition-specific prompts.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TransitionPromptConfig {

        /**
         * Source state.
         */
        private String fromState;

        /**
         * Target state.
         */
        private String toState;

        /**
         * Additional prompt content for this transition.
         */
        private String transitionPrompt;

        /**
         * System prompt override for this transition.
         */
        private String systemPromptOverride;

        /**
         * Variables specific to this transition.
         */
        private Map<String, Object> transitionVariables;

        /**
         * Additional injections for this transition.
         */
        private List<PromptInjection> transitionInjections;
    }

    // Factory methods for common state configs

    /**
     * Create config for EXECUTING state.
     */
    public static StatePromptConfig executingState() {
        return StatePromptConfig.builder()
                .stateId("EXECUTING")
                .displayName("Executing")
                .description("State for executing tasks and commands")
                .masterPrompt("""
                        You are executing a task as part of an automated workflow.

                        ## Current Task
                        {{taskDescription}}

                        ## Task Details
                        - Task ID: {{taskId}}
                        - Working Directory: {{workingDirectory}}
                        {{#if previousOutput}}
                        - Previous Output: {{previousOutput}}
                        {{/if}}

                        Please execute the task and report the results.
                        """)
                .systemPrompt("You are an automated workflow executor. Execute tasks precisely and report results accurately.")
                .build()
                .addInjection(PromptInjection.compilationErrorAdvice())
                .addInjection(PromptInjection.testFailureAdvice())
                .addInjection(PromptInjection.runtimeExceptionAdvice());
    }

    /**
     * Create config for FAILED state.
     */
    public static StatePromptConfig failedState() {
        return StatePromptConfig.builder()
                .stateId("FAILED")
                .displayName("Failed")
                .description("State for handling failures and recovery")
                .masterPrompt("""
                        A failure has occurred in the workflow and requires analysis.

                        ## Failure Details
                        - Error Type: {{errorType}}
                        - Error Message: {{errorMessage}}
                        - Exit Code: {{exitCode}}
                        {{#if retryCount}}
                        - Retry Attempt: {{retryCount}} of {{maxRetries}}
                        {{/if}}

                        ## Output
                        ```
                        {{output}}
                        ```

                        Please analyze the failure and recommend next steps.
                        """)
                .systemPrompt("You are a failure analysis expert. Identify root causes and provide actionable recovery recommendations.")
                .build()
                .addInjection(PromptInjection.failedStateRouting())
                .addInjection(PromptInjection.dependencyErrorAdvice())
                .addRoutingRule(PromptRoutingRule.builder()
                        .id("retry-on-transient")
                        .name("Retry on Transient Error")
                        .condition(PromptCondition.or(
                                PromptCondition.matchesError("timeout|timed out"),
                                PromptCondition.matchesError("connection refused"),
                                PromptCondition.matchesError("temporary failure")
                        ))
                        .targetState("EXECUTING")
                        .action(PromptRoutingRule.RoutingAction.RETRY)
                        .priority(100)
                        .build())
                .addErrorAdvice("COMPILATION_ERROR", "Check syntax and imports. Review the error line numbers carefully.")
                .addErrorAdvice("RUNTIME_ERROR", "Analyze the stack trace. Look for null pointers and resource issues.")
                .addErrorAdvice("TEST_FAILURE", "Compare expected vs actual values. Check test data and assertions.");
    }

    /**
     * Create config for WAITING state.
     */
    public static StatePromptConfig waitingState() {
        return StatePromptConfig.builder()
                .stateId("WAITING")
                .displayName("Waiting")
                .description("State for waiting for external events or approval")
                .masterPrompt("""
                        The workflow is waiting for {{waitReason}}.

                        ## Current Status
                        - Waiting Since: {{waitStartTime}}
                        - Expected Event: {{expectedEvent}}
                        {{#if timeout}}
                        - Timeout: {{timeout}}
                        {{/if}}

                        Please provide status update or take action if the wait condition is met.
                        """)
                .systemPrompt("You are monitoring a workflow that is waiting for an external event or approval.")
                .build();
    }

    /**
     * Create config for ANALYZING state.
     */
    public static StatePromptConfig analyzingState() {
        return StatePromptConfig.builder()
                .stateId("ANALYZING")
                .displayName("Analyzing")
                .description("State for analyzing output and determining next steps")
                .masterPrompt("""
                        Analyze the following output and determine the next action.

                        ## Task Output
                        - Task: {{taskName}}
                        - Status: {{taskStatus}}
                        - Exit Code: {{exitCode}}

                        ## Output Content
                        ```
                        {{output}}
                        ```

                        Based on this output:
                        1. Summarize what happened
                        2. Identify any issues or concerns
                        3. Recommend the next action

                        Respond with a JSON containing:
                        - analysis: Your analysis summary
                        - issues: List of identified issues
                        - recommendation: Recommended next action
                        - confidence: Your confidence level (0-1)
                        """)
                .systemPrompt("You are an output analysis expert. Provide accurate analysis and actionable recommendations.")
                .outputFormat("json")
                .build()
                .addInjection(PromptInjection.safeOperationsConstraint());
    }
}
