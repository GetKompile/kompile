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
import { MatInputModule } from '@angular/material/input';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatSelectModule } from '@angular/material/select';

import { BaseService } from '../../services/base.service';
import { OpinionDto, StrengthBand } from '../../services/kb-grounding.service';
import { StrengthBadgeComponent } from '../strength-badge/strength-badge.component';

interface FactOpinionRow {
  atomKey: string;
  confidence: number;
  band: string;
  promotionStatus: string;
  corroborationCount: number;
  belief: number | null;
  disbelief: number | null;
  uncertainty: number | null;
  expectation: number | null;
  baseRate: number | null;
}

interface TierChip {
  label: string;
  value: string | null;
  color: string;
}

const BAND_ORDER = ['ESTABLISHED', 'HIGH', 'PROBABLE', 'SPECULATIVE', 'SUPPRESSED'];

@Component({
  selector: 'app-opinion-browser',
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
    MatInputModule,
    MatFormFieldModule,
    MatSelectModule,
    StrengthBadgeComponent,
  ],
  template: `
    <mat-card class="opinion-browser-card">
      <mat-card-header>
        <mat-card-title>Facts &amp; Opinions</mat-card-title>
        <mat-card-subtitle>Full epistemic view per fact</mat-card-subtitle>
      </mat-card-header>

      <mat-card-content>
        <!-- Band summary bar -->
        <div class="band-summary" *ngIf="!loading && bandSummaryKeys.length > 0">
          <span *ngFor="let band of bandSummaryKeys" class="band-count"
                [style.color]="BAND_COLORS[band]">
            {{ band | titlecase }}: {{ bandSummary[band] }}
          </span>
        </div>

        <!-- Filter controls row -->
        <div class="filter-row">
          <!-- Tier chips -->
          <div class="tier-chips">
            <button
              *ngFor="let chip of tierChips"
              class="tier-chip"
              [class.active]="selectedTier === chip.value"
              [style.--chip-color]="chip.color"
              (click)="selectTier(chip.value)"
              [matTooltip]="chip.value ?? 'All tiers'">
              <span class="chip-dot" [style.background]="chip.color"></span>
              {{ chip.label }}
            </button>
          </div>

          <!-- Search box -->
          <mat-form-field appearance="outline" class="search-field">
            <mat-label>Search atom key...</mat-label>
            <input matInput [(ngModel)]="searchQuery" (ngModelChange)="search()">
          </mat-form-field>

          <!-- Max uncertainty -->
          <mat-form-field appearance="outline" class="filter-field">
            <mat-label>Max uncertainty</mat-label>
            <input matInput type="number" [(ngModel)]="maxUncertainty"
                   min="0" max="1" step="0.05"
                   (ngModelChange)="applyFilters()"
                   placeholder="e.g. 0.4">
          </mat-form-field>

          <!-- Min expectation -->
          <mat-form-field appearance="outline" class="filter-field">
            <mat-label>Min expectation</mat-label>
            <input matInput type="number" [(ngModel)]="minExpectation"
                   min="0" max="1" step="0.05"
                   (ngModelChange)="applyFilters()"
                   placeholder="e.g. 0.5">
          </mat-form-field>
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

        <!-- No fact sheet selected -->
        <div *ngIf="!loading && !error && factSheetId == null" class="empty-msg">
          <mat-icon>info_outline</mat-icon>
          <span>Select a fact sheet to browse facts and opinions.</span>
        </div>

        <!-- Empty results -->
        <div *ngIf="!loading && !error && rows.length === 0 && factSheetId != null" class="empty-msg">
          <mat-icon>info_outline</mat-icon>
          <span>No facts found{{ selectedTier ? ' for tier ' + selectedTier : '' }}.</span>
        </div>

        <!-- Results table -->
        <table mat-table [dataSource]="rows"
               *ngIf="!loading && !error && rows.length > 0"
               class="opinions-table">

          <!-- atomKey column -->
          <ng-container matColumnDef="atomKey">
            <th mat-header-cell *matHeaderCellDef>Atom Key</th>
            <td mat-cell *matCellDef="let row" class="atom-key-cell"
                [matTooltip]="row.atomKey">{{ row.atomKey }}</td>
          </ng-container>

          <!-- band column -->
          <ng-container matColumnDef="band">
            <th mat-header-cell *matHeaderCellDef>Band</th>
            <td mat-cell *matCellDef="let row">
              <app-strength-badge
                [band]="row.band"
                [opinion]="toOpinionDto(row)">
              </app-strength-badge>
            </td>
          </ng-container>

          <!-- confidence column -->
          <ng-container matColumnDef="confidence">
            <th mat-header-cell *matHeaderCellDef>Confidence</th>
            <td mat-cell *matCellDef="let row">{{ row.confidence | number:'1.3-3' }}</td>
          </ng-container>

          <!-- opinion bar column -->
          <ng-container matColumnDef="opinion">
            <th mat-header-cell *matHeaderCellDef>Opinion (b/d/u)</th>
            <td mat-cell *matCellDef="let row">
              <ng-container *ngIf="row.belief != null && row.disbelief != null && row.uncertainty != null; else noOpinion">
                <div class="opinion-bar"
                     [matTooltip]="'b=' + (row.belief | number:'1.2-2') + ' d=' + (row.disbelief | number:'1.2-2') + ' u=' + (row.uncertainty | number:'1.2-2')">
                  <span class="opinion-bar-b"
                        [style.width.%]="(row.belief * 100)"></span>
                  <span class="opinion-bar-d"
                        [style.width.%]="(row.disbelief * 100)"></span>
                  <span class="opinion-bar-u"
                        [style.width.%]="(row.uncertainty * 100)"></span>
                </div>
              </ng-container>
              <ng-template #noOpinion>
                <span class="no-opinion">—</span>
              </ng-template>
            </td>
          </ng-container>

          <!-- expectation column -->
          <ng-container matColumnDef="expectation">
            <th mat-header-cell *matHeaderCellDef>Expectation</th>
            <td mat-cell *matCellDef="let row">
              {{ row.expectation != null ? (row.expectation | number:'1.3-3') : '—' }}
            </td>
          </ng-container>

          <!-- uncertainty column -->
          <ng-container matColumnDef="uncertainty">
            <th mat-header-cell *matHeaderCellDef>Uncertainty</th>
            <td mat-cell *matCellDef="let row">
              {{ row.uncertainty != null ? (row.uncertainty | number:'1.3-3') : '—' }}
            </td>
          </ng-container>

          <!-- corroborations column -->
          <ng-container matColumnDef="corroborations">
            <th mat-header-cell *matHeaderCellDef>Corroborations</th>
            <td mat-cell *matCellDef="let row">{{ row.corroborationCount }}</td>
          </ng-container>

          <tr mat-header-row *matHeaderRowDef="displayedColumns"></tr>
          <tr mat-row *matRowDef="let row; columns: displayedColumns;"></tr>
        </table>

        <div *ngIf="!loading && !error && rows.length >= 500" class="cap-notice">
          Results capped at 500. Refine filters to see more.
        </div>
      </mat-card-content>
    </mat-card>
  `,
  styles: [`
    .opinion-browser-card {
      display: flex;
      flex-direction: column;
      height: 100%;
      background: var(--bg-surface);
      color: var(--text-primary);
      border: 1px solid var(--border-color);
      box-shadow: var(--shadow-sm);
    }

    .band-summary {
      display: flex;
      flex-direction: row;
      gap: 16px;
      padding: 8px 16px;
      font-size: 12px;
      flex-wrap: wrap;
    }

    .band-count {
      font-weight: 600;
    }

    .filter-row {
      display: flex;
      flex-direction: row;
      align-items: flex-end;
      gap: 12px;
      padding: 0 16px 8px;
      flex-wrap: wrap;
    }

    .tier-chips {
      display: flex;
      flex-direction: row;
      gap: 6px;
      flex-wrap: wrap;
      align-items: center;
    }

    .tier-chip {
      display: inline-flex;
      align-items: center;
      gap: 4px;
      padding: 4px 10px;
      border-radius: 16px;
      border: 1px solid var(--border-color);
      background: var(--bg-body);
      color: var(--text-secondary);
      font-size: 12px;
      cursor: pointer;
      transition: background 0.15s, color 0.15s;
    }

    .tier-chip.active {
      background: var(--chip-color, #1976d2);
      color: #fff;
      border-color: var(--chip-color, #1976d2);
    }

    .chip-dot {
      width: 8px;
      height: 8px;
      border-radius: 50%;
      display: inline-block;
    }

    .search-field {
      min-width: 200px;
    }

    .filter-field {
      width: 140px;
    }

    .spinner-row {
      display: flex;
      justify-content: center;
      padding: 24px;
    }

    .error-msg,
    .empty-msg {
      display: flex;
      align-items: center;
      gap: 8px;
      padding: 16px;
      color: var(--text-secondary);
      font-size: 14px;
    }

    .opinions-table {
      width: 100%;
      background: transparent;
    }

    .atom-key-cell {
      max-width: 260px;
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
      font-family: monospace;
      font-size: 12px;
      color: var(--text-primary);
    }

    .opinion-bar {
      display: flex;
      height: 8px;
      border-radius: 4px;
      overflow: hidden;
      width: 80px;
      background: var(--bg-body);
      border: 1px solid var(--border-color);
    }

    .opinion-bar-b {
      display: inline-block;
      height: 100%;
      background: #4CAF50;
    }

    .opinion-bar-d {
      display: inline-block;
      height: 100%;
      background: #F44336;
    }

    .opinion-bar-u {
      display: inline-block;
      height: 100%;
      background: #9E9E9E;
    }

    .no-opinion {
      color: var(--text-tertiary);
    }

    .cap-notice {
      padding: 8px 16px;
      font-size: 12px;
      color: var(--text-tertiary);
    }

    mat-header-cell {
      color: var(--text-secondary);
      font-size: 12px;
      font-weight: 600;
    }

    mat-cell {
      color: var(--text-primary);
      font-size: 13px;
    }
  `]
})
export class OpinionBrowserComponent extends BaseService implements OnInit, OnChanges {
  @Input() factSheetId: number | null = null;

  rows: FactOpinionRow[] = [];
  bandSummary: Record<string, number> = {};
  loading = false;
  error: string | null = null;
  selectedTier: string | null = null;
  searchQuery = '';
  maxUncertainty: number | null = null;
  minExpectation: number | null = null;

  displayedColumns = ['atomKey', 'band', 'confidence', 'opinion', 'expectation', 'uncertainty', 'corroborations'];

  readonly tierChips: TierChip[] = [
    { label: 'All',         value: null,          color: 'var(--text-tertiary)' },
    { label: 'Established', value: 'ESTABLISHED',  color: '#4CAF50' },
    { label: 'High',        value: 'HIGH',         color: '#8BC34A' },
    { label: 'Probable',    value: 'PROBABLE',     color: '#FFC107' },
    { label: 'Speculative', value: 'SPECULATIVE',  color: '#FF9800' },
    { label: 'Suppressed',  value: 'SUPPRESSED',   color: '#F44336' },
  ];

  readonly BAND_COLORS: Record<string, string> = {
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
      this.loadData();
    }
  }

  ngOnChanges(changes: SimpleChanges): void {
    if (changes['factSheetId'] && !changes['factSheetId'].firstChange) {
      this.rows = [];
      this.bandSummary = {};
      this.error = null;
      if (this.factSheetId != null) {
        this.loadData();
      }
    }
  }

  selectTier(value: string | null): void {
    this.selectedTier = value;
    if (this.factSheetId != null) {
      this.loadData();
    }
  }

  search(): void {
    if (this.factSheetId != null) {
      this.loadData();
    }
  }

  applyFilters(): void {
    if (this.factSheetId != null) {
      this.loadData();
    }
  }

  loadData(): void {
    if (this.factSheetId == null) {
      return;
    }
    this.loading = true;
    this.error = null;

    const params: string[] = [];
    if (this.selectedTier) {
      params.push(`tier=${encodeURIComponent(this.selectedTier)}`);
    }
    if (this.searchQuery && this.searchQuery.trim()) {
      params.push(`q=${encodeURIComponent(this.searchQuery.trim())}`);
    }
    if (this.maxUncertainty != null) {
      params.push(`maxUncertainty=${encodeURIComponent(String(this.maxUncertainty))}`);
    }
    if (this.minExpectation != null) {
      params.push(`minExpectation=${encodeURIComponent(String(this.minExpectation))}`);
    }
    params.push('limit=500');

    const qs = params.length > 0 ? '?' + params.join('&') : '';
    const opinionsUrl = `${this.backendUrl}/kb-grounding/${this.factSheetId}/opinions${qs}`;
    const summaryUrl  = `${this.backendUrl}/kb-grounding/${this.factSheetId}/band-summary`;

    // Load opinions
    this.http.get<FactOpinionRow[]>(opinionsUrl).subscribe({
      next: (data) => {
        this.rows = [...data].sort((a, b) => b.confidence - a.confidence);
        this.loading = false;
      },
      error: (err) => {
        const msg = err?.error?.error || err?.message || 'Failed to load opinions';
        this.error = msg;
        this.loading = false;
      },
    });

    // Load band summary independently (non-blocking)
    this.http.get<Record<string, number>>(summaryUrl).subscribe({
      next: (data) => {
        this.bandSummary = data;
      },
      error: () => {
        // Band summary is decorative — swallow the error
        this.bandSummary = {};
      },
    });
  }

  toOpinionDto(row: FactOpinionRow): OpinionDto | undefined {
    if (
      row.belief != null &&
      row.disbelief != null &&
      row.uncertainty != null &&
      row.expectation != null
    ) {
      return {
        belief: row.belief,
        disbelief: row.disbelief,
        uncertainty: row.uncertainty,
        expectation: row.expectation,
      };
    }
    return undefined;
  }

  bandColor(band: string): string {
    return this.BAND_COLORS[band] ?? '#9E9E9E';
  }

  get bandSummaryKeys(): string[] {
    const keys = Object.keys(this.bandSummary);
    return keys.sort((a, b) => {
      const ai = BAND_ORDER.indexOf(a);
      const bi = BAND_ORDER.indexOf(b);
      const aIdx = ai === -1 ? 999 : ai;
      const bIdx = bi === -1 ? 999 : bi;
      return aIdx - bIdx;
    });
  }
}
