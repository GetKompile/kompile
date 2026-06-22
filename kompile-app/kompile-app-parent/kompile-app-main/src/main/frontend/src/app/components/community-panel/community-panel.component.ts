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
import { HttpClient } from '@angular/common/http';
import { MatCardModule } from '@angular/material/card';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatSelectModule } from '@angular/material/select';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatTooltipModule } from '@angular/material/tooltip';
import { MatTableModule } from '@angular/material/table';
import { MatChipsModule } from '@angular/material/chips';
import { MatInputModule } from '@angular/material/input';
import { BaseService } from '../../services/base.service';

interface CommunityResult {
  communityCount: number;
  modularity: number;
  nodeCount: number;
  /** Map of nodeId -> communityId */
  nodeToCommunit: Record<string, number>;
  /** Map of communityId -> list of nodeIds */
  communities: Record<string, string[]>;
}

interface CommunitySummaryResult {
  communityId: number;
  summary: string;
  memberCount: number;
}

/**
 * Minimum node count below which community detection is likely to produce
 * degenerate or meaningless clusters. Shown as a pre-run caveat (not a hard block).
 */
const COMMUNITY_MIN_NODES = 20;

/**
 * Modularity below this threshold, combined with every node being its own
 * community, signals a degenerate result.
 */
const COMMUNITY_DEGENERATE_MODULARITY = 0.05;

@Component({
  selector: 'app-community-panel',
  standalone: true,
  imports: [
    CommonModule,
    FormsModule,
    MatCardModule,
    MatButtonModule,
    MatIconModule,
    MatProgressSpinnerModule,
    MatSelectModule,
    MatFormFieldModule,
    MatTooltipModule,
    MatTableModule,
    MatChipsModule,
    MatInputModule
  ],
  template: `
    <div class="community-panel">
      <div class="panel-header">
        <mat-icon class="header-icon">bubble_chart</mat-icon>
        <span class="header-title">Community Detection</span>
      </div>

      <!-- Controls -->
      <div class="controls-row">
        <mat-form-field appearance="outline" class="method-field">
          <mat-label>Algorithm</mat-label>
          <mat-select [(ngModel)]="selectedMethod">
            <mat-option value="louvain">Louvain</mat-option>
            <mat-option value="label_propagation">Label Propagation</mat-option>
          </mat-select>
        </mat-form-field>

        <mat-form-field appearance="outline" class="resolution-field"
                        *ngIf="selectedMethod === 'louvain'"
                        matTooltip="Resolution: higher values produce smaller communities (0.1 - 3.0)">
          <mat-label>Resolution</mat-label>
          <input matInput type="number" [(ngModel)]="resolution" min="0.1" max="3.0" step="0.1">
        </mat-form-field>
      </div>

      <button mat-raised-button color="primary"
              [disabled]="loading || !factSheetId"
              (click)="detect()"
              class="detect-btn">
        <mat-spinner *ngIf="loading" diameter="16" class="btn-spinner"></mat-spinner>
        <mat-icon *ngIf="!loading">search</mat-icon>
        {{loading ? 'Detecting...' : 'Detect Communities'}}
      </button>

      <!-- No fact sheet selected -->
      <div *ngIf="!factSheetId" class="empty-state">
        <mat-icon>folder_open</mat-icon>
        <p>Select a fact sheet in the graph visualizer to detect communities.</p>
      </div>

      <!-- Error -->
      <div *ngIf="error" class="error-banner">
        <mat-icon>error_outline</mat-icon>
        <span>{{error}}</span>
      </div>

      <!-- Density caveat: sparse / degenerate warning (non-blocking) -->
      <div *ngIf="densityCaveat" class="density-caveat">
        <mat-icon class="caveat-icon">warning</mat-icon>
        <span>{{densityCaveat}}</span>
      </div>

      <!-- Results -->
      <div *ngIf="result && !loading" class="results-section">
        <!-- Stats row -->
        <div class="stats-row">
          <div class="stat-card">
            <span class="stat-value">{{result.communityCount}}</span>
            <span class="stat-label">Communities</span>
          </div>
          <div class="stat-card">
            <span class="stat-value">{{result.modularity | number:'1.3-3'}}</span>
            <span class="stat-label">Modularity (Q)</span>
          </div>
          <div class="stat-card">
            <span class="stat-value">{{result.nodeCount}}</span>
            <span class="stat-label">Nodes</span>
          </div>
        </div>

        <!-- Community chips -->
        <div class="communities-section">
          <div class="section-label">Communities — click to inspect members</div>
          <div class="chips-row">
            <span *ngFor="let cid of communityIds"
                  class="community-chip"
                  [class.selected]="selectedCommunityId === cid"
                  [style.border-color]="communityColors[cid % communityColors.length]"
                  [style.background]="selectedCommunityId === cid
                    ? communityColors[cid % communityColors.length] + '33'
                    : 'transparent'"
                  (click)="selectCommunity(cid)">
              <span class="chip-dot"
                    [style.background]="communityColors[cid % communityColors.length]"></span>
              C{{cid}}
              <span class="chip-count">
                {{(result.communities[cid] || []).length}}
              </span>
              <button mat-icon-button
                      class="summarize-btn"
                      [disabled]="summarizingId === cid"
                      (click)="$event.stopPropagation(); summarizeCommunity(cid)"
                      matTooltip="Generate LLM summary for this community">
                <mat-spinner *ngIf="summarizingId === cid" diameter="12"></mat-spinner>
                <mat-icon *ngIf="summarizingId !== cid" class="summarize-icon">auto_awesome</mat-icon>
              </button>
            </span>
          </div>
        </div>

        <!-- Members table -->
        <div *ngIf="selectedCommunityId !== null" class="members-section">
          <div class="section-label">
            Community {{selectedCommunityId}} members
            ({{(result.communities[selectedCommunityId] || []).length}} nodes)
          </div>

          <!-- D9 LLM Summary -->
          <div *ngIf="summaryError[selectedCommunityId]" class="summary-error">
            <mat-icon>error_outline</mat-icon>
            <span>{{summaryError[selectedCommunityId]}}</span>
          </div>
          <div *ngIf="summaries[selectedCommunityId]" class="summary-box">
            <div class="summary-header">
              <mat-icon class="summary-icon">auto_awesome</mat-icon>
              <span class="summary-label">AI Community Summary</span>
            </div>
            <p class="summary-text">{{summaries[selectedCommunityId]}}</p>
          </div>

          <div class="members-list">
            <div *ngFor="let nodeId of result.communities[selectedCommunityId] || []"
                 class="member-row">
              <span class="member-dot"
                    [style.background]="communityColors[selectedCommunityId % communityColors.length]">
              </span>
              <span class="member-id">{{nodeId}}</span>
            </div>
            <div *ngIf="(result.communities[selectedCommunityId] || []).length === 0"
                 class="empty-members">
              No members in this community.
            </div>
          </div>
        </div>
      </div>
    </div>
  `,
  styles: [`
    :host {
      display: block;
    }

    .community-panel {
      padding: 16px;
      display: flex;
      flex-direction: column;
      gap: 12px;
    }

    .panel-header {
      display: flex;
      align-items: center;
      gap: 8px;
    }

    .header-icon {
      color: var(--text-secondary);
      font-size: 20px;
      width: 20px;
      height: 20px;
    }

    .header-title {
      font-size: 14px;
      font-weight: 600;
      color: var(--text-primary);
    }

    .controls-row {
      display: flex;
      gap: 8px;
      align-items: flex-start;
      flex-wrap: wrap;
    }

    .method-field {
      flex: 1 1 160px;
      min-width: 140px;
    }

    .resolution-field {
      flex: 0 0 120px;
    }

    .detect-btn {
      width: 100%;
      display: flex;
      align-items: center;
      gap: 6px;
    }

    .btn-spinner {
      margin-right: 4px;
    }

    .empty-state {
      display: flex;
      flex-direction: column;
      align-items: center;
      gap: 8px;
      padding: 24px 0;
      color: var(--text-tertiary);
    }

    .empty-state mat-icon {
      font-size: 36px;
      width: 36px;
      height: 36px;
    }

    .empty-state p {
      margin: 0;
      font-size: 13px;
      text-align: center;
    }

    .error-banner {
      display: flex;
      align-items: center;
      gap: 8px;
      padding: 10px 12px;
      background: rgba(239, 68, 68, 0.08);
      border: 1px solid rgba(239, 68, 68, 0.25);
      border-radius: 6px;
      color: #ef4444;
      font-size: 13px;
    }

    .error-banner mat-icon {
      font-size: 18px;
      width: 18px;
      height: 18px;
      flex-shrink: 0;
    }

    .results-section {
      display: flex;
      flex-direction: column;
      gap: 14px;
    }

    .stats-row {
      display: flex;
      gap: 8px;
    }

    .stat-card {
      flex: 1;
      display: flex;
      flex-direction: column;
      align-items: center;
      padding: 10px 8px;
      background: var(--bg-surface);
      border: 1px solid var(--border-color);
      border-radius: 8px;
    }

    .stat-value {
      font-size: 18px;
      font-weight: 700;
      font-family: monospace;
      color: var(--text-primary);
    }

    .stat-label {
      font-size: 10px;
      color: var(--text-tertiary);
      text-transform: uppercase;
      letter-spacing: 0.5px;
      margin-top: 2px;
    }

    .communities-section,
    .members-section {
      display: flex;
      flex-direction: column;
      gap: 8px;
    }

    .section-label {
      font-size: 11px;
      font-weight: 600;
      text-transform: uppercase;
      letter-spacing: 0.5px;
      color: var(--text-secondary);
    }

    .chips-row {
      display: flex;
      flex-wrap: wrap;
      gap: 6px;
    }

    .community-chip {
      display: inline-flex;
      align-items: center;
      gap: 4px;
      padding: 3px 8px 3px 6px;
      border-radius: 12px;
      border: 1.5px solid var(--border-color);
      font-size: 12px;
      font-weight: 500;
      cursor: pointer;
      color: var(--text-primary);
      transition: background 0.15s, border-color 0.15s;
      user-select: none;
    }

    .community-chip:hover {
      filter: brightness(0.96);
    }

    .chip-dot {
      width: 8px;
      height: 8px;
      border-radius: 50%;
      flex-shrink: 0;
    }

    .chip-count {
      font-size: 10px;
      color: var(--text-tertiary);
      margin-left: 2px;
    }

    .members-list {
      max-height: 240px;
      overflow-y: auto;
      border: 1px solid var(--border-color);
      border-radius: 6px;
      background: var(--bg-surface);
    }

    .member-row {
      display: flex;
      align-items: center;
      gap: 8px;
      padding: 6px 10px;
      border-bottom: 1px solid var(--border-color);
      font-size: 12px;
    }

    .member-row:last-child {
      border-bottom: none;
    }

    .member-dot {
      width: 6px;
      height: 6px;
      border-radius: 50%;
      flex-shrink: 0;
    }

    .member-id {
      color: var(--text-primary);
      font-family: monospace;
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
    }

    .empty-members {
      padding: 12px;
      font-size: 12px;
      color: var(--text-tertiary);
      text-align: center;
    }

    .summarize-btn {
      width: 20px;
      height: 20px;
      line-height: 20px;
      padding: 0;
      margin-left: 2px;
      display: inline-flex;
      align-items: center;
      justify-content: center;
      vertical-align: middle;
      flex-shrink: 0;
    }

    .summarize-icon {
      font-size: 14px;
      width: 14px;
      height: 14px;
      color: var(--text-secondary);
    }

    .summary-box {
      padding: 10px 12px;
      background: var(--bg-surface);
      border: 1px solid var(--border-color);
      border-radius: 8px;
      margin-bottom: 8px;
    }

    .summary-header {
      display: flex;
      align-items: center;
      gap: 6px;
      margin-bottom: 6px;
    }

    .summary-icon {
      font-size: 14px;
      width: 14px;
      height: 14px;
      color: var(--primary-color, #4285F4);
    }

    .summary-label {
      font-size: 11px;
      font-weight: 600;
      text-transform: uppercase;
      letter-spacing: 0.5px;
      color: var(--text-secondary);
    }

    .summary-text {
      margin: 0;
      font-size: 13px;
      line-height: 1.5;
      color: var(--text-primary);
      white-space: pre-wrap;
    }

    .summary-error {
      display: flex;
      align-items: center;
      gap: 6px;
      padding: 8px 10px;
      background: rgba(239, 68, 68, 0.06);
      border: 1px solid rgba(239, 68, 68, 0.2);
      border-radius: 6px;
      color: #ef4444;
      font-size: 12px;
      margin-bottom: 8px;
    }

    .summary-error mat-icon {
      font-size: 16px;
      width: 16px;
      height: 16px;
      flex-shrink: 0;
    }

    /* Density / degenerate caveat (non-blocking warning) */
    .density-caveat {
      display: flex;
      align-items: flex-start;
      gap: 8px;
      padding: 10px 12px;
      background: rgba(255, 193, 7, 0.08);
      border: 1px solid rgba(255, 193, 7, 0.35);
      border-left: 3px solid #FFC107;
      border-radius: 6px;
      color: var(--text-primary, #1a1f36);
      font-size: 12px;
      line-height: 1.5;
    }

    .caveat-icon {
      font-size: 17px;
      width: 17px;
      height: 17px;
      color: #F9A825;
      flex-shrink: 0;
      margin-top: 1px;
    }
  `]
})
export class CommunityPanelComponent extends BaseService implements OnChanges {

  @Input() factSheetId: number | null = null;

  result: CommunityResult | null = null;
  loading = false;
  error: string | null = null;
  selectedMethod = 'louvain';
  resolution = 1.0;
  selectedCommunityId: number | null = null;

  communityIds: number[] = [];

  /**
   * Density caveat shown before running detection when a prior result reveals
   * a sparse graph, or shown after a degenerate result.
   * Never blocks the action — it's a warning only.
   */
  densityCaveat: string | null = null;

  /** communityId → summary text (populated lazily on Summarize click) */
  summaries: Record<number, string> = {};
  /** communityId → error message if summarization failed */
  summaryError: Record<number, string> = {};
  /** id of the community currently being summarized, or null */
  summarizingId: number | null = null;

  readonly communityColors: string[] = [
    '#4285F4', '#EA4335', '#FBBC05', '#34A853', '#FF6D00',
    '#9C27B0', '#00BCD4', '#FF5722', '#607D8B', '#795548',
    '#E91E63', '#009688', '#FF9800', '#3F51B5', '#8BC34A',
    '#F44336', '#2196F3', '#4CAF50', '#FFC107', '#9E9E9E'
  ];

  constructor(private http: HttpClient) {
    super();
  }

  ngOnChanges(changes: SimpleChanges): void {
    if (changes['factSheetId']) {
      // Clear results when fact sheet changes
      this.result = null;
      this.error = null;
      this.selectedCommunityId = null;
      this.communityIds = [];
      this.summaries = {};
      this.summaryError = {};
      this.summarizingId = null;
      this.densityCaveat = null;
    }
  }

  detect(): void {
    if (!this.factSheetId) return;
    this.loading = true;
    this.error = null;
    this.result = null;
    this.selectedCommunityId = null;
    this.communityIds = [];
    this.summaries = {};
    this.summaryError = {};
    this.summarizingId = null;
    this.densityCaveat = null;

    const url = `${this.backendUrl}/graph/${this.factSheetId}/communities` +
      `?method=${this.selectedMethod}&resolution=${this.resolution}&maxNodes=500`;

    this.http.get<CommunityResult>(url).subscribe({
      next: (res) => {
        this.result = res;
        this.communityIds = Array.from(
          { length: res.communityCount },
          (_, i) => i
        );
        this.densityCaveat = this.evaluateDensityCaveat(res);
        this.loading = false;
      },
      error: (err) => {
        this.error = err?.error?.error || err?.message || 'Community detection failed';
        this.loading = false;
      }
    });
  }

  /**
   * Returns a human-readable caveat string if the result looks degenerate or
   * the graph is too sparse for meaningful communities, or null if results
   * look reasonable.
   *
   * Degenerate conditions checked:
   *  • nodeCount < COMMUNITY_MIN_NODES (sparse graph overall)
   *  • communityCount === nodeCount (every node its own singleton community)
   *  • modularity < COMMUNITY_DEGENERATE_MODULARITY (no real structure found)
   */
  private evaluateDensityCaveat(res: CommunityResult): string | null {
    if (res.nodeCount < COMMUNITY_MIN_NODES) {
      return `Community detection needs a denser graph — only ${res.nodeCount} nodes found` +
        ` (${COMMUNITY_MIN_NODES}+ recommended). Add more sources for meaningful communities.`;
    }
    const isDegenerate =
      (res.communityCount >= res.nodeCount) ||
      (res.modularity < COMMUNITY_DEGENERATE_MODULARITY);
    if (isDegenerate) {
      return `Result is degenerate (modularity Q=${res.modularity.toFixed(3)}, ` +
        `${res.communityCount} communities for ${res.nodeCount} nodes) — ` +
        `the graph may be too sparse or disconnected. Add more sources for meaningful communities.`;
    }
    return null;
  }

  selectCommunity(cid: number): void {
    this.selectedCommunityId = this.selectedCommunityId === cid ? null : cid;
  }

  /**
   * D9 — Request an LLM-generated summary for the given community.
   * Calls GET /api/graph/{factSheetId}/communities/{communityId}/summary.
   * Result is stored in `summaries[cid]` and shown inline.
   */
  summarizeCommunity(cid: number): void {
    if (!this.factSheetId || this.summarizingId === cid) return;

    // Ensure the community is selected so the summary box is visible.
    this.selectedCommunityId = cid;
    this.summarizingId = cid;
    delete this.summaryError[cid];

    const url = `${this.backendUrl}/graph/${this.factSheetId}/communities/${cid}/summary`;

    this.http.get<CommunitySummaryResult>(url).subscribe({
      next: (res) => {
        this.summaries[cid] = res.summary;
        this.summarizingId = null;
      },
      error: (err) => {
        this.summaryError[cid] =
          err?.error?.error || err?.message || 'Summary unavailable';
        this.summarizingId = null;
      }
    });
  }
}
