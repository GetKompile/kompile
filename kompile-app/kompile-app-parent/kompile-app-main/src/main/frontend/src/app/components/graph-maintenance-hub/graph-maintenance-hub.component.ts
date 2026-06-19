/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */

import { Component, Input, OnInit, OnDestroy } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatCardModule } from '@angular/material/card';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';
import { MatTooltipModule } from '@angular/material/tooltip';
import { MatSlideToggleModule } from '@angular/material/slide-toggle';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatDividerModule } from '@angular/material/divider';
import { MatChipsModule } from '@angular/material/chips';
import { MatExpansionModule } from '@angular/material/expansion';
import { MatBadgeModule } from '@angular/material/badge';
import { Subject, takeUntil, finalize } from 'rxjs';
import {
  GraphMaintenanceService,
  MaintenanceReport,
  OrphanScanResult,
  Contradiction,
  ProvenanceCheck,
  GraphSnapshot,
  SchedulerStatus,
  HealthReport
} from '../../services/graph-maintenance.service';

type MaintenanceTab = 'overview' | 'pruning' | 'quality' | 'snapshots' | 'scheduler' | 'history';

@Component({
  selector: 'app-graph-maintenance-hub',
  standalone: true,
  imports: [
    CommonModule,
    FormsModule,
    MatButtonModule,
    MatIconModule,
    MatCardModule,
    MatProgressSpinnerModule,
    MatSnackBarModule,
    MatTooltipModule,
    MatSlideToggleModule,
    MatFormFieldModule,
    MatInputModule,
    MatSelectModule,
    MatDividerModule,
    MatChipsModule,
    MatExpansionModule,
    MatBadgeModule
  ],
  templateUrl: './graph-maintenance-hub.component.html',
  styleUrls: ['./graph-maintenance-hub.component.css']
})
export class GraphMaintenanceHubComponent implements OnInit, OnDestroy {
  @Input() factSheetId: number | null = null;

  private destroy$ = new Subject<void>();

  activeTab: MaintenanceTab = 'overview';
  loading = false;

  // Overview
  healthReport: HealthReport | null = null;

  // Pruning
  ttlDays = 90;
  orphanGraceDays = 7;
  minEntityConfidence = 0.3;
  minRelConfidence = 0.2;
  minComponentSize = 3;
  orphanScan: OrphanScanResult | null = null;
  lastPruneReport: MaintenanceReport | null = null;

  // Quality
  contradictions: Contradiction[] = [];
  provenanceChecks: ProvenanceCheck[] = [];
  resolutionStrategy = 'FLAG_FOR_REVIEW';

  // Snapshots
  snapshots: GraphSnapshot[] = [];
  snapshotReason = 'manual';

  // Scheduler
  schedulerStatus: SchedulerStatus | null = null;

  // History
  historyReports: MaintenanceReport[] = [];

  constructor(
    private maintenanceService: GraphMaintenanceService,
    private snackBar: MatSnackBar
  ) {}

  ngOnInit(): void {
    this.loadOverview();
    this.loadSchedulerStatus();
  }

  ngOnDestroy(): void {
    this.destroy$.next();
    this.destroy$.complete();
  }

  setTab(tab: MaintenanceTab): void {
    this.activeTab = tab;
    if (tab === 'overview') this.loadOverview();
    if (tab === 'snapshots') this.loadSnapshots();
    if (tab === 'history') this.loadHistory();
    if (tab === 'scheduler') this.loadSchedulerStatus();
  }

  // ── Overview ─────────────────────────────────────────────────────────────

  loadOverview(): void {
    this.loading = true;
    this.maintenanceService.getHealthReport(this.factSheetId)
      .pipe(takeUntil(this.destroy$), finalize(() => this.loading = false))
      .subscribe({
        next: report => this.healthReport = report,
        error: err => this.showError('Failed to load health report', err)
      });
  }

  runFullMaintenance(dryRun: boolean): void {
    if (!this.factSheetId) {
      this.showError('No fact sheet selected');
      return;
    }
    this.loading = true;
    this.maintenanceService.runFullMaintenance(this.factSheetId, dryRun)
      .pipe(takeUntil(this.destroy$), finalize(() => this.loading = false))
      .subscribe({
        next: report => {
          this.lastPruneReport = report;
          this.showSuccess(`Full maintenance ${dryRun ? '(dry run) ' : ''}completed`);
          this.loadOverview();
        },
        error: err => this.showError('Full maintenance failed', err)
      });
  }

  // ── Pruning ──────────────────────────────────────────────────────────────

  runTtlSweep(dryRun: boolean): void {
    if (!this.factSheetId) return;
    this.loading = true;
    this.maintenanceService.runTtlSweep(this.factSheetId, dryRun, this.ttlDays)
      .pipe(takeUntil(this.destroy$), finalize(() => this.loading = false))
      .subscribe({
        next: report => {
          this.lastPruneReport = report;
          this.showSuccess(`TTL sweep ${dryRun ? '(dry run) ' : ''}completed`);
        },
        error: err => this.showError('TTL sweep failed', err)
      });
  }

  scanOrphans(): void {
    if (!this.factSheetId) return;
    this.loading = true;
    this.maintenanceService.findOrphans(this.factSheetId)
      .pipe(takeUntil(this.destroy$), finalize(() => this.loading = false))
      .subscribe({
        next: result => this.orphanScan = result,
        error: err => this.showError('Orphan scan failed', err)
      });
  }

  pruneOrphans(dryRun: boolean): void {
    if (!this.factSheetId) return;
    this.loading = true;
    this.maintenanceService.pruneOrphans(this.factSheetId, dryRun, this.orphanGraceDays)
      .pipe(takeUntil(this.destroy$), finalize(() => this.loading = false))
      .subscribe({
        next: report => {
          this.lastPruneReport = report;
          this.showSuccess(`Orphan prune ${dryRun ? '(dry run) ' : ''}completed`);
        },
        error: err => this.showError('Orphan prune failed', err)
      });
  }

  pruneByConfidence(dryRun: boolean): void {
    if (!this.factSheetId) return;
    this.loading = true;
    this.maintenanceService.pruneByConfidence(this.factSheetId, dryRun,
      this.minEntityConfidence, this.minRelConfidence)
      .pipe(takeUntil(this.destroy$), finalize(() => this.loading = false))
      .subscribe({
        next: report => {
          this.lastPruneReport = report;
          this.showSuccess(`Confidence prune ${dryRun ? '(dry run) ' : ''}completed`);
        },
        error: err => this.showError('Confidence prune failed', err)
      });
  }

  pruneSmallComponents(dryRun: boolean): void {
    if (!this.factSheetId) return;
    this.loading = true;
    this.maintenanceService.pruneSmallComponents(this.factSheetId, dryRun, this.minComponentSize)
      .pipe(takeUntil(this.destroy$), finalize(() => this.loading = false))
      .subscribe({
        next: report => {
          this.lastPruneReport = report;
          this.showSuccess(`Component prune ${dryRun ? '(dry run) ' : ''}completed`);
        },
        error: err => this.showError('Component prune failed', err)
      });
  }

  // ── Quality ──────────────────────────────────────────────────────────────

  detectContradictions(): void {
    if (!this.factSheetId) return;
    this.loading = true;
    this.maintenanceService.detectContradictions(this.factSheetId)
      .pipe(takeUntil(this.destroy$), finalize(() => this.loading = false))
      .subscribe({
        next: contradictions => this.contradictions = contradictions,
        error: err => this.showError('Contradiction detection failed', err)
      });
  }

  resolveContradictions(dryRun: boolean): void {
    if (!this.factSheetId) return;
    this.loading = true;
    this.maintenanceService.resolveContradictions(this.factSheetId, this.resolutionStrategy, dryRun)
      .pipe(takeUntil(this.destroy$), finalize(() => this.loading = false))
      .subscribe({
        next: report => {
          this.lastPruneReport = report;
          this.showSuccess(`Contradictions resolved ${dryRun ? '(dry run) ' : ''}`);
          this.detectContradictions();
        },
        error: err => this.showError('Contradiction resolution failed', err)
      });
  }

  validateProvenance(): void {
    if (!this.factSheetId) return;
    this.loading = true;
    this.maintenanceService.validateProvenance(this.factSheetId)
      .pipe(takeUntil(this.destroy$), finalize(() => this.loading = false))
      .subscribe({
        next: checks => this.provenanceChecks = checks,
        error: err => this.showError('Provenance validation failed', err)
      });
  }

  // ── Snapshots ────────────────────────────────────────────────────────────

  loadSnapshots(): void {
    if (!this.factSheetId) return;
    this.loading = true;
    this.maintenanceService.listSnapshots(this.factSheetId)
      .pipe(takeUntil(this.destroy$), finalize(() => this.loading = false))
      .subscribe({
        next: snapshots => this.snapshots = snapshots,
        error: err => this.showError('Failed to load snapshots', err)
      });
  }

  createSnapshot(): void {
    if (!this.factSheetId) return;
    this.loading = true;
    this.maintenanceService.createSnapshot(this.factSheetId, this.snapshotReason)
      .pipe(takeUntil(this.destroy$), finalize(() => this.loading = false))
      .subscribe({
        next: snapshot => {
          this.snapshots.unshift(snapshot);
          this.snapshotReason = 'manual';
          this.showSuccess('Snapshot created');
        },
        error: err => this.showError('Snapshot creation failed', err)
      });
  }

  // ── Scheduler ────────────────────────────────────────────────────────────

  loadSchedulerStatus(): void {
    this.maintenanceService.getSchedulerStatus()
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: status => this.schedulerStatus = status,
        error: () => this.schedulerStatus = { enabled: false, targetFactSheetId: null }
      });
  }

  toggleScheduler(): void {
    if (!this.schedulerStatus) return;
    if (this.schedulerStatus.enabled) {
      this.maintenanceService.disableScheduler()
        .pipe(takeUntil(this.destroy$))
        .subscribe({
          next: status => {
            this.schedulerStatus = status;
            this.showSuccess('Scheduler disabled');
          },
          error: err => this.showError('Failed to disable scheduler', err)
        });
    } else {
      if (!this.factSheetId) {
        this.showError('No fact sheet selected');
        return;
      }
      this.maintenanceService.enableScheduler(this.factSheetId)
        .pipe(takeUntil(this.destroy$))
        .subscribe({
          next: status => {
            this.schedulerStatus = status;
            this.showSuccess('Scheduler enabled');
          },
          error: err => this.showError('Failed to enable scheduler', err)
        });
    }
  }

  // ── History ──────────────────────────────────────────────────────────────

  loadHistory(): void {
    this.loading = true;
    const obs = this.factSheetId
      ? this.maintenanceService.getMaintenanceHistory(this.factSheetId)
      : this.maintenanceService.getAllMaintenanceHistory();
    obs.pipe(takeUntil(this.destroy$), finalize(() => this.loading = false))
      .subscribe({
        next: reports => this.historyReports = reports,
        error: err => this.showError('Failed to load history', err)
      });
  }

  // ── Helpers ──────────────────────────────────────────────────────────────

  getTaskReportEntries(report: MaintenanceReport): { key: string; value: any }[] {
    if (!report.taskReports) return [];
    return Object.entries(report.taskReports).map(([key, value]) => ({ key, value }));
  }

  getInvalidProvenanceCount(): number {
    return this.provenanceChecks.filter(p => p.allSourcesInvalid).length;
  }

  formatDate(dateStr: string): string {
    if (!dateStr) return '-';
    return new Date(dateStr).toLocaleString();
  }

  private showSuccess(message: string): void {
    this.snackBar.open(message, 'Dismiss', { duration: 3000, panelClass: 'snack-success' });
  }

  private showError(message: string, err?: any): void {
    const detail = err?.error?.message || err?.message || '';
    this.snackBar.open(`${message}${detail ? ': ' + detail : ''}`, 'Dismiss', { duration: 5000, panelClass: 'snack-error' });
  }
}
