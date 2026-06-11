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

export interface ModelAdmissionConfig {
  enabled: boolean;
  max_loaded_models: number;
  max_concurrent_loads: number;
  memory_reserve_bytes: number;
  background_load_threads: number;
  warmup_after_load: boolean;
  default_memory_estimate_bytes: number;
}

export interface AdmittedModel {
  modelId: string;
  state: 'LOADING' | 'GPU_HOT' | 'CPU_WARM' | 'UNLOADED' | 'REJECTED';
  device: string;
  memoryBytes: number;
  memoryMB: number;
  loadedAt: string;
  lastUsedAt: string;
}

export interface ModelAdmissionStatus {
  enabled: boolean;
  maxLoadedModels: number;
  maxConcurrentLoads: number;
  memoryReserveBytes: number;
  activeLoads: number;
  models: AdmittedModel[];
  totalGpuMemoryUsedBytes: number;
  totalModelsLoaded: number;
  stateCounts: Record<string, number>;
}

export interface AdmissionCheckResult {
  admitted: boolean;
  reason: string;
  estimatedMemoryBytes: number;
  modelsToEvict: string[];
}

@Injectable({
  providedIn: 'root'
})
export class ModelAdmissionService {
  private readonly apiUrl = `${backendUrl}/model-admission`;

  constructor(private http: HttpClient) {}

  getConfig(): Observable<ModelAdmissionConfig> {
    return this.http.get<ModelAdmissionConfig>(`${this.apiUrl}/config`);
  }

  updateConfig(config: ModelAdmissionConfig): Observable<ModelAdmissionConfig> {
    return this.http.put<ModelAdmissionConfig>(`${this.apiUrl}/config`, config);
  }

  resetConfig(): Observable<ModelAdmissionConfig> {
    return this.http.post<ModelAdmissionConfig>(`${this.apiUrl}/config/reset`, {});
  }

  getStatus(): Observable<ModelAdmissionStatus> {
    return this.http.get<ModelAdmissionStatus>(`${this.apiUrl}/status`);
  }

  checkModel(modelId: string): Observable<AdmissionCheckResult> {
    return this.http.post<AdmissionCheckResult>(`${this.apiUrl}/check/${modelId}`, {});
  }

  loadModel(modelId: string): Observable<any> {
    return this.http.post(`${this.apiUrl}/load/${modelId}`, {});
  }

  unloadModel(modelId: string): Observable<any> {
    return this.http.post(`${this.apiUrl}/unload/${modelId}`, {});
  }

  demoteModel(modelId: string): Observable<any> {
    return this.http.post(`${this.apiUrl}/demote/${modelId}`, {});
  }

  promoteModel(modelId: string): Observable<any> {
    return this.http.post(`${this.apiUrl}/promote/${modelId}`, {});
  }

  formatBytes(bytes: number): string {
    if (bytes < 1024) return `${bytes} B`;
    if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
    if (bytes < 1024 * 1024 * 1024) return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
    return `${(bytes / (1024 * 1024 * 1024)).toFixed(2)} GB`;
  }

  getStateClass(state: string): string {
    switch (state) {
      case 'GPU_HOT': return 'state-gpu-hot';
      case 'CPU_WARM': return 'state-cpu-warm';
      case 'LOADING': return 'state-loading';
      case 'UNLOADED': return 'state-unloaded';
      case 'REJECTED': return 'state-rejected';
      default: return '';
    }
  }

  getStateIcon(state: string): string {
    switch (state) {
      case 'GPU_HOT': return 'bolt';
      case 'CPU_WARM': return 'memory';
      case 'LOADING': return 'hourglass_top';
      case 'UNLOADED': return 'eject';
      case 'REJECTED': return 'block';
      default: return 'help';
    }
  }
}
