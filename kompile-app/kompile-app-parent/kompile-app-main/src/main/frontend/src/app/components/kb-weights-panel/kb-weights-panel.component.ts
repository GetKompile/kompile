/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */

import { Component, Input, OnChanges, OnInit, SimpleChanges } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { HttpClient, HttpParams } from '@angular/common/http';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatCardModule } from '@angular/material/card';
import { MatTableModule } from '@angular/material/table';
import { MatSelectModule } from '@angular/material/select';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatChipsModule } from '@angular/material/chips';
import { MatTooltipModule } from '@angular/material/tooltip';
import { MatDividerModule } from '@angular/material/divider';
import { BaseService } from '../../services/base.service';
import { KbConfigService, KbConfig } from '../../services/kb-config.service';

interface WeightsResponse {
  programId: string;
  version: number;
  weights: Record<string, number>;
  availablePrograms: string[];
  message?: string;
}

interface WeightRow {
  rule: string;
  weight: number;
}

interface MebnWeightRow {
  mFragName: string;
  conditionDescription: string;
  learnedStrength: number;
}

interface AuditEvent {
  eventId: string;
  eventType: string;
  atomKey: string;
  occurredAt: string;
  weightBefore?: number;
  weightAfter?: number;
  actor?: string;
}

@Component({
  selector: 'app-kb-weights-panel',
  standalone: true,
  imports: [
    CommonModule,
    FormsModule,
    MatButtonModule,
    MatIconModule,
    MatProgressSpinnerModule,
    MatCardModule,
    MatTableModule,
    MatSelectModule,
    MatFormFieldModule,
    MatChipsModule,
    MatTooltipModule,
    MatDividerModule,
  ],
  template: `
    <mat-card class="weights-card">
      <mat-card-header>
        <mat-card-title>
          <mat-icon>scale</mat-icon> Learned Weights
        </mat-card-title>
        <mat-card-subtitle>
          PSL / MEBN rule weights learned by online weight tuning
          <span *ngIf="currentVersion > 0">(v{{ currentVersion }})</span>
        </mat-card-subtitle>
      </mat-card-header>

      <mat-card-content>

        <!-- D3: Confidence Model card (read-only, collapsible) -->
        <div class="conf-model-card">
          <button class="conf-model-toggle" (click)="confidenceModelExpanded = !confidenceModelExpanded"
                  [attr.aria-expanded]="confidenceModelExpanded">
            <mat-icon class="conf-toggle-icon">{{ confidenceModelExpanded ? 'expand_less' : 'expand_more' }}</mat-icon>
            <span class="conf-model-title">Confidence Model</span>
            <span class="conf-model-subtitle">How facts move from SPECULATIVE → ESTABLISHED</span>
            <mat-spinner *ngIf="loadingKbConfig" diameter="14" class="conf-spinner"></mat-spinner>
          </button>

          <div class="conf-model-body" *ngIf="confidenceModelExpanded">
            <p class="conf-lifecycle">
              After a first crawl every fact starts <strong>SPECULATIVE</strong> (high uncertainty, low
              evidence). As subsequent crawls corroborate the same fact its corroboration count rises and
              the evidence prior pulls the confidence estimate upward through <strong>PROBABLE</strong>
              and <strong>HIGH</strong> until it reaches <strong>ESTABLISHED</strong>. Weight learning
              then tunes per-rule PSL/MEBN strengths to match observed evidence.
            </p>

            <div *ngIf="kbConfig" class="conf-table-wrapper">
              <table class="conf-table">
                <thead>
                  <tr><th>Parameter</th><th>Value</th><th>What it controls</th></tr>
                </thead>
                <tbody>
                  <tr>
                    <td>Evidence prior strength</td>
                    <td class="conf-val">{{ kbConfig.kbEvidencePriorStrength | number:'1.2-2' }}</td>
                    <td>How strongly a corroborated fact's belief rises per confirmation</td>
                  </tr>
                  <tr>
                    <td>Structural prior strength</td>
                    <td class="conf-val">{{ kbConfig.kbStructuralPriorStrength | number:'1.2-2' }}</td>
                    <td>Base credibility of graph-structural (non-LLM) evidence</td>
                  </tr>
                  <tr>
                    <td>Asserted prior strength</td>
                    <td class="conf-val">{{ kbConfig.kbAssertedPriorStrength | number:'1.2-2' }}</td>
                    <td>Trust for facts explicitly asserted by a human</td>
                  </tr>
                  <tr class="conf-sep"><td colspan="3"></td></tr>
                  <tr>
                    <td>Trust — structured upload</td>
                    <td class="conf-val">{{ kbConfig.kbTrustStructuredUpload | number:'1.2-2' }}</td>
                    <td>Source reliability of CSV / JSON uploads</td>
                  </tr>
                  <tr>
                    <td>Trust — PDF / Office</td>
                    <td class="conf-val">{{ kbConfig.kbTrustPdfOffice | number:'1.2-2' }}</td>
                    <td>Source reliability of parsed document files</td>
                  </tr>
                  <tr>
                    <td>Trust — LLM extraction</td>
                    <td class="conf-val">{{ kbConfig.kbTrustLlmExtraction | number:'1.2-2' }}</td>
                    <td>Reliability of facts inferred by an LLM</td>
                  </tr>
                  <tr>
                    <td>Trust — web scrape</td>
                    <td class="conf-val">{{ kbConfig.kbTrustWebScrape | number:'1.2-2' }}</td>
                    <td>Reliability of web-scraped content</td>
                  </tr>
                  <tr>
                    <td>Trust — email body</td>
                    <td class="conf-val">{{ kbConfig.kbTrustEmailBody | number:'1.2-2' }}</td>
                    <td>Reliability of email-body facts</td>
                  </tr>
                  <tr class="conf-sep"><td colspan="3"></td></tr>
                  <tr>
                    <td>PSL default rule weight</td>
                    <td class="conf-val">{{ kbConfig.kbPslDefaultRuleWeight | number:'1.2-2' }}</td>
                    <td>Starting weight for PSL inference rules before learning</td>
                  </tr>
                  <tr>
                    <td>Ontology rule weight</td>
                    <td class="conf-val">{{ kbConfig.kbOntologyRuleWeight | number:'1.2-2' }}</td>
                    <td>Extra weight given to facts matching the bound ontology</td>
                  </tr>
                </tbody>
              </table>
            </div>

            <div *ngIf="kbConfigError" class="conf-error">
              <mat-icon>error_outline</mat-icon>
              <span>{{ kbConfigError }}</span>
            </div>

            <div *ngIf="!loadingKbConfig && !kbConfig && !kbConfigError" class="conf-empty">
              <mat-icon>info_outline</mat-icon>
              <span>Confidence model config not yet available.</span>
            </div>
          </div>
        </div>

        <mat-divider class="section-div"></mat-divider>

        <!-- Program selector -->
        <div class="controls-row">
          <mat-form-field appearance="outline" class="program-select">
            <mat-label>Program / Ruleset</mat-label>
            <mat-select [(ngModel)]="selectedProgram" (selectionChange)="loadWeights()">
              <mat-option [value]="'default'">default</mat-option>
              <mat-option *ngFor="let p of availablePrograms" [value]="p">{{ p }}</mat-option>
            </mat-select>
          </mat-form-field>
          <button mat-icon-button (click)="loadWeights()" [disabled]="loadingWeights" matTooltip="Refresh">
            <mat-icon>refresh</mat-icon>
          </button>
        </div>

        <!-- Loading -->
        <div *ngIf="loadingWeights" class="spinner-row">
          <mat-spinner diameter="28"></mat-spinner>
          <span>Loading weights…</span>
        </div>

        <!-- Error -->
        <div *ngIf="!loadingWeights && weightsError" class="error-row">
          <mat-icon color="warn">error_outline</mat-icon>
          <span>{{ weightsError }}</span>
        </div>

        <!-- Empty / Not available -->
        <div *ngIf="!loadingWeights && !weightsError && weightRows.length === 0 && !weightsNoData && !weightsUnavailable" class="empty-row">
          <mat-icon>info_outline</mat-icon>
          <span>No weights stored yet for program "{{ selectedProgram }}". Weights appear after the first online learning pass.</span>
        </div>

        <!-- 404: learning hasn't run yet -->
        <div *ngIf="!loadingWeights && weightsNoData" class="empty-row">
          <mat-icon>pending_actions</mat-icon>
          <span>No weights stored yet for "{{ selectedProgram }}" — learning hasn't run. Run a crawl with online learning enabled to populate weights.</span>
        </div>

        <!-- 503: backend temporarily unavailable -->
        <div *ngIf="!loadingWeights && weightsUnavailable" class="empty-row warn-row">
          <mat-icon color="warn">cloud_off</mat-icon>
          <span>Weight service temporarily unavailable (503). The backend may still be starting — try refreshing in a moment.</span>
        </div>

        <!-- Weights table -->
        <table mat-table [dataSource]="weightRows" *ngIf="!loadingWeights && !weightsError && weightRows.length > 0" class="weights-table">
          <ng-container matColumnDef="rule">
            <th mat-header-cell *matHeaderCellDef>Rule</th>
            <td mat-cell *matCellDef="let row" class="rule-cell" [matTooltip]="row.rule">
              <code>{{ row.rule }}</code>
            </td>
          </ng-container>
          <ng-container matColumnDef="weight">
            <th mat-header-cell *matHeaderCellDef>Weight</th>
            <td mat-cell *matCellDef="let row">
              <span class="weight-bar-container" [matTooltip]="row.weight | number:'1.4-4'">
                <span class="weight-bar" [style.width.%]="weightBarPct(row.weight)"
                      [style.background]="weightColor(row.weight)"></span>
                <span class="weight-label">{{ row.weight | number:'1.3-3' }}</span>
              </span>
            </td>
          </ng-container>
          <tr mat-header-row *matHeaderRowDef="weightColumns"></tr>
          <tr mat-row *matRowDef="let row; columns: weightColumns;"></tr>
        </table>

        <mat-divider *ngIf="factSheetId != null" class="section-div"></mat-divider>

        <!-- D6 MEBN Theory Inspector -->
        <div class="mebn-section" *ngIf="factSheetId != null">
          <div class="mebn-header">
            <h4>MEBN Theory Inspector</h4>
            <button mat-icon-button (click)="loadMebnWeights()" [disabled]="loadingMebn" matTooltip="Refresh MEBN weights">
              <mat-icon>refresh</mat-icon>
            </button>
          </div>
          <p class="mebn-description">MFrag noisy-OR edge strengths learned by gradient descent (source: mebn-weights.json)</p>

          <div *ngIf="loadingMebn" class="spinner-row">
            <mat-spinner diameter="20"></mat-spinner>
            <span>Loading MEBN weights…</span>
          </div>

          <div *ngIf="!loadingMebn && mebnError" class="error-row">
            <mat-icon color="warn">error_outline</mat-icon>
            <span>{{ mebnError }}</span>
          </div>

          <div *ngIf="!loadingMebn && !mebnError && mebnRows.length === 0" class="empty-row">
            <mat-icon>info_outline</mat-icon>
            <span>No MEBN weights found. Run online MEBN weight learning to populate.</span>
          </div>

          <table mat-table [dataSource]="mebnRows" *ngIf="!loadingMebn && !mebnError && mebnRows.length > 0" class="weights-table mebn-table">
            <ng-container matColumnDef="mFragName">
              <th mat-header-cell *matHeaderCellDef>MFrag</th>
              <td mat-cell *matCellDef="let row" class="rule-cell" [matTooltip]="row.mFragName">
                <code>{{ row.mFragName }}</code>
              </td>
            </ng-container>
            <ng-container matColumnDef="conditionDescription">
              <th mat-header-cell *matHeaderCellDef>Condition (parent→child)</th>
              <td mat-cell *matCellDef="let row" class="rule-cell" [matTooltip]="row.conditionDescription">
                <code>{{ row.conditionDescription }}</code>
              </td>
            </ng-container>
            <ng-container matColumnDef="learnedStrength">
              <th mat-header-cell *matHeaderCellDef>Learned Strength</th>
              <td mat-cell *matCellDef="let row">
                <span class="weight-bar-container" [matTooltip]="row.learnedStrength | number:'1.4-4'">
                  <span class="weight-bar" [style.width.%]="weightBarPct(row.learnedStrength)"
                        [style.background]="weightColor(row.learnedStrength)"></span>
                  <span class="weight-label">{{ row.learnedStrength | number:'1.3-3' }}</span>
                </span>
              </td>
            </ng-container>
            <tr mat-header-row *matHeaderRowDef="mebnColumns"></tr>
            <tr mat-row *matRowDef="let row; columns: mebnColumns;"></tr>
          </table>
        </div>

        <mat-divider *ngIf="weightRows.length > 0 || tuningHistory.length > 0" class="section-div"></mat-divider>

        <!-- WEIGHT_TUNED audit history -->
        <div class="tuning-history" *ngIf="factSheetId != null">
          <div class="history-header">
            <h4>Weight Tuning History</h4>
            <button mat-icon-button (click)="loadTuningHistory()" [disabled]="loadingHistory" matTooltip="Refresh history">
              <mat-icon>refresh</mat-icon>
            </button>
          </div>

          <div *ngIf="loadingHistory" class="spinner-row">
            <mat-spinner diameter="20"></mat-spinner>
          </div>

          <div *ngIf="!loadingHistory && tuningHistory.length === 0" class="empty-row">
            <mat-icon>info_outline</mat-icon>
            <span>No weight-tuning events in the audit log yet.</span>
          </div>

          <div *ngIf="!loadingHistory && tuningHistory.length > 0" class="history-list">
            <div *ngFor="let ev of tuningHistory" class="history-row">
              <span class="history-time">{{ ev.occurredAt | date:'short' }}</span>
              <code class="history-atom">{{ ev.atomKey }}</code>
              <span *ngIf="isFinite(ev.weightBefore) && isFinite(ev.weightAfter)" class="history-delta">
                {{ ev.weightBefore | number:'1.3-3' }} → {{ ev.weightAfter | number:'1.3-3' }}
              </span>
              <span *ngIf="ev.actor" class="history-actor">{{ ev.actor }}</span>
            </div>
          </div>
        </div>

      </mat-card-content>
    </mat-card>
  `,
  styleUrls: ['./kb-weights-panel.component.css'],
})
export class KbWeightsPanelComponent extends BaseService implements OnInit, OnChanges {
  @Input() factSheetId: number | null = null;

  selectedProgram = 'default';
  availablePrograms: string[] = [];
  currentVersion = 0;
  weightRows: WeightRow[] = [];
  weightColumns = ['rule', 'weight'];

  loadingWeights = false;
  weightsError: string | null = null;
  /** D3 (404/503 distinction): set when /kb/weights returns 404 (no weights yet vs. truly unavailable) */
  weightsNoData = false;
  weightsUnavailable = false;

  tuningHistory: AuditEvent[] = [];
  loadingHistory = false;

  mebnRows: MebnWeightRow[] = [];
  mebnColumns = ['mFragName', 'conditionDescription', 'learnedStrength'];
  loadingMebn = false;
  mebnError: string | null = null;

  // D3: Confidence Model card state
  confidenceModelExpanded = false;
  kbConfig: KbConfig | null = null;
  loadingKbConfig = false;
  kbConfigError: string | null = null;

  constructor(private http: HttpClient, private kbConfigService: KbConfigService) {
    super();
  }

  ngOnInit(): void {
    // derive a program id from the fact sheet id if available
    if (this.factSheetId != null) {
      this.selectedProgram = String(this.factSheetId);
    }
    this.loadWeights();
    this.loadKbConfig();
    if (this.factSheetId != null) {
      this.loadTuningHistory();
      this.loadMebnWeights();
    }
  }

  ngOnChanges(changes: SimpleChanges): void {
    if (changes['factSheetId'] && !changes['factSheetId'].firstChange) {
      if (this.factSheetId != null) {
        this.selectedProgram = String(this.factSheetId);
        this.loadTuningHistory();
        this.loadMebnWeights();
      }
      this.loadWeights();
    }
  }

  loadWeights(): void {
    this.loadingWeights = true;
    this.weightsError = null;
    this.weightsNoData = false;
    this.weightsUnavailable = false;

    const params = new HttpParams().set('programId', this.selectedProgram);
    this.http.get<WeightsResponse>(`${this.backendUrl}/kb/weights`, { params }).subscribe({
      next: (resp) => {
        this.loadingWeights = false;
        this.currentVersion = resp.version;
        this.availablePrograms = (resp.availablePrograms || []).filter(p => p !== this.selectedProgram);
        if (resp.weights) {
          this.weightRows = Object.entries(resp.weights)
            .map(([rule, weight]) => ({ rule, weight }))
            .sort((a, b) => b.weight - a.weight);
        } else {
          this.weightRows = [];
        }
        if (resp.message) {
          this.weightsError = resp.message;
        }
      },
      error: (err) => {
        this.loadingWeights = false;
        const status = err?.status;
        if (status === 404) {
          // 404 = no weights stored yet for this program; learning hasn't run
          this.weightRows = [];
          this.weightsNoData = true;
        } else if (status === 503) {
          // 503 = backend service temporarily unavailable (adapter absent / starting)
          this.weightRows = [];
          this.weightsUnavailable = true;
        } else {
          this.weightsError = err?.error?.message || err?.message || 'Failed to load weights';
        }
      },
    });
  }

  loadMebnWeights(): void {
    if (this.factSheetId == null) return;
    this.loadingMebn = true;
    this.mebnError = null;

    this.http.get<MebnWeightRow[]>(`${this.backendUrl}/kb/weights/mebn/${this.factSheetId}`).subscribe({
      next: (rows) => {
        this.loadingMebn = false;
        this.mebnRows = rows || [];
      },
      error: (err) => {
        this.loadingMebn = false;
        const status = err?.status;
        if (status === 404 || status === 503) {
          this.mebnRows = [];
          this.mebnError = null;
        } else {
          this.mebnError = err?.error?.message || err?.message || 'Failed to load MEBN weights';
        }
      },
    });
  }

  loadTuningHistory(): void {
    if (this.factSheetId == null) return;
    this.loadingHistory = true;

    const params = new HttpParams().set('eventType', 'WEIGHT_TUNED').set('limit', '50');
    this.http.get<AuditEvent[]>(`${this.backendUrl}/kb-grounding/${this.factSheetId}/audit`, { params }).subscribe({
      next: (events) => {
        this.loadingHistory = false;
        this.tuningHistory = events.sort((a, b) =>
          new Date(b.occurredAt).getTime() - new Date(a.occurredAt).getTime()
        );
      },
      error: () => {
        this.loadingHistory = false;
        this.tuningHistory = [];
      },
    });
  }

  weightBarPct(w: number): number {
    return Math.min(100, Math.max(0, Math.abs(w) * 100));
  }

  weightColor(w: number): string {
    if (w >= 0.7) return '#4CAF50';
    if (w >= 0.4) return '#FFC107';
    if (w >= 0.1) return '#FF9800';
    return '#F44336';
  }

  isFinite(n: number | undefined): boolean {
    return n !== undefined && Number.isFinite(n);
  }

  /** D3: Load the KbConfig to power the Confidence Model card. */
  loadKbConfig(): void {
    this.loadingKbConfig = true;
    this.kbConfigError = null;
    this.kbConfigService.getConfig().subscribe({
      next: (cfg) => {
        this.loadingKbConfig = false;
        this.kbConfig = cfg;
      },
      error: (err) => {
        this.loadingKbConfig = false;
        const status = err?.status;
        if (status === 404 || status === 503) {
          // Config not yet persisted — silently suppress; defaults are shown elsewhere
          this.kbConfig = null;
        } else {
          this.kbConfigError = err?.error?.message || err?.message || 'Failed to load config';
        }
      },
    });
  }
}
