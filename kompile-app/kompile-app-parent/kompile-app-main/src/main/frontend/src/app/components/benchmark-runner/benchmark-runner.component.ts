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
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { MatCardModule } from '@angular/material/card';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatTooltipModule } from '@angular/material/tooltip';
import { MatExpansionModule } from '@angular/material/expansion';
import { MatDividerModule } from '@angular/material/divider';
import { MatChipsModule } from '@angular/material/chips';
import { MatInputModule } from '@angular/material/input';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';
import { MatSlideToggleModule } from '@angular/material/slide-toggle';
import { MatTableModule } from '@angular/material/table';
import { MatTabsModule } from '@angular/material/tabs';
import { MatSelectModule } from '@angular/material/select';
import { Subject } from 'rxjs';
import { takeUntil } from 'rxjs/operators';

import {
  BenchmarkService,
  SamediffBenchmarkConfig,
  SamediffBenchmarkResult
} from '../../services/benchmark.service';

@Component({
  selector: 'app-benchmark-runner',
  standalone: true,
  imports: [
    CommonModule,
    FormsModule,
    MatCardModule,
    MatButtonModule,
    MatIconModule,
    MatProgressSpinnerModule,
    MatProgressBarModule,
    MatTooltipModule,
    MatExpansionModule,
    MatDividerModule,
    MatChipsModule,
    MatInputModule,
    MatFormFieldModule,
    MatSnackBarModule,
    MatSlideToggleModule,
    MatTableModule,
    MatTabsModule,
    MatSelectModule
  ],
  templateUrl: './benchmark-runner.component.html',
  styleUrls: ['./benchmark-runner.component.css']
})
export class BenchmarkRunnerComponent implements OnInit, OnDestroy {
  // Configs tab
  configs: SamediffBenchmarkConfig[] = [];
  activeConfigName: string | null = null;
  loading = false;
  error: string | null = null;

  // Edit form
  editConfig: SamediffBenchmarkConfig = this.newConfig();
  isEditing = false;

  // Run tab
  selectedConfigForRun: string = '';
  runningBenchmark = false;
  lastRunResult: SamediffBenchmarkResult | null = null;

  // Profile search tab
  searchWarps: string = '4,8,16';
  searchStages: string = '2,3,4';
  searchFpFusion: boolean = true;
  searchRunning = false;
  searchProgress = 0;
  searchBestResult: SamediffBenchmarkResult | null = null;

  // Results tab
  results: SamediffBenchmarkResult[] = [];
  resultsColumns: string[] = ['configName', 'passed', 'tokPerSec', 'decodeTokPerSec', 'firstTokenMs', 'totalMs', 'timestamp'];

  private destroy$ = new Subject<void>();

  constructor(
    private benchmarkService: BenchmarkService,
    private snackBar: MatSnackBar
  ) {}

  ngOnInit(): void {
    this.loadConfigs();
    this.loadResults();
  }

  ngOnDestroy(): void {
    this.destroy$.next();
    this.destroy$.complete();
  }

  // === Configs Tab ===

  loadConfigs(): void {
    this.loading = true;
    this.benchmarkService.listConfigs()
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: (configs) => {
          this.configs = configs;
          const active = configs.find(c => c.isActive);
          this.activeConfigName = active?.name || null;
          this.loading = false;
        },
        error: (err) => {
          this.error = err?.message || 'Failed to load configs';
          this.loading = false;
        }
      });
  }

  newConfig(): SamediffBenchmarkConfig {
    return {
      name: '',
      tritonBuildThreads: 4,
      tritonCacheEnabled: true,
      tritonVerbose: false,
      tritonAlwaysCompile: false,
      tritonNumWarps: 8,
      tritonNumStages: 3,
      tritonNumCTAs: 1,
      tritonEnableFpFusion: true,
      cudaTensorCoreEnabled: true,
      cudaGraphOptimization: true,
      maxTokens: 100,
      captureMinExec: 3
    };
  }

  startNewConfig(): void {
    this.editConfig = this.newConfig();
    this.isEditing = true;
  }

  editExistingConfig(config: SamediffBenchmarkConfig): void {
    this.editConfig = { ...config };
    this.isEditing = true;
  }

  saveConfig(): void {
    if (!this.editConfig.name) {
      this.snackBar.open('Config name is required', 'Close', { duration: 3000 });
      return;
    }
    this.loading = true;
    this.benchmarkService.saveConfig(this.editConfig)
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: () => {
          this.snackBar.open('Config saved', 'Close', { duration: 3000 });
          this.isEditing = false;
          this.loadConfigs();
        },
        error: (err) => {
          this.snackBar.open(`Failed to save: ${err.message}`, 'Close', { duration: 5000 });
          this.loading = false;
        }
      });
  }

  cancelEdit(): void {
    this.isEditing = false;
  }

  deleteConfig(name: string): void {
    this.benchmarkService.deleteConfig(name)
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: () => {
          this.snackBar.open(`Config '${name}' deleted`, 'Close', { duration: 3000 });
          this.loadConfigs();
        },
        error: (err) => {
          this.snackBar.open(`Failed to delete: ${err.message}`, 'Close', { duration: 5000 });
        }
      });
  }

  activateConfig(name: string): void {
    this.loading = true;
    this.benchmarkService.activateConfig(name)
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: () => {
          this.snackBar.open(`Config '${name}' activated`, 'Close', { duration: 3000 });
          this.loadConfigs();
        },
        error: (err) => {
          this.snackBar.open(`Failed to activate: ${err.message}`, 'Close', { duration: 5000 });
          this.loading = false;
        }
      });
  }

  // === Run Tab ===

  runBenchmark(): void {
    if (!this.selectedConfigForRun) {
      this.snackBar.open('Select a config to run', 'Close', { duration: 3000 });
      return;
    }
    this.runningBenchmark = true;
    this.lastRunResult = null;
    this.benchmarkService.runBenchmark(this.selectedConfigForRun)
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: (result) => {
          this.lastRunResult = result;
          this.runningBenchmark = false;
          this.loadResults();
          if (result.passed) {
            this.snackBar.open(`Benchmark passed: ${result.decodeTokPerSec.toFixed(1)} tok/s`, 'Close', { duration: 5000 });
          } else {
            this.snackBar.open(`Benchmark failed: ${result.failureMessage}`, 'Close', { duration: 5000 });
          }
        },
        error: (err) => {
          this.runningBenchmark = false;
          this.snackBar.open(`Benchmark error: ${err.message}`, 'Close', { duration: 5000 });
        }
      });
  }

  // === Profile Search Tab ===

  startSearch(): void {
    const warps = this.searchWarps.split(',').map(s => parseInt(s.trim())).filter(n => !isNaN(n));
    const stages = this.searchStages.split(',').map(s => parseInt(s.trim())).filter(n => !isNaN(n));
    const fpFusion = this.searchFpFusion ? [true, false] : [true];

    if (warps.length === 0 || stages.length === 0) {
      this.snackBar.open('Enter valid warps and stages ranges', 'Close', { duration: 3000 });
      return;
    }

    this.searchRunning = true;
    this.searchBestResult = null;
    this.searchProgress = 0;

    this.benchmarkService.searchOptimalProfile({ warpsRange: warps, stagesRange: stages, fpFusionRange: fpFusion })
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: (response) => {
          this.searchRunning = false;
          this.searchProgress = 100;
          if (response.bestResult) {
            this.searchBestResult = response.bestResult;
            this.snackBar.open(`Best: ${response.bestConfig} at ${response.bestResult.decodeTokPerSec.toFixed(1)} tok/s`, 'Close', { duration: 5000 });
          } else {
            this.snackBar.open('No successful benchmark found', 'Close', { duration: 5000 });
          }
          this.loadConfigs();
          this.loadResults();
        },
        error: (err) => {
          this.searchRunning = false;
          this.snackBar.open(`Search failed: ${err.message}`, 'Close', { duration: 5000 });
        }
      });
  }

  applyOptimal(): void {
    this.loading = true;
    this.benchmarkService.applyOptimalDefaults()
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: () => {
          this.snackBar.open('Optimal defaults applied', 'Close', { duration: 3000 });
          this.loadConfigs();
        },
        error: (err) => {
          this.snackBar.open(`Failed: ${err.message}`, 'Close', { duration: 5000 });
          this.loading = false;
        }
      });
  }

  // === Results Tab ===

  loadResults(): void {
    this.benchmarkService.getResults()
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: (results) => {
          this.results = results;
        },
        error: (err) => {
          console.error('Failed to load results:', err);
        }
      });
  }

  clearResults(): void {
    this.benchmarkService.clearResults()
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: () => {
          this.results = [];
          this.snackBar.open('Results cleared', 'Close', { duration: 3000 });
        }
      });
  }

  formatTimestamp(ts: string): string {
    if (!ts) return '';
    try {
      return new Date(ts).toLocaleString();
    } catch {
      return ts;
    }
  }
}
