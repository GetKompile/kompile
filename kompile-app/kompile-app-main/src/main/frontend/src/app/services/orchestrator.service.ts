/*
 *   Copyright 2025 Kompile Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 * limitations under the License.
 */

import { Injectable } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { Observable } from 'rxjs';
import { BaseService } from './base.service';
import {
  StateDefinition,
  StateTransition,
  StateMachineConfig,
  StatePositionUpdate,
  StateCategory,
  TransitionConditionType,
  OutputClassifier,
  ClassificationRule,
  ClassificationResult,
  PatternTestResult,
  AuditLogEntry,
  AuditSearchCriteria,
  AuditStats,
  AuditEventType,
  AuditEntityType,
  PagedResult,
  EnhancedTaskDefinition,
  TaskDefinition as NewTaskDefinition,
  AgentInfo,
  ExportFormat
} from '../models/orchestrator-models';

// Re-export the models for backward compatibility
export type {
  StateDefinition,
  StateTransition,
  StateMachineConfig,
  StateCategory,
  TransitionConditionType,
  OutputClassifier,
  ClassificationRule,
  AuditLogEntry,
  AuditSearchCriteria,
  AuditStats,
  EnhancedTaskDefinition,
  TaskDefinition as NewTaskDefinition
} from '../models/orchestrator-models';

// Legacy TaskDefinition for backward compatibility
export interface TaskDefinition {
  taskId: string;
  name: string;
  description?: string;
  type?: string;
  command?: string;
  timeout?: number;
  retryCount?: number;
  metadata?: Record<string, any>;
}

export interface LlmTrigger {
  triggerId: string;
  name: string;
  triggerType?: string;
  stateConditions?: string[];
  promptTemplate?: string;
  autoExecute?: boolean;
}

export interface OrchestratorInstance {
  instanceId: string;
  name: string;
  description?: string;
  status: OrchestratorStatus;
  currentStateId?: string;
  previousStateId?: string;
  context?: Record<string, any>;
  workingDirectory?: string;
  createdAt?: string;
  updatedAt?: string;
  startedAt?: string;
  completedAt?: string;
  errorMessage?: string;
  ownerId?: string;
  tags?: string;
}

export type OrchestratorStatus = 'CREATED' | 'RUNNING' | 'PAUSED' | 'STOPPED' | 'ERROR' | 'COMPLETED';

export interface TaskInstance {
  id: number;
  taskDefinitionId: string;
  orchestratorInstanceId: string;
  name?: string;
  taskType?: TaskType;
  status: TaskStatus;
  command?: string;
  output?: string;
  exitCode?: number;
  errorMessage?: string;
  workingDirectory?: string;
  timeoutSeconds?: number;
  startTime?: string;
  endTime?: string;
  llmSessionId?: number;
  variables?: Record<string, any>;
  processId?: number;
  retryAttempt?: number;
  parentTaskId?: number;
  metadata?: Record<string, any>;
}

export type TaskType = 'SHELL' | 'HTTP' | 'CODE' | 'LLM_QUERY' | 'CUSTOM';
export type TaskStatus = 'PENDING' | 'RUNNING' | 'SUCCESS' | 'FAILED' | 'TIMEOUT' | 'CANCELLED';

export interface Workflow {
  id: number;
  name: string;
  orchestratorInstanceId: string;
  status: WorkflowStatus;
  currentStep?: number;
  maxSteps?: number;
  autoAdvance?: boolean;
  createdAt?: string;
  updatedAt?: string;
}

export type WorkflowStatus = 'CREATED' | 'IN_PROGRESS' | 'AWAITING_APPROVAL' | 'COMPLETED' | 'CANCELLED' | 'FAILED';

export interface WorkflowStep {
  id: number;
  workflowId: number;
  stepNumber: number;
  description?: string;
  status: WorkflowStepStatus;
  taskInstanceId?: number;
  llmSessionId?: number;
  llmAnalysis?: string;
  nextAction?: ActionProposal;
  userApproved?: boolean;
  rejectionReason?: string;
  taskOutput?: string;
  errorMessage?: string;
  startTime?: string;
  endTime?: string;
  inputContextJson?: string;
  outputContextJson?: string;
}

export interface ActionProposal {
  actionType?: ActionType;
  command?: string;
  taskDefinitionId?: string;
  llmPrompt?: string;
  reasoning?: string;
  expectedOutcome?: string;
  confidence?: number;
  analysis?: string;
  variables?: Record<string, string>;
  customConfigJson?: string;
}

export type ActionType = 'EXECUTE_COMMAND' | 'RUN_TASK' | 'INVOKE_LLM' | 'WAIT' | 'COMPLETE' | 'FAIL' | 'AWAIT_APPROVAL' | 'EXECUTE_CODE' | 'HTTP_REQUEST' | 'CUSTOM';
export type WorkflowStepStatus = 'PENDING' | 'WAITING_APPROVAL' | 'RUNNING' | 'COMPLETED' | 'FAILED' | 'SKIPPED' | 'REJECTED';

export interface CreateOrchestratorRequest {
  name: string;
  description?: string;
  initialContext?: Record<string, any>;
  customStates?: StateDefinition[];
  taskDefinitions?: TaskDefinition[];
  llmTriggers?: LlmTrigger[];
  // Agent/LLM configuration
  agentName?: string;
  systemPrompt?: string;
  defaultPromptTemplate?: string;
  // RAG configuration
  enableRag?: boolean;
  ragFolderId?: string;
  ragMaxResults?: number;
  ragSimilarityThreshold?: number;
  // Tool configuration
  enableTools?: boolean;
  allowedTools?: string;
}

export interface StateTransitionRequest {
  targetStateId: string;
  context?: Record<string, any>;
}

export interface ExecuteTaskRequest {
  taskDefinitionId: string;
  variables?: Record<string, any>;
}

export interface ExecuteCommandRequest {
  command: string;
}

export interface WorkflowStepDefinition {
  stepId?: string;
  name?: string;
  type: 'prompt' | 'tool' | 'condition' | 'parallel';
  prompt?: string;
  agentName?: string;
  factSheetId?: number | null;
  toolName?: string;
  toolArgs?: string;
  condition?: string;
  onSuccess?: string;
  onFailure?: string;
  parallelSteps?: string[];
  retryCount?: number;
  timeoutSeconds?: number;
}

export interface StartWorkflowRequest {
  name: string;
  initialPrompt: string;
  autoAdvance?: boolean;
  maxSteps?: number;
  // Visual builder step definitions
  steps?: WorkflowStepDefinition[];
}

export interface LlmSession {
  id: number;
  orchestratorInstanceId: string;
  providerId?: string;
  status: LlmSessionStatus;
  initialPrompt?: string;
  systemPrompt?: string;
  output?: string;
  errorMessage?: string;
  inputTokens?: number;
  outputTokens?: number;
  taskInstanceId?: number;
  workflowId?: number;
  workflowStepId?: number;
  startTime?: string;
  endTime?: string;
}

export type LlmSessionStatus = 'STARTING' | 'RUNNING' | 'COMPLETED' | 'FAILED' | 'TIMEOUT' | 'CANCELLED';

export interface LlmTriggerConfig {
  triggerId: string;
  name: string;
  triggerType?: LlmTriggerType;
  enabled?: boolean;
  stateConditions?: string[];
  promptTemplate?: string;
  autoExecute?: boolean;
}

export type LlmTriggerType = 'ON_STATE_ENTER' | 'ON_TASK_COMPLETE' | 'ON_ERROR';

export interface InvokeLlmRequest {
  prompt: string;
  providerId?: string;
  systemPrompt?: string;
}

@Injectable({
  providedIn: 'root'
})
export class OrchestratorService extends BaseService {

  constructor(private http: HttpClient) {
    super();
  }

  // ==================== Orchestrator Instance Operations ====================

  createOrchestrator(request: CreateOrchestratorRequest): Observable<OrchestratorInstance> {
    return this.http.post<OrchestratorInstance>(`${this.backendUrl}/orchestrator`, request);
  }

  getOrchestrator(instanceId: string): Observable<OrchestratorInstance> {
    return this.http.get<OrchestratorInstance>(`${this.backendUrl}/orchestrator/${instanceId}`);
  }

  getAllOrchestrators(): Observable<OrchestratorInstance[]> {
    return this.http.get<OrchestratorInstance[]>(`${this.backendUrl}/orchestrator`);
  }

  getRunningOrchestrators(): Observable<OrchestratorInstance[]> {
    return this.http.get<OrchestratorInstance[]>(`${this.backendUrl}/orchestrator/running`);
  }

  getOrchestratorsByStatus(status: OrchestratorStatus): Observable<OrchestratorInstance[]> {
    return this.http.get<OrchestratorInstance[]>(`${this.backendUrl}/orchestrator/status/${status}`);
  }

  startOrchestrator(instanceId: string): Observable<any> {
    return this.http.post(`${this.backendUrl}/orchestrator/${instanceId}/start`, {});
  }

  pauseOrchestrator(instanceId: string): Observable<any> {
    return this.http.post(`${this.backendUrl}/orchestrator/${instanceId}/pause`, {});
  }

  resumeOrchestrator(instanceId: string): Observable<any> {
    return this.http.post(`${this.backendUrl}/orchestrator/${instanceId}/resume`, {});
  }

  stopOrchestrator(instanceId: string): Observable<any> {
    return this.http.post(`${this.backendUrl}/orchestrator/${instanceId}/stop`, {});
  }

  deleteOrchestrator(instanceId: string): Observable<void> {
    return this.http.delete<void>(`${this.backendUrl}/orchestrator/${instanceId}`);
  }

  // ==================== State Operations ====================

  getCurrentState(instanceId: string): Observable<StateDefinition> {
    return this.http.get<StateDefinition>(`${this.backendUrl}/orchestrator/${instanceId}/state`);
  }

  transitionTo(instanceId: string, request: StateTransitionRequest): Observable<any> {
    return this.http.post(`${this.backendUrl}/orchestrator/${instanceId}/transition`, request);
  }

  registerState(instanceId: string, state: StateDefinition): Observable<any> {
    return this.http.post(`${this.backendUrl}/orchestrator/${instanceId}/states`, state);
  }

  // ==================== Context Operations ====================

  getContext(instanceId: string): Observable<Record<string, any>> {
    return this.http.get<Record<string, any>>(`${this.backendUrl}/orchestrator/${instanceId}/context`);
  }

  updateContext(instanceId: string, context: Record<string, any>): Observable<any> {
    return this.http.put(`${this.backendUrl}/orchestrator/${instanceId}/context`, context);
  }

  // ==================== Snapshot/Recovery ====================

  createSnapshot(instanceId: string): Observable<any> {
    return this.http.post(`${this.backendUrl}/orchestrator/${instanceId}/snapshot`, {});
  }

  recoverOrchestrator(instanceId: string): Observable<any> {
    return this.http.post(`${this.backendUrl}/orchestrator/${instanceId}/recover`, {});
  }

  // ==================== Task Operations ====================

  executeTask(instanceId: string, request: ExecuteTaskRequest): Observable<TaskInstance> {
    return this.http.post<TaskInstance>(`${this.backendUrl}/orchestrator/${instanceId}/tasks/execute`, request);
  }

  executeCommand(instanceId: string, request: ExecuteCommandRequest): Observable<TaskInstance> {
    return this.http.post<TaskInstance>(`${this.backendUrl}/orchestrator/${instanceId}/tasks/command`, request);
  }

  cancelTask(instanceId: string, taskId: number): Observable<any> {
    return this.http.post(`${this.backendUrl}/orchestrator/${instanceId}/tasks/${taskId}/cancel`, {});
  }

  getTask(instanceId: string, taskId: number): Observable<TaskInstance> {
    return this.http.get<TaskInstance>(`${this.backendUrl}/orchestrator/${instanceId}/tasks/${taskId}`);
  }

  getRunningTasks(instanceId: string): Observable<TaskInstance[]> {
    return this.http.get<TaskInstance[]>(`${this.backendUrl}/orchestrator/${instanceId}/tasks/running`);
  }

  getTaskHistory(instanceId: string): Observable<TaskInstance[]> {
    return this.http.get<TaskInstance[]>(`${this.backendUrl}/orchestrator/${instanceId}/tasks/history`);
  }

  registerTaskDefinition(instanceId: string, definition: TaskDefinition): Observable<any> {
    return this.http.post(`${this.backendUrl}/orchestrator/${instanceId}/tasks/definitions`, definition);
  }

  // ==================== Workflow Operations ====================

  startWorkflow(instanceId: string, request: StartWorkflowRequest): Observable<Workflow> {
    return this.http.post<Workflow>(`${this.backendUrl}/orchestrator/${instanceId}/workflows`, request);
  }

  getWorkflow(instanceId: string, workflowId: number): Observable<Workflow> {
    return this.http.get<Workflow>(`${this.backendUrl}/orchestrator/${instanceId}/workflows/${workflowId}`);
  }

  getActiveWorkflows(instanceId: string): Observable<Workflow[]> {
    return this.http.get<Workflow[]>(`${this.backendUrl}/orchestrator/${instanceId}/workflows/active`);
  }

  getAllWorkflows(instanceId: string): Observable<Workflow[]> {
    return this.http.get<Workflow[]>(`${this.backendUrl}/orchestrator/${instanceId}/workflows`);
  }

  advanceWorkflow(instanceId: string, workflowId: number): Observable<any> {
    return this.http.post(`${this.backendUrl}/orchestrator/${instanceId}/workflows/${workflowId}/advance`, {});
  }

  approveWorkflowStep(instanceId: string, workflowId: number, stepNumber: number): Observable<any> {
    return this.http.post(`${this.backendUrl}/orchestrator/${instanceId}/workflows/${workflowId}/steps/${stepNumber}/approve`, {});
  }

  rejectWorkflowStep(instanceId: string, workflowId: number, stepNumber: number, feedback: string): Observable<any> {
    return this.http.post(`${this.backendUrl}/orchestrator/${instanceId}/workflows/${workflowId}/steps/${stepNumber}/reject`, { feedback });
  }

  cancelWorkflow(instanceId: string, workflowId: number): Observable<any> {
    return this.http.post(`${this.backendUrl}/orchestrator/${instanceId}/workflows/${workflowId}/cancel`, {});
  }

  getWorkflowSteps(instanceId: string, workflowId: number): Observable<WorkflowStep[]> {
    return this.http.get<WorkflowStep[]>(`${this.backendUrl}/orchestrator/${instanceId}/workflows/${workflowId}/steps`);
  }

  // ==================== LLM Session Operations ====================

  invokeLlm(instanceId: string, request: InvokeLlmRequest): Observable<LlmSession> {
    return this.http.post<LlmSession>(`${this.backendUrl}/orchestrator/${instanceId}/llm/invoke`, request);
  }

  getLlmSession(instanceId: string, sessionId: number): Observable<LlmSession> {
    return this.http.get<LlmSession>(`${this.backendUrl}/orchestrator/${instanceId}/llm/sessions/${sessionId}`);
  }

  getActiveLlmSessions(instanceId: string): Observable<LlmSession[]> {
    return this.http.get<LlmSession[]>(`${this.backendUrl}/orchestrator/${instanceId}/llm/sessions/active`);
  }

  getLlmSessionHistory(instanceId: string): Observable<LlmSession[]> {
    return this.http.get<LlmSession[]>(`${this.backendUrl}/orchestrator/${instanceId}/llm/sessions`);
  }

  cancelLlmSession(instanceId: string, sessionId: number): Observable<any> {
    return this.http.post(`${this.backendUrl}/orchestrator/${instanceId}/llm/sessions/${sessionId}/cancel`, {});
  }

  getLlmProviders(instanceId: string): Observable<string[]> {
    return this.http.get<string[]>(`${this.backendUrl}/orchestrator/${instanceId}/llm/providers`);
  }

  registerLlmTrigger(instanceId: string, trigger: LlmTriggerConfig): Observable<any> {
    return this.http.post(`${this.backendUrl}/orchestrator/${instanceId}/llm/triggers`, trigger);
  }

  getLlmTriggers(instanceId: string): Observable<LlmTriggerConfig[]> {
    return this.http.get<LlmTriggerConfig[]>(`${this.backendUrl}/orchestrator/${instanceId}/llm/triggers`);
  }

  // ==================== Task Definition Operations ====================

  getTaskDefinitions(instanceId: string): Observable<TaskDefinition[]> {
    return this.http.get<TaskDefinition[]>(`${this.backendUrl}/orchestrator/${instanceId}/tasks/definitions`);
  }

  // ==================== Task Output/Logs ====================

  getTaskOutput(instanceId: string, taskId: number): Observable<TaskInstance> {
    return this.http.get<TaskInstance>(`${this.backendUrl}/orchestrator/${instanceId}/tasks/${taskId}`);
  }

  // ==================== State Machine Configuration ====================

  /**
   * Get the complete state machine configuration for an instance.
   */
  getStateMachineConfig(instanceId: string): Observable<StateMachineConfig> {
    return this.http.get<StateMachineConfig>(
      `${this.backendUrl}/orchestrator/${instanceId}/state-machine`
    );
  }

  /**
   * Import a complete state machine configuration.
   */
  importStateMachineConfig(instanceId: string, config: StateMachineConfig): Observable<StateMachineConfig> {
    return this.http.post<StateMachineConfig>(
      `${this.backendUrl}/orchestrator/${instanceId}/state-machine/import`,
      config
    );
  }

  /**
   * Export the state machine configuration.
   */
  exportStateMachineConfig(instanceId: string): Observable<StateMachineConfig> {
    return this.http.get<StateMachineConfig>(
      `${this.backendUrl}/orchestrator/${instanceId}/state-machine/export`
    );
  }

  /**
   * Get all states for an instance.
   */
  getStates(instanceId: string, includeGlobal: boolean = false): Observable<StateDefinition[]> {
    const params = new HttpParams().set('includeGlobal', includeGlobal.toString());
    return this.http.get<StateDefinition[]>(
      `${this.backendUrl}/orchestrator/${instanceId}/state-machine/states`,
      { params }
    );
  }

  /**
   * Get a specific state.
   */
  getState(instanceId: string, stateId: string): Observable<StateDefinition> {
    return this.http.get<StateDefinition>(
      `${this.backendUrl}/orchestrator/${instanceId}/state-machine/states/${stateId}`
    );
  }

  /**
   * Create a new state.
   */
  createState(instanceId: string, state: StateDefinition): Observable<StateDefinition> {
    return this.http.post<StateDefinition>(
      `${this.backendUrl}/orchestrator/${instanceId}/state-machine/states`,
      state
    );
  }

  /**
   * Create default states for an instance.
   */
  createDefaultStates(instanceId: string): Observable<StateDefinition[]> {
    return this.http.post<StateDefinition[]>(
      `${this.backendUrl}/orchestrator/${instanceId}/state-machine/states/defaults`,
      {}
    );
  }

  /**
   * Update a state.
   */
  updateState(instanceId: string, stateId: string, state: StateDefinition): Observable<StateDefinition> {
    return this.http.put<StateDefinition>(
      `${this.backendUrl}/orchestrator/${instanceId}/state-machine/states/${stateId}`,
      state
    );
  }

  /**
   * Delete a state.
   */
  deleteState(instanceId: string, stateId: string): Observable<any> {
    return this.http.delete(
      `${this.backendUrl}/orchestrator/${instanceId}/state-machine/states/${stateId}`
    );
  }

  /**
   * Update state positions (for visual editor).
   */
  updateStatePositions(instanceId: string, positions: StatePositionUpdate[]): Observable<any> {
    return this.http.post(
      `${this.backendUrl}/orchestrator/${instanceId}/state-machine/states/positions`,
      positions
    );
  }

  /**
   * Get available state categories.
   */
  getStateCategories(instanceId: string): Observable<StateCategory[]> {
    return this.http.get<StateCategory[]>(
      `${this.backendUrl}/orchestrator/${instanceId}/state-machine/categories`
    );
  }

  /**
   * Get all transitions for an instance.
   */
  getTransitions(instanceId: string): Observable<StateTransition[]> {
    return this.http.get<StateTransition[]>(
      `${this.backendUrl}/orchestrator/${instanceId}/state-machine/transitions`
    );
  }

  /**
   * Get transitions from a specific state.
   */
  getTransitionsFromState(instanceId: string, stateId: string): Observable<StateTransition[]> {
    return this.http.get<StateTransition[]>(
      `${this.backendUrl}/orchestrator/${instanceId}/state-machine/states/${stateId}/transitions`
    );
  }

  /**
   * Get a specific transition.
   */
  getTransition(instanceId: string, transitionId: number): Observable<StateTransition> {
    return this.http.get<StateTransition>(
      `${this.backendUrl}/orchestrator/${instanceId}/state-machine/transitions/${transitionId}`
    );
  }

  /**
   * Create a new transition.
   */
  createTransition(instanceId: string, transition: StateTransition): Observable<StateTransition> {
    return this.http.post<StateTransition>(
      `${this.backendUrl}/orchestrator/${instanceId}/state-machine/transitions`,
      transition
    );
  }

  /**
   * Create a transition from a specific state.
   */
  createTransitionFromState(
    instanceId: string,
    stateId: string,
    transition: StateTransition
  ): Observable<StateTransition> {
    return this.http.post<StateTransition>(
      `${this.backendUrl}/orchestrator/${instanceId}/state-machine/states/${stateId}/transitions`,
      transition
    );
  }

  /**
   * Update a transition.
   */
  updateTransition(
    instanceId: string,
    transitionId: number,
    transition: StateTransition
  ): Observable<StateTransition> {
    return this.http.put<StateTransition>(
      `${this.backendUrl}/orchestrator/${instanceId}/state-machine/transitions/${transitionId}`,
      transition
    );
  }

  /**
   * Delete a transition.
   */
  deleteTransition(instanceId: string, transitionId: number): Observable<any> {
    return this.http.delete(
      `${this.backendUrl}/orchestrator/${instanceId}/state-machine/transitions/${transitionId}`
    );
  }

  /**
   * Get available transition condition types.
   */
  getTransitionConditionTypes(instanceId: string): Observable<TransitionConditionType[]> {
    return this.http.get<TransitionConditionType[]>(
      `${this.backendUrl}/orchestrator/${instanceId}/state-machine/transition-conditions`
    );
  }

  // ==================== Audit Log Operations ====================

  /**
   * Get audit logs for an instance.
   */
  getAuditLogs(instanceId: string, page: number = 0, size: number = 50): Observable<PagedResult<AuditLogEntry>> {
    const params = new HttpParams()
      .set('page', page.toString())
      .set('size', size.toString());
    return this.http.get<PagedResult<AuditLogEntry>>(
      `${this.backendUrl}/orchestrator/${instanceId}/audit`,
      { params }
    );
  }

  /**
   * Search audit logs with filters.
   */
  searchAuditLogs(
    instanceId: string,
    criteria: AuditSearchCriteria,
    page: number = 0,
    size: number = 50
  ): Observable<PagedResult<AuditLogEntry>> {
    let params = new HttpParams()
      .set('page', page.toString())
      .set('size', size.toString());

    if (criteria.eventType) {
      params = params.set('eventType', criteria.eventType);
    }
    if (criteria.entityType) {
      params = params.set('entityType', criteria.entityType);
    }
    if (criteria.fromTime) {
      params = params.set('from', criteria.fromTime);
    }
    if (criteria.toTime) {
      params = params.set('to', criteria.toTime);
    }
    if (criteria.search) {
      params = params.set('search', criteria.search);
    }
    if (criteria.actorId) {
      params = params.set('actorId', criteria.actorId);
    }
    if (criteria.errorsOnly) {
      params = params.set('errorsOnly', criteria.errorsOnly.toString());
    }

    return this.http.get<PagedResult<AuditLogEntry>>(
      `${this.backendUrl}/orchestrator/${instanceId}/audit/search`,
      { params }
    );
  }

  /**
   * Get audit statistics.
   */
  getAuditStats(instanceId: string): Observable<AuditStats> {
    return this.http.get<AuditStats>(
      `${this.backendUrl}/orchestrator/${instanceId}/audit/stats`
    );
  }

  /**
   * Export audit logs.
   */
  exportAuditLogs(
    instanceId: string,
    format: ExportFormat = 'json',
    criteria?: AuditSearchCriteria
  ): Observable<Blob> {
    let params = new HttpParams().set('format', format);

    if (criteria) {
      if (criteria.eventType) {
        params = params.set('eventType', criteria.eventType);
      }
      if (criteria.entityType) {
        params = params.set('entityType', criteria.entityType);
      }
      if (criteria.fromTime) {
        params = params.set('from', criteria.fromTime);
      }
      if (criteria.toTime) {
        params = params.set('to', criteria.toTime);
      }
    }

    return this.http.get(
      `${this.backendUrl}/orchestrator/${instanceId}/audit/export`,
      { params, responseType: 'blob' }
    );
  }

  /**
   * Get audit logs by actor.
   */
  getAuditLogsByActor(
    instanceId: string,
    actorId: string,
    page: number = 0,
    size: number = 50
  ): Observable<PagedResult<AuditLogEntry>> {
    const params = new HttpParams()
      .set('page', page.toString())
      .set('size', size.toString());
    return this.http.get<PagedResult<AuditLogEntry>>(
      `${this.backendUrl}/orchestrator/${instanceId}/audit/actor/${actorId}`,
      { params }
    );
  }

  /**
   * Get available audit event types.
   */
  getAuditEventTypes(instanceId: string): Observable<AuditEventType[]> {
    return this.http.get<AuditEventType[]>(
      `${this.backendUrl}/orchestrator/${instanceId}/audit/event-types`
    );
  }

  /**
   * Get available audit entity types.
   */
  getAuditEntityTypes(instanceId: string): Observable<AuditEntityType[]> {
    return this.http.get<AuditEntityType[]>(
      `${this.backendUrl}/orchestrator/${instanceId}/audit/entity-types`
    );
  }

  // ==================== Output Classification Operations ====================

  /**
   * Get all output classifiers for an instance.
   */
  getOutputClassifiers(instanceId: string): Observable<OutputClassifier[]> {
    return this.http.get<OutputClassifier[]>(
      `${this.backendUrl}/orchestrator/${instanceId}/classifiers`
    );
  }

  /**
   * Get a specific output classifier.
   */
  getOutputClassifier(instanceId: string, classifierId: number): Observable<OutputClassifier> {
    return this.http.get<OutputClassifier>(
      `${this.backendUrl}/orchestrator/${instanceId}/classifiers/${classifierId}`
    );
  }

  /**
   * Create a new output classifier.
   */
  createOutputClassifier(instanceId: string, classifier: OutputClassifier): Observable<OutputClassifier> {
    return this.http.post<OutputClassifier>(
      `${this.backendUrl}/orchestrator/${instanceId}/classifiers`,
      classifier
    );
  }

  /**
   * Update an output classifier.
   */
  updateOutputClassifier(
    instanceId: string,
    classifierId: number,
    classifier: OutputClassifier
  ): Observable<OutputClassifier> {
    return this.http.put<OutputClassifier>(
      `${this.backendUrl}/orchestrator/${instanceId}/classifiers/${classifierId}`,
      classifier
    );
  }

  /**
   * Delete an output classifier.
   */
  deleteOutputClassifier(instanceId: string, classifierId: number): Observable<any> {
    return this.http.delete(
      `${this.backendUrl}/orchestrator/${instanceId}/classifiers/${classifierId}`
    );
  }

  /**
   * Toggle classifier enabled status.
   */
  toggleClassifierEnabled(instanceId: string, classifierId: number, enabled: boolean): Observable<OutputClassifier> {
    return this.http.patch<OutputClassifier>(
      `${this.backendUrl}/orchestrator/${instanceId}/classifiers/${classifierId}/enabled`,
      { enabled }
    );
  }

  /**
   * Add a rule to a classifier.
   */
  addClassificationRule(
    instanceId: string,
    classifierId: number,
    rule: ClassificationRule
  ): Observable<ClassificationRule> {
    return this.http.post<ClassificationRule>(
      `${this.backendUrl}/orchestrator/${instanceId}/classifiers/${classifierId}/rules`,
      rule
    );
  }

  /**
   * Update a classification rule.
   */
  updateClassificationRule(
    instanceId: string,
    classifierId: number,
    ruleId: number,
    rule: ClassificationRule
  ): Observable<ClassificationRule> {
    return this.http.put<ClassificationRule>(
      `${this.backendUrl}/orchestrator/${instanceId}/classifiers/${classifierId}/rules/${ruleId}`,
      rule
    );
  }

  /**
   * Delete a classification rule.
   */
  deleteClassificationRule(
    instanceId: string,
    classifierId: number,
    ruleId: number
  ): Observable<any> {
    return this.http.delete(
      `${this.backendUrl}/orchestrator/${instanceId}/classifiers/${classifierId}/rules/${ruleId}`
    );
  }

  /**
   * Reorder classification rules.
   */
  reorderClassificationRules(
    instanceId: string,
    classifierId: number,
    ruleIds: number[]
  ): Observable<any> {
    return this.http.post(
      `${this.backendUrl}/orchestrator/${instanceId}/classifiers/${classifierId}/rules/reorder`,
      ruleIds
    );
  }

  /**
   * Classify output using a specific classifier.
   */
  classifyOutput(
    instanceId: string,
    classifierId: number,
    output: string
  ): Observable<ClassificationResult> {
    return this.http.post<ClassificationResult>(
      `${this.backendUrl}/orchestrator/${instanceId}/classifiers/${classifierId}/classify`,
      { output }
    );
  }

  /**
   * Test a regex pattern against input.
   */
  testPattern(instanceId: string, pattern: string, input: string): Observable<PatternTestResult> {
    return this.http.post<PatternTestResult>(
      `${this.backendUrl}/orchestrator/${instanceId}/classifiers/test-pattern`,
      { pattern, input }
    );
  }

  /**
   * Get predefined classification templates.
   */
  getClassificationTemplates(instanceId: string): Observable<ClassificationRule[]> {
    return this.http.get<ClassificationRule[]>(
      `${this.backendUrl}/orchestrator/${instanceId}/classifiers/templates`
    );
  }

  // ==================== Enhanced Task Definition Operations ====================

  /**
   * Get all enhanced task definitions.
   */
  getEnhancedTaskDefinitions(instanceId: string): Observable<EnhancedTaskDefinition[]> {
    return this.http.get<EnhancedTaskDefinition[]>(
      `${this.backendUrl}/orchestrator/${instanceId}/task-definitions`
    );
  }

  /**
   * Get a specific enhanced task definition.
   */
  getEnhancedTaskDefinition(instanceId: string, taskId: string): Observable<EnhancedTaskDefinition> {
    return this.http.get<EnhancedTaskDefinition>(
      `${this.backendUrl}/orchestrator/${instanceId}/task-definitions/${taskId}`
    );
  }

  /**
   * Create an enhanced task definition.
   */
  createEnhancedTaskDefinition(
    instanceId: string,
    definition: EnhancedTaskDefinition
  ): Observable<EnhancedTaskDefinition> {
    return this.http.post<EnhancedTaskDefinition>(
      `${this.backendUrl}/orchestrator/${instanceId}/task-definitions`,
      definition
    );
  }

  /**
   * Update an enhanced task definition.
   */
  updateEnhancedTaskDefinition(
    instanceId: string,
    taskId: string,
    definition: EnhancedTaskDefinition
  ): Observable<EnhancedTaskDefinition> {
    return this.http.put<EnhancedTaskDefinition>(
      `${this.backendUrl}/orchestrator/${instanceId}/task-definitions/${taskId}`,
      definition
    );
  }

  /**
   * Delete an enhanced task definition.
   */
  deleteEnhancedTaskDefinition(instanceId: string, taskId: string): Observable<any> {
    return this.http.delete(
      `${this.backendUrl}/orchestrator/${instanceId}/task-definitions/${taskId}`
    );
  }

  /**
   * Test execute a task definition (dry run).
   */
  testTaskDefinition(
    instanceId: string,
    taskId: string,
    variables?: Record<string, string>
  ): Observable<any> {
    return this.http.post(
      `${this.backendUrl}/orchestrator/${instanceId}/task-definitions/${taskId}/test`,
      { variables }
    );
  }

  // ==================== Agent/Chat Integration ====================

  /**
   * Get available agents for task execution.
   */
  getAvailableAgents(instanceId: string): Observable<AgentInfo[]> {
    return this.http.get<AgentInfo[]>(
      `${this.backendUrl}/orchestrator/${instanceId}/agents`
    );
  }

  /**
   * Get available tools for a specific agent.
   */
  getAgentTools(instanceId: string, agentName: string): Observable<string[]> {
    return this.http.get<string[]>(
      `${this.backendUrl}/orchestrator/${instanceId}/agents/${agentName}/tools`
    );
  }

  /**
   * Get RAG folders available for filtering.
   */
  getRagFolders(instanceId: string): Observable<string[]> {
    return this.http.get<string[]>(
      `${this.backendUrl}/orchestrator/${instanceId}/rag/folders`
    );
  }
}
