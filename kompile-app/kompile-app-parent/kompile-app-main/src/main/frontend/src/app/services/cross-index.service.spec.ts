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

import { CrossIndexService } from './cross-index.service';
import {
  CrossIndexSummary,
  IndexedDocumentResponse,
  IndexedPassageResponse,
  SyncJobResponse,
  SyncJobStatusResponse,
  AutoSyncConfigResponse
} from '../models/api-models';
import { environment } from '../../environments/environment';

describe('CrossIndexService', () => {
  let service: CrossIndexService;
  let httpMock: HttpTestingController;
  const baseUrl = `${environment.apiUrl}/cross-index`;

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [HttpClientTestingModule],
      providers: [CrossIndexService]
    });

    service = TestBed.inject(CrossIndexService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    httpMock.verify();
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // getCrossIndexSummary()
  // ─────────────────────────────────────────────────────────────────────────────

  describe('getCrossIndexSummary()', () => {
    it('should GET /cross-index/status and return summary', () => {
      const mockSummary: CrossIndexSummary = {
        totalDocuments: 100,
        fullyIndexedDocuments: 80,
        partiallyIndexedDocuments: 10,
        notIndexedDocuments: 5,
        outOfSyncDocuments: 5
      } as CrossIndexSummary;

      service.getCrossIndexSummary().subscribe(summary => {
        expect(summary.totalDocuments).toBe(100);
        expect(summary.fullyIndexedDocuments).toBe(80);
      });

      const req = httpMock.expectOne(`${baseUrl}/status`);
      expect(req.request.method).toBe('GET');
      req.flush(mockSummary);
    });

    it('should handle error from getCrossIndexSummary', () => {
      service.getCrossIndexSummary().subscribe({
        error: err => expect(err).toBeTruthy()
      });
      const req = httpMock.expectOne(`${baseUrl}/status`);
      req.flush('Server Error', { status: 500, statusText: 'Server Error' });
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // getCrossIndexSummaryForFactSheet()
  // ─────────────────────────────────────────────────────────────────────────────

  describe('getCrossIndexSummaryForFactSheet()', () => {
    it('should GET /cross-index/status/:factSheetId', () => {
      const mockSummary: CrossIndexSummary = {
        totalDocuments: 50,
        fullyIndexedDocuments: 45,
        partiallyIndexedDocuments: 3,
        notIndexedDocuments: 2,
        outOfSyncDocuments: 0
      } as CrossIndexSummary;

      service.getCrossIndexSummaryForFactSheet(3).subscribe(summary => {
        expect(summary.totalDocuments).toBe(50);
      });

      const req = httpMock.expectOne(`${baseUrl}/status/3`);
      expect(req.request.method).toBe('GET');
      req.flush(mockSummary);
    });

    it('should handle 404 for unknown fact sheet', () => {
      service.getCrossIndexSummaryForFactSheet(999).subscribe({
        error: err => expect(err).toBeTruthy()
      });
      const req = httpMock.expectOne(`${baseUrl}/status/999`);
      req.flush('Not Found', { status: 404, statusText: 'Not Found' });
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // getStatistics()
  // ─────────────────────────────────────────────────────────────────────────────

  describe('getStatistics()', () => {
    it('should GET /cross-index/statistics/:factSheetId', () => {
      const mockStats = { factSheetId: 1, totalPassages: 500, vectorPassages: 480 };

      service.getStatistics(1).subscribe(stats => {
        expect(stats).toBeTruthy();
      });

      const req = httpMock.expectOne(`${baseUrl}/statistics/1`);
      expect(req.request.method).toBe('GET');
      req.flush(mockStats);
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // getDocuments()
  // ─────────────────────────────────────────────────────────────────────────────

  describe('getDocuments()', () => {
    it('should GET /cross-index/documents with required params', () => {
      const mockResponse: IndexedDocumentResponse = {
        documents: [
          {
            id: 1,
            sourceId: 'src-1',
            fileName: 'doc.pdf',
            overallStatus: 'FULLY_INDEXED' as any,
            keywordIndexStatus: 'INDEXED' as any,
            vectorStoreStatus: 'INDEXED' as any,
            graphStatus: 'NOT_INDEXED' as any,
            keywordPassageCount: 10,
            vectorPassageCount: 10,
            graphNodeCount: 0,
            updatedAt: '2025-01-01T00:00:00Z'
          }
        ],
        total: 1
      } as IndexedDocumentResponse;

      service.getDocuments(1, 0, 20).subscribe(response => {
        expect(response.total).toBe(1);
        expect(response.documents.length).toBe(1);
      });

      const req = httpMock.expectOne(r =>
        r.url.includes('/cross-index/documents') &&
        r.params.get('factSheetId') === '1' &&
        r.params.get('offset') === '0' &&
        r.params.get('limit') === '20'
      );
      expect(req.request.method).toBe('GET');
      req.flush(mockResponse);
    });

    it('should include optional status filter param when provided', () => {
      service.getDocuments(1, 0, 20, 'FULLY_INDEXED' as any).subscribe();

      const req = httpMock.expectOne(r => r.url.includes('/cross-index/documents'));
      expect(req.request.params.get('status')).toBe('FULLY_INDEXED');
      req.flush({ documents: [], total: 0 });
    });

    it('should include search param when provided', () => {
      service.getDocuments(1, 0, 20, undefined, 'myfile').subscribe();

      const req = httpMock.expectOne(r => r.url.includes('/cross-index/documents'));
      expect(req.request.params.get('search')).toBe('myfile');
      req.flush({ documents: [], total: 0 });
    });

    it('should not include optional params when absent', () => {
      service.getDocuments(1).subscribe();

      const req = httpMock.expectOne(r => r.url.includes('/cross-index/documents'));
      expect(req.request.params.get('status')).toBeNull();
      expect(req.request.params.get('search')).toBeNull();
      req.flush({ documents: [], total: 0 });
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // getDocumentDetail()
  // ─────────────────────────────────────────────────────────────────────────────

  describe('getDocumentDetail()', () => {
    it('should GET /cross-index/documents/:documentId', () => {
      const mockDetail = { id: 5, sourceId: 'src-5', fileName: 'detail.pdf' };

      service.getDocumentDetail(5).subscribe(detail => {
        expect(detail).toBeTruthy();
      });

      const req = httpMock.expectOne(`${baseUrl}/documents/5`);
      expect(req.request.method).toBe('GET');
      req.flush(mockDetail);
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // getDocumentsNeedingSync()
  // ─────────────────────────────────────────────────────────────────────────────

  describe('getDocumentsNeedingSync()', () => {
    it('should GET /cross-index/documents/needing-sync with factSheetId', () => {
      service.getDocumentsNeedingSync(2).subscribe();

      const req = httpMock.expectOne(r =>
        r.url.includes('/cross-index/documents/needing-sync') &&
        r.params.get('factSheetId') === '2'
      );
      expect(req.request.method).toBe('GET');
      req.flush({ documents: [], total: 0 });
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // getPassages()
  // ─────────────────────────────────────────────────────────────────────────────

  describe('getPassages()', () => {
    it('should GET /cross-index/passages with documentId, offset, limit', () => {
      const mockResponse: IndexedPassageResponse = {
        passages: [],
        total: 0,
        offset: 0,
        limit: 50
      } as IndexedPassageResponse;

      service.getPassages(10, 0, 50).subscribe(response => {
        expect(response.total).toBe(0);
      });

      const req = httpMock.expectOne(r =>
        r.url.includes('/cross-index/passages') &&
        r.params.get('documentId') === '10' &&
        r.params.get('offset') === '0' &&
        r.params.get('limit') === '50'
      );
      expect(req.request.method).toBe('GET');
      req.flush(mockResponse);
    });

    it('should use default offset=0 limit=50 when not specified', () => {
      service.getPassages(7).subscribe();
      const req = httpMock.expectOne(r => r.url.includes('/cross-index/passages'));
      expect(req.request.params.get('offset')).toBe('0');
      expect(req.request.params.get('limit')).toBe('50');
      req.flush({ passages: [], total: 0 });
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // checkPassageStatus()
  // ─────────────────────────────────────────────────────────────────────────────

  describe('checkPassageStatus()', () => {
    it('should POST /cross-index/passages/check-status with chunkIds', () => {
      const chunkIds = ['chunk-1', 'chunk-2', 'chunk-3'];
      const mockResponse = { statuses: {} };

      service.checkPassageStatus(chunkIds).subscribe();

      const req = httpMock.expectOne(`${baseUrl}/passages/check-status`);
      expect(req.request.method).toBe('POST');
      expect(req.request.body.chunkIds).toEqual(chunkIds);
      req.flush(mockResponse);
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // syncToVectorStore()
  // ─────────────────────────────────────────────────────────────────────────────

  describe('syncToVectorStore()', () => {
    it('should POST /cross-index/sync/vector-store with factSheetId param', () => {
      const mockResponse: SyncJobResponse = {
        jobId: 'job-vs-1',
        status: 'STARTED',
        message: 'Sync started'
      } as SyncJobResponse;

      service.syncToVectorStore(1).subscribe(response => {
        expect(response.jobId).toBe('job-vs-1');
        expect(response.status).toBe('STARTED');
      });

      const req = httpMock.expectOne(r =>
        r.url.includes('/cross-index/sync/vector-store') &&
        r.params.get('factSheetId') === '1'
      );
      expect(req.request.method).toBe('POST');
      req.flush(mockResponse);
    });

    it('should handle error when sync to vector store fails', () => {
      service.syncToVectorStore(1).subscribe({
        error: err => expect(err).toBeTruthy()
      });
      const req = httpMock.expectOne(r => r.url.includes('/cross-index/sync/vector-store'));
      req.flush('Server Error', { status: 500, statusText: 'Server Error' });
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // syncToKnowledgeGraph()
  // ─────────────────────────────────────────────────────────────────────────────

  describe('syncToKnowledgeGraph()', () => {
    it('should POST /cross-index/sync/knowledge-graph with factSheetId param', () => {
      const mockResponse: SyncJobResponse = {
        jobId: 'job-kg-1',
        status: 'STARTED',
        message: 'Graph sync started'
      } as SyncJobResponse;

      service.syncToKnowledgeGraph(2).subscribe(response => {
        expect(response.jobId).toBe('job-kg-1');
      });

      const req = httpMock.expectOne(r =>
        r.url.includes('/cross-index/sync/knowledge-graph') &&
        r.params.get('factSheetId') === '2'
      );
      expect(req.request.method).toBe('POST');
      req.flush(mockResponse);
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // syncAll()
  // ─────────────────────────────────────────────────────────────────────────────

  describe('syncAll()', () => {
    it('should POST /cross-index/sync/all with factSheetId param', () => {
      const mockResponse: SyncJobResponse = {
        jobId: 'job-all-1',
        status: 'STARTED',
        message: 'Full sync started'
      } as SyncJobResponse;

      service.syncAll(1).subscribe(response => {
        expect(response.jobId).toBe('job-all-1');
      });

      const req = httpMock.expectOne(r =>
        r.url.includes('/cross-index/sync/all') &&
        r.params.get('factSheetId') === '1'
      );
      expect(req.request.method).toBe('POST');
      req.flush(mockResponse);
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // startSync()
  // ─────────────────────────────────────────────────────────────────────────────

  describe('startSync()', () => {
    it('should POST /cross-index/sync with custom sync request', () => {
      const request = { factSheetId: 1, targets: ['VECTOR_STORE'] as any } as any;
      const mockResponse: SyncJobResponse = { jobId: 'job-custom-1', status: 'STARTED', message: 'OK' } as SyncJobResponse;

      service.startSync(request).subscribe(response => {
        expect(response.jobId).toBe('job-custom-1');
      });

      const req = httpMock.expectOne(`${baseUrl}/sync`);
      expect(req.request.method).toBe('POST');
      expect(req.request.body).toEqual(request);
      req.flush(mockResponse);
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // syncDocuments()
  // ─────────────────────────────────────────────────────────────────────────────

  describe('syncDocuments()', () => {
    it('should POST /cross-index/sync/documents with documentIds and targets', () => {
      const documentIds = [1, 2, 3];
      const targets: ('VECTOR_STORE' | 'KNOWLEDGE_GRAPH')[] = ['VECTOR_STORE', 'KNOWLEDGE_GRAPH'];
      const mockResponse: SyncJobResponse = { jobId: 'job-docs-1', status: 'STARTED', message: 'OK' } as SyncJobResponse;

      service.syncDocuments(documentIds, targets).subscribe(response => {
        expect(response.jobId).toBe('job-docs-1');
      });

      const req = httpMock.expectOne(`${baseUrl}/sync/documents`);
      expect(req.request.method).toBe('POST');
      expect(req.request.body.documentIds).toEqual(documentIds);
      expect(req.request.body.targets).toEqual(targets);
      req.flush(mockResponse);
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // syncPassages()
  // ─────────────────────────────────────────────────────────────────────────────

  describe('syncPassages()', () => {
    it('should POST /cross-index/sync/passages with chunkIds and targets', () => {
      const chunkIds = ['chunk-a', 'chunk-b'];
      const targets: ('VECTOR_STORE' | 'KNOWLEDGE_GRAPH')[] = ['VECTOR_STORE'];

      service.syncPassages(chunkIds, targets).subscribe();

      const req = httpMock.expectOne(`${baseUrl}/sync/passages`);
      expect(req.request.method).toBe('POST');
      expect(req.request.body.chunkIds).toEqual(chunkIds);
      expect(req.request.body.targets).toEqual(targets);
      req.flush({ jobId: 'j1', status: 'STARTED', message: 'OK' });
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // getSyncJobStatus()
  // ─────────────────────────────────────────────────────────────────────────────

  describe('getSyncJobStatus()', () => {
    it('should GET /cross-index/sync/:jobId and return status', () => {
      const mockStatus: SyncJobStatusResponse = {
        jobId: 'job-1',
        status: 'RUNNING',
        progress: 55,
        message: 'Syncing...'
      } as SyncJobStatusResponse;

      service.getSyncJobStatus('job-1').subscribe(status => {
        expect(status.status).toBe('RUNNING');
        expect(status.progress).toBe(55);
      });

      const req = httpMock.expectOne(`${baseUrl}/sync/job-1`);
      expect(req.request.method).toBe('GET');
      req.flush(mockStatus);
    });

    it('should handle error when job not found', () => {
      service.getSyncJobStatus('nonexistent').subscribe({
        error: err => expect(err).toBeTruthy()
      });
      const req = httpMock.expectOne(`${baseUrl}/sync/nonexistent`);
      req.flush('Not Found', { status: 404, statusText: 'Not Found' });
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // cancelSyncJob()
  // ─────────────────────────────────────────────────────────────────────────────

  describe('cancelSyncJob()', () => {
    it('should DELETE /cross-index/sync/:jobId', () => {
      service.cancelSyncJob('job-cancel-1').subscribe();

      const req = httpMock.expectOne(`${baseUrl}/sync/job-cancel-1`);
      expect(req.request.method).toBe('DELETE');
      req.flush(null);
    });

    it('should handle error when cancelling non-existent job', () => {
      service.cancelSyncJob('bad-job').subscribe({
        error: err => expect(err).toBeTruthy()
      });
      const req = httpMock.expectOne(`${baseUrl}/sync/bad-job`);
      req.flush('Not Found', { status: 404, statusText: 'Not Found' });
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // getAutoSyncConfig()
  // ─────────────────────────────────────────────────────────────────────────────

  describe('getAutoSyncConfig()', () => {
    it('should GET /cross-index/config/auto-sync with factSheetId', () => {
      const mockConfig: AutoSyncConfigResponse = {
        factSheetId: 1,
        enabled: true,
        maxPassagesPerSync: 100,
        syncTimeoutSeconds: 60,
        syncOnSearch: true,
        syncOnIngest: true
      };

      service.getAutoSyncConfig(1).subscribe(config => {
        expect(config.enabled).toBeTrue();
        expect(config.factSheetId).toBe(1);
      });

      const req = httpMock.expectOne(r =>
        r.url.includes('/cross-index/config/auto-sync') &&
        r.params.get('factSheetId') === '1'
      );
      expect(req.request.method).toBe('GET');
      req.flush(mockConfig);
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // updateAutoSyncConfig()
  // ─────────────────────────────────────────────────────────────────────────────

  describe('updateAutoSyncConfig()', () => {
    it('should PUT /cross-index/config/auto-sync with config request body', () => {
      const request = { factSheetId: 1, enabled: false };
      const mockResponse: AutoSyncConfigResponse = {
        factSheetId: 1,
        enabled: false,
        maxPassagesPerSync: 100,
        syncTimeoutSeconds: 60,
        syncOnSearch: false,
        syncOnIngest: false
      };

      service.updateAutoSyncConfig(request).subscribe(response => {
        expect(response.enabled).toBeFalse();
      });

      const req = httpMock.expectOne(`${baseUrl}/config/auto-sync`);
      expect(req.request.method).toBe('PUT');
      expect(req.request.body).toEqual(request);
      req.flush(mockResponse);
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // setAutoSyncEnabled() — delegates to updateAutoSyncConfig
  // ─────────────────────────────────────────────────────────────────────────────

  describe('setAutoSyncEnabled()', () => {
    it('should call PUT /cross-index/config/auto-sync with enabled=true', () => {
      service.setAutoSyncEnabled(1, true).subscribe();

      const req = httpMock.expectOne(`${baseUrl}/config/auto-sync`);
      expect(req.request.method).toBe('PUT');
      expect(req.request.body.factSheetId).toBe(1);
      expect(req.request.body.enabled).toBeTrue();
      req.flush({ factSheetId: 1, enabled: true, maxPassagesPerSync: 100, syncTimeoutSeconds: 60, syncOnSearch: true, syncOnIngest: true });
    });

    it('should call PUT /cross-index/config/auto-sync with enabled=false', () => {
      service.setAutoSyncEnabled(2, false).subscribe();

      const req = httpMock.expectOne(`${baseUrl}/config/auto-sync`);
      expect(req.request.body.enabled).toBeFalse();
      req.flush({ factSheetId: 2, enabled: false, maxPassagesPerSync: 100, syncTimeoutSeconds: 60, syncOnSearch: false, syncOnIngest: false });
    });
  });
});
