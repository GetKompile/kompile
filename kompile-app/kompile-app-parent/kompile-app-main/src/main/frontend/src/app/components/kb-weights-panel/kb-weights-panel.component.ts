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

interface WeightBackupInfo {
  backupId: string;
  programId: string;
  timestamp: string;
  versionAt: number;
  entryCount: number;
}

interface BackupResult {
  backupId: string | null;
  message: string | null;
}

interface ResetResult {
  programId: string;
  backupId: string | null;
  message: string | null;
}

interface NewSessionResult {
  programId: string;
  factSheetId: number;
  pslBackupId: string | null;
  mebnBackupId: string | null;
  message: string | null;
}

interface OperationResult {
  success: boolean;
  message: string;
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
          PSL / MEBN rule weights updated incrementally after every grounding cascade — weights persist and warm-start the next run
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
              and <strong>HIGH</strong> until it reaches <strong>ESTABLISHED</strong>. After each
              grounding cascade the PSL rule weights and MEBN edge strengths are updated by one
              warm-started gradient step whose signal is the cascade's MAP posteriors aggregated per
              entity (consensus targets) — not raw observed evidence — so the weights reflect the
              structural importance of each rule inside the soft-logic solve.
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

        <!-- How weights are learned — collapsible explainer -->
        <div class="how-learned-card">
          <button class="how-learned-toggle" (click)="howLearnedExpanded = !howLearnedExpanded"
                  [attr.aria-expanded]="howLearnedExpanded">
            <mat-icon class="conf-toggle-icon">{{ howLearnedExpanded ? 'expand_less' : 'expand_more' }}</mat-icon>
            <span class="conf-model-title">How weights are learned</span>
            <span class="conf-model-subtitle">Online incremental training — every grounding cascade</span>
          </button>

          <div class="how-learned-body" *ngIf="howLearnedExpanded">
            <p class="how-learned-text">
              After every grounding cascade the system runs <strong>one warm-started gradient step</strong>
              for both PSL rule weights and MEBN noisy-OR edge strengths — it never discards and refits
              from scratch. Instead, weights accumulate across cascades the same way Beta-evidence
              accumulates for individual facts: each cascade nudges the parameters a little closer to the
              consensus signal. The shared optimiser is a <strong>mean-normalised projected-gradient SGD</strong>
              (gradient = sum ÷ batch size, so the learning rate is batch-size-invariant; a projection step
              keeps weights in a valid range). The training signal is the cascade's MAP posteriors
              aggregated per entity, not raw extracted facts — this pulls rule weights toward the rules
              that actually matter in the soft-logic solve. PSL weights additionally use
              <strong>band-aware MAP regularisation</strong>: an ESTABLISHED-band rule is regularised
              toward a higher prior mean than a SPECULATIVE-band rule, so well-corroborated rules are not
              shrunk toward the same floor as uncertain ones. A weight here represents how strongly that
              rule is trusted when the soft-logic system solves for inferred beliefs.
            </p>
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
          <p class="mebn-description">MFrag noisy-OR edge strengths updated online each grounding cascade via finite-difference gradient descent, sharing the same mean-normalised SGD substrate as PSL (persisted to mebn-weights.json)</p>

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

        <!-- ── Session Management ──────────────────────────────────────────── -->
        <mat-divider class="section-div"></mat-divider>
        <div class="session-section">
          <div class="session-header">
            <mat-icon class="session-icon">model_training</mat-icon>
            <h4 class="session-title">Weight-Model Session</h4>
          </div>
          <p class="session-desc">
            Start a new training session to reset PSL rule weights and MEBN edge strengths to their
            cold-start defaults (PSL: config default, MEBN: 0.5 uniform prior). The current weights
            are automatically backed up before any reset — you can restore them from the Backups panel
            below.
          </p>

          <!-- Feedback row -->
          <div *ngIf="sessionFeedback" class="session-feedback"
               [class.session-feedback-error]="sessionFeedbackIsError"
               [class.session-feedback-ok]="!sessionFeedbackIsError">
            <mat-icon>{{ sessionFeedbackIsError ? 'error_outline' : 'check_circle' }}</mat-icon>
            <span>{{ sessionFeedback }}</span>
          </div>

          <div class="session-actions">
            <!-- New session button -->
            <button mat-raised-button color="warn"
                    *ngIf="!confirmingNewSession"
                    (click)="requestNewSession()"
                    [disabled]="sessionBusy || factSheetId == null"
                    matTooltip="{{ factSheetId == null ? 'Select a fact sheet first' : 'Back up + reinit all weights for a fresh training run' }}">
              <mat-icon>restart_alt</mat-icon>
              New Training Session
            </button>

            <!-- Confirm dialog (inline) -->
            <div *ngIf="confirmingNewSession" class="confirm-box">
              <mat-icon class="confirm-icon warn-icon">warning</mat-icon>
              <span class="confirm-text">
                This will <strong>reset all PSL and MEBN weights</strong> to cold-start defaults for
                <em>{{ selectedProgram }}</em> / fact sheet {{ factSheetId }}. Current weights will be
                backed up automatically. Continue?
              </span>
              <button mat-raised-button color="warn"
                      [disabled]="sessionBusy"
                      (click)="confirmNewSession()">
                <mat-spinner *ngIf="sessionBusy" diameter="16" class="inline-spinner"></mat-spinner>
                <mat-icon *ngIf="!sessionBusy">check</mat-icon>
                {{ sessionBusy ? 'Resetting…' : 'Yes, reset' }}
              </button>
              <button mat-button (click)="cancelNewSession()" [disabled]="sessionBusy">Cancel</button>
            </div>

            <!-- PSL-only reset -->
            <button mat-stroked-button
                    *ngIf="!confirmingPslReset"
                    (click)="requestPslReset()"
                    [disabled]="sessionBusy"
                    matTooltip="Back up + reset PSL rule weights only">
              <mat-icon>restart_alt</mat-icon>
              Reset PSL Only
            </button>
            <div *ngIf="confirmingPslReset" class="confirm-box">
              <mat-icon class="confirm-icon warn-icon">warning</mat-icon>
              <span class="confirm-text">Reset PSL weights for <em>{{ selectedProgram }}</em> to defaults?</span>
              <button mat-raised-button color="warn" [disabled]="sessionBusy" (click)="confirmPslReset()">
                <mat-spinner *ngIf="sessionBusy" diameter="16" class="inline-spinner"></mat-spinner>
                {{ sessionBusy ? 'Resetting…' : 'Yes' }}
              </button>
              <button mat-button (click)="cancelPslReset()" [disabled]="sessionBusy">Cancel</button>
            </div>

            <!-- MEBN-only reset -->
            <button mat-stroked-button
                    *ngIf="!confirmingMebnReset && factSheetId != null"
                    (click)="requestMebnReset()"
                    [disabled]="sessionBusy"
                    matTooltip="Back up + reset MEBN edge strengths only">
              <mat-icon>restart_alt</mat-icon>
              Reset MEBN Only
            </button>
            <div *ngIf="confirmingMebnReset" class="confirm-box">
              <mat-icon class="confirm-icon warn-icon">warning</mat-icon>
              <span class="confirm-text">Reset MEBN edge strengths for fact sheet {{ factSheetId }} to 0.5?</span>
              <button mat-raised-button color="warn" [disabled]="sessionBusy" (click)="confirmMebnReset()">
                <mat-spinner *ngIf="sessionBusy" diameter="16" class="inline-spinner"></mat-spinner>
                {{ sessionBusy ? 'Resetting…' : 'Yes' }}
              </button>
              <button mat-button (click)="cancelMebnReset()" [disabled]="sessionBusy">Cancel</button>
            </div>
          </div>
        </div>

        <!-- ── Backups ────────────────────────────────────────────────────── -->
        <div class="backups-section">
          <div class="backups-card">
            <button class="backups-toggle" (click)="backupsExpanded = !backupsExpanded"
                    [attr.aria-expanded]="backupsExpanded">
              <mat-icon class="conf-toggle-icon">{{ backupsExpanded ? 'expand_less' : 'expand_more' }}</mat-icon>
              <span class="conf-model-title">Backups</span>
              <span class="conf-model-subtitle">Snapshots taken before each reset — click to restore</span>
              <mat-spinner *ngIf="loadingBackups" diameter="14" class="conf-spinner"></mat-spinner>
            </button>

            <div class="backups-body" *ngIf="backupsExpanded">
              <div class="backups-tabs">
                <button class="backup-tab-btn" [class.active]="backupTab === 'psl'" (click)="setBackupTab('psl')">PSL</button>
                <button class="backup-tab-btn" [class.active]="backupTab === 'mebn'" (click)="setBackupTab('mebn')"
                        [disabled]="factSheetId == null">MEBN</button>
              </div>

              <div *ngIf="backupFeedback" class="session-feedback"
                   [class.session-feedback-error]="backupFeedbackIsError"
                   [class.session-feedback-ok]="!backupFeedbackIsError">
                <mat-icon>{{ backupFeedbackIsError ? 'error_outline' : 'check_circle' }}</mat-icon>
                <span>{{ backupFeedback }}</span>
              </div>

              <!-- PSL backups -->
              <div *ngIf="backupTab === 'psl'">
                <div class="backups-controls">
                  <button mat-stroked-button (click)="loadPslBackups()" [disabled]="loadingBackups">
                    <mat-icon>refresh</mat-icon> Refresh
                  </button>
                  <button mat-stroked-button (click)="triggerPslBackup()" [disabled]="sessionBusy">
                    <mat-icon>save</mat-icon> Backup Now
                  </button>
                </div>
                <div *ngIf="loadingBackups" class="spinner-row">
                  <mat-spinner diameter="20"></mat-spinner>
                  <span>Loading PSL backups…</span>
                </div>
                <div *ngIf="!loadingBackups && pslBackups.length === 0" class="empty-row">
                  <mat-icon>info_outline</mat-icon>
                  <span>No PSL backups yet.</span>
                </div>
                <div *ngIf="!loadingBackups && pslBackups.length > 0" class="backup-list">
                  <div *ngFor="let b of pslBackups" class="backup-row">
                    <mat-icon class="backup-icon">inventory_2</mat-icon>
                    <span class="backup-id">{{ b.backupId }}</span>
                    <span class="backup-count">{{ b.entryCount }} rules</span>
                    <button mat-icon-button matTooltip="Restore this backup"
                            [disabled]="sessionBusy || confirmingRestoreId === b.backupId"
                            (click)="requestRestore('psl', b.backupId)">
                      <mat-icon>restore</mat-icon>
                    </button>
                    <div *ngIf="confirmingRestoreId === b.backupId" class="inline-confirm">
                      <span class="confirm-text-sm">Restore weights from {{ b.backupId }}?</span>
                      <button mat-raised-button color="primary" [disabled]="sessionBusy"
                              (click)="confirmRestore('psl', b.backupId)">
                        <mat-spinner *ngIf="sessionBusy" diameter="14" class="inline-spinner"></mat-spinner>
                        {{ sessionBusy ? '…' : 'Restore' }}
                      </button>
                      <button mat-button (click)="cancelRestore()" [disabled]="sessionBusy">Cancel</button>
                    </div>
                  </div>
                </div>
              </div>

              <!-- MEBN backups -->
              <div *ngIf="backupTab === 'mebn' && factSheetId != null">
                <div class="backups-controls">
                  <button mat-stroked-button (click)="loadMebnBackups()" [disabled]="loadingBackups">
                    <mat-icon>refresh</mat-icon> Refresh
                  </button>
                  <button mat-stroked-button (click)="triggerMebnBackup()" [disabled]="sessionBusy">
                    <mat-icon>save</mat-icon> Backup Now
                  </button>
                </div>
                <div *ngIf="loadingBackups" class="spinner-row">
                  <mat-spinner diameter="20"></mat-spinner>
                  <span>Loading MEBN backups…</span>
                </div>
                <div *ngIf="!loadingBackups && mebnBackups.length === 0" class="empty-row">
                  <mat-icon>info_outline</mat-icon>
                  <span>No MEBN backups yet.</span>
                </div>
                <div *ngIf="!loadingBackups && mebnBackups.length > 0" class="backup-list">
                  <div *ngFor="let b of mebnBackups" class="backup-row">
                    <mat-icon class="backup-icon">inventory_2</mat-icon>
                    <span class="backup-id">{{ b.backupId }}</span>
                    <span class="backup-count">{{ b.entryCount }} edges</span>
                    <button mat-icon-button matTooltip="Restore this backup"
                            [disabled]="sessionBusy || confirmingRestoreId === b.backupId"
                            (click)="requestRestore('mebn', b.backupId)">
                      <mat-icon>restore</mat-icon>
                    </button>
                    <div *ngIf="confirmingRestoreId === b.backupId" class="inline-confirm">
                      <span class="confirm-text-sm">Restore MEBN weights from {{ b.backupId }}?</span>
                      <button mat-raised-button color="primary" [disabled]="sessionBusy"
                              (click)="confirmRestore('mebn', b.backupId)">
                        <mat-spinner *ngIf="sessionBusy" diameter="14" class="inline-spinner"></mat-spinner>
                        {{ sessionBusy ? '…' : 'Restore' }}
                      </button>
                      <button mat-button (click)="cancelRestore()" [disabled]="sessionBusy">Cancel</button>
                    </div>
                  </div>
                </div>
              </div>
            </div>
          </div>
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
  howLearnedExpanded = false;
  kbConfig: KbConfig | null = null;
  loadingKbConfig = false;
  kbConfigError: string | null = null;

  // ── Session management state ────────────────────────────────────────────────
  sessionBusy = false;
  sessionFeedback: string | null = null;
  sessionFeedbackIsError = false;

  confirmingNewSession = false;
  confirmingPslReset = false;
  confirmingMebnReset = false;

  // ── Backup panel state ──────────────────────────────────────────────────────
  backupsExpanded = false;
  backupTab: 'psl' | 'mebn' = 'psl';
  loadingBackups = false;
  backupFeedback: string | null = null;
  backupFeedbackIsError = false;
  pslBackups: WeightBackupInfo[] = [];
  mebnBackups: WeightBackupInfo[] = [];
  confirmingRestoreId: string | null = null;

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

  // ── Session management ────────────────────────────────────────────────────

  requestNewSession(): void {
    this.confirmingNewSession = true;
    this.confirmingPslReset = false;
    this.confirmingMebnReset = false;
    this.clearSessionFeedback();
  }

  cancelNewSession(): void { this.confirmingNewSession = false; }

  confirmNewSession(): void {
    if (this.factSheetId == null) return;
    this.sessionBusy = true;
    const params = new HttpParams()
      .set('programId', this.selectedProgram)
      .set('factSheetId', String(this.factSheetId));
    this.http.post<NewSessionResult>(`${this.backendUrl}/kb/weights/session/new`, null, { params }).subscribe({
      next: (result) => {
        this.sessionBusy = false;
        this.confirmingNewSession = false;
        if (result.message) {
          this.setSessionFeedback(result.message, true);
        } else {
          const pslMsg = result.pslBackupId ? `PSL backed up (${result.pslBackupId})` : 'PSL had no weights';
          const mebnMsg = result.mebnBackupId ? `MEBN backed up (${result.mebnBackupId})` : 'MEBN had no weights';
          this.setSessionFeedback(`New session started. ${pslMsg}; ${mebnMsg}.`, false);
        }
        this.loadWeights();
        this.loadMebnWeights();
      },
      error: (err) => {
        this.sessionBusy = false;
        this.confirmingNewSession = false;
        this.setSessionFeedback(err?.error?.message || err?.message || 'Reset failed', true);
      },
    });
  }

  requestPslReset(): void {
    this.confirmingPslReset = true;
    this.confirmingNewSession = false;
    this.confirmingMebnReset = false;
    this.clearSessionFeedback();
  }

  cancelPslReset(): void { this.confirmingPslReset = false; }

  confirmPslReset(): void {
    this.sessionBusy = true;
    const params = new HttpParams().set('programId', this.selectedProgram);
    this.http.post<ResetResult>(`${this.backendUrl}/kb/weights/reset`, null, { params }).subscribe({
      next: (result) => {
        this.sessionBusy = false;
        this.confirmingPslReset = false;
        const msg = result.backupId
          ? `PSL weights reset. Backup: ${result.backupId}`
          : 'PSL weights reset (no prior weights existed).';
        this.setSessionFeedback(msg, false);
        this.loadWeights();
      },
      error: (err) => {
        this.sessionBusy = false;
        this.confirmingPslReset = false;
        this.setSessionFeedback(err?.error?.message || err?.message || 'PSL reset failed', true);
      },
    });
  }

  requestMebnReset(): void {
    this.confirmingMebnReset = true;
    this.confirmingNewSession = false;
    this.confirmingPslReset = false;
    this.clearSessionFeedback();
  }

  cancelMebnReset(): void { this.confirmingMebnReset = false; }

  confirmMebnReset(): void {
    if (this.factSheetId == null) return;
    this.sessionBusy = true;
    this.http.post<ResetResult>(`${this.backendUrl}/kb/weights/mebn/${this.factSheetId}/reset`, null).subscribe({
      next: (result) => {
        this.sessionBusy = false;
        this.confirmingMebnReset = false;
        const msg = result.backupId
          ? `MEBN weights reset. Backup: ${result.backupId}`
          : 'MEBN weights reset (no prior weights existed).';
        this.setSessionFeedback(msg, false);
        this.loadMebnWeights();
      },
      error: (err) => {
        this.sessionBusy = false;
        this.confirmingMebnReset = false;
        this.setSessionFeedback(err?.error?.message || err?.message || 'MEBN reset failed', true);
      },
    });
  }

  // ── Backups panel ─────────────────────────────────────────────────────────

  setBackupTab(tab: 'psl' | 'mebn'): void {
    this.backupTab = tab;
    this.confirmingRestoreId = null;
    this.clearBackupFeedback();
    if (tab === 'psl') {
      this.loadPslBackups();
    } else if (tab === 'mebn' && this.factSheetId != null) {
      this.loadMebnBackups();
    }
  }

  loadPslBackups(): void {
    this.loadingBackups = true;
    const params = new HttpParams().set('programId', this.selectedProgram);
    this.http.get<WeightBackupInfo[]>(`${this.backendUrl}/kb/weights/backups`, { params }).subscribe({
      next: (backups) => {
        this.loadingBackups = false;
        this.pslBackups = backups || [];
      },
      error: () => {
        this.loadingBackups = false;
        this.pslBackups = [];
      },
    });
  }

  loadMebnBackups(): void {
    if (this.factSheetId == null) return;
    this.loadingBackups = true;
    this.http.get<WeightBackupInfo[]>(`${this.backendUrl}/kb/weights/mebn/${this.factSheetId}/backups`).subscribe({
      next: (backups) => {
        this.loadingBackups = false;
        this.mebnBackups = backups || [];
      },
      error: () => {
        this.loadingBackups = false;
        this.mebnBackups = [];
      },
    });
  }

  triggerPslBackup(): void {
    this.sessionBusy = true;
    this.clearBackupFeedback();
    const params = new HttpParams().set('programId', this.selectedProgram);
    this.http.post<BackupResult>(`${this.backendUrl}/kb/weights/backup`, null, { params }).subscribe({
      next: (result) => {
        this.sessionBusy = false;
        if (result.backupId) {
          this.setBackupFeedback(`PSL backup created: ${result.backupId}`, false);
          this.loadPslBackups();
        } else {
          this.setBackupFeedback(result.message || 'Nothing to back up', true);
        }
      },
      error: (err) => {
        this.sessionBusy = false;
        this.setBackupFeedback(err?.error?.message || 'Backup failed', true);
      },
    });
  }

  triggerMebnBackup(): void {
    if (this.factSheetId == null) return;
    this.sessionBusy = true;
    this.clearBackupFeedback();
    this.http.post<BackupResult>(`${this.backendUrl}/kb/weights/mebn/${this.factSheetId}/backup`, null).subscribe({
      next: (result) => {
        this.sessionBusy = false;
        if (result.backupId) {
          this.setBackupFeedback(`MEBN backup created: ${result.backupId}`, false);
          this.loadMebnBackups();
        } else {
          this.setBackupFeedback(result.message || 'Nothing to back up', true);
        }
      },
      error: (err) => {
        this.sessionBusy = false;
        this.setBackupFeedback(err?.error?.message || 'MEBN backup failed', true);
      },
    });
  }

  requestRestore(type: 'psl' | 'mebn', backupId: string): void {
    this.confirmingRestoreId = backupId;
    this.clearBackupFeedback();
  }

  cancelRestore(): void { this.confirmingRestoreId = null; }

  confirmRestore(type: 'psl' | 'mebn', backupId: string): void {
    this.sessionBusy = true;
    const url = type === 'psl'
      ? `${this.backendUrl}/kb/weights/restore`
      : `${this.backendUrl}/kb/weights/mebn/${this.factSheetId}/restore`;
    const params = type === 'psl'
      ? new HttpParams().set('programId', this.selectedProgram).set('backupId', backupId)
      : new HttpParams().set('backupId', backupId);
    this.http.post<{ success: boolean; message: string }>(url, null, { params }).subscribe({
      next: (result) => {
        this.sessionBusy = false;
        this.confirmingRestoreId = null;
        this.setBackupFeedback(result.message || 'Restored.', !result.success);
        if (result.success) {
          if (type === 'psl') { this.loadWeights(); this.loadPslBackups(); }
          else { this.loadMebnWeights(); this.loadMebnBackups(); }
        }
      },
      error: (err) => {
        this.sessionBusy = false;
        this.confirmingRestoreId = null;
        this.setBackupFeedback(err?.error?.message || 'Restore failed', true);
      },
    });
  }

  // ── Helpers ────────────────────────────────────────────────────────────────

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

  private setSessionFeedback(msg: string, isError: boolean): void {
    this.sessionFeedback = msg;
    this.sessionFeedbackIsError = isError;
    setTimeout(() => { this.sessionFeedback = null; }, 8000);
  }

  private clearSessionFeedback(): void {
    this.sessionFeedback = null;
  }

  private setBackupFeedback(msg: string, isError: boolean): void {
    this.backupFeedback = msg;
    this.backupFeedbackIsError = isError;
    setTimeout(() => { this.backupFeedback = null; }, 8000);
  }

  private clearBackupFeedback(): void {
    this.backupFeedback = null;
  }
}
