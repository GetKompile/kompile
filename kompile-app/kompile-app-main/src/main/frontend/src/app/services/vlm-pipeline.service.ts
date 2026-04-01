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
import { HttpClient, HttpErrorResponse, HttpParams } from '@angular/common/http';
import { Observable, throwError, BehaviorSubject } from 'rxjs';
import { catchError, tap } from 'rxjs/operators';
import {
  VlmPipelineDefinition,
  VlmStageDefinition,
  VlmCustomModelSet,
  PipelineListItem,
  StageListItem,
  ModelSetListItem,
  VlmRegistryStats,
  ValidationResponse,
  ApiResponse,
  PipelineCacheStats,
  CacheEntriesResponse,
  CacheEntryDetailResponse
} from '../models/vlm-pipeline-models';

/**
 * Angular service for VLM pipeline configuration API.
 *
 * Provides CRUD operations for:
 * - Dynamic pipelines
 * - Custom stages
 * - Custom model sets
 *
 * Uses the /api/vlm/config endpoints from VlmPipelineConfigController.
 */
@Injectable({
  providedIn: 'root'
})
export class VlmPipelineService {

  private baseUrl: string;

  // Observable for pipeline list (cached)
  private pipelinesSubject = new BehaviorSubject<PipelineListItem[]>([]);
  public pipelines$ = this.pipelinesSubject.asObservable();

  // Observable for stages list (cached)
  private stagesSubject = new BehaviorSubject<StageListItem[]>([]);
  public stages$ = this.stagesSubject.asObservable();

  // Observable for model sets list (cached)
  private modelSetsSubject = new BehaviorSubject<ModelSetListItem[]>([]);
  public modelSets$ = this.modelSetsSubject.asObservable();

  constructor(private http: HttpClient) {
    // Dynamic API URL based on current location
    if (typeof window !== 'undefined' && window.location) {
      const protocol = window.location.protocol;
      const hostname = window.location.hostname;
      const port = window.location.port;
      this.baseUrl = `${protocol}//${hostname}${port ? ':' + port : ''}/api/vlm/config`;
    } else {
      this.baseUrl = '/api/vlm/config';
    }
  }

  // ==================== Pipeline Operations ====================

  /**
   * List all pipelines.
   * @param customOnly If true, return only custom (non-builtin) pipelines
   */
  listPipelines(customOnly: boolean = false): Observable<PipelineListItem[]> {
    const params = new HttpParams().set('customOnly', customOnly.toString());
    return this.http.get<PipelineListItem[]>(`${this.baseUrl}/pipelines`, { params })
      .pipe(
        tap(pipelines => this.pipelinesSubject.next(pipelines)),
        catchError(this.handleError)
      );
  }

  /**
   * Get a specific pipeline by ID.
   */
  getPipeline(pipelineId: string): Observable<VlmPipelineDefinition> {
    return this.http.get<VlmPipelineDefinition>(`${this.baseUrl}/pipelines/${pipelineId}`)
      .pipe(catchError(this.handleError));
  }

  /**
   * Create a new pipeline.
   */
  createPipeline(pipeline: VlmPipelineDefinition): Observable<ApiResponse> {
    return this.http.post<ApiResponse>(`${this.baseUrl}/pipelines`, pipeline)
      .pipe(
        tap(() => this.refreshPipelines()),
        catchError(this.handleError)
      );
  }

  /**
   * Update an existing pipeline.
   */
  updatePipeline(pipelineId: string, pipeline: VlmPipelineDefinition): Observable<ApiResponse> {
    return this.http.put<ApiResponse>(`${this.baseUrl}/pipelines/${pipelineId}`, pipeline)
      .pipe(
        tap(() => this.refreshPipelines()),
        catchError(this.handleError)
      );
  }

  /**
   * Delete a pipeline.
   */
  deletePipeline(pipelineId: string): Observable<ApiResponse> {
    return this.http.delete<ApiResponse>(`${this.baseUrl}/pipelines/${pipelineId}`)
      .pipe(
        tap(() => this.refreshPipelines()),
        catchError(this.handleError)
      );
  }

  /**
   * Validate a pipeline without saving.
   */
  validatePipeline(pipeline: VlmPipelineDefinition): Observable<ValidationResponse> {
    return this.http.post<ValidationResponse>(`${this.baseUrl}/pipelines/validate`, pipeline)
      .pipe(catchError(this.handleError));
  }

  /**
   * Refresh the pipelines cache.
   */
  refreshPipelines(): void {
    this.listPipelines().subscribe();
  }

  // ==================== Stage Operations ====================

  /**
   * List all stages.
   * @param customOnly If true, return only custom (non-builtin) stages
   */
  listStages(customOnly: boolean = false): Observable<StageListItem[]> {
    const params = new HttpParams().set('customOnly', customOnly.toString());
    return this.http.get<StageListItem[]>(`${this.baseUrl}/stages`, { params })
      .pipe(
        tap(stages => this.stagesSubject.next(stages)),
        catchError(this.handleError)
      );
  }

  /**
   * Get a specific stage by ID.
   */
  getStage(stageId: string): Observable<VlmStageDefinition> {
    return this.http.get<VlmStageDefinition>(`${this.baseUrl}/stages/${stageId}`)
      .pipe(catchError(this.handleError));
  }

  /**
   * Create a custom stage.
   */
  createStage(stage: VlmStageDefinition): Observable<ApiResponse> {
    return this.http.post<ApiResponse>(`${this.baseUrl}/stages`, stage)
      .pipe(
        tap(() => this.refreshStages()),
        catchError(this.handleError)
      );
  }

  /**
   * Delete a custom stage.
   */
  deleteStage(stageId: string): Observable<ApiResponse> {
    return this.http.delete<ApiResponse>(`${this.baseUrl}/stages/${stageId}`)
      .pipe(
        tap(() => this.refreshStages()),
        catchError(this.handleError)
      );
  }

  /**
   * Refresh the stages cache.
   */
  refreshStages(): void {
    this.listStages().subscribe();
  }

  // ==================== Model Set Operations ====================

  /**
   * List all model sets.
   * @param customOnly If true, return only custom (non-builtin) model sets
   */
  listModelSets(customOnly: boolean = false): Observable<ModelSetListItem[]> {
    const params = new HttpParams().set('customOnly', customOnly.toString());
    return this.http.get<ModelSetListItem[]>(`${this.baseUrl}/model-sets`, { params })
      .pipe(
        tap(modelSets => this.modelSetsSubject.next(modelSets)),
        catchError(this.handleError)
      );
  }

  /**
   * Get a specific model set by ID.
   */
  getModelSet(setId: string): Observable<VlmCustomModelSet> {
    return this.http.get<VlmCustomModelSet>(`${this.baseUrl}/model-sets/${setId}`)
      .pipe(catchError(this.handleError));
  }

  /**
   * Create a custom model set.
   */
  createModelSet(modelSet: VlmCustomModelSet): Observable<ApiResponse> {
    return this.http.post<ApiResponse>(`${this.baseUrl}/model-sets`, modelSet)
      .pipe(
        tap(() => this.refreshModelSets()),
        catchError(this.handleError)
      );
  }

  /**
   * Delete a custom model set.
   */
  deleteModelSet(setId: string): Observable<ApiResponse> {
    return this.http.delete<ApiResponse>(`${this.baseUrl}/model-sets/${setId}`)
      .pipe(
        tap(() => this.refreshModelSets()),
        catchError(this.handleError)
      );
  }

  /**
   * Refresh the model sets cache.
   */
  refreshModelSets(): void {
    this.listModelSets().subscribe();
  }

  // ==================== Registry Operations ====================

  /**
   * Get registry statistics.
   */
  getStats(): Observable<VlmRegistryStats> {
    return this.http.get<VlmRegistryStats>(`${this.baseUrl}/stats`)
      .pipe(catchError(this.handleError));
  }

  /**
   * Clear all custom configurations.
   */
  clearCustomConfigs(): Observable<ApiResponse> {
    return this.http.post<ApiResponse>(`${this.baseUrl}/clear-custom`, {})
      .pipe(
        tap(() => {
          this.refreshPipelines();
          this.refreshStages();
          this.refreshModelSets();
        }),
        catchError(this.handleError)
      );
  }

  // ==================== Cache Operations ====================

  /**
   * Get cache statistics.
   */
  getCacheStats(): Observable<PipelineCacheStats> {
    return this.http.get<PipelineCacheStats>(`${this.baseUrl}/cache/stats`)
      .pipe(catchError(this.handleError));
  }

  /**
   * Invalidate cache for a specific content hash.
   */
  invalidateCacheByContent(contentHash: string): Observable<ApiResponse> {
    return this.http.delete<ApiResponse>(`${this.baseUrl}/cache/content/${contentHash}`)
      .pipe(catchError(this.handleError));
  }

  /**
   * Invalidate cache for a specific pipeline.
   */
  invalidateCacheByPipeline(pipelineId: string): Observable<ApiResponse> {
    return this.http.delete<ApiResponse>(`${this.baseUrl}/cache/pipeline/${pipelineId}`)
      .pipe(catchError(this.handleError));
  }

  /**
   * Evict expired cache entries.
   */
  evictExpiredCache(): Observable<ApiResponse> {
    return this.http.post<ApiResponse>(`${this.baseUrl}/cache/evict`, {})
      .pipe(catchError(this.handleError));
  }

  /**
   * Clear all cache entries.
   */
  clearCache(): Observable<ApiResponse> {
    return this.http.post<ApiResponse>(`${this.baseUrl}/cache/clear`, {})
      .pipe(catchError(this.handleError));
  }

  /**
   * List cache entries with pagination and optional type filter.
   */
  listCacheEntries(offset: number = 0, limit: number = 50, type?: string): Observable<CacheEntriesResponse> {
    let params = new HttpParams()
      .set('offset', offset.toString())
      .set('limit', limit.toString());
    if (type) {
      params = params.set('type', type);
    }
    return this.http.get<CacheEntriesResponse>(`${this.baseUrl}/cache/entries`, { params })
      .pipe(catchError(this.handleError));
  }

  /**
   * Get a single cache entry detail.
   */
  getCacheEntryDetail(cacheKey: string): Observable<CacheEntryDetailResponse> {
    return this.http.get<CacheEntryDetailResponse>(`${this.baseUrl}/cache/entries/${encodeURIComponent(cacheKey)}`)
      .pipe(catchError(this.handleError));
  }

  // ==================== Utility Methods ====================

  /**
   * Refresh all caches.
   */
  refreshAll(): void {
    this.refreshPipelines();
    this.refreshStages();
    this.refreshModelSets();
  }

  /**
   * Get builtin stages only (synchronous from cache).
   */
  getBuiltinStagesFromCache(): StageListItem[] {
    return this.stagesSubject.getValue().filter(s => s.isBuiltin);
  }

  /**
   * Get builtin model sets only (synchronous from cache).
   */
  getBuiltinModelSetsFromCache(): ModelSetListItem[] {
    return this.modelSetsSubject.getValue().filter(m => m.isBuiltin);
  }

  /**
   * Get enabled pipelines only (synchronous from cache).
   */
  getEnabledPipelinesFromCache(): PipelineListItem[] {
    return this.pipelinesSubject.getValue().filter(p => p.enabled);
  }

  // ==================== Error Handling ====================

  private handleError(error: HttpErrorResponse): Observable<never> {
    let errorMessage = 'An error occurred';

    if (error.error instanceof ErrorEvent) {
      // Client-side error
      errorMessage = `Error: ${error.error.message}`;
    } else {
      // Server-side error
      if (error.error?.message) {
        errorMessage = error.error.message;
      } else if (error.error?.errors) {
        errorMessage = error.error.errors.join(', ');
      } else if (error.error?.error) {
        errorMessage = error.error.error;
      } else {
        errorMessage = `Error ${error.status}: ${error.message}`;
      }
    }

    console.error('VlmPipelineService error:', errorMessage);
    return throwError(() => new Error(errorMessage));
  }
}
