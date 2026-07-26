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

import { Component, Input, OnChanges, SimpleChanges } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { HttpClient, HttpParams } from '@angular/common/http';
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
import { MatAutocompleteModule } from '@angular/material/autocomplete';

import { KbGroundingService, AssertResponse } from '@shared/services/kb-grounding.service';
import { BaseService } from '@shared/services/base.service';

// Shape returned by GET /api/kb-grounding/{factSheetId}/audit
export interface FactAuditEvent {
  eventId: string;
  eventType: string;
  atomKey: string;
  occurredAt: string;       // ISO-8601
  actor?: string;
  sessionId?: string;
  valueBefore?: number;
  valueAfter?: number;
  confidenceBefore?: number;
  confidenceAfter?: number;
  strengthLayerBefore?: string;
  strengthLayerAfter?: string;
  runId?: string;
  derivationTrailRef?: string;
  pinnedAfter?: boolean;
  ruleId?: string;
  weightBefore?: number;
  weightAfter?: number;
  correctionReason?: string;
}

interface CorrectionEntry {
  atomKey: string;
  value: number;
  source: string;
  submittedAt: string;
  accepted?: boolean;
  previousValue?: number;
  newValue?: number;
}

/** Row from GET /api/kb-grounding/{factSheetId}/opinions?q= — powers the atom search type-ahead. */
interface FactOpinionRow {
  atomKey: string;
  confidence?: number;
  band?: string;
}

const EVENT_TYPE_LEGEND = [
  { type: 'ASSERTED',               color: '#4caf50', description: 'User manually asserted a value' },
  { type: 'DERIVED',                color: '#2196f3', description: 'Inferred from rules/evidence' },
  { type: 'REWEIGHTED',             color: '#ff9800', description: 'Confidence re-weighted by PSL/Bayesian pass' },
  { type: 'STRENGTH_CHANGED',       color: '#9c27b0', description: 'Strength band crossed a threshold' },
  { type: 'TOMBSTONED',             color: '#f44336', description: 'Fact suppressed / removed from KB' },
  { type: 'CORRECTED',              color: '#00bcd4', description: 'Corrected via human feedback' },
  { type: 'WEIGHT_TUNED',           color: '#795548', description: 'Weight tuned by online learning' },
  { type: 'PROMOTED',               color: '#009688', description: 'Fact promoted to a higher strength tier' },
  { type: 'FUSED',                  color: '#3f51b5', description: 'Contradictory signals fused into consensus' },
  { type: 'CONTRADICTION_RESOLVED', color: '#e91e63', description: 'Contradiction resolved between competing facts' },
  { type: 'RULE_CREATED',           color: '#ff5722', description: 'New PSL/MEBN rule created from evidence' },
  { type: 'PROCESS_CREATED',        color: '#795548', description: 'Process suggestion derived from graph' },
] as const;

const EVENT_TYPE_COLOR: Record<string, string> = Object.fromEntries(
  EVENT_TYPE_LEGEND.map(e => [e.type, e.color])
);

const EVENT_TYPE_ICON: Record<string, string> = {
  ASSERTED:               'check_circle',
  DERIVED:                'auto_fix_high',
  REWEIGHTED:             'scale',
  STRENGTH_CHANGED:       'show_chart',
  TOMBSTONED:             'delete_forever',
  CORRECTED:              'edit',
  WEIGHT_TUNED:           'tune',
  PROMOTED:               'trending_up',
  FUSED:                  'merge_type',
  CONTRADICTION_RESOLVED: 'balance',
  RULE_CREATED:           'gavel',
  PROCESS_CREATED:        'account_tree',
};

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
    MatDividerModule,
    MatAutocompleteModule
  ],
  template: `
    <mat-card class="audit-card">
      <mat-card-header>
        <mat-card-title>
          <mat-icon>history</mat-icon> Audit Trail
        </mat-card-title>
        <mat-card-subtitle>
          KB grounding history for fact sheet {{ factSheetId ?? '(none selected)' }}
        </mat-card-subtitle>
      </mat-card-header>

      <mat-card-content>
        <!-- Event type legend -->
        <div class="legend-section">
          <h4 class="legend-title">Event Type Legend</h4>
          <div class="legend-grid">
            <div *ngFor="let ev of eventLegend" class="legend-item"
                 [matTooltip]="ev.description">
              <span class="legend-dot" [style.background]="ev.color"></span>
              <mat-icon *ngIf="iconOf(ev.type)" class="legend-icon" style="font-size:14px;color:inherit;vertical-align:middle">{{ iconOf(ev.type) }}</mat-icon>
              <span class="legend-label">{{ ev.type }}</span>
            </div>
          </div>
        </div>

        <mat-divider></mat-divider>

        <!-- Timeline from backend -->
        <div class="timeline-section">
          <div class="timeline-header">
            <h4 class="list-title">
              Audit Timeline
              <span *ngIf="auditEvents.length > 0">({{ auditEvents.length }})</span>
            </h4>
            <button mat-icon-button (click)="loadAuditTrail()"
                    [disabled]="loadingAudit || !factSheetId"
                    matTooltip="Refresh timeline">
              <mat-icon>refresh</mat-icon>
            </button>
          </div>

          <div *ngIf="loadingAudit" class="loading-state">
            <mat-spinner diameter="24"></mat-spinner>
            <span>Loading audit trail…</span>
          </div>

          <div *ngIf="!loadingAudit && auditError" class="error-state">
            <mat-icon color="warn">error_outline</mat-icon>
            <span>{{ auditError }}</span>
          </div>

          <div *ngIf="!loadingAudit && !auditError && auditEvents.length === 0" class="empty-state">
            <mat-icon>info_outline</mat-icon>
            <span>No audit events yet for this fact sheet.</span>
          </div>

          <div *ngIf="!loadingAudit && auditEvents.length > 0" class="audit-timeline">
            <div *ngFor="let ev of auditEvents" class="audit-event-row">
              <div class="event-dot-col">
                <span class="legend-dot"
                      [style.background]="colorOf(ev.eventType)"
                      [matTooltip]="ev.eventType"></span>
                <div class="event-connector"></div>
              </div>
              <div class="event-body">
                <div class="event-header">
                  <span class="event-type-badge" [style.color]="colorOf(ev.eventType)">
                    <mat-icon *ngIf="iconOf(ev.eventType)" class="badge-icon" style="font-size:14px;vertical-align:middle">{{ iconOf(ev.eventType) }}</mat-icon>
                    {{ ev.eventType }}
                  </span>
                  <code class="atom-key">{{ ev.atomKey }}</code>
                  <span class="entry-time">{{ ev.occurredAt | date:'short' }}</span>
                  <button *ngIf="ev.pinnedAfter"
                          mat-icon-button
                          class="revert-btn"
                          [matTooltip]="'Revert PIN for ' + ev.atomKey"
                          (click)="revertPin(ev.atomKey)">
                    <mat-icon class="small-icon">undo</mat-icon>
                  </button>
                </div>
                <div class="event-detail">
                  <span *ngIf="isFinite(ev.valueBefore!) && isFinite(ev.valueAfter!)">
                    value: <strong>{{ ev.valueBefore | number:'1.2-3' }}</strong>
                    &rarr; <strong>{{ ev.valueAfter | number:'1.2-3' }}</strong>
                  </span>
                  <span *ngIf="ev.correctionReason"> | reason: {{ ev.correctionReason }}</span>
                  <span *ngIf="ev.actor"> | actor: {{ ev.actor }}</span>
                  <span *ngIf="ev.strengthLayerAfter"> | strength: {{ ev.strengthLayerAfter }}</span>
                </div>
              </div>
            </div>
          </div>
        </div>

        <mat-divider></mat-divider>

        <!-- Correction form -->
        <div class="correction-form">
          <h4 class="form-title">Submit Correction</h4>
          <p class="form-help">
            An <strong>atom</strong> is a single fact in the graph, written <code>predicate(args)</code> —
            e.g. <code>isEmployedBy(Alice, Acme)</code> or <code>State(acme)</code>. Search your facts
            below and pick one to correct, or type a new atom to assert it.
          </p>
          <div class="form-row">
            <mat-form-field appearance="outline" class="form-field-wide">
              <mat-label>Atom</mat-label>
              <input matInput [(ngModel)]="atomKey" (ngModelChange)="onAtomSearch()"
                     [matAutocomplete]="atomAuto"
                     placeholder="search facts — e.g. isEmployedBy, revenue, an entity name" />
              <mat-icon matSuffix>search</mat-icon>
            </mat-form-field>
            <mat-autocomplete #atomAuto="matAutocomplete">
              <mat-option *ngFor="let r of atomResults" [value]="r.atomKey">
                {{ r.atomKey }}
                <small *ngIf="r.band"> — {{ r.band }}<ng-container *ngIf="r.confidence != null"> · {{ (r.confidence * 100) | number:'1.0-0' }}%</ng-container></small>
              </mat-option>
              <mat-option *ngIf="atomSearching" disabled>Searching…</mat-option>
              <mat-option *ngIf="!atomSearching && atomKey.trim().length >= 2 && atomResults.length === 0" disabled>
                No matching facts — type a new atom to assert it
              </mat-option>
            </mat-autocomplete>
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

        <!-- Correction history (session-local) -->
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
export class AuditTimelineComponent extends BaseService implements OnChanges {
  @Input() factSheetId: number | null = null;

  readonly eventLegend = EVENT_TYPE_LEGEND;

  // ── Timeline state ───────────────────────────────────────────────────────────
  auditEvents: FactAuditEvent[] = [];
  loadingAudit = false;
  auditError: string | null = null;

  // ── Correction form state ────────────────────────────────────────────────────
  atomKey = '';
  correctionValue = 1.0;
  correctionSource = '';
  submitting = false;
  corrections: CorrectionEntry[] = [];

  // ── Atom search (type-ahead so users don't need to know atom keys) ─────────────
  atomResults: FactOpinionRow[] = [];
  atomSearching = false;
  private atomSearchTimer: any;

  constructor(
    private http: HttpClient,
    private kbGrounding: KbGroundingService,
    private snackBar: MatSnackBar
  ) {
    super();
  }

  /** Debounced search over existing facts so the user can pick an atom instead of knowing its key. */
  onAtomSearch(): void {
    const q = (this.atomKey || '').trim();
    clearTimeout(this.atomSearchTimer);
    if (this.factSheetId == null || q.length < 2) {
      this.atomResults = [];
      this.atomSearching = false;
      return;
    }
    this.atomSearching = true;
    this.atomSearchTimer = setTimeout(() => {
      const params = new HttpParams().set('q', q).set('limit', '50');
      this.http.get<FactOpinionRow[]>(`${this.backendUrl}/kb-grounding/${this.factSheetId}/opinions`, { params })
        .subscribe({
          next: (rows) => { this.atomResults = rows || []; this.atomSearching = false; },
          error: () => { this.atomResults = []; this.atomSearching = false; }
        });
    }, 300);
  }

  ngOnChanges(changes: SimpleChanges): void {
    if (changes['factSheetId'] && this.factSheetId != null) {
      this.loadAuditTrail();
    }
  }

  // ── Backend: load audit trail ────────────────────────────────────────────────

  loadAuditTrail(): void {
    if (this.factSheetId == null) return;
    this.loadingAudit = true;
    this.auditError = null;

    const url = `${this.backendUrl}/kb-grounding/${this.factSheetId}/audit`;
    const params = new HttpParams().set('limit', '200');

    this.http.get<FactAuditEvent[]>(url, { params }).subscribe({
      next: (events) => {
        this.loadingAudit = false;
        // Sort newest-first for the timeline display
        this.auditEvents = [...events].sort((a, b) =>
          new Date(b.occurredAt).getTime() - new Date(a.occurredAt).getTime()
        );
      },
      error: (err: any) => {
        this.loadingAudit = false;
        this.auditError = err?.error?.message || err?.message || 'Failed to load audit trail';
      }
    });
  }

  // ── Backend: revert a PIN ────────────────────────────────────────────────────

  revertPin(atomKey: string): void {
    if (this.factSheetId == null) return;
    const url = `${this.backendUrl}/kb-grounding/${this.factSheetId}/corrections/${encodeURIComponent(atomKey)}`;

    this.http.delete<{ status: string; atomKey: string }>(url).subscribe({
      next: () => {
        this.snackBar.open(`PIN reverted for "${atomKey}"`, 'Dismiss', { duration: 3000 });
        this.loadAuditTrail();
      },
      error: (err: any) => {
        const msg = err?.error?.message || err?.message || 'Revert failed';
        this.snackBar.open(msg, 'Dismiss', { duration: 5000 });
      }
    });
  }

  // ── Submission form ──────────────────────────────────────────────────────────

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
        // Refresh the backend timeline
        this.loadAuditTrail();
      },
      error: (err: any) => {
        this.submitting = false;
        const msg = err?.error?.message || err?.message || 'Assertion failed';
        this.snackBar.open(msg, 'Dismiss', { duration: 5000 });
      }
    });
  }

  // ── Helpers ──────────────────────────────────────────────────────────────────

  colorOf(eventType: string): string {
    return EVENT_TYPE_COLOR[eventType] ?? '#9e9e9e';
  }

  iconOf(eventType: string): string | null {
    return EVENT_TYPE_ICON[eventType] ?? null;
  }

  isFinite(n: number | undefined): boolean {
    return n !== undefined && Number.isFinite(n);
  }
}
