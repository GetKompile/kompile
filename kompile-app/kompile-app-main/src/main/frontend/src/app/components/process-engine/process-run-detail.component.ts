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

import { Component, Input, Output, EventEmitter, OnChanges, SimpleChanges, OnDestroy } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { MatIconModule } from '@angular/material/icon';
import { MatButtonModule } from '@angular/material/button';
import { MatChipsModule } from '@angular/material/chips';
import { MatTooltipModule } from '@angular/material/tooltip';
import { MatExpansionModule } from '@angular/material/expansion';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatSelectModule } from '@angular/material/select';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';
import { Subject, interval, takeUntil } from 'rxjs';
import { ProcessEngineService, WorkflowRun, StepExecution, ApprovalResponse, ControlAttestation } from '../../services/process-engine.service';
import { GraphNodePopoverComponent } from './graph-node-popover.component';

@Component({
  standalone: true,
  selector: 'app-process-run-detail',
  imports: [
    CommonModule, FormsModule,
    MatIconModule, MatButtonModule, MatChipsModule, MatTooltipModule,
    MatExpansionModule, MatProgressBarModule, MatSelectModule,
    MatFormFieldModule, MatInputModule, MatSnackBarModule,
    GraphNodePopoverComponent
  ],
  template: `
    <div class="detail-container" *ngIf="run">
      <!-- Header -->
      <div class="detail-header">
        <div class="header-top">
          <button mat-icon-button (click)="closed.emit()" matTooltip="Back to list">
            <mat-icon>arrow_back</mat-icon>
          </button>
          <h3 class="run-id">{{ run.id }}</h3>
          <mat-chip class="status-chip" [ngClass]="'status-' + run.status?.toLowerCase()">
            <mat-icon class="chip-icon">{{ statusIcon(run.status) }}</mat-icon>
            {{ run.status }}
          </mat-chip>
          <div class="header-spacer"></div>
          <button mat-stroked-button color="warn"
                  *ngIf="isActive(run.status)"
                  (click)="cancelRun()">
            <mat-icon>cancel</mat-icon> Cancel
          </button>
          <button mat-icon-button (click)="refresh()" matTooltip="Refresh">
            <mat-icon>refresh</mat-icon>
          </button>
        </div>

        <div class="header-meta">
          <span class="meta-item">
            <mat-icon class="meta-icon">description</mat-icon>
            Definition: {{ run.processDefinitionId }} v{{ run.processVersion }}
          </span>
          <span class="meta-item">
            <mat-icon class="meta-icon">schedule</mat-icon>
            Started: {{ run.startedAt | date:'medium' }}
          </span>
          <span class="meta-item" *ngIf="run.completedAt">
            <mat-icon class="meta-icon">check_circle</mat-icon>
            Completed: {{ run.completedAt | date:'medium' }}
          </span>
          <span class="meta-item" *ngIf="!run.completedAt && run.startedAt">
            <mat-icon class="meta-icon">timer</mat-icon>
            Elapsed: {{ getElapsed(run.startedAt) }}
          </span>
        </div>

        <!-- Progress bar -->
        <mat-progress-bar
          mode="determinate"
          [value]="progressPercent"
          [color]="run.status === 'FAILED' ? 'warn' : 'primary'"
        ></mat-progress-bar>
        <span class="progress-label">{{ completedSteps }} / {{ totalSteps }} steps</span>
      </div>

      <!-- Section 2: Step Pipeline -->
      <div class="section-card">
        <h4 class="section-title">
          <mat-icon>account_tree</mat-icon>
          Step Pipeline
        </h4>
        <div class="step-timeline" *ngIf="run.stepExecutions?.length">
          <div *ngFor="let step of run.stepExecutions; let i = index; let last = last"
               class="timeline-node-group">
            <div [class]="'timeline-node timeline-' + step.status?.toLowerCase()"
                 [matTooltip]="step.stepName + ' — ' + step.status"
                 (click)="selectedStepIndex = (selectedStepIndex === i ? -1 : i)">
              <mat-icon class="node-icon">{{ stepStatusIcon(step.status) }}</mat-icon>
            </div>
            <div class="timeline-connector" *ngIf="!last"
                 [class.connector-completed]="step.status === 'COMPLETED'">
            </div>
          </div>
        </div>
      </div>

      <!-- Section 3: Step Detail -->
      <div class="section-card" *ngIf="selectedStepIndex >= 0 && selectedStep">
        <div class="step-detail-header">
          <h4 class="section-title">
            <mat-icon>info</mat-icon>
            {{ selectedStep.stepName || selectedStep.stepId }}
          </h4>
          <mat-chip class="status-chip small-chip" [ngClass]="'status-' + selectedStep.status?.toLowerCase()">
            {{ selectedStep.status }}
          </mat-chip>
        </div>

        <!-- Execution info -->
        <div class="step-meta-grid">
          <div class="step-meta" *ngIf="selectedStep.executedBy">
            <span class="meta-label">Executed By</span>
            <span class="meta-val">{{ selectedStep.executedBy }}</span>
          </div>
          <div class="step-meta" *ngIf="selectedStep.startedAt">
            <span class="meta-label">Started</span>
            <span class="meta-val">{{ selectedStep.startedAt | date:'medium' }}</span>
          </div>
          <div class="step-meta" *ngIf="selectedStep.completedAt">
            <span class="meta-label">Completed</span>
            <span class="meta-val">{{ selectedStep.completedAt | date:'medium' }}</span>
          </div>
          <div class="step-meta" *ngIf="selectedStep.startedAt && selectedStep.completedAt">
            <span class="meta-label">Duration</span>
            <span class="meta-val">{{ getDuration(selectedStep.startedAt, selectedStep.completedAt) }}</span>
          </div>
        </div>

        <!-- Error -->
        <div class="step-error" *ngIf="selectedStep.error">
          <mat-icon>error</mat-icon>
          <pre>{{ selectedStep.error }}</pre>
        </div>

        <!-- Inputs -->
        <div class="io-section" *ngIf="selectedStep.inputs && hasKeys(selectedStep.inputs)">
          <button mat-button class="io-toggle" (click)="showInputs = !showInputs">
            <mat-icon>{{ showInputs ? 'expand_less' : 'expand_more' }}</mat-icon>
            Inputs
            <span class="hash-badge" *ngIf="selectedStep.inputHash">
              SHA: {{ selectedStep.inputHash | slice:0:8 }}
            </span>
          </button>
          <pre class="io-json" *ngIf="showInputs">{{ selectedStep.inputs | json }}</pre>
        </div>

        <!-- Outputs -->
        <div class="io-section" *ngIf="selectedStep.outputs && hasKeys(selectedStep.outputs)">
          <button mat-button class="io-toggle" (click)="showOutputs = !showOutputs">
            <mat-icon>{{ showOutputs ? 'expand_less' : 'expand_more' }}</mat-icon>
            Outputs
            <span class="hash-badge" *ngIf="selectedStep.outputHash">
              SHA: {{ selectedStep.outputHash | slice:0:8 }}
            </span>
          </button>
          <pre class="io-json" *ngIf="showOutputs">{{ selectedStep.outputs | json }}</pre>
        </div>

        <!-- Evidence -->
        <div class="evidence-section" *ngIf="selectedStep.evidenceReliedOn?.length">
          <span class="meta-label">Evidence Relied On</span>
          <mat-chip-set>
            <mat-chip *ngFor="let e of selectedStep.evidenceReliedOn" class="evidence-chip">
              <mat-icon>source</mat-icon> {{ e }}
            </mat-chip>
          </mat-chip-set>
        </div>

        <!-- Graph Nodes -->
        <div class="graph-nodes-section" *ngIf="selectedStep.graphNodeIds?.length">
          <span class="meta-label">Knowledge Graph Nodes</span>
          <div class="graph-chips">
            <span *ngFor="let gid of selectedStep.graphNodeIds" class="graph-chip-wrapper">
              <mat-chip class="graph-chip" (click)="togglePopover(gid)">
                <mat-icon>hub</mat-icon> {{ graphNodeLabels[gid] || gid | slice:0:12 }}
              </mat-chip>
              <app-graph-node-popover
                [nodeId]="gid"
                [visible]="activePopoverNodeId === gid"
                (closed)="activePopoverNodeId = null"
                (openInGraph)="onOpenInGraph($event)">
              </app-graph-node-popover>
            </span>
          </div>
        </div>

        <!-- Control Attestations for this step -->
        <div class="attestations-section" *ngIf="stepAttestations.length > 0">
          <span class="meta-label">Control Attestations</span>
          <div *ngFor="let att of stepAttestations" class="attestation-row">
            <mat-icon [class]="att.passed ? 'att-pass' : 'att-fail'">
              {{ att.passed ? 'check_circle' : 'cancel' }}
            </mat-icon>
            <span class="att-id">{{ att.controlId | slice:0:12 }}</span>
            <code class="att-expr">{{ att.expressionEvaluated }}</code>
          </div>
        </div>

        <!-- Inline approval form (if awaiting approval) -->
        <div class="approval-inline" *ngIf="selectedStep.status === 'AWAITING_APPROVAL' && stepApprovalRequest">
          <h5>Pending Approval</h5>
          <div class="approval-meta" *ngIf="stepApprovalRequest.assignedTo">
            Assigned to: <strong>{{ stepApprovalRequest.assignedTo }}</strong>
          </div>
          <mat-form-field appearance="outline" class="approval-field">
            <mat-label>Action</mat-label>
            <mat-select [(value)]="approvalAction">
              <mat-option value="APPROVE">Approve</mat-option>
              <mat-option value="REJECT">Reject</mat-option>
              <mat-option value="ESCALATE">Escalate</mat-option>
              <mat-option value="DELEGATE">Delegate</mat-option>
              <mat-option value="REQUEST_INFO">Request Info</mat-option>
            </mat-select>
          </mat-form-field>
          <mat-form-field appearance="outline" class="approval-field">
            <mat-label>Comment</mat-label>
            <input matInput [(ngModel)]="approvalComment">
          </mat-form-field>
          <button mat-raised-button color="primary" (click)="submitApproval()">
            Submit
          </button>
        </div>
      </div>

      <!-- Section 4: Run Data State -->
      <div class="section-card">
        <button mat-button class="section-toggle" (click)="showRunData = !showRunData">
          <mat-icon>{{ showRunData ? 'expand_less' : 'expand_more' }}</mat-icon>
          <mat-icon>data_object</mat-icon>
          Run Data State
        </button>
        <pre class="io-json run-data-json" *ngIf="showRunData">{{ run.runData | json }}</pre>
      </div>

      <!-- Section 4b: Metrics -->
      <div class="section-card" *ngIf="run.metrics && hasKeys(run.metrics)">
        <button mat-button class="section-toggle" (click)="showMetrics = !showMetrics">
          <mat-icon>{{ showMetrics ? 'expand_less' : 'expand_more' }}</mat-icon>
          <mat-icon>analytics</mat-icon>
          Metrics
        </button>
        <pre class="io-json" *ngIf="showMetrics">{{ run.metrics | json }}</pre>
      </div>

      <!-- Section 5: All Graph Nodes (run-level) -->
      <div class="section-card" *ngIf="run.graphNodeIds?.length">
        <h4 class="section-title">
          <mat-icon>hub</mat-icon>
          All Knowledge Graph Nodes ({{ run.graphNodeIds!.length }})
        </h4>
        <div class="graph-chips">
          <span *ngFor="let gid of run.graphNodeIds" class="graph-chip-wrapper">
            <mat-chip class="graph-chip" (click)="togglePopover(gid)">
              <mat-icon>hub</mat-icon> {{ graphNodeLabels[gid] || gid | slice:0:12 }}
            </mat-chip>
            <app-graph-node-popover
              [nodeId]="gid"
              [visible]="activePopoverNodeId === gid"
              (closed)="activePopoverNodeId = null"
              (openInGraph)="onOpenInGraph($event)">
            </app-graph-node-popover>
          </span>
        </div>
      </div>

      <!-- Section 6: Pending Approvals -->
      <div class="section-card" *ngIf="run.pendingApprovals?.length">
        <h4 class="section-title">
          <mat-icon>approval</mat-icon>
          Pending Approvals ({{ run.pendingApprovals!.length }})
        </h4>
        <div *ngFor="let apr of run.pendingApprovals" class="approval-card">
          <div class="approval-card-header">
            <strong>{{ apr.stepName || apr.stepId }}</strong>
            <mat-chip class="small-chip status-pending">{{ apr.status }}</mat-chip>
          </div>
          <div class="approval-card-meta" *ngIf="apr.assignedTo">
            Assigned to: {{ apr.assignedTo }}
          </div>
          <div class="approval-card-meta" *ngIf="apr.slaDeadline">
            Deadline: {{ apr.slaDeadline | date:'medium' }}
          </div>
        </div>
      </div>

      <!-- Section 7: Control Results -->
      <div class="section-card" *ngIf="run.controlResults?.length">
        <h4 class="section-title">
          <mat-icon>verified_user</mat-icon>
          Control Results ({{ run.controlResults!.length }})
        </h4>
        <div *ngFor="let att of run.controlResults" class="attestation-row">
          <mat-icon [class]="att.passed ? 'att-pass' : 'att-fail'">
            {{ att.passed ? 'check_circle' : 'cancel' }}
          </mat-icon>
          <span class="att-id">{{ att.controlId | slice:0:12 }}</span>
          <code class="att-expr">{{ att.expressionEvaluated }}</code>
          <span class="att-time">{{ att.evaluatedAt | date:'shortTime' }}</span>
        </div>
      </div>
    </div>

    <!-- Loading state -->
    <div class="detail-loading" *ngIf="!run && !loadError">
      <mat-icon>hourglass_empty</mat-icon>
      Loading run details...
    </div>
    <div class="detail-error" *ngIf="loadError">
      <mat-icon>error_outline</mat-icon>
      {{ loadError }}
      <button mat-button (click)="closed.emit()">Back</button>
    </div>
  `,
  styles: [`
    .detail-container { display: flex; flex-direction: column; gap: 12px; }

    .detail-header { background: rgba(255,255,255,0.04); border-radius: 8px; padding: 12px 16px; }
    .header-top { display: flex; align-items: center; gap: 8px; margin-bottom: 8px; }
    .run-id { margin: 0; font-size: 16px; font-family: monospace; }
    .header-spacer { flex: 1; }

    .header-meta { display: flex; flex-wrap: wrap; gap: 16px; margin-bottom: 8px; font-size: 12px; color: #aaa; }
    .meta-icon { font-size: 14px; width: 14px; height: 14px; margin-right: 4px; vertical-align: middle; }
    .meta-item { display: flex; align-items: center; }

    .progress-label { font-size: 11px; color: #888; margin-top: 4px; }

    .status-chip { font-size: 11px !important; min-height: 24px !important; }
    .chip-icon { font-size: 14px !important; width: 14px !important; height: 14px !important; margin-right: 2px; }
    .small-chip { font-size: 10px !important; min-height: 20px !important; }

    .status-running { background: rgba(144,202,249,0.2) !important; color: #90caf9 !important; }
    .status-completed { background: rgba(129,199,132,0.2) !important; color: #81c784 !important; }
    .status-failed { background: rgba(239,83,80,0.2) !important; color: #ef5350 !important; }
    .status-cancelled { background: rgba(158,158,158,0.2) !important; color: #9e9e9e !important; }
    .status-paused_for_approval, .status-paused_for_human, .status-awaiting_approval {
      background: rgba(255,183,77,0.2) !important; color: #ffb74d !important;
    }
    .status-pending { background: rgba(255,255,255,0.08) !important; color: #999 !important; }

    .section-card {
      background: rgba(255,255,255,0.03); border: 1px solid rgba(255,255,255,0.08);
      border-radius: 8px; padding: 12px 16px;
    }
    .section-title { margin: 0 0 8px; font-size: 14px; display: flex; align-items: center; gap: 6px; }
    .section-title mat-icon { font-size: 18px; width: 18px; height: 18px; }
    .section-toggle {
      width: 100%; text-align: left; font-size: 14px; font-weight: 500;
      display: flex; align-items: center; gap: 6px;
    }

    /* Timeline */
    .step-timeline { display: flex; align-items: center; overflow-x: auto; padding: 8px 0; }
    .timeline-node-group { display: flex; align-items: center; }
    .timeline-node {
      width: 36px; height: 36px; border-radius: 50%; display: flex; align-items: center;
      justify-content: center; cursor: pointer; border: 2px solid transparent;
      transition: transform 0.15s;
    }
    .timeline-node:hover { transform: scale(1.15); }
    .node-icon { font-size: 18px; width: 18px; height: 18px; }

    .timeline-completed { background: rgba(129,199,132,0.25); color: #81c784; border-color: #81c784; }
    .timeline-running { background: rgba(144,202,249,0.25); color: #90caf9; border-color: #90caf9; animation: pulse 1.5s infinite; }
    .timeline-awaiting_approval { background: rgba(255,183,77,0.25); color: #ffb74d; border-color: #ffb74d; }
    .timeline-failed { background: rgba(239,83,80,0.25); color: #ef5350; border-color: #ef5350; }
    .timeline-pending { background: rgba(255,255,255,0.06); color: #666; border-color: rgba(255,255,255,0.15); }
    .timeline-skipped { background: rgba(158,158,158,0.15); color: #9e9e9e; border-color: #9e9e9e; }

    .timeline-connector { width: 24px; height: 2px; background: rgba(255,255,255,0.15); }
    .connector-completed { background: #81c784; }

    @keyframes pulse {
      0%, 100% { box-shadow: 0 0 0 0 rgba(144,202,249,0.4); }
      50% { box-shadow: 0 0 0 6px rgba(144,202,249,0); }
    }

    /* Step detail */
    .step-detail-header { display: flex; align-items: center; gap: 8px; margin-bottom: 8px; }
    .step-meta-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(180px, 1fr)); gap: 6px 16px; margin-bottom: 8px; }
    .step-meta { display: flex; flex-direction: column; }
    .meta-label { font-size: 10px; color: #888; text-transform: uppercase; letter-spacing: 0.5px; }
    .meta-val { font-size: 12px; color: #ccc; }

    .step-error { display: flex; align-items: flex-start; gap: 6px; color: #ef5350; margin-bottom: 8px; }
    .step-error pre { margin: 0; font-size: 12px; white-space: pre-wrap; }

    .io-section, .evidence-section, .graph-nodes-section, .attestations-section { margin-top: 8px; }
    .io-toggle { font-size: 13px; display: flex; align-items: center; gap: 4px; }
    .hash-badge { font-size: 10px; color: #888; font-family: monospace; margin-left: 8px; }
    .io-json {
      margin: 4px 0 0; padding: 8px; background: rgba(0,0,0,0.3); border-radius: 4px;
      font-size: 11px; color: #bbb; white-space: pre-wrap; word-break: break-word;
      max-height: 200px; overflow-y: auto;
    }
    .run-data-json { margin-top: 8px; }

    .evidence-chip mat-icon { font-size: 14px !important; width: 14px !important; height: 14px !important; }

    .graph-chips { display: flex; flex-wrap: wrap; gap: 4px; margin-top: 4px; }
    .graph-chip-wrapper { position: relative; display: inline-block; }
    .graph-chip { cursor: pointer; font-size: 11px !important; background: rgba(206,147,216,0.15) !important; color: #ce93d8 !important; }
    .graph-chip mat-icon { font-size: 14px !important; width: 14px !important; height: 14px !important; }

    .attestation-row { display: flex; align-items: center; gap: 8px; padding: 4px 0; font-size: 12px; }
    .att-pass { color: #81c784; font-size: 18px; }
    .att-fail { color: #ef5350; font-size: 18px; }
    .att-id { font-family: monospace; color: #aaa; }
    .att-expr { font-size: 11px; color: #888; background: rgba(0,0,0,0.2); padding: 2px 6px; border-radius: 3px; }
    .att-time { color: #888; margin-left: auto; }

    .approval-card {
      background: rgba(255,183,77,0.08); border: 1px solid rgba(255,183,77,0.2);
      border-radius: 6px; padding: 8px 12px; margin-bottom: 8px;
    }
    .approval-card-header { display: flex; align-items: center; gap: 8px; }
    .approval-card-meta { font-size: 12px; color: #aaa; margin-top: 4px; }

    .approval-inline { margin-top: 12px; padding: 12px; background: rgba(255,183,77,0.08); border-radius: 6px; }
    .approval-inline h5 { margin: 0 0 8px; }
    .approval-meta { font-size: 12px; color: #aaa; margin-bottom: 8px; }
    .approval-field { width: 100%; margin-bottom: 4px; }

    .detail-loading, .detail-error {
      display: flex; align-items: center; gap: 8px; padding: 24px; color: #aaa;
    }
    .detail-error { color: #ef5350; }
  `]
})
export class ProcessRunDetailComponent implements OnChanges, OnDestroy {
  @Input() runId: string | null = null;
  @Output() closed = new EventEmitter<void>();
  @Output() navigateToGraph = new EventEmitter<string>();

  run: WorkflowRun | null = null;
  loadError: string | null = null;
  selectedStepIndex = -1;
  showInputs = false;
  showOutputs = false;
  showRunData = false;
  showMetrics = false;
  activePopoverNodeId: string | null = null;

  // Approval form state
  approvalAction: string = 'APPROVE';
  approvalComment = '';

  // Graph node label cache
  graphNodeLabels: Record<string, string> = {};

  private destroy$ = new Subject<void>();

  constructor(
    private processEngineService: ProcessEngineService,
    private snackBar: MatSnackBar
  ) {}

  ngOnChanges(changes: SimpleChanges): void {
    if (changes['runId'] && this.runId) {
      this.loadRun();
      this.startAutoRefresh();
    }
  }

  ngOnDestroy(): void {
    this.destroy$.next();
    this.destroy$.complete();
  }

  get selectedStep(): StepExecution | null {
    if (!this.run?.stepExecutions || this.selectedStepIndex < 0) return null;
    return this.run.stepExecutions[this.selectedStepIndex] || null;
  }

  get totalSteps(): number {
    return this.run?.stepExecutions?.length || 0;
  }

  get completedSteps(): number {
    if (!this.run?.stepExecutions) return 0;
    return this.run.stepExecutions.filter(s => s.status === 'COMPLETED' || s.status === 'SKIPPED').length;
  }

  get progressPercent(): number {
    return this.totalSteps > 0 ? (this.completedSteps / this.totalSteps) * 100 : 0;
  }

  get stepAttestations(): ControlAttestation[] {
    if (!this.run?.controlResults || !this.selectedStep) return [];
    return this.run.controlResults.filter(a => {
      // Match by looking at the step execution context
      return true; // Show all attestations in the step panel for now
    });
  }

  get stepApprovalRequest(): any {
    if (!this.run?.pendingApprovals || !this.selectedStep) return null;
    return this.run.pendingApprovals.find(a => a.stepId === this.selectedStep!.stepId);
  }

  loadRun(): void {
    if (!this.runId) return;
    this.processEngineService.getRun(this.runId).subscribe({
      next: (run) => {
        this.run = run;
        this.loadError = null;
        this.resolveGraphNodeLabels();
      },
      error: () => {
        this.loadError = 'Failed to load run: ' + this.runId;
      }
    });
  }

  refresh(): void {
    this.loadRun();
  }

  cancelRun(): void {
    if (!this.run?.id) return;
    this.processEngineService.cancelRun(this.run.id).subscribe({
      next: (run) => {
        this.run = run;
        this.snackBar.open('Run cancelled', 'OK', { duration: 3000 });
      },
      error: () => {
        this.snackBar.open('Failed to cancel run', 'OK', { duration: 3000 });
      }
    });
  }

  submitApproval(): void {
    if (!this.stepApprovalRequest) return;
    const response: ApprovalResponse = {
      requestId: this.stepApprovalRequest.id,
      respondedBy: 'user',
      action: this.approvalAction as any,
      comment: this.approvalComment || undefined
    };
    this.processEngineService.submitApproval(this.stepApprovalRequest.id, response).subscribe({
      next: (run) => {
        this.run = run;
        this.approvalComment = '';
        this.snackBar.open('Approval submitted', 'OK', { duration: 3000 });
      },
      error: () => {
        this.snackBar.open('Failed to submit approval', 'OK', { duration: 3000 });
      }
    });
  }

  togglePopover(nodeId: string): void {
    this.activePopoverNodeId = this.activePopoverNodeId === nodeId ? null : nodeId;
  }

  onOpenInGraph(nodeId: string): void {
    this.activePopoverNodeId = null;
    this.navigateToGraph.emit(nodeId);
  }

  isActive(status?: string): boolean {
    return status === 'RUNNING' || status === 'PAUSED_FOR_APPROVAL' || status === 'PAUSED_FOR_HUMAN';
  }

  statusIcon(status?: string): string {
    switch (status) {
      case 'RUNNING': return 'play_circle';
      case 'COMPLETED': return 'check_circle';
      case 'FAILED': return 'error';
      case 'CANCELLED': return 'cancel';
      case 'PAUSED_FOR_APPROVAL': return 'approval';
      case 'PAUSED_FOR_HUMAN': return 'person';
      default: return 'help';
    }
  }

  stepStatusIcon(status?: string): string {
    switch (status) {
      case 'COMPLETED': return 'check';
      case 'RUNNING': return 'play_arrow';
      case 'AWAITING_APPROVAL': return 'hourglass_top';
      case 'FAILED': return 'close';
      case 'SKIPPED': return 'skip_next';
      case 'PENDING': return 'radio_button_unchecked';
      default: return 'help';
    }
  }

  hasKeys(obj: any): boolean {
    return obj && Object.keys(obj).length > 0;
  }

  getElapsed(startedAt: string): string {
    const start = new Date(startedAt).getTime();
    const now = Date.now();
    const sec = Math.floor((now - start) / 1000);
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

  private startAutoRefresh(): void {
    this.destroy$.next(); // Cancel previous interval
    interval(10000)
      .pipe(takeUntil(this.destroy$))
      .subscribe(() => {
        if (this.run && this.isActive(this.run.status)) {
          this.loadRun();
        }
      });
  }

  private resolveGraphNodeLabels(): void {
    if (!this.run?.graphNodeIds) return;
    // Only resolve IDs we haven't already cached
    const unresolved = this.run.graphNodeIds.filter(id => !this.graphNodeLabels[id]);
    // Intentionally skip importing GraphService to keep this self-contained
    // Labels will show as truncated IDs; the popover shows full details on click
  }
}
