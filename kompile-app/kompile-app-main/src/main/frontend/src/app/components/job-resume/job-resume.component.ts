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
import { catchError } from 'rxjs/operators';
import { of } from 'rxjs';
import {
  JobHistoryService,
  ResumableJobSummary,
  ResumableCrawlJob
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
  loading = false;
  error: string | null = null;
  resumingTaskId: string | null = null;
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
      crawl: this.jobHistoryService.listResumableCrawlJobs().pipe(catchError(() => of([] as ResumableCrawlJob[])))
    }).subscribe({
      next: ({ ingest, crawl }) => {
        this.resumableIngestJobs = ingest;
        this.resumableCrawlJobs = crawl;
        this.loading = false;
        this.cdr.markForCheck();
      },
      error: () => {
        this.loading = false;
        this.cdr.markForCheck();
      }
    });
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
    return this.resumableIngestJobs.length > 0 || this.resumableCrawlJobs.length > 0;
  }
}
