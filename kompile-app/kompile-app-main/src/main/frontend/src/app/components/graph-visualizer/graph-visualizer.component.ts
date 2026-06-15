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

import { Component, OnInit, OnDestroy, Input } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { MatCardModule } from '@angular/material/card';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatSelectModule } from '@angular/material/select';
import { MatInputModule } from '@angular/material/input';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatSliderModule } from '@angular/material/slider';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatChipsModule } from '@angular/material/chips';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';
import { MatTabsModule } from '@angular/material/tabs';
import { MatExpansionModule } from '@angular/material/expansion';
import { MatTooltipModule } from '@angular/material/tooltip';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatMenuModule } from '@angular/material/menu';
import { MatDividerModule } from '@angular/material/divider';
import { MatDialog, MatDialogModule } from '@angular/material/dialog';
import { Subject, Subscription, takeUntil, debounceTime, interval, filter } from 'rxjs';
import { ConfirmDialogComponent, ConfirmDialogData } from '../confirm-dialog/confirm-dialog.component';
import { BayesianPanelComponent } from './bayesian-panel.component';

import { GraphCanvasComponent } from './graph-canvas.component';
import { GraphService, GraphBuildStatus, FactSheetGraphStatistics } from '../../services/graph.service';
import { SourceWeightService } from '../../services/source-weight.service';
import { AttributionService } from '../../services/attribution.service';
import { AttributionResult, PredictionResult } from '../../models/attribution-models';
import {
  D3VisualizationData,
  D3Node,
  GraphNode,
  GraphEdge,
  SourceWeight,
  NodeLevel,
  EdgeType,
  GraphFilter,
  ForceConfig,
  DEFAULT_FORCE_CONFIG,
  CreateEdgeRequest,
  WeightedSearchPreview,
  NODE_COLORS,
  TemporalBounds
} from '../../models/graph-models';

@Component({
  selector: 'app-graph-visualizer',
  standalone: true,
  imports: [
    CommonModule,
    FormsModule,
    MatCardModule,
    MatButtonModule,
    MatIconModule,
    MatSelectModule,
    MatInputModule,
    MatFormFieldModule,
    MatSliderModule,
    MatCheckboxModule,
    MatChipsModule,
    MatProgressSpinnerModule,
    MatSnackBarModule,
    MatTabsModule,
    MatExpansionModule,
    MatTooltipModule,
    MatProgressBarModule,
    MatMenuModule,
    MatDividerModule,
    MatDialogModule,
    GraphCanvasComponent,
    ConfirmDialogComponent,
    BayesianPanelComponent
  ],
  template: `
    <div class="graph-visualizer">
      <!-- Fact Sheet Selector Bar -->
      <div class="fact-sheet-bar" *ngIf="factSheetId">
        <div class="fact-sheet-info">
          <mat-icon>folder_special</mat-icon>
          <span class="fact-sheet-label">Fact Sheet:</span>
          <span class="fact-sheet-name">{{factSheetName || 'ID: ' + factSheetId}}</span>
        </div>
        <div class="fact-sheet-actions">
          <button mat-raised-button color="primary" (click)="buildGraph()" [disabled]="building">
            <mat-icon>build</mat-icon>
            {{building ? 'Building...' : 'Build Graph from Index'}}
          </button>
          <button mat-icon-button [matMenuTriggerFor]="graphMenu" matTooltip="Graph Actions">
            <mat-icon>more_vert</mat-icon>
          </button>
          <mat-menu #graphMenu="matMenu">
            <button mat-menu-item (click)="linkAllSources()">
              <mat-icon>link</mat-icon>
              <span>Link Sources</span>
            </button>
            <button mat-menu-item (click)="rebuildEdges()">
              <mat-icon>sync</mat-icon>
              <span>Rebuild Concept Edges</span>
            </button>
            <button mat-menu-item (click)="viewStatistics()">
              <mat-icon>analytics</mat-icon>
              <span>View Statistics</span>
            </button>
            <mat-divider></mat-divider>
            <button mat-menu-item (click)="clearGraph()" class="warn-action">
              <mat-icon color="warn">delete_forever</mat-icon>
              <span>Clear Graph</span>
            </button>
          </mat-menu>
        </div>
      </div>

      <!-- Build Progress Bar -->
      <div class="build-progress" *ngIf="buildStatus && buildStatus.status === 'RUNNING'">
        <div class="progress-info">
          <span>Building graph: {{buildStatus.processedDocuments}} / {{buildStatus.totalDocuments}} documents</span>
          <span>{{buildStatus.conceptsExtracted}} concepts extracted</span>
        </div>
        <mat-progress-bar mode="determinate"
          [value]="(buildStatus.totalDocuments || 0) > 0 ? ((buildStatus.processedDocuments || 0) / (buildStatus.totalDocuments || 1) * 100) : 0">
        </mat-progress-bar>
        <button mat-icon-button (click)="cancelBuild()" matTooltip="Cancel Build">
          <mat-icon>cancel</mat-icon>
        </button>
      </div>

      <!-- Link Mode Banner -->
      <div class="link-mode-banner" *ngIf="linkMode">
        <div class="link-mode-info">
          <mat-icon>link</mat-icon>
          <span class="link-mode-step" *ngIf="!linkSourceNode">
            <strong>Step 1:</strong> Click a node to select it as the source
          </span>
          <span class="link-mode-step" *ngIf="linkSourceNode">
            <strong>Step 2:</strong> Click another node to create a relation from "{{linkSourceNode.label || linkSourceNode.title}}"
          </span>
        </div>
        <button mat-raised-button (click)="toggleLinkMode()">
          <mat-icon>close</mat-icon>
          Cancel
        </button>
      </div>

      <!-- Toolbar -->
      <div class="toolbar">
        <div class="toolbar-left">
          <button mat-raised-button color="primary" (click)="loadGraph()" [disabled]="loading">
            <mat-icon>refresh</mat-icon>
            Reload
          </button>
          <button mat-raised-button [color]="linkMode ? 'accent' : 'basic'" (click)="toggleLinkMode()">
            <mat-icon>{{linkMode ? 'link_off' : 'add_link'}}</mat-icon>
            {{linkMode ? 'Cancel Link' : 'Create Relation'}}
          </button>
        </div>
        <div class="toolbar-center">
          <mat-form-field appearance="outline" class="search-field">
            <mat-label>Search nodes...</mat-label>
            <input matInput [(ngModel)]="searchQuery" (ngModelChange)="onSearchChange($event)">
            <mat-icon matSuffix>search</mat-icon>
          </mat-form-field>
        </div>
        <div class="toolbar-right">
          <button mat-icon-button (click)="toggleSidePanel()" matTooltip="Toggle Side Panel">
            <mat-icon>{{showSidePanel ? 'chevron_right' : 'chevron_left'}}</mat-icon>
          </button>
        </div>
      </div>

      <!-- Main Content -->
      <div class="main-content">
        <!-- Graph Canvas -->
        <div class="canvas-container">
          <mat-spinner *ngIf="loading" diameter="50"></mat-spinner>
          <app-graph-canvas
            *ngIf="!loading"
            [data]="graphData"
            [forceConfig]="forceConfig"
            [linkMode]="linkMode"
            [showLegend]="true"
            [posteriorOverlay]="posteriorOverlay"
            [priorOverlay]="priorOverlay"
            [mebnMfragMap]="mebnMfragMap"
            [influenceOverlayActive]="influenceOverlayActive"
            (nodeSelected)="onNodeSelected($event)"
            (nodeDoubleClicked)="onNodeDoubleClicked($event)"
            (edgeCreated)="onEdgeCreated($event)"
            (nodeContextMenu)="onNodeContextMenu($event)"
            (linkSourceChanged)="onLinkSourceChanged($event)">
          </app-graph-canvas>
        </div>

        <!-- Side Panel -->
        <div class="side-panel" *ngIf="showSidePanel">
          <mat-tab-group [(selectedIndex)]="selectedTabIndex">
            <!-- Node Details Tab -->
            <mat-tab label="Details">
              <div class="panel-content">
                <div *ngIf="selectedNode" class="node-details">
                  <h3>{{selectedNode.title || selectedNode.label}}</h3>
                  <div class="detail-row">
                    <span class="label">Type:</span>
                    <span class="value type-badge" [class]="selectedNode.type.toLowerCase()">{{selectedNode.type}}</span>
                  </div>
                  <div class="detail-row" *ngIf="selectedNode.description">
                    <span class="label">Description:</span>
                    <span class="value">{{selectedNode.description}}</span>
                  </div>
                  <div class="detail-row" *ngIf="selectedNode.childCount !== undefined">
                    <span class="label">Children:</span>
                    <span class="value">{{selectedNode.childCount}}</span>
                  </div>
                  <div class="detail-row" *ngIf="selectedNode.edgeCount !== undefined">
                    <span class="label">Connections:</span>
                    <span class="value">{{selectedNode.edgeCount}}</span>
                  </div>
                  <div class="detail-row" *ngIf="selectedNode.metadata">
                    <span class="label">Metadata:</span>
                    <pre class="value metadata">{{selectedNode.metadata | json}}</pre>
                  </div>
                  <div class="node-actions">
                    <button mat-stroked-button (click)="expandNode()">
                      <mat-icon>unfold_more</mat-icon>
                      Expand
                    </button>
                    <button mat-stroked-button (click)="setAsRelationSource()">
                      <mat-icon>add_link</mat-icon>
                      Link From
                    </button>
                    <button mat-stroked-button color="warn" (click)="deleteNode()">
                      <mat-icon>delete</mat-icon>
                      Delete
                    </button>
                  </div>

                  <!-- Attribution Section -->
                  <mat-expansion-panel class="attribution-section">
                    <mat-expansion-panel-header>
                      <mat-panel-title>
                        <mat-icon class="section-icon">account_tree</mat-icon>
                        Causal Attribution
                      </mat-panel-title>
                    </mat-expansion-panel-header>
                    <div class="attribution-content">
                      <button mat-stroked-button (click)="loadAttribution()" [disabled]="attributionLoading"
                              *ngIf="!attributionResult">
                        <mat-icon>search</mat-icon> Explain "Why?"
                      </button>
                      <div *ngIf="attributionLoading" class="attr-loading">
                        <mat-spinner diameter="24"></mat-spinner>
                        <span>Analyzing causal chains...</span>
                      </div>
                      <div *ngIf="attributionResult" class="attr-results">
                        <div class="attr-stats">
                          <span class="stat">{{attributionResult.chains.length}} chains</span>
                          <span class="stat">{{attributionResult.nodesVisited}} nodes / {{attributionResult.edgesExamined}} edges</span>
                          <span class="stat">{{attributionResult.computationTimeMs}}ms</span>
                          <span class="stat" *ngIf="attributionResult.llmUsed">
                            <mat-icon class="stat-icon">smart_toy</mat-icon> LLM
                          </span>
                          <span class="stat attr-timestamp" *ngIf="attributionResult.computedAt">
                            {{attributionResult.computedAt | slice:11:19}}
                          </span>
                        </div>
                        <div *ngIf="attributionResult.synthesizedExplanation" class="attr-explanation">
                          {{attributionResult.synthesizedExplanation}}
                        </div>
                        <div *ngFor="let chain of attributionResult.chains; let i = index" class="chain-card">
                          <div class="chain-header">
                            <span class="chain-label">Chain {{i + 1}}</span>
                            <span class="chain-confidence" [style.color]="getConfidenceColor(chain.overallConfidence)">
                              {{(chain.overallConfidence * 100).toFixed(0)}}% {{chain.confidenceBand}}
                            </span>
                            <span class="chain-timestamp" *ngIf="chain.computedAt">{{chain.computedAt | slice:11:19}}</span>
                          </div>
                          <div *ngIf="chain.narrative" class="chain-narrative">{{chain.narrative}}</div>
                          <div class="chain-path">
                            <span class="chain-root">{{chain.rootCauseTitle}}</span>
                            <div *ngFor="let hop of chain.hops" class="chain-hop">
                              <div class="hop-arrow">
                                <mat-icon>arrow_downward</mat-icon>
                                <span class="hop-type">{{formatCausalType(hop.causalType)}}</span>
                                <span class="hop-strength" [style.color]="getConfidenceColor(hop.strength)">
                                  {{(hop.strength * 100).toFixed(0)}}%
                                </span>
                              </div>
                              <span class="hop-node">{{hop.effectTitle}}</span>
                              <div *ngIf="posteriorOverlay && posteriorOverlay[hop.effectNodeId] != null" class="hop-bayesian">
                                <span class="hop-bayesian-label">Post:</span>
                                <span class="hop-bayesian-val" [style.color]="getConfidenceColor(posteriorOverlay[hop.effectNodeId])">
                                  {{(posteriorOverlay[hop.effectNodeId] * 100).toFixed(1)}}%
                                </span>
                                <span *ngIf="priorOverlay && priorOverlay[hop.effectNodeId] != null" class="hop-bayesian-prior">
                                  (Prior: {{(priorOverlay[hop.effectNodeId] * 100).toFixed(1)}}%)
                                </span>
                              </div>
                              <div *ngIf="hop.evidence && hop.evidence.length > 0" class="hop-evidence">
                                <span *ngFor="let ev of hop.evidence" class="evidence-chip"
                                      [matTooltip]="ev.summary || ''">
                                  {{formatEvidenceType(ev.evidenceType)}} ({{(ev.strength * 100).toFixed(0)}}%)
                                </span>
                              </div>
                            </div>
                          </div>
                        </div>
                        <!-- Influence Scores -->
                        <div *ngIf="attributionResult.influenceScores && getInfluenceKeysForAttr().length > 0"
                             class="influence-section">
                          <h4>Influence Scores</h4>
                          <div *ngFor="let nodeId of getInfluenceKeysForAttr()" class="influence-bar-entry">
                            <span class="influence-node" [matTooltip]="nodeId">{{nodeId | slice:0:20}}</span>
                            <div class="influence-bar-track">
                              <div class="influence-bar-fill"
                                   [style.width.%]="(attributionResult.influenceScores[nodeId] || 0) * 100"
                                   [style.background-color]="getConfidenceColor(attributionResult.influenceScores[nodeId] || 0)"></div>
                            </div>
                            <span class="influence-bar-val"
                                  [style.color]="getConfidenceColor(attributionResult.influenceScores[nodeId] || 0)">
                              {{((attributionResult.influenceScores[nodeId] || 0) * 100).toFixed(0)}}%
                            </span>
                          </div>
                        </div>

                        <div *ngIf="attributionResult.counterfactuals && attributionResult.counterfactuals.length > 0"
                             class="counterfactuals">
                          <h4>Counterfactuals</h4>
                          <div *ngFor="let cf of attributionResult.counterfactuals" class="cf-entry">
                            <mat-icon [class.cf-necessary]="cf.necessaryCause">
                              {{cf.necessaryCause ? 'warning' : 'info'}}
                            </mat-icon>
                            <span>
                              Remove "{{cf.removedNodeTitle}}":
                              {{cf.targetStillReachable ? 'still reachable' : 'NOT reachable'}}
                              ({{cf.survivingChainCount}} chains survive,
                              delta {{(cf.confidenceDelta * 100).toFixed(1)}}%)
                            </span>
                            <span *ngIf="cf.explanation" class="cf-explanation">{{cf.explanation}}</span>
                          </div>
                        </div>

                        <!-- Dead Ends -->
                        <div *ngIf="attributionResult.deadEnds && attributionResult.deadEnds.length > 0"
                             class="dead-ends-section">
                          <h4>Dead Ends</h4>
                          <div class="dead-ends-chips">
                            <span *ngFor="let de of attributionResult.deadEnds" class="dead-end-chip"
                                  [matTooltip]="de">{{de | slice:0:20}}</span>
                          </div>
                        </div>

                        <button mat-stroked-button (click)="attributionResult = null" class="attr-reset">
                          <mat-icon>refresh</mat-icon> Reset
                        </button>
                      </div>
                    </div>
                  </mat-expansion-panel>

                  <!-- Prediction Section -->
                  <mat-expansion-panel class="prediction-section">
                    <mat-expansion-panel-header>
                      <mat-panel-title>
                        <mat-icon class="section-icon">trending_up</mat-icon>
                        Predictions
                      </mat-panel-title>
                    </mat-expansion-panel-header>
                    <div class="prediction-content">
                      <button mat-stroked-button (click)="loadPrediction()" [disabled]="predictionLoading"
                              *ngIf="!predictionResult">
                        <mat-icon>search</mat-icon> Predict "What next?"
                      </button>
                      <div *ngIf="predictionLoading" class="attr-loading">
                        <mat-spinner diameter="24"></mat-spinner>
                        <span>Predicting outcomes...</span>
                      </div>
                      <div *ngIf="predictionResult" class="pred-results">
                        <div class="attr-stats">
                          <span class="stat">{{predictionResult.predictions.length}} predictions</span>
                          <span class="stat">{{predictionResult.nodesVisited}} nodes</span>
                          <span class="stat">{{predictionResult.computationTimeMs}}ms</span>
                          <span class="stat" *ngIf="predictionResult.llmUsed">
                            <mat-icon class="stat-icon">smart_toy</mat-icon> LLM
                          </span>
                          <span class="stat attr-timestamp" *ngIf="predictionResult.computedAt">
                            {{predictionResult.computedAt | slice:11:19}}
                          </span>
                        </div>
                        <div *ngIf="predictionResult.synthesizedForecast" class="attr-explanation">
                          {{predictionResult.synthesizedForecast}}
                        </div>
                        <div *ngFor="let pred of predictionResult.predictions" class="pred-entry">
                          <div class="pred-header">
                            <span class="pred-title">{{pred.title}}</span>
                            <span class="pred-prob" [style.color]="getConfidenceColor(pred.probability)">
                              {{(pred.probability * 100).toFixed(1)}}%
                            </span>
                          </div>
                          <div class="pred-bar">
                            <div class="bar-fill" [style.width.%]="pred.probability * 100"
                                 [style.background-color]="getConfidenceColor(pred.probability)"></div>
                            <div *ngIf="priorOverlay && priorOverlay[pred.nodeId] != null"
                                 class="bar-marker prior-marker"
                                 [style.left.%]="priorOverlay[pred.nodeId] * 100"
                                 [matTooltip]="'Prior: ' + (priorOverlay[pred.nodeId] * 100).toFixed(1) + '%'"></div>
                          </div>
                          <div *ngIf="priorOverlay && priorOverlay[pred.nodeId] != null" class="pred-prior-label">
                            <span class="pred-prior-text">Prior: {{(priorOverlay[pred.nodeId] * 100).toFixed(1)}}%</span>
                            <mat-icon class="pred-prior-arrow">arrow_forward</mat-icon>
                            <span class="pred-posterior-text" [style.color]="getConfidenceColor(pred.probability)">
                              Pred: {{(pred.probability * 100).toFixed(1)}}%
                            </span>
                          </div>
                          <div class="pred-meta">
                            <span>{{pred.hopsFromSource}} hops</span>
                            <span *ngIf="pred.pathFromSource?.length" class="pred-path"
                                  [matTooltip]="pred.pathFromSource.join(' → ')">
                              Path: {{pred.pathFromSource.length}} nodes
                            </span>
                            <span *ngIf="pred.pathEdgeTypes?.length" class="pred-edge-types">
                              via {{pred.pathEdgeTypes.join(', ')}}
                            </span>
                          </div>
                          <div *ngIf="pred.evidence && pred.evidence.length > 0" class="hop-evidence">
                            <span *ngFor="let ev of pred.evidence" class="evidence-chip"
                                  [matTooltip]="ev.summary || ev.sourceSnippet || ''">
                              {{formatEvidenceType(ev.evidenceType)}} ({{(ev.strength * 100).toFixed(0)}}%)
                            </span>
                          </div>
                          <div *ngIf="pred.explanation" class="pred-explanation">
                            {{pred.explanation}}
                          </div>
                        </div>
                        <button mat-stroked-button (click)="predictionResult = null" class="attr-reset">
                          <mat-icon>refresh</mat-icon> Reset
                        </button>
                      </div>
                    </div>
                  </mat-expansion-panel>
                </div>
                <div *ngIf="!selectedNode" class="no-selection">
                  <mat-icon>touch_app</mat-icon>
                  <p>Click a node to view details</p>
                </div>
              </div>
            </mat-tab>

            <!-- Relations Tab -->
            <mat-tab label="Relations">
              <div class="panel-content">
                <!-- Create Relation Form -->
                <div class="relation-form">
                  <h4>Create New Relation</h4>

                  <div class="form-row">
                    <span class="label">Source Node</span>
                    <div *ngIf="newRelation.sourceNode" class="node-selector selected" (click)="clearRelationSource()">
                      <span class="node-dot" [style.background-color]="getNodeColor(newRelation.sourceNode.type)"></span>
                      <div class="node-info">
                        <div class="node-label">{{newRelation.sourceNode.label || newRelation.sourceNode.title}}</div>
                        <div class="node-type">{{newRelation.sourceNode.type}}</div>
                      </div>
                      <mat-icon>close</mat-icon>
                    </div>
                    <div *ngIf="!newRelation.sourceNode" class="node-selector-placeholder" (click)="selectNodeForRelation('source')">
                      <mat-icon>add_circle_outline</mat-icon>
                      Click to select source node
                    </div>
                  </div>

                  <div class="form-row">
                    <span class="label">Target Node</span>
                    <div *ngIf="newRelation.targetNode" class="node-selector selected" (click)="clearRelationTarget()">
                      <span class="node-dot" [style.background-color]="getNodeColor(newRelation.targetNode.type)"></span>
                      <div class="node-info">
                        <div class="node-label">{{newRelation.targetNode.label || newRelation.targetNode.title}}</div>
                        <div class="node-type">{{newRelation.targetNode.type}}</div>
                      </div>
                      <mat-icon>close</mat-icon>
                    </div>
                    <div *ngIf="!newRelation.targetNode" class="node-selector-placeholder" (click)="selectNodeForRelation('target')">
                      <mat-icon>add_circle_outline</mat-icon>
                      Click to select target node
                    </div>
                  </div>

                  <div class="form-row">
                    <mat-form-field appearance="outline">
                      <mat-label>Relation Type</mat-label>
                      <mat-select [(ngModel)]="newRelation.edgeType">
                        <mat-option *ngFor="let type of allEdgeTypes" [value]="type">
                          {{formatEdgeType(type)}}
                        </mat-option>
                      </mat-select>
                    </mat-form-field>
                  </div>

                  <div class="form-row">
                    <mat-form-field appearance="outline">
                      <mat-label>Weight (0-1)</mat-label>
                      <input matInput type="number" [(ngModel)]="newRelation.weight" min="0" max="1" step="0.1">
                    </mat-form-field>
                  </div>

                  <div class="form-row">
                    <mat-form-field appearance="outline">
                      <mat-label>Description (optional)</mat-label>
                      <textarea matInput [(ngModel)]="newRelation.description" rows="2"></textarea>
                    </mat-form-field>
                  </div>

                  <div class="relation-actions">
                    <button mat-raised-button color="primary" (click)="saveNewRelation()"
                            [disabled]="!newRelation.sourceNode || !newRelation.targetNode">
                      <mat-icon>save</mat-icon>
                      Save Relation
                    </button>
                    <button mat-stroked-button (click)="clearNewRelation()">
                      <mat-icon>clear</mat-icon>
                      Clear
                    </button>
                  </div>
                </div>

                <!-- Existing Relations List -->
                <h4>Existing Relations</h4>
                <p class="hint" *ngIf="!selectedNode">Select a node to see its relations</p>

                <div class="relations-list" *ngIf="nodeRelations && nodeRelations.length > 0">
                  <div class="relation-item" *ngFor="let rel of nodeRelations">
                    <div class="relation-nodes">
                      <span>{{getNodeLabel(rel.sourceNodeId)}}</span>
                      <mat-icon class="relation-arrow">arrow_forward</mat-icon>
                      <span>{{getNodeLabel(rel.targetNodeId)}}</span>
                    </div>
                    <span class="relation-type-badge" [class]="rel.edgeType.toLowerCase()">
                      {{formatEdgeType(rel.edgeType)}}
                    </span>
                    <span class="relation-weight">{{rel.weight | number:'1.2-2'}}</span>
                    <button mat-icon-button color="warn" (click)="deleteRelation(rel)" matTooltip="Delete relation">
                      <mat-icon>delete</mat-icon>
                    </button>
                  </div>
                </div>
                <div *ngIf="selectedNode && (!nodeRelations || nodeRelations.length === 0)" class="no-selection">
                  <mat-icon>link_off</mat-icon>
                  <p>No relations found for this node</p>
                </div>
              </div>
            </mat-tab>

            <!-- Filter Tab -->
            <mat-tab label="Filter">
              <div class="panel-content">
                <h4>Node Types</h4>
                <div class="filter-chips">
                  <mat-checkbox
                    *ngFor="let type of allNodeTypes"
                    [checked]="filter.nodeTypes.includes(type)"
                    (change)="toggleNodeTypeFilter(type)">
                    {{type}}
                  </mat-checkbox>
                </div>

                <h4>Edge Types</h4>
                <div class="filter-chips">
                  <mat-checkbox
                    *ngFor="let type of allEdgeTypes"
                    [checked]="filter.edgeTypes.includes(type)"
                    (change)="toggleEdgeTypeFilter(type)">
                    {{formatEdgeType(type)}}
                  </mat-checkbox>
                </div>

                <h4>Depth</h4>
                <mat-slider min="1" max="5" step="1" discrete showTickMarks>
                  <input matSliderThumb [(ngModel)]="maxDepth" (ngModelChange)="onDepthChange()">
                </mat-slider>
                <span class="slider-label">{{maxDepth}} levels</span>

                <h4>Max Nodes</h4>
                <mat-slider min="25" max="500" step="25" discrete>
                  <input matSliderThumb [(ngModel)]="maxNodes" (ngModelChange)="onMaxNodesChange()">
                </mat-slider>
                <span class="slider-label">{{maxNodes}} nodes</span>

                <h4>Time Range</h4>
                <mat-checkbox
                  [(ngModel)]="temporalFilterActive"
                  (ngModelChange)="onTemporalFilterToggle()">
                  Filter by time
                </mat-checkbox>
                <div *ngIf="temporalFilterActive" class="temporal-filter-row" style="margin-top: 8px;">
                  <mat-form-field appearance="outline" style="width: 48%; margin-right: 4%;">
                    <mat-label>From</mat-label>
                    <input matInput type="date" [(ngModel)]="timeFrom"
                           (ngModelChange)="onTimeRangeChange()"
                           [attr.min]="temporalBounds?.earliest ? temporalBounds!.earliest!.substring(0,10) : null">
                  </mat-form-field>
                  <mat-form-field appearance="outline" style="width: 48%;">
                    <mat-label>To</mat-label>
                    <input matInput type="date" [(ngModel)]="timeTo"
                           (ngModelChange)="onTimeRangeChange()"
                           [attr.max]="temporalBounds?.latest ? temporalBounds!.latest!.substring(0,10) : null">
                  </mat-form-field>
                  <p class="hint" *ngIf="temporalBounds && temporalBounds.temporalEdgeCount">
                    {{temporalBounds.temporalEdgeCount}} edges with timestamps
                    <span *ngIf="temporalBounds.earliest"> ({{temporalBounds.earliest!.substring(0,10)}} &mdash; {{temporalBounds.latest!.substring(0,10)}})</span>
                  </p>
                </div>

                <!-- Timeline Snapshot Player -->
                <div class="timeline-section" *ngIf="temporalBounds?.earliest && temporalBounds?.latest">
                  <h4>
                    <mat-icon class="section-icon-sm">slow_motion_video</mat-icon>
                    Timeline Snapshots
                  </h4>
                  <p class="hint">Scrub through time to see how the graph evolved</p>

                  <div class="timeline-controls">
                    <button mat-icon-button (click)="timelineStepBack()" matTooltip="Step back"
                            [disabled]="!snapshotMode || snapshotPosition <= 0">
                      <mat-icon>skip_previous</mat-icon>
                    </button>
                    <button mat-icon-button (click)="timelineTogglePlay()" [matTooltip]="timelinePlaying ? 'Pause' : 'Play'">
                      <mat-icon>{{timelinePlaying ? 'pause' : 'play_arrow'}}</mat-icon>
                    </button>
                    <button mat-icon-button (click)="timelineStepForward()" matTooltip="Step forward"
                            [disabled]="!snapshotMode || snapshotPosition >= timelineSteps">
                      <mat-icon>skip_next</mat-icon>
                    </button>
                    <button mat-icon-button (click)="timelineStop()" matTooltip="Stop & show full graph"
                            [disabled]="!snapshotMode">
                      <mat-icon>stop</mat-icon>
                    </button>
                  </div>

                  <div class="timeline-slider" *ngIf="snapshotMode">
                    <mat-slider [min]="0" [max]="timelineSteps" [step]="1" discrete style="width: 100%;">
                      <input matSliderThumb [(ngModel)]="snapshotPosition"
                             (ngModelChange)="onSnapshotPositionChange()">
                    </mat-slider>
                    <div class="snapshot-date-label">
                      <mat-icon class="snapshot-icon">event</mat-icon>
                      <span class="snapshot-date">{{ snapshotDateLabel }}</span>
                      <span class="snapshot-stats">
                        {{ snapshotVisibleNodes }} nodes, {{ snapshotVisibleLinks }} links
                      </span>
                    </div>
                  </div>

                  <div class="timeline-options" *ngIf="snapshotMode">
                    <mat-form-field appearance="outline" class="speed-field">
                      <mat-label>Speed</mat-label>
                      <mat-select [(ngModel)]="timelineSpeedMs" (ngModelChange)="onTimelineSpeedChange()">
                        <mat-option [value]="2000">Slow (2s)</mat-option>
                        <mat-option [value]="1000">Normal (1s)</mat-option>
                        <mat-option [value]="500">Fast (0.5s)</mat-option>
                        <mat-option [value]="200">Very fast (0.2s)</mat-option>
                      </mat-select>
                    </mat-form-field>
                    <mat-form-field appearance="outline" class="steps-field">
                      <mat-label>Steps</mat-label>
                      <mat-select [(ngModel)]="timelineSteps" (ngModelChange)="onTimelineStepsChange()">
                        <mat-option [value]="10">10</mat-option>
                        <mat-option [value]="20">20</mat-option>
                        <mat-option [value]="50">50</mat-option>
                        <mat-option [value]="100">100</mat-option>
                      </mat-select>
                    </mat-form-field>
                    <mat-checkbox [(ngModel)]="snapshotCumulative">
                      Cumulative
                    </mat-checkbox>
                  </div>
                </div>

                <button mat-stroked-button (click)="resetFilters()">
                  <mat-icon>filter_alt_off</mat-icon>
                  Reset Filters
                </button>
              </div>
            </mat-tab>

            <!-- Weights Tab -->
            <mat-tab label="Weights">
              <div class="panel-content">
                <h4>Source Weights</h4>
                <p class="hint">Adjust weights to influence search relevance</p>

                <div class="weight-preview">
                  <mat-form-field appearance="outline" class="full-width">
                    <mat-label>Preview Query</mat-label>
                    <input matInput [(ngModel)]="previewQuery" placeholder="Enter a query to preview weights">
                  </mat-form-field>
                  <button mat-raised-button color="primary" (click)="previewWeights()" [disabled]="!previewQuery">
                    Preview
                  </button>
                </div>

                <div *ngIf="weightPreview" class="weight-results">
                  <h5>Weighted Sources</h5>
                  <div *ngFor="let sw of weightPreview.sourceWeights" class="weight-item">
                    <span class="source-name">{{sw.sourceName}}</span>
                    <span class="source-weight">{{sw.weight | number:'1.2-2'}}</span>
                  </div>
                </div>

                <mat-expansion-panel>
                  <mat-expansion-panel-header>
                    <mat-panel-title>Configure Weights</mat-panel-title>
                  </mat-expansion-panel-header>
                  <div *ngFor="let weight of sourceWeights" class="weight-config">
                    <span class="weight-source">{{weight.sourceName || weight.sourceNodeId}}</span>
                    <mat-slider min="0" max="3" step="0.1">
                      <input matSliderThumb [ngModel]="weight.baseWeight"
                             (ngModelChange)="updateWeight(weight.sourceNodeId, $event)">
                    </mat-slider>
                    <span class="weight-value">{{weight.baseWeight | number:'1.1-1'}}</span>
                  </div>
                </mat-expansion-panel>
              </div>
            </mat-tab>

            <!-- Forces Tab -->
            <mat-tab label="Layout">
              <div class="panel-content">
                <h4>Force Configuration</h4>

                <div class="force-control">
                  <label>Link Distance</label>
                  <mat-slider min="30" max="300" step="10">
                    <input matSliderThumb [(ngModel)]="forceConfig.linkDistance" (ngModelChange)="updateForces()">
                  </mat-slider>
                  <span>{{forceConfig.linkDistance}}</span>
                </div>

                <div class="force-control">
                  <label>Link Strength</label>
                  <mat-slider min="0" max="1" step="0.1">
                    <input matSliderThumb [(ngModel)]="forceConfig.linkStrength" (ngModelChange)="updateForces()">
                  </mat-slider>
                  <span>{{forceConfig.linkStrength | number:'1.1-1'}}</span>
                </div>

                <div class="force-control">
                  <label>Charge Strength</label>
                  <mat-slider min="-1000" max="0" step="50">
                    <input matSliderThumb [(ngModel)]="forceConfig.chargeStrength" (ngModelChange)="updateForces()">
                  </mat-slider>
                  <span>{{forceConfig.chargeStrength}}</span>
                </div>

                <div class="force-control">
                  <label>Collision Radius</label>
                  <mat-slider min="10" max="100" step="5">
                    <input matSliderThumb [(ngModel)]="forceConfig.collisionRadius" (ngModelChange)="updateForces()">
                  </mat-slider>
                  <span>{{forceConfig.collisionRadius}}</span>
                </div>

                <button mat-stroked-button (click)="resetForces()">
                  <mat-icon>restart_alt</mat-icon>
                  Reset Layout
                </button>
              </div>
            </mat-tab>

            <!-- Attribution Tab -->
            <mat-tab label="Attribution">
              <div class="panel-content">
                <app-bayesian-panel
                  [nodeId]="selectedNode?.id || null"
                  (posteriorOverlayChanged)="onPosteriorOverlayChanged($event)"
                  (priorOverlayChanged)="onPriorOverlayChanged($event)"
                  (mebnMfragMapChanged)="onMebnMfragMapChanged($event)">
                </app-bayesian-panel>
              </div>
            </mat-tab>
          </mat-tab-group>
        </div>
      </div>

      <!-- Statistics Bar -->
      <div class="stats-bar" *ngIf="graphData">
        <span class="stat">
          <mat-icon>scatter_plot</mat-icon>
          {{graphData.nodes.length}} nodes
        </span>
        <span class="stat">
          <mat-icon>timeline</mat-icon>
          {{graphData.links.length}} edges
        </span>
      </div>
    </div>
  `,
  styles: [`
    .graph-visualizer {
      display: flex;
      flex-direction: column;
      height: 100%;
      background: var(--bg-body, #f8f9fa);
      color: var(--text-primary, #1a1f36);
    }

    .fact-sheet-bar {
      display: flex;
      justify-content: space-between;
      align-items: center;
      padding: 12px 20px;
      background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
      border-bottom: 1px solid var(--border-color, #e3e8ee);
    }

    .fact-sheet-info {
      display: flex;
      align-items: center;
      gap: 10px;
    }

    .fact-sheet-info mat-icon {
      color: #ffffff;
    }

    .fact-sheet-label {
      color: rgba(255, 255, 255, 0.8);
      font-size: 13px;
    }

    .fact-sheet-name {
      color: #ffffff;
      font-weight: 600;
    }

    .fact-sheet-actions {
      display: flex;
      align-items: center;
      gap: 8px;
    }

    .fact-sheet-actions button[mat-raised-button] {
      background: rgba(255, 255, 255, 0.15);
      color: #ffffff;
    }

    .fact-sheet-actions button[mat-raised-button]:hover {
      background: rgba(255, 255, 255, 0.25);
    }

    .fact-sheet-actions button[mat-icon-button] {
      color: #ffffff;
    }

    .build-progress {
      display: flex;
      align-items: center;
      gap: 16px;
      padding: 12px 20px;
      background: var(--bg-surface, #ffffff);
      border-bottom: 1px solid var(--border-color, #e3e8ee);
    }

    .build-progress .progress-info {
      display: flex;
      flex-direction: column;
      flex: 1;
      font-size: 12px;
      color: var(--text-secondary, #697386);
    }

    .build-progress mat-progress-bar {
      flex: 2;
    }

    .warn-action {
      color: #dc2626 !important;
    }

    .toolbar {
      display: flex;
      justify-content: space-between;
      align-items: center;
      padding: 12px 20px;
      background: var(--bg-surface, #ffffff);
      border-bottom: 1px solid var(--border-color, #e3e8ee);
      box-shadow: var(--shadow-sm, 0 1px 2px 0 rgba(0, 0, 0, 0.05));
    }

    .toolbar-left, .toolbar-right {
      display: flex;
      gap: 10px;
      align-items: center;
    }

    .toolbar-center {
      flex: 1;
      max-width: 400px;
      margin: 0 20px;
    }

    .search-field {
      width: 100%;
    }

    .main-content {
      display: flex;
      flex: 1;
      overflow: hidden;
    }

    .canvas-container {
      flex: 1;
      position: relative;
      display: flex;
      align-items: center;
      justify-content: center;
      background: var(--bg-body, #f8f9fa);
    }

    .side-panel {
      width: 360px;
      background: var(--bg-surface, #ffffff);
      border-left: 1px solid var(--border-color, #e3e8ee);
      overflow-y: auto;
      box-shadow: -2px 0 8px rgba(0, 0, 0, 0.05);
    }

    .panel-content {
      padding: 20px;
    }

    .panel-content h4 {
      margin: 20px 0 12px;
      color: var(--text-secondary, #697386);
      font-size: 11px;
      font-weight: 600;
      text-transform: uppercase;
      letter-spacing: 0.5px;
    }

    .panel-content h4:first-child {
      margin-top: 0;
    }

    .node-details h3 {
      margin: 0 0 16px;
      font-size: 18px;
      color: var(--text-primary, #1a1f36);
      font-weight: 600;
    }

    .detail-row {
      display: flex;
      flex-direction: column;
      margin-bottom: 14px;
    }

    .detail-row .label {
      color: var(--text-tertiary, #8792a2);
      font-size: 12px;
      margin-bottom: 4px;
      font-weight: 500;
    }

    .detail-row .value {
      color: var(--text-primary, #1a1f36);
    }

    .type-badge {
      display: inline-block;
      padding: 4px 10px;
      border-radius: 12px;
      font-size: 11px;
      font-weight: 600;
      color: #ffffff;
    }

    .type-badge.source { background: #22c55e; }
    .type-badge.document { background: #3b82f6; }
    .type-badge.snippet { background: #f59e0b; }
    .type-badge.entity { background: #a855f7; }
    .type-badge.custom { background: #64748b; }

    .metadata {
      background: #f1f5f9;
      padding: 12px;
      border-radius: 8px;
      font-size: 11px;
      font-family: var(--font-family-monospace, 'JetBrains Mono', monospace);
      overflow-x: auto;
      max-height: 150px;
      color: var(--text-primary, #1a1f36);
      border: 1px solid var(--border-color, #e3e8ee);
    }

    .node-actions {
      display: flex;
      gap: 10px;
      margin-top: 20px;
    }

    .no-selection {
      display: flex;
      flex-direction: column;
      align-items: center;
      justify-content: center;
      padding: 40px;
      color: var(--text-tertiary, #8792a2);
    }

    .no-selection mat-icon {
      font-size: 48px;
      width: 48px;
      height: 48px;
      margin-bottom: 16px;
      color: var(--text-tertiary, #8792a2);
    }

    .no-selection p {
      margin: 0;
      font-size: 14px;
    }

    .filter-chips {
      display: flex;
      flex-direction: column;
      gap: 10px;
    }

    .filter-chips mat-checkbox {
      font-size: 13px;
    }

    .slider-label {
      margin-left: 12px;
      color: var(--text-secondary, #697386);
      font-size: 13px;
      font-weight: 500;
    }

    .hint {
      color: var(--text-tertiary, #8792a2);
      font-size: 12px;
      margin-bottom: 16px;
    }

    /* Timeline Snapshot Player */
    .timeline-section {
      margin-top: 16px; padding-top: 12px;
      border-top: 1px solid rgba(255,255,255,0.08);
    }
    .timeline-section h4 {
      display: flex; align-items: center; gap: 6px;
      margin: 0 0 4px; font-size: 13px;
    }
    .section-icon-sm { font-size: 16px; width: 16px; height: 16px; }

    .timeline-controls {
      display: flex; align-items: center; gap: 4px; margin-bottom: 4px;
    }
    .timeline-controls button { color: var(--text-secondary, #90caf9); }
    .timeline-controls button:disabled { color: var(--text-tertiary, #555); }

    .timeline-slider { margin-bottom: 8px; }
    .snapshot-date-label {
      display: flex; align-items: center; gap: 6px;
      font-size: 12px; color: var(--text-secondary, #bbb);
      margin-top: -4px;
    }
    .snapshot-icon { font-size: 14px; width: 14px; height: 14px; color: #ffb74d; }
    .snapshot-date { font-weight: 600; color: #e0e0e0; }
    .snapshot-stats { color: #888; font-size: 11px; margin-left: auto; }

    .timeline-options {
      display: flex; align-items: center; gap: 8px; flex-wrap: wrap;
    }
    .speed-field, .steps-field { width: 110px; }
    .speed-field ::ng-deep .mat-mdc-form-field-infix,
    .steps-field ::ng-deep .mat-mdc-form-field-infix { min-height: 36px; padding-top: 6px; padding-bottom: 6px; }

    .weight-preview {
      display: flex;
      gap: 10px;
      align-items: flex-start;
    }

    .weight-preview .full-width {
      flex: 1;
    }

    .weight-results {
      margin-top: 16px;
    }

    .weight-results h5 {
      margin: 0 0 10px;
      color: var(--text-secondary, #697386);
      font-size: 12px;
      font-weight: 600;
    }

    .weight-item {
      display: flex;
      justify-content: space-between;
      padding: 10px 12px;
      background: #f8fafc;
      border-radius: 8px;
      margin-bottom: 6px;
      border: 1px solid var(--border-color, #e3e8ee);
    }

    .source-name {
      color: var(--text-primary, #1a1f36);
      font-size: 13px;
    }

    .source-weight {
      color: #22c55e;
      font-weight: 600;
      font-size: 13px;
    }

    .weight-config {
      display: flex;
      align-items: center;
      gap: 10px;
      margin-bottom: 10px;
    }

    .weight-source {
      flex: 1;
      font-size: 13px;
      color: var(--text-primary, #1a1f36);
    }

    .weight-value {
      width: 36px;
      text-align: right;
      font-weight: 600;
      color: var(--text-secondary, #697386);
    }

    .force-control {
      display: flex;
      align-items: center;
      gap: 10px;
      margin-bottom: 14px;
    }

    .force-control label {
      width: 110px;
      font-size: 13px;
      color: var(--text-secondary, #697386);
    }

    .force-control mat-slider {
      flex: 1;
    }

    .force-control span:last-child {
      width: 50px;
      text-align: right;
      font-weight: 500;
      color: var(--text-primary, #1a1f36);
    }

    .stats-bar {
      display: flex;
      gap: 24px;
      padding: 10px 20px;
      background: var(--bg-surface, #ffffff);
      border-top: 1px solid var(--border-color, #e3e8ee);
    }

    .stat {
      display: flex;
      align-items: center;
      gap: 6px;
      color: var(--text-secondary, #697386);
      font-size: 13px;
    }

    .stat mat-icon {
      font-size: 16px;
      width: 16px;
      height: 16px;
      color: var(--text-tertiary, #8792a2);
    }

    /* Link Mode Banner */
    .link-mode-banner {
      display: flex;
      align-items: center;
      justify-content: space-between;
      padding: 12px 20px;
      background: linear-gradient(135deg, #3b82f6 0%, #1d4ed8 100%);
      color: #ffffff;
    }

    .link-mode-info {
      display: flex;
      align-items: center;
      gap: 12px;
    }

    .link-mode-info mat-icon {
      color: #ffffff;
    }

    .link-mode-step {
      font-size: 13px;
    }

    .link-mode-step strong {
      color: #fbbf24;
    }

    /* Relation Form */
    .relation-form {
      background: #f8fafc;
      border-radius: 12px;
      padding: 20px;
      border: 1px solid var(--border-color, #e3e8ee);
      margin-bottom: 20px;
    }

    .relation-form h4 {
      margin: 0 0 16px !important;
      color: var(--text-primary, #1a1f36) !important;
      font-size: 14px !important;
      font-weight: 600 !important;
    }

    .form-row {
      margin-bottom: 16px;
    }

    .form-row:last-child {
      margin-bottom: 0;
    }

    .form-row mat-form-field {
      width: 100%;
    }

    .node-selector {
      display: flex;
      align-items: center;
      gap: 10px;
      padding: 10px 12px;
      background: #ffffff;
      border: 1px solid var(--border-color, #e3e8ee);
      border-radius: 8px;
      cursor: pointer;
      transition: all 0.2s ease;
    }

    .node-selector:hover {
      border-color: #667eea;
      background: #f0f4ff;
    }

    .node-selector.selected {
      border-color: #667eea;
      background: #eef2ff;
    }

    .node-selector .node-dot {
      width: 12px;
      height: 12px;
      border-radius: 50%;
    }

    .node-selector .node-info {
      flex: 1;
    }

    .node-selector .node-label {
      font-size: 13px;
      font-weight: 500;
      color: var(--text-primary, #1a1f36);
    }

    .node-selector .node-type {
      font-size: 11px;
      color: var(--text-tertiary, #8792a2);
    }

    .node-selector-placeholder {
      display: flex;
      align-items: center;
      gap: 10px;
      padding: 10px 12px;
      background: #ffffff;
      border: 2px dashed var(--border-color, #e3e8ee);
      border-radius: 8px;
      color: var(--text-tertiary, #8792a2);
      font-size: 13px;
    }

    .relation-actions {
      display: flex;
      gap: 10px;
      margin-top: 20px;
    }

    /* Relations List */
    .relations-list {
      margin-top: 16px;
    }

    .relation-item {
      display: flex;
      align-items: center;
      gap: 12px;
      padding: 12px;
      background: #ffffff;
      border: 1px solid var(--border-color, #e3e8ee);
      border-radius: 8px;
      margin-bottom: 8px;
      transition: all 0.2s ease;
    }

    .relation-item:hover {
      box-shadow: var(--shadow-sm, 0 1px 2px 0 rgba(0, 0, 0, 0.05));
      border-color: #d1d5db;
    }

    .relation-nodes {
      flex: 1;
      display: flex;
      align-items: center;
      gap: 8px;
      font-size: 13px;
    }

    .relation-arrow {
      color: var(--text-tertiary, #8792a2);
    }

    .relation-type-badge {
      padding: 3px 8px;
      border-radius: 8px;
      font-size: 10px;
      font-weight: 600;
      text-transform: uppercase;
      letter-spacing: 0.3px;
    }

    .relation-type-badge.hierarchical { background: #f3f4f6; color: #6b7280; }
    .relation-type-badge.embedding_similarity { background: #dcfce7; color: #166534; }
    .relation-type-badge.shared_entity { background: #f3e8ff; color: #7e22ce; }
    .relation-type-badge.user_defined { background: #dbeafe; color: #1d4ed8; }
    .relation-type-badge.citation { background: #ffedd5; color: #c2410c; }
    .relation-type-badge.temporal { background: #fef3c7; color: #92400e; }
    .relation-type-badge.cross_source { background: #cffafe; color: #0891b2; }

    .relation-weight {
      font-size: 12px;
      color: var(--text-secondary, #697386);
      font-weight: 500;
    }

    /* Attribution & Prediction Styles */
    .attribution-section, .prediction-section {
      margin-top: 16px;
    }

    .section-icon {
      margin-right: 8px;
      font-size: 18px;
      height: 18px;
      width: 18px;
    }

    .attr-loading {
      display: flex;
      align-items: center;
      gap: 8px;
      padding: 12px 0;
      font-size: 12px;
      color: var(--text-secondary, #697386);
    }

    .attr-stats {
      display: flex;
      gap: 12px;
      font-size: 11px;
      color: var(--text-secondary, #697386);
      margin-bottom: 12px;
    }

    .attr-explanation, .pred-explanation {
      font-size: 12px;
      color: var(--text-primary, #1a1f36);
      background: #f8fafc;
      padding: 10px;
      border-radius: 6px;
      margin-bottom: 12px;
      border-left: 3px solid #667eea;
    }

    .chain-card {
      padding: 10px;
      background: var(--bg-surface, #fff);
      border: 1px solid var(--border-color, #e3e8ee);
      border-radius: 8px;
      margin-bottom: 8px;
    }

    .chain-header {
      display: flex;
      justify-content: space-between;
      align-items: center;
      margin-bottom: 8px;
    }

    .chain-label {
      font-size: 12px;
      font-weight: 600;
      color: #667eea;
    }

    .chain-confidence {
      font-size: 11px;
      font-weight: 600;
      font-family: monospace;
    }

    .chain-path {
      display: flex;
      flex-direction: column;
    }

    .chain-root, .hop-node {
      font-size: 12px;
      font-weight: 500;
      padding: 4px 8px;
      background: #f1f5f9;
      border-radius: 4px;
    }

    .hop-arrow {
      display: flex;
      align-items: center;
      gap: 4px;
      padding: 4px 0 4px 12px;
      font-size: 11px;
      color: var(--text-secondary, #697386);
    }

    .hop-arrow mat-icon {
      font-size: 14px;
      height: 14px;
      width: 14px;
    }

    .hop-type {
      font-size: 10px;
      text-transform: uppercase;
      font-weight: 600;
      color: #667eea;
    }

    .hop-strength {
      font-family: monospace;
      font-size: 11px;
      font-weight: 600;
    }

    .hop-bayesian {
      display: flex;
      align-items: center;
      gap: 4px;
      padding: 2px 0 2px 20px;
      font-size: 10px;
    }

    .hop-bayesian-label {
      color: var(--text-secondary, #697386);
      font-weight: 500;
    }

    .hop-bayesian-val {
      font-family: monospace;
      font-weight: 600;
    }

    .hop-bayesian-prior {
      color: var(--text-secondary, #697386);
      font-family: monospace;
    }

    .hop-evidence {
      display: flex;
      flex-wrap: wrap;
      gap: 4px;
      padding: 4px 0 4px 20px;
    }

    .evidence-chip {
      font-size: 10px;
      padding: 2px 6px;
      background: #eef2ff;
      border-radius: 4px;
      color: #4338ca;
      cursor: default;
    }

    .counterfactuals {
      margin-top: 12px;
    }

    .counterfactuals h4 {
      margin: 0 0 8px !important;
    }

    .cf-entry {
      display: flex;
      align-items: flex-start;
      gap: 8px;
      font-size: 11px;
      padding: 6px 0;
      border-bottom: 1px solid var(--border-color, #e3e8ee);
    }

    .cf-entry mat-icon {
      font-size: 16px;
      height: 16px;
      width: 16px;
      color: var(--text-secondary, #697386);
    }

    .cf-necessary {
      color: #f59e0b !important;
    }

    .cf-explanation {
      display: block;
      font-size: 10px;
      color: var(--text-secondary, #697386);
      font-style: italic;
      margin-top: 2px;
    }

    .chain-narrative {
      font-size: 11px;
      color: var(--text-secondary, #697386);
      font-style: italic;
      padding: 4px 8px;
      margin-bottom: 8px;
      background: rgba(102, 126, 234, 0.04);
      border-radius: 4px;
      border-left: 2px solid #667eea;
    }

    .chain-timestamp {
      font-size: 9px;
      color: var(--text-tertiary, #8792a2);
      font-family: monospace;
    }

    .stat-icon {
      font-size: 12px !important;
      width: 12px !important;
      height: 12px !important;
      vertical-align: middle;
    }

    .attr-timestamp {
      font-family: monospace;
      color: var(--text-tertiary, #8792a2);
    }

    .pred-path {
      font-size: 10px;
      color: var(--text-secondary, #697386);
      cursor: pointer;
    }

    .pred-edge-types {
      font-size: 10px;
      color: var(--text-tertiary, #8792a2);
    }

    .influence-section, .dead-ends-section {
      margin-top: 12px;
    }

    .influence-section h4, .dead-ends-section h4 {
      margin: 0 0 8px !important;
      font-size: 13px;
    }

    .influence-bar-entry {
      display: flex;
      align-items: center;
      gap: 6px;
      margin-bottom: 4px;
      font-size: 12px;
    }

    .influence-node {
      color: var(--text-secondary, #697386);
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
      max-width: 100px;
      min-width: 60px;
      font-size: 11px;
    }

    .influence-bar-track {
      flex: 1;
      height: 4px;
      background: var(--bg-body, #f3f4f6);
      border-radius: 2px;
    }

    .influence-bar-fill {
      height: 100%;
      border-radius: 2px;
    }

    .influence-bar-val {
      font-family: monospace;
      font-weight: 600;
      min-width: 32px;
      text-align: right;
      font-size: 12px;
    }

    .dead-ends-chips {
      display: flex;
      flex-wrap: wrap;
      gap: 4px;
    }

    .dead-end-chip {
      padding: 2px 8px;
      border-radius: 4px;
      font-size: 11px;
      background: rgba(239, 68, 68, 0.08);
      color: #ef4444;
      border: 1px solid rgba(239, 68, 68, 0.2);
    }

    .attr-reset {
      margin-top: 8px;
    }

    .pred-entry {
      padding: 8px;
      background: var(--bg-surface, #fff);
      border: 1px solid var(--border-color, #e3e8ee);
      border-radius: 6px;
      margin-bottom: 6px;
    }

    .pred-header {
      display: flex;
      justify-content: space-between;
      align-items: center;
      margin-bottom: 4px;
    }

    .pred-title {
      font-size: 12px;
      font-weight: 500;
      max-width: 200px;
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
    }

    .pred-prob {
      font-size: 13px;
      font-weight: 600;
      font-family: monospace;
    }

    .pred-bar {
      position: relative;
      height: 4px;
      background: #f1f5f9;
      border-radius: 2px;
      margin-bottom: 4px;
      overflow: visible;
    }

    .pred-bar .bar-fill {
      height: 100%;
      border-radius: 2px;
      transition: width 0.3s ease;
    }

    .pred-bar .bar-marker {
      position: absolute;
      top: -2px;
      width: 2px;
      height: 8px;
      background: #1a1f36;
      border-radius: 1px;
    }

    .pred-prior-label {
      display: flex;
      align-items: center;
      gap: 4px;
      margin-bottom: 4px;
      font-size: 10px;
    }

    .pred-prior-text {
      color: var(--text-secondary, #697386);
      font-family: monospace;
    }

    .pred-prior-arrow {
      font-size: 10px;
      height: 10px;
      width: 10px;
      color: var(--text-secondary, #697386);
    }

    .pred-posterior-text {
      font-family: monospace;
      font-weight: 600;
    }

    .pred-meta {
      display: flex;
      gap: 12px;
      font-size: 10px;
      color: var(--text-secondary, #697386);
    }

    ::ng-deep .mat-mdc-tab-body-wrapper {
      flex: 1;
    }

    ::ng-deep .mat-mdc-form-field-subscript-wrapper {
      display: none;
    }

    ::ng-deep .mat-mdc-tab-header {
      background: var(--bg-surface, #ffffff);
      border-bottom: 1px solid var(--border-color, #e3e8ee);
    }

    ::ng-deep .mat-mdc-tab {
      min-width: 80px !important;
    }
  `]
})
export class GraphVisualizerComponent implements OnInit, OnDestroy {
  private destroy$ = new Subject<void>();
  private searchSubject = new Subject<string>();
  private buildPollSubscription: Subscription | null = null;

  // Fact sheet inputs
  @Input() factSheetId: number | null = null;
  @Input() factSheetName: string = '';

  // State
  loading = false;
  building = false;
  graphData: D3VisualizationData | null = null;
  selectedNode: D3Node | null = null;
  showSidePanel = true;
  linkMode = false;
  buildStatus: GraphBuildStatus | null = null;
  graphStatistics: FactSheetGraphStatistics | null = null;
  selectedTabIndex = 0;
  posteriorOverlay: Record<string, number> | null = null;
  priorOverlay: Record<string, number> | null = null;
  mebnMfragMap: Record<string, string> | null = null;  // nodeId -> mfragName
  influenceOverlayActive = false;  // true when posteriorOverlay contains influence scores

  // Attribution & prediction state
  attributionResult: AttributionResult | null = null;
  attributionLoading = false;
  predictionResult: PredictionResult | null = null;
  predictionLoading = false;

  // Link mode state
  linkSourceNode: D3Node | null = null;

  // Relation management
  nodeRelations: GraphEdge[] = [];
  newRelation: {
    sourceNode: D3Node | null;
    targetNode: D3Node | null;
    edgeType: EdgeType;
    weight: number;
    description: string;
  } = {
    sourceNode: null,
    targetNode: null,
    edgeType: 'USER_DEFINED',
    weight: 1.0,
    description: ''
  };
  selectingNodeFor: 'source' | 'target' | null = null;

  // Filters
  searchQuery = '';
  maxDepth = 2;
  maxNodes = 100;
  filter: GraphFilter = {
    nodeTypes: ['SOURCE', 'DOCUMENT', 'SNIPPET', 'ENTITY', 'CUSTOM'],
    edgeTypes: ['HIERARCHICAL', 'EMBEDDING_SIMILARITY', 'SHARED_ENTITY', 'USER_DEFINED']
  };

  // Temporal filtering
  temporalBounds: TemporalBounds | null = null;
  timeFrom: string = '';
  timeTo: string = '';
  temporalFilterActive = false;

  // Timeline snapshot player
  snapshotMode = false;
  snapshotPosition = 0;
  timelineSteps = 20;
  timelineSpeedMs = 1000;
  timelinePlaying = false;
  snapshotCumulative = true;
  snapshotDateLabel = '';
  snapshotVisibleNodes = 0;
  snapshotVisibleLinks = 0;
  private timelineTimer: any = null;
  /** Full unfiltered data cached for snapshot scrubbing */
  private fullGraphData: D3VisualizationData | null = null;

  // Weights
  sourceWeights: SourceWeight[] = [];
  previewQuery = '';
  weightPreview: WeightedSearchPreview | null = null;

  // Forces
  forceConfig: ForceConfig = { ...DEFAULT_FORCE_CONFIG };

  // Type lists
  allNodeTypes: NodeLevel[] = ['SOURCE', 'DOCUMENT', 'SNIPPET', 'ENTITY', 'CUSTOM'];
  allEdgeTypes: EdgeType[] = ['HIERARCHICAL', 'EMBEDDING_SIMILARITY', 'SHARED_ENTITY', 'USER_DEFINED', 'CITATION', 'TEMPORAL', 'CROSS_SOURCE'];

  // Node colors for display
  private nodeColors: Record<NodeLevel, string> = {
    SOURCE: '#22c55e',
    DOCUMENT: '#3b82f6',
    SNIPPET: '#f59e0b',
    ENTITY: '#a855f7',
    CUSTOM: '#64748b',
    TABLE: '#00bcd4',
    ATTACHMENT: '#795548'
  };

  constructor(
    private graphService: GraphService,
    private weightService: SourceWeightService,
    private attributionService: AttributionService,
    private snackBar: MatSnackBar,
    private dialog: MatDialog
  ) {}

  ngOnInit(): void {
    this.loadGraph();
    this.loadSourceWeights();
    this.loadTemporalBounds();

    // Debounce search input
    this.searchSubject.pipe(
      debounceTime(300),
      takeUntil(this.destroy$)
    ).subscribe(query => {
      this.loadGraph(query);
    });
  }

  ngOnDestroy(): void {
    this.stopTimelineTimer();
    this.destroy$.next();
    this.destroy$.complete();
  }

  loadGraph(query?: string): void {
    this.loading = true;

    const from = this.temporalFilterActive && this.timeFrom ? this.timeFrom + 'T00:00:00' : undefined;
    const to = this.temporalFilterActive && this.timeTo ? this.timeTo + 'T23:59:59' : undefined;

    const loadObservable = this.factSheetId
      ? this.graphService.getFactSheetVisualizationData(this.factSheetId, this.maxNodes, this.maxNodes * 2)
      : this.graphService.getVisualizationData(undefined, this.maxDepth, this.maxNodes, from, to);

    loadObservable
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: (data) => {
          // Cache full data for timeline snapshots
          this.fullGraphData = data;
          // Apply filters (snapshot filter handled inside applyFilters)
          this.graphData = this.applyFilters(data, query);
          this.loading = false;
        },
        error: (err) => {
          console.error('Failed to load graph:', err);
          this.snackBar.open('Failed to load knowledge graph', 'Dismiss', { duration: 3000 });
          this.loading = false;
        }
      });
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // FACT SHEET GRAPH OPERATIONS
  // ═══════════════════════════════════════════════════════════════════════════

  buildGraph(): void {
    if (!this.factSheetId) {
      this.snackBar.open('No fact sheet selected', 'Dismiss', { duration: 3000 });
      return;
    }

    this.building = true;
    this.graphService.buildFactSheetGraph(this.factSheetId)
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: (status) => {
          this.buildStatus = status;
          if (status.status === 'RUNNING' || status.status === 'PENDING') {
            this.startBuildPolling(status.jobId);
          } else {
            this.building = false;
            this.loadGraph();
          }
        },
        error: (err) => {
          console.error('Failed to start graph build:', err);
          this.snackBar.open('Failed to start graph build', 'Dismiss', { duration: 3000 });
          this.building = false;
        }
      });
  }

  cancelBuild(): void {
    if (!this.factSheetId || !this.buildStatus?.jobId) return;

    this.graphService.cancelFactSheetBuild(this.factSheetId, this.buildStatus.jobId)
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: () => {
          this.stopBuildPolling();
          this.building = false;
          this.buildStatus = null;
          this.snackBar.open('Build cancelled', 'Dismiss', { duration: 2000 });
        },
        error: (err) => {
          console.error('Failed to cancel build:', err);
        }
      });
  }

  private startBuildPolling(jobId: string): void {
    this.stopBuildPolling();

    this.buildPollSubscription = interval(2000)
      .pipe(takeUntil(this.destroy$))
      .subscribe(() => {
        if (!this.factSheetId) return;

        this.graphService.getFactSheetBuildStatus(this.factSheetId, jobId)
          .subscribe({
            next: (status) => {
              this.buildStatus = status;
              if (status.status === 'COMPLETED' || status.status === 'FAILED' || status.status === 'CANCELLED') {
                this.stopBuildPolling();
                this.building = false;
                if (status.status === 'COMPLETED') {
                  this.snackBar.open(`Graph built: ${status.nodesCreated} nodes, ${status.edgesCreated} edges`, 'Dismiss', { duration: 3000 });
                  this.loadGraph();
                } else if (status.status === 'FAILED') {
                  this.snackBar.open(`Build failed: ${status.errorMessage}`, 'Dismiss', { duration: 5000 });
                }
              }
            },
            error: () => {
              this.stopBuildPolling();
              this.building = false;
            }
          });
      });
  }

  private stopBuildPolling(): void {
    if (this.buildPollSubscription) {
      this.buildPollSubscription.unsubscribe();
      this.buildPollSubscription = null;
    }
  }

  linkAllSources(): void {
    if (!this.factSheetId) return;

    this.snackBar.open('Linking sources...', '', { duration: 2000 });
    this.graphService.linkSources(this.factSheetId)
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: (result) => {
          this.snackBar.open(`Created ${result.linksCreated} source links`, 'Dismiss', { duration: 3000 });
          this.loadGraph();
        },
        error: (err) => {
          console.error('Failed to link sources:', err);
          this.snackBar.open('Failed to link sources', 'Dismiss', { duration: 3000 });
        }
      });
  }

  rebuildEdges(): void {
    if (!this.factSheetId) return;

    this.snackBar.open('Rebuilding concept edges...', '', { duration: 2000 });
    this.graphService.rebuildConceptEdges(this.factSheetId)
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: (result) => {
          this.snackBar.open(`Created ${result.edgesCreated} edges`, 'Dismiss', { duration: 3000 });
          this.loadGraph();
        },
        error: (err) => {
          console.error('Failed to rebuild edges:', err);
          this.snackBar.open('Failed to rebuild edges', 'Dismiss', { duration: 3000 });
        }
      });
  }

  viewStatistics(): void {
    if (!this.factSheetId) return;

    this.graphService.getFactSheetStatistics(this.factSheetId)
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: (stats) => {
          this.graphStatistics = stats;
          // Show statistics in a snackbar or dialog
          const nodeCount = Object.values(stats.nodesByType || {}).reduce((a: number, b: number) => a + b, 0);
          const edgeCount = Object.values(stats.edgesByType || {}).reduce((a: number, b: number) => a + b, 0);
          this.snackBar.open(`Graph: ${nodeCount} nodes, ${edgeCount} edges, ${stats.distinctConcepts || 0} concepts`, 'Dismiss', { duration: 5000 });
        },
        error: (err) => {
          console.error('Failed to get statistics:', err);
        }
      });
  }

  clearGraph(): void {
    if (!this.factSheetId) return;

    const dialogData: ConfirmDialogData = {
      title: 'Clear Graph',
      message: 'Are you sure you want to clear the entire graph for this fact sheet? This cannot be undone.',
      confirmText: 'Clear Graph',
      confirmColor: 'warn',
      icon: 'delete_forever'
    };

    this.dialog.open(ConfirmDialogComponent, { data: dialogData })
      .afterClosed()
      .pipe(
        filter(confirmed => confirmed === true),
        takeUntil(this.destroy$)
      )
      .subscribe(() => {
        this.graphService.clearFactSheetGraph(this.factSheetId!)
          .pipe(takeUntil(this.destroy$))
          .subscribe({
            next: (result) => {
              this.snackBar.open(`Cleared ${result.entitiesDeleted} entities`, 'Dismiss', { duration: 3000 });
              this.loadGraph();
            },
            error: (err) => {
              console.error('Failed to clear graph:', err);
              this.snackBar.open('Failed to clear graph', 'Dismiss', { duration: 3000 });
            }
          });
      });
  }

  loadSourceWeights(): void {
    this.weightService.getWeights()
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: (weights) => {
          this.sourceWeights = weights;
        },
        error: (err) => {
          console.error('Failed to load weights:', err);
        }
      });
  }

  applyFilters(data: D3VisualizationData, searchQuery?: string): D3VisualizationData {
    let nodes = data.nodes.filter(n => this.filter.nodeTypes.includes(n.type));
    let links = data.links.filter(l => this.filter.edgeTypes.includes(l.type));

    // Apply temporal filter on links with occurredAt
    if (this.temporalFilterActive && (this.timeFrom || this.timeTo)) {
      const fromDate = this.timeFrom ? this.timeFrom + 'T00:00:00' : null;
      const toDate = this.timeTo ? this.timeTo + 'T23:59:59' : null;
      links = links.filter(l => {
        if (!l.occurredAt) return true; // keep links without timestamps
        if (fromDate && l.occurredAt < fromDate) return false;
        if (toDate && l.occurredAt > toDate) return false;
        return true;
      });
    }

    // Apply timeline snapshot filter
    if (this.snapshotMode && this.temporalBounds?.earliest && this.temporalBounds?.latest) {
      const cutoff = this.getSnapshotCutoffDate();
      if (cutoff) {
        if (this.snapshotCumulative) {
          // Cumulative: show everything up to cutoff
          links = links.filter(l => {
            if (!l.occurredAt) return true;
            return l.occurredAt <= cutoff;
          });
          nodes = nodes.filter(n => {
            if (!n.occurredAt) return true;
            return n.occurredAt <= cutoff;
          });
        } else {
          // Window: show only events in current step's time window
          const windowStart = this.getSnapshotWindowStart();
          links = links.filter(l => {
            if (!l.occurredAt) return false;
            return l.occurredAt >= windowStart && l.occurredAt <= cutoff;
          });
          nodes = nodes.filter(n => {
            if (!n.occurredAt) return false;
            return n.occurredAt >= windowStart && n.occurredAt <= cutoff;
          });
        }
      }
    }

    if (searchQuery) {
      const query = searchQuery.toLowerCase();
      nodes = nodes.filter(n =>
        (n.label || '').toLowerCase().includes(query) ||
        (n.title || '').toLowerCase().includes(query) ||
        (n.description || '').toLowerCase().includes(query)
      );
    }

    // Keep only links where both nodes are in filtered set
    const nodeIds = new Set(nodes.map(n => n.id));
    links = links.filter(l => nodeIds.has(l.source) && nodeIds.has(l.target));

    // Update snapshot stats
    if (this.snapshotMode) {
      this.snapshotVisibleNodes = nodes.length;
      this.snapshotVisibleLinks = links.length;
    }

    return { nodes, links };
  }

  onSearchChange(query: string): void {
    this.searchSubject.next(query);
  }

  onNodeSelected(node: D3Node | null): void {
    // Handle selecting nodes for the relation form
    if (node && this.selectingNodeFor) {
      if (this.selectingNodeFor === 'source') {
        this.newRelation.sourceNode = node;
      } else if (this.selectingNodeFor === 'target') {
        this.newRelation.targetNode = node;
      }
      this.selectingNodeFor = null;
      this.snackBar.open('Node selected', '', { duration: 1000 });
      return;
    }

    // Normal selection
    this.selectedNode = node;
    this.attributionResult = null;
    this.predictionResult = null;

    // Load relations for the selected node
    if (node) {
      this.loadNodeRelations(node.id);
    } else {
      this.nodeRelations = [];
    }
  }

  onPosteriorOverlayChanged(overlay: Record<string, number> | null): void {
    this.posteriorOverlay = overlay;
    this.influenceOverlayActive = false;  // Bayesian posteriors are true posteriors, not influence scores
  }

  onPriorOverlayChanged(overlay: Record<string, number> | null): void {
    this.priorOverlay = overlay;
  }

  onMebnMfragMapChanged(map: Record<string, string> | null): void {
    this.mebnMfragMap = map;
  }

  onNodeDoubleClicked(node: D3Node): void {
    // Expand node to show its children
    this.expandNodeById(node.id);
  }

  onEdgeCreated(edge: { source: string; target: string }): void {
    const request: CreateEdgeRequest = {
      sourceNodeId: edge.source,
      targetNodeId: edge.target,
      edgeType: 'USER_DEFINED',
      weight: 1.0
    };

    this.graphService.createEdge(request)
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: () => {
          this.snackBar.open('Edge created successfully', 'Dismiss', { duration: 2000 });
          this.loadGraph();
        },
        error: (err) => {
          console.error('Failed to create edge:', err);
          this.snackBar.open('Failed to create edge', 'Dismiss', { duration: 3000 });
        }
      });
  }

  onNodeContextMenu(event: { node: D3Node; event: MouseEvent }): void {
    // Could show a context menu here
    console.log('Context menu for node:', event.node);
  }

  onLinkSourceChanged(node: D3Node | null): void {
    this.linkSourceNode = node;
  }

  toggleLinkMode(): void {
    this.linkMode = !this.linkMode;
    if (this.linkMode) {
      this.linkSourceNode = null;
      this.snackBar.open('Click a node to start creating a relation', 'Dismiss', { duration: 3000 });
    } else {
      this.linkSourceNode = null;
    }
  }

  toggleSidePanel(): void {
    this.showSidePanel = !this.showSidePanel;
  }

  expandNode(): void {
    if (this.selectedNode) {
      this.expandNodeById(this.selectedNode.id);
    }
  }

  expandNodeById(nodeId: string): void {
    this.graphService.getConnectedNodes(nodeId, 1)
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: () => {
          this.loadGraph();
        },
        error: (err) => {
          console.error('Failed to expand node:', err);
        }
      });
  }

  deleteNode(): void {
    if (!this.selectedNode) return;

    const dialogData: ConfirmDialogData = {
      title: 'Delete Node',
      message: `Are you sure you want to delete "${this.selectedNode.label || this.selectedNode.title}"?`,
      confirmText: 'Delete',
      confirmColor: 'warn',
      icon: 'delete'
    };

    const nodeId = this.selectedNode.id;
    this.dialog.open(ConfirmDialogComponent, { data: dialogData })
      .afterClosed()
      .pipe(
        filter(confirmed => confirmed === true),
        takeUntil(this.destroy$)
      )
      .subscribe(() => {
        this.graphService.deleteNode(nodeId)
          .pipe(takeUntil(this.destroy$))
          .subscribe({
            next: () => {
              this.snackBar.open('Node deleted successfully', 'Dismiss', { duration: 2000 });
              this.selectedNode = null;
              this.loadGraph();
            },
            error: (err) => {
              console.error('Failed to delete node:', err);
              this.snackBar.open('Failed to delete node', 'Dismiss', { duration: 3000 });
            }
          });
      });
  }

  toggleNodeTypeFilter(type: NodeLevel): void {
    const index = this.filter.nodeTypes.indexOf(type);
    if (index >= 0) {
      this.filter.nodeTypes.splice(index, 1);
    } else {
      this.filter.nodeTypes.push(type);
    }
    if (this.graphData) {
      this.loadGraph();
    }
  }

  toggleEdgeTypeFilter(type: EdgeType): void {
    const index = this.filter.edgeTypes.indexOf(type);
    if (index >= 0) {
      this.filter.edgeTypes.splice(index, 1);
    } else {
      this.filter.edgeTypes.push(type);
    }
    if (this.graphData) {
      this.loadGraph();
    }
  }

  onDepthChange(): void {
    this.loadGraph();
  }

  onMaxNodesChange(): void {
    this.loadGraph();
  }

  resetFilters(): void {
    this.filter = {
      nodeTypes: [...this.allNodeTypes],
      edgeTypes: [...this.allEdgeTypes.slice(0, 4)]
    };
    this.maxDepth = 2;
    this.maxNodes = 100;
    this.searchQuery = '';
    this.temporalFilterActive = false;
    this.timeFrom = '';
    this.timeTo = '';
    this.timelineStop();
    this.loadGraph();
  }

  loadTemporalBounds(): void {
    this.graphService.getTemporalBounds()
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: (bounds) => {
          this.temporalBounds = bounds;
        },
        error: (err) => {
          console.error('Failed to load temporal bounds:', err);
        }
      });
  }

  onTemporalFilterToggle(): void {
    if (!this.temporalFilterActive) {
      this.timeFrom = '';
      this.timeTo = '';
    }
    this.loadGraph();
  }

  onTimeRangeChange(): void {
    if (this.temporalFilterActive) {
      this.loadGraph();
    }
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // TIMELINE SNAPSHOT PLAYER
  // ═══════════════════════════════════════════════════════════════════════════

  /**
   * Compute the ISO cutoff date string for the current snapshot position.
   * Maps snapshotPosition (0..timelineSteps) linearly across the temporal bounds.
   */
  private getSnapshotCutoffDate(): string {
    if (!this.temporalBounds?.earliest || !this.temporalBounds?.latest) return '';
    const earliest = new Date(this.temporalBounds.earliest).getTime();
    const latest = new Date(this.temporalBounds.latest).getTime();
    const range = latest - earliest;
    const fraction = this.snapshotPosition / this.timelineSteps;
    const cutoffMs = earliest + range * fraction;
    return new Date(cutoffMs).toISOString();
  }

  /**
   * For non-cumulative (window) mode, compute the start of the current step's window.
   */
  private getSnapshotWindowStart(): string {
    if (!this.temporalBounds?.earliest || !this.temporalBounds?.latest) return '';
    const earliest = new Date(this.temporalBounds.earliest).getTime();
    const latest = new Date(this.temporalBounds.latest).getTime();
    const range = latest - earliest;
    const stepSize = range / this.timelineSteps;
    const windowStartMs = earliest + stepSize * Math.max(0, this.snapshotPosition - 1);
    return new Date(windowStartMs).toISOString();
  }

  /**
   * Reapply snapshot filter using cached full data (no backend round-trip).
   */
  private applySnapshotFilter(): void {
    if (!this.fullGraphData) return;
    // Update date label
    const cutoff = this.getSnapshotCutoffDate();
    if (cutoff) {
      const d = new Date(cutoff);
      this.snapshotDateLabel = d.toLocaleDateString(undefined, {
        year: 'numeric', month: 'short', day: 'numeric',
        hour: '2-digit', minute: '2-digit'
      });
    }
    this.graphData = this.applyFilters(this.fullGraphData);
  }

  timelineTogglePlay(): void {
    if (this.timelinePlaying) {
      this.timelinePause();
    } else {
      this.timelinePlay();
    }
  }

  timelinePlay(): void {
    if (!this.snapshotMode) {
      this.snapshotMode = true;
      this.snapshotPosition = 0;
    }
    this.timelinePlaying = true;
    this.applySnapshotFilter();
    this.startTimelineTimer();
  }

  private startTimelineTimer(): void {
    this.stopTimelineTimer();
    this.timelineTimer = setInterval(() => {
      if (this.snapshotPosition >= this.timelineSteps) {
        this.timelinePause();
        return;
      }
      this.snapshotPosition++;
      this.applySnapshotFilter();
    }, this.timelineSpeedMs);
  }

  private stopTimelineTimer(): void {
    if (this.timelineTimer) {
      clearInterval(this.timelineTimer);
      this.timelineTimer = null;
    }
  }

  timelinePause(): void {
    this.timelinePlaying = false;
    this.stopTimelineTimer();
  }

  timelineStop(): void {
    this.timelinePause();
    this.snapshotMode = false;
    this.snapshotPosition = 0;
    this.snapshotDateLabel = '';
    this.snapshotVisibleNodes = 0;
    this.snapshotVisibleLinks = 0;
    // Restore full graph
    if (this.fullGraphData) {
      this.graphData = this.applyFilters(this.fullGraphData);
    }
  }

  timelineStepForward(): void {
    if (!this.snapshotMode) {
      this.snapshotMode = true;
      this.snapshotPosition = 0;
    }
    if (this.snapshotPosition < this.timelineSteps) {
      this.snapshotPosition++;
      this.applySnapshotFilter();
    }
  }

  timelineStepBack(): void {
    if (this.snapshotPosition > 0) {
      this.snapshotPosition--;
      this.applySnapshotFilter();
    }
  }

  onSnapshotPositionChange(): void {
    this.applySnapshotFilter();
  }

  onTimelineSpeedChange(): void {
    if (this.timelinePlaying) {
      this.startTimelineTimer();
    }
  }

  onTimelineStepsChange(): void {
    // Clamp position to new step count
    if (this.snapshotPosition > this.timelineSteps) {
      this.snapshotPosition = this.timelineSteps;
    }
    if (this.snapshotMode) {
      this.applySnapshotFilter();
    }
  }

  previewWeights(): void {
    if (!this.previewQuery) return;

    this.weightService.previewWeightedSearch(this.previewQuery)
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: (preview) => {
          this.weightPreview = preview;
        },
        error: (err) => {
          console.error('Failed to preview weights:', err);
          this.snackBar.open('Failed to preview weights', 'Dismiss', { duration: 3000 });
        }
      });
  }

  updateWeight(sourceNodeId: string, weight: number): void {
    this.weightService.setWeight({
      sourceNodeId,
      baseWeight: weight
    }).pipe(takeUntil(this.destroy$))
      .subscribe({
        next: () => {
          // Update local state
          const idx = this.sourceWeights.findIndex(w => w.sourceNodeId === sourceNodeId);
          if (idx >= 0) {
            this.sourceWeights[idx].baseWeight = weight;
          }
        },
        error: (err) => {
          console.error('Failed to update weight:', err);
          this.snackBar.open('Failed to update weight', 'Dismiss', { duration: 3000 });
        }
      });
  }

  updateForces(): void {
    // Force config is bound to forceConfig input which triggers change detection
    this.forceConfig = { ...this.forceConfig };
  }

  resetForces(): void {
    this.forceConfig = { ...DEFAULT_FORCE_CONFIG };
  }

  formatEdgeType(type: EdgeType): string {
    return type.toLowerCase().replace(/_/g, ' ');
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // RELATION MANAGEMENT
  // ═══════════════════════════════════════════════════════════════════════════

  /**
   * Get node color by type
   */
  getNodeColor(type: NodeLevel): string {
    return this.nodeColors[type] || '#64748b';
  }

  /**
   * Get node label by ID
   */
  getNodeLabel(nodeId: string): string {
    if (!this.graphData) return nodeId;
    const node = this.graphData.nodes.find(n => n.id === nodeId);
    return node ? (node.label || node.title || nodeId) : nodeId;
  }

  /**
   * Set selected node as the source for a new relation
   */
  setAsRelationSource(): void {
    if (this.selectedNode) {
      this.newRelation.sourceNode = this.selectedNode;
      this.selectedTabIndex = 1; // Switch to Relations tab
      this.snackBar.open('Source node selected. Now select a target node.', 'Dismiss', { duration: 3000 });
    }
  }

  /**
   * Start selecting a node for the relation form
   */
  selectNodeForRelation(role: 'source' | 'target'): void {
    this.selectingNodeFor = role;
    this.snackBar.open(`Click a node on the graph to select it as ${role}`, 'Cancel', { duration: 5000 })
      .onAction().subscribe(() => {
        this.selectingNodeFor = null;
      });
  }

  /**
   * Clear the source node from new relation
   */
  clearRelationSource(): void {
    this.newRelation.sourceNode = null;
  }

  /**
   * Clear the target node from new relation
   */
  clearRelationTarget(): void {
    this.newRelation.targetNode = null;
  }

  /**
   * Clear the entire new relation form
   */
  clearNewRelation(): void {
    this.newRelation = {
      sourceNode: null,
      targetNode: null,
      edgeType: 'USER_DEFINED',
      weight: 1.0,
      description: ''
    };
    this.selectingNodeFor = null;
  }

  /**
   * Save a new relation to the backend
   */
  saveNewRelation(): void {
    if (!this.newRelation.sourceNode || !this.newRelation.targetNode) {
      this.snackBar.open('Please select both source and target nodes', 'Dismiss', { duration: 3000 });
      return;
    }

    const request: CreateEdgeRequest = {
      sourceNodeId: this.newRelation.sourceNode.id,
      targetNodeId: this.newRelation.targetNode.id,
      edgeType: this.newRelation.edgeType,
      weight: this.newRelation.weight,
      description: this.newRelation.description || undefined
    };

    this.graphService.createEdge(request)
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: () => {
          this.snackBar.open('Relation created successfully', 'Dismiss', { duration: 3000 });
          this.clearNewRelation();
          this.loadGraph();
          // Reload relations if a node is selected
          if (this.selectedNode) {
            this.loadNodeRelations(this.selectedNode.id);
          }
        },
        error: (err) => {
          console.error('Failed to create relation:', err);
          this.snackBar.open('Failed to create relation', 'Dismiss', { duration: 3000 });
        }
      });
  }

  /**
   * Load relations for a specific node
   */
  loadNodeRelations(nodeId: string): void {
    this.graphService.getEdges(nodeId)
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: (edges) => {
          this.nodeRelations = edges;
        },
        error: (err) => {
          console.error('Failed to load node relations:', err);
          this.nodeRelations = [];
        }
      });
  }

  /**
   * Delete a relation
   */
  deleteRelation(relation: GraphEdge): void {
    const dialogData: ConfirmDialogData = {
      title: 'Delete Relation',
      message: 'Are you sure you want to delete this relation?',
      confirmText: 'Delete',
      confirmColor: 'warn',
      icon: 'link_off'
    };

    this.dialog.open(ConfirmDialogComponent, { data: dialogData })
      .afterClosed()
      .pipe(
        filter(confirmed => confirmed === true),
        takeUntil(this.destroy$)
      )
      .subscribe(() => {
        this.graphService.deleteEdge(relation.edgeId)
          .pipe(takeUntil(this.destroy$))
          .subscribe({
            next: () => {
              this.snackBar.open('Relation deleted successfully', 'Dismiss', { duration: 2000 });
              this.loadGraph();
              if (this.selectedNode) {
                this.loadNodeRelations(this.selectedNode.id);
              }
            },
            error: (err) => {
              console.error('Failed to delete relation:', err);
              this.snackBar.open('Failed to delete relation', 'Dismiss', { duration: 3000 });
            }
          });
      });
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // ATTRIBUTION & PREDICTION
  // ═══════════════════════════════════════════════════════════════════════════

  loadAttribution(): void {
    if (!this.selectedNode) return;
    this.attributionLoading = true;

    this.attributionService.explainQuick(this.selectedNode.id, {
      includeCounterfactuals: true
    }).pipe(takeUntil(this.destroy$)).subscribe({
      next: (result) => {
        this.attributionResult = result;
        this.attributionLoading = false;

        // Merge attribution chain influence scores into posterior overlay
        // so chain nodes are visually highlighted on the graph canvas
        this.mergeAttributionOverlay(result);
      },
      error: (err) => {
        console.error('Attribution query failed:', err);
        this.snackBar.open('Attribution query failed', 'Dismiss', { duration: 3000 });
        this.attributionLoading = false;
      }
    });
  }

  /**
   * Merge attribution chain node influence scores into the posterior overlay
   * so they render on the graph canvas as heat-colored nodes.
   */
  private mergeAttributionOverlay(result: AttributionResult): void {
    const overlay = this.posteriorOverlay ? { ...this.posteriorOverlay } : {} as Record<string, number>;
    let changed = false;

    // Use influence scores as posterior-like values for each node
    if (result.influenceScores) {
      for (const [nodeId, score] of Object.entries(result.influenceScores)) {
        if (overlay[nodeId] === undefined) {
          overlay[nodeId] = score;
          changed = true;
        }
      }
    }

    // Add chain hop nodes with their strength values
    for (const chain of result.chains) {
      if (chain.rootCauseNodeId && overlay[chain.rootCauseNodeId] === undefined) {
        overlay[chain.rootCauseNodeId] = chain.overallConfidence;
        changed = true;
      }
      for (const hop of chain.hops) {
        if (hop.effectNodeId && overlay[hop.effectNodeId] === undefined) {
          overlay[hop.effectNodeId] = hop.strength;
          changed = true;
        }
        if (hop.causeNodeId && overlay[hop.causeNodeId] === undefined) {
          overlay[hop.causeNodeId] = hop.strength;
          changed = true;
        }
      }
    }

    if (changed) {
      this.posteriorOverlay = overlay;
      this.influenceOverlayActive = true;
    }
  }

  loadPrediction(): void {
    if (!this.selectedNode) return;
    this.predictionLoading = true;

    this.attributionService.predictQuick(this.selectedNode.id).pipe(
      takeUntil(this.destroy$)
    ).subscribe({
      next: (result) => {
        this.predictionResult = result;
        this.predictionLoading = false;
      },
      error: (err) => {
        console.error('Prediction query failed:', err);
        this.snackBar.open('Prediction query failed', 'Dismiss', { duration: 3000 });
        this.predictionLoading = false;
      }
    });
  }

  getConfidenceColor(value: number): string {
    if (value < 0.3) return '#3b82f6';
    if (value < 0.5) return '#f59e0b';
    if (value < 0.7) return '#f97316';
    return '#ef4444';
  }

  formatCausalType(type: string): string {
    return type.toLowerCase().replace(/_/g, ' ');
  }

  formatEvidenceType(type: string): string {
    return type.toLowerCase().replace(/_/g, ' ');
  }

  getInfluenceKeysForAttr(): string[] {
    if (!this.attributionResult?.influenceScores) return [];
    return Object.keys(this.attributionResult.influenceScores)
      .sort((a, b) => (this.attributionResult!.influenceScores[b] || 0) - (this.attributionResult!.influenceScores[a] || 0))
      .slice(0, 10);
  }

  /**
   * Handle node selection - also manages selecting nodes for the relation form
   */
  onNodeSelectedForRelation(node: D3Node | null): void {
    if (node && this.selectingNodeFor) {
      if (this.selectingNodeFor === 'source') {
        this.newRelation.sourceNode = node;
      } else if (this.selectingNodeFor === 'target') {
        this.newRelation.targetNode = node;
      }
      this.selectingNodeFor = null;
      this.snackBar.open('Node selected', '', { duration: 1000 });
    }
  }
}
