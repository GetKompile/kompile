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
package ai.kompile.orchestrator.model.task;

import jakarta.persistence.*;
import lombok.*;

import java.time.Duration;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Defines a reusable task template that can be executed multiple times
 * with different variable substitutions.
 */
@Entity
@Table(name = "task_definitions")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TaskDefinition {

    private static final Pattern VARIABLE_PATTERN = Pattern.compile("\\$\\{([^}]+)}");

    @Id
    @Column(name = "task_id", nullable = false, unique = true)
    private String taskId;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "task_type", nullable = false)
    @Builder.Default
    private TaskType taskType = TaskType.SHELL;

    @Column(name = "command", columnDefinition = "TEXT")
    private String command;

    @Column(name = "working_directory")
    private String workingDirectory;

    @Column(name = "timeout_seconds")
    @Builder.Default
    private Long timeoutSeconds = 300L; // 5 minutes default

    @Column(name = "prompt_template", columnDefinition = "TEXT")
    private String promptTemplate;

    @Column(name = "auto_invoke_llm_on_error")
    @Builder.Default
    private boolean autoInvokeLlmOnError = false;

    @Column(name = "llm_error_prompt_template", columnDefinition = "TEXT")
    private String llmErrorPromptTemplate;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "task_default_variables", joinColumns = @JoinColumn(name = "task_id"))
    @MapKeyColumn(name = "variable_name")
    @Column(name = "variable_value")
    @Builder.Default
    private Map<String, String> defaultVariables = new HashMap<>();

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "task_required_variables", joinColumns = @JoinColumn(name = "task_id"))
    @Column(name = "variable_name")
    @Builder.Default
    private Set<String> requiredVariables = new HashSet<>();

    @Column(name = "success_pattern")
    private String successPattern;

    @Column(name = "failure_pattern")
    private String failurePattern;

    @Column(name = "http_url")
    private String httpUrl;

    @Column(name = "http_method")
    private String httpMethod;

    @Column(name = "http_headers_json", columnDefinition = "TEXT")
    private String httpHeadersJson;

    @Column(name = "http_body_template", columnDefinition = "TEXT")
    private String httpBodyTemplate;

    @Column(name = "executor_class_name")
    private String executorClassName;

    @Column(name = "retry_count")
    @Builder.Default
    private int retryCount = 0;

    @Column(name = "retry_delay_seconds")
    @Builder.Default
    private long retryDelaySeconds = 5L;

    @Column(name = "metadata_json", columnDefinition = "TEXT")
    private String metadataJson;

    @Transient
    @Builder.Default
    private Map<String, Object> metadata = new HashMap<>();

    /**
     * Get the timeout as a Duration.
     */
    public Duration getTimeout() {
        return Duration.ofSeconds(timeoutSeconds != null ? timeoutSeconds : 300L);
    }

    /**
     * Set the timeout from a Duration.
     */
    public void setTimeout(Duration timeout) {
        this.timeoutSeconds = timeout != null ? timeout.toSeconds() : 300L;
    }

    /**
     * Get the retry delay as a Duration.
     */
    public Duration getRetryDelay() {
        return Duration.ofSeconds(retryDelaySeconds);
    }

    /**
     * Resolve the command with variable substitution.
     */
    public String resolveCommand(Map<String, String> variables) {
        return resolveTemplate(command, variables);
    }

    /**
     * Resolve the prompt template with variable substitution.
     */
    public String resolvePrompt(Map<String, String> variables) {
        return resolveTemplate(promptTemplate, variables);
    }

    /**
     * Resolve the LLM error prompt template with variable substitution.
     */
    public String resolveLlmErrorPrompt(Map<String, String> variables) {
        return resolveTemplate(llmErrorPromptTemplate, variables);
    }

    /**
     * Resolve a template string with variable substitution.
     */
    private String resolveTemplate(String template, Map<String, String> variables) {
        if (template == null) {
            return null;
        }

        Map<String, String> allVariables = new HashMap<>(defaultVariables);
        if (variables != null) {
            allVariables.putAll(variables);
        }

        Matcher matcher = VARIABLE_PATTERN.matcher(template);
        StringBuilder result = new StringBuilder();

        while (matcher.find()) {
            String varName = matcher.group(1);
            String replacement = allVariables.getOrDefault(varName, "");
            matcher.appendReplacement(result, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(result);

        return result.toString();
    }

    /**
     * Extract all variable names from the command template.
     */
    public Set<String> extractVariables() {
        Set<String> variables = new HashSet<>();
        extractVariablesFromTemplate(command, variables);
        extractVariablesFromTemplate(promptTemplate, variables);
        extractVariablesFromTemplate(httpBodyTemplate, variables);
        return variables;
    }

    private void extractVariablesFromTemplate(String template, Set<String> variables) {
        if (template == null) {
            return;
        }
        Matcher matcher = VARIABLE_PATTERN.matcher(template);
        while (matcher.find()) {
            variables.add(matcher.group(1));
        }
    }

    /**
     * Validate that all required variables are provided.
     */
    public List<String> validateVariables(Map<String, String> variables) {
        List<String> missing = new ArrayList<>();
        for (String required : requiredVariables) {
            if (variables == null || !variables.containsKey(required)) {
                if (!defaultVariables.containsKey(required)) {
                    missing.add(required);
                }
            }
        }
        return missing;
    }

    /**
     * Check if task output matches the success pattern.
     */
    public boolean matchesSuccessPattern(String output) {
        if (successPattern == null || output == null) {
            return false;
        }
        return Pattern.compile(successPattern).matcher(output).find();
    }

    /**
     * Check if task output matches the failure pattern.
     */
    public boolean matchesFailurePattern(String output) {
        if (failurePattern == null || output == null) {
            return false;
        }
        return Pattern.compile(failurePattern).matcher(output).find();
    }
}
