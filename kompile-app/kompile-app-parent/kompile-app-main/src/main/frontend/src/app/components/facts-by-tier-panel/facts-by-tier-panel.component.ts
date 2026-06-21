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
}

interface TierChip {
  label: string;
  value: string | null;
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

          <tr mat-header-row *matHeaderRowDef="displayedColumns"></tr>
          <tr mat-row *matRowDef="let row; columns: displayedColumns;"></tr>
        </table>

        <div *ngIf="!loading && !error && rows.length >= 500" class="cap-notice">
          Results capped at 500. Refine by tier to see more.
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

  displayedColumns = ['atomKey', 'band', 'confidence', 'promotionStatus', 'corroborationCount'];

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
      this.loadFacts();
    }
  }

  ngOnChanges(changes: SimpleChanges): void {
    if (changes['factSheetId'] && !changes['factSheetId'].firstChange) {
      this.rows = [];
      this.error = null;
      if (this.factSheetId != null) {
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

  loadFacts(): void {
    if (this.factSheetId == null) {
      return;
    }
    this.loading = true;
    this.error = null;

    const tierParam = this.selectedTier ? `tier=${encodeURIComponent(this.selectedTier)}` : '';
    const url = `${this.backendUrl}/kb-grounding/${this.factSheetId}/facts${tierParam ? '?' + tierParam : ''}`;

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
