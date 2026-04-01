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

import lombok.Getter;

import java.util.Set;

/**
 * Default states provided by the orchestrator framework.
 * These can be extended with custom states.
 */
@Getter
public enum DefaultState {
    /**
     * Orchestrator is idle, waiting to start.
     */
    IDLE("idle", "Idle", "Orchestrator is idle and ready to start",
            StateCategory.INITIAL, Set.of("initializing", "cancelled")),

    /**
     * Orchestrator is initializing.
     */
    INITIALIZING("initializing", "Initializing", "Orchestrator is initializing",
            StateCategory.PROCESSING, Set.of("processing", "failed", "cancelled")),

    /**
     * Orchestrator is actively processing.
     */
    PROCESSING("processing", "Processing", "Orchestrator is processing tasks",
            StateCategory.PROCESSING, Set.of("waiting_input", "waiting_task", "waiting_llm",
                    "analyzing", "success", "failed", "cancelled")),

    /**
     * Waiting for user input.
     */
    WAITING_INPUT("waiting_input", "Waiting for Input", "Waiting for user input or approval",
            StateCategory.WAITING, Set.of("processing", "cancelled")),

    /**
     * Waiting for task completion.
     */
    WAITING_TASK("waiting_task", "Waiting for Task", "Waiting for task to complete",
            StateCategory.WAITING, Set.of("processing", "analyzing", "waiting_llm",
                    "success", "failed", "cancelled")),

    /**
     * Waiting for LLM response.
     */
    WAITING_LLM("waiting_llm", "Waiting for LLM", "Waiting for LLM response",
            StateCategory.WAITING, Set.of("processing", "analyzing", "waiting_input",
                    "success", "failed", "cancelled")),

    /**
     * Analyzing results.
     */
    ANALYZING("analyzing", "Analyzing", "Analyzing results and determining next steps",
            StateCategory.PROCESSING, Set.of("processing", "waiting_input", "waiting_llm",
                    "success", "failed", "cancelled")),

    /**
     * Orchestration completed successfully.
     */
    SUCCESS("success", "Success", "Orchestration completed successfully",
            StateCategory.TERMINAL, Set.of()),

    /**
     * Orchestration failed.
     */
    FAILED("failed", "Failed", "Orchestration failed",
            StateCategory.ERROR, Set.of("processing", "cancelled")),

    /**
     * Orchestration was cancelled.
     */
    CANCELLED("cancelled", "Cancelled", "Orchestration was cancelled",
            StateCategory.TERMINAL, Set.of());

    private final String stateId;
    private final String name;
    private final String description;
    private final StateCategory category;
    private final Set<String> allowedNextStates;

    DefaultState(String stateId, String name, String description,
                 StateCategory category, Set<String> allowedNextStates) {
        this.stateId = stateId;
        this.name = name;
        this.description = description;
        this.category = category;
        this.allowedNextStates = allowedNextStates;
    }

    /**
     * Convert to a StateDefinition object.
     */
    public StateDefinition toStateDefinition() {
        return StateDefinition.builder()
                .stateId(stateId)
                .name(name)
                .description(description)
                .category(category)
                .allowedNextStates(allowedNextStates)
                .builtin(true)
                .displayOrder(ordinal())
                .build();
    }

    /**
     * Find a default state by its ID.
     */
    public static DefaultState fromStateId(String stateId) {
        for (DefaultState state : values()) {
            if (state.stateId.equals(stateId)) {
                return state;
            }
        }
        return null;
    }

    /**
     * Check if a state ID corresponds to a default state.
     */
    public static boolean isDefaultState(String stateId) {
        return fromStateId(stateId) != null;
    }
}
