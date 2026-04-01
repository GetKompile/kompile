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
import { BehaviorSubject, Observable, throwError } from 'rxjs';
import { catchError, tap } from 'rxjs/operators';
import { BaseService } from './base.service';
import {
  BackupStatus,
  BackupResult,
  BackupInfo,
  BackupListResponse,
  RestoreResult,
  BackupCleanupResponse,
  DeleteBackupResponse
} from '../models/api-models';

/**
 * Service for managing database and index backups.
 * Provides operations for triggering backups, listing backups, restoring, and cleanup.
 */
@Injectable({
  providedIn: 'root'
})
export class BackupService extends BaseService {

  /** Observable of backup service status */
  private statusSubject = new BehaviorSubject<BackupStatus | null>(null);
  public status$ = this.statusSubject.asObservable();

  /** Observable of available backups */
  private backupsSubject = new BehaviorSubject<BackupInfo[]>([]);
  public backups$ = this.backupsSubject.asObservable();

  constructor(private http: HttpClient) {
    super();
  }

  // ═══════════════════════════════════════════════════════════════════════════════
  // STATUS
  // ═══════════════════════════════════════════════════════════════════════════════

  /**
   * Get the current status of the backup service.
   */
  getStatus(): Observable<BackupStatus> {
    return this.http.get<BackupStatus>(`${this.backendUrl}/backup/status`)
      .pipe(
        tap(status => this.statusSubject.next(status)),
        catchError(this.handleError)
      );
  }

  /**
   * Get current status synchronously.
   */
  getCurrentStatus(): BackupStatus | null {
    return this.statusSubject.value;
  }

  /**
   * Check if a backup is currently in progress.
   */
  isBackupInProgress(): boolean {
    return this.statusSubject.value?.inProgress ?? false;
  }

  // ═══════════════════════════════════════════════════════════════════════════════
  // BACKUP OPERATIONS
  // ═══════════════════════════════════════════════════════════════════════════════

  /**
   * Trigger a manual backup.
   */
  triggerBackup(): Observable<BackupResult> {
    return this.http.post<BackupResult>(`${this.backendUrl}/backup/trigger`, {})
      .pipe(
        tap(() => {
          // Refresh status and backup list after triggering
          this.getStatus().subscribe();
          this.listBackups().subscribe();
        }),
        catchError(this.handleError)
      );
  }

  /**
   * List all available backups.
   */
  listBackups(): Observable<BackupListResponse> {
    return this.http.get<BackupListResponse>(`${this.backendUrl}/backup/list`)
      .pipe(
        tap(response => this.backupsSubject.next(response.backups)),
        catchError(this.handleError)
      );
  }

  /**
   * Get available backups synchronously.
   */
  getBackups(): BackupInfo[] {
    return this.backupsSubject.value;
  }

  // ═══════════════════════════════════════════════════════════════════════════════
  // RESTORE OPERATIONS
  // ═══════════════════════════════════════════════════════════════════════════════

  /**
   * Restore from a specific backup.
   * @param backupName The name of the backup to restore (e.g., "backup-20251220-143000.tar.gz")
   */
  restoreBackup(backupName: string): Observable<RestoreResult> {
    return this.http.post<RestoreResult>(`${this.backendUrl}/backup/${backupName}/restore`, {})
      .pipe(catchError(this.handleError));
  }

  // ═══════════════════════════════════════════════════════════════════════════════
  // DOWNLOAD
  // ═══════════════════════════════════════════════════════════════════════════════

  /**
   * Get the download URL for a backup file.
   * @param backupName The name of the backup to download
   */
  getDownloadUrl(backupName: string): string {
    return `${this.backendUrl}/backup/${backupName}/download`;
  }

  /**
   * Trigger download of a backup file.
   * Opens the download URL in a new window/tab.
   * @param backupName The name of the backup to download
   */
  downloadBackup(backupName: string): void {
    const url = this.getDownloadUrl(backupName);
    window.open(url, '_blank');
  }

  // ═══════════════════════════════════════════════════════════════════════════════
  // DELETE OPERATIONS
  // ═══════════════════════════════════════════════════════════════════════════════

  /**
   * Delete a specific backup.
   * @param backupName The name of the backup to delete
   */
  deleteBackup(backupName: string): Observable<DeleteBackupResponse> {
    return this.http.delete<DeleteBackupResponse>(`${this.backendUrl}/backup/${backupName}`)
      .pipe(
        tap(() => {
          // Refresh backup list after deletion
          this.listBackups().subscribe();
        }),
        catchError(this.handleError)
      );
  }

  /**
   * Trigger cleanup of old backups (based on retention policy).
   */
  cleanup(): Observable<BackupCleanupResponse> {
    return this.http.post<BackupCleanupResponse>(`${this.backendUrl}/backup/cleanup`, {})
      .pipe(
        tap(() => {
          // Refresh backup list after cleanup
          this.listBackups().subscribe();
        }),
        catchError(this.handleError)
      );
  }

  // ═══════════════════════════════════════════════════════════════════════════════
  // HELPERS
  // ═══════════════════════════════════════════════════════════════════════════════

  /**
   * Format a file size in bytes to a human-readable string.
   */
  formatSize(sizeMB: number): string {
    if (sizeMB < 1) {
      return `${(sizeMB * 1024).toFixed(1)} KB`;
    } else if (sizeMB >= 1024) {
      return `${(sizeMB / 1024).toFixed(2)} GB`;
    }
    return `${sizeMB.toFixed(1)} MB`;
  }

  /**
   * Format a duration in milliseconds to a human-readable string.
   */
  formatDuration(durationMs: number): string {
    if (durationMs < 1000) {
      return `${durationMs} ms`;
    } else if (durationMs < 60000) {
      return `${(durationMs / 1000).toFixed(1)} s`;
    }
    const minutes = Math.floor(durationMs / 60000);
    const seconds = Math.floor((durationMs % 60000) / 1000);
    return `${minutes}m ${seconds}s`;
  }

  // ═══════════════════════════════════════════════════════════════════════════════
  // ERROR HANDLING
  // ═══════════════════════════════════════════════════════════════════════════════

  private handleError(error: HttpErrorResponse): Observable<never> {
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
    console.error('BackupService error:', errorMessage);
    return throwError(() => new Error(errorMessage));
  }
}
