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
import { Observable, BehaviorSubject, interval, switchMap, takeUntil, Subject } from 'rxjs';
import { tap, catchError, map } from 'rxjs/operators';
import { BaseService } from './base.service';
import {
  AgentProvider,
  ApiAgentConfigRequest,
  ProcessStatus,
  AgentDiagnosticSummary,
  AgentFullDiagnosticReport,
  AgentAvailabilityResponse,
  AgentCountSummary,
  ActiveProcessResponse,
  AgentSelectionInfo,
  toAgentSelectionInfo,
  KompileLocalModelStatus
} from '../models/api-models';

/**
 * Service for managing local AI agents (Claude Code, Codex, Gemini CLI).
 *
 * Provides functionality for:
 * - Listing and checking agent availability
 * - Monitoring process execution
 * - Managing agent selection
 */
@Injectable({
  providedIn: 'root'
})
export class AgentService extends BaseService {

  private readonly agentsUrl: string;

  // State management
  private agentsSubject = new BehaviorSubject<AgentProvider[]>([]);
  private selectedAgentSubject = new BehaviorSubject<AgentProvider | null>(null);
  private diagnosticSummarySubject = new BehaviorSubject<AgentDiagnosticSummary | null>(null);
  private loadingSubject = new BehaviorSubject<boolean>(false);
  private errorSubject = new BehaviorSubject<string | null>(null);

  // Polling control
  private pollingStop$ = new Subject<void>();
  private isPolling = false;

  // Public observables
  agents$ = this.agentsSubject.asObservable();
  selectedAgent$ = this.selectedAgentSubject.asObservable();
  diagnosticSummary$ = this.diagnosticSummarySubject.asObservable();
  loading$ = this.loadingSubject.asObservable();
  error$ = this.errorSubject.asObservable();

  constructor(private http: HttpClient) {
    super();
    this.agentsUrl = `${this.backendUrl}/agents`;
  }

  // ═══════════════════════════════════════════════════════════════════════════════
  // AGENT LISTING & SELECTION
  // ═══════════════════════════════════════════════════════════════════════════════

  /**
   * Get all registered agents.
   */
  getAllAgents(): Observable<AgentProvider[]> {
    this.loadingSubject.next(true);
    return this.http.get<AgentProvider[]>(this.agentsUrl).pipe(
      tap(agents => {
        this.agentsSubject.next(agents);
        this.loadingSubject.next(false);
        this.errorSubject.next(null);

        // Auto-select default agent if none selected
        if (!this.selectedAgentSubject.value) {
          const defaultAgent = agents.find(a => a.isDefault && a.available)
            || agents.find(a => a.available);
          if (defaultAgent) {
            this.selectedAgentSubject.next(defaultAgent);
          }
        }
      }),
      catchError(err => {
        this.loadingSubject.next(false);
        this.errorSubject.next(err.message || 'Failed to load agents');
        throw err;
      })
    );
  }

  /**
   * Get only available agents.
   */
  getAvailableAgents(): Observable<AgentProvider[]> {
    return this.http.get<AgentProvider[]>(`${this.agentsUrl}/available`);
  }

  /**
   * Get the default agent.
   */
  getDefaultAgent(): Observable<AgentProvider> {
    return this.http.get<AgentProvider>(`${this.agentsUrl}/default`);
  }

  /**
   * Get a specific agent by name.
   */
  getAgent(name: string): Observable<AgentProvider> {
    return this.http.get<AgentProvider>(`${this.agentsUrl}/${name}`);
  }

  /**
   * Set the currently selected agent.
   */
  selectAgent(agent: AgentProvider | null): void {
    this.selectedAgentSubject.next(agent);
  }

  /**
   * Get the currently selected agent.
   */
  getSelectedAgent(): AgentProvider | null {
    return this.selectedAgentSubject.value;
  }

  /**
   * Get agents formatted for UI selection.
   */
  getAgentSelectionList(): Observable<AgentSelectionInfo[]> {
    return this.agents$.pipe(
      map(agents => agents.map(toAgentSelectionInfo))
    );
  }

  // ═══════════════════════════════════════════════════════════════════════════════
  // AVAILABILITY CHECKS
  // ═══════════════════════════════════════════════════════════════════════════════

  /**
   * Check availability of a specific agent.
   */
  checkAgentAvailability(agentName: string): Observable<AgentAvailabilityResponse> {
    return this.http.post<AgentAvailabilityResponse>(
      `${this.agentsUrl}/${agentName}/check`, {}
    );
  }

  /**
   * Refresh availability status for all agents.
   */
  refreshAllAgents(): Observable<AgentProvider[]> {
    this.loadingSubject.next(true);
    return this.http.post<AgentProvider[]>(`${this.agentsUrl}/refresh`, {}).pipe(
      tap(agents => {
        this.agentsSubject.next(agents);
        this.loadingSubject.next(false);
        this.errorSubject.next(null);
      }),
      catchError(err => {
        this.loadingSubject.next(false);
        this.errorSubject.next(err.message || 'Failed to refresh agents');
        throw err;
      })
    );
  }

  /**
   * Get agent count summary.
   */
  getAgentCounts(): Observable<AgentCountSummary> {
    return this.http.get<AgentCountSummary>(`${this.agentsUrl}/count`);
  }

  // ═══════════════════════════════════════════════════════════════════════════════
  // DIAGNOSTICS
  // ═══════════════════════════════════════════════════════════════════════════════

  /**
   * Get diagnostic summary for quick status check.
   */
  getDiagnosticSummary(): Observable<AgentDiagnosticSummary> {
    return this.http.get<AgentDiagnosticSummary>(`${this.agentsUrl}/diagnostics/summary`).pipe(
      tap(summary => this.diagnosticSummarySubject.next(summary))
    );
  }

  /**
   * Get full diagnostic report including process history.
   */
  getFullDiagnosticReport(): Observable<AgentFullDiagnosticReport> {
    return this.http.get<AgentFullDiagnosticReport>(`${this.agentsUrl}/diagnostics/report`);
  }

  /**
   * Get current active process (if any).
   */
  getCurrentProcess(): Observable<ProcessStatus | null> {
    return this.http.get<ProcessStatus>(`${this.agentsUrl}/diagnostics/current`).pipe(
      catchError(() => {
        // 204 No Content returns null
        return [null];
      })
    );
  }

  /**
   * Get specific process by ID.
   */
  getProcess(processId: string): Observable<ProcessStatus> {
    return this.http.get<ProcessStatus>(`${this.agentsUrl}/diagnostics/process/${processId}`);
  }

  /**
   * Get process history.
   */
  getProcessHistory(): Observable<ProcessStatus[]> {
    return this.http.get<ProcessStatus[]>(`${this.agentsUrl}/diagnostics/history`);
  }

  /**
   * Clear process history.
   */
  clearProcessHistory(): Observable<void> {
    return this.http.delete<void>(`${this.agentsUrl}/diagnostics/history`);
  }

  /**
   * Check if there's an active process.
   */
  hasActiveProcess(): Observable<ActiveProcessResponse> {
    return this.http.get<ActiveProcessResponse>(`${this.agentsUrl}/diagnostics/active`);
  }

  // ═══════════════════════════════════════════════════════════════════════════════
  // POLLING
  // ═══════════════════════════════════════════════════════════════════════════════

  /**
   * Start polling diagnostic summary at specified interval.
   */
  startDiagnosticPolling(intervalMs: number = 2000): void {
    if (this.isPolling) {
      return;
    }

    this.isPolling = true;
    interval(intervalMs).pipe(
      takeUntil(this.pollingStop$),
      switchMap(() => this.getDiagnosticSummary())
    ).subscribe({
      error: (err) => {
        console.error('Diagnostic polling error:', err);
      }
    });
  }

  /**
   * Stop diagnostic polling.
   */
  stopDiagnosticPolling(): void {
    if (this.isPolling) {
      this.pollingStop$.next();
      this.isPolling = false;
    }
  }

  // ═══════════════════════════════════════════════════════════════════════════════
  // UTILITY METHODS
  // ═══════════════════════════════════════════════════════════════════════════════

  /**
   * Get agents from cache (synchronous).
   */
  getCachedAgents(): AgentProvider[] {
    return this.agentsSubject.value;
  }

  /**
   * Check if any agents are available.
   */
  hasAvailableAgents(): boolean {
    return this.agentsSubject.value.some(a => a.available);
  }

  /**
   * Get the first available agent.
   */
  getFirstAvailableAgent(): AgentProvider | undefined {
    return this.agentsSubject.value.find(a => a.available);
  }

  /**
   * Clear any error state.
   */
  clearError(): void {
    this.errorSubject.next(null);
  }

  // ═══════════════════════════════════════════════════════════════════════════════
  // API AGENT CONFIGURATION
  // ═══════════════════════════════════════════════════════════════════════════════

  /**
   * List configured API agents.
   */
  getApiAgentConfigs(): Observable<AgentProvider[]> {
    return this.http.get<AgentProvider[]>(`${this.agentsUrl}/api-config`);
  }

  /**
   * Add a new API agent endpoint.
   */
  addApiAgentConfig(config: ApiAgentConfigRequest): Observable<any> {
    return this.http.post(`${this.agentsUrl}/api-config`, config);
  }

  /**
   * Update an existing API agent configuration.
   */
  updateApiAgentConfig(name: string, config: ApiAgentConfigRequest): Observable<any> {
    return this.http.put(`${this.agentsUrl}/api-config/${name}`, config);
  }

  /**
   * Delete an API agent configuration.
   */
  deleteApiAgentConfig(name: string): Observable<any> {
    return this.http.delete(`${this.agentsUrl}/api-config/${name}`);
  }

  /**
   * Test connectivity to a named API agent.
   */
  testApiAgentConnection(name: string): Observable<any> {
    return this.http.post(`${this.agentsUrl}/api-config/${name}/test`, {});
  }

  /**
   * Test connectivity to an arbitrary endpoint.
   */
  testApiEndpoint(config: ApiAgentConfigRequest): Observable<any> {
    return this.http.post(`${this.agentsUrl}/api-config/test-endpoint`, config);
  }

  // ═══════════════════════════════════════════════════════════════════════════════
  // KOMPILE LOCAL MODEL
  // ═══════════════════════════════════════════════════════════════════════════════

  getKompileLocalStatus(): Observable<KompileLocalModelStatus> {
    return this.http.get<KompileLocalModelStatus>(`${this.agentsUrl}/kompile-local/status`);
  }

  discoverKompileLocal(): Observable<any> {
    return this.http.post(`${this.agentsUrl}/kompile-local/discover`, {});
  }

  connectKompileLocal(stagingUrl: string): Observable<any> {
    return this.http.post(`${this.agentsUrl}/kompile-local/connect`, { stagingUrl });
  }

  disconnectKompileLocal(): Observable<any> {
    return this.http.post(`${this.agentsUrl}/kompile-local/disconnect`, {});
  }

  // ═══════════════════════════════════════════════════════════════════════════════
  // AGENT SKIP-PERMISSIONS
  // ═══════════════════════════════════════════════════════════════════════════════

  updateSkipPermissions(agentName: string, skipPermissions: boolean): Observable<any> {
    return this.http.put(`${this.agentsUrl}/chat/agents/${agentName}/skip-permissions`, { skipPermissions });
  }
}
