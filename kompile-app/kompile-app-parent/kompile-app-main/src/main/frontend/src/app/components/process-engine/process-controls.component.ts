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
import { MatSelectModule } from '@angular/material/select';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';
import { MatChipsModule } from '@angular/material/chips';
import { MatDividerModule } from '@angular/material/divider';
import { MatTooltipModule } from '@angular/material/tooltip';
import {
  ProcessEngineService,
  ControlDefinition,
  ControlAttestation,
  SpelResult
} from '../../services/process-engine.service';
import { ProcessAttributionService, ProcessEventAlert } from '../../services/process-attribution.service';

type ViewMode = 'list' | 'create' | 'detail';

@Component({
  standalone: true,
  selector: 'app-process-controls',
  imports: [
    CommonModule, FormsModule,
    MatIconModule, MatButtonModule, MatCardModule,
    MatFormFieldModule, MatInputModule, MatSelectModule,
    MatSnackBarModule, MatChipsModule, MatDividerModule,
    MatTooltipModule
  ],
  template: `
    <div class="controls-container">
      <!-- Toolbar -->
      <div class="section-toolbar">
        <button mat-raised-button color="primary" (click)="startCreate()" *ngIf="viewMode === 'list'">
          <mat-icon>add</mat-icon> New Control
        </button>
        <button mat-button (click)="backToList()" *ngIf="viewMode !== 'list'">
          <mat-icon>arrow_back</mat-icon> Back to List
        </button>
        <button mat-icon-button (click)="loadControls()" matTooltip="Refresh" *ngIf="viewMode === 'list'">
          <mat-icon>refresh</mat-icon>
        </button>
      </div>

      <!-- List View -->
      <div *ngIf="viewMode === 'list'" class="list-view">
        <div *ngIf="controls.length === 0" class="empty-state">
          <mat-icon class="empty-icon">verified_user</mat-icon>
          <p>No control definitions found</p>
          <button mat-raised-button color="primary" (click)="startCreate()">
            <mat-icon>add</mat-icon> Create First Control
          </button>
        </div>

        <div class="controls-grid" *ngIf="controls.length > 0">
          <div *ngFor="let ctrl of controls" class="control-card" (click)="viewDetail(ctrl)">
            <div class="card-header">
              <mat-icon class="ctrl-icon" [class.hard-gate]="ctrl.gateType === 'HARD'">
                {{ ctrl.gateType === 'HARD' ? 'gpp_bad' : 'gpp_good' }}
              </mat-icon>
              <div class="card-title-block">
                <span class="card-name">{{ ctrl.name }}</span>
                <span class="card-desc" *ngIf="ctrl.description">{{ ctrl.description }}</span>
              </div>
              <mat-chip-set>
                <mat-chip [class]="'gate-chip gate-' + (ctrl.gateType || 'SOFT').toLowerCase()">
                  {{ ctrl.gateType || 'SOFT' }}
                </mat-chip>
              </mat-chip-set>
            </div>
            <mat-divider></mat-divider>
            <div class="card-stats">
              <div class="stat" *ngIf="ctrl.severity">
                <mat-icon [class]="'severity-' + ctrl.severity.toLowerCase()">
                  {{ getSeverityIcon(ctrl.severity) }}
                </mat-icon>
                <span>{{ ctrl.severity }}</span>
              </div>
              <div class="stat" *ngIf="ctrl.expression">
                <mat-icon>code</mat-icon>
                <span class="expr-preview">{{ ctrl.expression | slice:0:40 }}{{ (ctrl.expression?.length || 0) > 40 ? '...' : '' }}</span>
              </div>
              <div class="stat" *ngIf="ctrl.regulatoryReference">
                <mat-icon>policy</mat-icon>
                <span>{{ ctrl.regulatoryReference }}</span>
              </div>
            </div>
          </div>
        </div>
      </div>

      <!-- Create View -->
      <div *ngIf="viewMode === 'create'" class="create-view">
        <h3>Create Control Definition</h3>
        <p class="hint">Define a control gate that evaluates a SpEL expression against run data.</p>

        <mat-form-field appearance="outline" class="full-width">
          <mat-label>Name</mat-label>
          <input matInput [(ngModel)]="newControl.name" placeholder="e.g. sox-revenue-threshold">
        </mat-form-field>

        <mat-form-field appearance="outline" class="full-width">
          <mat-label>Description</mat-label>
          <input matInput [(ngModel)]="newControl.description" placeholder="What this control checks">
        </mat-form-field>

        <div class="form-row">
          <mat-form-field appearance="outline">
            <mat-label>Gate Type</mat-label>
            <mat-select [(ngModel)]="newControl.gateType">
              <mat-option value="HARD">HARD (blocks run)</mat-option>
              <mat-option value="SOFT">SOFT (warning only)</mat-option>
            </mat-select>
          </mat-form-field>

          <mat-form-field appearance="outline">
            <mat-label>Severity</mat-label>
            <mat-select [(ngModel)]="newControl.severity">
              <mat-option value="LOW">LOW</mat-option>
              <mat-option value="MEDIUM">MEDIUM</mat-option>
              <mat-option value="HIGH">HIGH</mat-option>
              <mat-option value="CRITICAL">CRITICAL</mat-option>
            </mat-select>
          </mat-form-field>
        </div>

        <mat-form-field appearance="outline" class="full-width">
          <mat-label>SpEL Expression</mat-label>
          <textarea matInput [(ngModel)]="newControl.expression" rows="3" class="expr-editor"
                    placeholder="e.g. #data['amount'] < 10000"></textarea>
        </mat-form-field>

        <!-- Inline SpEL Test Panel -->
        <div class="test-panel" *ngIf="newControl.expression?.trim()">
          <div class="test-panel-header">
            <mat-icon>science</mat-icon>
            <span>Test Expression</span>
          </div>
          <mat-form-field appearance="outline" class="full-width">
            <mat-label>Test Context JSON</mat-label>
            <textarea matInput [(ngModel)]="testContextJson" rows="3" class="expr-editor"
                      placeholder='{"runData": {"amount": 5000}}'></textarea>
          </mat-form-field>
          <button mat-stroked-button (click)="testExpression()" [disabled]="testingExpr">
            <mat-icon>play_arrow</mat-icon> {{ testingExpr ? 'Testing...' : 'Test' }}
          </button>
          <div *ngIf="testResult" class="test-result" [class.test-pass]="!testResult.error" [class.test-fail]="testResult.error">
            <mat-icon>{{ testResult.error ? 'error' : 'check_circle' }}</mat-icon>
            <div class="test-result-info">
              <span class="test-verdict" *ngIf="!testResult.error">
                Result: {{ testResult.result }} ({{ testResult.type }})
              </span>
              <span class="test-verdict" *ngIf="testResult.error">
                Error: {{ testResult.error }}
              </span>
            </div>
          </div>
        </div>

        <mat-form-field appearance="outline" class="full-width">
          <mat-label>Regulatory Reference (optional)</mat-label>
          <input matInput [(ngModel)]="newControl.regulatoryReference" placeholder="e.g. SOX Section 404">
        </mat-form-field>

        <div class="editor-actions">
          <button mat-button (click)="backToList()">Cancel</button>
          <button mat-raised-button color="primary" (click)="createControl()" [disabled]="saving || !newControl.name?.trim()">
            <mat-icon>save</mat-icon> {{ saving ? 'Saving...' : 'Create' }}
          </button>
        </div>
      </div>

      <!-- Detail View -->
      <div *ngIf="viewMode === 'detail' && selectedControl" class="detail-view">
        <div class="detail-header">
          <mat-icon class="detail-icon" [class.hard-gate]="selectedControl.gateType === 'HARD'">
            {{ selectedControl.gateType === 'HARD' ? 'gpp_bad' : 'gpp_good' }}
          </mat-icon>
          <div class="detail-title-block">
            <h3>{{ selectedControl.name }}</h3>
            <span class="detail-desc" *ngIf="selectedControl.description">{{ selectedControl.description }}</span>
          </div>
          <mat-chip-set>
            <mat-chip [class]="'gate-chip gate-' + (selectedControl.gateType || 'SOFT').toLowerCase()">
              {{ selectedControl.gateType || 'SOFT' }}
            </mat-chip>
            <mat-chip *ngIf="selectedControl.severity"
                      [class]="'severity-chip severity-chip-' + selectedControl.severity.toLowerCase()">
              {{ selectedControl.severity }}
            </mat-chip>
          </mat-chip-set>
        </div>

        <mat-divider class="section-divider"></mat-divider>

        <div class="detail-section" *ngIf="selectedControl.expression">
          <div class="section-label">Expression</div>
          <pre class="expression-block">{{ selectedControl.expression }}</pre>
        </div>

        <div class="detail-section" *ngIf="selectedControl.regulatoryReference">
          <div class="section-label">Regulatory Reference</div>
          <span class="ref-value">{{ selectedControl.regulatoryReference }}</span>
        </div>

        <div class="detail-section" *ngIf="selectedControl.inputKeys?.length">
          <div class="section-label">Input Keys</div>
          <mat-chip-set>
            <mat-chip *ngFor="let key of selectedControl.inputKeys" class="input-key-chip">{{ key }}</mat-chip>
          </mat-chip-set>
        </div>

        <mat-divider class="section-divider"></mat-divider>

        <!-- Evaluate Control -->
        <div class="evaluate-section">
          <div class="section-label">Evaluate Against Run Data</div>
          <div class="eval-form">
            <mat-form-field appearance="outline">
              <mat-label>Workflow Run ID</mat-label>
              <input matInput [(ngModel)]="evalRunId" placeholder="run-id">
            </mat-form-field>
            <mat-form-field appearance="outline" class="full-width">
              <mat-label>Data JSON</mat-label>
              <textarea matInput [(ngModel)]="evalData" rows="4" class="expr-editor"
                        placeholder='{"amount": 5000, "approved": true}'></textarea>
            </mat-form-field>
            <button mat-raised-button color="accent" (click)="evaluateControl()"
                    [disabled]="evaluating || !evalRunId.trim() || !evalData.trim()">
              <mat-icon>play_arrow</mat-icon> {{ evaluating ? 'Evaluating...' : 'Evaluate' }}
            </button>
          </div>

          <div *ngIf="evalResult" class="eval-result" [class.eval-pass]="evalResult.passed" [class.eval-fail]="!evalResult.passed">
            <mat-icon>{{ evalResult.passed ? 'check_circle' : 'error' }}</mat-icon>
            <div class="eval-result-info">
              <span class="eval-verdict">{{ evalResult.passed ? 'PASSED' : 'FAILED' }}</span>
              <span class="eval-time" *ngIf="evalResult.evaluatedAt">{{ evalResult.evaluatedAt }}</span>
            </div>
          </div>
        </div>

        <mat-divider class="section-divider"></mat-divider>

        <!-- Failure Analysis -->
        <div class="evaluate-section">
          <div class="section-label">Failure Analysis (Attribution)</div>
          <p class="hint">Investigate why this control failed on a specific run/step using causal attribution.</p>
          <div class="eval-form">
            <mat-form-field appearance="outline">
              <mat-label>Workflow Run ID</mat-label>
              <input matInput [(ngModel)]="failureRunId" placeholder="run-id">
            </mat-form-field>
            <mat-form-field appearance="outline">
              <mat-label>Step ID</mat-label>
              <input matInput [(ngModel)]="failureStepId" placeholder="step-id">
            </mat-form-field>
            <button mat-raised-button color="accent" (click)="analyzeFailure()"
                    [disabled]="analyzingFailure || !failureRunId.trim() || !failureStepId.trim()">
              <mat-icon>psychology</mat-icon> {{ analyzingFailure ? 'Analyzing...' : 'Analyze Failure' }}
            </button>
          </div>

          <div *ngIf="failureAlert" class="failure-analysis-result">
            <div class="failure-header">
              <mat-icon [class]="'severity-' + (failureAlert.severity || 'medium').toLowerCase()">
                {{ failureAlert.severity === 'CRITICAL' ? 'error' :
                   failureAlert.severity === 'HIGH' ? 'warning' : 'info' }}
              </mat-icon>
              <span class="failure-title">{{ failureAlert.title }}</span>
              <mat-chip [class]="'severity-chip severity-chip-' + (failureAlert.severity || 'medium').toLowerCase()">
                {{ failureAlert.severity }}
              </mat-chip>
            </div>
            <p class="failure-explanation" *ngIf="failureAlert.explanation">{{ failureAlert.explanation }}</p>
            <div class="failure-meta">
              <span>{{ (failureAlert.confidence * 100).toFixed(0) }}% confidence</span>
              <span *ngIf="failureAlert.causalChains?.length">{{ failureAlert.causalChains.length }} causal chains</span>
              <span *ngIf="failureAlert.predictions?.length">{{ failureAlert.predictions.length }} predictions</span>
              <span class="alert-type-badge" *ngIf="failureAlert.alertType">{{failureAlert.alertType}}</span>
              <span class="alert-id-badge" *ngIf="failureAlert.alertId" [matTooltip]="failureAlert.alertId">ID: {{failureAlert.alertId | slice:0:8}}</span>
              <span class="alert-timestamp" *ngIf="failureAlert.createdAt">{{failureAlert.createdAt | slice:0:19}}</span>
              <mat-icon *ngIf="failureAlert.acknowledged" class="alert-ack-icon" matTooltip="Acknowledged">check_circle</mat-icon>
              <mat-icon *ngIf="failureAlert.llmUsed" class="alert-llm-icon" matTooltip="LLM-assisted">smart_toy</mat-icon>
            </div>

            <div *ngIf="failureAlert.causalChains?.length" class="failure-chains">
              <span class="chains-label">Causal Chains</span>
              <div *ngFor="let chain of failureAlert.causalChains; let i = index" class="chain-card">
                <span class="chain-index">{{ i + 1 }}</span>
                <span class="chain-detail">
                  {{ chain.rootCauseTitle }} → {{ chain.hops?.length || 0 }} hops → {{ chain.targetEventTitle }}
                </span>
                <span class="chain-conf" [style.color]="chain.overallConfidence >= 0.7 ? '#ef5350' : chain.overallConfidence >= 0.4 ? '#ffb74d' : '#66bb6a'">
                  {{ (chain.overallConfidence * 100).toFixed(0) }}%
                </span>
              </div>
            </div>

            <!-- Predictions detail -->
            <div *ngIf="failureAlert.predictions?.length" class="failure-predictions">
              <span class="chains-label">Predictions</span>
              <div *ngFor="let pred of failureAlert.predictions" class="pred-card">
                <span class="pred-title-sm" [matTooltip]="pred.nodeId">{{ pred.title || pred.nodeId | slice:0:24 }}</span>
                <span class="pred-prob-sm"
                      [style.color]="pred.probability >= 0.7 ? '#ef5350' : pred.probability >= 0.4 ? '#ffb74d' : '#66bb6a'">
                  {{ (pred.probability * 100).toFixed(0) }}%
                </span>
                <span class="pred-hops-sm">{{ pred.hopsFromSource }} hops</span>
                <span *ngIf="pred.explanation" class="pred-expl-sm" [matTooltip]="pred.explanation">
                  {{ pred.explanation | slice:0:50 }}
                </span>
              </div>
            </div>

            <!-- Bayesian priors / posteriors -->
            <div *ngIf="failureAlert.bayesianPosteriors && hasKeys(failureAlert.bayesianPosteriors)" class="failure-bayesian">
              <span class="chains-label">Bayesian Inference</span>
              <div *ngFor="let nodeId of getObjectKeys(failureAlert.bayesianPosteriors!)" class="bayesian-row">
                <span class="bayesian-node-id" [matTooltip]="nodeId">{{ nodeId | slice:0:20 }}</span>
                <span class="bayesian-vals">
                  <span class="bayesian-prior" *ngIf="failureAlert.bayesianPriors?.[nodeId] != null">
                    {{ ((failureAlert.bayesianPriors?.[nodeId] ?? 0) * 100).toFixed(1) }}%
                  </span>
                  <mat-icon class="bayesian-arrow" *ngIf="failureAlert.bayesianPriors?.[nodeId] != null">arrow_forward</mat-icon>
                  <span class="bayesian-posterior"
                        [style.color]="failureAlert.bayesianPosteriors![nodeId] >= 0.7 ? '#ef5350' : failureAlert.bayesianPosteriors![nodeId] >= 0.4 ? '#ffb74d' : '#66bb6a'">
                    {{ (failureAlert.bayesianPosteriors![nodeId] * 100).toFixed(1) }}%
                  </span>
                </span>
                <ng-container *ngIf="failureAlert.mebnMeta?.[nodeId] as meta">
                  <div class="alert-mebn-badges">
                    <span class="alert-mebn-badge alert-mfrag">{{meta.mfragName}}</span>
                    <span class="alert-mebn-badge alert-role"
                          [class.alert-role-resident]="meta.nodeRole === 'RESIDENT'"
                          [class.alert-role-input]="meta.nodeRole === 'INPUT'">{{meta.nodeRole}}</span>
                    <span *ngIf="meta.entityType" class="alert-mebn-badge alert-etype">{{meta.entityType}}</span>
                  </div>
                </ng-container>
              </div>
            </div>

            <button mat-button (click)="failureAlert = null" class="clear-btn">
              <mat-icon>close</mat-icon> Clear
            </button>
          </div>
        </div>
      </div>
    </div>
  `,
  styles: [`
    .controls-container { padding: 12px 0; }
    .section-toolbar { display: flex; align-items: center; gap: 8px; margin-bottom: 16px; }
    .empty-state { text-align: center; padding: 60px 20px; color: #999; }
    .empty-icon { font-size: 56px; width: 56px; height: 56px; color: #555; display: block; margin: 0 auto 12px; }

    .controls-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(300px, 1fr)); gap: 12px; }
    .control-card {
      background: #1e1e2e; border: 1px solid rgba(255,255,255,0.08); border-radius: 8px;
      cursor: pointer; transition: border-color 0.2s;
    }
    .control-card:hover { border-color: rgba(239,83,80,0.4); }
    .card-header { display: flex; align-items: center; gap: 10px; padding: 12px 14px; }
    .ctrl-icon { font-size: 24px; width: 24px; height: 24px; color: #81c784; flex-shrink: 0; }
    .ctrl-icon.hard-gate { color: #ef5350; }
    .card-title-block { flex: 1; min-width: 0; }
    .card-name { display: block; font-weight: 500; font-size: 14px; }
    .card-desc { display: block; font-size: 11px; color: #888; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
    .card-stats { display: flex; gap: 16px; padding: 10px 14px; flex-wrap: wrap; }
    .stat { display: flex; align-items: center; gap: 6px; font-size: 12px; color: #aaa; }
    .stat mat-icon { font-size: 16px; width: 16px; height: 16px; }
    .expr-preview { font-family: monospace; font-size: 11px; }

    .gate-chip { font-size: 11px !important; min-height: 22px !important; font-weight: 600 !important; }
    .gate-hard { background: rgba(239,83,80,0.2) !important; color: #ef5350 !important; }
    .gate-soft { background: rgba(255,183,77,0.2) !important; color: #ffb74d !important; }

    .severity-low { color: #81c784; }
    .severity-medium { color: #ffb74d; }
    .severity-high { color: #ff8a65; }
    .severity-critical { color: #ef5350; }

    .severity-chip { font-size: 10px !important; min-height: 20px !important; }
    .severity-chip-low { background: rgba(129,199,132,0.15) !important; color: #81c784 !important; }
    .severity-chip-medium { background: rgba(255,183,77,0.15) !important; color: #ffb74d !important; }
    .severity-chip-high { background: rgba(255,138,101,0.15) !important; color: #ff8a65 !important; }
    .severity-chip-critical { background: rgba(239,83,80,0.2) !important; color: #ef5350 !important; }

    .create-view { max-width: 700px; }
    .create-view h3 { margin: 0 0 4px; }
    .hint { font-size: 12px; color: #aaa; margin: 0 0 16px; }
    .full-width { width: 100%; }
    .form-row { display: flex; gap: 16px; }
    .form-row mat-form-field { flex: 1; }
    .expr-editor { font-family: 'JetBrains Mono', 'Fira Code', monospace; font-size: 12px; }
    .editor-actions { display: flex; gap: 8px; justify-content: flex-end; margin-top: 8px; }

    .detail-header { display: flex; align-items: center; gap: 12px; margin-bottom: 16px; flex-wrap: wrap; }
    .detail-icon { font-size: 36px; width: 36px; height: 36px; color: #81c784; flex-shrink: 0; }
    .detail-icon.hard-gate { color: #ef5350; }
    .detail-title-block { flex: 1; }
    .detail-title-block h3 { margin: 0; font-size: 18px; }
    .detail-desc { font-size: 12px; color: #888; }
    .section-divider { margin: 16px 0; }

    .detail-section { margin-bottom: 16px; }
    .section-label { font-size: 12px; font-weight: 600; color: #90caf9; text-transform: uppercase; letter-spacing: 0.5px; margin-bottom: 6px; }
    .expression-block {
      background: #111; padding: 10px 12px; border-radius: 6px;
      font-size: 12px; font-family: 'JetBrains Mono', monospace; white-space: pre-wrap; margin: 0;
    }
    .ref-value { font-size: 13px; color: #ce93d8; }
    .input-key-chip { background: rgba(144,202,249,0.15) !important; color: #90caf9 !important; font-size: 11px !important; min-height: 22px !important; font-family: monospace; }

    .evaluate-section { margin-top: 8px; }
    .eval-form { display: flex; flex-direction: column; gap: 8px; max-width: 600px; }
    .eval-form button { align-self: flex-start; }

    .eval-result {
      display: flex; align-items: center; gap: 10px; padding: 12px;
      border-radius: 8px; margin-top: 12px; max-width: 600px;
    }
    .eval-pass { background: rgba(129,199,132,0.1); border: 1px solid rgba(129,199,132,0.3); }
    .eval-pass mat-icon { color: #81c784; font-size: 28px; width: 28px; height: 28px; }
    .eval-fail { background: rgba(239,83,80,0.1); border: 1px solid rgba(239,83,80,0.3); }
    .eval-fail mat-icon { color: #ef5350; font-size: 28px; width: 28px; height: 28px; }
    .eval-result-info { display: flex; flex-direction: column; }
    .eval-verdict { font-weight: 600; font-size: 14px; }
    .eval-time { font-size: 11px; color: #888; }

    /* SpEL Test Panel */
    .test-panel {
      background: rgba(144,202,249,0.05); border: 1px solid rgba(144,202,249,0.15);
      border-radius: 8px; padding: 12px; margin-bottom: 16px;
    }
    .test-panel-header { display: flex; align-items: center; gap: 6px; font-size: 13px; font-weight: 500; color: #90caf9; margin-bottom: 8px; }
    .test-panel-header mat-icon { font-size: 18px; width: 18px; height: 18px; }
    .test-result {
      display: flex; align-items: center; gap: 8px; padding: 8px 12px;
      border-radius: 6px; margin-top: 8px;
    }
    .test-pass { background: rgba(129,199,132,0.1); border: 1px solid rgba(129,199,132,0.3); }
    .test-pass mat-icon { color: #81c784; }
    .test-fail { background: rgba(239,83,80,0.1); border: 1px solid rgba(239,83,80,0.3); }
    .test-fail mat-icon { color: #ef5350; }
    .test-result-info { font-size: 12px; }
    .test-verdict { font-weight: 500; }

    /* Failure Analysis */
    .hint { font-size: 12px; color: #aaa; margin: 0 0 8px; }
    .failure-analysis-result {
      background: rgba(239,83,80,0.05); border: 1px solid rgba(239,83,80,0.15);
      border-radius: 8px; padding: 12px; margin-top: 12px; max-width: 700px;
    }
    .failure-header { display: flex; align-items: center; gap: 8px; margin-bottom: 8px; }
    .failure-header mat-icon { font-size: 20px; width: 20px; height: 20px; }
    .failure-title { flex: 1; font-size: 14px; font-weight: 500; }
    .failure-explanation { font-size: 12px; color: #bbb; margin: 0 0 8px; border-left: 3px solid #667eea; padding-left: 10px; }
    .failure-meta { display: flex; gap: 12px; font-size: 11px; color: #888; margin-bottom: 8px; flex-wrap: wrap; align-items: center; }
    .alert-type-badge { padding: 1px 5px; border-radius: 3px; background: rgba(144,202,249,0.12); color: #90caf9; font-size: 9px; font-weight: 500; }
    .alert-id-badge { font-family: monospace; font-size: 9px; color: #777; cursor: default; }
    .alert-timestamp { font-family: monospace; font-size: 9px; color: #777; }
    .alert-ack-icon { font-size: 14px !important; width: 14px !important; height: 14px !important; color: #66bb6a; }
    .alert-llm-icon { font-size: 14px !important; width: 14px !important; height: 14px !important; color: #ce93d8; }
    .alert-mebn-badges { display: flex; gap: 4px; margin-top: 2px; }
    .alert-mebn-badge { font-size: 9px; padding: 1px 5px; border-radius: 3px; font-weight: 600; }
    .alert-mfrag { background: rgba(102,126,234,0.15); color: #90caf9; }
    .alert-role { background: rgba(255,255,255,0.06); color: #aaa; }
    .alert-role-resident { color: #81c784; }
    .alert-role-input { color: #ffb74d; }
    .alert-etype { background: rgba(206,147,216,0.15); color: #ce93d8; }
    .failure-chains { margin-top: 8px; }
    .chains-label { font-size: 11px; font-weight: 600; color: #999; text-transform: uppercase; letter-spacing: 0.5px; display: block; margin-bottom: 6px; }
    .chain-card { display: flex; align-items: center; gap: 8px; padding: 6px 8px; background: rgba(255,255,255,0.02); border-radius: 4px; margin-bottom: 4px; font-size: 12px; }
    .chain-index { font-size: 10px; font-weight: 600; color: #667eea; width: 16px; text-align: center; }
    .chain-detail { flex: 1; color: #bbb; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
    .chain-conf { font-weight: 600; font-family: monospace; font-size: 11px; }
    .failure-predictions { margin-top: 8px; }
    .pred-card { display: flex; align-items: center; gap: 8px; padding: 6px 8px; background: rgba(255,255,255,0.02); border-radius: 4px; margin-bottom: 4px; font-size: 12px; }
    .pred-title-sm { color: #bbb; max-width: 140px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
    .pred-prob-sm { font-weight: 600; font-family: monospace; font-size: 11px; min-width: 32px; text-align: right; }
    .pred-hops-sm { color: #888; font-size: 10px; }
    .pred-expl-sm { color: #aaa; font-style: italic; font-size: 10px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; flex: 1; }
    .failure-bayesian { margin-top: 8px; }
    .bayesian-row { display: flex; justify-content: space-between; align-items: center; padding: 4px 8px; font-size: 12px; background: rgba(255,255,255,0.02); border-radius: 4px; margin-bottom: 3px; }
    .bayesian-node-id { color: #aaa; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; max-width: 120px; font-size: 11px; }
    .bayesian-vals { display: flex; align-items: center; gap: 4px; }
    .bayesian-prior { color: #888; font-size: 11px; }
    .bayesian-arrow { font-size: 12px; width: 12px; height: 12px; color: #666; }
    .bayesian-posterior { font-weight: 600; font-family: monospace; }
    .clear-btn { margin-top: 8px; }
  `]
})
export class ProcessControlsComponent implements OnInit {
  viewMode: ViewMode = 'list';
  controls: ControlDefinition[] = [];
  selectedControl: ControlDefinition | null = null;
  saving = false;

  newControl: ControlDefinition = {
    name: '',
    gateType: 'SOFT',
    severity: 'MEDIUM'
  };

  // Evaluate state
  evalRunId = '';
  evalData = '';
  evaluating = false;
  evalResult: ControlAttestation | null = null;

  // SpEL test state (in create view)
  testContextJson = '{ "runData": {} }';
  testingExpr = false;
  testResult: SpelResult | null = null;

  // Failure analysis state
  failureRunId = '';
  failureStepId = '';
  analyzingFailure = false;
  failureAlert: ProcessEventAlert | null = null;

  constructor(
    private processEngineService: ProcessEngineService,
    private processAttributionService: ProcessAttributionService,
    private snackBar: MatSnackBar
  ) {}

  ngOnInit(): void {
    this.loadControls();
  }

  loadControls(): void {
    this.processEngineService.listControls().subscribe({
      next: (result) => { this.controls = result; },
      error: () => { this.controls = []; }
    });
  }

  startCreate(): void {
    this.viewMode = 'create';
    this.newControl = { name: '', gateType: 'SOFT', severity: 'MEDIUM' };
    this.testContextJson = '{ "runData": {} }';
    this.testResult = null;
  }

  backToList(): void {
    this.viewMode = 'list';
    this.evalResult = null;
    this.failureAlert = null;
  }

  createControl(): void {
    this.saving = true;
    this.processEngineService.createControl(this.newControl).subscribe({
      next: (result) => {
        this.snackBar.open('Control created: ' + result.name, 'Close', { duration: 3000 });
        this.controls = [...this.controls, result];
        this.saving = false;
        this.viewMode = 'list';
      },
      error: (err) => {
        this.snackBar.open('Failed to create: ' + (err.error?.message || err.message), 'Close', { duration: 4000 });
        this.saving = false;
      }
    });
  }

  viewDetail(ctrl: ControlDefinition): void {
    if (ctrl.id) {
      this.processEngineService.getControl(ctrl.id).subscribe({
        next: (result) => {
          this.selectedControl = result;
          this.viewMode = 'detail';
          this.evalResult = null;
        },
        error: () => {
          this.selectedControl = ctrl;
          this.viewMode = 'detail';
          this.evalResult = null;
        }
      });
    } else {
      this.selectedControl = ctrl;
      this.viewMode = 'detail';
      this.evalResult = null;
    }
  }

  evaluateControl(): void {
    if (!this.selectedControl?.id) return;
    let data: Record<string, any>;
    try {
      data = JSON.parse(this.evalData);
    } catch {
      this.snackBar.open('Invalid JSON', 'Close', { duration: 3000 });
      return;
    }
    this.evaluating = true;
    this.processEngineService.evaluateControl(this.selectedControl.id, this.evalRunId, data).subscribe({
      next: (result) => {
        this.evalResult = result;
        this.evaluating = false;
      },
      error: (err) => {
        this.snackBar.open('Evaluation failed: ' + (err.error?.message || err.message), 'Close', { duration: 4000 });
        this.evaluating = false;
      }
    });
  }

  testExpression(): void {
    if (!this.newControl.expression?.trim()) return;
    let context: Record<string, any>;
    try {
      context = JSON.parse(this.testContextJson);
    } catch {
      this.snackBar.open('Invalid test context JSON', 'Close', { duration: 3000 });
      return;
    }
    this.testingExpr = true;
    this.testResult = null;
    this.processEngineService.evaluateSpelExpression(this.newControl.expression, context).subscribe({
      next: (result) => {
        this.testResult = result;
        this.testingExpr = false;
      },
      error: (err) => {
        this.testResult = { result: null, type: 'error', error: err.error?.message || err.message };
        this.testingExpr = false;
      }
    });
  }

  analyzeFailure(): void {
    if (!this.selectedControl?.id) return;
    this.analyzingFailure = true;
    this.failureAlert = null;
    this.processAttributionService.explainControlFailure(
      this.failureRunId, this.failureStepId, this.selectedControl.id
    ).subscribe({
      next: (result) => {
        this.failureAlert = result;
        this.analyzingFailure = false;
      },
      error: (err) => {
        this.snackBar.open('Failure analysis failed: ' + (err.error?.message || err.message), 'Close', { duration: 4000 });
        this.analyzingFailure = false;
      }
    });
  }

  getSeverityIcon(severity: string): string {
    switch (severity) {
      case 'LOW': return 'info';
      case 'MEDIUM': return 'warning';
      case 'HIGH': return 'report_problem';
      case 'CRITICAL': return 'error';
      default: return 'info';
    }
  }

  hasKeys(obj: any): boolean {
    return obj && Object.keys(obj).length > 0;
  }

  getObjectKeys(obj: Record<string, any>): string[] {
    return obj ? Object.keys(obj) : [];
  }
}
