/*
 *   Copyright 2025 Kompile Inc.
 *  Licensed under the Apache License, Version 2.0
 */

export interface EvalDebuggerStatus {
  available: boolean;
  evaluationAvailable: boolean;
  evaluatorCount: number;
  llmJudgeAvailable: boolean;
  llmProviderCount: number;
  message: string;
}

export interface EvaluatorTypeInfo {
  type: string;
  name: string;
  description: string;
  available: boolean;
}

export interface TestCase {
  id: string;
  prompt: string;
  expectedAnswer: string;
  evaluatorTypes?: string[];
  threshold?: number;
  maxDocuments?: number;
  metadata?: { [key: string]: any };
}

export interface TestCaseResult {
  testCaseId: string;
  prompt: string;
  expectedAnswer: string;
  actualAnswer: string;
  retrievedDocuments: string[];
  success: boolean;
  passed: boolean;
  error?: string;
  evaluationError?: string;
  evaluationReport?: EvaluationReportDto;
  ragTimeMs: number;
  evaluationTimeMs: number;
  timestamp: string;
}

export interface BatchTestRequest {
  testCases: TestCase[];
  evaluatorTypes?: string[];
}

export interface BatchTestResult {
  runId: string;
  success: boolean;
  error?: string;
  results: TestCaseResult[];
  totalTests: number;
  passedTests: number;
  failedTests: number;
  averageScore: number;
  totalTimeMs: number;
  timestamp: string;
}

export interface EvaluationReportDto {
  overallPassed: boolean;
  overallScore: number;
  passedCount: number;
  failedCount: number;
  summary: string;
  recommendations: string[];
  totalEvaluationTimeMs: number;
  results: EvaluationResultDto[];
  scoresByType: { [key: string]: number };
}

export interface EvaluationResultDto {
  evaluatorName: string;
  evaluationType: string;
  passed: boolean;
  score: number;
  confidence: number;
  explanation: string;
  threshold: number;
  evaluationTimeMs: number;
  metrics: { [key: string]: number };
  findings: FindingDto[];
}

export interface FindingDto {
  type: string;
  description: string;
  content: string;
  evidence: string;
  severity: string;
  scoreImpact: number;
}

export interface TestSuite {
  id?: string;
  name: string;
  description?: string;
  testCases: TestCase[];
  evaluatorTypes?: string[];
  createdAt?: string;
  updatedAt?: string;
}

export interface TestRunResult {
  runId: string;
  batchResult: BatchTestResult;
  timestamp: string;
}

// ═══════════════════════════════════════════════════════════════════════════════
// LLM-AS-JUDGE MODELS
// ═══════════════════════════════════════════════════════════════════════════════

export interface LlmProviderInfo {
  id: string;
  name: string;
  description: string;
  available: boolean;
  supportsJudge: boolean;
  models: LlmModelInfo[];
}

export interface LlmModelInfo {
  id: string;
  name: string;
  description: string;
  supportsTools: boolean;
}

export interface LlmJudgeRequest {
  testCaseId: string;
  providerId?: string;
  modelId?: string;
  query: string;
  expectedAnswer: string;
  actualAnswer: string;
  retrievedDocuments: string[];
  threshold?: number;
}

export interface LlmJudgeResult {
  success: boolean;
  testCaseId: string;
  error?: string;
  evaluation?: LlmJudgeEvaluation;
  timestamp: string;
}

export interface LlmJudgeEvaluation {
  passed: boolean;
  overallScore: number;
  summary: string;
  criteria: { [key: string]: LlmJudgeCriterion };
  recommendations: string[];
  rawResponse?: string;
  evaluationTimeMs: number;
  judgeModel: string;
  judgeProvider: string;
}

export interface LlmJudgeCriterion {
  score: number;
  explanation: string;
}

export interface CombinedEvalResult {
  success: boolean;
  testCaseId: string;
  error?: string;
  automatedResult?: TestCaseResult;
  llmJudgeResult?: LlmJudgeEvaluation;
  llmJudgeAvailable: boolean;
  timestamp: string;
}

export interface BatchCombinedResult {
  runId: string;
  success: boolean;
  error?: string;
  results: CombinedEvalResult[];
  totalTests: number;
  automatedPassedTests: number;
  llmJudgePassedTests: number;
  averageAutomatedScore: number;
  averageLlmJudgeScore: number;
  totalTimeMs: number;
  llmJudgeAvailable: boolean;
  timestamp: string;
}
