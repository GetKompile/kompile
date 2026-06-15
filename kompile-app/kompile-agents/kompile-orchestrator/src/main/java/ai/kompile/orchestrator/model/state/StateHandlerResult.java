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

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.HashMap;
import java.util.Map;

/**
 * Result returned by a state handler after processing.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StateHandlerResult {

    /**
     * Whether the handler has completed processing for this state.
     * If false and the state is a polling state, the handler will be called again.
     */
    @Builder.Default
    private boolean complete = true;

    /**
     * The suggested next state to transition to.
     * If null, stay in current state (for polling) or use auto-advance logic.
     */
    private String nextStateId;

    /**
     * Updates to apply to the orchestrator context.
     */
    @Builder.Default
    private Map<String, Object> contextUpdates = new HashMap<>();

    /**
     * Status message describing the result.
     */
    private String message;

    /**
     * Whether an error occurred during processing.
     */
    @Builder.Default
    private boolean error = false;

    /**
     * Error message if an error occurred.
     */
    private String errorMessage;

    /**
     * Exception that caused the error (if any).
     */
    private Throwable exception;

    /**
     * Whether to trigger LLM invocation after this handler completes.
     */
    @Builder.Default
    private boolean triggerLlm = false;

    /**
     * Custom prompt for LLM invocation (if triggerLlm is true).
     */
    private String llmPrompt;

    /**
     * ID of a task that was started by this handler (for tracking).
     */
    private Long startedTaskId;

    /**
     * ID of a workflow that was started by this handler (for tracking).
     */
    private Long startedWorkflowId;

    // Factory methods for common results

    /**
     * Create a success result that stays in the current state.
     */
    public static StateHandlerResult stay() {
        return StateHandlerResult.builder()
                .complete(false)
                .build();
    }

    /**
     * Create a success result that stays in the current state with a message.
     */
    public static StateHandlerResult stay(String message) {
        return StateHandlerResult.builder()
                .complete(false)
                .message(message)
                .build();
    }

    /**
     * Create a success result that transitions to the next state.
     */
    public static StateHandlerResult transitionTo(String nextStateId) {
        return StateHandlerResult.builder()
                .complete(true)
                .nextStateId(nextStateId)
                .build();
    }

    /**
     * Create a success result that transitions to the next state with a message.
     */
    public static StateHandlerResult transitionTo(String nextStateId, String message) {
        return StateHandlerResult.builder()
                .complete(true)
                .nextStateId(nextStateId)
                .message(message)
                .build();
    }

    /**
     * Create a success result with context updates.
     */
    public static StateHandlerResult transitionTo(String nextStateId, Map<String, Object> contextUpdates) {
        return StateHandlerResult.builder()
                .complete(true)
                .nextStateId(nextStateId)
                .contextUpdates(contextUpdates)
                .build();
    }

    /**
     * Create an error result.
     */
    public static StateHandlerResult error(String errorMessage) {
        return StateHandlerResult.builder()
                .complete(true)
                .error(true)
                .errorMessage(errorMessage)
                .nextStateId(DefaultState.FAILED.getStateId())
                .build();
    }

    /**
     * Create an error result with an exception.
     */
    public static StateHandlerResult error(String errorMessage, Throwable exception) {
        return StateHandlerResult.builder()
                .complete(true)
                .error(true)
                .errorMessage(errorMessage)
                .exception(exception)
                .nextStateId(DefaultState.FAILED.getStateId())
                .build();
    }

    /**
     * Create a result that triggers LLM invocation.
     */
    public static StateHandlerResult triggerLlm(String prompt) {
        return StateHandlerResult.builder()
                .complete(true)
                .triggerLlm(true)
                .llmPrompt(prompt)
                .nextStateId(DefaultState.WAITING_LLM.getStateId())
                .build();
    }

    /**
     * Create a result indicating completion/success.
     */
    public static StateHandlerResult complete() {
        return StateHandlerResult.builder()
                .complete(true)
                .nextStateId(DefaultState.SUCCESS.getStateId())
                .build();
    }

    /**
     * Create a result indicating completion with a message.
     */
    public static StateHandlerResult complete(String message) {
        return StateHandlerResult.builder()
                .complete(true)
                .message(message)
                .nextStateId(DefaultState.SUCCESS.getStateId())
                .build();
    }
}
