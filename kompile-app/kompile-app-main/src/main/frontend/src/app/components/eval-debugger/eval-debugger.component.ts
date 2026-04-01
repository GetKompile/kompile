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
import { TextFieldModule } from '@angular/cdk/text-field';
import { MatListModule } from '@angular/material/list';
import { MatButtonToggleModule } from '@angular/material/button-toggle';
import { Subject, takeUntil } from 'rxjs';

import { EvalDebuggerService } from '../../services/eval-debugger.service';
import { ManagedEvalService } from '../../services/managed-eval.service';
import { FactSheetService } from '../../services/fact-sheet.service';
import { FactSheet } from '../../models/api-models';
import {
  EvalDebuggerStatus,
  EvaluatorTypeInfo,
  TestCase,
  TestCaseResult,
  BatchTestResult,
  TestSuite,
  EvaluationResultDto,
  LlmProviderInfo,
  LlmModelInfo,
  LlmJudgeEvaluation,
  CombinedEvalResult,
  BatchCombinedResult
} from '../../models/eval-debugger.models';
import { EvalSuite, CreateTestCaseRequest } from '../../models/managed-eval.models';

@Component({
  selector: 'app-eval-debugger',
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
    TextFieldModule,
    MatListModule,
    MatButtonToggleModule
  ],
  templateUrl: './eval-debugger.component.html',
  styleUrls: ['./eval-debugger.component.scss']
})
export class EvalDebuggerComponent implements OnInit, OnDestroy {
  private destroy$ = new Subject<void>();

  status: EvalDebuggerStatus | null = null;
  evaluatorTypes: EvaluatorTypeInfo[] = [];
  testSuites: TestSuite[] = [];

  // Current working state
  currentSuite: TestSuite;
  selectedEvaluatorTypes: string[] = [];

  // Results
  lastRunResult: BatchTestResult | null = null;
  expandedResultId: string | null = null;

  // Loading states
  loading = true;
  running = false;
  saving = false;

  // Error state
  error: string | null = null;
  successMessage: string | null = null;

  // Managed Eval integration
  managedSuites: EvalSuite[] = [];
  selectedManagedSuiteId: string | null = null;
  activeFactSheetId: number | null = null;

  // LLM-as-Judge state
  llmProviders: LlmProviderInfo[] = [];
  selectedLlmProviderId: string | null = null;
  selectedLlmModelId: string | null = null;
  enableLlmJudge = false;
  lastCombinedResult: BatchCombinedResult | null = null;
  runMode: 'automated' | 'llm-judge' | 'combined' = 'automated';

  constructor(
    private evalService: EvalDebuggerService,
    private managedEvalService: ManagedEvalService,
    private factSheetService: FactSheetService,
    private snackBar: MatSnackBar
  ) {
    this.currentSuite = this.evalService.createEmptyTestSuite();
  }

  ngOnInit(): void {
    this.loadInitialData();
  }

  ngOnDestroy(): void {
    this.destroy$.next();
    this.destroy$.complete();
  }

  loadInitialData(): void {
    this.loading = true;
    this.error = null;

    // Load active fact sheet for managed eval integration
    this.factSheetService.activeSheet$
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: (factSheet: FactSheet | null) => {
          if (factSheet?.id) {
            this.activeFactSheetId = factSheet.id;
            this.loadManagedSuites();
          }
        }
      });

    // Load status
    this.evalService.getStatus()
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: (status) => {
          this.status = status;
          if (status.available) {
            this.loadEvaluatorTypes();
            this.loadTestSuites();
          }
          // Load LLM providers if LLM judge is available
          if (status.llmJudgeAvailable) {
            this.loadLlmProviders();
          }
          this.loading = false;
        },
        error: (err) => {
          this.error = 'Failed to load eval debugger status: ' + (err.error?.message || err.message);
          this.loading = false;
        }
      });
  }

  loadEvaluatorTypes(): void {
    this.evalService.getEvaluatorTypes()
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: (types) => {
          this.evaluatorTypes = types;
          // Select all available types by default
          this.selectedEvaluatorTypes = types
            .filter(t => t.available)
            .map(t => t.type);
        },
        error: (err) => {
          console.error('Failed to load evaluator types:', err);
        }
      });
  }

  loadLlmProviders(): void {
    this.evalService.getLlmProviders()
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: (providers) => {
          this.llmProviders = providers.filter(p => p.available);
          // Auto-select first available provider
          if (this.llmProviders.length > 0 && !this.selectedLlmProviderId) {
            this.selectedLlmProviderId = this.llmProviders[0].id;
            if (this.llmProviders[0].models.length > 0) {
              this.selectedLlmModelId = this.llmProviders[0].models[0].id;
            }
          }
        },
        error: (err) => {
          console.error('Failed to load LLM providers:', err);
        }
      });
  }

  onProviderChange(): void {
    const provider = this.llmProviders.find(p => p.id === this.selectedLlmProviderId);
    if (provider && provider.models.length > 0) {
      this.selectedLlmModelId = provider.models[0].id;
    } else {
      this.selectedLlmModelId = null;
    }
  }

  getSelectedProviderModels(): LlmModelInfo[] {
    const provider = this.llmProviders.find(p => p.id === this.selectedLlmProviderId);
    return provider?.models || [];
  }

  loadTestSuites(): void {
    this.evalService.getTestSuites()
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: (suites) => {
          this.testSuites = suites;
        },
        error: (err) => {
          console.error('Failed to load test suites:', err);
        }
      });
  }

  // Test Case Management

  addTestCase(): void {
    this.currentSuite.testCases.push(this.evalService.createEmptyTestCase());
  }

  removeTestCase(index: number): void {
    this.currentSuite.testCases.splice(index, 1);
  }

  duplicateTestCase(index: number): void {
    const original = this.currentSuite.testCases[index];
    const copy: TestCase = {
      ...original,
      id: 'tc_' + Math.random().toString(36).substring(2, 11)
    };
    this.currentSuite.testCases.splice(index + 1, 0, copy);
  }

  moveTestCase(index: number, direction: 'up' | 'down'): void {
    const newIndex = direction === 'up' ? index - 1 : index + 1;
    if (newIndex < 0 || newIndex >= this.currentSuite.testCases.length) return;

    const [removed] = this.currentSuite.testCases.splice(index, 1);
    this.currentSuite.testCases.splice(newIndex, 0, removed);
  }

  // Running Tests

  runAllTests(): void {
    if (this.currentSuite.testCases.length === 0) {
      this.showMessage('No test cases to run', true);
      return;
    }

    if (this.runMode === 'combined' && this.status?.llmJudgeAvailable) {
      this.runAllCombinedTests();
      return;
    }

    this.running = true;
    this.error = null;
    this.lastRunResult = null;

    const request = {
      testCases: this.currentSuite.testCases.map(tc => ({
        ...tc,
        evaluatorTypes: this.selectedEvaluatorTypes
      })),
      evaluatorTypes: this.selectedEvaluatorTypes
    };

    this.evalService.runBatchTests(request)
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: (result) => {
          this.lastRunResult = result;
          this.running = false;
          this.showMessage(`Completed: ${result.passedTests}/${result.totalTests} passed`);
        },
        error: (err) => {
          this.error = 'Failed to run tests: ' + (err.error?.message || err.message);
          this.running = false;
        }
      });
  }

  runAllCombinedTests(): void {
    this.running = true;
    this.error = null;
    this.lastRunResult = null;
    this.lastCombinedResult = null;

    const request = {
      testCases: this.currentSuite.testCases.map(tc => ({
        ...tc,
        evaluatorTypes: this.selectedEvaluatorTypes
      })),
      evaluatorTypes: this.selectedEvaluatorTypes
    };

    this.evalService.runBatchCombinedTests(request)
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: (result) => {
          this.lastCombinedResult = result;
          // Also populate the standard result for backward compatibility
          this.lastRunResult = {
            runId: result.runId,
            success: result.success,
            error: result.error,
            results: result.results
              .filter(r => r.automatedResult)
              .map(r => r.automatedResult!),
            totalTests: result.totalTests,
            passedTests: result.automatedPassedTests,
            failedTests: result.totalTests - result.automatedPassedTests,
            averageScore: result.averageAutomatedScore,
            totalTimeMs: result.totalTimeMs,
            timestamp: result.timestamp
          };
          this.running = false;
          this.showMessage(
            `Completed: Automated ${result.automatedPassedTests}/${result.totalTests}, ` +
            `LLM Judge ${result.llmJudgePassedTests}/${result.totalTests}`
          );
        },
        error: (err) => {
          this.error = 'Failed to run combined tests: ' + (err.error?.message || err.message);
          this.running = false;
        }
      });
  }

  runSingleTest(testCase: TestCase): void {
    if (this.runMode === 'combined' && this.status?.llmJudgeAvailable) {
      this.runCombinedSingleTest(testCase);
      return;
    }

    this.running = true;

    const request = {
      ...testCase,
      evaluatorTypes: this.selectedEvaluatorTypes
    };

    this.evalService.runSingleTest(request)
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: (result) => {
          // Update the last run result with just this one
          this.lastRunResult = {
            runId: 'single_' + Date.now(),
            success: true,
            results: [result],
            totalTests: 1,
            passedTests: result.passed ? 1 : 0,
            failedTests: result.passed ? 0 : 1,
            averageScore: result.evaluationReport?.overallScore || 0,
            totalTimeMs: result.ragTimeMs + result.evaluationTimeMs,
            timestamp: result.timestamp
          };
          this.expandedResultId = result.testCaseId;
          this.running = false;
          this.showMessage(result.passed ? 'Test passed!' : 'Test failed');
        },
        error: (err) => {
          this.error = 'Failed to run test: ' + (err.error?.message || err.message);
          this.running = false;
        }
      });
  }

  runCombinedSingleTest(testCase: TestCase): void {
    this.running = true;

    const request = {
      ...testCase,
      evaluatorTypes: this.selectedEvaluatorTypes
    };

    this.evalService.runCombinedTest(request)
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: (result) => {
          // Update both automated and combined results
          if (result.automatedResult) {
            this.lastRunResult = {
              runId: 'combined_single_' + Date.now(),
              success: true,
              results: [result.automatedResult],
              totalTests: 1,
              passedTests: result.automatedResult.passed ? 1 : 0,
              failedTests: result.automatedResult.passed ? 0 : 1,
              averageScore: result.automatedResult.evaluationReport?.overallScore || 0,
              totalTimeMs: result.automatedResult.ragTimeMs + result.automatedResult.evaluationTimeMs,
              timestamp: result.timestamp
            };
          }
          this.lastCombinedResult = {
            runId: 'combined_single_' + Date.now(),
            success: true,
            results: [result],
            totalTests: 1,
            automatedPassedTests: result.automatedResult?.passed ? 1 : 0,
            llmJudgePassedTests: result.llmJudgeResult?.passed ? 1 : 0,
            averageAutomatedScore: result.automatedResult?.evaluationReport?.overallScore || 0,
            averageLlmJudgeScore: result.llmJudgeResult?.overallScore || 0,
            totalTimeMs: (result.automatedResult?.ragTimeMs || 0) +
                         (result.automatedResult?.evaluationTimeMs || 0) +
                         (result.llmJudgeResult?.evaluationTimeMs || 0),
            llmJudgeAvailable: result.llmJudgeAvailable,
            timestamp: result.timestamp
          };
          this.expandedResultId = result.testCaseId;
          this.running = false;
          const automatedPassed = result.automatedResult?.passed;
          const llmPassed = result.llmJudgeResult?.passed;
          this.showMessage(`Automated: ${automatedPassed ? 'PASSED' : 'FAILED'}, LLM Judge: ${llmPassed ? 'PASSED' : 'FAILED'}`);
        },
        error: (err) => {
          this.error = 'Failed to run combined test: ' + (err.error?.message || err.message);
          this.running = false;
        }
      });
  }

  // Test Suite Management

  saveSuite(): void {
    if (!this.currentSuite.name) {
      this.showMessage('Please enter a suite name', true);
      return;
    }

    this.saving = true;
    this.currentSuite.evaluatorTypes = this.selectedEvaluatorTypes;

    this.evalService.saveTestSuite(this.currentSuite)
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: (saved) => {
          this.currentSuite = saved;
          this.loadTestSuites();
          this.saving = false;
          this.showMessage('Test suite saved');
        },
        error: (err) => {
          this.error = 'Failed to save suite: ' + (err.error?.message || err.message);
          this.saving = false;
        }
      });
  }

  loadSuite(suite: TestSuite): void {
    this.currentSuite = { ...suite, testCases: [...suite.testCases] };
    this.selectedEvaluatorTypes = suite.evaluatorTypes || [];
    this.lastRunResult = null;
  }

  newSuite(): void {
    this.currentSuite = this.evalService.createEmptyTestSuite();
    this.lastRunResult = null;
  }

  deleteSuite(suite: TestSuite): void {
    if (!suite.id) return;

    this.evalService.deleteTestSuite(suite.id)
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: () => {
          this.loadTestSuites();
          if (this.currentSuite.id === suite.id) {
            this.newSuite();
          }
          this.showMessage('Test suite deleted');
        },
        error: (err) => {
          this.showMessage('Failed to delete suite', true);
        }
      });
  }

  // Import/Export

  exportSuite(): void {
    const dataStr = JSON.stringify(this.currentSuite, null, 2);
    const blob = new Blob([dataStr], { type: 'application/json' });
    const url = URL.createObjectURL(blob);
    const link = document.createElement('a');
    link.href = url;
    link.download = `${this.currentSuite.name || 'test-suite'}.json`;
    link.click();
    URL.revokeObjectURL(url);
  }

  importSuite(event: Event): void {
    const input = event.target as HTMLInputElement;
    if (!input.files?.length) return;

    const file = input.files[0];
    const reader = new FileReader();
    reader.onload = (e) => {
      try {
        const suite = JSON.parse(e.target?.result as string) as TestSuite;
        suite.id = undefined; // Clear ID to create new
        this.currentSuite = suite;
        this.selectedEvaluatorTypes = suite.evaluatorTypes || [];
        this.showMessage('Test suite imported');
      } catch (err) {
        this.showMessage('Invalid JSON file', true);
      }
    };
    reader.readAsText(file);
    input.value = ''; // Reset for re-import
  }

  // Result Display Helpers

  getResultForTestCase(testCaseId: string): TestCaseResult | undefined {
    return this.lastRunResult?.results.find(r => r.testCaseId === testCaseId);
  }

  getCombinedResultForTestCase(testCaseId: string): CombinedEvalResult | undefined {
    return this.lastCombinedResult?.results.find(r => r.testCaseId === testCaseId);
  }

  getLlmJudgeResultForTestCase(testCaseId: string): LlmJudgeEvaluation | undefined {
    const combined = this.getCombinedResultForTestCase(testCaseId);
    return combined?.llmJudgeResult;
  }

  toggleResultExpansion(testCaseId: string): void {
    this.expandedResultId = this.expandedResultId === testCaseId ? null : testCaseId;
  }

  getScoreColor(score: number): string {
    if (score >= 0.8) return 'green';
    if (score >= 0.5) return 'orange';
    return 'red';
  }

  getSeverityColor(severity: string): string {
    switch (severity) {
      case 'CRITICAL': return 'red';
      case 'ERROR': return 'orangered';
      case 'WARNING': return 'orange';
      default: return 'gray';
    }
  }

  formatDuration(ms: number): string {
    if (ms < 1000) return `${ms}ms`;
    return `${(ms / 1000).toFixed(2)}s`;
  }

  // Helper methods

  private showMessage(message: string, isError = false): void {
    this.snackBar.open(message, 'Close', {
      duration: 3000,
      panelClass: isError ? ['error-snackbar'] : []
    });
  }

  trackByTestCase(index: number, testCase: TestCase): string {
    return testCase.id;
  }

  trackByResult(index: number, result: EvaluationResultDto): string {
    return result.evaluatorName;
  }

  // ═══════════════════════════════════════════════════════════════════════════════
  // MANAGED EVAL INTEGRATION
  // ═══════════════════════════════════════════════════════════════════════════════

  loadManagedSuites(): void {
    if (!this.activeFactSheetId) return;

    this.managedEvalService.getSuitesForFactSheet(this.activeFactSheetId)
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: (suites) => {
          this.managedSuites = suites;
        },
        error: (err) => {
          console.error('Failed to load managed eval suites:', err);
        }
      });
  }

  saveToManagedEval(testCase: TestCase): void {
    if (!this.selectedManagedSuiteId) {
      this.showMessage('Please select a managed eval suite first', true);
      return;
    }

    const request: CreateTestCaseRequest = {
      name: testCase.prompt?.substring(0, 50) || 'Imported Test Case',
      description: `Imported from Eval Debugger suite: ${this.currentSuite.name}`,
      factSheetId: this.activeFactSheetId ?? undefined,
      query: testCase.prompt || '',
      expectedAnswer: testCase.expectedAnswer,
      evaluationTypes: this.selectedEvaluatorTypes,
      tags: ['imported-from-debugger'],
      priority: 3,
      enabled: true,
      timeoutMs: 30000
    };

    this.managedEvalService.createTestCase(this.selectedManagedSuiteId, request)
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: () => {
          this.showMessage('Test case saved to managed eval suite');
        },
        error: (err) => {
          this.showMessage('Failed to save to managed eval: ' + (err.error?.message || err.message), true);
        }
      });
  }

  saveAllToManagedEval(): void {
    if (!this.selectedManagedSuiteId) {
      this.showMessage('Please select a managed eval suite first', true);
      return;
    }

    if (this.currentSuite.testCases.length === 0) {
      this.showMessage('No test cases to save', true);
      return;
    }

    let savedCount = 0;
    const total = this.currentSuite.testCases.length;

    this.currentSuite.testCases.forEach((testCase) => {
      const request: CreateTestCaseRequest = {
        name: testCase.prompt?.substring(0, 50) || 'Imported Test Case',
        description: `Imported from Eval Debugger suite: ${this.currentSuite.name}`,
        factSheetId: this.activeFactSheetId ?? undefined,
        query: testCase.prompt || '',
        expectedAnswer: testCase.expectedAnswer,
        evaluationTypes: this.selectedEvaluatorTypes,
        tags: ['imported-from-debugger'],
        priority: 3,
        enabled: true,
        timeoutMs: 30000
      };

      this.managedEvalService.createTestCase(this.selectedManagedSuiteId!, request)
        .pipe(takeUntil(this.destroy$))
        .subscribe({
          next: () => {
            savedCount++;
            if (savedCount === total) {
              this.showMessage(`Saved ${savedCount} test cases to managed eval suite`);
            }
          },
          error: (err) => {
            console.error('Failed to save test case:', err);
          }
        });
    });
  }
}
