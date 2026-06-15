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

import { Component, OnInit, OnDestroy, Input, Output, EventEmitter } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { MatCardModule } from '@angular/material/card';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatListModule } from '@angular/material/list';
import { MatChipsModule } from '@angular/material/chips';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';
import { MatExpansionModule } from '@angular/material/expansion';
import { MatTooltipModule } from '@angular/material/tooltip';
import { MatDividerModule } from '@angular/material/divider';
import { MatBadgeModule } from '@angular/material/badge';
import { Subject, takeUntil } from 'rxjs';
import { GraphService } from '../../services/graph.service';

interface SourceLink {
  sourceId1: string;
  sourceName1: string;
  sourceId2: string;
  sourceName2: string;
  linkType: string;
  strength: number;
  sharedConcepts: string[];
  description: string;
}

interface ConnectedSource {
  sourceId: string;
  sourceTitle: string;
  connectionCount: number;
}

@Component({
  selector: 'app-source-linking-panel',
  standalone: true,
  imports: [
    CommonModule,
    FormsModule,
    MatCardModule,
    MatButtonModule,
    MatIconModule,
    MatListModule,
    MatChipsModule,
    MatProgressSpinnerModule,
    MatSnackBarModule,
    MatExpansionModule,
    MatTooltipModule,
    MatDividerModule,
    MatBadgeModule
  ],
  template: `
    <div class="source-linking-panel">
      <div class="panel-header">
        <h3>
          <mat-icon>hub</mat-icon>
          Source Connections
        </h3>
        <button mat-icon-button (click)="refresh()" matTooltip="Refresh">
          <mat-icon>refresh</mat-icon>
        </button>
      </div>

      <mat-spinner *ngIf="loading" diameter="40"></mat-spinner>

      <div *ngIf="!loading" class="panel-content">
        <!-- Connectivity Summary -->
        <div class="connectivity-summary" *ngIf="connectivity">
          <div class="summary-stat">
            <span class="stat-value">{{connectivity.totalSources}}</span>
            <span class="stat-label">Sources</span>
          </div>
          <div class="summary-stat">
            <span class="stat-value">{{connectivity.totalSourceLinks}}</span>
            <span class="stat-label">Links</span>
          </div>
          <div class="summary-stat">
            <span class="stat-value">{{connectivity.isolatedSources}}</span>
            <span class="stat-label">Isolated</span>
          </div>
          <div class="summary-stat">
            <span class="stat-value">{{(connectivity.connectivityRatio * 100) | number:'1.0-0'}}%</span>
            <span class="stat-label">Connected</span>
          </div>
        </div>

        <!-- Most Connected Sources -->
        <mat-expansion-panel *ngIf="mostConnected.length > 0">
          <mat-expansion-panel-header>
            <mat-panel-title>
              <mat-icon>trending_up</mat-icon>
              Most Connected Sources
            </mat-panel-title>
          </mat-expansion-panel-header>
          <mat-list>
            <mat-list-item *ngFor="let source of mostConnected">
              <mat-icon matListItemIcon>folder</mat-icon>
              <span matListItemTitle>{{source.sourceTitle}}</span>
              <span matListItemMeta>
                <mat-chip>{{source.connectionCount}} links</mat-chip>
              </span>
            </mat-list-item>
          </mat-list>
        </mat-expansion-panel>

        <!-- Isolated Sources -->
        <mat-expansion-panel *ngIf="isolatedSources.length > 0">
          <mat-expansion-panel-header>
            <mat-panel-title>
              <mat-icon>warning</mat-icon>
              Isolated Sources
              <mat-chip class="count-chip">{{isolatedSources.length}}</mat-chip>
            </mat-panel-title>
          </mat-expansion-panel-header>
          <mat-list>
            <mat-list-item *ngFor="let sourceId of isolatedSources">
              <mat-icon matListItemIcon color="warn">folder_off</mat-icon>
              <span matListItemTitle>{{sourceId}}</span>
            </mat-list-item>
          </mat-list>
          <div class="panel-actions">
            <button mat-stroked-button color="primary" (click)="linkIsolatedSources()">
              <mat-icon>link</mat-icon>
              Auto-Link Isolated Sources
            </button>
          </div>
        </mat-expansion-panel>

        <!-- Source Links -->
        <mat-expansion-panel expanded>
          <mat-expansion-panel-header>
            <mat-panel-title>
              <mat-icon>link</mat-icon>
              Source Links
              <mat-chip class="count-chip">{{sourceLinks.length}}</mat-chip>
            </mat-panel-title>
          </mat-expansion-panel-header>
          <div class="links-list">
            <div class="link-item" *ngFor="let link of sourceLinks">
              <div class="link-sources">
                <span class="source-name">{{link.sourceName1}}</span>
                <mat-icon>swap_horiz</mat-icon>
                <span class="source-name">{{link.sourceName2}}</span>
              </div>
              <div class="link-details">
                <mat-chip class="link-type" [class]="link.linkType.toLowerCase()">{{formatLinkType(link.linkType)}}</mat-chip>
                <span class="link-strength">{{(link.strength * 100) | number:'1.0-0'}}%</span>
                <button mat-icon-button (click)="removeLink(link)" matTooltip="Remove Link" class="remove-btn">
                  <mat-icon>close</mat-icon>
                </button>
              </div>
              <div class="shared-concepts" *ngIf="link.sharedConcepts && link.sharedConcepts.length > 0">
                <span class="concepts-label">Shared:</span>
                <mat-chip *ngFor="let concept of link.sharedConcepts.slice(0, 5)" class="concept-chip">
                  {{concept}}
                </mat-chip>
                <span *ngIf="link.sharedConcepts.length > 5" class="more-concepts">
                  +{{link.sharedConcepts.length - 5}} more
                </span>
              </div>
            </div>
            <div *ngIf="sourceLinks.length === 0" class="no-links">
              <mat-icon>link_off</mat-icon>
              <p>No source links found</p>
              <button mat-raised-button color="primary" (click)="autoLinkSources()">
                <mat-icon>auto_fix_high</mat-icon>
                Auto-Link Sources
              </button>
            </div>
          </div>
        </mat-expansion-panel>
      </div>
    </div>
  `,
  styles: [`
    .source-linking-panel {
      padding: 16px;
      background: #1e1e2e;
      min-height: 100%;
    }

    .panel-header {
      display: flex;
      justify-content: space-between;
      align-items: center;
      margin-bottom: 16px;
    }

    .panel-header h3 {
      display: flex;
      align-items: center;
      gap: 8px;
      margin: 0;
      color: #fff;
      font-size: 16px;
    }

    .panel-content {
      display: flex;
      flex-direction: column;
      gap: 16px;
    }

    .connectivity-summary {
      display: grid;
      grid-template-columns: repeat(4, 1fr);
      gap: 8px;
      background: #2d2d44;
      border-radius: 8px;
      padding: 12px;
    }

    .summary-stat {
      display: flex;
      flex-direction: column;
      align-items: center;
      text-align: center;
    }

    .stat-value {
      font-size: 24px;
      font-weight: 600;
      color: #64b5f6;
    }

    .stat-label {
      font-size: 11px;
      color: #888;
      text-transform: uppercase;
    }

    .count-chip {
      margin-left: 8px;
      background: #3d5a80 !important;
      font-size: 11px;
    }

    .panel-actions {
      padding: 12px;
      display: flex;
      justify-content: center;
    }

    .links-list {
      display: flex;
      flex-direction: column;
      gap: 12px;
      padding: 8px;
    }

    .link-item {
      background: #2d2d44;
      border-radius: 8px;
      padding: 12px;
    }

    .link-sources {
      display: flex;
      align-items: center;
      gap: 8px;
      margin-bottom: 8px;
    }

    .source-name {
      color: #fff;
      font-weight: 500;
    }

    .link-sources mat-icon {
      color: #64b5f6;
      font-size: 18px;
    }

    .link-details {
      display: flex;
      align-items: center;
      gap: 8px;
    }

    .link-type {
      font-size: 10px;
      text-transform: uppercase;
    }

    .link-type.shared_concepts { background: #4CAF50 !important; }
    .link-type.cross_source { background: #2196F3 !important; }
    .link-type.user_defined { background: #FF9800 !important; }
    .link-type.embedding_similarity { background: #9C27B0 !important; }

    .link-strength {
      color: #888;
      font-size: 12px;
    }

    .remove-btn {
      margin-left: auto;
      opacity: 0.5;
    }

    .remove-btn:hover {
      opacity: 1;
    }

    .shared-concepts {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      gap: 4px;
      margin-top: 8px;
      padding-top: 8px;
      border-top: 1px solid #3d3d5c;
    }

    .concepts-label {
      color: #888;
      font-size: 11px;
      margin-right: 4px;
    }

    .concept-chip {
      font-size: 10px;
      background: #3d5a80 !important;
    }

    .more-concepts {
      color: #888;
      font-size: 11px;
    }

    .no-links {
      display: flex;
      flex-direction: column;
      align-items: center;
      padding: 24px;
      color: #888;
    }

    .no-links mat-icon {
      font-size: 48px;
      width: 48px;
      height: 48px;
      margin-bottom: 8px;
    }

    .no-links p {
      margin-bottom: 16px;
    }

    mat-expansion-panel {
      background: transparent !important;
    }

    mat-expansion-panel-header {
      background: #2d2d44 !important;
    }

    mat-panel-title {
      display: flex;
      align-items: center;
      gap: 8px;
    }

    ::ng-deep .mat-mdc-list-item-title {
      color: #fff !important;
    }
  `]
})
export class SourceLinkingPanelComponent implements OnInit, OnDestroy {
  private destroy$ = new Subject<void>();

  @Input() factSheetId: number | null = null;
  @Output() linksChanged = new EventEmitter<void>();

  loading = false;
  connectivity: any = null;
  sourceLinks: any[] = [];
  mostConnected: any[] = [];
  isolatedSources: string[] = [];

  constructor(
    private graphService: GraphService,
    private snackBar: MatSnackBar
  ) {}

  ngOnInit(): void {
    this.loadData();
  }

  ngOnDestroy(): void {
    this.destroy$.next();
    this.destroy$.complete();
  }

  refresh(): void {
    this.loadData();
  }

  loadData(): void {
    if (!this.factSheetId) return;

    this.loading = true;

    // Load connectivity summary
    this.graphService.getSourceConnectivity(this.factSheetId)
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: (data) => {
          this.connectivity = data;
        },
        error: (err) => console.error('Failed to load connectivity:', err)
      });

    // Load source links
    this.graphService.getSourceLinks(this.factSheetId)
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: (links) => {
          this.sourceLinks = links;
        },
        error: (err) => console.error('Failed to load source links:', err)
      });

    // Load most connected
    this.graphService.findMostConnectedSources(this.factSheetId, 5)
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: (sources) => {
          this.mostConnected = sources;
        },
        error: (err) => console.error('Failed to load most connected:', err)
      });

    // Load isolated sources
    this.graphService.findIsolatedSources(this.factSheetId)
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: (sources) => {
          this.isolatedSources = sources;
          this.loading = false;
        },
        error: (err) => {
          console.error('Failed to load isolated sources:', err);
          this.loading = false;
        }
      });
  }

  autoLinkSources(): void {
    if (!this.factSheetId) return;

    this.snackBar.open('Linking sources...', '', { duration: 2000 });
    this.graphService.linkSources(this.factSheetId)
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: (result) => {
          this.snackBar.open(`Created ${result.linksCreated} links`, 'Dismiss', { duration: 3000 });
          this.loadData();
          this.linksChanged.emit();
        },
        error: (err) => {
          console.error('Failed to link sources:', err);
          this.snackBar.open('Failed to link sources', 'Dismiss', { duration: 3000 });
        }
      });
  }

  linkIsolatedSources(): void {
    // Same as auto-link but focused on isolated
    this.autoLinkSources();
  }

  removeLink(link: any): void {
    if (!this.factSheetId) return;

    this.graphService.removeSourceLink(this.factSheetId, link.sourceId1, link.sourceId2)
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: () => {
          this.snackBar.open('Link removed', 'Dismiss', { duration: 2000 });
          this.loadData();
          this.linksChanged.emit();
        },
        error: (err) => {
          console.error('Failed to remove link:', err);
          this.snackBar.open('Failed to remove link', 'Dismiss', { duration: 3000 });
        }
      });
  }

  formatLinkType(type: string): string {
    return type.toLowerCase().replace(/_/g, ' ');
  }
}
