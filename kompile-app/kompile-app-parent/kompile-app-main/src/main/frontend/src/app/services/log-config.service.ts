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

export interface LogConfiguration {
  enabled: boolean;
  retentionDays: number;
  maxEntriesPerJob: number;
  maxTotalEntries: number;
  archiveEnabled?: boolean;
  archivePath?: string;
  archiveOnCleanup?: boolean;
}

export interface LogStatus {
  enabled: boolean;
  totalLogEntries: number;
  maxTotalEntries: number;
  utilizationPercent: number;
  retentionDays: number;
  activeSequenceTrackers: number;
  reason?: string;
}

export interface LogConfigResponse {
  available: boolean;
  config: LogConfiguration;
  status: LogStatus;
  message?: string;
}

export interface LogConfigUpdateRequest {
  enabled?: boolean;
  retentionDays?: number;
  maxEntriesPerJob?: number;
  maxTotalEntries?: number;
  archiveEnabled?: boolean;
  archivePath?: string;
  archiveOnCleanup?: boolean;
}

export interface ArchiveInfo {
  fileName: string;
  filePath: string;
  sizeBytes: number;
  sizeFormatted: string;
  createdAt: string;
}

export interface ArchiveListResponse {
  available: boolean;
  archiveEnabled: boolean;
  archivePath: string;
  archiveOnCleanup: boolean;
  archives: ArchiveInfo[];
}

export interface ArchiveCreateResponse {
  success: boolean;
  archivePath?: string;
  fileName?: string;
  message: string;
}

export interface CleanupResponse {
  success: boolean;
  deletedCount: number;
  hoursRetained: number;
  statusBefore: LogStatus;
  statusAfter: LogStatus;
}

@Injectable({
  providedIn: 'root'
})
export class LogConfigService {
  private readonly apiUrl = `${backendUrl}/config/logs`;

  constructor(private http: HttpClient) { }

  /**
   * Get current log configuration.
   */
  getConfiguration(): Observable<LogConfigResponse> {
    return this.http.get<LogConfigResponse>(this.apiUrl);
  }

  /**
   * Update log configuration.
   */
  updateConfiguration(config: LogConfigUpdateRequest): Observable<LogConfigResponse> {
    return this.http.put<LogConfigResponse>(this.apiUrl, config);
  }

  /**
   * Get log storage status.
   */
  getStatus(): Observable<LogStatus> {
    return this.http.get<LogStatus>(`${this.apiUrl}/status`);
  }

  /**
   * Trigger manual cleanup.
   */
  triggerCleanup(hoursToKeep: number = 168): Observable<CleanupResponse> {
    const params = new HttpParams().set('hoursToKeep', hoursToKeep.toString());
    return this.http.post<CleanupResponse>(`${this.apiUrl}/cleanup`, null, { params });
  }

  /**
   * Enable job logging.
   */
  enable(): Observable<{ success: boolean; enabled: boolean; message: string }> {
    return this.http.post<{ success: boolean; enabled: boolean; message: string }>(
      `${this.apiUrl}/enable`,
      null
    );
  }

  /**
   * Disable job logging.
   */
  disable(): Observable<{ success: boolean; enabled: boolean; message: string }> {
    return this.http.post<{ success: boolean; enabled: boolean; message: string }>(
      `${this.apiUrl}/disable`,
      null
    );
  }

  // ========== Archive Methods ==========

  /**
   * List available archives.
   */
  listArchives(): Observable<ArchiveListResponse> {
    return this.http.get<ArchiveListResponse>(`${this.apiUrl}/archives`);
  }

  /**
   * Create a full archive of all current logs.
   */
  createArchive(): Observable<ArchiveCreateResponse> {
    return this.http.post<ArchiveCreateResponse>(`${this.apiUrl}/archives/create`, null);
  }

  /**
   * Archive logs for a specific task.
   */
  archiveTaskLogs(taskId: string): Observable<ArchiveCreateResponse> {
    return this.http.post<ArchiveCreateResponse>(`${this.apiUrl}/archives/task/${taskId}`, null);
  }

  /**
   * Get download URL for an archive.
   */
  getArchiveDownloadUrl(fileName: string): string {
    return `${this.apiUrl}/archives/download/${encodeURIComponent(fileName)}`;
  }

  /**
   * Delete an archive.
   */
  deleteArchive(fileName: string): Observable<{ success: boolean; fileName: string; message: string }> {
    return this.http.delete<{ success: boolean; fileName: string; message: string }>(
      `${this.apiUrl}/archives/${encodeURIComponent(fileName)}`
    );
  }

  /**
   * Format file size for display.
   */
  formatFileSize(bytes: number): string {
    if (bytes < 1024) return bytes + ' B';
    if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB';
    if (bytes < 1024 * 1024 * 1024) return (bytes / (1024 * 1024)).toFixed(1) + ' MB';
    return (bytes / (1024 * 1024 * 1024)).toFixed(1) + ' GB';
  }

  /**
   * Format storage size for display.
   */
  formatCount(count: number): string {
    if (count < 1000) return count.toString();
    if (count < 1000000) return `${(count / 1000).toFixed(1)}K`;
    return `${(count / 1000000).toFixed(1)}M`;
  }

  /**
   * Get utilization color class.
   */
  getUtilizationClass(percent: number): string {
    if (percent < 50) return 'utilization-low';
    if (percent < 80) return 'utilization-medium';
    return 'utilization-high';
  }
}
