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

import { Component, OnInit, OnDestroy } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { MatIconModule } from '@angular/material/icon';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatChipsModule } from '@angular/material/chips';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatExpansionModule } from '@angular/material/expansion';
import { MatTooltipModule } from '@angular/material/tooltip';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';
import { MatTableModule } from '@angular/material/table';
import { MatBadgeModule } from '@angular/material/badge';
import { Subject, takeUntil } from 'rxjs';

import {
  MultiAgentGraphService,
  AgentInfo,
  MergeStrategyInfo,
  ExtractionRequest,
  ExtractionResponse,
  ExtractAndPersistResponse,
  AgentContribution
} from '../../services/multi-agent-graph.service';

@Component({
  selector: 'app-multi-agent-graph-extraction',
  standalone: true,
  imports: [
    CommonModule,
    FormsModule,
    MatIconModule,
    MatButtonModule,
    MatFormFieldModule,
    MatInputModule,
    MatSelectModule,
    MatCheckboxModule,
    MatChipsModule,
    MatProgressBarModule,
    MatExpansionModule,
    MatTooltipModule,
    MatSnackBarModule,
    MatTableModule,
    MatBadgeModule
  ],
  template: `
    <div class="multi-agent-extraction">
      <!-- Header -->
      <div class="section-header">
        <mat-icon>groups</mat-icon>
        <div>
          <h2>Multi-Agent Graph Extraction</h2>
          <p class="subtitle">Run multiple extraction agents on document text and merge results into a unified graph</p>
        </div>
      </div>

      <!-- Agent Selection -->
      <div class="config-section">
        <h3>Extraction Agents</h3>
        <div class="agents-grid" *ngIf="agents.length > 0; else noAgents">
          <div *ngFor="let agent of agents"
               class="agent-card"
               [class.selected]="selectedAgentIds.has(agent.id)"
               (click)="toggleAgent(agent.id)">
            <mat-icon class="agent-icon">
              {{ getAgentIcon(agent.id) }}
            </mat-icon>
            <div class="agent-info">
              <span class="agent-id">{{ agent.id }}</span>
              <span class="agent-desc">{{ agent.description }}</span>
              <span class="agent-types" *ngIf="agent.supportedContentTypes?.length">
                {{ agent.supportedContentTypes.join(', ') }}
              </span>
              <span class="agent-types" *ngIf="!agent.supportedContentTypes?.length">
                any content type
              </span>
            </div>
            <mat-icon class="check-icon" *ngIf="selectedAgentIds.has(agent.id)">check_circle</mat-icon>
          </div>
        </div>
        <ng-template #noAgents>
          <div class="empty-state">
            <mat-icon>info</mat-icon>
            <span>No extraction agents available. Make sure the server is running.</span>
            <button mat-stroked-button (click)="loadAgents()">
              <mat-icon>refresh</mat-icon> Retry
            </button>
          </div>
        </ng-template>
      </div>

      <!-- Input Configuration -->
      <div class="config-section">
        <h3>Input Text</h3>
        <mat-form-field appearance="outline" class="full-width">
          <mat-label>Text to extract from</mat-label>
          <textarea matInput
                    [(ngModel)]="inputText"
                    rows="6"
                    placeholder="Paste document text here. Entities and relationships will be extracted by all selected agents."></textarea>
          <mat-hint>Enter text content for entity and relationship extraction</mat-hint>
        </mat-form-field>
      </div>

      <!-- Extraction Settings -->
      <div class="config-section">
        <h3>Settings</h3>
        <div class="settings-row">
          <mat-form-field appearance="outline">
            <mat-label>Merge Strategy</mat-label>
            <mat-select [(ngModel)]="selectedStrategy">
              <mat-option *ngFor="let s of strategies" [value]="s.name"
                          [matTooltip]="s.description">
                {{ s.name }}
              </mat-option>
            </mat-select>
          </mat-form-field>

          <mat-form-field appearance="outline">
            <mat-label>Min Confidence</mat-label>
            <input matInput type="number"
                   [(ngModel)]="minConfidence"
                   min="0" max="1" step="0.05">
          </mat-form-field>

          <mat-form-field appearance="outline">
            <mat-label>Entity Types</mat-label>
            <input matInput
                   [(ngModel)]="entityTypesStr"
                   placeholder="PERSON,ORGANIZATION,LOCATION,CONCEPT,EVENT">
            <mat-hint>Comma-separated entity types</mat-hint>
          </mat-form-field>
        </div>

        <div class="settings-row">
          <mat-checkbox [(ngModel)]="persistResults">
            Persist results to Knowledge Graph
          </mat-checkbox>
        </div>
      </div>

      <!-- Run Button -->
      <div class="action-bar">
        <button mat-raised-button color="primary"
                [disabled]="isExtracting || !inputText.trim() || selectedAgentIds.size === 0"
                (click)="runExtraction()">
          <mat-icon>play_arrow</mat-icon>
          {{ isExtracting ? 'Extracting...' : 'Run Extraction' }}
        </button>
        <button mat-stroked-button
                *ngIf="lastResult"
                (click)="clearResults()">
          <mat-icon>clear</mat-icon> Clear Results
        </button>
      </div>

      <mat-progress-bar *ngIf="isExtracting" mode="indeterminate" class="extraction-progress"></mat-progress-bar>

      <!-- Results -->
      <div class="results-section" *ngIf="lastResult">
        <!-- Summary Cards -->
        <div class="summary-cards">
          <div class="summary-card">
            <mat-icon>category</mat-icon>
            <div class="card-value">{{ lastResult.totalEntities }}</div>
            <div class="card-label">Entities</div>
          </div>
          <div class="summary-card">
            <mat-icon>share</mat-icon>
            <div class="card-value">{{ lastResult.totalRelations }}</div>
            <div class="card-label">Relations</div>
          </div>
          <div class="summary-card">
            <mat-icon>timer</mat-icon>
            <div class="card-value">{{ lastResult.totalTimeMs }}ms</div>
            <div class="card-label">Time</div>
          </div>
          <div class="summary-card">
            <mat-icon>merge_type</mat-icon>
            <div class="card-value">{{ lastResult.strategy }}</div>
            <div class="card-label">Strategy</div>
          </div>
        </div>

        <!-- Persistence Summary -->
        <div class="persist-summary" *ngIf="persistSummary">
          <mat-icon>save</mat-icon>
          <span>
            Persisted: {{ persistSummary.entitiesCreated }} entities,
            {{ persistSummary.edgesCreated }} edges
            <span *ngIf="persistSummary.entitiesSkipped > 0 || persistSummary.edgesSkipped > 0">
              (skipped: {{ persistSummary.entitiesSkipped }} entities, {{ persistSummary.edgesSkipped }} edges)
            </span>
          </span>
        </div>

        <!-- Per-Agent Contributions -->
        <mat-expansion-panel class="contributions-panel">
          <mat-expansion-panel-header>
            <mat-panel-title>
              <mat-icon>assessment</mat-icon>
              Agent Contributions ({{ getContributionKeys().length }} agents)
            </mat-panel-title>
          </mat-expansion-panel-header>
          <div class="contributions-grid">
            <div *ngFor="let agentId of getContributionKeys()"
                 class="contribution-card">
              <div class="contrib-header">
                <mat-icon>{{ getAgentIcon(agentId) }}</mat-icon>
                <strong>{{ agentId }}</strong>
              </div>
              <div class="contrib-stats">
                <div class="stat">
                  <span class="stat-label">Extracted:</span>
                  <span>{{ lastResult.contributions[agentId].entitiesExtracted }} entities,
                    {{ lastResult.contributions[agentId].relationsExtracted }} relations</span>
                </div>
                <div class="stat">
                  <span class="stat-label">Retained:</span>
                  <span>{{ lastResult.contributions[agentId].entitiesRetained }} entities,
                    {{ lastResult.contributions[agentId].relationsRetained }} relations</span>
                </div>
                <div class="stat">
                  <span class="stat-label">Time:</span>
                  <span>{{ lastResult.contributions[agentId].extractionTimeMs }}ms</span>
                </div>
                <div class="stat" *ngIf="lastResult.contributions[agentId].entityTypes?.length">
                  <span class="stat-label">Types:</span>
                  <span class="type-chips">
                    <span class="type-chip" *ngFor="let t of lastResult.contributions[agentId].entityTypes">{{ t }}</span>
                  </span>
                </div>
              </div>
            </div>
          </div>
        </mat-expansion-panel>

        <!-- Entities Table -->
        <mat-expansion-panel class="entities-panel" [expanded]="true">
          <mat-expansion-panel-header>
            <mat-panel-title>
              <mat-icon>category</mat-icon>
              Entities ({{ lastResult.entities.length || 0 }})
            </mat-panel-title>
          </mat-expansion-panel-header>
          <table mat-table [dataSource]="lastResult.entities || []" class="results-table">
            <ng-container matColumnDef="type">
              <th mat-header-cell *matHeaderCellDef>Type</th>
              <td mat-cell *matCellDef="let e">
                <span class="entity-type-badge" [attr.data-type]="e.type">{{ e.type }}</span>
              </td>
            </ng-container>
            <ng-container matColumnDef="title">
              <th mat-header-cell *matHeaderCellDef>Title</th>
              <td mat-cell *matCellDef="let e">{{ e.title }}</td>
            </ng-container>
            <ng-container matColumnDef="description">
              <th mat-header-cell *matHeaderCellDef>Description</th>
              <td mat-cell *matCellDef="let e">{{ e.description }}</td>
            </ng-container>
            <ng-container matColumnDef="confidence">
              <th mat-header-cell *matHeaderCellDef>Confidence</th>
              <td mat-cell *matCellDef="let e">
                <span class="confidence" [class.high]="e.confidence >= 0.8"
                      [class.medium]="e.confidence >= 0.5 && e.confidence < 0.8"
                      [class.low]="e.confidence < 0.5">
                  {{ (e.confidence * 100).toFixed(0) }}%
                </span>
              </td>
            </ng-container>
            <tr mat-header-row *matHeaderRowDef="entityColumns"></tr>
            <tr mat-row *matRowDef="let row; columns: entityColumns;"></tr>
          </table>
        </mat-expansion-panel>

        <!-- Relations Table -->
        <mat-expansion-panel class="relations-panel">
          <mat-expansion-panel-header>
            <mat-panel-title>
              <mat-icon>share</mat-icon>
              Relations ({{ lastResult.relations.length || 0 }})
            </mat-panel-title>
          </mat-expansion-panel-header>
          <table mat-table [dataSource]="lastResult.relations || []" class="results-table">
            <ng-container matColumnDef="source">
              <th mat-header-cell *matHeaderCellDef>Source</th>
              <td mat-cell *matCellDef="let r">{{ r.source }}</td>
            </ng-container>
            <ng-container matColumnDef="type">
              <th mat-header-cell *matHeaderCellDef>Type</th>
              <td mat-cell *matCellDef="let r">
                <span class="relation-type-badge">{{ r.type }}</span>
              </td>
            </ng-container>
            <ng-container matColumnDef="target">
              <th mat-header-cell *matHeaderCellDef>Target</th>
              <td mat-cell *matCellDef="let r">{{ r.target }}</td>
            </ng-container>
            <ng-container matColumnDef="confidence">
              <th mat-header-cell *matHeaderCellDef>Confidence</th>
              <td mat-cell *matCellDef="let r">
                <span class="confidence" *ngIf="r.confidence != null"
                      [class.high]="r.confidence >= 0.8"
                      [class.medium]="r.confidence >= 0.5 && r.confidence < 0.8"
                      [class.low]="r.confidence < 0.5">
                  {{ (r.confidence * 100).toFixed(0) }}%
                </span>
              </td>
            </ng-container>
            <tr mat-header-row *matHeaderRowDef="relationColumns"></tr>
            <tr mat-row *matRowDef="let row; columns: relationColumns;"></tr>
          </table>
        </mat-expansion-panel>
      </div>

      <!-- Error display -->
      <div class="error-banner" *ngIf="errorMessage">
        <mat-icon>error</mat-icon>
        <span>{{ errorMessage }}</span>
        <button mat-icon-button (click)="errorMessage = null">
          <mat-icon>close</mat-icon>
        </button>
      </div>
    </div>
  `,
  styles: [`
    .multi-agent-extraction {
      padding: 24px;
      max-width: 1200px;
    }

    .section-header {
      display: flex;
      align-items: center;
      gap: 16px;
      margin-bottom: 24px;
    }

    .section-header mat-icon {
      font-size: 32px;
      width: 32px;
      height: 32px;
      color: #667eea;
    }

    .section-header h2 {
      margin: 0;
      font-size: 20px;
      font-weight: 600;
    }

    .subtitle {
      margin: 4px 0 0;
      color: #666;
      font-size: 13px;
    }

    .config-section {
      margin-bottom: 24px;
    }

    .config-section h3 {
      font-size: 15px;
      font-weight: 600;
      margin: 0 0 12px;
      color: #333;
    }

    .agents-grid {
      display: grid;
      grid-template-columns: repeat(auto-fill, minmax(280px, 1fr));
      gap: 12px;
    }

    .agent-card {
      display: flex;
      align-items: flex-start;
      gap: 12px;
      padding: 14px;
      border: 2px solid #e0e0e0;
      border-radius: 8px;
      cursor: pointer;
      transition: all 0.2s ease;
      position: relative;
    }

    .agent-card:hover {
      border-color: #667eea;
      background: rgba(102, 126, 234, 0.04);
    }

    .agent-card.selected {
      border-color: #667eea;
      background: rgba(102, 126, 234, 0.08);
    }

    .agent-icon {
      color: #667eea;
      margin-top: 2px;
    }

    .agent-info {
      display: flex;
      flex-direction: column;
      gap: 2px;
      flex: 1;
    }

    .agent-id {
      font-weight: 600;
      font-size: 14px;
      font-family: monospace;
    }

    .agent-desc {
      font-size: 12px;
      color: #666;
    }

    .agent-types {
      font-size: 11px;
      color: #999;
      font-style: italic;
    }

    .check-icon {
      color: #667eea;
      position: absolute;
      top: 8px;
      right: 8px;
      font-size: 20px;
      width: 20px;
      height: 20px;
    }

    .empty-state {
      display: flex;
      align-items: center;
      gap: 12px;
      padding: 16px;
      background: #f5f5f5;
      border-radius: 8px;
      color: #666;
    }

    .full-width {
      width: 100%;
    }

    .settings-row {
      display: flex;
      gap: 16px;
      align-items: flex-start;
      flex-wrap: wrap;
    }

    .settings-row mat-form-field {
      flex: 1;
      min-width: 200px;
    }

    .action-bar {
      display: flex;
      gap: 12px;
      margin-bottom: 16px;
    }

    .extraction-progress {
      margin-bottom: 16px;
    }

    .summary-cards {
      display: grid;
      grid-template-columns: repeat(auto-fit, minmax(140px, 1fr));
      gap: 12px;
      margin-bottom: 16px;
    }

    .summary-card {
      display: flex;
      flex-direction: column;
      align-items: center;
      padding: 16px;
      background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
      border-radius: 8px;
      color: white;
    }

    .summary-card mat-icon {
      margin-bottom: 4px;
      opacity: 0.9;
    }

    .card-value {
      font-size: 22px;
      font-weight: 700;
    }

    .card-label {
      font-size: 12px;
      opacity: 0.85;
      text-transform: uppercase;
      letter-spacing: 0.5px;
    }

    .persist-summary {
      display: flex;
      align-items: center;
      gap: 8px;
      padding: 12px 16px;
      background: #e8f5e9;
      border-radius: 8px;
      margin-bottom: 16px;
      color: #2e7d32;
    }

    .contributions-panel, .entities-panel, .relations-panel {
      margin-bottom: 12px;
    }

    .contributions-grid {
      display: grid;
      grid-template-columns: repeat(auto-fill, minmax(300px, 1fr));
      gap: 12px;
      padding: 8px 0;
    }

    .contribution-card {
      padding: 12px;
      border: 1px solid #e0e0e0;
      border-radius: 8px;
    }

    .contrib-header {
      display: flex;
      align-items: center;
      gap: 8px;
      margin-bottom: 8px;
      font-size: 14px;
    }

    .contrib-header mat-icon {
      color: #667eea;
      font-size: 20px;
      width: 20px;
      height: 20px;
    }

    .contrib-stats {
      display: flex;
      flex-direction: column;
      gap: 4px;
      font-size: 13px;
    }

    .stat {
      display: flex;
      gap: 8px;
    }

    .stat-label {
      color: #888;
      min-width: 70px;
    }

    .type-chips {
      display: flex;
      gap: 4px;
      flex-wrap: wrap;
    }

    .type-chip {
      padding: 1px 8px;
      border-radius: 10px;
      background: #e8eaf6;
      color: #3949ab;
      font-size: 11px;
    }

    .results-table {
      width: 100%;
    }

    .entity-type-badge {
      display: inline-block;
      padding: 2px 10px;
      border-radius: 10px;
      font-size: 11px;
      font-weight: 600;
      text-transform: uppercase;
      background: #e8eaf6;
      color: #3949ab;
    }

    .entity-type-badge[data-type="PERSON"] { background: #fce4ec; color: #c62828; }
    .entity-type-badge[data-type="ORGANIZATION"] { background: #e3f2fd; color: #1565c0; }
    .entity-type-badge[data-type="LOCATION"] { background: #e8f5e9; color: #2e7d32; }
    .entity-type-badge[data-type="CONCEPT"] { background: #fff3e0; color: #e65100; }
    .entity-type-badge[data-type="EVENT"] { background: #f3e5f5; color: #7b1fa2; }
    .entity-type-badge[data-type="DATE"] { background: #efebe9; color: #4e342e; }

    .relation-type-badge {
      display: inline-block;
      padding: 2px 10px;
      border-radius: 10px;
      font-size: 11px;
      font-weight: 500;
      background: #e0f2f1;
      color: #00695c;
    }

    .confidence.high { color: #2e7d32; font-weight: 600; }
    .confidence.medium { color: #f57f17; }
    .confidence.low { color: #c62828; }

    .error-banner {
      display: flex;
      align-items: center;
      gap: 8px;
      padding: 12px 16px;
      background: #fce4ec;
      border-radius: 8px;
      color: #c62828;
      margin-top: 16px;
    }

    .error-banner span {
      flex: 1;
    }

    .results-section {
      margin-top: 24px;
    }
  `]
})
export class MultiAgentGraphExtractionComponent implements OnInit, OnDestroy {
  private destroy$ = new Subject<void>();

  // Agent and strategy data
  agents: AgentInfo[] = [];
  strategies: MergeStrategyInfo[] = [];
  selectedAgentIds = new Set<string>();

  // Input configuration
  inputText = '';
  selectedStrategy = 'UNION';
  minConfidence = 0.5;
  entityTypesStr = 'PERSON,ORGANIZATION,LOCATION,CONCEPT,EVENT';
  persistResults = false;

  // State
  isExtracting = false;
  errorMessage: string | null = null;
  lastResult: ExtractionResponse | null = null;
  persistSummary: { entitiesCreated: number; entitiesSkipped: number;
    edgesCreated: number; edgesSkipped: number; errors: string[] } | null = null;

  // Table columns
  entityColumns = ['type', 'title', 'description', 'confidence'];
  relationColumns = ['source', 'type', 'target', 'confidence'];

  constructor(
    private multiAgentService: MultiAgentGraphService,
    private snackBar: MatSnackBar
  ) {}

  ngOnInit(): void {
    this.loadAgents();
    this.loadStrategies();
  }

  ngOnDestroy(): void {
    this.destroy$.next();
    this.destroy$.complete();
  }

  loadAgents(): void {
    this.multiAgentService.getAgents()
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: (agents) => {
          this.agents = agents;
          // Select all agents by default
          this.selectedAgentIds = new Set(agents.map(a => a.id));
        },
        error: (err) => {
          console.error('Failed to load agents:', err);
          this.agents = [];
        }
      });
  }

  loadStrategies(): void {
    this.multiAgentService.getStrategies()
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: (strategies) => {
          this.strategies = strategies;
        },
        error: (err) => {
          console.error('Failed to load strategies:', err);
          // Fallback defaults
          this.strategies = [
            { name: 'UNION', description: 'Accept all entities from all agents' },
            { name: 'INTERSECTION', description: 'Keep only entities found by 2+ agents' },
            { name: 'HIGHEST_CONFIDENCE', description: 'Keep highest confidence version' },
            { name: 'FIRST_WINS', description: 'First agent wins on conflicts' }
          ];
        }
      });
  }

  toggleAgent(id: string): void {
    if (this.selectedAgentIds.has(id)) {
      this.selectedAgentIds.delete(id);
    } else {
      this.selectedAgentIds.add(id);
    }
  }

  getAgentIcon(agentId: string): string {
    if (agentId.includes('llm')) return 'psychology';
    if (agentId.includes('pattern')) return 'pattern';
    if (agentId.includes('structural') || agentId.includes('excel')) return 'grid_on';
    return 'smart_toy';
  }

  getContributionKeys(): string[] {
    if (!this.lastResult?.contributions) return [];
    return Object.keys(this.lastResult.contributions);
  }

  runExtraction(): void {
    if (!this.inputText?.trim() || this.selectedAgentIds.size === 0) return;

    this.isExtracting = true;
    this.errorMessage = null;
    this.lastResult = null;
    this.persistSummary = null;

    const entityTypes = this.entityTypesStr.split(',').map(t => t.trim()).filter(t => t);

    const request: ExtractionRequest = {
      chunkTexts: [{ text: this.inputText.trim() }],
      agentIds: Array.from(this.selectedAgentIds),
      mergeStrategy: this.selectedStrategy,
      config: {
        entityTypes,
        minConfidence: this.minConfidence
      }
    };

    const handleError = (err: any) => {
      this.isExtracting = false;
      this.errorMessage = err.message || 'Extraction failed';
      this.snackBar.open('Extraction failed: ' + this.errorMessage, 'Dismiss', { duration: 5000 });
    };

    if (this.persistResults) {
      this.multiAgentService.extractAndPersist(request)
        .pipe(takeUntil(this.destroy$))
        .subscribe({
          next: (response) => {
            this.isExtracting = false;
            this.lastResult = response.extraction;
            this.persistSummary = response.persistence;
            this.snackBar.open(
              `Persisted ${response.persistence?.entitiesCreated || 0} entities and ${response.persistence?.edgesCreated || 0} edges`,
              'Dismiss', { duration: 4000 });
          },
          error: handleError
        });
    } else {
      this.multiAgentService.extract(request)
        .pipe(takeUntil(this.destroy$))
        .subscribe({
          next: (response) => {
            this.isExtracting = false;
            this.lastResult = response;
            this.snackBar.open(
              `Extracted ${response.totalEntities} entities and ${response.totalRelations} relations`,
              'Dismiss', { duration: 3000 });
          },
          error: handleError
        });
    }
  }

  clearResults(): void {
    this.lastResult = null;
    this.persistSummary = null;
    this.errorMessage = null;
  }
}
