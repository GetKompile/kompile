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
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { BaseService } from './base.service';

export interface StateDefinition {
  stateId: string;
  name: string;
  description?: string;
  category?: string;
  allowedTransitions?: string[];
  metadata?: Record<string, any>;
}

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
  status: TaskStatus;
  output?: string;
  errorMessage?: string;
  startedAt?: string;
  completedAt?: string;
  variables?: Record<string, any>;
}

export type TaskStatus = 'PENDING' | 'RUNNING' | 'COMPLETED' | 'FAILED' | 'CANCELLED';

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
  status: WorkflowStepStatus;
  proposedAction?: string;
  feedback?: string;
  executedAt?: string;
}

export type WorkflowStepStatus = 'PENDING' | 'PROPOSED' | 'APPROVED' | 'REJECTED' | 'EXECUTED' | 'FAILED';

export interface CreateOrchestratorRequest {
  name: string;
  description?: string;
  initialContext?: Record<string, any>;
  customStates?: StateDefinition[];
  taskDefinitions?: TaskDefinition[];
  llmTriggers?: LlmTrigger[];
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

export interface StartWorkflowRequest {
  name: string;
  initialPrompt: string;
  autoAdvance?: boolean;
  maxSteps?: number;
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
}
