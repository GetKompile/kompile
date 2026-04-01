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
  OpenClawChatRequest,
  OpenClawChatResponse,
  OpenClawSession,
  OpenClawConfig,
  ChannelStatus,
  ChannelConfig,
  HeartbeatInfo,
  HeartbeatRequest,
  PermissionStatus
} from '../models/openclaw-models';

@Injectable({
  providedIn: 'root'
})
export class OpenClawService extends BaseService {

  private readonly apiUrl: string;

  private agentsSubject = new BehaviorSubject<AgentDefinition[]>([]);
  private channelsSubject = new BehaviorSubject<ChannelStatus[]>([]);
  private heartbeatsSubject = new BehaviorSubject<HeartbeatInfo[]>([]);
  private configSubject = new BehaviorSubject<OpenClawConfig | null>(null);
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
    this.apiUrl = `${this.backendUrl}/openclaw`;
  }

  // ═══════════════════════════════════════════════════════════════════════════════
  // CONFIG
  // ═══════════════════════════════════════════════════════════════════════════════

  getConfig(): Observable<OpenClawConfig> {
    return this.http.get<OpenClawConfig>(`${this.apiUrl}/config`).pipe(
      tap(config => this.configSubject.next(config))
    );
  }

  updateConfig(config: Partial<OpenClawConfig>): Observable<OpenClawConfig> {
    return this.http.put<OpenClawConfig>(`${this.apiUrl}/config`, config).pipe(
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

  chat(request: OpenClawChatRequest): Observable<OpenClawChatResponse> {
    return this.http.post<OpenClawChatResponse>(`${this.apiUrl}/chat`, request);
  }

  chatStream(request: OpenClawChatRequest): Observable<string> {
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

  getSessionHistory(sessionKey: string): Observable<OpenClawSession> {
    return this.http.get<OpenClawSession>(`${this.apiUrl}/sessions/${encodeURIComponent(sessionKey)}/history`);
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
