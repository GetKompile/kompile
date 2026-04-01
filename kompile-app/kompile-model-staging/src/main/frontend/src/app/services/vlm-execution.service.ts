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
  VlmGenerateRequest,
  VlmGenerateResponse,
  OcrRecognizeResponse,
  OcrConfigRequest,
  DocTagsParseRequest,
  DocTagsParseResponse,
  TilingPreviewResponse
} from '../models/api-models';

/**
 * Response from loading/unloading a model.
 */
export interface ModelActionResponse {
  success: boolean;
  error?: string;
  modelSetId?: string;
  message?: string;
}

/**
 * Execution status response.
 */
export interface ExecutionStatusResponse {
  status: string;
  activeModelId: string | null;
  loadedModels: string[];
}

/**
 * Available VLM model entry from execution endpoint.
 */
export interface VlmExecutionModel {
  id: string;
  name: string;
  type: string;
}

/**
 * OCR engine entry.
 */
export interface OcrEngine {
  type: string;
  name: string;
  description: string;
}

/**
 * DocTags to Markdown/HTML conversion response.
 */
export interface DocTagsConvertResponse {
  success: boolean;
  markdown?: string;
  html?: string;
  error?: string;
}

@Injectable({
  providedIn: 'root'
})
export class VlmExecutionService {

  private baseUrl: string;

  constructor(private http: HttpClient) {
    if (typeof window !== 'undefined' && window.location) {
      const protocol = window.location.protocol;
      const hostname = window.location.hostname;
      const port = window.location.port;
      this.baseUrl = `${protocol}//${hostname}${port ? ':' + port : ''}/api/vlm`;
    } else {
      this.baseUrl = '/api/vlm';
    }
  }

  // ==================== Model Management ====================

  /**
   * Get available VLM models for execution.
   */
  getModels(): Observable<VlmExecutionModel[]> {
    return this.http.get<VlmExecutionModel[]>(`${this.baseUrl}/models`)
      .pipe(catchError(this.handleError));
  }

  /**
   * Load a VLM model for execution.
   */
  loadModel(modelSetId: string): Observable<ModelActionResponse> {
    return this.http.post<ModelActionResponse>(`${this.baseUrl}/load`, { modelSetId })
      .pipe(catchError(this.handleError));
  }

  /**
   * Unload the current VLM model.
   */
  unloadModel(): Observable<ModelActionResponse> {
    return this.http.delete<ModelActionResponse>(`${this.baseUrl}/unload`)
      .pipe(catchError(this.handleError));
  }

  /**
   * Get model execution status.
   */
  getStatus(): Observable<ExecutionStatusResponse> {
    return this.http.get<ExecutionStatusResponse>(`${this.baseUrl}/execution/status`)
      .pipe(catchError(this.handleError));
  }

  // ==================== Generation ====================

  /**
   * Generate text from an image using a loaded VLM model.
   */
  generate(image: File, request: VlmGenerateRequest): Observable<VlmGenerateResponse> {
    const formData = new FormData();
    formData.append('image', image);
    formData.append('request', JSON.stringify(request));
    return this.http.post<VlmGenerateResponse>(`${this.baseUrl}/generate`, formData)
      .pipe(catchError(this.handleError));
  }

  // ==================== OCR ====================

  /**
   * Perform OCR recognition on an image.
   */
  ocrRecognize(image: File): Observable<OcrRecognizeResponse> {
    const formData = new FormData();
    formData.append('image', image);
    return this.http.post<OcrRecognizeResponse>(`${this.baseUrl}/ocr/recognize`, formData)
      .pipe(catchError(this.handleError));
  }

  /**
   * Get available OCR engines.
   */
  getOcrEngines(): Observable<OcrEngine[]> {
    return this.http.get<OcrEngine[]>(`${this.baseUrl}/ocr/engines`)
      .pipe(catchError(this.handleError));
  }

  /**
   * Get current OCR configuration.
   */
  getOcrConfig(): Observable<any> {
    return this.http.get<any>(`${this.baseUrl}/ocr/config`)
      .pipe(catchError(this.handleError));
  }

  /**
   * Update OCR configuration.
   */
  updateOcrConfig(config: OcrConfigRequest): Observable<any> {
    return this.http.put<any>(`${this.baseUrl}/ocr/config`, config)
      .pipe(catchError(this.handleError));
  }

  // ==================== DocTags ====================

  /**
   * Parse raw DocTags to structured document.
   */
  parseDocTags(rawDocTags: string): Observable<DocTagsParseResponse> {
    const request: DocTagsParseRequest = { rawDocTags };
    return this.http.post<DocTagsParseResponse>(`${this.baseUrl}/doctags/parse`, request)
      .pipe(catchError(this.handleError));
  }

  /**
   * Convert DocTags to Markdown.
   */
  docTagsToMarkdown(rawDocTags: string): Observable<DocTagsConvertResponse> {
    const request: DocTagsParseRequest = { rawDocTags };
    return this.http.post<DocTagsConvertResponse>(`${this.baseUrl}/doctags/to-markdown`, request)
      .pipe(catchError(this.handleError));
  }

  /**
   * Convert DocTags to HTML.
   */
  docTagsToHtml(rawDocTags: string): Observable<DocTagsConvertResponse> {
    const request: DocTagsParseRequest = { rawDocTags };
    return this.http.post<DocTagsConvertResponse>(`${this.baseUrl}/doctags/to-html`, request)
      .pipe(catchError(this.handleError));
  }

  // ==================== Tiling ====================

  /**
   * Preview image tiling with given parameters.
   */
  tilingPreview(image: File, maxTiles: number): Observable<TilingPreviewResponse> {
    const formData = new FormData();
    formData.append('image', image);
    return this.http.post<TilingPreviewResponse>(
      `${this.baseUrl}/tiling/preview?maxTiles=${maxTiles}`, formData
    ).pipe(catchError(this.handleError));
  }

  // ==================== Error Handling ====================

  private handleError(error: HttpErrorResponse): Observable<never> {
    let errorMessage = 'An error occurred';

    if (error.error instanceof ErrorEvent) {
      errorMessage = `Error: ${error.error.message}`;
    } else {
      if (error.error?.message) {
        errorMessage = error.error.message;
      } else if (error.error?.error) {
        errorMessage = error.error.error;
      } else {
        errorMessage = `Error ${error.status}: ${error.message}`;
      }
    }

    console.error('VlmExecutionService error:', errorMessage);
    return throwError(() => new Error(errorMessage));
  }
}
