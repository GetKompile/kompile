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
import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Observable, throwError, timer, Subject, BehaviorSubject } from 'rxjs';
import { catchError, switchMap, takeWhile, tap } from 'rxjs/operators';
import {
  SameDiffLLMModelSet,
  SameDiffLLMPipelineStage,
  SameDiffLLMPreset,
  SameDiffLLMDownloadStatus,
  SameDiffLLMModelSetsStatus,
  SameDiffLLMServiceStatus,
  SameDiffLLMPipelineConfig,
  SameDiffLLMExecutionRequest,
  SameDiffLLMExecutionResult,
  SameDiffLLMStepSchema
} from '../models/samediff-llm-models';
import { environment } from '../../environments/environment';

@Injectable({
  providedIn: 'root'
})
export class SameDiffLLMService {

  private baseUrl: string;

  // Observable for download events
  private downloadEventsSubject = new Subject<SameDiffLLMDownloadStatus>();
  public downloadEvents$ = this.downloadEventsSubject.asObservable();

  // Cache status observable
  private modelSetsStatusSubject = new BehaviorSubject<SameDiffLLMModelSetsStatus | null>(null);
  public modelSetsStatus$ = this.modelSetsStatusSubject.asObservable();

  constructor(private http: HttpClient) {
    // Dynamic API URL based on current location
    if (typeof window !== 'undefined' && window.location) {
      const protocol = window.location.protocol;
      const hostname = window.location.hostname;
      const port = window.location.port;
      this.baseUrl = `${protocol}//${hostname}${port ? ':' + port : ''}/api/samediff-llm`;
    } else {
      this.baseUrl = '/api/samediff-llm';
    }
  }

  // ==================== Model Set Operations ====================

  /**
   * Get all available SameDiff LLM model sets.
   */
  getModelSets(): Observable<SameDiffLLMModelSet[]> {
    return this.http.get<SameDiffLLMModelSet[]>(`${this.baseUrl}/model-sets`)
      .pipe(catchError(this.handleError));
  }

  /**
   * Get a specific model set by ID.
   */
  getModelSet(setId: string): Observable<SameDiffLLMModelSet> {
    return this.http.get<SameDiffLLMModelSet>(`${this.baseUrl}/model-sets/${setId}`)
      .pipe(catchError(this.handleError));
  }

  /**
   * Get cache status for all model sets.
   */
  getModelSetsStatus(): Observable<SameDiffLLMModelSetsStatus> {
    return this.http.get<SameDiffLLMModelSetsStatus>(`${this.baseUrl}/model-sets/status`)
      .pipe(
        tap(status => this.modelSetsStatusSubject.next(status)),
        catchError(this.handleError)
      );
  }

  /**
   * Download a model set.
   */
  downloadModelSet(setId: string): Observable<any> {
    return this.http.post<any>(`${this.baseUrl}/model-sets/${setId}/download`, {})
      .pipe(catchError(this.handleError));
  }

  /**
   * Get download status for a model set.
   */
  getDownloadStatus(setId: string): Observable<SameDiffLLMDownloadStatus> {
    return this.http.get<SameDiffLLMDownloadStatus>(`${this.baseUrl}/model-sets/${setId}/download/status`)
      .pipe(catchError(this.handleError));
  }

  /**
   * Delete a cached model set.
   */
  deleteModelSet(setId: string): Observable<any> {
    return this.http.delete<any>(`${this.baseUrl}/model-sets/${setId}`)
      .pipe(catchError(this.handleError));
  }

  /**
   * Download a model set with progress polling.
   */
  downloadModelSetWithProgress(setId: string): Observable<SameDiffLLMDownloadStatus> {
    return new Observable(observer => {
      this.downloadModelSet(setId).subscribe({
        next: () => {
          const pollSubscription = timer(0, 500).pipe(
            switchMap(() => this.getDownloadStatus(setId)),
            tap(status => {
              this.downloadEventsSubject.next(status);
              observer.next(status);
            }),
            takeWhile(status => !status.complete, true)
          ).subscribe({
            next: (status) => {
              if (status.complete) {
                observer.complete();
                this.getModelSetsStatus().subscribe();
              }
            },
            error: (err) => observer.error(err)
          });
        },
        error: (err) => observer.error(err)
      });
    });
  }

  // ==================== Pipeline Stage Operations ====================

  /**
   * Get all pipeline stages with documentation.
   */
  getPipelineStages(): Observable<SameDiffLLMPipelineStage[]> {
    return this.http.get<SameDiffLLMPipelineStage[]>(`${this.baseUrl}/pipeline-stages`)
      .pipe(catchError(this.handleError));
  }

  // ==================== Preset Operations ====================

  /**
   * Get available configuration presets.
   */
  getPresets(): Observable<SameDiffLLMPreset[]> {
    return this.http.get<SameDiffLLMPreset[]>(`${this.baseUrl}/presets`)
      .pipe(catchError(this.handleError));
  }

  /**
   * Ensure all models for a preset are downloaded.
   */
  ensurePresetModels(presetId: string): Observable<any> {
    return this.http.post<any>(`${this.baseUrl}/presets/${presetId}/ensure`, {})
      .pipe(catchError(this.handleError));
  }

  // ==================== Pipeline Management ====================

  /**
   * Create a new pipeline configuration.
   */
  createPipeline(config: SameDiffLLMPipelineConfig): Observable<any> {
    return this.http.post<any>(`${this.baseUrl}/pipelines`, config)
      .pipe(catchError(this.handleError));
  }

  /**
   * List all configured pipelines.
   */
  listPipelines(): Observable<any[]> {
    return this.http.get<any[]>(`${this.baseUrl}/pipelines`)
      .pipe(catchError(this.handleError));
  }

  /**
   * Get a specific pipeline configuration.
   */
  getPipeline(pipelineId: string): Observable<any> {
    return this.http.get<any>(`${this.baseUrl}/pipelines/${pipelineId}`)
      .pipe(catchError(this.handleError));
  }

  /**
   * Delete a pipeline configuration.
   */
  deletePipeline(pipelineId: string): Observable<any> {
    return this.http.delete<any>(`${this.baseUrl}/pipelines/${pipelineId}`)
      .pipe(catchError(this.handleError));
  }

  // ==================== Pipeline Execution ====================

  /**
   * Execute a pipeline synchronously.
   */
  executePipeline(request: SameDiffLLMExecutionRequest): Observable<SameDiffLLMExecutionResult> {
    return this.http.post<SameDiffLLMExecutionResult>(`${this.baseUrl}/pipelines/execute`, request)
      .pipe(catchError(this.handleError));
  }

  /**
   * Execute a pipeline with streaming response.
   */
  executePipelineStreaming(request: SameDiffLLMExecutionRequest): Observable<string> {
    return this.http.post(`${this.baseUrl}/pipelines/execute/stream`, request, {
      responseType: 'text',
      observe: 'body'
    }).pipe(catchError(this.handleError));
  }

  // ==================== Step Schema ====================

  /**
   * Get available pipeline steps.
   */
  getAvailableSteps(): Observable<SameDiffLLMStepSchema[]> {
    return this.http.get<SameDiffLLMStepSchema[]>(`${this.baseUrl}/steps`)
      .pipe(catchError(this.handleError));
  }

  // ==================== Service Status ====================

  /**
   * Get service status and cache statistics.
   */
  getServiceStatus(): Observable<SameDiffLLMServiceStatus> {
    return this.http.get<SameDiffLLMServiceStatus>(`${this.baseUrl}/status`)
      .pipe(catchError(this.handleError));
  }

  // ==================== Benchmark ====================

  /**
   * Run a quick benchmark.
   */
  runQuickBenchmark(modelId?: string): Observable<any> {
    const params: any = {};
    if (modelId) {
      params.modelId = modelId;
    }
    return this.http.get<any>(`${this.baseUrl}/benchmark/quick`, { params })
      .pipe(catchError(this.handleError));
  }

  // ==================== Error Handling ====================

  private handleError(error: HttpErrorResponse): Observable<never> {
    let errorMessage = 'An error occurred';

    if (error.error instanceof ErrorEvent) {
      errorMessage = `Error: ${error.error.message}`;
    } else {
      if (error.error?.message) {
        errorMessage = error.error.message;
      } else if (error.error?.error) {
        errorMessage = error.error.error;
      } else {
        errorMessage = `Error ${error.status}: ${error.message}`;
      }
    }

    console.error('SameDiffLLMService error:', errorMessage);
    return throwError(() => new Error(errorMessage));
  }
}
