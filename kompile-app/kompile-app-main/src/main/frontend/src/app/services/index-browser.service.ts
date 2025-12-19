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
import { HttpClient, HttpParams, HttpErrorResponse } from '@angular/common/http';
import { Observable, throwError } from 'rxjs';
import { catchError } from 'rxjs/operators';
import { BaseService } from './base.service';
import {
  IndexedDocInfo,
  UpdateDocRequest,
  SimpleMessageResponse,
  SearchRequest,
  SearchResponse,
  IndexBrowserStatus,
  JobStatus,
  VectorPopulationUpdate,
  VectorPopulationServiceStatus,
  VectorPopulationSummary,
  VectorPopulationSubprocessStatus,
  VectorPopulationLaunchRequest,
  VectorPopulationTaskEnvironment
} from '../models/api-models';

@Injectable({
  providedIn: 'root'
})
export class IndexBrowserService extends BaseService {

  constructor(private http: HttpClient) {
    super();
  }

  getIndexBrowserStatus(): Observable<IndexBrowserStatus> {
    return this.http.get<IndexBrowserStatus>(`${this.backendUrl}/index-browser/status`)
      .pipe(catchError(this.handleError));
  }

  getAllIndexedDocs(offset: number = 0, limit: number = 10): Observable<IndexedDocInfo[]> {
    let params = new HttpParams()
      .set('offset', offset.toString())
      .set('limit', limit.toString());
    return this.http.get<IndexedDocInfo[]>(`${this.backendUrl}/index-browser/documents`, { params })
      .pipe(catchError(this.handleError));
  }

  getIndexedDoc(docId: string): Observable<IndexedDocInfo> {
    return this.http.get<IndexedDocInfo>(`${this.backendUrl}/index-browser/documents/${encodeURIComponent(docId)}`)
      .pipe(catchError(this.handleError));
  }

  updateIndexedDoc(docId: string, content: string): Observable<SimpleMessageResponse> {
    const request: UpdateDocRequest = { content };
    return this.http.put<SimpleMessageResponse>(`${this.backendUrl}/index-browser/documents/${encodeURIComponent(docId)}`, request)
      .pipe(catchError(this.handleError));
  }

  searchIndexedDocs(query: string, maxResults: number = 10): Observable<SearchResponse> {
    const request: SearchRequest = { query, maxResults };
    return this.http.post<SearchResponse>(`${this.backendUrl}/index-browser/search`, request)
      .pipe(catchError(this.handleError));
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // VECTOR STORE METHODS
  // ═══════════════════════════════════════════════════════════════════════════

  getVectorStoreDocuments(offset: number = 0, limit: number = 10): Observable<any[]> {
    let params = new HttpParams()
      .set('offset', offset.toString())
      .set('limit', limit.toString());
    return this.http.get<any[]>(`${this.backendUrl}/index-browser/vector-store/documents`, { params })
      .pipe(catchError(this.handleError));
  }

  searchVectorStore(query: string, maxResults: number = 10, similarityThreshold: number = 0.0): Observable<SearchResponse> {
    const request: SearchRequest = { query, maxResults, similarityThreshold };
    return this.http.post<SearchResponse>(`${this.backendUrl}/index-browser/vector-store/search`, request)
      .pipe(catchError(this.handleError));
  }

  createVectorIndexFromLucene(): Observable<SimpleMessageResponse> {
    return this.startVectorIndexCreation();
  }

  startVectorIndexCreation(): Observable<SimpleMessageResponse> {
    return this.http.post<SimpleMessageResponse>(`${this.backendUrl}/indexer/vector-index/start`, {})
      .pipe(catchError(this.handleError));
  }

  cancelVectorIndexCreation(): Observable<SimpleMessageResponse> {
    return this.http.post<SimpleMessageResponse>(`${this.backendUrl}/indexer/vector-index/cancel`, {})
      .pipe(catchError(this.handleError));
  }

  getVectorIndexJobStatus(): Observable<JobStatus> {
    return this.http.get<JobStatus>(`${this.backendUrl}/indexer/vector-index/status`)
      .pipe(catchError(this.handleError));
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // PIPELINE-BASED VECTOR POPULATION (New - uses full ingest pipeline)
  // ═══════════════════════════════════════════════════════════════════════════

  /**
   * Start vector store population using the full ingest pipeline.
   * Progress updates are sent via WebSocket on /topic/vector-population/progress
   */
  startVectorPopulation(): Observable<any> {
    return this.http.post<any>(`${this.backendUrl}/vector-population/start`, {})
      .pipe(catchError(this.handleError));
  }

  /**
   * Cancel an active vector population task.
   */
  cancelVectorPopulation(taskId: string): Observable<any> {
    return this.http.post<any>(`${this.backendUrl}/vector-population/cancel/${encodeURIComponent(taskId)}`, {})
      .pipe(catchError(this.handleError));
  }

  /**
   * Get status of a vector population task.
   */
  getVectorPopulationStatus(taskId: string): Observable<any> {
    return this.http.get<any>(`${this.backendUrl}/vector-population/status/${encodeURIComponent(taskId)}`)
      .pipe(catchError(this.handleError));
  }

  /**
   * Get all active vector population tasks.
   */
  getActiveVectorPopulationTasks(): Observable<any> {
    return this.http.get<any>(`${this.backendUrl}/vector-population/tasks`)
      .pipe(catchError(this.handleError));
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // SUBPROCESS-BASED VECTOR POPULATION (New - isolated JVM for stability)
  // ═══════════════════════════════════════════════════════════════════════════

  /**
   * Get vector population service status (available services, active tasks).
   */
  getVectorPopulationServiceStatus(): Observable<VectorPopulationServiceStatus> {
    return this.http.get<VectorPopulationServiceStatus>(`${this.backendUrl}/vector-population/service-status`)
      .pipe(catchError(this.handleError));
  }

  /**
   * Get vector population summary (counts by status).
   */
  getVectorPopulationSummary(): Observable<VectorPopulationSummary> {
    return this.http.get<VectorPopulationSummary>(`${this.backendUrl}/vector-population/summary`)
      .pipe(catchError(this.handleError));
  }

  /**
   * Launch a vector population subprocess.
   * This runs in an isolated JVM to prevent OOM crashes from affecting the main app.
   */
  launchVectorPopulationSubprocess(request: VectorPopulationLaunchRequest): Observable<any> {
    return this.http.post<any>(`${this.backendUrl}/vector-population/subprocess/launch`, request)
      .pipe(catchError(this.handleError));
  }

  /**
   * Cancel a vector population subprocess.
   */
  cancelVectorPopulationSubprocess(taskId: string): Observable<any> {
    return this.http.post<any>(`${this.backendUrl}/vector-population/subprocess/${encodeURIComponent(taskId)}/cancel`, {})
      .pipe(catchError(this.handleError));
  }

  /**
   * Get list of all active subprocesses.
   */
  getVectorPopulationSubprocessList(): Observable<{ available: boolean; subprocessCount: number; subprocesses: VectorPopulationSubprocessStatus[] }> {
    return this.http.get<{ available: boolean; subprocessCount: number; subprocesses: VectorPopulationSubprocessStatus[] }>(
      `${this.backendUrl}/vector-population/subprocess/list`
    ).pipe(catchError(this.handleError));
  }

  /**
   * Get subprocess status for a specific task.
   */
  getVectorPopulationSubprocessStatus(taskId: string): Observable<{ available: boolean; found: boolean; subprocess?: VectorPopulationSubprocessStatus }> {
    return this.http.get<{ available: boolean; found: boolean; subprocess?: VectorPopulationSubprocessStatus }>(
      `${this.backendUrl}/vector-population/subprocess/${encodeURIComponent(taskId)}`
    ).pipe(catchError(this.handleError));
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // PROGRESS TRACKER ENDPOINTS
  // ═══════════════════════════════════════════════════════════════════════════

  /**
   * Get all tracked vector population tasks (active and recently completed).
   */
  getTrackedVectorPopulationTasks(): Observable<{ available: boolean; taskCount: number; tasks: VectorPopulationUpdate[] }> {
    return this.http.get<{ available: boolean; taskCount: number; tasks: VectorPopulationUpdate[] }>(
      `${this.backendUrl}/vector-population/tracker/tasks`
    ).pipe(catchError(this.handleError));
  }

  /**
   * Get only active tracked tasks.
   */
  getActiveTrackedVectorPopulationTasks(): Observable<{ available: boolean; activeCount: number; tasks: VectorPopulationUpdate[] }> {
    return this.http.get<{ available: boolean; activeCount: number; tasks: VectorPopulationUpdate[] }>(
      `${this.backendUrl}/vector-population/tracker/tasks/active`
    ).pipe(catchError(this.handleError));
  }

  /**
   * Get tracked status for a specific task.
   */
  getTrackedVectorPopulationTaskStatus(taskId: string): Observable<{ available: boolean; found: boolean; task?: VectorPopulationUpdate }> {
    return this.http.get<{ available: boolean; found: boolean; task?: VectorPopulationUpdate }>(
      `${this.backendUrl}/vector-population/tracker/tasks/${encodeURIComponent(taskId)}`
    ).pipe(catchError(this.handleError));
  }

  /**
   * Get elapsed time for a tracked task.
   */
  getTrackedVectorPopulationElapsedTime(taskId: string): Observable<{ taskId: string; elapsedMs: number; elapsedSeconds: number; elapsedMinutes: number; isActive: boolean }> {
    return this.http.get<{ taskId: string; elapsedMs: number; elapsedSeconds: number; elapsedMinutes: number; isActive: boolean }>(
      `${this.backendUrl}/vector-population/tracker/tasks/${encodeURIComponent(taskId)}/elapsed`
    ).pipe(catchError(this.handleError));
  }

  /**
   * Get the ND4J environment snapshot captured at task launch time.
   * Useful for debugging and reproducing environment-specific issues.
   */
  getVectorPopulationTaskEnvironment(taskId: string): Observable<VectorPopulationTaskEnvironment> {
    return this.http.get<VectorPopulationTaskEnvironment>(
      `${this.backendUrl}/vector-population/tracker/tasks/${encodeURIComponent(taskId)}/environment`
    ).pipe(catchError(this.handleError));
  }

  /**
   * Get all captured task environments.
   */
  getAllVectorPopulationTaskEnvironments(): Observable<{ available: boolean; count: number; environments: { [taskId: string]: VectorPopulationTaskEnvironment } }> {
    return this.http.get<{ available: boolean; count: number; environments: { [taskId: string]: VectorPopulationTaskEnvironment } }>(
      `${this.backendUrl}/vector-population/tracker/environments`
    ).pipe(catchError(this.handleError));
  }

  /**
   * Get stored logs for a task (for page reload recovery).
   */
  getVectorPopulationTaskLogs(taskId: string): Observable<{ available: boolean; taskId: string; logCount: number; totalStoredCount: number; logs: any[] }> {
    return this.http.get<{ available: boolean; taskId: string; logCount: number; totalStoredCount: number; logs: any[] }>(
      `${this.backendUrl}/vector-population/tracker/tasks/${encodeURIComponent(taskId)}/logs`
    ).pipe(catchError(this.handleError));
  }

  /**
   * Get complete task state for page reload recovery.
   * Returns task status, environment, and logs in a single call.
   */
  getVectorPopulationTaskFullState(taskId: string): Observable<any> {
    return this.http.get<any>(
      `${this.backendUrl}/vector-population/tracker/tasks/${encodeURIComponent(taskId)}/full-state`
    ).pipe(catchError(this.handleError));
  }

  /**
   * Get full state for all active tasks.
   * Useful for restoring UI state after page reload.
   */
  getActiveTasksFullState(): Observable<{ available: boolean; activeCount: number; tasks: any[] }> {
    return this.http.get<{ available: boolean; activeCount: number; tasks: any[] }>(
      `${this.backendUrl}/vector-population/tracker/active-tasks-full-state`
    ).pipe(catchError(this.handleError));
  }

  private handleError(error: HttpErrorResponse) {
    let errorMessage = 'Unknown error!';
    if (error.error instanceof ErrorEvent) {
      errorMessage = `Error: ${error.error.message}`;
    } else {
      errorMessage = `Error Code: ${error.status}\nMessage: ${error.error?.message || error.error?.error || error.message || 'Server error'}`;
    }
    console.error(errorMessage);
    return throwError(() => new Error(errorMessage));
  }
}

