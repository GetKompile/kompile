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
import { HttpClient, HttpParams } from '@angular/common/http';
import { Observable } from 'rxjs';
import { BaseService } from './base.service';
import { ChatSessionDto } from './chat-history.service';

export interface CliSourceInfo {
  path: string;
  count: number;
  available: boolean;
}

export interface CliSessionSummary {
  sessionId: string;
  source: string;
  title: string;
  agent: string;
  messageCount: number;
  lastModified: number;
}

export interface ParsedTurn {
  role: string;
  content: string;
}

export interface CliTranscriptDetail {
  sessionId: string;
  source: string;
  title: string;
  agent: string;
  turns: ParsedTurn[];
}

export interface ExportResult {
  transcriptPath: string;
  sessionId: string;
}

@Injectable({
  providedIn: 'root'
})
export class CliTranscriptService extends BaseService {

  private readonly cliUrl = `${this.backendUrl}/chat-history/cli`;

  constructor(private http: HttpClient) {
    super();
  }

  /**
   * Discover available conversation sources with counts.
   */
  discoverSources(): Observable<{ [source: string]: CliSourceInfo }> {
    return this.http.get<{ [source: string]: CliSourceInfo }>(`${this.cliUrl}/sources`);
  }

  /**
   * List CLI sessions, optionally filtered by source.
   */
  listSessions(source?: string): Observable<CliSessionSummary[]> {
    let params = new HttpParams();
    if (source && source !== 'all') {
      params = params.set('source', source);
    }
    return this.http.get<CliSessionSummary[]>(`${this.cliUrl}/sessions`, { params });
  }

  /**
   * Read a specific CLI transcript with parsed turns.
   */
  getTranscript(sessionId: string, source: string): Observable<CliTranscriptDetail> {
    const params = new HttpParams().set('source', source);
    return this.http.get<CliTranscriptDetail>(
      `${this.cliUrl}/sessions/${encodeURIComponent(sessionId)}`,
      { params }
    );
  }

  /**
   * Import a CLI transcript into the app database.
   */
  importTranscript(sessionId: string, source: string): Observable<ChatSessionDto> {
    const params = new HttpParams().set('source', source);
    return this.http.post<ChatSessionDto>(
      `${this.cliUrl}/sessions/${encodeURIComponent(sessionId)}/import`,
      null,
      { params }
    );
  }

  /**
   * Export an app session to CLI transcript format.
   */
  exportSession(sessionId: string): Observable<ExportResult> {
    return this.http.post<ExportResult>(
      `${this.cliUrl}/export/${encodeURIComponent(sessionId)}`,
      null
    );
  }
}
