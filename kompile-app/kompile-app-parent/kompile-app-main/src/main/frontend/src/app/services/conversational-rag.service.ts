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
import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Observable, throwError, Subject } from 'rxjs';
import { catchError, map } from 'rxjs/operators';
import {
  ConversationalChatRequest,
  ConversationalChatResponse,
  ConversationalRagOptions,
  ConversationHistoryResponse,
  RagServiceStatus,
  RerankerConfig
} from '../models/api-models';
import { BaseService } from './base.service';

/**
 * Service for conversational RAG operations.
 * Provides methods for multi-turn conversations with RAG-enhanced responses.
 */
@Injectable({
  providedIn: 'root'
})
export class ConversationalRagService extends BaseService {

  constructor(private http: HttpClient) {
    super();
  }

  /**
   * Sends a chat message and receives a RAG-enhanced response.
   */
  chat(request: ConversationalChatRequest): Observable<ConversationalChatResponse> {
    return this.http.post<ConversationalChatResponse>(`${this.backendUrl}/chat`, request)
      .pipe(catchError(this.handleError));
  }

  /**
   * Streams a chat response using Server-Sent Events.
   * Returns an Observable that emits text chunks as they arrive.
   */
  chatStream(request: ConversationalChatRequest): Observable<string> {
    const subject = new Subject<string>();

    fetch(`${this.backendUrl}/chat/stream`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'Accept': 'text/event-stream'
      },
      body: JSON.stringify(request)
    }).then(response => {
      if (!response.ok) {
        subject.error(new Error(`HTTP error! status: ${response.status}`));
        return;
      }

      const reader = response.body?.getReader();
      if (!reader) {
        subject.error(new Error('No response body'));
        return;
      }

      const decoder = new TextDecoder();

      const readChunk = (): void => {
        reader.read().then(({ done, value }) => {
          if (done) {
            subject.complete();
            return;
          }

          const text = decoder.decode(value, { stream: true });
          const lines = text.split('\n');

          for (const line of lines) {
            if (line.startsWith('data: ')) {
              const data = line.substring(6).trim();
              if (data === '[DONE]') {
                subject.complete();
                return;
              }
              if (data) {
                // Unescape SSE data
                const unescaped = data
                  .replace(/\\n/g, '\n')
                  .replace(/\\r/g, '\r')
                  .replace(/\\"/g, '"')
                  .replace(/\\\\/g, '\\');
                subject.next(unescaped);
              }
            }
          }

          readChunk();
        }).catch(error => {
          subject.error(error);
        });
      };

      readChunk();
    }).catch(error => {
      subject.error(error);
    });

    return subject.asObservable();
  }

  /**
   * Gets conversation history for a specific conversation.
   */
  getHistory(conversationId: string): Observable<ConversationHistoryResponse> {
    return this.http.get<ConversationHistoryResponse>(`${this.backendUrl}/chat/${conversationId}/history`)
      .pipe(catchError(this.handleError));
  }

  /**
   * Clears a conversation.
   */
  clearConversation(conversationId: string): Observable<{ conversationId: string; cleared: boolean }> {
    return this.http.delete<{ conversationId: string; cleared: boolean }>(`${this.backendUrl}/chat/${conversationId}`)
      .pipe(catchError(this.handleError));
  }

  /**
   * Gets the RAG service status.
   */
  getStatus(): Observable<RagServiceStatus> {
    return this.http.get<RagServiceStatus>(`${this.backendUrl}/chat/status`)
      .pipe(catchError(this.handleError));
  }

  /**
   * Helper to build options from UI settings.
   */
  buildOptions(
    searchType: 'hybrid' | 'semantic' | 'keyword',
    semanticK: number,
    keywordK: number,
    similarityThreshold: number,
    maxHistoryMessages: number,
    useToolCalling: boolean,
    enableQueryProcessing: boolean,
    systemPrompt?: string,
    rerankerConfig?: RerankerConfig
  ): ConversationalRagOptions {
    const options: ConversationalRagOptions = {
      similarityThreshold,
      maxHistoryMessages,
      useToolCalling,
      enableQueryProcessing
    };

    // Set K values based on search type
    switch (searchType) {
      case 'semantic':
        options.semanticK = semanticK + keywordK; // Use total
        options.keywordK = 0;
        break;
      case 'keyword':
        options.semanticK = 0;
        options.keywordK = semanticK + keywordK; // Use total
        break;
      case 'hybrid':
      default:
        options.semanticK = semanticK;
        options.keywordK = keywordK;
        break;
    }

    if (systemPrompt && systemPrompt.trim()) {
      options.systemPrompt = systemPrompt.trim();
    }

    // Add reranker configuration if provided and enabled
    if (rerankerConfig && rerankerConfig.enabled) {
      options.rerankerConfig = rerankerConfig;
    }

    return options;
  }

  private handleError(error: HttpErrorResponse): Observable<never> {
    let errorMessage = 'Unknown error occurred';

    if (error.error instanceof ErrorEvent) {
      // Client-side error
      errorMessage = `Client error: ${error.error.message}`;
    } else {
      // Server-side error
      errorMessage = `Server error: ${error.status} - ${error.message}`;
      if (error.error?.error) {
        errorMessage = error.error.error;
      }
    }

    console.error('ConversationalRagService error:', errorMessage);
    return throwError(() => new Error(errorMessage));
  }
}
