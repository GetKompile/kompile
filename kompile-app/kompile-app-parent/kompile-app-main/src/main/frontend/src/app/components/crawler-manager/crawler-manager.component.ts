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

import { Component, OnInit, OnDestroy, ChangeDetectionStrategy, ChangeDetectorRef, NgZone } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { MatCardModule } from '@angular/material/card';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatChipsModule } from '@angular/material/chips';
import { MatTooltipModule } from '@angular/material/tooltip';
import { MatSlideToggleModule } from '@angular/material/slide-toggle';
import { MatDialog, MatDialogModule } from '@angular/material/dialog';
import { JobLogViewerComponent } from '../job-history/job-log-viewer/job-log-viewer.component';
import { ResourceStripComponent } from '../resource-strip/resource-strip.component';
import { CrawlStepMonitorComponent } from '../crawl-step-monitor/crawl-step-monitor.component';
import { CrawlLauncherDialogComponent, CrawlLauncherResult } from '../unified-crawl/crawl-launcher-dialog/crawl-launcher-dialog.component';

import {
  CrawlerService,
  CrawlerInfo,
  CrawlJobSummary,
  CrawlPipelineInfo,
  CrawlStepInfo,
  StartCrawlRequest
} from '../../services/crawler.service';
import { UnifiedCrawlService, JobDetail, UnifiedCrawlRequest } from '../../services/unified-crawl.service';
import { DistributedCrawlService } from '../../services/distributed-crawl.service';
import { WebSocketService } from '../../services/websocket.service';
import { GraphExtractionService, GraphExtractionConfig } from '../../services/graph-extraction.service';
import { Observable, Subscription, forkJoin, of } from 'rxjs';
import { catchError } from 'rxjs/operators';

@Component({
  selector: 'app-crawler-manager',
  standalone: true,
  imports: [
    CommonModule,
    FormsModule,
    MatCardModule,
    MatButtonModule,
    MatIconModule,
    MatFormFieldModule,
    MatInputModule,
    MatSelectModule,
    MatProgressBarModule,
    MatChipsModule,
    MatTooltipModule,
    MatSlideToggleModule,
    MatDialogModule,
    JobLogViewerComponent,
    ResourceStripComponent,
    CrawlStepMonitorComponent
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './crawler-manager.component.html',
  styleUrls: ['./crawler-manager.component.css']
})
export class CrawlerManagerComponent implements OnInit, OnDestroy {

  crawlers: CrawlerInfo[] = [];
  jobs: CrawlJobSummary[] = [];
  isLoading = false;
  errorMessage: string | null = null;
  stepActionInProgress: { [key: string]: boolean } = {};

  // New crawl form
  seed = '';
  selectedCrawlerId = '';
  maxDepth = 3;
  maxDocuments = 1000;
  sameDomainOnly = true;

  private refreshInterval: any;
  private globalEventSource: EventSource | null = null;
  private lastSseListRefreshMs = 0;
  expandedLogsJobId: string | null = null;
  expandedTranscriptsJobId: string | null = null;
  expandedStepsJobId: string | null = null;

  /** Full rich job detail for the currently-expanded steps panel (lazy-fetched on open, refreshed
   *  each loadJobs tick) — supplies retries / tuning decisions / transcripts / activity to the monitor. */
  cmRichJob: JobDetail | null = null;
  cmRichJobId: string | null = null;

  // Live crawl progress from WebSocket
  liveCrawlProgress: Record<string, any> = {};
  /** LLM / CLI-agent model that performs entity & graph extraction, e.g. "opencode-cli / default". */
  extractionAgentLabel: string | null = null;
  private wsSubs: Subscription[] = [];

  constructor(
    private crawlerService: CrawlerService,
    private unifiedCrawlService: UnifiedCrawlService,
    private distributedCrawlService: DistributedCrawlService,
    private cdr: ChangeDetectorRef,
    private wsService: WebSocketService,
    private graphExtractionService: GraphExtractionService,
    private dialog: MatDialog,
    private zone: NgZone
  ) {}

  ngOnInit(): void {
    this.loadCrawlers();
    this.loadJobs();
    this.loadExtractionAgent();
    this.refreshInterval = setInterval(() => this.loadJobs(), 5000);
    this.wsSubs.push(
      this.wsService.subscribeToCrawlProgress().subscribe((update: any) => {
        if (update.jobId) {
          this.liveCrawlProgress[update.jobId] = update;
          this.cdr.markForCheck();
        }
      }),
      this.wsService.subscribeToCrawlComplete().subscribe((update: any) => {
        if (update.jobId) {
          delete this.liveCrawlProgress[update.jobId];
          this.loadJobs();
        }
      })
    );
    this.connectGlobalStream();
  }

  /** Load which LLM / CLI-agent model performs entity & graph extraction, so it is visible during a crawl. */
  private loadExtractionAgent(): void {
    this.graphExtractionService.getConfig().subscribe({
      next: (cfg: GraphExtractionConfig) => {
        const provider = (cfg?.extractionModelProvider || '').trim() || 'default';
        const model = (cfg?.extractionModelName || '').trim() || 'default';
        this.extractionAgentLabel = `${provider} / ${model}`;
        this.cdr.markForCheck();
      },
      error: () => { /* non-fatal: leave the label hidden */ }
    });
  }

  ngOnDestroy(): void {
    if (this.refreshInterval) {
      clearInterval(this.refreshInterval);
    }
    this.wsSubs.forEach(s => s.unsubscribe());
    this.wsService.unsubscribeFromCrawlProgress();
    this.disconnectGlobalStream();
  }

  /**
   * Subscribe to the global crawl SSE stream so the merged job list reflects unified-crawl progress in
   * near-real time (the STOMP feed only covers legacy crawler jobs). Progress events are coalesced to a
   * list refresh at most once per second; terminal events refresh immediately. The 5s poll is the fallback.
   */
  private connectGlobalStream(): void {
    this.disconnectGlobalStream();
    if (typeof EventSource === 'undefined') return;
    let es: EventSource;
    try {
      es = new EventSource(this.unifiedCrawlService.crawlEventsStreamUrl());
    } catch {
      return; // SSE unavailable — the 5s poll still drives updates
    }
    this.globalEventSource = es;

    const liveRefresh = () => this.zone.run(() => {
      const now = Date.now();
      if (now - this.lastSseListRefreshMs < 1000) return;
      this.lastSseListRefreshMs = now;
      this.loadJobs();
    });
    const terminalRefresh = () => this.zone.run(() => this.loadJobs());

    es.addEventListener('started', liveRefresh);
    es.addEventListener('progress', liveRefresh);
    // Resource/model decisions (batch resizes, gate waits, KGE choices, OOM defers) arrive as
    // 'decision' events; they carry an updated snapshot so we refresh the list the same way as
    // a regular progress tick.
    es.addEventListener('decision', liveRefresh);
    es.addEventListener('completed', terminalRefresh);
    es.addEventListener('error', (e: any) => {
      // Browser transport hiccups fire 'error' with no data (auto-reconnect); our server ERROR event
      // carries data — only refresh on the latter.
      if (e && e.data) terminalRefresh();
    });
  }

  private disconnectGlobalStream(): void {
    if (this.globalEventSource) {
      this.globalEventSource.close();
      this.globalEventSource = null;
    }
  }

  loadCrawlers(): void {
    this.crawlerService.listCrawlers().subscribe({
      next: (crawlers) => {
        this.crawlers = crawlers;
        this.cdr.markForCheck();
      },
      error: (err) => {
        this.errorMessage = 'Failed to load crawlers: ' + (err.message || err.statusText);
        this.cdr.markForCheck();
      }
    });
  }

  loadJobs(): void {
    // Merge old crawler jobs and unified crawl jobs into one list
    forkJoin({
      crawlerJobs: this.crawlerService.listJobs().pipe(catchError(() => of([] as CrawlJobSummary[]))),
      unifiedJobs: this.unifiedCrawlService.listJobs(true).pipe(catchError(() => of([] as any[]))),
      distributedSessions: this.distributedCrawlService.listSessions().pipe(catchError(() => of([] as any[])))
    }).subscribe({
      next: ({ crawlerJobs, unifiedJobs, distributedSessions }) => {
        // Map unified crawl jobs to the same CrawlJobSummary shape
        const mappedUnified: CrawlJobSummary[] = (unifiedJobs || []).map((uj: any) => ({
          jobId: uj.jobId,
          crawlerId: 'unified-crawl',
          seed: uj.name || 'Unified Crawl',
          status: uj.status,
          // historyTaskId must use the "crawl-" prefix (matching JobLogService storage)
          // NOT the legacy "crawler-" prefix used for old-style crawl jobs.
          // The internalJobId field (when present after the scheduler-jobId change) holds
          // the UUID that logs are keyed under; fall back to jobId if not present.
          historyTaskId: 'crawl-' + (uj.internalJobId || uj.jobId),
          // Nested progress shape the row template binds to (job.progress.*)
          progress: {
            discovered: uj.documentsDiscovered || 0,
            processed: uj.documentsLoaded || 0,
            failed: uj.errorCount || 0,
            queued: 0,
            currentItem: uj.currentPhase || '',
            estimatedPercent: uj.progressPercent || 0
          },
          startedAt: uj.queuedAt,
          completedAt: uj.completedAt,
          // Modular pipeline-step breakdown (what ran / was skipped / archived)
          pipelineSteps: uj.pipelineSteps || [],
          // Extra unified crawl fields for the template
          currentPhase: uj.currentPhase,
          chunksCreated: uj.chunksCreated,
          chunksEmbedded: uj.chunksEmbedded,
          entitiesExtracted: uj.entitiesExtracted,
          relationshipsExtracted: uj.relationshipsExtracted,
          isUnifiedCrawl: true
        } as any));
        // Map distributed crawl sessions to the same row shape (a 3rd source alongside crawler + unified).
        const mappedDistributed: CrawlJobSummary[] = (distributedSessions || []).map((s: any) => ({
          jobId: 'distributed-' + s.sessionId,
          crawlerId: 'distributed',
          seed: s.name || 'Distributed Crawl',
          status: this.mapDistributedStatus(s.status),
          // Coordinator stores forwarded per-worker transcripts under this task id; the monitor's
          // transcript viewer reads them via getCrawlerHistoryTaskId(job).
          historyTaskId: 'crawl-distributed-' + s.sessionId,
          progress: {
            discovered: 0,
            processed: s.completedWorkers || 0,
            failed: s.failedWorkers || 0,
            queued: 0,
            currentItem: (s.completedWorkers || 0) + '/' + (s.totalWorkers || 0) + ' workers',
            estimatedPercent: 0
          },
          startedAt: s.startedAt,
          completedAt: s.completedAt,
          pipelineSteps: [], // filled from the aggregate snapshot when the Steps panel is opened
          isDistributedCrawl: true,
          sessionId: s.sessionId,
          totalWorkers: s.totalWorkers,
          completedWorkers: s.completedWorkers
        } as any));
        // Merge into existing job objects by id so Angular's ngFor trackBy keeps the same component
        // instances alive and the crawl-step-monitor expand state persists across every refresh.
        const freshAll = [...mappedDistributed, ...mappedUnified, ...crawlerJobs];
        const byId = new Map(this.jobs.map(j => [j.jobId, j]));
        this.jobs = freshAll.map(fresh => {
          const existing = byId.get(fresh.jobId);
          if (existing) { Object.assign(existing, fresh); return existing; }
          return fresh;
        });
        // Keep the open steps panel's rich detail (retries/tuning/transcripts) fresh — this runs on
        // both the 5s poll and the SSE-triggered refresh, so the monitor updates in near real time.
        this.refreshRichJob();
        this.cdr.markForCheck();
      },
      error: (err) => {
        this.errorMessage = 'Failed to load jobs: ' + (err.message || err.statusText);
        this.cdr.markForCheck();
      }
    });
  }

  startCrawl(): void {
    if (!this.seed.trim()) return;
    this.isLoading = true;
    this.errorMessage = null;

    const request: StartCrawlRequest = {
      seed: this.seed.trim(),
      maxDepth: this.maxDepth,
      maxDocuments: this.maxDocuments,
      sameDomainOnly: this.sameDomainOnly
    };
    if (this.selectedCrawlerId) {
      request.crawlerId = this.selectedCrawlerId;
    }

    this.crawlerService.startCrawl(request).subscribe({
      next: () => {
        this.isLoading = false;
        this.seed = '';
        this.loadJobs();
        this.cdr.markForCheck();
      },
      error: (err) => {
        this.isLoading = false;
        this.errorMessage = 'Failed to start crawl: ' + (err.error?.error || err.message || err.statusText);
        this.cdr.markForCheck();
      }
    });
  }

  /**
   * Open the full crawl-to-graph launcher modal (sources, graph extraction, vector indexing,
   * preprocessing, enrichment, runtime tuning, custom pipelines/routing). On submit, start the
   * assembled unified crawl — the new job then appears in the merged jobs list below.
   */
  openLauncher(): void {
    const ref = this.dialog.open(CrawlLauncherDialogComponent, {
      width: '960px',
      maxWidth: '96vw',
      maxHeight: '92vh',
      autoFocus: false,
      restoreFocus: false,
      panelClass: 'crawl-launcher-dialog-panel',
      data: { seedPath: this.seed.trim() || undefined }
    });
    ref.afterClosed().subscribe((result: CrawlLauncherResult | undefined) => {
      if (result && result.request) {
        this.startUnifiedCrawl(result.request, result.distribute);
      }
    });
  }

  /** Submit a fully-assembled unified crawl request — locally, or fanned across the live cluster. */
  private startUnifiedCrawl(request: UnifiedCrawlRequest, distribute: boolean): void {
    this.isLoading = true;
    this.errorMessage = null;
    this.cdr.markForCheck();

    if (distribute) {
      request.distribution = { partitionStrategy: 'PER_SOURCE', mergeResults: true };
    }
    const start$: Observable<any> = distribute
      ? this.distributedCrawlService.startDistributed(request)
      : this.unifiedCrawlService.startJob(request);

    start$.subscribe({
      next: () => {
        this.isLoading = false;
        this.seed = '';
        this.loadJobs();
        this.cdr.markForCheck();
      },
      error: (err: any) => {
        this.isLoading = false;
        this.errorMessage = 'Failed to start crawl: ' + (err.error?.error || err.message || err.statusText);
        this.cdr.markForCheck();
      }
    });
  }

  pauseJob(jobId: string): void {
    this.crawlerService.pauseJob(jobId).subscribe({
      next: () => this.loadJobs(),
      error: (err) => {
        this.errorMessage = 'Failed to pause job: ' + (err.error?.error || err.message);
        this.cdr.markForCheck();
      }
    });
  }

  resumeJob(jobId: string): void {
    this.crawlerService.resumeJob(jobId).subscribe({
      next: () => this.loadJobs(),
      error: (err) => {
        this.errorMessage = 'Failed to resume job: ' + (err.error?.error || err.message);
        this.cdr.markForCheck();
      }
    });
  }

  cancelJob(jobId: string): void {
    this.crawlerService.cancelJob(jobId).subscribe({
      next: () => this.loadJobs(),
      error: (err) => {
        this.errorMessage = 'Failed to cancel job: ' + (err.error?.error || err.message);
        this.cdr.markForCheck();
      }
    });
  }

  cleanupJobs(): void {
    this.crawlerService.cleanupJobs().subscribe({
      next: () => this.loadJobs(),
      error: (err) => {
        this.errorMessage = 'Failed to cleanup jobs: ' + (err.error?.error || err.message);
        this.cdr.markForCheck();
      }
    });
  }

  getStatusColor(status: string): string {
    switch (status) {
      case 'RUNNING': return 'primary';
      case 'PAUSED': return 'accent';
      case 'COMPLETED': return 'primary';
      case 'FAILED': return 'warn';
      case 'CANCELLED': return 'warn';
      case 'INTERRUPTED': return 'accent';
      default: return '';
    }
  }

  isJobActive(status: string): boolean {
    return status === 'RUNNING' || status === 'PAUSED' || status === 'PENDING' || status === 'INTERRUPTED';
  }

  getVisiblePipelines(job: CrawlJobSummary): CrawlPipelineInfo[] {
    return (job.pipelines || []).filter(pipeline => pipeline);
  }

  getPipelineProgress(pipeline: CrawlPipelineInfo): number {
    return Math.max(0, Math.min(100, pipeline.progressPercent || 0));
  }

  getPipelineTypeIcon(type: string | undefined): string {
    switch ((type || '').toUpperCase()) {
      case 'VLM': return 'visibility';
      case 'OCR': return 'document_scanner';
      case 'CODE': return 'code';
      case 'TABLE_AWARE': return 'table_chart';
      case 'KEYWORD_ONLY': return 'manage_search';
      default: return 'article';
    }
  }

  getPipelineTypeClass(type: string | undefined): string {
    return 'pipeline-type-' + (type || 'standard_text').toLowerCase().replace(/_/g, '-');
  }

  getPipelineStatusClass(status: string | undefined): string {
    return 'pipeline-status-' + (status || 'pending').toLowerCase();
  }

  getLatestPipelineTaskId(job: CrawlJobSummary): string | null {
    const latest = (job.pipelines || [])
      .map(pipeline => pipeline.latestTaskId)
      .find(taskId => !!taskId);
    return latest || null;
  }

  getCrawlerHistoryTaskId(job: CrawlJobSummary): string {
    // Logs persist under "crawl-<internalJobId>" (stable durable id). Prefer the server-provided
    // historyTaskId; otherwise build from the internal id — NOT the public jobId (which already carries
    // a "crawl-" prefix → "crawl-crawl-…" and has no persisted logs). Strip an existing prefix so the
    // fallback is never double-prefixed.
    if (job.historyTaskId) { return job.historyTaskId; }
    const raw = job.internalJobId || job.jobId || '';
    const id = raw.startsWith('crawl-') ? raw.substring('crawl-'.length) : raw;
    return `crawl-${id}`;
  }

  toggleLogs(jobId: string): void {
    this.expandedLogsJobId = this.expandedLogsJobId === jobId ? null : jobId;
  }

  toggleTranscripts(jobId: string): void {
    this.expandedTranscriptsJobId = this.expandedTranscriptsJobId === jobId ? null : jobId;
  }

  // --- Modular pipeline-step breakdown (unified crawl jobs) ---

  toggleSteps(jobId: string): void {
    this.expandedStepsJobId = this.expandedStepsJobId === jobId ? null : jobId;
    this.cmRichJob = null;
    this.cmRichJobId = null;
    if (this.expandedStepsJobId) {
      this.refreshRichJob();
    }
  }

  getJobSteps(job: CrawlJobSummary): CrawlStepInfo[] {
    return job.pipelineSteps || [];
  }

  hasSteps(job: CrawlJobSummary): boolean {
    return (job.pipelineSteps?.length || 0) > 0 || (job as any).isDistributedCrawl === true;
  }

  /** Map a DistributedCrawlSession.Status to the crawler-row status vocabulary (for the chip + colours). */
  private mapDistributedStatus(status: string): string {
    switch ((status || '').toUpperCase()) {
      case 'DISPATCHING':
      case 'RUNNING': return 'RUNNING';
      case 'COMPLETED':
      case 'PARTIALLY_COMPLETED': return 'COMPLETED';
      case 'FAILED': return 'FAILED';
      case 'CANCELLED': return 'CANCELLED';
      default: return status || 'PENDING';
    }
  }

  /** True when a job COMPLETED but some steps were intentionally skipped/archived (not a failure). */
  hasPartialCompletion(job: CrawlJobSummary): boolean {
    if (job.status !== 'COMPLETED') return false;
    return (job.pipelineSteps || []).some(s => s.status === 'SKIPPED' || s.status === 'ARCHIVED');
  }

  /** True when a non-active job has ARCHIVED or DEFERRED steps that can be resumed. */
  hasResumableSteps(job: CrawlJobSummary): boolean {
    if (this.isJobActive(job.status)) return false;
    return (job.pipelineSteps || []).some(s => {
      const st = (s.status || '').toUpperCase();
      return st === 'ARCHIVED' || st === 'DEFERRED';
    });
  }

  /** Count of ARCHIVED+DEFERRED steps for a job. */
  resumableStepCount(job: CrawlJobSummary): number {
    return (job.pipelineSteps || []).filter(s => {
      const st = (s.status || '').toUpperCase();
      return st === 'ARCHIVED' || st === 'DEFERRED';
    }).length;
  }

  /** Short "N ran · M skipped · K archived · F failed" summary of the step plan. */
  stepStatusSummary(job: CrawlJobSummary): string {
    const steps = job.pipelineSteps || [];
    if (steps.length === 0) return '';
    const n = (st: string) => steps.filter(s => (s.status || '').toUpperCase() === st).length;
    const parts: string[] = [];
    if (n('COMPLETED')) parts.push(`${n('COMPLETED')} ran`);
    if (n('RUNNING')) parts.push(`${n('RUNNING')} running`);
    if (n('SKIPPED')) parts.push(`${n('SKIPPED')} skipped`);
    if (n('ARCHIVED')) parts.push(`${n('ARCHIVED')} archived`);
    if (n('FAILED')) parts.push(`${n('FAILED')} failed`);
    return parts.join(' · ');
  }

  /** Run any terminal step now (Retry/Re-run/Run archived/Run deferred). */
  runStep(jobId: string, stepId: string): void {
    const key = `${jobId}:${stepId}`;
    this.stepActionInProgress[key] = true;
    this.cdr.markForCheck();
    this.unifiedCrawlService.runStep(jobId, stepId).subscribe({
      next: () => {
        this.stepActionInProgress[key] = false;
        this.loadJobs();
        this.cdr.markForCheck();
      },
      error: (err: any) => {
        this.stepActionInProgress[key] = false;
        this.errorMessage = 'Failed to run step ' + stepId + ': ' + (err.error?.error || err.message || err.statusText);
        this.cdr.markForCheck();
      }
    });
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

  // ─── Rich detail for the expanded steps panel (drives the shared monitor) ────

  /**
   * Lazy-fetch the full job detail for the open steps panel so the shared monitor can show the
   * per-step retries, tuning decisions, LLM transcripts and activity log (the list/summary
   * endpoint omits these). Refreshed on every loadJobs() tick (poll + SSE) so it stays live.
   */
  private refreshRichJob(): void {
    const jobId = this.expandedStepsJobId;
    if (!jobId) return;
    // Distributed sessions fetch the merged aggregate; unified jobs fetch their full detail.
    const row = this.jobs.find(j => j.jobId === jobId) as any;
    const obs = (row?.isDistributedCrawl && row.sessionId)
        ? this.distributedCrawlService.getAggregate(row.sessionId)
        : this.unifiedCrawlService.getJob(jobId);
    obs.subscribe({
      next: (detail: JobDetail) => {
        // Ignore a stale response if the user collapsed/switched panels meanwhile.
        if (this.expandedStepsJobId === jobId) {
          this.cmRichJob = detail;
          this.cmRichJobId = jobId;
          this.cdr.markForCheck();
        }
      },
      error: () => { /* non-fatal: the monitor falls back to the summary step list */ }
    });
  }

  /** The rich job object for a row — only once its steps panel is open and detail has loaded. */
  getRichJob(job: CrawlJobSummary): JobDetail | null {
    return this.cmRichJobId === job.jobId ? this.cmRichJob : null;
  }

  /** Prefer the full rich pipelineSteps (batch/timing fields) once loaded; else the summary list. */
  getMonitorSteps(job: CrawlJobSummary): any[] {
    const rich = this.getRichJob(job);
    if (rich?.pipelineSteps?.length) return rich.pipelineSteps;
    return this.getJobSteps(job);
  }

  trackByJobId(_i: number, job: CrawlJobSummary): string {
    return job.jobId;
  }
}
