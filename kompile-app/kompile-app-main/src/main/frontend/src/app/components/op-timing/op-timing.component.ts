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

import { Component, OnInit, OnDestroy } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { MatCardModule } from '@angular/material/card';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatTooltipModule } from '@angular/material/tooltip';
import { MatExpansionModule } from '@angular/material/expansion';
import { MatDividerModule } from '@angular/material/divider';
import { MatChipsModule } from '@angular/material/chips';
import { MatSlideToggleModule } from '@angular/material/slide-toggle';
import { MatTableModule } from '@angular/material/table';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatTabsModule } from '@angular/material/tabs';
import { Subject } from 'rxjs';
import { takeUntil } from 'rxjs/operators';

import {
  OpTimingService,
  OpTimingStatus,
  OpTimingStat,
  FlushResponse,
  OpBreakdown,
  OpHistogram,
  SubprocessTimingStat,
  ActiveSubprocessResponse,
  SubprocessHistoryResponse,
  SubprocessOpTimingStats
} from '../../services/op-timing.service';

@Component({
  selector: 'app-op-timing',
  standalone: true,
  imports: [
    CommonModule,
    FormsModule,
    MatCardModule,
    MatButtonModule,
    MatIconModule,
    MatProgressSpinnerModule,
    MatTooltipModule,
    MatExpansionModule,
    MatDividerModule,
    MatChipsModule,
    MatSlideToggleModule,
    MatTableModule,
    MatSnackBarModule,
    MatProgressBarModule,
    MatTabsModule
  ],
  templateUrl: './op-timing.component.html',
  styleUrls: ['./op-timing.component.css']
})
export class OpTimingComponent implements OnInit, OnDestroy {
  private destroy$ = new Subject<void>();

  // Status
  status: OpTimingStatus | null = null;
  loading = false;
  error: string | null = null;

  // Settings
  detailedMode = true;
  traceMode = false;
  topN = 20;

  // Statistics
  hotspots: OpTimingStat[] = [];
  totalExecutions = 0;
  numOps = 0;
  lastFlushTime: Date | null = null;

  // Subprocess op timing (where actual inference ops run)
  subprocessOpTiming: SubprocessOpTimingStats | null = null;

  // Selected op for breakdown
  selectedOp: string | null = null;
  opBreakdown: OpBreakdown | null = null;
  opHistogram: OpHistogram | null = null;
  loadingBreakdown = false;

  // Export data
  chromeTraceContent: string | null = null;
  csvContent: string | null = null;

  // Subprocess overhead tracking
  activeSubprocesses: ActiveSubprocessResponse | null = null;
  subprocessHistory: SubprocessHistoryResponse | null = null;
  loadingSubprocess = false;

  // Table columns
  displayedColumns = ['rank', 'opName', 'calls', 'totalMs', 'avgUs', 'stdDevUs', 'helperPercent', 'actions'];
  subprocessColumns = ['taskId', 'subprocessType', 'startupDurationMs', 'modelLoadDurationMs', 'ipcOverheadMs', 'totalOverheadMs', 'overheadPercent', 'success'];

  constructor(
    private opTimingService: OpTimingService,
    private snackBar: MatSnackBar
  ) {}

  ngOnInit(): void {
    this.loadStatus();
    this.loadSubprocessData();

    // Subscribe to shared state so we get updates from any component calling flush
    this.opTimingService.state$
      .pipe(takeUntil(this.destroy$))
      .subscribe(state => {
        // Update subprocess op timing from shared state
        if (state.subprocessStats) {
          this.subprocessOpTiming = state.subprocessStats;
          console.log('=== Op Timing Component: received subprocess stats from shared state ===', state.subprocessStats);
        }
        // Also update main JVM stats if we have them
        if (state.stats && state.stats.length > 0) {
          this.hotspots = state.stats;
        }
      });
  }

  ngOnDestroy(): void {
    this.destroy$.next();
    this.destroy$.complete();
  }

  loadStatus(): void {
    this.loading = true;
    this.error = null;

    this.opTimingService.getStatus()
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: (status) => {
          this.status = status;
          this.detailedMode = status.detailedMode;
          this.traceMode = status.traceMode;
          this.totalExecutions = status.totalExecutions;
          if (status.lastFlushTime > 0) {
            this.lastFlushTime = new Date(status.lastFlushTime);
          }
          this.loading = false;
        },
        error: (err) => {
          this.error = err?.message || 'Failed to load op timing status';
          this.loading = false;
        }
      });
  }

  enableTiming(): void {
    this.loading = true;

    const enable$ = this.traceMode
      ? this.opTimingService.enableTimingWithTrace(this.detailedMode)
      : this.opTimingService.enableTiming(this.detailedMode);

    enable$.pipe(takeUntil(this.destroy$))
      .subscribe({
        next: (response) => {
          this.showSnackbar(response.message || 'Op timing enabled');
          this.loadStatus();
        },
        error: (err) => {
          this.showSnackbar('Failed to enable op timing: ' + (err.message || err), true);
          this.loading = false;
        }
      });
  }

  disableTiming(): void {
    this.loading = true;

    this.opTimingService.disableTiming()
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: (response) => {
          this.showSnackbar(response.message || 'Op timing disabled');
          this.loadStatus();
        },
        error: (err) => {
          this.showSnackbar('Failed to disable op timing: ' + (err.message || err), true);
          this.loading = false;
        }
      });
  }

  flushStats(): void {
    console.log('=== FLUSH STATS METHOD CALLED === topN:', this.topN);
    this.loading = true;

    this.opTimingService.flushAndGetStats(this.topN)
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: (response: FlushResponse) => {
          console.log('=== FLUSH RESPONSE ===', response);
          console.log('subprocess data:', response.subprocess);
          console.log('subprocess available:', response.subprocess?.available);
          console.log('subprocess success:', response.subprocess?.success);
          console.log('subprocess hotspots count:', response.subprocess?.hotspots?.length);

          this.hotspots = response.hotspots || [];
          this.totalExecutions = response.totalExecutions;
          this.numOps = response.numOps;
          this.lastFlushTime = new Date(response.flushTime);

          // Store subprocess op timing data (this is where actual inference ops run!)
          this.subprocessOpTiming = response.subprocess || null;
          console.log('subprocessOpTiming set to:', this.subprocessOpTiming);

          this.loading = false;

          // Build message
          let message = `Main JVM: ${response.numOps} ops, ${response.totalExecutions} executions`;
          if (response.subprocess?.available && response.subprocess?.success) {
            message += ` | Subprocess: ${response.subprocess.numOps} ops, ${response.subprocess.totalExecutions} executions`;
          }
          this.showSnackbar(message);
        },
        error: (err) => {
          console.error('Flush stats error:', err);
          this.showSnackbar('Failed to flush stats: ' + (err.message || err), true);
          this.loading = false;
        }
      });
  }

  selectOp(opName: string): void {
    this.selectedOp = opName;
    this.loadOpDetails(opName);
  }

  loadOpDetails(opName: string): void {
    this.loadingBreakdown = true;
    this.opBreakdown = null;
    this.opHistogram = null;

    // Load breakdown and histogram in parallel
    this.opTimingService.getOpBreakdown(opName)
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: (breakdown) => {
          this.opBreakdown = breakdown;
        },
        error: (err) => {
          console.error('Failed to load breakdown', err);
        }
      });

    this.opTimingService.getOpHistogram(opName)
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: (histogram) => {
          this.opHistogram = histogram;
          this.loadingBreakdown = false;
        },
        error: (err) => {
          console.error('Failed to load histogram', err);
          this.loadingBreakdown = false;
        }
      });
  }

  resetTiming(): void {
    this.loading = true;

    this.opTimingService.reset()
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: () => {
          this.hotspots = [];
          this.totalExecutions = 0;
          this.numOps = 0;
          this.lastFlushTime = null;
          this.selectedOp = null;
          this.opBreakdown = null;
          this.opHistogram = null;
          this.loading = false;
          this.showSnackbar('Op timing data reset');
        },
        error: (err) => {
          this.showSnackbar('Failed to reset: ' + (err.message || err), true);
          this.loading = false;
        }
      });
  }

  exportChromeTrace(): void {
    this.loading = true;

    this.opTimingService.exportChromeTrace()
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: (response) => {
          this.chromeTraceContent = response.content;
          this.loading = false;

          // Download the file
          this.downloadFile(response.content, 'nd4j_trace.json', 'application/json');
          this.showSnackbar('Chrome trace exported');
        },
        error: (err) => {
          this.showSnackbar('Failed to export: ' + (err.message || err), true);
          this.loading = false;
        }
      });
  }

  exportCSV(): void {
    this.loading = true;

    this.opTimingService.exportCSV()
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: (response) => {
          this.csvContent = response.content;
          this.loading = false;

          // Download the file
          this.downloadFile(response.content, 'nd4j_timing.csv', 'text/csv');
          this.showSnackbar('CSV exported');
        },
        error: (err) => {
          this.showSnackbar('Failed to export: ' + (err.message || err), true);
          this.loading = false;
        }
      });
  }

  private downloadFile(content: string, filename: string, mimeType: string): void {
    const blob = new Blob([content], { type: mimeType });
    const url = window.URL.createObjectURL(blob);
    const link = document.createElement('a');
    link.href = url;
    link.download = filename;
    link.click();
    window.URL.revokeObjectURL(url);
  }

  formatNumber(value: number, decimals: number = 2): string {
    if (value === undefined || value === null) return 'N/A';
    return value.toFixed(decimals);
  }

  formatDuration(ms: number): string {
    if (ms < 1) return `${(ms * 1000).toFixed(1)} us`;
    if (ms < 1000) return `${ms.toFixed(2)} ms`;
    return `${(ms / 1000).toFixed(2)} s`;
  }

  getHelperClass(percent: number): string {
    if (percent >= 90) return 'helper-high';
    if (percent >= 50) return 'helper-medium';
    if (percent > 0) return 'helper-low';
    return 'helper-none';
  }

  getHistogramBarWidth(bucket: any): string {
    if (!this.opHistogram?.histogram?.buckets) return '0%';
    const maxCount = Math.max(...this.opHistogram.histogram.buckets.map(b => b.count));
    if (maxCount === 0) return '0%';
    return `${(bucket.count / maxCount) * 100}%`;
  }

  private showSnackbar(message: string, isError: boolean = false): void {
    this.snackBar.open(message, 'Close', {
      duration: isError ? 5000 : 3000,
      horizontalPosition: 'center',
      verticalPosition: 'top',
      panelClass: isError ? 'snackbar-error' : 'snackbar-success'
    });
  }

  // ===================== SUBPROCESS OVERHEAD METHODS =====================

  loadSubprocessData(): void {
    this.loadingSubprocess = true;

    // Load both active and history in parallel
    this.opTimingService.getActiveSubprocessTimings()
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: (response) => {
          this.activeSubprocesses = response;
        },
        error: (err) => {
          console.error('Failed to load active subprocesses', err);
        }
      });

    this.opTimingService.getSubprocessTimingHistory(50)
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: (response) => {
          this.subprocessHistory = response;
          this.loadingSubprocess = false;
        },
        error: (err) => {
          console.error('Failed to load subprocess history', err);
          this.loadingSubprocess = false;
        }
      });
  }

  refreshSubprocessData(): void {
    this.loadSubprocessData();
  }

  clearSubprocessHistory(): void {
    this.loadingSubprocess = true;

    this.opTimingService.clearSubprocessTimingHistory()
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: () => {
          this.showSnackbar('Subprocess timing history cleared');
          this.loadSubprocessData();
        },
        error: (err) => {
          this.showSnackbar('Failed to clear subprocess history: ' + (err.message || err), true);
          this.loadingSubprocess = false;
        }
      });
  }

  getSubprocessTypeIcon(type: string): string {
    switch (type?.toUpperCase()) {
      case 'EMBEDDING':
        return 'psychology';
      case 'VECTOR_POPULATION':
        return 'storage';
      case 'INGEST':
        return 'upload_file';
      case 'MODEL_INIT':
        return 'memory';
      default:
        return 'terminal';
    }
  }

  getOverheadClass(percent: number): string {
    if (percent >= 50) return 'overhead-high';
    if (percent >= 25) return 'overhead-medium';
    if (percent >= 10) return 'overhead-low';
    return 'overhead-minimal';
  }
}
