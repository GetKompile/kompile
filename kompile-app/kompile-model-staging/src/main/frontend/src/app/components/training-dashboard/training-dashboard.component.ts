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
      this.expandedJobId = jobId;
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
