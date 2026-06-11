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

import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { MatIconModule } from '@angular/material/icon';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';
import { MatChipsModule } from '@angular/material/chips';
import { MatDividerModule } from '@angular/material/divider';
import { MatTooltipModule } from '@angular/material/tooltip';
import {
  ProcessEngineService,
  ApprovalRequest,
  ApprovalResponse
} from '../../services/process-engine.service';

type ApprovalAction = 'APPROVE' | 'APPROVE_WITH_EDITS' | 'REJECT' | 'ESCALATE' | 'DELEGATE' | 'REQUEST_INFO';

interface ApprovalState {
  request: ApprovalRequest;
  expanded: boolean;
  comment: string;
  delegateTo: string;
  submitting: boolean;
}

@Component({
  standalone: true,
  selector: 'app-process-approvals',
  imports: [
    CommonModule, FormsModule,
    MatIconModule, MatButtonModule, MatCardModule,
    MatFormFieldModule, MatInputModule, MatSnackBarModule,
    MatChipsModule, MatDividerModule, MatTooltipModule
  ],
  template: `
    <div class="approvals-container">
      <!-- Toolbar -->
      <div class="section-toolbar">
        <mat-form-field appearance="outline" class="assignee-filter">
          <mat-label>Assigned to (optional)</mat-label>
          <input matInput [(ngModel)]="filterAssignee" (keyup.enter)="loadApprovals()" placeholder="user@example.com">
        </mat-form-field>
        <button mat-raised-button (click)="loadApprovals()">
          <mat-icon>search</mat-icon> Load Approvals
        </button>
        <button mat-icon-button (click)="loadApprovals()" matTooltip="Refresh">
          <mat-icon>refresh</mat-icon>
        </button>
        <span class="queue-badge" *ngIf="approvalStates.length > 0">
          {{ approvalStates.length }} pending
        </span>
      </div>

      <!-- Loading -->
      <div *ngIf="loading" class="loading-state">
        <mat-icon class="spin">refresh</mat-icon> Loading approvals...
      </div>

      <!-- Empty -->
      <div *ngIf="!loading && approvalStates.length === 0" class="empty-state">
        <mat-icon class="empty-icon">approval</mat-icon>
        <p>No pending approvals</p>
        <p class="empty-sub-text">The approval queue is clear — no human-in-the-loop actions required.</p>
      </div>

      <!-- Approval Cards -->
      <div class="approvals-list" *ngIf="!loading && approvalStates.length > 0">
        <div *ngFor="let state of approvalStates" class="approval-card"
             [class.approval-expanded]="state.expanded">

          <!-- Card Header -->
          <div class="approval-header" (click)="state.expanded = !state.expanded">
            <mat-icon class="approval-icon">approval</mat-icon>
            <div class="approval-info">
              <span class="approval-step">{{ state.request.stepName || state.request.stepId }}</span>
              <span class="approval-run">Run: {{ state.request.workflowRunId }}</span>
            </div>
            <div class="approval-meta">
              <span *ngIf="state.request.assignedTo" class="assignee">
                <mat-icon>person</mat-icon> {{ state.request.assignedTo }}
              </span>
              <span *ngIf="state.request.slaDeadline" class="sla"
                    [class.sla-urgent]="isSlaUrgent(state.request.slaDeadline)">
                <mat-icon>schedule</mat-icon> SLA: {{ formatDate(state.request.slaDeadline) }}
              </span>
              <span *ngIf="state.request.createdAt" class="created-time">
                {{ formatDate(state.request.createdAt) }}
              </span>
            </div>
            <mat-chip-set>
              <mat-chip class="pending-chip">PENDING</mat-chip>
            </mat-chip-set>
            <mat-icon class="expand-icon">{{ state.expanded ? 'expand_less' : 'expand_more' }}</mat-icon>
          </div>

          <!-- Expanded Detail -->
          <div *ngIf="state.expanded" class="approval-detail">
            <mat-divider></mat-divider>

            <!-- Context Data -->
            <div *ngIf="state.request.context && hasKeys(state.request.context)" class="context-section">
              <div class="detail-heading">
                <mat-icon>info</mat-icon> Context
              </div>
              <div class="context-grid">
                <div *ngFor="let entry of getContextEntries(state.request.context)" class="context-item">
                  <span class="ctx-key">{{ entry.key }}</span>
                  <span class="ctx-value">{{ formatValue(entry.value) }}</span>
                </div>
              </div>
            </div>

            <!-- Items -->
            <div *ngIf="state.request.items?.length" class="items-section">
              <div class="detail-heading">
                <mat-icon>list</mat-icon> Items ({{ state.request.items?.length }})
              </div>
              <pre class="items-json">{{ state.request.items | json }}</pre>
            </div>

            <!-- Response Form -->
            <div class="response-form">
              <div class="detail-heading">
                <mat-icon>reply</mat-icon> Response
              </div>

              <mat-form-field appearance="outline" class="full-width">
                <mat-label>Comment (optional)</mat-label>
                <textarea matInput [(ngModel)]="state.comment" rows="3"
                          placeholder="Add a note or justification..."></textarea>
              </mat-form-field>

              <mat-form-field appearance="outline" class="delegate-field"
                              *ngIf="state.delegateTo !== undefined">
                <mat-label>Delegate To</mat-label>
                <input matInput [(ngModel)]="state.delegateTo" placeholder="user@example.com">
              </mat-form-field>

              <!-- Action Buttons -->
              <div class="action-buttons">
                <button mat-raised-button class="btn-approve"
                        (click)="submitAction(state, 'APPROVE')"
                        [disabled]="state.submitting"
                        matTooltip="Approve this request">
                  <mat-icon>check_circle</mat-icon> Approve
                </button>
                <button mat-stroked-button
                        (click)="submitAction(state, 'APPROVE_WITH_EDITS')"
                        [disabled]="state.submitting"
                        matTooltip="Approve with modifications">
                  <mat-icon>edit</mat-icon> Approve w/ Edits
                </button>
                <button mat-stroked-button color="warn"
                        (click)="submitAction(state, 'REJECT')"
                        [disabled]="state.submitting"
                        matTooltip="Reject this request">
                  <mat-icon>cancel</mat-icon> Reject
                </button>
                <button mat-stroked-button
                        (click)="submitAction(state, 'ESCALATE')"
                        [disabled]="state.submitting"
                        matTooltip="Escalate to higher authority">
                  <mat-icon>trending_up</mat-icon> Escalate
                </button>
                <button mat-stroked-button
                        (click)="toggleDelegate(state)"
                        [disabled]="state.submitting"
                        matTooltip="Delegate to another approver">
                  <mat-icon>forward</mat-icon> Delegate
                </button>
                <button mat-stroked-button
                        (click)="submitAction(state, 'REQUEST_INFO')"
                        [disabled]="state.submitting"
                        matTooltip="Request more information">
                  <mat-icon>help</mat-icon> Request Info
                </button>
              </div>

              <div *ngIf="state.submitting" class="submitting-indicator">
                <mat-icon class="spin">refresh</mat-icon> Submitting...
              </div>
            </div>
          </div>
        </div>
      </div>
    </div>
  `,
  styles: [`
    .approvals-container { padding: 12px 0; }
    .section-toolbar { display: flex; align-items: center; gap: 8px; margin-bottom: 16px; flex-wrap: wrap; }
    .assignee-filter { width: 260px; }
    .queue-badge {
      background: rgba(255,183,77,0.2); color: #ffb74d; padding: 4px 12px;
      border-radius: 12px; font-size: 12px; font-weight: 600;
    }

    .loading-state { display: flex; align-items: center; gap: 8px; padding: 24px; color: #aaa; font-size: 13px; }
    .spin { animation: spin 1s linear infinite; }
    @keyframes spin { from { transform: rotate(0deg); } to { transform: rotate(360deg); } }

    .empty-state { text-align: center; padding: 60px 20px; color: #999; }
    .empty-icon { font-size: 56px; width: 56px; height: 56px; color: #555; display: block; margin: 0 auto 12px; }
    .empty-sub-text { font-size: 12px; color: #666; margin-top: 4px; }

    .approvals-list { display: flex; flex-direction: column; gap: 8px; }
    .approval-card {
      background: #1e1e2e; border: 1px solid rgba(255,183,77,0.2); border-radius: 8px;
      overflow: hidden; transition: border-color 0.2s;
    }
    .approval-card:hover { border-color: rgba(255,183,77,0.4); }
    .approval-expanded { border-color: rgba(255,183,77,0.5) !important; }

    .approval-header {
      display: flex; align-items: center; gap: 12px; padding: 12px 14px;
      cursor: pointer; flex-wrap: wrap;
    }
    .approval-header:hover { background: rgba(255,255,255,0.03); }
    .approval-icon { font-size: 22px; width: 22px; height: 22px; color: #ffb74d; flex-shrink: 0; }
    .approval-info { flex: 1; min-width: 0; }
    .approval-step { display: block; font-weight: 500; font-size: 14px; }
    .approval-run { font-size: 11px; color: #888; font-family: monospace; }

    .approval-meta { display: flex; flex-wrap: wrap; gap: 12px; font-size: 11px; color: #777; }
    .assignee, .sla, .created-time { display: flex; align-items: center; gap: 4px; }
    .assignee mat-icon, .sla mat-icon { font-size: 13px; width: 13px; height: 13px; }
    .sla-urgent { color: #ef5350 !important; font-weight: 600; }

    .pending-chip { background: rgba(255,183,77,0.2) !important; color: #ffb74d !important; font-size: 11px !important; min-height: 22px !important; }
    .expand-icon { color: #666; font-size: 20px; width: 20px; height: 20px; flex-shrink: 0; }

    .approval-detail { padding: 0 14px 14px; }

    .detail-heading {
      display: flex; align-items: center; gap: 6px; font-size: 12px; font-weight: 600;
      color: #90caf9; text-transform: uppercase; letter-spacing: 0.5px;
      margin: 14px 0 8px;
    }
    .detail-heading mat-icon { font-size: 16px; width: 16px; height: 16px; }

    .context-section, .items-section { margin-bottom: 12px; }
    .context-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(240px, 1fr)); gap: 6px; }
    .context-item {
      background: #141420; border-radius: 4px; padding: 6px 10px;
      display: flex; flex-direction: column; gap: 2px;
    }
    .ctx-key { font-size: 10px; color: #888; font-family: monospace; text-transform: uppercase; }
    .ctx-value { font-size: 12px; font-weight: 500; word-break: break-all; }

    .items-json {
      background: #111; padding: 10px 12px; border-radius: 6px;
      font-size: 11px; font-family: monospace; white-space: pre-wrap;
      max-height: 200px; overflow-y: auto; margin: 0;
    }

    .response-form { margin-top: 8px; }
    .full-width { width: 100%; }
    .delegate-field { width: 300px; margin-bottom: 8px; }

    .action-buttons { display: flex; gap: 8px; flex-wrap: wrap; margin-top: 4px; }
    .btn-approve { background: rgba(129,199,132,0.2) !important; color: #81c784 !important; }
    .action-buttons button { font-size: 12px; }

    .submitting-indicator { display: flex; align-items: center; gap: 6px; font-size: 12px; color: #aaa; margin-top: 8px; }
  `]
})
export class ProcessApprovalsComponent implements OnInit {
  approvalStates: ApprovalState[] = [];
  loading = false;
  filterAssignee = '';

  constructor(
    private processEngineService: ProcessEngineService,
    private snackBar: MatSnackBar
  ) {}

  ngOnInit(): void {
    this.loadApprovals();
  }

  loadApprovals(): void {
    this.loading = true;
    const assignee = this.filterAssignee.trim() || undefined;
    this.processEngineService.getPendingApprovals(assignee).subscribe({
      next: (requests) => {
        this.approvalStates = requests.map(r => ({
          request: r,
          expanded: false,
          comment: '',
          delegateTo: '',
          submitting: false
        }));
        this.loading = false;
      },
      error: (err) => {
        this.snackBar.open('Failed to load approvals: ' + (err.error?.message || err.message), 'Close', { duration: 4000 });
        this.loading = false;
      }
    });
  }

  submitAction(state: ApprovalState, action: ApprovalAction): void {
    if (!state.request.id) {
      this.snackBar.open('Approval request has no ID', 'Close', { duration: 3000 });
      return;
    }
    if (action === 'DELEGATE' && !state.delegateTo.trim()) {
      this.snackBar.open('Please enter a delegate email', 'Close', { duration: 3000 });
      return;
    }

    state.submitting = true;
    const response: ApprovalResponse = {
      requestId: state.request.id,
      respondedBy: 'ui-user',
      action,
      comment: state.comment || undefined,
      delegateTo: action === 'DELEGATE' ? state.delegateTo : undefined
    };

    this.processEngineService.submitApproval(state.request.id, response).subscribe({
      next: () => {
        this.snackBar.open(`Action "${action}" submitted`, 'Close', { duration: 3000 });
        if (action === 'APPROVE' || action === 'APPROVE_WITH_EDITS' || action === 'REJECT') {
          // Terminal actions — remove from list
          this.approvalStates = this.approvalStates.filter(s => s !== state);
        } else {
          // ESCALATE/DELEGATE/REQUEST_INFO — reload to get updated state
          state.submitting = false;
          this.loadApprovals();
        }
      },
      error: (err) => {
        this.snackBar.open('Failed to submit: ' + (err.error?.message || err.message), 'Close', { duration: 4000 });
        state.submitting = false;
      }
    });
  }

  toggleDelegate(state: ApprovalState): void {
    // Toggle delegate field visibility by setting a sentinel value
    if (state.delegateTo === undefined) {
      state.delegateTo = '';
    } else {
      state.delegateTo = undefined as any;
    }
  }

  isSlaUrgent(slaDeadline: string): boolean {
    try {
      const deadline = new Date(slaDeadline).getTime();
      const nowPlus2h = Date.now() + 2 * 60 * 60 * 1000;
      return deadline < nowPlus2h;
    } catch {
      return false;
    }
  }

  hasKeys(obj: Record<string, any> | undefined): boolean {
    return !!obj && Object.keys(obj).length > 0;
  }

  getContextEntries(context: Record<string, any>): { key: string; value: any }[] {
    return Object.entries(context).map(([key, value]) => ({ key, value }));
  }

  formatValue(value: any): string {
    if (value === null || value === undefined) return '—';
    if (typeof value === 'object') return JSON.stringify(value);
    return String(value);
  }

  formatDate(dateStr: string): string {
    try {
      return new Date(dateStr).toLocaleString();
    } catch {
      return dateStr;
    }
  }
}
