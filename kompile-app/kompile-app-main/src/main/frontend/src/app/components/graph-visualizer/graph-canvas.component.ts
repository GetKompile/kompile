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
import * as d3 from 'd3';
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

interface SimulationNode extends D3Node {
  x: number;
  y: number;
  vx?: number;
  vy?: number;
  fx?: number | null;
  fy?: number | null;
}

interface SimulationLink extends Omit<D3Link, 'source' | 'target'> {
  source: SimulationNode | string;
  target: SimulationNode | string;
}

@Component({
  selector: 'app-graph-canvas',
  standalone: true,
  imports: [CommonModule],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="graph-canvas-container" #container>
      <svg #svgElement class="graph-svg">
        <defs>
          <!-- Arrow markers for directed edges -->
          <marker id="arrow" viewBox="0 -5 10 10" refX="20" refY="0"
                  markerWidth="6" markerHeight="6" orient="auto">
            <path d="M0,-5L10,0L0,5" fill="#999"/>
          </marker>
          <marker id="arrow-similarity" viewBox="0 -5 10 10" refX="20" refY="0"
                  markerWidth="6" markerHeight="6" orient="auto">
            <path d="M0,-5L10,0L0,5" [attr.fill]="edgeColors.EMBEDDING_SIMILARITY"/>
          </marker>
          <marker id="arrow-entity" viewBox="0 -5 10 10" refX="20" refY="0"
                  markerWidth="6" markerHeight="6" orient="auto">
            <path d="M0,-5L10,0L0,5" [attr.fill]="edgeColors.SHARED_ENTITY"/>
          </marker>
          <marker id="arrow-user" viewBox="0 -5 10 10" refX="20" refY="0"
                  markerWidth="6" markerHeight="6" orient="auto">
            <path d="M0,-5L10,0L0,5" [attr.fill]="edgeColors.USER_DEFINED"/>
          </marker>
        </defs>
        <g class="zoom-container">
          <g class="links"></g>
          <g class="nodes"></g>
          <g class="labels"></g>
        </g>
      </svg>
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
      </div>
    </div>
  `,
  styles: [`
    .graph-canvas-container {
      position: relative;
      width: 100%;
      height: 100%;
      background: linear-gradient(135deg, #f8fafc 0%, #f1f5f9 100%);
      overflow: hidden;
    }

    .graph-svg {
      width: 100%;
      height: 100%;
    }

    .zoom-controls {
      position: absolute;
      top: 16px;
      right: 16px;
      display: flex;
      flex-direction: column;
      gap: 6px;
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
      background: #f1f5f9;
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
  `]
})
export class GraphCanvasComponent implements OnInit, OnChanges, OnDestroy {
  @ViewChild('container', { static: true }) containerRef!: ElementRef<HTMLDivElement>;
  @ViewChild('svgElement', { static: true }) svgRef!: ElementRef<SVGSVGElement>;

  @Input() data: D3VisualizationData | null = null;
  @Input() forceConfig: ForceConfig = DEFAULT_FORCE_CONFIG;
  @Input() showLegend: boolean = true;
  @Input() linkMode: boolean = false;

  @Output() nodeSelected = new EventEmitter<D3Node | null>();
  @Output() nodeDoubleClicked = new EventEmitter<D3Node>();
  @Output() edgeCreated = new EventEmitter<{ source: string; target: string }>();
  @Output() nodeContextMenu = new EventEmitter<{ node: D3Node; event: MouseEvent }>();
  @Output() linkSourceChanged = new EventEmitter<D3Node | null>();

  nodeColors = NODE_COLORS;
  edgeColors = EDGE_COLORS;
  nodeTypes: NodeLevel[] = ['SOURCE', 'DOCUMENT', 'SNIPPET', 'ENTITY', 'CUSTOM'];
  edgeTypes: EdgeType[] = ['HIERARCHICAL', 'EMBEDDING_SIMILARITY', 'SHARED_ENTITY', 'USER_DEFINED'];

  private svg!: d3.Selection<SVGSVGElement, unknown, null, undefined>;
  private zoomContainer!: d3.Selection<SVGGElement, unknown, null, undefined>;
  private linksGroup!: d3.Selection<SVGGElement, unknown, null, undefined>;
  private nodesGroup!: d3.Selection<SVGGElement, unknown, null, undefined>;
  private labelsGroup!: d3.Selection<SVGGElement, unknown, null, undefined>;

  private simulation!: d3.Simulation<SimulationNode, SimulationLink>;
  private zoom!: d3.ZoomBehavior<SVGSVGElement, unknown>;

  private nodes: SimulationNode[] = [];
  private links: SimulationLink[] = [];
  private selectedNode: SimulationNode | null = null;
  private linkSourceNode: SimulationNode | null = null;

  private resizeObserver!: ResizeObserver;
  private width = 0;
  private height = 0;

  constructor(
    private cdr: ChangeDetectorRef,
    private ngZone: NgZone
  ) {}

  ngOnInit(): void {
    this.initializeSvg();
    this.initializeZoom();
    this.initializeSimulation();
    this.setupResizeObserver();
  }

  ngOnChanges(changes: SimpleChanges): void {
    if (changes['data'] && this.data && this.simulation) {
      this.updateGraph();
    }
    if (changes['forceConfig'] && this.simulation) {
      this.updateForces();
    }
  }

  ngOnDestroy(): void {
    if (this.resizeObserver) {
      this.resizeObserver.disconnect();
    }
    if (this.simulation) {
      this.simulation.stop();
    }
  }

  private initializeSvg(): void {
    this.svg = d3.select(this.svgRef.nativeElement);
    this.zoomContainer = this.svg.select('.zoom-container') as d3.Selection<SVGGElement, unknown, null, undefined>;
    this.linksGroup = this.zoomContainer.select('.links') as d3.Selection<SVGGElement, unknown, null, undefined>;
    this.nodesGroup = this.zoomContainer.select('.nodes') as d3.Selection<SVGGElement, unknown, null, undefined>;
    this.labelsGroup = this.zoomContainer.select('.labels') as d3.Selection<SVGGElement, unknown, null, undefined>;

    const rect = this.containerRef.nativeElement.getBoundingClientRect();
    this.width = rect.width || 800;
    this.height = rect.height || 600;
  }

  private initializeZoom(): void {
    this.zoom = d3.zoom<SVGSVGElement, unknown>()
      .scaleExtent([0.1, 4])
      .on('zoom', (event) => {
        this.zoomContainer.attr('transform', event.transform);
      });

    this.svg.call(this.zoom);
  }

  private initializeSimulation(): void {
    this.simulation = d3.forceSimulation<SimulationNode, SimulationLink>()
      .force('link', d3.forceLink<SimulationNode, SimulationLink>()
        .id(d => d.id)
        .distance(this.forceConfig.linkDistance)
        .strength(this.forceConfig.linkStrength))
      .force('charge', d3.forceManyBody()
        .strength(this.forceConfig.chargeStrength))
      .force('collision', d3.forceCollide()
        .radius(this.forceConfig.collisionRadius))
      .force('center', d3.forceCenter(this.width / 2, this.height / 2)
        .strength(this.forceConfig.centerStrength))
      .alphaDecay(this.forceConfig.alphaDecay)
      .velocityDecay(this.forceConfig.velocityDecay);

    this.simulation.on('tick', () => this.ticked());
  }

  private updateForces(): void {
    this.simulation
      .force('link', d3.forceLink<SimulationNode, SimulationLink>()
        .id(d => d.id)
        .distance(this.forceConfig.linkDistance)
        .strength(this.forceConfig.linkStrength))
      .force('charge', d3.forceManyBody()
        .strength(this.forceConfig.chargeStrength))
      .force('collision', d3.forceCollide()
        .radius(this.forceConfig.collisionRadius))
      .force('center', d3.forceCenter(this.width / 2, this.height / 2)
        .strength(this.forceConfig.centerStrength))
      .alphaDecay(this.forceConfig.alphaDecay)
      .velocityDecay(this.forceConfig.velocityDecay);

    this.simulation.alpha(0.3).restart();
  }

  private setupResizeObserver(): void {
    this.resizeObserver = new ResizeObserver(entries => {
      for (const entry of entries) {
        this.width = entry.contentRect.width;
        this.height = entry.contentRect.height;
        this.simulation.force('center', d3.forceCenter(this.width / 2, this.height / 2));
        this.simulation.alpha(0.1).restart();
      }
    });
    this.resizeObserver.observe(this.containerRef.nativeElement);
  }

  private updateGraph(): void {
    if (!this.data) return;

    // Transform data to simulation format
    this.nodes = this.data.nodes.map(n => ({
      ...n,
      x: this.width / 2 + Math.random() * 100 - 50,
      y: this.height / 2 + Math.random() * 100 - 50
    }));

    this.links = this.data.links.map(l => ({ ...l }));

    // Update simulation data
    this.simulation.nodes(this.nodes);
    const linkForce = this.simulation.force('link') as d3.ForceLink<SimulationNode, SimulationLink>;
    if (linkForce) {
      linkForce.links(this.links);
    }

    // Render elements
    this.renderLinks();
    this.renderNodes();
    this.renderLabels();

    // Restart simulation
    this.simulation.alpha(1).restart();
  }

  private renderLinks(): void {
    const linkSelection = this.linksGroup
      .selectAll<SVGLineElement, SimulationLink>('line')
      .data(this.links, d => d.id);

    linkSelection.exit().remove();

    const linkEnter = linkSelection.enter()
      .append('line')
      .attr('stroke', d => EDGE_COLORS[d.type] || '#999')
      .attr('stroke-width', d => Math.max(1, (d.weight || 1) * 2))
      .attr('stroke-dasharray', d => EDGE_DASH_PATTERNS[d.type] || 'none')
      .attr('marker-end', d => this.getMarker(d.type));

    linkSelection.merge(linkEnter)
      .attr('stroke', d => EDGE_COLORS[d.type] || '#999')
      .attr('stroke-width', d => Math.max(1, (d.weight || 1) * 2))
      .attr('stroke-dasharray', d => EDGE_DASH_PATTERNS[d.type] || 'none');
  }

  private renderNodes(): void {
    const nodeSelection = this.nodesGroup
      .selectAll<SVGCircleElement, SimulationNode>('circle')
      .data(this.nodes, d => d.id);

    nodeSelection.exit().remove();

    const nodeEnter = nodeSelection.enter()
      .append('circle')
      .attr('r', d => NODE_SIZES[d.type] || 10)
      .attr('fill', d => NODE_COLORS[d.type] || '#999')
      .attr('stroke', '#ffffff')
      .attr('stroke-width', 2.5)
      .attr('cursor', 'pointer')
      .style('filter', 'drop-shadow(0 2px 4px rgba(0, 0, 0, 0.15))')
      .call(this.drag() as any);

    nodeEnter
      .on('click', (event, d) => this.onNodeClick(event, d))
      .on('dblclick', (event, d) => this.onNodeDoubleClick(event, d))
      .on('contextmenu', (event, d) => this.onNodeContextMenu(event, d));

    nodeSelection.merge(nodeEnter)
      .attr('r', d => NODE_SIZES[d.type] || 10)
      .attr('fill', d => NODE_COLORS[d.type] || '#999')
      .classed('selected', d => this.selectedNode?.id === d.id);
  }

  private renderLabels(): void {
    const labelSelection = this.labelsGroup
      .selectAll<SVGTextElement, SimulationNode>('text')
      .data(this.nodes, d => d.id);

    labelSelection.exit().remove();

    const labelEnter = labelSelection.enter()
      .append('text')
      .attr('font-size', '11px')
      .attr('font-weight', '500')
      .attr('fill', '#1a1f36')
      .attr('text-anchor', 'middle')
      .attr('dy', d => -(NODE_SIZES[d.type] || 10) - 8)
      .text(d => this.truncateLabel(d.label || d.title || d.id, 20));

    labelSelection.merge(labelEnter)
      .text(d => this.truncateLabel(d.label || d.title || d.id, 20));
  }

  private ticked(): void {
    // Update link positions
    this.linksGroup.selectAll<SVGLineElement, SimulationLink>('line')
      .attr('x1', d => (d.source as SimulationNode).x)
      .attr('y1', d => (d.source as SimulationNode).y)
      .attr('x2', d => (d.target as SimulationNode).x)
      .attr('y2', d => (d.target as SimulationNode).y);

    // Update node positions
    this.nodesGroup.selectAll<SVGCircleElement, SimulationNode>('circle')
      .attr('cx', d => d.x)
      .attr('cy', d => d.y);

    // Update label positions
    this.labelsGroup.selectAll<SVGTextElement, SimulationNode>('text')
      .attr('x', d => d.x)
      .attr('y', d => d.y);
  }

  private drag(): d3.DragBehavior<SVGCircleElement, SimulationNode, SimulationNode | d3.SubjectPosition> {
    return d3.drag<SVGCircleElement, SimulationNode>()
      .on('start', (event, d) => {
        if (!event.active) this.simulation.alphaTarget(0.3).restart();
        d.fx = d.x;
        d.fy = d.y;
      })
      .on('drag', (event, d) => {
        d.fx = event.x;
        d.fy = event.y;
      })
      .on('end', (event, d) => {
        if (!event.active) this.simulation.alphaTarget(0);
        // Keep node pinned if shift is held, otherwise release
        if (!event.sourceEvent.shiftKey) {
          d.fx = null;
          d.fy = null;
        }
      });
  }

  private onNodeClick(event: MouseEvent, node: SimulationNode): void {
    event.stopPropagation();

    if (this.linkMode && this.linkSourceNode) {
      // Complete link creation
      if (this.linkSourceNode.id !== node.id) {
        this.ngZone.run(() => {
          this.edgeCreated.emit({
            source: this.linkSourceNode!.id,
            target: node.id
          });
        });
      }
      this.linkSourceNode = null;
      this.ngZone.run(() => {
        this.linkSourceChanged.emit(null);
      });
    } else if (this.linkMode) {
      // Start link creation
      this.linkSourceNode = node;
      this.ngZone.run(() => {
        this.linkSourceChanged.emit(node);
      });
    } else {
      // Normal selection
      this.selectedNode = this.selectedNode?.id === node.id ? null : node;
      this.ngZone.run(() => {
        this.nodeSelected.emit(this.selectedNode);
        this.cdr.markForCheck();
      });
    }

    // Update visual selection
    this.nodesGroup.selectAll<SVGCircleElement, SimulationNode>('circle')
      .classed('selected', d => this.selectedNode?.id === d.id || this.linkSourceNode?.id === d.id)
      .attr('stroke', d => {
        if (this.linkSourceNode?.id === d.id) return '#fbbf24'; // Gold for link source
        if (this.selectedNode?.id === d.id) return '#667eea'; // Purple for selected
        return '#ffffff';
      })
      .attr('stroke-width', d => (this.selectedNode?.id === d.id || this.linkSourceNode?.id === d.id) ? 4 : 2);
  }

  private onNodeDoubleClick(event: MouseEvent, node: SimulationNode): void {
    event.stopPropagation();
    this.ngZone.run(() => {
      this.nodeDoubleClicked.emit(node);
    });
  }

  private onNodeContextMenu(event: MouseEvent, node: SimulationNode): void {
    event.preventDefault();
    event.stopPropagation();
    this.ngZone.run(() => {
      this.nodeContextMenu.emit({ node, event });
    });
  }

  // Public zoom methods
  zoomIn(): void {
    this.svg.transition().duration(300).call(this.zoom.scaleBy, 1.3);
  }

  zoomOut(): void {
    this.svg.transition().duration(300).call(this.zoom.scaleBy, 0.7);
  }

  resetZoom(): void {
    this.svg.transition().duration(500).call(
      this.zoom.transform,
      d3.zoomIdentity
    );
  }

  fitToScreen(): void {
    if (this.nodes.length === 0) return;

    const padding = 50;
    const bounds = this.getBounds();
    const dx = bounds.maxX - bounds.minX;
    const dy = bounds.maxY - bounds.minY;
    const x = (bounds.minX + bounds.maxX) / 2;
    const y = (bounds.minY + bounds.maxY) / 2;
    const scale = Math.min(
      (this.width - 2 * padding) / dx,
      (this.height - 2 * padding) / dy,
      2
    );

    this.svg.transition().duration(500).call(
      this.zoom.transform,
      d3.zoomIdentity
        .translate(this.width / 2, this.height / 2)
        .scale(scale)
        .translate(-x, -y)
    );
  }

  private getBounds(): { minX: number; maxX: number; minY: number; maxY: number } {
    let minX = Infinity, maxX = -Infinity;
    let minY = Infinity, maxY = -Infinity;

    for (const node of this.nodes) {
      minX = Math.min(minX, node.x);
      maxX = Math.max(maxX, node.x);
      minY = Math.min(minY, node.y);
      maxY = Math.max(maxY, node.y);
    }

    return { minX, maxX, minY, maxY };
  }

  private getMarker(edgeType: EdgeType): string {
    switch (edgeType) {
      case 'EMBEDDING_SIMILARITY': return 'url(#arrow-similarity)';
      case 'SHARED_ENTITY': return 'url(#arrow-entity)';
      case 'USER_DEFINED': return 'url(#arrow-user)';
      default: return 'url(#arrow)';
    }
  }

  private truncateLabel(text: string, maxLength: number): string {
    if (text.length <= maxLength) return text;
    return text.substring(0, maxLength - 3) + '...';
  }

  getEdgeBorderStyle(edgeType: EdgeType): string {
    const pattern = EDGE_DASH_PATTERNS[edgeType];
    return pattern === 'none' ? 'solid' : 'dashed';
  }

  formatEdgeType(edgeType: EdgeType): string {
    return edgeType.toLowerCase().replace(/_/g, ' ');
  }
}
