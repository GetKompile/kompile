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

import { Component, Input, OnChanges, OnInit, SimpleChanges } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { HttpClient } from '@angular/common/http';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatCardModule } from '@angular/material/card';
import { MatChipsModule } from '@angular/material/chips';
import { MatTooltipModule } from '@angular/material/tooltip';
import { MatTableModule } from '@angular/material/table';

import { BaseService } from '../../services/base.service';

export interface FactTierRow {
  atomKey: string;
  confidence: number;
  band: string;
  promotionStatus: string;
  corroborationCount: number;
  /** Epoch millis when this fact became valid (sourced from inferredAt). Null = unknown. */
  validFrom: number | null;
  /** Epoch millis when this fact ceased to be valid. Null = still valid / unbounded. */
  validTo: number | null;
}

interface TierChip {
  label: string;
  value: string | null;
  color: string;
}

interface BandSegment {
  band: string;
  count: number;
  pct: number;
  color: string;
}

const TIER_CHIPS: TierChip[] = [
  { label: 'All',         value: null,          color: '#9E9E9E' },
  { label: 'Established', value: 'ESTABLISHED',  color: '#4CAF50' },
  { label: 'High',        value: 'HIGH',         color: '#8BC34A' },
  { label: 'Probable',    value: 'PROBABLE',     color: '#FFC107' },
  { label: 'Speculative', value: 'SPECULATIVE',  color: '#FF9800' },
  { label: 'Suppressed',  value: 'SUPPRESSED',   color: '#F44336' },
];

/** Canonical band order for stacked bar (highest confidence first). */
const BAND_ORDER = ['ESTABLISHED', 'HIGH', 'PROBABLE', 'SPECULATIVE', 'SUPPRESSED'];

@Component({
  selector: 'app-facts-by-tier-panel',
  standalone: true,
  imports: [
    CommonModule,
    FormsModule,
    MatButtonModule,
    MatIconModule,
    MatProgressSpinnerModule,
    MatCardModule,
    MatChipsModule,
    MatTooltipModule,
    MatTableModule,
  ],
  template: `
    <mat-card class="facts-tier-card">
      <mat-card-header>
        <mat-card-title>Facts by Tier</mat-card-title>
        <mat-card-subtitle *ngIf="factSheetId != null">Fact Sheet {{ factSheetId }}</mat-card-subtitle>
      </mat-card-header>

      <mat-card-content>

        <!-- D8: Band-count stacked bar -->
        <div class="band-bar-wrapper" *ngIf="bandSegments.length > 0" aria-label="Band count overview">
          <div class="band-bar-track">
            <div
              *ngFor="let seg of bandSegments"
              class="band-bar-segment"
              [style.width.%]="seg.pct"
              [style.background]="seg.color"
              [matTooltip]="seg.band + ': ' + seg.count + ' (' + (seg.pct | number:'1.1-1') + '%)'">
            </div>
          </div>
          <div class="band-bar-legend">
            <span *ngFor="let seg of bandSegments" class="band-legend-item">
              <span class="legend-dot" [style.background]="seg.color"></span>
              <span class="legend-label">{{ seg.band | titlecase }}</span>
              <span class="legend-count">{{ seg.count }}</span>
            </span>
          </div>
        </div>

        <!-- Tier filter chips -->
        <div class="tier-chips">
          <button
            *ngFor="let chip of tierChips"
            class="tier-chip"
            [class.active]="selectedTier === chip.value"
            [style.--chip-color]="chip.color"
            (click)="selectTier(chip.value)"
            matTooltip="{{ chip.value ?? 'All tiers' }}">
            <span class="chip-dot" [style.background]="chip.color"></span>
            {{ chip.label }}
          </button>
        </div>

        <!-- D7: Temporal filter -->
        <div class="temporal-filter">
          <span class="temporal-label">
            <mat-icon class="temporal-icon">schedule</mat-icon>
            Time range:
          </span>
          <div class="temporal-inputs">
            <label class="date-label">From
              <input
                type="date"
                class="date-input"
                [(ngModel)]="validFromDate"
                (change)="onTemporalChange()"
                aria-label="Valid from date" />
            </label>
            <label class="date-label">To
              <input
                type="date"
                class="date-input"
                [(ngModel)]="validToDate"
                (change)="onTemporalChange()"
                aria-label="Valid to date" />
            </label>
            <label class="exclude-label">
              <input
                type="checkbox"
                [(ngModel)]="excludeUndated"
                (change)="onTemporalChange()" />
              Exclude undated
            </label>
            <button
              *ngIf="validFromDate || validToDate"
              class="clear-dates-btn"
              (click)="clearTemporalFilter()"
              matTooltip="Clear date filter">
              <mat-icon>clear</mat-icon>
            </button>
          </div>
        </div>

        <!-- Loading -->
        <div *ngIf="loading" class="spinner-row">
          <mat-spinner diameter="32"></mat-spinner>
        </div>

        <!-- Error -->
        <div *ngIf="!loading && error" class="error-msg">
          <mat-icon>error_outline</mat-icon>
          <span>{{ error }}</span>
        </div>

        <!-- Empty -->
        <div *ngIf="!loading && !error && rows.length === 0 && factSheetId != null" class="empty-msg">
          <mat-icon>info_outline</mat-icon>
          <span>No facts found{{ selectedTier ? ' for tier ' + selectedTier : '' }}.</span>
        </div>

        <!-- No fact sheet selected -->
        <div *ngIf="!loading && !error && factSheetId == null" class="empty-msg">
          <mat-icon>info_outline</mat-icon>
          <span>Select a fact sheet to browse facts by tier.</span>
        </div>

        <!-- Results table -->
        <table mat-table [dataSource]="rows" *ngIf="!loading && !error && rows.length > 0" class="facts-table">
          <ng-container matColumnDef="atomKey">
            <th mat-header-cell *matHeaderCellDef>Atom Key</th>
            <td mat-cell *matCellDef="let row" class="atom-key-cell" [matTooltip]="row.atomKey">{{ row.atomKey }}</td>
          </ng-container>

          <ng-container matColumnDef="band">
            <th mat-header-cell *matHeaderCellDef>Band</th>
            <td mat-cell *matCellDef="let row">
              <span class="band-badge" [style.background]="bandColor(row.band)">{{ row.band }}</span>
            </td>
          </ng-container>

          <ng-container matColumnDef="confidence">
            <th mat-header-cell *matHeaderCellDef>Confidence</th>
            <td mat-cell *matCellDef="let row">{{ row.confidence | number:'1.3-3' }}</td>
          </ng-container>

          <ng-container matColumnDef="promotionStatus">
            <th mat-header-cell *matHeaderCellDef>Promotion Status</th>
            <td mat-cell *matCellDef="let row">{{ row.promotionStatus }}</td>
          </ng-container>

          <ng-container matColumnDef="corroborationCount">
            <th mat-header-cell *matHeaderCellDef>Corroborations</th>
            <td mat-cell *matCellDef="let row">{{ row.corroborationCount }}</td>
          </ng-container>

          <ng-container matColumnDef="validFrom">
            <th mat-header-cell *matHeaderCellDef>Valid From</th>
            <td mat-cell *matCellDef="let row" class="date-cell">
              {{ row.validFrom != null ? (row.validFrom | date:'yyyy-MM-dd') : '—' }}
            </td>
          </ng-container>

          <ng-container matColumnDef="validTo">
            <th mat-header-cell *matHeaderCellDef>Valid To</th>
            <td mat-cell *matCellDef="let row" class="date-cell">
              {{ row.validTo != null ? (row.validTo | date:'yyyy-MM-dd') : '∞' }}
            </td>
          </ng-container>

          <tr mat-header-row *matHeaderRowDef="displayedColumns"></tr>
          <tr mat-row *matRowDef="let row; columns: displayedColumns;"></tr>
        </table>

        <div *ngIf="!loading && !error && rows.length >= 500" class="cap-notice">
          Results capped at 500. Refine by tier or date range to see more.
        </div>
      </mat-card-content>
    </mat-card>
  `,
  styleUrls: ['./facts-by-tier-panel.component.css'],
})
export class FactsByTierPanelComponent extends BaseService implements OnInit, OnChanges {
  @Input() factSheetId: number | null = null;

  tierChips: TierChip[] = TIER_CHIPS;
  selectedTier: string | null = null;
  rows: FactTierRow[] = [];
  loading = false;
  error: string | null = null;

  /** D8: Stacked bar segments, derived from the band-summary response. */
  bandSegments: BandSegment[] = [];

  /** D7: Temporal filter state — ISO date strings (yyyy-MM-dd) for native date inputs. */
  validFromDate: string = '';
  validToDate: string = '';
  excludeUndated: boolean = false;

  displayedColumns = [
    'atomKey', 'band', 'confidence', 'promotionStatus', 'corroborationCount',
    'validFrom', 'validTo',
  ];

  private readonly BAND_COLORS: Record<string, string> = {
    ESTABLISHED: '#4CAF50',
    HIGH:        '#8BC34A',
    PROBABLE:    '#FFC107',
    SPECULATIVE: '#FF9800',
    SUPPRESSED:  '#F44336',
  };

  constructor(private http: HttpClient) {
    super();
  }

  ngOnInit(): void {
    if (this.factSheetId != null) {
      this.loadBandSummary();
      this.loadFacts();
    }
  }

  ngOnChanges(changes: SimpleChanges): void {
    if (changes['factSheetId'] && !changes['factSheetId'].firstChange) {
      this.rows = [];
      this.bandSegments = [];
      this.error = null;
      if (this.factSheetId != null) {
        this.loadBandSummary();
        this.loadFacts();
      }
    }
  }

  selectTier(tier: string | null): void {
    this.selectedTier = tier;
    if (this.factSheetId != null) {
      this.loadFacts();
    }
  }

  onTemporalChange(): void {
    if (this.factSheetId != null) {
      this.loadFacts();
    }
  }

  clearTemporalFilter(): void {
    this.validFromDate = '';
    this.validToDate = '';
    this.excludeUndated = false;
    if (this.factSheetId != null) {
      this.loadFacts();
    }
  }

  // ── D8: Band-count stacked bar ──────────────────────────────────────────────

  /**
   * Load the band summary from the existing /band-summary endpoint
   * (GET /api/kb-grounding/{factSheetId}/band-summary → Map<String,Long>)
   * and build the stacked-bar segments.
   */
  loadBandSummary(): void {
    if (this.factSheetId == null) return;
    const url = `${this.backendUrl}/kb-grounding/${this.factSheetId}/band-summary`;
    this.http.get<Record<string, number>>(url).subscribe({
      next: (summary) => {
        this.bandSegments = this.buildBandSegments(summary);
      },
      error: () => {
        // Non-fatal: stacked bar just stays hidden
        this.bandSegments = [];
      },
    });
  }

  /**
   * Build sorted, percentage-annotated segments from a band → count map.
   * Bands with 0 count are omitted. Order follows BAND_ORDER.
   */
  buildBandSegments(summary: Record<string, number>): BandSegment[] {
    const total = Object.values(summary).reduce((sum, n) => sum + (n || 0), 0);
    if (total === 0) return [];
    return BAND_ORDER
      .filter(band => (summary[band] ?? 0) > 0)
      .map(band => ({
        band,
        count: summary[band] ?? 0,
        pct: ((summary[band] ?? 0) / total) * 100,
        color: this.BAND_COLORS[band] ?? '#9E9E9E',
      }));
  }

  // ── D7: Temporal helpers ────────────────────────────────────────────────────

  /** Convert 'yyyy-MM-dd' string to epoch millis at midnight UTC, or null if empty. */
  private dateStringToEpoch(dateStr: string): number | null {
    if (!dateStr) return null;
    const d = new Date(dateStr + 'T00:00:00Z');
    return isNaN(d.getTime()) ? null : d.getTime();
  }

  // ── Data loading ────────────────────────────────────────────────────────────

  loadFacts(): void {
    if (this.factSheetId == null) {
      return;
    }
    this.loading = true;
    this.error = null;

    const params: string[] = [];
    if (this.selectedTier) {
      params.push(`tier=${encodeURIComponent(this.selectedTier)}`);
    }
    const fromMs = this.dateStringToEpoch(this.validFromDate);
    const toMs   = this.dateStringToEpoch(this.validToDate);
    if (fromMs != null) {
      params.push(`validFrom=${fromMs}`);
    }
    if (toMs != null) {
      // End-of-day: add 86399999ms so "To: 2025-01-01" includes the whole day
      params.push(`validTo=${toMs + 86399999}`);
    }
    if (this.excludeUndated) {
      params.push('excludeUndated=true');
    }

    const qs  = params.length > 0 ? '?' + params.join('&') : '';
    const url = `${this.backendUrl}/kb-grounding/${this.factSheetId}/facts${qs}`;

    this.http.get<FactTierRow[]>(url).subscribe({
      next: (data) => {
        this.rows = [...data].sort((a, b) => b.confidence - a.confidence);
        this.loading = false;
      },
      error: (err) => {
        const msg = err?.error?.error || err?.message || 'Failed to load facts';
        this.error = msg;
        this.loading = false;
      },
    });
  }

  bandColor(band: string): string {
    return this.BAND_COLORS[band] ?? '#9E9E9E';
  }
}
