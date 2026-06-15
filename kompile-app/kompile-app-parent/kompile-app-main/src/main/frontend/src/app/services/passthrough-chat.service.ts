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
import { PassthroughMessage, PassthroughOptions } from '../models/api-models';

@Injectable({ providedIn: 'root' })
export class PassthroughChatService extends BaseService {

  private eventSource: EventSource | null = null;

  // State
  sessionId$ = new BehaviorSubject<string | null>(null);
  connected$ = new BehaviorSubject<boolean>(false);
  agentReady$ = new BehaviorSubject<boolean>(false);
  messages$ = new BehaviorSubject<PassthroughMessage[]>([]);
  streamingContent$ = new BehaviorSubject<string>('');
  error$ = new Subject<string>();

  private contentBuffer: string[] = [];
  private throttleTimer: any = null;

  constructor(private http: HttpClient) {
    super();
  }

  connect(options: PassthroughOptions): void {
    if (this.eventSource) {
      this.disconnect();
    }

    const params = new URLSearchParams();
    params.set('agentName', options.agentName);
    params.set('skipPermissions', String(options.skipPermissions));
    params.set('injectMcpTools', String(options.injectMcpTools));
    if (options.workingDirectory) {
      params.set('workingDirectory', options.workingDirectory);
    }
    if (options.sessionName) {
      params.set('sessionName', options.sessionName);
    }

    const url = `${this.backendUrl}/agents/passthrough/connect?${params.toString()}`;
    this.eventSource = new EventSource(url);

    this.eventSource.addEventListener('session_started', (event: any) => {
      const data = JSON.parse(event.data);
      this.sessionId$.next(data.sessionId);
      this.connected$.next(true);
      this.agentReady$.next(true);
    });

    this.eventSource.addEventListener('chunk', (event: any) => {
      const text = typeof event.data === 'string' ? event.data : JSON.parse(event.data);
      this.contentBuffer.push(text);
      this.throttleFlush();
    });

    this.eventSource.addEventListener('tool_use', (_event: any) => {
      // Tool use events are displayed as part of the streaming content
    });

    this.eventSource.addEventListener('turn_complete', (event: any) => {
      // Flush any remaining content
      this.flushBuffer();

      const data = JSON.parse(event.data);
      const content = data.content || this.streamingContent$.value;
      if (content) {
        const msgs = [...this.messages$.value];
        msgs.push({
          role: 'ASSISTANT',
          content: content,
          timestamp: new Date().toISOString()
        });
        this.messages$.next(msgs);
      }
      this.streamingContent$.next('');
      this.contentBuffer = [];
      this.agentReady$.next(true);
    });

    this.eventSource.addEventListener('session_ended', (_event: any) => {
      this.cleanupConnection();
    });

    this.eventSource.addEventListener('error', (event: any) => {
      if (event.data) {
        try {
          const data = JSON.parse(event.data);
          this.error$.next(data.message || 'Connection error');
        } catch {
          this.error$.next('Connection error');
        }
      }
      // EventSource may reconnect automatically; only clean up on fatal errors
      if (this.eventSource?.readyState === EventSource.CLOSED) {
        this.cleanupConnection();
      }
    });

    this.eventSource.onerror = () => {
      if (this.eventSource?.readyState === EventSource.CLOSED) {
        this.cleanupConnection();
      }
    };
  }

  sendMessage(message: string): void {
    const sessionId = this.sessionId$.value;
    if (!sessionId) return;

    // Add user message to list
    const msgs = [...this.messages$.value];
    msgs.push({
      role: 'USER',
      content: message,
      timestamp: new Date().toISOString()
    });
    this.messages$.next(msgs);

    this.agentReady$.next(false);
    this.streamingContent$.next('');
    this.contentBuffer = [];

    this.http.post(`${this.backendUrl}/agents/passthrough/send`, {
      sessionId: sessionId,
      message: message
    }).subscribe({
      error: (err) => this.error$.next('Failed to send message: ' + err.message)
    });
  }

  disconnect(): void {
    const sessionId = this.sessionId$.value;
    if (sessionId) {
      this.http.post(`${this.backendUrl}/agents/passthrough/end/${sessionId}`, {}).subscribe();
    }
    this.cleanupConnection();
  }

  private cleanupConnection(): void {
    if (this.eventSource) {
      this.eventSource.close();
      this.eventSource = null;
    }
    if (this.throttleTimer) {
      clearTimeout(this.throttleTimer);
      this.throttleTimer = null;
    }
    this.sessionId$.next(null);
    this.connected$.next(false);
    this.agentReady$.next(false);
    this.streamingContent$.next('');
    this.contentBuffer = [];
  }

  private throttleFlush(): void {
    if (this.throttleTimer) return;
    this.throttleTimer = setTimeout(() => {
      this.flushBuffer();
      this.throttleTimer = null;
    }, 50);
  }

  private flushBuffer(): void {
    if (this.contentBuffer.length > 0) {
      const current = this.streamingContent$.value + this.contentBuffer.join('');
      this.streamingContent$.next(current);
      this.contentBuffer = [];
    }
  }

  clearMessages(): void {
    this.messages$.next([]);
    this.streamingContent$.next('');
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // SESSION MANAGEMENT
  // ═══════════════════════════════════════════════════════════════════════════

  listSessions(): Observable<any[]> {
    return this.http.get<any[]>(`${this.backendUrl}/agents/passthrough/sessions`);
  }

  getSessionStatus(sessionId: string): Observable<any> {
    return this.http.get<any>(`${this.backendUrl}/agents/passthrough/status/${encodeURIComponent(sessionId)}`);
  }
}
