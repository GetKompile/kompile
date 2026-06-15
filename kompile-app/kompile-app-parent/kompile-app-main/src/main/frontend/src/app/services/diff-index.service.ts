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
import { backendUrl } from './base.service';

export interface DiffIndexEntry {
  id: string;
  agent: string;
  source: string;
  sessionId: string;
  sessionFingerprint: string;
  projectDirectory: string;
  filePath: string;
  toolName: string;
  diffType: string;
  oldString: string | null;
  newString: string | null;
  unifiedDiff: string | null;
  timestamp: string;
  linesAdded: number;
  linesRemoved: number;
}

export interface DiffProject {
  projectDirectory: string;
  projectName: string;
  entryCount: number;
  agents: string[];
  sources: string[];
  fileCount: number;
  totalLinesAdded: number;
  totalLinesRemoved: number;
}

export interface DiffAgent {
  agent: string;
  entryCount: number;
  fileCount: number;
  projects: string[];
  projectCount: number;
  totalLinesAdded: number;
  totalLinesRemoved: number;
}

export interface DiffIndexStats {
  totalEntries: number;
  indexing: boolean;
  totalLinesAdded: number;
  totalLinesRemoved: number;
  uniqueFiles: number;
  uniqueProjects: number;
  byAgent: Record<string, number>;
  bySource: Record<string, number>;
  byDiffType: Record<string, number>;
  indexMessage?: string;
  indexedCount?: number;
  sessionsScanned?: number;
  sourcesScanned?: number;
  indexErrors?: number;
  indexError?: string;
}

export interface DiffSearchParams {
  agent?: string;
  projectDirectory?: string;
  filePath?: string;
  contentQuery?: string;
  source?: string;
  limit?: number;
}

@Injectable({ providedIn: 'root' })
export class DiffIndexService {

  private readonly apiUrl = `${backendUrl}/diff-index`;

  constructor(private http: HttpClient) {}

  search(params: DiffSearchParams): Observable<DiffIndexEntry[]> {
    let httpParams = new HttpParams();
    if (params.agent) httpParams = httpParams.set('agent', params.agent);
    if (params.projectDirectory) httpParams = httpParams.set('projectDirectory', params.projectDirectory);
    if (params.filePath) httpParams = httpParams.set('filePath', params.filePath);
    if (params.contentQuery) httpParams = httpParams.set('contentQuery', params.contentQuery);
    if (params.source) httpParams = httpParams.set('source', params.source);
    if (params.limit) httpParams = httpParams.set('limit', params.limit.toString());
    return this.http.get<DiffIndexEntry[]>(`${this.apiUrl}/search`, { params: httpParams });
  }

  getEntry(id: string): Observable<DiffIndexEntry> {
    return this.http.get<DiffIndexEntry>(`${this.apiUrl}/entries/${id}`);
  }

  listProjects(): Observable<DiffProject[]> {
    return this.http.get<DiffProject[]>(`${this.apiUrl}/projects`);
  }

  listAgents(): Observable<DiffAgent[]> {
    return this.http.get<DiffAgent[]>(`${this.apiUrl}/agents`);
  }

  getStats(): Observable<DiffIndexStats> {
    return this.http.get<DiffIndexStats>(`${this.apiUrl}/stats`);
  }

  reindex(): Observable<Record<string, string>> {
    return this.http.post<Record<string, string>>(`${this.apiUrl}/reindex`, {});
  }
}
