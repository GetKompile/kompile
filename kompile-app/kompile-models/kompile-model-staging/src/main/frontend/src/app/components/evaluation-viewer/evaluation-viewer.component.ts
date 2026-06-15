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
import { MatSnackBar } from '@angular/material/snack-bar';
import { EvaluationService } from '../../services/evaluation.service';
import { DatasetService } from '../../services/dataset.service';
import { StagingService } from '../../services/staging.service';
import { ModelEntry, DatasetInfo, EvaluationResult } from '../../models/api-models';

@Component({
  selector: 'app-evaluation-viewer',
  standalone: false,
  templateUrl: './evaluation-viewer.component.html',
  styleUrls: ['./evaluation-viewer.component.css']
})
export class EvaluationViewerComponent implements OnInit {
  models: ModelEntry[] = [];
  datasets: DatasetInfo[] = [];
  availableMetrics: any[] = [];
  results: EvaluationResult[] = [];
  running = false;

  selectedModelId = '';
  selectedDatasetId = '';
  selectedMetrics: string[] = ['perplexity', 'accuracy'];
  batchSize = 8;
  maxSamples = -1;

  displayedColumns: string[] = [];

  constructor(
    private evaluationService: EvaluationService,
    private datasetService: DatasetService,
    private stagingService: StagingService,
    private snackBar: MatSnackBar
  ) {}

  ngOnInit(): void {
    this.stagingService.getRegistry().subscribe({
      next: (reg) => this.models = Object.values(reg.models || {}),
      error: () => {}
    });
    this.datasetService.listDatasets().subscribe({
      next: (ds) => this.datasets = ds,
      error: () => {}
    });
    this.evaluationService.getAvailableMetrics().subscribe({
      next: (m) => this.availableMetrics = m,
      error: () => {}
    });
  }

  toggleMetric(metricId: string): void {
    const idx = this.selectedMetrics.indexOf(metricId);
    if (idx >= 0) {
      this.selectedMetrics.splice(idx, 1);
    } else {
      this.selectedMetrics.push(metricId);
    }
  }

  runEvaluation(): void {
    if (!this.selectedModelId || !this.selectedDatasetId || this.selectedMetrics.length === 0) {
      this.snackBar.open('Please select model, dataset, and at least one metric', 'Close', { duration: 3000 });
      return;
    }
    this.running = true;
    this.evaluationService.runEvaluation({
      modelId: this.selectedModelId,
      datasetId: this.selectedDatasetId,
      metrics: this.selectedMetrics,
      batchSize: this.batchSize,
      maxSamples: this.maxSamples
    }).subscribe({
      next: (result) => {
        this.results.unshift(result);
        this.updateDisplayedColumns();
        this.running = false;
        this.snackBar.open('Evaluation complete', 'Close', { duration: 3000 });
      },
      error: (err) => {
        this.snackBar.open('Evaluation failed: ' + err.message, 'Close', { duration: 5000 });
        this.running = false;
      }
    });
  }

  updateDisplayedColumns(): void {
    const metricKeys = new Set<string>();
    this.results.forEach(r => Object.keys(r.metrics || {}).forEach(k => metricKeys.add(k)));
    this.displayedColumns = ['modelId', 'datasetId', ...Array.from(metricKeys), 'evaluationTimeMs'];
  }

  getMetricValue(result: EvaluationResult, key: string): string {
    const val = result.metrics?.[key];
    return val !== undefined ? val.toFixed(4) : '-';
  }
}
