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
  AddPathRequest,
  AddYouTubeRequest,
  YouTubeTranscriptResponse,
  AddTextRequest,
  AddTextResponse,
  AddDiscordRequest,
  DiscordResponse,
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
  ProcessingModeInfo,
  SubprocessIngestConfig
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

  getUploadedFiles(): Observable<{ uploaded_files_location: string, files: UploadedFileInfo[] }> {
    return this.http.get<{ uploaded_files_location: string, files: UploadedFileInfo[] }>(`${this.backendUrl}/documents/uploaded-files`)
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

  addPath(addPathRequest: AddPathRequest): Observable<SimpleMessageResponse> {
    return this.http.post<SimpleMessageResponse>(`${this.backendUrl}/documents/add-path`, addPathRequest)
      .pipe(catchError(this.handleError));
  }

  /**
   * Add a YouTube video transcript as a document source.
   * Fetches the transcript from YouTube and processes it for indexing.
   *
   * @param request The request containing YouTube URL and optional parameters
   * @returns Observable with transcript details and processing status
   */
  addYouTubeTranscript(request: AddYouTubeRequest): Observable<YouTubeTranscriptResponse> {
    return this.http.post<YouTubeTranscriptResponse>(`${this.backendUrl}/documents/add-youtube`, request)
      .pipe(catchError(this.handleError));
  }

  /**
   * Add text content directly as a document source.
   * Takes raw text (e.g., pasted from clipboard) and processes it for indexing.
   *
   * @param request The request containing text content and optional parameters
   * @returns Observable with processing status
   */
  addTextContent(request: AddTextRequest): Observable<AddTextResponse> {
    return this.http.post<AddTextResponse>(`${this.backendUrl}/documents/add-text`, request)
      .pipe(catchError(this.handleError));
  }

  /**
   * Add Discord channel/server messages as a document source.
   * Fetches messages from the specified Discord server/channel and processes them for indexing.
   *
   * @param request The request containing Discord server ID, optional channel ID, and bot token
   * @returns Observable with message fetch details and processing status
   */
  addDiscordMessages(request: AddDiscordRequest): Observable<DiscordResponse> {
    return this.http.post<DiscordResponse>(`${this.backendUrl}/documents/add-discord`, request)
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
   * @param subprocessConfig Optional subprocess configuration (heap size, timeout, etc.)
   */
  uploadFileAsync(file: File, loaderName?: string, chunkerName?: string, processingMode?: string, subprocessConfig?: SubprocessIngestConfig): Observable<AsyncUploadResponse> {
    const formData: FormData = new FormData();
    formData.append('file', file, file.name);
    if (loaderName) {
      formData.append('loader', loaderName);
    }
    if (chunkerName) {
      formData.append('chunkerName', chunkerName);
    }
    // Always send processingMode - default to 'auto' if not specified
    formData.append('processingMode', processingMode || 'auto');
    console.log('[DocumentService] uploadFileAsync processingMode:', processingMode || 'auto');
    // Pass subprocess config as JSON if provided
    if (subprocessConfig) {
      formData.append('subprocessHeapSize', subprocessConfig.heapSize || '');
      formData.append('subprocessTimeoutMinutes', String(subprocessConfig.timeoutMinutes || 60));
      formData.append('subprocessHeartbeatSeconds', String(subprocessConfig.heartbeatIntervalSeconds || 10));
      formData.append('subprocessStaleThresholdSeconds', String(subprocessConfig.staleThresholdSeconds || 120));
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
   * @param subprocessConfig Optional subprocess configuration (heap size, timeout, etc.)
   */
  uploadFilesAsync(files: File[], loaderName?: string, chunkerName?: string, processingMode?: string, subprocessConfig?: SubprocessIngestConfig): Observable<BatchAsyncUploadResponse> {
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
    // Always send processingMode - default to 'auto' if not specified
    formData.append('processingMode', processingMode || 'auto');
    console.log('[DocumentService] uploadFilesAsync processingMode:', processingMode || 'auto');
    // Pass subprocess config if provided
    if (subprocessConfig) {
      formData.append('subprocessHeapSize', subprocessConfig.heapSize || '');
      formData.append('subprocessTimeoutMinutes', String(subprocessConfig.timeoutMinutes || 60));
      formData.append('subprocessHeartbeatSeconds', String(subprocessConfig.heartbeatIntervalSeconds || 10));
      formData.append('subprocessStaleThresholdSeconds', String(subprocessConfig.staleThresholdSeconds || 120));
    }
    return this.http.post<BatchAsyncUploadResponse>(`${this.backendUrl}/documents/upload-batch-async`, formData)
      .pipe(catchError(this.handleError));
  }

  /**
   * Get available processing modes for document ingestion
   */
  getProcessingModes(): Observable<{ modes: ProcessingModeInfo[], default: string }> {
    return this.http.get<{ modes: ProcessingModeInfo[], default: string }>(`${this.backendUrl}/documents/processing-modes`)
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

  // ═══════════════════════════════════════════════════════════════════════════════
  // FACT SHEET INTEGRATION METHODS
  // ═══════════════════════════════════════════════════════════════════════════════

  /**
   * Upload a file with advanced options from the add source dialog.
   */
  uploadFileWithOptions(file: File, options: {
    loaderName?: string;
    chunkerName?: string;
    chunkerOptions?: { [key: string]: any };
    rebuildIndex?: boolean;
    subprocessConfig?: any;
    processingMode?: string;
  }): Observable<FileUploadResponse> {
    const formData: FormData = new FormData();
    formData.append('file', file, file.name);
    if (options.loaderName) {
      formData.append('loader', options.loaderName);
    }
    if (options.chunkerName) {
      formData.append('chunkerName', options.chunkerName);
    }
    if (options.chunkerOptions) {
      formData.append('chunkerOptions', JSON.stringify(options.chunkerOptions));
    }
    if (options.rebuildIndex !== undefined) {
      formData.append('rebuildIndex', String(options.rebuildIndex));
    }
    if (options.processingMode) {
      formData.append('processingMode', options.processingMode);
    }
    return this.http.post<FileUploadResponse>(`${this.backendUrl}/documents/upload`, formData)
      .pipe(catchError(this.handleError));
  }

  /**
   * Add a URL source with options.
   */
  addUrlSource(url: string, options: {
    fileName?: string;
    loaderName?: string;
    chunkerName?: string;
    rebuildIndex?: boolean;
  }): Observable<FileUploadResponse> {
    const request: AddUrlRequest = {
      url: url,
      fileName: options.fileName,
      loaderName: options.loaderName,
      chunkerName: options.chunkerName,
      rebuildIndex: options.rebuildIndex
    };
    return this.addUrl(request);
  }

  /**
   * Add text content as a source.
   */
  addTextSource(textContent: string, options: {
    sourceName?: string;
    chunkerName?: string;
    rebuildIndex?: boolean;
  }): Observable<AddTextResponse> {
    const request: AddTextRequest = {
      content: textContent,
      sourceName: options.sourceName,
      chunkerName: options.chunkerName,
      rebuildIndex: options.rebuildIndex
    };
    return this.addTextContent(request);
  }

  /**
   * Add a YouTube video transcript as a source.
   */
  addYouTubeSource(youtubeUrl: string, options: {
    language?: string;
    saveTranscript?: boolean;
    chunkerName?: string;
    rebuildIndex?: boolean;
  }): Observable<YouTubeTranscriptResponse> {
    const request: AddYouTubeRequest = {
      url: youtubeUrl,
      language: options.language || 'en',
      saveTranscriptFile: options.saveTranscript,
      chunkerName: options.chunkerName,
      rebuildIndex: options.rebuildIndex
    };
    return this.addYouTubeTranscript(request);
  }

  /**
   * Add Confluence content as a source.
   */
  addConfluenceSource(options: {
    baseUrl: string;
    email: string;
    apiToken: string;
    spaceKey: string;
    includeChildren?: boolean;
    includeAttachments?: boolean;
    chunkerName?: string;
    rebuildIndex?: boolean;
  }): Observable<SimpleMessageResponse> {
    return this.http.post<SimpleMessageResponse>(`${this.backendUrl}/confluence/ingest`, {
      baseUrl: options.baseUrl,
      email: options.email,
      apiToken: options.apiToken,
      spaceKey: options.spaceKey,
      includeChildren: options.includeChildren ?? true,
      includeAttachments: options.includeAttachments ?? false,
      chunkerName: options.chunkerName,
      rebuildIndex: options.rebuildIndex
    }).pipe(catchError(this.handleError));
  }

  /**
   * Add Slack messages as a source.
   */
  addSlackSource(options: {
    channelId: string;
    token?: string;
    messageLimit?: number;
    includeThreads?: boolean;
    startDate?: string;
    endDate?: string;
    daysBack?: number;
    loadAllChannels?: boolean;
    historyMode?: boolean;
    chunkerName?: string;
    rebuildIndex?: boolean;
  }): Observable<SimpleMessageResponse> {
    const endpoint = options.historyMode ? '/slack/history/ingest' : '/slack/ingest';
    return this.http.post<SimpleMessageResponse>(`${this.backendUrl}${endpoint}`, {
      channelId: options.channelId,
      token: options.token,
      messageLimit: options.messageLimit ?? 100,
      includeThreads: options.includeThreads ?? true,
      startDate: options.startDate,
      endDate: options.endDate,
      daysBack: options.daysBack ?? 30,
      loadAllChannels: options.loadAllChannels ?? false,
      chunkerName: options.chunkerName,
      rebuildIndex: options.rebuildIndex
    }).pipe(catchError(this.handleError));
  }
}
