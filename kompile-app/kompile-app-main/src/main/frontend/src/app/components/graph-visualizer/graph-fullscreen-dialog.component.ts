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

import { Component, Inject, OnDestroy, ViewChild, AfterViewInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { MatDialogRef, MAT_DIALOG_DATA, MatDialogModule } from '@angular/material/dialog';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatTooltipModule } from '@angular/material/tooltip';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatSliderModule } from '@angular/material/slider';
import { Subject, debounceTime, takeUntil } from 'rxjs';

import { GraphCanvasComponent } from './graph-canvas.component';
import {
  D3VisualizationData,
  D3Node,
  ForceConfig,
  DEFAULT_FORCE_CONFIG,
  NodeLevel,
  NODE_COLORS
} from '../../models/graph-models';

export interface GraphFullscreenDialogData {
  graphData: D3VisualizationData;
  forceConfig: ForceConfig;
  title?: string;
}

@Component({
  selector: 'app-graph-fullscreen-dialog',
  standalone: true,
  imports: [
    CommonModule,
    FormsModule,
    MatDialogModule,
    MatButtonModule,
    MatIconModule,
    MatTooltipModule,
    MatFormFieldModule,
    MatInputModule,
    MatCheckboxModule,
    MatSliderModule,
    GraphCanvasComponent
  ],
  template: `
    <div class="fullscreen-graph">
      <!-- Top Bar -->
      <div class="top-bar">
        <div class="top-bar-left">
          <mat-icon class="graph-icon">hub</mat-icon>
          <span class="title">{{ data.title || 'Graph Explorer' }}</span>
          <span class="stats" *ngIf="filteredData">
            {{ filteredData.nodes.length }} nodes &middot; {{ filteredData.links.length }} edges
          </span>
        </div>
        <div class="top-bar-center">
          <div class="search-box">
            <mat-icon>search</mat-icon>
            <input type="text"
                   placeholder="Search nodes..."
                   [(ngModel)]="searchQuery"
                   (ngModelChange)="onSearchChange($event)">
            <button *ngIf="searchQuery" class="clear-btn" (click)="clearSearch()">
              <mat-icon>close</mat-icon>
            </button>
          </div>
        </div>
        <div class="top-bar-right">
          <button mat-icon-button (click)="toggleDetailsPanel()" matTooltip="Node Details">
            <mat-icon>{{ showDetails ? 'info' : 'info_outline' }}</mat-icon>
          </button>
          <button mat-icon-button (click)="toggleFilterPanel()" matTooltip="Filters">
            <mat-icon>{{ showFilters ? 'filter_alt' : 'filter_alt_off' }}</mat-icon>
          </button>
          <button mat-icon-button (click)="close()" matTooltip="Close Fullscreen">
            <mat-icon>close</mat-icon>
          </button>
        </div>
      </div>

      <!-- Content Area -->
      <div class="content-area">
        <!-- Graph Canvas -->
        <div class="canvas-area">
          <app-graph-canvas
            #canvasRef
            [data]="filteredData"
            [forceConfig]="forceConfig"
            [linkMode]="false"
            [showLegend]="true"
            (nodeSelected)="onNodeSelected($event)"
            (nodeDoubleClicked)="onNodeDoubleClicked($event)">
          </app-graph-canvas>
        </div>

        <!-- Node Details Panel -->
        <div class="details-panel" *ngIf="showDetails && selectedNode">
          <div class="panel-header">
            <h3>{{ selectedNode.title || selectedNode.label }}</h3>
            <button mat-icon-button (click)="selectedNode = null" matTooltip="Clear Selection">
              <mat-icon>close</mat-icon>
            </button>
          </div>
          <div class="panel-body">
            <div class="detail-row">
              <span class="detail-label">Type</span>
              <span class="detail-value type-badge" [style.background-color]="getNodeColor(selectedNode.type)">
                {{ selectedNode.type }}
              </span>
            </div>
            <div class="detail-row" *ngIf="selectedNode.description">
              <span class="detail-label">Description</span>
              <span class="detail-value">{{ selectedNode.description }}</span>
            </div>
            <div class="detail-row" *ngIf="selectedNode.childCount !== undefined">
              <span class="detail-label">Children</span>
              <span class="detail-value">{{ selectedNode.childCount }}</span>
            </div>
            <div class="detail-row" *ngIf="selectedNode.edgeCount !== undefined">
              <span class="detail-label">Connections</span>
              <span class="detail-value">{{ selectedNode.edgeCount }}</span>
            </div>
            <div class="detail-row" *ngIf="selectedNode.metadata">
              <span class="detail-label">Metadata</span>
              <pre class="detail-meta">{{ selectedNode.metadata | json }}</pre>
            </div>
          </div>
        </div>

        <!-- Empty details hint -->
        <div class="details-panel details-hint" *ngIf="showDetails && !selectedNode">
          <mat-icon>touch_app</mat-icon>
          <span>Click a node to view details</span>
        </div>

        <!-- Filter Panel -->
        <div class="filter-panel" *ngIf="showFilters">
          <div class="panel-header">
            <h3>Filters</h3>
            <button mat-icon-button (click)="showFilters = false" matTooltip="Close">
              <mat-icon>close</mat-icon>
            </button>
          </div>
          <div class="panel-body">
            <div class="filter-section">
              <span class="filter-title">Node Types</span>
              <div class="filter-list">
                <label *ngFor="let type of allNodeTypes" class="filter-check">
                  <mat-checkbox [checked]="activeNodeTypes.has(type)"
                                (change)="toggleNodeType(type)">
                    <span class="filter-dot" [style.background-color]="nodeColors[type]"></span>
                    {{ type }}
                  </mat-checkbox>
                </label>
              </div>
            </div>
          </div>
        </div>
      </div>
    </div>
  `,
  styles: [`
    :host {
      display: block;
      width: 100%;
      height: 100%;
    }

    .fullscreen-graph {
      display: flex;
      flex-direction: column;
      width: 100vw;
      height: 100vh;
      background: var(--bg-body, #f8f9fa);
      color: var(--text-primary, #1a1f36);
    }

    /* Top Bar */
    .top-bar {
      display: flex;
      align-items: center;
      justify-content: space-between;
      padding: 0 16px;
      height: 52px;
      min-height: 52px;
      background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
      border-bottom: 1px solid var(--border-color, #e3e8ee);
      z-index: 10;
    }

    .top-bar-left {
      display: flex;
      align-items: center;
      gap: 12px;
    }

    .graph-icon {
      color: #ffffff;
      font-size: 24px;
      width: 24px;
      height: 24px;
    }

    .title {
      font-size: 16px;
      font-weight: 600;
      color: #ffffff;
    }

    .stats {
      font-size: 12px;
      color: rgba(255, 255, 255, 0.85);
      padding: 3px 10px;
      background: rgba(255, 255, 255, 0.15);
      border-radius: 12px;
    }

    .top-bar-center {
      flex: 1;
      max-width: 420px;
      margin: 0 24px;
    }

    .search-box {
      display: flex;
      align-items: center;
      gap: 8px;
      padding: 6px 12px;
      background: rgba(255, 255, 255, 0.15);
      border: 1px solid rgba(255, 255, 255, 0.25);
      border-radius: 8px;
      transition: border-color 0.2s, background 0.2s;
    }

    .search-box:focus-within {
      background: rgba(255, 255, 255, 0.25);
      border-color: rgba(255, 255, 255, 0.5);
    }

    .search-box mat-icon {
      color: rgba(255, 255, 255, 0.7);
      font-size: 18px;
      width: 18px;
      height: 18px;
    }

    .search-box input {
      flex: 1;
      background: none;
      border: none;
      outline: none;
      color: #ffffff;
      font-size: 13px;
    }

    .search-box input::placeholder {
      color: rgba(255, 255, 255, 0.6);
    }

    .clear-btn {
      background: none;
      border: none;
      cursor: pointer;
      padding: 0;
      display: flex;
    }

    .clear-btn mat-icon {
      color: rgba(255, 255, 255, 0.7);
      font-size: 16px;
      width: 16px;
      height: 16px;
    }

    .top-bar-right {
      display: flex;
      align-items: center;
      gap: 4px;
    }

    .top-bar-right button {
      color: rgba(255, 255, 255, 0.85);
    }

    .top-bar-right button:hover {
      color: #ffffff;
    }

    /* Content Area */
    .content-area {
      flex: 1;
      display: flex;
      min-height: 0;
      position: relative;
    }

    .canvas-area {
      flex: 1;
      display: flex;
      min-height: 0;
      min-width: 0;
    }

    /* Panels */
    .details-panel, .filter-panel {
      width: 320px;
      background: var(--bg-surface, #ffffff);
      border-left: 1px solid var(--border-color, #e3e8ee);
      display: flex;
      flex-direction: column;
      overflow-y: auto;
      box-shadow: -2px 0 8px rgba(0, 0, 0, 0.05);
    }

    .details-hint {
      align-items: center;
      justify-content: center;
      gap: 10px;
      color: var(--text-tertiary, #8792a2);
      font-size: 13px;
    }

    .details-hint mat-icon {
      font-size: 36px;
      width: 36px;
      height: 36px;
      color: var(--text-tertiary, #8792a2);
    }

    .panel-header {
      display: flex;
      align-items: center;
      justify-content: space-between;
      padding: 14px 16px;
      border-bottom: 1px solid var(--border-color, #e3e8ee);
    }

    .panel-header h3 {
      margin: 0;
      font-size: 14px;
      font-weight: 600;
      color: var(--text-primary, #1a1f36);
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
    }

    .panel-header button {
      color: var(--text-tertiary, #8792a2);
    }

    .panel-body {
      padding: 16px;
      flex: 1;
      overflow-y: auto;
    }

    .detail-row {
      margin-bottom: 14px;
    }

    .detail-label {
      display: block;
      font-size: 11px;
      font-weight: 600;
      color: var(--text-tertiary, #8792a2);
      text-transform: uppercase;
      letter-spacing: 0.5px;
      margin-bottom: 4px;
    }

    .detail-value {
      font-size: 13px;
      color: var(--text-primary, #1a1f36);
      line-height: 1.5;
    }

    .type-badge {
      display: inline-block;
      padding: 3px 10px;
      border-radius: 10px;
      font-size: 11px;
      font-weight: 600;
      color: #ffffff;
    }

    .detail-meta {
      background: #f1f5f9;
      padding: 10px;
      border-radius: 6px;
      font-size: 11px;
      font-family: var(--font-family-monospace, 'JetBrains Mono', monospace);
      color: var(--text-primary, #1a1f36);
      overflow-x: auto;
      max-height: 200px;
      margin: 0;
      border: 1px solid var(--border-color, #e3e8ee);
    }

    /* Filter Panel */
    .filter-section {
      margin-bottom: 20px;
    }

    .filter-title {
      display: block;
      font-size: 11px;
      font-weight: 600;
      color: var(--text-tertiary, #8792a2);
      text-transform: uppercase;
      letter-spacing: 0.5px;
      margin-bottom: 10px;
    }

    .filter-list {
      display: flex;
      flex-direction: column;
      gap: 6px;
    }

    .filter-check {
      font-size: 13px;
      color: var(--text-primary, #1a1f36);
    }

    .filter-dot {
      display: inline-block;
      width: 10px;
      height: 10px;
      border-radius: 50%;
      margin-right: 6px;
      vertical-align: middle;
    }
  `]
})
export class GraphFullscreenDialogComponent implements AfterViewInit, OnDestroy {
  @ViewChild('canvasRef') canvasRef!: GraphCanvasComponent;

  private destroy$ = new Subject<void>();
  private searchSubject = new Subject<string>();

  filteredData: D3VisualizationData;
  forceConfig: ForceConfig;
  selectedNode: D3Node | null = null;
  searchQuery = '';
  showDetails = true;
  showFilters = false;

  nodeColors = NODE_COLORS;
  allNodeTypes: NodeLevel[] = ['SOURCE', 'DOCUMENT', 'SNIPPET', 'ENTITY', 'ATTACHMENT', 'TABLE', 'CUSTOM'];
  activeNodeTypes = new Set<NodeLevel>(
    ['SOURCE', 'DOCUMENT', 'SNIPPET', 'ENTITY', 'ATTACHMENT', 'TABLE', 'CUSTOM']
  );

  constructor(
    public dialogRef: MatDialogRef<GraphFullscreenDialogComponent>,
    @Inject(MAT_DIALOG_DATA) public data: GraphFullscreenDialogData
  ) {
    this.filteredData = { ...data.graphData };
    this.forceConfig = { ...data.forceConfig };
  }

  ngAfterViewInit(): void {
    this.searchSubject.pipe(
      debounceTime(250),
      takeUntil(this.destroy$)
    ).subscribe(() => this.applyFilters());
  }

  ngOnDestroy(): void {
    this.destroy$.next();
    this.destroy$.complete();
  }

  close(): void {
    this.dialogRef.close();
  }

  onSearchChange(query: string): void {
    this.searchSubject.next(query);
  }

  clearSearch(): void {
    this.searchQuery = '';
    this.applyFilters();
  }

  onNodeSelected(node: D3Node | null): void {
    this.selectedNode = node;
    if (node) {
      this.showDetails = true;
    }
  }

  onNodeDoubleClicked(node: D3Node): void {
    this.selectedNode = node;
    this.showDetails = true;
  }

  toggleDetailsPanel(): void {
    this.showDetails = !this.showDetails;
  }

  toggleFilterPanel(): void {
    this.showFilters = !this.showFilters;
  }

  toggleNodeType(type: NodeLevel): void {
    if (this.activeNodeTypes.has(type)) {
      this.activeNodeTypes.delete(type);
    } else {
      this.activeNodeTypes.add(type);
    }
    this.applyFilters();
  }

  getNodeColor(type: NodeLevel): string {
    return NODE_COLORS[type] || '#64748b';
  }

  private applyFilters(): void {
    const source = this.data.graphData;
    let nodes = source.nodes.filter(n => this.activeNodeTypes.has(n.type));

    if (this.searchQuery) {
      const q = this.searchQuery.toLowerCase();
      nodes = nodes.filter(n =>
        (n.label || '').toLowerCase().includes(q) ||
        (n.title || '').toLowerCase().includes(q) ||
        (n.description || '').toLowerCase().includes(q)
      );
    }

    const nodeIds = new Set(nodes.map(n => n.id));
    const links = source.links.filter(l => nodeIds.has(l.source) && nodeIds.has(l.target));

    this.filteredData = { nodes, links };
  }
}
