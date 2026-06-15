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
package ai.kompile.orchestrator.model.state;

import ai.kompile.orchestrator.model.llm.LlmTriggerConfig;
import jakarta.persistence.*;
import lombok.*;

import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Defines a state in the orchestrator state machine.
 * States can be registered at startup or dynamically at runtime.
 */
@Entity
@Table(name = "state_definitions", indexes = {
        @Index(name = "idx_state_instance", columnList = "orchestrator_instance_id")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StateDefinition {

    @Id
    @Column(name = "state_id", nullable = false, unique = true)
    private String stateId;

    /**
     * Orchestrator instance this state belongs to.
     * Null for global/builtin states.
     */
    @Column(name = "orchestrator_instance_id")
    private String orchestratorInstanceId;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "category", nullable = false)
    @Builder.Default
    private StateCategory category = StateCategory.PROCESSING;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "state_transitions", joinColumns = @JoinColumn(name = "state_id"))
    @Column(name = "next_state_id")
    @Builder.Default
    private Set<String> allowedNextStates = new HashSet<>();

    @Column(name = "handler_class_name")
    private String handlerClassName;

    @Column(name = "timeout_seconds")
    private Long timeoutSeconds;

    @Column(name = "auto_advance")
    @Builder.Default
    private boolean autoAdvance = false;

    @Column(name = "is_polling")
    @Builder.Default
    private boolean polling = false;

    @Column(name = "polling_interval_ms")
    @Builder.Default
    private Long pollingIntervalMs = 2000L;

    @Embedded
    private LlmTriggerConfig llmTriggerConfig;

    @Column(name = "metadata_json", columnDefinition = "TEXT")
    private String metadataJson;

    @Transient
    @Builder.Default
    private Map<String, Object> metadata = new HashMap<>();

    @Column(name = "is_builtin")
    @Builder.Default
    private boolean builtin = false;

    @Column(name = "display_order")
    @Builder.Default
    private int displayOrder = 0;

    /**
     * X position for visual editor.
     */
    @Column(name = "position_x")
    private Double positionX;

    /**
     * Y position for visual editor.
     */
    @Column(name = "position_y")
    private Double positionY;

    /**
     * Task ID to execute when entering this state.
     */
    @Column(name = "on_enter_task_id")
    private String onEnterTaskId;

    /**
     * Task ID to execute when exiting this state.
     */
    @Column(name = "on_exit_task_id")
    private String onExitTaskId;

    /**
     * Get the timeout as a Duration.
     */
    public Duration getTimeout() {
        return timeoutSeconds != null ? Duration.ofSeconds(timeoutSeconds) : null;
    }

    /**
     * Set the timeout from a Duration.
     */
    public void setTimeout(Duration timeout) {
        this.timeoutSeconds = timeout != null ? timeout.toSeconds() : null;
    }

    /**
     * Get the polling interval as a Duration.
     */
    public Duration getPollingInterval() {
        return Duration.ofMillis(pollingIntervalMs != null ? pollingIntervalMs : 2000L);
    }

    /**
     * Set the polling interval from a Duration.
     */
    public void setPollingInterval(Duration interval) {
        this.pollingIntervalMs = interval != null ? interval.toMillis() : 2000L;
    }

    /**
     * Check if transition to the given state is allowed.
     */
    public boolean canTransitionTo(String targetStateId) {
        return allowedNextStates != null && allowedNextStates.contains(targetStateId);
    }

    /**
     * Check if this is a terminal state.
     */
    public boolean isTerminal() {
        return category == StateCategory.TERMINAL;
    }

    /**
     * Check if this is an error state.
     */
    public boolean isError() {
        return category == StateCategory.ERROR;
    }

    /**
     * Check if this is an initial state.
     */
    public boolean isInitial() {
        return category == StateCategory.INITIAL;
    }

    /**
     * Check if LLM should be triggered when entering this state.
     */
    public boolean hasLlmTriggerOnEnter() {
        return llmTriggerConfig != null && llmTriggerConfig.isTriggerOnEnter();
    }

    /**
     * Check if LLM should be triggered when exiting this state.
     */
    public boolean hasLlmTriggerOnExit() {
        return llmTriggerConfig != null && llmTriggerConfig.isTriggerOnExit();
    }

    /**
     * Check if LLM should be triggered on error in this state.
     */
    public boolean hasLlmTriggerOnError() {
        return llmTriggerConfig != null && llmTriggerConfig.isTriggerOnError();
    }

    /**
     * Create a copy of this state definition with a new ID.
     */
    public StateDefinition copyWithId(String newStateId) {
        return StateDefinition.builder()
                .stateId(newStateId)
                .orchestratorInstanceId(orchestratorInstanceId)
                .name(name)
                .description(description)
                .category(category)
                .allowedNextStates(new HashSet<>(allowedNextStates))
                .handlerClassName(handlerClassName)
                .timeoutSeconds(timeoutSeconds)
                .autoAdvance(autoAdvance)
                .polling(polling)
                .pollingIntervalMs(pollingIntervalMs)
                .llmTriggerConfig(llmTriggerConfig)
                .metadata(new HashMap<>(metadata))
                .builtin(false)
                .displayOrder(displayOrder)
                .positionX(positionX)
                .positionY(positionY)
                .onEnterTaskId(onEnterTaskId)
                .onExitTaskId(onExitTaskId)
                .build();
    }

    /**
     * Get position as a map for JSON serialization.
     */
    public Map<String, Double> getPosition() {
        if (positionX == null && positionY == null) {
            return null;
        }
        Map<String, Double> pos = new HashMap<>();
        pos.put("x", positionX != null ? positionX : 0.0);
        pos.put("y", positionY != null ? positionY : 0.0);
        return pos;
    }

    /**
     * Set position from a map.
     */
    public void setPosition(Map<String, Double> position) {
        if (position != null) {
            this.positionX = position.get("x");
            this.positionY = position.get("y");
        }
    }
}
