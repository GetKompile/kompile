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
import { MatTooltipModule } from '@angular/material/tooltip';
import { MatDividerModule } from '@angular/material/divider';
import { MatExpansionModule } from '@angular/material/expansion';
import {
  ProcessEngineService,
  ProcessDefinition,
  ProcessStep
} from '../../services/process-engine.service';
import { ProcessDiagramService } from '../../services/process-diagram.service';
import { ProcessAttributionService, ProcessRiskAssessment } from '../../services/process-attribution.service';
import { ProcessBuilderComponent } from './process-builder.component';
import { MermaidRendererComponent } from './mermaid-renderer.component';

type ViewMode = 'list' | 'create' | 'detail';

@Component({
  standalone: true,
  selector: 'app-process-definitions',
  imports: [
    CommonModule, FormsModule,
    MatIconModule, MatButtonModule, MatCardModule,
    MatFormFieldModule, MatInputModule, MatSnackBarModule,
    MatChipsModule, MatDividerModule, MatExpansionModule, MatTooltipModule,
    ProcessBuilderComponent, MermaidRendererComponent
  ],
  template: `
    <div class="definitions-container">
      <!-- Toolbar -->
      <div class="section-toolbar">
        <button mat-raised-button color="primary" (click)="startCreate()" *ngIf="viewMode === 'list'">
          <mat-icon>add</mat-icon> New Process
        </button>
        <button mat-button (click)="viewMode = 'list'" *ngIf="viewMode !== 'list'">
          <mat-icon>arrow_back</mat-icon> Back to List
        </button>
        <button mat-icon-button (click)="loadDefinitions()" *ngIf="viewMode === 'list'">
          <mat-icon>refresh</mat-icon>
        </button>
      </div>

      <!-- List View -->
      <div *ngIf="viewMode === 'list'" class="list-view">
        <div *ngIf="definitions.length === 0" class="empty-state">
          <mat-icon class="empty-icon">account_tree</mat-icon>
          <p>No process definitions found</p>
          <button mat-raised-button color="primary" (click)="startCreate()">
            <mat-icon>add</mat-icon> Create First Process
          </button>
        </div>

        <div class="def-grid" *ngIf="definitions.length > 0">
          <div *ngFor="let def of definitions" class="def-card" (click)="viewDetail(def)">
            <div class="card-header">
              <mat-icon class="def-icon">account_tree</mat-icon>
              <div class="card-title-block">
                <span class="card-name">{{ def.name }}</span>
                <span class="card-version">v{{ def.version || 1 }}</span>
              </div>
              <mat-chip-set>
                <mat-chip [class]="'status-chip status-' + (def.status || 'DRAFT').toLowerCase()">
                  {{ def.status || 'DRAFT' }}
                </mat-chip>
              </mat-chip-set>
            </div>
            <mat-divider></mat-divider>
            <div class="card-stats">
              <div class="stat">
                <mat-icon>layers</mat-icon>
                <span>{{ def.phases?.length || 0 }} phases</span>
              </div>
              <div class="stat">
                <mat-icon>checklist</mat-icon>
                <span>{{ countSteps(def) }} steps</span>
              </div>
            </div>
          </div>
        </div>
      </div>

      <!-- Create View -->
      <div *ngIf="viewMode === 'create'" class="create-view">
        <h3>Create Process Definition</h3>

        <div class="create-mode-toggle">
          <button mat-stroked-button [class.active-mode]="!useJsonMode" (click)="useJsonMode = false">
            <mat-icon>build</mat-icon> Visual Builder
          </button>
          <button mat-stroked-button [class.active-mode]="useJsonMode" (click)="useJsonMode = true">
            <mat-icon>code</mat-icon> Paste JSON
          </button>
        </div>

        <!-- Visual Builder -->
        <div *ngIf="!useJsonMode">
          <app-process-builder (saved)="onBuilderSaved($event)"></app-process-builder>
        </div>

        <!-- JSON Mode -->
        <div *ngIf="useJsonMode">
          <p class="hint">Paste a valid process definition JSON. Required: <code>name</code>, <code>phases</code> array.</p>
          <mat-form-field appearance="outline" class="full-width">
            <mat-label>Process Definition JSON</mat-label>
            <textarea matInput [(ngModel)]="createJson" rows="24" class="json-editor"
                      placeholder='{"name": "my-process", "phases": [{"id": "p1", "name": "Phase 1", "order": 1, "steps": [...]}]}'>
            </textarea>
          </mat-form-field>

          <div class="editor-actions">
            <button mat-button (click)="viewMode = 'list'">Cancel</button>
            <button mat-raised-button color="primary" (click)="createDefinition()" [disabled]="saving || !createJson.trim()">
              <mat-icon>save</mat-icon> {{ saving ? 'Saving...' : 'Create' }}
            </button>
          </div>
        </div>
      </div>

      <!-- Detail View -->
      <div *ngIf="viewMode === 'detail' && selectedDef" class="detail-view">
        <div class="detail-header">
          <mat-icon class="detail-icon">account_tree</mat-icon>
          <div class="detail-title-block">
            <h3>{{ selectedDef.name }}</h3>
            <span class="detail-version">Version {{ selectedDef.version || 1 }}</span>
          </div>
          <mat-chip-set class="detail-status">
            <mat-chip [class]="'status-chip status-' + (selectedDef.status || 'DRAFT').toLowerCase()">
              {{ selectedDef.status || 'DRAFT' }}
            </mat-chip>
          </mat-chip-set>
          <button mat-raised-button color="accent"
                  *ngIf="selectedDef.status === 'DRAFT' || selectedDef.status === 'IN_REVIEW'"
                  (click)="approveDefinition()" [disabled]="approving">
            <mat-icon>verified</mat-icon> {{ approving ? 'Approving...' : 'Approve' }}
          </button>
        </div>

        <mat-divider class="section-divider"></mat-divider>

        <div *ngIf="selectedDef.ontologySchemaId" class="ontology-ref">
          <mat-icon>schema</mat-icon>
          Ontology: <code>{{ selectedDef.ontologySchemaId }}</code>
        </div>

        <!-- Phases -->
        <div class="section-heading">
          <mat-icon>layers</mat-icon>
          <span>Phases ({{ selectedDef.phases?.length || 0 }})</span>
        </div>

        <mat-accordion *ngIf="selectedDef.phases?.length">
          <mat-expansion-panel *ngFor="let phase of selectedDef.phases" class="phase-panel">
            <mat-expansion-panel-header>
              <mat-panel-title>
                <span class="phase-order">{{ phase.order }}</span>
                {{ phase.name }}
              </mat-panel-title>
              <mat-panel-description>{{ phase.steps?.length || 0 }} steps</mat-panel-description>
            </mat-expansion-panel-header>

            <div class="steps-list" *ngIf="phase.steps?.length">
              <div *ngFor="let step of phase.steps" class="step-item">
                <div class="step-header">
                  <mat-chip-set>
                    <mat-chip [class]="'step-type-chip step-' + step.stepType.toLowerCase()">
                      {{ step.stepType }}
                    </mat-chip>
                  </mat-chip-set>
                  <span class="step-name">{{ step.name }}</span>
                </div>
                <p *ngIf="step.description" class="step-desc">{{ step.description }}</p>
                <div class="step-meta" *ngIf="step.inputKeys?.length || step.outputKeys?.length || step.dependsOn?.length || step.conditionLabel || step.conditionExpression || step.requiredRoles?.length || step.requiredPermissions?.length">
                  <span *ngIf="step.inputKeys?.length" class="meta-item">
                    <mat-icon>input</mat-icon> {{ step.inputKeys?.join(', ') }}
                  </span>
                  <span *ngIf="step.outputKeys?.length" class="meta-item">
                    <mat-icon>output</mat-icon> {{ step.outputKeys?.join(', ') }}
                  </span>
                  <span *ngIf="step.dependsOn?.length" class="meta-item">
                    <mat-icon>link</mat-icon> depends on: {{ step.dependsOn?.join(', ') }}
                  </span>
                  <span *ngIf="step.conditionLabel" class="meta-item">
                    <mat-icon>alt_route</mat-icon> branch: {{ step.conditionLabel }}
                  </span>
                  <span *ngIf="step.conditionExpression" class="meta-item">
                    <mat-icon>rule</mat-icon> {{ step.conditionExpression }}
                  </span>
                  <span *ngIf="step.requiredRoles?.length" class="meta-item">
                    <mat-icon>groups</mat-icon> roles: {{ step.requiredRoles?.join(', ') }}
                  </span>
                  <span *ngIf="step.requiredPermissions?.length" class="meta-item">
                    <mat-icon>lock</mat-icon> permissions: {{ step.requiredPermissions?.join(', ') }}
                  </span>
                </div>
              </div>
            </div>

            <div *ngIf="!phase.steps?.length" class="empty-sub">No steps in this phase.</div>
          </mat-expansion-panel>
        </mat-accordion>

        <div *ngIf="!selectedDef.phases?.length" class="empty-sub">No phases defined.</div>

        <!-- Diagram Visualization -->
        <mat-divider class="section-divider"></mat-divider>
        <div class="section-heading">
          <mat-icon>schema</mat-icon>
          <span>Diagram</span>
          <button mat-stroked-button class="diagram-toggle-btn"
                  (click)="toggleDiagram()" [disabled]="loadingDiagram">
            <mat-icon>{{ showDiagram ? 'visibility_off' : 'visibility' }}</mat-icon>
            {{ loadingDiagram ? 'Loading...' : (showDiagram ? 'Hide' : 'View Diagram') }}
          </button>
        </div>
        <div *ngIf="showDiagram" class="diagram-panel">
          <app-mermaid-renderer [code]="diagramMermaidCode"></app-mermaid-renderer>
        </div>
        <div *ngIf="diagramError" class="diagram-error">
          <mat-icon>error_outline</mat-icon> {{ diagramError }}
        </div>

        <!-- Risk Assessment -->
        <mat-divider class="section-divider"></mat-divider>
        <div class="section-heading">
          <mat-icon>security</mat-icon>
          <span>Risk Assessment</span>
          <button mat-stroked-button class="diagram-toggle-btn"
                  (click)="loadDefinitionRisk()" [disabled]="riskLoading"
                  *ngIf="!definitionRisk">
            <mat-icon>{{ riskLoading ? 'hourglass_empty' : 'assessment' }}</mat-icon>
            {{ riskLoading ? 'Assessing...' : 'Assess Risk' }}
          </button>
          <button mat-stroked-button class="diagram-toggle-btn"
                  (click)="definitionRisk = null"
                  *ngIf="definitionRisk">
            <mat-icon>close</mat-icon> Clear
          </button>
        </div>

        <div *ngIf="definitionRisk" class="risk-panel">
          <div class="risk-overview">
            <div class="risk-score-card" [class]="'risk-' + definitionRisk.riskLevel?.toLowerCase()">
              <span class="risk-score-value">{{ (definitionRisk.overallRiskScore * 100).toFixed(0) }}%</span>
              <span class="risk-score-label">{{ definitionRisk.riskLevel }} risk</span>
            </div>
            <div class="risk-stats">
              <span>{{ definitionRisk.alerts?.length || 0 }} alerts</span>
              <span>{{ definitionRisk.highRiskStepIds?.length || 0 }} high-risk steps</span>
            </div>
          </div>

          <div *ngIf="definitionRisk.summary" class="risk-summary">{{ definitionRisk.summary }}</div>

          <div *ngIf="definitionRisk.highRiskStepIds?.length" class="high-risk-steps">
            <span class="risk-section-label">High-Risk Steps</span>
            <mat-chip-set>
              <mat-chip *ngFor="let sid of definitionRisk.highRiskStepIds" class="high-risk-chip">
                <mat-icon>warning</mat-icon> {{ sid }}
              </mat-chip>
            </mat-chip-set>
          </div>

          <!-- Per-step Bayesian Priors & Posteriors -->
          <div *ngIf="definitionRisk.stepAttributionResults && hasKeys(definitionRisk.stepAttributionResults)"
               class="risk-bayesian-steps">
            <span class="risk-section-label">Step Bayesian Analysis</span>
            <div *ngFor="let stepId of getObjectKeys(definitionRisk.stepAttributionResults)" class="bayesian-step-card">
              <div class="bayesian-step-header">
                <span class="bayesian-step-name">{{ definitionRisk.stepAttributionResults![stepId].stepName || stepId }}</span>
                <span *ngIf="definitionRisk.stepAttributionResults![stepId].confidenceBand"
                      class="confidence-band-chip"
                      [class]="'band-' + definitionRisk.stepAttributionResults![stepId].confidenceBand?.toLowerCase()">
                  {{ definitionRisk.stepAttributionResults![stepId].confidenceBand }}
                </span>
                <span class="bayesian-step-risk"
                      [style.color]="getRiskColor(definitionRisk.stepAttributionResults![stepId].riskScore)">
                  {{ (definitionRisk.stepAttributionResults![stepId].riskScore * 100).toFixed(0) }}%
                </span>
              </div>
              <div *ngIf="definitionRisk.stepAttributionResults![stepId].bayesianPosteriors
                          && hasKeys(definitionRisk.stepAttributionResults![stepId].bayesianPosteriors)"
                   class="bayesian-node-list">
                <div *ngFor="let nodeId of getObjectKeys(definitionRisk.stepAttributionResults![stepId].bayesianPosteriors)"
                     class="bayesian-node-row">
                  <span class="bayesian-node-id" [matTooltip]="nodeId">{{ nodeId | slice:0:18 }}</span>
                  <ng-container *ngIf="definitionRisk.stepAttributionResults![stepId].mebnMeta?.[nodeId] as meta">
                    <span class="mebn-badge-sm mfrag-sm">{{meta.mfragName}}</span>
                    <span class="mebn-badge-sm role-sm"
                          [class.role-sm-resident]="meta.nodeRole === 'RESIDENT'"
                          [class.role-sm-input]="meta.nodeRole === 'INPUT'">{{meta.nodeRole}}</span>
                    <span *ngIf="meta.entityType" class="mebn-badge-sm etype-sm">{{meta.entityType}}</span>
                  </ng-container>
                  <span class="bayesian-node-vals">
                    <span class="bayesian-prior-val"
                          *ngIf="definitionRisk.stepAttributionResults![stepId].bayesianPriors?.[nodeId] != null">
                      {{ ((definitionRisk.stepAttributionResults![stepId].bayesianPriors[nodeId]) * 100).toFixed(1) }}%
                    </span>
                    <mat-icon class="bayesian-shift-arrow"
                              *ngIf="definitionRisk.stepAttributionResults![stepId].bayesianPriors?.[nodeId] != null">
                      arrow_forward</mat-icon>
                    <span class="bayesian-posterior-val"
                          [style.color]="getRiskColor(definitionRisk.stepAttributionResults![stepId].bayesianPosteriors[nodeId])">
                      {{ (definitionRisk.stepAttributionResults![stepId].bayesianPosteriors[nodeId] * 100).toFixed(1) }}%
                    </span>
                  </span>
                </div>
              </div>
            </div>
          </div>

          <div *ngIf="definitionRisk.alerts?.length" class="risk-alerts">
            <span class="risk-section-label">Alerts</span>
            <div *ngFor="let alert of definitionRisk.alerts" class="risk-alert-card"
                 [class]="'alert-' + alert.severity?.toLowerCase()">
              <div class="alert-header-row">
                <mat-icon>{{ alert.severity === 'CRITICAL' ? 'error' :
                  alert.severity === 'HIGH' ? 'warning' : 'info' }}</mat-icon>
                <span class="alert-title">{{ alert.title }}</span>
                <mat-chip class="severity-chip-sm">{{ alert.severity }}</mat-chip>
              </div>
              <p class="alert-text" *ngIf="alert.explanation">{{ alert.explanation }}</p>
              <div class="def-alert-meta">
                <span *ngIf="alert.causalChains?.length">{{alert.causalChains.length}} causal chains</span>
                <span *ngIf="alert.predictions?.length">{{alert.predictions.length}} predictions</span>
                <span>{{(alert.confidence * 100).toFixed(0)}}% confidence</span>
                <span class="def-alert-type" *ngIf="alert.alertType">{{alert.alertType}}</span>
                <span class="def-alert-id" *ngIf="alert.alertId" [matTooltip]="alert.alertId">ID: {{alert.alertId | slice:0:8}}</span>
                <span class="def-alert-ts" *ngIf="alert.createdAt">{{alert.createdAt | slice:0:19}}</span>
                <mat-icon *ngIf="alert.acknowledged" class="def-ack-icon" matTooltip="Acknowledged">check_circle</mat-icon>
                <mat-icon *ngIf="alert.llmUsed" class="def-llm-icon" matTooltip="LLM-assisted">smart_toy</mat-icon>
              </div>
              <!-- Causal chains detail -->
              <div *ngIf="alert.causalChains?.length" class="def-alert-chains">
                <div *ngFor="let chain of alert.causalChains" class="def-chain-row">
                  <span class="def-chain-node" [matTooltip]="chain.rootCauseNodeId">{{chain.rootCauseTitle || chain.rootCauseNodeId | slice:0:18}}</span>
                  <mat-icon class="def-chain-arrow">arrow_forward</mat-icon>
                  <span class="def-chain-node" [matTooltip]="chain.targetEventNodeId">{{chain.targetEventTitle || chain.targetEventNodeId | slice:0:18}}</span>
                  <span class="def-chain-conf" [style.color]="getRiskColor(chain.overallConfidence)">
                    {{(chain.overallConfidence * 100).toFixed(0)}}%
                  </span>
                </div>
              </div>
              <!-- Predictions detail -->
              <div *ngIf="alert.predictions?.length" class="def-alert-preds">
                <div *ngFor="let pred of alert.predictions" class="def-pred-row">
                  <span class="def-pred-title" [matTooltip]="pred.nodeId">{{pred.title || pred.nodeId | slice:0:20}}</span>
                  <span class="def-pred-prob" [style.color]="getRiskColor(pred.probability)">
                    {{(pred.probability * 100).toFixed(0)}}%
                  </span>
                  <span class="def-pred-hops">{{pred.hopsFromSource}} hops</span>
                </div>
              </div>
              <div *ngIf="alert.bayesianPosteriors && hasKeys(alert.bayesianPosteriors)"
                   class="def-alert-bayesian">
                <div *ngFor="let nodeId of getObjectKeys(alert.bayesianPosteriors)" class="bayesian-node-row">
                  <span class="bayesian-node-id" [matTooltip]="nodeId">{{ nodeId | slice:0:18 }}</span>
                  <span class="bayesian-node-vals">
                    <span class="bayesian-prior-val"
                          *ngIf="alert.bayesianPriors?.[nodeId] != null">
                      {{ ((alert.bayesianPriors![nodeId]) * 100).toFixed(1) }}%
                    </span>
                    <mat-icon class="bayesian-shift-arrow"
                              *ngIf="alert.bayesianPriors?.[nodeId] != null">arrow_forward</mat-icon>
                    <span class="bayesian-posterior-val"
                          [style.color]="getRiskColor(alert.bayesianPosteriors[nodeId])">
                      {{ (alert.bayesianPosteriors[nodeId] * 100).toFixed(1) }}%
                    </span>
                  </span>
                  <ng-container *ngIf="alert.mebnMeta?.[nodeId] as meta">
                    <div class="def-alert-mebn-badges">
                      <span class="def-alert-mebn mfrag-badge-sm">{{meta.mfragName}}</span>
                      <span class="def-alert-mebn role-badge-sm"
                            [class.role-badge-resident]="meta.nodeRole === 'RESIDENT'"
                            [class.role-badge-input]="meta.nodeRole === 'INPUT'">{{meta.nodeRole}}</span>
                      <span *ngIf="meta.entityType" class="def-alert-mebn etype-badge-sm">{{meta.entityType}}</span>
                    </div>
                  </ng-container>
                </div>
              </div>
            </div>
          </div>
        </div>

        <div *ngIf="riskError" class="diagram-error">
          <mat-icon>error_outline</mat-icon> {{ riskError }}
        </div>
      </div>
    </div>
  `,
  styles: [`
    .definitions-container { padding: 12px 0; }
    .section-toolbar { display: flex; align-items: center; gap: 8px; margin-bottom: 16px; }
    .empty-state { text-align: center; padding: 60px 20px; color: #999; }
    .empty-icon { font-size: 56px; width: 56px; height: 56px; color: #555; display: block; margin: 0 auto 12px; }

    .def-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(280px, 1fr)); gap: 12px; }
    .def-card {
      background: #1e1e2e; border: 1px solid rgba(255,255,255,0.08); border-radius: 8px;
      cursor: pointer; transition: border-color 0.2s;
    }
    .def-card:hover { border-color: rgba(144,202,249,0.5); }
    .card-header { display: flex; align-items: center; gap: 10px; padding: 12px 14px; }
    .def-icon { color: #90caf9; font-size: 24px; width: 24px; height: 24px; flex-shrink: 0; }
    .card-title-block { flex: 1; }
    .card-name { display: block; font-weight: 500; font-size: 14px; }
    .card-version { font-size: 11px; color: #888; }
    .card-stats { display: flex; gap: 16px; padding: 10px 14px; }
    .stat { display: flex; align-items: center; gap: 6px; font-size: 12px; color: #aaa; }
    .stat mat-icon { font-size: 16px; width: 16px; height: 16px; }

    .status-chip { font-size: 11px !important; min-height: 22px !important; font-weight: 600 !important; }
    .status-draft { background: rgba(144,202,249,0.15) !important; color: #90caf9 !important; }
    .status-in_review { background: rgba(255,183,77,0.15) !important; color: #ffb74d !important; }
    .status-approved { background: rgba(129,199,132,0.15) !important; color: #81c784 !important; }
    .status-live { background: rgba(129,199,132,0.3) !important; color: #81c784 !important; }
    .status-deprecated { background: rgba(239,83,80,0.15) !important; color: #ef5350 !important; }

    .create-view { max-width: 900px; }
    .create-view h3 { margin: 0 0 4px; }
    .hint { font-size: 12px; color: #aaa; margin: 0 0 16px; }
    .hint code { background: #2a2a3e; padding: 2px 6px; border-radius: 4px; }
    .full-width { width: 100%; }
    .json-editor { font-family: 'JetBrains Mono', 'Fira Code', monospace; font-size: 12px; }
    .editor-actions { display: flex; gap: 8px; justify-content: flex-end; margin-top: 8px; }

    .detail-header { display: flex; align-items: center; gap: 12px; margin-bottom: 16px; flex-wrap: wrap; }
    .detail-icon { font-size: 36px; width: 36px; height: 36px; color: #90caf9; flex-shrink: 0; }
    .detail-title-block { flex: 1; }
    .detail-title-block h3 { margin: 0; font-size: 18px; }
    .detail-version { font-size: 12px; color: #888; }
    .detail-status { margin-left: 4px; }
    .section-divider { margin: 16px 0; }

    .ontology-ref { display: flex; align-items: center; gap: 6px; font-size: 12px; color: #aaa; margin-bottom: 12px; }
    .ontology-ref mat-icon { font-size: 16px; width: 16px; height: 16px; }
    .ontology-ref code { background: #2a2a3e; padding: 2px 6px; border-radius: 4px; color: #81c784; }

    .section-heading { display: flex; align-items: center; gap: 8px; font-size: 14px; font-weight: 500; margin: 16px 0 8px; }
    .section-heading mat-icon { font-size: 18px; width: 18px; height: 18px; color: #90caf9; }

    .phase-panel { background: #1e1e2e !important; margin-bottom: 4px !important; }
    .phase-order {
      display: inline-flex; align-items: center; justify-content: center;
      width: 22px; height: 22px; border-radius: 50%; background: rgba(144,202,249,0.2);
      color: #90caf9; font-size: 12px; font-weight: 600; margin-right: 8px; flex-shrink: 0;
    }

    .steps-list { display: flex; flex-direction: column; gap: 8px; }
    .step-item {
      background: #141420; border: 1px solid rgba(255,255,255,0.06); border-radius: 6px;
      padding: 10px 12px;
    }
    .step-header { display: flex; align-items: center; gap: 10px; margin-bottom: 4px; }
    .step-name { font-weight: 500; font-size: 13px; }
    .step-desc { font-size: 12px; color: #aaa; margin: 4px 0 6px; }
    .step-meta { display: flex; flex-wrap: wrap; gap: 12px; font-size: 11px; color: #777; }
    .meta-item { display: flex; align-items: center; gap: 4px; }
    .meta-item mat-icon { font-size: 14px; width: 14px; height: 14px; }

    .step-type-chip { font-size: 10px !important; min-height: 20px !important; font-weight: 700 !important; }
    .step-auto { background: rgba(129,199,132,0.2) !important; color: #81c784 !important; }
    .step-tool_call { background: rgba(255,213,79,0.2) !important; color: #ffd54f !important; }
    .step-http_call { background: rgba(79,195,247,0.2) !important; color: #4fc3f7 !important; }
    .step-script { background: rgba(174,213,129,0.2) !important; color: #aed581 !important; }
    .step-excel_compute { background: rgba(102,187,106,0.2) !important; color: #66bb6a !important; }
    .step-pipeline { background: rgba(149,117,205,0.2) !important; color: #9575cd !important; }
    .step-camel_route { background: rgba(255,138,101,0.2) !important; color: #ff8a65 !important; }
    .step-drools_rule { background: rgba(240,98,146,0.2) !important; color: #f06292 !important; }
    .step-drools_inference { background: rgba(236,64,122,0.2) !important; color: #ec407a !important; }
    .step-drools_decision_table { background: rgba(244,143,177,0.2) !important; color: #f48fb1 !important; }
    .step-workflow { background: rgba(77,182,172,0.2) !important; color: #4db6ac !important; }
    .step-approve { background: rgba(255,183,77,0.2) !important; color: #ffb74d !important; }
    .step-human { background: rgba(144,202,249,0.2) !important; color: #90caf9 !important; }
    .step-control_gate { background: rgba(239,83,80,0.2) !important; color: #ef5350 !important; }

    .empty-sub { font-size: 13px; color: #666; padding: 8px 0; }

    .create-mode-toggle { display: flex; gap: 8px; margin-bottom: 16px; }
    .create-mode-toggle button { font-size: 12px; }
    .active-mode { background: rgba(144,202,249,0.15) !important; color: #90caf9 !important; border-color: rgba(144,202,249,0.4) !important; }

    .diagram-toggle-btn { margin-left: auto; font-size: 12px; height: 30px; line-height: 30px; }
    .diagram-toggle-btn mat-icon { font-size: 16px; width: 16px; height: 16px; margin-right: 4px; }
    .diagram-panel { margin-top: 8px; height: 500px; }
    .diagram-error { display: flex; align-items: center; gap: 6px; color: #ef5350; font-size: 12px; margin-top: 8px; }
    .diagram-error mat-icon { font-size: 16px; width: 16px; height: 16px; }

    /* Risk Assessment */
    .risk-panel { margin-top: 8px; }
    .risk-overview { display: flex; align-items: center; gap: 16px; margin-bottom: 12px; }
    .risk-score-card { padding: 12px 16px; border-radius: 8px; text-align: center; }
    .risk-score-value { font-size: 24px; font-weight: 700; display: block; }
    .risk-score-label { font-size: 11px; text-transform: uppercase; letter-spacing: 0.5px; }
    .risk-critical, .risk-high { background: rgba(239,68,68,0.15); color: #ef5350; }
    .risk-medium { background: rgba(255,183,77,0.15); color: #ffb74d; }
    .risk-low, .risk-info { background: rgba(102,187,106,0.15); color: #66bb6a; }
    .risk-stats { display: flex; flex-direction: column; gap: 4px; font-size: 12px; color: #888; }
    .risk-summary { font-size: 12px; color: #bbb; background: rgba(255,255,255,0.03); padding: 10px; border-radius: 6px; margin-bottom: 12px; border-left: 3px solid #667eea; }
    .risk-section-label { font-size: 11px; font-weight: 600; color: #999; text-transform: uppercase; letter-spacing: 0.5px; display: block; margin-bottom: 6px; }
    .high-risk-steps { margin-bottom: 12px; }
    .risk-bayesian-steps { margin-bottom: 12px; }
    .bayesian-step-card { padding: 8px 10px; border-radius: 6px; margin-bottom: 6px; background: rgba(255,255,255,0.02); border: 1px solid rgba(255,255,255,0.06); }
    .bayesian-step-header { display: flex; justify-content: space-between; align-items: center; margin-bottom: 4px; }
    .bayesian-step-name { font-size: 13px; font-weight: 500; color: #ccc; }
    .bayesian-step-risk { font-size: 13px; font-weight: 600; font-family: monospace; }
    .bayesian-node-list { padding-left: 8px; }
    .bayesian-node-row { display: flex; justify-content: space-between; align-items: center; padding: 2px 0; font-size: 12px; }
    .bayesian-node-id { color: #aaa; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; max-width: 120px; font-size: 11px; }
    .bayesian-node-vals { display: flex; align-items: center; gap: 4px; }
    .bayesian-prior-val { color: #888; font-size: 11px; font-family: monospace; }
    .bayesian-shift-arrow { font-size: 12px; width: 12px; height: 12px; color: #666; }
    .bayesian-posterior-val { font-weight: 600; font-family: monospace; }
    .high-risk-chip { background: rgba(239,83,80,0.15) !important; color: #ef5350 !important; font-size: 11px !important; }
    .high-risk-chip mat-icon { font-size: 14px !important; width: 14px !important; height: 14px !important; }
    .risk-alerts { margin-bottom: 8px; }
    .risk-alert-card { padding: 8px 10px; border-radius: 6px; margin-bottom: 6px; border: 1px solid rgba(255,255,255,0.06); }
    .alert-critical { border-left: 3px solid #ef5350; }
    .alert-high { border-left: 3px solid #ff7043; }
    .alert-medium { border-left: 3px solid #ffb74d; }
    .alert-low, .alert-info { border-left: 3px solid #66bb6a; }
    .alert-header-row { display: flex; align-items: center; gap: 8px; }
    .alert-header-row mat-icon { font-size: 16px; width: 16px; height: 16px; }
    .alert-title { flex: 1; font-size: 13px; font-weight: 500; }
    .severity-chip-sm { font-size: 10px !important; height: 20px !important; min-height: 20px !important; }
    .alert-text { font-size: 12px; color: #bbb; margin: 4px 0 0; }
    .def-alert-meta { display: flex; gap: 10px; font-size: 11px; color: #888; margin-top: 4px; flex-wrap: wrap; align-items: center; }
    .def-alert-type { padding: 1px 5px; border-radius: 3px; background: rgba(144,202,249,0.12); color: #90caf9; font-size: 9px; font-weight: 500; }
    .def-alert-id { font-family: monospace; font-size: 9px; color: #777; }
    .def-alert-ts { font-family: monospace; font-size: 9px; color: #777; }
    .def-ack-icon { font-size: 14px !important; width: 14px !important; height: 14px !important; color: #66bb6a; }
    .def-llm-icon { font-size: 14px !important; width: 14px !important; height: 14px !important; color: #ce93d8; }
    .def-alert-mebn-badges { display: flex; gap: 4px; margin-top: 2px; }
    .def-alert-mebn { font-size: 9px; padding: 1px 5px; border-radius: 3px; font-weight: 600; }
    .mfrag-badge-sm { background: rgba(102,126,234,0.15); color: #90caf9; }
    .role-badge-sm { background: rgba(255,255,255,0.06); color: #aaa; }
    .role-badge-resident { color: #81c784; }
    .role-badge-input { color: #ffb74d; }
    .etype-badge-sm { background: rgba(206,147,216,0.15); color: #ce93d8; }
    .def-alert-chains, .def-alert-preds { margin-top: 4px; padding-top: 4px; border-top: 1px solid rgba(255,255,255,0.04); }
    .def-chain-row, .def-pred-row { display: flex; align-items: center; gap: 6px; padding: 2px 0; font-size: 11px; }
    .def-chain-node { color: #bbb; max-width: 100px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
    .def-chain-arrow { font-size: 12px; width: 12px; height: 12px; color: #666; }
    .def-chain-conf { font-weight: 600; font-family: monospace; min-width: 30px; text-align: right; }
    .def-pred-title { color: #bbb; max-width: 120px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
    .def-pred-prob { font-weight: 600; font-family: monospace; min-width: 30px; text-align: right; }
    .def-pred-hops { color: #888; font-size: 10px; }
    .def-alert-bayesian { margin-top: 6px; padding-top: 6px; border-top: 1px solid rgba(255,255,255,0.06); }
    .confidence-band-chip { font-size: 10px; font-weight: 500; padding: 1px 6px; border-radius: 3px; letter-spacing: 0.3px; }
    .band-definitive { background: rgba(239,83,80,0.12); color: #ef5350; }
    .band-high { background: rgba(255,112,67,0.12); color: #ff7043; }
    .band-moderate { background: rgba(255,183,77,0.12); color: #ffb74d; }
    .band-low { background: rgba(102,187,106,0.12); color: #66bb6a; }
    .band-insufficient { background: rgba(158,158,158,0.12); color: #9e9e9e; }

    /* Small MEBN badges */
    .mebn-badge-sm { display: inline-block; padding: 0 4px; border-radius: 3px; font-size: 9px; font-weight: 500; line-height: 16px; letter-spacing: 0.3px; margin-right: 2px; }
    .mfrag-sm { background: rgba(144,202,249,0.12); color: #64b5f6; border: 1px solid rgba(144,202,249,0.2); }
    .role-sm { background: rgba(206,147,216,0.12); color: #ce93d8; border: 1px solid rgba(206,147,216,0.2); }
    .role-sm-resident { background: rgba(102,187,106,0.12); color: #66bb6a; border-color: rgba(102,187,106,0.2); }
    .role-sm-input { background: rgba(255,183,77,0.12); color: #ffb74d; border-color: rgba(255,183,77,0.2); }
    .etype-sm { background: rgba(245,158,11,0.1); color: #d97706; border: 1px solid rgba(245,158,11,0.2); }
  `]
})
export class ProcessDefinitionsComponent implements OnInit {
  viewMode: ViewMode = 'list';
  definitions: ProcessDefinition[] = [];
  selectedDef: ProcessDefinition | null = null;
  createJson = '';
  saving = false;
  approving = false;
  useJsonMode = false;
  showDiagram = false;
  loadingDiagram = false;
  diagramMermaidCode: string | null = null;
  diagramError: string | null = null;

  // Risk assessment state
  definitionRisk: ProcessRiskAssessment | null = null;
  riskLoading = false;
  riskError: string | null = null;

  constructor(
    private processEngineService: ProcessEngineService,
    private diagramService: ProcessDiagramService,
    private processAttributionService: ProcessAttributionService,
    private snackBar: MatSnackBar
  ) {}

  ngOnInit(): void {
    this.loadDefinitions();
  }

  loadDefinitions(): void {
    this.processEngineService.listProcessDefinitions().subscribe({
      next: (result) => { this.definitions = result; },
      error: () => { this.definitions = []; }
    });
  }

  startCreate(): void {
    this.viewMode = 'create';
    this.createJson = '';
    this.useJsonMode = false;
  }

  onBuilderSaved(definition: ProcessDefinition): void {
    this.saving = true;
    this.processEngineService.createProcess(definition).subscribe({
      next: (result) => {
        this.snackBar.open('Process created: ' + result.name, 'Close', { duration: 3000 });
        this.definitions = [...this.definitions, result];
        this.saving = false;
        this.viewMode = 'list';
      },
      error: (err) => {
        this.snackBar.open('Failed to create: ' + (err.error?.message || err.message), 'Close', { duration: 4000 });
        this.saving = false;
      }
    });
  }

  createDefinition(): void {
    let definition: ProcessDefinition;
    try {
      definition = JSON.parse(this.createJson);
    } catch {
      this.snackBar.open('Invalid JSON', 'Close', { duration: 3000 });
      return;
    }
    this.saving = true;
    this.processEngineService.createProcess(definition).subscribe({
      next: (result) => {
        this.snackBar.open('Process created: ' + result.name, 'Close', { duration: 3000 });
        this.definitions = [...this.definitions, result];
        this.saving = false;
        this.viewMode = 'list';
      },
      error: (err) => {
        this.snackBar.open('Failed to create: ' + (err.error?.message || err.message), 'Close', { duration: 4000 });
        this.saving = false;
      }
    });
  }

  viewDetail(def: ProcessDefinition): void {
    this.showDiagram = false;
    this.diagramMermaidCode = null;
    this.diagramError = null;
    this.definitionRisk = null;
    this.riskError = null;
    if (!def.id) {
      this.selectedDef = def;
      this.viewMode = 'detail';
      return;
    }
    this.processEngineService.getProcess(def.id, def.version ?? 1).subscribe({
      next: (result) => {
        this.selectedDef = result;
        this.viewMode = 'detail';
      },
      error: () => {
        this.selectedDef = def;
        this.viewMode = 'detail';
      }
    });
  }

  approveDefinition(): void {
    if (!this.selectedDef?.id) {
      this.snackBar.open('Definition must be saved before approving', 'Close', { duration: 3000 });
      return;
    }
    this.approving = true;
    this.processEngineService.approveProcess(this.selectedDef.id, 'ui-user').subscribe({
      next: (result) => {
        this.selectedDef = result;
        const idx = this.definitions.findIndex(d => d.id === result.id);
        if (idx >= 0) this.definitions[idx] = result;
        this.snackBar.open('Process approved', 'Close', { duration: 3000 });
        this.approving = false;
      },
      error: (err) => {
        this.snackBar.open('Approval failed: ' + (err.error?.message || err.message), 'Close', { duration: 4000 });
        this.approving = false;
      }
    });
  }

  countSteps(def: ProcessDefinition): number {
    return (def.phases || []).reduce((sum, p) => sum + (p.steps?.length ?? 0), 0);
  }

  loadDefinitionRisk(): void {
    if (!this.selectedDef?.id) {
      this.riskError = 'Process must be saved before assessing risk';
      return;
    }
    this.riskLoading = true;
    this.riskError = null;
    this.processAttributionService.assessDefinitionRisk(this.selectedDef.id, this.selectedDef.version ?? 1)
      .subscribe({
        next: (result) => {
          this.definitionRisk = result;
          this.riskLoading = false;
        },
        error: (err) => {
          this.riskError = 'Failed to assess risk: ' + (err.error?.message || err.message || 'Unknown error');
          this.riskLoading = false;
        }
      });
  }

  toggleDiagram(): void {
    if (this.showDiagram) {
      this.showDiagram = false;
      return;
    }
    if (!this.selectedDef?.id) {
      this.diagramError = 'Process must be saved before rendering a diagram';
      return;
    }
    this.loadingDiagram = true;
    this.diagramError = null;
    this.diagramService.renderProcessDiagram(this.selectedDef.id).subscribe({
      next: (result) => {
        this.diagramMermaidCode = result.mermaidCode;
        this.showDiagram = true;
        this.loadingDiagram = false;
      },
      error: (err) => {
        this.diagramError = 'Failed to render diagram: ' + (err.error?.message || err.message || 'Unknown error');
        this.loadingDiagram = false;
      }
    });
  }

  hasKeys(obj: any): boolean {
    return obj && Object.keys(obj).length > 0;
  }

  getObjectKeys(obj: Record<string, any>): string[] {
    return obj ? Object.keys(obj) : [];
  }

  getRiskColor(score: number): string {
    if (score >= 0.7) return '#ef5350';
    if (score >= 0.4) return '#ff9800';
    return '#66bb6a';
  }
}
