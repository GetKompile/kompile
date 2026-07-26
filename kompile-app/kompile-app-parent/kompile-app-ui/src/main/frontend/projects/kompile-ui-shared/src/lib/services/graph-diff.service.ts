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

/** An added/removed entity. Mirrors TemporalGraphQueryService.EntityRef. */
export interface EntityRef {
  entityKind: string;
  entityId: string;
  entityType?: string;
  label?: string;
}

/** One attribute that differs. Mirrors TemporalGraphQueryService.AttributeChange. */
export interface AttributeChange {
  attribute: string;
  before?: string;
  after?: string;
}

/** An entity present at both times whose attributes differ. Mirrors EntityChange. */
export interface EntityChange {
  entityKind: string;
  entityId: string;
  entityType?: string;
  label?: string;
  changes: AttributeChange[];
}

/** Mirrors TemporalGraphQueryService.EntityDiff (semantic entity-level diff). */
export interface EntityDiff {
  factSheetId: number;
  from: string;
  to: string;
  added: EntityRef[];
  removed: EntityRef[];
  changed: EntityChange[];
  addedCount: number;
  removedCount: number;
  changedCount: number;
}

/** Semantic entity-level diff of a fact sheet's graph over a time window (graph-as-asset Phase 5/8). */
@Injectable({ providedIn: 'root' })
export class GraphDiffService extends BaseService {
  private readonly apiPath = '/graph/changes';

  constructor(private http: HttpClient) {
    super();
  }

  /** from/to are ISO local date-times (e.g. 2026-06-20T06:00). */
  semanticDiff(factSheetId: number, from: string, to: string): Observable<EntityDiff> {
    return this.http.get<EntityDiff>(
      `${this.backendUrl}${this.apiPath}/fact-sheets/${factSheetId}/semantic-diff`,
      { params: { from, to } });
  }
}
