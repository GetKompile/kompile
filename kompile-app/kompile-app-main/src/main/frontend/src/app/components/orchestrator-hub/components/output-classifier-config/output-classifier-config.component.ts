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

import { Component, OnInit, OnDestroy, Input } from '@angular/core';
import { FormBuilder, FormGroup, Validators } from '@angular/forms';
import { CdkDragDrop, moveItemInArray } from '@angular/cdk/drag-drop';
import { Subject } from 'rxjs';
import { takeUntil } from 'rxjs/operators';
import { OrchestratorService } from '../../../../services/orchestrator.service';
import {
  OutputClassifier,
  ClassificationRule,
  ClassificationResult,
  ClassificationType,
  ClassificationSeverity,
  ClassificationAction,
  PatternTestResult
} from '../../../../models/orchestrator-models';

@Component({
  standalone: false,
  selector: 'app-output-classifier-config',
  templateUrl: './output-classifier-config.component.html',
  styleUrls: ['./output-classifier-config.component.scss']
})
export class OutputClassifierConfigComponent implements OnInit, OnDestroy {
  @Input() instanceId: string = '';

  // Data
  classifiers: OutputClassifier[] = [];
  selectedClassifier: OutputClassifier | null = null;
  templates: ClassificationRule[] = [];

  // UI state
  loading = false;
  saving = false;
  error: string | null = null;
  editingClassifier = false;
  editingRule: ClassificationRule | null = null;

  // Forms
  classifierForm!: FormGroup;
  ruleForm!: FormGroup;

  // Pattern testing
  testPatternInput = '';
  testPatternResult: PatternTestResult | null = null;

  // Classify output testing
  classifyTestInput = '';
  classifyTestResult: ClassificationResult | null = null;
  classifyTesting = false;

  // Dropdown options
  classificationTypes: ClassificationType[] = [
    'COMPILATION_ERROR', 'LINKER_ERROR', 'RUNTIME_ERROR', 'MEMORY_ERROR',
    'SEGFAULT', 'TIMEOUT', 'BUILD_SUCCESS', 'TEST_SUCCESS', 'TEST_FAILURE',
    'PERMISSION_ERROR', 'NETWORK_ERROR', 'DEPENDENCY_ERROR', 'CONFIGURATION_ERROR',
    'RESOURCE_ERROR', 'WARNING', 'INFO', 'DEBUG', 'CUSTOM'
  ];

  severityLevels: ClassificationSeverity[] = ['CRITICAL', 'ERROR', 'WARNING', 'INFO'];

  actions: ClassificationAction[] = [
    'RETRY', 'RETRY_WITH_BACKOFF', 'INVOKE_LLM', 'INVOKE_LLM_FOR_FIX',
    'TRANSITION_STATE', 'EXECUTE_TASK', 'NOTIFY', 'LOG', 'SKIP',
    'ABORT', 'AWAIT_APPROVAL', 'ESCALATE', 'CUSTOM', 'CONTINUE'
  ];

  private destroy$ = new Subject<void>();

  constructor(
    private fb: FormBuilder,
    private orchestratorService: OrchestratorService
  ) {}

  ngOnInit(): void {
    this.initForms();
    this.loadClassifiers();
    this.loadTemplates();
  }

  ngOnDestroy(): void {
    this.destroy$.next();
    this.destroy$.complete();
  }

  private initForms(): void {
    this.classifierForm = this.fb.group({
      name: ['', Validators.required],
      description: [''],
      enabled: [true],
      applyAllMatches: [false],
      defaultAction: ['CONTINUE'],
      tags: ['']
    });

    this.ruleForm = this.fb.group({
      name: ['', Validators.required],
      description: [''],
      pattern: ['', Validators.required],
      caseSensitive: [false],
      multiline: [false],
      classificationType: ['CUSTOM', Validators.required],
      severity: ['INFO', Validators.required],
      action: ['LOG', Validators.required],
      actionConfig: [''],
      targetStateId: [''],
      handlerTaskId: [''],
      llmPromptTemplate: [''],
      maxRetries: [3],
      retryDelaySeconds: [5],
      enabled: [true],
      stopOnMatch: [false],
      tags: ['']
    });
  }

  loadClassifiers(): void {
    if (!this.instanceId) return;

    this.loading = true;
    this.orchestratorService.getOutputClassifiers(this.instanceId)
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: (classifiers) => {
          this.classifiers = classifiers;
          this.loading = false;
        },
        error: (err) => {
          this.error = 'Failed to load classifiers: ' + (err.error?.message || err.message);
          this.loading = false;
        }
      });
  }

  loadTemplates(): void {
    if (!this.instanceId) return;

    this.orchestratorService.getClassificationTemplates(this.instanceId)
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: (templates) => {
          this.templates = templates;
        },
        error: () => {
          // Templates are optional, so no error handling needed
          this.templates = this.getDefaultTemplates();
        }
      });
  }

  private getDefaultTemplates(): ClassificationRule[] {
    return [
      {
        id: 0,
        name: 'Compilation Error',
        pattern: '(?i)error:\\s*(.+?)(?:\\n|$)',
        classificationType: 'COMPILATION_ERROR',
        severity: 'ERROR',
        action: 'INVOKE_LLM_FOR_FIX'
      },
      {
        id: 0,
        name: 'Build Success',
        pattern: 'BUILD SUCCESS|\\[INFO\\] BUILD SUCCESS',
        classificationType: 'BUILD_SUCCESS',
        severity: 'INFO',
        action: 'CONTINUE'
      },
      {
        id: 0,
        name: 'Test Failure',
        pattern: '(?i)tests? failed|failure|FAILED',
        classificationType: 'TEST_FAILURE',
        severity: 'ERROR',
        action: 'INVOKE_LLM'
      },
      {
        id: 0,
        name: 'Permission Denied',
        pattern: '(?i)permission denied|access denied',
        classificationType: 'PERMISSION_ERROR',
        severity: 'CRITICAL',
        action: 'ABORT'
      },
      {
        id: 0,
        name: 'Timeout',
        pattern: '(?i)timeout|timed out|deadline exceeded',
        classificationType: 'TIMEOUT',
        severity: 'ERROR',
        action: 'RETRY_WITH_BACKOFF'
      }
    ];
  }

  // Classifier CRUD
  selectClassifier(classifier: OutputClassifier): void {
    this.selectedClassifier = classifier;
    this.editingRule = null;
  }

  newClassifier(): void {
    this.editingClassifier = true;
    this.selectedClassifier = null;
    this.classifierForm.reset({
      enabled: true,
      applyAllMatches: false,
      defaultAction: 'CONTINUE'
    });
  }

  editClassifier(classifier: OutputClassifier): void {
    this.editingClassifier = true;
    this.selectedClassifier = classifier;
    this.classifierForm.patchValue({
      name: classifier.name,
      description: classifier.description || '',
      enabled: classifier.enabled !== false,
      applyAllMatches: classifier.applyAllMatches || false,
      defaultAction: classifier.defaultAction || 'CONTINUE',
      tags: classifier.tags || ''
    });
  }

  saveClassifier(): void {
    if (this.classifierForm.invalid) return;

    this.saving = true;
    const formValue = this.classifierForm.value;

    const classifierData: OutputClassifier = {
      id: this.selectedClassifier?.id,
      orchestratorInstanceId: this.instanceId,
      name: formValue.name,
      description: formValue.description || undefined,
      enabled: formValue.enabled,
      applyAllMatches: formValue.applyAllMatches,
      defaultAction: formValue.defaultAction,
      tags: formValue.tags || undefined,
      rules: this.selectedClassifier?.rules || []
    };

    const request = this.selectedClassifier?.id
      ? this.orchestratorService.updateOutputClassifier(this.instanceId, this.selectedClassifier.id, classifierData)
      : this.orchestratorService.createOutputClassifier(this.instanceId, classifierData);

    request.pipe(takeUntil(this.destroy$))
      .subscribe({
        next: (saved) => {
          this.saving = false;
          this.editingClassifier = false;
          this.selectedClassifier = saved;
          this.loadClassifiers();
        },
        error: (err) => {
          this.saving = false;
          this.error = 'Failed to save classifier: ' + (err.error?.message || err.message);
        }
      });
  }

  deleteClassifier(classifier: OutputClassifier): void {
    if (!classifier.id) return;

    if (!confirm(`Delete classifier "${classifier.name}"? This cannot be undone.`)) {
      return;
    }

    this.orchestratorService.deleteOutputClassifier(this.instanceId, classifier.id)
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: () => {
          if (this.selectedClassifier?.id === classifier.id) {
            this.selectedClassifier = null;
          }
          this.loadClassifiers();
        },
        error: (err) => {
          this.error = 'Failed to delete classifier: ' + (err.error?.message || err.message);
        }
      });
  }

  cancelEditClassifier(): void {
    this.editingClassifier = false;
  }

  toggleClassifierEnabled(classifier: OutputClassifier): void {
    if (!classifier.id) return;

    const newEnabled = !classifier.enabled;
    this.orchestratorService.toggleClassifierEnabled(this.instanceId, classifier.id, newEnabled)
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: () => {
          classifier.enabled = newEnabled;
        },
        error: (err) => {
          this.error = 'Failed to toggle classifier: ' + (err.error?.message || err.message);
        }
      });
  }

  // Rule CRUD
  newRule(): void {
    if (!this.selectedClassifier) return;

    this.editingRule = { id: 0 } as ClassificationRule;
    this.ruleForm.reset({
      caseSensitive: false,
      multiline: false,
      classificationType: 'CUSTOM',
      severity: 'INFO',
      action: 'LOG',
      maxRetries: 3,
      retryDelaySeconds: 5,
      enabled: true,
      stopOnMatch: false
    });
  }

  editRule(rule: ClassificationRule): void {
    this.editingRule = rule;
    this.ruleForm.patchValue({
      name: rule.name,
      description: rule.description || '',
      pattern: rule.pattern,
      caseSensitive: rule.caseSensitive || false,
      multiline: rule.multiline || false,
      classificationType: rule.classificationType,
      severity: rule.severity,
      action: rule.action,
      actionConfig: rule.actionConfig || '',
      targetStateId: rule.targetStateId || '',
      handlerTaskId: rule.handlerTaskId || '',
      llmPromptTemplate: rule.llmPromptTemplate || '',
      maxRetries: rule.maxRetries || 3,
      retryDelaySeconds: rule.retryDelaySeconds || 5,
      enabled: rule.enabled !== false,
      stopOnMatch: rule.stopOnMatch || false,
      tags: rule.tags || ''
    });
  }

  saveRule(): void {
    if (this.ruleForm.invalid || !this.selectedClassifier?.id) return;

    this.saving = true;
    const formValue = this.ruleForm.value;

    const ruleData: ClassificationRule = {
      id: this.editingRule?.id,
      classifierId: this.selectedClassifier.id,
      name: formValue.name,
      description: formValue.description || undefined,
      pattern: formValue.pattern,
      caseSensitive: formValue.caseSensitive,
      multiline: formValue.multiline,
      classificationType: formValue.classificationType,
      severity: formValue.severity,
      action: formValue.action,
      actionConfig: formValue.actionConfig || undefined,
      targetStateId: formValue.targetStateId || undefined,
      handlerTaskId: formValue.handlerTaskId || undefined,
      llmPromptTemplate: formValue.llmPromptTemplate || undefined,
      maxRetries: formValue.maxRetries,
      retryDelaySeconds: formValue.retryDelaySeconds,
      enabled: formValue.enabled,
      stopOnMatch: formValue.stopOnMatch,
      tags: formValue.tags || undefined
    };

    const request = this.editingRule?.id
      ? this.orchestratorService.updateClassificationRule(
          this.instanceId, this.selectedClassifier.id, this.editingRule.id, ruleData
        )
      : this.orchestratorService.addClassificationRule(
          this.instanceId, this.selectedClassifier.id, ruleData
        );

    request.pipe(takeUntil(this.destroy$))
      .subscribe({
        next: () => {
          this.saving = false;
          this.editingRule = null;
          this.loadClassifiers();
          // Refresh selected classifier
          if (this.selectedClassifier?.id) {
            this.orchestratorService.getOutputClassifier(this.instanceId, this.selectedClassifier.id)
              .pipe(takeUntil(this.destroy$))
              .subscribe(c => this.selectedClassifier = c);
          }
        },
        error: (err) => {
          this.saving = false;
          this.error = 'Failed to save rule: ' + (err.error?.message || err.message);
        }
      });
  }

  deleteRule(rule: ClassificationRule): void {
    if (!this.selectedClassifier?.id || !rule.id) return;

    if (!confirm(`Delete rule "${rule.name}"?`)) {
      return;
    }

    this.orchestratorService.deleteClassificationRule(this.instanceId, this.selectedClassifier.id, rule.id)
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: () => {
          if (this.editingRule?.id === rule.id) {
            this.editingRule = null;
          }
          // Refresh selected classifier
          if (this.selectedClassifier?.id) {
            this.orchestratorService.getOutputClassifier(this.instanceId, this.selectedClassifier.id)
              .pipe(takeUntil(this.destroy$))
              .subscribe(c => this.selectedClassifier = c);
          }
        },
        error: (err) => {
          this.error = 'Failed to delete rule: ' + (err.error?.message || err.message);
        }
      });
  }

  cancelEditRule(): void {
    this.editingRule = null;
  }

  // Rule reordering via drag-drop
  dropRule(event: CdkDragDrop<ClassificationRule[]>): void {
    if (!this.selectedClassifier?.rules || !this.selectedClassifier?.id) return;

    moveItemInArray(this.selectedClassifier.rules, event.previousIndex, event.currentIndex);

    const ruleIds = this.selectedClassifier.rules
      .filter(r => r.id)
      .map(r => r.id as number);

    this.orchestratorService.reorderClassificationRules(this.instanceId, this.selectedClassifier.id, ruleIds)
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        error: (err) => {
          this.error = 'Failed to reorder rules: ' + (err.error?.message || err.message);
        }
      });
  }

  // Template usage
  applyTemplate(template: ClassificationRule): void {
    this.ruleForm.patchValue({
      name: template.name,
      pattern: template.pattern,
      classificationType: template.classificationType,
      severity: template.severity,
      action: template.action
    });
  }

  // Pattern testing
  testPattern(): void {
    const pattern = this.ruleForm.get('pattern')?.value;
    if (!pattern || !this.testPatternInput) return;

    this.orchestratorService.testPattern(this.instanceId, pattern, this.testPatternInput)
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: (result) => {
          this.testPatternResult = result;
        },
        error: (err) => {
          this.testPatternResult = {
            pattern,
            input: this.testPatternInput,
            valid: false,
            error: err.error?.message || err.message,
            matchCount: 0,
            matches: []
          };
        }
      });
  }

  // Helpers
  getSeverityClass(severity: ClassificationSeverity): string {
    switch (severity) {
      case 'CRITICAL': return 'severity-critical';
      case 'ERROR': return 'severity-error';
      case 'WARNING': return 'severity-warning';
      case 'INFO': return 'severity-info';
      default: return '';
    }
  }

  getActionClass(action: ClassificationAction): string {
    switch (action) {
      case 'ABORT':
      case 'ESCALATE': return 'action-severe';
      case 'RETRY':
      case 'RETRY_WITH_BACKOFF': return 'action-retry';
      case 'INVOKE_LLM':
      case 'INVOKE_LLM_FOR_FIX': return 'action-llm';
      case 'CONTINUE':
      case 'LOG': return 'action-passive';
      default: return '';
    }
  }

  clearError(): void {
    this.error = null;
  }

  classifyOutput(): void {
    if (!this.selectedClassifier || !this.classifyTestInput.trim()) return;
    this.classifyTesting = true;
    this.classifyTestResult = null;
    this.orchestratorService.classifyOutput(this.instanceId, this.selectedClassifier.id!, this.classifyTestInput)
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: (result) => {
          this.classifyTestResult = result;
          this.classifyTesting = false;
        },
        error: (err) => {
          this.error = 'Classification failed: ' + (err.error?.message || err.message);
          this.classifyTesting = false;
        }
      });
  }
}
