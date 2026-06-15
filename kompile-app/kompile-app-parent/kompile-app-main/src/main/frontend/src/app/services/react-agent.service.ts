/*
 * Copyright 2025 Kompile Inc.
 */

import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { BehaviorSubject, Observable, tap, catchError, of } from 'rxjs';
import { backendUrl } from './base.service';

// Interfaces
export interface ReActConfig {
  enabled: boolean;
  maxSteps: number;
  executionMode: 'SEQUENTIAL' | 'PARALLEL';
  graphRagEnabled: boolean;
  graphRagSearchType: 'LOCAL' | 'GLOBAL';
  graphRagMaxResults: number;
  filterChainEnabled: boolean;
  evalBasedEnabled: boolean;
  evalTrackingEnabled: boolean;
  evalHookEnabled: boolean;
  selfEvaluate: boolean;
  evaluateReasoning: boolean;
  qualityThreshold: number;
  evalRetentionDays: number;
  summarizeResults: boolean;
  maxResultLength: number;
}

export interface ReActStatus {
  available: boolean;
  evalTrackingEnabled: boolean;
  testCaseCount: number;
  suiteCount: number;
  resultCount: number;
}

export interface EvalTestCase {
  id?: string;
  name: string;
  description?: string;
  factSheetId?: number;
  factSheetName?: string;
  query: string;
  expectedAnswer?: string;
  expectedFacts?: string[];
  forbiddenFacts?: string[];
  expectedEntities?: string[];
  expectedToolCalls?: string[];
  evaluationTypes?: string[];
  thresholds?: { [key: string]: number };
  tags?: string[];
  priority?: number;
  enabled?: boolean;
  timeoutMs?: number;
  createdAt?: string;
  updatedAt?: string;
  metadata?: { [key: string]: any };
}

export interface EvalSuite {
  id?: string;
  name: string;
  description?: string;
  factSheetId?: number;
  testCaseIds?: string[];
  tags?: string[];
  enabled?: boolean;
  requiredPassRate?: number;
  createdAt?: string;
  updatedAt?: string;
  metadata?: { [key: string]: any };
}

export interface EvalTestResult {
  id: string;
  testCaseId?: string;
  testCaseName?: string;
  suiteId?: string;
  factSheetId?: number;
  executionId?: string;
  passed: boolean;
  score: number;
  query: string;
  expectedAnswer?: string;
  actualAnswer?: string;
  retrievedDocuments?: string[];
  toolCalls?: string[];
  stepsExecuted?: number;
  scores?: { [key: string]: number };
  passedByType?: { [key: string]: boolean };
  failureReasons?: string[];
  startedAt?: string;
  completedAt?: string;
  executionTimeMs?: number;
  totalTokens?: number;
  metadata?: { [key: string]: any };
}

export interface FactSheetMetrics {
  factSheetId: number;
  totalTestCases: number;
  enabledTestCases: number;
  totalResults: number;
  passRate: number;
  averageScore: number;
  failingTestCases?: EvalTestCase[];
}

export interface EvaluationType {
  type: string;
  name: string;
  description: string;
}

@Injectable({
  providedIn: 'root'
})
export class ReactAgentService {
  private readonly apiUrl = `${backendUrl}/react-agent`;

  // State management
  private configSubject = new BehaviorSubject<ReActConfig | null>(null);
  public config$ = this.configSubject.asObservable();

  private statusSubject = new BehaviorSubject<ReActStatus | null>(null);
  public status$ = this.statusSubject.asObservable();

  private testCasesSubject = new BehaviorSubject<EvalTestCase[]>([]);
  public testCases$ = this.testCasesSubject.asObservable();

  private suitesSubject = new BehaviorSubject<EvalSuite[]>([]);
  public suites$ = this.suitesSubject.asObservable();

  private evaluationTypesSubject = new BehaviorSubject<EvaluationType[]>([]);
  public evaluationTypes$ = this.evaluationTypesSubject.asObservable();

  constructor(private http: HttpClient) {
    this.loadConfig();
    this.loadStatus();
    this.loadEvaluationTypes();
  }

  // ==================== Configuration ====================

  loadConfig(): Observable<ReActConfig> {
    return this.http.get<ReActConfig>(`${this.apiUrl}/config`).pipe(
      tap(config => this.configSubject.next(config)),
      catchError(err => {
        console.error('Failed to load ReAct config:', err);
        return of({} as ReActConfig);
      })
    );
  }

  getConfig(): ReActConfig | null {
    return this.configSubject.getValue();
  }

  loadStatus(): Observable<ReActStatus> {
    return this.http.get<ReActStatus>(`${this.apiUrl}/status`).pipe(
      tap(status => this.statusSubject.next(status)),
      catchError(err => {
        console.error('Failed to load ReAct status:', err);
        return of({} as ReActStatus);
      })
    );
  }

  loadEvaluationTypes(): Observable<EvaluationType[]> {
    return this.http.get<EvaluationType[]>(`${this.apiUrl}/evaluation-types`).pipe(
      tap(types => this.evaluationTypesSubject.next(types)),
      catchError(err => {
        console.error('Failed to load evaluation types:', err);
        return of([]);
      })
    );
  }

  // ==================== Test Cases ====================

  loadTestCases(factSheetId?: number, tag?: string): Observable<EvalTestCase[]> {
    let url = `${this.apiUrl}/test-cases`;
    const params: string[] = [];
    if (factSheetId !== undefined) params.push(`factSheetId=${factSheetId}`);
    if (tag) params.push(`tag=${encodeURIComponent(tag)}`);
    if (params.length > 0) url += '?' + params.join('&');

    return this.http.get<EvalTestCase[]>(url).pipe(
      tap(cases => this.testCasesSubject.next(cases)),
      catchError(err => {
        console.error('Failed to load test cases:', err);
        return of([]);
      })
    );
  }

  getTestCasesForFactSheet(factSheetId: number): Observable<EvalTestCase[]> {
    return this.http.get<EvalTestCase[]>(`${this.apiUrl}/fact-sheets/${factSheetId}/test-cases`);
  }

  getTestCase(id: string): Observable<EvalTestCase> {
    return this.http.get<EvalTestCase>(`${this.apiUrl}/test-cases/${id}`);
  }

  createTestCase(testCase: EvalTestCase): Observable<EvalTestCase> {
    return this.http.post<EvalTestCase>(`${this.apiUrl}/test-cases`, testCase).pipe(
      tap(() => this.loadTestCases(testCase.factSheetId))
    );
  }

  updateTestCase(id: string, testCase: EvalTestCase): Observable<EvalTestCase> {
    return this.http.put<EvalTestCase>(`${this.apiUrl}/test-cases/${id}`, testCase).pipe(
      tap(() => this.loadTestCases(testCase.factSheetId))
    );
  }

  deleteTestCase(id: string): Observable<void> {
    return this.http.delete<void>(`${this.apiUrl}/test-cases/${id}`).pipe(
      tap(() => {
        const current = this.testCasesSubject.getValue();
        this.testCasesSubject.next(current.filter(tc => tc.id !== id));
      })
    );
  }

  // ==================== Suites ====================

  loadSuites(factSheetId?: number): Observable<EvalSuite[]> {
    let url = `${this.apiUrl}/suites`;
    if (factSheetId !== undefined) url += `?factSheetId=${factSheetId}`;

    return this.http.get<EvalSuite[]>(url).pipe(
      tap(suites => this.suitesSubject.next(suites)),
      catchError(err => {
        console.error('Failed to load suites:', err);
        return of([]);
      })
    );
  }

  getSuite(id: string): Observable<EvalSuite> {
    return this.http.get<EvalSuite>(`${this.apiUrl}/suites/${id}`);
  }

  createSuite(suite: EvalSuite): Observable<EvalSuite> {
    return this.http.post<EvalSuite>(`${this.apiUrl}/suites`, suite).pipe(
      tap(() => this.loadSuites(suite.factSheetId))
    );
  }

  updateSuite(id: string, suite: EvalSuite): Observable<EvalSuite> {
    return this.http.put<EvalSuite>(`${this.apiUrl}/suites/${id}`, suite).pipe(
      tap(() => this.loadSuites(suite.factSheetId))
    );
  }

  deleteSuite(id: string): Observable<void> {
    return this.http.delete<void>(`${this.apiUrl}/suites/${id}`).pipe(
      tap(() => {
        const current = this.suitesSubject.getValue();
        this.suitesSubject.next(current.filter(s => s.id !== id));
      })
    );
  }

  addTestCaseToSuite(suiteId: string, testCaseId: string): Observable<EvalSuite> {
    return this.http.post<EvalSuite>(`${this.apiUrl}/suites/${suiteId}/test-cases/${testCaseId}`, {});
  }

  removeTestCaseFromSuite(suiteId: string, testCaseId: string): Observable<EvalSuite> {
    return this.http.delete<EvalSuite>(`${this.apiUrl}/suites/${suiteId}/test-cases/${testCaseId}`);
  }

  // ==================== Results & Metrics ====================

  getResultsForFactSheet(factSheetId: number, limit: number = 50): Observable<EvalTestResult[]> {
    return this.http.get<EvalTestResult[]>(`${this.apiUrl}/fact-sheets/${factSheetId}/results?limit=${limit}`);
  }

  getResultsForTestCase(testCaseId: string, limit: number = 20): Observable<EvalTestResult[]> {
    return this.http.get<EvalTestResult[]>(`${this.apiUrl}/test-cases/${testCaseId}/results?limit=${limit}`);
  }

  getMetricsForFactSheet(factSheetId: number): Observable<FactSheetMetrics> {
    return this.http.get<FactSheetMetrics>(`${this.apiUrl}/fact-sheets/${factSheetId}/metrics`);
  }

  // ==================== Utility ====================

  getCurrentTestCases(): EvalTestCase[] {
    return this.testCasesSubject.getValue();
  }

  getCurrentSuites(): EvalSuite[] {
    return this.suitesSubject.getValue();
  }
}
