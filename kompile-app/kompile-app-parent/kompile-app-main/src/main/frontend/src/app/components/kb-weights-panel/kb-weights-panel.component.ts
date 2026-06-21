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
        <div *ngIf="!loadingWeights && !weightsError && weightRows.length === 0" class="empty-row">
          <mat-icon>info_outline</mat-icon>
          <span>No weights stored yet for program "{{ selectedProgram }}". Weights appear after the first online learning pass.</span>
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

  tuningHistory: AuditEvent[] = [];
  loadingHistory = false;

  constructor(private http: HttpClient) {
    super();
  }

  ngOnInit(): void {
    // derive a program id from the fact sheet id if available
    if (this.factSheetId != null) {
      this.selectedProgram = String(this.factSheetId);
    }
    this.loadWeights();
    if (this.factSheetId != null) {
      this.loadTuningHistory();
    }
  }

  ngOnChanges(changes: SimpleChanges): void {
    if (changes['factSheetId'] && !changes['factSheetId'].firstChange) {
      if (this.factSheetId != null) {
        this.selectedProgram = String(this.factSheetId);
        this.loadTuningHistory();
      }
      this.loadWeights();
    }
  }

  loadWeights(): void {
    this.loadingWeights = true;
    this.weightsError = null;

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
        if (status === 404 || status === 503) {
          this.weightRows = [];
          this.weightsError = null; // show "no weights yet" empty state
        } else {
          this.weightsError = err?.error?.message || err?.message || 'Failed to load weights';
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
}
