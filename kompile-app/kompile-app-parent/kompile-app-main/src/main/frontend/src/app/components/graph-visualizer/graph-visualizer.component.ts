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

import { Component, OnInit, OnDestroy, OnChanges, SimpleChanges, Input, ViewChild } from '@angular/core';
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
import { MatRadioModule } from '@angular/material/radio';
import { HttpClient } from '@angular/common/http';
import { Subject, Subscription, takeUntil, debounceTime, interval, filter, of } from 'rxjs';
import { catchError, switchMap } from 'rxjs/operators';
import { pauseWhenHidden } from '../../services/visibility.util';
import { ConfirmDialogComponent, ConfirmDialogData } from '../confirm-dialog/confirm-dialog.component';
import { BayesianPanelComponent } from './bayesian-panel.component';
import { MarkdownRendererComponent } from '../markdown-renderer/markdown-renderer.component';
import { TableRendererComponent } from '../table-renderer/table-renderer.component';
import { KbContextPanelComponent } from '../kb-context-panel/kb-context-panel.component';
import { SourceLinkingPanelComponent } from './source-linking-panel.component';
import { GraphOntologyPanelComponent } from '../graph-ontology-panel/graph-ontology-panel.component';
import { SourceCitationComponent } from '../source-citation/source-citation.component';
import { ReasoningTrailComponent } from '../reasoning-trail/reasoning-trail.component';

import { GraphCanvasComponent } from './graph-canvas.component';
import { GraphService, GraphBuildStatus, FactSheetGraphStatistics } from '../../services/graph.service';
import { SourceWeightService } from '../../services/source-weight.service';
import { AttributionService } from '../../services/attribution.service';
import { ProcessEngineService } from '../../services/process-engine.service';
import { KbGroundingService, StrengthBand, confidenceToStrengthBand, ReasoningTrailDto, UnifiedExplainRequest } from '../../services/kb-grounding.service';
import { AttributionResult, PredictionResult } from '../../models/attribution-models';
import { Citation } from '../../models/api-models';
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
  TemporalBounds,
  ReasoningLayers,
  NodeReasoningOverlay,
  EdgeReasoningOverlay,
  ReasoningLayerKind,
  ReasoningLayerVisualOverlay,
  OntologyOverlay,
  TypeHierarchyOverlay,
  InferredRelationOverlay
} from '../../models/graph-models';
import {
  FlatProcessReasoningStep,
  ProcessHybridActivityReasoning,
  ProcessHybridReasoning,
  ProcessReasoningTrace,
  ProcessSuggestionSummary,
  flattenProcessReasoningTrace,
  processHybridModeLabel,
  rankedProcessHybridActivities,
  sortProcessSuggestions
} from '../../models/process-reasoning-models';

const DEFAULT_NODE_TYPES: NodeLevel[] = [
  'SOURCE', 'DOCUMENT', 'SNIPPET', 'ENTITY', 'CUSTOM', 'TABLE', 'ATTACHMENT', 'IDENTIFIER', 'ALIAS'
];

const DEFAULT_EDGE_TYPES: EdgeType[] = [
  'HIERARCHICAL',
  'EMBEDDING_SIMILARITY',
  'SHARED_ENTITY',
  'USER_DEFINED',
  'CITATION',
  'TEMPORAL',
  'CROSS_SOURCE',
  'CONTAINS',
  'EXTRACTED_FROM',
  'AUTHORED_BY',
  'ADDRESSED_TO',
  'RESOLVES_TO',
  'ALIAS_OF',
  'HAS_OWNER',
  'OWNED_BY',
  'HAS_TABLE',
  'HEADER_OF',
  'DEPENDS_ON',
  'CROSS_SHEET_DEPENDS_ON',
  'CROSS_SHEET_LINK',
  'RANGE_INPUT',
  'HAS_COMMENT',
  'HAS_DATA_VALIDATION',
  'VERSION_OF'
];

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
    MatRadioModule,
    GraphCanvasComponent,
    ConfirmDialogComponent,
    BayesianPanelComponent,
    MarkdownRendererComponent,
    TableRendererComponent,
    KbContextPanelComponent,
    SourceLinkingPanelComponent,
    GraphOntologyPanelComponent,
    SourceCitationComponent,
    ReasoningTrailComponent
  ],
  template: `
    <div class="graph-visualizer">
      <!-- Canvas hint -->
      <div class="gv-hint">
        Interactive canvas — click nodes to inspect, drag to rearrange, use the toolbar to filter or search, and enable overlays to see confidence bands, provenance, or communities.
      </div>

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
          <button mat-icon-button
                  (click)="exportUnifiedGraph()"
                  [disabled]="exportingUnified"
                  matTooltip="Export full graph (.kgraph — all vectors, opinions, weights)">
            <mat-icon>{{exportingUnified ? 'hourglass_empty' : 'download'}}</mat-icon>
          </button>
          <button mat-icon-button
                  (click)="kgraphInput.click()"
                  [disabled]="importingUnified"
                  matTooltip="Import a .kgraph file into this graph">
            <mat-icon>{{importingUnified ? 'hourglass_empty' : 'upload'}}</mat-icon>
          </button>
          <input #kgraphInput type="file" accept=".kgraph" hidden (change)="importUnifiedGraph($event)">
          <button mat-icon-button
                  [color]="strengthOverlayEnabled ? 'accent' : ''"
                  (click)="toggleStrengthOverlay()"
                  matTooltip="Toggle Strength Band Overlay">
            <mat-icon>verified</mat-icon>
          </button>
          <button mat-icon-button
                  [color]="provenanceOverlayEnabled ? 'accent' : ''"
                  (click)="toggleProvenanceOverlay()"
                  matTooltip="Toggle Provenance Overlay (circle=observed, diamond=derived)">
            <mat-icon>account_tree</mat-icon>
          </button>
          <button mat-icon-button
                  [color]="communityOverlayEnabled ? 'accent' : ''"
                  (click)="toggleCommunityOverlay()"
                  [disabled]="communityLoading"
                  matTooltip="Toggle Community Detection Overlay">
            <mat-icon>{{communityLoading ? 'hourglass_empty' : 'bubble_chart'}}</mat-icon>
          </button>
          <button mat-icon-button
                  [color]="conformanceOverlayEnabled ? 'accent' : ''"
                  (click)="toggleConformanceOverlay()"
                  [disabled]="conformanceLoading"
                  matTooltip="Toggle Ontology Conformance Overlay (green=conformant, red=violation, grey=untagged)">
            <mat-icon>{{conformanceLoading ? 'hourglass_empty' : 'rule'}}</mat-icon>
          </button>
          <button mat-icon-button
                  [color]="reasoningLayerOverlayEnabled ? 'accent' : ''"
                  (click)="toggleReasoningLayerOverlay()"
                  [disabled]="reasoningLayersLoading || !factSheetId"
                  matTooltip="Toggle Reasoning Layers (ontology, PSL, MEBN, provenance, opinions, neural scores)">
            <mat-icon>{{reasoningLayersLoading ? 'hourglass_empty' : 'schema'}}</mat-icon>
          </button>
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
            #graphCanvas
            *ngIf="!loading"
            [data]="graphData"
            [forceConfig]="forceConfig"
            [linkMode]="linkMode"
            [showLegend]="true"
            [posteriorOverlay]="posteriorOverlay"
            [priorOverlay]="priorOverlay"
            [mebnMfragMap]="mebnMfragMap"
            [findingNodeMap]="findingNodeMap"
            [influenceOverlayActive]="influenceOverlayActive"
            [simTruthNodeMap]="simTruthNodeMap"
            [strengthOverlayEnabled]="strengthOverlayEnabled"
            [strengthBandMap]="strengthBandMap"
            [provenanceOverlayEnabled]="provenanceOverlayEnabled"
            [communityOverlayEnabled]="communityOverlayEnabled"
            [communityMap]="communityMap"
            [conformanceOverlayEnabled]="conformanceOverlayEnabled"
            [conformanceMap]="conformanceMap"
            [reasoningLayerOverlayEnabled]="reasoningLayerOverlayEnabled"
            [reasoningNodeLayerMap]="reasoningNodeLayerMap"
            [reasoningEdgeLayerMap]="reasoningEdgeLayerMap"
            [processEvidenceNodeIds]="processEvidenceNodeIds"
            [processEvidenceEdgeIds]="processEvidenceEdgeIds"
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
            <!-- KB Context Tab (FIRST) -->
            <mat-tab label="KB Context">
              <div class="panel-content">
                <app-kb-context-panel
                  [node]="selectedNode"
                  [factSheetId]="factSheetId"
                  [isActive]="selectedTabIndex === 0"
                  (onGround)="selectedTabIndex = 6">
                </app-kb-context-panel>
              </div>
            </mat-tab>
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
                  <mat-expansion-panel *ngIf="getSelectedNodeReasoning() as reasoning" class="reasoning-section">
                    <mat-expansion-panel-header>
                      <mat-panel-title>
                        <mat-icon class="section-icon">schema</mat-icon>
                        Reasoning Layers
                      </mat-panel-title>
                    </mat-expansion-panel-header>
                    <ng-container *ngTemplateOutlet="reasoningNodeDetails; context: {$implicit: reasoning}"></ng-container>
                  </mat-expansion-panel>
                  <!-- Rendered table for TABLE nodes — works for any graph backend -->
                  <div class="detail-row detail-table-view" *ngIf="isTableNode(selectedNode)">
                    <span class="label">Table:</span>
                    <app-table-renderer
                      [markdownContent]="getNodeTableMarkdown(selectedNode)"
                      [showExport]="true">
                    </app-table-renderer>
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

                  <!-- D2: Focal / Subgraph View -->
                  <mat-expansion-panel class="focal-section">
                    <mat-expansion-panel-header>
                      <mat-panel-title>
                        <mat-icon class="section-icon">center_focus_strong</mat-icon>
                        Focal View
                      </mat-panel-title>
                    </mat-expansion-panel-header>
                    <div class="focal-content">
                      <p class="hint">Build a bounded subgraph centred on this node</p>

                      <!-- Radius slider -->
                      <div class="focal-control">
                        <label>Radius (hops)</label>
                        <mat-slider min="1" max="5" step="1" discrete showTickMarks style="flex:1">
                          <input matSliderThumb [(ngModel)]="focalRadius">
                        </mat-slider>
                        <span>{{focalRadius}}</span>
                      </div>

                      <!-- Confidence floor slider -->
                      <div class="focal-control">
                        <label>Min. confidence</label>
                        <mat-slider min="0" max="1" step="0.05" discrete style="flex:1">
                          <input matSliderThumb [(ngModel)]="focalConfidenceFloor">
                        </mat-slider>
                        <span>{{focalConfidenceFloor | number:'1.2-2'}}</span>
                      </div>

                      <!-- Edge type chips -->
                      <label style="font-size:12px;color:var(--text-secondary,#697386);margin-bottom:4px;display:block">Relation types (empty = all)</label>
                      <div class="focal-chips">
                        <mat-checkbox *ngFor="let type of allEdgeTypes"
                          [checked]="focalEdgeTypes.includes(type)"
                          (change)="toggleFocalEdgeType(type)">
                          {{formatEdgeType(type)}}
                        </mat-checkbox>
                      </div>

                      <!-- Actions -->
                      <div class="focal-actions">
                        <button mat-raised-button color="primary"
                                (click)="buildFocalView()"
                                [disabled]="focalViewLoading">
                          <mat-icon>{{focalViewLoading ? 'hourglass_empty' : 'center_focus_strong'}}</mat-icon>
                          {{focalViewActive ? 'Refresh Focus' : 'Focus View'}}
                        </button>
                        <button mat-stroked-button *ngIf="focalViewActive"
                                (click)="exitFocalView()">
                          <mat-icon>zoom_out_map</mat-icon>
                          Exit Focus
                        </button>
                      </div>

                      <div *ngIf="focalViewActive" class="focal-status">
                        <mat-icon class="focal-active-icon">center_focus_strong</mat-icon>
                        Focal view active — {{graphData?.nodes?.length || 0}} nodes, {{graphData?.links?.length || 0}} edges
                      </div>
                    </div>
                  </mat-expansion-panel>

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
                      <div *ngIf="attributionError && !attributionLoading" class="attr-error">
                        <mat-icon class="attr-error-icon">error_outline</mat-icon>
                        <div class="attr-error-body">
                          <div class="attr-error-title">Attribution failed</div>
                          <div class="attr-error-message">{{attributionError}}</div>
                          <button mat-stroked-button color="primary" class="attr-error-retry"
                                  (click)="loadAttribution()">
                            <mat-icon>refresh</mat-icon> Try again
                          </button>
                        </div>
                        <button mat-icon-button (click)="attributionError = null" matTooltip="Dismiss">
                          <mat-icon>close</mat-icon>
                        </button>
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
                          <app-markdown-renderer [content]="attributionResult.synthesizedExplanation"></app-markdown-renderer>
                        </div>
                        <div *ngFor="let chain of attributionResult.chains; let i = index" class="chain-card">
                          <div class="chain-header">
                            <span class="chain-label">Chain {{i + 1}}</span>
                            <span class="chain-confidence" [style.color]="getConfidenceColor(chain.overallConfidence)">
                              {{(chain.overallConfidence * 100).toFixed(0)}}% {{chain.confidenceBand}}
                            </span>
                            <span class="chain-timestamp" *ngIf="chain.computedAt">{{chain.computedAt | slice:11:19}}</span>
                          </div>
                          <div *ngIf="chain.narrative" class="chain-narrative">
                            <app-markdown-renderer [content]="chain.narrative"></app-markdown-renderer>
                          </div>
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
                                <div *ngFor="let ev of hop.evidence" class="evidence-item">
                                  <span class="evidence-chip"
                                        [matTooltip]="ev.summary || ''">
                                    {{formatEvidenceType(ev.evidenceType)}} ({{(ev.strength * 100).toFixed(0)}}%)
                                  </span>
                                  <app-source-citation
                                    [citation]="toCitation(ev.metadata, ev.sourceReference)"
                                    [compact]="true">
                                  </app-source-citation>
                                </div>
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

                        <button mat-stroked-button (click)="attributionResult = null; explainTrail = null" class="attr-reset">
                          <mat-icon>refresh</mat-icon> Reset
                        </button>
                      </div>

                      <!-- Reasoning Trail — unified /api/explain (HYBRID), shown below causal attribution -->
                      <app-reasoning-trail
                        *ngIf="explainTrail || explainTrailLoading"
                        [trail]="explainTrail"
                        [loading]="explainTrailLoading"
                        mode="full">
                      </app-reasoning-trail>
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
                      <div *ngIf="predictionError && !predictionLoading" class="attr-error">
                        <mat-icon class="attr-error-icon">error_outline</mat-icon>
                        <div class="attr-error-body">
                          <div class="attr-error-title">Prediction failed</div>
                          <div class="attr-error-message">{{predictionError}}</div>
                          <button mat-stroked-button color="primary" class="attr-error-retry"
                                  (click)="loadPrediction()">
                            <mat-icon>refresh</mat-icon> Try again
                          </button>
                        </div>
                        <button mat-icon-button (click)="predictionError = null" matTooltip="Dismiss">
                          <mat-icon>close</mat-icon>
                        </button>
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
                          <app-markdown-renderer [content]="predictionResult.synthesizedForecast"></app-markdown-renderer>
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
                    <div class="relation-main-row">
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
                    <div class="relation-reasoning" *ngIf="getRelationReasoning(rel) as edgeReasoning">
                      <div class="reasoning-chip-row">
                        <span class="reasoning-chip" *ngIf="edgeReasoning.ontology">Ontology</span>
                        <span class="reasoning-chip" *ngIf="edgeReasoning.psl">PSL {{edgeReasoning.psl.truthValue | number:'1.2-2'}}</span>
                        <span class="reasoning-chip" *ngIf="edgeReasoning.mebn">MEBN {{edgeReasoning.mebn.posterior | number:'1.2-2'}}</span>
                        <span class="reasoning-chip" *ngIf="edgeReasoning.provenance">Provenance</span>
                        <span class="reasoning-chip" *ngIf="edgeReasoning.opinion">Opinion {{edgeReasoning.opinion.confidence | number:'1.2-2'}}</span>
                        <span class="reasoning-chip" *ngIf="edgeReasoning.neuralScores">Neural</span>
                      </div>
                      <ng-container *ngTemplateOutlet="reasoningNodeDetails; context: {$implicit: edgeReasoning}"></ng-container>
                    </div>
                  </div>
                </div>
                <div *ngIf="selectedNode && (!nodeRelations || nodeRelations.length === 0)" class="no-selection">
                  <mat-icon>link_off</mat-icon>
                  <p>No relations found for this node</p>
                </div>
              </div>
            </mat-tab>

            <!-- Reasoning Layers Tab -->
            <mat-tab label="Reasoning">
              <div class="panel-content reasoning-panel">
                <div class="reasoning-header">
                  <div>
                    <h4>Reasoning Layers</h4>
                    <p class="hint" *ngIf="factSheetId">Typed overlays from ontology, PSL, MEBN, provenance, opinion, and neural scoring metadata.</p>
                    <p class="hint" *ngIf="!factSheetId">Select a fact sheet to load reasoning layers.</p>
                  </div>
                  <button mat-icon-button
                          (click)="loadReasoningLayers(true)"
                          [disabled]="!factSheetId || reasoningLayersLoading"
                          matTooltip="Refresh reasoning layers">
                    <mat-icon>{{reasoningLayersLoading ? 'hourglass_empty' : 'refresh'}}</mat-icon>
                  </button>
                </div>

                <div class="attr-loading" *ngIf="reasoningLayersLoading">
                  <mat-spinner diameter="24"></mat-spinner>
                  <span>Loading reasoning layers...</span>
                </div>

                <div class="attr-error" *ngIf="reasoningLayersError && !reasoningLayersLoading">
                  <mat-icon class="attr-error-icon">error_outline</mat-icon>
                  <div class="attr-error-body">
                    <div class="attr-error-title">Reasoning layers unavailable</div>
                    <div class="attr-error-message">{{reasoningLayersError}}</div>
                  </div>
                </div>

                <ng-container *ngIf="reasoningLayers">
                  <div class="reasoning-actions">
                    <mat-checkbox
                      [checked]="reasoningLayerOverlayEnabled"
                      (change)="toggleReasoningLayerOverlay($event.checked)">
                      Show on canvas
                    </mat-checkbox>
                  </div>

                  <h4>Visible Layers</h4>
                  <div class="filter-chips reasoning-toggles">
                    <mat-checkbox
                      [checked]="reasoningLayerToggles.ontology"
                      (change)="setReasoningLayer('ontology', $event.checked)">
                      Ontology
                      <span class="type-count" *ngIf="reasoningLayers.statistics?.ontologyCount">({{reasoningLayers.statistics?.ontologyCount}})</span>
                    </mat-checkbox>
                    <mat-checkbox
                      [checked]="reasoningLayerToggles.psl"
                      (change)="setReasoningLayer('psl', $event.checked)">
                      PSL
                      <span class="type-count" *ngIf="reasoningLayers.statistics?.pslCount">({{reasoningLayers.statistics?.pslCount}})</span>
                    </mat-checkbox>
                    <mat-checkbox
                      [checked]="reasoningLayerToggles.mebn"
                      (change)="setReasoningLayer('mebn', $event.checked)">
                      MEBN
                      <span class="type-count" *ngIf="reasoningLayers.statistics?.mebnCount">({{reasoningLayers.statistics?.mebnCount}})</span>
                    </mat-checkbox>
                    <mat-checkbox
                      [checked]="reasoningLayerToggles.provenance"
                      (change)="setReasoningLayer('provenance', $event.checked)">
                      Provenance
                      <span class="type-count" *ngIf="reasoningLayers.statistics?.provenanceCount">({{reasoningLayers.statistics?.provenanceCount}})</span>
                    </mat-checkbox>
                    <mat-checkbox
                      [checked]="reasoningLayerToggles.opinion"
                      (change)="setReasoningLayer('opinion', $event.checked)">
                      Opinion
                      <span class="type-count" *ngIf="reasoningLayers.statistics?.opinionCount">({{reasoningLayers.statistics?.opinionCount}})</span>
                    </mat-checkbox>
                    <mat-checkbox
                      [checked]="reasoningLayerToggles.neural"
                      (change)="setReasoningLayer('neural', $event.checked)">
                      Neural Scores
                      <span class="type-count" *ngIf="reasoningLayers.statistics?.neuralScoreCount">({{reasoningLayers.statistics?.neuralScoreCount}})</span>
                    </mat-checkbox>
                  </div>

                  <h4>Summary</h4>
                  <div class="reasoning-summary">
                    <span class="reasoning-stat"><strong>{{reasoningLayers.statistics?.nodeCount || reasoningLayers.nodes.length}}</strong> nodes</span>
                    <span class="reasoning-stat"><strong>{{reasoningLayers.statistics?.edgeCount || reasoningLayers.edges.length}}</strong> edges</span>
                    <span class="reasoning-stat"><strong>{{reasoningViolationCount}}</strong> violations</span>
                  </div>

                  <h4>Selected Node</h4>
                  <ng-container *ngIf="getSelectedNodeReasoning() as reasoning; else noReasoningSelection">
                    <ng-container *ngTemplateOutlet="reasoningNodeDetails; context: {$implicit: reasoning}"></ng-container>
                  </ng-container>
                  <ng-template #noReasoningSelection>
                    <div class="no-selection compact">
                      <mat-icon>touch_app</mat-icon>
                      <p>Select a node with reasoning metadata</p>
                    </div>
                  </ng-template>
                </ng-container>
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
                    {{type}}<span class="type-count" *ngIf="nodeTypeCounts[type]"> ({{nodeTypeCounts[type] | number}})</span>
                  </mat-checkbox>
                </div>

                <h4>Edge Types</h4>
                <div class="edge-type-summary" *ngIf="allEdgeTypes.length > 0">
                  <span class="edge-type-chip" *ngFor="let type of allEdgeTypes">
                    {{formatEdgeType(type)}}<span class="type-count" *ngIf="edgeTypeCounts[type]"> ({{edgeTypeCounts[type] | number}})</span>
                  </span>
                </div>

                <h4>Depth</h4>
                <mat-slider min="1" max="5" step="1" discrete showTickMarks>
                  <input matSliderThumb [(ngModel)]="maxDepth" (ngModelChange)="onDepthChange()">
                </mat-slider>
                <span class="slider-label">{{maxDepth}} levels</span>

                <h4>Max Nodes</h4>
                <div class="max-nodes-control">
                  <mat-slider min="0" max="10000" step="500" discrete>
                    <input matSliderThumb [(ngModel)]="maxNodes" (ngModelChange)="onMaxNodesChange()">
                  </mat-slider>
                  <span class="slider-label">{{maxNodes === 0 ? 'All (unlimited)' : maxNodes + ' nodes'}}</span>
                </div>
                <p class="hint">Set to 0 for unlimited. Large graphs may be slow to render.</p>

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

                <!-- Phase-2: Strength Band Filter -->
                <h4>
                  <mat-icon class="section-icon-sm">verified</mat-icon>
                  Strength Band
                </h4>
                <div class="filter-chips">
                  <mat-checkbox
                    *ngFor="let band of allStrengthBands"
                    [checked]="strengthBandFilter.has(band)"
                    (change)="toggleStrengthBandFilter(band)">
                    {{ band | titlecase }}
                  </mat-checkbox>
                </div>

                <!-- Phase-2: Creation Time Window -->
                <h4>
                  <mat-icon class="section-icon-sm">schedule</mat-icon>
                  Creation Window
                </h4>
                <div class="temporal-filter-row">
                  <mat-form-field appearance="outline" style="width: 48%; margin-right: 4%;">
                    <mat-label>Created From</mat-label>
                    <input matInput type="date" [(ngModel)]="creationTimeFrom"
                           (ngModelChange)="onCreationTimeChange()">
                  </mat-form-field>
                  <mat-form-field appearance="outline" style="width: 48%;">
                    <mat-label>Created To</mat-label>
                    <input matInput type="date" [(ngModel)]="creationTimeTo"
                           (ngModelChange)="onCreationTimeChange()">
                  </mat-form-field>
                </div>

                <!-- Phase-2: Provenance Type Filter -->
                <h4>
                  <mat-icon class="section-icon-sm">account_tree</mat-icon>
                  Provenance Type
                </h4>
                <mat-radio-group [(ngModel)]="provenanceFilter" (ngModelChange)="onProvenanceFilterChange()">
                  <mat-radio-button value="ALL">All</mat-radio-button>
                  <mat-radio-button value="OBSERVED_ONLY">Observed only</mat-radio-button>
                  <mat-radio-button value="DERIVED_ONLY">Derived only</mat-radio-button>
                </mat-radio-group>

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
                <p class="hint">Per-source multipliers applied at retrieval time. A weight of 1.0 is neutral; higher values boost results from that source.</p>

                <!-- Preview Query -->
                <div class="weight-preview">
                  <mat-form-field appearance="outline" class="full-width">
                    <mat-label>Preview Query</mat-label>
                    <input matInput [(ngModel)]="previewQuery"
                           placeholder="Enter a query to see how weights rank sources"
                           (keyup.enter)="previewQuery && !previewLoading && previewWeights()">
                  </mat-form-field>
                  <button mat-raised-button color="primary"
                          (click)="previewWeights()"
                          [disabled]="!previewQuery || previewLoading">
                    <mat-spinner *ngIf="previewLoading" diameter="16" style="display:inline-block;margin-right:6px"></mat-spinner>
                    Preview
                  </button>
                </div>

                <!-- Preview Results -->
                <div *ngIf="weightPreview" class="weight-results">
                  <div class="weight-results-header">
                    <h5>Weighted Sources for "{{weightPreview.query}}"</h5>
                    <button mat-icon-button (click)="weightPreview = null" matTooltip="Clear preview">
                      <mat-icon style="font-size:16px">close</mat-icon>
                    </button>
                  </div>
                  <div *ngFor="let sw of weightPreview.sourceWeights" class="weight-item">
                    <div class="weight-item-left">
                      <span class="source-name">{{sw.sourceName || sw.sourceId}}</span>
                      <div class="weight-breakdown">
                        <span *ngIf="sw.sourceType" class="source-type-badge">{{sw.sourceType}}</span>
                        <span *ngIf="sw.relevance != null" class="rel-chip"
                              matTooltip="Semantic relevance of this source to your query">
                          rel {{sw.relevance * 100 | number:'1.0-0'}}%
                        </span>
                        <span class="wt-chip" matTooltip="Configured source weight">×wt {{sw.weight | number:'1.1-1'}}</span>
                      </div>
                    </div>
                    <div class="weight-item-right">
                      <div class="weight-bar-wrap">
                        <div class="weight-bar" [style.width.%]="((sw.score != null ? sw.score : sw.weight) / 3) * 100"></div>
                      </div>
                      <span class="source-weight" matTooltip="Final ranking score">{{(sw.score != null ? sw.score : sw.weight) | number:'1.2-2'}}</span>
                      <button mat-icon-button class="feedback-btn" matTooltip="This source was helpful"
                              (click)="submitSourceFeedback(sw.sourceId, true)">
                        <mat-icon style="font-size:16px;color:#22c55e">thumb_up</mat-icon>
                      </button>
                      <button mat-icon-button class="feedback-btn" matTooltip="This source was not helpful"
                              (click)="submitSourceFeedback(sw.sourceId, false)">
                        <mat-icon style="font-size:16px;color:#ef4444">thumb_down</mat-icon>
                      </button>
                    </div>
                  </div>
                  <p *ngIf="weightPreview.note" class="weight-note">
                    <mat-icon style="font-size:14px;vertical-align:middle;margin-right:4px">info_outline</mat-icon>
                    {{weightPreview.note}}
                  </p>
                </div>

                <!-- Configure Weights -->
                <mat-expansion-panel [expanded]="sourceWeights.length > 0">
                  <mat-expansion-panel-header>
                    <mat-panel-title>
                      Configure Weights
                      <span *ngIf="sourceWeights.length" class="badge-count">{{sourceWeights.length}}</span>
                    </mat-panel-title>
                    <mat-panel-description *ngIf="!weightsLoading && sourceWeights.length === 0">
                      No sources indexed yet
                    </mat-panel-description>
                  </mat-expansion-panel-header>

                  <div *ngIf="weightsLoading" class="weights-loading">
                    <mat-spinner diameter="24"></mat-spinner>
                    <span>Loading sources…</span>
                  </div>

                  <div *ngIf="!weightsLoading && sourceWeights.length === 0" class="weights-empty">
                    <mat-icon>source</mat-icon>
                    <p>No indexed sources found. Run a crawl first, then come back here to tune weights.</p>
                  </div>

                  <div *ngFor="let weight of sourceWeights" class="weight-config">
                    <div class="weight-source-info">
                      <span class="weight-source">{{weight.sourceName || weight.sourceNodeId}}</span>
                      <span *ngIf="weight.sourceType" class="source-type-badge">{{weight.sourceType}}</span>
                    </div>
                    <mat-slider min="0" max="3" step="0.1" class="weight-slider">
                      <input matSliderThumb [ngModel]="weight.baseWeight"
                             (ngModelChange)="updateWeight(weight.sourceNodeId, $event)">
                    </mat-slider>
                    <span class="weight-value">{{weight.baseWeight | number:'1.1-1'}}</span>
                  </div>

                  <div *ngIf="!weightsLoading && sourceWeights.length > 0" class="weights-actions">
                    <button mat-stroked-button (click)="loadSourceWeights()">
                      <mat-icon>refresh</mat-icon> Refresh
                    </button>
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
                  (mebnMfragMapChanged)="onMebnMfragMapChanged($event)"
                  (findingNodesChanged)="onFindingNodesChanged($event)">
                </app-bayesian-panel>
              </div>
            </mat-tab>
            <!-- Source Links Tab -->
            <mat-tab label="Source Links">
              <div class="panel-content">
                <app-source-linking-panel
                  [factSheetId]="factSheetId">
                </app-source-linking-panel>
              </div>
            </mat-tab>
            <!-- Ontology / OWL Reasoning Tab -->
            <mat-tab label="Ontology">
              <div class="panel-content">
                <app-graph-ontology-panel [factSheetId]="factSheetId">
                </app-graph-ontology-panel>
              </div>
            </mat-tab>

            <mat-tab label="Processes">
              <div class="panel-content process-panel">
                <div class="process-toolbar">
                  <div class="process-toolbar-title">
                    <mat-icon>account_tree</mat-icon>
                    <span>Ranked candidates</span>
                    <span class="process-count">{{ processCandidates.length }}</span>
                  </div>
                  <div class="process-toolbar-actions">
                    <button mat-icon-button (click)="loadProcessCandidates()"
                            [disabled]="processLoading || !factSheetId"
                            matTooltip="Refresh process candidates">
                      <mat-icon>refresh</mat-icon>
                    </button>
                    <button mat-icon-button (click)="runProcessDiscovery()"
                            [disabled]="processMining || !factSheetId"
                            matTooltip="Run graph process mining and reasoning">
                      <mat-icon>{{ processMining ? 'hourglass_empty' : 'play_arrow' }}</mat-icon>
                    </button>
                  </div>
                </div>

                <mat-progress-bar *ngIf="processLoading || processMining" mode="indeterminate"></mat-progress-bar>
                <div class="process-error" *ngIf="processError">{{ processError }}</div>
                <div class="process-empty" *ngIf="!processLoading && !processCandidates.length">
                  No process candidates
                </div>

                <div class="process-candidate-list" *ngIf="processCandidates.length">
                  <button type="button" class="process-candidate-row"
                          *ngFor="let candidate of processCandidates; trackBy: trackByProcessCandidate"
                          [class.selected]="selectedProcessCandidate?.id === candidate.id"
                          (click)="selectProcessCandidate(candidate)">
                    <span class="process-rank" matTooltip="Composite process rank">#{{ candidate.reasoningRank || '-' }}</span>
                    <span class="process-candidate-name">
                      <strong>{{ candidate.name }}</strong>
                      <small>
                        {{ formatProcessLabel(candidate.reasoningProjection || candidate.discoverySource) }}
                        <ng-container *ngIf="candidate.reasoningFamily"> / {{ formatProcessLabel(candidate.reasoningFamily) }}</ng-container>
                      </small>
                    </span>
                    <span class="process-score" matTooltip="Composite process confidence">{{ (processCandidateScore(candidate) * 100) | number:'1.0-0' }}%</span>
                  </button>
                </div>

                <div class="selected-process" *ngIf="selectedProcessCandidate as candidate">
                  <div class="process-metrics">
                    <div *ngIf="candidate.hybridScore != null"><span>Hybrid activation</span><strong>{{ (candidate.hybridScore * 100) | number:'1.0-1' }}%</strong></div>
                    <div *ngIf="candidate.entailmentScore != null"><span>Entailment</span><strong>{{ (candidate.entailmentScore * 100) | number:'1.0-1' }}%</strong></div>
                    <div *ngIf="candidate.processCaseCount != null"><span>Cases</span><strong>{{ candidate.processCaseCount }}</strong></div>
                    <div *ngIf="candidate.processActivityCount != null"><span>Activities</span><strong>{{ candidate.processActivityCount }}</strong></div>
                    <div *ngIf="candidate.directlyFollowsCount != null"><span>Direct follows</span><strong>{{ candidate.directlyFollowsCount }}</strong></div>
                    <div *ngIf="candidate.acceptedPrecedenceCount != null"><span>Accepted order</span><strong>{{ candidate.acceptedPrecedenceCount }}</strong></div>
                    <div *ngIf="candidate.entailedOnlyPrecedenceCount != null"><span>Entailed only</span><strong>{{ candidate.entailedOnlyPrecedenceCount }}</strong></div>
                    <div><span>Evidence</span><strong>{{ processEvidenceNodeIds.size }} nodes / {{ processEvidenceEdgeIds.size }} edges</strong></div>
                  </div>

                  <details class="process-hybrid" *ngIf="candidate.hybridReasoning as hybrid">
                    <summary>
                      <span>Activity interpretation</span>
                      <small>{{ processHybridMode(hybrid) }}</small>
                      <small>{{ hybrid.embeddedActivityCount }}/{{ hybrid.activityCount }} embedded</small>
                    </summary>
                    <div class="process-hybrid-overview">
                      <span>PSL <strong>{{ (hybrid.pslScore * 100) | number:'1.0-1' }}%</strong></span>
                      <span>Bayes <strong>{{ (hybrid.bayesianScore * 100) | number:'1.0-1' }}%</strong></span>
                      <span>Semantic <strong>{{ (hybrid.semanticScore * 100) | number:'1.0-1' }}%</strong></span>
                      <span>S/M <strong>{{ (hybrid.structuralWeight * 100) | number:'1.0-0' }}/{{ (hybrid.semanticWeight * 100) | number:'1.0-0' }}</strong></span>
                    </div>
                    <div class="process-hybrid-source" *ngIf="hybrid.embeddingSource">
                      <span>{{ formatProcessLabel(hybrid.embeddingSource) }}</span>
                      <span *ngIf="hybrid.embeddingModel">{{ hybrid.embeddingModel }}</span>
                      <span>{{ hybrid.directlyEmbeddedActivityCount || 0 }} direct</span>
                      <span *ngIf="hybrid.inferredEmbeddingActivityCount">{{ hybrid.inferredEmbeddingActivityCount }} resolved</span>
                      <span *ngIf="hybrid.contextualizedActivityCount">{{ hybrid.contextualizedActivityCount }} contextual</span>
                    </div>
                    <div class="process-hybrid-warning" *ngFor="let warning of hybrid.warnings">{{ warning }}</div>
                    <div class="process-hybrid-activities">
                      <div *ngFor="let activity of processHybridActivities(candidate); trackBy: trackProcessHybridActivity">
                        <span>{{ activity.activity }}<small *ngIf="activity.embedded">embedded</small></span>
                        <span>PSL {{ (activity.pslScore * 100) | number:'1.0-1' }}%</span>
                        <span>Bayes {{ (activity.bayesianScore * 100) | number:'1.0-1' }}%</span>
                        <strong>{{ (activity.score * 100) | number:'1.0-1' }}%</strong>
                      </div>
                    </div>
                  </details>

                  <button mat-stroked-button class="process-trace-button"
                          *ngIf="candidate.reasoningTraceId"
                          (click)="loadSelectedProcessTrace()"
                          [disabled]="processTraceLoading">
                    <mat-icon>account_tree</mat-icon>
                    {{ processTraceLoading ? 'Loading trace...' :
                       (selectedProcessTrace ? 'Hide reasoning trace' : 'Show reasoning trace') }}
                  </button>
                  <div class="process-error" *ngIf="processTraceError">{{ processTraceError }}</div>
                  <div class="process-trace" *ngIf="selectedProcessTrace as trace">
                    <div class="process-trace-summary">
                      <span>{{ trace.size }} steps</span><span>Depth {{ trace.depth }}</span>
                    </div>
                    <div class="process-trace-step"
                         *ngFor="let step of selectedProcessTraceSteps(); trackBy: trackProcessTraceStep"
                         [style.padding-left.px]="8 + step.depthLevel * 12">
                      <span class="process-trace-kind">{{ formatProcessLabel(step.kind) }}</span>
                      <span class="process-trace-body">
                        <span class="process-trace-conclusion">{{ step.conclusion }}</span>
                        <small *ngIf="step.operation">{{ step.operation }}</small>
                      </span>
                      <strong>{{ (step.confidence * 100) | number:'1.0-0' }}%</strong>
                    </div>
                  </div>
                </div>
              </div>
            </mat-tab>
          </mat-tab-group>
        </div>
      </div>

      <ng-template #reasoningNodeDetails let-reasoning>
        <div class="reasoning-detail-stack">
          <section class="reasoning-detail" *ngIf="reasoning.ontology">
            <div class="reasoning-detail-title">
              <mat-icon>rule</mat-icon>
              <span>Ontology</span>
              <span class="reasoning-status"
                    [class.ok]="reasoning.ontology.conformant === true"
                    [class.warn]="reasoning.ontology.conformant === false">
                {{formatConformance(reasoning.ontology.conformant)}}
              </span>
            </div>
            <div class="reasoning-kv" *ngIf="reasoning.ontology.declaredType">
              <span>Declared</span><strong>{{reasoning.ontology.declaredType}}</strong>
            </div>
            <div class="reasoning-chip-row" *ngIf="reasoning.ontology.inferredTypes?.length">
              <span class="reasoning-chip" *ngFor="let type of reasoning.ontology.inferredTypes">{{type}}</span>
            </div>
            <div class="reasoning-type-candidates" *ngIf="reasoning.ontology.typeCandidates?.length">
              <div class="reasoning-type-candidate" *ngFor="let candidate of reasoning.ontology.typeCandidates">
                <div>
                  <strong>{{candidate.type}}</strong>
                  <small *ngIf="candidate.source || candidate.basis">
                    {{candidate.source}}{{candidate.source && candidate.basis ? ' / ' : ''}}{{candidate.basis}}
                  </small>
                </div>
                <span *ngIf="candidate.confidence != null">{{candidate.confidence | number:'1.2-2'}}</span>
              </div>
            </div>
            <div class="reasoning-inferred-list" *ngIf="reasoning.ontology.typeHierarchy?.length">
              <div class="reasoning-inferred-row hierarchy" *ngFor="let hierarchy of reasoning.ontology.typeHierarchy">
                <div>
                  <strong>{{formatTypeHierarchy(hierarchy)}}</strong>
                  <small *ngIf="formatTypeHierarchyBasis(hierarchy)">{{formatTypeHierarchyBasis(hierarchy)}}</small>
                </div>
                <span *ngIf="hierarchy.confidence != null">{{hierarchy.confidence | number:'1.2-2'}}</span>
              </div>
            </div>
            <div class="reasoning-inferred-list" *ngIf="reasoning.ontology.inferredRelations?.length">
              <div class="reasoning-inferred-row" *ngFor="let relation of reasoning.ontology.inferredRelations">
                <div>
                  <strong>{{formatInferredRelation(relation)}}</strong>
                  <small *ngIf="formatInferredRelationBasis(relation)">{{formatInferredRelationBasis(relation)}}</small>
                </div>
                <span *ngIf="relation.confidence != null">{{relation.confidence | number:'1.2-2'}}</span>
              </div>
            </div>
            <div class="reasoning-violations" *ngIf="reasoning.ontology.violations?.length">
              <div *ngFor="let violation of reasoning.ontology.violations">{{violation}}</div>
            </div>
          </section>

          <section class="reasoning-detail" *ngIf="reasoning.psl">
            <div class="reasoning-detail-title">
              <mat-icon>functions</mat-icon>
              <span>PSL</span>
              <span class="reasoning-score" *ngIf="reasoning.psl.truthValue != null">{{reasoning.psl.truthValue | number:'1.2-2'}}</span>
            </div>
            <div class="reasoning-kv" *ngIf="reasoning.psl.ruleId">
              <span>Rule</span><strong>{{reasoning.psl.ruleId}}</strong>
            </div>
            <p class="reasoning-rule" *ngIf="reasoning.psl.ruleText">{{reasoning.psl.ruleText}}</p>
            <div class="reasoning-kv" *ngIf="reasoning.psl.incompatibility != null">
              <span>Incompatibility</span><strong>{{reasoning.psl.incompatibility | number:'1.3-3'}}</strong>
            </div>
            <div class="reasoning-chip-row" *ngIf="reasoning.psl.bindings?.length">
              <span class="reasoning-chip" *ngFor="let binding of reasoning.psl.bindings">{{binding}}</span>
            </div>
          </section>

          <section class="reasoning-detail" *ngIf="reasoning.mebn">
            <div class="reasoning-detail-title">
              <mat-icon>device_hub</mat-icon>
              <span>MEBN</span>
              <span class="reasoning-score" *ngIf="reasoning.mebn.posterior != null">{{reasoning.mebn.posterior | number:'1.2-2'}}</span>
            </div>
            <div class="reasoning-kv" *ngIf="reasoning.mebn.mfrag">
              <span>MFrag</span><strong>{{reasoning.mebn.mfrag}}</strong>
            </div>
            <div class="reasoning-kv" *ngIf="reasoning.mebn.residentVariable">
              <span>Resident</span><strong>{{reasoning.mebn.residentVariable}}</strong>
            </div>
            <div class="reasoning-kv" *ngIf="reasoning.mebn.state">
              <span>State</span><strong>{{reasoning.mebn.state}}</strong>
            </div>
            <div class="reasoning-kv" *ngIf="reasoning.mebn.prior != null">
              <span>Prior</span><strong>{{reasoning.mebn.prior | number:'1.2-2'}}</strong>
            </div>
            <div class="reasoning-chip-row" *ngIf="reasoning.mebn.findings?.length">
              <span class="reasoning-chip warning" *ngFor="let finding of reasoning.mebn.findings">{{finding}}</span>
            </div>
          </section>

          <section class="reasoning-detail" *ngIf="reasoning.opinion">
            <div class="reasoning-detail-title">
              <mat-icon>verified</mat-icon>
              <span>Opinion</span>
              <span class="reasoning-score" *ngIf="reasoning.opinion.confidence != null">{{reasoning.opinion.confidence | number:'1.2-2'}}</span>
            </div>
            <div class="reasoning-bars">
              <div *ngIf="reasoning.opinion.belief != null"><span>Belief</span><strong>{{reasoning.opinion.belief | number:'1.2-2'}}</strong></div>
              <div *ngIf="reasoning.opinion.disbelief != null"><span>Disbelief</span><strong>{{reasoning.opinion.disbelief | number:'1.2-2'}}</strong></div>
              <div *ngIf="reasoning.opinion.uncertainty != null"><span>Uncertainty</span><strong>{{reasoning.opinion.uncertainty | number:'1.2-2'}}</strong></div>
            </div>
            <p class="reasoning-rule" *ngIf="reasoning.opinion.basis">{{reasoning.opinion.basis}}</p>
          </section>

          <section class="reasoning-detail" *ngIf="reasoning.neuralScores">
            <div class="reasoning-detail-title">
              <mat-icon>memory</mat-icon>
              <span>Neural Scores</span>
              <span class="reasoning-status" *ngIf="reasoning.neuralScores.embeddingAlgorithm">{{reasoning.neuralScores.embeddingAlgorithm}}</span>
            </div>
            <div class="reasoning-kv" *ngIf="reasoning.neuralScores.embeddingVersion != null">
              <span>Version</span><strong>{{reasoning.neuralScores.embeddingVersion}}</strong>
            </div>
            <div class="reasoning-bars" *ngIf="reasoning.neuralScores.scores">
              <div *ngFor="let score of objectEntries(reasoning.neuralScores.scores)">
                <span>{{score.key}}</span><strong>{{score.value | number:'1.3-3'}}</strong>
              </div>
            </div>
          </section>

          <section class="reasoning-detail" *ngIf="reasoning.provenance">
            <div class="reasoning-detail-title">
              <mat-icon>account_tree</mat-icon>
              <span>Provenance</span>
            </div>
            <pre class="metadata compact-metadata">{{reasoning.provenance.details | json}}</pre>
          </section>
        </div>
      </ng-template>

      <!-- Statistics Bar -->
      <div class="stats-bar" *ngIf="graphData">
        <span class="stat">
          <mat-icon>scatter_plot</mat-icon>
          <ng-container *ngIf="totalAvailableNodes !== null && totalAvailableNodes > graphData.nodes.length; else allNodes">
            showing {{graphData.nodes.length | number}} of {{totalAvailableNodes | number}} nodes
          </ng-container>
          <ng-template #allNodes>{{graphData.nodes.length | number}} nodes</ng-template>
        </span>
        <span class="stat">
          <mat-icon>timeline</mat-icon>
          <ng-container *ngIf="totalAvailableEdges !== null && totalAvailableEdges > graphData.links.length; else allEdges">
            showing {{graphData.links.length | number}} of {{totalAvailableEdges | number}} edges
          </ng-container>
          <ng-template #allEdges>{{graphData.links.length | number}} edges</ng-template>
        </span>
        <span class="stat stat-bounded-warn" *ngIf="totalAvailableNodes !== null && totalAvailableNodes > graphData.nodes.length">
          <mat-icon>info_outline</mat-icon>
          Graph is bounded — increase Max Nodes in Filter tab to see more
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

    .gv-hint {
      padding: 6px 16px;
      font-size: 12px;
      color: var(--text-secondary, #697386);
      background: var(--bg-surface, #fff);
      border-bottom: 1px solid var(--border-color, #e3e8ee);
      flex-shrink: 0;
      line-height: 1.4;
    }

    .fact-sheet-bar {
      display: flex;
      justify-content: space-between;
      align-items: center;
      padding: 12px 20px;
      background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
      border-bottom: 1px solid var(--border-color, #e3e8ee);
      flex-shrink: 0;
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
      flex-wrap: wrap;
      gap: 10px;
      min-width: 0;
      padding: 12px 20px;
      background: var(--bg-surface, #ffffff);
      border-bottom: 1px solid var(--border-color, #e3e8ee);
      box-shadow: var(--shadow-sm, 0 1px 2px 0 rgba(0, 0, 0, 0.05));
      flex-shrink: 0;
    }

    .toolbar-left, .toolbar-right {
      display: flex;
      gap: 10px;
      align-items: center;
      flex-wrap: wrap;
      min-width: 0;
      max-width: 100%;
    }

    .toolbar-center {
      flex: 1 1 220px;
      min-width: 160px;
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
      min-height: 0;
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
      background: var(--bg-body, #f1f5f9);
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

    .no-selection.compact {
      padding: 20px;
    }

    .no-selection.compact mat-icon {
      font-size: 28px;
      width: 28px;
      height: 28px;
      margin-bottom: 8px;
    }

    .filter-chips {
      display: flex;
      flex-direction: column;
      gap: 10px;
    }

    .filter-chips mat-checkbox {
      font-size: 13px;
    }

    .edge-type-summary {
      display: flex;
      flex-wrap: wrap;
      gap: 6px;
    }

    .edge-type-chip {
      display: inline-flex;
      align-items: center;
      gap: 3px;
      padding: 3px 8px;
      border: 1px solid var(--border-color, #e3e8ee);
      border-radius: 4px;
      background: var(--bg-body, #f8fafc);
      color: var(--text-secondary, #697386);
      font-size: 12px;
      line-height: 18px;
    }

    .type-count {
      color: var(--text-tertiary, #8792a2);
      font-size: 12px;
      font-weight: 500;
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

    .weight-results-header {
      display: flex;
      justify-content: space-between;
      align-items: center;
      margin-bottom: 10px;
    }

    .weight-results-header h5 {
      margin: 0;
      color: var(--text-secondary, #697386);
      font-size: 12px;
      font-weight: 600;
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
      align-items: center;
      padding: 8px 12px;
      background: var(--bg-surface, #f8fafc);
      border-radius: 8px;
      margin-bottom: 6px;
      border: 1px solid var(--border-color, #e3e8ee);
      gap: 8px;
    }

    .weight-item-left {
      display: flex;
      flex-direction: column;
      gap: 2px;
      flex: 1;
      min-width: 0;
    }

    .weight-item-right {
      display: flex;
      align-items: center;
      gap: 4px;
      flex-shrink: 0;
    }

    .weight-bar-wrap {
      width: 60px;
      height: 6px;
      background: var(--border-color, #e3e8ee);
      border-radius: 3px;
      overflow: hidden;
    }

    .weight-bar {
      height: 100%;
      background: #22c55e;
      border-radius: 3px;
      transition: width 0.3s;
    }

    .source-type-badge {
      display: inline-block;
      padding: 1px 6px;
      background: var(--bg-surface, #f1f5f9);
      border: 1px solid var(--border-color, #e3e8ee);
      border-radius: 4px;
      font-size: 10px;
      color: var(--text-secondary, #697386);
      text-transform: uppercase;
      letter-spacing: 0.04em;
    }

    .weight-breakdown {
      display: flex;
      align-items: center;
      flex-wrap: wrap;
      gap: 4px;
    }

    .rel-chip {
      display: inline-block;
      padding: 1px 6px;
      background: rgba(59, 130, 246, 0.12);
      border: 1px solid rgba(59, 130, 246, 0.35);
      border-radius: 4px;
      font-size: 10px;
      color: #2563eb;
      white-space: nowrap;
    }

    .wt-chip {
      display: inline-block;
      padding: 1px 6px;
      border-radius: 4px;
      font-size: 10px;
      color: var(--text-secondary, #697386);
      white-space: nowrap;
    }

    .feedback-btn {
      width: 28px;
      height: 28px;
      line-height: 28px;
    }

    .weight-note {
      margin-top: 10px;
      font-size: 11px;
      color: var(--text-secondary, #697386);
      display: flex;
      align-items: flex-start;
      gap: 4px;
    }

    .source-name {
      color: var(--text-primary, #1a1f36);
      font-size: 13px;
      white-space: nowrap;
      overflow: hidden;
      text-overflow: ellipsis;
    }

    .source-weight {
      color: #22c55e;
      font-weight: 600;
      font-size: 13px;
      min-width: 34px;
      text-align: right;
    }

    .weight-config {
      display: flex;
      align-items: center;
      gap: 8px;
      margin-bottom: 10px;
    }

    .weight-source-info {
      display: flex;
      flex-direction: column;
      gap: 2px;
      flex: 1;
      min-width: 0;
    }

    .weight-source {
      flex: 1;
      font-size: 13px;
      color: var(--text-primary, #1a1f36);
      white-space: nowrap;
      overflow: hidden;
      text-overflow: ellipsis;
    }

    .weight-slider {
      flex: 1;
    }

    .weight-value {
      width: 36px;
      text-align: right;
      font-weight: 600;
      color: var(--text-secondary, #697386);
    }

    .weights-loading {
      display: flex;
      align-items: center;
      gap: 10px;
      padding: 16px 0;
      color: var(--text-secondary, #697386);
      font-size: 13px;
    }

    .weights-empty {
      display: flex;
      flex-direction: column;
      align-items: center;
      gap: 8px;
      padding: 24px 16px;
      text-align: center;
      color: var(--text-secondary, #697386);
    }

    .weights-empty mat-icon {
      font-size: 36px;
      width: 36px;
      height: 36px;
      opacity: 0.4;
    }

    .weights-empty p {
      font-size: 12px;
      margin: 0;
    }

    .weights-actions {
      margin-top: 12px;
      display: flex;
      gap: 8px;
    }

    .badge-count {
      display: inline-flex;
      align-items: center;
      justify-content: center;
      min-width: 18px;
      height: 18px;
      background: var(--primary-color, #635bff);
      color: #fff;
      border-radius: 9px;
      font-size: 11px;
      font-weight: 600;
      padding: 0 5px;
      margin-left: 6px;
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
      flex-shrink: 0;
      flex-wrap: wrap;
      align-items: center;
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

    .stat-bounded-warn {
      color: var(--warn, #f59e0b);
      font-size: 12px;
    }

    .stat-bounded-warn mat-icon {
      color: var(--warn, #f59e0b);
    }

    .max-nodes-control {
      display: flex;
      align-items: center;
      gap: 8px;
    }

    .max-nodes-control mat-slider {
      flex: 1;
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
      background: var(--bg-body, #f8fafc);
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
      background: var(--bg-surface, #ffffff);
      border: 1px solid var(--border-color, #e3e8ee);
      border-radius: 8px;
      cursor: pointer;
      transition: all 0.2s ease;
    }

    .node-selector:hover {
      border-color: #667eea;
      background: var(--bg-body, #f0f4ff);
    }

    .node-selector.selected {
      border-color: #667eea;
      background: var(--bg-body, #eef2ff);
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
      background: var(--bg-surface, #ffffff);
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
      flex-direction: column;
      align-items: center;
      gap: 12px;
      padding: 12px;
      background: var(--bg-surface, #ffffff);
      border: 1px solid var(--border-color, #e3e8ee);
      border-radius: 8px;
      margin-bottom: 8px;
      transition: all 0.2s ease;
    }

    .relation-item:hover {
      box-shadow: var(--shadow-sm, 0 1px 2px 0 rgba(0, 0, 0, 0.05));
      border-color: #d1d5db;
    }

    .relation-main-row {
      width: 100%;
      display: flex;
      align-items: center;
      gap: 12px;
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

    .relation-type-badge.hierarchical { background: var(--bg-body, #f3f4f6); color: var(--text-secondary, #6b7280); }
    .relation-type-badge.embedding_similarity { background: rgba(34,197,94,0.15); color: #16a34a; }
    .relation-type-badge.shared_entity { background: rgba(168,85,247,0.15); color: #a855f7; }
    .relation-type-badge.user_defined { background: rgba(59,130,246,0.15); color: #3b82f6; }
    .relation-type-badge.citation { background: rgba(249,115,22,0.15); color: #f97316; }
    .relation-type-badge.temporal { background: rgba(234,179,8,0.15); color: #ca8a04; }
    .relation-type-badge.cross_source { background: rgba(6,182,212,0.15); color: #0891b2; }

    .relation-weight {
      font-size: 12px;
      color: var(--text-secondary, #697386);
      font-weight: 500;
    }

    .relation-reasoning {
      width: 100%;
      border-top: 1px solid var(--border-color, #e3e8ee);
      padding-top: 8px;
    }

    .reasoning-section {
      margin: 10px 0 14px;
    }

    .reasoning-panel .reasoning-header {
      display: flex;
      align-items: flex-start;
      justify-content: space-between;
      gap: 12px;
    }

    .reasoning-panel .reasoning-header h4 {
      margin-bottom: 4px;
    }

    .reasoning-actions {
      margin: 8px 0 14px;
    }

    .reasoning-toggles {
      margin-bottom: 12px;
    }

    .reasoning-summary {
      display: flex;
      gap: 8px;
      flex-wrap: wrap;
      margin-bottom: 12px;
    }

    .reasoning-stat {
      display: inline-flex;
      align-items: center;
      gap: 4px;
      padding: 4px 8px;
      border: 1px solid var(--border-color, #e3e8ee);
      border-radius: 6px;
      background: var(--bg-body, #f8fafc);
      font-size: 11px;
      color: var(--text-secondary, #697386);
    }

    .reasoning-detail-stack {
      display: flex;
      flex-direction: column;
      gap: 8px;
    }

    .reasoning-detail {
      border: 1px solid var(--border-color, #e3e8ee);
      border-left: 3px solid #667eea;
      border-radius: 6px;
      padding: 10px;
      background: var(--bg-surface, #ffffff);
    }

    .reasoning-detail-title {
      display: flex;
      align-items: center;
      gap: 6px;
      margin-bottom: 8px;
      font-size: 12px;
      font-weight: 600;
      color: var(--text-primary, #1a1f36);
    }

    .reasoning-detail-title mat-icon {
      font-size: 16px;
      width: 16px;
      height: 16px;
      color: #667eea;
    }

    .reasoning-status,
    .reasoning-score {
      margin-left: auto;
      padding: 2px 6px;
      border-radius: 4px;
      background: var(--bg-body, #f1f5f9);
      color: var(--text-secondary, #697386);
      font-size: 10px;
      font-weight: 600;
      text-transform: uppercase;
    }

    .reasoning-status.ok {
      background: rgba(34, 197, 94, 0.12);
      color: #16a34a;
    }

    .reasoning-status.warn {
      background: rgba(239, 68, 68, 0.12);
      color: #dc2626;
    }

    .reasoning-kv,
    .reasoning-bars div {
      display: flex;
      justify-content: space-between;
      gap: 10px;
      font-size: 11px;
      margin-bottom: 4px;
    }

    .reasoning-kv span,
    .reasoning-bars span {
      color: var(--text-tertiary, #8792a2);
    }

    .reasoning-kv strong,
    .reasoning-bars strong {
      color: var(--text-primary, #1a1f36);
      font-family: var(--font-family-monospace, 'JetBrains Mono', monospace);
      text-align: right;
      word-break: break-word;
    }

    .reasoning-chip-row {
      display: flex;
      flex-wrap: wrap;
      gap: 4px;
      margin-top: 6px;
    }

    .reasoning-chip {
      display: inline-flex;
      align-items: center;
      padding: 2px 6px;
      border-radius: 4px;
      background: rgba(99, 102, 241, 0.12);
      color: #4f46e5;
      font-size: 10px;
      font-weight: 500;
    }

    .reasoning-chip.warning {
      background: rgba(245, 158, 11, 0.14);
      color: #d97706;
    }

    .reasoning-type-candidates {
      display: flex;
      flex-direction: column;
      gap: 4px;
      margin-top: 6px;
    }

    .reasoning-type-candidate {
      display: flex;
      justify-content: space-between;
      gap: 10px;
      padding: 5px 6px;
      border-radius: 4px;
      background: rgba(15, 118, 110, 0.08);
      font-size: 11px;
    }

    .reasoning-type-candidate div {
      min-width: 0;
    }

    .reasoning-type-candidate strong {
      display: block;
      color: var(--text-primary, #1a1f36);
      word-break: break-word;
    }

    .reasoning-type-candidate small {
      display: block;
      margin-top: 2px;
      color: var(--text-tertiary, #8792a2);
      word-break: break-word;
    }

    .reasoning-type-candidate span {
      flex: 0 0 auto;
      color: #0f766e;
      font-family: var(--font-family-monospace, 'JetBrains Mono', monospace);
      font-weight: 600;
    }

    .reasoning-inferred-list {
      display: flex;
      flex-direction: column;
      gap: 4px;
      margin-top: 6px;
    }

    .reasoning-inferred-row {
      display: flex;
      justify-content: space-between;
      gap: 10px;
      padding: 5px 6px;
      border-radius: 4px;
      background: rgba(124, 58, 237, 0.08);
      border-left: 2px solid #7c3aed;
      font-size: 11px;
    }

    .reasoning-inferred-row.hierarchy {
      background: rgba(20, 184, 166, 0.08);
      border-left-color: #14b8a6;
    }

    .reasoning-inferred-row div {
      min-width: 0;
    }

    .reasoning-inferred-row strong {
      display: block;
      color: var(--text-primary, #1a1f36);
      word-break: break-word;
    }

    .reasoning-inferred-row small {
      display: block;
      margin-top: 2px;
      color: var(--text-tertiary, #8792a2);
      word-break: break-word;
    }

    .reasoning-inferred-row span {
      flex: 0 0 auto;
      color: #7c3aed;
      font-family: var(--font-family-monospace, 'JetBrains Mono', monospace);
      font-weight: 600;
    }

    .reasoning-inferred-row.hierarchy span {
      color: #0f766e;
    }

    .reasoning-rule {
      margin: 6px 0 0;
      font-size: 11px;
      color: var(--text-secondary, #697386);
      line-height: 1.4;
      word-break: break-word;
    }

    .reasoning-violations {
      margin-top: 6px;
      padding: 6px 8px;
      border-radius: 4px;
      background: rgba(239, 68, 68, 0.08);
      color: #dc2626;
      font-size: 11px;
      line-height: 1.4;
    }

    .compact-metadata {
      max-height: 120px;
      margin: 0;
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

    .attr-error {
      display: flex;
      align-items: flex-start;
      gap: 8px;
      margin: 8px 0;
      padding: 10px 12px;
      border: 1px solid var(--warn-border, #f3c2c2);
      border-left: 3px solid var(--warn, #d32f2f);
      border-radius: 4px;
      background: var(--warn-bg, #fdecea);
      font-size: 12px;
    }

    .attr-error-icon {
      color: var(--warn, #d32f2f);
      flex-shrink: 0;
    }

    .attr-error-body {
      flex: 1;
      min-width: 0;
    }

    .attr-error-title {
      font-weight: 600;
      color: var(--warn, #d32f2f);
      margin-bottom: 2px;
    }

    .attr-error-message {
      color: var(--text-primary, #1a2027);
      word-break: break-word;
      white-space: pre-wrap;
    }

    .attr-error-retry {
      margin-top: 8px;
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
      background: var(--bg-body, #f8fafc);
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
      background: var(--bg-body, #f1f5f9);
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
      background: rgba(99,102,241,0.12);
      border-radius: 4px;
      color: #818cf8;
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
      background: rgba(102, 126, 234, 0.08);
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

    /* D2 Focal View */
    .focal-section {
      margin-top: 16px;
    }

    .focal-content {
      padding: 8px 0;
    }

    .focal-control {
      display: flex;
      align-items: center;
      gap: 10px;
      margin-bottom: 12px;
    }

    .focal-control label {
      width: 110px;
      font-size: 12px;
      color: var(--text-secondary, #697386);
      flex-shrink: 0;
    }

    .focal-control span:last-child {
      width: 40px;
      text-align: right;
      font-weight: 500;
      font-size: 12px;
      color: var(--text-primary, #1a1f36);
    }

    .focal-chips {
      display: flex;
      flex-direction: column;
      gap: 6px;
      margin-bottom: 16px;
      max-height: 180px;
      overflow-y: auto;
    }

    .focal-chips mat-checkbox {
      font-size: 12px;
    }

    .focal-actions {
      display: flex;
      gap: 8px;
      flex-wrap: wrap;
    }

    .focal-status {
      display: flex;
      align-items: center;
      gap: 6px;
      margin-top: 10px;
      font-size: 11px;
      color: #667eea;
      font-weight: 500;
    }

    .focal-active-icon {
      font-size: 14px;
      width: 14px;
      height: 14px;
      color: #667eea;
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
      background: var(--bg-body, #f1f5f9);
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
      background: var(--text-primary, #1a1f36);
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

    .process-panel { min-width: 0; }
    .process-toolbar {
      display: flex; align-items: center; justify-content: space-between;
      min-height: 42px; border-bottom: 1px solid var(--border-color, #e3e8ee);
    }
    .process-toolbar-title, .process-toolbar-actions { display: flex; align-items: center; gap: 6px; }
    .process-toolbar-title { min-width: 0; font-size: 12px; font-weight: 600; color: var(--text-primary, #1a1f36); }
    .process-toolbar-title mat-icon { color: #00838f; font-size: 18px; width: 18px; height: 18px; }
    .process-count {
      min-width: 20px; padding: 1px 5px; text-align: center; font-size: 10px;
      color: #006064; background: #e0f7fa; border-radius: 8px;
    }
    .process-toolbar-actions button { width: 48px; height: 48px; padding: 12px; flex: 0 0 48px; }
    .process-toolbar-actions mat-icon { font-size: 18px; width: 18px; height: 18px; }
    .process-error { padding: 8px 0; color: #c62828; font-size: 11px; overflow-wrap: anywhere; }
    .process-empty { padding: 28px 4px; color: var(--text-secondary, #697386); text-align: center; font-size: 12px; }
    .process-candidate-list { border-bottom: 1px solid var(--border-color, #e3e8ee); }
    .process-candidate-row {
      display: grid; grid-template-columns: 30px minmax(0, 1fr) auto;
      align-items: center; gap: 7px; width: 100%; min-height: 50px;
      padding: 7px 4px; color: inherit; text-align: left; font: inherit;
      background: transparent; border: 0; border-bottom: 1px solid var(--border-color, #e3e8ee);
      cursor: pointer;
    }
    .process-candidate-row:last-child { border-bottom: 0; }
    .process-candidate-row:hover { background: var(--bg-hover, #f8fafc); }
    .process-candidate-row.selected {
      background: #eefbfc; box-shadow: inset 3px 0 0 #00838f;
    }
    .process-candidate-row:focus-visible { outline: 2px solid #00838f; outline-offset: -2px; }
    .process-rank {
      color: #00838f; font-size: 11px; font-weight: 700; text-align: center;
    }
    .process-candidate-name { display: flex; flex-direction: column; min-width: 0; gap: 2px; }
    .process-candidate-name strong {
      color: var(--text-primary, #1a1f36); font-size: 11px; font-weight: 600;
      overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
    }
    .process-candidate-name small {
      color: var(--text-secondary, #697386); font-size: 9px;
      overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
    }
    .process-score { color: #006064; font-size: 11px; font-weight: 700; font-family: monospace; }
    .selected-process { padding-top: 10px; }
    .process-metrics {
      display: grid; grid-template-columns: repeat(2, minmax(0, 1fr));
      gap: 1px; background: var(--border-color, #e3e8ee);
      border-top: 1px solid var(--border-color, #e3e8ee);
      border-bottom: 1px solid var(--border-color, #e3e8ee);
    }
    .process-metrics > div {
      display: flex; flex-direction: column; gap: 2px; min-width: 0;
      padding: 7px; background: var(--bg-surface, #fff);
    }
    .process-metrics span { color: var(--text-secondary, #697386); font-size: 9px; }
    .process-metrics strong { color: var(--text-primary, #1a1f36); font-size: 11px; overflow-wrap: anywhere; }
    .process-hybrid { margin-top: 8px; border-bottom: 1px solid var(--border-color, #e3e8ee); }
    .process-hybrid summary {
      display: flex; align-items: center; flex-wrap: wrap; gap: 6px; cursor: pointer;
      padding: 7px 4px; color: var(--text-primary, #1a1f36); font-size: 10px; font-weight: 600;
    }
    .process-hybrid summary small {
      color: #006064; border: 1px solid #b2dfdb; border-radius: 3px;
      padding: 1px 4px; font-size: 8px; font-weight: 500;
    }
    .process-hybrid-overview {
      display: grid; grid-template-columns: repeat(2, minmax(0, 1fr));
      gap: 1px; background: var(--border-color, #e3e8ee);
    }
    .process-hybrid-overview > span {
      display: flex; justify-content: space-between; gap: 5px; min-width: 0;
      padding: 5px 7px; background: var(--bg-surface, #fff);
      color: var(--text-secondary, #697386); font-size: 9px;
    }
    .process-hybrid-overview strong { color: var(--text-primary, #1a1f36); }
    .process-hybrid-source { display: flex; flex-wrap: wrap; gap: 3px 8px; padding: 5px 4px; color: var(--text-secondary, #697386); font-size: 8px; overflow-wrap: anywhere; }
    .process-hybrid-source span:first-child { color: #006064; font-weight: 600; }
    .process-hybrid-warning { padding: 5px 4px; color: #b45309; font-size: 8px; overflow-wrap: anywhere; }
    .process-hybrid-activities { max-height: 220px; overflow-y: auto; }
    .process-hybrid-activities > div {
      display: grid; grid-template-columns: minmax(0, 1fr) auto auto auto;
      align-items: center; gap: 5px; padding: 5px 4px;
      border-top: 1px solid var(--border-color, #e3e8ee); font-size: 8px;
    }
    .process-hybrid-activities > div > span:first-child {
      display: flex; flex-wrap: wrap; align-items: center; gap: 4px; min-width: 0;
      color: var(--text-primary, #1a1f36); overflow-wrap: anywhere;
    }
    .process-hybrid-activities small { color: #00796b; font-size: 7px; text-transform: uppercase; }
    .process-hybrid-activities > div > span:not(:first-child) { color: var(--text-secondary, #697386); white-space: nowrap; }
    .process-hybrid-activities strong { color: #00838f; white-space: nowrap; }
    .process-trace-button { margin-top: 10px; }
    .process-trace { max-height: 360px; overflow: auto; margin-top: 8px; }
    .process-trace-summary {
      display: flex; gap: 12px; padding: 6px 8px; color: #006064;
      background: #e0f7fa; font-size: 10px;
    }
    .process-trace-step {
      display: grid; grid-template-columns: minmax(58px, auto) minmax(0, 1fr) auto;
      align-items: start; gap: 6px; padding-top: 6px; padding-bottom: 6px; padding-right: 4px;
      border-bottom: 1px solid var(--border-color, #e3e8ee); font-size: 9px;
    }
    .process-trace-kind { color: #6a1b9a; font-weight: 600; overflow-wrap: anywhere; }
    .process-trace-body { display: flex; flex-direction: column; min-width: 0; gap: 2px; }
    .process-trace-conclusion { color: var(--text-primary, #1a1f36); overflow-wrap: anywhere; }
    .process-trace-body small { color: var(--text-secondary, #697386); font-size: 8px; overflow-wrap: anywhere; }
    .process-trace-step strong { color: #00838f; font-size: 9px; }

    @media (max-width: 720px) {
      .toolbar { align-items: flex-start; padding: 10px 12px; }
      .toolbar-left, .toolbar-center, .toolbar-right {
        width: 100%; max-width: none; margin: 0;
      }
      .toolbar-left button { flex: 1 1 auto; }
      .toolbar-right { justify-content: flex-start; gap: 4px; }
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
export class GraphVisualizerComponent implements OnInit, OnDestroy, OnChanges {
  private destroy$ = new Subject<void>();
  private searchSubject = new Subject<string>();
  private buildPollSubscription: Subscription | null = null;
  /** Auto-refresh the graph while it's actively changing (e.g. during a crawl writing to it). */
  autoRefresh = true;
  private lastGraphSignature = '';
  private lastQuery: string | undefined = undefined;

  // Fact sheet inputs
  @Input() factSheetId: number | null = null;
  @Input() factSheetName: string = '';
  @Input() focusNodeId: string | null = null;
  /** Graph-simulator ground-truth compare overlay (nodeId → recovery status); null = off. */
  @Input() simTruthNodeMap: Record<string, string> | null = null;

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
  /** nodeId → true for SSBN evidence/finding nodes; drives orange highlight in canvas */
  findingNodeMap: Record<string, boolean> | null = null;
  influenceOverlayActive = false;  // true when posteriorOverlay contains influence scores

  // Phase-2 KB overlay state
  strengthOverlayEnabled = false;
  provenanceOverlayEnabled = false;
  strengthBandMap = new Map<string, string>();
  private verifiedNodeIds = new Set<string>();
  private overlayDebounceTimer: any = null;

  // Community detection overlay state
  communityOverlayEnabled = false;
  communityMap: Map<string, number> = new Map();
  communityLoading = false;
  communityError: string | null = null;
  communityModularity: number | null = null;
  communityCount: number | null = null;
  communityMethod: 'louvain' | 'label_propagation' = 'louvain';
  communityResolution = 1.0;

  // Conformance overlay state (P2)
  conformanceOverlayEnabled = false;
  conformanceMap: Map<string, boolean | null> = new Map();  // nodeId -> true=conformant, false=violation, null=untagged
  conformanceLoading = false;

  // Typed reasoning-layer overlay state
  reasoningLayers: ReasoningLayers | null = null;
  reasoningLayersLoading = false;
  reasoningLayersError: string | null = null;
  reasoningLayerOverlayEnabled = false;
  reasoningLayerToggles: Record<ReasoningLayerKind, boolean> = {
    ontology: true,
    psl: true,
    mebn: true,
    provenance: true,
    opinion: true,
    neural: true
  };
  reasoningNodeMap: Map<string, NodeReasoningOverlay> = new Map();
  reasoningEdgeMap: Map<string, EdgeReasoningOverlay> = new Map();
  reasoningNodeLayerMap: Map<string, ReasoningLayerVisualOverlay> = new Map();
  reasoningEdgeLayerMap: Map<string, ReasoningLayerVisualOverlay> = new Map();
  reasoningViolationCount = 0;

  // Ranked process candidates and their selected graph evidence overlay
  processCandidates: ProcessSuggestionSummary[] = [];
  processLoading = false;
  processMining = false;
  processError: string | null = null;
  selectedProcessCandidate: ProcessSuggestionSummary | null = null;
  selectedProcessTrace: ProcessReasoningTrace | null = null;
  processTraceLoading = false;
  processTraceError: string | null = null;
  processEvidenceNodeIds: ReadonlySet<string> = new Set<string>();
  processEvidenceEdgeIds: ReadonlySet<string> = new Set<string>();

  // Focal/subgraph view state (D2)
  focalViewActive = false;
  focalViewLoading = false;
  focalRadius = 2;
  focalConfidenceFloor = 0.0;
  focalEdgeTypes: string[] = [];    // empty = all
  private preFocalData: D3VisualizationData | null = null;  // cached full graph before focal switch

  // Phase-2 filter state
  allStrengthBands: StrengthBand[] = ['ESTABLISHED', 'HIGH', 'PROBABLE', 'SPECULATIVE', 'SUPPRESSED'];
  strengthBandFilter: Set<StrengthBand> = new Set(this.allStrengthBands);
  provenanceFilter: 'ALL' | 'OBSERVED_ONLY' | 'DERIVED_ONLY' = 'ALL';
  creationTimeFrom: string = '';
  creationTimeTo: string = '';

  // Attribution & prediction state
  attributionResult: AttributionResult | null = null;
  attributionLoading = false;
  attributionError: string | null = null;
  predictionResult: PredictionResult | null = null;
  predictionLoading = false;
  predictionError: string | null = null;

  // Unified explain trail (POST /api/explain, HYBRID mode)
  explainTrail: ReasoningTrailDto | null = null;
  explainTrailLoading = false;

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
  /** 0 = unlimited (show the full graph). A positive value caps and triggers a "showing N of M" banner. Default 500 prevents loading multi-MB payloads on first open. */
  maxNodes = 500;
  /** Total nodes/edges available in the store (from backend metadata). Used for the "showing N of M" display. */
  totalAvailableNodes: number | null = null;
  totalAvailableEdges: number | null = null;
  /** Per-type counts from backend metadata — shown next to the filter-legend checkboxes. */
  nodeTypeCounts: { [type: string]: number } = {};
  edgeTypeCounts: { [type: string]: number } = {};
  filter: GraphFilter = {
    nodeTypes: [...DEFAULT_NODE_TYPES]
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
  weightsLoading = false;
  previewLoading = false;

  // Forces
  forceConfig: ForceConfig = { ...DEFAULT_FORCE_CONFIG };

  // Type lists
  allNodeTypes: NodeLevel[] = [...DEFAULT_NODE_TYPES];
  allEdgeTypes: EdgeType[] = [...DEFAULT_EDGE_TYPES];

  // Node colors for display
  private nodeColors: Record<NodeLevel, string> = {
    SOURCE: '#22c55e',
    DOCUMENT: '#3b82f6',
    SNIPPET: '#f59e0b',
    ENTITY: '#a855f7',
    CUSTOM: '#64748b',
    TABLE: '#00bcd4',
    ATTACHMENT: '#795548',
    IDENTIFIER: '#3F51B5',
    ALIAS: '#009688'  // Teal — synthetic cross-doc alias hub (matches NODE_COLORS)
  };

  /** Reference to the canvas child — used for imperative LOD merge calls (addNodesToGraph). */
  @ViewChild('graphCanvas') private graphCanvas?: GraphCanvasComponent;

  constructor(
    private graphService: GraphService,
    private weightService: SourceWeightService,
    private attributionService: AttributionService,
    private processEngineService: ProcessEngineService,
    private kbGrounding: KbGroundingService,
    private snackBar: MatSnackBar,
    private dialog: MatDialog,
    private http: HttpClient
  ) {}

  /** True while a .kgraph export/import is in flight (drives the toolbar spinner icons). */
  exportingUnified = false;
  importingUnified = false;

  /** Download this fact sheet's full native graph (.kgraph) — all vector layers, opinions, weights. */
  exportUnifiedGraph(): void {
    this.exportingUnified = true;
    this.graphService.exportNativeGraph(this.factSheetId).subscribe({
      next: (resp) => {
        this.exportingUnified = false;
        const blob = resp.body;
        if (!blob) { return; }
        const cd = resp.headers.get('Content-Disposition') || '';
        const match = /filename="?([^";]+)"?/.exec(cd);
        const fallback = `graph-${this.factSheetId != null ? this.factSheetId : 'global'}.kgraph`;
        const filename = match ? match[1] : fallback;
        const url = URL.createObjectURL(blob);
        const a = document.createElement('a');
        a.href = url;
        a.download = filename;
        document.body.appendChild(a);
        a.click();
        document.body.removeChild(a);
        URL.revokeObjectURL(url);
        this.snackBar.open('Graph exported', 'OK', { duration: 2500 });
      },
      error: (e) => {
        this.exportingUnified = false;
        this.snackBar.open(`Export failed: ${e?.error?.message || e?.message || 'error'}`, 'Dismiss', { duration: 5000 });
      }
    });
  }

  /** Import a .kgraph file into this fact sheet, then reload the visualization. */
  importUnifiedGraph(event: Event): void {
    const input = event.target as HTMLInputElement;
    const file = input.files && input.files.length ? input.files[0] : null;
    input.value = ''; // allow re-selecting the same file
    if (!file) { return; }
    this.importingUnified = true;
    this.graphService.importNativeGraph(file, this.factSheetId).subscribe({
      next: (summary) => {
        this.importingUnified = false;
        this.snackBar.open(
          `Imported ${summary.nodes} nodes, ${summary.edges} edges, ${summary.embeddings} embeddings` +
            (summary.atoms > 0 ? ` — ${summary.atoms} facts projected (reasoning-ready)` : ''),
          'OK', { duration: 4000 });
        this.loadGraph();
      },
      error: (e) => {
        this.importingUnified = false;
        this.snackBar.open(`Import failed: ${e?.error?.message || e?.message || 'error'}`, 'Dismiss', { duration: 5000 });
      }
    });
  }

  ngOnInit(): void {
    this.loadGraph();
    this.loadSourceWeights();
    this.loadTemporalBounds();
    this.loadProcessCandidates();

    // Debounce search input
    this.searchSubject.pipe(
      debounceTime(300),
      takeUntil(this.destroy$)
    ).subscribe(query => {
      this.loadGraph(query);
    });

    // Live-refresh: while the graph is changing (e.g. a crawl is writing to it), reload so the
    // visualizer fills in without a manual Refresh. Gated on a node/edge-count signature so we only
    // re-render when the graph actually changed (no jank when idle).
    //
    // Perf guards:
    //  • Skip when the browser tab is hidden (document.hidden) — zero-cost when backgrounded.
    //  • For large graphs (totalAvailableNodes > 2000), use the same LOD top-K path as loadGraph()
    //    instead of requesting the full visualization payload every tick.
    interval(5000).pipe(takeUntil(this.destroy$), pauseWhenHidden()).subscribe(() => {
      if (!this.autoRefresh || this.loading) return;
      const from = this.temporalFilterActive && this.timeFrom ? this.timeFrom + 'T00:00:00' : undefined;
      const to = this.temporalFilterActive && this.timeTo ? this.timeTo + 'T23:59:59' : undefined;

      // Mirror the LOD decision from loadGraph(): if we already know the graph is large,
      // use the cheap top-K endpoint rather than the full visualization payload.
      const refreshObs = (this.totalAvailableNodes !== null && this.totalAvailableNodes > 2000)
        ? this.graphService.getTopKVisualization(300, 'pagerank', this.factSheetId ?? undefined)
        : this.graphService.getVisualizationData(undefined, this.maxDepth, this.maxNodes, from, to);

      refreshObs
        .pipe(takeUntil(this.destroy$))
        .subscribe({
          next: (data: any) => {
            const sig = this.graphSignature(data);
            if (sig === this.lastGraphSignature) return; // unchanged → skip re-render (no layout jank)
            this.lastGraphSignature = sig;
            this.fullGraphData = data;
            this.mergeAvailableGraphTypes(data);
            this.graphData = this.applyFilters(data, this.lastQuery);
            this.refreshProcessEvidenceOverlay();
            if (this.strengthOverlayEnabled) {
              this.scheduleVisibleNodeVerify();
            }
            this.refreshReasoningLayersAfterGraphChange();
          },
          error: () => { /* transient — keep last render */ }
        });
    });
  }

  ngOnDestroy(): void {
    this.stopTimelineTimer();
    if (this.overlayDebounceTimer) {
      clearTimeout(this.overlayDebounceTimer);
    }
    this.destroy$.next();
    this.destroy$.complete();
  }

  ngOnChanges(changes: SimpleChanges): void {
    if (changes['factSheetId'] && !changes['factSheetId'].firstChange) {
      this.clearReasoningLayers();
      this.clearProcessSelection();
      this.loadProcessCandidates();
      this.loadGraph(this.lastQuery);
    }
    if (changes['focusNodeId'] && this.focusNodeId) {
      this.expandNodeById(this.focusNodeId);
    }
  }

  loadGraph(query?: string): void {
    this.loading = true;
    this.lastQuery = query;

    const from = this.temporalFilterActive && this.timeFrom ? this.timeFrom + 'T00:00:00' : undefined;
    const to = this.temporalFilterActive && this.timeTo ? this.timeTo + 'T23:59:59' : undefined;

    // LOD-first: ping /statistics to learn full graph size. If totalNodes > 2000, seed with
    // top-300 by PageRank instead of loading the entire graph. Falls back to flat load on
    // statistics error so normal operation is never interrupted.
    const loadObservable = this.graphService.getStatistics().pipe(
      switchMap((stats: any) => {
        const totalNodes: number = stats?.totalNodes ?? stats?.nodeCount ?? 0;
        if (totalNodes > 2000) {
          return this.graphService.getTopKVisualization(300, 'pagerank', this.factSheetId ?? undefined);
        }
        return this.graphService.getVisualizationData(undefined, this.maxDepth, this.maxNodes, from, to);
      }),
      catchError(() => this.graphService.getVisualizationData(undefined, this.maxDepth, this.maxNodes, from, to))
    );

    loadObservable
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: (data) => {
          // Capture store totals from backend metadata (used to show "N of M" in the stats bar)
          const meta = data.statistics as any;
          this.totalAvailableNodes = meta?.totalAvailableNodes ?? null;
          this.totalAvailableEdges = meta?.totalAvailableEdges ?? null;
          this.nodeTypeCounts = meta?.nodeTypeCounts ?? {};
          this.edgeTypeCounts = meta?.edgeTypeCounts ?? {};
          // Cache full data for timeline snapshots
          this.fullGraphData = data;
          this.mergeAvailableGraphTypes(data);
          // Apply filters (snapshot filter handled inside applyFilters)
          this.graphData = this.applyFilters(data, query);
          this.refreshProcessEvidenceOverlay();
          this.lastGraphSignature = this.graphSignature(data);
          this.loading = false;
          // If strength overlay is active, verify newly visible nodes
          if (this.strengthOverlayEnabled) {
            this.scheduleVisibleNodeVerify();
          }
          if (this.factSheetId) {
            this.loadReasoningLayers(true);
          } else {
            this.clearReasoningLayers();
          }
        },
        error: (err) => {
          console.error('Failed to load graph:', err);
          this.snackBar.open('Failed to load knowledge graph', 'Dismiss', { duration: 3000 });
          this.loading = false;
        }
      });
  }

  /** Lightweight change signature for auto-refresh gating (node + edge counts). */
  private graphSignature(data: any): string {
    const n = data?.nodes?.length ?? 0;
    const e = data?.links?.length ?? data?.edges?.length ?? 0;
    return n + ':' + e;
  }

  loadProcessCandidates(): void {
    if (this.factSheetId == null) {
      this.processCandidates = [];
      this.clearProcessSelection();
      return;
    }

    const selectedId = this.selectedProcessCandidate?.id;
    this.processLoading = true;
    this.processError = null;
    this.processEngineService.listStoredSuggestions(this.factSheetId)
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: (response) => {
          this.processLoading = false;
          this.processCandidates = sortProcessSuggestions(response.suggestions || []);
          this.selectedProcessCandidate = selectedId
            ? this.processCandidates.find(candidate => candidate.id === selectedId) ?? null
            : this.processCandidates[0] ?? null;
          this.selectedProcessTrace = null;
          this.processTraceError = null;
          this.refreshProcessEvidenceOverlay();
        },
        error: (err) => {
          this.processLoading = false;
          this.processCandidates = [];
          this.clearProcessSelection();
          this.processError = 'Candidates failed: ' + (err.error?.message || err.message);
        }
      });
  }

  runProcessDiscovery(): void {
    if (this.factSheetId == null) {
      return;
    }
    this.processMining = true;
    this.processError = null;
    this.processEngineService.mineProcesses(this.factSheetId)
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: () => {
          this.processMining = false;
          this.loadGraph(this.lastQuery);
          this.loadProcessCandidates();
          this.snackBar.open('Process mining complete', 'OK', { duration: 2500 });
        },
        error: (err) => {
          this.processMining = false;
          this.processError = 'Mining failed: ' + (err.error?.message || err.message);
        }
      });
  }

  selectProcessCandidate(candidate: ProcessSuggestionSummary): void {
    if (this.selectedProcessCandidate?.id === candidate.id) {
      this.clearProcessSelection();
      return;
    }
    this.selectedProcessCandidate = candidate;
    this.selectedProcessTrace = null;
    this.processTraceError = null;
    this.refreshProcessEvidenceOverlay();
  }

  clearProcessSelection(): void {
    this.selectedProcessCandidate = null;
    this.selectedProcessTrace = null;
    this.processTraceError = null;
    this.processEvidenceNodeIds = new Set<string>();
    this.processEvidenceEdgeIds = new Set<string>();
  }

  refreshProcessEvidenceOverlay(): void {
    const candidate = this.selectedProcessCandidate;
    if (!candidate) {
      this.processEvidenceNodeIds = new Set<string>();
      this.processEvidenceEdgeIds = new Set<string>();
      return;
    }

    const nodeIds = new Set<string>(candidate.sourceGraphNodeIds || []);
    const edgeIds = new Set<string>(candidate.sourceGraphRelationIds || []);
    const data = this.fullGraphData ?? this.graphData;
    for (const link of data?.links || []) {
      const supportsCandidate = edgeIds.has(link.id)
        || this.processSuggestionIdForEdge(link) === candidate.id;
      if (!supportsCandidate) {
        continue;
      }
      edgeIds.add(link.id);
      const sourceId = this.graphEndpointId(link.source);
      const targetId = this.graphEndpointId(link.target);
      if (sourceId) nodeIds.add(sourceId);
      if (targetId) nodeIds.add(targetId);
    }
    this.processEvidenceNodeIds = nodeIds;
    this.processEvidenceEdgeIds = edgeIds;
  }

  loadSelectedProcessTrace(): void {
    if (this.selectedProcessTrace) {
      this.selectedProcessTrace = null;
      return;
    }
    const candidate = this.selectedProcessCandidate;
    if (!candidate?.reasoningTraceId) {
      return;
    }
    this.processTraceLoading = true;
    this.processTraceError = null;
    this.processEngineService.getStoredSuggestionTrace(candidate.id)
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: (trace) => {
          if (this.selectedProcessCandidate?.id === candidate.id) {
            this.selectedProcessTrace = trace;
          }
          this.processTraceLoading = false;
        },
        error: (err) => {
          this.processTraceLoading = false;
          this.processTraceError = err.status === 404
            ? 'No persisted trace is available'
            : 'Trace failed: ' + (err.error?.message || err.message);
        }
      });
  }

  selectedProcessTraceSteps(): FlatProcessReasoningStep[] {
    return flattenProcessReasoningTrace(this.selectedProcessTrace);
  }

  processHybridActivities(candidate: ProcessSuggestionSummary): ProcessHybridActivityReasoning[] {
    return rankedProcessHybridActivities(candidate);
  }

  processHybridMode(reasoning: ProcessHybridReasoning): string {
    return processHybridModeLabel(reasoning);
  }

  processCandidateScore(candidate: ProcessSuggestionSummary): number {
    return candidate.learnedScore ?? candidate.confidence ?? 0;
  }

  formatProcessLabel(value: string | null | undefined): string {
    return (value || '').replace(/_/g, ' ').toLowerCase();
  }

  trackByProcessCandidate(_index: number, candidate: ProcessSuggestionSummary): string {
    return candidate.id;
  }

  trackProcessHybridActivity(_index: number, activity: ProcessHybridActivityReasoning): string {
    return activity.activity;
  }

  trackProcessTraceStep(index: number, step: FlatProcessReasoningStep): string {
    return `${index}:${step.kind}:${step.conclusion}`;
  }

  private processSuggestionIdForEdge(link: any): string | undefined {
    if (link?.metadata?.suggestionId != null) {
      return String(link.metadata.suggestionId);
    }
    if (typeof link?.metadataJson !== 'string' || !link.metadataJson.trim()) {
      return undefined;
    }
    try {
      const metadata = JSON.parse(link.metadataJson) as Record<string, unknown>;
      return metadata['suggestionId'] == null ? undefined : String(metadata['suggestionId']);
    } catch {
      return undefined;
    }
  }

  private graphEndpointId(endpoint: any): string | undefined {
    if (typeof endpoint === 'string') {
      return endpoint;
    }
    const id = endpoint?.id ?? endpoint?.nodeId;
    return id == null ? undefined : String(id);
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
      .pipe(takeUntil(this.destroy$), pauseWhenHidden())
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
    this.weightsLoading = true;
    this.weightService.getWeights()
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: (weights) => {
          this.sourceWeights = weights;
          this.weightsLoading = false;
        },
        error: (err) => {
          console.error('Failed to load weights:', err);
          this.weightsLoading = false;
        }
      });
  }

  private mergeAvailableGraphTypes(data: D3VisualizationData): void {
    const previousNodeTypes = new Set(this.allNodeTypes);

    const nodeTypes = new Set<NodeLevel>(this.allNodeTypes);
    Object.keys(this.nodeTypeCounts || {}).forEach(type => nodeTypes.add(type as NodeLevel));
    data.nodes.forEach(node => {
      if (node.type) nodeTypes.add(node.type);
    });
    this.allNodeTypes = Array.from(nodeTypes);
    for (const type of this.allNodeTypes) {
      if (!previousNodeTypes.has(type) && !this.filter.nodeTypes.includes(type)) {
        this.filter.nodeTypes.push(type);
      }
    }

    const edgeTypes = new Set<EdgeType>(this.allEdgeTypes);
    Object.keys(this.edgeTypeCounts || {}).forEach(type => edgeTypes.add(type));
    data.links.forEach(link => {
      if (link.type) edgeTypes.add(link.type);
    });
    this.allEdgeTypes = Array.from(edgeTypes);
  }

  private linkEndpointId(endpoint: string | D3Node | GraphNode | null | undefined): string | null {
    if (!endpoint) return null;
    if (typeof endpoint === 'string') return endpoint;
    const candidate = endpoint as unknown as { id?: string | number; nodeId?: string };
    return candidate.nodeId || (typeof candidate.id === 'string' ? candidate.id : null);
  }

  applyFilters(data: D3VisualizationData, searchQuery?: string): D3VisualizationData {
    let nodes = data.nodes.filter(n => this.filter.nodeTypes.includes(n.type));
    let links = data.links.slice();

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
          // Keep items without timestamps so they are not silently dropped
          const windowStart = this.getSnapshotWindowStart();
          links = links.filter(l => {
            if (!l.occurredAt) return true;
            return l.occurredAt >= windowStart && l.occurredAt <= cutoff;
          });
          nodes = nodes.filter(n => {
            if (!n.occurredAt) return true;
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

    // Phase-2: strength-band filter (only exclude nodes that have a KNOWN band that's unchecked)
    if (this.strengthBandFilter.size < this.allStrengthBands.length && this.strengthBandMap.size > 0) {
      nodes = nodes.filter(n => {
        const band = this.strengthBandMap.get(n.id) as StrengthBand | undefined;
        if (!band) return true; // unknown band — keep
        return this.strengthBandFilter.has(band);
      });
    }

    // Phase-2: creation-time window filter on _extractedAt or createdAt metadata
    if (this.creationTimeFrom || this.creationTimeTo) {
      const fromDate = this.creationTimeFrom ? this.creationTimeFrom + 'T00:00:00' : null;
      const toDate = this.creationTimeTo ? this.creationTimeTo + 'T23:59:59' : null;
      nodes = nodes.filter(n => {
        const meta = n.metadata as Record<string, string> | undefined;
        const ts = meta?.['_extractedAt'] || meta?.['createdAt'] || n.occurredAt;
        if (!ts) return true; // no timestamp — keep
        if (fromDate && ts < fromDate) return false;
        if (toDate && ts > toDate) return false;
        return true;
      });
    }

    // Phase-2: provenance type filter
    if (this.provenanceFilter !== 'ALL') {
      nodes = nodes.filter(n => {
        const meta = n.metadata as Record<string, unknown> | undefined;
        const isDerived =
          meta?.['_derived'] === true ||
          (typeof meta?.['_source'] === 'string' && (meta['_source'] as string).toLowerCase().includes('derived')) ||
          (typeof meta?.['_provenance'] === 'string' && (meta['_provenance'] as string).toLowerCase().includes('derived'));
        if (this.provenanceFilter === 'DERIVED_ONLY') return isDerived;
        if (this.provenanceFilter === 'OBSERVED_ONLY') return !isDerived;
        return true;
      });
    }

    // Keep only links where both endpoint nodes are still visible. D3/Sigma callers may
    // mutate source/target from ids into node objects, so normalize before membership checks.
    const nodeIds = new Set(nodes.map(n => n.id));
    links = links.filter(l => {
      const sourceId = this.linkEndpointId(l.source);
      const targetId = this.linkEndpointId(l.target);
      return !!sourceId && !!targetId && nodeIds.has(sourceId) && nodeIds.has(targetId);
    });

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

  /** True when a graph node should render as a table — store-agnostic (JPA or vector backend). */
  isTableNode(node: D3Node | null): boolean {
    return !!node && (node.type === 'TABLE' || !!node.metadata?.['fullTableContent']);
  }

  /** Best-available table markdown for a node: full content if persisted, else the preview. */
  getNodeTableMarkdown(node: D3Node | null): string {
    return (node?.metadata?.['fullTableContent'] as string) || node?.description || '';
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
    this.attributionError = null;
    this.predictionResult = null;
    this.predictionError = null;

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

  onFindingNodesChanged(m: Record<string, boolean> | null): void {
    this.findingNodeMap = m;
  }

  onNodeDoubleClicked(node: D3Node): void {
    // LOD expand: fetch the node's 1-hop neighborhood and merge it into the live graph
    // without a full reload. Falls back to flat expandNodeById on error.
    this.graphService.getNodeNeighborhood(node.id, 50)
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: (data) => {
          if (this.graphCanvas) {
            this.graphCanvas.addNodesToGraph(data);
          } else {
            // Canvas not yet rendered (should not happen during a double-click, but guard anyway)
            this.expandNodeById(node.id);
          }
        },
        error: (err) => {
          console.error('Failed to expand node neighborhood:', err);
          this.expandNodeById(node.id);
        }
      });
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
    // Apply the type filter in-memory against the cached full data to avoid a
    // network round-trip. Only fall back to loadGraph() when no data is cached yet.
    if (this.fullGraphData) {
      this.graphData = this.applyFilters(this.fullGraphData, this.lastQuery);
    } else if (this.graphData) {
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
      nodeTypes: [...this.allNodeTypes]
    };
    this.maxDepth = 2;
    this.maxNodes = 500;  // reset to bounded default — use slider to change
    this.searchQuery = '';
    this.temporalFilterActive = false;
    this.timeFrom = '';
    this.timeTo = '';
    this.timelineStop();
    // Phase-2 filter reset
    this.strengthBandFilter = new Set(this.allStrengthBands);
    this.provenanceFilter = 'ALL';
    this.creationTimeFrom = '';
    this.creationTimeTo = '';
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

    this.previewLoading = true;
    this.weightService.previewWeightedSearch(this.previewQuery)
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: (preview) => {
          this.weightPreview = preview;
          this.previewLoading = false;
        },
        error: (err) => {
          console.error('Failed to preview weights:', err);
          this.previewLoading = false;
          this.snackBar.open('Failed to preview weights', 'Dismiss', { duration: 3000 });
        }
      });
  }

  submitSourceFeedback(sourceId: string, wasHelpful: boolean): void {
    this.weightService.submitFeedback({ sourceNodeId: sourceId, wasHelpful })
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: () => {
          this.snackBar.open(wasHelpful ? 'Marked helpful — weight boosted' : 'Marked unhelpful — weight reduced', 'OK', { duration: 2500 });
          // Refresh the weight list so sliders reflect the new quality score
          this.loadSourceWeights();
        },
        error: (err) => {
          console.error('Failed to submit feedback:', err);
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
  // PHASE-2: STRENGTH & PROVENANCE OVERLAYS
  // ═══════════════════════════════════════════════════════════════════════════

  toggleReasoningLayerOverlay(checked?: boolean): void {
    const next = checked !== undefined ? checked : !this.reasoningLayerOverlayEnabled;
    if (next && !this.factSheetId) {
      this.snackBar.open('Select a fact sheet first', 'Dismiss', { duration: 2000 });
      return;
    }
    this.reasoningLayerOverlayEnabled = next;
    if (next && !this.reasoningLayers) {
      this.loadReasoningLayers(true);
    } else {
      this.rebuildReasoningLayerMaps();
    }
  }

  loadReasoningLayers(force = false): void {
    if (!this.factSheetId) {
      this.clearReasoningLayers();
      return;
    }
    if (this.reasoningLayersLoading) return;
    if (this.reasoningLayers && !force && !this.reasoningLayerOverlayEnabled) {
      this.rebuildReasoningLayerMaps();
      return;
    }

    this.reasoningLayersLoading = true;
    this.reasoningLayersError = null;
    this.graphService.getReasoningLayers(this.factSheetId)
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: (layers) => {
          this.reasoningLayers = layers;
          this.reasoningLayersLoading = false;
          this.rebuildReasoningLayerMaps();
        },
        error: (err) => {
          this.reasoningLayersLoading = false;
          this.reasoningLayersError = err?.error?.error || err?.message || 'Failed to load reasoning layers';
          this.reasoningNodeMap = new Map();
          this.reasoningEdgeMap = new Map();
          this.reasoningNodeLayerMap = new Map();
          this.reasoningEdgeLayerMap = new Map();
        }
      });
  }

  setReasoningLayer(kind: ReasoningLayerKind, enabled: boolean): void {
    this.reasoningLayerToggles = {
      ...this.reasoningLayerToggles,
      [kind]: enabled
    };
    this.rebuildReasoningLayerMaps();
  }

  getSelectedNodeReasoning(): NodeReasoningOverlay | null {
    if (!this.selectedNode) return null;
    return this.reasoningNodeMap.get(this.selectedNode.id) || null;
  }

  getRelationReasoning(relation: GraphEdge): EdgeReasoningOverlay | null {
    const direct = relation.edgeId ? this.reasoningEdgeMap.get(relation.edgeId) : null;
    if (direct) return direct;
    const byId = relation.id != null ? this.reasoningEdgeMap.get(String(relation.id)) : null;
    if (byId) return byId;
    return this.reasoningEdgeMap.get(this.reasoningEdgeCompositeKey(relation.sourceNodeId, relation.targetNodeId, relation.edgeType)) || null;
  }

  objectEntries(obj: Record<string, number> | null | undefined): Array<{ key: string; value: number }> {
    if (!obj) return [];
    return Object.entries(obj)
      .filter((entry): entry is [string, number] => Number.isFinite(entry[1]))
      .map(([key, value]) => ({ key, value }));
  }

  formatConformance(value: boolean | null | undefined): string {
    if (value === true) return 'ok';
    if (value === false) return 'violation';
    if (value === null) return 'untagged';
    return 'unknown';
  }

  formatTypeHierarchy(hierarchy: TypeHierarchyOverlay): string {
    if (!hierarchy) return '';
    return hierarchy.parentType ? `${hierarchy.type} -> ${hierarchy.parentType}` : hierarchy.type;
  }

  formatTypeHierarchyBasis(hierarchy: TypeHierarchyOverlay): string {
    return this.joinReasoningParts(
      hierarchy.source,
      hierarchy.basis,
      hierarchy.depth != null ? `depth ${hierarchy.depth}` : undefined
    );
  }

  formatInferredRelation(relation: InferredRelationOverlay): string {
    const relationType = relation.relationType || 'INFERRED';
    const source = relation.sourceType || relation.sourceNodeId;
    const target = relation.targetType || relation.targetNodeId;
    if (source && target) return `${source} ${relationType} ${target}`;
    if (source) return `${relationType} ${source}`;
    if (target) return `${relationType} ${target}`;
    return relationType;
  }

  formatInferredRelationBasis(relation: InferredRelationOverlay): string {
    return this.joinReasoningParts(relation.inferenceSource, relation.basis);
  }

  private clearReasoningLayers(): void {
    this.reasoningLayers = null;
    this.reasoningLayersError = null;
    this.reasoningLayerOverlayEnabled = false;
    this.reasoningNodeMap = new Map();
    this.reasoningEdgeMap = new Map();
    this.reasoningNodeLayerMap = new Map();
    this.reasoningEdgeLayerMap = new Map();
    this.reasoningViolationCount = 0;
  }

  private rebuildReasoningLayerMaps(): void {
    const nodeMap = new Map<string, NodeReasoningOverlay>();
    const edgeMap = new Map<string, EdgeReasoningOverlay>();
    const nodeVisualMap = new Map<string, ReasoningLayerVisualOverlay>();
    const edgeVisualMap = new Map<string, ReasoningLayerVisualOverlay>();
    let violations = 0;

    if (!this.reasoningLayers) {
      this.reasoningNodeMap = nodeMap;
      this.reasoningEdgeMap = edgeMap;
      this.reasoningNodeLayerMap = nodeVisualMap;
      this.reasoningEdgeLayerMap = edgeVisualMap;
      this.reasoningViolationCount = 0;
      return;
    }

    for (const node of this.reasoningLayers.nodes || []) {
      nodeMap.set(node.nodeId, node);
      if (this.hasOntologyViolation(node)) violations++;
      const visual = this.toVisualReasoningOverlay(node);
      if (visual) nodeVisualMap.set(node.nodeId, visual);
    }

    for (const edge of this.reasoningLayers.edges || []) {
      if (edge.edgeId) edgeMap.set(edge.edgeId, edge);
      if (edge.edgeId) {
        const visual = this.toVisualReasoningOverlay(edge);
        if (visual) edgeVisualMap.set(edge.edgeId, visual);
      }
      if (edge.sourceNodeId && edge.targetNodeId && edge.edgeType) {
        const visual = this.toVisualReasoningOverlay(edge);
        for (const key of this.reasoningEdgeCompositeKeys(edge.sourceNodeId, edge.targetNodeId, edge.edgeType)) {
          edgeMap.set(key, edge);
          if (visual) edgeVisualMap.set(key, visual);
        }
      }
      if (this.hasOntologyViolation(edge)) violations++;
    }

    this.reasoningNodeMap = nodeMap;
    this.reasoningEdgeMap = edgeMap;
    this.reasoningNodeLayerMap = nodeVisualMap;
    this.reasoningEdgeLayerMap = edgeVisualMap;
    this.reasoningViolationCount = violations;
  }

  private toVisualReasoningOverlay(reasoning: NodeReasoningOverlay | EdgeReasoningOverlay): ReasoningLayerVisualOverlay | null {
    const activeLayers: ReasoningLayerKind[] = [];
    const visual: ReasoningLayerVisualOverlay = { activeLayers };

    if (this.reasoningLayerToggles.ontology && reasoning.ontology) {
      activeLayers.push('ontology');
      visual.ontologyConformant = reasoning.ontology.conformant;
      visual.ontologyViolation = this.hasOntologyViolation(reasoning);
      visual.inferredRelationship = this.hasInferredRelationship(reasoning.ontology);
      visual.inferredRelationshipScore = this.maxInferredRelationshipScore(reasoning.ontology);
      visual.hierarchyDepth = this.minHierarchyDepth(reasoning.ontology);
    }
    if (this.reasoningLayerToggles.psl && reasoning.psl) {
      activeLayers.push('psl');
      visual.pslTruthValue = this.firstFinite(reasoning.psl.truthValue, 0.5);
      visual.pslIncompatibility = this.firstFinite(reasoning.psl.incompatibility);
    }
    if (this.reasoningLayerToggles.mebn && reasoning.mebn) {
      activeLayers.push('mebn');
      visual.mebnPosterior = this.firstFinite(reasoning.mebn.posterior);
      visual.mebnPrior = this.firstFinite(reasoning.mebn.prior);
      visual.mebnFinding = (reasoning.mebn.findings?.length || 0) > 0;
    }
    if (this.reasoningLayerToggles.provenance && reasoning.provenance) {
      activeLayers.push('provenance');
      visual.provenance = true;
    }
    if (this.reasoningLayerToggles.opinion && reasoning.opinion) {
      activeLayers.push('opinion');
      visual.opinionConfidence = this.firstFinite(
        reasoning.opinion.confidence,
        reasoning.opinion.belief,
        reasoning.opinion.uncertainty != null ? 1 - reasoning.opinion.uncertainty : undefined
      );
    }
    if (this.reasoningLayerToggles.neural && reasoning.neuralScores) {
      activeLayers.push('neural');
      visual.neuralScore = this.maxScore(reasoning.neuralScores.scores);
    }

    return activeLayers.length > 0 ? visual : null;
  }

  private hasOntologyViolation(reasoning: NodeReasoningOverlay | EdgeReasoningOverlay): boolean {
    return reasoning.ontology?.conformant === false || (reasoning.ontology?.violations?.length || 0) > 0;
  }

  private hasInferredRelationship(ontology: OntologyOverlay | null | undefined): boolean {
    return (ontology?.inferredRelations?.length || 0) > 0 || (ontology?.typeHierarchy?.length || 0) > 0;
  }

  private maxInferredRelationshipScore(ontology: OntologyOverlay): number | undefined {
    const scores = [
      ...(ontology.inferredRelations || []).map(relation => relation.confidence),
      ...(ontology.typeHierarchy || []).map(hierarchy => hierarchy.confidence)
    ].filter((value): value is number => Number.isFinite(value));
    return scores.length ? Math.max(...scores) : undefined;
  }

  private minHierarchyDepth(ontology: OntologyOverlay): number | undefined {
    const depths = (ontology.typeHierarchy || [])
      .map(hierarchy => hierarchy.depth)
      .filter((value): value is number => Number.isFinite(value));
    return depths.length ? Math.min(...depths) : undefined;
  }

  private firstFinite(...values: Array<number | null | undefined>): number | undefined {
    return values.find((value): value is number => Number.isFinite(value));
  }

  private maxScore(scores: Record<string, number> | undefined): number | undefined {
    if (!scores) return undefined;
    const finiteScores = Object.values(scores).filter((value): value is number => Number.isFinite(value));
    return finiteScores.length ? Math.max(...finiteScores) : undefined;
  }

  private joinReasoningParts(...parts: Array<string | null | undefined>): string {
    return parts
      .map(part => typeof part === 'string' ? part.trim() : '')
      .filter(part => part.length > 0)
      .join(' / ');
  }

  private reasoningEdgeCompositeKey(sourceNodeId: string, targetNodeId: string, edgeType: EdgeType | string): string {
    return `${sourceNodeId}->${targetNodeId}:${edgeType}`;
  }

  private reasoningEdgeCompositeKeys(sourceNodeId: string, targetNodeId: string, edgeType: EdgeType | string): string[] {
    return [
      this.reasoningEdgeCompositeKey(sourceNodeId, targetNodeId, edgeType),
      `${sourceNodeId}→${targetNodeId}:${edgeType}`
    ];
  }

  private refreshReasoningLayersAfterGraphChange(): void {
    if (!this.factSheetId) {
      this.clearReasoningLayers();
      return;
    }
    if (this.reasoningLayers || this.reasoningLayerOverlayEnabled || this.selectedTabIndex === 3) {
      this.loadReasoningLayers(true);
    }
  }

  toggleStrengthOverlay(): void {
    this.strengthOverlayEnabled = !this.strengthOverlayEnabled;
    if (this.strengthOverlayEnabled && this.graphData) {
      this.scheduleVisibleNodeVerify();
    }
  }

  toggleProvenanceOverlay(): void {
    this.provenanceOverlayEnabled = !this.provenanceOverlayEnabled;
  }

  toggleCommunityOverlay(): void {
    if (this.communityOverlayEnabled) {
      this.communityOverlayEnabled = false;
      this.communityMap = new Map();
      return;
    }
    if (!this.factSheetId) {
      this.snackBar.open('Select a fact sheet first', 'Dismiss', { duration: 2000 });
      return;
    }
    this.loadCommunityOverlay();
  }

  private loadCommunityOverlay(): void {
    if (!this.factSheetId) return;
    this.communityLoading = true;
    this.communityError = null;
    const url = `/api/graph/${this.factSheetId}/communities?method=${this.communityMethod}&resolution=${this.communityResolution}&maxNodes=500`;
    this.http.get<any>(url).subscribe({
      next: (result) => {
        const map = new Map<string, number>();
        if (result.nodeToCommunit) {
          for (const [nodeId, cid] of Object.entries(result.nodeToCommunit as Record<string, number>)) {
            map.set(nodeId, cid);
          }
        }
        this.communityMap = map;
        this.communityModularity = result.modularity;
        this.communityCount = result.communityCount;
        this.communityOverlayEnabled = true;
        this.communityLoading = false;
        this.snackBar.open(`${result.communityCount} communities detected (Q=${result.modularity?.toFixed(3)})`, 'Dismiss', { duration: 3000 });
      },
      error: (err) => {
        this.communityError = err?.error?.error || err?.message || 'Community detection failed';
        this.communityLoading = false;
        this.snackBar.open('Community detection failed: ' + this.communityError, 'Dismiss', { duration: 3000 });
      }
    });
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // P2: CONFORMANCE OVERLAY
  // ═══════════════════════════════════════════════════════════════════════════

  toggleConformanceOverlay(): void {
    if (this.conformanceOverlayEnabled) {
      this.conformanceOverlayEnabled = false;
      this.conformanceMap = new Map();
      return;
    }
    if (!this.factSheetId) {
      this.snackBar.open('Select a fact sheet first', 'Dismiss', { duration: 2000 });
      return;
    }
    this.loadConformanceOverlay();
  }

  private loadConformanceOverlay(): void {
    if (!this.factSheetId) return;
    this.conformanceLoading = true;
    const url = `/api/graph/${this.factSheetId}/conformance`;
    this.http.get<any[]>(url).subscribe({
      next: (entries) => {
        const map = new Map<string, boolean | null>();
        for (const entry of entries) {
          // conformant is true | false | null (untagged)
          map.set(entry.nodeId, entry.conformant as boolean | null);
        }
        this.conformanceMap = map;
        this.conformanceOverlayEnabled = true;
        this.conformanceLoading = false;

        const conformantCount  = [...map.values()].filter(v => v === true).length;
        const violationCount   = [...map.values()].filter(v => v === false).length;
        const untaggedCount    = [...map.values()].filter(v => v === null).length;
        this.snackBar.open(
          `Conformance: ${conformantCount} ok, ${violationCount} violations, ${untaggedCount} untagged`,
          'Dismiss', { duration: 4000 });
      },
      error: (err) => {
        this.conformanceLoading = false;
        const msg = err?.error?.error || err?.message || 'Conformance overlay failed';
        this.snackBar.open('Conformance overlay failed: ' + msg, 'Dismiss', { duration: 3000 });
      }
    });
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // D2: FOCAL / SUBGRAPH VIEW
  // ═══════════════════════════════════════════════════════════════════════════

  toggleFocalEdgeType(type: string): void {
    const idx = this.focalEdgeTypes.indexOf(type);
    if (idx >= 0) {
      this.focalEdgeTypes.splice(idx, 1);
    } else {
      this.focalEdgeTypes.push(type);
    }
  }

  buildFocalView(): void {
    if (!this.selectedNode) {
      this.snackBar.open('Select a node first to build a focal view', 'Dismiss', { duration: 2000 });
      return;
    }
    if (!this.factSheetId) {
      this.snackBar.open('Select a fact sheet first', 'Dismiss', { duration: 2000 });
      return;
    }

    // Cache the full graph so we can restore it when exiting
    if (!this.focalViewActive) {
      this.preFocalData = this.graphData;
    }

    this.focalViewLoading = true;
    const url = `/api/graph/${this.factSheetId}/subgraph`;
    const body = {
      seedNodeIds: [this.selectedNode.id],
      radius: this.focalRadius,
      edgeTypes: this.focalEdgeTypes.length > 0 ? this.focalEdgeTypes : [],
      confidenceFloor: this.focalConfidenceFloor
    };

    this.http.post<any>(url, body).subscribe({
      next: (result) => {
        // Backend returns { nodes, links, edges, statistics }
        // The frontend D3VisualizationData shape uses 'links' as the edge array
        const focalData: D3VisualizationData = {
          nodes: result.nodes || [],
          links: result.links || result.edges || []
        };
        this.graphData = focalData;
        this.focalViewActive = true;
        this.focalViewLoading = false;

        const stats = result.statistics || {};
        this.snackBar.open(
          `Focal view: ${stats.nodeCount || focalData.nodes.length} nodes, ` +
          `${stats.edgeCount || focalData.links.length} edges (radius=${stats.radius || this.focalRadius})`,
          'Dismiss', { duration: 3000 });
      },
      error: (err) => {
        this.focalViewLoading = false;
        const msg = err?.error?.error || err?.message || 'Subgraph build failed';
        this.snackBar.open('Focal view failed: ' + msg, 'Dismiss', { duration: 3000 });
      }
    });
  }

  exitFocalView(): void {
    this.focalViewActive = false;
    // Restore the full graph from cache; if not available, reload from backend
    if (this.preFocalData) {
      this.graphData = this.preFocalData;
      this.preFocalData = null;
    } else {
      this.loadGraph();
    }
    this.snackBar.open('Focal view exited', '', { duration: 1500 });
  }

  toggleStrengthBandFilter(band: StrengthBand): void {
    if (this.strengthBandFilter.has(band)) {
      this.strengthBandFilter.delete(band);
    } else {
      this.strengthBandFilter.add(band);
    }
    // Trigger re-filter
    if (this.fullGraphData) {
      this.graphData = this.applyFilters(this.fullGraphData, this.searchQuery || undefined);
    } else if (this.graphData) {
      this.loadGraph();
    }
  }

  onCreationTimeChange(): void {
    if (this.fullGraphData) {
      this.graphData = this.applyFilters(this.fullGraphData, this.searchQuery || undefined);
    }
  }

  onProvenanceFilterChange(): void {
    if (this.fullGraphData) {
      this.graphData = this.applyFilters(this.fullGraphData, this.searchQuery || undefined);
    }
  }

  /**
   * Schedule a debounced batch-verify of visible nodes.
   * Called when strength overlay is toggled on or when graph data changes while overlay is active.
   */
  private scheduleVisibleNodeVerify(): void {
    if (this.overlayDebounceTimer) {
      clearTimeout(this.overlayDebounceTimer);
    }
    this.overlayDebounceTimer = setTimeout(() => {
      this.batchVerifyVisibleNodes();
    }, 500);
  }

  /**
   * Batch-verify all visible (graphData) nodes that have not yet been verified.
   * Uses the batch endpoint to avoid per-node HTTP fan-out.
   */
  private batchVerifyVisibleNodes(): void {
    if (!this.graphData || !this.strengthOverlayEnabled) return;
    const newIds = this.graphData.nodes.map(n => n.id).filter(id => !this.verifiedNodeIds.has(id));
    if (newIds.length === 0) return;
    const BATCH_MAX = 50;
    const batch = newIds.slice(0, BATCH_MAX);
    this.kbGrounding.batchVerify({ factSheetId: this.factSheetId, atomKeys: batch })
      .pipe(catchError(() => of(null)), takeUntil(this.destroy$))
      .subscribe(resp => {
        if (resp?.results) {
          Object.entries(resp.results).forEach(([atomKey, summary]) => {
            this.verifiedNodeIds.add(atomKey);
            this.strengthBandMap = new Map(this.strengthBandMap).set(atomKey, confidenceToStrengthBand(summary.confidence));
          });
          if (newIds.length > BATCH_MAX) {
            this.scheduleVisibleNodeVerify();
          }
        }
      });
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // ATTRIBUTION & PREDICTION
  // ═══════════════════════════════════════════════════════════════════════════

  loadAttribution(): void {
    if (!this.selectedNode) return;
    this.attributionLoading = true;
    this.attributionError = null;
    this.explainTrail = null;
    this.explainTrailLoading = true;

    const nodeId = this.selectedNode.id;

    this.attributionService.explainQuick(nodeId, {
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
        const message = this.extractHttpError(err, 'Attribution query failed');
        console.error('Attribution query failed:', err);
        this.attributionError = message;
        this.attributionLoading = false;
        this.snackBar.open(message, 'Dismiss', { duration: 6000 });
      }
    });

    // In parallel: fetch a unified ReasoningTrail via POST /api/explain.
    // A bare node id is routed as HYBRID by ExplainController; fallback GROUNDING
    // fires only if HYBRID produces no trail (handled server-side by the orchestrator).
    const explainReq: UnifiedExplainRequest = { target: nodeId, mode: 'HYBRID' };
    this.kbGrounding.unifiedExplain(explainReq)
      .pipe(takeUntil(this.destroy$), catchError(() => of(null)))
      .subscribe(resp => {
        this.explainTrail = resp?.trail ?? null;
        this.explainTrailLoading = false;
      });
  }

  /**
   * Extract a human-readable message from an Angular HttpErrorResponse. The backend
   * returns a structured { error, message, type, ... } body for attribution / Bayesian
   * failures (err.error) — surface that instead of Angular's opaque
   * "Http failure response for ...: 500 ..." wrapper string.
   */
  private extractHttpError(err: any, fallback: string): string {
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
    this.predictionError = null;

    this.attributionService.predictQuick(this.selectedNode.id).pipe(
      takeUntil(this.destroy$)
    ).subscribe({
      next: (result) => {
        this.predictionResult = result;
        this.predictionLoading = false;
      },
      error: (err) => {
        const message = this.extractHttpError(err, 'Prediction query failed');
        console.error('Prediction query failed:', err);
        this.predictionError = message;
        this.predictionLoading = false;
        this.snackBar.open(message, 'Dismiss', { duration: 6000 });
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

  toCitation(metadata: Record<string, any> | undefined, sourceRef?: string): Citation | null {
    const c: Citation = {};
    if (sourceRef) c.sourceName = sourceRef;
    if (metadata) {
      const sid = metadata['_sourceDocumentId'] || metadata['source_id'];
      if (sid) c.sourceId = String(sid);
      if (metadata['_crawlRunId']) c.crawlRunId = String(metadata['_crawlRunId']);
      if (metadata['_basisType']) c.basisType = String(metadata['_basisType']);
      if (metadata['page_number'] != null) c.pageNumber = Number(metadata['page_number']);
      if (metadata['chunk_index'] != null) c.chunkIndex = Number(metadata['chunk_index']);
      if (metadata['confidence'] != null) c.confidence = Number(metadata['confidence']);
      if (metadata['provenance'] && typeof metadata['provenance'] === 'object') c.provenance = metadata['provenance'];
    }
    if (!c.sourceName && !c.sourceId && !c.basisType && c.confidence == null) return null;
    return c;
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
