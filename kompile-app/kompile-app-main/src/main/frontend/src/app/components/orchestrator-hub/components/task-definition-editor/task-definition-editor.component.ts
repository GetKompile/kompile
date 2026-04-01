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

import { Component, OnInit, OnDestroy, Input, Output, EventEmitter } from '@angular/core';
import { FormBuilder, FormGroup, FormArray, Validators } from '@angular/forms';
import { Subject } from 'rxjs';
import { takeUntil } from 'rxjs/operators';
import {
  OrchestratorService,
  EnhancedTaskDefinition
} from '../../../../services/orchestrator.service';
import {
  TaskType,
  TaskAction,
  OutputClassifier,
  AgentInfo,
  StateDefinition
} from '../../../../models/orchestrator-models';

@Component({
  standalone: false,
  selector: 'app-task-definition-editor',
  templateUrl: './task-definition-editor.component.html',
  styleUrls: ['./task-definition-editor.component.scss']
})
export class TaskDefinitionEditorComponent implements OnInit, OnDestroy {
  @Input() instanceId: string = '';
  @Input() taskDefinition: EnhancedTaskDefinition | null = null;
  @Output() saved = new EventEmitter<EnhancedTaskDefinition>();
  @Output() cancelled = new EventEmitter<void>();

  taskForm!: FormGroup;
  loading = false;
  saving = false;
  error: string | null = null;

  // Options for dropdowns
  taskTypes: TaskType[] = ['SHELL', 'HTTP', 'CODE', 'LLM_QUERY', 'CUSTOM'];
  httpMethods = ['GET', 'POST', 'PUT', 'DELETE', 'PATCH'];
  agents: AgentInfo[] = [];
  classifiers: OutputClassifier[] = [];
  ragFolders: string[] = [];
  availableTools: string[] = [];

  // Available states and tasks for action configuration
  availableStates: StateDefinition[] = [];
  availableTasks: Array<{taskId: string; name: string}> = [];

  // Action types for reference
  taskActions: TaskAction[] = [
    'CONTINUE', 'RETRY', 'RETRY_WITH_BACKOFF', 'INVOKE_LLM', 'INVOKE_LLM_FOR_FIX',
    'TRANSITION_STATE', 'EXECUTE_TASK', 'NOTIFY', 'LOG', 'SKIP', 'ABORT',
    'AWAIT_APPROVAL', 'ESCALATE', 'PARSE_LOG', 'WAIT_FOR_PROCESS', 'SEND_TO_AGENT', 'CUSTOM'
  ];

  // UI state
  activePanel: 'basic' | 'agent' | 'rag' | 'tools' | 'patterns' | 'actions' | 'http' | 'variables' | 'retry' = 'basic';
  showVariableEditor = false;
  testPatternInput = '';
  testPatternResult: any = null;

  private destroy$ = new Subject<void>();

  constructor(
    private fb: FormBuilder,
    private orchestratorService: OrchestratorService
  ) {}

  ngOnInit(): void {
    this.initForm();
    this.loadOptions();
    if (this.taskDefinition) {
      this.populateForm(this.taskDefinition);
    }
  }

  ngOnDestroy(): void {
    this.destroy$.next();
    this.destroy$.complete();
  }

  private initForm(): void {
    this.taskForm = this.fb.group({
      // Basic info
      taskId: ['', [Validators.required, Validators.pattern(/^[a-zA-Z0-9_-]+$/)]],
      name: ['', Validators.required],
      description: [''],
      taskType: ['SHELL', Validators.required],
      command: [''],
      workingDirectory: [''],
      timeoutSeconds: [300, [Validators.min(1), Validators.max(86400)]],

      // LLM/Agent Configuration
      promptTemplate: [''],
      autoInvokeLlmOnError: [false],
      llmErrorPromptTemplate: [''],
      agentName: [''],
      systemPrompt: [''],

      // RAG Configuration
      enableRag: [false],
      ragFolderId: [''],
      ragMaxResults: [5, [Validators.min(1), Validators.max(100)]],
      ragSimilarityThreshold: [0.7, [Validators.min(0), Validators.max(1)]],
      ragIncludeKeywordSearch: [true],
      ragIncludeSemanticSearch: [true],

      // Tool Configuration
      enableTools: [false],
      allowedTools: [''],
      skipPermissions: [false],

      // Output Classification
      outputClassifierId: [null],

      // Output Patterns
      successPattern: [''],
      failurePattern: [''],

      // Actions - Pass/Fail Triggers
      onSuccessAction: [''],
      onSuccessStateId: [''],
      onSuccessTaskId: [''],
      onSuccessLlmPrompt: [''],

      onFailureAction: [''],
      onFailureStateId: [''],
      onFailureTaskId: [''],
      onFailureLlmPrompt: [''],

      // HTTP Task
      httpUrl: [''],
      httpMethod: ['GET'],
      httpHeadersJson: ['{}'],
      httpBodyTemplate: [''],

      // Custom Executor
      executorClassName: [''],

      // Retry Configuration
      retryCount: [0, [Validators.min(0), Validators.max(10)]],
      retryDelaySeconds: [5, [Validators.min(1), Validators.max(3600)]],

      // Variables
      requiredVariables: this.fb.array([]),
      defaultVariables: this.fb.group({})
    });

    // Watch for task type changes
    this.taskForm.get('taskType')?.valueChanges
      .pipe(takeUntil(this.destroy$))
      .subscribe(type => {
        this.onTaskTypeChange(type);
      });

    // Watch for agent selection changes
    this.taskForm.get('agentName')?.valueChanges
      .pipe(takeUntil(this.destroy$))
      .subscribe(agentName => {
        if (agentName && this.instanceId) {
          this.loadAgentTools(agentName);
        }
      });
  }

  private loadOptions(): void {
    if (!this.instanceId) return;

    this.loading = true;

    // Load available agents
    this.orchestratorService.getAvailableAgents(this.instanceId)
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: (agents) => {
          this.agents = agents;
        },
        error: () => {
          // Agents endpoint may not exist yet
          this.agents = [];
        }
      });

    // Load output classifiers
    this.orchestratorService.getOutputClassifiers(this.instanceId)
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: (classifiers) => {
          this.classifiers = classifiers;
        },
        error: () => {
          this.classifiers = [];
        }
      });

    // Load RAG folders
    this.orchestratorService.getRagFolders(this.instanceId)
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: (folders) => {
          this.ragFolders = folders;
          this.loading = false;
        },
        error: () => {
          this.ragFolders = [];
          this.loading = false;
        }
      });

    // Load available states for state transitions
    this.orchestratorService.getStates(this.instanceId)
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: (states) => {
          this.availableStates = states;
        },
        error: () => {
          this.availableStates = [];
        }
      });

    // Load available tasks for chaining
    this.orchestratorService.getTaskDefinitions(this.instanceId)
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: (tasks) => {
          this.availableTasks = tasks;
        },
        error: () => {
          this.availableTasks = [];
        }
      });
  }

  private loadAgentTools(agentName: string): void {
    this.orchestratorService.getAgentTools(this.instanceId, agentName)
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: (tools) => {
          this.availableTools = tools;
        },
        error: () => {
          this.availableTools = [];
        }
      });
  }

  private populateForm(task: EnhancedTaskDefinition): void {
    this.taskForm.patchValue({
      taskId: task.taskId,
      name: task.name,
      description: task.description || '',
      taskType: task.taskType || 'SHELL',
      command: task.command || '',
      workingDirectory: task.workingDirectory || '',
      timeoutSeconds: task.timeoutSeconds || 300,
      promptTemplate: task.promptTemplate || '',
      autoInvokeLlmOnError: task.autoInvokeLlmOnError || false,
      llmErrorPromptTemplate: task.llmErrorPromptTemplate || '',
      agentName: task.agentName || '',
      systemPrompt: task.systemPrompt || '',
      enableRag: task.enableRag || false,
      ragFolderId: task.ragFolderId || '',
      ragMaxResults: task.ragMaxResults || 5,
      ragSimilarityThreshold: task.ragSimilarityThreshold || 0.7,
      ragIncludeKeywordSearch: task.ragIncludeKeywordSearch !== false,
      ragIncludeSemanticSearch: task.ragIncludeSemanticSearch !== false,
      enableTools: task.enableTools || false,
      allowedTools: task.allowedTools || '',
      skipPermissions: task.skipPermissions || false,
      outputClassifierId: task.outputClassifierId || null,
      successPattern: task.successPattern || '',
      failurePattern: task.failurePattern || '',
      // Action configuration
      onSuccessAction: task.onSuccessAction || '',
      onSuccessStateId: task.onSuccessStateId || '',
      onSuccessTaskId: task.onSuccessTaskId || '',
      onSuccessLlmPrompt: task.onSuccessLlmPrompt || '',
      onFailureAction: task.onFailureAction || '',
      onFailureStateId: task.onFailureStateId || '',
      onFailureTaskId: task.onFailureTaskId || '',
      onFailureLlmPrompt: task.onFailureLlmPrompt || '',
      httpUrl: task.httpUrl || '',
      httpMethod: task.httpMethod || 'GET',
      httpHeadersJson: task.httpHeadersJson || '{}',
      httpBodyTemplate: task.httpBodyTemplate || '',
      executorClassName: task.executorClassName || '',
      retryCount: task.retryCount || 0,
      retryDelaySeconds: task.retryDelaySeconds || 5
    });

    // Populate required variables
    if (task.requiredVariables && task.requiredVariables.length > 0) {
      const requiredVarsArray = this.taskForm.get('requiredVariables') as FormArray;
      requiredVarsArray.clear();
      task.requiredVariables.forEach(v => {
        requiredVarsArray.push(this.fb.control(v));
      });
    }

    // Load agent tools if agent is selected
    if (task.agentName) {
      this.loadAgentTools(task.agentName);
    }
  }

  private onTaskTypeChange(type: TaskType): void {
    // Reset type-specific fields
    const httpFields = ['httpUrl', 'httpMethod', 'httpHeadersJson', 'httpBodyTemplate'];
    const shellFields = ['command'];
    const llmFields = ['promptTemplate', 'agentName', 'systemPrompt'];
    const customFields = ['executorClassName'];

    switch (type) {
      case 'HTTP':
        // Enable HTTP fields, disable others
        httpFields.forEach(f => this.taskForm.get(f)?.enable());
        shellFields.forEach(f => this.taskForm.get(f)?.disable());
        break;
      case 'LLM_QUERY':
        // Enable LLM fields
        llmFields.forEach(f => this.taskForm.get(f)?.enable());
        httpFields.forEach(f => this.taskForm.get(f)?.disable());
        break;
      case 'CUSTOM':
        customFields.forEach(f => this.taskForm.get(f)?.enable());
        break;
      default:
        // SHELL - enable command
        shellFields.forEach(f => this.taskForm.get(f)?.enable());
        httpFields.forEach(f => this.taskForm.get(f)?.disable());
    }
  }

  // Variable management
  get requiredVariables(): FormArray {
    return this.taskForm.get('requiredVariables') as FormArray;
  }

  addRequiredVariable(): void {
    const varName = prompt('Enter variable name:');
    if (varName && varName.trim()) {
      this.requiredVariables.push(this.fb.control(varName.trim()));
    }
  }

  removeRequiredVariable(index: number): void {
    this.requiredVariables.removeAt(index);
  }

  // Pattern testing
  testSuccessPattern(): void {
    this.testPattern(this.taskForm.get('successPattern')?.value);
  }

  testFailurePattern(): void {
    this.testPattern(this.taskForm.get('failurePattern')?.value);
  }

  private testPattern(pattern: string): void {
    if (!pattern || !this.testPatternInput) return;

    this.orchestratorService.testPattern(this.instanceId, pattern, this.testPatternInput)
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: (result) => {
          this.testPatternResult = result;
        },
        error: (err) => {
          this.testPatternResult = {
            valid: false,
            error: err.error?.message || err.message
          };
        }
      });
  }

  // Save
  save(): void {
    if (this.taskForm.invalid) {
      this.error = 'Please fix validation errors before saving';
      return;
    }

    this.saving = true;
    this.error = null;

    const formValue = this.taskForm.getRawValue();

    // Build the task definition object
    const taskDef: EnhancedTaskDefinition = {
      taskId: formValue.taskId,
      name: formValue.name,
      description: formValue.description || undefined,
      taskType: formValue.taskType,
      command: formValue.command || undefined,
      workingDirectory: formValue.workingDirectory || undefined,
      timeoutSeconds: formValue.timeoutSeconds,
      promptTemplate: formValue.promptTemplate || undefined,
      autoInvokeLlmOnError: formValue.autoInvokeLlmOnError,
      llmErrorPromptTemplate: formValue.llmErrorPromptTemplate || undefined,
      agentName: formValue.agentName || undefined,
      systemPrompt: formValue.systemPrompt || undefined,
      enableRag: formValue.enableRag,
      ragFolderId: formValue.ragFolderId || undefined,
      ragMaxResults: formValue.ragMaxResults,
      ragSimilarityThreshold: formValue.ragSimilarityThreshold,
      ragIncludeKeywordSearch: formValue.ragIncludeKeywordSearch,
      ragIncludeSemanticSearch: formValue.ragIncludeSemanticSearch,
      enableTools: formValue.enableTools,
      allowedTools: formValue.allowedTools || undefined,
      skipPermissions: formValue.skipPermissions,
      outputClassifierId: formValue.outputClassifierId || undefined,
      successPattern: formValue.successPattern || undefined,
      failurePattern: formValue.failurePattern || undefined,
      // Action configuration - Pass/Fail Triggers
      onSuccessAction: formValue.onSuccessAction || undefined,
      onSuccessStateId: formValue.onSuccessStateId || undefined,
      onSuccessTaskId: formValue.onSuccessTaskId || undefined,
      onSuccessLlmPrompt: formValue.onSuccessLlmPrompt || undefined,
      onFailureAction: formValue.onFailureAction || undefined,
      onFailureStateId: formValue.onFailureStateId || undefined,
      onFailureTaskId: formValue.onFailureTaskId || undefined,
      onFailureLlmPrompt: formValue.onFailureLlmPrompt || undefined,
      httpUrl: formValue.httpUrl || undefined,
      httpMethod: formValue.httpMethod || undefined,
      httpHeadersJson: formValue.httpHeadersJson || undefined,
      httpBodyTemplate: formValue.httpBodyTemplate || undefined,
      executorClassName: formValue.executorClassName || undefined,
      retryCount: formValue.retryCount,
      retryDelaySeconds: formValue.retryDelaySeconds,
      requiredVariables: formValue.requiredVariables && formValue.requiredVariables.length > 0
        ? formValue.requiredVariables
        : undefined
    };

    const isUpdate = !!this.taskDefinition;
    const request = isUpdate
      ? this.orchestratorService.updateEnhancedTaskDefinition(this.instanceId, taskDef.taskId, taskDef)
      : this.orchestratorService.createEnhancedTaskDefinition(this.instanceId, taskDef);

    request.pipe(takeUntil(this.destroy$))
      .subscribe({
        next: (saved) => {
          this.saving = false;
          this.saved.emit(saved);
        },
        error: (err) => {
          this.saving = false;
          this.error = 'Failed to save task definition: ' + (err.error?.message || err.message);
        }
      });
  }

  cancel(): void {
    this.cancelled.emit();
  }

  // Panel navigation
  setActivePanel(panel: typeof this.activePanel): void {
    this.activePanel = panel;
  }

  // Helpers for template
  getSelectedAgent(): AgentInfo | undefined {
    const agentName = this.taskForm.get('agentName')?.value;
    return this.agents.find(a => a.name === agentName);
  }

  isToolSelected(tool: string): boolean {
    const allowedTools = this.taskForm.get('allowedTools')?.value || '';
    return allowedTools.split(',').map((t: string) => t.trim()).includes(tool);
  }

  toggleTool(tool: string): void {
    const allowedTools = this.taskForm.get('allowedTools')?.value || '';
    const tools = allowedTools.split(',').map((t: string) => t.trim()).filter((t: string) => t);

    if (tools.includes(tool)) {
      const filtered = tools.filter((t: string) => t !== tool);
      this.taskForm.patchValue({ allowedTools: filtered.join(', ') });
    } else {
      tools.push(tool);
      this.taskForm.patchValue({ allowedTools: tools.join(', ') });
    }
  }

  clearError(): void {
    this.error = null;
  }
}
