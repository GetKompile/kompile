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
import { MatDialog } from '@angular/material/dialog';
import { Subject, interval, forkJoin } from 'rxjs';
import { takeUntil, switchMap, startWith, filter } from 'rxjs/operators';
import { ConfirmDialogComponent, ConfirmDialogData } from '../confirm-dialog/confirm-dialog.component';
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
import { TaskDefinition } from '../../models/orchestrator-models';
import { AgentService } from '../../services/agent.service';
import { FactSheetService } from '../../services/fact-sheet.service';
import { AgentProvider, FactSheet } from '../../models/api-models';

// Workflow step configuration for the builder
interface WorkflowStepConfig {
  id: string;
  name: string;
  type: 'prompt' | 'tool' | 'condition' | 'parallel';
  prompt?: string;
  agentName?: string;
  factSheetId?: number | null;
  toolName?: string;
  toolArgs?: string;
  condition?: string;
  onSuccess?: string;  // next step id
  onFailure?: string;  // next step id
  parallelSteps?: string[];  // step ids to run in parallel
  retryCount?: number;
  timeoutSeconds?: number;
}

@Component({
  standalone: false,
  selector: 'app-orchestrator-hub',
  templateUrl: './orchestrator-hub.component.html',
  styleUrls: ['./orchestrator-hub.component.css']
})
export class OrchestratorHubComponent implements OnInit, OnDestroy {
  // Main tab navigation (top level)
  activeSubTab: 'instances' | 'orchestrator-detail' | 'react-agent' | 'create' = 'instances';

  // Orchestrator detail sub-tab navigation (when viewing a specific orchestrator)
  orchestratorDetailTab: 'overview' | 'tasks' | 'definitions' | 'workflows' | 'state-machine' | 'classifiers' | 'audit' = 'overview';

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

  // Create form - Basic
  newOrchestratorName = '';
  newOrchestratorDescription = '';
  creating = false;

  // Create form - Agent/LLM Configuration
  availableAgents: AgentProvider[] = [];
  selectedAgentName = '';
  systemPrompt = '';
  defaultPromptTemplate = '';

  // Create form - Fact Sheet / RAG Configuration
  factSheets: FactSheet[] = [];
  selectedFactSheetId: number | null = null;
  enableRag = false;
  ragMaxResults = 5;
  ragSimilarityThreshold = 0.7;

  // Create form - Tool Configuration
  enableTools = false;
  availableTools: string[] = ['file-read', 'file-write', 'shell-execute', 'web-search', 'code-interpreter'];
  selectedTools: string[] = [];

  // Workflow Builder
  workflowBuilderMode = false;
  workflowStepsBuilder: WorkflowStepConfig[] = [];
  editingStepIndex: number | null = null;

  // Workflow form
  newWorkflowName = '';
  newWorkflowPrompt = '';
  newWorkflowAutoAdvance = false;
  newWorkflowMaxSteps = 10;
  creatingWorkflow = false;

  // Command execution
  commandToExecute = '';
  executingCommand = false;

  // UI state for expandable sections
  expandedTaskOutputs: Set<number> = new Set();
  expandedStepAnalysis: Set<number> = new Set();
  expandedStepOutputs: Set<number> = new Set();

  // Selected LLM session for viewing
  selectedLlmSession: any = null;

  // Task definition editor
  selectedTaskDefinition: TaskDefinition | null = null;

  private destroy$ = new Subject<void>();

  constructor(
    private orchestratorService: OrchestratorService,
    private agentService: AgentService,
    private factSheetService: FactSheetService,
    private dialog: MatDialog
  ) {}

  ngOnInit(): void {
    this.loadOrchestrators();
    this.loadAgentsAndFactSheets();
    // Auto-refresh every 5 seconds
    interval(5000)
      .pipe(
        takeUntil(this.destroy$),
        startWith(0)
      )
      .subscribe(() => {
        if (this.activeSubTab === 'instances') {
          this.loadOrchestrators();
        } else if (this.activeSubTab === 'orchestrator-detail' && this.selectedOrchestrator) {
          // Refresh based on which orchestrator sub-tab is active
          if (this.orchestratorDetailTab === 'overview' || this.orchestratorDetailTab === 'tasks') {
            this.loadTasks();
          }
          if (this.orchestratorDetailTab === 'overview' || this.orchestratorDetailTab === 'workflows') {
            this.loadWorkflows();
          }
        }
      });
  }

  ngOnDestroy(): void {
    this.destroy$.next();
    this.destroy$.complete();
  }

  // ==================== Load Agents & Fact Sheets ====================

  loadAgentsAndFactSheets(): void {
    // Load available agents
    this.agentService.getAllAgents()
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: (agents) => {
          this.availableAgents = agents;
          // Auto-select default agent
          const defaultAgent = agents.find(a => a.isDefault && a.available) || agents.find(a => a.available);
          if (defaultAgent && !this.selectedAgentName) {
            this.selectedAgentName = defaultAgent.name;
          }
        },
        error: (err) => console.error('Failed to load agents:', err)
      });

    // Load fact sheets
    this.factSheetService.loadSheets()
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: (sheets) => {
          this.factSheets = sheets;
        },
        error: (err) => console.error('Failed to load fact sheets:', err)
      });
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

  selectAndOpenOrchestrator(orchestrator: OrchestratorInstance): void {
    this.selectedOrchestrator = orchestrator;
    this.activeSubTab = 'orchestrator-detail';
    this.orchestratorDetailTab = 'overview';
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
    const dialogData: ConfirmDialogData = {
      title: 'Delete Orchestrator',
      message: `Are you sure you want to delete orchestrator "${orchestrator.name}"?`,
      confirmText: 'Delete',
      confirmColor: 'warn',
      icon: 'delete'
    };

    this.dialog.open(ConfirmDialogComponent, { data: dialogData })
      .afterClosed()
      .pipe(
        filter(confirmed => confirmed === true),
        takeUntil(this.destroy$)
      )
      .subscribe(() => {
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
      });
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

    if (!this.selectedAgentName) {
      this.error = 'Please select an agent/chatbot';
      return;
    }

    this.creating = true;
    const request: CreateOrchestratorRequest = {
      name: this.newOrchestratorName.trim(),
      description: this.newOrchestratorDescription.trim() || undefined,
      // Agent configuration
      agentName: this.selectedAgentName,
      systemPrompt: this.systemPrompt.trim() || undefined,
      defaultPromptTemplate: this.defaultPromptTemplate.trim() || undefined,
      // RAG configuration
      enableRag: this.enableRag,
      ragFolderId: this.selectedFactSheetId?.toString() || undefined,
      ragMaxResults: this.ragMaxResults,
      ragSimilarityThreshold: this.ragSimilarityThreshold,
      // Tool configuration
      enableTools: this.enableTools,
      allowedTools: this.selectedTools.length > 0 ? this.selectedTools.join(',') : undefined
    };

    this.orchestratorService.createOrchestrator(request)
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: (created) => {
          this.creating = false;
          this.resetCreateForm();
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

  resetCreateForm(): void {
    this.newOrchestratorName = '';
    this.newOrchestratorDescription = '';
    this.systemPrompt = '';
    this.defaultPromptTemplate = '';
    this.enableRag = false;
    this.selectedFactSheetId = null;
    this.ragMaxResults = 5;
    this.ragSimilarityThreshold = 0.7;
    this.enableTools = false;
    this.selectedTools = [];
  }

  toggleTool(tool: string): void {
    const index = this.selectedTools.indexOf(tool);
    if (index >= 0) {
      this.selectedTools.splice(index, 1);
    } else {
      this.selectedTools.push(tool);
    }
  }

  isToolSelected(tool: string): boolean {
    return this.selectedTools.includes(tool);
  }

  getSelectedFactSheetName(): string {
    if (!this.selectedFactSheetId) return 'None selected';
    const sheet = this.factSheets.find(s => s.id === this.selectedFactSheetId);
    return sheet?.name || 'Unknown';
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

    const dialogData: ConfirmDialogData = {
      title: 'Cancel Workflow',
      message: `Are you sure you want to cancel workflow "${workflow.name}"?`,
      confirmText: 'Cancel Workflow',
      confirmColor: 'warn',
      icon: 'cancel'
    };

    this.dialog.open(ConfirmDialogComponent, { data: dialogData })
      .afterClosed()
      .pipe(
        filter(confirmed => confirmed === true),
        takeUntil(this.destroy$)
      )
      .subscribe(() => {
        this.orchestratorService.cancelWorkflow(this.selectedOrchestrator!.instanceId, workflow.id)
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
      });
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

  // ==================== Task Output/Log Helpers ====================

  toggleTaskOutput(taskId: number): void {
    if (this.expandedTaskOutputs.has(taskId)) {
      this.expandedTaskOutputs.delete(taskId);
    } else {
      this.expandedTaskOutputs.add(taskId);
    }
  }

  toggleStepAnalysis(stepId: number): void {
    if (this.expandedStepAnalysis.has(stepId)) {
      this.expandedStepAnalysis.delete(stepId);
    } else {
      this.expandedStepAnalysis.add(stepId);
    }
  }

  toggleStepOutput(stepId: number): void {
    if (this.expandedStepOutputs.has(stepId)) {
      this.expandedStepOutputs.delete(stepId);
    } else {
      this.expandedStepOutputs.add(stepId);
    }
  }

  viewLlmSession(sessionId: number): void {
    if (!this.selectedOrchestrator) return;
    this.orchestratorService.getLlmSession(this.selectedOrchestrator.instanceId, sessionId)
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: (session) => {
          this.selectedLlmSession = session;
          // Could open a dialog or switch to a session view
          alert(`LLM Session #${sessionId}\n\nPrompt: ${session.initialPrompt}\n\nOutput: ${session.output || 'No output yet'}`);
        },
        error: (err) => {
          this.error = 'Failed to load LLM session: ' + (err.error?.message || err.message);
        }
      });
  }

  viewTaskDetails(taskId: number): void {
    if (!this.selectedOrchestrator) return;
    this.orchestratorService.getTask(this.selectedOrchestrator.instanceId, taskId)
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: (task) => {
          // Expand the task output in the list
          this.expandedTaskOutputs.add(taskId);
          // Switch to tasks tab if not already there
          this.activeSubTab = 'orchestrator-detail';
          this.orchestratorDetailTab = 'tasks';
        },
        error: (err) => {
          this.error = 'Failed to load task: ' + (err.error?.message || err.message);
        }
      });
  }

  // ==================== Error Parsing ====================

  parseErrorPatterns(errorMessage: string): { type: string; message: string }[] | null {
    if (!errorMessage) return null;

    const errors: { type: string; message: string }[] = [];

    // Common error patterns
    const patterns = [
      { regex: /(?:error|Error|ERROR):\s*(.+?)(?:\n|$)/g, type: 'Error' },
      { regex: /(?:exception|Exception|EXCEPTION):\s*(.+?)(?:\n|$)/gi, type: 'Exception' },
      { regex: /(?:failed|Failed|FAILED):\s*(.+?)(?:\n|$)/gi, type: 'Failure' },
      { regex: /command not found:\s*(.+?)(?:\n|$)/gi, type: 'Command Not Found' },
      { regex: /permission denied/gi, type: 'Permission Denied' },
      { regex: /No such file or directory:\s*(.+?)(?:\n|$)/gi, type: 'File Not Found' },
      { regex: /(?:timeout|Timeout|TIMEOUT)/gi, type: 'Timeout' },
      { regex: /(?:connection refused|Connection refused)/gi, type: 'Connection Error' },
      { regex: /exit code\s*(\d+)/gi, type: 'Exit Code' },
      { regex: /(?:null pointer|NullPointerException)/gi, type: 'Null Pointer' },
      { regex: /(?:out of memory|OutOfMemoryError)/gi, type: 'Out of Memory' },
      { regex: /(?:syntax error|SyntaxError)/gi, type: 'Syntax Error' },
      { regex: /(?:compilation failed|compile error)/gi, type: 'Compilation Error' },
    ];

    for (const pattern of patterns) {
      const matches = errorMessage.matchAll(pattern.regex);
      for (const match of matches) {
        errors.push({
          type: pattern.type,
          message: match[1] || match[0]
        });
      }
    }

    // Remove duplicates
    const seen = new Set<string>();
    const uniqueErrors = errors.filter(e => {
      const key = `${e.type}:${e.message}`;
      if (seen.has(key)) return false;
      seen.add(key);
      return true;
    });

    return uniqueErrors.length > 0 ? uniqueErrors : null;
  }

  // ==================== Task Definition Handlers ====================

  onTaskDefinitionSaved(taskDefinition: TaskDefinition): void {
    this.selectedTaskDefinition = null;
    // Could show a success message or refresh task list
  }

  onTaskDefinitionCancelled(): void {
    this.selectedTaskDefinition = null;
  }

  editTaskDefinition(taskDefinition: TaskDefinition): void {
    this.selectedTaskDefinition = taskDefinition;
    this.activeSubTab = 'orchestrator-detail';
    this.orchestratorDetailTab = 'definitions';
  }

  // ==================== Workflow Builder Methods ====================

  addWorkflowStep(type: 'prompt' | 'tool' | 'condition' | 'parallel'): void {
    const newStep: WorkflowStepConfig = {
      id: this.generateStepId(),
      name: '',
      type: type,
      retryCount: 0,
      timeoutSeconds: 300
    };

    // Set defaults based on type
    if (type === 'prompt') {
      newStep.prompt = '';
      newStep.agentName = '';
      newStep.factSheetId = null;
    } else if (type === 'tool') {
      newStep.toolName = '';
      newStep.toolArgs = '';
    } else if (type === 'condition') {
      newStep.condition = '';
      newStep.onSuccess = '';
      newStep.onFailure = '';
    } else if (type === 'parallel') {
      newStep.parallelSteps = [];
    }

    this.workflowStepsBuilder.push(newStep);
    this.editingStepIndex = this.workflowStepsBuilder.length - 1;
  }

  removeWorkflowStep(index: number): void {
    if (index >= 0 && index < this.workflowStepsBuilder.length) {
      const removedId = this.workflowStepsBuilder[index].id;
      this.workflowStepsBuilder.splice(index, 1);

      // Clean up references to removed step
      this.workflowStepsBuilder.forEach(step => {
        if (step.onSuccess === removedId) step.onSuccess = '';
        if (step.onFailure === removedId) step.onFailure = '';
        if (step.parallelSteps) {
          step.parallelSteps = step.parallelSteps.filter(id => id !== removedId);
        }
      });

      if (this.editingStepIndex === index) {
        this.editingStepIndex = null;
      } else if (this.editingStepIndex !== null && this.editingStepIndex > index) {
        this.editingStepIndex--;
      }
    }
  }

  moveStepUp(index: number): void {
    if (index > 0) {
      const temp = this.workflowStepsBuilder[index];
      this.workflowStepsBuilder[index] = this.workflowStepsBuilder[index - 1];
      this.workflowStepsBuilder[index - 1] = temp;

      if (this.editingStepIndex === index) {
        this.editingStepIndex = index - 1;
      } else if (this.editingStepIndex === index - 1) {
        this.editingStepIndex = index;
      }
    }
  }

  moveStepDown(index: number): void {
    if (index < this.workflowStepsBuilder.length - 1) {
      const temp = this.workflowStepsBuilder[index];
      this.workflowStepsBuilder[index] = this.workflowStepsBuilder[index + 1];
      this.workflowStepsBuilder[index + 1] = temp;

      if (this.editingStepIndex === index) {
        this.editingStepIndex = index + 1;
      } else if (this.editingStepIndex === index + 1) {
        this.editingStepIndex = index;
      }
    }
  }

  toggleParallelStep(step: WorkflowStepConfig, stepId: string): void {
    if (!step.parallelSteps) {
      step.parallelSteps = [];
    }

    const index = step.parallelSteps.indexOf(stepId);
    if (index >= 0) {
      step.parallelSteps.splice(index, 1);
    } else {
      step.parallelSteps.push(stepId);
    }
  }

  clearWorkflowBuilder(): void {
    this.workflowStepsBuilder = [];
    this.editingStepIndex = null;
    this.newWorkflowName = '';
  }

  startWorkflowFromBuilder(): void {
    if (!this.selectedOrchestrator || !this.newWorkflowName.trim() || this.workflowStepsBuilder.length === 0) {
      this.error = 'Workflow name and at least one step are required';
      return;
    }

    // Build initial prompt from all prompt steps
    const promptSteps = this.workflowStepsBuilder.filter(s => s.type === 'prompt' && s.prompt);
    const initialPrompt = promptSteps.length > 0
      ? promptSteps[0].prompt || ''
      : 'Execute workflow steps as defined';

    this.creatingWorkflow = true;
    const request: StartWorkflowRequest = {
      name: this.newWorkflowName.trim(),
      initialPrompt: initialPrompt,
      autoAdvance: this.newWorkflowAutoAdvance,
      maxSteps: this.newWorkflowMaxSteps,
      // Pass step configurations
      steps: this.workflowStepsBuilder.map(step => ({
        stepId: step.id,
        name: step.name,
        type: step.type,
        prompt: step.prompt,
        agentName: step.agentName,
        factSheetId: step.factSheetId,
        toolName: step.toolName,
        toolArgs: step.toolArgs,
        condition: step.condition,
        onSuccess: step.onSuccess,
        onFailure: step.onFailure,
        parallelSteps: step.parallelSteps,
        retryCount: step.retryCount,
        timeoutSeconds: step.timeoutSeconds
      }))
    };

    this.orchestratorService.startWorkflow(this.selectedOrchestrator.instanceId, request)
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: (workflow) => {
          this.creatingWorkflow = false;
          this.clearWorkflowBuilder();
          this.loadWorkflows();
          this.selectWorkflow(workflow);
          this.workflowBuilderMode = false;
        },
        error: (err) => {
          this.creatingWorkflow = false;
          this.error = 'Failed to start workflow: ' + (err.error?.message || err.message);
        }
      });
  }

  private generateStepId(): string {
    return 'step-' + Date.now() + '-' + Math.random().toString(36).substring(2, 9);
  }
}
