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

import { Component, OnInit, OnDestroy, ChangeDetectorRef } from '@angular/core';
import { Subscription } from 'rxjs';
import { ProcessingSettingsService, GraphOptimizationStatus } from '../../services/processing-settings.service';
import { AdaptivePerformanceService, AdaptiveMetrics, BatchAdjustment } from '../../services/adaptive-performance.service';
import { BatchConfigService } from '../../services/batch-config.service';
import { SubprocessConfigService, SubprocessConfigResponse } from '../../services/subprocess-config.service';
import { ProcessingSettings, ProcessingSettingsUpdate, SubprocessIngestConfig, HEAP_SIZE_OPTIONS, PipelineConfig } from '../../models/api-models';
import { BatchSizeConfigResponse, EmbeddingModelInfo } from '../../models/batch-config.models';
import { MatSnackBar } from '@angular/material/snack-bar';

@Component({
  standalone: false,
  selector: 'app-processing-settings',
  templateUrl: './processing-settings.component.html',
  styleUrls: ['./processing-settings.component.css']
})
export class ProcessingSettingsComponent implements OnInit, OnDestroy {
  // Processing settings
  processingSettings: ProcessingSettings | null = null;
  editableSettings: ProcessingSettingsUpdate = {
    maxConcurrentJobs: 4,
    indexBatchSize: 50,
    memoryThresholdPercent: 80,
    adaptiveBatchSize: true
  };
  hasSettingsChanged: boolean = false;
  isApplyingSettings: boolean = false;
  isGCRunning: boolean = false;

  // Adaptive performance metrics
  adaptiveMetrics: AdaptiveMetrics | null = null;
  adaptiveAdjustments: BatchAdjustment[] = [];
  adaptiveModeActive: boolean = false;
  showAdaptiveMetrics: boolean = false;

  // Embedding batch size config (from benchmark results)
  currentBatchConfig: BatchSizeConfigResponse | null = null;
  currentEmbeddingModel: EmbeddingModelInfo | null = null;
  hasBenchmarkConfig: boolean = false;

  // Manual override for embedding batch size
  embeddingBatchSizeOverride: number | null = null;

  // Subprocess configuration (global setting)
  subprocessConfig: SubprocessIngestConfig | null = null;
  editableSubprocessConfig: Partial<SubprocessIngestConfig> = {};
  hasSubprocessConfigChanged: boolean = false;
  isApplyingSubprocessConfig: boolean = false;
  isLoadingSubprocessConfig: boolean = false;
  heapSizeOptions: string[] = HEAP_SIZE_OPTIONS;
  offHeapMultiplierOptions: number[] = [1, 2, 3, 4, 5, 6, 8, 10];

  // VLM subprocess configuration
  vlmHeapSize: string = '4g';
  vlmOffHeapMultiplier: number = 3;
  vlmTimeoutMinutes: number = 30;
  vlmCudaPinnedHostLimitMb: number = 0;
  editableVlmHeapSize: string = '4g';
  editableVlmOffHeapMultiplier: number = 3;
  editableVlmTimeoutMinutes: number = 30;
  editableVlmCudaPinnedHostLimitMb: number = 0;
  hasVlmConfigChanged: boolean = false;
  isApplyingVlmConfig: boolean = false;

  // Ingest off-heap multiplier
  ingestOffHeapMultiplier: number = 2;
  editableIngestOffHeapMultiplier: number = 2;

  // Pipeline settings configuration
  pipelineConfig: PipelineConfig | null = null;
  editablePipelineConfig: Partial<PipelineConfig> = {};
  hasPipelineConfigChanged: boolean = false;
  isApplyingPipelineConfig: boolean = false;
  isLoadingPipelineConfig: boolean = false;
  showAdvancedPipelineSettings: boolean = false;

  // Graph optimization settings
  graphOptimizationStatus: GraphOptimizationStatus | null = null;
  isLoadingGraphOptimization: boolean = false;
  isTogglingGraphOptimization: boolean = false;

  // Subscriptions
  private settingsSubscription: Subscription | null = null;
  private adaptiveMetricsSubscription: Subscription | null = null;
  private adaptiveAdjustmentsSubscription: Subscription | null = null;
  private adaptiveActiveSubscription: Subscription | null = null;

  constructor(
    private processingSettingsService: ProcessingSettingsService,
    private adaptivePerformanceService: AdaptivePerformanceService,
    private batchConfigService: BatchConfigService,
    private subprocessConfigService: SubprocessConfigService,
    private snackBar: MatSnackBar,
    private cdr: ChangeDetectorRef
  ) { }

  ngOnInit(): void {
    this.loadProcessingSettings();
    this.startSettingsPolling();
    this.initializeAdaptiveMetrics();
    this.loadBatchConfig();
    this.loadSubprocessConfig();
    this.loadPipelineConfig();
    this.loadGraphOptimizationStatus();
  }

  ngOnDestroy(): void {
    this.settingsSubscription?.unsubscribe();
    this.adaptiveMetricsSubscription?.unsubscribe();
    this.adaptiveAdjustmentsSubscription?.unsubscribe();
    this.adaptiveActiveSubscription?.unsubscribe();
  }

  private initializeAdaptiveMetrics(): void {
    // Subscribe to adaptive metrics
    this.adaptiveMetricsSubscription = this.adaptivePerformanceService.metrics$.subscribe(metrics => {
      this.adaptiveMetrics = metrics;
      this.cdr.detectChanges();
    });

    // Subscribe to adjustments
    this.adaptiveAdjustmentsSubscription = this.adaptivePerformanceService.adjustments$.subscribe(adjustments => {
      this.adaptiveAdjustments = adjustments;
      this.cdr.detectChanges();
    });

    // Subscribe to active state
    this.adaptiveActiveSubscription = this.adaptivePerformanceService.isActive$.subscribe(active => {
      this.adaptiveModeActive = active;
      // Show metrics panel when adaptive mode becomes active
      if (active) {
        this.showAdaptiveMetrics = true;
      }
      this.cdr.detectChanges();
    });

    // Fetch initial backend status
    this.adaptivePerformanceService.fetchBackendStatus().subscribe({
      error: (err) => console.warn('Could not fetch adaptive status:', err)
    });
  }

  /**
   * Load the current batch size configuration from the backend.
   * This fetches the persisted benchmark results if available.
   */
  private loadBatchConfig(): void {
    // First load global config to get the persisted embedding batch size
    this.batchConfigService.getGlobalConfig().subscribe({
      next: (globalConfig) => {
        // If there's a persisted optimal batch size different from default (32),
        // set it as the override value in the UI
        if (globalConfig && globalConfig.currentOptimalBatchSize &&
            globalConfig.currentOptimalBatchSize !== 32) {
          this.embeddingBatchSizeOverride = globalConfig.currentOptimalBatchSize;
        }
        this.currentBatchConfig = globalConfig;
        this.cdr.detectChanges();
      },
      error: (err) => {
        console.warn('Could not load global batch config:', err);
      }
    });

    // Also load model-specific info
    this.batchConfigService.getAvailableModels().subscribe({
      next: (models) => {
        // Find the first loaded model (the active one)
        const loadedModel = models.find(m => m.isLoaded);
        if (loadedModel) {
          this.currentEmbeddingModel = loadedModel;
          if (loadedModel.batchConfig) {
            this.currentBatchConfig = loadedModel.batchConfig;
          }
          // Check if this is a custom/benchmark config (has runtime override)
          this.hasBenchmarkConfig = loadedModel.batchConfig?.hasRuntimeOverride ?? false;
          this.cdr.detectChanges();
        }
      },
      error: (err) => {
        console.warn('Could not load batch config:', err);
      }
    });
  }

  /**
   * Get the currently configured optimal batch size.
   */
  getCurrentOptimalBatchSize(): number | null {
    return this.currentBatchConfig?.currentOptimalBatchSize ?? null;
  }

  /**
   * Get the effective (runtime) batch size.
   */
  getEffectiveBatchSize(): number | null {
    return this.currentBatchConfig?.calculatedEffectiveBatchSize ?? null;
  }

  toggleAdaptiveMetricsPanel(): void {
    this.showAdaptiveMetrics = !this.showAdaptiveMetrics;
  }

  getAdaptiveAdjustmentIcon(direction: string): string {
    return direction === 'INCREASE' ? 'trending_up' : 'trending_down';
  }

  getAdaptiveAdjustmentClass(direction: string): string {
    return direction === 'INCREASE' ? 'adjustment-increase' : 'adjustment-decrease';
  }

  formatAdjustmentTime(timestamp: Date): string {
    const date = new Date(timestamp);
    return date.toLocaleTimeString();
  }

  private loadProcessingSettings(): void {
    this.processingSettingsService.getSettings().subscribe({
      next: (settings) => {
        this.processingSettings = settings;
        this.syncEditableSettings();
        this.cdr.detectChanges();
      },
      error: (err) => {
        console.warn('Could not load processing settings:', err);
      }
    });
  }

  private startSettingsPolling(): void {
    // Poll settings every 5 seconds to keep memory status updated
    this.settingsSubscription = this.processingSettingsService.pollSettings(5000).subscribe({
      next: (settings) => {
        this.processingSettings = settings;
        // Don't overwrite editable settings if user has made changes
        if (!this.hasSettingsChanged) {
          this.syncEditableSettings();
        }
        this.cdr.detectChanges();
      },
      error: (err) => {
        console.warn('Settings polling error:', err);
      }
    });
  }

  private syncEditableSettings(): void {
    if (this.processingSettings) {
      this.editableSettings = {
        maxConcurrentJobs: this.processingSettings.maxConcurrentJobs,
        indexBatchSize: this.processingSettings.indexBatchSize,
        memoryThresholdPercent: this.processingSettings.memoryThresholdPercent,
        adaptiveBatchSize: this.processingSettings.adaptiveBatchSize
      };
    }
  }

  onSettingsChange(): void {
    if (!this.processingSettings) return;

    this.hasSettingsChanged =
      this.editableSettings.maxConcurrentJobs !== this.processingSettings.maxConcurrentJobs ||
      this.editableSettings.indexBatchSize !== this.processingSettings.indexBatchSize ||
      this.editableSettings.memoryThresholdPercent !== this.processingSettings.memoryThresholdPercent ||
      this.editableSettings.adaptiveBatchSize !== this.processingSettings.adaptiveBatchSize;
  }

  applySettings(): void {
    if (!this.hasSettingsChanged) return;

    this.isApplyingSettings = true;
    this.processingSettingsService.updateSettings(this.editableSettings).subscribe({
      next: (settings) => {
        this.processingSettings = settings;
        this.hasSettingsChanged = false;
        this.isApplyingSettings = false;
        this.showSnackbar('Processing settings updated successfully');
        this.cdr.detectChanges();
      },
      error: (err) => {
        this.isApplyingSettings = false;
        this.showSnackbar('Failed to update settings: ' + (err.message || 'Server error'), true);
        this.cdr.detectChanges();
      }
    });
  }

  /**
   * Called when user changes the embedding batch size override.
   * Updates the global batch configuration via the batch config service.
   */
  onEmbeddingBatchSizeChange(): void {
    if (this.embeddingBatchSizeOverride === null) {
      // Reset to auto/benchmark - call backend reset endpoint
      this.batchConfigService.resetGlobalConfig().subscribe({
        next: (response) => {
          this.currentBatchConfig = response;
          this.showSnackbar('Embedding batch size reset to defaults');
          this.cdr.detectChanges();
        },
        error: (err) => {
          this.showSnackbar('Failed to reset batch size: ' + (err.message || 'Server error'), true);
        }
      });
      return;
    }

    const newBatchSize = this.embeddingBatchSizeOverride;
    // Use the selected batch size as both optimal and max to ensure it's actually used
    this.batchConfigService.updateGlobalConfig({
      optimalBatchSize: newBatchSize,
      maxBatchSize: newBatchSize
    }).subscribe({
      next: (response) => {
        this.currentBatchConfig = response;
        this.showSnackbar(`Embedding batch size updated to ${newBatchSize}`);
        // Update adaptive metrics to reflect the change
        const metrics = this.adaptiveMetrics;
        if (metrics) {
          metrics.embeddingBatchSize = newBatchSize;
        }
        this.cdr.detectChanges();
      },
      error: (err) => {
        this.showSnackbar('Failed to update embedding batch size: ' + (err.message || 'Server error'), true);
      }
    });
  }

  triggerGC(): void {
    this.isGCRunning = true;
    this.processingSettingsService.triggerGC().subscribe({
      next: (result) => {
        this.isGCRunning = false;
        this.showSnackbar(`GC completed. Freed ${result.freedMB} MB. Current usage: ${result.currentUsagePercent.toFixed(1)}%`);
        this.loadProcessingSettings(); // Refresh to show updated memory
      },
      error: (err) => {
        this.isGCRunning = false;
        this.showSnackbar('GC request failed', true);
      }
    });
  }

  getMemoryBarColor(): string {
    if (!this.processingSettings) return 'primary';
    const status = this.processingSettings.memoryStatus.status;
    if (status === 'CRITICAL') return 'warn';
    if (status === 'WARNING') return 'accent';
    return 'primary';
  }

  /**
   * Check if a specific RAM tier is the current system's tier based on max memory.
   */
  isCurrentRamTier(tier: 'lt4' | '4to8' | '8to16' | 'gt16'): boolean {
    if (!this.processingSettings?.memoryStatus?.maxMemoryMB) {
      return false;
    }
    const ramGb = this.processingSettings.memoryStatus.maxMemoryMB / 1024;
    switch (tier) {
      case 'lt4': return ramGb < 4;
      case '4to8': return ramGb >= 4 && ramGb < 8;
      case '8to16': return ramGb >= 8 && ramGb < 16;
      case 'gt16': return ramGb >= 16;
      default: return false;
    }
  }

  /**
   * Get system RAM in GB for display.
   */
  getSystemRamGb(): string {
    if (!this.processingSettings?.memoryStatus?.maxMemoryMB) {
      return '?';
    }
    return (this.processingSettings.memoryStatus.maxMemoryMB / 1024).toFixed(1);
  }

  private showSnackbar(message: string, isError: boolean = false, duration: number = 5000): void {
    this.snackBar.open(message, 'Close', {
      duration,
      horizontalPosition: 'center',
      verticalPosition: 'top',
      panelClass: isError ? ['snackbar-error'] : ['snackbar-success']
    });
  }

  // ==================== Subprocess Configuration ====================

  /**
   * Load the current subprocess configuration from the backend.
   */
  private loadSubprocessConfig(): void {
    this.isLoadingSubprocessConfig = true;
    this.subprocessConfigService.getConfiguration().subscribe({
      next: (config: SubprocessConfigResponse) => {
        this.subprocessConfig = {
          enabled: config.enabled,
          heapSize: config.heapSize,
          offHeapMaxBytes: config.offHeapMaxBytes || '',
          timeoutMinutes: config.timeoutMinutes,
          heartbeatIntervalSeconds: config.heartbeatIntervalSeconds,
          staleThresholdSeconds: config.staleThresholdSeconds
        };
        // Load off-heap multiplier for ingest
        this.ingestOffHeapMultiplier = config.offHeapMultiplier || 2;
        this.editableIngestOffHeapMultiplier = this.ingestOffHeapMultiplier;
        // Load VLM config
        this.vlmHeapSize = config.vlmHeapSize || '4g';
        this.vlmOffHeapMultiplier = config.vlmOffHeapMultiplier || 3;
        this.vlmTimeoutMinutes = config.vlmTimeoutMinutes || 30;
        this.vlmCudaPinnedHostLimitMb = config.vlmCudaPinnedHostLimitMb || 0;
        this.editableVlmHeapSize = this.vlmHeapSize;
        this.editableVlmOffHeapMultiplier = this.vlmOffHeapMultiplier;
        this.editableVlmTimeoutMinutes = this.vlmTimeoutMinutes;
        this.editableVlmCudaPinnedHostLimitMb = this.vlmCudaPinnedHostLimitMb;
        this.syncEditableSubprocessConfig();
        this.isLoadingSubprocessConfig = false;
        this.cdr.detectChanges();
      },
      error: (err: Error) => {
        console.warn('Could not load subprocess config:', err);
        this.isLoadingSubprocessConfig = false;
        this.cdr.detectChanges();
      }
    });
  }

  private syncEditableSubprocessConfig(): void {
    if (this.subprocessConfig) {
      this.editableSubprocessConfig = { ...this.subprocessConfig };
    }
  }

  onSubprocessConfigChange(): void {
    if (!this.subprocessConfig) return;

    this.hasSubprocessConfigChanged =
      this.editableSubprocessConfig.enabled !== this.subprocessConfig.enabled ||
      this.editableSubprocessConfig.heapSize !== this.subprocessConfig.heapSize ||
      this.editableSubprocessConfig.timeoutMinutes !== this.subprocessConfig.timeoutMinutes ||
      this.editableSubprocessConfig.heartbeatIntervalSeconds !== this.subprocessConfig.heartbeatIntervalSeconds ||
      this.editableSubprocessConfig.staleThresholdSeconds !== this.subprocessConfig.staleThresholdSeconds ||
      this.editableIngestOffHeapMultiplier !== this.ingestOffHeapMultiplier;
  }

  onVlmConfigChange(): void {
    this.hasVlmConfigChanged =
      this.editableVlmHeapSize !== this.vlmHeapSize ||
      this.editableVlmOffHeapMultiplier !== this.vlmOffHeapMultiplier ||
      this.editableVlmTimeoutMinutes !== this.vlmTimeoutMinutes ||
      this.editableVlmCudaPinnedHostLimitMb !== this.vlmCudaPinnedHostLimitMb;
  }

  applyVlmConfig(): void {
    if (!this.hasVlmConfigChanged) return;
    this.isApplyingVlmConfig = true;
    this.subprocessConfigService.updateConfiguration({
      vlmHeapSize: this.editableVlmHeapSize,
      vlmOffHeapMultiplier: this.editableVlmOffHeapMultiplier,
      vlmTimeoutMinutes: this.editableVlmTimeoutMinutes,
      vlmCudaPinnedHostLimitMb: this.editableVlmCudaPinnedHostLimitMb
    }).subscribe({
      next: (config: SubprocessConfigResponse) => {
        this.vlmHeapSize = config.vlmHeapSize || this.editableVlmHeapSize;
        this.vlmOffHeapMultiplier = config.vlmOffHeapMultiplier || this.editableVlmOffHeapMultiplier;
        this.vlmTimeoutMinutes = config.vlmTimeoutMinutes || this.editableVlmTimeoutMinutes;
        this.vlmCudaPinnedHostLimitMb = config.vlmCudaPinnedHostLimitMb || 0;
        this.editableVlmHeapSize = this.vlmHeapSize;
        this.editableVlmOffHeapMultiplier = this.vlmOffHeapMultiplier;
        this.editableVlmTimeoutMinutes = this.vlmTimeoutMinutes;
        this.editableVlmCudaPinnedHostLimitMb = this.vlmCudaPinnedHostLimitMb;
        this.hasVlmConfigChanged = false;
        this.isApplyingVlmConfig = false;
        this.showSnackbar('VLM subprocess config updated');
        this.cdr.detectChanges();
      },
      error: (err: Error) => {
        this.isApplyingVlmConfig = false;
        this.showSnackbar('Failed to update VLM config: ' + (err.message || 'Server error'), true);
        this.cdr.detectChanges();
      }
    });
  }

  getEffectiveIngestOffHeapMb(): number {
    const heapStr = this.editableSubprocessConfig.heapSize || '4g';
    const heapNum = parseInt(heapStr, 10);
    return heapNum * this.editableIngestOffHeapMultiplier * 1024;
  }

  getEffectiveVlmOffHeapMb(): number {
    const heapNum = parseInt(this.editableVlmHeapSize, 10);
    return heapNum * this.editableVlmOffHeapMultiplier * 1024;
  }

  /**
   * Toggle subprocess mode enabled/disabled.
   */
  toggleSubprocessEnabled(): void {
    this.editableSubprocessConfig.enabled = !this.editableSubprocessConfig.enabled;
    this.onSubprocessConfigChange();
  }

  /**
   * Apply the subprocess configuration changes to the backend.
   */
  applySubprocessConfig(): void {
    if (!this.hasSubprocessConfigChanged || !this.editableSubprocessConfig) return;

    this.isApplyingSubprocessConfig = true;
    const update = {
      ...this.editableSubprocessConfig,
      offHeapMultiplier: this.editableIngestOffHeapMultiplier
    };
    this.subprocessConfigService.updateConfiguration(update as any).subscribe({
      next: (config: SubprocessConfigResponse) => {
        this.subprocessConfig = {
          enabled: config.enabled,
          heapSize: config.heapSize,
          offHeapMaxBytes: config.offHeapMaxBytes || '',
          timeoutMinutes: config.timeoutMinutes,
          heartbeatIntervalSeconds: config.heartbeatIntervalSeconds,
          staleThresholdSeconds: config.staleThresholdSeconds
        };
        this.ingestOffHeapMultiplier = config.offHeapMultiplier || this.editableIngestOffHeapMultiplier;
        this.editableIngestOffHeapMultiplier = this.ingestOffHeapMultiplier;
        this.hasSubprocessConfigChanged = false;
        this.isApplyingSubprocessConfig = false;
        this.showSnackbar(`Subprocess mode ${config.enabled ? 'enabled' : 'disabled'} successfully`);
        this.cdr.detectChanges();
      },
      error: (err: Error) => {
        this.isApplyingSubprocessConfig = false;
        this.showSnackbar('Failed to update subprocess config: ' + (err.message || 'Server error'), true);
        this.cdr.detectChanges();
      }
    });
  }

  /**
   * Quick toggle to enable subprocess mode with current settings.
   */
  enableSubprocessMode(): void {
    this.subprocessConfigService.enable().subscribe({
      next: (config: SubprocessConfigResponse) => {
        this.subprocessConfig = {
          enabled: config.enabled,
          heapSize: config.heapSize,
          offHeapMaxBytes: config.offHeapMaxBytes || '',
          timeoutMinutes: config.timeoutMinutes,
          heartbeatIntervalSeconds: config.heartbeatIntervalSeconds,
          staleThresholdSeconds: config.staleThresholdSeconds
        };
        this.syncEditableSubprocessConfig();
        this.hasSubprocessConfigChanged = false;
        this.showSnackbar('Subprocess mode enabled');
        this.cdr.detectChanges();
      },
      error: (err: Error) => {
        this.showSnackbar('Failed to enable subprocess mode: ' + (err.message || 'Server error'), true);
      }
    });
  }

  /**
   * Quick toggle to disable subprocess mode.
   */
  disableSubprocessMode(): void {
    this.subprocessConfigService.disable().subscribe({
      next: (config: SubprocessConfigResponse) => {
        this.subprocessConfig = {
          enabled: config.enabled,
          heapSize: config.heapSize,
          offHeapMaxBytes: config.offHeapMaxBytes || '',
          timeoutMinutes: config.timeoutMinutes,
          heartbeatIntervalSeconds: config.heartbeatIntervalSeconds,
          staleThresholdSeconds: config.staleThresholdSeconds
        };
        this.syncEditableSubprocessConfig();
        this.hasSubprocessConfigChanged = false;
        this.showSnackbar('Subprocess mode disabled');
        this.cdr.detectChanges();
      },
      error: (err: Error) => {
        this.showSnackbar('Failed to disable subprocess mode: ' + (err.message || 'Server error'), true);
      }
    });
  }

  /**
   * Debug: Verify subprocess config state from backend.
   */
  debugSubprocessConfig(): void {
    console.debug('=== DEBUG: Calling /api/subprocess-config/debug ===');
    this.subprocessConfigService.debug().subscribe({
      next: (result) => {
        console.debug('=== DEBUG RESULT ===', result);
        const msg = `DEBUG: inMemoryEnabled=${result.inMemoryEnabled}, config.enabled=${result.configuration?.enabled}, launcherAvailable=${result.launcherAvailable}`;
        this.showSnackbar(msg, false, 10000);
        // Also update local state to match backend
        if (result.configuration) {
          this.subprocessConfig = {
            enabled: result.configuration.enabled,
            heapSize: result.configuration.heapSize,
            offHeapMaxBytes: result.configuration.offHeapMaxBytes || '',
            timeoutMinutes: result.configuration.timeoutMinutes,
            heartbeatIntervalSeconds: result.configuration.heartbeatIntervalSeconds,
            staleThresholdSeconds: result.configuration.staleThresholdSeconds
          };
          this.syncEditableSubprocessConfig();
          this.cdr.detectChanges();
        }
      },
      error: (err: Error) => {
        console.error('DEBUG ERROR:', err);
        this.showSnackbar('Debug failed: ' + (err.message || 'Server error'), true);
      }
    });
  }

  // ==================== Pipeline Configuration ====================

  /**
   * Load the current pipeline configuration from the backend.
   */
  private loadPipelineConfig(): void {
    this.isLoadingPipelineConfig = true;
    this.processingSettingsService.getPipelineSettings().subscribe({
      next: (config: PipelineConfig) => {
        this.pipelineConfig = config;
        this.syncEditablePipelineConfig();
        this.isLoadingPipelineConfig = false;
        this.cdr.detectChanges();
      },
      error: (err: Error) => {
        console.warn('Could not load pipeline config:', err);
        this.isLoadingPipelineConfig = false;
        this.cdr.detectChanges();
      }
    });
  }

  private syncEditablePipelineConfig(): void {
    if (this.pipelineConfig) {
      this.editablePipelineConfig = { ...this.pipelineConfig };
    }
  }

  onPipelineConfigChange(): void {
    if (!this.pipelineConfig) return;

    this.hasPipelineConfigChanged =
      this.editablePipelineConfig.embeddingTimeoutSeconds !== this.pipelineConfig.embeddingTimeoutSeconds ||
      this.editablePipelineConfig.queueCapacity !== this.pipelineConfig.queueCapacity ||
      this.editablePipelineConfig.chunkingThreads !== this.pipelineConfig.chunkingThreads ||
      this.editablePipelineConfig.embeddingThreads !== this.pipelineConfig.embeddingThreads ||
      this.editablePipelineConfig.indexingThreads !== this.pipelineConfig.indexingThreads ||
      this.editablePipelineConfig.indexingBatchAccumulationSize !== this.pipelineConfig.indexingBatchAccumulationSize ||
      this.editablePipelineConfig.skipEmbedding !== this.pipelineConfig.skipEmbedding;
  }

  /**
   * Apply pipeline configuration changes.
   */
  applyPipelineConfig(): void {
    if (!this.hasPipelineConfigChanged) return;

    this.isApplyingPipelineConfig = true;
    this.processingSettingsService.updatePipelineSettings(this.editablePipelineConfig).subscribe({
      next: (config: PipelineConfig) => {
        this.pipelineConfig = config;
        this.syncEditablePipelineConfig();
        this.hasPipelineConfigChanged = false;
        this.isApplyingPipelineConfig = false;
        this.showSnackbar('Pipeline settings updated successfully');
        this.cdr.detectChanges();
      },
      error: (err: Error) => {
        this.isApplyingPipelineConfig = false;
        this.showSnackbar('Failed to update pipeline settings: ' + (err.message || 'Server error'), true);
        this.cdr.detectChanges();
      }
    });
  }

  /**
   * Apply a pipeline preset.
   */
  applyPipelinePreset(preset: string): void {
    this.isApplyingPipelineConfig = true;
    this.processingSettingsService.applyPipelinePreset(preset).subscribe({
      next: (config: PipelineConfig) => {
        this.pipelineConfig = config;
        this.syncEditablePipelineConfig();
        this.hasPipelineConfigChanged = false;
        this.isApplyingPipelineConfig = false;
        this.showSnackbar(`Applied "${preset}" preset`);
        this.cdr.detectChanges();
      },
      error: (err: Error) => {
        this.isApplyingPipelineConfig = false;
        this.showSnackbar('Failed to apply preset: ' + (err.message || 'Server error'), true);
        this.cdr.detectChanges();
      }
    });
  }

  /**
   * Reset pipeline settings to defaults.
   */
  resetPipelineConfig(): void {
    this.isApplyingPipelineConfig = true;
    this.processingSettingsService.resetPipelineSettings().subscribe({
      next: (config: PipelineConfig) => {
        this.pipelineConfig = config;
        this.syncEditablePipelineConfig();
        this.hasPipelineConfigChanged = false;
        this.isApplyingPipelineConfig = false;
        this.showSnackbar('Pipeline settings reset to defaults');
        this.cdr.detectChanges();
      },
      error: (err: Error) => {
        this.isApplyingPipelineConfig = false;
        this.showSnackbar('Failed to reset settings: ' + (err.message || 'Server error'), true);
        this.cdr.detectChanges();
      }
    });
  }

  /**
   * Toggle advanced pipeline settings visibility.
   */
  toggleAdvancedPipelineSettings(): void {
    this.showAdvancedPipelineSettings = !this.showAdvancedPipelineSettings;
  }

  /**
   * Format timeout in human-readable format.
   */
  formatTimeout(seconds: number | undefined): string {
    if (!seconds || seconds === 0) return 'No timeout';
    if (seconds < 60) return `${seconds}s`;
    const minutes = Math.floor(seconds / 60);
    const remainingSeconds = seconds % 60;
    if (remainingSeconds === 0) return `${minutes}m`;
    return `${minutes}m ${remainingSeconds}s`;
  }

  // ==================== Graph Optimization ====================

  /**
   * Load the current graph optimization status from the backend.
   */
  private loadGraphOptimizationStatus(): void {
    this.isLoadingGraphOptimization = true;
    this.processingSettingsService.getGraphOptimizationStatus().subscribe({
      next: (status: GraphOptimizationStatus) => {
        this.graphOptimizationStatus = status;
        this.isLoadingGraphOptimization = false;
        this.cdr.detectChanges();
      },
      error: (err: Error) => {
        console.warn('Could not load graph optimization status:', err);
        this.isLoadingGraphOptimization = false;
        this.cdr.detectChanges();
      }
    });
  }

  /**
   * Optimize and save the current model.
   * Creates a pre-optimized model file that can be registered for faster startup.
   */
  optimizeAndSaveModel(): void {
    if (this.isTogglingGraphOptimization) return;

    this.isTogglingGraphOptimization = true;
    this.showSnackbar('Starting model optimization... This may take a few minutes.', false, 10000);

    this.processingSettingsService.optimizeAndSaveModel().subscribe({
      next: (result) => {
        this.isTogglingGraphOptimization = false;

        if (result.success) {
          const message = `Model optimized in ${result.optimizationTimeMs}ms. Saved to: ${result.optimizedModelPath}`;
          this.showSnackbar(message, false, 8000);

          // Log next steps to console
          if (result.nextSteps) {
            console.log('Next steps to use the optimized model:');
            result.nextSteps.forEach((step, i) => console.log(`  ${step}`));
          }
        } else {
          this.showSnackbar(result.error || 'Failed to optimize model', true);
        }
        this.cdr.detectChanges();
      },
      error: (err: Error) => {
        this.isTogglingGraphOptimization = false;
        this.showSnackbar('Failed to optimize model: ' + (err.message || 'Server error'), true);
        this.cdr.detectChanges();
      }
    });
  }
}
