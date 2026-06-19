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

import { Component, OnInit, OnDestroy, Input, Output, EventEmitter, ViewChild } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { trigger, state, style, transition, animate } from '@angular/animations';
import { MatCardModule } from '@angular/material/card';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatSelectModule } from '@angular/material/select';
import { MatInputModule } from '@angular/material/input';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatChipsModule } from '@angular/material/chips';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';
import { MatTabsModule } from '@angular/material/tabs';
import { MatTooltipModule } from '@angular/material/tooltip';
import { MatTableModule } from '@angular/material/table';
import { MatPaginatorModule, MatPaginator, PageEvent } from '@angular/material/paginator';
import { MatSortModule, MatSort, Sort } from '@angular/material/sort';
import { MatDividerModule } from '@angular/material/divider';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatButtonToggleModule } from '@angular/material/button-toggle';
import { MatMenuModule } from '@angular/material/menu';
import { MatExpansionModule } from '@angular/material/expansion';
import { MatDialog, MatDialogModule } from '@angular/material/dialog';
import { Subject, takeUntil, debounceTime, filter } from 'rxjs';
import { ConfirmDialogComponent, ConfirmDialogData } from '../confirm-dialog/confirm-dialog.component';
import { TableRendererComponent } from '../table-renderer/table-renderer.component';

import { GraphService } from '../../services/graph.service';
import { BayesianService } from '../../services/bayesian.service';
import {
  GraphNode,
  GraphEdge,
  NodeLevel,
  EdgeType,
  NODE_COLORS,
  NODE_SIZES,
  EDGE_COLORS
} from '../../models/graph-models';

interface EntityMebnData {
  posterior: number | null;
  prior: number | null;
  mfragName: string | null;
  nodeRole: string | null;
  entityType: string | null;
  entityId: string | null;
}

interface EntitySearchResult {
  entity: GraphNode;
  matchScore?: number;
  connectionCount: number;
}

interface ConnectionSearchResult {
  edge: GraphEdge;
  sourceNode?: GraphNode;
  targetNode?: GraphNode;
}

@Component({
  selector: 'app-entity-browser',
  standalone: true,
  animations: [
    trigger('slideIn', [
      state('void', style({ transform: 'translateX(100%)', opacity: 0 })),
      state('*', style({ transform: 'translateX(0)', opacity: 1 })),
      transition('void => *', [animate('250ms ease-out')]),
      transition('* => void', [animate('200ms ease-in')])
    ])
  ],
  imports: [
    CommonModule,
    FormsModule,
    MatCardModule,
    MatButtonModule,
    MatIconModule,
    MatSelectModule,
    MatInputModule,
    MatFormFieldModule,
    MatChipsModule,
    MatProgressSpinnerModule,
    MatSnackBarModule,
    MatTabsModule,
    MatTooltipModule,
    MatTableModule,
    MatPaginatorModule,
    MatSortModule,
    MatDividerModule,
    MatCheckboxModule,
    MatButtonToggleModule,
    MatMenuModule,
    MatExpansionModule,
    MatDialogModule,
    ConfirmDialogComponent,
    TableRendererComponent
  ],
  templateUrl: './entity-browser.component.html',
  styleUrls: ['./entity-browser.component.css']
})
export class EntityBrowserComponent implements OnInit, OnDestroy {
  private destroy$ = new Subject<void>();
  private searchSubject = new Subject<string>();
  private connectionSearchSubject = new Subject<string>();

  @Input() factSheetId: number | null = null;
  @Output() entitySelected = new EventEmitter<GraphNode>();
  @Output() navigateToGraph = new EventEmitter<GraphNode>();

  @ViewChild('entityPaginator') entityPaginator!: MatPaginator;
  @ViewChild('connectionPaginator') connectionPaginator!: MatPaginator;
  @ViewChild(MatSort) sort!: MatSort;

  // State
  loading = false;
  loadingConnections = false;
  selectedTabIndex = 0;
  viewMode: 'list' | 'grid' = 'list';

  // Entity browsing
  entities: GraphNode[] = [];
  filteredEntities: GraphNode[] = [];
  displayedEntities: GraphNode[] = [];
  selectedEntity: GraphNode | null = null;
  entitySearchQuery = '';
  entityTypeFilters: NodeLevel[] = [];
  allNodeTypes: NodeLevel[] = ['SOURCE', 'DOCUMENT', 'SNIPPET', 'ENTITY', 'CUSTOM', 'TABLE', 'ATTACHMENT'];
  entityPageSize = 20;
  entityPageIndex = 0;
  totalEntities = 0;

  // Connection browsing
  connections: GraphEdge[] = [];
  filteredConnections: GraphEdge[] = [];
  displayedConnections: GraphEdge[] = [];
  connectionSearchQuery = '';
  connectionTypeFilters: EdgeType[] = [];
  allEdgeTypes: EdgeType[] = ['HIERARCHICAL', 'EMBEDDING_SIMILARITY', 'SHARED_ENTITY', 'USER_DEFINED', 'CITATION', 'TEMPORAL', 'CROSS_SOURCE'];
  connectionPageSize = 20;
  connectionPageIndex = 0;
  totalConnections = 0;

  // Entity detail panel
  entityConnections: GraphEdge[] = [];
  connectedEntities: GraphNode[] = [];
  showEntityDetail = false;
  entityMebnData: EntityMebnData | null = null;

  // Statistics
  statistics: {
    totalNodes: number;
    totalEdges: number;
    nodesByType: Record<string, number>;
    edgesByType: Record<string, number>;
  } | null = null;

  // Node ID to Node map for quick lookups
  private nodeMap = new Map<string, GraphNode>();

  // Bayesian posteriors cache for list/grid indicators
  entityPosteriors: Record<string, number> = {};
  entityPriors: Record<string, number> = {};
  entityMebnMetaMap: Record<string, { mfragName: string; nodeRole: string; entityType?: string }> = {};

  // Table columns
  entityColumns = ['type', 'title', 'description', 'bayesian', 'connections', 'actions'];
  connectionColumns = ['type', 'source', 'target', 'weight', 'description', 'actions'];

  constructor(
    private graphService: GraphService,
    private bayesianService: BayesianService,
    private snackBar: MatSnackBar,
    private dialog: MatDialog
  ) {}

  ngOnInit(): void {
    this.loadEntities();
    this.loadStatistics();

    // Debounce entity search
    this.searchSubject.pipe(
      debounceTime(300),
      takeUntil(this.destroy$)
    ).subscribe(query => {
      this.entitySearchQuery = query;
      this.filterAndPaginateEntities();
    });

    // Debounce connection search
    this.connectionSearchSubject.pipe(
      debounceTime(300),
      takeUntil(this.destroy$)
    ).subscribe(query => {
      this.connectionSearchQuery = query;
      this.filterAndPaginateConnections();
    });
  }

  ngOnDestroy(): void {
    this.destroy$.next();
    this.destroy$.complete();
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // ENTITY OPERATIONS
  // ═══════════════════════════════════════════════════════════════════════════

  loadEntities(): void {
    this.loading = true;

    // Load all node types if no filter is selected
    const typesToLoad = this.entityTypeFilters.length > 0 ? this.entityTypeFilters : undefined;

    this.graphService.getNodes(typesToLoad?.[0], this.entitySearchQuery || undefined, 500)
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: (nodes) => {
          this.entities = nodes;
          this.buildNodeMap(nodes);
          this.filterAndPaginateEntities();
          this.loading = false;
        },
        error: (err) => {
          console.error('Failed to load entities:', err);
          this.snackBar.open('Failed to load entities', 'Dismiss', { duration: 3000 });
          this.loading = false;
        }
      });
  }

  private buildNodeMap(nodes: GraphNode[]): void {
    this.nodeMap.clear();
    nodes.forEach(node => {
      this.nodeMap.set(node.nodeId, node);
    });
  }

  /**
   * Load Bayesian posteriors/priors for displayed entities to show indicators in list/grid rows.
   */
  private loadEntityPosteriors(): void {
    const nodeIds = this.displayedEntities.map(e => e.nodeId).slice(0, 20);
    if (nodeIds.length === 0) return;

    // Use type-filtered MEBN query when a single entity type filter is active
    const inferenceCall = this.entityTypeFilters.length === 1
      ? this.bayesianService.queryMebnByType({
          seedNodeIds: nodeIds.slice(0, 5),
          entityType: this.entityTypeFilters[0],
          maxDepth: 2,
          maxNodes: 100
        })
      : this.bayesianService.queryMebnFromNode(nodeIds[0], 2, 100);

    inferenceCall
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: (result) => {
          const posteriors: Record<string, number> = {};
          const priors: Record<string, number> = {};
          const mebnMap: Record<string, { mfragName: string; nodeRole: string; entityType?: string }> = {};
          for (const [varName, nodeId] of Object.entries(result.variableToNodeId || {})) {
            if (result.posteriors?.[varName] != null) {
              posteriors[nodeId] = result.posteriors[varName];
            }
            if (result.priors?.[varName] != null) {
              priors[nodeId] = result.priors[varName];
            }
            const meta = result.variableToMebnMeta?.[varName];
            if (meta?.mfragName) {
              mebnMap[nodeId] = { mfragName: meta.mfragName, nodeRole: meta.nodeRole, entityType: meta.entityType ?? undefined };
            }
          }
          this.entityPosteriors = posteriors;
          this.entityPriors = priors;
          this.entityMebnMetaMap = mebnMap;
        },
        error: () => {
          // Bayesian data is optional
        }
      });
  }

  onEntitySearch(query: string): void {
    this.searchSubject.next(query);
  }

  filterAndPaginateEntities(): void {
    let filtered = [...this.entities];

    // Apply type filter
    if (this.entityTypeFilters.length > 0) {
      filtered = filtered.filter(e => this.entityTypeFilters.includes(e.nodeType));
    }

    // Apply search filter
    if (this.entitySearchQuery) {
      const query = this.entitySearchQuery.toLowerCase();
      filtered = filtered.filter(e =>
        e.title?.toLowerCase().includes(query) ||
        e.description?.toLowerCase().includes(query) ||
        e.nodeId?.toLowerCase().includes(query) ||
        e.externalId?.toLowerCase().includes(query)
      );
    }

    this.filteredEntities = filtered;
    this.totalEntities = filtered.length;

    // Paginate
    const start = this.entityPageIndex * this.entityPageSize;
    const end = start + this.entityPageSize;
    this.displayedEntities = filtered.slice(start, end);

    // Load Bayesian posteriors for displayed entities
    this.loadEntityPosteriors();
  }

  onEntityPageChange(event: PageEvent): void {
    this.entityPageIndex = event.pageIndex;
    this.entityPageSize = event.pageSize;
    this.filterAndPaginateEntities();
  }

  toggleEntityTypeFilter(type: NodeLevel): void {
    const index = this.entityTypeFilters.indexOf(type);
    if (index >= 0) {
      this.entityTypeFilters.splice(index, 1);
    } else {
      this.entityTypeFilters.push(type);
    }
    this.filterAndPaginateEntities();
  }

  clearEntityFilters(): void {
    this.entityTypeFilters = [];
    this.entitySearchQuery = '';
    this.filterAndPaginateEntities();
  }

  selectEntity(entity: GraphNode): void {
    this.selectedEntity = entity;
    this.showEntityDetail = true;
    this.entityMebnData = null;
    this.loadEntityConnections(entity);
    this.loadEntityMebnData(entity);
    this.entitySelected.emit(entity);
  }

  selectEntityByNodeId(nodeId: string): void {
    const entity = this.nodeMap.get(nodeId);
    if (entity) {
      this.selectEntity(entity);
    }
  }

  closeEntityDetail(): void {
    this.showEntityDetail = false;
    this.selectedEntity = null;
    this.entityConnections = [];
    this.connectedEntities = [];
    this.entityMebnData = null;
  }

  viewInGraph(entity: GraphNode): void {
    this.navigateToGraph.emit(entity);
  }

  deleteEntity(entity: GraphNode): void {
    const dialogData: ConfirmDialogData = {
      title: 'Delete Entity',
      message: `Are you sure you want to delete "${entity.title}"?`,
      confirmText: 'Delete',
      confirmColor: 'warn',
      icon: 'delete'
    };

    this.dialog.open(ConfirmDialogComponent, { data: dialogData })
      .afterClosed()
      .pipe(
        filter(confirmed => confirmed === true),
        takeUntil(this.destroy$)
      )
      .subscribe(() => {
        this.graphService.deleteNode(entity.nodeId)
          .pipe(takeUntil(this.destroy$))
          .subscribe({
            next: () => {
              this.snackBar.open('Entity deleted successfully', 'Dismiss', { duration: 2000 });
              this.loadEntities();
              if (this.selectedEntity?.nodeId === entity.nodeId) {
                this.closeEntityDetail();
              }
            },
            error: (err) => {
              console.error('Failed to delete entity:', err);
              this.snackBar.open('Failed to delete entity', 'Dismiss', { duration: 3000 });
            }
          });
      });
  }

  private loadEntityConnections(entity: GraphNode): void {
    this.graphService.getEdges(entity.nodeId)
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: (edges) => {
          this.entityConnections = edges;
          this.loadConnectedEntities(edges, entity.nodeId);
        },
        error: (err) => {
          console.error('Failed to load entity connections:', err);
          this.entityConnections = [];
        }
      });
  }

  private loadEntityMebnData(entity: GraphNode): void {
    this.bayesianService.mebnStructure(entity.nodeId, 2, 50)
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: (result) => {
          // Find the variable corresponding to this entity's nodeId
          let posterior: number | null = null;
          let prior: number | null = null;
          let mfragName: string | null = null;
          let nodeRole: string | null = null;
          let entityType: string | null = null;
          let entityId: string | null = null;

          for (const [varName, nodeId] of Object.entries(result.variableToNodeId || {})) {
            if (nodeId === entity.nodeId) {
              posterior = result.posteriors?.[varName] ?? null;
              prior = result.priors?.[varName] ?? null;
              const meta = result.variableToMebnMeta?.[varName];
              if (meta) {
                mfragName = meta.mfragName || null;
                nodeRole = meta.nodeRole || null;
                entityType = meta.entityType || null;
                entityId = meta.entityId || null;
              }
              break;
            }
          }

          if (posterior != null || mfragName != null) {
            this.entityMebnData = { posterior, prior, mfragName, nodeRole, entityType, entityId };
          }
        },
        error: () => {
          // MEBN data is optional — fail silently
        }
      });
  }

  getBayesianColor(value: number): string {
    if (value < 0.3) return '#3b82f6';
    if (value < 0.5) return '#f59e0b';
    if (value < 0.7) return '#f97316';
    return '#ef4444';
  }

  private loadConnectedEntities(edges: GraphEdge[], currentNodeId: string): void {
    const connectedIds = new Set<string>();
    edges.forEach(edge => {
      if (edge.sourceNodeId !== currentNodeId) {
        connectedIds.add(edge.sourceNodeId);
      }
      if (edge.targetNodeId !== currentNodeId) {
        connectedIds.add(edge.targetNodeId);
      }
    });

    // Get nodes from the map or fetch them
    this.connectedEntities = [];
    connectedIds.forEach(id => {
      const node = this.nodeMap.get(id);
      if (node) {
        this.connectedEntities.push(node);
      }
    });

    // If we don't have all nodes, fetch them
    const missingIds = Array.from(connectedIds).filter(id => !this.nodeMap.has(id));
    if (missingIds.length > 0) {
      // Fetch connected nodes
      this.graphService.getConnectedNodes(currentNodeId, 1)
        .pipe(takeUntil(this.destroy$))
        .subscribe({
          next: (nodes) => {
            nodes.forEach(node => {
              this.nodeMap.set(node.nodeId, node);
              if (!this.connectedEntities.find(e => e.nodeId === node.nodeId)) {
                this.connectedEntities.push(node);
              }
            });
          }
        });
    }
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // CONNECTION OPERATIONS
  // ═══════════════════════════════════════════════════════════════════════════

  loadConnections(): void {
    if (this.selectedTabIndex !== 1) return;

    this.loadingConnections = true;

    // Use the more efficient getAllEdges method with optional search query
    const typeFilter = this.connectionTypeFilters.length === 1 ? this.connectionTypeFilters[0] : undefined;
    const query = this.connectionSearchQuery || undefined;

    this.graphService.getAllEdges(query, typeFilter, 200)
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: (edges) => {
          this.connections = edges;
          this.filterAndPaginateConnections();
          this.loadingConnections = false;
        },
        error: (err) => {
          console.error('Failed to load connections:', err);
          // Fallback to loading edges for each entity
          this.loadConnectionsFallback();
        }
      });
  }

  private loadConnectionsFallback(): void {
    if (this.entities.length === 0) {
      this.loadingConnections = false;
      return;
    }

    const nodeIds = this.entities.slice(0, 50).map(e => e.nodeId);
    const allEdges: GraphEdge[] = [];
    const edgeIds = new Set<string>();
    let completed = 0;

    nodeIds.forEach(nodeId => {
      this.graphService.getEdges(nodeId)
        .pipe(takeUntil(this.destroy$))
        .subscribe({
          next: (edges) => {
            edges.forEach(edge => {
              if (!edgeIds.has(edge.edgeId)) {
                edgeIds.add(edge.edgeId);
                allEdges.push(edge);
              }
            });
            completed++;
            if (completed === nodeIds.length) {
              this.connections = allEdges;
              this.filterAndPaginateConnections();
              this.loadingConnections = false;
            }
          },
          error: () => {
            completed++;
            if (completed === nodeIds.length) {
              this.connections = allEdges;
              this.filterAndPaginateConnections();
              this.loadingConnections = false;
            }
          }
        });
    });
  }

  onConnectionSearch(query: string): void {
    this.connectionSearchSubject.next(query);
  }

  filterAndPaginateConnections(): void {
    let filtered = [...this.connections];

    // Apply type filter
    if (this.connectionTypeFilters.length > 0) {
      filtered = filtered.filter(c => this.connectionTypeFilters.includes(c.edgeType));
    }

    // Apply search filter
    if (this.connectionSearchQuery) {
      const query = this.connectionSearchQuery.toLowerCase();
      filtered = filtered.filter(c => {
        const sourceNode = this.nodeMap.get(c.sourceNodeId);
        const targetNode = this.nodeMap.get(c.targetNodeId);
        return (
          c.description?.toLowerCase().includes(query) ||
          sourceNode?.title?.toLowerCase().includes(query) ||
          targetNode?.title?.toLowerCase().includes(query) ||
          c.edgeType.toLowerCase().includes(query)
        );
      });
    }

    this.filteredConnections = filtered;
    this.totalConnections = filtered.length;

    // Paginate
    const start = this.connectionPageIndex * this.connectionPageSize;
    const end = start + this.connectionPageSize;
    this.displayedConnections = filtered.slice(start, end);
  }

  onConnectionPageChange(event: PageEvent): void {
    this.connectionPageIndex = event.pageIndex;
    this.connectionPageSize = event.pageSize;
    this.filterAndPaginateConnections();
  }

  toggleConnectionTypeFilter(type: EdgeType): void {
    const index = this.connectionTypeFilters.indexOf(type);
    if (index >= 0) {
      this.connectionTypeFilters.splice(index, 1);
    } else {
      this.connectionTypeFilters.push(type);
    }
    this.filterAndPaginateConnections();
  }

  clearConnectionFilters(): void {
    this.connectionTypeFilters = [];
    this.connectionSearchQuery = '';
    this.filterAndPaginateConnections();
  }

  deleteConnection(edge: GraphEdge): void {
    const dialogData: ConfirmDialogData = {
      title: 'Delete Connection',
      message: 'Are you sure you want to delete this connection?',
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
        this.graphService.deleteEdge(edge.edgeId)
          .pipe(takeUntil(this.destroy$))
          .subscribe({
            next: () => {
              this.snackBar.open('Connection deleted successfully', 'Dismiss', { duration: 2000 });
              this.loadConnections();
              if (this.selectedEntity) {
                this.loadEntityConnections(this.selectedEntity);
              }
            },
            error: (err) => {
              console.error('Failed to delete connection:', err);
              this.snackBar.open('Failed to delete connection', 'Dismiss', { duration: 3000 });
            }
          });
      });
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // STATISTICS
  // ═══════════════════════════════════════════════════════════════════════════

  loadStatistics(): void {
    this.graphService.getStatistics()
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: (stats: any) => {
          this.statistics = {
            totalNodes: stats.totalNodes || 0,
            totalEdges: stats.totalEdges || 0,
            nodesByType: stats.nodesByType || {},
            edgesByType: stats.edgesByType || {}
          };
        },
        error: (err) => {
          console.error('Failed to load statistics:', err);
        }
      });
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // TAB HANDLING
  // ═══════════════════════════════════════════════════════════════════════════

  onTabChange(index: number): void {
    this.selectedTabIndex = index;
    if (index === 1 && this.connections.length === 0) {
      this.loadConnections();
    }
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // UTILITY METHODS
  // ═══════════════════════════════════════════════════════════════════════════

  getNodeColor(type: NodeLevel): string {
    return NODE_COLORS[type] || '#64748b';
  }

  getEdgeColor(type: EdgeType): string {
    return EDGE_COLORS[type] || '#9E9E9E';
  }

  getNodeLabel(nodeId: string): string {
    const node = this.nodeMap.get(nodeId);
    return node?.title || nodeId;
  }

  formatEdgeType(type: EdgeType): string {
    return type.toLowerCase().replace(/_/g, ' ');
  }

  formatNodeType(type: NodeLevel): string {
    return type.charAt(0) + type.slice(1).toLowerCase();
  }

  getTypeCount(type: NodeLevel): number {
    return this.statistics?.nodesByType?.[type] || 0;
  }

  /**
   * True when this entity should render as a table — independent of which knowledge-graph
   * store produced it. TABLE nodes always qualify; so does any node carrying full table
   * markdown in metadata (defensive: some backends may tag table content on other levels).
   */
  isTableEntity(entity: GraphNode | null): boolean {
    return !!entity && (entity.nodeType === 'TABLE' || !!entity.metadata?.['fullTableContent']);
  }

  /** Best-available table markdown: full content if persisted, otherwise the preview description. */
  getTableMarkdown(entity: GraphNode | null): string {
    return (entity?.metadata?.['fullTableContent'] as string) || entity?.description || '';
  }

  getEdgeTypeCount(type: EdgeType): number {
    return this.statistics?.edgesByType?.[type] || 0;
  }

  refreshData(): void {
    this.loadEntities();
    this.loadStatistics();
    if (this.selectedTabIndex === 1) {
      this.loadConnections();
    }
  }
}
