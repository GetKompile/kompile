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

import { Component, Input, Output, EventEmitter, OnChanges, SimpleChanges, OnDestroy } from '@angular/core';
import { CommonModule } from '@angular/common';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatIconModule } from '@angular/material/icon';
import { MatButtonModule } from '@angular/material/button';
import { MatTooltipModule } from '@angular/material/tooltip';
import { MatSlideToggleModule } from '@angular/material/slide-toggle';
import { MatExpansionModule } from '@angular/material/expansion';
import { MatChipsModule } from '@angular/material/chips';
import { FormsModule } from '@angular/forms';
import { Subject, takeUntil } from 'rxjs';
import { BayesianService } from '../../services/bayesian.service';
import { BayesianInferenceResult, MpeResult } from '../../models/attribution-models';

interface PosteriorEntry {
  variable: string;
  title: string;
  nodeId: string;
  posterior: number;
  prior?: number;
  sensitivity?: number;
  mfragName?: string;
  nodeRole?: string;
  entityType?: string;
  entityId?: string;
}

@Component({
  selector: 'app-bayesian-panel',
  standalone: true,
  imports: [
    CommonModule,
    MatProgressSpinnerModule,
    MatIconModule,
    MatButtonModule,
    MatTooltipModule,
    MatSlideToggleModule,
    MatExpansionModule,
    MatChipsModule,
    FormsModule
  ],
  template: `
    <div class="bayesian-panel">
      <div *ngIf="!nodeId" class="no-selection">
        <mat-icon>psychology</mat-icon>
        <p>Select a node to view Bayesian inference</p>
      </div>

      <div *ngIf="nodeId && loading" class="loading">
        <mat-spinner diameter="30"></mat-spinner>
        <span>Computing posteriors...</span>
      </div>

      <div *ngIf="nodeId && !loading && result" class="results">
        <div class="panel-header">
          <mat-slide-toggle [(ngModel)]="useMebn" (ngModelChange)="refresh()">
            MEBN
          </mat-slide-toggle>
          <button mat-icon-button (click)="refresh()" matTooltip="Refresh inference">
            <mat-icon>refresh</mat-icon>
          </button>
          <button mat-icon-button (click)="toggleOverlay()" [class.active]="overlayActive"
                  matTooltip="Toggle posterior overlay on graph">
            <mat-icon>layers</mat-icon>
          </button>
        </div>

        <div class="stats-row">
          <span class="stat">{{entries.length}} variables</span>
          <span class="stat">{{result.computationTimeMs}}ms</span>
          <span class="stat" *ngIf="result.networkStats?.['mFrags']">{{result.networkStats['mFrags']}} MFrags</span>
          <span class="stat" *ngIf="result.networkStats?.['entityTypes']">{{result.networkStats['entityTypes']}} entity types</span>
          <span class="stat" *ngIf="networkStatsData?.['variables']">{{networkStatsData!['variables']}} network vars</span>
          <span class="stat" *ngIf="networkStatsData?.['edges']">{{networkStatsData!['edges']}} edges</span>
        </div>

        <!-- Sensitivity Baseline -->
        <div *ngIf="sensitivityBaselinePosterior != null || sensitivityQueryPrior != null" class="sensitivity-baseline">
          <mat-icon class="baseline-icon">tune</mat-icon>
          <span *ngIf="sensitivityQueryPrior != null" class="baseline-item">
            Query Prior: <strong>{{(sensitivityQueryPrior * 100).toFixed(1)}}%</strong>
          </span>
          <mat-icon *ngIf="sensitivityQueryPrior != null && sensitivityBaselinePosterior != null" class="baseline-arrow">arrow_forward</mat-icon>
          <span *ngIf="sensitivityBaselinePosterior != null" class="baseline-item">
            Baseline Posterior: <strong [style.color]="getPosteriorColor(sensitivityBaselinePosterior)">{{(sensitivityBaselinePosterior * 100).toFixed(1)}}%</strong>
          </span>
        </div>

        <!-- MEBN Structure Details -->
        <mat-expansion-panel *ngIf="result.networkStats?.['mFragDetails']?.length" class="mebn-structure-panel">
          <mat-expansion-panel-header>
            <mat-panel-title>
              MEBN Structure ({{result.networkStats['name'] || 'MTheory'}})
            </mat-panel-title>
          </mat-expansion-panel-header>
          <div class="mebn-structure">
            <!-- Entity Types -->
            <div *ngIf="result.networkStats['entityTypeDetails']?.length" class="mebn-section">
              <span class="mebn-section-label">Entity Types</span>
              <div *ngFor="let et of result.networkStats['entityTypeDetails']" class="mebn-entity-type">
                <span class="mebn-type-name">{{et.typeName}}</span>
                <span class="mebn-type-count">{{et.entityCount}} entities</span>
              </div>
            </div>
            <!-- MFrags -->
            <div class="mebn-section">
              <span class="mebn-section-label">MFrags</span>
              <div *ngFor="let frag of result.networkStats['mFragDetails']" class="mebn-mfrag">
                <div class="mfrag-header">
                  <span class="mfrag-name">{{frag.name}}</span>
                </div>
                <div class="mfrag-vars" *ngIf="frag.residentVariables?.length">
                  <span class="mfrag-var-label">Resident:</span>
                  <span *ngFor="let rv of frag.residentVariables" class="mfrag-var-chip">{{rv}}</span>
                </div>
                <div class="mfrag-vars" *ngIf="frag.inputVariables?.length">
                  <span class="mfrag-var-label">Input:</span>
                  <span *ngFor="let iv of frag.inputVariables" class="mfrag-var-chip input-chip">{{iv}}</span>
                </div>
                <div class="mfrag-vars" *ngIf="frag.contextConstraints?.length">
                  <span class="mfrag-var-label">Context:</span>
                  <span *ngFor="let ctx of frag.contextConstraints" class="mfrag-ctx">{{ctx}}</span>
                </div>
                <div class="mfrag-edges" *ngIf="frag.edgeStrengths">
                  <div *ngFor="let edge of getObjectEntries(frag.edgeStrengths)" class="mfrag-edge">
                    <span class="edge-name">{{edge[0]}}</span>
                    <span class="edge-strength" [style.color]="getPosteriorColor(edge[1])">
                      {{(edge[1] * 100).toFixed(0)}}%
                    </span>
                  </div>
                </div>
              </div>
            </div>
          </div>
        </mat-expansion-panel>

        <div class="posteriors-list">
          <div *ngFor="let entry of entries; trackBy: trackByVariable"
               class="posterior-entry"
               [class.highlight]="entry.nodeId === nodeId">
            <div class="entry-header">
              <span class="entry-title" [matTooltip]="entry.variable">
                {{entry.title || entry.variable}}
              </span>
              <span class="entry-value" [style.color]="getPosteriorColor(entry.posterior)">
                {{(entry.posterior * 100).toFixed(1)}}%
              </span>
            </div>
            <div *ngIf="entry.mfragName" class="entry-mebn-meta">
              <span class="mebn-badge mfrag-badge" [matTooltip]="'MFrag: ' + entry.mfragName">
                {{entry.mfragName}}
              </span>
              <span class="mebn-badge role-badge"
                    [class.role-resident]="entry.nodeRole === 'RESIDENT'"
                    [class.role-input]="entry.nodeRole === 'INPUT'">
                {{entry.nodeRole}}
              </span>
              <span *ngIf="entry.entityType" class="mebn-badge entity-badge"
                    [matTooltip]="'Entity: ' + (entry.entityId || '')">
                {{entry.entityType}}
              </span>
            </div>
            <div class="entry-bar">
              <div class="bar-fill posterior-bar"
                   [style.width.%]="entry.posterior * 100"
                   [style.background-color]="getPosteriorColor(entry.posterior)">
              </div>
              <div *ngIf="entry.prior !== undefined"
                   class="bar-marker prior-marker"
                   [style.left.%]="entry.prior * 100"
                   matTooltip="Prior: {{(entry.prior * 100).toFixed(1)}}%">
              </div>
            </div>
            <div *ngIf="entry.prior !== undefined" class="entry-prior-label">
              <span class="prior-text">Prior: {{(entry.prior * 100).toFixed(1)}}%</span>
              <mat-icon class="prior-arrow">arrow_forward</mat-icon>
              <span class="posterior-text" [style.color]="getPosteriorColor(entry.posterior)">
                Post: {{(entry.posterior * 100).toFixed(1)}}%
              </span>
            </div>
            <div *ngIf="entry.sensitivity !== undefined" class="entry-sensitivity">
              <span class="sensitivity-label">Sensitivity:</span>
              <span class="sensitivity-value">{{(entry.sensitivity * 100).toFixed(2)}}%</span>
            </div>
          </div>
        </div>

        <mat-expansion-panel *ngIf="result.inferenceTrace && result.inferenceTrace.length > 0">
          <mat-expansion-panel-header>
            <mat-panel-title>Inference Trace ({{result.inferenceTrace.length}} steps)</mat-panel-title>
          </mat-expansion-panel-header>
          <div class="trace-list">
            <div *ngFor="let step of result.inferenceTrace" class="trace-step">
              <span class="step-op">{{step.operation}}</span>
              <span class="step-var">{{step.eliminatedTitle || step.eliminatedVariable}}</span>
              <span *ngIf="step.priorValue !== undefined" class="step-prior">
                {{(step.priorValue * 100).toFixed(1)}}%
              </span>
              <mat-icon *ngIf="step.priorValue !== undefined && step.posteriorValue !== undefined"
                        class="step-arrow">arrow_forward</mat-icon>
              <span *ngIf="step.posteriorValue !== undefined" class="step-value"
                    [style.color]="getPosteriorColor(step.posteriorValue)">
                {{(step.posteriorValue * 100).toFixed(1)}}%
              </span>
            </div>
          </div>
        </mat-expansion-panel>

        <!-- What-If Query -->
        <mat-expansion-panel>
          <mat-expansion-panel-header>
            <mat-panel-title>
              <mat-icon class="section-icon">science</mat-icon>
              What-If Analysis
            </mat-panel-title>
          </mat-expansion-panel-header>
          <div class="whatif-section">
            <p class="section-desc">Toggle evidence to see how posteriors change under hypothetical conditions.</p>
            <div class="evidence-chips">
              <mat-chip-set>
                <mat-chip *ngFor="let entry of entries"
                         [class.evidence-true]="whatIfEvidence[entry.variable] === 1"
                         [class.evidence-false]="whatIfEvidence[entry.variable] === 0"
                         (click)="toggleWhatIfEvidence(entry.variable)">
                  <mat-icon matChipAvatar>
                    {{whatIfEvidence[entry.variable] === 1 ? 'check_circle' :
                      whatIfEvidence[entry.variable] === 0 ? 'cancel' : 'radio_button_unchecked'}}
                  </mat-icon>
                  {{entry.title || entry.variable}}
                </mat-chip>
              </mat-chip-set>
            </div>
            <div class="whatif-actions">
              <button mat-stroked-button (click)="clearWhatIfEvidence()" [disabled]="!hasWhatIfEvidence()">
                <mat-icon>clear_all</mat-icon> Clear
              </button>
              <button mat-flat-button color="primary" (click)="runWhatIf()"
                      [disabled]="!hasWhatIfEvidence() || whatIfLoading">
                <mat-icon>play_arrow</mat-icon> Run What-If
              </button>
            </div>
            <div *ngIf="whatIfLoading" class="loading">
              <mat-spinner diameter="24"></mat-spinner>
              <span>Computing...</span>
            </div>
            <div *ngIf="whatIfResult" class="whatif-results">
              <div class="whatif-comparison">
                <div *ngFor="let entry of getWhatIfEntries()" class="posterior-entry">
                  <div class="entry-header">
                    <span class="entry-title" [matTooltip]="entry.variable">
                      {{entry.title || entry.variable}}
                    </span>
                    <span class="entry-value">
                      <span [style.color]="getPosteriorColor(entry.prior || 0)">
                        {{((entry.prior || 0) * 100).toFixed(1)}}%
                      </span>
                      <mat-icon class="arrow-icon">arrow_forward</mat-icon>
                      <span [style.color]="getPosteriorColor(entry.posterior)">
                        {{(entry.posterior * 100).toFixed(1)}}%
                      </span>
                    </span>
                  </div>
                  <div *ngIf="entry.mfragName" class="entry-mebn-meta">
                    <span class="mebn-badge mfrag-badge">{{entry.mfragName}}</span>
                    <span class="mebn-badge role-badge"
                          [class.role-resident]="entry.nodeRole === 'RESIDENT'"
                          [class.role-input]="entry.nodeRole === 'INPUT'">
                      {{entry.nodeRole}}
                    </span>
                    <span *ngIf="entry.entityType" class="mebn-badge entity-badge">{{entry.entityType}}</span>
                  </div>
                  <div class="entry-bar">
                    <div class="bar-fill" [style.width.%]="entry.posterior * 100"
                         [style.background-color]="getPosteriorColor(entry.posterior)"></div>
                    <div *ngIf="entry.prior !== undefined"
                         class="bar-marker prior-marker"
                         [style.left.%]="(entry.prior || 0) * 100"
                         matTooltip="Prior: {{((entry.prior || 0) * 100).toFixed(1)}}%"></div>
                  </div>
                </div>
              </div>
              <mat-expansion-panel *ngIf="whatIfResult.inferenceTrace && whatIfResult.inferenceTrace.length > 0">
                <mat-expansion-panel-header>
                  <mat-panel-title>What-If Trace ({{whatIfResult.inferenceTrace.length}} steps)</mat-panel-title>
                </mat-expansion-panel-header>
                <div class="trace-list">
                  <div *ngFor="let step of whatIfResult.inferenceTrace" class="trace-step">
                    <span class="step-op">{{step.operation}}</span>
                    <span class="step-var">{{step.eliminatedTitle || step.eliminatedVariable}}</span>
                    <span *ngIf="step.priorValue !== undefined" class="step-prior">
                      {{(step.priorValue * 100).toFixed(1)}}%
                    </span>
                    <mat-icon *ngIf="step.priorValue !== undefined && step.posteriorValue !== undefined"
                              class="step-arrow">arrow_forward</mat-icon>
                    <span *ngIf="step.posteriorValue !== undefined" class="step-value"
                          [style.color]="getPosteriorColor(step.posteriorValue)">
                      {{(step.posteriorValue * 100).toFixed(1)}}%
                    </span>
                  </div>
                </div>
              </mat-expansion-panel>
            </div>
          </div>
        </mat-expansion-panel>

        <!-- MPE (Most Probable Explanation) -->
        <mat-expansion-panel>
          <mat-expansion-panel-header>
            <mat-panel-title>
              <mat-icon class="section-icon">auto_awesome</mat-icon>
              Most Probable Explanation
            </mat-panel-title>
          </mat-expansion-panel-header>
          <div class="mpe-section">
            <p class="section-desc">Find the most likely joint state of all variables given the evidence.</p>
            <button mat-flat-button color="primary" (click)="runMpe()" [disabled]="mpeLoading">
              <mat-icon>play_arrow</mat-icon> Run MPE
            </button>
            <div *ngIf="mpeLoading" class="loading">
              <mat-spinner diameter="24"></mat-spinner>
              <span>Computing MPE...</span>
            </div>
            <div *ngIf="mpeResult" class="mpe-results">
              <div class="stats-row">
                <span class="stat">{{mpeResult.computationTimeMs}}ms</span>
                <span class="stat">{{getMpeEntries().length}} assignments</span>
              </div>
              <div class="posteriors-list">
                <div *ngFor="let entry of getMpeEntries()" class="posterior-entry">
                  <div class="entry-header">
                    <span class="entry-title" [matTooltip]="entry.variable">
                      {{entry.title || entry.variable}}
                    </span>
                    <span class="mpe-assignment">
                      <mat-icon [class.mpe-true]="entry.assignment === 1"
                                [class.mpe-false]="entry.assignment === 0">
                        {{entry.assignment === 1 ? 'check_circle' : 'cancel'}}
                      </mat-icon>
                      <span class="mpe-state">{{entry.assignment === 1 ? 'TRUE' : 'FALSE'}}</span>
                    </span>
                  </div>
                  <div *ngIf="entry.mfragName" class="entry-mebn-meta">
                    <span class="mebn-badge mfrag-badge">{{entry.mfragName}}</span>
                    <span class="mebn-badge role-badge"
                          [class.role-resident]="entry.nodeRole === 'RESIDENT'"
                          [class.role-input]="entry.nodeRole === 'INPUT'">
                      {{entry.nodeRole}}
                    </span>
                    <span *ngIf="entry.entityType" class="mebn-badge entity-badge">{{entry.entityType}}</span>
                  </div>
                  <div class="mpe-posteriors">
                    <span class="mpe-label">Prior:</span>
                    <span [style.color]="getPosteriorColor(entry.prior || 0)">
                      {{((entry.prior || 0) * 100).toFixed(1)}}%
                    </span>
                    <mat-icon class="arrow-icon">arrow_forward</mat-icon>
                    <span class="mpe-label">Posterior:</span>
                    <span [style.color]="getPosteriorColor(entry.posterior)">
                      {{(entry.posterior * 100).toFixed(1)}}%
                    </span>
                  </div>
                  <div class="entry-bar">
                    <div class="bar-fill" [style.width.%]="entry.posterior * 100"
                         [style.background-color]="getPosteriorColor(entry.posterior)"></div>
                    <div *ngIf="entry.prior !== undefined"
                         class="bar-marker prior-marker"
                         [style.left.%]="(entry.prior || 0) * 100"
                         matTooltip="Prior: {{((entry.prior || 0) * 100).toFixed(1)}}%"></div>
                  </div>
                </div>
              </div>
              <mat-expansion-panel *ngIf="mpeResult.inferenceTrace && mpeResult.inferenceTrace.length > 0">
                <mat-expansion-panel-header>
                  <mat-panel-title>MPE Trace ({{mpeResult.inferenceTrace.length}} steps)</mat-panel-title>
                </mat-expansion-panel-header>
                <div class="trace-list">
                  <div *ngFor="let step of mpeResult.inferenceTrace" class="trace-step">
                    <span class="step-op">{{step.operation}}</span>
                    <span class="step-var">{{step.eliminatedTitle || step.eliminatedVariable}}</span>
                    <span *ngIf="step.priorValue !== undefined" class="step-prior">
                      {{(step.priorValue * 100).toFixed(1)}}%
                    </span>
                    <mat-icon *ngIf="step.priorValue !== undefined && step.posteriorValue !== undefined"
                              class="step-arrow">arrow_forward</mat-icon>
                    <span *ngIf="step.posteriorValue !== undefined" class="step-value"
                          [style.color]="getPosteriorColor(step.posteriorValue)">
                      {{(step.posteriorValue * 100).toFixed(1)}}%
                    </span>
                  </div>
                </div>
              </mat-expansion-panel>
            </div>
          </div>
        </mat-expansion-panel>
      </div>

      <div *ngIf="nodeId && !loading && error" class="error">
        <mat-icon>error_outline</mat-icon>
        <p>{{error}}</p>
        <button mat-stroked-button (click)="refresh()">Retry</button>
      </div>
    </div>
  `,
  styles: [`
    .bayesian-panel {
      padding: 8px;
      max-height: 500px;
      overflow-y: auto;
    }

    .no-selection, .loading, .error {
      display: flex;
      flex-direction: column;
      align-items: center;
      gap: 8px;
      padding: 24px;
      color: var(--text-secondary, #6b7280);
    }

    .loading {
      flex-direction: row;
    }

    .panel-header {
      display: flex;
      align-items: center;
      gap: 8px;
      margin-bottom: 8px;
    }

    .panel-header .active {
      color: #667eea;
    }

    .stats-row {
      display: flex;
      gap: 16px;
      font-size: 12px;
      color: var(--text-secondary, #6b7280);
      margin-bottom: 12px;
    }

    .posteriors-list {
      display: flex;
      flex-direction: column;
      gap: 8px;
    }

    .posterior-entry {
      padding: 6px 8px;
      border-radius: 4px;
      background: var(--bg-surface, #fff);
      border: 1px solid var(--border-color, #e5e7eb);
    }

    .posterior-entry.highlight {
      border-color: #667eea;
      background: rgba(102, 126, 234, 0.05);
    }

    .entry-header {
      display: flex;
      justify-content: space-between;
      align-items: center;
      margin-bottom: 4px;
    }

    .entry-title {
      font-size: 13px;
      font-weight: 500;
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
      max-width: 200px;
    }

    .entry-value {
      font-size: 14px;
      font-weight: 600;
      font-family: monospace;
    }

    .entry-bar {
      position: relative;
      height: 6px;
      background: var(--bg-body, #f3f4f6);
      border-radius: 3px;
      overflow: visible;
    }

    .bar-fill {
      height: 100%;
      border-radius: 3px;
      transition: width 0.3s ease;
    }

    .bar-marker {
      position: absolute;
      top: -2px;
      width: 2px;
      height: 10px;
      background: #1a1f36;
      border-radius: 1px;
    }

    .sensitivity-baseline {
      display: flex;
      align-items: center;
      gap: 6px;
      padding: 6px 8px;
      margin-bottom: 8px;
      background: rgba(255,255,255,0.03);
      border-radius: 4px;
      font-size: 11px;
      color: var(--text-secondary, #6b7280);
    }
    .baseline-icon { font-size: 14px; width: 14px; height: 14px; color: #90caf9; }
    .baseline-arrow { font-size: 12px; width: 12px; height: 12px; color: #666; }
    .baseline-item strong { font-weight: 600; }

    .entry-sensitivity {
      display: flex;
      gap: 4px;
      margin-top: 4px;
      font-size: 11px;
      color: var(--text-secondary, #6b7280);
    }

    .entry-prior-label {
      display: flex;
      align-items: center;
      gap: 4px;
      margin-top: 4px;
      font-size: 11px;
    }

    .prior-text {
      color: var(--text-secondary, #6b7280);
      font-family: monospace;
    }

    .prior-arrow {
      font-size: 12px;
      height: 12px;
      width: 12px;
      color: var(--text-secondary, #6b7280);
    }

    .posterior-text {
      font-family: monospace;
      font-weight: 600;
    }

    .trace-list {
      display: flex;
      flex-direction: column;
      gap: 4px;
    }

    .trace-step {
      display: flex;
      gap: 8px;
      font-size: 12px;
      padding: 4px;
      border-bottom: 1px solid var(--border-color, #e5e7eb);
    }

    .step-op {
      font-weight: 600;
      color: #667eea;
      min-width: 90px;
    }

    .step-var {
      flex: 1;
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
    }

    .step-value {
      font-family: monospace;
      font-weight: 500;
    }

    .step-prior {
      font-family: monospace;
      font-weight: 500;
      color: var(--text-secondary, #6b7280);
      font-size: 11px;
    }

    .step-arrow {
      font-size: 14px;
      height: 14px;
      width: 14px;
      color: var(--text-secondary, #6b7280);
    }

    .section-icon {
      margin-right: 8px;
      font-size: 18px;
      height: 18px;
      width: 18px;
    }

    .section-desc {
      font-size: 12px;
      color: var(--text-secondary, #6b7280);
      margin: 0 0 12px 0;
    }

    .evidence-chips {
      margin-bottom: 12px;
    }

    .evidence-chips mat-chip {
      cursor: pointer;
      font-size: 12px;
    }

    .evidence-true {
      background-color: rgba(34, 197, 94, 0.15) !important;
      border-color: #22c55e !important;
    }

    .evidence-false {
      background-color: rgba(239, 68, 68, 0.15) !important;
      border-color: #ef4444 !important;
    }

    .whatif-actions {
      display: flex;
      gap: 8px;
      margin-bottom: 12px;
    }

    .whatif-results, .mpe-results {
      margin-top: 12px;
    }

    .whatif-comparison .entry-value {
      display: flex;
      align-items: center;
      gap: 4px;
    }

    .arrow-icon {
      font-size: 14px;
      height: 14px;
      width: 14px;
      color: var(--text-secondary, #6b7280);
    }

    .mpe-section button {
      margin-bottom: 12px;
    }

    .mpe-assignment {
      display: flex;
      align-items: center;
      gap: 4px;
    }

    .mpe-true {
      color: #22c55e;
    }

    .mpe-false {
      color: #ef4444;
    }

    .mpe-state {
      font-size: 11px;
      font-weight: 600;
      font-family: monospace;
    }

    .mpe-posteriors {
      display: flex;
      align-items: center;
      gap: 4px;
      font-size: 12px;
      margin-bottom: 4px;
    }

    .mpe-label {
      color: var(--text-secondary, #6b7280);
      font-size: 11px;
    }

    .mebn-structure-panel {
      margin-bottom: 12px;
    }

    .mebn-structure {
      display: flex;
      flex-direction: column;
      gap: 12px;
    }

    .mebn-section {
      display: flex;
      flex-direction: column;
      gap: 6px;
    }

    .mebn-section-label {
      font-size: 11px;
      font-weight: 600;
      text-transform: uppercase;
      letter-spacing: 0.5px;
      color: #667eea;
      margin-bottom: 2px;
    }

    .mebn-entity-type {
      display: flex;
      justify-content: space-between;
      align-items: center;
      padding: 4px 8px;
      background: var(--bg-surface, #fff);
      border: 1px solid var(--border-color, #e5e7eb);
      border-radius: 4px;
      font-size: 12px;
    }

    .mebn-type-name {
      font-weight: 500;
    }

    .mebn-type-count {
      color: var(--text-secondary, #6b7280);
      font-size: 11px;
    }

    .mebn-mfrag {
      padding: 8px;
      background: var(--bg-surface, #fff);
      border: 1px solid var(--border-color, #e5e7eb);
      border-radius: 6px;
      display: flex;
      flex-direction: column;
      gap: 6px;
    }

    .mfrag-header {
      display: flex;
      align-items: center;
      gap: 6px;
    }

    .mfrag-name {
      font-size: 13px;
      font-weight: 600;
    }

    .mfrag-vars {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      gap: 4px;
      font-size: 11px;
    }

    .mfrag-var-label {
      color: var(--text-secondary, #6b7280);
      font-weight: 500;
      min-width: 50px;
    }

    .mfrag-var-chip {
      padding: 1px 6px;
      border-radius: 3px;
      background: rgba(102, 126, 234, 0.1);
      color: #667eea;
      font-family: monospace;
      font-size: 11px;
    }

    .input-chip {
      background: rgba(139, 92, 246, 0.1);
      color: #8b5cf6;
    }

    .mfrag-ctx {
      padding: 1px 6px;
      border-radius: 3px;
      background: rgba(245, 158, 11, 0.1);
      color: #d97706;
      font-family: monospace;
      font-size: 11px;
    }

    .mfrag-edges {
      display: flex;
      flex-direction: column;
      gap: 2px;
      margin-top: 4px;
      padding-top: 4px;
      border-top: 1px solid var(--border-color, #e5e7eb);
    }

    .mfrag-edge {
      display: flex;
      justify-content: space-between;
      align-items: center;
      font-size: 11px;
    }

    .edge-name {
      font-family: monospace;
      color: var(--text-secondary, #6b7280);
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
      max-width: 180px;
    }

    .edge-strength {
      font-family: monospace;
      font-weight: 600;
    }

    .entry-mebn-meta {
      display: flex;
      flex-wrap: wrap;
      gap: 4px;
      margin: 4px 0 2px;
    }

    .mebn-badge {
      display: inline-flex;
      align-items: center;
      padding: 1px 6px;
      border-radius: 3px;
      font-size: 10px;
      font-weight: 500;
      letter-spacing: 0.3px;
    }

    .mfrag-badge {
      background: rgba(102, 126, 234, 0.12);
      color: #667eea;
      border: 1px solid rgba(102, 126, 234, 0.25);
    }

    .role-badge {
      background: rgba(107, 114, 128, 0.12);
      color: #6b7280;
      border: 1px solid rgba(107, 114, 128, 0.25);
    }

    .role-badge.role-resident {
      background: rgba(34, 197, 94, 0.12);
      color: #16a34a;
      border: 1px solid rgba(34, 197, 94, 0.25);
    }

    .role-badge.role-input {
      background: rgba(139, 92, 246, 0.12);
      color: #8b5cf6;
      border: 1px solid rgba(139, 92, 246, 0.25);
    }

    .entity-badge {
      background: rgba(245, 158, 11, 0.12);
      color: #d97706;
      border: 1px solid rgba(245, 158, 11, 0.25);
    }
  `]
})
export class BayesianPanelComponent implements OnChanges, OnDestroy {
  private destroy$ = new Subject<void>();

  @Input() nodeId: string | null = null;
  @Output() posteriorOverlayChanged = new EventEmitter<Record<string, number> | null>();
  @Output() priorOverlayChanged = new EventEmitter<Record<string, number> | null>();
  @Output() mebnMfragMapChanged = new EventEmitter<Record<string, string> | null>();

  loading = false;
  error: string | null = null;
  result: BayesianInferenceResult | null = null;
  entries: PosteriorEntry[] = [];
  sensitivities: Record<string, number> = {};
  sensitivityBaselinePosterior: number | null = null;
  sensitivityQueryPrior: number | null = null;
  useMebn = true;
  overlayActive = false;

  // Network stats (lightweight, no full inference)
  networkStatsData: Record<string, any> | null = null;
  networkStatsLoading = false;

  // What-If state
  whatIfEvidence: Record<string, number> = {};
  whatIfResult: BayesianInferenceResult | null = null;
  whatIfLoading = false;

  // MPE state
  mpeResult: MpeResult | null = null;
  mpeLoading = false;

  constructor(private bayesianService: BayesianService) {}

  ngOnChanges(changes: SimpleChanges): void {
    if (changes['nodeId'] && this.nodeId) {
      this.refresh();
    }
  }

  ngOnDestroy(): void {
    this.destroy$.next();
    this.destroy$.complete();
  }

  refresh(): void {
    if (!this.nodeId) return;

    this.loading = true;
    this.error = null;

    const inferenceCall = this.useMebn
      ? this.bayesianService.queryMebnFromNode(this.nodeId)
      : this.bayesianService.queryFromNode(this.nodeId);

    inferenceCall.pipe(takeUntil(this.destroy$)).subscribe({
      next: (result) => {
        this.result = result;
        this.buildEntries(result);
        this.loading = false;

        // Also fetch sensitivity (includes priors baseline)
        this.bayesianService.quickSensitivity(this.nodeId!)
          .pipe(takeUntil(this.destroy$))
          .subscribe({
            next: (sensResult) => {
              this.sensitivities = sensResult.sensitivities || {};
              this.sensitivityBaselinePosterior = sensResult.baselinePosterior ?? null;
              this.sensitivityQueryPrior = sensResult.queryPrior ?? null;
              const sensPriors = sensResult.priors || {};
              this.entries.forEach(e => {
                e.sensitivity = this.sensitivities[e.nodeId];
                // Fill in prior from sensitivity result if not already set by inference
                if (e.prior === undefined && sensPriors[e.variable] != null) {
                  e.prior = sensPriors[e.variable];
                }
              });
            },
            error: () => {} // Sensitivity is optional
          });

        if (this.overlayActive) {
          this.emitOverlay();
        }

        // Fetch dedicated network stats for richer metadata
        this.loadNetworkStats();
      },
      error: (err) => {
        this.error = this.extractErr(err, 'Inference failed');
        this.loading = false;
      }
    });
  }

  private loadNetworkStats(): void {
    if (!this.nodeId) return;
    this.networkStatsLoading = true;
    const statCall = this.useMebn
      ? this.bayesianService.mebnStats(this.nodeId)
      : this.bayesianService.networkStats(this.nodeId);
    statCall.pipe(takeUntil(this.destroy$)).subscribe({
      next: (stats) => {
        this.networkStatsData = stats;
        this.networkStatsLoading = false;
      },
      error: () => { this.networkStatsLoading = false; }
    });
  }

  toggleOverlay(): void {
    this.overlayActive = !this.overlayActive;
    if (this.overlayActive && this.result) {
      this.emitOverlay();
    } else {
      this.posteriorOverlayChanged.emit(null);
      this.priorOverlayChanged.emit(null);
      this.mebnMfragMapChanged.emit(null);
    }
  }

  trackByVariable(index: number, entry: PosteriorEntry): string {
    return entry.variable;
  }

  getPosteriorColor(value: number): string {
    // Gradient from blue (low) through yellow to red (high)
    if (value < 0.3) return '#3b82f6';
    if (value < 0.5) return '#f59e0b';
    if (value < 0.7) return '#f97316';
    return '#ef4444';
  }

  getObjectEntries(obj: Record<string, number>): [string, number][] {
    return obj ? Object.entries(obj) as [string, number][] : [];
  }

  // ── What-If ──

  toggleWhatIfEvidence(variable: string): void {
    if (this.whatIfEvidence[variable] === undefined) {
      this.whatIfEvidence[variable] = 1; // Set TRUE
    } else if (this.whatIfEvidence[variable] === 1) {
      this.whatIfEvidence[variable] = 0; // Set FALSE
    } else {
      delete this.whatIfEvidence[variable]; // Clear
    }
  }

  hasWhatIfEvidence(): boolean {
    return Object.keys(this.whatIfEvidence).length > 0;
  }

  clearWhatIfEvidence(): void {
    this.whatIfEvidence = {};
    this.whatIfResult = null;
  }

  runWhatIf(): void {
    if (!this.nodeId || !this.hasWhatIfEvidence()) return;

    this.whatIfLoading = true;
    const seedNodeIds = this.entries.map(e => e.nodeId).filter(id => !!id);

    this.bayesianService.whatIfQuery({
      seedNodeIds,
      hypotheticalEvidence: { ...this.whatIfEvidence }
    }).pipe(takeUntil(this.destroy$)).subscribe({
      next: (result) => {
        this.whatIfResult = result;
        this.whatIfLoading = false;
      },
      error: (err) => {
        this.error = this.extractErr(err, 'What-if query failed');
        this.whatIfLoading = false;
      }
    });
  }

  getWhatIfEntries(): PosteriorEntry[] {
    if (!this.whatIfResult) return [];
    return Object.entries(this.whatIfResult.posteriors)
      .map(([variable, posterior]) => {
        const meta = this.whatIfResult!.variableToMebnMeta?.[variable];
        return {
          variable,
          title: this.whatIfResult!.variableToTitle[variable] || variable,
          nodeId: this.whatIfResult!.variableToNodeId[variable] || '',
          posterior,
          prior: this.whatIfResult!.priors?.[variable] ?? this.result?.priors?.[variable],
          mfragName: meta?.mfragName,
          nodeRole: meta?.nodeRole,
          entityType: meta?.entityType,
          entityId: meta?.entityId
        };
      })
      .sort((a, b) => b.posterior - a.posterior);
  }

  // ── MPE ──

  runMpe(): void {
    if (!this.nodeId) return;

    this.mpeLoading = true;
    const seedNodeIds = this.entries.map(e => e.nodeId).filter(id => !!id);
    const evidence: Record<string, number> = {};

    // Use current evidence from what-if if any
    if (this.hasWhatIfEvidence()) {
      Object.assign(evidence, this.whatIfEvidence);
    }

    this.bayesianService.mostProbableExplanation({
      seedNodeIds,
      evidence
    }).pipe(takeUntil(this.destroy$)).subscribe({
      next: (result) => {
        this.mpeResult = result;
        this.mpeLoading = false;
      },
      error: (err) => {
        this.error = this.extractErr(err, 'MPE query failed');
        this.mpeLoading = false;
      }
    });
  }

  /**
   * Extract a human-readable message from an HttpErrorResponse. The backend now
   * returns a structured { error, message, type, ... } body (err.error) for
   * attribution / Bayesian failures — surface that rather than Angular's opaque
   * "Http failure response for ...: 500 ..." wrapper string.
   */
  private extractErr(err: any, fallback: string): string {
    const body = err?.error;
    if (body && typeof body === 'object' && body.message) {
      return body.type ? `${body.message} (${body.type})` : body.message;
    }
    if (typeof body === 'string' && body.trim().length > 0) {
      return body;
    }
    if (err?.status === 0) {
      return 'Could not reach the server (network error, or it is still starting up).';
    }
    return err?.message || fallback;
  }

  getMpeEntries(): Array<PosteriorEntry & { assignment: number }> {
    if (!this.mpeResult) return [];
    return Object.entries(this.mpeResult.assignments)
      .map(([variable, assignment]) => {
        // Prefer MPE result's own MEBN meta, fall back to main result
        const meta = this.mpeResult!.variableToMebnMeta?.[variable]
          ?? this.result?.variableToMebnMeta?.[variable];
        return {
          variable,
          title: this.mpeResult!.variableToTitle[variable] || variable,
          nodeId: this.mpeResult!.variableToNodeId[variable] || '',
          posterior: this.mpeResult!.posteriors[variable] || 0,
          prior: this.mpeResult!.priors?.[variable],
          assignment,
          mfragName: meta?.mfragName,
          nodeRole: meta?.nodeRole,
          entityType: meta?.entityType,
          entityId: meta?.entityId
        };
      })
      .sort((a, b) => b.posterior - a.posterior);
  }

  private buildEntries(result: BayesianInferenceResult): void {
    this.entries = Object.entries(result.posteriors)
      .map(([variable, posterior]) => {
        const meta = result.variableToMebnMeta?.[variable];
        return {
          variable,
          title: result.variableToTitle[variable] || variable,
          nodeId: result.variableToNodeId[variable] || '',
          posterior,
          prior: result.priors?.[variable],
          mfragName: meta?.mfragName,
          nodeRole: meta?.nodeRole,
          entityType: meta?.entityType,
          entityId: meta?.entityId
        };
      })
      .sort((a, b) => b.posterior - a.posterior);
  }

  private emitOverlay(): void {
    if (!this.result) return;
    // Map KG node IDs to posterior values
    const posteriorMap: Record<string, number> = {};
    for (const [variable, posterior] of Object.entries(this.result.posteriors)) {
      const nodeId = this.result.variableToNodeId[variable];
      if (nodeId) {
        posteriorMap[nodeId] = posterior;
      }
    }
    this.posteriorOverlayChanged.emit(posteriorMap);

    // Map KG node IDs to prior values
    if (this.result.priors && Object.keys(this.result.priors).length > 0) {
      const priorMap: Record<string, number> = {};
      for (const [variable, prior] of Object.entries(this.result.priors)) {
        const nodeId = this.result.variableToNodeId[variable];
        if (nodeId) {
          priorMap[nodeId] = prior;
        }
      }
      this.priorOverlayChanged.emit(priorMap);
    }

    // Map KG node IDs to MFrag names for grouping
    if (this.result.variableToMebnMeta) {
      const mfragMap: Record<string, string> = {};
      for (const [variable, meta] of Object.entries(this.result.variableToMebnMeta)) {
        const nodeId = this.result.variableToNodeId[variable];
        if (nodeId && meta.mfragName) {
          mfragMap[nodeId] = meta.mfragName;
        }
      }
      if (Object.keys(mfragMap).length > 0) {
        this.mebnMfragMapChanged.emit(mfragMap);
      }
    }
  }
}
