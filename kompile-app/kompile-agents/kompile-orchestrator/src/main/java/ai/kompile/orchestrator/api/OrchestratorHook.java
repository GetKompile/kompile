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
package ai.kompile.orchestrator.api;

import ai.kompile.orchestrator.model.OrchestratorInstance;
import ai.kompile.orchestrator.model.llm.LlmSession;
import ai.kompile.orchestrator.model.state.StateDefinition;
import ai.kompile.orchestrator.model.task.TaskInstance;
import ai.kompile.orchestrator.model.workflow.Workflow;
import ai.kompile.orchestrator.model.workflow.WorkflowStep;

import java.util.Map;

/**
 * Interface for custom hooks in the orchestrator lifecycle.
 * Implementations can be registered to receive callbacks at various points.
 */
public interface OrchestratorHook {

    /**
     * Get the unique identifier for this hook.
     *
     * @return The hook ID
     */
    String getId();

    /**
     * Get the priority of this hook (higher = runs first).
     *
     * @return The priority
     */
    default int getPriority() {
        return 0;
    }

    // ==================== Orchestrator Lifecycle ====================

    /**
     * Called before an orchestrator instance starts.
     *
     * @param instance The orchestrator instance
     */
    default void preStart(OrchestratorInstance instance) {}

    /**
     * Called after an orchestrator instance has started.
     *
     * @param instance The orchestrator instance
     */
    default void postStart(OrchestratorInstance instance) {}

    /**
     * Called before an orchestrator instance stops.
     *
     * @param instance The orchestrator instance
     */
    default void preStop(OrchestratorInstance instance) {}

    /**
     * Called after an orchestrator instance has stopped.
     *
     * @param instance The orchestrator instance
     */
    default void postStop(OrchestratorInstance instance) {}

    /**
     * Called before an orchestrator is recovered.
     *
     * @param instance The orchestrator instance
     */
    default void preRecovery(OrchestratorInstance instance) {}

    /**
     * Called after an orchestrator has recovered.
     *
     * @param instance The orchestrator instance
     */
    default void postRecovery(OrchestratorInstance instance) {}

    // ==================== State Transitions ====================

    /**
     * Called before entering a state.
     *
     * @param instance The orchestrator instance
     * @param state    The state being entered
     * @param context  The current context
     * @return true to continue, false to abort transition
     */
    default boolean preStateEnter(OrchestratorInstance instance, StateDefinition state, Map<String, Object> context) {
        return true;
    }

    /**
     * Called after entering a state.
     *
     * @param instance The orchestrator instance
     * @param state    The state that was entered
     * @param context  The current context
     */
    default void postStateEnter(OrchestratorInstance instance, StateDefinition state, Map<String, Object> context) {}

    /**
     * Called before exiting a state.
     *
     * @param instance The orchestrator instance
     * @param state    The state being exited
     * @param context  The current context
     * @return true to continue, false to abort transition
     */
    default boolean preStateExit(OrchestratorInstance instance, StateDefinition state, Map<String, Object> context) {
        return true;
    }

    /**
     * Called after exiting a state.
     *
     * @param instance The orchestrator instance
     * @param state    The state that was exited
     * @param context  The current context
     */
    default void postStateExit(OrchestratorInstance instance, StateDefinition state, Map<String, Object> context) {}

    /**
     * Called before a state transition.
     *
     * @param instance    The orchestrator instance
     * @param fromState   The current state
     * @param toState     The target state
     * @param context     The current context
     * @return true to continue, false to abort transition
     */
    default boolean preStateTransition(OrchestratorInstance instance, StateDefinition fromState,
                                       StateDefinition toState, Map<String, Object> context) {
        return true;
    }

    /**
     * Called after a state transition.
     *
     * @param instance    The orchestrator instance
     * @param fromState   The previous state
     * @param toState     The new state
     * @param context     The current context
     */
    default void postStateTransition(OrchestratorInstance instance, StateDefinition fromState,
                                     StateDefinition toState, Map<String, Object> context) {}

    // ==================== Task Execution ====================

    /**
     * Called before a task starts.
     *
     * @param instance The orchestrator instance
     * @param task     The task instance
     * @return true to continue, false to abort task
     */
    default boolean preTaskStart(OrchestratorInstance instance, TaskInstance task) {
        return true;
    }

    /**
     * Called after a task completes (success or failure).
     *
     * @param instance The orchestrator instance
     * @param task     The task instance
     */
    default void postTaskComplete(OrchestratorInstance instance, TaskInstance task) {}

    /**
     * Called when a task fails.
     *
     * @param instance The orchestrator instance
     * @param task     The task instance
     */
    default void onTaskError(OrchestratorInstance instance, TaskInstance task) {}

    // ==================== LLM Sessions ====================

    /**
     * Called before an LLM session starts.
     *
     * @param instance The orchestrator instance
     * @param prompt   The initial prompt
     * @return The potentially modified prompt
     */
    default String preLlmInvoke(OrchestratorInstance instance, String prompt) {
        return prompt;
    }

    /**
     * Called after an LLM session completes.
     *
     * @param instance The orchestrator instance
     * @param session  The LLM session
     */
    default void postLlmComplete(OrchestratorInstance instance, LlmSession session) {}

    /**
     * Called when an LLM session fails.
     *
     * @param instance The orchestrator instance
     * @param session  The LLM session
     */
    default void onLlmError(OrchestratorInstance instance, LlmSession session) {}

    // ==================== Workflow Management ====================

    /**
     * Called before a workflow starts.
     *
     * @param instance The orchestrator instance
     * @param workflow The workflow
     * @return true to continue, false to abort
     */
    default boolean preWorkflowStart(OrchestratorInstance instance, Workflow workflow) {
        return true;
    }

    /**
     * Called after a workflow completes.
     *
     * @param instance The orchestrator instance
     * @param workflow The workflow
     */
    default void postWorkflowComplete(OrchestratorInstance instance, Workflow workflow) {}

    /**
     * Called before a workflow step executes.
     *
     * @param instance The orchestrator instance
     * @param step     The workflow step
     * @return true to continue, false to skip step
     */
    default boolean preWorkflowStep(OrchestratorInstance instance, WorkflowStep step) {
        return true;
    }

    /**
     * Called after a workflow step completes.
     *
     * @param instance The orchestrator instance
     * @param step     The workflow step
     */
    default void postWorkflowStep(OrchestratorInstance instance, WorkflowStep step) {}

    // ==================== Error Handling ====================

    /**
     * Called when an error occurs.
     *
     * @param instance The orchestrator instance
     * @param error    The error
     * @param context  The current context
     * @return true if error was handled, false to continue default handling
     */
    default boolean onError(OrchestratorInstance instance, Throwable error, Map<String, Object> context) {
        return false;
    }

    /**
     * Called when a recoverable error threshold is reached.
     *
     * @param instance   The orchestrator instance
     * @param errorCount The number of errors
     * @param context    The current context
     */
    default void onErrorThreshold(OrchestratorInstance instance, int errorCount, Map<String, Object> context) {}
}
