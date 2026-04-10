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

/**
 * Subprocess configuration response from the backend.
 */
export interface SubprocessConfigResponse {
  enabled: boolean;
  javaPath: string;
  heapSize: string;
  offHeapMaxBytes: string;
  offHeapMultiplier: number;
  timeoutMinutes: number;
  // VLM subprocess configuration
  vlmHeapSize: string;
  vlmOffHeapMultiplier: number;
  vlmTimeoutMinutes: number;
  vlmCudaPinnedHostLimitMb: number;
  heartbeatIntervalSeconds: number;
  staleThresholdSeconds: number;
  // ND4J / Pipeline settings
  queueCapacity: number;
  parallelIndexing: boolean;
  indexingWorkers: number;
  indexingBatchAccumulationSize: number;
  embeddingThreads: number;
  // Restart configuration
  restartEnabled: boolean;
  maxRestartAttempts: number;
  initialBackoffMs: number;
  backoffMultiplier: number;
  heapIncreaseFactor: number;
  systemRamSafetyMargin: number;
  // Stall detection
  restartOnStall: boolean;
  restartOnTimeout: boolean;
  stallDetectionThresholdSeconds: number;
  progressStallWarningSeconds: number;
  // Native executable configuration
  nativeExecutableMode: string;
  nativeExecutablePath: string;
  ingestExecutablePath: string;
  vectorPopulationExecutablePath: string;
  embeddingExecutablePath: string;
  modelInitExecutablePath: string;
  subprocessTypeFlag: string;
  resolvedNativeMode: boolean;
  runningInNativeImage: boolean;
  hasClasspath: boolean;
  // Memory watchdog thresholds
  offHeapThresholdPercent: number;
  offHeapCriticalPercent: number;
  offHeapKillThresholdPercent: number;
  gpuMemoryThresholdPercent: number;
  gpuMemoryCriticalPercent: number;
  gpuMemoryKillThresholdPercent: number;
  // System info
  callbackBaseUrl: string;
  actualServerPort: number;
  availableMemoryMb: number;
  availableProcessors: number;
  osName: string;
  javaVersion: string;
}

/**
 * Subprocess configuration update request.
 */
export interface SubprocessConfigUpdate {
  enabled?: boolean;
  javaPath?: string;
  heapSize?: string;
  offHeapMaxBytes?: string;
  offHeapMultiplier?: number;
  timeoutMinutes?: number;
  // VLM subprocess configuration
  vlmHeapSize?: string;
  vlmOffHeapMultiplier?: number;
  vlmTimeoutMinutes?: number;
  vlmCudaPinnedHostLimitMb?: number;
  heartbeatIntervalSeconds?: number;
  staleThresholdSeconds?: number;
  // ND4J / Pipeline settings
  queueCapacity?: number;
  parallelIndexing?: boolean;
  indexingWorkers?: number;
  indexingBatchAccumulationSize?: number;
  embeddingThreads?: number;
  // Restart configuration
  restartEnabled?: boolean;
  maxRestartAttempts?: number;
  initialBackoffMs?: number;
  backoffMultiplier?: number;
  heapIncreaseFactor?: number;
  systemRamSafetyMargin?: number;
  // Stall detection
  restartOnStall?: boolean;
  restartOnTimeout?: boolean;
  stallDetectionThresholdSeconds?: number;
  progressStallWarningSeconds?: number;
  // Native executable configuration
  nativeExecutableMode?: string;
  nativeExecutablePath?: string;
  ingestExecutablePath?: string;
  vectorPopulationExecutablePath?: string;
  embeddingExecutablePath?: string;
  modelInitExecutablePath?: string;
  subprocessTypeFlag?: string;
  // Memory watchdog thresholds
  offHeapThresholdPercent?: number;
  offHeapCriticalPercent?: number;
  offHeapKillThresholdPercent?: number;
  gpuMemoryThresholdPercent?: number;
  gpuMemoryCriticalPercent?: number;
  gpuMemoryKillThresholdPercent?: number;
}

/**
 * Native executable validation response.
 */
export interface NativeExecutableValidation {
  valid: boolean;
  executablePath?: string;
  versionOutput?: string;
  exitCode?: number;
  error?: string;
}

/**
 * Native mode option.
 */
export interface NativeModeOption {
  value: string;
  label: string;
  description: string;
}

/**
 * Native image runtime info.
 */
export interface NativeImageInfo {
  runningInNativeImage: boolean;
  hasClasspath: boolean;
  recommendedLaunchMode: string;
  currentExecutablePath?: string;
  configuredNativeMode?: string;
  resolvedNativeMode?: boolean;
}

/**
 * Subprocess status for an active process.
 */
export interface SubprocessStatus {
  taskId: string;
  fileName: string;
  pid: number;
  alive: boolean;
  cancelled: boolean;
  oomDetected: boolean;
  currentPhase: string;
  progressPercent: number;
  lastMessage: string;
  startTime: string;
  lastHeartbeat: string;
  elapsedTime: string;
  // Memory metrics
  heapUsagePercent: number;
  heapUsedBytes: number;
  heapMaxBytes: number;
  offHeapUsagePercent: number;
  offHeapUsedBytes: number;
  offHeapMaxBytes: number;
  gpuUsagePercent: number;
  gpuUsedBytes: number;
  gpuMaxBytes: number;
  // Peak memory
  peakHeapUsagePercent: number;
  peakOffHeapUsagePercent: number;
  peakGpuUsagePercent: number;
}

/**
 * System information response.
 */
export interface SystemInfo {
  availableProcessors: number;
  maxMemoryMb: number;
  totalMemoryMb: number;
  freeMemoryMb: number;
  usedMemoryMb: number;
  osName: string;
  osVersion: string;
  osArch: string;
  javaVersion: string;
  javaVendor: string;
  javaHome: string;
  userDir: string;
}

/**
 * Java path validation response.
 */
export interface JavaPathValidation {
  valid: boolean;
  javaPath?: string;
  versionInfo?: string;
  error?: string;
}

/**
 * Service for managing subprocess ingest configuration.
 */
@Injectable({
  providedIn: 'root'
})
export class SubprocessConfigService {
  private readonly baseUrl = `${backendUrl}/subprocess-config`;

  constructor(private http: HttpClient) {}

  /**
   * Get current subprocess configuration.
   */
  getConfiguration(): Observable<SubprocessConfigResponse> {
    return this.http.get<SubprocessConfigResponse>(this.baseUrl);
  }

  /**
   * Update subprocess configuration.
   */
  updateConfiguration(update: SubprocessConfigUpdate): Observable<SubprocessConfigResponse> {
    return this.http.post<SubprocessConfigResponse>(this.baseUrl, update);
  }

  /**
   * Reset configuration to defaults.
   */
  resetConfiguration(): Observable<SubprocessConfigResponse> {
    return this.http.post<SubprocessConfigResponse>(`${this.baseUrl}/reset`, {});
  }

  /**
   * Enable subprocess mode.
   */
  enable(): Observable<SubprocessConfigResponse> {
    return this.http.post<SubprocessConfigResponse>(`${this.baseUrl}/enable`, {});
  }

  /**
   * Disable subprocess mode.
   */
  disable(): Observable<SubprocessConfigResponse> {
    return this.http.post<SubprocessConfigResponse>(`${this.baseUrl}/disable`, {});
  }

  /**
   * Get available heap size options.
   */
  getHeapSizeOptions(): Observable<string[]> {
    return this.http.get<string[]>(`${this.baseUrl}/heap-options`);
  }

  /**
   * Get all active subprocess statuses.
   */
  getActiveProcesses(): Observable<SubprocessStatus[]> {
    return this.http.get<SubprocessStatus[]>(`${this.baseUrl}/active-processes`);
  }

  /**
   * Get status of a specific subprocess.
   */
  getProcessStatus(taskId: string): Observable<SubprocessStatus> {
    return this.http.get<SubprocessStatus>(`${this.baseUrl}/active-processes/${encodeURIComponent(taskId)}`);
  }

  /**
   * Cancel a running subprocess.
   */
  cancelProcess(taskId: string): Observable<{taskId: string, cancelled: boolean, message: string}> {
    return this.http.post<{taskId: string, cancelled: boolean, message: string}>(
      `${this.baseUrl}/active-processes/${encodeURIComponent(taskId)}/cancel`,
      {}
    );
  }

  /**
   * Validate a Java path.
   */
  validateJavaPath(javaPath: string): Observable<JavaPathValidation> {
    return this.http.post<JavaPathValidation>(`${this.baseUrl}/validate-java-path`, { javaPath });
  }

  /**
   * Get system information.
   */
  getSystemInfo(): Observable<SystemInfo> {
    return this.http.get<SystemInfo>(`${this.baseUrl}/system-info`);
  }

  /**
   * Debug endpoint to verify current subprocess config state.
   */
  debug(): Observable<{inMemoryEnabled: boolean, configuration: SubprocessConfigResponse, launcherAvailable: boolean}> {
    return this.http.get<{inMemoryEnabled: boolean, configuration: SubprocessConfigResponse, launcherAvailable: boolean}>(`${this.baseUrl}/debug`);
  }

  /**
   * Validate a native executable path.
   */
  validateNativeExecutable(executablePath: string): Observable<NativeExecutableValidation> {
    return this.http.post<NativeExecutableValidation>(`${this.baseUrl}/validate-native-executable`, { executablePath });
  }

  /**
   * Get native mode options.
   */
  getNativeModeOptions(): Observable<NativeModeOption[]> {
    return this.http.get<NativeModeOption[]>(`${this.baseUrl}/native-mode-options`);
  }

  /**
   * Get native image runtime information.
   */
  getNativeImageInfo(): Observable<NativeImageInfo> {
    return this.http.get<NativeImageInfo>(`${this.baseUrl}/native-image-info`);
  }

  // ==================== Subprocess Restart Methods ====================
  // These methods allow restarting subprocesses with updated ND4J environment variables

  /**
   * Restart the embedding subprocess with updated ND4J environment variables.
   * This will stop the current subprocess and start a new one that inherits
   * the current system properties (including any newly set ND4J config).
   *
   * @param additionalEnvVars Optional additional environment variables to set before restart
   * @returns Observable with the restart result and subprocess status
   */
  restartEmbeddingSubprocess(additionalEnvVars?: Record<string, string>): Observable<SubprocessRestartResult> {
    return this.http.post<SubprocessRestartResult>(
      `${this.baseUrl}/restart-embedding-subprocess`,
      additionalEnvVars || {}
    );
  }

  /**
   * Get the current embedding subprocess status.
   * Returns detailed information about the subprocess state, model loading,
   * and ND4J environment.
   */
  getEmbeddingSubprocessStatus(): Observable<EmbeddingSubprocessStatus> {
    return this.http.get<EmbeddingSubprocessStatus>(`${this.baseUrl}/embedding-subprocess-status`);
  }

  /**
   * Get the ND4J environment variables that will be passed to subprocesses.
   * Useful for debugging what configuration the subprocess will inherit.
   */
  getNd4jSubprocessEnvironment(): Observable<Nd4jSubprocessEnvironment> {
    return this.http.get<Nd4jSubprocessEnvironment>(`${this.baseUrl}/nd4j-subprocess-environment`);
  }

  /**
   * Update ND4J environment and optionally restart the embedding subprocess.
   * This is a convenience method that combines updating ND4J config with restarting.
   *
   * @param request The update and restart request
   * @returns Observable with the update result
   */
  updateNd4jAndRestart(request: UpdateNd4jAndRestartRequest): Observable<UpdateNd4jAndRestartResult> {
    return this.http.post<UpdateNd4jAndRestartResult>(`${this.baseUrl}/update-nd4j-and-restart`, request);
  }

  /**
   * Stop the embedding subprocess gracefully.
   */
  stopEmbeddingSubprocess(): Observable<SubprocessStopResult> {
    return this.http.post<SubprocessStopResult>(`${this.baseUrl}/stop-embedding-subprocess`, {});
  }

  // ==================== Debug Mode Methods ====================

  /**
   * Get available debug options for subprocess.
   * Returns tool modes (mutually exclusive), additive options (combinable),
   * valgrind settings, and legacy modes.
   */
  getAvailableDebugModes(): Observable<DebugModesResponse> {
    return this.http.get<DebugModesResponse>(`${this.baseUrl}/debug-modes`);
  }

  /**
   * Get current debug configuration for the embedding subprocess.
   */
  getDebugConfig(): Observable<DebugConfigResponse> {
    return this.http.get<DebugConfigResponse>(`${this.baseUrl}/debug-config`);
  }

  /**
   * Set the debug mode for the embedding subprocess.
   * This will be applied on the next restart.
   *
   * @param request The debug configuration to set
   */
  setDebugConfig(request: SetDebugConfigRequest): Observable<SetDebugConfigResult> {
    return this.http.post<SetDebugConfigResult>(`${this.baseUrl}/debug-config`, request);
  }

  /**
   * Restart the embedding subprocess with the specified debug configuration.
   * This combines setting the debug mode and restarting in one call.
   *
   * @param request The restart request with debug options
   */
  restartWithDebug(request: RestartWithDebugRequest): Observable<RestartWithDebugResult> {
    return this.http.post<RestartWithDebugResult>(`${this.baseUrl}/restart-with-debug`, request);
  }
}

// ==================== Subprocess Restart Types ====================

/**
 * Result of a subprocess restart operation.
 */
export interface SubprocessRestartResult {
  success: boolean;
  message?: string;
  error?: string;
  nd4jStateBefore?: Record<string, any>;
  subprocessStatus?: SubprocessDetailedStatus;
  appliedEnvironmentVariables?: Record<string, string>;
}

/**
 * Detailed status of a subprocess.
 */
export interface SubprocessDetailedStatus {
  modelId?: string;
  initialized?: boolean;
  modelSource?: string;
  loadingPhase?: string;
  loadingMessage?: string;
  subprocessRunning?: boolean;
  subprocessModelLoaded?: boolean;
  subprocessDimensions?: number;
  subprocessEncoderType?: string;
  subprocessModelId?: string;
  totalEmbeddingsProcessed?: number;
  launchMode?: string;
  isNativeMode?: boolean;
  lastCrashReason?: string;
  error?: string;
  errorRetriable?: boolean;
}

/**
 * Embedding subprocess status response.
 */
export interface EmbeddingSubprocessStatus {
  available: boolean;
  error?: string;
  modelId?: string;
  initialized?: boolean;
  modelSource?: string;
  loadingPhase?: string;
  loadingMessage?: string;
  subprocessRunning?: boolean;
  subprocessModelLoaded?: boolean;
  subprocessDimensions?: number;
  subprocessEncoderType?: string;
  totalEmbeddingsProcessed?: number;
  launchMode?: string;
  isNativeMode?: boolean;
  lastCrashReason?: string;
  nd4jEnvironment?: Record<string, any>;
}

/**
 * ND4J subprocess environment information.
 */
export interface Nd4jSubprocessEnvironment {
  systemProperties?: Record<string, string>;
  nd4jEnvironment?: Record<string, any>;
  configServiceConfiguration?: Record<string, any>;
}

/**
 * Request for updating ND4J and optionally restarting subprocess.
 */
export interface UpdateNd4jAndRestartRequest {
  environmentVariables?: Record<string, string>;
  nd4jConfig?: Record<string, any>;
  restartEmbeddingSubprocess?: boolean;
}

/**
 * Result of update ND4J and restart operation.
 */
export interface UpdateNd4jAndRestartResult {
  success: boolean;
  error?: string;
  environmentVariablesUpdated?: number;
  nd4jConfigUpdated?: boolean;
  nd4jStateBefore?: Record<string, any>;
  restartSuccess?: boolean;
  restartSkipped?: boolean;
  restartReason?: string;
  subprocessStatus?: SubprocessDetailedStatus;
}

/**
 * Result of stopping a subprocess.
 */
export interface SubprocessStopResult {
  success: boolean;
  message?: string;
  error?: string;
  subprocessStatus?: SubprocessDetailedStatus;
}

// ==================== Debug Mode Types ====================

/**
 * Tool mode information (mutually exclusive - only one can wrap the JVM).
 */
export interface ToolModeInfo {
  value: string;
  name: string;
  description: string;
  requiresCuda: boolean;
}

/**
 * Additive option information (can combine multiple).
 */
export interface AdditiveOptionInfo {
  key: string;
  label: string;
  description: string;
  jvmArg: string;
}

/**
 * Valgrind option information.
 */
export interface ValgrindOptionInfo {
  key: string;
  label: string;
  description: string;
  default: boolean | string;
}

/**
 * Debug modes response from /debug-modes endpoint.
 * Contains tool modes, additive options, valgrind options, and legacy modes.
 */
export interface DebugModesResponse {
  // Tool modes - mutually exclusive (only one can wrap the JVM)
  toolModes: ToolModeInfo[];
  // Additive options - can enable multiple simultaneously
  additiveOptions: AdditiveOptionInfo[];
  // Valgrind-specific options
  valgrindOptions: {
    generateSuppressions: ValgrindOptionInfo;
    libnd4jSuppressionFile: ValgrindOptionInfo;
  };
  // Legacy modes (deprecated, for backwards compatibility)
  legacyModes: ToolModeInfo[];
}

/**
 * Legacy debug mode info (deprecated).
 * @deprecated Use ToolModeInfo instead
 */
export interface DebugModeInfo {
  value: string;
  name: string;
  description: string;
  requiresCuda: boolean;
}

/**
 * Current debug configuration response.
 * Contains full configuration including tool mode, additive options,
 * system environment variables, and ND4J environment configuration.
 */
export interface DebugConfigResponse {
  available: boolean;
  error?: string;

  // Tool mode (mutually exclusive)
  toolMode?: string;
  toolModeDescription?: string;

  // Additive options (can combine multiple)
  additiveOptions?: {
    verboseJni: boolean;
    nativeMemoryTracking: boolean;
    extensiveErrorReports: boolean;
    disableJit: boolean;
  };

  // Valgrind suppression settings
  valgrindSettings?: {
    generateSuppressions: boolean;
    libnd4jSuppressionFile: string | null;
  };

  // Log directory
  logDirectory?: string;

  // Extra JVM args
  extraJvmArgs?: string[];

  // System environment variables (LD_PRELOAD, MALLOC_CHECK_, etc.)
  systemEnvironmentVariables?: Record<string, string>;

  // ND4J environment configuration (Nd4j.getEnvironment() settings)
  nd4jEnvironmentConfig?: Record<string, any>;

  // Preview of what will be applied
  commandPrefixPreview?: string[];
  jvmArgsPreview?: string[];
  envVarsPreview?: Record<string, string>;

  // Subprocess and ND4J state
  subprocessStatus?: SubprocessDetailedStatus;
  nd4jEnvironmentState?: Record<string, any>;

  // Legacy fields (deprecated)
  currentMode?: string;
  disableJit?: boolean;
  environmentVariables?: Record<string, string>;
}

/**
 * Request to set debug configuration.
 * Supports the full debug configuration including tool mode, additive options,
 * valgrind settings, and clear separation of system env vars vs ND4J env config.
 */
export interface SetDebugConfigRequest {
  // Tool mode (mutually exclusive - only one can wrap the JVM)
  toolMode: string;

  // Additive JVM options (can combine multiple)
  verboseJni?: boolean;
  nativeMemoryTracking?: boolean;
  extensiveErrorReports?: boolean;
  disableJit?: boolean;

  // Valgrind-specific settings
  generateValgrindSuppressions?: boolean;
  libnd4jSuppressionFile?: string;

  // Log directory
  logDirectory?: string;

  // Extra JVM args
  extraJvmArgs?: string[];

  // System environment variables (LD_PRELOAD, MALLOC_CHECK_, ASAN_OPTIONS, etc.)
  systemEnvironmentVariables?: Record<string, string>;

  // ND4J environment configuration (Nd4j.getEnvironment() settings)
  // Supported keys: maxThreads, maxMasterThreads, debug, verbose, profiling
  nd4jEnvironmentConfig?: Record<string, any>;

  // Legacy field for backwards compatibility
  /** @deprecated Use toolMode instead */
  mode?: string;
  /** @deprecated Use systemEnvironmentVariables instead */
  environmentVariables?: Record<string, string>;
}

/**
 * Valgrind suppression file information in response.
 */
export interface ValgrindSuppressionInfo {
  willGenerateDynamicSuppression?: boolean;
  generatingDynamicSuppression?: boolean;
  dynamicSuppressionNote?: string;
  description?: string;
  purpose?: string;
  cleanup?: string;
  errorTypesSuppressed?: string[];
  leakKindsSuppressed?: string[];
  externalSuppressionFile?: string;
  externalFileNote?: string;
  // Additional properties for display
  dynamicSuppressionFile?: string;
  libjvmPath?: string;
  suppressedErrorTypes?: string[];  // Alias for errorTypesSuppressed
  suppressedLeakKinds?: string[];   // Alias for leakKindsSuppressed
  libnd4jSuppressionFile?: string;  // Alias for externalSuppressionFile
}

/**
 * Result of setting debug configuration.
 */
export interface SetDebugConfigResult {
  success: boolean;
  error?: string;

  // Tool mode info
  toolMode?: string;
  toolModeDescription?: string;
  configDescription?: string;

  // What will be applied (preview)
  commandPrefix?: string[];
  jvmArgs?: string[];
  systemEnvVars?: Record<string, string>;

  // Valgrind suppression info (shown when valgrind is selected)
  valgrindSuppressionInfo?: ValgrindSuppressionInfo;

  message?: string;

  // Legacy fields
  mode?: string;
  modeDescription?: string;
  envVars?: Record<string, string>;
}

/**
 * Request to restart with debug mode.
 * Same structure as SetDebugConfigRequest plus systemProperties for JVM inheritance.
 */
export interface RestartWithDebugRequest {
  // Tool mode (mutually exclusive - only one can wrap the JVM)
  toolMode: string;

  // Additive JVM options (can combine multiple)
  verboseJni?: boolean;
  nativeMemoryTracking?: boolean;
  extensiveErrorReports?: boolean;
  disableJit?: boolean;

  // Valgrind-specific settings
  generateValgrindSuppressions?: boolean;
  libnd4jSuppressionFile?: string;

  // Log directory
  logDirectory?: string;

  // Extra JVM args
  extraJvmArgs?: string[];

  // System environment variables (LD_PRELOAD, MALLOC_CHECK_, ASAN_OPTIONS, etc.)
  systemEnvironmentVariables?: Record<string, string>;

  // ND4J environment configuration (Nd4j.getEnvironment() settings)
  // Supported keys: maxThreads, maxMasterThreads, debug, verbose, profiling
  nd4jEnvironmentConfig?: Record<string, any>;

  // System properties to set before restart (subprocess inherits via ProcessBuilder)
  systemProperties?: Record<string, string>;

  // Legacy fields for backwards compatibility
  /** @deprecated Use toolMode instead */
  debugMode?: string;
  /** @deprecated Use systemEnvironmentVariables instead */
  environmentVariables?: Record<string, string>;
}

/**
 * Result of restart with debug mode.
 */
export interface RestartWithDebugResult {
  success: boolean;
  error?: string;

  // Tool mode info
  toolMode?: string;
  toolModeDescription?: string;
  configDescription?: string;

  // What was applied
  commandPrefix?: string[];
  jvmArgs?: string[];
  systemEnvVars?: Record<string, string>;

  // ND4J environment changes applied
  nd4jEnvironmentChangesApplied?: Record<string, any>;
  nd4jConfig?: Record<string, any>;  // Alias for display
  systemPropertiesApplied?: Record<string, string>;

  // Valgrind suppression info (user notification)
  valgrindSuppressionInfo?: ValgrindSuppressionInfo;

  // Result
  message?: string;
  subprocessStatus?: SubprocessDetailedStatus;
  nd4jEnvironmentState?: Record<string, any>;

  // Legacy fields
  debugMode?: string;
  debugDescription?: string;
  envVars?: Record<string, string>;
}
