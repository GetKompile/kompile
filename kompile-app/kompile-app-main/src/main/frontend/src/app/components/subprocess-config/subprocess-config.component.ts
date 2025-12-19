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
import { Subject, interval } from 'rxjs';
import { takeUntil } from 'rxjs/operators';

import {
  SubprocessConfigService,
  SubprocessConfigResponse,
  SubprocessConfigUpdate,
  SubprocessStatus,
  SystemInfo,
  JavaPathValidation
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
    MatExpansionModule
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

  // Edit form
  editEnabled = true;
  editJavaPath = 'java';
  editHeapSize = '4g';
  editOffHeapMaxBytes = '';
  editTimeoutMinutes = 60;
  editHeartbeatIntervalSeconds = 10;
  editStaleThresholdSeconds = 120;

  // Java path validation
  javaPathValid: boolean | null = null;
  javaVersionInfo = '';

  // Active processes
  activeProcesses: SubprocessStatus[] = [];
  displayedColumns = ['taskId', 'fileName', 'phase', 'progress', 'elapsed', 'actions'];

  // System info
  systemInfo: SystemInfo | null = null;

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

    // Poll active processes every 5 seconds
    interval(5000)
      .pipe(takeUntil(this.destroy$))
      .subscribe(() => {
        this.loadActiveProcesses();
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
        this.editEnabled = config.enabled;
        this.editJavaPath = config.javaPath;
        this.editHeapSize = config.heapSize;
        this.editOffHeapMaxBytes = config.offHeapMaxBytes || '';
        this.editTimeoutMinutes = config.timeoutMinutes;
        this.editHeartbeatIntervalSeconds = config.heartbeatIntervalSeconds;
        this.editStaleThresholdSeconds = config.staleThresholdSeconds;
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
        this.heapSizeOptions = ['1g', '2g', '4g', '6g', '8g', '12g', '16g'];
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

  saveConfiguration(): void {
    this.isSaving = true;
    this.cdr.markForCheck();

    const update: SubprocessConfigUpdate = {
      enabled: this.editEnabled,
      javaPath: this.editJavaPath,
      heapSize: this.editHeapSize,
      offHeapMaxBytes: this.editOffHeapMaxBytes,
      timeoutMinutes: this.editTimeoutMinutes,
      heartbeatIntervalSeconds: this.editHeartbeatIntervalSeconds,
      staleThresholdSeconds: this.editStaleThresholdSeconds
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
        this.editEnabled = config.enabled;
        this.editJavaPath = config.javaPath;
        this.editHeapSize = config.heapSize;
        this.editOffHeapMaxBytes = config.offHeapMaxBytes || '';
        this.editTimeoutMinutes = config.timeoutMinutes;
        this.editHeartbeatIntervalSeconds = config.heartbeatIntervalSeconds;
        this.editStaleThresholdSeconds = config.staleThresholdSeconds;
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
    if (memGb <= 4) return '1g';
    if (memGb <= 8) return '2g';
    if (memGb <= 16) return '4g';
    if (memGb <= 32) return '8g';
    return '12g';
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
