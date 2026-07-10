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
  Component, OnInit, OnDestroy, ChangeDetectionStrategy, ChangeDetectorRef, NgZone
} from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { RouterModule } from '@angular/router';
import { Observable, Subscription } from 'rxjs';

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
import { MatDialog, MatDialogModule } from '@angular/material/dialog';

import {
  UnifiedCrawlService,
  UnifiedCrawlRequest,
  JobSummary,
  JobDetail,
  DocumentGraphProgress,
  AvailableSourceType,
  SubprocessEvent,
  SubprocessStatistics,
  PipelineStepCatalogEntry,
  ResumableJobEntry
} from '../../services/unified-crawl.service';
import { JobLogViewerComponent } from '../job-history/job-log-viewer/job-log-viewer.component';
import { ResourceStripComponent } from '../resource-strip/resource-strip.component';
import { CrawlStepMonitorComponent } from '../crawl-step-monitor/crawl-step-monitor.component';
import { HydrationProgressPanelComponent } from '../hydration-progress-panel/hydration-progress-panel.component';
import { JobLogService, JobLogEntry } from '../../services/job-log.service';
import { FactSheetService } from '../../services/fact-sheet.service';
import { FactSheet } from '../../models/api-models';
import { GraphExtractionService, ModelProvider, GraphExtractionConfig } from '../../services/graph-extraction.service';
import { WebSocketService } from '../../services/websocket.service';
import { DistributedCrawlService } from '../../services/distributed-crawl.service';
import { CrawlLauncherDialogComponent, CrawlLauncherDialogData, CrawlLauncherResult } from './crawl-launcher-dialog/crawl-launcher-dialog.component';

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
    MatSnackBarModule, MatTabsModule, MatBadgeModule, MatDialogModule,
    JobLogViewerComponent,
    ResourceStripComponent,
    CrawlStepMonitorComponent,
    HydrationProgressPanelComponent
  ],
  templateUrl: './unified-crawl.component.html',
  styleUrls: ['./unified-crawl.component.css']
})
export class UnifiedCrawlComponent implements OnInit, OnDestroy {
  private subscriptions = new Subscription();
  private pollInterval: any;
  private jobEventSource: EventSource | null = null;
  private lastSseRefreshMs = 0;

  activeTab = 0;

  // Catalogs loaded once and handed to the launcher modal (avoids duplicate fetches in the dialog).
  availableSourceTypes: AvailableSourceType[] = [];
  isStarting = false;

  // Distributed crawl — when a cluster has live workers, the launcher offers to fan the sources
  // across them (POST /distributed-crawl/start) instead of running locally.
  clusterWorkerCount = 0;

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

  // Dynamic model providers (loaded once; passed to the launcher modal)
  graphModelProviders: ModelProvider[] = [];
  /** LLM / CLI-agent model that performs entity & graph extraction, e.g. "opencode-cli / default". */
  extractionAgentLabel: string | null = null;

  // Scheduler notifications
  schedulerNotifications: { eventType: string; message: string; jobType: string; queueDepth: number; runningCount: number; timestamp: string }[] = [];
  private static readonly MAX_NOTIFICATIONS = 10;

  // Document progress pagination
  docPageIndex = 0;
  docPageSize = 25;
  docStatusFilter = '';

  // LLM transcript audit state
  transcriptLogs: JobLogEntry[] = [];
  transcriptsLoading = false;
  transcriptsExpanded = false;
  transcriptPage = 0;
  transcriptTotalCount = 0;

  // Live in-progress transcript feed (shown during RUNNING/PENDING without expanding the full viewer)
  liveTranscripts: JobLogEntry[] = [];
  liveTranscriptsLoading = false;

  // Retry state
  retryInProgress = false;

  // Step catalog (loaded once; per-step run/archive/skip selection happens in the launcher modal)
  stepCatalog: PipelineStepCatalogEntry[] = [];

  // Resumable jobs
  resumableJobs: ResumableJobEntry[] = [];
  stepActionInProgress: { [key: string]: boolean } = {};

  constructor(
    private crawlService: UnifiedCrawlService,
    private factSheetService: FactSheetService,
    private graphExtractionService: GraphExtractionService,
    private jobLogService: JobLogService,
    private wsService: WebSocketService,
    private distributedCrawlService: DistributedCrawlService,
    private dialog: MatDialog,
    private snackBar: MatSnackBar,
    private cdr: ChangeDetectorRef,
    private router: Router,
    private zone: NgZone
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
    this.loadExtractionAgent();
    this.loadStepCatalog();
    this.refreshJobs();
    this.refreshResumableJobs();
    this.loadClusterWorkers();
    // Subscribe to scheduler events for real-time notifications
    this.wsService.connect();
    this.subscriptions.add(
      this.wsService.subscribeToSchedulerEvents().subscribe(event => {
        this.handleSchedulerEvent(event);
      })
    );
    this.pollInterval = setInterval(() => {
      this.refreshJobs();
      this.refreshResumableJobs();
      if (this.selectedJob && (this.selectedJob.status === 'RUNNING' || this.selectedJob.status === 'PENDING')) {
        this.refreshSelectedJob();
        this.refreshLiveGraphStats();
        this.refreshSubprocessEvents();
        this.refreshLiveTranscripts();
      }
    }, 5000);
  }

  ngOnDestroy() {
    this.wsService.unsubscribeFromSchedulerEvents();
    this.subscriptions.unsubscribe();
    if (this.pollInterval) clearInterval(this.pollInterval);
    this.disconnectJobStream();
  }

  loadGraphModelProviders() {
    this.subscriptions.add(
      this.graphExtractionService.getModelProviders().subscribe({
        next: (providers) => {
          this.graphModelProviders = providers;
          this.cdr.markForCheck();
        },
        error: (err) => { console.error('Failed to load graph model providers:', err.message); }
      })
    );
  }

  /** Load which LLM / CLI-agent model performs entity & graph extraction, so it is visible during a crawl. */
  loadExtractionAgent() {
    this.subscriptions.add(
      this.graphExtractionService.getConfig().subscribe({
        next: (cfg: GraphExtractionConfig) => {
          const provider = (cfg?.extractionModelProvider || '').trim() || 'default';
          const model = (cfg?.extractionModelName || '').trim() || 'default';
          this.extractionAgentLabel = `${provider} / ${model}`;
          this.cdr.markForCheck();
        },
        error: () => { /* non-fatal: leave the label hidden */ }
      })
    );
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
          // Merge into existing job objects by id so Angular's ngFor trackBy keeps the same component
          // instances alive and the crawl-step-monitor expand state (expandedSteps / transcriptsOpen)
          // is preserved across every SSE-triggered refresh.
          const byId = new Map(this.jobs.map(j => [j.jobId, j]));
          this.jobs = jobs.map(fresh => {
            const existing = byId.get(fresh.jobId);
            if (existing) { Object.assign(existing, fresh); return existing; }
            return fresh;
          });
          this.activeJobCount = this.jobs.filter(j => j.status === 'RUNNING' || j.status === 'PENDING').length;
          this.cdr.markForCheck();
        },
        error: (err) => { console.error('Failed to load crawl jobs:', err.message); }
      })
    );
  }

  trackByJobId(_i: number, job: JobSummary): string {
    return job.jobId;
  }

  /** Count live cluster workers so the launcher can offer to distribute the crawl. Best-effort; failure = local-only. */
  loadClusterWorkers() {
    this.subscriptions.add(
      this.distributedCrawlService.liveWorkers().subscribe({
        next: (workers) => {
          this.clusterWorkerCount = Array.isArray(workers) ? workers.length : 0;
          this.cdr.markForCheck();
        },
        error: () => {
          this.clusterWorkerCount = 0;
          this.cdr.markForCheck();
        }
      })
    );
  }

  /**
   * Open the full-surface crawl launcher modal. The dialog assembles a complete
   * {@link UnifiedCrawlRequest} from every supported parameter and returns it (plus a distribute
   * flag); the host then starts the job locally or fans it across the live cluster.
   */
  launchCrawl() {
    const data: CrawlLauncherDialogData = {
      sourceTypes: this.availableSourceTypes,
      graphModelProviders: this.graphModelProviders,
      stepCatalog: this.stepCatalog,
      clusterWorkerCount: this.clusterWorkerCount,
      activeFactSheet: this.activeFactSheet,
      extractionAgentLabel: this.extractionAgentLabel
    };
    const ref = this.dialog.open(CrawlLauncherDialogComponent, {
      width: '960px',
      maxWidth: '96vw',
      maxHeight: '92vh',
      autoFocus: false,
      restoreFocus: false,
      panelClass: 'crawl-launcher-dialog-panel',
      data
    });
    this.subscriptions.add(
      ref.afterClosed().subscribe((result: CrawlLauncherResult | undefined) => {
        if (result && result.request) {
          this.startJob(result.request, result.distribute);
        }
      })
    );
  }

  /** Start an assembled crawl request — locally, or fanned across the live cluster when requested. */
  startJob(request: UnifiedCrawlRequest, distribute: boolean) {
    if (!request.sources || request.sources.length === 0) return;
    this.isStarting = true;
    this.cdr.markForCheck();

    // Distribute across cluster workers when requested + a cluster is live; otherwise run locally.
    const distributed = distribute && this.clusterWorkerCount > 0;
    if (distributed) {
      request.distribution = { partitionStrategy: 'PER_SOURCE', mergeResults: true };
    }
    const start$: Observable<any> = distributed
      ? this.distributedCrawlService.startDistributed(request)
      : this.crawlService.startJob(request);

    this.subscriptions.add(
      start$.subscribe({
        next: (resp: any) => {
          this.isStarting = false;
          if (distributed) {
            const sid = (resp?.sessionId || '').toString();
            this.snackBar.open(
              `Distributed crawl started across ${this.clusterWorkerCount} worker(s) (${sid.substring(0, 8)}...) — `
              + `track it in the Crawlers panel`, 'OK', { duration: 5000 });
          } else {
            this.snackBar.open(`Job started: ${resp.jobId.substring(0, 8)}...`, 'OK', { duration: 3000 });
            this.activeTab = 0; // Switch to the jobs tab (local jobs only; distributed sessions live in Crawlers)
          }
          this.refreshJobs();
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

  selectJob(jobId: string) {
    // Clear live transcript feed when switching jobs
    this.liveTranscripts = [];
    this.disconnectJobStream();
    this.subscriptions.add(
      this.crawlService.getJob(jobId).subscribe({
        next: (detail) => {
          this.selectedJob = detail;
          this.activeTab = 1; // Switch to the Job Detail tab
          this.refreshSubprocessEvents();
          this.refreshLiveTranscripts();
          this.connectJobStream(jobId);
          this.cdr.markForCheck();
        },
        error: () => {
          // Live job not found — try loading from history
          this.subscriptions.add(
            this.crawlService.getJobFromHistory(jobId).subscribe({
              next: (detail) => {
                this.selectedJob = detail;
                this.activeTab = 1;
                this.cdr.markForCheck();
              },
              error: () => this.snackBar.open('Failed to load job details', 'Dismiss', { duration: 3000 })
            })
          );
        }
      })
    );
  }

  refreshSelectedJob() {
    if (!this.selectedJob) return;
    // Don't poll historical jobs — their state is fixed
    if (this.selectedJob.fromHistory) return;
    this.subscriptions.add(
      this.crawlService.getJob(this.selectedJob.jobId).subscribe({
        next: (detail) => {
          // Merge into the existing object so its reference stays stable across polls. Replacing it
          // wholesale every SSE tick tore down + rebuilt the entire detail subtree (collapsing any
          // expanded step panels — the "fidgety, won't-stay-expanded" flicker). Mirrors the jobs-list merge.
          if (this.selectedJob && this.selectedJob.jobId === detail.jobId) {
            Object.assign(this.selectedJob, detail);
          } else {
            this.selectedJob = detail;
          }
          this.cdr.markForCheck();
        },
        error: (err) => { console.error('Failed to load selected job:', err.message); }
      })
    );
  }

  /**
   * Subscribe to the live SSE progress stream for a job so per-step state and the rolling LLM
   * transcript update in real time instead of waiting for the 5s poll (which remains a fallback).
   * Progress bursts are coalesced to ~one detail refresh per 800ms; terminal events refresh at once.
   */
  private connectJobStream(jobId: string) {
    this.disconnectJobStream();
    if (typeof EventSource === 'undefined') return;
    let es: EventSource;
    try {
      es = new EventSource(this.crawlService.jobEventStreamUrl(jobId));
    } catch {
      return; // SSE unavailable — the 5s poll still drives updates
    }
    this.jobEventSource = es;
    this.lastSseRefreshMs = 0;

    const liveRefresh = () => this.zone.run(() => {
      if (!this.selectedJob || this.selectedJob.jobId !== jobId || this.selectedJob.fromHistory) return;
      const now = Date.now();
      if (now - this.lastSseRefreshMs < 800) return;
      this.lastSseRefreshMs = now;
      this.refreshSelectedJob();
      this.refreshLiveTranscripts();
    });

    const terminalRefresh = () => this.zone.run(() => {
      if (this.selectedJob?.jobId === jobId) {
        this.refreshSelectedJob();
        this.refreshLiveTranscripts();
        this.refreshLiveGraphStats();
      }
      this.refreshJobs();
      this.cdr.markForCheck();
    });

    es.addEventListener('started', liveRefresh);
    es.addEventListener('progress', liveRefresh);
    // Resource/model decisions (batch resizes, gate waits, KGE choices, OOM defers) arrive as
    // 'decision' events; the snapshot they carry has updated recentTuningDecisions/recentEvents so
    // we trigger the same live refresh as a progress tick so the step-monitor updates immediately.
    es.addEventListener('decision', liveRefresh);
    es.addEventListener('completed', terminalRefresh);
    es.addEventListener('error', (e: any) => {
      // The browser fires 'error' for transport hiccups (no data; EventSource auto-reconnects); our
      // server-sent ERROR event also lands here but carries data — only act on the latter.
      if (e && e.data) terminalRefresh();
    });
  }

  private disconnectJobStream() {
    if (this.jobEventSource) {
      this.jobEventSource.close();
      this.jobEventSource = null;
    }
  }

  refreshLiveGraphStats() {
    this.subscriptions.add(
      this.crawlService.getLiveGraphStats().subscribe({
        next: (stats) => {
          this.liveGraphStats = stats;
          // If the selectedJob doesn't already have graph data from the detail endpoint,
          // merge live stats into selectedJob.graph so the template can display them.
          if (this.selectedJob && !this.selectedJob.graph && stats) {
            // Mutate in place — replacing selectedJob here also collapsed expanded panels.
            this.selectedJob.graph = { ...stats, live: true };
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
    // Index Browser moved to Developer hub > Management section (tab index 2)
    this.router.navigate(['/developer'], { queryParams: { tab: 2 } });
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

  retryFailedDocuments(jobId: string, retryPhase?: string) {
    this.retryInProgress = true;
    this.cdr.markForCheck();
    this.subscriptions.add(
      this.crawlService.retryJob(jobId, retryPhase).subscribe({
        next: (resp: any) => {
          this.retryInProgress = false;
          this.snackBar.open(
            `Retry job started: ${resp.documentsToRetry} documents to re-process`,
            'View', { duration: 5000 }
          );
          this.refreshJobs();
          this.cdr.markForCheck();
        },
        error: (err: any) => {
          this.retryInProgress = false;
          const msg = err?.error?.error || 'Failed to start retry job';
          this.snackBar.open(msg, 'Dismiss', { duration: 4000 });
          this.cdr.markForCheck();
        }
      })
    );
  }

  getErrorDocumentsForPhase(documents: DocumentGraphProgress[] | undefined, phase: string): DocumentGraphProgress[] {
    if (!documents) return [];
    return documents.filter(d => (d.status === 'FAILED' || d.errorMessage) && d.phase === phase);
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

  getCrawlHistoryTaskId(jobId: string): string {
    // Crawl logs persist under "crawl-<internalJobId>" (the stable durable id), NOT the public
    // scheduler jobId. Using `crawl-${jobId}` produced a non-existent id (the jobId already carries a
    // "crawl-" prefix → "crawl-crawl-…", and it's the wrong id anyway), so the per-step log/transcript
    // viewer queried a task with zero logs → "No logs found / adjust your filters". Resolve the internal
    // id from the selected job when it matches; otherwise strip any existing "crawl-" prefix so we never
    // emit a double-prefixed id.
    const sj = this.selectedJob as { jobId?: string; internalJobId?: string } | null | undefined;
    const internal = sj && (sj.jobId === jobId || sj.internalJobId === jobId) ? sj.internalJobId : undefined;
    const resolved = internal || (jobId && jobId.startsWith('crawl-') ? jobId.substring('crawl-'.length) : jobId);
    return `crawl-${resolved}`;
  }

  isCrawlJobRunning(status: string): boolean {
    return status === 'RUNNING' || status === 'PENDING';
  }

  toggleTranscripts(jobId: string): void {
    this.transcriptsExpanded = !this.transcriptsExpanded;
    if (this.transcriptsExpanded && this.transcriptLogs.length === 0) {
      this.loadTranscripts(jobId);
    }
  }

  /** Extract the agent chat session id embedded in a transcript message header, if any. */
  transcriptSessionId(message: string | undefined): string | null {
    if (!message) { return null; }
    const m = message.match(/\bsession=(\S+)/);
    return m ? m[1] : null;
  }

  loadTranscripts(jobId: string, page: number = 0): void {
    this.transcriptsLoading = true;
    this.transcriptPage = page;
    this.cdr.markForCheck();

    const taskId = this.getCrawlHistoryTaskId(jobId);
    // When a parent task id is present (resumed job), fetch both the current job's
    // transcripts and the parent's, then merge them in chronological order.
    const parentTaskId = this.selectedJob?.resumedFromTaskId ?? null;

    this.jobLogService.getLogsForJob(taskId, {
      source: 'LLM_TRANSCRIPT',
      page: page,
      size: 50
    }).subscribe({
      next: (resp) => {
        const currentLogs: JobLogEntry[] = resp.logs || [];
        const totalCount: number = resp.totalCount || 0;

        if (parentTaskId && page === 0) {
          // Only merge on page 0 — subsequent pages belong to the current job only
          this.jobLogService.getLogsForJob(parentTaskId, {
            source: 'LLM_TRANSCRIPT',
            page: 0,
            size: 50
          }).subscribe({
            next: (parentResp) => {
              const parentLogs: JobLogEntry[] = parentResp.logs || [];
              // Merge: sort by timestamp ascending so the combined list is chronological
              const merged = [...parentLogs, ...currentLogs].sort((a, b) => {
                const ta = a.timestamp ? new Date(a.timestamp).getTime() : 0;
                const tb = b.timestamp ? new Date(b.timestamp).getTime() : 0;
                return ta - tb;
              });
              this.transcriptLogs = merged;
              // Report combined count so the pagination footer is informative
              this.transcriptTotalCount = (parentResp.totalCount || 0) + totalCount;
              this.transcriptsLoading = false;
              this.cdr.markForCheck();
            },
            error: () => {
              // Parent fetch failed — show only the current job's transcripts
              this.transcriptLogs = currentLogs;
              this.transcriptTotalCount = totalCount;
              this.transcriptsLoading = false;
              this.cdr.markForCheck();
            }
          });
        } else {
          this.transcriptLogs = currentLogs;
          this.transcriptTotalCount = totalCount;
          this.transcriptsLoading = false;
          this.cdr.markForCheck();
        }
      },
      error: () => {
        this.transcriptsLoading = false;
        this.cdr.markForCheck();
      }
    });
  }

  loadMoreTranscripts(jobId: string): void {
    this.loadTranscripts(jobId, this.transcriptPage + 1);
  }

  loadPrevTranscripts(jobId: string): void {
    if (this.transcriptPage > 0) {
      this.loadTranscripts(jobId, this.transcriptPage - 1);
    }
  }

  /**
   * Poll the latest LLM_TRANSCRIPT entries for the active running job so the in-progress view
   * surfaces extraction activity without requiring the user to open the full transcript panel.
   * Fetches the most-recent page (page=0, size=5) and stops if the job reaches a terminal state.
   */
  refreshLiveTranscripts(): void {
    if (!this.selectedJob) { return; }
    if (this.selectedJob.status !== 'RUNNING' && this.selectedJob.status !== 'PENDING') {
      // Clear live feed when job finishes; user uses the collapsible full viewer for post-mortem
      this.liveTranscripts = [];
      this.cdr.markForCheck();
      return;
    }
    this.liveTranscriptsLoading = true;
    const taskId = this.getCrawlHistoryTaskId(this.selectedJob.jobId);
    this.subscriptions.add(
      this.jobLogService.getLogsForJob(taskId, { source: 'LLM_TRANSCRIPT', page: 0, size: 5 }).subscribe({
        next: (resp) => {
          this.liveTranscripts = resp.logs || [];
          this.liveTranscriptsLoading = false;
          this.cdr.markForCheck();
        },
        error: () => {
          this.liveTranscriptsLoading = false;
          this.cdr.markForCheck();
        }
      })
    );
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
      'CHUNKING': 'Chunking documents',
      'GRAPH_EXTRACTION': 'Extracting graph',
      'SURFACING': 'Publishing crawl surface',
      'ENTITY_RESOLUTION': 'Resolving entities',
      'EDGE_COMPUTATION': 'Graph edge cleanup',
      'EMBEDDING': 'Embedding & vector indexing',
      'INDEXING': 'Embedding & vector indexing',
      'VECTOR_INDEXING': 'Embedding & vector indexing',
      'ENRICHMENT': 'Post-Crawl Enrichment',
      'LEARNING': 'KGE Training (Learning)',
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

  loadStepCatalog() {
    this.subscriptions.add(
      this.crawlService.getStepCatalog().subscribe({
        next: (catalog) => {
          this.stepCatalog = catalog;
          this.cdr.markForCheck();
        },
        error: (err) => { console.error('Failed to load step catalog:', err.message); }
      })
    );
  }

  refreshResumableJobs() {
    this.subscriptions.add(
      this.crawlService.listResumableJobs().subscribe({
        next: (jobs) => {
          this.resumableJobs = jobs;
          this.cdr.markForCheck();
        },
        error: (err) => { console.error('Failed to load resumable jobs:', err.message); }
      })
    );
  }

  runStep(jobId: string, stepId: string) {
    const key = `${jobId}:${stepId}`;
    this.stepActionInProgress[key] = true;
    this.cdr.markForCheck();
    this.subscriptions.add(
      this.crawlService.runStep(jobId, stepId).subscribe({
        next: (resp: any) => {
          this.stepActionInProgress[key] = false;
          this.snackBar.open(resp.message || `Step ${stepId} triggered`, 'OK', { duration: 3000 });
          this.refreshJobs();
          this.refreshResumableJobs();
          if (this.selectedJob?.jobId === jobId) {
            this.refreshSelectedJob();
          }
          this.cdr.markForCheck();
        },
        error: (err: any) => {
          this.stepActionInProgress[key] = false;
          this.snackBar.open(err.error?.error || `Failed to run step ${stepId}`, 'Dismiss', { duration: 4000 });
          this.cdr.markForCheck();
        }
      })
    );
  }

  resumeAllArchivedSteps(entry: ResumableJobEntry) {
    // Run archived steps sequentially by chaining subscriptions
    const steps = [...entry.archivedSteps];
    const runNext = (index: number) => {
      if (index >= steps.length) {
        this.snackBar.open(`Resumed ${steps.length} step(s) for "${entry.name}"`, 'OK', { duration: 3000 });
        this.refreshJobs();
        this.refreshResumableJobs();
        this.cdr.markForCheck();
        return;
      }
      const key = `${entry.jobId}:${steps[index]}`;
      this.stepActionInProgress[key] = true;
      this.cdr.markForCheck();
      this.subscriptions.add(
        this.crawlService.runStep(entry.jobId, steps[index]).subscribe({
          next: () => {
            this.stepActionInProgress[key] = false;
            runNext(index + 1);
          },
          error: (err: any) => {
            this.stepActionInProgress[key] = false;
            this.snackBar.open(err.error?.error || `Failed to run step ${steps[index]}`, 'Dismiss', { duration: 4000 });
            this.cdr.markForCheck();
          }
        })
      );
    };
    runNext(0);
  }

  isStepActionInProgress(jobId: string, stepId: string): boolean {
    return !!this.stepActionInProgress[`${jobId}:${stepId}`];
  }

  /** Builds the Set<string> of in-progress stepIds for a given jobId — passed to the step monitor. */
  getRunningStepIds(jobId: string): Set<string> {
    const s = new Set<string>();
    const prefix = jobId + ':';
    for (const key of Object.keys(this.stepActionInProgress)) {
      if (this.stepActionInProgress[key] && key.startsWith(prefix)) {
        s.add(key.slice(prefix.length));
      }
    }
    return s;
  }

}
