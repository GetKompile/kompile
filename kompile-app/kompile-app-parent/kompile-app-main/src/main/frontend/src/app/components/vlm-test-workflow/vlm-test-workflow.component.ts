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

import { Component, OnInit, OnDestroy, ChangeDetectorRef, ViewChild } from '@angular/core';
import { Subject } from 'rxjs';
import { takeUntil } from 'rxjs/operators';
import { MatSnackBar } from '@angular/material/snack-bar';
import { VlmTestService } from '../../services/vlm-test.service';
import { WebSocketService } from '../../services/websocket.service';
import { SubprocessLogsComponent } from '../subprocess-logs/subprocess-logs.component';
import {
  VlmTestResultResponse,
  VlmTestPageResult,
  VlmTestPerformance,
  VlmTestProgressMessage,
  VlmTestSubprocessConfig,
  VlmTestSubprocessConfigUpdate,
  HEAP_SIZE_OPTIONS
} from '../../models/api-models';

@Component({
  selector: 'app-vlm-test-workflow',
  standalone: false,
  templateUrl: './vlm-test-workflow.component.html',
  styleUrls: ['./vlm-test-workflow.component.css']
})
export class VlmTestWorkflowComponent implements OnInit, OnDestroy {
  @ViewChild('subprocessLogs') subprocessLogs?: SubprocessLogsComponent;

  private destroy$ = new Subject<void>();

  // Upload
  selectedFile: File | null = null;
  modelId = '';
  outputFormat = 'DOCTAGS';
  maxNewTokens = 4096;
  temperature = 1.0;
  topP = 1.0;
  pdfRenderDpi = 300;
  pageBatchSize = 1;
  pageBatchSizeOptions = [1, 2, 4, 8];

  availableModels: { id: string; name: string; status?: string }[] = [];
  loadingModels = false;
  outputFormats = ['DOCTAGS', 'MARKDOWN', 'JSON', 'TEXT'];

  // State
  isRunning = false;
  taskId: string | null = null;
  progressPercent = 0;
  currentPhase = '';
  currentMessage = '';
  elapsedTime = 0;
  private elapsedInterval: any;

  // Results
  hasResults = false;
  result: VlmTestResultResponse | null = null;
  selectedPageIndex = 0;

  // Persistent error message (visible even after test ends)
  lastErrorMessage: string | null = null;

  // Subprocess configuration
  subprocessConfig: VlmTestSubprocessConfig | null = null;
  editableConfig: Partial<VlmTestSubprocessConfigUpdate> = {};
  heapSizeOptions: string[] = HEAP_SIZE_OPTIONS;
  offHeapMultiplierOptions: number[] = [1, 2, 3, 4, 5, 6, 8, 10];
  hasConfigChanged = false;
  isApplyingConfig = false;

  constructor(
    private vlmTestService: VlmTestService,
    private wsService: WebSocketService,
    private snackBar: MatSnackBar,
    private cdr: ChangeDetectorRef
  ) {}

  ngOnInit(): void {
    this.wsService.connect();
    this.loadAvailableModels();
    this.loadSubprocessConfig();
  }

  loadAvailableModels(): void {
    this.loadingModels = true;
    this.vlmTestService.getAvailableModels()
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: (models) => {
          this.availableModels = models.map((m: any) => ({
            id: m.modelId || m.id,
            name: m.modelId || m.id,
            status: m.status
          }));
          // Auto-select first model if none selected
          if (!this.modelId && this.availableModels.length > 0) {
            this.modelId = this.availableModels[0].id;
          }
          this.loadingModels = false;
          this.cdr.detectChanges();
        },
        error: (err) => {
          console.warn('Failed to load VLM models from registry:', err);
          this.loadingModels = false;
          this.cdr.detectChanges();
        }
      });
  }

  ngOnDestroy(): void {
    this.destroy$.next();
    this.destroy$.complete();
    this.stopElapsedTimer();
    if (this.taskId) {
      this.wsService.unsubscribeFromVlmTest(this.taskId);
    }
  }

  onFileSelected(event: Event): void {
    const input = event.target as HTMLInputElement;
    if (input.files && input.files.length > 0) {
      this.selectedFile = input.files[0];
    }
  }

  runTest(): void {
    if (!this.selectedFile) {
      this.showError('Please select a file');
      return;
    }

    this.isRunning = true;
    this.hasResults = false;
    this.result = null;
    this.lastErrorMessage = null;
    this.progressPercent = 0;
    this.currentPhase = 'Starting...';
    this.currentMessage = '';
    this.elapsedTime = 0;
    this.startElapsedTimer();

    this.vlmTestService.runTest(this.selectedFile, this.modelId, this.outputFormat, {
      maxNewTokens: this.maxNewTokens,
      temperature: this.temperature,
      topP: this.topP,
      pdfRenderDpi: this.pdfRenderDpi,
      pageBatchSize: this.pageBatchSize
    }).pipe(takeUntil(this.destroy$)).subscribe({
      next: (response) => {
        this.taskId = response.taskId;
        // Connect subprocess logs component to this task
        if (this.subprocessLogs) {
          this.subprocessLogs.setTaskId(response.taskId);
        }
        this.subscribeToProgress(response.taskId);
        this.startPolling(response.taskId);
      },
      error: (err) => {
        this.isRunning = false;
        this.stopElapsedTimer();
        this.lastErrorMessage = 'Failed to start VLM test: ' + (err.error?.error || err.message);
        this.showError(this.lastErrorMessage);
      }
    });
  }

  cancelTest(): void {
    if (this.taskId) {
      this.vlmTestService.cancelTest(this.taskId)
        .pipe(takeUntil(this.destroy$))
        .subscribe({
          next: () => {
            this.isRunning = false;
            this.stopElapsedTimer();
            this.showSnackbar('VLM test cancelled');
          },
          error: () => this.showError('Failed to cancel test')
        });
    }
  }

  private subscribeToProgress(taskId: string): void {
    this.wsService.subscribeToVlmTest(taskId)
      .pipe(takeUntil(this.destroy$))
      .subscribe((update: VlmTestProgressMessage) => {
        this.progressPercent = update.progressPercent || 0;
        this.currentPhase = update.currentPhase || '';
        this.currentMessage = update.message || '';

        if (update.status === 'COMPLETED') {
          this.onTestCompleted(taskId);
        } else if (update.status === 'FAILED') {
          this.isRunning = false;
          this.stopElapsedTimer();
          this.lastErrorMessage = update.message || 'VLM test failed';
          this.showError(this.lastErrorMessage);
        } else if (update.status === 'CANCELLED') {
          this.isRunning = false;
          this.stopElapsedTimer();
        }
        this.cdr.detectChanges();
      });
  }

  private startPolling(taskId: string): void {
    // Poll every 3 seconds as fallback to WebSocket
    const pollInterval = setInterval(() => {
      if (!this.isRunning) {
        clearInterval(pollInterval);
        return;
      }
      this.vlmTestService.getStatus(taskId)
        .pipe(takeUntil(this.destroy$))
        .subscribe({
          next: (status) => {
            if (status.status === 'COMPLETED' || status.status === 'FAILED' || status.status === 'CANCELLED') {
              clearInterval(pollInterval);
              if (status.status === 'COMPLETED') {
                this.onTestCompleted(taskId);
              } else if (status.status === 'FAILED') {
                this.isRunning = false;
                this.stopElapsedTimer();
              }
            } else {
              this.progressPercent = status.progressPercent || this.progressPercent;
              this.currentPhase = status.currentPhase || this.currentPhase;
            }
            this.cdr.detectChanges();
          },
          error: () => {} // Ignore polling errors
        });
    }, 3000);
  }

  private onTestCompleted(taskId: string): void {
    if (this.hasResults) return; // Avoid duplicate fetches

    this.vlmTestService.getResults(taskId)
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: (result) => {
          this.result = result;
          this.hasResults = true;
          this.isRunning = false;
          this.stopElapsedTimer();
          this.progressPercent = 100;
          this.selectedPageIndex = 0;
          if (result.errorMessage) {
            this.lastErrorMessage = result.errorMessage;
          }
          this.cdr.detectChanges();
        },
        error: (err) => {
          this.isRunning = false;
          this.stopElapsedTimer();
          this.lastErrorMessage = 'Failed to fetch results';
          this.showError(this.lastErrorMessage);
        }
      });
  }

  selectPage(index: number): void {
    this.selectedPageIndex = index;
  }

  get pages(): any[] {
    return this.result?.pages || [];
  }

  get performance(): any {
    return this.result?.performance || null;
  }

  get selectedPage(): any {
    return this.pages[this.selectedPageIndex] || null;
  }

  copyText(text: string): void {
    navigator.clipboard.writeText(text).then(() => {
      this.showSnackbar('Copied to clipboard');
    });
  }

  formatBytes(bytes: number): string {
    if (!bytes || bytes === 0) return '0 B';
    const k = 1024;
    const sizes = ['B', 'KB', 'MB', 'GB'];
    const i = Math.floor(Math.log(bytes) / Math.log(k));
    return parseFloat((bytes / Math.pow(k, i)).toFixed(1)) + ' ' + sizes[i];
  }

  formatTime(ms: number): string {
    if (!ms) return '0s';
    if (ms < 1000) return ms + 'ms';
    const seconds = ms / 1000;
    if (seconds < 60) return seconds.toFixed(1) + 's';
    const minutes = Math.floor(seconds / 60);
    const remainingSeconds = (seconds % 60).toFixed(0);
    return minutes + 'm ' + remainingSeconds + 's';
  }

  private startElapsedTimer(): void {
    this.elapsedInterval = setInterval(() => {
      this.elapsedTime++;
      this.cdr.detectChanges();
    }, 1000);
  }

  private stopElapsedTimer(): void {
    if (this.elapsedInterval) {
      clearInterval(this.elapsedInterval);
      this.elapsedInterval = null;
    }
  }

  loadSubprocessConfig(): void {
    this.vlmTestService.getConfig()
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: (config) => {
          this.subprocessConfig = config;
          this.editableConfig = { ...config };
          this.hasConfigChanged = false;
        },
        error: (err) => console.warn('Failed to load VLM subprocess config:', err)
      });
  }

  onConfigChange(): void {
    if (!this.subprocessConfig) return;
    this.hasConfigChanged =
      this.editableConfig.heapSize !== this.subprocessConfig.heapSize ||
      this.editableConfig.offHeapMultiplier !== this.subprocessConfig.offHeapMultiplier ||
      this.editableConfig.timeoutMinutes !== this.subprocessConfig.timeoutMinutes;
  }

  applyConfig(): void {
    if (!this.hasConfigChanged) return;
    this.isApplyingConfig = true;
    const update: VlmTestSubprocessConfigUpdate = {};
    if (this.editableConfig.heapSize !== this.subprocessConfig?.heapSize) {
      update.heapSize = this.editableConfig.heapSize;
    }
    if (this.editableConfig.offHeapMultiplier !== this.subprocessConfig?.offHeapMultiplier) {
      update.offHeapMultiplier = this.editableConfig.offHeapMultiplier;
    }
    if (this.editableConfig.timeoutMinutes !== this.subprocessConfig?.timeoutMinutes) {
      update.timeoutMinutes = this.editableConfig.timeoutMinutes;
    }
    this.vlmTestService.updateConfig(update)
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: (config) => {
          this.subprocessConfig = config;
          this.editableConfig = { ...config };
          this.hasConfigChanged = false;
          this.isApplyingConfig = false;
          this.showSnackbar('Subprocess config updated');
        },
        error: (err) => {
          this.isApplyingConfig = false;
          this.showError('Failed to update config: ' + (err.error?.error || err.message));
        }
      });
  }

  getEffectiveOffHeapMb(): string {
    const heapStr = this.editableConfig.heapSize || '4g';
    const multiplier = this.editableConfig.offHeapMultiplier || 3;
    const match = heapStr.match(/^(\d+)([gGmM])$/);
    if (!match) return '?';
    let heapMb = parseInt(match[1]);
    if (match[2].toLowerCase() === 'g') heapMb *= 1024;
    return (heapMb * multiplier / 1024).toFixed(0) + 'g';
  }

  private showError(message: string): void {
    this.snackBar.open(message, 'Close', { duration: 5000, panelClass: ['error-snackbar'] });
  }

  private showSnackbar(message: string): void {
    this.snackBar.open(message, 'Close', { duration: 3000 });
  }
}
