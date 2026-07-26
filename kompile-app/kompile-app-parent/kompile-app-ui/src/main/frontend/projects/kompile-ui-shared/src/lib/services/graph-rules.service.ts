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

/** Mirrors ai.kompile.graphchangetracking.domain.GraphRuleConfig. */
export interface GraphRuleConfig {
  id?: number;
  ruleId?: string;
  name: string;
  enabled?: boolean;
  factSheetId?: number | null;
  // CHANGESET-trigger thresholds:
  minNodesCreated?: number | null;
  minEdgesCreated?: number | null;
  // Trigger + per-mutation match fields:
  triggerType?: string;          // 'CHANGESET' | 'MUTATION'
  onMutationType?: string | null; // e.g. NODE_CREATED, EDGE_DELETED
  onEntityKind?: string | null;   // NODE | EDGE
  onEntityType?: string | null;   // semantic type, e.g. PERSON
  // Action:
  actionType?: string;           // 'LOG' | 'WEBHOOK'
  actionTarget?: string | null;  // webhook URL
}

/** CRUD for the reactive graph-rules engine (/api/graph/rules) — graph-as-asset Phase 4/8. */
@Injectable({ providedIn: 'root' })
export class GraphRulesService extends BaseService {
  private readonly apiPath = '/graph/rules';

  constructor(private http: HttpClient) {
    super();
  }

  list(): Observable<GraphRuleConfig[]> {
    return this.http.get<GraphRuleConfig[]>(`${this.backendUrl}${this.apiPath}`);
  }

  create(rule: GraphRuleConfig): Observable<GraphRuleConfig> {
    return this.http.post<GraphRuleConfig>(`${this.backendUrl}${this.apiPath}`, rule);
  }

  update(ruleId: string, rule: GraphRuleConfig): Observable<GraphRuleConfig> {
    return this.http.put<GraphRuleConfig>(`${this.backendUrl}${this.apiPath}/${ruleId}`, rule);
  }

  delete(ruleId: string): Observable<void> {
    return this.http.delete<void>(`${this.backendUrl}${this.apiPath}/${ruleId}`);
  }

  setEnabled(ruleId: string, enabled: boolean): Observable<GraphRuleConfig> {
    const verb = enabled ? 'enable' : 'disable';
    return this.http.post<GraphRuleConfig>(`${this.backendUrl}${this.apiPath}/${ruleId}/${verb}`, {});
  }
}
