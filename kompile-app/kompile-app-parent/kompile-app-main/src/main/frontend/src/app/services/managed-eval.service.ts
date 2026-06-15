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
import { Injectable } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { Observable } from 'rxjs';
import { BaseService } from './base.service';
import {
  EvalSuite,
  EvalCase,
  EvalTestResult,
  EvalSuiteResult,
  FactSheetMetrics,
  TestCasePassRate,
  CreateSuiteRequest,
  UpdateSuiteRequest,
  CreateTestCaseRequest,
  UpdateTestCaseRequest,
  MoveTestCaseRequest,
  ImportSuiteRequest
} from '../models/managed-eval.models';

/**
 * Service for managing evaluation sets (suites and test cases).
 */
@Injectable({
  providedIn: 'root'
})
export class ManagedEvalService extends BaseService {

  private readonly baseUrl: string;

  constructor(private http: HttpClient) {
    super();
    this.baseUrl = `${this.backendUrl}/eval-sets`;
  }

  // ═══════════════════════════════════════════════════════════════════════════════
  // SUITE OPERATIONS
  // ═══════════════════════════════════════════════════════════════════════════════

  /**
   * Get all evaluation suites for the active fact sheet.
   */
  getAllSuites(): Observable<EvalSuite[]> {
    return this.http.get<EvalSuite[]>(this.baseUrl);
  }

  /**
   * Get suites for a specific fact sheet.
   */
  getSuitesForFactSheet(factSheetId: number): Observable<EvalSuite[]> {
    return this.http.get<EvalSuite[]>(`${this.baseUrl}/fact-sheet/${factSheetId}`);
  }

  /**
   * Get a suite by ID with full details.
   */
  getSuiteById(suiteId: string): Observable<EvalSuite> {
    return this.http.get<EvalSuite>(`${this.baseUrl}/${suiteId}`);
  }

  /**
   * Create a new evaluation suite.
   */
  createSuite(request: CreateSuiteRequest): Observable<EvalSuite> {
    return this.http.post<EvalSuite>(this.baseUrl, request);
  }

  /**
   * Update an existing suite.
   */
  updateSuite(suiteId: string, request: UpdateSuiteRequest): Observable<void> {
    return this.http.put<void>(`${this.baseUrl}/${suiteId}`, request);
  }

  /**
   * Delete a suite.
   */
  deleteSuite(suiteId: string): Observable<void> {
    return this.http.delete<void>(`${this.baseUrl}/${suiteId}`);
  }

  // ═══════════════════════════════════════════════════════════════════════════════
  // TEST CASE OPERATIONS
  // ═══════════════════════════════════════════════════════════════════════════════

  /**
   * Get all test cases in a suite.
   */
  getTestCasesInSuite(suiteId: string): Observable<EvalCase[]> {
    return this.http.get<EvalCase[]>(`${this.baseUrl}/${suiteId}/cases`);
  }

  /**
   * Get a test case by ID.
   */
  getTestCaseById(caseId: string): Observable<EvalCase> {
    return this.http.get<EvalCase>(`${this.baseUrl}/cases/${caseId}`);
  }

  /**
   * Create a new test case in a suite.
   */
  createTestCase(suiteId: string, request: CreateTestCaseRequest): Observable<EvalCase> {
    return this.http.post<EvalCase>(`${this.baseUrl}/${suiteId}/cases`, request);
  }

  /**
   * Update an existing test case.
   */
  updateTestCase(caseId: string, request: UpdateTestCaseRequest): Observable<void> {
    return this.http.put<void>(`${this.baseUrl}/cases/${caseId}`, request);
  }

  /**
   * Delete a test case.
   */
  deleteTestCase(caseId: string): Observable<void> {
    return this.http.delete<void>(`${this.baseUrl}/cases/${caseId}`);
  }

  /**
   * Move a test case to a different suite.
   */
  moveTestCase(caseId: string, targetSuiteId: string | null): Observable<void> {
    const request: MoveTestCaseRequest = { targetSuiteId: targetSuiteId || undefined };
    return this.http.post<void>(`${this.baseUrl}/cases/${caseId}/move`, request);
  }

  // ═══════════════════════════════════════════════════════════════════════════════
  // RESULTS OPERATIONS
  // ═══════════════════════════════════════════════════════════════════════════════

  /**
   * Get result history for a suite.
   */
  getSuiteResultHistory(suiteId: string, limit: number = 10): Observable<EvalSuiteResult[]> {
    const params = new HttpParams().set('limit', limit.toString());
    return this.http.get<EvalSuiteResult[]>(`${this.baseUrl}/${suiteId}/results`, { params });
  }

  /**
   * Get result history for a test case.
   */
  getTestCaseResultHistory(caseId: string, limit: number = 10): Observable<EvalTestResult[]> {
    const params = new HttpParams().set('limit', limit.toString());
    return this.http.get<EvalTestResult[]>(`${this.baseUrl}/cases/${caseId}/results`, { params });
  }

  /**
   * Get the latest result for a suite.
   */
  getLatestSuiteResult(suiteId: string): Observable<EvalSuiteResult> {
    return this.http.get<EvalSuiteResult>(`${this.baseUrl}/${suiteId}/results/latest`);
  }

  /**
   * Get the latest result for a test case.
   */
  getLatestTestCaseResult(caseId: string): Observable<EvalTestResult> {
    return this.http.get<EvalTestResult>(`${this.baseUrl}/cases/${caseId}/results/latest`);
  }

  // ═══════════════════════════════════════════════════════════════════════════════
  // METRICS OPERATIONS
  // ═══════════════════════════════════════════════════════════════════════════════

  /**
   * Get evaluation metrics for a fact sheet.
   */
  getFactSheetMetrics(factSheetId: number): Observable<FactSheetMetrics> {
    return this.http.get<FactSheetMetrics>(`${this.baseUrl}/fact-sheet/${factSheetId}/metrics`);
  }

  /**
   * Get consistently failing test cases for a fact sheet.
   */
  getFailingTestCases(factSheetId: number): Observable<EvalCase[]> {
    return this.http.get<EvalCase[]>(`${this.baseUrl}/fact-sheet/${factSheetId}/failing`);
  }

  /**
   * Get pass rate and trend for a test case.
   */
  getTestCasePassRate(caseId: string, windowDays: number = 30): Observable<TestCasePassRate> {
    const params = new HttpParams().set('windowDays', windowDays.toString());
    return this.http.get<TestCasePassRate>(`${this.baseUrl}/cases/${caseId}/pass-rate`, { params });
  }

  // ═══════════════════════════════════════════════════════════════════════════════
  // IMPORT/EXPORT OPERATIONS
  // ═══════════════════════════════════════════════════════════════════════════════

  /**
   * Export a suite as JSON.
   */
  exportSuite(suiteId: string): Observable<EvalSuite> {
    return this.http.get<EvalSuite>(`${this.baseUrl}/${suiteId}/export`);
  }

  /**
   * Import a suite from JSON.
   */
  importSuite(suite: EvalSuite, targetFactSheetId: number): Observable<EvalSuite> {
    const request: ImportSuiteRequest = { suite, targetFactSheetId };
    return this.http.post<EvalSuite>(`${this.baseUrl}/import`, request);
  }

  // ═══════════════════════════════════════════════════════════════════════════════
  // HELPER METHODS
  // ═══════════════════════════════════════════════════════════════════════════════

  /**
   * Download a suite as JSON file.
   */
  downloadSuiteAsJson(suite: EvalSuite): void {
    const json = JSON.stringify(suite, null, 2);
    const blob = new Blob([json], { type: 'application/json' });
    const url = window.URL.createObjectURL(blob);
    const link = document.createElement('a');
    link.href = url;
    link.download = `${suite.name.replace(/\s+/g, '_')}_eval_suite.json`;
    link.click();
    window.URL.revokeObjectURL(url);
  }

  /**
   * Parse an imported JSON file.
   */
  parseImportedFile(file: File): Promise<EvalSuite> {
    return new Promise((resolve, reject) => {
      const reader = new FileReader();
      reader.onload = (e) => {
        try {
          const suite = JSON.parse(e.target?.result as string);
          resolve(suite);
        } catch (err) {
          reject(new Error('Invalid JSON file'));
        }
      };
      reader.onerror = () => reject(new Error('Failed to read file'));
      reader.readAsText(file);
    });
  }

  /**
   * Create an empty test case with defaults.
   */
  createEmptyTestCase(): CreateTestCaseRequest {
    return {
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
  }

  /**
   * Create an empty suite with defaults.
   */
  createEmptySuite(factSheetId: number): CreateSuiteRequest {
    return {
      factSheetId,
      name: 'New Evaluation Suite',
      description: ''
    };
  }
}
