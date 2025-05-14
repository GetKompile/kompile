/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import { Injectable } from '@angular/core';
import { HttpClient, HttpErrorResponse, HttpHeaders } from '@angular/common/http';
import { Observable, throwError } from 'rxjs';
import { catchError } from 'rxjs/operators';
import { AddUrlRequest, FileUploadResponse, SimpleMessageResponse } from '../models/api-models';
import { BaseService } from './base.service';

@Injectable({
  providedIn: 'root'
})
export class DocumentService extends BaseService {

  constructor(private http: HttpClient) {
    super();
  }

  getConfiguredSources(): Observable<string[]> {
    return this.http.get<string[]>(`${this.backendUrl}/documents/sources`)
      .pipe(catchError(this.handleError));
  }

  getUploadedFiles(): Observable<{uploaded_files_location: string, files: string[]}> {
    return this.http.get<{uploaded_files_location: string, files: string[]}>(`${this.backendUrl}/documents/uploaded-files`)
      .pipe(catchError(this.handleError));
  }

  uploadFile(file: File): Observable<FileUploadResponse> {
    const formData: FormData = new FormData();
    formData.append('file', file, file.name);

    // Note: HttpClient sets Content-Type automatically for FormData
    return this.http.post<FileUploadResponse>(`${this.backendUrl}/documents/upload`, formData)
      .pipe(catchError(this.handleError));
  }

  addUrl(addUrlRequest: AddUrlRequest): Observable<FileUploadResponse> {
    return this.http.post<FileUploadResponse>(`${this.backendUrl}/documents/add-url`, addUrlRequest)
      .pipe(catchError(this.handleError));
  }

  private handleError(error: HttpErrorResponse) {
    // ... (same as in RagService or a shared error handler)
    let errorMessage = 'Unknown error!';
    if (error.error instanceof ErrorEvent) {
      errorMessage = `Error: ${error.error.message}`;
    } else {
      errorMessage = `Error Code: ${error.status}\nMessage: ${error.message}`;
      if (error.error && error.error.error) {
        errorMessage += `\nDetails: ${error.error.error}`;
      } else if (error.error && typeof error.error === 'string') {
        errorMessage += `\nDetails: ${error.error}`;
      }
    }
    console.error(errorMessage);
    return throwError(() => new Error(errorMessage));
  }
}
