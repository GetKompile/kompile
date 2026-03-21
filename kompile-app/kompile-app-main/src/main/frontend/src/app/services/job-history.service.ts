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
import { HttpClient, HttpParams } from '@angular/common/http';
import { Observable, BehaviorSubject } from 'rxjs';
import { tap } from 'rxjs/operators';
import { backendUrl } from './base.service';

export type JobStatus = 'QUEUED' | 'RUNNING' | 'COMPLETED' | 'FAILED' | 'CANCELLED' | 'MEMORY_KILLED' | 'PAUSED';

export type FailureReason = 'NONE' | 'OUT_OF_MEMORY' | 'MEMORY_KILLED' | 'USER_CANCELLED' |
  'LOAD_ERROR' | 'CONVERSION_ERROR' | 'CHUNKING_ERROR' | 'EMBEDDING_ERROR' |
  'INDEXING_ERROR' | 'SUBPROCESS_ERROR' | 'IO_ERROR' | 'INVALID_INPUT' | 'TIMEOUT' |
  'MODEL_NOT_FOUND' | 'STAGING_ERROR' | 'UNKNOWN';

export type IngestPhase = 'QUEUED' | 'LOADING' | 'CONVERTING' | 'CHUNKING' |
  'EMBEDDING' | 'INDEXING' | 'COMPLETED' | 'FAILED';

export interface IndexingJobHistory {
  id: number;
  taskId: string;
  fileName: string;
  fileSizeBytes?: number;
  contentType?: string;
  status: JobStatus;
  lastPhase?: IngestPhase;
  failedPhase?: IngestPhase;
  progressPercent?: number;
  startTime: string;
  endTime?: string;
  totalDurationMs?: number;
  loadingDurationMs?: number;
  conversionDurationMs?: number;
  chunkingDurationMs?: number;
  embeddingDurationMs?: number;
  indexingDurationMs?: number;
  loaderUsed?: string;
  chunkerUsed?: string;
  embeddingModelUsed?: string;
  indexerUsed?: string;
  chunkSize?: number;
  chunkOverlap?: number;
  embeddingBatchSize?: number;
  workerThreads?: number;
  parallelProcessingEnabled?: boolean;
  adaptiveBatchingEnabled?: boolean;
  documentsLoaded?: number;
  chunksCreated?: number;
  chunksEmbedded?: number;
  documentsIndexed?: number;
  totalTokensProcessed?: number;
  embeddingDimension?: number;
  javaVersion?: string;
  osInfo?: string;
  availableProcessors?: number;
  maxHeapMemoryBytes?: number;
  freeHeapMemoryAtStart?: number;
  memoryUsagePercentAtStart?: number;
  memoryUsagePercentAtEnd?: number;
  peakMemoryUsagePercent?: number;
  nd4jBackend?: string;
  nd4jEnvironmentJson?: string;
  errorMessage?: string;
  errorType?: string;
  stackTrace?: string;
  failureReason?: FailureReason;
  additionalDetails?: string;
  initiatedBy?: string;
  indexPath?: string;
  appVersion?: string;
}

export interface PagedResponse<T> {
  content: T[];
  totalElements: number;
  totalPages: number;
  size: number;
  number: number;
  first: boolean;
  last: boolean;
  empty: boolean;
}

export interface JobStatistics {
  totalJobs: number;
  completedJobs: number;
  failedJobs: number;
  cancelledJobs: number;
  memoryKilledJobs: number;
  activeJobs: number;
  statusBreakdown: Array<{
    status: JobStatus;
    count: number;
    avgDurationMs: number;
    totalDocumentsIndexed: number;
  }>;
  totalDocumentsIndexed?: number;
  totalChunksCreated?: number;
  totalProcessingTimeMs?: number;
  completedJobsInPeriod?: number;
  averagePhaseTimings?: {
    loadingMs?: number;
    conversionMs?: number;
    chunkingMs?: number;
    embeddingMs?: number;
    indexingMs?: number;
  };
  failureBreakdown: Array<{
    reason: FailureReason;
    count: number;
    avgDurationMs: number;
  }>;
  embeddingModels: string[];
  loaders: string[];
  chunkers: string[];
}

export interface JobSummary {
  taskId: string;
  fileName: string;
  status: JobStatus;
  progressPercent?: number;
  startTime: string;
  endTime?: string;
  totalDurationMs?: number;
  lastPhase?: IngestPhase;
  failedPhase?: IngestPhase;
  failureReason?: FailureReason;
  errorMessage?: string;
  parameters: {
    loaderUsed?: string;
    chunkerUsed?: string;
    embeddingModelUsed?: string;
    chunkSize?: number;
    chunkOverlap?: number;
    embeddingBatchSize?: number;
    workerThreads?: number;
  };
  results: {
    documentsLoaded?: number;
    chunksCreated?: number;
    chunksEmbedded?: number;
    documentsIndexed?: number;
  };
  phaseTimings?: {
    loadingMs?: number;
    conversionMs?: number;
    chunkingMs?: number;
    embeddingMs?: number;
    indexingMs?: number;
  };
  environment: {
    javaVersion?: string;
    osInfo?: string;
    availableProcessors?: number;
    maxHeapMB?: number;
    nd4jBackend?: string;
    nd4jEnvironmentJson?: string;
  };
  memoryPercent: {
    usageAtStart?: number;
    usageAtEnd?: number;
    peakUsage?: number;
  };
}

export interface IngestEvent {
  id?: number;
  taskId: string;
  timestamp: string;
  eventType: 'QUEUED' | 'STARTED' | 'PHASE_CHANGE' | 'PROGRESS' | 'COMPLETED' | 'FAILED' | 'CANCELLED'
    | 'RESTART_SCHEDULED' | 'RESTART_ATTEMPTED' | 'RESTART_SUCCEEDED' | 'RESTART_FAILED'
    | 'MEMORY_ANALYSIS' | 'HEAP_ADJUSTED' | 'THREADS_REDUCED' | 'MANUAL_RESTART';
  phase?: IngestPhase;
  message: string;
  details?: string;
  fileName?: string;
  durationMs?: number;
  nd4jEnvironmentSnapshot?: string;
  // Restart-related fields
  restartAttempt?: number;
  maxRestartAttempts?: number;
  heapSize?: string;
  batchSize?: number;
}

// ===================== SUBPROCESS EVENT TYPES =====================

export type SubprocessEventType =
  | 'SUBPROCESS_STARTED'
  | 'SUBPROCESS_STOPPED'
  | 'SUBPROCESS_CRASHED'
  | 'SUBPROCESS_RESTARTING'
  | 'SUBPROCESS_RESTART_SUCCESS'
  | 'SUBPROCESS_RESTART_EXHAUSTED'
  | 'MODEL_LOADED'
  | 'MODEL_FAILED';

export interface SubprocessEvent {
  id: number;
  eventType: SubprocessEventType;
  modelId: string;
  timestamp: string;
  taskId?: string;
  restartAttemptNumber?: number;
  maxRestartAttempts?: number;
  restartSuccessful?: boolean;
  failureReason?: string;
  errorMessage?: string;
  exitCode?: number;
  backoffMs?: number;
  heapBytes?: number;
  batchSize?: number;
  threadCount?: number;
  embeddingDimensions?: number;
  encoderType?: string;
}

export interface SubprocessStatistics {
  available: boolean;
  totalCrashes?: number;
  totalRestartAttempts?: number;
  successfulRestarts?: number;
  exhaustedRestarts?: number;
  modelsLoaded?: number;
  modelsFailed?: number;
  restartSuccessRate?: number;
}

@Injectable({
  providedIn: 'root'
})
export class JobHistoryService {
  private readonly apiUrl = `${backendUrl}/indexing/history`;
  private readonly subprocessApiUrl = `${backendUrl}/subprocess-events`;

  private jobsSubject = new BehaviorSubject<IndexingJobHistory[]>([]);
  public jobs$ = this.jobsSubject.asObservable();

  private statisticsSubject = new BehaviorSubject<JobStatistics | null>(null);
  public statistics$ = this.statisticsSubject.asObservable();

  constructor(private http: HttpClient) { }

  // ===================== QUERY METHODS =====================

  getAllJobs(page: number = 0, size: number = 20): Observable<PagedResponse<IndexingJobHistory>> {
    const params = new HttpParams()
      .set('page', page.toString())
      .set('size', size.toString());
    return this.http.get<PagedResponse<IndexingJobHistory>>(this.apiUrl, { params });
  }

  getJob(taskId: string): Observable<IndexingJobHistory> {
    return this.http.get<IndexingJobHistory>(`${this.apiUrl}/${taskId}`);
  }

  getJobsByStatus(status: JobStatus, hours?: number): Observable<IndexingJobHistory[]> {
    let params = new HttpParams();
    if (hours !== undefined) {
      params = params.set('hours', hours.toString());
    }
    return this.http.get<IndexingJobHistory[]>(`${this.apiUrl}/status/${status}`, { params });
  }

  getJobsByStatusPaged(status: JobStatus, page: number = 0, size: number = 20): Observable<PagedResponse<IndexingJobHistory>> {
    const params = new HttpParams()
      .set('page', page.toString())
      .set('size', size.toString());
    return this.http.get<PagedResponse<IndexingJobHistory>>(`${this.apiUrl}/status/${status}/page`, { params });
  }

  getRecentJobs(hours: number = 24): Observable<IndexingJobHistory[]> {
    const params = new HttpParams().set('hours', hours.toString());
    return this.http.get<IndexingJobHistory[]>(`${this.apiUrl}/recent`, { params }).pipe(
      tap(jobs => this.jobsSubject.next(jobs))
    );
  }

  getRecentJobsPaged(hours: number = 24, page: number = 0, size: number = 20): Observable<PagedResponse<IndexingJobHistory>> {
    const params = new HttpParams()
      .set('hours', hours.toString())
      .set('page', page.toString())
      .set('size', size.toString());
    return this.http.get<PagedResponse<IndexingJobHistory>>(`${this.apiUrl}/recent/page`, { params });
  }

  getJobsInRange(start: string, end: string): Observable<IndexingJobHistory[]> {
    const params = new HttpParams()
      .set('start', start)
      .set('end', end);
    return this.http.get<IndexingJobHistory[]>(`${this.apiUrl}/range`, { params });
  }

  getFailedJobs(): Observable<IndexingJobHistory[]> {
    return this.http.get<IndexingJobHistory[]>(`${this.apiUrl}/failed`);
  }

  getJobsByFailureReason(reason: FailureReason): Observable<IndexingJobHistory[]> {
    return this.http.get<IndexingJobHistory[]>(`${this.apiUrl}/failed/${reason}`);
  }

  getActiveJobs(): Observable<IndexingJobHistory[]> {
    return this.http.get<IndexingJobHistory[]>(`${this.apiUrl}/active`);
  }

  getLatestJobs(limit: number = 10): Observable<IndexingJobHistory[]> {
    const params = new HttpParams().set('limit', limit.toString());
    return this.http.get<IndexingJobHistory[]>(`${this.apiUrl}/latest`, { params });
  }

  searchByFileName(fileName: string): Observable<IndexingJobHistory[]> {
    const params = new HttpParams().set('fileName', fileName);
    return this.http.get<IndexingJobHistory[]>(`${this.apiUrl}/search`, { params });
  }

  getHighMemoryJobs(threshold: number = 80): Observable<IndexingJobHistory[]> {
    const params = new HttpParams().set('threshold', threshold.toString());
    return this.http.get<IndexingJobHistory[]>(`${this.apiUrl}/high-memory`, { params });
  }

  getLongRunningJobs(thresholdMs: number = 60000): Observable<IndexingJobHistory[]> {
    const params = new HttpParams().set('thresholdMs', thresholdMs.toString());
    return this.http.get<IndexingJobHistory[]>(`${this.apiUrl}/long-running`, { params });
  }

  // ===================== STATISTICS =====================

  getStatistics(lastHours: number = 24): Observable<JobStatistics> {
    const params = new HttpParams().set('lastHours', lastHours.toString());
    return this.http.get<JobStatistics>(`${this.apiUrl}/statistics`, { params }).pipe(
      tap(stats => this.statisticsSubject.next(stats))
    );
  }

  getFailureRate(lastHours: number = 24): Observable<{ failureRatePercent: number; periodHours: number }> {
    const params = new HttpParams().set('lastHours', lastHours.toString());
    return this.http.get<{ failureRatePercent: number; periodHours: number }>(`${this.apiUrl}/statistics/failure-rate`, { params });
  }

  // ===================== JOB SUMMARY =====================

  getTaskEvents(taskId: string): Observable<{ taskId: string; eventCount: number; events: IngestEvent[] }> {
    return this.http.get<{ taskId: string; eventCount: number; events: IngestEvent[] }>(`${backendUrl}/ingest/events/task/${taskId}`);
  }

  getJobSummary(taskId: string): Observable<JobSummary> {
    return this.http.get<JobSummary>(`${this.apiUrl}/${taskId}/summary`);
  }

  // ===================== MANAGEMENT =====================

  deleteJob(taskId: string): Observable<{ deleted: boolean; taskId: string }> {
    return this.http.delete<{ deleted: boolean; taskId: string }>(`${this.apiUrl}/${taskId}`);
  }

  forceCleanup(olderThanDays: number = 30): Observable<{ deleted: number; olderThanDays: number }> {
    const params = new HttpParams().set('olderThanDays', olderThanDays.toString());
    return this.http.post<{ deleted: number; olderThanDays: number }>(`${this.apiUrl}/cleanup`, null, { params });
  }

  // ===================== UTILITY METHODS =====================

  formatDuration(ms: number | undefined): string {
    if (ms === undefined || ms === null) return '-';
    if (ms < 1000) return `${ms}ms`;
    if (ms < 60000) return `${(ms / 1000).toFixed(1)}s`;
    if (ms < 3600000) return `${Math.floor(ms / 60000)}m ${Math.floor((ms % 60000) / 1000)}s`;
    return `${Math.floor(ms / 3600000)}h ${Math.floor((ms % 3600000) / 60000)}m`;
  }

  formatBytes(bytes: number | undefined): string {
    if (bytes === undefined || bytes === null) return '-';
    if (bytes < 1024) return `${bytes} B`;
    if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
    if (bytes < 1024 * 1024 * 1024) return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
    return `${(bytes / (1024 * 1024 * 1024)).toFixed(1)} GB`;
  }

  /**
   * Format a timestamp for display.
   * Handles both ISO-8601 strings and epoch milliseconds/seconds.
   */
  formatTimestamp(timestamp: string | number | null | undefined): string {
    if (!timestamp && timestamp !== 0) return '-';
    try {
      let date: Date;
      if (typeof timestamp === 'number') {
        // Handle epoch timestamps (detect if seconds or milliseconds)
        // Epoch seconds are typically 10 digits, milliseconds are 13+
        if (timestamp < 10000000000) {
          // Likely epoch seconds - convert to milliseconds
          date = new Date(timestamp * 1000);
        } else {
          // Already epoch milliseconds
          date = new Date(timestamp);
        }
      } else {
        // String - either ISO-8601 or numeric string
        const numericValue = Number(timestamp);
        if (!isNaN(numericValue) && String(numericValue) === timestamp) {
          // Numeric string - recurse with number
          return this.formatTimestamp(numericValue);
        }
        // ISO-8601 string
        date = new Date(timestamp);
      }

      // Validate the date is reasonable (not NaN)
      if (isNaN(date.getTime())) {
        return '-';
      }

      return date.toLocaleString();
    } catch {
      return '-';
    }
  }

  getStatusClass(status: JobStatus): string {
    switch (status) {
      case 'COMPLETED': return 'status-success';
      case 'RUNNING': case 'QUEUED': return 'status-info';
      case 'FAILED': case 'MEMORY_KILLED': return 'status-error';
      case 'CANCELLED': return 'status-warning';
      default: return '';
    }
  }

  getStatusIcon(status: JobStatus): string {
    switch (status) {
      case 'COMPLETED': return 'check_circle';
      case 'RUNNING': return 'sync';
      case 'QUEUED': return 'hourglass_empty';
      case 'FAILED': return 'error';
      case 'MEMORY_KILLED': return 'memory';
      case 'CANCELLED': return 'cancel';
      default: return 'help';
    }
  }

  getPhaseIcon(phase: IngestPhase | undefined): string {
    if (!phase) return 'help';
    switch (phase) {
      case 'QUEUED': return 'hourglass_empty';
      case 'LOADING': return 'upload_file';
      case 'CONVERTING': return 'transform';
      case 'CHUNKING': return 'content_cut';
      case 'EMBEDDING': return 'psychology';
      case 'INDEXING': return 'storage';
      case 'COMPLETED': return 'check_circle';
      case 'FAILED': return 'error';
      default: return 'help';
    }
  }

  // ===================== SUBPROCESS EVENT METHODS =====================

  /**
   * Get recent subprocess events.
   */
  getRecentSubprocessEvents(hours: number = 24): Observable<SubprocessEvent[]> {
    const params = new HttpParams().set('hours', hours.toString());
    return this.http.get<SubprocessEvent[]>(`${this.subprocessApiUrl}/recent`, { params });
  }

  /**
   * Get latest subprocess events (last 100).
   */
  getLatestSubprocessEvents(): Observable<SubprocessEvent[]> {
    return this.http.get<SubprocessEvent[]>(`${this.subprocessApiUrl}/latest`);
  }

  /**
   * Get subprocess restart events.
   */
  getSubprocessRestartEvents(): Observable<SubprocessEvent[]> {
    return this.http.get<SubprocessEvent[]>(`${this.subprocessApiUrl}/restarts`);
  }

  /**
   * Get subprocess restart events for a specific model.
   */
  getSubprocessRestartEventsForModel(modelId: string): Observable<SubprocessEvent[]> {
    return this.http.get<SubprocessEvent[]>(`${this.subprocessApiUrl}/restarts/${encodeURIComponent(modelId)}`);
  }

  /**
   * Get subprocess events for a specific task.
   */
  getSubprocessEventsForTask(taskId: string): Observable<SubprocessEvent[]> {
    return this.http.get<SubprocessEvent[]>(`${this.subprocessApiUrl}/task/${encodeURIComponent(taskId)}`);
  }

  /**
   * Get a specific subprocess event by ID.
   */
  getSubprocessEventById(id: number): Observable<SubprocessEvent> {
    return this.http.get<SubprocessEvent>(`${this.subprocessApiUrl}/${id}`);
  }

  /**
   * Get subprocess statistics.
   */
  getSubprocessStatistics(): Observable<SubprocessStatistics> {
    return this.http.get<SubprocessStatistics>(`${this.subprocessApiUrl}/statistics`);
  }

  /**
   * Get icon for subprocess event type.
   */
  getSubprocessEventIcon(eventType: SubprocessEventType): string {
    switch (eventType) {
      case 'SUBPROCESS_STARTED': return 'play_arrow';
      case 'SUBPROCESS_STOPPED': return 'stop';
      case 'SUBPROCESS_CRASHED': return 'error';
      case 'SUBPROCESS_RESTARTING': return 'autorenew';
      case 'SUBPROCESS_RESTART_SUCCESS': return 'check_circle';
      case 'SUBPROCESS_RESTART_EXHAUSTED': return 'block';
      case 'MODEL_LOADED': return 'download_done';
      case 'MODEL_FAILED': return 'error_outline';
      default: return 'help';
    }
  }

  /**
   * Get CSS class for subprocess event type.
   */
  getSubprocessEventClass(eventType: SubprocessEventType): string {
    switch (eventType) {
      case 'SUBPROCESS_STARTED':
      case 'SUBPROCESS_RESTART_SUCCESS':
      case 'MODEL_LOADED':
        return 'status-success';
      case 'SUBPROCESS_RESTARTING':
        return 'status-info';
      case 'SUBPROCESS_STOPPED':
        return 'status-warning';
      case 'SUBPROCESS_CRASHED':
      case 'SUBPROCESS_RESTART_EXHAUSTED':
      case 'MODEL_FAILED':
        return 'status-error';
      default:
        return '';
    }
  }

  /**
   * Format subprocess event type for display.
   */
  formatSubprocessEventType(eventType: SubprocessEventType): string {
    return eventType.replace(/_/g, ' ').toLowerCase().replace(/\b\w/g, l => l.toUpperCase());
  }
}
