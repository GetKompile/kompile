/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { BehaviorSubject, Observable, tap } from 'rxjs';
import { backendUrl } from './base.service';

export interface CodeProject {
  id: string;
  projectId: string;
  name: string;
  description: string | null;
  isActive: boolean;
  color: string;
  icon: string;
  indexState: string;
  totalFiles: number;
  totalEntities: number;
  totalRelations: number;
  languages: string | null;
  lastIndexedAt: string | null;
  tags: string | null;
  autoIndex: boolean;
  directoryCount: number;
  directories?: CodeProjectDirectory[];
  currentIndexingStatus?: IndexingProgress;
}

export interface CodeProjectDirectory {
  id: string;
  absolutePath: string;
  displayName: string | null;
  status: string;
  filesIndexed: number;
  entitiesFound: number;
  relationsCreated: number;
  errors: number;
  lastIndexedAt: string | null;
  includePatterns: string | null;
  excludePatterns: string | null;
  description: string | null;
  tags: string | null;
}

export interface IndexingProgress {
  projectId: string;
  totalFiles: number;
  filesProcessed: number;
  filesSkipped: number;
  entitiesFound: number;
  relationsCreated: number;
  errors: number;
  completed: boolean;
  incremental: boolean;
  progressPercent: number;
}

export interface ProjectSession {
  sessionId: string;
  title: string;
  description: string | null;
  createdAt: string;
  updatedAt: string;
  source: string | null;
  codeProjectId: string | null;
  messageCount: number;
}

export interface CreateCodeProjectRequest {
  projectId: string;
  name: string;
  description?: string;
  color?: string;
  icon?: string;
  tags?: string;
  autoIndex?: boolean;
  directories?: { path: string; displayName?: string; description?: string }[];
}

@Injectable({ providedIn: 'root' })
export class CodeProjectService {
  private apiUrl = backendUrl + '/code-projects';

  private projectsSubject = new BehaviorSubject<CodeProject[]>([]);
  projects$ = this.projectsSubject.asObservable();

  private activeProjectSubject = new BehaviorSubject<CodeProject | null>(null);
  activeProject$ = this.activeProjectSubject.asObservable();

  constructor(private http: HttpClient) {}

  loadProjects(): Observable<CodeProject[]> {
    return this.http.get<CodeProject[]>(this.apiUrl).pipe(
      tap(projects => {
        this.projectsSubject.next(projects);
        const active = projects.find(p => p.isActive);
        this.activeProjectSubject.next(active || null);
      })
    );
  }

  getProject(projectId: string): Observable<CodeProject> {
    return this.http.get<CodeProject>(`${this.apiUrl}/${projectId}`);
  }

  createProject(request: CreateCodeProjectRequest): Observable<CodeProject> {
    return this.http.post<CodeProject>(this.apiUrl, request).pipe(
      tap(() => this.loadProjects().subscribe())
    );
  }

  updateProject(projectId: string, updates: Partial<CodeProject>): Observable<CodeProject> {
    return this.http.put<CodeProject>(`${this.apiUrl}/${projectId}`, updates).pipe(
      tap(() => this.loadProjects().subscribe())
    );
  }

  deleteProject(projectId: string): Observable<any> {
    return this.http.delete(`${this.apiUrl}/${projectId}`).pipe(
      tap(() => this.loadProjects().subscribe())
    );
  }

  activateProject(projectId: string): Observable<CodeProject> {
    return this.http.post<CodeProject>(`${this.apiUrl}/${projectId}/activate`, {}).pipe(
      tap(() => this.loadProjects().subscribe())
    );
  }

  getActiveProject(): Observable<any> {
    return this.http.get(`${this.apiUrl}/active`);
  }

  indexProject(projectId: string, forceReindex: boolean = false): Observable<any> {
    return this.http.post(`${this.apiUrl}/${projectId}/index`, null, {
      params: { forceReindex: forceReindex.toString() }
    });
  }

  getIndexStatus(projectId: string): Observable<any> {
    return this.http.get(`${this.apiUrl}/${projectId}/index-status`);
  }

  getFactSheet(projectId: string): Observable<any> {
    return this.http.get(`${this.apiUrl}/${projectId}/fact-sheet`);
  }

  // Async index via the code-indexer endpoint
  indexAsync(projectId: string, rootPath: string, forceReindex: boolean = false): Observable<any> {
    return this.http.post(backendUrl + '/code-indexer/index-async', {
      projectId, rootPath, forceReindex
    });
  }

  getProjectSessions(projectId: string): Observable<ProjectSession[]> {
    return this.http.get<ProjectSession[]>(
      backendUrl + '/chat-history/sessions/by-project/' + projectId
    );
  }
}
