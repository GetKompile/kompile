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
package ai.kompile.orchestrator.model.workflow;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Transient;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.HashMap;
import java.util.Map;

/**
 * Represents an action proposed by an LLM during workflow execution.
 */
@Embeddable
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ActionProposal {

    /**
     * Type of action proposed.
     */
    @Enumerated(EnumType.STRING)
    private ActionType actionType;

    /**
     * Command to execute (for EXECUTE_COMMAND).
     */
    private String command;

    /**
     * Task definition ID (for RUN_TASK).
     */
    private String taskDefinitionId;

    /**
     * Follow-up prompt (for INVOKE_LLM).
     */
    private String llmPrompt;

    /**
     * LLM's reasoning for this proposal.
     */
    private String reasoning;

    /**
     * Expected outcome of the action.
     */
    private String expectedOutcome;

    /**
     * Whether this is the final step in the workflow.
     */
    @Builder.Default
    private boolean finalStep = false;

    /**
     * LLM's confidence in this proposal (0-1).
     */
    @Builder.Default
    private double confidence = 0.8;

    /**
     * JSON-serialized variables for persistence.
     */
    @Column(name = "variables_json", columnDefinition = "TEXT")
    private String variablesJson;

    /**
     * Variables for the action (transient, populated from JSON).
     */
    @Transient
    @Builder.Default
    private Map<String, String> variables = new HashMap<>();

    /**
     * Analysis of the current step's results.
     */
    private String analysis;

    /**
     * Custom action configuration (JSON).
     */
    private String customConfigJson;

    /**
     * Check if this proposal requires user approval.
     */
    public boolean requiresApproval() {
        return actionType == ActionType.AWAIT_APPROVAL || confidence < 0.5;
    }

    /**
     * Check if this proposal marks workflow completion.
     */
    public boolean isTerminal() {
        return actionType == ActionType.COMPLETE || actionType == ActionType.FAIL;
    }

    /**
     * Create a proposal to execute a command.
     */
    public static ActionProposal executeCommand(String command, String reasoning) {
        return ActionProposal.builder()
                .actionType(ActionType.EXECUTE_COMMAND)
                .command(command)
                .reasoning(reasoning)
                .build();
    }

    /**
     * Create a proposal to run a task.
     */
    public static ActionProposal runTask(String taskDefinitionId, String reasoning) {
        return ActionProposal.builder()
                .actionType(ActionType.RUN_TASK)
                .taskDefinitionId(taskDefinitionId)
                .reasoning(reasoning)
                .build();
    }

    /**
     * Create a proposal to invoke LLM.
     */
    public static ActionProposal invokeLlm(String prompt, String reasoning) {
        return ActionProposal.builder()
                .actionType(ActionType.INVOKE_LLM)
                .llmPrompt(prompt)
                .reasoning(reasoning)
                .build();
    }

    /**
     * Create a proposal to complete the workflow.
     */
    public static ActionProposal complete(String reasoning) {
        return ActionProposal.builder()
                .actionType(ActionType.COMPLETE)
                .reasoning(reasoning)
                .finalStep(true)
                .build();
    }

    /**
     * Create a proposal to fail the workflow.
     */
    public static ActionProposal fail(String reasoning) {
        return ActionProposal.builder()
                .actionType(ActionType.FAIL)
                .reasoning(reasoning)
                .finalStep(true)
                .build();
    }

    /**
     * Create a proposal to wait for user approval.
     */
    public static ActionProposal awaitApproval(String reasoning) {
        return ActionProposal.builder()
                .actionType(ActionType.AWAIT_APPROVAL)
                .reasoning(reasoning)
                .build();
    }
}
