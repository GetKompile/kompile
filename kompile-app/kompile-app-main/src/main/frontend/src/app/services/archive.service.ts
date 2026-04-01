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
import { ModelRegistryService } from './model-registry.service';
import {
  ArchiveInfo,
  ArchiveStatus,
  ArchiveModelInfo,
  LoadArchiveRequest,
  ExtractModelRequest,
  ExtractResult
} from '../models/api-models';

/**
 * Service for managing Kompile Archives (.karch).
 * Provides operations for listing, loading, and extracting models from archives.
 */
@Injectable({
  providedIn: 'root'
})
export class ArchiveService extends BaseService {

  /** Observable of available archives */
  private archivesSubject = new BehaviorSubject<ArchiveInfo[]>([]);
  public archives$ = this.archivesSubject.asObservable();

  /** Observable of current archive status */
  private statusSubject = new BehaviorSubject<ArchiveStatus | null>(null);
  public status$ = this.statusSubject.asObservable();

  /** Observable of models in the loaded archive */
  private modelsSubject = new BehaviorSubject<ArchiveModelInfo[]>([]);
  public models$ = this.modelsSubject.asObservable();

  constructor(
    private http: HttpClient,
    private modelRegistryService: ModelRegistryService
  ) {
    super();
  }

  // ═══════════════════════════════════════════════════════════════════════════════
  // ARCHIVE LISTING
  // ═══════════════════════════════════════════════════════════════════════════════

  /**
   * List all available archives.
   */
  listArchives(): Observable<ArchiveInfo[]> {
    return this.http.get<ArchiveInfo[]>(`${this.backendUrl}/archives`)
      .pipe(
        tap(archives => this.archivesSubject.next(archives)),
        catchError(this.handleError)
      );
  }

  /**
   * Get available archives synchronously.
   */
  getArchives(): ArchiveInfo[] {
    return this.archivesSubject.value;
  }

  // ═══════════════════════════════════════════════════════════════════════════════
  // ARCHIVE STATUS
  // ═══════════════════════════════════════════════════════════════════════════════

  /**
   * Get the status of the currently loaded archive.
   */
  getStatus(): Observable<ArchiveStatus> {
    return this.http.get<ArchiveStatus>(`${this.backendUrl}/archives/status`)
      .pipe(
        tap(status => this.statusSubject.next(status)),
        catchError(this.handleError)
      );
  }

  /**
   * Get current status synchronously.
   */
  getCurrentStatus(): ArchiveStatus | null {
    return this.statusSubject.value;
  }

  /**
   * Check if an archive is currently loaded.
   */
  isArchiveLoaded(): boolean {
    return this.statusSubject.value?.loaded ?? false;
  }

  // ═══════════════════════════════════════════════════════════════════════════════
  // ARCHIVE LOADING
  // ═══════════════════════════════════════════════════════════════════════════════

  /**
   * Load an archive from a file path.
   */
  loadArchive(archivePath: string): Observable<ArchiveStatus> {
    const request: LoadArchiveRequest = { archivePath };
    return this.http.post<ArchiveStatus>(`${this.backendUrl}/archives/load`, request)
      .pipe(
        tap(status => {
          this.statusSubject.next(status);
          this.listArchives().subscribe(); // Refresh archive list
          if (status.loaded) {
            this.loadModels().subscribe(); // Load models from archive
            // Notify that an archive was loaded
            this.modelRegistryService.notifyArchiveLoaded(status.archiveId || archivePath);
          }
        }),
        catchError(this.handleError)
      );
  }

  /**
   * Unload the currently loaded archive.
   */
  unloadArchive(): Observable<ArchiveStatus> {
    const currentArchiveId = this.statusSubject.value?.archiveId;
    return this.http.post<ArchiveStatus>(`${this.backendUrl}/archives/unload`, {})
      .pipe(
        tap(status => {
          this.statusSubject.next(status);
          this.modelsSubject.next([]);
          this.listArchives().subscribe();
          // Notify that an archive was unloaded
          this.modelRegistryService.notifyArchiveUnloaded(currentArchiveId || undefined);
        }),
        catchError(this.handleError)
      );
  }

  // ═══════════════════════════════════════════════════════════════════════════════
  // MODEL OPERATIONS
  // ═══════════════════════════════════════════════════════════════════════════════

  /**
   * Load models from the currently loaded archive.
   */
  loadModels(): Observable<ArchiveModelInfo[]> {
    return this.http.get<ArchiveModelInfo[]>(`${this.backendUrl}/archives/models`)
      .pipe(
        tap(models => this.modelsSubject.next(models)),
        catchError(this.handleError)
      );
  }

  /**
   * Get models by type from the loaded archive.
   */
  getModelsByType(type: string): Observable<ArchiveModelInfo[]> {
    return this.http.get<ArchiveModelInfo[]>(`${this.backendUrl}/archives/models/${type}`)
      .pipe(catchError(this.handleError));
  }

  /**
   * Get encoder models from the loaded archive.
   */
  getEncoders(): Observable<ArchiveModelInfo[]> {
    return this.getModelsByType('encoder');
  }

  /**
   * Get cross-encoder models from the loaded archive.
   */
  getCrossEncoders(): Observable<ArchiveModelInfo[]> {
    return this.getModelsByType('cross_encoder');
  }

  /**
   * Get models synchronously.
   */
  getModels(): ArchiveModelInfo[] {
    return this.modelsSubject.value;
  }

  /**
   * Extract a model from the loaded archive.
   */
  extractModel(modelId: string, destinationPath?: string): Observable<ExtractResult> {
    const request: ExtractModelRequest = { modelId, destinationPath };
    const archiveId = this.statusSubject.value?.archiveId;
    return this.http.post<ExtractResult>(`${this.backendUrl}/archives/extract`, request)
      .pipe(
        tap(result => {
          if (result.success) {
            // Notify that a model was extracted
            this.modelRegistryService.notifyModelExtracted(modelId, archiveId || undefined);
          }
        }),
        catchError(this.handleError)
      );
  }

  // ═══════════════════════════════════════════════════════════════════════════════
  // HELPERS
  // ═══════════════════════════════════════════════════════════════════════════════

  /**
   * Get encoder models for dropdown selection.
   * Returns an array suitable for use in a select dropdown.
   */
  getEncoderOptions(): { modelId: string; label: string; embeddingDim: number | null }[] {
    return this.modelsSubject.value
      .filter(m => m.type === 'encoder')
      .map(m => ({
        modelId: m.modelId,
        label: m.modelId + (m.embeddingDim ? ` (${m.embeddingDim}d)` : ''),
        embeddingDim: m.embeddingDim
      }));
  }

  /**
   * Get cross-encoder models for dropdown selection.
   * Returns an array suitable for use in a select dropdown.
   */
  getCrossEncoderOptions(): { modelId: string; label: string }[] {
    return this.modelsSubject.value
      .filter(m => m.type === 'cross_encoder')
      .map(m => ({
        modelId: m.modelId,
        label: m.modelId
      }));
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
    console.error('ArchiveService error:', errorMessage);
    return throwError(() => new Error(errorMessage));
  }
}
