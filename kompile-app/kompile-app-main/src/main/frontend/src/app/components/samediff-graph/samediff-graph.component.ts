import {
  Component,
  Input,
  OnChanges,
  OnDestroy,
  SimpleChanges,
  ElementRef,
  ViewChild,
  AfterViewInit,
  ChangeDetectorRef
} from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { MatCardModule } from '@angular/material/card';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatTooltipModule } from '@angular/material/tooltip';
import { MatChipsModule } from '@angular/material/chips';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatBadgeModule } from '@angular/material/badge';
import * as d3 from 'd3';

interface GraphNode {
  id: string;
  label: string;
  fullLabel: string;
  nodeType: 'operation' | 'variable';
  opType?: string;
  opClass?: string;
  variableType?: string;
  dataType?: string;
  shape?: string;
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
  type: 'input' | 'output';
  sourceNode?: GraphNode;
  targetNode?: GraphNode;
}

interface SameDiffSummary {
  operations?: Array<{
    name: string;
    opType: string;
    opClass: string;
    executionOrder?: number;
    inputs?: string[];
    outputs?: string[];
  }>;
  variables?: Array<{
    name: string;
    variableType: string;
    dataType?: string;
    shape?: number[];
  }>;
  graphEdges?: Array<{
    source: string;
    target: string;
    type: 'input' | 'output';
    sourceExecutionOrder?: number;
    targetExecutionOrder?: number;
  }>;
  variableExecutionOrder?: { [key: string]: number };
  totalExecutionSteps?: number;
  inputs?: string[];
  outputs?: string[];
}

@Component({
  selector: 'app-samediff-graph',
  standalone: true,
  imports: [
    CommonModule,
    FormsModule,
    MatCardModule,
    MatButtonModule,
    MatIconModule,
    MatTooltipModule,
    MatChipsModule,
    MatFormFieldModule,
    MatInputModule,
    MatBadgeModule
  ],
  template: `
    <div class="graph-container" (keydown)="onKeyDown($event)" tabindex="0" #containerRef>
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

      <!-- Main Graph Area -->
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
            <input
              type="text"
              placeholder="Search operations..."
              [(ngModel)]="searchQuery"
              (input)="onSearchInput()"
              (keydown.enter)="selectNextResult($event)"
              (keydown.escape)="toggleFindSidebar()"
              #searchInput
            />
            <span class="result-count" *ngIf="searchQuery && searchResults.length > 0">
              {{ searchResults.length }}
            </span>
          </div>

          <div class="find-results" *ngIf="searchQuery">
            <div *ngIf="searchResults.length === 0" class="no-results">
              No results
            </div>
            <div class="result-list">
              <div
                *ngFor="let result of searchResults; let i = index"
                class="result-item"
                [class.selected]="i === currentSearchIndex"
                (click)="selectResult(i)">
                <span class="result-order">{{ result.executionOrder }}</span>
                <div class="result-info">
                  <span class="result-name">{{ result.fullLabel }}</span>
                  <span class="result-type">{{ result.opType || result.variableType }}</span>
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
            <div class="detail-row" *ngIf="selectedNode.nodeType === 'operation'">
              <span class="detail-label">Operation</span>
              <span class="detail-value">{{ selectedNode.opType }}</span>
            </div>
            <div class="detail-row" *ngIf="selectedNode.opClass">
              <span class="detail-label">Class</span>
              <span class="detail-value">{{ selectedNode.opClass }}</span>
            </div>
            <div class="detail-row" *ngIf="selectedNode.inputs?.length">
              <span class="detail-label">Inputs</span>
              <span class="detail-value inputs-list">{{ selectedNode.inputs.join(', ') }}</span>
            </div>
            <div class="detail-row" *ngIf="selectedNode.outputs?.length">
              <span class="detail-label">Outputs</span>
              <span class="detail-value outputs-list">{{ selectedNode.outputs.join(', ') }}</span>
            </div>
          </div>
        </div>
      </div>
    </div>
  `,
  styles: [`
    .graph-container {
      display: flex;
      flex-direction: column;
      height: 700px;
      background: #fafafa;
      border: 1px solid #e0e0e0;
      border-radius: 4px;
      overflow: hidden;
      outline: none;
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

    .toolbar-left button, .toolbar-center button {
      color: #616161;
    }

    .toolbar-center button.active {
      background: #e3f2fd;
      color: #1976d2;
    }

    .zoom-level {
      font-size: 12px;
      color: #9e9e9e;
      margin-left: 8px;
      min-width: 40px;
    }

    .graph-stats {
      font-size: 12px;
      color: #757575;
    }

    .graph-content {
      flex: 1;
      position: relative;
      overflow: hidden;
    }

    .graph-canvas {
      width: 100%;
      height: 100%;
      cursor: grab;
    }

    .graph-canvas:active {
      cursor: grabbing;
    }

    /* Find Sidebar */
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
    }

    .find-sidebar.open {
      transform: translateX(0);
    }

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

    .find-search input:focus {
      border-color: #1976d2;
    }

    .result-count {
      background: #1976d2;
      color: white;
      font-size: 11px;
      padding: 2px 8px;
      border-radius: 10px;
      font-weight: 500;
    }

    .find-results {
      flex: 1;
      overflow-y: auto;
    }

    .no-results {
      padding: 20px;
      text-align: center;
      color: #9e9e9e;
      font-size: 13px;
    }

    .result-list {
      padding: 4px;
    }

    .result-item {
      display: flex;
      align-items: center;
      padding: 8px 12px;
      gap: 12px;
      cursor: pointer;
      border-radius: 4px;
      transition: background 0.1s;
    }

    .result-item:hover {
      background: #f5f5f5;
    }

    .result-item.selected {
      background: #e3f2fd;
    }

    .result-order {
      font-size: 11px;
      font-weight: 600;
      color: #9e9e9e;
      min-width: 24px;
    }

    .result-info {
      flex: 1;
      min-width: 0;
    }

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

    /* Details Panel */
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

    .details-content {
      padding: 12px;
      overflow-y: auto;
    }

    .detail-row {
      margin-bottom: 12px;
    }

    .detail-label {
      display: block;
      font-size: 10px;
      text-transform: uppercase;
      color: #9e9e9e;
      margin-bottom: 4px;
    }

    .detail-value {
      font-size: 13px;
      color: #424242;
    }

    .inputs-list, .outputs-list {
      font-family: 'SF Mono', Monaco, monospace;
      font-size: 11px;
      word-break: break-all;
    }

    /* SVG Styles */
    :host ::ng-deep .node-group {
      cursor: pointer;
    }

    :host ::ng-deep .node-rect {
      fill: #fff;
      stroke: #bdbdbd;
      stroke-width: 1;
      rx: 4;
      ry: 4;
    }

    :host ::ng-deep .node-rect:hover {
      stroke: #1976d2;
      stroke-width: 2;
    }

    :host ::ng-deep .node-rect.selected {
      stroke: #1976d2;
      stroke-width: 2;
      fill: #e3f2fd;
    }

    :host ::ng-deep .node-rect.highlighted {
      stroke: #ff9800;
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

    :host ::ng-deep .edge-arrow {
      fill: #bdbdbd;
    }

    :host ::ng-deep .dimmed {
      opacity: 0.2;
    }
  `]
})
export class SameDiffGraphComponent implements OnChanges, OnDestroy, AfterViewInit {
  @Input() summaryData: SameDiffSummary | null = null;

  @ViewChild('graphCanvas') graphCanvas!: ElementRef<HTMLDivElement>;
  @ViewChild('searchInput') searchInput!: ElementRef<HTMLInputElement>;
  @ViewChild('containerRef') containerRef!: ElementRef<HTMLDivElement>;

  // Graph data
  nodes: GraphNode[] = [];
  edges: GraphEdge[] = [];
  nodeMap = new Map<string, GraphNode>();

  // UI State
  selectedNode: GraphNode | null = null;
  nodeCount = 0;
  currentZoom = 1;
  showFindSidebar = false;

  // Search state
  searchQuery = '';
  searchResults: GraphNode[] = [];
  currentSearchIndex = -1;

  // D3 elements
  private svg: d3.Selection<SVGSVGElement, unknown, null, undefined> | null = null;
  private g: d3.Selection<SVGGElement, unknown, null, undefined> | null = null;
  private zoom: d3.ZoomBehavior<SVGSVGElement, unknown> | null = null;

  // Layout constants - Netron-inspired spacing
  private readonly NODE_WIDTH = 180;
  private readonly NODE_HEIGHT = 40;
  private readonly HORIZONTAL_GAP = 80;
  private readonly VERTICAL_GAP = 60;
  private readonly PADDING = 60;

  Math = Math; // Expose Math to template

  constructor(private cdr: ChangeDetectorRef) {}

  ngAfterViewInit(): void {
    // Use setTimeout to avoid ExpressionChangedAfterItHasBeenCheckedError
    setTimeout(() => this.initializeGraph(), 0);
  }

  ngOnChanges(changes: SimpleChanges): void {
    if (changes['summaryData'] && this.graphCanvas) {
      this.processData();
      this.renderGraph();
    }
  }

  ngOnDestroy(): void {
    if (this.svg) {
      this.svg.remove();
    }
  }

  private initializeGraph(): void {
    if (!this.graphCanvas) return;

    const container = this.graphCanvas.nativeElement;
    container.innerHTML = '';

    this.svg = d3.select(container)
      .append('svg')
      .attr('width', '100%')
      .attr('height', '100%');

    // Add defs for arrow markers
    const defs = this.svg.append('defs');
    defs.append('marker')
      .attr('id', 'arrow')
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

    // Setup zoom
    this.zoom = d3.zoom<SVGSVGElement, unknown>()
      .scaleExtent([0.1, 4])
      .on('zoom', (event) => {
        this.g!.attr('transform', event.transform);
        this.currentZoom = event.transform.k;
      });

    this.svg.call(this.zoom);

    this.processData();
    this.renderGraph();
  }

  private processData(): void {
    this.nodes = [];
    this.edges = [];
    this.nodeMap.clear();

    if (!this.summaryData) return;

    const { operations, variables, graphEdges, variableExecutionOrder, inputs, outputs } = this.summaryData;

    // Build a map of variable name -> execution order from when it's produced
    const varExecOrder = new Map<string, number>();
    if (variableExecutionOrder) {
      Object.entries(variableExecutionOrder).forEach(([name, order]) => {
        varExecOrder.set(name, order);
      });
    }

    // Create operation nodes with their execution order from backend
    if (operations) {
      operations.forEach((op, index) => {
        const execOrder = op.executionOrder !== undefined ? op.executionOrder : index;
        const node: GraphNode = {
          id: op.name,
          label: this.truncateLabel(op.name, 20),
          fullLabel: op.name,
          nodeType: 'operation',
          opType: op.opType,
          opClass: op.opClass,
          executionOrder: execOrder,
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
    }

    // Build edges between operations based on variable flow
    // For each operation, find which operations produce its input variables
    if (operations && graphEdges) {
      // Map: variable name -> producing operation name
      const varProducer = new Map<string, string>();

      // First pass: find which operation produces each variable
      graphEdges.forEach(edge => {
        if (edge.type === 'output') {
          // edge.source is operation, edge.target is variable
          varProducer.set(edge.target, edge.source);
        }
      });

      // Second pass: create edges between operations
      graphEdges.forEach(edge => {
        if (edge.type === 'input') {
          // edge.source is variable, edge.target is operation (consumer)
          const producerOp = varProducer.get(edge.source);
          if (producerOp && this.nodeMap.has(producerOp) && this.nodeMap.has(edge.target)) {
            // Create edge from producer op to consumer op
            // Avoid duplicate edges
            const edgeKey = `${producerOp}->${edge.target}`;
            const exists = this.edges.some(e => `${e.source}->${e.target}` === edgeKey);
            if (!exists) {
              this.edges.push({
                source: producerOp,
                target: edge.target,
                type: 'input',
                sourceNode: this.nodeMap.get(producerOp),
                targetNode: this.nodeMap.get(edge.target)
              });
            }
          }
        }
      });
    }

    this.nodeCount = this.nodes.length;
  }

  private computeLayout(): void {
    if (this.nodes.length === 0) return;

    // Sort nodes by execution order
    const sortedNodes = [...this.nodes].sort((a, b) => a.executionOrder - b.executionOrder);

    // Compute layers based on dependencies using topological sort
    // But respect execution order from backend
    const nodeLayer = new Map<string, number>();
    const predecessors = new Map<string, Set<string>>();

    // Initialize predecessors
    this.nodes.forEach(n => predecessors.set(n.id, new Set()));
    this.edges.forEach(e => {
      predecessors.get(e.target)?.add(e.source);
    });

    // Assign layers based on longest path from sources
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

    // Group nodes by layer
    const layers: GraphNode[][] = [];
    this.nodes.forEach(node => {
      const layer = nodeLayer.get(node.id) || 0;
      while (layers.length <= layer) {
        layers.push([]);
      }
      layers[layer].push(node);
    });

    // Sort nodes within each layer by execution order for consistency
    layers.forEach(layer => {
      layer.sort((a, b) => a.executionOrder - b.executionOrder);
    });

    // Apply barycenter heuristic to minimize edge crossings
    for (let pass = 0; pass < 4; pass++) {
      // Forward pass
      for (let i = 1; i < layers.length; i++) {
        this.orderLayerByBarycenter(layers[i], layers[i - 1], true);
      }
      // Backward pass
      for (let i = layers.length - 2; i >= 0; i--) {
        this.orderLayerByBarycenter(layers[i], layers[i + 1], false);
      }
    }

    // Calculate positions
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
        let sum = 0;
        let count = 0;
        connected.forEach(id => {
          if (positions.has(id)) {
            sum += positions.get(id)!;
            count++;
          }
        });
        if (count > 0) {
          (node as any).barycenter = sum / count;
        }
      }
    });

    layer.sort((a, b) => {
      const aBC = (a as any).barycenter;
      const bBC = (b as any).barycenter;
      if (aBC !== undefined && bBC !== undefined) {
        return aBC - bBC;
      }
      if (aBC !== undefined) return -1;
      if (bBC !== undefined) return 1;
      return a.executionOrder - b.executionOrder;
    });
  }

  private renderGraph(): void {
    if (!this.g || this.nodes.length === 0) return;

    this.computeLayout();

    // Clear previous render
    this.g.selectAll('*').remove();

    // Create edges group (render first so nodes are on top)
    const edgesGroup = this.g.append('g').attr('class', 'edges');

    // Draw edges with curved paths
    edgesGroup.selectAll('path')
      .data(this.edges)
      .enter()
      .append('path')
      .attr('class', 'edge-path')
      .attr('d', (d) => this.generateEdgePath(d))
      .attr('marker-end', 'url(#arrow)');

    // Create nodes group
    const nodesGroup = this.g.append('g').attr('class', 'nodes');

    // Draw nodes
    const nodeGroups = nodesGroup.selectAll('g')
      .data(this.nodes)
      .enter()
      .append('g')
      .attr('class', 'node-group')
      .attr('transform', d => `translate(${d.x}, ${d.y})`)
      .on('click', (event, d) => this.onNodeClick(d));

    // Node rectangles
    nodeGroups.append('rect')
      .attr('class', 'node-rect')
      .attr('width', d => d.width)
      .attr('height', d => d.height);

    // Execution order badge
    nodeGroups.append('text')
      .attr('class', 'node-order')
      .attr('x', 8)
      .attr('y', 14)
      .text(d => d.executionOrder);

    // Node label (operation name)
    nodeGroups.append('text')
      .attr('class', 'node-label')
      .attr('x', d => d.width / 2)
      .attr('y', 17)
      .attr('text-anchor', 'middle')
      .text(d => d.label);

    // Node type (op type)
    nodeGroups.append('text')
      .attr('class', 'node-type')
      .attr('x', d => d.width / 2)
      .attr('y', 32)
      .attr('text-anchor', 'middle')
      .text(d => d.opType || '');

    // Fit to view initially
    setTimeout(() => this.fitToView(), 100);
  }

  private generateEdgePath(edge: GraphEdge): string {
    const source = this.nodeMap.get(edge.source);
    const target = this.nodeMap.get(edge.target);

    if (!source || !target) return '';

    // Start from bottom center of source
    const x1 = source.x + source.width / 2;
    const y1 = source.y + source.height;

    // End at top center of target
    const x2 = target.x + target.width / 2;
    const y2 = target.y;

    // Control points for smooth curve
    const midY = (y1 + y2) / 2;

    return `M ${x1} ${y1} C ${x1} ${midY}, ${x2} ${midY}, ${x2} ${y2}`;
  }

  private truncateLabel(label: string, maxLength: number): string {
    if (label.length <= maxLength) return label;
    return label.substring(0, maxLength - 2) + '..';
  }

  private onNodeClick(node: GraphNode): void {
    this.selectedNode = node;
    this.highlightNode(node);
  }

  private highlightNode(node: GraphNode): void {
    if (!this.g) return;

    // Reset all nodes
    this.g.selectAll('.node-rect')
      .classed('selected', false)
      .classed('highlighted', false)
      .classed('dimmed', false);

    this.g.selectAll('.edge-path')
      .classed('highlighted', false)
      .classed('dimmed', false);

    // Highlight selected node
    this.g.selectAll('.node-group')
      .filter((d: any) => d.id === node.id)
      .select('.node-rect')
      .classed('selected', true);

    // Highlight connected edges and nodes
    const connectedNodes = new Set<string>([node.id]);

    this.edges.forEach(edge => {
      if (edge.source === node.id || edge.target === node.id) {
        connectedNodes.add(edge.source);
        connectedNodes.add(edge.target);
      }
    });

    // Dim unconnected elements
    this.g.selectAll('.node-group')
      .filter((d: any) => !connectedNodes.has(d.id))
      .classed('dimmed', true);

    this.g.selectAll('.edge-path')
      .each((d: any, i, nodes) => {
        const isConnected = d.source === node.id || d.target === node.id;
        d3.select(nodes[i])
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

    this.g.selectAll('.edge-path')
      .classed('highlighted', false)
      .classed('dimmed', false);
  }

  // Zoom controls
  zoomIn(): void {
    if (!this.svg || !this.zoom) return;
    this.svg.transition().duration(300).call(this.zoom.scaleBy, 1.3);
  }

  zoomOut(): void {
    if (!this.svg || !this.zoom) return;
    this.svg.transition().duration(300).call(this.zoom.scaleBy, 0.7);
  }

  fitToView(): void {
    if (!this.svg || !this.g || !this.zoom || this.nodes.length === 0) return;

    const container = this.graphCanvas.nativeElement;
    const width = container.clientWidth;
    const height = container.clientHeight;

    // Calculate bounds
    let minX = Infinity, maxX = -Infinity, minY = Infinity, maxY = -Infinity;
    this.nodes.forEach(node => {
      minX = Math.min(minX, node.x);
      maxX = Math.max(maxX, node.x + node.width);
      minY = Math.min(minY, node.y);
      maxY = Math.max(maxY, node.y + node.height);
    });

    const graphWidth = maxX - minX + 100;
    const graphHeight = maxY - minY + 100;

    // Calculate scale to fit, but don't zoom out too much (min 0.1) or in too much (max 1.5)
    const fitScale = Math.min(width / graphWidth, height / graphHeight);
    const scale = Math.max(0.1, Math.min(fitScale, 1.5)) * 0.9;

    const centerX = (minX + maxX) / 2;
    const centerY = (minY + maxY) / 2;

    const translateX = width / 2 - centerX * scale;
    const translateY = height / 2 - centerY * scale;

    this.svg.transition()
      .duration(500)
      .call(this.zoom.transform, d3.zoomIdentity.translate(translateX, translateY).scale(scale));
  }

  // Keyboard shortcuts
  onKeyDown(event: KeyboardEvent): void {
    if ((event.ctrlKey || event.metaKey) && event.key === 'f') {
      event.preventDefault();
      this.toggleFindSidebar();
    }
    if (event.key === 'Escape') {
      if (this.showFindSidebar) {
        this.toggleFindSidebar();
      } else if (this.selectedNode) {
        this.clearSelection();
      }
    }
  }

  // Search functionality
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

    this.searchResults = this.nodes.filter(node => {
      return node.id.toLowerCase().includes(query) ||
             node.fullLabel.toLowerCase().includes(query) ||
             node.opType?.toLowerCase().includes(query) ||
             node.opClass?.toLowerCase().includes(query);
    }).sort((a, b) => a.executionOrder - b.executionOrder);

    this.currentSearchIndex = -1;
    this.updateSearchHighlights();
  }

  selectResult(index: number): void {
    if (index < 0 || index >= this.searchResults.length) return;
    this.currentSearchIndex = index;
    const node = this.searchResults[index];
    this.focusOnNode(node);
    this.updateSearchHighlights();
  }

  selectNextResult(event: Event): void {
    event.preventDefault();
    if (this.searchResults.length === 0) return;
    this.currentSearchIndex = (this.currentSearchIndex + 1) % this.searchResults.length;
    const node = this.searchResults[this.currentSearchIndex];
    this.focusOnNode(node);
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

    this.g.selectAll('.edge-path')
      .classed('dimmed', this.searchResults.length > 0);
  }

  private clearSearchHighlights(): void {
    if (!this.g) return;
    this.g.selectAll('.node-rect')
      .classed('highlighted', false)
      .classed('dimmed', false);
    this.g.selectAll('.edge-path')
      .classed('dimmed', false);
  }

  private focusOnNode(node: GraphNode): void {
    if (!this.svg || !this.zoom) return;

    const container = this.graphCanvas.nativeElement;
    const width = container.clientWidth;
    const height = container.clientHeight;

    const scale = 1.2;
    const translateX = width / 2 - (node.x + node.width / 2) * scale;
    const translateY = height / 2 - (node.y + node.height / 2) * scale;

    this.svg.transition()
      .duration(300)
      .call(this.zoom.transform, d3.zoomIdentity.translate(translateX, translateY).scale(scale));

    this.selectedNode = node;
  }
}
