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
import { BaseService } from './base.service';
import { JobDetail } from './unified-crawl.service';

/** Per-worker summary in a distributed crawl session (from DistributedCrawlSession.toSnapshot). */
export interface DistributedWorkerView {
  workerId: string;
  status: string;
  externalRef?: string;
  sourceCount?: number;
  sourceLabels?: string[];
  startedAt?: string;
  completedAt?: string;
  errorMessage?: string;
}

/** Lightweight distributed-crawl session summary (GET /sessions). */
export interface DistributedCrawlSessionSummary {
  sessionId: string;
  status: string;            // DISPATCHING | RUNNING | COMPLETED | PARTIALLY_COMPLETED | FAILED | CANCELLED
  name?: string;
  totalWorkers: number;
  completedWorkers: number;
  failedWorkers: number;
  startedAt?: string;
  completedAt?: string;
  elapsedMs?: number;
  workers?: DistributedWorkerView[];
  errors?: string[];
}

@Injectable({ providedIn: 'root' })
export class DistributedCrawlService extends BaseService {

  constructor(private http: HttpClient) {
    super();
  }

  listSessions(): Observable<DistributedCrawlSessionSummary[]> {
    return this.http.get<DistributedCrawlSessionSummary[]>(`${this.backendUrl}/distributed-crawl/sessions`);
  }

  /**
   * Start a distributed crawl. {@code request} is a UnifiedCrawlRequest (the same shape the normal crawler
   * sends) plus a {@code distribution} config; the coordinator partitions the sources across cluster workers
   * and returns the new session summary. Requires a cluster (>=1 live worker) on the orchestrator.
   */
  startDistributed(request: any): Observable<DistributedCrawlSessionSummary> {
    return this.http.post<DistributedCrawlSessionSummary>(
      `${this.backendUrl}/distributed-crawl/start`, request);
  }

  /** The merged per-worker ProgressSnapshot (shape matches a unified JobDetail) for the step monitor. */
  getAggregate(sessionId: string): Observable<JobDetail> {
    return this.http.get<JobDetail>(`${this.backendUrl}/distributed-crawl/sessions/${sessionId}`,
      { params: { aggregate: 'true' } });
  }

  cancelSession(sessionId: string): Observable<any> {
    return this.http.post<any>(`${this.backendUrl}/distributed-crawl/sessions/${sessionId}/cancel`, {});
  }

  /** Live cluster workers (GET /cluster/workers); the UI uses the count to gate the "distribute" affordance. */
  liveWorkers(): Observable<any[]> {
    return this.http.get<any[]>(`${this.backendUrl}/cluster/workers`);
  }

  /** Per-session SSE stream id (the coordinator republishes aggregate progress under this job id). */
  distributedEventsStreamUrl(sessionId: string): string {
    return `${this.backendUrl}/crawl-events/stream/distributed-${sessionId}`;
  }
}
