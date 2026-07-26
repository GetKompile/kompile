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
  PromptTemplate,
  TemplateCategoryInfo,
  TemplatesSummary,
  TemplateExample,
  RenderedTemplate
} from '../models/api-models';

/**
 * Service for managing prompt templates.
 * Provides CRUD operations, rendering, and template discovery.
 */
@Injectable({
  providedIn: 'root'
})
export class PromptTemplateService {
  private readonly baseUrl = `${environment.apiUrl}/prompts`;

  constructor(private http: HttpClient) {}

  /**
   * Gets all templates.
   */
  getAllTemplates(): Observable<PromptTemplate[]> {
    return this.http.get<PromptTemplate[]>(this.baseUrl);
  }

  /**
   * Gets enabled templates only.
   */
  getEnabledTemplates(): Observable<PromptTemplate[]> {
    return this.http.get<PromptTemplate[]>(`${this.baseUrl}?enabledOnly=true`);
  }

  /**
   * Gets templates by category.
   */
  getTemplatesByCategory(category: string): Observable<PromptTemplate[]> {
    return this.http.get<PromptTemplate[]>(`${this.baseUrl}?category=${encodeURIComponent(category)}`);
  }

  /**
   * Searches templates by query.
   */
  searchTemplates(query: string): Observable<PromptTemplate[]> {
    return this.http.get<PromptTemplate[]>(`${this.baseUrl}?query=${encodeURIComponent(query)}`);
  }

  /**
   * Gets templates by tag.
   */
  getTemplatesByTag(tag: string): Observable<PromptTemplate[]> {
    return this.http.get<PromptTemplate[]>(`${this.baseUrl}?tag=${encodeURIComponent(tag)}`);
  }

  /**
   * Gets a specific template by name.
   */
  getTemplateByName(name: string): Observable<PromptTemplate> {
    return this.http.get<PromptTemplate>(`${this.baseUrl}/${encodeURIComponent(name)}`);
  }

  /**
   * Creates a new template.
   */
  createTemplate(template: PromptTemplate): Observable<PromptTemplate> {
    return this.http.post<PromptTemplate>(this.baseUrl, template);
  }

  /**
   * Updates an existing template.
   */
  updateTemplate(name: string, updates: Partial<PromptTemplate>): Observable<PromptTemplate> {
    return this.http.put<PromptTemplate>(`${this.baseUrl}/${encodeURIComponent(name)}`, updates);
  }

  /**
   * Deletes a template.
   */
  deleteTemplate(name: string): Observable<void> {
    return this.http.delete<void>(`${this.baseUrl}/${encodeURIComponent(name)}`);
  }

  /**
   * Enables or disables a template.
   */
  setTemplateEnabled(name: string, enabled: boolean): Observable<PromptTemplate> {
    return this.http.patch<PromptTemplate>(`${this.baseUrl}/${encodeURIComponent(name)}/enabled`, { enabled });
  }

  /**
   * Renders a template with provided variables.
   */
  renderTemplate(name: string, variables: { [key: string]: any }): Observable<{ templateName: string; rendered: string }> {
    return this.http.post<{ templateName: string; rendered: string }>(`${this.baseUrl}/${encodeURIComponent(name)}/render`, variables);
  }

  /**
   * Renders a template with full context including system prompt.
   */
  renderTemplateFull(name: string, variables: { [key: string]: any }): Observable<RenderedTemplate> {
    return this.http.post<RenderedTemplate>(`${this.baseUrl}/${encodeURIComponent(name)}/render-full`, variables);
  }

  /**
   * Duplicates a template.
   */
  duplicateTemplate(name: string, newName: string): Observable<PromptTemplate> {
    return this.http.post<PromptTemplate>(`${this.baseUrl}/${encodeURIComponent(name)}/duplicate`, { newName });
  }

  /**
   * Gets available template categories.
   */
  getCategories(): Observable<{ [key: string]: TemplateCategoryInfo }> {
    return this.http.get<{ [key: string]: TemplateCategoryInfo }>(`${this.baseUrl}/meta/categories`);
  }

  /**
   * Gets templates grouped by category.
   */
  getTemplatesGroupedByCategory(): Observable<{ [key: string]: PromptTemplate[] }> {
    return this.http.get<{ [key: string]: PromptTemplate[] }>(`${this.baseUrl}/meta/grouped`);
  }

  /**
   * Gets a summary of all templates.
   */
  getTemplatesSummary(): Observable<TemplatesSummary> {
    return this.http.get<TemplatesSummary>(`${this.baseUrl}/meta/summary`);
  }

  /**
   * Refreshes templates from disk.
   */
  refreshTemplates(): Observable<{ message: string; templateCount: number }> {
    return this.http.post<{ message: string; templateCount: number }>(`${this.baseUrl}/meta/refresh`, {});
  }

  /**
   * Gets template variables.
   */
  getTemplateVariables(name: string): Observable<{ defined: any[]; extracted: string[] }> {
    return this.http.get<{ defined: any[]; extracted: string[] }>(`${this.baseUrl}/${encodeURIComponent(name)}/variables`);
  }

  /**
   * Adds an example to a template.
   */
  addExample(name: string, example: TemplateExample): Observable<PromptTemplate> {
    return this.http.post<PromptTemplate>(`${this.baseUrl}/${encodeURIComponent(name)}/examples`, example);
  }

  /**
   * Previews a template without saving.
   */
  previewTemplate(content: string, systemPrompt: string | null, variables: { [key: string]: any }): Observable<{ rendered: string; extractedVariables: string[] }> {
    return this.http.post<{ rendered: string; extractedVariables: string[] }>(`${this.baseUrl}/preview`, {
      content,
      systemPrompt,
      variables
    });
  }
}
