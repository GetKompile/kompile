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
import { BehaviorSubject, Observable, Subject } from 'rxjs';
import { BaseService } from './base.service';

export interface EnforcerSession {
  sessionId: string;
  agentName: string;
  enabled: boolean;
  active: boolean;
  score: number;
  corrections: number;
  maxCorrections: number;
  totalTurns: number;
  violations: number;
  judgeBackend: string;
  startedAt: string;
  workingDirectory: string;
  codingProjectId?: string;
  rules?: string;
  events?: EnforcerEvent[];
}

export interface EnforcerProcess {
  processId: string;
  kind: 'judge' | 'enforcer' | string;
  sessionId: string;
  agentName: string;
  judgeBackend: string;
  active: boolean;
  enabled: boolean;
  startedAt: string;
  workingDirectory: string;
  codingProjectId?: string;
  owner: string;
  pid: number;
  description: string;
}

export interface EnforcerEvent {
  eventId: string;
  timestamp: string;
  type: string;
  severity: string;
  score: number;
  violations: string[];
  reason: string;
  correctionPrompt: string;
  action: string;
}

export interface CreateSessionRequest {
  agentName: string;
  rules: string;
  maxCorrections?: number;
  judgeBackend?: string;
  workingDirectory?: string;
  skipPermissions?: boolean;
  injectMcpTools?: boolean;
  codingProjectId?: string;
}

export interface EnforcerConfigResponse {
  source: 'code-project' | 'project' | 'none';
  codingProjectId: string;
  configPath?: string;
  config?: EnforcerJudgeConfig;
}

export interface EnforcerMetricsSummary {
  codingProjectId: string;
  agentName: string;
  totalSessions: number;
  totalViolations: number;
  totalInterruptions: number;
  lastScore: number;
  avgScore: number;
  lastSessionId: string;
  lastUpdated: string;
}

export interface EnforcerMetricsDetail extends EnforcerMetricsSummary {
  scoreCount: number;
  totalScoreSum: number;
  history: MetricHistoryEvent[];
}

export interface MetricHistoryEvent {
  timestamp: string;
  sessionId: string;
  type: string;
  eventType: string;
  score: number;
  reason: string;
  violations: string[];
}

export interface EnforcerJudgeConfig {
  agent?: string;
  skipPermissions?: boolean;
  injectTools?: boolean;
  injectSkills?: boolean;
  ruleFile?: string;
  inlineRules?: string;
  maxCorrections?: number;
  keywordMode?: boolean;
  archiveDiffs?: boolean;
  autoRollbackOnViolation?: boolean;
  judgeMode?: string;
  judgeProvider?: string;
  judgeModel?: string;
  judgeApiKey?: string;
  judgeBaseUrl?: string;
  semanticMode?: string;
  semanticThreshold?: number;
  embeddingUrl?: string;
  bannedTools?: string[];
  bannedCommands?: string[];
  bannedKeywords?: string[];
  primaryLanguage?: string;
}

@Injectable({ providedIn: 'root' })
export class EnforcerService extends BaseService {

  private eventSource: EventSource | null = null;

  sessions$ = new BehaviorSubject<EnforcerSession[]>([]);
  processes$ = new BehaviorSubject<EnforcerProcess[]>([]);
  activeSession$ = new BehaviorSubject<EnforcerSession | null>(null);
  liveEvents$ = new Subject<{ name: string; data: any }>();
  error$ = new Subject<string>();

  constructor(private http: HttpClient) {
    super();
  }

  /**
   * List all enforcer sessions.
   */
  listSessions(): Observable<EnforcerSession[]> {
    return this.http.get<EnforcerSession[]>(`${this.backendUrl}/enforcer/sessions`);
  }

  /**
   * Refresh the sessions list.
   */
  refreshSessions(): void {
    this.listSessions().subscribe({
      next: sessions => this.sessions$.next(sessions),
      error: err => this.error$.next(err.message || 'Failed to list sessions')
    });
  }

  /**
   * List active judge/enforcer watcher processes.
   */
  listProcesses(): Observable<EnforcerProcess[]> {
    return this.http.get<EnforcerProcess[]>(`${this.backendUrl}/enforcer/processes`);
  }

  /**
   * Refresh the active watcher process list.
   */
  refreshProcesses(): void {
    this.listProcesses().subscribe({
      next: processes => this.processes$.next(processes),
      error: err => this.error$.next(err.message || 'Failed to list enforcer processes')
    });
  }

  /**
   * Get detail for a session.
   */
  getSession(sessionId: string): Observable<EnforcerSession> {
    return this.http.get<EnforcerSession>(`${this.backendUrl}/enforcer/sessions/${sessionId}`);
  }

  /**
   * Create a new enforcer session.
   */
  createSession(request: CreateSessionRequest): Observable<EnforcerSession> {
    return this.http.post<EnforcerSession>(`${this.backendUrl}/enforcer/sessions`, request);
  }

  /**
   * Enable enforcement.
   */
  enableEnforcement(sessionId: string): Observable<any> {
    return this.http.put(`${this.backendUrl}/enforcer/sessions/${sessionId}/enable`, {});
  }

  /**
   * Disable enforcement.
   */
  disableEnforcement(sessionId: string): Observable<any> {
    return this.http.put(`${this.backendUrl}/enforcer/sessions/${sessionId}/disable`, {});
  }

  /**
   * Send a message to the agent.
   */
  sendMessage(sessionId: string, message: string): Observable<any> {
    return this.http.post(`${this.backendUrl}/enforcer/sessions/${sessionId}/send`, { message });
  }

  /**
   * Get violations for a session.
   */
  getViolations(sessionId: string): Observable<EnforcerEvent[]> {
    return this.http.get<EnforcerEvent[]>(`${this.backendUrl}/enforcer/sessions/${sessionId}/violations`);
  }

  /**
   * Restart a session.
   */
  restartSession(sessionId: string): Observable<EnforcerSession> {
    return this.http.post<EnforcerSession>(`${this.backendUrl}/enforcer/sessions/${sessionId}/restart`, {});
  }

  /**
   * Delete a session.
   */
  deleteSession(sessionId: string): Observable<any> {
    return this.http.delete(`${this.backendUrl}/enforcer/sessions/${sessionId}`);
  }

  /**
   * Subscribe to real-time events via SSE.
   */
  connectEvents(sessionId: string): void {
    this.disconnectEvents();

    const url = `${this.backendUrl}/enforcer/sessions/${sessionId}/stream`;
    this.eventSource = new EventSource(url);

    const eventTypes = [
      'session_state', 'session_started', 'session_registered',
      'session_ended', 'chunk', 'tool_use', 'turn_complete',
      'interrupt_event', 'enforcement_enabled', 'enforcement_disabled',
      'message_sent', 'heartbeat'
    ];

    for (const type of eventTypes) {
      this.eventSource.addEventListener(type, (event: any) => {
        try {
          const data = JSON.parse(event.data);
          this.liveEvents$.next({ name: type, data });

          // Update active session state on relevant events
          if (type === 'session_state' || type === 'interrupt_event'
              || type === 'enforcement_enabled' || type === 'enforcement_disabled'
              || type === 'turn_complete') {
            this.getSession(sessionId).subscribe(s => this.activeSession$.next(s));
          }
        } catch (e) {
          // Ignore parse errors
        }
      });
    }

    this.eventSource.onerror = () => {
      this.error$.next('SSE connection lost');
    };
  }

  /**
   * Disconnect from SSE.
   */
  disconnectEvents(): void {
    if (this.eventSource) {
      this.eventSource.close();
      this.eventSource = null;
    }
  }

  // ── Per-coding-project config management ─────────────────────────

  /**
   * Get enforcer/judge config for a coding project.
   */
  getCodeProjectConfig(codingProjectId: string): Observable<EnforcerConfigResponse> {
    return this.http.get<EnforcerConfigResponse>(
      `${this.backendUrl}/enforcer/config/${codingProjectId}`);
  }

  /**
   * Save enforcer/judge config for a coding project.
   */
  saveCodeProjectConfig(codingProjectId: string, config: EnforcerJudgeConfig): Observable<any> {
    return this.http.put(
      `${this.backendUrl}/enforcer/config/${codingProjectId}`, config);
  }

  /**
   * Delete per-coding-project config (reverts to project-level).
   */
  deleteCodeProjectConfig(codingProjectId: string): Observable<any> {
    return this.http.delete(
      `${this.backendUrl}/enforcer/config/${codingProjectId}`);
  }

  // ── Metrics ──────────────────────────────────────────────────────

  /**
   * Get metrics for all coding projects and agents.
   */
  getAllMetrics(): Observable<EnforcerMetricsSummary[]> {
    return this.http.get<EnforcerMetricsSummary[]>(
      `${this.backendUrl}/enforcer/metrics`);
  }

  /**
   * Get metrics for a specific coding project (all agents).
   */
  getProjectMetrics(codingProjectId: string): Observable<EnforcerMetricsSummary[]> {
    return this.http.get<EnforcerMetricsSummary[]>(
      `${this.backendUrl}/enforcer/metrics/${codingProjectId}`);
  }

  /**
   * Get detailed metrics including history for a specific agent in a coding project.
   */
  getProjectAgentMetrics(codingProjectId: string, agentName: string): Observable<EnforcerMetricsDetail> {
    return this.http.get<EnforcerMetricsDetail>(
      `${this.backendUrl}/enforcer/metrics/${codingProjectId}/${agentName}`);
  }
}
