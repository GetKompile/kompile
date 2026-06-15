/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import { Component, OnInit, OnDestroy } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { MatIconModule } from '@angular/material/icon';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';
import { MatChipsModule } from '@angular/material/chips';
import { MatDividerModule } from '@angular/material/divider';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatButtonToggleModule } from '@angular/material/button-toggle';
import { MatTooltipModule } from '@angular/material/tooltip';
import { Subject, interval } from 'rxjs';
import { takeUntil } from 'rxjs/operators';
import {
  ProcessEngineService,
  WorkflowRun,
  StepExecution
} from '../../services/process-engine.service';
import { ProcessAttributionService, ProcessRiskAssessment } from '../../services/process-attribution.service';
import { ProcessRunDetailComponent } from './process-run-detail.component';

@Component({
  standalone: true,
  selector: 'app-process-runs',
  imports: [
    CommonModule, FormsModule,
    MatIconModule, MatButtonModule, MatCardModule,
    MatProgressBarModule, MatSnackBarModule,
    MatChipsModule, MatDividerModule,
    MatFormFieldModule, MatInputModule, MatSelectModule,
    MatButtonToggleModule, MatTooltipModule,
    ProcessRunDetailComponent
  ],
  template: `
    <div class="runs-container">
      <!-- Detail View (when a run is selected) -->
      <app-process-run-detail
        *ngIf="selectedRunId"
        [runId]="selectedRunId"
        (closed)="selectedRunId = null"
        (navigateToGraph)="onNavigateToGraph($event)">
      </app-process-run-detail>

      <!-- List View (when no run is selected) -->
      <ng-container *ngIf="!selectedRunId">

      <!-- Toolbar -->
      <div class="section-toolbar">
        <button mat-raised-button color="primary" (click)="showStartForm = !showStartForm">
          <mat-icon>{{ showStartForm ? 'close' : 'play_circle' }}</mat-icon>
          {{ showStartForm ? 'Cancel' : 'Start New Run' }}
        </button>
        <mat-button-toggle-group [(value)]="viewFilter" (change)="loadRuns()">
          <mat-button-toggle value="active">Active</mat-button-toggle>
          <mat-button-toggle value="all">All</mat-button-toggle>
        </mat-button-toggle-group>
        <button mat-icon-button (click)="loadRuns()" matTooltip="Refresh">
          <mat-icon>refresh</mat-icon>
        </button>
        <span class="toolbar-label" *ngIf="runs.length > 0">
          {{ runs.length }} run{{ runs.length !== 1 ? 's' : '' }}
        </span>
      </div>

      <!-- Start New Run Form -->
      <div *ngIf="showStartForm" class="start-form">
        <h4>Start New Workflow Run</h4>
        <mat-form-field appearance="outline" class="full-width">
          <mat-label>Process Definition ID</mat-label>
          <input matInput [(ngModel)]="startProcessId" placeholder="e.g. proc-compliance-001">
        </mat-form-field>
        <mat-form-field appearance="outline" class="full-width">
          <mat-label>Initial Data JSON (optional)</mat-label>
          <textarea matInput [(ngModel)]="startInitialData" rows="5" class="json-editor"
                    placeholder='{"key": "value"}'></textarea>
        </mat-form-field>
        <div class="form-actions">
          <button mat-raised-button color="primary" (click)="startRun()"
                  [disabled]="starting || !startProcessId.trim()">
            <mat-icon>play_arrow</mat-icon> {{ starting ? 'Starting...' : 'Start' }}
          </button>
        </div>
      </div>

      <mat-divider *ngIf="showStartForm" class="section-divider"></mat-divider>

      <!-- Runs List -->
      <div *ngIf="loading" class="loading-state">
        <mat-icon class="spin">refresh</mat-icon> Loading runs...
      </div>

      <div *ngIf="!loading && runs.length === 0" class="empty-state">
        <mat-icon class="empty-icon">play_circle</mat-icon>
        <p>No {{ viewFilter === 'active' ? 'active ' : '' }}workflow runs</p>
        <button mat-raised-button color="primary" (click)="showStartForm = true">
          <mat-icon>play_circle</mat-icon> Start a Run
        </button>
      </div>

      <div class="runs-list" *ngIf="!loading && runs.length > 0">
        <div *ngFor="let run of runs" class="run-card">
          <div class="run-header" (click)="toggleExpand(run)">
            <div class="run-status-icon">
              <mat-icon [class]="'status-icon status-' + (run.status || 'RUNNING').toLowerCase()">
                {{ getStatusIcon(run.status) }}
              </mat-icon>
            </div>
            <div class="run-info">
              <span class="run-id">{{ run.id || 'unknown' }}</span>
              <span class="run-process">{{ run.processDefinitionId }}</span>
            </div>
            <div class="run-chips">
              <mat-chip-set>
                <mat-chip [class]="'status-chip status-' + (run.status || 'RUNNING').toLowerCase()">
                  {{ run.status || 'RUNNING' }}
                </mat-chip>
                <mat-chip *ngIf="runRiskLevels[run.id || '']"
                          [class]="'risk-chip risk-chip-' + runRiskLevels[run.id || ''].level.toLowerCase()">
                  <mat-icon class="risk-chip-icon">security</mat-icon>
                  {{ runRiskLevels[run.id || ''].level }}
                </mat-chip>
              </mat-chip-set>
            </div>
            <div class="run-progress-info">
              <span class="progress-label">{{ countCompleted(run) }} / {{ countTotal(run) }} steps</span>
            </div>
            <mat-icon class="expand-icon">{{ expandedRuns.has(run.id || '') ? 'expand_less' : 'expand_more' }}</mat-icon>
          </div>

          <mat-progress-bar
            mode="determinate"
            [value]="getProgressPercent(run)"
            [class]="'progress-bar-' + (run.status || 'RUNNING').toLowerCase()"
          ></mat-progress-bar>

          <!-- Step Timeline -->
          <div class="step-timeline" *ngIf="run.stepExecutions?.length">
            <div *ngFor="let step of run.stepExecutions; let last = last"
                 class="timeline-node-group">
              <div [class]="'timeline-node timeline-' + step.status.toLowerCase()"
                   [matTooltip]="(step.stepName || step.stepId) + ' — ' + step.status">
              </div>
              <div class="timeline-connector" *ngIf="!last"
                   [class.connector-completed]="step.status === 'COMPLETED'">
              </div>
            </div>
          </div>

          <div class="run-meta">
            <span *ngIf="run.startedAt">Started: {{ formatDate(run.startedAt) }}</span>
            <span *ngIf="run.completedAt">Completed: {{ formatDate(run.completedAt) }}</span>
            <span *ngIf="run.startedAt && run.completedAt" class="duration-badge">
              <mat-icon>timer</mat-icon> {{ getDuration(run.startedAt, run.completedAt) }}
            </span>
            <span *ngIf="run.startedAt && !run.completedAt && isActiveRun(run)" class="duration-badge active-timer">
              <mat-icon>timer</mat-icon> {{ getElapsed(run.startedAt) }}
            </span>
            <span *ngIf="run.pendingApprovals?.length" class="pending-approvals">
              <mat-icon>approval</mat-icon> {{ run.pendingApprovals?.length }} pending
            </span>
          </div>

          <!-- Run Action Buttons -->
          <div class="run-actions">
            <button mat-stroked-button color="primary" (click)="selectedRunId = run.id || null; $event.stopPropagation()"
                    matTooltip="View full run details">
              <mat-icon>visibility</mat-icon> View Details
            </button>
            <button mat-stroked-button color="warn" (click)="cancelRun(run); $event.stopPropagation()"
                    *ngIf="run.status === 'RUNNING' || run.status === 'PAUSED_FOR_APPROVAL' || run.status === 'PAUSED_FOR_HUMAN'"
                    matTooltip="Cancel this run">
              <mat-icon>cancel</mat-icon> Cancel
            </button>
            <button mat-stroked-button (click)="promptCompleteStep(run); $event.stopPropagation()"
                    *ngIf="run.status === 'PAUSED_FOR_HUMAN'"
                    matTooltip="Complete the pending human step">
              <mat-icon>task_alt</mat-icon> Complete Step
            </button>
            <button mat-stroked-button (click)="loadRunRisk(run); $event.stopPropagation()"
                    *ngIf="run.id && !runRiskLevels[run.id] && !riskLoadingRuns.has(run.id)"
                    matTooltip="Assess run risk">
              <mat-icon>security</mat-icon> Risk
            </button>
            <span *ngIf="run.id && riskLoadingRuns.has(run.id)" class="risk-loading-inline">Assessing...</span>
          </div>

          <!-- Complete Step Form (inline) -->
          <div *ngIf="completeStepRunId === run.id" class="complete-step-form">
            <mat-divider></mat-divider>
            <div class="form-heading">Complete Human Step</div>
            <mat-form-field appearance="outline" class="full-width">
              <mat-label>Step ID</mat-label>
              <input matInput [(ngModel)]="completeStepId" placeholder="step-id">
            </mat-form-field>
            <mat-form-field appearance="outline" class="full-width">
              <mat-label>Completed By</mat-label>
              <input matInput [(ngModel)]="completeStepBy" placeholder="user@example.com">
            </mat-form-field>
            <mat-form-field appearance="outline" class="full-width">
              <mat-label>Outputs JSON (optional)</mat-label>
              <textarea matInput [(ngModel)]="completeStepOutputs" rows="3" class="json-editor"
                        placeholder='{"key": "value"}'></textarea>
            </mat-form-field>
            <div class="form-actions">
              <button mat-button (click)="completeStepRunId = null">Cancel</button>
              <button mat-raised-button color="primary" (click)="completeHumanStep(run)"
                      [disabled]="completingStep || !completeStepId.trim() || !completeStepBy.trim()">
                <mat-icon>check</mat-icon> {{ completingStep ? 'Completing...' : 'Complete' }}
              </button>
            </div>
          </div>

          <!-- Expanded Step Executions -->
          <div *ngIf="expandedRuns.has(run.id || '')" class="step-executions">
            <mat-divider></mat-divider>
            <div class="steps-heading">Step Executions</div>
            <div *ngIf="!run.stepExecutions?.length" class="empty-sub">No step executions recorded yet.</div>
            <div *ngFor="let step of run.stepExecutions" class="step-exec-item">
              <mat-icon [class]="'step-exec-icon step-status-' + step.status.toLowerCase()">
                {{ getStepStatusIcon(step.status) }}
              </mat-icon>
              <div class="step-exec-info">
                <span class="step-exec-name">{{ step.stepName || step.stepId }}</span>
                <span class="step-exec-time" *ngIf="step.startedAt">{{ formatDate(step.startedAt) }}</span>
              </div>
              <mat-chip-set>
                <mat-chip [class]="'step-status-chip step-status-chip-' + step.status.toLowerCase()">
                  {{ step.status }}
                </mat-chip>
              </mat-chip-set>
              <div *ngIf="step.error" class="step-error">{{ step.error }}</div>
            </div>

            <!-- Graph Node IDs -->
            <div *ngIf="run.graphNodeIds?.length" class="graph-nodes-section">
              <mat-divider></mat-divider>
              <div class="steps-heading">Knowledge Graph Nodes</div>
              <mat-chip-set>
                <mat-chip *ngFor="let gid of run.graphNodeIds" class="graph-node-chip">
                  {{ gid }}
                </mat-chip>
              </mat-chip-set>
            </div>
          </div>
        </div>
      </div>

      </ng-container>
    </div>
  `,
  styles: [`
    .runs-container { padding: 12px 0; }
    .section-toolbar { display: flex; align-items: center; gap: 8px; margin-bottom: 16px; }
    .toolbar-label { font-size: 12px; color: #aaa; margin-left: 4px; }

    .start-form {
      background: #1e1e2e; border: 1px solid rgba(255,255,255,0.08);
      border-radius: 8px; padding: 16px; margin-bottom: 16px; max-width: 700px;
    }
    .start-form h4 { margin: 0 0 12px; font-size: 14px; }
    .full-width { width: 100%; }
    .json-editor { font-family: 'JetBrains Mono', 'Fira Code', monospace; font-size: 12px; }
    .form-actions { display: flex; justify-content: flex-end; margin-top: 4px; }
    .section-divider { margin: 16px 0; }

    .loading-state { display: flex; align-items: center; gap: 8px; padding: 24px; color: #aaa; font-size: 13px; }
    .spin { animation: spin 1s linear infinite; }
    @keyframes spin { from { transform: rotate(0deg); } to { transform: rotate(360deg); } }

    .empty-state { text-align: center; padding: 60px 20px; color: #999; }
    .empty-icon { font-size: 56px; width: 56px; height: 56px; color: #555; display: block; margin: 0 auto 12px; }

    .runs-list { display: flex; flex-direction: column; gap: 8px; }
    .run-card {
      background: #1e1e2e; border: 1px solid rgba(255,255,255,0.08); border-radius: 8px;
      overflow: hidden;
    }
    .run-header {
      display: flex; align-items: center; gap: 12px; padding: 12px 14px;
      cursor: pointer;
    }
    .run-header:hover { background: rgba(255,255,255,0.03); }

    .run-status-icon mat-icon { font-size: 22px; width: 22px; height: 22px; }
    .status-icon { color: #666; }
    .status-running { color: #90caf9 !important; }
    .status-completed { color: #81c784 !important; }
    .status-failed { color: #ef5350 !important; }
    .status-pending_approval { color: #ffb74d !important; }
    .status-paused { color: #ce93d8 !important; }

    .run-info { flex: 1; min-width: 0; }
    .run-id { display: block; font-weight: 500; font-size: 13px; font-family: monospace; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
    .run-process { font-size: 11px; color: #888; }
    .run-progress-info { font-size: 12px; color: #aaa; white-space: nowrap; }

    .status-chip { font-size: 11px !important; min-height: 22px !important; }
    .status-chip.status-running { background: rgba(144,202,249,0.15) !important; color: #90caf9 !important; }
    .status-chip.status-completed { background: rgba(129,199,132,0.15) !important; color: #81c784 !important; }
    .status-chip.status-failed { background: rgba(239,83,80,0.15) !important; color: #ef5350 !important; }
    .status-chip.status-pending_approval { background: rgba(255,183,77,0.15) !important; color: #ffb74d !important; }

    .expand-icon { color: #666; font-size: 20px; width: 20px; height: 20px; flex-shrink: 0; }

    .run-meta {
      display: flex; gap: 16px; padding: 4px 14px 10px; font-size: 11px; color: #777; flex-wrap: wrap;
    }
    .pending-approvals { display: flex; align-items: center; gap: 4px; color: #ffb74d; }
    .pending-approvals mat-icon { font-size: 14px; width: 14px; height: 14px; }
    .duration-badge { display: flex; align-items: center; gap: 2px; color: #999; }
    .duration-badge mat-icon { font-size: 14px; width: 14px; height: 14px; }
    .active-timer { color: #90caf9; }

    mat-progress-bar { height: 3px !important; }
    .progress-bar-running ::ng-deep .mdc-linear-progress__bar-inner { border-color: #90caf9 !important; }
    .progress-bar-completed ::ng-deep .mdc-linear-progress__bar-inner { border-color: #81c784 !important; }
    .progress-bar-failed ::ng-deep .mdc-linear-progress__bar-inner { border-color: #ef5350 !important; }

    .step-executions { padding: 12px 14px; }
    .steps-heading { font-size: 12px; font-weight: 600; color: #90caf9; text-transform: uppercase; letter-spacing: 0.5px; margin: 8px 0 10px; }
    .step-exec-item {
      display: flex; align-items: center; gap: 10px; padding: 6px 0;
      border-bottom: 1px solid rgba(255,255,255,0.04); flex-wrap: wrap;
    }
    .step-exec-item:last-child { border-bottom: none; }
    .step-exec-icon { font-size: 18px; width: 18px; height: 18px; flex-shrink: 0; }
    .step-status-completed { color: #81c784; }
    .step-status-running { color: #90caf9; }
    .step-status-failed { color: #ef5350; }
    .step-status-pending { color: #ffb74d; }
    .step-status-skipped { color: #777; }
    .step-exec-info { flex: 1; min-width: 0; }
    .step-exec-name { display: block; font-size: 13px; font-weight: 500; }
    .step-exec-time { font-size: 11px; color: #666; }
    .step-error { width: 100%; padding: 4px 0 4px 28px; font-size: 11px; color: #ef5350; }

    .step-status-chip { font-size: 10px !important; min-height: 20px !important; }
    .step-status-chip-completed { background: rgba(129,199,132,0.15) !important; color: #81c784 !important; }
    .step-status-chip-running { background: rgba(144,202,249,0.15) !important; color: #90caf9 !important; }
    .step-status-chip-failed { background: rgba(239,83,80,0.15) !important; color: #ef5350 !important; }
    .step-status-chip-pending { background: rgba(255,183,77,0.15) !important; color: #ffb74d !important; }
    .step-status-chip-awaiting_approval { background: rgba(255,183,77,0.15) !important; color: #ffb74d !important; }

    .empty-sub { font-size: 13px; color: #666; padding: 4px 0 8px; }

    /* Step Timeline */
    .step-timeline {
      display: flex; align-items: center; padding: 8px 14px 4px;
      overflow-x: auto; gap: 0;
    }
    .timeline-node-group { display: flex; align-items: center; }
    .timeline-node {
      width: 12px; height: 12px; border-radius: 50%; flex-shrink: 0;
      border: 2px solid #555; background: transparent;
    }
    .timeline-completed { background: #81c784; border-color: #81c784; }
    .timeline-running { background: #90caf9; border-color: #90caf9; animation: pulse 1.5s infinite; }
    .timeline-failed { background: #ef5350; border-color: #ef5350; }
    .timeline-awaiting_approval { background: #ffb74d; border-color: #ffb74d; }
    .timeline-pending, .timeline-skipped { background: transparent; border-color: #555; }
    @keyframes pulse { 0%, 100% { opacity: 1; } 50% { opacity: 0.5; } }
    .timeline-connector { width: 20px; height: 2px; background: #555; flex-shrink: 0; }
    .connector-completed { background: #81c784; }

    /* Run Actions */
    .run-actions { display: flex; gap: 8px; padding: 6px 14px 10px; }
    .run-actions button { font-size: 12px; }

    /* Complete Step Form */
    .complete-step-form { padding: 12px 14px; }
    .form-heading { font-size: 12px; font-weight: 600; color: #90caf9; text-transform: uppercase; letter-spacing: 0.5px; margin: 8px 0 12px; }

    /* Graph Nodes */
    .graph-nodes-section { margin-top: 8px; }
    .graph-node-chip { background: rgba(206,147,216,0.15) !important; color: #ce93d8 !important; font-size: 11px !important; min-height: 22px !important; font-family: monospace; }

    /* Risk chips */
    .risk-chip { font-size: 10px !important; min-height: 20px !important; font-weight: 600 !important; }
    .risk-chip-icon { font-size: 12px !important; width: 12px !important; height: 12px !important; margin-right: 2px; }
    .risk-chip-critical, .risk-chip-high { background: rgba(239,83,80,0.2) !important; color: #ef5350 !important; }
    .risk-chip-medium { background: rgba(255,183,77,0.2) !important; color: #ffb74d !important; }
    .risk-chip-low, .risk-chip-info { background: rgba(129,199,132,0.15) !important; color: #81c784 !important; }
    .risk-loading-inline { font-size: 11px; color: #888; padding: 0 8px; }

    /* Button Toggle Group */
    mat-button-toggle-group { font-size: 12px; }
  `]
})
export class ProcessRunsComponent implements OnInit, OnDestroy {
  runs: WorkflowRun[] = [];
  loading = false;
  showStartForm = false;
  selectedRunId: string | null = null;
  starting = false;
  startProcessId = '';
  startInitialData = '';
  expandedRuns = new Set<string>();
  viewFilter: 'active' | 'all' = 'active';

  // Complete step form state
  completeStepRunId: string | null = null;
  completeStepId = '';
  completeStepBy = 'ui-user';
  completeStepOutputs = '';
  completingStep = false;

  // Risk assessment cache per run
  runRiskLevels: Record<string, { level: string; score: number }> = {};
  riskLoadingRuns: Set<string> = new Set();

  private destroy$ = new Subject<void>();

  constructor(
    private processEngineService: ProcessEngineService,
    private processAttributionService: ProcessAttributionService,
    private snackBar: MatSnackBar
  ) {}

  ngOnInit(): void {
    this.loadRuns();
    // Auto-refresh every 10 seconds
    interval(10000).pipe(takeUntil(this.destroy$)).subscribe(() => this.loadRuns());
  }

  ngOnDestroy(): void {
    this.destroy$.next();
    this.destroy$.complete();
  }

  loadRuns(): void {
    if (!this.loading) {
      this.loading = true;
    }
    const obs = this.viewFilter === 'all'
      ? this.processEngineService.listAllRuns()
      : this.processEngineService.listActiveRuns();
    obs.subscribe({
      next: (runs) => {
        this.runs = runs;
        this.loading = false;
      },
      error: (err) => {
        this.snackBar.open('Failed to load runs: ' + (err.error?.message || err.message), 'Close', { duration: 4000 });
        this.loading = false;
      }
    });
  }

  startRun(): void {
    let initialData: Record<string, any> = {};
    if (this.startInitialData.trim()) {
      try {
        initialData = JSON.parse(this.startInitialData);
      } catch {
        this.snackBar.open('Invalid initial data JSON', 'Close', { duration: 3000 });
        return;
      }
    }
    this.starting = true;
    this.processEngineService.startRun(this.startProcessId, initialData).subscribe({
      next: (run) => {
        this.snackBar.open('Run started: ' + run.id, 'Close', { duration: 3000 });
        this.runs = [...this.runs, run];
        this.starting = false;
        this.showStartForm = false;
        this.startProcessId = '';
        this.startInitialData = '';
      },
      error: (err) => {
        this.snackBar.open('Failed to start run: ' + (err.error?.message || err.message), 'Close', { duration: 4000 });
        this.starting = false;
      }
    });
  }

  cancelRun(run: WorkflowRun): void {
    if (!run.id) return;
    this.processEngineService.cancelRun(run.id).subscribe({
      next: (updated) => {
        const idx = this.runs.findIndex(r => r.id === updated.id);
        if (idx >= 0) this.runs[idx] = updated;
        this.snackBar.open('Run cancelled', 'Close', { duration: 3000 });
      },
      error: (err) => {
        this.snackBar.open('Cancel failed: ' + (err.error?.message || err.message), 'Close', { duration: 4000 });
      }
    });
  }

  promptCompleteStep(run: WorkflowRun): void {
    this.completeStepRunId = run.id || null;
    this.completeStepId = '';
    this.completeStepOutputs = '';
    // Pre-fill step ID from first AWAITING_APPROVAL step
    const awaiting = (run.stepExecutions || []).find(s => s.status === 'AWAITING_APPROVAL');
    if (awaiting) {
      this.completeStepId = awaiting.stepId;
    }
  }

  completeHumanStep(run: WorkflowRun): void {
    if (!run.id) return;
    let outputs: Record<string, any> = {};
    if (this.completeStepOutputs.trim()) {
      try {
        outputs = JSON.parse(this.completeStepOutputs);
      } catch {
        this.snackBar.open('Invalid outputs JSON', 'Close', { duration: 3000 });
        return;
      }
    }
    this.completingStep = true;
    this.processEngineService.completeHumanStep(run.id, this.completeStepId, this.completeStepBy, outputs).subscribe({
      next: (updated) => {
        const idx = this.runs.findIndex(r => r.id === updated.id);
        if (idx >= 0) this.runs[idx] = updated;
        this.snackBar.open('Step completed', 'Close', { duration: 3000 });
        this.completingStep = false;
        this.completeStepRunId = null;
      },
      error: (err) => {
        this.snackBar.open('Complete step failed: ' + (err.error?.message || err.message), 'Close', { duration: 4000 });
        this.completingStep = false;
      }
    });
  }

  isActiveRun(run: WorkflowRun): boolean {
    const s = run.status || '';
    return s === 'RUNNING' || s === 'PAUSED_FOR_APPROVAL' || s === 'PAUSED_FOR_HUMAN';
  }

  toggleExpand(run: WorkflowRun): void {
    const key = run.id || '';
    if (this.expandedRuns.has(key)) {
      this.expandedRuns.delete(key);
    } else {
      this.expandedRuns.add(key);
      if (run.id) {
        this.processEngineService.getRun(run.id).subscribe({
          next: (detail) => {
            const idx = this.runs.findIndex(r => r.id === detail.id);
            if (idx >= 0) this.runs[idx] = detail;
          },
          error: () => {}
        });
      }
    }
  }

  countCompleted(run: WorkflowRun): number {
    return (run.stepExecutions || []).filter(s => s.status === 'COMPLETED').length;
  }

  countTotal(run: WorkflowRun): number {
    return (run.stepExecutions || []).length;
  }

  getProgressPercent(run: WorkflowRun): number {
    const total = this.countTotal(run);
    if (total === 0) return run.status === 'COMPLETED' ? 100 : 0;
    return Math.round((this.countCompleted(run) / total) * 100);
  }

  getStatusIcon(status?: string): string {
    switch (status) {
      case 'COMPLETED': return 'check_circle';
      case 'FAILED': return 'error';
      case 'CANCELLED': return 'cancel';
      case 'PAUSED_FOR_APPROVAL': return 'approval';
      case 'PAUSED_FOR_HUMAN': return 'person';
      case 'RUNNING': return 'play_circle';
      default: return 'hourglass_empty';
    }
  }

  getStepStatusIcon(status: string): string {
    switch (status) {
      case 'COMPLETED': return 'check_circle';
      case 'FAILED': return 'error';
      case 'RUNNING': return 'play_circle';
      case 'AWAITING_APPROVAL': return 'approval';
      case 'PENDING': return 'hourglass_empty';
      case 'SKIPPED': return 'skip_next';
      default: return 'radio_button_unchecked';
    }
  }

  getElapsed(startedAt: string): string {
    const sec = Math.floor((Date.now() - new Date(startedAt).getTime()) / 1000);
    if (sec < 60) return `${sec}s`;
    if (sec < 3600) return `${Math.floor(sec / 60)}m ${sec % 60}s`;
    return `${Math.floor(sec / 3600)}h ${Math.floor((sec % 3600) / 60)}m`;
  }

  getDuration(start: string, end: string): string {
    const ms = new Date(end).getTime() - new Date(start).getTime();
    if (ms < 1000) return `${ms}ms`;
    const sec = Math.floor(ms / 1000);
    if (sec < 60) return `${sec}s`;
    return `${Math.floor(sec / 60)}m ${sec % 60}s`;
  }

  loadRunRisk(run: WorkflowRun): void {
    if (!run.id) return;
    this.riskLoadingRuns.add(run.id);
    this.processAttributionService.assessRunRisk(run.id, false)
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: (result) => {
          this.runRiskLevels[run.id!] = { level: result.riskLevel, score: result.overallRiskScore };
          this.riskLoadingRuns.delete(run.id!);
        },
        error: () => {
          this.riskLoadingRuns.delete(run.id!);
        }
      });
  }

  onNavigateToGraph(nodeId: string): void {
    // The parent dashboard component could handle tab switching to the knowledge graph
    // For now, just log/display
    this.snackBar.open('Open Knowledge Graph node: ' + nodeId, 'OK', { duration: 3000 });
  }

  formatDate(dateStr: string): string {
    try {
      return new Date(dateStr).toLocaleString();
    } catch {
      return dateStr;
    }
  }
}
