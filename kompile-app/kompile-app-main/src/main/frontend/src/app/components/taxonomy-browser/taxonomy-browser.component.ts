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

import { Component, Input, OnChanges, OnDestroy, SimpleChanges, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { MatTreeModule, MatTreeNestedDataSource } from '@angular/material/tree';
import { NestedTreeControl } from '@angular/cdk/tree';
import { MatIconModule } from '@angular/material/icon';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatTableModule } from '@angular/material/table';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatChipsModule } from '@angular/material/chips';
import { MatTooltipModule } from '@angular/material/tooltip';
import { MatInputModule } from '@angular/material/input';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';
import { MatBadgeModule } from '@angular/material/badge';
import { MatMenuModule } from '@angular/material/menu';
import { MatDividerModule } from '@angular/material/divider';
import { Subject } from 'rxjs';
import { takeUntil } from 'rxjs/operators';
import { EnrichmentService } from '../../services/enrichment.service';
import { WebSocketService } from '../../services/websocket.service';
import { TaxonomyNode, EnrichmentJob, EnrichmentStatus, EnrichmentProgressUpdate } from '../../models/api-models';
import { GraphNode } from '../../models/graph-models';

interface TreeNode {
  id: string;
  label: string;
  description: string;
  level: string;
  entityTypes: string[];
  children: TreeNode[];
}

interface EnrichmentStep {
  key: string;
  label: string;
  icon: string;
  phase: string;
  run: (factSheetId: number) => void;
}

@Component({
  selector: 'app-taxonomy-browser',
  standalone: true,
  imports: [
    CommonModule, FormsModule,
    MatTreeModule, MatIconModule, MatButtonModule, MatCardModule,
    MatTableModule, MatProgressBarModule, MatProgressSpinnerModule,
    MatChipsModule, MatTooltipModule, MatInputModule, MatFormFieldModule,
    MatSnackBarModule, MatBadgeModule, MatMenuModule, MatDividerModule
  ],
  template: `
    <!-- Enrichment Status Banner -->
    <div class="enrichment-banner" *ngIf="status">
      <mat-icon [class.enriched]="status.enriched">{{ status.enriched ? 'check_circle' : 'info' }}</mat-icon>
      <span *ngIf="status.enriched">
        Enriched | Version {{ status.taxonomyVersion }} | {{ status.categoryCount }} categories
        <span *ngIf="status.lastEnrichmentAt"> | Last: {{ status.lastEnrichmentAt | date:'short' }}</span>
      </span>
      <span *ngIf="!status.enriched">Not yet enriched</span>
      <span class="spacer"></span>

      <!-- Full pipeline button -->
      <button mat-stroked-button (click)="runEnrichment()" [disabled]="isRunning" class="run-btn"
              matTooltip="Run the full enrichment pipeline (all enabled phases)">
        <mat-icon>play_arrow</mat-icon> {{ isRunning ? 'Running...' : 'Run All' }}
      </button>

      <!-- Individual step dropdown -->
      <button mat-stroked-button [matMenuTriggerFor]="stepMenu" [disabled]="isRunning"
              matTooltip="Run individual enrichment steps">
        <mat-icon>tune</mat-icon> Run Step
        <mat-icon>arrow_drop_down</mat-icon>
      </button>
      <mat-menu #stepMenu="matMenu">
        <div class="step-group-label">Clean</div>
        <button mat-menu-item (click)="runStep('dedup')" [disabled]="isRunning">
          <mat-icon>content_copy</mat-icon> Deduplicate Chunks
        </button>
        <button mat-menu-item (click)="runStep('prune')" [disabled]="isRunning">
          <mat-icon>content_cut</mat-icon> Prune Graph
        </button>
        <button mat-menu-item (click)="runStep('validate')" [disabled]="isRunning">
          <mat-icon>verified</mat-icon> Validate Graph
        </button>
        <button mat-menu-item (click)="runStep('normalize')" [disabled]="isRunning">
          <mat-icon>text_format</mat-icon> Normalize Entities
        </button>
        <mat-divider></mat-divider>
        <div class="step-group-label">Organize</div>
        <button mat-menu-item (click)="runStep('discover-taxonomy')" [disabled]="isRunning">
          <mat-icon>account_tree</mat-icon> Discover Taxonomy
        </button>
        <button mat-menu-item (click)="runStep('categorize')" [disabled]="isRunning">
          <mat-icon>label</mat-icon> Categorize Entities
        </button>
        <mat-divider></mat-divider>
        <div class="step-group-label">Generate</div>
        <button mat-menu-item (click)="runStep('generate-processes')" [disabled]="isRunning">
          <mat-icon>schema</mat-icon> Generate Processes
        </button>
      </mat-menu>
    </div>

    <mat-progress-bar *ngIf="isRunning && currentJob" mode="determinate" [value]="currentJob.progressPercent"></mat-progress-bar>
    <div class="step-status" *ngIf="isRunning">
      {{ runningStepLabel }}
    </div>

    <div class="browser-layout">
      <!-- Left: Taxonomy Tree -->
      <div class="tree-panel">
        <h3>Taxonomy</h3>
        <mat-tree [dataSource]="dataSource" [treeControl]="treeControl" class="taxonomy-tree">
          <mat-nested-tree-node *matTreeNodeDef="let node">
            <li>
              <div class="tree-node" [class.selected]="selectedNode === node" (click)="selectNode(node)">
                <button mat-icon-button *ngIf="node.children?.length" (click)="treeControl.toggle(node); $event.stopPropagation()">
                  <mat-icon>{{ treeControl.isExpanded(node) ? 'expand_more' : 'chevron_right' }}</mat-icon>
                </button>
                <span *ngIf="!node.children?.length" class="spacer"></span>
                <mat-icon class="level-icon">{{ getLevelIcon(node.level) }}</mat-icon>
                <span class="node-label">{{ node.label }}</span>
                <span class="entity-count" *ngIf="node.entityTypes?.length">({{ node.entityTypes.length }})</span>
              </div>
              <ul *ngIf="treeControl.isExpanded(node)">
                <ng-container matTreeNodeOutlet></ng-container>
              </ul>
            </li>
          </mat-nested-tree-node>
        </mat-tree>

        <div class="tree-stats" *ngIf="taxonomyNodes.length > 0">
          {{ domainCount }} domains, {{ categoryCount }} categories, {{ entityTypeCount }} types
        </div>
        <div class="empty-state" *ngIf="taxonomyNodes.length === 0 && !loading">
          No taxonomy discovered yet. Run enrichment to auto-discover.
        </div>
      </div>

      <!-- Right: Entity List -->
      <div class="entity-panel">
        <div *ngIf="selectedNode" class="selected-info">
          <h3>{{ selectedNode.label }}</h3>
          <p *ngIf="selectedNode.description">{{ selectedNode.description }}</p>
          <span class="level-badge">{{ selectedNode.level }}</span>
        </div>

        <mat-form-field class="search-field" *ngIf="selectedNode">
          <mat-label>Search entities in {{ selectedNode.label }}</mat-label>
          <input matInput [(ngModel)]="entitySearchQuery" (ngModelChange)="searchEntities()">
          <mat-icon matSuffix>search</mat-icon>
        </mat-form-field>

        <div *ngIf="loadingEntities" class="loading-entities">
          <mat-spinner diameter="32"></mat-spinner>
        </div>

        <table mat-table [dataSource]="filteredEntities" *ngIf="filteredEntities.length > 0 && !loadingEntities" class="entity-table">
          <ng-container matColumnDef="title">
            <th mat-header-cell *matHeaderCellDef>Title</th>
            <td mat-cell *matCellDef="let entity">{{ entity.title }}</td>
          </ng-container>
          <ng-container matColumnDef="type">
            <th mat-header-cell *matHeaderCellDef>Type</th>
            <td mat-cell *matCellDef="let entity">
              <span class="type-chip">{{ entity.metadata?.entity_type || entity.metadata?.entityType || 'N/A' }}</span>
            </td>
          </ng-container>
          <ng-container matColumnDef="confidence">
            <th mat-header-cell *matHeaderCellDef>Confidence</th>
            <td mat-cell *matCellDef="let entity">{{ (entity.confidence || 0) | number:'1.0-2' }}</td>
          </ng-container>
          <ng-container matColumnDef="edges">
            <th mat-header-cell *matHeaderCellDef>Edges</th>
            <td mat-cell *matCellDef="let entity">{{ entity.edgeCount || 0 }}</td>
          </ng-container>
          <tr mat-header-row *matHeaderRowDef="entityColumns"></tr>
          <tr mat-row *matRowDef="let row; columns: entityColumns;"></tr>
        </table>

        <div class="empty-state" *ngIf="selectedNode && filteredEntities.length === 0 && !loadingEntities">
          No entities found in this category.
        </div>
        <div class="empty-state" *ngIf="!selectedNode && !loading">
          Select a taxonomy node to view entities.
        </div>
      </div>
    </div>
  `,
  styles: [`
    :host { display: block; height: 100%; }
    .enrichment-banner {
      display: flex; align-items: center; gap: 8px; padding: 8px 16px;
      background: var(--mat-sys-surface-container); border-radius: 8px; margin-bottom: 12px;
    }
    .enrichment-banner .enriched { color: #4caf50; }
    .spacer { flex: 1; }
    .run-btn { }
    .browser-layout { display: flex; gap: 16px; height: calc(100% - 80px); }
    .tree-panel { width: 320px; min-width: 280px; overflow-y: auto; }
    .entity-panel { flex: 1; overflow-y: auto; }
    .tree-node {
      display: flex; align-items: center; gap: 4px; padding: 4px 8px;
      cursor: pointer; border-radius: 4px;
    }
    .tree-node:hover { background: var(--mat-sys-surface-container-high); }
    .tree-node.selected { background: var(--mat-sys-primary-container); }
    .spacer { width: 40px; }
    .level-icon { font-size: 18px; width: 18px; height: 18px; }
    .node-label { flex: 1; }
    .entity-count { color: var(--mat-sys-on-surface-variant); font-size: 12px; }
    .tree-stats { padding: 8px; font-size: 12px; color: var(--mat-sys-on-surface-variant); }
    .selected-info { margin-bottom: 12px; }
    .level-badge {
      display: inline-block; padding: 2px 8px; border-radius: 12px; font-size: 11px;
      background: var(--mat-sys-secondary-container); color: var(--mat-sys-on-secondary-container);
    }
    .search-field { width: 100%; }
    .loading-entities { display: flex; justify-content: center; padding: 24px; }
    .entity-table { width: 100%; }
    .type-chip {
      display: inline-block; padding: 2px 8px; border-radius: 12px; font-size: 11px;
      background: var(--mat-sys-tertiary-container); color: var(--mat-sys-on-tertiary-container);
    }
    .empty-state { text-align: center; padding: 24px; color: var(--mat-sys-on-surface-variant); }
    ul { list-style: none; padding-left: 16px; margin: 0; }
    li { list-style: none; }
    .taxonomy-tree { background: transparent; }
    .step-group-label {
      padding: 8px 16px 4px; font-size: 11px; font-weight: 500; text-transform: uppercase;
      color: var(--mat-sys-on-surface-variant); letter-spacing: 0.5px;
    }
    .step-status { padding: 4px 16px; font-size: 13px; color: var(--mat-sys-on-surface-variant); }
  `]
})
export class TaxonomyBrowserComponent implements OnInit, OnChanges, OnDestroy {
  private destroy$ = new Subject<void>();
  @Input() factSheetId: number | null = null;

  treeControl = new NestedTreeControl<TreeNode>(node => node.children);
  dataSource = new MatTreeNestedDataSource<TreeNode>();

  taxonomyNodes: TaxonomyNode[] = [];
  selectedNode: TreeNode | null = null;
  entities: GraphNode[] = [];
  filteredEntities: GraphNode[] = [];
  entitySearchQuery = '';
  entityColumns = ['title', 'type', 'confidence', 'edges'];

  status: EnrichmentStatus | null = null;
  currentJob: EnrichmentJob | null = null;
  isRunning = false;
  runningStepLabel = '';
  loading = false;
  loadingEntities = false;

  domainCount = 0;
  categoryCount = 0;
  entityTypeCount = 0;

  private pollTimer: any;

  private readonly stepLabels: { [key: string]: string } = {
    'dedup': 'Deduplicating chunks...',
    'prune': 'Pruning graph...',
    'validate': 'Validating graph...',
    'normalize': 'Normalizing entities...',
    'discover-taxonomy': 'Discovering taxonomy...',
    'categorize': 'Categorizing entities...',
    'generate-processes': 'Generating processes...'
  };

  constructor(
    private enrichmentService: EnrichmentService,
    private webSocketService: WebSocketService,
    private snackBar: MatSnackBar
  ) {}

  ngOnInit(): void {
    this.webSocketService.subscribeToEnrichmentProgress().pipe(
      takeUntil(this.destroy$)
    ).subscribe((event: EnrichmentProgressUpdate) => {
      if (!this.factSheetId || event.factSheetId !== this.factSheetId) return;

      // Update progress in real-time
      if (this.currentJob) {
        this.currentJob.progressPercent = event.progressPercent;
        this.currentJob.currentPhase = event.phase || this.currentJob.currentPhase;
      }

      if (event.eventType === 'COMPLETED') {
        this.isRunning = false;
        this.runningStepLabel = '';
        this.stopPolling();
        this.loadTaxonomy();
        this.loadStatus();
        this.snackBar.open('Enrichment completed', 'OK', { duration: 3000 });
      } else if (event.eventType === 'FAILED') {
        this.isRunning = false;
        this.runningStepLabel = '';
        this.stopPolling();
        this.snackBar.open('Enrichment failed: ' + (event.message || ''), 'OK', { duration: 5000 });
      } else if (event.eventType === 'PHASE_COMPLETED') {
        this.loadTaxonomy();
        this.loadStatus();
      }
    });
  }

  ngOnChanges(changes: SimpleChanges): void {
    if (changes['factSheetId'] && this.factSheetId) {
      this.loadTaxonomy();
      this.loadStatus();
    }
  }

  loadTaxonomy(): void {
    if (!this.factSheetId) return;
    this.loading = true;
    this.enrichmentService.getTaxonomy(this.factSheetId).subscribe({
      next: nodes => {
        this.taxonomyNodes = nodes;
        this.dataSource.data = this.buildTree(nodes);
        this.domainCount = nodes.filter(n => n.level === 'DOMAIN').length;
        this.categoryCount = nodes.filter(n => n.level === 'CATEGORY').length;
        this.entityTypeCount = nodes.filter(n => n.level === 'ENTITY_TYPE').length;
        this.loading = false;
      },
      error: () => { this.loading = false; }
    });
  }

  loadStatus(): void {
    if (!this.factSheetId) return;
    this.enrichmentService.getEnrichmentStatus(this.factSheetId).subscribe({
      next: status => this.status = status,
      error: () => {}
    });
  }

  buildTree(nodes: TaxonomyNode[]): TreeNode[] {
    const nodeMap = new Map<string, TreeNode>();
    const roots: TreeNode[] = [];
    for (const n of nodes) {
      nodeMap.set(n.id, { id: n.id, label: n.label, description: n.description || '', level: n.level || '', entityTypes: n.entityTypes || [], children: [] });
    }
    for (const n of nodes) {
      const treeNode = nodeMap.get(n.id)!;
      if (n.parentId && nodeMap.has(n.parentId)) {
        nodeMap.get(n.parentId)!.children.push(treeNode);
      } else {
        roots.push(treeNode);
      }
    }
    return roots;
  }

  selectNode(node: TreeNode): void {
    this.selectedNode = node;
    this.loadEntitiesForNode(node);
  }

  loadEntitiesForNode(node: TreeNode): void {
    if (!this.factSheetId) return;
    this.loadingEntities = true;
    this.enrichmentService.searchByCategory(this.factSheetId, undefined, node.label, 200).subscribe({
      next: entities => {
        this.entities = entities;
        this.filteredEntities = entities;
        this.loadingEntities = false;
      },
      error: () => { this.loadingEntities = false; }
    });
  }

  searchEntities(): void {
    if (!this.entitySearchQuery) {
      this.filteredEntities = this.entities;
      return;
    }
    const q = this.entitySearchQuery.toLowerCase();
    this.filteredEntities = this.entities.filter(e =>
      (e.title && e.title.toLowerCase().includes(q)) ||
      (e.description && e.description.toLowerCase().includes(q))
    );
  }

  /** Run the full enrichment pipeline */
  runEnrichment(): void {
    if (!this.factSheetId || this.isRunning) return;
    this.isRunning = true;
    this.runningStepLabel = 'Running full enrichment pipeline...';
    this.enrichmentService.startEnrichment(this.factSheetId).subscribe({
      next: job => {
        this.currentJob = job;
        this.snackBar.open('Enrichment started', 'OK', { duration: 3000 });
        this.startPolling(job.jobId || job.id);
      },
      error: err => {
        this.isRunning = false;
        this.runningStepLabel = '';
        this.snackBar.open('Failed to start enrichment: ' + (err.error?.message || err.message), 'OK', { duration: 5000 });
      }
    });
  }

  /** Run an individual enrichment step */
  runStep(stepKey: string): void {
    if (!this.factSheetId || this.isRunning) return;
    this.isRunning = true;
    this.runningStepLabel = this.stepLabels[stepKey] || 'Running step...';

    const stepMethods: { [key: string]: (id: number) => any } = {
      'dedup': (id) => this.enrichmentService.runDedup(id),
      'prune': (id) => this.enrichmentService.runPrune(id),
      'validate': (id) => this.enrichmentService.runValidate(id),
      'normalize': (id) => this.enrichmentService.runNormalize(id),
      'discover-taxonomy': (id) => this.enrichmentService.runDiscoverTaxonomy(id),
      'categorize': (id) => this.enrichmentService.runCategorize(id),
      'generate-processes': (id) => this.enrichmentService.runGenerateProcesses(id)
    };

    const method = stepMethods[stepKey];
    if (!method) return;

    method(this.factSheetId).subscribe({
      next: (job: EnrichmentJob) => {
        this.currentJob = job;
        this.startPolling(job.jobId || job.id);
      },
      error: (err: any) => {
        this.isRunning = false;
        this.runningStepLabel = '';
        this.snackBar.open('Step failed: ' + (err.error?.message || err.message), 'OK', { duration: 5000 });
      }
    });
  }

  private startPolling(jobId: string): void {
    this.stopPolling();
    this.pollTimer = setInterval(() => {
      this.enrichmentService.getJob(jobId).subscribe({
        next: job => {
          this.currentJob = job;
          if (job.statusValue === 'COMPLETED' || job.statusValue === 'FAILED' || job.statusValue === 'CANCELLED') {
            this.isRunning = false;
            this.runningStepLabel = '';
            this.stopPolling();
            if (job.statusValue === 'COMPLETED') {
              this.loadTaxonomy();
              this.loadStatus();
              this.snackBar.open('Completed', 'OK', { duration: 3000 });
            } else {
              this.snackBar.open(job.statusValue.toLowerCase() + ': ' + (job.errorMessage || ''), 'OK', { duration: 5000 });
            }
          }
        },
        error: () => { this.isRunning = false; this.runningStepLabel = ''; this.stopPolling(); }
      });
    }, 2000);
  }

  private stopPolling(): void {
    if (this.pollTimer) { clearInterval(this.pollTimer); this.pollTimer = null; }
  }

  getLevelIcon(level: string): string {
    switch (level) {
      case 'DOMAIN': return 'domain';
      case 'CATEGORY': return 'category';
      case 'ENTITY_TYPE': return 'label';
      default: return 'circle';
    }
  }

  ngOnDestroy(): void {
    this.stopPolling();
    this.destroy$.next();
    this.destroy$.complete();
  }
}
