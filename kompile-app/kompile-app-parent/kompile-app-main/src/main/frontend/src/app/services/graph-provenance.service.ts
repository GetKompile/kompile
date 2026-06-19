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

/** The provenance view of a node (GraphProvenanceKeys.describe): structural lineage + reserved keys. */
export interface NodeProvenance {
  nodeId: string;
  externalId?: string;
  nodeType?: string;
  sourceNodeId?: string;
  sourceExternalId?: string;
  sourceTitle?: string;
  sourceType?: string;
  occurredAt?: string;
  observedAt?: string;
  createdAt?: string;
  /** Reserved keys (leading underscore stripped): source, sourceDocumentId, sourceChunkId, crawlRunId, extractionModel, extractedAt, extractionLogId. */
  provenance: { [key: string]: any };
}

/** Result of a provenance purge (matched/deleted counts + dryRun flag). */
export interface ProvenancePurgeResult {
  [key: string]: any;
}

/**
 * Node provenance lineage + purge-by-source (graph-as-asset Phase 3/8) over /api/knowledge-graph:
 * trace a node to its source document / chunk / crawl run / extraction model, and purge all facts
 * from a crawl run or source document.
 */
@Injectable({ providedIn: 'root' })
export class GraphProvenanceService extends BaseService {
  private readonly apiPath = '/knowledge-graph';

  constructor(private http: HttpClient) {
    super();
  }

  getProvenance(nodeId: string): Observable<NodeProvenance> {
    return this.http.get<NodeProvenance>(
      `${this.backendUrl}${this.apiPath}/nodes/${encodeURIComponent(nodeId)}/provenance`);
  }

  /** Purge nodes whose provenance matches a crawl run or source document (dryRun previews the count). */
  purge(opts: { crawlRunId?: string; sourceDocumentId?: string; factSheetId?: number | null; dryRun: boolean }):
      Observable<ProvenancePurgeResult> {
    const params: { [k: string]: string | number | boolean } = { dryRun: opts.dryRun };
    if (opts.crawlRunId) {
      params['crawlRunId'] = opts.crawlRunId;
    }
    if (opts.sourceDocumentId) {
      params['sourceDocumentId'] = opts.sourceDocumentId;
    }
    if (opts.factSheetId != null) {
      params['factSheetId'] = opts.factSheetId;
    }
    return this.http.delete<ProvenancePurgeResult>(`${this.backendUrl}${this.apiPath}/provenance`, { params });
  }
}
