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

export interface Nd4jEnvironmentConfig {
  // Thread configuration
  maxThreads?: number;
  maxMasterThreads?: number;

  // Debug/verbose modes
  debug?: boolean;
  verbose?: boolean;
  profiling?: boolean;

  // Core settings
  enableBlas?: boolean;
  helpersAllowed?: boolean;
  leaksDetector?: boolean;

  // Performance thresholds
  tadThreshold?: number;
  elementwiseThreshold?: number;

  // Memory limits (in bytes, 0 = unlimited)
  maxPrimaryMemory?: number;
  maxSpecialMemory?: number;
  maxDeviceMemory?: number;

  // Lifecycle tracking master switch
  lifecycleTracking?: boolean;

  // Lifecycle tracking sub-options
  trackViews?: boolean;
  trackDeletions?: boolean;
  snapshotFiles?: boolean;
  trackOperations?: boolean;

  // Lifecycle tracking parameters
  stackDepth?: number;
  reportInterval?: number;
  maxDeletionHistory?: number;

  // Individual tracker toggles
  ndArrayTracking?: boolean;
  dataBufferTracking?: boolean;
  tadCacheTracking?: boolean;
  shapeCacheTracking?: boolean;
  opContextTracking?: boolean;

  // Advanced debugging - function tracing
  funcTracePrintAllocate?: boolean;
  funcTracePrintDeallocate?: boolean;
  funcTracePrintJavaOnly?: boolean;

  // Advanced debugging - other
  logNativeNDArrayCreation?: boolean;
  logNDArrayEvents?: boolean;
  truncateNDArrayLogStrings?: boolean;
  numWorkspaceEventsToKeep?: number;
  checkInputChange?: boolean;
  checkOutputChange?: boolean;
  trackWorkspaceOpenClose?: boolean;
  deleteShapeInfo?: boolean;
  deletePrimary?: boolean;
  deleteSpecial?: boolean;
  variableTracingEnabled?: boolean;

  // JavaCPP settings
  javacppLoggerDebug?: boolean;
  javacppPathsFirst?: boolean;

  // BLAS configuration
  blasSerializationEnabled?: boolean;
  openBlasThreads?: number;

  // OpenMP thread configuration
  ompNumThreads?: number;

  // CUDA configuration
  cudaCurrentDevice?: number;
  cudaMemoryPinned?: boolean;
  cudaUseManagedMemory?: boolean;
  cudaMemoryPoolSize?: number;
  cudaForceP2P?: boolean;
  cudaAllocatorEnabled?: boolean;
  cudaMaxBlocks?: number;
  cudaMaxThreadsPerBlock?: number;
  cudaAsyncExecution?: boolean;
  cudaStreamLimit?: number;
  cudaUseDeviceHost?: boolean;
  cudaEventLimit?: number;
  cudaCachingAllocatorLimit?: number;
  cudaUseUnifiedMemory?: boolean;
  cudaPrefetchSize?: number;
  cudaGraphOptimization?: boolean;
  cudaTensorCoreEnabled?: boolean;
  cudaBlockingSync?: number;
  cudaDeviceSchedule?: number;
  cudaStackSize?: number;
  cudaMallocHeapSize?: number;
  cudaPrintfFifoSize?: number;
  cudaDevRuntimeSyncDepth?: number;
  cudaDevRuntimePendingLaunchCount?: number;
  cudaMaxL2FetchGranularity?: number;
  cudaPersistingL2CacheSize?: number;

  // Triton compiler configuration
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
}

export interface CudaSummary {
  deviceCount: number;
  currentDevice?: number;
  memoryPinned?: boolean;
  useManagedMemory?: boolean;
  memoryPoolSize?: number;
  forceP2P?: boolean;
  allocatorEnabled?: boolean;
  maxBlocks?: number;
  maxThreadsPerBlock?: number;
  asyncExecution?: boolean;
  streamLimit?: number;
  useDeviceHost?: boolean;
  eventLimit?: number;
  cachingAllocatorLimit?: number;
  useUnifiedMemory?: boolean;
  prefetchSize?: number;
  graphOptimization?: boolean;
  tensorCoreEnabled?: boolean;
  blockingSync?: number;
  deviceSchedule?: number;
  stackSize?: number;
  mallocHeapSize?: number;
  printfFifoSize?: number;
  devRuntimeSyncDepth?: number;
  devRuntimePendingLaunchCount?: number;
  maxL2FetchGranularity?: number;
  persistingL2CacheSize?: number;
}

export interface Nd4jConfigResponse {
  persisted: Nd4jEnvironmentConfig;
  actual: Nd4jEnvironmentConfig;
  summary: {
    threads: { maxThreads: number; maxMasterThreads: number; availableProcessors: number };
    debug: { debug: boolean; verbose: boolean; profiling: boolean };
    lifecycle: {
      enabled: boolean;
      trackViews: boolean;
      trackDeletions: boolean;
      snapshotFiles: boolean;
      trackOperations: boolean;
      stackDepth: number;
      reportInterval: number;
      maxDeletionHistory: number;
    };
    trackers: {
      ndArrayTracking: boolean;
      dataBufferTracking: boolean;
      tadCacheTracking: boolean;
      shapeCacheTracking: boolean;
      opContextTracking: boolean;
    };
    javacpp: { loggerDebug: boolean; pathsFirst: boolean };
    blas: { serializationEnabled: boolean; openBlasThreads: number; ompNumThreads: number };
    advancedDebug: { truncateNDArrayLogStrings: boolean; numWorkspaceEventsToKeep: number };
    isCudaBackend: boolean;
    cuda?: CudaSummary;
    triton?: {
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
    };
    configFilePath: string;
    configFileExists: boolean;
  };
}

export interface OmpSettings {
  ompNumThreads: number;
  configuredOmpNumThreads: number;
  availableProcessors: number;
  description: string;
}

export interface BlasSettings {
  blasSerializationEnabled: boolean;
  openBlasThreads: number;
  availableProcessors: number;
  description: { blasSerializationEnabled: string; openBlasThreads: string };
}

export interface PresetInfo {
  name: string;
  description: string;
  useCase: string;
}

export interface PresetsResponse {
  presets: { [key: string]: PresetInfo };
  currentPreset: string;
}

@Injectable({
  providedIn: 'root'
})
export class Nd4jEnvironmentService {
  private readonly baseUrl = `${backendUrl}/nd4j/environment`;

  constructor(private http: HttpClient) {}

  /**
   * Get full ND4J environment configuration
   */
  getConfiguration(): Observable<Nd4jConfigResponse> {
    return this.http.get<Nd4jConfigResponse>(this.baseUrl);
  }

  /**
   * Get configuration summary
   */
  getConfigurationSummary(): Observable<any> {
    return this.http.get<any>(`${this.baseUrl}/summary`);
  }

  /**
   * Update configuration
   */
  updateConfiguration(config: Partial<Nd4jEnvironmentConfig>): Observable<any> {
    return this.http.post<any>(this.baseUrl, config);
  }

  /**
   * Update thread settings
   */
  updateThreadSettings(maxThreads?: number, maxMasterThreads?: number): Observable<any> {
    const params: any = {};
    if (maxThreads !== undefined) params.maxThreads = maxThreads;
    if (maxMasterThreads !== undefined) params.maxMasterThreads = maxMasterThreads;
    return this.http.post<any>(`${this.baseUrl}/threads`, null, { params });
  }

  /**
   * Set lifecycle tracking
   */
  setLifecycleTracking(enabled: boolean, enableAllTrackers?: boolean): Observable<any> {
    const params: any = { enabled };
    if (enableAllTrackers !== undefined) params.enableAllTrackers = enableAllTrackers;
    return this.http.post<any>(`${this.baseUrl}/lifecycle-tracking`, null, { params });
  }

  /**
   * Update individual trackers
   */
  updateTrackers(trackers: {
    ndArrayTracking?: boolean;
    dataBufferTracking?: boolean;
    tadCacheTracking?: boolean;
    shapeCacheTracking?: boolean;
    opContextTracking?: boolean;
  }): Observable<any> {
    return this.http.post<any>(`${this.baseUrl}/trackers`, null, { params: trackers as any });
  }

  /**
   * Apply a preset configuration
   */
  applyPreset(presetName: string): Observable<any> {
    return this.http.post<any>(`${this.baseUrl}/preset/${presetName}`, null);
  }

  /**
   * Get available presets
   */
  getPresets(): Observable<PresetsResponse> {
    return this.http.get<PresetsResponse>(`${this.baseUrl}/presets`);
  }

  /**
   * Reset to defaults
   */
  resetConfiguration(): Observable<any> {
    return this.http.post<any>(`${this.baseUrl}/reset`, null);
  }

  /**
   * Update lifecycle parameters
   */
  updateLifecycleParams(params: {
    stackDepth?: number;
    reportInterval?: number;
    maxDeletionHistory?: number;
  }): Observable<any> {
    return this.http.post<any>(`${this.baseUrl}/lifecycle-params`, null, { params: params as any });
  }

  /**
   * Update high-overhead tracking options
   */
  updateHighOverheadTracking(trackViews?: boolean, trackDeletions?: boolean): Observable<any> {
    const params: any = {};
    if (trackViews !== undefined) params.trackViews = trackViews;
    if (trackDeletions !== undefined) params.trackDeletions = trackDeletions;
    return this.http.post<any>(`${this.baseUrl}/high-overhead-tracking`, null, { params });
  }

  /**
   * Update BLAS settings
   */
  updateBlasSettings(serializationEnabled?: boolean, openBlasThreads?: number): Observable<any> {
    const params: any = {};
    if (serializationEnabled !== undefined) params.serializationEnabled = serializationEnabled;
    if (openBlasThreads !== undefined) params.openBlasThreads = openBlasThreads;
    return this.http.post<any>(`${this.baseUrl}/blas`, null, { params });
  }

  /**
   * Get BLAS settings
   */
  getBlasSettings(): Observable<BlasSettings> {
    return this.http.get<BlasSettings>(`${this.baseUrl}/blas`);
  }

  /**
   * Update OMP settings
   */
  updateOmpSettings(ompNumThreads: number): Observable<any> {
    return this.http.post<any>(`${this.baseUrl}/omp`, null, { params: { ompNumThreads } });
  }

  /**
   * Get OMP settings
   */
  getOmpSettings(): Observable<OmpSettings> {
    return this.http.get<OmpSettings>(`${this.baseUrl}/omp`);
  }

  /**
   * Update memory limits
   */
  updateMemorySettings(maxPrimaryMemory?: number, maxSpecialMemory?: number, maxDeviceMemory?: number): Observable<any> {
    const config: Partial<Nd4jEnvironmentConfig> = {};
    if (maxPrimaryMemory !== undefined) config.maxPrimaryMemory = maxPrimaryMemory;
    if (maxSpecialMemory !== undefined) config.maxSpecialMemory = maxSpecialMemory;
    if (maxDeviceMemory !== undefined) config.maxDeviceMemory = maxDeviceMemory;
    return this.http.post<any>(this.baseUrl, config);
  }

  /**
   * Update performance thresholds
   */
  updatePerformanceThresholds(tadThreshold?: number, elementwiseThreshold?: number): Observable<any> {
    const params: any = {};
    if (tadThreshold !== undefined) params.tadThreshold = tadThreshold;
    if (elementwiseThreshold !== undefined) params.elementwiseThreshold = elementwiseThreshold;
    return this.http.post<any>(`${this.baseUrl}/thresholds`, null, { params });
  }

  /**
   * Get debug settings
   */
  getDebugSettings(): Observable<any> {
    return this.http.get<any>(`${this.baseUrl}/debug`);
  }

  /**
   * Update debug settings
   */
  updateDebugSettings(settings: {
    debug?: boolean;
    verbose?: boolean;
    profiling?: boolean;
    leaksDetector?: boolean;
  }): Observable<any> {
    const params: any = {};
    if (settings.debug !== undefined) params.debug = settings.debug;
    if (settings.verbose !== undefined) params.verbose = settings.verbose;
    if (settings.profiling !== undefined) params.profiling = settings.profiling;
    if (settings.leaksDetector !== undefined) params.leaksDetector = settings.leaksDetector;
    return this.http.post<any>(`${this.baseUrl}/debug`, null, { params });
  }

  /**
   * Get advanced debug settings
   */
  getAdvancedDebugSettings(): Observable<any> {
    return this.http.get<any>(`${this.baseUrl}/advanced-debug`);
  }

  /**
   * Update advanced debug settings
   */
  updateAdvancedDebugSettings(settings: {
    logNativeNDArrayCreation?: boolean;
    logNDArrayEvents?: boolean;
    checkInputChange?: boolean;
    checkOutputChange?: boolean;
    trackWorkspaceOpenClose?: boolean;
    variableTracingEnabled?: boolean;
  }): Observable<any> {
    const params: any = {};
    if (settings.logNativeNDArrayCreation !== undefined) params.logNativeNDArrayCreation = settings.logNativeNDArrayCreation;
    if (settings.logNDArrayEvents !== undefined) params.logNDArrayEvents = settings.logNDArrayEvents;
    if (settings.checkInputChange !== undefined) params.checkInputChange = settings.checkInputChange;
    if (settings.checkOutputChange !== undefined) params.checkOutputChange = settings.checkOutputChange;
    if (settings.trackWorkspaceOpenClose !== undefined) params.trackWorkspaceOpenClose = settings.trackWorkspaceOpenClose;
    if (settings.variableTracingEnabled !== undefined) params.variableTracingEnabled = settings.variableTracingEnabled;
    return this.http.post<any>(`${this.baseUrl}/advanced-debug`, null, { params });
  }

  /**
   * Get function trace settings
   */
  getFunctionTraceSettings(): Observable<any> {
    return this.http.get<any>(`${this.baseUrl}/function-trace`);
  }

  /**
   * Update function trace settings
   */
  updateFunctionTraceSettings(settings: {
    funcTracePrintAllocate?: boolean;
    funcTracePrintDeallocate?: boolean;
    funcTracePrintJavaOnly?: boolean;
  }): Observable<any> {
    const params: any = {};
    if (settings.funcTracePrintAllocate !== undefined) params.funcTracePrintAllocate = settings.funcTracePrintAllocate;
    if (settings.funcTracePrintDeallocate !== undefined) params.funcTracePrintDeallocate = settings.funcTracePrintDeallocate;
    if (settings.funcTracePrintJavaOnly !== undefined) params.funcTracePrintJavaOnly = settings.funcTracePrintJavaOnly;
    return this.http.post<any>(`${this.baseUrl}/function-trace`, null, { params });
  }

  /**
   * Get deletion settings
   */
  getDeletionSettings(): Observable<any> {
    return this.http.get<any>(`${this.baseUrl}/deletion`);
  }

  /**
   * Update deletion settings
   */
  updateDeletionSettings(settings: {
    deleteShapeInfo?: boolean;
    deletePrimary?: boolean;
    deleteSpecial?: boolean;
  }): Observable<any> {
    const params: any = {};
    if (settings.deleteShapeInfo !== undefined) params.deleteShapeInfo = settings.deleteShapeInfo;
    if (settings.deletePrimary !== undefined) params.deletePrimary = settings.deletePrimary;
    if (settings.deleteSpecial !== undefined) params.deleteSpecial = settings.deleteSpecial;
    return this.http.post<any>(`${this.baseUrl}/deletion`, null, { params });
  }

  /**
   * Get core settings
   */
  getCoreSettings(): Observable<any> {
    return this.http.get<any>(`${this.baseUrl}/core`);
  }

  /**
   * Update core settings
   */
  updateCoreSettings(settings: {
    enableBlas?: boolean;
    helpersAllowed?: boolean;
  }): Observable<any> {
    const params: any = {};
    if (settings.enableBlas !== undefined) params.enableBlas = settings.enableBlas;
    if (settings.helpersAllowed !== undefined) params.helpersAllowed = settings.helpersAllowed;
    return this.http.post<any>(`${this.baseUrl}/core`, null, { params });
  }

  /**
   * Get threshold settings
   */
  getThresholdSettings(): Observable<any> {
    return this.http.get<any>(`${this.baseUrl}/thresholds`);
  }

  /**
   * Get memory limit settings
   */
  getMemoryLimitSettings(): Observable<any> {
    return this.http.get<any>(`${this.baseUrl}/memory-limits`);
  }

  /**
   * Update memory limit settings via dedicated endpoint
   */
  updateMemoryLimitSettings(settings: {
    maxPrimaryMemory?: number;
    maxSpecialMemory?: number;
    maxDeviceMemory?: number;
  }): Observable<any> {
    const params: any = {};
    if (settings.maxPrimaryMemory !== undefined) params.maxPrimaryMemory = settings.maxPrimaryMemory;
    if (settings.maxSpecialMemory !== undefined) params.maxSpecialMemory = settings.maxSpecialMemory;
    if (settings.maxDeviceMemory !== undefined) params.maxDeviceMemory = settings.maxDeviceMemory;
    return this.http.post<any>(`${this.baseUrl}/memory-limits`, null, { params });
  }

  /**
   * Get JavaCPP settings
   */
  getJavacppSettings(): Observable<any> {
    return this.http.get<any>(`${this.baseUrl}/javacpp`);
  }

  /**
   * Update JavaCPP settings
   */
  updateJavacppSettings(settings: {
    loggerDebug?: boolean;
    pathsFirst?: boolean;
  }): Observable<any> {
    const params: any = {};
    if (settings.loggerDebug !== undefined) params.loggerDebug = settings.loggerDebug;
    if (settings.pathsFirst !== undefined) params.pathsFirst = settings.pathsFirst;
    return this.http.post<any>(`${this.baseUrl}/javacpp`, null, { params });
  }

  /**
   * Check if CUDA backend is available
   */
  isCudaBackend(): Observable<{ isCuda: boolean; deviceCount?: number }> {
    return this.http.get<{ isCuda: boolean; deviceCount?: number }>(`${this.baseUrl}/cuda/status`);
  }

  /**
   * Update CUDA settings (only applies when running on CUDA backend)
   */
  /**
   * Get Triton compiler settings
   */
  getTritonSettings(): Observable<any> {
    return this.http.get<any>(`${this.baseUrl}/triton`);
  }

  /**
   * Update Triton compiler settings
   */
  updateTritonSettings(settings: {
    buildThreads?: number;
    cacheEnabled?: boolean;
    verbose?: boolean;
    alwaysCompile?: boolean;
    numWarps?: number;
    numStages?: number;
    numCTAs?: number;
    enableFpFusion?: boolean;
    cacheDir?: string;
    dumpDir?: string;
    overrideArch?: string;
  }): Observable<any> {
    const params: any = {};
    if (settings.buildThreads !== undefined) params.buildThreads = settings.buildThreads;
    if (settings.cacheEnabled !== undefined) params.cacheEnabled = settings.cacheEnabled;
    if (settings.verbose !== undefined) params.verbose = settings.verbose;
    if (settings.alwaysCompile !== undefined) params.alwaysCompile = settings.alwaysCompile;
    if (settings.numWarps !== undefined) params.numWarps = settings.numWarps;
    if (settings.numStages !== undefined) params.numStages = settings.numStages;
    if (settings.numCTAs !== undefined) params.numCTAs = settings.numCTAs;
    if (settings.enableFpFusion !== undefined) params.enableFpFusion = settings.enableFpFusion;
    if (settings.cacheDir !== undefined) params.cacheDir = settings.cacheDir;
    if (settings.dumpDir !== undefined) params.dumpDir = settings.dumpDir;
    if (settings.overrideArch !== undefined) params.overrideArch = settings.overrideArch;
    return this.http.post<any>(`${this.baseUrl}/triton`, null, { params });
  }

  updateCudaSettings(settings: {
    cudaCurrentDevice?: number;
    cudaMemoryPinned?: boolean;
    cudaUseManagedMemory?: boolean;
    cudaMemoryPoolSize?: number;
    cudaForceP2P?: boolean;
    cudaAllocatorEnabled?: boolean;
    cudaMaxBlocks?: number;
    cudaMaxThreadsPerBlock?: number;
    cudaAsyncExecution?: boolean;
    cudaStreamLimit?: number;
    cudaUseDeviceHost?: boolean;
    cudaEventLimit?: number;
    cudaCachingAllocatorLimit?: number;
    cudaUseUnifiedMemory?: boolean;
    cudaPrefetchSize?: number;
    cudaGraphOptimization?: boolean;
    cudaTensorCoreEnabled?: boolean;
    cudaBlockingSync?: number;
    cudaDeviceSchedule?: number;
    cudaStackSize?: number;
    cudaMallocHeapSize?: number;
    cudaPrintfFifoSize?: number;
    cudaDevRuntimeSyncDepth?: number;
    cudaDevRuntimePendingLaunchCount?: number;
    cudaMaxL2FetchGranularity?: number;
    cudaPersistingL2CacheSize?: number;
  }): Observable<any> {
    return this.http.post<any>(this.baseUrl, settings);
  }

  /**
   * Get SameDiff graph optimizer / DSP framework settings
   */
  getFrameworkSettings(): Observable<any> {
    return this.http.get<any>(`${this.baseUrl}/framework`);
  }

  /**
   * Update SameDiff graph optimizer / DSP framework settings
   */
  updateFrameworkSettings(settings: {
    optimizerEnabled?: boolean;
    optimizerFp16?: boolean;
    dspNoFreeze?: boolean;
    dspNoNativeDecode?: boolean;
    dspNoAttnOverride?: boolean;
    dspNoDirect?: boolean;
    tritonSkipKernels?: boolean;
    tritonTf32?: boolean;
    cublasDisableWorkspace?: boolean;
    dspDiagnostics?: string;
    opTiming?: boolean;
  }): Observable<any> {
    const params: any = {};
    if (settings.optimizerEnabled !== undefined) params.optimizerEnabled = settings.optimizerEnabled;
    if (settings.optimizerFp16 !== undefined) params.optimizerFp16 = settings.optimizerFp16;
    if (settings.dspNoFreeze !== undefined) params.dspNoFreeze = settings.dspNoFreeze;
    if (settings.dspNoNativeDecode !== undefined) params.dspNoNativeDecode = settings.dspNoNativeDecode;
    if (settings.dspNoAttnOverride !== undefined) params.dspNoAttnOverride = settings.dspNoAttnOverride;
    if (settings.dspNoDirect !== undefined) params.dspNoDirect = settings.dspNoDirect;
    if (settings.tritonSkipKernels !== undefined) params.tritonSkipKernels = settings.tritonSkipKernels;
    if (settings.tritonTf32 !== undefined) params.tritonTf32 = settings.tritonTf32;
    if (settings.cublasDisableWorkspace !== undefined) params.cublasDisableWorkspace = settings.cublasDisableWorkspace;
    if (settings.dspDiagnostics !== undefined) params.dspDiagnostics = settings.dspDiagnostics;
    if (settings.opTiming !== undefined) params.opTiming = settings.opTiming;
    return this.http.post<any>(`${this.baseUrl}/framework`, null, { params });
  }

  /**
   * Apply a framework preset configuration
   */
  applyFrameworkPreset(presetName: string): Observable<any> {
    return this.http.post<any>(`${this.baseUrl}/framework/preset/${presetName}`, null);
  }
}
