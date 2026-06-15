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
import { BehaviorSubject, Observable, Subject } from 'rxjs';
import { environment } from '../../environments/environment';

export interface TrainingMetricsSnapshot {
  step: number;
  epoch: number;
  trainLoss: number;
  evalLoss: number;
  learningRate: number;
  tokensPerSecond: number;
  samplesPerSecond: number;
  customMetrics: Record<string, number>;
  // DSP (Dynamic Shape Plan) monitoring fields
  dspPlanPhase?: string;
  dspReplayCacheHits?: number;
  dspReplayCacheMisses?: number;
  dspFrozenExecutionCount?: number;
  dspNumSegments?: number;
  dspCapturedGraphSegments?: number;
  dspRecompilationCount?: number;
  dspStepTimeMs?: number;
  dspSegmentSummary?: string;
  // Per-segment execution breakdown
  dspSegmentsWarmup?: number;
  dspSegmentsReplayed?: number;
  dspSegmentsCaptured?: number;
  dspSegmentsSlotBySlot?: number;
  dspSegmentsFailed?: number;
  // Buffer pool stats
  dspBufferPoolBytes?: number;
  dspBufferPoolReused?: number;
  dspColoringSavedBytes?: number;
  // GPU memory
  gpuMemUsedBytes?: number;
  gpuMemFreeBytes?: number;
  gpuMemTotalBytes?: number;
  gpuPoolUsedBytes?: number;
  gpuPoolReservedBytes?: number;
  numGpuDevices?: number;
  gpuDeviceNames?: string;
  // JVM heap
  heapUsedBytes?: number;
  heapMaxBytes?: number;
  heapUsagePercent?: number;
}

export interface TrainingLogEntry {
  timestamp: string;
  level: string;
  message: string;
  step: number;
  loss: number;
  learningRate: number;
  metrics: Record<string, number>;
}

export interface DspAlertEvent {
  jobId: string;
  step: number;
  recompilationCount: number;
  planPhase: string;
  message: string;
}

export interface TrainingCompletedEvent {
  jobId: string;
  status: string;
  modelId: string;
  registeredModelId: string | null;
  outputPath: string;
  finalLoss: number;
  totalSteps: number;
  totalEpochs: number;
}

export interface EvalCompleteEvent {
  jobId: string;
  modelId: string;
  results: Record<string, Record<string, number>>;
}

@Injectable({ providedIn: 'root' })
export class TrainingMetricsService {
  private baseUrl = `${environment.apiUrl}/training`;
  private eventSource: EventSource | null = null;

  metrics$ = new BehaviorSubject<TrainingMetricsSnapshot[]>([]);
  logs$ = new BehaviorSubject<TrainingLogEntry[]>([]);
  liveMetric$ = new Subject<TrainingMetricsSnapshot>();
  liveLog$ = new Subject<TrainingLogEntry>();
  statusUpdate$ = new Subject<any>();
  connected$ = new BehaviorSubject<boolean>(false);
  metricsReady$ = new BehaviorSubject<boolean>(false);
  dspAlert$ = new Subject<DspAlertEvent>();
  completed$ = new Subject<TrainingCompletedEvent>();
  evalComplete$ = new Subject<EvalCompleteEvent>();
  error$ = new Subject<string>();

  constructor(private http: HttpClient) {}

  /** Fetch full metrics history for a job (REST). */
  getMetricsHistory(jobId: string): Observable<TrainingMetricsSnapshot[]> {
    return this.http.get<TrainingMetricsSnapshot[]>(`${this.baseUrl}/jobs/${jobId}/metrics`);
  }

  /** Fetch full log history for a job (REST). */
  getLogHistory(jobId: string): Observable<TrainingLogEntry[]> {
    return this.http.get<TrainingLogEntry[]>(`${this.baseUrl}/jobs/${jobId}/logs`);
  }

  /** Connect to SSE stream for real-time updates. */
  connectToStream(jobId: string): void {
    this.disconnect();
    this.metrics$.next([]);
    this.logs$.next([]);
    this.metricsReady$.next(false);

    const url = `${this.baseUrl}/jobs/${jobId}/stream`;
    this.eventSource = new EventSource(url);
    this.connected$.next(true);

    this.eventSource.addEventListener('metrics', (event: any) => {
      try {
        const snapshot: TrainingMetricsSnapshot = JSON.parse(event.data);
        const current = this.metrics$.value;
        this.metrics$.next([...current, snapshot]);
        this.liveMetric$.next(snapshot);
      } catch (e) { /* ignore parse errors */ }
    });

    this.eventSource.addEventListener('log', (event: any) => {
      try {
        const entry: TrainingLogEntry = JSON.parse(event.data);
        const current = this.logs$.value;
        // Keep last 500 log entries
        const updated = [...current, entry];
        if (updated.length > 500) updated.splice(0, updated.length - 500);
        this.logs$.next(updated);
        this.liveLog$.next(entry);
      } catch (e) { /* ignore parse errors */ }
    });

    this.eventSource.addEventListener('status', (event: any) => {
      try {
        const status = JSON.parse(event.data);
        this.statusUpdate$.next(status);
      } catch (e) { /* ignore parse errors */ }
    });

    this.eventSource.addEventListener('ready', (event: any) => {
      this.metricsReady$.next(true);
    });

    this.eventSource.addEventListener('heartbeat', () => {
      // Keepalive — no action needed, prevents SSE timeout
    });

    this.eventSource.addEventListener('dsp_alert', (event: any) => {
      try {
        const alert: DspAlertEvent = JSON.parse(event.data);
        this.dspAlert$.next(alert);
      } catch (e) { /* ignore parse errors */ }
    });

    this.eventSource.addEventListener('completed', (event: any) => {
      try {
        const completedEvent: TrainingCompletedEvent = JSON.parse(event.data);
        this.completed$.next(completedEvent);
      } catch (e) { /* ignore parse errors */ }
    });

    this.eventSource.addEventListener('eval_complete', (event: any) => {
      try {
        const evalEvent: EvalCompleteEvent = JSON.parse(event.data);
        this.evalComplete$.next(evalEvent);
      } catch (e) { /* ignore parse errors */ }
    });

    this.eventSource.onerror = () => {
      this.error$.next('Training stream connection lost');
      this.connected$.next(false);
    };
  }

  /** Disconnect from SSE stream. */
  disconnect(): void {
    if (this.eventSource) {
      this.eventSource.close();
      this.eventSource = null;
    }
    this.connected$.next(false);
  }

  /** Load historical metrics and logs (for completed/failed jobs). */
  loadHistorical(jobId: string): void {
    this.metrics$.next([]);
    this.logs$.next([]);

    this.getMetricsHistory(jobId).subscribe({
      next: m => this.metrics$.next(m || []),
      error: () => this.metrics$.next([])
    });

    this.getLogHistory(jobId).subscribe({
      next: l => this.logs$.next(l || []),
      error: () => this.logs$.next([])
    });
  }
}
