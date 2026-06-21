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
import { Subject, takeUntil } from 'rxjs';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatCardModule } from '@angular/material/card';
import { MatIconModule } from '@angular/material/icon';
import { MatChipsModule } from '@angular/material/chips';
import { MatButtonModule } from '@angular/material/button';
import { MatBadgeModule } from '@angular/material/badge';

import { WebSocketService } from '../../services/websocket.service';
import { UnifiedCrawlService } from '../../services/unified-crawl.service';
import { EnrichmentProgressUpdate } from '../../models/api-models';

interface PipelineStep {
  name: string;
  status: string;
  progressPercent: number;
}

interface ProgressSnapshot {
  jobId: string;
  currentPhase: string;
  progressPercent: number;
  pipelineSteps: PipelineStep[];
  entitiesExtracted: number;
  relationshipsExtracted: number;
  chunksEmbedded: number;
}

@Component({
  selector: 'app-hydration-progress-panel',
  standalone: true,
  imports: [
    CommonModule,
    MatProgressBarModule,
    MatCardModule,
    MatIconModule,
    MatChipsModule,
    MatButtonModule,
    MatBadgeModule
  ],
  template: `
    <div class="hydration-panel">
      <!-- Enrichment WebSocket Progress -->
      <mat-card class="progress-card">
        <mat-card-header>
          <mat-card-title>
            <mat-icon>local_florist</mat-icon> Enrichment Progress (WebSocket)
          </mat-card-title>
          <mat-card-subtitle>Live updates from /topic/enrichment/progress</mat-card-subtitle>
        </mat-card-header>
        <mat-card-content>
          <div *ngIf="enrichmentUpdate; else noEnrichment">
            <div class="progress-row">
              <span class="phase-label">Phase: <strong>{{ enrichmentUpdate.phase }}</strong></span>
              <span class="percent-label">{{ enrichmentUpdate.progressPercent | number:'1.0-0' }}%</span>
            </div>
            <mat-progress-bar mode="determinate"
                              [value]="enrichmentUpdate.progressPercent"
                              class="progress-bar">
            </mat-progress-bar>
            <div class="detail-row" *ngIf="enrichmentUpdate.message">
              <mat-icon class="detail-icon">info_outline</mat-icon>
              <span>{{ enrichmentUpdate.message }}</span>
            </div>
            <div class="stats-row" *ngIf="enrichmentUpdate.processedCount !== undefined">
              <span class="stat-chip">
                <mat-icon class="stat-icon">check_circle</mat-icon>
                {{ enrichmentUpdate.processedCount }} / {{ enrichmentUpdate.totalCount }} items
              </span>
              <span class="stat-chip" *ngIf="enrichmentUpdate.entityType">
                <mat-icon class="stat-icon">category</mat-icon>
                {{ enrichmentUpdate.entityType }}
              </span>
            </div>
            <div class="timestamp" *ngIf="enrichmentUpdate.timestamp">
              Last update: {{ enrichmentUpdate.timestamp | date:'medium' }}
            </div>
          </div>
          <ng-template #noEnrichment>
            <div class="empty-state">
              <mat-icon>wifi_off</mat-icon>
              No enrichment progress received yet. Progress updates appear here when an enrichment job is active.
            </div>
          </ng-template>
        </mat-card-content>
      </mat-card>

      <!-- Crawl ENRICHMENT Step Progress (SSE) -->
      <mat-card class="progress-card">
        <mat-card-header>
          <mat-card-title>
            <mat-icon>water</mat-icon> Crawl ENRICHMENT Step (SSE)
          </mat-card-title>
          <mat-card-subtitle>Filtered from /api/crawl-events/stream — ENRICHMENT phase only</mat-card-subtitle>
        </mat-card-header>
        <mat-card-content>
          <div *ngIf="crawlEnrichmentSnapshot; else noCrawl">
            <div class="progress-row">
              <span class="phase-label">Job: <strong>{{ crawlEnrichmentSnapshot.jobId }}</strong></span>
              <span class="percent-label">{{ crawlEnrichmentSnapshot.progressPercent | number:'1.0-0' }}%</span>
            </div>
            <mat-progress-bar mode="determinate"
                              [value]="crawlEnrichmentSnapshot.progressPercent"
                              class="progress-bar">
            </mat-progress-bar>

            <!-- Pipeline steps -->
            <div *ngIf="crawlEnrichmentSnapshot.pipelineSteps?.length" class="pipeline-steps">
              <div *ngFor="let step of crawlEnrichmentSnapshot.pipelineSteps" class="step-row">
                <mat-icon class="step-icon"
                          [class.done]="step.status === 'COMPLETED'"
                          [class.running]="step.status === 'RUNNING'"
                          [class.failed]="step.status === 'FAILED'">
                  {{ step.status === 'COMPLETED' ? 'check_circle' : step.status === 'RUNNING' ? 'pending' : step.status === 'FAILED' ? 'error' : 'radio_button_unchecked' }}
                </mat-icon>
                <span class="step-name">{{ step.name }}</span>
                <span class="step-pct" *ngIf="step.status === 'RUNNING'">{{ step.progressPercent | number:'1.0-0' }}%</span>
              </div>
            </div>

            <div class="stats-row">
              <span class="stat-chip" *ngIf="crawlEnrichmentSnapshot.entitiesExtracted">
                <mat-icon class="stat-icon">hub</mat-icon>
                {{ crawlEnrichmentSnapshot.entitiesExtracted }} entities
              </span>
              <span class="stat-chip" *ngIf="crawlEnrichmentSnapshot.relationshipsExtracted">
                <mat-icon class="stat-icon">share</mat-icon>
                {{ crawlEnrichmentSnapshot.relationshipsExtracted }} relations
              </span>
              <span class="stat-chip" *ngIf="crawlEnrichmentSnapshot.chunksEmbedded">
                <mat-icon class="stat-icon">memory</mat-icon>
                {{ crawlEnrichmentSnapshot.chunksEmbedded }} chunks
              </span>
            </div>
          </div>
          <ng-template #noCrawl>
            <div class="empty-state">
              <mat-icon>wifi_off</mat-icon>
              No ENRICHMENT-phase crawl progress received. Events appear here during an active crawl.
            </div>
          </ng-template>

          <div class="sse-status">
            <span class="sse-dot" [class.connected]="sseConnected"></span>
            SSE {{ sseConnected ? 'connected' : 'disconnected' }}
          </div>
        </mat-card-content>
      </mat-card>
    </div>
  `,
  styleUrls: ['./hydration-progress-panel.component.css']
})
export class HydrationProgressPanelComponent implements OnInit, OnDestroy {
  enrichmentUpdate: EnrichmentProgressUpdate | null = null;
  crawlEnrichmentSnapshot: ProgressSnapshot | null = null;
  sseConnected = false;

  private destroy$ = new Subject<void>();
  private eventSource: EventSource | null = null;

  constructor(
    private wsService: WebSocketService,
    private crawlService: UnifiedCrawlService
  ) {}

  ngOnInit(): void {
    // WebSocket: enrichment progress
    this.wsService.subscribeToEnrichmentProgress()
      .pipe(takeUntil(this.destroy$))
      .subscribe(update => {
        this.enrichmentUpdate = update;
      });

    // SSE: crawl events — filter to ENRICHMENT phase
    const url = this.crawlService.crawlEventsStreamUrl();
    this.eventSource = new EventSource(url);
    this.sseConnected = true;

    this.eventSource.onmessage = (event: MessageEvent) => {
      try {
        const snapshot: ProgressSnapshot = JSON.parse(event.data);
        if (snapshot.currentPhase === 'ENRICHMENT') {
          this.crawlEnrichmentSnapshot = snapshot;
        }
      } catch {
        // ignore malformed events
      }
    };

    this.eventSource.onerror = () => {
      this.sseConnected = false;
    };

    this.eventSource.onopen = () => {
      this.sseConnected = true;
    };
  }

  ngOnDestroy(): void {
    this.destroy$.next();
    this.destroy$.complete();
    if (this.eventSource) {
      this.eventSource.close();
      this.eventSource = null;
    }
  }
}
