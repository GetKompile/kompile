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

import { Component, OnInit, OnDestroy, Input } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { MatTabsModule } from '@angular/material/tabs';
import { MatIconModule } from '@angular/material/icon';
import { MatButtonModule } from '@angular/material/button';
import { MatTooltipModule } from '@angular/material/tooltip';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';
import { Subject, takeUntil } from 'rxjs';

import { GraphVisualizerComponent } from '../graph-visualizer/graph-visualizer.component';
import { EntityBrowserComponent } from '../entity-browser/entity-browser.component';
import { KnowledgeGraphBuilderComponent } from '../knowledge-graph-builder/knowledge-graph-builder.component';
import { FactSheetService } from '../../services/fact-sheet.service';
import { GraphNode } from '../../models/graph-models';
import { FactSheet } from '../../models/api-models';

type KnowledgeGraphTab = 'visualizer' | 'browser' | 'builder';

@Component({
  selector: 'app-knowledge-graph-hub',
  standalone: true,
  imports: [
    CommonModule,
    FormsModule,
    MatTabsModule,
    MatIconModule,
    MatButtonModule,
    MatTooltipModule,
    MatSnackBarModule,
    GraphVisualizerComponent,
    EntityBrowserComponent,
    KnowledgeGraphBuilderComponent
  ],
  template: `
    <div class="knowledge-graph-hub">
      <!-- Hub Header -->
      <div class="hub-header">
        <div class="header-left">
          <mat-icon class="hub-icon">hub</mat-icon>
          <div class="header-text">
            <h1>Knowledge Graph</h1>
            <span class="fact-sheet-info" *ngIf="activeFactSheet">
              Working with: <strong>{{ activeFactSheet.name }}</strong>
            </span>
          </div>
        </div>
        <div class="header-actions">
          <button mat-stroked-button
                  [class.active]="activeTab === 'visualizer'"
                  (click)="setActiveTab('visualizer')"
                  matTooltip="Visual Graph Explorer">
            <mat-icon>scatter_plot</mat-icon>
            Visualizer
          </button>
          <button mat-stroked-button
                  [class.active]="activeTab === 'browser'"
                  (click)="setActiveTab('browser')"
                  matTooltip="Browse Entities & Connections">
            <mat-icon>list_alt</mat-icon>
            Entity Browser
          </button>
          <button mat-stroked-button
                  [class.active]="activeTab === 'builder'"
                  (click)="setActiveTab('builder')"
                  matTooltip="Build Graph from Documents">
            <mat-icon>construction</mat-icon>
            Builder
          </button>
        </div>
      </div>

      <!-- Tab Content -->
      <div class="hub-content">
        <!-- Graph Visualizer Tab -->
        <div class="tab-panel" *ngIf="activeTab === 'visualizer'">
          <app-graph-visualizer
            [factSheetId]="activeFactSheet?.id || null"
            [factSheetName]="activeFactSheet?.name || ''">
          </app-graph-visualizer>
        </div>

        <!-- Entity Browser Tab -->
        <div class="tab-panel" *ngIf="activeTab === 'browser'">
          <app-entity-browser
            [factSheetId]="activeFactSheet?.id || null"
            (entitySelected)="onEntitySelected($event)"
            (navigateToGraph)="onNavigateToGraph($event)">
          </app-entity-browser>
        </div>

        <!-- Knowledge Graph Builder Tab -->
        <div class="tab-panel" *ngIf="activeTab === 'builder'">
          <app-knowledge-graph-builder></app-knowledge-graph-builder>
        </div>
      </div>
    </div>
  `,
  styles: [`
    .knowledge-graph-hub {
      display: flex;
      flex-direction: column;
      height: 100%;
      background: var(--bg-body, #f8f9fa);
    }

    .hub-header {
      display: flex;
      justify-content: space-between;
      align-items: center;
      padding: 16px 24px;
      background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
      color: #ffffff;
      box-shadow: 0 2px 8px rgba(0, 0, 0, 0.15);
    }

    .header-left {
      display: flex;
      align-items: center;
      gap: 16px;
    }

    .hub-icon {
      font-size: 32px;
      width: 32px;
      height: 32px;
      opacity: 0.9;
    }

    .header-text h1 {
      margin: 0;
      font-size: 20px;
      font-weight: 600;
    }

    .fact-sheet-info {
      font-size: 13px;
      opacity: 0.85;
    }

    .fact-sheet-info strong {
      font-weight: 600;
    }

    .header-actions {
      display: flex;
      gap: 8px;
    }

    .header-actions button {
      color: rgba(255, 255, 255, 0.9);
      border-color: rgba(255, 255, 255, 0.3);
      background: rgba(255, 255, 255, 0.1);
      transition: all 0.2s ease;
    }

    .header-actions button:hover {
      background: rgba(255, 255, 255, 0.2);
      border-color: rgba(255, 255, 255, 0.5);
    }

    .header-actions button.active {
      background: rgba(255, 255, 255, 0.25);
      border-color: rgba(255, 255, 255, 0.6);
      font-weight: 500;
    }

    .header-actions button mat-icon {
      margin-right: 6px;
    }

    .hub-content {
      flex: 1;
      overflow: hidden;
    }

    .tab-panel {
      height: 100%;
      overflow: auto;
    }

    /* Responsive adjustments */
    @media (max-width: 768px) {
      .hub-header {
        flex-direction: column;
        gap: 16px;
        padding: 16px;
      }

      .header-actions {
        width: 100%;
        justify-content: center;
        flex-wrap: wrap;
      }

      .header-actions button {
        flex: 1;
        min-width: 100px;
      }
    }
  `]
})
export class KnowledgeGraphHubComponent implements OnInit, OnDestroy {
  private destroy$ = new Subject<void>();

  activeTab: KnowledgeGraphTab = 'visualizer';
  activeFactSheet: FactSheet | null = null;
  focusedNodeId: string | null = null;

  constructor(
    private factSheetService: FactSheetService,
    private snackBar: MatSnackBar
  ) {}

  ngOnInit(): void {
    // Subscribe to active fact sheet
    this.factSheetService.activeSheet$
      .pipe(takeUntil(this.destroy$))
      .subscribe(sheet => {
        this.activeFactSheet = sheet;
      });
  }

  ngOnDestroy(): void {
    this.destroy$.next();
    this.destroy$.complete();
  }

  setActiveTab(tab: KnowledgeGraphTab): void {
    this.activeTab = tab;
  }

  onEntitySelected(entity: GraphNode): void {
    // Could show entity details or perform other actions
    console.log('Entity selected:', entity);
  }

  onNavigateToGraph(entity: GraphNode): void {
    // Switch to visualizer and focus on the entity
    this.focusedNodeId = entity.nodeId;
    this.activeTab = 'visualizer';
    this.snackBar.open(`Showing "${entity.title || entity.nodeId}" in graph`, 'Dismiss', { duration: 2000 });
  }
}
