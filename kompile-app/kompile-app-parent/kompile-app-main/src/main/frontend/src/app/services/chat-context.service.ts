import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../../environments/environment';

export type SourceMode = 'FULL' | 'SUMMARY_ONLY' | 'EXCLUDED';

export interface ChatSessionContext {
  id: number;
  sessionId: string;
  factSheetId?: number;
  factId: number;
  sourceDisplayName: string;
  mode: SourceMode;
  createdAt: string;
  updatedAt: string;
}

export interface SetModeRequest {
  factSheetId?: number;
  factId?: number;
  sourceDisplayName?: string;
  mode: SourceMode;
}

@Injectable({ providedIn: 'root' })
export class ChatContextService {
  private baseUrl = `${environment.apiUrl}/chat-sessions`;

  constructor(private http: HttpClient) {}

  getContext(sessionId: string): Observable<ChatSessionContext[]> {
    return this.http.get<ChatSessionContext[]>(`${this.baseUrl}/${sessionId}/context`);
  }

  setMode(sessionId: string, request: SetModeRequest): Observable<ChatSessionContext> {
    return this.http.put<ChatSessionContext>(`${this.baseUrl}/${sessionId}/context`, request);
  }

  getExcluded(sessionId: string): Observable<number[]> {
    return this.http.get<number[]>(`${this.baseUrl}/${sessionId}/context/excluded`);
  }

  clearContext(sessionId: string): Observable<void> {
    return this.http.delete<void>(`${this.baseUrl}/${sessionId}/context`);
  }
}
