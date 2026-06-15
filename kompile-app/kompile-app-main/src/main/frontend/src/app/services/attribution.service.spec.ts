/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */

import { TestBed } from '@angular/core/testing';
import { HttpClientTestingModule, HttpTestingController } from '@angular/common/http/testing';
import { AttributionService } from './attribution.service';
import { backendUrl } from './base.service';

describe('AttributionService', () => {
  let service: AttributionService;
  let httpMock: HttpTestingController;
  const apiBase = `${backendUrl}/attribution`;

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [HttpClientTestingModule],
      providers: [AttributionService]
    });
    service = TestBed.inject(AttributionService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    httpMock.verify();
  });

  // ── explain() ──────────────────────────────────────────────────────────────

  describe('explain()', () => {
    it('should POST to /attribution/explain', (done) => {
      const request = {
        targetNodeId: 'node-1',
        question: 'Why did this happen?',
        maxDepth: 3,
        maxChains: 5,
        useLlm: true,
        includeCounterfactuals: true
      };
      const mockResult = {
        targetNodeId: 'node-1',
        targetTitle: 'Test Event',
        chains: [{ chainId: 'c1', targetEventNodeId: 'node-1', targetEventTitle: 'Test',
                    rootCauseNodeId: 'node-0', rootCauseTitle: 'Root', hops: [],
                    overallConfidence: 0.8, confidenceBand: 'HIGH', depth: 2 }],
        synthesizedExplanation: 'Test explanation',
        influenceScores: { 'node-0': 0.9 },
        counterfactuals: [{ removedNodeId: 'node-0', removedNodeTitle: 'Root',
                            targetStillReachable: false, survivingChainCount: 0,
                            confidenceDelta: -0.8, necessaryCause: true }],
        deadEnds: [],
        computedAt: '2025-01-01T00:00:00Z',
        computationTimeMs: 120,
        nodesVisited: 15,
        edgesExamined: 22,
        llmUsed: true
      };

      service.explain(request).subscribe(res => {
        expect(res.targetNodeId).toBe('node-1');
        expect(res.chains.length).toBe(1);
        expect(res.synthesizedExplanation).toBe('Test explanation');
        expect(res.counterfactuals.length).toBe(1);
        expect(res.nodesVisited).toBe(15);
        expect(res.llmUsed).toBeTrue();
        done();
      });

      const req = httpMock.expectOne(`${apiBase}/explain`);
      expect(req.request.method).toBe('POST');
      expect(req.request.body.targetNodeId).toBe('node-1');
      expect(req.request.body.useLlm).toBeTrue();
      req.flush(mockResult);
    });
  });

  // ── explainQuick() ─────────────────────────────────────────────────────────

  describe('explainQuick()', () => {
    it('should GET /attribution/explain/:nodeId with default params', (done) => {
      const mockResult = {
        targetNodeId: 'node-1', targetTitle: 'Event', chains: [],
        influenceScores: {}, counterfactuals: [], deadEnds: [],
        computedAt: '2025-01-01T00:00:00Z', computationTimeMs: 50,
        nodesVisited: 5, edgesExamined: 8, llmUsed: false
      };

      service.explainQuick('node-1').subscribe(res => {
        expect(res.targetNodeId).toBe('node-1');
        expect(res.llmUsed).toBeFalse();
        done();
      });

      const req = httpMock.expectOne(r =>
        r.url === `${apiBase}/explain/node-1` && r.method === 'GET'
      );
      req.flush(mockResult);
    });

    it('should pass all optional query params', (done) => {
      const mockResult = {
        targetNodeId: 'node-1', targetTitle: 'Event', chains: [],
        influenceScores: {}, counterfactuals: [], deadEnds: [],
        computedAt: '2025-01-01T00:00:00Z', computationTimeMs: 80,
        nodesVisited: 10, edgesExamined: 14, llmUsed: true
      };

      service.explainQuick('node-1', {
        question: 'What caused this?',
        factSheetId: 42,
        maxDepth: 5,
        maxChains: 10,
        useLlm: true,
        includeCounterfactuals: true
      }).subscribe(res => {
        expect(res.nodesVisited).toBe(10);
        done();
      });

      const req = httpMock.expectOne(r =>
        r.url === `${apiBase}/explain/node-1` &&
        r.params.get('question') === 'What caused this?' &&
        r.params.get('factSheetId') === '42' &&
        r.params.get('maxDepth') === '5' &&
        r.params.get('maxChains') === '10' &&
        r.params.get('useLlm') === 'true' &&
        r.params.get('includeCounterfactuals') === 'true'
      );
      expect(req.request.method).toBe('GET');
      req.flush(mockResult);
    });

    it('should URL-encode special characters in nodeId', (done) => {
      const mockResult = {
        targetNodeId: 'node/special', targetTitle: 'S', chains: [],
        influenceScores: {}, counterfactuals: [], deadEnds: [],
        computedAt: '2025-01-01T00:00:00Z', computationTimeMs: 10,
        nodesVisited: 1, edgesExamined: 0, llmUsed: false
      };

      service.explainQuick('node/special').subscribe(res => {
        expect(res.targetNodeId).toBe('node/special');
        done();
      });

      const req = httpMock.expectOne(r =>
        r.url === `${apiBase}/explain/node%2Fspecial`
      );
      req.flush(mockResult);
    });
  });

  // ── predict() ──────────────────────────────────────────────────────────────

  describe('predict()', () => {
    it('should POST to /attribution/predict', (done) => {
      const request = {
        sourceNodeId: 'node-1',
        context: 'What happens next?',
        maxDepth: 3,
        maxPredictions: 10,
        useLlm: true
      };
      const mockResult = {
        sourceNodeId: 'node-1',
        sourceTitle: 'Source Event',
        predictions: [{
          nodeId: 'node-2', title: 'Predicted Event', probability: 0.75,
          hopsFromSource: 2, pathFromSource: ['node-1', 'node-3', 'node-2'],
          pathEdgeTypes: ['CAUSES', 'TRIGGERS'], evidence: []
        }],
        synthesizedForecast: 'Likely outcome',
        computedAt: '2025-01-01T00:00:00Z',
        computationTimeMs: 90,
        nodesVisited: 12,
        llmUsed: true
      };

      service.predict(request).subscribe(res => {
        expect(res.sourceNodeId).toBe('node-1');
        expect(res.predictions.length).toBe(1);
        expect(res.predictions[0].probability).toBe(0.75);
        expect(res.predictions[0].pathFromSource.length).toBe(3);
        expect(res.predictions[0].pathEdgeTypes).toEqual(['CAUSES', 'TRIGGERS']);
        expect(res.synthesizedForecast).toBe('Likely outcome');
        done();
      });

      const req = httpMock.expectOne(`${apiBase}/predict`);
      expect(req.request.method).toBe('POST');
      expect(req.request.body.sourceNodeId).toBe('node-1');
      expect(req.request.body.useLlm).toBeTrue();
      req.flush(mockResult);
    });
  });

  // ── predictQuick() ─────────────────────────────────────────────────────────

  describe('predictQuick()', () => {
    it('should GET /attribution/predict/:nodeId with default params', (done) => {
      const mockResult = {
        sourceNodeId: 'node-1', sourceTitle: 'Source', predictions: [],
        computedAt: '2025-01-01T00:00:00Z', computationTimeMs: 30,
        nodesVisited: 3, llmUsed: false
      };

      service.predictQuick('node-1').subscribe(res => {
        expect(res.sourceNodeId).toBe('node-1');
        expect(res.predictions.length).toBe(0);
        done();
      });

      const req = httpMock.expectOne(r =>
        r.url === `${apiBase}/predict/node-1` && r.method === 'GET'
      );
      req.flush(mockResult);
    });

    it('should pass all optional query params', (done) => {
      const mockResult = {
        sourceNodeId: 'node-1', sourceTitle: 'Source',
        predictions: [{ nodeId: 'n2', title: 'P', probability: 0.6,
                         hopsFromSource: 1, pathFromSource: ['node-1', 'n2'],
                         pathEdgeTypes: ['CAUSES'], evidence: [] }],
        computedAt: '2025-01-01T00:00:00Z', computationTimeMs: 60,
        nodesVisited: 8, llmUsed: true
      };

      service.predictQuick('node-1', {
        context: 'Market risk',
        factSheetId: 7,
        maxDepth: 4,
        maxPredictions: 20,
        useLlm: true
      }).subscribe(res => {
        expect(res.predictions.length).toBe(1);
        done();
      });

      const req = httpMock.expectOne(r =>
        r.url === `${apiBase}/predict/node-1` &&
        r.params.get('context') === 'Market risk' &&
        r.params.get('factSheetId') === '7' &&
        r.params.get('maxDepth') === '4' &&
        r.params.get('maxPredictions') === '20' &&
        r.params.get('useLlm') === 'true'
      );
      expect(req.request.method).toBe('GET');
      req.flush(mockResult);
    });
  });
});
