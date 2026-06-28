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
import { forkJoin } from 'rxjs';
import { catchError, finalize } from 'rxjs/operators';
import { of } from 'rxjs';
import {
  JobHistoryService,
  ResumableJobSummary,
  ResumableCrawlJob,
  ResumableUnifiedCrawlJob,
  UnifiedCrawlStepProgress
} from '../../services/job-history.service';

@Component({
  standalone: false,
  selector: 'app-job-resume',
  templateUrl: './job-resume.component.html',
  styleUrls: ['./job-resume.component.css'],
  changeDetection: ChangeDetectionStrategy.OnPush
})
export class JobResumeComponent implements OnInit, OnDestroy {
  resumableIngestJobs: ResumableJobSummary[] = [];
  resumableCrawlJobs: ResumableCrawlJob[] = [];
  /** Unified crawl jobs with archived/deferred steps, loaded from /api/unified-crawl/jobs/resumable */
  resumableUnifiedJobs: ResumableUnifiedCrawlJob[] = [];
  loading = false;
  error: string | null = null;
  resumingTaskId: string | null = null;
  /** Step-level: jobId+stepId combo being resumed right now */
  resumingStep: { jobId: string; stepId: string } | null = null;
  /** Step success notices: { jobId, stepId, timestamp } */
  resumedStepNotices: { jobId: string; stepId: string; timestamp: number }[] = [];
  /** Tracks recently resumed jobs with their new task IDs for success feedback */
  recentlyResumed: { originalId: string; newTaskId: string; type: 'ingest' | 'crawl'; timestamp: number }[] = [];
  checkpointDetail: any = null;
  private refreshInterval: any;

  constructor(
    private jobHistoryService: JobHistoryService,
    private cdr: ChangeDetectorRef
  ) {}

  ngOnInit(): void {
    this.loadAll();
    this.refreshInterval = setInterval(() => this.loadAll(), 30000);
  }

  ngOnDestroy(): void {
    if (this.refreshInterval) {
      clearInterval(this.refreshInterval);
    }
  }

  loadAll(): void {
    this.loading = true;
    this.error = null;

    forkJoin({
      ingest: this.jobHistoryService.listResumableIngestJobs().pipe(catchError(() => of([] as ResumableJobSummary[]))),
      crawl: this.jobHistoryService.listResumableCrawlJobs().pipe(catchError(() => of([] as ResumableCrawlJob[]))),
      unified: this.jobHistoryService.listResumableUnifiedCrawlJobs().pipe(catchError(() => of([] as ResumableUnifiedCrawlJob[])))
    }).subscribe({
      next: ({ ingest, crawl, unified }) => {
        this.resumableIngestJobs = ingest;
        this.resumableCrawlJobs = crawl;
        // Preserve expansion/loaded state across refreshes
        this.resumableUnifiedJobs = unified.map(incoming => {
          const existing = this.resumableUnifiedJobs.find(j => j.jobId === incoming.jobId);
          return existing
            ? { ...incoming, steps: existing.steps, stepsLoaded: existing.stepsLoaded, expanded: existing.expanded }
            : { ...incoming, expanded: false, stepsLoaded: false };
        });
        this.loading = false;
        this.cdr.markForCheck();
      },
      error: () => {
        this.loading = false;
        this.cdr.markForCheck();
      }
    });
  }

  /** Toggle step detail panel for a unified crawl job; lazy-loads steps on first open. */
  toggleUnifiedJobExpanded(job: ResumableUnifiedCrawlJob): void {
    job.expanded = !job.expanded;
    if (job.expanded && !job.stepsLoaded) {
      this.loadUnifiedJobSteps(job);
    }
    this.cdr.markForCheck();
  }

  /** Load per-step detail for a unified crawl job from the job detail endpoint. */
  loadUnifiedJobSteps(job: ResumableUnifiedCrawlJob): void {
    this.jobHistoryService.getUnifiedCrawlJobDetail(job.jobId).subscribe({
      next: (detail) => {
        job.steps = detail.pipelineSteps || [];
        job.stepsLoaded = true;
        this.cdr.markForCheck();
      },
      error: () => {
        job.stepsLoaded = true; // mark as loaded even on error to avoid infinite retries
        this.cdr.markForCheck();
      }
    });
  }

  /** Check if a step is resumable (ARCHIVED or DEFERRED status). */
  isStepResumable(step: UnifiedCrawlStepProgress): boolean {
    const s = (step.status || '').toUpperCase();
    return s === 'ARCHIVED' || s === 'DEFERRED' || s === 'FAILED';
  }

  /** Get steps that are resumable for a given unified crawl job. */
  getResumableSteps(job: ResumableUnifiedCrawlJob): UnifiedCrawlStepProgress[] {
    return (job.steps || []).filter(s => this.isStepResumable(s));
  }

  /** True if any step for this job is currently being resumed. */
  isResumingAnyStep(job: ResumableUnifiedCrawlJob): boolean {
    return this.resumingStep?.jobId === job.jobId;
  }

  /** True if a specific step is currently being resumed. */
  isResumingStep(jobId: string, stepId: string): boolean {
    return this.resumingStep?.jobId === jobId && this.resumingStep?.stepId === stepId;
  }

  /** Resume a single archived/deferred step for a unified crawl job. */
  resumeUnifiedStep(job: ResumableUnifiedCrawlJob, step: UnifiedCrawlStepProgress): void {
    if (this.resumingStep) return; // already resuming something
    this.resumingStep = { jobId: job.jobId, stepId: step.stepId };
    this.error = null;
    this.cdr.markForCheck();

    this.jobHistoryService.runUnifiedCrawlStep(job.jobId, step.stepId)
      .pipe(finalize(() => {
        this.resumingStep = null;
        this.cdr.markForCheck();
      }))
      .subscribe({
        next: () => {
          // Record success notice
          this.resumedStepNotices.push({ jobId: job.jobId, stepId: step.stepId, timestamp: Date.now() });
          // Reload steps to reflect new status
          job.stepsLoaded = false;
          this.loadUnifiedJobSteps(job);
          this.cdr.markForCheck();
          // Auto-dismiss success notice after 15s
          setTimeout(() => {
            this.resumedStepNotices = this.resumedStepNotices.filter(
              n => !(n.jobId === job.jobId && n.stepId === step.stepId)
            );
            this.cdr.markForCheck();
          }, 15000);
        },
        error: (err) => {
          this.error = err?.error?.message || err?.message || `Failed to resume step ${step.stepId}`;
          this.cdr.markForCheck();
        }
      });
  }

  /** Resume ALL resumable steps for a job in sequence (fire-and-forget for each). */
  resumeAllSteps(job: ResumableUnifiedCrawlJob): void {
    const resumable = this.getResumableSteps(job);
    if (!resumable.length || this.resumingStep) return;
    // Start with first; user can click again for subsequent ones
    this.resumeUnifiedStep(job, resumable[0]);
  }

  dismissStepNotice(jobId: string, stepId: string): void {
    this.resumedStepNotices = this.resumedStepNotices.filter(
      n => !(n.jobId === jobId && n.stepId === stepId)
    );
    this.cdr.markForCheck();
  }

  hasStepNotice(jobId: string, stepId: string): boolean {
    return this.resumedStepNotices.some(n => n.jobId === jobId && n.stepId === stepId);
  }

  getStepStatusClass(status: string): string {
    const s = (status || '').toUpperCase();
    if (s === 'COMPLETED') return 'step-completed';
    if (s === 'FAILED') return 'step-failed';
    if (s === 'RUNNING') return 'step-running';
    if (s === 'ARCHIVED' || s === 'DEFERRED') return 'step-archived';
    return 'step-pending';
  }

  getStepStatusIcon(status: string): string {
    const s = (status || '').toUpperCase();
    if (s === 'COMPLETED') return 'check_circle';
    if (s === 'FAILED') return 'error';
    if (s === 'RUNNING') return 'sync';
    if (s === 'ARCHIVED') return 'archive';
    if (s === 'DEFERRED') return 'hourglass_empty';
    return 'radio_button_unchecked';
  }

  getStepTypeIcon(stepType: string): string {
    const t = (stepType || '').toUpperCase();
    if (t.includes('IO')) return 'folder_open';
    if (t.includes('CPU')) return 'settings_suggest';
    if (t.includes('LLM')) return 'psychology';
    if (t.includes('GRAPH_CONSTRUCTOR')) return 'account_tree';
    if (t.includes('GRAPH')) return 'hub';
    if (t.includes('EMBEDDING')) return 'memory';
    if (t.includes('ENRICH')) return 'auto_awesome';
    return 'schema';
  }

  /** Build a synthetic step object from just a step ID (used for archived IDs fallback). */
  makeSyntheticStep(stepId: string): UnifiedCrawlStepProgress {
    return { stepId, displayName: stepId, stepType: '', status: 'ARCHIVED', progressPercent: 0, totalItems: 0, completedItems: 0, failedItems: 0 };
  }

  resumeIngestJob(taskId: string): void {
    this.resumingTaskId = taskId;
    this.error = null;
    this.jobHistoryService.resumeIngestJob(taskId).subscribe({
      next: (result) => {
        this.resumingTaskId = null;
        this.recentlyResumed.push({
          originalId: taskId,
          newTaskId: result.newTaskId,
          type: 'ingest',
          timestamp: Date.now()
        });
        this.loadAll();
        this.cdr.markForCheck();
        // Auto-dismiss after 15 seconds
        setTimeout(() => {
          this.recentlyResumed = this.recentlyResumed.filter(r => r.originalId !== taskId);
          this.cdr.markForCheck();
        }, 15000);
      },
      error: (err) => {
        this.resumingTaskId = null;
        this.error = err?.error?.error || err?.message || 'Failed to resume job';
        this.cdr.markForCheck();
      }
    });
  }

  resumeCrawlJob(jobId: string): void {
    this.resumingTaskId = jobId;
    this.error = null;
    this.jobHistoryService.restartCrawlJob(jobId).subscribe({
      next: (result: any) => {
        this.resumingTaskId = null;
        this.recentlyResumed.push({
          originalId: jobId,
          newTaskId: result.jobId || result.newTaskId || 'unknown',
          type: 'crawl',
          timestamp: Date.now()
        });
        this.loadAll();
        this.cdr.markForCheck();
        setTimeout(() => {
          this.recentlyResumed = this.recentlyResumed.filter(r => r.originalId !== jobId);
          this.cdr.markForCheck();
        }, 15000);
      },
      error: (err) => {
        this.resumingTaskId = null;
        this.error = err?.error?.error || err?.message || 'Failed to resume crawl';
        this.cdr.markForCheck();
      }
    });
  }

  dismissResumeNotice(originalId: string): void {
    this.recentlyResumed = this.recentlyResumed.filter(r => r.originalId !== originalId);
    this.cdr.markForCheck();
  }

  getProgressPercent(job: ResumableJobSummary): number {
    if (!job.totalChunks || job.totalChunks === 0) return 0;
    return Math.round(((job.chunksIndexed || 0) / job.totalChunks) * 100);
  }

  formatTimestamp(ts: string | undefined | null): string {
    if (!ts) return '-';
    try {
      return new Date(ts).toLocaleString();
    } catch {
      return ts;
    }
  }

  getStatusClass(status: string): string {
    switch (status) {
      case 'FAILED': return 'status-error';
      case 'MEMORY_KILLED': return 'status-error';
      case 'CANCELLED': return 'status-warn';
      case 'PAUSED': return 'status-paused';
      case 'INTERRUPTED': return 'status-warn';
      default: return '';
    }
  }

  getStatusIcon(status: string): string {
    switch (status) {
      case 'FAILED': return 'error';
      case 'MEMORY_KILLED': return 'memory';
      case 'CANCELLED': return 'cancel';
      case 'PAUSED': return 'pause_circle';
      case 'INTERRUPTED': return 'power_off';
      default: return 'help';
    }
  }

  viewCheckpoint(taskId: string): void {
    this.checkpointDetail = null;
    this.jobHistoryService.getIngestCheckpointStatus(taskId).subscribe({
      next: (detail) => {
        this.checkpointDetail = { ...detail, taskId };
        this.cdr.markForCheck();
      },
      error: (err) => {
        this.error = err?.error?.error || err?.message || 'Failed to load checkpoint';
        this.cdr.markForCheck();
      }
    });
  }

  get hasAnyJobs(): boolean {
    return this.resumableIngestJobs.length > 0
      || this.resumableCrawlJobs.length > 0
      || this.resumableUnifiedJobs.length > 0;
  }
}
