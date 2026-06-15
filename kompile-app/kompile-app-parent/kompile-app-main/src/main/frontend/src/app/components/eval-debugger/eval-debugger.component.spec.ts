/*
 *   Copyright 2025 Kompile Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
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

import {
  ComponentFixture,
  TestBed,
  fakeAsync,
  tick
} from '@angular/core/testing';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { NO_ERRORS_SCHEMA } from '@angular/core';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';
import { MatSnackBar } from '@angular/material/snack-bar';
import { MatDialog } from '@angular/material/dialog';
import { of, throwError, BehaviorSubject } from 'rxjs';

import { EvalDebuggerComponent } from './eval-debugger.component';
import { EvalDebuggerService } from '../../services/eval-debugger.service';
import { ManagedEvalService } from '../../services/managed-eval.service';
import { FactSheetService } from '../../services/fact-sheet.service';
import {
  EvalDebuggerStatus,
  EvaluatorTypeInfo,
  TestCase,
  TestCaseResult,
  BatchTestResult,
  TestSuite,
  LlmProviderInfo,
  CombinedEvalResult,
  BatchCombinedResult
} from '../../models/eval-debugger.models';
import { EvalSuite } from '../../models/managed-eval.models';
import { FactSheet } from '../../models/api-models';

// ─────────────────────────────────────────────────────────────────────────────
// Test Data Helpers
// ─────────────────────────────────────────────────────────────────────────────

const makeStatus = (overrides: Partial<EvalDebuggerStatus> = {}): EvalDebuggerStatus => ({
  available: true,
  evaluationAvailable: true,
  evaluatorCount: 2,
  llmJudgeAvailable: false,
  llmProviderCount: 0,
  message: 'Ready',
  ...overrides
});

const makeTestCase = (overrides: Partial<TestCase> = {}): TestCase => ({
  id: 'tc_001',
  prompt: 'What is RAG?',
  expectedAnswer: 'Retrieval Augmented Generation',
  evaluatorTypes: ['RELEVANCY'],
  threshold: 0.5,
  maxDocuments: 5,
  ...overrides
});

const makeTestCaseResult = (overrides: Partial<TestCaseResult> = {}): TestCaseResult => ({
  testCaseId: 'tc_001',
  prompt: 'What is RAG?',
  expectedAnswer: 'RAG answer',
  actualAnswer: 'Retrieval Augmented Generation',
  retrievedDocuments: ['doc1'],
  success: true,
  passed: true,
  ragTimeMs: 100,
  evaluationTimeMs: 50,
  timestamp: '2025-01-01T00:00:00Z',
  ...overrides
});

const makeBatchResult = (overrides: Partial<BatchTestResult> = {}): BatchTestResult => ({
  runId: 'run_001',
  success: true,
  results: [makeTestCaseResult()],
  totalTests: 1,
  passedTests: 1,
  failedTests: 0,
  averageScore: 0.9,
  totalTimeMs: 150,
  timestamp: '2025-01-01T00:00:00Z',
  ...overrides
});

const makeTestSuite = (overrides: Partial<TestSuite> = {}): TestSuite => ({
  id: 'suite_001',
  name: 'Test Suite',
  description: 'Suite description',
  testCases: [makeTestCase()],
  evaluatorTypes: ['RELEVANCY'],
  ...overrides
});

const makeEvaluatorType = (overrides: Partial<EvaluatorTypeInfo> = {}): EvaluatorTypeInfo => ({
  type: 'RELEVANCY',
  name: 'Relevancy',
  description: 'Checks relevancy',
  available: true,
  ...overrides
});

const makeLlmProvider = (overrides: Partial<LlmProviderInfo> = {}): LlmProviderInfo => ({
  id: 'openai',
  name: 'OpenAI',
  description: 'OpenAI provider',
  available: true,
  supportsJudge: true,
  models: [{ id: 'gpt-4', name: 'GPT-4', description: '', supportsTools: true }],
  ...overrides
});

const makeEvalSuite = (overrides: Partial<EvalSuite> = {}): EvalSuite => ({
  id: 'managed_suite_001',
  name: 'Managed Suite',
  enabled: true,
  requiredPassRate: 0.8,
  testCaseCount: 2,
  ...overrides
});

const makeEmptyTestSuite = (): TestSuite => ({
  name: 'New Test Suite',
  description: '',
  testCases: [],
  evaluatorTypes: []
});

// ─────────────────────────────────────────────────────────────────────────────
// Spec
// ─────────────────────────────────────────────────────────────────────────────

describe('EvalDebuggerComponent', () => {
  let component: EvalDebuggerComponent;
  let fixture: ComponentFixture<EvalDebuggerComponent>;
  let evalServiceSpy: jasmine.SpyObj<EvalDebuggerService>;
  let managedEvalServiceSpy: jasmine.SpyObj<ManagedEvalService>;
  let factSheetServiceSpy: jasmine.SpyObj<FactSheetService>;
  let snackBarSpy: jasmine.SpyObj<MatSnackBar>;
  let dialogSpy: jasmine.SpyObj<MatDialog>;

  let activeSheetSubject: BehaviorSubject<FactSheet | null>;

  beforeEach(async () => {
    activeSheetSubject = new BehaviorSubject<FactSheet | null>(null);

    evalServiceSpy = jasmine.createSpyObj('EvalDebuggerService', [
      'getStatus',
      'getEvaluatorTypes',
      'getTestSuites',
      'getLlmProviders',
      'runSingleTest',
      'runBatchTests',
      'runCombinedTest',
      'runBatchCombinedTests',
      'saveTestSuite',
      'deleteTestSuite',
      'createEmptyTestCase',
      'createEmptyTestSuite'
    ]);
    managedEvalServiceSpy = jasmine.createSpyObj('ManagedEvalService', [
      'getSuitesForFactSheet',
      'createTestCase'
    ]);
    factSheetServiceSpy = jasmine.createSpyObj('FactSheetService', [], {
      activeSheet$: activeSheetSubject.asObservable()
    });
    snackBarSpy = jasmine.createSpyObj('MatSnackBar', ['open']);
    dialogSpy = jasmine.createSpyObj('MatDialog', ['open']);

    // Default responses
    evalServiceSpy.createEmptyTestSuite.and.returnValue(makeEmptyTestSuite());
    evalServiceSpy.createEmptyTestCase.and.returnValue(makeTestCase({ id: 'tc_new' }));
    evalServiceSpy.getStatus.and.returnValue(of(makeStatus()));
    evalServiceSpy.getEvaluatorTypes.and.returnValue(of([makeEvaluatorType()]));
    evalServiceSpy.getTestSuites.and.returnValue(of([makeTestSuite()]));
    evalServiceSpy.getLlmProviders.and.returnValue(of([makeLlmProvider()]));
    managedEvalServiceSpy.getSuitesForFactSheet.and.returnValue(of([makeEvalSuite()]));

    await TestBed.configureTestingModule({
      imports: [NoopAnimationsModule]
    })
      .overrideComponent(EvalDebuggerComponent, {
        set: {
          imports: [CommonModule, FormsModule],
          template: '<div></div>',
          schemas: [NO_ERRORS_SCHEMA]
        }
      })
      .overrideProvider(EvalDebuggerService, { useValue: evalServiceSpy })
      .overrideProvider(ManagedEvalService, { useValue: managedEvalServiceSpy })
      .overrideProvider(FactSheetService, { useValue: factSheetServiceSpy })
      .overrideProvider(MatSnackBar, { useValue: snackBarSpy })
      .overrideProvider(MatDialog, { useValue: dialogSpy })
      .compileComponents();

    fixture = TestBed.createComponent(EvalDebuggerComponent);
    component = fixture.componentInstance;
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // Creation & Init
  // ─────────────────────────────────────────────────────────────────────────────

  it('should be created', () => {
    expect(component).toBeTruthy();
  });

  it('should have a currentSuite initialized in the constructor', () => {
    expect(component.currentSuite).toBeTruthy();
    expect(component.currentSuite.name).toBe('New Test Suite');
  });

  it('should call loadInitialData on ngOnInit', () => {
    spyOn(component, 'loadInitialData');
    component.ngOnInit();
    expect(component.loadInitialData).toHaveBeenCalled();
  });

  it('should complete destroy$ on ngOnDestroy', () => {
    fixture.detectChanges();
    const nextSpy = spyOn((component as any).destroy$, 'next').and.callThrough();
    const completeSpy = spyOn((component as any).destroy$, 'complete').and.callThrough();
    component.ngOnDestroy();
    expect(nextSpy).toHaveBeenCalled();
    expect(completeSpy).toHaveBeenCalled();
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // loadInitialData()
  // ─────────────────────────────────────────────────────────────────────────────

  describe('loadInitialData()', () => {
    it('should set loading=false and populate status on success', fakeAsync(() => {
      fixture.detectChanges();
      tick();
      expect(component.loading).toBeFalse();
      expect(component.status).toBeTruthy();
      expect(component.status!.available).toBeTrue();
    }));

    it('should call loadEvaluatorTypes and loadTestSuites when status.available is true', fakeAsync(() => {
      spyOn(component, 'loadEvaluatorTypes');
      spyOn(component, 'loadTestSuites');
      fixture.detectChanges();
      tick();
      expect(component.loadEvaluatorTypes).toHaveBeenCalled();
      expect(component.loadTestSuites).toHaveBeenCalled();
    }));

    it('should call loadLlmProviders when status.llmJudgeAvailable is true', fakeAsync(() => {
      evalServiceSpy.getStatus.and.returnValue(of(makeStatus({ llmJudgeAvailable: true })));
      spyOn(component, 'loadLlmProviders');
      fixture.detectChanges();
      tick();
      expect(component.loadLlmProviders).toHaveBeenCalled();
    }));

    it('should NOT call loadLlmProviders when status.llmJudgeAvailable is false', fakeAsync(() => {
      evalServiceSpy.getStatus.and.returnValue(of(makeStatus({ llmJudgeAvailable: false })));
      spyOn(component, 'loadLlmProviders');
      fixture.detectChanges();
      tick();
      expect(component.loadLlmProviders).not.toHaveBeenCalled();
    }));

    it('should set error and loading=false on getStatus failure', fakeAsync(() => {
      evalServiceSpy.getStatus.and.returnValue(throwError(() => ({ message: 'Network Error' })));
      fixture.detectChanges();
      tick();
      expect(component.error).toContain('Failed to load eval debugger status');
      expect(component.loading).toBeFalse();
    }));

    it('should load managed suites when active fact sheet has an id', fakeAsync(() => {
      spyOn(component, 'loadManagedSuites');
      fixture.detectChanges();
      activeSheetSubject.next({ id: 5, name: 'Active Sheet' } as FactSheet);
      tick();
      expect(component.activeFactSheetId).toBe(5);
      expect(component.loadManagedSuites).toHaveBeenCalled();
    }));
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // loadEvaluatorTypes()
  // ─────────────────────────────────────────────────────────────────────────────

  describe('loadEvaluatorTypes()', () => {
    it('should populate evaluatorTypes and auto-select available ones', fakeAsync(() => {
      evalServiceSpy.getEvaluatorTypes.and.returnValue(of([
        makeEvaluatorType({ type: 'RELEVANCY', available: true }),
        makeEvaluatorType({ type: 'FAITHFULNESS', available: true }),
        makeEvaluatorType({ type: 'HALLUCINATION', available: false })
      ]));
      fixture.detectChanges();
      tick();
      expect(component.evaluatorTypes.length).toBe(3);
      expect(component.selectedEvaluatorTypes).toEqual(['RELEVANCY', 'FAITHFULNESS']);
      expect(component.selectedEvaluatorTypes).not.toContain('HALLUCINATION');
    }));

    it('should handle errors on loadEvaluatorTypes gracefully', fakeAsync(() => {
      evalServiceSpy.getEvaluatorTypes.and.returnValue(throwError(() => new Error('Failed')));
      spyOn(console, 'error');
      fixture.detectChanges();
      tick();
      expect(console.error).toHaveBeenCalled();
      expect(component.evaluatorTypes).toEqual([]);
    }));
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // loadLlmProviders()
  // ─────────────────────────────────────────────────────────────────────────────

  describe('loadLlmProviders()', () => {
    it('should populate llmProviders with available providers', fakeAsync(() => {
      evalServiceSpy.getStatus.and.returnValue(of(makeStatus({ llmJudgeAvailable: true })));
      evalServiceSpy.getLlmProviders.and.returnValue(of([
        makeLlmProvider({ id: 'openai', available: true }),
        makeLlmProvider({ id: 'anthropic', available: false })
      ]));
      fixture.detectChanges();
      tick();
      expect(component.llmProviders.length).toBe(1);
      expect(component.llmProviders[0].id).toBe('openai');
    }));

    it('should auto-select first available provider and model', fakeAsync(() => {
      evalServiceSpy.getStatus.and.returnValue(of(makeStatus({ llmJudgeAvailable: true })));
      evalServiceSpy.getLlmProviders.and.returnValue(of([
        makeLlmProvider({ id: 'openai', available: true })
      ]));
      fixture.detectChanges();
      tick();
      expect(component.selectedLlmProviderId).toBe('openai');
      expect(component.selectedLlmModelId).toBe('gpt-4');
    }));

    it('should handle errors on loadLlmProviders gracefully', fakeAsync(() => {
      evalServiceSpy.getStatus.and.returnValue(of(makeStatus({ llmJudgeAvailable: true })));
      evalServiceSpy.getLlmProviders.and.returnValue(throwError(() => new Error('Failed')));
      spyOn(console, 'error');
      fixture.detectChanges();
      tick();
      expect(console.error).toHaveBeenCalled();
      expect(component.llmProviders).toEqual([]);
    }));
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // onProviderChange()
  // ─────────────────────────────────────────────────────────────────────────────

  describe('onProviderChange()', () => {
    beforeEach(fakeAsync(() => {
      evalServiceSpy.getStatus.and.returnValue(of(makeStatus({ llmJudgeAvailable: true })));
      evalServiceSpy.getLlmProviders.and.returnValue(of([
        makeLlmProvider({ id: 'openai', models: [{ id: 'gpt-4', name: 'GPT-4', description: '', supportsTools: true }] }),
        makeLlmProvider({ id: 'anthropic', models: [] })
      ]));
      fixture.detectChanges();
      tick();
    }));

    it('should set selectedLlmModelId to first model when provider has models', () => {
      component.selectedLlmProviderId = 'openai';
      component.onProviderChange();
      expect(component.selectedLlmModelId).toBe('gpt-4');
    });

    it('should set selectedLlmModelId to null when provider has no models', () => {
      component.selectedLlmProviderId = 'anthropic';
      component.onProviderChange();
      expect(component.selectedLlmModelId).toBeNull();
    });

    it('should set selectedLlmModelId to null for unknown provider', () => {
      component.selectedLlmProviderId = 'nonexistent';
      component.onProviderChange();
      expect(component.selectedLlmModelId).toBeNull();
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // getSelectedProviderModels()
  // ─────────────────────────────────────────────────────────────────────────────

  describe('getSelectedProviderModels()', () => {
    beforeEach(fakeAsync(() => {
      evalServiceSpy.getStatus.and.returnValue(of(makeStatus({ llmJudgeAvailable: true })));
      evalServiceSpy.getLlmProviders.and.returnValue(of([
        makeLlmProvider({ id: 'openai', available: true })
      ]));
      fixture.detectChanges();
      tick();
    }));

    it('should return models for the selected provider', () => {
      component.selectedLlmProviderId = 'openai';
      const models = component.getSelectedProviderModels();
      expect(models.length).toBe(1);
      expect(models[0].id).toBe('gpt-4');
    });

    it('should return empty array when no provider is selected', () => {
      component.selectedLlmProviderId = null;
      const models = component.getSelectedProviderModels();
      expect(models).toEqual([]);
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // addTestCase()
  // ─────────────────────────────────────────────────────────────────────────────

  describe('addTestCase()', () => {
    it('should add a new test case to currentSuite.testCases', () => {
      fixture.detectChanges();
      const initialCount = component.currentSuite.testCases.length;
      component.addTestCase();
      expect(component.currentSuite.testCases.length).toBe(initialCount + 1);
    });

    it('should use createEmptyTestCase to create the new case', () => {
      fixture.detectChanges();
      component.addTestCase();
      expect(evalServiceSpy.createEmptyTestCase).toHaveBeenCalled();
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // removeTestCase()
  // ─────────────────────────────────────────────────────────────────────────────

  describe('removeTestCase()', () => {
    it('should remove the test case at the given index', () => {
      fixture.detectChanges();
      component.currentSuite.testCases = [
        makeTestCase({ id: 'tc_a' }),
        makeTestCase({ id: 'tc_b' }),
        makeTestCase({ id: 'tc_c' })
      ];
      component.removeTestCase(1);
      expect(component.currentSuite.testCases.length).toBe(2);
      expect(component.currentSuite.testCases.map(tc => tc.id)).toEqual(['tc_a', 'tc_c']);
    });

    it('should remove the first test case when index is 0', () => {
      fixture.detectChanges();
      component.currentSuite.testCases = [makeTestCase({ id: 'tc_a' }), makeTestCase({ id: 'tc_b' })];
      component.removeTestCase(0);
      expect(component.currentSuite.testCases[0].id).toBe('tc_b');
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // duplicateTestCase()
  // ─────────────────────────────────────────────────────────────────────────────

  describe('duplicateTestCase()', () => {
    it('should insert a copy of the test case after the original', () => {
      fixture.detectChanges();
      component.currentSuite.testCases = [
        makeTestCase({ id: 'tc_a', prompt: 'Q1' }),
        makeTestCase({ id: 'tc_b', prompt: 'Q2' })
      ];
      component.duplicateTestCase(0);
      expect(component.currentSuite.testCases.length).toBe(3);
      expect(component.currentSuite.testCases[1].prompt).toBe('Q1');
    });

    it('should generate a new id for the duplicated case', () => {
      fixture.detectChanges();
      component.currentSuite.testCases = [makeTestCase({ id: 'tc_original' })];
      component.duplicateTestCase(0);
      expect(component.currentSuite.testCases[1].id).not.toBe('tc_original');
      expect(component.currentSuite.testCases[1].id).toMatch(/^tc_/);
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // moveTestCase()
  // ─────────────────────────────────────────────────────────────────────────────

  describe('moveTestCase()', () => {
    beforeEach(() => {
      fixture.detectChanges();
      component.currentSuite.testCases = [
        makeTestCase({ id: 'tc_a' }),
        makeTestCase({ id: 'tc_b' }),
        makeTestCase({ id: 'tc_c' })
      ];
    });

    it('should move a test case up', () => {
      component.moveTestCase(1, 'up');
      expect(component.currentSuite.testCases[0].id).toBe('tc_b');
      expect(component.currentSuite.testCases[1].id).toBe('tc_a');
    });

    it('should move a test case down', () => {
      component.moveTestCase(1, 'down');
      expect(component.currentSuite.testCases[1].id).toBe('tc_c');
      expect(component.currentSuite.testCases[2].id).toBe('tc_b');
    });

    it('should not move the first case up (no-op)', () => {
      const order = component.currentSuite.testCases.map(tc => tc.id);
      component.moveTestCase(0, 'up');
      expect(component.currentSuite.testCases.map(tc => tc.id)).toEqual(order);
    });

    it('should not move the last case down (no-op)', () => {
      const order = component.currentSuite.testCases.map(tc => tc.id);
      component.moveTestCase(2, 'down');
      expect(component.currentSuite.testCases.map(tc => tc.id)).toEqual(order);
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // runAllTests()
  // ─────────────────────────────────────────────────────────────────────────────

  describe('runAllTests()', () => {
    it('should show error snackbar when no test cases exist', () => {
      fixture.detectChanges();
      component.currentSuite.testCases = [];
      component.runAllTests();
      expect(snackBarSpy.open).toHaveBeenCalledWith('No test cases to run', 'Close', jasmine.any(Object));
      expect(evalServiceSpy.runBatchTests).not.toHaveBeenCalled();
    });

    it('should call runBatchTests and populate lastRunResult on success', fakeAsync(() => {
      evalServiceSpy.runBatchTests.and.returnValue(of(makeBatchResult()));
      fixture.detectChanges();
      component.currentSuite.testCases = [makeTestCase()];
      component.selectedEvaluatorTypes = ['RELEVANCY'];
      component.runMode = 'automated';
      component.runAllTests();
      tick();
      expect(evalServiceSpy.runBatchTests).toHaveBeenCalled();
      expect(component.lastRunResult).toBeTruthy();
      expect(component.lastRunResult!.passedTests).toBe(1);
      expect(component.running).toBeFalse();
    }));

    it('should show success snackbar with pass count', fakeAsync(() => {
      evalServiceSpy.runBatchTests.and.returnValue(of(makeBatchResult({ passedTests: 3, totalTests: 5 })));
      fixture.detectChanges();
      component.currentSuite.testCases = [makeTestCase()];
      component.runMode = 'automated';
      component.runAllTests();
      tick();
      expect(snackBarSpy.open).toHaveBeenCalledWith('Completed: 3/5 passed', 'Close', jasmine.any(Object));
    }));

    it('should set error and running=false on runBatchTests failure', fakeAsync(() => {
      evalServiceSpy.runBatchTests.and.returnValue(throwError(() => ({ message: 'Network Error' })));
      fixture.detectChanges();
      component.currentSuite.testCases = [makeTestCase()];
      component.runMode = 'automated';
      component.runAllTests();
      tick();
      expect(component.error).toContain('Failed to run tests');
      expect(component.running).toBeFalse();
    }));

    it('should call runAllCombinedTests when runMode is combined and llmJudgeAvailable', fakeAsync(() => {
      evalServiceSpy.getStatus.and.returnValue(of(makeStatus({ llmJudgeAvailable: true })));
      spyOn(component, 'runAllCombinedTests');
      fixture.detectChanges();
      tick();
      component.currentSuite.testCases = [makeTestCase()];
      component.runMode = 'combined';
      component.runAllTests();
      expect(component.runAllCombinedTests).toHaveBeenCalled();
    }));
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // runAllCombinedTests()
  // ─────────────────────────────────────────────────────────────────────────────

  describe('runAllCombinedTests()', () => {
    const makeCombinedBatch = (overrides: Partial<BatchCombinedResult> = {}): BatchCombinedResult => ({
      runId: 'batch_combined_001',
      success: true,
      results: [],
      totalTests: 2,
      automatedPassedTests: 2,
      llmJudgePassedTests: 1,
      averageAutomatedScore: 0.8,
      averageLlmJudgeScore: 0.7,
      totalTimeMs: 3000,
      llmJudgeAvailable: true,
      timestamp: '2025-01-01T00:00:00Z',
      ...overrides
    });

    it('should populate lastCombinedResult on success', fakeAsync(() => {
      evalServiceSpy.runBatchCombinedTests.and.returnValue(of(makeCombinedBatch()));
      fixture.detectChanges();
      component.currentSuite.testCases = [makeTestCase()];
      component.runAllCombinedTests();
      tick();
      expect(component.lastCombinedResult).toBeTruthy();
      expect(component.running).toBeFalse();
    }));

    it('should also populate lastRunResult from the automatedResult part', fakeAsync(() => {
      const automated = makeTestCaseResult();
      evalServiceSpy.runBatchCombinedTests.and.returnValue(of(makeCombinedBatch({
        results: [{
          success: true,
          testCaseId: 'tc_001',
          llmJudgeAvailable: true,
          timestamp: '2025-01-01T00:00:00Z',
          automatedResult: automated
        }]
      })));
      fixture.detectChanges();
      component.currentSuite.testCases = [makeTestCase()];
      component.runAllCombinedTests();
      tick();
      expect(component.lastRunResult).toBeTruthy();
      expect(component.lastRunResult!.results[0].testCaseId).toBe('tc_001');
    }));

    it('should set error on runBatchCombinedTests failure', fakeAsync(() => {
      evalServiceSpy.runBatchCombinedTests.and.returnValue(throwError(() => ({ message: 'Error' })));
      fixture.detectChanges();
      component.runAllCombinedTests();
      tick();
      expect(component.error).toContain('Failed to run combined tests');
      expect(component.running).toBeFalse();
    }));
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // runSingleTest()
  // ─────────────────────────────────────────────────────────────────────────────

  describe('runSingleTest()', () => {
    it('should call runSingleTest on service and populate lastRunResult', fakeAsync(() => {
      const result = makeTestCaseResult({ passed: true });
      evalServiceSpy.runSingleTest.and.returnValue(of(result));
      fixture.detectChanges();
      const tc = makeTestCase();
      component.runMode = 'automated';
      component.runSingleTest(tc);
      tick();
      expect(evalServiceSpy.runSingleTest).toHaveBeenCalled();
      expect(component.lastRunResult).toBeTruthy();
      expect(component.lastRunResult!.results[0].testCaseId).toBe('tc_001');
      expect(component.running).toBeFalse();
    }));

    it('should show passed snackbar when test passes', fakeAsync(() => {
      evalServiceSpy.runSingleTest.and.returnValue(of(makeTestCaseResult({ passed: true })));
      fixture.detectChanges();
      component.runMode = 'automated';
      component.runSingleTest(makeTestCase());
      tick();
      expect(snackBarSpy.open).toHaveBeenCalledWith('Test passed!', 'Close', jasmine.any(Object));
    }));

    it('should show failed snackbar when test fails', fakeAsync(() => {
      evalServiceSpy.runSingleTest.and.returnValue(of(makeTestCaseResult({ passed: false })));
      fixture.detectChanges();
      component.runMode = 'automated';
      component.runSingleTest(makeTestCase());
      tick();
      expect(snackBarSpy.open).toHaveBeenCalledWith('Test failed', 'Close', jasmine.any(Object));
    }));

    it('should set expandedResultId to the test case id', fakeAsync(() => {
      evalServiceSpy.runSingleTest.and.returnValue(of(makeTestCaseResult({ testCaseId: 'tc_expand' })));
      fixture.detectChanges();
      component.runMode = 'automated';
      component.runSingleTest(makeTestCase({ id: 'tc_expand' }));
      tick();
      expect(component.expandedResultId).toBe('tc_expand');
    }));

    it('should set error and running=false on failure', fakeAsync(() => {
      evalServiceSpy.runSingleTest.and.returnValue(throwError(() => ({ message: 'Error' })));
      fixture.detectChanges();
      component.runMode = 'automated';
      component.runSingleTest(makeTestCase());
      tick();
      expect(component.error).toContain('Failed to run test');
      expect(component.running).toBeFalse();
    }));

    it('should call runCombinedSingleTest when runMode is combined and llmJudgeAvailable', fakeAsync(() => {
      evalServiceSpy.getStatus.and.returnValue(of(makeStatus({ llmJudgeAvailable: true })));
      spyOn(component, 'runCombinedSingleTest');
      fixture.detectChanges();
      tick();
      const tc = makeTestCase();
      component.runMode = 'combined';
      component.runSingleTest(tc);
      expect(component.runCombinedSingleTest).toHaveBeenCalledWith(tc);
    }));
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // runCombinedSingleTest()
  // ─────────────────────────────────────────────────────────────────────────────

  describe('runCombinedSingleTest()', () => {
    const makeCombinedResult = (overrides: Partial<CombinedEvalResult> = {}): CombinedEvalResult => ({
      success: true,
      testCaseId: 'tc_001',
      llmJudgeAvailable: true,
      timestamp: '2025-01-01T00:00:00Z',
      automatedResult: makeTestCaseResult({ passed: true }),
      llmJudgeResult: {
        passed: true,
        overallScore: 0.9,
        summary: 'Good',
        criteria: {},
        recommendations: [],
        evaluationTimeMs: 1000,
        judgeModel: 'gpt-4',
        judgeProvider: 'openai'
      },
      ...overrides
    });

    it('should call runCombinedTest and populate lastCombinedResult', fakeAsync(() => {
      evalServiceSpy.runCombinedTest.and.returnValue(of(makeCombinedResult()));
      fixture.detectChanges();
      component.runCombinedSingleTest(makeTestCase());
      tick();
      expect(evalServiceSpy.runCombinedTest).toHaveBeenCalled();
      expect(component.lastCombinedResult).toBeTruthy();
      expect(component.running).toBeFalse();
    }));

    it('should set error on runCombinedTest failure', fakeAsync(() => {
      evalServiceSpy.runCombinedTest.and.returnValue(throwError(() => ({ message: 'err' })));
      fixture.detectChanges();
      component.runCombinedSingleTest(makeTestCase());
      tick();
      expect(component.error).toContain('Failed to run combined test');
      expect(component.running).toBeFalse();
    }));
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // saveSuite()
  // ─────────────────────────────────────────────────────────────────────────────

  describe('saveSuite()', () => {
    it('should show error when suite name is empty', () => {
      fixture.detectChanges();
      component.currentSuite.name = '';
      component.saveSuite();
      expect(snackBarSpy.open).toHaveBeenCalledWith('Please enter a suite name', 'Close', jasmine.any(Object));
      expect(evalServiceSpy.saveTestSuite).not.toHaveBeenCalled();
    });

    it('should call saveTestSuite and update currentSuite on success', fakeAsync(() => {
      const savedSuite = makeTestSuite({ id: 'saved_suite' });
      evalServiceSpy.saveTestSuite.and.returnValue(of(savedSuite));
      fixture.detectChanges();
      component.currentSuite.name = 'Valid Name';
      component.saveSuite();
      tick();
      expect(evalServiceSpy.saveTestSuite).toHaveBeenCalled();
      expect(component.currentSuite.id).toBe('saved_suite');
      expect(component.saving).toBeFalse();
      expect(snackBarSpy.open).toHaveBeenCalledWith('Test suite saved', 'Close', jasmine.any(Object));
    }));

    it('should set error and saving=false on saveTestSuite failure', fakeAsync(() => {
      evalServiceSpy.saveTestSuite.and.returnValue(throwError(() => ({ message: 'Save failed' })));
      fixture.detectChanges();
      component.currentSuite.name = 'Valid Name';
      component.saveSuite();
      tick();
      expect(component.error).toContain('Failed to save suite');
      expect(component.saving).toBeFalse();
    }));
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // loadSuite()
  // ─────────────────────────────────────────────────────────────────────────────

  describe('loadSuite()', () => {
    it('should set currentSuite and selectedEvaluatorTypes from the given suite', () => {
      fixture.detectChanges();
      const suite = makeTestSuite({ evaluatorTypes: ['RELEVANCY', 'FAITHFULNESS'] });
      component.loadSuite(suite);
      expect(component.currentSuite.name).toBe('Test Suite');
      expect(component.selectedEvaluatorTypes).toEqual(['RELEVANCY', 'FAITHFULNESS']);
    });

    it('should clear lastRunResult when loading a suite', () => {
      fixture.detectChanges();
      component.lastRunResult = makeBatchResult();
      component.loadSuite(makeTestSuite());
      expect(component.lastRunResult).toBeNull();
    });

    it('should deep-copy test cases to avoid mutation', () => {
      fixture.detectChanges();
      const original = makeTestSuite({ testCases: [makeTestCase({ id: 'tc_original' })] });
      component.loadSuite(original);
      component.currentSuite.testCases.push(makeTestCase({ id: 'tc_extra' }));
      expect(original.testCases.length).toBe(1);
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // newSuite()
  // ─────────────────────────────────────────────────────────────────────────────

  describe('newSuite()', () => {
    it('should reset currentSuite to an empty suite', () => {
      fixture.detectChanges();
      component.currentSuite = makeTestSuite({ name: 'Old Suite' });
      component.newSuite();
      expect(component.currentSuite.name).toBe('New Test Suite');
      expect(component.currentSuite.testCases.length).toBe(0);
    });

    it('should clear lastRunResult', () => {
      fixture.detectChanges();
      component.lastRunResult = makeBatchResult();
      component.newSuite();
      expect(component.lastRunResult).toBeNull();
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // deleteSuite()
  // ─────────────────────────────────────────────────────────────────────────────

  describe('deleteSuite()', () => {
    it('should not call deleteTestSuite when suite has no id', () => {
      fixture.detectChanges();
      const suite: TestSuite = { name: 'No ID Suite', testCases: [], evaluatorTypes: [] };
      component.deleteSuite(suite);
      expect(evalServiceSpy.deleteTestSuite).not.toHaveBeenCalled();
    });

    it('should call deleteTestSuite and reload suites on success', fakeAsync(() => {
      evalServiceSpy.deleteTestSuite.and.returnValue(of(void 0));
      fixture.detectChanges();
      const suite = makeTestSuite();
      component.deleteSuite(suite);
      tick();
      expect(evalServiceSpy.deleteTestSuite).toHaveBeenCalledWith('suite_001');
      expect(evalServiceSpy.getTestSuites).toHaveBeenCalled();
    }));

    it('should reset currentSuite when deleting the active suite', fakeAsync(() => {
      evalServiceSpy.deleteTestSuite.and.returnValue(of(void 0));
      fixture.detectChanges();
      const suite = makeTestSuite();
      component.currentSuite = { ...suite };
      component.deleteSuite(suite);
      tick();
      expect(component.currentSuite.id).toBeUndefined();
    }));

    it('should show error snackbar on delete failure', fakeAsync(() => {
      evalServiceSpy.deleteTestSuite.and.returnValue(throwError(() => new Error('Error')));
      fixture.detectChanges();
      component.deleteSuite(makeTestSuite());
      tick();
      expect(snackBarSpy.open).toHaveBeenCalledWith('Failed to delete suite', 'Close', jasmine.any(Object));
    }));
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // Result Display Helpers
  // ─────────────────────────────────────────────────────────────────────────────

  describe('getResultForTestCase()', () => {
    it('should return the result matching the test case id', () => {
      fixture.detectChanges();
      component.lastRunResult = makeBatchResult({ results: [makeTestCaseResult({ testCaseId: 'tc_search' })] });
      const found = component.getResultForTestCase('tc_search');
      expect(found).toBeTruthy();
      expect(found!.testCaseId).toBe('tc_search');
    });

    it('should return undefined when no result matches', () => {
      fixture.detectChanges();
      component.lastRunResult = makeBatchResult({ results: [] });
      expect(component.getResultForTestCase('nonexistent')).toBeUndefined();
    });

    it('should return undefined when lastRunResult is null', () => {
      fixture.detectChanges();
      component.lastRunResult = null;
      expect(component.getResultForTestCase('any')).toBeUndefined();
    });
  });

  describe('getCombinedResultForTestCase()', () => {
    it('should return combined result for given testCaseId', () => {
      fixture.detectChanges();
      component.lastCombinedResult = {
        runId: 'run_c',
        success: true,
        results: [{
          success: true,
          testCaseId: 'tc_c',
          llmJudgeAvailable: true,
          timestamp: '2025-01-01T00:00:00Z'
        }],
        totalTests: 1,
        automatedPassedTests: 1,
        llmJudgePassedTests: 1,
        averageAutomatedScore: 0.9,
        averageLlmJudgeScore: 0.85,
        totalTimeMs: 2000,
        llmJudgeAvailable: true,
        timestamp: '2025-01-01T00:00:00Z'
      };
      const found = component.getCombinedResultForTestCase('tc_c');
      expect(found).toBeTruthy();
      expect(found!.testCaseId).toBe('tc_c');
    });

    it('should return undefined when lastCombinedResult is null', () => {
      fixture.detectChanges();
      component.lastCombinedResult = null;
      expect(component.getCombinedResultForTestCase('tc')).toBeUndefined();
    });
  });

  describe('toggleResultExpansion()', () => {
    it('should set expandedResultId when it is null', () => {
      fixture.detectChanges();
      component.expandedResultId = null;
      component.toggleResultExpansion('tc_001');
      expect(component.expandedResultId as any).toBe('tc_001');
    });

    it('should clear expandedResultId when toggling the same id again', () => {
      fixture.detectChanges();
      component.expandedResultId = 'tc_001';
      component.toggleResultExpansion('tc_001');
      expect(component.expandedResultId).toBeNull();
    });

    it('should switch to a new id when toggling a different id', () => {
      fixture.detectChanges();
      component.expandedResultId = 'tc_001';
      component.toggleResultExpansion('tc_002');
      expect(component.expandedResultId).toBe('tc_002');
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // Display/Format Helpers
  // ─────────────────────────────────────────────────────────────────────────────

  describe('getScoreColor()', () => {
    it('should return green for scores >= 0.8', () => {
      expect(component.getScoreColor(0.8)).toBe('green');
      expect(component.getScoreColor(1.0)).toBe('green');
    });

    it('should return orange for scores >= 0.5 but < 0.8', () => {
      expect(component.getScoreColor(0.5)).toBe('orange');
      expect(component.getScoreColor(0.79)).toBe('orange');
    });

    it('should return red for scores < 0.5', () => {
      expect(component.getScoreColor(0.4)).toBe('red');
      expect(component.getScoreColor(0)).toBe('red');
    });
  });

  describe('getSeverityColor()', () => {
    it('should return red for CRITICAL severity', () => {
      expect(component.getSeverityColor('CRITICAL')).toBe('red');
    });

    it('should return orangered for ERROR severity', () => {
      expect(component.getSeverityColor('ERROR')).toBe('orangered');
    });

    it('should return orange for WARNING severity', () => {
      expect(component.getSeverityColor('WARNING')).toBe('orange');
    });

    it('should return gray for unknown severity', () => {
      expect(component.getSeverityColor('INFO')).toBe('gray');
      expect(component.getSeverityColor('unknown')).toBe('gray');
    });
  });

  describe('formatDuration()', () => {
    it('should format durations under 1000ms as Xms', () => {
      expect(component.formatDuration(0)).toBe('0ms');
      expect(component.formatDuration(500)).toBe('500ms');
      expect(component.formatDuration(999)).toBe('999ms');
    });

    it('should format durations 1000ms and over as X.XXs', () => {
      expect(component.formatDuration(1000)).toBe('1.00s');
      expect(component.formatDuration(2500)).toBe('2.50s');
      expect(component.formatDuration(10000)).toBe('10.00s');
    });
  });

  describe('trackByTestCase()', () => {
    it('should return the test case id', () => {
      const tc = makeTestCase({ id: 'tc_track_123' });
      expect(component.trackByTestCase(0, tc)).toBe('tc_track_123');
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // loadManagedSuites()
  // ─────────────────────────────────────────────────────────────────────────────

  describe('loadManagedSuites()', () => {
    it('should not call getSuitesForFactSheet when activeFactSheetId is null', () => {
      fixture.detectChanges();
      component.activeFactSheetId = null;
      component.loadManagedSuites();
      expect(managedEvalServiceSpy.getSuitesForFactSheet).not.toHaveBeenCalled();
    });

    it('should populate managedSuites when activeFactSheetId is set', fakeAsync(() => {
      managedEvalServiceSpy.getSuitesForFactSheet.and.returnValue(of([makeEvalSuite()]));
      fixture.detectChanges();
      component.activeFactSheetId = 42;
      component.loadManagedSuites();
      tick();
      expect(managedEvalServiceSpy.getSuitesForFactSheet).toHaveBeenCalledWith(42);
      expect(component.managedSuites.length).toBe(1);
    }));

    it('should handle error gracefully on loadManagedSuites', fakeAsync(() => {
      managedEvalServiceSpy.getSuitesForFactSheet.and.returnValue(throwError(() => new Error('Failed')));
      spyOn(console, 'error');
      fixture.detectChanges();
      component.activeFactSheetId = 42;
      component.loadManagedSuites();
      tick();
      expect(console.error).toHaveBeenCalled();
    }));
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // saveToManagedEval()
  // ─────────────────────────────────────────────────────────────────────────────

  describe('saveToManagedEval()', () => {
    it('should show error when no managed suite is selected', () => {
      fixture.detectChanges();
      component.selectedManagedSuiteId = null;
      component.saveToManagedEval(makeTestCase());
      expect(snackBarSpy.open).toHaveBeenCalledWith('Please select a managed eval suite first', 'Close', jasmine.any(Object));
      expect(managedEvalServiceSpy.createTestCase).not.toHaveBeenCalled();
    });

    it('should call createTestCase with correct request when suite is selected', fakeAsync(() => {
      managedEvalServiceSpy.createTestCase.and.returnValue(of({
        id: 'new_case',
        name: 'Test',
        query: 'What is RAG?',
        priority: 3,
        enabled: true,
        timeoutMs: 30000
      } as any));
      fixture.detectChanges();
      component.selectedManagedSuiteId = 'managed_suite_001';
      component.activeFactSheetId = 42;
      const tc = makeTestCase({ prompt: 'What is RAG?', expectedAnswer: 'RAG answer' });
      component.saveToManagedEval(tc);
      tick();
      expect(managedEvalServiceSpy.createTestCase).toHaveBeenCalledWith(
        'managed_suite_001',
        jasmine.objectContaining({
          query: 'What is RAG?',
          expectedAnswer: 'RAG answer',
          factSheetId: 42
        })
      );
      expect(snackBarSpy.open).toHaveBeenCalledWith('Test case saved to managed eval suite', 'Close', jasmine.any(Object));
    }));

    it('should show error snackbar when createTestCase fails', fakeAsync(() => {
      managedEvalServiceSpy.createTestCase.and.returnValue(throwError(() => ({ message: 'Failed' })));
      fixture.detectChanges();
      component.selectedManagedSuiteId = 'managed_suite_001';
      component.saveToManagedEval(makeTestCase());
      tick();
      expect(snackBarSpy.open).toHaveBeenCalledWith(jasmine.stringContaining('Failed to save'), 'Close', jasmine.any(Object));
    }));
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // saveAllToManagedEval()
  // ─────────────────────────────────────────────────────────────────────────────

  describe('saveAllToManagedEval()', () => {
    it('should show error when no managed suite is selected', () => {
      fixture.detectChanges();
      component.selectedManagedSuiteId = null;
      component.saveAllToManagedEval();
      expect(snackBarSpy.open).toHaveBeenCalledWith('Please select a managed eval suite first', 'Close', jasmine.any(Object));
    });

    it('should show error when there are no test cases', () => {
      fixture.detectChanges();
      component.selectedManagedSuiteId = 'suite_001';
      component.currentSuite.testCases = [];
      component.saveAllToManagedEval();
      expect(snackBarSpy.open).toHaveBeenCalledWith('No test cases to save', 'Close', jasmine.any(Object));
    });

    it('should call createTestCase for each test case in the suite', fakeAsync(() => {
      managedEvalServiceSpy.createTestCase.and.returnValue(of({
        id: 'c', name: 'n', query: 'q', priority: 3, enabled: true, timeoutMs: 30000
      } as any));
      fixture.detectChanges();
      component.selectedManagedSuiteId = 'suite_001';
      component.currentSuite.testCases = [makeTestCase({ id: 'tc_a' }), makeTestCase({ id: 'tc_b' })];
      component.saveAllToManagedEval();
      tick();
      expect(managedEvalServiceSpy.createTestCase).toHaveBeenCalledTimes(2);
    }));
  });
});
