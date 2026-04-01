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
import { BaseService } from './base.service';
import { ModelRegistryService } from './model-registry.service';
import { ModelRegistry, VersionInfoResponse } from '../models/api-models';

/**
 * Embedding model status from the backend.
 */
export interface EmbeddingModelStatus {
  available: boolean;
  initialized: boolean;
  modelId: string;
  source: string;
  dimensions: number;
  maxSequenceLength: number;
  canReload: boolean;
  message?: string;
}

/**
 * Available embedding model info.
 */
export interface AvailableModelInfo {
  modelId: string;
  source: string;
  embeddingDim: number | null;
  maxSequenceLength: number | null;
  type: string;
  status: 'active' | 'available' | 'unavailable';
  description?: string;
}

/**
 * Available models response.
 */
export interface AvailableModelsResponse {
  available: boolean;
  currentModel: string;
  currentSource: string;
  models: AvailableModelInfo[];
  message?: string;
}

/**
 * Model switch/reload response.
 */
export interface ModelSwitchResponse {
  success: boolean;
  message?: string;
  previousModel?: string;
  previousSource?: string;
  currentModel?: string;
  currentSource?: string;
  dimensions?: number;
  error?: string;
}

/**
 * Registry refresh response.
 */
export interface RegistryRefreshResponse {
  success: boolean;
  message?: string;
  modelCount?: number;
  encoderCount?: number;
  crossEncoderCount?: number;
  embeddingModelReloaded?: boolean;
  previousModel?: string;
  currentModel?: string;
  modelSource?: string;
  dimensions?: number;
  embeddingModelMessage?: string;
  error?: string;
}

/**
 * Service for interacting with the local model registry.
 * Provides read-only access to the registry and model loading capabilities.
 */
@Injectable({
  providedIn: 'root'
})
export class LocalRegistryService extends BaseService {

  // State subjects
  private registrySubject = new BehaviorSubject<ModelRegistry | null>(null);
  private versionInfoSubject = new BehaviorSubject<VersionInfoResponse | null>(null);
  private embeddingStatusSubject = new BehaviorSubject<EmbeddingModelStatus | null>(null);
  private availableModelsSubject = new BehaviorSubject<AvailableModelsResponse | null>(null);
  private loadingSubject = new BehaviorSubject<boolean>(false);
  private errorSubject = new BehaviorSubject<string | null>(null);

  // Public observables
  registry$ = this.registrySubject.asObservable();
  versionInfo$ = this.versionInfoSubject.asObservable();
  embeddingStatus$ = this.embeddingStatusSubject.asObservable();
  availableModels$ = this.availableModelsSubject.asObservable();
  loading$ = this.loadingSubject.asObservable();
  error$ = this.errorSubject.asObservable();

  constructor(
    private http: HttpClient,
    private modelRegistryService: ModelRegistryService
  ) {
    super();
  }

  /**
   * Load the full model registry.
   */
  loadRegistry(): Observable<ModelRegistry> {
    this.loadingSubject.next(true);
    this.errorSubject.next(null);

    return this.http.get<ModelRegistry>(`${this.backendUrl}/models/registry`).pipe(
      tap(registry => {
        this.registrySubject.next(registry);
        this.loadingSubject.next(false);
      }),
      catchError(err => {
        this.errorSubject.next('Failed to load registry');
        this.loadingSubject.next(false);
        throw err;
      })
    );
  }

  /**
   * Get version info and provenance summary.
   */
  loadVersionInfo(): Observable<VersionInfoResponse> {
    return this.http.get<VersionInfoResponse>(`${this.backendUrl}/models/registry/version-info`).pipe(
      tap(info => this.versionInfoSubject.next(info)),
      catchError(err => {
        console.error('Failed to load version info', err);
        return of({
          registryVersion: '1.0',
          updatedAt: '',
          totalModels: 0,
          activeModels: 0,
          modelsBySource: {},
          installedArchives: []
        } as VersionInfoResponse);
      })
    );
  }

  /**
   * Get current embedding model status.
   */
  getEmbeddingStatus(): Observable<EmbeddingModelStatus> {
    return this.http.get<EmbeddingModelStatus>(`${this.backendUrl}/models/embedding/status`).pipe(
      tap(status => this.embeddingStatusSubject.next(status)),
      catchError(err => {
        console.error('Failed to get embedding status', err);
        return of({
          available: false,
          initialized: false,
          modelId: '',
          source: '',
          dimensions: 0,
          maxSequenceLength: 0,
          canReload: false,
          message: 'Failed to get embedding status'
        } as EmbeddingModelStatus);
      })
    );
  }

  /**
   * Get available embedding models from registry and built-in sources.
   */
  getAvailableModels(): Observable<AvailableModelsResponse> {
    return this.http.get<AvailableModelsResponse>(`${this.backendUrl}/models/embedding/available`).pipe(
      tap(response => this.availableModelsSubject.next(response)),
      catchError(err => {
        console.error('Failed to get available models', err);
        return of({
          available: false,
          currentModel: '',
          currentSource: '',
          models: [],
          message: 'Failed to get available models'
        } as AvailableModelsResponse);
      })
    );
  }

  /**
   * Reload the current embedding model from its source.
   */
  reloadEmbeddingModel(): Observable<ModelSwitchResponse> {
    return this.http.post<ModelSwitchResponse>(`${this.backendUrl}/models/embedding/reload`, {}).pipe(
      tap(response => {
        if (response.success) {
          this.getEmbeddingStatus().subscribe();
          this.modelRegistryService.notifyModelLoaded(response.currentModel || '');
        }
      }),
      catchError(err => {
        console.error('Failed to reload embedding model', err);
        return of({
          success: false,
          error: 'Failed to reload embedding model'
        } as ModelSwitchResponse);
      })
    );
  }

  /**
   * Switch to a different embedding model.
   */
  switchEmbeddingModel(modelId: string): Observable<ModelSwitchResponse> {
    return this.http.post<ModelSwitchResponse>(`${this.backendUrl}/models/embedding/switch/${modelId}`, {}).pipe(
      tap(response => {
        if (response.success) {
          this.getEmbeddingStatus().subscribe();
          this.getAvailableModels().subscribe();
          this.modelRegistryService.notifyModelLoaded(modelId);
        }
      }),
      catchError(err => {
        console.error('Failed to switch embedding model', err);
        return of({
          success: false,
          error: `Failed to switch to model: ${modelId}`
        } as ModelSwitchResponse);
      })
    );
  }

  /**
   * Refresh registry and reload the embedding model.
   * This is the recommended call after importing archives.
   */
  refreshAndReload(): Observable<RegistryRefreshResponse> {
    this.loadingSubject.next(true);

    return this.http.post<RegistryRefreshResponse>(`${this.backendUrl}/models/registry/refresh-and-reload`, {}).pipe(
      tap(response => {
        this.loadingSubject.next(false);
        if (response.success) {
          // Refresh all cached data
          this.loadRegistry().subscribe();
          this.loadVersionInfo().subscribe();
          this.getEmbeddingStatus().subscribe();
          this.getAvailableModels().subscribe();
          this.modelRegistryService.notifyRegistryRefreshed();
        }
      }),
      catchError(err => {
        this.loadingSubject.next(false);
        console.error('Failed to refresh and reload', err);
        return of({
          success: false,
          error: 'Failed to refresh registry and reload model'
        } as RegistryRefreshResponse);
      })
    );
  }

  /**
   * Refresh the registry cache only (without reloading models).
   */
  refreshRegistry(): Observable<RegistryRefreshResponse> {
    return this.http.post<RegistryRefreshResponse>(`${this.backendUrl}/models/registry/refresh`, {}).pipe(
      tap(response => {
        if (response.success) {
          this.loadRegistry().subscribe();
          this.loadVersionInfo().subscribe();
          this.modelRegistryService.notifyRegistryRefreshed();
        }
      }),
      catchError(err => {
        console.error('Failed to refresh registry', err);
        return of({
          success: false,
          error: 'Failed to refresh registry'
        } as RegistryRefreshResponse);
      })
    );
  }

  /**
   * Get models by type from the registry.
   */
  getModelsByType(type: string): Observable<any[]> {
    return this.http.get<any[]>(`${this.backendUrl}/models/registry/${type}`);
  }

  /**
   * Get current cached registry.
   */
  getRegistry(): ModelRegistry | null {
    return this.registrySubject.value;
  }

  /**
   * Get current cached version info.
   */
  getVersionInfo(): VersionInfoResponse | null {
    return this.versionInfoSubject.value;
  }

  /**
   * Get current cached embedding status.
   */
  getEmbeddingStatusCached(): EmbeddingModelStatus | null {
    return this.embeddingStatusSubject.value;
  }

  /**
   * Clear any errors.
   */
  clearError(): void {
    this.errorSubject.next(null);
  }
}
