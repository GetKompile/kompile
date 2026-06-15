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

import { ChunkManagerService } from './chunk-manager.service';
import {
  ChunkListResponse,
  ChunkDetail,
  ChunkEditDetail,
  ChunkUpdateRequest,
  ChunkUpdateResponse,
  SourceListResponse,
  OperationResponse,
  DuplicateAnalysisResponse,
  DeduplicationRequest,
  DeduplicationResult,
  ExportRequest,
  ExportResponse,
  ClearTokenResponse
} from '../models/chunk-manager.models';

// ─────────────────────────────────────────────────────────────────────────────
// Helpers
// ─────────────────────────────────────────────────────────────────────────────

function makeChunkListResponse(overrides: Partial<ChunkListResponse> = {}): ChunkListResponse {
  return {
    chunks: [
      { id: 'chunk-1', preview: 'Hello world', contentLength: 11, inKeywordIndex: true, inVectorStore: true },
      { id: 'chunk-2', preview: 'Another chunk', contentLength: 13, inKeywordIndex: false, inVectorStore: true }
    ],
    offset: 0,
    limit: 20,
    totalCount: 2,
    pageCount: 1,
    ...overrides
  };
}

function makeChunkDetail(overrides: Partial<ChunkDetail> = {}): ChunkDetail {
  return {
    id: 'chunk-1',
    content: 'Hello world',
    contentLength: 11,
    metadata: { source: 'test.pdf' },
    inKeywordIndex: true,
    inVectorStore: true,
    ...overrides
  };
}

function makeChunkEditDetail(overrides: Partial<ChunkEditDetail> = {}): ChunkEditDetail {
  return {
    id: 'chunk-1',
    content: 'Hello world',
    contentLength: 11,
    metadata: { source: 'test.pdf' },
    inKeywordIndex: true,
    inVectorStore: true,
    semanticType: 'TEXT',
    sourceTitle: 'Test Doc',
    entities: [],
    ...overrides
  };
}

function makeSourceListResponse(overrides: Partial<SourceListResponse> = {}): SourceListResponse {
  return {
    sources: [
      { sourceId: 'src-1', filename: 'doc1.pdf', chunkCount: 5, keywordChunkCount: 5, vectorChunkCount: 5 },
      { sourceId: 'src-2', filename: 'doc2.txt', chunkCount: 3, keywordChunkCount: 3, vectorChunkCount: 2 }
    ],
    totalSources: 2,
    ...overrides
  };
}

function makeOperationResponse(overrides: Partial<OperationResponse> = {}): OperationResponse {
  return {
    success: true,
    message: 'Operation completed',
    affectedCount: 1,
    ...overrides
  };
}

// ─────────────────────────────────────────────────────────────────────────────
// Tests
// ─────────────────────────────────────────────────────────────────────────────

describe('ChunkManagerService', () => {
  let service: ChunkManagerService;
  let httpMock: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [HttpClientTestingModule],
      providers: [ChunkManagerService]
    });

    service = TestBed.inject(ChunkManagerService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    httpMock.verify();
  });

  // ─────────────────────────────────────────────────────────────────────────
  // listChunks()
  // ─────────────────────────────────────────────────────────────────────────

  describe('listChunks()', () => {
    it('should GET /chunk-manager/chunks with default offset and limit', () => {
      const mock = makeChunkListResponse();

      service.listChunks().subscribe(res => {
        expect(res.chunks.length).toBe(2);
        expect(res.totalCount).toBe(2);
      });

      const req = httpMock.expectOne(r =>
        r.url.endsWith('/chunk-manager/chunks') &&
        r.params.get('offset') === '0' &&
        r.params.get('limit') === '20'
      );
      expect(req.request.method).toBe('GET');
      req.flush(mock);
    });

    it('should include sourceId param when provided', () => {
      service.listChunks(0, 10, 'src-1').subscribe();

      const req = httpMock.expectOne(r =>
        r.url.endsWith('/chunk-manager/chunks') &&
        r.params.get('sourceId') === 'src-1'
      );
      expect(req.request.params.get('offset')).toBe('0');
      expect(req.request.params.get('limit')).toBe('10');
      req.flush(makeChunkListResponse());
    });

    it('should NOT include sourceId when not provided', () => {
      service.listChunks(20, 20).subscribe();

      const req = httpMock.expectOne(r => r.url.endsWith('/chunk-manager/chunks'));
      expect(req.request.params.has('sourceId')).toBeFalse();
      expect(req.request.params.get('offset')).toBe('20');
      req.flush(makeChunkListResponse({ offset: 20 }));
    });

    it('should handle server error', () => {
      service.listChunks().subscribe({ error: err => expect(err).toBeTruthy() });

      const req = httpMock.expectOne(r => r.url.endsWith('/chunk-manager/chunks'));
      req.flush('Server Error', { status: 500, statusText: 'Internal Server Error' });
    });
  });

  // ─────────────────────────────────────────────────────────────────────────
  // getChunk()
  // ─────────────────────────────────────────────────────────────────────────

  describe('getChunk()', () => {
    it('should GET /chunk-manager/chunks/:id and return ChunkDetail', () => {
      const mock = makeChunkDetail();

      service.getChunk('chunk-1').subscribe(res => {
        expect(res.id).toBe('chunk-1');
        expect(res.content).toBe('Hello world');
      });

      const req = httpMock.expectOne(r => r.url.includes('/chunk-manager/chunks/chunk-1'));
      expect(req.request.method).toBe('GET');
      req.flush(mock);
    });

    it('should URL-encode the chunk id', () => {
      service.getChunk('chunk id with spaces').subscribe();

      const req = httpMock.expectOne(r => r.url.includes('chunk%20id%20with%20spaces'));
      req.flush(makeChunkDetail({ id: 'chunk id with spaces' }));
    });

    it('should handle 404 for missing chunk', () => {
      service.getChunk('nonexistent').subscribe({ error: err => expect(err).toBeTruthy() });

      const req = httpMock.expectOne(r => r.url.includes('/chunk-manager/chunks/nonexistent'));
      req.flush('Not Found', { status: 404, statusText: 'Not Found' });
    });
  });

  // ─────────────────────────────────────────────────────────────────────────
  // listSources()
  // ─────────────────────────────────────────────────────────────────────────

  describe('listSources()', () => {
    it('should GET /chunk-manager/sources and return SourceListResponse', () => {
      const mock = makeSourceListResponse();

      service.listSources().subscribe(res => {
        expect(res.sources.length).toBe(2);
        expect(res.totalSources).toBe(2);
        expect(res.sources[0].filename).toBe('doc1.pdf');
      });

      const req = httpMock.expectOne(r => r.url.endsWith('/chunk-manager/sources'));
      expect(req.request.method).toBe('GET');
      req.flush(mock);
    });

    it('should handle empty sources list', () => {
      service.listSources().subscribe(res => {
        expect(res.sources.length).toBe(0);
      });

      const req = httpMock.expectOne(r => r.url.endsWith('/chunk-manager/sources'));
      req.flush({ sources: [], totalSources: 0 });
    });
  });

  // ─────────────────────────────────────────────────────────────────────────
  // deleteChunk()
  // ─────────────────────────────────────────────────────────────────────────

  describe('deleteChunk()', () => {
    it('should DELETE /chunk-manager/chunks/:id and return OperationResponse', () => {
      const mock = makeOperationResponse();

      service.deleteChunk('chunk-1').subscribe(res => {
        expect(res.success).toBeTrue();
        expect(res.affectedCount).toBe(1);
      });

      const req = httpMock.expectOne(r => r.url.includes('/chunk-manager/chunks/chunk-1'));
      expect(req.request.method).toBe('DELETE');
      req.flush(mock);
    });

    it('should handle delete failure', () => {
      service.deleteChunk('chunk-1').subscribe({ error: err => expect(err).toBeTruthy() });

      const req = httpMock.expectOne(r => r.url.includes('/chunk-manager/chunks/chunk-1'));
      req.flush('Error', { status: 500, statusText: 'Internal Server Error' });
    });
  });

  // ─────────────────────────────────────────────────────────────────────────
  // deleteChunks()
  // ─────────────────────────────────────────────────────────────────────────

  describe('deleteChunks()', () => {
    it('should DELETE /chunk-manager/chunks with body containing ids array', () => {
      const ids = ['chunk-1', 'chunk-2', 'chunk-3'];
      const mock = makeOperationResponse({ affectedCount: 3 });

      service.deleteChunks(ids).subscribe(res => {
        expect(res.success).toBeTrue();
        expect(res.affectedCount).toBe(3);
      });

      const req = httpMock.expectOne(r => r.url.endsWith('/chunk-manager/chunks'));
      expect(req.request.method).toBe('DELETE');
      expect(req.request.body).toEqual(ids);
      req.flush(mock);
    });

    it('should handle empty ids array', () => {
      service.deleteChunks([]).subscribe(res => {
        expect(res.success).toBeTrue();
      });

      const req = httpMock.expectOne(r => r.url.endsWith('/chunk-manager/chunks'));
      expect(req.request.body).toEqual([]);
      req.flush(makeOperationResponse({ affectedCount: 0 }));
    });
  });

  // ─────────────────────────────────────────────────────────────────────────
  // deleteBySource()
  // ─────────────────────────────────────────────────────────────────────────

  describe('deleteBySource()', () => {
    it('should DELETE /chunk-manager/chunks/by-source with sourceId in body', () => {
      const mock = makeOperationResponse({ message: 'Deleted 5 chunks from source' });

      service.deleteBySource('src-1').subscribe(res => {
        expect(res.success).toBeTrue();
      });

      const req = httpMock.expectOne(r => r.url.endsWith('/chunk-manager/chunks/by-source'));
      expect(req.request.method).toBe('DELETE');
      expect(req.request.body).toEqual({ sourceId: 'src-1' });
      req.flush(mock);
    });
  });

  // ─────────────────────────────────────────────────────────────────────────
  // generateClearToken()
  // ─────────────────────────────────────────────────────────────────────────

  describe('generateClearToken()', () => {
    it('should POST /chunk-manager/clear-all/token and return ClearTokenResponse', () => {
      const mock: ClearTokenResponse = {
        token: 'tok-abc-123',
        expiresIn: 60,
        message: 'Token generated'
      };

      service.generateClearToken().subscribe(res => {
        expect(res.token).toBe('tok-abc-123');
        expect(res.expiresIn).toBe(60);
      });

      const req = httpMock.expectOne(r => r.url.endsWith('/chunk-manager/clear-all/token'));
      expect(req.request.method).toBe('POST');
      req.flush(mock);
    });
  });

  // ─────────────────────────────────────────────────────────────────────────
  // clearAll()
  // ─────────────────────────────────────────────────────────────────────────

  describe('clearAll()', () => {
    it('should DELETE /chunk-manager/clear-all with confirmation token in body', () => {
      const mock = makeOperationResponse({ message: 'All chunks cleared' });

      service.clearAll('tok-abc-123').subscribe(res => {
        expect(res.success).toBeTrue();
        expect(res.message).toBe('All chunks cleared');
      });

      const req = httpMock.expectOne(r => r.url.endsWith('/chunk-manager/clear-all'));
      expect(req.request.method).toBe('DELETE');
      expect(req.request.body).toEqual({ confirmationToken: 'tok-abc-123' });
      req.flush(mock);
    });

    it('should handle invalid token error', () => {
      service.clearAll('bad-token').subscribe({ error: err => expect(err).toBeTruthy() });

      const req = httpMock.expectOne(r => r.url.endsWith('/chunk-manager/clear-all'));
      req.flush({ error: 'Invalid token' }, { status: 403, statusText: 'Forbidden' });
    });
  });

  // ─────────────────────────────────────────────────────────────────────────
  // analyzeDuplicates()
  // ─────────────────────────────────────────────────────────────────────────

  describe('analyzeDuplicates()', () => {
    it('should GET /chunk-manager/duplicates with default strategy', () => {
      const mock: DuplicateAnalysisResponse = {
        strategy: 'content_hash',
        totalDuplicateGroups: 2,
        totalDuplicateChunks: 5,
        chunksToRemove: 3,
        groups: []
      };

      service.analyzeDuplicates().subscribe(res => {
        expect(res.totalDuplicateGroups).toBe(2);
        expect(res.chunksToRemove).toBe(3);
      });

      const req = httpMock.expectOne(r =>
        r.url.endsWith('/chunk-manager/duplicates') &&
        r.params.get('strategy') === 'content_hash'
      );
      expect(req.request.method).toBe('GET');
      req.flush(mock);
    });

    it('should use provided strategy param', () => {
      service.analyzeDuplicates('source_and_index').subscribe();

      const req = httpMock.expectOne(r => r.url.endsWith('/chunk-manager/duplicates'));
      expect(req.request.params.get('strategy')).toBe('source_and_index');
      req.flush({ strategy: 'source_and_index', totalDuplicateGroups: 0, totalDuplicateChunks: 0, chunksToRemove: 0, groups: [] });
    });
  });

  // ─────────────────────────────────────────────────────────────────────────
  // deduplicate()
  // ─────────────────────────────────────────────────────────────────────────

  describe('deduplicate()', () => {
    it('should POST /chunk-manager/deduplicate with DeduplicationRequest and return result', () => {
      const request: DeduplicationRequest = {
        strategy: 'content_hash',
        keepPolicy: 'first',
        dryRun: true
      };
      const mock: DeduplicationResult = {
        strategy: 'content_hash',
        duplicateGroupsFound: 2,
        chunksRemoved: 0,
        chunksKept: 5,
        success: true,
        message: 'Dry run: 2 groups found'
      };

      service.deduplicate(request).subscribe(res => {
        expect(res.success).toBeTrue();
        expect(res.duplicateGroupsFound).toBe(2);
        expect(res.chunksRemoved).toBe(0);
      });

      const req = httpMock.expectOne(r => r.url.endsWith('/chunk-manager/deduplicate'));
      expect(req.request.method).toBe('POST');
      expect(req.request.body).toEqual(request);
      req.flush(mock);
    });

    it('should handle actual deduplication (non-dry-run)', () => {
      const request: DeduplicationRequest = {
        strategy: 'source_and_index',
        keepPolicy: 'latest',
        dryRun: false
      };

      service.deduplicate(request).subscribe(res => {
        expect(res.chunksRemoved).toBe(3);
      });

      const req = httpMock.expectOne(r => r.url.endsWith('/chunk-manager/deduplicate'));
      expect(req.request.body.dryRun).toBeFalse();
      req.flush({
        strategy: 'source_and_index',
        duplicateGroupsFound: 2,
        chunksRemoved: 3,
        chunksKept: 4,
        success: true,
        message: '3 chunks removed'
      });
    });
  });

  // ─────────────────────────────────────────────────────────────────────────
  // exportChunks()
  // ─────────────────────────────────────────────────────────────────────────

  describe('exportChunks()', () => {
    it('should POST /chunk-manager/export with ExportRequest', () => {
      const request: ExportRequest = {
        chunkIds: ['chunk-1', 'chunk-2'],
        includeMetadata: true,
        format: 'markdown'
      };
      const mock: ExportResponse = {
        format: 'markdown',
        content: '# Chunk 1\nHello world',
        chunkCount: 2,
        filename: 'export.md'
      };

      service.exportChunks(request).subscribe(res => {
        expect(res.chunkCount).toBe(2);
        expect(res.format).toBe('markdown');
      });

      const req = httpMock.expectOne(r => r.url.endsWith('/chunk-manager/export'));
      expect(req.request.method).toBe('POST');
      expect(req.request.body).toEqual(request);
      req.flush(mock);
    });
  });

  // ─────────────────────────────────────────────────────────────────────────
  // downloadExport()
  // ─────────────────────────────────────────────────────────────────────────

  describe('downloadExport()', () => {
    it('should POST /chunk-manager/export/download with blob response type', () => {
      const request: ExportRequest = {
        includeMetadata: true,
        format: 'markdown'
      };
      const blobContent = '# Export\nContent';

      service.downloadExport(request).subscribe(blob => {
        expect(blob instanceof Blob).toBeTrue();
      });

      const req = httpMock.expectOne(r => r.url.endsWith('/chunk-manager/export/download'));
      expect(req.request.method).toBe('POST');
      expect(req.request.responseType).toBe('blob');
      req.flush(new Blob([blobContent], { type: 'text/markdown' }));
    });

    it('should include chunkIds and sourceId when provided', () => {
      const request: ExportRequest = {
        chunkIds: ['c1', 'c2'],
        sourceId: 'src-1',
        includeMetadata: false,
        format: 'markdown'
      };

      service.downloadExport(request).subscribe();

      const req = httpMock.expectOne(r => r.url.endsWith('/chunk-manager/export/download'));
      expect(req.request.body.chunkIds).toEqual(['c1', 'c2']);
      expect(req.request.body.sourceId).toBe('src-1');
      req.flush(new Blob(['content']));
    });
  });

  // ─────────────────────────────────────────────────────────────────────────
  // getChunkForEdit()
  // ─────────────────────────────────────────────────────────────────────────

  describe('getChunkForEdit()', () => {
    it('should GET /chunk-manager/chunks/:id/edit and return ChunkEditDetail', () => {
      const mock = makeChunkEditDetail();

      service.getChunkForEdit('chunk-1').subscribe(res => {
        expect(res.id).toBe('chunk-1');
        expect(res.semanticType).toBe('TEXT');
      });

      const req = httpMock.expectOne(r => r.url.includes('/chunk-manager/chunks/chunk-1/edit'));
      expect(req.request.method).toBe('GET');
      req.flush(mock);
    });
  });

  // ─────────────────────────────────────────────────────────────────────────
  // updateChunk()
  // ─────────────────────────────────────────────────────────────────────────

  describe('updateChunk()', () => {
    it('should PUT /chunk-manager/chunks/:id with ChunkUpdateRequest', () => {
      const updateRequest: ChunkUpdateRequest = {
        content: 'Updated content',
        semanticType: 'DEFINITION',
        sourceTitle: 'New Title'
      };
      const mock: ChunkUpdateResponse = {
        success: true,
        message: 'Chunk updated',
        updatedChunk: makeChunkEditDetail({ content: 'Updated content', semanticType: 'DEFINITION' })
      };

      service.updateChunk('chunk-1', updateRequest).subscribe(res => {
        expect(res.success).toBeTrue();
        expect(res.updatedChunk?.content).toBe('Updated content');
      });

      const req = httpMock.expectOne(r => r.url.includes('/chunk-manager/chunks/chunk-1'));
      expect(req.request.method).toBe('PUT');
      expect(req.request.body).toEqual(updateRequest);
      req.flush(mock);
    });

    it('should URL-encode special characters in chunk id for update', () => {
      service.updateChunk('chunk/with/slashes', { content: 'data' }).subscribe();

      const req = httpMock.expectOne(r => r.url.includes('chunk%2Fwith%2Fslashes'));
      expect(req.request.method).toBe('PUT');
      req.flush({ success: true, message: 'OK' });
    });
  });

  // ─────────────────────────────────────────────────────────────────────────
  // getSemanticTypes()
  // ─────────────────────────────────────────────────────────────────────────

  describe('getSemanticTypes()', () => {
    it('should GET /chunk-manager/semantic-types and return types array', () => {
      const mock = { types: ['TEXT', 'DEFINITION', 'CODE'] };

      service.getSemanticTypes().subscribe(res => {
        expect(res.types.length).toBe(3);
        expect(res.types).toContain('TEXT');
      });

      const req = httpMock.expectOne(r => r.url.endsWith('/chunk-manager/semantic-types'));
      expect(req.request.method).toBe('GET');
      req.flush(mock);
    });
  });

  // ─────────────────────────────────────────────────────────────────────────
  // getEntityTypes()
  // ─────────────────────────────────────────────────────────────────────────

  describe('getEntityTypes()', () => {
    it('should GET /chunk-manager/entity-types and return types array', () => {
      const mock = { types: ['PERSON', 'ORGANIZATION', 'LOCATION'] };

      service.getEntityTypes().subscribe(res => {
        expect(res.types.length).toBe(3);
        expect(res.types).toContain('PERSON');
      });

      const req = httpMock.expectOne(r => r.url.endsWith('/chunk-manager/entity-types'));
      expect(req.request.method).toBe('GET');
      req.flush(mock);
    });

    it('should handle server error on entity types', () => {
      service.getEntityTypes().subscribe({ error: err => expect(err).toBeTruthy() });

      const req = httpMock.expectOne(r => r.url.endsWith('/chunk-manager/entity-types'));
      req.flush('Server Error', { status: 500, statusText: 'Internal Server Error' });
    });
  });
});
