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
package ai.kompile.orchestrator.model.output;

import jakarta.persistence.*;
import lombok.*;

import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Individual classification rule for pattern matching in task output.
 */
@Entity
@Table(name = "classification_rules", indexes = {
        @Index(name = "idx_rule_classifier", columnList = "classifier_id"),
        @Index(name = "idx_rule_type", columnList = "classification_type"),
        @Index(name = "idx_rule_order", columnList = "rule_order")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ClassificationRule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    /**
     * Regex pattern to match against output.
     */
    @Column(name = "pattern", nullable = false, columnDefinition = "TEXT")
    private String pattern;

    /**
     * Whether the pattern is case-sensitive.
     */
    @Column(name = "case_sensitive")
    @Builder.Default
    private boolean caseSensitive = false;

    /**
     * Whether to use multiline matching.
     */
    @Column(name = "multiline")
    @Builder.Default
    private boolean multiline = true;

    /**
     * Type of error/output this rule matches.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "classification_type", nullable = false)
    private ClassificationType classificationType;

    /**
     * Severity level when this rule matches.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "severity", nullable = false)
    @Builder.Default
    private ClassificationSeverity severity = ClassificationSeverity.ERROR;

    /**
     * Action to take when this rule matches.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "action", nullable = false)
    @Builder.Default
    private ClassificationAction action = ClassificationAction.LOG_CONTINUE;

    /**
     * JSON configuration for the action (e.g., webhook URL, handler task ID).
     */
    @Column(name = "action_config", columnDefinition = "TEXT")
    private String actionConfig;

    /**
     * Target state to transition to (for TRANSITION_STATE action).
     */
    @Column(name = "target_state_id")
    private String targetStateId;

    /**
     * Handler task ID to execute (for EXECUTE_HANDLER action).
     */
    @Column(name = "handler_task_id")
    private String handlerTaskId;

    /**
     * LLM prompt template for INVOKE_LLM action.
     */
    @Column(name = "llm_prompt_template", columnDefinition = "TEXT")
    private String llmPromptTemplate;

    /**
     * Maximum retry count for RETRY action.
     */
    @Column(name = "max_retries")
    @Builder.Default
    private int maxRetries = 3;

    /**
     * Retry delay in seconds for RETRY action.
     */
    @Column(name = "retry_delay_seconds")
    @Builder.Default
    private long retryDelaySeconds = 5L;

    /**
     * Order in which this rule is evaluated (lower = first).
     */
    @Column(name = "rule_order")
    @Builder.Default
    private int ruleOrder = 100;

    /**
     * Whether this rule is enabled.
     */
    @Column(name = "enabled")
    @Builder.Default
    private boolean enabled = true;

    /**
     * Whether to stop evaluating rules after this one matches.
     */
    @Column(name = "stop_on_match")
    @Builder.Default
    private boolean stopOnMatch = false;

    /**
     * Tags for categorization.
     */
    @Column(name = "tags")
    private String tags;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "classifier_id")
    private OutputClassifier classifier;

    @Transient
    private Pattern compiledPattern;

    /**
     * Compile and cache the regex pattern.
     */
    public Pattern getCompiledPattern() {
        if (compiledPattern == null && pattern != null) {
            int flags = 0;
            if (!caseSensitive) {
                flags |= Pattern.CASE_INSENSITIVE;
            }
            if (multiline) {
                flags |= Pattern.MULTILINE;
            }
            compiledPattern = Pattern.compile(pattern, flags);
        }
        return compiledPattern;
    }

    /**
     * Check if the output matches this rule.
     */
    public boolean matches(String output) {
        if (output == null || pattern == null || !enabled) {
            return false;
        }
        try {
            return getCompiledPattern().matcher(output).find();
        } catch (PatternSyntaxException e) {
            return false;
        }
    }

    /**
     * Validate that the pattern is a valid regex.
     */
    public boolean isValidPattern() {
        if (pattern == null) {
            return false;
        }
        try {
            Pattern.compile(pattern);
            return true;
        } catch (PatternSyntaxException e) {
            return false;
        }
    }

    /**
     * Create a rule for compilation errors.
     */
    public static ClassificationRule compilationError(String name, String pattern) {
        return ClassificationRule.builder()
                .name(name)
                .pattern(pattern)
                .classificationType(ClassificationType.COMPILATION_ERROR)
                .severity(ClassificationSeverity.ERROR)
                .action(ClassificationAction.INVOKE_LLM)
                .build();
    }

    /**
     * Create a rule for runtime errors.
     */
    public static ClassificationRule runtimeError(String name, String pattern) {
        return ClassificationRule.builder()
                .name(name)
                .pattern(pattern)
                .classificationType(ClassificationType.RUNTIME_ERROR)
                .severity(ClassificationSeverity.ERROR)
                .action(ClassificationAction.INVOKE_LLM)
                .build();
    }

    /**
     * Create a rule for success patterns.
     */
    public static ClassificationRule success(String name, String pattern) {
        return ClassificationRule.builder()
                .name(name)
                .pattern(pattern)
                .classificationType(ClassificationType.SUCCESS)
                .severity(ClassificationSeverity.INFO)
                .action(ClassificationAction.NONE)
                .build();
    }
}
