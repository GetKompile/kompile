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
import { MatSnackBar } from '@angular/material/snack-bar';
import { Subject } from 'rxjs';
import { takeUntil } from 'rxjs/operators';
import { OcrService } from '../../services/ocr.service';
import { VlmService } from '../../services/vlm.service';
import {
  OcrDebugStatus,
  RegistryModelInfo,
  PipelineTrace,
  VlmOutputFormat,
  PageRenderResult
} from '../../models/ocr-models';
import { VlmBenchmarkResult } from '../../models/vlm-models';

@Component({
  selector: 'app-ocr-debug',
  standalone: false,
  templateUrl: './ocr-debug.component.html',
  styleUrls: ['./ocr-debug.component.css']
})
export class OcrDebugComponent implements OnInit, OnDestroy {

  // Status
  status: OcrDebugStatus | null = null;
  models: RegistryModelInfo[] = [];
  isLoadingStatus = false;
  isLoadingModels = false;

  // VLM management
  isLoadingVlm = false;
  isUnloadingVlm = false;
  vlmLoadModelId = '';

  // File upload & test
  selectedFile: File | null = null;
  selectedPage: number | null = null;
  testModelId = '';
  testMaxTokens = 4096;
  testOutputFormat: VlmOutputFormat = 'DOCTAGS';
  isTesting = false;
  testResult: PipelineTrace | null = null;

  // Comparison mode
  comparisonMode = false;
  isLoadingPageImage = false;
  currentPageImage: PageRenderResult | null = null;

  // Benchmark
  isBenchmarking = false;
  benchmarkResult: VlmBenchmarkResult | null = null;
  benchmarkIterations = 3;
  benchmarkModelId = '';

  // Formats
  vlmOutputFormats: VlmOutputFormat[] = ['DOCTAGS', 'MARKDOWN', 'PLAIN_TEXT', 'JSON', 'TEXT'];

  errorMessage: string | null = null;
  private destroy$ = new Subject<void>();

  constructor(
    private ocrService: OcrService,
    private vlmService: VlmService,
    private snackBar: MatSnackBar,
    private cdr: ChangeDetectorRef
  ) {}

  ngOnInit(): void {
    this.loadStatus();
    this.loadModels();
  }

  ngOnDestroy(): void {
    this.destroy$.next();
    this.destroy$.complete();
  }

  // ---- Data Loading ----

  loadStatus(): void {
    this.isLoadingStatus = true;
    this.ocrService.getDebugStatus()
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: (status) => {
          this.status = status;
          this.isLoadingStatus = false;
          this.cdr.detectChanges();
        },
        error: (err) => {
          this.showError('Failed to load status: ' + this.getErrorMessage(err));
          this.isLoadingStatus = false;
          this.cdr.detectChanges();
        }
      });
  }

  loadModels(): void {
    this.isLoadingModels = true;
    this.ocrService.getRegistryModels()
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: (models) => {
          this.models = models;
          this.isLoadingModels = false;
          this.cdr.detectChanges();
        },
        error: (err) => {
          this.showError('Failed to load models: ' + this.getErrorMessage(err));
          this.isLoadingModels = false;
          this.cdr.detectChanges();
        }
      });
  }

  refreshAll(): void {
    this.loadStatus();
    this.loadModels();
    this.showSnackbar('Refreshed');
  }

  // ---- VLM Management ----

  loadVlm(): void {
    if (!this.vlmLoadModelId) {
      this.showError('Enter a model ID to load');
      return;
    }
    this.isLoadingVlm = true;
    this.ocrService.loadVlm(this.vlmLoadModelId)
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: () => {
          this.isLoadingVlm = false;
          this.showSnackbar('VLM model loaded: ' + this.vlmLoadModelId);
          this.loadStatus();
          this.cdr.detectChanges();
        },
        error: (err) => {
          this.isLoadingVlm = false;
          this.showError('Failed to load VLM: ' + this.getErrorMessage(err));
          this.cdr.detectChanges();
        }
      });
  }

  unloadVlm(): void {
    this.isUnloadingVlm = true;
    this.ocrService.unloadVlm()
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: () => {
          this.isUnloadingVlm = false;
          this.showSnackbar('VLM model unloaded');
          this.loadStatus();
          this.cdr.detectChanges();
        },
        error: (err) => {
          this.isUnloadingVlm = false;
          this.showError('Failed to unload VLM: ' + this.getErrorMessage(err));
          this.cdr.detectChanges();
        }
      });
  }

  // ---- File Testing ----

  onFileSelected(event: Event): void {
    const input = event.target as HTMLInputElement;
    if (input.files && input.files.length > 0) {
      this.selectedFile = input.files[0];
      this.testResult = null;
    }
  }

  runTest(): void {
    if (!this.selectedFile) {
      this.showError('Please select a file first');
      return;
    }

    this.isTesting = true;
    this.testResult = null;
    this.errorMessage = null;

    const page = this.selectedPage && this.selectedPage > 0 ? this.selectedPage : undefined;

    this.ocrService.testOcr(
      this.selectedFile,
      this.testModelId || undefined,
      this.testMaxTokens,
      this.testOutputFormat,
      page
    )
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: (result) => {
          this.testResult = result;
          this.isTesting = false;
          if (result.success) {
            this.showSnackbar(`Completed in ${result.totalTimeMs}ms - ${result.tokensGenerated} tokens`);
          } else {
            this.showError('Test failed: ' + (result.error || 'Unknown error'));
          }
          this.cdr.detectChanges();
        },
        error: (err) => {
          this.isTesting = false;
          this.showError('Test failed: ' + this.getErrorMessage(err));
          this.cdr.detectChanges();
        }
      });
  }

  // ---- Comparison Mode ----

  toggleComparisonMode(): void {
    this.comparisonMode = !this.comparisonMode;
    if (this.comparisonMode && this.selectedFile) {
      this.loadPageImage(this.testResult?.pageProcessed || 1);
    }
  }

  loadPageImage(pageNumber: number): void {
    if (!this.selectedFile) return;

    this.isLoadingPageImage = true;
    this.ocrService.renderPdfPage(this.selectedFile, pageNumber)
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: (result) => {
          this.currentPageImage = result;
          this.isLoadingPageImage = false;
          this.cdr.detectChanges();
        },
        error: (err) => {
          this.showError('Failed to render page: ' + this.getErrorMessage(err));
          this.isLoadingPageImage = false;
          this.cdr.detectChanges();
        }
      });
  }

  // ---- Benchmark ----

  runQuickBenchmark(): void {
    this.isBenchmarking = true;
    this.benchmarkResult = null;
    this.vlmService.runQuickBenchmark(this.benchmarkModelId || undefined)
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: (result) => {
          this.benchmarkResult = result;
          this.isBenchmarking = false;
          if (result.summary) {
            this.showSnackbar(`${result.summary.avgTokensPerSecond.toFixed(1)} tokens/sec avg`);
          }
          this.cdr.detectChanges();
        },
        error: (err) => {
          this.isBenchmarking = false;
          this.showError('Benchmark failed: ' + this.getErrorMessage(err));
          this.cdr.detectChanges();
        }
      });
  }

  runFileBenchmark(): void {
    if (!this.selectedFile) {
      this.showError('Select a file first');
      return;
    }
    this.isBenchmarking = true;
    this.benchmarkResult = null;
    this.vlmService.runBenchmark(this.selectedFile, this.benchmarkModelId || undefined, this.benchmarkIterations)
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: (result) => {
          this.benchmarkResult = result;
          this.isBenchmarking = false;
          if (result.summary) {
            this.showSnackbar(`${result.summary.avgTokensPerSecond.toFixed(1)} tokens/sec avg`);
          }
          this.cdr.detectChanges();
        },
        error: (err) => {
          this.isBenchmarking = false;
          this.showError('Benchmark failed: ' + this.getErrorMessage(err));
          this.cdr.detectChanges();
        }
      });
  }

  // ---- Utilities ----

  getModelsByType(type: string): RegistryModelInfo[] {
    return this.models.filter(m => m.type === type);
  }

  getModelTypes(): string[] {
    const types = new Set(this.models.map(m => m.type));
    return Array.from(types);
  }

  getModelTypeIcon(type: string): string {
    switch (type) {
      case 'OCR_DETECTION': return 'find_in_page';
      case 'OCR_RECOGNITION': return 'text_fields';
      case 'OCR_TABLE': return 'table_chart';
      case 'LAYOUT_MODEL': return 'dashboard';
      case 'OCR_PIPELINE': return 'account_tree';
      case 'VLM_PIPELINE': return 'smart_toy';
      default: return 'memory';
    }
  }

  getModelTypeLabel(type: string): string {
    switch (type) {
      case 'OCR_DETECTION': return 'Detection';
      case 'OCR_RECOGNITION': return 'Recognition';
      case 'OCR_TABLE': return 'Table';
      case 'LAYOUT_MODEL': return 'Layout';
      case 'OCR_PIPELINE': return 'Pipeline';
      case 'VLM_PIPELINE': return 'VLM Pipeline';
      default: return type;
    }
  }

  getStatusColor(status: string): string {
    switch (status) {
      case 'ACTIVE': return '#4caf50';
      case 'STAGED': return '#ff9800';
      case 'PENDING': return '#2196f3';
      case 'FAILED': return '#f44336';
      default: return '#9e9e9e';
    }
  }

  getStepDurationPercent(stepMs: number): number {
    if (!this.testResult || this.testResult.totalTimeMs === 0) return 0;
    return Math.max(2, (stepMs / this.testResult.totalTimeMs) * 100);
  }

  copyToClipboard(text: string): void {
    if (navigator.clipboard?.writeText) {
      navigator.clipboard.writeText(text)
        .then(() => this.showSnackbar('Copied'))
        .catch(() => this.showSnackbar('Copy failed'));
    }
  }

  formatBytes(bytes: number): string {
    if (bytes === 0) return '0 B';
    const k = 1024;
    const sizes = ['B', 'KB', 'MB', 'GB'];
    const i = Math.floor(Math.log(bytes) / Math.log(k));
    return parseFloat((bytes / Math.pow(k, i)).toFixed(1)) + ' ' + sizes[i];
  }

  private getErrorMessage(error: any): string {
    return error?.error?.message || error?.error?.error || error?.message || 'Unknown error';
  }

  private showSnackbar(message: string): void {
    this.snackBar.open(message, 'Close', {
      duration: 4000,
      horizontalPosition: 'center',
      verticalPosition: 'top'
    });
  }

  private showError(message: string): void {
    this.errorMessage = message;
    this.snackBar.open(message, 'Close', {
      duration: 6000,
      horizontalPosition: 'center',
      verticalPosition: 'top'
    });
  }
}
