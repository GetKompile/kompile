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

import { TestBed } from '@angular/core/testing';
import { HttpClientTestingModule, HttpTestingController } from '@angular/common/http/testing';

import { EvalDebuggerService } from './eval-debugger.service';
import {
  EvalDebuggerStatus,
  EvaluatorTypeInfo,
  TestCase,
  TestCaseResult,
  BatchTestRequest,
  BatchTestResult,
  TestSuite,
  TestRunResult,
  LlmProviderInfo,
  LlmJudgeRequest,
  LlmJudgeResult,
  CombinedEvalResult,
  BatchCombinedResult
} from '../models/eval-debugger.models';

describe('EvalDebuggerService', () => {
  let service: EvalDebuggerService;
  let httpMock: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [HttpClientTestingModule],
      providers: [EvalDebuggerService]
    });

    service = TestBed.inject(EvalDebuggerService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    httpMock.verify();
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // Creation
  // ─────────────────────────────────────────────────────────────────────────────

  it('should be created', () => {
    expect(service).toBeTruthy();
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // getStatus()
  // ─────────────────────────────────────────────────────────────────────────────

  describe('getStatus()', () => {
    it('should GET /eval-debugger/status and return status', () => {
      const mockStatus: EvalDebuggerStatus = {
        available: true,
        evaluationAvailable: true,
        evaluatorCount: 3,
        llmJudgeAvailable: true,
        llmProviderCount: 2,
        message: 'Eval debugger ready'
      };

      service.getStatus().subscribe(status => {
        expect(status).toEqual(mockStatus);
        expect(status.available).toBeTrue();
        expect(status.evaluatorCount).toBe(3);
      });

      const req = httpMock.expectOne(r => r.url.endsWith('/eval-debugger/status'));
      expect(req.request.method).toBe('GET');
      req.flush(mockStatus);
    });

    it('should handle server error on getStatus', () => {
      service.getStatus().subscribe({
        error: err => expect(err).toBeTruthy()
      });

      const req = httpMock.expectOne(r => r.url.endsWith('/eval-debugger/status'));
      req.flush('Server Error', { status: 500, statusText: 'Server Error' });
    });

    it('should return status with llmJudgeAvailable false when unavailable', () => {
      const mockStatus: EvalDebuggerStatus = {
        available: false,
        evaluationAvailable: false,
        evaluatorCount: 0,
        llmJudgeAvailable: false,
        llmProviderCount: 0,
        message: 'Eval debugger not configured'
      };

      service.getStatus().subscribe(status => {
        expect(status.available).toBeFalse();
        expect(status.llmJudgeAvailable).toBeFalse();
      });

      const req = httpMock.expectOne(r => r.url.endsWith('/eval-debugger/status'));
      req.flush(mockStatus);
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // getEvaluatorTypes()
  // ─────────────────────────────────────────────────────────────────────────────

  describe('getEvaluatorTypes()', () => {
    it('should GET /eval-debugger/evaluator-types and return types array', () => {
      const mockTypes: EvaluatorTypeInfo[] = [
        { type: 'RELEVANCY', name: 'Relevancy', description: 'Checks relevancy', available: true },
        { type: 'FAITHFULNESS', name: 'Faithfulness', description: 'Checks faithfulness', available: true },
        { type: 'HALLUCINATION', name: 'Hallucination', description: 'Detects hallucinations', available: false }
      ];

      service.getEvaluatorTypes().subscribe(types => {
        expect(types.length).toBe(3);
        expect(types[0].type).toBe('RELEVANCY');
        expect(types[2].available).toBeFalse();
      });

      const req = httpMock.expectOne(r => r.url.endsWith('/eval-debugger/evaluator-types'));
      expect(req.request.method).toBe('GET');
      req.flush(mockTypes);
    });

    it('should return empty array when no evaluators configured', () => {
      service.getEvaluatorTypes().subscribe(types => {
        expect(types.length).toBe(0);
      });

      const req = httpMock.expectOne(r => r.url.endsWith('/eval-debugger/evaluator-types'));
      req.flush([]);
    });

    it('should handle error on getEvaluatorTypes', () => {
      service.getEvaluatorTypes().subscribe({
        error: err => expect(err).toBeTruthy()
      });

      const req = httpMock.expectOne(r => r.url.endsWith('/eval-debugger/evaluator-types'));
      req.flush('Not Found', { status: 404, statusText: 'Not Found' });
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // runSingleTest()
  // ─────────────────────────────────────────────────────────────────────────────

  describe('runSingleTest()', () => {
    it('should POST to /eval-debugger/run-single with the test case', () => {
      const testCase: TestCase = {
        id: 'tc_abc123',
        prompt: 'What is RAG?',
        expectedAnswer: 'Retrieval Augmented Generation',
        evaluatorTypes: ['RELEVANCY'],
        threshold: 0.7,
        maxDocuments: 5
      };

      const mockResult: TestCaseResult = {
        testCaseId: 'tc_abc123',
        prompt: 'What is RAG?',
        expectedAnswer: 'Retrieval Augmented Generation',
        actualAnswer: 'RAG stands for Retrieval Augmented Generation',
        retrievedDocuments: ['doc1', 'doc2'],
        success: true,
        passed: true,
        ragTimeMs: 120,
        evaluationTimeMs: 80,
        timestamp: '2025-01-01T00:00:00Z'
      };

      service.runSingleTest(testCase).subscribe(result => {
        expect(result.testCaseId).toBe('tc_abc123');
        expect(result.passed).toBeTrue();
        expect(result.success).toBeTrue();
      });

      const req = httpMock.expectOne(r => r.url.endsWith('/eval-debugger/run-single'));
      expect(req.request.method).toBe('POST');
      expect(req.request.body.id).toBe('tc_abc123');
      expect(req.request.body.prompt).toBe('What is RAG?');
      req.flush(mockResult);
    });

    it('should handle failure result from runSingleTest', () => {
      const testCase: TestCase = {
        id: 'tc_fail',
        prompt: 'Unknown query',
        expectedAnswer: 'Specific answer',
        evaluatorTypes: ['RELEVANCY']
      };

      const mockResult: TestCaseResult = {
        testCaseId: 'tc_fail',
        prompt: 'Unknown query',
        expectedAnswer: 'Specific answer',
        actualAnswer: 'I do not know',
        retrievedDocuments: [],
        success: true,
        passed: false,
        ragTimeMs: 50,
        evaluationTimeMs: 30,
        timestamp: '2025-01-01T00:00:00Z'
      };

      service.runSingleTest(testCase).subscribe(result => {
        expect(result.passed).toBeFalse();
      });

      const req = httpMock.expectOne(r => r.url.endsWith('/eval-debugger/run-single'));
      req.flush(mockResult);
    });

    it('should handle HTTP error on runSingleTest', () => {
      const testCase: TestCase = {
        id: 'tc_err',
        prompt: 'query',
        expectedAnswer: 'answer',
        evaluatorTypes: []
      };

      service.runSingleTest(testCase).subscribe({
        error: err => expect(err).toBeTruthy()
      });

      const req = httpMock.expectOne(r => r.url.endsWith('/eval-debugger/run-single'));
      req.flush('Internal Error', { status: 500, statusText: 'Internal Server Error' });
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // runBatchTests()
  // ─────────────────────────────────────────────────────────────────────────────

  describe('runBatchTests()', () => {
    it('should POST to /eval-debugger/run-batch with batch request', () => {
      const request: BatchTestRequest = {
        testCases: [
          { id: 'tc_1', prompt: 'Query 1', expectedAnswer: 'Answer 1', evaluatorTypes: ['RELEVANCY'] },
          { id: 'tc_2', prompt: 'Query 2', expectedAnswer: 'Answer 2', evaluatorTypes: ['RELEVANCY'] }
        ],
        evaluatorTypes: ['RELEVANCY']
      };

      const mockResult: BatchTestResult = {
        runId: 'run_xyz',
        success: true,
        results: [],
        totalTests: 2,
        passedTests: 2,
        failedTests: 0,
        averageScore: 0.85,
        totalTimeMs: 500,
        timestamp: '2025-01-01T00:00:00Z'
      };

      service.runBatchTests(request).subscribe(result => {
        expect(result.runId).toBe('run_xyz');
        expect(result.totalTests).toBe(2);
        expect(result.passedTests).toBe(2);
        expect(result.averageScore).toBe(0.85);
      });

      const req = httpMock.expectOne(r => r.url.endsWith('/eval-debugger/run-batch'));
      expect(req.request.method).toBe('POST');
      expect(req.request.body.testCases.length).toBe(2);
      req.flush(mockResult);
    });

    it('should handle partial failures in batch result', () => {
      const request: BatchTestRequest = {
        testCases: [
          { id: 'tc_1', prompt: 'Q1', expectedAnswer: 'A1', evaluatorTypes: [] }
        ],
        evaluatorTypes: []
      };

      const mockResult: BatchTestResult = {
        runId: 'run_partial',
        success: true,
        results: [],
        totalTests: 1,
        passedTests: 0,
        failedTests: 1,
        averageScore: 0.2,
        totalTimeMs: 200,
        timestamp: '2025-01-01T00:00:00Z'
      };

      service.runBatchTests(request).subscribe(result => {
        expect(result.failedTests).toBe(1);
        expect(result.passedTests).toBe(0);
      });

      const req = httpMock.expectOne(r => r.url.endsWith('/eval-debugger/run-batch'));
      req.flush(mockResult);
    });

    it('should handle error on runBatchTests', () => {
      const request: BatchTestRequest = { testCases: [], evaluatorTypes: [] };

      service.runBatchTests(request).subscribe({
        error: err => expect(err).toBeTruthy()
      });

      const req = httpMock.expectOne(r => r.url.endsWith('/eval-debugger/run-batch'));
      req.flush('Bad Request', { status: 400, statusText: 'Bad Request' });
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // saveTestSuite()
  // ─────────────────────────────────────────────────────────────────────────────

  describe('saveTestSuite()', () => {
    it('should POST to /eval-debugger/suites and return saved suite', () => {
      const suite: TestSuite = {
        name: 'My Suite',
        description: 'Test suite',
        testCases: [],
        evaluatorTypes: ['RELEVANCY']
      };

      const mockSaved: TestSuite = {
        id: 'suite_123',
        name: 'My Suite',
        description: 'Test suite',
        testCases: [],
        evaluatorTypes: ['RELEVANCY'],
        createdAt: '2025-01-01T00:00:00Z'
      };

      service.saveTestSuite(suite).subscribe(saved => {
        expect(saved.id).toBe('suite_123');
        expect(saved.name).toBe('My Suite');
      });

      const req = httpMock.expectOne(r => r.url.endsWith('/eval-debugger/suites'));
      expect(req.request.method).toBe('POST');
      expect(req.request.body.name).toBe('My Suite');
      req.flush(mockSaved);
    });

    it('should handle error when saving suite fails', () => {
      const suite: TestSuite = { name: '', testCases: [], evaluatorTypes: [] };

      service.saveTestSuite(suite).subscribe({
        error: err => expect(err).toBeTruthy()
      });

      const req = httpMock.expectOne(r => r.url.endsWith('/eval-debugger/suites'));
      req.flush('Bad Request', { status: 400, statusText: 'Bad Request' });
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // getTestSuites()
  // ─────────────────────────────────────────────────────────────────────────────

  describe('getTestSuites()', () => {
    it('should GET /eval-debugger/suites and return suites array', () => {
      const mockSuites: TestSuite[] = [
        { id: 'suite_1', name: 'Suite 1', testCases: [], evaluatorTypes: [] },
        { id: 'suite_2', name: 'Suite 2', testCases: [], evaluatorTypes: [] }
      ];

      service.getTestSuites().subscribe(suites => {
        expect(suites.length).toBe(2);
        expect(suites[0].id).toBe('suite_1');
      });

      const req = httpMock.expectOne(r => r.url.endsWith('/eval-debugger/suites'));
      expect(req.request.method).toBe('GET');
      req.flush(mockSuites);
    });

    it('should return empty array when no suites exist', () => {
      service.getTestSuites().subscribe(suites => {
        expect(suites).toEqual([]);
      });

      const req = httpMock.expectOne(r => r.url.endsWith('/eval-debugger/suites'));
      req.flush([]);
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // getTestSuite()
  // ─────────────────────────────────────────────────────────────────────────────

  describe('getTestSuite()', () => {
    it('should GET /eval-debugger/suites/:id and return suite', () => {
      const mockSuite: TestSuite = {
        id: 'suite_abc',
        name: 'Specific Suite',
        testCases: [{ id: 'tc_1', prompt: 'Q', expectedAnswer: 'A', evaluatorTypes: [] }],
        evaluatorTypes: ['RELEVANCY']
      };

      service.getTestSuite('suite_abc').subscribe(suite => {
        expect(suite.id).toBe('suite_abc');
        expect(suite.testCases.length).toBe(1);
      });

      const req = httpMock.expectOne(r => r.url.endsWith('/eval-debugger/suites/suite_abc'));
      expect(req.request.method).toBe('GET');
      req.flush(mockSuite);
    });

    it('should handle 404 when suite not found', () => {
      service.getTestSuite('nonexistent').subscribe({
        error: err => expect(err).toBeTruthy()
      });

      const req = httpMock.expectOne(r => r.url.endsWith('/eval-debugger/suites/nonexistent'));
      req.flush('Not Found', { status: 404, statusText: 'Not Found' });
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // deleteTestSuite()
  // ─────────────────────────────────────────────────────────────────────────────

  describe('deleteTestSuite()', () => {
    it('should DELETE /eval-debugger/suites/:id', () => {
      let completed = false;

      service.deleteTestSuite('suite_to_delete').subscribe({
        complete: () => { completed = true; }
      });

      const req = httpMock.expectOne(r => r.url.endsWith('/eval-debugger/suites/suite_to_delete'));
      expect(req.request.method).toBe('DELETE');
      req.flush(null);

      expect(completed).toBeTrue();
    });

    it('should handle error when deleting non-existent suite', () => {
      service.deleteTestSuite('missing_suite').subscribe({
        error: err => expect(err).toBeTruthy()
      });

      const req = httpMock.expectOne(r => r.url.endsWith('/eval-debugger/suites/missing_suite'));
      req.flush('Not Found', { status: 404, statusText: 'Not Found' });
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // runTestSuite()
  // ─────────────────────────────────────────────────────────────────────────────

  describe('runTestSuite()', () => {
    it('should POST to /eval-debugger/suites/:id/run and return batch result', () => {
      const mockResult: BatchTestResult = {
        runId: 'run_suite_123',
        success: true,
        results: [],
        totalTests: 5,
        passedTests: 4,
        failedTests: 1,
        averageScore: 0.8,
        totalTimeMs: 1000,
        timestamp: '2025-01-01T00:00:00Z'
      };

      service.runTestSuite('suite_xyz').subscribe(result => {
        expect(result.runId).toBe('run_suite_123');
        expect(result.totalTests).toBe(5);
      });

      const req = httpMock.expectOne(r => r.url.endsWith('/eval-debugger/suites/suite_xyz/run'));
      expect(req.request.method).toBe('POST');
      req.flush(mockResult);
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // getRunHistory()
  // ─────────────────────────────────────────────────────────────────────────────

  describe('getRunHistory()', () => {
    it('should GET /eval-debugger/runs and return run history', () => {
      const mockHistory: TestRunResult[] = [
        {
          runId: 'run_1',
          batchResult: {
            runId: 'run_1',
            success: true,
            results: [],
            totalTests: 3,
            passedTests: 3,
            failedTests: 0,
            averageScore: 0.9,
            totalTimeMs: 300,
            timestamp: '2025-01-01T00:00:00Z'
          },
          timestamp: '2025-01-01T00:00:00Z'
        }
      ];

      service.getRunHistory().subscribe(history => {
        expect(history.length).toBe(1);
        expect(history[0].runId).toBe('run_1');
      });

      const req = httpMock.expectOne(r => r.url.endsWith('/eval-debugger/runs'));
      expect(req.request.method).toBe('GET');
      req.flush(mockHistory);
    });

    it('should return empty array when no run history', () => {
      service.getRunHistory().subscribe(history => {
        expect(history).toEqual([]);
      });

      const req = httpMock.expectOne(r => r.url.endsWith('/eval-debugger/runs'));
      req.flush([]);
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // getRunResult()
  // ─────────────────────────────────────────────────────────────────────────────

  describe('getRunResult()', () => {
    it('should GET /eval-debugger/runs/:runId and return run result', () => {
      const mockResult: TestRunResult = {
        runId: 'run_detail_99',
        batchResult: {
          runId: 'run_detail_99',
          success: true,
          results: [],
          totalTests: 1,
          passedTests: 1,
          failedTests: 0,
          averageScore: 1.0,
          totalTimeMs: 100,
          timestamp: '2025-01-01T00:00:00Z'
        },
        timestamp: '2025-01-01T00:00:00Z'
      };

      service.getRunResult('run_detail_99').subscribe(result => {
        expect(result.runId).toBe('run_detail_99');
        expect(result.batchResult.passedTests).toBe(1);
      });

      const req = httpMock.expectOne(r => r.url.endsWith('/eval-debugger/runs/run_detail_99'));
      expect(req.request.method).toBe('GET');
      req.flush(mockResult);
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // createEmptyTestCase()
  // ─────────────────────────────────────────────────────────────────────────────

  describe('createEmptyTestCase()', () => {
    it('should return a test case with default values', () => {
      const tc = service.createEmptyTestCase();
      expect(tc).toBeTruthy();
      expect(tc.id).toMatch(/^tc_/);
      expect(tc.prompt).toBe('');
      expect(tc.expectedAnswer).toBe('');
      expect(tc.evaluatorTypes).toEqual([]);
      expect(tc.threshold).toBe(0.5);
      expect(tc.maxDocuments).toBe(5);
    });

    it('should generate unique IDs for each call', () => {
      const tc1 = service.createEmptyTestCase();
      const tc2 = service.createEmptyTestCase();
      expect(tc1.id).not.toBe(tc2.id);
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // createEmptyTestSuite()
  // ─────────────────────────────────────────────────────────────────────────────

  describe('createEmptyTestSuite()', () => {
    it('should return a suite with default values', () => {
      const suite = service.createEmptyTestSuite();
      expect(suite).toBeTruthy();
      expect(suite.name).toBe('New Test Suite');
      expect(suite.description).toBe('');
      expect(suite.testCases).toEqual([]);
      expect(suite.evaluatorTypes).toEqual([]);
      expect(suite.id).toBeUndefined();
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // getLlmProviders()
  // ─────────────────────────────────────────────────────────────────────────────

  describe('getLlmProviders()', () => {
    it('should GET /eval-debugger/llm-providers and return providers', () => {
      const mockProviders: LlmProviderInfo[] = [
        {
          id: 'openai',
          name: 'OpenAI',
          description: 'OpenAI models',
          available: true,
          supportsJudge: true,
          models: [{ id: 'gpt-4', name: 'GPT-4', description: '', supportsTools: true }]
        },
        {
          id: 'anthropic',
          name: 'Anthropic',
          description: 'Claude models',
          available: false,
          supportsJudge: true,
          models: []
        }
      ];

      service.getLlmProviders().subscribe(providers => {
        expect(providers.length).toBe(2);
        expect(providers[0].id).toBe('openai');
        expect(providers[1].available).toBeFalse();
        expect(providers[0].models[0].id).toBe('gpt-4');
      });

      const req = httpMock.expectOne(r => r.url.endsWith('/eval-debugger/llm-providers'));
      expect(req.request.method).toBe('GET');
      req.flush(mockProviders);
    });

    it('should handle error on getLlmProviders', () => {
      service.getLlmProviders().subscribe({
        error: err => expect(err).toBeTruthy()
      });

      const req = httpMock.expectOne(r => r.url.endsWith('/eval-debugger/llm-providers'));
      req.flush('Service Unavailable', { status: 503, statusText: 'Service Unavailable' });
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // runLlmJudge()
  // ─────────────────────────────────────────────────────────────────────────────

  describe('runLlmJudge()', () => {
    it('should POST to /eval-debugger/run-llm-judge with judge request', () => {
      const request: LlmJudgeRequest = {
        testCaseId: 'tc_judge_1',
        providerId: 'openai',
        modelId: 'gpt-4',
        query: 'What is RAG?',
        expectedAnswer: 'Retrieval Augmented Generation',
        actualAnswer: 'RAG is a technique that combines retrieval with generation',
        retrievedDocuments: ['doc1'],
        threshold: 0.7
      };

      const mockResult: LlmJudgeResult = {
        success: true,
        testCaseId: 'tc_judge_1',
        timestamp: '2025-01-01T00:00:00Z',
        evaluation: {
          passed: true,
          overallScore: 0.85,
          summary: 'Good answer',
          criteria: {},
          recommendations: [],
          evaluationTimeMs: 2000,
          judgeModel: 'gpt-4',
          judgeProvider: 'openai'
        }
      };

      service.runLlmJudge(request).subscribe(result => {
        expect(result.success).toBeTrue();
        expect(result.testCaseId).toBe('tc_judge_1');
        expect(result.evaluation?.passed).toBeTrue();
        expect(result.evaluation?.overallScore).toBe(0.85);
      });

      const req = httpMock.expectOne(r => r.url.endsWith('/eval-debugger/run-llm-judge'));
      expect(req.request.method).toBe('POST');
      expect(req.request.body.testCaseId).toBe('tc_judge_1');
      expect(req.request.body.providerId).toBe('openai');
      req.flush(mockResult);
    });

    it('should handle error on runLlmJudge', () => {
      const request: LlmJudgeRequest = {
        testCaseId: 'tc_err',
        query: 'q',
        expectedAnswer: 'a',
        actualAnswer: 'b',
        retrievedDocuments: []
      };

      service.runLlmJudge(request).subscribe({
        error: err => expect(err).toBeTruthy()
      });

      const req = httpMock.expectOne(r => r.url.endsWith('/eval-debugger/run-llm-judge'));
      req.flush('Error', { status: 500, statusText: 'Internal Server Error' });
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // runCombinedTest()
  // ─────────────────────────────────────────────────────────────────────────────

  describe('runCombinedTest()', () => {
    it('should POST to /eval-debugger/run-combined with test case', () => {
      const testCase: TestCase = {
        id: 'tc_combined',
        prompt: 'Combined query',
        expectedAnswer: 'Combined answer',
        evaluatorTypes: ['RELEVANCY']
      };

      const mockResult: CombinedEvalResult = {
        success: true,
        testCaseId: 'tc_combined',
        llmJudgeAvailable: true,
        timestamp: '2025-01-01T00:00:00Z',
        automatedResult: {
          testCaseId: 'tc_combined',
          prompt: 'Combined query',
          expectedAnswer: 'Combined answer',
          actualAnswer: 'The answer is...',
          retrievedDocuments: [],
          success: true,
          passed: true,
          ragTimeMs: 100,
          evaluationTimeMs: 50,
          timestamp: '2025-01-01T00:00:00Z'
        },
        llmJudgeResult: {
          passed: true,
          overallScore: 0.9,
          summary: 'Excellent',
          criteria: {},
          recommendations: [],
          evaluationTimeMs: 1500,
          judgeModel: 'gpt-4',
          judgeProvider: 'openai'
        }
      };

      service.runCombinedTest(testCase).subscribe(result => {
        expect(result.success).toBeTrue();
        expect(result.testCaseId).toBe('tc_combined');
        expect(result.automatedResult?.passed).toBeTrue();
        expect(result.llmJudgeResult?.overallScore).toBe(0.9);
        expect(result.llmJudgeAvailable).toBeTrue();
      });

      const req = httpMock.expectOne(r => r.url.endsWith('/eval-debugger/run-combined'));
      expect(req.request.method).toBe('POST');
      expect(req.request.body.id).toBe('tc_combined');
      req.flush(mockResult);
    });

    it('should handle error on runCombinedTest', () => {
      const testCase: TestCase = {
        id: 'tc_err',
        prompt: 'q',
        expectedAnswer: 'a',
        evaluatorTypes: []
      };

      service.runCombinedTest(testCase).subscribe({
        error: err => expect(err).toBeTruthy()
      });

      const req = httpMock.expectOne(r => r.url.endsWith('/eval-debugger/run-combined'));
      req.flush('Error', { status: 500, statusText: 'Internal Server Error' });
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // runBatchCombinedTests()
  // ─────────────────────────────────────────────────────────────────────────────

  describe('runBatchCombinedTests()', () => {
    it('should POST to /eval-debugger/run-batch-combined and return batch combined result', () => {
      const request: BatchTestRequest = {
        testCases: [
          { id: 'tc_1', prompt: 'Q1', expectedAnswer: 'A1', evaluatorTypes: ['RELEVANCY'] }
        ],
        evaluatorTypes: ['RELEVANCY']
      };

      const mockResult: BatchCombinedResult = {
        runId: 'batch_combined_run_1',
        success: true,
        results: [],
        totalTests: 1,
        automatedPassedTests: 1,
        llmJudgePassedTests: 1,
        averageAutomatedScore: 0.85,
        averageLlmJudgeScore: 0.9,
        totalTimeMs: 2000,
        llmJudgeAvailable: true,
        timestamp: '2025-01-01T00:00:00Z'
      };

      service.runBatchCombinedTests(request).subscribe(result => {
        expect(result.runId).toBe('batch_combined_run_1');
        expect(result.automatedPassedTests).toBe(1);
        expect(result.llmJudgePassedTests).toBe(1);
        expect(result.averageLlmJudgeScore).toBe(0.9);
        expect(result.llmJudgeAvailable).toBeTrue();
      });

      const req = httpMock.expectOne(r => r.url.endsWith('/eval-debugger/run-batch-combined'));
      expect(req.request.method).toBe('POST');
      expect(req.request.body.testCases.length).toBe(1);
      req.flush(mockResult);
    });

    it('should handle error on runBatchCombinedTests', () => {
      const request: BatchTestRequest = { testCases: [], evaluatorTypes: [] };

      service.runBatchCombinedTests(request).subscribe({
        error: err => expect(err).toBeTruthy()
      });

      const req = httpMock.expectOne(r => r.url.endsWith('/eval-debugger/run-batch-combined'));
      req.flush('Bad Request', { status: 400, statusText: 'Bad Request' });
    });
  });
});
