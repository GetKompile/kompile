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

import jakarta.persistence.*;
import lombok.*;

/**
 * Defines a transition between two states in the orchestrator state machine.
 * Provides detailed configuration for transition conditions and actions.
 */
@Entity
@Table(name = "state_transitions_config", indexes = {
        @Index(name = "idx_transition_instance", columnList = "orchestrator_instance_id"),
        @Index(name = "idx_transition_from", columnList = "from_state_id"),
        @Index(name = "idx_transition_to", columnList = "to_state_id")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StateTransition {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    /**
     * Orchestrator instance this transition belongs to.
     */
    @Column(name = "orchestrator_instance_id", nullable = false)
    private String orchestratorInstanceId;

    /**
     * The state this transition originates from.
     */
    @Column(name = "from_state_id", nullable = false)
    private String fromStateId;

    /**
     * The state this transition leads to.
     */
    @Column(name = "to_state_id", nullable = false)
    private String toStateId;

    /**
     * Optional name for this transition.
     */
    @Column(name = "name")
    private String name;

    /**
     * Description of when this transition occurs.
     */
    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    /**
     * Condition type for this transition.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "condition_type")
    @Builder.Default
    private TransitionConditionType conditionType = TransitionConditionType.ALWAYS;

    /**
     * Condition expression (regex pattern, SpEL expression, etc. based on conditionType).
     */
    @Column(name = "condition_expression", columnDefinition = "TEXT")
    private String conditionExpression;

    /**
     * Whether this transition is automatic or requires manual trigger.
     */
    @Column(name = "auto_trigger")
    @Builder.Default
    private boolean autoTrigger = true;

    /**
     * Priority for transition evaluation (higher = evaluated first).
     */
    @Column(name = "priority")
    @Builder.Default
    private int priority = 0;

    /**
     * Task ID to execute when this transition occurs.
     */
    @Column(name = "on_transition_task_id")
    private String onTransitionTaskId;

    /**
     * Whether this transition is enabled.
     */
    @Column(name = "enabled")
    @Builder.Default
    private boolean enabled = true;

    /**
     * Optional label to display on the visual editor.
     */
    @Column(name = "label")
    private String label;

    /**
     * Transition condition types.
     */
    public enum TransitionConditionType {
        /**
         * Transition always occurs.
         */
        ALWAYS,

        /**
         * Transition on success outcome.
         */
        ON_SUCCESS,

        /**
         * Transition on failure outcome.
         */
        ON_FAILURE,

        /**
         * Transition when output matches a regex pattern.
         */
        PATTERN_MATCH,

        /**
         * Transition based on classification result.
         */
        CLASSIFICATION,

        /**
         * Transition based on SpEL expression evaluation.
         */
        EXPRESSION,

        /**
         * Manual trigger only.
         */
        MANUAL
    }

    /**
     * Check if this transition matches a given outcome.
     */
    public boolean matchesOutcome(boolean success, String output) {
        switch (conditionType) {
            case ALWAYS:
                return true;
            case ON_SUCCESS:
                return success;
            case ON_FAILURE:
                return !success;
            case PATTERN_MATCH:
                if (conditionExpression != null && output != null) {
                    return output.matches(conditionExpression);
                }
                return false;
            case MANUAL:
                return false;
            default:
                return false;
        }
    }

    /**
     * Get a display label for this transition.
     */
    public String getDisplayLabel() {
        if (label != null && !label.isEmpty()) {
            return label;
        }
        if (name != null && !name.isEmpty()) {
            return name;
        }
        return conditionType.name().toLowerCase().replace("_", " ");
    }
}
