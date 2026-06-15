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

import { Injectable, OnDestroy } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { BehaviorSubject, Subject, Subscription, interval, Observable } from 'rxjs';
import { takeUntil, filter, tap } from 'rxjs/operators';
import { backendUrl } from './base.service';
import { ProcessingSettingsService } from './processing-settings.service';
import { BatchConfigService } from './batch-config.service';
import { IngestEventService } from './ingest-event.service';
import { MemoryStatus, IngestEvent } from '../models/api-models';
import { RECOMMENDED_BATCH_SIZES, getRecommendedBatchSizes } from '../models/api-models';

/**
 * Adaptive performance metrics collected during processing.
 */
export interface AdaptiveMetrics {
  /** Current embedding batch size */
  embeddingBatchSize: number;
  /** Current index batch size */
  indexBatchSize: number;
  /** Memory usage percentage */
  memoryUsagePercent: number;
  /** Processing throughput (chunks per second) */
  throughputChunksPerSec: number;
  /** Average embedding time (ms) */
  avgEmbeddingTimeMs: number;
  /** Average indexing time (ms) */
  avgIndexingTimeMs: number;
  /** Number of batch size adjustments made */
  adjustmentCount: number;
  /** Last adjustment reason */
  lastAdjustmentReason: string;
  /** Timestamp of last update */
  lastUpdated: Date;
}

/**
 * Configuration for adaptive performance mode.
 */
export interface AdaptiveConfig {
  /** Enable adaptive performance mode */
  enabled: boolean;
  /** Target memory usage percentage (will reduce batch size if exceeded) */
  targetMemoryPercent: number;
  /** Critical memory threshold (aggressive reduction) */
  criticalMemoryPercent: number;
  /** Minimum embedding batch size */
  minEmbeddingBatch: number;
  /** Maximum embedding batch size */
  maxEmbeddingBatch: number;
  /** Minimum index batch size */
  minIndexBatch: number;
  /** Maximum index batch size */
  maxIndexBatch: number;
  /** How often to check metrics (ms) */
  checkIntervalMs: number;
  /** Number of samples to average for throughput */
  throughputWindowSize: number;
  /** Cooldown between adjustments (ms) */
  adjustmentCooldownMs: number;
}

/**
 * Batch size adjustment event.
 */
export interface BatchAdjustment {
  timestamp: Date;
  type: 'embedding' | 'index' | 'both';
  previousValue: number;
  newValue: number;
  reason: string;
  memoryUsagePercent: number;
}

/**
 * Default adaptive configuration.
 */
export const DEFAULT_ADAPTIVE_CONFIG: AdaptiveConfig = {
  enabled: false,
  targetMemoryPercent: 75,
  criticalMemoryPercent: 90,
  minEmbeddingBatch: 2,
  maxEmbeddingBatch: 64,
  minIndexBatch: 10,
  maxIndexBatch: 200,
  checkIntervalMs: 5000,
  throughputWindowSize: 10,
  adjustmentCooldownMs: 15000
};

/**
 * Service for adaptive performance monitoring and automatic batch size adjustment.
 *
 * This service monitors system metrics during document processing and automatically
 * adjusts batch sizes to optimize throughput while preventing memory issues.
 *
 * Key features:
 * - Real-time memory monitoring
 * - Automatic batch size reduction when memory is high
 * - Automatic batch size increase when memory is low and throughput can improve
 * - Event-driven adjustments based on ingest events
 * - Configurable thresholds and limits
 */
@Injectable({
  providedIn: 'root'
})
export class AdaptivePerformanceService implements OnDestroy {

  private readonly baseUrl = `${backendUrl}/processing/adaptive`;

  // Current configuration
  private configSubject = new BehaviorSubject<AdaptiveConfig>({ ...DEFAULT_ADAPTIVE_CONFIG });
  public config$ = this.configSubject.asObservable();

  // Current metrics
  private metricsSubject = new BehaviorSubject<AdaptiveMetrics>({
    embeddingBatchSize: 8,
    indexBatchSize: 100,
    memoryUsagePercent: 0,
    throughputChunksPerSec: 0,
    avgEmbeddingTimeMs: 0,
    avgIndexingTimeMs: 0,
    adjustmentCount: 0,
    lastAdjustmentReason: 'Not started',
    lastUpdated: new Date()
  });
  public metrics$ = this.metricsSubject.asObservable();

  // Adjustment history
  private adjustmentsSubject = new BehaviorSubject<BatchAdjustment[]>([]);
  public adjustments$ = this.adjustmentsSubject.asObservable();

  // Active state
  private isActiveSubject = new BehaviorSubject<boolean>(false);
  public isActive$ = this.isActiveSubject.asObservable();

  // Internal state
  private destroy$ = new Subject<void>();
  private monitoringSubscription: Subscription | null = null;
  private lastAdjustmentTime: number = 0;
  private throughputHistory: number[] = [];
  private embeddingTimeHistory: number[] = [];
  private indexingTimeHistory: number[] = [];

  constructor(
    private http: HttpClient,
    private processingSettingsService: ProcessingSettingsService,
    private batchConfigService: BatchConfigService,
    private ingestEventService: IngestEventService
  ) {
    // Subscribe to ingest events for real-time metrics
    this.ingestEventService.newEvent$.pipe(
      takeUntil(this.destroy$),
      filter(() => this.configSubject.getValue().enabled)
    ).subscribe(event => this.handleIngestEvent(event));
  }

  /**
   * Get current configuration.
   */
  getConfig(): AdaptiveConfig {
    return this.configSubject.getValue();
  }

  /**
   * Update configuration - both locally and on backend.
   */
  updateConfig(config: Partial<AdaptiveConfig>): void {
    const current = this.configSubject.getValue();
    const updated = { ...current, ...config };
    this.configSubject.next(updated);

    // Sync to backend
    this.syncConfigToBackend(updated);

    // If enabled state changed, start or stop monitoring
    if (config.enabled !== undefined) {
      if (config.enabled) {
        this.startMonitoring();
      } else {
        this.stopMonitoring();
      }
    }
  }

  /**
   * Sync configuration to the backend.
   */
  private syncConfigToBackend(config: AdaptiveConfig): void {
    const backendConfig = {
      enabled: config.enabled,
      targetMemoryPercent: config.targetMemoryPercent,
      criticalMemoryPercent: config.criticalMemoryPercent,
      minEmbeddingBatch: config.minEmbeddingBatch,
      maxEmbeddingBatch: config.maxEmbeddingBatch,
      minIndexBatch: config.minIndexBatch,
      maxIndexBatch: config.maxIndexBatch,
      checkIntervalMs: config.checkIntervalMs,
      adjustmentCooldownMs: config.adjustmentCooldownMs,
      preset: null
    };

    this.http.put<any>(this.baseUrl, backendConfig).subscribe({
      next: (response) => {
        console.log('Backend adaptive config updated:', response);
        // Update local metrics from backend response
        if (response.currentEmbeddingBatch) {
          const metrics = this.metricsSubject.getValue();
          metrics.embeddingBatchSize = response.currentEmbeddingBatch;
          metrics.indexBatchSize = response.currentIndexBatch;
          this.metricsSubject.next(metrics);
        }
      },
      error: (err) => console.warn('Failed to sync adaptive config to backend:', err)
    });
  }

  /**
   * Fetch current status from the backend.
   */
  fetchBackendStatus(): Observable<any> {
    return this.http.get<any>(this.baseUrl).pipe(
      tap(status => {
        if (status) {
          const metrics = this.metricsSubject.getValue();
          metrics.embeddingBatchSize = status.currentEmbeddingBatch || metrics.embeddingBatchSize;
          metrics.indexBatchSize = status.currentIndexBatch || metrics.indexBatchSize;
          metrics.memoryUsagePercent = status.currentMemoryPercent || metrics.memoryUsagePercent;
          metrics.adjustmentCount = status.adjustmentCount || metrics.adjustmentCount;
          metrics.lastUpdated = new Date();
          this.metricsSubject.next(metrics);
        }
      })
    );
  }

  /**
   * Apply a preset configuration via backend API.
   */
  applyPreset(preset: 'conservative' | 'balanced' | 'aggressive'): Observable<any> {
    return this.http.post<any>(`${this.baseUrl}/preset/${preset}`, {}).pipe(
      tap(status => {
        if (status) {
          // Update local config from backend response
          const config = this.configSubject.getValue();
          config.enabled = status.enabled;
          config.targetMemoryPercent = status.targetMemoryPercent;
          config.criticalMemoryPercent = status.criticalMemoryPercent;
          this.configSubject.next(config);

          // Update metrics
          const metrics = this.metricsSubject.getValue();
          metrics.embeddingBatchSize = status.currentEmbeddingBatch;
          metrics.indexBatchSize = status.currentIndexBatch;
          this.metricsSubject.next(metrics);
        }
      })
    );
  }

  /**
   * Fetch available presets from backend.
   */
  getAvailablePresets(): Observable<any> {
    return this.http.get<any>(`${this.baseUrl}/presets`);
  }

  /**
   * Enable adaptive performance mode.
   */
  enable(): void {
    this.updateConfig({ enabled: true });
  }

  /**
   * Disable adaptive performance mode.
   */
  disable(): void {
    this.updateConfig({ enabled: false });
  }

  /**
   * Check if adaptive mode is currently active.
   */
  isActive(): boolean {
    return this.isActiveSubject.getValue();
  }

  /**
   * Get current metrics.
   */
  getMetrics(): AdaptiveMetrics {
    return this.metricsSubject.getValue();
  }

  /**
   * Get adjustment history.
   */
  getAdjustments(): BatchAdjustment[] {
    return this.adjustmentsSubject.getValue();
  }

  /**
   * Start adaptive monitoring.
   */
  startMonitoring(): void {
    if (this.monitoringSubscription) {
      return; // Already monitoring
    }

    const config = this.configSubject.getValue();
    this.isActiveSubject.next(true);

    console.log('Starting adaptive performance monitoring', config);

    // Start periodic memory checks
    this.monitoringSubscription = interval(config.checkIntervalMs).pipe(
      takeUntil(this.destroy$)
    ).subscribe(() => this.checkAndAdjust());

    // Initial check
    this.checkAndAdjust();
  }

  /**
   * Stop adaptive monitoring.
   */
  stopMonitoring(): void {
    if (this.monitoringSubscription) {
      this.monitoringSubscription.unsubscribe();
      this.monitoringSubscription = null;
    }
    this.isActiveSubject.next(false);
    console.log('Stopped adaptive performance monitoring');
  }

  /**
   * Reset metrics and adjustment history.
   */
  reset(): void {
    this.throughputHistory = [];
    this.embeddingTimeHistory = [];
    this.indexingTimeHistory = [];
    this.lastAdjustmentTime = 0;

    this.metricsSubject.next({
      embeddingBatchSize: this.metricsSubject.getValue().embeddingBatchSize,
      indexBatchSize: this.metricsSubject.getValue().indexBatchSize,
      memoryUsagePercent: 0,
      throughputChunksPerSec: 0,
      avgEmbeddingTimeMs: 0,
      avgIndexingTimeMs: 0,
      adjustmentCount: 0,
      lastAdjustmentReason: 'Reset',
      lastUpdated: new Date()
    });

    this.adjustmentsSubject.next([]);
  }

  /**
   * Check metrics and adjust batch sizes if needed.
   */
  private checkAndAdjust(): void {
    this.processingSettingsService.getMemoryStatus().subscribe({
      next: (memoryStatus) => this.evaluateAndAdjust(memoryStatus),
      error: (err) => console.warn('Failed to get memory status for adaptive adjustment:', err)
    });
  }

  /**
   * Evaluate memory status and adjust batch sizes.
   */
  private evaluateAndAdjust(memoryStatus: MemoryStatus): void {
    const config = this.configSubject.getValue();
    const metrics = this.metricsSubject.getValue();
    const now = Date.now();

    // Update memory metric
    metrics.memoryUsagePercent = memoryStatus.usagePercent;
    metrics.lastUpdated = new Date();

    // Check cooldown
    if (now - this.lastAdjustmentTime < config.adjustmentCooldownMs) {
      this.metricsSubject.next(metrics);
      return;
    }

    let adjustment: BatchAdjustment | null = null;

    // Critical memory - aggressive reduction
    if (memoryStatus.usagePercent >= config.criticalMemoryPercent) {
      adjustment = this.reducesBatchSizes('Critical memory pressure', memoryStatus.usagePercent, 0.5);
    }
    // High memory - moderate reduction
    else if (memoryStatus.usagePercent >= config.targetMemoryPercent) {
      adjustment = this.reducesBatchSizes('High memory usage', memoryStatus.usagePercent, 0.75);
    }
    // Low memory with good throughput history - try increasing
    else if (memoryStatus.usagePercent < config.targetMemoryPercent - 20 &&
             this.throughputHistory.length >= 3) {
      adjustment = this.tryIncreaseBatchSizes('Low memory with stable throughput', memoryStatus.usagePercent);
    }

    if (adjustment) {
      this.recordAdjustment(adjustment);
      metrics.adjustmentCount++;
      metrics.lastAdjustmentReason = adjustment.reason;
      this.lastAdjustmentTime = now;
    }

    this.metricsSubject.next(metrics);
  }

  /**
   * Reduce batch sizes by a factor.
   */
  private reducesBatchSizes(reason: string, memoryPercent: number, factor: number): BatchAdjustment | null {
    const config = this.configSubject.getValue();
    const metrics = this.metricsSubject.getValue();

    const currentEmbedding = metrics.embeddingBatchSize;
    const currentIndex = metrics.indexBatchSize;

    // Calculate new values
    const newEmbedding = Math.max(
      config.minEmbeddingBatch,
      Math.floor(currentEmbedding * factor)
    );
    const newIndex = Math.max(
      config.minIndexBatch,
      Math.floor(currentIndex * factor)
    );

    // Check if any change needed
    if (newEmbedding === currentEmbedding && newIndex === currentIndex) {
      return null;
    }

    // Apply changes
    this.applyBatchSizeChanges(newEmbedding, newIndex);

    return {
      timestamp: new Date(),
      type: 'both',
      previousValue: currentEmbedding,
      newValue: newEmbedding,
      reason: `${reason} (${memoryPercent.toFixed(1)}%)`,
      memoryUsagePercent: memoryPercent
    };
  }

  /**
   * Try to increase batch sizes if conditions allow.
   */
  private tryIncreaseBatchSizes(reason: string, memoryPercent: number): BatchAdjustment | null {
    const config = this.configSubject.getValue();
    const metrics = this.metricsSubject.getValue();

    const currentEmbedding = metrics.embeddingBatchSize;
    const currentIndex = metrics.indexBatchSize;

    // Calculate new values (increase by 25%)
    const newEmbedding = Math.min(
      config.maxEmbeddingBatch,
      Math.ceil(currentEmbedding * 1.25)
    );
    const newIndex = Math.min(
      config.maxIndexBatch,
      Math.ceil(currentIndex * 1.25)
    );

    // Check if any change possible
    if (newEmbedding === currentEmbedding && newIndex === currentIndex) {
      return null;
    }

    // Apply changes
    this.applyBatchSizeChanges(newEmbedding, newIndex);

    return {
      timestamp: new Date(),
      type: 'both',
      previousValue: currentEmbedding,
      newValue: newEmbedding,
      reason: `${reason} (${memoryPercent.toFixed(1)}%)`,
      memoryUsagePercent: memoryPercent
    };
  }

  /**
   * Apply batch size changes to the backend.
   */
  private applyBatchSizeChanges(embeddingBatch: number, indexBatch: number): void {
    const metrics = this.metricsSubject.getValue();

    // Update local metrics first
    metrics.embeddingBatchSize = embeddingBatch;
    metrics.indexBatchSize = indexBatch;
    this.metricsSubject.next(metrics);

    // Update index batch size via processing settings
    this.processingSettingsService.updateSettings({
      indexBatchSize: indexBatch
    }).subscribe({
      next: () => console.log(`Adaptive: Updated index batch size to ${indexBatch}`),
      error: (err) => console.warn('Failed to update index batch size:', err)
    });

    // Update embedding batch size via batch config service
    this.batchConfigService.updateGlobalConfig({
      optimalBatchSize: embeddingBatch,
      maxBatchSize: Math.min(embeddingBatch * 2, 64)
    }).subscribe({
      next: () => console.log(`Adaptive: Updated embedding batch size to ${embeddingBatch}`),
      error: (err) => console.warn('Failed to update embedding batch size:', err)
    });
  }

  /**
   * Record an adjustment to history.
   */
  private recordAdjustment(adjustment: BatchAdjustment): void {
    const adjustments = this.adjustmentsSubject.getValue();
    // Keep last 50 adjustments
    const updated = [adjustment, ...adjustments].slice(0, 50);
    this.adjustmentsSubject.next(updated);

    console.log(`Adaptive adjustment: ${adjustment.reason}`, adjustment);
  }

  /**
   * Handle ingest events to track performance metrics.
   */
  private handleIngestEvent(event: IngestEvent): void {
    const config = this.configSubject.getValue();
    const metrics = this.metricsSubject.getValue();

    // Track embedding times
    if (event.phase === 'EMBEDDING' && event.durationMs) {
      this.embeddingTimeHistory.push(event.durationMs);
      if (this.embeddingTimeHistory.length > config.throughputWindowSize) {
        this.embeddingTimeHistory.shift();
      }
      metrics.avgEmbeddingTimeMs = this.calculateAverage(this.embeddingTimeHistory);
    }

    // Track indexing times
    if (event.phase === 'INDEXING' && event.durationMs) {
      this.indexingTimeHistory.push(event.durationMs);
      if (this.indexingTimeHistory.length > config.throughputWindowSize) {
        this.indexingTimeHistory.shift();
      }
      metrics.avgIndexingTimeMs = this.calculateAverage(this.indexingTimeHistory);
    }

    // Track throughput from completion events
    if (event.eventType === 'COMPLETED' && event.itemsProcessed && event.durationMs) {
      const chunksPerSec = (event.itemsProcessed / event.durationMs) * 1000;
      this.throughputHistory.push(chunksPerSec);
      if (this.throughputHistory.length > config.throughputWindowSize) {
        this.throughputHistory.shift();
      }
      metrics.throughputChunksPerSec = this.calculateAverage(this.throughputHistory);
    }

    metrics.lastUpdated = new Date();
    this.metricsSubject.next(metrics);
  }

  /**
   * Calculate average of an array.
   */
  private calculateAverage(values: number[]): number {
    if (values.length === 0) return 0;
    return values.reduce((sum, v) => sum + v, 0) / values.length;
  }

  /**
   * Get recommended configuration based on system memory.
   */
  getRecommendedConfig(systemMemoryMB: number): Partial<AdaptiveConfig> {
    const recommendations = getRecommendedBatchSizes(systemMemoryMB);

    return {
      minEmbeddingBatch: Math.max(2, Math.floor(recommendations.embeddingBatch / 2)),
      maxEmbeddingBatch: recommendations.maxEmbeddingBatch,
      minIndexBatch: Math.max(10, Math.floor(recommendations.indexBatch / 2)),
      maxIndexBatch: Math.min(200, recommendations.indexBatch * 2),
      targetMemoryPercent: systemMemoryMB < 8192 ? 70 : 75,
      criticalMemoryPercent: systemMemoryMB < 8192 ? 85 : 90
    };
  }

  /**
   * Clean up on destroy.
   */
  ngOnDestroy(): void {
    this.destroy$.next();
    this.destroy$.complete();
    this.stopMonitoring();
  }
}
