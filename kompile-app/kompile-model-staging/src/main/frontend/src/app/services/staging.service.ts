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
import { HttpClient, HttpErrorResponse, HttpEvent, HttpEventType, HttpRequest } from '@angular/common/http';
import { Observable, throwError, timer, Subject } from 'rxjs';
import { catchError, switchMap, retry, map, tap } from 'rxjs/operators';
import {
  ModelRegistry,
  ModelCatalog,
  StagingStatusResponse,
  StagingModelInfo,
  StageModelRequest,
  PromoteModelRequest,
  ExportRequest,
  ImportRequest,
  ExportResult,
  ImportResult,
  ApiResponse
} from '../models/api-models';

export interface RestoreResult {
  modelId: string;
  success: boolean;
  message?: string;
  restoredPath?: string;
  error?: string;
}

@Injectable({
  providedIn: 'root'
})
export class StagingService {

  private baseUrl: string;

  constructor(private http: HttpClient) {
    // Dynamic API URL based on current location
    if (typeof window !== 'undefined' && window.location) {
      const protocol = window.location.protocol;
      const hostname = window.location.hostname;
      const port = window.location.port;
      this.baseUrl = `${protocol}//${hostname}${port ? ':' + port : ''}/api/staging`;
    } else {
      this.baseUrl = '/api/staging';
    }
  }

  // ==================== Registry Operations ====================

  /**
   * Get the full model registry.
   */
  getRegistry(): Observable<ModelRegistry> {
    return this.http.get<ModelRegistry>(`${this.baseUrl}/registry`)
      .pipe(catchError(this.handleError));
  }

  /**
   * Get models by type from registry.
   */
  getModelsByType(type: string): Observable<any[]> {
    return this.http.get<any[]>(`${this.baseUrl}/registry/${type}`)
      .pipe(catchError(this.handleError));
  }

  /**
   * Get a specific model from the registry.
   */
  getModel(modelId: string): Observable<any> {
    return this.http.get<any>(`${this.baseUrl}/registry/model/${modelId}`)
      .pipe(catchError(this.handleError));
  }

  /**
   * Update a model in the registry.
   */
  updateModel(modelId: string, updates: {
    type?: string;
    status?: string;
    metadata?: {
      description?: string;
      embeddingDim?: number;
      hiddenSize?: number;
      numLayers?: number;
      maxSequenceLength?: number;
      framework?: string;
      trainingData?: string;
      sourceOrigin?: string;
      sourceRepository?: string;
      vocabSize?: number;
      visionEncoderPixelValuesName?: string;
      visionEncoderPixelAttentionMaskName?: string;
      visionEncoderPrimaryOutputName?: string;
      visionEncoderOutputNames?: string[];
    };
    tokenizer?: {
      doLowerCase?: boolean;
      addSpecialTokens?: boolean;
      stripAccents?: boolean;
      maxLength?: number;
      padding?: string;
      truncation?: boolean;
    };
    preprocessor?: {
      imageProcessorType?: string;
      doResize?: boolean;
      sizeHeight?: number;
      sizeWidth?: number;
      sizeShortestEdge?: number;
      sizeLongestEdge?: number;
      resample?: number;
      doRescale?: boolean;
      rescaleFactor?: number;
      doNormalize?: boolean;
      imageMean?: number[];
      imageStd?: number[];
      doConvertRgb?: boolean;
      doCenterCrop?: boolean;
      cropSizeHeight?: number;
      cropSizeWidth?: number;
      doPad?: boolean;
      padSizeHeight?: number;
      padSizeWidth?: number;
      patchSize?: number;
      numChannels?: number;
    };
  }): Observable<ApiResponse<any>> {
    return this.http.put<ApiResponse<any>>(`${this.baseUrl}/registry/model/${modelId}`, updates)
      .pipe(catchError(this.handleError));
  }

  /**
   * Re-probe a VLM model's vision encoder IO config from the SameDiff graph.
   */
  probeVisionEncoderIO(modelId: string): Observable<ApiResponse<any>> {
    return this.http.post<ApiResponse<any>>(`${this.baseUrl}/registry/model/${modelId}/probe-vision-io`, {})
      .pipe(catchError(this.handleError));
  }

  /**
   * Delete a model from the registry.
   */
  deleteModel(modelId: string): Observable<ApiResponse<void>> {
    return this.http.delete<ApiResponse<void>>(`${this.baseUrl}/registry/model/${modelId}`)
      .pipe(catchError(this.handleError));
  }

  // ==================== Catalog Operations ====================

  /**
   * Get the model catalog (available models for download).
   */
  getCatalog(): Observable<ModelCatalog> {
    return this.http.get<ModelCatalog>(`${this.baseUrl}/catalog`)
      .pipe(catchError(this.handleError));
  }

  // ==================== Staging Operations ====================

  /**
   * Get staging status.
   */
  getStagingStatus(): Observable<StagingStatusResponse> {
    return this.http.get<StagingStatusResponse>(`${this.baseUrl}/status`)
      .pipe(catchError(this.handleError));
  }

  /**
   * Get models currently in staging.
   */
  getModelsInStaging(): Observable<StagingModelInfo[]> {
    return this.http.get<StagingModelInfo[]>(`${this.baseUrl}/models`)
      .pipe(catchError(this.handleError));
  }

  /**
   * Stage a model (download and convert).
   */
  stageModel(request: StageModelRequest): Observable<ApiResponse<StagingModelInfo>> {
    return this.http.post<ApiResponse<StagingModelInfo>>(`${this.baseUrl}/stage`, request)
      .pipe(catchError(this.handleError));
  }

  /**
   * Stage a model from the catalog by ID.
   */
  stageFromCatalog(modelId: string, autoPromote: boolean = false): Observable<StagingModelInfo> {
    return this.http.post<StagingModelInfo>(
      `${this.baseUrl}/stage/catalog/${modelId}?autoPromote=${autoPromote}`,
      {}
    ).pipe(catchError(this.handleError));
  }

  /**
   * Stage a custom model from a URL or repository.
   * This supports HuggingFace, GitHub, HTTP/HTTPS, and S3 sources.
   */
  stageCustomModel(options: {
    modelId: string;
    source: 'huggingface' | 'github' | 'http' | 's3';
    repository?: string;
    url?: string;
    modelType?: 'dense_encoder' | 'sparse_encoder' | 'cross_encoder';
    format?: 'onnx' | 'pytorch' | 'tensorflow' | 'samediff';
    autoPromote?: boolean;
  }): Observable<ApiResponse<StagingModelInfo>> {
    const request: StageModelRequest = {
      modelId: options.modelId,
      source: options.source,
      repository: options.repository || options.url,
      format: options.format,
      autoPromote: options.autoPromote ?? false
    };
    return this.http.post<ApiResponse<StagingModelInfo>>(`${this.baseUrl}/stage`, request)
      .pipe(catchError(this.handleError));
  }

  /**
   * Get status of a specific staged model.
   */
  getStagedModelStatus(modelId: string): Observable<StagingModelInfo> {
    return this.http.get<StagingModelInfo>(`${this.baseUrl}/models/${modelId}`)
      .pipe(catchError(this.handleError));
  }

  /**
   * Cancel staging of a model.
   */
  cancelStaging(modelId: string): Observable<ApiResponse<void>> {
    return this.http.delete<ApiResponse<void>>(`${this.baseUrl}/models/${modelId}`)
      .pipe(catchError(this.handleError));
  }

  // ==================== Promotion Operations ====================

  /**
   * Promote a staged model to the registry.
   */
  promoteModel(request: PromoteModelRequest): Observable<ApiResponse<void>> {
    return this.http.post<ApiResponse<void>>(`${this.baseUrl}/promote`, request)
      .pipe(catchError(this.handleError));
  }

  /**
   * Promote a staged model by ID.
   */
  promoteModelById(modelId: string): Observable<ApiResponse<void>> {
    return this.http.post<ApiResponse<void>>(`${this.baseUrl}/promote/${modelId}`, {})
      .pipe(catchError(this.handleError));
  }

  // ==================== Export/Import Operations ====================

  /**
   * Export models to a bundle.
   */
  exportModels(request: ExportRequest): Observable<ExportResult> {
    return this.http.post<ExportResult>(`${this.baseUrl}/export`, request)
      .pipe(catchError(this.handleError));
  }

  /**
   * Import models from a bundle.
   */
  importModels(request: ImportRequest): Observable<ImportResult> {
    return this.http.post<ImportResult>(`${this.baseUrl}/import`, request)
      .pipe(catchError(this.handleError));
  }

  // ==================== Conversion Operations ====================

  /**
   * Convert a local model file to SameDiff format.
   * Supports ONNX, TensorFlow (.pb), and Keras (.h5, .keras) formats.
   */
  convertModel(options: {
    inputPath: string;
    format: 'onnx' | 'tensorflow' | 'keras';
    modelId: string;
    modelType?: 'dense_encoder' | 'sparse_encoder' | 'cross_encoder';
    outputPath?: string;
    autoStage?: boolean;
    autoPromote?: boolean;
    metadata?: {
      description?: string;
      embeddingDim?: number;
      maxSequenceLength?: number;
    };
  }): Observable<ApiResponse<StagingModelInfo>> {
    const request = {
      inputPath: options.inputPath,
      format: options.format,
      modelId: options.modelId,
      type: options.modelType || 'dense_encoder',
      outputPath: options.outputPath,
      autoStage: options.autoStage ?? true,
      autoPromote: options.autoPromote ?? false,
      metadata: options.metadata
    };
    return this.http.post<ApiResponse<StagingModelInfo>>(`${this.baseUrl}/convert`, request)
      .pipe(catchError(this.handleError));
  }

  /**
   * Validate a converted SameDiff model.
   */
  validateModel(modelPath: string): Observable<ApiResponse<{
    isValid: boolean;
    errorMessage?: string;
    numOperations: number;
    numVariables: number;
  }>> {
    return this.http.post<ApiResponse<any>>(`${this.baseUrl}/validate`, { modelPath })
      .pipe(catchError(this.handleError));
  }

  /**
   * Upload a model file for conversion.
   * Returns the server-side path where the file was uploaded.
   */
  uploadModelFile(file: File, progressCallback?: (progress: number) => void): Observable<{ filePath: string }> {
    const formData = new FormData();
    formData.append('file', file, file.name);

    const req = new HttpRequest('POST', `${this.baseUrl}/upload`, formData, {
      reportProgress: true
    });

    return new Observable(observer => {
      this.http.request(req).subscribe({
        next: (event: HttpEvent<any>) => {
          if (event.type === HttpEventType.UploadProgress) {
            if (progressCallback && event.total) {
              const progress = Math.round(100 * event.loaded / event.total);
              progressCallback(progress);
            }
          } else if (event.type === HttpEventType.Response) {
            observer.next(event.body);
            observer.complete();
          }
        },
        error: (error) => {
          observer.error(error);
        }
      });
    });
  }

  /**
   * Get the conversion status of a model.
   */
  getConversionStatus(jobId: string): Observable<ApiResponse<{
    jobId: string;
    status: string;
    progress: number;
    message?: string;
    outputPath?: string;
    error?: string;
  }>> {
    return this.http.get<ApiResponse<any>>(`${this.baseUrl}/convert/status/${jobId}`)
      .pipe(catchError(this.handleError));
  }

  // ==================== Directory Operations ====================

  /**
   * List contents of the models directory.
   * @param path Optional subdirectory path (relative to models root)
   */
  listModelsDirectory(path?: string): Observable<{
    currentPath: string;
    entries: Array<{
      name: string;
      isDirectory: boolean;
      size?: number;
      lastModified?: string;
    }>;
  }> {
    const params = path ? `?path=${encodeURIComponent(path)}` : '';
    return this.http.get<any>(`${this.baseUrl}/files/list${params}`)
      .pipe(catchError(this.handleError));
  }

  /**
   * List directory contents (alias for listModelsDirectory with path mapping).
   * Returns path and entries for directory browser UI.
   */
  listDirectory(path: string = ''): Observable<{
    path: string;
    entries: Array<{
      name: string;
      path: string;
      isDirectory: boolean;
      size?: number;
      modifiedAt?: string;
    }>;
  }> {
    const params = path ? `?path=${encodeURIComponent(path)}` : '';
    return this.http.get<any>(`${this.baseUrl}/files/list${params}`)
      .pipe(
        map(response => ({
          path: response.currentPath || path,
          entries: (response.entries || []).map((entry: any) => ({
            name: entry.name,
            path: path ? `${path}/${entry.name}` : entry.name,
            isDirectory: entry.isDirectory,
            size: entry.size,
            modifiedAt: entry.lastModified
          }))
        })),
        catchError(this.handleError)
      );
  }

  /**
   * List available archives in the archives directory.
   */
  listArchives(): Observable<Array<{
    name: string;
    size: number;
    lastModified: string;
    modelCount?: number;
  }>> {
    return this.http.get<any[]>(`${this.baseUrl}/archives`)
      .pipe(catchError(this.handleError));
  }

  // ==================== Model Source Configuration ====================

  /**
   * Get the current model source configuration.
   */
  getModelSourceConfig(): Observable<any> {
    return this.http.get<any>(`${this.baseUrl}/config/source`)
      .pipe(catchError(this.handleError));
  }

  /**
   * Get archive status.
   */
  getArchiveStatus(): Observable<any> {
    return this.http.get<any>(`${this.baseUrl}/config/archive/status`)
      .pipe(catchError(this.handleError));
  }

  /**
   * Get models available from the loaded archive.
   */
  getArchiveModels(): Observable<any[]> {
    return this.http.get<any[]>(`${this.baseUrl}/config/archive/models`)
      .pipe(catchError(this.handleError));
  }

  /**
   * Load an archive from a file path.
   */
  loadArchive(archivePath: string): Observable<any> {
    return this.http.post<any>(`${this.baseUrl}/config/archive/load`, { archivePath })
      .pipe(catchError(this.handleError));
  }

  // ==================== Auto-Optimization Configuration ====================

  /**
   * Get the current auto-optimization configuration.
   */
  getAutoOptimizeConfig(): Observable<any> {
    return this.http.get<any>(`${this.baseUrl}/config/auto-optimize`)
      .pipe(catchError(this.handleError));
  }

  /**
   * Set the auto-optimization configuration.
   * When set, newly staged models will be automatically optimized.
   */
  setAutoOptimizeConfig(config: any): Observable<any> {
    return this.http.post<any>(`${this.baseUrl}/config/auto-optimize`, config)
      .pipe(catchError(this.handleError));
  }

  /**
   * Clear the auto-optimization configuration.
   */
  clearAutoOptimizeConfig(): Observable<any> {
    return this.http.delete<any>(`${this.baseUrl}/config/auto-optimize`)
      .pipe(catchError(this.handleError));
  }

  /**
   * Stage a model from catalog with optimization configuration.
   */
  stageFromCatalogWithOptimization(modelId: string, request: any): Observable<StagingModelInfo> {
    return this.http.post<StagingModelInfo>(
      `${this.baseUrl}/stage/catalog/${modelId}/with-optimization`,
      request
    ).pipe(catchError(this.handleError));
  }

  // ==================== Optimization Operations ====================

  /**
   * Get available optimization types.
   */
  getAvailableOptimizations(): Observable<any> {
    return this.http.get<any>(`${this.baseUrl}/optimizations`)
      .pipe(catchError(this.handleError));
  }

  /**
   * Get details for a specific optimization type.
   */
  getOptimizationDetails(optimizationId: string): Observable<any> {
    return this.http.get<any>(`${this.baseUrl}/optimizations/${optimizationId}`)
      .pipe(catchError(this.handleError));
  }

  /**
   * Optimize a model with configurable optimization options.
   * Hits the compiler controller at /api/compiler/optimize.
   */
  optimizeModelConfigurable(modelId: string, request: any): Observable<any> {
    // Build the compiler base URL (same host, /api/compiler)
    let compilerUrl: string;
    if (typeof window !== 'undefined' && window.location) {
      const protocol = window.location.protocol;
      const hostname = window.location.hostname;
      const port = window.location.port;
      compilerUrl = `${protocol}//${hostname}${port ? ':' + port : ''}/api/compiler`;
    } else {
      compilerUrl = '/api/compiler';
    }

    const compilerRequest = {
      modelId: modelId,
      selectedPasses: request.enabledOptimizations || [],
      profile: request.preset || 'FULL',
      maxIterations: request.maxIterations || 3,
      createBackup: request.createBackup !== false,
      force: request.force || false,
      quantizationType: request.quantizationType
    };

    return this.http.post<any>(`${compilerUrl}/optimize`, compilerRequest)
      .pipe(catchError(this.handleError));
  }

  /**
   * Restore an optimized model to its original unoptimized state.
   */
  restoreUnoptimized(modelId: string): Observable<any> {
    return this.http.post<any>(`${this.baseUrl}/restore/${modelId}`, {})
      .pipe(catchError(this.handleError));
  }

  /**
   * Compare optimized vs original model.
   */
  compareModels(modelId: string, request?: any): Observable<any> {
    return this.http.post<any>(`${this.baseUrl}/compare/${encodeURIComponent(modelId)}`, request || {})
      .pipe(catchError(this.handleError));
  }

  // ==================== Polling Utilities ====================

  /**
   * Poll staging status at regular intervals.
   */
  pollStagingStatus(intervalMs: number = 2000): Observable<StagingStatusResponse> {
    return timer(0, intervalMs).pipe(
      switchMap(() => this.getStagingStatus())
    );
  }

  /**
   * Poll a specific model's staging status.
   */
  pollModelStatus(modelId: string, intervalMs: number = 1000): Observable<StagingModelInfo> {
    return timer(0, intervalMs).pipe(
      switchMap(() => this.getStagedModelStatus(modelId))
    );
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

    console.error('StagingService error:', errorMessage);
    return throwError(() => new Error(errorMessage));
  }
}
