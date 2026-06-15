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
package ai.kompile.orchestrator.model.llm;

import jakarta.persistence.*;
import lombok.*;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Defines when and how to automatically invoke an LLM.
 */
@Entity
@Table(name = "llm_triggers")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LlmTrigger {

    private static final Pattern VARIABLE_PATTERN = Pattern.compile("\\$\\{([^}]+)}");

    @Id
    @Column(name = "trigger_id", nullable = false, unique = true)
    private String triggerId;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "trigger_type", nullable = false)
    private LlmTriggerType triggerType;

    @Column(name = "target_state_id")
    private String targetStateId;

    @Column(name = "target_task_id")
    private String targetTaskId;

    @Column(name = "pattern_match")
    private String patternMatch;

    @Column(name = "error_count_threshold")
    @Builder.Default
    private int errorCountThreshold = 3;

    @Column(name = "cron_expression")
    private String cronExpression;

    @Column(name = "prompt_template", columnDefinition = "TEXT", nullable = false)
    private String promptTemplate;

    @Column(name = "system_prompt", columnDefinition = "TEXT")
    private String systemPrompt;

    @Column(name = "llm_provider_id")
    private String llmProviderId;

    @Column(name = "auto_execute_proposal")
    @Builder.Default
    private boolean autoExecuteProposal = false;

    @Column(name = "enabled")
    @Builder.Default
    private boolean enabled = true;

    @Column(name = "priority")
    @Builder.Default
    private int priority = 0;

    @Column(name = "max_tokens")
    private Integer maxTokens;

    @Column(name = "temperature")
    private Double temperature;

    @Column(name = "config_json", columnDefinition = "TEXT")
    private String configJson;

    @Transient
    @Builder.Default
    private Map<String, Object> config = new HashMap<>();

    /**
     * Resolve the prompt template with variable substitution.
     */
    public String resolvePrompt(Map<String, Object> context) {
        if (promptTemplate == null) {
            return null;
        }

        Matcher matcher = VARIABLE_PATTERN.matcher(promptTemplate);
        StringBuilder result = new StringBuilder();

        while (matcher.find()) {
            String varName = matcher.group(1);
            Object value = context.get(varName);
            String replacement = value != null ? value.toString() : "";
            matcher.appendReplacement(result, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(result);

        return result.toString();
    }

    /**
     * Check if the given output matches the pattern.
     */
    public boolean matchesPattern(String output) {
        if (patternMatch == null || output == null) {
            return false;
        }
        return Pattern.compile(patternMatch).matcher(output).find();
    }

    /**
     * Check if this trigger applies to the given state.
     */
    public boolean appliesToState(String stateId) {
        if (triggerType != LlmTriggerType.ON_STATE_ENTER &&
            triggerType != LlmTriggerType.ON_STATE_EXIT) {
            return false;
        }
        return targetStateId == null || targetStateId.equals(stateId);
    }

    /**
     * Check if this trigger applies to the given task.
     */
    public boolean appliesToTask(String taskId) {
        return targetTaskId == null || targetTaskId.equals(taskId);
    }

    /**
     * Create a trigger for task errors.
     */
    public static LlmTrigger onTaskError(String id, String name, String promptTemplate) {
        return LlmTrigger.builder()
                .triggerId(id)
                .name(name)
                .triggerType(LlmTriggerType.ON_TASK_ERROR)
                .promptTemplate(promptTemplate)
                .build();
    }

    /**
     * Create a trigger for pattern matches.
     */
    public static LlmTrigger onPatternMatch(String id, String name, String pattern, String promptTemplate) {
        return LlmTrigger.builder()
                .triggerId(id)
                .name(name)
                .triggerType(LlmTriggerType.ON_PATTERN_MATCH)
                .patternMatch(pattern)
                .promptTemplate(promptTemplate)
                .build();
    }

    /**
     * Create a trigger for state entry.
     */
    public static LlmTrigger onStateEnter(String id, String name, String stateId, String promptTemplate) {
        return LlmTrigger.builder()
                .triggerId(id)
                .name(name)
                .triggerType(LlmTriggerType.ON_STATE_ENTER)
                .targetStateId(stateId)
                .promptTemplate(promptTemplate)
                .build();
    }
}
