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

import { TestBed } from '@angular/core/testing';
import { HttpClientTestingModule, HttpTestingController } from '@angular/common/http/testing';

import { ConversationalRagService } from './conversational-rag.service';
import {
  ConversationalChatRequest,
  ConversationalChatResponse,
  ConversationalRagOptions,
  ConversationHistoryResponse,
  RagServiceStatus,
  RerankerConfig,
  DEFAULT_RERANKER_CONFIG
} from '../models/api-models';

describe('ConversationalRagService', () => {
  let service: ConversationalRagService;
  let httpMock: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [HttpClientTestingModule],
      providers: [ConversationalRagService]
    });

    service = TestBed.inject(ConversationalRagService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    httpMock.verify();
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // 1. getStatus()
  // ─────────────────────────────────────────────────────────────────────────────

  describe('getStatus()', () => {
    it('should return service status', () => {
      const mockStatus: RagServiceStatus = { available: true, service: 'conversational-rag' };

      service.getStatus().subscribe(status => {
        expect(status.available).toBeTrue();
        expect(status.service).toBe('conversational-rag');
      });

      const req = httpMock.expectOne(r => r.url.endsWith('/chat/status'));
      expect(req.request.method).toBe('GET');
      req.flush(mockStatus);
    });

    it('should handle server error on status check', () => {
      service.getStatus().subscribe({
        error: err => {
          expect(err).toBeTruthy();
        }
      });

      const req = httpMock.expectOne(r => r.url.endsWith('/chat/status'));
      req.flush('Internal Server Error', { status: 500, statusText: 'Server Error' });
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // 2. chat()
  // ─────────────────────────────────────────────────────────────────────────────

  describe('chat()', () => {
    it('should send a basic chat request', () => {
      const request: ConversationalChatRequest = {
        message: 'What is RAG?'
      };

      const mockResponse: ConversationalChatResponse = {
        conversationId: 'conv-123',
        answer: 'RAG stands for Retrieval Augmented Generation.',
        documents: [
          { id: 'doc1', content: 'RAG combines...', score: 0.95 }
        ],
        metrics: {
          totalMs: 1500,
          retrievalMs: 200,
          generationMs: 1300,
          documentsRetrieved: 3
        }
      };

      service.chat(request).subscribe(response => {
        expect(response.conversationId).toBe('conv-123');
        expect(response.answer).toContain('Retrieval Augmented Generation');
        expect(response.documents?.length).toBe(1);
        expect(response.metrics?.totalMs).toBe(1500);
      });

      const req = httpMock.expectOne(r => r.url.endsWith('/chat'));
      expect(req.request.method).toBe('POST');
      expect(req.request.body.message).toBe('What is RAG?');
      req.flush(mockResponse);
    });

    it('should send chat request with conversation ID for follow-up', () => {
      const request: ConversationalChatRequest = {
        conversationId: 'conv-123',
        message: 'Tell me more'
      };

      service.chat(request).subscribe();

      const req = httpMock.expectOne(r => r.url.endsWith('/chat'));
      expect(req.request.body.conversationId).toBe('conv-123');
      expect(req.request.body.message).toBe('Tell me more');
      req.flush({ conversationId: 'conv-123', answer: 'More details...' });
    });

    it('should send chat request with full options', () => {
      const options: ConversationalRagOptions = {
        semanticK: 10,
        keywordK: 5,
        similarityThreshold: 0.7,
        maxHistoryMessages: 20,
        maxContextTokens: 8000,
        useToolCalling: true,
        systemPrompt: 'Be concise.',
        enableQueryProcessing: true,
        rerankerConfig: {
          ...DEFAULT_RERANKER_CONFIG,
          enabled: true,
          type: 'rm3'
        }
      };

      const request: ConversationalChatRequest = {
        message: 'Complex query',
        options
      };

      service.chat(request).subscribe();

      const req = httpMock.expectOne(r => r.url.endsWith('/chat'));
      const body = req.request.body;
      expect(body.options.semanticK).toBe(10);
      expect(body.options.keywordK).toBe(5);
      expect(body.options.similarityThreshold).toBe(0.7);
      expect(body.options.maxHistoryMessages).toBe(20);
      expect(body.options.maxContextTokens).toBe(8000);
      expect(body.options.useToolCalling).toBeTrue();
      expect(body.options.systemPrompt).toBe('Be concise.');
      expect(body.options.enableQueryProcessing).toBeTrue();
      expect(body.options.rerankerConfig.enabled).toBeTrue();
      expect(body.options.rerankerConfig.type).toBe('rm3');
      req.flush({ conversationId: 'conv-456', answer: 'Response' });
    });

    it('should handle chat response with query metadata', () => {
      const request: ConversationalChatRequest = {
        message: 'how vector search works'
      };

      const mockResponse: ConversationalChatResponse = {
        conversationId: 'conv-789',
        answer: 'Vector search works by...',
        queryMetadata: {
          originalQuery: 'how vector search works',
          rewrittenQuery: 'How does vector similarity search work in information retrieval systems?',
          wasRewritten: true,
          intent: 'technical_explanation'
        }
      };

      service.chat(request).subscribe(response => {
        expect(response.queryMetadata).toBeDefined();
        expect(response.queryMetadata?.wasRewritten).toBeTrue();
        expect(response.queryMetadata?.rewrittenQuery).toContain('vector similarity');
        expect(response.queryMetadata?.intent).toBe('technical_explanation');
      });

      const req = httpMock.expectOne(r => r.url.endsWith('/chat'));
      req.flush(mockResponse);
    });

    it('should handle chat response with error field', () => {
      const request: ConversationalChatRequest = { message: 'test' };

      service.chat(request).subscribe(response => {
        expect(response.error).toBe('No documents indexed');
      });

      const req = httpMock.expectOne(r => r.url.endsWith('/chat'));
      req.flush({ conversationId: 'c1', answer: '', error: 'No documents indexed' });
    });

    it('should handle HTTP 400 error', () => {
      const request: ConversationalChatRequest = { message: '' };

      service.chat(request).subscribe({
        error: err => {
          expect(err.message).toBe('Message cannot be empty');
        }
      });

      const req = httpMock.expectOne(r => r.url.endsWith('/chat'));
      req.flush({ error: 'Message cannot be empty' }, { status: 400, statusText: 'Bad Request' });
    });

    it('should handle HTTP 503 service unavailable', () => {
      const request: ConversationalChatRequest = { message: 'test' };

      service.chat(request).subscribe({
        error: err => {
          expect(err).toBeTruthy();
        }
      });

      const req = httpMock.expectOne(r => r.url.endsWith('/chat'));
      req.flush('Service Unavailable', { status: 503, statusText: 'Service Unavailable' });
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // 3. getHistory()
  // ─────────────────────────────────────────────────────────────────────────────

  describe('getHistory()', () => {
    it('should fetch conversation history', () => {
      const mockHistory: ConversationHistoryResponse = {
        conversationId: 'conv-123',
        messages: [
          { role: 'user', content: 'What is RAG?' },
          { role: 'assistant', content: 'RAG is...' }
        ]
      };

      service.getHistory('conv-123').subscribe(history => {
        expect(history.conversationId).toBe('conv-123');
        expect(history.messages.length).toBe(2);
        expect(history.messages[0].role).toBe('user');
      });

      const req = httpMock.expectOne(r => r.url.endsWith('/chat/conv-123/history'));
      expect(req.request.method).toBe('GET');
      req.flush(mockHistory);
    });

    it('should handle 404 for unknown conversation', () => {
      service.getHistory('nonexistent').subscribe({
        error: err => {
          expect(err).toBeTruthy();
        }
      });

      const req = httpMock.expectOne(r => r.url.endsWith('/chat/nonexistent/history'));
      req.flush('Not Found', { status: 404, statusText: 'Not Found' });
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // 4. clearConversation()
  // ─────────────────────────────────────────────────────────────────────────────

  describe('clearConversation()', () => {
    it('should clear a conversation', () => {
      service.clearConversation('conv-123').subscribe(result => {
        expect(result.conversationId).toBe('conv-123');
        expect(result.cleared).toBeTrue();
      });

      const req = httpMock.expectOne(r => r.url.endsWith('/chat/conv-123'));
      expect(req.request.method).toBe('DELETE');
      req.flush({ conversationId: 'conv-123', cleared: true });
    });

    it('should handle error when clearing non-existent conversation', () => {
      service.clearConversation('bad-id').subscribe({
        error: err => {
          expect(err).toBeTruthy();
        }
      });

      const req = httpMock.expectOne(r => r.url.endsWith('/chat/bad-id'));
      req.flush('Not Found', { status: 404, statusText: 'Not Found' });
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // 5. buildOptions() — pure logic
  // ─────────────────────────────────────────────────────────────────────────────

  describe('buildOptions()', () => {
    describe('Search type routing', () => {
      it('should set hybrid K values separately', () => {
        const opts = service.buildOptions('hybrid', 5, 5, 0.5, 10, false, true);
        expect(opts.semanticK).toBe(5);
        expect(opts.keywordK).toBe(5);
      });

      it('should combine K values for semantic search', () => {
        const opts = service.buildOptions('semantic', 3, 7, 0.5, 10, false, true);
        expect(opts.semanticK).toBe(10); // 3 + 7
        expect(opts.keywordK).toBe(0);
      });

      it('should combine K values for keyword search', () => {
        const opts = service.buildOptions('keyword', 3, 7, 0.5, 10, false, true);
        expect(opts.semanticK).toBe(0);
        expect(opts.keywordK).toBe(10); // 3 + 7
      });
    });

    describe('Common parameters', () => {
      it('should set similarity threshold', () => {
        const opts = service.buildOptions('hybrid', 5, 5, 0.8, 10, false, true);
        expect(opts.similarityThreshold).toBe(0.8);
      });

      it('should set max history messages', () => {
        const opts = service.buildOptions('hybrid', 5, 5, 0.5, 25, false, true);
        expect(opts.maxHistoryMessages).toBe(25);
      });

      it('should set tool calling flag', () => {
        const opts = service.buildOptions('hybrid', 5, 5, 0.5, 10, true, true);
        expect(opts.useToolCalling).toBeTrue();
      });

      it('should set query processing flag', () => {
        const opts = service.buildOptions('hybrid', 5, 5, 0.5, 10, false, false);
        expect(opts.enableQueryProcessing).toBeFalse();
      });
    });

    describe('System prompt', () => {
      it('should include trimmed system prompt when non-empty', () => {
        const opts = service.buildOptions(
          'hybrid', 5, 5, 0.5, 10, false, true, '  Custom prompt  '
        );
        expect(opts.systemPrompt).toBe('Custom prompt');
      });

      it('should not include system prompt when undefined', () => {
        const opts = service.buildOptions('hybrid', 5, 5, 0.5, 10, false, true);
        expect(opts.systemPrompt).toBeUndefined();
      });

      it('should not include system prompt when empty string', () => {
        const opts = service.buildOptions('hybrid', 5, 5, 0.5, 10, false, true, '');
        expect(opts.systemPrompt).toBeUndefined();
      });

      it('should not include system prompt when whitespace-only', () => {
        const opts = service.buildOptions('hybrid', 5, 5, 0.5, 10, false, true, '   ');
        expect(opts.systemPrompt).toBeUndefined();
      });
    });

    describe('Reranker config', () => {
      it('should include reranker config when enabled', () => {
        const rerankerConfig: RerankerConfig = {
          ...DEFAULT_RERANKER_CONFIG,
          enabled: true,
          type: 'rm3'
        };

        const opts = service.buildOptions(
          'hybrid', 5, 5, 0.5, 10, false, true, undefined, rerankerConfig
        );
        expect(opts.rerankerConfig).toBeDefined();
        expect(opts.rerankerConfig?.enabled).toBeTrue();
        expect(opts.rerankerConfig?.type).toBe('rm3');
      });

      it('should not include reranker config when disabled', () => {
        const rerankerConfig: RerankerConfig = {
          ...DEFAULT_RERANKER_CONFIG,
          enabled: false
        };

        const opts = service.buildOptions(
          'hybrid', 5, 5, 0.5, 10, false, true, undefined, rerankerConfig
        );
        expect(opts.rerankerConfig).toBeUndefined();
      });

      it('should not include reranker config when undefined', () => {
        const opts = service.buildOptions(
          'hybrid', 5, 5, 0.5, 10, false, true, undefined, undefined
        );
        expect(opts.rerankerConfig).toBeUndefined();
      });

      it('should pass through all reranker parameters', () => {
        const rerankerConfig: RerankerConfig = {
          enabled: true,
          type: 'rocchio',
          fbDocs: 15,
          fbTerms: 12,
          topK: 50,
          originalQueryWeight: 0.6,
          filterTerms: false,
          outputQuery: true,
          k1: 1.2,
          b: 0.6,
          newTermWeight: 0.3,
          alpha: 1.5,
          beta: 0.8,
          gamma: 0.2,
          useNegative: true,
          r: 25,
          n: 35,
          axiomBeta: 0.5,
          deterministic: false,
          seed: 99,
          crossEncoderModel: 'model-v2'
        };

        const opts = service.buildOptions(
          'hybrid', 5, 5, 0.5, 10, false, true, undefined, rerankerConfig
        );

        expect(opts.rerankerConfig).toEqual(rerankerConfig);
      });
    });

    describe('Edge cases', () => {
      it('should handle zero K values', () => {
        const opts = service.buildOptions('hybrid', 0, 0, 0.5, 10, false, true);
        expect(opts.semanticK).toBe(0);
        expect(opts.keywordK).toBe(0);
      });

      it('should handle very large K values', () => {
        const opts = service.buildOptions('hybrid', 500, 500, 0.5, 10, false, true);
        expect(opts.semanticK).toBe(500);
        expect(opts.keywordK).toBe(500);
      });

      it('should handle zero similarity threshold', () => {
        const opts = service.buildOptions('hybrid', 5, 5, 0.0, 10, false, true);
        expect(opts.similarityThreshold).toBe(0.0);
      });

      it('should handle threshold of 1.0', () => {
        const opts = service.buildOptions('hybrid', 5, 5, 1.0, 10, false, true);
        expect(opts.similarityThreshold).toBe(1.0);
      });

      it('should handle zero max history messages', () => {
        const opts = service.buildOptions('hybrid', 5, 5, 0.5, 0, false, true);
        expect(opts.maxHistoryMessages).toBe(0);
      });

      it('should handle all flags enabled simultaneously', () => {
        const rerankerConfig: RerankerConfig = {
          ...DEFAULT_RERANKER_CONFIG,
          enabled: true,
          type: 'cross_encoder',
          crossEncoderModel: 'ms-marco'
        };

        const opts = service.buildOptions(
          'hybrid', 10, 10, 0.9, 50, true, true,
          'Expert mode. Use technical language.',
          rerankerConfig
        );

        expect(opts.semanticK).toBe(10);
        expect(opts.keywordK).toBe(10);
        expect(opts.similarityThreshold).toBe(0.9);
        expect(opts.maxHistoryMessages).toBe(50);
        expect(opts.useToolCalling).toBeTrue();
        expect(opts.enableQueryProcessing).toBeTrue();
        expect(opts.systemPrompt).toBe('Expert mode. Use technical language.');
        expect(opts.rerankerConfig?.type).toBe('cross_encoder');
      });
    });
  });
});
