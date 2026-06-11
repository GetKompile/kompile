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
import { HttpClient, HttpParams } from '@angular/common/http';
import { Observable } from 'rxjs';
import { backendUrl } from './base.service';

@Injectable({ providedIn: 'root' })
export class CodeGraphService {
  private apiUrl = backendUrl + '/code-indexer';

  constructor(private http: HttpClient) {}

  // ─── Directory management ─────────────────────────────────────────────────

  addDirectory(projectId: string, path: string, options?: {
    displayName?: string;
    includePatterns?: string;
    excludePatterns?: string;
    languageOverrides?: Record<string, string>;
    description?: string;
    tags?: string;
  }): Observable<any> {
    const body: any = { projectId, path };
    if (options?.displayName) body.displayName = options.displayName;
    if (options?.includePatterns) body.includePatterns = options.includePatterns;
    if (options?.excludePatterns) body.excludePatterns = options.excludePatterns;
    if (options?.languageOverrides) body.languageOverrides = options.languageOverrides;
    if (options?.description) body.description = options.description;
    if (options?.tags) body.tags = options.tags;
    return this.http.post(`${this.apiUrl}/directories`, body);
  }

  removeDirectory(projectId: string, path: string): Observable<any> {
    return this.http.delete(`${this.apiUrl}/directories`, { params: { projectId, path } });
  }

  listDirectories(projectId: string): Observable<any[]> {
    return this.http.get<any[]>(`${this.apiUrl}/directories`, { params: { projectId } });
  }

  // ─── Graph building ───────────────────────────────────────────────────────

  buildGraph(projectId: string, directoryPath: string): Observable<any> {
    return this.http.post(`${this.apiUrl}/graph/build`, { projectId, directoryPath });
  }

  buildMulti(projectId: string, paths: string[]): Observable<any> {
    return this.http.post(`${this.apiUrl}/graph/build-multi`, { projectId, paths });
  }

  ensureConnectivity(projectId: string): Observable<any> {
    return this.http.post(`${this.apiUrl}/graph/ensure-connectivity`, null, { params: { projectId } });
  }

  // ─── Search ───────────────────────────────────────────────────────────────

  searchCode(projectId: string, query: string, type?: string, maxResults?: number): Observable<any[]> {
    let params = new HttpParams().set('projectId', projectId).set('query', query);
    if (type) { params = params.set('type', type); }
    if (maxResults) { params = params.set('maxResults', maxResults.toString()); }
    return this.http.get<any[]>(`${this.apiUrl}/search`, { params });
  }

  searchGraph(projectId: string, query: string, maxResults: number = 20): Observable<any> {
    return this.http.get(`${this.apiUrl}/graph/search`, {
      params: { projectId, query, maxResults: maxResults.toString() }
    });
  }

  // ─── Graph visualization & navigation ────────────────────────────────────

  getVisualization(projectId: string, maxNodes: number = 200): Observable<any> {
    return this.http.get(`${this.apiUrl}/graph/visualization`, {
      params: { projectId, maxNodes: maxNodes.toString() }
    });
  }

  getSymbolGraph(projectId: string, fqn: string, depth: number = 2): Observable<any> {
    return this.http.get(`${this.apiUrl}/graph/symbol`, {
      params: { projectId, fqn, depth: depth.toString() }
    });
  }

  getFileGraph(projectId: string, filePath: string): Observable<any> {
    return this.http.get(`${this.apiUrl}/graph/file`, { params: { projectId, filePath } });
  }

  // ─── Shortest path ────────────────────────────────────────────────────────

  getShortestPath(projectId: string, from: string, to: string, edgeType?: string): Observable<any> {
    let params = new HttpParams()
      .set('projectId', projectId)
      .set('from', from)
      .set('to', to);
    if (edgeType) { params = params.set('edgeType', edgeType); }
    return this.http.get(`${this.apiUrl}/graph/shortest-path`, { params });
  }

  // ─── Export ───────────────────────────────────────────────────────────────

  exportGraph(projectId: string, format: string, maxNodes: number = 500): Observable<Blob> {
    return this.http.get(`${this.apiUrl}/graph/export`, {
      params: { projectId, format, maxNodes: maxNodes.toString() },
      responseType: 'blob'
    });
  }

  // ─── Entity lookup ────────────────────────────────────────────────────────

  getEntityByFqn(projectId: string, fqn: string): Observable<any> {
    return this.http.get(`${this.apiUrl}/entity`, { params: { projectId, fqn } });
  }

  getEntities(projectId: string, options: { file?: string; parentFqn?: string }): Observable<any> {
    let params = new HttpParams().set('projectId', projectId);
    if (options.file) { params = params.set('file', options.file); }
    if (options.parentFqn) { params = params.set('parentFqn', options.parentFqn); }
    return this.http.get(`${this.apiUrl}/entities`, { params });
  }

  getEntitiesByLanguage(projectId: string, language: string): Observable<any> {
    return this.http.get(`${this.apiUrl}/entities-by-language`, { params: { projectId, language } });
  }

  getProjectLanguages(projectId: string): Observable<any> {
    return this.http.get(`${this.apiUrl}/project-languages`, { params: { projectId } });
  }

  // ─── Relations ───────────────────────────────────────────────────────────

  getRelated(projectId: string, fqn: string, maxDepth: number = 2): Observable<any> {
    return this.http.get(`${this.apiUrl}/related`, {
      params: { projectId, fqn, maxDepth: maxDepth.toString() }
    });
  }

  getRelations(projectId: string, fqn: string, type?: string): Observable<any> {
    let params = new HttpParams().set('projectId', projectId).set('fqn', fqn);
    if (type) { params = params.set('type', type); }
    return this.http.get(`${this.apiUrl}/relations`, { params });
  }

  getReverseRelations(projectId: string, fqn: string, type?: string): Observable<any> {
    let params = new HttpParams().set('projectId', projectId).set('fqn', fqn);
    if (type) { params = params.set('type', type); }
    return this.http.get(`${this.apiUrl}/reverse-relations`, { params });
  }

  getCallers(projectId: string, name: string): Observable<any> {
    return this.http.get(`${this.apiUrl}/callers`, { params: { projectId, name } });
  }

  getUnusedFunctions(projectId: string, options?: {
    visibility?: string; language?: string; includeTests?: boolean; maxResults?: number;
  }): Observable<any> {
    let params = new HttpParams().set('projectId', projectId);
    if (options?.visibility) { params = params.set('visibility', options.visibility); }
    if (options?.language) { params = params.set('language', options.language); }
    if (options?.includeTests !== undefined) { params = params.set('includeTests', options.includeTests.toString()); }
    if (options?.maxResults) { params = params.set('maxResults', options.maxResults.toString()); }
    return this.http.get(`${this.apiUrl}/unused-functions`, { params });
  }

  // ─── Statistics ───────────────────────────────────────────────────────────

  getStatistics(projectId: string): Observable<any> {
    return this.http.get(`${this.apiUrl}/statistics`, { params: { projectId } });
  }

  getGraphStatistics(projectId: string): Observable<any> {
    return this.http.get(`${this.apiUrl}/graph/statistics`, { params: { projectId } });
  }

  // ─── Status ───────────────────────────────────────────────────────────────

  getStatus(projectId: string): Observable<any> {
    return this.http.get(`${this.apiUrl}/status/${encodeURIComponent(projectId)}`);
  }

  // ─── Languages ────────────────────────────────────────────────────────────

  getLanguages(): Observable<any> {
    return this.http.get(`${this.apiUrl}/languages`);
  }

  setLanguageOverride(options: { pattern?: string; file?: string; language: string }): Observable<any> {
    return this.http.post(`${this.apiUrl}/languages/override`, options);
  }

  // ─── Index ────────────────────────────────────────────────────────────────

  indexCodebase(projectId: string, rootPath: string): Observable<any> {
    return this.http.post(`${this.apiUrl}/index`, { projectId, rootPath });
  }

  indexAll(projectId: string): Observable<any> {
    return this.http.post(`${this.apiUrl}/index-all`, null, { params: { projectId } });
  }

  // ─── Project management ──────────────────────────────────────────────────

  deleteProject(projectId: string): Observable<any> {
    return this.http.delete(`${this.apiUrl}/project`, { params: { projectId } });
  }

  // ─── Composite graph queries ──────────────────────────────────────────────

  getImpactAnalysis(projectId: string, fqn: string): Observable<any> {
    return this.http.get(`${this.apiUrl}/graph/impact-analysis`, { params: { projectId, fqn } });
  }

  getDependencyTree(projectId: string, fqn: string, maxDepth: number = 3): Observable<any> {
    return this.http.get(`${this.apiUrl}/graph/dependency-tree`, {
      params: { projectId, fqn, maxDepth: maxDepth.toString() }
    });
  }

  getComponentMap(projectId: string, scope: string): Observable<any> {
    return this.http.get(`${this.apiUrl}/graph/component-map`, { params: { projectId, scope } });
  }

  getSymbolDossier(projectId: string, fqn: string): Observable<any> {
    return this.http.get(`${this.apiUrl}/graph/symbol-dossier`, { params: { projectId, fqn } });
  }

  exportLocalizedGraph(projectId: string, focus: string, format: string = 'svg', depth: number = 2): Observable<Blob> {
    return this.http.get(`${this.apiUrl}/graph/export-local`, {
      params: { projectId, focus, format, depth: depth.toString() },
      responseType: 'blob'
    });
  }

  // ─── Test coverage & code paths ──────────────────────────────────────

  getTestFrameworks(projectId: string): Observable<any> {
    return this.http.get(`${this.apiUrl}/graph/test-frameworks`, { params: { projectId } });
  }

  getTestCoverage(projectId: string): Observable<any> {
    return this.http.get(`${this.apiUrl}/graph/test-coverage`, { params: { projectId } });
  }

  getTestsForSymbol(projectId: string, fqn: string): Observable<any> {
    return this.http.get(`${this.apiUrl}/graph/tests-for-symbol`, { params: { projectId, fqn } });
  }

  getCodePaths(projectId: string, fqn: string, maxDepth: number = 5): Observable<any> {
    return this.http.get(`${this.apiUrl}/graph/code-paths`, {
      params: { projectId, fqn, maxDepth: maxDepth.toString() }
    });
  }
}
