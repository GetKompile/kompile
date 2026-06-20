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

import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { MatTabsModule } from '@angular/material/tabs';
import { MatIconModule } from '@angular/material/icon';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatChipsModule } from '@angular/material/chips';
import { MatTooltipModule } from '@angular/material/tooltip';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';
import { MermaidRendererComponent } from './mermaid-renderer.component';
import {
  ProcessMiningService, MiningPreview, ProcessCausalModel,
  DeclareConstraint, InferenceResult, MiningSuggestion, ConformanceResult
} from '../../services/process-mining.service';
import { FactSheetService } from '../../services/fact-sheet.service';

/**
 * Surfaces the LLM-free process-mining engine: derive a sound process from a fact sheet's graph and
 * inspect every artifact — the directly-follows map, the process tree, the χ²-tested causal
 * dependencies, the Declare constraints, and live PSL / Bayesian inference over the discovered structure.
 */
@Component({
  standalone: true,
  selector: 'app-process-mining',
  imports: [
    CommonModule, FormsModule,
    MatTabsModule, MatIconModule, MatButtonModule, MatFormFieldModule, MatInputModule, MatSelectModule,
    MatProgressBarModule, MatChipsModule, MatTooltipModule, MatSnackBarModule,
    MermaidRendererComponent
  ],
  template: `
    <div class="mining-container">
      <div class="mining-controls">
        <mat-form-field appearance="outline" class="fs-field" *ngIf="sheets.length">
          <mat-label>Fact sheet</mat-label>
          <mat-select [(ngModel)]="factSheetId">
            <mat-option *ngFor="let s of sheets" [value]="s.id">{{ s.name || ('#' + s.id) }}</mat-option>
          </mat-select>
        </mat-form-field>
        <mat-form-field appearance="outline" class="fs-field" *ngIf="!sheets.length">
          <mat-label>Fact sheet ID</mat-label>
          <input matInput type="number" [(ngModel)]="factSheetId" min="1">
        </mat-form-field>
        <mat-form-field appearance="outline" class="noise-field"
                        matTooltip="0 = classic Inductive Miner; higher filters infrequent behaviour (IMf)">
          <mat-label>Noise filter</mat-label>
          <input matInput type="number" [(ngModel)]="noise" min="0" max="1" step="0.05">
        </mat-form-field>
        <button mat-flat-button color="primary" (click)="analyze()" [disabled]="loading">
          <mat-icon>insights</mat-icon> Analyze
        </button>
        <button mat-stroked-button (click)="discover()" [disabled]="loading"
                matTooltip="Mine and save as a process suggestion">
          <mat-icon>save</mat-icon> Discover &amp; save
        </button>
        <span class="spacer"></span>
        <mat-chip-set *ngIf="preview">
          <mat-chip>{{ preview.cases }} cases</mat-chip>
          <mat-chip>{{ preview.activities.length }} activities</mat-chip>
          <mat-chip>{{ preview.variants }} variants</mat-chip>
          <mat-chip>{{ preview.directlyFollowsArcs }} edges</mat-chip>
          <mat-chip *ngIf="conformance" class="conf-chip"
                    matTooltip="Fitness = behaviour the model replays; precision = how tightly it fits; simplicity = activities ÷ nodes">
            <mat-icon>verified</mat-icon>
            fitness {{ pct(conformance.fitness) }} · precision {{ pct(conformance.precision) }} · simplicity {{ pct(conformance.simplicity) }}
          </mat-chip>
        </mat-chip-set>
      </div>
      <mat-progress-bar *ngIf="loading" mode="indeterminate"></mat-progress-bar>

      <p class="hint" *ngIf="!preview && !loading">
        Enter a fact sheet ID and press <b>Analyze</b> to derive a business process from its knowledge
        graph — no LLM. The miner produces a sound, block-structured model and couples it into the
        causal, PSL, and Bayesian engines.
      </p>

      <mat-tab-group *ngIf="preview || discovered" class="mining-tabs" animationDuration="150ms">
        <!-- Process map -->
        <mat-tab label="Process map">
          <app-mermaid-renderer [code]="dfgMermaid"></app-mermaid-renderer>
        </mat-tab>

        <!-- Process tree -->
        <mat-tab label="Process tree">
          <app-mermaid-renderer [code]="treeMermaid"></app-mermaid-renderer>
          <pre class="tree-text" *ngIf="preview">{{ preview.processTree }}</pre>
        </mat-tab>

        <!-- Causal -->
        <mat-tab label="Causal">
          <div class="panel" *ngIf="causal">
            <table class="data">
              <thead>
                <tr><th>From</th><th>To</th><th>Type</th><th>Dependency</th><th>χ²</th><th>Sig.</th></tr>
              </thead>
              <tbody>
                <tr *ngFor="let d of causal.dependencies">
                  <td>{{ d.from }}</td>
                  <td>{{ d.to }}</td>
                  <td><span class="tag" [class.causal]="d.significant">{{ d.type }}</span></td>
                  <td>{{ fmt(d.dependency) }}</td>
                  <td>{{ fmt(d.chiSquare) }}</td>
                  <td>{{ d.significant ? '✓' : '' }}</td>
                </tr>
              </tbody>
            </table>
            <h4 *ngIf="causal.pslRules.length">Generated PSL rules</h4>
            <pre class="rules" *ngIf="causal.pslRules.length">{{ causal.pslRules.join('\\n') }}</pre>
          </div>
        </mat-tab>

        <!-- Declare -->
        <mat-tab label="Declare">
          <div class="panel">
            <table class="data">
              <thead><tr><th>Constraint</th><th>A</th><th>B</th><th>Confidence</th><th>Support</th></tr></thead>
              <tbody>
                <tr *ngFor="let c of constraints">
                  <td>{{ c.template }}</td>
                  <td>{{ c.activityA }}</td>
                  <td>{{ c.activityB }}</td>
                  <td>{{ pct(c.confidence) }}</td>
                  <td>{{ pct(c.support) }}</td>
                </tr>
              </tbody>
            </table>
            <p class="hint" *ngIf="!constraints.length">No constraints cleared the thresholds.</p>
          </div>
        </mat-tab>

        <!-- Inference -->
        <mat-tab label="Inference">
          <div class="panel">
            <div class="inference-controls">
              <mat-form-field appearance="outline" class="ev-field">
                <mat-label>Evidence (active activities, comma-separated)</mat-label>
                <input matInput [(ngModel)]="evidence" placeholder="e.g. ORDER">
              </mat-form-field>
              <button mat-flat-button color="primary" (click)="runInference()" [disabled]="loading">
                <mat-icon>bolt</mat-icon> Run inference
              </button>
            </div>
            <div class="inference-grid">
              <div class="inference-col">
                <h4>PSL (HL-MRF) activation</h4>
                <table class="data" *ngIf="psl">
                  <tbody>
                    <tr *ngFor="let e of entries(psl.activation)">
                      <td>{{ e[0] }}</td>
                      <td><div class="bar"><div class="fill psl" [style.width.%]="e[1] * 100"></div></div></td>
                      <td class="num">{{ fmt(e[1]) }}</td>
                    </tr>
                  </tbody>
                </table>
              </div>
              <div class="inference-col">
                <h4>Bayesian posterior P(active)</h4>
                <table class="data" *ngIf="bayesian">
                  <tbody>
                    <tr *ngFor="let e of entries(bayesian.posteriors)">
                      <td>{{ e[0] }}</td>
                      <td><div class="bar"><div class="fill bayes" [style.width.%]="e[1] * 100"></div></div></td>
                      <td class="num">{{ fmt(e[1]) }}</td>
                    </tr>
                  </tbody>
                </table>
              </div>
            </div>
          </div>
        </mat-tab>

        <!-- Discovered process: the phases → steps that become a ProcessDefinition -->
        <mat-tab label="Process">
          <div class="panel">
            <p class="hint" *ngIf="!discovered">
              Press <b>Discover &amp; save</b> to generate the executable process (phases → steps).
            </p>
            <div *ngIf="discovered">
              <h4>{{ discovered.name }}
                <span class="conf-label">confidence {{ pct(discovered.confidence) }}</span></h4>
              <p class="hint">{{ discovered.description }}</p>
              <div class="phase" *ngFor="let phase of discovered.phases; let i = index">
                <div class="phase-head">{{ i + 1 }}. {{ phase.name }}</div>
                <div class="steps">
                  <span class="step" *ngFor="let s of phase.steps">
                    <mat-icon class="step-icon">{{ stepIcon(s.stepType) }}</mat-icon>{{ s.name }}
                    <em class="step-type">{{ s.stepType }}</em>
                  </span>
                </div>
              </div>
            </div>
          </div>
        </mat-tab>
      </mat-tab-group>
    </div>
  `,
  styles: [`
    .mining-container { padding: 12px; }
    .mining-controls { display: flex; align-items: center; gap: 12px; flex-wrap: wrap; }
    .fs-field { width: 130px; } .noise-field { width: 120px; } .ev-field { width: 360px; max-width: 100%; }
    .spacer { flex: 1 1 auto; }
    .hint { color: var(--mat-sys-on-surface-variant, #666); max-width: 720px; }
    .mining-tabs { margin-top: 8px; }
    .panel { padding: 12px 4px; }
    table.data { width: 100%; border-collapse: collapse; font-size: 13px; }
    table.data th, table.data td { text-align: left; padding: 6px 10px; border-bottom: 1px solid rgba(0,0,0,0.08); }
    table.data th { font-weight: 600; color: var(--mat-sys-on-surface-variant, #555); }
    td.num { text-align: right; font-variant-numeric: tabular-nums; }
    .tag { padding: 2px 8px; border-radius: 10px; background: rgba(0,0,0,0.06); font-size: 11px; }
    .tag.causal { background: rgba(46,125,50,0.16); color: #2e7d32; font-weight: 600; }
    .rules, .tree-text { background: rgba(0,0,0,0.04); padding: 10px; border-radius: 6px; overflow-x: auto;
      font-family: monospace; font-size: 12px; white-space: pre; }
    .inference-controls { display: flex; gap: 12px; align-items: center; flex-wrap: wrap; }
    .inference-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 24px; margin-top: 8px; }
    @media (max-width: 800px) { .inference-grid { grid-template-columns: 1fr; } }
    .bar { width: 140px; height: 10px; background: rgba(0,0,0,0.08); border-radius: 5px; overflow: hidden; }
    .fill { height: 100%; } .fill.psl { background: #1565c0; } .fill.bayes { background: #6a1b9a; }
    .conf-label { font-size: 12px; color: #2e7d32; margin-left: 8px; font-weight: 500; }
    .phase { margin: 12px 0; }
    .phase-head { font-weight: 600; margin-bottom: 6px; }
    .steps { display: flex; flex-wrap: wrap; gap: 8px; padding-left: 14px; }
    .step { display: inline-flex; align-items: center; gap: 4px; padding: 4px 10px; border-radius: 14px;
      background: rgba(21,101,192,0.08); font-size: 13px; }
    .step-icon { font-size: 16px; width: 16px; height: 16px; }
    .step-type { color: #888; font-size: 11px; font-style: normal; }
  `]
})
export class ProcessMiningComponent implements OnInit {

  factSheetId = 1;
  noise = 0;
  loading = false;

  preview?: MiningPreview;
  dfgMermaid = '';
  treeMermaid = '';
  causal?: ProcessCausalModel;
  constraints: DeclareConstraint[] = [];
  evidence = '';
  psl?: InferenceResult;
  bayesian?: InferenceResult;
  discovered?: MiningSuggestion;
  conformance?: ConformanceResult;
  sheets: any[] = [];

  constructor(private mining: ProcessMiningService, private snack: MatSnackBar,
              private factSheets: FactSheetService) {}

  ngOnInit(): void {
    this.factSheets.loadSheets().subscribe({
      next: (s) => {
        this.sheets = (s as any[]) || [];
        if (this.sheets.length && !this.factSheetId) {
          this.factSheetId = this.sheets[0].id;
        }
      },
      error: () => { /* leave the manual id input as the fallback */ }
    });
  }

  analyze(): void {
    const id = this.factSheetId;
    this.loading = true;
    this.psl = undefined;
    this.bayesian = undefined;
    this.mining.preview(id, this.noise).subscribe({
      next: (p) => { this.preview = p; this.loading = false; },
      error: (e) => this.fail(e)
    });
    this.mining.mermaid(id, this.noise).subscribe({
      next: (m) => { this.dfgMermaid = m.dfg; this.treeMermaid = m.tree; },
      error: () => {}
    });
    this.mining.causal(id).subscribe({ next: (c) => this.causal = c, error: () => {} });
    this.mining.declareConstraints(id).subscribe({ next: (c) => this.constraints = c, error: () => {} });
    this.mining.conformance(id, this.noise).subscribe({ next: (c) => this.conformance = c, error: () => {} });
  }

  runInference(): void {
    const id = this.factSheetId;
    const ev = this.evidence.split(',').map((s) => s.trim()).filter((s) => s.length > 0);
    this.loading = true;
    this.mining.psl(id, ev).subscribe({ next: (r) => { this.psl = r; this.loading = false; }, error: (e) => this.fail(e) });
    this.mining.bayesian(id, ev).subscribe({ next: (r) => this.bayesian = r, error: () => {} });
  }

  discover(): void {
    this.loading = true;
    this.mining.discover(this.factSheetId, this.noise).subscribe({
      next: (s) => {
        this.loading = false;
        this.discovered = s;
        this.snack.open(s ? `Saved process suggestion "${s.name}" (confidence ${this.pct(s.confidence)})`
                          : 'No process found for this fact sheet', 'OK', { duration: 4000 });
      },
      error: (e) => this.fail(e)
    });
  }

  entries(obj?: Record<string, number>): Array<[string, number]> {
    return obj ? Object.entries(obj).sort((a, b) => b[1] - a[1]) : [];
  }

  fmt(n: number): string {
    return (n ?? 0).toFixed(2);
  }

  pct(n: number): string {
    return Math.round((n ?? 0) * 100) + '%';
  }

  stepIcon(type: string): string {
    switch (type) {
      case 'APPROVE': return 'verified';
      case 'HUMAN': return 'person';
      case 'TOOL_CALL': return 'build';
      case 'HTTP_CALL': return 'http';
      case 'EXCEL_COMPUTE': return 'table_chart';
      default: return 'bolt';
    }
  }

  private fail(err: any): void {
    this.loading = false;
    this.snack.open('Process mining failed: ' + (err?.error?.message || err?.message || 'error'), 'Dismiss',
      { duration: 5000 });
  }
}
