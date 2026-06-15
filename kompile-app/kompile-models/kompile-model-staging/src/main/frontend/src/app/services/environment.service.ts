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

export interface EnvironmentSettings {
  cpu: boolean;
  blasMajorVersion: number;
  blasMinorVersion: number;
  blasPatchVersion: number;
  enableBlas: boolean;
  maxPrimaryMemory: number;
  maxSpecialMemory: number;
  maxDeviceMemory: number;
  verbose: boolean;
  debug: boolean;
  profiling: boolean;
  detectingLeaks: boolean;
  helpersAllowed: boolean;
  logNativeNDArrayCreation: boolean;
  checkOutputChange: boolean;
  checkInputChange: boolean;
  lifecycleTracking: boolean;
  trackViews: boolean;
  trackDeletions: boolean;
  ndArrayTracking: boolean;
  dataBufferTracking: boolean;
  stackDepth: number;
  reportInterval: number;
  maxDeletionHistory: number;
  tadThreshold: number;
  elementwiseThreshold: number;
  maxThreads: number;
  maxMasterThreads: number;
  gpuAvailable: boolean;
  cuda: CudaSettings;
  triton: TritonSettings;
  dsp: DspSettings;
}

export interface MemorySettings {
  enableBlas: boolean;
  maxPrimaryMemory: number;
  maxSpecialMemory: number;
  maxDeviceMemory: number;
  jvmMaxMemory: number;
  jvmTotalMemory: number;
  jvmFreeMemory: number;
}

export interface CudaSettings {
  available: boolean;
  deviceCount: number;
  currentDevice: number;
  memoryPinned: boolean;
  useManagedMemory: boolean;
  memoryPoolSize: number;
  maxBlocks: number;
  asyncExecution: boolean;
  tensorCoreEnabled: boolean;
  graphOptimization: boolean;
}

export interface TritonSettings {
  // Compiler settings
  buildThreads: number;
  cacheEnabled: boolean;
  verbose: boolean;
  alwaysCompile: boolean;
  numWarps: number;
  numStages: number;
  numCTAs: number;
  enableFpFusion: boolean;
  cacheDir: string;
  dumpDir: string;
  overrideArch: string;
  disableLineInfo: boolean;
  maxNreg: number;
  attentionBlockN: number;

  // Compilation mode
  compileAll: boolean;
  includeTypes: string;
  excludeOps: string;

  // Section fusion
  sectionFusion: boolean;
  fusionScoring: boolean;
  fusionMinScore: number;

  // Graph capture
  graphCapture: boolean;
  allowFallbackCapture: boolean;
  forceRecapture: boolean;
  captureMinExec: number;

  // Arg table
  consolidatedArgTable: boolean;
  argDirtyTracking: boolean;

  // Cooperative launch
  cooperativeLaunch: boolean;
  coopTargetBlocks: number;

  // Debug/verification
  skipKernels: boolean;
  verifyKernels: boolean;
  verifyFullSnapshot: boolean;
  dumpSections: boolean;
  dumpArgs: boolean;

  // Subsegment limits
  maxSubsegmentOps: number;
  maxSubsegmentSections: number;

  // Segment fusion optimization flags
  fuseIdentityShapes: boolean;
  fuseCastChains: boolean;
  fuseTrivialGather: boolean;
  specializePermuteSeq1: boolean;
  eliminateConcatSplitPairs: boolean;
  fusedMatmul: boolean;
  fuseAttentionNeighborhoods: boolean;
}

export interface DspSettings {
  // GEMM / matmul
  batchedGemm: boolean;
  cublasTf32: boolean;
  cublasCaptureWorkspace: boolean;
  fp16Compute: boolean;
  matmulSegmentation: boolean;

  // Cast optimization
  castElimination: boolean;
  castSinkMatmul: boolean;

  // Batch-zero
  batchZero: boolean;
  batchZeroKernel: boolean;

  // Symbolic shapes
  symbolicShapes: boolean;
  symbolicShapeWarmup: number;

  // Capture pool
  capturePoolEnabled: boolean;
  capturePoolMaxBytes: number;
}

export interface DebugSettings {
  verbose: boolean;
  debug: boolean;
  profiling: boolean;
  detectingLeaks: boolean;
  helpersAllowed: boolean;
  logNativeNDArrayCreation: boolean;
  checkOutputChange: boolean;
  checkInputChange: boolean;
}

export interface LifecycleSettings {
  lifecycleTracking: boolean;
  trackViews: boolean;
  trackDeletions: boolean;
  ndArrayTracking: boolean;
  dataBufferTracking: boolean;
  stackDepth: number;
  reportInterval: number;
  maxDeletionHistory: number;
}

export interface UpdateResponse {
  success: boolean;
  updatedCount: number;
  message: string;
  error?: string;
}

export interface DeviceInfo {
  deviceId: number;
  deviceName: string;
  freeMemoryBytes: number;
  totalMemoryBytes: number;
  usedMemoryBytes: number;
  memoryUtilizationPercent: number;
  computeMajor: number;
  computeMinor: number;
  computeCapability: string;
}

export interface NativeCacheInfo {
  cacheType: string;
  cachedEntries: number;
  cachedBytes: number;
  peakCachedEntries: number;
  peakCachedBytes: number;
  cacheContents: string;
}

export interface DeviceCacheStatusResponse {
  availableDevices: number;
  devices: DeviceInfo[];
  tadCache: NativeCacheInfo;
  shapeCache: NativeCacheInfo;
}

export interface LlmConfigPreset {
  id: string;
  name: string;
  description: string;
}

export interface LlmConfigApplyResponse {
  success: boolean;
  preset: string;
  presetName: string;
  message: string;
  triton?: TritonSettings;
  dsp?: DspSettings;
  error?: string;
}

export interface PerformanceProfile {
  id: string;
  name: string;
  description: string;
  settings: { [key: string]: any };
}

@Injectable({
  providedIn: 'root'
})
export class EnvironmentService {

  private baseUrl: string;

  constructor(private http: HttpClient) {
    if (typeof window !== 'undefined' && window.location) {
      const protocol = window.location.protocol;
      const hostname = window.location.hostname;
      const port = window.location.port;
      this.baseUrl = `${protocol}//${hostname}${port ? ':' + port : ''}/api/environment`;
    } else {
      this.baseUrl = '/api/environment';
    }
  }

  // ==================== Full Environment ====================

  getEnvironment(): Observable<EnvironmentSettings> {
    return this.http.get<EnvironmentSettings>(this.baseUrl)
      .pipe(catchError(this.handleError));
  }

  updateEnvironment(settings: Partial<EnvironmentSettings>): Observable<UpdateResponse> {
    return this.http.put<UpdateResponse>(this.baseUrl, settings)
      .pipe(catchError(this.handleError));
  }

  // ==================== Memory ====================

  getMemorySettings(): Observable<MemorySettings> {
    return this.http.get<MemorySettings>(`${this.baseUrl}/memory`)
      .pipe(catchError(this.handleError));
  }

  // ==================== CUDA ====================

  getCudaSettings(): Observable<CudaSettings> {
    return this.http.get<CudaSettings>(`${this.baseUrl}/cuda`)
      .pipe(catchError(this.handleError));
  }

  updateCudaSettings(settings: Partial<CudaSettings>): Observable<UpdateResponse> {
    return this.http.put<UpdateResponse>(`${this.baseUrl}/cuda`, settings)
      .pipe(catchError(this.handleError));
  }

  // ==================== Triton ====================

  getTritonSettings(): Observable<TritonSettings> {
    return this.http.get<TritonSettings>(`${this.baseUrl}/triton`)
      .pipe(catchError(this.handleError));
  }

  updateTritonSettings(settings: Partial<TritonSettings>): Observable<UpdateResponse> {
    return this.http.put<UpdateResponse>(`${this.baseUrl}/triton`, settings)
      .pipe(catchError(this.handleError));
  }

  // ==================== DSP ====================

  getDspSettings(): Observable<DspSettings> {
    return this.http.get<DspSettings>(`${this.baseUrl}/dsp`)
      .pipe(catchError(this.handleError));
  }

  updateDspSettings(settings: Partial<DspSettings>): Observable<UpdateResponse> {
    return this.http.put<UpdateResponse>(`${this.baseUrl}/dsp`, settings)
      .pipe(catchError(this.handleError));
  }

  // ==================== Debug ====================

  getDebugSettings(): Observable<DebugSettings> {
    return this.http.get<DebugSettings>(`${this.baseUrl}/debug`)
      .pipe(catchError(this.handleError));
  }

  updateDebugSettings(settings: Partial<DebugSettings>): Observable<UpdateResponse> {
    return this.http.put<UpdateResponse>(`${this.baseUrl}/debug`, settings)
      .pipe(catchError(this.handleError));
  }

  // ==================== Lifecycle ====================

  getLifecycleSettings(): Observable<LifecycleSettings> {
    return this.http.get<LifecycleSettings>(`${this.baseUrl}/lifecycle`)
      .pipe(catchError(this.handleError));
  }

  updateLifecycleSettings(settings: Partial<LifecycleSettings>): Observable<UpdateResponse> {
    return this.http.put<UpdateResponse>(`${this.baseUrl}/lifecycle`, settings)
      .pipe(catchError(this.handleError));
  }

  // ==================== Per-Device & Native Cache ====================

  getDeviceCacheStatus(): Observable<DeviceCacheStatusResponse> {
    return this.http.get<DeviceCacheStatusResponse>(`${this.baseUrl}/devices`)
      .pipe(catchError(this.handleError));
  }

  clearNativeCache(type: string = 'all'): Observable<any> {
    return this.http.post(`${this.baseUrl}/devices/cache/clear?type=${encodeURIComponent(type)}`, {})
      .pipe(catchError(this.handleError));
  }

  // ==================== Performance Profiles ====================

  getPerformanceProfiles(): Observable<PerformanceProfile[]> {
    return this.http.get<PerformanceProfile[]>(`${this.baseUrl}/profiles`)
      .pipe(catchError(this.handleError));
  }

  applyPerformanceProfile(profileId: string): Observable<any> {
    return this.http.post(`${this.baseUrl}/profiles/apply?profile=${encodeURIComponent(profileId)}`, {})
      .pipe(catchError(this.handleError));
  }

  // ==================== LLM Config Presets ====================

  getLlmConfigPresets(): Observable<LlmConfigPreset[]> {
    return this.http.get<LlmConfigPreset[]>(`${this.baseUrl}/llm-config/presets`)
      .pipe(catchError(this.handleError));
  }

  applyLlmConfig(preset: string): Observable<LlmConfigApplyResponse> {
    return this.http.post<LlmConfigApplyResponse>(
      `${this.baseUrl}/llm-config/apply?preset=${encodeURIComponent(preset)}`, {}
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

    console.error('EnvironmentService error:', errorMessage);
    return throwError(() => new Error(errorMessage));
  }
}
