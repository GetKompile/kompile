/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import { CommonModule } from '@angular/common';
import { Component, Input } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatButtonToggleModule } from '@angular/material/button-toggle';
import { MatChipsModule } from '@angular/material/chips';
import { MatDividerModule } from '@angular/material/divider';
import { MatExpansionModule } from '@angular/material/expansion';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatSelectModule } from '@angular/material/select';
import { MatSlideToggleModule } from '@angular/material/slide-toggle';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';
import { MatTooltipModule } from '@angular/material/tooltip';
import { finalize } from 'rxjs/operators';

import {
  GraphMetadataPatchRequest,
  GraphMetadataPatchResult,
  GraphMetadataPatchRule,
  GraphMetadataPatchSample,
  NodeLevel
} from '../../models/graph-models';
import { GraphService } from '../../services/graph.service';

type PatchMode = 'builder' | 'json';

interface PatchRuleDraft {
  name: string;
  nodeType: NodeLevel;
  entityType: string;
  titleEquals: string;
  titleRegex: string;
  metadataEquals: string;
  metadataExists: string;
  setMetadata: string;
  removeMetadataKeys: string;
}

@Component({
  selector: 'app-graph-data-patch',
  standalone: true,
  imports: [
    CommonModule,
    FormsModule,
    MatButtonModule,
    MatButtonToggleModule,
    MatChipsModule,
    MatDividerModule,
    MatExpansionModule,
    MatFormFieldModule,
    MatIconModule,
    MatInputModule,
    MatProgressSpinnerModule,
    MatSelectModule,
    MatSlideToggleModule,
    MatSnackBarModule,
    MatTooltipModule
  ],
  template: `
    <section class="patch-shell">
      <div class="patch-header">
        <div class="patch-title">
          <mat-icon>edit_note</mat-icon>
          <div>
            <h2>Data Patch</h2>
            <span>{{ scopeLabel }}</span>
          </div>
        </div>
        <div class="patch-header-actions">
          <mat-slide-toggle
            [(ngModel)]="useGlobal"
            matTooltip="Patch nodes outside the active fact sheet scope">
            Global
          </mat-slide-toggle>
          <mat-button-toggle-group [(ngModel)]="mode" aria-label="Patch mode">
            <mat-button-toggle value="builder">
              <mat-icon>tune</mat-icon>
            </mat-button-toggle>
            <mat-button-toggle value="json">
              <mat-icon>data_object</mat-icon>
            </mat-button-toggle>
          </mat-button-toggle-group>
        </div>
      </div>

      <div class="scope-warning" *ngIf="!useGlobal && !factSheetId">
        <mat-icon>lock</mat-icon>
        <span>Choose an active fact sheet or enable Global.</span>
      </div>

      <div class="patch-layout">
        <div class="patch-section rule-section" *ngIf="mode === 'builder'">
          <div class="section-heading">
            <h3>Match</h3>
            <button mat-stroked-button type="button" (click)="resetBuilder()" matTooltip="Reset fields">
              <mat-icon>restart_alt</mat-icon>
              Reset
            </button>
          </div>

          <div class="form-grid">
            <mat-form-field appearance="outline">
              <mat-label>Rule name</mat-label>
              <input matInput [(ngModel)]="ruleDraft.name">
            </mat-form-field>

            <mat-form-field appearance="outline">
              <mat-label>Node type</mat-label>
              <mat-select [(ngModel)]="ruleDraft.nodeType">
                <mat-option *ngFor="let nodeType of nodeTypes" [value]="nodeType">
                  {{ nodeType }}
                </mat-option>
              </mat-select>
            </mat-form-field>

            <mat-form-field appearance="outline">
              <mat-label>Entity type</mat-label>
              <input matInput [(ngModel)]="ruleDraft.entityType">
            </mat-form-field>

            <mat-form-field appearance="outline">
              <mat-label>Title regex</mat-label>
              <input matInput [(ngModel)]="ruleDraft.titleRegex">
            </mat-form-field>
          </div>

          <div class="form-grid two-column">
            <mat-form-field appearance="outline">
              <mat-label>Title equals</mat-label>
              <textarea matInput rows="4" [(ngModel)]="ruleDraft.titleEquals"></textarea>
            </mat-form-field>

            <mat-form-field appearance="outline">
              <mat-label>Metadata exists</mat-label>
              <textarea matInput rows="4" [(ngModel)]="ruleDraft.metadataExists"></textarea>
            </mat-form-field>
          </div>

          <mat-form-field appearance="outline" class="full-width">
            <mat-label>Metadata equals JSON</mat-label>
            <textarea matInput rows="4" class="code-input" [(ngModel)]="ruleDraft.metadataEquals"></textarea>
          </mat-form-field>

          <mat-divider></mat-divider>

          <div class="section-heading compact">
            <h3>Update</h3>
            <button mat-stroked-button type="button" (click)="syncRawRulesFromBuilder()" matTooltip="Copy builder rule to JSON mode">
              <mat-icon>sync_alt</mat-icon>
              JSON
            </button>
          </div>

          <div class="form-grid two-column">
            <mat-form-field appearance="outline">
              <mat-label>Set metadata JSON</mat-label>
              <textarea matInput rows="7" class="code-input" [(ngModel)]="ruleDraft.setMetadata"></textarea>
            </mat-form-field>

            <mat-form-field appearance="outline">
              <mat-label>Remove keys</mat-label>
              <textarea matInput rows="7" [(ngModel)]="ruleDraft.removeMetadataKeys"></textarea>
            </mat-form-field>
          </div>
        </div>

        <div class="patch-section rule-section" *ngIf="mode === 'json'">
          <div class="section-heading">
            <h3>Rules JSON</h3>
            <button mat-stroked-button type="button" (click)="formatRulesJson()" matTooltip="Format JSON">
              <mat-icon>format_align_left</mat-icon>
              Format
            </button>
          </div>
          <mat-form-field appearance="outline" class="full-width raw-rules">
            <mat-label>Rules</mat-label>
            <textarea matInput rows="22" class="code-input" [(ngModel)]="rawRulesJson"></textarea>
          </mat-form-field>
        </div>

        <aside class="patch-section run-section">
          <div class="section-heading">
            <h3>Run</h3>
            <mat-progress-spinner *ngIf="loading" mode="indeterminate" diameter="22"></mat-progress-spinner>
          </div>

          <mat-form-field appearance="outline" class="full-width">
            <mat-label>Limit</mat-label>
            <input matInput type="number" min="1" [(ngModel)]="limit">
          </mat-form-field>

          <div class="button-row">
            <button mat-raised-button color="primary" type="button" (click)="dryRunPatch()" [disabled]="loading">
              <mat-icon>preview</mat-icon>
              Dry Run
            </button>
            <button mat-raised-button color="warn" type="button" (click)="applyPatch()" [disabled]="!canApply()">
              <mat-icon>task_alt</mat-icon>
              Apply
            </button>
          </div>

          <div class="error-box" *ngIf="lastError">
            <mat-icon>error</mat-icon>
            <span>{{ lastError }}</span>
          </div>

          <div class="result-summary" *ngIf="lastResult">
            <mat-chip-set>
              <mat-chip>{{ lastResult.dryRun ? 'Dry run' : 'Applied' }}</mat-chip>
              <mat-chip>{{ lastResult.allowGlobal ? 'Global' : 'Scoped' }}</mat-chip>
            </mat-chip-set>

            <div class="metrics-grid">
              <div class="metric">
                <span>Scanned</span>
                <strong>{{ lastResult.scannedCount }}</strong>
              </div>
              <div class="metric">
                <span>Matched</span>
                <strong>{{ lastResult.matchedCount }}</strong>
              </div>
              <div class="metric changed">
                <span>Changed</span>
                <strong>{{ lastResult.changedCount }}</strong>
              </div>
              <div class="metric">
                <span>Updated</span>
                <strong>{{ lastResult.updatedCount }}</strong>
              </div>
              <div class="metric">
                <span>Unchanged</span>
                <strong>{{ lastResult.unchangedCount }}</strong>
              </div>
              <div class="metric">
                <span>Limited</span>
                <strong>{{ lastResult.skippedByLimitCount }}</strong>
              </div>
            </div>
          </div>

          <mat-expansion-panel class="request-preview">
            <mat-expansion-panel-header>
              <mat-panel-title>Request</mat-panel-title>
            </mat-expansion-panel-header>
            <pre>{{ requestPreview }}</pre>
          </mat-expansion-panel>
        </aside>
      </div>

      <div class="samples-section" *ngIf="lastResult?.samples?.length">
        <div class="section-heading">
          <h3>Samples</h3>
          <span>{{ lastResult?.samples?.length || 0 }} shown</span>
        </div>
        <mat-expansion-panel *ngFor="let sample of lastResult?.samples; trackBy: trackSample">
          <mat-expansion-panel-header>
            <mat-panel-title>{{ sample.title || sample.nodeId }}</mat-panel-title>
            <mat-panel-description>{{ sample.matchedRules.join(', ') }}</mat-panel-description>
          </mat-expansion-panel-header>
          <div class="sample-grid">
            <div>
              <h4>Before</h4>
              <pre>{{ formatJson(sample.beforeMetadata) }}</pre>
            </div>
            <div>
              <h4>After</h4>
              <pre>{{ formatJson(sample.afterMetadata) }}</pre>
            </div>
          </div>
        </mat-expansion-panel>
      </div>
    </section>
  `,
  styles: [`
    .patch-shell {
      min-height: 100%;
      background: #f6f8fb;
      color: #20242a;
      padding: 20px;
    }

    .patch-header,
    .patch-section,
    .samples-section {
      background: #ffffff;
      border: 1px solid #d9e1ea;
      border-radius: 8px;
    }

    .patch-header {
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: 16px;
      padding: 16px 18px;
      margin-bottom: 16px;
    }

    .patch-title {
      display: flex;
      align-items: center;
      gap: 12px;
      min-width: 0;
    }

    .patch-title mat-icon {
      color: #246b64;
    }

    .patch-title h2,
    .section-heading h3 {
      margin: 0;
      font-weight: 600;
    }

    .patch-title h2 {
      font-size: 20px;
    }

    .patch-title span {
      display: block;
      color: #5f6f7f;
      font-size: 13px;
      margin-top: 2px;
    }

    .patch-header-actions {
      display: flex;
      align-items: center;
      gap: 12px;
      flex-wrap: wrap;
    }

    .patch-header-actions mat-button-toggle mat-icon {
      margin: 0;
    }

    .scope-warning,
    .error-box {
      display: flex;
      align-items: center;
      gap: 8px;
      border-radius: 8px;
      padding: 10px 12px;
      margin-bottom: 16px;
      font-size: 13px;
    }

    .scope-warning {
      background: #fff8e1;
      color: #6d5500;
      border: 1px solid #f1d68b;
    }

    .error-box {
      background: #fff1f1;
      color: #8a1f1f;
      border: 1px solid #efb7b7;
      margin: 14px 0 0;
      align-items: flex-start;
    }

    .patch-layout {
      display: grid;
      grid-template-columns: minmax(0, 1fr) minmax(280px, 360px);
      gap: 16px;
      align-items: start;
    }

    .patch-section {
      padding: 16px;
    }

    .section-heading {
      display: flex;
      justify-content: space-between;
      align-items: center;
      gap: 12px;
      margin-bottom: 14px;
    }

    .section-heading h3 {
      font-size: 15px;
      color: #2d3845;
    }

    .section-heading > span {
      color: #637384;
      font-size: 13px;
    }

    .form-grid {
      display: grid;
      grid-template-columns: repeat(2, minmax(0, 1fr));
      gap: 12px;
    }

    .full-width {
      width: 100%;
    }

    .code-input,
    pre {
      font-family: "SFMono-Regular", Consolas, "Liberation Mono", monospace;
      font-size: 12px;
      line-height: 1.45;
    }

    .button-row {
      display: grid;
      grid-template-columns: 1fr 1fr;
      gap: 10px;
    }

    .result-summary {
      margin-top: 16px;
    }

    .metrics-grid {
      display: grid;
      grid-template-columns: repeat(2, minmax(0, 1fr));
      gap: 10px;
      margin-top: 12px;
    }

    .metric {
      border: 1px solid #e1e7ee;
      background: #f9fbfd;
      border-radius: 6px;
      padding: 10px;
    }

    .metric span {
      display: block;
      color: #657486;
      font-size: 12px;
      margin-bottom: 4px;
    }

    .metric strong {
      font-size: 20px;
      font-weight: 650;
      color: #26313d;
    }

    .metric.changed strong {
      color: #246b64;
    }

    .request-preview {
      margin-top: 16px;
      box-shadow: none;
      border: 1px solid #e1e7ee;
    }

    .request-preview pre,
    .sample-grid pre {
      overflow: auto;
      margin: 0;
      padding: 12px;
      border-radius: 6px;
      background: #111827;
      color: #edf2f7;
      max-height: 360px;
      white-space: pre-wrap;
      word-break: break-word;
    }

    .samples-section {
      margin-top: 16px;
      padding: 16px;
    }

    .samples-section mat-expansion-panel {
      box-shadow: none;
      border: 1px solid #e1e7ee;
      margin-bottom: 10px;
    }

    .sample-grid {
      display: grid;
      grid-template-columns: repeat(2, minmax(0, 1fr));
      gap: 14px;
    }

    .sample-grid h4 {
      margin: 0 0 8px;
      font-size: 13px;
      color: #425160;
    }

    mat-form-field {
      min-width: 0;
    }

    @media (max-width: 980px) {
      .patch-layout,
      .sample-grid {
        grid-template-columns: 1fr;
      }
    }

    @media (max-width: 720px) {
      .patch-shell {
        padding: 12px;
      }

      .patch-header {
        align-items: stretch;
        flex-direction: column;
      }

      .patch-header-actions,
      .button-row,
      .form-grid {
        grid-template-columns: 1fr;
        width: 100%;
      }
    }
  `]
})
export class GraphDataPatchComponent {
  @Input() factSheetId: number | null = null;
  @Input() factSheetName = '';

  readonly nodeTypes: NodeLevel[] = ['ENTITY', 'SOURCE', 'DOCUMENT', 'SNIPPET', 'CUSTOM', 'ATTACHMENT', 'TABLE'];

  mode: PatchMode = 'builder';
  useGlobal = false;
  loading = false;
  limit: number | null = null;
  lastResult: GraphMetadataPatchResult | null = null;
  lastError = '';
  lastDryRunFingerprint = '';

  ruleDraft: PatchRuleDraft = this.defaultRuleDraft();
  rawRulesJson = this.defaultRulesJson();

  constructor(
    private graphService: GraphService,
    private snackBar: MatSnackBar
  ) {}

  get scopeLabel(): string {
    if (this.useGlobal) {
      return 'Global graph scope';
    }
    if (this.factSheetId) {
      return `${this.factSheetName || 'Fact sheet'} #${this.factSheetId}`;
    }
    return 'No active fact sheet';
  }

  get requestPreview(): string {
    try {
      return this.formatJson(this.buildRequest(true));
    } catch (error) {
      return this.extractErrorMessage(error);
    }
  }

  resetBuilder(): void {
    this.ruleDraft = this.defaultRuleDraft();
    this.clearRunState();
  }

  syncRawRulesFromBuilder(): void {
    try {
      this.rawRulesJson = this.formatJson([this.buildRuleFromDraft()]);
      this.mode = 'json';
      this.clearRunState();
    } catch (error) {
      this.showError(error);
    }
  }

  formatRulesJson(): void {
    try {
      this.rawRulesJson = this.formatJson(this.parseRulesJson());
      this.clearRunState();
    } catch (error) {
      this.showError(error);
    }
  }

  dryRunPatch(): void {
    this.submitPatch(true);
  }

  applyPatch(): void {
    const fingerprint = this.currentFingerprint();
    if (!fingerprint || fingerprint !== this.lastDryRunFingerprint) {
      this.snackBar.open('Run Dry Run first', 'Dismiss', { duration: 2500 });
      return;
    }
    this.submitPatch(false);
  }

  canApply(): boolean {
    return !this.loading
      && !!this.lastResult
      && !!this.lastResult.dryRun
      && (this.lastResult?.changedCount ?? 0) > 0
      && this.currentFingerprint() === this.lastDryRunFingerprint;
  }

  trackSample(_index: number, sample: GraphMetadataPatchSample): string {
    return sample.nodeId;
  }

  formatJson(value: unknown): string {
    return JSON.stringify(value ?? {}, null, 2);
  }

  private submitPatch(dryRun: boolean): void {
    let request: GraphMetadataPatchRequest;
    let fingerprint = '';
    try {
      request = this.buildRequest(dryRun);
      fingerprint = this.fingerprintFor(request);
    } catch (error) {
      this.showError(error);
      return;
    }

    this.loading = true;
    this.lastError = '';
    this.graphService.patchNodeMetadata(request)
      .pipe(finalize(() => {
        this.loading = false;
      }))
      .subscribe({
        next: result => {
          this.lastResult = result;
          if (dryRun) {
            this.lastDryRunFingerprint = fingerprint;
          } else {
            this.lastDryRunFingerprint = '';
          }
          const action = dryRun ? 'Dry run complete' : 'Patch applied';
          this.snackBar.open(`${action}: ${result.changedCount} changed`, 'Dismiss', { duration: 3000 });
        },
        error: error => this.showError(error)
      });
  }

  private buildRequest(dryRun: boolean): GraphMetadataPatchRequest {
    if (!this.useGlobal && !this.factSheetId) {
      throw new Error('Active fact sheet required');
    }
    const rules = this.mode === 'builder'
      ? [this.buildRuleFromDraft()]
      : this.parseRulesJson();
    return {
      factSheetId: this.useGlobal ? null : this.factSheetId,
      allowGlobal: this.useGlobal,
      dryRun,
      limit: this.limit && this.limit > 0 ? Number(this.limit) : null,
      rules
    };
  }

  private buildRuleFromDraft(): GraphMetadataPatchRule {
    const setMetadata = this.parseJsonObject(this.ruleDraft.setMetadata, 'Set metadata JSON');
    const metadataEquals = this.parseJsonObject(this.ruleDraft.metadataEquals, 'Metadata equals JSON');
    const removeMetadataKeys = this.parseList(this.ruleDraft.removeMetadataKeys);

    if (Object.keys(setMetadata).length === 0 && removeMetadataKeys.length === 0) {
      throw new Error('Set metadata JSON or Remove keys is required');
    }

    const rule: GraphMetadataPatchRule = {
      nodeType: this.ruleDraft.nodeType,
      setMetadata
    };

    const name = this.ruleDraft.name.trim();
    if (name) {
      rule.name = name;
    }
    const entityType = this.ruleDraft.entityType.trim();
    if (entityType) {
      rule.entityType = entityType;
    }
    const titleEquals = this.parseList(this.ruleDraft.titleEquals);
    if (titleEquals.length) {
      rule.titleEquals = titleEquals;
    }
    const titleRegex = this.ruleDraft.titleRegex.trim();
    if (titleRegex) {
      rule.titleRegex = titleRegex;
    }
    if (Object.keys(metadataEquals).length) {
      rule.metadataEquals = metadataEquals;
    }
    const metadataExists = this.parseList(this.ruleDraft.metadataExists);
    if (metadataExists.length) {
      rule.metadataExists = metadataExists;
    }
    if (removeMetadataKeys.length) {
      rule.removeMetadataKeys = removeMetadataKeys;
    }

    return rule;
  }

  private parseRulesJson(): GraphMetadataPatchRule[] {
    let parsed: unknown;
    try {
      parsed = JSON.parse(this.rawRulesJson || '[]');
    } catch (error) {
      throw new Error(`Rules JSON is invalid: ${this.extractErrorMessage(error)}`);
    }
    const rules = Array.isArray(parsed) ? parsed : [parsed];
    if (!rules.length) {
      throw new Error('At least one rule is required');
    }
    return rules.map(rule => {
      if (!rule || Array.isArray(rule) || typeof rule !== 'object') {
        throw new Error('Each rule must be a JSON object');
      }
      return rule as GraphMetadataPatchRule;
    });
  }

  private parseJsonObject(value: string, label: string): Record<string, any> {
    if (!value || !value.trim()) {
      return {};
    }
    let parsed: unknown;
    try {
      parsed = JSON.parse(value);
    } catch (error) {
      throw new Error(`${label} is invalid: ${this.extractErrorMessage(error)}`);
    }
    if (!parsed || Array.isArray(parsed) || typeof parsed !== 'object') {
      throw new Error(`${label} must be a JSON object`);
    }
    return parsed as Record<string, any>;
  }

  private parseList(value: string): string[] {
    if (!value || !value.trim()) {
      return [];
    }
    return value
      .split(/[\n,]+/)
      .map(item => item.trim())
      .filter(item => item.length > 0);
  }

  private currentFingerprint(): string {
    try {
      return this.fingerprintFor(this.buildRequest(false));
    } catch {
      return '';
    }
  }

  private fingerprintFor(request: GraphMetadataPatchRequest): string {
    return JSON.stringify({
      factSheetId: request.factSheetId ?? null,
      allowGlobal: !!request.allowGlobal,
      limit: request.limit ?? null,
      rules: request.rules
    });
  }

  private defaultRuleDraft(): PatchRuleDraft {
    return {
      name: 'custom-category',
      nodeType: 'ENTITY',
      entityType: '',
      titleEquals: '',
      titleRegex: '',
      metadataEquals: '{}',
      metadataExists: '',
      setMetadata: this.formatJson({ entity_category: 'CUSTOM_CATEGORY' }),
      removeMetadataKeys: ''
    };
  }

  private defaultRulesJson(): string {
    return this.formatJson([
      {
        name: 'custom-category',
        nodeType: 'ENTITY',
        titleEquals: ['Example Entity'],
        setMetadata: {
          entity_category: 'CUSTOM_CATEGORY'
        }
      }
    ]);
  }

  private clearRunState(): void {
    this.lastResult = null;
    this.lastError = '';
    this.lastDryRunFingerprint = '';
  }

  private showError(error: unknown): void {
    this.lastError = this.extractErrorMessage(error);
    this.snackBar.open(this.lastError, 'Dismiss', { duration: 5000 });
  }

  private extractErrorMessage(error: unknown): string {
    if (error instanceof Error) {
      return error.message;
    }
    if (typeof error === 'object' && error !== null) {
      const candidate = error as { error?: unknown; message?: unknown };
      if (typeof candidate.error === 'object' && candidate.error !== null) {
        const body = candidate.error as { detail?: unknown; error?: unknown; message?: unknown };
        if (typeof body.detail === 'string') {
          return body.detail;
        }
        if (typeof body.error === 'string') {
          return body.error;
        }
        if (typeof body.message === 'string') {
          return body.message;
        }
      }
      if (typeof candidate.error === 'string') {
        return candidate.error;
      }
      if (typeof candidate.message === 'string') {
        return candidate.message;
      }
    }
    return 'Patch failed';
  }
}
