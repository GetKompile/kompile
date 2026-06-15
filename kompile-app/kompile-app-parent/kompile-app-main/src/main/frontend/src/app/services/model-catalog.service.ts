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
import { HttpClient } from '@angular/common/http';
import { BehaviorSubject, Observable, tap, catchError, of } from 'rxjs';
import { environment } from '../../environments/environment';
import {
  BuiltInModelCatalog,
  BuiltInModelInfo,
  AssembleArchiveRequest,
  AssembleArchiveResponse,
  StagingArchiveExportRequest,
  StagingArchiveExportResponse
} from '../models/api-models';

/**
 * Service for interacting with the model catalog API.
 * Provides access to built-in models for archive assembly.
 */
@Injectable({
  providedIn: 'root'
})
export class ModelCatalogService {

  private readonly apiUrl = `${environment.apiUrl}/models`;

  // State
  private catalogSubject = new BehaviorSubject<BuiltInModelCatalog | null>(null);
  private loadingSubject = new BehaviorSubject<boolean>(false);
  private errorSubject = new BehaviorSubject<string | null>(null);

  // Observables
  catalog$ = this.catalogSubject.asObservable();
  loading$ = this.loadingSubject.asObservable();
  error$ = this.errorSubject.asObservable();

  constructor(private http: HttpClient) {}

  /**
   * Load the complete model catalog.
   */
  loadCatalog(): Observable<BuiltInModelCatalog> {
    this.loadingSubject.next(true);
    this.errorSubject.next(null);

    return this.http.get<BuiltInModelCatalog>(`${this.apiUrl}/catalog`).pipe(
      tap(catalog => {
        this.catalogSubject.next(catalog);
        this.loadingSubject.next(false);
      }),
      catchError(err => {
        this.errorSubject.next('Failed to load model catalog');
        this.loadingSubject.next(false);
        throw err;
      })
    );
  }

  /**
   * Get the current catalog (cached).
   */
  getCatalog(): BuiltInModelCatalog | null {
    return this.catalogSubject.value;
  }

  /**
   * Get dense encoder models.
   */
  getDenseEncoders(): Observable<BuiltInModelInfo[]> {
    return this.http.get<BuiltInModelInfo[]>(`${this.apiUrl}/catalog/dense-encoders`);
  }

  /**
   * Get sparse encoder models.
   */
  getSparseEncoders(): Observable<BuiltInModelInfo[]> {
    return this.http.get<BuiltInModelInfo[]>(`${this.apiUrl}/catalog/sparse-encoders`);
  }

  /**
   * Get cross-encoder models.
   */
  getCrossEncoders(): Observable<BuiltInModelInfo[]> {
    return this.http.get<BuiltInModelInfo[]>(`${this.apiUrl}/catalog/cross-encoders`);
  }

  /**
   * Get a specific model by ID.
   */
  getModel(modelId: string): Observable<BuiltInModelInfo> {
    return this.http.get<BuiltInModelInfo>(`${this.apiUrl}/catalog/${modelId}`);
  }

  /**
   * Assemble a custom archive with selected models.
   * This validates the selection and returns archive metadata.
   */
  assembleArchive(request: AssembleArchiveRequest): Observable<AssembleArchiveResponse> {
    return this.http.post<AssembleArchiveResponse>(`${this.apiUrl}/catalog/assemble`, request);
  }

  /**
   * Export an archive via the staging service.
   * This actually creates the .karch file on disk.
   */
  exportArchive(request: StagingArchiveExportRequest): Observable<StagingArchiveExportResponse> {
    const stagingUrl = `${environment.apiUrl}/staging/archives/export`;
    return this.http.post<StagingArchiveExportResponse>(stagingUrl, request);
  }

  /**
   * Download an archive file.
   * Returns a Blob that can be saved by the browser.
   */
  downloadArchive(archivePath: string): Observable<Blob> {
    const downloadUrl = `${environment.apiUrl}/staging/archives/download-file`;
    return this.http.post(downloadUrl, { path: archivePath }, { responseType: 'blob' });
  }

  /**
   * Get models grouped by category for easy selection.
   */
  getModelsByCategory(): {
    retrieval: {
      dense: BuiltInModelInfo[];
      sparse: BuiltInModelInfo[];
    };
    reranking: BuiltInModelInfo[];
  } | null {
    const catalog = this.catalogSubject.value;
    if (!catalog) return null;

    return {
      retrieval: {
        dense: catalog.denseEncoders,
        sparse: catalog.sparseEncoders
      },
      reranking: catalog.crossEncoders
    };
  }

  /**
   * Clear any errors.
   */
  clearError(): void {
    this.errorSubject.next(null);
  }
}
