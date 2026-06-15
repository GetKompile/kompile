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

import { ComponentFixture, TestBed, fakeAsync, tick } from '@angular/core/testing';
import { NO_ERRORS_SCHEMA, ChangeDetectorRef } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';
import {
  HttpClientTestingModule,
  HttpTestingController
} from '@angular/common/http/testing';
import { Router } from '@angular/router';
import { MatSnackBar } from '@angular/material/snack-bar';
import { MatSelectModule } from '@angular/material/select';
import { MatSlideToggleModule } from '@angular/material/slide-toggle';
import { MatInputModule } from '@angular/material/input';
import { Subject } from 'rxjs';

import { RagTesterComponent } from './rag-tester.component';
import { WebSocketService } from '../../services/websocket.service';
import { ModelStatusUpdate } from '../../models/api-models';

// ═══════════════════════════════════════════════════════════════════════════════
// Test helpers
// ═══════════════════════════════════════════════════════════════════════════════

/** Builds a minimal ModelStatusUpdate for WebSocket tests. */
function makeModelStatusUpdate(overrides: Partial<ModelStatusUpdate> = {}): ModelStatusUpdate {
  return {
    timestamp: Date.now(),
    ready: true,
    embedding: {
      available: true,
      loading: false,
      initialized: true,
      dimensions: 768,
      ...((overrides as any).embedding || {})
    },
    staging: {
      available: false,
      connected: false,
      ...((overrides as any).staging || {})
    },
    ...overrides
  } as ModelStatusUpdate;
}

/** Creates all test doubles and the TestBed configuration. */
function createTestBed() {
  const modelStatusSubject = new Subject<ModelStatusUpdate>();

  const routerSpy = jasmine.createSpyObj('Router', ['navigate']);
  const snackBarSpy = jasmine.createSpyObj('MatSnackBar', ['open']);
  const webSocketServiceSpy = jasmine.createSpyObj('WebSocketService', [
    'connect',
    'subscribeToModelStatus',
    'unsubscribeFromModelStatus'
  ]);
  webSocketServiceSpy.subscribeToModelStatus.and.returnValue(
    modelStatusSubject.asObservable()
  );

  return {
    routerSpy,
    snackBarSpy,
    webSocketServiceSpy,
    modelStatusSubject,
    providers: [
      { provide: Router, useValue: routerSpy },
      { provide: MatSnackBar, useValue: snackBarSpy },
      { provide: WebSocketService, useValue: webSocketServiceSpy }
    ]
  };
}

// ═══════════════════════════════════════════════════════════════════════════════
// Base URL resolution
// Because window.location is available in the test environment the component
// constructor derives backendUrl from window.location.
// In Karma the port is normally something like 9876, so the URL becomes
// `http://localhost:9876/api`.  We verify requests via that same origin.
// ═══════════════════════════════════════════════════════════════════════════════

function expectedApiRoot(): string {
  const { protocol, hostname, port } = window.location;
  return `${protocol}//${hostname}${port ? ':' + port : ''}/api`;
}

// ═══════════════════════════════════════════════════════════════════════════════
// TESTS
// ═══════════════════════════════════════════════════════════════════════════════

describe('RagTesterComponent', () => {
  let component: RagTesterComponent;
  let fixture: ComponentFixture<RagTesterComponent>;
  let httpMock: HttpTestingController;
  let spies: ReturnType<typeof createTestBed>;
  let apiRoot: string;

  // ---------------------------------------------------------------------------
  // Shared setup
  // ---------------------------------------------------------------------------

  beforeEach(async () => {
    spies = createTestBed();

    await TestBed.configureTestingModule({
      imports: [FormsModule, NoopAnimationsModule, HttpClientTestingModule, MatSelectModule, MatSlideToggleModule, MatInputModule],
      declarations: [RagTesterComponent],
      providers: spies.providers,
      schemas: [NO_ERRORS_SCHEMA]
    }).compileComponents();

    httpMock = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(RagTesterComponent);
    component = fixture.componentInstance;
    apiRoot = expectedApiRoot();
  });

  afterEach(() => {
    // Discard any outstanding requests from ngOnInit so later tests start clean.
    // Some tests don't call flushNgOnInit() when they only test component logic.
    httpMock.match(() => true);
  });

  // ---------------------------------------------------------------------------
  // Helper: flush the three ngOnInit HTTP requests
  // ---------------------------------------------------------------------------

  function flushNgOnInit(opts: {
    statusBody?: any;
    embeddingStatusBody?: any;
    rerankersBody?: any;
    graphRagBody?: any;
  } = {}): void {
    const statusReq = httpMock.expectOne(`${apiRoot}/rag/test/status`);
    statusReq.flush(opts.statusBody ?? {
      keywordRetriever: { class: 'AnseriniRetriever', available: true },
      vectorStore: { class: 'HnswVectorStore', available: true },
      embeddingModel: { class: 'BgeEncoder', available: true },
      reranker: { class: 'RM3Reranker', available: true, supportedTypes: ['rm3'] },
      graphRag: { class: 'Neo4jGraphRag', available: false, searchTypes: [] }
    });

    // loadStatus() → success → loadEmbeddingModelStatus()
    const embStatusReq = httpMock.expectOne(`${apiRoot}/models/embedding/status`);
    embStatusReq.flush(opts.embeddingStatusBody ?? {
      initialized: true,
      dimensions: 768,
      loading: false,
      loadingPhase: null,
      initializationError: null
    });

    const rerankersReq = httpMock.expectOne(`${apiRoot}/rag/test/rerankers`);
    rerankersReq.flush(opts.rerankersBody ?? {
      available: true,
      types: [
        {
          id: 'rm3',
          name: 'RM3',
          description: 'Relevance Model 3',
          supported: true,
          parameters: [
            { id: 'fbDocs', label: 'Feedback Docs', type: 'number', default: 10, min: 1, max: 100 },
            { id: 'fbTerms', label: 'Feedback Terms', type: 'number', default: 10, min: 1, max: 100 },
            { id: 'originalQueryWeight', label: 'OQW', type: 'number', default: 0.5, min: 0, max: 1 },
            { id: 'filterTerms', label: 'Filter Terms', type: 'boolean', default: true },
            { id: 'outputQuery', label: 'Output Query', type: 'boolean', default: false }
          ]
        },
        {
          id: 'cross_encoder',
          name: 'Cross Encoder',
          description: 'Neural cross-encoder reranker',
          supported: true,
          parameters: [
            {
              id: 'crossEncoderModel',
              label: 'Model',
              type: 'select',
              default: 'ms-marco-MiniLM-L-6-v2',
              options: ['ms-marco-MiniLM-L-6-v2', 'ms-marco-MiniLM-L-12-v2']
            }
          ]
        }
      ]
    });

    const graphInfoReq = httpMock.expectOne(`${apiRoot}/rag/test/graph/info`);
    graphInfoReq.flush(opts.graphRagBody ?? {
      available: true,
      class: 'Neo4jGraphRag',
      type: 'neo4j',
      description: 'Neo4j graph RAG',
      searchTypes: [
        { id: 'LOCAL', name: 'Local', description: 'Local search' },
        { id: 'GLOBAL', name: 'Global', description: 'Global search' }
      ]
    });
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // 1. COMPONENT INITIALIZATION
  // ─────────────────────────────────────────────────────────────────────────────

  describe('Component initialization', () => {
    it('should create the component', () => {
      expect(component).toBeTruthy();
    });

    it('should initialize with default field values before ngOnInit', () => {
      expect(component.query).toBe('');
      expect(component.maxResults).toBe(5);
      expect(component.threshold).toBe(0.0);
      expect(component.includeKeyword).toBeTrue();
      expect(component.includeSemantic).toBeTrue();
      expect(component.useHybrid).toBeFalse();
      expect(component.isSearching).toBeFalse();
      expect(component.embedText).toBe('');
      expect(component.isEmbedding).toBeFalse();
      expect(component.useReranking).toBeFalse();
      expect(component.selectedRerankerType).toBe('none');
      expect(component.isRerankingSearch).toBeFalse();
      expect(component.graphRagSearchType).toBe('LOCAL');
      expect(component.graphRagMaxResults).toBe(5);
      expect(component.graphRagConversationId).toBe('test');
      expect(component.isGraphRagSearching).toBeFalse();
    });

    it('should call loadStatus, loadRerankers, loadGraphRagInfo, and subscribeToModelStatusUpdates on ngOnInit', () => {
      fixture.detectChanges(); // triggers ngOnInit
      flushNgOnInit();

      expect(spies.webSocketServiceSpy.connect).toHaveBeenCalled();
      expect(spies.webSocketServiceSpy.subscribeToModelStatus).toHaveBeenCalled();
    });

    it('should call unsubscribeFromModelStatus on ngOnDestroy', () => {
      fixture.detectChanges();
      flushNgOnInit();

      component.ngOnDestroy();
      expect(spies.webSocketServiceSpy.unsubscribeFromModelStatus).toHaveBeenCalled();
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // 2. STATUS LOADING — loadStatus()
  // ─────────────────────────────────────────────────────────────────────────────

  describe('loadStatus()', () => {
    it('should set statusLoading to true while the request is in flight', () => {
      fixture.detectChanges();
      expect(component.statusLoading).toBeTrue();
      // Flush all four init requests to clean up
      flushNgOnInit();
    });

    it('should populate status on success and clear statusLoading', () => {
      fixture.detectChanges();
      flushNgOnInit({
        statusBody: {
          keywordRetriever: { class: 'LuceneRetriever', available: true },
          vectorStore: { class: 'HnswStore', available: true },
          embeddingModel: { class: 'BgeEncoder', available: true },
          reranker: { class: 'RM3', available: true, supportedTypes: ['rm3'] },
          graphRag: { class: 'None', available: false, searchTypes: [] }
        }
      });

      expect(component.status).not.toBeNull();
      expect(component.status?.keywordRetriever.available).toBeTrue();
      expect(component.status?.vectorStore.class).toBe('HnswStore');
      expect(component.statusLoading).toBeFalse();
    });

    it('should show snackbar and clear statusLoading on HTTP error', () => {
      fixture.detectChanges();

      const statusReq = httpMock.expectOne(`${apiRoot}/rag/test/status`);
      statusReq.flush({ message: 'Service unavailable' }, { status: 503, statusText: 'Service Unavailable' });

      // loadEmbeddingModelStatus is not called on error, so only rerankers + graph requests remain
      httpMock.expectOne(`${apiRoot}/rag/test/rerankers`).flush({ available: false, types: [] });
      httpMock.expectOne(`${apiRoot}/rag/test/graph/info`).flush({ available: false, class: '', type: '', description: '', searchTypes: [] });

      expect(component.statusLoading).toBeFalse();
      expect(spies.snackBarSpy.open).toHaveBeenCalledWith(
        jasmine.stringMatching(/Failed to load RAG status/),
        'Close',
        jasmine.any(Object)
      );
    });

    it('should update embeddingModelInitialized from embedding status endpoint', () => {
      fixture.detectChanges();
      flushNgOnInit({
        embeddingStatusBody: { initialized: true, dimensions: 384, loading: false, loadingPhase: null, initializationError: null }
      });

      expect(component.embeddingModelInitialized).toBeTrue();
    });

    it('should set embeddingModelError when initializationError is present', () => {
      fixture.detectChanges();
      flushNgOnInit({
        embeddingStatusBody: { initialized: false, dimensions: 0, loading: false, initializationError: 'Model file not found' }
      });

      expect(component.embeddingModelError).toBe('Model file not found');
    });

    it('should set embeddingModelLoading when model is still loading', () => {
      fixture.detectChanges();
      flushNgOnInit({
        embeddingStatusBody: { initialized: false, dimensions: 0, loading: true, loadingPhase: 'Downloading weights' }
      });

      expect(component.embeddingModelLoading).toBeTrue();
      expect(component.embeddingModelLoadingPhase).toBe('Downloading weights');
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // 3. EMBEDDING STATUS HELPERS
  // ─────────────────────────────────────────────────────────────────────────────

  describe('isEmbeddingReady() and getEmbeddingStatusMessage()', () => {
    beforeEach(() => {
      fixture.detectChanges();
      flushNgOnInit();
    });

    it('isEmbeddingReady() returns true when initialized and not loading', () => {
      component.embeddingModelInitialized = true;
      component.embeddingModelLoading = false;
      expect(component.isEmbeddingReady()).toBeTrue();
    });

    it('isEmbeddingReady() returns false when loading even if initialized', () => {
      component.embeddingModelInitialized = true;
      component.embeddingModelLoading = true;
      expect(component.isEmbeddingReady()).toBeFalse();
    });

    it('isEmbeddingReady() returns false when not initialized', () => {
      component.embeddingModelInitialized = false;
      component.embeddingModelLoading = false;
      expect(component.isEmbeddingReady()).toBeFalse();
    });

    it('getEmbeddingStatusMessage() returns "Ready" when initialized and not loading', () => {
      component.embeddingModelInitialized = true;
      component.embeddingModelLoading = false;
      component.embeddingModelError = null;
      expect(component.getEmbeddingStatusMessage()).toBe('Ready');
    });

    it('getEmbeddingStatusMessage() includes loading phase when loading', () => {
      component.embeddingModelLoading = true;
      component.embeddingModelLoadingPhase = 'Tokenizing';
      expect(component.getEmbeddingStatusMessage()).toBe('Loading model (Tokenizing)...');
    });

    it('getEmbeddingStatusMessage() returns generic loading when phase is null', () => {
      component.embeddingModelLoading = true;
      component.embeddingModelLoadingPhase = null;
      expect(component.getEmbeddingStatusMessage()).toBe('Loading model...');
    });

    it('getEmbeddingStatusMessage() returns error message when not loading', () => {
      component.embeddingModelLoading = false;
      component.embeddingModelError = 'CUDA out of memory';
      expect(component.getEmbeddingStatusMessage()).toBe('CUDA out of memory');
    });

    it('getEmbeddingStatusMessage() returns "Embedding model not loaded" when not initialized and no error', () => {
      component.embeddingModelInitialized = false;
      component.embeddingModelLoading = false;
      component.embeddingModelError = null;
      expect(component.getEmbeddingStatusMessage()).toBe('Embedding model not loaded');
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // 4. QUERY EXECUTION — runQuery() / runStandardQuery() / runHybridQuery()
  // ─────────────────────────────────────────────────────────────────────────────

  describe('runQuery()', () => {
    beforeEach(() => {
      fixture.detectChanges();
      flushNgOnInit();
    });

    it('should show snackbar and not make HTTP request when query is empty', () => {
      component.query = '   ';
      component.runQuery();

      httpMock.expectNone(`${apiRoot}/rag/test/query`);
      expect(spies.snackBarSpy.open).toHaveBeenCalledWith(
        'Please enter a query', 'Close', jasmine.any(Object)
      );
    });

    it('should set isSearching to true while request is in flight (standard)', () => {
      component.query = 'test query';
      component.useHybrid = false;
      component.runQuery();

      expect(component.isSearching).toBeTrue();
      httpMock.expectOne((req) => req.url.includes('/rag/test/query')).flush({
        query: 'test query', maxResults: 5, threshold: 0, results: [], totalHits: 0
      });
    });

    it('should call GET /rag/test/query with correct params for standard search', () => {
      component.query = 'embedding models';
      component.maxResults = 7;
      component.threshold = 0.3;
      component.includeKeyword = true;
      component.includeSemantic = false;
      component.useHybrid = false;

      component.runQuery();

      const req = httpMock.expectOne((r) => r.url.includes('/rag/test/query'));
      expect(req.request.method).toBe('GET');
      expect(req.request.params.get('q')).toBe('embedding models');
      expect(req.request.params.get('k')).toBe('7');
      expect(req.request.params.get('threshold')).toBe('0.3');
      expect(req.request.params.get('keyword')).toBe('true');
      expect(req.request.params.get('semantic')).toBe('false');

      req.flush({ query: 'embedding models', maxResults: 7, threshold: 0.3, results: [], totalHits: 3 });
    });

    it('should populate queryResponse and clear isSearching on success', () => {
      component.query = 'what is RAG?';
      component.useHybrid = false;
      component.runQuery();

      const mockResponse = {
        query: 'what is RAG?',
        maxResults: 5,
        threshold: 0,
        totalHits: 2,
        results: [
          { type: 'keyword', durationMs: 20, count: 1, hits: [{ id: 'h1', score: 1.2, contentLength: 100, preview: 'RAG is...', content: 'RAG is...' }] },
          { type: 'semantic', durationMs: 40, count: 1, hits: [{ id: 'h2', score: 0.9, contentLength: 200, preview: 'Retrieval...', content: 'Retrieval...' }] }
        ]
      };

      httpMock.expectOne((r) => r.url.includes('/rag/test/query')).flush(mockResponse);

      expect(component.queryResponse).toEqual(mockResponse as any);
      expect(component.isSearching).toBeFalse();
      expect(spies.snackBarSpy.open).toHaveBeenCalledWith('Found 2 results', 'Close', jasmine.any(Object));
    });

    it('should show error snackbar and clear isSearching on standard query failure', () => {
      component.query = 'failing query';
      component.useHybrid = false;
      component.runQuery();

      httpMock.expectOne((r) => r.url.includes('/rag/test/query')).flush(
        { message: 'Index not found' }, { status: 500, statusText: 'Internal Server Error' }
      );

      expect(component.isSearching).toBeFalse();
      expect(spies.snackBarSpy.open).toHaveBeenCalledWith(
        jasmine.stringMatching(/Query failed/), 'Close', jasmine.any(Object)
      );
    });

    it('should call GET /rag/test/hybrid when useHybrid is true', () => {
      component.query = 'hybrid test';
      component.maxResults = 4;
      component.threshold = 0.1;
      component.useHybrid = true;

      component.runQuery();

      const req = httpMock.expectOne((r) => r.url.includes('/rag/test/hybrid'));
      expect(req.request.method).toBe('GET');
      expect(req.request.params.get('q')).toBe('hybrid test');
      expect(req.request.params.get('k')).toBe('4');
      expect(req.request.params.get('threshold')).toBe('0.1');

      req.flush({ query: 'hybrid test', maxResults: 4, hits: [], totalHits: 0 });
    });

    it('should populate hybridResponse and clear isSearching on hybrid success', () => {
      component.query = 'hybrid search';
      component.useHybrid = true;
      component.runQuery();

      const mockHybrid = { query: 'hybrid search', maxResults: 5, hits: [], totalHits: 5 };
      httpMock.expectOne((r) => r.url.includes('/rag/test/hybrid')).flush(mockHybrid);

      expect(component.hybridResponse).toEqual(mockHybrid as any);
      expect(component.isSearching).toBeFalse();
      expect(spies.snackBarSpy.open).toHaveBeenCalledWith(
        'Found 5 results (hybrid)', 'Close', jasmine.any(Object)
      );
    });

    it('should show error snackbar and clear isSearching on hybrid failure', () => {
      component.query = 'failing hybrid';
      component.useHybrid = true;
      component.runQuery();

      httpMock.expectOne((r) => r.url.includes('/rag/test/hybrid')).flush(
        { message: 'Hybrid search failed' }, { status: 500, statusText: 'Server Error' }
      );

      expect(component.isSearching).toBeFalse();
      expect(spies.snackBarSpy.open).toHaveBeenCalledWith(
        jasmine.stringMatching(/Hybrid query failed/), 'Close', jasmine.any(Object)
      );
    });

    it('should reset both queryResponse and hybridResponse before running a new query', () => {
      component.queryResponse = { query: 'old', maxResults: 5, threshold: 0, results: [], totalHits: 0 };
      component.hybridResponse = { query: 'old-hybrid', maxResults: 5, hits: [], totalHits: 0 };
      component.query = 'new query';
      component.useHybrid = false;
      component.runQuery();

      expect(component.queryResponse).toBeNull();
      expect(component.hybridResponse).toBeNull();

      httpMock.expectOne((r) => r.url.includes('/rag/test/query')).flush({
        query: 'new query', maxResults: 5, threshold: 0, results: [], totalHits: 0
      });
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // 5. EMBEDDING TEST — testEmbedding()
  // ─────────────────────────────────────────────────────────────────────────────

  describe('testEmbedding()', () => {
    beforeEach(() => {
      fixture.detectChanges();
      flushNgOnInit();
    });

    it('should show snackbar when embedText is empty', () => {
      component.embedText = '';
      component.testEmbedding();

      httpMock.expectNone(`${apiRoot}/rag/test/embed`);
      expect(spies.snackBarSpy.open).toHaveBeenCalledWith(
        'Please enter text to embed', 'Close', jasmine.any(Object)
      );
    });

    it('should call GET /rag/test/embed with the text param', () => {
      component.embedText = 'hello world';
      component.testEmbedding();

      const req = httpMock.expectOne((r) => r.url.includes('/rag/test/embed'));
      expect(req.request.method).toBe('GET');
      expect(req.request.params.get('text')).toBe('hello world');

      req.flush({ text: 'hello world', embeddingModel: 'bge-base', dimensions: 768, durationMs: 50, preview: [] });
    });

    it('should set isEmbedding to true while the request is in flight', () => {
      component.embedText = 'test';
      component.testEmbedding();

      expect(component.isEmbedding).toBeTrue();
      httpMock.expectOne((r) => r.url.includes('/rag/test/embed')).flush({
        text: 'test', embeddingModel: 'bge', dimensions: 384, durationMs: 30, preview: []
      });
    });

    it('should populate embedResponse and clear isEmbedding on success without timing', () => {
      component.embedText = 'sample text';
      component.testEmbedding();

      const mockResp = { text: 'sample text', embeddingModel: 'bge-base', dimensions: 768, durationMs: 45, preview: [0.1, 0.2] };
      httpMock.expectOne((r) => r.url.includes('/rag/test/embed')).flush(mockResp);

      expect(component.embedResponse).toEqual(mockResp as any);
      expect(component.isEmbedding).toBeFalse();
      expect(spies.snackBarSpy.open).toHaveBeenCalledWith(
        'Generated 768-dim embedding in 45ms', 'Close', jasmine.any(Object)
      );
    });

    it('should include subprocess timing info in snackbar when timing is present', () => {
      component.embedText = 'with timing';
      component.testEmbedding();

      const mockResp = {
        text: 'with timing',
        embeddingModel: 'bge-base',
        dimensions: 768,
        durationMs: 120,
        preview: [],
        timing: { totalWallClockMs: 120, subprocessInferenceMs: 80, subprocessOverheadMs: 40 }
      };
      httpMock.expectOne((r) => r.url.includes('/rag/test/embed')).flush(mockResp);

      expect(spies.snackBarSpy.open).toHaveBeenCalledWith(
        jasmine.stringMatching(/80ms inference.*40ms overhead/),
        'Close',
        jasmine.any(Object)
      );
    });

    it('should show embedding error from response body', () => {
      component.embedText = 'broken text';
      component.testEmbedding();

      httpMock.expectOne((r) => r.url.includes('/rag/test/embed')).flush({
        text: 'broken text', embeddingModel: 'bge', error: 'Model not initialized'
      });

      expect(spies.snackBarSpy.open).toHaveBeenCalledWith(
        'Embedding error: Model not initialized', 'Close', jasmine.any(Object)
      );
    });

    it('should show error snackbar and clear isEmbedding on HTTP error', () => {
      component.embedText = 'fail';
      component.testEmbedding();

      httpMock.expectOne((r) => r.url.includes('/rag/test/embed')).flush(
        { message: 'Server crash' }, { status: 500, statusText: 'Server Error' }
      );

      expect(component.isEmbedding).toBeFalse();
      expect(spies.snackBarSpy.open).toHaveBeenCalledWith(
        jasmine.stringMatching(/Embedding failed/), 'Close', jasmine.any(Object)
      );
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // 6. RERANKING — loadRerankers() / runRerankingQuery()
  // ─────────────────────────────────────────────────────────────────────────────

  describe('loadRerankers()', () => {
    it('should call GET /rag/test/rerankers and populate rerankerInfo', () => {
      fixture.detectChanges();
      flushNgOnInit();

      expect(component.rerankerInfo).not.toBeNull();
      expect(component.rerankerInfo?.types.length).toBeGreaterThan(0);
    });

    it('should log error and not throw when rerankers endpoint fails', () => {
      spyOn(console, 'error');
      fixture.detectChanges();

      // Status succeeds
      httpMock.expectOne(`${apiRoot}/rag/test/status`).flush({
        keywordRetriever: { class: '', available: false },
        vectorStore: { class: '', available: false },
        embeddingModel: { class: '', available: false },
        reranker: { class: '', available: false, supportedTypes: [] },
        graphRag: { class: '', available: false, searchTypes: [] }
      });
      httpMock.expectOne(`${apiRoot}/models/embedding/status`).flush({ initialized: false, dimensions: 0, loading: false });

      // Rerankers fails
      httpMock.expectOne(`${apiRoot}/rag/test/rerankers`).flush(
        { message: 'Not found' }, { status: 404, statusText: 'Not Found' }
      );

      httpMock.expectOne(`${apiRoot}/rag/test/graph/info`).flush({ available: false, class: '', type: '', description: '', searchTypes: [] });

      expect(console.error).toHaveBeenCalledWith('Failed to load rerankers:', jasmine.anything());
    });
  });

  describe('runRerankingQuery()', () => {
    beforeEach(() => {
      fixture.detectChanges();
      flushNgOnInit();
    });

    it('should show snackbar when query is empty', () => {
      component.query = '';
      component.runRerankingQuery();

      httpMock.expectNone(`${apiRoot}/rag/test/query-with-reranking`);
      expect(spies.snackBarSpy.open).toHaveBeenCalledWith(
        'Please enter a query', 'Close', jasmine.any(Object)
      );
    });

    it('should call GET /rag/test/query-with-reranking with all reranker params', () => {
      component.query = 'reranked search';
      component.maxResults = 10;
      component.threshold = 0.2;
      component.selectedRerankerType = 'rm3';
      component.rerankerParams['fbDocs'] = 15;
      component.rerankerParams['fbTerms'] = 12;
      component.rerankerParams['topK'] = 50;
      component.rerankerParams['originalQueryWeight'] = 0.6;
      component.rerankerParams['filterTerms'] = false;
      component.rerankerParams['outputQuery'] = true;

      component.runRerankingQuery();

      const req = httpMock.expectOne((r) => r.url.includes('/rag/test/query-with-reranking'));
      expect(req.request.method).toBe('GET');
      expect(req.request.params.get('q')).toBe('reranked search');
      expect(req.request.params.get('k')).toBe('10');
      expect(req.request.params.get('rerankerType')).toBe('rm3');
      expect(req.request.params.get('fbDocs')).toBe('15');
      expect(req.request.params.get('fbTerms')).toBe('12');
      expect(req.request.params.get('topK')).toBe('50');
      expect(req.request.params.get('originalQueryWeight')).toBe('0.6');
      expect(req.request.params.get('filterTerms')).toBe('false');
      expect(req.request.params.get('outputQuery')).toBe('true');

      req.flush({
        query: 'reranked search', maxResults: 10, threshold: 0.2,
        initialDurationMs: 30, initialCount: 5, initialHits: [],
        rerankerType: 'rm3', rerankerDescription: 'RM3', reranked: true,
        rerankDurationMs: 10, rerankedCount: 5, rerankedHits: [], totalHits: 5
      });
    });

    it('should set isRerankingSearch to true while the request is in flight', () => {
      component.query = 'in flight';
      component.runRerankingQuery();

      expect(component.isRerankingSearch).toBeTrue();
      httpMock.expectOne((r) => r.url.includes('/rag/test/query-with-reranking')).flush({
        query: 'in flight', maxResults: 5, threshold: 0,
        initialDurationMs: 20, initialCount: 2, initialHits: [],
        rerankerType: 'none', rerankerDescription: '', reranked: false,
        rerankDurationMs: 0, rerankedHits: [], totalHits: 2
      });
    });

    it('should populate rerankingResponse and clear isRerankingSearch on success (reranked)', () => {
      component.query = 'rerank me';
      component.runRerankingQuery();

      const mockResp = {
        query: 'rerank me', maxResults: 5, threshold: 0,
        initialDurationMs: 25, initialCount: 3, initialHits: [],
        rerankerType: 'rm3', rerankerDescription: 'RM3', reranked: true,
        rerankDurationMs: 8, rerankedCount: 3,
        rerankedHits: [
          { id: 'h1', score: 1.5, contentLength: 100, preview: 'Doc 1', content: 'Doc 1', newRank: 1, originalRank: 3, rankChange: 2 }
        ],
        totalHits: 3
      };

      httpMock.expectOne((r) => r.url.includes('/rag/test/query-with-reranking')).flush(mockResp);

      expect(component.rerankingResponse).toEqual(mockResp as any);
      expect(component.isRerankingSearch).toBeFalse();
      expect(spies.snackBarSpy.open).toHaveBeenCalledWith(
        jasmine.stringMatching(/Found 3 results in 25ms.*reranked in 8ms/),
        'Close',
        jasmine.any(Object)
      );
    });

    it('should show success snackbar without rerank info when not reranked', () => {
      component.query = 'no rerank';
      component.runRerankingQuery();

      httpMock.expectOne((r) => r.url.includes('/rag/test/query-with-reranking')).flush({
        query: 'no rerank', maxResults: 5, threshold: 0,
        initialDurationMs: 20, initialCount: 2, initialHits: [],
        rerankerType: 'none', rerankerDescription: '', reranked: false,
        rerankDurationMs: 0, rerankedHits: [], totalHits: 2
      });

      expect(spies.snackBarSpy.open).toHaveBeenCalledWith(
        'Found 2 results in 20ms',
        'Close',
        jasmine.any(Object)
      );
    });

    it('should show reranking error from response body', () => {
      component.query = 'errored rerank';
      component.runRerankingQuery();

      httpMock.expectOne((r) => r.url.includes('/rag/test/query-with-reranking')).flush({
        query: 'errored rerank', maxResults: 5, threshold: 0,
        initialDurationMs: 10, initialCount: 0, initialHits: [],
        rerankerType: 'rm3', rerankerDescription: '', reranked: false,
        rerankDurationMs: 0, rerankedHits: [], totalHits: 0,
        error: 'RM3 failed: model error'
      });

      expect(spies.snackBarSpy.open).toHaveBeenCalledWith(
        'Reranking error: RM3 failed: model error', 'Close', jasmine.any(Object)
      );
    });

    it('should show error snackbar and clear isRerankingSearch on HTTP error', () => {
      component.query = 'http fail';
      component.runRerankingQuery();

      httpMock.expectOne((r) => r.url.includes('/rag/test/query-with-reranking')).flush(
        { message: 'Timeout' }, { status: 504, statusText: 'Gateway Timeout' }
      );

      expect(component.isRerankingSearch).toBeFalse();
      expect(spies.snackBarSpy.open).toHaveBeenCalledWith(
        jasmine.stringMatching(/Reranking query failed/), 'Close', jasmine.any(Object)
      );
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // 7. RERANKER PARAMETER TYPE DETECTION
  // ─────────────────────────────────────────────────────────────────────────────

  describe('Parameter type detection helpers', () => {
    const numberParam = { id: 'fbDocs', label: 'Feedback Docs', type: 'number' as const, default: 10, min: 1, max: 100 };
    const boolParam = { id: 'filterTerms', label: 'Filter Terms', type: 'boolean' as const, default: true };
    const selectParam = { id: 'model', label: 'Model', type: 'select' as const, default: 'mini', options: ['mini', 'large'] };
    const stringParam = { id: 'notes', label: 'Notes', type: 'string' as const, default: '' };

    beforeEach(() => {
      fixture.detectChanges();
      flushNgOnInit();
    });

    it('isNumberParam() returns true only for number type', () => {
      expect(component.isNumberParam(numberParam)).toBeTrue();
      expect(component.isNumberParam(boolParam)).toBeFalse();
      expect(component.isNumberParam(selectParam)).toBeFalse();
      expect(component.isNumberParam(stringParam)).toBeFalse();
    });

    it('isBooleanParam() returns true only for boolean type', () => {
      expect(component.isBooleanParam(boolParam)).toBeTrue();
      expect(component.isBooleanParam(numberParam)).toBeFalse();
      expect(component.isBooleanParam(selectParam)).toBeFalse();
      expect(component.isBooleanParam(stringParam)).toBeFalse();
    });

    it('isSelectParam() returns true only for select type', () => {
      expect(component.isSelectParam(selectParam)).toBeTrue();
      expect(component.isSelectParam(numberParam)).toBeFalse();
      expect(component.isSelectParam(boolParam)).toBeFalse();
      expect(component.isSelectParam(stringParam)).toBeFalse();
    });

    it('isStringParam() returns true only for string type', () => {
      expect(component.isStringParam(stringParam)).toBeTrue();
      expect(component.isStringParam(numberParam)).toBeFalse();
      expect(component.isStringParam(boolParam)).toBeFalse();
      expect(component.isStringParam(selectParam)).toBeFalse();
    });

    it('getNumberParams() returns only number parameters for selected reranker', () => {
      component.selectedRerankerType = 'rm3';
      const params = component.getNumberParams();
      expect(params.every(p => p.type === 'number')).toBeTrue();
      expect(params.length).toBeGreaterThan(0);
    });

    it('getBooleanParams() returns only boolean parameters for selected reranker', () => {
      component.selectedRerankerType = 'rm3';
      const params = component.getBooleanParams();
      expect(params.every(p => p.type === 'boolean')).toBeTrue();
    });

    it('getSelectParams() returns only select parameters for cross_encoder reranker', () => {
      component.selectedRerankerType = 'cross_encoder';
      const params = component.getSelectParams();
      expect(params.every(p => p.type === 'select')).toBeTrue();
      expect(params.length).toBeGreaterThan(0);
    });

    it('getNumberParams() returns empty array when no reranker selected', () => {
      component.selectedRerankerType = 'none';
      expect(component.getNumberParams()).toEqual([]);
    });

    it('getBooleanParams() returns empty array when rerankerInfo is null', () => {
      component.rerankerInfo = null;
      expect(component.getBooleanParams()).toEqual([]);
    });

    it('getSelectParams() returns empty array when rerankerInfo is null', () => {
      component.rerankerInfo = null;
      expect(component.getSelectParams()).toEqual([]);
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // 8. RERANKER PARAMETER VALUE ACCESSORS
  // ─────────────────────────────────────────────────────────────────────────────

  describe('Reranker parameter value accessors', () => {
    beforeEach(() => {
      fixture.detectChanges();
      flushNgOnInit();
    });

    it('getParamBoolValue() returns the boolean stored in rerankerParams', () => {
      component.rerankerParams['filterTerms'] = true;
      expect(component.getParamBoolValue('filterTerms')).toBeTrue();

      component.rerankerParams['filterTerms'] = false;
      expect(component.getParamBoolValue('filterTerms')).toBeFalse();
    });

    it('getParamBoolValue() returns false for non-boolean value', () => {
      component.rerankerParams['filterTerms'] = 42 as any;
      expect(component.getParamBoolValue('filterTerms')).toBeFalse();
    });

    it('setParamBoolValue() stores the boolean in rerankerParams', () => {
      component.setParamBoolValue('outputQuery', true);
      expect(component.rerankerParams['outputQuery']).toBeTrue();
    });

    it('getParamNumberValue() returns the number stored in rerankerParams', () => {
      component.rerankerParams['fbDocs'] = 20;
      expect(component.getParamNumberValue('fbDocs')).toBe(20);
    });

    it('getParamNumberValue() returns 0 for non-number value', () => {
      component.rerankerParams['fbDocs'] = 'notanumber' as any;
      expect(component.getParamNumberValue('fbDocs')).toBe(0);
    });

    it('setParamNumberValue() stores the number in rerankerParams', () => {
      component.setParamNumberValue('fbTerms', 25);
      expect(component.rerankerParams['fbTerms']).toBe(25);
    });

    it('getParamStringValue() returns the string stored in rerankerParams', () => {
      component.rerankerParams['crossEncoderModel'] = 'ms-marco-large';
      expect(component.getParamStringValue('crossEncoderModel')).toBe('ms-marco-large');
    });

    it('getParamStringValue() returns empty string for non-string value', () => {
      component.rerankerParams['crossEncoderModel'] = 42 as any;
      expect(component.getParamStringValue('crossEncoderModel')).toBe('');
    });

    it('setParamStringValue() stores the string in rerankerParams', () => {
      component.setParamStringValue('crossEncoderModel', 'ms-marco-MiniLM-L-12-v2');
      expect(component.rerankerParams['crossEncoderModel']).toBe('ms-marco-MiniLM-L-12-v2');
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // 9. onRerankerTypeChange() — reset to defaults
  // ─────────────────────────────────────────────────────────────────────────────

  describe('onRerankerTypeChange()', () => {
    beforeEach(() => {
      fixture.detectChanges();
      flushNgOnInit();
    });

    it('should reset rerankerParams to defaults of the selected type', () => {
      component.selectedRerankerType = 'rm3';
      component.rerankerParams['fbDocs'] = 999; // dirty value

      component.onRerankerTypeChange();

      expect(component.rerankerParams['fbDocs']).toBe(10); // default from flushed reranker info
    });

    it('should not throw when selectedRerankerType is "none"', () => {
      component.selectedRerankerType = 'none';
      expect(() => component.onRerankerTypeChange()).not.toThrow();
    });

    it('should not throw when rerankerInfo is null', () => {
      component.rerankerInfo = null;
      component.selectedRerankerType = 'rm3';
      expect(() => component.onRerankerTypeChange()).not.toThrow();
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // 10. RANK CHANGE UI HELPERS
  // ─────────────────────────────────────────────────────────────────────────────

  describe('getRankChangeClass() / getRankChangeIcon() / formatRankChange()', () => {
    beforeEach(() => {
      fixture.detectChanges();
      flushNgOnInit();
    });

    it('getRankChangeClass() returns "rank-unchanged" for undefined change', () => {
      expect(component.getRankChangeClass(undefined)).toBe('rank-unchanged');
    });

    it('getRankChangeClass() returns "rank-unchanged" for 0 change', () => {
      expect(component.getRankChangeClass(0)).toBe('rank-unchanged');
    });

    it('getRankChangeClass() returns "rank-up" for positive change', () => {
      expect(component.getRankChangeClass(3)).toBe('rank-up');
    });

    it('getRankChangeClass() returns "rank-down" for negative change', () => {
      expect(component.getRankChangeClass(-2)).toBe('rank-down');
    });

    it('getRankChangeIcon() returns "remove" for undefined change', () => {
      expect(component.getRankChangeIcon(undefined)).toBe('remove');
    });

    it('getRankChangeIcon() returns "remove" for 0 change', () => {
      expect(component.getRankChangeIcon(0)).toBe('remove');
    });

    it('getRankChangeIcon() returns "arrow_upward" for positive change', () => {
      expect(component.getRankChangeIcon(5)).toBe('arrow_upward');
    });

    it('getRankChangeIcon() returns "arrow_downward" for negative change', () => {
      expect(component.getRankChangeIcon(-1)).toBe('arrow_downward');
    });

    it('formatRankChange() returns "-" for undefined change', () => {
      expect(component.formatRankChange(undefined)).toBe('-');
    });

    it('formatRankChange() returns "-" for 0 change', () => {
      expect(component.formatRankChange(0)).toBe('-');
    });

    it('formatRankChange() returns "+N" for positive change', () => {
      expect(component.formatRankChange(4)).toBe('+4');
    });

    it('formatRankChange() returns "-N" for negative change', () => {
      expect(component.formatRankChange(-3)).toBe('-3');
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // 11. UI STATE HELPERS — toggleHitExpansion() / isHitExpanded() / copyContent()
  // ─────────────────────────────────────────────────────────────────────────────

  describe('toggleHitExpansion() and isHitExpanded()', () => {
    beforeEach(() => {
      fixture.detectChanges();
      flushNgOnInit();
    });

    it('isHitExpanded() returns false for all hits before any toggle', () => {
      expect(component.isHitExpanded('hit-1')).toBeFalse();
    });

    it('toggleHitExpansion() should expand a hit that was collapsed', () => {
      component.toggleHitExpansion('hit-1');
      expect(component.isHitExpanded('hit-1')).toBeTrue();
    });

    it('toggleHitExpansion() should collapse a hit that was expanded', () => {
      component.toggleHitExpansion('hit-1');
      component.toggleHitExpansion('hit-1');
      expect(component.isHitExpanded('hit-1')).toBeFalse();
    });

    it('toggleHitExpansion() should collapse the previous hit when expanding a new one', () => {
      component.toggleHitExpansion('hit-1');
      component.toggleHitExpansion('hit-2');

      expect(component.isHitExpanded('hit-1')).toBeFalse();
      expect(component.isHitExpanded('hit-2')).toBeTrue();
    });

    it('isHitExpanded() returns false for a different hit than the expanded one', () => {
      component.toggleHitExpansion('hit-1');
      expect(component.isHitExpanded('hit-99')).toBeFalse();
    });
  });

  describe('copyContent()', () => {
    let originalClipboard: Clipboard;
    let clipboardSpy: jasmine.SpyObj<Clipboard>;

    beforeEach(() => {
      fixture.detectChanges();
      flushNgOnInit();
      originalClipboard = navigator.clipboard;
      clipboardSpy = jasmine.createSpyObj('Clipboard', ['writeText']);
      clipboardSpy.writeText.and.returnValue(Promise.resolve());
      Object.defineProperty(navigator, 'clipboard', {
        value: clipboardSpy,
        writable: true,
        configurable: true
      });
    });

    afterEach(() => {
      Object.defineProperty(navigator, 'clipboard', {
        value: originalClipboard,
        writable: true,
        configurable: true
      });
    });

    it('should call navigator.clipboard.writeText with the given content', fakeAsync(() => {
      component.copyContent('some content');
      tick();

      expect(clipboardSpy.writeText).toHaveBeenCalledWith('some content');
      expect(spies.snackBarSpy.open).toHaveBeenCalledWith(
        'Content copied to clipboard', 'Close', jasmine.any(Object)
      );
    }));

    it('should show error snackbar when clipboard write fails', fakeAsync(() => {
      clipboardSpy.writeText.and.returnValue(Promise.reject(new Error('Denied')));
      component.copyContent('content');
      tick();

      expect(spies.snackBarSpy.open).toHaveBeenCalledWith('Failed to copy', 'Close', jasmine.any(Object));
    }));
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // 12. GRAPH RAG — loadGraphRagInfo() / runGraphRagQuery() / clearGraphRagConversation()
  // ─────────────────────────────────────────────────────────────────────────────

  describe('loadGraphRagInfo()', () => {
    it('should call GET /rag/test/graph/info and populate graphRagInfo', () => {
      fixture.detectChanges();
      flushNgOnInit({
        graphRagBody: {
          available: true, class: 'Neo4jGraphRag', type: 'neo4j', description: 'Neo4j RAG',
          searchTypes: [
            { id: 'LOCAL', name: 'Local', description: 'Local community search' },
            { id: 'GLOBAL', name: 'Global', description: 'Global community search' }
          ]
        }
      });

      expect(component.graphRagInfo).not.toBeNull();
      expect(component.graphRagInfo?.type).toBe('neo4j');
      expect(component.graphRagInfo?.searchTypes.length).toBe(2);
    });

    it('should log error when graph info endpoint fails', () => {
      spyOn(console, 'error');
      fixture.detectChanges();

      httpMock.expectOne(`${apiRoot}/rag/test/status`).flush({
        keywordRetriever: { class: '', available: false }, vectorStore: { class: '', available: false },
        embeddingModel: { class: '', available: false },
        reranker: { class: '', available: false, supportedTypes: [] },
        graphRag: { class: '', available: false, searchTypes: [] }
      });
      httpMock.expectOne(`${apiRoot}/models/embedding/status`).flush({ initialized: false, dimensions: 0, loading: false });
      httpMock.expectOne(`${apiRoot}/rag/test/rerankers`).flush({ available: false, types: [] });
      httpMock.expectOne(`${apiRoot}/rag/test/graph/info`).flush(
        { message: 'Graph RAG not configured' }, { status: 404, statusText: 'Not Found' }
      );

      expect(console.error).toHaveBeenCalledWith(
        'Failed to load Graph RAG info:', jasmine.anything()
      );
    });
  });

  describe('runGraphRagQuery()', () => {
    beforeEach(() => {
      fixture.detectChanges();
      flushNgOnInit();
    });

    it('should show snackbar when graphRagQuery is empty', () => {
      component.graphRagQuery = '';
      component.runGraphRagQuery();

      httpMock.expectNone(`${apiRoot}/rag/test/graph/query`);
      expect(spies.snackBarSpy.open).toHaveBeenCalledWith(
        'Please enter a query', 'Close', jasmine.any(Object)
      );
    });

    it('should call GET /rag/test/graph/query with correct params', () => {
      component.graphRagQuery = 'What entities are linked?';
      component.graphRagSearchType = 'GLOBAL';
      component.graphRagMaxResults = 8;
      component.graphRagConversationId = 'conv-abc';

      component.runGraphRagQuery();

      const req = httpMock.expectOne((r) => r.url.includes('/rag/test/graph/query'));
      expect(req.request.method).toBe('GET');
      expect(req.request.params.get('q')).toBe('What entities are linked?');
      expect(req.request.params.get('searchType')).toBe('GLOBAL');
      expect(req.request.params.get('k')).toBe('8');
      expect(req.request.params.get('conversationId')).toBe('conv-abc');

      req.flush({
        query: 'What entities are linked?', searchType: 'GLOBAL', k: 8,
        conversationId: 'conv-abc', available: true, durationMs: 150, answer: 'Many entities.'
      });
    });

    it('should set isGraphRagSearching to true while the request is in flight', () => {
      component.graphRagQuery = 'in flight graph query';
      component.runGraphRagQuery();

      expect(component.isGraphRagSearching).toBeTrue();
      httpMock.expectOne((r) => r.url.includes('/rag/test/graph/query')).flush({
        query: '', searchType: 'LOCAL', k: 5, conversationId: 'test', available: true, durationMs: 100
      });
    });

    it('should populate graphRagResponse and clear isGraphRagSearching on success', () => {
      component.graphRagQuery = 'find all relationships';
      component.runGraphRagQuery();

      const mockResp = {
        query: 'find all relationships', searchType: 'LOCAL', k: 5,
        conversationId: 'test', available: true, durationMs: 200,
        answer: 'Found 10 relationships.'
      };
      httpMock.expectOne((r) => r.url.includes('/rag/test/graph/query')).flush(mockResp);

      expect(component.graphRagResponse).toEqual(mockResp as any);
      expect(component.isGraphRagSearching).toBeFalse();
      expect(spies.snackBarSpy.open).toHaveBeenCalledWith(
        'Graph RAG query completed in 200ms', 'Close', jasmine.any(Object)
      );
    });

    it('should show error from response body when present', () => {
      component.graphRagQuery = 'fail graph query';
      component.runGraphRagQuery();

      httpMock.expectOne((r) => r.url.includes('/rag/test/graph/query')).flush({
        query: 'fail graph query', searchType: 'LOCAL', k: 5, conversationId: 'test',
        available: false, durationMs: 0, error: 'Graph not available'
      });

      expect(spies.snackBarSpy.open).toHaveBeenCalledWith(
        'Graph RAG error: Graph not available', 'Close', jasmine.any(Object)
      );
    });

    it('should show error snackbar and clear isGraphRagSearching on HTTP error', () => {
      component.graphRagQuery = 'http fail graph';
      component.runGraphRagQuery();

      httpMock.expectOne((r) => r.url.includes('/rag/test/graph/query')).flush(
        { message: 'Service down' }, { status: 500, statusText: 'Server Error' }
      );

      expect(component.isGraphRagSearching).toBeFalse();
      expect(spies.snackBarSpy.open).toHaveBeenCalledWith(
        jasmine.stringMatching(/Graph RAG query failed/), 'Close', jasmine.any(Object)
      );
    });
  });

  describe('clearGraphRagConversation()', () => {
    beforeEach(() => {
      fixture.detectChanges();
      flushNgOnInit();
    });

    it('should generate a new conversation ID prefixed with "test-"', () => {
      const oldId = component.graphRagConversationId;
      component.clearGraphRagConversation();

      expect(component.graphRagConversationId).not.toBe(oldId);
      expect(component.graphRagConversationId).toMatch(/^test-\d+$/);
    });

    it('should show a snackbar confirming the conversation reset', () => {
      component.clearGraphRagConversation();

      expect(spies.snackBarSpy.open).toHaveBeenCalledWith(
        'Conversation reset with new ID', 'Close', jasmine.any(Object)
      );
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // 13. GRAPH RAG DISPLAY HELPERS
  // ─────────────────────────────────────────────────────────────────────────────

  describe('Graph RAG display helpers', () => {
    beforeEach(() => {
      fixture.detectChanges();
      flushNgOnInit();
    });

    it('getGraphRagSearchTypeDescription() returns description for current search type', () => {
      // graphRagInfo is populated by flushNgOnInit
      component.graphRagSearchType = 'LOCAL';
      expect(component.getGraphRagSearchTypeDescription()).toBeTruthy();
    });

    it('getGraphRagSearchTypeDescription() returns empty string when graphRagInfo is null', () => {
      component.graphRagInfo = null;
      expect(component.getGraphRagSearchTypeDescription()).toBe('');
    });

    it('getGraphRagSearchTypeDescription() returns empty string for unknown search type', () => {
      component.graphRagSearchType = 'UNKNOWN_TYPE';
      expect(component.getGraphRagSearchTypeDescription()).toBe('');
    });

    it('getGraphRagTypeIcon() returns "hub" for neo4j type', () => {
      component.graphRagInfo = { available: true, class: 'Neo4j', type: 'neo4j', description: '', searchTypes: [] };
      expect(component.getGraphRagTypeIcon()).toBe('hub');
    });

    it('getGraphRagTypeIcon() returns "grid_on" for matrix type', () => {
      component.graphRagInfo = { available: true, class: 'Matrix', type: 'matrix', description: '', searchTypes: [] };
      expect(component.getGraphRagTypeIcon()).toBe('grid_on');
    });

    it('getGraphRagTypeIcon() returns "account_tree" for unknown type', () => {
      component.graphRagInfo = { available: true, class: 'Custom', type: 'custom', description: '', searchTypes: [] };
      expect(component.getGraphRagTypeIcon()).toBe('account_tree');
    });

    it('getGraphRagTypeIcon() returns "account_tree" when graphRagInfo is null', () => {
      component.graphRagInfo = null;
      expect(component.getGraphRagTypeIcon()).toBe('account_tree');
    });

    it('toggleGraphRagContext() should toggle showGraphRagContext', () => {
      expect(component.showGraphRagContext).toBeFalse();
      component.toggleGraphRagContext();
      expect(component.showGraphRagContext).toBeTrue();
      component.toggleGraphRagContext();
      expect(component.showGraphRagContext).toBeFalse();
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // 14. NAVIGATION — navigateToEmbeddingLogs()
  // ─────────────────────────────────────────────────────────────────────────────

  describe('navigateToEmbeddingLogs()', () => {
    beforeEach(() => {
      fixture.detectChanges();
      flushNgOnInit();
    });

    it('should navigate to /developer-hub with tab=2 (Management)', () => {
      component.navigateToEmbeddingLogs();

      expect(spies.routerSpy.navigate).toHaveBeenCalledWith(
        ['/developer-hub'],
        { queryParams: { tab: 2 } }
      );
    });

    it('should show a navigation snackbar when navigating to logs', () => {
      component.navigateToEmbeddingLogs();

      expect(spies.snackBarSpy.open).toHaveBeenCalledWith(
        jasmine.stringMatching(/Developer Hub.*Management.*Job Scheduler.*Subprocess Logs/),
        'Close',
        jasmine.any(Object)
      );
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // 15. WEBSOCKET — subscribeToModelStatusUpdates() / handleModelStatusUpdate()
  // ─────────────────────────────────────────────────────────────────────────────

  describe('WebSocket model status updates', () => {
    beforeEach(() => {
      fixture.detectChanges();
      flushNgOnInit();
    });

    it('should call websocketService.connect() and subscribeToModelStatus() on init', () => {
      expect(spies.webSocketServiceSpy.connect).toHaveBeenCalled();
      expect(spies.webSocketServiceSpy.subscribeToModelStatus).toHaveBeenCalled();
    });

    it('should not subscribe a second time if already subscribed (guard)', () => {
      // ngOnInit already subscribed once; call the private method again
      (component as any).subscribeToModelStatusUpdates();
      // subscribeToModelStatus should still have been called only once
      expect(spies.webSocketServiceSpy.subscribeToModelStatus).toHaveBeenCalledTimes(1);
    });

    it('should update embeddingModelInitialized when a valid status arrives', () => {
      component.embeddingModelInitialized = false;

      spies.modelStatusSubject.next(makeModelStatusUpdate({
        embedding: { available: true, loading: false, initialized: true, dimensions: 768 }
      } as any));

      expect(component.embeddingModelInitialized).toBeTrue();
    });

    it('should update embeddingModelLoading and loadingPhase from status update', () => {
      spies.modelStatusSubject.next(makeModelStatusUpdate({
        embedding: { available: false, loading: true, initialized: false, dimensions: 0, loadingPhase: 'Quantizing' }
      } as any));

      expect(component.embeddingModelLoading).toBeTrue();
      expect(component.embeddingModelLoadingPhase).toBe('Quantizing');
    });

    it('should update embeddingModelError from status update', () => {
      spies.modelStatusSubject.next(makeModelStatusUpdate({
        embedding: { available: false, loading: false, initialized: false, dimensions: 0, error: 'OOM error' }
      } as any));

      expect(component.embeddingModelError).toBe('OOM error');
    });

    it('should not update state when status has no embedding field', () => {
      component.embeddingModelInitialized = true;
      const noEmbedding = { timestamp: Date.now(), ready: false } as any;
      spies.modelStatusSubject.next(noEmbedding);

      // State should remain unchanged
      expect(component.embeddingModelInitialized).toBeTrue();
    });

    it('should show snackbar and reload status when model transitions from unavailable to ready', () => {
      // Simulate an existing status where embedding was not available
      component.status = {
        keywordRetriever: { class: '', available: false },
        vectorStore: { class: '', available: false },
        embeddingModel: { class: '', available: false },
        reranker: { class: '', available: false, supportedTypes: [] },
        graphRag: { class: '', available: false, searchTypes: [] }
      };

      spies.modelStatusSubject.next(makeModelStatusUpdate({
        embedding: { available: true, loading: false, initialized: true, dimensions: 768 }
      } as any));

      expect(spies.snackBarSpy.open).toHaveBeenCalledWith(
        'Embedding model is now ready!', 'Close', jasmine.any(Object)
      );

      // loadStatus() should have been called again → expect the HTTP request
      const reloadReq = httpMock.expectOne(`${apiRoot}/rag/test/status`);
      reloadReq.flush({
        keywordRetriever: { class: '', available: true },
        vectorStore: { class: '', available: true },
        embeddingModel: { class: '', available: true },
        reranker: { class: '', available: false, supportedTypes: [] },
        graphRag: { class: '', available: false, searchTypes: [] }
      });
      httpMock.expectOne(`${apiRoot}/models/embedding/status`).flush({ initialized: true, dimensions: 768, loading: false });
    });

    it('should update status.embeddingModel.available to reflect WebSocket state', () => {
      component.status = {
        keywordRetriever: { class: '', available: true },
        vectorStore: { class: '', available: true },
        embeddingModel: { class: 'BgeEncoder', available: false },
        reranker: { class: '', available: false, supportedTypes: [] },
        graphRag: { class: '', available: false, searchTypes: [] }
      };

      spies.modelStatusSubject.next(makeModelStatusUpdate({
        embedding: { available: true, loading: false, initialized: true, dimensions: 384 }
      } as any));

      expect(component.status.embeddingModel.available).toBeTrue();

      // Flush the reload triggered by the transition
      httpMock.expectOne(`${apiRoot}/rag/test/status`).flush({
        keywordRetriever: { class: '', available: true }, vectorStore: { class: '', available: true },
        embeddingModel: { class: '', available: true },
        reranker: { class: '', available: false, supportedTypes: [] },
        graphRag: { class: '', available: false, searchTypes: [] }
      });
      httpMock.expectOne(`${apiRoot}/models/embedding/status`).flush({ initialized: true, dimensions: 384, loading: false });
    });

    it('should clear embeddingModelError when model becomes ready', () => {
      component.embeddingModelError = 'Previous error';
      component.status = {
        keywordRetriever: { class: '', available: false }, vectorStore: { class: '', available: false },
        embeddingModel: { class: '', available: false },
        reranker: { class: '', available: false, supportedTypes: [] },
        graphRag: { class: '', available: false, searchTypes: [] }
      };

      spies.modelStatusSubject.next(makeModelStatusUpdate({
        embedding: { available: true, loading: false, initialized: true, dimensions: 768 }
      } as any));

      expect(component.embeddingModelError).toBeNull();

      // Flush reload
      httpMock.expectOne(`${apiRoot}/rag/test/status`).flush({
        keywordRetriever: { class: '', available: true }, vectorStore: { class: '', available: true },
        embeddingModel: { class: '', available: true },
        reranker: { class: '', available: false, supportedTypes: [] },
        graphRag: { class: '', available: false, searchTypes: [] }
      });
      httpMock.expectOne(`${apiRoot}/models/embedding/status`).flush({ initialized: true, dimensions: 768, loading: false });
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // 16. MISC DISPLAY HELPERS
  // ─────────────────────────────────────────────────────────────────────────────

  describe('Misc display helpers', () => {
    beforeEach(() => {
      fixture.detectChanges();
      flushNgOnInit();
    });

    it('getKeywordResults() should return keyword result from queryResponse', () => {
      component.queryResponse = {
        query: 'q', maxResults: 5, threshold: 0, totalHits: 1,
        results: [
          { type: 'keyword', durationMs: 10, count: 1, hits: [] },
          { type: 'semantic', durationMs: 20, count: 0, hits: [] }
        ]
      };
      const result = component.getKeywordResults();
      expect(result?.type).toBe('keyword');
    });

    it('getSemanticResults() should return semantic result from queryResponse', () => {
      component.queryResponse = {
        query: 'q', maxResults: 5, threshold: 0, totalHits: 1,
        results: [
          { type: 'keyword', durationMs: 10, count: 0, hits: [] },
          { type: 'semantic', durationMs: 30, count: 1, hits: [] }
        ]
      };
      const result = component.getSemanticResults();
      expect(result?.type).toBe('semantic');
    });

    it('getKeywordResults() returns null when queryResponse is null', () => {
      component.queryResponse = null;
      expect(component.getKeywordResults()).toBeNull();
    });

    it('getSemanticResults() returns null when queryResponse is null', () => {
      component.queryResponse = null;
      expect(component.getSemanticResults()).toBeNull();
    });

    it('getSourceBadgeClass() returns "badge-keyword" for keyword source', () => {
      expect(component.getSourceBadgeClass('keyword')).toBe('badge-keyword');
    });

    it('getSourceBadgeClass() returns "badge-semantic" for non-keyword source', () => {
      expect(component.getSourceBadgeClass('semantic')).toBe('badge-semantic');
      expect(component.getSourceBadgeClass('other')).toBe('badge-semantic');
    });

    it('formatScore() formats a number to 4 decimal places', () => {
      expect(component.formatScore(0.12345678)).toBe('0.1235');
    });

    it('formatScore() returns "N/A" for undefined', () => {
      expect(component.formatScore(undefined)).toBe('N/A');
    });

    it('formatScore() returns "N/A" for null', () => {
      expect(component.formatScore(null as any)).toBe('N/A');
    });

    it('getRerankerIcon() returns correct icons for each known type', () => {
      expect(component.getRerankerIcon('rm3')).toBe('autorenew');
      expect(component.getRerankerIcon('bm25prf')).toBe('tune');
      expect(component.getRerankerIcon('rocchio')).toBe('compare_arrows');
      expect(component.getRerankerIcon('axiom')).toBe('analytics');
      expect(component.getRerankerIcon('cross_encoder')).toBe('psychology');
      expect(component.getRerankerIcon('score_ties')).toBe('swap_vert');
      expect(component.getRerankerIcon('rrf')).toBe('merge_type');
      expect(component.getRerankerIcon('normalize')).toBe('straighten');
      expect(component.getRerankerIcon('mmr')).toBe('diversity_3');
    });

    it('getRerankerIcon() returns "sort" for unknown reranker type', () => {
      expect(component.getRerankerIcon('unknown_type')).toBe('sort');
    });

    it('getSelectedRerankerType() returns null when selectedRerankerType is "none"', () => {
      component.selectedRerankerType = 'none';
      expect(component.getSelectedRerankerType()).toBeNull();
    });

    it('getSelectedRerankerType() returns null when rerankerInfo is null', () => {
      component.rerankerInfo = null;
      component.selectedRerankerType = 'rm3';
      expect(component.getSelectedRerankerType()).toBeNull();
    });

    it('getSelectedRerankerType() returns the matching type info', () => {
      component.selectedRerankerType = 'rm3';
      const typeInfo = component.getSelectedRerankerType();
      expect(typeInfo?.id).toBe('rm3');
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // 17. getParamStep() — number step calculation
  // ─────────────────────────────────────────────────────────────────────────────

  describe('getParamStep()', () => {
    beforeEach(() => {
      fixture.detectChanges();
      flushNgOnInit();
    });

    it('returns 1 for non-number params', () => {
      const boolParam = { id: 'filter', label: 'Filter', type: 'boolean' as const, default: true };
      expect(component.getParamStep(boolParam)).toBe(1);
    });

    it('returns 0.05 for number params with max <= 1', () => {
      const weightParam = { id: 'oqw', label: 'OQW', type: 'number' as const, default: 0.5, min: 0, max: 1 };
      expect(component.getParamStep(weightParam)).toBe(0.05);
    });

    it('returns 0.1 for number params with decimal default and no max <= 1 constraint', () => {
      const decimalParam = { id: 'beta', label: 'Beta', type: 'number' as const, default: 0.75, min: 0, max: 10 };
      expect(component.getParamStep(decimalParam)).toBe(0.1);
    });

    it('returns 1 for number params with integer default', () => {
      const intParam = { id: 'fbDocs', label: 'Docs', type: 'number' as const, default: 10, min: 1, max: 100 };
      expect(component.getParamStep(intParam)).toBe(1);
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // 18. GRAPH RAG copyGraphRagContent()
  // ─────────────────────────────────────────────────────────────────────────────

  describe('copyGraphRagContent()', () => {
    let originalClipboard: Clipboard;
    let clipboardSpy: jasmine.SpyObj<Clipboard>;

    beforeEach(() => {
      fixture.detectChanges();
      flushNgOnInit();
      originalClipboard = navigator.clipboard;
      clipboardSpy = jasmine.createSpyObj('Clipboard', ['writeText']);
      clipboardSpy.writeText.and.returnValue(Promise.resolve());
      Object.defineProperty(navigator, 'clipboard', {
        value: clipboardSpy,
        writable: true,
        configurable: true
      });
    });

    afterEach(() => {
      Object.defineProperty(navigator, 'clipboard', {
        value: originalClipboard,
        writable: true,
        configurable: true
      });
    });

    it('should copy graph RAG content to clipboard', fakeAsync(() => {
      component.copyGraphRagContent('graph context text');
      tick();

      expect(clipboardSpy.writeText).toHaveBeenCalledWith('graph context text');
      expect(spies.snackBarSpy.open).toHaveBeenCalledWith(
        'Content copied to clipboard', 'Close', jasmine.any(Object)
      );
    }));

    it('should show error snackbar when clipboard copy fails', fakeAsync(() => {
      clipboardSpy.writeText.and.returnValue(Promise.reject(new Error('Permission denied')));
      component.copyGraphRagContent('some text');
      tick();

      expect(spies.snackBarSpy.open).toHaveBeenCalledWith('Failed to copy', 'Close', jasmine.any(Object));
    }));
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // 19. SNACKBAR CONFIGURATION
  // ─────────────────────────────────────────────────────────────────────────────

  describe('Snackbar configuration', () => {
    beforeEach(() => {
      fixture.detectChanges();
      flushNgOnInit();
    });

    it('should use snackbar-success panel class for non-error messages', () => {
      component.query = 'snackbar check';
      component.useHybrid = false;
      component.runQuery();

      httpMock.expectOne((r) => r.url.includes('/rag/test/query')).flush({
        query: 'snackbar check', maxResults: 5, threshold: 0, results: [], totalHits: 1
      });

      const call = spies.snackBarSpy.open.calls.mostRecent();
      expect(call.args[2].panelClass).toBe('snackbar-success');
    });

    it('should use snackbar-error panel class for error messages', () => {
      component.query = 'error snackbar';
      component.useHybrid = false;
      component.runQuery();

      httpMock.expectOne((r) => r.url.includes('/rag/test/query')).flush(
        { message: 'Error' }, { status: 500, statusText: 'Error' }
      );

      const call = spies.snackBarSpy.open.calls.mostRecent();
      expect(call.args[2].panelClass).toBe('snackbar-error');
    });

    it('should close snackbar after 4 seconds', () => {
      component.graphRagQuery = 'duration check';
      component.runGraphRagQuery();

      httpMock.expectOne((r) => r.url.includes('/rag/test/graph/query')).flush({
        query: 'duration check', searchType: 'LOCAL', k: 5, conversationId: 'test',
        available: true, durationMs: 10
      });

      const call = spies.snackBarSpy.open.calls.mostRecent();
      expect(call.args[2].duration).toBe(4000);
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // 20. EDGE CASES & BOUNDARY CONDITIONS
  // ─────────────────────────────────────────────────────────────────────────────

  describe('Edge cases and boundary conditions', () => {
    beforeEach(() => {
      fixture.detectChanges();
      flushNgOnInit();
    });

    it('should not make query request when query is only whitespace (hybrid mode)', () => {
      component.query = '    ';
      component.useHybrid = true;
      component.runQuery();

      httpMock.expectNone((r) => r.url.includes('/rag/test/hybrid'));
    });

    it('should handle queryResponse with empty results array gracefully', () => {
      component.queryResponse = { query: 'q', maxResults: 5, threshold: 0, results: [], totalHits: 0 };
      expect(component.getKeywordResults()).toBeNull();
      expect(component.getSemanticResults()).toBeNull();
    });

    it('should handle rerankingResponse with undefined reranked hits', () => {
      component.rerankingResponse = {
        query: 'q', maxResults: 5, threshold: 0,
        initialDurationMs: 10, initialCount: 0, initialHits: [],
        rerankerType: 'none', rerankerDescription: '', reranked: false,
        rerankDurationMs: 0, rerankedHits: [], totalHits: 0
      };
      // Accessing rank change helpers on an empty response should not throw
      expect(() => component.getRankChangeClass(undefined)).not.toThrow();
      expect(() => component.getRankChangeIcon(undefined)).not.toThrow();
      expect(() => component.formatRankChange(undefined)).not.toThrow();
    });

    it('should handle graph RAG query with whitespace-only text', () => {
      component.graphRagQuery = '   \t  ';
      component.runGraphRagQuery();

      httpMock.expectNone((r) => r.url.includes('/rag/test/graph/query'));
      expect(spies.snackBarSpy.open).toHaveBeenCalledWith(
        'Please enter a query', 'Close', jasmine.any(Object)
      );
    });

    it('should handle embed with whitespace-only text', () => {
      component.embedText = '  \n  ';
      component.testEmbedding();

      httpMock.expectNone((r) => r.url.includes('/rag/test/embed'));
      expect(spies.snackBarSpy.open).toHaveBeenCalledWith(
        'Please enter text to embed', 'Close', jasmine.any(Object)
      );
    });

    it('should handle multiple toggles of the same hit ID as a toggle cycle', () => {
      // Start collapsed
      expect(component.isHitExpanded('hit-abc')).toBeFalse();

      component.toggleHitExpansion('hit-abc');
      expect(component.isHitExpanded('hit-abc')).toBeTrue();

      component.toggleHitExpansion('hit-abc');
      expect(component.isHitExpanded('hit-abc')).toBeFalse();

      component.toggleHitExpansion('hit-abc');
      expect(component.isHitExpanded('hit-abc')).toBeTrue();
    });

    it('should not throw when ngOnDestroy is called before ngOnInit', () => {
      // Create a fresh component without calling fixture.detectChanges()
      const freshFixture = TestBed.createComponent(RagTesterComponent);
      const freshComponent = freshFixture.componentInstance;
      expect(() => freshComponent.ngOnDestroy()).not.toThrow();
      // Fresh component triggers no HTTP requests so just let it go
      // (afterEach verify() will catch unexpected calls)
      httpMock.match(() => true); // consume any stray requests
    });

    it('getNumberParams() returns empty array when selected reranker type has no parameters', () => {
      component.rerankerInfo = {
        available: true,
        types: [{ id: 'custom', name: 'Custom', description: '', parameters: [], supported: true }]
      };
      component.selectedRerankerType = 'custom';
      expect(component.getNumberParams()).toEqual([]);
    });
  });
});
