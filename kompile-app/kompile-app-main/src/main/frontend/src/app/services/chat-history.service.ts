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

export interface ChatMessageDto {
  id?: number;
  role: 'USER' | 'ASSISTANT' | 'SYSTEM' | 'TOOL';
  content: string;
  createdAt?: string;
  model?: string;
  tokenCount?: number;
}

export interface ChatSessionDto {
  sessionId: string;
  title: string;
  description?: string;
  createdAt: string;
  updatedAt: string;
  userId?: string;
  source?: string;
  messages?: ChatMessageDto[];
  messageCount: number;
  originalTimestamp?: number;
}

export interface CreateSessionRequest {
  title: string;
  userId?: string;
}

export interface AddMessageRequest {
  role: 'USER' | 'ASSISTANT' | 'SYSTEM' | 'TOOL';
  content: string;
  model?: string;
}

@Injectable({
  providedIn: 'root'
})
export class ChatHistoryService extends BaseService {

  private readonly chatHistoryUrl = `${this.backendUrl}/chat-history`;

  constructor(private http: HttpClient) {
    super();
  }

  /**
   * Create a new chat session.
   */
  createSession(title: string, userId?: string): Observable<ChatSessionDto> {
    const request: CreateSessionRequest = { title, userId };
    return this.http.post<ChatSessionDto>(`${this.chatHistoryUrl}/sessions`, request);
  }

  /**
   * Get all sessions (optionally filtered by user).
   */
  getSessions(userId?: string, source?: string): Observable<ChatSessionDto[]> {
    let params = new HttpParams();
    if (userId) {
      params = params.set('userId', userId);
    }
    if (source) {
      params = params.set('source', source);
    }
    return this.http.get<ChatSessionDto[]>(`${this.chatHistoryUrl}/sessions`, { params });
  }

  /**
   * Get a specific session with all messages.
   */
  getSession(sessionId: string): Observable<ChatSessionDto> {
    return this.http.get<ChatSessionDto>(`${this.chatHistoryUrl}/sessions/${sessionId}`);
  }

  /**
   * Update session title.
   */
  updateSessionTitle(sessionId: string, title: string): Observable<ChatSessionDto> {
    return this.http.patch<ChatSessionDto>(
      `${this.chatHistoryUrl}/sessions/${sessionId}/title`,
      { title }
    );
  }

  /**
   * Delete a session.
   */
  deleteSession(sessionId: string): Observable<void> {
    return this.http.delete<void>(`${this.chatHistoryUrl}/sessions/${sessionId}`);
  }

  /**
   * Add a message to a session.
   */
  addMessage(sessionId: string, request: AddMessageRequest): Observable<ChatMessageDto> {
    return this.http.post<ChatMessageDto>(
      `${this.chatHistoryUrl}/sessions/${sessionId}/messages`,
      request
    );
  }

  /**
   * Get all messages for a session.
   */
  getSessionMessages(sessionId: string): Observable<ChatMessageDto[]> {
    return this.http.get<ChatMessageDto[]>(`${this.chatHistoryUrl}/sessions/${sessionId}/messages`);
  }

  /**
   * Get a single message by ID with full content.
   * Used when frontend needs complete message data including metadata.
   */
  getMessageById(messageId: number): Observable<ChatMessageDto> {
    return this.http.get<ChatMessageDto>(`${this.chatHistoryUrl}/messages/${messageId}`);
  }

  /**
   * Get full content of a message by ID.
   * Used for copy/save/parse operations where we need complete, untruncated content.
   */
  getMessageContent(messageId: number): Observable<string> {
    return this.http.get(`${this.chatHistoryUrl}/messages/${messageId}/content`, { responseType: 'text' });
  }

  /**
   * Get all messages up to and including a specific message.
   * Used for fork/branch operations where we need conversation history up to a point.
   */
  getMessagesUntil(sessionId: string, messageId: number): Observable<ChatMessageDto[]> {
    return this.http.get<ChatMessageDto[]>(
      `${this.chatHistoryUrl}/sessions/${sessionId}/messages/until/${messageId}`
    );
  }
}
