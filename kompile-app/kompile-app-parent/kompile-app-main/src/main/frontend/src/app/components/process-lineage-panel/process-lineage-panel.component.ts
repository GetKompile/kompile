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
import { MatCardModule } from '@angular/material/card';
import { MatChipsModule } from '@angular/material/chips';
import { MatExpansionModule } from '@angular/material/expansion';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatTooltipModule } from '@angular/material/tooltip';
import { MatDividerModule } from '@angular/material/divider';
import { BaseService } from '../../services/base.service';

export interface ProcessLineage {
  basisNodeIds: string[];
  basisNodeTitles?: string[];
  supportingRuleTexts: string[];
  causalActivityPairs: string[];
  derivationMethod: string;
  softTruthValue?: number;
  atomKey?: string;
}

export interface SuggestedStep {
  name: string;
  stepType: string;
  description?: string;
  roleBinding?: string;
  graphNodeIds?: string[];
  graphNodeTitles?: string[];
  lineageRef?: ProcessLineage;
}

export interface SuggestedPhase {
  name: string;
  steps: SuggestedStep[];
}

export interface ProcessSuggestion {
  name: string;
  description?: string;
  phases: SuggestedPhase[];
  lineageRef?: ProcessLineage;
}

@Component({
  selector: 'app-process-lineage-panel',
  standalone: true,
  imports: [
    CommonModule,
    FormsModule,
    MatButtonModule,
    MatIconModule,
    MatCardModule,
    MatChipsModule,
    MatExpansionModule,
    MatProgressSpinnerModule,
    MatTooltipModule,
    MatDividerModule,
  ],
  template: `
    <mat-card class="lineage-card">
      <mat-card-header>
        <mat-card-title>
          <mat-icon>device_hub</mat-icon> Process Lineage
        </mat-card-title>
        <mat-card-subtitle>
          Trace a discovered process back to its basis facts, mined rules, and causal evidence
        </mat-card-subtitle>
      </mat-card-header>

      <mat-card-content>

        <!-- Controls -->
        <div class="controls-row">
          <span class="fact-sheet-label" *ngIf="factSheetId != null">
            Fact Sheet: <strong>{{ factSheetId }}</strong>
          </span>
          <span class="fact-sheet-label" *ngIf="factSheetId == null">
            No fact sheet selected
          </span>
          <button mat-raised-button color="primary" (click)="loadProcess()" [disabled]="loading || factSheetId == null">
            <mat-icon>search</mat-icon> Discover Process
          </button>
          <div class="noise-row" *ngIf="factSheetId != null">
            <label>Noise filter:</label>
            <input type="range" min="0" max="0.5" step="0.05" [(ngModel)]="noise" style="width:100px"/>
            <span>{{ noise | number:'1.2-2' }}</span>
          </div>
        </div>

        <!-- Loading -->
        <div *ngIf="loading" class="spinner-row">
          <mat-spinner diameter="32"></mat-spinner>
          <span>Discovering process…</span>
        </div>

        <!-- Error -->
        <div *ngIf="!loading && error" class="error-row">
          <mat-icon color="warn">error_outline</mat-icon>
          <span>{{ error }}</span>
        </div>

        <!-- No process found -->
        <div *ngIf="!loading && !error && !process && factSheetId != null && hasLoaded" class="empty-row">
          <mat-icon>info_outline</mat-icon>
          <span>No process discovered for this fact sheet. Try crawling the graph first.</span>
        </div>

        <!-- No fact sheet -->
        <div *ngIf="!loading && factSheetId == null" class="empty-row">
          <mat-icon>info_outline</mat-icon>
          <span>Select a fact sheet to discover and trace its process lineage.</span>
        </div>

        <!-- Process header -->
        <div *ngIf="!loading && !error && process" class="process-header">
          <h3 class="process-name">{{ process.name }}</h3>
          <p *ngIf="process.description" class="process-desc">{{ process.description }}</p>

          <!-- Top-level lineage -->
          <div *ngIf="process.lineageRef" class="lineage-block">
            <h4>Process Derivation</h4>
            <ng-container [ngTemplateOutlet]="lineageDetail" [ngTemplateOutletContext]="{ lineage: process.lineageRef }"></ng-container>
          </div>

          <mat-divider *ngIf="process.phases?.length"></mat-divider>

          <!-- Phases -->
          <div *ngIf="process.phases?.length" class="phases-section">
            <h4>Phases &amp; Steps</h4>
            <mat-accordion multi>
              <mat-expansion-panel *ngFor="let phase of process.phases" class="phase-panel">
                <mat-expansion-panel-header>
                  <mat-panel-title>{{ phase.name }}</mat-panel-title>
                  <mat-panel-description>{{ phase.steps?.length ?? 0 }} step(s)</mat-panel-description>
                </mat-expansion-panel-header>

                <div *ngFor="let step of phase.steps" class="step-row">
                  <div class="step-header">
                    <mat-icon class="step-icon">{{ stepIcon(step.stepType) }}</mat-icon>
                    <strong class="step-name">{{ step.name }}</strong>
                    <span class="step-type-badge">{{ step.stepType }}</span>
                    <span *ngIf="step.roleBinding" class="role-badge">{{ step.roleBinding }}</span>
                  </div>
                  <p *ngIf="step.description" class="step-desc">{{ step.description }}</p>

                  <!-- Per-step lineage (collapsible) -->
                  <mat-expansion-panel *ngIf="step.lineageRef" class="step-lineage-panel">
                    <mat-expansion-panel-header>
                      <mat-panel-title>
                        <mat-icon>account_tree</mat-icon> Trace this step
                      </mat-panel-title>
                      <mat-panel-description *ngIf="step.lineageRef.derivationMethod">
                        via {{ step.lineageRef.derivationMethod }}
                      </mat-panel-description>
                    </mat-expansion-panel-header>
                    <ng-container [ngTemplateOutlet]="lineageDetail" [ngTemplateOutletContext]="{ lineage: step.lineageRef }"></ng-container>
                  </mat-expansion-panel>
                </div>
              </mat-expansion-panel>
            </mat-accordion>
          </div>
        </div>

      </mat-card-content>
    </mat-card>

    <!-- Lineage detail template (reused for process + per-step) -->
    <ng-template #lineageDetail let-lineage="lineage">
      <div class="lineage-detail">

        <div class="lineage-row" *ngIf="lineage.derivationMethod">
          <span class="lineage-label">Method:</span>
          <span class="method-badge">{{ lineage.derivationMethod }}</span>
        </div>

        <div class="lineage-row" *ngIf="lineage.softTruthValue != null">
          <span class="lineage-label">Soft-truth value:</span>
          <span class="confidence-value">{{ lineage.softTruthValue | number:'1.3-3' }}
            ({{ (lineage.softTruthValue * 100) | number:'1.0-0' }}%)
          </span>
        </div>

        <!-- atomKey: internal PSL formalism — shown only in dev mode -->
        <div class="lineage-row" *ngIf="lineage.atomKey && showDevDetails">
          <span class="lineage-label">Atom key:</span>
          <code class="atom-key">{{ lineage.atomKey }}</code>
        </div>

        <div class="lineage-block-inner" *ngIf="lineage.basisNodeIds?.length">
          <span class="lineage-label">Basis facts ({{ lineage.basisNodeIds.length }}):</span>
          <div class="chips-row">
            <span *ngFor="let id of lineage.basisNodeIds.slice(0, 20); let i = index"
                  class="node-chip"
                  [matTooltip]="'Node ID: ' + id"
                  (click)="copyToClipboard(id)">{{ (lineage.basisNodeTitles && lineage.basisNodeTitles[i]) ? lineage.basisNodeTitles[i] : id }}</span>
            <span *ngIf="lineage.basisNodeIds.length > 20" class="overflow-chip">
              +{{ lineage.basisNodeIds.length - 20 }} more
            </span>
          </div>
        </div>

        <div class="lineage-block-inner" *ngIf="lineage.supportingRuleTexts?.length">
          <span class="lineage-label">Supporting rules:</span>
          <!-- Human-readable prose summary always shown -->
          <p class="rules-summary">{{ rulesSummary(lineage.supportingRuleTexts) }}
            <button mat-button class="dev-toggle-btn" (click)="showDevDetails = !showDevDetails">
              {{ showDevDetails ? 'Hide details' : 'Show details' }}
            </button>
          </p>
          <!-- Raw PSL rules: dev-only detail view -->
          <pre *ngIf="showDevDetails" class="rules-block">{{ lineage.supportingRuleTexts.join('\\n') }}</pre>
        </div>

        <div class="lineage-block-inner" *ngIf="lineage.causalActivityPairs?.length">
          <span class="lineage-label">Causal pairs:</span>
          <div class="causal-pairs">
            <span *ngFor="let pair of lineage.causalActivityPairs" class="causal-pair">{{ pair }}</span>
          </div>
        </div>

      </div>
    </ng-template>
  `,
  styleUrls: ['./process-lineage-panel.component.css'],
})
export class ProcessLineagePanelComponent extends BaseService implements OnInit, OnChanges {
  @Input() factSheetId: number | null = null;

  process: ProcessSuggestion | null = null;
  noise = 0.0;
  loading = false;
  error: string | null = null;
  hasLoaded = false;
  /** When true, low-level PSL formalism (atom key, raw rule text) is shown in the lineage panel. */
  showDevDetails = false;

  constructor(private http: HttpClient) {
    super();
  }

  ngOnInit(): void {
    if (this.factSheetId != null) {
      this.loadProcess();
    }
  }

  ngOnChanges(changes: SimpleChanges): void {
    if (changes['factSheetId'] && !changes['factSheetId'].firstChange && this.factSheetId != null) {
      this.process = null;
      this.error = null;
      this.hasLoaded = false;
      this.loadProcess();
    }
  }

  loadProcess(): void {
    if (this.factSheetId == null) return;
    this.loading = true;
    this.error = null;

    const params = new HttpParams()
      .set('factSheetId', String(this.factSheetId))
      .set('noise', String(this.noise));

    this.http.get<ProcessSuggestion>(`${this.backendUrl}/process/mining/discover`, { params }).subscribe({
      next: (p) => {
        this.loading = false;
        this.hasLoaded = true;
        this.process = p;
      },
      error: (err) => {
        this.loading = false;
        this.hasLoaded = true;
        if (err?.status === 204 || err?.status === 404) {
          this.process = null;
          this.error = null;
        } else {
          this.error = err?.error?.message || err?.message || 'Failed to discover process';
        }
      },
    });
  }

  stepIcon(stepType: string): string {
    const icons: Record<string, string> = {
      AUTO: 'smart_toy',
      HUMAN: 'person',
      APPROVE: 'check_circle',
      TOOL_CALL: 'build',
      EXCEL_COMPUTE: 'table_chart',
      SCRIPT: 'code',
      HTTP_CALL: 'http',
    };
    return icons[stepType] ?? 'play_arrow';
  }

  copyToClipboard(text: string): void {
    if (navigator.clipboard) {
      navigator.clipboard.writeText(text);
    }
  }

  /**
   * Produces a human-readable prose summary of a set of PSL rules.
   * Attempts to extract a simple "A often leads to B (NN%)" sentence from the first rule;
   * falls back to a generic count label when the rule format is unrecognised.
   *
   * Example rule: `0.87: Occurs("INVOICE") -> Occurs("PAYMENT") ^2`
   * Result:        `INVOICE often leads to PAYMENT (87% confidence). 3 supporting rules.`
   */
  rulesSummary(rules: string[]): string {
    if (!rules || rules.length === 0) return '';
    const count = rules.length;
    const first = rules[0];
    // Parse pattern:  <weight>: Occurs("<A>") -> Occurs("<B>") ...
    const m = first.match(/^([\d.]+):\s*\w+\("([^"]+)"\)\s*->\s*\w+\("([^"]+)"\)/);
    if (m) {
      const pct = Math.round(parseFloat(m[1]) * 100);
      const a = m[2];
      const b = m[3];
      const suffix = count > 1 ? ` ${count} supporting rules.` : '';
      return `${a} often leads to ${b} (${pct}% confidence).${suffix}`;
    }
    return `${count} supporting rule${count !== 1 ? 's' : ''}.`;
  }
}
