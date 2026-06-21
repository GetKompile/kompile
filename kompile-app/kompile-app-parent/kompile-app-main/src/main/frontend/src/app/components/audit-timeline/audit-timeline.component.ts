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

import { Component, Input } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatCardModule } from '@angular/material/card';
import { MatChipsModule } from '@angular/material/chips';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';
import { MatTooltipModule } from '@angular/material/tooltip';
import { MatDividerModule } from '@angular/material/divider';

import { KbGroundingService, AssertResponse } from '../../services/kb-grounding.service';

interface CorrectionEntry {
  atomKey: string;
  value: number;
  source: string;
  submittedAt: string;
  accepted?: boolean;
  previousValue?: number;
  newValue?: number;
}

const EVENT_TYPE_LEGEND = [
  { type: 'ASSERTED',         color: '#4caf50', description: 'User manually asserted a value' },
  { type: 'DERIVED',          color: '#2196f3', description: 'Inferred from rules/evidence' },
  { type: 'REWEIGHTED',       color: '#ff9800', description: 'Confidence re-weighted by PSL/Bayesian pass' },
  { type: 'STRENGTH_CHANGED', color: '#9c27b0', description: 'Strength band crossed a threshold' },
  { type: 'TOMBSTONED',       color: '#f44336', description: 'Fact suppressed / removed from KB' },
  { type: 'CORRECTED',        color: '#00bcd4', description: 'Corrected via human feedback' },
  { type: 'WEIGHT_TUNED',     color: '#795548', description: 'Weight tuned by online learning' }
] as const;

@Component({
  selector: 'app-audit-timeline',
  standalone: true,
  imports: [
    CommonModule,
    FormsModule,
    MatButtonModule,
    MatIconModule,
    MatInputModule,
    MatFormFieldModule,
    MatProgressSpinnerModule,
    MatCardModule,
    MatChipsModule,
    MatSnackBarModule,
    MatTooltipModule,
    MatDividerModule
  ],
  template: `
    <mat-card class="audit-card">
      <mat-card-header>
        <mat-card-title>
          <mat-icon>history</mat-icon> Audit Trail
        </mat-card-title>
        <mat-card-subtitle>Phase 3 — backend pending. Correction submission is live.</mat-card-subtitle>
      </mat-card-header>

      <mat-card-content>
        <!-- Event type legend -->
        <div class="legend-section">
          <h4 class="legend-title">Event Type Legend</h4>
          <div class="legend-grid">
            <div *ngFor="let ev of eventLegend" class="legend-item"
                 [matTooltip]="ev.description">
              <span class="legend-dot" [style.background]="ev.color"></span>
              <span class="legend-label">{{ ev.type }}</span>
            </div>
          </div>
        </div>

        <mat-divider></mat-divider>

        <!-- Correction form -->
        <div class="correction-form">
          <h4 class="form-title">Submit Correction</h4>
          <div class="form-row">
            <mat-form-field appearance="outline" class="form-field-wide">
              <mat-label>Atom Key</mat-label>
              <input matInput [(ngModel)]="atomKey" placeholder="e.g. node:42 or edge:revenue_growth" />
            </mat-form-field>
          </div>
          <div class="form-row">
            <mat-form-field appearance="outline" class="form-field-sm">
              <mat-label>Value (0–1)</mat-label>
              <input matInput type="number" min="0" max="1" step="0.01"
                     [(ngModel)]="correctionValue" />
            </mat-form-field>
            <mat-form-field appearance="outline" class="form-field-wide">
              <mat-label>Source</mat-label>
              <input matInput [(ngModel)]="correctionSource" placeholder="e.g. analyst-review" />
            </mat-form-field>
          </div>
          <div class="form-actions">
            <button mat-raised-button color="primary"
                    [disabled]="submitting || !atomKey.trim()"
                    (click)="submitCorrection()">
              <mat-spinner *ngIf="submitting" diameter="16" class="btn-spinner"></mat-spinner>
              <mat-icon *ngIf="!submitting">check_circle</mat-icon>
              {{ submitting ? 'Submitting…' : 'Assert Correction' }}
            </button>
          </div>
        </div>

        <mat-divider></mat-divider>

        <!-- Correction history -->
        <div class="corrections-list" *ngIf="corrections.length > 0">
          <h4 class="list-title">Submitted Corrections ({{ corrections.length }})</h4>
          <div *ngFor="let c of corrections" class="correction-entry">
            <div class="entry-header">
              <span class="legend-dot" style="background:#4caf50"></span>
              <strong class="atom-key">{{ c.atomKey }}</strong>
              <span class="entry-time">{{ c.submittedAt | date:'short' }}</span>
              <mat-chip *ngIf="c.accepted === true" color="accent" selected class="status-chip">accepted</mat-chip>
              <mat-chip *ngIf="c.accepted === false" color="warn" selected class="status-chip">rejected</mat-chip>
            </div>
            <div class="entry-detail">
              value: <strong>{{ c.value }}</strong>
              <span *ngIf="c.previousValue !== undefined"> (was {{ c.previousValue }})</span>
              &nbsp;| source: {{ c.source || '—' }}
            </div>
          </div>
        </div>
        <div *ngIf="corrections.length === 0" class="empty-state">
          <mat-icon>info_outline</mat-icon>
          No corrections submitted yet in this session.
        </div>
      </mat-card-content>
    </mat-card>
  `,
  styleUrls: ['./audit-timeline.component.css']
})
export class AuditTimelineComponent {
  @Input() factSheetId: number | null = null;

  readonly eventLegend = EVENT_TYPE_LEGEND;

  atomKey = '';
  correctionValue = 1.0;
  correctionSource = '';
  submitting = false;

  corrections: CorrectionEntry[] = [];

  constructor(
    private kbGrounding: KbGroundingService,
    private snackBar: MatSnackBar
  ) {}

  submitCorrection(): void {
    if (!this.atomKey.trim()) return;
    this.submitting = true;

    this.kbGrounding.assert({
      atom: this.atomKey.trim(),
      value: this.correctionValue,
      source: this.correctionSource.trim() || undefined,
      factSheetId: this.factSheetId
    }).subscribe({
      next: (resp: AssertResponse) => {
        this.submitting = false;
        const entry: CorrectionEntry = {
          atomKey: this.atomKey.trim(),
          value: this.correctionValue,
          source: this.correctionSource.trim(),
          submittedAt: new Date().toISOString(),
          accepted: resp.accepted,
          previousValue: resp.previousValue,
          newValue: resp.newValue
        };
        this.corrections.unshift(entry);
        this.snackBar.open(
          resp.accepted ? `Correction accepted for "${entry.atomKey}"` : `Correction rejected: ${resp.message || 'unknown reason'}`,
          'Dismiss',
          { duration: 4000 }
        );
        this.atomKey = '';
        this.correctionSource = '';
        this.correctionValue = 1.0;
      },
      error: (err: any) => {
        this.submitting = false;
        const msg = err?.error?.message || err?.message || 'Assertion failed';
        this.snackBar.open(msg, 'Dismiss', { duration: 5000 });
      }
    });
  }
}
