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
import { Observable, BehaviorSubject, Subject } from 'rxjs';
import { tap, catchError, map } from 'rxjs/operators';
import { BaseService } from './base.service';
import {
  AgentDefinition,
  KClawChatRequest,
  KClawChatResponse,
  KClawSession,
  KClawConfig,
  ChannelStatus,
  ChannelConfig,
  HeartbeatInfo,
  HeartbeatRequest,
  PermissionStatus
} from '../models/kclaw-models';

@Injectable({
  providedIn: 'root'
})
export class KClawService extends BaseService {

  private readonly apiUrl: string;

  private agentsSubject = new BehaviorSubject<AgentDefinition[]>([]);
  private channelsSubject = new BehaviorSubject<ChannelStatus[]>([]);
  private heartbeatsSubject = new BehaviorSubject<HeartbeatInfo[]>([]);
  private configSubject = new BehaviorSubject<KClawConfig | null>(null);
  private loadingSubject = new BehaviorSubject<boolean>(false);
  private errorSubject = new BehaviorSubject<string | null>(null);

  agents$ = this.agentsSubject.asObservable();
  channels$ = this.channelsSubject.asObservable();
  heartbeats$ = this.heartbeatsSubject.asObservable();
  config$ = this.configSubject.asObservable();
  loading$ = this.loadingSubject.asObservable();
  error$ = this.errorSubject.asObservable();

  constructor(private http: HttpClient) {
    super();
    this.apiUrl = `${this.backendUrl}/kclaw`;
  }

  // ═══════════════════════════════════════════════════════════════════════════════
  // CONFIG
  // ═══════════════════════════════════════════════════════════════════════════════

  getConfig(): Observable<KClawConfig> {
    return this.http.get<KClawConfig>(`${this.apiUrl}/config`).pipe(
      tap(config => this.configSubject.next(config))
    );
  }

  updateConfig(config: Partial<KClawConfig>): Observable<KClawConfig> {
    return this.http.put<KClawConfig>(`${this.apiUrl}/config`, config).pipe(
      tap(config => this.configSubject.next(config))
    );
  }

  // ═══════════════════════════════════════════════════════════════════════════════
  // AGENTS
  // ═══════════════════════════════════════════════════════════════════════════════

  getAgents(): Observable<AgentDefinition[]> {
    this.loadingSubject.next(true);
    return this.http.get<AgentDefinition[]>(`${this.apiUrl}/agents`).pipe(
      tap(agents => {
        this.agentsSubject.next(agents);
        this.loadingSubject.next(false);
      }),
      catchError(err => {
        this.loadingSubject.next(false);
        this.errorSubject.next(err.message);
        throw err;
      })
    );
  }

  getAgent(name: string): Observable<AgentDefinition> {
    return this.http.get<AgentDefinition>(`${this.apiUrl}/agents/${name}`);
  }

  createAgent(agent: AgentDefinition): Observable<AgentDefinition> {
    return this.http.post<AgentDefinition>(`${this.apiUrl}/agents`, agent).pipe(
      tap(() => this.getAgents().subscribe())
    );
  }

  updateAgent(name: string, agent: AgentDefinition): Observable<AgentDefinition> {
    return this.http.put<AgentDefinition>(`${this.apiUrl}/agents/${name}`, agent).pipe(
      tap(() => this.getAgents().subscribe())
    );
  }

  deleteAgent(name: string): Observable<void> {
    return this.http.delete<void>(`${this.apiUrl}/agents/${name}`).pipe(
      tap(() => this.getAgents().subscribe())
    );
  }

  // ═══════════════════════════════════════════════════════════════════════════════
  // CHAT
  // ═══════════════════════════════════════════════════════════════════════════════

  chat(request: KClawChatRequest): Observable<KClawChatResponse> {
    return this.http.post<KClawChatResponse>(`${this.apiUrl}/chat`, request);
  }

  chatStream(request: KClawChatRequest): Observable<string> {
    return this.http.post(`${this.apiUrl}/chat/stream`, request, {
      responseType: 'text'
    }) as Observable<string>;
  }

  // ═══════════════════════════════════════════════════════════════════════════════
  // SESSIONS
  // ═══════════════════════════════════════════════════════════════════════════════

  getSessions(): Observable<string[]> {
    return this.http.get<string[]>(`${this.apiUrl}/sessions`);
  }

  getSessionHistory(sessionKey: string): Observable<KClawSession> {
    return this.http.get<KClawSession>(`${this.apiUrl}/sessions/${encodeURIComponent(sessionKey)}/history`);
  }

  clearSession(sessionKey: string): Observable<void> {
    return this.http.delete<void>(`${this.apiUrl}/sessions/${encodeURIComponent(sessionKey)}`);
  }

  // ═══════════════════════════════════════════════════════════════════════════════
  // CHANNELS
  // ═══════════════════════════════════════════════════════════════════════════════

  getChannels(): Observable<ChannelStatus[]> {
    return this.http.get<ChannelStatus[]>(`${this.apiUrl}/channels`).pipe(
      tap(channels => this.channelsSubject.next(channels))
    );
  }

  getChannelStatus(channelName: string): Observable<ChannelStatus> {
    return this.http.get<ChannelStatus>(`${this.apiUrl}/channels/${channelName}`);
  }

  startChannel(channelName: string): Observable<void> {
    return this.http.post<void>(`${this.apiUrl}/channels/${channelName}/start`, {}).pipe(
      tap(() => this.getChannels().subscribe())
    );
  }

  stopChannel(channelName: string): Observable<void> {
    return this.http.post<void>(`${this.apiUrl}/channels/${channelName}/stop`, {}).pipe(
      tap(() => this.getChannels().subscribe())
    );
  }

  updateChannelConfig(channelName: string, config: ChannelConfig): Observable<void> {
    return this.http.post<void>(`${this.apiUrl}/channels/${channelName}/config`, config);
  }

  getSupportedChannelTypes(): Observable<string[]> {
    return this.http.get<string[]>(`${this.apiUrl}/channels/types`);
  }

  // ═══════════════════════════════════════════════════════════════════════════════
  // HEARTBEATS
  // ═══════════════════════════════════════════════════════════════════════════════

  getHeartbeats(): Observable<HeartbeatInfo[]> {
    return this.http.get<HeartbeatInfo[]>(`${this.apiUrl}/heartbeats`).pipe(
      tap(heartbeats => this.heartbeatsSubject.next(heartbeats))
    );
  }

  createHeartbeat(request: HeartbeatRequest): Observable<void> {
    return this.http.post<void>(`${this.apiUrl}/heartbeats`, request).pipe(
      tap(() => this.getHeartbeats().subscribe())
    );
  }

  cancelHeartbeat(id: string): Observable<void> {
    return this.http.delete<void>(`${this.apiUrl}/heartbeats/${id}`).pipe(
      tap(() => this.getHeartbeats().subscribe())
    );
  }

  // ═══════════════════════════════════════════════════════════════════════════════
  // PERMISSIONS
  // ═══════════════════════════════════════════════════════════════════════════════

  getPermissions(): Observable<PermissionStatus> {
    return this.http.get<PermissionStatus>(`${this.apiUrl}/permissions/commands`);
  }

  allowCommand(command: string): Observable<void> {
    return this.http.post<void>(`${this.apiUrl}/permissions/commands/allow`, { command });
  }

  denyCommand(command: string): Observable<void> {
    return this.http.post<void>(`${this.apiUrl}/permissions/commands/deny`, { command });
  }

  // ═══════════════════════════════════════════════════════════════════════════════
  // OAUTH
  // ═══════════════════════════════════════════════════════════════════════════════

  getOAuthStatus(provider: string): Observable<{connected: boolean, teamName?: string}> {
    return this.http.get<{connected: boolean, teamName?: string}>(`${this.apiUrl}/oauth/${provider}/status`);
  }

  setOAuthConfig(provider: string, clientId: string, clientSecret: string): Observable<void> {
    return this.http.post<void>(`${this.apiUrl}/oauth/config/${provider}`, { clientId, clientSecret });
  }

  startOAuth(provider: string): Observable<{authorizationUrl: string, state: string}> {
    return this.http.get<{authorizationUrl: string, state: string}>(`${this.apiUrl}/oauth/${provider}/authorize`);
  }

  disconnectOAuth(provider: string): Observable<void> {
    return this.http.delete<void>(`${this.apiUrl}/oauth/${provider}`);
  }

  // ═══════════════════════════════════════════════════════════════════════════════
  // TOOLS
  // ═══════════════════════════════════════════════════════════════════════════════

  getAvailableTools(): Observable<{name: string, description: string}[]> {
    return this.http.get<{name: string, description: string}[]>(`${this.apiUrl}/tools`);
  }

  // ═══════════════════════════════════════════════════════════════════════════════
  // UTILITY
  // ═══════════════════════════════════════════════════════════════════════════════

  clearError(): void {
    this.errorSubject.next(null);
  }

  getCachedAgents(): AgentDefinition[] {
    return this.agentsSubject.value;
  }

  getDefaultAgent(): AgentDefinition | undefined {
    return this.agentsSubject.value.find(a => a.isDefault);
  }
}
