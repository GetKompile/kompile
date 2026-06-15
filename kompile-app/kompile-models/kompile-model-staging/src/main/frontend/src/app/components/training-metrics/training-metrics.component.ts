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
import { ActivatedRoute } from '@angular/router';
import { MatSnackBar } from '@angular/material/snack-bar';
import { TrainingService } from '../../services/training.service';
import { TrainingJobStatus, TrainingMetricsSnapshot, TrainingLogEntry } from '../../models/api-models';

@Component({
  selector: 'app-training-metrics',
  standalone: false,
  templateUrl: './training-metrics.component.html',
  styleUrls: ['./training-metrics.component.css']
})
export class TrainingMetricsComponent implements OnInit, OnDestroy {
  jobId: string = '';
  job: TrainingJobStatus | null = null;
  metricsHistory: TrainingMetricsSnapshot[] = [];
  logs: TrainingLogEntry[] = [];
  eventSource: EventSource | null = null;
  selectedTab = 0;

  // Chart data (simple arrays for display)
  lossData: { step: number; value: number }[] = [];
  lrData: { step: number; value: number }[] = [];
  throughputData: { step: number; value: number }[] = [];

  constructor(
    private route: ActivatedRoute,
    private trainingService: TrainingService,
    private snackBar: MatSnackBar
  ) {}

  ngOnInit(): void {
    this.jobId = this.route.snapshot.paramMap.get('jobId') || '';
    if (this.jobId) {
      this.loadJob();
      this.loadMetrics();
      this.loadLogs();
      this.connectStream();
    }
  }

  ngOnDestroy(): void {
    if (this.eventSource) {
      this.eventSource.close();
      this.eventSource = null;
    }
  }

  loadJob(): void {
    this.trainingService.getJob(this.jobId).subscribe({
      next: (job) => this.job = job,
      error: () => {}
    });
  }

  loadMetrics(): void {
    this.trainingService.getMetricsHistory(this.jobId).subscribe({
      next: (metrics) => {
        this.metricsHistory = metrics;
        this.updateChartData();
      },
      error: () => {}
    });
  }

  loadLogs(): void {
    this.trainingService.getJobLogs(this.jobId).subscribe({
      next: (logs) => this.logs = logs,
      error: () => {}
    });
  }

  connectStream(): void {
    this.eventSource = this.trainingService.connectToJobStream(this.jobId);

    this.eventSource.addEventListener('log', (event: any) => {
      try {
        const entry = JSON.parse(event.data);
        this.logs.push(entry);
      } catch (e) {}
    });

    this.eventSource.addEventListener('metrics', (event: any) => {
      try {
        const snapshot = JSON.parse(event.data);
        this.metricsHistory.push(snapshot);
        this.updateChartData();
        // Update job status
        if (this.job) {
          this.job.loss = snapshot.trainLoss;
          this.job.learningRate = snapshot.learningRate;
          this.job.currentStep = snapshot.step;
          this.job.currentEpoch = snapshot.epoch;
        }
      } catch (e) {}
    });

    this.eventSource.addEventListener('status', (event: any) => {
      try {
        const status = JSON.parse(event.data);
        this.job = status;
      } catch (e) {}
    });

    this.eventSource.onerror = () => {
      // Reload final state
      this.loadJob();
      this.loadMetrics();
    };
  }

  updateChartData(): void {
    this.lossData = this.metricsHistory.map(m => ({ step: m.step, value: m.trainLoss }));
    this.lrData = this.metricsHistory.map(m => ({ step: m.step, value: m.learningRate }));
    this.throughputData = this.metricsHistory.map(m => ({ step: m.step, value: m.samplesPerSecond }));
  }

  exportCsv(): void {
    if (this.metricsHistory.length === 0) return;
    const headers = ['step', 'epoch', 'train_loss', 'eval_loss', 'learning_rate', 'tokens_per_second', 'samples_per_second'];
    const rows = this.metricsHistory.map(m =>
      [m.step, m.epoch, m.trainLoss, m.evalLoss || '', m.learningRate, m.tokensPerSecond, m.samplesPerSecond].join(',')
    );
    const csv = [headers.join(','), ...rows].join('\n');
    const blob = new Blob([csv], { type: 'text/csv' });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = `training-metrics-${this.jobId}.csv`;
    a.click();
    URL.revokeObjectURL(url);
  }

  getMinLoss(): number {
    if (this.lossData.length === 0) return 0;
    return Math.min(...this.lossData.map(d => d.value));
  }

  getMaxLoss(): number {
    if (this.lossData.length === 0) return 0;
    return Math.max(...this.lossData.map(d => d.value));
  }

  getAvgThroughput(): number {
    if (this.throughputData.length === 0) return 0;
    return this.throughputData.reduce((sum, d) => sum + d.value, 0) / this.throughputData.length;
  }
}
