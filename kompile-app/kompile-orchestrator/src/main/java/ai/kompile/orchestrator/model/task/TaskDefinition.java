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
import java.util.stream.Collectors;

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

    // ==================== Agent/Chat Integration ====================

    /**
     * Name of the agent to use (e.g., "claude-cli", "codex", "gemini").
     * When specified, LLM tasks will use this agent from the AgentRegistryService.
     */
    @Column(name = "agent_name")
    private String agentName;

    /**
     * Whether to enable RAG (Retrieval Augmented Generation) for LLM tasks.
     */
    @Column(name = "enable_rag")
    @Builder.Default
    private boolean enableRag = false;

    /**
     * Folder ID to filter RAG documents by.
     */
    @Column(name = "rag_folder_id")
    private String ragFolderId;

    /**
     * Maximum number of documents to retrieve for RAG context.
     */
    @Column(name = "rag_max_results")
    @Builder.Default
    private Integer ragMaxResults = 5;

    /**
     * Similarity threshold for RAG document retrieval (0.0 - 1.0).
     */
    @Column(name = "rag_similarity_threshold")
    @Builder.Default
    private Double ragSimilarityThreshold = 0.5;

    /**
     * Whether to include keyword search in RAG retrieval.
     */
    @Column(name = "rag_include_keyword_search")
    @Builder.Default
    private boolean ragIncludeKeywordSearch = true;

    /**
     * Whether to include semantic search in RAG retrieval.
     */
    @Column(name = "rag_include_semantic_search")
    @Builder.Default
    private boolean ragIncludeSemanticSearch = true;

    /**
     * Whether to enable tool calling for LLM tasks.
     */
    @Column(name = "enable_tools")
    @Builder.Default
    private boolean enableTools = false;

    /**
     * Comma-separated list of allowed tool names.
     * If empty, all available tools are allowed.
     */
    @Column(name = "allowed_tools")
    private String allowedTools;

    /**
     * Whether to skip permission checks when invoking CLI agents.
     */
    @Column(name = "skip_permissions")
    @Builder.Default
    private boolean skipPermissions = false;

    /**
     * System prompt to prepend to LLM conversations.
     */
    @Column(name = "system_prompt", columnDefinition = "TEXT")
    private String systemPrompt;

    /**
     * ID of the output classifier to use for result parsing.
     */
    @Column(name = "output_classifier_id")
    private Long outputClassifierId;

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

    // ==================== Pass/Fail Trigger Actions ====================

    /**
     * Action to execute when task succeeds (matches success pattern or exits with 0).
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "on_success_action")
    private TaskAction onSuccessAction;

    /**
     * State ID to transition to on success (when onSuccessAction = TRANSITION_STATE).
     */
    @Column(name = "on_success_state_id")
    private String onSuccessStateId;

    /**
     * Task ID to execute on success (when onSuccessAction = EXECUTE_TASK).
     */
    @Column(name = "on_success_task_id")
    private String onSuccessTaskId;

    /**
     * LLM prompt to use on success (when onSuccessAction = INVOKE_LLM or SEND_TO_AGENT).
     * Variables from the task output can be referenced using ${output}, ${exitCode}, etc.
     */
    @Column(name = "on_success_llm_prompt", columnDefinition = "TEXT")
    private String onSuccessLlmPrompt;

    /**
     * Action to execute when task fails (matches failure pattern or exits non-zero).
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "on_failure_action")
    private TaskAction onFailureAction;

    /**
     * State ID to transition to on failure (when onFailureAction = TRANSITION_STATE).
     */
    @Column(name = "on_failure_state_id")
    private String onFailureStateId;

    /**
     * Task ID to execute on failure (when onFailureAction = EXECUTE_TASK).
     */
    @Column(name = "on_failure_task_id")
    private String onFailureTaskId;

    /**
     * LLM prompt to use on failure (when onFailureAction = INVOKE_LLM, INVOKE_LLM_FOR_FIX, or SEND_TO_AGENT).
     * Variables from the task output can be referenced using ${output}, ${exitCode}, ${errorMessage}, etc.
     */
    @Column(name = "on_failure_llm_prompt", columnDefinition = "TEXT")
    private String onFailureLlmPrompt;

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
    String resolveTemplate(String template, Map<String, String> variables) {
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

    /**
     * Get allowed tools as a list.
     */
    public List<String> getAllowedToolsList() {
        if (allowedTools == null || allowedTools.isEmpty()) {
            return List.of();
        }
        return Arrays.stream(allowedTools.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    /**
     * Set allowed tools from a list.
     */
    public void setAllowedToolsList(List<String> tools) {
        if (tools == null || tools.isEmpty()) {
            this.allowedTools = null;
        } else {
            this.allowedTools = String.join(",", tools);
        }
    }

    /**
     * Check if a specific tool is allowed.
     */
    public boolean isToolAllowed(String toolName) {
        if (allowedTools == null || allowedTools.isEmpty()) {
            return true; // All tools allowed if no restrictions
        }
        return getAllowedToolsList().contains(toolName);
    }

    /**
     * Check if this task uses LLM features.
     */
    public boolean usesLlm() {
        return taskType == TaskType.LLM_QUERY ||
               agentName != null ||
               autoInvokeLlmOnError ||
               promptTemplate != null;
    }

    /**
     * Check if this task uses RAG features.
     */
    public boolean usesRag() {
        return enableRag && usesLlm();
    }

    // ==================== Pass/Fail Action Helpers ====================

    /**
     * Determine if task completed successfully based on output and exit code.
     *
     * @param output The task output
     * @param exitCode The task exit code
     * @return true if the task succeeded
     */
    public boolean isTaskSuccess(String output, Integer exitCode) {
        // Check failure pattern first (takes precedence if matched)
        if (matchesFailurePattern(output)) {
            return false;
        }

        // Check success pattern
        if (successPattern != null && !successPattern.isEmpty()) {
            return matchesSuccessPattern(output);
        }

        // Fall back to exit code
        return exitCode != null && exitCode == 0;
    }

    /**
     * Get the action to execute based on task outcome.
     *
     * @param success Whether the task succeeded
     * @return The action to execute, or null if none configured
     */
    public TaskAction getActionForOutcome(boolean success) {
        return success ? onSuccessAction : onFailureAction;
    }

    /**
     * Get the target state ID based on task outcome.
     */
    public String getTargetStateIdForOutcome(boolean success) {
        return success ? onSuccessStateId : onFailureStateId;
    }

    /**
     * Get the target task ID based on task outcome.
     */
    public String getTargetTaskIdForOutcome(boolean success) {
        return success ? onSuccessTaskId : onFailureTaskId;
    }

    /**
     * Get the LLM prompt based on task outcome.
     */
    public String getLlmPromptForOutcome(boolean success) {
        return success ? onSuccessLlmPrompt : onFailureLlmPrompt;
    }

    /**
     * Resolve the LLM prompt for the given outcome with variable substitution.
     *
     * @param success Whether the task succeeded
     * @param output The task output
     * @param exitCode The task exit code
     * @param errorMessage Optional error message
     * @return The resolved prompt
     */
    public String resolveActionLlmPrompt(boolean success, String output, Integer exitCode, String errorMessage) {
        String template = getLlmPromptForOutcome(success);
        if (template == null) {
            return null;
        }

        Map<String, String> variables = new HashMap<>();
        variables.put("output", output != null ? output : "");
        variables.put("exitCode", exitCode != null ? String.valueOf(exitCode) : "");
        variables.put("errorMessage", errorMessage != null ? errorMessage : "");
        variables.put("success", String.valueOf(success));
        variables.put("taskName", name != null ? name : "");
        variables.put("taskId", taskId != null ? taskId : "");

        return resolveTemplate(template, variables);
    }

    /**
     * Check if any action is configured for task outcomes.
     */
    public boolean hasConfiguredActions() {
        return onSuccessAction != null || onFailureAction != null;
    }

    /**
     * Check if this task has LLM-based actions configured.
     */
    public boolean hasLlmActions() {
        return (onSuccessAction != null && onSuccessAction.involvesLlm()) ||
               (onFailureAction != null && onFailureAction.involvesLlm());
    }
}
