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
import { Observable, BehaviorSubject } from 'rxjs';
import { tap } from 'rxjs/operators';
import { BaseService, backendUrl } from './base.service';
import {
  NoteSyncConfig,
  SyncConnectionRequest,
  SyncConnectionResponse,
  SyncConnectionTestResponse,
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

  // ── Sync Config ─────────────────────────────────────────────────────

  getConfig(): Observable<NoteSyncConfig> {
    return this.http.get<NoteSyncConfig>(`${backendUrl}/sync/config`);
  }

  updateConfig(config: Partial<NoteSyncConfig>): Observable<NoteSyncConfig> {
    return this.http.put<NoteSyncConfig>(`${backendUrl}/sync/config`, config);
  }

  resetConfig(): Observable<NoteSyncConfig> {
    return this.http.post<NoteSyncConfig>(`${backendUrl}/sync/config/reset`, {});
  }

  // ── Sync Connections ─────────────────────────────────────────────────

  loadConnections(factSheetId: number): Observable<SyncConnectionResponse[]> {
    return this.http.get<SyncConnectionResponse[]>(
      `${backendUrl}/sync/connections?factSheetId=${factSheetId}`
    ).pipe(tap(conns => this.connectionsSubject.next(conns)));
  }

  createConnection(req: SyncConnectionRequest): Observable<SyncConnectionResponse> {
    return this.http.post<SyncConnectionResponse>(`${backendUrl}/sync/connections`, req);
  }

  updateConnection(id: number, req: SyncConnectionRequest): Observable<SyncConnectionResponse> {
    return this.http.put<SyncConnectionResponse>(`${backendUrl}/sync/connections/${id}`, req);
  }

  deleteConnection(id: number): Observable<void> {
    return this.http.delete<void>(`${backendUrl}/sync/connections/${id}`);
  }

  triggerSync(connectionId: number): Observable<{ sessionId: string; status: string }> {
    return this.http.post<{ sessionId: string; status: string }>(
      `${backendUrl}/sync/connections/${connectionId}/trigger`, {}
    );
  }

  enableConnection(id: number): Observable<SyncConnectionResponse> {
    return this.http.post<SyncConnectionResponse>(`${backendUrl}/sync/connections/${id}/enable`, {});
  }

  disableConnection(id: number): Observable<SyncConnectionResponse> {
    return this.http.post<SyncConnectionResponse>(`${backendUrl}/sync/connections/${id}/disable`, {});
  }

  testConnectionAuth(id: number): Observable<SyncConnectionTestResponse> {
    return this.http.post<SyncConnectionTestResponse>(`${backendUrl}/sync/connections/${id}/test-auth`, {});
  }

  listRecords(connectionId: number, status?: string): Observable<SyncRecord[]> {
    let url = `${backendUrl}/sync/connections/${connectionId}/records`;
    if (status) url += `?status=${status}`;
    return this.http.get<SyncRecord[]>(url);
  }

  resolveConflict(connectionId: number, recordId: number, resolution: string): Observable<any> {
    return this.http.post(
      `${backendUrl}/sync/connections/${connectionId}/records/${recordId}/resolve-conflict`,
      { resolution }
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
}
