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
import { EvaluationRequest, EvaluationResult, TrainingJobStatus } from '../models/api-models';

@Injectable({
  providedIn: 'root'
})
export class EvaluationService {

  private baseUrl: string;

  constructor(private http: HttpClient) {
    if (typeof window !== 'undefined' && window.location) {
      const protocol = window.location.protocol;
      const hostname = window.location.hostname;
      const port = window.location.port;
      this.baseUrl = `${protocol}//${hostname}${port ? ':' + port : ''}/api/evaluation`;
    } else {
      this.baseUrl = '/api/evaluation';
    }
  }

  runEvaluation(request: EvaluationRequest): Observable<EvaluationResult> {
    return this.http.post<EvaluationResult>(`${this.baseUrl}/run`, request)
      .pipe(catchError(this.handleError));
  }

  startAsyncEvaluation(request: EvaluationRequest): Observable<TrainingJobStatus> {
    return this.http.post<TrainingJobStatus>(`${this.baseUrl}/start`, request)
      .pipe(catchError(this.handleError));
  }

  getResult(evaluationId: string): Observable<EvaluationResult> {
    return this.http.get<EvaluationResult>(`${this.baseUrl}/jobs/${encodeURIComponent(evaluationId)}`)
      .pipe(catchError(this.handleError));
  }

  getAvailableMetrics(): Observable<any[]> {
    return this.http.get<any[]>(`${this.baseUrl}/metrics`)
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

    console.error('EvaluationService error:', errorMessage);
    return throwError(() => new Error(errorMessage));
  }
}
