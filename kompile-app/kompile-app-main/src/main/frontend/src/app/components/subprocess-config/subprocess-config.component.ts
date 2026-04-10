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

import { Component, OnInit, OnDestroy, ChangeDetectionStrategy, ChangeDetectorRef } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { MatCardModule } from '@angular/material/card';
import { MatSelectModule } from '@angular/material/select';
import { MatSliderModule } from '@angular/material/slider';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatTableModule } from '@angular/material/table';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatChipsModule } from '@angular/material/chips';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSlideToggleModule } from '@angular/material/slide-toggle';
import { MatToolbarModule } from '@angular/material/toolbar';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';
import { MatTooltipModule } from '@angular/material/tooltip';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatExpansionModule } from '@angular/material/expansion';
import { MatTabsModule } from '@angular/material/tabs';
import { Subject, interval } from 'rxjs';
import { takeUntil } from 'rxjs/operators';

import {
  SubprocessConfigService,
  SubprocessConfigResponse,
  SubprocessConfigUpdate,
  SubprocessStatus,
  SystemInfo,
  JavaPathValidation,
  NativeExecutableValidation,
  NativeModeOption,
  NativeImageInfo,
  EmbeddingSubprocessStatus,
  Nd4jSubprocessEnvironment,
  SubprocessRestartResult,
  DebugModeInfo,
  DebugModesResponse,
  ToolModeInfo,
  AdditiveOptionInfo,
  DebugConfigResponse,
  RestartWithDebugRequest,
  RestartWithDebugResult,
  ValgrindSuppressionInfo
} from '../../services/subprocess-config.service';

@Component({
  selector: 'app-subprocess-config',
  standalone: true,
  imports: [
    CommonModule,
    FormsModule,
    MatCardModule,
    MatSelectModule,
    MatSliderModule,
    MatButtonModule,
    MatIconModule,
    MatTableModule,
    MatProgressBarModule,
    MatChipsModule,
    MatFormFieldModule,
    MatInputModule,
    MatSlideToggleModule,
    MatToolbarModule,
    MatSnackBarModule,
    MatTooltipModule,
    MatProgressSpinnerModule,
    MatExpansionModule,
    MatTabsModule
  ],
  templateUrl: './subprocess-config.component.html',
  styleUrls: ['./subprocess-config.component.css'],
  changeDetection: ChangeDetectionStrategy.OnPush
})
export class SubprocessConfigComponent implements OnInit, OnDestroy {
  private destroy$ = new Subject<void>();

  // Loading state
  isLoading = false;
  isSaving = false;
  isValidating = false;

  // Configuration
  config: SubprocessConfigResponse | null = null;
  heapSizeOptions: string[] = [];

  // Edit form - JVM settings
  editEnabled = true;
  editJavaPath = 'java';
  editHeapSize = '4g';
  editOffHeapMaxBytes = '';
  editTimeoutMinutes = 60;
  editHeartbeatIntervalSeconds = 10;
  editStaleThresholdSeconds = 120;

  // Edit form - ND4J / Pipeline settings
  editQueueCapacity = 1000;
  editParallelIndexing = true;
  editIndexingWorkers = 4;
  editIndexingBatchAccumulationSize = 8;
  editEmbeddingThreads = 1;

  // Edit form - Restart configuration
  editRestartEnabled = true;
  editMaxRestartAttempts = 3;
  editInitialBackoffMs = 5000;
  editBackoffMultiplier = 2.0;
  editHeapIncreaseFactor = 1.25;
  editSystemRamSafetyMargin = 0.15;

  // Edit form - Stall detection
  editRestartOnStall = true;
  editRestartOnTimeout = true;
  editStallDetectionThresholdSeconds = 300;
  editProgressStallWarningSeconds = 60;

  // Edit form - Native executable configuration
  editNativeExecutableMode = 'auto';
  editNativeExecutablePath = '';
  editIngestExecutablePath = '';
  editVectorPopulationExecutablePath = '';
  editEmbeddingExecutablePath = '';
  editModelInitExecutablePath = '';
  editSubprocessTypeFlag = '--subprocess=';

  // Edit form - Memory watchdog thresholds
  editOffHeapThresholdPercent = 80;
  editOffHeapCriticalPercent = 90;
  editOffHeapKillThresholdPercent = 95;
  editGpuMemoryThresholdPercent = 75;
  editGpuMemoryCriticalPercent = 85;
  editGpuMemoryKillThresholdPercent = 92;

  // Native mode options
  nativeModeOptions: NativeModeOption[] = [];

  // Native image info
  nativeImageInfo: NativeImageInfo | null = null;

  // Java path validation
  javaPathValid: boolean | null = null;
  javaVersionInfo = '';

  // Native executable path validation
  nativeExecutableValid: boolean | null = null;
  nativeExecutableVersionInfo = '';
  isValidatingNativeExecutable = false;

  // Active processes
  activeProcesses: SubprocessStatus[] = [];
  displayedColumns = ['taskId', 'fileName', 'phase', 'progress', 'memory', 'elapsed', 'actions'];

  // System info
  systemInfo: SystemInfo | null = null;

  // Embedding subprocess management
  embeddingSubprocessStatus: EmbeddingSubprocessStatus | null = null;
  nd4jEnvironment: Nd4jSubprocessEnvironment | null = null;
  isLoadingSubprocessStatus = false;
  isLoadingNd4jEnvironment = false;
  isRestartingSubprocess = false;
  lastRestartResult: SubprocessRestartResult | null = null;
  customEnvVars: {key: string, value: string}[] = [];

  // Debug mode configuration
  availableDebugModes: DebugModeInfo[] = [];  // Legacy support
  debugModesResponse: DebugModesResponse | null = null;  // New structure
  currentDebugConfig: DebugConfigResponse | null = null;
  isLoadingDebugModes = false;
  isRestartingWithDebug = false;
  lastDebugRestartResult: RestartWithDebugResult | null = null;

  // Tool modes (mutually exclusive - only one can wrap the JVM)
  availableToolModes: ToolModeInfo[] = [];
  selectedToolMode = 'none';

  // Additive options (can combine multiple)
  additiveOptions: AdditiveOptionInfo[] = [];
  debugVerboseJni = false;
  debugNativeMemoryTracking = false;
  debugExtensiveErrorReports = false;
  debugDisableJit = false;

  // Valgrind suppression settings
  debugGenerateValgrindSuppressions = true;
  debugLibnd4jSuppressionFile = '';

  // Log directory
  debugLogDirectory = './logs/debug';

  // Extra JVM args
  debugExtraJvmArgs: string[] = [];
  newJvmArg = '';

  // System environment variables (LD_PRELOAD, MALLOC_CHECK_, etc.)
  debugSystemEnvVars: {key: string, value: string}[] = [];

  // ND4J environment configuration (Nd4j.getEnvironment() settings)
  debugNd4jEnvConfig: {key: string, value: any}[] = [];

  // Legacy - will be migrated
  debugEnvVars: {key: string, value: string}[] = [];

  // Selected debug mode (legacy support)
  selectedDebugMode = 'none';

  constructor(
    private configService: SubprocessConfigService,
    private snackBar: MatSnackBar,
    private cdr: ChangeDetectorRef
  ) {}

  ngOnInit(): void {
    this.loadConfiguration();
    this.loadHeapSizeOptions();
    this.loadActiveProcesses();
    this.loadSystemInfo();
    this.loadNativeModeOptions();
    this.loadNativeImageInfo();
    this.loadEmbeddingSubprocessStatus();
    this.loadAvailableDebugModes();

    // Poll active processes and subprocess status every 5 seconds
    interval(5000)
      .pipe(takeUntil(this.destroy$))
      .subscribe(() => {
        this.loadActiveProcesses();
        // Also refresh subprocess status periodically
        if (!this.isRestartingSubprocess && !this.isRestartingWithDebug) {
          this.loadEmbeddingSubprocessStatus();
        }
      });
  }

  ngOnDestroy(): void {
    this.destroy$.next();
    this.destroy$.complete();
  }

  loadConfiguration(): void {
    this.isLoading = true;
    this.cdr.markForCheck();

    this.configService.getConfiguration().subscribe({
      next: (config) => {
        this.config = config;
        // JVM settings
        this.editEnabled = config.enabled;
        this.editJavaPath = config.javaPath;
        this.editHeapSize = config.heapSize;
        this.editOffHeapMaxBytes = config.offHeapMaxBytes || '';
        this.editTimeoutMinutes = config.timeoutMinutes;
        this.editHeartbeatIntervalSeconds = config.heartbeatIntervalSeconds;
        this.editStaleThresholdSeconds = config.staleThresholdSeconds;
        // ND4J / Pipeline settings
        this.editQueueCapacity = config.queueCapacity;
        this.editParallelIndexing = config.parallelIndexing;
        this.editIndexingWorkers = config.indexingWorkers;
        this.editIndexingBatchAccumulationSize = config.indexingBatchAccumulationSize;
        this.editEmbeddingThreads = config.embeddingThreads;
        // Restart configuration
        this.editRestartEnabled = config.restartEnabled;
        this.editMaxRestartAttempts = config.maxRestartAttempts;
        this.editInitialBackoffMs = config.initialBackoffMs;
        this.editBackoffMultiplier = config.backoffMultiplier;
        this.editHeapIncreaseFactor = config.heapIncreaseFactor;
        this.editSystemRamSafetyMargin = config.systemRamSafetyMargin;
        // Stall detection
        this.editRestartOnStall = config.restartOnStall;
        this.editRestartOnTimeout = config.restartOnTimeout;
        this.editStallDetectionThresholdSeconds = config.stallDetectionThresholdSeconds;
        this.editProgressStallWarningSeconds = config.progressStallWarningSeconds;
        // Native executable configuration
        this.editNativeExecutableMode = config.nativeExecutableMode || 'auto';
        this.editNativeExecutablePath = config.nativeExecutablePath || '';
        this.editIngestExecutablePath = config.ingestExecutablePath || '';
        this.editVectorPopulationExecutablePath = config.vectorPopulationExecutablePath || '';
        this.editEmbeddingExecutablePath = config.embeddingExecutablePath || '';
        this.editModelInitExecutablePath = config.modelInitExecutablePath || '';
        this.editSubprocessTypeFlag = config.subprocessTypeFlag || '--subprocess=';
        // Memory watchdog thresholds
        this.editOffHeapThresholdPercent = config.offHeapThresholdPercent ?? 80;
        this.editOffHeapCriticalPercent = config.offHeapCriticalPercent ?? 90;
        this.editOffHeapKillThresholdPercent = config.offHeapKillThresholdPercent ?? 95;
        this.editGpuMemoryThresholdPercent = config.gpuMemoryThresholdPercent ?? 75;
        this.editGpuMemoryCriticalPercent = config.gpuMemoryCriticalPercent ?? 85;
        this.editGpuMemoryKillThresholdPercent = config.gpuMemoryKillThresholdPercent ?? 92;
        this.isLoading = false;
        this.cdr.markForCheck();
      },
      error: (err) => {
        this.showError('Failed to load configuration: ' + err.message);
        this.isLoading = false;
        this.cdr.markForCheck();
      }
    });
  }

  loadHeapSizeOptions(): void {
    this.configService.getHeapSizeOptions().subscribe({
      next: (options) => {
        this.heapSizeOptions = options;
        this.cdr.markForCheck();
      },
      error: () => {
        // Fallback: generate options based on available memory
        const memGb = this.getMemoryGb();
        const maxGb = Math.min(Math.floor(memGb * 0.8), 1024); // 80% of RAM, max 1TB
        const options = ['1g', '2g', '4g', '6g', '8g'];
        if (maxGb >= 12) options.push('12g');
        if (maxGb >= 16) options.push('16g');
        if (maxGb >= 24) options.push('24g');
        if (maxGb >= 32) options.push('32g');
        if (maxGb >= 48) options.push('48g');
        if (maxGb >= 64) options.push('64g');
        if (maxGb >= 96) options.push('96g');
        if (maxGb >= 128) options.push('128g');
        if (maxGb >= 192) options.push('192g');
        if (maxGb >= 256) options.push('256g');
        if (maxGb >= 384) options.push('384g');
        if (maxGb >= 512) options.push('512g');
        if (maxGb >= 768) options.push('768g');
        if (maxGb >= 1024) options.push('1024g');
        this.heapSizeOptions = options;
        this.cdr.markForCheck();
      }
    });
  }

  loadActiveProcesses(): void {
    this.configService.getActiveProcesses().subscribe({
      next: (processes) => {
        this.activeProcesses = processes;
        this.cdr.markForCheck();
      },
      error: () => {
        // Silently ignore errors on polling
      }
    });
  }

  loadSystemInfo(): void {
    this.configService.getSystemInfo().subscribe({
      next: (info) => {
        this.systemInfo = info;
        this.cdr.markForCheck();
      },
      error: () => {
        // Silently ignore
      }
    });
  }

  loadNativeModeOptions(): void {
    this.configService.getNativeModeOptions().subscribe({
      next: (options) => {
        this.nativeModeOptions = options;
        this.cdr.markForCheck();
      },
      error: () => {
        // Fallback options
        this.nativeModeOptions = [
          { value: 'auto', label: 'Auto-detect', description: 'Automatically detect based on runtime context' },
          { value: 'jvm', label: 'JVM/Classpath', description: 'Always use java -cp for subprocess launching' },
          { value: 'native', label: 'Native Executable', description: 'Always use native executable for subprocess launching' }
        ];
        this.cdr.markForCheck();
      }
    });
  }

  loadNativeImageInfo(): void {
    this.configService.getNativeImageInfo().subscribe({
      next: (info) => {
        this.nativeImageInfo = info;
        this.cdr.markForCheck();
      },
      error: () => {
        // Silently ignore
      }
    });
  }

  validateNativeExecutable(): void {
    this.isValidatingNativeExecutable = true;
    this.nativeExecutableValid = null;
    this.nativeExecutableVersionInfo = '';
    this.cdr.markForCheck();

    this.configService.validateNativeExecutable(this.editNativeExecutablePath).subscribe({
      next: (result) => {
        this.nativeExecutableValid = result.valid;
        this.nativeExecutableVersionInfo = result.versionOutput || result.error || '';
        this.isValidatingNativeExecutable = false;
        this.cdr.markForCheck();
      },
      error: (err) => {
        this.nativeExecutableValid = false;
        this.nativeExecutableVersionInfo = err.message;
        this.isValidatingNativeExecutable = false;
        this.cdr.markForCheck();
      }
    });
  }

  saveConfiguration(): void {
    this.isSaving = true;
    this.cdr.markForCheck();

    const update: SubprocessConfigUpdate = {
      // JVM settings
      enabled: this.editEnabled,
      javaPath: this.editJavaPath,
      heapSize: this.editHeapSize,
      offHeapMaxBytes: this.editOffHeapMaxBytes,
      timeoutMinutes: this.editTimeoutMinutes,
      heartbeatIntervalSeconds: this.editHeartbeatIntervalSeconds,
      staleThresholdSeconds: this.editStaleThresholdSeconds,
      // ND4J / Pipeline settings
      queueCapacity: this.editQueueCapacity,
      parallelIndexing: this.editParallelIndexing,
      indexingWorkers: this.editIndexingWorkers,
      indexingBatchAccumulationSize: this.editIndexingBatchAccumulationSize,
      embeddingThreads: this.editEmbeddingThreads,
      // Restart configuration
      restartEnabled: this.editRestartEnabled,
      maxRestartAttempts: this.editMaxRestartAttempts,
      initialBackoffMs: this.editInitialBackoffMs,
      backoffMultiplier: this.editBackoffMultiplier,
      heapIncreaseFactor: this.editHeapIncreaseFactor,
      systemRamSafetyMargin: this.editSystemRamSafetyMargin,
      // Stall detection
      restartOnStall: this.editRestartOnStall,
      restartOnTimeout: this.editRestartOnTimeout,
      stallDetectionThresholdSeconds: this.editStallDetectionThresholdSeconds,
      progressStallWarningSeconds: this.editProgressStallWarningSeconds,
      // Native executable configuration
      nativeExecutableMode: this.editNativeExecutableMode,
      nativeExecutablePath: this.editNativeExecutablePath,
      ingestExecutablePath: this.editIngestExecutablePath,
      vectorPopulationExecutablePath: this.editVectorPopulationExecutablePath,
      embeddingExecutablePath: this.editEmbeddingExecutablePath,
      modelInitExecutablePath: this.editModelInitExecutablePath,
      subprocessTypeFlag: this.editSubprocessTypeFlag,
      // Memory watchdog thresholds
      offHeapThresholdPercent: this.editOffHeapThresholdPercent,
      offHeapCriticalPercent: this.editOffHeapCriticalPercent,
      offHeapKillThresholdPercent: this.editOffHeapKillThresholdPercent,
      gpuMemoryThresholdPercent: this.editGpuMemoryThresholdPercent,
      gpuMemoryCriticalPercent: this.editGpuMemoryCriticalPercent,
      gpuMemoryKillThresholdPercent: this.editGpuMemoryKillThresholdPercent
    };

    this.configService.updateConfiguration(update).subscribe({
      next: (config) => {
        this.config = config;
        this.isSaving = false;
        this.showSuccess('Configuration saved (persists across restarts)');
        this.cdr.markForCheck();
      },
      error: (err) => {
        this.isSaving = false;
        this.showError('Failed to save configuration: ' + (err.error?.error || err.message));
        this.cdr.markForCheck();
      }
    });
  }

  resetConfiguration(): void {
    this.isSaving = true;
    this.cdr.markForCheck();

    this.configService.resetConfiguration().subscribe({
      next: (config) => {
        this.config = config;
        // JVM settings
        this.editEnabled = config.enabled;
        this.editJavaPath = config.javaPath;
        this.editHeapSize = config.heapSize;
        this.editOffHeapMaxBytes = config.offHeapMaxBytes || '';
        this.editTimeoutMinutes = config.timeoutMinutes;
        this.editHeartbeatIntervalSeconds = config.heartbeatIntervalSeconds;
        this.editStaleThresholdSeconds = config.staleThresholdSeconds;
        // ND4J / Pipeline settings
        this.editQueueCapacity = config.queueCapacity;
        this.editParallelIndexing = config.parallelIndexing;
        this.editIndexingWorkers = config.indexingWorkers;
        this.editIndexingBatchAccumulationSize = config.indexingBatchAccumulationSize;
        this.editEmbeddingThreads = config.embeddingThreads;
        // Restart configuration
        this.editRestartEnabled = config.restartEnabled;
        this.editMaxRestartAttempts = config.maxRestartAttempts;
        this.editInitialBackoffMs = config.initialBackoffMs;
        this.editBackoffMultiplier = config.backoffMultiplier;
        this.editHeapIncreaseFactor = config.heapIncreaseFactor;
        this.editSystemRamSafetyMargin = config.systemRamSafetyMargin;
        // Stall detection
        this.editRestartOnStall = config.restartOnStall;
        this.editRestartOnTimeout = config.restartOnTimeout;
        this.editStallDetectionThresholdSeconds = config.stallDetectionThresholdSeconds;
        this.editProgressStallWarningSeconds = config.progressStallWarningSeconds;
        // Native executable configuration
        this.editNativeExecutableMode = config.nativeExecutableMode || 'auto';
        this.editNativeExecutablePath = config.nativeExecutablePath || '';
        this.editIngestExecutablePath = config.ingestExecutablePath || '';
        this.editVectorPopulationExecutablePath = config.vectorPopulationExecutablePath || '';
        this.editEmbeddingExecutablePath = config.embeddingExecutablePath || '';
        this.editModelInitExecutablePath = config.modelInitExecutablePath || '';
        this.editSubprocessTypeFlag = config.subprocessTypeFlag || '--subprocess=';
        // Memory watchdog thresholds
        this.editOffHeapThresholdPercent = config.offHeapThresholdPercent ?? 80;
        this.editOffHeapCriticalPercent = config.offHeapCriticalPercent ?? 90;
        this.editOffHeapKillThresholdPercent = config.offHeapKillThresholdPercent ?? 95;
        this.editGpuMemoryThresholdPercent = config.gpuMemoryThresholdPercent ?? 75;
        this.editGpuMemoryCriticalPercent = config.gpuMemoryCriticalPercent ?? 85;
        this.editGpuMemoryKillThresholdPercent = config.gpuMemoryKillThresholdPercent ?? 92;
        this.isSaving = false;
        this.showSuccess('Configuration reset to defaults');
        this.cdr.markForCheck();
      },
      error: (err) => {
        this.isSaving = false;
        this.showError('Failed to reset configuration: ' + err.message);
        this.cdr.markForCheck();
      }
    });
  }

  toggleEnabled(): void {
    if (this.editEnabled) {
      this.configService.enable().subscribe({
        next: (config) => {
          this.config = config;
          this.showSuccess('Subprocess mode enabled');
          this.cdr.markForCheck();
        },
        error: (err) => this.showError('Failed to enable: ' + err.message)
      });
    } else {
      this.configService.disable().subscribe({
        next: (config) => {
          this.config = config;
          this.showSuccess('Subprocess mode disabled (using in-process mode)');
          this.cdr.markForCheck();
        },
        error: (err) => this.showError('Failed to disable: ' + err.message)
      });
    }
  }

  validateJavaPath(): void {
    this.isValidating = true;
    this.javaPathValid = null;
    this.javaVersionInfo = '';
    this.cdr.markForCheck();

    this.configService.validateJavaPath(this.editJavaPath).subscribe({
      next: (result) => {
        this.javaPathValid = result.valid;
        this.javaVersionInfo = result.versionInfo || result.error || '';
        this.isValidating = false;
        this.cdr.markForCheck();
      },
      error: (err) => {
        this.javaPathValid = false;
        this.javaVersionInfo = err.message;
        this.isValidating = false;
        this.cdr.markForCheck();
      }
    });
  }

  cancelProcess(taskId: string): void {
    this.configService.cancelProcess(taskId).subscribe({
      next: (result) => {
        if (result.cancelled) {
          this.showSuccess('Process cancelled: ' + taskId);
        } else {
          this.showError(result.message);
        }
        this.loadActiveProcesses();
      },
      error: (err) => this.showError('Failed to cancel: ' + err.message)
    });
  }

  getElapsedTime(status: SubprocessStatus): string {
    // Parse ISO duration or return as-is
    if (status.elapsedTime) {
      // Try to parse ISO 8601 duration
      const match = status.elapsedTime.match(/PT(\d+H)?(\d+M)?(\d+\.?\d*S)?/);
      if (match) {
        const hours = match[1] ? parseInt(match[1]) : 0;
        const minutes = match[2] ? parseInt(match[2]) : 0;
        const seconds = match[3] ? parseFloat(match[3]) : 0;
        if (hours > 0) {
          return `${hours}h ${minutes}m`;
        } else if (minutes > 0) {
          return `${minutes}m ${Math.floor(seconds)}s`;
        } else {
          return `${Math.floor(seconds)}s`;
        }
      }
    }
    return status.elapsedTime || '-';
  }

  getMemoryGb(): number {
    if (!this.config) return 0;
    return Math.round(this.config.availableMemoryMb / 1024 * 10) / 10;
  }

  getRecommendedHeapSize(): string {
    const memGb = this.getMemoryGb();
    // Recommend ~50% of available memory as a safe default
    if (memGb <= 4) return '1g';
    if (memGb <= 8) return '2g';
    if (memGb <= 16) return '4g';
    if (memGb <= 32) return '8g';
    if (memGb <= 64) return '24g';
    if (memGb <= 128) return '48g';
    if (memGb <= 256) return '96g';
    if (memGb <= 512) return '192g';
    if (memGb <= 1024) return '384g';
    return '512g';
  }

  getMaxHeapSize(): string {
    // Calculate 80% of system RAM as max heap
    const memGb = this.getMemoryGb();
    const maxGb = Math.min(Math.floor(memGb * 0.8), 1024); // Cap at 1TB
    return maxGb + 'g';
  }

  formatBytes(bytes: number): string {
    if (!bytes || bytes === 0) return '0 B';
    const units = ['B', 'KB', 'MB', 'GB', 'TB'];
    const i = Math.floor(Math.log(bytes) / Math.log(1024));
    return (bytes / Math.pow(1024, i)).toFixed(1) + ' ' + units[i];
  }

  getMemoryBarColor(percent: number): string {
    if (percent >= 90) return 'warn';
    if (percent >= 75) return 'accent';
    return 'primary';
  }

  private showSuccess(message: string): void {
    this.snackBar.open(message, 'Close', {
      duration: 3000,
      panelClass: ['success-snackbar']
    });
  }

  private showError(message: string): void {
    this.snackBar.open(message, 'Close', {
      duration: 5000,
      panelClass: ['error-snackbar']
    });
  }

  // ==================== Embedding Subprocess Management Methods ====================

  loadEmbeddingSubprocessStatus(): void {
    this.isLoadingSubprocessStatus = true;
    this.cdr.markForCheck();

    this.configService.getEmbeddingSubprocessStatus().subscribe({
      next: (status) => {
        this.embeddingSubprocessStatus = status;
        this.isLoadingSubprocessStatus = false;
        this.cdr.markForCheck();
      },
      error: (err) => {
        this.isLoadingSubprocessStatus = false;
        this.cdr.markForCheck();
        // Silently ignore errors during polling
      }
    });
  }

  loadNd4jEnvironment(): void {
    this.isLoadingNd4jEnvironment = true;
    this.cdr.markForCheck();

    this.configService.getNd4jSubprocessEnvironment().subscribe({
      next: (env) => {
        this.nd4jEnvironment = env;
        this.isLoadingNd4jEnvironment = false;
        this.cdr.markForCheck();
      },
      error: (err) => {
        this.showError('Failed to load ND4J environment: ' + err.message);
        this.isLoadingNd4jEnvironment = false;
        this.cdr.markForCheck();
      }
    });
  }

  restartEmbeddingSubprocess(): void {
    this.isRestartingSubprocess = true;
    this.lastRestartResult = null;
    this.cdr.markForCheck();

    // Build environment variables from custom vars
    const envVars: Record<string, string> = {};
    for (const env of this.customEnvVars) {
      if (env.key && env.key.trim()) {
        envVars[env.key.trim()] = env.value || '';
      }
    }

    this.configService.restartEmbeddingSubprocess(envVars).subscribe({
      next: (result) => {
        this.lastRestartResult = result;
        this.isRestartingSubprocess = false;
        if (result.success) {
          this.showSuccess('Embedding subprocess restarted successfully');
          // Reload status
          this.loadEmbeddingSubprocessStatus();
        } else {
          this.showError('Restart failed: ' + (result.error || 'Unknown error'));
        }
        this.cdr.markForCheck();
      },
      error: (err) => {
        this.lastRestartResult = {
          success: false,
          error: err.message || 'Request failed'
        };
        this.isRestartingSubprocess = false;
        this.showError('Failed to restart subprocess: ' + err.message);
        this.cdr.markForCheck();
      }
    });
  }

  stopEmbeddingSubprocess(): void {
    this.isRestartingSubprocess = true;
    this.cdr.markForCheck();

    this.configService.stopEmbeddingSubprocess().subscribe({
      next: (result) => {
        this.isRestartingSubprocess = false;
        if (result.success) {
          this.showSuccess('Embedding subprocess stopped');
          this.loadEmbeddingSubprocessStatus();
        } else {
          this.showError('Stop failed: ' + (result.error || 'Unknown error'));
        }
        this.cdr.markForCheck();
      },
      error: (err) => {
        this.isRestartingSubprocess = false;
        this.showError('Failed to stop subprocess: ' + err.message);
        this.cdr.markForCheck();
      }
    });
  }

  // Environment variable management
  addEnvVar(): void {
    this.customEnvVars.push({ key: '', value: '' });
    this.cdr.markForCheck();
  }

  removeEnvVar(index: number): void {
    this.customEnvVars.splice(index, 1);
    this.cdr.markForCheck();
  }

  // Helper methods for displaying environment properties
  getEnvPropsArray(obj: Record<string, any> | undefined): {key: string, value: string}[] {
    if (!obj) return [];
    return Object.entries(obj).map(([key, value]) => ({
      key,
      value: typeof value === 'object' ? JSON.stringify(value) : String(value)
    }));
  }

  hasEnvProps(obj: Record<string, any> | undefined): boolean {
    return obj ? Object.keys(obj).length > 0 : false;
  }

  // ==================== Debug Mode Methods ====================

  loadAvailableDebugModes(): void {
    this.isLoadingDebugModes = true;
    this.cdr.markForCheck();

    this.configService.getAvailableDebugModes().subscribe({
      next: (response) => {
        this.debugModesResponse = response;

        // Extract tool modes and additive options from new structure
        if (response.toolModes) {
          this.availableToolModes = response.toolModes;
        }
        if (response.additiveOptions) {
          this.additiveOptions = response.additiveOptions;
        }

        // Legacy support - flatten to old format
        this.availableDebugModes = response.toolModes || [];

        this.isLoadingDebugModes = false;
        this.cdr.markForCheck();
      },
      error: (err) => {
        // Provide fallback tool modes
        this.availableToolModes = [
          { value: 'none', name: 'NONE', description: 'No debug tool', requiresCuda: false },
          { value: 'valgrind', name: 'VALGRIND', description: 'Valgrind full leak check', requiresCuda: false },
          { value: 'valgrind-minimal', name: 'VALGRIND_MINIMAL', description: 'Valgrind minimal (faster)', requiresCuda: false },
          { value: 'compute-sanitizer', name: 'COMPUTE_SANITIZER_MEMCHECK', description: 'CUDA memory checker (memcheck)', requiresCuda: true },
          { value: 'compute-sanitizer-race', name: 'COMPUTE_SANITIZER_RACECHECK', description: 'CUDA race condition checker', requiresCuda: true },
          { value: 'compute-sanitizer-init', name: 'COMPUTE_SANITIZER_INITCHECK', description: 'CUDA uninitialized memory checker', requiresCuda: true },
          { value: 'compute-sanitizer-sync', name: 'COMPUTE_SANITIZER_SYNCCHECK', description: 'CUDA synchronization checker', requiresCuda: true },
          { value: 'cuda-gdb', name: 'CUDA_GDB', description: 'CUDA debugger (interactive)', requiresCuda: true },
          { value: 'asan', name: 'ASAN', description: 'AddressSanitizer', requiresCuda: false },
          { value: 'efence', name: 'EFENCE', description: 'Electric Fence (use-after-free detection)', requiresCuda: false },
          { value: 'malloc-check', name: 'MALLOC_CHECK', description: 'glibc heap checking (MALLOC_CHECK_=3)', requiresCuda: false }
        ];

        // Fallback additive options
        this.additiveOptions = [
          { key: 'verboseJni', label: 'Verbose JNI', description: 'Enable verbose JNI logging', jvmArg: '-verbose:jni' },
          { key: 'nativeMemoryTracking', label: 'Native Memory Tracking', description: 'Enable JVM Native Memory Tracking', jvmArg: '-XX:NativeMemoryTracking=detail' },
          { key: 'extensiveErrorReports', label: 'Extensive Error Reports', description: 'Enable JVM extensive error reports', jvmArg: '-XX:+ExtensiveErrorReports' },
          { key: 'disableJit', label: 'Disable JIT', description: 'Disable JIT compilation for debugging', jvmArg: '-Djava.compiler=NONE' }
        ];

        // Legacy support
        this.availableDebugModes = this.availableToolModes;

        this.isLoadingDebugModes = false;
        this.cdr.markForCheck();
      }
    });
  }

  loadDebugConfig(): void {
    this.configService.getDebugConfig().subscribe({
      next: (config) => {
        this.currentDebugConfig = config;

        // New structure
        if (config.toolMode) {
          this.selectedToolMode = config.toolMode;
          this.selectedDebugMode = config.toolMode; // Legacy support
        }
        if (config.additiveOptions) {
          this.debugVerboseJni = config.additiveOptions.verboseJni;
          this.debugNativeMemoryTracking = config.additiveOptions.nativeMemoryTracking;
          this.debugExtensiveErrorReports = config.additiveOptions.extensiveErrorReports;
          this.debugDisableJit = config.additiveOptions.disableJit;
        }
        if (config.valgrindSettings) {
          this.debugGenerateValgrindSuppressions = config.valgrindSettings.generateSuppressions;
          this.debugLibnd4jSuppressionFile = config.valgrindSettings.libnd4jSuppressionFile || '';
        }
        if (config.logDirectory) {
          this.debugLogDirectory = config.logDirectory;
        }
        if (config.extraJvmArgs) {
          this.debugExtraJvmArgs = [...config.extraJvmArgs];
        }
        if (config.systemEnvironmentVariables) {
          this.debugSystemEnvVars = Object.entries(config.systemEnvironmentVariables)
            .map(([key, value]) => ({ key, value }));
        }
        if (config.nd4jEnvironmentConfig) {
          this.debugNd4jEnvConfig = Object.entries(config.nd4jEnvironmentConfig)
            .map(([key, value]) => ({ key, value }));
        }

        // Legacy support
        if (config.currentMode) {
          this.selectedDebugMode = config.currentMode;
        }
        if (config.disableJit !== undefined) {
          this.debugDisableJit = config.disableJit;
        }
        if (config.environmentVariables) {
          this.debugEnvVars = Object.entries(config.environmentVariables)
            .map(([key, value]) => ({ key, value }));
        }

        this.cdr.markForCheck();
      },
      error: () => {
        // Silently ignore
      }
    });
  }

  restartWithDebugMode(): void {
    this.isRestartingWithDebug = true;
    this.lastDebugRestartResult = null;
    this.cdr.markForCheck();

    // Build system environment variables
    const systemEnvVars: Record<string, string> = {};
    for (const env of this.debugSystemEnvVars) {
      if (env.key && env.key.trim()) {
        systemEnvVars[env.key.trim()] = env.value || '';
      }
    }
    // Also include legacy debugEnvVars for backwards compatibility
    for (const env of this.debugEnvVars) {
      if (env.key && env.key.trim()) {
        systemEnvVars[env.key.trim()] = env.value || '';
      }
    }

    // Build ND4J environment config
    const nd4jEnvConfig: Record<string, any> = {};
    for (const env of this.debugNd4jEnvConfig) {
      if (env.key && env.key.trim()) {
        // Try to parse as number or boolean
        let value: any = env.value;
        if (typeof value === 'string') {
          if (value === 'true') value = true;
          else if (value === 'false') value = false;
          else if (!isNaN(Number(value))) value = Number(value);
        }
        nd4jEnvConfig[env.key.trim()] = value;
      }
    }

    // Build system properties from custom env vars
    const systemProps: Record<string, string> = {};
    for (const env of this.customEnvVars) {
      if (env.key && env.key.trim()) {
        systemProps[env.key.trim()] = env.value || '';
      }
    }

    const request: RestartWithDebugRequest = {
      // Tool mode (mutually exclusive)
      toolMode: this.selectedToolMode,

      // Additive options
      verboseJni: this.debugVerboseJni,
      nativeMemoryTracking: this.debugNativeMemoryTracking,
      extensiveErrorReports: this.debugExtensiveErrorReports,
      disableJit: this.debugDisableJit,

      // Valgrind suppression settings
      generateValgrindSuppressions: this.debugGenerateValgrindSuppressions,
      libnd4jSuppressionFile: this.debugLibnd4jSuppressionFile || undefined,

      // Log directory
      logDirectory: this.debugLogDirectory,

      // Extra JVM args
      extraJvmArgs: this.debugExtraJvmArgs.filter(arg => arg && arg.trim()),

      // System environment variables (LD_PRELOAD, MALLOC_CHECK_, etc.)
      systemEnvironmentVariables: Object.keys(systemEnvVars).length > 0 ? systemEnvVars : undefined,

      // ND4J environment configuration
      nd4jEnvironmentConfig: Object.keys(nd4jEnvConfig).length > 0 ? nd4jEnvConfig : undefined,

      // System properties
      systemProperties: Object.keys(systemProps).length > 0 ? systemProps : undefined
    };

    this.configService.restartWithDebug(request).subscribe({
      next: (result) => {
        this.lastDebugRestartResult = result;
        this.isRestartingWithDebug = false;
        if (result.success) {
          const description = result.configDescription || result.toolModeDescription || result.debugDescription || this.selectedToolMode;
          this.showSuccess('Subprocess restarted with debug configuration: ' + description);
          this.loadEmbeddingSubprocessStatus();
        } else {
          this.showError('Debug restart failed: ' + (result.error || 'Unknown error'));
        }
        this.cdr.markForCheck();
      },
      error: (err) => {
        this.lastDebugRestartResult = {
          success: false,
          error: err.message || 'Request failed'
        };
        this.isRestartingWithDebug = false;
        this.showError('Failed to restart with debug mode: ' + err.message);
        this.cdr.markForCheck();
      }
    });
  }

  getSelectedToolModeDescription(): string {
    const mode = this.availableToolModes.find(m => m.value === this.selectedToolMode);
    return mode ? mode.description : '';
  }

  isSelectedToolModeCudaRequired(): boolean {
    const mode = this.availableToolModes.find(m => m.value === this.selectedToolMode);
    return mode ? mode.requiresCuda : false;
  }

  isValgrindSelected(): boolean {
    return this.selectedToolMode === 'valgrind' || this.selectedToolMode === 'valgrind-minimal';
  }

  // Legacy methods for backwards compatibility
  getSelectedDebugModeDescription(): string {
    return this.getSelectedToolModeDescription();
  }

  isSelectedDebugModeCudaRequired(): boolean {
    return this.isSelectedToolModeCudaRequired();
  }

  // Debug JVM args management
  addDebugJvmArg(): void {
    if (this.newJvmArg && this.newJvmArg.trim()) {
      this.debugExtraJvmArgs.push(this.newJvmArg.trim());
      this.newJvmArg = '';
      this.cdr.markForCheck();
    }
  }

  removeDebugJvmArg(index: number): void {
    this.debugExtraJvmArgs.splice(index, 1);
    this.cdr.markForCheck();
  }

  // System environment variable management
  addDebugSystemEnvVar(): void {
    this.debugSystemEnvVars.push({ key: '', value: '' });
    this.cdr.markForCheck();
  }

  removeDebugSystemEnvVar(index: number): void {
    this.debugSystemEnvVars.splice(index, 1);
    this.cdr.markForCheck();
  }

  // ND4J environment config management
  addDebugNd4jEnvConfig(): void {
    this.debugNd4jEnvConfig.push({ key: '', value: '' });
    this.cdr.markForCheck();
  }

  removeDebugNd4jEnvConfig(index: number): void {
    this.debugNd4jEnvConfig.splice(index, 1);
    this.cdr.markForCheck();
  }

  // Legacy environment variable management
  addDebugEnvVar(): void {
    this.debugEnvVars.push({ key: '', value: '' });
    this.cdr.markForCheck();
  }

  removeDebugEnvVar(index: number): void {
    this.debugEnvVars.splice(index, 1);
    this.cdr.markForCheck();
  }

  // Clear debug configuration
  clearDebugConfig(): void {
    this.selectedToolMode = 'none';
    this.selectedDebugMode = 'none';
    this.debugVerboseJni = false;
    this.debugNativeMemoryTracking = false;
    this.debugExtensiveErrorReports = false;
    this.debugDisableJit = false;
    this.debugGenerateValgrindSuppressions = true;
    this.debugLibnd4jSuppressionFile = '';
    this.debugLogDirectory = './logs/debug';
    this.debugExtraJvmArgs = [];
    this.debugSystemEnvVars = [];
    this.debugNd4jEnvConfig = [];
    this.debugEnvVars = [];
    this.lastDebugRestartResult = null;
    this.cdr.markForCheck();
  }

  // Get valgrind suppression info from last result for display
  getValgrindSuppressionInfo(): ValgrindSuppressionInfo | null {
    return this.lastDebugRestartResult?.valgrindSuppressionInfo || null;
  }
}
