/*
 *   Copyright 2025 Kompile Inc.
 *  Licensed under the Apache License, Version 2.0
 */

import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { backendUrl } from './base.service';
import {
  SystemPrompt,
  SystemPromptTestResult,
  CreatePromptRequest,
  UpdatePromptRequest,
  CreateVersionRequest,
  TestPromptRequest,
  ComparePromptsRequest,
  PromptComparisonResult,
  PromptTestStats,
  EvalSuiteSummary
} from '../models/system-prompt.models';

@Injectable({
  providedIn: 'root'
})
export class SystemPromptService {
  private baseUrl = `${backendUrl}/system-prompts`;

  constructor(private http: HttpClient) {}

  // ==================== CRUD Operations ====================

  /**
   * List all prompts for the active fact sheet.
   */
  listPrompts(): Observable<SystemPrompt[]> {
    return this.http.get<SystemPrompt[]>(this.baseUrl);
  }

  /**
   * List prompts for a specific fact sheet.
   */
  listPromptsForFactSheet(factSheetId: number): Observable<SystemPrompt[]> {
    return this.http.get<SystemPrompt[]>(`${this.baseUrl}/fact-sheet/${factSheetId}`);
  }

  /**
   * Get a prompt by ID.
   */
  getPrompt(id: string): Observable<SystemPrompt> {
    return this.http.get<SystemPrompt>(`${this.baseUrl}/${id}`);
  }

  /**
   * Get the active prompt for the current fact sheet.
   */
  getActivePrompt(): Observable<SystemPrompt> {
    return this.http.get<SystemPrompt>(`${this.baseUrl}/active`);
  }

  /**
   * Get the active prompt for a specific fact sheet.
   */
  getActivePromptForFactSheet(factSheetId: number): Observable<SystemPrompt> {
    return this.http.get<SystemPrompt>(`${this.baseUrl}/fact-sheet/${factSheetId}/active`);
  }

  /**
   * Create a new prompt.
   */
  createPrompt(request: CreatePromptRequest): Observable<SystemPrompt> {
    return this.http.post<SystemPrompt>(this.baseUrl, request);
  }

  /**
   * Update an existing prompt.
   */
  updatePrompt(id: string, request: UpdatePromptRequest): Observable<SystemPrompt> {
    return this.http.put<SystemPrompt>(`${this.baseUrl}/${id}`, request);
  }

  /**
   * Delete a prompt.
   */
  deletePrompt(id: string): Observable<void> {
    return this.http.delete<void>(`${this.baseUrl}/${id}`);
  }

  // ==================== Versioning Operations ====================

  /**
   * Create a new version of a prompt.
   */
  createVersion(id: string, request: CreateVersionRequest): Observable<SystemPrompt> {
    return this.http.post<SystemPrompt>(`${this.baseUrl}/${id}/versions`, request);
  }

  /**
   * Get version history for a prompt.
   */
  getVersionHistory(id: string): Observable<SystemPrompt[]> {
    return this.http.get<SystemPrompt[]>(`${this.baseUrl}/${id}/versions`);
  }

  /**
   * Activate a specific prompt version.
   */
  activatePrompt(id: string): Observable<SystemPrompt> {
    return this.http.post<SystemPrompt>(`${this.baseUrl}/${id}/activate`, {});
  }

  // ==================== Search Operations ====================

  /**
   * Search prompts by name.
   */
  searchPrompts(query: string): Observable<SystemPrompt[]> {
    return this.http.get<SystemPrompt[]>(`${this.baseUrl}/search`, {
      params: { q: query }
    });
  }

  /**
   * Find prompts by tag.
   */
  findByTag(tag: string): Observable<SystemPrompt[]> {
    return this.http.get<SystemPrompt[]>(`${this.baseUrl}/by-tag/${tag}`);
  }

  // ==================== Variable Operations ====================

  /**
   * Extract variables from a prompt's content.
   */
  extractVariables(id: string): Observable<{ variables: string[] }> {
    return this.http.get<{ variables: string[] }>(`${this.baseUrl}/${id}/variables/extract`);
  }

  // ==================== Testing Operations ====================

  /**
   * Get available eval suites for a prompt.
   */
  getAvailableEvalSuites(id: string): Observable<EvalSuiteSummary[]> {
    return this.http.get<EvalSuiteSummary[]>(`${this.baseUrl}/${id}/eval-suites`);
  }

  /**
   * Record a test result for a prompt.
   */
  recordTestResult(id: string, request: TestPromptRequest): Observable<SystemPromptTestResult> {
    return this.http.post<SystemPromptTestResult>(`${this.baseUrl}/${id}/test`, request);
  }

  /**
   * Get test result history for a prompt.
   */
  getTestResults(id: string, evalSuiteId?: string): Observable<SystemPromptTestResult[]> {
    const params: any = {};
    if (evalSuiteId) {
      params.evalSuiteId = evalSuiteId;
    }
    return this.http.get<SystemPromptTestResult[]>(`${this.baseUrl}/${id}/test-results`, { params });
  }

  /**
   * Get test statistics for a prompt.
   */
  getTestStats(id: string): Observable<PromptTestStats> {
    return this.http.get<PromptTestStats>(`${this.baseUrl}/${id}/test-stats`);
  }

  /**
   * Compare two prompts.
   */
  comparePrompts(request: ComparePromptsRequest): Observable<PromptComparisonResult> {
    return this.http.post<PromptComparisonResult>(`${this.baseUrl}/compare`, request);
  }

  // ==================== Statistics Operations ====================

  /**
   * Get prompt count for the active fact sheet.
   */
  getPromptCount(): Observable<{ count: number }> {
    return this.http.get<{ count: number }>(`${this.baseUrl}/stats/count`);
  }

  // ==================== Utility Methods ====================

  /**
   * Parse variables JSON to array.
   */
  parseVariables(variablesJson: string | null | undefined): any[] {
    if (!variablesJson) {
      return [];
    }
    try {
      return JSON.parse(variablesJson);
    } catch {
      return [];
    }
  }

  /**
   * Parse tags JSON to array.
   */
  parseTags(tagsJson: string | null | undefined): string[] {
    if (!tagsJson) {
      return [];
    }
    try {
      return JSON.parse(tagsJson);
    } catch {
      return [];
    }
  }

  /**
   * Stringify variables array to JSON.
   */
  stringifyVariables(variables: any[]): string {
    return JSON.stringify(variables);
  }

  /**
   * Stringify tags array to JSON.
   */
  stringifyTags(tags: string[]): string {
    return JSON.stringify(tags);
  }

  /**
   * Create an empty prompt.
   */
  createEmptyPrompt(): CreatePromptRequest {
    return {
      name: 'New Prompt',
      description: '',
      content: '',
      variablesJson: '[]',
      tagsJson: '[]',
      createdBy: ''
    };
  }
}
