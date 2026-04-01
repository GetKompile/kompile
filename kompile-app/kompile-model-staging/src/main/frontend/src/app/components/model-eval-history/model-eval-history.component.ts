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
import { ActivatedRoute } from '@angular/router';
import { MatSnackBar } from '@angular/material/snack-bar';
import { ExperimentService } from '../../services/experiment.service';
import { ExperimentRun, getExperimentStatusColor, getExperimentStatusIcon } from '../../models/api-models';

@Component({
  selector: 'app-model-eval-history',
  standalone: false,
  templateUrl: './model-eval-history.component.html',
  styleUrls: ['./model-eval-history.component.css']
})
export class ModelEvalHistoryComponent implements OnInit {

  modelId = '';
  runs: ExperimentRun[] = [];
  loading = false;

  displayedColumns = ['experimentId', 'modelVariant', 'status', 'passRate', 'averageScore', 'completedAt'];

  getStatusColor = getExperimentStatusColor;
  getStatusIcon = getExperimentStatusIcon;

  constructor(
    private route: ActivatedRoute,
    private experimentService: ExperimentService,
    private snackBar: MatSnackBar
  ) {}

  ngOnInit(): void {
    this.modelId = this.route.snapshot.paramMap.get('modelId') || '';
    this.loadHistory();
  }

  loadHistory(): void {
    this.loading = true;
    this.experimentService.getModelHistory(this.modelId).subscribe({
      next: (runs) => {
        this.runs = runs;
        this.loading = false;
      },
      error: (err) => {
        this.snackBar.open('Failed to load history: ' + err.message, 'Close', { duration: 5000 });
        this.loading = false;
      }
    });
  }

  formatPercent(value?: number): string {
    if (value == null) return '-';
    return (value * 100).toFixed(1) + '%';
  }

  formatScore(value?: number): string {
    if (value == null) return '-';
    return value.toFixed(3);
  }
}
