/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { BaseService } from './base.service';

/** Mirrors ai.kompile.graphchangetracking.domain.GraphUpdatePipelineConfig. */
export interface GraphUpdatePipelineConfig {
  id?: number;
  pipelineId?: string;
  pipelineName: string;
  enabled?: boolean;
  /** Comma-separated channel names (matched LIKE %channel%), e.g. "slack,email". */
  triggerChannels?: string | null;
  triggerEventTypes?: string | null;
  /** Optional JSON map of message filters. */
  filterJson?: string | null;
  targetFactSheetId?: number | null;
  /** JSON array of steps: [{ "step": "EXTRACT_GRAPH", "params": {} }]. */
  processingSteps?: string | null;
  requireApproval?: boolean;
  priority?: number;
}

/**
 * CRUD for channel→graph update pipelines (/api/graph/pipelines) — the ingest half of reactive
 * graphs (graph-as-asset Phase 2/8): an inbound channel message runs the configured steps to update
 * the graph without a full crawl.
 */
@Injectable({ providedIn: 'root' })
export class GraphPipelinesService extends BaseService {
  private readonly apiPath = '/graph/pipelines';

  constructor(private http: HttpClient) {
    super();
  }

  list(): Observable<GraphUpdatePipelineConfig[]> {
    return this.http.get<GraphUpdatePipelineConfig[]>(`${this.backendUrl}${this.apiPath}`);
  }

  create(config: GraphUpdatePipelineConfig): Observable<GraphUpdatePipelineConfig> {
    return this.http.post<GraphUpdatePipelineConfig>(`${this.backendUrl}${this.apiPath}`, config);
  }

  update(pipelineId: string, config: GraphUpdatePipelineConfig): Observable<GraphUpdatePipelineConfig> {
    return this.http.put<GraphUpdatePipelineConfig>(`${this.backendUrl}${this.apiPath}/${pipelineId}`, config);
  }

  delete(pipelineId: string): Observable<void> {
    return this.http.delete<void>(`${this.backendUrl}${this.apiPath}/${pipelineId}`);
  }

  setEnabled(pipelineId: string, enabled: boolean): Observable<GraphUpdatePipelineConfig> {
    const verb = enabled ? 'enable' : 'disable';
    return this.http.post<GraphUpdatePipelineConfig>(`${this.backendUrl}${this.apiPath}/${pipelineId}/${verb}`, {});
  }
}
