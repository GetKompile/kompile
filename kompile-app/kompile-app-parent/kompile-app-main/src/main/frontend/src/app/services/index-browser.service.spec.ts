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

import { IndexBrowserService } from './index-browser.service';
import {
  IndexedDocInfo,
  SimpleMessageResponse,
  SearchResponse,
  IndexBrowserStatus,
  JobStatus,
  JobState
} from '../models/api-models';

function apiRoot(): string {
  const { protocol, hostname, port } = window.location;
  return `${protocol}//${hostname}${port ? ':' + port : ''}/api`;
}

describe('IndexBrowserService', () => {
  let service: IndexBrowserService;
  let httpMock: HttpTestingController;
  let base: string;

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [HttpClientTestingModule],
      providers: [IndexBrowserService]
    });

    service = TestBed.inject(IndexBrowserService);
    httpMock = TestBed.inject(HttpTestingController);
    base = apiRoot();
  });

  afterEach(() => {
    httpMock.verify();
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // getIndexBrowserStatus()
  // ─────────────────────────────────────────────────────────────────────────────

  describe('getIndexBrowserStatus()', () => {
    it('should GET /index-browser/status and return status object', () => {
      const mockStatus: IndexBrowserStatus = {
        indexAvailable: true,
        isNoOpIndexer: false,
        isNoOpRetriever: false,
        approximateDocumentCount: 42,
        approximateVectorCount: 38
      } as IndexBrowserStatus;

      service.getIndexBrowserStatus().subscribe(status => {
        expect(status.indexAvailable).toBeTrue();
        expect(status.approximateDocumentCount).toBe(42);
      });

      const req = httpMock.expectOne(r => r.url.includes('/index-browser/status'));
      expect(req.request.method).toBe('GET');
      req.flush(mockStatus);
    });

    it('should propagate errors from getIndexBrowserStatus', () => {
      service.getIndexBrowserStatus().subscribe({
        error: err => expect(err).toBeTruthy()
      });
      const req = httpMock.expectOne(r => r.url.includes('/index-browser/status'));
      req.flush('Server Error', { status: 500, statusText: 'Server Error' });
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // getAllIndexedDocs()
  // ─────────────────────────────────────────────────────────────────────────────

  describe('getAllIndexedDocs()', () => {
    it('should GET /index-browser/documents with offset and limit params', () => {
      const mockDocs: IndexedDocInfo[] = [
        { id: 'doc-1', preview: 'First doc preview', content: 'First doc', metadata: {} } as IndexedDocInfo,
        { id: 'doc-2', preview: 'Second doc preview', content: 'Second doc', metadata: {} } as IndexedDocInfo
      ];

      service.getAllIndexedDocs(0, 10).subscribe(docs => {
        expect(docs.length).toBe(2);
        expect(docs[0].id).toBe('doc-1');
      });

      const req = httpMock.expectOne(r =>
        r.url.includes('/index-browser/documents') &&
        r.params.get('offset') === '0' &&
        r.params.get('limit') === '10'
      );
      expect(req.request.method).toBe('GET');
      req.flush(mockDocs);
    });

    it('should use default offset=0 limit=10 when not specified', () => {
      service.getAllIndexedDocs().subscribe();
      const req = httpMock.expectOne(r => r.url.includes('/index-browser/documents'));
      expect(req.request.params.get('offset')).toBe('0');
      expect(req.request.params.get('limit')).toBe('10');
      req.flush([]);
    });

    it('should handle server error on getAllIndexedDocs', () => {
      service.getAllIndexedDocs().subscribe({
        error: err => expect(err).toBeTruthy()
      });
      const req = httpMock.expectOne(r => r.url.includes('/index-browser/documents'));
      req.flush('Not Found', { status: 404, statusText: 'Not Found' });
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // getIndexedDoc()
  // ─────────────────────────────────────────────────────────────────────────────

  describe('getIndexedDoc()', () => {
    it('should GET /index-browser/documents/:id and return doc', () => {
      const mockDoc: IndexedDocInfo = {
        id: 'doc-abc',
        preview: 'Preview text',
        content: 'Full document content',
        metadata: { source: 'test.pdf' }
      } as IndexedDocInfo;

      service.getIndexedDoc('doc-abc').subscribe(doc => {
        expect(doc.id).toBe('doc-abc');
        expect(doc.content).toBe('Full document content');
      });

      const req = httpMock.expectOne(r => r.url.includes('/index-browser/documents/doc-abc'));
      expect(req.request.method).toBe('GET');
      req.flush(mockDoc);
    });

    it('should URL-encode the docId', () => {
      service.getIndexedDoc('doc with spaces').subscribe();
      const req = httpMock.expectOne(r => r.url.includes('doc%20with%20spaces'));
      req.flush({ id: 'doc with spaces', preview: '', content: '', metadata: {} });
    });

    it('should handle 404 when document not found', () => {
      service.getIndexedDoc('missing-doc').subscribe({
        error: err => expect(err).toBeTruthy()
      });
      const req = httpMock.expectOne(r => r.url.includes('/index-browser/documents/'));
      req.flush('Not Found', { status: 404, statusText: 'Not Found' });
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // updateIndexedDoc()
  // ─────────────────────────────────────────────────────────────────────────────

  describe('updateIndexedDoc()', () => {
    it('should PUT /index-browser/documents/:id with content body', () => {
      const mockResponse: SimpleMessageResponse = { message: 'Document updated successfully' };

      service.updateIndexedDoc('doc-1', 'New content').subscribe(response => {
        expect(response.message).toBe('Document updated successfully');
      });

      const req = httpMock.expectOne(r =>
        r.url.includes('/index-browser/documents/doc-1') && r.method === 'PUT'
      );
      expect(req.request.body).toEqual({ content: 'New content' });
      req.flush(mockResponse);
    });

    it('should handle update error', () => {
      service.updateIndexedDoc('doc-1', 'content').subscribe({
        error: err => expect(err).toBeTruthy()
      });
      const req = httpMock.expectOne(r => r.method === 'PUT' && r.url.includes('/index-browser/documents/'));
      req.flush('Server Error', { status: 500, statusText: 'Server Error' });
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // searchIndexedDocs()
  // ─────────────────────────────────────────────────────────────────────────────

  describe('searchIndexedDocs()', () => {
    it('should POST /index-browser/search with query and maxResults', () => {
      const mockResponse: SearchResponse = {
        results: [
          { id: 'r1', preview: 'Result 1', score: 0.95, originalDocument: 'doc.pdf', metadata: {}, content: 'content 1' }
        ],
        totalResults: 1,
        query: 'test query'
      } as SearchResponse;

      service.searchIndexedDocs('test query', 5).subscribe(response => {
        expect(response.totalResults).toBe(1);
        expect(response.results[0].id).toBe('r1');
      });

      const req = httpMock.expectOne(r => r.url.includes('/index-browser/search'));
      expect(req.request.method).toBe('POST');
      expect(req.request.body.query).toBe('test query');
      expect(req.request.body.maxResults).toBe(5);
      req.flush(mockResponse);
    });

    it('should use default maxResults=10 when not specified', () => {
      service.searchIndexedDocs('query').subscribe();
      const req = httpMock.expectOne(r => r.url.includes('/index-browser/search'));
      expect(req.request.body.maxResults).toBe(10);
      req.flush({ results: [], totalResults: 0, query: 'query' });
    });

    it('should handle search error', () => {
      service.searchIndexedDocs('bad query').subscribe({
        error: err => expect(err).toBeTruthy()
      });
      const req = httpMock.expectOne(r => r.url.includes('/index-browser/search'));
      req.flush('Server Error', { status: 500, statusText: 'Server Error' });
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // getVectorStoreDocuments()
  // ─────────────────────────────────────────────────────────────────────────────

  describe('getVectorStoreDocuments()', () => {
    it('should GET /index-browser/vector-store/documents with params', () => {
      const mockDocs = [
        { id: 'v1', preview: 'Vector doc 1', content: 'content', metadata: {} }
      ];

      service.getVectorStoreDocuments(0, 10).subscribe(docs => {
        expect(docs.length).toBe(1);
        expect(docs[0].id).toBe('v1');
      });

      const req = httpMock.expectOne(r =>
        r.url.includes('/index-browser/vector-store/documents') &&
        r.params.get('offset') === '0' &&
        r.params.get('limit') === '10'
      );
      expect(req.request.method).toBe('GET');
      req.flush(mockDocs);
    });

    it('should handle error from vector store documents endpoint', () => {
      service.getVectorStoreDocuments().subscribe({
        error: err => expect(err).toBeTruthy()
      });
      const req = httpMock.expectOne(r => r.url.includes('/index-browser/vector-store/documents'));
      req.flush('Server Error', { status: 500, statusText: 'Server Error' });
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // searchVectorStore()
  // ─────────────────────────────────────────────────────────────────────────────

  describe('searchVectorStore()', () => {
    it('should POST /index-browser/vector-store/search with query, maxResults, threshold', () => {
      const mockResponse: SearchResponse = {
        results: [{ id: 'v1', preview: 'result', score: 0.85, originalDocument: null!, metadata: {}, content: '' }],
        totalResults: 1,
        maxResults: 5,
        query: 'vector query'
      };

      service.searchVectorStore('vector query', 5, 0.5).subscribe(response => {
        expect(response.totalResults).toBe(1);
      });

      const req = httpMock.expectOne(r => r.url.includes('/index-browser/vector-store/search'));
      expect(req.request.method).toBe('POST');
      expect(req.request.body.query).toBe('vector query');
      expect(req.request.body.maxResults).toBe(5);
      expect(req.request.body.similarityThreshold).toBe(0.5);
      req.flush(mockResponse);
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // startVectorPopulation()
  // ─────────────────────────────────────────────────────────────────────────────

  describe('startVectorPopulation()', () => {
    it('should POST /vector-population/start', () => {
      const mockResponse = { message: 'Vector population started', taskId: 'task-123' };

      service.startVectorPopulation().subscribe(response => {
        expect(response.taskId).toBe('task-123');
      });

      const req = httpMock.expectOne(r => r.url.includes('/vector-population/start'));
      expect(req.request.method).toBe('POST');
      req.flush(mockResponse);
    });

    it('should handle error when vector population fails to start', () => {
      service.startVectorPopulation().subscribe({
        error: err => expect(err).toBeTruthy()
      });
      const req = httpMock.expectOne(r => r.url.includes('/vector-population/start'));
      req.flush('Conflict', { status: 409, statusText: 'Conflict' });
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // cancelVectorPopulation()
  // ─────────────────────────────────────────────────────────────────────────────

  describe('cancelVectorPopulation()', () => {
    it('should POST /vector-population/cancel/:taskId', () => {
      const taskId = 'task-to-cancel';
      const mockResponse = { message: 'Cancellation requested' };

      service.cancelVectorPopulation(taskId).subscribe(response => {
        expect(response.message).toBe('Cancellation requested');
      });

      const req = httpMock.expectOne(r => r.url.includes('/vector-population/cancel/') && r.url.includes(taskId));
      expect(req.request.method).toBe('POST');
      req.flush(mockResponse);
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // getVectorIndexJobStatus()
  // ─────────────────────────────────────────────────────────────────────────────

  describe('getVectorIndexJobStatus()', () => {
    it('should GET /indexer/vector-index/status and return JobStatus', () => {
      const mockStatus: JobStatus = {
        state: JobState.RUNNING,
        message: 'Running',
        percentComplete: 50,
        documentsProcessed: 100
      };

      service.getVectorIndexJobStatus().subscribe(status => {
        expect(status.state).toBe(JobState.RUNNING);
        expect(status.percentComplete).toBe(50);
      });

      const req = httpMock.expectOne(r => r.url.includes('/indexer/vector-index/status'));
      expect(req.request.method).toBe('GET');
      req.flush(mockStatus);
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // getActiveTrackedVectorPopulationTasks()
  // ─────────────────────────────────────────────────────────────────────────────

  describe('getActiveTrackedVectorPopulationTasks()', () => {
    it('should GET /vector-population/tracker/tasks/active', () => {
      const mockResponse = {
        available: true,
        activeCount: 1,
        tasks: [{ taskId: 'task-1', phase: 'RUNNING', progressPercent: 30 }]
      };

      service.getActiveTrackedVectorPopulationTasks().subscribe(response => {
        expect(response.available).toBeTrue();
        expect(response.activeCount).toBe(1);
        expect(response.tasks[0].taskId).toBe('task-1');
      });

      const req = httpMock.expectOne(r => r.url.includes('/vector-population/tracker/tasks/active'));
      expect(req.request.method).toBe('GET');
      req.flush(mockResponse);
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // getTrackedVectorPopulationTaskStatus()
  // ─────────────────────────────────────────────────────────────────────────────

  describe('getTrackedVectorPopulationTaskStatus()', () => {
    it('should GET /vector-population/tracker/tasks/:taskId', () => {
      const taskId = 'task-xyz';
      const mockResponse = {
        available: true,
        found: true,
        task: { taskId, phase: 'EMBEDDING', progressPercent: 75 }
      };

      service.getTrackedVectorPopulationTaskStatus(taskId).subscribe(response => {
        expect(response.found).toBeTrue();
        expect(response.task?.taskId).toBe(taskId);
      });

      const req = httpMock.expectOne(r =>
        r.url.includes('/vector-population/tracker/tasks/') && r.url.includes(taskId)
      );
      expect(req.request.method).toBe('GET');
      req.flush(mockResponse);
    });

    it('should return found=false when task does not exist', () => {
      service.getTrackedVectorPopulationTaskStatus('nonexistent').subscribe(response => {
        expect(response.found).toBeFalse();
      });

      const req = httpMock.expectOne(r => r.url.includes('/vector-population/tracker/tasks/'));
      req.flush({ available: true, found: false });
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // getVectorPopulationTaskEnvironment()
  // ─────────────────────────────────────────────────────────────────────────────

  describe('getVectorPopulationTaskEnvironment()', () => {
    it('should GET /vector-population/tracker/tasks/:taskId/environment', () => {
      const taskId = 'env-task-1';
      const mockEnv = {
        available: true,
        found: true,
        taskId,
        environmentCaptured: true
      };

      service.getVectorPopulationTaskEnvironment(taskId).subscribe(env => {
        expect(env.environmentCaptured).toBeTrue();
        expect(env.taskId).toBe(taskId);
      });

      const req = httpMock.expectOne(r => r.url.includes(`/environment`));
      expect(req.request.method).toBe('GET');
      req.flush(mockEnv);
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // getVectorPopulationSubprocessList()
  // ─────────────────────────────────────────────────────────────────────────────

  describe('getVectorPopulationSubprocessList()', () => {
    it('should GET /vector-population/subprocess/list', () => {
      const mockResponse = { available: true, subprocessCount: 0, subprocesses: [] };

      service.getVectorPopulationSubprocessList().subscribe(response => {
        expect(response.available).toBeTrue();
        expect(response.subprocessCount).toBe(0);
      });

      const req = httpMock.expectOne(r => r.url.includes('/vector-population/subprocess/list'));
      expect(req.request.method).toBe('GET');
      req.flush(mockResponse);
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // getActiveTasksFullState()
  // ─────────────────────────────────────────────────────────────────────────────

  describe('getActiveTasksFullState()', () => {
    it('should GET /vector-population/tracker/active-tasks-full-state', () => {
      const mockResponse = { available: true, activeCount: 0, tasks: [] };

      service.getActiveTasksFullState().subscribe(response => {
        expect(response.available).toBeTrue();
        expect(response.activeCount).toBe(0);
      });

      const req = httpMock.expectOne(r => r.url.includes('/vector-population/tracker/active-tasks-full-state'));
      expect(req.request.method).toBe('GET');
      req.flush(mockResponse);
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // cancelVectorIndexCreation() — legacy alias
  // ─────────────────────────────────────────────────────────────────────────────

  describe('cancelVectorIndexCreation()', () => {
    it('should POST /indexer/vector-index/cancel', () => {
      service.cancelVectorIndexCreation().subscribe(response => {
        expect(response.message).toBeTruthy();
      });

      const req = httpMock.expectOne(r => r.url.includes('/indexer/vector-index/cancel'));
      expect(req.request.method).toBe('POST');
      req.flush({ message: 'Cancelled' });
    });
  });
});
