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
  ElementRef,
  EventEmitter,
  Input,
  OnChanges,
  OnDestroy,
  OnInit,
  Output,
  SimpleChanges,
  ViewChild,
  ChangeDetectionStrategy,
  ChangeDetectorRef,
  NgZone
} from '@angular/core';
import { CommonModule } from '@angular/common';
import { MatIconModule } from '@angular/material/icon';
import { Subject, takeUntil } from 'rxjs';
import Sigma from 'sigma';
import Graph from 'graphology';
import { ThemeService } from '../../services/theme.service';
import {
  D3Node,
  D3Link,
  D3VisualizationData,
  NodeLevel,
  EdgeType,
  ForceConfig,
  DEFAULT_FORCE_CONFIG,
  NODE_COLORS,
  NODE_SIZES,
  EDGE_COLORS,
  EDGE_DASH_PATTERNS
} from '../../models/graph-models';

// Community palette (same as original)
const COMMUNITY_PALETTE: string[] = [
  '#4285F4', '#EA4335', '#FBBC05', '#34A853', '#FF6D00',
  '#9C27B0', '#00BCD4', '#FF5722', '#607D8B', '#795548',
  '#E91E63', '#009688', '#FF9800', '#3F51B5', '#8BC34A',
  '#F44336', '#2196F3', '#4CAF50', '#FFC107', '#9E9E9E'
];

// Strength band border colors (same as original)
const STRENGTH_BORDER_COLORS: Record<string, string> = {
  ESTABLISHED:  '#4CAF50',
  HIGH:         '#8BC34A',
  PROBABLE:     '#FFC107',
  SPECULATIVE:  '#FF9800',
  SUPPRESSED:   '#F44336',
};

// MFrag accent colors (maps each unique MFrag to a color)
const MFRAG_ACCENT_COLORS: string[] = [
  '#667eea', '#22c55e', '#f59e0b', '#8b5cf6', '#ef4444',
  '#0ea5e9', '#ec4899'
];

@Component({
  selector: 'app-graph-canvas',
  standalone: true,
  imports: [CommonModule, MatIconModule],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="graph-canvas-container" #container>
      <!-- Empty-state overlay: shown after data loads but graph has 0 nodes -->
      <div *ngIf="showEmptyState" class="canvas-empty-state">
        <mat-icon class="empty-icon">device_hub</mat-icon>
        <p class="empty-message">No nodes yet — run a crawl or build the graph to populate it.</p>
      </div>
      <!-- Sigma.js WebGL container — Sigma injects its canvas elements here -->
      <div #sigmaContainer class="sigma-container"></div>
      <div class="zoom-controls">
        <button (click)="zoomIn()" title="Zoom In">+</button>
        <button (click)="zoomOut()" title="Zoom Out">-</button>
        <button (click)="resetZoom()" title="Reset View">Reset</button>
        <button (click)="fitToScreen()" title="Fit to Screen">Fit</button>
      </div>
      <div class="legend" *ngIf="showLegend">
        <div class="legend-title">Node Types</div>
        <div class="legend-item" *ngFor="let nodeType of nodeTypes">
          <span class="legend-color" [style.background-color]="nodeColors[nodeType]"></span>
          <span class="legend-label">{{nodeType}}</span>
        </div>
        <div class="legend-title">Edge Types</div>
        <div class="legend-item" *ngFor="let edgeType of edgeTypes">
          <span class="legend-line" [style.border-color]="edgeColors[edgeType]"
                [style.border-style]="getEdgeBorderStyle(edgeType)"></span>
          <span class="legend-label">{{formatEdgeType(edgeType)}}</span>
        </div>
        <ng-container *ngIf="posteriorOverlay || priorOverlay || mebnMfragMap || findingNodeMap">
          <div class="legend-title">Bayesian</div>
          <div class="legend-item" *ngIf="priorOverlay">
            <span class="legend-ring"></span>
            <span class="legend-label">Prior (heat tint)</span>
          </div>
          <div class="legend-item" *ngIf="posteriorOverlay && !influenceOverlayActive">
            <span class="legend-heat-swatch"></span>
            <span class="legend-label">Posterior heat</span>
          </div>
          <!-- Heat gradient bar: low (blue) → mid (yellow) → high (red) -->
          <div class="legend-item legend-heat-bar-row" *ngIf="posteriorOverlay && !influenceOverlayActive">
            <span class="legend-heat-bar"></span>
            <div class="legend-heat-ticks">
              <span>0%</span><span>50%</span><span>100%</span>
            </div>
          </div>
          <div class="legend-item" *ngIf="posteriorOverlay && influenceOverlayActive">
            <span class="legend-influence-swatch"></span>
            <span class="legend-label">Influence score</span>
          </div>
          <!-- Evidence/Finding swatch -->
          <div class="legend-item" *ngIf="findingNodeMap">
            <span class="legend-color" style="background:#ff6b00"></span>
            <span class="legend-label">Evidence / Finding</span>
          </div>
          <!-- Per-fragment MFrag swatches (replaces the generic single swatch) -->
          <ng-container *ngIf="mebnMfragMap && uniqueMfrags.length > 0">
            <div class="legend-item" *ngFor="let frag of uniqueMfrags; let fi = index">
              <span class="legend-color"
                    [style.background-color]="mfragAccentColor(fi)"
                    style="border-radius:3px"></span>
              <span class="legend-label">{{ fragLabelCanvas(frag) }}</span>
            </div>
          </ng-container>
          <ng-container *ngIf="mebnMfragMap && uniqueMfrags.length === 0">
            <div class="legend-item">
              <span class="legend-mfrag-swatch"></span>
              <span class="legend-label">MFrag region</span>
            </div>
          </ng-container>
        </ng-container>
        <ng-container *ngIf="strengthOverlayEnabled">
          <div class="legend-title">Strength</div>
          <div class="legend-item">
            <span class="legend-color" style="background:#4CAF50"></span>
            <span class="legend-label">Established</span>
          </div>
          <div class="legend-item">
            <span class="legend-color" style="background:#8BC34A"></span>
            <span class="legend-label">High</span>
          </div>
          <div class="legend-item">
            <span class="legend-color" style="background:#FFC107"></span>
            <span class="legend-label">Probable</span>
          </div>
          <div class="legend-item">
            <span class="legend-color" style="background:#FF9800"></span>
            <span class="legend-label">Speculative</span>
          </div>
          <div class="legend-item">
            <span class="legend-color" style="background:#F44336"></span>
            <span class="legend-label">Suppressed</span>
          </div>
        </ng-container>
        <ng-container *ngIf="provenanceOverlayEnabled">
          <div class="legend-title">Provenance</div>
          <div class="legend-item">
            <span class="legend-circle-swatch"></span>
            <span class="legend-label">Observed (circle)</span>
          </div>
          <div class="legend-item">
            <span class="legend-diamond-swatch"></span>
            <span class="legend-label">Derived (diamond)</span>
          </div>
        </ng-container>
        <ng-container *ngIf="conformanceOverlayEnabled">
          <div class="legend-title">Conformance</div>
          <div class="legend-item">
            <span class="legend-color" style="background:#4CAF50"></span>
            <span class="legend-label">Conformant</span>
          </div>
          <div class="legend-item">
            <span class="legend-color" style="background:#F44336"></span>
            <span class="legend-label">Violation</span>
          </div>
          <div class="legend-item">
            <span class="legend-color" style="background:#9E9E9E"></span>
            <span class="legend-label">Untagged</span>
          </div>
        </ng-container>
      </div>
    </div>
  `,
  styles: [`
    :host {
      display: block;
      width: 100%;
      height: 100%;
    }

    .graph-canvas-container {
      position: relative;
      width: 100%;
      height: 100%;
      background: var(--graph-bg, linear-gradient(135deg, #f8fafc 0%, #f1f5f9 100%));
      overflow: hidden;
    }

    /* Sigma.js WebGL canvas container — fills the full graph area */
    .sigma-container {
      position: absolute;
      inset: 0;
      width: 100%;
      height: 100%;
    }

    /* Empty-state overlay shown when graph loaded but has 0 nodes */
    .canvas-empty-state {
      position: absolute;
      inset: 0;
      display: flex;
      flex-direction: column;
      align-items: center;
      justify-content: center;
      gap: 12px;
      pointer-events: none;
      z-index: 10;
    }

    .empty-icon {
      font-size: 48px;
      width: 48px;
      height: 48px;
      color: var(--text-tertiary, #c0c7d0);
    }

    .empty-message {
      margin: 0;
      font-size: 14px;
      color: var(--text-tertiary, #9aa5b4);
      text-align: center;
      max-width: 320px;
      line-height: 1.5;
    }

    .zoom-controls {
      position: absolute;
      top: 16px;
      right: 16px;
      display: flex;
      flex-direction: column;
      gap: 6px;
      z-index: 20;
    }

    .zoom-controls button {
      min-width: 36px;
      height: 36px;
      padding: 0 12px;
      border: 1px solid var(--border-color, #e3e8ee);
      background: var(--bg-surface, #ffffff);
      color: var(--text-primary, #1a1f36);
      font-size: 13px;
      font-weight: 500;
      cursor: pointer;
      border-radius: 8px;
      transition: all 0.2s ease;
      box-shadow: 0 1px 3px rgba(0, 0, 0, 0.1);
      white-space: nowrap;
    }

    .zoom-controls button:hover {
      background: var(--bg-body, #f1f5f9);
      border-color: #667eea;
      color: #667eea;
      transform: translateY(-1px);
      box-shadow: 0 2px 6px rgba(102, 126, 234, 0.15);
    }

    .zoom-controls button:active {
      transform: translateY(0);
    }

    .legend {
      position: absolute;
      bottom: 16px;
      left: 16px;
      background: var(--bg-surface, #ffffff);
      border: 1px solid var(--border-color, #e3e8ee);
      border-radius: 12px;
      padding: 14px 16px;
      color: var(--text-primary, #1a1f36);
      font-size: 12px;
      box-shadow: 0 4px 12px rgba(0, 0, 0, 0.08);
      min-width: 160px;
      max-width: 220px;
      z-index: 20;
    }

    .legend-title {
      font-weight: 600;
      margin-bottom: 8px;
      margin-top: 12px;
      color: var(--text-secondary, #697386);
      font-size: 10px;
      text-transform: uppercase;
      letter-spacing: 0.5px;
    }

    .legend-title:first-child {
      margin-top: 0;
    }

    .legend-item {
      display: flex;
      align-items: center;
      gap: 10px;
      margin-bottom: 6px;
    }

    .legend-color {
      width: 14px;
      height: 14px;
      min-width: 14px;
      border-radius: 50%;
      box-shadow: 0 1px 2px rgba(0, 0, 0, 0.1);
    }

    .legend-line {
      width: 24px;
      min-width: 24px;
      height: 0;
      border-width: 2px;
    }

    .legend-label {
      text-transform: capitalize;
      color: var(--text-primary, #1a1f36);
      font-size: 11px;
      white-space: nowrap;
      overflow: visible;
    }

    .legend-ring {
      width: 14px;
      height: 14px;
      border-radius: 50%;
      border: 2px dashed var(--text-tertiary, #8792a2);
      background: transparent;
    }

    .legend-heat-swatch {
      width: 14px;
      height: 14px;
      border-radius: 50%;
      background: radial-gradient(circle, #ef5350 0%, #66bb6a 100%);
    }

    .legend-influence-swatch {
      width: 14px;
      height: 14px;
      border-radius: 50%;
      background: radial-gradient(circle, #ce93d8 0%, #7e57c2 100%);
    }

    .legend-mfrag-swatch {
      width: 14px;
      height: 14px;
      border-radius: 3px;
      background: rgba(144, 202, 249, 0.1);
      border: 1.5px dashed rgba(144, 202, 249, 0.3);
    }

    /* Posterior heat gradient bar (feature 4) */
    .legend-heat-bar-row {
      flex-direction: column;
      align-items: stretch;
      gap: 2px;
      margin-bottom: 4px;
    }

    .legend-heat-bar {
      display: block;
      width: 100%;
      height: 8px;
      border-radius: 4px;
      background: linear-gradient(to right, #3b82f6 0%, #f59e0b 50%, #ef4444 100%);
    }

    .legend-heat-ticks {
      display: flex;
      justify-content: space-between;
      font-size: 9px;
      color: var(--text-secondary, #697386);
      margin-top: 1px;
    }

    .legend-circle-swatch {
      width: 14px;
      height: 14px;
      border-radius: 50%;
      background: transparent;
      border: 2px solid #888;
    }

    .legend-diamond-swatch {
      width: 12px;
      height: 12px;
      background: transparent;
      border: 2px dashed #ce93d8;
      transform: rotate(45deg);
    }

    /* Dark-theme background override for the graph canvas */
    :host-context(body.dark-theme) .graph-canvas-container {
      background: linear-gradient(135deg, #12161e 0%, #0f1318 100%);
    }
  `]
})
export class GraphCanvasComponent implements OnInit, OnChanges, OnDestroy {
  @ViewChild('container', { static: true }) containerRef!: ElementRef<HTMLDivElement>;
  @ViewChild('sigmaContainer', { static: true }) sigmaContainerRef!: ElementRef<HTMLDivElement>;

  @Input() data: D3VisualizationData | null = null;
  /** ForceConfig is accepted for API compatibility; Sigma handles its own layout. */
  @Input() forceConfig: ForceConfig = DEFAULT_FORCE_CONFIG;
  @Input() showLegend: boolean = true;
  @Input() linkMode: boolean = false;
  @Input() posteriorOverlay: Record<string, number> | null = null;
  @Input() priorOverlay: Record<string, number> | null = null;
  @Input() mebnMfragMap: Record<string, string> | null = null;
  @Input() findingNodeMap: Record<string, boolean> | null = null;
  @Input() influenceOverlayActive: boolean = false;

  // Phase-2 KB overlays
  @Input() strengthOverlayEnabled: boolean = false;
  @Input() strengthBandMap: Map<string, string> = new Map();
  @Input() provenanceOverlayEnabled: boolean = false;
  @Input() communityOverlayEnabled: boolean = false;
  @Input() communityMap: Map<string, number> = new Map();

  // Conformance overlay
  @Input() conformanceOverlayEnabled: boolean = false;
  @Input() conformanceMap: Map<string, boolean | null> = new Map();

  @Output() nodeSelected = new EventEmitter<D3Node | null>();
  @Output() nodeDoubleClicked = new EventEmitter<D3Node>();
  @Output() edgeCreated = new EventEmitter<{ source: string; target: string }>();
  @Output() nodeContextMenu = new EventEmitter<{ node: D3Node; event: MouseEvent }>();
  @Output() linkSourceChanged = new EventEmitter<D3Node | null>();

  nodeColors = NODE_COLORS;
  edgeColors = EDGE_COLORS;
  nodeTypes: NodeLevel[] = ['SOURCE', 'DOCUMENT', 'SNIPPET', 'ENTITY', 'CUSTOM', 'TABLE', 'ATTACHMENT'];
  edgeTypes: EdgeType[] = ['HIERARCHICAL', 'EMBEDDING_SIMILARITY', 'SHARED_ENTITY', 'USER_DEFINED', 'CITATION', 'TEMPORAL', 'CROSS_SOURCE'];

  /** True after data has been received but the node list resolved to 0 entries. */
  showEmptyState = false;

  // Sigma/graphology state
  private sigmaInstance: Sigma | null = null;
  private graph: Graph | null = null;

  /** Fast lookup: node key → original D3Node. */
  private nodeMap = new Map<string, D3Node>();

  private selectedNodeKey: string | null = null;
  private linkSourceKey: string | null = null;

  // Drag interaction state
  private isDragging = false;
  private dragNode: string | null = null;

  // Unique MFrag name list — built when mebnMfragMap changes; public for legend *ngFor
  uniqueMfrags: string[] = [];

  private destroy$ = new Subject<void>();

  constructor(
    private cdr: ChangeDetectorRef,
    private ngZone: NgZone,
    private themeService: ThemeService
  ) {}

  ngOnInit(): void {
    this.initializeSigma();
    if (this.data) {
      this.updateGraph();
    }
    // Re-apply colors whenever the theme toggles
    this.themeService.theme$
      .pipe(takeUntil(this.destroy$))
      .subscribe(() => this.refreshNodeColors());
  }

  ngOnChanges(changes: SimpleChanges): void {
    if (changes['data'] && this.data && this.sigmaInstance) {
      this.updateGraph();
    }
    // Overlay changes: just recompute node colors + refresh
    const overlayKeys = [
      'posteriorOverlay', 'priorOverlay', 'mebnMfragMap',
      'findingNodeMap',
      'strengthOverlayEnabled', 'strengthBandMap',
      'provenanceOverlayEnabled',
      'communityOverlayEnabled', 'communityMap',
      'conformanceOverlayEnabled', 'conformanceMap',
      'influenceOverlayActive',
    ];
    if (overlayKeys.some(k => !!changes[k]) && this.sigmaInstance) {
      if (changes['mebnMfragMap']) {
        this.rebuildMfragIndex();
      }
      this.refreshNodeColors();
    }
  }

  ngOnDestroy(): void {
    this.destroy$.next();
    this.destroy$.complete();
    if (this.sigmaInstance) {
      this.sigmaInstance.kill();
      this.sigmaInstance = null;
    }
    this.graph = null;
  }

  // ── Initialization ────────────────────────────────────────────────────────────

  private initializeSigma(): void {
    this.graph = new Graph({ multi: true, allowSelfLoops: false });

    this.sigmaInstance = new Sigma(this.graph, this.sigmaContainerRef.nativeElement, {
      renderEdgeLabels: false,
      labelFont: 'system-ui, -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif',
      labelSize: 11,
      labelWeight: '500',
      defaultEdgeType: 'line',
      minCameraRatio: 0.05,
      maxCameraRatio: 20,
    });

    // ── Node click — selection / link-mode ────────────────────────────────────
    this.sigmaInstance.on('clickNode', ({ node }) => {
      this.ngZone.run(() => this.handleNodeClick(node));
    });

    // ── Node double-click ─────────────────────────────────────────────────────
    this.sigmaInstance.on('doubleClickNode', ({ node, event }) => {
      event.preventSigmaDefault();
      this.ngZone.run(() => {
        const d3Node = this.nodeMap.get(node);
        if (d3Node) this.nodeDoubleClicked.emit(d3Node);
      });
    });

    // ── Right-click context menu ───────────────────────────────────────────────
    this.sigmaInstance.on('rightClickNode', ({ node, event }) => {
      event.preventSigmaDefault();
      this.ngZone.run(() => {
        const d3Node = this.nodeMap.get(node);
        if (d3Node) {
          this.nodeContextMenu.emit({ node: d3Node, event: event.original as MouseEvent });
        }
      });
    });

    // ── Background click — deselect ────────────────────────────────────────────
    this.sigmaInstance.on('clickStage', () => {
      this.ngZone.run(() => {
        this.selectedNodeKey = null;
        this.nodeSelected.emit(null);
        this.refreshNodeColors();
        this.cdr.markForCheck();
      });
    });

    // ── Node dragging ─────────────────────────────────────────────────────────
    this.setupDragging();
  }

  private setupDragging(): void {
    if (!this.sigmaInstance) return;
    const sigma = this.sigmaInstance;

    sigma.on('downNode', ({ node }) => {
      this.isDragging = true;
      this.dragNode = node;
      sigma.getCamera().disable();
    });

    sigma.getMouseCaptor().on('mousemovebody', (event: any) => {
      if (!this.isDragging || !this.dragNode || !this.graph) return;
      const pos = sigma.viewportToGraph({ x: event.x, y: event.y });
      if (this.graph.hasNode(this.dragNode)) {
        this.graph.setNodeAttribute(this.dragNode, 'x', pos.x);
        this.graph.setNodeAttribute(this.dragNode, 'y', pos.y);
      }
      event.preventSigmaDefault?.();
    });

    sigma.getMouseCaptor().on('mouseup', () => {
      if (this.isDragging) {
        this.isDragging = false;
        this.dragNode = null;
        sigma.getCamera().enable();
      }
    });
  }

  // ── Graph data update ─────────────────────────────────────────────────────────

  /**
   * Diff-based graph update: adds/removes/updates only the nodes and edges that
   * actually changed relative to the current graphology state. Existing node
   * positions (including user drags) are never touched — only visual attributes
   * (color, label, size) are updated. Layout is computed only for genuinely new
   * nodes. One sigma.refresh() fires at the end.
   *
   * This replaces the old clear()-and-rebuild approach, which destroyed and
   * recreated every graphology object on every 5s auto-refresh tick even when
   * the graph content was stable.
   */
  private updateGraph(): void {
    if (!this.data || !this.graph || !this.sigmaInstance) return;

    // Rebuild internal index of unique MFrag names
    this.rebuildMfragIndex();

    const nodes = this.data.nodes;
    this.showEmptyState = nodes.length === 0;
    this.cdr.markForCheck();

    // ── 1. Compute which node ids are incoming ────────────────────────────────
    const incomingNodeIds = new Set(nodes.map(n => n.id));

    // ── 2. Drop nodes absent from the new data (graphology also drops their edges) ──
    const toDropNodes: string[] = [];
    this.graph.forEachNode((id) => {
      if (!incomingNodeIds.has(id)) toDropNodes.push(id);
    });
    for (const id of toDropNodes) {
      this.graph.dropNode(id);
      this.nodeMap.delete(id);
    }

    if (nodes.length === 0) {
      this.sigmaInstance.refresh();
      return;
    }

    // ── 3. Identify genuinely new nodes; compute positions only for them ──────
    const newNodes = nodes.filter(n => !this.graph!.hasNode(n.id));
    const newPositions = this.computeCircularLayout(newNodes);

    // ── 4. Add new nodes / update existing node attributes ────────────────────
    for (const node of nodes) {
      const anyNode = node as any;
      const color = this.resolveNodeColor(node);
      const label = this.truncateLabel(anyNode.label || anyNode.title || node.id, 20);
      const size = (NODE_SIZES[node.type] || 10) * 0.75;

      if (this.graph.hasNode(node.id)) {
        // Existing node: update visual attributes only — NEVER touch x/y so
        // user-dragged positions are preserved across every auto-refresh tick.
        this.graph.setNodeAttribute(node.id, 'color', color);
        this.graph.setNodeAttribute(node.id, 'label', label);
        this.graph.setNodeAttribute(node.id, 'size', size);
      } else {
        // New node: assign a layout position.
        const pos = newPositions.get(node.id)!;
        this.graph.addNode(node.id, {
          x: anyNode.x !== undefined ? anyNode.x : pos.x,
          y: anyNode.y !== undefined ? anyNode.y : pos.y,
          size,
          color,
          label,
        });
      }
      this.nodeMap.set(node.id, node);
    }

    // ── 5. Diff edges: build the expected key set, add missing, drop stale ────
    const incomingEdgeKeys = new Set<string>();
    for (const link of this.data.links) {
      const srcKey = typeof link.source === 'string' ? link.source : (link.source as any).id;
      const tgtKey = typeof link.target === 'string' ? link.target : (link.target as any).id;
      if (!this.graph.hasNode(srcKey) || !this.graph.hasNode(tgtKey)) continue;
      const edgeKey = link.id || `${srcKey}→${tgtKey}:${link.type}`;
      incomingEdgeKeys.add(edgeKey);
      if (!this.graph.hasEdge(edgeKey)) {
        try {
          this.graph.addEdgeWithKey(edgeKey, srcKey, tgtKey, {
            color: EDGE_COLORS[link.type] || '#999999',
            size: Math.max(0.5, (link.weight || 1) * 1.5),
            type: 'line',
          });
        } catch {
          // Duplicate edge key (edge exists under a different key) — skip
        }
      }
    }

    // Drop edges that are no longer in the incoming data. Collect first to avoid
    // mutating the graph while iterating over it.
    const toDropEdges: string[] = [];
    this.graph.forEachEdge((edgeKey) => {
      if (!incomingEdgeKeys.has(edgeKey)) toDropEdges.push(edgeKey);
    });
    for (const edgeKey of toDropEdges) {
      try { this.graph.dropEdge(edgeKey); } catch { /* already gone */ }
    }

    this.sigmaInstance.refresh();
  }

  /**
   * Merge the given visualization data into the existing Sigma graph (LOD expand).
   *
   * Only nodes/edges absent from the graph are added — existing positions and
   * dragged placements are preserved. New nodes are placed radially around the
   * first incoming node that is already in the graph (the "anchor"), using the
   * same FNV-1a jitter as computeCircularLayout so positions are stable across
   * subsequent calls for the same nodeId. Calls sigma.refresh() once at the end.
   *
   * Called by GraphVisualizerComponent on double-click expand.
   */
  addNodesToGraph(data: D3VisualizationData): void {
    if (!data || !this.graph || !this.sigmaInstance) return;

    // Locate anchor: the first incoming node already present in the graph.
    // This is the node the user double-clicked, which anchors the radial layout.
    let anchorX = 0;
    let anchorY = 0;
    let foundAnchor = false;
    for (const node of data.nodes) {
      if (this.graph.hasNode(node.id)) {
        anchorX = this.graph.getNodeAttribute(node.id, 'x') as number;
        anchorY = this.graph.getNodeAttribute(node.id, 'y') as number;
        foundAnchor = true;
        break;
      }
    }
    if (!foundAnchor && this.graph.order > 0) {
      // Fall back to centroid of the whole graph
      let sumX = 0, sumY = 0, count = 0;
      this.graph.forEachNode((_, attrs) => {
        const a = attrs as any;
        sumX += (a.x as number) || 0;
        sumY += (a.y as number) || 0;
        count++;
      });
      if (count > 0) {
        anchorX = sumX / count;
        anchorY = sumY / count;
      }
    }

    // Identify genuinely new nodes and compute their radial positions
    const newNodes = data.nodes.filter(n => !this.graph!.hasNode(n.id));
    const n = newNodes.length;
    const radius = Math.max(100, Math.sqrt(n + 1) * 50);
    const jitterScale = radius * 0.08;

    newNodes.forEach((node, i) => {
      const angle = (2 * Math.PI * i) / Math.max(1, n);
      const x = anchorX + Math.cos(angle) * radius + (this.hashToUnit(node.id, 1) - 0.5) * jitterScale;
      const y = anchorY + Math.sin(angle) * radius + (this.hashToUnit(node.id, 2) - 0.5) * jitterScale;
      const anyNode = node as any;
      this.graph!.addNode(node.id, {
        x,
        y,
        size: (NODE_SIZES[node.type] || 10) * 0.75,
        color: this.resolveNodeColor(node),
        label: this.truncateLabel(anyNode.label || anyNode.title || node.id, 20),
      });
      this.nodeMap.set(node.id, node);
    });

    // Ensure nodeMap is up-to-date for existing nodes too (may arrive with richer metadata)
    for (const node of data.nodes) {
      if (!this.nodeMap.has(node.id)) {
        this.nodeMap.set(node.id, node);
      }
    }

    // Add only missing edges (same key scheme as updateGraph)
    for (const link of data.links) {
      const srcKey = typeof link.source === 'string' ? link.source : (link.source as any).id;
      const tgtKey = typeof link.target === 'string' ? link.target : (link.target as any).id;
      if (!this.graph.hasNode(srcKey) || !this.graph.hasNode(tgtKey)) continue;
      const edgeKey = link.id || `${srcKey}→${tgtKey}:${link.type}`;
      if (this.graph.hasEdge(edgeKey)) continue;
      try {
        this.graph.addEdgeWithKey(edgeKey, srcKey, tgtKey, {
          color: EDGE_COLORS[link.type] || '#999999',
          size: Math.max(0.5, (link.weight || 1) * 1.5),
          type: 'line',
        });
      } catch {
        // Duplicate edge key (possible if the edge exists under a different key) — skip
      }
    }

    if (n > 0) {
      this.showEmptyState = false;
      this.cdr.markForCheck();
    }

    this.sigmaInstance.refresh();
  }

  /**
   * Circular layout: nodes at equal angles around a circle whose radius grows
   * with sqrt(n) so small and large graphs both look spread out.
   */
  private computeCircularLayout(nodes: D3Node[]): Map<string, { x: number; y: number }> {
    const positions = new Map<string, { x: number; y: number }>();
    const n = nodes.length;
    const radius = Math.max(200, Math.sqrt(n) * 60);
    const jitterScale = radius * 0.08;

    nodes.forEach((node, i) => {
      const angle = (2 * Math.PI * i) / n;
      // Deterministic jitter seeded by node id (was Math.random()). A random jitter
      // re-positioned every node on every updateGraph() call, so the whole graph visibly
      // jumped on each 5s auto-refresh / filter change even when nothing actually changed.
      // Hashing the id keeps each node's jitter stable across refreshes.
      positions.set(node.id, {
        x: Math.cos(angle) * radius + (this.hashToUnit(node.id, 1) - 0.5) * jitterScale,
        y: Math.sin(angle) * radius + (this.hashToUnit(node.id, 2) - 0.5) * jitterScale,
      });
    });

    return positions;
  }

  /**
   * Deterministic [0,1) hash of a string id + salt (FNV-1a). Stable per id, so the
   * circular-layout jitter is reproducible across refreshes instead of random.
   */
  private hashToUnit(s: string, salt: number): number {
    let h = (2166136261 ^ salt) >>> 0;
    for (let i = 0; i < s.length; i++) {
      h ^= s.charCodeAt(i);
      h = Math.imul(h, 16777619);
    }
    return ((h >>> 0) % 100000) / 100000;
  }

  // ── Node color resolution (overlays in priority order) ─────────────────────

  private resolveNodeColor(node: D3Node): string {
    // 1. Posterior / influence heat
    if (this.posteriorOverlay && this.posteriorOverlay[node.id] !== undefined) {
      return this.posteriorHeatColor(this.posteriorOverlay[node.id]);
    }
    // 1b. Evidence/finding nodes — orange, rendered after posterior so findings that also
    //     have posteriors keep the heat color when inference is running.
    if (this.findingNodeMap?.[node.id]) {
      return '#ff6b00';
    }
    // 2. Prior rings — tint toward heat color
    if (this.priorOverlay && this.priorOverlay[node.id] !== undefined) {
      const h = this.posteriorHeatColor(this.priorOverlay[node.id]);
      return this.blendColor(NODE_COLORS[node.type] || '#999999', h, 0.45);
    }
    // 3. Strength band
    if (this.strengthOverlayEnabled && this.strengthBandMap.has(node.id)) {
      return STRENGTH_BORDER_COLORS[this.strengthBandMap.get(node.id)!] || (NODE_COLORS[node.type] || '#999999');
    }
    // 4. Community
    if (this.communityOverlayEnabled && this.communityMap.has(node.id)) {
      return COMMUNITY_PALETTE[this.communityMap.get(node.id)! % COMMUNITY_PALETTE.length];
    }
    // 5. Conformance
    if (this.conformanceOverlayEnabled && this.conformanceMap.has(node.id)) {
      const v = this.conformanceMap.get(node.id);
      if (v === true)  return '#4CAF50';
      if (v === false) return '#F44336';
      return '#9E9E9E';
    }
    // 6. MFrag membership — accent color per unique MFrag name
    if (this.mebnMfragMap && this.mebnMfragMap[node.id]) {
      const idx = this.uniqueMfrags.indexOf(this.mebnMfragMap[node.id]);
      return MFRAG_ACCENT_COLORS[Math.max(0, idx) % MFRAG_ACCENT_COLORS.length];
    }
    // 7. Provenance-derived nodes — purple tint
    if (this.provenanceOverlayEnabled && this.isDerivedNode(node)) {
      return '#ce93d8';
    }
    // 8. Default: type color
    return NODE_COLORS[node.type] || '#999999';
  }

  private refreshNodeColors(): void {
    if (!this.graph || !this.sigmaInstance) return;

    this.graph.forEachNode((nodeKey) => {
      const d3Node = this.nodeMap.get(nodeKey);
      if (!d3Node) return;
      let color = this.resolveNodeColor(d3Node);

      // Selection and link-source highlights override the overlay color
      if (nodeKey === this.selectedNodeKey) {
        this.graph!.setNodeAttribute(nodeKey, 'highlighted', true);
        this.graph!.setNodeAttribute(nodeKey, 'color', color);
      } else if (nodeKey === this.linkSourceKey) {
        this.graph!.setNodeAttribute(nodeKey, 'highlighted', true);
        this.graph!.setNodeAttribute(nodeKey, 'color', '#fbbf24');
      } else {
        this.graph!.setNodeAttribute(nodeKey, 'highlighted', false);
        this.graph!.setNodeAttribute(nodeKey, 'color', color);
      }
    });

    this.sigmaInstance.refresh();
  }

  // ── Click / interaction handlers ──────────────────────────────────────────────

  private handleNodeClick(nodeKey: string): void {
    const d3Node = this.nodeMap.get(nodeKey);
    if (!d3Node) return;

    if (this.linkMode && this.linkSourceKey) {
      if (this.linkSourceKey !== nodeKey) {
        this.edgeCreated.emit({ source: this.linkSourceKey, target: nodeKey });
      }
      this.linkSourceKey = null;
      this.linkSourceChanged.emit(null);
    } else if (this.linkMode) {
      this.linkSourceKey = nodeKey;
      this.linkSourceChanged.emit(d3Node);
    } else {
      // Toggle selection
      if (this.selectedNodeKey === nodeKey) {
        this.selectedNodeKey = null;
        this.nodeSelected.emit(null);
      } else {
        this.selectedNodeKey = nodeKey;
        this.nodeSelected.emit(d3Node);
      }
      this.cdr.markForCheck();
    }

    this.refreshNodeColors();
  }

  // ── Zoom controls (public — called from template) ─────────────────────────────

  zoomIn(): void {
    if (!this.sigmaInstance) return;
    const cam = this.sigmaInstance.getCamera();
    cam.animate({ ratio: cam.ratio / 1.3 }, { duration: 300 });
  }

  zoomOut(): void {
    if (!this.sigmaInstance) return;
    const cam = this.sigmaInstance.getCamera();
    cam.animate({ ratio: cam.ratio * 1.3 }, { duration: 300 });
  }

  resetZoom(): void {
    if (!this.sigmaInstance) return;
    this.sigmaInstance.getCamera().animatedReset();
  }

  fitToScreen(): void {
    if (!this.sigmaInstance) return;
    this.sigmaInstance.getCamera().animatedReset();
  }

  // ── Utility helpers ───────────────────────────────────────────────────────────

  private truncateLabel(text: string, maxLength: number): string {
    if (!text) return '';
    return text.length <= maxLength ? text : text.substring(0, maxLength - 3) + '...';
  }

  private isDerivedNode(node: D3Node): boolean {
    const meta = (node as any).metadata as Record<string, unknown> | undefined;
    if (!meta) return false;
    if (meta['_derived'] === true) return true;
    const src = meta['_source'] as string | undefined;
    if (src?.toLowerCase().includes('derived')) return true;
    const prov = meta['_provenance'] as string | undefined;
    if (prov?.toLowerCase().includes('derived')) return true;
    return false;
  }

  private rebuildMfragIndex(): void {
    if (!this.mebnMfragMap) {
      this.uniqueMfrags = [];
      return;
    }
    const names = Object.values(this.mebnMfragMap);
    this.uniqueMfrags = [...new Set(names)];
  }

  /**
   * Heat colour interpolation: blue (0) → yellow (0.5) → red (1).
   * Matches the original D3 implementation for visual consistency.
   */
  private posteriorHeatColor(value: number): string {
    const r = value < 0.5 ? Math.round(value * 2 * 255) : 255;
    const g = value < 0.5
      ? Math.round(100 + value * 2 * 155)
      : Math.round(255 - (value - 0.5) * 2 * 200);
    const b = value < 0.5
      ? Math.round(255 - value * 2 * 200)
      : Math.round(55 - (value - 0.5) * 2 * 55);
    return `rgb(${r},${g},${b})`;
  }

  /**
   * Linear blend between two hex/rgb colours. alpha=0 → a, alpha=1 → b.
   * Used to produce a gentle prior-ring tint.
   */
  private blendColor(a: string, b: string, alpha: number): string {
    const pa = this.parseColor(a);
    const pb = this.parseColor(b);
    if (!pa || !pb) return b;
    const r = Math.round(pa[0] * (1 - alpha) + pb[0] * alpha);
    const g = Math.round(pa[1] * (1 - alpha) + pb[1] * alpha);
    const bl = Math.round(pa[2] * (1 - alpha) + pb[2] * alpha);
    return `rgb(${r},${g},${bl})`;
  }

  private parseColor(color: string): [number, number, number] | null {
    const hex = color.match(/^#([0-9a-f]{6})$/i);
    if (hex) {
      const v = parseInt(hex[1], 16);
      return [(v >> 16) & 255, (v >> 8) & 255, v & 255];
    }
    const rgb = color.match(/rgb\((\d+),\s*(\d+),\s*(\d+)\)/);
    if (rgb) return [+rgb[1], +rgb[2], +rgb[3]];
    return null;
  }

  // ── Legend helper methods (called from template) ──────────────────────────────

  getEdgeBorderStyle(edgeType: EdgeType): string {
    const pattern = EDGE_DASH_PATTERNS[edgeType];
    return pattern === 'none' ? 'solid' : 'dashed';
  }

  formatEdgeType(edgeType: EdgeType): string {
    return edgeType.toLowerCase().replace(/_/g, ' ');
  }

  /**
   * Human-readable label for an MFrag name — mirrors BayesianPanelComponent.fragLabel()
   * but lives here so the legend template can call it without importing the panel.
   */
  fragLabelCanvas(name: string | undefined | null): string {
    if (!name) return '';
    const tokens = name
      .replace(/([a-z0-9])([A-Z])/g, '$1 $2')
      .replace(/[_-]+/g, ' ')
      .trim()
      .split(/\s+/)
      .filter(w => w.length);
    const out: string[] = [];
    for (const w of tokens) {
      const titled = w.charAt(0).toUpperCase() + w.slice(1).toLowerCase();
      if (out.length === 0 || out[out.length - 1].toLowerCase() !== titled.toLowerCase()) {
        out.push(titled);
      }
    }
    return out.join(' ') || name;
  }

  /** Returns the MFRAG_ACCENT_COLORS entry for a given array index. */
  mfragAccentColor(index: number): string {
    return MFRAG_ACCENT_COLORS[index % MFRAG_ACCENT_COLORS.length];
  }
}
