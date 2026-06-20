/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import { Component } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { MatTabsModule } from '@angular/material/tabs';
import { MatIconModule } from '@angular/material/icon';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatTooltipModule } from '@angular/material/tooltip';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';
import { AttributionService } from '../../services/attribution.service';
import { BayesianService } from '../../services/bayesian.service';
import {
  SensitivityResult,
  BayesianInferenceResult,
  PredictionResult,
  PredictedEvent
} from '../../models/attribution-models';

/**
 * Surfaces three orphaned causal / attribution endpoints that had no UI:
 *   1. Sensitivity analysis — which variables most shift the posterior for a given KG node.
 *   2. MEBN structure — multi-entity Bayesian posteriors / variable metadata for a KG node.
 *   3. Prediction — forward causal inference ("what will happen next?").
 *
 * Styled and structured after ProcessMiningComponent.
 */
@Component({
  standalone: true,
  selector: 'app-causal-attribution-extras',
  imports: [
    CommonModule, FormsModule,
    MatTabsModule, MatIconModule, MatButtonModule, MatFormFieldModule, MatInputModule,
    MatProgressBarModule, MatTooltipModule, MatSnackBarModule
  ],
  template: `
    <div class="extras-container">
      <div class="extras-controls">
        <mat-form-field appearance="outline" class="node-field">
          <mat-label>KG Node ID</mat-label>
          <input matInput [(ngModel)]="nodeId" placeholder="e.g. doc::report/q1.pdf">
          <mat-icon matSuffix>account_tree</mat-icon>
        </mat-form-field>

        <button mat-flat-button color="primary" (click)="runSensitivity()"
                [disabled]="loading || !nodeId" matTooltip="Which variables most shift the posterior?">
          <mat-icon>equalizer</mat-icon> Sensitivity
        </button>

        <button mat-flat-button color="accent" (click)="runMebnStructure()"
                [disabled]="loading || !nodeId" matTooltip="Multi-entity Bayesian structure & posteriors">
          <mat-icon>hub</mat-icon> MEBN structure
        </button>

        <button mat-stroked-button (click)="runPrediction()"
                [disabled]="loading || !nodeId" matTooltip="Forward causal inference: what will happen next?">
          <mat-icon>trending_up</mat-icon> Predict
        </button>
      </div>

      <mat-progress-bar *ngIf="loading" mode="indeterminate"></mat-progress-bar>

      <p class="hint" *ngIf="!sensitivityResult && !mebnResult && !predictionResult && !loading">
        Enter a KG node ID and choose one of the three analyses above.<br>
        <b>Sensitivity</b> shows which connected variables most influence the node's posterior probability.<br>
        <b>MEBN structure</b> runs multi-entity Bayesian inference and returns per-variable metadata.<br>
        <b>Predict</b> walks forward causal edges and returns ranked future events.
      </p>

      <mat-tab-group *ngIf="sensitivityResult || mebnResult || predictionResult"
                     class="extras-tabs" animationDuration="150ms">

        <!-- ── Sensitivity ──────────────────────────────────────────────── -->
        <mat-tab label="Sensitivity" *ngIf="sensitivityResult">
          <div class="panel">
            <div class="summary-row">
              <span class="meta">Baseline posterior: <b>{{ fmt(sensitivityResult.baselinePosterior) }}</b></span>
              <span class="meta">Query prior: <b>{{ fmt(sensitivityResult.queryPrior) }}</b></span>
              <span class="meta">Computed in <b>{{ sensitivityResult.computationTimeMs }} ms</b></span>
            </div>
            <h4>Variable sensitivities</h4>
            <p class="hint" *ngIf="sensitivityEntries.length === 0">No sensitivity data returned.</p>
            <table class="data" *ngIf="sensitivityEntries.length">
              <thead>
                <tr><th>Variable</th><th>Sensitivity shift</th><th>Prior</th></tr>
              </thead>
              <tbody>
                <tr *ngFor="let e of sensitivityEntries">
                  <td>{{ varTitle(e[0]) }}</td>
                  <td>
                    <div class="bar-wrap">
                      <div class="bar">
                        <div class="fill sens" [style.width.%]="barWidth(e[1], maxSens)"></div>
                      </div>
                      <span class="num">{{ fmt(e[1]) }}</span>
                    </div>
                  </td>
                  <td class="num">{{ fmt(sensitivityResult!.priors[e[0]]) }}</td>
                </tr>
              </tbody>
            </table>
          </div>
        </mat-tab>

        <!-- ── MEBN Structure ───────────────────────────────────────────── -->
        <mat-tab label="MEBN Structure" *ngIf="mebnResult">
          <div class="panel">
            <div class="summary-row">
              <span class="meta">Variables: <b>{{ posteriorEntries.length }}</b></span>
              <span class="meta" *ngIf="mebnResult.networkStats?.['networkSize']">
                Network size: <b>{{ mebnResult.networkStats['networkSize'] }}</b>
              </span>
              <span class="meta">Computed in <b>{{ mebnResult.computationTimeMs }} ms</b></span>
            </div>

            <h4>Posteriors</h4>
            <p class="hint" *ngIf="posteriorEntries.length === 0">No posterior data returned.</p>
            <table class="data" *ngIf="posteriorEntries.length">
              <thead>
                <tr><th>Variable</th><th>Node title</th><th>MEBN role</th><th>Posterior</th><th>Prior</th></tr>
              </thead>
              <tbody>
                <tr *ngFor="let e of posteriorEntries">
                  <td>{{ e[0] }}</td>
                  <td>{{ mebnResult!.variableToTitle[e[0]] || '—' }}</td>
                  <td>
                    <span class="tag role" *ngIf="mebnResult!.variableToMebnMeta?.[e[0]] as m">
                      {{ m.nodeRole }}
                    </span>
                    <span *ngIf="!mebnResult!.variableToMebnMeta?.[e[0]]">—</span>
                  </td>
                  <td>
                    <div class="bar-wrap">
                      <div class="bar">
                        <div class="fill mebn" [style.width.%]="e[1] * 100"></div>
                      </div>
                      <span class="num">{{ fmt(e[1]) }}</span>
                    </div>
                  </td>
                  <td class="num">{{ fmt(mebnResult!.priors[e[0]]) }}</td>
                </tr>
              </tbody>
            </table>

            <div *ngIf="mebnResult.inferenceTrace?.length">
              <h4>Inference trace ({{ mebnResult.inferenceTrace.length }} steps)</h4>
              <table class="data trace-table">
                <thead>
                  <tr><th>#</th><th>Eliminated</th><th>Operation</th><th>Factors</th></tr>
                </thead>
                <tbody>
                  <tr *ngFor="let step of mebnResult.inferenceTrace; let i = index">
                    <td class="num">{{ i + 1 }}</td>
                    <td>{{ step.eliminatedTitle || step.eliminatedVariable }}</td>
                    <td>{{ step.operation }}</td>
                    <td class="num">{{ step.factorsInvolved }}</td>
                  </tr>
                </tbody>
              </table>
            </div>
          </div>
        </mat-tab>

        <!-- ── Prediction ───────────────────────────────────────────────── -->
        <mat-tab label="Prediction" *ngIf="predictionResult">
          <div class="panel">
            <div class="summary-row">
              <span class="meta">Source: <b>{{ predictionResult.sourceTitle }}</b></span>
              <span class="meta">Predictions: <b>{{ predictionResult.predictions.length }}</b></span>
              <span class="meta">Computed in <b>{{ predictionResult.computationTimeMs }} ms</b></span>
            </div>

            <p class="forecast" *ngIf="predictionResult.synthesizedForecast">
              {{ predictionResult.synthesizedForecast }}
            </p>

            <p class="hint" *ngIf="predictionResult.predictions.length === 0">No predictions returned.</p>
            <table class="data" *ngIf="predictionResult.predictions.length">
              <thead>
                <tr><th>Predicted event</th><th>Probability</th><th>Hops</th><th>Path</th></tr>
              </thead>
              <tbody>
                <tr *ngFor="let p of predictionResult.predictions">
                  <td>
                    <span class="event-title">{{ p.title }}</span>
                    <span class="hint small" *ngIf="p.explanation">{{ p.explanation }}</span>
                  </td>
                  <td>
                    <div class="bar-wrap">
                      <div class="bar">
                        <div class="fill pred" [style.width.%]="p.probability * 100"></div>
                      </div>
                      <span class="num">{{ pct(p.probability) }}</span>
                    </div>
                  </td>
                  <td class="num">{{ p.hopsFromSource }}</td>
                  <td class="path-cell">{{ p.pathEdgeTypes.join(' → ') }}</td>
                </tr>
              </tbody>
            </table>
          </div>
        </mat-tab>

      </mat-tab-group>
    </div>
  `,
  styles: [`
    .extras-container { padding: 12px; }
    .extras-controls { display: flex; align-items: center; gap: 12px; flex-wrap: wrap; }
    .node-field { min-width: 280px; }

    .hint { color: var(--mat-sys-on-surface-variant, #666); max-width: 720px; }
    .hint.small { font-size: 11px; display: block; color: #888; margin-top: 2px; }
    .extras-tabs { margin-top: 8px; }
    .panel { padding: 12px 4px; }

    .summary-row { display: flex; gap: 24px; flex-wrap: wrap; margin-bottom: 12px; }
    .meta { font-size: 12px; color: var(--mat-sys-on-surface-variant, #777); }
    .meta b { color: inherit; font-weight: 600; }

    table.data { width: 100%; border-collapse: collapse; font-size: 13px; }
    table.data th, table.data td { text-align: left; padding: 6px 10px; border-bottom: 1px solid rgba(0,0,0,0.08); }
    table.data th { font-weight: 600; color: var(--mat-sys-on-surface-variant, #555); }
    td.num { text-align: right; font-variant-numeric: tabular-nums; }
    .trace-table td, .trace-table th { padding: 4px 8px; }

    .bar-wrap { display: flex; align-items: center; gap: 8px; }
    .bar { width: 120px; height: 10px; background: rgba(0,0,0,0.08); border-radius: 5px; overflow: hidden; flex-shrink: 0; }
    .fill { height: 100%; }
    .fill.sens { background: #f57c00; }
    .fill.mebn { background: #6a1b9a; }
    .fill.pred  { background: #1565c0; }

    .tag { padding: 2px 8px; border-radius: 10px; background: rgba(0,0,0,0.06); font-size: 11px; }
    .tag.role { background: rgba(106,27,154,0.14); color: #6a1b9a; }

    .event-title { font-weight: 500; }
    .forecast { font-style: italic; color: var(--mat-sys-on-surface-variant, #555);
                border-left: 3px solid rgba(21,101,192,0.4); padding-left: 10px; margin-bottom: 16px; }
    .path-cell { font-size: 11px; color: #888; max-width: 280px; word-break: break-word; }
  `]
})
export class CausalAttributionExtrasComponent {

  nodeId = '';
  loading = false;

  sensitivityResult?: SensitivityResult;
  mebnResult?: BayesianInferenceResult;
  predictionResult?: PredictionResult;

  // Derived sorted arrays (recomputed after each fetch)
  sensitivityEntries: Array<[string, number]> = [];
  maxSens = 1;
  posteriorEntries: Array<[string, number]> = [];

  constructor(
    private attribution: AttributionService,
    private bayesian: BayesianService,
    private snack: MatSnackBar
  ) {}

  // ── Actions ──────────────────────────────────────────────────────────────

  runSensitivity(): void {
    const id = this.nodeId.trim();
    if (!id) { return; }
    this.loading = true;
    this.sensitivityResult = undefined;
    this.sensitivityEntries = [];

    this.bayesian.quickSensitivity(id).subscribe({
      next: (r) => {
        this.sensitivityResult = r;
        this.sensitivityEntries = Object.entries(r.sensitivities)
          .sort((a, b) => Math.abs(b[1]) - Math.abs(a[1]));
        this.maxSens = this.sensitivityEntries.length
          ? Math.max(...this.sensitivityEntries.map((e) => Math.abs(e[1])))
          : 1;
        this.loading = false;
      },
      error: (e) => this.fail('Sensitivity analysis failed', e)
    });
  }

  runMebnStructure(): void {
    const id = this.nodeId.trim();
    if (!id) { return; }
    this.loading = true;
    this.mebnResult = undefined;
    this.posteriorEntries = [];

    this.bayesian.mebnStructure(id).subscribe({
      next: (r) => {
        this.mebnResult = r;
        this.posteriorEntries = Object.entries(r.posteriors)
          .sort((a, b) => b[1] - a[1]);
        this.loading = false;
      },
      error: (e) => this.fail('MEBN structure query failed', e)
    });
  }

  runPrediction(): void {
    const id = this.nodeId.trim();
    if (!id) { return; }
    this.loading = true;
    this.predictionResult = undefined;

    this.attribution.predictQuick(id).subscribe({
      next: (r) => {
        this.predictionResult = r;
        this.loading = false;
      },
      error: (e) => this.fail('Prediction query failed', e)
    });
  }

  // ── Helpers ───────────────────────────────────────────────────────────────

  /** Resolve a variable ID to its human-readable title (from sensitivity result). */
  varTitle(varId: string): string {
    return varId;
  }

  barWidth(value: number, max: number): number {
    return max > 0 ? (Math.abs(value) / max) * 100 : 0;
  }

  fmt(n?: number): string {
    return (n ?? 0).toFixed(3);
  }

  pct(n?: number): string {
    return Math.round((n ?? 0) * 100) + '%';
  }

  private fail(label: string, err: any): void {
    this.loading = false;
    this.snack.open(label + ': ' + (err?.error?.message || err?.message || 'error'),
      'Dismiss', { duration: 5000 });
  }
}
