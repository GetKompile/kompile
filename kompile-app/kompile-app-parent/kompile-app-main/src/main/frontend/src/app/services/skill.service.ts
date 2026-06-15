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

export interface SkillDefinition {
  name: string;
  displayName?: string;
  description?: string;
  category?: string;
  promptTemplate?: string;
  allowedTools?: string[];
  maxSteps?: number;
  modelHint?: string;
  builtIn?: boolean;
}

export interface SkillsSummary {
  total: number;
  builtIn: number;
  custom: number;
  categories: string[];
}

/**
 * Service for managing kompile skills via the REST API.
 */
@Injectable({
  providedIn: 'root'
})
export class SkillService {
  private readonly baseUrl = `${environment.apiUrl}/skills`;

  constructor(private http: HttpClient) {}

  /** List all skills, optionally filtered by category or query. */
  listAll(category?: string, query?: string): Observable<SkillDefinition[]> {
    let url = this.baseUrl;
    const params: string[] = [];
    if (category) params.push(`category=${encodeURIComponent(category)}`);
    if (query) params.push(`query=${encodeURIComponent(query)}`);
    if (params.length > 0) url += '?' + params.join('&');
    return this.http.get<SkillDefinition[]>(url);
  }

  /** Get a specific skill by name. */
  getByName(name: string): Observable<SkillDefinition> {
    return this.http.get<SkillDefinition>(`${this.baseUrl}/${encodeURIComponent(name)}`);
  }

  /** Create a new skill. */
  create(skill: SkillDefinition): Observable<SkillDefinition> {
    return this.http.post<SkillDefinition>(this.baseUrl, skill);
  }

  /** Update an existing skill. */
  update(name: string, updates: Partial<SkillDefinition>): Observable<SkillDefinition> {
    return this.http.put<SkillDefinition>(`${this.baseUrl}/${encodeURIComponent(name)}`, updates);
  }

  /** Delete a skill. */
  delete(name: string): Observable<any> {
    return this.http.delete(`${this.baseUrl}/${encodeURIComponent(name)}`);
  }

  /** Expand a skill template with arguments. */
  expandTemplate(name: string, args: string): Observable<{ name: string; expanded: string }> {
    return this.http.post<{ name: string; expanded: string }>(
      `${this.baseUrl}/${encodeURIComponent(name)}/expand`, { args });
  }

  /** Get skills summary. */
  getSummary(): Observable<SkillsSummary> {
    return this.http.get<SkillsSummary>(`${this.baseUrl}/meta/summary`);
  }

  /** Get skills grouped by category. */
  getByCategory(): Observable<{ [key: string]: SkillDefinition[] }> {
    return this.http.get<{ [key: string]: SkillDefinition[] }>(`${this.baseUrl}/meta/categories`);
  }

  /** Get generated skills.md content. */
  getSkillsMarkdown(compact: boolean = false): Observable<{ content: string; skillCount: number }> {
    return this.http.get<{ content: string; skillCount: number }>(
      `${this.baseUrl}/meta/markdown?compact=${compact}`);
  }

  /** Reload skills from disk. */
  refresh(): Observable<{ message: string; skillCount: number }> {
    return this.http.post<{ message: string; skillCount: number }>(
      `${this.baseUrl}/meta/refresh`, {});
  }
}
