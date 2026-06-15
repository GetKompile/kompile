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

import { RagService } from './rag.service';
import { RagQuery, RagResponse, RetrievedContext } from '../models/api-models';

describe('RagService', () => {
  let service: RagService;
  let httpMock: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [HttpClientTestingModule],
      providers: [RagService]
    });

    service = TestBed.inject(RagService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    httpMock.verify();
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // 1. queryRag()
  // ─────────────────────────────────────────────────────────────────────────────

  describe('queryRag()', () => {
    it('should POST to /api/rag/query with the provided query object', () => {
      const query: RagQuery = { query: 'What is Kompile?' };
      const mockResponse: RagResponse = {
        query: 'What is Kompile?',
        answer: 'Kompile is an AI/ML platform.',
        retrieved_contexts: []
      };

      service.queryRag(query).subscribe(response => {
        expect(response.query).toBe('What is Kompile?');
        expect(response.answer).toBe('Kompile is an AI/ML platform.');
        expect(response.retrieved_contexts).toEqual([]);
      });

      const req = httpMock.expectOne(r => r.url.endsWith('/rag/query'));
      expect(req.request.method).toBe('POST');
      expect(req.request.body.query).toBe('What is Kompile?');
      req.flush(mockResponse);
    });

    it('should include maxResults in request body when provided', () => {
      const query: RagQuery = { query: 'Tell me about embeddings', maxResults: 10 };

      service.queryRag(query).subscribe();

      const req = httpMock.expectOne(r => r.url.endsWith('/rag/query'));
      expect(req.request.method).toBe('POST');
      expect(req.request.body.query).toBe('Tell me about embeddings');
      expect(req.request.body.maxResults).toBe(10);
      req.flush({ query: 'Tell me about embeddings', answer: 'Embeddings are...', retrieved_contexts: [] });
    });

    it('should return response with retrieved contexts', () => {
      const query: RagQuery = { query: 'How does vector search work?', maxResults: 3 };
      const mockContexts: RetrievedContext[] = [
        { document_id: 'doc-1', content: 'Vector search uses embeddings...', score: 0.92 },
        { document_id: 'doc-2', content: 'Dense retrieval models...', score: 0.87 },
        { document_id: 'doc-3', content: 'Lucene HNSW index...', score: 0.83 }
      ];
      const mockResponse: RagResponse = {
        query: 'How does vector search work?',
        answer: 'Vector search works by embedding queries and documents into the same vector space.',
        retrieved_contexts: mockContexts
      };

      service.queryRag(query).subscribe(response => {
        expect(response.retrieved_contexts.length).toBe(3);
        expect(response.retrieved_contexts[0].document_id).toBe('doc-1');
        expect(response.retrieved_contexts[0].score).toBe(0.92);
        expect(response.retrieved_contexts[1].document_id).toBe('doc-2');
      });

      const req = httpMock.expectOne(r => r.url.endsWith('/rag/query'));
      req.flush(mockResponse);
    });

    it('should return response with no retrieved contexts when index is empty', () => {
      const query: RagQuery = { query: 'anything' };
      const mockResponse: RagResponse = {
        query: 'anything',
        answer: 'No relevant documents found.',
        retrieved_contexts: []
      };

      service.queryRag(query).subscribe(response => {
        expect(response.retrieved_contexts.length).toBe(0);
        expect(response.answer).toBe('No relevant documents found.');
      });

      const req = httpMock.expectOne(r => r.url.endsWith('/rag/query'));
      req.flush(mockResponse);
    });

    it('should handle HTTP 400 Bad Request error', () => {
      const query: RagQuery = { query: '' };

      service.queryRag(query).subscribe({
        error: err => {
          expect(err).toBeTruthy();
          expect(err.message).toContain('400');
        }
      });

      const req = httpMock.expectOne(r => r.url.endsWith('/rag/query'));
      req.flush({ error: 'Query cannot be empty' }, { status: 400, statusText: 'Bad Request' });
    });

    it('should handle HTTP 500 Internal Server Error', () => {
      const query: RagQuery = { query: 'test query' };

      service.queryRag(query).subscribe({
        error: err => {
          expect(err).toBeTruthy();
          expect(err.message).toContain('500');
        }
      });

      const req = httpMock.expectOne(r => r.url.endsWith('/rag/query'));
      req.flush('Internal Server Error', { status: 500, statusText: 'Server Error' });
    });

    it('should handle HTTP 503 Service Unavailable when index is not loaded', () => {
      const query: RagQuery = { query: 'test' };

      service.queryRag(query).subscribe({
        error: err => {
          expect(err).toBeTruthy();
        }
      });

      const req = httpMock.expectOne(r => r.url.endsWith('/rag/query'));
      req.flush('Service Unavailable', { status: 503, statusText: 'Service Unavailable' });
    });

    it('should handle server-side error with error detail field', () => {
      const query: RagQuery = { query: 'test' };

      service.queryRag(query).subscribe({
        error: err => {
          expect(err.message).toContain('Details:');
        }
      });

      const req = httpMock.expectOne(r => r.url.endsWith('/rag/query'));
      req.flush({ error: 'Embedding model not initialized' }, { status: 500, statusText: 'Server Error' });
    });
  });
});
