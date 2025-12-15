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
import { Observable, throwError } from 'rxjs';
import { catchError } from 'rxjs/operators';
import {
  AddUrlRequest,
  FileUploadResponse,
  SimpleMessageResponse,
  LoaderInfo,
  ChunkerInfo,
  BatchProcessRequest,
  BatchProcessResponse,
  DebugAnalysisResult,
  DebuggerStatus,
  TestUploadResponse,
  AsyncUploadResponse,
  IngestProgressUpdate,
  BatchAsyncUploadResponse,
  UploadedFileInfo,
  CancelTaskResponse,
  ProcessingModeInfo
} from '../models/api-models';
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

  getUploadedFiles(): Observable<{uploaded_files_location: string, files: UploadedFileInfo[]}> {
    return this.http.get<{uploaded_files_location: string, files: UploadedFileInfo[]}>(`${this.backendUrl}/documents/uploaded-files`)
      .pipe(catchError(this.handleError));
  }

  getAvailableLoaders(): Observable<LoaderInfo[]> {
    return this.http.get<LoaderInfo[]>(`${this.backendUrl}/documents/loaders`)
      .pipe(catchError(this.handleError));
  }

  getAvailableChunkers(): Observable<ChunkerInfo[]> {
    return this.http.get<ChunkerInfo[]>(`${this.backendUrl}/documents/chunkers`)
      .pipe(catchError(this.handleError));
  }

  uploadFile(file: File, loaderName?: string): Observable<FileUploadResponse> {
    const formData: FormData = new FormData();
    formData.append('file', file, file.name);
    if (loaderName) {
      formData.append('loader', loaderName);
    }
    return this.http.post<FileUploadResponse>(`${this.backendUrl}/documents/upload`, formData)
      .pipe(catchError(this.handleError));
  }

  addUrl(addUrlRequest: AddUrlRequest): Observable<FileUploadResponse> {
    return this.http.post<FileUploadResponse>(`${this.backendUrl}/documents/add-url`, addUrlRequest)
      .pipe(catchError(this.handleError));
  }

  processBatch(request: BatchProcessRequest): Observable<BatchProcessResponse> {
    return this.http.post<BatchProcessResponse>(`${this.backendUrl}/documents/process-batch`, request)
      .pipe(catchError(this.handleError));
  }

  // Document Debugger Endpoints
  getDebuggerStatus(): Observable<DebuggerStatus> {
    return this.http.get<DebuggerStatus>(`${this.backendUrl}/documents/debug/status`)
      .pipe(catchError(this.handleError));
  }

  analyzeFile(fileName: string, loaderName?: string, chunkerName?: string): Observable<DebugAnalysisResult> {
    let params = new HttpParams().set('fileName', fileName);
    if (loaderName) {
      params = params.set('loaderName', loaderName);
    }
    if (chunkerName) {
      params = params.set('chunkerName', chunkerName);
    }
    return this.http.post<DebugAnalysisResult>(`${this.backendUrl}/documents/debug/analyze-file`, null, { params })
      .pipe(catchError(this.handleError));
  }

  testUploadDebugFile(file: File): Observable<TestUploadResponse> {
    const formData: FormData = new FormData();
    formData.append('file', file, file.name);
    return this.http.post<TestUploadResponse>(`${this.backendUrl}/documents/debug/test-upload`, formData)
      .pipe(catchError(this.handleError));
  }

  /**
   * Upload a file asynchronously. Returns immediately with a task ID.
   * Subscribe to WebSocket topic for real-time progress updates.
   *
   * @param file The file to upload
   * @param loaderName Optional loader name
   * @param chunkerName Optional chunker name
   * @param processingMode Optional processing mode: 'auto', 'subprocess', or 'inprocess'
   */
  uploadFileAsync(file: File, loaderName?: string, chunkerName?: string, processingMode?: string): Observable<AsyncUploadResponse> {
    const formData: FormData = new FormData();
    formData.append('file', file, file.name);
    if (loaderName) {
      formData.append('loader', loaderName);
    }
    if (chunkerName) {
      formData.append('chunkerName', chunkerName);
    }
    if (processingMode) {
      formData.append('processingMode', processingMode);
    }
    return this.http.post<AsyncUploadResponse>(`${this.backendUrl}/documents/upload-async`, formData)
      .pipe(catchError(this.handleError));
  }

  /**
   * Upload multiple files asynchronously for batch processing.
   * All files are processed concurrently. Subscribe to WebSocket for progress updates.
   *
   * @param files The files to upload
   * @param loaderName Optional loader name
   * @param chunkerName Optional chunker name
   * @param processingMode Optional processing mode: 'auto', 'subprocess', or 'inprocess'
   */
  uploadFilesAsync(files: File[], loaderName?: string, chunkerName?: string, processingMode?: string): Observable<BatchAsyncUploadResponse> {
    const formData: FormData = new FormData();
    files.forEach(file => {
      formData.append('files', file, file.name);
    });
    if (loaderName) {
      formData.append('loader', loaderName);
    }
    if (chunkerName) {
      formData.append('chunkerName', chunkerName);
    }
    if (processingMode) {
      formData.append('processingMode', processingMode);
    }
    return this.http.post<BatchAsyncUploadResponse>(`${this.backendUrl}/documents/upload-batch-async`, formData)
      .pipe(catchError(this.handleError));
  }

  /**
   * Get available processing modes for document ingestion
   */
  getProcessingModes(): Observable<{modes: ProcessingModeInfo[], default: string}> {
    return this.http.get<{modes: ProcessingModeInfo[], default: string}>(`${this.backendUrl}/documents/processing-modes`)
      .pipe(catchError(this.handleError));
  }

  /**
   * Get the status of an async ingest task
   */
  getIngestStatus(taskId: string): Observable<IngestProgressUpdate> {
    return this.http.get<IngestProgressUpdate>(`${this.backendUrl}/documents/ingest-status/${taskId}`)
      .pipe(catchError(this.handleError));
  }

  /**
   * Get all active ingest tasks
   */
  getAllIngestTasks(): Observable<IngestProgressUpdate[]> {
    return this.http.get<IngestProgressUpdate[]>(`${this.backendUrl}/documents/ingest-tasks`)
      .pipe(catchError(this.handleError));
  }

  /**
   * Cancel an async ingest task.
   * The task will be stopped as soon as possible and marked as CANCELLED.
   */
  cancelIngestTask(taskId: string): Observable<CancelTaskResponse> {
    return this.http.post<CancelTaskResponse>(`${this.backendUrl}/documents/ingest-cancel/${taskId}`, {})
      .pipe(catchError(this.handleError));
  }

  private handleError(error: HttpErrorResponse) {
    let errorMessage = 'Unknown error!';
    if (error.error instanceof ErrorEvent) {
      errorMessage = `Error: ${error.error.message}`;
    } else {
      const serverError = error.error;
      if (serverError && (serverError.error || serverError.message)) {
        errorMessage = `Error Code: ${error.status}\nMessage: ${serverError.error || serverError.message}`;
      } else if (error.message) {
        errorMessage = `Error Code: ${error.status}\nMessage: ${error.message}`;
      } else {
        errorMessage = `Error Code: ${error.status}\nMessage: Server error`;
      }
    }
    console.error(errorMessage);
    return throwError(() => new Error(errorMessage));
  }

  /**
   * Get the warmup status of the system (embedding model, chunker).
   */
  getWarmupStatus(): Observable<any> {
    return this.http.get<any>(`${this.backendUrl}/system/warmup`)
      .pipe(catchError(this.handleError));
  }
}
