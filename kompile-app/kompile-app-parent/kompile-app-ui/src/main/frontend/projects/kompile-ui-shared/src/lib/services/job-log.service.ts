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
import { Observable } from 'rxjs';
import { backendUrl } from './base.service';

export type LogLevel = 'TRACE' | 'DEBUG' | 'INFO' | 'WARN' | 'ERROR';
export type LogSource = 'STDOUT' | 'STDERR' | 'SYSTEM' | 'APPLICATION' | 'EMBEDDING' | 'LLM_TRANSCRIPT';

export interface JobLogEntry {
  id: number;
  taskId: string;
  timestamp: string;
  level: LogLevel;
  source: LogSource;
  message: string;
  loggerName?: string;
  threadName?: string;
  sequenceNumber: number;
  exceptionClass?: string;
  stackTrace?: string;
}

export interface JobLogsResponse {
  enabled: boolean;
  taskId: string;
  logs: JobLogEntry[];
  totalCount: number;
  page: number;
  size: number;
  levelCounts: { [key in LogLevel]?: number };
}

export interface LogTailResponse {
  enabled: boolean;
  taskId: string;
  logs: JobLogEntry[];
  count: number;
}

export interface LogErrorsResponse {
  enabled: boolean;
  taskId: string;
  errors: JobLogEntry[];
  count: number;
}

export interface LogCountResponse {
  enabled: boolean;
  taskId: string;
  count: number;
  levelCounts: { [key in LogLevel]?: number };
}

export interface LogStatistics {
  enabled: boolean;
  totalLogEntries: number;
  totalTasksWithLogs: number;
  topTasksByLogCount: { [taskId: string]: number };
  retentionDays: number;
  maxEntriesPerJob: number;
  maxTotalEntries: number;
  utilizationPercent: number;
}

export interface ArchivedLogEntry {
  taskId: string;
  timestamp: string;
  level: string;
  source: string;
  message: string;
  sequenceNumber: number;
}

export interface ArchivedLogsResponse {
  available: boolean;
  taskId: string;
  logs: ArchivedLogEntry[];
  totalCount: number;
  source: string;
  message?: string;
}

export interface ArchiveCheckResponse {
  available: boolean;
  taskId: string;
  hasArchive: boolean;
}

@Injectable({
  providedIn: 'root'
})
export class JobLogService {
  private readonly apiUrl = `${backendUrl}/indexing/jobs`;

  constructor(private http: HttpClient) { }

  /**
   * Get logs for a job with optional filtering.
   */
  getLogsForJob(
    taskId: string,
    options?: {
      level?: LogLevel;
      levels?: LogLevel[];
      source?: LogSource;
      search?: string;
      page?: number;
      size?: number;
    }
  ): Observable<JobLogsResponse> {
    let params = new HttpParams();

    if (options?.source) {
      params = params.set('source', options.source);
    }
    if (options?.level) {
      params = params.set('level', options.level);
    }
    if (options?.levels && options.levels.length > 0) {
      params = params.set('levels', options.levels.join(','));
    }
    if (options?.search) {
      params = params.set('search', options.search);
    }
    if (options?.page !== undefined) {
      params = params.set('page', options.page.toString());
    }
    if (options?.size !== undefined) {
      params = params.set('size', options.size.toString());
    }

    return this.http.get<JobLogsResponse>(`${this.apiUrl}/${taskId}/logs`, { params });
  }

  /**
   * Get the last N log entries for a job (tail).
   */
  tailLogs(taskId: string, lines: number = 100): Observable<LogTailResponse> {
    const params = new HttpParams().set('lines', lines.toString());
    return this.http.get<LogTailResponse>(`${this.apiUrl}/${taskId}/logs/tail`, { params });
  }

  /**
   * Download logs as a text file.
   */
  downloadLogs(taskId: string): Observable<Blob> {
    return this.http.get(`${this.apiUrl}/${taskId}/logs/download`, {
      responseType: 'blob'
    });
  }

  /**
   * Trigger download of logs as a file.
   */
  downloadLogsAsFile(taskId: string): void {
    this.downloadLogs(taskId).subscribe({
      next: (blob) => {
        const url = window.URL.createObjectURL(blob);
        const a = document.createElement('a');
        a.href = url;
        a.download = `job-logs-${taskId}.txt`;
        document.body.appendChild(a);
        a.click();
        document.body.removeChild(a);
        window.URL.revokeObjectURL(url);
      },
      error: (err) => {
        console.error('Error downloading logs:', err);
      }
    });
  }

  /**
   * Get log entries with errors (have stack traces).
   */
  getErrorsWithStackTrace(taskId: string): Observable<LogErrorsResponse> {
    return this.http.get<LogErrorsResponse>(`${this.apiUrl}/${taskId}/logs/errors`);
  }

  /**
   * Get log count for a job.
   */
  getLogCount(taskId: string): Observable<LogCountResponse> {
    return this.http.get<LogCountResponse>(`${this.apiUrl}/${taskId}/logs/count`);
  }

  /**
   * Delete logs for a job.
   */
  deleteLogsForJob(taskId: string): Observable<{ success: boolean; taskId: string; deletedCount: number }> {
    return this.http.delete<{ success: boolean; taskId: string; deletedCount: number }>(
      `${this.apiUrl}/${taskId}/logs`
    );
  }

  /**
   * Get log statistics across all jobs.
   */
  getStatistics(): Observable<LogStatistics> {
    return this.http.get<LogStatistics>(`${this.apiUrl}/logs/statistics`);
  }

  /**
   * Get archived logs for a job.
   * Used when live logs have been cleaned up but archives exist.
   */
  getArchivedLogs(taskId: string): Observable<ArchivedLogsResponse> {
    return this.http.get<ArchivedLogsResponse>(`${this.apiUrl}/${taskId}/logs/archive`);
  }

  /**
   * Check if archived logs exist for a job.
   */
  checkArchivedLogs(taskId: string): Observable<ArchiveCheckResponse> {
    return this.http.get<ArchiveCheckResponse>(`${this.apiUrl}/${taskId}/logs/archive/check`);
  }

  /**
   * Get CSS class for log level.
   */
  getLevelClass(level: LogLevel): string {
    switch (level) {
      case 'TRACE': return 'log-trace';
      case 'DEBUG': return 'log-debug';
      case 'INFO': return 'log-info';
      case 'WARN': return 'log-warn';
      case 'ERROR': return 'log-error';
      default: return '';
    }
  }

  /**
   * Get icon for log level.
   */
  getLevelIcon(level: LogLevel): string {
    switch (level) {
      case 'TRACE': return 'bug_report';
      case 'DEBUG': return 'code';
      case 'INFO': return 'info';
      case 'WARN': return 'warning';
      case 'ERROR': return 'error';
      default: return 'help';
    }
  }

  /**
   * Get icon for log source.
   */
  getSourceIcon(source: LogSource): string {
    switch (source) {
      case 'STDOUT': return 'terminal';
      case 'STDERR': return 'error_outline';
      case 'SYSTEM': return 'settings';
      case 'APPLICATION': return 'code';
      default: return 'help';
    }
  }

  /**
   * Format timestamp for display.
   */
  formatTimestamp(timestamp: string): string {
    try {
      const date = new Date(timestamp);
      return date.toLocaleTimeString(undefined, {
        hour: '2-digit',
        minute: '2-digit',
        second: '2-digit',
        fractionalSecondDigits: 3
      });
    } catch {
      return timestamp;
    }
  }

  /**
   * Format full timestamp with date.
   */
  formatFullTimestamp(timestamp: string): string {
    try {
      const date = new Date(timestamp);
      return date.toLocaleString();
    } catch {
      return timestamp;
    }
  }
}
