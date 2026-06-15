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
import { MatTableModule } from '@angular/material/table';
import { MatPaginatorModule, PageEvent } from '@angular/material/paginator';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatSelectModule } from '@angular/material/select';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatChipsModule } from '@angular/material/chips';
import { MatCardModule } from '@angular/material/card';
import { MatTooltipModule } from '@angular/material/tooltip';
import { MatExpansionModule } from '@angular/material/expansion';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { EnrichmentService } from '../../services/enrichment.service';
import { EnrichmentAuditEntry, AuditSummary, RevertResult } from '../../models/api-models';

@Component({
  selector: 'app-enrichment-audit-log',
  standalone: true,
  imports: [
    CommonModule, FormsModule,
    MatTableModule, MatPaginatorModule, MatButtonModule, MatIconModule,
    MatSelectModule, MatFormFieldModule, MatChipsModule, MatCardModule,
    MatTooltipModule, MatExpansionModule, MatSnackBarModule, MatProgressSpinnerModule
  ],
  template: `
    <!-- Filters -->
    <div class="filter-bar">
      <mat-form-field class="filter-field">
        <mat-label>Phase</mat-label>
        <mat-select [(ngModel)]="phaseFilter" (selectionChange)="loadAuditLog()">
          <mat-option [value]="''">All Phases</mat-option>
          <mat-option value="CLEAN">CLEAN</mat-option>
          <mat-option value="ORGANIZE">ORGANIZE</mat-option>
          <mat-option value="TAXONOMY">TAXONOMY</mat-option>
        </mat-select>
      </mat-form-field>
      <span class="spacer"></span>
      <button mat-stroked-button (click)="revertAllPhase()" *ngIf="phaseFilter && entries.length > 0" [disabled]="reverting">
        <mat-icon>undo</mat-icon> Revert Phase
      </button>
    </div>

    <!-- Summary -->
    <div class="summary-bar" *ngIf="summary">
      <span>{{ summary.totalActions }} actions</span>
      <span *ngFor="let action of summary.phases" class="phase-chip">{{ action }}</span>
      <span *ngIf="(summary.revertedCount ?? 0) > 0" class="reverted-count">{{ summary.revertedCount }} reverted</span>
    </div>

    <mat-spinner *ngIf="loading" diameter="32" class="centered"></mat-spinner>

    <!-- Audit Table -->
    <table mat-table [dataSource]="entries" *ngIf="entries.length > 0 && !loading" class="audit-table">
      <ng-container matColumnDef="createdAt">
        <th mat-header-cell *matHeaderCellDef>Time</th>
        <td mat-cell *matCellDef="let e">{{ e.createdAt | date:'HH:mm:ss' }}</td>
      </ng-container>
      <ng-container matColumnDef="action">
        <th mat-header-cell *matHeaderCellDef>Action</th>
        <td mat-cell *matCellDef="let e">
          <span class="action-chip">{{ e.action }}</span>
        </td>
      </ng-container>
      <ng-container matColumnDef="description">
        <th mat-header-cell *matHeaderCellDef>Description</th>
        <td mat-cell *matCellDef="let e">{{ e.description }}</td>
      </ng-container>
      <ng-container matColumnDef="targetType">
        <th mat-header-cell *matHeaderCellDef>Target</th>
        <td mat-cell *matCellDef="let e">{{ e.targetType }}</td>
      </ng-container>
      <ng-container matColumnDef="actions">
        <th mat-header-cell *matHeaderCellDef>Actions</th>
        <td mat-cell *matCellDef="let e">
          <button mat-icon-button (click)="expandedEntry = expandedEntry === e ? null : e" matTooltip="View details">
            <mat-icon>visibility</mat-icon>
          </button>
          <button mat-icon-button (click)="revertAction(e)" [disabled]="e.reverted || reverting" matTooltip="Revert">
            <mat-icon [class.reverted]="e.reverted">{{ e.reverted ? 'check' : 'undo' }}</mat-icon>
          </button>
        </td>
      </ng-container>
      <tr mat-header-row *matHeaderRowDef="displayColumns"></tr>
      <tr mat-row *matRowDef="let row; columns: displayColumns;" [class.reverted-row]="row.reverted"></tr>
    </table>

    <mat-paginator *ngIf="totalElements > pageSize"
      [length]="totalElements" [pageSize]="pageSize" [pageIndex]="pageIndex"
      (page)="onPage($event)">
    </mat-paginator>

    <!-- Expanded Detail -->
    <mat-card *ngIf="expandedEntry" class="detail-card">
      <mat-card-header>
        <mat-card-title>{{ expandedEntry.action }} - {{ expandedEntry.description }}</mat-card-title>
        <mat-card-subtitle>{{ expandedEntry.createdAt | date:'medium' }} | Target: {{ expandedEntry.targetNodeId || 'N/A' }}</mat-card-subtitle>
      </mat-card-header>
      <mat-card-content>
        <div class="diff-panels">
          <div class="diff-panel" *ngIf="expandedEntry.beforeSnapshot">
            <h4>Before</h4>
            <pre class="json-pre">{{ formatJson(expandedEntry.beforeSnapshot) }}</pre>
          </div>
          <div class="diff-panel" *ngIf="expandedEntry.afterSnapshot">
            <h4>After</h4>
            <pre class="json-pre">{{ formatJson(expandedEntry.afterSnapshot) }}</pre>
          </div>
          <div class="diff-panel" *ngIf="!expandedEntry.afterSnapshot">
            <h4>After</h4>
            <pre class="json-pre deleted">(deleted)</pre>
          </div>
        </div>
      </mat-card-content>
    </mat-card>

    <div class="empty-state" *ngIf="entries.length === 0 && !loading">
      No audit log entries. Run enrichment to generate cleanup records.
    </div>
  `,
  styles: [`
    :host { display: block; }
    .filter-bar { display: flex; align-items: center; gap: 8px; margin-bottom: 12px; }
    .filter-field { width: 200px; }
    .spacer { flex: 1; }
    .summary-bar { display: flex; align-items: center; gap: 8px; margin-bottom: 12px; flex-wrap: wrap; }
    .phase-chip {
      display: inline-block; padding: 2px 8px; border-radius: 12px; font-size: 11px;
      background: var(--mat-sys-secondary-container);
    }
    .reverted-count { color: #ff9800; font-size: 13px; }
    .centered { margin: 24px auto; }
    .audit-table { width: 100%; }
    .action-chip {
      display: inline-block; padding: 2px 8px; border-radius: 12px; font-size: 11px;
      background: var(--mat-sys-tertiary-container); color: var(--mat-sys-on-tertiary-container);
    }
    .reverted-row { opacity: 0.6; }
    .reverted { color: #4caf50; }
    .detail-card { margin-top: 16px; }
    .diff-panels { display: flex; gap: 16px; }
    .diff-panel { flex: 1; }
    .json-pre {
      background: var(--mat-sys-surface-container); padding: 12px; border-radius: 8px;
      font-size: 12px; max-height: 300px; overflow: auto; white-space: pre-wrap; word-break: break-all;
    }
    .deleted { color: #f44336; font-style: italic; }
    .empty-state { text-align: center; padding: 24px; color: var(--mat-sys-on-surface-variant); }
  `]
})
export class EnrichmentAuditLogComponent implements OnChanges {
  @Input() factSheetId: number | null = null;
  @Input() enrichmentJobId: string | null = null;

  entries: EnrichmentAuditEntry[] = [];
  summary: AuditSummary | null = null;
  expandedEntry: EnrichmentAuditEntry | null = null;

  displayColumns = ['createdAt', 'action', 'description', 'targetType', 'actions'];
  phaseFilter = '';
  pageIndex = 0;
  pageSize = 20;
  totalElements = 0;
  loading = false;
  reverting = false;

  constructor(
    private enrichmentService: EnrichmentService,
    private snackBar: MatSnackBar
  ) {}

  ngOnChanges(changes: SimpleChanges): void {
    if ((changes['factSheetId'] || changes['enrichmentJobId']) && this.factSheetId) {
      this.loadAuditLog();
    }
  }

  loadAuditLog(): void {
    if (!this.factSheetId) return;
    this.loading = true;
    this.enrichmentService.getAuditLog(
      this.factSheetId,
      this.enrichmentJobId || undefined,
      this.phaseFilter || undefined,
      this.pageIndex,
      this.pageSize
    ).subscribe({
      next: (page: any) => {
        this.entries = page.content || [];
        this.totalElements = page.totalElements || 0;
        this.loading = false;
      },
      error: () => { this.loading = false; }
    });

    if (this.enrichmentJobId) {
      this.enrichmentService.getAuditSummary(this.factSheetId, this.enrichmentJobId).subscribe({
        next: summary => this.summary = summary,
        error: () => {}
      });
    }
  }

  onPage(event: PageEvent): void {
    this.pageIndex = event.pageIndex;
    this.pageSize = event.pageSize;
    this.loadAuditLog();
  }

  revertAction(entry: EnrichmentAuditEntry): void {
    if (!this.factSheetId || entry.reverted) return;
    if (!confirm('Revert this action? ' + entry.description)) return;
    this.reverting = true;
    this.enrichmentService.revertAction(this.factSheetId, entry.auditId).subscribe({
      next: result => {
        this.reverting = false;
        this.snackBar.open(`Reverted: ${result.actionsReverted} action(s), ${result.nodesRestored} node(s) restored`, 'OK', { duration: 3000 });
        if ((result.warnings?.length ?? 0) > 0) {
          this.snackBar.open('Warnings: ' + result.warnings?.join(', '), 'OK', { duration: 5000 });
        }
        this.loadAuditLog();
      },
      error: err => {
        this.reverting = false;
        this.snackBar.open('Revert failed: ' + (err.error?.message || err.message), 'OK', { duration: 4000 });
      }
    });
  }

  revertAllPhase(): void {
    if (!this.factSheetId || !this.enrichmentJobId || !this.phaseFilter) return;
    if (!confirm('Revert all ' + this.phaseFilter + ' actions for this job?')) return;
    this.reverting = true;
    this.enrichmentService.revertPhase(this.factSheetId, this.enrichmentJobId, this.phaseFilter).subscribe({
      next: result => {
        this.reverting = false;
        this.snackBar.open(`Phase reverted: ${result.actionsReverted} action(s)`, 'OK', { duration: 3000 });
        this.loadAuditLog();
      },
      error: err => {
        this.reverting = false;
        this.snackBar.open('Revert failed: ' + (err.error?.message || err.message), 'OK', { duration: 4000 });
      }
    });
  }

  formatJson(json: string): string {
    try {
      return JSON.stringify(JSON.parse(json), null, 2);
    } catch {
      return json;
    }
  }
}
