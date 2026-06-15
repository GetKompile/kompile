/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
import { Component, Input, OnInit, OnDestroy, EventEmitter, Output } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { MatCardModule } from '@angular/material/card';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatSelectModule } from '@angular/material/select';
import { MatInputModule } from '@angular/material/input';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatSliderModule } from '@angular/material/slider';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatChipsModule } from '@angular/material/chips';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';
import { MatTabsModule } from '@angular/material/tabs';
import { MatExpansionModule } from '@angular/material/expansion';
import { MatTooltipModule } from '@angular/material/tooltip';
import { MatListModule } from '@angular/material/list';
import { MatDividerModule } from '@angular/material/divider';
import { MatBadgeModule } from '@angular/material/badge';
import { Subject, takeUntil } from 'rxjs';

import {
  GraphMaintenanceService,
  HealthReport,
  PruneResult,
  ValidateResult,
  RelabelResult,
  BulkDeleteResult,
  EdgeCleanupResult,
  LabelCount
} from '../../services/graph-maintenance.service';

@Component({
  selector: 'app-graph-maintenance-panel',
  standalone: true,
  imports: [
    CommonModule,
    FormsModule,
    MatCardModule,
    MatButtonModule,
    MatIconModule,
    MatSelectModule,
    MatInputModule,
    MatFormFieldModule,
    MatSliderModule,
    MatCheckboxModule,
    MatChipsModule,
    MatProgressSpinnerModule,
    MatSnackBarModule,
    MatTabsModule,
    MatExpansionModule,
    MatTooltipModule,
    MatListModule,
    MatDividerModule,
    MatBadgeModule
  ],
  template: `
    <div class="maintenance-panel">
      <mat-tab-group [(selectedIndex)]="selectedTab">
        <!-- Health Tab -->
        <mat-tab>
          <ng-template mat-tab-label>
            <mat-icon [matBadge]="healthReport?.issues?.length || null" matBadgeColor="warn"
                      [matBadgeHidden]="!healthReport?.issues?.length">monitor_heart</mat-icon>
            <span class="tab-label">Health</span>
          </ng-template>
          <div class="tab-content">
            <div class="action-bar">
              <button mat-raised-button color="primary" (click)="loadHealth()" [disabled]="loading">
                <mat-icon>refresh</mat-icon> Scan
              </button>
            </div>
            <mat-spinner *ngIf="loading" diameter="30"></mat-spinner>
            <div *ngIf="healthReport && !loading" class="health-report">
              <div class="stat-grid">
                <div class="stat-card">
                  <div class="stat-value">{{healthReport.totalNodes}}</div>
                  <div class="stat-label">Nodes</div>
                </div>
                <div class="stat-card">
                  <div class="stat-value">{{healthReport.totalEdges}}</div>
                  <div class="stat-label">Edges</div>
                </div>
                <div class="stat-card" [class.warn]="healthReport.orphanEntityCount > 0">
                  <div class="stat-value">{{healthReport.orphanEntityCount}}</div>
                  <div class="stat-label">Orphans</div>
                </div>
                <div class="stat-card" [class.warn]="healthReport.danglingEdgeCount > 0">
                  <div class="stat-value">{{healthReport.danglingEdgeCount}}</div>
                  <div class="stat-label">Dangling</div>
                </div>
              </div>

              <mat-expansion-panel *ngIf="healthReport.issues.length > 0">
                <mat-expansion-panel-header>
                  <mat-panel-title>
                    <mat-icon color="warn">warning</mat-icon>
                    {{healthReport.issues.length}} Issues Found
                  </mat-panel-title>
                </mat-expansion-panel-header>
                <mat-list dense>
                  <mat-list-item *ngFor="let issue of healthReport.issues">
                    <mat-icon matListItemIcon color="warn">error_outline</mat-icon>
                    <span>{{issue}}</span>
                  </mat-list-item>
                </mat-list>
              </mat-expansion-panel>

              <mat-expansion-panel>
                <mat-expansion-panel-header>
                  <mat-panel-title>Entity Type Distribution</mat-panel-title>
                </mat-expansion-panel-header>
                <div class="label-list">
                  <div class="label-item" *ngFor="let entry of objectEntries(healthReport.entityTypeDistribution)">
                    <span class="label-name">{{entry[0]}}</span>
                    <span class="label-count">{{entry[1]}}</span>
                  </div>
                </div>
              </mat-expansion-panel>
            </div>
          </div>
        </mat-tab>

        <!-- Prune Tab -->
        <mat-tab>
          <ng-template mat-tab-label>
            <mat-icon>content_cut</mat-icon>
            <span class="tab-label">Prune</span>
          </ng-template>
          <div class="tab-content">
            <p class="section-desc">Remove orphan entities and weak edges.</p>

            <mat-form-field appearance="outline" class="full-width">
              <mat-label>Confidence Threshold</mat-label>
              <input matInput type="number" [(ngModel)]="pruneConfidence" min="0" max="1" step="0.05">
              <mat-hint>Orphan entities below this confidence are removed</mat-hint>
            </mat-form-field>

            <mat-form-field appearance="outline" class="full-width">
              <mat-label>Edge Weight Threshold</mat-label>
              <input matInput type="number" [(ngModel)]="pruneEdgeWeight" min="0" max="1" step="0.05">
              <mat-hint>Weak edges below this weight are removed</mat-hint>
            </mat-form-field>

            <div class="action-bar">
              <button mat-stroked-button (click)="previewPrune()" [disabled]="loading">
                <mat-icon>preview</mat-icon> Preview
              </button>
              <button mat-raised-button color="warn" (click)="executePrune()" [disabled]="loading">
                <mat-icon>content_cut</mat-icon> Prune
              </button>
            </div>

            <div *ngIf="pruneResult" class="result-summary">
              <div class="result-header" [class.dry-run]="pruneResult.dryRun">
                {{pruneResult.dryRun ? 'Preview' : 'Executed'}}:
                {{pruneResult.nodesPruned}} nodes, {{pruneResult.edgesPruned}} edges
              </div>
              <mat-list dense *ngIf="pruneResult.details?.length">
                <mat-list-item *ngFor="let d of pruneResult.details">
                  <span class="detail-reason">[{{d.reason}}]</span>
                  {{d.title || '(untitled)'}} — {{d.info}}
                </mat-list-item>
              </mat-list>
            </div>
          </div>
        </mat-tab>

        <!-- Validate Tab -->
        <mat-tab>
          <ng-template mat-tab-label>
            <mat-icon>verified</mat-icon>
            <span class="tab-label">Validate</span>
          </ng-template>
          <div class="tab-content">
            <p class="section-desc">Fix blank titles, dangling edges, and duplicates.</p>

            <div class="action-bar">
              <button mat-stroked-button (click)="previewValidate()" [disabled]="loading">
                <mat-icon>preview</mat-icon> Preview
              </button>
              <button mat-raised-button color="primary" (click)="executeValidate()" [disabled]="loading">
                <mat-icon>build</mat-icon> Fix Issues
              </button>
            </div>

            <div *ngIf="validateResult" class="result-summary">
              <div class="result-header" [class.dry-run]="validateResult.dryRun">
                {{validateResult.dryRun ? 'Preview' : 'Fixed'}}: {{validateResult.issuesFound}} issues
              </div>
              <mat-list dense *ngIf="validateResult.details?.length">
                <mat-list-item *ngFor="let d of validateResult.details">
                  <span class="detail-reason">[{{d.action}}]</span>
                  {{d.description}}
                </mat-list-item>
              </mat-list>
            </div>
          </div>
        </mat-tab>

        <!-- Labels Tab -->
        <mat-tab>
          <ng-template mat-tab-label>
            <mat-icon>label</mat-icon>
            <span class="tab-label">Labels</span>
          </ng-template>
          <div class="tab-content">
            <p class="section-desc">View and change entity type labels.</p>

            <div class="action-bar">
              <button mat-raised-button color="primary" (click)="loadLabels()" [disabled]="loading">
                <mat-icon>refresh</mat-icon> Load Labels
              </button>
            </div>

            <div *ngIf="labels.length > 0" class="label-list">
              <div class="label-item" *ngFor="let l of labels">
                <span class="label-name">{{l.label}}</span>
                <span class="label-count">{{l.count}}</span>
              </div>
            </div>

            <mat-divider *ngIf="labels.length > 0"></mat-divider>

            <h4>Relabel Entities</h4>
            <mat-form-field appearance="outline" class="full-width">
              <mat-label>From Type</mat-label>
              <mat-select [(ngModel)]="relabelFrom">
                <mat-option *ngFor="let l of labels" [value]="l.label">
                  {{l.label}} ({{l.count}})
                </mat-option>
              </mat-select>
            </mat-form-field>

            <mat-form-field appearance="outline" class="full-width">
              <mat-label>To Type</mat-label>
              <input matInput [(ngModel)]="relabelTo" placeholder="New label name">
            </mat-form-field>

            <mat-form-field appearance="outline" class="full-width">
              <mat-label>Title Pattern (optional regex)</mat-label>
              <input matInput [(ngModel)]="relabelTitlePattern" placeholder=".*">
            </mat-form-field>

            <div class="action-bar">
              <button mat-stroked-button (click)="previewRelabel()" [disabled]="!relabelFrom || !relabelTo || loading">
                <mat-icon>preview</mat-icon> Preview
              </button>
              <button mat-raised-button color="accent" (click)="executeRelabel()" [disabled]="!relabelFrom || !relabelTo || loading">
                <mat-icon>swap_horiz</mat-icon> Relabel
              </button>
            </div>

            <div *ngIf="relabelResult" class="result-summary">
              <div class="result-header" [class.dry-run]="relabelResult.dryRun">
                {{relabelResult.dryRun ? 'Preview' : 'Relabeled'}}: {{relabelResult.relabeledCount}} entities
                ({{relabelResult.fromType}} -> {{relabelResult.toType}})
              </div>
              <mat-list dense *ngIf="relabelResult.details?.length">
                <mat-list-item *ngFor="let d of relabelResult.details">
                  {{d.title || '(untitled)'}}: {{d.oldType}} -> {{d.newType}}
                </mat-list-item>
              </mat-list>
            </div>
          </div>
        </mat-tab>

        <!-- Bulk Ops Tab -->
        <mat-tab>
          <ng-template mat-tab-label>
            <mat-icon>delete_sweep</mat-icon>
            <span class="tab-label">Bulk Ops</span>
          </ng-template>
          <div class="tab-content">
            <mat-expansion-panel [expanded]="true">
              <mat-expansion-panel-header>
                <mat-panel-title>Bulk Delete</mat-panel-title>
              </mat-expansion-panel-header>

              <mat-form-field appearance="outline" class="full-width">
                <mat-label>Node Type</mat-label>
                <mat-select [(ngModel)]="bulkDeleteNodeType">
                  <mat-option value="">Any</mat-option>
                  <mat-option value="ENTITY">ENTITY</mat-option>
                  <mat-option value="SOURCE">SOURCE</mat-option>
                  <mat-option value="DOCUMENT">DOCUMENT</mat-option>
                  <mat-option value="SNIPPET">SNIPPET</mat-option>
                  <mat-option value="CUSTOM">CUSTOM</mat-option>
                  <mat-option value="TABLE">TABLE</mat-option>
                </mat-select>
              </mat-form-field>

              <mat-form-field appearance="outline" class="full-width">
                <mat-label>Entity Type Label</mat-label>
                <input matInput [(ngModel)]="bulkDeleteEntityType" placeholder="e.g. PERSON">
              </mat-form-field>

              <mat-form-field appearance="outline" class="full-width">
                <mat-label>Max Confidence</mat-label>
                <input matInput type="number" [(ngModel)]="bulkDeleteMaxConfidence" min="0" max="1" step="0.1">
              </mat-form-field>

              <mat-checkbox [(ngModel)]="bulkDeleteOrphansOnly">Orphans only (zero edges)</mat-checkbox>

              <mat-form-field appearance="outline" class="full-width">
                <mat-label>Title Pattern (regex)</mat-label>
                <input matInput [(ngModel)]="bulkDeleteTitlePattern">
              </mat-form-field>

              <div class="action-bar">
                <button mat-stroked-button (click)="previewBulkDelete()" [disabled]="loading">
                  <mat-icon>preview</mat-icon> Preview
                </button>
                <button mat-raised-button color="warn" (click)="executeBulkDelete()" [disabled]="loading">
                  <mat-icon>delete_sweep</mat-icon> Delete
                </button>
              </div>

              <div *ngIf="bulkDeleteResult" class="result-summary">
                <div class="result-header" [class.dry-run]="bulkDeleteResult.dryRun">
                  {{bulkDeleteResult.dryRun ? 'Preview' : 'Deleted'}}: {{bulkDeleteResult.deletedCount}} nodes
                </div>
                <mat-list dense *ngIf="bulkDeleteResult.details?.length">
                  <mat-list-item *ngFor="let d of bulkDeleteResult.details">
                    [{{d.nodeType}}/{{d.entityType || '-'}}] {{d.title || '(untitled)'}}
                  </mat-list-item>
                </mat-list>
              </div>
            </mat-expansion-panel>

            <mat-expansion-panel>
              <mat-expansion-panel-header>
                <mat-panel-title>Edge Cleanup</mat-panel-title>
              </mat-expansion-panel-header>

              <mat-checkbox [(ngModel)]="edgeCleanupDangling">Remove dangling edges</mat-checkbox>
              <mat-checkbox [(ngModel)]="edgeCleanupDuplicates">Remove duplicate edges</mat-checkbox>

              <mat-form-field appearance="outline" class="full-width">
                <mat-label>Min Edge Weight</mat-label>
                <input matInput type="number" [(ngModel)]="edgeCleanupMinWeight" min="0" max="1" step="0.05">
                <mat-hint>Remove edges below this weight (0 = skip)</mat-hint>
              </mat-form-field>

              <div class="action-bar">
                <button mat-stroked-button (click)="previewEdgeCleanup()" [disabled]="loading">
                  <mat-icon>preview</mat-icon> Preview
                </button>
                <button mat-raised-button color="warn" (click)="executeEdgeCleanup()" [disabled]="loading">
                  <mat-icon>cleaning_services</mat-icon> Cleanup
                </button>
              </div>

              <div *ngIf="edgeCleanupResult" class="result-summary">
                <div class="result-header" [class.dry-run]="edgeCleanupResult.dryRun">
                  {{edgeCleanupResult.dryRun ? 'Preview' : 'Cleaned'}}:
                  {{edgeCleanupResult.danglingRemoved}} dangling,
                  {{edgeCleanupResult.duplicatesRemoved}} duplicates,
                  {{edgeCleanupResult.weakRemoved}} weak
                </div>
              </div>
            </mat-expansion-panel>
          </div>
        </mat-tab>
      </mat-tab-group>
    </div>
  `,
  styles: [`
    .maintenance-panel {
      height: 100%;
      overflow-y: auto;
    }
    .tab-content {
      padding: 12px;
    }
    .tab-label {
      margin-left: 6px;
      font-size: 12px;
    }
    .section-desc {
      color: #888;
      font-size: 12px;
      margin: 0 0 12px 0;
    }
    .action-bar {
      display: flex;
      gap: 8px;
      margin: 12px 0;
    }
    .full-width {
      width: 100%;
    }
    .stat-grid {
      display: grid;
      grid-template-columns: 1fr 1fr;
      gap: 8px;
      margin-bottom: 12px;
    }
    .stat-card {
      background: #f5f5f5;
      border-radius: 8px;
      padding: 12px;
      text-align: center;
    }
    .stat-card.warn {
      background: #fff3e0;
      border: 1px solid #ff9800;
    }
    .stat-value {
      font-size: 24px;
      font-weight: 700;
    }
    .stat-label {
      font-size: 11px;
      color: #666;
      text-transform: uppercase;
    }
    .label-list {
      margin: 8px 0;
    }
    .label-item {
      display: flex;
      justify-content: space-between;
      padding: 4px 8px;
      border-bottom: 1px solid #eee;
      font-size: 13px;
    }
    .label-name {
      font-weight: 500;
    }
    .label-count {
      color: #666;
    }
    .result-summary {
      margin-top: 12px;
      background: #fafafa;
      border-radius: 8px;
      padding: 8px;
      max-height: 300px;
      overflow-y: auto;
    }
    .result-header {
      font-weight: 600;
      padding: 4px 8px;
      border-radius: 4px;
      background: #e8f5e9;
      margin-bottom: 8px;
    }
    .result-header.dry-run {
      background: #e3f2fd;
    }
    .detail-reason {
      font-size: 11px;
      font-weight: 600;
      color: #ff5722;
      margin-right: 4px;
    }
    mat-checkbox {
      display: block;
      margin: 8px 0;
    }
    ::ng-deep .maintenance-panel .mat-mdc-tab-labels {
      justify-content: center;
    }
    ::ng-deep .maintenance-panel .mat-mdc-tab {
      min-width: 0;
      padding: 0 8px;
    }
  `]
})
export class GraphMaintenancePanelComponent implements OnInit, OnDestroy {
  @Input() factSheetId: number | null = null;
  @Output() graphChanged = new EventEmitter<void>();

  private destroy$ = new Subject<void>();

  selectedTab = 0;
  loading = false;

  // Health
  healthReport: HealthReport | null = null;

  // Prune
  pruneConfidence = 0.3;
  pruneEdgeWeight = 0.25;
  pruneResult: PruneResult | null = null;

  // Validate
  validateResult: ValidateResult | null = null;

  // Labels / Relabel
  labels: LabelCount[] = [];
  relabelFrom = '';
  relabelTo = '';
  relabelTitlePattern = '';
  relabelResult: RelabelResult | null = null;

  // Bulk Delete
  bulkDeleteNodeType = '';
  bulkDeleteEntityType = '';
  bulkDeleteMaxConfidence: number | null = null;
  bulkDeleteOrphansOnly = false;
  bulkDeleteTitlePattern = '';
  bulkDeleteResult: BulkDeleteResult | null = null;

  // Edge Cleanup
  edgeCleanupDangling = true;
  edgeCleanupDuplicates = true;
  edgeCleanupMinWeight: number | null = null;
  edgeCleanupResult: EdgeCleanupResult | null = null;

  constructor(
    private maintenanceService: GraphMaintenanceService,
    private snackBar: MatSnackBar
  ) {}

  ngOnInit(): void {
    this.loadHealth();
  }

  ngOnDestroy(): void {
    this.destroy$.next();
    this.destroy$.complete();
  }

  objectEntries(obj: Record<string, number> | undefined): [string, number][] {
    if (!obj) return [];
    return Object.entries(obj).sort((a, b) => b[1] - a[1]);
  }

  // ═════════════════════════════════════════════════════════════════════════
  // HEALTH
  // ═════════════════════════════════════════════════════════════════════════

  loadHealth(): void {
    this.loading = true;
    this.maintenanceService.getHealthReport(this.factSheetId)
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: report => { this.healthReport = report; this.loading = false; },
        error: err => { this.showError('Failed to load health report', err); this.loading = false; }
      });
  }

  // ═════════════════════════════════════════════════════════════════════════
  // PRUNE
  // ═════════════════════════════════════════════════════════════════════════

  previewPrune(): void {
    this.loading = true;
    this.maintenanceService.prunePreview(this.factSheetId, {
      confidenceThreshold: this.pruneConfidence,
      edgeWeightThreshold: this.pruneEdgeWeight
    }).pipe(takeUntil(this.destroy$)).subscribe({
      next: result => { this.pruneResult = result; this.loading = false; },
      error: err => { this.showError('Prune preview failed', err); this.loading = false; }
    });
  }

  executePrune(): void {
    this.loading = true;
    this.maintenanceService.prune(this.factSheetId, {
      confidenceThreshold: this.pruneConfidence,
      edgeWeightThreshold: this.pruneEdgeWeight
    }).pipe(takeUntil(this.destroy$)).subscribe({
      next: result => {
        this.pruneResult = result;
        this.loading = false;
        this.snackBar.open(`Pruned ${result.nodesPruned} nodes, ${result.edgesPruned} edges`, 'OK', { duration: 3000 });
        this.graphChanged.emit();
      },
      error: err => { this.showError('Prune failed', err); this.loading = false; }
    });
  }

  // ═════════════════════════════════════════════════════════════════════════
  // VALIDATE
  // ═════════════════════════════════════════════════════════════════════════

  previewValidate(): void {
    this.loading = true;
    this.maintenanceService.validatePreview(this.factSheetId)
      .pipe(takeUntil(this.destroy$)).subscribe({
        next: result => { this.validateResult = result; this.loading = false; },
        error: err => { this.showError('Validate preview failed', err); this.loading = false; }
      });
  }

  executeValidate(): void {
    this.loading = true;
    this.maintenanceService.validate(this.factSheetId, false)
      .pipe(takeUntil(this.destroy$)).subscribe({
        next: result => {
          this.validateResult = result;
          this.loading = false;
          this.snackBar.open(`Fixed ${result.issuesFound} issues`, 'OK', { duration: 3000 });
          this.graphChanged.emit();
        },
        error: err => { this.showError('Validate failed', err); this.loading = false; }
      });
  }

  // ═════════════════════════════════════════════════════════════════════════
  // LABELS / RELABEL
  // ═════════════════════════════════════════════════════════════════════════

  loadLabels(): void {
    this.loading = true;
    this.maintenanceService.getLabels(this.factSheetId)
      .pipe(takeUntil(this.destroy$)).subscribe({
        next: labels => { this.labels = labels; this.loading = false; },
        error: err => { this.showError('Failed to load labels', err); this.loading = false; }
      });
  }

  previewRelabel(): void {
    this.loading = true;
    this.maintenanceService.relabelPreview(this.factSheetId, {
      fromType: this.relabelFrom,
      toType: this.relabelTo,
      titlePattern: this.relabelTitlePattern || undefined
    }).pipe(takeUntil(this.destroy$)).subscribe({
      next: result => { this.relabelResult = result; this.loading = false; },
      error: err => { this.showError('Relabel preview failed', err); this.loading = false; }
    });
  }

  executeRelabel(): void {
    this.loading = true;
    this.maintenanceService.relabel(this.factSheetId, {
      fromType: this.relabelFrom,
      toType: this.relabelTo,
      titlePattern: this.relabelTitlePattern || undefined
    }).pipe(takeUntil(this.destroy$)).subscribe({
      next: result => {
        this.relabelResult = result;
        this.loading = false;
        this.snackBar.open(`Relabeled ${result.relabeledCount} entities`, 'OK', { duration: 3000 });
        this.loadLabels();
        this.graphChanged.emit();
      },
      error: err => { this.showError('Relabel failed', err); this.loading = false; }
    });
  }

  // ═════════════════════════════════════════════════════════════════════════
  // BULK DELETE
  // ═════════════════════════════════════════════════════════════════════════

  previewBulkDelete(): void {
    this.loading = true;
    this.maintenanceService.bulkDeletePreview(this.factSheetId, this.buildBulkDeleteRequest())
      .pipe(takeUntil(this.destroy$)).subscribe({
        next: result => { this.bulkDeleteResult = result; this.loading = false; },
        error: err => { this.showError('Bulk delete preview failed', err); this.loading = false; }
      });
  }

  executeBulkDelete(): void {
    this.loading = true;
    this.maintenanceService.bulkDelete(this.factSheetId, this.buildBulkDeleteRequest())
      .pipe(takeUntil(this.destroy$)).subscribe({
        next: result => {
          this.bulkDeleteResult = result;
          this.loading = false;
          this.snackBar.open(`Deleted ${result.deletedCount} nodes`, 'OK', { duration: 3000 });
          this.graphChanged.emit();
        },
        error: err => { this.showError('Bulk delete failed', err); this.loading = false; }
      });
  }

  private buildBulkDeleteRequest() {
    return {
      nodeType: this.bulkDeleteNodeType || undefined,
      entityType: this.bulkDeleteEntityType || undefined,
      maxConfidence: this.bulkDeleteMaxConfidence ?? undefined,
      orphansOnly: this.bulkDeleteOrphansOnly || undefined,
      titlePattern: this.bulkDeleteTitlePattern || undefined
    };
  }

  // ═════════════════════════════════════════════════════════════════════════
  // EDGE CLEANUP
  // ═════════════════════════════════════════════════════════════════════════

  previewEdgeCleanup(): void {
    this.loading = true;
    this.maintenanceService.edgeCleanupPreview(this.factSheetId, this.buildEdgeCleanupRequest())
      .pipe(takeUntil(this.destroy$)).subscribe({
        next: result => { this.edgeCleanupResult = result; this.loading = false; },
        error: err => { this.showError('Edge cleanup preview failed', err); this.loading = false; }
      });
  }

  executeEdgeCleanup(): void {
    this.loading = true;
    this.maintenanceService.edgeCleanup(this.factSheetId, this.buildEdgeCleanupRequest())
      .pipe(takeUntil(this.destroy$)).subscribe({
        next: result => {
          this.edgeCleanupResult = result;
          this.loading = false;
          const total = result.danglingRemoved + result.duplicatesRemoved + result.weakRemoved;
          this.snackBar.open(`Cleaned ${total} edges`, 'OK', { duration: 3000 });
          this.graphChanged.emit();
        },
        error: err => { this.showError('Edge cleanup failed', err); this.loading = false; }
      });
  }

  private buildEdgeCleanupRequest() {
    return {
      removeDangling: this.edgeCleanupDangling,
      removeDuplicates: this.edgeCleanupDuplicates,
      minWeight: this.edgeCleanupMinWeight ?? undefined
    };
  }

  // ═════════════════════════════════════════════════════════════════════════

  private showError(message: string, err: any): void {
    const detail = err?.error?.message || err?.message || '';
    this.snackBar.open(`${message}: ${detail}`, 'OK', { duration: 5000 });
  }
}
