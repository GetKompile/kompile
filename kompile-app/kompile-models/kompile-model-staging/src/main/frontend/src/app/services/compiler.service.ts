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

// ==================== Interfaces ====================

export interface OptimizationPassInfo {
  id: string;
  name: string;
  description: string;
  category: string; // CLEANUP, FUSION, GPU, QUANTIZATION
  isDefault: boolean;
}

export interface OptimizationProfileInfo {
  profileName: string;
  description: string;
  includedPasses: string[];
}

export interface CompilerOptimizeRequest {
  modelId: string;
  selectedPasses?: string[];
  maxIterations?: number;
  profile?: string;
  outputModelId?: string;
  quantizationType?: string;
  force?: boolean;
  createBackup?: boolean;
  dryRun?: boolean;
}

export interface CompilerOptimizeResponse {
  jobId: string;
  status: string;
  success: boolean;
  modelId: string;
  message?: string;
  error?: string;
  opsRemoved: number;
  opsFused: number;
  passesApplied: string[];
  beforeOpsCount: number;
  afterOpsCount: number;
  beforeVarsCount: number;
  afterVarsCount: number;
  sizeBeforeBytes: number;
  sizeAfterBytes: number;
  reductionPercent: number;
  optimizationTimeMs: number;
  backupFile?: string;
  dryRun: boolean;
}

export interface GraphInfoResponse {
  modelId: string;
  totalOps: number;
  totalVariables: number;
  opTypes: { [key: string]: number };
  inputNames: string[];
  outputNames: string[];
  ops: OpInfo[];
  modelSizeBytes: number;
  analysis?: GraphAnalysisInfo;
}

export interface GraphAnalysisInfo {
  graphDepth: number;
  parameterCount: number;
  parametersByDataType?: { [key: string]: number };
  constantCount: number;
  layerGroups?: LayerGroupInfo[];
  attentionHeads: number;
  hasAttentionFusion: boolean;
  hasLinearFusion: boolean;
  fusedOpCount: number;
  memoryEstimateBytes: number;
}

export interface LayerGroupInfo {
  name: string;
  count: number;
  opsPerGroup: number;
  opTypes: string[];
}

export interface OpInfo {
  name: string;
  opType: string;
  inputs: string[];
  outputs: string[];
}

export interface CompilerCompareResponse {
  success: boolean;
  error?: string;
  model1Info?: ModelInfo;
  model2Info?: ModelInfo;
  opsAdded: number;
  opsRemoved: number;
  opsChanged: number;
  sizeChange: number;
  maxAbsoluteDifference: number;
  meanAbsoluteDifference: number;
  outputsMatch: boolean;
  speedupFactor: number;
}

export interface ModelInfo {
  modelId: string;
  opsCount: number;
  varsCount: number;
  sizeBytes: number;
  inferenceTimeMs: number;
  opTypeCounts: { [key: string]: string };
}

export interface TritonCompileRequest {
  modelId: string;
  numWarps?: number;
  numStages?: number;
  numCTAs?: number;
  fpFusion?: boolean;
  arch?: string;
}

export interface TritonConfigResponse {
  tritonBuildThreads: number;
  tritonCacheEnabled: boolean;
  tritonNumWarps: number;
  tritonNumStages: number;
  tritonNumCTAs: number;
  tritonMaxNreg: number;
  tritonEnableFpFusion: boolean;
  tritonVerbose: boolean;
  tritonAlwaysCompile: boolean;
  tritonKernelDump: boolean;
  tritonCacheDir: string;
  tritonDumpDir: string;
  tritonOverrideDir: string;
  tritonOverrideArch: string;
}

// ==================== Cache & Job Interfaces ====================

export interface CacheEntry {
  key: string;
  shapeSignature: string;
  createdAt: string;
}

export interface CacheStatusResponse {
  executionPlanCacheSize: number;
  executionPlanCacheEnabled: boolean;
  dagCacheSize: number;
  dagCacheEnabled: boolean;
  executionPlanCacheEntries: CacheEntry[];
  dagCacheEntries: CacheEntry[];
}

export interface CompilationJobStatus {
  jobId: string;
  modelId: string;
  status: string; // QUEUED, COMPILING, COMPLETED, FAILED, CANCELLED
  compilationMode: string;
  executionMode: string;
  progressPercent: number;
  currentPhase: string;
  message: string;
  error: string;
  startedAt: string;
  completedAt: string;
  elapsedMs: number;
  result: CompilerOptimizeResponse;
}

export interface CompilationLogEntry {
  timestamp: string;
  level: string; // INFO, WARN, ERROR, DEBUG
  phase: string;
  message: string;
  details: { [key: string]: any };
}

// ==================== Device & Native Cache Interfaces ====================

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

export interface SaveCompiledGraphRequest {
  sourceModelId: string;
  outputModelId: string;
  targetOutputs?: string[];
  selectedPasses?: string[];
  profile?: string;
  maxIterations?: number;
  saveFormat?: string;
  description?: string;
}

export interface SaveCompiledGraphResponse {
  success: boolean;
  outputModelId: string;
  outputPath: string;
  error?: string;
  beforeOpsCount: number;
  afterOpsCount: number;
  beforeVarsCount: number;
  afterVarsCount: number;
  sizeBeforeBytes: number;
  sizeAfterBytes: number;
  reductionPercent: number;
  optimizationTimeMs: number;
  passesApplied: string[];
}

export interface CompiledModelInfo {
  modelId: string;
  filePath: string;
  sizeBytes: number;
  lastModified: string;
  totalOps: number;
  totalVariables: number;
}

export interface CompilationRequest {
  modelId: string;
  compilationMode?: string; // REDUCE_OVERHEAD, SPLIT_STITCH, MAX_AUTOTUNE
  executionMode?: string; // AUTO, SLOT_BY_SLOT, CUDA_GRAPHS, NVRTC_JIT, PTX_JIT, TRITON
  selectedPasses?: string[];
  profile?: string;
  maxIterations?: number;
  createBackup?: boolean;
  outputModelId?: string;
  enableCache?: boolean;
  targetOutputs?: string[];
  placeholderShapes?: { [key: string]: number[] };
}

export const DSP_COMPILATION_MODES = [
  { value: 'REDUCE_OVERHEAD', label: 'Reduce Overhead', description: 'Minimizes dispatch overhead with graph capture' },
  { value: 'SPLIT_STITCH', label: 'Split & Stitch', description: 'Splits graph into segments for parallel compilation' },
  { value: 'MAX_AUTOTUNE', label: 'Max Autotune', description: 'Exhaustive search for optimal kernel configurations' }
];

export const GRAPH_EXECUTION_MODES = [
  { value: 'AUTO', label: 'Auto', description: 'Automatically selects the best execution mode' },
  { value: 'SLOT_BY_SLOT', label: 'Slot by Slot', description: 'Sequential execution of operation slots' },
  { value: 'CUDA_GRAPHS', label: 'CUDA Graphs', description: 'Captures and replays CUDA graph for reduced launch overhead' },
  { value: 'NVRTC_JIT', label: 'NVRTC JIT', description: 'Runtime CUDA kernel compilation via NVRTC' },
  { value: 'PTX_JIT', label: 'PTX JIT', description: 'PTX-level just-in-time compilation' },
  { value: 'TRITON', label: 'Triton', description: 'Triton GPU compiler for optimized kernel generation' }
];

// ==================== Service ====================

@Injectable({
  providedIn: 'root'
})
export class CompilerService {

  private baseUrl: string;

  constructor(private http: HttpClient) {
    if (typeof window !== 'undefined' && window.location) {
      const protocol = window.location.protocol;
      const hostname = window.location.hostname;
      const port = window.location.port;
      this.baseUrl = `${protocol}//${hostname}${port ? ':' + port : ''}/api/compiler`;
    } else {
      this.baseUrl = '/api/compiler';
    }
  }

  // ==================== Passes & Profiles ====================

  getAvailablePasses(): Observable<OptimizationPassInfo[]> {
    return this.http.get<OptimizationPassInfo[]>(`${this.baseUrl}/passes`)
      .pipe(catchError(this.handleError));
  }

  getProfiles(): Observable<OptimizationProfileInfo[]> {
    return this.http.get<OptimizationProfileInfo[]>(`${this.baseUrl}/profiles`)
      .pipe(catchError(this.handleError));
  }

  // ==================== Optimization ====================

  optimize(request: CompilerOptimizeRequest): Observable<CompilerOptimizeResponse> {
    return this.http.post<CompilerOptimizeResponse>(`${this.baseUrl}/optimize`, request)
      .pipe(catchError(this.handleError));
  }

  // ==================== Graph Inspection ====================

  getGraphInfo(modelId: string): Observable<GraphInfoResponse> {
    return this.http.get<GraphInfoResponse>(`${this.baseUrl}/graph/${encodeURIComponent(modelId)}`)
      .pipe(catchError(this.handleError));
  }

  // ==================== Model Comparison ====================

  compareGraphs(model1Id: string, model2Id: string): Observable<CompilerCompareResponse> {
    return this.http.post<CompilerCompareResponse>(`${this.baseUrl}/compare`, {
      model1Id,
      model2Id
    }).pipe(catchError(this.handleError));
  }

  // ==================== Triton Compiler ====================

  getTritonConfig(): Observable<TritonConfigResponse> {
    return this.http.get<TritonConfigResponse>(`${this.baseUrl}/triton/config`)
      .pipe(catchError(this.handleError));
  }

  compileWithTriton(request: TritonCompileRequest): Observable<CompilerOptimizeResponse> {
    return this.http.post<CompilerOptimizeResponse>(`${this.baseUrl}/triton/compile`, request)
      .pipe(catchError(this.handleError));
  }

  // ==================== Device & Native Cache ====================

  getDeviceCacheStatus(): Observable<DeviceCacheStatusResponse> {
    return this.http.get<DeviceCacheStatusResponse>(`${this.baseUrl}/devices/cache`)
      .pipe(catchError(this.handleError));
  }

  clearNativeCache(type: string = 'all'): Observable<any> {
    return this.http.post(`${this.baseUrl}/devices/cache/clear?type=${encodeURIComponent(type)}`, {})
      .pipe(catchError(this.handleError));
  }

  // ==================== Cache Management ====================

  getCacheStatus(): Observable<CacheStatusResponse> {
    return this.http.get<CacheStatusResponse>(`${this.baseUrl}/cache/status`)
      .pipe(catchError(this.handleError));
  }

  clearCache(type: string = 'all'): Observable<any> {
    return this.http.post(`${this.baseUrl}/cache/clear?type=${encodeURIComponent(type)}`, {})
      .pipe(catchError(this.handleError));
  }

  setCacheEnabled(type: string, enabled: boolean): Observable<any> {
    return this.http.put(`${this.baseUrl}/cache/enabled?type=${encodeURIComponent(type)}&enabled=${enabled}`, {})
      .pipe(catchError(this.handleError));
  }

  // ==================== Compilation Jobs ====================

  startCompilationJob(request: CompilationRequest): Observable<CompilationJobStatus> {
    return this.http.post<CompilationJobStatus>(`${this.baseUrl}/jobs/start`, request)
      .pipe(catchError(this.handleError));
  }

  getJobs(): Observable<CompilationJobStatus[]> {
    return this.http.get<CompilationJobStatus[]>(`${this.baseUrl}/jobs`)
      .pipe(catchError(this.handleError));
  }

  getJobStatus(jobId: string): Observable<CompilationJobStatus> {
    return this.http.get<CompilationJobStatus>(`${this.baseUrl}/jobs/${encodeURIComponent(jobId)}`)
      .pipe(catchError(this.handleError));
  }

  getJobLogs(jobId: string): Observable<CompilationLogEntry[]> {
    return this.http.get<CompilationLogEntry[]>(`${this.baseUrl}/jobs/${encodeURIComponent(jobId)}/logs`)
      .pipe(catchError(this.handleError));
  }

  connectToJobStream(jobId: string): EventSource {
    return new EventSource(`${this.baseUrl}/jobs/${encodeURIComponent(jobId)}/stream`);
  }

  cancelJob(jobId: string): Observable<any> {
    return this.http.post(`${this.baseUrl}/jobs/${encodeURIComponent(jobId)}/cancel`, {})
      .pipe(catchError(this.handleError));
  }

  // ==================== Compiled Graph Management ====================

  saveCompiledGraph(request: SaveCompiledGraphRequest): Observable<SaveCompiledGraphResponse> {
    return this.http.post<SaveCompiledGraphResponse>(`${this.baseUrl}/save`, request)
      .pipe(catchError(this.handleError));
  }

  listCompiledModels(): Observable<CompiledModelInfo[]> {
    return this.http.get<CompiledModelInfo[]>(`${this.baseUrl}/models`)
      .pipe(catchError(this.handleError));
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

    console.error('CompilerService error:', errorMessage);
    return throwError(() => new Error(errorMessage));
  }
}
