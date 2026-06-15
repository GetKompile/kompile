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
import { ExperimentComparison, RunComparisonEntry } from '../../models/api-models';

@Component({
  selector: 'app-experiment-comparison',
  standalone: false,
  templateUrl: './experiment-comparison.component.html',
  styleUrls: ['./experiment-comparison.component.css']
})
export class ExperimentComparisonComponent implements OnInit {

  comparison: ExperimentComparison | null = null;
  loading = false;
  experimentId = '';

  displayedColumns: string[] = ['metric'];

  constructor(
    private route: ActivatedRoute,
    private router: Router,
    private experimentService: ExperimentService,
    private snackBar: MatSnackBar
  ) {}

  ngOnInit(): void {
    this.experimentId = this.route.snapshot.paramMap.get('id') || '';
    this.loadComparison();
  }

  loadComparison(): void {
    this.loading = true;
    this.experimentService.compareRuns(this.experimentId).subscribe({
      next: (data) => {
        this.comparison = data;
        // Build columns: metric + one per model
        this.displayedColumns = ['metric'];
        if (data.runs) {
          data.runs.forEach(run => {
            const colId = run.modelId + (run.modelVariant ? ' (' + run.modelVariant + ')' : '');
            this.displayedColumns.push(colId);
          });
        }
        this.loading = false;
      },
      error: (err) => {
        this.snackBar.open('Failed to load comparison: ' + err.message, 'Close', { duration: 5000 });
        this.loading = false;
      }
    });
  }

  getMetricRows(): { metric: string; values: { [key: string]: string } }[] {
    if (!this.comparison?.runs) return [];

    const metrics = ['Pass Rate', 'Average Score', 'Passed', 'Failed', 'Total', 'Duration'];
    return metrics.map(metric => {
      const values: { [key: string]: string } = {};
      this.comparison!.runs.forEach(run => {
        const colId = run.modelId + (run.modelVariant ? ' (' + run.modelVariant + ')' : '');
        switch (metric) {
          case 'Pass Rate':
            values[colId] = run.passRate != null ? (run.passRate * 100).toFixed(1) + '%' : '-';
            break;
          case 'Average Score':
            values[colId] = run.averageScore != null ? run.averageScore.toFixed(3) : '-';
            break;
          case 'Passed':
            values[colId] = run.passedCount != null ? run.passedCount.toString() : '-';
            break;
          case 'Failed':
            values[colId] = run.failedCount != null ? run.failedCount.toString() : '-';
            break;
          case 'Total':
            values[colId] = run.totalCount != null ? run.totalCount.toString() : '-';
            break;
          case 'Duration':
            values[colId] = run.executionTimeMs != null ? (run.executionTimeMs / 1000).toFixed(1) + 's' : '-';
            break;
        }
      });
      return { metric, values };
    });
  }

  isBestValue(metric: string, colId: string): boolean {
    if (!this.comparison?.runs) return false;
    const row = this.getMetricRows().find(r => r.metric === metric);
    if (!row) return false;

    // For Pass Rate and Average Score, higher is better. For Duration, lower is better.
    const numericValues: { col: string; val: number }[] = [];
    for (const [col, strVal] of Object.entries(row.values)) {
      const num = parseFloat(strVal);
      if (!isNaN(num)) numericValues.push({ col, val: num });
    }
    if (numericValues.length < 2) return false;

    if (metric === 'Duration') {
      const min = Math.min(...numericValues.map(v => v.val));
      return numericValues.find(v => v.col === colId)?.val === min;
    } else {
      const max = Math.max(...numericValues.map(v => v.val));
      return numericValues.find(v => v.col === colId)?.val === max;
    }
  }

  goBack(): void {
    this.router.navigate(['/experiments', this.experimentId]);
  }
}
