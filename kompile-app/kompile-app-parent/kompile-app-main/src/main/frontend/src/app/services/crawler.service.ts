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

import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { catchError } from 'rxjs/operators';
import { BaseService } from './base.service';

// ── Types ────────────────────────────────────────────────────────────────────

export interface CrawlerInfo {
  id: string;
  name: string;
  description: string;
  supportedSourceTypes: string[];
}

export interface CrawlPipelineDefinition {
  pipelineId: string;
  displayName?: string;
  pipelineType: string;
  loaderName?: string;
  chunkerName?: string;
  embeddingModelName?: string;
  chunkSize?: number;
  chunkOverlap?: number;
  keywordOnly?: boolean;
  enableVlm?: boolean;
  collectionName?: string;
}

export interface CrawlRouteRule {
  pipelineId: string;
  contentTypes?: string[];
  fileExtensions?: string[];
  urlPatterns?: string[];
  sourceTypes?: string[];
  priority?: number;
  minSizeBytes?: number;
  maxSizeBytes?: number;
}

export interface StartCrawlRequest {
  crawlerId?: string;
  seed: string;
  maxDepth?: number;
  maxDocuments?: number;
  sameDomainOnly?: boolean;
  respectRobotsTxt?: boolean;
  requestDelay?: number;
  includePatterns?: string[];
  excludePatterns?: string[];
  collectionName?: string;
  loaderName?: string;
  chunkerName?: string;
  pipelines?: CrawlPipelineDefinition[];
  routeRules?: CrawlRouteRule[];
  defaultPipelineId?: string;
}

export interface StartCrawlResponse {
  jobId: string;
  historyTaskId?: string;
  status: string;
  message: string;
  crawlerId: string;
  seed: string;
  pipelineCount: number;
  routeRuleCount: number;
}

export interface CrawlProgress {
  discovered: number;
  processed: number;
  failed: number;
  queued: number;
  currentDepth: number;
  maxDepth: number;
  currentItem: string;
  estimatedPercent: number;
}

export interface CrawlPipelineInfo {
  pipelineId: string;
  displayName: string;
  pipelineType: string;
  isDefault: boolean;
  loaderName?: string;
  chunkerName?: string;
  embeddingModelName?: string;
  keywordOnly?: boolean;
  enableVlm?: boolean;
  enableGraphExtraction?: boolean;
  routedItems?: number;
  queuedTasks?: number;
  activeTasks?: number;
  dispatchedTasks?: number;
  failedDispatches?: number;
  progressPercent?: number;
  status?: string;
  lastItem?: string;
  latestTaskId?: string;
  lastUpdatedEpochMs?: number;
}

export interface CrawlStepInfo {
  stepId: string;
  displayName?: string;
  stepType?: string;
  status?: string;            // PENDING|RUNNING|COMPLETED|FAILED|SKIPPED|ARCHIVED|DEFERRED|...
  progressPercent?: number;
  completedItems?: number;
  totalItems?: number;
  failedItems?: number;
  message?: string;
}

export interface CrawlJobSummary {
  jobId: string;
  historyTaskId?: string;
  status: string;
  crawlerId: string;
  seed: string;
  progress: CrawlProgress;
  resumedFromCheckpoint?: boolean;
  resumedUrlCount?: number;
  ingestQueue?: {
    activeThreads: number;
    poolSize: number;
    maxThreads: number;
    queuedTasks: number;
    queueCapacity: number;
    completedTasks: number;
  };
  pipelines?: CrawlPipelineInfo[];
  // Unified-crawl jobs (mapped in CrawlerManagerComponent) carry the modular pipeline-step
  // breakdown + a few summary fields so the row can show what ran / was skipped / archived.
  pipelineSteps?: CrawlStepInfo[];
  isUnifiedCrawl?: boolean;
  currentPhase?: string;
  chunksCreated?: number;
  chunksEmbedded?: number;
  entitiesExtracted?: number;
  relationshipsExtracted?: number;
}

export interface CrawlJobDetail extends CrawlJobSummary {
  pipelines?: CrawlPipelineInfo[];
}

export interface ValidateConfigResponse {
  valid: boolean;
  errors?: string[];
}

export interface SimpleResponse {
  message?: string;
  error?: string;
  removed?: number;
}

// ── Service ──────────────────────────────────────────────────────────────────

@Injectable({
  providedIn: 'root'
})
export class CrawlerService extends BaseService {

  constructor(private http: HttpClient) {
    super();
  }

  listCrawlers(): Observable<CrawlerInfo[]> {
    return this.http.get<CrawlerInfo[]>(`${this.backendUrl}/crawlers`)
      .pipe(catchError(this.handleError));
  }

  startCrawl(request: StartCrawlRequest): Observable<StartCrawlResponse> {
    return this.http.post<StartCrawlResponse>(`${this.backendUrl}/crawlers/start`, request)
      .pipe(catchError(this.handleError));
  }

  validateConfig(request: StartCrawlRequest): Observable<ValidateConfigResponse> {
    return this.http.post<ValidateConfigResponse>(`${this.backendUrl}/crawlers/validate`, request)
      .pipe(catchError(this.handleError));
  }

  listJobs(): Observable<CrawlJobSummary[]> {
    return this.http.get<CrawlJobSummary[]>(`${this.backendUrl}/crawlers/jobs`)
      .pipe(catchError(this.handleError));
  }

  listActiveJobs(): Observable<CrawlJobSummary[]> {
    return this.http.get<CrawlJobSummary[]>(`${this.backendUrl}/crawlers/jobs/active`)
      .pipe(catchError(this.handleError));
  }

  getJob(jobId: string): Observable<CrawlJobDetail> {
    return this.http.get<CrawlJobDetail>(`${this.backendUrl}/crawlers/jobs/${jobId}`)
      .pipe(catchError(this.handleError));
  }

  pauseJob(jobId: string): Observable<SimpleResponse> {
    return this.http.post<SimpleResponse>(`${this.backendUrl}/crawlers/jobs/${jobId}/pause`, {})
      .pipe(catchError(this.handleError));
  }

  resumeJob(jobId: string): Observable<SimpleResponse> {
    return this.http.post<SimpleResponse>(`${this.backendUrl}/crawlers/jobs/${jobId}/resume`, {})
      .pipe(catchError(this.handleError));
  }

  cancelJob(jobId: string): Observable<SimpleResponse> {
    return this.http.post<SimpleResponse>(`${this.backendUrl}/crawlers/jobs/${jobId}/cancel`, {})
      .pipe(catchError(this.handleError));
  }

  cleanupJobs(): Observable<SimpleResponse> {
    return this.http.post<SimpleResponse>(`${this.backendUrl}/crawlers/jobs/cleanup`, {})
      .pipe(catchError(this.handleError));
  }

  private handleError(error: any): Observable<never> {
    console.error('CrawlerService error:', error);
    throw error;
  }
}
