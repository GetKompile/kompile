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

/** Mirrors ai.kompile.core.graphrag.maintenance.model.GraphHealthSnapshot. */
export interface GraphHealthSnapshot {
  factSheetId: number;
  computedAt: string;
  nodeCount: number;
  edgeCount: number;
  nodesByType: { [type: string]: number };
  density: number;
  averageDegree: number;
  maxDegree: number;
  orphanCount: number;
  orphanRate: number;
  lowConfidenceNodeCount: number;
  lowConfidenceEdgeCount: number;
  connectedComponentCount: number;
  largestComponentFraction: number;
  ontologyBound: boolean;
  conformanceScore: number | null;
}

/** Mirrors ai.kompile.core.graphrag.maintenance.model.GraphComparison. */
export interface GraphComparison {
  factSheetIdA: number;
  factSheetIdB: number;
  sharedEntityCount: number;
  onlyInACount: number;
  onlyInBCount: number;
  sampleShared: string[];
  sampleOnlyInA: string[];
  sampleOnlyInB: string[];
  healthA: GraphHealthSnapshot;
  healthB: GraphHealthSnapshot;
}

/** Calls the /api/graph-health endpoints (graph-as-asset Phase 7). */
@Injectable({ providedIn: 'root' })
export class GraphHealthService extends BaseService {
  private readonly apiPath = '/graph-health';

  constructor(private http: HttpClient) {
    super();
  }

  /** Current health vector, computed live (not persisted). */
  getHealth(factSheetId: number): Observable<GraphHealthSnapshot> {
    return this.http.get<GraphHealthSnapshot>(`${this.backendUrl}${this.apiPath}/${factSheetId}`);
  }

  /** Compute and append a snapshot to the fact sheet's health time series. */
  takeSnapshot(factSheetId: number): Observable<GraphHealthSnapshot> {
    return this.http.post<GraphHealthSnapshot>(`${this.backendUrl}${this.apiPath}/${factSheetId}/snapshot`, {});
  }

  /** The fact sheet's persisted health time series, oldest first. */
  getHistory(factSheetId: number): Observable<GraphHealthSnapshot[]> {
    return this.http.get<GraphHealthSnapshot[]>(`${this.backendUrl}${this.apiPath}/${factSheetId}/history`);
  }

  /** Compare two fact sheets' graphs (entity-set overlap + each side's metrics). */
  compare(a: number, b: number): Observable<GraphComparison> {
    return this.http.get<GraphComparison>(`${this.backendUrl}${this.apiPath}/compare?a=${a}&b=${b}`);
  }
}
