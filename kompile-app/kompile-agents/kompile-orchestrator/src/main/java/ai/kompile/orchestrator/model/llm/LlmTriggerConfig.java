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

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Configuration for LLM triggers embedded in state definitions.
 */
@Embeddable
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LlmTriggerConfig {

    /**
     * Trigger LLM when entering the state.
     */
    @Column(name = "llm_trigger_on_enter")
    @Builder.Default
    private boolean triggerOnEnter = false;

    /**
     * Trigger LLM when exiting the state.
     */
    @Column(name = "llm_trigger_on_exit")
    @Builder.Default
    private boolean triggerOnExit = false;

    /**
     * Trigger LLM on error in this state.
     */
    @Column(name = "llm_trigger_on_error")
    @Builder.Default
    private boolean triggerOnError = false;

    /**
     * Prompt template to use (supports ${variable} substitution).
     */
    @Column(name = "llm_prompt_template", columnDefinition = "TEXT")
    private String promptTemplate;

    /**
     * LLM provider ID to use (null = default).
     */
    @Column(name = "llm_provider_id")
    private String llmProviderId;

    /**
     * Auto-execute the LLM's proposed action.
     */
    @Column(name = "llm_auto_execute")
    @Builder.Default
    private boolean autoExecuteProposal = false;

    /**
     * Maximum tokens for the response.
     */
    @Column(name = "llm_max_tokens")
    private Integer maxTokens;

    /**
     * Temperature for the LLM response.
     */
    @Column(name = "llm_temperature")
    private Double temperature;

    /**
     * System prompt to prepend.
     */
    @Column(name = "llm_system_prompt", columnDefinition = "TEXT")
    private String systemPrompt;

    /**
     * Check if any trigger is configured.
     */
    public boolean hasAnyTrigger() {
        return triggerOnEnter || triggerOnExit || triggerOnError;
    }

    /**
     * Create a trigger config for on-enter.
     */
    public static LlmTriggerConfig onEnter(String promptTemplate) {
        return LlmTriggerConfig.builder()
                .triggerOnEnter(true)
                .promptTemplate(promptTemplate)
                .build();
    }

    /**
     * Create a trigger config for on-error.
     */
    public static LlmTriggerConfig onError(String promptTemplate) {
        return LlmTriggerConfig.builder()
                .triggerOnError(true)
                .promptTemplate(promptTemplate)
                .build();
    }

    /**
     * Create a trigger config for on-error with auto-execute.
     */
    public static LlmTriggerConfig onErrorAutoFix(String promptTemplate) {
        return LlmTriggerConfig.builder()
                .triggerOnError(true)
                .promptTemplate(promptTemplate)
                .autoExecuteProposal(true)
                .build();
    }
}
