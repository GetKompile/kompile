/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { MatIconModule } from '@angular/material/icon';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatChipsModule } from '@angular/material/chips';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatTooltipModule } from '@angular/material/tooltip';
import { MatExpansionModule } from '@angular/material/expansion';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';
import { ProcessEngineService } from '../../services/process-engine.service';
import { MebnVariableMeta } from '../../models/attribution-models';

interface StructuredEvidence {
  type: string;
  description: string;
  score: number | null;
  supportingNodeIds: string[];
}

interface SuggestedStep {
  name: string;
  stepType: string;
  description: string;
  occurredAt: string | null;
}

interface SuggestedPhase {
  name: string;
  description: string;
  steps: SuggestedStep[];
  earliestOccurrence: string | null;
  latestOccurrence: string | null;
}

interface ProcessSuggestion {
  id: string;
  factSheetId: number | null;
  discoveredAt: string;
  name: string;
  description: string;
  discoverySource: string;
  confidence: number;
  phases: SuggestedPhase[];
  sourceGraphNodeIds: string[];
  evidence: string[];
  bayesianPosteriors: Record<string, number>;
  bayesianPriors: Record<string, number>;
  mebnMeta: Record<string, MebnVariableMeta>;
  structuredEvidence: StructuredEvidence[];
  childSuggestions: ProcessSuggestion[];
  accepted: boolean | null;
  acceptedProcessDefinitionId: string | null;
}

@Component({
  standalone: true,
  selector: 'app-process-discovery-suggestions',
  imports: [
    CommonModule,
    MatIconModule, MatButtonModule, MatCardModule,
    MatChipsModule, MatProgressBarModule, MatTooltipModule,
    MatExpansionModule, MatSnackBarModule
  ],
  template: `
    <div class="suggestions-container">
      <!-- Header with actions -->
      <div class="section-header">
        <div class="header-left">
          <h3>Discovered Process Suggestions</h3>
          <span class="count-badge" *ngIf="suggestions.length > 0">{{ suggestions.length }}</span>
        </div>
        <div class="header-actions">
          <button mat-stroked-button (click)="runDiscovery()" [disabled]="loading">
            <mat-icon>search</mat-icon> Run Discovery
          </button>
          <button mat-button (click)="loadSuggestions()" [disabled]="loading">
            <mat-icon>refresh</mat-icon> Refresh
          </button>
        </div>
      </div>

      <!-- Loading -->
      <mat-progress-bar *ngIf="loading" mode="indeterminate" class="loading-bar"></mat-progress-bar>

      <!-- Empty state -->
      <div class="empty-state" *ngIf="!loading && suggestions.length === 0">
        <mat-icon class="empty-icon">auto_awesome</mat-icon>
        <p>No process suggestions yet. Run discovery to analyze your knowledge graph for repeatable patterns.</p>
      </div>

      <!-- Suggestions list -->
      <mat-accordion *ngIf="suggestions.length > 0" multi>
        <mat-expansion-panel *ngFor="let suggestion of suggestions; trackBy: trackById"
                             class="suggestion-panel"
                             [class.accepted]="suggestion.accepted">
          <mat-expansion-panel-header>
            <mat-panel-title>
              <mat-icon class="source-icon" [matTooltip]="suggestion.discoverySource">
                {{ getSourceIcon(suggestion.discoverySource) }}
              </mat-icon>
              <span class="suggestion-name">{{ suggestion.name }}</span>
            </mat-panel-title>
            <mat-panel-description>
              <div class="header-meta">
                <span class="confidence-badge"
                      [class.high]="suggestion.confidence >= 0.7"
                      [class.medium]="suggestion.confidence >= 0.4 && suggestion.confidence < 0.7"
                      [class.low]="suggestion.confidence < 0.4">
                  {{ (suggestion.confidence * 100) | number:'1.0-0' }}%
                </span>
                <mat-chip *ngIf="suggestion.accepted" class="accepted-chip">
                  <mat-icon>check_circle</mat-icon> Accepted
                </mat-chip>
              </div>
            </mat-panel-description>
          </mat-expansion-panel-header>

          <!-- Expanded content -->
          <div class="suggestion-detail">
            <p class="description">{{ suggestion.description }}</p>

            <!-- Source graph node IDs -->
            <div class="source-nodes-section" *ngIf="suggestion.sourceGraphNodeIds?.length">
              <div class="source-nodes-chips">
                <mat-chip *ngFor="let nodeId of suggestion.sourceGraphNodeIds" class="source-node-chip"
                          [matTooltip]="nodeId">
                  <mat-icon class="chip-icon">hub</mat-icon>
                  {{ nodeId | slice:0:20 }}
                </mat-chip>
              </div>
            </div>

            <!-- Bayesian Posteriors -->
            <div class="bayesian-section" *ngIf="hasBayesianData(suggestion)">
              <h4><mat-icon class="section-icon">insights</mat-icon> Bayesian Posteriors</h4>
              <div class="posteriors-grid">
                <div class="posterior-item" *ngFor="let entry of getPosteriorEntries(suggestion)">
                  <div class="posterior-header">
                    <span class="posterior-label" [matTooltip]="entry.nodeId">{{ entry.nodeId | slice:0:24 }}</span>
                    <span class="posterior-values">
                      <span class="prior-value" *ngIf="entry.prior !== undefined"
                            matTooltip="Prior">{{ (entry.prior * 100) | number:'1.1-1' }}%</span>
                      <mat-icon class="shift-arrow" *ngIf="entry.prior !== undefined">arrow_forward</mat-icon>
                      <span class="posterior-value">{{ (entry.value * 100) | number:'1.1-1' }}%</span>
                    </span>
                  </div>
                  <ng-container *ngIf="suggestion.mebnMeta?.[entry.nodeId] as meta">
                    <div class="posterior-mebn-meta">
                      <span class="mebn-badge-sm mfrag-sm">{{meta.mfragName}}</span>
                      <span class="mebn-badge-sm role-sm"
                            [class.role-sm-resident]="meta.nodeRole === 'RESIDENT'"
                            [class.role-sm-input]="meta.nodeRole === 'INPUT'">{{meta.nodeRole}}</span>
                      <span *ngIf="meta.entityType" class="mebn-badge-sm etype-sm">{{meta.entityType}}</span>
                    </div>
                  </ng-container>
                  <div class="posterior-bar-container">
                    <mat-progress-bar mode="determinate" [value]="entry.value * 100"
                                      [class.bar-high]="entry.value >= 0.7"
                                      [class.bar-medium]="entry.value >= 0.4 && entry.value < 0.7"
                                      [class.bar-low]="entry.value < 0.4">
                    </mat-progress-bar>
                    <div *ngIf="entry.prior !== undefined"
                         class="prior-marker"
                         [style.left.%]="entry.prior * 100"
                         matTooltip="Prior: {{ (entry.prior * 100) | number:'1.1-1' }}%">
                    </div>
                  </div>
                </div>
              </div>
            </div>

            <!-- Structured Evidence -->
            <div class="evidence-section" *ngIf="suggestion.structuredEvidence?.length">
              <h4><mat-icon class="section-icon">fact_check</mat-icon> Evidence</h4>
              <div class="evidence-list">
                <div class="evidence-item" *ngFor="let ev of suggestion.structuredEvidence">
                  <mat-chip class="evidence-type-chip" [class]="'ev-' + ev.type.toLowerCase()">
                    {{ ev.type }}
                  </mat-chip>
                  <span class="evidence-desc">{{ ev.description }}</span>
                  <span class="evidence-score" *ngIf="ev.score != null">
                    {{ (ev.score * 100) | number:'1.1-1' }}%
                  </span>
                </div>
              </div>
            </div>

            <!-- Phases & Steps -->
            <div class="phases-section" *ngIf="suggestion.phases?.length">
              <h4><mat-icon class="section-icon">account_tree</mat-icon> Suggested Workflow</h4>
              <div class="phase" *ngFor="let phase of suggestion.phases; let i = index">
                <div class="phase-header">
                  Phase {{ i + 1 }}: {{ phase.name }}
                  <span class="temporal-range" *ngIf="phase.earliestOccurrence || phase.latestOccurrence">
                    <mat-icon class="temporal-icon">schedule</mat-icon>
                    <span *ngIf="phase.earliestOccurrence && phase.latestOccurrence">
                      {{ phase.earliestOccurrence | date:'mediumDate' }} &ndash; {{ phase.latestOccurrence | date:'mediumDate' }}
                    </span>
                    <span *ngIf="phase.earliestOccurrence && !phase.latestOccurrence">
                      from {{ phase.earliestOccurrence | date:'mediumDate' }}
                    </span>
                    <span *ngIf="!phase.earliestOccurrence && phase.latestOccurrence">
                      until {{ phase.latestOccurrence | date:'mediumDate' }}
                    </span>
                  </span>
                </div>
                <div class="step" *ngFor="let step of phase.steps; let j = index">
                  <span class="step-number">{{ i + 1 }}.{{ j + 1 }}</span>
                  <mat-chip class="step-type-chip">{{ step.stepType }}</mat-chip>
                  <span class="step-desc">{{ step.description }}</span>
                  <span class="step-timestamp" *ngIf="step.occurredAt" [matTooltip]="step.occurredAt">
                    {{ step.occurredAt | date:'short' }}
                  </span>
                </div>
              </div>
            </div>

            <!-- Text evidence -->
            <div class="text-evidence" *ngIf="suggestion.evidence?.length">
              <h4><mat-icon class="section-icon">info</mat-icon> Discovery Evidence</h4>
              <ul>
                <li *ngFor="let e of suggestion.evidence">{{ e }}</li>
              </ul>
            </div>

            <!-- Child suggestions -->
            <div class="children-section" *ngIf="suggestion.childSuggestions?.length">
              <h4><mat-icon class="section-icon">subdirectory_arrow_right</mat-icon> Sub-Processes</h4>
              <mat-card *ngFor="let child of suggestion.childSuggestions" class="child-card">
                <mat-card-header>
                  <mat-card-title>{{ child.name }}</mat-card-title>
                  <mat-card-subtitle>{{ child.discoverySource }} | {{ (child.confidence * 100) | number:'1.0-0' }}% confidence</mat-card-subtitle>
                </mat-card-header>
                <mat-card-content>
                  <p>{{ child.description }}</p>
                  <!-- Child Bayesian posteriors -->
                  <div class="child-bayesian" *ngIf="hasBayesianData(child)">
                    <div class="posteriors-grid">
                      <div class="posterior-item" *ngFor="let entry of getPosteriorEntries(child)">
                        <div class="posterior-header">
                          <span class="posterior-label" [matTooltip]="entry.nodeId">{{ entry.nodeId | slice:0:20 }}</span>
                          <span class="posterior-values">
                            <span class="prior-value" *ngIf="entry.prior !== undefined"
                                  matTooltip="Prior">{{ (entry.prior * 100) | number:'1.1-1' }}%</span>
                            <mat-icon class="shift-arrow" *ngIf="entry.prior !== undefined">arrow_forward</mat-icon>
                            <span class="posterior-value">{{ (entry.value * 100) | number:'1.1-1' }}%</span>
                          </span>
                        </div>
                        <ng-container *ngIf="child.mebnMeta?.[entry.nodeId] as meta">
                          <div class="posterior-mebn-meta">
                            <span class="mebn-badge-sm mfrag-sm">{{meta.mfragName}}</span>
                            <span class="mebn-badge-sm role-sm"
                                  [class.role-sm-resident]="meta.nodeRole === 'RESIDENT'"
                                  [class.role-sm-input]="meta.nodeRole === 'INPUT'">{{meta.nodeRole}}</span>
                            <span *ngIf="meta.entityType" class="mebn-badge-sm etype-sm">{{meta.entityType}}</span>
                          </div>
                        </ng-container>
                        <div class="posterior-bar-container">
                          <mat-progress-bar mode="determinate" [value]="entry.value * 100"
                                            [class.bar-high]="entry.value >= 0.7"
                                            [class.bar-medium]="entry.value >= 0.4 && entry.value < 0.7"
                                            [class.bar-low]="entry.value < 0.4">
                          </mat-progress-bar>
                          <div *ngIf="entry.prior !== undefined"
                               class="prior-marker"
                               [style.left.%]="entry.prior * 100"
                               matTooltip="Prior: {{ (entry.prior * 100) | number:'1.1-1' }}%">
                          </div>
                        </div>
                      </div>
                    </div>
                  </div>
                  <!-- Child structured evidence -->
                  <div *ngIf="child.structuredEvidence?.length" class="child-evidence">
                    <div class="evidence-item" *ngFor="let ev of child.structuredEvidence">
                      <mat-chip class="evidence-type-chip" [class]="'ev-' + ev.type.toLowerCase()">
                        {{ ev.type }}
                      </mat-chip>
                      <span class="evidence-desc">{{ ev.description }}</span>
                      <span class="evidence-score" *ngIf="ev.score != null">
                        {{ (ev.score * 100) | number:'1.1-1' }}%
                      </span>
                    </div>
                  </div>
                </mat-card-content>
              </mat-card>
            </div>

            <!-- Actions -->
            <div class="suggestion-actions" *ngIf="!suggestion.accepted">
              <button mat-raised-button color="primary" (click)="acceptSuggestion(suggestion)">
                <mat-icon>check</mat-icon> Accept & Create Process
              </button>
              <button mat-button color="warn" (click)="deleteSuggestion(suggestion)">
                <mat-icon>delete</mat-icon> Dismiss
              </button>
            </div>
            <div class="accepted-info" *ngIf="suggestion.accepted">
              <mat-icon>check_circle</mat-icon>
              Accepted as process: {{ suggestion.acceptedProcessDefinitionId }}
            </div>
          </div>
        </mat-expansion-panel>
      </mat-accordion>
    </div>
  `,
  styles: [`
    .suggestions-container { padding: 4px 0; }

    .section-header {
      display: flex; justify-content: space-between; align-items: center;
      margin-bottom: 16px; flex-wrap: wrap; gap: 8px;
    }
    .header-left { display: flex; align-items: center; gap: 8px; }
    .header-left h3 { margin: 0; font-size: 16px; }
    .count-badge {
      background: rgba(144,202,249,0.2); color: #90caf9;
      border-radius: 10px; padding: 2px 8px; font-size: 12px; font-weight: 600;
    }
    .header-actions { display: flex; gap: 8px; }

    .loading-bar { margin-bottom: 16px; }

    .empty-state {
      text-align: center; padding: 48px 16px; color: #888;
    }
    .empty-icon { font-size: 48px; width: 48px; height: 48px; color: #555; }

    .suggestion-panel { margin-bottom: 8px !important; }
    .suggestion-panel.accepted { opacity: 0.7; }

    .source-icon { font-size: 18px; width: 18px; height: 18px; margin-right: 8px; color: #90caf9; }
    .suggestion-name { font-weight: 500; }

    .header-meta { display: flex; align-items: center; gap: 8px; }
    .confidence-badge {
      font-size: 12px; font-weight: 600; padding: 2px 8px; border-radius: 4px;
    }
    .confidence-badge.high { background: rgba(102,187,106,0.2); color: #66bb6a; }
    .confidence-badge.medium { background: rgba(255,183,77,0.2); color: #ffb74d; }
    .confidence-badge.low { background: rgba(239,83,80,0.2); color: #ef5350; }

    .accepted-chip { font-size: 11px !important; }

    .suggestion-detail { padding: 8px 0; }
    .description { color: #bbb; margin: 0 0 16px; }

    h4 { display: flex; align-items: center; gap: 6px; font-size: 14px; margin: 16px 0 8px; color: #e0e0e0; }
    .section-icon { font-size: 18px; width: 18px; height: 18px; }

    /* Bayesian posteriors */
    .bayesian-section { margin-bottom: 16px; }
    .posteriors-grid {
      display: grid; grid-template-columns: repeat(auto-fill, minmax(260px, 1fr)); gap: 8px;
    }
    .posterior-item { padding: 8px; background: rgba(255,255,255,0.03); border-radius: 4px; }
    .posterior-header { display: flex; justify-content: space-between; align-items: center; margin-bottom: 4px; }
    .posterior-label { font-size: 12px; color: #aaa; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; max-width: 140px; }
    .posterior-values { display: flex; align-items: center; gap: 4px; }
    .prior-value { font-size: 11px; color: #888; }
    .shift-arrow { font-size: 12px; height: 12px; width: 12px; color: #666; }
    .posterior-value { font-size: 12px; font-weight: 600; color: #e0e0e0; }
    .posterior-bar-container { position: relative; }
    .prior-marker { position: absolute; top: 0; width: 2px; height: 100%; background: #fff; opacity: 0.6; border-radius: 1px; pointer-events: auto; cursor: default; }
    .bar-high ::ng-deep .mdc-linear-progress__bar-inner { border-color: #66bb6a !important; }
    .bar-medium ::ng-deep .mdc-linear-progress__bar-inner { border-color: #ffb74d !important; }
    .bar-low ::ng-deep .mdc-linear-progress__bar-inner { border-color: #ef5350 !important; }

    /* Structured evidence */
    .evidence-section { margin-bottom: 16px; }
    .evidence-list { display: flex; flex-direction: column; gap: 6px; }
    .evidence-item { display: flex; align-items: center; gap: 8px; font-size: 13px; }
    .evidence-type-chip { font-size: 10px !important; height: 22px !important; min-height: 22px !important; }
    .ev-bayesian { background: rgba(206,147,216,0.2) !important; color: #ce93d8 !important; }
    .ev-causal { background: rgba(144,202,249,0.2) !important; color: #90caf9 !important; }
    .ev-temporal { background: rgba(255,183,77,0.2) !important; color: #ffb74d !important; }
    .ev-statistical { background: rgba(102,187,106,0.2) !important; color: #66bb6a !important; }
    .evidence-desc { flex: 1; color: #bbb; }
    .evidence-score { font-weight: 600; color: #e0e0e0; font-size: 12px; }

    /* Phases */
    .phases-section { margin-bottom: 16px; }
    .phase { margin-bottom: 12px; }
    .phase-header { font-weight: 500; font-size: 13px; color: #e0e0e0; margin-bottom: 4px; }
    .step {
      display: flex; align-items: center; gap: 8px;
      padding: 4px 8px; font-size: 13px; color: #bbb;
    }
    .step-number { font-size: 11px; color: #888; min-width: 24px; }
    .step-type-chip { font-size: 10px !important; height: 20px !important; min-height: 20px !important; }
    .step-desc { flex: 1; }
    .step-timestamp { font-size: 11px; color: #90caf9; white-space: nowrap; }

    .temporal-range {
      display: inline-flex; align-items: center; gap: 4px;
      font-size: 11px; color: #ffb74d; font-weight: 400; margin-left: 8px;
    }
    .temporal-icon { font-size: 14px; width: 14px; height: 14px; }

    .text-evidence ul { margin: 0; padding-left: 20px; }
    .text-evidence li { font-size: 13px; color: #bbb; margin-bottom: 4px; }

    .child-card { margin-bottom: 8px; }
    .child-bayesian { margin-top: 8px; }
    .child-evidence { display: flex; flex-direction: column; gap: 4px; margin-top: 6px; }

    /* MEBN badges for posterior entries */
    .posterior-mebn-meta { display: flex; gap: 4px; margin: 2px 0 4px; flex-wrap: wrap; }
    .mebn-badge-sm {
      font-size: 9px; padding: 1px 5px; border-radius: 3px; font-weight: 500;
      white-space: nowrap;
    }
    .mfrag-sm { background: rgba(144,202,249,0.15); color: #90caf9; }
    .role-sm { background: rgba(206,147,216,0.15); color: #ce93d8; }
    .role-sm-resident { background: rgba(102,187,106,0.15); color: #66bb6a; }
    .role-sm-input { background: rgba(255,183,77,0.15); color: #ffb74d; }
    .etype-sm { background: rgba(255,255,255,0.08); color: #bbb; }

    .source-nodes-section { margin-bottom: 12px; }
    .source-nodes-chips { display: flex; flex-wrap: wrap; gap: 4px; }
    .source-node-chip {
      font-size: 10px !important; height: 22px !important; min-height: 22px !important;
      background: rgba(144,202,249,0.1) !important; color: #90caf9 !important;
    }
    .chip-icon { font-size: 12px !important; width: 12px !important; height: 12px !important; margin-right: 2px; }

    .suggestion-actions { display: flex; gap: 8px; margin-top: 16px; }
    .accepted-info {
      display: flex; align-items: center; gap: 6px;
      margin-top: 16px; color: #66bb6a; font-size: 13px;
    }
  `]
})
export class ProcessDiscoverySuggestionsComponent implements OnInit {
  suggestions: ProcessSuggestion[] = [];
  loading = false;

  constructor(
    private processEngineService: ProcessEngineService,
    private snackBar: MatSnackBar
  ) {}

  ngOnInit(): void {
    this.loadSuggestions();
  }

  loadSuggestions(): void {
    this.loading = true;
    this.processEngineService.listStoredSuggestions().subscribe({
      next: (response) => {
        this.suggestions = response.suggestions || [];
        this.loading = false;
      },
      error: () => {
        this.suggestions = [];
        this.loading = false;
      }
    });
  }

  runDiscovery(): void {
    this.loading = true;
    this.processEngineService.discoverProcesses().subscribe({
      next: (response) => {
        this.snackBar.open(
          `Discovery complete: ${response.count} suggestions found`,
          'Close', { duration: 3000 }
        );
        this.loadSuggestions();
      },
      error: (err) => {
        this.loading = false;
        this.snackBar.open(
          'Discovery failed: ' + (err.error?.message || err.message),
          'Close', { duration: 5000 }
        );
      }
    });
  }

  acceptSuggestion(suggestion: ProcessSuggestion): void {
    this.processEngineService.acceptStoredSuggestion(suggestion.id).subscribe({
      next: (definition) => {
        this.snackBar.open(
          `Process "${definition.name}" created (${definition.id})`,
          'Close', { duration: 3000 }
        );
        this.loadSuggestions();
      },
      error: (err) => {
        this.snackBar.open(
          'Accept failed: ' + (err.error?.message || err.message),
          'Close', { duration: 5000 }
        );
      }
    });
  }

  deleteSuggestion(suggestion: ProcessSuggestion): void {
    this.processEngineService.deleteStoredSuggestion(suggestion.id).subscribe({
      next: () => {
        this.suggestions = this.suggestions.filter(s => s.id !== suggestion.id);
        this.snackBar.open('Suggestion dismissed', 'Close', { duration: 2000 });
      },
      error: (err) => {
        this.snackBar.open(
          'Delete failed: ' + (err.error?.message || err.message),
          'Close', { duration: 5000 }
        );
      }
    });
  }

  hasBayesianData(suggestion: ProcessSuggestion): boolean {
    return suggestion.bayesianPosteriors != null
      && Object.keys(suggestion.bayesianPosteriors).length > 0;
  }

  getPosteriorEntries(suggestion: ProcessSuggestion): Array<{ nodeId: string; value: number; prior?: number }> {
    if (!suggestion.bayesianPosteriors) return [];
    return Object.entries(suggestion.bayesianPosteriors)
      .map(([nodeId, value]) => ({
        nodeId,
        value,
        prior: suggestion.bayesianPriors?.[nodeId]
      }))
      .sort((a, b) => b.value - a.value);
  }

  getSourceIcon(source: string): string {
    switch (source?.toUpperCase()) {
      case 'EMAIL_FLOW': return 'email';
      case 'EXCEL_COMPUTATION': return 'table_chart';
      case 'DOCUMENT_PIPELINE': return 'description';
      case 'CROSS_DOCUMENT': return 'link';
      case 'COMMUNITY': return 'groups';
      default: return 'auto_awesome';
    }
  }

  trackById(_index: number, item: ProcessSuggestion): string {
    return item.id;
  }
}
