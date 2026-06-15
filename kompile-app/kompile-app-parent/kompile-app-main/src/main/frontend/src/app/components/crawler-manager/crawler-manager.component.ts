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
  StartCrawlRequest
} from '../../services/crawler.service';
import { WebSocketService } from '../../services/websocket.service';
import { Subscription } from 'rxjs';

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

  // Live crawl progress from WebSocket
  liveCrawlProgress: Record<string, any> = {};
  private wsSubs: Subscription[] = [];

  constructor(
    private crawlerService: CrawlerService,
    private cdr: ChangeDetectorRef,
    private wsService: WebSocketService
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
    this.crawlerService.listJobs().subscribe({
      next: (jobs) => {
        this.jobs = jobs;
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
}
