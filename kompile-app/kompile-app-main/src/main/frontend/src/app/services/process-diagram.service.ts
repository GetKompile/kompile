/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable, Subject, BehaviorSubject } from 'rxjs';
import { BaseService } from './base.service';
import { ProvenanceCitation } from '../models/graph-models';

// ─── Interfaces ─────────────────────────────────────────────────────────────

export interface DiagramSession {
  id: number;
  factSheetId: number | null;
  prompt: string;
  agentName: string;
  status: string; // RUNNING, COMPLETED, COMPLETED_NO_DIAGRAM, FAILED, CANCELLED
  transcriptJson: string | null;
  mermaidCode: string | null;
  title: string | null;
  description: string | null;
  sourcesJson: string | null;
  processDefinitionId: string | null;
  errorMessage: string | null;
  createdAt: string;
  updatedAt: string;
  completedAt: string | null;
}

export interface DiagramGenerateRequest {
  prompt: string;
  agentName: string;
  factSheetId: number | null;
}

export interface DiagramFinalizeRequest {
  transcriptJson: string;
  mermaidCode: string | null;
  title: string | null;
  description: string | null;
  sourcesJson: string | null;
}

export interface TranscriptEntry {
  timestamp: string;
  type: 'chunk' | 'tool_use' | 'sources' | 'start' | 'complete' | 'stats' | 'error' | 'session_created';
  content: string;
  toolName?: string;
  toolInput?: string;
}

// ─── Service ────────────────────────────────────────────────────────────────

@Injectable({
  providedIn: 'root'
})
export class ProcessDiagramService extends BaseService {

  private readonly baseUrl = `${this.backendUrl}/process/diagrams`;

  // Live streaming state
  private transcript$ = new BehaviorSubject<TranscriptEntry[]>([]);
  private streamingContent$ = new BehaviorSubject<string>('');
  private isStreaming$ = new BehaviorSubject<boolean>(false);
  private currentSessionId$ = new BehaviorSubject<number | null>(null);
  private streamError$ = new Subject<string>();
  private streamComplete$ = new Subject<string>(); // emits full content on completion

  private abortController: AbortController | null = null;
  private contentChunks: string[] = [];

  constructor(private http: HttpClient) {
    super();
  }

  // ── Observable accessors ──────────────────────────────────────────────────

  get transcript(): Observable<TranscriptEntry[]> { return this.transcript$.asObservable(); }
  get streamingContent(): Observable<string> { return this.streamingContent$.asObservable(); }
  get isStreaming(): Observable<boolean> { return this.isStreaming$.asObservable(); }
  get currentSessionId(): Observable<number | null> { return this.currentSessionId$.asObservable(); }
  get streamError(): Observable<string> { return this.streamError$.asObservable(); }
  get streamComplete(): Observable<string> { return this.streamComplete$.asObservable(); }

  // ── Streaming generation ──────────────────────────────────────────────────

  /**
   * Start diagram generation via SSE. Returns immediately; events are emitted
   * to the observable streams above.
   */
  startGeneration(request: DiagramGenerateRequest): void {
    this.cancelGeneration();

    this.transcript$.next([]);
    this.streamingContent$.next('');
    this.contentChunks = [];
    this.isStreaming$.next(true);
    this.currentSessionId$.next(null);

    this.abortController = new AbortController();

    fetch(`${this.baseUrl}/generate`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(request),
      signal: this.abortController.signal
    }).then(response => {
      if (!response.ok) {
        throw new Error(`HTTP ${response.status}: ${response.statusText}`);
      }
      const reader = response.body!.getReader();
      const decoder = new TextDecoder();
      let buffer = '';

      const processStream = (): Promise<void> => {
        return reader.read().then(({ done, value }) => {
          if (done) {
            this.onStreamEnd();
            return;
          }

          buffer += decoder.decode(value, { stream: true });
          const lines = buffer.split('\n');
          buffer = lines.pop() || '';

          for (const line of lines) {
            if (line.startsWith('data:')) {
              const data = line.substring(5).trim();
              if (data) this.handleSseData(data);
            } else if (line.startsWith('event:')) {
              // Event type is embedded in the data payload
            }
          }

          return processStream();
        });
      };

      return processStream();
    }).catch(err => {
      if (err.name === 'AbortError') return;
      this.streamError$.next(err.message || 'Stream failed');
      this.isStreaming$.next(false);
    });
  }

  cancelGeneration(): void {
    if (this.abortController) {
      this.abortController.abort();
      this.abortController = null;
    }
    this.isStreaming$.next(false);
  }

  // ── CRUD endpoints ────────────────────────────────────────────────────────

  listSessions(factSheetId?: number): Observable<DiagramSession[]> {
    const params = factSheetId != null ? `?factSheetId=${factSheetId}` : '';
    return this.http.get<DiagramSession[]>(`${this.baseUrl}${params}`);
  }

  getSession(sessionId: number): Observable<DiagramSession> {
    return this.http.get<DiagramSession>(`${this.baseUrl}/${sessionId}`);
  }

  deleteSession(sessionId: number): Observable<void> {
    return this.http.delete<void>(`${this.baseUrl}/${sessionId}`);
  }

  finalizeSession(sessionId: number, request: DiagramFinalizeRequest): Observable<DiagramSession> {
    return this.http.post<DiagramSession>(`${this.baseUrl}/${sessionId}/finalize`, request);
  }

  failSession(sessionId: number, error: string): Observable<DiagramSession> {
    return this.http.post<DiagramSession>(`${this.baseUrl}/${sessionId}/fail`, { error });
  }

  updateTitle(sessionId: number, title: string): Observable<DiagramSession> {
    return this.http.patch<DiagramSession>(`${this.baseUrl}/${sessionId}/title`, { title });
  }

  updateMermaid(sessionId: number, mermaidCode: string): Observable<DiagramSession> {
    return this.http.patch<DiagramSession>(`${this.baseUrl}/${sessionId}/mermaid`, { mermaidCode });
  }

  // ── Diagram ↔ Process conversion ──────────────────────────────────────────

  /**
   * Convert a diagram session's Mermaid code into an executable ProcessDefinition.
   */
  convertToProcess(sessionId: number): Observable<any> {
    return this.http.post<any>(`${this.baseUrl}/${sessionId}/convert-to-process`, {});
  }

  /**
   * Render a ProcessDefinition as a Mermaid flowchart.
   */
  renderProcessDiagram(processDefinitionId: string): Observable<{ mermaidCode: string; processDefinitionId: string }> {
    return this.http.get<{ mermaidCode: string; processDefinitionId: string }>(
      `${this.baseUrl}/render/${processDefinitionId}`
    );
  }

  /**
   * Preview what a Mermaid diagram would look like as a ProcessDefinition (without persisting).
   */
  previewConversion(mermaidCode: string, processName?: string): Observable<any> {
    return this.http.post<any>(`${this.baseUrl}/preview-conversion`, { mermaidCode, processName });
  }

  // ── Provenance & Cross-Linking ────────────────────────────────────────────

  /**
   * Get structured provenance citations for a diagram session.
   */
  getSessionProvenance(sessionId: number): Observable<ProvenanceCitation[]> {
    return this.http.get<ProvenanceCitation[]>(`${this.baseUrl}/${sessionId}/provenance`);
  }

  /**
   * Get the diagram session linked to a given process definition.
   */
  getByProcessDefinitionId(processDefinitionId: string): Observable<DiagramSession> {
    return this.http.get<DiagramSession>(`${this.baseUrl}/by-process/${processDefinitionId}`);
  }

  // ── Private SSE handling ──────────────────────────────────────────────────

  private handleSseData(rawData: string): void {
    try {
      const parsed = JSON.parse(rawData);

      // Session created event (from our controller)
      if (parsed.sessionId && !this.currentSessionId$.value) {
        this.currentSessionId$.next(parsed.sessionId);
      }

      // Text chunk from agent
      if (typeof parsed === 'string') {
        this.contentChunks.push(parsed);
        this.streamingContent$.next(this.contentChunks.join(''));
        this.addTranscriptEntry('chunk', parsed);
      } else if (parsed.processId && parsed.agent) {
        // Start event
        this.addTranscriptEntry('start', `Agent ${parsed.agent} started (process: ${parsed.processId})`);
      } else if (parsed.content !== undefined && parsed.processId) {
        // Complete event
        this.addTranscriptEntry('complete', 'Generation complete');
      } else if (parsed.durationMs !== undefined) {
        // Stats event
        this.addTranscriptEntry('stats', JSON.stringify(parsed));
      } else if (Array.isArray(parsed)) {
        // Sources event
        this.addTranscriptEntry('sources', JSON.stringify(parsed));
      } else if (parsed.error) {
        this.addTranscriptEntry('error', parsed.error);
        this.streamError$.next(parsed.error);
      }
    } catch {
      // Plain text chunk
      this.contentChunks.push(rawData);
      this.streamingContent$.next(this.contentChunks.join(''));
      this.addTranscriptEntry('chunk', rawData);
    }
  }

  private addTranscriptEntry(type: TranscriptEntry['type'], content: string): void {
    const entries = this.transcript$.value;
    entries.push({
      timestamp: new Date().toISOString(),
      type,
      content
    });
    this.transcript$.next([...entries]);
  }

  private onStreamEnd(): void {
    const fullContent = this.contentChunks.join('');
    this.streamComplete$.next(fullContent);
    this.isStreaming$.next(false);
  }
}
