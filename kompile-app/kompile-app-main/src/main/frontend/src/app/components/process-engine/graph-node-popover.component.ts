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

import { Component, Input, Output, EventEmitter, OnChanges, SimpleChanges } from '@angular/core';
import { CommonModule } from '@angular/common';
import { MatIconModule } from '@angular/material/icon';
import { MatButtonModule } from '@angular/material/button';
import { MatChipsModule } from '@angular/material/chips';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatTooltipModule } from '@angular/material/tooltip';
import { GraphService } from '../../services/graph.service';
import { GraphNode } from '../../models/graph-models';

@Component({
  standalone: true,
  selector: 'app-graph-node-popover',
  imports: [
    CommonModule, MatIconModule, MatButtonModule, MatChipsModule, MatProgressSpinnerModule, MatTooltipModule
  ],
  template: `
    <div class="popover-card" *ngIf="visible && nodeId">
      <!-- Loading -->
      <div class="popover-loading" *ngIf="loading">
        <mat-spinner diameter="24"></mat-spinner>
        <span>Loading node...</span>
      </div>

      <!-- Error -->
      <div class="popover-error" *ngIf="error && !loading">
        <mat-icon>error_outline</mat-icon>
        <span>{{ error }}</span>
      </div>

      <!-- Content -->
      <div class="popover-content" *ngIf="node && !loading && !error">
        <!-- Header -->
        <div class="popover-header">
          <mat-chip class="type-chip" [ngClass]="'type-' + node.nodeType?.toLowerCase()">
            {{ node.nodeType }}
          </mat-chip>
          <button mat-icon-button class="close-btn" (click)="close()">
            <mat-icon>close</mat-icon>
          </button>
        </div>

        <!-- Title -->
        <h4 class="node-title">{{ node.title || 'Untitled' }}</h4>

        <!-- Description -->
        <p class="node-desc" *ngIf="node.description">{{ node.description }}</p>

        <!-- Metadata -->
        <div class="meta-grid">
          <div class="meta-item" *ngIf="node.sourceType">
            <span class="meta-label">Source Type</span>
            <span class="meta-value">{{ node.sourceType }}</span>
          </div>
          <div class="meta-item" *ngIf="node.pathOrUrl">
            <span class="meta-label">Path</span>
            <span class="meta-value path-value">{{ node.pathOrUrl }}</span>
          </div>
          <div class="meta-item" *ngIf="node.confidence != null">
            <span class="meta-label">Confidence</span>
            <span class="meta-value">{{ (node.confidence * 100).toFixed(0) }}%</span>
          </div>
          <div class="meta-item" *ngIf="node.childCount > 0">
            <span class="meta-label">Children</span>
            <span class="meta-value">{{ node.childCount }}</span>
          </div>
          <div class="meta-item" *ngIf="node.edgeCount > 0">
            <span class="meta-label">Edges</span>
            <span class="meta-value">{{ node.edgeCount }}</span>
          </div>
        </div>

        <!-- MEBN Metadata -->
        <div class="mebn-meta-section" *ngIf="mebnMfragName">
          <span class="meta-label">MEBN Structure</span>
          <div class="mebn-meta-badges">
            <span class="popover-mebn-badge popover-mfrag-badge" matTooltip="MFrag membership">
              {{ mebnMfragName }}
            </span>
            <span class="popover-mebn-badge popover-role-badge"
                  [class.popover-role-resident]="mebnNodeRole === 'RESIDENT'"
                  [class.popover-role-input]="mebnNodeRole === 'INPUT'">
              {{ mebnNodeRole }}
            </span>
            <span *ngIf="mebnEntityType" class="popover-mebn-badge popover-entity-badge"
                  [matTooltip]="'Entity ID: ' + (mebnEntityId || 'N/A')">
              {{ mebnEntityType }}
            </span>
          </div>
        </div>

        <!-- Bayesian Inference -->
        <div class="bayesian-section" *ngIf="posteriorValue != null">
          <span class="meta-label">Bayesian Inference</span>
          <div class="bayesian-bar-row">
            <div class="bayesian-values">
              <span class="prior-val" *ngIf="priorValue != null"
                    matTooltip="Prior probability">{{ (priorValue * 100).toFixed(1) }}%</span>
              <mat-icon class="shift-icon" *ngIf="priorValue != null">arrow_forward</mat-icon>
              <span class="posterior-val"
                    [style.color]="getPosteriorColor(posteriorValue)"
                    matTooltip="Posterior probability">{{ (posteriorValue * 100).toFixed(1) }}%</span>
            </div>
            <div class="bayesian-bar">
              <div class="bayesian-bar-fill"
                   [style.width.%]="posteriorValue * 100"
                   [style.background-color]="getPosteriorColor(posteriorValue)"></div>
              <div *ngIf="priorValue != null"
                   class="bayesian-prior-marker"
                   [style.left.%]="priorValue * 100"
                   matTooltip="Prior: {{ (priorValue * 100).toFixed(1) }}%"></div>
            </div>
          </div>
        </div>

        <!-- Content Preview -->
        <div class="content-preview" *ngIf="node.contentPreview">
          <span class="meta-label">Preview</span>
          <pre class="preview-text">{{ node.contentPreview | slice:0:300 }}{{ (node.contentPreview?.length || 0) > 300 ? '...' : '' }}</pre>
        </div>

        <!-- Action -->
        <div class="popover-actions">
          <button mat-stroked-button color="primary" (click)="openInGraph.emit(node.nodeId)">
            <mat-icon>hub</mat-icon>
            Open in Knowledge Graph
          </button>
        </div>
      </div>
    </div>
  `,
  styles: [`
    :host { position: relative; display: inline-block; }

    .popover-card {
      position: absolute; bottom: calc(100% + 8px); left: 50%; transform: translateX(-50%);
      z-index: 1000; width: 360px; max-height: 420px; overflow-y: auto;
      background: #2a2a2a; border: 1px solid rgba(255,255,255,0.15); border-radius: 8px;
      box-shadow: 0 8px 24px rgba(0,0,0,0.4); padding: 12px;
    }

    .popover-loading { display: flex; align-items: center; gap: 8px; padding: 12px; color: #aaa; }
    .popover-error { display: flex; align-items: center; gap: 8px; color: #ef5350; padding: 8px; }

    .popover-header { display: flex; justify-content: space-between; align-items: center; margin-bottom: 4px; }
    .close-btn { width: 28px !important; height: 28px !important; line-height: 28px !important; }
    .close-btn mat-icon { font-size: 18px; width: 18px; height: 18px; }

    .type-chip { font-size: 10px !important; min-height: 22px !important; padding: 0 8px !important; }
    .type-source { background: rgba(144,202,249,0.2) !important; color: #90caf9 !important; }
    .type-document { background: rgba(129,199,132,0.2) !important; color: #81c784 !important; }
    .type-snippet { background: rgba(255,183,77,0.2) !important; color: #ffb74d !important; }
    .type-entity { background: rgba(206,147,216,0.2) !important; color: #ce93d8 !important; }
    .type-custom { background: rgba(255,255,255,0.1) !important; color: #ccc !important; }

    .node-title { margin: 4px 0 6px; font-size: 14px; font-weight: 600; color: #eee; }
    .node-desc { margin: 0 0 8px; font-size: 12px; color: #aaa; line-height: 1.4; }

    .meta-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 6px 12px; margin-bottom: 8px; }
    .meta-item { display: flex; flex-direction: column; }
    .meta-label { font-size: 10px; color: #888; text-transform: uppercase; letter-spacing: 0.5px; }
    .meta-value { font-size: 12px; color: #ccc; }
    .path-value { word-break: break-all; font-family: monospace; font-size: 11px; }

    .content-preview { margin-bottom: 8px; }
    .preview-text {
      margin: 4px 0 0; padding: 8px; background: rgba(0,0,0,0.3); border-radius: 4px;
      font-size: 11px; color: #bbb; white-space: pre-wrap; word-break: break-word;
      max-height: 120px; overflow-y: auto;
    }

    .bayesian-section { margin-bottom: 8px; }
    .bayesian-bar-row { margin-top: 4px; }
    .bayesian-values { display: flex; align-items: center; gap: 4px; margin-bottom: 4px; }
    .prior-val { font-size: 11px; color: #888; }
    .shift-icon { font-size: 12px; width: 12px; height: 12px; color: #666; }
    .posterior-val { font-size: 13px; font-weight: 600; }
    .bayesian-bar { position: relative; height: 6px; background: rgba(255,255,255,0.08); border-radius: 3px; overflow: visible; }
    .bayesian-bar-fill { height: 100%; border-radius: 3px; transition: width 0.3s; }
    .bayesian-prior-marker {
      position: absolute; top: -2px; width: 2px; height: 10px;
      background: #fff; opacity: 0.6; border-radius: 1px;
      pointer-events: auto; cursor: default;
    }

    .mebn-meta-section { margin-bottom: 8px; }
    .mebn-meta-badges { display: flex; flex-wrap: wrap; gap: 4px; margin-top: 4px; }
    .popover-mebn-badge {
      display: inline-flex; align-items: center; padding: 1px 6px;
      border-radius: 3px; font-size: 10px; font-weight: 500; letter-spacing: 0.3px;
    }
    .popover-mfrag-badge {
      background: rgba(102, 126, 234, 0.15); color: #90a0ff;
      border: 1px solid rgba(102, 126, 234, 0.3);
    }
    .popover-role-badge {
      background: rgba(255, 255, 255, 0.08); color: #aaa;
      border: 1px solid rgba(255, 255, 255, 0.15);
    }
    .popover-role-badge.popover-role-resident {
      background: rgba(34, 197, 94, 0.15); color: #66bb6a;
      border: 1px solid rgba(34, 197, 94, 0.3);
    }
    .popover-role-badge.popover-role-input {
      background: rgba(139, 92, 246, 0.15); color: #b39ddb;
      border: 1px solid rgba(139, 92, 246, 0.3);
    }
    .popover-entity-badge {
      background: rgba(245, 158, 11, 0.15); color: #ffb74d;
      border: 1px solid rgba(245, 158, 11, 0.3);
    }

    .popover-actions { display: flex; justify-content: flex-end; }
    .popover-actions button { font-size: 12px; }
    .popover-actions mat-icon { font-size: 16px; width: 16px; height: 16px; margin-right: 4px; }
  `]
})
export class GraphNodePopoverComponent implements OnChanges {
  @Input() nodeId: string | null = null;
  @Input() visible = false;
  @Input() posteriorValue: number | null = null;
  @Input() priorValue: number | null = null;
  @Input() mebnMfragName: string | null = null;
  @Input() mebnNodeRole: string | null = null;
  @Input() mebnEntityType: string | null = null;
  @Input() mebnEntityId: string | null = null;
  @Output() openInGraph = new EventEmitter<string>();
  @Output() closed = new EventEmitter<void>();

  node: GraphNode | null = null;
  loading = false;
  error: string | null = null;

  private loadedNodeId: string | null = null;

  constructor(private graphService: GraphService) {}

  ngOnChanges(changes: SimpleChanges): void {
    if ((changes['nodeId'] || changes['visible']) && this.visible && this.nodeId) {
      if (this.nodeId !== this.loadedNodeId) {
        this.loadNode();
      }
    }
    if (changes['visible'] && !this.visible) {
      this.node = null;
      this.loadedNodeId = null;
      this.error = null;
    }
  }

  private loadNode(): void {
    if (!this.nodeId) return;
    this.loading = true;
    this.error = null;
    this.graphService.getNode(this.nodeId).subscribe({
      next: (node) => {
        this.node = node;
        this.loadedNodeId = this.nodeId;
        this.loading = false;
      },
      error: (err) => {
        this.error = 'Node not found';
        this.loading = false;
      }
    });
  }

  close(): void {
    this.closed.emit();
  }

  getPosteriorColor(value: number): string {
    if (value >= 0.7) return '#ef5350';
    if (value >= 0.4) return '#ffb74d';
    return '#66bb6a';
  }
}
