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
import { HttpClient, HttpHeaders, HttpParams } from '@angular/common/http';
import { Observable, BehaviorSubject } from 'rxjs';
import { tap } from 'rxjs/operators';
import { BaseService, backendUrl } from './base.service';
import {
  NoteSyncConfig,
  NoteSyncConfigUpdate,
  SyncConnectionRequest,
  SyncConnectionResponse,
  SyncConnectionTestResponse,
  SyncRunResponse,
  SyncRecord,
  NoteModel
} from '../models/sync-models';

@Injectable({ providedIn: 'root' })
export class NoteSyncService extends BaseService {

  private connectionsSubject = new BehaviorSubject<SyncConnectionResponse[]>([]);
  public connections$ = this.connectionsSubject.asObservable();

  private notesSubject = new BehaviorSubject<NoteModel[]>([]);
  public notes$ = this.notesSubject.asObservable();

  constructor(private http: HttpClient) {
    super();
  }

  hasIntegrationBrowserSession(): boolean {
    return typeof sessionStorage !== 'undefined'
      && !!sessionStorage.getItem('kompile.channel.csrf');
  }

  exchangeIntegrationBrowserSession(code: string): Observable<{ csrfToken: string; expiresAt: string }> {
    return this.http.post<{ csrfToken: string; expiresAt: string }>(
      `${backendUrl}/channel-integrations/browser-sessions/exchange`,
      { code }, { withCredentials: true }).pipe(tap(session => {
        if (typeof sessionStorage !== 'undefined') {
          sessionStorage.setItem('kompile.channel.csrf', session.csrfToken);
        }
      }));
  }

  // ── Sync Config ─────────────────────────────────────────────────────

  getConfig(): Observable<NoteSyncConfig> {
    return this.http.get<NoteSyncConfig>(`${backendUrl}/sync/config`, this.syncOptions());
  }

  updateConfig(config: NoteSyncConfigUpdate): Observable<NoteSyncConfig> {
    return this.http.put<NoteSyncConfig>(`${backendUrl}/sync/config`, config, this.syncOptions());
  }

  resetConfig(): Observable<NoteSyncConfig> {
    return this.http.post<NoteSyncConfig>(`${backendUrl}/sync/config/reset`, {}, this.syncOptions());
  }

  // ── Sync Connections ─────────────────────────────────────────────────

  loadConnections(factSheetId: number): Observable<SyncConnectionResponse[]> {
    return this.http.get<SyncConnectionResponse[]>(
      `${backendUrl}/sync/connections?factSheetId=${factSheetId}`, this.syncOptions()
    ).pipe(tap(conns => this.connectionsSubject.next(conns)));
  }

  createConnection(req: SyncConnectionRequest): Observable<SyncConnectionResponse> {
    return this.http.post<SyncConnectionResponse>(`${backendUrl}/sync/connections`, req, this.syncOptions());
  }

  updateConnection(id: number, req: SyncConnectionRequest): Observable<SyncConnectionResponse> {
    return this.http.put<SyncConnectionResponse>(`${backendUrl}/sync/connections/${id}`, req, this.syncOptions());
  }

  deleteConnection(id: number): Observable<void> {
    return this.http.delete<void>(`${backendUrl}/sync/connections/${id}`, this.syncOptions());
  }

  triggerSync(connectionId: number): Observable<SyncRunResponse> {
    return this.http.post<SyncRunResponse>(
      `${backendUrl}/sync/connections/${connectionId}/trigger`, {}, this.syncOptions()
    );
  }

  pullUpdates(connectionId: number): Observable<SyncRunResponse> {
    return this.http.post<SyncRunResponse>(
      `${backendUrl}/sync/connections/${connectionId}/pull`, {}, this.syncOptions()
    );
  }

  getRun(sessionId: string): Observable<SyncRunResponse> {
    return this.http.get<SyncRunResponse>(
      `${backendUrl}/sync/runs/${encodeURIComponent(sessionId)}`, this.syncOptions()
    );
  }

  listRuns(connectionId?: number, factSheetId?: number): Observable<SyncRunResponse[]> {
    let params = new HttpParams();
    if (connectionId != null) params = params.set('connectionId', connectionId);
    if (factSheetId != null) params = params.set('factSheetId', factSheetId);
    return this.http.get<SyncRunResponse[]>(`${backendUrl}/sync/runs`, this.syncOptions(params));
  }

  updateAutoSync(id: number, enabled: boolean, pollCron?: string): Observable<SyncConnectionResponse> {
    return this.http.patch<SyncConnectionResponse>(`${backendUrl}/sync/connections/${id}/auto-sync`, {
      enabled,
      pollCron: enabled ? pollCron : null
    }, this.syncOptions());
  }

  enableConnection(id: number): Observable<SyncConnectionResponse> {
    return this.http.post<SyncConnectionResponse>(`${backendUrl}/sync/connections/${id}/enable`, {}, this.syncOptions());
  }

  disableConnection(id: number): Observable<SyncConnectionResponse> {
    return this.http.post<SyncConnectionResponse>(`${backendUrl}/sync/connections/${id}/disable`, {}, this.syncOptions());
  }

  testConnectionAuth(id: number): Observable<SyncConnectionTestResponse> {
    return this.http.post<SyncConnectionTestResponse>(`${backendUrl}/sync/connections/${id}/test-auth`, {}, this.syncOptions());
  }

  listRecords(connectionId: number, status?: string): Observable<SyncRecord[]> {
    let url = `${backendUrl}/sync/connections/${connectionId}/records`;
    if (status) url += `?status=${status}`;
    return this.http.get<SyncRecord[]>(url, this.syncOptions());
  }

  resolveConflict(connectionId: number, recordId: number, resolution: string): Observable<any> {
    return this.http.post(
      `${backendUrl}/sync/connections/${connectionId}/records/${recordId}/resolve-conflict`,
      { resolution }, this.syncOptions()
    );
  }

  // ── Notes CRUD ──────────────────────────────────────────────────────

  loadNotes(factSheetId: number): Observable<NoteModel[]> {
    return this.http.get<NoteModel[]>(
      `${backendUrl}/fact-sheets/${factSheetId}/notes`
    ).pipe(tap(notes => this.notesSubject.next(notes)));
  }

  createNote(factSheetId: number, note: { title?: string; content: string; noteType?: string; tags?: string }): Observable<NoteModel> {
    return this.http.post<NoteModel>(`${backendUrl}/fact-sheets/${factSheetId}/notes`, note);
  }

  updateNote(noteId: number, update: { title?: string; content?: string; tags?: string }): Observable<NoteModel> {
    return this.http.put<NoteModel>(`${backendUrl}/notes/${noteId}`, update);
  }

  deleteNote(noteId: number): Observable<void> {
    return this.http.delete<void>(`${backendUrl}/notes/${noteId}`);
  }

  searchNotes(factSheetId: number, query: string): Observable<NoteModel[]> {
    return this.http.get<NoteModel[]>(
      `${backendUrl}/fact-sheets/${factSheetId}/notes/search?q=${encodeURIComponent(query)}`
    );
  }

  private syncOptions(params?: HttpParams): { headers: HttpHeaders; withCredentials: boolean; params?: HttpParams } {
    let headers = new HttpHeaders({ 'X-Kompile-Channel-Request': '1' });
    if (typeof sessionStorage !== 'undefined') {
      const csrf = sessionStorage.getItem('kompile.channel.csrf');
      if (csrf) headers = headers.set('X-Kompile-Channel-CSRF', csrf);
    }
    return { headers, withCredentials: true, ...(params ? { params } : {}) };
  }
}
