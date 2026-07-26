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
import { AttributionService } from '@shared/services/attribution.service';
import { BayesianService } from '@shared/services/bayesian.service';
import {
  SensitivityResult,
  BayesianInferenceResult,
  PredictionResult,
  PredictedEvent,
  MpeResult,
  WhatIfRequest,
  BayesianQueryRequest
} from '@shared/models/attribution-models';

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

        <button mat-flat-button color="warn" (click)="runWhatIf()"
                [disabled]="loading || !nodeId" matTooltip="Recompute posteriors under hypothetical evidence">
          <mat-icon>science</mat-icon> What-If
        </button>

        <button mat-stroked-button (click)="runMpe()"
                [disabled]="loading || !nodeId" matTooltip="Most probable explanation: most likely state of all variables">
          <mat-icon>psychology</mat-icon> MPE
        </button>
      </div>

      <!-- What-If evidence editor (shown when What-If is active) -->
      <div class="whatif-editor" *ngIf="showWhatIfEditor">
        <mat-form-field appearance="outline" class="evidence-field">
          <mat-label>Hypothetical evidence (nodeId=probability, one per line)</mat-label>
          <textarea matInput [(ngModel)]="evidenceText" rows="4"
                    placeholder="variable_id_1=0.9&#10;variable_id_2=0.1"></textarea>
          <mat-hint>Each line: &lt;variable-id&gt;=&lt;0..1 probability&gt;</mat-hint>
        </mat-form-field>
      </div>

      <mat-progress-bar *ngIf="loading" mode="indeterminate"></mat-progress-bar>

      <p class="hint" *ngIf="!sensitivityResult && !mebnResult && !predictionResult && !whatIfResult && !mpeResult && !loading">
        Enter a KG node ID and choose one of the five analyses above.<br>
        <b>Sensitivity</b> shows which connected variables most influence the node's posterior probability.<br>
        <b>MEBN structure</b> runs multi-entity Bayesian inference and returns per-variable metadata.<br>
        <b>Predict</b> walks forward causal edges and returns ranked future events.<br>
        <b>What-If</b> recomputes posteriors under hypothetical evidence you supply.<br>
        <b>MPE</b> finds the most probable explanation — the most likely assignment of all variables.
      </p>

      <mat-tab-group *ngIf="sensitivityResult || mebnResult || predictionResult || whatIfResult || mpeResult"
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

        <!-- ── What-If ──────────────────────────────────────────────────────── -->
        <mat-tab label="What-If" *ngIf="whatIfResult">
          <div class="panel">
            <div class="summary-row">
              <span class="meta">Seed node: <b>{{ nodeId }}</b></span>
              <span class="meta">Variables: <b>{{ whatIfPosteriorEntries.length }}</b></span>
              <span class="meta">Computed in <b>{{ whatIfResult.computationTimeMs }} ms</b></span>
            </div>

            <div class="evidence-summary" *ngIf="lastEvidenceMap && objectKeys(lastEvidenceMap).length">
              <span class="label">Hypothetical evidence applied:</span>
              <span class="tag ev-tag" *ngFor="let kv of objectEntries(lastEvidenceMap)">
                {{ kv[0] }} = {{ fmt(kv[1]) }}
              </span>
            </div>

            <h4>Posteriors under hypothetical evidence</h4>
            <p class="hint" *ngIf="whatIfPosteriorEntries.length === 0">No posterior data returned.</p>
            <table class="data" *ngIf="whatIfPosteriorEntries.length">
              <thead>
                <tr><th>Variable</th><th>Posterior (what-if)</th><th>Prior (baseline)</th><th>Shift</th></tr>
              </thead>
              <tbody>
                <tr *ngFor="let e of whatIfPosteriorEntries">
                  <td>{{ whatIfResult!.variableToTitle[e[0]] || e[0] }}</td>
                  <td>
                    <div class="bar-wrap">
                      <div class="bar">
                        <div class="fill whatif" [style.width.%]="e[1] * 100"></div>
                      </div>
                      <span class="num">{{ fmt(e[1]) }}</span>
                    </div>
                  </td>
                  <td class="num">{{ fmt(whatIfResult!.priors[e[0]]) }}</td>
                  <td class="num shift"
                      [class.pos]="e[1] - (whatIfResult!.priors[e[0]] ?? 0) > 0"
                      [class.neg]="e[1] - (whatIfResult!.priors[e[0]] ?? 0) < 0">
                    {{ shiftLabel(e[1], whatIfResult!.priors[e[0]]) }}
                  </td>
                </tr>
              </tbody>
            </table>
          </div>
        </mat-tab>

        <!-- ── MPE ─────────────────────────────────────────────────────────── -->
        <mat-tab label="MPE" *ngIf="mpeResult">
          <div class="panel">
            <div class="summary-row">
              <span class="meta">Seed node: <b>{{ nodeId }}</b></span>
              <span class="meta">Variables: <b>{{ mpeAssignmentEntries.length }}</b></span>
              <span class="meta">Computed in <b>{{ mpeResult.computationTimeMs }} ms</b></span>
            </div>

            <h4>Most probable assignments</h4>
            <p class="hint" *ngIf="mpeAssignmentEntries.length === 0">No assignments returned.</p>
            <table class="data" *ngIf="mpeAssignmentEntries.length">
              <thead>
                <tr><th>Variable</th><th>MAP assignment</th><th>Posterior</th><th>Prior</th></tr>
              </thead>
              <tbody>
                <tr *ngFor="let e of mpeAssignmentEntries">
                  <td>{{ mpeResult!.variableToTitle[e[0]] || e[0] }}</td>
                  <td class="num"><span class="tag assign-tag">{{ fmt(e[1]) }}</span></td>
                  <td>
                    <div class="bar-wrap">
                      <div class="bar">
                        <div class="fill mpe" [style.width.%]="(mpeResult!.posteriors[e[0]] ?? 0) * 100"></div>
                      </div>
                      <span class="num">{{ fmt(mpeResult!.posteriors[e[0]]) }}</span>
                    </div>
                  </td>
                  <td class="num">{{ fmt(mpeResult!.priors[e[0]]) }}</td>
                </tr>
              </tbody>
            </table>

            <div *ngIf="mpeResult.inferenceTrace?.length">
              <h4>Inference trace ({{ mpeResult.inferenceTrace.length }} steps)</h4>
              <table class="data trace-table">
                <thead>
                  <tr><th>#</th><th>Eliminated</th><th>Operation</th><th>Factors</th></tr>
                </thead>
                <tbody>
                  <tr *ngFor="let step of mpeResult.inferenceTrace; let i = index">
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

    /* What-If editor */
    .whatif-editor { margin: 8px 0 4px; }
    .evidence-field { width: 100%; max-width: 640px; }

    /* Evidence summary chips */
    .evidence-summary { display: flex; align-items: center; flex-wrap: wrap; gap: 6px;
                        margin-bottom: 12px; font-size: 12px; }
    .evidence-summary .label { color: var(--mat-sys-on-surface-variant, #777); margin-right: 4px; }
    .tag.ev-tag { background: rgba(211,47,47,0.12); color: #c62828; }

    /* Bar colours for new tabs */
    .fill.whatif { background: #00796b; }
    .fill.mpe    { background: #4527a0; }

    /* Shift column colouring */
    td.shift { font-weight: 600; }
    td.shift.pos { color: #2e7d32; }
    td.shift.neg { color: #c62828; }

    /* MPE assignment tag */
    .tag.assign-tag { background: rgba(69,39,160,0.12); color: #4527a0; font-variant-numeric: tabular-nums; }
  `]
})
export class CausalAttributionExtrasComponent {

  nodeId = '';
  loading = false;

  sensitivityResult?: SensitivityResult;
  mebnResult?: BayesianInferenceResult;
  predictionResult?: PredictionResult;
  whatIfResult?: BayesianInferenceResult;
  mpeResult?: MpeResult;

  // Derived sorted arrays (recomputed after each fetch)
  sensitivityEntries: Array<[string, number]> = [];
  maxSens = 1;
  posteriorEntries: Array<[string, number]> = [];
  whatIfPosteriorEntries: Array<[string, number]> = [];
  mpeAssignmentEntries: Array<[string, number]> = [];

  // What-If editor state
  showWhatIfEditor = false;
  evidenceText = '';
  lastEvidenceMap: Record<string, number> = {};

  // Object helpers exposed to template
  readonly objectKeys = Object.keys;
  readonly objectEntries = (o: Record<string, number>): [string, number][] => Object.entries(o) as [string, number][];

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

  runWhatIf(): void {
    const id = this.nodeId.trim();
    if (!id) { return; }

    // Toggle editor visibility; first click shows editor so user can enter evidence.
    // Subsequent clicks (or if editor already shown) proceed with the query.
    if (!this.showWhatIfEditor) {
      this.showWhatIfEditor = true;
      return;
    }

    const evidenceMap = this.parseEvidenceText(this.evidenceText);
    this.lastEvidenceMap = evidenceMap;

    const request: WhatIfRequest = {
      seedNodeIds: [id],
      hypotheticalEvidence: evidenceMap
    };

    this.loading = true;
    this.whatIfResult = undefined;
    this.whatIfPosteriorEntries = [];

    this.bayesian.whatIfQuery(request).subscribe({
      next: (r) => {
        this.whatIfResult = r;
        this.whatIfPosteriorEntries = Object.entries(r.posteriors)
          .sort((a, b) => b[1] - a[1]);
        this.loading = false;
      },
      error: (e) => this.fail('What-If query failed', e)
    });
  }

  runMpe(): void {
    const id = this.nodeId.trim();
    if (!id) { return; }
    this.loading = true;
    this.mpeResult = undefined;
    this.mpeAssignmentEntries = [];

    const request: BayesianQueryRequest = {
      seedNodeIds: [id]
    };

    this.bayesian.mostProbableExplanation(request).subscribe({
      next: (r) => {
        this.mpeResult = r;
        // Sort assignments by descending MAP value
        this.mpeAssignmentEntries = Object.entries(r.assignments)
          .sort((a, b) => b[1] - a[1]);
        this.loading = false;
      },
      error: (e) => this.fail('MPE query failed', e)
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

  shiftLabel(posterior: number, prior: number | undefined): string {
    const delta = posterior - (prior ?? 0);
    const sign = delta > 0 ? '+' : '';
    return sign + delta.toFixed(3);
  }

  /** Parse "varId=0.9\nvarId2=0.1" → { varId: 0.9, varId2: 0.1 } */
  private parseEvidenceText(text: string): Record<string, number> {
    const result: Record<string, number> = {};
    if (!text) { return result; }
    for (const line of text.split('\n')) {
      const trimmed = line.trim();
      if (!trimmed || !trimmed.includes('=')) { continue; }
      const eqIdx = trimmed.indexOf('=');
      const key = trimmed.substring(0, eqIdx).trim();
      const val = parseFloat(trimmed.substring(eqIdx + 1).trim());
      if (key && !isNaN(val)) {
        result[key] = Math.min(1, Math.max(0, val));
      }
    }
    return result;
  }

  private fail(label: string, err: any): void {
    this.loading = false;
    this.snack.open(label + ': ' + (err?.error?.message || err?.message || 'error'),
      'Dismiss', { duration: 5000 });
  }
}
