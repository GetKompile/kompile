/*
 *   Copyright 2025 Kompile Inc.
 *  Licensed under the Apache License, Version 2.0
 */

/**
 * System prompt entity.
 */
export interface SystemPrompt {
  id: string;
  name: string;
  description?: string;
  content: string;
  factSheetId: number;
  version: number;
  parentVersionId?: string;
  isActive: boolean;
  variablesJson?: string;
  tagsJson?: string;
  changeNotes?: string;
  createdBy?: string;
  createdAt: string;
  updatedAt: string;
}

/**
 * Variable definition for a prompt.
 */
export interface PromptVariable {
  name: string;
  type: 'string' | 'number' | 'boolean' | 'array' | 'object';
  required: boolean;
  defaultValue?: any;
  description?: string;
}

/**
 * Test result for a prompt.
 */
export interface SystemPromptTestResult {
  id: string;
  promptId: string;
  promptName?: string;
  promptVersion?: number;
  evalSuiteId: string;
  evalSuiteName?: string;
  passed: boolean;
  score?: number;
  passedCount: number;
  failedCount: number;
  totalCount: number;
  resultsJson?: string;
  errorMessage?: string;
  startedAt: string;
  completedAt?: string;
  executionTimeMs?: number;
}

/**
 * Request to create a new prompt.
 */
export interface CreatePromptRequest {
  name: string;
  description?: string;
  content: string;
  variablesJson?: string;
  tagsJson?: string;
  createdBy?: string;
}

/**
 * Request to update a prompt.
 */
export interface UpdatePromptRequest {
  name?: string;
  description?: string;
  content?: string;
  variablesJson?: string;
  tagsJson?: string;
  changeNotes?: string;
}

/**
 * Request to create a new version.
 */
export interface CreateVersionRequest {
  content?: string;
  changeNotes?: string;
}

/**
 * Request to test a prompt.
 */
export interface TestPromptRequest {
  evalSuiteId: string;
  passed: boolean;
  score: number;
  passedCount: number;
  failedCount: number;
  detailedResults?: { [key: string]: any };
}

/**
 * Request to compare prompts.
 */
export interface ComparePromptsRequest {
  promptId1: string;
  promptId2: string;
  evalSuiteId: string;
}

/**
 * Comparison result for two prompts.
 */
export interface PromptComparisonResult {
  prompt1: {
    id: string;
    name: string;
    version: number;
    result?: PromptResultSummary;
  };
  prompt2: {
    id: string;
    name: string;
    version: number;
    result?: PromptResultSummary;
  };
  scoreDifference?: number;
  winner?: string;
}

/**
 * Summary of a prompt test result.
 */
export interface PromptResultSummary {
  id: string;
  passed: boolean;
  score?: number;
  passedCount: number;
  failedCount: number;
  totalCount: number;
  completedAt?: string;
  executionTimeMs?: number;
}

/**
 * Statistics for a prompt's test history.
 */
export interface PromptTestStats {
  totalTests: number;
  passedTests: number;
  failedTests: number;
  passRate: number;
  averageScore: number;
  lastTestAt?: string;
  lastTestPassed?: boolean;
  lastTestScore?: number;
}

/**
 * Eval suite summary for display.
 */
export interface EvalSuiteSummary {
  id: string;
  name: string;
  description?: string;
  factSheetId: number;
  enabled: boolean;
  testCaseCount?: number;
}
