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

import { Component, OnInit } from '@angular/core';
import { ActivatedRoute, Router } from '@angular/router';
import { MatSnackBar } from '@angular/material/snack-bar';
import { ExperimentService } from '../../services/experiment.service';
import { StagingService } from '../../services/staging.service';
import {
  ExperimentWithRuns,
  ExperimentRun,
  ModelRegistry,
  getExperimentStatusColor,
  getExperimentStatusIcon
} from '../../models/api-models';

@Component({
  selector: 'app-experiment-detail',
  standalone: false,
  templateUrl: './experiment-detail.component.html',
  styleUrls: ['./experiment-detail.component.css']
})
export class ExperimentDetailComponent implements OnInit {

  experiment: ExperimentWithRuns | null = null;
  registry: ModelRegistry | null = null;
  loading = false;
  experimentId = '';

  // Add run form
  showAddRun = false;
  newModelId = '';
  newModelVariant = '';

  getStatusColor = getExperimentStatusColor;
  getStatusIcon = getExperimentStatusIcon;

  displayedColumns = ['modelId', 'modelVariant', 'status', 'passRate', 'averageScore', 'passedCount', 'failedCount', 'executionTimeMs', 'actions'];

  constructor(
    private route: ActivatedRoute,
    private router: Router,
    private experimentService: ExperimentService,
    private stagingService: StagingService,
    private snackBar: MatSnackBar
  ) {}

  ngOnInit(): void {
    this.experimentId = this.route.snapshot.paramMap.get('id') || '';
    this.loadExperiment();
    this.loadRegistry();
  }

  loadExperiment(): void {
    this.loading = true;
    this.experimentService.getExperiment(this.experimentId).subscribe({
      next: (exp) => {
        this.experiment = exp;
        this.loading = false;
      },
      error: (err) => {
        this.snackBar.open('Failed to load experiment: ' + err.message, 'Close', { duration: 5000 });
        this.loading = false;
      }
    });
  }

  loadRegistry(): void {
    this.stagingService.getRegistry().subscribe({
      next: (reg) => this.registry = reg,
      error: () => {}
    });
  }

  getModelIds(): string[] {
    if (!this.registry?.models) return [];
    return Object.keys(this.registry.models);
  }

  addRun(): void {
    if (!this.newModelId) {
      this.snackBar.open('Model ID is required', 'Close', { duration: 3000 });
      return;
    }

    this.experimentService.addRun(this.experimentId, {
      modelId: this.newModelId,
      modelVariant: this.newModelVariant || undefined
    }).subscribe({
      next: () => {
        this.snackBar.open('Run added', 'Close', { duration: 3000 });
        this.showAddRun = false;
        this.newModelId = '';
        this.newModelVariant = '';
        this.loadExperiment();
      },
      error: (err) => {
        this.snackBar.open('Failed to add run: ' + err.message, 'Close', { duration: 5000 });
      }
    });
  }

  executeRun(run: ExperimentRun): void {
    this.experimentService.executeRun(this.experimentId, run.id).subscribe({
      next: (updatedRun) => {
        this.snackBar.open(`Run ${updatedRun.status.toLowerCase()}`, 'Close', { duration: 3000 });
        this.loadExperiment();
      },
      error: (err) => {
        this.snackBar.open('Execution failed: ' + err.message, 'Close', { duration: 5000 });
      }
    });
  }

  openComparison(): void {
    this.router.navigate(['/experiments', this.experimentId, 'compare']);
  }

  goBack(): void {
    this.router.navigate(['/experiments']);
  }

  formatPercent(value?: number): string {
    if (value == null) return '-';
    return (value * 100).toFixed(1) + '%';
  }

  formatScore(value?: number): string {
    if (value == null) return '-';
    return value.toFixed(3);
  }

  formatDuration(ms?: number): string {
    if (ms == null) return '-';
    if (ms < 1000) return ms + 'ms';
    return (ms / 1000).toFixed(1) + 's';
  }
}
