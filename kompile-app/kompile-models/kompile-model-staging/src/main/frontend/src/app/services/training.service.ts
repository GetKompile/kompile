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
import { TrainingConfigRequest, TrainingJobStatus, TrainingLogEntry, TrainingMetricsSnapshot } from '../models/api-models';

@Injectable({
  providedIn: 'root'
})
export class TrainingService {

  private baseUrl: string;

  constructor(private http: HttpClient) {
    if (typeof window !== 'undefined' && window.location) {
      const protocol = window.location.protocol;
      const hostname = window.location.hostname;
      const port = window.location.port;
      this.baseUrl = `${protocol}//${hostname}${port ? ':' + port : ''}/api/training`;
    } else {
      this.baseUrl = '/api/training';
    }
  }

  startTraining(request: TrainingConfigRequest): Observable<TrainingJobStatus> {
    return this.http.post<TrainingJobStatus>(`${this.baseUrl}/start`, request)
      .pipe(catchError(this.handleError));
  }

  getJobs(): Observable<TrainingJobStatus[]> {
    return this.http.get<TrainingJobStatus[]>(`${this.baseUrl}/jobs`)
      .pipe(catchError(this.handleError));
  }

  getJob(jobId: string): Observable<TrainingJobStatus> {
    return this.http.get<TrainingJobStatus>(`${this.baseUrl}/jobs/${encodeURIComponent(jobId)}`)
      .pipe(catchError(this.handleError));
  }

  cancelJob(jobId: string): Observable<any> {
    return this.http.post(`${this.baseUrl}/jobs/${encodeURIComponent(jobId)}/cancel`, {})
      .pipe(catchError(this.handleError));
  }

  connectToJobStream(jobId: string): EventSource {
    return new EventSource(`${this.baseUrl}/jobs/${encodeURIComponent(jobId)}/stream`);
  }

  getJobLogs(jobId: string): Observable<TrainingLogEntry[]> {
    return this.http.get<TrainingLogEntry[]>(`${this.baseUrl}/jobs/${encodeURIComponent(jobId)}/logs`)
      .pipe(catchError(this.handleError));
  }

  getMetricsHistory(jobId: string): Observable<TrainingMetricsSnapshot[]> {
    return this.http.get<TrainingMetricsSnapshot[]>(`${this.baseUrl}/jobs/${encodeURIComponent(jobId)}/metrics`)
      .pipe(catchError(this.handleError));
  }

  getPeftTypes(): Observable<any[]> {
    return this.http.get<any[]>(`${this.baseUrl}/peft-types`)
      .pipe(catchError(this.handleError));
  }

  getUpdaterTypes(): Observable<any[]> {
    return this.http.get<any[]>(`${this.baseUrl}/updater-types`)
      .pipe(catchError(this.handleError));
  }

  getLrSchedules(): Observable<any[]> {
    return this.http.get<any[]>(`${this.baseUrl}/lr-schedules`)
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

    console.error('TrainingService error:', errorMessage);
    return throwError(() => new Error(errorMessage));
  }
}
