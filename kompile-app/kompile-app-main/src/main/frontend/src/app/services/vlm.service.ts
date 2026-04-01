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
  VlmModelSet,
  VlmPipelineStage,
  VlmExtractionType,
  VlmPreset,
  VlmDownloadStatus,
  VlmModelSetsStatus,
  VlmServiceStatus,
  VlmPresetModels,
  VlmEnsureModelsResponse,
  VlmBenchmarkResult
} from '../models/vlm-models';
import { environment } from '../../environments/environment';

@Injectable({
  providedIn: 'root'
})
export class VlmService {

  private baseUrl: string;

  // Observable for download events
  private downloadEventsSubject = new Subject<VlmDownloadStatus>();
  public downloadEvents$ = this.downloadEventsSubject.asObservable();

  // Cache status observable
  private modelSetsStatusSubject = new BehaviorSubject<VlmModelSetsStatus | null>(null);
  public modelSetsStatus$ = this.modelSetsStatusSubject.asObservable();

  constructor(private http: HttpClient) {
    // Dynamic API URL based on current location
    if (typeof window !== 'undefined' && window.location) {
      const protocol = window.location.protocol;
      const hostname = window.location.hostname;
      const port = window.location.port;
      this.baseUrl = `${protocol}//${hostname}${port ? ':' + port : ''}/api/vlm`;
    } else {
      this.baseUrl = '/api/vlm';
    }
  }

  // ==================== Model Set Operations ====================

  /**
   * Get all available VLM model sets.
   */
  getModelSets(): Observable<VlmModelSet[]> {
    return this.http.get<VlmModelSet[]>(`${this.baseUrl}/model-sets`)
      .pipe(catchError(this.handleError));
  }

  /**
   * Get a specific model set by ID.
   */
  getModelSet(setId: string): Observable<VlmModelSet> {
    return this.http.get<VlmModelSet>(`${this.baseUrl}/model-sets/${setId}`)
      .pipe(catchError(this.handleError));
  }

  /**
   * Get cache status for all model sets.
   */
  getModelSetsStatus(): Observable<VlmModelSetsStatus> {
    return this.http.get<VlmModelSetsStatus>(`${this.baseUrl}/model-sets/status`)
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
  getDownloadStatus(setId: string): Observable<VlmDownloadStatus> {
    return this.http.get<VlmDownloadStatus>(`${this.baseUrl}/model-sets/${setId}/download/status`)
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
   * Returns an observable that emits progress updates until complete.
   */
  downloadModelSetWithProgress(setId: string): Observable<VlmDownloadStatus> {
    return new Observable(observer => {
      // Start the download
      this.downloadModelSet(setId).subscribe({
        next: () => {
          // Poll for status every 500ms
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
                // Refresh model sets status
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
  getPipelineStages(): Observable<VlmPipelineStage[]> {
    return this.http.get<VlmPipelineStage[]>(`${this.baseUrl}/pipeline-stages`)
      .pipe(catchError(this.handleError));
  }

  // ==================== Extraction Type Operations ====================

  /**
   * Get all extraction types.
   */
  getExtractionTypes(): Observable<VlmExtractionType[]> {
    return this.http.get<VlmExtractionType[]>(`${this.baseUrl}/extraction-types`)
      .pipe(catchError(this.handleError));
  }

  // ==================== Preset Operations ====================

  /**
   * Get available configuration presets.
   */
  getPresets(): Observable<VlmPreset[]> {
    return this.http.get<VlmPreset[]>(`${this.baseUrl}/presets`)
      .pipe(catchError(this.handleError));
  }

  /**
   * Get required models for a preset.
   */
  getPresetModels(presetId: string): Observable<VlmPresetModels> {
    return this.http.get<VlmPresetModels>(`${this.baseUrl}/presets/${presetId}/models`)
      .pipe(catchError(this.handleError));
  }

  /**
   * Ensure all models for a preset are downloaded.
   */
  ensurePresetModels(presetId: string): Observable<VlmEnsureModelsResponse> {
    return this.http.post<VlmEnsureModelsResponse>(`${this.baseUrl}/presets/${presetId}/ensure`, {})
      .pipe(catchError(this.handleError));
  }

  // ==================== Benchmark ====================

  /**
   * Run VLM benchmark with a file upload.
   */
  runBenchmark(file: File, modelId?: string, iterations: number = 3): Observable<VlmBenchmarkResult> {
    const formData = new FormData();
    formData.append('file', file);
    if (modelId) {
      formData.append('modelId', modelId);
    }
    formData.append('iterations', iterations.toString());
    return this.http.post<VlmBenchmarkResult>(`${this.baseUrl}/benchmark`, formData)
      .pipe(catchError(this.handleError));
  }

  /**
   * Run a quick VLM benchmark using a blank test image.
   */
  runQuickBenchmark(modelId?: string): Observable<VlmBenchmarkResult> {
    const params: any = {};
    if (modelId) {
      params.modelId = modelId;
    }
    return this.http.get<VlmBenchmarkResult>(`${this.baseUrl}/benchmark/quick`, { params })
      .pipe(catchError(this.handleError));
  }

  // ==================== Service Status ====================

  /**
   * Get VLM service status and cache statistics.
   */
  getServiceStatus(): Observable<VlmServiceStatus> {
    return this.http.get<VlmServiceStatus>(`${this.baseUrl}/status`)
      .pipe(catchError(this.handleError));
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
      } else if (error.error?.error) {
        errorMessage = error.error.error;
      } else {
        errorMessage = `Error ${error.status}: ${error.message}`;
      }
    }

    console.error('VlmService error:', errorMessage);
    return throwError(() => new Error(errorMessage));
  }
}
