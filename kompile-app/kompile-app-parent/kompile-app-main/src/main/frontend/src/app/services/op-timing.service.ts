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
import { BehaviorSubject, Observable } from 'rxjs';
import { tap } from 'rxjs/operators';
import { backendUrl } from './base.service';

/**
 * Statistics for a single operation type.
 */
export interface OpTimingStat {
  rank: number;
  opName: string;
  calls: number;
  totalMs: number;
  avgUs: number;
  stdDevUs: number;
  minUs: number;
  maxUs: number;
  helperPercent: number;
}

/**
 * Current op timing status.
 */
export interface OpTimingStatus {
  enabled: boolean;
  detailedMode: boolean;
  traceMode: boolean;
  lastFlushTime: number;
  cachedStatsCount: number;
  totalExecutions: number;
  nativeAvailable: boolean;
  subprocessAvailable?: boolean;
  subprocessNote?: string;
}

/**
 * Phase breakdown for a specific op.
 */
export interface PhaseInfo {
  phase: string;
  totalMs: number;
  avgUs: number;
  percent: number;
}

/**
 * Op breakdown response.
 */
export interface OpBreakdown {
  status: string;
  opName: string;
  breakdown: {
    phases: PhaseInfo[];
    helperPercent?: number;
    minUs?: number;
    maxUs?: number;
    stdDevUs?: number;
    p50Us?: number;
    p90Us?: number;
    p99Us?: number;
  };
  rawOutput: string;
}

/**
 * Histogram bucket.
 */
export interface HistogramBucket {
  rangeStart: number;
  rangeEnd: number;
  count: number;
  percent: number;
  bars: number;
}

/**
 * Op histogram response.
 */
export interface OpHistogram {
  status: string;
  opName: string;
  histogram: {
    buckets: HistogramBucket[];
    p50Us?: number;
    p90Us?: number;
    p99Us?: number;
  };
  rawOutput: string;
}

/**
 * Subprocess op timing stats (from embedding subprocess).
 */
export interface SubprocessOpTimingStats {
  available: boolean;
  success?: boolean;
  numOps?: number;
  totalExecutions?: number;
  hotspots?: OpTimingStat[];
  error?: string;
}

/**
 * Flush response with hotspot statistics.
 */
export interface FlushResponse {
  status: string;
  numOps: number;
  totalExecutions: number;
  flushTime: number;
  hotspots: OpTimingStat[];
  topN: number;
  message?: string;
  subprocess?: SubprocessOpTimingStats;  // Subprocess op timing (where actual inference runs)
}

/**
 * Export response.
 */
export interface ExportResponse {
  status: string;
  path: string;
  content: string;
  size: number;
  message?: string;
}

/**
 * Subprocess timing statistics.
 */
export interface SubprocessTimingStat {
  taskId: string;
  subprocessType: string;
  modelId?: string;
  startTimeMs: number;
  endTimeMs?: number;
  startupDurationMs: number;
  modelLoadDurationMs: number;
  totalDurationMs: number;
  ipcOverheadMs: number;
  totalOverheadMs: number;
  overheadPercent: number;
  ipcSendCount: number;
  ipcReceiveCount: number;
  success: boolean;
}

/**
 * Active subprocess timings response.
 */
export interface ActiveSubprocessResponse {
  activeCount: number;
  active: Array<{
    taskId: string;
    subprocessType: string;
    modelId?: string;
    startTimeMs: number;
    elapsedMs: number;
    startupDurationMs: number;
    modelLoadDurationMs: number;
    ipcSendCount: number;
    ipcReceiveCount: number;
  }>;
}

/**
 * Subprocess timing history response.
 */
export interface SubprocessHistoryResponse {
  count: number;
  history: SubprocessTimingStat[];
  aggregates?: {
    avgStartupMs: number;
    avgModelLoadMs: number;
    avgIpcOverheadMs: number;
    avgTotalOverheadMs: number;
    avgTotalDurationMs: number;
    avgOverheadPercent: number;
    successRate: number;
  };
}

/**
 * Shared state for op timing.
 */
export interface OpTimingState {
  enabled: boolean;
  detailedMode: boolean;
  traceMode: boolean;
  stats: OpTimingStat[];
  nativeAvailable: boolean;
  subprocessStats?: SubprocessOpTimingStats;
}

/**
 * Service for interacting with the ND4J Op Timing profiling API.
 * Provides methods to enable/disable profiling, capture timing data,
 * and export statistics for performance analysis.
 *
 * State is shared via state$ observable so multiple components stay in sync.
 */
@Injectable({
  providedIn: 'root'
})
export class OpTimingService {
  private readonly baseUrl = `${backendUrl}/op-timing`;

  // Shared state that all components can subscribe to
  private stateSubject = new BehaviorSubject<OpTimingState>({
    enabled: false,
    detailedMode: true,
    traceMode: false,
    stats: [],
    nativeAvailable: true
  });

  /** Observable of current op timing state - subscribe to stay in sync */
  state$ = this.stateSubject.asObservable();

  constructor(private http: HttpClient) {
    // Load initial state from backend
    this.refreshStatus();
  }

  /** Get current state snapshot */
  getState(): OpTimingState {
    return this.stateSubject.getValue();
  }

  /** Update state and notify subscribers */
  private updateState(partial: Partial<OpTimingState>): void {
    this.stateSubject.next({ ...this.stateSubject.getValue(), ...partial });
  }

  /** Refresh status from backend */
  refreshStatus(): void {
    this.getStatus().subscribe({
      next: (status) => {
        this.updateState({
          enabled: status.enabled,
          detailedMode: status.detailedMode,
          traceMode: status.traceMode,
          nativeAvailable: status.nativeAvailable !== false
        });
      },
      error: () => {} // Silently fail on initial load
    });
  }

  /**
   * Get current op timing status.
   */
  getStatus(): Observable<OpTimingStatus> {
    return this.http.get<OpTimingStatus>(`${this.baseUrl}/status`).pipe(
      tap(status => {
        this.updateState({
          enabled: status.enabled,
          detailedMode: status.detailedMode,
          traceMode: status.traceMode,
          nativeAvailable: status.nativeAvailable !== false
        });
      })
    );
  }

  /**
   * Enable op timing profiling.
   * @param detailed if true, enable detailed phase-level timing (higher overhead)
   */
  enableTiming(detailed: boolean = false): Observable<any> {
    return this.http.post<any>(`${this.baseUrl}/enable`, null, {
      params: { detailed: detailed.toString() }
    }).pipe(
      tap(response => {
        if (response.status === 'success') {
          this.updateState({
            enabled: true,
            detailedMode: detailed,
            traceMode: false
          });
        }
      })
    );
  }

  /**
   * Enable op timing with trace mode for Chrome trace export.
   * @param detailed if true, enable detailed phase-level timing
   */
  enableTimingWithTrace(detailed: boolean = true): Observable<any> {
    return this.http.post<any>(`${this.baseUrl}/enable-trace`, null, {
      params: { detailed: detailed.toString() }
    }).pipe(
      tap(response => {
        if (response.status === 'success') {
          this.updateState({
            enabled: true,
            detailedMode: detailed,
            traceMode: true
          });
        }
      })
    );
  }

  /**
   * Disable op timing profiling.
   */
  disableTiming(): Observable<any> {
    return this.http.post<any>(`${this.baseUrl}/disable`, null).pipe(
      tap(response => {
        if (response.status === 'success') {
          this.updateState({
            enabled: false,
            traceMode: false
          });
        }
      })
    );
  }

  /**
   * Flush timing data and get hotspot statistics.
   * @param topN number of top ops to return (default 20)
   */
  flushAndGetStats(topN: number = 20): Observable<FlushResponse> {
    console.log('=== OpTimingService.flushAndGetStats called === topN:', topN, 'url:', `${this.baseUrl}/flush`);
    return this.http.post<FlushResponse>(`${this.baseUrl}/flush`, null, {
      params: { topN: topN.toString() }
    }).pipe(
      tap(response => {
        console.log('=== OpTimingService.flushAndGetStats tap === response:', response);
        // Update shared state with both main JVM and subprocess stats
        this.updateState({
          stats: response.hotspots || [],
          subprocessStats: response.subprocess
        });
      })
    );
  }

  /**
   * Get cached statistics without flushing.
   */
  getCachedStats(): Observable<any> {
    return this.http.get<any>(`${this.baseUrl}/stats`);
  }

  /**
   * Get phase breakdown for a specific operation.
   * @param opName the operation name (e.g., "matmul", "conv2d")
   */
  getOpBreakdown(opName: string): Observable<OpBreakdown> {
    return this.http.get<OpBreakdown>(`${this.baseUrl}/breakdown/${encodeURIComponent(opName)}`);
  }

  /**
   * Get timing histogram for a specific operation.
   * @param opName the operation name
   */
  getOpHistogram(opName: string): Observable<OpHistogram> {
    return this.http.get<OpHistogram>(`${this.baseUrl}/histogram/${encodeURIComponent(opName)}`);
  }

  /**
   * Get per-thread timing statistics.
   */
  getThreadStats(): Observable<any> {
    return this.http.get<any>(`${this.baseUrl}/thread-stats`);
  }

  /**
   * Export timing data to Chrome trace JSON format.
   */
  exportChromeTrace(): Observable<ExportResponse> {
    return this.http.get<ExportResponse>(`${this.baseUrl}/export/chrome-trace`);
  }

  /**
   * Export timing data to CSV format.
   */
  exportCSV(): Observable<ExportResponse> {
    return this.http.get<ExportResponse>(`${this.baseUrl}/export/csv`);
  }

  /**
   * Reset all timing data.
   */
  reset(): Observable<any> {
    return this.http.post<any>(`${this.baseUrl}/reset`, null).pipe(
      tap(() => {
        this.updateState({ stats: [] });
      })
    );
  }

  // ===================== SUBPROCESS OVERHEAD METHODS =====================

  /**
   * Get currently active subprocess timings.
   */
  getActiveSubprocessTimings(): Observable<ActiveSubprocessResponse> {
    return this.http.get<ActiveSubprocessResponse>(`${this.baseUrl}/subprocess/active`);
  }

  /**
   * Get subprocess timing history.
   * @param limit maximum number of entries to return (default 50)
   */
  getSubprocessTimingHistory(limit: number = 50): Observable<SubprocessHistoryResponse> {
    return this.http.get<SubprocessHistoryResponse>(`${this.baseUrl}/subprocess/history`, {
      params: { limit: limit.toString() }
    });
  }

  /**
   * Clear subprocess timing history.
   */
  clearSubprocessTimingHistory(): Observable<any> {
    return this.http.post<any>(`${this.baseUrl}/subprocess/clear`, null);
  }
}
