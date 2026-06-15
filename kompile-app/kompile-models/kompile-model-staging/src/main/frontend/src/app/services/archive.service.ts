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
import {
  ArchiveInstallInfo,
  ArchiveExportRequest,
  ArchiveExportResult,
  ArchiveImportRequest,
  ArchiveImportResult,
  ArchiveDownloadRequest,
  ArchiveDownloadResult,
  ArchiveUpdateResponse,
  UpdateInfo,
  UpdateApplyResult,
  BatchUpdateResult,
  RemoteCatalog,
  CacheStatus
} from '../models/api-models';

@Injectable({
  providedIn: 'root'
})
export class ArchiveService {

  private baseUrl: string;

  constructor(private http: HttpClient) {
    // Dynamic API URL based on current location
    if (typeof window !== 'undefined' && window.location) {
      const protocol = window.location.protocol;
      const hostname = window.location.hostname;
      const port = window.location.port;
      this.baseUrl = `${protocol}//${hostname}${port ? ':' + port : ''}/api/staging/archives`;
    } else {
      this.baseUrl = '/api/staging/archives';
    }
  }

  // ==================== Archive List Operations ====================

  /**
   * Get all installed archives.
   */
  listArchives(): Observable<ArchiveInstallInfo[]> {
    return this.http.get<ArchiveInstallInfo[]>(this.baseUrl)
      .pipe(catchError(this.handleError));
  }

  /**
   * Get details of a specific installed archive.
   */
  getArchive(archiveId: string): Observable<ArchiveInstallInfo> {
    return this.http.get<ArchiveInstallInfo>(`${this.baseUrl}/${archiveId}`)
      .pipe(catchError(this.handleError));
  }

  /**
   * Uninstall an archive.
   */
  uninstallArchive(archiveId: string, deleteModels: boolean = false): Observable<any> {
    return this.http.delete(`${this.baseUrl}/${archiveId}?deleteModels=${deleteModels}`)
      .pipe(catchError(this.handleError));
  }

  // ==================== Export Operations ====================

  /**
   * Export models to a Kompile archive (.karch).
   */
  exportArchive(request: ArchiveExportRequest): Observable<ArchiveExportResult> {
    return this.http.post<ArchiveExportResult>(`${this.baseUrl}/export`, request)
      .pipe(catchError(this.handleError));
  }

  // ==================== Import Operations ====================

  /**
   * Import a Kompile archive (.karch).
   */
  importArchive(request: ArchiveImportRequest): Observable<ArchiveImportResult> {
    return this.http.post<ArchiveImportResult>(`${this.baseUrl}/import`, request)
      .pipe(catchError(this.handleError));
  }

  // ==================== Download Operations ====================

  /**
   * Download a Kompile archive from a URL.
   */
  downloadArchive(request: ArchiveDownloadRequest): Observable<ArchiveDownloadResult> {
    return this.http.post<ArchiveDownloadResult>(`${this.baseUrl}/download`, request)
      .pipe(catchError(this.handleError));
  }

  // ==================== Remote Catalog Operations ====================

  /**
   * Get the remote catalog of available archives.
   */
  getRemoteCatalog(refresh: boolean = false): Observable<RemoteCatalog> {
    return this.http.get<RemoteCatalog>(`${this.baseUrl}/catalog/remote?refresh=${refresh}`)
      .pipe(catchError(this.handleError));
  }

  /**
   * Get cache status for remote catalogs.
   */
  getCatalogCacheStatus(): Observable<{ [key: string]: CacheStatus }> {
    return this.http.get<{ [key: string]: CacheStatus }>(`${this.baseUrl}/catalog/cache`)
      .pipe(catchError(this.handleError));
  }

  /**
   * Clear the catalog cache.
   */
  clearCatalogCache(): Observable<any> {
    return this.http.delete(`${this.baseUrl}/catalog/cache`)
      .pipe(catchError(this.handleError));
  }

  // ==================== Update Operations ====================

  /**
   * Check for updates for all installed archives.
   */
  checkForUpdates(refresh: boolean = false): Observable<ArchiveUpdateResponse> {
    return this.http.get<ArchiveUpdateResponse>(`${this.baseUrl}/updates?refresh=${refresh}`)
      .pipe(catchError(this.handleError));
  }

  /**
   * Check for update for a specific archive.
   */
  checkForUpdate(archiveId: string): Observable<UpdateInfo> {
    return this.http.get<UpdateInfo>(`${this.baseUrl}/updates/${archiveId}`)
      .pipe(catchError(this.handleError));
  }

  /**
   * Apply update for a specific archive.
   */
  applyUpdate(archiveId: string): Observable<UpdateApplyResult> {
    return this.http.post<UpdateApplyResult>(`${this.baseUrl}/updates/${archiveId}/apply`, {})
      .pipe(catchError(this.handleError));
  }

  /**
   * Apply all available updates.
   */
  applyAllUpdates(): Observable<BatchUpdateResult> {
    return this.http.post<BatchUpdateResult>(`${this.baseUrl}/updates/apply-all`, {})
      .pipe(catchError(this.handleError));
  }

  // ==================== Utility Methods ====================

  /**
   * Format file size for display.
   */
  formatSize(bytes: number): string {
    if (bytes < 1024) return bytes + ' B';
    if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB';
    if (bytes < 1024 * 1024 * 1024) return (bytes / (1024 * 1024)).toFixed(1) + ' MB';
    return (bytes / (1024 * 1024 * 1024)).toFixed(2) + ' GB';
  }

  /**
   * Get display text for version diff.
   */
  getVersionDiffDisplay(diff: string): string {
    switch (diff) {
      case 'MAJOR_UPGRADE': return 'Major';
      case 'MINOR_UPGRADE': return 'Minor';
      case 'PATCH_UPGRADE': return 'Patch';
      case 'SAME': return 'Same';
      case 'DOWNGRADE': return 'Downgrade';
      default: return diff;
    }
  }

  // ==================== Error Handling ====================

  private handleError(error: HttpErrorResponse): Observable<never> {
    let errorMessage = 'An error occurred';

    if (error.error instanceof ErrorEvent) {
      // Client-side error
      errorMessage = `Error: ${error.error.message}`;
    } else {
      // Server-side error
      if (error.error?.message) {
        errorMessage = error.error.message;
      } else if (error.error?.error) {
        errorMessage = error.error.error;
      } else {
        errorMessage = `Error ${error.status}: ${error.message}`;
      }
    }

    console.error('ArchiveService error:', errorMessage);
    return throwError(() => new Error(errorMessage));
  }
}
