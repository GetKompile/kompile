/*
 * Copyright 2025 Kompile Inc.
 */

import { Component, OnInit, OnDestroy, Input } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule, ReactiveFormsModule, FormBuilder, FormGroup, Validators } from '@angular/forms';
import { MatTabsModule } from '@angular/material/tabs';
import { MatCardModule } from '@angular/material/card';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatSlideToggleModule } from '@angular/material/slide-toggle';
import { MatSliderModule } from '@angular/material/slider';
import { MatTableModule } from '@angular/material/table';
import { MatPaginatorModule } from '@angular/material/paginator';
import { MatSortModule } from '@angular/material/sort';
import { MatChipsModule } from '@angular/material/chips';
import { MatDialogModule, MatDialog } from '@angular/material/dialog';
import { MatTooltipModule } from '@angular/material/tooltip';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatExpansionModule } from '@angular/material/expansion';
import { MatBadgeModule } from '@angular/material/badge';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';
import { Subject, takeUntil } from 'rxjs';

import {
  ReactAgentService,
  ReActConfig,
  ReActStatus,
  EvalTestCase,
  EvalSuite,
  EvalTestResult,
  FactSheetMetrics,
  EvaluationType
} from '../../services/react-agent.service';

@Component({
  selector: 'app-react-agent-config',
  standalone: true,
  imports: [
    CommonModule,
    FormsModule,
    ReactiveFormsModule,
    MatTabsModule,
    MatCardModule,
    MatButtonModule,
    MatIconModule,
    MatFormFieldModule,
    MatInputModule,
    MatSelectModule,
    MatSlideToggleModule,
    MatSliderModule,
    MatTableModule,
    MatPaginatorModule,
    MatSortModule,
    MatChipsModule,
    MatDialogModule,
    MatTooltipModule,
    MatProgressBarModule,
    MatExpansionModule,
    MatBadgeModule,
    MatSnackBarModule
  ],
  templateUrl: './react-agent-config.component.html',
  styleUrls: ['./react-agent-config.component.scss']
})
export class ReactAgentConfigComponent implements OnInit, OnDestroy {
  @Input() factSheetId?: number;
  @Input() factSheetName?: string;

  private destroy$ = new Subject<void>();

  // State
  config: ReActConfig | null = null;
  status: ReActStatus | null = null;
  testCases: EvalTestCase[] = [];
  suites: EvalSuite[] = [];
  evaluationTypes: EvaluationType[] = [];
  metrics: FactSheetMetrics | null = null;
  results: EvalTestResult[] = [];

  // UI State
  isLoading = false;
  selectedTab = 0;
  selectedTestCase: EvalTestCase | null = null;
  selectedSuite: EvalSuite | null = null;

  // Forms
  testCaseForm: FormGroup;
  suiteForm: FormGroup;

  // Table columns
  testCaseColumns = ['name', 'query', 'expectedAnswer', 'priority', 'enabled', 'actions'];
  suiteColumns = ['name', 'testCaseCount', 'passRate', 'enabled', 'actions'];
  resultColumns = ['testCase', 'passed', 'score', 'completedAt', 'actions'];

  constructor(
    private reactAgentService: ReactAgentService,
    private fb: FormBuilder,
    private snackBar: MatSnackBar,
    private dialog: MatDialog
  ) {
    this.testCaseForm = this.createTestCaseForm();
    this.suiteForm = this.createSuiteForm();
  }

  ngOnInit(): void {
    this.loadData();
    this.subscribeToData();
  }

  ngOnDestroy(): void {
    this.destroy$.next();
    this.destroy$.complete();
  }

  private subscribeToData(): void {
    this.reactAgentService.config$.pipe(takeUntil(this.destroy$))
      .subscribe(config => this.config = config);

    this.reactAgentService.status$.pipe(takeUntil(this.destroy$))
      .subscribe(status => this.status = status);

    this.reactAgentService.testCases$.pipe(takeUntil(this.destroy$))
      .subscribe(cases => this.testCases = cases);

    this.reactAgentService.suites$.pipe(takeUntil(this.destroy$))
      .subscribe(suites => this.suites = suites);

    this.reactAgentService.evaluationTypes$.pipe(takeUntil(this.destroy$))
      .subscribe(types => this.evaluationTypes = types);
  }

  private loadData(): void {
    this.isLoading = true;
    this.reactAgentService.loadConfig().subscribe();
    this.reactAgentService.loadStatus().subscribe();
    this.reactAgentService.loadTestCases(this.factSheetId).subscribe();
    this.reactAgentService.loadSuites(this.factSheetId).subscribe();

    if (this.factSheetId) {
      this.loadMetrics();
      this.loadResults();
    }

    this.isLoading = false;
  }

  private loadMetrics(): void {
    if (!this.factSheetId) return;
    this.reactAgentService.getMetricsForFactSheet(this.factSheetId)
      .subscribe(metrics => this.metrics = metrics);
  }

  loadResults(): void {
    if (!this.factSheetId) return;
    this.reactAgentService.getResultsForFactSheet(this.factSheetId)
      .subscribe(results => this.results = results);
  }

  // ==================== Forms ====================

  private createTestCaseForm(): FormGroup {
    return this.fb.group({
      name: ['', Validators.required],
      description: [''],
      query: ['', Validators.required],
      expectedAnswer: [''],
      expectedFacts: [''],
      forbiddenFacts: [''],
      expectedEntities: [''],
      expectedToolCalls: [''],
      evaluationTypes: [['RELEVANCY', 'FAITHFULNESS', 'ANSWER_CORRECTNESS']],
      tags: [''],
      priority: [3],
      enabled: [true],
      timeoutMs: [30000]
    });
  }

  private createSuiteForm(): FormGroup {
    return this.fb.group({
      name: ['', Validators.required],
      description: [''],
      tags: [''],
      enabled: [true],
      requiredPassRate: [0.8]
    });
  }

  // ==================== Test Case Actions ====================

  createTestCase(): void {
    if (this.testCaseForm.invalid) return;

    const formValue = this.testCaseForm.value;
    const testCase: EvalTestCase = {
      name: formValue.name,
      description: formValue.description,
      factSheetId: this.factSheetId,
      factSheetName: this.factSheetName,
      query: formValue.query,
      expectedAnswer: formValue.expectedAnswer,
      expectedFacts: this.parseCommaSeparated(formValue.expectedFacts),
      forbiddenFacts: this.parseCommaSeparated(formValue.forbiddenFacts),
      expectedEntities: this.parseCommaSeparated(formValue.expectedEntities),
      expectedToolCalls: this.parseCommaSeparated(formValue.expectedToolCalls),
      evaluationTypes: formValue.evaluationTypes,
      tags: this.parseCommaSeparated(formValue.tags),
      priority: formValue.priority,
      enabled: formValue.enabled,
      timeoutMs: formValue.timeoutMs
    };

    this.reactAgentService.createTestCase(testCase).subscribe({
      next: () => {
        this.showMessage('Test case created');
        this.testCaseForm.reset(this.createTestCaseForm().value);
        this.selectedTestCase = null;
      },
      error: err => this.showMessage('Failed to create test case: ' + err.message)
    });
  }

  editTestCase(testCase: EvalTestCase): void {
    this.selectedTestCase = testCase;
    this.testCaseForm.patchValue({
      name: testCase.name,
      description: testCase.description || '',
      query: testCase.query,
      expectedAnswer: testCase.expectedAnswer || '',
      expectedFacts: testCase.expectedFacts?.join(', ') || '',
      forbiddenFacts: testCase.forbiddenFacts?.join(', ') || '',
      expectedEntities: testCase.expectedEntities?.join(', ') || '',
      expectedToolCalls: testCase.expectedToolCalls?.join(', ') || '',
      evaluationTypes: testCase.evaluationTypes || [],
      tags: testCase.tags?.join(', ') || '',
      priority: testCase.priority || 3,
      enabled: testCase.enabled !== false,
      timeoutMs: testCase.timeoutMs || 30000
    });
  }

  updateTestCase(): void {
    if (!this.selectedTestCase?.id || this.testCaseForm.invalid) return;

    const formValue = this.testCaseForm.value;
    const testCase: EvalTestCase = {
      ...this.selectedTestCase,
      name: formValue.name,
      description: formValue.description,
      query: formValue.query,
      expectedAnswer: formValue.expectedAnswer,
      expectedFacts: this.parseCommaSeparated(formValue.expectedFacts),
      forbiddenFacts: this.parseCommaSeparated(formValue.forbiddenFacts),
      expectedEntities: this.parseCommaSeparated(formValue.expectedEntities),
      expectedToolCalls: this.parseCommaSeparated(formValue.expectedToolCalls),
      evaluationTypes: formValue.evaluationTypes,
      tags: this.parseCommaSeparated(formValue.tags),
      priority: formValue.priority,
      enabled: formValue.enabled,
      timeoutMs: formValue.timeoutMs
    };

    this.reactAgentService.updateTestCase(this.selectedTestCase.id, testCase).subscribe({
      next: () => {
        this.showMessage('Test case updated');
        this.cancelTestCaseEdit();
      },
      error: err => this.showMessage('Failed to update test case: ' + err.message)
    });
  }

  deleteTestCase(testCase: EvalTestCase): void {
    if (!testCase.id) return;
    if (!confirm(`Delete test case "${testCase.name}"?`)) return;

    this.reactAgentService.deleteTestCase(testCase.id).subscribe({
      next: () => this.showMessage('Test case deleted'),
      error: err => this.showMessage('Failed to delete test case: ' + err.message)
    });
  }

  cancelTestCaseEdit(): void {
    this.selectedTestCase = null;
    this.testCaseForm.reset(this.createTestCaseForm().value);
  }

  toggleTestCaseEnabled(testCase: EvalTestCase): void {
    if (!testCase.id) return;
    const updated = { ...testCase, enabled: !testCase.enabled };
    this.reactAgentService.updateTestCase(testCase.id, updated).subscribe();
  }

  // ==================== Suite Actions ====================

  createSuite(): void {
    if (this.suiteForm.invalid) return;

    const formValue = this.suiteForm.value;
    const suite: EvalSuite = {
      name: formValue.name,
      description: formValue.description,
      factSheetId: this.factSheetId,
      tags: this.parseCommaSeparated(formValue.tags),
      enabled: formValue.enabled,
      requiredPassRate: formValue.requiredPassRate,
      testCaseIds: []
    };

    this.reactAgentService.createSuite(suite).subscribe({
      next: () => {
        this.showMessage('Suite created');
        this.suiteForm.reset(this.createSuiteForm().value);
        this.selectedSuite = null;
      },
      error: err => this.showMessage('Failed to create suite: ' + err.message)
    });
  }

  editSuite(suite: EvalSuite): void {
    this.selectedSuite = suite;
    this.suiteForm.patchValue({
      name: suite.name,
      description: suite.description || '',
      tags: suite.tags?.join(', ') || '',
      enabled: suite.enabled !== false,
      requiredPassRate: suite.requiredPassRate || 0.8
    });
  }

  updateSuite(): void {
    if (!this.selectedSuite?.id || this.suiteForm.invalid) return;

    const formValue = this.suiteForm.value;
    const suite: EvalSuite = {
      ...this.selectedSuite,
      name: formValue.name,
      description: formValue.description,
      tags: this.parseCommaSeparated(formValue.tags),
      enabled: formValue.enabled,
      requiredPassRate: formValue.requiredPassRate
    };

    this.reactAgentService.updateSuite(this.selectedSuite.id, suite).subscribe({
      next: () => {
        this.showMessage('Suite updated');
        this.cancelSuiteEdit();
      },
      error: err => this.showMessage('Failed to update suite: ' + err.message)
    });
  }

  deleteSuite(suite: EvalSuite): void {
    if (!suite.id) return;
    if (!confirm(`Delete suite "${suite.name}"?`)) return;

    this.reactAgentService.deleteSuite(suite.id).subscribe({
      next: () => this.showMessage('Suite deleted'),
      error: err => this.showMessage('Failed to delete suite: ' + err.message)
    });
  }

  cancelSuiteEdit(): void {
    this.selectedSuite = null;
    this.suiteForm.reset(this.createSuiteForm().value);
  }

  addTestCaseToSuite(suite: EvalSuite, testCase: EvalTestCase): void {
    if (!suite.id || !testCase.id) return;
    this.reactAgentService.addTestCaseToSuite(suite.id, testCase.id).subscribe({
      next: () => {
        this.showMessage('Test case added to suite');
        this.reactAgentService.loadSuites(this.factSheetId).subscribe();
      },
      error: err => this.showMessage('Failed to add test case: ' + err.message)
    });
  }

  removeTestCaseFromSuite(suite: EvalSuite, testCaseId: string): void {
    if (!suite.id) return;
    this.reactAgentService.removeTestCaseFromSuite(suite.id, testCaseId).subscribe({
      next: () => {
        this.showMessage('Test case removed from suite');
        this.reactAgentService.loadSuites(this.factSheetId).subscribe();
      },
      error: err => this.showMessage('Failed to remove test case: ' + err.message)
    });
  }

  // ==================== Helpers ====================

  private parseCommaSeparated(value: string): string[] {
    if (!value) return [];
    return value.split(',').map(s => s.trim()).filter(s => s.length > 0);
  }

  private showMessage(message: string): void {
    this.snackBar.open(message, 'Close', { duration: 3000 });
  }

  getTestCaseById(id: string): EvalTestCase | undefined {
    return this.testCases.find(tc => tc.id === id);
  }

  getTestCaseCount(suite: EvalSuite): number {
    return suite.testCaseIds?.length || 0;
  }

  formatScore(score: number): string {
    return (score * 100).toFixed(1) + '%';
  }

  formatDate(date: string | undefined): string {
    if (!date) return 'N/A';
    return new Date(date).toLocaleString();
  }

  getPassRateClass(passRate: number): string {
    if (passRate >= 0.8) return 'pass-rate-good';
    if (passRate >= 0.5) return 'pass-rate-warning';
    return 'pass-rate-bad';
  }
}
