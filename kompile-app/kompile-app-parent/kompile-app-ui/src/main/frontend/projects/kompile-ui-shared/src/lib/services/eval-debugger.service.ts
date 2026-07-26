/*
 *   Copyright 2025 Kompile Inc.
 *  Licensed under the Apache License, Version 2.0
 */

import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { backendUrl } from './base.service';
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

@Injectable({
  providedIn: 'root'
})
export class EvalDebuggerService {
  private baseUrl = `${backendUrl}/eval-debugger`;

  constructor(private http: HttpClient) {}

  /**
   * Get the status of the eval debugger.
   */
  getStatus(): Observable<EvalDebuggerStatus> {
    return this.http.get<EvalDebuggerStatus>(`${this.baseUrl}/status`);
  }

  /**
   * Get available evaluator types.
   */
  getEvaluatorTypes(): Observable<EvaluatorTypeInfo[]> {
    return this.http.get<EvaluatorTypeInfo[]>(`${this.baseUrl}/evaluator-types`);
  }

  /**
   * Run a single test case.
   */
  runSingleTest(testCase: TestCase): Observable<TestCaseResult> {
    return this.http.post<TestCaseResult>(`${this.baseUrl}/run-single`, testCase);
  }

  /**
   * Run multiple test cases.
   */
  runBatchTests(request: BatchTestRequest): Observable<BatchTestResult> {
    return this.http.post<BatchTestResult>(`${this.baseUrl}/run-batch`, request);
  }

  /**
   * Save a test suite.
   */
  saveTestSuite(suite: TestSuite): Observable<TestSuite> {
    return this.http.post<TestSuite>(`${this.baseUrl}/suites`, suite);
  }

  /**
   * Get all test suites.
   */
  getTestSuites(): Observable<TestSuite[]> {
    return this.http.get<TestSuite[]>(`${this.baseUrl}/suites`);
  }

  /**
   * Get a specific test suite.
   */
  getTestSuite(id: string): Observable<TestSuite> {
    return this.http.get<TestSuite>(`${this.baseUrl}/suites/${id}`);
  }

  /**
   * Delete a test suite.
   */
  deleteTestSuite(id: string): Observable<void> {
    return this.http.delete<void>(`${this.baseUrl}/suites/${id}`);
  }

  /**
   * Run a saved test suite.
   */
  runTestSuite(id: string): Observable<BatchTestResult> {
    return this.http.post<BatchTestResult>(`${this.baseUrl}/suites/${id}/run`, {});
  }

  /**
   * Get run history.
   */
  getRunHistory(): Observable<TestRunResult[]> {
    return this.http.get<TestRunResult[]>(`${this.baseUrl}/runs`);
  }

  /**
   * Get a specific run result.
   */
  getRunResult(runId: string): Observable<TestRunResult> {
    return this.http.get<TestRunResult>(`${this.baseUrl}/runs/${runId}`);
  }

  /**
   * Create an empty test case.
   */
  createEmptyTestCase(): TestCase {
    return {
      id: this.generateId(),
      prompt: '',
      expectedAnswer: '',
      evaluatorTypes: [],
      threshold: 0.5,
      maxDocuments: 5
    };
  }

  /**
   * Create an empty test suite.
   */
  createEmptyTestSuite(): TestSuite {
    return {
      name: 'New Test Suite',
      description: '',
      testCases: [],
      evaluatorTypes: []
    };
  }

  private generateId(): string {
    return 'tc_' + Math.random().toString(36).substring(2, 11);
  }

  // ═══════════════════════════════════════════════════════════════════════════════
  // LLM-AS-JUDGE METHODS
  // ═══════════════════════════════════════════════════════════════════════════════

  /**
   * Get available LLM providers for LLM-as-judge evaluation.
   */
  getLlmProviders(): Observable<LlmProviderInfo[]> {
    return this.http.get<LlmProviderInfo[]>(`${this.baseUrl}/llm-providers`);
  }

  /**
   * Run LLM-as-judge evaluation on a single test case result.
   */
  runLlmJudge(request: LlmJudgeRequest): Observable<LlmJudgeResult> {
    return this.http.post<LlmJudgeResult>(`${this.baseUrl}/run-llm-judge`, request);
  }

  /**
   * Run combined evaluation (automated + LLM judge) on a single test case.
   */
  runCombinedTest(testCase: TestCase): Observable<CombinedEvalResult> {
    return this.http.post<CombinedEvalResult>(`${this.baseUrl}/run-combined`, testCase);
  }

  /**
   * Run combined evaluation (automated + LLM judge) on multiple test cases.
   */
  runBatchCombinedTests(request: BatchTestRequest): Observable<BatchCombinedResult> {
    return this.http.post<BatchCombinedResult>(`${this.baseUrl}/run-batch-combined`, request);
  }
}
