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

import {
  Component,
  Inject,
  ElementRef,
  ViewChild,
  AfterViewInit,
  OnDestroy,
  ChangeDetectorRef
} from '@angular/core';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import * as d3 from 'd3';
import { CompilerService, GraphInfoResponse, OpInfo } from '../../services/compiler.service';

// ==================== Graph data structures ====================

interface GraphNode {
  id: string;
  label: string;
  fullLabel: string;
  opType: string;
  executionOrder: number;
  x: number;
  y: number;
  width: number;
  height: number;
  inputs: string[];
  outputs: string[];
}

interface GraphEdge {
  source: string;
  target: string;
  sourceNode?: GraphNode;
  targetNode?: GraphNode;
}

// ==================== Op type color mapping ====================

function getOpColor(opType: string): string {
  const lower = opType.toLowerCase();
  if (/matmul|mmul|linear|dense|gemm/.test(lower)) return '#2196F3';
  if (/relu|gelu|sigmoid|tanh|softmax|silu|swish|mish|elu/.test(lower)) return '#FF9800';
  if (/layernorm|batchnorm|layer_norm|batch_norm|norm/.test(lower)) return '#9C27B0';
  if (/attention|sdpa|multi_head/.test(lower)) return '#E91E63';
  if (/embedding|lookup|gather/.test(lower)) return '#00BCD4';
  if (/reshape|transpose|permute|concat|slice|split|squeeze|expand|flatten/.test(lower)) return '#607D8B';
  if (/add|multiply|subtract|divide|pow|sqrt/.test(lower)) return '#795548';
  if (/conv|convolution/.test(lower)) return '#3F51B5';
  if (/pool|avgpool|maxpool/.test(lower)) return '#009688';
  if (/dropout|identity/.test(lower)) return '#BDBDBD';
  return '#78909C';
}

function getOpColorLight(opType: string): string {
  const lower = opType.toLowerCase();
  if (/matmul|mmul|linear|dense|gemm/.test(lower)) return '#E3F2FD';
  if (/relu|gelu|sigmoid|tanh|softmax|silu|swish|mish|elu/.test(lower)) return '#FFF3E0';
  if (/layernorm|batchnorm|layer_norm|batch_norm|norm/.test(lower)) return '#F3E5F5';
  if (/attention|sdpa|multi_head/.test(lower)) return '#FCE4EC';
  if (/embedding|lookup|gather/.test(lower)) return '#E0F7FA';
  if (/reshape|transpose|permute|concat|slice|split|squeeze|expand|flatten/.test(lower)) return '#ECEFF1';
  if (/add|multiply|subtract|divide|pow|sqrt/.test(lower)) return '#EFEBE9';
  if (/conv|convolution/.test(lower)) return '#E8EAF6';
  if (/pool|avgpool|maxpool/.test(lower)) return '#E0F2F1';
  if (/dropout|identity/.test(lower)) return '#F5F5F5';
  return '#ECEFF1';
}

@Component({
  selector: 'app-model-graph-dialog',
  standalone: false,
  template: `
    <div class="dialog-container">
      <!-- Header -->
      <div class="dialog-header">
        <div class="header-left">
          <mat-icon class="header-icon">account_tree</mat-icon>
          <div class="header-text">
            <h2>{{ data.modelId }}</h2>
            <span class="header-stats" *ngIf="graphInfo">
              {{ graphInfo.totalOps }} operations &middot; {{ graphInfo.totalVariables }} variables
              <span *ngIf="graphInfo.modelSizeBytes"> &middot; {{ formatBytes(graphInfo.modelSizeBytes) }}</span>
            </span>
          </div>
        </div>
        <button mat-icon-button (click)="dialogRef.close()" matTooltip="Close">
          <mat-icon>close</mat-icon>
        </button>
      </div>

      <!-- Loading -->
      <div class="loading-state" *ngIf="loading">
        <mat-spinner diameter="40"></mat-spinner>
        <span>Loading computation graph...</span>
      </div>

      <!-- Error -->
      <div class="error-state" *ngIf="errorMsg">
        <mat-icon>error_outline</mat-icon>
        <span>{{ errorMsg }}</span>
        <button mat-button color="primary" (click)="loadGraph()">Retry</button>
      </div>

      <!-- Graph Area -->
      <div class="graph-area" *ngIf="!loading && !errorMsg" (keydown)="onKeyDown($event)" tabindex="0" #containerRef>
        <!-- Toolbar -->
        <div class="graph-toolbar">
          <div class="toolbar-left">
            <button mat-icon-button (click)="zoomIn()" matTooltip="Zoom In">
              <mat-icon>add</mat-icon>
            </button>
            <button mat-icon-button (click)="zoomOut()" matTooltip="Zoom Out">
              <mat-icon>remove</mat-icon>
            </button>
            <button mat-icon-button (click)="fitToView()" matTooltip="Fit to View">
              <mat-icon>fit_screen</mat-icon>
            </button>
            <span class="zoom-level">{{ Math.round(currentZoom * 100) }}%</span>
          </div>
          <div class="toolbar-center">
            <button mat-icon-button
                    (click)="toggleFindSidebar()"
                    matTooltip="Find (Ctrl+F)"
                    [class.active]="showFindSidebar">
              <mat-icon>search</mat-icon>
            </button>
          </div>
          <div class="toolbar-right">
            <span class="graph-stats">{{ nodeCount }} ops</span>
          </div>
        </div>

        <!-- Graph Content -->
        <div class="graph-content">
          <div class="graph-canvas" #graphCanvas></div>

          <!-- Find Sidebar -->
          <div class="find-sidebar" [class.open]="showFindSidebar">
            <div class="find-header">
              <span>Find</span>
              <button mat-icon-button (click)="toggleFindSidebar()">
                <mat-icon>close</mat-icon>
              </button>
            </div>
            <div class="find-search">
              <input type="text"
                     placeholder="Search operations..."
                     [(ngModel)]="searchQuery"
                     (input)="onSearchInput()"
                     (keydown.enter)="selectNextResult($event)"
                     (keydown.escape)="toggleFindSidebar()"
                     #searchInput />
              <span class="result-count" *ngIf="searchQuery && searchResults.length > 0">
                {{ searchResults.length }}
              </span>
            </div>
            <div class="find-results" *ngIf="searchQuery">
              <div *ngIf="searchResults.length === 0" class="no-results">No results</div>
              <div class="result-list">
                <div *ngFor="let result of searchResults; let i = index"
                     class="result-item"
                     [class.selected]="i === currentSearchIndex"
                     (click)="selectResult(i)">
                  <span class="result-order">{{ result.executionOrder }}</span>
                  <div class="result-info">
                    <span class="result-name">{{ result.fullLabel }}</span>
                    <span class="result-type">{{ result.opType }}</span>
                  </div>
                </div>
              </div>
            </div>
          </div>

          <!-- Node Details Panel -->
          <div class="details-panel" *ngIf="selectedNode && !showFindSidebar">
            <div class="details-header">
              <span class="details-title">{{ selectedNode.fullLabel }}</span>
              <button mat-icon-button (click)="clearSelection()">
                <mat-icon>close</mat-icon>
              </button>
            </div>
            <div class="details-content">
              <div class="detail-row">
                <span class="detail-label">Execution Order</span>
                <span class="detail-value">{{ selectedNode.executionOrder }}</span>
              </div>
              <div class="detail-row">
                <span class="detail-label">Operation Type</span>
                <span class="detail-value op-type-chip" [style.color]="getOpColor(selectedNode.opType)">
                  {{ selectedNode.opType }}
                </span>
              </div>
              <div class="detail-row" *ngIf="selectedNode.inputs?.length">
                <span class="detail-label">Inputs</span>
                <span class="detail-value mono-text">{{ selectedNode.inputs.join(', ') }}</span>
              </div>
              <div class="detail-row" *ngIf="selectedNode.outputs?.length">
                <span class="detail-label">Outputs</span>
                <span class="detail-value mono-text">{{ selectedNode.outputs.join(', ') }}</span>
              </div>
            </div>
          </div>
        </div>

        <!-- Legend -->
        <div class="graph-legend">
          <span class="legend-item" *ngFor="let cat of legendCategories">
            <span class="legend-dot" [style.background]="cat.color"></span>
            {{ cat.label }}
          </span>
        </div>
      </div>
    </div>
  `,
  styles: [`
    .dialog-container {
      display: flex;
      flex-direction: column;
      height: 80vh;
      min-height: 500px;
    }

    /* ==================== Header ==================== */
    .dialog-header {
      display: flex;
      align-items: center;
      justify-content: space-between;
      padding: 16px 20px;
      border-bottom: 1px solid #e0e0e0;
      background: linear-gradient(135deg, #f8fafc 0%, #f1f5f9 100%);
    }

    .header-left {
      display: flex;
      align-items: center;
      gap: 12px;
    }

    .header-icon {
      color: #1976d2;
      font-size: 28px;
      width: 28px;
      height: 28px;
    }

    .header-text h2 {
      margin: 0;
      font-size: 1rem;
      font-weight: 600;
      color: #333;
      font-family: 'SF Mono', Monaco, Consolas, monospace;
    }

    .header-stats {
      font-size: 0.78rem;
      color: #888;
    }

    /* ==================== Loading / Error ==================== */
    .loading-state, .error-state {
      display: flex;
      flex-direction: column;
      align-items: center;
      justify-content: center;
      flex: 1;
      gap: 12px;
      color: #888;
      font-size: 0.9rem;
    }

    .error-state { color: #d32f2f; }

    .error-state mat-icon {
      font-size: 36px;
      width: 36px;
      height: 36px;
    }

    /* ==================== Graph Area ==================== */
    .graph-area {
      display: flex;
      flex-direction: column;
      flex: 1;
      overflow: hidden;
      outline: none;
      position: relative;
    }

    .graph-toolbar {
      display: flex;
      align-items: center;
      justify-content: space-between;
      padding: 4px 12px;
      background: #fff;
      border-bottom: 1px solid #e0e0e0;
      min-height: 44px;
    }

    .toolbar-left, .toolbar-center, .toolbar-right {
      display: flex;
      align-items: center;
      gap: 4px;
    }

    .toolbar-left button, .toolbar-center button { color: #616161; }
    .toolbar-center button.active { background: #e3f2fd; color: #1976d2; }
    .zoom-level { font-size: 12px; color: #9e9e9e; margin-left: 8px; min-width: 40px; }
    .graph-stats { font-size: 12px; color: #757575; }

    .graph-content {
      flex: 1;
      position: relative;
      overflow: hidden;
    }

    .graph-canvas {
      width: 100%;
      height: 100%;
      cursor: grab;
      background: #fafafa;
    }

    .graph-canvas:active { cursor: grabbing; }

    /* ==================== Find Sidebar ==================== */
    .find-sidebar {
      position: absolute;
      top: 0;
      right: 0;
      width: 280px;
      height: 100%;
      background: #fff;
      border-left: 1px solid #e0e0e0;
      transform: translateX(100%);
      transition: transform 0.2s ease;
      display: flex;
      flex-direction: column;
      box-shadow: -2px 0 8px rgba(0,0,0,0.1);
      z-index: 10;
    }

    .find-sidebar.open { transform: translateX(0); }

    .find-header {
      display: flex;
      align-items: center;
      justify-content: space-between;
      padding: 8px 12px;
      border-bottom: 1px solid #e0e0e0;
      font-weight: 500;
      color: #424242;
    }

    .find-search {
      display: flex;
      align-items: center;
      padding: 8px 12px;
      gap: 8px;
      border-bottom: 1px solid #e0e0e0;
    }

    .find-search input {
      flex: 1;
      border: 1px solid #e0e0e0;
      border-radius: 4px;
      padding: 8px 12px;
      font-size: 13px;
      outline: none;
    }

    .find-search input:focus { border-color: #1976d2; }

    .result-count {
      background: #1976d2;
      color: white;
      font-size: 11px;
      padding: 2px 8px;
      border-radius: 10px;
      font-weight: 500;
    }

    .find-results { flex: 1; overflow-y: auto; }
    .no-results { padding: 20px; text-align: center; color: #9e9e9e; font-size: 13px; }
    .result-list { padding: 4px; }

    .result-item {
      display: flex;
      align-items: center;
      padding: 8px 12px;
      gap: 12px;
      cursor: pointer;
      border-radius: 4px;
      transition: background 0.1s;
    }

    .result-item:hover { background: #f5f5f5; }
    .result-item.selected { background: #e3f2fd; }

    .result-order {
      font-size: 11px;
      font-weight: 600;
      color: #9e9e9e;
      min-width: 24px;
    }

    .result-info { flex: 1; min-width: 0; }

    .result-name {
      display: block;
      font-size: 12px;
      font-family: 'SF Mono', Monaco, monospace;
      color: #212121;
      white-space: nowrap;
      overflow: hidden;
      text-overflow: ellipsis;
    }

    .result-type {
      display: block;
      font-size: 10px;
      color: #757575;
      text-transform: uppercase;
    }

    /* ==================== Details Panel ==================== */
    .details-panel {
      position: absolute;
      top: 0;
      right: 0;
      width: 280px;
      background: #fff;
      border-left: 1px solid #e0e0e0;
      box-shadow: -2px 0 8px rgba(0,0,0,0.1);
      display: flex;
      flex-direction: column;
      max-height: 100%;
      z-index: 10;
    }

    .details-header {
      display: flex;
      align-items: center;
      justify-content: space-between;
      padding: 12px;
      border-bottom: 1px solid #e0e0e0;
    }

    .details-title {
      font-size: 13px;
      font-weight: 500;
      font-family: 'SF Mono', Monaco, monospace;
      color: #212121;
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
    }

    .details-content { padding: 12px; overflow-y: auto; }

    .detail-row { margin-bottom: 12px; }

    .detail-label {
      display: block;
      font-size: 10px;
      text-transform: uppercase;
      color: #9e9e9e;
      margin-bottom: 4px;
    }

    .detail-value { font-size: 13px; color: #424242; }

    .op-type-chip { font-weight: 600; }

    .mono-text {
      font-family: 'SF Mono', Monaco, monospace;
      font-size: 11px;
      word-break: break-all;
    }

    /* ==================== Legend ==================== */
    .graph-legend {
      display: flex;
      flex-wrap: wrap;
      gap: 12px;
      padding: 6px 16px;
      background: #fff;
      border-top: 1px solid #e0e0e0;
      font-size: 0.72rem;
      color: #666;
    }

    .legend-item {
      display: flex;
      align-items: center;
      gap: 4px;
      white-space: nowrap;
    }

    .legend-dot {
      width: 10px;
      height: 10px;
      border-radius: 2px;
      flex-shrink: 0;
    }

    /* ==================== SVG Styles ==================== */
    :host ::ng-deep .node-group { cursor: pointer; }

    :host ::ng-deep .node-rect {
      stroke-width: 1;
      rx: 4;
      ry: 4;
    }

    :host ::ng-deep .node-rect:hover { stroke-width: 2; }

    :host ::ng-deep .node-rect.selected {
      stroke: #1976d2 !important;
      stroke-width: 2;
    }

    :host ::ng-deep .node-rect.highlighted {
      stroke: #ff9800 !important;
      stroke-width: 2;
    }

    :host ::ng-deep .node-label {
      font-family: 'SF Mono', Monaco, Consolas, monospace;
      font-size: 11px;
      fill: #212121;
      pointer-events: none;
    }

    :host ::ng-deep .node-type {
      font-family: -apple-system, BlinkMacSystemFont, sans-serif;
      font-size: 9px;
      fill: #757575;
      pointer-events: none;
    }

    :host ::ng-deep .node-order {
      font-family: -apple-system, BlinkMacSystemFont, sans-serif;
      font-size: 9px;
      fill: #9e9e9e;
      pointer-events: none;
    }

    :host ::ng-deep .edge-path {
      fill: none;
      stroke: #bdbdbd;
      stroke-width: 1;
    }

    :host ::ng-deep .edge-path.highlighted {
      stroke: #1976d2;
      stroke-width: 1.5;
    }

    :host ::ng-deep .edge-arrow { fill: #bdbdbd; }

    :host ::ng-deep .dimmed { opacity: 0.15; }
  `]
})
export class ModelGraphDialogComponent implements AfterViewInit, OnDestroy {
  @ViewChild('graphCanvas') graphCanvas!: ElementRef<HTMLDivElement>;
  @ViewChild('searchInput') searchInput!: ElementRef<HTMLInputElement>;
  @ViewChild('containerRef') containerRef!: ElementRef<HTMLDivElement>;

  // Data
  graphInfo: GraphInfoResponse | null = null;
  loading = true;
  errorMsg: string | null = null;

  // Graph
  nodes: GraphNode[] = [];
  edges: GraphEdge[] = [];
  nodeMap = new Map<string, GraphNode>();

  // UI State
  selectedNode: GraphNode | null = null;
  nodeCount = 0;
  currentZoom = 1;
  showFindSidebar = false;

  // Search
  searchQuery = '';
  searchResults: GraphNode[] = [];
  currentSearchIndex = -1;

  // D3
  private svg: d3.Selection<SVGSVGElement, unknown, null, undefined> | null = null;
  private g: d3.Selection<SVGGElement, unknown, null, undefined> | null = null;
  private zoomBehavior: d3.ZoomBehavior<SVGSVGElement, unknown> | null = null;

  // Layout constants (Sugiyama-style DAG)
  private readonly NODE_WIDTH = 180;
  private readonly NODE_HEIGHT = 40;
  private readonly HORIZONTAL_GAP = 80;
  private readonly VERTICAL_GAP = 60;
  private readonly PADDING = 60;

  Math = Math;

  legendCategories = [
    { label: 'MatMul/Linear', color: '#2196F3' },
    { label: 'Activation', color: '#FF9800' },
    { label: 'Normalization', color: '#9C27B0' },
    { label: 'Attention', color: '#E91E63' },
    { label: 'Embedding', color: '#00BCD4' },
    { label: 'Reshape/Transform', color: '#607D8B' },
    { label: 'Arithmetic', color: '#795548' },
    { label: 'Convolution', color: '#3F51B5' },
    { label: 'Pooling', color: '#009688' },
    { label: 'Other', color: '#78909C' }
  ];

  getOpColor = getOpColor;

  constructor(
    public dialogRef: MatDialogRef<ModelGraphDialogComponent>,
    @Inject(MAT_DIALOG_DATA) public data: { modelId: string },
    private compilerService: CompilerService,
    private cdr: ChangeDetectorRef
  ) {}

  ngAfterViewInit(): void {
    this.loadGraph();
  }

  ngOnDestroy(): void {
    if (this.svg) this.svg.remove();
  }

  loadGraph(): void {
    this.loading = true;
    this.errorMsg = null;
    this.cdr.detectChanges();

    this.compilerService.getGraphInfo(this.data.modelId).subscribe({
      next: (info) => {
        this.graphInfo = info;
        this.loading = false;
        this.cdr.detectChanges();
        setTimeout(() => this.buildAndRender(info), 0);
      },
      error: (err) => {
        this.errorMsg = err.message || 'Failed to load graph';
        this.loading = false;
        this.cdr.detectChanges();
      }
    });
  }

  // ==================== Graph Building ====================

  private buildAndRender(info: GraphInfoResponse): void {
    const ops = info.ops || [];
    if (ops.length === 0) {
      this.errorMsg = 'No operations in model graph';
      this.cdr.detectChanges();
      return;
    }

    this.buildGraphData(ops);
    this.initializeSvg();
    this.computeLayout();
    this.renderGraph();
    setTimeout(() => this.fitToView(), 150);
  }

  private buildGraphData(ops: OpInfo[]): void {
    this.nodes = [];
    this.edges = [];
    this.nodeMap.clear();

    // Map output name -> producing op
    const outputToOp = new Map<string, string>();
    for (const op of ops) {
      if (op.outputs) {
        for (const out of op.outputs) {
          outputToOp.set(out, op.name);
        }
      }
    }

    // Create nodes
    ops.forEach((op, index) => {
      const node: GraphNode = {
        id: op.name,
        label: this.truncateLabel(op.name, 22),
        fullLabel: op.name,
        opType: op.opType,
        executionOrder: index,
        x: 0,
        y: 0,
        width: this.NODE_WIDTH,
        height: this.NODE_HEIGHT,
        inputs: op.inputs || [],
        outputs: op.outputs || []
      };
      this.nodes.push(node);
      this.nodeMap.set(op.name, node);
    });

    // Create edges based on data flow
    const edgeSet = new Set<string>();
    for (const op of ops) {
      if (op.inputs) {
        for (const inp of op.inputs) {
          const srcOp = outputToOp.get(inp);
          if (srcOp && srcOp !== op.name && this.nodeMap.has(srcOp)) {
            const key = `${srcOp}->${op.name}`;
            if (!edgeSet.has(key)) {
              edgeSet.add(key);
              this.edges.push({
                source: srcOp,
                target: op.name,
                sourceNode: this.nodeMap.get(srcOp),
                targetNode: this.nodeMap.get(op.name)
              });
            }
          }
        }
      }
    }

    this.nodeCount = this.nodes.length;
  }

  // ==================== DAG Layout (Sugiyama-style) ====================

  private computeLayout(): void {
    if (this.nodes.length === 0) return;

    // 1. Compute predecessors
    const predecessors = new Map<string, Set<string>>();
    this.nodes.forEach(n => predecessors.set(n.id, new Set()));
    this.edges.forEach(e => {
      predecessors.get(e.target)?.add(e.source);
    });

    // 2. Assign layers via longest-path from sources (topological layering)
    const nodeLayer = new Map<string, number>();
    const computeLayer = (nodeId: string, memo: Map<string, number>): number => {
      if (memo.has(nodeId)) return memo.get(nodeId)!;
      const preds = predecessors.get(nodeId);
      if (!preds || preds.size === 0) {
        memo.set(nodeId, 0);
        return 0;
      }
      let maxPredLayer = -1;
      for (const pred of preds) {
        if (this.nodeMap.has(pred)) {
          maxPredLayer = Math.max(maxPredLayer, computeLayer(pred, memo));
        }
      }
      const layer = maxPredLayer + 1;
      memo.set(nodeId, layer);
      return layer;
    };

    const layerMemo = new Map<string, number>();
    this.nodes.forEach(n => {
      nodeLayer.set(n.id, computeLayer(n.id, layerMemo));
    });

    // 3. Group nodes by layer
    const layers: GraphNode[][] = [];
    this.nodes.forEach(node => {
      const layer = nodeLayer.get(node.id) || 0;
      while (layers.length <= layer) layers.push([]);
      layers[layer].push(node);
    });

    // 4. Sort within layers by execution order
    layers.forEach(layer => {
      layer.sort((a, b) => a.executionOrder - b.executionOrder);
    });

    // 5. Barycenter heuristic to minimize edge crossings (4 passes)
    for (let pass = 0; pass < 4; pass++) {
      for (let i = 1; i < layers.length; i++) {
        this.orderLayerByBarycenter(layers[i], layers[i - 1], true);
      }
      for (let i = layers.length - 2; i >= 0; i--) {
        this.orderLayerByBarycenter(layers[i], layers[i + 1], false);
      }
    }

    // 6. Calculate positions
    const maxNodesInLayer = Math.max(...layers.map(l => l.length));
    const totalWidth = maxNodesInLayer * (this.NODE_WIDTH + this.HORIZONTAL_GAP);

    layers.forEach((layer, layerIndex) => {
      const layerWidth = layer.length * (this.NODE_WIDTH + this.HORIZONTAL_GAP) - this.HORIZONTAL_GAP;
      const startX = (totalWidth - layerWidth) / 2 + this.PADDING;

      layer.forEach((node, nodeIndex) => {
        node.x = startX + nodeIndex * (this.NODE_WIDTH + this.HORIZONTAL_GAP);
        node.y = this.PADDING + layerIndex * (this.NODE_HEIGHT + this.VERTICAL_GAP);
      });
    });
  }

  private orderLayerByBarycenter(layer: GraphNode[], fixedLayer: GraphNode[], isForward: boolean): void {
    const positions = new Map<string, number>();
    fixedLayer.forEach((node, index) => positions.set(node.id, index));

    layer.forEach(node => {
      const connected = isForward
        ? this.edges.filter(e => e.target === node.id).map(e => e.source)
        : this.edges.filter(e => e.source === node.id).map(e => e.target);

      if (connected.length > 0) {
        let sum = 0, count = 0;
        connected.forEach(id => {
          if (positions.has(id)) { sum += positions.get(id)!; count++; }
        });
        if (count > 0) (node as any)._bc = sum / count;
      }
    });

    layer.sort((a, b) => {
      const aBC = (a as any)._bc;
      const bBC = (b as any)._bc;
      if (aBC !== undefined && bBC !== undefined) return aBC - bBC;
      if (aBC !== undefined) return -1;
      if (bBC !== undefined) return 1;
      return a.executionOrder - b.executionOrder;
    });

    // Clean up temp property
    layer.forEach(n => delete (n as any)._bc);
  }

  // ==================== SVG Rendering ====================

  private initializeSvg(): void {
    if (!this.graphCanvas) return;
    const container = this.graphCanvas.nativeElement;
    container.innerHTML = '';

    this.svg = d3.select(container)
      .append('svg')
      .attr('width', '100%')
      .attr('height', '100%');

    const defs = this.svg.append('defs');
    defs.append('marker')
      .attr('id', 'dag-arrow')
      .attr('viewBox', '0 -5 10 10')
      .attr('refX', 8)
      .attr('refY', 0)
      .attr('markerWidth', 6)
      .attr('markerHeight', 6)
      .attr('orient', 'auto')
      .append('path')
      .attr('d', 'M0,-4L10,0L0,4')
      .attr('class', 'edge-arrow');

    this.g = this.svg.append('g').attr('class', 'graph-layer');

    this.zoomBehavior = d3.zoom<SVGSVGElement, unknown>()
      .scaleExtent([0.05, 4])
      .on('zoom', (event) => {
        this.g!.attr('transform', event.transform);
        this.currentZoom = event.transform.k;
      });

    this.svg.call(this.zoomBehavior);
  }

  private renderGraph(): void {
    if (!this.g || this.nodes.length === 0) return;
    this.g.selectAll('*').remove();

    // Edges (behind nodes)
    const edgesGroup = this.g.append('g').attr('class', 'edges');
    edgesGroup.selectAll('path')
      .data(this.edges)
      .enter()
      .append('path')
      .attr('class', 'edge-path')
      .attr('d', (d) => this.generateEdgePath(d))
      .attr('marker-end', 'url(#dag-arrow)');

    // Nodes
    const nodesGroup = this.g.append('g').attr('class', 'nodes');
    const nodeGroups = nodesGroup.selectAll('g')
      .data(this.nodes)
      .enter()
      .append('g')
      .attr('class', 'node-group')
      .attr('transform', d => `translate(${d.x}, ${d.y})`)
      .on('click', (event, d) => this.onNodeClick(d));

    // Node rectangle with op-type color
    nodeGroups.append('rect')
      .attr('class', 'node-rect')
      .attr('width', d => d.width)
      .attr('height', d => d.height)
      .attr('fill', d => getOpColorLight(d.opType))
      .attr('stroke', d => getOpColor(d.opType));

    // Left color accent bar
    nodeGroups.append('rect')
      .attr('x', 0)
      .attr('y', 0)
      .attr('width', 4)
      .attr('height', d => d.height)
      .attr('fill', d => getOpColor(d.opType))
      .attr('rx', 2);

    // Execution order badge
    nodeGroups.append('text')
      .attr('class', 'node-order')
      .attr('x', 12)
      .attr('y', 14)
      .text(d => `#${d.executionOrder}`);

    // Node label
    nodeGroups.append('text')
      .attr('class', 'node-label')
      .attr('x', d => d.width / 2)
      .attr('y', 17)
      .attr('text-anchor', 'middle')
      .text(d => d.label);

    // Op type sub-label
    nodeGroups.append('text')
      .attr('class', 'node-type')
      .attr('x', d => d.width / 2)
      .attr('y', 32)
      .attr('text-anchor', 'middle')
      .text(d => d.opType);
  }

  private generateEdgePath(edge: GraphEdge): string {
    const source = this.nodeMap.get(edge.source);
    const target = this.nodeMap.get(edge.target);
    if (!source || !target) return '';

    const x1 = source.x + source.width / 2;
    const y1 = source.y + source.height;
    const x2 = target.x + target.width / 2;
    const y2 = target.y;

    const midY = (y1 + y2) / 2;
    return `M ${x1} ${y1} C ${x1} ${midY}, ${x2} ${midY}, ${x2} ${y2}`;
  }

  // ==================== Interaction ====================

  private onNodeClick(node: GraphNode): void {
    this.selectedNode = node;
    this.highlightNode(node);
    this.cdr.detectChanges();
  }

  private highlightNode(node: GraphNode): void {
    if (!this.g) return;

    this.g.selectAll('.node-rect')
      .classed('selected', false)
      .classed('highlighted', false)
      .classed('dimmed', false);

    this.g.selectAll('.edge-path')
      .classed('highlighted', false)
      .classed('dimmed', false);

    this.g.selectAll('.node-group')
      .classed('dimmed', false);

    // Selected node
    this.g.selectAll('.node-group')
      .filter((d: any) => d.id === node.id)
      .select('.node-rect')
      .classed('selected', true);

    // Find connected nodes
    const connectedNodes = new Set<string>([node.id]);
    this.edges.forEach(edge => {
      if (edge.source === node.id || edge.target === node.id) {
        connectedNodes.add(edge.source);
        connectedNodes.add(edge.target);
      }
    });

    // Dim unconnected
    this.g.selectAll('.node-group')
      .filter((d: any) => !connectedNodes.has(d.id))
      .classed('dimmed', true);

    this.g.selectAll('.edge-path')
      .each((d: any, i, pathNodes) => {
        const isConnected = d.source === node.id || d.target === node.id;
        d3.select(pathNodes[i])
          .classed('highlighted', isConnected)
          .classed('dimmed', !isConnected);
      });
  }

  clearSelection(): void {
    this.selectedNode = null;
    if (!this.g) return;

    this.g.selectAll('.node-rect')
      .classed('selected', false)
      .classed('highlighted', false)
      .classed('dimmed', false);

    this.g.selectAll('.node-group')
      .classed('dimmed', false);

    this.g.selectAll('.edge-path')
      .classed('highlighted', false)
      .classed('dimmed', false);
  }

  // ==================== Zoom ====================

  zoomIn(): void {
    if (!this.svg || !this.zoomBehavior) return;
    this.svg.transition().duration(300).call(this.zoomBehavior.scaleBy, 1.3);
  }

  zoomOut(): void {
    if (!this.svg || !this.zoomBehavior) return;
    this.svg.transition().duration(300).call(this.zoomBehavior.scaleBy, 0.7);
  }

  fitToView(): void {
    if (!this.svg || !this.g || !this.zoomBehavior || !this.graphCanvas || this.nodes.length === 0) return;

    const container = this.graphCanvas.nativeElement;
    const width = container.clientWidth;
    const height = container.clientHeight;

    let minX = Infinity, maxX = -Infinity, minY = Infinity, maxY = -Infinity;
    this.nodes.forEach(node => {
      minX = Math.min(minX, node.x);
      maxX = Math.max(maxX, node.x + node.width);
      minY = Math.min(minY, node.y);
      maxY = Math.max(maxY, node.y + node.height);
    });

    const graphWidth = maxX - minX + 100;
    const graphHeight = maxY - minY + 100;

    const fitScale = Math.min(width / graphWidth, height / graphHeight);
    const scale = Math.max(0.05, Math.min(fitScale, 1.5)) * 0.9;

    const centerX = (minX + maxX) / 2;
    const centerY = (minY + maxY) / 2;

    const translateX = width / 2 - centerX * scale;
    const translateY = height / 2 - centerY * scale;

    this.svg.transition()
      .duration(500)
      .call(this.zoomBehavior.transform, d3.zoomIdentity.translate(translateX, translateY).scale(scale));
  }

  // ==================== Search ====================

  onKeyDown(event: KeyboardEvent): void {
    if ((event.ctrlKey || event.metaKey) && event.key === 'f') {
      event.preventDefault();
      this.toggleFindSidebar();
    }
    if (event.key === 'Escape') {
      if (this.showFindSidebar) this.toggleFindSidebar();
      else if (this.selectedNode) this.clearSelection();
    }
  }

  toggleFindSidebar(): void {
    this.showFindSidebar = !this.showFindSidebar;
    if (this.showFindSidebar) {
      setTimeout(() => this.searchInput?.nativeElement?.focus(), 100);
    } else {
      this.clearSearch();
      this.containerRef?.nativeElement?.focus();
    }
  }

  onSearchInput(): void {
    const query = this.searchQuery.trim().toLowerCase();
    if (!query) {
      this.searchResults = [];
      this.currentSearchIndex = -1;
      this.clearSearchHighlights();
      return;
    }

    this.searchResults = this.nodes.filter(node =>
      node.id.toLowerCase().includes(query) ||
      node.fullLabel.toLowerCase().includes(query) ||
      node.opType.toLowerCase().includes(query)
    ).sort((a, b) => a.executionOrder - b.executionOrder);

    this.currentSearchIndex = -1;
    this.updateSearchHighlights();
  }

  selectResult(index: number): void {
    if (index < 0 || index >= this.searchResults.length) return;
    this.currentSearchIndex = index;
    this.focusOnNode(this.searchResults[index]);
    this.updateSearchHighlights();
  }

  selectNextResult(event: Event): void {
    event.preventDefault();
    if (this.searchResults.length === 0) return;
    this.currentSearchIndex = (this.currentSearchIndex + 1) % this.searchResults.length;
    this.focusOnNode(this.searchResults[this.currentSearchIndex]);
    this.updateSearchHighlights();
  }

  private clearSearch(): void {
    this.searchQuery = '';
    this.searchResults = [];
    this.currentSearchIndex = -1;
    this.clearSearchHighlights();
  }

  private updateSearchHighlights(): void {
    if (!this.g) return;
    const matchIds = new Set(this.searchResults.map(n => n.id));
    const currentId = this.currentSearchIndex >= 0 ? this.searchResults[this.currentSearchIndex]?.id : null;

    this.g.selectAll('.node-rect')
      .classed('highlighted', (d: any) => matchIds.has(d.id) && d.id !== currentId)
      .classed('selected', (d: any) => d.id === currentId)
      .classed('dimmed', (d: any) => this.searchResults.length > 0 && !matchIds.has(d.id));

    this.g.selectAll('.node-group')
      .classed('dimmed', (d: any) => this.searchResults.length > 0 && !matchIds.has(d.id));

    this.g.selectAll('.edge-path')
      .classed('dimmed', this.searchResults.length > 0);
  }

  private clearSearchHighlights(): void {
    if (!this.g) return;
    this.g.selectAll('.node-rect').classed('highlighted', false).classed('dimmed', false);
    this.g.selectAll('.node-group').classed('dimmed', false);
    this.g.selectAll('.edge-path').classed('dimmed', false);
  }

  private focusOnNode(node: GraphNode): void {
    if (!this.svg || !this.zoomBehavior || !this.graphCanvas) return;
    const container = this.graphCanvas.nativeElement;
    const width = container.clientWidth;
    const height = container.clientHeight;

    const scale = 1.2;
    const translateX = width / 2 - (node.x + node.width / 2) * scale;
    const translateY = height / 2 - (node.y + node.height / 2) * scale;

    this.svg.transition()
      .duration(300)
      .call(this.zoomBehavior.transform, d3.zoomIdentity.translate(translateX, translateY).scale(scale));

    this.selectedNode = node;
  }

  // ==================== Utilities ====================

  private truncateLabel(label: string, maxLength: number): string {
    if (label.length <= maxLength) return label;
    return label.substring(0, maxLength - 2) + '..';
  }

  formatBytes(bytes: number | undefined): string {
    if (!bytes) return '';
    if (bytes < 1024) return bytes + ' B';
    if (bytes < 1048576) return (bytes / 1024).toFixed(1) + ' KB';
    if (bytes < 1073741824) return (bytes / 1048576).toFixed(1) + ' MB';
    return (bytes / 1073741824).toFixed(2) + ' GB';
  }
}
