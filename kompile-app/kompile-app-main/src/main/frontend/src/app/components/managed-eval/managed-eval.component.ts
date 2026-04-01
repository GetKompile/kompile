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
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatCardModule } from '@angular/material/card';
import { MatTooltipModule } from '@angular/material/tooltip';
import { MatSelectModule } from '@angular/material/select';
import { MatChipsModule } from '@angular/material/chips';
import { MatExpansionModule } from '@angular/material/expansion';
import { MatTabsModule } from '@angular/material/tabs';
import { MatTableModule } from '@angular/material/table';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatDividerModule } from '@angular/material/divider';
import { MatBadgeModule } from '@angular/material/badge';
import { MatMenuModule } from '@angular/material/menu';
import { MatDialogModule, MatDialog } from '@angular/material/dialog';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';
import { MatListModule } from '@angular/material/list';
import { MatSliderModule } from '@angular/material/slider';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatSlideToggleModule } from '@angular/material/slide-toggle';
import { MatButtonToggleModule } from '@angular/material/button-toggle';
import { TextFieldModule } from '@angular/cdk/text-field';
import { Subject, takeUntil, forkJoin } from 'rxjs';

import { ManagedEvalService } from '../../services/managed-eval.service';
import { FactSheetService } from '../../services/fact-sheet.service';
import { FactSheet } from '../../models/api-models';
import {
  EvalSuite,
  EvalCase,
  EvalTestResult,
  EvalSuiteResult,
  FactSheetMetrics,
  CreateSuiteRequest,
  CreateTestCaseRequest,
  EVALUATION_TYPES,
  PRIORITY_LEVELS
} from '../../models/managed-eval.models';

@Component({
  selector: 'app-managed-eval',
  standalone: true,
  imports: [
    CommonModule,
    FormsModule,
    MatButtonModule,
    MatIconModule,
    MatProgressSpinnerModule,
    MatFormFieldModule,
    MatInputModule,
    MatCardModule,
    MatTooltipModule,
    MatSelectModule,
    MatChipsModule,
    MatExpansionModule,
    MatTabsModule,
    MatTableModule,
    MatProgressBarModule,
    MatDividerModule,
    MatBadgeModule,
    MatMenuModule,
    MatDialogModule,
    MatSnackBarModule,
    MatListModule,
    MatSliderModule,
    MatCheckboxModule,
    MatSlideToggleModule,
    MatButtonToggleModule,
    TextFieldModule
  ],
  templateUrl: './managed-eval.component.html',
  styleUrls: ['./managed-eval.component.scss']
})
export class ManagedEvalComponent implements OnInit, OnDestroy {
  private destroy$ = new Subject<void>();

  @Input() factSheetId?: number;

  // Data
  suites: EvalSuite[] = [];
  selectedSuite: EvalSuite | null = null;
  testCases: EvalCase[] = [];
  selectedTestCase: EvalCase | null = null;
  metrics: FactSheetMetrics | null = null;
  testCaseResults: EvalTestResult[] = [];
  suiteResults: EvalSuiteResult[] = [];

  // Editing state
  editingSuite = false;
  editingTestCase = false;
  editedSuite: Partial<EvalSuite> = {};
  editedTestCase: Partial<EvalCase> = {};

  // View state
  detailsView: 'editor' | 'results' | 'metrics' = 'editor';
  loading = true;
  savingSuite = false;
  savingTestCase = false;

  // Constants
  evaluationTypes = EVALUATION_TYPES;
  priorityLevels = PRIORITY_LEVELS;

  // New tag input
  newTag = '';
  newExpectedFact = '';
  newForbiddenFact = '';
  newExpectedEntity = '';

  constructor(
    private evalService: ManagedEvalService,
    private factSheetService: FactSheetService,
    private snackBar: MatSnackBar,
    private dialog: MatDialog
  ) {}

  ngOnInit(): void {
    this.loadData();
  }

  ngOnDestroy(): void {
    this.destroy$.next();
    this.destroy$.complete();
  }

  // ═══════════════════════════════════════════════════════════════════════════════
  // DATA LOADING
  // ═══════════════════════════════════════════════════════════════════════════════

  loadData(): void {
    this.loading = true;

    // Get fact sheet ID if not provided
    if (!this.factSheetId) {
      this.factSheetService.activeSheet$
        .pipe(takeUntil(this.destroy$))
        .subscribe({
          next: (factSheet: FactSheet | null) => {
            if (factSheet?.id) {
              this.factSheetId = factSheet.id;
              this.loadSuitesAndMetrics();
            } else {
              this.loading = false;
              this.snackBar.open('No active fact sheet', 'Close', { duration: 3000 });
            }
          },
          error: (err: Error) => {
            this.loading = false;
            this.snackBar.open('Failed to get active fact sheet', 'Close', { duration: 3000 });
          }
        });
    } else {
      this.loadSuitesAndMetrics();
    }
  }

  loadSuitesAndMetrics(): void {
    if (!this.factSheetId) return;

    forkJoin({
      suites: this.evalService.getSuitesForFactSheet(this.factSheetId),
      metrics: this.evalService.getFactSheetMetrics(this.factSheetId)
    }).pipe(takeUntil(this.destroy$))
      .subscribe({
        next: ({ suites, metrics }) => {
          this.suites = suites;
          this.metrics = metrics;
          this.loading = false;

          // Select first suite if available
          if (suites.length > 0 && !this.selectedSuite) {
            this.selectSuite(suites[0]);
          }
        },
        error: (err) => {
          this.loading = false;
          this.snackBar.open('Failed to load evaluation data', 'Close', { duration: 3000 });
        }
      });
  }

  // ═══════════════════════════════════════════════════════════════════════════════
  // SUITE OPERATIONS
  // ═══════════════════════════════════════════════════════════════════════════════

  selectSuite(suite: EvalSuite): void {
    this.selectedSuite = suite;
    this.selectedTestCase = null;
    this.editingSuite = false;
    this.editingTestCase = false;
    this.loadTestCases(suite.id);
    this.loadSuiteResults(suite.id);
  }

  loadTestCases(suiteId: string): void {
    this.evalService.getTestCasesInSuite(suiteId)
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: (cases) => {
          this.testCases = cases;
          if (cases.length > 0 && !this.selectedTestCase) {
            this.selectTestCase(cases[0]);
          }
        },
        error: (err) => {
          this.snackBar.open('Failed to load test cases', 'Close', { duration: 3000 });
        }
      });
  }

  loadSuiteResults(suiteId: string): void {
    this.evalService.getSuiteResultHistory(suiteId, 10)
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: (results) => {
          this.suiteResults = results;
        }
      });
  }

  createSuite(): void {
    if (!this.factSheetId) return;

    this.editingSuite = true;
    this.selectedSuite = null;
    this.editedSuite = {
      name: 'New Evaluation Suite',
      description: '',
      enabled: true,
      requiredPassRate: 0.8,
      tags: []
    };
    this.detailsView = 'editor';
  }

  editSuite(): void {
    if (!this.selectedSuite) return;
    this.editingSuite = true;
    this.editedSuite = { ...this.selectedSuite };
    this.detailsView = 'editor';
  }

  saveSuite(): void {
    if (!this.factSheetId) return;

    this.savingSuite = true;

    if (this.selectedSuite?.id) {
      // Update existing
      this.evalService.updateSuite(this.selectedSuite.id, {
        name: this.editedSuite.name,
        description: this.editedSuite.description,
        enabled: this.editedSuite.enabled,
        requiredPassRate: this.editedSuite.requiredPassRate,
        tags: this.editedSuite.tags
      }).pipe(takeUntil(this.destroy$))
        .subscribe({
          next: () => {
            this.savingSuite = false;
            this.editingSuite = false;
            this.snackBar.open('Suite updated', 'Close', { duration: 2000 });
            this.loadSuitesAndMetrics();
          },
          error: (err) => {
            this.savingSuite = false;
            this.snackBar.open('Failed to update suite', 'Close', { duration: 3000 });
          }
        });
    } else {
      // Create new
      const request: CreateSuiteRequest = {
        factSheetId: this.factSheetId,
        name: this.editedSuite.name || 'New Suite',
        description: this.editedSuite.description
      };
      this.evalService.createSuite(request)
        .pipe(takeUntil(this.destroy$))
        .subscribe({
          next: (suite) => {
            this.savingSuite = false;
            this.editingSuite = false;
            this.snackBar.open('Suite created', 'Close', { duration: 2000 });
            this.loadSuitesAndMetrics();
            this.selectSuite(suite);
          },
          error: (err) => {
            this.savingSuite = false;
            this.snackBar.open('Failed to create suite', 'Close', { duration: 3000 });
          }
        });
    }
  }

  cancelEditSuite(): void {
    this.editingSuite = false;
    this.editedSuite = {};
  }

  deleteSuite(suite: EvalSuite): void {
    if (!confirm(`Delete suite "${suite.name}"? This will also delete all test cases in this suite.`)) {
      return;
    }

    this.evalService.deleteSuite(suite.id)
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: () => {
          this.snackBar.open('Suite deleted', 'Close', { duration: 2000 });
          this.selectedSuite = null;
          this.testCases = [];
          this.loadSuitesAndMetrics();
        },
        error: (err) => {
          this.snackBar.open('Failed to delete suite', 'Close', { duration: 3000 });
        }
      });
  }

  // ═══════════════════════════════════════════════════════════════════════════════
  // TEST CASE OPERATIONS
  // ═══════════════════════════════════════════════════════════════════════════════

  selectTestCase(testCase: EvalCase): void {
    this.selectedTestCase = testCase;
    this.editingTestCase = false;
    this.loadTestCaseResults(testCase.id);
  }

  loadTestCaseResults(caseId: string): void {
    this.evalService.getTestCaseResultHistory(caseId, 10)
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: (results) => {
          this.testCaseResults = results;
        }
      });
  }

  createTestCase(): void {
    if (!this.selectedSuite) return;

    this.editingTestCase = true;
    this.selectedTestCase = null;
    this.editedTestCase = {
      name: 'New Test Case',
      query: '',
      expectedAnswer: '',
      expectedFacts: [],
      forbiddenFacts: [],
      expectedEntities: [],
      evaluationTypes: ['RELEVANCY', 'FAITHFULNESS', 'ANSWER_CORRECTNESS'],
      thresholds: {},
      tags: [],
      priority: 3,
      enabled: true,
      timeoutMs: 30000
    };
    this.detailsView = 'editor';
  }

  editTestCase(): void {
    if (!this.selectedTestCase) return;
    this.editingTestCase = true;
    this.editedTestCase = { ...this.selectedTestCase };
    this.detailsView = 'editor';
  }

  saveTestCase(): void {
    if (!this.selectedSuite) return;

    this.savingTestCase = true;

    if (this.selectedTestCase?.id) {
      // Update existing
      this.evalService.updateTestCase(this.selectedTestCase.id, {
        name: this.editedTestCase.name,
        description: this.editedTestCase.description,
        query: this.editedTestCase.query,
        expectedAnswer: this.editedTestCase.expectedAnswer,
        expectedFacts: this.editedTestCase.expectedFacts,
        forbiddenFacts: this.editedTestCase.forbiddenFacts,
        expectedEntities: this.editedTestCase.expectedEntities,
        evaluationTypes: this.editedTestCase.evaluationTypes,
        thresholds: this.editedTestCase.thresholds,
        tags: this.editedTestCase.tags,
        priority: this.editedTestCase.priority,
        enabled: this.editedTestCase.enabled,
        timeoutMs: this.editedTestCase.timeoutMs
      }).pipe(takeUntil(this.destroy$))
        .subscribe({
          next: () => {
            this.savingTestCase = false;
            this.editingTestCase = false;
            this.snackBar.open('Test case updated', 'Close', { duration: 2000 });
            this.loadTestCases(this.selectedSuite!.id);
          },
          error: (err) => {
            this.savingTestCase = false;
            this.snackBar.open('Failed to update test case', 'Close', { duration: 3000 });
          }
        });
    } else {
      // Create new
      const request: CreateTestCaseRequest = {
        name: this.editedTestCase.name || 'New Test Case',
        description: this.editedTestCase.description,
        factSheetId: this.factSheetId,
        query: this.editedTestCase.query || '',
        expectedAnswer: this.editedTestCase.expectedAnswer,
        expectedFacts: this.editedTestCase.expectedFacts,
        forbiddenFacts: this.editedTestCase.forbiddenFacts,
        expectedEntities: this.editedTestCase.expectedEntities,
        evaluationTypes: this.editedTestCase.evaluationTypes,
        thresholds: this.editedTestCase.thresholds,
        tags: this.editedTestCase.tags,
        priority: this.editedTestCase.priority,
        enabled: this.editedTestCase.enabled,
        timeoutMs: this.editedTestCase.timeoutMs
      };

      this.evalService.createTestCase(this.selectedSuite.id, request)
        .pipe(takeUntil(this.destroy$))
        .subscribe({
          next: (testCase) => {
            this.savingTestCase = false;
            this.editingTestCase = false;
            this.snackBar.open('Test case created', 'Close', { duration: 2000 });
            this.loadTestCases(this.selectedSuite!.id);
            this.selectTestCase(testCase);
          },
          error: (err) => {
            this.savingTestCase = false;
            this.snackBar.open('Failed to create test case', 'Close', { duration: 3000 });
          }
        });
    }
  }

  cancelEditTestCase(): void {
    this.editingTestCase = false;
    this.editedTestCase = {};
  }

  deleteTestCase(testCase: EvalCase): void {
    if (!confirm(`Delete test case "${testCase.name}"?`)) {
      return;
    }

    this.evalService.deleteTestCase(testCase.id)
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: () => {
          this.snackBar.open('Test case deleted', 'Close', { duration: 2000 });
          this.selectedTestCase = null;
          if (this.selectedSuite) {
            this.loadTestCases(this.selectedSuite.id);
          }
        },
        error: (err) => {
          this.snackBar.open('Failed to delete test case', 'Close', { duration: 3000 });
        }
      });
  }

  duplicateTestCase(testCase: EvalCase): void {
    if (!this.selectedSuite) return;

    const request: CreateTestCaseRequest = {
      name: testCase.name + ' (Copy)',
      description: testCase.description,
      factSheetId: testCase.factSheetId,
      query: testCase.query,
      expectedAnswer: testCase.expectedAnswer,
      expectedFacts: [...(testCase.expectedFacts || [])],
      forbiddenFacts: [...(testCase.forbiddenFacts || [])],
      expectedEntities: [...(testCase.expectedEntities || [])],
      evaluationTypes: [...(testCase.evaluationTypes || [])],
      thresholds: { ...(testCase.thresholds || {}) },
      tags: [...(testCase.tags || [])],
      priority: testCase.priority,
      enabled: testCase.enabled,
      timeoutMs: testCase.timeoutMs
    };

    this.evalService.createTestCase(this.selectedSuite.id, request)
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: () => {
          this.snackBar.open('Test case duplicated', 'Close', { duration: 2000 });
          this.loadTestCases(this.selectedSuite!.id);
        },
        error: (err) => {
          this.snackBar.open('Failed to duplicate test case', 'Close', { duration: 3000 });
        }
      });
  }

  // ═══════════════════════════════════════════════════════════════════════════════
  // CHIP LIST MANAGEMENT
  // ═══════════════════════════════════════════════════════════════════════════════

  addTag(): void {
    if (this.newTag.trim() && this.editedTestCase.tags) {
      if (!this.editedTestCase.tags.includes(this.newTag.trim())) {
        this.editedTestCase.tags = [...this.editedTestCase.tags, this.newTag.trim()];
      }
      this.newTag = '';
    }
  }

  removeTag(tag: string): void {
    if (this.editedTestCase.tags) {
      this.editedTestCase.tags = this.editedTestCase.tags.filter(t => t !== tag);
    }
  }

  addExpectedFact(): void {
    if (this.newExpectedFact.trim() && this.editedTestCase.expectedFacts) {
      if (!this.editedTestCase.expectedFacts.includes(this.newExpectedFact.trim())) {
        this.editedTestCase.expectedFacts = [...this.editedTestCase.expectedFacts, this.newExpectedFact.trim()];
      }
      this.newExpectedFact = '';
    }
  }

  removeExpectedFact(fact: string): void {
    if (this.editedTestCase.expectedFacts) {
      this.editedTestCase.expectedFacts = this.editedTestCase.expectedFacts.filter(f => f !== fact);
    }
  }

  addForbiddenFact(): void {
    if (this.newForbiddenFact.trim() && this.editedTestCase.forbiddenFacts) {
      if (!this.editedTestCase.forbiddenFacts.includes(this.newForbiddenFact.trim())) {
        this.editedTestCase.forbiddenFacts = [...this.editedTestCase.forbiddenFacts, this.newForbiddenFact.trim()];
      }
      this.newForbiddenFact = '';
    }
  }

  removeForbiddenFact(fact: string): void {
    if (this.editedTestCase.forbiddenFacts) {
      this.editedTestCase.forbiddenFacts = this.editedTestCase.forbiddenFacts.filter(f => f !== fact);
    }
  }

  addExpectedEntity(): void {
    if (this.newExpectedEntity.trim() && this.editedTestCase.expectedEntities) {
      if (!this.editedTestCase.expectedEntities.includes(this.newExpectedEntity.trim())) {
        this.editedTestCase.expectedEntities = [...this.editedTestCase.expectedEntities, this.newExpectedEntity.trim()];
      }
      this.newExpectedEntity = '';
    }
  }

  removeExpectedEntity(entity: string): void {
    if (this.editedTestCase.expectedEntities) {
      this.editedTestCase.expectedEntities = this.editedTestCase.expectedEntities.filter(e => e !== entity);
    }
  }

  toggleEvaluationType(type: string): void {
    if (!this.editedTestCase.evaluationTypes) {
      this.editedTestCase.evaluationTypes = [];
    }
    const index = this.editedTestCase.evaluationTypes.indexOf(type);
    if (index >= 0) {
      this.editedTestCase.evaluationTypes = this.editedTestCase.evaluationTypes.filter(t => t !== type);
    } else {
      this.editedTestCase.evaluationTypes = [...this.editedTestCase.evaluationTypes, type];
    }
  }

  isEvaluationTypeSelected(type: string): boolean {
    return this.editedTestCase.evaluationTypes?.includes(type) || false;
  }

  // ═══════════════════════════════════════════════════════════════════════════════
  // IMPORT/EXPORT
  // ═══════════════════════════════════════════════════════════════════════════════

  exportSuite(): void {
    if (!this.selectedSuite) return;

    this.evalService.exportSuite(this.selectedSuite.id)
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: (suite) => {
          this.evalService.downloadSuiteAsJson(suite);
          this.snackBar.open('Suite exported', 'Close', { duration: 2000 });
        },
        error: (err) => {
          this.snackBar.open('Failed to export suite', 'Close', { duration: 3000 });
        }
      });
  }

  async importSuite(event: Event): Promise<void> {
    const input = event.target as HTMLInputElement;
    if (!input.files?.length || !this.factSheetId) return;

    try {
      const suite = await this.evalService.parseImportedFile(input.files[0]);
      this.evalService.importSuite(suite, this.factSheetId)
        .pipe(takeUntil(this.destroy$))
        .subscribe({
          next: (imported) => {
            this.snackBar.open('Suite imported', 'Close', { duration: 2000 });
            this.loadSuitesAndMetrics();
            this.selectSuite(imported);
          },
          error: (err) => {
            this.snackBar.open('Failed to import suite', 'Close', { duration: 3000 });
          }
        });
    } catch (err) {
      this.snackBar.open('Invalid JSON file', 'Close', { duration: 3000 });
    }

    input.value = '';
  }

  // ═══════════════════════════════════════════════════════════════════════════════
  // HELPERS
  // ═══════════════════════════════════════════════════════════════════════════════

  getPriorityColor(priority: number): string {
    return PRIORITY_LEVELS.find(p => p.value === priority)?.color || '#9e9e9e';
  }

  getPriorityLabel(priority: number): string {
    return PRIORITY_LEVELS.find(p => p.value === priority)?.label || 'Unknown';
  }

  formatDate(dateStr?: string): string {
    if (!dateStr) return '-';
    return new Date(dateStr).toLocaleString();
  }

  formatPercentage(value?: number): string {
    if (value === undefined || value === null) return '-';
    return `${(value * 100).toFixed(1)}%`;
  }

  formatScore(value?: number): string {
    if (value === undefined || value === null) return '-';
    return value.toFixed(2);
  }
}
