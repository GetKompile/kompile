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
import { map, catchError } from 'rxjs/operators';
import { environment } from '../../environments/environment';
import {
  VlmTestStartResponse,
  VlmTestStatusResponse,
  VlmTestResultResponse,
  VlmTestSubprocessConfig,
  VlmTestSubprocessConfigUpdate
} from '../models/api-models';

@Injectable({
  providedIn: 'root'
})
export class VlmTestService {
  private readonly baseUrl: string;
  private readonly stagingConfigUrl: string;

  constructor(private http: HttpClient) {
    this.baseUrl = environment.apiUrl;
    // Use the staging-config proxy endpoint on the main app - this calls the configured staging service
    if (typeof window !== 'undefined' && window.location) {
      const protocol = window.location.protocol;
      const hostname = window.location.hostname;
      const port = window.location.port;
      this.stagingConfigUrl = `${protocol}//${hostname}${port ? ':' + port : ''}/api/staging-config`;
    } else {
      this.stagingConfigUrl = '/api/staging-config';
    }
  }

  /**
   * Get available VLM models from the remote staging registry.
   * Calls /api/staging-config/remote/registry and filters for vlm_pipeline type.
   */
  getAvailableModels(): Observable<any[]> {
    return this.http.get<any>(`${this.stagingConfigUrl}/remote/registry`).pipe(
      map(registry => {
        if (!registry?.models) return [];
        return Object.entries(registry.models)
          .filter(([_, model]: [string, any]) => model.type === 'vlm_pipeline')
          .map(([id, model]: [string, any]) => ({
            modelId: id,
            id: id,
            name: model.model_id || id,
            type: model.type,
            status: model.status,
            path: model.path,
            framework: model.metadata?.framework
          }));
      }),
      catchError(() => of([]))
    );
  }

  runTest(file: File, modelId: string, outputFormat: string,
          options?: { maxNewTokens?: number; temperature?: number; topP?: number; pdfRenderDpi?: number; pageBatchSize?: number }
  ): Observable<VlmTestStartResponse> {
    const formData = new FormData();
    formData.append('file', file);
    formData.append('modelId', modelId);
    formData.append('outputFormat', outputFormat);
    if (options?.maxNewTokens) formData.append('maxNewTokens', String(options.maxNewTokens));
    if (options?.temperature) formData.append('temperature', String(options.temperature));
    if (options?.topP) formData.append('topP', String(options.topP));
    if (options?.pdfRenderDpi) formData.append('pdfRenderDpi', String(options.pdfRenderDpi));
    if (options?.pageBatchSize) formData.append('pageBatchSize', String(options.pageBatchSize));

    return this.http.post<VlmTestStartResponse>(`${this.baseUrl}/vlm/test/run`, formData);
  }

  getStatus(taskId: string): Observable<VlmTestStatusResponse> {
    return this.http.get<VlmTestStatusResponse>(`${this.baseUrl}/vlm/test/status/${taskId}`);
  }

  getResults(taskId: string): Observable<VlmTestResultResponse> {
    return this.http.get<VlmTestResultResponse>(`${this.baseUrl}/vlm/test/results/${taskId}`);
  }

  cancelTest(taskId: string): Observable<any> {
    return this.http.delete(`${this.baseUrl}/vlm/test/cancel/${taskId}`);
  }

  getConfig(): Observable<VlmTestSubprocessConfig> {
    return this.http.get<VlmTestSubprocessConfig>(`${this.baseUrl}/vlm/test/config`);
  }

  updateConfig(update: VlmTestSubprocessConfigUpdate): Observable<VlmTestSubprocessConfig> {
    return this.http.post<VlmTestSubprocessConfig>(`${this.baseUrl}/vlm/test/config`, update);
  }
}
