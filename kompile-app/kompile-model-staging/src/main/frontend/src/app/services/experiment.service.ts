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
import { Observable, throwError } from 'rxjs';
import { catchError } from 'rxjs/operators';
import {
  Experiment,
  ExperimentWithRuns,
  ExperimentRun,
  EvalDataset,
  DatasetRow,
  ExperimentComparison
} from '../models/api-models';

@Injectable({
  providedIn: 'root'
})
export class ExperimentService {

  private baseUrl: string;

  constructor(private http: HttpClient) {
    if (typeof window !== 'undefined' && window.location) {
      const protocol = window.location.protocol;
      const hostname = window.location.hostname;
      const port = window.location.port;
      this.baseUrl = `${protocol}//${hostname}${port ? ':' + port : ''}/api/experiments`;
    } else {
      this.baseUrl = '/api/experiments';
    }
  }

  // ==================== Experiments ====================

  createExperiment(request: { name: string; description?: string; suiteId: string; datasetId?: string; tags?: string[] }): Observable<Experiment> {
    return this.http.post<Experiment>(this.baseUrl, request)
      .pipe(catchError(this.handleError));
  }

  listExperiments(): Observable<Experiment[]> {
    return this.http.get<Experiment[]>(this.baseUrl)
      .pipe(catchError(this.handleError));
  }

  getExperiment(id: string): Observable<ExperimentWithRuns> {
    return this.http.get<ExperimentWithRuns>(`${this.baseUrl}/${encodeURIComponent(id)}`)
      .pipe(catchError(this.handleError));
  }

  deleteExperiment(id: string): Observable<void> {
    return this.http.delete<void>(`${this.baseUrl}/${encodeURIComponent(id)}`)
      .pipe(catchError(this.handleError));
  }

  compareRuns(experimentId: string): Observable<ExperimentComparison> {
    return this.http.get<ExperimentComparison>(`${this.baseUrl}/${encodeURIComponent(experimentId)}/compare`)
      .pipe(catchError(this.handleError));
  }

  // ==================== Runs ====================

  addRun(experimentId: string, request: { modelId: string; modelVariant?: string; modelType?: string }): Observable<ExperimentRun> {
    return this.http.post<ExperimentRun>(`${this.baseUrl}/${encodeURIComponent(experimentId)}/runs`, request)
      .pipe(catchError(this.handleError));
  }

  executeRun(experimentId: string, runId: string): Observable<ExperimentRun> {
    return this.http.post<ExperimentRun>(
      `${this.baseUrl}/${encodeURIComponent(experimentId)}/runs/${encodeURIComponent(runId)}/execute`, {})
      .pipe(catchError(this.handleError));
  }

  listRuns(experimentId: string): Observable<ExperimentRun[]> {
    return this.http.get<ExperimentRun[]>(`${this.baseUrl}/${encodeURIComponent(experimentId)}/runs`)
      .pipe(catchError(this.handleError));
  }

  getRun(experimentId: string, runId: string): Observable<ExperimentRun> {
    return this.http.get<ExperimentRun>(
      `${this.baseUrl}/${encodeURIComponent(experimentId)}/runs/${encodeURIComponent(runId)}`)
      .pipe(catchError(this.handleError));
  }

  // ==================== Model History ====================

  getModelHistory(modelId: string): Observable<ExperimentRun[]> {
    return this.http.get<ExperimentRun[]>(`${this.baseUrl}/models/${encodeURIComponent(modelId)}/runs`)
      .pipe(catchError(this.handleError));
  }

  // ==================== Datasets ====================

  uploadDataset(file: File, name: string, description?: string): Observable<EvalDataset> {
    const formData = new FormData();
    formData.append('file', file);
    formData.append('name', name);
    if (description) {
      formData.append('description', description);
    }
    return this.http.post<EvalDataset>(`${this.baseUrl}/eval-datasets`, formData)
      .pipe(catchError(this.handleError));
  }

  listDatasets(): Observable<EvalDataset[]> {
    return this.http.get<EvalDataset[]>(`${this.baseUrl}/eval-datasets`)
      .pipe(catchError(this.handleError));
  }

  getDataset(id: string): Observable<EvalDataset> {
    return this.http.get<EvalDataset>(`${this.baseUrl}/eval-datasets/${encodeURIComponent(id)}`)
      .pipe(catchError(this.handleError));
  }

  deleteDataset(id: string): Observable<void> {
    return this.http.delete<void>(`${this.baseUrl}/eval-datasets/${encodeURIComponent(id)}`)
      .pipe(catchError(this.handleError));
  }

  previewDataset(id: string, maxRows: number = 20): Observable<DatasetRow[]> {
    return this.http.get<DatasetRow[]>(`${this.baseUrl}/eval-datasets/${encodeURIComponent(id)}/preview?maxRows=${maxRows}`)
      .pipe(catchError(this.handleError));
  }

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

    console.error('ExperimentService error:', errorMessage);
    return throwError(() => new Error(errorMessage));
  }
}
