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
  templateUrl: './unified-crawl.component.html',
  styleUrls: ['./unified-crawl.component.css']
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

  formatCost(centsX100: number): string {
    return '$' + (centsX100 / 10000).toFixed(4);
  }

  getBackendIds(job: any): string[] {
    if (!job?.backendStats) return [];
    return Object.keys(job.backendStats);
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
