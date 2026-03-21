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
import { Observable, BehaviorSubject, throwError, TimeoutError } from 'rxjs';
import { tap, timeout, catchError } from 'rxjs/operators';
import { ModelRegistryService } from './model-registry.service';

/**
 * Staging service configuration.
 */
export interface StagingServiceConfig {
  id?: number;
  name: string;
  endpointUrl: string;
  apiKey?: string;
  active: boolean;
  verified: boolean;
  lastVerifiedAt?: string;
  lastError?: string;
  connectionTimeoutMs: number;
  readTimeoutMs: number;
  autoSync: boolean;
  syncIntervalMinutes: number;
  description?: string;
  createdAt?: string;
  updatedAt?: string;
}

/**
 * DTO for creating/updating configurations.
 */
export interface StagingServiceConfigDto {
  name: string;
  endpointUrl: string;
  apiKey?: string;
  active?: boolean;
  connectionTimeoutMs?: number;
  readTimeoutMs?: number;
  autoSync?: boolean;
  syncIntervalMinutes?: number;
  description?: string;
}

/**
 * Connection test result.
 */
export interface ConnectionTestResult {
  success: boolean;
  message: string;
  modelCount: number;
  version: string;
}

/**
 * Active models response (one per type).
 */
export interface ActiveModelsResponse {
  active: { [type: string]: string };
  types: string[];
}

/**
 * Connection status for retry functionality.
 */
export interface ConnectionStatus {
  connected: boolean;
  attempted: boolean;
  endpointUrl: string | null;
  lastError: string | null;
  lastAttemptTimeMs: number;
  consecutiveFailures: number;
  canRetry: boolean;
  timeSinceLastAttemptMs: number;
  activeConfigId?: number;
  activeConfigName?: string;
}

/**
 * Retry connection result.
 */
export interface RetryConnectionResult {
  success: boolean;
  message: string;
  modelCount?: number;
  version?: string;
  activeModels?: { [type: string]: string };
  connectionStatus?: {
    connected: boolean;
    consecutiveFailures: number;
    canRetry: boolean;
  };
}

/**
 * Retry discover result.
 */
export interface RetryDiscoverResult {
  success: boolean;
  message?: string;
  error?: string;
  canRetry: boolean;
  connectionSuccess?: boolean;
  connectionMessage?: string;
  registryAvailable?: boolean;
  modelCount?: number;
  activeModels?: { [type: string]: string };
  hasActiveModels?: boolean;
}

/**
 * Service for managing staging service configurations.
 */
@Injectable({
  providedIn: 'root'
})
export class StagingConfigService {

  private baseUrl: string;
  private configsSubject = new BehaviorSubject<StagingServiceConfig[]>([]);
  private activeConfigSubject = new BehaviorSubject<StagingServiceConfig | null>(null);
  private loadingSubject = new BehaviorSubject<boolean>(false);

  configs$ = this.configsSubject.asObservable();
  activeConfig$ = this.activeConfigSubject.asObservable();
  loading$ = this.loadingSubject.asObservable();

  constructor(
    private http: HttpClient,
    private modelRegistryService: ModelRegistryService
  ) {
    // Dynamic API URL based on current location
    if (typeof window !== 'undefined' && window.location) {
      const protocol = window.location.protocol;
      const hostname = window.location.hostname;
      const port = window.location.port;
      this.baseUrl = `${protocol}//${hostname}${port ? ':' + port : ''}/api/staging-config`;
    } else {
      this.baseUrl = '/api/staging-config';
    }
  }

  /**
   * Load all configurations.
   */
  loadConfigs(): Observable<StagingServiceConfig[]> {
    this.loadingSubject.next(true);
    return this.http.get<StagingServiceConfig[]>(`${this.baseUrl}/configs`).pipe(
      tap({
        next: (configs) => {
          this.configsSubject.next(configs);
          const active = configs.find(c => c.active);
          this.activeConfigSubject.next(active || null);
          this.loadingSubject.next(false);
        },
        error: () => this.loadingSubject.next(false)
      })
    );
  }

  /**
   * Get a specific configuration.
   */
  getConfig(id: number): Observable<StagingServiceConfig> {
    return this.http.get<StagingServiceConfig>(`${this.baseUrl}/configs/${id}`);
  }

  /**
   * Get the active configuration.
   */
  getActiveConfig(): Observable<StagingServiceConfig> {
    return this.http.get<StagingServiceConfig>(`${this.baseUrl}/configs/active`);
  }

  /**
   * Create a new configuration.
   */
  createConfig(config: StagingServiceConfigDto): Observable<StagingServiceConfig> {
    return this.http.post<StagingServiceConfig>(`${this.baseUrl}/configs`, config).pipe(
      tap(() => this.loadConfigs().subscribe())
    );
  }

  /**
   * Update an existing configuration.
   */
  updateConfig(id: number, config: StagingServiceConfigDto): Observable<StagingServiceConfig> {
    return this.http.put<StagingServiceConfig>(`${this.baseUrl}/configs/${id}`, config).pipe(
      tap(() => this.loadConfigs().subscribe())
    );
  }

  /**
   * Delete a configuration.
   */
  deleteConfig(id: number): Observable<any> {
    return this.http.delete(`${this.baseUrl}/configs/${id}`).pipe(
      tap(() => this.loadConfigs().subscribe())
    );
  }

  /**
   * Set a configuration as active.
   */
  activateConfig(id: number): Observable<StagingServiceConfig> {
    return this.http.post<StagingServiceConfig>(`${this.baseUrl}/configs/${id}/activate`, {}).pipe(
      tap(() => this.loadConfigs().subscribe())
    );
  }

  /**
   * Test connection to a specific configuration.
   */
  testConnection(id: number): Observable<ConnectionTestResult> {
    return this.http.post<ConnectionTestResult>(`${this.baseUrl}/configs/${id}/test`, {}).pipe(
      tap(() => this.loadConfigs().subscribe())
    );
  }

  /**
   * Test connection to the active configuration.
   */
  testActiveConnection(): Observable<ConnectionTestResult> {
    return this.http.post<ConnectionTestResult>(`${this.baseUrl}/test`, {});
  }

  // ==================== Connection Retry Operations ====================

  /**
   * Get the current connection status.
   * Useful for UI to check if a connection retry is needed.
   */
  getConnectionStatus(): Observable<ConnectionStatus> {
    return this.http.get<ConnectionStatus>(`${this.baseUrl}/connection-status`);
  }

  /**
   * Retry connection to the active staging service.
   * This is the main method for users to retry a failed connection.
   */
  retryConnection(): Observable<RetryConnectionResult> {
    return this.http.post<RetryConnectionResult>(`${this.baseUrl}/retry-connection`, {}).pipe(
      tap({
        next: (result) => {
          if (result.success) {
            // Notify that connection was restored
            this.modelRegistryService.notifyStagingApplied();
          }
        }
      })
    );
  }

  /**
   * Retry connection and discover models from the staging service.
   * This performs a full discovery: test connection, get registry, get active models.
   */
  retryDiscover(): Observable<RetryDiscoverResult> {
    return this.http.post<RetryDiscoverResult>(`${this.baseUrl}/retry-discover`, {}).pipe(
      tap({
        next: (result) => {
          if (result.success) {
            // Notify that discovery was successful
            this.modelRegistryService.notifyStagingApplied();
          }
        }
      })
    );
  }

  // ==================== Remote Staging Operations ====================

  /**
   * Get the model registry from the remote staging service.
   */
  getRemoteRegistry(): Observable<any> {
    return this.http.get(`${this.baseUrl}/remote/registry`);
  }

  /**
   * Get the model catalog from the remote staging service.
   */
  getRemoteCatalog(): Observable<any> {
    return this.http.get(`${this.baseUrl}/remote/catalog`);
  }

  /**
   * Get staging status from the remote staging service.
   */
  getRemoteStagingStatus(): Observable<any> {
    return this.http.get(`${this.baseUrl}/remote/status`);
  }

  /**
   * Stage a model on the remote staging service.
   */
  stageRemoteModel(modelId: string, autoPromote: boolean = false): Observable<any> {
    return this.http.post(`${this.baseUrl}/remote/stage/${modelId}?autoPromote=${autoPromote}`, {}).pipe(
      tap(() => {
        // Notify that staging was applied (model staged or promoted)
        this.modelRegistryService.notifyStagingApplied();
      })
    );
  }

  /**
   * Promote a staged model on the remote staging service.
   */
  promoteRemoteModel(modelId: string): Observable<any> {
    return this.http.post(`${this.baseUrl}/remote/promote/${modelId}`, {}).pipe(
      tap(() => {
        // Notify that a model was promoted to the registry
        this.modelRegistryService.notifyStagingApplied();
      })
    );
  }

  /**
   * Get a specific model from the remote registry.
   */
  getRemoteModel(modelId: string): Observable<any> {
    return this.http.get(`${this.baseUrl}/remote/model/${modelId}`);
  }

  /**
   * Refresh the configuration list.
   */
  refresh(): void {
    this.loadConfigs().subscribe();
  }

  // ==================== Active Model Management ====================

  /**
   * Get active models from the remote staging service (one per type).
   */
  getActiveModels(): Observable<ActiveModelsResponse> {
    return this.http.get<ActiveModelsResponse>(`${this.baseUrl}/remote/active`);
  }

  /**
   * Activate a model (deactivates other models of the same type).
   */
  activateModel(modelId: string): Observable<any> {
    return this.http.post(`${this.baseUrl}/remote/models/${modelId}/activate`, {}).pipe(
      tap(() => {
        this.modelRegistryService.notifyStagingApplied();
      })
    );
  }

  // ==================== Model Loading (Download from Remote + Load into App) ====================

  /**
   * Load a model from the remote staging service into the running application.
   * This will download the model if needed and switch the embedding model.
   *
   * @param modelId The model ID to load
   */
  loadModel(modelId: string): Observable<any> {
    // First, get the base URL for the models API
    const modelsBaseUrl = this.baseUrl.replace('/staging-config', '/models');
    return this.http.post(`${modelsBaseUrl}/embedding/switch/${modelId}`, {}).pipe(
      tap((response: any) => {
        if (response.success) {
          this.modelRegistryService.notifyStagingApplied();
        }
      })
    );
  }

  /**
   * Get current embedding model status.
   */
  getEmbeddingStatus(): Observable<any> {
    const modelsBaseUrl = this.baseUrl.replace('/staging-config', '/models');
    return this.http.get(`${modelsBaseUrl}/embedding/status`);
  }

  /**
   * Get available embedding models.
   */
  getAvailableEmbeddingModels(): Observable<any> {
    const modelsBaseUrl = this.baseUrl.replace('/staging-config', '/models');
    return this.http.get(`${modelsBaseUrl}/embedding/available`);
  }

  /**
   * Reload the current embedding model.
   */
  reloadEmbeddingModel(): Observable<any> {
    const modelsBaseUrl = this.baseUrl.replace('/staging-config', '/models');
    return this.http.post(`${modelsBaseUrl}/embedding/reload`, {}).pipe(
      tap(() => {
        this.modelRegistryService.notifyStagingApplied();
      })
    );
  }

  /**
   * Refresh model source and reload embedding model.
   */
  refreshAndReloadModel(): Observable<any> {
    const modelsBaseUrl = this.baseUrl.replace('/staging-config', '/models');
    return this.http.post(`${modelsBaseUrl}/registry/refresh-and-reload`, {}).pipe(
      tap(() => {
        this.modelRegistryService.notifyStagingApplied();
      })
    );
  }

  /**
   * Upload an .sdz model file to the remote staging service.
   *
   * @param modelFile The .sdz model file
   * @param vocabFile Optional vocabulary file
   * @param modelId Model identifier
   * @param modelType Model type (dense_encoder, sparse_encoder, cross_encoder)
   * @param version Optional version
   * @param embeddingDim Optional embedding dimension
   * @param maxSequenceLength Optional max sequence length
   * @param description Optional description
   * @param overwrite Whether to overwrite existing model
   */
  uploadSdzModel(
    modelFile: File,
    vocabFile: File | null,
    modelId: string,
    modelType: string,
    version: string | null,
    embeddingDim: number | null,
    maxSequenceLength: number | null,
    description: string | null,
    overwrite: boolean
  ): Observable<any> {
    const formData = new FormData();
    formData.append('modelFile', modelFile);
    if (vocabFile) {
      formData.append('vocabFile', vocabFile);
    }
    formData.append('modelId', modelId);
    formData.append('modelType', modelType);
    if (version) {
      formData.append('version', version);
    }
    if (embeddingDim) {
      formData.append('embeddingDim', embeddingDim.toString());
    }
    if (maxSequenceLength) {
      formData.append('maxSequenceLength', maxSequenceLength.toString());
    }
    if (description) {
      formData.append('description', description);
    }
    formData.append('overwrite', overwrite.toString());

    return this.http.post(`${this.baseUrl}/remote/upload-sdz`, formData).pipe(
      tap(() => {
        // Notify that a model was uploaded to staging
        this.modelRegistryService.notifyStagingApplied();
      })
    );
  }

  // ==================== Model Download & Load Operations ====================

  /**
   * Download a model from the remote staging service and start loading it.
   * This downloads the model files (.sdz, vocab.txt) to ~/.kompile/models/{modelId}/
   * and then starts loading asynchronously in the background.
   *
   * The download phase happens synchronously (returns in this request).
   * The loading phase happens asynchronously - progress updates come via WebSocket.
   *
   * @param modelId The model ID to download and load
   * @returns Observable with download status. If `loading: true`, model is loading in background.
   */
  downloadAndLoadModel(modelId: string): Observable<any> {
    const url = `${this.baseUrl}/remote/download-and-load/${modelId}`;
    // 2 minute timeout for download phase (loading happens asynchronously)
    const TIMEOUT_MS = 120000;

    return this.http.post(url, {}).pipe(
      timeout(TIMEOUT_MS),
      tap({
        next: (response: any) => {
          // Notify if model was loaded synchronously (fallback mode)
          if (response.success && response.loadSuccess) {
            this.modelRegistryService.notifyStagingApplied();
          }
          // For async loading, the WebSocket will trigger refresh when model is ready
        }
      }),
      catchError((error) => {
        if (error instanceof TimeoutError) {
          return throwError(() => ({
            error: {
              error: 'Model download timed out. Please check your network connection and try again.'
            },
            message: 'Download timeout'
          }));
        }
        return throwError(() => error);
      })
    );
  }

  /**
   * Download a model from the remote staging service without loading it.
   * Use this to pre-download models for later use.
   *
   * @param modelId The model ID to download
   */
  downloadModel(modelId: string): Observable<any> {
    return this.http.post(`${this.baseUrl}/remote/download/${modelId}`, {}).pipe(
      tap(() => {
        this.modelRegistryService.notifyStagingApplied();
      })
    );
  }

  /**
   * Check if a model is already downloaded locally.
   *
   * @param modelId The model ID to check
   */
  isModelDownloaded(modelId: string): Observable<any> {
    return this.http.get(`${this.baseUrl}/remote/downloaded/${modelId}`);
  }

  // ==================== Local Registry Operations ====================

  /**
   * Get the full local model registry.
   */
  getLocalRegistry(): Observable<LocalModelRegistry> {
    return this.http.get<LocalModelRegistry>(`${this.baseUrl}/registry`);
  }

  /**
   * Get models by type from the local registry.
   */
  getLocalModelsByType(type: string): Observable<LocalModelEntry[]> {
    return this.http.get<LocalModelEntry[]>(`${this.baseUrl}/registry/${type}`);
  }

  // ==================== Graph Optimization Operations ====================

  /**
   * Get optimization status for a model.
   * Returns whether the model is optimized and if a backup exists.
   *
   * @param modelId The model ID to check
   */
  getOptimizationStatus(modelId: string): Observable<OptimizationStatus> {
    return this.http.get<OptimizationStatus>(`${this.baseUrl}/registry/model/${modelId}/optimization`);
  }

  /**
   * Restore a model to its unoptimized state.
   * This restores the backup of the original unoptimized model file.
   *
   * @param modelId The model ID to restore
   */
  restoreUnoptimized(modelId: string): Observable<RestoreResult> {
    return this.http.post<RestoreResult>(`${this.baseUrl}/registry/model/${modelId}/restore`, {}).pipe(
      tap(() => {
        this.modelRegistryService.notifyStagingApplied();
      })
    );
  }

  /**
   * Check if a model can be optimized.
   * Returns preparation info without actually starting optimization.
   *
   * @param modelId The model ID to check
   */
  canOptimize(modelId: string): Observable<CanOptimizeResult> {
    return this.http.get<CanOptimizeResult>(`${this.baseUrl}/registry/model/${modelId}/can-optimize`);
  }

  /**
   * Optimize a model in-place.
   * This creates a backup of the original and saves the optimized version.
   *
   * @param modelId The model ID to optimize
   */
  optimizeModel(modelId: string): Observable<OptimizeResult> {
    return this.http.post<OptimizeResult>(`${this.baseUrl}/registry/model/${modelId}/optimize`, {}).pipe(
      tap(() => {
        this.modelRegistryService.notifyStagingApplied();
      })
    );
  }
}

// ==================== Graph Optimization Types ====================

export interface OptimizationStatus {
  modelId: string;
  optimized: boolean;
  optimizedAt?: string;
  optimizationTimeMs?: number;
  hasBackup: boolean;
  backupFile?: string;
}

export interface RestoreResult {
  success: boolean;
  modelId: string;
  message: string;
  error?: string;
}

export interface CanOptimizeResult {
  modelId: string;
  canOptimize: boolean;
  reason?: string;
  modelFile?: string;
  hasBackup?: boolean;
}

export interface OptimizeResult {
  success: boolean;
  modelId: string;
  message?: string;
  optimizationTimeMs?: number;
  backupFile?: string;
  error?: string;
}

// ==================== Local Registry Types ====================

export interface LocalModelRegistry {
  version?: string;
  lastUpdated?: string;
  models: LocalModelEntry[];
}

export interface LocalModelEntry {
  modelId: string;
  type: string;
  status: string;
  path?: string;
  modelFile?: string;
  vocabFile?: string;
  checksum?: string;
  promotedAt?: string;
  installedAt?: string;
  metadata?: LocalModelMetadata;
  tokenizer?: any;
}

export interface LocalModelMetadata {
  embeddingDim?: number;
  hiddenSize?: number;
  maxSequenceLength?: number;
  modelType?: string;
  encoderType?: string;
  ragRole?: string;
  framework?: string;
  description?: string;
  vocabSize?: number;
  optimized?: boolean;
  optimizedAt?: string;
  optimizationTimeMs?: number;
  unoptimizedBackupFile?: string;
}
