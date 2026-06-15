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
package ai.kompile.orchestrator.service.prompt;

import ai.kompile.orchestrator.model.prompt.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Manages prompt generation with dynamic injection based on state and context.
 * Provides routing advice and error-specific guidance to the LLM.
 */
@Service
@Slf4j
public class PromptManager {

    /**
     * Registry of state-specific prompt configurations.
     */
    private final Map<String, StatePromptConfig> stateConfigs = new ConcurrentHashMap<>();

    /**
     * Global injections that apply to all states.
     */
    private final List<PromptInjection> globalInjections = new ArrayList<>();

    /**
     * Global routing rules that apply to all states.
     */
    private final List<PromptRoutingRule> globalRoutingRules = new ArrayList<>();

    /**
     * Master prompt template (configurable).
     */
    private String masterPromptTemplate;

    /**
     * Default system prompt.
     */
    private String defaultSystemPrompt;

    // Pattern for variable substitution
    private static final Pattern VARIABLE_PATTERN = Pattern.compile("\\{\\{([a-zA-Z_][a-zA-Z0-9_]*)}}");

    // Pattern for conditional blocks
    private static final Pattern CONDITIONAL_PATTERN = Pattern.compile(
            "\\{\\{#if\\s+([a-zA-Z_][a-zA-Z0-9_]*)}}([\\s\\S]*?)\\{\\{/if}}");

    public PromptManager() {
        initializeDefaults();
    }

    /**
     * Initialize default configurations.
     */
    private void initializeDefaults() {
        // Default master prompt template
        masterPromptTemplate = """
                {{systemSection}}

                {{contextSection}}

                {{taskSection}}

                {{outputSection}}

                {{adviceSection}}

                {{constraintsSection}}
                """;

        defaultSystemPrompt = """
                You are an intelligent workflow orchestrator assistant. Your role is to:
                1. Execute tasks accurately and efficiently
                2. Analyze outputs and identify issues
                3. Provide clear recommendations for next steps
                4. Handle errors gracefully with appropriate recovery strategies

                Always respond with structured output when requested.
                """;

        // Register default state configs
        registerStateConfig(StatePromptConfig.executingState());
        registerStateConfig(StatePromptConfig.failedState());
        registerStateConfig(StatePromptConfig.waitingState());
        registerStateConfig(StatePromptConfig.analyzingState());

        // Add global safety injection
        globalInjections.add(PromptInjection.safeOperationsConstraint());

        // Add global routing rules
        globalRoutingRules.add(PromptRoutingRule.retryOnTransientError());
        globalRoutingRules.add(PromptRoutingRule.escalateOnCriticalError());
        globalRoutingRules.add(PromptRoutingRule.failOnMaxRetries(5));
    }

    /**
     * Build a complete prompt for the given state and context.
     *
     * @param request The prompt build request containing all context
     * @return The assembled prompt with all injections applied
     */
    public PromptBuildResult buildPrompt(PromptBuildRequest request) {
        log.debug("Building prompt for state: {}", request.getStateId());

        // Get state-specific config
        StatePromptConfig stateConfig = stateConfigs.get(request.getStateId());

        // Merge variables
        Map<String, Object> variables = new HashMap<>();
        if (stateConfig != null && stateConfig.getDefaultVariables() != null) {
            variables.putAll(stateConfig.getDefaultVariables());
        }
        if (request.getVariables() != null) {
            variables.putAll(request.getVariables());
        }

        // Add standard variables
        variables.put("stateId", request.getStateId());
        variables.put("previousStateId", request.getPreviousStateId());
        variables.put("exitCode", request.getExitCode());
        variables.put("retryCount", request.getRetryCount());
        variables.put("maxRetries", request.getMaxRetries());

        // Build sections
        String systemSection = buildSystemSection(stateConfig, request, variables);
        String contextSection = buildContextSection(stateConfig, request, variables);
        String taskSection = buildTaskSection(stateConfig, request, variables);
        String outputSection = buildOutputSection(stateConfig, request, variables);
        String adviceSection = buildAdviceSection(stateConfig, request, variables);
        String constraintsSection = buildConstraintsSection(stateConfig, request, variables);

        // Add sections to variables
        variables.put("systemSection", systemSection);
        variables.put("contextSection", contextSection);
        variables.put("taskSection", taskSection);
        variables.put("outputSection", outputSection);
        variables.put("adviceSection", adviceSection);
        variables.put("constraintsSection", constraintsSection);

        // Render master prompt
        String basePrompt = stateConfig != null && stateConfig.getMasterPrompt() != null
                ? stateConfig.getMasterPrompt()
                : masterPromptTemplate;

        String renderedPrompt = renderTemplate(basePrompt, variables);

        // Get applicable injections
        List<PromptInjection> applicableInjections = getApplicableInjections(stateConfig, request);

        // Apply injections
        renderedPrompt = applyInjections(renderedPrompt, applicableInjections, variables);

        // Get routing recommendation
        PromptRoutingRule routingRule = getMatchingRoutingRule(stateConfig, request);

        // Build system prompt
        String systemPrompt = buildFinalSystemPrompt(stateConfig, request, variables);

        return PromptBuildResult.builder()
                .prompt(renderedPrompt.trim())
                .systemPrompt(systemPrompt)
                .appliedInjections(applicableInjections.stream()
                        .map(PromptInjection::getId)
                        .collect(Collectors.toList()))
                .routingRule(routingRule)
                .variables(variables)
                .stateConfig(stateConfig)
                .build();
    }

    /**
     * Build the system section of the prompt.
     */
    private String buildSystemSection(StatePromptConfig config, PromptBuildRequest request,
                                       Map<String, Object> variables) {
        StringBuilder sb = new StringBuilder();

        // Add transition-specific content if available
        if (config != null && request.getPreviousStateId() != null) {
            StatePromptConfig.TransitionPromptConfig transitionConfig =
                    config.getTransitionConfig(request.getPreviousStateId());
            if (transitionConfig != null && transitionConfig.getTransitionPrompt() != null) {
                sb.append("## Transition Context\n");
                sb.append(renderTemplate(transitionConfig.getTransitionPrompt(), variables));
                sb.append("\n\n");
            }
        }

        return sb.toString();
    }

    /**
     * Build the context section of the prompt.
     */
    private String buildContextSection(StatePromptConfig config, PromptBuildRequest request,
                                        Map<String, Object> variables) {
        StringBuilder sb = new StringBuilder();

        if (request.getContext() != null && !request.getContext().isEmpty()) {
            sb.append("## Context\n");
            for (Map.Entry<String, Object> entry : request.getContext().entrySet()) {
                if (entry.getValue() != null && !entry.getKey().startsWith("_")) {
                    sb.append("- ").append(entry.getKey()).append(": ")
                            .append(truncate(entry.getValue().toString(), 200))
                            .append("\n");
                }
            }
            sb.append("\n");
        }

        return sb.toString();
    }

    /**
     * Build the task section of the prompt.
     */
    private String buildTaskSection(StatePromptConfig config, PromptBuildRequest request,
                                     Map<String, Object> variables) {
        StringBuilder sb = new StringBuilder();

        if (request.getTaskDescription() != null) {
            sb.append("## Task\n");
            sb.append(renderTemplate(request.getTaskDescription(), variables));
            sb.append("\n\n");
        }

        return sb.toString();
    }

    /**
     * Build the output section of the prompt.
     */
    private String buildOutputSection(StatePromptConfig config, PromptBuildRequest request,
                                       Map<String, Object> variables) {
        StringBuilder sb = new StringBuilder();

        if (request.getOutput() != null && !request.getOutput().isEmpty()) {
            sb.append("## Output\n");
            sb.append("```\n");
            sb.append(truncate(request.getOutput(), 10000));
            sb.append("\n```\n\n");

            // Add exit code if available
            if (request.getExitCode() != null) {
                sb.append("Exit Code: ").append(request.getExitCode()).append("\n\n");
            }
        }

        return sb.toString();
    }

    /**
     * Build the advice section based on error patterns and state.
     */
    private String buildAdviceSection(StatePromptConfig config, PromptBuildRequest request,
                                       Map<String, Object> variables) {
        StringBuilder sb = new StringBuilder();
        List<String> adviceItems = new ArrayList<>();

        // Get error-specific advice from state config
        if (config != null && config.getErrorAdvice() != null && request.getClassification() != null) {
            String advice = config.getErrorAdvice().get(request.getClassification());
            if (advice != null) {
                adviceItems.add(advice);
            }
        }

        // Get advice from matching routing rule
        PromptRoutingRule routingRule = getMatchingRoutingRule(config, request);
        if (routingRule != null && routingRule.getAdvice() != null) {
            adviceItems.add(routingRule.getAdvice());
        }

        if (!adviceItems.isEmpty()) {
            sb.append("## Guidance\n");
            for (String advice : adviceItems) {
                sb.append("- ").append(advice).append("\n");
            }
            sb.append("\n");
        }

        return sb.toString();
    }

    /**
     * Build the constraints section.
     */
    private String buildConstraintsSection(StatePromptConfig config, PromptBuildRequest request,
                                            Map<String, Object> variables) {
        // Constraints are typically added via injections
        return "";
    }

    /**
     * Get all applicable injections for the current context.
     */
    private List<PromptInjection> getApplicableInjections(StatePromptConfig config, PromptBuildRequest request) {
        List<PromptInjection> applicable = new ArrayList<>();

        // Add global injections
        for (PromptInjection injection : globalInjections) {
            if (injection.applies(
                    request.getStateId(),
                    request.getPreviousStateId(),
                    request.getContext(),
                    request.getOutput(),
                    request.getExitCode(),
                    request.getClassification(),
                    request.getRetryCount())) {
                applicable.add(injection);
            }
        }

        // Add state-specific injections
        if (config != null) {
            applicable.addAll(config.getApplicableInjections(
                    request.getPreviousStateId(),
                    request.getContext(),
                    request.getOutput(),
                    request.getExitCode(),
                    request.getClassification(),
                    request.getRetryCount()));
        }

        // Sort by priority (higher first)
        applicable.sort((a, b) -> Integer.compare(b.getPriority(), a.getPriority()));

        return applicable;
    }

    /**
     * Apply injections to the prompt based on their positions.
     */
    private String applyInjections(String prompt, List<PromptInjection> injections,
                                    Map<String, Object> variables) {
        if (injections.isEmpty()) {
            return prompt;
        }

        // Group by position
        Map<PromptInjection.InjectionPosition, List<PromptInjection>> byPosition = injections.stream()
                .collect(Collectors.groupingBy(PromptInjection::getPosition));

        StringBuilder result = new StringBuilder();

        // START injections
        appendInjections(result, byPosition.get(PromptInjection.InjectionPosition.START), variables);

        // Process prompt sections with inline injections
        result.append(prompt);

        // END injections
        appendInjections(result, byPosition.get(PromptInjection.InjectionPosition.END), variables);

        return result.toString();
    }

    /**
     * Append rendered injections to a string builder.
     */
    private void appendInjections(StringBuilder sb, List<PromptInjection> injections,
                                   Map<String, Object> variables) {
        if (injections == null || injections.isEmpty()) {
            return;
        }

        for (PromptInjection injection : injections) {
            String rendered = injection.render(variables);
            if (rendered != null && !rendered.isEmpty()) {
                sb.append(rendered).append("\n");
            }
        }
    }

    /**
     * Get the first matching routing rule.
     */
    private PromptRoutingRule getMatchingRoutingRule(StatePromptConfig config, PromptBuildRequest request) {
        // Check state-specific rules first
        if (config != null) {
            PromptRoutingRule stateRule = config.getMatchingRoutingRule(
                    request.getPreviousStateId(),
                    request.getContext(),
                    request.getOutput(),
                    request.getExitCode(),
                    request.getClassification(),
                    request.getRetryCount());
            if (stateRule != null) {
                return stateRule;
            }
        }

        // Check global rules
        return globalRoutingRules.stream()
                .filter(PromptRoutingRule::isEnabled)
                .filter(r -> r.matches(
                        request.getStateId(),
                        request.getPreviousStateId(),
                        request.getContext(),
                        request.getOutput(),
                        request.getExitCode(),
                        request.getClassification(),
                        request.getRetryCount()))
                .sorted((a, b) -> Integer.compare(b.getPriority(), a.getPriority()))
                .findFirst()
                .orElse(null);
    }

    /**
     * Build the final system prompt.
     */
    private String buildFinalSystemPrompt(StatePromptConfig config, PromptBuildRequest request,
                                           Map<String, Object> variables) {
        String systemPrompt = defaultSystemPrompt;

        // Use state-specific system prompt if available
        if (config != null && config.getSystemPrompt() != null) {
            systemPrompt = config.getSystemPrompt();
        }

        // Check for transition override
        if (config != null && request.getPreviousStateId() != null) {
            StatePromptConfig.TransitionPromptConfig transitionConfig =
                    config.getTransitionConfig(request.getPreviousStateId());
            if (transitionConfig != null && transitionConfig.getSystemPromptOverride() != null) {
                systemPrompt = transitionConfig.getSystemPromptOverride();
            }
        }

        // Use request override if provided
        if (request.getSystemPromptOverride() != null) {
            systemPrompt = request.getSystemPromptOverride();
        }

        return renderTemplate(systemPrompt, variables);
    }

    /**
     * Render a template with variable substitution and conditional blocks.
     */
    private String renderTemplate(String template, Map<String, Object> variables) {
        if (template == null) {
            return "";
        }

        // Process conditional blocks first
        String result = processConditionals(template, variables);

        // Then substitute variables
        Matcher matcher = VARIABLE_PATTERN.matcher(result);
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
     * Process conditional blocks ({{#if var}}...{{/if}}).
     */
    private String processConditionals(String template, Map<String, Object> variables) {
        Matcher matcher = CONDITIONAL_PATTERN.matcher(template);
        StringBuffer sb = new StringBuffer();

        while (matcher.find()) {
            String varName = matcher.group(1);
            String content = matcher.group(2);

            Object value = variables.get(varName);
            boolean include = value != null &&
                    (!(value instanceof Boolean) || (Boolean) value) &&
                    (!(value instanceof String) || !((String) value).isEmpty());

            matcher.appendReplacement(sb, include ? Matcher.quoteReplacement(content) : "");
        }
        matcher.appendTail(sb);

        return sb.toString();
    }

    /**
     * Truncate a string to maximum length.
     */
    private String truncate(String text, int maxLength) {
        if (text == null || text.length() <= maxLength) {
            return text;
        }
        return text.substring(0, maxLength - 20) + "\n... [truncated]";
    }

    // Configuration methods

    /**
     * Register a state-specific prompt configuration.
     */
    public void registerStateConfig(StatePromptConfig config) {
        if (config != null && config.getStateId() != null) {
            stateConfigs.put(config.getStateId(), config);
            log.info("Registered prompt config for state: {}", config.getStateId());
        }
    }

    /**
     * Get state configuration.
     */
    public StatePromptConfig getStateConfig(String stateId) {
        return stateConfigs.get(stateId);
    }

    /**
     * Get all registered state configurations.
     */
    public Map<String, StatePromptConfig> getAllStateConfigs() {
        return Collections.unmodifiableMap(stateConfigs);
    }

    /**
     * Add a global injection.
     */
    public void addGlobalInjection(PromptInjection injection) {
        globalInjections.add(injection);
        log.info("Added global injection: {}", injection.getId());
    }

    /**
     * Remove a global injection.
     */
    public void removeGlobalInjection(String injectionId) {
        globalInjections.removeIf(i -> injectionId.equals(i.getId()));
    }

    /**
     * Add a global routing rule.
     */
    public void addGlobalRoutingRule(PromptRoutingRule rule) {
        globalRoutingRules.add(rule);
        log.info("Added global routing rule: {}", rule.getId());
    }

    /**
     * Remove a global routing rule.
     */
    public void removeGlobalRoutingRule(String ruleId) {
        globalRoutingRules.removeIf(r -> ruleId.equals(r.getId()));
    }

    /**
     * Set the master prompt template.
     */
    public void setMasterPromptTemplate(String template) {
        this.masterPromptTemplate = template;
        log.info("Updated master prompt template");
    }

    /**
     * Get the master prompt template.
     */
    public String getMasterPromptTemplate() {
        return masterPromptTemplate;
    }

    /**
     * Set the default system prompt.
     */
    public void setDefaultSystemPrompt(String systemPrompt) {
        this.defaultSystemPrompt = systemPrompt;
        log.info("Updated default system prompt");
    }

    /**
     * Get the default system prompt.
     */
    public String getDefaultSystemPrompt() {
        return defaultSystemPrompt;
    }

    /**
     * Get global injections.
     */
    public List<PromptInjection> getGlobalInjections() {
        return Collections.unmodifiableList(globalInjections);
    }

    /**
     * Get global routing rules.
     */
    public List<PromptRoutingRule> getGlobalRoutingRules() {
        return Collections.unmodifiableList(globalRoutingRules);
    }
}
