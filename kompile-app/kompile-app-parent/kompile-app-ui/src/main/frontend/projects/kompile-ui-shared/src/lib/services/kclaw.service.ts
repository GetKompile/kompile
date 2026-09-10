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
import { HttpClient, HttpHeaders } from '@angular/common/http';
import { Observable, BehaviorSubject } from 'rxjs';
import { tap, catchError } from 'rxjs/operators';
import { BaseService } from './base.service';
import {
  AgentDefinition,
  KClawChatRequest,
  KClawChatResponse,
  KClawSession,
  KClawConfig,
  ChannelConnectionView,
  ChannelConnectionWrite,
  ChannelBrowserSession,
  ChannelEngineDescriptor,
  ChannelProviderDescriptor,
  TelegramDiagnostics,
  TelegramPairing,
  TelegramPairingStart,
  TelegramWebhookInfo,
  HeartbeatInfo,
  HeartbeatRequest,
  PermissionStatus
} from '../models/kclaw-models';

@Injectable({
  providedIn: 'root'
})
export class KClawService extends BaseService {

  private readonly apiUrl: string;
  private readonly channelApiUrl: string;
  private csrfToken: string | null = null;

  private agentsSubject = new BehaviorSubject<AgentDefinition[]>([]);
  private channelsSubject = new BehaviorSubject<ChannelConnectionView[]>([]);
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
    this.channelApiUrl = `${this.backendUrl}/channel-integrations`;
    if (typeof sessionStorage !== 'undefined') {
      this.csrfToken = sessionStorage.getItem('kompile.channel.csrf');
    }
  }

  // ═══════════════════════════════════════════════════════════════════════════════
  // CONFIG
  // ═══════════════════════════════════════════════════════════════════════════════

  getConfig(): Observable<KClawConfig> {
    return this.http.get<KClawConfig>(`${this.apiUrl}/config`, { headers: this.adminHeaders }).pipe(
      tap(config => this.configSubject.next(config))
    );
  }

  updateConfig(config: Partial<KClawConfig>): Observable<KClawConfig> {
    return this.http.put<KClawConfig>(`${this.apiUrl}/config`, config, { headers: this.adminHeaders }).pipe(
      tap(config => this.configSubject.next(config))
    );
  }

  // ═══════════════════════════════════════════════════════════════════════════════
  // AGENTS
  // ═══════════════════════════════════════════════════════════════════════════════

  getAgents(): Observable<AgentDefinition[]> {
    this.loadingSubject.next(true);
    return this.http.get<AgentDefinition[]>(`${this.apiUrl}/agents`, { headers: this.adminHeaders }).pipe(
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
    return this.http.get<AgentDefinition>(`${this.apiUrl}/agents/${name}`, { headers: this.adminHeaders });
  }

  createAgent(agent: AgentDefinition): Observable<AgentDefinition> {
    return this.http.post<AgentDefinition>(`${this.apiUrl}/agents`, agent, { headers: this.adminHeaders }).pipe(
      tap(() => this.getAgents().subscribe())
    );
  }

  updateAgent(name: string, agent: AgentDefinition): Observable<AgentDefinition> {
    return this.http.put<AgentDefinition>(`${this.apiUrl}/agents/${name}`, agent, { headers: this.adminHeaders }).pipe(
      tap(() => this.getAgents().subscribe())
    );
  }

  deleteAgent(name: string): Observable<void> {
    return this.http.delete<void>(`${this.apiUrl}/agents/${name}`, { headers: this.adminHeaders }).pipe(
      tap(() => this.getAgents().subscribe())
    );
  }

  // ═══════════════════════════════════════════════════════════════════════════════
  // CHAT
  // ═══════════════════════════════════════════════════════════════════════════════

  chat(request: KClawChatRequest): Observable<KClawChatResponse> {
    return this.http.post<KClawChatResponse>(`${this.apiUrl}/chat`, request, { headers: this.adminHeaders });
  }

  chatStream(request: KClawChatRequest): Observable<string> {
    return this.http.post(`${this.apiUrl}/chat/stream`, request, {
      headers: this.adminHeaders,
      responseType: 'text'
    }) as Observable<string>;
  }

  // ═══════════════════════════════════════════════════════════════════════════════
  // SESSIONS
  // ═══════════════════════════════════════════════════════════════════════════════

  getSessions(): Observable<string[]> {
    return this.http.get<string[]>(`${this.apiUrl}/sessions`, { headers: this.adminHeaders });
  }

  getSessionHistory(sessionKey: string): Observable<KClawSession> {
    return this.http.get<KClawSession>(`${this.apiUrl}/sessions/${encodeURIComponent(sessionKey)}/history`,
      { headers: this.adminHeaders });
  }

  clearSession(sessionKey: string): Observable<void> {
    return this.http.delete<void>(`${this.apiUrl}/sessions/${encodeURIComponent(sessionKey)}`,
      { headers: this.adminHeaders });
  }

  // ═══════════════════════════════════════════════════════════════════════════════
  // CHANNELS
  exchangeChannelBrowserSession(code: string): Observable<ChannelBrowserSession> {
    return this.http.post<ChannelBrowserSession>(`${this.channelApiUrl}/browser-sessions/exchange`,
      { code }, { withCredentials: true }).pipe(tap(session => {
        this.csrfToken = session.csrfToken;
        if (typeof sessionStorage !== 'undefined') {
          sessionStorage.setItem('kompile.channel.csrf', session.csrfToken);
        }
      }));
  }

  hasChannelBrowserSession(): boolean {
    return Boolean(this.csrfToken);
  }

  // ═══════════════════════════════════════════════════════════════════════════════

  getChannelProviders(): Observable<ChannelProviderDescriptor[]> {
    return this.http.get<ChannelProviderDescriptor[]>(`${this.channelApiUrl}/providers`,
      { headers: this.adminHeaders });
  }

  getChannelEngines(): Observable<ChannelEngineDescriptor[]> {
    return this.http.get<ChannelEngineDescriptor[]>(`${this.channelApiUrl}/engines`,
      { headers: this.adminHeaders });
  }

  getChannels(): Observable<ChannelConnectionView[]> {
    return this.http.get<ChannelConnectionView[]>(`${this.channelApiUrl}/connections`,
      { headers: this.adminHeaders }).pipe(
      tap(channels => this.channelsSubject.next(channels))
    );
  }

  getChannel(name: string): Observable<ChannelConnectionView> {
    return this.http.get<ChannelConnectionView>(this.channelPath(name), { headers: this.adminHeaders });
  }

  createChannel(request: ChannelConnectionWrite): Observable<ChannelConnectionView> {
    return this.http.post<ChannelConnectionView>(`${this.channelApiUrl}/connections`, request,
      { headers: this.adminHeaders }).pipe(
      tap(() => this.getChannels().subscribe())
    );
  }

  updateChannel(name: string, request: ChannelConnectionWrite): Observable<ChannelConnectionView> {
    return this.http.put<ChannelConnectionView>(this.channelPath(name), request,
      { headers: this.adminHeaders }).pipe(
      tap(() => this.getChannels().subscribe())
    );
  }

  enableChannel(name: string): Observable<ChannelConnectionView> {
    return this.http.post<ChannelConnectionView>(`${this.channelPath(name)}/enable`, {},
      { headers: this.adminHeaders }).pipe(tap(() => this.getChannels().subscribe()));
  }

  disableChannel(name: string): Observable<ChannelConnectionView> {
    return this.http.post<ChannelConnectionView>(`${this.channelPath(name)}/disable`, {},
      { headers: this.adminHeaders }).pipe(tap(() => this.getChannels().subscribe()));
  }

  disconnectChannel(name: string): Observable<void> {
    return this.http.delete<void>(this.channelPath(name), { headers: this.adminHeaders }).pipe(
      tap(() => this.getChannels().subscribe()));
  }

  testChannel(name: string, target: string, message?: string): Observable<{accepted: boolean; message: string}> {
    return this.http.post<{accepted: boolean; message: string}>(`${this.channelPath(name)}/test`,
      { target, message }, { headers: this.adminHeaders });
  }

  startTelegramPairing(name: string): Observable<TelegramPairingStart> {
    return this.http.post<TelegramPairingStart>(`${this.telegramPath(name)}/pairings`, {},
      { headers: this.adminHeaders });
  }

  getTelegramPairing(name: string, pairingId: string): Observable<TelegramPairing> {
    return this.http.get<TelegramPairing>(
      `${this.telegramPath(name)}/pairings/${encodeURIComponent(pairingId)}`,
      { headers: this.adminHeaders });
  }

  approveTelegramPairing(name: string, pairingId: string, expectedChatId: number): Observable<ChannelConnectionView> {
    return this.http.post<ChannelConnectionView>(
      `${this.telegramPath(name)}/pairings/${encodeURIComponent(pairingId)}/approve`,
      { expectedChatId }, { headers: this.adminHeaders }).pipe(tap(() => this.getChannels().subscribe()));
  }

  cancelTelegramPairing(name: string, pairingId: string): Observable<void> {
    return this.http.delete<void>(
      `${this.telegramPath(name)}/pairings/${encodeURIComponent(pairingId)}`,
      { headers: this.adminHeaders });
  }

  getTelegramDiagnostics(name: string): Observable<TelegramDiagnostics> {
    return this.http.get<TelegramDiagnostics>(`${this.telegramPath(name)}/diagnostics`,
      { headers: this.adminHeaders });
  }

  getTelegramWebhook(name: string): Observable<TelegramWebhookInfo> {
    return this.http.get<TelegramWebhookInfo>(`${this.telegramPath(name)}/webhook`,
      { headers: this.adminHeaders });
  }

  deleteTelegramWebhook(name: string, dropPendingUpdates: boolean): Observable<TelegramWebhookInfo> {
    return this.http.delete<TelegramWebhookInfo>(`${this.telegramPath(name)}/webhook`, {
      headers: this.adminHeaders,
      params: { dropPendingUpdates }
    });
  }

  // ═══════════════════════════════════════════════════════════════════════════════
  // HEARTBEATS
  // ═══════════════════════════════════════════════════════════════════════════════

  getHeartbeats(): Observable<HeartbeatInfo[]> {
    return this.http.get<HeartbeatInfo[]>(`${this.apiUrl}/heartbeats`, { headers: this.adminHeaders }).pipe(
      tap(heartbeats => this.heartbeatsSubject.next(heartbeats))
    );
  }

  createHeartbeat(request: HeartbeatRequest): Observable<void> {
    return this.http.post<void>(`${this.apiUrl}/heartbeats`, request, { headers: this.adminHeaders }).pipe(
      tap(() => this.getHeartbeats().subscribe())
    );
  }

  cancelHeartbeat(id: string): Observable<void> {
    return this.http.delete<void>(`${this.apiUrl}/heartbeats/${id}`, { headers: this.adminHeaders }).pipe(
      tap(() => this.getHeartbeats().subscribe())
    );
  }

  // ═══════════════════════════════════════════════════════════════════════════════
  // PERMISSIONS
  // ═══════════════════════════════════════════════════════════════════════════════

  getPermissions(): Observable<PermissionStatus> {
    return this.http.get<PermissionStatus>(`${this.apiUrl}/permissions/commands`, { headers: this.adminHeaders });
  }

  allowCommand(command: string): Observable<void> {
    return this.http.post<void>(`${this.apiUrl}/permissions/commands/allow`, { command },
      { headers: this.adminHeaders });
  }

  denyCommand(command: string): Observable<void> {
    return this.http.post<void>(`${this.apiUrl}/permissions/commands/deny`, { command },
      { headers: this.adminHeaders });
  }

  // ═══════════════════════════════════════════════════════════════════════════════
  // TOOLS
  // ═══════════════════════════════════════════════════════════════════════════════

  getAvailableTools(): Observable<{name: string, description: string}[]> {
    return this.http.get<{name: string, description: string}[]>(`${this.apiUrl}/tools`,
      { headers: this.adminHeaders });
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

  private channelPath(name: string): string {
    return `${this.channelApiUrl}/connections/${encodeURIComponent(name)}`;
  }

  private telegramPath(name: string): string {
    return `${this.channelPath(name)}/telegram`;
  }

  private get adminHeaders(): HttpHeaders {
    let headers = new HttpHeaders({ 'X-Kompile-Channel-Request': '1' });
    if (this.csrfToken) {
      headers = headers.set('X-Kompile-Channel-CSRF', this.csrfToken);
    }
    return headers;
  }
}
