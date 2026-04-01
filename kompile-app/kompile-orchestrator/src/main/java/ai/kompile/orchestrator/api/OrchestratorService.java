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
import ai.kompile.orchestrator.model.OrchestratorStatus;
import ai.kompile.orchestrator.model.llm.LlmSession;
import ai.kompile.orchestrator.model.llm.LlmTrigger;
import ai.kompile.orchestrator.model.state.StateDefinition;
import ai.kompile.orchestrator.model.task.TaskDefinition;
import ai.kompile.orchestrator.model.task.TaskInstance;
import ai.kompile.orchestrator.model.workflow.Workflow;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Main service interface for the orchestrator.
 * Provides a unified API for managing orchestrator instances and their components.
 */
public interface OrchestratorService {

    // ==================== Instance Lifecycle ====================

    /**
     * Create a new orchestrator instance.
     *
     * @param name        The instance name
     * @param description The instance description
     * @return The created instance
     */
    OrchestratorInstance create(String name, String description);

    /**
     * Create a new orchestrator instance with configuration.
     *
     * @param name           The instance name
     * @param description    The instance description
     * @param initialContext Initial context values
     * @param customStates   Custom state definitions
     * @param taskDefinitions Task definitions
     * @param llmTriggers    LLM triggers
     * @return The created instance
     */
    OrchestratorInstance create(String name, String description,
                                Map<String, Object> initialContext,
                                List<StateDefinition> customStates,
                                List<TaskDefinition> taskDefinitions,
                                List<LlmTrigger> llmTriggers);

    /**
     * Start an orchestrator instance.
     *
     * @param instanceId The instance ID
     */
    void start(String instanceId);

    /**
     * Pause an orchestrator instance.
     *
     * @param instanceId The instance ID
     */
    void pause(String instanceId);

    /**
     * Resume a paused orchestrator instance.
     *
     * @param instanceId The instance ID
     */
    void resume(String instanceId);

    /**
     * Stop an orchestrator instance.
     *
     * @param instanceId The instance ID
     */
    void stop(String instanceId);

    /**
     * Delete an orchestrator instance.
     *
     * @param instanceId The instance ID
     */
    void delete(String instanceId);

    // ==================== Instance Queries ====================

    /**
     * Get an orchestrator instance by ID.
     *
     * @param instanceId The instance ID
     * @return The instance, or empty if not found
     */
    Optional<OrchestratorInstance> getInstance(String instanceId);

    /**
     * Get all orchestrator instances.
     *
     * @return List of all instances
     */
    List<OrchestratorInstance> getAllInstances();

    /**
     * Get running orchestrator instances.
     *
     * @return List of running instances
     */
    List<OrchestratorInstance> getRunningInstances();

    /**
     * Get instances by status.
     *
     * @param status The status to filter by
     * @return List of matching instances
     */
    List<OrchestratorInstance> getInstancesByStatus(OrchestratorStatus status);

    // ==================== State Management ====================

    /**
     * Get the current state of an orchestrator.
     *
     * @param instanceId The instance ID
     * @return The current state definition, or empty if not found
     */
    Optional<StateDefinition> getCurrentState(String instanceId);

    /**
     * Transition to a new state.
     *
     * @param instanceId    The instance ID
     * @param targetStateId The target state ID
     */
    void transitionTo(String instanceId, String targetStateId);

    /**
     * Transition to a new state with context updates.
     *
     * @param instanceId    The instance ID
     * @param targetStateId The target state ID
     * @param context       Context updates
     */
    void transitionTo(String instanceId, String targetStateId, Map<String, Object> context);

    /**
     * Register a custom state.
     *
     * @param instanceId The instance ID
     * @param state      The state definition
     */
    void registerState(String instanceId, StateDefinition state);

    /**
     * Register a state handler.
     *
     * @param stateId The state ID
     * @param handler The handler
     */
    void registerStateHandler(String stateId, StateHandler handler);

    // ==================== Task Management ====================

    /**
     * Execute a task.
     *
     * @param instanceId       The instance ID
     * @param taskDefinitionId The task definition ID
     * @param variables        Variables for the task
     * @return The task instance
     */
    TaskInstance executeTask(String instanceId, String taskDefinitionId, Map<String, String> variables);

    /**
     * Execute a command directly.
     *
     * @param instanceId The instance ID
     * @param command    The command to execute
     * @return The task instance
     */
    TaskInstance executeCommand(String instanceId, String command);

    /**
     * Cancel a running task.
     *
     * @param taskInstanceId The task instance ID
     */
    void cancelTask(Long taskInstanceId);

    /**
     * Register a task definition.
     *
     * @param definition The task definition
     */
    void registerTaskDefinition(TaskDefinition definition);

    // ==================== Workflow Management ====================

    /**
     * Start a workflow.
     *
     * @param instanceId    The instance ID
     * @param name          The workflow name
     * @param initialPrompt The initial prompt
     * @return The workflow
     */
    Workflow startWorkflow(String instanceId, String name, String initialPrompt);

    /**
     * Start a workflow with options.
     *
     * @param instanceId    The instance ID
     * @param name          The workflow name
     * @param initialPrompt The initial prompt
     * @param autoAdvance   Whether to auto-advance steps
     * @param maxSteps      Maximum number of steps
     * @return The workflow
     */
    Workflow startWorkflow(String instanceId, String name, String initialPrompt,
                           boolean autoAdvance, Integer maxSteps);

    /**
     * Advance a workflow.
     *
     * @param workflowId The workflow ID
     */
    void advanceWorkflow(Long workflowId);

    /**
     * Approve a workflow step.
     *
     * @param workflowId The workflow ID
     * @param stepNumber The step number
     */
    void approveWorkflowStep(Long workflowId, Integer stepNumber);

    /**
     * Reject a workflow step.
     *
     * @param workflowId The workflow ID
     * @param stepNumber The step number
     * @param feedback   Feedback for the rejection
     */
    void rejectWorkflowStep(Long workflowId, Integer stepNumber, String feedback);

    /**
     * Cancel a workflow.
     *
     * @param workflowId The workflow ID
     */
    void cancelWorkflow(Long workflowId);

    // ==================== LLM Management ====================

    /**
     * Invoke LLM with a prompt.
     *
     * @param instanceId The instance ID
     * @param prompt     The prompt
     * @return The LLM session
     */
    LlmSession invokeLlm(String instanceId, String prompt);

    /**
     * Invoke LLM with a specific provider.
     *
     * @param instanceId The instance ID
     * @param providerId The provider ID
     * @param prompt     The prompt
     * @return The LLM session
     */
    LlmSession invokeLlm(String instanceId, String providerId, String prompt);

    /**
     * Cancel an LLM session.
     *
     * @param sessionId The session ID
     */
    void cancelLlmSession(Long sessionId);

    /**
     * Register an LLM trigger.
     *
     * @param trigger The trigger
     */
    void registerLlmTrigger(LlmTrigger trigger);

    /**
     * Register an LLM provider.
     *
     * @param provider The provider
     */
    void registerLlmProvider(LlmProvider provider);

    // ==================== Context Management ====================

    /**
     * Update the context of an orchestrator instance.
     *
     * @param instanceId The instance ID
     * @param context    Context updates
     */
    void updateContext(String instanceId, Map<String, Object> context);

    /**
     * Get the context of an orchestrator instance.
     *
     * @param instanceId The instance ID
     * @return The context
     */
    Map<String, Object> getContext(String instanceId);

    // ==================== Recovery ====================

    /**
     * Create a snapshot for recovery.
     *
     * @param instanceId The instance ID
     */
    void createSnapshot(String instanceId);

    /**
     * Recover an orchestrator from a snapshot.
     *
     * @param instanceId The instance ID
     */
    void recover(String instanceId);
}
