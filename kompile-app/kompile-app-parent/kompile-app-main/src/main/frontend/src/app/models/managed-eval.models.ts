/*
 * Copyright 2025 Kompile Inc.
 * Licensed under the Apache License, Version 2.0
 */

/**
 * Evaluation suite - a collection of test cases.
 */
export interface EvalSuite {
  id: string;
  name: string;
  description?: string;
  factSheetId?: number;
  enabled: boolean;
  requiredPassRate: number;
  testCaseCount: number;
  tags?: string[];
  createdAt?: string;
  updatedAt?: string;
  testCases?: EvalCase[];
}

/**
 * Individual evaluation test case.
 */
export interface EvalCase {
  id: string;
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
  priority: number;
  enabled: boolean;
  timeoutMs: number;
  createdAt?: string;
  updatedAt?: string;
}

/**
 * Result of running an individual test case.
 */
export interface EvalTestResult {
  id: string;
  testCaseId: string;
  testCaseName?: string;
  suiteId?: string;
  factSheetId?: number;
  passed: boolean;
  score: number;
  query?: string;
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
  executionTimeMs: number;
  totalTokens?: number;
}

/**
 * Result of running an evaluation suite.
 */
export interface EvalSuiteResult {
  id: string;
  suiteId: string;
  suiteName?: string;
  factSheetId?: number;
  passed: boolean;
  passRate: number;
  averageScore: number;
  passedCount: number;
  failedCount: number;
  skippedCount: number;
  totalCount: number;
  averageScoresByType?: { [key: string]: number };
  passRatesByType?: { [key: string]: number };
  failedTests?: { [key: string]: string[] };
  startedAt?: string;
  completedAt?: string;
  executionTimeMs: number;
  totalTokens?: number;
}

/**
 * Fact sheet metrics summary.
 */
export interface FactSheetMetrics {
  totalTestRuns?: number;
  passedTestRuns?: number;
  testPassRate?: number;
  averageTestScore?: number;
  totalSuiteRuns?: number;
  passedSuiteRuns?: number;
  suitePassRate?: number;
  averageSuitePassRate?: number;
  averageSuiteScore?: number;
  totalTestCases?: number;
  totalSuites?: number;
}

/**
 * Test case pass rate response.
 */
export interface TestCasePassRate {
  passRate: number;
  trend: number;
  windowDays: number;
}

/**
 * Request to create a new suite.
 */
export interface CreateSuiteRequest {
  factSheetId: number;
  name: string;
  description?: string;
}

/**
 * Request to update a suite.
 */
export interface UpdateSuiteRequest {
  name?: string;
  description?: string;
  enabled?: boolean;
  requiredPassRate?: number;
  tags?: string[];
}

/**
 * Request to create a new test case.
 */
export interface CreateTestCaseRequest {
  name: string;
  description?: string;
  factSheetId?: number;
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
}

/**
 * Request to update a test case.
 */
export interface UpdateTestCaseRequest {
  name?: string;
  description?: string;
  query?: string;
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
}

/**
 * Request to move a test case to another suite.
 */
export interface MoveTestCaseRequest {
  targetSuiteId?: string;
}

/**
 * Request to import a suite.
 */
export interface ImportSuiteRequest {
  suite: EvalSuite;
  targetFactSheetId: number;
}

/**
 * Available evaluation types.
 */
export const EVALUATION_TYPES = [
  { value: 'RELEVANCY', label: 'Relevancy', description: 'Evaluate if response is relevant to the query' },
  { value: 'FAITHFULNESS', label: 'Faithfulness', description: 'Evaluate if response is factually grounded in context' },
  { value: 'FACTUALITY', label: 'Factuality', description: 'Evaluate if response contains accurate information' },
  { value: 'CONTEXT_RELEVANCY', label: 'Context Relevancy', description: 'Evaluate if retrieved context is relevant to query' },
  { value: 'CONTEXT_SUFFICIENCY', label: 'Context Sufficiency', description: 'Evaluate if retrieved context is sufficient for answering' },
  { value: 'COHERENCE', label: 'Coherence', description: 'Evaluate response coherence and readability' },
  { value: 'COMPLETENESS', label: 'Completeness', description: 'Evaluate response completeness' },
  { value: 'CONCISENESS', label: 'Conciseness', description: 'Evaluate response conciseness' },
  { value: 'ANSWER_CORRECTNESS', label: 'Answer Correctness', description: 'Evaluate answer correctness against ground truth' },
  { value: 'SEMANTIC_SIMILARITY', label: 'Semantic Similarity', description: 'Evaluate semantic similarity to expected answer' },
  { value: 'HALLUCINATION_DETECTION', label: 'Hallucination Detection', description: 'Detect hallucinations in response' },
];

/**
 * Priority levels.
 */
export const PRIORITY_LEVELS = [
  { value: 1, label: 'Very Low', color: '#9e9e9e' },
  { value: 2, label: 'Low', color: '#4caf50' },
  { value: 3, label: 'Medium', color: '#2196f3' },
  { value: 4, label: 'High', color: '#ff9800' },
  { value: 5, label: 'Critical', color: '#f44336' },
];
