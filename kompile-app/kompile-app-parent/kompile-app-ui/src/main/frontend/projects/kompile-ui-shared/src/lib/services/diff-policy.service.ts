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
import { HttpClient, HttpParams } from '@angular/common/http';
import { Observable } from 'rxjs';
import { backendUrl } from './base.service';

export interface PathRule {
  glob: string;
  severity: string;
  description: string;
}

export interface DiffPolicyRules {
  pathRules: PathRule[];
  contentRulesText: string;
  /** Whether an LLM judge backend is wired (enables the LLM scan toggle). */
  llmAvailable?: boolean;
}

/** A policy violation found in a captured agent file-change. */
export interface DiffPolicyViolation {
  id: string;
  diffEntryId: string;
  detector: string;      // path | rule | llm
  ruleId: string;
  severity: string;      // info | warning | error | critical
  riskScore: number;
  agent: string;
  source: string;
  sessionId: string;
  filePath: string;
  lineNumber: number;
  matchedLine: string | null;
  message: string;
  toolName: string;
  timestamp: string;
  detectedAt: string;
}

export interface ViolationQuery {
  filePath?: string;
  agent?: string;
  sessionId?: string;
  detector?: string;
  severity?: string;
  since?: string;
  until?: string;
  limit?: number;
}

export interface ScanParams {
  agent?: string;
  filePath?: string;
  sessionId?: string;
  since?: string;
  until?: string;
  limit?: number;
  useLlm?: boolean;
}

@Injectable({ providedIn: 'root' })
export class DiffPolicyService {

  private readonly apiUrl = `${backendUrl}/diff-policy`;

  constructor(private http: HttpClient) {}

  getRules(): Observable<DiffPolicyRules> {
    return this.http.get<DiffPolicyRules>(`${this.apiUrl}/rules`);
  }

  saveRules(rules: DiffPolicyRules): Observable<Record<string, unknown>> {
    return this.http.put<Record<string, unknown>>(`${this.apiUrl}/rules`, rules);
  }

  scan(params: ScanParams): Observable<Record<string, unknown>> {
    let p = new HttpParams();
    if (params.agent) p = p.set('agent', params.agent);
    if (params.filePath) p = p.set('filePath', params.filePath);
    if (params.sessionId) p = p.set('sessionId', params.sessionId);
    if (params.since) p = p.set('since', params.since);
    if (params.until) p = p.set('until', params.until);
    if (params.limit) p = p.set('limit', params.limit.toString());
    if (params.useLlm) p = p.set('useLlm', 'true');
    return this.http.post<Record<string, unknown>>(`${this.apiUrl}/scan`, null, { params: p });
  }

  listViolations(q: ViolationQuery): Observable<DiffPolicyViolation[]> {
    let p = new HttpParams();
    if (q.filePath) p = p.set('filePath', q.filePath);
    if (q.agent) p = p.set('agent', q.agent);
    if (q.sessionId) p = p.set('sessionId', q.sessionId);
    if (q.detector) p = p.set('detector', q.detector);
    if (q.severity) p = p.set('severity', q.severity);
    if (q.since) p = p.set('since', q.since);
    if (q.until) p = p.set('until', q.until);
    if (q.limit) p = p.set('limit', q.limit.toString());
    return this.http.get<DiffPolicyViolation[]>(`${this.apiUrl}/violations`, { params: p });
  }

  stats(): Observable<Record<string, unknown>> {
    return this.http.get<Record<string, unknown>>(`${this.apiUrl}/stats`);
  }

  clearViolations(): Observable<Record<string, unknown>> {
    return this.http.delete<Record<string, unknown>>(`${this.apiUrl}/violations`);
  }
}
