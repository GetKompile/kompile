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

import { Component, OnInit, ChangeDetectionStrategy, ChangeDetectorRef } from '@angular/core';
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

import { BatchConfigService } from '../../services/batch-config.service';
import {
  EmbeddingModelInfo,
  BatchSizeConfigResponse,
  BatchSizeConfigRequest,
  BatchSizeTestRequest,
  BatchSizeTestResponse,
  BatchSizeTestResult
} from '../../models/batch-config.models';

@Component({
  selector: 'app-batch-size-config',
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
    MatProgressSpinnerModule
  ],
  templateUrl: './batch-size-config.component.html',
  styleUrls: ['./batch-size-config.component.css'],
  changeDetection: ChangeDetectionStrategy.OnPush
})
export class BatchSizeConfigComponent implements OnInit {
  // Model selection
  models: EmbeddingModelInfo[] = [];
  selectedModelId: string | null = null;
  selectedConfig: BatchSizeConfigResponse | null = null;
  isLoading = false;

  // Edit form
  editOptimalBatchSize = 8;
  editMaxBatchSize = 16;
  editMemoryScaleFactor = -1;
  autoScale = true;

  // Benchmark configuration
  availableBatchSizes = [1, 2, 4, 8, 16, 32, 64, 128];
  selectedBatchSizes: number[] = [1, 2, 4, 8, 16, 32];
  sampleText = '';
  testIterations = 3;
  warmupIterations = 1;
  timeoutSeconds = 30;

  // Benchmark state
  isRunningBenchmark = false;
  benchmarkProgress = 0;
  testResults: BatchSizeTestResponse | null = null;

  // Table configuration
  displayedColumns = ['batchSize', 'avgTimeMs', 'tokensPerSecond', 'documentsPerSecond', 'memoryMb', 'status'];

  constructor(
    private batchConfigService: BatchConfigService,
    private snackBar: MatSnackBar,
    private cdr: ChangeDetectorRef
  ) {}

  ngOnInit(): void {
    this.refreshModels();
  }

  refreshModels(): void {
    this.isLoading = true;
    this.cdr.markForCheck();

    this.batchConfigService.getAvailableModels().subscribe({
      next: (models) => {
        this.models = models;
        this.isLoading = false;

        // Auto-select first loaded model
        const loadedModel = models.find(m => m.isLoaded);
        if (loadedModel && !this.selectedModelId) {
          this.selectedModelId = loadedModel.modelId;
          this.loadModelConfig(loadedModel.modelId);
        }

        this.cdr.markForCheck();
      },
      error: (err) => {
        this.showError('Failed to load models: ' + err.message);
        this.isLoading = false;
        this.cdr.markForCheck();
      }
    });
  }

  onModelChange(modelId: string): void {
    this.selectedModelId = modelId;
    this.loadModelConfig(modelId);
    this.testResults = null;
  }

  loadModelConfig(modelId: string): void {
    this.batchConfigService.getModelConfig(modelId).subscribe({
      next: (config) => {
        this.selectedConfig = config;
        this.editOptimalBatchSize = config.currentOptimalBatchSize;
        this.editMaxBatchSize = config.currentMaxBatchSize;
        this.editMemoryScaleFactor = config.memoryScaleFactor;
        this.autoScale = config.isAutoScaled;
        this.cdr.markForCheck();
      },
      error: (err) => {
        this.showError('Failed to load config: ' + err.message);
      }
    });
  }

  applyConfig(): void {
    if (!this.selectedModelId) return;

    const request: BatchSizeConfigRequest = {
      optimalBatchSize: this.editOptimalBatchSize,
      maxBatchSize: this.editMaxBatchSize,
      memoryScaleFactor: this.autoScale ? -1 : this.editMemoryScaleFactor
    };

    this.batchConfigService.updateModelConfig(this.selectedModelId, request).subscribe({
      next: (config) => {
        this.selectedConfig = config;
        this.showSuccess('Configuration saved (persists across restarts)');
        this.cdr.markForCheck();
      },
      error: (err) => {
        this.showError('Failed to update config: ' + (err.error?.error || err.message));
      }
    });
  }

  resetConfig(): void {
    if (!this.selectedModelId) return;

    this.batchConfigService.resetModelConfig(this.selectedModelId).subscribe({
      next: (config) => {
        this.selectedConfig = config;
        this.editOptimalBatchSize = config.currentOptimalBatchSize;
        this.editMaxBatchSize = config.currentMaxBatchSize;
        this.editMemoryScaleFactor = config.memoryScaleFactor;
        this.autoScale = config.isAutoScaled;
        this.showSuccess('Configuration reset to defaults');
        this.cdr.markForCheck();
      },
      error: (err) => {
        this.showError('Failed to reset config: ' + err.message);
      }
    });
  }

  toggleBatchSize(size: number): void {
    const index = this.selectedBatchSizes.indexOf(size);
    if (index >= 0) {
      this.selectedBatchSizes.splice(index, 1);
    } else {
      this.selectedBatchSizes.push(size);
      this.selectedBatchSizes.sort((a, b) => a - b);
    }
  }

  isBatchSizeSelected(size: number): boolean {
    return this.selectedBatchSizes.includes(size);
  }

  runBenchmark(): void {
    if (this.isRunningBenchmark || this.selectedBatchSizes.length === 0) return;

    this.isRunningBenchmark = true;
    this.benchmarkProgress = 0;
    this.testResults = null;
    this.cdr.markForCheck();

    const request: BatchSizeTestRequest = {
      modelId: this.selectedModelId || undefined,
      sampleTexts: this.sampleText ? [this.sampleText] : undefined,
      batchSizesToTest: this.selectedBatchSizes,
      iterations: this.testIterations,
      warmupIterations: this.warmupIterations,
      timeoutSeconds: this.timeoutSeconds
    };

    // Simulate progress updates
    const progressInterval = setInterval(() => {
      if (this.benchmarkProgress < 90) {
        this.benchmarkProgress += 5;
        this.cdr.markForCheck();
      }
    }, 500);

    this.batchConfigService.runBatchSizeTest(request).subscribe({
      next: (response) => {
        clearInterval(progressInterval);
        this.benchmarkProgress = 100;
        this.testResults = response;
        this.isRunningBenchmark = false;
        this.showSuccess(`Benchmark complete. Recommended batch size: ${response.recommendedBatchSize}`);
        this.cdr.markForCheck();
      },
      error: (err) => {
        clearInterval(progressInterval);
        this.isRunningBenchmark = false;
        this.benchmarkProgress = 0;
        this.showError('Benchmark failed: ' + (err.error?.error || err.message));
        this.cdr.markForCheck();
      }
    });
  }

  applyRecommended(): void {
    if (!this.testResults || !this.selectedModelId) return;

    this.editOptimalBatchSize = this.testResults.recommendedBatchSize;
    this.editMaxBatchSize = Math.max(this.testResults.recommendedBatchSize, this.testResults.maxSafeBatchSize);
    this.applyConfig();
  }

  getBarWidth(tokensPerSecond: number): number {
    if (!this.testResults?.results?.length) return 0;

    const maxTps = Math.max(...this.testResults.results
      .filter(r => r.success)
      .map(r => r.tokensPerSecond));

    return maxTps > 0 ? (tokensPerSecond / maxTps) * 100 : 0;
  }

  formatMemory(bytes: number): string {
    const mb = bytes / (1024 * 1024);
    return mb.toFixed(1);
  }

  isRecommended(result: BatchSizeTestResult): boolean {
    return this.testResults !== null && result.batchSize === this.testResults.recommendedBatchSize;
  }

  /**
   * Get the recommended batch size based on system's available RAM.
   * Returns null if no config is loaded yet.
   */
  getRecommendedBatchSizeByRam(): number | null {
    if (!this.selectedConfig?.availableMemoryMb) {
      return null;
    }
    const ramGb = this.selectedConfig.availableMemoryMb / 1024;
    if (ramGb < 4) return 4;
    if (ramGb < 8) return 8;
    if (ramGb < 16) return 16;
    return 32;
  }

  /**
   * Check if a specific RAM tier is the current system's tier.
   */
  isCurrentRamTier(tier: 'lt4' | '4to8' | '8to16' | 'gt16'): boolean {
    if (!this.selectedConfig?.availableMemoryMb) {
      return false;
    }
    const ramGb = this.selectedConfig.availableMemoryMb / 1024;
    switch (tier) {
      case 'lt4': return ramGb < 4;
      case '4to8': return ramGb >= 4 && ramGb < 8;
      case '8to16': return ramGb >= 8 && ramGb < 16;
      case 'gt16': return ramGb >= 16;
      default: return false;
    }
  }

  /**
   * Get available RAM in GB for display.
   */
  getAvailableRamGb(): string {
    if (!this.selectedConfig?.availableMemoryMb) {
      return '?';
    }
    return (this.selectedConfig.availableMemoryMb / 1024).toFixed(1);
  }

  exportResults(): void {
    if (!this.testResults) return;

    const headers = ['Batch Size', 'Avg Time (ms)', 'Min Time (ms)', 'Max Time (ms)', 'Std Dev (ms)',
      'Tokens/sec', 'Docs/sec', 'Memory (MB)', 'Peak Memory (MB)', 'Success', 'Error'];

    const rows = this.testResults.results.map(r => [
      r.batchSize,
      r.avgTimeMs.toFixed(2),
      r.minTimeMs.toFixed(2),
      r.maxTimeMs.toFixed(2),
      r.stdDevMs.toFixed(2),
      r.tokensPerSecond.toFixed(1),
      r.documentsPerSecond.toFixed(2),
      (r.memoryUsedBytes / 1024 / 1024).toFixed(1),
      (r.peakMemoryBytes / 1024 / 1024).toFixed(1),
      r.success ? 'Yes' : 'No',
      r.errorMessage || ''
    ]);

    const csv = [headers.join(','), ...rows.map(r => r.join(','))].join('\n');
    const blob = new Blob([csv], { type: 'text/csv' });
    const url = window.URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = `batch-size-benchmark-${this.testResults.modelId}-${Date.now()}.csv`;
    a.click();
    window.URL.revokeObjectURL(url);
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
}
