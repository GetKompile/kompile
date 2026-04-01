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
import { Observable, interval, switchMap, startWith } from 'rxjs';
import { ProcessingSettings, ProcessingSettingsUpdate, MemoryStatus, PipelineConfig, PipelinePresets, StageMetricsResponse } from '../models/api-models';
import { backendUrl } from './base.service';

@Injectable({
  providedIn: 'root'
})
export class ProcessingSettingsService {

  private readonly baseUrl = `${backendUrl}/processing`;

  constructor(private http: HttpClient) { }

  /**
   * Get current processing settings and system status.
   */
  getSettings(): Observable<ProcessingSettings> {
    return this.http.get<ProcessingSettings>(`${this.baseUrl}/settings`);
  }

  /**
   * Update processing settings.
   */
  updateSettings(settings: ProcessingSettingsUpdate): Observable<ProcessingSettings> {
    return this.http.put<ProcessingSettings>(`${this.baseUrl}/settings`, settings);
  }

  /**
   * Get current memory status.
   */
  getMemoryStatus(): Observable<MemoryStatus> {
    return this.http.get<MemoryStatus>(`${this.baseUrl}/memory`);
  }

  /**
   * Trigger garbage collection.
   */
  triggerGC(): Observable<{ message: string; freedMB: number; currentUsagePercent: number }> {
    return this.http.post<{ message: string; freedMB: number; currentUsagePercent: number }>(
      `${this.baseUrl}/gc`, {}
    );
  }

  /**
   * Get job statistics.
   */
  getJobStats(): Observable<{
    activeJobs: number;
    maxConcurrentJobs: number;
    queuedJobs: number;
    canAcceptNewJob: boolean;
  }> {
    return this.http.get<any>(`${this.baseUrl}/jobs`);
  }

  /**
   * Poll settings at regular intervals.
   */
  pollSettings(intervalMs: number = 5000): Observable<ProcessingSettings> {
    return interval(intervalMs).pipe(
      startWith(0),
      switchMap(() => this.getSettings())
    );
  }

  /**
   * Poll memory status at regular intervals.
   */
  pollMemoryStatus(intervalMs: number = 2000): Observable<MemoryStatus> {
    return interval(intervalMs).pipe(
      startWith(0),
      switchMap(() => this.getMemoryStatus())
    );
  }

  /**
   * Get current pipeline configuration.
   */
  getPipelineConfig(): Observable<PipelineConfig> {
    return this.http.get<PipelineConfig>(`${this.baseUrl}/pipeline-config`);
  }

  /**
   * Get available pipeline presets.
   */
  getPipelinePresets(): Observable<PipelinePresets> {
    return this.http.get<PipelinePresets>(`${this.baseUrl}/pipeline-presets`);
  }

  /**
   * Get current stage metrics.
   */
  getStageMetrics(): Observable<StageMetricsResponse> {
    return this.http.get<StageMetricsResponse>(`${this.baseUrl}/stage-metrics`);
  }

  // ========== PIPELINE SETTINGS (USER-CONFIGURABLE) ==========

  /**
   * Get current pipeline settings.
   * These are user-configurable settings that persist across restarts.
   */
  getPipelineSettings(): Observable<PipelineConfig> {
    return this.http.get<PipelineConfig>(`${this.baseUrl}/pipeline-settings`);
  }

  /**
   * Update pipeline settings.
   * Only non-null values in the request are applied.
   */
  updatePipelineSettings(settings: Partial<PipelineConfig>): Observable<PipelineConfig> {
    return this.http.put<PipelineConfig>(`${this.baseUrl}/pipeline-settings`, settings);
  }

  /**
   * Apply a pipeline preset.
   * Valid presets: defaults, highThroughput, lowMemory, keywordOnly
   */
  applyPipelinePreset(preset: string): Observable<PipelineConfig> {
    return this.http.post<PipelineConfig>(`${this.baseUrl}/pipeline-settings/preset/${preset}`, {});
  }

  /**
   * Reset pipeline settings to defaults.
   */
  resetPipelineSettings(): Observable<PipelineConfig> {
    return this.http.post<PipelineConfig>(`${this.baseUrl}/pipeline-settings/reset`, {});
  }

  /**
   * Get available pipeline setting presets with their descriptions.
   */
  getPipelineSettingPresets(): Observable<{ [key: string]: any }> {
    return this.http.get<{ [key: string]: any }>(`${this.baseUrl}/pipeline-settings/presets`);
  }

  // ========== GRAPH OPTIMIZATION ==========

  /**
   * Get graph optimization status.
   */
  getGraphOptimizationStatus(): Observable<GraphOptimizationStatus> {
    return this.http.get<GraphOptimizationStatus>(`${this.baseUrl}/graph-optimization`);
  }

  /**
   * Optimize and save the current model.
   * This creates a pre-optimized model file that can be registered for faster startup.
   */
  optimizeAndSaveModel(outputPath?: string): Observable<GraphOptimizationResult> {
    const body = outputPath ? { outputPath } : {};
    return this.http.post<GraphOptimizationResult>(`${this.baseUrl}/graph-optimization/optimize-and-save`, body);
  }
}

// ========== GRAPH OPTIMIZATION TYPES ==========

export interface GraphOptimizationStatus {
  available: boolean;
  embeddingModelLoaded?: boolean;
  currentModel?: string;
  description?: string;
  note?: string;
  message?: string;
}

export interface GraphOptimizationResult {
  success: boolean;
  modelId?: string;
  originalModel?: string;
  optimizedModelPath?: string;
  optimizationTimeMs?: number;
  backupFile?: string;
  canRestore?: boolean;
  message?: string;
  nextSteps?: string[];
  note?: string;
  error?: string;
}
