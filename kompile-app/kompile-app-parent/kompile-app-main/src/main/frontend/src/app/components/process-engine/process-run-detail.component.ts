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
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatSelectModule } from '@angular/material/select';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';
import { Subject, interval, takeUntil } from 'rxjs';
import { ProcessEngineService, WorkflowRun, StepExecution, ApprovalResponse, ControlAttestation } from '../../services/process-engine.service';
import { ProcessAttributionService, ProcessRiskAssessment, StepAttributionResult, StepAttributionSummary, ProcessEventAlert } from '../../services/process-attribution.service';
import { GraphService } from '../../services/graph.service';
import { GraphNodePopoverComponent } from './graph-node-popover.component';

@Component({
  standalone: true,
  selector: 'app-process-run-detail',
  imports: [
    CommonModule, FormsModule,
    MatIconModule, MatButtonModule, MatChipsModule, MatTooltipModule,
    MatExpansionModule, MatProgressBarModule, MatProgressSpinnerModule, MatSelectModule,
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
                 (click)="selectStep(i)">
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
                [posteriorValue]="getStepPopoverPosterior(gid)"
                [priorValue]="getStepPopoverPrior(gid)"
                [mebnMfragName]="getStepMebnMeta(gid, 'mfragName')"
                [mebnNodeRole]="getStepMebnMeta(gid, 'nodeRole')"
                [mebnEntityType]="getStepMebnMeta(gid, 'entityType')"
                [mebnEntityId]="getStepMebnMeta(gid, 'entityId')"
                (closed)="activePopoverNodeId = null"
                (openInGraph)="onOpenInGraph($event)">
              </app-graph-node-popover>
            </span>
          </div>
        </div>

        <!-- Control Attestations for this step -->
        <div class="attestations-section" *ngIf="stepAttestations.length > 0">
          <span class="meta-label">Control Attestations</span>
          <div *ngFor="let att of stepAttestations" class="attestation-block">
            <div class="attestation-row">
              <mat-icon [class]="att.passed ? 'att-pass' : 'att-fail'">
                {{ att.passed ? 'check_circle' : 'cancel' }}
              </mat-icon>
              <span class="att-id">{{ att.controlId | slice:0:12 }}</span>
              <code class="att-expr">{{ att.expressionEvaluated }}</code>
              <button mat-icon-button *ngIf="!att.passed && !controlExplanations[att.controlId]"
                      (click)="explainControlFailure(att.controlId)"
                      [disabled]="controlExplainLoading[att.controlId]"
                      matTooltip="Explain why this control failed">
                <mat-icon>psychology</mat-icon>
              </button>
            </div>
            <div *ngIf="controlExplainLoading[att.controlId]" class="control-explain-loading">
              Analyzing failure...
            </div>
            <div *ngIf="controlExplanations[att.controlId]" class="control-explanation">
              <div class="control-explain-header">
                <mat-icon>lightbulb</mat-icon>
                <span class="control-explain-title">{{ controlExplanations[att.controlId].title }}</span>
                <span class="control-explain-conf">
                  {{ (controlExplanations[att.controlId].confidence * 100).toFixed(0) }}% confidence
                </span>
                <button mat-icon-button (click)="clearControlExplanation(att.controlId)" matTooltip="Dismiss">
                  <mat-icon>close</mat-icon>
                </button>
              </div>
              <p class="control-explain-text" *ngIf="controlExplanations[att.controlId].explanation">
                {{ controlExplanations[att.controlId].explanation }}
              </p>
              <div class="control-explain-meta">
                <span *ngIf="controlExplanations[att.controlId].causalChains?.length">
                  {{ controlExplanations[att.controlId].causalChains.length }} causal chains
                </span>
                <span *ngIf="controlExplanations[att.controlId].predictions?.length">
                  {{ controlExplanations[att.controlId].predictions.length }} predictions
                </span>
                <span class="run-alert-type" *ngIf="controlExplanations[att.controlId].alertType">{{controlExplanations[att.controlId].alertType}}</span>
                <span class="run-alert-id" *ngIf="controlExplanations[att.controlId].alertId" [matTooltip]="controlExplanations[att.controlId].alertId">ID: {{controlExplanations[att.controlId].alertId | slice:0:8}}</span>
                <span class="run-alert-ts" *ngIf="controlExplanations[att.controlId].createdAt">{{controlExplanations[att.controlId].createdAt | slice:0:19}}</span>
                <mat-icon *ngIf="controlExplanations[att.controlId].acknowledged" class="run-ack-icon" matTooltip="Acknowledged">check_circle</mat-icon>
              </div>
              <!-- Control explanation causal chains detail -->
              <div *ngIf="controlExplanations[att.controlId].causalChains?.length" class="alert-chains-detail">
                <div *ngFor="let chain of controlExplanations[att.controlId].causalChains" class="alert-chain-row">
                  <span class="chain-root" [matTooltip]="chain.rootCauseNodeId">{{chain.rootCauseTitle || chain.rootCauseNodeId | slice:0:20}}</span>
                  <mat-icon class="chain-arrow-sm">arrow_forward</mat-icon>
                  <span class="chain-target" [matTooltip]="chain.targetEventNodeId">{{chain.targetEventTitle || chain.targetEventNodeId | slice:0:20}}</span>
                  <span class="chain-conf" [style.color]="getRiskColor(chain.overallConfidence)">
                    {{(chain.overallConfidence * 100).toFixed(0)}}%
                  </span>
                </div>
              </div>
              <!-- Control explanation predictions detail -->
              <div *ngIf="controlExplanations[att.controlId].predictions?.length" class="alert-predictions-detail">
                <div *ngFor="let pred of controlExplanations[att.controlId].predictions" class="alert-pred-row">
                  <span class="pred-title" [matTooltip]="pred.nodeId">{{pred.title || pred.nodeId | slice:0:24}}</span>
                  <span class="pred-prob" [style.color]="getRiskColor(pred.probability)">
                    {{(pred.probability * 100).toFixed(0)}}%
                  </span>
                  <span class="pred-hops">{{pred.hopsFromSource}} hops</span>
                </div>
              </div>
              <!-- Step-level control explanation Bayesian data -->
              <ng-container *ngIf="controlExplanations[att.controlId] as stepCtrl">
                <div *ngIf="stepCtrl.bayesianPosteriors && hasKeys(stepCtrl.bayesianPosteriors)"
                     class="alert-bayesian">
                  <div *ngFor="let nodeId of getObjectKeys(stepCtrl.bayesianPosteriors!)"
                       class="bayesian-entry">
                    <span class="bayesian-node-id" [matTooltip]="nodeId">{{nodeId | slice:0:20}}</span>
                    <span class="bayesian-vals">
                      <span class="bayesian-prior"
                            *ngIf="stepCtrl.bayesianPriors?.[nodeId] != null">
                        {{((stepCtrl.bayesianPriors?.[nodeId] ?? 0) * 100).toFixed(1)}}%
                      </span>
                      <mat-icon class="bayesian-arrow"
                                *ngIf="stepCtrl.bayesianPriors?.[nodeId] != null">arrow_forward</mat-icon>
                      <span class="bayesian-posterior"
                            [style.color]="getRiskColor(stepCtrl.bayesianPosteriors![nodeId])">
                        {{(stepCtrl.bayesianPosteriors![nodeId] * 100).toFixed(1)}}%
                      </span>
                    </span>
                  </div>
                </div>
              </ng-container>
            </div>
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

      <!-- Section: Risk Assessment & Attribution -->
      <div class="section-card">
        <div class="risk-header">
          <h4 class="section-title">
            <mat-icon>security</mat-icon>
            Risk Assessment
          </h4>
          <button mat-stroked-button (click)="loadRiskAssessment()" [disabled]="riskLoading"
                  *ngIf="!riskAssessment">
            <mat-icon>assessment</mat-icon> Assess Risk
          </button>
        </div>

        <div *ngIf="riskLoading" class="risk-loading">
          <mat-spinner diameter="24"></mat-spinner>
          <span>Analyzing risk...</span>
        </div>

        <div *ngIf="riskAssessment" class="risk-results">
          <div class="risk-overview">
            <div class="risk-score-card" [class]="'risk-' + riskAssessment.riskLevel?.toLowerCase()">
              <span class="risk-score-value">{{(riskAssessment.overallRiskScore * 100).toFixed(0)}}%</span>
              <span class="risk-score-label">{{riskAssessment.riskLevel}} risk</span>
            </div>
            <div class="risk-stats">
              <span>{{riskAssessment.alerts?.length || 0}} alerts</span>
              <span>{{riskAssessment.highRiskStepIds?.length || 0}} high-risk steps</span>
              <span>{{riskAssessment.computationTimeMs}}ms</span>
              <span class="run-alert-ts" *ngIf="riskAssessment.computedAt">{{riskAssessment.computedAt | slice:0:19}}</span>
            </div>
          </div>

          <div *ngIf="riskAssessment.summary" class="risk-summary">
            {{riskAssessment.summary}}
          </div>

          <!-- Per-step risk bars -->
          <div *ngIf="riskAssessment.stepRiskScores && hasKeys(riskAssessment.stepRiskScores)" class="step-risk-list">
            <h5>Step Risk Scores</h5>
            <div *ngFor="let entry of getStepRiskEntries()" class="step-risk-entry">
              <span class="step-risk-name">{{entry.stepId}}</span>
              <div class="step-risk-bar">
                <div class="step-risk-fill"
                     [style.width.%]="entry.score * 100"
                     [style.background-color]="getRiskColor(entry.score)"></div>
              </div>
              <span class="step-risk-value" [style.color]="getRiskColor(entry.score)">
                {{(entry.score * 100).toFixed(0)}}%
              </span>
              <!-- Bayesian priors/posteriors for this step from stepAttributionResults -->
              <div *ngIf="getStepAttrSummary(entry.stepId) as stepAttr"
                   class="step-bayesian-inline">
                <div class="step-summary-meta" *ngIf="stepAttr.confidenceBand || stepAttr.narrative">
                  <span *ngIf="stepAttr.confidenceBand"
                        class="confidence-band-chip"
                        [class]="'band-' + stepAttr.confidenceBand.toLowerCase()">
                    {{stepAttr.confidenceBand}}
                  </span>
                  <span *ngIf="stepAttr.causalChains?.length" class="chain-count-chip">
                    {{stepAttr.causalChains!.length}} chains
                  </span>
                </div>
                <div *ngIf="stepAttr.narrative" class="step-narrative">
                  {{stepAttr.narrative}}
                </div>
                <!-- Step attribution causal chains detail -->
                <div *ngIf="stepAttr.causalChains?.length" class="alert-chains-detail">
                  <div *ngFor="let chain of stepAttr.causalChains" class="alert-chain-row">
                    <span class="chain-root" [matTooltip]="chain.rootCauseNodeId">{{chain.rootCauseTitle || chain.rootCauseNodeId | slice:0:18}}</span>
                    <mat-icon class="chain-arrow-sm">arrow_forward</mat-icon>
                    <span class="chain-target" [matTooltip]="chain.targetEventNodeId">{{chain.targetEventTitle || chain.targetEventNodeId | slice:0:18}}</span>
                    <span class="chain-conf" [style.color]="getRiskColor(chain.overallConfidence)">
                      {{(chain.overallConfidence * 100).toFixed(0)}}%
                    </span>
                  </div>
                </div>
                <div *ngFor="let nodeId of getObjectKeys(stepAttr.bayesianPosteriors)"
                     class="bayesian-entry">
                  <span class="bayesian-node-id" [matTooltip]="nodeId">{{nodeId | slice:0:20}}</span>
                  <ng-container *ngIf="stepAttr.mebnMeta?.[nodeId] as meta">
                    <span class="mebn-badge-sm mfrag-sm">{{meta.mfragName}}</span>
                    <span class="mebn-badge-sm role-sm"
                          [class.role-sm-resident]="meta.nodeRole === 'RESIDENT'"
                          [class.role-sm-input]="meta.nodeRole === 'INPUT'">{{meta.nodeRole}}</span>
                    <span *ngIf="meta.entityType" class="mebn-badge-sm etype-sm">{{meta.entityType}}</span>
                  </ng-container>
                  <span class="bayesian-vals">
                    <span class="bayesian-prior"
                          *ngIf="stepAttr.bayesianPriors[nodeId] !== undefined">
                      {{(stepAttr.bayesianPriors[nodeId] * 100).toFixed(1)}}%
                    </span>
                    <mat-icon class="bayesian-arrow"
                              *ngIf="stepAttr.bayesianPriors[nodeId] !== undefined">
                      arrow_forward</mat-icon>
                    <span class="bayesian-posterior"
                          [style.color]="getRiskColor(stepAttr.bayesianPosteriors[nodeId])">
                      {{(stepAttr.bayesianPosteriors[nodeId] * 100).toFixed(1)}}%
                    </span>
                  </span>
                </div>
                <!-- Influence scores for this step -->
                <div *ngIf="stepAttr.influenceScores && hasKeys(stepAttr.influenceScores)" class="step-influence-inline">
                  <span class="influence-label-sm">Influence:</span>
                  <div *ngFor="let nodeId of getSortedInfluenceKeys(stepAttr.influenceScores)" class="influence-entry-sm">
                    <span class="influence-name-sm" [matTooltip]="nodeId">{{nodeId | slice:0:16}}</span>
                    <div class="influence-bar-sm">
                      <div class="influence-fill-sm"
                           [style.width.%]="(stepAttr.influenceScores![nodeId] || 0) * 100"
                           [style.background-color]="getRiskColor(stepAttr.influenceScores![nodeId] || 0)"></div>
                    </div>
                    <span class="influence-val-sm">{{((stepAttr.influenceScores![nodeId] || 0) * 100).toFixed(0)}}%</span>
                  </div>
                </div>
              </div>
            </div>
          </div>

          <!-- Alerts -->
          <div *ngIf="riskAssessment.alerts?.length" class="alerts-list">
            <h5>Alerts</h5>
            <div *ngFor="let alert of riskAssessment.alerts" class="alert-card"
                 [class]="'alert-' + alert.severity?.toLowerCase()">
              <div class="alert-header">
                <mat-icon>{{alert.severity === 'CRITICAL' ? 'error' :
                  alert.severity === 'HIGH' ? 'warning' : 'info'}}</mat-icon>
                <span class="alert-title">{{alert.title}}</span>
                <mat-chip class="severity-chip">{{alert.severity}}</mat-chip>
              </div>
              <p class="alert-explanation" *ngIf="alert.explanation">{{alert.explanation}}</p>
              <div class="alert-meta">
                <span *ngIf="alert.causalChains?.length">{{alert.causalChains.length}} causal chains</span>
                <span *ngIf="alert.predictions?.length">{{alert.predictions.length}} predictions</span>
                <span>{{(alert.confidence * 100).toFixed(0)}}% confidence</span>
                <span class="run-alert-type" *ngIf="alert.alertType">{{alert.alertType}}</span>
                <span class="run-alert-id" *ngIf="alert.alertId" [matTooltip]="alert.alertId">ID: {{alert.alertId | slice:0:8}}</span>
                <span class="run-alert-ts" *ngIf="alert.createdAt">{{alert.createdAt | slice:0:19}}</span>
                <mat-icon *ngIf="alert.acknowledged" class="run-ack-icon" matTooltip="Acknowledged">check_circle</mat-icon>
                <mat-icon *ngIf="alert.llmUsed" class="run-llm-icon" matTooltip="LLM-assisted">smart_toy</mat-icon>
              </div>
              <!-- Causal chains detail -->
              <div *ngIf="alert.causalChains?.length" class="alert-chains-detail">
                <div *ngFor="let chain of alert.causalChains" class="alert-chain-row">
                  <span class="chain-root" [matTooltip]="chain.rootCauseNodeId">{{chain.rootCauseTitle || chain.rootCauseNodeId | slice:0:20}}</span>
                  <mat-icon class="chain-arrow-sm">arrow_forward</mat-icon>
                  <span class="chain-target" [matTooltip]="chain.targetEventNodeId">{{chain.targetEventTitle || chain.targetEventNodeId | slice:0:20}}</span>
                  <span class="chain-conf" [style.color]="getRiskColor(chain.overallConfidence)">
                    {{(chain.overallConfidence * 100).toFixed(0)}}%
                  </span>
                  <mat-chip *ngIf="chain.confidenceBand" class="chain-band-chip">{{chain.confidenceBand}}</mat-chip>
                </div>
              </div>
              <!-- Predictions detail -->
              <div *ngIf="alert.predictions?.length" class="alert-predictions-detail">
                <div *ngFor="let pred of alert.predictions" class="alert-pred-row">
                  <span class="pred-title" [matTooltip]="pred.nodeId">{{pred.title || pred.nodeId | slice:0:24}}</span>
                  <span class="pred-prob" [style.color]="getRiskColor(pred.probability)">
                    {{(pred.probability * 100).toFixed(0)}}%
                  </span>
                  <span class="pred-hops">{{pred.hopsFromSource}} hops</span>
                  <span *ngIf="pred.explanation" class="pred-expl" [matTooltip]="pred.explanation">{{pred.explanation | slice:0:60}}</span>
                </div>
              </div>
              <!-- Alert-level Bayesian priors/posteriors -->
              <div *ngIf="alert.bayesianPosteriors && hasKeys(alert.bayesianPosteriors)"
                   class="alert-bayesian">
                <div *ngFor="let nodeId of getObjectKeys(alert.bayesianPosteriors)"
                     class="bayesian-entry">
                  <span class="bayesian-node-id" [matTooltip]="nodeId">{{nodeId | slice:0:20}}</span>
                  <span class="bayesian-vals">
                    <span class="bayesian-prior"
                          *ngIf="alert.bayesianPriors?.[nodeId] != null">
                      {{((alert.bayesianPriors?.[nodeId] ?? 0) * 100).toFixed(1)}}%
                    </span>
                    <mat-icon class="bayesian-arrow"
                              *ngIf="alert.bayesianPriors?.[nodeId] != null">arrow_forward</mat-icon>
                    <span class="bayesian-posterior"
                          [style.color]="getRiskColor(alert.bayesianPosteriors[nodeId])">
                      {{(alert.bayesianPosteriors[nodeId] * 100).toFixed(1)}}%
                    </span>
                  </span>
                </div>
              </div>
            </div>
          </div>

          <button mat-stroked-button (click)="riskAssessment = null" class="risk-reset">
            <mat-icon>refresh</mat-icon> Reset
          </button>
        </div>
      </div>

      <!-- Step Attribution (when step selected) -->
      <div class="section-card" *ngIf="selectedStepIndex >= 0 && selectedStep">
        <div class="risk-header">
          <h4 class="section-title">
            <mat-icon>psychology</mat-icon>
            Step Attribution
          </h4>
          <button mat-stroked-button (click)="explainStep()" [disabled]="stepAttrLoading"
                  *ngIf="!stepAttributionResult">
            <mat-icon>search</mat-icon> Explain Step
          </button>
        </div>

        <div *ngIf="stepAttrLoading" class="risk-loading">
          <mat-spinner diameter="24"></mat-spinner>
          <span>Analyzing step...</span>
        </div>

        <div *ngIf="stepAttributionResult" class="step-attr-results">
          <div class="step-attr-overview">
            <span class="step-attr-risk" [style.color]="getRiskColor(stepAttributionResult.riskScore)">
              Risk: {{(stepAttributionResult.riskScore * 100).toFixed(0)}}%
            </span>
            <mat-chip *ngIf="stepAttributionResult.hasGraphBindings" class="graph-binding-chip">
              <mat-icon>hub</mat-icon> Graph-linked
            </mat-chip>
          </div>

          <!-- Attribution chains -->
          <div *ngIf="stepAttributionResult.attribution" class="step-attr-section">
            <h5>Why this step? ({{stepAttributionResult.attribution.chains?.length || 0}} chains)</h5>
            <div *ngIf="stepAttributionResult.attribution.synthesizedExplanation" class="attr-explanation">
              {{stepAttributionResult.attribution.synthesizedExplanation}}
            </div>
            <div *ngFor="let chain of stepAttributionResult.attribution.chains?.slice(0, 5); let i = index"
                 class="mini-chain">
              <span class="chain-badge">Chain {{i + 1}}</span>
              <span class="chain-conf" [style.color]="getRiskColor(chain.overallConfidence)">
                {{(chain.overallConfidence * 100).toFixed(0)}}% {{chain.confidenceBand}}
              </span>
              <span class="chain-path-text">
                {{chain.rootCauseTitle}} → {{chain.hops?.length || 0}} hops → {{chain.targetEventTitle}}
              </span>
            </div>
          </div>

          <!-- Influence Scores -->
          <div *ngIf="stepAttributionResult.attribution?.influenceScores && hasKeys(stepAttributionResult.attribution!.influenceScores)"
               class="step-attr-section">
            <h5>
              <mat-icon class="section-icon-sm">trending_up</mat-icon>
              Influence Scores ({{getObjectKeys(stepAttributionResult.attribution!.influenceScores).length}} nodes)
            </h5>
            <div *ngFor="let nodeId of getInfluenceKeys()" class="influence-entry">
              <span class="influence-node" [matTooltip]="nodeId">
                {{graphNodeLabels[nodeId] || nodeId | slice:0:24}}
              </span>
              <span class="influence-score"
                    [style.color]="getRiskColor(stepAttributionResult.attribution!.influenceScores[nodeId])">
                {{(stepAttributionResult.attribution!.influenceScores[nodeId] * 100).toFixed(1)}}%
              </span>
              <div class="influence-bar">
                <div class="influence-bar-fill"
                     [style.width.%]="stepAttributionResult.attribution!.influenceScores[nodeId] * 100"
                     [style.background-color]="getRiskColor(stepAttributionResult.attribution!.influenceScores[nodeId])">
                </div>
              </div>
            </div>
          </div>

          <!-- Counterfactuals -->
          <div *ngIf="stepAttributionResult.attribution?.counterfactuals?.length"
               class="step-attr-section">
            <h5>
              <mat-icon class="section-icon-sm">change_history</mat-icon>
              Counterfactuals ({{stepAttributionResult.attribution!.counterfactuals!.length}})
            </h5>
            <div *ngFor="let cf of stepAttributionResult.attribution!.counterfactuals" class="counterfactual-entry">
              <div class="cf-header">
                <span class="cf-node">{{cf.removedNodeTitle || cf.removedNodeId}}</span>
                <mat-chip class="cf-chip"
                          [class.cf-necessary]="cf.necessaryCause"
                          [class.cf-not-necessary]="!cf.necessaryCause">
                  {{cf.necessaryCause ? 'NECESSARY' : 'NOT NECESSARY'}}
                </mat-chip>
              </div>
              <div class="cf-details">
                <span class="cf-detail">
                  Reachable: {{cf.targetStillReachable ? 'Yes' : 'No'}}
                </span>
                <span class="cf-detail">
                  Surviving chains: {{cf.survivingChainCount}}
                </span>
                <span class="cf-detail" *ngIf="cf.confidenceDelta !== 0"
                      [style.color]="cf.confidenceDelta < 0 ? '#ef5350' : '#66bb6a'">
                  Δ {{(cf.confidenceDelta * 100).toFixed(1)}}%
                </span>
              </div>
              <div class="cf-explanation" *ngIf="cf.explanation">{{cf.explanation}}</div>
            </div>
          </div>

          <!-- Dead Ends -->
          <div *ngIf="stepAttributionResult.attribution?.deadEnds?.length"
               class="step-attr-section">
            <h5>
              <mat-icon class="section-icon-sm">block</mat-icon>
              Dead Ends ({{stepAttributionResult.attribution!.deadEnds!.length}})
            </h5>
            <div class="dead-ends-list">
              <span *ngFor="let de of stepAttributionResult.attribution!.deadEnds" class="dead-end-chip">
                {{graphNodeLabels[de] || de | slice:0:20}}
              </span>
            </div>
          </div>

          <!-- Predictions -->
          <div *ngIf="stepAttributionResult.prediction" class="step-attr-section">
            <h5>What next? ({{stepAttributionResult.prediction.predictions?.length || 0}} predictions)</h5>
            <div *ngIf="stepAttributionResult.prediction.synthesizedForecast" class="attr-explanation">
              {{stepAttributionResult.prediction.synthesizedForecast}}
            </div>
            <div *ngFor="let pred of stepAttributionResult.prediction.predictions?.slice(0, 5)"
                 class="mini-pred">
              <span class="pred-name">{{pred.title}}</span>
              <span class="pred-probability" [style.color]="getRiskColor(pred.probability)">
                {{(pred.probability * 100).toFixed(0)}}%
              </span>
            </div>
          </div>

          <!-- Bayesian Posteriors & Priors -->
          <div *ngIf="stepAttributionResult.bayesianPosteriors && hasKeys(stepAttributionResult.bayesianPosteriors)"
               class="step-attr-section">
            <h5>Bayesian Inference ({{getObjectKeys(stepAttributionResult.bayesianPosteriors).length}} nodes)</h5>
            <div *ngFor="let nodeId of getObjectKeys(stepAttributionResult.bayesianPosteriors)"
                 class="bayesian-entry">
              <span class="bayesian-node-id" [matTooltip]="nodeId">{{nodeId | slice:0:20}}</span>
              <span class="bayesian-vals">
                <span class="bayesian-prior"
                      *ngIf="stepAttributionResult.bayesianPriors?.[nodeId] != null">
                  {{((stepAttributionResult.bayesianPriors?.[nodeId] ?? 0) * 100).toFixed(1)}}%
                </span>
                <mat-icon class="bayesian-arrow"
                          *ngIf="stepAttributionResult.bayesianPriors?.[nodeId] != null">arrow_forward</mat-icon>
                <span class="bayesian-posterior"
                      [style.color]="getRiskColor(stepAttributionResult.bayesianPosteriors[nodeId])">
                  {{(stepAttributionResult.bayesianPosteriors[nodeId] * 100).toFixed(1)}}%
                </span>
              </span>
              <ng-container *ngIf="stepAttributionResult.mebnMeta?.[nodeId] as meta">
                <span class="mebn-badge-sm mfrag-sm">{{meta.mfragName}}</span>
                <span class="mebn-badge-sm role-sm"
                      [class.role-sm-resident]="meta.nodeRole === 'RESIDENT'"
                      [class.role-sm-input]="meta.nodeRole === 'INPUT'">{{meta.nodeRole}}</span>
                <span *ngIf="meta.entityType" class="mebn-badge-sm etype-sm">{{meta.entityType}}</span>
              </ng-container>
            </div>
          </div>

          <button mat-stroked-button (click)="stepAttributionResult = null" class="risk-reset">
            <mat-icon>refresh</mat-icon> Reset
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
              [posteriorValue]="getRunPopoverPosterior(gid)"
              [priorValue]="getRunPopoverPrior(gid)"
              [mebnMfragName]="getRunMebnMeta(gid, 'mfragName')"
              [mebnNodeRole]="getRunMebnMeta(gid, 'nodeRole')"
              [mebnEntityType]="getRunMebnMeta(gid, 'entityType')"
              [mebnEntityId]="getRunMebnMeta(gid, 'entityId')"
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
        <div *ngFor="let att of run.controlResults" class="attestation-block">
          <div class="attestation-row">
            <mat-icon [class]="att.passed ? 'att-pass' : 'att-fail'">
              {{ att.passed ? 'check_circle' : 'cancel' }}
            </mat-icon>
            <span class="att-id">{{ att.controlId | slice:0:12 }}</span>
            <code class="att-expr">{{ att.expressionEvaluated }}</code>
            <span class="att-time">{{ att.evaluatedAt | date:'shortTime' }}</span>
            <button mat-icon-button *ngIf="!att.passed && !controlExplanations[att.controlId]"
                    (click)="explainControlFailure(att.controlId)"
                    [disabled]="controlExplainLoading[att.controlId]"
                    matTooltip="Explain failure">
              <mat-icon>psychology</mat-icon>
            </button>
          </div>
          <div *ngIf="controlExplanations[att.controlId]" class="control-explanation">
            <div class="control-explain-header">
              <mat-icon>lightbulb</mat-icon>
              <span class="control-explain-title">{{ controlExplanations[att.controlId].title }}</span>
              <span class="control-explain-conf">
                {{ (controlExplanations[att.controlId].confidence * 100).toFixed(0) }}%
              </span>
              <button mat-icon-button (click)="clearControlExplanation(att.controlId)" matTooltip="Dismiss">
                <mat-icon>close</mat-icon>
              </button>
            </div>
            <p class="control-explain-text" *ngIf="controlExplanations[att.controlId].explanation">
              {{ controlExplanations[att.controlId].explanation }}
            </p>
            <div class="control-explain-meta">
              <span class="run-alert-type" *ngIf="controlExplanations[att.controlId].alertType">{{controlExplanations[att.controlId].alertType}}</span>
              <span class="run-alert-id" *ngIf="controlExplanations[att.controlId].alertId" [matTooltip]="controlExplanations[att.controlId].alertId">ID: {{controlExplanations[att.controlId].alertId | slice:0:8}}</span>
              <span class="run-alert-ts" *ngIf="controlExplanations[att.controlId].createdAt">{{controlExplanations[att.controlId].createdAt | slice:0:19}}</span>
              <mat-icon *ngIf="controlExplanations[att.controlId].acknowledged" class="run-ack-icon" matTooltip="Acknowledged">check_circle</mat-icon>
            </div>
            <!-- Run-level control explanation causal chains detail -->
            <div *ngIf="controlExplanations[att.controlId].causalChains?.length" class="alert-chains-detail">
              <div *ngFor="let chain of controlExplanations[att.controlId].causalChains" class="alert-chain-row">
                <span class="chain-root" [matTooltip]="chain.rootCauseNodeId">{{chain.rootCauseTitle || chain.rootCauseNodeId | slice:0:20}}</span>
                <mat-icon class="chain-arrow-sm">arrow_forward</mat-icon>
                <span class="chain-target" [matTooltip]="chain.targetEventNodeId">{{chain.targetEventTitle || chain.targetEventNodeId | slice:0:20}}</span>
                <span class="chain-conf" [style.color]="getRiskColor(chain.overallConfidence)">
                  {{(chain.overallConfidence * 100).toFixed(0)}}%
                </span>
              </div>
            </div>
            <!-- Run-level control explanation predictions detail -->
            <div *ngIf="controlExplanations[att.controlId].predictions?.length" class="alert-predictions-detail">
              <div *ngFor="let pred of controlExplanations[att.controlId].predictions" class="alert-pred-row">
                <span class="pred-title" [matTooltip]="pred.nodeId">{{pred.title || pred.nodeId | slice:0:24}}</span>
                <span class="pred-prob" [style.color]="getRiskColor(pred.probability)">
                  {{(pred.probability * 100).toFixed(0)}}%
                </span>
                <span class="pred-hops">{{pred.hopsFromSource}} hops</span>
              </div>
            </div>
            <!-- Control explanation Bayesian priors/posteriors -->
            <ng-container *ngIf="controlExplanations[att.controlId] as ctrlExpl">
              <div *ngIf="ctrlExpl.bayesianPosteriors && hasKeys(ctrlExpl.bayesianPosteriors)"
                   class="alert-bayesian">
                <div *ngFor="let nodeId of getObjectKeys(ctrlExpl.bayesianPosteriors!)"
                     class="bayesian-entry">
                  <span class="bayesian-node-id" [matTooltip]="nodeId">{{nodeId | slice:0:20}}</span>
                  <span class="bayesian-vals">
                    <span class="bayesian-prior"
                          *ngIf="ctrlExpl.bayesianPriors?.[nodeId] != null">
                      {{((ctrlExpl.bayesianPriors?.[nodeId] ?? 0) * 100).toFixed(1)}}%
                    </span>
                    <mat-icon class="bayesian-arrow"
                              *ngIf="ctrlExpl.bayesianPriors?.[nodeId] != null">arrow_forward</mat-icon>
                    <span class="bayesian-posterior"
                          [style.color]="getRiskColor(ctrlExpl.bayesianPosteriors![nodeId])">
                      {{(ctrlExpl.bayesianPosteriors![nodeId] * 100).toFixed(1)}}%
                    </span>
                  </span>
                </div>
              </div>
            </ng-container>
          </div>
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

    /* Risk Assessment & Attribution */
    .risk-header { display: flex; justify-content: space-between; align-items: center; }
    .risk-loading { display: flex; align-items: center; gap: 8px; padding: 12px 0; font-size: 12px; color: #aaa; }
    .risk-overview { display: flex; align-items: center; gap: 16px; margin-bottom: 12px; }
    .risk-score-card { padding: 12px 16px; border-radius: 8px; text-align: center; }
    .risk-score-value { font-size: 24px; font-weight: 700; display: block; }
    .risk-score-label { font-size: 11px; text-transform: uppercase; letter-spacing: 0.5px; }
    .risk-critical, .risk-high { background: rgba(239,68,68,0.15); color: #ef5350; }
    .risk-medium { background: rgba(255,183,77,0.15); color: #ffb74d; }
    .risk-low, .risk-info { background: rgba(102,187,106,0.15); color: #66bb6a; }
    .risk-stats { display: flex; flex-direction: column; gap: 4px; font-size: 12px; color: #888; }
    .risk-summary { font-size: 12px; color: #bbb; background: rgba(255,255,255,0.03); padding: 10px; border-radius: 6px; margin-bottom: 12px; border-left: 3px solid #667eea; }
    .step-risk-list h5, .alerts-list h5 { margin: 12px 0 8px; color: #999; font-size: 11px; text-transform: uppercase; letter-spacing: 0.5px; }
    .step-risk-entry { display: flex; align-items: center; gap: 8px; margin-bottom: 6px; flex-wrap: wrap; }
    .step-risk-name { font-size: 12px; color: #bbb; width: 100px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
    .step-risk-bar { flex: 1; height: 4px; background: rgba(255,255,255,0.05); border-radius: 2px; }
    .step-risk-fill { height: 100%; border-radius: 2px; transition: width 0.3s ease; }
    .step-risk-value { font-size: 11px; font-weight: 600; font-family: monospace; width: 36px; text-align: right; }
    .alert-card { padding: 10px; border-radius: 6px; margin-bottom: 6px; border: 1px solid rgba(255,255,255,0.06); }
    .alert-critical { border-left: 3px solid #ef5350; }
    .alert-high { border-left: 3px solid #ff7043; }
    .alert-medium { border-left: 3px solid #ffb74d; }
    .alert-low, .alert-info { border-left: 3px solid #66bb6a; }
    .alert-header { display: flex; align-items: center; gap: 8px; margin-bottom: 4px; }
    .alert-title { flex: 1; font-size: 13px; font-weight: 500; }
    .severity-chip { font-size: 10px !important; height: 20px !important; min-height: 20px !important; }
    .alert-explanation { font-size: 12px; color: #bbb; margin: 4px 0; }
    .alert-meta { display: flex; gap: 12px; font-size: 11px; color: #888; flex-wrap: wrap; align-items: center; }
    .run-alert-type { padding: 1px 5px; border-radius: 3px; background: rgba(144,202,249,0.12); color: #90caf9; font-size: 9px; font-weight: 500; }
    .run-alert-id { font-family: monospace; font-size: 9px; color: #777; }
    .run-alert-ts { font-family: monospace; font-size: 9px; color: #777; }
    .run-ack-icon { font-size: 14px !important; width: 14px !important; height: 14px !important; color: #66bb6a; }
    .run-llm-icon { font-size: 14px !important; width: 14px !important; height: 14px !important; color: #ce93d8; }
    .risk-reset { margin-top: 8px; }
    .step-attr-overview { display: flex; align-items: center; gap: 12px; margin-bottom: 12px; }
    .step-attr-risk { font-size: 16px; font-weight: 600; }
    .graph-binding-chip { font-size: 10px !important; height: 22px !important; min-height: 22px !important; }
    .step-attr-section { margin-bottom: 12px; }
    .step-attr-section h5 { margin: 0 0 8px; color: #999; font-size: 11px; text-transform: uppercase; }
    .attr-explanation { font-size: 12px; color: #bbb; background: rgba(255,255,255,0.03); padding: 8px; border-radius: 6px; margin-bottom: 8px; border-left: 3px solid #667eea; }
    .mini-chain { display: flex; align-items: center; gap: 8px; padding: 6px 8px; background: rgba(255,255,255,0.02); border-radius: 4px; margin-bottom: 4px; font-size: 12px; }
    .chain-badge { font-size: 10px; font-weight: 600; color: #667eea; }
    .chain-conf { font-size: 11px; font-weight: 600; font-family: monospace; }
    .chain-path-text { flex: 1; color: #bbb; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
    .mini-pred { display: flex; justify-content: space-between; padding: 4px 8px; font-size: 12px; }
    .pred-name { color: #bbb; }
    .pred-probability { font-weight: 600; font-family: monospace; }
    .step-bayesian-inline { width: 100%; padding: 4px 0 0 108px; }
    .bayesian-entry { display: flex; justify-content: space-between; align-items: center; padding: 4px 8px; font-size: 12px; background: rgba(255,255,255,0.02); border-radius: 4px; margin-bottom: 3px; }
    .bayesian-node-id { color: #aaa; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; max-width: 120px; font-size: 11px; }
    .bayesian-vals { display: flex; align-items: center; gap: 4px; }
    .bayesian-prior { color: #888; font-size: 11px; }
    .bayesian-arrow { font-size: 12px; width: 12px; height: 12px; color: #666; }
    .bayesian-posterior { font-weight: 600; font-family: monospace; }
    .step-summary-meta { display: flex; align-items: center; gap: 6px; margin-bottom: 4px; }
    .chain-count-chip { font-size: 10px; padding: 1px 6px; border-radius: 3px; background: rgba(102,126,234,0.12); color: #667eea; }
    .step-narrative { font-size: 11px; color: #aaa; margin-bottom: 4px; line-height: 1.4; }
    .step-influence-inline { margin-top: 4px; padding-top: 4px; border-top: 1px solid rgba(255,255,255,0.06); }
    .influence-label-sm { font-size: 10px; color: #888; text-transform: uppercase; letter-spacing: 0.5px; display: block; margin-bottom: 2px; }
    .influence-entry-sm { display: flex; align-items: center; gap: 4px; font-size: 11px; margin-bottom: 2px; }
    .influence-name-sm { color: #aaa; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; max-width: 80px; }
    .influence-bar-sm { flex: 1; height: 3px; background: rgba(255,255,255,0.06); border-radius: 2px; }
    .influence-fill-sm { height: 100%; border-radius: 2px; }
    .influence-val-sm { font-family: monospace; min-width: 28px; text-align: right; }
    .attestation-block { margin-bottom: 4px; }
    .control-explain-loading { font-size: 11px; color: #888; padding: 4px 0 4px 28px; }
    .control-explanation { background: rgba(255,183,77,0.06); border-left: 3px solid #ffb74d; border-radius: 4px; padding: 8px 10px; margin: 4px 0 4px 28px; }
    .control-explain-header { display: flex; align-items: center; gap: 6px; }
    .control-explain-header mat-icon { font-size: 16px; width: 16px; height: 16px; color: #ffb74d; }
    .control-explain-title { flex: 1; font-size: 12px; font-weight: 500; color: #e0e0e0; }
    .control-explain-conf { font-size: 11px; color: #888; font-family: monospace; }
    .control-explain-text { font-size: 12px; color: #bbb; margin: 4px 0; }
    .control-explain-meta { display: flex; gap: 12px; font-size: 11px; color: #888; flex-wrap: wrap; align-items: center; }
    .alert-bayesian { margin-top: 6px; padding-top: 6px; border-top: 1px solid rgba(255,255,255,0.06); }
    .section-icon-sm { font-size: 14px; width: 14px; height: 14px; margin-right: 4px; vertical-align: middle; }
    .influence-entry { display: flex; align-items: center; gap: 8px; padding: 4px 8px; font-size: 12px; background: rgba(255,255,255,0.02); border-radius: 4px; margin-bottom: 3px; }
    .influence-node { color: #aaa; min-width: 100px; max-width: 160px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; font-size: 11px; }
    .influence-score { font-weight: 600; font-family: monospace; min-width: 50px; text-align: right; }
    .influence-bar { flex: 1; height: 4px; background: rgba(255,255,255,0.06); border-radius: 2px; overflow: hidden; }
    .influence-bar-fill { height: 100%; border-radius: 2px; transition: width 0.3s; }
    .counterfactual-entry { padding: 6px 8px; background: rgba(255,255,255,0.02); border-radius: 4px; margin-bottom: 4px; }
    .cf-header { display: flex; align-items: center; gap: 8px; margin-bottom: 4px; }
    .cf-node { font-size: 12px; font-weight: 500; color: #ddd; }
    .cf-chip { font-size: 9px !important; min-height: 18px !important; padding: 0 6px !important; }
    .cf-necessary { background: rgba(239,83,80,0.15) !important; color: #ef5350 !important; }
    .cf-not-necessary { background: rgba(102,187,106,0.15) !important; color: #66bb6a !important; }
    .cf-details { display: flex; gap: 12px; font-size: 11px; color: #888; }
    .cf-detail { font-family: monospace; }
    .cf-explanation { font-size: 11px; color: #aaa; margin-top: 4px; font-style: italic; }
    .dead-ends-list { display: flex; flex-wrap: wrap; gap: 4px; }
    .dead-end-chip { padding: 2px 8px; border-radius: 3px; font-size: 11px; background: rgba(239,83,80,0.1); color: #ef5350; border: 1px solid rgba(239,83,80,0.2); }

    /* Alert causal chains + predictions detail */
    .alert-chains-detail, .alert-predictions-detail { margin-top: 6px; padding-top: 4px; border-top: 1px solid rgba(255,255,255,0.04); }
    .alert-chain-row, .alert-pred-row { display: flex; align-items: center; gap: 6px; padding: 3px 0; font-size: 11px; }
    .chain-root, .chain-target { color: #bbb; max-width: 120px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
    .chain-arrow-sm { font-size: 12px; width: 12px; height: 12px; color: #666; }
    .chain-conf { font-weight: 600; font-family: monospace; min-width: 32px; text-align: right; }
    .chain-band-chip { font-size: 9px !important; min-height: 16px !important; padding: 0 4px !important; }
    .pred-title { color: #bbb; max-width: 140px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
    .pred-prob { font-weight: 600; font-family: monospace; min-width: 32px; text-align: right; }
    .pred-hops { color: #888; font-size: 10px; }
    .pred-expl { color: #aaa; font-style: italic; font-size: 10px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; max-width: 200px; }

    /* Small MEBN badges for inline Bayesian entries */
    .mebn-badge-sm { display: inline-block; padding: 0 4px; border-radius: 3px; font-size: 9px; font-weight: 500; line-height: 16px; letter-spacing: 0.3px; }
    .mfrag-sm { background: rgba(144,202,249,0.12); color: #64b5f6; border: 1px solid rgba(144,202,249,0.2); }
    .role-sm { background: rgba(206,147,216,0.12); color: #ce93d8; border: 1px solid rgba(206,147,216,0.2); }
    .role-sm-resident { background: rgba(102,187,106,0.12); color: #66bb6a; border-color: rgba(102,187,106,0.2); }
    .role-sm-input { background: rgba(255,183,77,0.12); color: #ffb74d; border-color: rgba(255,183,77,0.2); }
    .etype-sm { background: rgba(245,158,11,0.1); color: #d97706; border: 1px solid rgba(245,158,11,0.2); }
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

  // Risk assessment & attribution state
  riskAssessment: ProcessRiskAssessment | null = null;
  riskLoading = false;
  stepAttributionResult: StepAttributionResult | null = null;
  stepAttrLoading = false;
  controlExplanations: Record<string, ProcessEventAlert> = {};
  controlExplainLoading: Record<string, boolean> = {};

  private destroy$ = new Subject<void>();

  constructor(
    private processEngineService: ProcessEngineService,
    private processAttributionService: ProcessAttributionService,
    private graphService: GraphService,
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
    return this.run.controlResults.filter(a => a.stepId === this.selectedStep!.stepId);
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

  getObjectKeys(obj: Record<string, any>): string[] {
    return obj ? Object.keys(obj) : [];
  }

  getStepAttrSummary(stepId: string): StepAttributionSummary | null {
    const summary = this.riskAssessment?.stepAttributionResults?.[stepId];
    if (!summary || !summary.bayesianPosteriors || !this.hasKeys(summary.bayesianPosteriors)) {
      return null;
    }
    return summary;
  }

  getSortedInfluenceKeys(scores: Record<string, number>): string[] {
    if (!scores) return [];
    return Object.keys(scores).sort((a, b) => (scores[b] || 0) - (scores[a] || 0)).slice(0, 5);
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

  explainControlFailure(controlId: string): void {
    if (!this.run?.id || !this.selectedStep?.stepId) return;
    this.controlExplainLoading[controlId] = true;
    this.processAttributionService.explainControlFailure(this.run.id, this.selectedStep.stepId, controlId)
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: (result) => {
          this.controlExplanations[controlId] = result;
          this.controlExplainLoading[controlId] = false;
        },
        error: () => {
          this.snackBar.open('Failed to explain control failure', 'OK', { duration: 3000 });
          this.controlExplainLoading[controlId] = false;
        }
      });
  }

  clearControlExplanation(controlId: string): void {
    delete this.controlExplanations[controlId];
  }

  selectStep(index: number): void {
    this.selectedStepIndex = this.selectedStepIndex === index ? -1 : index;
    this.stepAttributionResult = null;
    this.stepAttrLoading = false;
  }

  loadRiskAssessment(): void {
    if (!this.run?.id) return;
    this.riskLoading = true;
    this.processAttributionService.assessRunRisk(this.run.id)
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: (result) => {
          this.riskAssessment = result;
          this.riskLoading = false;
        },
        error: () => {
          this.snackBar.open('Failed to assess risk', 'OK', { duration: 3000 });
          this.riskLoading = false;
        }
      });
  }

  explainStep(): void {
    if (!this.run?.id || !this.selectedStep?.stepId) return;
    this.stepAttrLoading = true;
    this.processAttributionService.explainStep(this.run.id, this.selectedStep.stepId)
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: (result) => {
          this.stepAttributionResult = result;
          this.stepAttrLoading = false;
        },
        error: () => {
          this.snackBar.open('Failed to explain step', 'OK', { duration: 3000 });
          this.stepAttrLoading = false;
        }
      });
  }

  getStepRiskEntries(): Array<{ stepId: string; score: number }> {
    if (!this.riskAssessment?.stepRiskScores) return [];
    return Object.entries(this.riskAssessment.stepRiskScores)
      .map(([stepId, score]) => ({ stepId, score }))
      .sort((a, b) => b.score - a.score);
  }

  getRiskColor(score: number): string {
    if (score >= 0.7) return '#ef5350';
    if (score >= 0.4) return '#ffb74d';
    return '#66bb6a';
  }

  /** Posterior for a graph node in the selected step's attribution result. */
  getStepPopoverPosterior(gid: string): number | null {
    return this.stepAttributionResult?.bayesianPosteriors?.[gid] ?? null;
  }

  /** Prior for a graph node in the selected step's attribution result. */
  getStepPopoverPrior(gid: string): number | null {
    return this.stepAttributionResult?.bayesianPriors?.[gid] ?? null;
  }

  /** Posterior for a run-level graph node, searching all step attribution results. */
  getRunPopoverPosterior(gid: string): number | null {
    if (!this.riskAssessment?.stepAttributionResults) return null;
    for (const s of Object.values(this.riskAssessment.stepAttributionResults)) {
      if (s.bayesianPosteriors?.[gid] != null) return s.bayesianPosteriors[gid];
    }
    return null;
  }

  /** Prior for a run-level graph node, searching all step attribution results. */
  getRunPopoverPrior(gid: string): number | null {
    if (!this.riskAssessment?.stepAttributionResults) return null;
    for (const s of Object.values(this.riskAssessment.stepAttributionResults)) {
      if (s.bayesianPriors?.[gid] != null) return s.bayesianPriors[gid];
    }
    return null;
  }

  /** Influence score keys sorted by score descending. */
  getInfluenceKeys(): string[] {
    const scores = this.stepAttributionResult?.attribution?.influenceScores;
    if (!scores) return [];
    return Object.keys(scores).sort((a, b) => (scores[b] || 0) - (scores[a] || 0));
  }

  /** MEBN meta field for a graph node in the selected step's attribution result. */
  getStepMebnMeta(gid: string, field: string): string | null {
    const meta = this.stepAttributionResult?.mebnMeta;
    if (!meta) return null;
    for (const [varName, m] of Object.entries(meta)) {
      // Match by entityId (preferred), or fall back to variable name containing the node ID
      if (m.entityId === gid || varName.includes(gid)) {
        return (m as any)[field] ?? null;
      }
    }
    return null;
  }

  /** MEBN meta field for a run-level graph node, searching all step attribution results. */
  getRunMebnMeta(gid: string, field: string): string | null {
    if (!this.riskAssessment?.stepAttributionResults) return null;
    for (const s of Object.values(this.riskAssessment.stepAttributionResults)) {
      if (!s.mebnMeta) continue;
      for (const [varName, m] of Object.entries(s.mebnMeta)) {
        if (m.entityId === gid || varName.includes(gid)) {
          return (m as any)[field] ?? null;
        }
      }
    }
    return null;
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
    const unresolved = this.run.graphNodeIds.filter(id => !this.graphNodeLabels[id]);
    if (unresolved.length === 0) return;

    for (const nodeId of unresolved.slice(0, 20)) {
      this.graphService.getNode(nodeId)
        .pipe(takeUntil(this.destroy$))
        .subscribe({
          next: (node) => {
            if (node?.title) {
              this.graphNodeLabels[nodeId] = node.title;
            }
          },
          error: () => {} // Node may not exist; keep raw ID
        });
    }
  }
}
