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

import { Component, OnInit, OnDestroy } from '@angular/core';
import { Subject, interval } from 'rxjs';
import { takeUntil, switchMap, startWith } from 'rxjs/operators';
import {
  OrchestratorService,
  OrchestratorInstance,
  OrchestratorStatus,
  TaskInstance,
  Workflow,
  WorkflowStep,
  CreateOrchestratorRequest,
  StartWorkflowRequest
} from '../../services/orchestrator.service';

@Component({
  standalone: false,
  selector: 'app-orchestrator-hub',
  templateUrl: './orchestrator-hub.component.html',
  styleUrls: ['./orchestrator-hub.component.css']
})
export class OrchestratorHubComponent implements OnInit, OnDestroy {
  activeSubTab: 'instances' | 'tasks' | 'workflows' | 'create' = 'instances';

  // Orchestrator instances
  orchestrators: OrchestratorInstance[] = [];
  selectedOrchestrator: OrchestratorInstance | null = null;
  loading = false;
  error: string | null = null;

  // Tasks
  tasks: TaskInstance[] = [];
  runningTasks: TaskInstance[] = [];
  loadingTasks = false;

  // Workflows
  workflows: Workflow[] = [];
  activeWorkflows: Workflow[] = [];
  selectedWorkflow: Workflow | null = null;
  workflowSteps: WorkflowStep[] = [];
  loadingWorkflows = false;

  // Create form
  newOrchestratorName = '';
  newOrchestratorDescription = '';
  creating = false;

  // Workflow form
  newWorkflowName = '';
  newWorkflowPrompt = '';
  newWorkflowAutoAdvance = false;
  newWorkflowMaxSteps = 10;
  creatingWorkflow = false;

  // Command execution
  commandToExecute = '';
  executingCommand = false;

  private destroy$ = new Subject<void>();

  constructor(private orchestratorService: OrchestratorService) {}

  ngOnInit(): void {
    this.loadOrchestrators();
    // Auto-refresh every 5 seconds
    interval(5000)
      .pipe(
        takeUntil(this.destroy$),
        startWith(0)
      )
      .subscribe(() => {
        if (this.activeSubTab === 'instances') {
          this.loadOrchestrators();
        } else if (this.activeSubTab === 'tasks' && this.selectedOrchestrator) {
          this.loadTasks();
        } else if (this.activeSubTab === 'workflows' && this.selectedOrchestrator) {
          this.loadWorkflows();
        }
      });
  }

  ngOnDestroy(): void {
    this.destroy$.next();
    this.destroy$.complete();
  }

  // ==================== Orchestrator Operations ====================

  loadOrchestrators(): void {
    this.orchestratorService.getAllOrchestrators()
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: (data) => {
          this.orchestrators = data;
          this.error = null;
        },
        error: (err) => {
          this.error = 'Failed to load orchestrators: ' + (err.error?.message || err.message);
        }
      });
  }

  selectOrchestrator(orchestrator: OrchestratorInstance): void {
    this.selectedOrchestrator = orchestrator;
    this.loadTasks();
    this.loadWorkflows();
  }

  startOrchestrator(orchestrator: OrchestratorInstance): void {
    this.orchestratorService.startOrchestrator(orchestrator.instanceId)
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: () => this.loadOrchestrators(),
        error: (err) => {
          this.error = 'Failed to start orchestrator: ' + (err.error?.message || err.message);
        }
      });
  }

  pauseOrchestrator(orchestrator: OrchestratorInstance): void {
    this.orchestratorService.pauseOrchestrator(orchestrator.instanceId)
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: () => this.loadOrchestrators(),
        error: (err) => {
          this.error = 'Failed to pause orchestrator: ' + (err.error?.message || err.message);
        }
      });
  }

  resumeOrchestrator(orchestrator: OrchestratorInstance): void {
    this.orchestratorService.resumeOrchestrator(orchestrator.instanceId)
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: () => this.loadOrchestrators(),
        error: (err) => {
          this.error = 'Failed to resume orchestrator: ' + (err.error?.message || err.message);
        }
      });
  }

  stopOrchestrator(orchestrator: OrchestratorInstance): void {
    this.orchestratorService.stopOrchestrator(orchestrator.instanceId)
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: () => this.loadOrchestrators(),
        error: (err) => {
          this.error = 'Failed to stop orchestrator: ' + (err.error?.message || err.message);
        }
      });
  }

  deleteOrchestrator(orchestrator: OrchestratorInstance): void {
    if (confirm(`Are you sure you want to delete orchestrator "${orchestrator.name}"?`)) {
      this.orchestratorService.deleteOrchestrator(orchestrator.instanceId)
        .pipe(takeUntil(this.destroy$))
        .subscribe({
          next: () => {
            if (this.selectedOrchestrator?.instanceId === orchestrator.instanceId) {
              this.selectedOrchestrator = null;
            }
            this.loadOrchestrators();
          },
          error: (err) => {
            this.error = 'Failed to delete orchestrator: ' + (err.error?.message || err.message);
          }
        });
    }
  }

  createSnapshot(orchestrator: OrchestratorInstance): void {
    this.orchestratorService.createSnapshot(orchestrator.instanceId)
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: () => {
          this.error = null;
          alert('Snapshot created successfully');
        },
        error: (err) => {
          this.error = 'Failed to create snapshot: ' + (err.error?.message || err.message);
        }
      });
  }

  recoverOrchestrator(orchestrator: OrchestratorInstance): void {
    this.orchestratorService.recoverOrchestrator(orchestrator.instanceId)
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: () => {
          this.loadOrchestrators();
          alert('Recovery initiated');
        },
        error: (err) => {
          this.error = 'Failed to recover orchestrator: ' + (err.error?.message || err.message);
        }
      });
  }

  createOrchestrator(): void {
    if (!this.newOrchestratorName.trim()) {
      this.error = 'Orchestrator name is required';
      return;
    }

    this.creating = true;
    const request: CreateOrchestratorRequest = {
      name: this.newOrchestratorName.trim(),
      description: this.newOrchestratorDescription.trim() || undefined
    };

    this.orchestratorService.createOrchestrator(request)
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: (created) => {
          this.creating = false;
          this.newOrchestratorName = '';
          this.newOrchestratorDescription = '';
          this.loadOrchestrators();
          this.activeSubTab = 'instances';
          this.selectOrchestrator(created);
        },
        error: (err) => {
          this.creating = false;
          this.error = 'Failed to create orchestrator: ' + (err.error?.message || err.message);
        }
      });
  }

  // ==================== Task Operations ====================

  loadTasks(): void {
    if (!this.selectedOrchestrator) return;

    this.loadingTasks = true;
    const instanceId = this.selectedOrchestrator.instanceId;

    this.orchestratorService.getTaskHistory(instanceId)
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: (data) => {
          this.tasks = data;
          this.loadingTasks = false;
        },
        error: (err) => {
          this.loadingTasks = false;
          this.error = 'Failed to load tasks: ' + (err.error?.message || err.message);
        }
      });

    this.orchestratorService.getRunningTasks(instanceId)
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: (data) => {
          this.runningTasks = data;
        },
        error: () => {}
      });
  }

  executeCommand(): void {
    if (!this.selectedOrchestrator || !this.commandToExecute.trim()) return;

    this.executingCommand = true;
    this.orchestratorService.executeCommand(this.selectedOrchestrator.instanceId, { command: this.commandToExecute.trim() })
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: () => {
          this.executingCommand = false;
          this.commandToExecute = '';
          this.loadTasks();
        },
        error: (err) => {
          this.executingCommand = false;
          this.error = 'Failed to execute command: ' + (err.error?.message || err.message);
        }
      });
  }

  cancelTask(task: TaskInstance): void {
    if (!this.selectedOrchestrator) return;

    this.orchestratorService.cancelTask(this.selectedOrchestrator.instanceId, task.id)
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: () => this.loadTasks(),
        error: (err) => {
          this.error = 'Failed to cancel task: ' + (err.error?.message || err.message);
        }
      });
  }

  // ==================== Workflow Operations ====================

  loadWorkflows(): void {
    if (!this.selectedOrchestrator) return;

    this.loadingWorkflows = true;
    const instanceId = this.selectedOrchestrator.instanceId;

    this.orchestratorService.getAllWorkflows(instanceId)
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: (data) => {
          this.workflows = data;
          this.loadingWorkflows = false;
        },
        error: (err) => {
          this.loadingWorkflows = false;
          this.error = 'Failed to load workflows: ' + (err.error?.message || err.message);
        }
      });

    this.orchestratorService.getActiveWorkflows(instanceId)
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: (data) => {
          this.activeWorkflows = data;
        },
        error: () => {}
      });
  }

  selectWorkflow(workflow: Workflow): void {
    this.selectedWorkflow = workflow;
    this.loadWorkflowSteps();
  }

  loadWorkflowSteps(): void {
    if (!this.selectedOrchestrator || !this.selectedWorkflow) return;

    this.orchestratorService.getWorkflowSteps(this.selectedOrchestrator.instanceId, this.selectedWorkflow.id)
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: (data) => {
          this.workflowSteps = data;
        },
        error: (err) => {
          this.error = 'Failed to load workflow steps: ' + (err.error?.message || err.message);
        }
      });
  }

  startWorkflow(): void {
    if (!this.selectedOrchestrator || !this.newWorkflowName.trim() || !this.newWorkflowPrompt.trim()) {
      this.error = 'Workflow name and prompt are required';
      return;
    }

    this.creatingWorkflow = true;
    const request: StartWorkflowRequest = {
      name: this.newWorkflowName.trim(),
      initialPrompt: this.newWorkflowPrompt.trim(),
      autoAdvance: this.newWorkflowAutoAdvance,
      maxSteps: this.newWorkflowMaxSteps
    };

    this.orchestratorService.startWorkflow(this.selectedOrchestrator.instanceId, request)
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: (workflow) => {
          this.creatingWorkflow = false;
          this.newWorkflowName = '';
          this.newWorkflowPrompt = '';
          this.newWorkflowAutoAdvance = false;
          this.newWorkflowMaxSteps = 10;
          this.loadWorkflows();
          this.selectWorkflow(workflow);
        },
        error: (err) => {
          this.creatingWorkflow = false;
          this.error = 'Failed to start workflow: ' + (err.error?.message || err.message);
        }
      });
  }

  advanceWorkflow(workflow: Workflow): void {
    if (!this.selectedOrchestrator) return;

    this.orchestratorService.advanceWorkflow(this.selectedOrchestrator.instanceId, workflow.id)
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: () => {
          this.loadWorkflows();
          if (this.selectedWorkflow?.id === workflow.id) {
            this.loadWorkflowSteps();
          }
        },
        error: (err) => {
          this.error = 'Failed to advance workflow: ' + (err.error?.message || err.message);
        }
      });
  }

  approveStep(step: WorkflowStep): void {
    if (!this.selectedOrchestrator || !this.selectedWorkflow) return;

    this.orchestratorService.approveWorkflowStep(this.selectedOrchestrator.instanceId, this.selectedWorkflow.id, step.stepNumber)
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: () => {
          this.loadWorkflowSteps();
          this.loadWorkflows();
        },
        error: (err) => {
          this.error = 'Failed to approve step: ' + (err.error?.message || err.message);
        }
      });
  }

  rejectStep(step: WorkflowStep): void {
    if (!this.selectedOrchestrator || !this.selectedWorkflow) return;

    const feedback = prompt('Enter feedback for rejection:');
    if (feedback === null) return;

    this.orchestratorService.rejectWorkflowStep(this.selectedOrchestrator.instanceId, this.selectedWorkflow.id, step.stepNumber, feedback)
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: () => {
          this.loadWorkflowSteps();
          this.loadWorkflows();
        },
        error: (err) => {
          this.error = 'Failed to reject step: ' + (err.error?.message || err.message);
        }
      });
  }

  cancelWorkflow(workflow: Workflow): void {
    if (!this.selectedOrchestrator) return;

    if (confirm(`Are you sure you want to cancel workflow "${workflow.name}"?`)) {
      this.orchestratorService.cancelWorkflow(this.selectedOrchestrator.instanceId, workflow.id)
        .pipe(takeUntil(this.destroy$))
        .subscribe({
          next: () => {
            this.loadWorkflows();
            if (this.selectedWorkflow?.id === workflow.id) {
              this.selectedWorkflow = null;
              this.workflowSteps = [];
            }
          },
          error: (err) => {
            this.error = 'Failed to cancel workflow: ' + (err.error?.message || err.message);
          }
        });
    }
  }

  // ==================== Utilities ====================

  getStatusClass(status: string): string {
    switch (status) {
      case 'RUNNING':
      case 'IN_PROGRESS':
      case 'EXECUTED':
        return 'status-running';
      case 'COMPLETED':
      case 'APPROVED':
        return 'status-completed';
      case 'PAUSED':
      case 'AWAITING_APPROVAL':
      case 'PROPOSED':
      case 'PENDING':
        return 'status-paused';
      case 'STOPPED':
      case 'CANCELLED':
      case 'REJECTED':
        return 'status-stopped';
      case 'ERROR':
      case 'FAILED':
        return 'status-error';
      default:
        return 'status-default';
    }
  }

  clearError(): void {
    this.error = null;
  }
}
