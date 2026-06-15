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

import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../../environments/environment';
import {
  EnhancedToolDefinition,
  ToolCategoryInfo,
  ToolsSummary,
  ToolUsageExample
} from '../models/api-models';

/**
 * Service for managing tool definitions.
 * Provides CRUD operations and discovery for MCP tools.
 */
@Injectable({
  providedIn: 'root'
})
export class ToolDefinitionService {
  private readonly baseUrl = `${environment.apiUrl}/tools`;

  constructor(private http: HttpClient) {}

  /**
   * Gets all tool definitions.
   */
  getAllTools(): Observable<EnhancedToolDefinition[]> {
    return this.http.get<EnhancedToolDefinition[]>(this.baseUrl);
  }

  /**
   * Gets enabled tools only.
   */
  getEnabledTools(): Observable<EnhancedToolDefinition[]> {
    return this.http.get<EnhancedToolDefinition[]>(`${this.baseUrl}?enabledOnly=true`);
  }

  /**
   * Gets tools by category.
   */
  getToolsByCategory(category: string): Observable<EnhancedToolDefinition[]> {
    return this.http.get<EnhancedToolDefinition[]>(`${this.baseUrl}?category=${encodeURIComponent(category)}`);
  }

  /**
   * Searches tools by query string.
   */
  searchTools(query: string): Observable<EnhancedToolDefinition[]> {
    return this.http.get<EnhancedToolDefinition[]>(`${this.baseUrl}?query=${encodeURIComponent(query)}`);
  }

  /**
   * Gets a specific tool by name.
   */
  getToolByName(name: string): Observable<EnhancedToolDefinition> {
    return this.http.get<EnhancedToolDefinition>(`${this.baseUrl}/${encodeURIComponent(name)}`);
  }

  /**
   * Gets the agent-friendly prompt for a tool.
   */
  getToolPrompt(name: string): Observable<string> {
    return this.http.get(`${this.baseUrl}/${encodeURIComponent(name)}/prompt`, { responseType: 'text' });
  }

  /**
   * Creates a new custom tool.
   */
  createTool(tool: EnhancedToolDefinition): Observable<EnhancedToolDefinition> {
    return this.http.post<EnhancedToolDefinition>(this.baseUrl, tool);
  }

  /**
   * Updates an existing tool.
   */
  updateTool(name: string, updates: Partial<EnhancedToolDefinition>): Observable<EnhancedToolDefinition> {
    return this.http.put<EnhancedToolDefinition>(`${this.baseUrl}/${encodeURIComponent(name)}`, updates);
  }

  /**
   * Deletes a custom tool.
   */
  deleteTool(name: string): Observable<void> {
    return this.http.delete<void>(`${this.baseUrl}/${encodeURIComponent(name)}`);
  }

  /**
   * Enables or disables a tool.
   */
  setToolEnabled(name: string, enabled: boolean): Observable<EnhancedToolDefinition> {
    return this.http.patch<EnhancedToolDefinition>(`${this.baseUrl}/${encodeURIComponent(name)}/enabled`, { enabled });
  }

  /**
   * Gets available tool categories.
   */
  getCategories(): Observable<{ [key: string]: ToolCategoryInfo }> {
    return this.http.get<{ [key: string]: ToolCategoryInfo }>(`${this.baseUrl}/meta/categories`);
  }

  /**
   * Gets tools grouped by category.
   */
  getToolsGroupedByCategory(): Observable<{ [key: string]: EnhancedToolDefinition[] }> {
    return this.http.get<{ [key: string]: EnhancedToolDefinition[] }>(`${this.baseUrl}/meta/grouped`);
  }

  /**
   * Gets the complete agent tools prompt.
   */
  getAgentToolsPrompt(): Observable<string> {
    return this.http.get(`${this.baseUrl}/meta/agent-prompt`, { responseType: 'text' });
  }

  /**
   * Gets a summary of all tools.
   */
  getToolsSummary(): Observable<ToolsSummary> {
    return this.http.get<ToolsSummary>(`${this.baseUrl}/meta/summary`);
  }

  /**
   * Refreshes tool definitions from disk and rediscovers built-in tools.
   */
  refreshTools(): Observable<{ message: string; toolCount: number }> {
    return this.http.post<{ message: string; toolCount: number }>(`${this.baseUrl}/meta/refresh`, {});
  }

  /**
   * Adds an example to a tool.
   */
  addExample(name: string, example: ToolUsageExample): Observable<EnhancedToolDefinition> {
    return this.http.post<EnhancedToolDefinition>(`${this.baseUrl}/${encodeURIComponent(name)}/examples`, example);
  }

  /**
   * Updates usage hints for a tool.
   */
  updateHints(name: string, hints: string[]): Observable<EnhancedToolDefinition> {
    return this.http.put<EnhancedToolDefinition>(`${this.baseUrl}/${encodeURIComponent(name)}/hints`, hints);
  }

  /**
   * Updates related tools for a tool.
   */
  updateRelatedTools(name: string, relatedTools: string[]): Observable<EnhancedToolDefinition> {
    return this.http.put<EnhancedToolDefinition>(`${this.baseUrl}/${encodeURIComponent(name)}/related`, relatedTools);
  }
}
