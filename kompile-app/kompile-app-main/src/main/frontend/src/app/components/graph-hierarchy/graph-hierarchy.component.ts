/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */

import { Component, Input, Output, EventEmitter, OnInit, OnChanges, SimpleChanges } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { MatTreeModule, MatTreeNestedDataSource } from '@angular/material/tree';
import { MatIconModule } from '@angular/material/icon';
import { MatButtonModule } from '@angular/material/button';
import { MatChipsModule } from '@angular/material/chips';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatTooltipModule } from '@angular/material/tooltip';
import { MatInputModule } from '@angular/material/input';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatSelectModule } from '@angular/material/select';
import { NestedTreeControl } from '@angular/cdk/tree';

import { GraphService } from '../../services/graph.service';
import { HierarchyTreeNode, GraphNode, NodeLevel, NODE_COLORS } from '../../models/graph-models';

@Component({
  selector: 'app-graph-hierarchy',
  standalone: true,
  imports: [
    CommonModule,
    FormsModule,
    MatTreeModule,
    MatIconModule,
    MatButtonModule,
    MatChipsModule,
    MatProgressBarModule,
    MatProgressSpinnerModule,
    MatTooltipModule,
    MatInputModule,
    MatFormFieldModule,
    MatSelectModule
  ],
  template: `
    <div class="graph-hierarchy">
      <div class="hierarchy-toolbar">
        <mat-form-field appearance="outline" class="search-field">
          <mat-label>Search nodes</mat-label>
          <input matInput [(ngModel)]="searchQuery" (keyup.enter)="searchNodes()" placeholder="Search by title...">
          <mat-icon matSuffix>search</mat-icon>
        </mat-form-field>

        <mat-form-field appearance="outline" class="type-filter">
          <mat-label>Start from</mat-label>
          <mat-select [(ngModel)]="startNodeType" (selectionChange)="loadRootNodes()">
            <mat-option value="SOURCE">Sources</mat-option>
            <mat-option value="ENTITY">Entities</mat-option>
            <mat-option value="DOCUMENT">Documents</mat-option>
          </mat-select>
        </mat-form-field>

        <button mat-icon-button (click)="loadRootNodes()" matTooltip="Refresh">
          <mat-icon>refresh</mat-icon>
        </button>
      </div>

      <div class="breadcrumb-bar" *ngIf="ancestors.length > 0">
        <span class="breadcrumb-label">Path: </span>
        <span *ngFor="let ancestor of ancestors; let last = last" class="breadcrumb-item">
          <button mat-button class="breadcrumb-btn" (click)="navigateToNode(ancestor)">
            <mat-icon class="breadcrumb-icon" [style.color]="getNodeColor(ancestor.nodeType)">
              {{ getNodeIcon(ancestor.nodeType) }}
            </mat-icon>
            {{ ancestor.title }}
          </button>
          <mat-icon *ngIf="!last" class="breadcrumb-separator">chevron_right</mat-icon>
        </span>
      </div>

      <div class="loading-container" *ngIf="loading">
        <mat-spinner diameter="40"></mat-spinner>
        <span>Loading hierarchy...</span>
      </div>

      <div class="tree-container" *ngIf="!loading">
        <mat-tree [dataSource]="dataSource" [treeControl]="treeControl" class="hierarchy-tree">
          <mat-nested-tree-node *matTreeNodeDef="let node">
            <div class="tree-node" [class.selected]="selectedNode?.id === node.id">
              <div class="node-content" (click)="selectNode(node)">
                <button mat-icon-button
                        [attr.aria-label]="'Toggle ' + node.label"
                        (click)="toggleNode(node, $event)"
                        class="toggle-btn">
                  <mat-icon *ngIf="node.children?.length > 0 || node.hasMore">
                    {{ treeControl.isExpanded(node) ? 'expand_more' : 'chevron_right' }}
                  </mat-icon>
                  <mat-icon *ngIf="!node.children?.length && !node.hasMore" class="leaf-spacer">
                    remove
                  </mat-icon>
                </button>

                <mat-icon class="node-type-icon" [style.color]="getNodeColor(node.type)">
                  {{ getNodeIcon(node.type) }}
                </mat-icon>

                <span class="node-title">{{ node.label || node.title }}</span>

                <span class="node-type-badge" [style.background]="getNodeColor(node.type)">
                  {{ node.type }}
                </span>

                <span class="confidence-badge" *ngIf="node.confidence != null"
                      [class.high]="node.confidence >= 0.8"
                      [class.medium]="node.confidence >= 0.5 && node.confidence < 0.8"
                      [class.low]="node.confidence < 0.5"
                      [matTooltip]="'Certainty: ' + (node.confidence * 100).toFixed(0) + '%'">
                  {{ (node.confidence * 100).toFixed(0) }}%
                </span>

                <mat-icon *ngIf="node.isComposite" class="composite-icon"
                          matTooltip="Composite entity - contains a sub-graph">
                  account_tree
                </mat-icon>

                <span class="child-count" *ngIf="node.childCount">
                  ({{ node.childCount }})
                </span>
              </div>

              <div class="node-description" *ngIf="selectedNode?.id === node.id && node.description">
                {{ node.description }}
              </div>

              <div class="nested-nodes" *ngIf="treeControl.isExpanded(node)">
                <ng-container matTreeNodeOutlet></ng-container>
              </div>
            </div>
          </mat-nested-tree-node>
        </mat-tree>

        <div class="empty-state" *ngIf="dataSource.data.length === 0 && !loading">
          <mat-icon>account_tree</mat-icon>
          <p>No nodes found. Build a graph first or select a different node type.</p>
        </div>
      </div>

      <!-- Node Detail Panel -->
      <div class="detail-panel" *ngIf="selectedNode">
        <div class="detail-header">
          <div class="detail-title-row">
            <mat-icon [style.color]="getNodeColor(selectedNode.type)">
              {{ getNodeIcon(selectedNode.type) }}
            </mat-icon>
            <h3>{{ selectedNode.label || selectedNode.title }}</h3>
            <button mat-icon-button (click)="selectedNode = null" class="close-btn">
              <mat-icon>close</mat-icon>
            </button>
          </div>

          <div class="detail-badges">
            <span class="detail-badge type-badge" [style.background]="getNodeColor(selectedNode.type)">
              {{ selectedNode.type }}
            </span>
            <span class="detail-badge confidence-badge"
                  *ngIf="selectedNode.confidence != null"
                  [class.high]="selectedNode.confidence >= 0.8"
                  [class.medium]="selectedNode.confidence >= 0.5 && selectedNode.confidence < 0.8"
                  [class.low]="selectedNode.confidence < 0.5">
              Certainty: {{ (selectedNode.confidence * 100).toFixed(0) }}%
            </span>
            <span class="detail-badge composite-badge" *ngIf="selectedNode.isComposite">
              Composite Graph Entity
            </span>
          </div>
        </div>

        <div class="detail-body">
          <div class="detail-row" *ngIf="selectedNode.description">
            <label>Description</label>
            <p>{{ selectedNode.description }}</p>
          </div>
          <div class="detail-row">
            <label>Children</label>
            <span>{{ selectedNode.childCount || 0 }}</span>
          </div>
          <div class="detail-row">
            <label>Edges</label>
            <span>{{ selectedNode.edgeCount || 0 }}</span>
          </div>
          <div class="detail-row" *ngIf="selectedNode.subGraphId">
            <label>Sub-Graph ID</label>
            <span class="mono">{{ selectedNode.subGraphId }}</span>
          </div>
        </div>

        <div class="detail-actions">
          <button mat-stroked-button (click)="drillIntoNode(selectedNode)">
            <mat-icon>zoom_in</mat-icon>
            Drill Into
          </button>
          <button mat-stroked-button *ngIf="selectedNode.isComposite"
                  (click)="viewSubGraph.emit(selectedNode)">
            <mat-icon>account_tree</mat-icon>
            View Sub-Graph
          </button>
          <button mat-stroked-button (click)="navigateToGraph.emit(selectedNode)">
            <mat-icon>scatter_plot</mat-icon>
            View in Graph
          </button>
        </div>
      </div>
    </div>
  `,
  styles: [`
    .graph-hierarchy {
      display: flex;
      flex-direction: column;
      height: 100%;
      position: relative;
    }

    .hierarchy-toolbar {
      display: flex;
      gap: 12px;
      padding: 12px 16px;
      border-bottom: 1px solid #e0e0e0;
      align-items: center;
    }

    .search-field { flex: 1; }
    .type-filter { width: 160px; }

    .hierarchy-toolbar mat-form-field {
      margin-bottom: -1.25em;
    }

    .breadcrumb-bar {
      display: flex;
      align-items: center;
      padding: 8px 16px;
      background: #f5f5f5;
      border-bottom: 1px solid #e0e0e0;
      flex-wrap: wrap;
      gap: 2px;
    }

    .breadcrumb-label { font-size: 12px; color: #666; margin-right: 4px; }
    .breadcrumb-item { display: flex; align-items: center; }
    .breadcrumb-btn { min-width: 0; padding: 0 8px; font-size: 13px; }
    .breadcrumb-icon { font-size: 16px; width: 16px; height: 16px; margin-right: 4px; }
    .breadcrumb-separator { font-size: 18px; width: 18px; height: 18px; color: #999; }

    .loading-container {
      display: flex;
      flex-direction: column;
      align-items: center;
      justify-content: center;
      gap: 12px;
      padding: 48px;
      color: #666;
    }

    .tree-container {
      flex: 1;
      overflow: auto;
      padding: 8px;
    }

    .hierarchy-tree { background: transparent; }

    .tree-node { margin: 2px 0; }

    .node-content {
      display: flex;
      align-items: center;
      gap: 4px;
      padding: 4px 8px;
      border-radius: 6px;
      cursor: pointer;
      transition: background 0.15s;
    }

    .node-content:hover { background: #f0f0f0; }
    .tree-node.selected > .node-content { background: #e3f2fd; }

    .toggle-btn { width: 28px; height: 28px; line-height: 28px; }
    .toggle-btn mat-icon { font-size: 20px; }
    .leaf-spacer { color: #ccc; font-size: 14px; }

    .node-type-icon { font-size: 20px; width: 20px; height: 20px; }

    .node-title {
      flex: 1;
      font-size: 14px;
      font-weight: 500;
      white-space: nowrap;
      overflow: hidden;
      text-overflow: ellipsis;
    }

    .node-type-badge {
      font-size: 10px;
      padding: 1px 6px;
      border-radius: 10px;
      color: white;
      font-weight: 600;
      text-transform: uppercase;
    }

    .confidence-badge {
      font-size: 11px;
      padding: 1px 6px;
      border-radius: 10px;
      font-weight: 600;
    }
    .confidence-badge.high { background: #e8f5e9; color: #2e7d32; }
    .confidence-badge.medium { background: #fff3e0; color: #e65100; }
    .confidence-badge.low { background: #ffebee; color: #c62828; }

    .composite-icon { color: #ff9800; font-size: 18px; width: 18px; height: 18px; }

    .child-count { font-size: 12px; color: #999; }

    .node-description {
      padding: 4px 16px 4px 60px;
      font-size: 12px;
      color: #666;
      max-width: 500px;
    }

    .nested-nodes { padding-left: 24px; }

    .empty-state {
      display: flex;
      flex-direction: column;
      align-items: center;
      padding: 48px;
      color: #999;
    }
    .empty-state mat-icon { font-size: 48px; width: 48px; height: 48px; margin-bottom: 12px; }

    /* Detail panel */
    .detail-panel {
      position: absolute;
      right: 0;
      top: 0;
      bottom: 0;
      width: 360px;
      background: white;
      border-left: 1px solid #e0e0e0;
      box-shadow: -2px 0 8px rgba(0,0,0,0.1);
      overflow-y: auto;
      z-index: 10;
    }

    .detail-header {
      padding: 16px;
      border-bottom: 1px solid #e0e0e0;
    }

    .detail-title-row {
      display: flex;
      align-items: center;
      gap: 8px;
    }

    .detail-title-row h3 { flex: 1; margin: 0; font-size: 16px; }
    .close-btn { margin-left: auto; }

    .detail-badges {
      display: flex;
      gap: 6px;
      margin-top: 8px;
      flex-wrap: wrap;
    }

    .detail-badge {
      font-size: 11px;
      padding: 2px 8px;
      border-radius: 10px;
      font-weight: 600;
    }

    .type-badge { color: white; }
    .composite-badge { background: #fff3e0; color: #e65100; }

    .detail-body { padding: 16px; }

    .detail-row {
      margin-bottom: 12px;
    }

    .detail-row label {
      display: block;
      font-size: 11px;
      color: #999;
      text-transform: uppercase;
      font-weight: 600;
      margin-bottom: 2px;
    }

    .detail-row p { margin: 0; font-size: 13px; }
    .mono { font-family: monospace; font-size: 12px; }

    .detail-actions {
      padding: 16px;
      display: flex;
      flex-direction: column;
      gap: 8px;
    }

    .detail-actions button { width: 100%; justify-content: flex-start; }
    .detail-actions button mat-icon { margin-right: 8px; }
  `]
})
export class GraphHierarchyComponent implements OnInit, OnChanges {
  @Input() factSheetId: number | null = null;
  @Output() navigateToGraph = new EventEmitter<any>();
  @Output() viewSubGraph = new EventEmitter<any>();

  treeControl = new NestedTreeControl<HierarchyTreeNode>(node => node.children);
  dataSource = new MatTreeNestedDataSource<HierarchyTreeNode>();

  loading = false;
  searchQuery = '';
  startNodeType: string = 'SOURCE';
  selectedNode: HierarchyTreeNode | null = null;
  ancestors: GraphNode[] = [];

  constructor(private graphService: GraphService) {}

  ngOnInit(): void {
    this.loadRootNodes();
  }

  ngOnChanges(changes: SimpleChanges): void {
    if (changes['factSheetId']) {
      this.loadRootNodes();
    }
  }

  loadRootNodes(): void {
    this.loading = true;
    this.ancestors = [];
    this.selectedNode = null;

    const type = this.startNodeType as NodeLevel;
    this.graphService.getNodes(type, undefined, 100).subscribe({
      next: (nodes) => {
        // For each root node, load its hierarchy
        if (nodes.length === 0) {
          this.dataSource.data = [];
          this.loading = false;
          return;
        }

        const treeNodes: HierarchyTreeNode[] = nodes.map(n => ({
          nodeId: n.nodeId,
          nodeType: n.nodeType,
          id: n.nodeId,
          type: n.nodeType,
          label: n.title,
          title: n.title,
          description: n.description,
          childCount: n.childCount,
          edgeCount: n.edgeCount,
          confidence: n.confidence,
          isComposite: n.isComposite,
          subGraphId: n.subGraphId,
          depth: 0,
          children: [] as HierarchyTreeNode[],
          hasMore: (n.childCount || 0) > 0
        }));

        this.dataSource.data = treeNodes;
        this.loading = false;
      },
      error: () => {
        this.loading = false;
        this.dataSource.data = [];
      }
    });
  }

  toggleNode(node: HierarchyTreeNode, event: Event): void {
    event.stopPropagation();

    if (this.treeControl.isExpanded(node)) {
      this.treeControl.collapse(node);
    } else {
      // Lazy-load children if not yet loaded
      if ((!node.children || node.children.length === 0) && node.hasMore) {
        this.graphService.getHierarchy((node.id || node.nodeId)!, 2).subscribe({
          next: (tree) => {
            node.children = tree.children || [];
            node.hasMore = false;
            // Force refresh
            this.dataSource.data = [...this.dataSource.data];
            this.treeControl.expand(node);
          }
        });
      } else {
        this.treeControl.expand(node);
      }
    }
  }

  selectNode(node: HierarchyTreeNode): void {
    this.selectedNode = node;
    // Load ancestors for breadcrumb
    this.graphService.getAncestors((node.id || node.nodeId)!).subscribe({
      next: (ancestors) => this.ancestors = ancestors,
      error: () => this.ancestors = []
    });
  }

  drillIntoNode(node: HierarchyTreeNode): void {
    this.loading = true;
    this.graphService.getHierarchy((node.id || node.nodeId)!, 5).subscribe({
      next: (tree) => {
        this.dataSource.data = [tree];
        this.loading = false;
        this.treeControl.expand(tree);
      },
      error: () => this.loading = false
    });
  }

  navigateToNode(node: GraphNode): void {
    this.loading = true;
    this.graphService.getHierarchy(node.nodeId, 5).subscribe({
      next: (tree) => {
        this.dataSource.data = [tree];
        this.loading = false;
        this.treeControl.expand(tree);
      },
      error: () => this.loading = false
    });
  }

  searchNodes(): void {
    if (!this.searchQuery.trim()) {
      this.loadRootNodes();
      return;
    }

    this.loading = true;
    this.graphService.searchNodes(this.searchQuery, undefined, 50).subscribe({
      next: (nodes) => {
        const treeNodes: HierarchyTreeNode[] = nodes.map(n => ({
          nodeId: n.nodeId,
          nodeType: n.nodeType,
          id: n.nodeId,
          type: n.nodeType,
          label: n.title,
          title: n.title,
          description: n.description,
          childCount: n.childCount,
          edgeCount: n.edgeCount,
          confidence: n.confidence,
          isComposite: n.isComposite,
          subGraphId: n.subGraphId,
          depth: 0,
          children: [] as HierarchyTreeNode[],
          hasMore: (n.childCount || 0) > 0
        }));
        this.dataSource.data = treeNodes;
        this.loading = false;
      },
      error: () => this.loading = false
    });
  }

  getNodeColor(type: string | undefined): string {
    return (NODE_COLORS as any)[(type || '').toUpperCase()] || '#607D8B';
  }

  getNodeIcon(type: string | undefined): string {
    switch (type?.toUpperCase()) {
      case 'SOURCE': return 'dns';
      case 'DOCUMENT': return 'description';
      case 'SNIPPET': return 'short_text';
      case 'ENTITY': return 'label';
      case 'ATTACHMENT': return 'attach_file';
      case 'TABLE': return 'table_chart';
      case 'CUSTOM': return 'extension';
      default: return 'circle';
    }
  }
}
