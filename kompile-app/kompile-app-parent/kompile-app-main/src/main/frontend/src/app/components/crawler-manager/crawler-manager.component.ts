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

import { Component, OnInit, OnDestroy, ChangeDetectionStrategy, ChangeDetectorRef } from '@angular/core';
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
import { JobLogViewerComponent } from '../job-history/job-log-viewer/job-log-viewer.component';

import {
  CrawlerService,
  CrawlerInfo,
  CrawlJobSummary,
  CrawlPipelineInfo,
  CrawlStepInfo,
  StartCrawlRequest
} from '../../services/crawler.service';
import { UnifiedCrawlService } from '../../services/unified-crawl.service';
import { WebSocketService } from '../../services/websocket.service';
import { JobLogService, JobLogEntry } from '../../services/job-log.service';
import { Subscription, forkJoin, of } from 'rxjs';
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
    JobLogViewerComponent
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

  // New crawl form
  seed = '';
  selectedCrawlerId = '';
  maxDepth = 3;
  maxDocuments = 1000;
  sameDomainOnly = true;

  private refreshInterval: any;
  expandedLogsJobId: string | null = null;
  expandedStepsJobId: string | null = null;

  /** Per-step accordion state: key is `${jobId}::${stepId}` */
  expandedCmSteps: Set<string> = new Set<string>();

  /**
   * Persisted log cache: keyed by taskId ('crawl-<internalJobId>').
   * Stores the fetched log entries so we don't refetch on every change-detection cycle.
   * Value is null while the fetch is in-flight (shows loading indicator).
   */
  cmStepLogCache: Map<string, JobLogEntry[] | null> = new Map();

  // Live crawl progress from WebSocket
  liveCrawlProgress: Record<string, any> = {};
  private wsSubs: Subscription[] = [];

  constructor(
    private crawlerService: CrawlerService,
    private unifiedCrawlService: UnifiedCrawlService,
    private cdr: ChangeDetectorRef,
    private wsService: WebSocketService,
    private jobLogService: JobLogService
  ) {}

  ngOnInit(): void {
    this.loadCrawlers();
    this.loadJobs();
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
  }

  ngOnDestroy(): void {
    if (this.refreshInterval) {
      clearInterval(this.refreshInterval);
    }
    this.wsSubs.forEach(s => s.unsubscribe());
    this.wsService.unsubscribeFromCrawlProgress();
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
      unifiedJobs: this.unifiedCrawlService.listJobs(true).pipe(catchError(() => of([] as any[])))
    }).subscribe({
      next: ({ crawlerJobs, unifiedJobs }) => {
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
        this.jobs = [...mappedUnified, ...crawlerJobs];
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
    return job.historyTaskId || `crawler-${job.jobId}`;
  }

  toggleLogs(jobId: string): void {
    this.expandedLogsJobId = this.expandedLogsJobId === jobId ? null : jobId;
  }

  // --- Modular pipeline-step breakdown (unified crawl jobs) ---

  toggleSteps(jobId: string): void {
    this.expandedStepsJobId = this.expandedStepsJobId === jobId ? null : jobId;
  }

  getJobSteps(job: CrawlJobSummary): CrawlStepInfo[] {
    return job.pipelineSteps || [];
  }

  hasSteps(job: CrawlJobSummary): boolean {
    return (job.pipelineSteps?.length || 0) > 0;
  }

  /** Per-step status class. SKIPPED/ARCHIVED are styled neutral (not failures) in CSS. */
  getStepStatusClass(status: string | undefined): string {
    return 'cs-' + (status || 'pending').toLowerCase();
  }

  getStepIcon(stepType: string | undefined): string {
    const t = (stepType || '').toUpperCase();
    if (t.includes('IO')) return 'folder_open';
    if (t.includes('LLM')) return 'psychology';
    if (t.includes('EMBEDDING')) return 'memory';
    if (t.includes('GRAPH')) return 'hub';
    if (t.includes('CPU')) return 'settings_suggest';
    return 'schema';
  }

  /** True when a job COMPLETED but some steps were intentionally skipped/archived (not a failure). */
  hasPartialCompletion(job: CrawlJobSummary): boolean {
    if (job.status !== 'COMPLETED') return false;
    return (job.pipelineSteps || []).some(s => s.status === 'SKIPPED' || s.status === 'ARCHIVED');
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

  /** Run an ARCHIVED/DEFERRED step now (e.g. kick off batched embeddings on demand). */
  runStep(jobId: string, stepId: string): void {
    this.unifiedCrawlService.runStep(jobId, stepId).subscribe({
      next: () => this.loadJobs(),
      error: (err: any) => {
        this.errorMessage = 'Failed to run step ' + stepId + ': ' + (err.error?.error || err.message || err.statusText);
        this.cdr.markForCheck();
      }
    });
  }

  // ─── Per-step accordion (crawler-manager) ────────────────────────────────

  /** Toggle expand/collapse for a specific step within a job.
   *  On first open, lazily fetches the persisted logs for the parent job. */
  toggleCmStepExpanded(job: CrawlJobSummary, stepId: string): void {
    const key = `${job.jobId}::${stepId}`;
    if (this.expandedCmSteps.has(key)) {
      this.expandedCmSteps.delete(key);
    } else {
      this.expandedCmSteps.add(key);
      // Trigger a persisted-log fetch for this job if not already cached/in-flight
      this.fetchCmStepLogs(job);
    }
    this.cdr.markForCheck();
  }

  /** Derive the persisted-log taskId for a crawler-manager job. */
  private cmTaskId(job: CrawlJobSummary): string {
    return (job as any).historyTaskId || `crawl-${job.jobId}`;
  }

  /**
   * Fetch persisted logs for the job once and cache them.
   * Subsequent calls for the same taskId are no-ops (cache hit or in-flight).
   */
  private fetchCmStepLogs(job: CrawlJobSummary): void {
    const taskId = this.cmTaskId(job);
    if (this.cmStepLogCache.has(taskId)) return; // already fetched or in-flight
    this.cmStepLogCache.set(taskId, null); // null = loading
    this.jobLogService.getLogsForJob(taskId, { page: 0, size: 500 }).subscribe({
      next: (resp) => {
        this.cmStepLogCache.set(taskId, resp.logs || []);
        this.cdr.markForCheck();
      },
      error: () => {
        // On error store empty array so the empty-state renders instead of a spinner
        this.cmStepLogCache.set(taskId, []);
        this.cdr.markForCheck();
      }
    });
  }

  /** True while persisted logs are being fetched for a job (spinner). */
  isCmStepLogLoading(job: CrawlJobSummary): boolean {
    return this.cmStepLogCache.get(this.cmTaskId(job)) === null;
  }

  /** Returns true when the given step accordion is open. */
  isCmStepExpanded(jobId: string, stepId: string): boolean {
    return this.expandedCmSteps.has(`${jobId}::${stepId}`);
  }

  /**
   * Map a crawl stepId to the phase strings that appear in liveCrawlProgress
   * recentEvents for this step.  Falls back to the stepId itself.
   */
  cmStepIdToPhases(stepId: string): string[] {
    const upper = (stepId || '').toUpperCase();
    const phaseMap: { [key: string]: string[] } = {
      'SOURCE_LOADING':         ['LOADING'],
      'SOURCE_DISCOVERY':       ['DISCOVERING'],
      'TEXT_CONVERSION':        ['CONVERTING'],
      'DOCUMENT_PREPROCESSING': ['OCR_PROCESSING', 'CONVERTING'],
      'CONTENT_ROUTING':        ['ROUTING'],
      'RULE_GRAPH_PREP':        ['GRAPH_PREP'],
      'CHUNKING':               ['CHUNKING'],
      'GRAPH_EXTRACTION':       ['GRAPH_EXTRACTION'],
      'CRAWL_SURFACE':          ['CRAWL_SURFACE'],
      'ENTITY_RESOLUTION':      ['ENTITY_RESOLUTION'],
      'GRAPH_EDGE_CLEANUP':     ['EDGE_COMPUTATION'],
      'EMBEDDING':              ['EMBEDDING', 'VECTOR_INDEXING', 'INDEXING'],
    };
    return phaseMap[upper] || [upper];
  }

  /**
   * Return log entries for the given step, filtered by phase.
   *
   * Priority:
   *  1. Persisted logs from the durable API (available during AND after a crawl).
   *  2. Fallback to live recentEvents from the WebSocket progress update while the
   *     persisted fetch is still in-flight or returned nothing.
   *
   * Calling this method does NOT trigger a fetch — that happens inside
   * toggleCmStepExpanded so we only fetch once per job expand, not once per
   * change-detection cycle.
   */
  getCmStepEvents(job: CrawlJobSummary, step: CrawlStepInfo): any[] {
    const taskId = this.cmTaskId(job);
    const cached = this.cmStepLogCache.get(taskId);
    const phases = this.cmStepIdToPhases(step.stepId);
    const phaseSet = new Set(phases);

    if (cached !== null && cached !== undefined) {
      // Cache is populated (even if empty) — filter and return persisted entries.
      const persisted = cached.filter((e: JobLogEntry) =>
        phaseSet.has((e.source || '').toUpperCase()) ||
        phaseSet.has((e.message || '').substring(0, 30).toUpperCase()) ||
        // Primary filter: match the phase embedded in the message prefix '[PHASE]'
        phases.some(ph => (e.message || '').toUpperCase().startsWith('[' + ph + ']')) ||
        // Secondary: JobLogEntry has no phase field — use source as proxy for step type
        phaseSet.has((e.level || '').toUpperCase())
      );
      // If persisted logs exist for this job, return them (may be empty for this step).
      if (cached.length > 0) return persisted;
      // Persisted returned empty (job may be live or log storage not yet enabled) —
      // fall through to live events below.
    }

    // Fallback: live recentEvents from WebSocket progress
    const live = this.liveCrawlProgress[job.jobId];
    const events: any[] = live?.recentEvents || (job as any).recentEvents || [];
    return events.filter((e: any) => phaseSet.has((e.phase || '').toUpperCase()));
  }
}
