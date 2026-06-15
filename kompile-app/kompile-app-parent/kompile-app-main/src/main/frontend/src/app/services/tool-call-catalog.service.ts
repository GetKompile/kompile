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
import { HttpClient, HttpParams } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../../environments/environment';

export interface ToolCallEntry {
  id: string;
  sessionId: string;
  toolName: string;
  toolInput: string;
  toolInputSummary: string;
  timestamp: string;
  source: string;
  agentName: string;
  isError: boolean;
  durationMs: number;
  category: string;
  projectDirectory: string;
}

export interface ToolCallSearchResult {
  results: ToolCallEntry[];
  totalCount: number;
  page: number;
  pageSize: number;
  totalPages: number;
}

export interface ToolCallStats {
  totalToolCalls: number;
  totalErrors: number;
  sessionCount: number;
  byTool: Record<string, number>;
  byCategory: Record<string, number>;
  byAgent: Record<string, number>;
  bySource: Record<string, number>;
  byProject: Record<string, number>;
}

export interface ToolCallFilterOptions {
  toolNames: string[];
  categories: string[];
  agents: string[];
  sources: string[];
  sessions: string[];
  projects: string[];
}

export interface ToolCallGroupedResult {
  groups: Record<string, ToolCallEntry[]>;
  groupCounts: Record<string, number>;
  groupField: string;
  totalGroups: number;
  totalCount: number;
}

@Injectable({ providedIn: 'root' })
export class ToolCallCatalogService {
  private baseUrl = `${environment.apiUrl}/tool-calls`;

  constructor(private http: HttpClient) {}

  search(params: {
    q?: string;
    tool?: string;
    session?: string;
    category?: string;
    agent?: string;
    source?: string;
    project?: string;
    sortBy?: string;
    sortDir?: string;
    page?: number;
    pageSize?: number;
  }): Observable<ToolCallSearchResult> {
    let httpParams = new HttpParams();
    if (params.q) httpParams = httpParams.set('q', params.q);
    if (params.tool) httpParams = httpParams.set('tool', params.tool);
    if (params.session) httpParams = httpParams.set('session', params.session);
    if (params.category) httpParams = httpParams.set('category', params.category);
    if (params.agent) httpParams = httpParams.set('agent', params.agent);
    if (params.source) httpParams = httpParams.set('source', params.source);
    if (params.project) httpParams = httpParams.set('project', params.project);
    if (params.sortBy) httpParams = httpParams.set('sortBy', params.sortBy);
    if (params.sortDir) httpParams = httpParams.set('sortDir', params.sortDir);
    if (params.page !== undefined) httpParams = httpParams.set('page', params.page.toString());
    if (params.pageSize !== undefined) httpParams = httpParams.set('pageSize', params.pageSize.toString());
    return this.http.get<ToolCallSearchResult>(this.baseUrl, { params: httpParams });
  }

  grouped(params: {
    groupBy: string;
    q?: string;
    tool?: string;
    session?: string;
    category?: string;
    agent?: string;
    source?: string;
    project?: string;
    sortBy?: string;
    sortDir?: string;
    limitPerGroup?: number;
  }): Observable<ToolCallGroupedResult> {
    let httpParams = new HttpParams().set('groupBy', params.groupBy);
    if (params.q) httpParams = httpParams.set('q', params.q);
    if (params.tool) httpParams = httpParams.set('tool', params.tool);
    if (params.session) httpParams = httpParams.set('session', params.session);
    if (params.category) httpParams = httpParams.set('category', params.category);
    if (params.agent) httpParams = httpParams.set('agent', params.agent);
    if (params.source) httpParams = httpParams.set('source', params.source);
    if (params.project) httpParams = httpParams.set('project', params.project);
    if (params.sortBy) httpParams = httpParams.set('sortBy', params.sortBy);
    if (params.sortDir) httpParams = httpParams.set('sortDir', params.sortDir);
    if (params.limitPerGroup !== undefined) httpParams = httpParams.set('limitPerGroup', params.limitPerGroup.toString());
    return this.http.get<ToolCallGroupedResult>(`${this.baseUrl}/grouped`, { params: httpParams });
  }

  getStats(): Observable<ToolCallStats> {
    return this.http.get<ToolCallStats>(`${this.baseUrl}/stats`);
  }

  getFilterOptions(): Observable<ToolCallFilterOptions> {
    return this.http.get<ToolCallFilterOptions>(`${this.baseUrl}/filters`);
  }

  getById(id: string): Observable<ToolCallEntry> {
    return this.http.get<ToolCallEntry>(`${this.baseUrl}/${id}`);
  }

  indexTranscripts(source: string = 'all', reindex: boolean = false): Observable<Record<string, any>> {
    let httpParams = new HttpParams()
      .set('source', source)
      .set('reindex', reindex.toString());
    return this.http.post<Record<string, any>>(`${this.baseUrl}/index`, null, { params: httpParams });
  }
}
