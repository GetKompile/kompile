/*
 *   Copyright 2025 Kompile Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 * limitations under the License.
 */

import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { HttpClient } from '@angular/common/http';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatCardModule } from '@angular/material/card';
import { MatTooltipModule } from '@angular/material/tooltip';
import { MatDividerModule } from '@angular/material/divider';

/** Mirrors ProcessMiningConfig.toMap() — the kompile-managed process-mining tunables. */
export interface ProcessMiningConfig {
  miningAnchorEntityType: string;
  miningClusterJaccardThreshold: number;
  miningClusterMaxProcesses: number;
  miningDeclareMinSupport: number;
  miningDeclareMinConfidence: number;
  miningEntailAssertThreshold: number;
  miningEntailMaterializeThreshold: number;
  miningRecencyHalfLifeDays: number;
  miningWeightLearningMinLabels: number;
  miningWeightLearningEpochs: number;
  miningHybridSemanticWeight: number;
  miningActorInvolvementMinShare: number;
  miningGuardMinAccuracy: number;
  miningAliasSimilarityThreshold: number;
  miningIdentityJaccardThreshold: number;
  miningChangePointMinWindowCases: number;
  miningConflictMinorityShare: number;
  miningTaxonomyMinSiblings: number;
}

interface FieldDef {
  key: keyof ProcessMiningConfig;
  label: string;
  hint: string;
  min?: number;
  max?: number;
  step?: number;
}

interface SectionDef {
  title: string;
  description: string;
  fields: FieldDef[];
}

/**
 * Settings editor for the kompile-managed process-mining configuration
 * (process-mining-config.json via /api/process-mining-config) — clustering, Declare floors,
 * entailment thresholds, temporal recency decay, rule-weight learning, hybrid semantic blend,
 * and the observed-actor role gate. Mirrors the KB Confidence settings tab.
 */
@Component({
  selector: 'app-process-mining-settings',
  standalone: true,
  imports: [
    CommonModule, FormsModule,
    MatButtonModule, MatIconModule, MatProgressSpinnerModule,
    MatFormFieldModule, MatInputModule, MatCardModule,
    MatTooltipModule, MatDividerModule
  ],
  template: `
    <div class="pm-settings">
      <div class="header-row">
        <div>
          <h3>Process Mining &amp; Entailment</h3>
          <p class="subtitle">
            Kompile-managed tunables for process discovery (process-mining-config.json) —
            hot-reloaded; the next mine picks changes up immediately.
          </p>
        </div>
        <div class="header-actions">
          <button mat-stroked-button (click)="resetToDefaults()" [disabled]="loading || saving">
            <mat-icon>restart_alt</mat-icon> Reset to defaults
          </button>
          <button mat-raised-button color="primary" (click)="save()" [disabled]="loading || saving">
            <mat-icon>save</mat-icon> {{ saving ? 'Saving…' : 'Save' }}
          </button>
        </div>
      </div>

      <div class="status error" *ngIf="error">{{ error }}</div>
      <div class="status success" *ngIf="successMessage">{{ successMessage }}</div>
      <mat-spinner diameter="28" *ngIf="loading"></mat-spinner>

      <div class="sections" *ngIf="!loading && config">
        <mat-card class="section" *ngFor="let section of sections">
          <mat-card-header>
            <mat-card-title>{{ section.title }}</mat-card-title>
            <mat-card-subtitle>{{ section.description }}</mat-card-subtitle>
          </mat-card-header>
          <mat-card-content>
            <div class="field-grid">
              <mat-form-field appearance="outline" *ngFor="let field of section.fields"
                              [matTooltip]="field.hint">
                <mat-label>{{ field.label }}</mat-label>
                <input matInput type="number" [(ngModel)]="config[field.key]"
                       [min]="field.min ?? null" [max]="field.max ?? null"
                       [step]="field.step ?? 0.05">
                <mat-hint>{{ field.hint }}</mat-hint>
              </mat-form-field>
            </div>
          </mat-card-content>
        </mat-card>

        <mat-card class="section">
          <mat-card-header>
            <mat-card-title>Case notion</mat-card-title>
            <mat-card-subtitle>
              How graph nodes group into process instances before mining.
            </mat-card-subtitle>
          </mat-card-header>
          <mat-card-content>
            <mat-form-field appearance="outline" class="anchor-field"
                            matTooltip="Object-centric case notion: each entity of this type anchors one process instance. Blank = connected-component correlation.">
              <mat-label>Anchor entity type</mat-label>
              <input matInput type="text" [(ngModel)]="config.miningAnchorEntityType"
                     placeholder="blank = connected components (e.g. INVOICE, TICKET)">
              <mat-hint>One process instance per entity of this type; blank = connected components</mat-hint>
            </mat-form-field>
          </mat-card-content>
        </mat-card>
      </div>
    </div>
  `,
  styles: [`
    .pm-settings { max-width: 1100px; }
    .header-row { display: flex; justify-content: space-between; align-items: flex-start; gap: 16px; }
    .header-row h3 { margin: 0 0 4px 0; }
    .subtitle { margin: 0 0 12px 0; font-size: 13px; color: var(--text-secondary, #999); }
    .header-actions { display: flex; gap: 8px; white-space: nowrap; }
    .status { padding: 8px 12px; border-radius: 4px; margin: 8px 0; font-size: 13px; }
    .status.error { background: rgba(239,83,80,0.15); color: #ef5350; }
    .status.success { background: rgba(102,187,106,0.15); color: #66bb6a; }
    .sections { display: flex; flex-direction: column; gap: 16px; margin-top: 8px; }
    .section mat-card-subtitle { font-size: 12px; }
    .field-grid {
      display: grid; grid-template-columns: repeat(auto-fill, minmax(240px, 1fr));
      gap: 12px; margin-top: 12px;
    }
    .anchor-field { width: 100%; max-width: 480px; margin-top: 12px; }
    mat-hint { font-size: 11px; }
  `]
})
export class ProcessMiningSettingsComponent implements OnInit {

  private readonly baseUrl = '/api/process-mining-config';

  config: ProcessMiningConfig | null = null;
  loading = false;
  saving = false;
  error: string | null = null;
  successMessage: string | null = null;

  readonly sections: SectionDef[] = [
    {
      title: 'Trace clustering',
      description: 'How traces split into distinct business processes before the Inductive Miner runs.',
      fields: [
        { key: 'miningClusterJaccardThreshold', label: 'Cluster Jaccard threshold',
          hint: 'Activity-set overlap ≥ this ⇒ same process (0–1)', min: 0, max: 1 },
        { key: 'miningClusterMaxProcesses', label: 'Max processes per fact sheet',
          hint: 'Mine at most this many trace clusters, largest first', min: 1, max: 100, step: 1 }
      ]
    },
    {
      title: 'Declare mining',
      description: 'Floors for the declarative constraints (RESPONSE/PRECEDENCE/…) feeding entailment.',
      fields: [
        { key: 'miningDeclareMinSupport', label: 'Min support',
          hint: 'Fraction of traces a constraint must cover (0–1)', min: 0, max: 1 },
        { key: 'miningDeclareMinConfidence', label: 'Min confidence',
          hint: 'Satisfaction rate a constraint must reach (0–1)', min: 0, max: 1 }
      ]
    },
    {
      title: 'Entailment & temporal evidence',
      description: 'The PSL Precedes settlement: what gets asserted, materialized, and how time votes.',
      fields: [
        { key: 'miningEntailAssertThreshold', label: 'KB assert threshold',
          hint: 'Min posterior to promote precedes(...) facts into the KB (0–1)', min: 0, max: 1 },
        { key: 'miningEntailMaterializeThreshold', label: 'Edge materialize threshold',
          hint: 'Min posterior for PRECEDES edge write-back onto the graph (0–1)', min: 0, max: 1 },
        { key: 'miningRecencyHalfLifeDays', label: 'Recency half-life (days)',
          hint: 'Temporal votes halve per this many days of age; 0 disables decay', min: 0, max: 36500, step: 1 },
        { key: 'miningWeightLearningMinLabels', label: 'Weight-learning min labels',
          hint: 'Min temporally-labeled pairs before rule weights are learned; 0 disables', min: 0, max: 100000, step: 1 },
        { key: 'miningWeightLearningEpochs', label: 'Weight-learning epochs',
          hint: 'Pseudolikelihood epochs (deterministic, full batch)', min: 1, max: 100000, step: 1 }
      ]
    },
    {
      title: 'Hybrid activation & roles',
      description: 'Semantic blending of the PSL ⊕ Bayesian consensus, and the observed-actor role gate.',
      fields: [
        { key: 'miningHybridSemanticWeight', label: 'Semantic blend weight',
          hint: 'Cosine-to-centroid contribution when activity embeddings exist; 0 = structural only', min: 0, max: 1 },
        { key: 'miningActorInvolvementMinShare', label: 'Actor involvement min share',
          hint: 'Involvement-only actors must touch this share of an activity instances to win its role (0–1)',
          min: 0, max: 1 }
      ]
    },
    {
      title: 'Decision & alias mining',
      description: 'XOR guards from event attributes, and embedding-based activity label unification.',
      fields: [
        { key: 'miningGuardMinAccuracy', label: 'Guard min accuracy',
          hint: 'A decision stump must separate at least this fraction of an XOR’s cases to become a mined guard (0.5–1)',
          min: 0.5, max: 1 },
        { key: 'miningAliasSimilarityThreshold', label: 'Alias similarity threshold',
          hint: 'Activity labels at or above this embedding cosine unify onto one label; > 1 disables',
          min: 0.5, max: 2 },
        { key: 'miningIdentityJaccardThreshold', label: 'Identity overlap threshold',
          hint: 'A re-mined process matching a predecessor at this activity-set Jaccard is the SAME process — lineage + drift diff (0.1–1)',
          min: 0.1, max: 1 },
        { key: 'miningChangePointMinWindowCases', label: 'Change-point min window',
          hint: 'Min dated cases on EACH side of a split before "the process changed around <date>" is claimed',
          min: 1, max: 100000, step: 1 },
        { key: 'miningConflictMinorityShare', label: 'Conflict minority share',
          hint: 'The losing direction must hold this share of votes before a CONFLICT (vs silent-majority noise) is claimed (0.05–0.5)',
          min: 0.05, max: 0.5 },
        { key: 'miningTaxonomyMinSiblings', label: 'Taxonomy min siblings',
          hint: 'Min sibling activities sharing an OWL closure ancestor before they roll up to the concept (< 2 disables)',
          min: 0, max: 1000, step: 1 }
      ]
    }
  ];

  constructor(private http: HttpClient) {}

  ngOnInit(): void {
    this.load();
  }

  load(): void {
    this.loading = true;
    this.error = null;
    this.http.get<ProcessMiningConfig>(this.baseUrl).subscribe({
      next: (config) => {
        this.config = config;
        this.loading = false;
      },
      error: (err) => {
        this.error = 'Failed to load process-mining configuration: ' + (err.error?.message || err.message);
        this.loading = false;
      }
    });
  }

  save(): void {
    if (!this.config) { return; }
    this.saving = true;
    this.error = null;
    this.successMessage = null;
    this.http.post<ProcessMiningConfig>(this.baseUrl, this.config).subscribe({
      next: (updated) => {
        this.config = updated;
        this.saving = false;
        this.successMessage = 'Configuration saved — the next mine uses these values';
        setTimeout(() => this.successMessage = null, 3000);
      },
      error: (err) => {
        this.error = 'Failed to save configuration: ' + (err.error?.error || err.message);
        this.saving = false;
      }
    });
  }

  resetToDefaults(): void {
    this.loading = true;
    this.error = null;
    this.http.get<ProcessMiningConfig>(this.baseUrl + '/defaults').subscribe({
      next: (defaults) => {
        this.config = defaults;
        this.loading = false;
        this.successMessage = 'Defaults loaded — press Save to apply';
        setTimeout(() => this.successMessage = null, 4000);
      },
      error: (err) => {
        this.error = 'Failed to load defaults: ' + (err.error?.message || err.message);
        this.loading = false;
      }
    });
  }
}
