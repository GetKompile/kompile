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
import { Observable } from 'rxjs';
import { backendUrl } from './base.service';
import {
  BatchSizeTestRequest,
  BatchSizeTestResponse,
  BatchSizeConfigRequest,
  BatchSizeConfigResponse,
  EmbeddingModelInfo,
  BatchConfigStatus
} from '../models/batch-config.models';

/**
 * Service for embedding batch size configuration and testing.
 */
@Injectable({
  providedIn: 'root'
})
export class BatchConfigService {
  private readonly baseUrl = `${backendUrl}/embeddings/batch-config`;

  constructor(private http: HttpClient) {}

  /**
   * Gets the status of the batch config service.
   */
  getStatus(): Observable<BatchConfigStatus> {
    return this.http.get<BatchConfigStatus>(`${this.baseUrl}/status`);
  }

  /**
   * Gets all available embedding models with their configurations.
   */
  getAvailableModels(): Observable<EmbeddingModelInfo[]> {
    return this.http.get<EmbeddingModelInfo[]>(`${this.baseUrl}/models`);
  }

  /**
   * Gets batch size configuration for a specific model.
   */
  getModelConfig(modelId: string): Observable<BatchSizeConfigResponse> {
    return this.http.get<BatchSizeConfigResponse>(`${this.baseUrl}/models/${encodeURIComponent(modelId)}`);
  }

  /**
   * Gets global batch size configuration.
   */
  getGlobalConfig(): Observable<BatchSizeConfigResponse> {
    return this.http.get<BatchSizeConfigResponse>(`${this.baseUrl}/global`);
  }

  /**
   * Updates batch size configuration for a specific model.
   */
  updateModelConfig(modelId: string, config: BatchSizeConfigRequest): Observable<BatchSizeConfigResponse> {
    return this.http.put<BatchSizeConfigResponse>(
      `${this.baseUrl}/models/${encodeURIComponent(modelId)}`,
      config
    );
  }

  /**
   * Updates global batch size configuration.
   */
  updateGlobalConfig(config: BatchSizeConfigRequest): Observable<BatchSizeConfigResponse> {
    return this.http.put<BatchSizeConfigResponse>(`${this.baseUrl}/global`, config);
  }

  /**
   * Runs a batch size benchmark test.
   */
  runBatchSizeTest(request: BatchSizeTestRequest): Observable<BatchSizeTestResponse> {
    return this.http.post<BatchSizeTestResponse>(`${this.baseUrl}/test`, request);
  }

  /**
   * Resets batch size configuration to defaults for a model.
   */
  resetModelConfig(modelId: string): Observable<BatchSizeConfigResponse> {
    return this.http.post<BatchSizeConfigResponse>(
      `${this.baseUrl}/models/${encodeURIComponent(modelId)}/reset`,
      {}
    );
  }

  /**
   * Resets global batch size configuration to defaults.
   */
  resetGlobalConfig(): Observable<BatchSizeConfigResponse> {
    return this.http.post<BatchSizeConfigResponse>(`${this.baseUrl}/global/reset`, {});
  }

  /**
   * Creates a default test request for benchmarking.
   */
  createDefaultTestRequest(modelId?: string): BatchSizeTestRequest {
    return {
      modelId: modelId,
      batchSizesToTest: [1, 2, 4, 8, 16, 32],
      iterations: 3,
      warmupIterations: 1,
      timeoutSeconds: 30
    };
  }
}
