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
import { Observable, of } from 'rxjs';
import { map } from 'rxjs/operators';
import { environment } from '../../environments/environment';
import {
  OcrDebugStatus,
  RegistryModelInfo,
  PipelineTrace,
  VlmStatusResponse,
  PageRenderResult,
  VlmOutputFormat,
  OcrStatus,
  OcrConfig,
  OcrModelInfo
} from '../models/ocr-models';

@Injectable({
  providedIn: 'root'
})
export class OcrService {
  private readonly baseUrl: string;

  constructor(private http: HttpClient) {
    this.baseUrl = environment.apiUrl;
  }

  getDebugStatus(): Observable<OcrDebugStatus> {
    return this.http.get<OcrDebugStatus>(`${this.baseUrl}/ocr/debug/status`);
  }

  /**
   * Backward-compatible getStatus() used by chunking-loader-test and document-debugger.
   * Maps the new OcrDebugStatus response to the old OcrStatus shape.
   */
  getStatus(): Observable<OcrStatus> {
    return this.getDebugStatus().pipe(
      map(s => ({
        ocrEnabled: s.stagingReachable,
        pipelineReady: s.vlmLoaded,
        postProcessorAvailable: false,
        stagingReachable: s.stagingReachable,
        vlmLoaded: s.vlmLoaded,
        vlmModelId: s.vlmModelId,
        registryModelCount: s.registryModelCount
      }))
    );
  }

  /**
   * Backward-compatible getConfig(). Returns empty config since the debug controller
   * no longer manages pipeline configuration.
   */
  getConfig(): Observable<OcrConfig> {
    return of({});
  }

  getModels(): Observable<OcrModelInfo[]> {
    return this.getRegistryModels().pipe(
      map(models => models.map(m => ({
        modelId: m.modelId,
        type: m.type,
        description: m.description,
        status: m.status,
        isLoaded: m.status === 'ACTIVE'
      })))
    );
  }

  getRegistryModels(): Observable<RegistryModelInfo[]> {
    return this.http.get<RegistryModelInfo[]>(`${this.baseUrl}/ocr/debug/models`);
  }

  loadVlm(modelId: string): Observable<any> {
    return this.http.post(`${this.baseUrl}/ocr/debug/load-vlm`, { modelId });
  }

  unloadVlm(): Observable<any> {
    return this.http.post(`${this.baseUrl}/ocr/debug/unload-vlm`, {});
  }

  getVlmStatus(): Observable<VlmStatusResponse> {
    return this.http.get<VlmStatusResponse>(`${this.baseUrl}/ocr/debug/vlm-status`);
  }

  testOcr(
    file: File,
    modelId?: string,
    maxTokens: number = 4096,
    outputFormat: VlmOutputFormat = 'DOCTAGS',
    page?: number
  ): Observable<PipelineTrace> {
    const formData = new FormData();
    formData.append('file', file);

    const params: any = {
      maxTokens: maxTokens.toString(),
      outputFormat
    };
    if (modelId) {
      params.modelId = modelId;
    }
    if (page !== undefined && page > 0) {
      params.page = page.toString();
    }

    return this.http.post<PipelineTrace>(
      `${this.baseUrl}/ocr/debug/test`,
      formData,
      { params }
    );
  }

  renderPdfPage(file: File, page: number = 1, dpi: number = 150): Observable<PageRenderResult> {
    const formData = new FormData();
    formData.append('file', file);

    return this.http.post<PageRenderResult>(
      `${this.baseUrl}/ocr/debug/render-page`,
      formData,
      { params: { page: page.toString(), dpi: dpi.toString() } }
    );
  }
}
