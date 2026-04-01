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

export interface SamediffBenchmarkConfig {
  name: string;
  isActive?: boolean;
  createdAt?: string;
  lastUsedAt?: string;

  // Triton settings
  tritonBuildThreads?: number;
  tritonCacheEnabled?: boolean;
  tritonVerbose?: boolean;
  tritonAlwaysCompile?: boolean;
  tritonNumWarps?: number;
  tritonNumStages?: number;
  tritonNumCTAs?: number;
  tritonEnableFpFusion?: boolean;
  tritonCacheDir?: string;
  tritonDumpDir?: string;
  tritonOverrideArch?: string;

  // CUDA settings
  cudaTensorCoreEnabled?: boolean;
  cudaGraphOptimization?: boolean;

  // Generation
  maxTokens?: number;
  captureMinExec?: number;

  // Validation
  minDiversityPct?: number;
  expectedSubstrings?: string[];
  expectStructuralTags?: boolean;
}

export interface SamediffBenchmarkResult {
  configName: string;
  passed: boolean;
  failureMessage?: string;
  resetMs: number;
  compileMs: number;
  decodeMs: number;
  validateMs: number;
  totalMs: number;
  tokenCount: number;
  tokPerSec: number;
  decodeTokPerSec: number;
  firstTokenMs: number;
  tritonLaunches: number;
  tritonCacheHits: number;
  generatedTextPreview?: string;
  finishReason: string;
  timestamp: string;
}

export interface ProfileSearchRequest {
  warpsRange?: number[];
  stagesRange?: number[];
  fpFusionRange?: boolean[];
}

@Injectable({
  providedIn: 'root'
})
export class BenchmarkService {
  private readonly baseUrl = `${backendUrl}/benchmark`;

  constructor(private http: HttpClient) {}

  listConfigs(): Observable<SamediffBenchmarkConfig[]> {
    return this.http.get<SamediffBenchmarkConfig[]>(`${this.baseUrl}/configs`);
  }

  getConfig(name: string): Observable<SamediffBenchmarkConfig> {
    return this.http.get<SamediffBenchmarkConfig>(`${this.baseUrl}/configs/${name}`);
  }

  saveConfig(config: SamediffBenchmarkConfig): Observable<SamediffBenchmarkConfig> {
    return this.http.post<SamediffBenchmarkConfig>(`${this.baseUrl}/configs`, config);
  }

  updateConfig(name: string, config: SamediffBenchmarkConfig): Observable<SamediffBenchmarkConfig> {
    return this.http.put<SamediffBenchmarkConfig>(`${this.baseUrl}/configs/${name}`, config);
  }

  deleteConfig(name: string): Observable<any> {
    return this.http.delete<any>(`${this.baseUrl}/configs/${name}`);
  }

  activateConfig(name: string): Observable<any> {
    return this.http.post<any>(`${this.baseUrl}/configs/${name}/activate`, null);
  }

  getActiveConfig(): Observable<SamediffBenchmarkConfig> {
    return this.http.get<SamediffBenchmarkConfig>(`${this.baseUrl}/active`);
  }

  runBenchmark(configName: string): Observable<SamediffBenchmarkResult> {
    return this.http.post<SamediffBenchmarkResult>(`${this.baseUrl}/run`, null, {
      params: { configName }
    });
  }

  runMatrix(request: ProfileSearchRequest): Observable<SamediffBenchmarkResult[]> {
    return this.http.post<SamediffBenchmarkResult[]>(`${this.baseUrl}/run-matrix`, request);
  }

  searchOptimalProfile(request: ProfileSearchRequest): Observable<any> {
    return this.http.post<any>(`${this.baseUrl}/search`, request);
  }

  getResults(): Observable<SamediffBenchmarkResult[]> {
    return this.http.get<SamediffBenchmarkResult[]>(`${this.baseUrl}/results`);
  }

  clearResults(): Observable<any> {
    return this.http.delete<any>(`${this.baseUrl}/results`);
  }

  applyOptimalDefaults(): Observable<any> {
    return this.http.post<any>(`${this.baseUrl}/apply-optimal`, null);
  }
}
