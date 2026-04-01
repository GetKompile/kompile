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
import { DatasetInfo, DatasetStats, DatasetUploadConfig } from '../models/api-models';

@Injectable({
  providedIn: 'root'
})
export class DatasetService {

  private baseUrl: string;

  constructor(private http: HttpClient) {
    if (typeof window !== 'undefined' && window.location) {
      const protocol = window.location.protocol;
      const hostname = window.location.hostname;
      const port = window.location.port;
      this.baseUrl = `${protocol}//${hostname}${port ? ':' + port : ''}/api/datasets`;
    } else {
      this.baseUrl = '/api/datasets';
    }
  }

  uploadDataset(config: DatasetUploadConfig, file: File): Observable<DatasetInfo> {
    const formData = new FormData();
    formData.append('file', file);
    Object.keys(config).forEach(key => {
      const value = (config as any)[key];
      if (value !== undefined && value !== null) {
        formData.append(key, String(value));
      }
    });
    return this.http.post<DatasetInfo>(`${this.baseUrl}/upload`, formData)
      .pipe(catchError(this.handleError));
  }

  listDatasets(): Observable<DatasetInfo[]> {
    return this.http.get<DatasetInfo[]>(`${this.baseUrl}`)
      .pipe(catchError(this.handleError));
  }

  getDataset(id: string): Observable<DatasetInfo> {
    return this.http.get<DatasetInfo>(`${this.baseUrl}/${encodeURIComponent(id)}`)
      .pipe(catchError(this.handleError));
  }

  deleteDataset(id: string): Observable<any> {
    return this.http.delete(`${this.baseUrl}/${encodeURIComponent(id)}`)
      .pipe(catchError(this.handleError));
  }

  previewDataset(id: string, rows: number = 10): Observable<any[]> {
    return this.http.get<any[]>(`${this.baseUrl}/${encodeURIComponent(id)}/preview?rows=${rows}`)
      .pipe(catchError(this.handleError));
  }

  getStats(id: string): Observable<DatasetStats> {
    return this.http.get<DatasetStats>(`${this.baseUrl}/${encodeURIComponent(id)}/stats`)
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

    console.error('DatasetService error:', errorMessage);
    return throwError(() => new Error(errorMessage));
  }
}
