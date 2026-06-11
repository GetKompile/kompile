/*
 *   Copyright 2025 Kompile Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 * limitations under the License.
 */

import {
  Component, OnInit, OnDestroy, ChangeDetectionStrategy, ChangeDetectorRef
} from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { RouterModule } from '@angular/router';
import { Subscription } from 'rxjs';

// Angular Material
import { MatCardModule } from '@angular/material/card';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatSlideToggleModule } from '@angular/material/slide-toggle';
import { MatChipsModule } from '@angular/material/chips';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatExpansionModule } from '@angular/material/expansion';
import { MatTooltipModule } from '@angular/material/tooltip';
import { MatDividerModule } from '@angular/material/divider';
import { MatSnackBarModule, MatSnackBar } from '@angular/material/snack-bar';
import { MatTabsModule } from '@angular/material/tabs';
import { MatBadgeModule } from '@angular/material/badge';

import {
  UnifiedCrawlService,
  UnifiedCrawlSource,
  GraphExtractionConfig,
  VectorIndexConfig,
  UnifiedCrawlRequest,
  ProcessingRouteConfig,
  ProcessingBackend,
  CapacitySnapshot,
  JobSummary,
  JobDetail,
  PipelineStepProgress,
  DocumentGraphProgress,
  AvailableSourceType,
  SubprocessEvent,
  SubprocessStatistics
} from '../../services/unified-crawl.service';
import { JobLogViewerComponent } from '../job-history/job-log-viewer/job-log-viewer.component';
import { FactSheetService } from '../../services/fact-sheet.service';
import { FactSheet } from '../../models/api-models';
import { GraphExtractionService, ModelProvider } from '../../services/graph-extraction.service';
import { WebSocketService } from '../../services/websocket.service';

type EditableUnifiedCrawlSource = UnifiedCrawlSource & { propertiesJson?: string };

@Component({
  selector: 'app-unified-crawl',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    CommonModule, FormsModule, RouterModule,
    MatCardModule, MatButtonModule, MatIconModule,
    MatFormFieldModule, MatInputModule, MatSelectModule,
    MatSlideToggleModule, MatChipsModule, MatProgressBarModule,
    MatExpansionModule, MatTooltipModule, MatDividerModule,
    MatSnackBarModule, MatTabsModule, MatBadgeModule,
    JobLogViewerComponent
  ],
  template: `
    <div class="unified-crawl-container">
      <!-- Scheduler notification banner -->
      <div *ngIf="schedulerNotifications.length > 0" class="scheduler-notifications">
        <div *ngFor="let notif of schedulerNotifications; let i = index"
             class="scheduler-notification"
             [class.notification-blocked]="notif.eventType === 'JOB_BLOCKED'"
             [class.notification-skipped]="notif.eventType === 'JOB_SKIPPED_AHEAD'"
             [class.notification-reordered]="notif.eventType === 'JOB_REORDERED'"
             [class.notification-dispatched]="notif.eventType === 'JOB_DISPATCHED'"
             [class.notification-completed]="notif.eventType === 'JOB_COMPLETED'"
             [class.notification-failed]="notif.eventType === 'JOB_FAILED'">
          <mat-icon class="notif-icon">
            {{ notif.eventType === 'JOB_BLOCKED' ? 'pause_circle' :
               notif.eventType === 'JOB_SKIPPED_AHEAD' ? 'fast_forward' :
               notif.eventType === 'JOB_REORDERED' ? 'swap_vert' :
               notif.eventType === 'JOB_DISPATCHED' ? 'play_circle' :
               notif.eventType === 'JOB_COMPLETED' ? 'check_circle' :
               notif.eventType === 'JOB_FAILED' ? 'error' : 'info' }}
          </mat-icon>
          <span class="notif-text">{{ notif.message }}</span>
          <span class="notif-meta">{{ notif.jobType }} | Q:{{ notif.queueDepth }} R:{{ notif.runningCount }}</span>
          <button mat-icon-button (click)="dismissNotification(i)" class="notif-dismiss">
            <mat-icon>close</mat-icon>
          </button>
        </div>
      </div>
      <mat-tab-group [(selectedIndex)]="activeTab">
        <!-- New Job Tab -->
        <mat-tab label="New Crawl Job">
          <div class="tab-content">
            <mat-card>
              <mat-card-header>
                <mat-card-title>Unified Crawl-to-Graph</mat-card-title>
                <mat-card-subtitle>Crawl multiple sources and automatically build a knowledge graph + vector index</mat-card-subtitle>
              </mat-card-header>
              <mat-card-content>
                <!-- Job name -->
                <mat-form-field appearance="outline" class="full-width">
                  <mat-label>Job Name</mat-label>
                  <input matInput [(ngModel)]="jobName" placeholder="e.g., Company Knowledge Base">
                </mat-form-field>

                <div class="target-sheet" *ngIf="activeFactSheet">
                  <mat-icon>folder_special</mat-icon>
                  <span>{{ activeFactSheet.name }}</span>
                </div>

                <!-- Sources -->
                <h3>Sources
                  <button mat-icon-button color="primary" (click)="addSource()" matTooltip="Add source">
                    <mat-icon>add_circle</mat-icon>
                  </button>
                </h3>

                <div *ngFor="let source of sources; let i = index" class="source-card">
                  <mat-expansion-panel [expanded]="i === sources.length - 1">
                    <mat-expansion-panel-header>
                      <mat-panel-title>
                        <mat-icon class="source-icon">{{ getSourceIcon(source.sourceType) }}</mat-icon>
                        {{ source.label || 'Source ' + (i + 1) }}
                      </mat-panel-title>
                      <mat-panel-description>
                        {{ source.sourceType || 'Not configured' }}
                        <span *ngIf="source.pathOrUrl"> &mdash; {{ source.pathOrUrl | slice:0:40 }}</span>
                      </mat-panel-description>
                    </mat-expansion-panel-header>

                    <div class="source-form">
                      <div class="form-row">
                        <mat-form-field appearance="outline">
                          <mat-label>Label</mat-label>
                          <input matInput [(ngModel)]="source.label" placeholder="e.g., Project docs">
                        </mat-form-field>

                        <mat-form-field appearance="outline">
                          <mat-label>Source Type</mat-label>
                          <mat-select [(ngModel)]="source.sourceType">
                            <mat-option *ngFor="let st of availableSourceTypes" [value]="st.type"
                                        [disabled]="!st.available">
                              {{ st.displayName }}
                              <span *ngIf="!st.available" class="unavailable">(not available)</span>
                            </mat-option>
                          </mat-select>
                        </mat-form-field>
                      </div>

                      <mat-form-field appearance="outline" class="full-width">
                        <mat-label>Path or URL</mat-label>
                        <input matInput [(ngModel)]="source.pathOrUrl"
                               placeholder="e.g., /data/docs or https://example.com">
                      </mat-form-field>

                      <div class="form-row">
                        <mat-form-field appearance="outline">
                          <mat-label>Max Depth</mat-label>
                          <input matInput type="number" [(ngModel)]="source.maxDepth" min="0" max="10">
                        </mat-form-field>

                        <mat-form-field appearance="outline">
                          <mat-label>Max Documents (0 = unlimited)</mat-label>
                          <input matInput type="number" [(ngModel)]="source.maxDocuments" min="0">
                        </mat-form-field>
                      </div>

                      <mat-form-field appearance="outline" class="full-width">
                        <mat-label>Properties JSON</mat-label>
                        <textarea matInput rows="3" [(ngModel)]="source.propertiesJson"
                                  placeholder='{"crawlerId":"filesystem","folder":"Inbox"}'></textarea>
                      </mat-form-field>

                      <button mat-button color="warn" (click)="removeSource(i)">
                        <mat-icon>delete</mat-icon> Remove Source
                      </button>
                    </div>
                  </mat-expansion-panel>
                </div>

                <div *ngIf="sources.length === 0" class="empty-state">
                  <mat-icon>source</mat-icon>
                  <p>No sources added yet. Click + to add a source.</p>
                </div>

                <mat-divider class="section-divider"></mat-divider>

                <!-- Graph Extraction Settings -->
                <h3>
                  <mat-slide-toggle [(ngModel)]="graphEnabled" color="primary">
                    Graph Extraction
                  </mat-slide-toggle>
                </h3>

                <div *ngIf="graphEnabled" class="settings-section">
                  <div class="form-row">
                    <mat-form-field appearance="outline">
                      <mat-label>LLM Provider</mat-label>
                      <mat-select [(ngModel)]="graphLlmProvider" (selectionChange)="onGraphLlmProviderChange()">
                        <mat-option value="default">Default</mat-option>
                        <mat-option *ngFor="let p of graphModelProviders" [value]="p.id">
                          {{ p.name }}
                        </mat-option>
                      </mat-select>
                    </mat-form-field>

                    <mat-form-field appearance="outline">
                      <mat-label>Model Name (optional)</mat-label>
                      <mat-select *ngIf="graphAvailableModels.length > 0" [(ngModel)]="graphModelName">
                        <mat-option value="">None (provider default)</mat-option>
                        <mat-option *ngFor="let m of graphAvailableModels" [value]="m.id">
                          {{ m.name || m.id }}
                        </mat-option>
                      </mat-select>
                      <input *ngIf="graphAvailableModels.length === 0" matInput [(ngModel)]="graphModelName"
                             placeholder="Leave blank for provider default">
                    </mat-form-field>
                  </div>

                  <mat-form-field appearance="outline" class="full-width">
                    <mat-label>Schema Preset</mat-label>
                    <input matInput [(ngModel)]="graphSchemaPresetId" placeholder="fpna-cpg-channel-v1">
                  </mat-form-field>

                  <mat-form-field appearance="outline" class="full-width">
                    <mat-label>Entity Types (comma-separated)</mat-label>
                    <input matInput [(ngModel)]="graphEntityTypesStr"
                           placeholder="e.g., PERSON, ORGANIZATION, PRODUCT, CONCEPT">
                  </mat-form-field>

                  <mat-form-field appearance="outline" class="full-width">
                    <mat-label>Relationship Types (comma-separated, optional)</mat-label>
                    <input matInput [(ngModel)]="graphRelTypesStr"
                           placeholder="e.g., WORKS_AT, LOCATED_IN, DEPENDS_ON">
                  </mat-form-field>

                  <div class="form-row">
                    <mat-form-field appearance="outline">
                      <mat-label>Schema Mode</mat-label>
                      <mat-select [(ngModel)]="graphSchemaMode">
                        <mat-option value="NONE">None</mat-option>
                        <mat-option value="LENIENT">Lenient</mat-option>
                        <mat-option value="STRICT">Strict</mat-option>
                      </mat-select>
                    </mat-form-field>

                    <mat-form-field appearance="outline">
                      <mat-label>Min Confidence</mat-label>
                      <input matInput type="number" [(ngModel)]="graphMinConfidence" min="0" max="1" step="0.1">
                    </mat-form-field>
                  </div>

                  <mat-slide-toggle [(ngModel)]="graphEntityResolution" color="primary">
                    Entity Resolution (merge duplicates across sources)
                  </mat-slide-toggle>

                  <div *ngIf="graphEntityResolution" class="form-row">
                    <mat-form-field appearance="outline">
                      <mat-label>Resolution Threshold</mat-label>
                      <input matInput type="number" [(ngModel)]="graphEntityResolutionSimilarityThreshold" min="0" max="1" step="0.01">
                    </mat-form-field>

                    <mat-slide-toggle [(ngModel)]="graphEntityResolutionUseEmbeddings" color="primary">
                      Use Embeddings
                    </mat-slide-toggle>

                    <mat-form-field appearance="outline">
                      <mat-label>Embedding Threshold</mat-label>
                      <input matInput type="number" [(ngModel)]="graphEntityResolutionEmbeddingThreshold" min="0" max="1" step="0.01">
                    </mat-form-field>
                  </div>
                </div>

                <mat-divider class="section-divider"></mat-divider>

                <!-- Vector Indexing Settings -->
                <h3>
                  <mat-slide-toggle [(ngModel)]="indexEnabled" color="primary">
                    Vector Indexing
                  </mat-slide-toggle>
                </h3>

                <div *ngIf="indexEnabled" class="settings-section">
                  <mat-form-field appearance="outline" class="full-width">
                    <mat-label>Collection Name</mat-label>
                    <input matInput [(ngModel)]="indexCollectionName" placeholder="e.g., my-knowledge-base">
                  </mat-form-field>
                  <div class="form-row">
                    <mat-form-field appearance="outline">
                      <mat-label>Embedding Batch Size</mat-label>
                      <input matInput type="number" [(ngModel)]="embeddingBatchSize" min="0">
                    </mat-form-field>
                    <mat-form-field appearance="outline">
                      <mat-label>Max Embedding Batch</mat-label>
                      <input matInput type="number" [(ngModel)]="maxEmbeddingBatchSize" min="0">
                    </mat-form-field>
                  </div>
                  <mat-slide-toggle [(ngModel)]="adaptiveBatching" color="primary">
                    Adaptive batching under memory pressure
                  </mat-slide-toggle>
                </div>

                <!-- Processing Routes -->
                <h3>
                  <mat-slide-toggle [(ngModel)]="processingRouteEnabled" color="primary">
                    Processing Routes
                  </mat-slide-toggle>
                </h3>

                <div *ngIf="processingRouteEnabled" class="settings-section">
                  <mat-form-field appearance="outline" class="full-width">
                    <mat-label>PDF Routing Mode</mat-label>
                    <mat-select [(ngModel)]="pdfRoutingMode">
                      <mat-option value="AUTO">Auto — classify PDFs, text-only to cheap parser, images to VLM</mat-option>
                      <mat-option value="FORCE_VLM">Force VLM — all PDFs through VLM pipeline</mat-option>
                      <mat-option value="FORCE_TEXT">Force Text — all PDFs through text extraction only</mat-option>
                      <mat-option value="DISABLED">Disabled — no PDF-specific routing</mat-option>
                    </mat-select>
                  </mat-form-field>

                  <mat-slide-toggle [(ngModel)]="extractTablesFromTextPdfs" color="primary">
                    Extract tables from text PDFs (via Tabula)
                  </mat-slide-toggle>

                  <mat-slide-toggle [(ngModel)]="fallbackEnabled" color="primary" class="fallback-toggle">
                    Enable capacity-based fallback routing
                  </mat-slide-toggle>

                  <div *ngIf="fallbackEnabled" class="backends-section">
                    <p class="hint-text">
                      When local model capacity is exhausted, requests fall back through this chain.
                      Lower priority number = preferred backend.
                    </p>

                    <div *ngFor="let backend of backends; let i = index" class="backend-row">
                      <mat-form-field appearance="outline">
                        <mat-label>Backend ID</mat-label>
                        <input matInput [(ngModel)]="backend.id" placeholder="e.g., local-vlm">
                      </mat-form-field>
                      <mat-form-field appearance="outline">
                        <mat-label>Type</mat-label>
                        <mat-select [(ngModel)]="backend.type">
                          <mat-option value="LOCAL_MODEL">Local Model</mat-option>
                          <mat-option value="CLI_AGENT">CLI Agent</mat-option>
                          <mat-option value="API_AGENT">API Agent</mat-option>
                        </mat-select>
                      </mat-form-field>
                      <mat-form-field appearance="outline">
                        <mat-label>Priority</mat-label>
                        <input matInput type="number" [(ngModel)]="backend.priority" min="1">
                      </mat-form-field>
                      <mat-form-field appearance="outline">
                        <mat-label>Max Concurrent</mat-label>
                        <input matInput type="number" [(ngModel)]="backend.maxConcurrent" min="0">
                      </mat-form-field>
                      <mat-form-field appearance="outline">
                        <mat-label>Rate Limit (rpm)</mat-label>
                        <input matInput type="number" [(ngModel)]="backend.requestsPerMinute" min="0">
                      </mat-form-field>
                      <mat-form-field appearance="outline" *ngIf="backend.type === 'CLI_AGENT'">
                        <mat-label>Agent Name</mat-label>
                        <input matInput [(ngModel)]="backend.agentName" placeholder="e.g., claude-cli">
                      </mat-form-field>
                      <mat-form-field appearance="outline" *ngIf="backend.type === 'API_AGENT'">
                        <mat-label>Endpoint URL</mat-label>
                        <input matInput [(ngModel)]="backend.endpointUrl" placeholder="e.g., https://api.openai.com/v1">
                      </mat-form-field>
                      <mat-form-field appearance="outline" *ngIf="backend.type === 'API_AGENT'">
                        <mat-label>Model</mat-label>
                        <input matInput [(ngModel)]="backend.modelName" placeholder="e.g., gpt-4o">
                      </mat-form-field>
                      <button mat-icon-button color="warn" (click)="removeBackend(i)">
                        <mat-icon>delete</mat-icon>
                      </button>
                    </div>

                    <button mat-stroked-button color="primary" (click)="addBackend()">
                      <mat-icon>add</mat-icon> Add Backend
                    </button>
                  </div>
                </div>

              </mat-card-content>
              <mat-card-actions align="end">
                <button mat-raised-button color="primary"
                        [disabled]="sources.length === 0 || isStarting"
                        (click)="startJob()">
                  <mat-icon>play_arrow</mat-icon>
                  {{ isStarting ? 'Starting...' : 'Start Crawl' }}
                </button>
              </mat-card-actions>
            </mat-card>
          </div>
        </mat-tab>

        <!-- Jobs Tab -->
        <mat-tab>
          <ng-template mat-tab-label>
            Jobs
            <span *ngIf="activeJobCount > 0" class="tab-badge">{{ activeJobCount }}</span>
          </ng-template>
          <div class="tab-content">
            <div class="jobs-toolbar">
              <button mat-button (click)="refreshJobs()">
                <mat-icon>refresh</mat-icon> Refresh
              </button>
              <button mat-button color="warn" (click)="cleanupJobs()" [disabled]="jobs.length === 0">
                <mat-icon>cleaning_services</mat-icon> Cleanup Finished
              </button>
            </div>

            <div *ngIf="jobs.length === 0" class="empty-state">
              <mat-icon>work_outline</mat-icon>
              <p>No crawl jobs yet. Start one from the "New Crawl Job" tab.</p>
            </div>

            <mat-card *ngFor="let job of jobs" class="job-card" (click)="selectJob(job.jobId)">
              <mat-card-header>
                <mat-card-title>
                  {{ job.name || 'Unnamed Job' }}
                  <span class="status-badge" [class]="'status-' + job.status.toLowerCase()">
                    {{ job.status }}
                  </span>
                </mat-card-title>
                <mat-card-subtitle>
                  <span class="job-type-badges">
                    <span *ngIf="job.graphExtractionEnabled" class="job-type-badge graph-badge"
                          matTooltip="Graph extraction enabled{{ job.llmModel ? ' (' + job.llmModel + ')' : '' }}">
                      <mat-icon inline>hub</mat-icon> Graph
                      <ng-container *ngIf="job.llmProvider && job.llmProvider !== 'default'"> ({{ job.llmProvider }})</ng-container>
                    </span>
                    <span *ngIf="job.vectorIndexEnabled" class="job-type-badge vector-badge">
                      <mat-icon inline>storage</mat-icon> Vector Index
                    </span>
                    <span *ngIf="!job.graphExtractionEnabled && !job.vectorIndexEnabled" class="job-type-badge crawl-only-badge">
                      <mat-icon inline>travel_explore</mat-icon> Crawl Only
                    </span>
                  </span>
                  {{ job.sourceCount }} source(s)
                  <span *ngIf="job.elapsedMs > 0" class="elapsed">
                    &bull; {{ formatElapsed(job.elapsedMs) }}
                  </span>
                  <span *ngIf="job.createdAt" class="elapsed">
                    &bull; Started {{ formatTimestamp(job.createdAt) }}
                  </span>
                  <span *ngIf="job.progressPercent !== undefined && job.progressPercent > 0" class="elapsed">
                    &bull; {{ job.progressPercent }}%
                  </span>
                </mat-card-subtitle>
              </mat-card-header>
              <mat-card-content>
                <!-- Current activity banner -->
                <div *ngIf="job.status === 'RUNNING'" class="current-activity">
                  <mat-icon class="activity-icon spinning-icon">sync</mat-icon>
                  <span class="phase-chip">{{ formatPhase(job.currentPhase) || job.status }}</span>
                  <span *ngIf="job.currentFile" class="activity-detail">{{ job.currentFile }}</span>
                  <span *ngIf="job.currentBatchStep" class="activity-detail">{{ job.currentBatchStep }}</span>
                  <span *ngIf="job.queuePosition && job.queuePosition > 0" class="activity-detail">Queue #{{ job.queuePosition }} of {{ job.queuedJobs }}</span>
                  <span *ngIf="job.vectorBatchesTotal && job.vectorBatchesTotal > 0" class="activity-detail">
                    Batch {{ job.vectorBatchesCompleted || 0 }}/{{ job.vectorBatchesTotal }}
                    <ng-container *ngIf="job.currentBatchSize">({{ job.currentBatchSize }} chunks)</ng-container>
                  </span>
                </div>
                <div *ngIf="job.status === 'PENDING'" class="current-activity pending-activity">
                  <mat-icon class="activity-icon">hourglass_empty</mat-icon>
                  <span class="phase-chip">Queued</span>
                  <span *ngIf="job.queuePosition && job.queuePosition > 0" class="activity-detail">Position #{{ job.queuePosition }} of {{ job.queuedJobs }}</span>
                </div>

                <!-- Document processing pipeline counters -->
                <div class="job-pipeline-counters">
                  <div class="pipeline-counter" [class.active]="job.currentPhase === 'DISCOVERING' || job.currentPhase === 'LOADING'">
                    <div class="counter-value">{{ job.documentsDiscovered || 0 }}</div>
                    <div class="counter-label"><mat-icon inline>search</mat-icon> Found</div>
                  </div>
                  <mat-icon class="counter-arrow">arrow_forward</mat-icon>
                  <div class="pipeline-counter" [class.active]="job.currentPhase === 'LOADING'">
                    <div class="counter-value">{{ job.documentsLoaded }}</div>
                    <div class="counter-label"><mat-icon inline>description</mat-icon> Loaded</div>
                  </div>
                  <mat-icon class="counter-arrow">arrow_forward</mat-icon>
                  <div class="pipeline-counter" [class.active]="job.currentPhase === 'CHUNKING'">
                    <div class="counter-value">{{ job.chunksCreated || job.chunksProcessed || 0 }}</div>
                    <div class="counter-label"><mat-icon inline>segment</mat-icon> Chunks</div>
                  </div>
                  <mat-icon class="counter-arrow" *ngIf="job.graphExtractionEnabled !== false">arrow_forward</mat-icon>
                  <div class="pipeline-counter" *ngIf="job.graphExtractionEnabled !== false"
                       [class.active]="job.currentPhase === 'GRAPH_EXTRACTION' || job.currentPhase === 'GRAPH_PREP' || job.currentPhase === 'ENTITY_RESOLUTION' || job.currentPhase === 'EDGE_COMPUTATION'">
                    <div class="counter-value">{{ job.entitiesExtracted || 0 }}<span class="counter-sub" *ngIf="job.relationshipsExtracted"> / {{ job.relationshipsExtracted }} rels</span></div>
                    <div class="counter-label"><mat-icon inline>hub</mat-icon> Entities</div>
                  </div>
                  <mat-icon class="counter-arrow" *ngIf="job.vectorIndexEnabled !== false">arrow_forward</mat-icon>
                  <div class="pipeline-counter" *ngIf="job.vectorIndexEnabled !== false"
                       [class.active]="job.currentPhase === 'EMBEDDING'">
                    <div class="counter-value">{{ job.chunksEmbedded || 0 }}<span class="counter-sub" *ngIf="job.chunksQueuedForEmbedding"> / {{ job.chunksQueuedForEmbedding }}</span></div>
                    <div class="counter-label"><mat-icon inline>memory</mat-icon> Embedded</div>
                  </div>
                  <mat-icon class="counter-arrow" *ngIf="job.vectorIndexEnabled !== false">arrow_forward</mat-icon>
                  <div class="pipeline-counter" *ngIf="job.vectorIndexEnabled !== false"
                       [class.active]="job.currentPhase === 'INDEXING' || job.currentPhase === 'VECTOR_INDEXING'">
                    <div class="counter-value">{{ job.documentsIndexed || 0 }}</div>
                    <div class="counter-label"><mat-icon inline>storage</mat-icon> Indexed</div>
                  </div>
                </div>

                <!-- Graph stats (if available) -->
                <div class="job-graph-stats" *ngIf="(job.graphNodeCount ?? 0) > 0">
                  <mat-icon inline>scatter_plot</mat-icon>
                  <span>{{ job.graphNodeCount }} graph nodes &bull; {{ job.graphEdgeCount }} graph edges</span>
                </div>

                <!-- Real-time entity type breakdown -->
                <div class="job-type-breakdown" *ngIf="job.entityTypeCounts && objectKeys(job.entityTypeCounts).length > 0">
                  <div class="type-breakdown-header">
                    <mat-icon inline>category</mat-icon>
                    <span>Entity Types</span>
                  </div>
                  <div class="type-chips">
                    <span class="type-chip" *ngFor="let entry of sortedTypeCounts(job.entityTypeCounts) | slice:0:12">
                      <span class="type-name">{{ entry[0] }}</span>
                      <span class="type-count">{{ entry[1] }}</span>
                    </span>
                    <span class="type-chip more" *ngIf="objectKeys(job.entityTypeCounts).length > 12">
                      +{{ objectKeys(job.entityTypeCounts).length - 12 }} more
                    </span>
                  </div>
                </div>

                <!-- Real-time relationship type breakdown -->
                <div class="job-type-breakdown" *ngIf="job.relationshipTypeCounts && objectKeys(job.relationshipTypeCounts).length > 0">
                  <div class="type-breakdown-header">
                    <mat-icon inline>link</mat-icon>
                    <span>Relationship Types</span>
                  </div>
                  <div class="type-chips">
                    <span class="type-chip rel-chip" *ngFor="let entry of sortedTypeCounts(job.relationshipTypeCounts) | slice:0:10">
                      <span class="type-name">{{ entry[0] }}</span>
                      <span class="type-count">{{ entry[1] }}</span>
                    </span>
                    <span class="type-chip more" *ngIf="objectKeys(job.relationshipTypeCounts).length > 10">
                      +{{ objectKeys(job.relationshipTypeCounts).length - 10 }} more
                    </span>
                  </div>
                </div>

                <!-- Source progress strip -->
                <div class="source-progress-strip" *ngIf="job.sources && job.sources.length > 0">
                  <div *ngFor="let src of job.sources" class="source-progress-row">
                    <mat-icon inline class="source-icon-sm">{{ getSourceIcon(src.sourceType || '') }}</mat-icon>
                    <span class="source-label" [title]="src.pathOrUrl">{{ src.label || src.pathOrUrl }}</span>
                    <span class="source-status-chip" [class]="'status-' + (src.status || 'PENDING').toLowerCase()">{{ src.status || 'PENDING' }}</span>
                    <span class="source-counters">
                      {{ src.documentsDiscovered || 0 }} found
                      <ng-container *ngIf="src.documentsLoaded"> &bull; {{ src.documentsLoaded }} loaded</ng-container>
                      <ng-container *ngIf="src.chunksCreated"> &bull; {{ src.chunksCreated }} chunks</ng-container>
                    </span>
                    <span *ngIf="src.currentItem" class="source-current-item">
                      <mat-icon inline class="spinning-icon-sm">sync</mat-icon> {{ src.currentItem | slice:0:40 }}
                    </span>
                  </div>
                </div>

                <!-- Discovery feed (recently discovered files/URLs) -->
                <div class="discovery-feed" *ngIf="job.recentlyDiscoveredItems && job.recentlyDiscoveredItems.length > 0 && (job.status === 'RUNNING' || job.status === 'PENDING')">
                  <div class="discovery-feed-header">
                    <mat-icon inline>explore</mat-icon>
                    <span>Recently Discovered ({{ job.documentsDiscovered || 0 }} total)</span>
                  </div>
                  <div class="discovery-feed-items">
                    <div *ngFor="let item of job.recentlyDiscoveredItems | slice:-8" class="discovery-item">
                      <mat-icon inline class="discovery-icon">{{ getSourceIcon(item.sourceType) }}</mat-icon>
                      <span class="discovery-name" [title]="item.name">{{ item.name | slice:0:60 }}</span>
                      <span class="discovery-source">{{ item.sourceLabel }}</span>
                    </div>
                  </div>
                </div>

                <!-- Recent document activity (top 5 most recently active documents) -->
                <div class="recent-docs-feed" *ngIf="job.recentDocuments && job.recentDocuments.length > 0">
                  <div class="recent-docs-header">
                    <mat-icon inline>description</mat-icon>
                    <span>Recent Document Activity</span>
                  </div>
                  <div class="recent-docs-list">
                    <div *ngFor="let doc of job.recentDocuments | slice:0:5" class="recent-doc-row"
                         [ngClass]="getDocumentStatusClass(doc.status)">
                      <span class="recent-doc-name" [title]="doc.documentKey">{{ doc.fileName || doc.documentKey | slice:0:40 }}</span>
                      <span class="recent-doc-status" [class]="'doc-status-' + (doc.status || 'unknown').toLowerCase()">{{ doc.status || '?' }}</span>
                      <span class="recent-doc-phase" *ngIf="doc.phase">{{ formatPhase(doc.phase) }}</span>
                      <span class="recent-doc-stats">
                        <ng-container *ngIf="doc.chunksCreated">{{ doc.chunksCreated }} chunks</ng-container>
                        <ng-container *ngIf="doc.entitiesExtracted"> &bull; {{ doc.entitiesExtracted }} entities</ng-container>
                      </span>
                      <span *ngIf="doc.errorMessage" class="recent-doc-error" [title]="doc.errorMessage">
                        <mat-icon inline>error</mat-icon>
                      </span>
                    </div>
                  </div>
                </div>

                <!-- Memory & error row -->
                <div class="job-meta-row">
                  <span *ngIf="(job.status === 'RUNNING' || job.status === 'PENDING') && job.memoryUsagePercent !== undefined" class="meta-item">
                    <mat-icon inline>speed</mat-icon> {{ job.memoryUsagePercent }}% heap
                  </span>
                  <span *ngIf="(job.status === 'RUNNING' || job.status === 'PENDING') && job.nativeMemoryUsagePercent !== undefined && job.nativeMaxPhysicalBytes" class="meta-item">
                    <mat-icon inline>developer_board</mat-icon> {{ job.nativeMemoryUsagePercent }}% native
                  </span>
                  <span *ngIf="(job.status === 'RUNNING' || job.status === 'PENDING') && job.processTreeRssBytes" class="meta-item">
                    <mat-icon inline>memory</mat-icon> {{ formatBytes(job.processTreeRssBytes || 0) }} RSS
                  </span>
                  <span *ngIf="(job.status === 'RUNNING' || job.status === 'PENDING') && job.embeddingSubprocessRssBytes !== undefined" class="meta-item">
                    <mat-icon inline>psychology_alt</mat-icon> embed {{ formatBytes(job.embeddingSubprocessRssBytes || 0) }}
                  </span>
                  <span *ngIf="(job.status === 'RUNNING' || job.status === 'PENDING') && job.otherChildProcessRssBytes" class="meta-item">
                    <mat-icon inline>smart_toy</mat-icon> agents {{ formatBytes(job.otherChildProcessRssBytes || 0) }}
                  </span>
                  <span *ngIf="job.errorCount > 0" class="meta-item stat-error" matTooltip="Click job for error details">
                    <mat-icon inline>warning</mat-icon> {{ job.errorCount }} errors
                  </span>
                </div>

                <!-- Pipeline steps strip -->
                <div class="step-strip" *ngIf="job.pipelineSteps && job.pipelineSteps.length > 0">
                  <div *ngFor="let step of getVisiblePipelineSteps(job.pipelineSteps)"
                       class="step-pill"
                       [ngClass]="getStepStatusClass(step.status)">
                    <div class="step-pill-header">
                      <mat-icon>{{ getStepTypeIcon(step.stepType) }}</mat-icon>
                      <span class="step-name">{{ step.displayName }}</span>
                      <span class="step-type" [class]="getStepTypeClass(step.stepType)">{{ step.stepType }}</span>
                    </div>
                    <mat-progress-bar mode="determinate" [value]="step.progressPercent || 0"></mat-progress-bar>
                    <div class="step-pill-meta">
                      <span>{{ step.completedItems || 0 }}/{{ step.totalItems || 0 }}</span>
                      <span *ngIf="step.failedItems">{{ step.failedItems }} failed</span>
                      <span *ngIf="step.totalBatches">Batches {{ step.completedBatches || 0 }}/{{ step.totalBatches }}</span>
                      <span *ngIf="getStepThroughput(step)" class="step-throughput">{{ getStepThroughput(step) }}</span>
                      <span *ngIf="step.elapsedMs">{{ formatElapsed(step.elapsedMs) }}</span>
                    </div>
                  </div>
                </div>
                <mat-progress-bar *ngIf="job.status === 'RUNNING' || job.status === 'PENDING'"
                                  [mode]="job.progressPercent !== undefined && job.progressPercent > 0 ? 'determinate' : 'indeterminate'"
                                  [value]="job.progressPercent || 0"></mat-progress-bar>
              </mat-card-content>
              <mat-card-actions *ngIf="job.status === 'RUNNING' || job.status === 'PENDING'" align="end">
                <button mat-button color="warn" (click)="cancelJob(job.jobId); $event.stopPropagation()">
                  <mat-icon>cancel</mat-icon> Cancel
                </button>
              </mat-card-actions>
            </mat-card>
          </div>
        </mat-tab>

        <!-- Job Detail Tab -->
        <mat-tab label="Job Detail" [disabled]="!selectedJob">
          <div class="tab-content" *ngIf="selectedJob">
            <mat-card>
              <mat-card-header>
                <mat-card-title>
                  {{ selectedJob.name || 'Unnamed Job' }}
                  <span class="status-badge" [class]="'status-' + selectedJob.status.toLowerCase()">
                    {{ selectedJob.status }}
                  </span>
                </mat-card-title>
                <mat-card-subtitle>
                  Job ID: {{ selectedJob.jobId | slice:0:8 }}...
                  <span *ngIf="selectedJob.createdAt" class="elapsed"> &bull; Created {{ formatTimestamp(selectedJob.createdAt) }}</span>
                  <span *ngIf="selectedJob.startedAt" class="elapsed"> &bull; Started {{ formatTimestamp(selectedJob.startedAt) }}</span>
                  <span *ngIf="selectedJob.completedAt" class="elapsed"> &bull; Finished {{ formatTimestamp(selectedJob.completedAt) }}</span>
                </mat-card-subtitle>
              </mat-card-header>
              <mat-card-content>
                <!-- Job Configuration (from original request) -->
                <div *ngIf="selectedJob.requestConfig" class="job-config-panel">
                  <h3><mat-icon inline>settings</mat-icon> Job Configuration</h3>
                  <div class="config-grid">
                    <!-- Sources configured -->
                    <div class="config-section" *ngIf="selectedJob.requestConfig.sources && selectedJob.requestConfig.sources.length > 0">
                      <div class="config-label">Sources ({{ selectedJob.requestConfig.sources.length }})</div>
                      <div *ngFor="let src of selectedJob.requestConfig.sources" class="config-source-row">
                        <mat-icon class="source-icon">{{ getSourceIcon(src.sourceType || '') }}</mat-icon>
                        <strong>{{ src.label || src.pathOrUrl }}</strong>
                        <span class="source-type-tag">{{ src.sourceType }}</span>
                        <span class="elapsed" *ngIf="src.maxDepth">depth {{ src.maxDepth }}</span>
                        <span class="elapsed" *ngIf="src.maxDocuments">max {{ src.maxDocuments }} docs</span>
                      </div>
                    </div>
                    <!-- Graph extraction config -->
                    <div class="config-section" *ngIf="selectedJob.requestConfig.graphExtraction">
                      <div class="config-label">
                        <mat-icon inline>hub</mat-icon> Graph Extraction
                        <span class="config-status-tag" [class.enabled]="selectedJob.requestConfig.graphExtraction.enabled">
                          {{ selectedJob.requestConfig.graphExtraction.enabled ? 'Enabled' : 'Disabled' }}
                        </span>
                      </div>
                      <div class="config-details" *ngIf="selectedJob.requestConfig.graphExtraction.enabled">
                        <span *ngIf="selectedJob.requestConfig.graphExtraction.llmProvider">
                          <strong>LLM:</strong> {{ selectedJob.requestConfig.graphExtraction.llmProvider }}
                          <ng-container *ngIf="selectedJob.requestConfig.graphExtraction.modelName"> / {{ selectedJob.requestConfig.graphExtraction.modelName }}</ng-container>
                        </span>
                        <span *ngIf="selectedJob.requestConfig.graphExtraction.entityTypes && selectedJob.requestConfig.graphExtraction.entityTypes.length > 0">
                          <strong>Entities:</strong> {{ selectedJob.requestConfig.graphExtraction.entityTypes.join(', ') }}
                        </span>
                        <span *ngIf="selectedJob.requestConfig.graphExtraction.relationshipTypes && selectedJob.requestConfig.graphExtraction.relationshipTypes.length > 0">
                          <strong>Relations:</strong> {{ selectedJob.requestConfig.graphExtraction.relationshipTypes.join(', ') }}
                        </span>
                        <span><strong>Schema:</strong> {{ selectedJob.requestConfig.graphExtraction.schemaMode || 'LENIENT' }}</span>
                        <span *ngIf="selectedJob.requestConfig.graphExtraction.schemaPresetId">
                          <strong>Preset:</strong> {{ selectedJob.requestConfig.graphExtraction.schemaPresetId }}
                        </span>
                        <span><strong>Temp:</strong> {{ selectedJob.requestConfig.graphExtraction.temperature }}</span>
                        <span><strong>Entity resolution:</strong> {{ selectedJob.requestConfig.graphExtraction.entityResolution ? 'Yes' : 'No' }}</span>
                        <span><strong>Min confidence:</strong> {{ selectedJob.requestConfig.graphExtraction.minConfidence }}</span>
                      </div>
                    </div>
                    <!-- Vector index config -->
                    <div class="config-section" *ngIf="selectedJob.requestConfig.vectorIndex">
                      <div class="config-label">
                        <mat-icon inline>storage</mat-icon> Vector Index
                        <span class="config-status-tag" [class.enabled]="selectedJob.requestConfig.vectorIndex.enabled">
                          {{ selectedJob.requestConfig.vectorIndex.enabled ? 'Enabled' : 'Disabled' }}
                        </span>
                      </div>
                      <div class="config-details" *ngIf="selectedJob.requestConfig.vectorIndex.enabled">
                        <span *ngIf="selectedJob.requestConfig.vectorIndex.collectionName">
                          <strong>Collection:</strong> {{ selectedJob.requestConfig.vectorIndex.collectionName }}
                        </span>
                        <span *ngIf="selectedJob.requestConfig.vectorIndex.chunkerName">
                          <strong>Chunker:</strong> {{ selectedJob.requestConfig.vectorIndex.chunkerName }}
                        </span>
                        <span *ngIf="selectedJob.requestConfig.vectorIndex.chunkSize">
                          <strong>Chunk size:</strong> {{ selectedJob.requestConfig.vectorIndex.chunkSize }} tokens
                          <ng-container *ngIf="selectedJob.requestConfig.vectorIndex.chunkOverlap"> (overlap {{ selectedJob.requestConfig.vectorIndex.chunkOverlap }})</ng-container>
                        </span>
                        <span *ngIf="selectedJob.requestConfig.vectorIndex.embeddingBatchSize">
                          <strong>Batch:</strong> {{ selectedJob.requestConfig.vectorIndex.embeddingBatchSize }}
                          <ng-container *ngIf="selectedJob.requestConfig.vectorIndex.maxEmbeddingBatchSize"> (max {{ selectedJob.requestConfig.vectorIndex.maxEmbeddingBatchSize }})</ng-container>
                        </span>
                        <span><strong>Adaptive batching:</strong> {{ selectedJob.requestConfig.vectorIndex.adaptiveBatching ? 'Yes' : 'No' }}</span>
                      </div>
                    </div>
                  </div>
                </div>

                <!-- Progress stats -->
                <div class="detail-stats">
                  <div class="stat-card">
                    <div class="stat-value">{{ selectedJob.documentsDiscovered }}</div>
                    <div class="stat-label">Discovered</div>
                  </div>
                  <div class="stat-card">
                    <div class="stat-value">{{ selectedJob.documentsLoaded }}</div>
                    <div class="stat-label">Loaded</div>
                  </div>
                  <div class="stat-card">
                    <div class="stat-value">{{ selectedJob.chunksCreated || selectedJob.chunksProcessed }}</div>
                    <div class="stat-label">Chunks</div>
                  </div>
                  <div class="stat-card">
                    <div class="stat-value">{{ selectedJob.chunksEmbedded || 0 }}<span class="stat-sub" *ngIf="selectedJob.chunksQueuedForEmbedding">/{{ selectedJob.chunksQueuedForEmbedding }}</span></div>
                    <div class="stat-label">Embedded</div>
                  </div>
                  <div class="stat-card">
                    <div class="stat-value">{{ selectedJob.documentsIndexed }}</div>
                    <div class="stat-label">Indexed</div>
                  </div>
                  <div class="stat-card highlight">
                    <div class="stat-value">{{ selectedJob.entitiesExtracted }}</div>
                    <div class="stat-label">Entities</div>
                  </div>
                  <div class="stat-card highlight">
                    <div class="stat-value">{{ selectedJob.relationshipsExtracted }}</div>
                    <div class="stat-label">Relationships</div>
                  </div>
                  <div class="stat-card" [class.error]="selectedJob.errorCount > 0">
                    <div class="stat-value">{{ selectedJob.errorCount }}</div>
                    <div class="stat-label">Errors</div>
                  </div>
                </div>

                <!-- Elapsed time and current activity -->
                <div *ngIf="selectedJob.elapsedMs > 0" class="elapsed-row">
                  <mat-icon inline>timer</mat-icon>
                  Elapsed: {{ formatElapsed(selectedJob.elapsedMs) }}
                </div>
                <div *ngIf="selectedJob.currentFile && selectedJob.status === 'RUNNING'" class="current-file">
                  <mat-icon inline>sync</mat-icon> {{ selectedJob.currentFile }}
                </div>

                <div class="runtime-grid">
                  <div><strong>Phase:</strong> {{ formatPhase(selectedJob.currentPhase) || selectedJob.status }}</div>
                  <div><strong>Progress:</strong> {{ selectedJob.progressPercent || 0 }}%</div>
                  <div *ngIf="selectedJob.queuePosition && selectedJob.queuePosition > 0">
                    <strong>Queue:</strong> #{{ selectedJob.queuePosition }} of {{ selectedJob.queuedJobs }}
                  </div>
                  <div *ngIf="selectedJob.memoryUsagePercent !== undefined">
                    <strong>Heap:</strong> {{ selectedJob.memoryUsagePercent }}% (peak {{ selectedJob.peakMemoryUsagePercent || 0 }}%)
                  </div>
                  <div *ngIf="selectedJob.nativeMemoryUsagePercent !== undefined && selectedJob.nativeMaxPhysicalBytes">
                    <strong>Native:</strong> {{ selectedJob.nativeMemoryUsagePercent }}%
                    ({{ formatBytes(selectedJob.nativePhysicalBytes || 0) }} / {{ formatBytes(selectedJob.nativeMaxPhysicalBytes || 0) }},
                    peak {{ selectedJob.peakNativeMemoryUsagePercent || 0 }}%)
                  </div>
                  <div *ngIf="selectedJob.nativeTotalBytes || selectedJob.directBufferBytes">
                    <strong>Tracked native:</strong> {{ formatBytes(selectedJob.nativeTotalBytes || 0) }}
                    <ng-container *ngIf="selectedJob.directBufferBytes">
                      &bull; Direct {{ formatBytes(selectedJob.directBufferBytes || 0) }}
                    </ng-container>
                  </div>
                  <div *ngIf="selectedJob.processTreeRssBytes">
                    <strong>Process RSS:</strong> {{ formatBytes(selectedJob.processTreeRssBytes || 0) }}
                    <ng-container *ngIf="selectedJob.processRssBytes || selectedJob.childProcessRssBytes">
                      (app {{ formatBytes(selectedJob.processRssBytes || 0) }},
                      embedding subprocess {{ formatBytes(selectedJob.embeddingSubprocessRssBytes || 0) }},
                      other children {{ formatBytes(selectedJob.otherChildProcessRssBytes || selectedJob.childProcessRssBytes || 0) }})
                    </ng-container>
                  </div>
                  <div *ngIf="selectedJob.llmProvider">
                    <strong>LLM:</strong> {{ selectedJob.llmProvider }}
                    <ng-container *ngIf="selectedJob.llmModel"> / {{ selectedJob.llmModel }}</ng-container>
                  </div>
                  <div *ngIf="selectedJob.embeddingBatchSize">
                    <strong>Embedding batch:</strong> {{ selectedJob.embeddingBatchSize }}
                    <ng-container *ngIf="selectedJob.embeddingModelOptimalBatchSize">
                      (model optimal={{ selectedJob.embeddingModelOptimalBatchSize }} max={{ selectedJob.embeddingModelMaxBatchSize }})
                    </ng-container>
                  </div>
                  <div *ngIf="selectedJob.embeddingSingleDspPlan !== undefined">
                    <strong>DSP plan:</strong>
                    {{ selectedJob.embeddingSingleDspPlan ? 'single (batch=' + selectedJob.embeddingDspPlanBatchSize + ')' : 'dynamic' }}
                  </div>
                  <div *ngIf="selectedJob.currentBatchStep">
                    <strong>Batch step:</strong> {{ selectedJob.currentBatchStep }}
                  </div>
                </div>

                <div class="pipeline-steps-panel" *ngIf="selectedJob.pipelineSteps && selectedJob.pipelineSteps.length > 0">
                  <h3>Pipeline Steps</h3>
                  <div class="pipeline-step-grid">
                    <div *ngFor="let step of selectedJob.pipelineSteps"
                         class="pipeline-step"
                         [ngClass]="getStepStatusClass(step.status)">
                      <div class="pipeline-step-top">
                        <mat-icon>{{ getStepTypeIcon(step.stepType) }}</mat-icon>
                        <div class="pipeline-step-title">
                          <strong>{{ step.displayName }}</strong>
                          <span>{{ step.stepType }} &bull; {{ step.status }}</span>
                        </div>
                        <span class="pipeline-step-percent">{{ step.progressPercent || 0 }}%</span>
                      </div>
                      <mat-progress-bar mode="determinate" [value]="step.progressPercent || 0"></mat-progress-bar>
                      <div class="pipeline-step-details">
                        <span>{{ step.completedItems || 0 }}/{{ step.totalItems || 0 }} items</span>
                        <span *ngIf="step.totalBatches">Batches {{ step.completedBatches || 0 }}/{{ step.totalBatches }}</span>
                        <span *ngIf="step.currentBatchSize">Current batch {{ step.currentBatchSize }}</span>
                        <span *ngIf="getStepThroughput(step)" class="step-throughput">{{ getStepThroughput(step) }}</span>
                        <span *ngIf="step.elapsedMs">{{ formatElapsed(step.elapsedMs) }}</span>
                      </div>
                      <div *ngIf="step.message || step.currentItem" class="pipeline-step-message">
                        {{ step.message || step.currentItem }}
                      </div>
                    </div>
                  </div>
                </div>

                <div class="document-stream-panel" *ngIf="selectedJob.documentProgress && selectedJob.documentProgress.length > 0">
                  <h3>
                    Documents ({{ selectedJob.documentProgress.length }})
                    <span class="live-badge" *ngIf="selectedJob.status === 'RUNNING'">LIVE</span>
                  </h3>
                  <!-- Document status summary -->
                  <div class="doc-status-summary">
                    <span *ngIf="countDocsByStatus(selectedJob.documentProgress, 'RUNNING') as cnt" class="doc-count-badge running">
                      <mat-icon inline>sync</mat-icon> {{ cnt }} processing
                    </span>
                    <span *ngIf="countDocsByStatus(selectedJob.documentProgress, 'COMPLETED') as cnt" class="doc-count-badge completed">
                      <mat-icon inline>check_circle</mat-icon> {{ cnt }} completed
                    </span>
                    <span *ngIf="countDocsByStatus(selectedJob.documentProgress, 'LOADED') as cnt" class="doc-count-badge loaded">
                      <mat-icon inline>description</mat-icon> {{ cnt }} loaded
                    </span>
                    <span *ngIf="countDocsByStatus(selectedJob.documentProgress, 'FAILED') as cnt" class="doc-count-badge failed">
                      <mat-icon inline>error</mat-icon> {{ cnt }} failed
                    </span>
                    <span *ngIf="countDocsByStatus(selectedJob.documentProgress, 'SKIPPED') as cnt" class="doc-count-badge skipped">
                      <mat-icon inline>block</mat-icon> {{ cnt }} skipped
                    </span>
                    <span class="doc-count-total">{{ selectedJob.documentProgress.length }} total documents</span>
                  </div>
                  <!-- Status filter and pagination controls -->
                  <div class="doc-controls">
                    <select [(ngModel)]="docStatusFilter" (ngModelChange)="onDocStatusFilterChange()" class="doc-filter-select">
                      <option value="">All statuses</option>
                      <option value="RUNNING">Running</option>
                      <option value="LOADED">Loaded</option>
                      <option value="COMPLETED">Completed</option>
                      <option value="FAILED">Failed</option>
                      <option value="SKIPPED">Skipped</option>
                    </select>
                    <span class="doc-page-info">
                      Page {{ docPageIndex + 1 }} of {{ docTotalPages || 1 }}
                      ({{ getFilteredDocumentCount(selectedJob.documentProgress) }} docs)
                    </span>
                    <button mat-icon-button (click)="docPrevPage()" [disabled]="docPageIndex === 0" class="doc-page-btn">
                      <mat-icon>chevron_left</mat-icon>
                    </button>
                    <button mat-icon-button (click)="docNextPage()" [disabled]="docPageIndex >= docTotalPages - 1" class="doc-page-btn">
                      <mat-icon>chevron_right</mat-icon>
                    </button>
                  </div>
                  <div class="document-stream-header">
                    <span>Document</span>
                    <span>Type</span>
                    <span>Phase</span>
                    <span>Status</span>
                    <span>Chunks</span>
                    <span>Vector</span>
                    <span>Entities</span>
                    <span>Rels</span>
                    <span>Updated</span>
                  </div>
                  <div *ngFor="let doc of getVisibleDocumentProgress(selectedJob.documentProgress)"
                       class="document-stream-row"
                       [ngClass]="getDocumentStatusClass(doc.status)">
                    <div class="doc-main">
                      <strong [title]="doc.sourcePath || doc.documentKey">{{ doc.fileName || doc.documentKey }}</strong>
                      <span *ngIf="doc.loaderName" class="doc-loader">via {{ doc.loaderName }}</span>
                      <span *ngIf="doc.extractors && doc.extractors.length > 0" class="doc-extractors">
                        {{ doc.extractors.join(' &rarr; ') }}
                      </span>
                      <span *ngIf="doc.message" class="doc-message">{{ doc.message }}</span>
                      <span *ngIf="doc.errorMessage" class="doc-error">{{ doc.errorMessage }}</span>
                    </div>
                    <span class="doc-content-type" [title]="doc.contentType || ''">{{ doc.contentType || '-' }}</span>
                    <span class="phase-chip compact">{{ formatPhase(doc.phase) || 'Document' }}</span>
                    <span class="status-badge small" [class]="'status-' + (doc.status || 'unknown').toLowerCase()">
                      {{ doc.status || 'UNKNOWN' }}
                    </span>
                    <span>{{ doc.chunksCreated || 0 }}</span>
                    <span>{{ doc.chunksEmbedded || 0 }}/{{ doc.chunksIndexed || 0 }}</span>
                    <span>{{ doc.entitiesExtracted || 0 }}</span>
                    <span>{{ doc.relationshipsExtracted || 0 }}</span>
                    <span class="doc-updated">{{ formatTimestamp(doc.updatedAt || doc.completedAt || doc.startedAt) || '-' }}</span>
                  </div>
                </div>

                <mat-progress-bar *ngIf="selectedJob.status === 'RUNNING' || selectedJob.status === 'PENDING'"
                                  mode="determinate"
                                  [value]="selectedJob.progressPercent || 0"></mat-progress-bar>

                <!-- Per-source progress -->
                <h3>Source Progress ({{ selectedJob.sources.length }})</h3>
                <div *ngFor="let sp of selectedJob.sources" class="source-progress"
                     [class.source-running]="sp.status === 'RUNNING'"
                     [class.source-completed]="sp.status === 'COMPLETED'"
                     [class.source-failed]="sp.status === 'FAILED'">
                  <div class="source-progress-header">
                    <mat-icon class="source-icon">{{ getSourceIcon(sp.sourceType) }}</mat-icon>
                    <strong>{{ sp.label }}</strong>
                    <span class="source-type-tag">{{ sp.sourceType }}</span>
                    <span class="status-badge small" [class]="'status-' + sp.status.toLowerCase()">
                      {{ sp.status }}
                    </span>
                    <span *ngIf="sp.currentPhase" class="phase-chip compact">{{ formatPhase(sp.currentPhase) }}</span>
                  </div>
                  <div *ngIf="sp.pathOrUrl" class="source-path" [title]="sp.pathOrUrl">
                    <mat-icon inline style="font-size:13px;vertical-align:middle">link</mat-icon>
                    {{ sp.pathOrUrl.length > 60 ? (sp.pathOrUrl | slice:0:57) + '...' : sp.pathOrUrl }}
                  </div>
                  <div class="source-counters">
                    <span><strong>{{ sp.documentsDiscovered }}</strong> discovered</span>
                    <span><strong>{{ sp.documentsLoaded }}</strong> loaded</span>
                    <span><strong>{{ sp.chunksCreated || 0 }}</strong> chunks</span>
                    <span><strong>{{ sp.entitiesExtracted }}</strong> entities</span>
                    <span><strong>{{ sp.relationshipsExtracted }}</strong> rels</span>
                  </div>
                  <mat-progress-bar *ngIf="sp.status === 'RUNNING' && sp.documentsDiscovered > 0"
                    mode="determinate"
                    [value]="(sp.documentsLoaded / sp.documentsDiscovered) * 100"></mat-progress-bar>
                  <div *ngIf="sp.currentItem" class="source-current-item">
                    <mat-icon inline class="spinning-icon" style="font-size:14px">sync</mat-icon> {{ sp.currentItem }}
                  </div>
                  <div *ngIf="sp.errorMessage" class="source-error">{{ sp.errorMessage }}</div>
                </div>

                <!-- Discovery feed (detail view) -->
                <div class="discovery-feed detail-discovery" *ngIf="selectedJob.recentlyDiscoveredItems && selectedJob.recentlyDiscoveredItems.length > 0 && (selectedJob.status === 'RUNNING' || selectedJob.status === 'PENDING')">
                  <div class="discovery-feed-header">
                    <mat-icon inline>explore</mat-icon>
                    <span>Recently Discovered Files/URLs ({{ selectedJob.documentsDiscovered || 0 }} total found)</span>
                  </div>
                  <div class="discovery-feed-items detail-items">
                    <div *ngFor="let item of selectedJob.recentlyDiscoveredItems | slice:-20" class="discovery-item">
                      <mat-icon inline class="discovery-icon">{{ getSourceIcon(item.sourceType) }}</mat-icon>
                      <span class="discovery-name" [title]="item.name">{{ item.name }}</span>
                      <span class="discovery-source">{{ item.sourceLabel }}</span>
                      <span class="discovery-time">{{ formatTimestamp(item.discoveredAt) }}</span>
                    </div>
                  </div>
                </div>

                <div class="batch-summary" *ngIf="selectedJob.vectorBatchesTotal || selectedJob.graphChunksTotal">
                  <h3>Pipeline Batches</h3>
                  <div class="batch-row" *ngIf="selectedJob.graphChunksTotal">
                    <span>Graph extraction</span>
                    <span>{{ selectedJob.graphChunksProcessed || 0 }}/{{ selectedJob.graphChunksTotal }} chunks</span>
                  </div>
                  <div class="batch-row" *ngIf="selectedJob.vectorBatchesTotal">
                    <span>Embedding/indexing</span>
                    <span>{{ selectedJob.vectorBatchesCompleted || 0 }}/{{ selectedJob.vectorBatchesTotal }} batches, {{ selectedJob.chunksEmbedded || 0 }}/{{ selectedJob.chunksQueuedForEmbedding || 0 }} chunks</span>
                  </div>
                </div>

                <div class="recent-events" *ngIf="selectedJob.recentEvents && selectedJob.recentEvents.length > 0">
                  <h3>Recent Progress</h3>
                  <div *ngFor="let event of selectedJob.recentEvents.slice(-12)" class="event-row" [class.warn]="event.level === 'WARN'" [class.error]="event.level === 'ERROR'">
                    <span *ngIf="event.timestamp" class="event-time">{{ formatEventTime(event.timestamp) }}</span>
                    <span class="event-phase">{{ event.phase }}</span>
                    <span class="event-message">{{ event.message }}</span>
                    <span *ngIf="event.details" class="event-details">{{ event.details }}</span>
                  </div>
                </div>

                <div class="live-logs-panel">
                  <h3>Live Logs</h3>
                  <app-job-log-viewer
                    [taskId]="getCrawlHistoryTaskId(selectedJob.jobId)"
                    [isJobRunning]="isCrawlJobRunning(selectedJob.status)"
                    logSource="ingest"
                    [maxTailLogs]="250">
                  </app-job-log-viewer>
                </div>

                <!-- Graph summary (live during processing, final on completion) -->
                <div *ngIf="selectedJob.graph" class="graph-summary">
                  <h3>
                    Graph Summary
                    <span *ngIf="selectedJob.graph.live" class="live-badge">LIVE</span>
                  </h3>

                  <!-- Stats grid for graph -->
                  <div class="detail-stats">
                    <div class="stat-card highlight">
                      <div class="stat-value">{{ selectedJob.graph.entityCount }}</div>
                      <div class="stat-label">Entities (JPA)</div>
                    </div>
                    <div class="stat-card highlight">
                      <div class="stat-value">{{ selectedJob.graph.relationshipCount }}</div>
                      <div class="stat-label">Edges (JPA)</div>
                    </div>
                    <div *ngIf="selectedJob.graph.documentCount" class="stat-card">
                      <div class="stat-value">{{ selectedJob.graph.documentCount }}</div>
                      <div class="stat-label">Documents</div>
                    </div>
                    <div *ngIf="selectedJob.graph.snippetCount" class="stat-card">
                      <div class="stat-value">{{ selectedJob.graph.snippetCount }}</div>
                      <div class="stat-label">Snippets</div>
                    </div>
                    <div *ngIf="selectedJob.graph.tableCount" class="stat-card">
                      <div class="stat-value">{{ selectedJob.graph.tableCount }}</div>
                      <div class="stat-label">Tables</div>
                    </div>
                  </div>

                  <div *ngIf="selectedJob.graph.entityTypeCounts" class="type-breakdown">
                    <h4>Entity Types</h4>
                    <mat-chip-listbox>
                      <mat-chip *ngFor="let entry of objectEntries(selectedJob.graph.entityTypeCounts)">
                        {{ entry[0] }}: {{ entry[1] }}
                      </mat-chip>
                    </mat-chip-listbox>
                  </div>

                  <div *ngIf="selectedJob.graph.edgeTypeCounts" class="type-breakdown">
                    <h4>Edge Types</h4>
                    <mat-chip-listbox>
                      <mat-chip *ngFor="let entry of objectEntries(selectedJob.graph.edgeTypeCounts)">
                        {{ entry[0] }}: {{ entry[1] }}
                      </mat-chip>
                    </mat-chip-listbox>
                  </div>

                  <div *ngIf="selectedJob.graph.relationshipTypeCounts" class="type-breakdown">
                    <h4>Relationship Types</h4>
                    <mat-chip-listbox>
                      <mat-chip *ngFor="let entry of objectEntries(selectedJob.graph.relationshipTypeCounts)">
                        {{ entry[0] }}: {{ entry[1] }}
                      </mat-chip>
                    </mat-chip-listbox>
                  </div>

                  <!-- Top entities list -->
                  <div *ngIf="selectedJob.graph.topEntities && selectedJob.graph.topEntities.length > 0" class="top-entities">
                    <h4>Top Entities</h4>
                    <div *ngFor="let entity of selectedJob.graph.topEntities" class="top-entity-row">
                      <span class="entity-name">{{ entity.name }}</span>
                      <span class="entity-type-tag">{{ entity.type }}</span>
                      <span class="entity-count" [title]="entity.connectionCount + ' connections'">{{ entity.connectionCount }} conn</span>
                    </div>
                  </div>

                  <!-- Browse entities button -->
                  <div class="graph-actions">
                    <button mat-raised-button color="accent" (click)="navigateToKnowledgeGraph()">
                      <mat-icon>hub</mat-icon> Browse Extracted Entities &rarr;
                    </button>
                  </div>
                </div>

                <!-- Errors & Warnings Detail -->
                <div *ngIf="selectedJob.errorCount > 0 || (selectedJob.errors && selectedJob.errors.length > 0) || getErrorDocuments(selectedJob.documentProgress).length > 0" class="errors-detail-panel">
                  <h3>
                    <mat-icon color="warn">warning</mat-icon>
                    Errors & Warnings ({{ selectedJob.errorCount || 0 }})
                  </h3>

                  <!-- Top-level error banner -->
                  <div *ngIf="selectedJob.errorMessage" class="error-banner">
                    <mat-icon>error</mat-icon> {{ selectedJob.errorMessage }}
                  </div>

                  <!-- Per-file error strings from errors[] -->
                  <div *ngIf="selectedJob.errors && selectedJob.errors.length > 0" class="error-list-section">
                    <h4>Processing Errors</h4>
                    <div *ngFor="let err of selectedJob.errors" class="error-item">
                      <mat-icon inline class="error-item-icon">report_problem</mat-icon>
                      {{ err }}
                    </div>
                  </div>

                  <!-- Documents with errors -->
                  <div *ngIf="getErrorDocuments(selectedJob.documentProgress).length > 0" class="error-list-section">
                    <h4>Failed Documents ({{ getErrorDocuments(selectedJob.documentProgress).length }})</h4>
                    <div *ngFor="let doc of getErrorDocuments(selectedJob.documentProgress)" class="error-doc-row">
                      <div class="error-doc-header">
                        <mat-icon inline class="error-doc-icon">{{ doc.status === 'FAILED' ? 'cancel' : 'warning' }}</mat-icon>
                        <strong>{{ doc.fileName || doc.documentKey }}</strong>
                        <span class="error-doc-type" *ngIf="doc.contentType">{{ doc.contentType }}</span>
                        <span class="error-doc-phase" *ngIf="doc.phase">{{ formatPhase(doc.phase) }}</span>
                      </div>
                      <div class="error-doc-message" *ngIf="doc.errorMessage">{{ doc.errorMessage }}</div>
                      <div class="error-doc-path" *ngIf="doc.sourcePath">{{ doc.sourcePath }}</div>
                    </div>
                  </div>

                  <!-- WARN/ERROR events from recentEvents -->
                  <div *ngIf="selectedJob.recentEvents && getWarnErrorEvents(selectedJob.recentEvents).length > 0" class="error-list-section">
                    <h4>Warning Events</h4>
                    <div *ngFor="let event of getWarnErrorEvents(selectedJob.recentEvents)" class="event-row" [class.warn]="event.level === 'WARN'" [class.error]="event.level === 'ERROR'">
                      <span class="event-level-badge" [class]="'level-' + event.level.toLowerCase()">{{ event.level }}</span>
                      <span class="event-phase">{{ event.phase }}</span>
                      <span class="event-message">{{ event.message }}</span>
                      <span *ngIf="event.details" class="event-details">{{ event.details }}</span>
                    </div>
                  </div>
                </div>

                <!-- Subprocess Activity -->
                <div class="subprocess-panel" *ngIf="subprocessEvents.length > 0 || subprocessStats?.available">
                  <h3>
                    <mat-icon inline>developer_board</mat-icon>
                    Subprocess Activity
                    <span *ngIf="subprocessStats && subprocessStats.available" class="subprocess-stats-inline">
                      <span *ngIf="subprocessStats.totalCrashes > 0" class="sp-stat sp-stat-crash">{{ subprocessStats.totalCrashes }} crashes</span>
                      <span *ngIf="subprocessStats.totalRestartAttempts > 0" class="sp-stat sp-stat-restart">{{ subprocessStats.totalRestartAttempts }} restarts ({{ subprocessStats.restartSuccessRate | number:'1.0-0' }}% success)</span>
                      <span *ngIf="subprocessStats.modelsLoaded > 0" class="sp-stat sp-stat-model">{{ subprocessStats.modelsLoaded }} models loaded</span>
                    </span>
                  </h3>

                  <div *ngIf="subprocessEvents.length === 0 && subprocessStats?.available" class="subprocess-empty">
                    No subprocess events for this job.
                  </div>

                  <div *ngIf="subprocessEvents.length > 0" class="subprocess-event-list">
                    <div *ngFor="let ev of subprocessEvents" class="subprocess-event-row" [ngClass]="getSubprocessEventClass(ev.eventType)">
                      <mat-icon class="sp-event-icon">{{ getSubprocessEventIcon(ev.eventType) }}</mat-icon>
                      <div class="sp-event-body">
                        <div class="sp-event-header">
                          <span class="sp-event-type">{{ formatEventType(ev.eventType) }}</span>
                          <span class="sp-event-model" *ngIf="ev.modelId">{{ ev.modelId }}</span>
                          <span class="sp-event-time">{{ formatTimestamp(ev.timestamp) }}</span>
                        </div>
                        <div *ngIf="ev.failureReason || ev.errorMessage" class="sp-event-detail">
                          {{ ev.failureReason || ev.errorMessage }}
                        </div>
                        <div class="sp-event-meta" *ngIf="ev.exitCode !== undefined || ev.heapBytes || ev.embeddingDimensions || ev.restartAttemptNumber">
                          <span *ngIf="ev.exitCode !== undefined">exit={{ ev.exitCode }}</span>
                          <span *ngIf="ev.heapBytes">heap={{ formatBytes(ev.heapBytes) }}</span>
                          <span *ngIf="ev.batchSize">batch={{ ev.batchSize }}</span>
                          <span *ngIf="ev.embeddingDimensions">dims={{ ev.embeddingDimensions }}</span>
                          <span *ngIf="ev.encoderType">encoder={{ ev.encoderType }}</span>
                          <span *ngIf="ev.restartAttemptNumber">attempt {{ ev.restartAttemptNumber }}/{{ ev.maxRestartAttempts }}</span>
                          <span *ngIf="ev.backoffMs">backoff={{ ev.backoffMs }}ms</span>
                        </div>
                      </div>
                    </div>
                  </div>
                </div>
              </mat-card-content>
            </mat-card>
          </div>
        </mat-tab>
      </mat-tab-group>
    </div>
  `,
  styles: [`
    .unified-crawl-container { padding: 16px; max-width: 900px; }
    .scheduler-notifications { margin-bottom: 12px; }
    .scheduler-notification {
      display: flex; align-items: center; gap: 8px;
      padding: 8px 12px; margin-bottom: 4px; border-radius: 6px;
      font-size: 13px; border-left: 4px solid #ccc;
      background: #f9fafb;
    }
    .notification-blocked { border-left-color: #f59e0b; background: #fffbeb; }
    .notification-skipped { border-left-color: #3b82f6; background: #eff6ff; }
    .notification-reordered { border-left-color: #8b5cf6; background: #f5f3ff; }
    .notification-dispatched { border-left-color: #10b981; background: #ecfdf5; }
    .notification-completed { border-left-color: #059669; background: #ecfdf5; }
    .notification-failed { border-left-color: #ef4444; background: #fef2f2; }
    .notif-icon { font-size: 18px; width: 18px; height: 18px; }
    .notification-blocked .notif-icon { color: #f59e0b; }
    .notification-skipped .notif-icon { color: #3b82f6; }
    .notification-reordered .notif-icon { color: #8b5cf6; }
    .notification-dispatched .notif-icon { color: #10b981; }
    .notification-completed .notif-icon { color: #059669; }
    .notification-failed .notif-icon { color: #ef4444; }
    .notif-text { flex: 1; }
    .notif-meta { font-size: 11px; color: #6b7280; white-space: nowrap; }
    .notif-dismiss { width: 24px; height: 24px; line-height: 24px; }
    .notif-dismiss mat-icon { font-size: 16px; width: 16px; height: 16px; }
    .tab-content { padding: 16px 0; }
    .full-width { width: 100%; }
    .target-sheet {
      display: inline-flex;
      align-items: center;
      gap: 6px;
      margin: 0 0 12px;
      padding: 6px 10px;
      border-radius: 6px;
      background: #eef2ff;
      color: #3730a3;
      font-size: 13px;
      font-weight: 500;
    }
    .target-sheet mat-icon { font-size: 18px; width: 18px; height: 18px; }
    .form-row { display: flex; gap: 16px; }
    .form-row mat-form-field { flex: 1; }
    .section-divider { margin: 24px 0; }
    .settings-section { padding: 8px 0 16px; }
    .fallback-toggle { display: block; margin-top: 12px; }
    .backends-section { padding: 12px 0; }
    .backends-section .hint-text { font-size: 12px; color: #6b7280; margin: 0 0 12px; }
    .backend-row {
      display: flex; gap: 8px; flex-wrap: wrap; align-items: center;
      padding: 8px; margin-bottom: 8px;
      border: 1px solid #e5e7eb; border-radius: 8px; background: #f9fafb;
    }
    .backend-row mat-form-field { flex: 1; min-width: 120px; }
    .source-card { margin-bottom: 8px; }
    .source-icon { margin-right: 8px; font-size: 20px; }
    .source-form { padding: 8px 0; }
    .empty-state { text-align: center; padding: 40px; color: #999; }
    .empty-state mat-icon { font-size: 48px; width: 48px; height: 48px; }

    .jobs-toolbar { display: flex; gap: 8px; margin-bottom: 16px; }
    .job-card { margin-bottom: 12px; cursor: pointer; }
    .job-card:hover { box-shadow: 0 4px 8px rgba(0,0,0,0.15); }
    .job-stats { display: flex; gap: 16px; margin: 8px 0; }
    .job-stats span { display: flex; align-items: center; gap: 4px; font-size: 13px; }
    .job-pipeline-status {
      display: flex; flex-wrap: wrap; gap: 8px; align-items: center;
      font-size: 12px; color: #555; margin: 6px 0;
    }
    .phase-chip {
      background: #eef2ff; color: #3730a3; border-radius: 10px;
      padding: 2px 8px; font-weight: 600;
    }
    .step-strip {
      display: grid;
      grid-template-columns: repeat(auto-fit, minmax(170px, 1fr));
      gap: 8px;
      margin: 10px 0;
    }
    .step-pill {
      border: 1px solid #e5e7eb;
      border-radius: 6px;
      padding: 8px;
      background: #fff;
      min-width: 0;
    }
    .step-pill.step-running { border-color: #90caf9; background: #f8fbff; }
    .step-pill.step-completed { border-color: #a5d6a7; background: #f8fff8; }
    .step-pill.step-failed { border-color: #ef9a9a; background: #fff8f8; }
    .step-pill.step-backpressure { border-color: #ffcc80; background: #fffaf2; }
    .step-pill-header {
      display: grid;
      grid-template-columns: 20px minmax(0, 1fr) auto;
      gap: 6px;
      align-items: center;
      font-size: 12px;
      margin-bottom: 6px;
    }
    .step-pill-header mat-icon { font-size: 17px; width: 17px; height: 17px; }
    .step-name { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; font-weight: 600; }
    .step-type {
      font-size: 10px;
      padding: 1px 5px;
      border-radius: 8px;
      background: #eef2f7;
      color: #4b5563;
    }
    .step-type.type-llm, .step-type.type-graph-constructor { background: #f3e8ff; color: #6b21a8; }
    .step-type.type-embedding { background: #dcfce7; color: #166534; }
    .step-type.type-graph { background: #e0f2fe; color: #075985; }
    .step-type.type-io { background: #fef3c7; color: #92400e; }
    .step-type.type-cpu { background: #e5e7eb; color: #374151; }
    .step-type.type-pipeline { background: #f0f4f8; color: #4b5563; }
    .step-pill-meta {
      display: flex;
      justify-content: space-between;
      gap: 8px;
      font-size: 11px;
      color: #667085;
      margin-top: 5px;
    }

    .tab-badge {
      background: #1976d2; color: white; border-radius: 12px;
      padding: 2px 8px; font-size: 11px; margin-left: 6px;
    }

    .status-badge {
      display: inline-block; padding: 2px 10px; border-radius: 12px;
      font-size: 12px; font-weight: 500; margin-right: 8px;
    }
    .status-badge.small { padding: 1px 6px; font-size: 11px; }
    .status-running { background: #e3f2fd; color: #1565c0; }
    .status-completed { background: #e8f5e9; color: #2e7d32; }
    .status-failed { background: #ffebee; color: #c62828; }
    .status-cancelled { background: #fff3e0; color: #e65100; }
    .status-pending { background: #f3e5f5; color: #6a1b9a; }
    .status-loaded, .status-skipped, .status-unknown { background: #f5f5f5; color: #555; }

    .detail-stats {
      display: flex; flex-wrap: wrap; gap: 12px; margin: 16px 0;
    }
    .stat-card {
      background: #f5f5f5; border-radius: 8px; padding: 12px 16px;
      text-align: center; min-width: 80px;
    }
    .stat-card.highlight { background: #e3f2fd; }
    .stat-card.error { background: #ffebee; }
    .stat-value { font-size: 24px; font-weight: 700; }
    .stat-label { font-size: 12px; color: #666; margin-top: 4px; }

    .job-config-panel {
      background: #f8fafc; border: 1px solid #e5e7eb; border-radius: 8px;
      padding: 12px 16px; margin-bottom: 16px;
    }
    .job-config-panel h3 {
      margin: 0 0 10px; font-size: 14px; display: flex; align-items: center; gap: 6px;
    }
    .config-grid { display: flex; flex-direction: column; gap: 12px; }
    .config-section { border-left: 3px solid #e5e7eb; padding-left: 12px; }
    .config-label {
      font-size: 13px; font-weight: 600; color: #374151;
      display: flex; align-items: center; gap: 6px; margin-bottom: 4px;
    }
    .config-status-tag {
      font-size: 10px; padding: 1px 6px; border-radius: 8px;
      background: #fee2e2; color: #991b1b;
    }
    .config-status-tag.enabled { background: #dcfce7; color: #166534; }
    .config-details {
      display: flex; flex-wrap: wrap; gap: 6px 14px;
      font-size: 12px; color: #555;
    }
    .config-source-row {
      display: flex; align-items: center; gap: 6px;
      font-size: 12px; padding: 2px 0;
    }
    .stat-sub { font-size: 14px; font-weight: 400; color: #667085; }
    .source-progress {
      padding: 10px 0; border-bottom: 1px solid #eee;
      border-left: 3px solid transparent; padding-left: 8px;
    }
    .source-running { border-left-color: #1976d2; }
    .source-completed { border-left-color: #2e7d32; }
    .source-failed { border-left-color: #c62828; }
    .source-progress-header { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
    .source-counters {
      display: flex; gap: 12px; font-size: 12px; color: #555; margin: 4px 0 4px 32px;
    }
    .source-current-item {
      font-size: 12px; color: #555; margin-left: 32px; margin-top: 4px;
      display: flex; align-items: center; gap: 4px;
      font-style: italic; overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
    }
    .source-error { color: #c62828; font-size: 13px; margin-left: 32px; }
    .doc-status-summary {
      display: flex; flex-wrap: wrap; gap: 8px; align-items: center;
      margin-bottom: 8px; font-size: 12px;
    }
    .doc-count-badge {
      display: inline-flex; align-items: center; gap: 3px;
      padding: 2px 8px; border-radius: 10px; font-weight: 500;
    }
    .doc-count-badge.running { background: #e3f2fd; color: #1565c0; }
    .doc-count-badge.completed { background: #e8f5e9; color: #2e7d32; }
    .doc-count-badge.loaded { background: #f3e5f5; color: #6a1b9a; }
    .doc-count-badge.failed { background: #ffebee; color: #c62828; }
    .doc-count-badge.skipped { background: #f5f5f5; color: #666; }
    .doc-count-total { color: #888; margin-left: auto; }
    .doc-content-type {
      font-size: 11px; color: #667085; overflow: hidden;
      text-overflow: ellipsis; white-space: nowrap;
    }
    .doc-loader { font-size: 11px; color: #888; }
    .doc-truncation-note {
      font-size: 12px; color: #888; text-align: center; padding: 8px;
      border-top: 1px solid #edf0f2;
    }

    .current-file {
      font-size: 12px; color: #555; padding: 4px 0;
      display: flex; align-items: center; gap: 4px;
      font-style: italic; overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
    }
    .elapsed { font-size: 12px; color: #888; }
    .elapsed-row { font-size: 13px; color: #555; display: flex; align-items: center; gap: 4px; margin: 4px 0; }
    .stat-error { color: #c62828; }
    .runtime-grid {
      display: grid; grid-template-columns: repeat(auto-fit, minmax(220px, 1fr));
      gap: 8px 16px; margin: 12px 0; font-size: 13px; color: #444;
    }
    .pipeline-steps-panel, .document-stream-panel, .live-logs-panel { margin-top: 18px; }
    .pipeline-step-grid {
      display: grid;
      grid-template-columns: repeat(auto-fit, minmax(260px, 1fr));
      gap: 10px;
      margin: 8px 0 12px;
    }
    .pipeline-step {
      border: 1px solid #e5e7eb;
      border-radius: 6px;
      padding: 10px;
      background: #fff;
      min-width: 0;
    }
    .pipeline-step.step-running { border-left: 4px solid #1976d2; }
    .pipeline-step.step-completed { border-left: 4px solid #2e7d32; }
    .pipeline-step.step-failed { border-left: 4px solid #c62828; }
    .pipeline-step.step-skipped { opacity: 0.72; }
    .pipeline-step.step-backpressure { border-left: 4px solid #f57c00; }
    .pipeline-step-top {
      display: grid;
      grid-template-columns: 24px minmax(0, 1fr) auto;
      gap: 8px;
      align-items: center;
      margin-bottom: 8px;
    }
    .pipeline-step-top mat-icon { font-size: 20px; width: 20px; height: 20px; }
    .pipeline-step-title { min-width: 0; }
    .pipeline-step-title strong {
      display: block;
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
      font-size: 13px;
    }
    .pipeline-step-title span { display: block; color: #667085; font-size: 11px; }
    .pipeline-step-percent { font-weight: 700; color: #1f2937; font-size: 12px; }
    .pipeline-step-details {
      display: flex;
      flex-wrap: wrap;
      gap: 8px;
      margin-top: 6px;
      color: #667085;
      font-size: 11px;
    }
    .pipeline-step-message {
      margin-top: 6px;
      color: #374151;
      font-size: 12px;
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
    }
    .document-stream-header, .document-stream-row {
      display: grid;
      grid-template-columns: minmax(220px, 1fr) 100px 110px 90px 56px 62px 64px 50px 76px;
      gap: 8px;
      align-items: center;
      padding: 8px 10px;
      border-bottom: 1px solid #edf0f2;
      font-size: 12px;
    }
    .document-stream-header {
      background: #f8fafc;
      color: #667085;
      font-weight: 700;
      text-transform: uppercase;
      font-size: 10px;
      letter-spacing: 0.3px;
    }
    .document-stream-row {
      background: #fff;
    }
    .document-stream-row.doc-running { border-left: 4px solid #1976d2; }
    .document-stream-row.doc-completed { border-left: 4px solid #2e7d32; }
    .document-stream-row.doc-failed { border-left: 4px solid #c62828; }
    .document-stream-row.doc-skipped { border-left: 4px solid #9e9e9e; }
    .doc-updated { color: #667085; font-size: 11px; white-space: nowrap; }
    .doc-main {
      display: flex;
      flex-direction: column;
      gap: 2px;
      min-width: 0;
    }
    .doc-main strong, .doc-main span {
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
    }
    .doc-main strong { color: #1f2937; }
    .doc-main span { color: #667085; }
    .doc-extractors { font-family: monospace; font-size: 11px; }
    .doc-message { color: #374151 !important; }
    .doc-error { color: #c62828 !important; }
    .phase-chip.compact {
      display: inline-block;
      text-align: center;
      padding: 2px 6px;
      font-size: 10px;
    }
    .batch-summary, .recent-events { margin-top: 16px; }
    .batch-row, .event-row {
      display: flex; justify-content: space-between; gap: 12px;
      padding: 6px 0; border-bottom: 1px solid #f0f0f0; font-size: 13px;
    }
    .event-row { justify-content: flex-start; align-items: baseline; }
    .event-row.warn { color: #9a6700; }
    .event-row.error { color: #c62828; }
    .event-phase {
      min-width: 120px; font-family: monospace; font-size: 12px; color: #555;
    }
    .event-message { font-weight: 500; }
    .event-details { color: #777; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }

    .graph-summary { margin-top: 16px; }
    .type-breakdown { margin-top: 8px; }

    .top-entities { margin-top: 12px; }
    .top-entities h4 { margin: 8px 0 6px; font-size: 13px; font-weight: 600; color: #444; }
    .top-entity-row {
      display: flex; align-items: center; gap: 8px;
      padding: 4px 0; border-bottom: 1px solid #f0f0f0; font-size: 13px;
    }
    .top-entity-row:last-child { border-bottom: none; }
    .entity-name { flex: 1; font-weight: 500; }
    .entity-type-tag {
      background: #e3f2fd; color: #1565c0; border-radius: 10px;
      padding: 1px 8px; font-size: 11px; font-weight: 500;
    }
    .entity-count { color: #888; font-size: 12px; min-width: 32px; text-align: right; }

    .graph-actions { margin-top: 14px; }

    .source-type-tag {
      font-size: 11px; color: #666; background: #f5f5f5;
      border-radius: 8px; padding: 1px 6px;
    }
    .source-path {
      font-size: 12px; color: #555; margin: 2px 0 2px 32px;
      font-family: monospace; overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
    }

    .error-banner {
      background: #ffebee; color: #c62828; padding: 12px;
      border-radius: 8px; margin-top: 16px; display: flex;
      align-items: center; gap: 8px;
    }
    .current-activity {
      display: flex; flex-wrap: wrap; align-items: center; gap: 8px;
      padding: 8px 12px; margin-bottom: 8px;
      background: #e8f5e9; border-radius: 8px; border-left: 3px solid #4caf50;
      font-size: 13px;
    }
    .pending-activity {
      background: #f3e5f5; border-left-color: #6a1b9a;
    }
    .activity-icon { font-size: 18px; width: 18px; height: 18px; }
    .spinning-icon { animation: spin 1.5s linear infinite; }
    @keyframes spin { from { transform: rotate(0deg); } to { transform: rotate(360deg); } }
    .activity-detail { color: #555; }
    .job-type-badges { display: inline-flex; gap: 6px; margin-right: 8px; vertical-align: middle; }
    .job-type-badge {
      display: inline-flex; align-items: center; gap: 3px;
      font-size: 11px; font-weight: 600; padding: 1px 7px;
      border-radius: 10px;
    }
    .graph-badge { background: #ede9fe; color: #6b21a8; }
    .vector-badge { background: #dcfce7; color: #166534; }
    .crawl-only-badge { background: #e0f2fe; color: #075985; }
    .job-pipeline-counters {
      display: flex; align-items: center; gap: 4px;
      margin: 10px 0; padding: 10px 12px;
      background: #f8fafc; border-radius: 8px; border: 1px solid #e5e7eb;
      overflow-x: auto;
    }
    .pipeline-counter {
      text-align: center; min-width: 56px; padding: 4px 8px;
      border-radius: 6px; transition: background 0.2s;
    }
    .pipeline-counter.active { background: #e3f2fd; }
    .counter-value { font-size: 18px; font-weight: 700; color: #1f2937; line-height: 1.2; }
    .counter-sub { font-size: 11px; font-weight: 400; color: #667085; }
    .counter-label {
      font-size: 11px; color: #667085; white-space: nowrap;
      display: flex; align-items: center; justify-content: center; gap: 3px;
    }
    .counter-arrow { font-size: 16px; color: #9ca3af; flex-shrink: 0; }
    .job-graph-stats {
      display: flex; align-items: center; gap: 6px;
      font-size: 12px; color: #6b21a8; margin: 4px 0;
    }
    .job-meta-row {
      display: flex; flex-wrap: wrap; gap: 12px; font-size: 12px; color: #666; margin: 6px 0;
    }
    .meta-item { display: flex; align-items: center; gap: 4px; }
    .unavailable { color: #999; font-size: 12px; }

    /* Errors detail panel */
    .errors-detail-panel { margin-top: 18px; }
    .errors-detail-panel h3 { display: flex; align-items: center; gap: 6px; font-size: 15px; }
    .error-list-section { margin: 12px 0; }
    .error-list-section h4 { font-size: 13px; font-weight: 600; color: #444; margin: 8px 0 6px; }
    .error-item {
      background: #fff3e0; border-left: 3px solid #e65100;
      padding: 6px 10px; margin: 4px 0; font-size: 12px;
      border-radius: 0 4px 4px 0; word-break: break-all;
      display: flex; align-items: flex-start; gap: 6px;
    }
    .error-item-icon { font-size: 16px; width: 16px; height: 16px; color: #e65100; flex-shrink: 0; margin-top: 1px; }
    .error-doc-row {
      border: 1px solid #fecaca; border-radius: 6px; padding: 8px 10px; margin: 4px 0;
      background: #fef2f2;
    }
    .error-doc-header {
      display: flex; align-items: center; gap: 6px; flex-wrap: wrap;
    }
    .error-doc-icon { font-size: 16px; width: 16px; height: 16px; flex-shrink: 0; }
    .error-doc-type {
      font-size: 11px; background: #fee2e2; color: #991b1b;
      padding: 1px 6px; border-radius: 8px;
    }
    .error-doc-phase {
      font-size: 11px; background: #fef3c7; color: #92400e;
      padding: 1px 6px; border-radius: 8px;
    }
    .error-doc-message {
      margin-top: 4px; font-size: 12px; color: #991b1b; word-break: break-all;
    }
    .error-doc-path { font-size: 11px; color: #666; font-family: monospace; margin-top: 2px; }
    .event-level-badge {
      font-size: 10px; font-weight: 700; padding: 1px 6px; border-radius: 8px;
      min-width: 40px; text-align: center;
    }
    .level-warn { background: #fef3c7; color: #92400e; }
    .level-error { background: #fee2e2; color: #991b1b; }

    /* Subprocess activity panel */
    .subprocess-panel { margin-top: 18px; }
    .subprocess-panel h3 {
      display: flex; align-items: center; gap: 6px; font-size: 15px; flex-wrap: wrap;
    }
    .subprocess-stats-inline {
      display: inline-flex; gap: 8px; margin-left: 8px; font-size: 12px; font-weight: 400;
    }
    .sp-stat {
      display: inline-flex; align-items: center; padding: 1px 8px; border-radius: 10px;
    }
    .sp-stat-crash { background: #fee2e2; color: #991b1b; }
    .sp-stat-restart { background: #fef3c7; color: #92400e; }
    .sp-stat-model { background: #dcfce7; color: #166534; }
    .subprocess-empty { font-size: 13px; color: #888; padding: 8px 0; }
    .subprocess-event-list { display: flex; flex-direction: column; gap: 4px; }
    .subprocess-event-row {
      display: flex; align-items: flex-start; gap: 8px;
      padding: 8px 10px; border-radius: 6px; border: 1px solid #e5e7eb;
      background: #fff; font-size: 12px;
    }
    .subprocess-event-row.sp-error { border-color: #fecaca; background: #fef2f2; }
    .subprocess-event-row.sp-warn { border-color: #fde68a; background: #fffbeb; }
    .subprocess-event-row.sp-ok { border-color: #bbf7d0; background: #f0fdf4; }
    .subprocess-event-row.sp-info { border-color: #e5e7eb; background: #f9fafb; }
    .sp-event-icon {
      font-size: 18px; width: 18px; height: 18px; flex-shrink: 0; margin-top: 1px;
    }
    .sp-error .sp-event-icon { color: #dc2626; }
    .sp-warn .sp-event-icon { color: #d97706; }
    .sp-ok .sp-event-icon { color: #16a34a; }
    .sp-info .sp-event-icon { color: #6b7280; }
    .sp-event-body { flex: 1; min-width: 0; }
    .sp-event-header {
      display: flex; align-items: center; gap: 8px; flex-wrap: wrap;
    }
    .sp-event-type { font-weight: 600; }
    .sp-event-model {
      font-size: 11px; background: #e0f2fe; color: #075985;
      padding: 1px 6px; border-radius: 8px; font-family: monospace;
    }
    .sp-event-time { color: #9ca3af; margin-left: auto; white-space: nowrap; }
    .sp-event-detail {
      margin-top: 3px; color: #6b7280; word-break: break-all;
    }
    .sp-event-meta {
      display: flex; flex-wrap: wrap; gap: 8px;
      margin-top: 3px; font-size: 11px; color: #9ca3af; font-family: monospace;
    }

    .live-badge {
      display: inline-block; background: #4caf50; color: white;
      border-radius: 8px; padding: 1px 8px; font-size: 11px;
      font-weight: 600; margin-left: 8px; vertical-align: middle;
      animation: livePulse 2s ease-in-out infinite;
    }
    @keyframes livePulse {
      0%, 100% { opacity: 1; }
      50% { opacity: 0.6; }
    }

    .job-type-breakdown {
      margin: 4px 0;
    }
    .type-breakdown-header {
      display: flex; align-items: center; gap: 4px;
      font-size: 11px; color: #667085; font-weight: 600;
      margin-bottom: 3px;
    }
    .type-breakdown-header mat-icon { font-size: 14px; width: 14px; height: 14px; }
    .type-chips {
      display: flex; flex-wrap: wrap; gap: 4px;
    }
    .type-chip {
      display: inline-flex; align-items: center; gap: 4px;
      background: #f0f4ff; border: 1px solid #d0d9f0;
      border-radius: 12px; padding: 1px 8px; font-size: 11px;
      color: #374151;
    }
    .type-chip .type-name {
      font-weight: 500; max-width: 120px; overflow: hidden;
      text-overflow: ellipsis; white-space: nowrap;
    }
    .type-chip .type-count {
      color: #6b21a8; font-weight: 600;
    }
    .type-chip.rel-chip {
      background: #fef3f2; border-color: #fecdca;
    }
    .type-chip.rel-chip .type-count {
      color: #b42318;
    }
    .type-chip.more {
      background: #f3f4f6; border-color: #d1d5db;
      color: #6b7280; font-style: italic;
    }

    /* Source progress strip */
    .source-progress-strip { margin: 8px 0; padding: 6px 0; border-top: 1px solid #e5e7eb; }
    .source-progress-row { display: flex; align-items: center; gap: 6px; padding: 3px 0; font-size: 12px; flex-wrap: wrap; }
    .source-icon-sm { font-size: 14px !important; width: 14px !important; height: 14px !important; color: #6b7280; }
    .source-label { font-weight: 500; max-width: 180px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
    .source-status-chip { padding: 1px 6px; border-radius: 3px; font-size: 10px; font-weight: 600; }
    .source-status-chip.status-running { background: #dbeafe; color: #2563eb; }
    .source-status-chip.status-completed { background: #dcfce7; color: #16a34a; }
    .source-status-chip.status-failed { background: #fee2e2; color: #dc2626; }
    .source-status-chip.status-pending { background: #f3f4f6; color: #6b7280; }
    .source-counters { color: #6b7280; font-size: 11px; }
    .source-current-item { color: #2563eb; font-size: 11px; font-family: monospace; max-width: 250px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
    .spinning-icon-sm { font-size: 12px !important; width: 12px !important; height: 12px !important; animation: spin 1.5s linear infinite; }

    /* Discovery feed */
    .discovery-feed { margin: 8px 0; padding: 6px 8px; background: #f0fdf4; border: 1px solid #bbf7d0; border-radius: 6px; }
    .discovery-feed-header { display: flex; align-items: center; gap: 4px; font-size: 12px; font-weight: 500; color: #16a34a; margin-bottom: 4px; }
    .discovery-feed-items { display: flex; flex-direction: column; gap: 2px; max-height: 120px; overflow-y: auto; }
    .discovery-item { display: flex; align-items: center; gap: 4px; font-size: 11px; padding: 1px 0; }
    .discovery-icon { font-size: 12px !important; width: 12px !important; height: 12px !important; color: #6b7280; }
    .discovery-name { font-family: monospace; color: #374151; max-width: 300px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
    .discovery-source { color: #9ca3af; font-size: 10px; margin-left: auto; }

    /* Recent documents feed */
    .recent-docs-feed { margin: 8px 0; padding: 6px 0; border-top: 1px solid #e5e7eb; }
    .recent-docs-header { display: flex; align-items: center; gap: 4px; font-size: 12px; font-weight: 500; color: #374151; margin-bottom: 4px; }
    .recent-docs-list { display: flex; flex-direction: column; gap: 2px; }
    .recent-doc-row { display: flex; align-items: center; gap: 6px; font-size: 11px; padding: 2px 4px; border-radius: 3px; }
    .recent-doc-row.doc-status-running { background: #eff6ff; }
    .recent-doc-row.doc-status-failed { background: #fef2f2; }
    .recent-doc-name { font-weight: 500; max-width: 200px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
    .recent-doc-status { padding: 1px 5px; border-radius: 3px; font-size: 10px; font-weight: 600; }
    .doc-status-running { background: #dbeafe; color: #2563eb; }
    .doc-status-completed { background: #dcfce7; color: #16a34a; }
    .doc-status-failed { background: #fee2e2; color: #dc2626; }
    .doc-status-loaded { background: #fef3c7; color: #d97706; }
    .recent-doc-phase { color: #6b7280; font-size: 10px; }
    .recent-doc-stats { color: #6b7280; font-size: 10px; margin-left: auto; }
    .recent-doc-error { color: #dc2626; display: flex; align-items: center; }
    .recent-doc-error mat-icon { font-size: 12px !important; width: 12px !important; height: 12px !important; }

    /* Detail view discovery feed */
    .detail-discovery { margin: 12px 0; }
    .detail-items { max-height: 200px; }
    .discovery-time { color: #9ca3af; font-size: 10px; font-family: monospace; }

    /* Document pagination controls */
    .doc-controls { display: flex; align-items: center; gap: 8px; margin: 6px 0; flex-wrap: wrap; }
    .doc-filter-select { padding: 4px 8px; border: 1px solid #d1d5db; border-radius: 4px; font-size: 12px; background: #fff; }
    .doc-page-info { font-size: 12px; color: #6b7280; }
    .doc-page-btn { width: 28px !important; height: 28px !important; }
    .doc-page-btn mat-icon { font-size: 18px; }
  `]
})
export class UnifiedCrawlComponent implements OnInit, OnDestroy {
  private subscriptions = new Subscription();
  private pollInterval: any;

  activeTab = 0;

  // New job form
  jobName = '';
  sources: EditableUnifiedCrawlSource[] = [];
  availableSourceTypes: AvailableSourceType[] = [];
  isStarting = false;

  // Graph extraction
  graphEnabled = true;
  graphLlmProvider = 'default';
  graphModelName = '';
  graphSchemaPresetId = 'fpna-cpg-channel-v1';
  graphEntityTypesStr = 'PERSON, ORGANIZATION, CONCEPT, TECHNOLOGY';
  graphRelTypesStr = '';
  graphSchemaMode = 'LENIENT';
  graphMinConfidence = 0.5;
  graphEntityResolution = true;
  graphEntityResolutionSimilarityThreshold = 0.85;
  graphEntityResolutionUseEmbeddings = true;
  graphEntityResolutionEmbeddingThreshold = 0.88;

  // Vector index
  indexEnabled = true;
  indexCollectionName = '';
  embeddingBatchSize = 0;
  maxEmbeddingBatchSize = 0;
  adaptiveBatching = true;

  // Processing routes
  processingRouteEnabled = false;
  pdfRoutingMode: 'AUTO' | 'FORCE_VLM' | 'FORCE_TEXT' | 'DISABLED' = 'AUTO';
  extractTablesFromTextPdfs = true;
  fallbackEnabled = false;
  backends: any[] = [];

  // Jobs
  jobs: JobSummary[] = [];
  activeJobCount = 0;
  selectedJob: JobDetail | null = null;
  activeFactSheet: FactSheet | null = null;

  // Live graph stats (fetched separately from /api/unified-crawl/graph-stats)
  liveGraphStats: any = null;

  // Subprocess events for the selected job
  subprocessEvents: SubprocessEvent[] = [];
  subprocessStats: SubprocessStatistics | null = null;

  // Dynamic model providers
  graphModelProviders: ModelProvider[] = [];
  graphAvailableModels: { id: string; name: string }[] = [];

  // Scheduler notifications
  schedulerNotifications: { eventType: string; message: string; jobType: string; queueDepth: number; runningCount: number; timestamp: string }[] = [];
  private static readonly MAX_NOTIFICATIONS = 10;

  // Document progress pagination
  docPageIndex = 0;
  docPageSize = 25;
  docStatusFilter = '';

  constructor(
    private crawlService: UnifiedCrawlService,
    private factSheetService: FactSheetService,
    private graphExtractionService: GraphExtractionService,
    private wsService: WebSocketService,
    private snackBar: MatSnackBar,
    private cdr: ChangeDetectorRef,
    private router: Router
  ) {}

  ngOnInit() {
    this.subscriptions.add(
      this.factSheetService.activeSheet$.subscribe(sheet => {
        this.activeFactSheet = sheet;
        this.cdr.markForCheck();
      })
    );
    this.subscriptions.add(this.factSheetService.loadActiveSheet().subscribe({ error: (err) => { console.error('Failed to load active sheet:', err.message); } }));
    this.loadSourceTypes();
    this.loadGraphModelProviders();
    this.refreshJobs();
    // Subscribe to scheduler events for real-time notifications
    this.wsService.connect();
    this.subscriptions.add(
      this.wsService.subscribeToSchedulerEvents().subscribe(event => {
        this.handleSchedulerEvent(event);
      })
    );
    this.pollInterval = setInterval(() => {
      this.refreshJobs();
      if (this.selectedJob && (this.selectedJob.status === 'RUNNING' || this.selectedJob.status === 'PENDING')) {
        this.refreshSelectedJob();
        this.refreshLiveGraphStats();
        this.refreshSubprocessEvents();
      }
    }, 5000);
  }

  ngOnDestroy() {
    this.wsService.unsubscribeFromSchedulerEvents();
    this.subscriptions.unsubscribe();
    if (this.pollInterval) clearInterval(this.pollInterval);
  }

  loadGraphModelProviders() {
    this.subscriptions.add(
      this.graphExtractionService.getModelProviders().subscribe({
        next: (providers) => {
          this.graphModelProviders = providers;
          this.onGraphLlmProviderChange();
          this.cdr.markForCheck();
        },
        error: (err) => { console.error('Failed to load graph model providers:', err.message); }
      })
    );
  }

  onGraphLlmProviderChange() {
    this.graphAvailableModels = [];
    if (this.graphLlmProvider && this.graphLlmProvider !== 'default') {
      const provider = this.graphModelProviders.find(p => p.id === this.graphLlmProvider);
      if (provider && provider.models && provider.models.length > 0) {
        this.graphAvailableModels = provider.models;
      }
    }
    this.cdr.markForCheck();
  }

  handleSchedulerEvent(event: any) {
    const eventType = event.eventType;
    // Show all meaningful event types to the user
    const relevantTypes = ['JOB_QUEUED', 'JOB_DISPATCHED', 'JOB_COMPLETED', 'JOB_FAILED',
                           'JOB_CANCELLED', 'JOB_PROMOTED', 'JOB_PHASE_TRANSITION',
                           'JOB_BLOCKED', 'JOB_SKIPPED_AHEAD', 'JOB_REORDERED',
                           'QUEUE_FULL', 'SCHEDULER_STARTED', 'SCHEDULER_STOPPED'];
    if (!relevantTypes.includes(eventType)) return;

    // Filter to crawl-related events only — global events (no jobType) always pass
    const crawlJobTypes = ['unifiedCrawl', 'crawl'];
    const globalEventTypes = ['SCHEDULER_STARTED', 'SCHEDULER_STOPPED', 'JOB_REORDERED', 'QUEUE_FULL'];
    if (event.jobType && !crawlJobTypes.includes(event.jobType) && !globalEventTypes.includes(eventType)) {
      return;
    }

    let message = '';
    switch (eventType) {
      case 'JOB_QUEUED':
        message = `Job "${event.jobId}" queued (${event.jobType || 'unknown'}, priority=${event.priority || 0})`;
        break;
      case 'JOB_BLOCKED':
        message = `Job "${event.jobId}" blocked: ${event.blockedReason || 'resource unavailable'}`;
        break;
      case 'JOB_SKIPPED_AHEAD':
        message = `Job "${event.jobId}" dispatched ahead of blocked "${event.blockedJobId}" (${event.blockedReason})`;
        break;
      case 'JOB_REORDERED':
        message = `Queue reordered: ${event.blockedCount} blocked, ${event.skippedCount} dispatched ahead`;
        break;
      case 'JOB_DISPATCHED':
        message = `Job "${event.jobId}" dispatched: ${event.description || event.jobType || 'unknown'}`;
        break;
      case 'JOB_COMPLETED':
        const durationStr = event.durationMs ? ` in ${this.formatDuration(event.durationMs)}` : '';
        message = `Job "${event.jobId}" completed${durationStr}: ${event.description || event.jobType || ''}`;
        break;
      case 'JOB_FAILED':
        message = `Job "${event.jobId}" failed: ${event.error || 'unknown'}`;
        break;
      case 'JOB_CANCELLED':
        message = `Job "${event.jobId}" cancelled: ${event.cancelReason || 'no reason'}`;
        break;
      case 'JOB_PROMOTED':
        message = `Job "${event.jobId}" promoted: priority ${event.oldPriority || '?'} → ${event.newPriority || '?'}`;
        break;
      case 'JOB_PHASE_TRANSITION':
        message = `Job "${event.jobId}" phase: ${event.previousPhase || '?'} → ${event.currentPhase || '?'}`;
        break;
      case 'QUEUE_FULL':
        message = `Queue full — job "${event.rejectedJobId || event.jobId}" rejected`;
        break;
      case 'SCHEDULER_STARTED':
        message = 'Scheduler started';
        break;
      case 'SCHEDULER_STOPPED':
        message = 'Scheduler stopped';
        break;
    }

    this.schedulerNotifications.unshift({
      eventType,
      message,
      jobType: event.jobType || '',
      queueDepth: event.queueDepth || 0,
      runningCount: event.runningCount || 0,
      timestamp: event.timestamp || new Date().toISOString()
    });

    // Cap notification list
    if (this.schedulerNotifications.length > UnifiedCrawlComponent.MAX_NOTIFICATIONS) {
      this.schedulerNotifications = this.schedulerNotifications.slice(0, UnifiedCrawlComponent.MAX_NOTIFICATIONS);
    }

    // Show snackbar for blocking/skip-ahead/failure events
    if (eventType === 'JOB_BLOCKED' || eventType === 'JOB_SKIPPED_AHEAD' ||
        eventType === 'JOB_FAILED' || eventType === 'QUEUE_FULL') {
      this.snackBar.open(message, 'Dismiss', { duration: 5000 });
    }

    this.cdr.markForCheck();
  }

  dismissNotification(index: number) {
    this.schedulerNotifications.splice(index, 1);
    this.cdr.markForCheck();
  }

  private formatDuration(ms: number): string {
    if (ms < 1000) return `${ms}ms`;
    if (ms < 60000) return `${(ms / 1000).toFixed(1)}s`;
    const min = Math.floor(ms / 60000);
    const sec = Math.floor((ms % 60000) / 1000);
    return `${min}m ${sec}s`;
  }

  loadSourceTypes() {
    this.subscriptions.add(
      this.crawlService.getSourceTypes().subscribe({
        next: (types) => { this.availableSourceTypes = types; this.cdr.markForCheck(); },
        error: () => {
          // Provide defaults if endpoint unavailable
          this.availableSourceTypes = [
            { type: 'DIRECTORY', displayName: 'Local Directory', description: '', available: true, requiredProperties: [], optionalProperties: [] },
            { type: 'FILE', displayName: 'Single File', description: '', available: true, requiredProperties: [], optionalProperties: [] },
            { type: 'WEB_CRAWL', displayName: 'Web Crawl', description: '', available: true, requiredProperties: [], optionalProperties: [] },
            { type: 'URL', displayName: 'Web URL', description: '', available: true, requiredProperties: [], optionalProperties: [] },
            { type: 'EMAIL', displayName: 'Email (IMAP)', description: '', available: false, requiredProperties: [], optionalProperties: [] },
            { type: 'IMAP', displayName: 'IMAP Inbox', description: '', available: false, requiredProperties: [], optionalProperties: [] },
            { type: 'POP3', displayName: 'POP3 Inbox', description: '', available: false, requiredProperties: [], optionalProperties: [] },
            { type: 'SLACK', displayName: 'Slack', description: '', available: false, requiredProperties: [], optionalProperties: [] },
            { type: 'SLACK_HISTORY', displayName: 'Slack History', description: '', available: false, requiredProperties: [], optionalProperties: [] },
            { type: 'GDRIVE', displayName: 'Google Drive', description: '', available: false, requiredProperties: [], optionalProperties: [] },
            { type: 'ONEDRIVE', displayName: 'OneDrive', description: '', available: false, requiredProperties: [], optionalProperties: [] },
            { type: 'GMAIL', displayName: 'Gmail', description: '', available: false, requiredProperties: [], optionalProperties: [] },
            { type: 'GDOCS', displayName: 'Google Docs', description: '', available: false, requiredProperties: [], optionalProperties: [] },
            { type: 'GOOGLE_WORKSPACE', displayName: 'Google Workspace', description: '', available: false, requiredProperties: [], optionalProperties: [] },
            { type: 'CONFLUENCE', displayName: 'Confluence', description: '', available: false, requiredProperties: [], optionalProperties: [] },
            { type: 'DISCORD', displayName: 'Discord', description: '', available: false, requiredProperties: [], optionalProperties: [] },
            { type: 'DISCORD_HISTORY', displayName: 'Discord History', description: '', available: false, requiredProperties: [], optionalProperties: [] },
            { type: 'MBOX', displayName: 'MBOX Archive', description: '', available: true, requiredProperties: [], optionalProperties: [] },
            { type: 'MAILDIR', displayName: 'Maildir Archive', description: '', available: true, requiredProperties: [], optionalProperties: [] },
            { type: 'EMLX_DIR', displayName: 'Apple Mail Archive', description: '', available: true, requiredProperties: [], optionalProperties: [] },
            { type: 'PST', displayName: 'Outlook PST', description: '', available: true, requiredProperties: [], optionalProperties: [] },
          ];
          this.cdr.markForCheck();
        }
      })
    );
  }

  refreshJobs() {
    this.subscriptions.add(
      this.crawlService.listJobs().subscribe({
        next: (jobs) => {
          this.jobs = jobs;
          this.activeJobCount = jobs.filter(j => j.status === 'RUNNING' || j.status === 'PENDING').length;
          this.cdr.markForCheck();
        },
        error: (err) => { console.error('Failed to load crawl jobs:', err.message); }
      })
    );
  }

  addSource() {
    this.sources.push({
      label: '',
      sourceType: 'DIRECTORY',
      pathOrUrl: '',
      maxDepth: 3,
      maxDocuments: 0
    });
  }

  removeSource(index: number) {
    this.sources.splice(index, 1);
  }

  addBackend() {
    this.backends.push({
      id: '',
      type: 'LOCAL_MODEL',
      priority: (this.backends.length + 1) * 10,
      maxConcurrent: 1,
      requestsPerMinute: 0,
      enabled: true
    });
  }

  removeBackend(index: number) {
    this.backends.splice(index, 1);
  }

  startJob() {
    if (this.sources.length === 0) return;
    this.isStarting = true;

    let requestSources: UnifiedCrawlSource[];
    try {
      requestSources = this.sources.map(source => this.toRequestSource(source));
    } catch (err: any) {
      this.isStarting = false;
      this.snackBar.open(err.message || 'Invalid source properties JSON', 'Dismiss', { duration: 5000 });
      this.cdr.markForCheck();
      return;
    }

    const request: UnifiedCrawlRequest = {
      name: this.jobName || 'Unified crawl',
      factSheetId: this.activeFactSheet?.id || null,
      sources: requestSources,
      graphExtraction: {
        enabled: this.graphEnabled,
        schemaPresetId: this.graphSchemaPresetId || undefined,
        entityTypes: this.parseCommaSeparated(this.graphEntityTypesStr),
        relationshipTypes: this.parseCommaSeparated(this.graphRelTypesStr),
        llmProvider: this.graphLlmProvider,
        modelName: this.graphModelName || undefined,
        schemaMode: this.graphSchemaMode,
        minConfidence: this.graphMinConfidence,
        entityResolution: this.graphEntityResolution,
        entityResolutionSimilarityThreshold: this.graphEntityResolutionSimilarityThreshold,
        entityResolutionUseEmbeddings: this.graphEntityResolutionUseEmbeddings,
        entityResolutionEmbeddingThreshold: this.graphEntityResolutionEmbeddingThreshold
      },
      vectorIndex: {
        enabled: this.indexEnabled,
        collectionName: this.indexCollectionName || undefined,
        embeddingBatchSize: this.embeddingBatchSize > 0 ? this.embeddingBatchSize : undefined,
        maxEmbeddingBatchSize: this.maxEmbeddingBatchSize > 0 ? this.maxEmbeddingBatchSize : undefined,
        adaptiveBatching: this.adaptiveBatching
      },
      processingRoute: this.processingRouteEnabled ? {
        pdfRoutingMode: this.pdfRoutingMode,
        fallbackEnabled: this.fallbackEnabled,
        extractTablesFromTextPdfs: this.extractTablesFromTextPdfs,
        backends: this.fallbackEnabled ? this.backends : undefined
      } : undefined
    };

    this.subscriptions.add(
      this.crawlService.startJob(request).subscribe({
        next: (resp) => {
          this.isStarting = false;
          this.snackBar.open(`Job started: ${resp.jobId.substring(0, 8)}...`, 'OK', { duration: 3000 });
          this.refreshJobs();
          this.activeTab = 1; // Switch to jobs tab
          this.cdr.markForCheck();
        },
        error: (err) => {
          this.isStarting = false;
          this.snackBar.open('Failed to start job: ' + (err.error?.error || err.message), 'Dismiss', { duration: 5000 });
          this.cdr.markForCheck();
        }
      })
    );
  }

  private toRequestSource(source: EditableUnifiedCrawlSource): UnifiedCrawlSource {
    const { propertiesJson, ...payload } = source;
    const parsedProperties = this.parsePropertiesJson(propertiesJson);
    const properties = {
      ...(payload.properties || {}),
      ...(parsedProperties || {})
    };
    return {
      ...payload,
      properties: Object.keys(properties).length > 0 ? properties : undefined
    };
  }

  private parsePropertiesJson(propertiesJson?: string): { [key: string]: any } | undefined {
    if (!propertiesJson || !propertiesJson.trim()) {
      return undefined;
    }
    const parsed = JSON.parse(propertiesJson);
    if (!parsed || Array.isArray(parsed) || typeof parsed !== 'object') {
      throw new Error('Source properties JSON must be an object');
    }
    return parsed;
  }

  selectJob(jobId: string) {
    this.subscriptions.add(
      this.crawlService.getJob(jobId).subscribe({
        next: (detail) => {
          this.selectedJob = detail;
          this.activeTab = 2; // Switch to detail tab
          this.refreshSubprocessEvents();
          this.cdr.markForCheck();
        },
        error: () => this.snackBar.open('Failed to load job details', 'Dismiss', { duration: 3000 })
      })
    );
  }

  refreshSelectedJob() {
    if (!this.selectedJob) return;
    this.subscriptions.add(
      this.crawlService.getJob(this.selectedJob.jobId).subscribe({
        next: (detail) => { this.selectedJob = detail; this.cdr.markForCheck(); },
        error: (err) => { console.error('Failed to load selected job:', err.message); }
      })
    );
  }

  refreshLiveGraphStats() {
    this.subscriptions.add(
      this.crawlService.getLiveGraphStats().subscribe({
        next: (stats) => {
          this.liveGraphStats = stats;
          // If the selectedJob doesn't already have graph data from the detail endpoint,
          // merge live stats into selectedJob.graph so the template can display them.
          if (this.selectedJob && !this.selectedJob.graph && stats) {
            this.selectedJob = { ...this.selectedJob, graph: { ...stats, live: true } };
          }
          this.cdr.markForCheck();
        },
        error: (err) => { console.error('Failed to load live graph stats:', err.message); }
      })
    );
  }

  refreshSubprocessEvents() {
    if (!this.selectedJob) return;
    const taskId = `crawl-${this.selectedJob.jobId}`;
    this.subscriptions.add(
      this.crawlService.getSubprocessEventsForTask(taskId).subscribe({
        next: (events) => {
          this.subprocessEvents = events;
          this.cdr.markForCheck();
        },
        error: (err) => { console.error('Failed to load subprocess events:', err.message); }
      })
    );
    this.subscriptions.add(
      this.crawlService.getSubprocessStatistics().subscribe({
        next: (stats) => {
          this.subprocessStats = stats;
          this.cdr.markForCheck();
        },
        error: (err) => { console.error('Failed to load subprocess statistics:', err.message); }
      })
    );
  }

  getSubprocessEventIcon(eventType: string): string {
    const icons: { [key: string]: string } = {
      'SUBPROCESS_STARTED': 'play_circle',
      'SUBPROCESS_STOPPED': 'stop_circle',
      'SUBPROCESS_CRASHED': 'error',
      'SUBPROCESS_RESTARTING': 'restart_alt',
      'SUBPROCESS_RESTART_SUCCESS': 'check_circle',
      'SUBPROCESS_RESTART_EXHAUSTED': 'dangerous',
      'MODEL_LOADING': 'hourglass_top',
      'MODEL_LOADED': 'check_circle',
      'MODEL_FAILED': 'error_outline',
    };
    return icons[eventType] || 'info';
  }

  getSubprocessEventClass(eventType: string): string {
    if (eventType.includes('CRASHED') || eventType.includes('FAILED') || eventType.includes('EXHAUSTED')) return 'sp-error';
    if (eventType.includes('RESTARTING') || eventType === 'MODEL_LOADING') return 'sp-warn';
    if (eventType.includes('SUCCESS') || eventType === 'MODEL_LOADED' || eventType === 'SUBPROCESS_STARTED') return 'sp-ok';
    return 'sp-info';
  }

  formatEventType(eventType: string): string {
    const map: { [key: string]: string } = {
      'SUBPROCESS_STARTED': 'Started',
      'SUBPROCESS_STOPPED': 'Stopped',
      'SUBPROCESS_CRASHED': 'Crashed',
      'SUBPROCESS_RESTARTING': 'Restarting',
      'SUBPROCESS_RESTART_SUCCESS': 'Restart OK',
      'SUBPROCESS_RESTART_EXHAUSTED': 'Restarts Exhausted',
      'MODEL_LOADING': 'Loading Model',
      'MODEL_LOADED': 'Model Loaded',
      'MODEL_FAILED': 'Model Failed',
    };
    return map[eventType] || eventType;
  }

  getErrorDocuments(documents: DocumentGraphProgress[] | undefined): DocumentGraphProgress[] {
    if (!documents) return [];
    return documents.filter(d => d.status === 'FAILED' || d.errorMessage);
  }

  getWarnErrorEvents(events: any[] | undefined): any[] {
    if (!events) return [];
    return events.filter((e: any) => e.level === 'WARN' || e.level === 'ERROR');
  }

  navigateToKnowledgeGraph() {
    this.router.navigate(['/tools'], { queryParams: { tab: 'indexBrowser' } });
  }

  cancelJob(jobId: string) {
    this.subscriptions.add(
      this.crawlService.cancelJob(jobId).subscribe({
        next: () => {
          this.snackBar.open('Job cancelled', 'OK', { duration: 2000 });
          this.refreshJobs();
        },
        error: () => this.snackBar.open('Failed to cancel job', 'Dismiss', { duration: 3000 })
      })
    );
  }

  cleanupJobs() {
    this.subscriptions.add(
      this.crawlService.cleanupJobs().subscribe({
        next: (resp: any) => {
          this.snackBar.open(`Removed ${resp.removed} finished jobs`, 'OK', { duration: 2000 });
          this.refreshJobs();
        },
        error: (err) => { console.error('Failed to cleanup jobs:', err.message); }
      })
    );
  }

  getSourceIcon(type: string): string {
    const icons: { [key: string]: string } = {
      'DIRECTORY': 'folder',
      'FILE': 'insert_drive_file',
      'URL': 'link',
      'WEB_CRAWL': 'language',
      'EMAIL': 'email',
      'IMAP': 'email',
      'POP3': 'mark_email_unread',
      'SLACK': 'chat',
      'SLACK_HISTORY': 'forum',
      'GDRIVE': 'cloud',
      'GDOCS': 'article',
      'GMAIL': 'alternate_email',
      'CONFLUENCE': 'article',
      'DISCORD': 'forum',
      'DISCORD_HISTORY': 'history',
      'GOOGLE_WORKSPACE': 'work',
      'MBOX': 'inbox',
      'MAILDIR': 'move_to_inbox',
      'EMLX_DIR': 'mail',
      'PST': 'inbox',
      'ONEDRIVE': 'cloud_queue',
      'NOTION': 'note',
    };
    return icons[type] || 'source';
  }

  objectEntries(obj: any): [string, number][] {
    return obj ? Object.entries(obj) as [string, number][] : [];
  }

  objectKeys(obj: any): string[] {
    return obj ? Object.keys(obj) : [];
  }

  sortedTypeCounts(obj: { [type: string]: number }): [string, number][] {
    if (!obj) return [];
    return Object.entries(obj).sort((a, b) => (b[1] as number) - (a[1] as number));
  }

  getVisiblePipelineSteps(steps: PipelineStepProgress[] | undefined): PipelineStepProgress[] {
    if (!steps || steps.length === 0) return [];
    return steps.filter(step => step.status !== 'SKIPPED').slice(0, 9);
  }

  getVisibleDocumentProgress(documents: DocumentGraphProgress[] | undefined): DocumentGraphProgress[] {
    if (!documents || documents.length === 0) return [];
    // Filter by status if set
    let filtered = documents;
    if (this.docStatusFilter) {
      filtered = documents.filter(d => d.status === this.docStatusFilter);
    }
    // Show running/in-progress documents first, then by most recently updated
    const sorted = [...filtered].sort((a, b) => {
      const statusOrder: { [key: string]: number } = { 'RUNNING': 0, 'LOADED': 1, 'COMPLETED': 2, 'FAILED': 3, 'SKIPPED': 4 };
      const aOrder = statusOrder[a.status || ''] ?? 5;
      const bOrder = statusOrder[b.status || ''] ?? 5;
      if (aOrder !== bOrder) return aOrder - bOrder;
      const aTime = a.updatedAt ? new Date(a.updatedAt).getTime() : 0;
      const bTime = b.updatedAt ? new Date(b.updatedAt).getTime() : 0;
      return bTime - aTime;
    });
    // Paginate
    const start = this.docPageIndex * this.docPageSize;
    return sorted.slice(start, start + this.docPageSize);
  }

  getFilteredDocumentCount(documents: DocumentGraphProgress[] | undefined): number {
    if (!documents || documents.length === 0) return 0;
    if (this.docStatusFilter) {
      return documents.filter(d => d.status === this.docStatusFilter).length;
    }
    return documents.length;
  }

  get docTotalPages(): number {
    if (!this.selectedJob?.documentProgress) return 0;
    return Math.ceil(this.getFilteredDocumentCount(this.selectedJob.documentProgress) / this.docPageSize);
  }

  docNextPage(): void {
    if (this.docPageIndex < this.docTotalPages - 1) {
      this.docPageIndex++;
    }
  }

  docPrevPage(): void {
    if (this.docPageIndex > 0) {
      this.docPageIndex--;
    }
  }

  onDocStatusFilterChange(): void {
    this.docPageIndex = 0;
  }

  countDocsByStatus(documents: DocumentGraphProgress[] | undefined, status: string): number {
    if (!documents) return 0;
    return documents.filter(d => d.status === status).length;
  }

  getDocumentStatusClass(status: string | undefined): string {
    return 'doc-' + (status || 'unknown').toLowerCase();
  }

  getStepTypeIcon(stepType: string | undefined): string {
    const normalized = (stepType || '').toUpperCase();
    if (normalized.includes('IO')) return 'folder_open';
    if (normalized.includes('CPU')) return 'settings_suggest';
    if (normalized.includes('LLM')) return 'psychology';
    if (normalized.includes('GRAPH_CONSTRUCTOR')) return 'account_tree';
    if (normalized.includes('GRAPH')) return 'hub';
    if (normalized.includes('EMBEDDING')) return 'memory';
    if (normalized.includes('PIPELINE')) return 'schema';
    return 'schema';
  }

  getStepTypeClass(stepType: string | undefined): string {
    return 'type-' + (stepType || 'pipeline').toLowerCase().replace(/_/g, '-');
  }

  getStepStatusClass(status: string | undefined): string {
    return 'step-' + (status || 'pending').toLowerCase();
  }

  getCrawlHistoryTaskId(jobId: string): string {
    return `crawl-${jobId}`;
  }

  isCrawlJobRunning(status: string): boolean {
    return status === 'RUNNING' || status === 'PENDING';
  }

  formatElapsed(ms: number): string {
    if (!ms || ms <= 0) return '';
    const totalSeconds = Math.floor(ms / 1000);
    const hours = Math.floor(totalSeconds / 3600);
    const minutes = Math.floor((totalSeconds % 3600) / 60);
    const seconds = totalSeconds % 60;
    if (hours > 0) return `${hours}h ${minutes}m ${seconds}s`;
    if (minutes > 0) return `${minutes}m ${seconds}s`;
    return `${seconds}s`;
  }

  /** Get throughput for a pipeline step as items/sec or items/min */
  getStepThroughput(step: PipelineStepProgress): string {
    if (!step.elapsedMs || step.elapsedMs < 1000 || step.completedItems <= 0) return '';
    const rate = step.completedItems / (step.elapsedMs / 1000);
    if (rate >= 1) return `${rate.toFixed(1)}/s`;
    const perMin = rate * 60;
    return `${perMin.toFixed(1)}/min`;
  }

  /** Format event timestamp as relative time */
  formatEventTime(timestamp: string): string {
    if (!timestamp) return '';
    const diff = Date.now() - new Date(timestamp).getTime();
    if (diff < 1000) return 'now';
    if (diff < 60000) return `${Math.floor(diff / 1000)}s ago`;
    if (diff < 3600000) return `${Math.floor(diff / 60000)}m ago`;
    return `${Math.floor(diff / 3600000)}h ago`;
  }

  formatBytes(bytes: number): string {
    if (!bytes || bytes <= 0) return '0 B';
    const units = ['B', 'KB', 'MB', 'GB', 'TB'];
    let value = bytes;
    let unitIndex = 0;
    while (value >= 1024 && unitIndex < units.length - 1) {
      value /= 1024;
      unitIndex++;
    }
    return unitIndex === 0 ? `${bytes} ${units[unitIndex]}` : `${value.toFixed(1)} ${units[unitIndex]}`;
  }

  formatPhase(phase: string | undefined | null): string {
    if (!phase) return '';
    const phaseMap: { [key: string]: string } = {
      'QUEUED': 'Queued',
      'DISCOVERING': 'Discovering documents',
      'LOADING': 'Loading documents',
      'OCR_PROCESSING': 'OCR processing',
      'CONVERTING': 'Converting documents',
      'ROUTING': 'Routing documents',
      'GRAPH_PREP': 'Preparing graph extraction',
      'ENTITY_RESOLUTION': 'Resolving entities',
      'EDGE_COMPUTATION': 'Computing edges',
      'CHUNKING': 'Chunking documents',
      'GRAPH_EXTRACTION': 'Extracting graph',
      'EMBEDDING': 'Generating embeddings',
      'INDEXING': 'Indexing to vector store',
      'VECTOR_INDEXING': 'Embedding & vector indexing',
      'COMPLETED': 'Completed',
      'FAILED': 'Failed',
      'CANCELLED': 'Cancelled',
      'PENDING': 'Pending',
      'RUNNING': 'Running',
      'PAUSED': 'Paused',
    };
    return phaseMap[phase] || phase;
  }

  formatTimestamp(ts: string | undefined | null): string {
    if (!ts) return '';
    try {
      const date = new Date(ts);
      const now = new Date();
      const diffMs = now.getTime() - date.getTime();
      if (diffMs < 60000) return 'just now';
      if (diffMs < 3600000) return `${Math.floor(diffMs / 60000)}m ago`;
      if (diffMs < 86400000) return `${Math.floor(diffMs / 3600000)}h ago`;
      return date.toLocaleDateString() + ' ' + date.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
    } catch {
      return ts;
    }
  }

  private parseCommaSeparated(str: string): string[] {
    if (!str || !str.trim()) return [];
    return str.split(',').map(s => s.trim()).filter(s => s.length > 0);
  }
}
