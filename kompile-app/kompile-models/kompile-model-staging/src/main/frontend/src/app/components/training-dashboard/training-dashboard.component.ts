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
import { Router } from '@angular/router';
import { MatSnackBar } from '@angular/material/snack-bar';
import { TrainingService } from '../../services/training.service';
import { TrainingJobStatus, getTrainingStatusColor, getTrainingStatusIcon } from '../../models/api-models';

@Component({
  selector: 'app-training-dashboard',
  standalone: false,
  templateUrl: './training-dashboard.component.html',
  styleUrls: ['./training-dashboard.component.css']
})
export class TrainingDashboardComponent implements OnInit, OnDestroy {
  jobs: TrainingJobStatus[] = [];
  loading = false;
  expandedJobId: string | null = null;
  eventSources: Map<string, EventSource> = new Map();
  private refreshInterval: any;

  // Per-job SSE data
  jobLogs: Map<string, any[]> = new Map();
  jobMetrics: Map<string, any[]> = new Map();

  displayedColumns = ['status', 'jobId', 'modelId', 'progress', 'loss', 'epoch', 'elapsed', 'actions'];

  constructor(
    private trainingService: TrainingService,
    private router: Router,
    private snackBar: MatSnackBar
  ) {}

  ngOnInit(): void {
    this.loadJobs();
    this.refreshInterval = setInterval(() => this.loadJobs(), 5000);
  }

  ngOnDestroy(): void {
    if (this.refreshInterval) clearInterval(this.refreshInterval);
    this.eventSources.forEach(es => es.close());
    this.eventSources.clear();
  }

  loadJobs(): void {
    this.trainingService.getJobs().subscribe({
      next: (jobs) => { this.jobs = jobs; this.loading = false; },
      error: () => this.loading = false
    });
  }

  newTraining(): void {
    this.router.navigate(['/training/new']);
  }

  cancelJob(jobId: string): void {
    this.trainingService.cancelJob(jobId).subscribe({
      next: () => { this.snackBar.open('Job cancelled', 'Close', { duration: 3000 }); this.loadJobs(); },
      error: (err) => this.snackBar.open('Cancel failed: ' + err.message, 'Close', { duration: 5000 })
    });
  }

  viewMetrics(jobId: string): void {
    this.router.navigate(['/training', jobId, 'metrics']);
  }

  toggleExpand(jobId: string): void {
    if (this.expandedJobId === jobId) {
      this.expandedJobId = null;
      const es = this.eventSources.get(jobId);
      if (es) { es.close(); this.eventSources.delete(jobId); }
    } else {
      // Close any previously expanded job's stream
      if (this.expandedJobId) {
        const old = this.eventSources.get(this.expandedJobId);
        if (old) { old.close(); this.eventSources.delete(this.expandedJobId); }
      }
      this.expandedJobId = jobId;

      // Initialize per-job buffers
      if (!this.jobLogs.has(jobId)) this.jobLogs.set(jobId, []);
      if (!this.jobMetrics.has(jobId)) this.jobMetrics.set(jobId, []);

      // Open SSE stream for live log/metrics/status updates
      const es = this.trainingService.connectToJobStream(jobId);
      this.eventSources.set(jobId, es);

      es.addEventListener('log', (event: MessageEvent) => {
        try {
          const entry = JSON.parse(event.data);
          const logs = this.jobLogs.get(jobId) || [];
          logs.push(entry);
          // Keep last 200 log entries
          if (logs.length > 200) logs.splice(0, logs.length - 200);
          this.jobLogs.set(jobId, logs);
        } catch (e) { /* ignore parse errors */ }
      });

      es.addEventListener('metrics', (event: MessageEvent) => {
        try {
          const snapshot = JSON.parse(event.data);
          const metrics = this.jobMetrics.get(jobId) || [];
          metrics.push(snapshot);
          if (metrics.length > 100) metrics.splice(0, metrics.length - 100);
          this.jobMetrics.set(jobId, metrics);
        } catch (e) { /* ignore parse errors */ }
      });

      es.addEventListener('status', (event: MessageEvent) => {
        try {
          const status = JSON.parse(event.data);
          // Update the job in the list with latest status
          const idx = this.jobs.findIndex(j => j.jobId === jobId);
          if (idx >= 0) {
            this.jobs[idx] = { ...this.jobs[idx], ...status };
          }
        } catch (e) { /* ignore parse errors */ }
      });

      es.onerror = () => {
        es.close();
        this.eventSources.delete(jobId);
      };
    }
  }

  getStatusColor(status: string): string { return getTrainingStatusColor(status as any); }
  getStatusIcon(status: string): string { return getTrainingStatusIcon(status as any); }

  formatElapsed(ms: number): string {
    if (!ms) return '-';
    const s = Math.floor(ms / 1000);
    const m = Math.floor(s / 60);
    const h = Math.floor(m / 60);
    if (h > 0) return `${h}h ${m % 60}m`;
    if (m > 0) return `${m}m ${s % 60}s`;
    return `${s}s`;
  }
}
