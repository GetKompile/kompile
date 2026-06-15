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
import { environment } from '../../environments/environment';

export interface SessionMetricsSummary {
  sessionId: string;
  started: string;
  ended: string;
  durationSeconds: number;
  provider: string;
  model: string;
  agent: string;
  ragEnabled: boolean;

  userTurns: number;
  assistantTurns: number;
  totalTurns: number;

  inputTokens: number;
  outputTokens: number;
  cacheReadTokens: number;
  cacheCreationTokens: number;

  apiCalls: number;
  avgResponseTimeMs: number;

  totalToolCalls: number;
  totalToolErrors: number;
  toolBreakdown: Record<string, number>;

  agenticSteps: number;
  compactions: number;
  thinkingTokens: number;
  subagentsSpawned: number;
}

export interface AggregatedStats {
  totalSessions: number;
  tokens: {
    totalInput: number;
    totalOutput: number;
    total: number;
    cacheRead: number;
    cacheCreation: number;
  };
  tools: {
    totalCalls: number;
    totalErrors: number;
  };
  turns: {
    user: number;
    assistant: number;
    total: number;
  };
  totalDurationSeconds: number;
  tokensByProvider: Record<string, number>;
  tokensByModel: Record<string, number>;
  tokensByAgent: Record<string, number>;
  toolBreakdown: Record<string, number>;
  providerSessionCount?: number;
  providerTotalInput?: number;
  providerTotalOutput?: number;
}

export interface ProviderUsageEntry {
  sessionId: string;
  source: string;
  provider: string;
  model: string;
  projectDirectory: string;
  inputTokens: number;
  outputTokens: number;
  totalTokens: number;
  cacheReadTokens: number;
  cacheCreationTokens: number;
  thinkingTokens: number;
  apiCalls: number;
  indexedAt: string;
}

export interface ProviderUsageStats {
  totalSessions: number;
  totalInputTokens: number;
  totalOutputTokens: number;
  totalTokens: number;
  byProvider: Record<string, {
    sessionCount: number;
    inputTokens: number;
    outputTokens: number;
    totalTokens: number;
    cacheReadTokens: number;
    cacheCreationTokens: number;
    thinkingTokens: number;
    apiCalls: number;
    byModel: Record<string, number>;
    byProject: Record<string, number>;
    sessions: ProviderUsageEntry[];
  }>;
}

export interface ProjectBreakdown {
  projects: Record<string, {
    sessionCount: number;
    tokens: { input: number; output: number; total: number; cacheRead: number };
    toolCalls: number;
    sessions: SessionMetricsSummary[];
  }>;
  totalProjects: number;
  totalSessions: number;
}

@Injectable({ providedIn: 'root' })
export class SessionMetricsService {
  private baseUrl = `${environment.apiUrl}/session-metrics`;

  constructor(private http: HttpClient) {}

  listAll(): Observable<SessionMetricsSummary[]> {
    return this.http.get<SessionMetricsSummary[]>(this.baseUrl);
  }

  getStats(): Observable<AggregatedStats> {
    return this.http.get<AggregatedStats>(`${this.baseUrl}/stats`);
  }

  getByProject(): Observable<ProjectBreakdown> {
    return this.http.get<ProjectBreakdown>(`${this.baseUrl}/by-project`);
  }

  getProviderUsage(): Observable<ProviderUsageStats> {
    return this.http.get<ProviderUsageStats>(`${this.baseUrl}/provider-usage`);
  }

  getBySessionId(sessionId: string): Observable<SessionMetricsSummary> {
    return this.http.get<SessionMetricsSummary>(`${this.baseUrl}/${sessionId}`);
  }
}
