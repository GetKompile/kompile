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

// ── D4: BasisType definitions ─────────────────────────────────────────────────

export type BasisTypeValue =
  | 'STRUCTURAL'
  | 'LLM_EXTRACTION'
  | 'PSL_INFERENCE'
  | 'MEBN_INFERENCE'
  | 'CORROBORATION'
  | 'ASSERTED';

interface BasisTypeChip {
  label: string;
  value: BasisTypeValue | null;
  color: string;
  abbrev: string;
}

const BASIS_TYPE_META: Record<BasisTypeValue, { color: string; abbrev: string }> = {
  STRUCTURAL:     { color: '#2196F3', abbrev: 'STRUCT' },
  LLM_EXTRACTION: { color: '#9C27B0', abbrev: 'LLM'    },
  PSL_INFERENCE:  { color: '#00BCD4', abbrev: 'PSL'    },
  MEBN_INFERENCE: { color: '#FF9800', abbrev: 'MEBN'   },
  CORROBORATION:  { color: '#4CAF50', abbrev: 'CORR'   },
  ASSERTED:       { color: '#F44336', abbrev: 'ASSERT' },
};

// ── Response DTO ─────────────────────────────────────────────────────────────

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
  /** D4: basisType sourced from _basisType in provenanceJson */
  basisType: string | null;
}

// ── Tier chips ────────────────────────────────────────────────────────────────

interface TierChip {
  label: string;
  value: string | null;
  color: string;
}

const BAND_ORDER = ['ESTABLISHED', 'HIGH', 'PROBABLE', 'SPECULATIVE', 'SUPPRESSED'];

/** D2: If this fraction or more of loaded facts are SPECULATIVE, show the cold-start banner. */
export const SPECULATIVE_SATURATION_THRESHOLD = 0.8;

// ── Simplex geometry helpers (D3) ─────────────────────────────────────────────

/**
 * Project a (belief, disbelief, uncertainty) triplet onto barycentric coordinates
 * inside an equilateral triangle with the given vertex screen positions.
 *
 * Vertices:
 *   T (certain-true)  = top apex
 *   F (certain-false) = bottom-left
 *   V (vacuous)       = bottom-right
 *
 * The barycentric weights are (b, d, u) which already sum to ≤ 1. When b+d+u < 1
 * we normalise so the point is always inside the triangle.
 */
function simplexPoint(
  b: number, d: number, u: number,
  T: { x: number; y: number },
  F: { x: number; y: number },
  V: { x: number; y: number }
): { x: number; y: number } {
  const total = b + d + u || 1;
  const nb = b / total;
  const nd = d / total;
  const nu = u / total;
  return {
    x: nb * T.x + nd * F.x + nu * V.x,
    y: nb * T.y + nd * F.y + nu * V.y,
  };
}

// ── Component ─────────────────────────────────────────────────────────────────

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
        <!-- D2: SPECULATIVE-saturation cold-start banner -->
        <div *ngIf="!loading && isSpeculativeSaturated" class="cold-start-banner">
          <mat-icon class="cold-start-icon">info</mat-icon>
          <span>
            All facts are <strong>SPECULATIVE</strong> — expected after one crawl.
            Confidence climbs as additional crawls corroborate facts toward ESTABLISHED.
          </span>
        </div>

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

          <!-- D5: Corpus-level search box (filters on fact text / atom key client-side) -->
          <mat-form-field appearance="outline" class="search-field">
            <mat-label>Search facts, subjects, predicates…</mat-label>
            <input matInput [(ngModel)]="searchQuery" (ngModelChange)="applyClientFilters()">
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

        <!-- D4: BasisType multi-select filter chips -->
        <div class="basis-type-filter-row">
          <span class="basis-label">Basis:</span>
          <button
            *ngFor="let chip of basisTypeChips"
            class="basis-chip"
            [class.active]="selectedBasisTypes.has(chip.value!)"
            [class.all-chip]="chip.value === null"
            [class.all-active]="chip.value === null && selectedBasisTypes.size === 0"
            [style.--basis-color]="chip.color"
            (click)="toggleBasisType(chip.value)"
            [matTooltip]="chip.value ?? 'All basis types'">
            <span class="chip-dot" [style.background]="chip.color"></span>
            {{ chip.label }}
          </button>
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
        <div *ngIf="!loading && !error && filteredRows.length === 0 && factSheetId != null" class="empty-msg">
          <mat-icon>info_outline</mat-icon>
          <span>No facts found{{ selectedTier ? ' for tier ' + selectedTier : '' }}.</span>
        </div>

        <!-- Results table -->
        <table mat-table [dataSource]="filteredRows"
               *ngIf="!loading && !error && filteredRows.length > 0"
               class="opinions-table">

          <!-- Expand toggle column -->
          <ng-container matColumnDef="expand">
            <th mat-header-cell *matHeaderCellDef></th>
            <td mat-cell *matCellDef="let row">
              <button class="expand-btn"
                      (click)="toggleExpand(row); $event.stopPropagation()"
                      [matTooltip]="expandedRow === row ? 'Collapse' : 'Expand opinion detail'">
                <mat-icon>{{ expandedRow === row ? 'expand_less' : 'expand_more' }}</mat-icon>
              </button>
            </td>
          </ng-container>

          <!-- atomKey column -->
          <ng-container matColumnDef="atomKey">
            <th mat-header-cell *matHeaderCellDef>Atom Key</th>
            <td mat-cell *matCellDef="let row" class="atom-key-cell"
                [matTooltip]="row.atomKey">{{ row.atomKey }}</td>
          </ng-container>

          <!-- D4: basisType column -->
          <ng-container matColumnDef="basisType">
            <th mat-header-cell *matHeaderCellDef>Basis</th>
            <td mat-cell *matCellDef="let row">
              <span class="basis-badge"
                    [style.background]="basisTypeColor(row.basisType)"
                    [matTooltip]="row.basisType ?? 'unknown'">
                {{ basisTypeAbbrev(row.basisType) }}
              </span>
            </td>
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

          <!-- D3: Expandable detail row column -->
          <ng-container matColumnDef="expandedDetail">
            <td mat-cell *matCellDef="let row" [attr.colspan]="displayedColumns.length"
                class="expanded-cell">
              <div class="expanded-panel" *ngIf="expandedRow === row">
                <ng-container *ngIf="row.belief != null && row.disbelief != null && row.uncertainty != null; else noSimplex">
                  <!-- D3: Barycentric simplex triangle SVG -->
                  <div class="simplex-container">
                    <svg [attr.width]="SIMPLEX_SIZE" [attr.height]="SIMPLEX_SIZE"
                         class="simplex-svg"
                         [attr.viewBox]="'0 0 ' + SIMPLEX_SIZE + ' ' + SIMPLEX_SIZE">
                      <!-- Triangle outline -->
                      <polygon
                        [attr.points]="simplexTrianglePoints()"
                        fill="none"
                        stroke="var(--border-color)"
                        stroke-width="1.5"/>

                      <!-- Vertex labels -->
                      <text [attr.x]="SIMPLEX_T.x" [attr.y]="SIMPLEX_T.y - 8"
                            text-anchor="middle" class="simplex-label">certain-true</text>
                      <text [attr.x]="SIMPLEX_F.x - 4" [attr.y]="SIMPLEX_F.y + 14"
                            text-anchor="end" class="simplex-label">certain-false</text>
                      <text [attr.x]="SIMPLEX_V.x + 4" [attr.y]="SIMPLEX_V.y + 14"
                            text-anchor="start" class="simplex-label">vacuous</text>

                      <!-- Opinion point -->
                      <circle
                        [attr.cx]="simplexOpinionPoint(row).x"
                        [attr.cy]="simplexOpinionPoint(row).y"
                        r="5"
                        fill="#1976d2"
                        stroke="#fff"
                        stroke-width="1.5">
                        <title>b={{ (row.belief | number:'1.3-3') }} d={{ (row.disbelief | number:'1.3-3') }} u={{ (row.uncertainty | number:'1.3-3') }}</title>
                      </circle>

                      <!-- Dotted lines from point to triangle edges (guide lines) -->
                      <line
                        [attr.x1]="simplexOpinionPoint(row).x"
                        [attr.y1]="simplexOpinionPoint(row).y"
                        [attr.x2]="SIMPLEX_T.x"
                        [attr.y2]="SIMPLEX_T.y"
                        stroke="#4CAF50" stroke-width="0.8" stroke-dasharray="3,3" opacity="0.5"/>
                      <line
                        [attr.x1]="simplexOpinionPoint(row).x"
                        [attr.y1]="simplexOpinionPoint(row).y"
                        [attr.x2]="SIMPLEX_F.x"
                        [attr.y2]="SIMPLEX_F.y"
                        stroke="#F44336" stroke-width="0.8" stroke-dasharray="3,3" opacity="0.5"/>
                      <line
                        [attr.x1]="simplexOpinionPoint(row).x"
                        [attr.y1]="simplexOpinionPoint(row).y"
                        [attr.x2]="SIMPLEX_V.x"
                        [attr.y2]="SIMPLEX_V.y"
                        stroke="#9E9E9E" stroke-width="0.8" stroke-dasharray="3,3" opacity="0.5"/>
                    </svg>

                    <!-- Numeric b/d/u/expectation breakdown -->
                    <div class="simplex-stats">
                      <div class="simplex-stat">
                        <span class="stat-dot" style="background:#4CAF50"></span>
                        <span class="stat-label">Belief</span>
                        <span class="stat-value">{{ row.belief | number:'1.4-4' }}</span>
                      </div>
                      <div class="simplex-stat">
                        <span class="stat-dot" style="background:#F44336"></span>
                        <span class="stat-label">Disbelief</span>
                        <span class="stat-value">{{ row.disbelief | number:'1.4-4' }}</span>
                      </div>
                      <div class="simplex-stat">
                        <span class="stat-dot" style="background:#9E9E9E"></span>
                        <span class="stat-label">Uncertainty</span>
                        <span class="stat-value">{{ row.uncertainty | number:'1.4-4' }}</span>
                      </div>
                      <div class="simplex-stat">
                        <span class="stat-dot" style="background:#1976d2"></span>
                        <span class="stat-label">Expectation</span>
                        <span class="stat-value">{{ row.expectation != null ? (row.expectation | number:'1.4-4') : '—' }}</span>
                      </div>
                      <div class="simplex-stat" *ngIf="row.baseRate != null">
                        <span class="stat-dot" style="background:var(--text-tertiary)"></span>
                        <span class="stat-label">Base Rate</span>
                        <span class="stat-value">{{ row.baseRate | number:'1.4-4' }}</span>
                      </div>
                      <div class="simplex-stat">
                        <span class="stat-dot"
                              [style.background]="basisTypeColor(row.basisType)"></span>
                        <span class="stat-label">Basis</span>
                        <span class="stat-value">{{ row.basisType ?? '—' }}</span>
                      </div>
                    </div>
                  </div>
                </ng-container>
                <ng-template #noSimplex>
                  <span class="no-opinion" style="padding:8px 0;display:block;">
                    No full opinion available for this fact.
                  </span>
                </ng-template>
              </div>
            </td>
          </ng-container>

          <tr mat-header-row *matHeaderRowDef="displayedColumns"></tr>
          <tr mat-row *matRowDef="let row; columns: displayedColumns;"
              [class.expanded-row]="expandedRow === row"
              (click)="toggleExpand(row)"></tr>
          <!-- D3: detail expansion row -->
          <tr mat-row *matRowDef="let row; columns: ['expandedDetail']"
              class="detail-row"></tr>
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

    /* D2: cold-start / SPECULATIVE-saturation banner */
    .cold-start-banner {
      display: flex;
      align-items: flex-start;
      gap: 8px;
      margin: 8px 16px;
      padding: 10px 14px;
      border-radius: 6px;
      background: rgba(255, 152, 0, 0.10);
      border: 1px solid rgba(255, 152, 0, 0.35);
      font-size: 13px;
      color: var(--text-primary);
      line-height: 1.45;
    }

    .cold-start-icon {
      font-size: 18px;
      width: 18px;
      height: 18px;
      color: #FF9800;
      flex-shrink: 0;
      margin-top: 1px;
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

    /* D4: BasisType filter row */
    .basis-type-filter-row {
      display: flex;
      flex-direction: row;
      align-items: center;
      gap: 6px;
      padding: 0 16px 12px;
      flex-wrap: wrap;
    }

    .basis-label {
      font-size: 12px;
      color: var(--text-secondary);
      font-weight: 600;
      white-space: nowrap;
    }

    .basis-chip {
      display: inline-flex;
      align-items: center;
      gap: 4px;
      padding: 3px 8px;
      border-radius: 12px;
      border: 1px solid var(--border-color);
      background: var(--bg-body);
      color: var(--text-secondary);
      font-size: 11px;
      cursor: pointer;
      transition: background 0.15s, color 0.15s;
    }

    .basis-chip.active {
      background: var(--basis-color, #9C27B0);
      color: #fff;
      border-color: var(--basis-color, #9C27B0);
    }

    .basis-chip.all-chip {
      border-color: var(--text-tertiary);
    }

    .basis-chip.all-active {
      background: var(--text-tertiary);
      color: #fff;
    }

    /* D4: basisType column badge */
    .basis-badge {
      display: inline-block;
      padding: 2px 6px;
      border-radius: 8px;
      color: #fff;
      font-size: 10px;
      font-weight: 700;
      letter-spacing: 0.3px;
      text-transform: uppercase;
    }

    .search-field {
      min-width: 220px;
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
      max-width: 220px;
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

    /* Expand button */
    .expand-btn {
      background: none;
      border: none;
      cursor: pointer;
      padding: 0;
      color: var(--text-secondary);
      display: flex;
      align-items: center;
    }

    .expand-btn mat-icon {
      font-size: 18px;
      width: 18px;
      height: 18px;
    }

    /* Clickable data rows */
    tr.mat-mdc-row {
      cursor: pointer;
    }

    tr.expanded-row {
      background: var(--bg-body);
    }

    /* D3: expanded detail row */
    tr.detail-row {
      height: 0;
    }

    .expanded-cell {
      padding: 0 !important;
      border-bottom: none;
    }

    .expanded-panel {
      overflow: hidden;
      padding: 8px 24px 16px;
      background: var(--bg-body);
      border-bottom: 1px solid var(--border-color);
    }

    /* D3: Simplex layout */
    .simplex-container {
      display: flex;
      flex-direction: row;
      gap: 24px;
      align-items: flex-start;
    }

    .simplex-svg {
      flex-shrink: 0;
      border: 1px solid var(--border-color);
      border-radius: 4px;
      background: var(--bg-surface);
    }

    .simplex-label {
      font-size: 9px;
      fill: var(--text-secondary);
      font-family: sans-serif;
    }

    .simplex-stats {
      display: flex;
      flex-direction: column;
      gap: 6px;
      padding-top: 4px;
    }

    .simplex-stat {
      display: flex;
      align-items: center;
      gap: 8px;
      font-size: 13px;
    }

    .stat-dot {
      width: 10px;
      height: 10px;
      border-radius: 50%;
      flex-shrink: 0;
    }

    .stat-label {
      color: var(--text-secondary);
      min-width: 90px;
    }

    .stat-value {
      font-family: monospace;
      color: var(--text-primary);
      font-weight: 600;
    }
  `]
})
export class OpinionBrowserComponent extends BaseService implements OnInit, OnChanges {
  @Input() factSheetId: number | null = null;

  /** Raw rows returned from the server (un-filtered by client-side search). */
  rows: FactOpinionRow[] = [];
  /** D5: client-filtered rows shown in the table. */
  filteredRows: FactOpinionRow[] = [];

  bandSummary: Record<string, number> = {};
  loading = false;
  error: string | null = null;
  selectedTier: string | null = null;
  searchQuery = '';
  maxUncertainty: number | null = null;
  minExpectation: number | null = null;

  /** D4: multi-select set of active basisType values (empty = all). */
  selectedBasisTypes: Set<BasisTypeValue> = new Set();

  /** D3: which row is currently expanded to show the simplex inspector. */
  expandedRow: FactOpinionRow | null = null;

  displayedColumns = [
    'expand', 'atomKey', 'basisType', 'band', 'confidence',
    'opinion', 'expectation', 'uncertainty', 'corroborations'
  ];

  // D3: simplex geometry constants
  readonly SIMPLEX_SIZE = 160;
  readonly SIMPLEX_T = { x: 80, y: 16 };   // certain-true  (top)
  readonly SIMPLEX_F = { x: 12, y: 148 };  // certain-false (bottom-left)
  readonly SIMPLEX_V = { x: 148, y: 148 }; // vacuous       (bottom-right)

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

  // D4: BasisType filter chips
  readonly basisTypeChips: BasisTypeChip[] = [
    { label: 'All',       value: null,              color: 'var(--text-tertiary)', abbrev: 'ALL' },
    { label: 'Structural', value: 'STRUCTURAL',     color: BASIS_TYPE_META.STRUCTURAL.color,     abbrev: BASIS_TYPE_META.STRUCTURAL.abbrev     },
    { label: 'LLM',        value: 'LLM_EXTRACTION', color: BASIS_TYPE_META.LLM_EXTRACTION.color, abbrev: BASIS_TYPE_META.LLM_EXTRACTION.abbrev },
    { label: 'PSL',        value: 'PSL_INFERENCE',  color: BASIS_TYPE_META.PSL_INFERENCE.color,  abbrev: BASIS_TYPE_META.PSL_INFERENCE.abbrev  },
    { label: 'MEBN',       value: 'MEBN_INFERENCE', color: BASIS_TYPE_META.MEBN_INFERENCE.color, abbrev: BASIS_TYPE_META.MEBN_INFERENCE.abbrev },
    { label: 'Corr',       value: 'CORROBORATION',  color: BASIS_TYPE_META.CORROBORATION.color,  abbrev: BASIS_TYPE_META.CORROBORATION.abbrev  },
    { label: 'Asserted',   value: 'ASSERTED',       color: BASIS_TYPE_META.ASSERTED.color,       abbrev: BASIS_TYPE_META.ASSERTED.abbrev       },
  ];

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
      this.filteredRows = [];
      this.bandSummary = {};
      this.error = null;
      this.expandedRow = null;
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

  /** D5: client-side search — called on every keystroke; no network request. */
  applyClientFilters(): void {
    this.filteredRows = this.clientFilter(this.rows);
  }

  applyFilters(): void {
    if (this.factSheetId != null) {
      this.loadData();
    }
  }

  /** D4: toggle one basisType in the multi-select set. null = "All" = clear set. */
  toggleBasisType(value: BasisTypeValue | null): void {
    if (value === null) {
      this.selectedBasisTypes.clear();
    } else {
      if (this.selectedBasisTypes.has(value)) {
        this.selectedBasisTypes.delete(value);
      } else {
        this.selectedBasisTypes.add(value);
      }
    }
    // BasisType filter is client-side (already loaded) — no network request needed
    this.filteredRows = this.clientFilter(this.rows);
  }

  /** D3: toggle expanded row for the simplex inspector. */
  toggleExpand(row: FactOpinionRow): void {
    this.expandedRow = this.expandedRow === row ? null : row;
  }

  loadData(): void {
    if (this.factSheetId == null) {
      return;
    }
    this.loading = true;
    this.error = null;
    this.expandedRow = null;

    const params: string[] = [];
    if (this.selectedTier) {
      params.push(`tier=${encodeURIComponent(this.selectedTier)}`);
    }
    // D5: pass the search query to the server for atomKey filtering; additionally
    // the client re-filters after load for instant response
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
        // Apply client-side filters (basisType multi-select + D5 text filter)
        this.filteredRows = this.clientFilter(this.rows);
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

  /**
   * Apply all purely client-side filters (D4 basisType, D5 text search) to a row set.
   * Server already handled tier / maxUncertainty / minExpectation.
   */
  clientFilter(source: FactOpinionRow[]): FactOpinionRow[] {
    const qLower = this.searchQuery.trim().toLowerCase();
    return source.filter(row => {
      // D5: corpus-level text search on the full atomKey string (covers subject, predicate, args)
      if (qLower && !row.atomKey.toLowerCase().includes(qLower)) {
        return false;
      }
      // D4: basisType multi-select filter
      if (this.selectedBasisTypes.size > 0) {
        const bt = (row.basisType ?? 'LLM_EXTRACTION') as BasisTypeValue;
        if (!this.selectedBasisTypes.has(bt)) {
          return false;
        }
      }
      return true;
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

  // D4: basisType badge helpers
  basisTypeColor(bt: string | null): string {
    const meta = BASIS_TYPE_META[bt as BasisTypeValue];
    return meta?.color ?? '#9E9E9E';
  }

  basisTypeAbbrev(bt: string | null): string {
    const meta = BASIS_TYPE_META[bt as BasisTypeValue];
    return meta?.abbrev ?? (bt ? bt.substring(0, 6) : '?');
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

  /**
   * D2: True when ≥ SPECULATIVE_SATURATION_THRESHOLD of loaded rows are in the SPECULATIVE band.
   * Uses bandSummary counts (which include ALL rows, not just the filtered set) for accuracy.
   * Falls back to scanning rows when bandSummary is empty (first load).
   */
  get isSpeculativeSaturated(): boolean {
    const total = Object.values(this.bandSummary).reduce((s, n) => s + (n || 0), 0);
    if (total > 0) {
      const specCount = this.bandSummary['SPECULATIVE'] || 0;
      return specCount / total >= SPECULATIVE_SATURATION_THRESHOLD;
    }
    // Fallback: scan the loaded row set
    if (this.rows.length === 0) return false;
    const specRows = this.rows.filter(r => r.band === 'SPECULATIVE').length;
    return specRows / this.rows.length >= SPECULATIVE_SATURATION_THRESHOLD;
  }

  // D3: simplex geometry helpers used in the template

  simplexTrianglePoints(): string {
    const T = this.SIMPLEX_T;
    const F = this.SIMPLEX_F;
    const V = this.SIMPLEX_V;
    return `${T.x},${T.y} ${F.x},${F.y} ${V.x},${V.y}`;
  }

  simplexOpinionPoint(row: FactOpinionRow): { x: number; y: number } {
    const b = row.belief ?? 0;
    const d = row.disbelief ?? 0;
    const u = row.uncertainty ?? 0;
    return simplexPoint(b, d, u, this.SIMPLEX_T, this.SIMPLEX_F, this.SIMPLEX_V);
  }
}
