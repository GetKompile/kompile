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
import { HttpClient } from '@angular/common/http';
import { Subject } from 'rxjs';
import { takeUntil } from 'rxjs/operators';
import {
  WebSocketService, WorkerCapabilities, GpuInfo, ClusterWorkersUpdate, ClusterMigrationState
} from '../../../services/websocket.service';
import { backendUrl } from '../../../services/base.service';

/**
 * Real-time worker-management view: resources, connectivity, and lifecycle. Cold-loads from REST then
 * live-updates from {@code /topic/cluster/workers}. Lifecycle actions (drain/deregister/cancel) call the
 * orchestrator, which proxies to the worker.
 */
@Component({
  standalone: false,
  selector: 'app-cluster-workers',
  templateUrl: './cluster-workers.component.html',
  styleUrls: ['./cluster-workers.component.css']
})
export class ClusterWorkersComponent implements OnInit, OnDestroy {
  private destroy$ = new Subject<void>();

  workers: WorkerCapabilities[] = [];
  workerCount = 0;
  localSaturated = false;
  workerTimeoutSeconds = 45;
  migration: ClusterMigrationState | null = null;
  loading = true;
  error: string | null = null;
  lastUpdate: string | null = null;

  selectedWorkerId: string | null = null;
  workerJobs: { [workerId: string]: { jobId: string; status: string }[] } = {};
  actionMsg: string | null = null;

  sessions: any[] = [];

  constructor(
    private ws: WebSocketService,
    private http: HttpClient,
    private cdr: ChangeDetectorRef
  ) {}

  ngOnInit(): void {
    this.coldLoad();
    this.loadSessions();
    this.ws.connect();
    this.ws.subscribeToClusterWorkers().pipe(takeUntil(this.destroy$)).subscribe(update => {
      this.applyUpdate(update);
      this.cdr.markForCheck();
    });
  }

  ngOnDestroy(): void {
    this.destroy$.next();
    this.destroy$.complete();
    this.ws.unsubscribeFromClusterWorkers();
  }

  // ---- data ----
  private coldLoad(): void {
    this.http.get<WorkerCapabilities[]>(`${backendUrl}/cluster/workers`)
      .pipe(takeUntil(this.destroy$)).subscribe({
        next: w => { this.workers = w || []; this.workerCount = this.workers.length; this.loading = false; this.cdr.markForCheck(); },
        error: () => { this.loading = false; this.error = 'Failed to load cluster workers'; this.cdr.markForCheck(); }
      });
    this.http.get<any>(`${backendUrl}/scheduler/status`).pipe(takeUntil(this.destroy$)).subscribe({
      next: s => {
        this.localSaturated = !!(s?.governor?.localSaturated);
        if (s?.governor?.gpuToCpuMigration) { this.migration = s.governor.gpuToCpuMigration; }
        this.cdr.markForCheck();
      },
      error: () => { /* status optional */ }
    });
  }

  loadSessions(): void {
    this.http.get<any>(`${backendUrl}/distributed-crawl/sessions`).pipe(takeUntil(this.destroy$)).subscribe({
      next: r => { this.sessions = Array.isArray(r) ? r : (r?.sessions || []); this.cdr.markForCheck(); },
      error: () => { this.sessions = []; }
    });
  }

  private applyUpdate(u: ClusterWorkersUpdate): void {
    this.workers = u.workers || [];
    this.workerCount = u.workerCount ?? this.workers.length;
    if (u.localSaturated !== undefined) { this.localSaturated = u.localSaturated; }
    if (u.workerTimeoutSeconds) { this.workerTimeoutSeconds = u.workerTimeoutSeconds; }
    if (u.migration) { this.migration = u.migration; }
    this.lastUpdate = u.timestamp;
    this.loading = false;
  }

  // ---- resources ----
  freeSlots(w: WorkerCapabilities): number { return Math.max(0, w.maxConcurrentJobs - w.activeJobs); }
  loadPercent(w: WorkerCapabilities): number { return w.maxConcurrentJobs > 0 ? Math.round(100 * w.activeJobs / w.maxConcurrentJobs) : 0; }
  cpuPercent(w: WorkerCapabilities): number { return w.cpuLoad >= 0 ? Math.round(w.cpuLoad * 100) : -1; }
  ramPercent(w: WorkerCapabilities): number { return Math.round((w.ramUsedFraction || 0) * 100); }
  gpuPercent(g: GpuInfo): number { return Math.round((g.usedFraction || 0) * 100); }
  pressureClass(p: string): string { return 'pressure-' + (p || 'NOMINAL').toLowerCase(); }
  gb(bytes: number): string { return (bytes / 1e9).toFixed(1); }

  // ---- connectivity ----
  ageSeconds(w: WorkerCapabilities): number {
    return w.advertisedAtEpochMs ? Math.max(0, Math.round((Date.now() - w.advertisedAtEpochMs) / 1000)) : -1;
  }
  connectionStatus(w: WorkerCapabilities): 'healthy' | 'stale' | 'lost' {
    const age = this.ageSeconds(w);
    if (age < 0) { return 'lost'; }
    if (age >= this.workerTimeoutSeconds) { return 'lost'; }
    if (age >= this.workerTimeoutSeconds / 2) { return 'stale'; }
    return 'healthy';
  }
  lastSeen(w: WorkerCapabilities): string {
    const s = this.ageSeconds(w);
    if (s < 0) { return 'unknown'; }
    return s < 60 ? `${s}s ago` : `${Math.round(s / 60)}m ago`;
  }

  // ---- lifecycle ----
  select(w: WorkerCapabilities): void {
    this.selectedWorkerId = this.selectedWorkerId === w.workerId ? null : w.workerId;
    if (this.selectedWorkerId) { this.loadJobs(w); }
  }
  drain(w: WorkerCapabilities, on: boolean, ev?: Event): void {
    ev?.stopPropagation();
    this.http.post<any>(`${backendUrl}/cluster/workers/${encodeURIComponent(w.workerId)}/drain?on=${on}`, {})
      .subscribe({ next: () => this.flash(`${on ? 'Draining' : 'Resumed'} ${w.workerId}`), error: e => this.flash(`Drain failed: ${e?.error?.error || e.message}`) });
  }
  deregister(w: WorkerCapabilities, ev?: Event): void {
    ev?.stopPropagation();
    this.http.delete<any>(`${backendUrl}/cluster/workers/${encodeURIComponent(w.workerId)}`)
      .subscribe({ next: () => this.flash(`Deregistered ${w.workerId}`), error: e => this.flash(`Deregister failed: ${e?.error?.error || e.message}`) });
  }
  loadJobs(w: WorkerCapabilities): void {
    this.http.get<any>(`${backendUrl}/cluster/workers/${encodeURIComponent(w.workerId)}/jobs`).subscribe({
      next: r => {
        const jobsMap = (r && r.body) ? this.parseJobs(r.body) : (r?.jobs || {});
        this.workerJobs[w.workerId] = Object.keys(jobsMap).map(jobId => ({ jobId, status: jobsMap[jobId] }));
        this.cdr.markForCheck();
      },
      error: () => { this.workerJobs[w.workerId] = []; this.cdr.markForCheck(); }
    });
  }
  cancelJob(w: WorkerCapabilities, jobId: string, ev?: Event): void {
    ev?.stopPropagation();
    this.http.delete<any>(`${backendUrl}/cluster/workers/${encodeURIComponent(w.workerId)}/jobs/${encodeURIComponent(jobId)}`)
      .subscribe({ next: () => { this.flash(`Cancelled ${jobId}`); this.loadJobs(w); }, error: e => this.flash(`Cancel failed: ${e?.error?.error || e.message}`) });
  }
  private parseJobs(body: string): { [id: string]: string } {
    try { const p = JSON.parse(body); return p?.jobs || {}; } catch { return {}; }
  }
  jobsFor(workerId: string): { jobId: string; status: string }[] { return this.workerJobs[workerId] || []; }

  private flash(msg: string): void {
    this.actionMsg = msg;
    this.cdr.markForCheck();
    setTimeout(() => { this.actionMsg = null; this.cdr.markForCheck(); }, 4000);
  }

  trackByWorkerId(_i: number, w: WorkerCapabilities): string { return w.workerId; }
}
